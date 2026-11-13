package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.ui.MbgExampleCallResolver
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.ui.XmlPreviewService
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext
import cn.song.mybatisplus.visualizer.ui.XmlSqlCompletionContextResolver
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlToken
import com.intellij.psi.xml.XmlTokenType
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * XML mapper 语句预览图标（T-M24-04）：`<select|insert|update|delete>` 语句行显示预览图标，
 * 点击弹出动态 SQL 预览（复用 [SqlPreviewService.showResult] 链路）。
 *
 * **性能红线**：快速路径只做 O(1) 语法判定（tag 名 + 父标签名），零 resolve、零遍历、
 * 零转换；tooltip 静态文案；点击转换走 [SqlPreviewService.previewAsync] 后台线程。
 *
 * 注册见 plugin.xml `<codeInsight.lineMarkerProvider language="XML" .../>`。
 */
class XmlSqlGutterLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // LineMarker 必须注册在 leaf 元素：只接受语句标签的 name token（O(1) 判定，零 resolve）
        val token = element as? XmlToken ?: return null
        if (token.tokenType != XmlTokenType.XML_NAME) return null
        val tag = token.parent as? XmlTag ?: return null
        // 语句标签 + 直接父级是 <mapper>（避免非 mapper XML 误显）
        if (tag.name !in STATEMENT_TAGS) return null
        if (tag.parentTag?.name != "mapper") return null
        // F36（T-PerfSweep16）：单次取 state，总闸与组显隐共用（原 gutterEnabled + enabledFor
        // 双路径每到达元素 3 次 app 级查找收敛为 1 次）
        val gutterState = runCatching { PluginSettings.getInstance().state }.getOrNull()
        if (gutterState != null && !gutterState.showGutterIcon) return null
        if (!GutterGroups.enabledFor(javaClass, gutterState)) return null // R2 分组显隐
        // T-PreviewOpenPerfM2 M11：MBG Example 语句的首次预览慢在 M2 引用检索（真机 1628~2042ms，
        // 弹窗 2.7~4.6s），第二次起只有 200ms 级。渲染 gutter 时顺手在后台预取一次——用户从
        // 「看到图标」到「点开」通常有秒级延迟，届时缓存已热；不改变任何交互，失败静默。
        runCatching { prefetchMbgExample(tag) }
        // §二十七：文件首次渲染 gutter 时后台预热一次「补全上下文」（实体解析冷态实测 13.3s）。
        // 原来这笔成本压在点击预览里（pre-assembly:completion=13280ms）；挪到文件打开时，
        // 用户真去点预览/补全时已经是热态。每个文件只做一次，dumb 期跳过（等下次渲染再试）。
        runCatching { warmCompletionContextOnce(tag) }
        // 七参构造器（2020.3+）：末参 accessibleNameProvider 供屏幕阅读器使用
        return LineMarkerInfo(
            element,
            element.textRange,
            SQL_PREVIEW_ICON,
            { MpSqlVisualizerBundle.message("gutter.xml.tooltip") },
            XmlGutterNavigationHandler,
            GutterIconRenderer.Alignment.CENTER,
            { MpSqlVisualizerBundle.message("gutter.xml.accessible.name") },
        )
    }

    /**
     * M11 预取入口：只对 MBG Example 形态（`parameterType` 以 `Example` 结尾）的语句触发，
     * 其余语句（普通 mapper SQL）M2 根本不参与，预取纯属白烧。
     */
    private fun prefetchMbgExample(tag: XmlTag) {
        // 只预取 select：真机上 countByExample / deleteByExample 这些语句多数没有调用点，
        // 预取它们只会白烧一轮检索（实测 6 秒内同一语句重跑 4 轮）。
        if (tag.name != "select") return
        val paramType = tag.getAttributeValue("parameterType") ?: return
        if (!paramType.endsWith("Example")) return
        val id = tag.getAttributeValue("id") ?: return
        val namespace = tag.parentTag?.getAttributeValue("namespace") ?: return
        MbgExampleCallResolver.prefetch(tag.project, namespace, id)
    }

    /** 点击处理：后台解析 + 渲染（EDT 禁止 PSI 慢操作），结果走统一展示入口。 */
    private object XmlGutterNavigationHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent?, elt: PsiElement) {
            val project: Project = elt.project
            val tag = elt.parent as? XmlTag ?: return
            val file = tag.containingFile as? XmlFile ?: return
            val contextRef = java.util.concurrent.atomic.AtomicReference<cn.song.mybatisplus.visualizer.context.PreviewSourceContext?>()
            SqlPreviewService.previewAsync(project, {
                val t0 = System.currentTimeMillis()
                val context = XmlPreviewService.resolveSourceContext(file, tag)
                contextRef.set(context)
                val t1 = System.currentTimeMillis()
                // T-StartupReadLockFreeze §十八（2026-11-13 14:48 真机）：索引期**不做**实体/表上下文解析。
                // 它依赖索引，冷启动时会跟着 JavaBuilder/索引一起等——实测「点击 → compute 开始」11s，
                // 其中 8s 是 IDE 在建索引/编译，而我们这段闭包就排在哪里等读锁；
                // 实体上下文只影响补全提示（表/列来源），缺席不影响 SQL 预览本身。
                val dumb = runCatching {
                    com.intellij.openapi.project.DumbService.isDumb(project)
                }.getOrDefault(false)
                // §二十：预览打开不解析联表候选（实测 2~3s、最终只得 1 个候选），候选留给补全触发时再算
                val src = if (dumb) null else XmlSqlCompletionContextResolver.resolveXml(project, tag, includeCandidates = false)
                val t2 = System.currentTimeMillis()
                LOG.info(
                    "[open-perf] pre-assembly: sourceCtx=${t1 - t0}ms completion=${t2 - t1}ms dumb=$dumb" +
                        if (dumb) "（索引未就绪，已跳过实体上下文）" else "",
                )
                val entity = src?.main?.let { m ->
                    SqlCompletionContext(m.tableName, m.columns, m.tableAlias, m.columnComments, m.tableComment, src.candidates)
                }
                XmlPreviewService.preview(file.text, tag.getAttributeValue("id"), project, entity, sourceContext = context)
            }) { result ->
                SqlPreviewService.showResult(project, result, sourceContext = contextRef.get())
            }
        }
    }

    companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(XmlSqlGutterLineMarkerProvider::class.java)

        /** §二十七：已预热过补全上下文的文件（按 VirtualFile.url，每文件一次）。 */
        private val warmedContextFiles: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /**
         * 后台预热补全上下文（实体解析 + 参数名）。**不解析联表候选**（那条另有预热链）。
         * 读动作走 nonBlocking + inSmartMode：等索引不持读锁，可取消。
         */
        private fun warmCompletionContextOnce(tag: XmlTag) {
            val project = tag.project
            val fileUrl = tag.containingFile?.virtualFile?.url ?: return
            if (!warmedContextFiles.add(fileUrl)) return
            if (runCatching { com.intellij.openapi.project.DumbService.isDumb(project) }.getOrDefault(false)) {
                warmedContextFiles.remove(fileUrl) // 索引期不预热，等下一次渲染再试
                return
            }
            val started = System.currentTimeMillis()
            com.intellij.openapi.application.ReadAction.nonBlocking<Unit> {
                XmlSqlCompletionContextResolver.resolveXml(project, tag, includeCandidates = false)
                Unit
            }
                .inSmartMode(project)
                .expireWith(project)
                .submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService())
                .onSuccess {
                    LOG.info("[open-perf] ctx warm done in ${System.currentTimeMillis() - started}ms file=$fileUrl")
                }
                .onError { t ->
                    warmedContextFiles.remove(fileUrl)
                    LOG.info("[open-perf] ctx warm fail ${t.javaClass.simpleName}: ${t.message}")
                }
        }

        private val SQL_PREVIEW_ICON: Icon =
            IconLoader.getIcon("/icons/sqlGutter.svg", XmlSqlGutterLineMarkerProvider::class.java)

        val STATEMENT_TAGS = setOf("select", "insert", "update", "delete")

        /**
         * T-M29.6：选择区内 mapper 语句候选（tag 起始 offset 在选区范围内，按 offset 排序）——
         * ConvertToSqlAction 选择区候选交互复用（与 Java 选择区候选语义一致：语句开头在选择区内才计入）；
         * 纯 PSI 遍历零 resolve，internal 供测试。
         */
        internal fun statementsInRange(file: XmlFile, start: Int, end: Int): List<XmlTag> =
            PsiTreeUtil.collectElementsOfType(file, XmlTag::class.java)
                .filter { it.name in STATEMENT_TAGS && it.textRange.startOffset in start until end }
                .sortedBy { it.textRange.startOffset }
    }
}
