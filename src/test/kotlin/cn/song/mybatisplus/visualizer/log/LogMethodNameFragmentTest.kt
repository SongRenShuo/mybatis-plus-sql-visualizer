package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * T-LogMethodName：来源位置后的「类名#方法名」片段。
 *
 * 覆盖三类判据：
 *  - 归一化口径（`mapperStatementLabel`）：桥通道 FQN、agent 帧、混写、内部类、五种不可解析形态；
 *  - 开关门控：默认关时不产出片段（存量形态逐字节不变），打开后才追加；
 *  - 组合顺序：来源位置在前、方法名在后（渲染器与 `metadata()` 同一口径）。
 *
 * 语义背景：控制台通道的 `mapperStatement` 是 mapper 语句 id，与来源文件不是同一个类；
 * 增强捕获通道是同一栈帧。因此两段并排、不合并——场景清单见开发计划 §3.3。
 */
class LogMethodNameFragmentTest : BasePlatformTestCase() {

    private fun entry(
        mapperStatement: String?,
        sourceFile: String? = "src/main/java/com/example/mapper/UserMapper.java",
        sourceLine: Int = 41,
    ) = LogSqlEntry(
        rawSql = "SELECT 1",
        restoredSql = "SELECT 1",
        params = emptyList(),
        timestamp = 1_700_000_000_000L,
        sourceFile = sourceFile,
        sourceLine = sourceLine,
        mapperStatement = mapperStatement,
    )

    private fun <T> withShowMethod(enabled: Boolean, block: () -> T): T {
        val settings = PluginSettings.getInstance()
        val snapshot = settings.state.copy()
        try {
            settings.state.showMethodInLogList = enabled
            return block()
        } finally {
            settings.loadState(snapshot)
        }
    }

    // ---------------- 归一化口径 ----------------

    fun test桥通道语句id归一化为简单类名与方法名() {
        assertEquals(
            "mapper 语句 id 应取简单类名 + 方法名",
            "UserMapper#selectById",
            mapperStatementLabel("com.example.mapper.UserMapper.selectById"),
        )
    }

    fun test增强捕获帧形态原样保留() {
        assertEquals(
            "agent 栈帧本来就是 类名#方法名，原样保留",
            "DeviceInfoServiceImpl#joinRightAlias",
            mapperStatementLabel("DeviceInfoServiceImpl#joinRightAlias"),
        )
    }

    fun test混写包名与井号形态取简单类名() {
        assertEquals("UserMapper#selectById", mapperStatementLabel("com.example.mapper.UserMapper#selectById"))
    }

    fun test内部类保留美元符() {
        assertEquals("Outer\$Inner#selectById", mapperStatementLabel("com.example.Outer\$Inner.selectById"))
        assertEquals("Outer\$1#run", mapperStatementLabel("Outer\$1#run"))
    }

    fun test不可解析的语句id返回null() {
        val bad = listOf(
            null, "", "   ",
            "selectById",                 // 无 . 无 #：判不出类名
            "com.x.UserMapper.",          // 方法段为空
            "#selectById",                // 类名为空
            "com.x.UserMapper.#",         // 两段都空
            ".selectById",                // 类名为空
        )
        for (raw in bad) {
            assertNull("不可解析应返回 null（宁可不显示）: <$raw>", mapperStatementLabel(raw))
        }
    }

    // ---------------- 开关门控与组合 ----------------

    fun test开关关闭时不产出方法名片段() {
        val fragments = withShowMethod(false) {
            SqlEntryPresentation.headerSourceFragments(entry("com.example.mapper.UserMapper.selectById"))
        }
        assertEquals("默认关闭时只有来源片段（存量形态不变）", 1, fragments.size)
        assertEquals("[UserMapper.java:41] ", fragments[0].text)
    }

    fun test开关打开时来源片段顺序为文件位置在前方法名在后() {
        val fragments = withShowMethod(true) {
            SqlEntryPresentation.headerSourceFragments(entry("com.example.mapper.UserMapper.selectById"))
        }
        assertEquals("来源 + 方法名两段", 2, fragments.size)
        assertEquals("[UserMapper.java:41] ", fragments[0].text)
        assertEquals("[UserMapper#selectById] ", fragments[1].text)
    }

    fun test无语句id时开关打开也不产出方法名片段() {
        val fragments = withShowMethod(true) {
            SqlEntryPresentation.headerSourceFragments(entry(null))
        }
        assertEquals("Hibernate / JdbcTemplate / 旧条目没有方法名，保持原样", 1, fragments.size)
    }

    fun testMP分页计数条目只显示来源占位不显示方法名() {
        val fragments = withShowMethod(true) {
            SqlEntryPresentation.headerSourceFragments(
                entry(null, sourceFile = SourceMarkers.MP_PAGINATION_COUNT, sourceLine = 0),
            )
        }
        assertEquals(1, fragments.size)
        assertEquals("[MP 分页计数] ", fragments[0].text)
    }

    fun testmetadata口径与渲染器一致() {
        val fragments = withShowMethod(true) {
            SqlEntryPresentation.metadata(entry("com.example.mapper.UserMapper.selectById"), false, false, emptyMap())
        }
        val texts = fragments.map { it.text }
        val sourceIdx = texts.indexOf("[UserMapper.java:41] ")
        val methodIdx = texts.indexOf("[UserMapper#selectById] ")
        assertTrue("来源片段应在场: $texts", sourceIdx >= 0)
        assertTrue("方法名片段应在场: $texts", methodIdx >= 0)
        assertEquals("方法名必须紧跟来源位置之后: $texts", sourceIdx + 1, methodIdx)
    }
}
