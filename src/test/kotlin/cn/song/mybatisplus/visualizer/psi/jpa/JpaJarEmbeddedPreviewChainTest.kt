package cn.song.mybatisplus.visualizer.psi.jpa

import cn.song.mybatisplus.visualizer.psi.EntityResolver
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import org.junit.Assert.assertNotNull
import java.io.File
import java.nio.file.Files

/**
 * 版本对比探针（T-EmbeddedIdentify）：**JAR 内实体**的 @Embedded 展平端到端读数。
 * 挂载 demo 仓产出的 jpa-jar-model-lib-1.0.0.jar 为库根（真实二进制类），
 * 走 [EntityResolver.resolve] 全链打印列清单。用完即删，不入库。
 */
class JpaJarEmbeddedPreviewChainTest : HeavyPlatformTestCase() {

    private var fixtureJar: File? = null

    override fun setUp() {
        super.setUp()
        fixtureJar = locateFixtureJar()
        fixtureJar?.let { attachFixtureJar(it) }
    }

    private fun fixtureReady(): Boolean = fixtureJar != null

    private fun locateFixtureJar(): File? {
        val candidates = buildList {
            System.getProperty("mpv.w3.fixtureJar")?.let { add(File(it)) }
            val userDir = File(System.getProperty("user.dir") ?: ".")
            add(File(userDir, "../mybatis-plus-sql-visualizer-demo/jpa-jar-model-lib/target/jpa-jar-model-lib-1.0.0.jar"))
            val home = System.getProperty("user.home") ?: "."
            add(File(home, ".m2/repository/cn/song/jpa-jar-model-lib/1.0.0/jpa-jar-model-lib-1.0.0.jar"))
        }
        return candidates.firstOrNull { it.isFile }
    }

    private fun attachFixtureJar(jar: File) {
        val staged = File(File(System.getProperty("java.io.tmpdir")), "tmp-embed-probe-${jar.length()}.jar")
        jar.copyTo(staged, overwrite = true)
        val jarVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(staged)
        assertNotNull(jarVf)
        val jarRootUrl = "jar://" + staged.absolutePath.replace('\\', '/') + "!/"
        ModuleRootModificationUtil.addModuleLibrary(myModule, jarRootUrl)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private fun entityClass(): PsiClass? =
        JavaPsiFacade.getInstance(project).findClass(ENTITY_FQN, GlobalSearchScope.allScope(project))

    fun test探针_JAR实体的嵌入列展平读数() {
        if (!fixtureReady()) {
            println("[JarEmbedChain] SKIP 未找到夹具 jar")
            return
        }
        val clazz = entityClass()
        org.junit.Assert.assertNotNull("JAR 实体应可解析", clazz)
        // JPA 轨（与 resolveHqlTable 一致）——MP 轨不读 @Table，表名会退化为类名推断，不能当读数
        val table = cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver().resolve(clazz!!)
        println("[JarEmbedChain] entity=${clazz.qualifiedName} fromBinary=${clazz.containingFile?.virtualFile?.path?.contains(".jar")}")
        println("[JarEmbedChain] table=${table.tableName} columns=${table.columns.size}")
        table.columns.forEach {
            println(
                "PROBE-JAR>>> col field=${it.fieldName} column=${it.columnName} embedded=${it.embedded} " +
                    "parent=${it.embeddedParent} path=${it.embeddedPath}",
            )
        }
        // JAR 形态契约：@Table 显式表名必须从二进制类读出；嵌入列展开 + embeddedPath 在场
        org.junit.Assert.assertEquals(
            "JAR 实体 @Table(name) 应读出真表名",
            "wms_inventory_jar_demo",
            table.tableName,
        )
        org.junit.Assert.assertTrue(
            "JAR 实体嵌入列应展平：${table.columns.joinToString { "${it.fieldName}→${it.columnName}" }}",
            table.columns.any { it.embedded && it.embeddedPath != null },
        )
    }

    companion object {
        private const val ENTITY_FQN = "cn.song.jarlib.entity.WmsInventoryEntity"
    }

    /** 端到端：语料 DAO 形态（EntityManager DAO + 拼接 HQL）走 previewCustomDaoHql 全链。 */
    fun test探针_语料DAO全链预览() {
        if (!fixtureReady()) {
            println("[JarEmbedChain] SKIP 未找到夹具 jar")
            return
        }
        // 宿主模块形态：pom.xml（探测模块标记）+ src/main/resources/application.yml（component-path）
        // + src/main/java 的 DAO 调用点——与 jar-model-demo 语料同构
        val hostRoot = Files.createTempDirectory("jar-embed-host-").toFile()
        File(hostRoot, "pom.xml").writeText("<project/>")
        File(hostRoot, "src/main/resources/application.yml").apply {
            parentFile.mkdirs()
            writeText(
                "spring:\n  jpa:\n    hibernate:\n      naming:\n        implicit_naming_strategy: component-path\n",
            )
        }
        val hostRootVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(hostRoot)!!
        // 注册为模块源根：文件成为工程 PSI（contextModule / findFile 才可达）
        com.intellij.testFramework.PsiTestUtil.addSourceRoot(myModule, hostRootVf!!)
        var callFileVf: com.intellij.openapi.vfs.VirtualFile? = null
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            val dir = VfsUtil.createDirectoryIfMissing(hostRootVf, "src/main/java")
            println("[JarEmbedChain] dir=$dir")
            callFileVf = dir!!.createChildData(this, "WmsJarQueryService.java")
            VfsUtil.saveText(
                callFileVf!!,
                """
                import java.util.List;

                class WmsJarQueryService {
                    private Object wmsJarCommonDao;

                    public List<?> findAvailableJarInventory(String warehouseId, String inventoryStatus) {
                        String hql = "from WmsInventoryEntity inv "
                                + "where inv.warehouseId = :warehouseId "
                                + "and inv.sku.inventoryStatus = :inventoryStatus "
                                + "and inv.qty.baseQty > 0 "
                                + "and inv.itemKey.lotInfo.receivedDate is not null "
                                + "order by inv.id";
                        return wmsJarCommonDao.findByQueryMaxNum(
                                hql,
                                new String[]{"warehouseId", "inventoryStatus"},
                                new Object[]{warehouseId, inventoryStatus},
                                0,
                                10);
                    }
                }
                """.trimIndent(),
            )
        }
        val callFile = PsiManager.getInstance(project).findFile(
            callFileVf!!,
        ) as com.intellij.psi.PsiJavaFile ?: error("DAO 调用点文件应可取 PSI")
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        // 锚点取方法调用表达式（与 gutter navigate 同位）
        val psiCall = com.intellij.psi.util.PsiTreeUtil.collectElementsOfType(
            callFile,
            com.intellij.psi.PsiMethodCallExpression::class.java,
        ).firstOrNull { it.methodExpression.referenceName == "findByQueryMaxNum" }
        org.junit.Assert.assertNotNull("夹具内应有 findByQueryMaxNum 调用", psiCall)
        val service = project.getService(cn.song.mybatisplus.visualizer.ui.SqlPreviewService::class.java)
        val locateInfo = cn.song.mybatisplus.visualizer.psi.jpa.CustomDaoHqlLocator.locate(psiCall!!)
        println("[JarEmbedChain] locate=${locateInfo != null} kind=${locateInfo?.kind} hql=${locateInfo?.hql?.take(60)}")
        val result = com.intellij.openapi.application.ReadAction.compute<
            cn.song.mybatisplus.visualizer.model.PreviewResult?, RuntimeException> {
            service.previewCustomDaoHql(psiCall)
        }
        println("[JarEmbedChain] result=${result != null}")
        println("[JarEmbedChain] sql=${result?.sql}")
        println("[JarEmbedChain] tableName=${result?.tableName}")
        org.junit.Assert.assertNotNull("语料 DAO 形态应可预览", result)
        org.junit.Assert.assertTrue(
            "表名应为 @Table 真名: ${result?.sql}",
            result!!.sql.contains("wms_inventory_jar_demo"),
        )
        org.junit.Assert.assertTrue(
            "嵌入列应带 component-path 前缀: ${result.sql}",
            result.sql.contains("qty_base_qty") && result.sql.contains("item_key_lot_info_received_date"),
        )
    }
}
