package cn.song.mybatisplus.visualizer.heavy

import cn.song.mybatisplus.visualizer.psi.EntityClassFinder
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * 实体类查找 Heavy 测试（T-M36.2：SQL 反向转换 Lambda 模式的实体上下文解析）。
 *
 * 按表名在**索引**中检索实体类（PsiShortNamesCache）——跨文件检索依赖真实索引，
 * Light 环境不可用（M27 实证：Light 跨文件 resolve/FileBasedIndex 不可用），故用
 * [HeavyPlatformTestCase]（磁盘项目 + 索引；文件经 createTestProjectStructure 落磁盘）。
 */
class EntityClassFinderHeavyTest : HeavyPlatformTestCase() {

    /** 在项目 src 根下创建 Java 源文件（磁盘 + 索引），返回 PsiClass。 */
    private fun addClassFile(name: String, content: String): PsiClass {
        return WriteCommandAction.runWriteCommandAction(project, Computable {
            val root = createTestProjectStructure()
            val src = root.findChild("src") ?: root.createChildDirectory(this, "src")
            val vf = src.createChildData(this, name)
            VfsUtil.saveText(vf, content.trimIndent())
            PsiManager.getInstance(project).findFile(vf)!!
        }).let { file ->
            PsiTreeUtil.findChildOfType(file, PsiClass::class.java)!!
        }
    }

    fun testTableNameAnnotationMatch() {
        addClassFile(
            "CarDeviceInfoEntity.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("car_device_info")
            public class CarDeviceInfoEntity {
                private Long id;
                private String name;
            }
            """,
        )
        val found = EntityClassFinder(project).find("car_device_info")
        assertEquals(1, found.size)
        assertEquals("CarDeviceInfoEntity", found[0].name)
    }

    fun testClassNameInferenceMatchWithoutAnnotation() {
        // 无 @TableName：按命名策略推断（User → user）
        addClassFile(
            "User.java",
            """
            public class User {
                private Long id;
                private String name;
            }
            """,
        )
        val found = EntityClassFinder(project).find("user")
        assertEquals(1, found.size)
        assertEquals("User", found[0].name)
    }

    fun testEntitySuffixCandidate() {
        // 候选类名回退：CarDeviceInfo 不存在、CarDeviceInfoEntity 存在（@TableName 匹配）
        addClassFile(
            "CarDeviceInfoEntity.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("car_device_info")
            public class CarDeviceInfoEntity {
                private Long id;
            }
            """,
        )
        val found = EntityClassFinder(project).find("car_device_info")
        assertEquals(1, found.size)
        assertEquals("CarDeviceInfoEntity", found[0].name)
    }

    fun testTableNameMismatchExcluded() {
        // 同名类但 @TableName 指向别的表 → 排除（防误配）
        addClassFile(
            "CarDeviceInfo.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("car_device_log")
            public class CarDeviceInfo {
                private Long id;
            }
            """,
        )
        assertTrue(EntityClassFinder(project).find("car_device_info").isEmpty())
    }

    fun testVoDtoClassesExcluded() {
        // T-M36.3：VO/DTO 类即使表名匹配也被排除（命名惯例显式防御）
        addClassFile(
            "User.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("user")
            public class User {
                private Long id;
                private String name;
            }
            """,
        )
        addClassFile(
            "UserVO.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("user")
            public class UserVO {
                private String name;
            }
            """,
        )
        addClassFile(
            "UserDto.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("user")
            public class UserDto {
                private String name;
            }
            """,
        )
        val found = EntityClassFinder(project).find("user")
        assertEquals(1, found.size)
        assertEquals("User", found[0].name)
    }

    fun testNoMatchReturnsEmpty() {
        assertTrue(EntityClassFinder(project).find("not_exist_table").isEmpty())
    }

    // ------------------------------------------------------------------
    // @TableName 反查慢路径（类名与表名驼峰不一致时的兜底）
    // ------------------------------------------------------------------

    fun testTableNameAnnotationReverseLookup() {
        // 实体命名与表名驼峰不一致（AccountRoleRelEntity ↔ sys_account_role_rel）：
        // 类名推断候选 [SysAccountRoleRel, SysAccountRoleRelEntity] 无法命中 → @TableName 反查兜底
        addClassFile(
            "AccountRoleRelEntity.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("sys_account_role_rel")
            public class AccountRoleRelEntity {
                private Long roleId;
                private Long accountId;
            }
            """,
        )
        val found = EntityClassFinder(project).find("sys_account_role_rel")
        assertEquals(1, found.size)
        assertEquals("AccountRoleRelEntity", found[0].name)
    }

    fun testTableNameAnnotationReverseLookupIgnoreCase() {
        // 注解值大小写不同（SYS_ACCOUNT_ROLE_REL）仍命中（表名比较大小写不敏感）
        addClassFile(
            "RoleRel.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("SYS_ACCOUNT_ROLE_REL")
            public class RoleRel {
                private Long id;
            }
            """,
        )
        val found = EntityClassFinder(project).find("sys_account_role_rel")
        assertEquals(1, found.size)
        assertEquals("RoleRel", found[0].name)
    }

    fun testReverseLookupMultipleCandidates() {
        // 反查命中多个候选（类名与表名驼峰无关，不在启发式候选内 → 走全量反查收集全部）
        // 注意：AccountRoleRelEntity 会被 P1 启发式快路径提前命中（返回 1 个）——
        // 此处用 RoleRelA/B 验证全量反查的多候选语义
        addClassFile(
            "RoleRelA.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("sys_account_role_rel")
            public class RoleRelA {
                private Long id;
            }
            """,
        )
        addClassFile(
            "RoleRelB.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("sys_account_role_rel")
            public class RoleRelB {
                private Long id;
            }
            """,
        )
        val found = EntityClassFinder(project).find("sys_account_role_rel")
        assertEquals(2, found.size)
        assertEquals(listOf("RoleRelA", "RoleRelB"), found.map { it.name })
    }

    fun testReverseLookupVoDtoExcluded() {
        // 反查路径同样排除 VO/DTO 命名
        addClassFile(
            "RoleRel.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("sys_account_role_rel")
            public class RoleRel {
                private Long id;
            }
            """,
        )
        addClassFile(
            "RoleRelVO.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("sys_account_role_rel")
            public class RoleRelVO {
                private Long id;
            }
            """,
        )
        val found = EntityClassFinder(project).find("sys_account_role_rel")
        assertEquals(1, found.size)
        assertEquals("RoleRel", found[0].name)
    }

    fun testReverseLookupAnnotationMismatchExcluded() {
        // 反查命中判定依赖表名匹配——注解指向别的表的类不命中
        addClassFile(
            "RoleRel.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("other_table")
            public class RoleRel {
                private Long id;
            }
            """,
        )
        assertTrue(EntityClassFinder(project).find("sys_account_role_rel").isEmpty())
    }

    // ------------------------------------------------------------------
    // 反查缓存（P0：性能修复——无实体场景不再每次转换全量扫描）与启发式候选（P1）
    // ------------------------------------------------------------------

    fun testReverseLookupCachedAcrossFinds() {
        // 无匹配实体表名：首次 find 全量扫描 1 次建**全表索引**；之后无论查同表名还是**别的表名**
        // 都只走索引、零扫描（旧口径是「查一个表名扫一遍」，16804 类的工程每换一张表再吃 3.8s）
        val finder = EntityClassFinder(project)
        assertTrue(finder.find("not_exist_table_1").isEmpty())
        assertEquals("首次查找应触发 1 次全量扫描", 1, EntityClassFinder.scanCountForTest(project))
        assertTrue(finder.find("not_exist_table_1").isEmpty())
        assertEquals("同表名二次查找应命中缓存（扫描次数不变）", 1, EntityClassFinder.scanCountForTest(project))
        assertTrue(finder.find("not_exist_table_2").isEmpty())
        assertEquals("不同表名也应命中索引（不得再扫）", 1, EntityClassFinder.scanCountForTest(project))
    }

    fun testReverseLookupIndexSurvivesSourceEdit() {
        // 失效基准＝项目结构版本 + TTL：**改已有文件正文（打字）**不得作废索引。
        // 这条直接对着真机现象：预览链路自己会改 PSI（写 exec 文件、临时 PsiFile、对话框打字），
        // 旧口径挂 PsiModificationTracker ⇒ 每次打开预览都重扫全工程（实测 resolve=3994ms）。
        val entity = addClassFile(
            "RoleRel.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("sys_account_role_rel")
            public class RoleRel {}
            """,
        )
        assertTrue(EntityClassFinder(project).find("sys_account_role_rel").isNotEmpty())
        assertEquals(1, EntityClassFinder.scanCountForTest(project))

        // 模拟打字：改**已有文件**的正文（PSI 版本递增；项目结构不变 ⇒ 索引应存活）
        val vf = entity.containingFile.virtualFile
        val psiBefore = com.intellij.psi.util.PsiModificationTracker.getInstance(project).modificationCount
        WriteCommandAction.runWriteCommandAction(project, Computable {
            VfsUtil.saveText(
                vf,
                """
                import com.baomidou.mybatisplus.annotation.TableName;

                @TableName("sys_account_role_rel")
                public class RoleRel { private Long id; }
                """.trimIndent(),
            )
        })
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val psiAfter = com.intellij.psi.util.PsiModificationTracker.getInstance(project).modificationCount
        // 夹具有效性：编辑后 PSI 版本必须递增，否则本用例什么都没验（空绿）
        assertTrue("夹具有效性：编辑后 PSI 版本必须递增（$psiBefore → $psiAfter）", psiAfter > psiBefore)

        assertTrue(EntityClassFinder(project).find("sys_account_role_rel").isNotEmpty())
        assertEquals("改源码正文后不得重扫全工程", 1, EntityClassFinder.indexBuildCount(project).get())
    }

    fun testReverseLookupCacheInvalidatedOnPsiChange() {
        // TTL 兜住「改已有实体上的 @TableName」这类结构版本看不见的正文修改：
        // 把 TTL 置 0 ⇒ 下一次查询必重建索引 ⇒ 改后的 @TableName 能被找到（默认 5 分钟，见 ReverseLookupCache）
        val psiFile = addClassFile(
            "RoleRel.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("old_table")
            public class RoleRel {}
            """,
        ).containingFile
        assertTrue(EntityClassFinder(project).find("sys_account_role_rel").isEmpty())
        assertEquals(1, EntityClassFinder.scanCountForTest(project))
        // 文档修改（Document.setText）
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)!!
        WriteCommandAction.runWriteCommandAction(project, Computable {
            document.setText(
                """
                import com.baomidou.mybatisplus.annotation.TableName;

                @TableName("sys_account_role_rel")
                public class RoleRel {}
                """.trimIndent(),
            )
            null
        })
        PsiDocumentManager.getInstance(project).commitDocument(document)
        // TTL 置 0：即便结构版本没变也强制重建（模拟「过了 TTL 周期」）
        EntityClassFinder.reverseLookupTtlMs = 0L
        val found = try {
            EntityClassFinder(project).find("sys_account_role_rel")
        } finally {
            EntityClassFinder.reverseLookupTtlMs = 300_000L
        }
        assertEquals("索引重建后应找到修改后的实体", 1, found.size)
        assertEquals("RoleRel", found[0].name)
        assertEquals("索引重建后（新缓存实例）应扫描 1 次", 1, EntityClassFinder.scanCountForTest(project))
    }

    fun testAsyncIndexBuildDoesNotBlockCaller() {
        // 真机（2026-11-13 沙盒）：全量扫描实测 4737ms / 9675ms，压在**点击链**上就是「打开还是慢」。
        // 契约：扫描派到应用线程池，调用方至多等 INDEX_WAIT_BUDGET_MS，超预算就用当前结果返回。
        // 注入 3 秒扫描延迟 ⇒ 若退化成同步扫描，本次 find 必然 >3s，本用例红。
        val entity = addClassFile(
            "RoleRel.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("sys_account_role_rel")
            public class RoleRel {}
            """,
        )
        EntityClassFinder.syncIndexBuildForTest = false
        EntityClassFinder.indexScanDelayMsForTest = 3_000L
        try {
            val started = System.currentTimeMillis()
            val first = EntityClassFinder(project).find("sys_account_role_rel")
            val elapsed = System.currentTimeMillis() - started
            assertTrue("首次查询不得同步等全量扫描（实测 $elapsed ms）", elapsed < 2_500)
            assertTrue("扫描未完成时本次降级返回空（不是报错）", first.isEmpty())

            // 后台补齐后：索引建成、下一次查询命中
            val deadline = System.currentTimeMillis() + 20_000
            while (EntityClassFinder.indexBuildCount(project).get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }
            assertEquals("后台扫描应最终落地一次", 1, EntityClassFinder.indexBuildCount(project).get())
            val second = EntityClassFinder(project).find("sys_account_role_rel")
            assertEquals("索引就绪后应命中", 1, second.size)
            assertEquals("RoleRel", second[0].name)
            assertEquals("命中后不得再扫", 1, EntityClassFinder.indexBuildCount(project).get())
            assertTrue("夹具有效性：实体类必须真的存在（否则本用例什么都没验）", entity.isValid())
        } finally {
            EntityClassFinder.syncIndexBuildForTest = null
            EntityClassFinder.indexScanDelayMsForTest = null
        }
    }

    fun testAsyncIndexBuildQueryDoesNotHangWhenBuildInFlight() {
        // 在途/失败路径必须放行等待者：否则后来者白等一整个预算、且索引再没人能建。
        // 注入 2 秒延迟让「在途」稳定出现，连查两次都应在预算内返回（不得挂死）。
        EntityClassFinder.syncIndexBuildForTest = false
        EntityClassFinder.indexScanDelayMsForTest = 2_000L
        try {
            val started = System.currentTimeMillis()
            assertTrue(EntityClassFinder(project).find("not_exist_table_async_1").isEmpty())
            assertTrue(EntityClassFinder(project).find("not_exist_table_async_2").isEmpty())
            val elapsed = System.currentTimeMillis() - started
            assertTrue("两次在途查询都必须在预算内返回（实测 $elapsed ms）", elapsed < 2_400)
        } finally {
            EntityClassFinder.syncIndexBuildForTest = null
            EntityClassFinder.indexScanDelayMsForTest = null
        }
    }

    fun testReverseLookupHitRestoresClassesByQualifiedName() {
        // 命中缓存后按类名/全限定名重取：同名类（不同包）不会误配
        addClassFile(
            "RoleRel.java",
            """
            package com.a;
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("sys_account_role_rel")
            public class RoleRel {}
            """,
        )
        addClassFile(
            "RoleRel.java",
            """
            package com.b;
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("other_table")
            public class RoleRel {}
            """,
        )
        // 同名类文件不能同目录——分开放包目录
        // 上面第二次 addClassFile 覆盖同名文件，改用不同文件名验证重取逻辑
        val found = EntityClassFinder(project).find("sys_account_role_rel")
        assertEquals(1, found.size)
        assertEquals("com.a.RoleRel", found[0].qualifiedName)
        // 缓存命中路径重取（再次 find 不重扫）
        val again = EntityClassFinder(project).find("sys_account_role_rel")
        assertEquals(1, again.size)
        assertEquals("com.a.RoleRel", again[0].qualifiedName)
    }

    fun testCandidateClassNamesIncludesNoFirstSegment() {
        // P1 启发式候选：去首段变体（sys_/biz_ 前缀不一致命名直接快路径命中）
        val candidates = EntityClassFinder(project).candidateClassNames("sys_account_role_rel")
        assertTrue(candidates.contains("SysAccountRoleRel"))
        assertTrue(candidates.contains("SysAccountRoleRelEntity"))
        assertTrue("去首段候选应包含 AccountRoleRel", candidates.contains("AccountRoleRel"))
        assertTrue("去首段候选应包含 AccountRoleRelEntity", candidates.contains("AccountRoleRelEntity"))
        val banner = EntityClassFinder(project).candidateClassNames("biz_banner")
        assertTrue(banner.contains("Banner"))
        assertTrue(banner.contains("BannerEntity"))
        // 单段表名：去首段无新候选（substringAfter('_') 返回原样，去重）
        val single = EntityClassFinder(project).candidateClassNames("user")
        assertEquals(listOf("User", "UserEntity"), single)
    }

    fun testReverseLookupCacheInfoAndClear() {
        // 索引信息＝**已建索引里的条目**（表名→类）；查一个不存在的表名不会往索引里塞负条目
        // （旧口径按「查询过的表名」建负缓存，现在是「一次扫描建全表索引」，没匹配自然不进索引）
        assertTrue(EntityClassFinder(project).find("not_exist_table_1").isEmpty())
        assertEquals("无实体的夹具：索引条目应为空", 0 to 0, EntityClassFinder.reverseLookupCacheInfo(project))
        assertEquals("但索引已建成（扫描 1 次，后续零扫描）", 1, EntityClassFinder.scanCountForTest(project))
        assertTrue(EntityClassFinder(project).find("not_exist_table_1").isEmpty())
        assertEquals("二次 find 不得重扫", 1, EntityClassFinder.scanCountForTest(project))
        // 清除缓存 → 信息归零 → 再 find 重新扫描（scanCount 重置后再次为 1）
        EntityClassFinder.clearReverseLookupCache(project)
        assertEquals("清除后缓存信息应归零", 0 to 0, EntityClassFinder.reverseLookupCacheInfo(project))
        assertTrue(EntityClassFinder(project).find("not_exist_table_1").isEmpty())
        assertEquals("清除后 find 应重新扫描", 1, EntityClassFinder.scanCountForTest(project))
    }

    fun testReverseLookupCacheInfoCountsClasses() {
        // 有命中缓存：条目数 + 类名数
        addClassFile(
            "RoleRelA.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("sys_account_role_rel")
            public class RoleRelA {}
            """,
        )
        addClassFile(
            "RoleRelB.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("sys_account_role_rel")
            public class RoleRelB {}
            """,
        )
        assertEquals(2, EntityClassFinder(project).find("sys_account_role_rel").size)
        assertEquals("1 张表 2 个类", 1 to 2, EntityClassFinder.reverseLookupCacheInfo(project))
        assertTrue("有命中缓存时大小估算应 > 0", EntityClassFinder.reverseLookupCacheSizeBytes(project) > 0)
        // 清除后大小归零
        EntityClassFinder.clearReverseLookupCache(project)
        assertEquals(0L, EntityClassFinder.reverseLookupCacheSizeBytes(project))
    }
}
