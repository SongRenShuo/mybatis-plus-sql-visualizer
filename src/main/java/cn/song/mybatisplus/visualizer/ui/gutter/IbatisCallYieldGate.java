package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.ui.nav.IbatisXmlStatementTargets;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethodCallExpression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * T-IbatisFix3 缺陷一：<b>iBatis 优先、JDBC 模板让位</b>的 O(1) 判据
 * （家族排除制防双图标，第二处应用）。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>{@code JdbcTemplateMethodDetector.JDBC_TEMPLATE_METHODS} 里的 {@code queryForObject} /
 * {@code queryForList} / {@code queryForMap} / {@code update} / {@code delete} 与 iBatis
 * {@code SqlMapExecutor} 的入口方法名<b>完全重叠</b>（iBatis 2 的 DAO 调用形态正是
 * {@code client.queryForObject("ns.id", p)}）。本检测器只判方法名、不判接收者类型，于是同一次调用
 * 被两条 gutter 通道同时认领：JdbcTemplateSqlGutterLineMarkerProvider（预览图标）与
 * IbatisJavaToXmlLineMarkerProvider（Java → XML 跳转箭头）——两者锚在同一枚方法名 identifier 上，
 * 平台按锚点并排渲染两枚图标（用户截图：UserDaoImpl.java 32/37/42/47/52 行各两枚）。
 *
 * <h2>判据口径（为什么形状判据就够）</h2>
 *
 * <p>iBatis 通道的认领条件是「方法名 ∈ IbatisXmlStatementTargets.STATEMENT_ENTRY_METHODS +
 * 首参是 String 字面量 + 目标语句<b>存在</b>」。本判据取前两条的<b>形状超集</b>，去掉存在性校验：
 * 首参字面量必须是 {@code id} / {@code ns.id} 这种<b>标识符形态</b>
 * （{@code [A-Za-z_$][\w$]*(.[A-Za-z_$][\w$]*)*}）。SQL 文本必然含空格/括号/{@code *}/{@code ?}
 * 等字符，形状上不可能命中，所以：真正的 JDBC 模板调用
 * （{@code queryForList("SELECT … WHERE id = ?")}）<b>照常</b>出预览图标；每条可能被 iBatis 认领的
 * 调用都被本判据覆盖（形状是 iBatis 认领条件的超集），双图标在结构上消失。
 *
 * <p>存在性校验刻意留在 iBatis 侧（它已有 resolveStatementAnchors 兜底）：本判据若也查存在性，
 * 就要进索引/项目扫描——违反 gutter 热路径红线。
 *
 * <h2>性能红线</h2>
 *
 * <p>全路径只有「已完成的两次 parent 上跳 + 一个字面量 value 读 + 一次短正则」——
 * 零 resolve、零 PSI 子树遍历、零索引访问。仅对 5 个重叠方法名 + 无其它类型判定的调用触发
 * （方法名不在重叠集时首行即返回）。
 *
 * <h2>代价（已知且刻意接受）</h2>
 *
 * <p>非 iBatis 工程里 {@code update("refreshCache")} 这类「首参是标识符形态字符串、且不在 SQL
 * 白名单方法语义内」的调用会一并让位（预览图标消失）。该形态的字符串本就不是 SQL（点击期的
 * {@code JdbcTemplateSqlPreviewService.isLikelySql} 也会拒绝），即「本来点开也只会得到未找到提示」，
 * 让位无功能损失。</p>
 */
public enum IbatisCallYieldGate {
    /** 单例实例（Kotlin object 语义兼容）。 */
    INSTANCE;

    /** {@code id} / {@code ns.id} / {@code a.b.c} 形态（iBatis statementId 的语法字符集：标识符段 + 点）。 */
    private static final Pattern STATEMENT_ID_SHAPE =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    /**
     * 该调用是否是「iBatis {@code SqlMapExecutor} 入口形态」（判定即让位）。
     *
     * @param call       方法调用表达式（调用方已完成 methodExpression/grandParent 判定）
     * @param methodName 方法名（调用方已判定的 {@code element.text}，避免重复取）
     */
    public boolean isIbatisStatementIdCall(@NotNull PsiMethodCallExpression call, @NotNull String methodName) {
        // 方法名不在 iBatis 入口集 → 两条通道本就不重叠（O(1) 集合判定）
        if (!IbatisXmlStatementTargets.STATEMENT_ENTRY_METHODS.contains(methodName)) {
            return false;
        }
        // 首参必须是 String 字面量（与 iBatis 通道同口径；非字面量形态 iBatis 也不认领）
        var expressions = call.getArgumentList().getExpressions();
        if (!(expressions.length > 0 && expressions[0] instanceof PsiLiteralExpression literal)) {
            return false;
        }
        Object value = literal.getValue();
        return isStatementIdShape(value instanceof String s ? s : null);
    }

    /** statementId 字面量形状判定（{@code id} / {@code ns.id}；纯函数，单测正负例直调）。 */
    public boolean isStatementIdShape(@Nullable String raw) {
        String value = raw != null ? raw.trim() : null;
        if (value == null || value.isEmpty()) {
            return false;
        }
        return STATEMENT_ID_SHAPE.matcher(value).matches();
    }
}
