package cn.song.mybatisplus.visualizer.xml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 预览模型测试（T-M24-04）：变量收集（排除保留键）、启发式默认真值（`== null` 关闭）、
 * 渲染状态切换（条件开关 / foreach 次数与 null / bind 覆盖）、默认渲染单源。
 */
public class XmlPreviewModelTest {

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

    private static String compact(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static XmlPreviewState copy(XmlPreviewState base, Set<String> enabledIds) {
        return new XmlPreviewState(enabledIds, base.forEachCounts(), base.forEachNulls(), base.bindOverrides());
    }

    private static XmlPreviewState copy(XmlPreviewState base, Map<String, Integer> forEachCounts) {
        return new XmlPreviewState(base.enabledIds(), forEachCounts, base.forEachNulls(), base.bindOverrides());
    }

    private static XmlPreviewState copyNull(XmlPreviewState base, Set<String> forEachNulls) {
        return new XmlPreviewState(base.enabledIds(), base.forEachCounts(), forEachNulls, base.bindOverrides());
    }

    private static XmlPreviewState copyBind(XmlPreviewState base, Map<String, String> bindOverrides) {
        return new XmlPreviewState(base.enabledIds(), base.forEachCounts(), base.forEachNulls(), bindOverrides);
    }

    // ------------------------------------------------------------------
    // 变量收集
    // ------------------------------------------------------------------

    @Test
    public void 变量收集_来自bind与dollar表达式_排除保留键() throws Exception {
        XmlPreviewModel m = model("""
                <mapper namespace="n">
                  <select id="s1">
                    <bind name="p" value="'%' + _parameter.title + '%'"/>
                    <bind name="q" value="list.size() + 1"/>
                    SELECT * FROM t WHERE a = ${ew.customSqlSegment} AND b LIKE #{p}
                  </select>
                </mapper>
                """.stripTrailing());
        // _parameter 是保留键（不收集）；title 归模拟参数对象属性；list/ew 是普通根变量
        assertEquals(Set.of("title"), m.getParamProps());
        assertEquals(Set.of("list", "ew"), m.getVariables());
        assertEquals(List.of("p", "q"), m.getBindViews().stream().map(XmlBindView::name).collect(Collectors.toList()));
    }

    @Test
    public void 变量收集_if条件不参与() throws Exception {
        XmlPreviewModel m = model("""
                <mapper namespace="n">
                  <select id="s1">SELECT * FROM t<if test="status != null and type == 1"> WHERE x = 1</if></select>
                </mapper>
                """.stripTrailing());
        // test 表达式由开关驱动，不收集变量
        assertEquals(Set.of(), m.getVariables());
        assertEquals(List.of("status != null and type == 1"),
                m.getConditionViews().stream().map(XmlConditionView::id).collect(Collectors.toList()));
    }

    // ------------------------------------------------------------------
    // 启发式默认值
    // ------------------------------------------------------------------

    @Test
    public void 启发式默认_非判空展开_判null关闭() throws Exception {
        XmlPreviewModel m = model("""
                <mapper namespace="n">
                  <select id="s1">SELECT * FROM t
                    <where>
                      <if test="a != null">AND a = 1</if>
                      <if test="b == null">AND b IS NULL</if>
                      <if test="c != null and c != ''">AND c = #{c}</if>
                      <if test="d">AND d = 1</if>
                      <if test="flag == false">AND e = 2</if>
                    </where>
                  </select>
                </mapper>
                """.stripTrailing());
        Map<String, Boolean> defaults = m.getConditionViews().stream()
                .collect(Collectors.toMap(XmlConditionView::id, XmlConditionView::defaultEnabled));
        assertEquals(Boolean.TRUE, defaults.get("a != null"));
        assertEquals(Boolean.FALSE, defaults.get("b == null"));
        assertEquals(Boolean.TRUE, defaults.get("c != null and c != ''"));
        assertEquals(Boolean.TRUE, defaults.get("d"));
        assertEquals(Boolean.TRUE, defaults.get("flag == false"));
    }

    // ------------------------------------------------------------------
    // 渲染状态
    // ------------------------------------------------------------------

    @Test
    public void foreach默认展开两次_次数可调() throws Exception {
        // T-GoldenDefense：collection="map.items" 形态锁定为金样矩阵 X5（XmlMapParamSpecMatrixTest）
        XmlPreviewModel m = model("""
                <mapper namespace="n">
                  <select id="s1">SELECT * FROM t WHERE id IN
                    <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
                  </select>
                </mapper>
                """.stripTrailing());
        assertEquals((int) XmlPreviewModel.DEFAULT_FOREACH_SIZE, (int) m.defaultState().forEachCounts().get("ids"));
        assertEquals("SELECT * FROM t WHERE id IN ( ? , ? )", compact(m.renderSql().sql()));
        XmlPreviewState state = copy(m.defaultState(), Map.of("ids", 0));
        assertEquals("SELECT * FROM t WHERE id IN", compact(m.renderSql(state).sql()));
    }

    @Test
    public void foreach集合为null时错误提示() throws Exception {
        XmlPreviewModel m = model("""
                <mapper namespace="n">
                  <select id="s1">SELECT * FROM t WHERE id IN
                    <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
                  </select>
                </mapper>
                """.stripTrailing());
        XmlPreviewState state = copyNull(m.defaultState(), Set.of("ids"));
        RenderedXmlSql result = m.renderSql(state);
        assertEquals("SELECT * FROM t WHERE id IN", compact(result.sql()));
        // T-MbgExamplePreview R6：文案已中文化（原 MyBatis 英文原文透传到中文 UI）
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("求值为 null")));
    }

    @Test
    public void bind覆盖优先于表达式求值() throws Exception {
        XmlPreviewModel m = model("""
                <mapper namespace="n">
                  <select id="s1">
                    <bind name="pattern" value="'%' + name + '%'"/>
                    SELECT * FROM t WHERE name LIKE #{pattern}
                  </select>
                </mapper>
                """.stripTrailing());
        // 默认：name 占位 "1" → '%1%'
        assertEquals("SELECT * FROM t WHERE name LIKE ?", compact(m.renderSql().sql()));
        XmlPreviewState state = copyBind(m.defaultState(), Map.of("pattern", "'%x%'"));
        assertEquals("SELECT * FROM t WHERE name LIKE ?", compact(m.renderSql(state).sql()));
        assertEquals("pattern", m.renderSql(state).mappings().get(0).property());
    }

    @Test
    public void 条件开关切换分支() throws Exception {
        // T-GoldenDefense：Map 参数条件（map.status != null）开关语义锁定为金样矩阵 X3（XmlMapParamSpecMatrixTest）
        XmlPreviewModel m = model("""
                <mapper namespace="n">
                  <select id="s1">SELECT * FROM t
                    <if test="a != null">WHERE a = 1</if>
                    <if test="b != null"> AND b = 2</if>
                  </select>
                </mapper>
                """.stripTrailing());
        assertEquals("SELECT * FROM t WHERE a = 1 AND b = 2", compact(m.renderSql().sql()));
        XmlPreviewState state = copy(m.defaultState(), Set.of("a != null"));
        assertEquals("SELECT * FROM t WHERE a = 1", compact(m.renderSql(state).sql()));
        XmlPreviewState none = copy(m.defaultState(), Set.of());
        assertEquals("SELECT * FROM t", compact(m.renderSql(none).sql()));
    }

    @Test
    public void 表达式解析失败的test默认展开_开关可关闭() throws Exception {
        XmlPreviewModel m = model("""
                <mapper namespace="n">
                  <select id="s1">SELECT * FROM t
                    <if test="not valid @@ expression">WHERE a = 1</if>
                  </select>
                </mapper>
                """.stripTrailing());
        // 解析失败 → 默认 true（预览容错）
        assertEquals("SELECT * FROM t WHERE a = 1", compact(m.renderSql().sql()));
        XmlPreviewState state = copy(m.defaultState(), Set.of());
        assertEquals("SELECT * FROM t", compact(m.renderSql(state).sql()));
    }

    @Test
    public void 语句元信息透传() throws Exception {
        XmlPreviewModel m = model("""
                <mapper namespace="com.example.BizMapper">
                  <update id="updateById" parameterType="map">UPDATE t SET a = 1 WHERE id = 2</update>
                </mapper>
                """.stripTrailing(), "updateById");
        assertEquals("updateById", m.getStatementId());
        assertEquals(XmlStatementKind.UPDATE, m.getKind());
        assertEquals("map", m.getParameterType());
    }

    // ------------------------------------------------------------------
    // T-M29.5：参数回填引号映射（quoteFor 精确/前缀/未命中）
    // ------------------------------------------------------------------

    @Test
    public void quoteFor_精确与前缀匹配() throws Exception {
        XmlPreviewModel m = XmlPreviewModel.from(
                new XmlMapperParser().parse("""
                        <mapper namespace="n">
                          <select id="s1">SELECT * FROM t WHERE a = #{p.settlementStartDate} AND b = #{p.dicCreditStatus}</select>
                        </mapper>
                        """.stripTrailing()).statements().get(0),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of("p.settlementStartDate", true, "p.dicCreditStatus", false, "keyword", true));
        // 精确命中
        assertEquals(Boolean.TRUE, m.quoteFor("p.settlementStartDate"));
        assertEquals(Boolean.FALSE, m.quoteFor("p.dicCreditStatus"));
        assertEquals(Boolean.TRUE, m.quoteFor("keyword"));
        // 未命中 → null（面板兜底引号）
        assertEquals(null, m.quoteFor("unknown"));
    }

    @Test
    public void quoteFor_Map参数前缀兜底() throws Exception {
        // Map 入参：键 = 参数名 p，任意 p.* 属性前缀命中（T-M29.4 兜底语义）
        // T-GoldenDefense：@Param("map") 命名形态已锁定为金样矩阵 X2（XmlMapParamSpecMatrixTest）
        XmlPreviewModel m = XmlPreviewModel.from(
                new XmlMapperParser().parse("""
                        <mapper namespace="n">
                          <select id="s1">SELECT * FROM t WHERE a = #{p.settlementStartDate}</select>
                        </mapper>
                        """.stripTrailing()).statements().get(0),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of("p", true));
        assertEquals(Boolean.TRUE, m.quoteFor("p.settlementStartDate"));
        // 精确键优先于前缀（DTO 与 Map 键并存时不误判）
        XmlPreviewModel mixed = XmlPreviewModel.from(
                new XmlMapperParser().parse("""
                        <mapper namespace="n">
                          <select id="s1">SELECT * FROM t</select>
                        </mapper>
                        """.stripTrailing()).statements().get(0),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of("p", true, "p.dicCreditStatus", false));
        assertEquals(Boolean.FALSE, mixed.quoteFor("p.dicCreditStatus"));
        assertEquals(Boolean.TRUE, mixed.quoteFor("p.settlementStartDate"));
    }

    // ------------------------------------------------------------------
    // T-ExprAstPrecheck T-EP-02：if test 求值失败不再静默
    // ------------------------------------------------------------------

    @Test
    public void if求值失败记错误_默认展开行为不变() throws Exception {
        XmlMapperModel parsed = new XmlMapperParser().parse("""
                <mapper namespace="n">
                  <select id="s1">SELECT * FROM t
                    <if test="not valid @@ expression">WHERE a = 1</if>
                  </select>
                </mapper>
                """.stripTrailing());
        // 开关缺席（无 conditionOverrides）→ 走表达式求值兜底：解析失败仍默认展开 true，但必须记 errors
        RenderedXmlSql rendered = new XmlSqlRenderer().render(parsed.statements().get(0).node(), Map.of());
        assertEquals("SELECT * FROM t WHERE a = 1", compact(rendered.sql()));
        assertTrue(
                "求值失败的 test 应留下可见错误：" + rendered.errors(),
                rendered.errors().stream()
                        .anyMatch(e -> e.contains("条件表达式无法求值，已按 true 展开——可在预览面板用开关覆盖")));
        // 表达式原文随错误一并带出（用户据此定位是哪条条件）
        assertTrue(
                "错误应含 test 原文：" + rendered.errors(),
                rendered.errors().stream().anyMatch(e -> e.contains("not valid @@ expression")));
    }

    @Test
    public void if求值成功的test不记错误() throws Exception {
        XmlMapperModel parsed = new XmlMapperParser().parse("""
                <mapper namespace="n">
                  <select id="s1">SELECT * FROM t
                    <if test="a != null">WHERE a = 1</if>
                  </select>
                </mapper>
                """.stripTrailing());
        RenderedXmlSql rendered = new XmlSqlRenderer().render(parsed.statements().get(0).node(), Map.of("a", "1"));
        assertEquals("SELECT * FROM t WHERE a = 1", compact(rendered.sql()));
        assertTrue("求值成功不应记错误：" + rendered.errors(), rendered.errors().isEmpty());
    }

    // ------------------------------------------------------------------
    // T-ExprAstPrecheck T-EP-03：条件行支持面预检标注
    // ------------------------------------------------------------------

    @Test
    public void 条件行支持面标注_三档与展开行为无关() throws Exception {
        XmlPreviewModel m = model("""
                <mapper namespace="n">
                  <select id="s1">SELECT * FROM t
                    <if test="a != null and a.size() > 0">AND a = 1</if>
                    <if test="a ? b : c">AND b = 2</if>
                    <if test="map.length() > 0">AND c = 3</if>
                  </select>
                </mapper>
                """.stripTrailing());
        Map<String, XmlConditionView> byId = m.getConditionViews().stream()
                .collect(Collectors.toMap(XmlConditionView::id, v -> v));

        XmlConditionView supported = byId.get("a != null and a.size() > 0");
        assertEquals(ExprSupport.SUPPORTED, supported.support());
        assertEquals(null, supported.supportReason());
        assertEquals(null, supported.supportDetail());

        XmlConditionView syntax = byId.get("a ? b : c");
        assertEquals(ExprSupport.UNSUPPORTED_SYNTAX, syntax.support());
        assertEquals(ExprReason.SYNTAX, syntax.supportReason());
        assertTrue("解析失败应带解析器消息：" + syntax.supportDetail(),
                syntax.supportDetail() != null && !syntax.supportDetail().isBlank());

        XmlConditionView fallback = byId.get("map.length() > 0");
        assertEquals(ExprSupport.EVAL_FALLBACK, fallback.support());
        assertEquals(ExprReason.NARROW_CARRIER, fallback.supportReason());
        assertEquals("length", fallback.supportDetail());

        // 标注只是提示：默认渲染三个条件全部按启发式展开（行为零变化）
        assertEquals("SELECT * FROM t AND a = 1 AND b = 2 AND c = 3", compact(m.renderSql().sql()));
    }
}
