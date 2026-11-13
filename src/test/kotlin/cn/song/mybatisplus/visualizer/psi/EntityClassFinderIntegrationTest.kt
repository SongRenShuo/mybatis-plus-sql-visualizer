package cn.song.mybatisplus.visualizer.psi

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * EntityClassFinder 反查命中后正确传递到下游的接缝测试。
 *
 * 验证 EntityClassFinder 与下游 SqlToJavaConverter / JavaCodeGenerator 的接缝：
 *  - @TableName 实体 find 返回正确 qualifiedName
 *  - 表名推断命中（sys_ 前缀去首段）
 *  - 反查缓存命中后第二次 find 零扫描
 *  - 缓存清除后重新扫描
 *
 * 依赖真实索引（PsiShortNamesCache），使用 [HeavyPlatformTestCase]。
 */
class EntityClassFinderIntegrationTest : HeavyPlatformTestCase() {

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

    /**
     * 接缝：EntityClassFinder（@TableName 命中）→ 下游 SqlToJavaConverter（qualifiedName 传递）。
     * 场景：有 @TableName 实体时，find 返回正确 qualifiedName。
     */
    fun testAnnotatedEntityReturnsCorrectQualifiedName() {
        val clazz = addClassFile(
            "DeviceInfoEntity.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("device_info")
            public class DeviceInfoEntity {
                private Long id;
                private String name;
            }
            """,
        )
        // 验证 @TableName 实体创建成功
        assertNotNull("实体类应创建成功", clazz)
        assertEquals("DeviceInfoEntity", clazz.name)

        // find 返回命中
        val found = EntityClassFinder(project).find("device_info")
        assertEquals("应找到 1 个实体", 1, found.size)

        // 验证 qualifiedName 正确传递到下游
        assertEquals("com.example.DeviceInfoEntity", found[0].qualifiedName)
        assertEquals("device_info", EntityResolver().resolve(found[0]).tableName)
    }

    /**
     * 接缝：EntityClassFinder（表名推断命中）→ 下游（qualifiedName 传递）。
     * 场景：sys_ 前缀去首段推断命中（sys_account_role_rel → AccountRoleRel）。
     */
    fun testTableNameInferenceHitsByRemovingFirstSegment() {
        addClassFile(
            "AccountRoleRelEntity.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("sys_account_role_rel")
            public class AccountRoleRelEntity {
                private Long roleId;
                private Long accountId;
            }
            """,
        )
        // 表名推断：sys_account_role_rel → 去首段 AccountRoleRel → 加 Entity 后缀命中
        val found = EntityClassFinder(project).find("sys_account_role_rel")
        assertEquals("去首段推断应命中 1 个实体", 1, found.size)
        assertEquals("AccountRoleRelEntity", found[0].name)
        assertEquals("com.example.AccountRoleRelEntity", found[0].qualifiedName)
    }

    /**
     * 接缝：EntityClassFinder（缓存命中）→ 下游（零扫描）。
     * 场景：反查缓存命中后第二次 find 零扫描（性能接缝）。
     */
    fun testCacheHitResultsInZeroScansOnSecondFind() {
        addClassFile(
            "RoleRelEntity.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("sys_account_role_rel")
            public class RoleRelEntity {
                private Long id;
            }
            """,
        )
        val finder = EntityClassFinder(project)

        // 首次 find：全量扫描 1 次
        val first = finder.find("sys_account_role_rel")
        assertEquals("首次 find 应命中", 1, first.size)
        val scansAfterFirst = EntityClassFinder.scanCountForTest(project)
        assertEquals("首次 find 应触发 1 次全量扫描", 1, scansAfterFirst)

        // 第二次 find（同表名）：缓存命中，零扫描
        val second = finder.find("sys_account_role_rel")
        assertEquals("二次 find 应同样命中", 1, second.size)
        val scansAfterSecond = EntityClassFinder.scanCountForTest(project)
        assertEquals("缓存命中后二次 find 应零扫描", scansAfterFirst, scansAfterSecond)

        // 验证下游收到的 qualifiedName 一致（缓存传递一致性）
        assertEquals("缓存命中后 qualifiedName 应一致", first[0].qualifiedName, second[0].qualifiedName)
    }

    /**
     * 接缝：EntityClassFinder（缓存清除）→ 下游（重新扫描）。
     * 场景：缓存清除后重新扫描，find 仍能命中。
     */
    fun testCacheClearTriggersRescan() {
        // 使用与表名驼峰无关的类名（RoleRel ↔ sys_account_role_rel）：快路径候选
        // [SysAccountRoleRel, SysAccountRoleRelEntity, AccountRoleRel, AccountRoleRelEntity] 不含 RoleRel → 走反查慢路径
        addClassFile(
            "RoleRel.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("sys_account_role_rel")
            public class RoleRel {
                private Long id;
            }
            """,
        )
        val finder = EntityClassFinder(project)

        // 首次 find → 反查慢路径 → 缓存
        assertEquals(1, finder.find("sys_account_role_rel").size)
        assertEquals("首次扫描应为 1 次", 1, EntityClassFinder.scanCountForTest(project))

        // 清除缓存
        EntityClassFinder.clearReverseLookupCache(project)
        assertEquals("清除后缓存信息应归零", 0 to 0, EntityClassFinder.reverseLookupCacheInfo(project))

        // 再次 find → 重新扫描
        val afterClear = finder.find("sys_account_role_rel")
        assertEquals("清除缓存后 find 应仍能命中", 1, afterClear.size)
        assertEquals("RoleRel", afterClear[0].name)
        // 清除缓存后重新扫描：新缓存实例扫描计数重置，扫描 1 次
        assertEquals("清除缓存后 find 应重新扫描 1 次", 1, EntityClassFinder.scanCountForTest(project))
    }

    /**
     * 接缝：EntityClassFinder（find 结果）→ 下游 SqlToJavaConverter（多候选顺序传递）。
     * 场景：多候选实体时，调用方按 find 返回的顺序处理。
     */
    fun testMultipleCandidatesPassedInOrder() {
        addClassFile(
            "RoleRelA.java",
            """
            package com.example;
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
            package com.example;
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("sys_account_role_rel")
            public class RoleRelB {
                private Long id;
            }
            """,
        )
        val found = EntityClassFinder(project).find("sys_account_role_rel")
        assertEquals("应返回 2 个候选", 2, found.size)
        // 验证顺序：按类名排序（A 先于 B）
        assertEquals(listOf("RoleRelA", "RoleRelB"), found.map { it.name })
        // 验证下游能正确获取每个候选的 qualifiedName
        assertEquals("com.example.RoleRelA", found[0].qualifiedName)
        assertEquals("com.example.RoleRelB", found[1].qualifiedName)
    }

    /**
     * 接缝：EntityClassFinder（索引重建 → 新表名可见）→ 下游（新表名可被找到）。
     *
     * 失效契约（20261113 改）：索引失效基准＝**项目结构版本 + TTL**，**不再**挂 `PsiModificationTracker`。
     * 原因见 `ReverseLookupCache` 注释：预览链路自己就会改 PSI（写 exec 文件/临时 PsiFile/对话框打字），
     * 挂 PSI 版本等于每次打开预览都重扫全工程（真机实测 `resolve=3994ms`）。本用例把两条都钉住：
     * ①改 `@TableName` 后**未过 TTL**时索引不重建（打字不作废索引）；②过 TTL 后重建、新表名可见。
     */
    fun testPsiModificationInvalidatesCache() {
        // 使用与表名驼峰无关的类名（RoleRel ↔ sys_account_role_rel）：快路径不命中 → 走反查慢路径
        val psiFile = addClassFile(
            "RoleRel.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("old_table")
            public class RoleRel {
                private Long id;
            }
            """,
        ).containingFile

        // 首次 find 旧表名（走反查慢路径，因为 RoleRel 不在 old_table 的快路径候选中）
        assertEquals(1, EntityClassFinder(project).find("old_table").size)
        assertEquals("首次扫描应为 1 次", 1, EntityClassFinder.scanCountForTest(project))

        // 新表名此时找不到（索引里没有它）
        assertTrue("新表名修改前应找不到", EntityClassFinder(project).find("new_table").isEmpty())

        // 修改文档 @TableName
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)!!
        WriteCommandAction.runWriteCommandAction(project, Computable {
            document.setText(
                """
                package com.example;
                import com.baomidou.mybatisplus.annotation.TableName;

                @TableName("new_table")
                public class RoleRel {
                    private Long id;
                }
                """.trimIndent(),
            )
            null
        })
        PsiDocumentManager.getInstance(project).commitDocument(document)

        // ① 未过 TTL：索引不重建（这就是「打字不作废索引」的契约，反向改回 PSI 版本基准 → 本断言必红）
        assertTrue("未过 TTL 时索引不得因正文修改重建", EntityClassFinder(project).find("new_table").isEmpty())
        assertEquals("未过 TTL 不得重扫", 1, EntityClassFinder.indexBuildCount(project).get())

        // ② 过 TTL（置 0）：重建索引 → 新表名可被找到
        EntityClassFinder.reverseLookupTtlMs = 0L
        val found = try {
            EntityClassFinder(project).find("new_table")
        } finally {
            EntityClassFinder.reverseLookupTtlMs = 300_000L
        }
        assertEquals("TTL 到期重建后应找到新表名实体", 1, found.size)
        assertEquals("RoleRel", found[0].name)
        assertEquals("重建应恰好扫 1 次（新缓存实例）", 1, EntityClassFinder.scanCountForTest(project))
    }

    /**
     * 接缝：EntityClassFinder（无匹配）→ 下游（空列表传递，调用方回落字符串列名形态）。
     * 场景：无实体匹配时返回空列表，下游调用方应正确处理空列表。
     */
    fun testNoMatchReturnsEmptyListForDownstreamFallback() {
        val found = EntityClassFinder(project).find("non_existent_table_xyz")
        assertTrue("无匹配时应返回空列表", found.isEmpty())
        // 验证负缓存生效（再次 find 不再扫描）
        val firstScanCount = EntityClassFinder.scanCountForTest(project)
        EntityClassFinder(project).find("non_existent_table_xyz")
        assertEquals("负缓存命中后不应再扫描", firstScanCount, EntityClassFinder.scanCountForTest(project))
    }

    /**
     * 接缝：EntityClassFinder（VO/DTO 排除）→ 下游（不含 VO/DTO 的候选）。
     * 场景：VO/DTO 类即使表名匹配也被排除，下游只收到真正的实体类。
     */
    fun testVoDtoExcludedFromDownstreamCandidates() {
        addClassFile(
            "UserEntity.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("t_user")
            public class UserEntity {
                private Long id;
                private String name;
            }
            """,
        )
        addClassFile(
            "UserVO.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("t_user")
            public class UserVO {
                private String name;
            }
            """,
        )
        val found = EntityClassFinder(project).find("t_user")
        assertEquals("VO 排除后应只返回实体类", 1, found.size)
        assertEquals("UserEntity", found[0].name)
        assertEquals("com.example.UserEntity", found[0].qualifiedName)
    }

    // ------------------------------------------------------------------
    // T-JarEmbedded W2：依赖库（JAR / SDK）里的实体反查
    // ------------------------------------------------------------------

    /**
     * 给测试模块挂上**本机 JDK 作为 SDK**。
     *
     * `HeavyPlatformTestCase` 默认**不挂任何 SDK**（实测：`allScope` 里连 `java.lang.Object` 都解析不到），
     * 而库兜底要验的正是「类在依赖库/SDK 里、项目内容根没有」——所以这里以本机 JDK 建一个 SDK
     * （先注册进 [ProjectJdkTable] 才能 setSdk，实测 `setSdk` 会校验注册表）挂到模块上，
     * 让 `allScope` 真有库类可查；[tearDown] 里摘掉，避免污染其他用例。
     */
    private fun attachJdkAsSdk() {
        val home = System.getProperty("java.home") ?: return
        val sdk = JavaSdk.getInstance().createJdk("mpv-test-jdk", home, false)
        ApplicationManager.getApplication().runWriteAction { ProjectJdkTable.getInstance().addJdk(sdk) }
        attachedJdk = sdk
        val model = ModuleRootManager.getInstance(myModule).modifiableModel
        model.sdk = sdk
        ApplicationManager.getApplication().runWriteAction { model.commit() }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    override fun tearDown() {
        try {
            attachedJdk?.let { sdk ->
                ApplicationManager.getApplication().runWriteAction { ProjectJdkTable.getInstance().removeJdk(sdk) }
            }
        } finally {
            attachedJdk = null
            super.tearDown()
        }
    }

    /** 本用例挂上的 SDK（tearDown 摘除）。 */
    private var attachedJdk: Sdk? = null

    /**
     * 库兜底正向：**项目内容根里没有**该表对应的实体，但依赖库（测试工程的 SDK / mock JDK）里有。
     *
     * 不猜具体类名：先在环境里探测**哪个库类真的可见**（`JavaPsiFacade.findClass` 走 allScope），
     * 再用它按插件自己的口径反推表名，最后断言 `find(表名)` 能把它找回来——与「实体来自依赖 JAR」
     * 在 `find` 眼里同形（库兜底只看「候选类名 → 表名精确匹配」，与类从哪个 jar 来无关）。
     * 环境若连一个库类都没挂，按「环境受限未验证」显式跳过，不编读数。
     *
     * 为什么不造临时 jar：测试 JVM **无 javac**（`ToolProvider.getSystemJavaCompiler()` 为 null，实测）；
     * 「库源码根」也不行（实测该类根本不进索引：`JavaPsiFacade.findClass` 与短名索引都查不到）。
     */
    fun testLibraryEntityFoundByCandidateNameFallback() {
        attachJdkAsSdk()
        val all = GlobalSearchScope.allScope(project)
        val facade = JavaPsiFacade.getInstance(project)
        val libClass = listOf("java.lang.Object", "java.util.List", "java.util.Map", "java.lang.StringBuilder")
            .firstNotNullOfOrNull { facade.findClass(it, all) }
        assertNotNull("挂了 mock JDK 后 allScope 应能找到 java.lang.Object", libClass)

        val table = EntityResolver.inferDecoratedTableName(libClass!!.name!!, project)
        val found = EntityClassFinder(project).find(table)

        assertTrue(
            "依赖库里的类应被库兜底找到（表名 $table）：${found.map { it.qualifiedName }}",
            found.any { it.qualifiedName == libClass.qualifiedName },
        )
        assertEquals("库命中后不应触发慢路径全量扫描", 0, EntityClassFinder.scanCountForTest(project))
    }

    /** 快路径命中即返回：**不得**进入慢路径全项目逐类扫描（性能口径钉成断言）。 */
    fun testFastPathHitSkipsSlowScan() {
        addClassFile("Random.java", "package com.example; public class Random { private Long id; }")

        val found = EntityClassFinder(project).find("random")
        assertEquals("快路径应命中项目里的实体", 1, found.size)
        assertEquals("com.example.Random", found[0].qualifiedName)
        assertEquals("快路径命中不应触发慢路径全量扫描", 0, EntityClassFinder.scanCountForTest(project))
    }

    // ------------------------------------------------------------------
    // T-JarEmbedded 优化方案 1：库内实体的一次性全库扫描 + 项目级缓存
    // ------------------------------------------------------------------

    /**
     * 真扫：`runLibrarySweepNow()` 按 `@TableName` 建「表名 → 类」索引。
     *
     * **口径变化（T-JarEmbedded 性能收口）**：扫描只认 `@TableName`，不再按类名推断表名——后者对
     * 任何类名都能推出非空表名，等于把整条 classpath（含 JDK）的类全建成「表」（实测只挂 JDK 就有
     * 27k 类 → 20k 条目、单趟 4.5s、打字被阻塞 5.5s）。所以这里用**带注解的夹具**断言，不再拿
     * 「JDK 里能扫出条目」当读数（JDK 类没有 `@TableName`，本就该扫出 0 条）。
     */
    fun testLibrarySweepIndexesAnnotatedClass() {
        addClassFile(
            "ProbeEntity.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("probe_tbl")
            public class ProbeEntity { private Long id; }
            """,
        )
        val entries = EntityClassFinder(project).runLibrarySweepNow()
        assertTrue("带 @TableName 的类应被扫进索引", "probe_tbl" in EntityClassFinder.sweepTablesForTest(project))
        assertEquals("缓存条目数应与扫描返回值一致", entries, EntityClassFinder.sweepTableCountForTest(project))
        assertEquals(
            "扫描结果应能被 find 命中",
            "com.example.ProbeEntity",
            EntityClassFinder(project).find("probe_tbl").single().qualifiedName,
        )
    }

    /**
     * **性能收口的判别用例**：没有 `@TableName` 的类**不进**扫描索引。
     *
     * 摘掉「只认 @TableName」这一刀（恢复 `?: inferDecoratedTableName(...)` 兜底）→ 夹具类
     * `PlainModel` 会被按类名推断成 `plain_model` 进缓存，本用例必红。
     * 断言必须看**缓存内容**而不是 `find` 的结果——`find` 的快路径本来就会按类名推断命中
     * `PlainModel`，拿它当断言等于什么都没验（这类假绿本轮踩过）。
     */
    fun testLibrarySweepSkipsClassesWithoutTableName() {
        addClassFile("PlainModel.java", "package com.example; public class PlainModel { private Long id; }")
        EntityClassFinder(project).runLibrarySweepNow()
        assertTrue(
            "无 @TableName 的类不应进库扫描索引（实际索引：${EntityClassFinder.sweepTablesForTest(project)}）",
            "plain_model" !in EntityClassFinder.sweepTablesForTest(project),
        )
    }

    /** 触顶（未扫完）**不落缓存**：残缺结果不得被当成完整结果长期复用。 */
    fun testLibrarySweepTruncationDoesNotCache() {
        addClassFile(
            "ProbeEntity.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("probe_tbl")
            public class ProbeEntity { private Long id; }
            """,
        )
        // 上限置 0 = 立刻触顶（与短名个数无关，确定性）；上限按**剩余预算**切分，故 0 必然扫不动
        EntityClassFinder.sweepScanLimitForTest = 0
        try {
            val entries = EntityClassFinder(project).runLibrarySweepNow()
            assertEquals("触顶应返回 -1（未扫完）", -1, entries)
            assertTrue("触顶不得落缓存", EntityClassFinder.sweepTablesForTest(project).isEmpty())
            assertTrue("触顶不得把该版本标成已扫完", !EntityClassFinder.sweepDispatchedForTest(project))
        } finally {
            EntityClassFinder.sweepScanLimitForTest = null
        }
    }

    /**
     * 失效基准＝**classpath 版本**：**改源码正文（打字）**不得作废库扫描缓存。
     *
     * 旧口径挂 `PsiModificationTracker`（对任何 PSI 变化都递增，实测改一个文件 4→16）⇒ 每次编辑后
     * 下一次未命中都会重投一趟全 classpath 扫描。本用例把冷却窗口置 0，单独验失效基准这一条；
     * 摘回旧基准 → 本用例必红。
     *
     * 注意模拟动作必须是**改已有文件的正文**，不是「新增文件」：新增文件属于项目结构变化，
     * `ProjectRootModificationTracker` 本来就会递增（实测踩过——第一版用例就是因此假红），
     * 而日常最频繁的动作是打字。
     */
    fun testLibrarySweepCacheSurvivesSourceEdit() {
        val entity = addClassFile(
            "ProbeEntity.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("probe_tbl")
            public class ProbeEntity { private Long id; }
            """,
        )
        EntityClassFinder(project).runLibrarySweepNow()
        assertTrue("扫描后应已登记投递", EntityClassFinder.sweepDispatchedForTest(project))

        // 模拟打字：改**已有文件**的正文（PSI 版本递增；项目结构不变 ⇒ classpath 版本不变）
        val vf = entity.containingFile.virtualFile
        val psiBefore = PsiModificationTracker.getInstance(project).modificationCount
        WriteCommandAction.runWriteCommandAction(project, Computable {
            VfsUtil.saveText(
                vf,
                """
                package com.example;
                import com.baomidou.mybatisplus.annotation.TableName;

                @TableName("probe_tbl")
                public class ProbeEntity { private Long id; private String name; }
                """.trimIndent(),
            )
        })
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val psiAfter = PsiModificationTracker.getInstance(project).modificationCount
        assertTrue("夹具有效性：编辑后 PSI 版本必须递增，否则本用例什么都没验（$psiBefore → $psiAfter）", psiAfter > psiBefore)

        var dispatched = 0
        EntityClassFinder.sweepDispatchForTest = { dispatched++ }
        EntityClassFinder.sweepCooldownMs = 0
        try {
            EntityClassFinder(project).find("t_booking")
            assertEquals("改源码正文后不得重投库扫描", 0, dispatched)
        } finally {
            EntityClassFinder.sweepCooldownMs = EntityClassFinder.LIBRARY_SWEEP_COOLDOWN_MS
            EntityClassFinder.sweepDispatchForTest = null
        }
        assertTrue("缓存条目应仍在", "probe_tbl" in EntityClassFinder.sweepTablesForTest(project))
    }

    /**
     * 冷却窗口下界＝**上趟实际耗时**：扫描是秒级（实测只挂 JDK 就 4.4s），固定 5s 冷却短于一趟
     * ⇒ 结构频繁变化（重构中反复增删文件）时会一趟接一趟连跑。这里把配置窗口置 0，
     * 仅靠「上趟耗时」这一项就应挡住重投；摘掉那个下界 → 本用例必红。
     *
     * 用时钟缝（[EntityClassFinder.sweepClockForTest]）而非真实时钟：下界只有毫秒量级，
     * 靠真实时间会变成时序抖动（假绿/假红都可能有）。
     */
    fun testLibrarySweepCooldownBoundedByLastScanCost() {
        addClassFile(
            "ProbeEntity.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("probe_tbl")
            public class ProbeEntity { private Long id; }
            """,
        )
        var now = 1_000_000L
        EntityClassFinder.sweepClockForTest = { now }
        var dispatched = 0
        EntityClassFinder.sweepDispatchForTest = { dispatched++ }
        EntityClassFinder.sweepCooldownMs = 0
        try {
            // 直接执行一趟（＝弹窗「在依赖库中查找」那条路）：应记下完成时刻与耗时，从而自带冷却窗口
            EntityClassFinder(project).runLibrarySweepNow()
            EntityClassFinder.clearLibrarySweepDispatchedForTest(project)

            EntityClassFinder(project).find("t_booking")
            assertEquals("冷却窗口应以上趟耗时为下界，不得立即重投", 0, dispatched)

            now += 5
            EntityClassFinder(project).find("t_booking")
            assertEquals("越过下界后应可重投", 1, dispatched)
        } finally {
            EntityClassFinder.sweepClockForTest = null
            EntityClassFinder.sweepCooldownMs = EntityClassFinder.LIBRARY_SWEEP_COOLDOWN_MS
            EntityClassFinder.sweepDispatchForTest = null
        }
    }

    /**
     * 冷却窗口：窗口内不得重复投递（依赖/模块频繁变化时防投递风暴）。
     *
     * 两半都断言，使本用例自带判别力：冷却窗口内 → 不投；冷却关掉 → 同条件应可投。
     */
    fun testLibrarySweepCooldownBlocksRedispatch() {
        var dispatched = 0
        EntityClassFinder.sweepDispatchForTest = { dispatched++ }
        EntityClassFinder.sweepCooldownMs = 60_000
        try {
            EntityClassFinder(project).find("t_booking")
            assertEquals("首次未命中应投递", 1, dispatched)

            // 模拟「上次扫描被取消/触顶」：登记被清（可重投），但冷却窗口仍在
            EntityClassFinder.clearLibrarySweepDispatchedForTest(project)
            EntityClassFinder(project).find("t_booking")
            assertEquals("冷却窗口内不得重复投递", 1, dispatched)

            EntityClassFinder.sweepCooldownMs = 0
            EntityClassFinder(project).find("t_booking")
            assertEquals("冷却关闭后应可重投", 2, dispatched)
        } finally {
            EntityClassFinder.sweepCooldownMs = EntityClassFinder.LIBRARY_SWEEP_COOLDOWN_MS
            EntityClassFinder.sweepDispatchForTest = null
        }
    }

    /**
     * 缓存 → 命中接线：注入一条「t_booking → LegacyBooking」的扫描结果后，`find("t_booking")` 应命中它。
     *
     * 这正是「库内实体类名与表名约定不符、只能靠 @TableName 认出」的形态：`LegacyBooking` 不在
     * `t_booking` 的候选类名（TBooking/TBookingEntity/Booking/BookingEntity）里，慢路径按名推断也认不出。
     */
    fun testLibrarySweepCacheHitForUnconventionalTableName() {
        val clazz = addClassFile(
            "LegacyBooking.java",
            "package com.example; public class LegacyBooking { private Long id; }",
        )
        EntityClassFinder.seedLibrarySweepForTest(project, "t_booking", clazz)

        val found = EntityClassFinder(project).find("t_booking")
        assertEquals("库扫描缓存命中应返回该实体", 1, found.size)
        assertEquals("com.example.LegacyBooking", found[0].qualifiedName)
    }

    /**
     * 库类也要能经缓存取回（堵测试盲点）：上一条注入的是**项目类**，`restoreMatched` 即便只查
     * projectScope 也会绿——而方案 1 的缓存里存的恰恰是**依赖库里的类**。这里注入
     * 「t_object → java.lang.Object」（该表名快路径/慢路径都认不出），断言能取回库类本身。
     */
    fun testLibrarySweepCacheRestoresLibraryClass() {
        attachJdkAsSdk()
        val objectClass = JavaPsiFacade.getInstance(project)
            .findClass("java.lang.Object", GlobalSearchScope.allScope(project))
        assertNotNull("挂了 JDK 后应能解析 java.lang.Object", objectClass)

        EntityClassFinder.seedLibrarySweepForTest(project, "t_object", objectClass!!)
        val found = EntityClassFinder(project).find("t_object")

        assertEquals("库扫描缓存命中应取回库类", 1, found.size)
        assertEquals("java.lang.Object", found[0].qualifiedName)
    }

    /** 反向对照：不注入缓存且关掉自动投递 → 同一表名找不到（证明上一条走的确实是缓存那条路）。 */
    fun testUnconventionalTableNameMissesWithoutSweepCache() {
        addClassFile("LegacyBooking.java", "package com.example; public class LegacyBooking { private Long id; }")
        EntityClassFinder.clearLibrarySweepCache(project)
        EntityClassFinder.sweepEnabledForTest = false
        try {
            assertTrue(
                "无缓存 + 不投递时非常规命名找不到（既有行为，无回归）",
                EntityClassFinder(project).find("t_booking").isEmpty(),
            )
        } finally {
            EntityClassFinder.sweepEnabledForTest = null
        }
    }

    /** 开关：关闭「后台扫描依赖库」→ 未命中时**不投递**（用投递缝断言，不产生真实后台线程）。 */
    fun testLibrarySweepNotDispatchedWhenDisabled() {
        var dispatched = 0
        EntityClassFinder.sweepDispatchForTest = { dispatched++ }
        EntityClassFinder.sweepEnabledForTest = false
        try {
            EntityClassFinder(project).find("t_booking")
            assertEquals("开关关闭时不得投递库扫描", 0, dispatched)
        } finally {
            EntityClassFinder.sweepEnabledForTest = null
            EntityClassFinder.sweepDispatchForTest = null
        }
    }

    /** 每项目版本只投递一次：同一版本内多次未命中不得重复投递（多表 JOIN 场景常见）。 */
    fun testLibrarySweepDispatchedOncePerProjectVersion() {
        var dispatched = 0
        EntityClassFinder.sweepDispatchForTest = { dispatched++ }
        try {
            val finder = EntityClassFinder(project)
            finder.find("t_booking")
            finder.find("t_booking")
            finder.find("another_missing_table")
            assertEquals("同一项目版本只投递一次库扫描", 1, dispatched)
            assertTrue("投递后应已登记", EntityClassFinder.sweepDispatchedForTest(project))
        } finally {
            EntityClassFinder.sweepDispatchForTest = null
        }
    }
}
