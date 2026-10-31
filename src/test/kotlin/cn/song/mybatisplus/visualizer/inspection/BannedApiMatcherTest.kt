package cn.song.mybatisplus.visualizer.inspection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 禁用 API 匹配核心单元测试（T-BannedApiCheck S1，纯逻辑——零平台依赖）。
 *
 * 覆盖：类精确/正则两模式、方法正则与空=任意、首条命中优先、非法正则不命中（fail-safe，
 * 不退化成匹配一切）、校验错误分类、方法名廉价短路。
 */
class BannedApiMatcherTest {

    private fun rule(
        className: String,
        classRegex: Boolean = false,
        methodName: String = "",
        description: String = "说明",
        level: String = BannedApiRule.LEVEL_WARNING,
        id: String = "r",
    ) = BannedApiRule(
        id = id,
        className = className,
        classRegex = classRegex,
        methodName = methodName,
        description = description,
        level = level,
    )

    // ---------- 类匹配：精确 / 正则 ----------

    @Test
    fun `精确模式_要求全限定名完全相等`() {
        val rules = listOf(rule("com.acme.LegacyDao", methodName = "save"))
        assertEquals("save", BannedApiMatcher.match("com.acme.LegacyDao", "save", rules)?.methodName)
        assertNull("同简单名的其它包不命中", BannedApiMatcher.match("org.other.LegacyDao", "save", rules))
        assertNull("子类名不命中精确规则", BannedApiMatcher.match("com.acme.LegacyDao2", "save", rules))
    }

    @Test
    fun `正则模式_整串匹配_点号须转义`() {
        val escaped = listOf(rule("""com\.acme\.legacy\..*""", classRegex = true))
        assertEquals(
            "转义后的正则命中包内任意类",
            """com\.acme\.legacy\..*""",
            BannedApiMatcher.match("com.acme.legacy.OldDao", "save", escaped)?.className,
        )

        // 未转义：`.` 是任意字符——这是使用者最容易踩的坑，用「不该命中的串」把语义钉死
        val unescaped = listOf(rule("com.acme.LegacyDao", classRegex = true))
        assertTrue(
            "未转义点号的正则按正则语义解释（comXacmeYLegacyDao 也命中）——精确模式才不会",
            BannedApiMatcher.match("comXacmeYLegacyDao", "save", unescaped) != null,
        )
        assertNull(
            "正则整串匹配：前缀号外扩不命中（com.acme.LegacyDao2）",
            BannedApiMatcher.match("com.acme.LegacyDao2", "save", unescaped),
        )
    }

    // ---------- 方法名 ----------

    @Test
    fun `方法正则为空_表示任意方法`() {
        val rules = listOf(rule("com.acme.LegacyDao"))
        assertEquals(
            "任意方法命中",
            "com.acme.LegacyDao",
            BannedApiMatcher.match("com.acme.LegacyDao", "anyName", rules)?.className,
        )
    }

    @Test
    fun `方法名正则_整串匹配`() {
        val rules = listOf(rule("com.acme.LegacyDao", methodName = "save.*"))
        assertTrue("save 前缀命中", BannedApiMatcher.match("com.acme.LegacyDao", "saveBatch", rules) != null)
        assertNull("不匹配的方法名不命中", BannedApiMatcher.match("com.acme.LegacyDao", "load", rules))
        assertNull(
            "整串匹配：save 出现在中段不命中",
            BannedApiMatcher.match("com.acme.LegacyDao", "presave", rules),
        )
    }

    // ---------- 顺序 / 空值 / 非法正则 ----------

    @Test
    fun `多条规则_首条命中即返回_按规则表顺序`() {
        val rules = listOf(
            rule("com.acme.LegacyDao", methodName = "save.*", description = "第一条", id = "a"),
            rule("""com\.acme\..*""", classRegex = true, description = "第二条", id = "b"),
        )
        assertEquals("先加的规则先赢", "第一条", BannedApiMatcher.match("com.acme.LegacyDao", "save", rules)?.description)
        assertEquals("第一条不命中时落到第二条", "第二条", BannedApiMatcher.match("com.acme.LegacyDao", "find", rules)?.description)
    }

    @Test
    fun `空输入与空白规则_不命中不抛异常`() {
        val rules = listOf(rule("com.acme.LegacyDao", methodName = "save"))
        assertNull("类 FQN 为 null", BannedApiMatcher.match(null, "save", rules))
        assertNull("方法名为 null", BannedApiMatcher.match("com.acme.LegacyDao", null, rules))
        assertNull("类 FQN 空白", BannedApiMatcher.match("   ", "save", rules))
        assertNull("类名空白的规则被跳过", BannedApiMatcher.match("com.acme.LegacyDao", "save", listOf(rule("", methodName = "save"))))
    }

    @Test
    fun `非法正则规则静默失效_不退化成一刀切`() {
        val bad = listOf(rule("com.acme.LegacyDao", classRegex = true).copy(className = "[unclosed"))
        assertNull("类正则写坏 = 不命中", BannedApiMatcher.match("com.acme.LegacyDao", "save", bad))
        val badMethod = listOf(rule("com.acme.LegacyDao", methodName = "save[("))
        assertNull("方法正则写坏 = 不命中", BannedApiMatcher.match("com.acme.LegacyDao", "save", badMethod))
    }

    // ---------- 校验 ----------

    @Test
    fun `校验_各类错误分别可判`() {
        assertEquals(
            BannedApiMatcher.ValidationError.CLASS_NAME_BLANK,
            BannedApiMatcher.validate(rule("  ")),
        )
        assertEquals(
            BannedApiMatcher.ValidationError.DESCRIPTION_BLANK,
            BannedApiMatcher.validate(rule("com.acme.A").copy(description = " ")),
        )
        assertEquals(
            BannedApiMatcher.ValidationError.INVALID_LEVEL,
            BannedApiMatcher.validate(rule("com.acme.A").copy(level = "info")),
        )
        assertEquals(
            BannedApiMatcher.ValidationError.INVALID_CLASS_REGEX,
            BannedApiMatcher.validate(rule("[unclosed", classRegex = true)),
        )
        assertEquals(
            BannedApiMatcher.ValidationError.INVALID_METHOD_REGEX,
            BannedApiMatcher.validate(rule("com.acme.A", methodName = "save[(")),
        )
        assertNull("合法规则无错误", BannedApiMatcher.validate(rule("com.acme.A", methodName = "save.*")))
        assertNull("精确模式类名不需要是合法正则", BannedApiMatcher.validate(rule("com.acme.A[1]")))
    }

    // ---------- 便捷短路 ----------

    @Test
    fun `方法名短路_只在可能命中时返回真`() {
        val compiled = BannedApiMatcher.compile(listOf(rule("com.acme.A", methodName = "save.*")))
        assertFalse("空表", BannedApiMatcher.compile(emptyList()).mayMatchMethodName("save"))
        assertTrue("可能命中", compiled.mayMatchMethodName("saveBatch"))
        assertFalse("不可能命中", compiled.mayMatchMethodName("load"))
        assertFalse("null 不命中", compiled.mayMatchMethodName(null))
        assertTrue(
            "含任意方法规则时一律可能命中",
            BannedApiMatcher.compile(listOf(rule("com.acme.A"))).mayMatchMethodName("whatever"),
        )
    }
}
