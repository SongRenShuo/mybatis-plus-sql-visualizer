package cn.song.mybatisplus.visualizer.ui.highlight

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.util.Key

/**
 * SQL 自动折叠公共应用器（T-AutoFold F0）：六条 SQL 预览编辑器支链的唯一收口。
 *
 * 按 [SqlAutoFoldPlanner] 的规划对目标编辑器**全量重建本方折叠区域**（先摘自己上一轮清单、
 * 再按当前文本添加）——每次程序性写入后调用即覆盖平台可能的重算抹除（防御策略，见计划 §2.3）。
 *
 * 区域清单挂 Editor UserData（[KEY_LAST_FOLD_REGIONS]）：跨类共享、随编辑器释放自然消失；
 * 清除时**只摘清单内的**，绝不 [com.intellij.openapi.editor.FoldingModel.getAllFoldRegions]
 * 全清——原生控制台/平台可能挂有 ConsoleFolding 等他人区域，越权摘除即事故。
 *
 * 开关关闭/任何平台异常 → 清除本方区域后静默返回（写入管线零折叠操作，行为与关闭前逐字节
 * 一致；折叠是锦上添花，不因平台异常影响内容展示）。全部调用点已在 EDT，
 * [com.intellij.openapi.editor.FoldingModel.runBatchFoldingOperation] 为 UI 层批量刷新，
 * 无 read/write 锁问题。
 *
 * 外观最小接线：开启态下若 [com.intellij.openapi.editor.EditorSettings.isFoldingOutlineShown]
 * 为 false 仅补设该一项（双击弹窗紧凑外观、原生控制台需要；关闭态不动任何外观设置）。
 *
 * T-PreviewEdtGuard S1（同文本幂等短路）：程序性写入管线（renderSql→showSql）**每次渲染**
 * 都调本函数，而参数防抖后的常态是渲染文本未变（清空别的参数、与文本无关的勾选/聚焦等）——
 * 此时全量重建是纯冗余 EDT 工作（2×N 个 fold region 增删 + 两次批操作，清参数框重渲染链
 * EDT 噪音的可治理主体）。短路判据 = 开启态 + 上一轮应用文本相同 + 本方区域全部仍有效
 * （有效性检查保住「防御平台重算抹除」语义：被抹掉的 region isValid=false → 照常重建）。
 * 行为差：同文本渲染不再把用户手动展开的区域重新收起（旧实现每次渲染全量收起——与防御
 * 初衷无关的副作用，新行为更贴近用户意图）。
 */
object SqlAutoFolding {

    /** 本方折叠区域清单（Editor UserData；批操作外更新，批操作内只读）。 */
    private val KEY_LAST_FOLD_REGIONS = Key.create<Array<FoldRegion>>("MP_SQL_AUTO_FOLD_REGIONS")

    /** 上一轮成功应用的全文（Editor UserData；同文本短路判据之一，见类 KDoc T-PreviewEdtGuard S1）。 */
    private val KEY_LAST_APPLIED_TEXT = Key.create<String>("MP_SQL_AUTO_FOLD_LAST_TEXT")

    private val LOG = Logger.getInstance(SqlAutoFolding::class.java)

    /** 按当前设置对 editor 重建折叠区域；text = 当前全文。关/异常 → 清除本方区域后静默返回。 */
    fun applyTo(editor: Editor, text: String) {
        if (editor.isDisposed) return
        // F-09：超大 SQL 护栏——折叠规划与逐区域建区随文本长度线性增长，超限直接清空本方区域
        //（正文不动，执行/导出照用原文）。
        if (!cn.song.mybatisplus.visualizer.util.SqlSizeGuard.plan(text).decorationsEnabled) {
            clearOwnRegions(editor)
            return
        }
        val state = runCatching { PluginSettings.getInstance().state }.getOrNull()
        val enabled = state?.autoFoldEnabled ?: false
        if (!enabled) {
            clearOwnRegions(editor)
            return
        }
        // T-PreviewEdtGuard S1：同文本短路——任意一条判据不满足即照常全量重建
        //（含防御重建：区域被平台/他人抹除时 isValid=false，短路不成立）。
        val lastRegions = editor.getUserData(KEY_LAST_FOLD_REGIONS)
        if (editor.getUserData(KEY_LAST_APPLIED_TEXT) == text &&
            !lastRegions.isNullOrEmpty() &&
            lastRegions.all { it.isValid }
        ) {
            return
        }
        val threshold = state?.autoFoldFieldThreshold ?: PluginSettings.DEFAULT_AUTO_FOLD_FIELD_THRESHOLD
        runCatching {
            if (!editor.settings.isFoldingOutlineShown) {
                editor.settings.isFoldingOutlineShown = true
            }
        }
        val template = runCatching { MpSqlVisualizerBundle.message("preview.fold.placeholder") }
            .getOrElse { "… {0} columns" }
        val specs = runCatching { SqlAutoFoldPlanner.plan(text, threshold, template) }
            .getOrElse {
                LOG.warn("[T-AutoFold] plan failed, skip folding: ${it.message}")
                emptyList()
            }
        runCatching {
            editor.foldingModel.runBatchFoldingOperation {
                clearOwnRegions(editor)
                val added = ArrayList<FoldRegion>(specs.size)
                for (spec in specs) {
                    if (spec.startOffset < 0 || spec.endOffset > text.length || spec.startOffset >= spec.endOffset) continue
                    // 相交即拒（返回 null）：规划器自保证互斥，此处跳过即可
                    val region = editor.foldingModel.addFoldRegion(spec.startOffset, spec.endOffset, spec.placeholder)
                    if (region != null) {
                        region.isExpanded = false
                        added += region
                    }
                }
                editor.putUserData(KEY_LAST_FOLD_REGIONS, added.toTypedArray())
                editor.putUserData(KEY_LAST_APPLIED_TEXT, text)
            }
        }.onFailure {
            LOG.warn("[T-AutoFold] apply failed: ${it.message}")
        }
    }

    /** 清除本方上一轮区域（ UserData 清单内逐个摘除；他人区域不碰）。 */
    private fun clearOwnRegions(editor: Editor) {
        val old = editor.getUserData(KEY_LAST_FOLD_REGIONS) ?: return
        editor.putUserData(KEY_LAST_FOLD_REGIONS, null)
        runCatching {
            editor.foldingModel.runBatchFoldingOperation {
                for (region in old) {
                    if (region.isValid) {
                        editor.foldingModel.removeFoldRegion(region)
                    }
                }
            }
        }
    }
}
