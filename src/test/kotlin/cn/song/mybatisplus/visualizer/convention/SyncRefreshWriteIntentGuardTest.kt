package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同步 VFS refresh × WriteIntentReadAction 死锁守卫（T-PrewarmWilDeadlock，2026-10-15）。
 *
 * ## 要防的形态
 *
 * **后台线程**上以 `WriteIntentReadAction.compute { LocalFileSystem.refreshAndFindFileBy*(…) }`
 * 同步刷新 VFS：调用线程持写意向锁阻塞在 `RefreshSessionImpl.waitFor`，而刷新会话推进需要
 * WIL 空闲/EDT——持锁者等会话、会话等持锁者，互等成环。2026.2 真机实证
 * （threadDump-20261015-181620）：`prewarmExecTempFileInner` 正是这一形态，池线程永驻 + EDT
 * （预览编辑器 mousePressed 排队等 WIL）全 IDE「not responding」。
 *
 * ## 规则
 *
 * - 规则一：`prewarmExecTempFileInner`（BGT 预热链本体）函数体内不得再现 `WriteIntentReadAction`；
 * - 规则二：`createTempPsiFile`（exec 临时文件慢段本体）必须保留 `WriteIntentReadAction` +
 *   `refreshAndFindFileByIoFile`——WIRA 分支是**防御性 EDT 分支**（T-ExecCopyFix F2b 修「262 平台
 *   EDT + plain ReadAction 内同步 refresh 硬抛 write thread only」的修复本体，EDT 语境无自锁）；
 *   T-ExecPlanEdtSlowOp 后生产形态恒走 BGT 裸调分支（`createTempPsiFileAsync` 派发），
 *   两条分支的线程要求相反，故 WIRA 只能留在 `isDispatchThread` 判定内，勿顺手清理；
 * - 规则三（全量扫描面）：全仓 main 源码中 WIRA `.compute` 块内含同步 refresh 的站点，
 *   白名单仅 ExecTempFileFactory.kt 的 `createTempPsiFile` 函数体（上述防御分支）。
 *
 * 已知漏报边界（只漏报不误报）：`WriteIntentReadAction.run { }` 等非 `.compute` 形态不进扫描面
 * （仓内当前 0 处该形态包 refresh；`.compute` 是 Computable 重载的唯一入口，T-Wia262 定例）。
 */
class SyncRefreshWriteIntentGuardTest : BannedApiScanSupport() {

    // ------------------------------------------------------------------
    // 判定内核（元守卫登记：syncRefreshInsideWiraOffenders）
    // ------------------------------------------------------------------

    internal data class WilRefreshOffender(val path: String, val line: Int)

    /**
     * 全量扫描：WIRA `.compute` 块内含同步 refresh 的站点（涂白注释/字符串后判定）。
     * 白名单仅 [WHITELIST_FILE] 的 [WHITELIST_FUNCTION]（EDT 冷路径）函数体。
     */
    internal fun syncRefreshInsideWiraOffenders(sources: List<Pair<String, String>>): List<WilRefreshOffender> {
        val out = ArrayList<WilRefreshOffender>()
        for ((path, source) in sources) {
            val masked = maskNonCode(source)
            val whitelistRange: IntRange? = if (path.endsWith(WHITELIST_FILE)) {
                functionBodyRange(masked, WHITELIST_FUNCTION)
            } else {
                null
            }
            var idx = masked.indexOf(WIRA_TOKEN)
            while (idx >= 0) {
                val open = wiraComputeBlockStart(masked, idx)
                if (open != null) {
                    val close = matchingDelimiter(masked, open, '{', '}')
                    val block = if (close > open) masked.substring(open, close + 1) else ""
                    if (block.contains(SYNC_REFRESH_TOKEN) && (whitelistRange == null || idx !in whitelistRange)) {
                        out.add(WilRefreshOffender(path, lineOf(masked, idx)))
                    }
                }
                idx = masked.indexOf(WIRA_TOKEN, idx + WIRA_TOKEN.length)
            }
        }
        return out
    }

    /**
     * WIRA 令牌后是否直接接 `.compute`（容忍泛型实参与空白），返回 lambda `{` 下标；否则 null。
     * `.compute` 紧邻要求同时排掉 import 行误配（其后最近的 `{` 可能是无关类体）。
     */
    internal fun wiraComputeBlockStart(masked: String, tokenStart: Int): Int? {
        var i = tokenStart + WIRA_TOKEN.length
        while (i < masked.length && masked[i].isWhitespace()) i++
        if (!masked.regionMatches(i, ".compute", 0, ".compute".length)) return null
        i += ".compute".length
        while (i < masked.length && masked[i].isWhitespace()) i++
        if (i < masked.length && masked[i] == '<') {
            // 泛型实参（可嵌套 <>）：跳到配对 '>'
            var depth = 0
            while (i < masked.length) {
                val c = masked[i]
                i++
                when (c) {
                    '<' -> depth++
                    '>' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
            }
            while (i < masked.length && masked[i].isWhitespace()) i++
        }
        return if (i < masked.length && masked[i] == '{') i else null
    }

    /**
     * 在涂白文本上按函数名定位函数体 `{…}` 下标区间：名字出现点向左回走标识符/接收者链，
     * 仅当紧邻（隔空白）是 `fun` 关键字才认定声明——普通调用点（`val t = foo(`、`x.foo(`）不命中。
     * 找不到返回 null。
     */
    internal fun functionBodyRange(masked: String, functionName: String): IntRange? {
        val marker = "$functionName("
        var at = masked.indexOf(marker)
        while (at > 0) {
            var back = at
            while (back > 0 && (masked[back - 1].isLetterOrDigit() || masked[back - 1] == '.' || masked[back - 1] == '_')) {
                back--
            }
            var s = back
            while (s > 0 && masked[s - 1] == ' ') s--
            val isDeclaration = s >= 3 &&
                masked.regionMatches(s - 3, "fun", 0, 3) &&
                (s - 4 < 0 || !(masked[s - 4].isLetterOrDigit() || masked[s - 4] == '_'))
            if (isDeclaration) {
                val open = masked.indexOf('{', at)
                if (open < 0) return null
                val close = matchingDelimiter(masked, open, '{', '}')
                if (close < 0) return null
                return open..close
            }
            at = masked.indexOf(marker, at + marker.length)
        }
        return null
    }

    // ------------------------------------------------------------------
    // 生产站点规则
    // ------------------------------------------------------------------

    @Test
    fun 规则一_BGT预热链不得再现WIRA包装() {
        val (_, source) = mainSources().first { it.first.endsWith(EXEC_PREWARM_FILE) }
        val masked = maskNonCode(source)
        val body = functionBodyRange(masked, PREWARM_FUNCTION)
        assertTrue(
            "$EXEC_PREWARM_FILE 的 $PREWARM_FUNCTION 函数体定位失败——文件/函数改名后本守卫须同步跟进",
            body != null,
        )
        val bodyText = masked.substring(body!!.first, body.last + 1)
        assertTrue(
            "$PREWARM_FUNCTION（BGT 预热链）函数体出现 WriteIntentReadAction——同步 VFS refresh 包 WIRA " +
                "在后台线程即「持 WIL 等需要 WIL/EDT 的刷新会话」自锁（T-PrewarmWilDeadlock，" +
                "threadDump-20261015-181620 全 IDE 冻结实证），refresh 段必须裸调",
            WIRA_TOKEN !in bodyText,
        )
    }

    @Test
    fun 规则二_EDT冷路径WIRA包装不得被清理() {
        val (_, source) = mainSources().first { it.first.endsWith(EXEC_PREWARM_FILE) }
        val masked = maskNonCode(source)
        val body = functionBodyRange(masked, WHITELIST_FUNCTION)
        assertTrue(
            "$EXEC_PREWARM_FILE 的 $WHITELIST_FUNCTION 函数体定位失败——文件/函数改名后本守卫须同步跟进",
            body != null,
        )
        val bodyText = masked.substring(body!!.first, body.last + 1)
        assertTrue(
            "$WHITELIST_FUNCTION（慢段本体）丢失 WriteIntentReadAction 包装——EDT 防御分支被清理后，" +
                "262 平台 EDT + plain ReadAction 内同步 refresh 硬抛 write thread only（T-ExecCopyFix F2b " +
                "修复本体，勿清理）；生产形态走 BGT 裸调分支，两条分支线程要求相反",
            WIRA_TOKEN in bodyText,
        )
        assertTrue(
            "$WHITELIST_FUNCTION 不再含同步 refresh——refresh 方式变更后须重新评审 F2b 语境与本守卫",
            SYNC_REFRESH_TOKEN in bodyText,
        )
        assertTrue(
            "$WHITELIST_FUNCTION 的 WIRA 必须收在 isDispatchThread 判定内——T-ExecPlanEdtSlowOp 后生产" +
                "形态在 BGT（裸调），无判定的 WIRA 会在池线程自锁（T-PrewarmWilDeadlock）",
            bodyText.contains("isDispatchThread"),
        )
    }

    @Test
    fun 规则三_全仓同步refresh不得包WIRA() {
        val offenders = syncRefreshInsideWiraOffenders(mainSources())
        assertTrue(
            "同步 VFS refresh 不得包在 WriteIntentReadAction 内于非 EDT 冷路径站点执行" +
                "（BGT 持 WIL 等会话推进 = WIL/EDT 自锁，T-PrewarmWilDeadlock）。" +
                "白名单仅 $WHITELIST_FILE 的 $WHITELIST_FUNCTION。违规 ${offenders.size} 处：$offenders",
            offenders.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 判定内核自证（合成样本直接喂判定函数）
    // ------------------------------------------------------------------

    @Test
    fun 自证_合成BGT包装站点必被抓() {
        val synthetic = listOf(
            "fake/BgtChain.kt" to """
                class Fake {
                    fun prewarmInner() {
                        val vf = WriteIntentReadAction.compute<VirtualFile?> {
                            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
                        }
                    }
                }
            """.trimIndent(),
        )
        val offenders = syncRefreshInsideWiraOffenders(synthetic)
        assertEquals("合成 BGT WIRA×同步 refresh 站点必须被抓（守卫灵敏度自证）", 1, offenders.size)
        assertEquals("fake/BgtChain.kt", offenders[0].path)
    }

    @Test
    fun 自证_白名单函数体内不报() {
        val synthetic = listOf(
            WHITELIST_FILE to """
                internal fun DataSourceSelector.createTempPsiFile(project: Project): Pair<VirtualFile, PsiFile>? {
                    val vf = WriteIntentReadAction.compute<VirtualFile?> {
                        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
                    }
                    return null
                }
            """.trimIndent(),
        )
        assertEquals(
            "白名单（EDT 冷路径 createTempPsiFile）内的 WIRA×refresh 不应报违规",
            0,
            syncRefreshInsideWiraOffenders(synthetic).size,
        )
    }

    @Test
    fun 自证_裸调refresh不报() {
        val synthetic = listOf(
            "fake/Bare.kt" to """
                class Fake {
                    fun prewarmInner() {
                        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
                    }
                }
            """.trimIndent(),
        )
        assertEquals("BGT 裸调（合规形态）不应报违规", 0, syncRefreshInsideWiraOffenders(synthetic).size)
    }

    @Test
    fun 自证_规则一灵敏度_预热链合成WIRA必检出() {
        val synthetic = """
            internal fun DataSourceSelector.prewarmExecTempFileInner(project: Project) {
                val vf = WriteIntentReadAction.compute<VirtualFile?> {
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
                }
            }
        """.trimIndent()
        val masked = maskNonCode(synthetic)
        val body = functionBodyRange(masked, PREWARM_FUNCTION)
        assertTrue("自证样本的 $PREWARM_FUNCTION 函数体定位失败", body != null)
        assertTrue(
            "规则一灵敏度自证：预热链函数体内的 WIRA 必须被检出",
            WIRA_TOKEN in masked.substring(body!!.first, body.last + 1),
        )
    }

    private companion object {
        private const val WIRA_TOKEN = "WriteIntentReadAction"
        private const val SYNC_REFRESH_TOKEN = "refreshAndFindFileBy"
        // T-ExecPlanEdtSlowOp：exec 临时文件工厂（含 BGT 预热链与慢段本体）已从
        // DataSourceSelectorResults.kt 拆到独立文件，两条规则的白名单/扫描面同步迁移。
        private const val EXEC_PREWARM_FILE = "execute/ExecTempFileFactory.kt"
        private const val PREWARM_FUNCTION = "prewarmExecTempFileInner"
        private const val WHITELIST_FILE = "execute/ExecTempFileFactory.kt"
        private const val WHITELIST_FUNCTION = "createTempPsiFile"
    }
}
