// T-SyncK2JMaster115 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/XmlParamTypeResolver.kt 移植（T-FCA-01 集合快捷别名 + kindOfTypeText 数组判定前移）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.psi.EntityFieldCollector;
import cn.song.mybatisplus.visualizer.sql.ParamQuote;
import cn.song.mybatisplus.visualizer.xml.ParamKind;
import cn.song.mybatisplus.visualizer.xml.ParamTypeInfo;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * XML 参数类型解析器（T-M29.5 / T-PT-01）：按 mapper 方法签名解析参数 Java 类型 →
 * 参数类型类别映射（{@link ParamTypeInfo}）。
 *
 * <p>链路：XML `{@code <mapper namespace>}` → {@link JavaPsiFacade#findClass}（真实 IDE resolve）→ 语句 id 对应
 * 接口方法 → 参数列表：`@Param("p")` 值 / 参数名 → 参数类型类别：
 * <ul>
 *  <li>**DTO/实体**（非 java.* 非枚举）：逐字段解析类型（含父类字段、嵌套 DTO 递归），
 *      键 = `p.字段名`，值 = 字段类型类别；</li>
 *  <li>**Map / 基本类型 / 其他简单类型**：键 = 参数名，值 = 类型类别（Map → OBJECT，数值 → NUMBER）；</li>
 *  <li>注解类不可解析（测试沙盒）时按短名兜底；无 @Param 用参数名；</li>
 *  <li>**多参数位置别名**（T-PC2-03）：全部参数都有显式 `@Param` 时补 `param1..paramN`
 *      （MyBatis `ParamMap` 位置别名，全或无门槛见 {@link #resolveTypes}）；</li>
 *  <li>**集合快捷别名**（T-FCA-01）：单参数且无显式 `@Param` 时按参数集合形态补
 *      `list` / `collection` / `array`（MyBatis `ParamNameResolver.wrapToMapIfCollection`，
 *      键集与门槛见 {@link #collectionAliasTypes}）。</li>
 * </ul>
 *
 * <p>类型映射规则（T-PT-01，见 {@link #kindOf}）：String/CharSequence→TEXT、数值族→NUMBER、
 * `{@code java.time.*}` 与 `{@code java.util.Date}`→TEMPORAL、List/Collection/Set 族与数组→COLLECTION、
 * DTO/Map→OBJECT；映射不到一律 UNKNOWN（行为=升级前，宁缺勿错）。
 * T-PC2-01/02 补两处漏收：`{@code java.math.BigInteger}`→NUMBER（数值族漏收）、
 * `{@code java.util.UUID}`→TEXT（带引号字符串形态比较，与 TEXT 引号语义一致）。
 *
 * <p>不可解析（LightPlatform 跨文件 resolve 不可用 / namespace 缺失 / 方法缺失）→ 空映射，
 * 面板对未命中参数兜底（T-M29.4 引号行为 + T-PT-02 无类型信息不提示）。
 */
public final class XmlParamTypeResolver {

    public static final XmlParamTypeResolver INSTANCE = new XmlParamTypeResolver();

    private static final String PARAM_ANNOTATION = "org.apache.ibatis.annotations.Param";

    /** 时间类型简单名（含 java.time.* 各形态 + java.util.Date 族）。 */
    private static final Set<String> TEMPORAL_SIMPLE_NAMES = Set.of(
            "LocalDate", "LocalDateTime", "LocalTime", "OffsetDateTime", "OffsetTime",
            "ZonedDateTime", "Instant", "Year", "YearMonth", "MonthDay", "Date", "Calendar");

    /** 字符序列简单名（TEXT）。 */
    private static final Set<String> TEXT_SIMPLE_NAMES = Set.of("String", "CharSequence");

    /**
     * 文本档类型全限定名（T-PC2-02）：{@code java.util.UUID} 在 SQL 里以带引号字符串形态比较
     * （{@code id = '550e8400-…'}），引号语义与 {@link ParamKind#TEXT} 一致——故归 TEXT，<b>不扩枚举</b>
     * （新类别会给 {@code XmlPreviewModel} 等既有 {@code when} 分支带连带成本）。
     */
    private static final String UUID_FQN = "java.util.UUID";

    /** 集合简单名（COLLECTION）：JDK 集合族——List/Collection 之外含 Set/Queue 等同族形态。 */
    private static final Set<String> COLLECTION_SIMPLE_NAMES = Set.of(
            "List", "ArrayList", "LinkedList", "Collection", "Set", "HashSet", "LinkedHashSet",
            "TreeSet", "SortedSet", "NavigableSet", "Queue", "Deque", "ArrayDeque", "Vector", "Stack", "Iterable");

    /** 键值对象简单名（OBJECT）：Map 族——按属性路径取值，不整体内联。 */
    private static final Set<String> OBJECT_SIMPLE_NAMES = Set.of(
            "Map", "HashMap", "LinkedHashMap", "TreeMap", "SortedMap", "NavigableMap",
            "ConcurrentHashMap", "Hashtable", "Properties");

    /**
     * `List` 族简单名（T-FCA-01）：MyBatis `object instanceof List` 成立 → 别名 `collection` + `list` 双写。
     * 只列 {@link #COLLECTION_SIMPLE_NAMES} 中确为 `java.util.List` 子类型的成员（`Set`/`Queue` 族不在内）。
     */
    private static final Set<String> LIST_SIMPLE_NAMES = Set.of(
            "List", "ArrayList", "LinkedList", "Vector", "Stack");

    /**
     * 集合族中**非 `java.util.Collection`** 的简单名（T-FCA-01）：`Iterable` 虽是集合语义，
     * 但 `wrapToMapIfCollection` 的 `instanceof Collection` 判定不成立 → 真引擎不包装、无别名键。
     */
    private static final Set<String> NON_COLLECTION_SIMPLE_NAMES = Set.of("Iterable");

    private XmlParamTypeResolver() {
    }

    /**
     * 解析语句参数类型类别映射。{@code project} 不可用（null）时返回空映射。
     *
     * <p>T-PC2-03（Z-17 全或无门槛）：<b>仅当全部参数都有显式 {@code @Param}（值非空白）时</b>才补
     * {@code param1..paramN}（MyBatis {@code ParamMap} 的位置别名，类型跟随对应位置参数）。一旦存在未命名参数
     * 即整体不推导——MyBatis 实际会用位置名，我方保守取「宁缺勿错」：宁可不给 {@code paramN} 类型
     * （面板按 UNKNOWN 兜底，行为=升级前），也不给可能错位的类型。
     * 规则来源标注：{@code mybatis-3.5.19-paramN-全或无}。
     *
     * <p>与注解路径的差异（登记，不擅改）：{@code AnnotationSqlPreviewService.paramTypes} 现状为
     * 「多参数或含 @Param 就补 paramN」，比本门槛宽；两条路径语义分叉已在计划 §八 留痕。
     */
    @NotNull
    public Map<String, ParamTypeInfo> resolveTypes(@Nullable Project project, @Nullable String namespace, @NotNull String statementId) {
        if (project == null || namespace == null || namespace.isEmpty()) return java.util.Collections.emptyMap();
        PsiClass mapperClass = JavaPsiFacade.getInstance(project)
                .findClass(namespace, GlobalSearchScope.allScope(project));
        if (mapperClass == null) return java.util.Collections.emptyMap();
        PsiMethod[] methods = mapperClass.findMethodsByName(statementId, false);
        if (methods.length == 0) return java.util.Collections.emptyMap();
        PsiMethod method = methods[0];
        Map<String, ParamTypeInfo> result = new LinkedHashMap<>();
        PsiParameter[] parameters = method.getParameterList().getParameters();
        for (PsiParameter param : parameters) {
            String paramName = paramNameOf(param);
            if (paramName == null) continue;
            PsiType type = param.getType();
            PsiClass psiClass = (type instanceof PsiClassType) ? ((PsiClassType) type).resolve() : null;
            if (psiClass == null) {
                // 基本类型/数组/不可解析：直接按类型文本判定（property = 参数名）
                result.put(paramName, typeInfoOf(type));
            } else if (isDtoLike(psiClass)) {
                collectFieldTypes(paramName, psiClass, result);
            } else {
                result.put(paramName, typeInfoOf(type, psiClass));
            }
        }
        // T-PC2-03：全或无门槛（见 KDoc）——全部参数都有显式 @Param 才补位置别名
        if (parameters.length > 0) {
            boolean allExplicit = true;
            for (PsiParameter param : parameters) {
                if (explicitParamNameOf(param) == null) {
                    allExplicit = false;
                    break;
                }
            }
            if (allExplicit) {
                for (int index = 0; index < parameters.length; index++) {
                    result.put("param" + (index + 1), typeInfoOf(parameters[index].getType()));
                }
            }
        }
        // T-FCA-01：foreach 集合快捷别名（MyBatis ParamNameResolver.wrapToMapIfCollection）
        result.putAll(collectionAliasTypes(parameters));
        return result;
    }

    /**
     * T-FCA-01（N-32）：foreach 集合快捷别名 —— `collection="list" / "collection" / "array"` 的类型条目。
     *
     * <p>MyBatis 3.5.19 `ParamNameResolver.getNamedParams` 在「<b>无 @Param 注解 ∧ 恰一个非特殊参数</b>」时
     * 把参数对象交给 `wrapToMapIfCollection(value, 实参名)` 包成 `ParamMap`，此后语句里可用别名键取到该集合。
     * 键集<b>经真引擎实测钉住</b>（`xml/fidelity/RealEngineFidelityTest` 的「别名键集钉」用例，非竞品转述）：
     * <ul>
     *  <li>`T[]`（<b>任意元素类型，含泛型数组 `List&lt;Long&gt;[]`</b>；`byte[]` 除外，见下方近似登记）→ 仅 `array`；</li>
     *  <li>`List`（含 ArrayList/LinkedList/Vector/Stack 等 List 族）→ `collection` + `list` 双写；</li>
     *  <li>`Collection` 非 `List`（Set/Queue/Deque 族）→ 仅 `collection`；</li>
     *  <li>其余（Map / DTO / 标量 / `Iterable` 等非 Collection）→ 不包装，无别名键。</li>
     * </ul>
     * 规则来源标注：`mybatis-3.5.19-param-name-resolver-wrap-to-map-if-collection`。
     *
     * <p><b>分支顺序是判据的一部分（T-FCA-01 修正）</b>：数组形态<b>先判</b>、集合族后判。先剥泛型
     * （`substringBefore('&lt;')`）会把 `java.util.List&lt;java.lang.Long&gt;[]` 截成 `java.util.List`，
     * 据此判族会误产 `list` 键——而数组在真引擎里<b>没有</b> `list`/`collection` 键（`instanceof Collection`
     * 对数组不成立，走 `getClass().isArray()` 支）。本方法因此与 {@link #kindOfTypeText} 同口径：数组先判。
     *
     * <p><b>门槛与 {@link #resolveTypes} 的 `paramN` 门槛互补而非复用</b>：那条是「全部参数都有显式 `@Param`」
     * （多参数 `ParamMap` 位置别名），本条是「单参数 ∧ 无显式 `@Param`」——MyBatis 侧同样是互斥两支
     * （`hasParamAnnotation` 为真即走多参数支，不再包装集合）。
     *
     * <p>类型读不到类别（非 COLLECTION）时不产出别名（宁缺勿错：`Iterable` 不是 `java.util.Collection`，
     * 真引擎不包装，故显式排除）。<b>两处已知近似（登记，无测试覆盖，本次不扩面；键集为 Heavy 沙盒实测）</b>：
     * ① `byte[]`/`Byte[]` 按 BLOB 落 {@link ParamKind#UNKNOWN} → 实测键集仅 `[bytes]`，无 `array` 键
     * （真引擎会包装成 `array`）；② 用户自定义 `implements/extends List` 的类经 PSI 落 OBJECT
     * （{@link #isDtoLike}，走 DTO 字段展开分支）→ 实测键集为空（真引擎会给 `collection` + `list`）。
     * 两形态均按「宁缺勿错」保守处置：少给键 = 面板按 UNKNOWN 兜底，多给键 = 可能错位的类型。
     *
     * <p>移植注记：master 侧为 `internal fun`（Java 侧落 `public`，k2j 惯例）。
     */
    @NotNull
    public Map<String, ParamTypeInfo> collectionAliasTypes(@NotNull PsiParameter[] parameters) {
        if (parameters.length != 1) return java.util.Collections.emptyMap();
        PsiParameter param = parameters[0];
        if (explicitParamNameOf(param) != null) return java.util.Collections.emptyMap();
        ParamTypeInfo info = typeInfoOf(param.getType());
        if (info.kind() != ParamKind.COLLECTION) return java.util.Collections.emptyMap();
        // 数组先判（含泛型数组）——顺序不可颠倒，理由见 KDoc「分支顺序是判据的一部分」
        String canonical = param.getType().getCanonicalText().trim();
        if (canonical.endsWith("[]")) {
            Map<String, ParamTypeInfo> arrayOnly = new LinkedHashMap<>();
            arrayOnly.put("array", info);
            return arrayOnly;
        }
        int lt = canonical.indexOf('<');
        String beforeGenerics = (lt >= 0 ? canonical.substring(0, lt) : canonical).trim();
        int dot = beforeGenerics.lastIndexOf('.');
        String simple = dot >= 0 ? beforeGenerics.substring(dot + 1) : beforeGenerics;
        if (NON_COLLECTION_SIMPLE_NAMES.contains(simple)) return java.util.Collections.emptyMap();
        Map<String, ParamTypeInfo> aliases = new LinkedHashMap<>();
        if (LIST_SIMPLE_NAMES.contains(simple)) {
            aliases.put("collection", info);
            aliases.put("list", info);
        } else {
            aliases.put("collection", info);
        }
        return aliases;
    }

    /**
     * T-PT-01：参数类型类别映射的**派生引号视图**（T-M29.5 布尔通道保留）——
     * NUMBER→false、其余 true；与升级前 {@link ParamQuote#decide} 逐值等价（防回归断言见 Heavy 用例）。
     */
    @NotNull
    public Map<String, Boolean> resolveQuotes(@Nullable Project project, @Nullable String namespace, @NotNull String statementId) {
        Map<String, Boolean> derived = new LinkedHashMap<>();
        for (Map.Entry<String, ParamTypeInfo> e : resolveTypes(project, namespace, statementId).entrySet()) {
            derived.put(e.getKey(), e.getValue().kind().isQuoteRequired());
        }
        return derived;
    }

    /**
     * DTO/实体字段递归收集：`p.字段名` → 类型类别；嵌套 DTO 继续递归（`p.dto.x`）。
     *
     * <p>D254：<b>不使用 {@code getAllFields()}（master {@code allFields}）</b>——平台实现不按名去重
     * （本类字段先、父类后），{@code sink[key] =} 的后写覆盖会让父类同名字段<b>盖掉子类</b>，与真机
     * {@code Reflector}（本类字段先注册、父类同名不覆盖）方向相反。改用
     * {@link EntityFieldCollector#collectProperties}：
     * <ul>
     *  <li>同名合并取子类；</li>
     *  <li>{@code static} 常量<b>保留</b>（{@code Reflector.addFields} 仍注册其 getter，真机
     *      {@code #{p.CONST}} 可取值）；</li>
     *  <li>{@code serialVersionUID} / {@code class} / {@code $} 前缀不产出键
     *      （{@code Reflector.isValidPropertyName} 同口径）。</li>
     * </ul>
     */
    private void collectFieldTypes(@NotNull String prefix, @NotNull PsiClass psiClass, @NotNull Map<String, ParamTypeInfo> sink) {
        for (PsiField field : EntityFieldCollector.collectProperties(psiClass)) {
            String fieldName = field.getName();
            if (fieldName == null) continue;
            String key = prefix + "." + fieldName;
            PsiClass fieldClass = (field.getType() instanceof PsiClassType)
                    ? ((PsiClassType) field.getType()).resolve() : null;
            if (fieldClass != null && isDtoLike(fieldClass)) {
                collectFieldTypes(key, fieldClass, sink);
            } else {
                sink.put(key, typeInfoOf(field.getType(), fieldClass));
            }
        }
    }

    /** PSI 类型 → 类型信息（psi 层唯一映射入口；注解侧参数同用）。 */
    @NotNull
    public ParamTypeInfo typeInfoOf(@NotNull PsiType type, @Nullable PsiClass psiClass) {
        ParamKind kind = kindOf(type, psiClass);
        // O-78 T-ForeachItemRender：集合元素类别——泛型实参唯一时取之（`List<Integer>`→NUMBER），
        // 供 foreach item 占位键派生引号/类别；裸集合（无实参）、数组、多实参不产（宁缺勿错）
        ParamKind elementKind = null;
        if (kind == ParamKind.COLLECTION && type instanceof PsiClassType) {
            PsiType[] parameters = ((PsiClassType) type).getParameters();
            if (parameters.length == 1) { // Kotlin singleOrNull()
                PsiType arg = parameters[0];
                elementKind = kindOf(arg, (arg instanceof PsiClassType) ? ((PsiClassType) arg).resolve() : null);
            }
        }
        return new ParamTypeInfo(
                kind,
                // 原语类型（int/long 等）无 null 语义；其余保守视为可空
                !(type instanceof PsiPrimitiveType),
                elementKind);
    }

    /** {@link #typeInfoOf(PsiType, PsiClass)} 的缺省 psiClass 形态（Kotlin 默认实参 {@code (type as? PsiClassType)?.resolve()}）。 */
    @NotNull
    public ParamTypeInfo typeInfoOf(@NotNull PsiType type) {
        return typeInfoOf(type, (type instanceof PsiClassType) ? ((PsiClassType) type).resolve() : null);
    }

    /**
     * PSI 类型 → 类别（T-PT-01）：先按类型文本映射（简单名/全限定名两形态归一，与 {@link ParamQuote} 同口径），
     * 未命中且可解析为用户项目类（非 JDK、非枚举）→ OBJECT；其余 → {@link ParamKind#UNKNOWN}。
     */
    @NotNull
    public ParamKind kindOf(@NotNull PsiType type, @Nullable PsiClass psiClass) {
        ParamKind byText = kindOfTypeText(type.getCanonicalText());
        if (byText != null) return byText;
        if (psiClass != null && isDtoLike(psiClass)) return ParamKind.OBJECT;
        return ParamKind.UNKNOWN;
    }

    /**
     * 类型文本 → 类别（命中返回类别，未命中返回 null 交由调用方判 DTO 兜底）。
     *
     * <p><b>数组先于泛型剥壳（T-FCA-01 修正）</b>：`java.util.List&lt;java.lang.Long&gt;[]` 剥壳后得 `List`，
     * 按外层类型判定会漏掉「它是数组」这一事实——`Map&lt;K,V&gt;[]` 更会落 OBJECT（数组类别与元素类型无关）。
     * 数组一律 COLLECTION，但 `byte[]`/`Byte[]` 按 BLOB 语义落 UNKNOWN（T-PT-01 收窄，见实施记录）；
     * 非数组文本再剥泛型（`java.util.List&lt;java.lang.String&gt;` → `java.util.List`）；
     * NUMBER 判定复用 {@link ParamQuote#decide}（升级前数值集单一来源，杜绝两份数值名单漂移）；
     * `java.util.UUID` → TEXT（T-PC2-02，见 {@link #UUID_FQN}）。
     */
    @Nullable
    public ParamKind kindOfTypeText(@Nullable String typeText) {
        if (typeText == null || typeText.isBlank()) return null;
        String full = typeText.trim();
        if (full.endsWith("[]")) {
            int lastDot = full.lastIndexOf('.');
            String afterLastDot = lastDot >= 0 ? full.substring(lastDot + 1) : full;
            return isByteArray(afterLastDot) ? ParamKind.UNKNOWN : ParamKind.COLLECTION;
        }
        int lt = full.indexOf('<');
        String bare = (lt >= 0 ? full.substring(0, lt) : full).trim();
        int dot = bare.lastIndexOf('.');
        String simple = dot >= 0 ? bare.substring(dot + 1) : bare;
        if (bare.equals(UUID_FQN) || simple.equals("UUID")) return ParamKind.TEXT;
        if (bare.startsWith("java.time.") || TEMPORAL_SIMPLE_NAMES.contains(simple)) return ParamKind.TEMPORAL;
        if (Boolean.FALSE.equals(ParamQuote.decide(bare))) return ParamKind.NUMBER;
        if (TEXT_SIMPLE_NAMES.contains(simple)) return ParamKind.TEXT;
        if (COLLECTION_SIMPLE_NAMES.contains(simple)) return ParamKind.COLLECTION;
        if (OBJECT_SIMPLE_NAMES.contains(simple)) return ParamKind.OBJECT;
        return null;
    }

    /** 字节数组（BLOB 语义，不按集合提示展开）。 */
    private boolean isByteArray(@NotNull String simpleName) {
        return simpleName.equals("byte[]") || simpleName.equals("Byte[]") || simpleName.equals("java.lang.Byte[]");
    }

    /** DTO/实体判定：非 JDK 类、非枚举。 */
    private boolean isDtoLike(@NotNull PsiClass psiClass) {
        String qn = psiClass.getQualifiedName();
        if (qn == null) return false;
        return !qn.startsWith("java.") && !qn.startsWith("javax.") && !psiClass.isEnum();
    }

    /** @Param 注解（全限定名优先、短名兜底——注解类不可解析的沙盒环境）。 */
    @Nullable
    private PsiAnnotation paramAnnotationOf(@NotNull PsiParameter param) {
        PsiAnnotation[] annotations = param.getModifierList() != null
                ? param.getModifierList().getAnnotations() : new PsiAnnotation[0];
        for (PsiAnnotation a : annotations) {
            if (PARAM_ANNOTATION.equals(a.getQualifiedName())) return a;
        }
        for (PsiAnnotation a : annotations) {
            if (a.getNameReferenceElement() != null
                    && "Param".equals(a.getNameReferenceElement().getReferenceName())) {
                return a;
            }
        }
        return null;
    }

    /**
     * 显式 {@code @Param} 值（无注解 / 值取不到 / 值空白 → null）。
     * T-PC2-03 的「全或无门槛」以此判定「显式命名」——值空白视为未命名（宁缺勿错，不推导 paramN）。
     */
    @Nullable
    private String explicitParamNameOf(@NotNull PsiParameter param) {
        PsiAnnotation annotation = paramAnnotationOf(param);
        if (annotation == null) return null;
        PsiAnnotationMemberValue memberValue = annotation.findAttributeValue("value");
        if (memberValue instanceof PsiLiteralExpression) {
            Object value = ((PsiLiteralExpression) memberValue).getValue();
            if (value instanceof String) {
                String s = (String) value;
                return s.isBlank() ? null : s;
            }
        }
        return null;
    }

    /** @Param 值（全限定名优先、短名兜底——注解类不可解析的沙盒环境）；无注解/取不到 → 参数名。 */
    @Nullable
    private String paramNameOf(@NotNull PsiParameter param) {
        String explicit = explicitParamNameOf(param);
        return explicit != null ? explicit : param.getName();
    }
}
