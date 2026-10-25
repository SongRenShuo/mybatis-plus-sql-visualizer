package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BGT 同步 VFS 刷新不得持有写意向锁；EDT 冷路径的包装必须保留。 */
class SyncRefreshWriteIntentGuardTest : BannedApiScanSupport() {
    internal data class WilRefreshOffender(val path: String, val line: Int)

    internal fun syncRefreshInsideWiraOffenders(sources: List<Pair<String, String>>): List<WilRefreshOffender> {
        val out = ArrayList<WilRefreshOffender>()
        for ((path, source) in sources) {
            val masked = maskNonCode(source)
            // k2j 重载族适配：Kotlin 默认参数翻成 Java 后是「单参委托重载 + 多参实现重载」，
            // 白名单必须覆盖同名全部方法体——只认第一个声明会让实现重载里的合法冷路径 WIRA
            // 站点脱保（本轮规则三/反向/规则二三连红的根因，语料见自证_重载族方法体全部纳入白名单）。
            val allowed = if (path.replace('\\', '/').endsWith(WHITELIST_FILE)) {
                functionBodyRanges(masked, "createTempPsiFile")
            } else emptyList()
            var idx = masked.indexOf(WIRA_TOKEN)
            while (idx >= 0) {
                val open = wiraComputeBlockStart(masked, idx)
                if (open != null) {
                    val close = matchingDelimiter(masked, open, '(', ')')
                    if (close > open && masked.substring(open, close + 1).contains(SYNC_REFRESH_TOKEN)
                        && allowed.none { idx in it }) {
                        out.add(WilRefreshOffender(path, lineOf(masked, idx)))
                    }
                }
                idx = masked.indexOf(WIRA_TOKEN, idx + WIRA_TOKEN.length)
            }
        }
        return out
    }

    /** Java 显式类型参数在方法名前；括号区间同时覆盖块 lambda、表达式 lambda 和 Computable 强转。 */
    internal fun wiraComputeBlockStart(masked: String, tokenStart: Int): Int? {
        var i = tokenStart + WIRA_TOKEN.length
        while (i < masked.length && masked[i].isWhitespace()) i++
        if (i >= masked.length || masked[i] != '.') return null
        i++
        while (i < masked.length && masked[i].isWhitespace()) i++
        if (i < masked.length && masked[i] == '<') {
            val end = matchingDelimiter(masked, i, '<', '>')
            if (end < 0) return null
            i = end + 1
            while (i < masked.length && masked[i].isWhitespace()) i++
        }
        if (!masked.regionMatches(i, "compute", 0, 7)) return null
        i += 7
        while (i < masked.length && masked[i].isWhitespace()) i++
        return if (i < masked.length && masked[i] == '(') i else null
    }

    /**
     * 只接受带 Java 可见性修饰符的方法声明，不能让普通调用后的块扩大白名单范围。
     *
     * k2j 重载族适配：同名方法的**全部**声明各提一个方法体（Kotlin 单一定义 + 默认参数翻译
     * 成委托/实现重载后，真实链落在实现重载体内）。
     */
    internal fun functionBodyRanges(masked: String, functionName: String): List<IntRange> {
        val declaration = Regex(
            "(?m)^\\h*(?:public|protected|private)\\h+(?:(?:static|final|synchronized)\\h+)*" +
                "[\\w.$<>?,\\[\\] ]+\\h+" + Regex.escape(functionName) + "\\s*\\("
        )
        val out = ArrayList<IntRange>()
        for (match in declaration.findAll(masked)) {
            val paramsOpen = match.range.last
            val paramsClose = matchingDelimiter(masked, paramsOpen, '(', ')')
            if (paramsClose < 0) continue
            val bodyOpen = masked.indexOf('{', paramsClose + 1)
            if (bodyOpen < 0) continue
            val terminator = masked.indexOf(';', paramsClose + 1)
            if (terminator in (paramsClose + 1) until bodyOpen) continue
            val bodyClose = matchingDelimiter(masked, bodyOpen, '{', '}')
            if (bodyClose > bodyOpen) out.add(bodyOpen..bodyClose)
        }
        return out
    }

    /** 单一定义函数（无重载）的定位：恰好一个方法体；零个或多个都算守卫定位失败（null）。 */
    internal fun functionBodyRange(masked: String, functionName: String): IntRange? =
        functionBodyRanges(masked, functionName).singleOrNull()

    private fun productionSource(): Pair<String, String> =
        mainSources().single { it.first.replace('\\', '/').endsWith(WHITELIST_FILE) }

    @Test
    fun 规则一_BGT预热链不得再现WIRA包装() {
        val source = maskNonCode(productionSource().second)
        // T-SyncK2JMaster82：prewarmExecTempFileInner 现为「3 参委托 + 4 参实现」重载族
        // （Kotlin 默认参 → Java 重载惯例），改按重载族口径锚定含同步 refresh 的实现体。
        val impl = functionBodyRanges(source, "prewarmExecTempFileInner")
            .map { source.substring(it.first, it.last + 1) }
            .firstOrNull { SYNC_REFRESH_TOKEN in it }
        assertNotNull("Java 预热实现重载（含同步 refresh）必须被定位，不能空扫描", impl)
        assertTrue(WIRA_TOKEN !in impl!!)
        assertTrue(SYNC_REFRESH_TOKEN in impl)
    }

    @Test
    fun 规则二_EDT冷路径WIRA包装不得被清理() {
        val source = maskNonCode(productionSource().second)
        val bodies = functionBodyRanges(source, "createTempPsiFile")
        assertTrue("Java 冷路径必须被定位（重载族至少一个方法体），不能空扫描", bodies.isNotEmpty())
        // 重载族中必须有一个方法体（实现重载）同时保留 WIRA 包装与同步 refresh——
        // 262 平台 EDT + plain ReadAction 内同步 refresh 硬抛 write thread only
        //（T-ExecCopyFix F2b 修复本体，勿清理）；EDT 语境刷新会话在调用线程同步处理，无自锁。
        val coldPath = bodies.firstOrNull {
            val t = source.substring(it.first, it.last + 1)
            WIRA_TOKEN in t && SYNC_REFRESH_TOKEN in t
        }
        assertNotNull(
            "createTempPsiFile 重载族中没有任何方法体保留 WriteIntentReadAction + 同步 refresh——" +
                "F2b 修复本体被清理或形态变更，须重新评审与本守卫",
            coldPath,
        )
    }

    @Test
    fun 规则三_全仓同步refresh不得包WIRA() {
        val sources = mainSources()
        assertTrue("扫描面必须包含 Java 生产文件", sources.any { it.first.endsWith(".java") })
        val offenders = syncRefreshInsideWiraOffenders(sources)
        assertTrue("非 EDT 冷路径中发现持写意向锁的同步刷新：$offenders", offenders.isEmpty())
    }

    @Test
    fun 自证_Java泛型强转和表达式lambda均检出() {
        val calls = listOf(
            "WriteIntentReadAction.compute(() -> { return fs.refreshAndFindFileByIoFile(file); });",
            "WriteIntentReadAction.<VirtualFile>compute(() -> fs.refreshAndFindFileByIoFile(file));",
            "WriteIntentReadAction.compute((Computable<VirtualFile>) () -> fs.refreshAndFindFileByIoFile(file));"
        )
        calls.forEach { call ->
            assertEquals(call, 1, syncRefreshInsideWiraOffenders(listOf("fake/Bgt.java" to call)).size)
        }
    }

    @Test
    fun 自证_只豁免冷路径方法体() {
        val source = """
            public class Example {
                public static VirtualFile createTempPsiFile(Project project, String sql) {
                    return WriteIntentReadAction.compute(() -> fs.refreshAndFindFileByIoFile(file));
                }
                public static void prewarmExecTempFileInner(Project project, String sql) {
                    WriteIntentReadAction.compute(() -> fs.refreshAndFindFileByIoFile(file));
                }
            }
        """.trimIndent()
        val offenders = syncRefreshInsideWiraOffenders(listOf(WHITELIST_FILE to source))
        assertEquals(1, offenders.size)
        assertEquals(6, offenders.single().line)
        assertEquals(2, syncRefreshInsideWiraOffenders(listOf("fake/Other.java" to source)).size)
    }

    @Test
    fun 自证_重载族方法体全部纳入白名单() {
        // k2j 树 Java 形态：Kotlin 默认参数翻译成「委托重载 + 实现重载」，真实链（WIRA 冷路径）
        // 在实现重载体内——白名单只认第一个声明时该站点脱保，即本轮规则三报出违规的还原形态。
        val source = """
            public class Example {
                public static TempPsiFile createTempPsiFile(Project project, String sql) {
                    return createTempPsiFile(project, sql, null);
                }
                public static TempPsiFile createTempPsiFile(Project project, String sql, String name) {
                    return WriteIntentReadAction.compute(
                        (com.intellij.openapi.util.Computable<VirtualFile>) () ->
                            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file));
                }
            }
        """.trimIndent()
        assertEquals(
            "白名单路径：重载族全部方法体都须豁免（委托重载在前、实现重载在后是翻译常态）",
            0, syncRefreshInsideWiraOffenders(listOf(WHITELIST_FILE to source)).size,
        )
        assertEquals(
            "非白名单路径：重载族里的 WIRA×同步 refresh 站点照报",
            1, syncRefreshInsideWiraOffenders(listOf("fake/Overload.java" to source)).size,
        )
    }

    @Test
    fun 自证_裸调和注释不报() {
        val source = """
            import com.intellij.openapi.application.WriteIntentReadAction;
            public class Example {
                public void prewarm() {
                    // WriteIntentReadAction.compute(() -> fs.refreshAndFindFileByIoFile(file));
                    fs.refreshAndFindFileByIoFile(file);
                }
            }
        """.trimIndent()
        assertTrue(syncRefreshInsideWiraOffenders(listOf("fake/Bare.java" to source)).isEmpty())
    }

    @Test
    fun 反向_真实预热站点还原WIRA必须变红() {
        val (path, original) = productionSource()
        val maskedOriginal = maskNonCode(original)
        // T-SyncK2JMaster82：重载族口径——锚定含同步 refresh 的实现体重载
        val implRange = functionBodyRanges(maskedOriginal, "prewarmExecTempFileInner")
            .firstOrNull { SYNC_REFRESH_TOKEN in maskedOriginal.substring(it.first, it.last + 1) }
            ?: error("prewarmExecTempFileInner 实现重载定位失败——文件/函数改名后本守卫须同步跟进")
        val bodyText = original.substring(implRange.first, implRange.last + 1)
        val bare = "vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile);"
        assertTrue("反向探针必须命中真实修改点", bodyText.contains(bare))
        val broken = original.replaceRange(implRange, bodyText.replace(bare,
            "vf = WriteIntentReadAction.compute((com.intellij.openapi.util.Computable<VirtualFile>) () -> " +
                "LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile));"))
        assertTrue("原始形态必须全仓零违规", syncRefreshInsideWiraOffenders(listOf(path to original)).isEmpty())
        assertEquals("恢复真实旧形态后必须恰好报一个后台站点", 1,
            syncRefreshInsideWiraOffenders(listOf(path to broken)).size)
    }

    @Test
    fun 预热预算必须取消并清理在途状态() {
        val source = mainSources().single { it.first.replace('\\', '/').endsWith("execute/DataSourceSelector.java") }.second
        val masked = maskNonCode(source)
        val bodies = functionBodyRanges(masked, "prewarmExecTempFile")
        assertTrue("prewarmExecTempFile 重载族必须被定位（委托 + 实现两重载）", bodies.isNotEmpty())
        // 预算/取消/清理链在实现重载体内；按重载族并集文本断言
        val text = bodies.joinToString("\n") { masked.substring(it.first, it.last + 1) }
        assertTrue("预热必须以有界预算等待后台链", text.contains("prewarmFuture.get(EXEC_PREWARM_BUDGET_MS"))
        assertTrue("预算超时必须取消后台链", text.contains("prewarmFuture.cancel(true)"))
        assertTrue("等待中断必须复位中断标记", text.contains("Thread.currentThread().interrupt()"))
        assertTrue("内层 fatal 必须原对象上浮（不被 Future 包装吞掉）", text.contains("FatalErrors.isFatal(cause)"))
        assertTrue("在途标记必须在 finally 清理", text.contains("finally"))
        assertTrue("在途标记清理语句必须在场", text.contains("execPrewarmInFlight.set(false)"))
    }

    private companion object {
        private const val WIRA_TOKEN = "WriteIntentReadAction"
        private const val SYNC_REFRESH_TOKEN = "refreshAndFindFileBy"
        private const val WHITELIST_FILE = "execute/DataSourceSelectorResultsKt.java"
    }
}
