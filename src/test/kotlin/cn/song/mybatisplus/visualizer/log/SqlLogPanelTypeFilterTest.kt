package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Font
import javax.swing.JButton

/**
 * SqlLogPanelTypeFilter 专项测试（20261104 262 沙盒验收缺口 1 补齐）。
 *
 * 覆盖该文件（含同组 [LogEntryFiltering] 中类型过滤纯函数）的**纯逻辑面**，不实例化真实 UI、
 * 不引入 BasePlatformTestCase（平台测试全程持写意图，且不在 testFast 桶）：
 *
 *  - 六类可筛枚举与顺序（SELECT/INSERT/UPDATE/DELETE/CREATE/DROP，OTHER 不单列）；
 *  - 过滤谓词 [filterByType]：全开透传（含 OTHER）/ 仅关查询 / 全关；
 *  - 触发钮摘要三态（[SqlLogPanelTypeFilter.updateTypeFilterButton]）：全部 / 无 / N-M 组合
 *    （显示文本按 64px 预算截尾省略、tooltip 恒全文）；
 *  - 弹窗「全选/全不选」切换钮文案判定（[SqlLogPanelTypeFilter.typePopupToggleLabel]）；
 *  - 状态持久化契约：类型筛选态为**会话级**（新面板默认全开，不进 PluginSettings）。
 *
 * **裸实例说明**：摘要文案与切换钮文案是 `SqlLogPanel` 的扩展函数，判定只读 `activeFilter`、
 * 写 `typeFilterButton` 两个字段。为不跑真实构造器（init 全量建 UI、需平台服务），用
 * `Unsafe.allocateInstance` 造裸实例并反射注入这两个字段——生产 `when` 判定代码原样执行。
 * 面板构造期默认全开（`activeFilter = FILTERABLE_TYPES.toMutableSet()`）这一初始化契约
 * 属构造器行为，裸实例测不到，由既有 SqlLogPanelTypePopupToggleTest（平台桶）覆盖。
 */
class SqlLogPanelTypeFilterTest {

    private fun label(key: String) = MpSqlVisualizerBundle.message(key)

    private fun entry(type: StatementType): LogSqlEntry {
        val sql = when (type) {
            StatementType.SELECT -> "SELECT * FROM t"
            StatementType.INSERT -> "INSERT INTO t VALUES (1)"
            StatementType.UPDATE -> "UPDATE t SET x = 1"
            StatementType.DELETE -> "DELETE FROM t"
            StatementType.CREATE -> "CREATE TABLE t (id INT)"
            StatementType.DROP -> "DROP TABLE t"
            StatementType.OTHER -> "MERGE INTO t USING s ON (1=1)"
        }
        return LogSqlEntry(rawSql = sql, restoredSql = sql, params = emptyList(), timestamp = 0L)
    }

    // ---------- 六类枚举契约 ----------

    @Test
    fun test可筛类型为六类且OTHER不单列() {
        assertEquals(
            "弹窗可勾选六类（四 DML + 两 DDL）且顺序即弹窗展示序",
            listOf(
                StatementType.SELECT,
                StatementType.INSERT,
                StatementType.UPDATE,
                StatementType.DELETE,
                StatementType.CREATE,
                StatementType.DROP,
            ),
            FILTERABLE_TYPES,
        )
        assertFalse("OTHER 不单列（全选时随透传语义覆盖）", StatementType.OTHER in FILTERABLE_TYPES)
    }

    @Test
    fun test类型文案映射六类且两两互异() {
        val expected = mapOf(
            StatementType.SELECT to "log.filter.select",
            StatementType.INSERT to "log.filter.insert",
            StatementType.UPDATE to "log.filter.update",
            StatementType.DELETE to "log.filter.delete",
            StatementType.CREATE to "log.filter.create",
            StatementType.DROP to "log.filter.drop",
        )
        expected.forEach { (type, key) ->
            assertEquals("类型文案映射错位：$type", label(key), labelForType(type))
        }
        val labels = FILTERABLE_TYPES.map { labelForType(it) }
        assertEquals("六类文案必须两两互异（换臂/复制臂立即红）", labels.size, labels.toSet().size)
        assertEquals("非可筛类型兜底返回字面 OTHER", "OTHER", labelForType(StatementType.OTHER))
    }

    // ---------- 过滤谓词（filterByType） ----------

    @Test
    fun test默认全开_六类与OTHER全放行() {
        val entries = FILTERABLE_TYPES.map { entry(it) } + entry(StatementType.OTHER)
        assertEquals("全选 = 「全部」语义，含 OTHER 透传", entries, filterByType(entries, FILTERABLE_TYPES.toSet()))
    }

    @Test
    fun test仅关查询_SELECT被拒其余仍过() {
        val entries = (FILTERABLE_TYPES + StatementType.OTHER).map { entry(it) }
        val active = FILTERABLE_TYPES.toSet() - StatementType.SELECT
        val filtered = filterByType(entries, active)
        assertEquals(
            "SELECT 被拒；其余五类仍过；OTHER 不在可筛集内、部分勾选态随隐藏（文档化契约）",
            listOf(StatementType.INSERT, StatementType.UPDATE, StatementType.DELETE, StatementType.CREATE, StatementType.DROP),
            filtered.map { it.statementType },
        )
        assertFalse("SELECT 条目不得残留", filtered.any { it.statementType == StatementType.SELECT })
    }

    @Test
    fun test全关谓词零输出() {
        val entries = listOf(entry(StatementType.SELECT), entry(StatementType.OTHER))
        assertTrue("全不选 = 列表清空", filterByType(entries, emptySet()).isEmpty())
    }

    // ---------- 触发钮摘要三态（updateTypeFilterButton，裸实例驱动真实判定） ----------

    /** 造裸 [SqlLogPanel]（跳过构造器），注入 activeFilter 与 typeFilterButton 两个字段。 */
    private fun barePanelWith(active: Set<StatementType>): SqlLogPanel {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null) as sun.misc.Unsafe
        @Suppress("UNCHECKED_CAST")
        val panel = unsafe.allocateInstance(SqlLogPanel::class.java) as SqlLogPanel
        setField(panel, "activeFilter", active.toMutableSet())
        // 显式给字体：headless 下未 installUI 的 JButton 字体可为 null，getFontMetrics 会 NPE
        setField(panel, "typeFilterButton", JButton().apply { font = Font(Font.DIALOG, Font.PLAIN, 12) })
        return panel
    }

    private fun setField(target: Any, name: String, value: Any?) {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val field = cls.getDeclaredField(name)
                field.isAccessible = true
                field.set(target, value)
                return
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        error("字段不存在：${target.javaClass.simpleName}.$name")
    }

    private fun typeFilterButtonOf(panel: SqlLogPanel): JButton = panel.typeFilterButtonForTest

    @Test
    fun test摘要_全开显示全部() {
        val panel = barePanelWith(FILTERABLE_TYPES.toSet())
        panel.updateTypeFilterButton()
        val button = typeFilterButtonOf(panel)
        assertEquals("全开态触发钮显示「全部」", label("log.filter.all"), button.text)
        assertEquals(
            "tooltip 恒为「SQL类型：」+ 完整状态",
            label("log.filter.type.label") + label("log.filter.all"),
            button.toolTipText,
        )
    }

    @Test
    fun test摘要_全关显示无() {
        val panel = barePanelWith(emptySet())
        panel.updateTypeFilterButton()
        val button = typeFilterButtonOf(panel)
        assertEquals("全关态触发钮显示「无」", label("log.filter.type.none"), button.text)
        assertEquals(
            label("log.filter.type.label") + label("log.filter.type.none"),
            button.toolTipText,
        )
    }

    @Test
    fun test摘要_部分组合按可筛顺序拼接() {
        // 乱序存入：断言组合文案按 FILTERABLE_TYPES 顺序（而非集合插入序）拼接
        val panel = barePanelWith(linkedSetOf(StatementType.UPDATE, StatementType.SELECT))
        panel.updateTypeFilterButton()
        val button = typeFilterButtonOf(panel)
        val full = label("log.filter.select") + ", " + label("log.filter.update")
        assertEquals("tooltip 恒全文：N-M 组合", label("log.filter.type.label") + full, button.toolTipText)
        val shown = button.text
        assertTrue(
            "按钮显示 = 全文或按预算截尾省略（64px 预算下中文组合大概率截尾）：$shown",
            shown == full || (shown.endsWith("\u2026") && full.startsWith(shown.removeSuffix("\u2026"))),
        )
    }

    // ---------- 弹窗「全选/全不选」切换钮文案判定（T-SelToggle） ----------

    @Test
    fun test切换钮文案_全勾为全不选_其余为全选() {
        assertEquals(
            "全勾态 → 「全不选」（点击清空）",
            label("log.filter.selectNone"),
            barePanelWith(FILTERABLE_TYPES.toSet()).typePopupToggleLabel(),
        )
        assertEquals(
            "部分勾选态 → 「全选」（点击填满）",
            label("log.filter.selectAll"),
            barePanelWith(setOf(StatementType.SELECT)).typePopupToggleLabel(),
        )
        assertEquals(
            "空集态 → 「全选」（与外层「无」同口径的剩余态）",
            label("log.filter.selectAll"),
            barePanelWith(emptySet()).typePopupToggleLabel(),
        )
    }

    // ---------- 状态持久化契约 ----------

    /**
     * 类型筛选态是**会话级**：每次建面板默认全开，不挂 PluginSettings（持久化仅
     * `lastHighlightedVersion` 等内部状态位与用户设置项）。守卫：State 里出现
     * typeFilter/activeFilter 形态字段即红——届时应同步补 roundtrip 用例。
     */
    @Test
    fun test类型筛选态不进PluginSettings持久化() {
        val stateFields = PluginSettings.State::class.java.declaredFields.map { it.name }
        val leaked = stateFields.filter {
            it.contains("typeFilter", ignoreCase = true) ||
                it.contains("activeFilter", ignoreCase = true) ||
                it.contains("typeSelection", ignoreCase = true)
        }
        assertTrue("类型筛选态不得进 PluginSettings.State（会话级契约）：$leaked", leaked.isEmpty())
    }
}
