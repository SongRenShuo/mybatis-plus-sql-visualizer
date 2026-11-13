package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-HotUpdateSettingsGuard 同期修复：`JavaAnnotationIndex` 的**键是注解简单名，不是 FQN**。
 *
 * 243 字节码实证（`plugins/java/lib/java-impl.jar!com/intellij/psi/impl/java/stubs/JavaAnnotationElementType`）：
 * `indexStub` 写入的键＝`getReferenceShortName(stub.getText())`，它先 `indexOf('(')` 截断
 * （属性名/属性值都不进索引）、再取短类名 ⇒ 键恒为 `TableName`。所以
 * `getAnnotations("com.baomidou.mybatisplus.annotation.TableName", …)` **恒空**——而这条兜底只在
 * 「注解类解析不到」时走，正好是它被写出来要救的场景（测试沙盒 / 工程没接 MP 依赖）。
 *
 * 为什么用源码守卫而不是行为用例：`FileBasedIndex`/stub 索引对测试里 `createFileFromText` 造出来的
 * 文件**恒 0 命中**（仓内既有口径），真机才能验；能钉住的就是「传的是简单名」这件事本身。
 * 反向改回 FQN ⇒ 本用例必须变红。
 */
class XmlSqlCompletionContextResolverIndexKeyTest {

    private val source: String by lazy {
        val candidates = listOf(
            File("src/main/kotlin/cn/song/mybatisplus/visualizer/ui/XmlSqlCompletionContextResolver.kt"),
            File("../src/main/kotlin/cn/song/mybatisplus/visualizer/ui/XmlSqlCompletionContextResolver.kt"),
        )
        candidates.firstOrNull { it.isFile }?.readText(Charsets.UTF_8)
            ?: error("找不到 XmlSqlCompletionContextResolver.kt（工作目录=${File(".").absolutePath}）")
    }

    /** 剥注释后再扫——KDoc 里就写着 `getAnnotations(...)` 这几个字，不剥会把说明文字当成调用点。 */
    private val codeOnly: String by lazy {
        source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\n]*"), "")
    }

    @Test
    fun `注解索引按简单名查询_不得传FQN`() {
        val calls = Regex("getAnnotations\\(([^)]*)\\)").findAll(codeOnly).map { it.groupValues[1] }.toList()
        assertEquals("应恰好 1 处 getAnnotations 调用（多出来先看是不是新链路）", 1, calls.size)
        assertTrue(
            "索引键必须是简单名常量 TABLE_NAME_ANNOTATION_SIMPLE_NAME，实际=$calls",
            calls.single().trim().startsWith("TABLE_NAME_ANNOTATION_SIMPLE_NAME"),
        )
    }

    @Test
    fun `简单名常量的值就是TableName`() {
        val m = Regex("""TABLE_NAME_ANNOTATION_SIMPLE_NAME\s*=\s*"([^"]+)"""").find(codeOnly)
        assertEquals("简单名常量必须等于索引里实际的键", "TableName", m?.groupValues?.get(1))
    }
}
