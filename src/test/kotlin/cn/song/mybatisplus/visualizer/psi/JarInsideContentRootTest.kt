package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * T-ShadowEntityResolve W5 端到端护栏：**真 jar 放在模块内容根下的 `resources/lib/`**，
 * jar 内实体与工程源码影子同 FQN，预览必须解析到**工程源码那份**且不降级。
 *
 * 为什么用真 jar：本用例的前身（W4）用「解包目录」代替 jar，jar 那份根本没进候选 ⇒ 假绿。
 * 本类用 `JarFileSystem` 把**真实 jar**挂成库根（先经探针验证 jar 内 class 能进短名索引），
 * 且 jar 物理路径位于 source root 之下，复刻用户 `src/main/resources/lib/` 的布局。
 *
 * 解析口径由 `narrowAmbiguous` 三步收窄保证（内容 → 模块依赖方向 → 源码形态），本类从端到端
 * 验证整链：locator 认领 → 实体解析 → 表名/列清单 → 不降级。
 */
class JarInsideContentRootTest : HeavyPlatformTestCase() {

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

    private fun tempDir(): File = Files.createTempDirectory("jar-in-content-").toFile().also(dirs::add)

    private fun javac(): File {
        val exe = if (System.getProperty("os.name").startsWith("Windows")) "javac.exe" else "javac"
        return listOfNotNull(System.getenv("JAVA_HOME"), System.getProperty("java.home"), "D:/HuanJing/Java/jdk1.8.0_181")
            .map { File(it, "bin/$exe") }.firstOrNull(File::isFile) ?: error("需要真实外部 JDK")
    }

    private fun jpaStubs(dir: File) {
        listOf(
            "Entity" to "public @interface Entity { String name() default \"\"; }",
            "Table" to "public @interface Table { String name(); }",
            "Column" to "public @interface Column { String name(); }",
            "Id" to "public @interface Id { }",
        ).forEach { (n, b) ->
            File(dir, "javax/persistence/$n.java").apply {
                parentFile.mkdirs()
                writeText("package javax.persistence; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) $b")
            }
        }
    }

    /**
     * 造**真 jar**（jar 内实体表 `wms_task_jar` + DAO + 带 HQL 的 service），放进 source root 下的
     * `src/main/resources/lib/`，并以库根挂载。返回 jar 里的 service 类（桩 PSI）。
     */
    private fun jarInResourcesLib(): PsiClass {
        val work = tempDir()
        val src = File(work, "src").apply { mkdirs() }
        val out = File(work, "out").apply { mkdirs() }
        jpaStubs(src)
        File(src, "com/example/shadow/model/WmsTask.java").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.example.shadow.model;
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
        File(src, "com/example/shadow/dao/CommonDao.java").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.example.shadow.dao;
                import java.util.List;
                import java.util.Collections;
                public class CommonDao {
                    public List<?> findByQuery(String hql, String[] names, Object[] values) {
                        return Collections.emptyList();
                    }
                }
                """.trimIndent(),
            )
        }
        File(src, "com/example/shadow/service/WmsTaskJarService.java").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.example.shadow.service;
                import java.util.List;
                import com.example.shadow.dao.CommonDao;
                public class WmsTaskJarService {
                    private final CommonDao commonDao = new CommonDao();
                    public List<?> beAllocateSuccess() {
                        String hql = "FROM WmsTask task WHERE task.status = :status AND task.type = :type";
                        return this.commonDao.findByQuery(hql, new String[]{"status","type"}, new Object[]{"OP","PU"});
                    }
                }
                """.trimIndent(),
            )
        }
        val jc = javac()
        val stubs = listOf("Entity", "Table", "Column", "Id").map { File(src, "javax/persistence/$it.java").absolutePath }
        val log = File(work, "javac.log")
        val p = ProcessBuilder(
            listOf(jc.absolutePath, "-source", "8", "-target", "8", "-d", out.absolutePath) + stubs +
                File(src, "com/example/shadow/model/WmsTask.java").absolutePath +
                File(src, "com/example/shadow/dao/CommonDao.java").absolutePath +
                File(src, "com/example/shadow/service/WmsTaskJarService.java").absolutePath,
        ).redirectErrorStream(true).redirectOutput(log).start()
        assertTrue(log.readText(), p.waitFor(30, TimeUnit.SECONDS))
        assertEquals(log.readText(), 0, p.exitValue())

        val jarFile = File(work, "shadow-entity-lib-1.0.0.jar")
        val classFiles = out.walkTopDown().filter { it.isFile && it.extension == "class" }.toList()
        assertTrue("javac 应产出 class 文件", classFiles.isNotEmpty())
        java.util.jar.JarOutputStream(jarFile.outputStream()).use { jar ->
            jar.putNextEntry(java.util.zip.ZipEntry("META-INF/MANIFEST.MF"))
            jar.write("Manifest-Version: 1.0\r\n\r\n".toByteArray()); jar.closeEntry()
            classFiles.forEach { cf ->
                jar.putNextEntry(java.util.zip.ZipEntry(cf.relativeTo(out).path.replace('\\', '/')))
                jar.write(cf.readBytes()); jar.closeEntry()
            }
        }

        // jar 落到模块 source root 下的 src/main/resources/lib/（复刻用户布局）
        val root = tempDir()
        val rootVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(root)!!
        PsiTestUtil.addSourceRoot(myModule, rootVf)
        val libDir = File(root, "src/main/resources/lib").apply { mkdirs() }
        val staged = File(libDir, "shadow-entity-lib-1.0.0.jar")
        jarFile.copyTo(staged, overwrite = true)
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(staged)?.refresh(false, true)

        val jarRoot = JarFileSystem.getInstance()
            .refreshAndFindFileByPath(staged.absolutePath.replace('\\', '/') + "!/")
            ?: error("jar 未打开：$staged")
        ModuleRootModificationUtil.addModuleLibrary(myModule, "shadow-entity-lib", listOf(jarRoot.url), emptyList())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        return JavaPsiFacade.getInstance(project)
            .findClass("com.example.shadow.service.WmsTaskJarService", GlobalSearchScope.allScope(project))
            ?: error("jar 里的 WmsTaskJarService 解析不到")
    }

    /** 工程源码影子（同 FQN，表 `wms_task_project`，列多一个 remark）。 */
    private fun projectShadow() {
        val root = tempDir()
        val rootVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(root)!!
        PsiTestUtil.addSourceRoot(myModule, rootVf)
        File(root, "src/main/java/com/example/shadow/model").apply { mkdirs() }
        File(root, "src/main/java/com/example/shadow/model/WmsTask.java").writeText(
            """
            package com.example.shadow.model;
            @javax.persistence.Entity
            @javax.persistence.Table(name="wms_task_project")
            public class WmsTask {
                @javax.persistence.Id public long id;
                @javax.persistence.Column(name="status") public String status;
                @javax.persistence.Column(name="task_type") public String type;
                @javax.persistence.Column(name="remark") public String remark;
            }
            """.trimIndent(),
        )
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(
            File(root, "src/main/java/com/example/shadow/model/WmsTask.java"),
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    /**
     * 端到端：jar 内宿主的 HQL 预览解析到**工程源码影子**（`wms_task_project`），不降级。
     * 走反编译 PSI（桩 PSI 无方法体），与 IDE 里点图标时 daemon 的取数方式一致。
     */
    fun testJarInResourcesLibPreviewUsesProjectShadow() {
        val serviceClass = jarInResourcesLib()
        projectShadow()

        val stubMethod = serviceClass.findMethodsByName("beAllocateSuccess", false).single()
        assertNull("桩 PSI 不应有方法体", stubMethod.body)

        // 候选里应同时有 jar 内二进制与工程源码两份（同 FQN）
        val resolution = EntityClassResolver.resolveByName(project, "WmsTask", stubMethod)
        assertEquals("应唯一收窄到工程源码那份", 1, resolution.candidates.size)
        assertFalse(resolution.ambiguous)
        assertEquals("java", resolution.candidates.single().containingFile.virtualFile.extension)

        val mirror = (stubMethod.containingFile as PsiCompiledFile).getDecompiledPsiFile()
        val mirrorMethod = (mirror as PsiJavaFile).classes.single()
            .findMethodsByName("beAllocateSuccess", false).single()
        assertNotNull("反编译 PSI 应有方法体", mirrorMethod.body)
        val call = mirrorMethod.body!!
            .let { PsiTreeUtil.collectElementsOfType(it, PsiMethodCallExpression::class.java) }
            .first { it.methodExpression.referenceName == "findByQuery" }

        val preview = SqlPreviewService(project).previewCustomDaoHql(call)!!
        assertEquals("wms_task_project", preview.tableName)
        assertTrue(preview.sql, preview.sql.contains("task_type"))
        assertTrue(preview.sql, preview.sql.contains("remark"))
        assertFalse("jar 在内容根下不应降级：${preview.sql}", preview.sql.contains("实体不可解析"))
        assertFalse(preview.sql, preview.sql.contains("unknown_table"))
    }

    /** jar 内 class 的真实身份读数：库类（内容与否由 IDE 决定，本用例不假设）。 */
    fun testJarClassIsRecognizedAsLibraryClass() {
        jarInResourcesLib()
        val fileIndex = ProjectFileIndex.getInstance(project)
        val jarClass = JavaPsiFacade.getInstance(project)
            .findClass("com.example.shadow.model.WmsTask", GlobalSearchScope.allScope(project))
        assertNotNull("jar 内实体应可解析", jarClass)
        val vf = jarClass!!.containingFile.virtualFile
        assertEquals("应读到 jar 内 class 而非解包目录", "class", vf.extension)
        assertTrue("jar 内 class 应被识别为库类：${vf.path}", fileIndex.isInLibraryClasses(vf))
    }
}
