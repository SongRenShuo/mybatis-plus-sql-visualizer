package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-SourceJumpFunnel 纯逻辑测试：跳转目标统一解析（[EntryJumpResolver]）+ 历史持久化往返。
 *
 * 场景来自用户真实故障形态：自研框架汇聚门面（DataUpdateTool.findByFilter）被选为主位置，
 * 候选含真实业务帧——主位置解析失败时须按栈序落首个工程内候选；同时锁定与实时/历史两面板
 * 的行为一致性（两面板共用本解析器）。
 *
 * 工程内命中场景依赖平台 VirtualFile，由 UI 测试覆盖（SqlLogPanelLiveJumpWithSourceTest
 * 既有 BasePlatformTestCase）；本文件覆盖不触 VFS 的路径（NoSource/MissingSource/
 * NotFound 候选全落空/MP 分页标记）与 Codec 往返，Project 用既有空壳桩形态。
 */
class EntryJumpResolverTest : BasePlatformTestCase() {

    /**
     * 空壳条目（Java record 全参构造：master 侧 data class 具名默认参数的位置实参等价形态，
     * 缺省值与三参便捷构造一致——summary 取 summarizeSql(restoredSql)、source=UNKNOWN）。
     */
    private fun entry(
        sourceFile: String? = null,
        sourceLine: Int = 0,
        candidates: List<Pair<String, Int>> = emptyList(),
    ) = LogSqlEntry(
        "SELECT 1", "SELECT 1", emptyList(), System.currentTimeMillis(), null,
        "SELECT 1", SqlSource.UNKNOWN, null, sourceFile, sourceLine, candidates, emptyList(), null,
        0L, null, null, null, null, null,
    )

    @Test
    fun test主位置缺失且无候选_MissingSource() {
        val t = EntryJumpResolver.resolve(project, entry())
        assertTrue(t is EntryJumpResolver.JumpTarget.MissingSource)
    }

    @Test
    fun testMP分页计数标记_NoSource_优先于工程解析() {
        val t = EntryJumpResolver.resolve(
            project,
            entry(SourceMarkers.MP_PAGINATION_COUNT, 1),
        )
        assertTrue(t is EntryJumpResolver.JumpTarget.NoSource)
    }

    @Test
    fun test主位置不可解析且候选不可解析_NotFound带候选明细() {
        val t = EntryJumpResolver.resolve(
            project,
            entry(
                "com/corp/engine/DataUpdateTool.java",
                88,
                listOf("com/corp/engine/Facade.java" to 20, "com/corp/engine/Repo.java" to 40),
            ),
        )
        assertTrue(t is EntryJumpResolver.JumpTarget.NotFound)
        val nf = t as EntryJumpResolver.JumpTarget.NotFound
        assertEquals("com/corp/engine/DataUpdateTool.java", nf.sourceFile)
        assertEquals(2, nf.candidates.size)
    }

    @Test
    fun test候选含MP分页标记_跳过不误选() {
        val t = EntryJumpResolver.resolve(
            project,
            entry(
                null,
                0,
                listOf(SourceMarkers.MP_PAGINATION_COUNT to 1),
            ),
        )
        assertTrue(t is EntryJumpResolver.JumpTarget.MissingSource)
    }

    @Test
    fun test栈快照仅透传不参与判定_历史读回payload往返() {
        // Codec 往返：候选/类名/栈快照落盘后读回，跳转解析消费同一数据
        val origin = LogSqlEntry(
            "SELECT * FROM t WHERE id = ?", "SELECT * FROM t WHERE id = 1", emptyList(),
            System.currentTimeMillis(), null,
            "SELECT * FROM t WHERE id = 1", SqlSource.UNKNOWN, null,
            "com/corp/engine/DataUpdateTool.java", 0,
            listOf("com/app/XxxService.java" to 36, "com/app/XxxController.java" to 48),
            listOf("com.app.XxxService", "com.app.XxxController"),
            "com.app.XxxService#getXxxId:36<-com.app.XxxController#load:48",
            0L, null, null, null, null, null,
        )
        val payload = SqlLogParamCodec.encodeEntry(origin)
        val decoded = SqlLogParamCodec.decode(payload)
            ?: throw AssertionError("decode 失败")
        assertEquals(origin.sourceCandidates, decoded.sourceCandidates)
        assertEquals(origin.candidateClasses, decoded.candidateClasses)
        assertEquals(origin.stackDigest, decoded.stackDigest)
    }

    @Test
    fun test旧版payload无候选键_解码缺省空() {
        val json = """
            {"r":"SELECT 1","p":[["x","String"]]}
        """.trimIndent()
        val payload = java.util.Base64.getEncoder()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
        val decoded = SqlLogParamCodec.decode(payload) ?: throw AssertionError("decode 失败")
        assertTrue(decoded.sourceCandidates.isEmpty())
        assertTrue(decoded.candidateClasses.isEmpty())
        assertNull(decoded.stackDigest)
    }
}
