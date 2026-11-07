package cn.song.mybatisplus.visualizer.sql;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 参数替换器（F-02）：处理 SQL 中残留的 {@code ?} 占位符与参数对照表。
 *
 * <p>说明：绝大多数参数值在 psi 层已通过变量追踪（M2-01）内联进 SQL；
 * 残留的 {@code ?} 表示运行时才能确定的值（如方法参数），静态插件无法取得实际值，
 * 因此提供两种兜底：
 * <ul>
 *  <li>来源注释模式：{@link #annotate} 将 {@code ?} 标注其来源（如 {@code ? /*deviceId*}{@code /}）</li>
 *  <li>对照表：{@link #buildParamTable} 生成 {@code -- 参数对照: [deviceId=?, stationId=?]}</li>
 *  <li>可执行 SQL：{@link #buildExecutableSql} 将 {@code ?} 替换为带类型处理的字面值（供复制到外部工具执行）</li>
 * </ul>
 */
public final class ParameterReplacer {

    /** 数值启发式正则（无 {@link ParamHint} 时判定是否加引号）。 */
    private static final Pattern NUMBER_REGEX = Pattern.compile("-?\\d+(\\.\\d+)?");

    private ParameterReplacer() {
    }

    /**
     * 将 SQL 中的 {@code ?} 按出现顺序标注来源注释。
     *
     * @param sql    含 {@code ?} 的 SQL
     * @param params 参数来源列表（与 {@code GeneratedSql.unresolvedParams} 对应）
     * @return 标注后的 SQL（{@code ?} 数量多于参数列表时，多余的 {@code ?} 保持原样）
     */
    public static String annotate(String sql, List<String> params) {
        if (params.isEmpty()) return sql;
        int index = 0;
        StringBuilder sb = new StringBuilder(sql.length() + params.size() * 12);
        int i = 0;
        while (i < sql.length()) {
            char ch = sql.charAt(i);
            if (ch == '?' && index < params.size()) {
                sb.append("? /*").append(params.get(index)).append("*/");
                index++;
            } else {
                sb.append(ch);
            }
            i++;
        }
        return sb.toString();
    }

    /**
     * 生成参数对照表（供 UI 展示，如 {@code -- 参数对照: [deviceId=?, stationId=?]}）。
     *
     * @param params 未解析参数来源列表
     * @return 对照表文本；无参数时返回 null
     */
    public static String buildParamTable(List<String> params) {
        if (params.isEmpty()) return null;
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) body.append(", ");
            body.append(params.get(i)).append("=?");
        }
        return "-- 参数对照: [" + body + "]";
    }

    /**
     * 将 SQL 中的 {@code ?} 占位符替换为带类型处理的字面值，生成可执行 SQL（供复制到 DBeaver/DataGrip 等工具直接执行）。
     *
     * <p>替换规则（与面板回填 {@code SqlPreviewPanel} 一致）：
     * <ul>
     *  <li>值未填写（空/空白）→ 保留 {@code ?}</li>
     *  <li>{@link ParamHint#getQuote()} = true → 单引号包裹（String/日期列）</li>
     *  <li>{@link ParamHint#getQuote()} = false → 原样渲染（数值列）</li>
     *  <li>{@link ParamHint#getQuote()} = null → 数值启发式（纯数字原样，否则引号包裹）</li>
     *  <li>值中单引号转义为 {@code ''}（防 SQL 注入，与面板回填一致）</li>
     * </ul>
     *
     * <p>T-PSG-02（N-33）：位点判定改用 {@link PlaceholderPositionsKt#placeholderPositions}（string-aware）——
     * 字符串字面量与 {@code --} / 块注释内的 {@code ?} 不再被当作占位符替换（改前朴素逐字符 {@code c == '?'} 会错位）。
     * 不含这类 {@code ?} 的 SQL 替换结果与改前逐字节一致（替换语义与顺序不变）。
     *
     * <p>T-RuntimeSlotFill 券 B：{@code protectedSpans} 是<b>已被运行期占位回填占用</b>的区间（入参 {@code sql}
     * 的坐标）。落在这些区间里的 {@code ?} 一律不当参数占位符——用户填的值里带 {@code ?} 时不会被这一步
     * 二次当占位（P-2）。默认空表 = 既有行为逐字节不变。</p>
     *
     * @param sql         含 {@code ?} 的 SQL（{@code ?} 顺序与 {@code sources} 一一对应）
     * @param sources     参数来源列表（与 SQL 中 {@code ?} 按出现顺序对应）
     * @param paramValues 参数值映射（source → 用户输入值）
     * @param hints       与 {@code sources} 并行的回填提示（引号规则）；缺失走启发式
     * @param protectedSpans 不回填的区间（运行期占位已占用的文本范围）
     * @param dialectName 当前方言名（日期族 ANSI 字面量门禁；null=未知，按支持处理）
     * @return 替换后的可执行 SQL
     */
    public static String buildExecutableSql(
            String sql,
            List<String> sources,
            Map<String, String> paramValues,
            List<ParamHint> hints,
            List<Span> protectedSpans,
            String dialectName) {
        if (sources.isEmpty()) return sql;
        List<Integer> allPositions = PlaceholderPositionsKt.placeholderPositions(sql);
        if (allPositions.isEmpty()) return sql;
        List<Integer> positions;
        if (protectedSpans.isEmpty()) {
            positions = allPositions;
        } else {
            positions = new java.util.ArrayList<>();
            for (Integer p : allPositions) {
                boolean covered = false;
                for (Span span : protectedSpans) {
                    if (p >= span.first() && p <= span.last()) {
                        covered = true;
                        break;
                    }
                }
                if (!covered) positions.add(p);
            }
        }
        if (positions.isEmpty()) return sql;
        StringBuilder sb = new StringBuilder(sql.length() + 32);
        int paramIndex = 0;
        int prev = 0;
        for (int pos : positions) {
            sb.append(sql, prev, pos);
            String source = paramIndex < sources.size() ? sources.get(paramIndex) : null;
            String value = source != null ? paramValues.get(source) : null;
            sb.append(renderValue(value, paramIndex < hints.size() ? hints.get(paramIndex) : null, dialectName));
            paramIndex++;
            prev = pos + 1;
        }
        sb.append(sql, prev, sql.length());
        return sb.toString();
    }

    /** 对应 Kotlin 默认参数 {@code hints = emptyList()} 的重载。 */
    public static String buildExecutableSql(
            String sql,
            List<String> sources,
            Map<String, String> paramValues) {
        return buildExecutableSql(sql, sources, paramValues, List.of());
    }

    /** 对应 Kotlin 默认参数（protectedSpans=空表, dialectName=null）的重载（既有四参形态）。 */
    public static String buildExecutableSql(
            String sql,
            List<String> sources,
            Map<String, String> paramValues,
            List<ParamHint> hints) {
        return buildExecutableSql(sql, sources, paramValues, hints, List.of(), null);
    }

    /**
     * 单值渲染：日期族（{@link ParamHint#isTemporal()}）先试 ANSI 类型字面量；否则按
     * {@link ParamHint#getQuote()} 决定引号规则；值中单引号转义为 {@code ''}。
     * 空值/空白 → 返回 {@code ?}（保留占位符）。
     */
    private static String renderValue(String value, ParamHint hint, String dialectName) {
        if (value == null || value.trim().isEmpty()) return "?";
        // LIKE 是字符串语义，优先于日期族（面板 renderParamValue 已先行分流；此处防御直接调用）
        if (hint != null && hint.isTemporal() && hint.getLikePosition() == null) {
            String typed = SqlDateLiteral.renderByShape(value, dialectName);
            if (typed != null) return typed;
        }
        String escaped = value.replace("'", "''");
        if (hint != null && Boolean.TRUE.equals(hint.getQuote())) {
            return "'" + escaped + "'";
        }
        if (hint != null && Boolean.FALSE.equals(hint.getQuote())) {
            return value.trim();
        }
        String trimmed = value.trim();
        return NUMBER_REGEX.matcher(trimmed).matches() ? trimmed : "'" + escaped + "'";
    }

    // ------------------------------------------------------------------
    // JDBC 命名参数（NamedParameterJdbcTemplate `:name`）按名回填
    // ------------------------------------------------------------------

    /**
     * 按命名参数名把 SQL 中 {@code :name} 替换为用户输入值（NamedParameterJdbcTemplate 语义）。
     *
     * <p>替换规则：
     * <ul>
     *  <li>{@code :name} 出现在 paramValues 中且值非空 → 按 ParamHint.quote 渲染</li>
     *  <li>{@code :name} 未出现在参数值表 / 值空 → 原样保留（与 {@code ?} 空值保留语义一致）</li>
     *  <li>{@code ::name}（双冒号）→ 跳过（双冒号守卫，避免误标 PostgreSQL 类型转换）</li>
     *  <li>{@code ?} 占位符不受影响（由调用方走 {@link #buildExecutableSql} 按序回填）</li>
     *  <li>同名参数在 SQL 中多次出现 → 全部替换（NamedParameterJdbcTemplate 语义：同名复用）</li>
     * </ul>
     * 值含单引号 → 转义 {@code ''}（与 {@link #renderValue} 一致）。
     *
     * @param sql         含 {@code :name} 的 SQL（{@code ?} 与 {@code :name} 可共存）
     * @param paramValues 参数值映射（source → 用户输入值）；source 即命名参数名
     * @param hints       参数名 → 引号提示；缺失走启发式（数值裸 / 字符串引号）
     * @param protectedSpans T-RuntimeSlotFill 券 B：不回填的区间（运行期占位已占用的文本范围）
     * @param dialectName 当前方言名（日期族 ANSI 字面量门禁；null=未知，按支持处理）
     * @return 替换后的 SQL
     */
    public static String replaceNamedParams(
            String sql,
            Map<String, String> paramValues,
            Map<String, ParamHint> hints,
            List<Span> protectedSpans,
            String dialectName) {
        if (paramValues.isEmpty() || hints.isEmpty()) return sql;
        if (protectedSpans.isEmpty()) {
            java.util.regex.Matcher m = NAMED_PARAM_PATTERN.matcher(sql);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String name = m.group(1);
                String value = paramValues.get(name);
                if (value == null || value.isBlank()) {
                    m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group(0)));
                } else {
                    m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                            renderNamedValue(value, hints.get(name), dialectName)));
                }
            }
            m.appendTail(sb);
            return sb.toString();
        }
        java.util.regex.Matcher matcher = NAMED_PARAM_PATTERN.matcher(sql);
        StringBuilder sb = new StringBuilder(sql.length() + 32);
        int prev = 0;
        while (matcher.find()) {
            boolean covered = false;
            for (Span span : protectedSpans) {
                if (matcher.start() >= span.first() && matcher.start() <= span.last()) {
                    covered = true;
                    break;
                }
            }
            if (covered) continue;
            String name = matcher.group(1);
            String value = paramValues.get(name);
            if (value == null || value.isBlank()) continue;
            sb.append(sql, prev, matcher.start()).append(renderNamedValue(value, hints.get(name), dialectName));
            prev = matcher.end();
        }
        sb.append(sql, prev, sql.length());
        return sb.toString();
    }

    /** 对应 Kotlin 默认参数（protectedSpans=空表, dialectName=null）的重载（既有三参形态）。 */
    public static String replaceNamedParams(String sql, Map<String, String> paramValues, Map<String, ParamHint> hints) {
        return replaceNamedParams(sql, paramValues, hints, List.of(), null);
    }

    /**
     * 命名参数单值渲染：日期族（{@link ParamHint#isTemporal()}）先试 ANSI 类型字面量；否则按
     * {@link ParamHint#getQuote()} 决定引号规则；值中单引号转义为 {@code ''}。
     */
    private static String renderNamedValue(String value, ParamHint hint, String dialectName) {
        if (hint != null && hint.isTemporal() && hint.getLikePosition() == null) {
            String typed = SqlDateLiteral.renderByShape(value, dialectName);
            if (typed != null) return typed;
        }
        String escaped = value.replace("'", "''");
        if (hint != null && Boolean.TRUE.equals(hint.getQuote())) {
            return "'" + escaped + "'";
        }
        if (hint != null && Boolean.FALSE.equals(hint.getQuote())) {
            return value.trim();
        }
        String trimmed = value.trim();
        return NUMBER_REGEX.matcher(trimmed).matches() ? trimmed : "'" + escaped + "'";
    }

    /**
     * 命名参数正则。
     *
     * <p>负向后顾 {@code (?<!:)} 排除双冒号（前一个字符不是 {@code :}），避免误标 {@code ::msgId} /
     * {@code 1::integer}。捕获组 1 = 参数名（字母 / 数字 / 下划线）。
     * 冒号后容忍<b>可选空白</b> {@code \s*}——SQL 经
     * {@link cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter} 格式化后
     * 会把 {@code :name} 的冒号当作运算符空格化为 {@code : name}（用户实测绘点），务必同样命中。
     */
    private static final Pattern NAMED_PARAM_PATTERN =
            Pattern.compile("(?<!:)[:]\\s*([A-Za-z_][A-Za-z0-9_]*)");

    // ------------------------------------------------------------------
    // T-RuntimeSlotFill 券 B：运行期占位回填（slot → ? → :name）
    // ------------------------------------------------------------------

    /** Kotlin {@code IntRange} 等价（{@code first..last} 闭区间，文本坐标）。 */
    public record Span(int first, int last) {
    }

    /**
     * 一次「运行期占位回填」的产物。
     *
     * <p>「区间记账」是 P-2 的落实：占位替换与 {@code ?} / {@code :name} 回填共用一次扫描，替换进来的用户值
     * 立即成为不可再扫描的成品区间；{@link #buildExecutableSqlWithSlots} 的产物把区间记在这里，
     * 供后续步骤（或诊断）跳过。单次扫描本身已经杜绝二次误伤，区间是它的可核对凭据。</p>
     *
     * @param sql            回填后的文本（未回填的占位<b>原样保留</b>，与 β.2 行为一致）
     * @param appliedSlotIds 真正被填进 SQL 的占位 id（去重、按首次替换顺序）；空表 = 与输入逐字一致
     * @param warnings       逐条提示（没填进去的原因 / 需要用户知道的风险）
     * @param protectedSpans 已替换区间的<b>输出坐标</b>（{@code sql} 的坐标）
     */
    public static final class RuntimeSlotRender {

        private final String sql;
        private final List<String> appliedSlotIds;
        private final List<SlotWarning> warnings;
        private final List<Span> protectedSpans;

        public RuntimeSlotRender(String sql, List<String> appliedSlotIds, List<SlotWarning> warnings,
                                 List<Span> protectedSpans) {
            this.sql = sql;
            this.appliedSlotIds = appliedSlotIds;
            this.warnings = warnings;
            this.protectedSpans = protectedSpans;
        }

        /** Kotlin 默认参数（protectedSpans=空表）等价重载。 */
        public RuntimeSlotRender(String sql, List<String> appliedSlotIds, List<SlotWarning> warnings) {
            this(sql, appliedSlotIds, warnings, List.of());
        }

        /** Kotlin 默认参数（appliedSlotIds/warnings/protectedSpans 均空表）等价重载。 */
        public RuntimeSlotRender(String sql) {
            this(sql, List.of(), List.of(), List.of());
        }

        public String getSql() {
            return sql;
        }

        public List<String> getAppliedSlotIds() {
            return appliedSlotIds;
        }

        public List<SlotWarning> getWarnings() {
            return warnings;
        }

        public List<Span> getProtectedSpans() {
            return protectedSpans;
        }

        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }

        /** 是否有占位被替换/删除；false = 产物与输入逐字一致（留空语义 D-5）。 */
        public boolean changed() {
            return !appliedSlotIds.isEmpty();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof RuntimeSlotRender)) return false;
            RuntimeSlotRender other = (RuntimeSlotRender) o;
            return java.util.Objects.equals(sql, other.sql)
                    && java.util.Objects.equals(appliedSlotIds, other.appliedSlotIds)
                    && java.util.Objects.equals(warnings, other.warnings)
                    && java.util.Objects.equals(protectedSpans, other.protectedSpans);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(sql, appliedSlotIds, warnings, protectedSpans);
        }

        @Override
        public String toString() {
            return "RuntimeSlotRender(sql=" + sql + ", appliedSlotIds=" + appliedSlotIds
                    + ", warnings=" + warnings + ", protectedSpans=" + protectedSpans + ")";
        }
    }

    /**
     * 只把运行期占位替换成字面量/片段（<b>不碰</b> {@code ?} 与 {@code :name}）。
     *
     * <p>用于「只想看占位展开成什么」的场景（导出回放脚本、预览渲染），以及复制链在
     * 剥注释<b>之前</b>先把占位填掉（占位本身是块注释，先剥就再也找不到了）。</p>
     *
     * <p>定位口径：按 {@code RuntimeSlot.token} 精确匹配（token 内含唯一 id，正常只出现一次；重复出现
     * 则每处都填同一个值）。找不到 token（用户手工改过文本）→ 保留原文并提示，不猜。</p>
     *
     * @param sql         预览 SQL（含占位注释）
     * @param slots       本次预览的占位表
     * @param values      用户填的值；空表 / 某占位未登记 / 登记为「未动作」→ 该处原样保留
     * @param dialectName 当前方言名（只用于布尔字面量判定；null = 按有布尔字面量处理）
     */
    public static RuntimeSlotRender replaceRuntimeSlots(
            String sql,
            List<RuntimeSlot> slots,
            RuntimeSlotValues values,
            String dialectName) {
        if (slots.isEmpty() || values.isEmpty()) return new RuntimeSlotRender(sql);
        Map<String, SlotValue> effective = values.effective();
        if (effective.isEmpty()) return new RuntimeSlotRender(sql);
        boolean[] mask = stringMask(sql);
        RenderContext context = new RenderContext(dialectName);
        List<SlotWarning> warnings = new java.util.ArrayList<>();
        List<String> applied = new java.util.ArrayList<>();
        List<Edit> edits = new java.util.ArrayList<>();
        for (RuntimeSlot slot : slots) {
            SlotValue value = effective.get(slot.getId());
            if (value == null) continue;
            List<Integer> occurrences = occurrencesOf(sql, slot.getToken());
            if (occurrences.isEmpty()) {
                warnings.add(new SlotWarning(slot.getId(), "SQL 里找不到这段占位（可能已被手工编辑），未回填"));
                continue;
            }
            boolean ok = true;
            for (int start : occurrences) {
                Filled filled = renderSlot(slot, value, start < mask.length && mask[start], context);
                if (filled.text() == null) {
                    warnings.add(new SlotWarning(slot.getId(), filled.error() != null ? filled.error() : "无法回填"));
                    ok = false;
                    break;
                }
                if (filled.notice() != null) {
                    warnings.add(new SlotWarning(slot.getId(), filled.notice()));
                }
                edits.add(new Edit(start, start + slot.getToken().length(), filled.text(), filled.text().isEmpty()));
            }
            if (ok) applied.add(slot.getId());
        }
        if (edits.isEmpty()) return new RuntimeSlotRender(sql, distinct(applied), distinct(warnings));

        // 从后往前替换的等价形态：按起点排序后单次前向重建。索引位移问题在构造上消失
        // （不存在「先改前面再看后面」的坐标），并且删除动作可当场收敛相邻空白。
        edits.sort(java.util.Comparator.comparingInt(Edit::start));
        StringBuilder sb = new StringBuilder(sql.length() + 64);
        List<Span> spans = new java.util.ArrayList<>();
        int cursor = 0;
        for (Edit edit : edits) {
            if (edit.start() < cursor) continue; // 理论不可达：token 带唯一 id，区间不会重叠
            sb.append(sql, cursor, edit.start());
            int end = edit.end();
            if (edit.deletion() && sb.length() > 0
                    && (sb.charAt(sb.length() - 1) == ' ' || sb.charAt(sb.length() - 1) == '\t')) {
                while (end < sql.length() && (sql.charAt(end) == ' ' || sql.charAt(end) == '\t')) end++;
            }
            int at = sb.length();
            sb.append(edit.text());
            if (!edit.text().isEmpty()) spans.add(new Span(at, at + edit.text().length() - 1));
            cursor = end;
        }
        sb.append(sql, cursor, sql.length());
        return new RuntimeSlotRender(sb.toString(), distinct(applied), distinct(warnings), spans);
    }

    /** 对应 Kotlin 默认参数（values=EMPTY, dialectName=null）的重载。 */
    public static RuntimeSlotRender replaceRuntimeSlots(String sql, List<RuntimeSlot> slots) {
        return replaceRuntimeSlots(sql, slots, RuntimeSlotValues.EMPTY, null);
    }

    /**
     * 统一回填（T-RuntimeSlotFill 券 B 的入口）：<b>先占位、再 {@code ?}、再 {@code :name}</b>，全程一次扫描。
     *
     * <p>为什么必须一次扫描：占位值、{@code ?} 值、{@code :name} 值都是用户输入——分三步串起来做，前一阶段的
     * 产物里就带着用户值，后一阶段的定位会把值里的 {@code ?} / {@code :x} 当成占位二次填（P-2 的真实风险）。
     * 单次前向扫描里每种替换都是「原子整段产出」，产出不再被扫描，因此不发生二次误伤；替换区间
     * 仍记账在 {@link RuntimeSlotRender#getProtectedSpans()} 里，便于核对。</p>
     *
     * <p>零新语义保证：{@code slots} 与 {@code namedHints} 都为空时<b>直接走既有</b> {@link #buildExecutableSql}
     * （逐字节一致，既有链路零风险）。</p>
     *
     * @param sql         预览 SQL
     * @param slots       运行期占位表（{@code cn.song.mybatisplus.visualizer.model.PreviewResult.runtimeSlots}）
     * @param slotValues  占位值
     * @param sources     {@code ?} 的参数来源（按出现顺序）
     * @param paramValues 参数值（{@code ?} 的来源名与 {@code :name} 的名字共用这张表）
     * @param hints       与 {@code sources} 并行的 {@code ?} 回填提示
     * @param namedHints  {@code :name} 的引号提示；空表 = 不做命名参数回填
     * @param dialectName 当前方言名（布尔字面量判定）
     */
    public static RuntimeSlotRender buildExecutableSqlWithSlots(
            String sql,
            List<RuntimeSlot> slots,
            RuntimeSlotValues slotValues,
            List<String> sources,
            Map<String, String> paramValues,
            List<ParamHint> hints,
            Map<String, ParamHint> namedHints,
            String dialectName) {
        boolean hasNamed = !namedHints.isEmpty() && !paramValues.isEmpty();
        if (slots.isEmpty() && !hasNamed) {
            return new RuntimeSlotRender(
                    buildExecutableSql(sql, sources, paramValues, hints, List.of(), dialectName));
        }
        RenderContext context = new RenderContext(dialectName);
        List<SlotWarning> warnings = new java.util.ArrayList<>();
        List<String> applied = new java.util.ArrayList<>();
        List<Span> spans = new java.util.ArrayList<>();
        // 长 token 优先，避免一个 token 是另一个前缀时错配
        List<RuntimeSlot> tokens = new java.util.ArrayList<>();
        for (RuntimeSlot slot : slots) {
            if (!slot.getToken().isEmpty()) tokens.add(slot);
        }
        tokens.sort(java.util.Comparator.comparingInt((RuntimeSlot s) -> s.getToken().length()).reversed());
        java.util.regex.Matcher namedMatcher = NAMED_PARAM_PATTERN.matcher(sql);
        // 正则带 (?<!:) 后顾断言：按位置窗口匹配时必须让断言看得到窗口外的前一个字符，
        // 否则 region 起点的 lookbehind 恒失败（:: 双冒号守卫会连正常 :name 一起挡掉）
        namedMatcher.useTransparentBounds(true);
        StringBuilder out = new StringBuilder(sql.length() + 64);
        int paramIndex = 0;
        boolean inString = false;
        int i = 0;
        while (i < sql.length()) {
            // 1) 运行期占位优先：它在字符串字面量里也要认（'%/* 运行时值: keyword · rt2 */%'）
            RuntimeSlot slot = null;
            for (RuntimeSlot t : tokens) {
                if (sql.startsWith(t.getToken(), i)) {
                    slot = t;
                    break;
                }
            }
            if (slot != null) {
                SlotValue value = slotValues.get(slot.getId());
                if (value == null || value.isUnset()) {
                    out.append(slot.getToken()); // 留空 = 原样保留（D-5）
                } else {
                    Filled filled = renderSlot(slot, value, inString, context);
                    if (filled.text() == null) {
                        warnings.add(new SlotWarning(slot.getId(), filled.error() != null ? filled.error() : "无法回填"));
                        out.append(slot.getToken());
                    } else {
                        if (filled.notice() != null) {
                            warnings.add(new SlotWarning(slot.getId(), filled.notice()));
                        }
                        int at = out.length();
                        out.append(filled.text());
                        if (!filled.text().isEmpty()) spans.add(new Span(at, at + filled.text().length() - 1));
                        if (!applied.contains(slot.getId())) applied.add(slot.getId());
                    }
                }
                i += slot.getToken().length();
                continue;
            }
            char c = sql.charAt(i);
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        out.append("''");
                        i += 2;
                        continue;
                    }
                    inString = false;
                }
                out.append(c);
                i++;
                continue;
            }
            if (c == '\'') {
                inString = true;
                out.append(c);
                i++;
            } else if (c == '?') {
                String source = paramIndex < sources.size() ? sources.get(paramIndex) : null;
                String value = source != null ? paramValues.get(source) : null;
                String rendered = renderValue(value, paramIndex < hints.size() ? hints.get(paramIndex) : null,
                        context.dialectName());
                paramIndex++;
                out.append(rendered);
                i++;
            } else if (c == ':' && (i == 0 || sql.charAt(i - 1) != ':') && hasNamed) {
                namedMatcher.region(i, sql.length());
                if (namedMatcher.lookingAt()) {
                    String value = paramValues.get(namedMatcher.group(1));
                    if (value == null || value.isBlank()) {
                        out.append(sql, i, namedMatcher.end());
                    } else {
                        out.append(renderNamedValue(value, namedHints.get(namedMatcher.group(1)), context.dialectName()));
                    }
                    i = namedMatcher.end();
                } else {
                    out.append(c);
                    i++;
                }
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int end = i;
                while (end < sql.length() && sql.charAt(end) != '\n') end++;
                out.append(sql, i, end);
                i = end;
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = i + 2;
                while (end + 1 < sql.length() && !(sql.charAt(end) == '*' && sql.charAt(end + 1) == '/')) end++;
                end = Math.min(sql.length(), end + 2);
                out.append(sql, i, end);
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return new RuntimeSlotRender(out.toString(), applied, distinct(warnings), spans);
    }

    /** 对应 Kotlin 默认参数（hints/namedHints/dialectName 缺省）的重载。 */
    public static RuntimeSlotRender buildExecutableSqlWithSlots(
            String sql,
            List<RuntimeSlot> slots,
            RuntimeSlotValues slotValues,
            List<String> sources,
            Map<String, String> paramValues) {
        return buildExecutableSqlWithSlots(sql, slots, slotValues, sources, paramValues,
                List.of(), Map.of(), null);
    }

    // ---- 内部：渲染 ----------------------------------------------------

    /** 渲染成败（{@code text} null = 未回填，{@code error} 说明原因；{@code notice} 是成功时也要说的风险）。 */
    private record Filled(String text, String error, String notice) {

        Filled(String text) {
            this(text, null, null);
        }
    }

    /** 一次替换编辑（{@code sql} 原坐标；{@code deletion} 用于收敛相邻空白）。 */
    private record Edit(int start, int end, String text, boolean deletion) {
    }

    /** 渲染上下文（方言相关口径）。 */
    private record RenderContext(String dialectName) {

        boolean supportsBooleanLiteral() {
            if (dialectName == null) return true;
            for (String prefix : NO_BOOLEAN_LITERAL_DIALECTS) {
                if (dialectName.startsWith(prefix)) return false;
            }
            return true;
        }
    }

    /** 无布尔字面量的方言（P-7：Oracle / 达梦不造 TRUE）。 */
    private static final List<String> NO_BOOLEAN_LITERAL_DIALECTS = List.of("Oracle", "DM");

    private static Filled renderSlot(RuntimeSlot slot, SlotValue value, boolean inQuoted, RenderContext context) {
        return switch (slot.getKind()) {
            case VALUE -> fillValueSlot(slot, value, inQuoted, context);
            case LOOP -> fillLoopSlot(slot, value, inQuoted, context);
            case TERNARY -> fillTernarySlot(slot, value, inQuoted, context);
            case OPTIONAL -> fillOptionalSlot(slot, value, inQuoted, context);
        };
    }

    private static Filled fillValueSlot(RuntimeSlot slot, SlotValue value, boolean inQuoted, RenderContext context) {
        boolean isList = value.getType() == ValueType.LIST || slot.getHint().isInList();
        if (!isList) {
            if (value.getType() != ValueType.NULL && value.getText().isBlank()) {
                return new Filled(null, "选了值类型但没有填值，已保留占位原文", null);
            }
            return literal(value.getText(), value.getType(), slot.getHint().getQuote(), inQuoted, context);
        }
        if (value.getType() == ValueType.LIST && !slot.getHint().isInList()) {
            return new Filled(null, "列表值只能用于 IN (...) 上下文；当前占位不是 IN 列表，已保留占位原文", null);
        }
        if (inQuoted) return new Filled(null, "占位落在字符串字面量内，列表值无法直接填入", null);
        List<String> items = RuntimeSlotValues.splitList(value.getText());
        if (items.isEmpty()) return new Filled(null, "列表占位没有值，已保留占位原文", null);
        if (items.size() > RuntimeSlotValues.MAX_LOOP_ITEMS) {
            return new Filled(null, "列表值过多（" + items.size() + " 项，上限 " + RuntimeSlotValues.MAX_LOOP_ITEMS + "）", null);
        }
        ValueType itemType = value.getType() == ValueType.LIST ? ValueType.AUTO : value.getType();
        List<Filled> parts = new java.util.ArrayList<>();
        for (String item : items) {
            parts.add(literal(item, itemType, slot.getHint().getQuote(), false, context));
        }
        for (Filled part : parts) {
            if (part.text() == null) return new Filled(null, part.error(), null);
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) joined.append(", ");
            joined.append(parts.get(i).text());
        }
        return new Filled(joined.toString());
    }

    private static Filled fillLoopSlot(RuntimeSlot slot, SlotValue value, boolean inQuoted, RenderContext context) {
        List<String> items = RuntimeSlotValues.splitList(value.getText());
        if (items.isEmpty()) return new Filled(null, "循环占位没有值，已保留占位原文", null);
        if (items.size() > RuntimeSlotValues.MAX_LOOP_ITEMS) {
            return new Filled(null, "循环值过多（" + items.size() + " 项，上限 " + RuntimeSlotValues.MAX_LOOP_ITEMS + "）", null);
        }
        if (inQuoted) return new Filled(null, "占位落在字符串字面量内，循环片段无法直接填入", null);
        String first = slot.getFirstTemplate();
        String rest = slot.getRestTemplate();
        if (first == null || rest == null) {
            if (slot.getHint().isInList()) {
                // IN 列表的连接符由 SQL 语法决定（不是猜），可以安全展开
                List<Filled> parts = new java.util.ArrayList<>();
                for (String item : items) {
                    parts.add(literal(item, value.getType(), slot.getHint().getQuote(), false, context));
                }
                for (Filled part : parts) {
                    if (part.text() == null) return new Filled(null, part.error(), null);
                }
                StringBuilder joined = new StringBuilder();
                for (int i = 0; i < parts.size(); i++) {
                    if (i > 0) joined.append(", ");
                    joined.append(parts.get(i).text());
                }
                return new Filled(joined.toString());
            }
            return new Filled(null, "循环展开形态未知（源码里没取到模板），拒绝按猜测的形态拼接", null);
        }
        StringBuilder sb = new StringBuilder();
        for (int index = 0; index < items.size(); index++) {
            Filled filled = fillTemplate(index == 0 ? first : rest, items.get(index), value.getType(), context,
                    "第 " + (index + 1) + " 项");
            if (filled.text() == null) return new Filled(null, filled.error(), null);
            sb.append(filled.text());
        }
        return new Filled(sb.toString());
    }

    private static Filled fillTernarySlot(RuntimeSlot slot, SlotValue value, boolean inQuoted, RenderContext context) {
        Integer branch = value.getBranch();
        if (branch == null) return new Filled(null, "三元占位还没选分支，已保留占位原文", null);
        if (branch != 0 && branch != 1) return new Filled(null, "三元分支只能是 0（真）或 1（假）", null);
        String template = branch == 0 ? slot.getFirstTemplate() : slot.getRestTemplate();
        if (template == null) return new Filled(null, "该分支的展开形态未知（源码里没取到模板）", null);
        if (template.isEmpty()) return new Filled("");
        if (inQuoted) return new Filled(null, "占位落在字符串字面量内，分支片段无法直接填入", null);
        return fillTemplate(template, value.getText(), value.getType(), context, "三元值");
    }

    private static Filled fillOptionalSlot(RuntimeSlot slot, SlotValue value, boolean inQuoted, RenderContext context) {
        String template = slot.getFirstTemplate();
        if (!value.isInclude()) {
            String notice;
            if (template == null) {
                // 多值片段回退形态（template=null）：token 只是值的位置，取消包含只删当前值
                notice = "该片段含多个运行期值，取消「包含这段」只移除当前值，静态片段会保留";
            } else if (!value.getText().isBlank()) {
                notice = "已取消「包含这段」，填的值被忽略";
            } else {
                notice = null;
            }
            return new Filled("", null, notice);
        }
        if (template == null) {
            // 值位置模式（T-RuntimeSlotOptionalFill R2 的多值回退）：token 只是值的位置，
            // 就地填值、不展开整段片段——整段模板会把 token 外的静态前缀重复一遍。
            if (value.getText().isBlank() && value.getType() != ValueType.NULL) {
                return new Filled(null, "选了值类型但没有填值，已保留占位原文", null);
            }
            return literal(value.getText(), value.getType(), slot.getHint().getQuote(), inQuoted, context);
        }
        if (inQuoted) return new Filled(null, "占位落在字符串字面量内，片段无法直接填入", null);
        if (countMarks(template) == 0) return new Filled(template);
        return fillTemplate(template, value.getText(), value.getType(), context, "片段值");
    }

    /**
     * 把单个值填进一段模板。
     *
     * <p><b>{@code %s} 计数校验</b>（券 A 报告第①条坑）：模板里的 {@code %s} 是字面标记，用户源码本身也可能含
     * {@code %s}（{@code LIKE '%s%'} 之类）。个数不等于 1 就<b>拒绝展开并提示</b>，绝不静默少填/多填。
     * 0 个 = 片段是纯字面量，原样使用；&gt;1 个 = 无法判定哪个该填，拒绝。</p>
     */
    private static Filled fillTemplate(String template, String rawValue, ValueType type, RenderContext context,
                                       String label) {
        int marks = countMarks(template);
        if (marks > 1) {
            return new Filled(null,
                    "模板里有 " + marks + " 个 " + RuntimeSlot.VALUE_MARK + "，与预期 1 个值不符（源码本身可能含 "
                            + RuntimeSlot.VALUE_MARK + "），拒绝展开",
                    null);
        }
        if (marks == 0) return new Filled(template);
        if (rawValue.isBlank()) return new Filled(null, label + " 为空，而模板需要一个值", null);
        int markAt = template.indexOf(RuntimeSlot.VALUE_MARK);
        boolean[] mask = stringMask(template);
        Filled filled = literal(rawValue, type, null, markAt < mask.length && mask[markAt], context);
        if (filled.text() == null) return new Filled(null, label + "：" + filled.error(), null);
        String text = template.substring(0, markAt) + filled.text()
                + template.substring(markAt + RuntimeSlot.VALUE_MARK.length());
        return new Filled(text, null, filled.notice());
    }

    /**
     * 单个字面量渲染（<b>绝不拼裸文本</b>）。
     *
     * <p>{@code inQuoted} = 这个位置已经在字符串字面量里：只做转义（{@code '} → {@code ''}），不再补外层引号——
     * 外层引号来自用户自己的 SQL（{@code '%/* 运行时值: keyword *&#47;%'}）。</p>
     */
    private static Filled literal(String raw, ValueType type, Boolean quoteHint, boolean inQuoted,
                                  RenderContext context) {
        String trimmed = raw.trim();
        boolean numeric = NUMBER_REGEX.matcher(trimmed).matches();
        ValueType effective;
        if (type == ValueType.AUTO) {
            effective = (Boolean.TRUE.equals(quoteHint) || !numeric) ? ValueType.STRING : ValueType.NUMBER;
        } else {
            effective = type;
        }
        String notice = (type == ValueType.AUTO && Boolean.FALSE.equals(quoteHint) && !numeric)
                ? "上下文看起来是数值列，但输入「" + trimmed + "」不是数字，已按字符串渲染"
                : null;
        String escaped = trimmed.replace("'", "''");
        return switch (effective) {
            case STRING, LIST, AUTO -> new Filled(inQuoted ? escaped : "'" + escaped + "'", null, notice);
            case NUMBER -> numeric
                    ? new Filled(trimmed, null, notice)
                    : new Filled(inQuoted ? escaped : "'" + escaped + "'", null,
                            "「数字」类型收到非数字输入「" + trimmed + "」，已按字符串渲染");
            case NULL -> inQuoted
                    ? new Filled(null, "占位落在字符串字面量内，NULL 不能填进去", null)
                    : new Filled("NULL", null, "填了 NULL：`= NULL` 恒不成立，判空请改用 IS NULL");
            case BOOLEAN -> {
                String text = booleanLiteral(trimmed);
                if (text == null) {
                    yield new Filled(null, "布尔值只认 true/false 或 1/0（输入：「" + trimmed + "」）", null);
                }
                if (inQuoted) {
                    yield new Filled(null, "占位落在字符串字面量内，布尔字面量不能直接填入", null);
                }
                if (!context.supportsBooleanLiteral()) {
                    yield new Filled(null, "当前方言（" + context.dialectName() + "）没有布尔字面量，请把值改成 1/0", null);
                }
                yield new Filled(text);
            }
        };
    }

    private static String booleanLiteral(String trimmed) {
        return switch (trimmed.toLowerCase(java.util.Locale.ROOT)) {
            case "true" -> "TRUE";
            case "false" -> "FALSE";
            case "1" -> "1";
            case "0" -> "0";
            default -> null;
        };
    }

    /** 模板里 {@code %s} 标记的个数（不重叠计数）。 */
    private static int countMarks(String template) {
        String mark = RuntimeSlot.VALUE_MARK;
        int count = 0;
        int from = 0;
        while (true) {
            int at = template.indexOf(mark, from);
            if (at < 0) return count;
            count++;
            from = at + mark.length();
        }
    }

    /** {@code token} 在 {@code sql} 里的全部出现位置（token 自带唯一 id，正常只有一个）。 */
    private static List<Integer> occurrencesOf(String sql, String token) {
        if (token.isEmpty()) return List.of();
        List<Integer> result = new java.util.ArrayList<>();
        int from = 0;
        while (true) {
            int at = sql.indexOf(token, from);
            if (at < 0) return result;
            result.add(at);
            from = at + token.length();
        }
    }

    /**
     * 逐字符标记「是否落在字符串字面量内」（{@code ''} 转义按一个字符处理）。
     *
     * <p>用于判断占位/模板标记的上下文：落在引号内 → 只做转义、不补引号。行注释与块注释内的
     * 字符不算字面量（占位 token 本身是块注释，判断它是否被引号包住靠的是<b>外层</b>引号状态）。</p>
     */
    private static boolean[] stringMask(String sql) {
        boolean[] mask = new boolean[sql.length()];
        boolean inString = false;
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (inString) {
                mask[i] = true;
                if (c == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        mask[i + 1] = true;
                        i += 2;
                        continue;
                    }
                    inString = false;
                }
                i++;
                continue;
            }
            if (c == '\'') {
                inString = true;
                mask[i] = true;
                i++;
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                while (i < sql.length() && sql.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < sql.length() && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) i++;
                i = Math.min(sql.length(), i + 2);
            } else {
                i++;
            }
        }
        return mask;
    }

    /** Kotlin {@code List.distinct()} 等价（保持首现顺序去重）。 */
    private static <T> List<T> distinct(List<T> list) {
        return new java.util.ArrayList<>(new java.util.LinkedHashSet<>(list));
    }
}
