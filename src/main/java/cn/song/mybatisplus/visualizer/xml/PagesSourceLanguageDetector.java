package cn.song.mybatisplus.visualizer.xml;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * {@code <datasource>} 语言判定（纯函数，无 PSI 依赖）。
 *
 * <p>判定顺序（首命中即定，默认 SQL）：</p>
 * <ol>
 * <li>{@code type="hql|jpql"} → HQL；{@code type="sql"} → SQL；</li>
 * <li>根标识符 == entityClass 实体名 → HQL；</li>
 * <li>根标识符 == entityClass 表名 → SQL；</li>
 * <li>根标识符解析为持久化实体且（表名 ≠ 根名 或 命中 HQL 专属记号）→ HQL；</li>
 * <li>其余 → SQL。</li>
 * </ol>
 *
 * <p>{@code rootIdentifierOf} 与 {@code probeEntity} 由调用方注入（生产分别接
 * {@code HqlEntityNameExtractor::extract} 与 PSI 实体解析），使本文件不依赖 {@code psi} 包，
 * 也让纯 JUnit 能覆盖用户样本形态。master 顶层 {@code object}，k2j 落静态承载
 * （A2 调用点已按 {@code PagesSourceLanguageDetector.detect(...)} 写死）。</p>
 */
public final class PagesSourceLanguageDetector {

    /** HQL 专属记号：只作加权，不单独定罪（SQL 里也可能出现 {@code ?1} 之外的巧合）。 */
    private static final Pattern[] HQL_ONLY_TOKENS = {
            Pattern.compile("(?i)\\bJOIN\\s+FETCH\\b"),
            Pattern.compile("(?i)\\bMEMBER\\s+OF\\b"),
            Pattern.compile("(?i)\\bnew\\s+[A-Za-z_][A-Za-z0-9_.$]*\\s*\\("),
            Pattern.compile("\\?\\d+"),
    };

    private PagesSourceLanguageDetector() {
    }

    @NotNull
    public static PagesSourceDetection detect(
            @NotNull String sql,
            @Nullable String entityClassName,
            @Nullable String declaredType,
            @NotNull Function<String, String> rootIdentifierOf,
            @Nullable Function<String, PagesEntityProbe> probeEntity) {
        String root = null;
        try { // Kotlin runCatching { ... }.getOrNull()?.takeIf { it.isNotBlank() } 语义
            String extracted = rootIdentifierOf.apply(sql);
            root = extracted != null && !extracted.isBlank() ? extracted : null;
        } catch (Throwable ignored) {
            root = null;
        }

        // 1. 显式类型（框架若有 type 属性，最权威）
        String type = declaredType != null && !declaredType.trim().isEmpty()
                ? declaredType.trim().toLowerCase(Locale.ROOT) : null;
        if ("hql".equals(type) || "jpql".equals(type)) {
            return new PagesSourceDetection(PagesSourceLanguage.HQL, PagesSourceEvidence.EXPLICIT_TYPE, root);
        }
        if ("sql".equals(type)) {
            return new PagesSourceDetection(PagesSourceLanguage.SQL, PagesSourceEvidence.EXPLICIT_SQL, root);
        }

        // 2/3. entityClass 相关性
        String entityFqcn = entityClassName != null && !entityClassName.trim().isEmpty()
                ? entityClassName.trim() : null;
        if (entityFqcn != null) {
            String simpleName = entityFqcn.substring(entityFqcn.lastIndexOf('.') + 1);
            PagesEntityProbe probe = probeQuietly(probeEntity, entityFqcn);
            String entityName = probe != null && probe.entityName() != null && !probe.entityName().isBlank()
                    ? probe.entityName() : simpleName;
            if (root != null && (root.equals(simpleName) || root.equals(entityName))) {
                return new PagesSourceDetection(PagesSourceLanguage.HQL, PagesSourceEvidence.ROOT_IS_ENTITY_CLASS, root);
            }
            String tableName = probe != null && probe.tableName() != null && !probe.tableName().isBlank()
                    ? probe.tableName() : null;
            if (root != null && tableName != null && root.equalsIgnoreCase(tableName)) {
                return new PagesSourceDetection(PagesSourceLanguage.SQL, PagesSourceEvidence.ROOT_IS_TABLE, root);
            }
        }

        // 4. 根标识符能解析为持久化实体：表名与根名不同（根名是实体名）或命中 HQL 记号
        if (root != null) {
            PagesEntityProbe probe = probeQuietly(probeEntity, root);
            if (probe != null) {
                String tableName = probe.tableName() != null && !probe.tableName().isBlank()
                        ? probe.tableName() : null;
                boolean tableDiffers = tableName == null || !root.equalsIgnoreCase(tableName);
                if (tableDiffers || hasHqlOnlyToken(sql)) {
                    return new PagesSourceDetection(PagesSourceLanguage.HQL, PagesSourceEvidence.ROOT_RESOLVES_ENTITY, root);
                }
            }
        }

        // 5. 保守默认
        return new PagesSourceDetection(PagesSourceLanguage.SQL, PagesSourceEvidence.DEFAULT_SQL, root);
    }

    /** Kotlin 默认参（{@code probeEntity = { null }}）等价重载：无探针形态（纯文本判定）。 */
    @NotNull
    public static PagesSourceDetection detect(
            @NotNull String sql,
            @Nullable String entityClassName,
            @Nullable String declaredType,
            @NotNull Function<String, String> rootIdentifierOf) {
        return detect(sql, entityClassName, declaredType, rootIdentifierOf, null);
    }

    /** 语句里是否出现 HQL 专属记号。 */
    public static boolean hasHqlOnlyToken(@NotNull String sql) {
        for (Pattern token : HQL_ONLY_TOKENS) {
            if (token.matcher(sql).find()) {
                return true;
            }
        }
        return false;
    }

    /** runCatching.getOrNull 语义：探针异常（Dumb/解析失败）一律 null（判定回落 SQL，不抛）。 */
    @Nullable
    private static PagesEntityProbe probeQuietly(
            @Nullable Function<String, PagesEntityProbe> probeEntity, @NotNull String name) {
        if (probeEntity == null) {
            return null;
        }
        try {
            return probeEntity.apply(name);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
