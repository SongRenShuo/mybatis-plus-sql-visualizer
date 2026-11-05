package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

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

    private fun entry(
        sourceFile: String? = null,
        sourceLine: Int = 0,
        candidates: List<Pair<String, Int>> = emptyList(),
    ) = LogSqlEntry(
        rawSql = "SELECT 1",
        restoredSql = "SELECT 1",
        params = emptyList(),
        sourceFile = sourceFile,
        sourceLine = sourceLine,
        sourceCandidates = candidates,
    )

    fun test主位置缺失且无候选_MissingSource() {
        val t = EntryJumpResolver.resolve(project = project, entry = entry())
        assertTrue(t is EntryJumpResolver.JumpTarget.MissingSource)
    }

    fun testMP分页计数标记_NoSource_优先于工程解析() {
        val t = EntryJumpResolver.resolve(
            project = project,
            entry = entry(sourceFile = SourceMarkers.MP_PAGINATION_COUNT, sourceLine = 1),
        )
        assertTrue(t is EntryJumpResolver.JumpTarget.NoSource)
    }

    fun test主位置不可解析且候选不可解析_NotFound带候选明细() {
        val t = EntryJumpResolver.resolve(
            project = project,
            entry = entry(
                sourceFile = "com/corp/engine/DataUpdateTool.java",
                sourceLine = 88,
                candidates = listOf("com/corp/engine/Facade.java" to 20, "com/corp/engine/Repo.java" to 40),
            ),
        )
        assertTrue(t is EntryJumpResolver.JumpTarget.NotFound)
        val nf = t as EntryJumpResolver.JumpTarget.NotFound
        assertEquals("com/corp/engine/DataUpdateTool.java", nf.sourceFile)
        assertEquals(2, nf.candidates.size)
    }

    fun test候选含MP分页标记_跳过不误选() {
        val t = EntryJumpResolver.resolve(
            entry = entry(
                sourceFile = null,
                candidates = listOf(SourceMarkers.MP_PAGINATION_COUNT to 1),
            ),
            project = project,
        )
        assertTrue(t is EntryJumpResolver.JumpTarget.MissingSource)
    }

    fun test栈快照仅透传不参与判定_历史读回payload往返() {
        // Codec 往返：候选/类名/栈快照落盘后读回，跳转解析消费同一数据
        val origin = LogSqlEntry(
            rawSql = "SELECT * FROM t WHERE id = ?",
            restoredSql = "SELECT * FROM t WHERE id = 1",
            params = emptyList(),
            sourceFile = "com/corp/engine/DataUpdateTool.java",
            sourceCandidates = listOf("com/app/XxxService.java" to 36, "com/app/XxxController.java" to 48),
            candidateClasses = listOf("com.app.XxxService", "com.app.XxxController"),
            stackDigest = "com.app.XxxService#getXxxId:36<-com.app.XxxController#load:48",
        )
        val payload = SqlLogParamCodec.encodeEntry(origin)
        val decoded = SqlLogParamCodec.decode(payload)
            ?: throw AssertionError("decode 失败")
        assertEquals(origin.sourceCandidates, decoded.sourceCandidates)
        assertEquals(origin.candidateClasses, decoded.candidateClasses)
        assertEquals(origin.stackDigest, decoded.stackDigest)
    }

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
