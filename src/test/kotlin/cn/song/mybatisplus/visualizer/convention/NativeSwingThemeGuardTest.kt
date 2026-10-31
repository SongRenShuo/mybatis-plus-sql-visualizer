package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 原生 Swing 容器跟随主题守卫（T-DarkTheme，2026-11-01）。
 *
 * ## 要解决的问题
 *
 * 用户报障：SQL 预览窗口出现白条。根因是**我们自己 new 的原生 Swing 容器**没有走平台组件，
 * 深色主题下露出一套与主题不一致的绘制。本仓已在同一根因上踩过多次：`InsightDetailPanel`
 * （`b99abd4a`）的原生 `JSplitPane`/`JScrollPane`、`SqlSuppressManagerDialog`（20260924 走查）
 * 的原生 `JSplitPane`、`SqlListPreviewHost` 的原生 `JSplitPane`。每次都是「单点改完就收工」，
 * 所以第四次、第五次照样出现——本守卫把「单点修复」升级为结构约束。
 *
 * ## 已核实的平台事实（2024.3.7.1 Darcula 主题，UIManager / 组件实例读数）
 *
 *  **原生 JScrollPane 与 JBScrollPane 的真实差异不是「滚动条不跟随主题」**——Darcula 主题注册了
 *  `ScrollBar.UI = com.intellij.ui.components.JBScrollBar` 与 `ScrollPane.border =
 *  DarculaScrollPaneBorder`，实测两者的滚动条 UI 都是 `DefaultScrollBarUI`、边框都是主题边框。
 *  真实差异是 `viewport` 与四角：
 *   - 原生 `JScrollPane`：viewport 是裸 `javax.swing.JViewport`，四角 Corner 组件为 `null`；
 *   - `JBScrollPane`：viewport 是 `JBViewport`（`getBackground()` 取所载 view 的底色）、
 *     四个角都装了平台 `Corner` 组件。
 *  即：原生滚动容器的边缘/角落**没有平台组件负责绘制**，由父背景透出。
 *
 *  **JSplitPane 才是硬证据**：Darcula 主题只给 `os.mac` 注册了 `SplitPane.UI`，Windows/Linux
 *  下 `SplitPaneUI` 仍是 `MetalSplitPaneUI`；主题只覆盖了 `SplitPane.highlight`，#3c3f41，
 *  而 `SplitPane.shadow`（Metal 值 `#B8CFE5`）、`SplitPane.darkShadow`（`#7A8A99`）和
 *  `SplitPane.dividerSize`（10）都还是 Metal 的浅色/厚分隔条——深色主题里这就是一条浅色带。
 *  `OnePixelSplitter` 的 1px 分隔条取主题分隔线色，不依赖这几个未覆盖的键。
 *
 *  **诚实边界**：上述结论来自静态读数与无头 LAF 探测；无头渲染在 Darcula 下**未能**复现
 *  全宽浅色带（浅色带是否可见还与父容器底色、实际内容高度有关）。因此本守卫约束的是
 *  「自建原生容器」这一**结构形态**，不承诺它能解释用户看到的每一处浅色。
 *
 * ## 判据
 *
 * 扫描 `src/main/kotlin/cn/song/mybatisplus/visualizer/{ui,log,config}` 下全部 `.kt`，**剥注释后**
 * 找原生容器构造点：
 *  - `JScrollPane(` —— 允许 `JBScrollPane(`（平台子类：JBViewport + 四角 Corner）；
 *  - `JSplitPane(` / `object : javax.swing.JSplitPane(` —— 分隔条走未覆盖的 Metal 键。
 *
 * **只认构造点**，不认类型判断/类型转换：`is JScrollPane` / `as? JSplitPane` 是对**平台给的**
 * 组件做操作（如 `execute/DataSourceSelector*` 对 IDE 内嵌控制台 SplitPane 的 cast），
 * 那些组件的 UI 由平台装——把它们也扫进来只会逼人加一堆假豁免。
 *
 * ## 为什么范围只有 ui/log/config
 *
 * `execute/` 下的 `DataSourceSelectorResults.buildConsoleSplitPane` 确实自建了原生 JSplitPane，
 * 但它**已经**覆写 `paint` 用 `UIUtil.getPanelBackground()` 重涂分隔条区域并设了
 * `background`/`dividerSize`——即满足「至少设 dividerSize/background 取主题色」的允许形态。
 * 它同时在等 IDE 内嵌控制台的 reparent 时序，换成 OnePixelSplitter 会动到一整套 divider
 * 修复机制（M36.5.x 系列）。故本守卫范围不含 execute/，该处**靠代价更高的平台替换收口**，
 * 不纳入静态禁止（否则只能给它留一条永久豁免，等于把缺口固化成受保护的特例）。
 *
 * ## 违反时的修法
 *
 *  - 自建滚动容器 → `com.intellij.ui.components.JBScrollPane`（滚动条 JBScrollBar + JBViewport）；
 *  - 自建分隔 → `com.intellij.ui.OnePixelSplitter`（1px 分隔条取主题色）；
 *  - 内容组件露白（不透明且用系统色）→ 设 `isOpaque = false` 或取
 *    `UIUtil.getPanelBackground()` / `JBColor` 主题色，**不要硬编码颜色**。
 *
 * 判据内核 [nativeContainerOffenders] 与自证用例同文件（元守卫要求语料与内核同文件）。
 */
class NativeSwingThemeGuardTest : BannedApiScanSupport() {

    /**
     * 原生容器构造点：`JScrollPane(` / `JSplitPane(`。
     *
     * `(?<![A-Za-z0-9_$])` 前置边界排除 `JBScrollPane(`（平台子类）与自定义 `MyScrollPane(`
     * 之类的后缀同名。全限定写法 `javax.swing.JScrollPane(` 的 `.` 不在排除集里，故同一条正则
     * 一并覆盖——不再单列全限定形态（单列会让同一处被报两次，噪音）。
     */
    private val nativeContainerPatterns = listOf(
        Regex("""(?<![A-Za-z0-9_$])JScrollPane\s*\("""),
        Regex("""(?<![A-Za-z0-9_$])JSplitPane\s*\("""),
    )

    /** 扫描面：UI 呈现三包（execute 见类头说明，不在范围内）。 */
    private val scannedPackages = listOf("ui", "log", "config")

    /** 违规点：`相对路径:行 片段`。 */
    private fun nativeContainerOffenders(sources: List<Pair<String, String>>): List<String> {
        val hits = mutableListOf<String>()
        for ((path, raw) in sources) {
            val code = stripComments(raw)
            for (pattern in nativeContainerPatterns) {
                for (m in pattern.findAll(code)) {
                    hits += "$path:${lineOf(code, m.range.first)}  ${m.value.trim()}"
                }
            }
        }
        return hits
    }

    /** 扫描面：三包下全部主源码（防空跑断言用 size 判据）。 */
    private fun scopedSources(): List<Pair<String, String>> =
        mainSources().filter { (path, _) ->
            val rel = path.replace('\\', '/').removePrefix("src/main/kotlin/cn/song/mybatisplus/visualizer/")
            scannedPackages.any { rel.startsWith("$it/") }
        }

    // ------------------------------------------------------------------
    // 主用例
    // ------------------------------------------------------------------

    @Test
    fun `ui与log与config三包不得自建原生滚动容器与分隔容器`() {
        val sources = scopedSources()
        // 防空跑：扫描面塌掉时本守卫毫无判别力，必须显式失败（同仓 HardcodedUiTextGuardTest 口径）。
        assertTrue("扫描面为空（ui/log/config 三包未取到源码），守卫未生效", sources.size > 100)
        val hits = nativeContainerOffenders(sources)
        assertTrue(
            "深色主题白条根因：自建原生 Swing 容器的滚动条/分隔条不跟随主题。命中 ${hits.size} 处：\n" +
                hits.joinToString("\n") { "  - $it" } +
                "\n修法：滚动容器换 com.intellij.ui.components.JBScrollPane；分隔换 " +
                "com.intellij.ui.OnePixelSplitter（或至少把 dividerSize/background 取主题色）。" +
                "内容组件露白改 isOpaque=false 或取 UIUtil.getPanelBackground()，禁硬编码颜色。",
            hits.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 自证语料（判定内核灵敏度；元守卫按函数名要求同文件）
    // ------------------------------------------------------------------

    /** 正向：三种真实形态（裸调用 / 全限定 / 匿名子类）都必须被抓。 */
    @Test
    fun `自证_自建原生容器必被抓`() {
        val synthetic = listOf(
            "ui/Probe1.kt" to """
                fun build(list: JList<String>) = JScrollPane(list).apply { border = null }
            """.trimIndent(),
            "log/Probe2.kt" to """
                fun build(a: Any, b: Any) = object : javax.swing.JSplitPane(VERTICAL_SPLIT, a, b) { }
            """.trimIndent(),
            "config/Probe3.kt" to """
                fun build(a: JComponent) = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, a, a)
            """.trimIndent(),
        )
        val hits = nativeContainerOffenders(synthetic)
        assertTrue("判定内核看不见裸 JScrollPane(", hits.any { it.contains("Probe1") })
        assertTrue("判定内核看不见全限定 javax.swing.JSplitPane(", hits.any { it.contains("Probe2") })
        assertTrue("判定内核看不见 JSplitPane(", hits.any { it.contains("Probe3") })
    }

    /** 反向：平台组件、类型判断、注释里的写法都不得命中（防误报把合法代码逼上豁免）。 */
    @Test
    fun `自证_平台组件与类型判断与注释不得误报`() {
        val synthetic = listOf(
            "ui/Probe4.kt" to """
                // 注释里写 JScrollPane(list) 不该命中
                fun build(list: JList<String>) = JBScrollPane(list)
                fun probe(c: JComponent): Boolean = c is JScrollPane || c is JSplitPane
                fun cast(c: JComponent) = (c as? JSplitPane)?.dividerLocation
            """.trimIndent(),
        )
        val hits = nativeContainerOffenders(synthetic)
        assertTrue("误报了合法形态（JBScrollPane / is / as? / 注释）：\n${hits.joinToString("\n")}", hits.isEmpty())
    }

    /**
     * 扫描面保鲜：三包取到的源码必须都是 .kt 且覆盖到已知存在的 ui 主面板，
     * 防「过滤条件写错 → 空集 → 全绿」。
     */
    @Test
    fun `扫描面保鲜_三包源码非空且覆盖已知面板`() {
        val sources = scopedSources()
        val paths = sources.map { it.first.replace('\\', '/') }
        assertTrue(
            "扫描面里没有 SqlPreviewPanel.kt——前缀过滤写错会让整条守卫空绿",
            paths.any { it.endsWith("ui/SqlPreviewPanel.kt") },
        )
        assertTrue(
            "扫描面里没有 log/SqlLogPanel.kt",
            paths.any { it.endsWith("log/SqlLogPanel.kt") },
        )
        assertTrue(
            "扫描面里没有 config/PluginSettingsPanel.kt",
            paths.any { it.endsWith("config/PluginSettingsPanel.kt") },
        )
    }

}
