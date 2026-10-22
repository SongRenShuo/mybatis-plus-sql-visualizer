package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * T-ShadowEntityResolve 回归护栏：**库内宿主 + 工程同名实体**的实体解析口径。
 *
 * 场景（用户真机 20261022 报障）：DAO 与 HQL 都在 jar 内、HQL 引用的实体也在 jar 内，工程里另有一份
 * 同包同名（或同短名）的类。库内宿主拿不到模块上下文（平台对库文件 `getModuleForFile` 恒 null），
 * 实体解析退到 `allScope`——本类钉住此时**该取哪一份 / 该不该判歧义**。
 *
 * W0 实测矩阵（20261022，修复前 → 修复后）：
 *  - V1 同 FQN 影子**同模块**：候选 1 工程胜出 → 不变（平台 allScope 对同内容-库组合有序）
 *  - V2 同 FQN 影子**另一模块**：2 候选降级 → **修复**（工程内容优先）
 *  - V3 异 FQN 双实体同模块：2 候选降级 → 不变（Hibernate 真正的重名冲突，fail closed 正确）
 *  - V4 异 FQN 双实体跨模块：2 候选降级 → 不变（同 V3）
 *  - V5 HQL 写全限定名：仍降级 → **修复**
 *  - V6 同 FQN 影子在**依赖 jar 模块的业务模块**（最贴近用户）：2 候选降级 → **修复**
 *  - V7 影子是**非实体类**（普通 DTO）：降级 → **修复**（非实体类不构成 JPA 实体歧义）
 */
class ShadowEntityResolveTest : HeavyPlatformTestCase() {

    private val dirs = mutableListOf<File>()
    private val roots = mutableMapOf<Module, VirtualFile>()

    override fun setUp() {
        super.setUp()
        EntityClassResolver.clearAmbiguityNotifiedForTest(project)
    }

    override fun tearDown() {
        try {
            EntityClassResolver.ambiguitySinkForTest = null
            EntityClassResolver.clearAmbiguityNotifiedForTest(project)
            roots.clear()
        } finally {
            dirs.forEach { it.deleteRecursively() }
            super.tearDown()
        }
    }

    private fun tempDir(): File = Files.createTempDirectory("shadow-entity-").toFile().also(dirs::add)

    private fun ready() = IndexingTestUtil.waitUntilIndexesAreReady(project)

    private fun externalJavac(): File {
        val exe = if (System.getProperty("os.name").startsWith("Windows")) "javac.exe" else "javac"
        return listOfNotNull(
            System.getProperty("mpv.entityResolver.javacHome"), System.getenv("JAVA_HOME"),
            System.getProperty("java.home"), "D:/HuanJing/Java/jdk1.8.0_181",
        ).map { File(it, "bin/$exe") }.firstOrNull(File::isFile) ?: error("需要真实外部 JDK")
    }

    private fun jpaStubs(dir: File) {
        listOf(
            "Entity" to "public @interface Entity { String name() default \"\"; }",
            "Table" to "public @interface Table { String name(); }",
            "Column" to "public @interface Column { String name(); }",
            "Id" to "public @interface Id { }",
        ).forEach { (name, body) ->
            File(dir, "javax/persistence/$name.java").apply {
                parentFile.mkdirs()
                writeText("package javax.persistence; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) $body")
            }
        }
    }

    /**
     * 造「jar」：class 根放实体二进制（`wms_task_jar`），source 根放 DAO（HQL 字面量在方法体内）。
     * 返回库里的 DAO 类 PSI——宿主在库源码根，正是用户「HQL 在 jar 里」的形态。
     */
    private fun jarWithDaoAndEntity(hostModule: Module): PsiClass {
        val dir = tempDir()
        val sources = File(dir, "sources").apply { mkdirs() }
        val classes = File(dir, "classes").apply { mkdirs() }
        jpaStubs(sources)
        File(sources, "com/acme/WmsTask.java").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.acme;
                @javax.persistence.Entity
                @javax.persistence.Table(name="wms_task_jar")
                public class WmsTask {
                    @javax.persistence.Id public long id;
                    @javax.persistence.Column(name="task_no") public String taskNo;
                    @javax.persistence.Column(name="status") public String status;
                    @javax.persistence.Column(name="type") public String type;
                }
                """.trimIndent(),
            )
        }
        File(sources, "com/acme/CommonDao.java").apply {
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
                    public List<?> beAllocateSuccess(String containerCode, Long warehouseId) {
                        String hql = "FROM WmsTask task WHERE task.status = :status AND task.type = :type";
                        return this.findByQuery(hql, new String[]{"status", "type"}, new Object[]{"OP", "PU"});
                    }
                }
                """.trimIndent(),
            )
        }
        val javac = externalJavac()
        val stubs = listOf("Entity", "Table", "Column", "Id").map { File(sources, "javax/persistence/$it.java").absolutePath }
        val log = File(dir, "javac.log")
        val p = ProcessBuilder(
            listOf(javac.absolutePath, "-source", "8", "-target", "8", "-d", classes.absolutePath) + stubs +
                File(sources, "com/acme/WmsTask.java").absolutePath,
        ).redirectErrorStream(true).redirectOutput(log).start()
        assertTrue(log.readText(), p.waitFor(30, TimeUnit.SECONDS))
        assertEquals(log.readText(), 0, p.exitValue())

        val classesVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(classes)!!
        classesVf.refresh(false, true)
        val sourcesVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(sources)!!
        sourcesVf.refresh(false, true)
        ModuleRootModificationUtil.addModuleLibrary(hostModule, "shadow-lib", listOf(classesVf.url), listOf(sourcesVf.url))
        ready()
        val daoVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(sources, "com/acme/CommonDao.java"))!!
        return (PsiManager.getInstance(project).findFile(daoVf) as PsiJavaFile).classes.single()
    }

    /** 工程侧实体（表名与列清单都与 jar 版不同，便于判定取了哪一份）。 */
    private fun projectEntity(module: Module, pkg: String, table: String): PsiClass =
        writeProject(module, pkg, "WmsTask",
            """
            @javax.persistence.Entity
            @javax.persistence.Table(name="$table")
            public class WmsTask {
                @javax.persistence.Id public long id;
                @javax.persistence.Column(name="task_no") public String taskNo;
                @javax.persistence.Column(name="status") public String status;
                @javax.persistence.Column(name="task_type") public String type;
                @javax.persistence.Column(name="remark") public String remark;
            }
            """.trimIndent())

    private fun writeProject(module: Module, pkg: String, name: String, body: String): PsiClass {
        val root = roots.getOrPut(module) {
            val dir = tempDir()
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!.also { PsiTestUtil.addSourceRoot(module, it) }
        }
        val file = WriteCommandAction.runWriteCommandAction(project, Computable {
            val vf = VfsUtil.createDirectoryIfMissing(root, pkg.replace('.', '/'))!!
                .createChildData(this, "$name.java")
            VfsUtil.saveText(vf, "package $pkg; $body")
            PsiManager.getInstance(project).findFile(vf) as PsiJavaFile
        })
        return file.classes.single()
    }

    private fun preview(dao: PsiClass) = SqlPreviewService(project).previewCustomDaoHql(
        dao.findMethodsByName("beAllocateSuccess", false).single().body!!
            .let { PsiTreeUtil.collectElementsOfType(it, PsiMethodCallExpression::class.java) }
            .single { it.methodExpression.referenceName == "findByQuery" },
    )

    /** 同 FQN 影子落在**依赖 jar 模块的业务模块**（用户真实形态）：工程内容那份胜出。 */
    fun testSameFqnShadowInDependentModuleWins() {
        val biz = createModule("shadow-biz")
        val dao = jarWithDaoAndEntity(myModule)
        projectEntity(biz, "com.acme", "wms_task_biz_override")
        ModuleRootModificationUtil.addDependency(biz, myModule)
        ready()

        val resolution = EntityClassResolver.resolveByName(project, "WmsTask", dao.findMethodsByName("beAllocateSuccess", false).single())
        assertEquals("工程内那份应唯一胜出", 1, resolution.candidates.size)
        assertFalse(resolution.ambiguous)

        val result = preview(dao)!!
        assertEquals("wms_task_biz_override", result.tableName)
        assertTrue(result.sql, result.sql.contains("task_type"))
        assertTrue(result.sql, result.sql.contains("remark"))
        assertFalse("不应再降级：${result.sql}", result.sql.contains("实体不可解析"))
        assertFalse("不应再用 fallback 表：${result.sql}", result.sql.contains("unknown_table"))
    }

    /** 同 FQN，HQL 写全限定名时同样解析到工程那份（此前限定名也救不了）。 */
    fun testSameFqnShadowResolvesForQualifiedNameLookup() {
        val biz = createModule("shadow-biz")
        val dao = jarWithDaoAndEntity(myModule)
        projectEntity(biz, "com.acme", "wms_task_biz_override")
        ModuleRootModificationUtil.addDependency(biz, myModule)
        ready()

        val method = dao.findMethodsByName("beAllocateSuccess", false).single()
        val byFqn = EntityClassResolver.resolveByName(project, "com.acme.WmsTask", method)
        assertEquals(1, byFqn.candidates.size)
        assertTrue(byFqn.candidates.single().containingFile.virtualFile.path, byFqn.candidates.single().containingFile.virtualFile.url.contains("/classes/").not())
    }

    /** 影子是**非实体类**（普通 DTO）：不构成 JPA 实体歧义，库里的真实体正常解析。 */
    fun testNonEntityShadowDoesNotCreateAmbiguity() {
        val dao = jarWithDaoAndEntity(myModule)
        writeProject(myModule, "com.acme.dto", "WmsTask", "public class WmsTask { public String taskNo; public String remark; }")
        ready()

        val method = dao.findMethodsByName("beAllocateSuccess", false).single()
        val resolution = EntityClassResolver.resolveByName(project, "WmsTask", method)
        assertEquals("非实体同名类不应参与歧义", 1, resolution.candidates.size)
        assertEquals("com.acme.WmsTask", resolution.candidates.single().qualifiedName)

        val result = preview(dao)!!
        assertEquals("wms_task_jar", result.tableName)
        assertFalse(result.sql, result.sql.contains("实体不可解析"))
    }

    /** 异 FQN 的**两个真实体**仍是真歧义：必须 fail closed，不得挑一个。 */
    fun testTwoRealEntitiesWithSameShortNameStillFailClosed() {
        val dao = jarWithDaoAndEntity(myModule)
        projectEntity(myModule, "com.acme.shadow", "wms_task_shadow")
        ready()

        val method = dao.findMethodsByName("beAllocateSuccess", false).single()
        val resolution = EntityClassResolver.resolveByName(project, "WmsTask", method)
        assertEquals(2, resolution.candidates.size)
        assertTrue("两个真实体同名属真歧义", resolution.ambiguous)
        assertNull(resolution.psiClass)
    }

    /** 同 FQN 但两份都在库/都非工程内容时不臆造胜者（既有契约保持）。 */
    fun testSameFqnWithoutProjectContentStaysAmbiguous() {
        val dao = jarWithDaoAndEntity(myModule)
        ready()
        val method = dao.findMethodsByName("beAllocateSuccess", false).single()
        val resolution = EntityClassResolver.resolveByName(project, "WmsTask", method)
        assertEquals("只有库内一份，正常解析", 1, resolution.candidates.size)
        assertEquals("com.acme.WmsTask", resolution.candidates.single().qualifiedName)
    }
}
