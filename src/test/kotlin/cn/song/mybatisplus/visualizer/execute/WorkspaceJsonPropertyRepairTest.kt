package cn.song.mybatisplus.visualizer.execute

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-WorkspaceHeal 行为测试：`repairPropertiesComponentText` 的定点修复判定。
 *
 * 夹具来自真机实证（`mybatis-plus-sql-visualizer-demo/.idea/workspace.xml`）：那次损坏**只削掉了
 * 值的开头引号**，其余条目完好，平台因此整块反序列化失败、每次启动报
 * `JsonDecodingException: Expected quotation mark '"', but had '8'`。这里按同一形态造语料。
 *
 * **反向验证**：把 `repairOneLine` 里「补回引号」的分支摘掉（改为直接返回 null），
 * [test真实损坏行补回开头引号] 与 [test三个键同时损坏全部修好] 必红。
 */
class WorkspaceJsonPropertyRepairTest {

    /**
     * 健康形态（脱敏：项目路径换 `F:/demo`，UUID 保持真机原样）。
     * 注意 `.id` 与 `.id.external:` 两条**值相同**——夹具必须让它们可区分，
     * 否则按值做 replace 会同时命中两行（造语料时踩过一次）。
     */
    private fun healthySample(): String = """
        {
          &quot;keyToString&quot;: {
            &quot;git-widget-placeholder&quot;: &quot;main&quot;,
            &quot;mpsql.visualizer.last.runner&quot;: &quot;MySQL&quot;,
            &quot;mpsql.visualizer.last.runner.id&quot;: &quot;8d6c2a1b-f817-44fd-a3db-6798b116bc78&quot;,
            &quot;mpsql.visualizer.last.runner.id.external:Maven:F:/demo/pom.xml&quot;: &quot;8d6c2a1b-f817-44fd-a3db-6798b116bc78&quot;,
            &quot;mpsql.visualizer.last.runner.modules&quot;: &quot;external:Maven:F:/demo/pom.xml&quot;
          },
          &quot;keyToStringList&quot;: {}
        }
    """.trimIndent()

    /** 真机损坏形态：`last.runner.id` 这条值的**开头引号**被削掉。 */
    private fun damagedSample(): String = healthySample().replace(
        "&quot;mpsql.visualizer.last.runner.id&quot;: &quot;",
        "&quot;mpsql.visualizer.last.runner.id&quot;: ",
    )

    /** 键值后半段（`id` 与 `id.external` 共用同一 UUID，故要与键名连起来才唯一）。 */
    private val idValueTail = "6798b116bc78&quot;,"

    private fun assertValidJson(escapedBlock: String) {
        val plain = escapedBlock
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        assertTrue("修复产物必须能被平台按 MyState 形态加载：$escapedBlock", isPlatformLoadable(plain))
    }

    /**
     * **踩过的坑**（本功能首版三例测试红的根因）：`Json.parseToJsonElement` 对**未加引号的值是宽松的**，
     * 而平台走 `BasePropertyService.MyState`（`Map<String, String>`）的流式严格解码才会报
     * `Expected quotation mark`。拿宽松解析器的成败当判据，真机这个损坏形态会被判「健康」、自愈永不触发。
     * 本用例把两者的差异钉死，防止有人把校验改回 `parseToJsonElement`。
     */
    @Test
    fun test裸值必须判不可加载即使宽松解析器放过它() {
        val bare = "{\"keyToString\":{\"mpsql.visualizer.last.runner.id\": 8d6c2a1b-f817}}"
        assertTrue(
            "前提：宽松解析器确实会放过裸值（正是本功能踩过的坑）",
            runCatching { Json.parseToJsonElement(bare) }.isSuccess,
        )
        assertFalse("平台判据必须判它不可加载（摘掉字符串值校验即红）", isPlatformLoadable(bare))
    }

    @Test
    fun test健康组件文本原样放行() {
        assertEquals("健康工程必须判 Healthy 且零改动", RepairOutcome.Healthy, repairPropertiesComponentText(healthySample()))
    }

    @Test
    fun test真实损坏行补回开头引号() {
        val outcome = repairPropertiesComponentText(damagedSample())
        assertTrue("真机损坏样本必须被判为已修：$outcome", outcome is RepairOutcome.Repaired)
        outcome as RepairOutcome.Repaired
        assertEquals("只应点出一条被修的键", listOf("mpsql.visualizer.last.runner.id"), outcome.fixedKeys)
        assertTrue(
            "值必须补回成对引号",
            outcome.text.contains("&quot;mpsql.visualizer.last.runner.id&quot;: &quot;8d6c2a1b-f817-44fd-a3db-$idValueTail"),
        )
        assertFalse("不得留下裸值形态", outcome.text.contains("&quot;: 8d6c2a1b-"))
        assertValidJson(outcome.text)
    }

    @Test
    fun test缺尾引号也能修() {
        val damaged = healthySample().replace(
            "&quot;mpsql.visualizer.last.runner.id&quot;: &quot;8d6c2a1b-f817-44fd-a3db-6798b116bc78&quot;,",
            "&quot;mpsql.visualizer.last.runner.id&quot;: &quot;8d6c2a1b-f817-44fd-a3db-6798b116bc78,",
        )
        val outcome = repairPropertiesComponentText(damaged)
        assertTrue("缺尾引号同样要修：$outcome", outcome is RepairOutcome.Repaired)
        outcome as RepairOutcome.Repaired
        assertTrue(outcome.text.contains("&quot;mpsql.visualizer.last.runner.id&quot;: &quot;8d6c2a1b-f817-44fd-a3db-$idValueTail"))
        assertValidJson(outcome.text)
    }

    @Test
    fun test两端引号都缺也能修() {
        val damaged = damagedSample().replace(
            "&quot;mpsql.visualizer.last.runner.id&quot;: 8d6c2a1b-f817-44fd-a3db-6798b116bc78&quot;,",
            "&quot;mpsql.visualizer.last.runner.id&quot;: 8d6c2a1b-f817-44fd-a3db-6798b116bc78,",
        )
        val outcome = repairPropertiesComponentText(damaged)
        assertTrue(outcome is RepairOutcome.Repaired)
        outcome as RepairOutcome.Repaired
        assertTrue(outcome.text.contains("&quot;mpsql.visualizer.last.runner.id&quot;: &quot;8d6c2a1b-f817-44fd-a3db-$idValueTail"))
        assertValidJson(outcome.text)
    }

    @Test
    fun test载荷无法安全重建时降级为空串() {
        // 载荷含反斜杠：包成 JSON 字符串会破坏转义语义，只能丢值保 JSON 合法
        val damaged = damagedSample().replace(
            "&quot;mpsql.visualizer.last.runner.id&quot;: 8d6c2a1b-f817-44fd-a3db-6798b116bc78&quot;,",
            "&quot;mpsql.visualizer.last.runner.id&quot;: C:\\bad\\path&quot;,",
        )
        val outcome = repairPropertiesComponentText(damaged)
        assertTrue("仍应判为已修（降级形态）：$outcome", outcome is RepairOutcome.Repaired)
        outcome as RepairOutcome.Repaired
        assertTrue(
            "不可安全重建时必须降级为空串",
            outcome.text.contains("&quot;mpsql.visualizer.last.runner.id&quot;: &quot;&quot;,"),
        )
        assertValidJson(outcome.text)
    }

    @Test
    fun test别的插件的键坏了不碰我们的数据() {
        // 只坏别人的键：本插件一律不动（不替别人做决定，也不冒写坏的风险）
        val foreignOnly = healthySample().replace(
            "&quot;git-widget-placeholder&quot;: &quot;main&quot;,",
            "&quot;git-widget-placeholder&quot;: main,",
        )
        assertEquals(
            "只有别人的键坏时必须判 NoOwnDamage（不写任何字节）",
            RepairOutcome.NoOwnDamage,
            repairPropertiesComponentText(foreignOnly),
        )
    }

    @Test
    fun test修复是幂等的() {
        val once = repairPropertiesComponentText(damagedSample()) as RepairOutcome.Repaired
        assertEquals("修完再跑必须判 Healthy", RepairOutcome.Healthy, repairPropertiesComponentText(once.text))
    }

    @Test
    fun test三个键同时损坏全部修好() {
        val allDamaged = healthySample()
            .replace(
                "&quot;mpsql.visualizer.last.runner&quot;: &quot;MySQL&quot;",
                "&quot;mpsql.visualizer.last.runner&quot;: MySQL&quot;",
            )
            .replace(
                "&quot;mpsql.visualizer.last.runner.modules&quot;: &quot;external:Maven:F:/demo/pom.xml&quot;",
                "&quot;mpsql.visualizer.last.runner.modules&quot;: external:Maven:F:/demo/pom.xml&quot;",
            )
            .replace(
                "&quot;mpsql.visualizer.last.runner.id&quot;: &quot;",
                "&quot;mpsql.visualizer.last.runner.id&quot;: ",
            )
        val outcome = repairPropertiesComponentText(allDamaged)
        assertTrue(outcome is RepairOutcome.Repaired)
        outcome as RepairOutcome.Repaired
        assertEquals("三条本插件键都要点名", 3, outcome.fixedKeys.size)
        assertTrue(outcome.text.contains("&quot;mpsql.visualizer.last.runner&quot;: &quot;MySQL&quot;,"))
        assertTrue(
            outcome.text.contains(
                "&quot;mpsql.visualizer.last.runner.modules&quot;: &quot;external:Maven:F:/demo/pom.xml&quot;",
            ),
        )
        assertValidJson(outcome.text)
    }

    @Test
    fun testCRLF工程文件修完仍是CRLF() {
        val crlf = damagedSample().replace("\n", "\r\n")
        val outcome = repairPropertiesComponentText(crlf) as RepairOutcome.Repaired
        assertFalse("不得留下裸 LF（原文件是 CRLF）", Regex("[^\\r]\\n").containsMatchIn(outcome.text))
        assertTrue("应保留 CRLF", outcome.text.contains("\r\n"))
        assertValidJson(outcome.text)
    }

    /**
     * 前缀契约：修复器只认 `mpsql.visualizer.` 前缀，若生产侧键名改了前缀，自愈会静默失效。
     * 这里按源码字面量对账（与 `DataSourceSelector` 的三个键常量同源）。
     */
    @Test
    fun test生产侧键前缀与修复器前缀一致() {
        val source = File(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelector.kt",
        ).readText()
        listOf(
            "\"mpsql.visualizer.last.runner\"",
            "\"mpsql.visualizer.last.runner.id\"",
            "\"mpsql.visualizer.last.runner.modules\"",
        ).forEach { literal ->
            assertTrue("生产侧键 $literal 必须仍以 $OWN_PROPERTY_KEY_PREFIX 开头", literal.startsWith("\"$OWN_PROPERTY_KEY_PREFIX"))
            assertTrue("DataSourceSelector 里找不到键字面量 $literal（键名被改则会静默失配）", source.contains(literal))
        }
    }

    /**
     * 整文件形态（照真机 workspace.xml 的排版：组件内容紧跟开标签、其它组件前后都在）。
     * 用来钉住「除被修那一行外逐字节不变」——工程文件是用户资产。
     */
    private fun damagedWorkspaceFile(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <project version="4">
          <component name="ChangeListManager">
            <list default="true" id="a1" name="Default" comment="" />
          </component>
          <component name="PropertiesComponent">{
          &quot;keyToString&quot;: {
            &quot;git-widget-placeholder&quot;: &quot;main&quot;,
            &quot;mpsql.visualizer.last.runner.id&quot;: 8d6c2a1b-f817-44fd-a3db-6798b116bc78&quot;,
            &quot;mpsql.visualizer.last.runner.modules&quot;: &quot;external:Maven:F:/demo/pom.xml&quot;
          },
          &quot;keyToStringList&quot;: {}
        }</component>
          <component name="RunManager" selected="Application.Foo" />
        </project>
    """.trimIndent()

    @Test
    fun test整文件只改坏的那一行() {
        val raw = damagedWorkspaceFile()
        val outcome = repairWorkspaceFileText(raw)
        assertTrue("整文件级也必须判已修：$outcome", outcome is RepairOutcome.Repaired)
        outcome as RepairOutcome.Repaired
        assertEquals("被修的键应只有一条", listOf("mpsql.visualizer.last.runner.id"), outcome.fixedKeys)

        val before = raw.split('\n')
        val after = outcome.text.split('\n')
        assertEquals("行数不得变化（定点手术不该增删行）", before.size, after.size)
        val changedLines = before.indices.filter { before[it] != after[it] }
        assertEquals("只允许被修的那一行变化，实际变了 $changedLines", 1, changedLines.size)
        assertTrue(after[changedLines[0]].contains("&quot;: &quot;8d6c2a1b-f817-44fd-a3db-6798b116bc78&quot;,"))
        // 前后文原样保留
        assertTrue(outcome.text.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(outcome.text.contains("<component name=\"RunManager\" selected=\"Application.Foo\" />"))
    }

    @Test
    fun test整文件没有本插件前缀时零改动() {
        val raw = damagedWorkspaceFile().replace("mpsql.visualizer.", "other.plugin.")
        assertEquals("没有本插件键的工程必须判健康、零写入", RepairOutcome.Healthy, repairWorkspaceFileText(raw))
    }

    @Test
    fun test整文件缺少Properties组件时判健康() {
        val raw = "<project version=\"4\">\n  <component name=\"Other\" />\n</project>"
        assertEquals(RepairOutcome.Healthy, repairWorkspaceFileText(raw))
    }

    /** 没有本插件键的组件：健康则放行，坏则判「不归我管」。 */
    @Test
    fun test无本插件键的组件() {
        assertEquals(RepairOutcome.Healthy, repairPropertiesComponentText("{}"))
        assertEquals(RepairOutcome.Healthy, repairPropertiesComponentText("{\n  &quot;keyToString&quot;: {}\n}"))
        assertEquals(
            RepairOutcome.NoOwnDamage,
            repairPropertiesComponentText("{\n  &quot;git-widget-placeholder&quot;: main,\n}"),
        )
    }
}