package cn.song.mybatisplus.visualizer.ui

import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * XML/注解 SQL 补全上下文解析测试（T-M27-02，M27 架构 §3.2）：
 *  - 主实体链路：Mapper 泛型基类实参（BaseMapper<X>）→ 语句方法实体参数（非 Wrapper/基本类型过滤）；
 *  - 参数名：@Param 值优先 + 实际名 + param1..N（对齐 AnnotationSqlPreviewService.paramNames 语义）；
 *  - 降级：无 namespace / 接口找不到 / 无方法 → main=null、无异常；
 *  - candidates：主实体 + 项目内 @TableName 实体（按 tableName 去重）；
 *  - resolveAnnotation：注解方法场景同链路解析。
 *
 * fixture 说明（对齐 XmlEwResolverTest / EntityResolverTableConfigTest 先例）：
 *  - @TableName 用**全限定名**书写（EntityResolver 短名兜底可识别；Light 沙盒注解类不可解析时
 *    JavaAnnotationIndex 按 FQN 文本命中 stub 索引——测试环境 libs.mp.core 真实 jar 在 classpath、
 *    但未挂载为 module classpath，PSI 无法解析 MP 库类，见 HeavyAnchorSemanticsTest 文档）；
 *  - 泛型基类 BaseMapper 在沙盒不可解析时自动落到语句方法实体参数路径（同实体断言不受影响）。
 */
class XmlSqlCompletionContextResolverTest : BasePlatformTestCase() {

    /**
     * T-StartupReadLockFreeze §十九：候选实体现在是**项目级缓存**（按 `PsiModificationTracker` 失效）。
     * Light 沙盒同一测试类共享一个 project 且测试间不改 PSI ⇒ 上一条用例的候选会漏进下一条
     * （实测 `testNoNamespaceDegradesToNullMain` 因此变红）。每个用例前清缓存，保持用例隔离。
     */
    override fun setUp() {
        super.setUp()
        XmlSqlCompletionContextResolver.clearCandidatesCacheForTest()
        XmlSqlCompletionContextResolver.tableNameEntitiesProviderForTest = null
    }

    // ------------------------------------------------------------------
    // fixture 工具
    // ------------------------------------------------------------------

    /** 建一个 Mapper 接口文件（package com.example）。 */
    private fun addMapperFile(content: String) {
        myFixture.addFileToProject("UserMapper.java", "package com.example;\n\n${content.trimIndent()}")
    }

    /** 以 XML 语句标签为入口解析（configureByText 建 XML 文件 → 按 id 定位语句标签）。 */
    private fun resolveXml(xml: String, id: String): SourceSqlContext {
        myFixture.configureByText("UserMapper.xml", xml.trimIndent())
        val statementTag = PsiTreeUtil.findChildrenOfType(myFixture.file, XmlTag::class.java)
            .first { it.getAttributeValue("id") == id }
        return XmlSqlCompletionContextResolver.resolveXml(project, statementTag)
    }

    // ------------------------------------------------------------------
    // 1. 泛型基类主实体（BaseMapper<X> → 主表）
    // ------------------------------------------------------------------

    fun testGenericBaseMapperEntityResolved() {
        // Light 沙盒限制：跨文件类型引用 resolve 返回 null（HeavyAnchorSemanticsTest 文档实证），
        // 实体与接口同文件声明（主实体经方法参数路径命中；BaseMapper 泛型路径在沙盒不可解析，
        // 自动落到同实体兜底，断言主表结果不受影响）
        addMapperFile(
            """
            @com.baomidou.mybatisplus.annotation.TableName("t_device_info")
            public class DeviceInfoEntity {
                private Long id;
                private String name;
            }

            public interface UserMapper extends BaseMapper<DeviceInfoEntity> {
                DeviceInfoEntity selectOne(DeviceInfoEntity entity);
            }
            """,
        )
        val ctx = resolveXml(
            """
            <mapper namespace="com.example.UserMapper">
              <select id="selectOne">SELECT * FROM t_user</select>
            </mapper>
            """,
            "selectOne",
        )
        assertNotNull("泛型基类/参数实体应解析出主表", ctx.main)
        assertEquals("主表应来自 BaseMapper<DeviceInfoEntity> 实体", "t_device_info", ctx.main?.tableName)
        assertTrue("列集合应含实体字段列 id：${ctx.main?.columns}", ctx.main?.columns?.contains("id") == true)
        assertTrue("列集合应含实体字段列 name：${ctx.main?.columns}", ctx.main?.columns?.contains("name") == true)
    }

    // ------------------------------------------------------------------
    // 2. 语句方法实体参数兜底（无泛型基类时）
    // ------------------------------------------------------------------

    fun testMethodEntityParamFallback() {
        myFixture.addFileToProject(
            "OrderMapper.java",
            """
            package com.example;
            
            @com.baomidou.mybatisplus.annotation.TableName("t_order")
            public class OrderEntity {
                private Long id;
                private String orderNo;
            }
            
            public interface OrderMapper {
                OrderEntity selectById(OrderEntity entity);
            }
            """.trimIndent(),
        )
        myFixture.configureByText("OrderMapper.xml", """
            <mapper namespace="com.example.OrderMapper">
              <select id="selectById">SELECT * FROM t_order WHERE id = #{id}</select>
            </mapper>
        """.trimIndent())
        val statementTag = PsiTreeUtil.findChildrenOfType(myFixture.file, XmlTag::class.java)
            .first { it.getAttributeValue("id") == "selectById" }
        val ctx = XmlSqlCompletionContextResolver.resolveXml(project, statementTag)
        assertNotNull("无泛型时应从方法实体参数兜底", ctx.main)
        assertEquals("t_order", ctx.main?.tableName)
    }

    // ------------------------------------------------------------------
    // 3. 方法参数实体优先级（泛型缺失时从参数找 + 非实体过滤）
    // ------------------------------------------------------------------

    fun testEntityParamSkipsNonEntityTypes() {
        myFixture.addFileToProject(
            "UserMapper.java",
            """
            package com.example;
            
            @com.baomidou.mybatisplus.annotation.TableName("t_user")
            public class UserEntity {
                private Long id;
            }
            
            public interface UserMapper {
                UserEntity selectByCondition(Long id, java.util.Map<String, Object> condition, UserEntity entity);
            }
            """.trimIndent(),
        )
        myFixture.configureByText("UserMapper.xml", """
            <mapper namespace="com.example.UserMapper">
              <select id="selectByCondition">SELECT * FROM t_user</select>
            </mapper>
        """.trimIndent())
        val statementTag = PsiTreeUtil.findChildrenOfType(myFixture.file, XmlTag::class.java)
            .first { it.getAttributeValue("id") == "selectByCondition" }
        val ctx = XmlSqlCompletionContextResolver.resolveXml(project, statementTag)
        assertNotNull("应跳过 Long/Map 后命中第一个实体参数", ctx.main)
        assertEquals("t_user", ctx.main?.tableName)
    }

    // ------------------------------------------------------------------
    // 4. 参数名（@Param 值优先 + param1..N 补充）
    // ------------------------------------------------------------------

    fun testParamNamesParamValueFirstThenParam1N() {
        addMapperFile(
            """
            public interface UserMapper {
                UserEntity selectByCondition(@Param("userId") Long userId, String name);
            }
            """,
        )
        val ctx = resolveXml(
            """
            <mapper namespace="com.example.UserMapper">
              <select id="selectByCondition">SELECT * FROM t_user WHERE id = #{userId}</select>
            </mapper>
            """,
            "selectByCondition",
        )
        assertEquals(
            "@Param 值优先 + 实际名 + param1..N（对齐 AnnotationSqlPreviewService.paramNames）",
            listOf("userId", "name", "param1", "param2"),
            ctx.paramNames,
        )
    }

    // ------------------------------------------------------------------
    // 5. 无 namespace → main=null、无异常
    // ------------------------------------------------------------------

    fun testNoNamespaceDegradesToNullMain() {
        val ctx = resolveXml(
            """
            <mapper>
              <select id="selectList">SELECT * FROM t</select>
            </mapper>
            """,
            "selectList",
        )
        assertNull("无 namespace 时主表应为 null", ctx.main)
        assertTrue("无 namespace 时无方法参数名", ctx.paramNames.isEmpty())
        assertTrue("无 namespace 时无候选实体", ctx.candidates.isEmpty())
    }

    // ------------------------------------------------------------------
    // 6. 接口找不到 / 无方法 → main=null、无异常
    // ------------------------------------------------------------------

    fun testMapperNotFoundAndMethodNotFoundDegrade() {
        // 6a：namespace 对应接口不存在（findClass 返回 null）
        val ctxNoMapper = resolveXml(
            """
            <mapper namespace="com.example.NoSuchMapper">
              <select id="selectList">SELECT * FROM t</select>
            </mapper>
            """,
            "selectList",
        )
        assertNull("接口找不到时主表应为 null", ctxNoMapper.main)

        // 6b：接口存在但无该 id 的方法（findMethodsByName 空）
        addMapperFile(
            """
            public interface UserMapper {
                UserEntity selectList(UserEntity entity);
            }
            """,
        )
        val ctxNoMethod = resolveXml(
            """
            <mapper namespace="com.example.UserMapper">
              <select id="selectAll">SELECT * FROM t_user</select>
            </mapper>
            """,
            "selectAll",
        )
        assertNull("无对应 id 方法时主表应为 null", ctxNoMethod.main)
    }

    // ------------------------------------------------------------------
    // 7. candidates：@TableName 实体候选（Light 沙盒 FileBasedIndex 不可用，
    //    已移至 Heavy 沙盒——HeavySqlCompletionContextResolverTest）
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 8. resolveAnnotation：注解方法场景主实体解析
    // ------------------------------------------------------------------

    fun testResolveAnnotationMethodMainEntity() {
        myFixture.configureByText(
            "UserMapper.java",
            """
            package com.example;
            
            @com.baomidou.mybatisplus.annotation.TableName("t_device_info")
            public class DeviceInfoEntity {
                private Long id;
            }
            
            public interface UserMapper {
                @Select("SELECT * FROM t_device_info WHERE id = #{id}")
                DeviceInfoEntity selectByEntity(DeviceInfoEntity entity);
            }
            """.trimIndent(),
        )
        val method = PsiTreeUtil.findChildOfType(myFixture.file, PsiMethod::class.java)!!
        val ctx = XmlSqlCompletionContextResolver.resolveAnnotation(project, method)
        assertNotNull("注解方法场景应解析出主表", ctx.main)
        assertEquals("t_device_info", ctx.main?.tableName)
        assertEquals("注解方法参数名应含 @Param/实际名", listOf("entity"), ctx.paramNames)
    }

    // ------------------------------------------------------------------
    // §二十五：MBG selectByExample(XxxExample) 的参数不是实体
    // ------------------------------------------------------------------

    /**
     * 真机日志（2026-11-13 16:35）：`[completion] ctx: main=cust_channel_example columns=3`——
     * 把 MBG 的 Example 类当成了实体，表名推成 `cust_channel_example`、列只有 Example 的 3 个字段，
     * 列名补全于是给出完全错误的候选。修复后应按约定回查同包实体。
     */
    fun testExampleParamNotTreatedAsEntity() {
        addMapperFile(
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("cust_channel")
            public class CustChannel {
                private Long id;
                private String channelCode;
                private String channelName;
            }

            public class CustChannelExample {
                private Integer distinct;
                private String orderByClause;
                private java.util.List oredCriteria;
            }

            public interface CustChannelMapper {
                CustChannel selectByExample(CustChannelExample example);
            }
            """.trimIndent(),
        )
        val ctx = resolveXml(
            """
            <mapper namespace="com.example.CustChannelMapper">
              <select id="selectByExample" parameterType="com.example.CustChannelExample" resultType="map">
                select * from cust_channel
              </select>
            </mapper>
            """.trimIndent(),
            "selectByExample",
        )
        assertEquals("主表必须取同包实体，而不是 Example 类", "cust_channel", ctx.main?.tableName)
    }

    /** 同包实体不存在时宁可 main=null，也不得把 Example 当实体（否则补全给出 xxx_example 的假列）。 */
    fun testExampleParamWithoutEntityDegradesToNull() {
        addMapperFile(
            """
            public class OrphanExample {
                private Integer distinct;
            }

            public interface OrphanMapper {
                Object selectByExample(OrphanExample example);
            }
            """.trimIndent(),
        )
        val ctx = resolveXml(
            """
            <mapper namespace="com.example.OrphanMapper">
              <select id="selectByExample" parameterType="com.example.OrphanExample" resultType="map">
                select * from orphan
              </select>
            </mapper>
            """.trimIndent(),
            "selectByExample",
        )
        assertNull("Example 无对应实体时必须降级为 null", ctx.main)
    }

    /**
     * §二十六：mapper 接口解析不到时（MBG 风格 / 沙盒没 Load Maven），用 SQL 里的 FROM 表名反查实体。
     * 真机 16:55 出现过 `main=-`，那时补全整片为空。
     */
    fun testFromTableNameFallback() {
        // 注意：必须保留 addFileToProject 返回的 Java 文件句柄——`myFixture.file` 是后配的 XML，
        // 从它里面找 Java 类必然 NoSuchElement（本轮实测踩到）。
        val javaFile = myFixture.addFileToProject(
            "CustChannelHolder.java",
            """
            package com.example;

            @com.baomidou.mybatisplus.annotation.TableName("cust_channel")
            public class CustChannel {
                private Long id;
                private String channelCode;
                private String channelName;
            }
            """.trimIndent(),
        )
        // Light 沙盒里 EntityClassFinder 依赖索引（返回空），用注入缝提供「表名 → 实体」
        val entityClass = PsiTreeUtil.findChildrenOfType(javaFile, com.intellij.psi.PsiClass::class.java)
            .first { it.name == "CustChannel" }
        XmlSqlCompletionContextResolver.entityFinderForTest = { _, table ->
            if (table == "cust_channel") entityClass else null
        }
        val ctx = resolveXml(
            """
            <mapper namespace="com.example.NoSuchMapperInterface">
              <select id="selectAll" resultType="map">
                select * from cust_channel where id = #{'$'}{id}
              </select>
            </mapper>
            """.trimIndent(),
            "selectAll",
        )
        XmlSqlCompletionContextResolver.entityFinderForTest = null
        assertEquals("应按 FROM 表名反查到实体", "cust_channel", ctx.main?.tableName)
        assertTrue("反查到的实体应带列", (ctx.main?.columns?.size ?: 0) >= 3)
    }
}
