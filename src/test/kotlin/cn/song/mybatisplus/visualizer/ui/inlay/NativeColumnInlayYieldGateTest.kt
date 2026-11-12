package cn.song.mybatisplus.visualizer.ui.inlay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * T-PreviewInlayNativeYield：原生让位门的纯逻辑面。
 *
 * 覆盖四块：
 *  ① [NativeColumnInlayYieldGate.matchesAnchor] 锚点真值表——providerId 命中、实现类简单名命中、
 *    非 SQL 语言的同名类不命中、无关实现类不命中、读不到属性时不误命中；
 *  ② 探针三形态——在位 / 不在位 / 探测抛异常；异常必须降级成「不在位」（保留本插件提示），
 *    不得把异常抛进 inlay 收集线程；
 *  ③ 结论级缓存——探测只做一次（探针计数钉住），插件/EP 启停需重启 IDE，不做时效失效；
 *  ④ 真实探测链在**无平台实例**的纯 JUnit 环境下必须降级为「不在位」且不抛（两代 EP 都探不到时
 *     `extensionList` 会抛，`runCatching` 兜住即返回 false）。
 *
 * 纯 JUnit（零平台 fixture）：被测对象是纯判定 + 可注入探针。真机上的「原生确实在位」由
 * 计划 §七 A1~A4 验收，不在单测里断言——测试平台的 DatabaseTools 是否注册该 provider 随桶位漂移，
 * 拿它当断言会变成环境依赖的假绿/假红。
 */
class NativeColumnInlayYieldGateTest {

    @After
    fun tearDown() {
        NativeColumnInlayYieldGate.resetForTest()
    }

    // ---------- ① 锚点真值表 ----------

    @Test
    fun testAnchor_按ProviderId命中() {
        assertTrue(
            "providerId 是 DatabaseTools 自己声明的契约锚点，读到即命中（实现类名可以漂移）",
            NativeColumnInlayYieldGate.matchesAnchor(
                providerId = NativeColumnInlayYieldGate.NATIVE_PROVIDER_ID,
                implementationClass = "com.example.SomeOtherProvider",
                language = "SQL",
            ),
        )
    }

    @Test
    fun testAnchor_按实现类简单名命中_语言缺省也认() {
        assertTrue(
            "legacy bean 只有实现类名可读：language 读不到时只靠类名这一路",
            NativeColumnInlayYieldGate.matchesAnchor(
                providerId = null,
                implementationClass = "com.intellij.sql.inlays.SqlColumnNamesInlayHintsProvider",
                language = null,
            ),
        )
        assertTrue(
            "language 读到且是 SQL 时同样命中",
            NativeColumnInlayYieldGate.matchesAnchor(
                providerId = null,
                implementationClass = "com.intellij.sql.inlays.SqlColumnNamesInlayHintsProvider",
                language = "SQL",
            ),
        )
        assertTrue(
            "内部类后缀只取简单名（`Foo\$Bar` 形态）",
            NativeColumnInlayYieldGate.matchesAnchor(
                providerId = null,
                implementationClass = "com.intellij.sql.inlays.SqlColumnNamesInlayHintsProvider\$Companion",
                language = "sql",
            ),
        )
    }

    @Test
    fun testAnchor_非SQL语言同名类不命中() {
        assertFalse(
            "类名锚必须再叠语言锚：别的语言上注册的同名类不是这条功能",
            NativeColumnInlayYieldGate.matchesAnchor(
                providerId = null,
                implementationClass = "com.intellij.sql.inlays.SqlColumnNamesInlayHintsProvider",
                language = "JAVA",
            ),
        )
    }

    @Test
    fun testAnchor_无关实现类与空值不命中() {
        assertFalse(
            "本插件自己的 provider 不得被自己的让位门认成原生",
            NativeColumnInlayYieldGate.matchesAnchor(
                providerId = null,
                implementationClass = "cn.song.mybatisplus.visualizer.ui.inlay.PreviewInsertColumnInlayProvider",
                language = "SQL",
            ),
        )
        assertFalse("全空不命中", NativeColumnInlayYieldGate.matchesAnchor(null, null, null))
        assertFalse("空串不命中", NativeColumnInlayYieldGate.matchesAnchor("", "", ""))
    }

    // ---------- ② 探针三形态 ----------

    @Test
    fun testProbe_原生在位即让位() {
        NativeColumnInlayYieldGate.resetForTest()
        NativeColumnInlayYieldGate.probe = { true }
        assertTrue("探针报在位时应让位", NativeColumnInlayYieldGate.shouldYield())
    }

    @Test
    fun testProbe_原生不在位保留本插件提示() {
        NativeColumnInlayYieldGate.resetForTest()
        NativeColumnInlayYieldGate.probe = { false }
        assertFalse("探针报不在位时应保留本插件提示", NativeColumnInlayYieldGate.shouldYield())
    }

    @Test
    fun testProbe_探测抛异常降级为不在位() {
        NativeColumnInlayYieldGate.resetForTest()
        NativeColumnInlayYieldGate.probe = { throw IllegalStateException("probe boom") }
        assertFalse("探测失败必须降级为不在位（宁重复不丢功能）", NativeColumnInlayYieldGate.shouldYield())
    }

    // ---------- ③ 缓存 ----------

    @Test
    fun testCache_同会话只探测一次() {
        NativeColumnInlayYieldGate.resetForTest()
        val counter = AtomicInteger(0)
        NativeColumnInlayYieldGate.probe = {
            counter.incrementAndGet()
            true
        }
        repeat(5) { assertTrue(NativeColumnInlayYieldGate.shouldYield()) }
        assertEquals("结论级缓存：同会话内不得反复走探测链", 1, counter.get())
    }

    @Test
    fun testCache_重置后重新探测() {
        NativeColumnInlayYieldGate.resetForTest()
        val counter = AtomicInteger(0)
        NativeColumnInlayYieldGate.probe = {
            counter.incrementAndGet()
            false
        }
        assertFalse(NativeColumnInlayYieldGate.shouldYield())
        NativeColumnInlayYieldGate.resetForTest()
        NativeColumnInlayYieldGate.probe = {
            counter.incrementAndGet()
            true
        }
        assertTrue("重置后应换用新探针结论", NativeColumnInlayYieldGate.shouldYield())
        assertEquals(2, counter.get())
    }

    // ---------- ④ 真实探测链的降级 ----------

    @Test
    fun test真实探测链_无平台实例时降级为不在位且不抛() {
        NativeColumnInlayYieldGate.resetForTest()
        val first = NativeColumnInlayYieldGate.isNativeColumnHintPresent()
        val second = NativeColumnInlayYieldGate.isNativeColumnHintPresent()
        assertEquals("探测结论在一个会话内必须稳定", first, second)
        assertFalse(
            "纯 JUnit 无平台实例时两代 EP 都探不到 ⇒ 必须降级为「不在位」（保留本插件提示），不得抛异常",
            first,
        )
    }
}