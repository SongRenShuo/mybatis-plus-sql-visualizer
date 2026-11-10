package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.ui.IbatisXmlPreviewService
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisStatementKind
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlToken
import com.intellij.psi.xml.XmlTokenType
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * iBatis 2.x `<sqlMap>` 语句预览图标（T-Ibatis2Support 券 A 接管面）。
 *
 * ## 锚定条件（与让位方同源，见 [cn.song.mybatisplus.visualizer.xml.XmlInlineSqlDetector]）
 *
 * [isStatementAnchor]：锚 `XML_NAME` token，**标签名 ∈ [STATEMENT_TAGS] 且父标签为 `<sqlMap>`**。
 *
 * ## 与既有 XML provider 的两处关键差异（都是用户报障的一半）
 *
 * 1. **不要求叶子标签**：iBatis 语句带 `<dynamic>` / `<isNotNull>` 子标签时**也必须出图标**。
 *    通用兜底通道（`XmlInlineSqlDetector.isSqlCarrierTag`）的叶子判定让这类语句一个图标都没有
 *    （计划册 §券 A 动因第 3 条），接管面不得抄那条限制。
 * 2. **不做「首词是 SQL 关键字」判定**：`<statement>` / `<selectKey>` 体常是手写 `?` 或
 *    非 SELECT 开头（`INSERT`/`BEGIN`…），首词启发式会漏；本 provider 只认标签结构。
 *
 * ## 性能红线
 *
 * `getLineMarkerInfo` **严格 O(1)**：只看标签名 + 父标签名，零 resolve、零索引查询、
 * 零 `IbatisXmlRecognizer.sqlMapClientOnClasspath`（classpath 探针是 resolve 级操作）。
 * 完整识别留给冷路径（[IbatisXmlPreviewService] 预览时按根元素结构判定）。
 * 设置读取按 F36/F51 定例**方法头单次取 state**（`GutterProviderSingleLookupGuardTest` 守卫）。
 *
 * 注册见 plugin.xml `<codeInsight.lineMarkerProvider language="XML" .../>`（该平台
 * EP 本身是 dynamic 点位，热更新不受影响；守卫 `PluginXmlExtensionPointResolutionTest` 会抓）。
 */
class IbatisXmlGutterLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // LineMarker 必须注册在 leaf 元素：只接受语句标签的 name token（O(1) 判定，零 resolve）
        val token = element as? XmlToken ?: return null
        if (token.tokenType != XmlTokenType.XML_NAME) return null
        val tag = token.parent as? XmlTag ?: return null
        if (!isStatementAnchor(tag)) return null
        // F36/F51 定例：方法头单次取 state，总闸与组显隐共用（每到达元素 app 级服务查找 1 次）
        val gutterState = runCatching { PluginSettings.getInstance().state }.getOrNull()
        if (gutterState != null && !gutterState.showGutterIcon) return null
        if (gutterState != null && !gutterState.ibatisXmlEnabled) return null
        if (!GutterGroups.enabledFor(javaClass, gutterState)) return null // R2 分组显隐（拍板 D-8＝mapperXml 组）
        return LineMarkerInfo(
            element,
            element.textRange,
            SQL_PREVIEW_ICON,
            { MpSqlVisualizerBundle.message("gutter.ibatis.tooltip") },
            IbatisGutterNavigationHandler,
            GutterIconRenderer.Alignment.CENTER,
            { MpSqlVisualizerBundle.message("gutter.ibatis.accessible.name") },
        )
    }

    /** 点击处理：后台解析 + 渲染（EDT 禁止 PSI 慢操作），结果走统一展示入口。 */
    private object IbatisGutterNavigationHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            val project: Project = elt.project
            val tag = elt.parent as? XmlTag ?: return
            val file = tag.containingFile as? XmlFile ?: return
            SqlPreviewService.previewAsync(project, {
                // PSI 读取落在 previewAsync 的 ReadAction.compute 内（与 PagesXml/XmlInlineSql
                // 两条既有链同款两段式：EDT 只取元素，文本与定位在后台读权限内完成）
                IbatisXmlPreviewService.preview(
                    fileText = file.text,
                    statementId = tag.getAttributeValue("id"),
                    statementTagName = tag.name,
                    statementOrdinal = statementOrdinalOf(tag),
                )
            }) { result ->
                SqlPreviewService.showResult(project, result)
            }
        }
    }

    companion object {
        private val SQL_PREVIEW_ICON: Icon =
            IconLoader.getIcon("/icons/sqlGutter.svg", IbatisXmlGutterLineMarkerProvider::class.java)

        /**
         * iBatis 2.x 语句标签集（单一事实源）：直接取自解析模型的
         * [IbatisStatementKind] 6 种语句标签，避免与 `xml/ibatis/` 的标签表产生第二份拷贝。
         */
        val STATEMENT_TAGS: Set<String> =
            IbatisStatementKind.entries.mapTo(LinkedHashSet()) { it.tagName }

        /** iBatis 语句映射文件的根标签。 */
        const val SQL_MAP_TAG: String = "sqlMap"

        /**
         * 语句锚定判定（**O(1)**，零 resolve）：标签名 ∈ [STATEMENT_TAGS] 且父标签为 `<sqlMap>`。
         *
         * 同时被 [cn.song.mybatisplus.visualizer.xml.XmlInlineSqlDetector.isClaimedByKnownStructure]
         * 复用为让位判据——两处**必须是同一个函数**，否则会出现图标空洞或双图标（见该类 KDoc）。
         *
         * **不得**在此调用 `IbatisXmlRecognizer` / classpath 查询 / 任何 PSI resolve：
         * 本函数跑在 gutter 热路径（每到达一个 XML_NAME token）。
         */
        internal fun isStatementAnchor(tag: XmlTag): Boolean {
            return tag.name in STATEMENT_TAGS && tag.parentTag?.name == SQL_MAP_TAG
        }

        /**
         * 同标签名兄弟中的序号（0 起）——语句缺 `id` 属性（非法文件）时的定位回落。
         * 纯 PSI 兄弟遍历，O(兄弟数)，不进热路径（只在小概率的无 id 分支调用）。
         */
        private fun statementOrdinalOf(tag: XmlTag): Int {
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
    }
}
