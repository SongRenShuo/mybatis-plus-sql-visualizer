// T-SyncK2JMaster180 测试移植自 master src/test/kotlin/cn/song/mybatisplus/visualizer/mcp/TinyClassFileWriterD239Test.kt
// 互操作适配：DelegateMethod/AnnotationValue 具名实参→位置构造（Java 构造器）；元素表落
// List<kotlin.Pair<String, Any>>（Pair/List 协变 + Java 平台型实参兼容）；kotlin.Metadata 为
// Kotlin 注解类、stub 注解同为 Kotlin 声明——属性访问形态不变。
package cn.song.mybatisplus.visualizer.mcp

import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D239：壳类字节码生成器扩「方法 + 注解」的原语单测（无平台 EP 依赖，纯类加载验证）。
 *
 * 钉住三件事（真机 2026-11-19 定案：旧壳类 `declared=0 / kotlinMeta=false` 导致平台扫描不到
 * `@McpTool` 方法、`requireAnyTool` 抛异常打断 toolset 遍历，工具清单只列前 1~3 个）：
 *  1. 委托方法真的出现在壳类 `declaredMethods` 里（`declared` 不再为 0）；
 *  2. 方法级注解能被反射读到（`@McpTool` 的 name 逐字一致）；
 *  3. class 级注解（`kotlin.Metadata`）能被读到（kotlin-reflect 才肯把它当 Kotlin 类解析）。
 *
 * 壳形态与生产一致：`final class X extends McpToolsetSupport implements McpToolset`，
 * 唯一差异是 iface 用 mcpStub 源集里的桩接口（测试环境无平台 mcpserver jar）。
 */

/**
 * D239 测试用假 toolset 接口。
 *
 * 为什么不用真 `com.intellij.mcpserver.McpToolset`：桩源集（src/mcpStub）只提供注解与少数类型，
 * **没有 McpToolset 接口**（生产运行期由平台 mcpserver 插件提供，测试运行期不存在）→ 壳类
 * defineClass 解析接口即 `NoClassDefFoundError`。既有 `McpToolsetShellFactoryTest` 同样用假接口。
 */
interface D239FakeToolset

class TinyClassFileWriterD239Test {

    // ShellClassLoader 是单例 object：同一 FQN 二次 define 会抛 duplicate class definition，
    // 故每个用例用独立壳名（define 不可撤销）。
    private val shellFqn = "cn.song.mybatisplus.visualizer.mcp.gen.D239ProbeShell" + System.nanoTime()

    private fun buildShell(withDelegate: Boolean = true, withClassAnnotation: Boolean = true): Class<*> {
        val delegates = if (!withDelegate) emptyList() else listOf(
            TinyClassFileWriter.DelegateMethod(
                "sqlv_check_sql",
                // suspend fun sqlv_check_sql(sql: String): String 的 JVM 形态
                "(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
                SqlVisualizerCheckSqlTool::class.java.name,
                listOf(
                    TinyClassFileWriter.AnnotationValue(
                        "Lcom/intellij/mcpserver/annotations/McpTool;",
                        listOf<kotlin.Pair<String, Any>>("name" to "sqlv_check_sql", "title" to "probe title"),
                    ),
                ),
                false,
            ),
        )
        val classAnnotations = if (!withClassAnnotation) emptyList() else listOf(
            TinyClassFileWriter.AnnotationValue(
                "Lkotlin/Metadata;",
                listOf<kotlin.Pair<String, Any>>("k" to 1, "mv" to listOf(1, 9, 0), "d1" to listOf(""), "d2" to listOf("")),
            ),
        )
        val bytes = TinyClassFileWriter.shellBytes(
            shellFqn,
            McpToolsetSupport::class.java.name,
            D239FakeToolset::class.java.name,
            "sqlv_check_sql",
            delegates,
            classAnnotations,
        )
        return McpToolsetDynamicRegistrar.ShellClassLoader.INSTANCE.define(shellFqn, bytes)
    }

    /** 1：委托方法出现在 declaredMethods（D239 的核心诉求——declared 必须非 0）。 */
    @Test
    fun `委托方法出现在壳类 declaredMethods`() {
        val shell = buildShell()
        val declared = shell.declaredMethods.map { it.name }
        assertTrue("壳类应自己声明 sqlv_check_sql，实际：$declared", declared.contains("sqlv_check_sql"))
        val m = shell.declaredMethods.first { it.name == "sqlv_check_sql" }
        assertEquals(
            "描述符应与父类 suspend 形态一致",
            "(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
            jvmDescriptorOf(m),
        )
    }

    /** 2：方法级注解可读且参数一致。 */
    @Test
    fun `方法注解可被反射读取`() {
        val shell = buildShell()
        val m = shell.declaredMethods.first { it.name == "sqlv_check_sql" }
        val ann = m.getAnnotation(McpTool::class.java)
        assertNotNull("方法应带 @McpTool，实际注解：${m.annotations.toList()}", ann)
        assertEquals("sqlv_check_sql", ann!!.name)
        assertEquals("probe title", ann.title)
    }

    /** 3：class 级 kotlin.Metadata 可读（kotlin-reflect 认它是 Kotlin 类的前提）。 */
    @Test
    fun `class 级 Metadata 注解可被读取`() {
        val shell = buildShell()
        val meta = shell.getAnnotation(Metadata::class.java)
        assertNotNull("壳类应带 kotlin.Metadata", meta)
        assertEquals(1, meta!!.kind)
    }

    /**
     * 4：枚举型注解元素（`@McpToolHints` 的 `McpToolHintValue`）与字符串注解（`@McpDescription`）可读。
     *
     * 真机 2026-11-19 定案：枚举 element_value 的 type_name 写成 `Ljava/lang/Enum;` 时，
     * JDK `AnnotationParser.parseEnumValue` 抛
     * `Incorrectly typed data found for annotation element McpToolHints.readOnlyHint()
     *  (Found data of type java.lang.Enum.TRUE)`，整轮 toolset 加载失败、工具全不出现。
     * 本用例就是那条缺陷的反向探针——读注解的那一刻就会炸，不必等真机。
     */
    @Test
    fun `枚举与字符串注解元素可被反射读取`() {
        val fqn = "cn.song.mybatisplus.visualizer.mcp.gen.D239HintShell" + System.nanoTime()
        val delegates = listOf(
            TinyClassFileWriter.DelegateMethod(
                "sqlv_check_sql",
                "(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
                SqlVisualizerCheckSqlTool::class.java.name,
                listOf(
                    TinyClassFileWriter.AnnotationValue(
                        "Lcom/intellij/mcpserver/annotations/McpTool;",
                        listOf<kotlin.Pair<String, Any>>("name" to "sqlv_check_sql", "title" to "probe title"),
                    ),
                    TinyClassFileWriter.AnnotationValue(
                        "Lcom/intellij/mcpserver/annotations/McpDescription;",
                        listOf<kotlin.Pair<String, Any>>("description" to "probe description"),
                    ),
                    TinyClassFileWriter.AnnotationValue(
                        "Lcom/intellij/mcpserver/annotations/McpToolHints;",
                        listOf<kotlin.Pair<String, Any>>(
                            "readOnlyHint" to McpToolHintValue.TRUE,
                            "destructiveHint" to McpToolHintValue.FALSE,
                            "idempotentHint" to McpToolHintValue.UNSPECIFIED,
                            "openWorldHint" to McpToolHintValue.UNSPECIFIED,
                        ),
                    ),
                ),
                false,
            ),
        )
        val bytes = TinyClassFileWriter.shellBytes(
            fqn,
            McpToolsetSupport::class.java.name,
            D239FakeToolset::class.java.name,
            "sqlv_check_sql",
            delegates,
            emptyList(),
        )
        val shell = McpToolsetDynamicRegistrar.ShellClassLoader.INSTANCE.define(fqn, bytes)
        val m = shell.declaredMethods.first { it.name == "sqlv_check_sql" }
        assertEquals(
            "probe description",
            m.getAnnotation(McpDescription::class.java)?.description,
        )
        val hints = m.getAnnotation(McpToolHints::class.java)
        assertNotNull("方法应带 @McpToolHints（读不到即枚举 type_name 编码错）", hints)
        assertEquals(McpToolHintValue.TRUE, hints!!.readOnlyHint)
        assertEquals(McpToolHintValue.FALSE, hints.destructiveHint)
        assertEquals(McpToolHintValue.UNSPECIFIED, hints.idempotentHint)
        assertEquals(McpToolHintValue.UNSPECIFIED, hints.openWorldHint)
    }

    /** 5：旧签名（无委托、无注解）仍可加载——回归保护，生产回退路径依赖它。 */
    @Test
    fun `旧签名无参壳仍可加载`() {
        val legacyFqn = "cn.song.mybatisplus.visualizer.mcp.gen.D239LegacyShell" + System.nanoTime()
        val bytes = TinyClassFileWriter.shellBytes(
            legacyFqn,
            SqlVisualizerCheckSqlTool::class.java.name,
            D239FakeToolset::class.java.name,
        )
        val shell = McpToolsetDynamicRegistrar.ShellClassLoader.INSTANCE.define(legacyFqn, bytes)
        assertEquals(SqlVisualizerCheckSqlTool::class.java, shell.superclass)
        assertEquals(0, shell.declaredMethods.size)
    }

    private fun jvmDescriptorOf(m: java.lang.reflect.Method): String {
        val params = m.parameterTypes.joinToString("") { t -> descriptorOf(t) }
        return "($params)${descriptorOf(m.returnType)}"
    }

    private fun descriptorOf(t: Class<*>): String = when {
        t == Void.TYPE -> "V"
        t == java.lang.Boolean.TYPE -> "Z"
        t == java.lang.Byte.TYPE -> "B"
        t == Character.TYPE -> "C"
        t == java.lang.Short.TYPE -> "S"
        t == Integer.TYPE -> "I"
        t == java.lang.Long.TYPE -> "J"
        t == java.lang.Float.TYPE -> "F"
        t == java.lang.Double.TYPE -> "D"
        t.isArray -> "[" + descriptorOf(t.componentType)
        else -> "L" + t.name.replace('.', '/') + ";"
    }
}
