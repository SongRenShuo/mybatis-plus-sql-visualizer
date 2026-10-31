package cn.song.mybatisplus.visualizer.inspection

/**
 * 禁用 API 匹配核心（纯逻辑，零平台依赖）：规则表 + 实际调用点（类全限定名 / 方法名）→ 命中规则。
 *
 * 抽成独立对象的目的：`LocalInspectionTool` 与全项目存量扫描共用同一判据，且可用普通 JUnit
 * 直测（inspection 类本身只做 PSI 取数与 report 薄壳）。见 [BannedApiInspection] /
 * [cn.song.mybatisplus.visualizer.actions.ScanBannedApiUsagesAction]。
 *
 * ## 匹配语义
 *
 *  - **类**：[BannedApiRule.classRegex] 为 false 时要求 `className` **等于**实际 FQN；
 *    为 true 时按正则**整串匹配**（`Regex.matches`）。点号在正则里有「任意字符」语义，
 *    故正则规则里写包分隔点须转义（`com\.acme\..*`）——精确模式不需要；
 *  - **方法**：`methodName` 非空时按正则**整串匹配**；空串 = 任意方法；
 *  - **首条命中即返回**：规则按管理页顺序优先级解释（先加的规则先赢），行为可预期；
 *  - **非法正则 = 不命中**：手改 XML 写坏正则时规则静默失效，绝不退化成「匹配一切」
 *    （管理页保存前经 [validate] 拦截，正常路径不会产生非法规则）；
 *  - **只认声明类**：命中判定用 `PsiMethod.containingClass.qualifiedName`（方法实际声明在哪个类），
 *    不做父类/接口回溯——子类未覆写时调用点的实际方法就是父类那份，规则写父类即可命中。
 */
object BannedApiMatcher {

    /** 规则校验失败原因（UI 层再映射成文案键）。 */
    enum class ValidationError {
        /** 类名/正则为空。 */
        CLASS_NAME_BLANK,

        /** 说明为空（必填——提示与报告都要引用它）。 */
        DESCRIPTION_BLANK,

        /** 正则模式下类匹配串不是合法正则。 */
        INVALID_CLASS_REGEX,

        /** 方法名不是合法正则。 */
        INVALID_METHOD_REGEX,

        /** 级别不在 [BannedApiRule.LEVELS] 内。 */
        INVALID_LEVEL,
    }

    /**
     * 校验规则是否可保存/可参与匹配。
     *
     * @return null = 合法；否则为第一处问题（顺序：类名 → 说明 → 级别 → 类正则 → 方法正则）
     */
    fun validate(rule: BannedApiRule): ValidationError? {
        if (rule.className.isBlank()) return ValidationError.CLASS_NAME_BLANK
        if (rule.description.isBlank()) return ValidationError.DESCRIPTION_BLANK
        if (rule.level !in BannedApiRule.LEVELS) return ValidationError.INVALID_LEVEL
        if (rule.classRegex && compileOrNull(rule.className.trim()) == null) {
            return ValidationError.INVALID_CLASS_REGEX
        }
        if (rule.methodName.isNotBlank() && compileOrNull(rule.methodName.trim()) == null) {
            return ValidationError.INVALID_METHOD_REGEX
        }
        return null
    }

    /** 单次匹配（便捷入口；批量场景请先 [compile] 复用编译结果）。 */
    fun match(classFqn: String?, methodName: String?, rules: List<BannedApiRule>): BannedApiRule? =
        compile(rules).match(classFqn, methodName)

    /** 预编译规则表（inspection 每次 buildVisitor / 扫描动作每个文件复用一份）。 */
    fun compile(rules: List<BannedApiRule>): CompiledRules {
        val entries = ArrayList<CompiledRules.Entry>(rules.size)
        for (rule in rules) {
            val cls = rule.className.trim()
            if (cls.isEmpty()) continue
            val classRegex = if (rule.classRegex) compileOrNull(cls) ?: continue else null
            val methodRegex = if (rule.methodName.isBlank()) null
            else compileOrNull(rule.methodName.trim()) ?: continue
            entries += CompiledRules.Entry(
                rule = rule,
                exactClass = if (rule.classRegex) null else cls,
                classRegex = classRegex,
                methodRegex = methodRegex,
            )
        }
        return CompiledRules(entries)
    }

    private fun compileOrNull(pattern: String): Regex? = runCatching { Regex(pattern) }.getOrNull()
}

/**
 * 预编译后的规则集合（不可变，可跨线程读）。
 *
 * inspection 在每次 `buildVisitor` 里取一份快照；全项目扫描在扫描开始时取一份——
 * 扫描期间规则变更不影响本轮判据（避免同一份报告混用两套规则）。
 */
class CompiledRules internal constructor(internal val entries: List<Entry>) {

    internal class Entry(
        val rule: BannedApiRule,
        /** 精确模式下的目标 FQN；正则模式为 null。 */
        val exactClass: String?,
        /** 正则模式下的类匹配器；精确模式为 null。 */
        val classRegex: Regex?,
        /** 方法名匹配器；null = 任意方法。 */
        val methodRegex: Regex?,
    ) {
        fun matches(classFqn: String, methodName: String): Boolean {
            val classHit = if (exactClass != null) exactClass == classFqn
            else classRegex?.matches(classFqn) ?: false
            if (!classHit) return false
            return methodRegex == null || methodRegex.matches(methodName)
        }
    }

    /** 规则表是否为空（空表时 inspection 直接返回空 visitor，零行为）。 */
    val isEmpty: Boolean get() = entries.isEmpty()

    /** 方法名是否**可能**命中任一规则——纯字符串判据，用来在 resolve 之前廉价短路。 */
    fun mayMatchMethodName(methodName: String?): Boolean {
        if (methodName == null) return false
        return entries.any { it.methodRegex == null || it.methodRegex.matches(methodName) }
    }

    /**
     * 匹配实际调用点。
     *
     * @param classFqn   方法声明类的全限定名（解析不出时为 null）
     * @param methodName 方法名（解析不出时为 null）
     * @return 首条命中规则；无命中返回 null
     */
    fun match(classFqn: String?, methodName: String?): BannedApiRule? {
        if (classFqn.isNullOrBlank() || methodName == null) return null
        return entries.firstOrNull { it.matches(classFqn, methodName) }?.rule
    }
}
