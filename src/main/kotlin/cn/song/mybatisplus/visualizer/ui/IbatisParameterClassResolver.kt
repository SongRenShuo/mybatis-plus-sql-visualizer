package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.sql.ParamHint
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache

/**
 * T-IbatisParamClassQuote：iBatis `parameterClass` → 属性类型 → 回填引号提示。
 *
 * iBatis 2 的 `#name#` 参数类型由引擎拿 `parameterClass` 反射决定（`User.username` 是 String →
 * `setString`），与值的字面形态无关。预览链此前唯一的类型来源是占位符自带的 jdbcType
 * （[cn.song.mybatisplus.visualizer.xml.ibatis.IbatisParamTypeHints]），裸 `#name#` 因此落到面板的
 * `NUMBER_REGEX` 启发式——`1213` 填进 VARCHAR 列被裸渲染成 `= 1213`。
 *
 * 解析口径（宁缺勿错，全部失败路径返回空表 = 维持现状）：
 *  - 全限定名 → `JavaPsiFacade.findClass`；短名 / `<typeAlias>` 别名 → 短名索引，**唯一命中才用**
 *    （多候选直接弃权：贴错类型比不贴更糟）；
 *  - 属性路径 `a.b.c` 逐段解析字段类型（只认字段，getter-only 的 POJO 退化为不产提示）；
 *  - 类型映射与 [cn.song.mybatisplus.visualizer.xml.ParamKind] 六类同源：字符族引号、数值裸、
 *    时间族引号 + temporal；List / Map / 未知对象不产提示。
 */
object IbatisParameterClassResolver {

    /**
     * 字符串族（含 `char` 与 UUID）。
     *
     * 集合按**简单名**建：真实 IDE 里 `PsiClassType.resolve()` 能拿到 `java.lang.String`，
     * 但测试/无 SDK 环境下 resolve 为 null，`canonicalText` 只剩 `String`——两处都要能命中。
     * 简单名匹配的误判面只有「用户自定义 `p.String`」这类极罕见形态，iBatis 场景可接受。
     */
    private val TEXT_TYPES = setOf("String", "CharSequence", "Character", "char", "UUID")

    /** 数值与布尔族（iBatis 走 setInt/setBoolean，SQL 里裸字面量）。 */
    private val NUMBER_TYPES = setOf(
        "byte", "short", "int", "long", "float", "double",
        "Byte", "Short", "Integer", "Long", "Float", "Double", "Number",
        "BigDecimal", "BigInteger", "boolean", "Boolean",
    )

    /** 日期时间族（`ParamHint.temporal` 让面板先试 ANSI 类型字面量）。 */
    private val TEMPORAL_TYPES = setOf(
        "Date", "Time", "Timestamp",
        "LocalDate", "LocalDateTime", "LocalTime",
        "Instant", "OffsetDateTime", "OffsetTime", "ZonedDateTime", "Year", "YearMonth",
    )

    /**
     * 属性名 → 回填提示。
     *
     * @param project        当前项目（null = 纯文本测试链，直接返回空表）
     * @param parameterClass 语句的 `parameterClass` 属性原值（可能是 FQN，也可能是短名 / 别名）
     * @param propertyNames  `#name#` 的属性名（含点路径也照传，逐段解析）
     * @param moduleName     语句所在模块名（L34：多模块工程里短名常重名，按「就近」收窄；null = 不做模块过滤）
     */
    fun resolve(
        project: Project?,
        parameterClass: String?,
        propertyNames: Collection<String>,
        moduleName: String? = null,
    ): Map<String, ParamHint> =
        runCatching { resolveStrict(project, parameterClass, propertyNames, moduleName) }.getOrDefault(emptyMap())

    /**
     * 不吞异常的版本（测试/诊断用）：解析链任一步抛错都原样传播。
     *
     * [resolve] 的 `runCatching` 是生产侧的降级保护（PSI 异常不该打断预览），但它会把
     * 「为什么没解析出来」抹成空表——诊断时走这条入口。
     */
    internal fun resolveStrict(
        project: Project?,
        parameterClass: String?,
        propertyNames: Collection<String>,
        moduleName: String? = null,
    ): Map<String, ParamHint> {
        if (project == null || parameterClass.isNullOrBlank() || propertyNames.isEmpty()) return emptyMap()
        return ReadAction.compute<Map<String, ParamHint>, RuntimeException> {
            val cls = findClass(project, parameterClass.trim(), moduleName) ?: return@compute emptyMap()
            propertyNames.distinct()
                .mapNotNull { path -> hintOfProperty(cls, path)?.let { path to it } }
                .toMap()
        }
    }

    /**
     * 类名 → PsiClass。先按全限定名找；落空再按短名查索引并收窄（[pickCandidate]）。
     *
     * 短名这条路是给 `<typeAlias alias="User" type="…"/>` 准备的：别名通常与类简单名同名，
     * 不读 config 也能命中。多模块工程里同名实体是常态（demo 项目三个 `User`），
     * 所以唯一命中之外还要按「语句所在模块」收窄，仍不唯一才弃权。
     */
    private fun findClass(project: Project, name: String, moduleName: String?): PsiClass? {
        val scope = GlobalSearchScope.allScope(project)
        JavaPsiFacade.getInstance(project).findClass(name, scope)?.let { return it }
        // T-MbgPreviewParityAndOpenPerf P6：先查 iBatis `<typeAlias>` 表——别名 → FQN 直查，
        // 零歧义也省掉下面的候选遍历（大工程里短名索引本身就可能慢）
        IbatisTypeAliasResolver.fqnOf(project, moduleName, name)
            ?.let { JavaPsiFacade.getInstance(project).findClass(it, scope) }
            ?.let { return it }
        val candidates = PsiShortNamesCache.getInstance(project).getClassesByName(name, scope).filterNotNull()
        return pickCandidate(candidates, moduleName) { cls ->
            runCatching { ModuleUtilCore.findModuleForPsiElement(cls)?.name }.getOrNull()
        }
    }

    /**
     * 短名候选收窄（纯逻辑，可单测）：唯一命中直接用；多候选时按模块「就近」——
     * 语句所在模块里恰好一个同名类才算命中，仍不唯一则弃权（贴错类型比不贴更糟）。
     */
    internal fun <T> pickCandidate(
        candidates: List<T>,
        moduleName: String?,
        moduleOf: (T) -> String?,
    ): T? = candidates.singleOrNull()
        ?: candidates.filter { moduleName != null && moduleOf(it) == moduleName }.singleOrNull()

    /** 属性路径 → 提示；任一段解析不到返回 null。 */
    private fun hintOfProperty(root: PsiClass, path: String): ParamHint? {
        var current = root
        var type: PsiType? = null
        val segments = path.split('.').filter { it.isNotEmpty() }
        for ((index, segment) in segments.withIndex()) {
            val field = current.findFieldByName(segment, true) ?: return null
            type = field.type
            if (index < segments.lastIndex) {
                current = (type as? PsiClassType)?.resolve() ?: return null
                type = null
            }
        }
        return type?.let(::hintOfType)
    }

    /**
     * PsiType → 提示（映射不到 = null，调用方落回默认 [ParamHint]）。
     *
     * 取值优先 `resolve()?.qualifiedName`，解析不到退 `canonicalText`（无 SDK 的测试环境只剩
     * 简单名）；匹配一律按**简单名**（`substringAfterLast('.')`），三类集合也是简单名。
     */
    internal fun hintOfType(type: PsiType): ParamHint? {
        val name = when (type) {
            is PsiPrimitiveType -> type.canonicalText
            is PsiClassType -> type.resolve()?.qualifiedName ?: type.canonicalText
            else -> return null
        }
        return when (name.substringAfterLast('.')) {
            in TEXT_TYPES -> ParamHint(quote = true)
            in NUMBER_TYPES -> ParamHint(quote = false)
            in TEMPORAL_TYPES -> ParamHint(quote = true, temporal = true)
            else -> null
        }
    }
}