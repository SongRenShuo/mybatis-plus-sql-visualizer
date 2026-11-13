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
            // 取函数体前 1200 字符（足以覆盖注释 + 归位块 + runCatching 开头）
            val seg = stripped.substring(idx, minOf(idx + 1200, stripped.length))
            !(seg.contains("isDispatchThread") && seg.contains("invokeLater"))
        }
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
