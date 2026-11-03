package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.sql.interceptor.PaginationSimulator
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-R12 T-PaginationSim 分页拦截器模拟测试（开发计划 20260929-1400）：
 *
 *  - T-01 mapper 方法 Page 参数解析（缺口 A）：BaseMapper 3.5.17 IPage 重载（selectList/selectMaps/
 *    selectMapsPage，本地 jar 反编译实证）与 XML/注解语句路径的 Page/IPage 形参 → LIMIT 追加；
 *  - T-02 COUNT 包裹预览（缺口 B，D1=A 第二条目 + D2=A 基础形态）：`SELECT COUNT(*) FROM (原SQL) TOTAL`
 *    （MP 3.5.17 SqlParserUtils.getOriginalCountSql 逐字节形态，反编译实证）；
 *  - D3=A：Page.searchCount=false（`new Page<>(1,10,false)`）跳过 COUNT 产出；
 *  - T-CountSimToggle（D129）：COUNT 子开关关 → LIMIT 在而 COUNT 无；父开关关子开关开 → 全无（父优先）；
 *  - 边界：count/exists 消费不产 COUNT（复用 T-M29-02/03 口径）、last("LIMIT n") 与分页并存以分页为准
 *    （单 LIMIT）、XML 手写 LIMIT 不重复追加（V13c 保守口径）、方言矩阵 LIMIT 形态。
 */
class PaginationSimTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // O-97 治理（T-TestPerf15）：进态恢复出厂默认，截断跨类全局设置态泄漏
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
    }

    private fun newService() = SqlPreviewService(project)

    private fun anchor(): PsiElement {
        val file = myFixture.file
        val methodCall = PsiTreeUtil.findChildOfType(file, PsiMethodCallExpression::class.java)
        if (methodCall != null) return methodCall
        val variable = PsiTreeUtil.findChildOfType(file, PsiLocalVariable::class.java)
        if (variable != null) return variable
        return PsiTreeUtil.findChildOfType(file, PsiLiteralExpression::class.java)
            ?: PsiTreeUtil.getDeepestFirst(file)
    }

    override fun tearDown() {
        PluginSettings.getInstance().state.dialectAuto = true
        // T-BatchTestLeak（O-93）：setManualDialect 写入的 state.dialect 也须还原——psi 渲染链
        // （BatchOperationConverter → currentDialect(project=null)）在 auto=true 时仍取全局手动值，
        // 残留的 "Oracle"/"PostgreSQL" 会污染同 JVM 后续测试类（曾致 SqlPreviewServiceBatchTest
        // 5 例方言/引用 ComparisonFailure 随装箱顺序间歇爆发）。
        PluginSettings.getInstance().state.dialect = PluginSettings.DEFAULT_DIALECT
        PluginSettings.getInstance().state.paginationSimEnabled = true
        PluginSettings.getInstance().state.paginationCountEnabled = true
        super.tearDown()
    }

    private companion object {
        val USER_MAPPER_XML = """
            <mapper namespace="com.example.UserMapper">
              <select id="selectPageUsers">
                SELECT id, name FROM t_user WHERE name = #{name}
              </select>
            </mapper>
        """.trimIndent()
    }

    private fun setManualDialect(name: String) {
        val state = PluginSettings.getInstance().state
        state.dialectAuto = false
        state.dialect = name
    }

    // ------------------------------------------------------------------
    // T-01a：BaseMapper IPage 重载（Wrapper 实参路径）
    // ------------------------------------------------------------------

    fun testMapperSelectListWithPageLiteralAppendsLimit() {
        // BaseMapper.selectList(IPage, Wrapper)（3.5.17 反编译实证重载）→ LIMIT 追加
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    mapper.selectList(new Page<>(1, 20), w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出预览结果", result)
        assertEquals(
            "selectList(Page, wrapper) 应按字面量 Page 追加 LIMIT 0, 20",
            "SELECT id FROM device_info_entity WHERE id = 1001 LIMIT 0, 20",
            result!!.compactSql,
        )
    }

    fun testMapperSelectMapsWithPageRuntimeArgsAppendsPlaceholderLimit() {
        // selectMaps(IPage, Wrapper)：运行时 current/size → LIMIT ?, ?（对齐 T-M29-03 占位口径）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query(long pageNum, long pageSize) {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    mapper.selectMaps(new Page<>(pageNum, pageSize), w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出预览结果", result)
        assertTrue(
            "selectMaps(Page, wrapper) 应追加分页 LIMIT 占位：${result!!.compactSql}",
            result.compactSql.endsWith("LIMIT ?, ?"),
        )
        assertEquals(
            "分页 offset 参数应登记计算公式来源",
            listOf("(pageNum - 1) * pageSize", "pageSize"),
            result.unresolvedParams.takeLast(2),
        )
    }

    fun testSelectListWithoutPageArgStaysUnlimited() {
        // 负例守卫：selectList(wrapper) 无 Page 实参 → 不追加 LIMIT（零回归）
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

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("应产出预览结果", result)
        assertEquals(
            "无 Page 实参不追加分页",
            "SELECT id FROM device_info_entity WHERE id = 1001",
            result!!.compactSql,
        )
    }

    // ------------------------------------------------------------------
    // T-Exec262ReadLock B：last("limit …") 不是分页消费，不得附赠 COUNT
    // ------------------------------------------------------------------

    fun testSelectListWithLastLimitProducesNoCountSql() {
        // 2026-11-02 用户实测形态：selectList(w.last("limit 100")) 预览凭空多一条 COUNT。
        // COUNT 只跟随 Page/IPage 分页消费；last 给的 LIMIT 照常渲染、但不产 COUNT。
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L)
                        .last("limit 100");
                    mapper.selectList(w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull(result)
        assertTrue("last 的 LIMIT 照常渲染：${result!!.compactSql}", result.compactSql.endsWith("LIMIT 100"))
        assertNull("last 的 LIMIT 不是分页消费，不产 COUNT 包裹", result.countSql)
    }

    fun testSelectListWithLastLimitVariableProducesNoCountSql() {
        // 用户现场形态：last("limit " + n) → LIMIT ?（形参值静态不可得），同样不产 COUNT
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query(int n) {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L)
                        .last("limit " + n);
                    mapper.selectList(w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull(result)
        assertTrue("last 拼接口径渲染占位 LIMIT：${result!!.compactSql}", result.compactSql.endsWith("LIMIT ?"))
        assertNull("占位 LIMIT 也不是分页消费，不产 COUNT 包裹", result.countSql)
    }

    fun testSelectPageWithLastLimitStillProducesCountSql() {
        // 正对照（防本闸误杀）：真分页消费 + last 并存 → 以分页为准，COUNT 仍在
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L)
                        .last("LIMIT 1");
                    mapper.selectList(new Page<>(3, 15), w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull(result)
        assertEquals(
            "真分页消费仍产 COUNT 包裹",
            "SELECT COUNT(*) FROM (SELECT id FROM device_info_entity WHERE id = 1001) TOTAL",
            result!!.countSql,
        )
    }

    // ------------------------------------------------------------------
    // T-01b：XML 语句路径（mapper 接口 Page/IPage 形参）
    // ------------------------------------------------------------------

    private fun configureXmlMapperWithPageParam() {
        myFixture.addFileToProject(
            "com/example/UserMapper.java",
            """
            package com.example;

            import java.util.List;

            public class User {
                private Long id;
                private String name;
            }

            public interface UserMapper {
                List<User> selectPageUsers(Page page, String name);
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject("com/example/UserMapper.xml", USER_MAPPER_XML)
    }

    /**
     * T-DevAcceptFix ③（20261006 验收）：面板渲染的分页段——主 SQL 与 COUNT 语句各自以 `;` 收尾。
     * 缺分号形态：原 appendCountSection 主 SQL 尾直拼注释头，复制到控制台执行两条语句粘连。
     *
     * 两段断言：raw 形态（formatSql 关，确定性逐字）钉死分号位置；格式化形态下分号不得被格式化
     * 吞掉（容忍空白差异的形态断言）——用户复制走的是哪一种形态都必须带分号。
     */
    fun testDevAcceptFix03_分页渲染段两条语句各自分号收尾() {
        configureXmlMapperWithPageParam()
        val result = XmlPreviewService.INSTANCE.preview(USER_MAPPER_XML, "selectPageUsers", project)
        assertNotNull("前置：应产出 COUNT 包裹", result!!.getCountSql())
        val settings = PluginSettings.getInstance()
        val oldFormat = runCatching { settings.state.formatSql }.getOrDefault(true)
        val panel = SqlPreviewPanel(project)
        Disposer.register(testRootDisposable, panel)
        try {
            // ① raw 形态：主 SQL 尾分号 + 注释头 + COUNT 语句尾分号（逐字）
            runCatching { settings.state.formatSql = false }
            SqlPreviewPanelResultViewKt.showResult(panel, result)
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            val raw = panel.lastRenderedSqlForTest()
            assertNotNull("面板应产出渲染文本", raw)
            val header = MpSqlVisualizerBundle.message("panel.pagination.count.section")
            assertTrue(
                "主 SQL 应以分号收尾再接 COUNT 段（分号缺失验收缺陷），实际渲染=${raw!!}",
                raw!!.contains(";\n\n-- $header\n"),
            )
            assertTrue("COUNT 语句尾应补分号", raw.trimEnd().endsWith("TOTAL;"))

            // ② 格式化形态：分号不得被格式化吞掉（放宽空白）
            runCatching { settings.state.formatSql = true }
            panel.renderSqlForTest()
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            val formatted = panel.lastRenderedSqlForTest()
            assertNotNull(formatted)
            assertTrue(
                "格式化后主 SQL 与 COUNT 注释头之间仍须有分号，实际渲染=${formatted!!}",
                Regex(";\\s*--\\s*" + Regex.escape(header)).containsMatchIn(formatted),
            )
        } finally {
            runCatching { settings.state.formatSql = oldFormat }
            // 面板清场后再释放（组件不在容器，removeNotify 不触发，须先 clear 走 releaseEditor）
            runCatching { SqlPreviewPanelSqlViewKt.clear(panel) }
            runCatching { Disposer.dispose(panel) }
        }
    }

    fun testXmlStatementWithMapperPageParamAppendsLimitAndCount() {
        configureXmlMapperWithPageParam()
        val result = XmlPreviewService.INSTANCE.preview(USER_MAPPER_XML, "selectPageUsers", project)
        assertTrue(
            "mapper 方法带 Page 形参 → 语句预览应追加分页 LIMIT：${result.sql}",
            result.sql.contains("LIMIT ?, ?"),
        )
        assertEquals(
            "LIMIT 行存入 paginationLimitLine（面板模型级重渲染回带用，20261008 验收缺陷 #1）",
            "LIMIT ?, ?",
            result.getPaginationLimitLine(),
        )
        assertEquals(
            "LIMIT 占位参数登记（offset 公式 + size）",
            listOf("name", "(page.current - 1) * page.size", "page.size"),
            result.unresolvedParams,
        )
        assertEquals(
            "分页 COUNT 包裹（MP 3.5.17 基础形态，包裹不含 LIMIT 的原语句）",
            "SELECT COUNT(*) FROM (SELECT id, name FROM t_user WHERE name = ?) TOTAL",
            result.getCountSql(),
        )
    }

    fun testXmlStatementWithIPageParamAlsoPaginates() {
        // IPage 形参同识别（MP 语义：拦截器按 IPage 参数定位）
        myFixture.addFileToProject(
            "com/example/OrderMapper.java",
            """
            package com.example;

            public interface OrderMapper {
                java.util.List<Order> selectOrders(IPage page);
            }
            """.trimIndent(),
        )
        val xmlText = """
            <mapper namespace="com.example.OrderMapper">
              <select id="selectOrders">SELECT id FROM t_order</select>
            </mapper>
        """.trimIndent()
        myFixture.addFileToProject("com/example/OrderMapper.xml", xmlText)
        val result = XmlPreviewService.INSTANCE.preview(xmlText, "selectOrders", project)
        assertTrue("IPage 形参同样追加分页：${result.sql}", result.sql.contains("LIMIT ?, ?"))
        assertNotNull("IPage 形参同样产出 COUNT 包裹", result.getCountSql())
    }

    fun testXmlStatementWithoutPageParamStaysUnlimited() {
        // 负例：无 Page 形参的 XML 语句不追加 LIMIT、不产 COUNT（零回归）
        myFixture.addFileToProject(
            "com/example/PlainMapper.java",
            """
            package com.example;

            public interface PlainMapper {
                java.util.List<User> selectUsers(String name);
            }
            """.trimIndent(),
        )
        val xmlText = """
            <mapper namespace="com.example.PlainMapper">
              <select id="selectUsers">SELECT id FROM t_user WHERE name = #{name}</select>
            </mapper>
        """.trimIndent()
        myFixture.addFileToProject("com/example/PlainMapper.xml", xmlText)
        val result = XmlPreviewService.INSTANCE.preview(xmlText, "selectUsers", project)
        assertEquals(
            "无 Page 形参不追加分页",
            "SELECT id FROM t_user WHERE name = ?",
            result.sql.trim(),
        )
        assertNull("无分页不产 COUNT 包裹", result.getCountSql())
    }

    fun testXmlStatementWithHandWrittenLimitNotDuplicated() {
        // V13c 边界（保守口径）：XML 已手写 LIMIT → 模拟不重复追加（偏离 MP 运行时字面拼接，登记）
        configureXmlMapperWithPageParam()
        myFixture.addFileToProject(
            "com/example/LimitMapper.java",
            """
            package com.example;

            public interface LimitMapper {
                java.util.List<User> selectTop(Page page);
            }
            """.trimIndent(),
        )
        val xmlText = """
            <mapper namespace="com.example.LimitMapper">
              <select id="selectTop">SELECT id FROM t_user LIMIT 100</select>
            </mapper>
        """.trimIndent()
        myFixture.addFileToProject("com/example/LimitMapper.xml", xmlText)
        val result = XmlPreviewService.INSTANCE.preview(xmlText, "selectTop", project)
        assertEquals(
            "手写 LIMIT 不重复追加（LIMIT 恰好一次）",
            1,
            Regex("(?i)\\bLIMIT\\b").findAll(result.sql).count(),
        )
        assertNull("手写 LIMIT 语句不产模拟 COUNT（预览按原语句语义）", result.getCountSql())
    }

    fun testXmlUpdateStatementWithPageParamNoLimit() {
        // 语句类型守卫：UPDATE/DELETE 不追加分页（分页仅 SELECT）
        myFixture.addFileToProject(
            "com/example/UpdMapper.java",
            """
            package com.example;

            public interface UpdMapper {
                int touch(Page page);
            }
            """.trimIndent(),
        )
        val xmlText = """
            <mapper namespace="com.example.UpdMapper">
              <update id="touch">UPDATE t_user SET name = #{name}</update>
            </mapper>
        """.trimIndent()
        myFixture.addFileToProject("com/example/UpdMapper.xml", xmlText)
        val result = XmlPreviewService.INSTANCE.preview(xmlText, "touch", project)
        assertTrue("UPDATE 不追加分页：${result.sql}", !result.sql.contains("LIMIT"))
        assertNull(result.getCountSql())
    }

    // ------------------------------------------------------------------
    // T-01c：注解 SQL 路径（@Select + Page 形参）
    // ------------------------------------------------------------------

    fun testAnnotationSelectWithPageParamAppendsLimit() {
        myFixture.configureByText(
            "AnnoMapper.java",
            """
            import org.apache.ibatis.annotations.Select;

            public interface AnnoMapper {
                @Select("SELECT id FROM t_user WHERE name = #{name}")
                java.util.List<java.util.Map<String, Object>> selectByPage(Page page, String name);
            }
            """.trimIndent(),
        )
        val annotation = PsiTreeUtil.findChildOfType(myFixture.file, com.intellij.psi.PsiAnnotation::class.java)
        assertNotNull(annotation)
        val result = AnnotationSqlPreviewService.INSTANCE.preview(annotation!!, project)
        assertTrue("注解语句带 Page 形参应追加分页：${result.sql}", result.sql.contains("LIMIT ?, ?"))
        assertEquals(
            "注解路径同样产出 COUNT 包裹",
            "SELECT COUNT(*) FROM (SELECT id FROM t_user WHERE name = ?) TOTAL",
            result.getCountSql(),
        )
    }

    // ------------------------------------------------------------------
    // T-02：COUNT 包裹预览（D1=A 第二条目 / D2=A 基础形态 / D3=A searchCount）
    // ------------------------------------------------------------------

    fun testWrapCountSqlMatchesMpOriginalCountShape() {
        // MP 3.5.17 SqlParserUtils.getOriginalCountSql 逐字节形态（反编译实证）
        assertEquals(
            "SELECT COUNT(*) FROM (SELECT id FROM t) TOTAL",
            PaginationSimulator.wrapCountSql("SELECT id FROM t"),
        )
    }

    fun testPageConsumedProducesWrappedCountSql() {
        // service.page(Page, wrapper) → 主语句 LIMIT + 第二条目 COUNT 包裹（包裹不含 LIMIT 的原语句）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    service.page(new Page<>(2, 10), w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull(result)
        assertTrue("主语句带 LIMIT：${result!!.compactSql}", result.compactSql.endsWith("LIMIT 10, 10"))
        assertEquals(
            "COUNT 包裹按 D1=A 附第二条目（D2=A 基础形态）",
            "SELECT COUNT(*) FROM (SELECT id FROM device_info_entity WHERE id = 1001) TOTAL",
            result.getCountSql(),
        )
    }

    fun testSearchCountFalseSkipsCountSql() {
        // D3=A：new Page<>(current, size, false) → searchCount=false，MP 不执行 COUNT → 预览不产出
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    service.page(new Page<>(2, 10, false), w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull(result)
        assertTrue("LIMIT 仍追加：${result!!.compactSql}", result.compactSql.endsWith("LIMIT 10, 10"))
        assertNull("searchCount=false 不产 COUNT 包裹", result.getCountSql())
    }

    fun testCountAndExistsConsumedProduceNoCountWrap() {
        // count/exists 消费不追加分页、也不产 COUNT 包裹（复用 T-M29-02/03 口径）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void count() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    service.count(w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull(result)
        assertNull("count 消费不产分页 COUNT 包裹", result!!.getCountSql())
    }

    fun testSelectListWithPageProducesCountSql() {
        // mapper.selectList(Page, wrapper) 同产 COUNT（T-01 与 T-02 闭环）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    mapper.selectList(new Page<>(1, 20), w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertEquals(
            "SELECT COUNT(*) FROM (SELECT id FROM device_info_entity WHERE id = 1001) TOTAL",
            result!!.getCountSql(),
        )
    }

    fun testPaginationSwitchOffDisablesCountAndXmlLimit() {
        // T-03：分页模拟开关关闭 → 不产 COUNT、XML 路径不追加 LIMIT（Wrapper 路径 LIMIT 属
        // T-M29-02/03 存量行为不受本开关控制——回归口径以既有测试为准）
        PluginSettings.getInstance().state.paginationSimEnabled = false
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    service.page(new Page<>(2, 10), w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull(result)
        assertNull("开关关闭不产 COUNT 包裹", result!!.getCountSql())
    }

    // ------------------------------------------------------------------
    // T-CountSimToggle（D129）：COUNT 子开关与 LIMIT 解耦
    // ------------------------------------------------------------------

    fun testCountSubSwitchOffKeepsLimitDropsCountOnWrapperChain() {
        // 子开关关（父开）：Wrapper 链 LIMIT 照旧追加、COUNT 包裹不再产出
        PluginSettings.getInstance().state.paginationCountEnabled = false
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    service.page(new Page<>(2, 10), w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull(result)
        assertTrue("子开关关 LIMIT 仍追加：${result!!.compactSql}", result.compactSql.endsWith("LIMIT 10, 10"))
        assertNull("子开关关不产 COUNT 包裹", result.getCountSql())
    }

    fun testCountSubSwitchOffKeepsLimitDropsCountOnSharedPath() {
        // 子开关关（父开）：applyPaginationShared（XML/注解共享）LIMIT 行照旧、countSql=null
        PluginSettings.getInstance().state.paginationCountEnabled = false
        configureXmlMapperWithPageParam()
        val result = XmlPreviewService.INSTANCE.preview(USER_MAPPER_XML, "selectPageUsers", project)
        assertTrue(
            "子开关关 LIMIT 行仍追加：${result.sql}",
            result.sql.contains("LIMIT ?, ?"),
        )
        assertEquals(
            "LIMIT 行存入 paginationLimitLine（面板模型级重渲染回带不受子开关影响）",
            "LIMIT ?, ?",
            result.getPaginationLimitLine(),
        )
        assertNull("子开关关不产 COUNT 包裹", result.getCountSql())
    }

    fun testParentSwitchOffOverridesCountSubSwitchOn() {
        // 父关子开：父开关优先，LIMIT 与 COUNT 全无（子开关不「复活」任何产出）
        PluginSettings.getInstance().state.paginationSimEnabled = false
        PluginSettings.getInstance().state.paginationCountEnabled = true
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    service.page(new Page<>(2, 10), w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val wrapperResult = newService().preview(anchor())
        assertNotNull(wrapperResult)
        assertNull("父开关优先：Wrapper 链不产 COUNT", wrapperResult!!.getCountSql())

        configureXmlMapperWithPageParam()
        val xmlResult = XmlPreviewService.INSTANCE.preview(USER_MAPPER_XML, "selectPageUsers", project)
        assertTrue(
            "父开关优先：XML 路径不追加 LIMIT：${xmlResult.sql}",
            !xmlResult.sql.contains("LIMIT"),
        )
        assertNull("父开关优先：XML 路径不产 COUNT", xmlResult.getCountSql())
    }

    // ------------------------------------------------------------------
    // 边界（验收 §三.2）与方言矩阵（验收 §三.4）
    // ------------------------------------------------------------------

    fun testLastLimitAndPageCoexistPageWinsSingleLimit() {
        // last("LIMIT ...") 与分页并存以分页为准（对齐 T-M29-02 口径）——LIMIT 恰好一次、取分页值
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L)
                        .last("LIMIT 1");
                    mapper.selectList(new Page<>(3, 15), w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull(result)
        assertEquals(
            "并存以分页为准：LIMIT 恰好一次",
            1,
            Regex("(?i)\\bLIMIT\\b").findAll(result!!.compactSql).count(),
        )
        assertTrue("LIMIT 取分页值：${result.compactSql}", result.compactSql.endsWith("LIMIT 30, 15"))
    }

    fun testDialectMatrixLimitShapeForPaginatedMapperSelect() {
        // 方言矩阵：MySQL `LIMIT 0, 20` / PostgreSQL `LIMIT 20 OFFSET 0` / Oracle `OFFSET 0 ROWS FETCH ...`
        val cases = listOf(
            "MySQL" to "SELECT id FROM device_info_entity WHERE id = 1001 LIMIT 0, 20",
            "PostgreSQL" to "SELECT id FROM device_info_entity WHERE id = 1001 LIMIT 20 OFFSET 0",
            "Oracle" to "SELECT id FROM device_info_entity WHERE id = 1001 OFFSET 0 ROWS FETCH NEXT 20 ROWS ONLY",
        )
        for ((dialectName, expected) in cases) {
            setManualDialect(dialectName)
            myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                public class Demo {
                    public void query() {
                        LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                            .eq(DeviceInfoEntity::getId, 1001L);
                        mapper.selectList(new Page<>(1, 20), w);
                    }

                    static class DeviceInfoEntity { private Long id; }
                }
                """.trimIndent(),
            )
            val result = newService().preview(anchor())
            assertEquals("$dialectName LIMIT 形态", expected, result!!.compactSql)
        }
    }

    fun test面板重渲染回带_LIMIT行拼接与手写LIMIT守卫() {
        val base = "SELECT id, name FROM t_user WHERE name = ?"

        // 回带：模型级 SQL + 服务端存下的 LIMIT 行 → 尾部拼接
        assertEquals(
            "无手写 LIMIT 时应回带服务端 LIMIT 行",
            base + "\nLIMIT ?, ?",
            SqlPreviewPanelTextKt.appendPaginationLimitLine(base, "LIMIT ?, ?"),
        )

        // 守卫 A：baseSql 已含 LIMIT（手写语句）不重复追加（V13c 保守口径）
        val withLimit = base + " LIMIT 0, 20"
        assertEquals("已含手写 LIMIT 不重复追加", withLimit, SqlPreviewPanelTextKt.appendPaginationLimitLine(withLimit, "LIMIT ?, ?"))

        // 守卫 B：无分页（limitLine=null）原样返回
        assertEquals("无分页原样返回", base, SqlPreviewPanelTextKt.appendPaginationLimitLine(base, null))
    }
}