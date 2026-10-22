package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.psi.jpa.CustomDaoHqlLocator
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * T-ShadowEntityResolve W2 护栏：**无源码 jar（反编译视图）**上的 HQL 预览可达性。
 *
 * 用户 20261022 明确两种 jar 形态都要能识别：sources jar 附加（[ShadowEntityResolveTest] 覆盖）
 * 与无源码时的反编译视图（本类）。平台侧机制（W2 取证，2026-10-22 实测）：
 *
 *  - `.class` 的**桩 PSI 没有方法体**（`ClsMethodImpl.getBody()` 恒 null，实测 `body=false`）⇒
 *    桩 PSI 上不可能看见 HQL 字面量与调用表达式；
 *  - 平台把编译文件的 daemon 整体指向**反编译 PSI**：`DaemonCodeAnalyzerImpl.findFileToHighlight`
 *    对 `PsiCompiledFile` 取 `getDecompiledPsiFile()`，`ClsFileImpl.getMirror()` 用 FernFlower
 *    反编译文本造出一份真 Java PSI（实测有 body、有 `findByQuery(...)` 调用）；
 *  - 该 PSI 上生产链与 sources 形态**产物一致**（实测：locator 认领、表名列名正确、不降级）。
 *
 * 本类钉住后两点：mirror 可达 + 产物正确。编辑器 gutter 图标在反编译视图里是否真的出现属
 * 平台 daemon 行为，须真机走查（计划 §8 P-8 的剩余部分），不在本类覆盖范围。
 */
class DecompiledJarHostPreviewTest : HeavyPlatformTestCase() {

    private val dirs = mutableListOf<File>()

    override fun setUp() {
        super.setUp()
        EntityClassResolver.clearAmbiguityNotifiedForTest(project)
    }

    override fun tearDown() {
        try {
            EntityClassResolver.ambiguitySinkForTest = null
            EntityClassResolver.clearAmbiguityNotifiedForTest(project)
        } finally {
            dirs.forEach { it.deleteRecursively() }
            super.tearDown()
        }
    }

    private fun tempDir(): File = Files.createTempDirectory("decompiled-host-").toFile().also(dirs::add)

    private fun externalJavac(): File {
        val exe = if (System.getProperty("os.name").startsWith("Windows")) "javac.exe" else "javac"
        return listOfNotNull(
            System.getProperty("mpv.entityResolver.javacHome"), System.getenv("JAVA_HOME"),
            System.getProperty("java.home"), "D:/HuanJing/Java/jdk1.8.0_181",
        ).map { File(it, "bin/$exe") }.firstOrNull(File::isFile) ?: error("需要真实外部 JDK")
    }

    /** 只挂 class 根（**无 source 根**）＝无源码 jar；实体与宿主 DAO 都在这个库里。 */
    private fun classOnlyJar(): File {
        val dir = tempDir()
        val src = File(dir, "src").apply { mkdirs() }
        val classes = File(dir, "classes").apply { mkdirs() }
        listOf(
            "Entity" to "public @interface Entity { String name() default \"\"; }",
            "Table" to "public @interface Table { String name(); }",
            "Column" to "public @interface Column { String name(); }",
            "Id" to "public @interface Id { }",
        ).forEach { (name, body) ->
            File(src, "javax/persistence/$name.java").apply {
                parentFile.mkdirs()
                writeText("package javax.persistence; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) $body")
            }
        }
        File(src, "com/acme/WmsTask.java").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.acme;
                @javax.persistence.Entity
                @javax.persistence.Table(name="wms_task_jar")
                public class WmsTask {
                    @javax.persistence.Id public long id;
                    @javax.persistence.Column(name="status") public String status;
                    @javax.persistence.Column(name="type") public String type;
                }
                """.trimIndent(),
            )
        }
        File(src, "com/acme/CommonDao.java").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.acme;
                import java.util.List;
                import java.util.Collections;
                public class CommonDao {
                    public List<?> findByQuery(String hql, String[] paramNames, Object[] values) {
                        return Collections.emptyList();
                    }
                    public List<?> beAllocateSuccess() {
                        String hql = "FROM WmsTask task WHERE task.status = :status AND task.type = :type";
                        return this.findByQuery(hql, new String[]{"status", "type"}, new Object[]{"OP", "PU"});
                    }
                }
                """.trimIndent(),
            )
        }
        val javac = externalJavac()
        val stubs = listOf("Entity", "Table", "Column", "Id").map { File(src, "javax/persistence/$it.java").absolutePath }
        val log = File(dir, "javac.log")
        val p = ProcessBuilder(
            listOf(javac.absolutePath, "-source", "8", "-target", "8", "-d", classes.absolutePath) + stubs +
                File(src, "com/acme/WmsTask.java").absolutePath +
                File(src, "com/acme/CommonDao.java").absolutePath,
        ).redirectErrorStream(true).redirectOutput(log).start()
        assertTrue(log.readText(), p.waitFor(30, TimeUnit.SECONDS))
        assertEquals(log.readText(), 0, p.exitValue())

        val classesVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(classes)!!
        classesVf.refresh(false, true)
        ModuleRootModificationUtil.addModuleLibrary(myModule, "decompiled-lib", listOf(classesVf.url), emptyList())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return File(classes, "com/acme/CommonDao.class")
    }

    /**
     * 平台 daemon 拿到的就是反编译 PSI（`PsiCompiledFile.getDecompiledPsiFile()`），
     * 生产链在该 PSI 上必须与 sources 形态产物一致。
     */
    fun testDecompiledHostPreviewMatchesSourceForm() {
        val daoClassFile = classOnlyJar()
        val daoClassVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(daoClassFile)!!
        assertNotNull("库里的 CommonDao.class 应可见", daoClassVf)

        val psi = PsiManager.getInstance(project).findFile(daoClassVf)!!
        assertTrue("编译文件 PSI 应实现 PsiCompiledFile", psi is PsiCompiledFile)

        // 桩 PSI：方法存在但**没有方法体**——这正是「桩 PSI 上看不见 HQL」的机制证据
        val stubClass = (psi as PsiJavaFile).classes.single()
        assertTrue("桩 PSI 的每个方法都不应有 body", stubClass.methods.all { it.body == null })

        // 反编译 PSI：daemon 实际遍历的那份
        val mirror: PsiFile = (psi as PsiCompiledFile).getDecompiledPsiFile()
        val mirrorClass = (mirror as PsiJavaFile).classes.single()
        assertTrue("反编译 PSI 应带回方法体", mirrorClass.methods.any { it.body != null })

        val call = mirrorClass.methods.first { it.name == "beAllocateSuccess" }.body!!
            .let { PsiTreeUtil.collectElementsOfType(it, PsiMethodCallExpression::class.java) }
            .first { it.methodExpression.referenceName == "findByQuery" }

        // 生产链接线：locator 认领 + 预览产物正确
        assertEquals(
            "FROM WmsTask task WHERE task.status = :status AND task.type = :type",
            CustomDaoHqlLocator.locate(call)?.hql,
        )
        val preview = SqlPreviewService(project).previewCustomDaoHql(call)!!
        assertEquals("wms_task_jar", preview.tableName)
        assertTrue(preview.sql, preview.sql.contains("task.status"))
        assertTrue(preview.sql, preview.sql.contains("task.type"))
        assertFalse("反编译形态不应降级：${preview.sql}", preview.sql.contains("实体不可解析"))
        assertFalse("反编译形态不应走 fallback 表：${preview.sql}", preview.sql.contains("unknown_table"))
    }
}