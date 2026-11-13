package cn.song.mybatisplus.visualizer.xml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 渲染管线端到端测试（T-M24-01）：mapper 文本 → 解析 → 预览模型 → 渲染。
 *
 * 覆盖：静态语句、where/if 组合、include 展开 + property、CDATA、set、foreach、
 * choose、bind、转义 `\#{`、渲染状态切换（关闭条件 / foreach 次数 / bind 覆盖）。
 */
public class XmlSqlRendererTest {

    /** 解析 mapper 文本并构建预览模型（首个语句）。 */
    private static XmlPreviewModel model(String mapper, String statementId) {
        XmlMapperModel parsed = new XmlMapperParser().parse(mapper);
        XmlStatement stmt = null;
        for (XmlStatement s : parsed.statements()) {
            if (s.id().equals(statementId)) {
                stmt = s;
                break;
            }
        }
        return XmlPreviewModel.from(stmt, parsed.errors());
    }

    private static XmlPreviewModel model(String mapper) {
        return model(mapper, "s1");
    }

    /** 空白归一化单行断言（段间/缩进空白不参与比较）。 */
    private static String compact(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static XmlPreviewState copy(XmlPreviewState base, Set<String> enabledIds) {
        return new XmlPreviewState(enabledIds, base.forEachCounts(), base.forEachNulls(), base.bindOverrides());
    }

    private static XmlPreviewState copy(XmlPreviewState base, Map<String, Integer> forEachCounts) {
        return new XmlPreviewState(base.enabledIds(), forEachCounts, base.forEachNulls(), base.bindOverrides());
    }

    private static XmlPreviewState copyWithBinds(XmlPreviewState base, Map<String, String> bindOverrides) {
        return new XmlPreviewState(base.enabledIds(), base.forEachCounts(), base.forEachNulls(), bindOverrides);
    }

    private static final String mapperTemplate = """
            <mapper namespace="com.example.BizMapper">
              <sql id="baseWhere">
                <if test="status != null">AND status = #{status}</if>
              </sql>
              <select id="s1" resultType="map">
                SELECT * FROM t_biz
                <where>
                  <if test="type != null">AND type = #{type}</if>
                  <include refid="baseWhere">
                    <property name="status" value="1"/>
                  </include>
                </where>
                AND create_time <![CDATA[ > ]]> #{createTime}
                <if test="ids != null">
                  AND id IN
                  <foreach collection="ids" item="id" open="(" separator="," close=")">
                    #{id}
                  </foreach>
                </if>
                <choose>
                  <when test="orderType == 1">ORDER BY create_time DESC</when>
                  <otherwise>ORDER BY id</otherwise>
                </choose>
                <bind name="likeName" value="'%' + name + '%'"/>
                <if test="name != null">AND name LIKE #{likeName}</if>
              </select>
            </mapper>
            """.stripTrailing();

    // ------------------------------------------------------------------
    // 默认状态（全部条件按启发式展开）
    // ------------------------------------------------------------------

    @Test
    public void 默认渲染_全标签组合() throws Exception {
        XmlPreviewModel m = model(mapperTemplate);
        RenderedXmlSql result = m.renderSql();

        assertEquals(
                "SELECT * FROM t_biz WHERE type = ? AND status = ? AND create_time > ? " +
                        "AND id IN ( ? , ? ) ORDER BY create_time DESC AND name LIKE ?",
                compact(result.sql()));
        // 参数顺序：#{} 出现顺序（含 foreach 重写名）
        assertEquals(
                List.of("type", "status", "createTime", "__frch_id_0", "__frch_id_1", "likeName"),
                result.mappings().stream().map(ParameterMapping::property).collect(Collectors.toList()));
        // 视图收集
        assertEquals(5, m.getConditionViews().size());
        assertEquals(List.of("ids"), m.getForEachViews().stream().map(XmlForEachView::collectionExpr).collect(Collectors.toList()));
        assertEquals(List.of("likeName"), m.getBindViews().stream().map(XmlBindView::name).collect(Collectors.toList()));
        assertEquals(Set.of("name"), m.getVariables());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    public void 默认勾选态启发式() throws Exception {
        XmlPreviewModel m = model(mapperTemplate);
        Map<String, Boolean> ids = m.getConditionViews().stream()
                .collect(Collectors.toMap(XmlConditionView::id, XmlConditionView::defaultEnabled));
        assertEquals(Boolean.TRUE, ids.get("type != null"));
        assertEquals(Boolean.TRUE, ids.get("status != null"));
        assertEquals(Boolean.TRUE, ids.get("ids != null"));
        assertEquals(Boolean.TRUE, ids.get("orderType == 1")); // 非判空形态默认展开
        assertEquals(Boolean.TRUE, ids.get("name != null"));
        // == null 形态默认关闭
        XmlPreviewModel m2 = model(mapperTemplate.replace("name != null", "name == null"));
        assertEquals(Boolean.FALSE, m2.getConditionViews().stream()
                .filter(v -> v.id().equals("name == null")).findFirst().get().defaultEnabled());
    }

    // ------------------------------------------------------------------
    // 渲染状态切换
    // ------------------------------------------------------------------

    @Test
    public void 关闭条件后where整体消失() throws Exception {
        XmlPreviewModel m = model(mapperTemplate);
        XmlPreviewState state = copy(m.defaultState(), Set.of("ids != null")); // 只剩 foreach 条件
        RenderedXmlSql result = m.renderSql(state);
        // where 内容为空 → WHERE 不输出（对齐 MyBatis trim 行为）
        assertEquals(
                "SELECT * FROM t_biz AND create_time > ? AND id IN ( ? , ? ) ORDER BY id",
                compact(result.sql()));
    }

    @Test
    public void foreach次数可调_零次为空集() throws Exception {
        XmlPreviewModel m = model(mapperTemplate);
        XmlPreviewState state = copy(m.defaultState(), Map.of("ids", 3));
        assertEquals(
                "SELECT * FROM t_biz WHERE type = ? AND status = ? AND create_time > ? " +
                        "AND id IN ( ? , ? , ? ) ORDER BY create_time DESC AND name LIKE ?",
                compact(m.renderSql(state).sql()));
        XmlPreviewState zero = copy(m.defaultState(), Map.of("ids", 0));
        assertTrue(!compact(m.renderSql(zero).sql()).contains("IN ("));
    }

    @Test
    public void bind覆盖值优先于表达式求值() throws Exception {
        XmlPreviewModel m = model(mapperTemplate);
        XmlPreviewState state = copyWithBinds(m.defaultState(), Map.of("likeName", "'%用户输入%'"));
        RenderedXmlSql result = m.renderSql(state);
        assertEquals(
                "SELECT * FROM t_biz WHERE type = ? AND status = ? AND create_time > ? " +
                        "AND id IN ( ? , ? ) ORDER BY create_time DESC AND name LIKE ?",
                compact(result.sql()));
        assertEquals("likeName", result.mappings().get(result.mappings().size() - 1).property());
    }

    @Test
    public void choose关闭首个when时走otherwise() throws Exception {
        XmlPreviewModel m = model(mapperTemplate);
        XmlPreviewState base = m.defaultState();
        Set<String> enabled = new HashSet<>(base.enabledIds());
        enabled.remove("orderType == 1");
        XmlPreviewState state = copy(base, enabled);
        assertTrue(compact(m.renderSql(state).sql()).endsWith("ORDER BY id AND name LIKE ?"));
    }

    // ------------------------------------------------------------------
    // 静态语句与边界
    // ------------------------------------------------------------------

    @Test
    public void 纯静态语句_无参数() throws Exception {
        XmlPreviewModel m = model("""
                <mapper namespace="n">
                  <select id="s1">SELECT 1</select>
                </mapper>
                """.stripTrailing());
        RenderedXmlSql result = m.renderSql();
        assertEquals("SELECT 1", result.sql());
        assertTrue(result.mappings().isEmpty());
        assertTrue(m.getConditionViews().isEmpty());
        assertTrue(m.getForEachViews().isEmpty());
    }

    @Test
    public void 纯参数语句_含jdbcType属性() throws Exception {
        XmlPreviewModel m = model("""
                <mapper namespace="n">
                  <select id="s1">SELECT * FROM t WHERE a = #{a} AND b = #{b, jdbcType=VARCHAR}</select>
                </mapper>
                """.stripTrailing());
        RenderedXmlSql result = m.renderSql();
        assertEquals("SELECT * FROM t WHERE a = ? AND b = ?", result.sql());
        assertEquals("a", result.mappings().get(0).property());
        assertEquals("b", result.mappings().get(1).property());
        assertEquals("VARCHAR", result.mappings().get(1).attributes().get("jdbcType"));
    }

    @Test
    public void 转义dollar在静态文本中原样保留() throws Exception {
        XmlPreviewModel m = model("""
                <mapper namespace="n">
                  <select id="s1">SELECT \\${col} FROM t</select>
                </mapper>
                """.stripTrailing());
        // `\$` 转义 → 文本判定为静态 → StaticTextSqlNode 原样输出（对齐 MyBatis：转义仅在动态文本生效）
        assertEquals("SELECT \\${col} FROM t", m.renderSql().sql());
    }

    @Test
    public void dollar表达式未解析时输出占位并记录错误() throws Exception {
        XmlPreviewModel m = model("""
                <mapper namespace="n">
                  <select id="s1">SELECT * FROM t WHERE ${ew.customSqlSegment}</select>
                </mapper>
                """.stripTrailing());
        RenderedXmlSql result = m.renderSql();
        // T-MbgExamplePreview R1：预览链开启占位模式——缺段写成 {{表达式}}，不再是无声空串
        assertEquals("SELECT * FROM t WHERE {{ew.customSqlSegment}}", result.sql());
        assertTrue(result.errors().size() > 0);
    }

    @Test
    public void renderer直接调用时占位默认关_空值仍输出空串() throws Exception {
        // T-MbgExamplePreview R1 的默认值闸：真实 SQL 产物路径（renderer 默认参数）不受占位模式影响
        DynamicContext context = new DynamicContext(java.util.Collections.<String, Object>singletonMap(DynamicContext.PARAMETER_OBJECT_KEY, null));
        new TextSqlNode("SELECT * FROM t WHERE ${ew.customSqlSegment}").apply(context);
        assertEquals("SELECT * FROM t WHERE", context.getSql());
    }

    @Test
    public void set与if组合_update语句() throws Exception {
        XmlPreviewModel m = model("""
                <mapper namespace="n">
                  <update id="s1">
                    UPDATE t_biz
                    <set>
                      <if test="name != null">name = #{name},</if>
                      <if test="remark != null">remark = #{remark},</if>
                    </set>
                    WHERE id = #{id}
                  </update>
                </mapper>
                """.stripTrailing());
        assertEquals(
                "UPDATE t_biz SET name = ?, remark = ? WHERE id = ?",
                compact(m.renderSql().sql()));
        assertEquals(
                "UPDATE t_biz SET name = ? WHERE id = ?",
                compact(m.renderSql(copy(m.defaultState(), Set.of("name != null"))).sql()));
    }

    @Test
    public void 未知标签报错() throws Exception {
        assertThrows(XmlScriptException.class, () -> new XmlMapperParser().parse("""
                <mapper namespace="n">
                  <select id="s1"><else>oops</else></select>
                </mapper>
                """.stripTrailing()));
    }

    @Test
    public void 根元素非mapper报错() throws Exception {
        assertThrows(XmlParseException.class, () -> new XmlMapperParser().parse("<configuration></configuration>"));
    }

    @Test
    public void 非良构XML报错() throws Exception {
        assertThrows(XmlParseException.class, () -> new XmlMapperParser().parse("<mapper><select id=\"a\"></mapper>"));
    }

    @Test
    public void include缺失记录错误且不中断() throws Exception {
        XmlPreviewModel m = model("""
                <mapper namespace="n">
                  <select id="s1">SELECT * FROM t <include refid="missing"/></select>
                </mapper>
                """.stripTrailing());
        assertTrue(m.getErrors().stream().anyMatch(e -> e.contains("missing")));
        assertEquals("SELECT * FROM t", compact(m.renderSql().sql()));
    }

    @Test
    public void bind自动求值结果提取() throws Exception {
        // T-M25.5（用户实测：bind 变量未自动求值）——渲染结果携带 bind 求值结果
        XmlMapperModel parsed = new XmlMapperParser().parse("""
                <mapper namespace="n">
                  <select id="s1">
                    <bind name="idleStatus" value="'1'"/>
                    SELECT * FROM t WHERE status = #{idleStatus}
                  </select>
                </mapper>
                """.stripTrailing());
        XmlPreviewModel m = XmlPreviewModel.from(parsed.statements().get(0), parsed.errors());
        RenderedXmlSql rendered = m.renderSql();
        assertEquals("1", rendered.bindValues().get("idleStatus"));
        assertEquals("SELECT * FROM t WHERE status = ?", compact(rendered.sql()));
    }

    @Test
    public void bind覆盖值优先于自动求值() throws Exception {
        XmlMapperModel parsed = new XmlMapperParser().parse("""
                <mapper namespace="n">
                  <select id="s1">
                    <bind name="status" value="'1'"/>
                    SELECT * FROM t WHERE status = #{status}
                  </select>
                </mapper>
                """.stripTrailing());
        XmlPreviewModel m = XmlPreviewModel.from(parsed.statements().get(0), parsed.errors());
        XmlPreviewState state = copyWithBinds(m.defaultState(), Map.of("status", "9"));
        assertEquals("9", m.renderSql(state).bindValues().get("status"));
    }

    @Test
    public void 官方DTD声明可解析() throws Exception {
        // T-M25.1（用户实测）：MyBatis 官方 DTD 声明（<!DOCTYPE mapper PUBLIC ...>）
        // 此前被 disallow-doctype-decl 拒绝——允许声明 + EntityResolver 拦截（不联网）
        XmlPreviewModel m = model("""
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="n">
                  <select id="s1">SELECT * FROM t WHERE id = #{id}</select>
                </mapper>
                """.stripTrailing());
        assertEquals("SELECT * FROM t WHERE id = ?", compact(m.renderSql().sql()));
        assertEquals("id", m.renderSql().mappings().stream().findFirst().get().property());
    }

    @Test
    public void 内部实体定义可解析() throws Exception {
        XmlPreviewModel m = model("""
                <!DOCTYPE mapper [<!ENTITY status "ACTIVE">]>
                <mapper namespace="n">
                  <select id="s1">SELECT * FROM t WHERE status = '&status;'</select>
                </mapper>
                """.stripTrailing());
        assertEquals("SELECT * FROM t WHERE status = 'ACTIVE'", compact(m.renderSql().sql()));
    }

    @Test
    public void 外部实体被清空_XXE防护() throws Exception {
        // 外部实体引用解析为空（不注入外部文件内容）——实测 /etc/hosts 内容不出现
        XmlPreviewModel m = model("""
                <!DOCTYPE mapper [<!ENTITY xxe SYSTEM "file:///etc/hosts">]>
                <mapper namespace="n">
                  <select id="s1">SELECT '&xxe;' FROM t</select>
                </mapper>
                """.stripTrailing());
        String sql = compact(m.renderSql().sql());
        assertEquals("SELECT '' FROM t", sql);
    }

    @Test
    public void bind静态常量引用_注入值渲染() throws Exception {
        // T-M25.2（用户实测）：OGNL 静态字段引用 @类全名@字段名（MyBatis mapper 常见）
        XmlMapperModel parsed = new XmlMapperParser().parse("""
                <mapper namespace="n">
                  <select id="s1">
                    <bind name="portStatus" value="@com.cyzc.platform.common.constant.DicConstants@DIC_VALUE_BIKE_DEVICE_PORT_STATUS_IDLE"/>
                    SELECT * FROM t WHERE port_status = #{portStatus}
                  </select>
                </mapper>
                """.stripTrailing());
        XmlStatement statement = parsed.statements().get(0);
        XmlPreviewModel m = XmlPreviewModel.from(statement, parsed.errors(),
                Map.<String, Object>of("com.cyzc.platform.common.constant.DicConstants@DIC_VALUE_BIKE_DEVICE_PORT_STATUS_IDLE", "1"),
                Map.of(), Map.of());
        RenderedXmlSql rendered = m.renderSql();
        assertEquals("SELECT * FROM t WHERE port_status = ?", compact(rendered.sql()));
        assertEquals("portStatus", rendered.mappings().stream().findFirst().get().property());
        assertTrue(rendered.errors().isEmpty());
    }

    @Test
    public void bind双竖线包裹_兼容解析并提示() throws Exception {
        // 用户实测场景：value="||@class@FIELD||"（非标准写法，真实 OGNL 运行时会失败）
        XmlMapperModel parsed = new XmlMapperParser().parse("""
                <mapper namespace="n">
                  <select id="s1">
                    <bind name="idleStatus" value="||@com.cyzc.platform.common.constant.DicConstants@DIC_VALUE_BIKE_DEVICE_PORT_STATUS_IDLE||"/>
                    SELECT * FROM t WHERE status = #{idleStatus}
                  </select>
                </mapper>
                """.stripTrailing());
        XmlStatement statement = parsed.statements().get(0);
        XmlPreviewModel m = XmlPreviewModel.from(statement, parsed.errors(),
                Map.<String, Object>of("com.cyzc.platform.common.constant.DicConstants@DIC_VALUE_BIKE_DEVICE_PORT_STATUS_IDLE", "1"),
                Map.of(), Map.of());
        RenderedXmlSql rendered = m.renderSql();
        assertEquals("SELECT * FROM t WHERE status = ?", compact(rendered.sql()));
        assertTrue("应提示 || 包裹非标准写法", rendered.errors().stream().anyMatch(e -> e.contains("|| 包裹")));
    }

    @Test
    public void bind静态常量引用_未注入时提示可填写覆盖值() throws Exception {
        XmlMapperModel parsed = new XmlMapperParser().parse("""
                <mapper namespace="n">
                  <select id="s1">
                    <bind name="portStatus" value="@com.cyzc.platform.common.constant.DicConstants@DIC_VALUE"/>
                    SELECT * FROM t WHERE port_status = #{portStatus}
                  </select>
                </mapper>
                """.stripTrailing());
        XmlPreviewModel m = XmlPreviewModel.from(parsed.statements().get(0), parsed.errors());
        RenderedXmlSql rendered = m.renderSql();
        assertTrue(rendered.errors().stream().anyMatch(e -> e.contains("静态常量") && e.contains("填写覆盖值")));
    }
}
