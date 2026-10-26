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
 * T-EmbeddedIdentify 回归护栏：**JAR/依赖模块实体的 @Embedded 全链预览**。
 *
 * 两个互补形态（外部报障「JAR 里的 @Embedded 不识别」20261026 收口后固化）：
 *  - `test探针_语料DAO全链预览`——**JAR 态**：挂载 demo 仓产出的 jpa-jar-model-lib-1.0.0.jar
 *    为库根（真实二进制类，锚点无模块根 → 工程级信号），语料 DAO 形态走 previewCustomDaoHql 全链；
 *  - `test源码形态lib模块走宿主级联端到端`——**workspace 态**：实体源码在 lib 模块（自带普通
 *    resources、无命名配置），命名判定走「实体模块 miss → 宿主模块」级联（T-EmbeddedHostCascade）。
 *
 * 前置缺陷背景：修复前该形态嵌入列丢 component-path 前缀（`base_qty` ≠ 真库 `qty_base_qty`），
 * 且既有测试锚点全在夹具源码/工程级扫描，覆盖不到本类两形态（复盘见排查计划 §七）。
 * Heavy 桶（全量分片执行）；夹具 jar 缺失时 JAR 态用例显式绿跳（与 JpaJarClassFileAnnotationTest 同惯例）。
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

        /** 源码形态夹具：与 jar 语料同字段（三嵌入、内层零 @Column），javax 短名注解（classpath 无 JPA API 同形态）。 */
        private val SOURCE_ENTITY = """
            package cn.song.jarlib.entity;

            import javax.persistence.Embeddable;
            import javax.persistence.Embedded;
            import javax.persistence.Entity;
            import javax.persistence.Id;
            import javax.persistence.Table;
            import java.io.Serializable;
            import java.time.LocalDateTime;

            @Embeddable
            class WmsInventoryQty implements Serializable {
                private Long baseQty;
            }

            @Embeddable
            class WmsSku implements Serializable {
                private String inventoryStatus;
            }

            @Embeddable
            class WmsItemKey implements Serializable {
                @Embedded
                private LotInfo lotInfo;

                @Embeddable
                static class LotInfo implements Serializable {
                    private LocalDateTime receivedDate;
                }
            }

            @Entity
            @Table(name = "wms_inventory_jar_demo")
            class WmsInventoryEntity implements Serializable {
                private Long id;
                private String warehouseId;
                @Embedded
                private WmsInventoryQty qty;
                @Embedded
                private WmsSku sku;
                @Embedded
                private WmsItemKey itemKey;
            }
        """.trimIndent()
    }

    /** workspace 形态复刻（用户实测档，T-EmbeddedHostCascade）：实体**源码**在 lib 模块（自带普通
     *  resources、无命名配置）、DAO 在宿主模块（component-path）——命名判定须「实体模块 miss →
     *  宿主模块」级联，列名带前缀。与 JAR 态 E2E（test探针_语料DAO全链预览）互补成对。 */
    fun test源码形态lib模块走宿主级联端到端() {
        val base = Files.createTempDirectory("jar-embed-src-").toFile()
        File(base, "lib-module/pom.xml").apply { parentFile.mkdirs(); writeText("<project/>") }
        File(base, "lib-module/src/main/resources/application.yml").apply {
            parentFile.mkdirs()
            writeText("server:\n  port: 0\n")
        }
        File(base, "lib-module/src/main/java/cn/song/jarlib/entity/WmsInventoryEntity.java").apply {
            parentFile.mkdirs()
            writeText(SOURCE_ENTITY)
        }
        val libRootVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(base, "lib-module"))!!
        val hostRoot = File(base, "host-module")
        File(hostRoot, "pom.xml").apply { parentFile.mkdirs(); writeText("<project/>") }
        File(hostRoot, "src/main/resources/application.yml").apply {
            parentFile.mkdirs()
            writeText(
                "spring:\n  jpa:\n    hibernate:\n      naming:\n        implicit_naming_strategy: component-path\n",
            )
        }
        val hostRootVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(hostRoot)!!
        com.intellij.testFramework.PsiTestUtil.addSourceRoot(myModule, libRootVf)
        com.intellij.testFramework.PsiTestUtil.addSourceRoot(myModule, hostRootVf)
        var callFileVf: com.intellij.openapi.vfs.VirtualFile? = null
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            val dir = VfsUtil.createDirectoryIfMissing(hostRootVf, "src/main/java")!!
            callFileVf = dir.createChildData(this, "WmsJarQueryService.java")
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
                        return wmsJarCommonDao.findByQueryMaxNum(hql,
                                new String[]{"warehouseId", "inventoryStatus"},
                                new Object[]{warehouseId, inventoryStatus}, 0, 10);
                    }
                }
                """.trimIndent(),
            )
        }
        com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
        val callFile = PsiManager.getInstance(project).findFile(callFileVf!!)
                as com.intellij.psi.PsiJavaFile ?: error("DAO 调用点文件应可取 PSI")
        val psiCall = com.intellij.psi.util.PsiTreeUtil.collectElementsOfType(
            callFile,
            com.intellij.psi.PsiMethodCallExpression::class.java,
        ).firstOrNull { it.methodExpression.referenceName == "findByQueryMaxNum" }
        org.junit.Assert.assertNotNull("夹具内应有 findByQueryMaxNum 调用", psiCall)
        val service = project.getService(cn.song.mybatisplus.visualizer.ui.SqlPreviewService::class.java)
        val result = com.intellij.openapi.application.ReadAction.compute<
            cn.song.mybatisplus.visualizer.model.PreviewResult?, RuntimeException> {
            service.previewCustomDaoHql(psiCall!!)
        }
        org.junit.Assert.assertNotNull("lib 源码 + 宿主 DAO 形态应可预览", result)
        org.junit.Assert.assertTrue(
            "表名应为真名: ${result?.sql}",
            result!!.sql.contains("wms_inventory_jar_demo"),
        )
        org.junit.Assert.assertTrue("嵌入列应带前缀: ${result.sql}", result.sql.contains("qty_base_qty"))
        org.junit.Assert.assertTrue("三层嵌套应带前缀: ${result.sql}", result.sql.contains("item_key_lot_info_received_date"))
        org.junit.Assert.assertFalse("不应出现丢前缀列: ${result.sql}", result.sql.contains("inv.base_qty"))
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
