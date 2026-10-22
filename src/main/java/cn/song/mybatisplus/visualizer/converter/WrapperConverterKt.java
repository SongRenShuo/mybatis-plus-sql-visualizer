package cn.song.mybatisplus.visualizer.converter;

import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.SqlFragment;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.WrapperArg;
import cn.song.mybatisplus.visualizer.sql.PlaceholderPositionsKt;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 共享实参解析辅助（各 converter 复用；对应 psi 层 WrapperArg 产物）。
 */
public final class WrapperConverterKt {
    private WrapperConverterKt() {
    }

    /** 片段中无来源可追溯的占位符所用的中性来源名。 */
    public static final String UNKNOWN_PARAM = "?";

    /** 实参转列：ColumnArg 直接取；ValueArg(字面量) 视为字符串列名（MP 字符串字段版）。 */
    public static ColumnRef asColumn(WrapperArg arg) {
        if (arg instanceof WrapperArg.ColumnArg ca) return ca.getColumn();
        if (arg instanceof WrapperArg.ValueArg va) {
            SqlValue v = va.getValue();
            if (v instanceof SqlValue.Literal lit) {
                return new ColumnRef(null, lit.getUnquoted(), null, null, null, null, null);
            }
        }
        return null;
    }

    /** 实参转值。 */
    public static SqlValue asValue(WrapperArg arg) {
        if (arg instanceof WrapperArg.ValueArg va) return va.getValue();
        return null;
    }

    /**
     * 实参转「原始 SQL 片段」（T-UpdateSetSqlConcat）：文本 + 占位符来源。
     *
     * <p>片段位置（setSql/setApply/apply/exists/last/having/inSql/compareSql/first/comment/applyFunc/selectFunc
     * /MPJ 字符串 join）的实参在 MP 里是<b>原样拼进语句的 SQL 文本</b>，不做 JDBC 参数绑定：静态段必须保留，
     * 运行期段用 {@code ?} 近似（{@code "amount = amount * " + rate} → {@code amount = amount * ?}）。
     *
     * <p>值位置（eq/set 的值实参）<b>不得</b>用本函数——那里整个字符串是一个绑定参数，只能渲染 {@code ?}
     * （{@code eq("name", "pre" + x)} 是 {@code name = ?}，不是 {@code name = pre?}）。
     *
     * @return 片段（文本 + 与文本中 {@code ?} 一一对应的来源）；纯运行期片段（无任何静态段）返回 null，
     *         调用方按既有语义放弃该片段
     */
    public static SqlFragment asSqlFragment(WrapperArg arg) {
        SqlValue value = arg instanceof WrapperArg.ValueArg va ? va.getValue() : null;
        if (value instanceof SqlValue.Literal lit) {
            String unquoted = lit.getUnquoted();
            // 字面量里用户自写的 `?` 也是一次参数占位，须占位以保持参数表对齐
            int placeholderCount = PlaceholderPositionsKt.placeholderPositions(unquoted).size();
            List<String> params = new ArrayList<>(placeholderCount);
            for (int i = 0; i < placeholderCount; i++) {
                params.add(UNKNOWN_PARAM);
            }
            return new SqlFragment(unquoted, params);
        }
        if (value instanceof SqlValue.Placeholder ph) {
            return ph.getFragment();
        }
        return null;
    }

    /**
     * 片段模板渲染（T-UpdateSetSqlConcat）：{@code {i}} 绑定参数替换 + 按最终文本顺序收集占位符来源。
     *
     * <ul>
     *   <li>{@code {i}}：替换为第 i 个绑定值（字面量取 {@code raw}、运行期值写 {@code ?} 并登记来源）；
     *       绑定值缺失（null）时保留 {@code {i}} 原样（与既有 {@code replace} 跳过语义一致）</li>
     *   <li>模板自带的 {@code ?}：按顺序消耗 fragment 的来源，<b>只认真实占位符位点</b>
     *       （字符串字面量/注释内的 {@code ?} 不消耗、不登记——{@code first("/*+ ...(" + ms + ") *&#47;")}
     *       这类不能凭空多出来源）</li>
     *   <li>自带来源不足时补中性来源 {@link #UNKNOWN_PARAM}，<b>宁可名字含糊也不能让参数表整体错位</b></li>
     * </ul>
     */
    public static SqlFragment renderSqlFragment(SqlFragment fragment, List<SqlValue> values) {
        String template = fragment.getText();
        Set<Integer> templatePlaceholders = new HashSet<>(PlaceholderPositionsKt.placeholderPositions(template));
        StringBuilder text = new StringBuilder(template.length() + 8);
        // 输出文本位点 → 来源；最后按输出的真实占位符位点过滤（`{i}` 插进注释/引号时不留痕）
        List<Integer> markPositions = new ArrayList<>();
        List<String> markSources = new ArrayList<>();
        List<String> templateParams = fragment.getParams();
        int index = 0;
        int paramIndex = 0;
        while (index < template.length()) {
            if (template.charAt(index) == '{') {
                int close = template.indexOf('}', index);
                Integer binding = null;
                if (close > index) {
                    try {
                        binding = Integer.parseInt(template.substring(index + 1, close));
                    } catch (NumberFormatException ignored) {
                        // Kotlin toIntOrNull：非数字保持 null
                    }
                }
                if (binding != null && binding < values.size()) {
                    SqlValue value = values.get(binding);
                    if (value == null) {
                        text.append(template, index, close + 1);
                    } else if (value instanceof SqlValue.Literal lit) {
                        String raw = lit.getRaw();
                        for (int i = 0; i < raw.length(); i++) {
                            if (raw.charAt(i) == '?') {
                                markPositions.add(text.length() + i);
                                markSources.add(UNKNOWN_PARAM);
                            }
                        }
                        text.append(raw);
                    } else if (value instanceof SqlValue.Placeholder ph) {
                        markPositions.add(text.length());
                        markSources.add(ph.getSource());
                        text.append('?');
                    }
                    index = close + 1;
                    continue;
                }
            }
            if (template.charAt(index) == '?' && templatePlaceholders.contains(index)) {
                markPositions.add(text.length());
                markSources.add(paramIndex < templateParams.size() ? templateParams.get(paramIndex) : UNKNOWN_PARAM);
                paramIndex++;
            }
            text.append(template.charAt(index));
            index++;
        }
        Set<Integer> effective = new HashSet<>(PlaceholderPositionsKt.placeholderPositions(text.toString()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < markPositions.size(); i++) {
            if (effective.contains(markPositions.get(i))) {
                out.add(markSources.get(i));
            }
        }
        return new SqlFragment(text.toString(), out);
    }

    /** 为列引用补表别名（MPJ 主表场景），已有别名时保持不变。 */
    public static ColumnRef withTableAlias(ColumnRef column, String alias) {
        return column.getTableAlias() == null
                ? new ColumnRef(alias, column.getColumnName(), column.getAlias(), column.getFunction(),
                column.getRawExpr(), column.getEntityClass(), column.getType())
                : column;
    }
}
