// T-SyncK2JMaster104 波C 翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/highlight/MybatisConfigCompletionContributor.kt
package cn.song.mybatisplus.visualizer.ui.highlight;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.completion.PrefixMatcher;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.project.DumbAware;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.patterns.PsiElementPattern;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T-XmlGap W6：mybatis-config.xml（配置文件）内的补全。
 *
 * <p>与 mapper XML 的分工：XmlSqlCompletionContributor 的根守卫是「最近语句标签 + 根 == mapper」，
 * 配置文件整类落在它之外（原状是零候选）。本类<b>独立成文件</b>，根守卫为「根标签 == configuration」。</p>
 *
 * <p>硬编码表来源（<b>全部实读 MyBatis 3.5.19 jar，禁凭记忆</b>）：</p>
 * <ul>
 *   <li>29 个 {@code <setting name>} 键：{@code org.apache.ibatis.builder.xml.XMLConfigBuilder} 常量池中带
 *       {@code setXxx} 对应者的字面量（javap -p -c / 常量池扫描实测；{@code multipleResultSetsEnabled}
 *       在该版<b>已不存在</b>，故不入表）；</li>
 *   <li>布尔型 13 项、枚举型 6 项的值集：javap 实测 {@code ExecutorType}/{@code AutoMappingBehavior}/
 *       {@code AutoMappingUnknownColumnBehavior}/{@code LocalCacheScope}/{@code ResultSetType} 的常量；</li>
 *   <li>{@code jdbcTypeForNull} 的 41 个候选：{@code org.apache.ibatis.type.JdbcType} 常量实测。</li>
 * </ul>
 *
 * <p>形态对齐既有约定：CompletionContributor + DumbAware（T-XmlCmpl2 教训：缺该接口会在索引期
 * 被平台整体丢弃）；所有候选经 prefixMatcher 过滤；零索引依赖（纯静态表 + 同标签属性读取）。</p>
 */
public class MybatisConfigCompletionContributor extends CompletionContributor implements DumbAware {

    /**
     * T-PerfSweep26 F101：补全位置模式——限定 XML 语言元素。
     *
     * <p>原为无约束 {@code PlatformPatterns.psiElement()}：全 IDE 任意语言每次补全调用都进 provider 体，
     * 靠首行 {@code instanceof XmlFile} O(1) 早退兜底。{@code withLanguage} 是 [PsiElementPattern] 上最小可用的
     * 语言约束（243 sources jar {@code PsiElementPattern.java:268}，accepts 即 {@code t.getLanguage().equals(language)}）；
     * mybatis-config.xml 是标准 XML 语言，文件内任意补全位（XmlToken 等）语言均为 XMLLanguage，
     * 约束后行为不变；注入片段（如 mapper.xml 里的 SQL）语言非 XML，本就不该进本贡献器。
     */
    static final PsiElementPattern.Capture<PsiElement> completionPositionPattern =
            PlatformPatterns.psiElement().withLanguage(XMLLanguage.INSTANCE);

    public MybatisConfigCompletionContributor() {
        extend(CompletionType.BASIC, completionPositionPattern, new CompletionProvider<CompletionParameters>() {
            @Override
            protected void addCompletions(CompletionParameters parameters, ProcessingContext context,
                                          CompletionResultSet result) {
                PsiElement position = parameters.getPosition();
                if (!(position.getContainingFile() instanceof XmlFile)) return;
                XmlFile file = (XmlFile) position.getContainingFile();
                if (file.getRootTag() == null || !file.getRootTag().getName().equals("configuration")) return;
                PrefixMatcher prefix = result.getPrefixMatcher();

                // 1. 属性值位
                XmlAttributeValue value = PsiTreeUtil.getParentOfType(position, XmlAttributeValue.class, false);
                if (value != null) {
                    if (!(value.getParent() instanceof XmlAttribute)) return;
                    XmlAttribute attr = (XmlAttribute) value.getParent();
                    if (!(attr.getParent() instanceof XmlTag)) return;
                    XmlTag tag = (XmlTag) attr.getParent();
                    if (tag.getName().equals("setting") && attr.getName().equals("name")) {
                        addCandidates(result, prefix, SETTING_NAMES);
                    } else if (tag.getName().equals("setting") && attr.getName().equals("value")) {
                        String settingName = tag.getAttributeValue("name");
                        addCandidates(result, prefix, settingValueCandidates(settingName != null ? settingName : ""));
                    }
                    // property name = 用户自定义键，无候选；类名属性（type/class/interceptor/typeHandler）
                    // 暂无有界候选来源 → 不产噪音
                    return;
                }

                // 2. 标签名位（光标前 `<`）：按父标签给子标签名
                XmlTag tag = PsiTreeUtil.getParentOfType(position, XmlTag.class);
                if (tag == null) return;
                String parentName = position.getParent() instanceof XmlTag ? null : tag.getName();
                List<String> siblingNames = configChildTags(parentName != null ? parentName : tag.getName());
                if (!siblingNames.isEmpty()) {
                    addCandidates(result, prefix, siblingNames);
                } else {
                    addCandidates(result, prefix, configChildTags(
                            tag.getParentTag() != null ? tag.getParentTag().getName() : null));
                }
            }
        });
    }

    private static void addCandidates(CompletionResultSet result, PrefixMatcher prefix, List<String> names) {
        for (String name : names) {
            if (prefix.prefixMatches(name)) result.addElement(LookupElementBuilder.create(name));
        }
    }

    /** {@code <setting value>} 的候选：布尔表 / 枚举表 / jdbcType 表；未知键返回空。 */
    public List<String> settingValueCandidates(String settingName) {
        if (SETTING_BOOLEANS.contains(settingName)) return BOOLEAN_VALUES;
        List<String> enumValues = SETTING_ENUMS.get(settingName);
        if (enumValues != null) return enumValues;
        if (settingName.equals("jdbcTypeForNull")) return JDBC_TYPE_NAMES;
        return List.of();
    }

    /** 类名属性（{@code type}/{@code class}/{@code interceptor} 等）暂无有界候选来源，返回空（不产噪音）。 */
    public List<String> configChildTags(@Nullable String parentTagName) {
        List<String> tags = CONFIG_CHILD_TAGS.get(parentTagName);
        return tags != null ? tags : List.of();
    }

    /** 29 个 {@code <setting name>} 键（MyBatis 3.5.19 实测，见类 KDoc）。 */
    public static final List<String> SETTING_NAMES = List.of(
            "cacheEnabled", "lazyLoadingEnabled", "aggressiveLazyLoading", "useColumnLabel",
            "useGeneratedKeys", "autoMappingBehavior", "autoMappingUnknownColumnBehavior",
            "defaultExecutorType", "defaultStatementTimeout", "defaultFetchSize", "defaultResultSetType",
            "safeResultHandlerEnabled", "mapUnderscoreToCamelCase", "localCacheScope", "jdbcTypeForNull",
            "lazyLoadTriggerMethods", "defaultScriptingLanguage", "defaultEnumTypeHandler",
            "callSettersOnNulls", "useActualParamName", "returnInstanceForEmptyRow", "logPrefix",
            "logImpl", "vfsImpl", "configurationFactory", "shrinkWhitespacesInSql",
            "defaultSqlProviderType", "nullableOnForEach", "safeRowBoundsEnabled");

    /** 布尔型设置（值候选 true/false）。 */
    public static final Set<String> SETTING_BOOLEANS = Set.of(
            "cacheEnabled", "lazyLoadingEnabled", "aggressiveLazyLoading", "useColumnLabel",
            "useGeneratedKeys", "safeResultHandlerEnabled", "mapUnderscoreToCamelCase",
            "callSettersOnNulls", "useActualParamName", "returnInstanceForEmptyRow",
            "shrinkWhitespacesInSql", "nullableOnForEach", "safeRowBoundsEnabled");

    private static final List<String> BOOLEAN_VALUES = List.of("true", "false");

    /** 枚举型设置的值候选（jar 内枚举常量实测）。 */
    public static final Map<String, List<String>> SETTING_ENUMS = Map.of(
            "autoMappingBehavior", List.of("NONE", "PARTIAL", "FULL"),
            "autoMappingUnknownColumnBehavior", List.of("NONE", "WARNING", "FAILING"),
            "defaultExecutorType", List.of("SIMPLE", "REUSE", "BATCH"),
            "defaultResultSetType", List.of("DEFAULT", "FORWARD_ONLY", "SCROLL_INSENSITIVE", "SCROLL_SENSITIVE"),
            "localCacheScope", List.of("SESSION", "STATEMENT"));

    /** {@code jdbcTypeForNull}（以及 W4 的 {@code #{}} jdbcType 值表）——{@code org.apache.ibatis.type.JdbcType} 41 常量实测。 */
    public static final List<String> JDBC_TYPE_NAMES = List.of(
            "ARRAY", "BIT", "TINYINT", "SMALLINT", "INTEGER", "BIGINT", "FLOAT", "REAL", "DOUBLE",
            "NUMERIC", "DECIMAL", "CHAR", "VARCHAR", "LONGVARCHAR", "DATE", "TIME", "TIMESTAMP",
            "BINARY", "VARBINARY", "LONGVARBINARY", "NULL", "OTHER", "BLOB", "CLOB", "BOOLEAN",
            "CURSOR", "UNDEFINED", "NVARCHAR", "NCHAR", "NCLOB", "STRUCT", "JAVA_OBJECT", "DISTINCT",
            "REF", "DATALINK", "ROWID", "LONGNVARCHAR", "SQLXML", "DATETIMEOFFSET",
            "TIME_WITH_TIMEZONE", "TIMESTAMP_WITH_TIMEZONE");

    @SuppressWarnings("unused")
    private static final Set<String> CLASS_ATTRIBUTES = Set.of("type", "class", "interceptor", "typeHandler");

    /** 根标签下一层的子标签（<b>必须先于 CONFIG_CHILD_TAGS 声明</b>——静态字段按声明序初始化）。 */
    private static final List<String> CONFIG_ROOT_CHILDREN = List.of(
            "properties", "settings", "typeAliases", "typeHandlers", "objectFactory",
            "objectWrapperFactory", "reflectorFactory", "plugins", "environments",
            "databaseIdProvider", "mappers");

    /**
     * 父子标签表：按父标签名给子标签名候选（<b>null 键 = 根下一层</b>——HashMap 承载 null 键，
     * Kotlin 的 {@code Map<String?, ...>} 同形）。
     */
    public static final Map<String, List<String>> CONFIG_CHILD_TAGS = buildConfigChildTags();

    private static Map<String, List<String>> buildConfigChildTags() {
        Map<String, List<String>> m = new HashMap<>();
        m.put(null, CONFIG_ROOT_CHILDREN);
        m.put("configuration", CONFIG_ROOT_CHILDREN);
        m.put("settings", List.of("setting"));
        m.put("typeAliases", List.of("typeAlias", "package"));
        m.put("typeHandlers", List.of("typeHandler", "package"));
        m.put("plugins", List.of("plugin"));
        m.put("objectFactory", List.of("property"));
        m.put("objectWrapperFactory", List.of());
        m.put("reflectorFactory", List.of());
        m.put("environments", List.of("environment"));
        m.put("environment", List.of("transactionManager", "dataSource"));
        m.put("transactionManager", List.of("property"));
        m.put("dataSource", List.of("property"));
        m.put("databaseIdProvider", List.of("property"));
        m.put("mappers", List.of("mapper", "package"));
        m.put("properties", List.of("property"));
        m.put("mapper", List.of());
        return Collections.unmodifiableMap(m);
    }
}
