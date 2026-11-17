package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.ui.nav.IbatisXmlStatementTargets;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.TextOccurenceProcessor;
import com.intellij.psi.search.UsageSearchContext;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * XML → Java 方向的<b>点击期</b>目标解析（T-Ibatis2Gap 券 B）。
 *
 * <p>在项目里找「{@code SqlMapExecutor} 入口方法调用 + 首参 String 字面量值 == {@code ns.id}
 * 或裸 {@code id}」的调用点字面量。只服务点击/跳转（不在 daemon 任何路径调用），但实现仍取
 * <b>词索引</b>（{@link PsiSearchHelper#processElementsWithWord}）而不是全项目字面量遍历——
 * 真实工程 Java 文件量远大于 XML，点击延迟也要可接受。词取 <b>statementId 的最后一段</b>
 * （点号前缀/无点号两种形态的字面量都含该词）；dumb 态索引不可用 → 返回 null（无动作）。</p>
 *
 * <p>判定语义与 L8 handler（IbatisDaoStatementGotoDeclarationHandler）反向对齐：入口方法集同一批
 * （javap 实测 statementId 都在第一参数位）；只认<b>首参</b>，不在实参表里搜字面量。
 * 找不到返回 null，绝不抛穿。</p>
 */
public final class IbatisJavaCallSites {

    private IbatisJavaCallSites() {
    }

    /** 首个命中字面量（「本文件 namespace.id」或裸「id」两种精确形态，见 {@link #matches}）。 */
    @Nullable
    public static PsiLiteralExpression findFirstCallLiteral(@NotNull Project project,
                                                            @Nullable String namespace,
                                                            @NotNull String statementId) {
        List<PsiLiteralExpression> all = findAllCallLiterals(project, namespace, statementId, 1);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * 全部命中字面量（T-IbatisFix4：XML 语句 {@code id} 的 Ctrl+Click 目标集）。
     *
     * <p>判定与 {@link #findFirstCallLiteral} 完全同源（同一个词索引 + 同一套 matches 口径），
     * 差别只是不提前收工。limit 防「一条语句几百个调用点」把 GTD 目标表撑爆——平台拿到多目标会弹
     * 候选列表，超出上限的尾部调用点不列。</p>
     */
    @NotNull
    public static List<PsiLiteralExpression> findAllCallLiterals(@NotNull Project project,
                                                                 @Nullable String namespace,
                                                                 @NotNull String statementId,
                                                                 int limit) {
        String sid = statementId.trim();
        if (sid.isEmpty() || limit <= 0) {
            return List.of();
        }
        int lastDot = sid.lastIndexOf('.');
        String word = lastDot >= 0 ? sid.substring(lastDot + 1) : sid;
        if (word.isEmpty()) {
            return List.of();
        }
        for (int i = 0; i < word.length(); i++) {
            if (!Character.isJavaIdentifierPart(word.charAt(i))) {
                return List.of();
            }
        }
        if (DumbService.isDumb(project)) {
            return List.of();
        }
        List<PsiLiteralExpression> found = new ArrayList<>(Math.min(limit, 4));
        // TextOccurenceProcessor.execute(element, offsetInElement)：element = 词命中的宿主元素
        //（多为字面量本身或其内部 leaf），手工上爬找宿主字面量
        TextOccurenceProcessor processor = (element, offsetInElement) -> {
            PsiLiteralExpression literal = null;
            PsiElement cur = element;
            while (cur != null) {
                if (cur instanceof PsiLiteralExpression candidate) {
                    literal = candidate;
                    break;
                }
                cur = cur.getParent();
            }
            if (literal == null) {
                return true;
            }
            if (!matches(literal, namespace, sid)) {
                return true;
            }
            PsiMethodCallExpression call =
                    PsiTreeUtil.getParentOfType(literal, PsiMethodCallExpression.class);
            if (call == null) {
                return true;
            }
            String methodName = call.getMethodExpression().getReferenceName();
            if (methodName == null || !IbatisXmlStatementTargets.STATEMENT_ENTRY_METHODS.contains(methodName)) {
                return true;
            }
            var expressions = call.getArgumentList().getExpressions();
            if (expressions.length == 0 || expressions[0] != literal) {
                return true;
            }
            // 同一字面量的多个词命中（字面量内含点号分段）只记一次
            for (PsiLiteralExpression existing : found) {
                if (existing == literal) {
                    return true;
                }
            }
            found.add(literal);
            return found.size() < limit;
        };
        try {
            PsiSearchHelper.getInstance(project).processElementsWithWord(
                    processor,
                    GlobalSearchScope.projectScope(project),
                    word,
                    UsageSearchContext.IN_STRINGS,
                    true);
        } catch (RuntimeException ignored) {
            // 词索引失败按「无命中」处理（点击期口径：找不到无动作，绝不抛穿）
        }
        return List.copyOf(found);
    }

    /**
     * 字面量值命中，<b>只认两种精确形态</b>：
     * <ul>
     * <li>裸 {@code id}：useStatementNamespaces=false（默认）时运行期 id 不带命名空间前缀；</li>
     * <li>「本文件 namespace.」+ id：true 时引擎按 {@code namespace + "." + id} 组装运行期 id
     *     （namespace 即 XML 根标签的 namespace 属性值，可含点，按字符串精确比较）。</li>
     * </ul>
     *
     * <p><b>禁止</b>再放宽成「任意前缀的尾段 {@code .id}」（T-IbatisXmlArrowNamespace，2026-11-16
     * 用户报障）：词索引按词取候选，多命名空间工程里同 id 遍地——demo 里 User.xml 的 selectById
     * 就被 OdLogistics60233.selectById 抢先命中，点箭头直接跳到 OdLogistics60233DaoImpl.java。
     * 命名空间是 iBatis 语句身份的一部分，跨命名空间的同名 id 是<b>另一条语句</b>，不得互相认领。</p>
     */
    private static boolean matches(@NotNull PsiLiteralExpression literal,
                                   @Nullable String namespace,
                                   @NotNull String statementId) {
        Object raw = literal.getValue();
        String value = raw instanceof String s ? s.trim() : null;
        if (value == null) {
            return false;
        }
        if (value.equals(statementId)) {
            return true;
        }
        return namespace != null && value.equals(namespace + "." + statementId);
    }

    /** 定位三态结果类型（语义见类头 {@link #lookupCallSite} KDoc）。 */
    public sealed interface CallLookup permits Found, CallAbsent, CallUnknown {
    }

    /** 找到调用点字面量（箭头该出）。 */
    public record Found(@NotNull PsiLiteralExpression literal) implements CallLookup {
    }

    /** 确定没有调用点（词索引查询成功执行）——箭头不该出。 */
    public enum CallAbsent implements CallLookup {
        ABSENT
    }

    /** 暂时判不了（dumb / 词不是合法 Java 标识符形态）——调用方保留既有判定。 */
    public enum CallUnknown implements CallLookup {
        UNKNOWN
    }

    /** 测试缝：替换三态查询（生产＝null）。矩阵用例用它驱动 CallUnknown / CallAbsent 分支。 */
    @Nullable
    public static volatile TestLookup lookupOverrideForTest;

    /** 三态查询的可替换形态（测试缝专用）。 */
    @FunctionalInterface
    public interface TestLookup {
        CallLookup apply(@NotNull Project project, @Nullable String namespace, @NotNull String statementId);
    }

    /**
     * 调用点存在性三态（供 gutter 判定；点击期仍走 {@link #findFirstCallLiteral}，
     * 两者同源同一个词索引查询）。
     *
     * <p>成本提示：底层是 {@code processElementsWithWord} 词索引查询，<b>常见词（如 selectAll）
     * 命中面较大</b>，故调用方（IbatisXmlToJavaLineMarkerProvider）按「命中入缓存 / 确认缺失收回
     * 缓存」处理，避免每趟慢 pass 对每条语句重复付费；另有 Registry 回退通道可整项关掉该判定。</p>
     */
    @NotNull
    public static CallLookup lookupCallSite(@NotNull Project project,
                                            @Nullable String namespace,
                                            @NotNull String statementId) {
        String sid = statementId.trim();
        if (sid.isEmpty()) {
            return CallAbsent.ABSENT;
        }
        TestLookup override = lookupOverrideForTest;
        if (override != null) {
            return override.apply(project, namespace, sid);
        }
        int lastDot = sid.lastIndexOf('.');
        String word = lastDot >= 0 ? sid.substring(lastDot + 1) : sid;
        if (word.isEmpty()) {
            return CallUnknown.UNKNOWN;
        }
        for (int i = 0; i < word.length(); i++) {
            if (!Character.isJavaIdentifierPart(word.charAt(i))) {
                return CallUnknown.UNKNOWN;
            }
        }
        if (DumbService.isDumb(project)) {
            return CallUnknown.UNKNOWN;
        }
        List<PsiLiteralExpression> all = findAllCallLiterals(project, namespace, sid, 1);
        return all.isEmpty() ? CallAbsent.ABSENT : new Found(all.get(0));
    }
}
