package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.psi.EntityFieldCollector
import cn.song.mybatisplus.visualizer.sql.ParamQuote
import cn.song.mybatisplus.visualizer.xml.ParamKind
import cn.song.mybatisplus.visualizer.xml.ParamTypeInfo
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.search.GlobalSearchScope

/**
 * XML 参数类型解析器（T-M29.5 / T-PT-01）：按 mapper 方法签名解析参数 Java 类型 →
 * 参数类型类别映射（[ParamTypeInfo]）。
 *
 * 链路：XML `<mapper namespace>` → [JavaPsiFacade.findClass]（真实 IDE resolve）→ 语句 id 对应
 * 接口方法 → 参数列表：`@Param("p")` 值 / 参数名 → 参数类型类别：
 *  - **DTO/实体**（非 java.* 非枚举）：逐字段解析类型（含父类字段、嵌套 DTO 递归），
 *    键 = `p.字段名`，值 = 字段类型类别；
 *  - **Map / 基本类型 / 其他简单类型**：键 = 参数名，值 = 类型类别（Map → OBJECT，数值 → NUMBER）；
 *  - 注解类不可解析（测试沙盒）时按短名兜底；无 @Param 用参数名；
 *  - **多参数位置别名**（T-PC2-03）：全部参数都有显式 `@Param` 时补 `param1..paramN`
 *    （MyBatis `ParamMap` 位置别名，全或无门槛见 [resolveTypes]）；
 *  - **集合快捷别名**（T-FCA-01）：单参数且无显式 `@Param` 时按参数集合形态补
 *    `list` / `collection` / `array`（MyBatis `ParamNameResolver.wrapToMapIfCollection`，
 *    键集与门槛见 [collectionAliasTypes]）。
 *
 * 类型映射规则（T-PT-01，见 [kindOf]）：String/CharSequence→TEXT、数值族→NUMBER、
 * `java.time.*` 与 `java.util.Date`→TEMPORAL、List/Collection/Set 族与数组→COLLECTION、
 * DTO/Map→OBJECT；映射不到一律 UNKNOWN（行为=升级前，宁缺勿错）。
 * T-PC2-01/02 补两处漏收：`java.math.BigInteger`→NUMBER（数值族漏收）、
 * `java.util.UUID`→TEXT（带引号字符串形态比较，与 TEXT 引号语义一致）。
 *
 * 不可解析（LightPlatform 跨文件 resolve 不可用 / namespace 缺失 / 方法缺失）→ 空映射，
 * 面板对未命中参数兜底（T-M29.4 引号行为 + T-PT-02 无类型信息不提示）。
 */
object XmlParamTypeResolver {

    private const val PARAM_ANNOTATION = "org.apache.ibatis.annotations.Param"

    /** 时间类型简单名（含 java.time.* 各形态 + java.util.Date 族）。 */
    private val TEMPORAL_SIMPLE_NAMES = setOf(
        "LocalDate", "LocalDateTime", "LocalTime", "OffsetDateTime", "OffsetTime",
        "ZonedDateTime", "Instant", "Year", "YearMonth", "MonthDay", "Date", "Calendar",
    )

    /** 字符序列简单名（TEXT）。 */
    private val TEXT_SIMPLE_NAMES = setOf("String", "CharSequence")

    /**
     * 文本档类型全限定名（T-PC2-02）：`java.util.UUID` 在 SQL 里以带引号字符串形态比较
     * （`id = '550e8400-…'`），引号语义与 [ParamKind.TEXT] 一致——故归 TEXT，**不扩枚举**
     * （新类别会给 `XmlPreviewModel` 等既有 `when` 分支带连带成本）。
     */
    private const val UUID_FQN = "java.util.UUID"

    /** 集合简单名（COLLECTION）：JDK 集合族——List/Collection 之外含 Set/Queue 等同族形态。 */
    private val COLLECTION_SIMPLE_NAMES = setOf(
        "List", "ArrayList", "LinkedList", "Collection", "Set", "HashSet", "LinkedHashSet",
        "TreeSet", "SortedSet", "NavigableSet", "Queue", "Deque", "ArrayDeque", "Vector", "Stack", "Iterable",
    )

    /** 键值对象简单名（OBJECT）：Map 族——按属性路径取值，不整体内联。 */
    private val OBJECT_SIMPLE_NAMES = setOf(
        "Map", "HashMap", "LinkedHashMap", "TreeMap", "SortedMap", "NavigableMap",
        "ConcurrentHashMap", "Hashtable", "Properties",
    )

    /**
     * `List` 族简单名（T-FCA-01）：MyBatis `object instanceof List` 成立 → 别名 `collection` + `list` 双写。
     * 只列 [COLLECTION_SIMPLE_NAMES] 中确为 `java.util.List` 子类型的成员（`Set`/`Queue` 族不在内）。
     */
    private val LIST_SIMPLE_NAMES = setOf(
        "List", "ArrayList", "LinkedList", "Vector", "Stack",
    )

    /**
     * 集合族中**非 `java.util.Collection`** 的简单名（T-FCA-01）：`Iterable` 虽是集合语义，
     * 但 `wrapToMapIfCollection` 的 `instanceof Collection` 判定不成立 → 真引擎不包装、无别名键。
     */
    private val NON_COLLECTION_SIMPLE_NAMES = setOf("Iterable")

    /**
     * 解析语句参数类型类别映射。@param project 不可用（null）时返回空映射。
     *
     * T-PC2-03（Z-17 全或无门槛）：**仅当全部参数都有显式 `@Param`（值非空白）时**才补
     * `param1..paramN`（MyBatis `ParamMap` 的位置别名，类型跟随对应位置参数）。一旦存在未命名参数
     * 即整体不推导——MyBatis 实际会用位置名，我方保守取「宁缺勿错」：宁可不给 `paramN` 类型
     * （面板按 UNKNOWN 兜底，行为=升级前），也不给可能错位的类型。
     * 规则来源标注：`mybatis-3.5.19-paramN-全或无`。
     *
     * 与注解路径的差异（登记，不擅改）：[AnnotationSqlPreviewService.paramTypes] 现状为
     * 「多参数或含 @Param 就补 paramN」，比本门槛宽；两条路径语义分叉已在计划 §八 留痕。
     */
    fun resolveTypes(project: Project?, namespace: String?, statementId: String): Map<String, ParamTypeInfo> {
        if (project == null || namespace.isNullOrEmpty()) return emptyMap()
        val mapperClass = JavaPsiFacade.getInstance(project)
            .findClass(namespace, GlobalSearchScope.allScope(project)) ?: return emptyMap()
        val method = mapperClass.findMethodsByName(statementId, false).firstOrNull() ?: return emptyMap()
        val result = mutableMapOf<String, ParamTypeInfo>()
        val parameters = method.parameterList.parameters
        for (param in parameters) {
            val paramName = paramNameOf(param) ?: continue
            val type = param.type
            val psiClass = (type as? PsiClassType)?.resolve()
            when {
                psiClass == null -> {
                    // 基本类型/数组/不可解析：直接按类型文本判定（property = 参数名）
                    result[paramName] = typeInfoOf(type)
                }
                isDtoLike(psiClass) -> collectFieldTypes(paramName, psiClass, result)
                else -> result[paramName] = typeInfoOf(type, psiClass)
            }
        }
        // T-PC2-03：全或无门槛（见 KDoc）——全部参数都有显式 @Param 才补位置别名
        if (parameters.isNotEmpty() && parameters.all { explicitParamNameOf(it) != null }) {
            parameters.forEachIndexed { index, param ->
                result["param${index + 1}"] = typeInfoOf(param.type)
            }
        }
        // T-FCA-01：foreach 集合快捷别名（MyBatis ParamNameResolver.wrapToMapIfCollection）
        result += collectionAliasTypes(parameters)
        return result
    }

    /**
     * T-FCA-01（N-32）：foreach 集合快捷别名 —— `collection="list" / "collection" / "array"` 的类型条目。
     *
     * MyBatis 3.5.19 `ParamNameResolver.getNamedParams` 在「**无 @Param 注解 ∧ 恰一个非特殊参数**」时
     * 把参数对象交给 `wrapToMapIfCollection(value, 实参名)` 包成 `ParamMap`，此后语句里可用别名键取到该集合。
     * 键集**经真引擎实测钉住**（`xml/fidelity/RealEngineFidelityTest` 的「别名键集钉」用例，非竞品转述）：
     *  - `T[]`（**任意元素类型，含泛型数组 `List<Long>[]`**；`byte[]` 除外，见下方近似登记）→ 仅 `array`；
     *  - `List`（含 ArrayList/LinkedList/Vector/Stack 等 List 族）→ `collection` + `list` 双写；
     *  - `Collection` 非 `List`（Set/Queue/Deque 族）→ 仅 `collection`；
     *  - 其余（Map / DTO / 标量 / `Iterable` 等非 Collection）→ 不包装，无别名键。
     * 规则来源标注：`mybatis-3.5.19-param-name-resolver-wrap-to-map-if-collection`。
     *
     * **分支顺序是判据的一部分（T-FCA-01 修正）**：数组形态**先判**、集合族后判。先剥泛型
     * （`substringBefore('<')`）会把 `java.util.List<java.lang.Long>[]` 截成 `java.util.List`，
     * 据此判族会误产 `list` 键——而数组在真引擎里**没有** `list`/`collection` 键（`instanceof Collection`
     * 对数组不成立，走 `getClass().isArray()` 支）。本方法因此与 [kindOfTypeText] 同口径：数组先判。
     *
     * **门槛与 [resolveTypes] 的 `paramN` 门槛互补而非复用**：那条是「全部参数都有显式 `@Param`」
     * （多参数 `ParamMap` 位置别名），本条是「单参数 ∧ 无显式 `@Param`」——MyBatis 侧同样是互斥两支
     * （`hasParamAnnotation` 为真即走多参数支，不再包装集合）。
     *
     * 类型读不到类别（非 COLLECTION）时不产出别名（宁缺勿错：`Iterable` 不是 `java.util.Collection`，
     * 真引擎不包装，故显式排除）。**两处已知近似（登记，无测试覆盖，本次不扩面；键集为 Heavy 沙盒实测）**：
     * ① `byte[]`/`Byte[]` 按 BLOB 落 [ParamKind.UNKNOWN] → 实测键集仅 `[bytes]`，无 `array` 键
     * （真引擎会包装成 `array`）；② 用户自定义 `implements/extends List` 的类经 PSI 落 OBJECT
     * （[isDtoLike]，走 DTO 字段展开分支）→ 实测键集为空（真引擎会给 `collection` + `list`）。
     * 两形态均按「宁缺勿错」保守处置：少给键 = 面板按 UNKNOWN 兜底，多给键 = 可能错位的类型。
     */
    internal fun collectionAliasTypes(parameters: Array<PsiParameter>): Map<String, ParamTypeInfo> {
        if (parameters.size != 1) return emptyMap()
        val param = parameters.single()
        if (explicitParamNameOf(param) != null) return emptyMap()
        val info = typeInfoOf(param.type)
        if (info.kind != ParamKind.COLLECTION) return emptyMap()
        // 数组先判（含泛型数组）——顺序不可颠倒，理由见 KDoc「分支顺序是判据的一部分」
        val canonical = param.type.canonicalText.trim()
        if (canonical.endsWith("[]")) return mapOf("array" to info)
        val simple = canonical.substringBefore('<').trim().substringAfterLast('.')
        if (simple in NON_COLLECTION_SIMPLE_NAMES) return emptyMap()
        return if (simple in LIST_SIMPLE_NAMES) mapOf("collection" to info, "list" to info)
        else mapOf("collection" to info)
    }

    /**
     * T-PT-01：参数类型类别映射的**派生引号视图**（T-M29.5 布尔通道保留）——
     * NUMBER→false、其余 true；与升级前 [ParamQuote.decide] 逐值等价（防回归断言见 Heavy 用例）。
     */
    fun resolveQuotes(project: Project?, namespace: String?, statementId: String): Map<String, Boolean> =
        resolveTypes(project, namespace, statementId).mapValues { (_, info) -> info.kind.quoteRequired }

    /**
     * DTO/实体字段递归收集：`p.字段名` → 类型类别；嵌套 DTO 继续递归（`p.dto.x`）。
     *
     * D254：**不使用 `allFields`**——平台实现不按名去重（本类字段先、父类后），
     * `sink[key] =` 的后写覆盖会让父类同名字段**盖掉子类**，与真机 `Reflector`
     * （本类字段先注册、父类同名不覆盖）方向相反。改用 [EntityFieldCollector.collectProperties]：
     *  - 同名合并取子类；
     *  - `static` 常量**保留**（`Reflector.addFields` 仍注册其 getter，真机 `#{p.CONST}` 可取值）；
     *  - `serialVersionUID` / `class` / `$` 前缀不产出键（`Reflector.isValidPropertyName` 同口径）。
     */
    private fun collectFieldTypes(prefix: String, psiClass: PsiClass, sink: MutableMap<String, ParamTypeInfo>) {
        for (field in EntityFieldCollector.collectProperties(psiClass)) {
            val fieldName = field.name ?: continue
            val key = "$prefix.$fieldName"
            val fieldClass = (field.type as? PsiClassType)?.resolve()
            if (fieldClass != null && isDtoLike(fieldClass)) {
                collectFieldTypes(key, fieldClass, sink)
            } else {
                sink[key] = typeInfoOf(field.type, fieldClass)
            }
        }
    }

    /** PSI 类型 → 类型信息（psi 层唯一映射入口；注解侧参数同用）。 */
    internal fun typeInfoOf(type: PsiType, psiClass: PsiClass? = (type as? PsiClassType)?.resolve()): ParamTypeInfo {
        val kind = kindOf(type, psiClass)
        // O-78 T-ForeachItemRender：集合元素类别——泛型实参唯一时取之（`List<Integer>`→NUMBER），
        // 供 foreach item 占位键派生引号/类别；裸集合（无实参）、数组、多实参不产（宁缺勿错）
        val elementKind = if (kind == ParamKind.COLLECTION && type is PsiClassType) {
            type.parameters.singleOrNull()?.let { arg -> kindOf(arg, (arg as? PsiClassType)?.resolve()) }
        } else {
            null
        }
        return ParamTypeInfo(
            kind = kind,
            // 原语类型（int/long 等）无 null 语义；其余保守视为可空
            nullable = type !is PsiPrimitiveType,
            elementKind = elementKind,
        )
    }

    /**
     * PSI 类型 → 类别（T-PT-01）：先按类型文本映射（简单名/全限定名两形态归一，与 [ParamQuote] 同口径），
     * 未命中且可解析为用户项目类（非 JDK、非枚举）→ OBJECT；其余 → [ParamKind.UNKNOWN]。
     */
    internal fun kindOf(type: PsiType, psiClass: PsiClass?): ParamKind {
        kindOfTypeText(type.canonicalText)?.let { return it }
        if (psiClass != null && isDtoLike(psiClass)) return ParamKind.OBJECT
        return ParamKind.UNKNOWN
    }

    /**
     * 类型文本 → 类别（命中返回类别，未命中返回 null 交由调用方判 DTO 兜底）。
     *
     * **数组先于泛型剥壳（T-FCA-01 修正）**：`java.util.List<java.lang.Long>[]` 剥壳后得 `List`，
     * 按外层类型判定会漏掉「它是数组」这一事实——`Map<K,V>[]` 更会落 OBJECT（数组类别与元素类型无关）。
     * 数组一律 COLLECTION，但 `byte[]`/`Byte[]` 按 BLOB 语义落 UNKNOWN（T-PT-01 收窄，见实施记录）；
     * 非数组文本再剥泛型（`java.util.List<java.lang.String>` → `java.util.List`）；
     * NUMBER 判定复用 [ParamQuote.decide]（升级前数值集单一来源，杜绝两份数值名单漂移）；
     * `java.util.UUID` → TEXT（T-PC2-02，见 [UUID_FQN]）。
     */
    internal fun kindOfTypeText(typeText: String?): ParamKind? {
        if (typeText.isNullOrBlank()) return null
        val full = typeText.trim()
        if (full.endsWith("[]")) {
            return if (isByteArray(full.substringAfterLast('.'))) ParamKind.UNKNOWN else ParamKind.COLLECTION
        }
        val bare = full.substringBefore('<').trim()
        val simple = bare.substringAfterLast('.')
        return when {
            bare == UUID_FQN || simple == "UUID" -> ParamKind.TEXT
            bare.startsWith("java.time.") || simple in TEMPORAL_SIMPLE_NAMES -> ParamKind.TEMPORAL
            ParamQuote.decide(bare) == false -> ParamKind.NUMBER
            simple in TEXT_SIMPLE_NAMES -> ParamKind.TEXT
            simple in COLLECTION_SIMPLE_NAMES -> ParamKind.COLLECTION
            simple in OBJECT_SIMPLE_NAMES -> ParamKind.OBJECT
            else -> null
        }
    }

    /** 字节数组（BLOB 语义，不按集合提示展开）。 */
    private fun isByteArray(simpleName: String): Boolean =
        simpleName == "byte[]" || simpleName == "Byte[]" || simpleName == "java.lang.Byte[]"

    /** DTO/实体判定：非 JDK 类、非枚举——用户项目类视为可展开字段的对象（Map/List/String 等在 java.* 天然排除）。 */
    private fun isDtoLike(psiClass: PsiClass): Boolean {
        val qn = psiClass.qualifiedName ?: return false
        return !qn.startsWith("java.") && !qn.startsWith("javax.") && !psiClass.isEnum
    }

    /** @Param 注解（全限定名优先、短名兜底——注解类不可解析的沙盒环境）。 */
    private fun paramAnnotationOf(param: PsiParameter): PsiAnnotation? {
        val annotations = param.modifierList?.annotations.orEmpty()
        return annotations.firstOrNull { it.qualifiedName == PARAM_ANNOTATION }
            ?: annotations.firstOrNull { it.nameReferenceElement?.referenceName == "Param" }
    }

    /**
     * 显式 `@Param` 值（无注解 / 值取不到 / 值空白 → null）。
     * T-PC2-03 的「全或无门槛」以此判定「显式命名」——值空白视为未命名（宁缺勿错，不推导 paramN）。
     */
    private fun explicitParamNameOf(param: PsiParameter): String? {
        val annotation = paramAnnotationOf(param) ?: return null
        val memberValue = annotation.findAttributeValue("value")
        val value = (memberValue as? PsiLiteralExpression)?.value as? String
        return value?.takeIf { it.isNotBlank() }
    }

    /** @Param 值（全限定名优先、短名兜底——注解类不可解析的沙盒环境）；无注解/取不到 → 参数名。 */
    private fun paramNameOf(param: PsiParameter): String? = explicitParamNameOf(param) ?: param.name
}
