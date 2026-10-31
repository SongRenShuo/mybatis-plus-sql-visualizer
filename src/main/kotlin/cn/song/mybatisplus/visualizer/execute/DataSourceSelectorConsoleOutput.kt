package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.execute.DataSourceSelector.FillTargetLayout
import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver
import cn.song.mybatisplus.visualizer.util.FatalErrors
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import javax.swing.Icon

// T-M53-II（2026-10-10）同法，D202 第六次登记：DataSourceSelector 的「控制台输出写入与分栏边界取证」
// 内聚成员，原样搬出到本文件——方法体逐字符搬运，同包扩展函数可见性不变。

/**
 * M36.5.6：嵌入后自检数据上下文——原生 ▶ 执行按钮启用链（QueryActionBase：EDITOR +
 * JdbcConsole.findConsole 的 CLIENT_KEY 分支）经 DataManager 在嵌入块上解析验证。
 * M36.5.8：补齐 ▶ 启用链的剩余环节探测——isSqlAtPlace(psiFile, editor)（isApplicable
 * 依赖）与 getSelectedStatementsRange(client, editor, execOption)（最终 setEnabled 依据，
 * = scriptModel.getTextRange().length > 0）——每步结果落日志，▶ 禁用时一眼定位断点。
 */

internal fun DataSourceSelector.writeConsoleOutput(console: Any?, text: String, isError: Boolean = false) {
    if (console == null || text.isBlank()) return
    val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
    runCatching {
        val consoleView = console.javaClass.getMethod("getConsoleView").invoke(console)
        if (consoleView == null) { LOG.info("[M36] writeConsoleOutput: consoleView null"); return }
        // ① 直接向 historyViewer 的 document 追加文本（Output 控制台显示的就是此 document）
        //    ——比走 ConsoleView.print 更可靠（不依赖 ConsoleViewContentType 枚举类是否存在）。
        val historyViewer = consoleView.javaClass.getMethod("getHistoryViewer").invoke(consoleView)
        if (historyViewer != null) {
            val doc = historyViewer.javaClass.getMethod("getDocument").invoke(historyViewer)
            if (doc is com.intellij.openapi.editor.Document) {
                val append = if (doc.text.isNotEmpty() && !doc.text.endsWith("\n")) "\n" else ""
                try {
                    val proj = (console as? com.intellij.openapi.project.Project) ?: runCatching {
                        console.javaClass.getMethod("getProject").invoke(console) as? com.intellij.openapi.project.Project
                    }.getOrNull()
                    @Suppress("DEPRECATION")
                    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(proj) {
                        doc.insertString(doc.textLength, "$append$text")
                    }
                    // 滚到末尾（新输出可见）
                    // write-safe: 无 document 写（反射替换 DBN script model）
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
                        runCatching {
                            // 滚动到底：LanguageConsoleView 的 caret/scrollPosition
                            val editor = com.intellij.openapi.editor.EditorFactory.getInstance()
                                .getEditors(doc).firstOrNull()
                            if (editor != null) {
                                editor.caretModel.moveToOffset(doc.textLength)
                                editor.scrollingModel.scrollToCaret(
                                    com.intellij.openapi.editor.ScrollType.RELATIVE,
                                )
                            }
                        }
                    }, com.intellij.openapi.application.ModalityState.any())
                    LOG.info("[M36] writeConsoleOutput: appended to history doc len=${doc.textLength}")
                    return
                } catch (e: Throwable) {
                    // T-FatalPassthrough T-FP-02：JVM 级致命错误透传（见 util/FatalErrors.kt）——
                    // 本文件是执行链长驻路径，捕获面覆盖 Error：普通异常（含可选能力探测的
                    // ClassNotFoundException）仍走既有降级，VirtualMachineError/LinkageError 不得
                    // 被吞成一条日志后让执行链带病继续。
                    if (FatalErrors.isFatal(e)) throw e
                    LOG.info("[M36] writeConsoleOutput doc-append FAILED: ${e.message}")
                }
            }
        }
        // ② 兜底 1：ConsoleView.print(String, ConsoleViewContentType) 反射
        val contentClass = runCatching { Class.forName("com.intellij.execution.ui.ConsoleViewContentType") }.getOrNull()
        if (contentClass != null) {
            val ct = try {
                val fieldName = if (isError) "ERROR_OUTPUT" else "NORMAL_OUTPUT"
                contentClass.getField(fieldName).get(null)
            } catch (e: Throwable) {
                if (FatalErrors.isFatal(e)) throw e // T-FP-02：fatal 透传
                contentClass.fields.firstOrNull()?.get(null)
            }
            val print = consoleView.javaClass.methods.firstOrNull { m ->
                m.name == "print" && m.parameterCount >= 2 && m.parameterTypes[0] == String::class.java
            }
            if (ct != null && print != null) {
                print.invoke(consoleView, text, ct)
                LOG.info("[M36] writeConsoleOutput: ConsoleView.print ok")
                return
            } else {
                LOG.info("[M36] writeConsoleOutput: ct=${ct != null} print=${print != null}")
            }
        } else {
            LOG.info("[M36] writeConsoleOutput: ConsoleViewContentType class not found")
        }
    }.onFailure { e ->
        // T-FatalPassthrough T-FP-02b（独立复核阻断 1 修正）：本函数的 runCatching 包住上面两处
        // fatal 透传（doc-append 捕获块 / ConsoleViewContentType 字段回退捕获块）——在此把 fatal
        // 吞成一行 INFO 等于透传无效（改前实测：fatal 被转成 "[M36] writeConsoleOutput FAILED: …"
        // 后执行链带病继续，正是上面捕获块注释声称的反面）。反射链先解包 InvocationTargetException
        // 再判（同本文件原生执行链 :2604-2607 形态；只判外层会漏判目标方法抛出的 fatal）。
        // 非 fatal 路径行为逐字节不变：仍只记原 INFO 行，函数返回 Unit、调用方（EDT 排出的
        // writeConsoleOutput + onError 回调）行为不变；fatal 则透传到平台 EDT 处理器记录现场。
        val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        if (FatalErrors.isFatal(cause)) throw cause
        LOG.info("[M36] writeConsoleOutput FAILED: ${e.message}")
    }
}

/**
 * M36.5.8/M36.5.9/M36.5.13：执行后结果区刷新——**限时多次重取**。新执行会重建 RunnerLayoutUi 的
 * 内容组件，重建时机与数据到达相关（单次重取可能仍取到旧实例——M36.5.8 实测刷新命中后
 * 显示依旧停留上一轮）；EDT Timer 600ms×8 重取 `ui.getComponent()`。
 *
 * M36.5.13 变更：
 *  - [forceSwapIn] = true（startResultTabRetry 找到结果 Tab 后传入）：**立即强制换入一次**——
 *    旧 ui 的组件实例可能恒定（M36.5.10 盘点实证"刷新定时器 8 拍实例恒定"），"实例变化才换入"
 *    的去重导致新结果永远不显示（用户实测第二次执行后仍显示第一次结果）；换入后更新
 *    lastLiveResultSwapped 基线，后续实例变化仍可继续换入。
 *  - Timer **每拍清提示符**——DatabaseLogView 在执行/数据渲染时重设 prompt（用户实测
 *    prepare/reuse/入口清理后仍重现），4.8s 窗口内持续压制。
 */

internal fun DataSourceSelector.dumpSplitBounds(sp: javax.swing.JSplitPane, log: com.intellij.openapi.diagnostic.Logger) {
    // T-Perf-13：取证 dump 降 debug 门控（非 debug 模式零开销）
    if (!log.isDebugEnabled) return
    runCatching {
        fun b(c: java.awt.Component?): String = if (c == null) "null" else
            "${c.javaClass.simpleName}@${System.identityHashCode(c)} bounds=${c.bounds} pref=${c.preferredSize} min=${c.minimumSize} max=${c.maximumSize}"
        log.debug("[M36][BOUNDS] sp=${b(sp)} divider=${sp.dividerLocation}")
        log.debug("[M36][BOUNDS]   top=${b(sp.topComponent)}")
        (sp.topComponent as? java.awt.Container)?.components?.forEachIndexed { i, c ->
            log.debug("[M36][BOUNDS]   top.child[$i]=${b(c)}")
            if (c is java.awt.Container) c.components.forEachIndexed { j, d ->
                log.debug("[M36][BOUNDS]   top.child[$i].child[$j]=${b(d)}")
                // M36.5.61：编辑器视图（PanelWithFloatingToolbar）内部是否含未拉伸的 scrollpane/
                // viewport——mask 真相在此层（编辑器 view 有 bounds 但只 paint 内容高度）
                if (d is java.awt.Container) d.components.forEachIndexed { k, e ->
                    log.debug("[M36][BOUNDS]   top.child[$i].child[$j].child[$k]=${b(e)}")
                    if (e is javax.swing.JScrollPane) {
                        log.info("[M36][BOUNDS]     scrollpane viewport=${e.viewport?.let { v -> "${b(v)} vsbV=${e.verticalScrollBar?.isVisible} hsbV=${e.horizontalScrollBar?.isVisible}" } }")
                    }
                }
            }
        }
        log.info("[M36][BOUNDS]   bottom=${b(sp.bottomComponent)}")
        // M36.5.63：递归看结果区内部——RunnerLayoutUi 结果页有内部 layout（Output/结果 sub-split），
        // 换入时若内部 divider 塌缩（结果页只显示 Output 一行），外层 bounds 正确但内部是坏的。
        fun dumpDeep(c: java.awt.Component?, depth: Int, path: String) {
            if (c == null || depth > 12) return
            val spExtra = if (c is javax.swing.JSplitPane) " div=${c.dividerLocation} split(${c.orientation})" else ""
            log.info("[M36][BOUNDS]   ${path}=${b(c)}$spExtra")
            if (c is java.awt.Container) {
                var idx = 0
                for (d in c.components) {
                    dumpDeep(d, depth + 1, "$path.child[$idx]")
                    idx++
                }
            }
        }
        // 结果区整体（MyNonOpaquePanel/MyComponent）+ 内部两级，重点找 JSplitPane/塌缩 divider
        (sp.bottomComponent as? java.awt.Container)?.components?.forEachIndexed { i, c ->
            log.info("[M36][BOUNDS]   bottom.child[$i]=${b(c)}")
            if (c is java.awt.Container) c.components.forEachIndexed { j, d ->
                dumpDeep(d, 2, "bottom.child[$i].child[$j]")
            }
        }
        // M36.5.68：定位 Output 控制台（ConsoleView/EditorImpl 文本视图）真实高度——问题 SQL 后
        // 结果 grid 内容 ProviderWrapper 填满 234px，但承载 Output 历史文本的滚动视图可能只按
        // 内容高度渲染（2 行），下方留白。深扫所有 JScrollPane/JTextComponent，看谁没撑满。
        fun scanTextViews(c: java.awt.Component?, path: String) {
            if (c == null) return
            val cls = c.javaClass.name
            if (c is javax.swing.JScrollPane || cls.contains("ConsoleView") || cls.contains("EditorImpl") || c is javax.swing.text.JTextComponent) {
                log.info("[M36][BOUNDS][TEXT] $path ${cls.substringAfterLast('.')} bounds=${c.bounds} pref=${c.preferredSize}")
                if (c is javax.swing.JScrollPane) {
                    log.info("[M36][BOUNDS][TEXT]   viewport=${c.viewport?.let { it.bounds.toString() }} vsbV=${c.verticalScrollBar?.isVisible}")
                    c.viewport?.let { scanTextViews(it.view, "$path.view") }
                }
            }
            if (c is java.awt.Container) {
                var idx = 0
                for (d in c.components) { scanTextViews(d, "$path.child[$idx]"); idx++ }
            }
        }
        (sp.bottomComponent as? java.awt.Container)?.let { scanTextViews(it, "bottom") }
    }.onFailure { e ->
        log.info("[M36][BOUNDS] dump failed: ${e.message}")
    }
}
