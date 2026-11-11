package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.IbatisXmlPreviewService
import cn.song.mybatisplus.visualizer.ui.nav.IbatisXmlStatementTargets
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiStatement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag

/**
 * T-IbatisCallPreview：iBatis 语句预览的**唯一装配点**。
 *
 * 三处入口共用同一份「语句标签 / statementId → [PreviewResult]」装配，产物逐字节同源：
 * - gutter 图标：`<sqlMap>` 语句行（[IbatisXmlGutterLineMarkerProvider]）与 Java DAO 调用行
 *   （[IbatisJavaToXmlLineMarkerProvider] 的预览图标）；
 * - 快捷键 / 右键：`actions/ConvertToSqlAction` 的 iBatis 回落两档；
 * - MCP：`mcp/McpSourceLocator` 的 sqlMap 语句与 Java 调用行分支。
 *
 * 「同一份装配」不是洁癖：iBatis 的 `#name#` 参数名、条件行、`<selectKey>` 块与 `$x$` 闸门都由
 * [IbatisXmlPreviewService] 建模，任何一处另写渲染都会让面板产物分叉（T-IbatisFix2/Fix3 的教训）。
 *
 * 本对象只做 PSI 读取与调用转接，**调用方必须持读锁**（gutter 点击在 `previewAsync` 的
 * `ReadAction.compute` 内；MCP 在 `ReadAction` 内；快捷键同样走 `previewAsync`）。
 */
internal object IbatisStatementPreview {

    /** 语句标签 → 预览产物（`fileText` 取标签所在文件，statementId 取裸 id——解析模型 id 即裸 id）。 */
    fun previewTag(tag: XmlTag): PreviewResult =
        IbatisXmlPreviewService.preview(
            fileText = tag.containingFile.text,
            statementId = tag.getAttributeValue("id"),
            statementTagName = tag.name,
            statementOrdinal = statementOrdinalOf(tag),
        )

    /**
     * `statementId` → 目标语句标签；解析复用 [IbatisXmlStatementTargets.resolveStatementAnchors]
     * （与跳转/GTD 同一份 `ns.id` / 裸 id 解析与 TTL memo），多目标取首个（与跳转箭头点击落点同口径）。
     */
    fun resolveStatementTag(project: Project, statementId: String, ownerNamespace: String?): XmlTag? =
        IbatisXmlStatementTargets
            .resolveStatementAnchors(project, statementId, ownerNamespace)
            .firstOrNull()
            ?.parent
            ?.parent as? XmlTag

    /**
     * `statementId` → 目标语句标签 → 预览产物；目标缺失返回「语句未找到」**可见失败**（不是 null）。
     *
     * 目标解析与多目标选择口径见 [resolveStatementTag]。
     */
    fun previewResolved(project: Project, statementId: String, ownerNamespace: String?): PreviewResult =
        resolveStatementTag(project, statementId, ownerNamespace)?.let(::previewTag) ?: statementNotFound()

    /**
     * 同标签名兄弟中的序号（0 起）——语句缺 `id` 属性（非法文件）时的定位回落。
     * 纯 PSI 兄弟遍历，O(兄弟数)，不进 gutter 热路径（只在无 id 的小概率分支调用）。
     */
    fun statementOrdinalOf(tag: XmlTag): Int {
        val name = tag.name
        val siblings = tag.parentTag?.children ?: return 0
        var ordinal = 0
        for (child in siblings) {
            if (child !is XmlTag || child.name != name) continue
            if (child.textRange.startOffset >= tag.textRange.startOffset) break
            ordinal++
        }
        return ordinal
    }

    /**
     * 目标缺失的可见失败结果：空 SQL + 「语句未找到」告警。
     *
     * 不用 `SqlPreviewService.showResult(null)` 的通用「未找到可转换的 Wrapper」文案——那条提示
     * 指向 Wrapper 链，在 iBatis 场景属错源归因（T-IbatisFix3 现场）。空 sql 让面板呈现失败态，
     * 告警文案说明「语句可能已被改名/删除」。
     */
    fun statementNotFound(): PreviewResult = PreviewResult(
        sql = "",
        compactSql = "",
        unresolvedParams = emptyList(),
        paramTable = null,
        wrapperType = null,
        tableName = null,
        warning = MpSqlVisualizerBundle.message("ibatis.preview.statementNotFound.warning"),
    )
}

/**
 * 一个「iBatis `SqlMapExecutor` 调用点」：调用表达式 + statementId + 调用点所属类限定名。
 *
 * [statementId] 与形状门（[IbatisCallYieldGate]）同一口径：首参 String 字面量、`id` / `ns.id`
 * 标识符形态；存在性**不在这里校验**（gutter 慢趟与点击期各自走既有 memo 解析）。
 */
internal data class IbatisCallTarget(
    val call: PsiMethodCallExpression,
    val statementId: String,
    val ownerNamespace: String?,
)

/**
 * 光标 / 方法体 → [IbatisCallTarget] 的定位助手（快捷键与 MCP 共用；gutter provider 复用
 * [ownerNamespace] 与同一形状门）。
 *
 * 形状门直接复用 [IbatisCallYieldGate.isIbatisStatementIdCall]——与 JdbcTemplate 通道让位、
 * 方法定义行让位是**同一个函数**，三处不会漂移出第二份正则。
 */
internal object IbatisCallTargets {

    /**
     * 最近的外层调用是 iBatis 形状即返回；否则 null（O(1) 语法门，零 resolve）。
     *
     * 两跳定位：① 光标在调用表达式内（方法名 / 实参 / 限定符）→ 直接上爬；
     * ② 光标在**包含调用的语句**上（`return client.queryForX(...)` 的 `return` 关键字不是调用表达式
     *    的祖先，是最常见的落点）→ 在该语句子树里取首个 iBatis 形状调用。
     */
    fun resolve(element: PsiElement): IbatisCallTarget? {
        PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression::class.java, false)
            ?.let(::targetOf)
            ?.let { return it }
        val statement = PsiTreeUtil.getParentOfType(element, PsiStatement::class.java, false) ?: return null
        return PsiTreeUtil.collectElementsOfType(statement, PsiMethodCallExpression::class.java)
            .mapNotNull(::targetOf)
            .minByOrNull { it.call.textRange.startOffset }
    }

    /**
     * 方法体内全部 iBatis 形状调用（按 offset 序）。
     *
     * 带两道廉价预筛（对齐 `StringSqlMethodGutterLineMarkerProvider.hasStringSqlCall` 的形态）：
     * 无方法体 / 超长方法体 / 方法体不含引号 → 直接空；避免快捷键在普通方法上白付子树遍历。
     */
    fun callsInMethod(method: PsiMethod): List<IbatisCallTarget> {
        val body = method.body ?: return emptyList()
        if (body.textLength > MAX_BODY_TEXT_LENGTH) return emptyList()
        if (!body.text.contains('"')) return emptyList()
        return PsiTreeUtil.collectElementsOfType(body, PsiMethodCallExpression::class.java)
            .mapNotNull(::targetOf)
            .sortedBy { it.call.textRange.startOffset }
    }

    /** 调用点所属类的限定名（裸 id 的「同 namespace 优先」排序偏好，与 L8 handler 同语义）。 */
    fun ownerNamespace(element: PsiElement): String? {
        var current: PsiElement? = element
        var depth = 0
        while (current != null && depth++ < 6) {
            if (current is PsiClass) return current.qualifiedName
            current = current.parent
        }
        return null
    }

    private fun targetOf(call: PsiMethodCallExpression): IbatisCallTarget? {
        val methodName = call.methodExpression.referenceName ?: return null
        // 形状门与 gutter 让位同一函数：入口方法名 + 首参 String 字面量 + 标识符形态
        if (!IbatisCallYieldGate.isIbatisStatementIdCall(call, methodName)) return null
        val literal = call.argumentList?.expressions?.firstOrNull() as? PsiLiteralExpression ?: return null
        val statementId = (literal.value as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return IbatisCallTarget(call, statementId, ownerNamespace(call))
    }

    /** 方法体文本长度护栏（字符）：超过则不扫描（与 T-DaoSql-04 同量级）。 */
    private const val MAX_BODY_TEXT_LENGTH = 200_000
}
