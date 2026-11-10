package cn.song.mybatisplus.visualizer.xml.ibatis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `removeFirstPrepend` marker 栈与 `<iterate>` 下标替换单测（T-Ibatis2Support 券 B）。
 *
 * 这里钉的就是「`<dynamic prepend>` 吃掉第一个子 prepend」这条 iBatis 2 独有机制——
 * **`disable`/`peek` 都操作父层**是引擎的实现事实（`SqlTagContext` 里都是 `stack.get(1)`）。
 */
class SqlMapPrependMarkerTest {

    private val ctx = IbatisTagContext()

    @Test
    fun `dynamic 写了 prepend 就默认吃掉第一个子 prepend`() {
        val dynamic = tag(IbatisTagKind.DYNAMIC, prepend = " WHERE ")
        val child = tag(IbatisTagKind.IS_NOT_NULL, property = "a", parent = dynamic)

        ctx.pushRemoveFirstPrependMarker(dynamic)
        ctx.pushRemoveFirstPrependMarker(child)

        assertTrue("父层（dynamic）marker 应为 true", ctx.peekParentRemoveFirstPrepend())
        ctx.disableParentRemoveFirstPrepend()
        assertFalse("被消费一次后父层 marker 变 false", ctx.peekParentRemoveFirstPrepend())
    }

    @Test
    fun `dynamic 无 prepend 时不设 marker`() {
        val dynamic = tag(IbatisTagKind.DYNAMIC, prepend = null)
        ctx.pushRemoveFirstPrependMarker(dynamic)
        ctx.pushRemoveFirstPrependMarker(tag(IbatisTagKind.IS_NOT_NULL, property = "a", parent = dynamic))
        assertFalse(ctx.peekParentRemoveFirstPrepend())
    }

    @Test
    fun `removeFirstPrepend 为 true 或 iterate 时本层 marker 为真`() {
        for (value in listOf("true", "iterate")) {
            val inner = IbatisTagContext()
            val node = tag(IbatisTagKind.IS_NOT_NULL, property = "a", removeFirstPrepend = value)
            inner.pushRemoveFirstPrependMarker(node)
            inner.pushRemoveFirstPrependMarker(tag(IbatisTagKind.IS_NOT_NULL, property = "b", parent = node))
            assertTrue("removeFirstPrepend=$value 应让本层 marker 为真", inner.peekParentRemoveFirstPrepend())
        }
    }

    @Test
    fun `无 prepend 的子标签继承父级 removeFirstPrepend`() {
        val parent = tag(IbatisTagKind.IS_NOT_NULL, property = "a", removeFirstPrepend = "true")
        val child = tag(IbatisTagKind.IS_NULL, property = "b", parent = parent)
        ctx.pushRemoveFirstPrependMarker(parent)
        ctx.pushRemoveFirstPrependMarker(child)
        assertTrue(ctx.peekParentRemoveFirstPrepend())
    }

    @Test
    fun `reEnable 重新武装本层 marker 供下一轮消费`() {
        val node = tag(IbatisTagKind.ITERATE, prepend = null, removeFirstPrepend = "iterate")
        val childA = tag(IbatisTagKind.IS_NOT_NULL, property = "a", parent = node)
        val childB = tag(IbatisTagKind.IS_NOT_NULL, property = "b", parent = node)

        ctx.pushRemoveFirstPrependMarker(node)
        ctx.pushRemoveFirstPrependMarker(childA)
        ctx.disableParentRemoveFirstPrepend()
        assertFalse("先被消费掉", ctx.peekParentRemoveFirstPrepend())
        ctx.popRemoveFirstPrependMarker(childA)

        // removeFirstPrepend="iterate"：每轮迭代重新武装，供下一次消费
        ctx.reEnableRemoveFirstPrepend()
        ctx.pushRemoveFirstPrependMarker(childB)
        assertTrue("reEnable 后应重新可消费", ctx.peekParentRemoveFirstPrepend())
    }

    @Test
    fun `迭代上下文按下标替换方括号占位`() {
        val node = tag(IbatisTagKind.ITERATE, property = "ids")
        val iterate = IbatisIterateContext(listOf("X1", "X2"), node, null)
        iterate.property = "ids"

        assertEquals(-1, iterate.index)
        iterate.next()
        assertEquals(0, iterate.index)
        assertEquals("ids[0]", iterate.addIndexToTagProperty("ids[]"))
        iterate.next()
        assertEquals("ids[1]", iterate.addIndexToTagProperty("ids[]"))
        assertFalse(iterate.hasNext())
        assertTrue(iterate.isLast())
    }

    @Test
    fun `无方括号的属性名原样返回`() {
        val node = tag(IbatisTagKind.ITERATE, property = "ids")
        val iterate = IbatisIterateContext(listOf("X1"), node, null)
        iterate.property = "ids"
        iterate.next()
        assertEquals("plainName", iterate.addIndexToTagProperty("plainName"))
    }

    @Test
    fun `嵌套 iterate 先由父层下标再补本层下标`() {
        val outerTag = tag(IbatisTagKind.ITERATE, property = "orConditions")
        val innerTag = tag(IbatisTagKind.ITERATE, property = "orConditions[].conditions")
        val outer = IbatisIterateContext(listOf(mapOf("conditions" to listOf("c1"))), outerTag, null)
        outer.property = "orConditions"
        outer.next()
        val inner = IbatisIterateContext(listOf("c1"), innerTag, outer)
        inner.property = outer.addIndexToTagProperty("orConditions[].conditions")
        assertEquals("orConditions[0].conditions", inner.property)
        inner.next()
        assertEquals("orConditions[0].conditions[0]", inner.addIndexToTagProperty(inner.property + "[]"))
    }

    @Test
    fun `iterate 属性不是集合时抛可回溯的渲染异常`() {
        val node = tag(IbatisTagKind.ITERATE, property = "notACollection")
        val error = runCatching { IbatisIterateContext("scalar", node, null) }.exceptionOrNull()
        assertNotNull("引擎抛 SqlMapException，本链折成 IbatisRenderException", error)
        assertTrue(error is IbatisRenderException)
        assertTrue("异常信息要能回溯到 property", error!!.message!!.contains("notACollection"))
    }

    private fun tag(
        kind: IbatisTagKind,
        property: String? = null,
        prepend: String? = null,
        removeFirstPrepend: String? = null,
        parent: IbatisTagNode? = null,
    ): IbatisTagNode = IbatisTagNode(
        kind = kind,
        prepend = prepend,
        property = property,
        removeFirstPrepend = removeFirstPrepend,
        open = null,
        close = null,
        conjunction = null,
        compareProperty = null,
        compareValue = null,
        postParseRequired = kind == IbatisTagKind.ITERATE,
        children = emptyList(),
    ).also { node -> parent?.let { node.attachParent(it) } }
}
