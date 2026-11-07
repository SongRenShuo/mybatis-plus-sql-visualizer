package cn.song.mybatisplus.visualizer.ui.annotate

import cn.song.mybatisplus.visualizer.execute.DataSourceSelector
import cn.song.mybatisplus.visualizer.execute.SchemaValidationGate
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInsight.hint.TooltipController
import com.intellij.codeInsight.hint.TooltipGroup
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.ui.JBColor
import com.intellij.util.Alarm
import java.util.Collections
import java.util.WeakHashMap

/**
 * 预览编辑器错列的**编辑器级标注通道**（UI025，配置目录 `.idea` 兜底）。
 *
 * ## 为什么需要第二条通道
 *
 * 平台不把项目配置目录（`.idea`）里的文件交给 daemon：测试实证同一份 SQL 放在
 * `sql-visualizer/exec/mpsql-exec.sql` 下 `ExternalAnnotator` 立即触发，放到
 * `.idea/sql-visualizer/exec/mpsql-exec.sql` 下永不触发（`inContent=true / excluded=false /
 * inSource=true` 均满足，仍被跳过）。本插件的预览文件与原生控制台 exec 文件恰好都在
 * `.idea/sql-visualizer/` 下——只靠 [PreviewUnknownColumnAnnotator] 在真机上不会出任何波浪线。
 *
 * 本对象因此直接操作编辑器的 `MarkupModel`：
 *  - 红色波浪线：`TextAttributes.effectType = WAVE_UNDERSCORE`、`effectColor = JBColor.RED`，
 *    图层 `HighlighterLayer.ERROR + 1`（压在语法高亮之上）；
 *  - 悬停信息：鼠标移到标注区间时经 `TooltipController` 弹出与 ExternalAnnotator 同款文案；
 *  - 右侧错误条：`errorStripeMarkColor` + `errorStripeTooltip`（悬停右侧条同样可见）。
 *
 * ## 数据与作用域（与 ExternalAnnotator 完全同源）
 *
 * 只对 [PreviewSchemaAnnotationScope.isPreviewSurface] 且位于 `.idea` 下的文件生效；目标优先取
 * [DataSourceSelector.schemaValidationTarget]（已备控制台的执行文档身份），仅在其为 null 时回落
 * [PreviewFallbackTarget]（面板「数据源（上次使用）」记忆，UI029 日志条目预览场景）；列集仍只读
 * [SchemaValidationGate.check] 三参入口的已预热缓存，位点仍由
 * [PreviewUnknownColumnPlanner] 从 AST token 换算。无目标/缓存冷/作用域不可证 → 清空标注。
 *
 * ## 生命周期
 *
 * 面板装配编辑器时 [install]；文档变更经 [Alarm] 防抖后重算并**先清旧标注再投新标注**
 * （编辑后不残留）。编辑器释放/面板销毁时 [uninstall]。缓存预热完成后由面板调用 [refresh]
 * 补一拍（`refreshCompletionContext`），保证「控制台就绪/缓存变热」后无需用户输入即可出现标注。
 */
internal object PreviewSchemaHighlighter {

    /** 红色波浪线属性（前景/背景不改，只叠效果）。 */
    private val ATTRS: TextAttributes = TextAttributes().apply {
        effectType = EffectType.WAVE_UNDERSCORE
        effectColor = JBColor.RED
        errorStripeColor = JBColor.RED
    }

    private val sessions: MutableMap<Editor, Session> =
        Collections.synchronizedMap(WeakHashMap<Editor, Session>())

    /**
     * 为编辑器安装标注通道（幂等；重复安装直接返回）。
     *
     * T-PreviewSchemaSessionLeak：Session 必须**注册进 Disposer 树**，否则其内部 Alarm（以 Session 为
     * parentDisposable）会把整条分支挂到 `ROOT_DISPOSABLE` 下，关 IDE 时报「Memory leak detected」。
     * 属主取 `editor.project`：project 必在树中，且项目关闭即兜底回收（正常释放仍由 [uninstall] 提前销毁）。
     */
    fun install(editor: Editor) {
        install(editor, editor.project)
    }

    /**
     * T-PreviewSchemaSessionLeak 测试缝/内部入口：显式指定属主（生产传 `editor.project`）。
     *
     * 用 [Disposer.tryRegister] 而非 `register`：后者在属主正在关闭时抛异常，而 install 可能发生在
     * 项目关闭的临界窗口（预览编辑器尚未释放）。属主为 null（无 project 的编辑器）时退化为不注册——
     * 该分支由测试显式覆盖。
     */
    internal fun install(editor: Editor, owner: Disposable?) {
        synchronized(sessions) {
            if (sessions.containsKey(editor)) return
            val session = Session(editor, owner)
            owner?.let { Disposer.tryRegister(it, session) }
            sessions[editor] = session
        }
    }

    /** 触发一次重算（缓存预热完成/控制台就绪后由面板调用）。 */
    fun refresh(editor: Editor?) {
        if (editor == null) return
        sessions[editor]?.schedule(0)
    }

    /** 编辑器释放/面板销毁时摘除监听与标注（幂等）。 */
    fun uninstall(editor: Editor?) {
        if (editor == null) return
        val session = synchronized(sessions) { sessions.remove(editor) } ?: return
        // T-PreviewSchemaSessionLeak：走树感知销毁——由 Disposer 移除节点并连带 dispose 子对象（Alarm），
        // 此前直接调 `session.dispose()` 只跑一半清理（节点与 Alarm 仍留在树里）。自身 disposed 标志
        // 保证幂等：项目关闭兜底已销毁时这里直接跳过（`Disposer.isDisposed` 已废弃，不许用）。
        if (!session.isDisposed()) Disposer.dispose(session)
    }

    /** 测试缝：同步触发一次重算（结果经 `invokeLater` 落盘，测试侧 dispatch 后断言）。 */
    internal fun refreshNowForTest(editor: Editor) {
        sessions[editor]?.recomputeForTest()
    }

    /**
     * T-PreviewSchemaSessionLeak 测试缝：该编辑器是否**仍有活会话**（＝已安装且未 dispose）。
     *
     * 注意语义：`uninstall` 会先把会话移出 map，故「map 里没有」＝没有活会话＝无泄漏风险。
     * 这正是泄漏判据（是否残留一个未 dispose 的会话），而非「曾经安装过」。
     */
    internal fun hasLiveSessionForTest(editor: Editor): Boolean = sessions[editor]?.isDisposed()?.not() ?: false

    /** T-PreviewSchemaSessionLeak 测试缝：该会话记录的属主（用于断言生产入口确实注册到 project）。 */
    internal fun sessionOwnerForTest(editor: Editor): Disposable? = sessions[editor]?.owner

    private class Session(private val editor: Editor, internal val owner: Disposable?) : Disposable {

        private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
        private val highlighters = ArrayList<RangeHighlighter>()

        /**
         * T-PreviewSchemaSessionLeak：自身可判定的 disposed 标志（**注意不是 override**——243 的
         * `com.intellij.openapi.Disposable` 只声明 `dispose()`，没有 `isDisposed()`；平台对
         * 「`Disposer.isDisposed` 已废弃」给出的官方替代正是「对象自带布尔标志」，本处照此实现）。
         *
         * 与「注册进父树」并用：入树负责兜底回收，标志负责幂等（兜底与 uninstall 可能先后到达）。
         */
        @Volatile
        private var disposed = false

        fun isDisposed(): Boolean = disposed

        /** 代际：文档连续变更时旧一轮结果不得覆盖新一轮（池化线程与 EDT 并发读写）。 */
        private val generation = java.util.concurrent.atomic.AtomicInteger()

        /** 当前悬停的标注起点（避免同位置反复弹提示）。 */
        private var hoverAnchor = -1

        private val documentListener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = schedule(300)
        }

        private val mouseListener = object : EditorMouseMotionListener {
            override fun mouseMoved(event: EditorMouseEvent) = showHover(event)
        }

        init {
            // T-PreviewChainNoise W3：两参重载=注册+把「摘除」挂成本会话的子 disposable（所有权
            // 一次性交出去）——dispose 里**禁止**再手动 remove（后序销毁下必成二次摘除，见 dispose 注释）
            editor.document.addDocumentListener(documentListener, this)
            editor.addEditorMouseMotionListener(mouseListener, this)
            // 首拍：控制台 preparedEditor 在本编辑器就绪回调返回后才赋值，故延迟一拍；
            // 1.5s 再补一拍，覆盖「目标/缓存晚到」的窗口（缓存预热另有 refresh 显式触发）。
            schedule(300)
            alarm.addRequest({ recompute(generation.incrementAndGet()) }, 1500)
        }

        fun schedule(delayMs: Long) {
            val token = generation.incrementAndGet()
            alarm.cancelAllRequests()
            alarm.addRequest({ recompute(token) }, delayMs)
        }

        /** 测试缝：在当前线程直接算一次（apply 仍经 invokeLater，保持 EDT 语义）。 */
        fun recomputeForTest() {
            recompute(generation.incrementAndGet())
        }

        private fun recompute(token: Int) {
            val document = editor.document
            val text = document.text
            // F-09：超大 SQL 护栏——一次 SQL 校验 + 逐 mark 建 highlighter 的成本随文本长度放大；
            // 超限走「无标注」路径（与无目标同形态），正文与执行链不受影响。
            if (!cn.song.mybatisplus.visualizer.util.SqlSizeGuard.plan(text).decorationsEnabled) {
                clear(token)
                return
            }
            val project = editor.project
            if (project == null || project.isDisposed || editor.isDisposed) {
                clear(token)
                return
            }
            val psi = ReadAction.compute<PsiFile?, Throwable> {
                PsiDocumentManager.getInstance(project).getPsiFile(document)
            }
            // 只接配置目录（.idea）内的预览载体；其余文件由 ExternalAnnotator 通道负责，避免双标。
            if (psi == null || !PreviewSchemaAnnotationScope.isPreviewSurface(psi) ||
                !PreviewSchemaAnnotationScope.isProjectConfigSurface(psi)
            ) {
                clear(token)
                return
            }
            // 旧入口零变化：仅在其返回 null 时，才用面板自身已确定的目标（last-used 记忆）回落。
            val legacyTarget = runCatching { DataSourceSelector.schemaValidationTarget(document) }.getOrNull()
            val target = legacyTarget ?: runCatching { PreviewFallbackTarget.resolve(project, text) }.getOrNull()
            val marks = if (target == null) {
                emptyList()
            } else {
                val outcome = runCatching { SchemaValidationGate.check(project, text, target) }.getOrNull()
                val issues = (outcome as? SchemaValidationGate.Outcome.Checked)?.issues.orEmpty()
                PreviewUnknownColumnPlanner.plan(text, issues)
            }
            ApplicationManager.getApplication().invokeLater({
                if (token != generation.get()) return@invokeLater
                apply(marks)
            }, ModalityState.any())
        }

        private fun clear(token: Int) {
            ApplicationManager.getApplication().invokeLater({
                if (token == generation.get()) apply(emptyList())
            }, ModalityState.any())
        }

        private fun apply(marks: List<PreviewUnknownColumnPlanner.Mark>) {
            if (editor.isDisposed) return
            for (highlighter in highlighters) {
                runCatching { editor.markupModel.removeHighlighter(highlighter) }
            }
            highlighters.clear()
            val length = editor.document.textLength
            for (mark in marks) {
                if (mark.startOffset < 0 || mark.endOffset > length || mark.startOffset >= mark.endOffset) continue
                val highlighter = editor.markupModel.addRangeHighlighter(
                    mark.startOffset,
                    mark.endOffset,
                    HighlighterLayer.ERROR + 1,
                    ATTRS,
                    HighlighterTargetArea.EXACT_RANGE,
                )
                val message = previewUnknownColumnMessage(mark)
                highlighter.errorStripeTooltip = message
                highlighter.setErrorStripeMarkColor(JBColor.RED)
                highlighters += highlighter
            }
        }

        private fun showHover(event: EditorMouseEvent) {
            val offset = event.offset
            if (offset < 0) return
            val highlighter = highlighters.firstOrNull { offset in it.startOffset until it.endOffset }
            if (highlighter == null) {
                hoverAnchor = -1
                return
            }
            if (hoverAnchor == highlighter.startOffset) return
            hoverAnchor = highlighter.startOffset
            val message = highlighter.errorStripeTooltip as? String ?: return
            TooltipController.getInstance()
                .showTooltip(editor, event.mouseEvent.point, message, false, TooltipGroup("MP_SQL_UNKNOWN_COLUMN", 0))
        }

        override fun dispose() {
            if (disposed) return   // T-PreviewSchemaSessionLeak：幂等（兜底回收与 uninstall 可能先后到达）
            disposed = true
            // Alarm 是本会话的子 disposable（构造参数 this），Disposer 后序销毁时已先 cancel——
            // 这里是防御性重复调用（cancelAllRequests 幂等，列表已空）
            alarm.cancelAllRequests()
            // T-PreviewChainNoise W3：监听摘除职责**只归注册时的 parentDisposable**——init 用两参
            // 重载（addDocumentListener / addEditorMouseMotionListener）注册时，平台已在 Disposer 树
            // 里给本会话挂了摘除子对象，且 ObjectTree 按后序（子先父后）销毁：跑到本方法时监听必已
            // 被摘，再手动 removeDocumentListener / removeEditorMouseMotionListener 即二次摘除 →
            // 平台打 "Can't remove document listener" 与 EditorImpl 的 "Assertion failed"
            // （2026-10-23 用户真机日志，ebfaf73f 改树感知销毁后暴露）。仓内同款定例见
            // SqlPreviewPanelEditorLifecycle（T-RA-02）。
            for (highlighter in highlighters) {
                runCatching { editor.markupModel.removeHighlighter(highlighter) }
            }
            highlighters.clear()
        }
    }
}

/** 与 ExternalAnnotator 通道共用同一文案（前缀 + 表/列/建议）。 */
internal fun previewUnknownColumnMessage(mark: PreviewUnknownColumnPlanner.Mark): String {
    val body = if (mark.suggestion != null) {
        MpSqlVisualizerBundle.message(
            "schema.check.unknownColumnSuggestion",
            mark.column,
            mark.table,
            mark.suggestion,
        )
    } else {
        MpSqlVisualizerBundle.message("schema.check.unknownColumn", mark.column, mark.table)
    }
    return MpSqlVisualizerBundle.message("schema.check.notification.title") + ": " + body
}
