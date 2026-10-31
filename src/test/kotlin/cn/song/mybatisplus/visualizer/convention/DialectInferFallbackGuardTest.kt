package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-DialectInferFix 券 06：方言兜底的**反向守卫**。
 *
 * 真机事故的形态是「未知方言 → 映射成 MySQL → PG 合法列名被 MySQL 保留字表判红」。
 * 这类回归在功能测试里很难看出来（MySQL 是「能跑」的方言），所以钉三条源码级不变式：
 *
 *  1. 预览映射编排（`SqlPreviewPanelEditorLifecycle`）**不得**再出现 MySQL 硬兜底
 *     （`MYSQL_LANGUAGE_ID` / `setMySqlOnEdt`），必须走继承入口 `inheritedLanguageId`；
 *  2. `PreviewSourceContext` 不得再长出「声明了没人读」的精确 URL/数据源字段；
 *  3. 日志识别通道必须真的把条目方言传下去（`capturedDialect` 在场）。
 *
 * 每条规则都用合成样本自证「看得见危险形态」，避免空绿。
 *
 * k2j 移植注（生产为 Java，扫描根 src/main/java + .java）：
 *  - 编排件承载为 `ui/SqlPreviewPanelEditorLifecycleKt.java`（顶层函数件后缀惯例），继承入口在
 *    Java 侧是 `DialectPreMapping.inheritedLanguageId(proj)` 限定调用，子串锚点 `inheritedLanguageId` 不变；
 *  - 反查打开入口在 master 是 `log/EntrySourceMatcher.kt` 里的嵌套 `LogEntryPreviewOpener`，Java 侧
 *    独立成 `log/LogEntryPreviewOpener.java`（顶层对象独立成文件模式），锚点随文件名迁移；
 *  - Java 没有具名实参，master 的 `capturedDialect = entry.dialectName` 在 Java 侧是
 *    `toPreviewResult(entry, …, entry.dialectName())` 末位实参形态，锚点改为
 *    「文件内存在 toPreviewResult 调用且把 `entry.dialectName()` 作为实参传入」。
 */
class DialectInferFallbackGuardTest {

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/java").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/java 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    private fun read(path: String): String =
        stripComments(repoRoot().resolve(path).readText())

    /** 去掉行注释与块注释（保留字符串字面量内的内容——守卫只关心代码形态）。 */
    private fun stripComments(code: String): String = code
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .lineSequence()
        .map { line ->
            val idx = line.indexOf("//")
            if (idx >= 0 && !insideString(line, idx)) line.substring(0, idx) else line
        }
        .joinToString("\n")

    private fun insideString(line: String, index: Int): Boolean {
        var quotes = 0
        for (i in 0 until index) {
            if (line[i] == '"' && (i == 0 || line[i - 1] != '\\')) quotes++
        }
        return quotes % 2 == 1
    }

    private fun mappingFallbacks(code: String): List<String> = listOfNotNull(
        "MYSQL_LANGUAGE_ID".takeIf { code.contains(it) }?.let { "未知方言回落 MySQL language id" },
        "setMySqlOnEdt".takeIf { code.contains(it) }?.let { "直接写 MySQL 方言映射" },
    )

    /** Java 侧「条目方言传下去」的文本形态：存在 toPreviewResult 调用且实参里有 entry.dialectName()。 */
    private fun passesEntryDialect(code: String): Boolean =
        code.contains("toPreviewResult") && code.contains("entry.dialectName()")

    @Test
    fun `预览映射编排不得硬编码 MySQL 兜底`() {
        val code = read("src/main/java/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelEditorLifecycleKt.java")
        val violations = mappingFallbacks(code)
        assertTrue("预览方言映射又出现 MySQL 硬兜底：$violations", violations.isEmpty())
        assertTrue(
            "预览映射必须保留平台继承入口 inheritedLanguageId",
            code.contains("inheritedLanguageId"),
        )
    }

    @Test
    fun `来源上下文不得重新长出死字段`() {
        val code = read("src/main/java/cn/song/mybatisplus/visualizer/context/PreviewSourceContext.java")
        assertTrue("PreviewSourceContext 又出现 capturedJdbcUrl（声明后应真正接线，不得留死字段）",
            !code.contains("capturedJdbcUrl"))
        assertTrue("PreviewSourceContext 又出现 selectedDataSourceId",
            !code.contains("selectedDataSourceId"))
    }

    @Test
    fun `日志识别通道必须把条目方言传下去`() {
        val recognizer = read("src/main/java/cn/song/mybatisplus/visualizer/log/LogEntryPreviewRecognizerKt.java")
        assertTrue("toPreviewResult 必须接收 capturedDialect", recognizer.contains("capturedDialect"))
        listOf("SqlLogPanel.java", "HistorySqlLogPanel.java", "LogEntryPreviewOpener.java").forEach { name ->
            val code = read("src/main/java/cn/song/mybatisplus/visualizer/log/$name")
            assertTrue("$name 打开预览时必须把 entry.dialectName() 作为 capturedDialect 实参传入", passesEntryDialect(code))
        }
    }

    @Test
    fun `守卫自证_危险形态必红`() {
        assertTrue(mappingFallbacks("targetId = MYSQL_LANGUAGE_ID").isNotEmpty())
        assertTrue(mappingFallbacks("setMySqlOnEdt(project, vf)").isNotEmpty())
        assertTrue(mappingFallbacks("inheritPlatformPreviewDialect(proj, target)").isEmpty())
        // 注释里的历史说明不算违规（stripComments 之后看不到）
        assertTrue(mappingFallbacks(stripComments("// 旧实现是 MYSQL_LANGUAGE_ID 兜底")).isEmpty())
        // Java 侧方言传参锚点：传了绿、没传红
        assertTrue(passesEntryDialect("Object r = LogEntryPreviewRecognizerKt.toPreviewResult(entry, true, true, entry.dialectName());"))
        assertTrue(!passesEntryDialect("Object r = LogEntryPreviewRecognizerKt.toPreviewResult(entry, true, true, null);"))
    }
}
