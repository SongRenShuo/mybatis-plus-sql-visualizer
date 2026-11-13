package cn.song.mybatisplus.visualizer.xml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 注解 SQL 解析器测试（T-M25-02）：script/纯文本分派、动态标签渲染、参数名 mock 注入、
 * kind 映射、非良构 script 报错——对齐 MyBatis MapperAnnotationBuilder 构建规则。
 */
public class AnnotationSqlParserTest {

    private final AnnotationSqlParser parser = new AnnotationSqlParser();

    private static String compact(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    @Test
    public void 非script纯文本_参数占位与映射() throws Exception {
        XmlPreviewModel model = parser.parse("SELECT * FROM t WHERE id = #{id}", XmlStatementKind.SELECT, List.of());
        RenderedXmlSql rendered = model.renderSql();
        assertEquals("SELECT * FROM t WHERE id = ?", compact(rendered.sql()));
        List<String> props = new ArrayList<>();
        for (ParameterMapping m : rendered.mappings()) props.add(m.property());
        assertEquals(List.of("id"), props);
        assertTrue(model.getConditionViews().isEmpty());
    }

    @Test
    public void 非script纯文本_尖括号无需转义() throws Exception {
        XmlPreviewModel model = parser.parse("SELECT * FROM t WHERE age < 18 AND id = #{id}", XmlStatementKind.SELECT, List.of());
        assertEquals("SELECT * FROM t WHERE age < 18 AND id = ?", compact(model.renderSql().sql()));
    }

    @Test
    public void 非script纯文本_dollar根变量占位求值() throws Exception {
        // 根变量统一占位 "1"（对齐 ContextMap → _parameter fallback）
        XmlPreviewModel model = parser.parse("SELECT ${cols} FROM t", XmlStatementKind.SELECT, List.of());
        assertEquals("SELECT 1 FROM t", compact(model.renderSql().sql()));
    }

    @Test
    public void 非script纯文本_dollar属性链求值为空时提示() throws Exception {
        XmlPreviewModel model = parser.parse("SELECT ${x.y} FROM t", XmlStatementKind.SELECT, List.of());
        RenderedXmlSql rendered = model.renderSql();
        // T-MbgExamplePreview R1：预览链占位模式——求值为空写成 {{表达式}}，不再是无声空串
        assertEquals("SELECT {{x.y}} FROM t", compact(rendered.sql()));
        boolean anyEmpty = rendered.errors().stream().anyMatch(e -> e.contains("求值为空"));
        assertTrue(anyEmpty);
    }

    @Test
    public void script_动态标签全兼容() throws Exception {
        XmlPreviewModel model = parser.parse(
                "<script>SELECT * FROM t" +
                        "<where><if test=\"a != null\">AND a = #{a}</if></where>" +
                        "<if test=\"ids != null\">AND id IN <foreach collection=\"ids\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach></if>" +
                        "</script>",
                XmlStatementKind.SELECT,
                List.of()
        );
        RenderedXmlSql rendered = model.renderSql();
        assertEquals(
                "SELECT * FROM t WHERE a = ? AND id IN ( ? , ? )",
                compact(rendered.sql())
        );
        assertEquals(2, model.getConditionViews().size());
        List<String> colls = new ArrayList<>();
        for (XmlForEachView v : model.getForEachViews()) colls.add(v.collectionExpr());
        assertEquals(List.of("ids"), colls);
    }

    @Test
    public void script_条件开关关闭分支() throws Exception {
        XmlPreviewModel model = parser.parse(
                "<script>SELECT * FROM t<if test=\"a != null\"> WHERE a = 1</if></script>",
                XmlStatementKind.SELECT,
                List.of()
        );
        XmlPreviewState state = new XmlPreviewState(Set.of(), Map.of(), Set.of(), Map.of());
        assertEquals("SELECT * FROM t", compact(model.renderSql(state).sql()));
    }

    @Test
    public void 参数名mock注入_param1可求值() throws Exception {
        // MyBatis 多参数 Map 键：@Param 名/实际名 + param1..N
        XmlPreviewModel model = parser.parse(
                "SELECT * FROM t WHERE a = ${param1} AND b = ${id}",
                XmlStatementKind.SELECT,
                List.of("id", "param1")
        );
        assertEquals("SELECT * FROM t WHERE a = 1 AND b = 1", compact(model.renderSql().sql()));
    }

    @Test
    public void kind透传() throws Exception {
        XmlPreviewModel model = parser.parse("UPDATE t SET a = 1", XmlStatementKind.UPDATE, List.of());
        assertEquals(XmlStatementKind.UPDATE, model.getKind());
    }

    @Test
    public void script非良构报错() throws Exception {
        assertThrows(XmlParseException.class, () ->
                parser.parse("<script><if test=\"a\">x</script>", XmlStatementKind.SELECT, List.of()));
    }

    @Test
    public void script内include报未知元素_与MyBatis一致() throws Exception {
        assertThrows(XmlScriptException.class, () ->
                parser.parse("<script>SELECT * FROM t <include refid=\"cols\"/></script>", XmlStatementKind.SELECT, List.of()));
    }

    // ------------------------------------------------------------------
    // T-XmlDtdHardening T-XDH-05：注解 <script> 路径与 XML mapper 同规（共用 parseDocument 入口）
    // ------------------------------------------------------------------

    @Test
    public void script内DTD指数膨胀被拒绝_与mapper同规() throws Exception {
        // 注解链与 mapper 链共用 XmlMapperParser.parseDocument → 同一膨胀预扫。`<script>` 之后的
        // DOCTYPE 位置本身非法（DOCTYPE 只能在序言），但预扫先于 DOM 解析，故拒绝口径是膨胀判据
        // 而非「DOCTYPE 位置非法」——本用例钉住「共享入口」这一事实。
        StringBuilder sb = new StringBuilder();
        sb.append("<script><!DOCTYPE script [\n<!ENTITY e0 \"xxxxxxxxxx\">\n");
        for (int level = 1; level <= 4; level++) {
            sb.append("<!ENTITY e").append(level).append(" \"");
            for (int i = 0; i < 10; i++) {
                sb.append("&e").append(level - 1).append(';');
            }
            sb.append("\">\n");
        }
        sb.append("]><select id=\"s1\">&e4;</select></script>");
        String script = sb.toString();
        XmlParseException e = assertThrows(XmlParseException.class, () ->
                parser.parse(script, XmlStatementKind.SELECT, List.of()));
        String msg = e.getMessage() == null ? "" : e.getMessage();
        assertTrue("拒绝原因须点明实体展开，实际：" + msg, msg.contains("实体展开"));
    }

    @Test
    public void script内无法夹带DTD声明_序言契约拒绝() throws Exception {
        // 注解 SQL 文本恒以 `<script>` 开头（MyBatis XMLLanguageDriver 的包装形态），DOCTYPE 只能在
        // 序言出现 → 注解路径无法夹带内联子集/实体定义；本用例钉住「夹带即拒绝」
        String script =
                "<script><!DOCTYPE script [<!ENTITY status \"ACTIVE\">]><select id=\"s1\">SELECT '&status;'</select></script>";
        assertThrows(XmlParseException.class, () ->
                parser.parse(script, XmlStatementKind.SELECT, List.of()));
    }
}
