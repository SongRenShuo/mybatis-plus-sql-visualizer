package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S2-12：执行链「绕开绑定链取源」回落。
 *
 * 判定面：
 *  - 回落目标源纯函数（学习绑定 > last-used 记忆（UUID 精确 → 名字唯一）> 单源；歧义/零候选
 *    → null 不猜——用户 2026-11-01 拍板口径）；
 *  - 生产接线守卫（源码扫描）：回落调用点**唯一**且落在 `runners.isEmpty()` 分支内（重映射
 *    重试之后、归因文案之前）；回落实现**不写平台绑定状态**（无 attachConsole / per-file 映射）；
 *  - 守卫自证语料：合成源码喂进同一判定形状（摘接线 / 挪位置 / 重复触发都必须判红）。
 *
 * 纯逻辑层无平台依赖，可进 testFast 快桶。
 */
class ExecBindFallbackTest {

    // ------------------------------------------------------------------
    // 桩与工具
    // ------------------------------------------------------------------

    private class Src(val id: String, val name: String) {
        override fun toString(): String = "$id/$name"
    }

    private val byId: (String) -> Any? = { id -> stubs.firstOrNull { it.id == id } }
    private val byName: (Any) -> String = { (it as Src).name }
    private val stubs = listOf(
        Src("uuid-a", "alpha@localhost"),
        Src("uuid-b", "beta@localhost"),
        Src("uuid-c", "alpha-copy@localhost"),
    )

    private fun pick(
        learned: String? = null,
        memory: ExecDialectRouting.MemoryRef? = null,
        sources: List<Any> = stubs,
    ): Any? = ExecBindFallback.pickFallbackTargetDataSource(
        learnedBindingId = learned,
        lastUsedMemory = memory,
        registeredDataSources = sources,
        dataSourceById = byId,
        dataSourceName = byName,
    )

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return dir
        }
        return dir
    }

    private fun mainSource(relativePath: String): String =
        File(repoRoot(), "src/main/kotlin/$relativePath").readText()

    // ------------------------------------------------------------------
    // 回落目标源纯函数（S2-12 定案口径）
    // ------------------------------------------------------------------

    @Test
    fun `学习绑定UUID命中即用`() {
        assertEquals("uuid-b", (pick(learned = "uuid-b") as Src).id)
    }

    @Test
    fun `学习绑定优先于last-used记忆`() {
        val memory = ExecDialectRouting.MemoryRef("uuid-c", "alpha-copy@localhost")
        assertEquals("uuid-b", (pick(learned = "uuid-b", memory = memory) as Src).id)
    }

    @Test
    fun `学习绑定指向已删除源时落到last-used`() {
        val memory = ExecDialectRouting.MemoryRef("uuid-c", "alpha-copy@localhost")
        assertEquals("uuid-c", (pick(learned = "uuid-gone", memory = memory) as Src).id)
    }

    @Test
    fun `last-used记忆UUID精确命中`() {
        val memory = ExecDialectRouting.MemoryRef("uuid-b", null)
        assertEquals("uuid-b", (pick(memory = memory) as Src).id)
    }

    @Test
    fun `last-used记忆UUID失效时按名字唯一命中`() {
        val memory = ExecDialectRouting.MemoryRef("uuid-gone", "beta@localhost")
        assertEquals("uuid-b", (pick(memory = memory) as Src).id)
    }

    @Test
    fun `last-used记忆名字歧义不猜`() {
        // alpha@localhost 在桩里唯一，歧义须靠重复名构造：两条同名 → 名字档不命中 → 多源不猜
        val duplicated = stubs + Src("uuid-d", "alpha@localhost")
        assertNull(pick(memory = ExecDialectRouting.MemoryRef(null, "alpha@localhost"), sources = duplicated))
    }

    @Test
    fun `多源无任何记忆不猜`() {
        assertNull(pick())
    }

    @Test
    fun `零源不猜`() {
        assertNull(pick(sources = emptyList()))
    }

    @Test
    fun `单源兜底`() {
        val single = listOf(Src("uuid-b", "beta@localhost"))
        assertEquals("uuid-b", (pick(sources = single) as Src).id)
    }

    @Test
    fun `单源兜底不越过学习绑定歧义`() {
        // 学习绑定指向已删除源、又在册多个源：单源档不适用（registered.size != 1）→ null
        assertNull(pick(learned = "uuid-gone", sources = stubs))
    }

    // ------------------------------------------------------------------
    // 生产接线守卫（源码扫描；摘接线即红——反向验证锚点）+ 自证语料
    // ------------------------------------------------------------------

    /**
     * 接线形状判定：回落调用点唯一、且在 `runners.isEmpty()` 分支内（空分支 → pickTarget →
     * directRunner → 归因文案）。自证语料与真实源码共用同一实现。
     */
    private fun fallbackWiringShape(results: String): Boolean {
        val emptyBranch = results.indexOf("runners.isEmpty() ->")
        val pick = results.indexOf("ExecBindFallback.pickTarget(")
        val direct = results.indexOf("ExecBindFallback.directRunner(")
        val hint = results.indexOf("EmptyRunnersHint.message(project, registered")
        if (emptyBranch < 0 || pick < 0 || direct < 0 || hint < 0) return false
        if (results.indexOf("ExecBindFallback.pickTarget(", pick + 1) >= 0) return false
        if (results.indexOf("ExecBindFallback.directRunner(", direct + 1) >= 0) return false
        return emptyBranch < pick && pick < direct && direct < hint
    }

    @Test
    fun `接线守卫_S2-12回落唯一且只在空Runner分支触发`() {
        val results = mainSource("cn/song/mybatisplus/visualizer/execute/DataSourceSelectorResults.kt")
        assertTrue(
            "S2-12 回落接线缺失或挪位（须在 runners.isEmpty() 分支内、重映射之后、归因文案之前，且触发点唯一）——" +
                "摘掉本接线即红（反向验证锚点）",
            fallbackWiringShape(results),
        )
        assertTrue(
            "回落须把执行交给既有 runRunner 全链（不新建执行通道）",
            Regex("""runRunner\(\s*project,\s*ExecBindFallback\.directRunner""").containsMatchIn(results),
        )
    }

    @Test
    fun `接线守卫_S2-12回落不写平台绑定状态`() {
        val raw = mainSource("cn/song/mybatisplus/visualizer/execute/ExecBindFallback.kt")
        // 剥注释再扫：KDoc 里**提**禁词不算违规，代码里**调**才算（防文档措辞误伤守卫）
        val code = raw
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\r\n]*"), "")
        val banned = listOf("attachConsole", "DataSourcePerFileMappings", "setMapping", "remember(")
        val offenders = banned.filter { code.contains(it) }
        assertTrue(
            "ExecBindFallback 不得触碰平台绑定状态/绑定存储（用户定案：不自动 attachConsole、不写 per-file 映射），命中=$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `自证_摘掉接线判定变红`() {
        val results = mainSource("cn/song/mybatisplus/visualizer/execute/DataSourceSelectorResults.kt")
        // T-ExecPlanEdtSlowOp：回落块随 chooseAndPrepare 后半链搬进 continuePrepareAfterTempFile，
        // 标签返回 `return@chooseAndPrepare` 改为普通 `return`——锚点同步（摘掉的仍是同一段接线）。
        val stripped = results.replace(
            Regex("""// S2-12：绑定回落[\s\S]*?return\r?\n\s*\}"""),
            "",
        )
        assertFalse("摘掉回落块后判定必须变红（守卫灵敏度）", fallbackWiringShape(stripped))
    }

    @Test
    fun `自证_挪到空分支之前判定变红`() {
        val synthetic = """
            fun x() {
                ExecBindFallback.pickTarget(p)
                when {
                    runners.isEmpty() -> {
                        EmptyRunnersHint.message(project, registered, d, vf)
                    }
                }
            }
        """.trimIndent()
        assertFalse("回落挪到空 Runner 分支之前（每次执行都回落）必须判红", fallbackWiringShape(synthetic))
    }

    @Test
    fun `自证_重复触发点判定变红`() {
        val synthetic = """
            fun x() {
                when {
                    runners.isEmpty() -> {
                        val a = ExecBindFallback.pickTarget(p)
                        val b = ExecBindFallback.pickTarget(p)
                        EmptyRunnersHint.message(project, registered, d, vf)
                    }
                }
            }
        """.trimIndent()
        assertFalse("回落触发点必须唯一（重复 = 多条旁路）", fallbackWiringShape(synthetic))
    }

    @Test
    fun `自证_真实接线形状判绿`() {
        // 正对照：判定实现看见真实形态时必须判绿（防「只会红」的反向假守卫）
        assertTrue(fallbackWiringShape(mainSource("cn/song/mybatisplus/visualizer/execute/DataSourceSelectorResults.kt")))
    }
}
