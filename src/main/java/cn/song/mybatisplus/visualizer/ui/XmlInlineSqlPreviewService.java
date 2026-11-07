// T-SyncK2JMaster187 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/XmlInlineSqlPreviewService.kt 移植
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.psi.jpa.HbmXmlMapping;
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.xml.XmlInlineSqlDetector;
import com.intellij.openapi.project.Project;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 通用 XML 内嵌 SQL 预览服务（O-72 T-XmlInlineSql）：载体标签 → {@link PreviewResult}。
 *
 * <ul>
 *   <li>源语言判定（T-PagesHql 泛化）：{@code type} ∈ {hql, jpql}（就近祖先优先）为显式 HQL；
 *       无 {@code type} 时按 {@code PagesSourceLanguageDetector} 自动推断（载体标签可带 {@code entityClass} 属性，
 *       也可完全不带——靠「根标识符能解析为持久化实体」反查，如 {@code <dataValue>}）；</li>
 *   <li>判为 HQL → 复用 hbm 命名查询管道（{@link SqlPreviewService#previewHbmNamedQuery}，
 *       name 传空即走 FROM 子句实体提取）；<b>管道异常 / 实体不可解析 → 回退原文直显 + warning，
 *       不抛错</b>（验收底线：至少展示原文）；</li>
 *   <li>其余 → 原生 SQL 直显（{@code ?} 占位符 → param 表，string-aware 计数）。</li>
 * </ul>
 *
 * <p>输入是已定位的载体 XmlTag（gutter / Action 负责定位），本服务在后台线程调用。</p>
 *
 * <p>Kotlin object → 本类 + {@link #INSTANCE} + 私有构造。</p>
 */
public final class XmlInlineSqlPreviewService {

    public static final XmlInlineSqlPreviewService INSTANCE = new XmlInlineSqlPreviewService();

    private static final Pattern WHITESPACE_REGEX = Pattern.compile("\\s+");

    private XmlInlineSqlPreviewService() {
    }

    @NotNull
    public PreviewResult preview(@Nullable Project project, @NotNull XmlTag tag) {
        String sql = XmlInlineSqlDetector.extractSqlText(tag);
        if (sql.isBlank()) {
            return emptyResult(MpSqlVisualizerBundle.message("xmlInlineSql.empty.warning"));
        }
        if (project == null) {
            // 无工程：能展示但不能判/转 HQL，保持原文直显（历史行为）
            return plainResult(sql);
        }
        // 与 <pages> 链共用同一档位：仅 SQL 时关闭自动 HQL（逃生舱对两条链都生效）
        // 跨层依赖（A3 波次 config/）：pagesXmlSourceMode 档位与 PAGES_SOURCE_* 常量按 master 签名照译
        String mode;
        try {
            mode = PluginSettings.getInstance().getState().getPagesXmlSourceMode();
        } catch (Throwable ignored) {
            mode = PluginSettings.PAGES_SOURCE_AUTO;
        }
        if (PluginSettings.PAGES_SOURCE_SQL.equals(mode)) {
            return plainResult(sql);
        }
        String declaredType = PluginSettings.PAGES_SOURCE_HQL.equals(mode)
                ? "hql"
                : XmlInlineSqlDetector.queryTypeOf(tag);
        // 跨层依赖（xml/ 波次）：PagesSourceLanguageDetector.detect 为纯函数（master 顶层 object，
        // 推断落 static 承载）；probeEntity 注入 PagesEntityProbeResolver（本波次已落位）
        cn.song.mybatisplus.visualizer.xml.PagesSourceDetection detection =
                cn.song.mybatisplus.visualizer.xml.PagesSourceLanguageDetector.detect(
                        sql,
                        tag.getAttributeValue("entityClass"),
                        declaredType,
                        cn.song.mybatisplus.visualizer.psi.jpa.HqlEntityNameExtractor::extract,
                        name -> PagesEntityProbeResolver.probe(project, name, tag));
        if (detection.language() != cn.song.mybatisplus.visualizer.xml.PagesSourceLanguage.HQL) {
            return plainResult(sql);
        }
        PreviewResult converted = null;
        try { // Kotlin runCatching.getOrNull() 语义（含 Error 全吞，回退不抛错）
            converted = project.getService(SqlPreviewService.class)
                    .previewHbmNamedQuery(new HbmXmlMapping.NamedQuery("", HbmXmlMapping.QueryKind.HQL, sql),
                            null, tag, tag);
        } catch (Throwable ignored) {
            // HQL 管道异常 → 回退原文直显（不抛错）
        }
        if (converted != null && !converted.getSql().contains(UNRESOLVED_TABLE)) {
            return withHqlSource(converted, sql);
        }
        // 管道异常 / 根实体不可解析 → 回退原文直显（不抛错）
        return withHqlSource(
                plainResult(sql, MpSqlVisualizerBundle.message(
                        converted == null ? "xmlInlineSql.hqlFallback.warning" : "pages.hql.entityUnresolved")),
                sql);
    }

    /**
     * {@code copy(sourceLanguage = HQL, originalSql = sql)} 的 Java 承载（跨层依赖：model/ 波次的
     * PreviewResult 尾参增量——sourceLanguage/originalSql 按 master 尾参位照译，单点收口便于
     * 该波次形态落位后对齐）。
     */
    @NotNull
    private static PreviewResult withHqlSource(@NotNull PreviewResult base, @NotNull String originalSql) {
        return new PreviewResult(
                base.getSql(), base.getCompactSql(), base.getUnresolvedParams(), base.getParamTable(),
                base.getWrapperType(), base.getTableName(), base.getWarning(), base.getColumns(),
                base.getColumnComments(), base.getTableComment(), base.getPreviewModel(),
                base.getParamHints(), base.getCandidateTables(), base.getBatchModel(),
                base.getXmlModel(), base.getInitialParamValues(), base.getPerfWarnings(),
                base.getFillClauses(), base.getPreviousSql(), base.getPagesColumns(),
                base.getNamedParamHints(), base.getStackDigest(), base.getThreadName(),
                base.isHasDollarRawSegments(), base.getCountSql(), base.getPaginationLimitLine(),
                base.getPlaceholderParamNames(), base.getDuplicateParamNames(),
                base.getDiagramStatement(), base.getHistoryFingerprintSeed(),
                base.getPaginationParamSources(), base.getRenderedDialect(), base.getDialectDecision(),
                base.getHistoryEntryId(), base.getActualSql(),
                cn.song.mybatisplus.visualizer.model.SourceLanguage.HQL, originalSql);
    }

    /** 原生 SQL 直显（PagesXmlPreviewService 同款：{@code ?} 计数 → param1..N）。 */
    @NotNull
    private static PreviewResult plainResult(@NotNull String sql) {
        return plainResult(sql, null);
    }

    /** plainResult + warning（Kotlin data class {@code copy(warning=...)} 等价形态）。 */
    @NotNull
    private static PreviewResult plainResult(@NotNull String sql, @Nullable String warning) {
        List<String> params = extractPlaceholders(sql);
        return new PreviewResult(
                sql,
                WHITESPACE_REGEX.matcher(sql).replaceAll(" ").trim(),
                params,
                ParameterReplacer.buildParamTable(params),
                null,
                null,
                warning,
                List.of(),
                Map.of(),
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                Map.of(),
                null,
                null,
                null,
                null);
    }

    private static List<String> extractPlaceholders(@NotNull String sql) {
        // string-aware 计数（跨层依赖 A1：sql/PlaceholderPositionsKt.placeholderPositions——
        // 字符串字面量里的 ? 不计，与 pages 链同口径）
        int count = cn.song.mybatisplus.visualizer.sql.PlaceholderPositionsKt.placeholderPositions(sql).size();
        if (count == 0) return List.of();
        List<String> result = new ArrayList<>();
        for (int i = 1; i <= count; i++) result.add("param" + i);
        return result;
    }

    private static PreviewResult emptyResult(@NotNull String message) {
        return new PreviewResult(
                "",
                "",
                List.of(),
                null,
                null,
                null,
                message,
                List.of(),
                Map.of(),
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                Map.of(),
                null,
                null,
                null,
                null);
    }

    /** HQL 管道根实体不可解析时的产物指纹（判定回退文案用，与 pages 链同款）。 */
    private static final String UNRESOLVED_TABLE = "unknown_table";
}
