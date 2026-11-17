package cn.song.mybatisplus.visualizer.xml.ibatis;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 一个 {@code <sqlMap>} 文件的解析产物（语句 + 参数图 + 片段清单 + 降级告警）。
 *
 * @param namespace     {@code namespace} 属性原值；{@code #IMPLIED}，可为 null（iBatis 2 里 namespace 不是必填）
 * @param statements    语句清单
 * @param parameterMaps {@code <parameterMap>} 声明清单
 * @param sqlFragmentIds {@code <sql id>} 清单（原样 id，与引擎默认 {@code useStatementNamespaces=false} 的注册键一致）
 * @param warnings      降级/配置告警
 */
public record IbatisSqlMap(
        @Nullable String namespace,
        List<IbatisStatement> statements,
        List<IbatisParameterMap> parameterMaps,
        List<String> sqlFragmentIds,
        List<String> warnings) {

    public IbatisSqlMap {
        statements = List.copyOf(statements);
        parameterMaps = List.copyOf(parameterMaps);
        sqlFragmentIds = List.copyOf(sqlFragmentIds);
        warnings = List.copyOf(warnings);
    }

    /** 按 id 查语句；不存在返回 null。 */
    @Nullable
    public IbatisStatement statementById(String id) {
        Objects.requireNonNull(id, "id");
        for (IbatisStatement statement : statements) {
            if (statement.getId().equals(id)) {
                return statement;
            }
        }
        return null;
    }

    /** 含 {@code selectKey} 生成的语句在内的全部 id（供 UI 列出）。 */
    public List<String> getAllStatementIds() {
        List<String> ids = new ArrayList<>();
        for (IbatisStatement statement : statements) {
            ids.add(statement.getId());
            for (IbatisSelectKey selectKey : statement.getSelectKeys()) {
                ids.add(selectKey.id());
            }
        }
        return List.copyOf(ids);
    }
}
