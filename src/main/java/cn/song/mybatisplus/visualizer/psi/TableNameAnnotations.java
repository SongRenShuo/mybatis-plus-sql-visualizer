package cn.song.mybatisplus.visualizer.psi;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiLiteralExpression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 各 ORM 的「类 → 表名」注解源（单一事实源）。
 *
 * <p>为什么要有这张表：表名反查的<b>快路由</b>（平台注解索引）必须知道去问哪些注解——只认 MP 的
 * {@code @TableName} 会把 JPA 工程整个漏掉（它们用 {@code @Table(name=…)}），而漏掉的形态在测试
 * 夹具里看不见（stub 索引对 fixture 新建文件不给结果，退化成全项目推断，用例照样绿）。</p>
 *
 * <p>取值口径：<b>只认字面量</b>（{@code @TableName("x")} / {@code @Table(name = "x")}）；
 * 常量/表达式取值返回 null，由调用方决定降级（反查会走「未命中 → 全项目推断」的回补链）。</p>
 *
 * <p>索引键是<b>注解简单名</b>、不是 FQN（243 字节码实证：JavaAnnotationElementType.indexStub 先
 * {@code indexOf('(')} 截断再取短类名，属性名/属性值都不进索引）——所以 simpleNames 里给的是短名，
 * 精确归属再由 {@link #sourceOf} 按「写入的名字 + 可解析时的 FQN」二次筛。</p>
 */
public final class TableNameAnnotations {

    /** 一个注解源：写入的简单名 + 期望的 FQN + 承载表名的属性名。 */
    public record Source(@NotNull String simpleName, @NotNull String fqn, @NotNull String valueAttribute) {
    }

    /** 受支持的注解源（顺序＝优先级：MP 放最前，工程同时装多套时以 MP 为准）。 */
    private static final Source[] SOURCES = {
            new Source("TableName", "com.baomidou.mybatisplus.annotation.TableName", "value"),
            new Source("Table", "javax.persistence.Table", "name"),
            new Source("Table", "jakarta.persistence.Table", "name"),
    };

    /** 注解 stub 索引要查的简单名集合（TableName / Table）。 */
    public static final Set<String> SIMPLE_NAMES = buildSimpleNames();

    private static Set<String> buildSimpleNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Source source : SOURCES) {
            names.add(source.simpleName());
        }
        return Set.copyOf(names);
    }

    private TableNameAnnotations() {
    }

    private static Source[] sources() {
        return SOURCES;
    }

    /**
     * 该注解属于哪个源；不属于（别家同名注解）→ null。
     *
     * <p>判据：写入的简单名必须命中某个源；注解能解析出<b>带包的</b> FQN 时必须等于该源的 FQN。
     * 解析不出（工程没接该 ORM 依赖 / 测试环境）时按<b>写入的名字</b>认——这正是快路径要救的场景。</p>
     */
    @Nullable
    public static Source sourceOf(@NotNull PsiAnnotation annotation) {
        String written = annotation.getNameReferenceElement() != null
                ? annotation.getNameReferenceElement().getReferenceName() : null;
        if (written == null) {
            return null;
        }
        int matchCount = 0;
        Source first = null;
        Source second = null;
        for (Source source : SOURCES) {
            if (source.simpleName().equals(written)) {
                if (first == null) {
                    first = source;
                } else if (second == null) {
                    second = source;
                }
                matchCount++;
            }
        }
        if (matchCount == 0) {
            return null;
        }
        String resolved = resolvedFqnOrNull(annotation);
        if (matchCount == 1) {
            Source only = first;
            return resolved == null || resolved.equals(only.fqn()) ? only : null;
        }
        // 同名多源（javax / jakarta 两代 JPA）：能解析时按 FQN 定源，解析不出取第一个（行为等价）
        for (Source source : SOURCES) {
            if (source.simpleName().equals(written) && source.fqn().equals(resolved)) {
                return source;
            }
        }
        return resolved == null ? first : null;
    }

    /**
     * 注解的<b>带包</b> FQN；解析不出 → null。
     *
     * <p>坑（2026-11-13 实测）：{@code PsiAnnotation.getQualifiedName()} 在<b>注解类解析不到</b>时
     * 不会返回 null，而是把引用文本原样返回——{@code @TableName(...)} 得到 {@code "TableName"}
     * （无点）。直接拿它和 FQN 比会把所有「缺依赖」场景判成「别家注解」，快路径与回补一起静默失效。
     * 故此处按「是否带包」过滤。</p>
     */
    @Nullable
    private static String resolvedFqnOrNull(@NotNull PsiAnnotation annotation) {
        String resolved;
        try {
            resolved = annotation.getQualifiedName();
        } catch (RuntimeException e) {
            return null;
        }
        if (resolved == null) {
            return null;
        }
        return resolved.indexOf('.') >= 0 ? resolved : null;
    }

    /** 注解里承载表名的字面量；非本表支持的注解、非字面量取值 → null。 */
    @Nullable
    public static String tableNameOf(@NotNull PsiAnnotation annotation) {
        Source source = sourceOf(annotation);
        if (source == null) {
            return null;
        }
        if (annotation.findAttributeValue(source.valueAttribute()) instanceof PsiLiteralExpression literal) {
            return literal.getValue() instanceof String value ? value : null;
        }
        return null;
    }

    /** 类级注解里的表名（按源的优先级取第一个命中的字面量）。 */
    @Nullable
    public static String tableNameOfClass(@NotNull PsiClass clazz) {
        if (clazz.getModifierList() == null) {
            return null;
        }
        PsiAnnotation[] annotations = clazz.getModifierList().getAnnotations();
        for (Source source : sources()) {
            for (PsiAnnotation annotation : annotations) {
                if (sourceOf(annotation) == source) {
                    String name = null;
                    if (annotation.findAttributeValue(source.valueAttribute()) instanceof PsiLiteralExpression literal
                            && literal.getValue() instanceof String value) {
                        name = value;
                    }
                    if (name != null && !name.isBlank()) {
                        return name;
                    }
                }
            }
        }
        return null;
    }
}
