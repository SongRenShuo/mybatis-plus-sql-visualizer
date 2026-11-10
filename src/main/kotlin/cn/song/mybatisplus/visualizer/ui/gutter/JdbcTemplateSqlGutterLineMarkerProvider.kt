package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateMethodDetector
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlLocator
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlPreviewService
import cn.song.mybatisplus.visualizer.psi.PreviewReceiverResolver
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.ui.nav.IbatisXmlStatementTargets
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * JdbcTemplate / NamedParameterJdbcTemplate SQL 字符串预览水印图标（JDBC 模板 SQL 字符串预览）。
 *
 * 在 `queryForList` / `update` / `queryForObject` / `execute` 等 JDBC 模板方法调用行显示预览图标，
 * 点击弹出 SQL 预览（复用 [SqlPreviewService] 弹窗链路，支持命名参数回填）。
 *
 * **性能红线**：快速路径只做 O(1) 语法判定——锚点为方法名 [PsiIdentifier]，方法名 ∈
 * [JdbcTemplateMethodDetector.JDBC_TEMPLATE_METHODS] 且第 1 实参存在；零 resolve、零聚合、
 * 零转换；完整解析（字符串聚合 / 命名参数识别）仅在点击时走 [SqlPreviewService.previewAsync]。
 *
 * **去重保证**：与既有 [cn.song.mybatisplus.visualizer.ui.gutter.SqlGutterLineMarkerProvider]
 * （Wrapper 链）、[AnnotationSqlGutterLineMarkerProvider]（@Select 注解）、
 * [CustomDaoHqlGutterLineMarkerProvider]（自定义 DAO HQL）互不冲突——方法名集合天然区分
 * （`query*` 是 Spring JDBC 模板方法名，`createQuery`/`findByQuery` 是 Hibernate / 自定义 DAO）。
 *
 * 注册见 plugin.xml `<codeInsight.lineMarkerProvider language="JAVA" .../>`。
 */
class JdbcTemplateSqlGutterLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // LineMarker 框架只传递 leaf 元素：锚点为 PsiIdentifier（方法名 leaf）。
        if (element !is PsiIdentifier) return null

        // 锚定在方法调用的方法名 PsiIdentifier 上：
        // element = "queryForList" 等方法名 identifier
        // element.parent = PsiReferenceExpression（方法调用的方法名引用）
        // element.parent.parent = PsiMethodCallExpression
        val parent = element.parent as? PsiReferenceExpression ?: return null
        val grandParent = parent.parent as? PsiMethodCallExpression ?: return null

        // 确认 element 是方法名而非 qualifier
        if (grandParent.methodExpression.referenceName != element.text) return null

        // 方法名 ∈ 字符串 SQL 方法候选（Spring JDBC 模板 ∪ 自研 DAO select 族，O(1) 判定，T-DaoSql-01）
        if (!JdbcTemplateMethodDetector.isStringSqlMethodCandidate(element.text)) return null

        // T-IbatisFix3 缺陷一：iBatis 优先、JDBC 模板让位——`SqlMapExecutor` 的入口名
        // （queryForObject/queryForList/queryForMap/update/delete）与本 provider 的白名单**完全重叠**，
        // 且本 provider 不查接收者类型；`client.queryForObject("User.selectById", id)` 于是被两条通道
        // 同时认领（本 provider 的预览图标 + IbatisJavaToXmlLineMarkerProvider 的跳转箭头叠在同一枚
        // 方法名 identifier 上 → 用户看到两个图标）。让位判据是**纯语法 O(1)**（见
        // [IbatisCallYieldGate.isIbatisStatementIdCall]）：零 resolve、零项目扫描、零类型推断。
        if (IbatisCallYieldGate.isIbatisStatementIdCall(grandParent, element.text)) return null

        // T-GutterDedup + T-DaoSql-03：家族排除制守卫——CUSTOM_DAO / HIBERNATE_TEMPLATE / JPA_SESSION
        // 各有专属 provider（防双图标），拒绝；JDBC_TEMPLATE 与 UNKNOWN（自研框架 `beanCruder` 等
        // 无法归类接收者）通过。误标由「存在字符串形态实参」+ 点击时 isLikelySql 双层拦截。
        when (PreviewReceiverResolver.classify(grandParent)) {
            PreviewReceiverResolver.Family.CUSTOM_DAO,
            PreviewReceiverResolver.Family.HIBERNATE_TEMPLATE,
            PreviewReceiverResolver.Family.JPA_SESSION,
            -> return null
            else -> {}
        }

        // T-DaoSql-02：存在字符串形态实参（自研框架 (Class, sql, ...) 形态 SQL 在第 2 参），
        // 杜绝 `execute(callback)` / MP select 族（Wrapper 实参）误标
        if (!PreviewReceiverResolver.hasStringLikeArg(grandParent)) return null

        // 开关关闭时不显示。F36（T-PerfSweep16）：单次取 state——本 provider 在总闸之外还受
        // jdbcSqlPreviewEnabled 约束（原 gutterEnabled 语义等价保留：失败回退按 true），总闸/组显隐/
        // JDBC 预览开关共用这一次读取（原双路径每到达元素 3 次 app 级查找收敛为 1 次）
        val gutterState = runCatching { PluginSettings.getInstance().state }.getOrNull()
        if (gutterState != null && (!gutterState.showGutterIcon || !gutterState.jdbcSqlPreviewEnabled)) return null
        if (!GutterGroups.enabledFor(javaClass, gutterState)) return null // R2 分组显隐

        return LineMarkerInfo(
            element,
            element.textRange,
            SQL_PREVIEW_ICON,
            { MpSqlVisualizerBundle.message("gutter.jdbcTemplate.tooltip") },
            JdbcTemplateGutterNavigationHandler,
            GutterIconRenderer.Alignment.CENTER,
            { MpSqlVisualizerBundle.message("gutter.jdbcTemplate.accessible.name") },
        )
    }

    /**
     * 点击处理：在 EDT 捕获锚点元素，转换走后台线程 + ReadAction（EDT 禁止 PSI 慢操作），
     * 结果按显示方式配置展示。
     */
    private object JdbcTemplateGutterNavigationHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            val project: Project = elt.project
            SqlPreviewService.previewAsync(project, {
                val call = JdbcTemplateSqlLocator.locate(elt)
                if (call != null) {
                    JdbcTemplateSqlPreviewService.preview(call, elt)
                } else {
                    null
                }
            }) { result ->
                SqlPreviewService.showResult(project, result)
            }
        }
    }

    companion object {
        private val SQL_PREVIEW_ICON: Icon =
            IconLoader.getIcon("/icons/sqlGutter.svg", JdbcTemplateSqlGutterLineMarkerProvider::class.java)
    }
}

/**
 * T-IbatisFix3 缺陷一：**iBatis 优先、JDBC 模板让位**的 O(1) 判据（家族排除制防双图标，第二处应用）。
 *
 * ## 为什么需要它
 *
 * `JdbcTemplateMethodDetector.JDBC_TEMPLATE_METHODS` 里的 `queryForObject` / `queryForList` /
 * `queryForMap` / `update` / `delete` 与 iBatis `SqlMapExecutor` 的入口方法名**完全重叠**
 * （iBatis 2 的 DAO 调用形态正是 `client.queryForObject("ns.id", p)`）。本检测器只判方法名、
 * 不判接收者类型，于是同一次调用被两条 gutter 通道同时认领：
 * `JdbcTemplateSqlGutterLineMarkerProvider`（预览图标）与
 * `IbatisJavaToXmlLineMarkerProvider`（Java → XML 跳转箭头）——两者锚在同一枚方法名 identifier 上，
 * 平台按锚点并排渲染两枚图标（用户截图：`UserDaoImpl.java` 32/37/42/47/52 行各两枚）。
 *
 * ## 判据口径（为什么形状判据就够）
 *
 * iBatis 通道的认领条件是「方法名 ∈ [IbatisXmlStatementTargets.STATEMENT_ENTRY_METHODS] +
 * 首参是 String 字面量 + 目标语句**存在**」。本判据取前两条的**形状超集**，去掉存在性校验：
 * 首参字面量必须是 `id` / `ns.id` 这种**标识符形态**（`[A-Za-z_$][\w$]*(.[A-Za-z_$][\w$]*)*`）。
 * SQL 文本必然含空格/括号/`*`/`?` 等字符，形状上不可能命中，所以：
 *  - 真正的 JDBC 模板调用（`queryForList("SELECT … WHERE id = ?")`）**照常**出预览图标；
 *  - 每条可能被 iBatis 认领的调用都被本判据覆盖（形状是 iBatis 认领条件的超集），双图标在结构上消失。
 *
 * 存在性校验刻意留在 iBatis 侧（它已有 `resolveStatementAnchors` 兜底）：本判据若也查存在性，
 * 就要进索引/项目扫描——违反 gutter 热路径红线。
 *
 * ## 性能红线
 *
 * 全路径只有「已完成的两次 parent 上跳 + 一个字面量 `value` 读 + 一次短正则」——
 * 零 resolve、零 PSI 子树遍历、零索引访问。仅对 5 个重叠方法名 + 无其它类型判定的调用触发
 * （方法名不在重叠集时首行即返回）。
 *
 * ## 代价（已知且刻意接受）
 *
 * 非 iBatis 工程里 `update("refreshCache")` 这类「首参是标识符形态字符串、且不在 SQL 白名单方法
 * 语义内」的调用会一并让位（预览图标消失）。该形态的字符串本就不是 SQL（点击期的
 * `JdbcTemplateSqlPreviewService.isLikelySql` 也会拒绝），即「本来点开也只会得到未找到提示」，
 * 让位无功能损失。
 */
internal object IbatisCallYieldGate {

    /** `id` / `ns.id` / `a.b.c` 形态（iBatis statementId 的语法字符集：标识符段 + 点）。 */
    private val STATEMENT_ID_SHAPE = Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")

    /**
     * 该调用是否是「iBatis `SqlMapExecutor` 入口形态」（判定即让位）。
     *
     * @param call 方法调用表达式（调用方已完成 methodExpression/grandParent 判定）
     * @param methodName 方法名（调用方已判定的 `element.text`，避免重复取）
     */
    fun isIbatisStatementIdCall(call: PsiMethodCallExpression, methodName: String): Boolean {
        // 方法名不在 iBatis 入口集 → 两条通道本就不重叠（O(1) 集合判定）
        if (methodName !in IbatisXmlStatementTargets.STATEMENT_ENTRY_METHODS) return false
        // 首参必须是 String 字面量（与 iBatis 通道同口径；非字面量形态 iBatis 也不认领）
        val first = call.argumentList?.expressions?.firstOrNull() as? PsiLiteralExpression ?: return false
        return isStatementIdShape(first.value as? String)
    }

    /** statementId 字面量形状判定（`id` / `ns.id`；纯函数，单测正负例直调）。 */
    fun isStatementIdShape(raw: String?): Boolean {
        val value = raw?.trim() ?: return false
        if (value.isEmpty()) return false
        return STATEMENT_ID_SHAPE.matches(value)
    }
}
