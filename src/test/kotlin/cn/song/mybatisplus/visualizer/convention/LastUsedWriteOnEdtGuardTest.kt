package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-LastUsedEdt 守卫（2026-11-01 真机实证缺陷）：项目级 `PropertiesComponent` 的 last-used
 * 写入必须在 EDT。
 *
 * 缺陷形态：`rememberRunner` 由 Database 插件的 runner session 回调链进入（`prepareAfterEditorResolved`
 * 是非 EDT 直行），导致后台线程写项目级持久化状态，与平台保存竞争后 demo 工程
 * `.idea/workspace.xml` 里 `mpsql.visualizer.last.runner.id` 的值丢了开头引号，每次启动报
 * `JsonDecodingException`——**且损坏后平台不重写该条目，用户工程文件被永久污染**。
 *
 * 本守卫锁「入口归位」这一形态：三个写入口函数体前必须有 `isDispatchThread` 判定 + `invokeLater`
 * 归位。只要有人把归位移除（或新写一个直接调 `PropertiesComponent.setValue` 的入口），即红。
 *
 * 为什么锁形态而非锁行为：headless 环境下 `isDispatchThread` 恒 true，行为测试无法暴露竞争；
 * 形态守卫是这类「线程纪律」问题唯一可机器化的判据。
 */
class LastUsedWriteOnEdtGuardTest {

    private val writeFns = listOf("rememberRunner", "forgetLastRunner", "forgetAllLastUsed")

    private fun source(): String = File(
        "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelector.kt",
    ).readText()

    /** 判定内核（VIOLATIONS）：返回缺 EDT 归位的写入口函数名。可注入合成源码自证。 */
    internal fun offendersOf(source: String): List<String> {
        val stripped = stripComments(source)
        return writeFns.filter { fn ->
            val idx = stripped.indexOf("internal fun $fn(")
            if (idx < 0) return@filter true   // 函数不见了也算异常（防被删）
            val seg = functionBodyAt(stripped, idx)
            // 两种合规形态都接受：
            //  a) 内联归位——函数体内直接 isDispatchThread + invokeLater；
            //  b) 委托归位——函数体内调 onEdtForLastUsed { ... }（归位知识收敛到 DataSourceSelectorLastUsedEvents
            //     的该辅助函数；另有「辅助函数本身必须在 EDT 判定」的用例钉住它不被掏空）。
            val inline = seg.contains("isDispatchThread") && seg.contains("invokeLater")
            val delegated = seg.contains("onEdtForLastUsed")
            !(inline || delegated)
        }
    }

    /**
     * 取 `idx` 处函数的**真实函数体**（大括号配对），而不是固定长度窗口。
     *
     * 踩过的坑（本轮反向验证抓出）：早先按「函数声明后 1200 字符」截窗口，`rememberRunner` 摘掉
     * 自己的归位后，窗口跨界读到了后面 `forgetLastRunner` / `forgetAllLastUsed` 的 `onEdtForLastUsed`
     * 调用 → 守卫**假绿**。按大括号配对切体后，窗口天然止于本函数。
     */
    private fun functionBodyAt(src: String, declIdx: Int): String {
        val open = src.indexOf('{', declIdx)
        if (open < 0) return ""
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        return src.substring(open)
    }

    @Test
    fun `三个 last-used 写入口必须在非 EDT 时归位`() {
        val offenders = offendersOf(source())
        assertTrue(
            "以下写入口缺 EDT 归位（会与平台保存竞争、写坏用户 .idea/workspace.xml）：$offenders\n" +
                "修法：函数体开头加 isDispatchThread 判定 + invokeLater 归位后 return（照 rememberRunner 的 T-LastUsedEdt 块）",
            offenders.isEmpty(),
        )
    }

    /**
     * 委托形态的兜底：三个入口现在都调 `onEdtForLastUsed { ... }`，归位知识在该辅助函数里。
     * 若有人把辅助函数掏空成直行（或删掉 EDT 判定），上面的 offendersOf 仍绿——故单钉一条。
     */
    @Test
    fun `委托归位的辅助函数本身必须在 EDT 判定`() {
        val helperFile = File(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorLastUsedEvents.kt",
        )
        assertTrue("onEdtForLastUsed 所在文件不存在：${helperFile.path}", helperFile.isFile)
        val src = stripComments(helperFile.readText())
        val idx = src.indexOf("fun onEdtForLastUsed(")
        assertTrue("onEdtForLastUsed 不见了（委托归位失去落点）", idx >= 0)
        val seg = src.substring(idx, minOf(idx + 600, src.length))
        assertTrue("onEdtForLastUsed 缺 isDispatchThread 判定：A/B 竞争会复现", seg.contains("isDispatchThread"))
        assertTrue("onEdtForLastUsed 缺 invokeLater 投递（非 EDT 时未归位）", seg.contains("invokeLater"))
    }

    /** 自证语料：合成一段「无归位」的写入口，判定内核必须点名；带归位的必须放行。 */
    @Test
    fun `自证_判定内核能看见缺归位形态且不误报合规形态`() {
        val bad = """
            internal fun rememberRunner(project: Project, name: String) {
                runCatching { PropertiesComponent.getInstance(project).setValue("k", name) }
            }
            internal fun forgetLastRunner(project: Project) {
                runCatching { PropertiesComponent.getInstance(project).unsetValue("k") }
            }
            internal fun forgetAllLastUsed(project: Project) {
                runCatching { PropertiesComponent.getInstance(project).unsetValue("k") }
            }
        """.trimIndent()
        assertTrue("缺归位的三个入口都应被点名", offendersOf(bad).size == 3)

        val good = writeFns.joinToString("\n") { fn ->
            """
            internal fun $fn(project: Project) {
                if (!app.isDispatchThread) { app.invokeLater({ $fn(project) }); return }
                runCatching { PropertiesComponent.getInstance(project).setValue("k", "v") }
            }
            """.trimIndent()
        }
        assertTrue("带归位的合规形态不应误报", offendersOf(good).isEmpty())
    }

    private fun stripComments(src: String): String {
        val noBlock = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(src) { m ->
            m.value.replace(Regex("[^\n]"), " ")
        }
        return noBlock.lineSequence().joinToString("\n") { line ->
            val i = line.indexOf("//")
            if (i >= 0) line.substring(0, i) else line
        }
    }
}
