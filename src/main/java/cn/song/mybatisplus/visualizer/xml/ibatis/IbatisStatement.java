package cn.song.mybatisplus.visualizer.xml.ibatis;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 一条可执行语句（{@code select}/{@code insert}/{@code update}/{@code delete}/{@code statement}/{@code procedure}）。
 *
 * @param id                          语句 id
 * @param kind                        语句种类
 * @param kindTagName                 语句标签名原值
 * @param attributes                  语句标签属性原值
 * @param parameterMapRef             {@code parameterMap} 属性原值（未加 namespace 前缀）；缺省 null
 * @param hasParameterMapDeclaration  引擎 {@code MappedStatementConfig.applyInlineParameterMap} 的
 *                                    {@code if (parameterMap == null)} 守卫判据。<b>只在非动态语句上生效</b>：
 *                                    动态语句（有任一动态标签）走 {@code DynamicSql}，{@code #} 一律转 {@code ?}
 *                                    （{@code setSqlForStatement} 的 {@code sql instanceof DynamicSql} 分支）——这是本链必须复刻的形态。
 * @param body                        语句体节点
 * @param parameterMap                命中的 {@code <parameterMap>} 声明（按 applyNamespace 解析）；缺省 null
 * @param usedSqlFragmentIds          语句体内展开过的 {@code <sql id>}（解析期收集，渲染产物直接透出）
 * @param selectKeys                  语句的 {@code <selectKey>} 产物
 * @param warnings                    降级/配置错误告警（含语句 id 可回溯）
 */
public final class IbatisStatement {

    private final String id;
    private final IbatisStatementKind kind;
    private final String kindTagName;
    private final Map<String, String> attributes;
    @Nullable
    private final String parameterMapRef;
    private final boolean hasParameterMapDeclaration;
    private final List<IbatisSqlNode> body;
    @Nullable
    private final IbatisParameterMap parameterMap;
    private final List<String> usedSqlFragmentIds;
    private final List<IbatisSelectKey> selectKeys;
    private final List<String> warnings;

    public IbatisStatement(String id,
                           IbatisStatementKind kind,
                           String kindTagName,
                           Map<String, String> attributes,
                           @Nullable String parameterMapRef,
                           boolean hasParameterMapDeclaration,
                           List<IbatisSqlNode> body,
                           @Nullable IbatisParameterMap parameterMap,
                           List<String> usedSqlFragmentIds,
                           List<IbatisSelectKey> selectKeys,
                           List<String> warnings) {
        this.id = id;
        this.kind = kind;
        this.kindTagName = kindTagName;
        this.attributes = Map.copyOf(attributes);
        this.parameterMapRef = parameterMapRef;
        this.hasParameterMapDeclaration = hasParameterMapDeclaration;
        this.body = List.copyOf(body);
        this.parameterMap = parameterMap;
        this.usedSqlFragmentIds = List.copyOf(usedSqlFragmentIds);
        this.selectKeys = List.copyOf(selectKeys);
        this.warnings = List.copyOf(warnings);
    }

    /** 无 parameterMap 命中、无片段/selectKey/warning 的基础形态（解析器常规路径）。 */
    public IbatisStatement(String id,
                           IbatisStatementKind kind,
                           String kindTagName,
                           Map<String, String> attributes,
                           @Nullable String parameterMapRef,
                           boolean hasParameterMapDeclaration,
                           List<IbatisSqlNode> body) {
        this(id, kind, kindTagName, attributes, parameterMapRef, hasParameterMapDeclaration,
                body, null, List.of(), List.of(), List.of());
    }

    public String getId() {
        return id;
    }

    public IbatisStatementKind getKind() {
        return kind;
    }

    public String getKindTagName() {
        return kindTagName;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    @Nullable
    public String getParameterMapRef() {
        return parameterMapRef;
    }

    public boolean isHasParameterMapDeclaration() {
        return hasParameterMapDeclaration;
    }

    public List<IbatisSqlNode> getBody() {
        return body;
    }

    @Nullable
    public IbatisParameterMap getParameterMap() {
        return parameterMap;
    }

    public List<String> getUsedSqlFragmentIds() {
        return usedSqlFragmentIds;
    }

    public List<IbatisSelectKey> getSelectKeys() {
        return selectKeys;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    /** 引擎 {@code XMLSqlSource} 的 {@code isDynamic}：出现任一已注册标签即为动态语句。 */
    public boolean isDynamic() {
        for (IbatisSqlNode node : body) {
            if (node instanceof IbatisTagNode) {
                return true;
            }
        }
        return false;
    }
}
