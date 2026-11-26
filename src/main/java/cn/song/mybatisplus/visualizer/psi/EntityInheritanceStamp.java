package cn.song.mybatisplus.visualizer.psi;

import com.intellij.psi.PsiClass;

import java.util.HashSet;
import java.util.Set;

/**
 * 父类链修改戳签名（D254 T-EntityFieldStaticHidden M5）：实体解析缓存的第二失效判据。
 *
 * <p>继承实体的列集合来自父类字段，但缓存原先只看实体<b>自身文件</b>的 {@code modificationStamp}——
 * 父类文件被修改（新增 {@code @TableLogic}、改字段类型、换 {@code @TableField} 列名）后，
 * 子类仍命中旧结果，表现为「改了父类，预览里还是老列」。</p>
 *
 * <p>这里把链上每个父类所在文件的修改戳滚成一个 {@code long}（顺序敏感、深度敏感），随缓存条目保存，
 * 命中时重算比对。内容型判据，不用 TTL 兜底（L67：TTL 内一律命中会吃掉内容变化）。</p>
 *
 * <p>成本：链首（{@code entityClass.superClass}）为 {@code java.lang.Object} 或 null 时为零额外开销；
 * 有显式父类时是「链长 × 一次 containingFile 访问」，链长通常 1~2，远低于一次完整 PSI 解析。</p>
 *
 * <p><b>待验证前提</b>：外部 jar / 反编译类（{@code PsiCompiledFile}）的 modificationStamp 恒定，
 * 因此依赖变更不会触发失效——只覆盖源码内父类，够用且不会误失效。</p>
 *
 * <p>k2j 移植注：master 是 {@code EntityInheritanceStamp.kt} 内的顶层 {@code internal fun
 * inheritanceChainStamp}（编译后门面名为 {@code EntityInheritanceStampKt}）。k2j 生产树 100% Java，
 * 且全仓无一处以 Kotlin 顶层函数形态调用它（唯一消费方是本轮的 {@link EntityResolver} /
 * {@code JpaEntityResolver}，均为 Java 直接静态调用），故按写集给定的同名件
 * {@code EntityInheritanceStamp.java} 承载、方法为 {@code public static}——
 * 不新增 {@code XxxKt.java} 门面，避免与「既有门面不重命名」的结构性保护混淆。</p>
 */
public final class EntityInheritanceStamp {

    private EntityInheritanceStamp() {
    }

    /** 父类链修改戳签名（链上每个父类所在文件 modificationStamp 的 31 进制滚动哈希）。 */
    public static long inheritanceChainStamp(PsiClass entityClass) {
        long hash = 0L;
        Set<PsiClass> seen = new HashSet<>();
        PsiClass current = superClassOf(entityClass);
        while (current != null) {
            if (!seen.add(current)) break;
            // java.lang.Object 无字段且来自 JDK，戳恒定——不参与签名，避免给每个实体白加一次访问
            if ("java.lang.Object".equals(qualifiedNameOf(current))) break;
            long stamp = containingFileStamp(current);
            hash = hash * 31 + stamp;
            current = superClassOf(current);
        }
        return hash;
    }

    private static PsiClass superClassOf(PsiClass clazz) {
        try {
            return clazz.getSuperClass();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String qualifiedNameOf(PsiClass clazz) {
        try {
            return clazz.getQualifiedName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 所在文件修改戳；containingFile 为 null（反编译/未索引）按 0 计——与 master 的 {@code ?: 0L} 同口径。 */
    private static long containingFileStamp(PsiClass clazz) {
        try {
            return clazz.getContainingFile() != null ? clazz.getContainingFile().getModificationStamp() : 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }
}
