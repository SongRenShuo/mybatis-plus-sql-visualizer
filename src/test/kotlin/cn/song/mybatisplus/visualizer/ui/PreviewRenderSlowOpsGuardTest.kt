package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 预览重渲染链 EDT 慢操作守卫（T-PreviewEdtGuard S1 防回潮钉）。
 *
 * ## 归因依据（2026-09-24 沙盒实栈，主树 `.intellijPlatform/sandbox/.../log/idea.log:1263`）
 *
 * `SyntaxHighlighterFactory.getSyntaxHighlighter` → SQL 插件 `SqlLanguageSubstitutor.getSqlLanguageDialect`
 * → `SqlDialectMappings.getMapping` → `PerFileMappingsBase.ensureStateLoaded` → `VirtualFileManager.findFileByUrl`
 * → `LocalFileSystem.findFileByPath` → `VirtualDirectoryImpl.findChild` → `PersistentFSImpl.findChildInfo`
 * → `FSRecordsImpl.update` → **SlowOperations assertSlowOperationsAreAllowed（EDT 禁）**。
 * 即：SQL 方言/文件类型高亮器解析族内部会做 VFS 目录下沉查找，而该查找本身是慢操作门控 API——
 * EDT 上凡直调 `getSyntaxHighlighter(` / `findFileByUrl(` / `findFileByPath(` 都是断言源。
 *
 * ## 规则
 *
 * 重渲染链宿主文件（[SCAN_FILES]：SqlPreviewPanel.kt＝参数框 listener→renderSql→showSql 链；
 * SqlAutoFolding.kt＝showSql 末尾折叠收口）**禁止直接出现**上述三个令牌——链上的 VFS 下沉
 * 必须留在既有 BGT 预热通道（prewarmPreviewTempFile 池线程，T-M36.6-03 定例），编辑器创建期
 * 的高亮器解析走平台 EditorFactory 内部（不经插件直调）。当前基线 0 命中；本守卫钉住该形态
 * 防回潮（新增重渲染支链时把慢操作 API 拷进面板即红）。
 *
 * 判定内核 [slowOpDirectCallHits] 暴露合成样本注入口，自证用例喂「每令牌必中 + 干净源零误报」
 * 双向语料（防空绿：真源 0 命中时判别力由自证语料独立证明）。
 */
class PreviewRenderSlowOpsGuardTest {

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    /**
     * 剥 block 注释与整行注释（保守：只处理 trim 后以双斜杠、星号、块注释起始符三种形态起头的行
     * ——KDoc/行注释形态；字符串字面量不剥，只可能过报不可能漏报）。
     * 注意：本 KDoc 不得出现块注释起始符的字面形态——Kotlin 块注释可嵌套，字面量会把注释钉成
     * 永不闭合（首轮编译红实证）。
     */
    private fun maskComments(source: String): String =
        source.lineSequence()
            .filter { line ->
                val t = line.trimStart()
                !(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*"))
            }
            .joinToString("\n")

    /**
     * 判定内核（元守卫登记面）：给定**剥注释**源码，返回慢操作 API 族命中清单（`token@行号`）。
     * 空清单 = 合规。合成样本经自证用例注入（见 `test自证_慢操作API族判定内核可判别`）。
     */
    internal fun slowOpDirectCallHits(maskedSource: String): List<String> {
        val banned = listOf("getSyntaxHighlighter(", "findFileByUrl(", "findFileByPath(")
        return maskedSource.lineSequence()
            .flatMapIndexed { idx, line ->
                banned.filter { line.contains(it) }.map { "$it@${idx + 1}" }
            }
            .toList()
    }

    private fun mainSource(rel: String): String =
        maskComments(repoRoot().resolve("src/main/kotlin/cn/song/mybatisplus/visualizer/$rel").readText())

    @Test
    fun test重渲染链文件禁直接调慢操作API族() {
        val offenders = SCAN_FILES.flatMap { rel ->
            slowOpDirectCallHits(mainSource(rel)).map { "$rel: $it" }
        }
        assertTrue(
            "预览重渲染链宿主文件出现慢操作门控 API 直调（命中 ${offenders.size} 处：$offenders）。" +
                "SQL 方言/高亮器解析与 VFS 目录下沉是 EDT 慢操作断言源（2026-09-24 沙盒实栈，见类 KDoc）——" +
                "VFS 查找放 BGT 预热通道（prewarmPreviewTempFile 池线程定例），高亮器解析交平台编辑器内部",
            offenders.isEmpty(),
        )
    }

    /** 自证语料：判定内核对每个禁令牌必报、对干净源零误报（判别力独立于真源 0 命中，防空绿）。 */
    @Test
    fun test自证_慢操作API族判定内核可判别() {
        // 三令牌逐一必中（合成样本，非真源）
        val offenderSources = listOf(
            "fun f() = SyntaxHighlighterFactory.getSyntaxHighlighter(fileType, project, file)",
            "val vf = VirtualFileManager.getInstance().findFileByUrl(url)",
            "val v = LocalFileSystem.getInstance().findFileByPath(path)",
        )
        offenderSources.forEach { sample ->
            val hits = slowOpDirectCallHits(sample)
            assertEquals("合成违规样本必须命中：$sample", 1, hits.size)
        }
        // 干净源（真链形态：字符串拼接与纯文本渲染）零误报
        val clean = """
            private fun renderSql() {
                val sql = backfillParamsBySource(base, sources, hints)
                showSql(sql)
            }
        """.trimIndent()
        assertEquals("干净源不得误报", emptyList<String>(), slowOpDirectCallHits(clean))
    }

    private companion object {
        /**
         * 重渲染链宿主文件（相对 src/main/kotlin/cn/song/mybatisplus/visualizer/）。
         *
         * T-M53-II D2-5：面板巨文件被拆成同包协作文件集——判定面必须跟着铺开，否则链上的文件
         * 一旦不是扫描目标，「把慢操作 API 拷进面板即红」这条守卫就在那些文件里静默失效
         * （扫描面漏站点＝假绿）。故按**面板家族全集 + 折叠收口**登记；当前各文件命中均为 0。
         */
        val SCAN_FILES = listOf(
            "ui/SqlPreviewPanel.kt",
            "ui/SqlPreviewPanelSqlView.kt",
            "ui/SqlPreviewPanelResultView.kt",
            "ui/SqlPreviewPanelParamPanel.kt",
            "ui/SqlPreviewPanelExecution.kt",
            // D202（2026-10-31）：EXPLAIN 双通道链随拆分搬入本文件，扫描面同族铺开
            "ui/SqlPreviewPanelExecutionExplain.kt",
            "ui/SqlPreviewPanelEditorLifecycle.kt",
            "ui/SqlPreviewPanelText.kt",
            "ui/highlight/SqlAutoFolding.kt",
        )
    }
}
