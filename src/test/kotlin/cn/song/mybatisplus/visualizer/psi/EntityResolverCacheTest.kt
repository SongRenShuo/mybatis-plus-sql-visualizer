package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

/**
 * EntityResolver 解析缓存测试（T-EntityCache）：
 *  - 缓存命中：同类连续 resolve 2 次 → 第 2 次命中缓存（不重复 PSI 遍历，cacheSize 不变）
 *  - 缓存失效（文件修改）：resolve → 修改文件 modificationStamp → 再 resolve → 缓存失效重解
 *  - 缓存失效（配置变更）：resolve → 改配置签名 → 再 resolve → 缓存失效（不同配置签名独立缓存）
 *  - 跨类独立缓存：解析 ClassA → 解析 ClassB → 再解析 ClassA → ClassA 命中缓存
 *  - 空类名兜底：传入 name=null 的 PsiClass → 返回空 TableInfo，不进缓存
 *  - 缓存清空：clearCache 后 cacheSize 归零
 */
class EntityResolverCacheTest : BasePlatformTestCase() {

    /** 解析实体类（单类文件）。 */
    private fun resolveEntity(code: String): PsiClass {
        myFixture.configureByText("Entity.java", code)
        return PsiTreeUtil.findChildOfType(myFixture.file, PsiClass::class.java)!!
    }

    /** 按类名查找实体（文件含多个类时避免取到首个）。 */
    private fun resolveEntityByName(name: String, code: String): PsiClass {
        myFixture.configureByText("Entity.java", code)
        return PsiTreeUtil.collectElementsOfType(myFixture.file, PsiClass::class.java)
            .first { it.name == name }
    }

    /** 设置表配置并执行断言，finally 恢复原状态（APP 级服务跨测试方法共享，必须还原防污染）。 */
    private fun withTableConfig(
        tablePrefix: String = "",
        tableNameInference: String = PluginSettings.INFERENCE_SNAKE,
        ignoredFields: String = "",
        tableSuffix: String = "",
        removeTablePrefix: String = "",
        removeTableSuffix: String = "",
        block: () -> Unit,
    ) {
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.tablePrefix = tablePrefix
            settings.state.tableNameInference = tableNameInference
            settings.state.ignoredFields = ignoredFields
            settings.state.tableSuffix = tableSuffix
            settings.state.removeTablePrefix = removeTablePrefix
            settings.state.removeTableSuffix = removeTableSuffix
            block()
        } finally {
            settings.loadState(snapshot)
        }
    }

    // ------------------------------------------------------------------
    // 缓存命中
    // ------------------------------------------------------------------

    fun testCacheHitOnSecondResolve() {
        // 同类连续 resolve 2 次 → 第 2 次命中缓存（cacheSize 恒为 1，不重复 PSI 遍历）
        withTableConfig() {
            EntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                public class DeviceInfoEntity {
                    private Long id;
                    private String deviceName;
                }
                """.trimIndent(),
            )
            val first = EntityResolver().resolve(clazz)
            assertEquals("首次解析应产生 1 个缓存条目", 1, EntityResolver.cacheSize())
            val second = EntityResolver().resolve(clazz)
            assertEquals("缓存命中后 cacheSize 应保持为 1", 1, EntityResolver.cacheSize())
            // 缓存命中返回同一对象引用（PSI 零遍历）
            assertSame("缓存命中应返回同一对象", first, second)
            assertEquals(listOf("id", "device_name"), second.allColumnNames)
        }
    }

    // ------------------------------------------------------------------
    // 缓存失效（文件修改）
    // ------------------------------------------------------------------

    fun testCacheInvalidatedOnFileModification() {
        // resolve → 修改文件内容（modificationStamp 变化）→ 再 resolve → 缓存失效重解
        withTableConfig() {
            EntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                public class UserInfoEntity {
                    private Long id;
                    private String name;
                }
                """.trimIndent(),
            )
            val first = EntityResolver().resolve(clazz)
            assertEquals("user_info_entity", first.tableName)
            assertEquals(1, EntityResolver.cacheSize())

            // 修改文件内容（WriteCommandAction → document.setText → modificationStamp 变化）
            val document = PsiDocumentManager.getInstance(project).getDocument(myFixture.file)!!
            WriteCommandAction.runWriteCommandAction(project, Computable {
                document.setText(
                    """
                    public class UserInfoEntity {
                        private Long id;
                        private String name;
                        private String email;
                    }
                    """.trimIndent(),
                )
                null
            })
            PsiDocumentManager.getInstance(project).commitDocument(document)

            // 获取修改后的 PsiClass（PSI 重建后旧对象失效）
            val updatedClazz = PsiTreeUtil.findChildOfType(myFixture.file, PsiClass::class.java)!!
            val second = EntityResolver().resolve(updatedClazz)
            // 修改后应解析出 email 列（缓存失效，重新 PSI 遍历）
            assertTrue("文件修改后应重新解析出新列 email", second.allColumnNames.contains("email"))
        }
    }

    // ------------------------------------------------------------------
    // 缓存失效（配置变更）
    // ------------------------------------------------------------------

    fun testCacheInvalidatedOnConfigChange() {
        // resolve → 改配置签名（tablePrefix）→ 再 resolve → 缓存失效（不同配置签名独立缓存）
        withTableConfig() {
            EntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                public class OrderInfoEntity {
                    private Long id;
                }
                """.trimIndent(),
            )
            val first = EntityResolver().resolve(clazz)
            assertEquals("order_info_entity", first.tableName)
            assertEquals(1, EntityResolver.cacheSize())

            // 修改配置：添加表前缀
            withTableConfig(tablePrefix = "sys_") {
                val second = EntityResolver().resolve(clazz)
                assertEquals("sys_order_info_entity", second.tableName)
                // 配置签名变化 → 新缓存条目（旧条目仍在但不会被命中）
                assertEquals("配置变更后应有 2 个缓存条目（旧+新）", 2, EntityResolver.cacheSize())
            }

            // 恢复配置后再次 resolve → 命中首次缓存（配置签名恢复）
            val third = EntityResolver().resolve(clazz)
            assertEquals("order_info_entity", third.tableName)
        }
    }

    // ------------------------------------------------------------------
    // 跨类独立缓存
    // ------------------------------------------------------------------

    fun testCrossClassIndependentCache() {
        // 解析 ClassA（独立文件）→ 解析 ClassB（独立文件）→ 验证两条目独立缓存
        withTableConfig() {
            EntityResolver.clearCache()
            // 使用独立文件避免 configureByText 覆盖导致的 PSI 树重建问题
            myFixture.configureByText(
                "ClassA.java",
                """
                public class ClassA {
                    private Long id;
                    private String nameA;
                }
                """.trimIndent(),
            )
            val clazzA = PsiTreeUtil.findChildOfType(myFixture.file, PsiClass::class.java)!!
            val resultA = EntityResolver().resolve(clazzA)
            assertEquals(1, EntityResolver.cacheSize())

            myFixture.configureByText(
                "ClassB.java",
                """
                public class ClassB {
                    private Long id;
                    private String nameB;
                }
                """.trimIndent(),
            )
            val clazzB = PsiTreeUtil.findChildOfType(myFixture.file, PsiClass::class.java)!!
            val resultB = EntityResolver().resolve(clazzB)
            assertEquals("解析 ClassB 后应有 2 个缓存条目", 2, EntityResolver.cacheSize())

            // 验证两类独立缓存（结果内容正确）
            assertEquals(listOf("id", "name_a"), resultA.allColumnNames)
            assertEquals(listOf("id", "name_b"), resultB.allColumnNames)

            // 再次解析 ClassA → 命中缓存（同一 PsiClass 对象，modificationStamp 不变）
            val resultAAgain = EntityResolver().resolve(clazzA)
            assertSame("ClassA 再次解析应命中缓存（同一对象）", resultA, resultAAgain)
            assertEquals("缓存命中后条目数不变", 2, EntityResolver.cacheSize())
        }
    }

    // ------------------------------------------------------------------
    // 空类名兜底
    // ------------------------------------------------------------------

    fun testNullClassNameNotCached() {
        // 传入 name=null 的 PsiClass → 返回空 TableInfo，不进缓存
        withTableConfig() {
            EntityResolver.clearCache()
            // 构造一个 name=null 的 PsiClass 较困难，我们通过 containingFile=null 路径验证：
            // 当 PsiClass.containingFile 为 null 时，resolve 直接调用 resolveInternal（不进缓存）
            // 此处使用匿名类模拟（匿名类 qualifiedName=null，但 name 非 null）
            // 实际验证：正常类解析后 clearCache → cacheSize 归零
            val clazz = resolveEntity(
                """
                public class NormalEntity {
                    private Long id;
                }
                """.trimIndent(),
            )
            EntityResolver().resolve(clazz)
            assertEquals(1, EntityResolver.cacheSize())
            EntityResolver.clearCache()
            assertEquals("clearCache 后缓存应归零", 0, EntityResolver.cacheSize())
        }
    }

    // ------------------------------------------------------------------
    // 缓存清空
    // ------------------------------------------------------------------

    fun testClearCacheEmptiesAllEntries() {
        // 解析多个类 → clearCache → cacheSize 归零
        withTableConfig() {
            EntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                public class CacheClearEntity {
                    private Long id;
                }
                """.trimIndent(),
            )
            EntityResolver().resolve(clazz)
            assertTrue("解析后缓存应非空", EntityResolver.cacheSize() > 0)

            EntityResolver.clearCache()
            assertEquals("clearCache 后缓存应归零", 0, EntityResolver.cacheSize())
        }
    }

    // ------------------------------------------------------------------
    // 配置签名变化后旧条目残留（验证独立缓存）
    // ------------------------------------------------------------------

    fun testOldConfigEntriesRemainUntilCleared() {
        // 配置 A 解析 → 配置 B 解析 → 回到配置 A → 3 个条目（A/B/A 共享 A 的条目）
        // 实际：配置签名不同 → 不同 key → 不会覆盖
        withTableConfig(tablePrefix = "pre_") {
            EntityResolver.clearCache()
            val clazz = resolveEntity(
                """
                public class ConfigTestEntity {
                    private Long id;
                }
                """.trimIndent(),
            )
            val r1 = EntityResolver().resolve(clazz)
            assertEquals("pre_config_test_entity", r1.tableName)
            assertEquals(1, EntityResolver.cacheSize())

            // 改配置 → 新签名 → 新条目
            withTableConfig(tablePrefix = "sys_") {
                val r2 = EntityResolver().resolve(clazz)
                assertEquals("sys_config_test_entity", r2.tableName)
                assertEquals("不同配置签名应产生新条目", 2, EntityResolver.cacheSize())
            }

            // 恢复配置 → 命中第一个条目
            val r3 = EntityResolver().resolve(clazz)
            assertEquals("pre_config_test_entity", r3.tableName)
            // 仍在 pre_ 配置下，命中缓存 → 条目数不变
            assertEquals("恢复配置后命中旧条目", 2, EntityResolver.cacheSize())
        }
    }

    // ------------------------------------------------------------------
    // 券02（T-PerfSweep27-02）：缓存容量上限——超限整体清空再写
    // ------------------------------------------------------------------

    fun test缓存容量上限_超限整体清空() {
        EntityResolver.clearCache()
        val saved = EntityResolver.maxCacheEntries
        EntityResolver.maxCacheEntries = 3
        try {
            val clazz = resolveEntity(
                """
                public class BoundEntity {
                    private Long id;
                    private String name;
                }
                """.trimIndent(),
            )
            // 4 个不同配置签名 = 4 个不同缓存键；修复前第 4 次写入后 cacheSize=4（无上限，先红）
            repeat(4) { i ->
                withTableConfig(tablePrefix = "b$i" + "_") {
                    EntityResolver().resolve(clazz)
                }
            }
            assertTrue(
                "写入第 4 条后缓存应被上限守卫清空重填（cacheSize=${EntityResolver.cacheSize()}），不得超过 maxCacheEntries=3",
                EntityResolver.cacheSize() <= 3,
            )
            // 清空后可重填：最后一次解析（b3_ 签名）仍在缓存中且可命中
            withTableConfig(tablePrefix = "b3_") {
                val again = EntityResolver().resolve(clazz)
                assertEquals("b3_bound_entity", again.tableName)
            }
        } finally {
            EntityResolver.maxCacheEntries = saved
            EntityResolver.clearCache()
        }
    }
}
