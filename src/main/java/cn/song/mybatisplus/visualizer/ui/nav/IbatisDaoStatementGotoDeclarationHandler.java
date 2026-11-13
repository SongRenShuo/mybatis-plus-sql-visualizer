// T-SyncK2JMaster183 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/nav/IbatisDaoStatementGotoDeclarationHandler.kt 移植
package cn.song.mybatisplus.visualizer.ui.nav;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * T-Ibatis2Support 券 F（车道 L8）：<b>Java DAO → iBatis XML 语句</b>跳转接入 IDEA 原生
 * Ctrl+B / Ctrl+Click（{@code GotoDeclaration}）。
 *
 * <h2>为什么是 {@code GotoDeclarationHandler} 而不是给 Java 侧造 {@code PsiReference}</h2>
 *
 * <p>与 {@link MapperNavigationGotoDeclarationHandler} 同款理由：平台顺序是 handler 优先、返回 null 才回落
 * 原生（{@code GotoDeclarationUtil.findTargetElementsFromProviders} 只遍历 handler，首个非空即返回）。
 * 因此「非 iBatis DAO 调用位置返回 null」即可保证原生跳转零变化。handler 只在用户按键时被调用，
 * <b>不进 daemon / 索引热路径</b>（Java 文件量远大于 XML，给每个方法名造引用会在高亮侧反复付成本）。</p>
 *
 * <h2>识别口径（jar 实测，非推测）</h2>
 *
 * <p>入口方法集取自 {@code com.ibatis.sqlmap.client.SqlMapExecutor}（{@code org.apache.ibatis:ibatis-sqlmap:2.3.4.726}
 * 的 {@code javap} 实测：{@code insert(String[,Object])} / {@code update(String[,Object])} / {@code delete(String[,Object])} /
 * {@code queryForObject(String[,Object[,Object]])} / {@code queryForList(String[,Object[,int,int]] | String,int,int)} /
 * {@code queryWithRowHandler(String[,Object],RowHandler)} / {@code queryForPaginatedList(String[,Object],int | String,int)} /
 * {@code queryForMap(String,Object,String[,String])}）——这一批的 <b>statementId 都是第一参数</b>，故取
 * <b>首参且必须是 String 字面量</b>（见 {@link #statementIdOf} 注释：不在实参表里搜字面量，避免第二参误判）。
 * {@code startBatch()} / {@code executeBatch()} / {@code executeBatchDetailed()} 不吃 statementId，故不在表内。</p>
 *
 * <p>判定是<b>语法级</b>（方法名 + 首参字面量），不做接收者类型解析——仓内零 {@code SqlMapClient} 引用可参照，
 * 且 real 工程的 {@code SqlMapClient} 常经 Spring 代理注入、类型不可解析。<b>误报由目标存在性兜底</b>：
 * 目标 sqlMap 里没有该语句 → 返回 null → 不接管（普通 Java 类上的同名调用因此零影响）。</p>
 *
 * <h2>解析与落点</h2>
 *
 * <p>解析集中在 {@link IbatisXmlStatementTargets}（{@code ns.id} 按 namespace 匹配 sqlMap 文件；裸 id 全项目语句表），
 * 落点 = 命中语句标签的 <b>{@code id} 属性值元素</b>（与 {@code ui/ref/MybatisXmlTargets.findIdAttributeInFile} 同口径）。</p>
 *
 * <h2>线程</h2>
 *
 * <p>{@code getGotoDeclarationTargets} 在 EDT 上被调用。按 {@code docs/架构设计/PSI跨线程读取协议.md} 形态 A
 * （EDT 直读）本可直接读，但本类仍按仓内红线把 PSI 读取整体包进 {@link ReadAction#compute}
 * （EDT 内联执行，零额外成本）。任何异常按「本次不接管」处理：回落原生跳转，不弹提示、不写 ERROR。</p>
 *
 * <h2>覆盖边界</h2>
 *
 * <ul>
 * <li>Kotlin DAO 调用形态（{@code SqlMapClient} 的 Kotlin 侧调用）未覆盖：本期验收只要求 Java 侧；</li>
 * <li>反方向（XML 语句 → Java 调用点）<b>已拆到</b> {@link IbatisXmlStatementGotoDeclarationHandler}
 *     （T-IbatisFix4）：两个方向各占一个 {@code gotoDeclarationHandler}，互不干扰、互不抢认领；</li>
 * <li>{@code <selectKey>} 生成的隐式 id（{@code <insertId>-SelectKey}）不做。</li>
 * </ul>
 */
public class IbatisDaoStatementGotoDeclarationHandler implements GotoDeclarationHandler {

    /** 祖先爬升上限（literal → 实参表 → 调用只需 3 层；留余量给括号/强制转换）。 */
    private static final int MAX_UP = 6;

    /**
     * {@code SqlMapExecutor} 里吃 statementId 的入口方法——单一事实源在
     * {@link IbatisXmlStatementTargets#STATEMENT_ENTRY_METHODS}（券 B 起跳转图标对与本 handler 共用；
     * 此前本类持有私有副本，清单漂移时会出现「Ctrl+B 认得、箭头不认得」的分裂形态）。
     */
    private static final Set<String> STATEMENT_ENTRY_METHODS = IbatisXmlStatementTargets.STATEMENT_ENTRY_METHODS;

    /**
     * T-M52 同款注记：master 侧 {@code @Suppress("DEPRECATION")}——{@code ReadAction.compute(ThrowableComputable)}
     * 在 262 标 deprecated；243 编译目标仅此重载可用。Java 侧沿用该重载。
     */
    @Override
    @Nullable
    public PsiElement[] getGotoDeclarationTargets(
            @Nullable PsiElement sourceElement,
            int offset,
            @Nullable Editor editor) {
        PsiElement element = sourceElement;
        if (element == null) {
            return null;
        }
        try {
            // master 侧 runCatching 降级（任何异常按「本次不接管」处理）→ try/catch 等义
            return ReadAction.compute(() -> targetsFor(element));
        } catch (Throwable e) {
            return null;
        }
    }

    @Nullable
    private static PsiElement[] targetsFor(@NotNull PsiElement element) {
        String statementId = statementIdOf(element);
        if (statementId == null) {
            return null;
        }
        if (DumbService.isDumb(element.getProject())) {
            return null;
        }
        List<XmlAttributeValue> anchors = IbatisXmlStatementTargets.resolveStatementAnchors(
            element.getProject(),
            statementId,
            ownerNamespace(element)
        );
        return anchors.isEmpty() ? null : anchors.toArray(new PsiElement[0]);
    }

    /**
     * 光标元素向上找到的最近 {@code queryForObject(...)} 这类调用，取<b>首个实参</b>（必须是 String 字面量）。
     *
     * <p>只认首参而不在实参表里搜字面量：{@code javap} 实测 {@code SqlMapExecutor} 全部重载的 statementId 都在
     * 第一参数位；搜字面量会把 {@code queryForObject(param, "Tom")} 的第二参误当 statementId。</p>
     */
    @Nullable
    private static String statementIdOf(@NotNull PsiElement element) {
        PsiMethodCallExpression call = nearestCall(element);
        if (call == null) {
            return null;
        }
        String name = call.getMethodExpression().getReferenceName();
        if (name == null || !STATEMENT_ENTRY_METHODS.contains(name)) {
            return null;
        }
        com.intellij.psi.PsiExpression[] expressions = call.getArgumentList().getExpressions();
        if (expressions.length == 0 || !(expressions[0] instanceof PsiLiteralExpression literal)) {
            return null;
        }
        Object value = literal.getValue();
        if (!(value instanceof String s)) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Nullable
    private static PsiMethodCallExpression nearestCall(@NotNull PsiElement element) {
        PsiElement current = element;
        int depth = 0;
        while (current != null && depth++ < MAX_UP) {
            if (current instanceof PsiMethodCallExpression call) {
                return call;
            }
            current = current.getParent();
        }
        return null;
    }

    /** 引用点所属类的限定名（裸 id 的「同 namespace 优先」排序偏好；取不到为 null）。 */
    @Nullable
    private static String ownerNamespace(@NotNull PsiElement element) {
        PsiClass owner = PsiTreeUtil.getParentOfType(element, PsiClass.class);
        return owner != null ? owner.getQualifiedName() : null;
    }
}
