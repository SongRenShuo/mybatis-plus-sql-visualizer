package cn.song.mybatisplus.visualizer.xml.ibatis;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * {@code <selectKey>} 生成的独立语句（引擎 statement id = {@code <insertId>-SelectKey}）。
 *
 * @param id          生成的语句 id（{@code <insertId>-SelectKey}）
 * @param runAfterSql 引擎 {@code SelectKeyStatement.runAfterSQL}：{@code type="post"} 优先，
 *                    否则由位置推断（selectKey 前是否已有非空 SQL 文本）
 * @param attributes  selectKey 标签属性原值
 * @param body        selectKey 语句体节点
 * @param warnings    降级/配置告警
 */
public record IbatisSelectKey(
        String id,
        boolean runAfterSql,
        Map<String, String> attributes,
        List<IbatisSqlNode> body,
        List<String> warnings) {

    public IbatisSelectKey {
        attributes = Map.copyOf(attributes);
        body = List.copyOf(body);
        warnings = List.copyOf(warnings);
    }

    public IbatisSelectKey(String id, boolean runAfterSql, Map<String, String> attributes,
                           List<IbatisSqlNode> body) {
        this(id, runAfterSql, attributes, body, List.of());
    }
}
