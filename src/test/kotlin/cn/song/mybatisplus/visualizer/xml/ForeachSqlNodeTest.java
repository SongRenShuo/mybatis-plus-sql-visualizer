package cn.song.mybatisplus.visualizer.xml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * foreach 节点测试（T-M24-01）：复刻 MyBatis ForEachSqlNode——
 * `__frch_` 重写（item/index 独立名、嵌套属性、不误伤 itemName）、Map 迭代 index=键、
 * 空集合无输出、null 报错/可空、separator 惰性插入（首项无内容顺延）、嵌套遮蔽。
 */
public class ForeachSqlNodeTest {

    private static ForeachSqlNode foreach(
            String collection,
            String item,
            String index,
            SqlNode content,
            String separator,
            String open,
            String close,
            boolean nullable
    ) {
        return new ForeachSqlNode(
                new MixedSqlNode(List.of(content)),
                collection,
                separator,
                open,
                close,
                item,
                index,
                nullable);
    }

    private static ForeachSqlNode foreach(String collection) {
        return foreach(collection, "x", null, new StaticTextSqlNode("#{x}"), ",", "(", ")", false);
    }

    private static ForeachSqlNode foreach(String collection, SqlNode content) {
        return foreach(collection, "x", null, content, ",", "(", ")", false);
    }

    private static ForeachSqlNode foreach(String collection, String index, SqlNode content) {
        return foreach(collection, "x", index, content, ",", "(", ")", false);
    }

    private static RenderedXmlSql render(SqlNode node, Map<String, Object> bindings) {
        return new XmlSqlRenderer().render(node, bindings);
    }

    private static List<String> props(RenderedXmlSql result) {
        return result.mappings().stream().map(ParameterMapping::property).collect(Collectors.toList());
    }

    @Test
    public void 基本展开_item重写为frch名() throws Exception {
        RenderedXmlSql result = render(foreach("list"), Map.<String, Object>of("list", List.of("a", "b")));
        assertEquals("( ? , ? )", result.sql());
        assertEquals(List.of("__frch_x_0", "__frch_x_1"), props(result));
    }

    @Test
    public void 嵌套属性重写() throws Exception {
        RenderedXmlSql result = render(foreach("list", new StaticTextSqlNode("#{x.id}")),
                Map.<String, Object>of("list", List.of("a")));
        assertEquals("( ? )", result.sql());
        assertEquals(List.of("__frch_x_0.id"), props(result));
    }

    @Test
    public void itemName不重写() throws Exception {
        RenderedXmlSql result = render(foreach("list", new StaticTextSqlNode("#{xname}")),
                Map.<String, Object>of("list", List.of("a")));
        assertEquals(List.of("xname"), props(result));
    }

    @Test
    public void index绑定为序号() throws Exception {
        RenderedXmlSql result = render(
                foreach("list", "x", "i", new StaticTextSqlNode("#{i}:#{x}"), ",", "(", ")", false),
                Map.<String, Object>of("list", List.of("a", "b")));
        assertEquals("( ?:? , ?:? )", result.sql());
        assertEquals(
                List.of("__frch_i_0", "__frch_x_0", "__frch_i_1", "__frch_x_1"),
                props(result));
    }

    @Test
    public void Map迭代_index为键_item为值() throws Exception {
        RenderedXmlSql result = render(
                foreach("m", "x", "i", new StaticTextSqlNode("#{i}:#{x}"), ",", "(", ")", false),
                Map.<String, Object>of("m", Map.of("k1", 1, "k2", 2)));
        assertEquals("( ?:? , ?:? )", result.sql());
        assertEquals(
                List.of("__frch_i_0", "__frch_x_0", "__frch_i_1", "__frch_x_1"),
                props(result));
    }

    @Test
    public void 空集合整段不输出_open与close都不出现() throws Exception {
        RenderedXmlSql result = render(foreach("list"), Map.<String, Object>of("list", List.of()));
        assertEquals("", result.sql());
    }

    @Test
    public void null集合默认报错记录() throws Exception {
        RenderedXmlSql result = render(foreach("list"), java.util.Collections.<String, Object>singletonMap("list", null));
        assertEquals("", result.sql());
        // T-MbgExamplePreview R6：文案已中文化（原 MyBatis 英文原文透传到中文 UI）
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("求值为 null")));
    }

    @Test
    public void nullable为true时null集合静默跳过() throws Exception {
        RenderedXmlSql result = render(foreach("list", "x", null, new StaticTextSqlNode("#{x}"), ",", "(", ")", true),
                java.util.Collections.<String, Object>singletonMap("list", null));
        assertEquals("", result.sql());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    public void separator仅在项之间_首尾无() throws Exception {
        RenderedXmlSql result = render(foreach("list"), Map.<String, Object>of("list", List.of(1, 2, 3)));
        assertEquals("( ? , ? , ? )", result.sql());
    }

    @Test
    public void 首项无内容时separator顺延到有内容的迭代() throws Exception {
        ForeachSqlNode node = foreach("list", "x", null,
                new IfSqlNode(new StaticTextSqlNode("#{x}"), "x != null"), ",", "(", ")", false);
        // 第一项 null → if 关闭（无输出）；第二项 "v" → 输出；first 顺延 → 无 separator
        RenderedXmlSql result = render(node, java.util.Collections.<String, Object>singletonMap("list", java.util.Arrays.asList(null, "v")));
        assertEquals("( ? )", result.sql());
        assertEquals(List.of("__frch_x_1"), props(result));
    }

    @Test
    public void open与close自定义() throws Exception {
        ForeachSqlNode node = foreach("list", "x", null, new StaticTextSqlNode("#{x}"), ", ", "IN (", ")", false);
        RenderedXmlSql result = render(node, Map.<String, Object>of("list", List.of(1, 2)));
        assertEquals("IN ( ? ,  ? )", result.sql());
    }

    @Test
    public void 迭代内容换行缩进被trim_预览紧凑() throws Exception {
        // T-M25.5（用户实测）：foreach 体 "\n  #{x}\n" 原样输出使 IN 每项换行——trim 后单行紧凑
        ForeachSqlNode node = new ForeachSqlNode(
                new MixedSqlNode(List.of(new StaticTextSqlNode("\n  #{x}\n"))),
                "list",
                ",",
                "(",
                ")",
                "x",
                null,
                false);
        RenderedXmlSql result = render(node, Map.<String, Object>of("list", List.of("a", "b")));
        assertEquals("( ? , ? )", result.sql().replaceAll("\\s+", " ").trim());
    }

    @Test
    public void 嵌套foreach同名item内层遮蔽外层() throws Exception {
        ForeachSqlNode outer = new ForeachSqlNode(
                new MixedSqlNode(
                        List.of(
                                new StaticTextSqlNode("#{x}->"),
                                foreach("list", new StaticTextSqlNode("#{x}")))),
                "list",
                null,
                null,
                null,
                "x",
                null,
                false);
        RenderedXmlSql result = render(outer, Map.<String, Object>of("list", List.of("a")));
        assertEquals("?-> ( ? )", result.sql());
        // 外层 __frch_x_0；内层独立编号 __frch_x_1（遮蔽）
        assertEquals(List.of("__frch_x_0", "__frch_x_1"), props(result));
    }
}
