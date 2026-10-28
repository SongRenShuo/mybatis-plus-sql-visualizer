package cn.song.mybatisplus.visualizer.i18n

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties

/**
 * 降级横幅文案守卫（T-BannerTruth，2026-09-20）：`panel.banner.*` **不得把「等 IDE 索引完成」
 * 写成恢复条件**。
 *
 * 历史案（首开降级，见 `docs/验收文档/20260920-待分析问题清单.md`「另记」）：`panel.banner.degradedPsi`
 * 原文写「**IDE 索引完成后**重新打开预览即可恢复完整支持」，但预览文档降级链
 * （`SqlPreviewPanel.fallbackPreviewDocument` → `createInMemoryPreviewPsi`）**没有任何 DumbService
 * 判据**——grep 全 `src/main/kotlin`，`DumbService` 只出现在 `execute/DataSourceSelector.kt` 与
 * `mcp/McpToolsetSupport.kt`，`ui/` 下零命中。用户照文案去「等索引」永远等不到，而真正生效的解法
 * 就是关掉重开（真机走查实测：重开稳定走预热回调主路径、无横幅）。
 *
 * **为何要两条断言**：只有负向断言时，「把整句删掉」也能通过——那会留下一条**没有出路的告警**。
 * 故正向断言要求降级横幅必须写出真正可行的补救动作，负向断言要求不得出现代码里不存在的条件。
 * 两条合起来才是「文案不得承诺代码做不到的事」这个不变式。
 *
 * **该文案当时无自动化覆盖**（2026-09-20 车道 B 的变异实验：把文案改回旧口径后，
 * `DegradationBannerTest` 与三个 bundle 守卫仍 17/17 全绿——前者只断言横幅显隐与类别，后者只校验
 * 键集合/占位符/键引用方向，都不看文案值）。本守卫即补这个缺口。
 *
 * 纯逻辑测试（零平台依赖）：按 UTF-8 读 classpath bundle。
 */
class DegradedBannerWordingGuardTest {

    private fun loadBundle(suffix: String): Map<String, String> {
        val stream = javaClass.classLoader.getResourceAsStream("messages/MpSqlVisualizerBundle$suffix.properties")
            ?: error("bundle 资源缺失: messages/MpSqlVisualizerBundle$suffix.properties")
        val props = Properties()
        stream.use { props.load(it.reader(Charsets.UTF_8)) }
        return props.stringPropertyNames().associateWith { props.getProperty(it) }
    }

    /** 取 `panel.banner.*` 全部键值（两个语言包各取一次，键前缀为 `en:` / `zh:`）。 */
    private fun bannerEntries(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for ((suffix, lang) in listOf("" to "en", "_zh" to "zh")) {
            loadBundle(suffix).forEach { (k, v) ->
                if (k.startsWith("panel.banner.")) out += "$lang:$k" to v
            }
        }
        return out
    }

    /** 自检：命中数过低即判「扫描器失效」，防本类规则假绿（bundle 改路径/键名时会先在此红）。 */
    @Test
    fun `扫描自检_banner键数非零`() {
        val entries = bannerEntries()
        assertTrue("应扫到两个语言包的 panel.banner.* 键（实得 ${entries.size} 条，过少 = 扫描失效）", entries.size >= 6)
    }

    /**
     * 负向：不得让用户去等一个代码里不存在的条件。
     *
     * 预览链路没有 DumbService 判据，故 `panel.banner.*` 任何一条都不该出现「索引 / indexing / dumb」
     * （这两个词只会出现在「等索引」这类不成立的补救条件里）。
     */
    @Test
    fun `横幅不得把等索引写成恢复条件`() {
        val offenders = bannerEntries()
            .filter { (_, v) -> Regex("索引|[Ii]ndexing|Dumb|dumb").containsMatchIn(v) }
            .map { (k, v) -> "$k → $v" }
        assertFalse(
            "预览降级链没有 DumbService 判据（grep 核实：ui/ 下零命中），横幅不得让用户等索引: $offenders",
            offenders.isNotEmpty(),
        )
    }

    /**
     * 正向：降级横幅必须给出**真正可行**的补救动作——只删不写等于留下没有出路的告警。
     *
     * T-DegradedPreviewUx P2：原单一 `degradedPsi` 按原因拆成 `degradedTimeout` / `degradedFailed`
     * （外加第三级 `degradedPlain`）。三条都必须仍能指向「重试」这条就地出路——此前写「关掉重开」
     * 虽然属实，但把一次本可自动完成的恢复外包给了用户。
     */
    @Test
    fun `降级横幅必须写出重试这条出路`() {
        val entries = bannerEntries().toMap()
        for (lang in listOf("en", "zh")) {
            for (key in listOf(
                "panel.banner.degradedTimeout",
                "panel.banner.degradedFailed",
                "panel.banner.degradedPlain",
            )) {
                val v = entries["$lang:$key"]
                    ?: error("缺少键 $lang:$key（bundle 结构变了，本守卫需同步）")
                val hasRemedy = if (lang == "zh") {
                    v.contains("重试")
                } else {
                    v.contains("Retry", ignoreCase = true)
                }
                assertTrue("$lang:$key 未写出「重试」这条可行补救动作: $v", hasRemedy)
            }
        }
    }
}
