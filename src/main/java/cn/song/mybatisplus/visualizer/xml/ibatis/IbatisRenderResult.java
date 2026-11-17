package cn.song.mybatisplus.visualizer.xml.ibatis;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 渲染产物（纯数据，不依赖 model/PreviewResult——类型转换由接线车道负责）。
 *
 * @param sql                 {@code ?} 形态的 SQL（{@code #} 已转 {@code ?}；{@code $} 已按参数值直替）
 * @param compactSql          空白折叠后的单行形态（面板「紧凑视图」用；<b>不改变 sql 的保真性</b>）
 * @param paramNames          按 {@code ?} 出现顺序的参数名（iterate 内为 {@code ids[0]}/{@code ids[1]} 形态）
 * @param paramJdbcTypes      与 paramNames 同序的 {@code jdbcType}（{@code #p:TYPE#} 的第二段）
 * @param paramNullValues     与 paramNames 同序的 {@code nullValue} 哨兵（{@code #p:TYPE:NULL值#} 的第三段）
 * @param nullBindFlags       与 paramNames 同序：该参数值是否等于 nullValue 哨兵（true ⇒ 引擎按 SQL NULL 绑定）
 * @param warnings            降级/配置错误告警（含语句 id 可回溯）
 * @param dollarRawCount      {@code $x$} 直替<b>站点数</b>（含值为 null 被静默丢弃的站点）
 * @param dollarRenderedValues 实际写入 SQL 的 {@code $} 取值（<b>注入闸门接既有 ${} 确认通道</b>用）
 * @param usedStatementIds    本次渲染涉及的语句 id（含展开的 {@code <include>} 归属主语句）
 * @param usedSqlFragmentIds  展开过的 {@code <sql id>} 清单
 * @param degraded            券 D（T-Ibatis2Gap）：本次渲染是否走了降级回退（renderBody 抛错后仅按顶层文本节点拼装）。
 *                            默认 false——既有构造点零影响；UI 侧据此区分「正常渲染」与「半截 SQL」，不再静默。
 * @param unrenderedNodes     券 D：降级时未渲染的顶层节点事实（失败节点 + 失败原因分类 + 涉及的 property）。
 *                            非降级恒空。
 * @param unusedDeclaredParams 券 C：parameterMap 声明了、但语句体参数位点（手写 {@code ?} / 内联 {@code #x#} 对）
 *                            没消费到的映射——供 UI 提示「声明了但未消费」。按位取前 N 之后的剩余，
 *                            声明数 ≤ 位点数时恒空。
 */
public record IbatisRenderResult(
        String sql,
        String compactSql,
        List<String> paramNames,
        List<@Nullable String> paramJdbcTypes,
        List<@Nullable String> paramNullValues,
        List<Boolean> nullBindFlags,
        List<String> warnings,
        int dollarRawCount,
        List<String> dollarRenderedValues,
        List<String> usedStatementIds,
        List<String> usedSqlFragmentIds,
        boolean degraded,
        List<IbatisUnrenderedNode> unrenderedNodes,
        List<IbatisParamMapping> unusedDeclaredParams) {

    public IbatisRenderResult {
        paramNames = List.copyOf(paramNames);
        paramJdbcTypes = List.copyOf(paramJdbcTypes);
        paramNullValues = List.copyOf(paramNullValues);
        nullBindFlags = List.copyOf(nullBindFlags);
        warnings = List.copyOf(warnings);
        dollarRenderedValues = List.copyOf(dollarRenderedValues);
        usedStatementIds = List.copyOf(usedStatementIds);
        usedSqlFragmentIds = List.copyOf(usedSqlFragmentIds);
        unrenderedNodes = List.copyOf(unrenderedNodes);
        unusedDeclaredParams = List.copyOf(unusedDeclaredParams);
    }

    /** 既有构造点的正常渲染形态（无降级信息，券 D/C 字段取默认）。 */
    public IbatisRenderResult(String sql,
                              String compactSql,
                              List<String> paramNames,
                              List<@Nullable String> paramJdbcTypes,
                              List<@Nullable String> paramNullValues,
                              List<Boolean> nullBindFlags,
                              List<String> warnings,
                              int dollarRawCount,
                              List<String> dollarRenderedValues,
                              List<String> usedStatementIds,
                              List<String> usedSqlFragmentIds) {
        this(sql, compactSql, paramNames, paramJdbcTypes, paramNullValues, nullBindFlags, warnings,
                dollarRawCount, dollarRenderedValues, usedStatementIds, usedSqlFragmentIds,
                false, List.of(), List.of());
    }

    /** 是否含 {@code $} 直替段（注入闸门判据，等价于既有 PreviewResult.hasDollarRawSegments 语义）。 */
    public boolean hasDollarRawSegments() {
        return !dollarRenderedValues.isEmpty();
    }
}
