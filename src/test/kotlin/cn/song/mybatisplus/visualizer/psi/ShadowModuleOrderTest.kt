package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootModificationUtil
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
 * T-ShadowEntityResolve W6 护栏：**两个模块各有一份同 FQN 源码**时的实体仲裁。
 *
 * 用户 20261023 真机日志（决定性读数）：
 *
 * ```
 * entity-ambiguity[0] ...WmsTask ext=java inContent=true module=shadow-entity-demo
 *   url=.../shadow-entity-demo/src/main/java/com/example/shadow/model/WmsTask.java
 * entity-ambiguity[1] ...WmsTask ext=java inContent=true module=shadow-entity-lib
 *   url=.../shadow-entity-lib/src/main/java/com/example/shadow/model/WmsTask.java
 * ```
 *
 * 两个候选**都是 `.java`**、都在工程内容里，只是分属两个模块（jar 内那份 class 已被平台自身的
 * 同 FQN 去重淘汰）。此时「内容优先」「源码形态优先」两招都失效 ⇒ 歧义 ⇒ 降级。
 *
 * 正确判据是**模块依赖方向**：demo 依赖 lib，运行期 demo 自己的输出先于 lib ⇒ demo 那份生效。
 * 平台 `ProjectAndLibrariesScope.compare` 本身就编码了这个方向（依赖模块在本模块之后），
 * 但原实现在「宿主无模块」（jar 内宿主）时整段跳过平台仲裁，导致信息白丢。
 *
 * 本类夹具：宿主在 jar 里（`contextModule == null`），两个模块各有同 FQN 源码且 demo→lib 有依赖。
 */
class ShadowModuleOrderTest : HeavyPlatformTestCase() {

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

    private fun tempDir(): File = Files.createTempDirectory("w6-module-order-").toFile().also(dirs::add)

    private fun javac(): File {
        val exe = if (System.getProperty("os.name").startsWith("Windows")) "javac.exe" else "javac"
        return listOfNotNull(System.getenv("JAVA_HOME"), System.getProperty("java.home"), "D:/HuanJing/Java/jdk1.8.0_181")
            .map { File(it, "bin/$exe") }.firstOrNull(File::isFile) ?: error("需要外部 JDK")
    }

    private fun writeSource(module: Module, relPath: String, text: String): PsiClass {
        val root = roots.getOrPut(module) {
            val d = tempDir()
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(d)!!
                .also { PsiTestUtil.addSourceRoot(module, it) }
        }
        val io = File(root.path, relPath).apply { parentFile.mkdirs() }
        io.writeText(text)
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(io)!!
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return (com.intellij.psi.PsiManager.getInstance(project).findFile(vf) as PsiJavaFile).classes.single()
    }

    private val roots = mutableMapOf<Module, com.intellij.openapi.vfs.VirtualFile>()

    /** jar 内宿主（DAO + 带 HQL 的 service），jar 挂库根 ⇒ 宿主无模块上下文。 */
    private fun jarHost(libModule: Module): PsiClass {
        val work = tempDir()
        val src = File(work, "src").apply { mkdirs() }
        val out = File(work, "out").apply { mkdirs() }
        listOf(
            "Entity" to "public @interface Entity { String name() default \"\"; }",
            "Table" to "public @interface Table { String name(); }",
            "Column" to "public @interface Column { String name(); }",
            "Id" to "public @interface Id { }",
        ).forEach { (n, b) ->
            File(src, "javax/persistence/$n.java").apply {
                parentFile.mkdirs()
                writeText("package javax.persistence; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) $b")
            }
        }
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
                    public List<?> findByQuery(String hql, String[] n, Object[] v) { return Collections.emptyList(); }
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
        val stubs = listOf("Entity", "Table", "Column", "Id").map { File(src, "javax/persistence/$it.java").absolutePath }
        val log = File(work, "javac.log")
        val p = ProcessBuilder(
            listOf(javac().absolutePath, "-source", "8", "-target", "8", "-d", out.absolutePath) + stubs +
                File(src, "com/example/shadow/model/WmsTask.java").absolutePath +
                File(src, "com/example/shadow/dao/CommonDao.java").absolutePath +
                File(src, "com/example/shadow/service/WmsTaskJarService.java").absolutePath,
        ).redirectErrorStream(true).redirectOutput(log).start()
        assertTrue(log.readText(), p.waitFor(30, TimeUnit.SECONDS))
        assertEquals(log.readText(), 0, p.exitValue())

        val jar = File(work, "shadow-entity-lib-1.0.0.jar")
        java.util.jar.JarOutputStream(jar.outputStream()).use { j ->
            j.putNextEntry(java.util.zip.ZipEntry("META-INF/MANIFEST.MF"))
            j.write("Manifest-Version: 1.0\r\n\r\n".toByteArray()); j.closeEntry()
            out.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { cf ->
                j.putNextEntry(java.util.zip.ZipEntry(cf.relativeTo(out).path.replace('\\', '/')))
                j.write(cf.readBytes()); j.closeEntry()
            }
        }
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(jar)?.refresh(false, true)
        val jarRoot = JarFileSystem.getInstance()
            .refreshAndFindFileByPath(jar.absolutePath.replace('\\', '/') + "!/") ?: error("jar 未打开")
        ModuleRootModificationUtil.addModuleLibrary(libModule, "w6-lib", listOf(jarRoot.url), emptyList())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        return JavaPsiFacade.getInstance(project)
            .findClass("com.example.shadow.service.WmsTaskJarService", GlobalSearchScope.allScope(project))
            ?: error("jar 内 service 解析不到")
    }

    private fun entitySource(table: String, typeColumn: String) =
        """
        package com.example.shadow.model;
        @javax.persistence.Entity
        @javax.persistence.Table(name="$table")
        public class WmsTask {
            @javax.persistence.Id public long id;
            @javax.persistence.Column(name="status") public String status;
            @javax.persistence.Column(name="$typeColumn") public String type;
        }
        """.trimIndent()

    /**
     * 两模块同 FQN 源码 + demo→lib 依赖：应解析到 **demo**（依赖方输出先于被依赖方）。
     * 修复前：两候选国家级歧义 ⇒ 降级。
     */
    fun testDependentModuleSourceWins() {
        val lib = createModule("shadow-lib-module")
        val demo = createModule("shadow-demo-module")
        // 宿主在 jar 里（挂在 lib 模块上，宿主本身仍无模块上下文）
        val host = jarHost(lib)
        // 两模块各一份同 FQN 源码
        writeSource(lib, "com/example/shadow/model/WmsTask.java", entitySource("wms_task_jar", "type"))
        writeSource(demo, "com/example/shadow/model/WmsTask.java", entitySource("wms_task_project", "task_type"))
        // demo 依赖 lib（复刻用户工程：demo 用 lib 的 DAO）
        ModuleRootModificationUtil.addDependency(demo, lib)
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val method = host.findMethodsByName("beAllocateSuccess", false).single()
        // 宿主无模块上下文（jar 内）
        assertNull("宿主应无模块上下文", EntityClassResolver.contextModule(project, method))

        val all = JavaPsiFacade.getInstance(project)
            .findClasses("com.example.shadow.model.WmsTask", GlobalSearchScope.allScope(project)).toList()
        val javaOnes = all.filter { it.containingFile.virtualFile.extension == "java" }
        assertEquals("两模块源码都应可见（实际 ${javaOnes.map { it.containingFile.virtualFile.path }}）", 2, javaOnes.size)
        val moduleOf = { c: PsiClass ->
            com.intellij.openapi.roots.ProjectFileIndex.getInstance(project)
                .getModuleForFile(c.containingFile.virtualFile)?.name
        }
        assertEquals(
            "两份源码应分属两个模块",
            setOf("shadow-lib-module", "shadow-demo-module"),
            javaOnes.map(moduleOf).toSet(),
        )

        val resolution = EntityClassResolver.resolveByName(project, "WmsTask", method)
        assertEquals("依赖方向应唯一确定胜者", 1, resolution.candidates.size)
        assertFalse(resolution.ambiguous)
        assertEquals(
            "应取依赖方（demo）那份：${resolution.candidates.single().containingFile.virtualFile.path}",
            "shadow-demo-module",
            moduleOf(resolution.candidates.single()),
        )

        val mirror = (method.containingFile as PsiCompiledFile).getDecompiledPsiFile()
        val mirrorMethod = (mirror as PsiJavaFile).classes.single()
            .findMethodsByName("beAllocateSuccess", false).single()
        val call = mirrorMethod.body!!
            .let { PsiTreeUtil.collectElementsOfType(it, PsiMethodCallExpression::class.java) }
            .first { it.methodExpression.referenceName == "findByQuery" }
        val preview = SqlPreviewService(project).previewCustomDaoHql(call)!!
        assertEquals("wms_task_project", preview.tableName)
        assertFalse("不应降级：${preview.sql}", preview.sql.contains("实体不可解析"))
    }

    /** 反向契约：两模块**无依赖关系**时不得凭空挑一个（保持歧义 fail closed）。 */
    fun testUnrelatedModulesStayAmbiguous() {
        val a = createModule("shadow-a-module")
        val b = createModule("shadow-b-module")
        val host = jarHost(a)
        writeSource(a, "com/example/shadow/model/WmsTask.java", entitySource("wms_task_a", "type"))
        writeSource(b, "com/example/shadow/model/WmsTask.java", entitySource("wms_task_b", "task_type"))
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val method = host.findMethodsByName("beAllocateSuccess", false).single()
        val resolution = EntityClassResolver.resolveByName(project, "WmsTask", method)
        assertTrue("无依赖关系属真歧义，不得挑一个", resolution.ambiguous)
        assertNull(resolution.psiClass)
    }
}
