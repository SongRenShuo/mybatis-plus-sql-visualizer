package cn.song.mybatisplus.visualizer.ui;

import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

/**
 * XML/注解 SQL 补全上下文解析测试（T-M27-02，M27 架构 §3.2）：
 *  - 主实体链路：Mapper 泛型基类实参（BaseMapper<X>）→ 语句方法实体参数（非 Wrapper/基本类型过滤）；
 *  - 参数名：@Param 值优先 + 实际名 + param1..N；
 *  - 降级：无 namespace / 接口找不到 / 无方法 → main=null、无异常；
 *  - candidates：主实体 + 项目内 @TableName 实体（按 tableName 去重）；
 *  - resolveAnnotation：注解方法场景同链路解析。
 */
public class XmlSqlCompletionContextResolverTest extends BasePlatformTestCase {

    /**
     * T-StartupReadLockFreeze §十九：候选实体现在是**项目级缓存**（按 `PsiModificationTracker` 失效）。
     * Light 沙盒同一测试类共享一个 project 且测试间不改 PSI ⇒ 上一条用例的候选会漏进下一条
     * （实测 `testNoNamespaceDegradesToNullMain` 因此变红）。每个用例前清缓存，保持用例隔离。
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        XmlSqlCompletionContextResolver.clearCandidatesCacheForTest();
        XmlSqlCompletionContextResolver.tableNameEntitiesProviderForTest = null;
        XmlSqlCompletionContextResolver.resetEntityCacheForTest();
    }

    // ------------------------------------------------------------------
    // fixture 工具
    // ------------------------------------------------------------------

    /** 建一个 Mapper 接口文件（package com.example）。 */
    private void addMapperFile(String content) {
        myFixture.addFileToProject("UserMapper.java", "package com.example;\n\n" + content.stripIndent());
    }

    /** 以 XML 语句标签为入口解析（configureByText 建 XML 文件 → 按 id 定位语句标签）。 */
    private SourceSqlContext resolveXml(String xml, String id) {
        myFixture.configureByText("UserMapper.xml", xml.stripIndent());
        XmlTag statementTag = PsiTreeUtil.findChildrenOfType(myFixture.getFile(), XmlTag.class).stream()
                .filter(t -> id.equals(t.getAttributeValue("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 id=" + id + " 的语句标签"));
        return XmlSqlCompletionContextResolver.INSTANCE.resolveXml(getProject(), statementTag);
    }

    // ------------------------------------------------------------------
    // 1. 泛型基类主实体（BaseMapper<X> → 主表）
    // ------------------------------------------------------------------

    public void testGenericBaseMapperEntityResolved() throws Exception {
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
                """
        );
        SourceSqlContext ctx = resolveXml(
                """
                <mapper namespace="com.example.UserMapper">
                  <select id="selectOne">SELECT * FROM t_user</select>
                </mapper>
                """,
                "selectOne"
        );
        assertNotNull("泛型基类/参数实体应解析出主表", ctx.getMain());
        assertEquals("主表应来自 BaseMapper<DeviceInfoEntity> 实体", "t_device_info", ctx.getMain().tableName);
        assertTrue("列集合应含实体字段列 id：" + ctx.getMain().columns, ctx.getMain().columns.contains("id"));
        assertTrue("列集合应含实体字段列 name：" + ctx.getMain().columns, ctx.getMain().columns.contains("name"));
    }

    // ------------------------------------------------------------------
    // 2. 语句方法实体参数兜底（无泛型基类时）
    // ------------------------------------------------------------------

    public void testMethodEntityParamFallback() throws Exception {
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
                """.stripTrailing()
        );
        myFixture.configureByText("OrderMapper.xml", """
                <mapper namespace="com.example.OrderMapper">
                  <select id="selectById">SELECT * FROM t_order WHERE id = #{id}</select>
                </mapper>
                """.stripTrailing());
        XmlTag statementTag = PsiTreeUtil.findChildrenOfType(myFixture.getFile(), XmlTag.class).stream()
                .filter(t -> "selectById".equals(t.getAttributeValue("id")))
                .findFirst().get();
        SourceSqlContext ctx = XmlSqlCompletionContextResolver.INSTANCE.resolveXml(getProject(), statementTag);
        assertNotNull("无泛型时应从方法实体参数兜底", ctx.getMain());
        assertEquals("t_order", ctx.getMain().tableName);
    }

    // ------------------------------------------------------------------
    // 3. 方法参数实体优先级（泛型缺失时从参数找 + 非实体过滤）
    // ------------------------------------------------------------------

    public void testEntityParamSkipsNonEntityTypes() throws Exception {
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
                """.stripTrailing()
        );
        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                  <select id="selectByCondition">SELECT * FROM t_user</select>
                </mapper>
                """.stripTrailing());
        XmlTag statementTag = PsiTreeUtil.findChildrenOfType(myFixture.getFile(), XmlTag.class).stream()
                .filter(t -> "selectByCondition".equals(t.getAttributeValue("id")))
                .findFirst().get();
        SourceSqlContext ctx = XmlSqlCompletionContextResolver.INSTANCE.resolveXml(getProject(), statementTag);
        assertNotNull("应跳过 Long/Map 后命中第一个实体参数", ctx.getMain());
        assertEquals("t_user", ctx.getMain().tableName);
    }

    // ------------------------------------------------------------------
    // 4. 参数名（@Param 值优先 + param1..N 补充）
    // ------------------------------------------------------------------

    public void testParamNamesParamValueFirstThenParam1N() throws Exception {
        addMapperFile(
                """
                public interface UserMapper {
                    UserEntity selectByCondition(@Param("userId") Long userId, String name);
                }
                """
        );
        SourceSqlContext ctx = resolveXml(
                """
                <mapper namespace="com.example.UserMapper">
                  <select id="selectByCondition">SELECT * FROM t_user WHERE id = #{userId}</select>
                </mapper>
                """,
                "selectByCondition"
        );
        assertEquals(
                "@Param 值优先 + 实际名 + param1..N",
                List.of("userId", "name", "param1", "param2"),
                ctx.getParamNames()
        );
    }

    // ------------------------------------------------------------------
    // 5. 无 namespace → main=null、无异常
    // ------------------------------------------------------------------

    public void testNoNamespaceDegradesToNullMain() throws Exception {
        SourceSqlContext ctx = resolveXml(
                """
                <mapper>
                  <select id="selectList">SELECT * FROM t</select>
                </mapper>
                """,
                "selectList"
        );
        assertNull("无 namespace 时主表应为 null", ctx.getMain());
        assertTrue("无 namespace 时无方法参数名", ctx.getParamNames().isEmpty());
        assertTrue("无 namespace 时无候选实体", ctx.getCandidates().isEmpty());
    }

    // ------------------------------------------------------------------
    // 6. 接口找不到 / 无方法 → main=null、无异常
    // ------------------------------------------------------------------

    public void testMapperNotFoundAndMethodNotFoundDegrade() throws Exception {
        // 6a：namespace 对应接口不存在（findClass 返回 null）
        SourceSqlContext ctxNoMapper = resolveXml(
                """
                <mapper namespace="com.example.NoSuchMapper">
                  <select id="selectList">SELECT * FROM t</select>
                </mapper>
                """,
                "selectList"
        );
        assertNull("接口找不到时主表应为 null", ctxNoMapper.getMain());

        // 6b：接口存在但无该 id 的方法（findMethodsByName 空）
        addMapperFile(
                """
                public interface UserMapper {
                    UserEntity selectList(UserEntity entity);
                }
                """
        );
        SourceSqlContext ctxNoMethod = resolveXml(
                """
                <mapper namespace="com.example.UserMapper">
                  <select id="selectAll">SELECT * FROM t_user</select>
                </mapper>
                """,
                "selectAll"
        );
        assertNull("无对应 id 方法时主表应为 null", ctxNoMethod.getMain());
    }

    // ------------------------------------------------------------------
    // 8. resolveAnnotation：注解方法场景主实体解析
    // ------------------------------------------------------------------

    public void testResolveAnnotationMethodMainEntity() throws Exception {
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
                """.stripTrailing()
        );
        PsiMethod method = PsiTreeUtil.findChildOfType(myFixture.getFile(), PsiMethod.class);
        SourceSqlContext ctx = XmlSqlCompletionContextResolver.INSTANCE.resolveAnnotation(getProject(), method);
        assertNotNull("注解方法场景应解析出主表", ctx.getMain());
        assertEquals("t_device_info", ctx.getMain().tableName);
        assertEquals("注解方法参数名应含 @Param/实际名", List.of("entity"), ctx.getParamNames());
    }
}
