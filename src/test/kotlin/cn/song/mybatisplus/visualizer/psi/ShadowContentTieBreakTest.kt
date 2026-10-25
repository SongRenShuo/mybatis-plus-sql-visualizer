package cn.song.mybatisplus.visualizer.psi

import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * W5b 判据级护栏：`narrowAmbiguous` 的**内容步 + 源码形态步**。
 *
 * 用户 20261023 现场读数：歧义提示里两个候选都是 `[工程]`——
 *
 * ```
 * 候选：com.example.shadow.model.WmsTask [工程], com.example.shadow.model.WmsTask [工程]
 * ```
 *
 * 说明该环境下 jar 内 class 也被判成「工程内容」，第一步（按内容收窄）**分不出胜负**。
 * 此时必须靠第二步（**源码形态优先**：`.java` 胜 `.class`）收窄——这正是「工程里新增一份
 * 同包同名类」的本质形态。
 *
 * 本类用真实 PSI（一份 `.java` 源码 + 一份 jar 内 `.class`）＋**可控 isContent 谓词**，
 * 覆盖三种输入形态，不依赖 IDE 具体怎么判 inContent（那正是用户环境与我夹具的分歧点）。
 */
class ShadowContentTieBreakTest : HeavyPlatformTestCase() {

    private val dirs = mutableListOf<File>()

    override fun tearDown() {
        try {
            dirs.forEach { it.deleteRecursively() }
        } finally {
            super.tearDown()
        }
    }

    private fun tempDir(): File = Files.createTempDirectory("w5b-tie-").toFile().also(dirs::add)

    private fun javac(): File {
        val exe = if (System.getProperty("os.name").startsWith("Windows")) "javac.exe" else "javac"
        return listOfNotNull(System.getenv("JAVA_HOME"), System.getProperty("java.home"), "D:/HuanJing/Java/jdk1.8.0_181")
            .map { File(it, "bin/$exe") }.firstOrNull(File::isFile) ?: error("需要外部 JDK")
    }

    /** 造 (源码份, jar 内二进制份)，两者同 FQN。 */
    private fun sourceAndBinary(): Pair<PsiClass, PsiClass> {
        val work = tempDir()
        val srcRoot = File(work, "src").apply { mkdirs() }
        val out = File(work, "out").apply { mkdirs() }
        val body = "package com.example.shadow.model; public class WmsTask { public long id; }"

        File(srcRoot, "com/example/shadow/model/WmsTask.java").apply {
            parentFile.mkdirs(); writeText(body)
        }
        File(work, "libsrc/com/example/shadow/model/WmsTask.java").apply {
            parentFile.mkdirs(); writeText(body)
        }
        val log = File(work, "javac.log")
        val p = ProcessBuilder(
            javac().absolutePath, "-source", "8", "-target", "8", "-d", out.absolutePath,
            File(work, "libsrc/com/example/shadow/model/WmsTask.java").absolutePath,
        ).redirectErrorStream(true).redirectOutput(log).start()
        assertTrue(log.readText(), p.waitFor(30, TimeUnit.SECONDS))
        assertEquals(log.readText(), 0, p.exitValue())

        val jar = File(work, "lib.jar")
        java.util.jar.JarOutputStream(jar.outputStream()).use { j ->
            j.putNextEntry(java.util.zip.ZipEntry("META-INF/MANIFEST.MF"))
            j.write("Manifest-Version: 1.0\r\n\r\n".toByteArray()); j.closeEntry()
            out.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { cf ->
                j.putNextEntry(java.util.zip.ZipEntry(cf.relativeTo(out).path.replace('\\', '/')))
                j.write(cf.readBytes()); j.closeEntry()
            }
        }

        PsiTestUtil.addSourceRoot(myModule, LocalFileSystem.getInstance().refreshAndFindFileByIoFile(srcRoot)!!)
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(jar)?.refresh(false, true)
        val jarRoot = JarFileSystem.getInstance()
            .refreshAndFindFileByPath(jar.absolutePath.replace('\\', '/') + "!/") ?: error("jar 未打开")
        ModuleRootModificationUtil.addModuleLibrary(myModule, "w5b-lib", listOf(jarRoot.url), emptyList())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val all = JavaPsiFacade.getInstance(project)
            .findClasses("com.example.shadow.model.WmsTask", GlobalSearchScope.allScope(project)).toList()
        val javaOne = all.firstOrNull { it.containingFile.virtualFile.extension == "java" }
            ?: error("源码份未解析：${all.map { it.containingFile.virtualFile.path }}")
        val classOne = all.firstOrNull { it.containingFile.virtualFile.extension == "class" }
            ?: error("二进制份未解析：${all.map { it.containingFile.virtualFile.path }}")
        return javaOne to classOne
    }

    /**
     * **用户现场形态**：两份都被判「工程内容」⇒ 第一步分不出胜负 ⇒ 必须靠源码形态收窄。
     * 这是修复前会降级的路径（两个候选都是 `[工程]`）。
     */
    fun testBothContentThenSourceFormWins() {
        val (javaOne, classOne) = sourceAndBinary()
        // k2j 适配：narrowAmbiguous 落成 Java 静态方法（java.util.function 谓词），Kotlin 调 Java
        // 禁具名实参（坑 23/27b），改位置实参 + 两参便捷重载。
        val picked = EntityClassResolver.narrowAmbiguous(listOf(classOne, javaOne), { true })
        assertEquals("两份都是内容时，应由源码形态收窄", 1, picked.size)
        assertEquals("java", picked.single().containingFile.virtualFile.extension)
    }

    /** 常规形态：jar 判为非内容 ⇒ 第一步就收窄（源码份胜出）。 */
    fun testContentStepAloneSufficesWhenJarIsLibrary() {
        val (javaOne, classOne) = sourceAndBinary()
        val picked = EntityClassResolver.narrowAmbiguous(listOf(classOne, javaOne), { clazz ->
            clazz.containingFile.virtualFile.extension == "java"
        })
        assertEquals(1, picked.size)
        assertEquals("java", picked.single().containingFile.virtualFile.extension)
    }

    /** 两份都是源码（跨模块同名）⇒ 两步都不收窄，保持歧义。 */
    fun testTwoSourcesStayAmbiguous() {
        val (javaOne, _) = sourceAndBinary()
        val picked = EntityClassResolver.narrowAmbiguous(listOf(javaOne, javaOne), { true })
        assertEquals("两份源码不得挑一个", 2, picked.size)
    }

    /** 两份都是二进制（两个 jar）⇒ 两步都不收窄，保持歧义。 */
    fun testTwoBinariesStayAmbiguous() {
        val (_, classOne) = sourceAndBinary()
        val picked = EntityClassResolver.narrowAmbiguous(listOf(classOne, classOne), { true })
        assertEquals("两份二进制不得挑一个", 2, picked.size)
    }

    /** 单份原样返回。 */
    fun testSingleUnchanged() {
        val (javaOne, _) = sourceAndBinary()
        assertEquals(1, EntityClassResolver.narrowAmbiguous(listOf(javaOne), { true }).size)
    }
}
