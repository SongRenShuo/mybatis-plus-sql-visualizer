package cn.song.mybatisplus.visualizer.converter;

import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.SetClause;
import cn.song.mybatisplus.visualizer.model.SqlFragment;
import cn.song.mybatisplus.visualizer.model.SqlStatement;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.UpdateStatement;
import cn.song.mybatisplus.visualizer.model.WrapperArg;
import cn.song.mybatisplus.visualizer.model.WrapperCall;
import cn.song.mybatisplus.visualizer.model.WrapperChain;
import cn.song.mybatisplus.visualizer.model.WrapperType;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * UpdateJoinWrapper 转换器（T-M21-08，MPJ 官方联表更新，1.4.5+）。
 * `UPDATE user t LEFT JOIN address t1 ON (...) SET t.name = ?, t1.tel = ? WHERE (t.id = ?)`。
 *
 * 已知简化（KDoc 标注）：跨表 SET 的列归属静态不可判——方法引用列统一补主表别名近似；
 * setUpdateEntity/AndNull 的实体字段值运行时不可知 → 主表非主键全列 ? 占位近似。
 */
public final class UpdateJoinConverter implements WrapperConverter {
    /** 单例（Kotlin object 转换）。 */
    public static final UpdateJoinConverter INSTANCE = new UpdateJoinConverter();

    private static final String MAIN_ALIAS_DEFAULT = "t";
    private static final String SET = "set";
    private static final String SET_IF_EXISTS = "setIfExists";
    private static final String SET_SQL = "setSql";
    private static final String SET_APPLY = "setApply";
    private static final String SET_INCR = "setIncrBy";
    private static final String SET_DECR = "setDecrBy";
    private static final String SET_UPDATE_ENTITY = "setUpdateEntity";
    private static final String SET_UPDATE_ENTITY_AND_NULL = "setUpdateEntityAndNull";
    private static final String SET_ALIAS = "setAlias";
    private static final String LAST = "last";
    private static final Set<String> SET_METHODS = Set.of(
            SET, SET_IF_EXISTS, SET_SQL, SET_APPLY, SET_INCR, SET_DECR, SET_UPDATE_ENTITY, SET_UPDATE_ENTITY_AND_NULL);

    private UpdateJoinConverter() {
    }

    @Override
    public Set<WrapperType> getSupportedTypes() {
        return Set.of(WrapperType.UPDATE_JOIN);
    }

    @Override
    public SqlStatement convert(WrapperChain chain, TableInfo table, Project project) {
        String mainAlias = chain.getConstructorAlias();
        if (mainAlias == null) {
            WrapperCall setAlias = null;
            for (WrapperCall c : chain.getCalls()) {
                if (SET_ALIAS.equals(c.getMethodName())) { setAlias = c; break; }
            }
            if (setAlias != null) {
                SqlValue v = WrapperConverterKt.asValue(argAt(setAlias.getArgs(), 0));
                if (v instanceof SqlValue.Literal lit) mainAlias = lit.getUnquoted();
            }
        }
        if (mainAlias == null) mainAlias = MAIN_ALIAS_DEFAULT;

        // SET 赋值（按源码顺序；setUpdateEntity 展开为多列）
        // T-UpdateSetSqlConcat：解析失败的 SET 调用原文（UI 警示用）
        List<String> unparsedSets = new ArrayList<>();
        List<SetClause> sets = new ArrayList<>();
        for (WrapperCall call : chain.getCalls()) {
            if (SET_METHODS.contains(call.getMethodName())) {
                sets.addAll(parseSet(call, table, mainAlias, unparsedSets));
            }
        }

        List<WrapperCall> joinCalls = new ArrayList<>();
        for (WrapperCall call : chain.getCalls()) {
            if (MpjJoinSupport.JOIN_METHODS.contains(call.getMethodName())) joinCalls.add(call);
        }
        List<cn.song.mybatisplus.visualizer.model.JoinClause> joins = new ArrayList<>();
        for (WrapperCall call : joinCalls) {
            // T-FS-04：project 透传至类名兜底推断
            cn.song.mybatisplus.visualizer.model.JoinClause j = MpjJoinSupport.INSTANCE.parseJoin(call, mainAlias, project);
            if (j != null) joins.add(j);
        }

        List<WrapperCall> conditionCalls = new ArrayList<>();
        for (WrapperCall call : chain.getCalls()) {
            String m = call.getMethodName();
            if (!SET_METHODS.contains(m) && !MpjJoinSupport.JOIN_METHODS.contains(m)
                    && !SET_ALIAS.equals(m) && !LAST.equals(m)) {
                conditionCalls.add(call);
            }
        }
        List<cn.song.mybatisplus.visualizer.model.Condition> conditions = new QueryWrapperParser(table, mainAlias)
                .parse(new WrapperChain(chain.getWrapperType(), chain.getEntityClass(), conditionCalls, null, false, false, false, false, null))
                .getConditions();

        // last 非 limit 尾缀（U05 语义对齐）：`last("AND (... >= 0)")` 原样追加到语句末尾
        // T-UpdateSetSqlConcat：片段位取 asSqlFragment——含运行期段的拼接不再整段丢弃
        SqlFragment tailFragment = null;
        for (WrapperCall call : chain.getCalls()) {
            if (LAST.equals(call.getMethodName())) {
                SqlFragment f = WrapperConverterKt.asSqlFragment(argAt(call.getArgs(), 0));
                if (f != null && f.getText() != null && !f.getText().regionMatches(true, 0, "limit", 0, 5)) {
                    tailFragment = f;
                    break;
                }
            }
        }

        return new UpdateStatement(table, sets, conditions,
                tailFragment != null ? tailFragment.getText() : null, mainAlias, joins,
                unparsedSets,
                tailFragment != null ? tailFragment.getParams() : List.of());
    }

    /**
     * 解析 set 类调用为赋值子句（T-M21-08）。
     *
     * @param unparsedSets 解析失败的调用原文收集器（T-UpdateSetSqlConcat：UI 警示用）
     * @return 赋值列表（setUpdateEntity 展开为多列；解析失败返回空列表）
     */
    private List<SetClause> parseSet(WrapperCall call, TableInfo table, String mainAlias, List<String> unparsedSets) {
        List<WrapperArg> args = call.getArgs();
        switch (call.getMethodName()) {
            case SET, SET_IF_EXISTS -> {
                int startIndex = (args.size() >= 3 && isFlagArgument(args.get(0))) ? 1 : 0;
                ColumnRef column = WrapperConverterKt.asColumn(argAt(args, startIndex));
                SqlValue value = WrapperConverterKt.asValue(argAt(args, startIndex + 1));
                if (column == null || value == null) {
                    unparsedSets.add(setCallSummary(call));
                    return List.of();
                }
                return List.of(new SetClause(withMainAliasIfUnqualified(column, mainAlias), value, null));
            }
            case SET_SQL, SET_APPLY -> {
                int startIndex = (args.size() >= 2 && isFlagArgument(args.get(0))) ? 1 : 0;
                // T-UpdateSetSqlConcat：片段位取 asSqlFragment（静态段保留、运行期段写 `?`）
                SqlFragment fragment = WrapperConverterKt.asSqlFragment(argAt(args, startIndex));
                if (fragment == null) {
                    unparsedSets.add(setCallSummary(call));
                    return List.of();
                }
                // `{i}` 绑定参数替换 + 按最终文本顺序登记占位符来源
                List<SqlValue> bindings = new ArrayList<>();
                List<WrapperArg> tail = args.size() > startIndex + 1 ? args.subList(startIndex + 1, args.size()) : java.util.List.of();
                for (WrapperArg a : tail) {
                    bindings.add(WrapperConverterKt.asValue(a));
                }
                SqlFragment rendered = WrapperConverterKt.renderSqlFragment(fragment, bindings);
                return List.of(new SetClause(null, null, rendered.getText(), rendered.getParams()));
            }
            case SET_INCR, SET_DECR -> {
                int startIndex = (args.size() >= 3 && isFlagArgument(args.get(0))) ? 1 : 0;
                ColumnRef column = WrapperConverterKt.asColumn(argAt(args, startIndex));
                SqlValue value = WrapperConverterKt.asValue(argAt(args, startIndex + 1));
                if (column == null || value == null) {
                    unparsedSets.add(setCallSummary(call));
                    return List.of();
                }
                // T-M23.5：列名按方言引用（保留字列名反引号包裹，如 t.`table`）
                String qualified = withMainAliasIfUnqualified(column, mainAlias)
                        .qualifiedName(cn.song.mybatisplus.visualizer.psi.PsiDialect.currentDialect());
                String op = SET_INCR.equals(call.getMethodName()) ? "+" : "-";
                return List.of(new SetClause(null, null,
                        qualified + " = " + qualified + " " + op + " " + renderSetValue(value),
                        // T-UpdateSetSqlConcat：rawSql 里的 `?` 也要登记来源（否则后续条件整体错位）
                        value instanceof SqlValue.Placeholder ph ? List.of(ph.getSource()) : List.of()));
            }
            case SET_UPDATE_ENTITY, SET_UPDATE_ENTITY_AND_NULL -> {
                // D255：每列独立参数键（`entity.col`），避免多列 SET 在参数面板共用一个值。
                String prefix = ParamSourceNaming.prefixOf(args);
                List<SetClause> result = new ArrayList<>();
                for (ColumnInfo col : table.getColumns()) {
                    if (col.isId()) continue;
                    result.add(new SetClause(
                            new ColumnRef(mainAlias, col.getColumnName(), null, null, null, null, null),
                            new SqlValue.Placeholder(ParamSourceNaming.source(prefix, col.getColumnName())), null));
                }
                return result;
            }
            default -> {
                return List.of();
            }
        }
    }

    /** SET 调用原文摘要（警示文案用）：`setApply("t.a = " + x)`。 */
    private static String setCallSummary(WrapperCall call) {
        StringBuilder sb = new StringBuilder();
        List<WrapperArg> args = call.getArgs();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            WrapperArg arg = args.get(i);
            if (arg instanceof WrapperArg.ColumnArg ca) {
                sb.append(ca.getColumn().getColumnName());
            } else if (arg instanceof WrapperArg.ValueArg va) {
                SqlValue v = va.getValue();
                if (v instanceof SqlValue.Literal lit) sb.append(lit.getUnquoted());
                else if (v instanceof SqlValue.Placeholder ph) sb.append(ph.getSource());
            } else {
                sb.append("…");
            }
        }
        return call.getMethodName() + "(" + sb + ")";
    }

    /** 列归属近似：ColumnArg（方法引用）统一补主表别名；字符串列已含表别名限定时原样保留。 */
    private ColumnRef withMainAliasIfUnqualified(ColumnRef column, String mainAlias) {
        return column.getTableAlias() == null && !column.getColumnName().contains(".")
                ? WrapperConverterKt.withTableAlias(column, mainAlias) : column;
    }

    /** 首参是否为布尔条件前缀。 */
    private static boolean isFlagArgument(WrapperArg arg) {
        if (arg instanceof WrapperArg.ColumnArg) return false;
        SqlValue value = WrapperConverterKt.asValue(arg);
        // T-UpdateSetSqlConcat：带片段形态的运行期实参是 SQL 模板（`setApply("t.a = " + x, ...)`），
        // 不是布尔前缀——否则模板会被当 flag 吞掉，真正的模板位被错取成第二参
        if (value instanceof SqlValue.Placeholder && WrapperConverterKt.asSqlFragment(arg) != null) return false;
        if (value instanceof SqlValue.Literal lit) {
            return "true".equals(lit.getUnquoted()) || "false".equals(lit.getUnquoted());
        }
        return value instanceof SqlValue.Placeholder;
    }

    /** setSql 绑定参数渲染：字面量原样（raw），运行时值渲染为 ?。 */
    private static String renderSetValue(SqlValue value) {
        if (value instanceof SqlValue.Literal lit) return lit.getRaw();
        if (value instanceof SqlValue.Placeholder) return "?";
        return "";
    }

    private static WrapperArg argAt(List<WrapperArg> args, int i) {
        return (i >= 0 && i < args.size()) ? args.get(i) : null;
    }
}
