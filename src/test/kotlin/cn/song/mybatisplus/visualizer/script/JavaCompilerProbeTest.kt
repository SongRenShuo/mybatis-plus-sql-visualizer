package cn.song.mybatisplus.visualizer.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.net.URI
import javax.tools.FileObject
import javax.tools.ForwardingJavaFileManager
import javax.tools.JavaFileManager
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider

/**
 * T-RollbackSnapshotPreExec P0（V9/V10 探针，纯逻辑进 testFast）：
 * - V9：`ToolProvider.getSystemJavaCompiler()` 在本 JVM 非空。注意：测试 JVM 是宿主 JDK，
 *   **IDE JVM（JBR）的真值仍待 runIde 复验**，not run 如实登记（CoolRequest 生产实证可用，
 *   见计划 §3.9）。
 * - V10（JDK 侧）：「javac 内存编译 → 自定义 classloader 加载 → MethodHandle 调用」
 *   整链跑通。插件 classloader 边界的真值待 P6 集成与 runIde 复验。
 *
 * 本文件源码刻意不出现平台包名字面量（testFast 划桶按字面过滤，注释里写了会被踢出快桶）。
 */
class JavaCompilerProbeTest {

    private class MemFileManager(
        delegate: JavaFileManager,
        private val outputs: MutableMap<String, ByteArray>,
    ) : ForwardingJavaFileManager<JavaFileManager>(delegate) {
        override fun getJavaFileForOutput(
            location: JavaFileManager.Location,
            className: String,
            kind: JavaFileObject.Kind,
            sibling: FileObject?,
        ): JavaFileObject = object : SimpleJavaFileObject(URI.create("mem:///$className"), kind) {
            override fun openOutputStream(): OutputStream = object : ByteArrayOutputStream() {
                override fun close() {
                    outputs[className] = toByteArray()
                }
            }
        }
    }

    @Test
    fun systemJavaCompilerAvailable() {
        assertNotNull(ToolProvider.getSystemJavaCompiler())
    }

    @Test
    fun compileLoadInvokePipeline() {
        // 注意：`?: Assert.fail(...)` 会把表达式类型推断成 Any（void 方法映射 Unit），
        // 编译期已被此坑过一次——这里必须 throw，保住 JavaCompiler 类型
        val compiler = ToolProvider.getSystemJavaCompiler() ?: throw AssertionError("no javac")
        val outputs = HashMap<String, ByteArray>()
        val manager = MemFileManager(compiler.getStandardFileManager(null, null, null), outputs)
        val src: JavaFileObject = object : SimpleJavaFileObject(
            URI.create("mem:///MemProbe.java"),
            JavaFileObject.Kind.SOURCE,
        ) {
            override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = """
                public class MemProbe {
                    private final int value = 42;
                    public int value() { return value; }
                    public boolean ok() { return value == 42; }
                }
            """.trimIndent()
        }
        val task = compiler.getTask(null, manager, null, listOf("-proc:none"), null, listOf(src))
        assertTrue("编译失败：MemProbe.java", task.call() == true)
        assertEquals("应产出一个类文件", 1, outputs.size)

        val loader = object : ClassLoader(Thread.currentThread().contextClassLoader) {
            override fun findClass(name: String): Class<*> {
                val bytes = outputs[name] ?: throw ClassNotFoundException(name)
                return defineClass(name, bytes, 0, bytes.size)
            }
        }
        val cls = loader.loadClass("MemProbe")
        val instance = cls.getDeclaredConstructor().newInstance()
        val mh = MethodHandles.lookup().findVirtual(cls, "ok", MethodType.methodType(Boolean::class.javaPrimitiveType))
        assertEquals(true, mh.invoke(instance) as Boolean)
        val mhValue = MethodHandles.lookup().findVirtual(cls, "value", MethodType.methodType(Int::class.javaPrimitiveType))
        assertEquals(42, mhValue.invoke(instance) as Int)
    }
}
