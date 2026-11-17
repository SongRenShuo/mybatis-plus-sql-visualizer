package cn.song.mybatisplus.visualizer.xml;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * XML 预览模型（T-M24-04）：驱动预览面板的动态条件勾选与参数输入。
 *
 * renderSql 是唯一渲染源——初始渲染与面板勾选重建共用，保证一致；
 * 纯逻辑（依赖 OgnlEvaluator / XmlSqlRenderer），不依赖 PSI / IDE，可独立单测。
 *
 * 模拟参数策略（M24 版，文档化）：
 *  - 顶层变量（bind / ${} 表达式引用的根标识符，排除 _parameter/_databaseId）
 *    统一占位为字符串 "1"；
 *  - if/when 真假由面板开关直接决定（启发式默认），表达式求值仅在无开关覆盖时发生；
 *  - foreach 展开次数由面板输入（默认 DEFAULT_FOREACH_SIZE），collection 表达式全文作为 mock 键；
 *  - bind 覆盖值优先（DynamicContext.isProtected）。
 */
public class XmlPreviewModel {

    /** foreach 默认展开次数（IN 场景两元素可直观展示 separator）。 */
    public static final int DEFAULT_FOREACH_SIZE = 2;

    /**
     * T-MbgPreviewParityAndOpenPerf (d)：MBG Criterion 的四个互斥 kind 标志
     * （M2 反推产物里的布尔键，也是 choose 组 when 分支 id 的后缀）。
     */
    private static final List<String> KIND_FLAGS = List.of("noValue", "singleValue", "betweenValue", "listValue");

    /** foreach item 占位键前缀（变量双写形态 {@code __frch_<item>_<唯一编号>}，见 ForeachSqlNode）。 */
    private static final String FRCH_PREFIX = "__frch_";

    /** item 占位键裸键形态：前缀 + item 名（贪婪）+ {@code _数字}；dotted 子路径（{@code __frch_st_0.x}）不匹配。 */
    private static final Pattern FOREACH_ITEM_KEY = Pattern.compile("^__frch_(.+)_(\\d+)$");

    /**
     * T-PT-03：TEMPORAL 参数占位值——带引号的日期字面量（${} 直替进 SQL 后即为合法字面量；
     * test 表达式里为非空字符串，判真与升级前的 "1" 一致）。
     */
    public static final String TEMPORAL_MOCK_VALUE = "'2026-01-01 00:00:00'";

    private final String statementId;
    private final XmlStatementKind kind;
    private final List<XmlConditionView> conditionViews;
    private final List<XmlForEachView> forEachViews;
    private final List<XmlBindView> bindViews;
    private final String parameterType;
    private final List<String> errors;
    private final Set<String> variables;
    private final Set<String> paramProps;
    private final Map<String, Object> staticFieldValues;
    private final Map<String, Object> extraBindings;
    private final Map<String, Boolean> paramQuotes;
    /** T-InjectionGate T-IG-01：语句文本含未转义的 ${} 直替 token（解析期事实）。 */
    private final boolean hasDollarRawSegments;
    /** T-PT-01：参数类型类别映射（参数路径 → ParamTypeInfo）。 */
    private final Map<String, ParamTypeInfo> paramTypes;
    /** choose 互斥组（T-MbgExamplePreview R4）——defaultState 每组只启用一个分支。 */
    private final List<XmlChooseGroup> chooseGroups;
    /** 语句形态档（T-MbgExamplePreview R5）——null 时不改任何默认值（升级前行为）。 */
    private final XmlPreviewProfile profile;
    /**
     * 集合表达式 → 元素级模拟值（T-MbgExamplePreview M2）。键与 {@link #forEachViews} 的
     * collectionExpr 同源（oredCriteria / criteria.criteria），值为调用点反推出的 Criteria / Criterion；
     * 缺席时退回「展开 N 次的无属性元素」。
     */
    private final Map<String, List<MockObject>> collectionElements;
    private final SqlNode rootNode;

    private XmlPreviewModel(
            String statementId,
            XmlStatementKind kind,
            List<XmlConditionView> conditionViews,
            List<XmlForEachView> forEachViews,
            List<XmlBindView> bindViews,
            String parameterType,
            List<String> errors,
            Set<String> variables,
            Set<String> paramProps,
            Map<String, Object> staticFieldValues,
            Map<String, Object> extraBindings,
            Map<String, Boolean> paramQuotes,
            boolean hasDollarRawSegments,
            Map<String, ParamTypeInfo> paramTypes,
            List<XmlChooseGroup> chooseGroups,
            XmlPreviewProfile profile,
            Map<String, List<MockObject>> collectionElements,
            SqlNode rootNode
    ) {
        this.statementId = statementId;
        this.kind = kind;
        this.conditionViews = conditionViews;
        this.forEachViews = forEachViews;
        this.bindViews = bindViews;
        this.parameterType = parameterType;
        this.errors = errors;
        this.variables = variables;
        this.paramProps = paramProps;
        this.staticFieldValues = staticFieldValues;
        this.extraBindings = extraBindings;
        this.paramQuotes = paramQuotes;
        this.hasDollarRawSegments = hasDollarRawSegments;
        this.paramTypes = paramTypes;
        this.chooseGroups = List.copyOf(chooseGroups);
        this.profile = profile;
        this.collectionElements = Map.copyOf(collectionElements);
        this.rootNode = rootNode;
    }

    public String getStatementId() {
        return statementId;
    }

    public XmlStatementKind getKind() {
        return kind;
    }

    public List<XmlConditionView> getConditionViews() {
        return conditionViews;
    }

    public List<XmlForEachView> getForEachViews() {
        return forEachViews;
    }

    public List<XmlBindView> getBindViews() {
        return bindViews;
    }

    public String getParameterType() {
        return parameterType;
    }

    public List<String> getErrors() {
        return errors;
    }

    public Set<String> getVariables() {
        return variables;
    }

    public Set<String> getParamProps() {
        return paramProps;
    }

    public Map<String, Object> getStaticFieldValues() {
        return staticFieldValues;
    }

    public Map<String, Object> getExtraBindings() {
        return extraBindings;
    }

    public Map<String, Boolean> getParamQuotes() {
        return paramQuotes;
    }

    /**
     * T-InjectionGate T-IG-01：语句文本含未转义的 ${} 直替 token（解析期事实，与替换成功与否无关——
     * 成功替换为模拟值后产物文本里已无 ${} 痕迹，最终文本 regex 会漏掉全部成功场景）。
     * 执行/EXPLAIN 前的 ${} 确认闸门据此判定「产物含 raw 原文拼接段」。默认 false，既有调用点零影响。
     */
    public boolean hasDollarRawSegments() {
        return hasDollarRawSegments;
    }

    /**
     * T-PT-01：参数类型类别映射（参数路径（{@code @Param} 值.字段 / 参数名）→ {@link ParamTypeInfo}）——
     * 由 XmlParamTypeResolver 按 mapper 方法签名参数 Java 类型映射（psi 层只做映射，类别枚举在 xml 层）。
     * 无类型信息不含键（面板按 UNKNOWN 兜底：无占位提示、mock 占位 "1"、引号兜底）。
     */
    public Map<String, ParamTypeInfo> getParamTypes() {
        return paramTypes;
    }

    /** choose 互斥组（T-MbgExamplePreview R4）。 */
    public List<XmlChooseGroup> getChooseGroups() {
        return chooseGroups;
    }

    /** 语句形态档（T-MbgExamplePreview R5）；null = 不改默认。 */
    public XmlPreviewProfile getProfile() {
        return profile;
    }

    /** 集合表达式 → 元素级模拟值（T-MbgExamplePreview M2）。 */
    public Map<String, List<MockObject>> getCollectionElements() {
        return collectionElements;
    }

    public SqlNode getRootNode() {
        return rootNode;
    }

    /**
     * T-M29.5：参数路径 → 引号策略（精确匹配优先，前缀匹配兜底）。
     * 未命中返回 null（面板兜底引号）。
     *
     * <p>O-78 T-ForeachItemRender：paramQuotes 派生表未含 foreach item 键，末位再经 {@link #paramTypeFor}
     * 兜底派生（item 键 → 元素类别 → quoteRequired）；paramTypes 空的旧布尔通道 paramTypeFor 恒
     * null，行为不变。</p>
     */
    public Boolean quoteFor(String source) {
        Boolean exact = paramQuotes.get(source);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, Boolean> e : paramQuotes.entrySet()) {
            if (source.startsWith(e.getKey() + ".")) {
                return e.getValue();
            }
        }
        ParamTypeInfo info = paramTypeFor(source);
        return info != null ? info.kind().isQuoteRequired() : null;
    }

    /**
     * T-PT-01：参数路径 → 类型信息（精确匹配优先、前缀匹配兜底，命中规则与 {@link #quoteFor} 一致）。
     * 未命中返回 null（调用方按 {@link ParamKind#UNKNOWN} 兜底）。
     *
     * <p>O-78 T-ForeachItemRender：末位追加 foreach item 占位键解析（{@link #foreachItemTypeInfo}）。</p>
     */
    public ParamTypeInfo paramTypeFor(String source) {
        ParamTypeInfo exact = paramTypes.get(source);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, ParamTypeInfo> e : paramTypes.entrySet()) {
            if (source.startsWith(e.getKey() + ".")) {
                return e.getValue();
            }
        }
        return foreachItemTypeInfo(source);
    }

    /**
     * O-78 T-ForeachItemRender：foreach item 占位键（{@code __frch_<item>_<n>}，{@link ForeachSqlNode} 变量双写）
     * → 集合元素类别。item 键不在 paramTypes（表按方法签名生成），借 {@link #forEachViews} 反查
     * collection 表达式取 {@link ParamTypeInfo#elementKind}；仅裸键命中（{@code __frch_st_0.name} 子路径不接）、
     * 无元素类别信息返回 null（宁缺勿错，调用方按 UNKNOWN 兜底=升级前行为）。
     */
    private ParamTypeInfo foreachItemTypeInfo(String source) {
        if (!source.startsWith(FRCH_PREFIX)) return null;
        Matcher match = FOREACH_ITEM_KEY.matcher(source);
        if (!match.matches()) return null;
        XmlForEachView view = null;
        for (XmlForEachView v : forEachViews) {
            if (Objects.equals(v.item(), match.group(1))) {
                view = v;
                break;
            }
        }
        if (view == null) return null;
        // 双层判空直译会漏内层 null 合并：paramTypes 可能未含该键、elementKind 亦可能为 null
        ParamTypeInfo collectionInfo = paramTypes.get(view.collectionExpr());
        if (collectionInfo == null || collectionInfo.elementKind() == null) return null;
        return new ParamTypeInfo(collectionInfo.elementKind());
    }

    /** T-PT-01：参数路径 → 类型类别（无类型信息 → {@link ParamKind#UNKNOWN}）。 */
    public ParamKind kindFor(String source) {
        ParamTypeInfo info = paramTypeFor(source);
        return info != null ? info.kind() : ParamKind.UNKNOWN;
    }

    /**
     * T-PC2-04（D1=B 定案）：执行 / EXPLAIN 前提示所需的「签名类型未能识别」参数清单
     * （按 {@code sources} 顺序去重）。
     *
     * <p>判定口径：<b>在 {@link #getParamTypes()} 中有条目且类别为 {@link ParamKind#UNKNOWN}</b>
     * （boolean / {@code byte[]} / 枚举等——类型读到了但映射不到类别，执行时以字面 {@code ?} 提交）。
     * <b>无类型信息</b>（键不在 {@code paramTypes}：mapper 接口不可解析 / 参数未参与签名解析）<b>不计入</b>
     * ——与 T-PT-02「无类型信息不提示」同向，也避免「接口解析不到 → 每次执行都提示」的噪音面。
     *
     * @param sources 面板参数行来源（{@code PreviewResult.unresolvedParams}，即渲染出的 {@code #{}} 属性序列）
     */
    public List<String> unknownParamSources(Collection<String> sources) {
        List<String> result = new ArrayList<>();
        for (String source : sources) {
            ParamTypeInfo info = paramTypeFor(source);
            if (info != null && info.kind() == ParamKind.UNKNOWN && !result.contains(source)) {
                result.add(source);
            }
        }
        return result;
    }

    public XmlPreviewState defaultState() {
        Set<String> suppressed = profile != null ? profile.suppressedConditionIds() : Set.of();
        Set<String> enabled = new LinkedHashSet<>();
        for (XmlConditionView view : conditionViews) {
            if (view.defaultEnabled() && !suppressed.contains(view.id())) {
                enabled.add(view.id());
            }
        }
        // T-MbgExamplePreview R4：同一 choose 的 when 互斥——组内多于一个时只留默认分支。
        // T-MbgPreviewParityAndOpenPerf (d)：M2 命中时优先按**反推到的真实 kind**选默认分支
        // （用户不动勾选即得到真实语义；手动改仍完全生效——开关仍是唯一真值源）。
        for (XmlChooseGroup group : chooseGroups) {
            List<String> candidates = new ArrayList<>();
            for (String branchId : group.branchIds()) {
                if (enabled.contains(branchId)) {
                    candidates.add(branchId);
                }
            }
            if (candidates.size() > 1) {
                String realKindBranch = realKindPreferredBranch(group);
                String keep = realKindBranch != null && candidates.contains(realKindBranch)
                        ? realKindBranch
                        : (group.defaultBranchId() != null && candidates.contains(group.defaultBranchId())
                            ? group.defaultBranchId() : candidates.get(0));
                for (String candidate : candidates) {
                    if (!candidate.equals(keep)) {
                        enabled.remove(candidate);
                    }
                }
            }
        }
        int count = profile != null && profile.forEachCount() != null
                ? profile.forEachCount() : DEFAULT_FOREACH_SIZE;
        // D13（真机 2026-11-12 16:26，`CustAddressMapper#deleteByExample`）：拿不到调用点时
        // `Example_Where_Clause` 的四个 kind 分支可能一个都没被组内收敛掉（真机复选框四个全勾），
        // 渲染按短路顺序永远走第一个 `noValue`——产物就成了 `WHERE ( {{criterion.condition}} )`，
        // 连 `?` 都没有，和 select 的默认形态不一致，用户会以为预览坏了。
        // MBG 无元素注入时统一收敛到 `singleValue`：形态 `WHERE ( {{criterion.condition}} ? )`。
        if (profile == XmlPreviewProfile.MBG_EXAMPLE && collectionElements.isEmpty()) {
            // 按「kind 分支」特征收敛，不依赖 choose 组是否被完整识别——真机上 deleteByExample 的
            // `<choose>` 没被识别成组，listValue 因此漏在组外一直显示勾选（勾着却永远轮不到）。
            List<String> kindIds = new ArrayList<>();
            for (XmlConditionView view : conditionViews) {
                if (isKindBranchId(view.id())) {
                    kindIds.add(view.id());
                }
            }
            String keep = null;
            for (String id : kindIds) {
                if (id.endsWith("." + KIND_FLAGS.get(1))) {
                    keep = id;
                    break;
                }
            }
            if (keep != null) {
                for (String id : kindIds) {
                    if (id.equals(keep)) {
                        enabled.add(id);
                    } else {
                        enabled.remove(id);
                    }
                }
            }
        }
        Map<String, Integer> forEachCounts = forEachViews.stream()
                .collect(Collectors.toMap(XmlForEachView::collectionExpr, v -> count));
        return new XmlPreviewState(enabled, forEachCounts, Set.of(), Map.of());
    }

    /** 分支 id 是否属于 MBG Criterion 的四个 kind 标志（{@code criterion.singleValue} 等）。 */
    private static boolean isKindBranchId(String id) {
        for (String flag : KIND_FLAGS) {
            if (id.equals(flag) || id.endsWith("." + flag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * T-MbgPreviewParityAndOpenPerf (d)：从 M2 反推的元素里读<b>真实 kind 的多数票</b>，映射回该组
     * 对应的 when 分支 id；拿不到（M2 未命中 / 元素没有这四个布尔 / 该 id 不在组里）返回 null，
     * 调用方退回既有启发式。
     *
     * <p>MBG 的 {@code addCriterion("CUSTOMERNUMBER =", value, "custNumber")} 形态让 M2 知道每个
     * criterion 究竟是 noValue / singleValue / betweenValue / listValue——面板默认值据此对齐即可，
     * 不必新增交互或提示。
     */
    private String realKindPreferredBranch(XmlChooseGroup group) {
        List<MockObject> elements = new ArrayList<>();
        for (List<MockObject> list : collectionElements.values()) {
            elements.addAll(list);
        }
        if (elements.isEmpty()) {
            return null;
        }
        String best = null;
        int bestCount = 0;
        for (String flag : KIND_FLAGS) {
            int count = 0;
            for (MockObject element : elements) {
                if (Boolean.TRUE.equals(element.get(flag))) {
                    count++;
                }
            }
            if (count > bestCount) {
                bestCount = count;
                best = flag;
            }
        }
        if (best == null) {
            return null;
        }
        for (String branchId : group.branchIds()) {
            if (branchId.equals(best) || branchId.endsWith("." + best)) {
                return branchId;
            }
        }
        return null;
    }

    public RenderedXmlSql renderSql() {
        return renderSql(defaultState());
    }

    public RenderedXmlSql renderSql(XmlPreviewState state) {
        Map<String, Boolean> conditionOverrides = new LinkedHashMap<>();
        for (XmlConditionView view : conditionViews) {
            // 全部条件都有明确真值（开关缺席 = false）——开关是分支唯一真值源，表达式仅无开关时兜底求值。
            // 例外（D5）：M2 反推到的元素带**逐条真实 kind**，choose 的四个 kind 分支不能再按「组内只开一个」
            // 强制——否则一个 between 条件会被 singleValue 模板渲染成 `between ?`（丢第二个占位，真机表现为
            // `BETWEEN '11'`）。这类 id 直接交给表达式按每条 criterion 自己的标志求值。
            conditionOverrides.put(view.id(), state.enabledIds().contains(view.id()));
        }
        for (String kindDrivenId : kindDrivenConditionIds()) {
            conditionOverrides.remove(kindDrivenId);
        }
        return new XmlSqlRenderer().render(
                rootNode,
                mockBindings(state),
                conditionOverrides,
                state.bindOverrides().keySet(),
                staticFieldValues,
                bindViews.stream().map(XmlBindView::name).collect(Collectors.toSet()),
                // T-MbgExamplePreview R1：预览链开启 `${}` 空值占位（真实 SQL 产物路径不受影响）
                true
        );
    }

    /**
     * 由数据（而非开关）决定真值的条件 id：M2 注入了元素级 kind 时，choose 里那四个 kind 分支。
     *
     * <p>判据只看组内分支是否<b>全是</b> {@link #KIND_FLAGS}——其它形态的 choose
     * （业务自写的 {@code status != null} 等）仍走「组内只开一个」的既有语义，不受影响。
     */
    private Set<String> kindDrivenConditionIds() {
        if (collectionElements.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (XmlChooseGroup group : chooseGroups) {
            if (group.branchIds().isEmpty()) {
                continue;
            }
            boolean allKindBranches = true;
            for (String id : group.branchIds()) {
                if (!isKindBranchId(id)) {
                    allKindBranches = false;
                    break;
                }
            }
            if (allKindBranches) {
                result.addAll(group.branchIds());
            }
        }
        return result;
    }

    /**
     * R30 探镜：本轮渲染的条件求值结果（三态，键与 {@link #getConditionViews()} 的 id 同源——面板条件行
     * 按 id 定位标注）。判定结果取 {@link RenderedXmlSql#conditionResults()}（只含被走到的条件），
     * 缺席映射 {@link ConditionOutcome#UNEVALUATED}。
     */
    public Map<String, ConditionOutcome> conditionOutcomes(RenderedXmlSql rendered) {
        Map<String, ConditionOutcome> result = new LinkedHashMap<>();
        for (XmlConditionView view : conditionViews) {
            Boolean recorded = rendered.conditionResults().get(view.id());
            ConditionOutcome outcome = recorded == null
                    ? ConditionOutcome.UNEVALUATED
                    : (recorded ? ConditionOutcome.TRUE : ConditionOutcome.FALSE);
            result.put(view.id(), outcome);
        }
        return result;
    }

    private Map<String, Object> mockBindings(XmlPreviewState state) {
        Map<String, Object> bindings = new HashMap<>();
        // 变量/参数名占位双写：_parameter 对象属性 + bindings 顶层（对齐 MyBatis ContextMap →
        // _parameter fallback：${param1} 与 _parameter.param1 均可取到占位值）
        // T-PT-03：占位值按类型类别分派（有类型信息才分派，无类型信息维持 "1"——行为=升级前）
        Set<String> mockPropsKeys = new HashSet<>(variables);
        mockPropsKeys.addAll(paramProps);
        Map<String, Object> mockProps = new HashMap<>();
        for (String k : mockPropsKeys) {
            mockProps.put(k, mockValueOf(k));
        }
        bindings.put(DynamicContext.PARAMETER_OBJECT_KEY, new MockObject(mockProps));
        for (String k : mockProps.keySet()) {
            bindings.put(k, mockValueOf(k));
        }
        bindings.put(DynamicContext.DATABASE_ID_KEY, null);
        for (XmlForEachView view : forEachViews) {
            List<MockObject> elements = collectionElements.get(view.collectionExpr());
            // D7：M2 注入（有元素级模拟值）时，点号表达式（`criteria.criteria`）不再绑成全局集合——
            // 否则内层 foreach 走的是这份全局列表，外层每条 Criteria 都会把全部条件再渲染一遍
            //（真机 `( a ) or( b )` 变成 `( a and b ) or ( a and b )`）。跳过绑定，让路径解析落到
            // 每条 Criteria 自己的 `criteria` 属性上（注入的 Criteria 必带该属性）。
            if (!collectionElements.isEmpty() && view.collectionExpr().indexOf('.') >= 0) {
                continue;
            }
            if (state.forEachNulls().contains(view.collectionExpr())) {
                bindings.put(view.collectionExpr(), null);
            } else if (elements != null) {
                // T-MbgExamplePreview M2：调用点反推到的元素级模拟值优先（Criteria / Criterion）
                bindings.put(view.collectionExpr(), new MockIterable(elements.size(), elements));
            } else {
                bindings.put(view.collectionExpr(),
                        new MockIterable(state.forEachCounts().getOrDefault(view.collectionExpr(), DEFAULT_FOREACH_SIZE)));
            }
        }
        for (Map.Entry<String, String> e : state.bindOverrides().entrySet()) {
            bindings.put(e.getKey(), e.getValue());
        }
        // T-M26-B：MP 占位 mock 覆盖（ew → 片段对象，置于最后优先级最高）
        bindings.putAll(extraBindings);
        return bindings;
    }

    /**
     * T-PT-03：变量/参数占位值按类型类别分派——NUMBER→{@code 1}、TEMPORAL→{@link #TEMPORAL_MOCK_VALUE}、
     * COLLECTION→{@link MockIterable}、OBJECT→{@link MockObject}；TEXT/UNKNOWN（无类型信息）→ {@code "1"}（升级前行为）。
     */
    private Object mockValueOf(String name) {
        ParamKind kind = kindFor(name);
        return switch (kind) {
            case NUMBER -> 1;
            case TEMPORAL -> TEMPORAL_MOCK_VALUE;
            case COLLECTION -> new MockIterable(DEFAULT_FOREACH_SIZE);
            case OBJECT -> new MockObject();
            case TEXT, UNKNOWN -> "1";
        };
    }

    /** 启发式默认勾选态：含 == null → false（变量默认非 null），其余 true（保守展开）。 */
    public static boolean defaultTruth(String test) {
        return !test.contains("== null");
    }

    /**
     * 由解析出的语句构建预览模型（变量收集 + 条件/foreach/bind 视图）。
     * T-M25-02：注解场景额外注入方法参数名（@Param 值/实际名/param1..N，MyBatis 多参数 Map 键）。
     * T-M25.2：staticFieldValues 为 @class@field → 值（PSI 层解析用户项目常量类）。
     * T-M26-B：extraBindings 为 MP 占位 mock（ew → 片段对象）。
     * T-PT-01：paramTypes 为参数类型类别映射；非空时 paramQuotes 由其派生
     * （NUMBER→false、其余 true），升级前显式传入的布尔通道继续可用（既有单测形态）。
     * T-MbgExamplePreview R5/M2：profile 语句形态档；collectionElements 元素级模拟值。
     */
    public static XmlPreviewModel from(
            XmlStatement statement,
            List<String> errors,
            Set<String> extraParamProps,
            Map<String, Object> staticFieldValues,
            Map<String, Object> extraBindings,
            Map<String, Boolean> paramQuotes,
            Map<String, ParamTypeInfo> paramTypes,
            XmlPreviewProfile profile,
            Map<String, List<MockObject>> collectionElements
    ) {
        List<XmlConditionView> conditionViews = new ArrayList<>();
        List<XmlForEachView> forEachViews = new ArrayList<>();
        List<XmlBindView> bindViews = new ArrayList<>();
        List<XmlChooseGroup> chooseGroups = new ArrayList<>();
        // Kotlin mutableSetOf() = LinkedHashSet（保序）：paramProps 顺序 = 动态标签收集序 + extraParamProps
        Set<String> variables = new LinkedHashSet<>();
        Set<String> paramProps = new LinkedHashSet<>();
        // T-InjectionGate T-IG-01：解析期 ${} token 收集（非空即「含 raw 原文拼接段」）
        List<String> dollarTokens = new ArrayList<>();
        collect(statement.node(), conditionViews, forEachViews, bindViews, variables, paramProps, dollarTokens, chooseGroups);
        variables.remove(DynamicContext.PARAMETER_OBJECT_KEY);
        variables.remove(DynamicContext.DATABASE_ID_KEY);
        paramProps.addAll(extraParamProps);
        // T-PT-01：布尔引号通道降级为派生视图——有类型类别时由类别派生（NUMBER→false 其余 true）
        Map<String, Boolean> effectiveQuotes;
        if (!paramTypes.isEmpty()) {
            effectiveQuotes = new HashMap<>();
            for (Map.Entry<String, ParamTypeInfo> e : paramTypes.entrySet()) {
                effectiveQuotes.put(e.getKey(), e.getValue().kind().isQuoteRequired());
            }
        } else {
            effectiveQuotes = paramQuotes;
        }
        return new XmlPreviewModel(
                statement.id(),
                statement.kind(),
                conditionViews,
                forEachViews,
                bindViews,
                statement.parameterType(),
                errors,
                variables,
                paramProps,
                staticFieldValues,
                extraBindings,
                effectiveQuotes,
                !dollarTokens.isEmpty(),
                paramTypes,
                chooseGroups,
                profile,
                collectionElements,
                statement.node()
        );
    }

    public static XmlPreviewModel from(
            XmlStatement statement,
            List<String> errors,
            Set<String> extraParamProps,
            Map<String, Object> staticFieldValues,
            Map<String, Object> extraBindings,
            Map<String, Boolean> paramQuotes,
            Map<String, ParamTypeInfo> paramTypes
    ) {
        return from(statement, errors, extraParamProps, staticFieldValues, extraBindings, paramQuotes,
                paramTypes, null, Map.of());
    }

    public static XmlPreviewModel from(XmlStatement statement, List<String> errors, Set<String> extraParamProps, Map<String, Object> staticFieldValues, Map<String, Object> extraBindings, Map<String, Boolean> paramQuotes) {
        return from(statement, errors, extraParamProps, staticFieldValues, extraBindings, paramQuotes, Map.of());
    }

    public static XmlPreviewModel from(
            XmlStatement statement,
            List<String> errors,
            Map<String, Object> staticFieldValues,
            Map<String, Object> extraBindings,
            Map<String, Boolean> paramQuotes
    ) {
        return from(statement, errors, Set.of(), staticFieldValues, extraBindings, paramQuotes);
    }

    public static XmlPreviewModel from(XmlStatement statement, List<String> errors, Set<String> extraParamProps, Map<String, Object> staticFieldValues) {
        return from(statement, errors, extraParamProps, staticFieldValues, Map.of(), Map.of());
    }

    public static XmlPreviewModel from(XmlStatement statement, List<String> errors, Set<String> extraParamProps) {
        return from(statement, errors, extraParamProps, Map.of(), Map.of(), Map.of());
    }

    public static XmlPreviewModel from(XmlStatement statement, List<String> errors) {
        return from(statement, errors, Set.of(), Map.of(), Map.of(), Map.of());
    }

    /** 表达式根路径分派：_parameter.x → 模拟参数对象属性；其余根（排除保留键）→ 变量。 */
    private static void collectExpressionPaths(String expression, Set<String> variables, Set<String> paramProps) {
        for (String path : OgnlEvaluator.rootPaths(expression)) {
            String prefix = DynamicContext.PARAMETER_OBJECT_KEY + ".";
            if (path.startsWith(prefix)) {
                paramProps.add(path.substring(prefix.length()));
            } else if (!path.equals(DynamicContext.PARAMETER_OBJECT_KEY) && !path.equals(DynamicContext.DATABASE_ID_KEY)) {
                variables.add(path);
            }
        }
    }

    /**
     * 统计节点树里的 {@code #{}} token 数（T-MbgExamplePreview R4：choose 组默认分支按「含参数」优先——
     * 带 {@code #{}} 的分支在预览里至少能给出 {@code ?} 占位，不带参数的 {@code and ${...}} 分支往往
     * 只剩一个裸 and）。转义 {@code \#{} 不计（与 TokenParser 语义一致）。
     */
    public static int countParameterTokens(SqlNode node) {
        if (node instanceof MixedSqlNode m) {
            int total = 0;
            for (SqlNode child : m.getChildren()) {
                total += countParameterTokens(child);
            }
            return total;
        }
        if (node instanceof TextSqlNode t) {
            int[] count = {0};
            TokenParser.parse(t.getText(), "#{", "}", tok -> {
                count[0]++;
                return tok;
            });
            return count[0];
        }
        if (node instanceof IfSqlNode i) {
            return countParameterTokens(i.getContents());
        }
        if (node instanceof TrimSqlNode tr) {
            return countParameterTokens(tr.getContents());
        }
        if (node instanceof ForeachSqlNode f) {
            return countParameterTokens(f.getContents());
        }
        if (node instanceof ChooseSqlNode c) {
            int total = 0;
            for (SqlNode whenNode : c.getWhenNodes()) {
                total += countParameterTokens(whenNode);
            }
            if (c.getDefaultNode() != null) {
                total += countParameterTokens(c.getDefaultNode());
            }
            return total;
        }
        return 0;
    }

    private static void collect(
            SqlNode node,
            List<XmlConditionView> conditionViews,
            List<XmlForEachView> forEachViews,
            List<XmlBindView> bindViews,
            Set<String> variables,
            Set<String> paramProps,
            List<String> dollarTokens,
            List<XmlChooseGroup> chooseGroups
    ) {
        if (node instanceof MixedSqlNode m) {
            for (SqlNode c : m.getChildren()) {
                collect(c, conditionViews, forEachViews, bindViews, variables, paramProps, dollarTokens, chooseGroups);
            }
        } else if (node instanceof StaticTextSqlNode) {
            // no-op
        } else if (node instanceof TextSqlNode t) {
            List<String> tokens = new ArrayList<>();
            TokenParser.parse(t.getText(), "${", "}", tok -> {
                tokens.add(tok);
                return tok;
            });
            dollarTokens.addAll(tokens);
            for (String tok : tokens) {
                collectExpressionPaths(tok, variables, paramProps);
            }
        } else if (node instanceof IfSqlNode i) {
            // T-ExprAstPrecheck T-EP-03：构建条件行时对 test 表达式跑支持面预检（纯 AST 遍历，
            // 零 PSI）——标注只是提示，展开行为仍由面板开关与 T-EP-02 的错误记录管
            ExprClassification classification = OgnlEvaluator.classifyDetail(i.getTest());
            conditionViews.add(new XmlConditionView(i.getTest(), i.getTest(), defaultTruth(i.getTest()),
                    classification.support(), classification.reason(), classification.detail()));
            collect(i.getContents(), conditionViews, forEachViews, bindViews, variables, paramProps, dollarTokens, chooseGroups);
        } else if (node instanceof ChooseSqlNode c) {
            // T-MbgExamplePreview R4：组内 when 归组（顺序 = 短路顺序），默认只启用一个——
            // 含 `#{}` 参数的分支里最先出现者优先，全无参数时取首个。否则四个互斥分支同真，
            // MBG Example 会永远短路到唯一不带参数的 noValue 分支。
            List<String> branchIds = new ArrayList<>();
            List<Integer> paramCounts = new ArrayList<>();
            for (SqlNode w : c.getWhenNodes()) {
                collect(w, conditionViews, forEachViews, bindViews, variables, paramProps, dollarTokens, chooseGroups);
                if (w instanceof IfSqlNode branch) {
                    branchIds.add(branch.getTest());
                    paramCounts.add(countParameterTokens(branch.getContents()));
                }
            }
            if (branchIds.size() > 1) {
                String defaultBranch = null;
                for (int i = 0; i < branchIds.size(); i++) {
                    if (paramCounts.get(i) > 0) {
                        defaultBranch = branchIds.get(i);
                        break;
                    }
                }
                if (defaultBranch == null) {
                    defaultBranch = branchIds.get(0);
                }
                chooseGroups.add(new XmlChooseGroup(branchIds, defaultBranch));
            }
            if (c.getDefaultNode() != null) {
                collect(c.getDefaultNode(), conditionViews, forEachViews, bindViews, variables, paramProps, dollarTokens, chooseGroups);
            }
        } else if (node instanceof TrimSqlNode tr) {
            collect(tr.getContents(), conditionViews, forEachViews, bindViews, variables, paramProps, dollarTokens, chooseGroups);
        } else if (node instanceof ForeachSqlNode f) {
            forEachViews.add(new XmlForEachView(f.getCollectionExpression(), f.getItem()));
            collect(f.getContents(), conditionViews, forEachViews, bindViews, variables, paramProps, dollarTokens, chooseGroups);
        } else if (node instanceof VarDeclSqlNode v) {
            bindViews.add(new XmlBindView(v.getName(), v.getExpression()));
            collectExpressionPaths(v.getExpression(), variables, paramProps);
        }
    }
}
