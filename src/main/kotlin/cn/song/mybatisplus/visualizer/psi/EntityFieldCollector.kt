package cn.song.mybatisplus.visualizer.psi

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifier

/**
 * 实体字段收集（D254 T-EntityFieldStaticHidden）：把「继承链字段 + 同名合并 + 可用性过滤」
 * 收成一条规则，供实体列映射与参数属性访问两个语义面共用。
 *
 * **为什么不能直接用平台 `allFields`**：反编译平台 `PsiClassImplUtil$MemberCache` 实证
 * `allFields` 由 `myAllSupers.flatArray(type::getMembers)` 直接拼接——既不按名去重、
 * 也不过静态；父类字段与子类同名字段会各出现一次（子类在前，父类在后）。
 * 各调用点此前靠 `.distinct()`（名字）或后写覆盖来兜，方向与真机不一致。
 *
 * **两个语义面（口径分叉是刻意的，不要合并）**：
 *  - [collectColumns]：实体 → 表列（MyBatis-Plus / JPA）。排除 `static` 与 `transient`。
 *  - [collectProperties]：参数取值（MyBatis OGNL/MetaObject）。**保留 static 常量**，只排除
 *    真机取不到的名字（`serialVersionUID` / `class` / `$` 前缀）。
 *
 * **同名合并方向**：子类优先（最具体声明占位），父类同名字段被排除。两轨语义依据：
 *  - MyBatis-Plus `ReflectionKit.getFieldList`：`excludeOverrideSuperField` 先以子类字段
 *    put 进 LinkedHashMap，父类同名字段 `!containsKey` 才补；随后才对 values 过滤
 *    `Modifier.isStatic` / `Modifier.isTransient`。
 *  - MyBatis `Reflector.addFields`：本类字段先注册，父类同名 `!getMethods.containsKey` 才补。
 *  - Hibernate：父类字段先 put、子类同名 put 覆盖（保留父类位置）——与下面的输出顺序一致。
 *
 * **顺序是判据的一部分**（两条真机语义不可拆开抄）：
 *  1. 先去重、后过滤静态。子类 `static` 字段与父类实例字段同名时，该名字**整体不产出列**
 *     （子类已占位，父类同名被排除，随后 static 被过滤）。把过滤提前会多出一列，与 MP 真机不符。
 *  2. 输出顺序保持「父类在前、子类在后」——本仓 T-M16.2-02 / T-M23.3 既有顺序契约；
 *     同名冲突时输出的是**子类**字段对象、占用父类槽位，因此无重名场景的顺序与改动前逐项相同。
 */
object EntityFieldCollector {

    /**
     * 实体列语义（MyBatis-Plus / JPA 映射）：同名合并（子类优先）+ 排除 `static` / `transient`。
     *
     * @param clazz 实体类（父类链沿 `superClass` 递归；接口字段不纳入，沿用既有语义）
     * @return 参与列映射的字段，顺序为「父类在前、子类在后」
     */
    fun collectColumns(clazz: PsiClass): List<PsiField> =
        collect(clazz) { field -> !isStaticOrTransient(field) }

    /**
     * 参数属性语义（MyBatis 参数取值）：同名合并（子类优先）+ 仅排除真机取不到的名字。
     *
     * `static` 常量**保留**——`Reflector.addFields` 对 static 字段仍注册 getter（static final
     * 只是不生成 setter），`#{user.SOME_CONST}` 在真机可取值；`serialVersionUID` / `class` /
     * `$` 前缀三类由 `Reflector.isValidPropertyName` 排除，真机取不到，故这里同样不产出键。
     */
    fun collectProperties(clazz: PsiClass): List<PsiField> =
        collect(clazz) { field -> isReadablePropertyName(field.name) }

    private inline fun collect(clazz: PsiClass, keep: (PsiField) -> Boolean): List<PsiField> {
        // 1) 继承链：本类 → 直接父类 → …（seen 防异常 PSI 的循环继承）
        val chain = ArrayList<PsiClass>(4)
        val seen = HashSet<PsiClass>()
        var current: PsiClass? = clazz
        while (current != null) {
            if (!seen.add(current)) break
            chain += current
            current = current.superClass
        }
        // 2) 子类先占位：父类同名字段被排除（MP excludeOverrideSuperField / MyBatis Reflector 同向）
        val declaredByMostSpecific = LinkedHashMap<String, PsiField>()
        for (type in chain) {
            for (field in type.fields) {
                val name = field.name ?: continue
                if (!declaredByMostSpecific.containsKey(name)) declaredByMostSpecific[name] = field
            }
        }
        // 3) 输出：父先子后，只输出「名字归属最具体声明」的那个对象；过滤在去重之后（顺序语义见类 KDoc）
        val result = ArrayList<PsiField>(declaredByMostSpecific.size)
        for (type in chain.asReversed()) {
            for (field in type.fields) {
                val name = field.name ?: continue
                if (declaredByMostSpecific[name] === field && keep(field)) result += field
            }
        }
        return result
    }

    /** `static` / `transient`：非持久化修饰符（MP `getFieldList` 两条过滤；Hibernate 亦不持久化 transient）。 */
    private fun isStaticOrTransient(field: PsiField): Boolean = runCatching {
        field.hasModifierProperty(PsiModifier.STATIC) || field.hasModifierProperty(PsiModifier.TRANSIENT)
    }.getOrDefault(false)

    /**
     * MyBatis `Reflector.isValidPropertyName` 同口径：`$` 前缀（编译器合成）、`serialVersionUID`、
     * `class` 三类不产出属性键。
     */
    private fun isReadablePropertyName(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        return !name.startsWith("$") && name != "serialVersionUID" && name != "class"
    }
}
