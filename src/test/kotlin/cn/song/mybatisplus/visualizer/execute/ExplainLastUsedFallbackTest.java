package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * T-DialectInferPollution W9 护栏：EXPLAIN 链回落证据选择
 * （{@link DataSourceSelector#resolveExplainFallbackChoice}）纯逻辑表。
 *
 * <p>缺陷形态（2026-10-24 用户沙盒实测）：W4/W8 的「无脑回落 last-used」把 mp_demo 硬套给
 * jpa-jar-model-demo 模块（表在 jpa_demo）→ {@code mp_demo.wms_inventory_jar_demo doesn't exist}。
 * 修正语义：</p>
 * <ul>
 *  <li>预览模块有学习绑定 → 用学习源（模块归属强于全局记忆，UUID 解析）；</li>
 *  <li>学习源与 last-used 同时存在且不同 → <b>冲突不猜</b>，返回 null（下游 NO_DATA_SOURCE 引导
 *      弹数据源选择器，用户选一次即自愈更新学习与记忆）；</li>
 *  <li>学习缺失 → last-used 兜底（demo 主模块等既有场景）。</li>
 * </ul>
 *
 * <p>本类零平台引用（testFast 快桶成员）。</p>
 *
 * <p>K2J 承载注（收口勘误）：{@code ownerIndexesForSources} 形参是
 * {@code List<java.util.function.Function<String, java.util.Set<String>>>}——Kotlin 侧显式 SAM
 * 构造与生产灵活平台类型在泛型不变位恒不等（K2 实测 5 错），改 Java 承载（测试树混放惯例）。</p>
 */
public class ExplainLastUsedFallbackTest {

    @Test
    public void testLearnedEqualsLastIsConsistent() {
        Assert.assertEquals(
                "学习源与 last-used 同源 → 一致，取该源",
                "same-uuid",
                DataSourceSelector.resolveExplainFallbackChoice("same-uuid", "same-uuid"));
    }

    @Test
    public void testConflictBetweenLearnedAndLastIsAbandoned() {
        Assert.assertNull(
                "学习源与 last-used 不同源 → 冲突不猜（错源执行比引导选择更糟，W9 核心语义）",
                DataSourceSelector.resolveExplainFallbackChoice("dbx-test-pg", "mp-demo-mysql"));
    }

    @Test
    public void testLastUsedFallsBackWhenNoLearning() {
        Assert.assertEquals(
                "模块无学习 → last-used 兜底（W4/W8 既有场景）",
                "last-uuid",
                DataSourceSelector.resolveExplainFallbackChoice(null, "last-uuid"));
        Assert.assertNull("两者皆无 → null（下游 NO_DATA_SOURCE 引导）",
                DataSourceSelector.resolveExplainFallbackChoice(null, null));
    }

    // ======================== W10：表归属路由（缓存源查询器） ========================

    /** 假源查询器：入参=表名，返回该源里这张表的列名集（P7 lookup 同形；未持有返回 null）。 */
    private static Function<String, Set<String>> sourceOf(Map.Entry<String, Set<String>>... tables) {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : tables) {
            map.put(entry.getKey(), entry.getValue());
        }
        return map::get;
    }

    private static Map.Entry<String, Set<String>> entry(String table, String... columns) {
        return Map.entry(table, new LinkedHashSet<>(Arrays.asList(columns)));
    }

    @Test
    public void testUniqueOwnerIsRouted() {
        List<Function<String, Set<String>>> sources = List.of(
                sourceOf(entry("user", "id", "name")),                        // mp_demo：只有 user
                sourceOf(entry("wms_inventory_jar_demo", "id", "qty")));      // jpa_demo：有 jar 表
        List<Integer> owners = DatabaseSchemaResolver
                .ownerIndexesForSources(sources, List.of("wms_inventory_jar_demo"));
        Assert.assertEquals(
                "唯一持有者应被路由（jar demo 表在 jpa_demo → 自动改取 jpa_demo）",
                List.of(1),
                owners);
    }

    @Test
    public void testCandidateOwnerShortCircuitsOthers() {
        List<Function<String, Set<String>>> sources = List.of(
                sourceOf(entry("user", "id", "name")),
                sourceOf(entry("user", "id", "name"), entry("order_entity", "id")));
        Assert.assertEquals(
                "候选源持有全部表 → 候选可信（owner 集合含候选即保持现行为，由调用方判定）",
                List.of(0, 1),
                DatabaseSchemaResolver.ownerIndexesForSources(sources, List.of("user")));
    }

    @Test
    public void testNoOwnerOrAmbiguityYieldsEmptyForCallerFallback() {
        List<Function<String, Set<String>>> sources = List.of(
                sourceOf(entry("user", "id", "name")),
                sourceOf(entry("order_entity", "id")));
        Assert.assertEquals(
                "无源持有该表（缓存未热/表不存在）→ 空清单（调用方保持候选，诚实降级）",
                List.of(),
                DatabaseSchemaResolver.ownerIndexesForSources(sources, List.of("wms_inventory_jar_demo")));
        Assert.assertEquals(
                "空表集 → 空清单",
                List.of(),
                DatabaseSchemaResolver.ownerIndexesForSources(sources, List.of()));
    }
}
