package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.ProjectPluginSettings
import cn.song.mybatisplus.visualizer.psi.WrapperAnchorDetector
import cn.song.mybatisplus.visualizer.psi.WrapperLocator
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

class SqlPreviewServiceBatchTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // O-97 治理（T-TestPerf15）：进态恢复出厂默认，截断跨类全局设置态泄漏
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
    }

    private fun newService() = SqlPreviewService(project)

    /** T-TenantLine：每个测试结束后重置多租户配置为默认，防止状态泄漏。 */
    override fun tearDown() {
        PluginSettings.getInstance().state.injectTenantLine = false
        PluginSettings.getInstance().state.injectTenantOnInsert = true
        PluginSettings.getInstance().state.tenantColumnName = PluginSettings.DEFAULT_TENANT_COLUMN_NAME
        super.tearDown()
    }

    private fun anchor(): PsiElement {
        val file = myFixture.file
        val methodCall = PsiTreeUtil.findChildOfType(file, PsiMethodCallExpression::class.java)
        if (methodCall != null) return methodCall
        // 空调用链场景（如无条件的 LambdaDeleteWrapper）：锚定变量声明本身
        val variable = PsiTreeUtil.findChildOfType(file, PsiLocalVariable::class.java)
        if (variable != null) return variable
        return PsiTreeUtil.findChildOfType(file, PsiLiteralExpression::class.java)
            ?: PsiTreeUtil.getDeepestFirst(file)
    }

    fun testBatchSaveRendersInsertWithParamSources() {
        // T-M23-02（用户实测：save 没有填参数的地方）：save(entity) 每个 `?` 登记 `实体.列名` 参数行，
        // 回填引号提示按列类型（数值列无引号、String 列引号）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private StoreCouponService storeCouponService;

                public void save(StoreCouponEntity entity) {
                    storeCouponService.save(entity);
                }

                static class StoreCouponEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                    private Integer dicStatus;
                }

                static class BaseService<T> {}

                static class StoreCouponService extends BaseService<StoreCouponEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("save 应产出预览结果", result)
        assertEquals(
            """
            INSERT INTO store_coupon_entity (id, name, dic_status)
            VALUES (?, ?, ?)
            """.trimIndent(),
            result!!.sql,
        )
        assertEquals(
            "save 应登记实体字段参数行",
            listOf("entity.id", "entity.name", "entity.dic_status"),
            result.unresolvedParams,
        )
        // 数值列（Long/Integer）无引号、String 列引号
        assertEquals(listOf(false, true, false), result.paramHints.map { it.quote })
    }

    fun testBatchSaveKeywordColumnQuoted() {
        // T-M23.4（用户示例：biz_banner 的 table 字段为 MySQL 保留字）：批量 INSERT 列清单
        // 关键字列名反引号包裹（方言符号），参数行/回填提示不受影响（模型层列名不包裹）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private BizBannerService bizBannerService;

                public void save(BizBannerEntity entity) {
                    bizBannerService.save(entity);
                }

                static class BizBannerEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String title;
                    private String name;
                    private String table;
                    private String imageUrl;
                    private Integer dicStatus;
                    private Integer sort;
                }

                static class BaseService<T> {}

                static class BizBannerService extends BaseService<BizBannerEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("save 应产出预览结果", result)
        assertEquals(
            """
            INSERT INTO biz_banner_entity (id, title, name, `table`, image_url, dic_status, sort)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            result!!.sql,
        )
        // 参数行来源/引号提示与未引用时一致
        assertEquals(
            listOf("entity.id", "entity.title", "entity.name", "entity.table", "entity.image_url", "entity.dic_status", "entity.sort"),
            result.unresolvedParams,
        )
        assertEquals(listOf(false, true, true, true, true, false, false), result.paramHints.map { it.quote })
    }

    fun testBatchGetByIdKeywordColumnsQuoted() {
        // T-M23.5（用户实测：查询未处理关键字）：批量 SELECT（getById 形态）——
        // 全列清单/表名/主键条件按方言引用（`table` 列反引号包裹）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private BizBannerService bizBannerService;

                public void query(Long id) {
                    bizBannerService.getById(id);
                }

                static class BizBannerEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String title;
                    private String name;
                    private String table;
                    private String imageUrl;
                    private Integer dicStatus;
                }

                static class BaseService<T> {}

                static class BizBannerService extends BaseService<BizBannerEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("getById 应产出预览结果", result)
        assertEquals(
            """
            SELECT id, title, name, `table`, image_url, dic_status
            FROM biz_banner_entity
            WHERE id = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testBatchRemoveByIdLogicDeleteKeywordColumnsQuoted() {
        // T-M23.5：批量软删除模板（@TableLogic）——表名/SET 逻辑列/WHERE 主键与逻辑条件按方言引用
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private BizBannerService bizBannerService;

                public void remove(Long id) {
                    bizBannerService.removeById(id);
                }

                static class BizBannerEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String table;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }

                static class BaseService<T> {}

                static class BizBannerService extends BaseService<BizBannerEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("removeById 应产出预览结果", result)
        assertEquals(
            """
            UPDATE biz_banner_entity
            SET deleted = 1
            WHERE id = ? AND deleted = 0
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testBatchSaveAutoIncrementIdDefaultUnchecked() {
        // T-M23-04（用户建议：添加时 id 可能不需要）：@TableId(type = IdType.AUTO) 主键默认不勾选
        // → 初始 INSERT 不含 id 列（对齐 MP：自增主键不进 INSERT 列清单）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private StoreCouponService storeCouponService;

                public void save(StoreCouponEntity entity) {
                    storeCouponService.save(entity);
                }

                static class StoreCouponEntity {
                    @com.baomidou.mybatisplus.annotation.TableId(type = com.baomidou.mybatisplus.annotation.IdType.AUTO)
                    private Long id;
                    private String name;
                    private Integer dicStatus;
                }

                static class BaseService<T> {}

                static class StoreCouponService extends BaseService<StoreCouponEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("save 应产出预览结果", result)
        assertEquals(
            """
            INSERT INTO store_coupon_entity (name, dic_status)
            VALUES (?, ?)
            """.trimIndent(),
            result!!.sql,
        )
        assertEquals("AUTO 主键不进参数行", listOf("entity.name", "entity.dic_status"), result.unresolvedParams)
        val model = result.batchModel!!
        assertEquals("主键应默认不勾选", false, model.toggleColumns.first { it.columnName == "id" }.checked)
    }

    fun testBatchUpdateByIdExposesToggleColumns() {
        // T-M23-04（用户建议：修改时有些字段可能不需要修改）：updateById 的 SET 列为可勾选（默认全勾选），
        // WHERE 主键为固定列（无勾选）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void update(DeviceInfoEntity entity) {
                    deviceInfoService.updateById(entity);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                    private Integer dicStatus;
                }

                static class BaseService<T> {}

                static class DeviceInfoService extends BaseService<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("updateById 应产出预览结果", result)
        assertEquals(
            """
            UPDATE device_info_entity
            SET name = ?, dic_status = ?
            WHERE id = ?
            """.trimIndent(),
            result!!.sql,
        )
        val model = result.batchModel!!
        assertEquals("SET 列应为可勾选", listOf("name", "dic_status"), model.toggleColumns.map { it.columnName })
        assertEquals("SET 列默认全勾选", listOf(true, true), model.toggleColumns.map { it.checked })
        assertEquals("WHERE 主键应为固定列", listOf("id"), model.fixedColumns.map { it.columnName })
    }

    fun testBatchSaveOrUpdateIdSelectable() {
        // T-M23.2（用户实测：taskRecordService.saveOrUpdate(task)——id 应为可选）：
        // saveOrUpdate 主键纳入 toggleColumns（默认勾选渲染完整 upsert；取消 → 纯 INSERT 新记录路径），
        // 不再是固定必填列
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private TaskRecordService taskRecordService;

                public void saveOrUpdate(TaskRecordEntity task) {
                    taskRecordService.saveOrUpdate(task);
                }

                static class TaskRecordEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String taskName;
                    private Integer dicStatus;
                }

                static class BaseService<T> {}

                static class TaskRecordService extends BaseService<TaskRecordEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("saveOrUpdate 应产出预览结果", result)
        assertEquals(
            """
            INSERT INTO task_record_entity (id, task_name, dic_status)
            VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE task_name = VALUES(task_name), dic_status = VALUES(dic_status)
            """.trimIndent(),
            result!!.sql,
        )
        val model = result.batchModel!!
        assertEquals("主键应在 toggleColumns（可选）", listOf("id", "task_name", "dic_status"), model.toggleColumns.map { it.columnName })
        assertEquals("冲突键应为主键", "id", model.conflictColumn)
        assertTrue("fixedColumns 应为空（无必填列）", model.fixedColumns.isEmpty())
    }

    fun testBatchRemoveByIdUnresolvableArgRegistersParamRow() {
        // T-M23-02（用户实测：removeById 没有填参数的地方）：removeById(1001L) 字面量内联（无参数行，
        // 见 testRemoveByIdRendersDelete）；removeById(id) 运行时值 → `?` + `#id` 参数行
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void remove(Long id) {
                    deviceInfoService.removeById(id);
                }

                static class DeviceInfoEntity {
                    private Long id;
                }

                static class BaseService<T> {}

                static class DeviceInfoService extends BaseService<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("removeById 应产出预览结果", result)
        assertEquals(
            """
            DELETE FROM device_info_entity
            WHERE id = ?
            """.trimIndent(),
            result!!.sql,
        )
        assertEquals("removeById 应登记 id 参数行", listOf("id"), result.unresolvedParams)
    }

    fun testSaveEntityRendersInsert() {
        // M19.5（T-M19.5-03，用户实测用例 4 原样）：service.save(new Entity()) 单实体插入——
        // 全列 INSERT（类注释声称支持但注册表缺失，注释与实现不一致，M19.5 补注册）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private CarDeviceInfoService carDeviceInfoService;

                public void save() {
                    carDeviceInfoService.save(new CarDeviceInfoEntity());
                }

                static class CarDeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String deviceCode;
                    private Integer dicStatus;
                }

                static class CarDeviceInfoService {
                    public void save(CarDeviceInfoEntity entity) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("save 应产出预览结果", result)
        assertEquals(
            """
            INSERT INTO car_device_info_entity (id, device_code, dic_status)
            VALUES (?, ?, ?)
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testEntityCrudMethodNamesAreAnchors() {
        // M19.5（T-M19.5-04）：save/getById/listByIds 注册后经 batchOperationMethodNames 集合，
        // WrapperAnchorDetector 场景 D 锚点自动生效（gutter 图标）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void ops() {
                    deviceInfoService.save(entity);
                    deviceInfoService.getById(1001L);
                    deviceInfoService.listByIds(ids);
                }

                static class DeviceInfoEntity {
                    private Long id;
                }

                static class DeviceInfoService {
                    public void save(DeviceInfoEntity entity) {}
                    public void getById(Long id) {}
                    public void listByIds(java.util.List<Long> ids) {}
                }
            }
            """.trimIndent(),
        )
        for (name in listOf("save", "getById", "listByIds")) {
            val call = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
                .first { it.methodExpression.referenceName == name }
            assertSame(
                "$name 方法名应为 gutter 锚点",
                call.methodExpression.referenceNameElement,
                WrapperAnchorDetector.anchorElement(call.methodExpression.referenceNameElement),
            )
        }
    }

    fun testUpdateByIdRendersUpdate() {
        // U12（T-M15-08）：service.updateById(entity) 单实体更新（update-strategy: always 全列语义）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void update() {
                    DeviceInfoEntity entity = new DeviceInfoEntity();
                    deviceInfoService.updateById(entity);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                    private Integer dicStatus;
                }

                static class DeviceInfoService {
                    public void updateById(DeviceInfoEntity entity) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("updateById 应产出预览结果", result)
        assertEquals(
            """
            UPDATE device_info_entity
            SET name = ?, dic_status = ?
            WHERE id = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testSaveOrUpdateRendersMysqlUpsert() {
        // U12（T-M15-08）：service.saveOrUpdate(entity) → 默认（MySQL）方言 ON DUPLICATE KEY UPDATE upsert
        // （T-M43：PG 系方言才渲染 ON CONFLICT；Oracle/SQL Server/DM 降级纯 INSERT，见 upsertFollowsDialect）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private StationOccupyInfoService stationOccupyInfoService;

                public void saveOrUpdate() {
                    StationOccupyInfoEntity entity = new StationOccupyInfoEntity();
                    stationOccupyInfoService.saveOrUpdate(entity);
                }

                static class StationOccupyInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private Long stationId;
                    private Integer dicStatus;
                }

                static class StationOccupyInfoService {
                    public void saveOrUpdate(StationOccupyInfoEntity entity) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("saveOrUpdate 应产出预览结果", result)
        assertEquals(
            """
            INSERT INTO station_occupy_info_entity (id, station_id, dic_status)
            VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE station_id = VALUES(station_id), dic_status = VALUES(dic_status)
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testProjectPostgreSqlOverridesGlobalAutoForBatchUpsert() {
        val global = PluginSettings.getInstance()
        val savedGlobal = global.getState().copy()
        val settings = ProjectPluginSettings.getInstance(project)
        val savedProject = settings.state.copy()
        try {
            global.state.dialectAuto = true
            settings.state.useProjectSettings = true
            settings.state.dialectAuto = false
            settings.state.dialect = "PostgreSQL"
            myFixture.configureByText(
                "Demo.java",
                """
                public class Demo {
                    private StoreService service;
                    public void save() { service.saveOrUpdate(new StoreEntity()); }
                    static class StoreEntity {
                        @com.baomidou.mybatisplus.annotation.TableId private Long id;
                        private String status;
                    }
                    static class StoreService { void saveOrUpdate(StoreEntity entity) {} }
                }
                """.trimIndent(),
            )
            val result = newService().preview(anchor())
            assertNotNull(result)
            assertTrue("项目级 PG 方言应渲染冲突子句：${result!!.sql}",
                result.sql.contains("ON CONFLICT (id) DO UPDATE"))
            assertTrue("PG 不得输出 MySQL upsert：${result.sql}",
                !result.sql.contains("ON DUPLICATE KEY UPDATE"))
        } finally {
            settings.loadState(savedProject)
            global.loadState(savedGlobal)
        }
    }

    // ==================================================================
    // T-WriteFamily（R4）：空 Wrapper 写族（delete(null) / update(entity, null) / remove(null)）
    // ==================================================================

    fun testDeleteNullWithoutLogicDeleteRendersDeleteWithoutWhereAndWarning() {
        // R4：`BaseMapper.delete(Wrapper)` 实参为 null → 无 WHERE 的 `DELETE FROM 表`（对齐 MP
        // SqlMethod.DELETE：wrapper 为 null 时 sqlWhereEntityWrapper 不产生 WHERE）+ 全表删除警示
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoMapper deviceInfoMapper;

                public void remove() {
                    deviceInfoMapper.delete(null);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                }

                interface BaseMapper<T> {}

                interface DeviceInfoMapper extends BaseMapper<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("delete(null) 应产出预览结果", result)
        assertEquals("DELETE FROM device_info_entity", result!!.sql)
        assertTrue("null Wrapper 不得生成 WHERE：${result.sql}", !result.sql.contains("WHERE"))
        assertEquals(
            "无 WHERE 删除应带全表删除警示",
            "⚠ 无 WHERE 条件，将删除全表数据！",
            result.warning,
        )
        assertTrue("无参数占位", result.unresolvedParams.isEmpty())
    }

    fun testDeleteNullWithTableLogicRendersSoftDelete() {
        // R4：实体含 @TableLogic → 删除转软删除 `UPDATE 表 SET deleted = 1 WHERE deleted = 0`
        // （对齐 MP SqlMethod.LOGIC_DELETE：逻辑删除片段无条件追加 TableInfo.getLogicDeleteSql）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoMapper deviceInfoMapper;

                public void remove() {
                    deviceInfoMapper.delete(null);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }

                interface BaseMapper<T> {}

                interface DeviceInfoMapper extends BaseMapper<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("delete(null) 应产出预览结果", result)
        assertEquals(
            """
            UPDATE device_info_entity
            SET deleted = 1
            WHERE deleted = 0
            """.trimIndent(),
            result!!.sql,
        )
        assertEquals("软删除仍属全表写操作，应带警示", "⚠ 无 WHERE 条件，将删除全表数据！", result.warning)
    }

    fun testUpdateEntityNullWrapperRendersUpdateWithoutWhere() {
        // R4：`BaseMapper.update(T, Wrapper)` 第二参为 null → SET 走实体列（与 updateById 同口径：
        // 排除主键/@Version），**不得生成任何 WHERE**（BatchModel 的 UPDATE 渲染把主键硬编码成 WHERE，
        // 故本分支直接拼 SQL、batchModel=null）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoMapper deviceInfoMapper;

                public void update(DeviceInfoEntity entity) {
                    deviceInfoMapper.update(entity, null);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                    private Integer dicStatus;
                }

                interface BaseMapper<T> {}

                interface DeviceInfoMapper extends BaseMapper<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("update(entity, null) 应产出预览结果", result)
        assertEquals(
            """
            UPDATE device_info_entity
            SET name = ?, dic_status = ?
            """.trimIndent(),
            result!!.sql,
        )
        assertTrue("null Wrapper 更新不得生成 WHERE：${result.sql}", !result.sql.contains("WHERE"))
        assertEquals(
            "实体列参数行（与 updateById 同口径）",
            listOf("entity.name", "entity.dic_status"),
            result.unresolvedParams,
        )
        assertEquals(
            "无 WHERE 更新应带全表更新警示",
            "⚠ 无 WHERE 条件，将更新全表数据！",
            result.warning,
        )
        assertNull("无 WHERE 分支不提供列勾选模型", result.batchModel)
    }

    fun testRemoveNullRendersDeleteWithoutWhereAndWarning() {
        // R4（D-4）：`IService.remove(Wrapper)` 实参为 null → 与 delete(null) 同模板（无 WHERE 删除 + 全表警示）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void remove() {
                    deviceInfoService.remove(null);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                }

                interface IService<T> {}

                interface DeviceInfoService extends IService<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("remove(null) 应产出预览结果", result)
        assertEquals("DELETE FROM device_info_entity", result!!.sql)
        assertTrue("null Wrapper 不得生成 WHERE：${result.sql}", !result.sql.contains("WHERE"))
        assertEquals("remove(null) 应带全表删除警示", "⚠ 无 WHERE 条件，将删除全表数据！", result.warning)
    }

    fun testDeleteWrapperVariableStillRendersWrapperConditions() {
        // R4 回归：非 null Wrapper（变量形态）不走空 Wrapper 分支——Wrapper 链路径照旧出 DELETE + WHERE
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoMapper deviceInfoMapper;

                public void remove() {
                    com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DeviceInfoEntity> w =
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DeviceInfoEntity>()
                            .eq(DeviceInfoEntity::getId, 1001L);
                    deviceInfoMapper.delete(w);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                }

                interface BaseMapper<T> {}

                interface DeviceInfoMapper extends BaseMapper<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val variableId = PsiTreeUtil.findChildOfType(myFixture.file, PsiLocalVariable::class.java)!!.nameIdentifier!!
        val result = newService().preview(variableId)
        assertNotNull("delete(wrapper) 应仍走 Wrapper 链路径并产出预览", result)
        assertTrue("应生成 DELETE：${result!!.sql}", result.sql.startsWith("DELETE FROM"))
        assertTrue("Wrapper 条件应保留 WHERE：${result.sql}", result.sql.contains("WHERE"))
        assertNull("有 Wrapper 条件时不带全表警示", result.warning)
    }
}
