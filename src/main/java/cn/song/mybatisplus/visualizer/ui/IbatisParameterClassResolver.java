// T-SyncK2JMaster183 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/IbatisParameterClassResolver.kt 移植
// （Kotlin object 翻译：成员全静态——纯函数集无实例状态，调用方以对象成员形态调用，静态承载可互操作。）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.sql.ParamHint;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * T-IbatisParamClassQuote：iBatis {@code parameterClass} → 属性类型 → 回填引号提示。
 *
 * <p>iBatis 2 的 {@code #name#} 参数类型由引擎拿 {@code parameterClass} 反射决定（{@code User.username} 是 String →
 * {@code setString}），与值的字面形态无关。预览链此前唯一的类型来源是占位符自带的 jdbcType
 * （{@code cn.song.mybatisplus.visualizer.xml.ibatis.IbatisParamTypeHints}），裸 {@code #name#} 因此落到面板的
 * {@code NUMBER_REGEX} 启发式——{@code 1213} 填进 VARCHAR 列被裸渲染成 {@code = 1213}。</p>
 *
 * <p>解析口径（宁缺勿错，全部失败路径返回空表 = 维持现状）：</p>
 * <ul>
 *   <li>全限定名 → {@code JavaPsiFacade.findClass}；短名 / {@code <typeAlias>} 别名 → 短名索引，
 *       <b>唯一命中才用</b>（多候选直接弃权：贴错类型比不贴更糟）；</li>
 *   <li>属性路径 {@code a.b.c} 逐段解析字段类型（只认字段，getter-only 的 POJO 退化为不产提示）；</li>
 *   <li>类型映射与 {@code cn.song.mybatisplus.visualizer.xml.ParamKind} 六类同源：字符族引号、数值裸、
 *       时间族引号 + temporal；List / Map / 未知对象不产提示。</li>
 * </ul>
 */
public final class IbatisParameterClassResolver {

    private IbatisParameterClassResolver() {
    }

    /**
     * 字符串族（含 {@code char} 与 UUID）。
     *
     * <p>集合按<b>简单名</b>建：真实 IDE 里 {@code PsiClassType.resolve()} 能拿到 {@code java.lang.String}，
     * 但测试/无 SDK 环境下 resolve 为 null，{@code canonicalText} 只剩 {@code String}——两处都要能命中。
     * 简单名匹配的误判面只有「用户自定义 {@code p.String}」这类极罕见形态，iBatis 场景可接受。</p>
     */
    private static final Set<String> TEXT_TYPES = Set.of(
        "String", "CharSequence", "Character", "char", "UUID"
    );

    /** 数值与布尔族（iBatis 走 setInt/setBoolean，SQL 里裸字面量）。 */
    private static final Set<String> NUMBER_TYPES = Set.of(
        "byte", "short", "int", "long", "float", "double",
        "Byte", "Short", "Integer", "Long", "Float", "Double", "Number",
        "BigDecimal", "BigInteger", "boolean", "Boolean"
    );

    /** 日期时间族（{@code ParamHint.temporal} 让面板先试 ANSI 类型字面量）。 */
    private static final Set<String> TEMPORAL_TYPES = Set.of(
        "Date", "Time", "Timestamp",
        "LocalDate", "LocalDateTime", "LocalTime",
        "Instant", "OffsetDateTime", "OffsetTime", "ZonedDateTime", "Year", "YearMonth"
    );

    /**
     * 属性名 → 回填提示。
     *
     * @param project        当前项目（null = 纯文本测试链，直接返回空表）
     * @param parameterClass 语句的 {@code parameterClass} 属性原值（可能是 FQN，也可能是短名 / 别名）
     * @param propertyNames  {@code #name#} 的属性名（含点路径也照传，逐段解析）
     * @param moduleName     语句所在模块名（L34：多模块工程里短名常重名，按「就近」收窄；null = 不做模块过滤）
     */
    @NotNull
    public static Map<String, ParamHint> resolve(@Nullable Project project,
                                                 @Nullable String parameterClass,
                                                 @NotNull Collection<String> propertyNames,
                                                 @Nullable String moduleName) {
        try {
            return resolveStrict(project, parameterClass, propertyNames, moduleName);
        } catch (Throwable e) {
            return Collections.emptyMap();
        }
    }

    /** Kotlin 默认参数等价重载：{@code moduleName} 缺省为 null（不做模块过滤）。 */
    @NotNull
    public static Map<String, ParamHint> resolve(@Nullable Project project,
                                                 @Nullable String parameterClass,
                                                 @NotNull Collection<String> propertyNames) {
        return resolve(project, parameterClass, propertyNames, null);
    }

    /**
     * 不吞异常的版本（测试/诊断用）：解析链任一步抛错都原样传播。
     *
     * <p>{@link #resolve} 的 runCatching 是生产侧的降级保护（PSI 异常不该打断预览），但它会把
     * 「为什么没解析出来」抹成空表——诊断时走这条入口。</p>
     */
    @NotNull
    public static Map<String, ParamHint> resolveStrict(@Nullable Project project,
                                                       @Nullable String parameterClass,
                                                       @NotNull Collection<String> propertyNames,
                                                       @Nullable String moduleName) {
        if (project == null || parameterClass == null || parameterClass.isBlank() || propertyNames.isEmpty()) {
            return Collections.emptyMap();
        }
        return ReadAction.compute(() -> {
            PsiClass cls = findClass(project, parameterClass.trim(), moduleName);
            if (cls == null) {
                return Collections.<String, ParamHint>emptyMap();
            }
            Map<String, ParamHint> result = new LinkedHashMap<>();
            for (String path : distinctInOrder(propertyNames)) {
                ParamHint hint = hintOfProperty(cls, path);
                if (hint != null) {
                    result.put(path, hint);
                }
            }
            return result;
        });
    }

    /** Kotlin 默认参数等价重载：{@code moduleName} 缺省为 null（不做模块过滤）。 */
    @NotNull
    public static Map<String, ParamHint> resolveStrict(@Nullable Project project,
                                                       @Nullable String parameterClass,
                                                       @NotNull Collection<String> propertyNames) {
        return resolveStrict(project, parameterClass, propertyNames, null);
    }

    @NotNull
    private static List<String> distinctInOrder(@NotNull Collection<String> propertyNames) {
        return new ArrayList<>(new LinkedHashSet<>(propertyNames));
    }

    /**
     * 类名 → PsiClass。先按全限定名找；落空再按短名查索引并收窄（{@link #pickCandidate}）。
     *
     * <p>短名这条路是给 {@code <typeAlias alias="User" type="…"/>} 准备的：别名通常与类简单名同名，
     * 不读 config 也能命中。多模块工程里同名实体是常态（demo 项目三个 {@code User}），
     * 所以唯一命中之外还要按「语句所在模块」收窄，仍不唯一才弃权。</p>
     */
    @Nullable
    private static PsiClass findClass(@NotNull Project project, @NotNull String name, @Nullable String moduleName) {
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        PsiClass byFqn = JavaPsiFacade.getInstance(project).findClass(name, scope);
        if (byFqn != null) {
            return byFqn;
        }
        // T-MbgPreviewParityAndOpenPerf P6：先查 iBatis <typeAlias> 表——别名 → FQN 直查，
        // 零歧义也省掉下面的候选遍历（大工程里短名索引本身就可能慢）
        String aliasFqn = IbatisTypeAliasResolver.fqnOf(project, moduleName, name);
        if (aliasFqn != null) {
            PsiClass byAlias = JavaPsiFacade.getInstance(project).findClass(aliasFqn, scope);
            if (byAlias != null) {
                return byAlias;
            }
        }
        PsiClass[] named = PsiShortNamesCache.getInstance(project).getClassesByName(name, scope);
        List<PsiClass> candidates = new ArrayList<>();
        for (PsiClass candidate : named) {
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        return pickCandidate(candidates, moduleName, cls -> {
            try {
                Module module = ModuleUtilCore.findModuleForPsiElement(cls);
                return module != null ? module.getName() : null;
            } catch (Throwable e) {
                return null;
            }
        });
    }

    /**
     * 短名候选收窄（纯逻辑，可单测）：唯一命中直接用；多候选时按模块「就近」——
     * 语句所在模块里恰好一个同名类才算命中，仍不唯一则弃权（贴错类型比不贴更糟）。
     */
    @Nullable
    public static <T> T pickCandidate(@NotNull List<T> candidates,
                                      @Nullable String moduleName,
                                      @NotNull Function<T, String> moduleOf) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (moduleName == null) {
            return null;
        }
        T unique = null;
        for (T candidate : candidates) {
            if (moduleName.equals(moduleOf.apply(candidate))) {
                if (unique != null) {
                    return null;
                }
                unique = candidate;
            }
        }
        return unique;
    }

    /** 属性路径 → 提示；任一段解析不到返回 null。 */
    @Nullable
    private static ParamHint hintOfProperty(@NotNull PsiClass root, @NotNull String path) {
        PsiClass current = root;
        PsiType type = null;
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("\\.")) {
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
        }
        for (int index = 0; index < segments.size(); index++) {
            PsiField field = current.findFieldByName(segments.get(index), true);
            if (field == null) {
                return null;
            }
            type = field.getType();
            if (index < segments.size() - 1) {
                if (!(type instanceof PsiClassType classType)) {
                    return null;
                }
                PsiClass resolved = classType.resolve();
                if (resolved == null) {
                    return null;
                }
                current = resolved;
                type = null;
            }
        }
        return type != null ? hintOfType(type) : null;
    }

    /**
     * PsiType → 提示（映射不到 = null，调用方落回默认 {@link ParamHint}）。
     *
     * <p>取值优先 {@code resolve()?.qualifiedName}，解析不到退 {@code canonicalText}（无 SDK 的测试环境只剩
     * 简单名）；匹配一律按<b>简单名</b>（{@code substringAfterLast('.')}），三类集合也是简单名。</p>
     */
    @Nullable
    public static ParamHint hintOfType(@NotNull PsiType type) {
        String name;
        if (type instanceof PsiPrimitiveType primitive) {
            name = primitive.getCanonicalText();
        } else if (type instanceof PsiClassType classType) {
            PsiClass resolved = classType.resolve();
            name = resolved != null && resolved.getQualifiedName() != null
                ? resolved.getQualifiedName()
                : classType.getCanonicalText();
        } else {
            return null;
        }
        String simpleName = simpleNameOf(name);
        if (TEXT_TYPES.contains(simpleName)) {
            return new ParamHint(Boolean.TRUE);
        }
        if (NUMBER_TYPES.contains(simpleName)) {
            return new ParamHint(Boolean.FALSE);
        }
        if (TEMPORAL_TYPES.contains(simpleName)) {
            return new ParamHint(Boolean.TRUE, null, false, true);
        }
        return null;
    }

    /** Kotlin {@code substringAfterLast('.')} 等义。 */
    @NotNull
    private static String simpleNameOf(@NotNull String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }
}
