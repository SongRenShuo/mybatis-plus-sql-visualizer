package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
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

/**
 * SqlPreviewService 预览渲染用例（testPreview*：未解析参数/变量追踪/Join/Batch/静态工厂等，自 SqlPreviewServiceTest 拆出）。
 * （T-M53-II 2026-10-10：逐字符原样搬运，零逻辑改动。）
 */
class SqlPreviewServicePreviewTest: BasePlatformTestCase() {

    fun testPreviewWithUnresolvedParam() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query(Long deviceId) {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, deviceId);
                }

                static class DeviceInfoEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull(result)
        // 方法参数无法静态求值 → 收集为未解析参数
        assertEquals(listOf("deviceId"), result!!.unresolvedParams)
        assertEquals("-- 参数对照: [deviceId=?]", result.paramTable)
    }

    fun testPreviewVariableTraced() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    Long stationId = 101L;
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getStationId, stationId);
                }

                static class DeviceInfoEntity {
                    private Long stationId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull(result)
        // 变量赋值追踪内联：stationId=101L → 101
        assertEquals("station_id = 101", result!!.sql.substringAfter("WHERE "))
        assertEquals(emptyList<String>(), result.unresolvedParams)
    }

    fun testPreviewNonWrapperReturnsNull() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    String s = "hello";
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNull("非 Wrapper 代码应返回 null", result)
    }

    fun testPreviewUpdate() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

            public class Demo {
                public void update() {
                    LambdaUpdateWrapper<DeviceInfoEntity> w = new LambdaUpdateWrapper<DeviceInfoEntity>()
                        .set(DeviceInfoEntity::getStationId, 101L)
                        .eq(DeviceInfoEntity::getId, 1001L);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Long stationId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出 UPDATE 预览", result)
        assertEquals(
            """
            UPDATE device_info_entity
            SET station_id = 101
            WHERE id = 1001
            """.trimIndent(),
            result!!.sql,
        )
        // 有 WHERE → 无警示
        assertNull(result.warning)
    }

    fun testPreviewDeleteWithoutWhereWarning() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.delete.LambdaDeleteWrapper;

            public class Demo {
                public void delete() {
                    LambdaDeleteWrapper<DeviceInfoEntity> w = new LambdaDeleteWrapper<DeviceInfoEntity>();
                }

                static class DeviceInfoEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出 DELETE 预览", result)
        assertEquals("DELETE FROM device_info_entity", result!!.sql)
        // 无 WHERE → 红色警示（F-10 全表删除风险）
        assertNotNull("应提示全表删除风险", result.warning)
    }

    fun testPreviewMpjJoin() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.github.yulichang.wrapper.MPJLambdaWrapper;

            public class Demo {
                public void query() {
                    MPJLambdaWrapper<DeviceInfoEntity> w = new MPJLambdaWrapper<DeviceInfoEntity>()
                        .selectAs(DeviceInfoEntity::getId, "device_id")
                        .leftJoin(StationSchemeMerchantInfo.class, on(DeviceInfoEntity::getStationId, StationSchemeMerchantInfo::getSchemeId))
                        .eq(DeviceInfoEntity::getId, 1001L);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Long shareSchemeId;
                }

                static class StationSchemeMerchantInfo {
                    private Long schemeId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出 MPJ 联表预览", result)
        assertEquals(
            """
            SELECT t.id AS device_id
            FROM device_info_entity t
            LEFT JOIN station_scheme_merchant_info stationSchemeMerchantInfo ON t.station_id = stationSchemeMerchantInfo.scheme_id
            WHERE t.id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testPreviewMpjJoinSelectAsMethodRef() {
        // P01 形态：selectAs(Entity::getX, VO::getY) 方法引用别名（第一参为主表字段；关联表列归属在 T-M7-05 覆盖）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.github.yulichang.wrapper.MPJLambdaWrapper;

            public class Demo {
                public void query() {
                    MPJLambdaWrapper<DeviceInfoEntity> w = new MPJLambdaWrapper<DeviceInfoEntity>()
                        .selectAs(DeviceInfoEntity::getId, DeviceDetailVO::getDeviceId)
                        .selectAs(DeviceInfoEntity::getStationId, DeviceDetailVO::getStationId)
                        .leftJoin(StationInfoEntity.class, on(DeviceInfoEntity::getStationId, StationInfoEntity::getId))
                        .eq(DeviceInfoEntity::getId, 1001L);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Long stationId;
                }

                static class StationInfoEntity {
                    private Long id;
                }

                static class DeviceDetailVO {
                    private Long deviceId;
                    private Long stationId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出 MPJ 联表预览", result)
        assertEquals(
            """
            SELECT t.id AS deviceId, t.station_id AS stationId
            FROM device_info_entity t
            LEFT JOIN station_info_entity stationInfoEntity ON t.station_id = stationInfoEntity.id
            WHERE t.id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testPreviewMpjLambdaAliasConstructor() {
        // C4/P11 全链路：new MPJLambdaWrapper<>(Class, "apply_info") + 四参带别名 join + 关联实体注解解析
        myFixture.configureByText(
            "Demo.java",
            """
            import com.github.yulichang.wrapper.MPJLambdaWrapper;

            public class Demo {
                public void query() {
                    MPJLambdaWrapper<InvoiceApplyEntity> w = new MPJLambdaWrapper<>(InvoiceApplyEntity.class, "apply_info")
                        .innerJoin(InvoiceApplyOrderRelEntity.class, "rel",
                            InvoiceApplyOrderRelEntity::getApplyId, InvoiceApplyEntity::getId)
                        .eq(InvoiceApplyEntity::getId, 1001L);
                }

                static class InvoiceApplyEntity {
                    private Long id;
                }

                static class InvoiceApplyOrderRelEntity {
                    private Long applyId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出带表别名的 MPJ 预览", result)
        assertEquals(
            """
            SELECT apply_info.id
            FROM invoice_apply_entity apply_info
            INNER JOIN invoice_apply_order_rel_entity rel ON rel.apply_id = apply_info.id
            WHERE apply_info.id = 1001
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testPreviewLastLimitConcat() {
        // P12：last("LIMIT " + N) 字符串拼接表达式 → LIMIT 100
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    int batchSize = 100;
                    LambdaQueryWrapper<OrderMonitorRecordEntity> w = new LambdaQueryWrapper<OrderMonitorRecordEntity>()
                        .eq(OrderMonitorRecordEntity::getOrderId, 1L)
                        .last("LIMIT " + batchSize);
                }

                static class OrderMonitorRecordEntity {
                    private Long orderId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出带 LIMIT 的预览", result)
        assertEquals(
            """
            SELECT order_id
            FROM order_monitor_record_entity
            WHERE order_id = 1
            LIMIT 100
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testPreviewMpjFactoryChain() {
        // P03：baseJoinWrapper() 工厂方法返回 wrapper，调用方继续 .eq() 续链 —— 需跨方法追踪
        myFixture.configureByText(
            "Demo.java",
            """
            import com.github.yulichang.wrapper.MPJLambdaWrapper;

            public class Demo {
                public void query() {
                    MPJLambdaWrapper<CouponEntity> wrapper = baseJoinWrapper()
                        .eq(CouponEntity::getCouponCode, "CODE1");
                }

                private MPJLambdaWrapper<CouponEntity> baseJoinWrapper() {
                    return new MPJLambdaWrapper<CouponEntity>()
                        .selectAll(CouponEntity.class)
                        .leftJoin(MerchantEntity.class, MerchantEntity::getId, CouponEntity::getMerchantId);
                }

                static class CouponEntity {
                    private String couponCode;
                    private Long merchantId;
                }

                static class MerchantEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出跨方法工厂链的预览", result)
        assertEquals(
            """
            SELECT t.coupon_code, t.merchant_id
            FROM coupon_entity t
            LEFT JOIN merchant_entity merchantEntity ON merchantEntity.id = t.merchant_id
            WHERE t.coupon_code = 'CODE1'
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testPreviewMpjLambdaQueryWrapper() {
        // P17：IService.list(new MPJLambdaQueryWrapper<...>().select(...).eq(...)) 单表查询
        myFixture.configureByText(
            "Demo.java",
            """
            import com.github.yulichang.query.MPJLambdaQueryWrapper;

            public class Demo {
                public void query() {
                    subAccountStationService.list(new MPJLambdaQueryWrapper<SubAccountStationEntity>()
                        .select(SubAccountStationEntity::getStationId)
                        .eq(SubAccountStationEntity::getAccountId, 100L));
                }

                static class SubAccountStationEntity {
                    private Long stationId;
                    private Long accountId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出 MPJLambdaQueryWrapper 预览", result)
        assertEquals(
            """
            SELECT station_id
            FROM sub_account_station_entity
            WHERE account_id = 100
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testPreviewStaticFactoryGetOne() {
        // 用户反馈片段：merchantService.getOne(Wrappers.<T>lambdaQuery().select(9列).eq(列, dto.getPhone()))
        // 静态工厂内联链（无 new、无 Wrapper 变量）应完整转换
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            public class Demo {
                public void query() {
                    MerchantEntity merchant = merchantService.getOne(Wrappers.<MerchantEntity>lambdaQuery()
                            .select(
                                    MerchantEntity::getId,
                                    MerchantEntity::getMerchantCode,
                                    MerchantEntity::getName,
                                    MerchantEntity::getPhone,
                                    MerchantEntity::getDicMerchantType,
                                    MerchantEntity::getPassword,
                                    MerchantEntity::getDicStatus,
                                    MerchantEntity::getParentId,
                                    MerchantEntity::getSubAccountName)
                            .eq(MerchantEntity::getPhone, dto.getPhone()));
                }

                static class MerchantEntity {
                    private Long id;
                    private String merchantCode;
                    private String name;
                    private String phone;
                    private Integer dicMerchantType;
                    private String password;
                    private Integer dicStatus;
                    private Long parentId;
                    private String subAccountName;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出 Wrappers 静态工厂链预览", result)
        assertEquals(
            """
            SELECT id, merchant_code, name, phone, dic_merchant_type, password, dic_status, parent_id, sub_account_name
            FROM merchant_entity
            WHERE phone = ?
            """.trimIndent(),
            result!!.sql,
        )
        // dto.getPhone() 经 getter→字段引用转换后解析为 phone（master 分支 getter 方法字段名修复）
        assertEquals(listOf("phone"), result.unresolvedParams)
        assertEquals(cn.song.mybatisplus.visualizer.model.WrapperType.LAMBDA_QUERY, result.wrapperType)
    }

    fun testPreviewPastedSnippetWithTableNameAnnotation() {
        // 粘贴片段含实体 + @TableName 注解：注解类不可解析，走短名兜底（M9 工具窗口反馈）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            @com.baomidou.mybatisplus.annotation.TableName("t_user")
            public class UserEntity {
                private Long id;
            }

            public class Demo {
                public void query() {
                    merchantService.getOne(Wrappers.<UserEntity>lambdaQuery()
                            .eq(UserEntity::getId, 1L));
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(myFixture.file)
        assertNotNull("应识别 @TableName 注解（短名兜底）", result)
        assertEquals(
            """
            SELECT id
            FROM t_user
            WHERE id = 1
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testPreviewPastedSnippetWithoutEntityClass() {
        // 模拟工具窗口粘贴：片段无实体类定义，按类型参数类名命名策略兜底表名（M9 增强）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            public class Demo {
                public void query() {
                    merchantService.getOne(Wrappers.<MerchantEntity>lambdaQuery()
                            .select(MerchantEntity::getId)
                            .eq(MerchantEntity::getPhone, "138"));
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(myFixture.file)
        assertNotNull("无实体类定义也应产出预览（命名策略兜底）", result)
        assertEquals(
            """
            SELECT id
            FROM merchant_entity
            WHERE phone = '138'
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testPreviewBatchUpdate() {
        myFixture.configureByText(
            "Demo.java",
            """
            import java.util.List;

            public class Demo {
                public void batch(List<DeviceInfoEntity> list) {
                    DeviceInfoService service = new DeviceInfoService();
                    service.updateBatchById(list);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Long stationId;
                    private Long shareSchemeId;
                }

                static class DeviceInfoService {
                    public void updateBatchById(List<DeviceInfoEntity> list) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应识别批量更新调用", result)
        assertEquals(
            """
            UPDATE device_info_entity
            SET station_id = ?, share_scheme_id = ?
            WHERE id = ?
            """.trimIndent(),
            result!!.sql,
        )
        // 参数组说明（F-15）
        assertNotNull(result.paramTable)
    }

    fun testPreviewBatchInsert() {
        myFixture.configureByText(
            "Demo.java",
            """
            import java.util.List;

            public class Demo {
                public void batch(List<DeviceInfoEntity> list) {
                    DeviceInfoService service = new DeviceInfoService();
                    service.saveBatch(list);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private String name;
                }

                static class DeviceInfoService {
                    public void saveBatch(List<DeviceInfoEntity> list) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应识别批量插入调用", result)
        assertEquals(
            """
            INSERT INTO device_info_entity (id, name)
            VALUES (?, ?)
            """.trimIndent(),
            result!!.sql,
        )
    }

    // ------------------------------------------------------------------
    // D264 修0 / 修5：Wrapper 条件链解析失败的可见化
    // ------------------------------------------------------------------

    /**
     * D264 修0：`selectList(FilterUtil.buildQuery(1))`——实参静态可见是 Wrapper，但 Wrapper 链路径
     * 定位不到链（工具方法返回裸变量，属修2 范畴）。**修复前**批量模板接手，产出
     * `SELECT id, status, device_name FROM device_info_entity`（无 WHERE）的静默错误 SQL。
     * 现在必须：不产 SQL、给出明确原因。
     */
    fun testD264WrapperArgButChainUnresolvedRefusesBatchTemplate() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = FilterUtil.buildQuery(1);
                    mapper.selectList(w);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Integer status;
                    private String deviceName;
                }

                static class FilterUtil {
                    static LambdaQueryWrapper<DeviceInfoEntity> buildQuery(Integer status) {
                        LambdaQueryWrapper<DeviceInfoEntity> q = new LambdaQueryWrapper<>();
                        q.eq(DeviceInfoEntity::getStatus, status);
                        return q;
                    }
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("必须给出结果（而不是静默 return null），否则用户不知道发生了什么", result)
        assertTrue("不得产出无 WHERE 的全表 SQL：\n${result!!.sql}", !result.sql.contains("FROM device_info_entity"))
        assertEquals("SQL 必须为空（拒绝产出）", "", result.sql)
        assertNotNull("必须带明确提示", result.warning)
        val warning = result.warning!!
        assertTrue("提示要点明「检测到 Wrapper 但无法解析条件链」：$warning", warning.contains("无法解析其条件链"))
        assertTrue("提示要点明方法名：$warning", warning.contains("selectList"))
    }

    /**
     * D264 修0（wrapper 路径最坏面）：`return q` 形态下链被定位到，但链上收集到的
     * 只有工具方法名（`[buildQuery]`）、一个可识别条件都没有——同样必须拒绝产出，不能给无 WHERE SQL。
     */
    fun testD264ConditionlessChainWithUnknownMethodRefusesPreview() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = FilterUtil.buildQuery(1);
                    mapper.selectList(w);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Integer status;
                }

                static class FilterUtil {
                    static LambdaQueryWrapper<DeviceInfoEntity> buildQuery(Integer status) {
                        LambdaQueryWrapper<DeviceInfoEntity> q = new LambdaQueryWrapper<>();
                        q.eq(DeviceInfoEntity::getStatus, status);
                        return q;
                    }
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull(result)
        val sql = result!!.sql
        assertTrue("产物不得是无条件的全表 SELECT：\n$sql", !sql.startsWith("SELECT ") || sql.contains("WHERE") || sql.isEmpty())
        assertTrue("拒绝时必须留下原因：$sql / ${result.warning}", sql.isEmpty() || result.warning != null)
    }

    /**
     * D264 修5：自研 wrapper 的语义化链方法（`statusEq`）——收集层能过、解析层不认。
     * 修复后条件仍然缺（不猜语义是刻意的），但**必须出现在 warning 里**，用户能看到少了什么。
     */
    fun testD264UnknownChainMethodSurfacesInWarning() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class MyWrapper<T> extends LambdaQueryWrapper<T> {
                public MyWrapper<T> statusEq(Integer status) { return this; }
            }

            public class Demo {
                public void query() {
                    MyWrapper<DeviceInfoEntity> w = new MyWrapper<>();
                    w.eq(DeviceInfoEntity::getId, 1L);
                    w.statusEq(1);
                    mapper.selectList(w);
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Integer status;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("链上已有 eq 条件，应正常产出预览", result)
        assertTrue("既有条件必须保留：\n${result!!.sql}", result.sql.contains("WHERE") && result.sql.contains("id = 1"))
        val warning = result.warning
        assertNotNull("未识别方法必须可见（不能静默丢条件）：${result.sql}", warning)
        assertTrue("warning 要点名 statusEq：$warning", warning!!.contains("statusEq"))
        assertTrue("warning 要说明「条件未出现在 SQL 中」：$warning", warning.contains("无法静态解析"))
    }

    /** D264 修5 反向守卫：全部方法都识别时**不挂**任何警告（避免噪音式误报）。 */
    fun testD264KnownMethodsProduceNoUnknownMethodWarning() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    mapper.selectList(w);
                }

                static class DeviceInfoEntity {
                    private Long id;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull(result)
        assertNull("全部方法已识别时不得有 warning（避免把正常预览变成告警）：${result!!.warning}", result.warning)
    }

    override fun setUp() {
        super.setUp()
        // O-97 治理（T-TestPerf15）：进态恢复出厂默认，截断跨类全局设置态泄漏
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
    }

    private fun newService() = SqlPreviewService(project)

    /** T-TenantLine：每个测试结束后重置多租户配置为默认，防止状态泄漏。 */
    override fun tearDown() {
        PluginSettings.getInstance().state.injectTenantLine = false
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
}
