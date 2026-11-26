package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.WrapperArg
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 逐列展开条件的参数键命名（D255 T-SetEntityParamKeyCollision）。
 *
 * 命名规则的边界就是「这些名字会被当成 `paramValues` 的键」：合法标识符/点链原样用，
 * 表达式文本（`new User()`、`User.builder().build()`）一律回退 `entity`，避免键里出现括号与空格。
 *
 * k2j 移植注：`ParamSourceNaming` 在 k2j 是无状态 Java 静态工具（原 Kotlin `internal object`），
 * 调用形态 `ParamSourceNaming.prefixOf(...)` / `FALLBACK_PREFIX` 与 master 逐字一致；
 * `WrapperArg.ValueArg(...)` / `SqlValue.Placeholder(...)` / `SqlValue.Literal(...)` 为 Java 构造器，
 * Kotlin 位置实参直呼，无具名实参需改。
 */
class ParamSourceNamingTest {

    @Test
    fun `标识符实参直接作前缀`() {
        assertEquals(
            "query",
            ParamSourceNaming.prefixOf(listOf(WrapperArg.ValueArg(SqlValue.Placeholder("query")))),
        )
    }

    @Test
    fun `点链实参保留层级`() {
        assertEquals(
            "req.user",
            ParamSourceNaming.prefixOf(listOf(WrapperArg.ValueArg(SqlValue.Placeholder("req.user")))),
        )
    }

    @Test
    fun `构造表达式实参回退 entity`() {
        assertEquals(
            ParamSourceNaming.FALLBACK_PREFIX,
            ParamSourceNaming.prefixOf(listOf(WrapperArg.ValueArg(SqlValue.Placeholder("new User()")))),
        )
        assertEquals(
            ParamSourceNaming.FALLBACK_PREFIX,
            ParamSourceNaming.prefixOf(listOf(WrapperArg.ValueArg(SqlValue.Placeholder("User.builder().build()")))),
        )
    }

    @Test
    fun `字面量实参与无实参回退 entity`() {
        assertEquals(
            ParamSourceNaming.FALLBACK_PREFIX,
            ParamSourceNaming.prefixOf(listOf(WrapperArg.ValueArg(SqlValue.Literal("1")))),
        )
        assertEquals(ParamSourceNaming.FALLBACK_PREFIX, ParamSourceNaming.prefixOf(emptyList()))
    }

    @Test
    fun `列键是前缀加点列名`() {
        assertEquals("query.password", ParamSourceNaming.source("query", "password"))
    }

    @Test
    fun `多次展开用序号区分`() {
        assertEquals("query", ParamSourceNaming.uniquePrefix("query", 1))
        assertEquals("query#2", ParamSourceNaming.uniquePrefix("query", 2))
    }
}
