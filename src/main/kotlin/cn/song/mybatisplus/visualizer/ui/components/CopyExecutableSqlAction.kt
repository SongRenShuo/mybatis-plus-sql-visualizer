package cn.song.mybatisplus.visualizer.ui.components

import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.sql.PlaceholderCardinality
import cn.song.mybatisplus.visualizer.sql.RuntimeSlot
import cn.song.mybatisplus.visualizer.sql.RuntimeSlotValues
import cn.song.mybatisplus.visualizer.sql.SlotWarning
import cn.song.mybatisplus.visualizer.sql.SqlCommentStripper
import cn.song.mybatisplus.visualizer.sql.placeholderPositions
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * 一键复制可执行 SQL（O-4 Phase A）。
 *
 * 取当前面板 SQL（含参数回填与手动编辑），将 `?` 替换为带类型处理的字面值
 * （复用 [ParameterReplacer.buildExecutableSql]），复制到系统剪贴板并 toast 提示。
 *
 * 复制统一走 [UiTextClipboard]（平台 CopyPasteManager）：在 headless 测试环境下
 * 剪贴板写入不影响复制逻辑本身。
 *
 * T-RuntimeSlotFill 券 B：运行期占位必须**在剥注释之前**填掉——占位本身就是一条块注释，
 * 先剥就再也定位不到（会留下 `WHERE ( )`）。产物组装收敛到 [assembleProduct]（纯函数），
 * 与「分享 / 执行 / 导出」共用 [cn.song.mybatisplus.visualizer.share.format.ExecutableSqlFormatter]
 * 同一条回填链，保证四处文本一致。
 */
class CopyExecutableSqlAction(private val project: Project?) {

    companion object {
        /** 通知分组 id（plugin.xml 注册）。 */
        private const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"

        /**
         * T-InjectionGate T-IG-03：产物含 `${}` raw 段时的头部标记注释。
         *
         * 复制不是执行——这条链不弹确认（弹窗克制红线：模态确认只留给执行），改让风险随产物走：
         * 产物粘到别处时读者能看到 raw 段带的是模拟值。SQL 注释行（`--`）不改产物可执行性。
         */
        internal const val DOLLAR_RAW_MARKER = "-- contains \${} raw segments (mock values)"

        /** 模拟占位形态：`{{表达式}}`（T-MbgExamplePreview R1 的预览占位）。 */
        private val PLACEHOLDER_REGEX = Regex("""\{\{[^{}]*}}""")

        /**
         * T-CopyPlaceholderMarker：产物里残留的模拟占位个数（`{{表达式}}` 不是 SQL 注释，
         * `SqlCommentStripper` 不会碰它，会原样跟着产物走）。
         */
        internal fun countSimulationPlaceholders(sql: String): Int =
            PLACEHOLDER_REGEX.findAll(sql).count()

        /**
         * T-CopyPlaceholderMarker：含模拟占位时的头部标记（带计数）。
         *
         * 与 [DOLLAR_RAW_MARKER] 的分工：那条说的是「`${}` 已被模拟值替换」，这条说的是
         * 「`${}` 根本没求到值、产物里以 `{{…}}` 标出」——同一件事不说两遍，有占位时只用这条。
         * 英文与既有标记一致：注释随产物走到 PR / 聊天 / 工单，同一条 SQL 里不混排中英。
         */
        internal fun unresolvedPlaceholderMarker(count: Int): String =
            "-- contains $count unresolved \${} placeholders shown as {{...}}; replace them before executing"

        /**
         * T-CopyPlaceholderMarker：给产物补占位标记（分享面板的复制链复用——它没有
         * [assembleProduct] 的其它标记，只需要这一条）。无占位时原样返回，逐字节不变。
         */
        internal fun withPlaceholderMarker(sql: String): String {
            val count = countSimulationPlaceholders(sql)
            return if (count > 0) unresolvedPlaceholderMarker(count) + "\n" + sql else sql
        }

        /**
         * 复制链产物（纯数据，`execute` 与链路一致用例共用）。
         *
         * @param text 真正写进剪贴板的文本（含头部标记行）
         * @param slotsApplied 被填进 SQL 的运行期占位 id
         * @param warnings 占位没填进去的原因（面板可展示）
         * @param replacedPlaceholderCount 被替换掉的 `?` 个数（toast 用）
         * @param mismatchMarker 占位/参数条数不符时的头部标注行（null = 无）
         * @param simulationPlaceholderCount 产物里残留的 `{{…}}` 模拟占位个数（0 = 无）
         */
        internal data class Product(
            val text: String,
            val slotsApplied: List<String>,
            val warnings: List<SlotWarning>,
            val replacedPlaceholderCount: Int,
            val mismatchMarker: String?,
            val simulationPlaceholderCount: Int = 0,
        )

        /**
         * 组装复制产物（**纯函数**，零 UI / 零剪贴板依赖）。
         *
         * 顺序（P-2 / 券 B 设计要点 3）：运行期占位 → `?`（一次扫描，由
         * [ParameterReplacer.buildExecutableSqlWithSlots] 完成）→ 剥注释。
         */
        internal fun assembleProduct(
            rawSql: String,
            slots: List<RuntimeSlot> = emptyList(),
            slotValues: RuntimeSlotValues = RuntimeSlotValues.EMPTY,
            sources: List<String> = emptyList(),
            paramValues: Map<String, String> = emptyMap(),
            hasDollarRawSegments: Boolean = false,
            dialectName: String? = null,
        ): Product {
            val render = ParameterReplacer.buildExecutableSqlWithSlots(
                sql = rawSql,
                slots = slots,
                slotValues = slotValues,
                sources = sources,
                paramValues = paramValues,
                dialectName = dialectName,
            )
            // 复制时剥离 SQL 注释（与 copySql 对齐）；占位已在上一步换成字面量，不会再被剥掉
            val executable = SqlCommentStripper.strip(render.sql)
            val strippedRaw = SqlCommentStripper.strip(rawSql)

            // T-PSG-04（N-33）：`?` 位点数与参数条数对账——产物必然残留无参数可填的裸 `?` 时在头部
            // 如实标注（不阻断）。只认「占位符多于参数」这一方向：本链拼的是面板**已回填**文本，
            // 位点少于参数是常态（见 PlaceholderCardinality.excessPlaceholderMarker 的 KDoc）。
            val mismatch = if (sources.isEmpty()) {
                null
            } else {
                PlaceholderCardinality.excessPlaceholderMarker(placeholderPositions(strippedRaw).size, sources.size)
            }

            // T-InjectionGate T-IG-03：含 `${}` raw 段时产物头部加标记注释；无标记时产物逐字节不变
            // T-CopyPlaceholderMarker：raw 段求值为空时产物里是 `{{…}}` 占位——改用带计数的占位标记
            // （两者互斥：有占位就不再挂「已用模拟值填充」那条，避免同一件事说两遍）
            val placeholderCount = countSimulationPlaceholders(executable)
            val markers = buildList {
                if (placeholderCount > 0) {
                    add(unresolvedPlaceholderMarker(placeholderCount))
                } else if (hasDollarRawSegments) {
                    add(DOLLAR_RAW_MARKER)
                }
                mismatch?.let { add(it) }
            }
            val text = if (markers.isEmpty()) executable else markers.joinToString("\n") + "\n" + executable
            return Product(
                text = text,
                slotsApplied = render.appliedSlotIds,
                warnings = render.warnings,
                replacedPlaceholderCount = countReplaced(strippedRaw, executable),
                mismatchMarker = mismatch,
                simulationPlaceholderCount = placeholderCount,
            )
        }

        /** 统计被替换的 `?` 数量（原始 SQL 中真实占位数 - 结果中真实占位数；注释/字符串里的 `?` 不计）。 */
        private fun countReplaced(original: String, result: String): Int {
            val originalCount = placeholderPositions(original).size
            val resultCount = placeholderPositions(result).size
            return (originalCount - resultCount).coerceAtLeast(0)
        }
    }

    /**
     * 执行复制：取面板当前 SQL + 参数值 → 回填占位与参数 → 剪贴板 → toast。
     *
     * @param panel 预览面板（取 SQL / 参数值 / 回填提示 / 运行期占位）
     * @param slotValues 运行期占位的用户输入；默认空表 = 占位原样保留（行为零变化）
     * @return 是否成功复制
     */
    fun execute(panel: SqlPreviewPanel, slotValues: RuntimeSlotValues = RuntimeSlotValues.EMPTY): Boolean {
        val raw = panel.editorDocumentText()
            ?.takeIf { it.isNotBlank() } ?: run {
            showToast(MpSqlVisualizerBundle.message("preview.copyExecutableSql.nothing"))
            return false
        }
        val current = panel.current
        val product = assembleProduct(
            rawSql = raw,
            slots = current?.runtimeSlots.orEmpty(),
            slotValues = slotValues,
            // T-JpaParamOrder F3：JPA 占位符名序优先（按名回填），与面板渲染同口径
            sources = panel.backfillSources(),
            paramValues = panel.paramValuesSnapshot(),
            hasDollarRawSegments = panel.hasDollarRawSegments(),
            dialectName = current?.effectiveDialect?.name,
        )

        copyToClipboard(product.text)

        val message = if (product.replacedPlaceholderCount == 0 && product.slotsApplied.isEmpty()) {
            MpSqlVisualizerBundle.message("preview.copyExecutableSql.noParams")
        } else {
            MpSqlVisualizerBundle.message("preview.copyExecutableSql.done", product.replacedPlaceholderCount)
        }
        showToast(message)
        return true
    }

    /** 复制文本到系统剪贴板（统一走 UiTextClipboard 收口）。 */
    private fun copyToClipboard(text: String) {
        UiTextClipboard.copy(text)
    }

    /** Toast 通知（仅在非测试模式下显示，测试模式跳过避免通知系统依赖）。 */
    private fun showToast(message: String) {
        if (com.intellij.openapi.application.ApplicationManager.getApplication().isUnitTestMode) return
        val project = project ?: return
        runCatching {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(message, NotificationType.INFORMATION)
                .notify(project)
        }
    }
}
