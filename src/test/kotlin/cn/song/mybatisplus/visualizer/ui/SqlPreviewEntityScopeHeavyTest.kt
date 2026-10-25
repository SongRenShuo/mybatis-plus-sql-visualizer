package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.psi.EntityClassResolver
import cn.song.mybatisplus.visualizer.psi.jpa.EntityAliasSweep
import cn.song.mybatisplus.visualizer.psi.jpa.HbmXmlEntityResolver
import cn.song.mybatisplus.visualizer.psi.jpa.NamedQueryLocator
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
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import java.io.File
import java.nio.file.Files

/** Actual production preview entry points with separate disk-backed modules, not source-string guards. */
class SqlPreviewEntityScopeHeavyTest : HeavyPlatformTestCase() {
    private val roots = mutableMapOf<Module, VirtualFile>()
    private val dirs = mutableListOf<File>()

    override fun setUp() {
        super.setUp()
        EntityClassResolver.ambiguitySinkForTest = { _, _, _, _ -> }
    }

    override fun tearDown() {
        try {
            EntityClassResolver.ambiguitySinkForTest = null
            HbmXmlEntityResolver.clearCache()
            roots.clear()
            super.tearDown()
        } finally {
            dirs.forEach { it.deleteRecursively() }
        }
    }

    private fun file(module: Module, path: String, text: String): com.intellij.psi.PsiFile {
        val root = roots.getOrPut(module) {
            val dir = Files.createTempDirectory("preview-entity-scope-").toFile().also(dirs::add)
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!.also { PsiTestUtil.addSourceRoot(module, it) }
        }
        return WriteCommandAction.runWriteCommandAction(project, Computable {
            val parent = path.substringBeforeLast('/', "")
            val directory = if (parent.isEmpty()) root else VfsUtil.createDirectoryIfMissing(root, parent)!!
            val vf = directory.createChildData(this, path.substringAfterLast('/'))
            VfsUtil.saveText(vf, text)
            PsiManager.getInstance(project).findFile(vf)!!
        })
    }

    private fun source(module: Module, name: String, body: String = "", annotations: String = ""): PsiClass =
        (file(module, name.replace('.', '/') + ".java",
            "package ${name.substringBeforeLast('.')}; $annotations public class ${name.substringAfterLast('.')} { $body }") as PsiJavaFile).classes.single()

    private fun entity(module: Module, name: String, table: String, alias: String? = null): PsiClass =
        source(module, name, "@javax.persistence.Id long id; @javax.persistence.Column(name=\"item_code\") String itemCode;",
            "@javax.persistence.Entity${alias?.let { "(name=\"$it\")" }.orEmpty()} @javax.persistence.Table(name=\"$table\")")

    private fun ready() = IndexingTestUtil.waitUntilIndexesAreReady(project)
    private fun service() = SqlPreviewService(project)
    private fun call(clazz: PsiClass, name: String) = PsiTreeUtil.collectElementsOfType(clazz, PsiMethodCallExpression::class.java)
        .single { it.methodExpression.referenceName == name }

    fun testCustomDaoProductionPreviewUsesQueryModule() {
        val other = createModule("other")
        entity(myModule, "a.Inventory", "inventory_a")
        entity(other, "b.Inventory", "inventory_b")
        val host = source(myModule, "host.Query", "Object commonDao; void run() { commonDao.findByQuery(\"from Inventory i where i.itemCode = :code\"); }")
        ready()
        val preview = service().previewCustomDaoHql(call(host, "findByQuery"))
        assertNotNull(preview)
        assertEquals("inventory_a", preview!!.tableName)
        assertTrue(preview.sql, preview.sql.contains("item_code"))
        assertFalse(preview.sql, preview.sql.contains("inventory_b"))
    }

    fun testCustomDaoProductionPreviewKeepsHostContextForRealLibraryEntity() {
        val other = createModule("other")
        entity(other, "unrelated.Inventory", "wrong_source")
        val host = source(myModule, "host.Query", "Object commonDao; void run() { commonDao.findByQuery(\"from Inventory i\"); }")
        val dir = Files.createTempDirectory("preview-entity-library-").toFile().also(dirs::add)
        val classes = File(dir, "classes").apply { mkdirs() }
        val annotation = File(dir, "javax/persistence/Table.java").apply {
            parentFile.mkdirs()
            writeText("package javax.persistence; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface Table { String name(); }")
        }
        val javaSource = File(dir, "lib/Inventory.java").apply {
            parentFile.mkdirs()
            writeText("package lib; @javax.persistence.Table(name=\"jar_inventory\") public class Inventory { public long id; public String itemCode; }")
        }
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "javac.exe" else "javac"
        val javac = listOfNotNull(System.getenv("JAVA_HOME"), System.getProperty("java.home"), "D:/HuanJing/Java/jdk1.8.0_181")
            .map { File(it, "bin/$executable") }.firstOrNull(File::isFile)
        assertNotNull("A real JDK is required", javac)
        val log = File(dir, "javac.log")
        val process = ProcessBuilder(javac!!.absolutePath, "-source", "8", "-target", "8", "-d", classes.absolutePath,
            annotation.absolutePath, javaSource.absolutePath).redirectErrorStream(true).redirectOutput(log).start()
        assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(log.readText(), 0, process.exitValue())
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(classes)!!
        root.refresh(false, true)
        ModuleRootModificationUtil.addModuleLibrary(myModule, root.url)
        ready()
        val resolved = EntityClassResolver.resolveClassByName(project, "Inventory", host)
        assertEquals("class", resolved?.containingFile?.virtualFile?.extension)
        assertEquals("jar_inventory", service().previewCustomDaoHql(call(host, "findByQuery"))?.tableName)
    }

    fun testHqlProductionPreviewUsesQueryModule() {
        val other = createModule("other")
        entity(myModule, "a.Inventory", "inventory_a")
        entity(other, "b.Inventory", "inventory_b")
        val host = source(myModule, "host.Query", "Object session; void run() { session.createQuery(\"from Inventory i\"); }")
        ready()
        val preview = service().previewHql(call(host, "createQuery"))
        assertNotNull(preview)
        assertEquals("inventory_a", preview!!.tableName)
    }

    fun testExplicitResultClassCannotOverrideDifferentQueryRoot() {
        val resultClass = entity(myModule, "model.ResultDto", "wrong_result")
        entity(myModule, "model.Inventory", "inventory")
        val host = source(myModule, "host.Query")
        ready()
        assertEquals("inventory", SqlPreviewServiceEnhancementsKt.resolveHqlTable(service(), resultClass, "ResultDto", "from Inventory", host)?.tableName)
    }

    fun testNamedQueryAnnotationHostIsNotAssumedToBeRootEntity() {
        entity(myModule, "model.Inventory", "inventory")
        val owner = source(myModule, "model.Owner", "long id;",
            "@javax.persistence.Entity @javax.persistence.Table(name=\"wrong_owner\") @javax.persistence.NamedQuery(name=\"Owner.all\", query=\"from Inventory\")")
        ready()
        val annotation = owner.modifierList!!.annotations.single { it.text.contains("NamedQuery") }
        assertEquals("inventory", service().previewNamedQuery(annotation)?.tableName)
    }

    fun testNamedQueryLookupIsScopedAndSameLevelAmbiguityIsUnresolved() {
        val other = createModule("other")
        val a = source(myModule, "a.Inventory", annotations = "@javax.persistence.NamedQuery(name=\"Inventory.all\", query=\"from A\")")
        val b = source(other, "b.Inventory", annotations = "@javax.persistence.NamedQuery(name=\"Inventory.all\", query=\"from B\")")
        ready()
        assertEquals("from A", NamedQueryLocator.findByQueryName(project, "Inventory.all", a)?.query)
        assertEquals("from B", NamedQueryLocator.findByQueryName(project, "Inventory.all", b)?.query)
        assertNull(NamedQueryLocator.findByQueryName(project, "Inventory.all"))
    }

    fun testAliasCacheAlternatesModulesAndKeepsCaseSensitiveKeys() {
        val other = createModule("other")
        val a = entity(myModule, "a.OwnerA", "a_table", "Stock")
        val b = entity(other, "b.OwnerB", "b_table", "Stock")
        ready()
        repeat(3) {
            assertEquals(a, EntityAliasSweep.resolve(project, "Stock", a).psiClass())
            assertEquals(b, EntityAliasSweep.resolve(project, "Stock", b).psiClass())
            assertNull(EntityAliasSweep.resolve(project, "stock", a).psiClass())
        }
    }

    fun testAliasNegativeCacheInvalidatesWhenDependencyChanges() {
        val other = createModule("other")
        val host = source(myModule, "host.Query")
        val alias = entity(other, "b.Owner", "stock", "Stock")
        ready()
        assertNull(EntityAliasSweep.resolve(project, "Stock", host).psiClass())
        ModuleRootModificationUtil.addDependency(myModule, other)
        ready()
        assertEquals(alias, EntityAliasSweep.resolve(project, "Stock", host).psiClass())
    }

    fun testAliasCacheInvalidatesAfterPsiEditAndDuplicatesFailClosed() {
        val host = source(myModule, "host.Query")
        val a = entity(myModule, "a.Owner", "stock", "Stock")
        ready()
        assertEquals(a, EntityAliasSweep.resolve(project, "Stock", host).psiClass())
        entity(myModule, "b.Owner", "other", "Stock")
        ready()
        val result = EntityAliasSweep.resolve(project, "Stock", host)
        assertNull(result.psiClass())
        assertTrue(result.ambiguous)
        assertNull(SqlPreviewServiceEnhancementsKt.resolveHqlTable(service(), null, "Stock", "from Stock", host))
    }

    fun testExplicitInvisibleClassCannotBypassHostScope() {
        val other = createModule("other")
        val wrong = entity(other, "wrong.Inventory", "wrong_inventory")
        // T-ShadowEntityResolve W3 契约更新：窄域**零候选**时全项目唯一命中可解析（见
        // ShadowEntityResolveTest#testEntityOnlyInDownstreamModuleResolvesFromWorkspaceHost）。
        // 因此「显式不可见类不得旁路」要在**窄域有候选**的形态下钉：宿主可见的 local.Inventory
        // 先被解析出来，不可见的 wrong.Inventory 不得顶掉它。
        entity(myModule, "local.Inventory", "local_inventory")
        val host = source(myModule, "host.Query")
        ready()
        val table = SqlPreviewServiceEnhancementsKt.resolveHqlTable(service(), wrong, "Inventory", "from Inventory", host)!!
        assertEquals("local_inventory", table.tableName)
    }

    fun testExplicitSameNameClassCannotBypassVisibleAmbiguity() {
        val first = entity(myModule, "first.Inventory", "first_inventory")
        entity(myModule, "second.Inventory", "second_inventory")
        val host = source(myModule, "host.Query")
        ready()
        assertNull(SqlPreviewServiceEnhancementsKt.resolveHqlTable(service(), first, "Inventory", "from Inventory", host))
    }

    fun testExplicitClassCannotReplaceUniqueVisibleCandidate() {
        val other = createModule("other")
        val wrong = entity(other, "wrong.Inventory", "wrong_inventory")
        entity(myModule, "visible.Inventory", "visible_inventory")
        val host = source(myModule, "host.Query")
        ready()
        assertEquals("visible_inventory", SqlPreviewServiceEnhancementsKt.resolveHqlTable(service(), wrong, "Inventory", "from Inventory", host)?.tableName)
    }

    fun testProductionPreviewIgnoresScalarSubqueryWhenResolvingRoot() {
        entity(myModule, "model.Inventory", "inventory")
        entity(myModule, "model.Detail", "detail")
        val host = source(myModule, "host.Query", "Object commonDao; void run() { commonDao.findByQuery(\"select (select max(d.id) from Detail d) from Inventory i\"); }")
        ready()
        assertEquals("inventory", service().previewCustomDaoHql(call(host, "findByQuery"))?.tableName)
    }

    fun testProductionPreviewUsesQualifiedRootNotPackageNamedEntity() {
        entity(myModule, "model.Inventory", "inventory")
        entity(myModule, "decoy.model", "wrong_package")
        val host = source(myModule, "host.Query", "Object commonDao; void run() { commonDao.findByQuery(\"from model.Inventory i\"); }")
        ready()
        assertEquals("inventory", service().previewCustomDaoHql(call(host, "findByQuery"))?.tableName)
    }

    fun testFindAvailableJarInventoryMapsAllThreeEmbeddedPathsThroughProductionPreview() {
        val settings = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state
        val oldNaming = settings.embeddedColumnNaming
        settings.embeddedColumnNaming = cn.song.mybatisplus.visualizer.config.PluginSettings.EMBEDDED_NAMING_AUTO
        try {
            val other = createModule("embedded-demo")
            source(other, "decoy.WmsInventoryEntity", "long id; @javax.persistence.Column(name=\"move_tool_pallet_no\") String moveTool;",
                "@javax.persistence.Entity @javax.persistence.Table(name=\"embed_demo\")")
            file(myModule, "application.properties", "spring.jpa.properties.hibernate.implicit_naming_strategy=component-path")
            val host = source(myModule, "host.JarQueryService", """
                Object wmsJarCommonDao;
                Object findAvailableJarInventory(String warehouseId, String inventoryStatus) {
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
            """.trimIndent())
            val dir = Files.createTempDirectory("preview-embedded-classlib-").toFile().also(dirs::add)
            val classes = File(dir, "classes").apply { mkdirs() }
            val sources = linkedMapOf<String, String>()
            for (annotation in listOf("Entity", "Embeddable", "Embedded", "Id")) {
                sources["javax/persistence/$annotation.java"] = "package javax.persistence; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface $annotation {}"
            }
            for (annotation in listOf("Table", "Column")) {
                sources["javax/persistence/$annotation.java"] = "package javax.persistence; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface $annotation { String name(); }"
            }
            sources["lib/WmsInventoryEntity.java"] = """
                package lib;
                @javax.persistence.Entity @javax.persistence.Table(name="jar_demo")
                public class WmsInventoryEntity {
                    @javax.persistence.Id public long id;
                    @javax.persistence.Column(name="warehouse_id") public String warehouseId;
                    @javax.persistence.Embedded public WmsInventoryQty qty;
                    @javax.persistence.Embedded public WmsSku sku;
                    @javax.persistence.Embedded public WmsItemKey itemKey;
                }
            """.trimIndent()
            sources["lib/WmsInventoryQty.java"] = "package lib; @javax.persistence.Embeddable public class WmsInventoryQty { public Long baseQty; }"
            sources["lib/WmsSku.java"] = "package lib; @javax.persistence.Embeddable public class WmsSku { public String inventoryStatus; }"
            sources["lib/WmsItemKey.java"] = """
                package lib;
                @javax.persistence.Embeddable public class WmsItemKey {
                    @javax.persistence.Embedded public LotInfo lotInfo;
                    @javax.persistence.Embeddable public static class LotInfo { public java.time.LocalDateTime receivedDate; }
                }
            """.trimIndent()
            val paths = sources.map { (path, text) -> File(dir, path).apply { parentFile.mkdirs(); writeText(text) }.absolutePath }
            val executable = if (System.getProperty("os.name").startsWith("Windows")) "javac.exe" else "javac"
            val javac = listOfNotNull(System.getenv("JAVA_HOME"), System.getProperty("java.home"), "D:/HuanJing/Java/jdk1.8.0_181")
                .map { File(it, "bin/$executable") }.firstOrNull(File::isFile)
            assertNotNull("Real external javac required", javac)
            val log = File(dir, "javac.log")
            val process = ProcessBuilder(listOf(javac!!.absolutePath, "-source", "8", "-target", "8", "-d", classes.absolutePath) + paths)
                .redirectErrorStream(true).redirectOutput(log).start()
            assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(log.readText(), 0, process.exitValue())
            val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(classes)!!
            root.refresh(false, true)
            ModuleRootModificationUtil.addModuleLibrary(myModule, root.url)
            ready()
            val binary = EntityClassResolver.resolveClassByName(project, "WmsInventoryEntity", host)
            assertEquals("class", binary?.containingFile?.virtualFile?.extension)
            assertEquals("lib.WmsInventoryEntity", binary?.qualifiedName)
            val preview = service().previewCustomDaoHql(call(host, "findByQueryMaxNum"))
            assertNotNull(preview)
            val sql = preview!!.sql
            assertEquals("jar_demo", preview.tableName)
            for (column in listOf("qty_base_qty", "sku_inventory_status", "item_key_lot_info_received_date")) assertTrue(sql, sql.contains(column))
            for (forbidden in listOf("embed_demo", "move_tool_pallet_no", "inv.qty.baseQty", "inv.sku.inventoryStatus", "inv.itemKey.lotInfo.receivedDate")) assertFalse(sql, sql.contains(forbidden))
        } finally {
            settings.embeddedColumnNaming = oldNaming
        }
    }

    private fun hbm(module: Module, filename: String, table: String): XmlFile = file(module, filename,
        "<hibernate-mapping><class name=\"legacy.Inventory\" table=\"$table\"><id name=\"id\" column=\"item_id\"/></class><query name=\"Unrelated.all\"><![CDATA[from Inventory]]></query></hibernate-mapping>") as XmlFile

    fun testHbmRootAndNamedQueryCachesAreModuleScoped() {
        val other = createModule("other")
        val a = hbm(myModule, "a.hbm.xml", "inventory_a")
        val b = hbm(other, "b.hbm.xml", "inventory_b")
        ready()
        repeat(3) {
            assertEquals("inventory_a", HbmXmlEntityResolver.resolve("Inventory", project, a)?.tableName)
            assertEquals("inventory_b", HbmXmlEntityResolver.resolve("Inventory", project, b)?.tableName)
            assertNotNull(HbmXmlEntityResolver.findNamedQuery("Unrelated.all", project, a))
            assertNotNull(HbmXmlEntityResolver.findNamedQuery("Unrelated.all", project, b))
        }
        assertNull(HbmXmlEntityResolver.resolve("Inventory", project))
        assertNull(HbmXmlEntityResolver.findNamedQuery("Unrelated.all", project))
        val tag = a.rootTag!!.findFirstSubTag("query")!!
        assertEquals("inventory_a", service().previewHbmNamedQueryTag(project, tag)?.tableName)
    }

    fun testHbmNegativeCacheInvalidatesAfterNewMapping() {
        val host = source(myModule, "host.Query")
        ready()
        assertNull(HbmXmlEntityResolver.resolve("Inventory", project, host))
        assertNull(HbmXmlEntityResolver.findNamedQuery("Unrelated.all", project, host))
        hbm(myModule, "new.hbm.xml", "inventory")
        ready()
        assertEquals("inventory", HbmXmlEntityResolver.resolve("Inventory", project, host)?.tableName)
        assertNotNull(HbmXmlEntityResolver.findNamedQuery("Unrelated.all", project, host))
    }
}
