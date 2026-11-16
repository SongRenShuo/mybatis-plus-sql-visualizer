package cn.song.mybatisplus.visualizer.psi

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiLiteralExpression

/**
 * 各 ORM 的「类 → 表名」注解源（单一事实源）。
 *
 * 为什么要有这张表：表名反查的**快路由**（平台注解索引）必须知道去问哪些注解——只认 MP 的
 * `@TableName` 会把 JPA 工程整个漏掉（它们用 `@Table(name=…)`），而漏掉的形态在测试夹具里
 * 看不见（stub 索引对 fixture 新建文件不给结果，退化成全项目推断，用例照样绿）。
 *
 * 取值口径：**只认字面量**（`@TableName("x")` / `@Table(name = "x")`）；常量/表达式取值返回 null，
 * 由调用方决定降级（反查会走「未命中 → 全项目推断」的回补链）。
 *
 * 索引键是**注解简单名**、不是 FQN（243 字节码实证：`JavaAnnotationElementType.indexStub` 先
 * `indexOf('(')` 截断再取短类名，属性名/属性值都不进索引）——所以 [simpleNames] 里给的是短名，
 * 精确归属再由 [tableNameOf] 按「写入的名字 + 可解析时的 FQN」二次筛。
 */
internal object TableNameAnnotations {

    /** 一个注解源：写入的简单名 + 期望的 FQN + 承载表名的属性名。 */
    internal data class Source(val simpleName: String, val fqn: String, val valueAttribute: String)

    /** 受支持的注解源（顺序＝优先级：MP 放最前，工程同时装多套时以 MP 为准）。 */
    private val sources: List<Source> = listOf(
        Source("TableName", "com.baomidou.mybatisplus.annotation.TableName", "value"),
        Source("Table", "javax.persistence.Table", "name"),
        Source("Table", "jakarta.persistence.Table", "name"),
    )

    /** 注解 stub 索引要查的简单名集合（`TableName` / `Table`）。 */
    internal val simpleNames: Set<String> = sources.map { it.simpleName }.toSet()

    /**
     * 该注解属于哪个源；不属于（别家同名注解）→ null。
     *
     * 判据：写入的简单名必须命中某个源；注解能解析出**带包的** FQN 时必须等于该源的 FQN。
     * 解析不出（工程没接该 ORM 依赖 / 测试环境）时按**写入的名字**认——这正是快路径要救的场景。
     */
    internal fun sourceOf(annotation: PsiAnnotation): Source? {
        val written = annotation.nameReferenceElement?.referenceName ?: return null
        val candidates = sources.filter { it.simpleName == written }
        if (candidates.isEmpty()) return null
        val resolved = resolvedFqnOrNull(annotation)
        if (candidates.size == 1) {
            val only = candidates.single()
            return if (resolved == null || resolved == only.fqn) only else null
        }
        // 同名多源（`javax` / `jakarta` 两代 JPA）：能解析时按 FQN 定源，解析不出取第一个（行为等价）
        return candidates.firstOrNull { it.fqn == resolved } ?: if (resolved == null) candidates.first() else null
    }

    /**
     * 注解的**带包** FQN；解析不出 → null。
     *
     * 坑（2026-11-13 实测）：`PsiAnnotation.getQualifiedName()` 在**注解类解析不到**时不会返回 null，
     * 而是把引用文本原样返回——`@TableName(...)` 得到 `"TableName"`（无点）。直接拿它和 FQN 比会把
     * 所有「缺依赖」场景判成「别家注解」，快路径与回补一起静默失效。故此处按「是否带包」过滤。
     */
    private fun resolvedFqnOrNull(annotation: PsiAnnotation): String? {
        val resolved = runCatching { annotation.qualifiedName }.getOrNull() ?: return null
        return if (resolved.contains('.')) resolved else null
    }

    /** 注解里承载表名的字面量；非本表支持的注解、非字面量取值 → null。 */
    internal fun tableNameOf(annotation: PsiAnnotation): String? {
        val source = sourceOf(annotation) ?: return null
        return (annotation.findAttributeValue(source.valueAttribute) as? PsiLiteralExpression)
            ?.value as? String
    }

    /** 类级注解里的表名（按源的优先级取第一个命中的字面量）。 */
    internal fun tableNameOfClass(clazz: PsiClass): String? {
        val annotations = clazz.modifierList?.annotations ?: return null
        for (source in sources) {
            val annotation = annotations.firstOrNull { sourceOf(it) == source } ?: continue
            val value = annotation.findAttributeValue(source.valueAttribute) as? PsiLiteralExpression
            val name = value?.value as? String
            if (!name.isNullOrBlank()) return name
        }
        return null
    }
}
