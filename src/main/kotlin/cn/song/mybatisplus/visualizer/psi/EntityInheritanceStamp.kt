package cn.song.mybatisplus.visualizer.psi

import com.intellij.psi.PsiClass

/**
 * 父类链修改戳签名（D254 T-EntityFieldStaticHidden M5）：实体解析缓存的第二失效判据。
 *
 * 继承实体的列集合来自父类字段，但缓存原先只看实体**自身文件**的 `modificationStamp`——
 * 父类文件被修改（新增 @TableLogic、改字段类型、换 @TableField 列名）后，子类仍命中旧结果，
 * 表现为「改了父类，预览里还是老列」。
 *
 * 这里把链上每个父类所在文件的修改戳滚成一个 Long（顺序敏感、深度敏感），随缓存条目保存，
 * 命中时重算比对。内容型判据，不用 TTL 兜底（L67：TTL 内一律命中会吃掉内容变化）。
 *
 * 成本：链首（`entityClass.superClass`）为 `java.lang.Object` 或 null 时为零额外开销；
 * 有显式父类时是「链长 × 一次 containingFile 访问」，链长通常 1~2，远低于一次完整 PSI 解析。
 *
 * **待验证前提**：外部 jar / 反编译类（`PsiCompiledFile`）的 modificationStamp 恒定，
 * 因此依赖变更不会触发失效——只覆盖源码内父类，够用且不会误失效。
 */
internal fun inheritanceChainStamp(entityClass: PsiClass): Long {
    var hash = 0L
    val seen = HashSet<PsiClass>()
    var current: PsiClass? = runCatching { entityClass.superClass }.getOrNull()
    while (current != null) {
        if (!seen.add(current)) break
        // java.lang.Object 无字段且来自 JDK，戳恒定——不参与签名，避免给每个实体白加一次访问
        if (runCatching { current.qualifiedName }.getOrNull() == "java.lang.Object") break
        val stamp = runCatching { current.containingFile?.modificationStamp }.getOrNull() ?: 0L
        hash = hash * 31 + stamp
        current = runCatching { current.superClass }.getOrNull()
    }
    return hash
}
