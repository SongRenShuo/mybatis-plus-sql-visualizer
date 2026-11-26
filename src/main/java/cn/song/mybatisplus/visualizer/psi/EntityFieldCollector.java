package cn.song.mybatisplus.visualizer.psi;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiModifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 实体字段收集（D254 T-EntityFieldStaticHidden）：把「继承链字段 + 同名合并 + 可用性过滤」
 * 收成一条规则，供实体列映射与参数属性访问两个语义面共用。
 *
 * <p><b>为什么不能直接用平台 {@code allFields}</b>：反编译平台 {@code PsiClassImplUtil$MemberCache} 实证
 * {@code allFields} 由 {@code myAllSupers.flatArray(type::getMembers)} 直接拼接——既不按名去重、
 * 也不过静态；父类字段与子类同名字段会各出现一次（子类在前，父类在后）。
 * 各调用点此前靠 {@code .distinct()}（名字）或后写覆盖来兜，方向与真机不一致。</p>
 *
 * <p><b>两个语义面（口径分叉是刻意的，不要合并）</b>：</p>
 * <ul>
 *   <li>{@link #collectColumns}：实体 → 表列（MyBatis-Plus / JPA）。排除 {@code static} 与 {@code transient}。</li>
 *   <li>{@link #collectProperties}：参数取值（MyBatis OGNL/MetaObject）。<b>保留 static 常量</b>，只排除
 *       真机取不到的名字（{@code serialVersionUID} / {@code class} / {@code $} 前缀）。</li>
 * </ul>
 *
 * <p><b>同名合并方向</b>：子类优先（最具体声明占位），父类同名字段被排除。两轨语义依据：</p>
 * <ul>
 *   <li>MyBatis-Plus {@code ReflectionKit.getFieldList}：{@code excludeOverrideSuperField} 先以子类字段
 *       put 进 LinkedHashMap，父类同名字段 {@code !containsKey} 才补；随后才对 values 过滤
 *       {@code Modifier.isStatic} / {@code Modifier.isTransient}。</li>
 *   <li>MyBatis {@code Reflector.addFields}：本类字段先注册，父类同名 {@code !getMethods.containsKey} 才补。</li>
 *   <li>Hibernate：父类字段先 put、子类同名 put 覆盖（保留父类位置）——与下面的输出顺序一致。</li>
 * </ul>
 *
 * <p><b>顺序是判据的一部分</b>（两条真机语义不可拆开抄）：</p>
 * <ol>
 *   <li>先去重、后过滤静态。子类 {@code static} 字段与父类实例字段同名时，该名字<b>整体不产出列</b>
 *       （子类已占位，父类同名被排除，随后 static 被过滤）。把过滤提前会多出一列，与 MP 真机不符。</li>
 *   <li>输出顺序保持「父类在前、子类在后」——本仓 T-M16.2-02 / T-M23.3 既有顺序契约；
 *       同名冲突时输出的是<b>子类</b>字段对象、占用父类槽位，因此无重名场景的顺序与改动前逐项相同。</li>
 * </ol>
 *
 * <p>原 Kotlin {@code object EntityFieldCollector}：Java 端口按无状态静态工具承载
 * （与 {@link TableNameAnnotations} 同形态）。Kotlin 与 Java 调用方都写
 * {@code EntityFieldCollector.collectColumns(clazz)}。</p>
 */
public final class EntityFieldCollector {

    private EntityFieldCollector() {
    }

    /**
     * 实体列语义（MyBatis-Plus / JPA 映射）：同名合并（子类优先）+ 排除 {@code static} / {@code transient}。
     *
     * @param clazz 实体类（父类链沿 {@code superClass} 递归；接口字段不纳入，沿用既有语义）
     * @return 参与列映射的字段，顺序为「父类在前、子类在后」
     */
    public static List<PsiField> collectColumns(PsiClass clazz) {
        return collect(clazz, false);
    }

    /**
     * 参数属性语义（MyBatis 参数取值）：同名合并（子类优先）+ 仅排除真机取不到的名字。
     *
     * <p>{@code static} 常量<b>保留</b>——{@code Reflector.addFields} 对 static 字段仍注册 getter
     * （static final 只是不生成 setter），{@code #{user.SOME_CONST}} 在真机可取值；
     * {@code serialVersionUID} / {@code class} / {@code $} 前缀三类由 {@code Reflector.isValidPropertyName}
     * 排除，真机取不到，故这里同样不产出键。</p>
     */
    public static List<PsiField> collectProperties(PsiClass clazz) {
        return collect(clazz, true);
    }

    /**
     * 收集主体（原 Kotlin 私有 inline fun 带 {@code keep} 谓词；Java 侧落 boolean 语义位——
     * 谓词只有「列语义 / 属性语义」两态，与 {@link #collectColumns}/{@link #collectProperties} 一一对应）。
     *
     * @param propertySemantics false = 列语义（排除 static/transient）；true = 属性语义（只排除不可读名字）
     */
    private static List<PsiField> collect(PsiClass clazz, boolean propertySemantics) {
        // 1) 继承链：本类 → 直接父类 → …（seen 防异常 PSI 的循环继承）
        List<PsiClass> chain = new ArrayList<>(4);
        Set<PsiClass> seen = new HashSet<>();
        PsiClass current = clazz;
        while (current != null) {
            if (!seen.add(current)) break;
            chain.add(current);
            current = current.getSuperClass();
        }
        // 2) 子类先占位：父类同名字段被排除（MP excludeOverrideSuperField / MyBatis Reflector 同向）
        Map<String, PsiField> declaredByMostSpecific = new LinkedHashMap<>();
        for (PsiClass type : chain) {
            for (PsiField field : type.getFields()) {
                String name = field.getName();
                if (name == null) continue;
                if (!declaredByMostSpecific.containsKey(name)) declaredByMostSpecific.put(name, field);
            }
        }
        // 3) 输出：父先子后，只输出「名字归属最具体声明」的那个对象；过滤在去重之后（顺序语义见类 KDoc）
        List<PsiField> result = new ArrayList<>(declaredByMostSpecific.size());
        for (int i = chain.size() - 1; i >= 0; i--) {
            for (PsiField field : chain.get(i).getFields()) {
                String name = field.getName();
                if (name == null) continue;
                if (declaredByMostSpecific.get(name) != field) continue;
                boolean keep = propertySemantics ? isReadablePropertyName(name) : !isStaticOrTransient(field);
                if (keep) result.add(field);
            }
        }
        return result;
    }

    /** {@code static} / {@code transient}：非持久化修饰符（MP {@code getFieldList} 两条过滤；Hibernate 亦不持久化 transient）。 */
    private static boolean isStaticOrTransient(PsiField field) {
        try {
            return field.hasModifierProperty(PsiModifier.STATIC) || field.hasModifierProperty(PsiModifier.TRANSIENT);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * MyBatis {@code Reflector.isValidPropertyName} 同口径：{@code $} 前缀（编译器合成）、
     * {@code serialVersionUID}、{@code class} 三类不产出属性键。
     */
    private static boolean isReadablePropertyName(String name) {
        if (name == null || name.isEmpty()) return false;
        return !name.startsWith("$") && !"serialVersionUID".equals(name) && !"class".equals(name);
    }
}
