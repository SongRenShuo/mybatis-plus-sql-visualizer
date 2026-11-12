// T-SyncK2JMaster178 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/xml/ibatis/IbatisParamModel.kt 移植
package cn.song.mybatisplus.visualizer.xml.ibatis;

import cn.song.mybatisplus.visualizer.model.PreviewRerenderInput;
import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.xml.ParamKind;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisParameterDef;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisParameterMap;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisParamMapping;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisSqlNode;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisStatement;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisTagKind;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisTagNode;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisTextNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

/**
 * iBatis 预览的参数/条件行清单（T-Ibatis2Gap 券 A）：{@code IbatisStatement} → 面板可交互行 + Map 装配。
 *
 * <h2>为什么 Map 装配就够（本券的钥匙）</h2>
 *
 * {@code IbatisProbe} 原生支持 {@code Map} 参数：{@code parameterObject is Map} 时取值走 {@code map[name]}、
 * 可用性走 {@code containsKey}。因此<b>条件开关 = Map 里放不放这个 key</b>（{@code <isNotNull property="id">}
 * 在 map 含非 null {@code id} 时成立、不含时不成立），<b>值语义 = value 的类型与内容</b>。
 * 渲染入口不变：{@code SqlMapSqlRenderer.render(statement, params)}——渲染器零改动。
 *
 * <h2>装配语义（拍板 D-1「条件勾选 + 值可填」）</h2>
 *
 * <ul>
 * <li><b>勾选 = 放 key</b>（值为用户输入，缺省回落 {@code compareValue}），<b>取消 = 移除 key</b>；</li>
 * <li>占位符行（{@code #x#}）：填了值才放 key；与条件行同名的 key 由<b>条件行的开关主导</b>
 * （勾上 {@code isNotNull(id)} 时 {@code #id#} 的值进 map，取消时整段跳过、key 不放）；</li>
 * <li>{@code $x$} 行：填了值才放 key（直替照常触发既有注入闸门，<b>不得绕过确认</b>）；</li>
 * <li>{@code <iterate>}：值列表非空才放 key（{@code List<String>}），按列表长度由渲染器生成 {@code ids[0..n-1]}；
 * 留空 = key 不放 = 引擎语义「取值不是集合」→ 渲染降级（与无参基线现状一致）；</li>
 * <li><b>空串值对 {@code isNotEmpty} 天然为 false 是引擎语义，不做「帮用户转 null」的伪装</b>；</li>
 * <li>装配结果为空 map 时回退 {@code null}（无参基线，与现状逐字节一致）——唯一例外是勾选了
 * {@code <isParameterPresent>}：它判 {@code params != null}，此时传空 map 才能让条件成立。</li>
 * </ul>
 *
 * <p>k2j 承载形态：master 侧本文件的 {@code IbatisParamModelBuilder} / 4 个行 data class /
 * {@code IbatisParamTypeHints} 聚合落在本 record 的嵌套类型里。</p>
 */
public record IbatisParamModel(
        List<IbatisPlaceholderRow> placeholderRows,
        List<IbatisConditionRow> conditionRows,
        List<IbatisIterateRow> iterateRows,
        List<IbatisDollarRow> dollarRows
) {

    /** 面板是否有任何可交互控件（决定 rebuildLeftPanel 是否早退为「无参数」）。 */
    public boolean hasControls() {
        return !placeholderRows.isEmpty() || !conditionRows.isEmpty()
                || !iterateRows.isEmpty() || !dollarRows.isEmpty();
    }

    /** 条件行摘要（面板复选框标签；标签名是引擎标识符，保持原文不本地化）。 */
    public String labelOf(IbatisConditionRow row) {
        if (row.compareProperty() != null) {
            return row.kindTagName() + ": " + (row.property() != null ? row.property() : "parameter")
                    + " == " + row.compareProperty();
        }
        if (row.compareValue() != null && row.property() != null) {
            return row.kindTagName() + ": " + row.property() + " " + compareOpOf(row.kindTagName())
                    + " " + row.compareValue();
        }
        if (row.property() != null) {
            return row.kindTagName() + ": " + row.property();
        }
        return row.kindTagName() + ":";
    }

    /** compare 族标签里的比较符号（仅展示用，不影响求值）。 */
    private static String compareOpOf(String tagName) {
        if (IbatisTagKind.IS_EQUAL.getTagName().equals(tagName)) {
            return "==";
        }
        if (IbatisTagKind.IS_NOT_EQUAL.getTagName().equals(tagName)) {
            return "!=";
        }
        if (IbatisTagKind.IS_GREATER_THAN.getTagName().equals(tagName)) {
            return ">";
        }
        if (IbatisTagKind.IS_GREATER_EQUAL.getTagName().equals(tagName)) {
            return ">=";
        }
        if (IbatisTagKind.IS_LESS_THAN.getTagName().equals(tagName)) {
            return "<";
        }
        if (IbatisTagKind.IS_LESS_EQUAL.getTagName().equals(tagName)) {
            return "<=";
        }
        return "cmp";
    }

    /** 占位符行（{@code #x#} 家族 / parameterMap 声明参数）：参数名 + 类型提示 + 值输入（面板侧持有）。 */
    public record IbatisPlaceholderRow(
            String name,
            String jdbcType,
            String nullValue
    ) {

        /** master 侧默认参量便捷构造器（{@code jdbcType} 起全默认 {@code null}）。 */
        public IbatisPlaceholderRow(String name) {
            this(name, null, null);
        }
    }

    /**
     * 条件行（{@code isXxx}）：开关 + 摘要 + 引入的参数名。
     *
     * <p>{@code id} 是面板勾选键（{@code ibatis-cond-N}，单次 build 内稳定）；{@code label} 由
     * {@link IbatisParamModel#labelOf} 生成（{@code isNotNull: id} / {@code isEqual: status == "1"} 形态）。</p>
     */
    public record IbatisConditionRow(
            String id,
            String kindTagName,
            String property,
            String compareValue,
            String compareProperty
    ) {

        /** master 侧默认参量便捷构造器（{@code compareValue} 起全默认 {@code null}）。 */
        public IbatisConditionRow(String id, String kindTagName, String property) {
            this(id, kindTagName, property, null, null);
        }
    }

    /** iterate 行：集合属性名 + 值列表输入（逗号/换行分隔，面板侧持有）。 */
    public record IbatisIterateRow(
            String id,
            String property,
            String conjunction
    ) {

        /** master 侧默认参量便捷构造器（{@code conjunction = null}）。 */
        public IbatisIterateRow(String id, String property) {
            this(id, property, null);
        }
    }

    /** {@code $x$} 直替行：站点名 + 风险标注（面板侧展示「原文直替（注入面）」）。 */
    public record IbatisDollarRow(String name) {
    }

    /**
     * 面板行清单装配器（master 侧 {@code object IbatisParamModelBuilder}，Java 侧静态门面）。
     */
    public static final class IbatisParamModelBuilder {

        private IbatisParamModelBuilder() {
        }

        /**
         * 从语句体收集面板行（树序 DFS）。
         *
         * <ul>
         * <li>占位符行：非 postParse 文本节点的配置期解析产物（{@code #x#} / {@code #x:TYPE#} /
         * {@code #x:TYPE:null#} / {@code #x,jdbcType=?#}）+ 声明 {@code parameterMap} 的语句参数（按声明序）；</li>
         * <li>条件行：全部 {@code isXxx} 标签（含嵌套在 {@code <dynamic>}/条件内的）；
         * <b>iterate 子树内的条件不建行</b>（它们的求值随迭代逐轮走带下标的属性路径，开关语义不独立）；</li>
         * <li>iterate 行：每个 {@code <iterate>}（含嵌套）；</li>
         * <li>{@code $x$} 行：文本节点里的直替站点（{@code $$} 转义与 {@code ${prop}} 全局属性 token 不算）。</li>
         * </ul>
         */
        public static IbatisParamModel build(IbatisStatement statement) {
            LinkedHashMap<String, IbatisPlaceholderRow> placeholders = new LinkedHashMap<>();
            List<IbatisConditionRow> conditions = new ArrayList<>();
            List<IbatisIterateRow> iterates = new ArrayList<>();
            LinkedHashMap<String, IbatisDollarRow> dollars = new LinkedHashMap<>();

            // 声明 parameterMap 的静态语句：参数按声明序进占位符行（值只进 map，渲染静态路径本就不吃参数）
            IbatisParameterMap parameterMap = statement.parameterMap();
            if (parameterMap != null) {
                for (IbatisParameterDef p : parameterMap.parameters()) {
                    if (!placeholders.containsKey(p.property())) {
                        placeholders.put(p.property(), new IbatisPlaceholderRow(p.property(), p.jdbcType(), p.nullValue()));
                    }
                }
            }

            walk(statement.body(), false, placeholders, conditions, iterates, dollars);

            return new IbatisParamModel(
                    List.copyOf(placeholders.values()),
                    List.copyOf(conditions),
                    List.copyOf(iterates),
                    List.copyOf(dollars.values())
            );
        }

        /** master 侧树序 DFS 局部函数 {@code walk}（状态经实参携带）。 */
        private static void walk(
                List<IbatisSqlNode> nodes,
                boolean insideIterate,
                LinkedHashMap<String, IbatisPlaceholderRow> placeholders,
                List<IbatisConditionRow> conditions,
                List<IbatisIterateRow> iterates,
                LinkedHashMap<String, IbatisDollarRow> dollars
        ) {
            for (IbatisSqlNode node : nodes) {
                if (node instanceof IbatisTextNode textNode) {
                    for (IbatisParamMapping m : textNode.getMappings()) {
                        if (!placeholders.containsKey(m.propertyName())) {
                            placeholders.put(m.propertyName(), new IbatisPlaceholderRow(m.propertyName(), m.jdbcTypeName(), m.nullValue()));
                        }
                    }
                    for (String name : dollarSiteNames(textNode.getText())) {
                        dollars.putIfAbsent(name, new IbatisDollarRow(name));
                    }
                } else if (node instanceof IbatisTagNode tagNode) {
                    IbatisTagKind kind = tagNode.getKind();
                    if (kind == IbatisTagKind.ITERATE) {
                        iterates.add(new IbatisIterateRow(
                                "ibatis-iter-" + iterates.size(),
                                tagNode.getProperty() != null ? tagNode.getProperty() : "",
                                tagNode.getConjunction()
                        ));
                        walk(tagNode.getChildren(), true, placeholders, conditions, iterates, dollars);
                    } else if (kind == IbatisTagKind.DYNAMIC) {
                        walk(tagNode.getChildren(), insideIterate, placeholders, conditions, iterates, dollars);
                    } else {
                        if (!insideIterate) {
                            conditions.add(new IbatisConditionRow(
                                    "ibatis-cond-" + conditions.size(),
                                    kind.getTagName(),
                                    tagNode.getProperty() != null && !tagNode.getProperty().isEmpty() ? tagNode.getProperty() : null,
                                    tagNode.getCompareValue(),
                                    tagNode.getCompareProperty()
                            ));
                        }
                        walk(tagNode.getChildren(), insideIterate, placeholders, conditions, iterates, dollars);
                    }
                }
            }
        }

        /**
         * {@code $x$} 站点名提取（与引擎 {@code SimpleDynamicSql} 的 token 化同口径：
         * {@code StringTokenizer(text, "$", true)}——{@code $$} 是字面量 {@code $}，不是站点；{@code ${prop}} 是
         * 全局属性 token（首字符 {@code {}），本链不做替换，也不算直替参数行）。
         */
        private static List<String> dollarSiteNames(String text) {
            List<String> tokens = new ArrayList<>();
            StringTokenizer parser = new StringTokenizer(text, "$", true);
            while (parser.hasMoreTokens()) {
                tokens.add(parser.nextToken());
            }
            List<String> names = new ArrayList<>();
            int i = 0;
            while (i < tokens.size()) {
                if (!"$".equals(tokens.get(i))) {
                    i++;
                    continue;
                }
                if (i + 1 < tokens.size() && "$".equals(tokens.get(i + 1))) {
                    // $$ → 字面量 $
                    i += 2;
                    continue;
                }
                int j = i + 1;
                while (j < tokens.size() && !"$".equals(tokens.get(j))) {
                    j++;
                }
                if (j >= tokens.size()) {
                    break;
                }
                String name = String.join("", tokens.subList(i + 1, j));
                if (!name.isEmpty() && !name.startsWith("{")) {
                    names.add(name);
                }
                i = j + 1;
            }
            return names;
        }

        /** 面板状态 → 渲染参数（{@code Map} 或 null；null = 无参基线，与现状一致）。 */
        public static Object assembleParams(IbatisParamModel model, PreviewRerenderInput input) {
            Map<String, Object> map = new LinkedHashMap<>();

            // 条件行：勾选 = 放 key。值优先取用户输入（与同名占位符行共享一个输入框），缺省回落
            // compareValue（isEqual 的常见形态：勾上即按比较值参与 WHERE），再缺省空串。
            for (IbatisConditionRow row : model.conditionRows()) {
                if (!input.enabledConditionIds().contains(row.id())) {
                    continue;
                }
                String property = row.property();
                if (property == null) {
                    // isParameterPresent 族无属性可放——是否成立只看 map 是否为 null（见函数尾）
                    continue;
                }
                String value = input.params().get(property);
                map.put(property, value != null && !value.isEmpty()
                        ? value
                        : (row.compareValue() != null ? row.compareValue() : ""));
            }

            // 条件行「接管」的属性名：勾选时 key 已由条件行放入；**取消勾选时同名占位符/`$` 行的值
            // 也不放**——取消 = 模拟「该属性不存在」（引擎 Map 语义 containsKey 决定一切），用户填的
            // 值一并隐藏，语义自洽（否则「条件关了、占位符还绑上值」是自相矛盾的模拟）。
            Set<String> conditionControlled = new LinkedHashSet<>();
            for (IbatisConditionRow row : model.conditionRows()) {
                if (row.property() != null) {
                    conditionControlled.add(row.property());
                }
            }

            // 占位符行：没被条件行接管的 key，填了值才放（空串 = 不放 = `?` 处 null 绑定，引擎语义）
            for (IbatisPlaceholderRow row : model.placeholderRows()) {
                if (map.containsKey(row.name())) {
                    continue;
                }
                if (conditionControlled.contains(row.name())) {
                    continue;
                }
                String value = input.params().get(row.name());
                if (value != null && !value.isEmpty()) {
                    map.put(row.name(), value);
                }
            }

            // $x$ 行：填了值才放（直替；注入闸门由渲染产物的 dollarRawCount 照常置位，不得绕过）
            for (IbatisDollarRow row : model.dollarRows()) {
                if (conditionControlled.contains(row.name())) {
                    continue;
                }
                String value = input.params().get(row.name());
                if (value != null && !value.isEmpty()) {
                    map.put(row.name(), value);
                }
            }

            // iterate 行：值列表非空才放（List<String> → 渲染器生成 ids[0..n-1]）；留空 = 引擎降级（现状）
            for (IbatisIterateRow row : model.iterateRows()) {
                List<String> values = input.collections().get(row.property());
                if (values == null) {
                    values = List.of();
                }
                if (!values.isEmpty()) {
                    map.put(row.property(), values);
                }
            }

            if (map.isEmpty()) {
                // 空装配 = 无参基线（null，与现状逐字节一致）；唯一例外是 isParameterPresent 被勾选——
                // 它判 params != null，传空 map 才成立（isNotParameterPresent 反之，null 天然成立）
                boolean parameterPresentChecked = false;
                for (IbatisConditionRow row : model.conditionRows()) {
                    if (input.enabledConditionIds().contains(row.id())
                            && IbatisTagKind.IS_PARAMETER_PRESENT.getTagName().equals(row.kindTagName())) {
                        parameterPresentChecked = true;
                        break;
                    }
                }
                return parameterPresentChecked ? Map.of() : null;
            }
            return map;
        }
    }

    /**
     * T-IbatisFix3 缺陷三：{@code #x:jdbcType#} 的 <b>jdbcType → 参数类别 → 回填引号提示</b> 映射（纯函数）。
     *
     * <h2>缺陷现场</h2>
     *
     * {@code #email:VARCHAR:0#} 填 {@code 11212} 预览出 {@code WHERE EMAIL = 11212}——<b>没有引号</b>。
     * 原因是服务层（{@code ui/IbatisXmlPreviewService.toPreviewResult}）只把 {@code ?} 位点数量/参数名交给面板，
     * <b>从未构造 {@code PreviewResult.paramHints}</b>：面板 {@code backfillParamsBySource} 拿不到提示时按
     * {@code NUMBER_REGEX} 启发式判定，{@code 11212} 是纯数字 → 裸渲染。jdbcType 明明写着 VARCHAR。
     *
     * <h2>口径（沿用既有 {@code ParamKind} 六类契约，不新造类型系统）</h2>
     *
     * <ul>
     * <li>{@code VARCHAR} / {@code CHAR} / {@code TEXT} / {@code CLOB} 族 → {@link ParamKind#TEXT}（恒引号）；</li>
     * <li>{@code INTEGER} / {@code NUMERIC} / {@code DECIMAL} / 浮点 / {@code BIT} → {@link ParamKind#NUMBER}（裸渲染）；</li>
     * <li>{@code DATE} / {@code TIME} / {@code TIMESTAMP} / {@code DATETIME} → {@link ParamKind#TEMPORAL}
     * （{@code ParamHint.quote=true} 为回退口径，{@code temporal=true} 让面板先试 ANSI 类型字面量
     * {@code DATE '…'}，形态不匹配再回落引号——与 MyBatis 链 T-DateLiteralBackfill 同口径）；</li>
     * <li><b>映射不到 / jdbcType 缺省 → 不产出提示</b>（{@code ParamHint} 默认 {@code quote=null}）：面板维持既有
     * 启发式，{@code #x#} 无类型参数的行为与修复前逐字节一致（宁缺勿错）。</li>
     * </ul>
     *
     * <p>大小写不敏感（iBatis DTD 里 jdbcType 由用户手写，{@code varchar} / {@code VARCHAR} 都真实存在）。</p>
     */
    public static final class IbatisParamTypeHints {

        private IbatisParamTypeHints() {
        }

        /** 字符串族 jdbcType（JDBC 标准名 + 各库常见的 {@code TEXT} 方言名）。 */
        private static final Set<String> TEXT_TYPES = Set.of(
                "CHAR", "VARCHAR", "LONGVARCHAR", "NCHAR", "NVARCHAR", "LONGNVARCHAR",
                "CLOB", "NCLOB", "TEXT", "LONGTEXT", "MEDIUMTEXT", "TINYTEXT", "STRING", "UUID"
        );

        /** 数值族 jdbcType（含布尔位与各库的 {@code NUMBER} / {@code SERIAL} 形态）。 */
        private static final Set<String> NUMBER_TYPES = Set.of(
                "TINYINT", "SMALLINT", "INTEGER", "INT", "BIGINT", "MEDIUMINT",
                "FLOAT", "REAL", "DOUBLE", "NUMERIC", "DECIMAL", "NUMBER",
                "BIT", "BOOLEAN", "SERIAL", "BIGSERIAL"
        );

        /** 日期时间族 jdbcType（{@code DATETIME} 是 MySQL 等方言名）。 */
        private static final Set<String> TEMPORAL_TYPES = Set.of(
                "DATE", "TIME", "TIMESTAMP", "DATETIME", "TIMESTAMPTZ", "TIMETZ"
        );

        /** jdbcType → 类型类别；映射不到（含 null / 空串）返回 null = 不产出提示。 */
        public static ParamKind kindOf(String jdbcType) {
            if (jdbcType == null) {
                return null;
            }
            String type = jdbcType.trim().toUpperCase();
            if (type.isEmpty()) {
                return null;
            }
            if (TEXT_TYPES.contains(type)) {
                return ParamKind.TEXT;
            }
            if (NUMBER_TYPES.contains(type)) {
                return ParamKind.NUMBER;
            }
            if (TEMPORAL_TYPES.contains(type)) {
                return ParamKind.TEMPORAL;
            }
            return null;
        }

        /**
         * jdbcType → 回填提示；映射不到返回 null（= 面板维持启发式，与修复前一致）。
         *
         * <p>{@code quote} 取 {@link ParamKind#isQuoteRequired()}（NUMBER 之外恒引号），{@code temporal}
         * 单独标记时间族以便面板先试 ANSI 字面量。</p>
         */
        public static ParamHint hintOf(String jdbcType) {
            ParamKind kind = kindOf(jdbcType);
            if (kind == null) {
                return null;
            }
            return new ParamHint(kind.isQuoteRequired(), null, false, kind == ParamKind.TEMPORAL);
        }

        /**
         * 与 {@code paramNames} 同序的提示清单（jdbcTypes 短/长为 null 时按「无提示」补位）。
         *
         * <p>供 {@code IbatisXmlPreviewService.toPreviewResult} 一次装配；{@code quote=null} 的元素位面板按
         * 启发式处理，所以清单长度必须与 SQL 里 {@code ?} 的数量一致（调用方已保证）。</p>
         */
        public static List<ParamHint> hintsFor(List<String> jdbcTypes) {
            List<ParamHint> result = new ArrayList<>();
            for (String jdbcType : jdbcTypes) {
                ParamHint hint = hintOf(jdbcType);
                result.add(hint != null ? hint : new ParamHint());
            }
            return result;
        }
    }
}
