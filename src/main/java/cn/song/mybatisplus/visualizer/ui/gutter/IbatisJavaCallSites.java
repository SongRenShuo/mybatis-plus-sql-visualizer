// T-SyncK2JMaster183 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/IbatisXmlToJavaLineMarkerProvider.kt 移植（拆件：internal object IbatisJavaCallSites）
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
 * <p>在项目里找「{@code SqlMapExecutor} 入口方法调用 + 首参 String 字面量值 == {@code ns.id} 或裸 {@code id}」的
 * 调用点字面量。只服务点击/跳转（不在 daemon 任何路径调用），但实现仍取<b>词索引</b>
 * （{@link PsiSearchHelper#processElementsWithWord}）而不是全项目字面量遍历——真实工程 Java 文件量
 * 远大于 XML，点击延迟也要可接受。词取 <b>statementId 的最后一段</b>（点号前缀/无点号两种形态
 * 的字面量都含该词）；dumb 态索引不可用 → 返回 null（无动作）。</p>
 *
 * <p>判定语义与 L8 handler
 * （{@code cn.song.mybatisplus.visualizer.ui.nav.IbatisDaoStatementGotoDeclarationHandler}）
 * 反向对齐：入口方法集同一批（{@code javap} 实测 statementId 都在第一参数位）；只认<b>首参</b>，
 * 不在实参表里搜字面量。找不到返回 null，绝不抛穿。</p>
 */
public final class IbatisJavaCallSites {

    private IbatisJavaCallSites() {
    }

    /** 首个命中字面量（{@code ns.id} / 裸 {@code id} / 任意 namespace 前缀的 {@code .id} 尾缀形态）。 */
    @Nullable
    public static PsiLiteralExpression findFirstCallLiteral(@NotNull Project project,
                                                            @Nullable String namespace,
                                                            @NotNull String statementId) {
        List<PsiLiteralExpression> hits = findAllCallLiterals(project, namespace, statementId, 1);
        return hits.isEmpty() ? null : hits.get(0);
    }

    /**
     * 全部命中字面量（T-IbatisFix4：XML 语句 {@code id} 的 Ctrl+Click 目标集）。
     *
     * <p>判定与 {@link #findFirstCallLiteral} 完全同源（同一个词索引 + 同一套 {@code matches} 口径），差别只是
     * 不提前收工。{@code limit} 防「一条语句几百个调用点」把 GTD 目标表撑爆——平台拿到多目标会弹
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
        String word = substringAfterLastDot(sid);
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
        // （多为字面量本身或其内部 leaf），手工上爬找宿主字面量
        TextOccurenceProcessor processor = (element, offsetInElement) -> {
            PsiElement cur = element;
            PsiLiteralExpression literal = null;
            while (cur != null) {
                if (cur instanceof PsiLiteralExpression candidate) {
                    literal = candidate;
                    break;
                }
                cur = cur.getParent();
            }
            if (literal == null || !matches(literal, namespace, sid)) {
                return true;
            }
            PsiMethodCallExpression call = PsiTreeUtil.getParentOfType(literal, PsiMethodCallExpression.class);
            if (call == null) {
                return true;
            }
            String callName = call.getMethodExpression().getReferenceName();
            if (callName == null || !IbatisXmlStatementTargets.STATEMENT_ENTRY_METHODS.contains(callName)) {
                return true;
            }
            com.intellij.psi.PsiExpression[] expressions = call.getArgumentList().getExpressions();
            if (expressions.length == 0 || expressions[0] != literal) {
                return true;
            }
            // 同一字面量的多个词命中（字面量内含点号分段）只记一次
            if (found.contains(literal)) {
                return true;
            }
            found.add(literal);
            return found.size() < limit;
        };
        try {
            // master 侧 runCatching 降级（索引异常不抛穿）→ try/catch 等义
            PsiSearchHelper.getInstance(project).processElementsWithWord(
                processor,
                GlobalSearchScope.projectScope(project),
                word,
                UsageSearchContext.IN_STRINGS,
                true
            );
        } catch (Throwable ignored) {
            // 失败退化为「本次无目标」
        }
        return found;
    }

    /** 字面量值命中：裸 {@code id}、{@code ns.id}（本文件 namespace）、或任意前缀的尾段 {@code … . id}。 */
    private static boolean matches(@NotNull PsiLiteralExpression literal,
                                   @Nullable String namespace,
                                   @NotNull String statementId) {
        Object value = literal.getValue();
        String text = value instanceof String s ? s.trim() : null;
        if (text == null) {
            return false;
        }
        if (text.equals(statementId)) {
            return true;
        }
        if (namespace != null && text.equals(namespace + "." + statementId)) {
            return true;
        }
        return text.endsWith("." + statementId) && text.length() > statementId.length() + 1;
    }

    /** Kotlin {@code substringAfterLast('.')} 等义。 */
    @NotNull
    private static String substringAfterLastDot(@NotNull String value) {
        int dot = value.lastIndexOf('.');
        return dot < 0 ? value : value.substring(dot + 1);
    }
}
