package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-TemplateHook（FR-0045 第 3 项 MVP）接线守卫（源码扫描，纯逻辑零平台依赖，进 testFast 快桶）。
 *
 * 守的是什么：外部模板渲染是**默认关闭**的可选能力，接线顺序一旦漂移就是两类静默故障——
 *  1. 挂点①跑到 `STATEMENT_TAGS` 爬升**之后**：`<sqls>/<sql id>` 不在 mapper 语句名单里，
 *     爬升先失败，外部渲染永远不会被问到（＝用户 FR-0045 的报障原样复发，绿灯下无声失效）；
 *  2. 外部调用没有 `isActive()` 门：默认关闭的用户也会被启动外部进程（语义从「零行为变更」
 *     退化为「默认执行用户没配的命令」）。
 *
 * 三条断言都落在**真实源码**上（不喂合成样本），故「摘掉接线 → 变红」是这套守卫的灵敏度定义：
 *
 *  - 断言一：`McpSourceLocator` 里首个 `externalStep(` 调用点必须**早于** STATEMENT_TAGS 爬升循环；
 *  - 断言二：该调用点之前（同一 XML 分支内、最近 12 行内）必须出现 `isActive()` 空判门；
 *  - 断言三：`ExternalTemplateSettings` 的 `enabled` 默认值必须是 `false`（关闭＝零行为变更的前提）。
 *
 * 扫描前剥注释（与 [BannedApiSourceScanTest] 同口径：注释里常留着事故现场的旧写法）。
 * 源码定位仿收编口径（`InFunctionRegexSourceScanTest` / `SettingsTruthTest`）。
 */
class ExternalTemplateWiringSourceScanTest {

    private val locatorPath = "src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpSourceLocator.kt"
    private val settingsPath = "src/main/kotlin/cn/song/mybatisplus/visualizer/config/ExternalTemplateSettings.kt"

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    /** 逐字符等长涂白注释（保行结构，行号可对齐）。 */
    private fun maskComments(text: String): String {
        val noBlock = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(text) { m ->
            m.value.map { if (it == '\n') '\n' else ' ' }.joinToString("")
        }
        return Regex("//[^\n]*").replace(noBlock) { " ".repeat(it.value.length) }
    }

    /** 真实源码（已剥注释）。 */
    private fun source(rel: String): String {
        val file = File(repoRoot(), rel)
        assertTrue("受守卫文件必须存在：$rel", file.isFile)
        return maskComments(file.readText())
    }

    @Test
    fun `外部渲染挂点必须早于STATEMENT_TAGS爬升`() {
        val src = source(locatorPath)
        val climbIndex = src.indexOf("in XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS")
        assertTrue(
            "McpSourceLocator 里找不到 STATEMENT_TAGS 爬升循环——守卫失去判别面，先修接线再验",
            climbIndex > 0,
        )
        val externalIndex = src.indexOf("externalStep(")
        assertTrue(
            "McpSourceLocator 里找不到 externalStep( 调用点（T-TemplateHook 接线被摘除/改名？" +
                "外部模板渲染失效且没有任何用例报错）",
            externalIndex >= 0,
        )
        assertTrue(
            "外部模板渲染挂点必须出现在 STATEMENT_TAGS 爬升之前（<sqls>/<sql id> 不在 mapper 语句名单，" +
                "爬升先跑就永远轮不到外部渲染——FR-0045 报障原样复发）。" +
                "当前 externalStep@$externalIndex vs STATEMENT_TAGS@$climbIndex",
            externalIndex < climbIndex,
        )
    }

    @Test
    fun `外部渲染挂点必须有isActive门_默认关闭不启动任何进程`() {
        val src = source(locatorPath)
        val externalIndex = src.indexOf("externalStep(")
        assertTrue("找不到 externalStep( 调用点", externalIndex >= 0)
        val before = src.substring(0, externalIndex)
        val recent = before.lines().takeLast(12).joinToString("\n")
        assertTrue(
            "externalStep( 之前 12 行内必须出现 isActive() 空判门（默认关闭＝零行为变更；" +
                "无门的形态会让没配命令的用户也被启动外部进程）：\n$recent",
            recent.contains("isActive()"),
        )
    }

    @Test
    fun `外部模板开关出厂默认必须为false`() {
        val src = source(settingsPath)
        assertTrue(
            "ExternalTemplateSettings.State 的 enabled 出厂默认必须是 false（关闭时整条渲染链零行为变更）",
            Regex("""var\s+enabled\s*:\s*Boolean\s*=\s*false""").containsMatchIn(src),
        )
    }

    /**
     * 自证（灵敏度）：把合成源码喂进同一判定口径——「挂点后置」与「无 isActive 门」两种故障形态
     * 必须被判为违规。合成样本只证明规则看得见这两种形态，真实源码的绿灯由上面三条断言承担。
     */
    @Test
    fun `自证_挂点后置与无门形态必被判红`() {
        val violated = """
            if (psiFile is XmlFile) {
                var tag: XmlTag? = PsiTreeUtil.getParentOfType(element, XmlTag::class.java)
                while (tag != null && tag.name !in XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS) {
                    tag = tag.parentTag
                }
                externalStep(project, psiFile, tag!!, settings, paramsJson)
            }
        """.trimIndent()
        val climbIdx = violated.indexOf("in XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS")
        val extIdx = violated.indexOf("externalStep(")
        assertTrue("自证：挂点后置必须被判违规", extIdx > climbIdx)

        val gated = """
            if (psiFile is XmlFile) {
                if (settings.isActive()) {
                    externalStep(project, psiFile, tagAtCursor, settings, paramsJson)?.let { return it }
                }
                var tag: XmlTag? = PsiTreeUtil.getParentOfType(element, XmlTag::class.java)
                while (tag != null && tag.name !in XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS) {
                    tag = tag.parentTag
                }
            }
        """.trimIndent()
        val gatedExt = gated.indexOf("externalStep(")
        val gatedRecent = gated.substring(0, gatedExt).lines().takeLast(12).joinToString("\n")
        assertTrue("自证：合法形态（前置 + isActive 门）应通过", gatedRecent.contains("isActive()"))
        assertTrue(
            "自证：合法形态的挂点仍须早于爬升",
            gatedExt < gated.indexOf("in XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS"),
        )

        val ungated = """
            if (psiFile is XmlFile) {
                externalStep(project, psiFile, tagAtCursor, settings, paramsJson)?.let { return it }
            }
        """.trimIndent()
        val ungatedExt = ungated.indexOf("externalStep(")
        val ungatedRecent = ungated.substring(0, ungatedExt).lines().takeLast(12).joinToString("\n")
        assertTrue("自证：无 isActive 门必须被判违规", !ungatedRecent.contains("isActive()"))
    }
}
