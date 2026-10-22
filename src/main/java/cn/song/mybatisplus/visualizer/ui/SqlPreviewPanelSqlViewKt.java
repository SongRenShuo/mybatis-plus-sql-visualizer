// T-SyncK2JMaster482 波D1 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelSqlView.kt
// （T-M53-II 2026-10-10：SqlPreviewPanel 的「SQL 文本展示、格式化/显示模式、复制导出与补全上下文」内聚成员，
//   以同包扩展函数形式搬出——方法体逐字符搬运）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.execute.DocWriteContext;
import cn.song.mybatisplus.visualizer.execute.SqlExecutor;
import cn.song.mybatisplus.visualizer.model.FillClause;
import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.model.SqlPreviewModel;
import cn.song.mybatisplus.visualizer.share.ui.ShareDialog;
import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.sql.MySqlDialect;
import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer;
import cn.song.mybatisplus.visualizer.sql.SqlCommentStripper;
import cn.song.mybatisplus.visualizer.sql.SqlGenerator;
import cn.song.mybatisplus.visualizer.sql.extract.TableExtractor;
import cn.song.mybatisplus.visualizer.ui.components.CopyExecutableSqlAction;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContributor;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter;
import cn.song.mybatisplus.visualizer.ui.inlay.PreviewInlayScope;
import cn.song.mybatisplus.visualizer.util.EditorCommentShortcuts;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
import cn.song.mybatisplus.visualizer.xml.ParameterMapping;
import cn.song.mybatisplus.visualizer.xml.RenderedXmlSql;
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel;
import cn.song.mybatisplus.visualizer.xml.XmlPreviewState;
import cn.song.mybatisplus.visualizer.xml.XmlStatementKind;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.Shortcut;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.keymap.KeymapManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code SqlPreviewPanelSqlView.kt}（T-M53-II 拆分件）的 Java 承载类——扩展函数
 * {@code fun SqlPreviewPanel.foo(...)} 落为 {@code public static R foo(SqlPreviewPanel receiver, ...)}。
 *
 * <p>含 SQL 文本展示、格式化/显示模式、复制导出与补全上下文相关成员。</p>
 */
public final class SqlPreviewPanelSqlViewKt {

    private SqlPreviewPanelSqlViewKt() {
    }

    /** 刷新补全上下文（M16.2 双载体：Editor userData 优先 + PSI 兜底）。 */
    public static void refreshCompletionContext(@NotNull SqlPreviewPanel p) {
        SqlCompletionContext ctx = buildCompletionContext(p);
        if (p.editor != null) p.editor.putUserData(SqlCompletionContext.KEY, ctx);
        if (p.psiFile != null) p.psiFile.putUserData(SqlCompletionContext.KEY, ctx);
        if (p.editor != null) SqlCompletionContributor.registerContext(p.editor.getDocument(), ctx);
        // T-InsertColumnInlay O-44b：预览语句类型载体随上下文一同刷新（同一收口，两处渲染路径都过这里）——
        // INSERT 目标列 inlay 的语句类型闸门据此判定；非 XML 预览（xmlModel 为 null）写 null 即清除，
        // 防复用编辑器上残留上一次预览的语句类型
        attachPreviewStatementKind(p);
        // T-DbSchemaCompletion：预览实际输入面是原生控制台（Ultimate），它的上下文只在
        // onNativeEditorReady 挂过一次——不在此同步，schema 预热完成后控制台仍读旧上下文，
        // 库里独有的列照旧补不出来（且控制台端编辑器 userData 优先级高于 document 兜底）。
        Editor console = p.nativeConsoleEditor;
        if (console != null) {
            console.putUserData(SqlCompletionContext.KEY, ctx);
            SqlCompletionContributor.registerContext(console.getDocument(), ctx);
        }
    }

    /**
     * T-InsertColumnInlay O-44b：把当前预览的语句类型写到预览编辑器（Editor + PSI 文件双载体）。
     *
     * <p>这是 `xmlModel.kind` 的<b>首个读点</b>（此前零消费者）——INSERT 目标列 inlay 的语句类型闸门
     * 由 provider 读该载体判定，`kind != INSERT` 即零提示。取 `xmlModel` 而非 `SqlTypeClassifier`：
     * 语句类型是解析期事实（`XmlMapperParser.kindOf`），非 XML 预览链无此模型（写 null = 不提示）。</p>
     *
     * <p>写 null 而非「不写」：预览编辑器在预览切换间被复用（`showSql` 的 existing 分支），
     * 残留上一次预览的语句类型会让复用编辑器继续按旧类型产提示。</p>
     *
     * <p>Java 形态：Kotlin 默认参数 {@code target: Editor? = editor} → 两重载（无参版取字段 {@link SqlPreviewPanel#editor}）。</p>
     */
    public static void attachPreviewStatementKind(@NotNull SqlPreviewPanel p) {
        attachPreviewStatementKind(p, p.editor);
    }

    public static void attachPreviewStatementKind(@NotNull SqlPreviewPanel p, @Nullable Editor target) {
        XmlStatementKind kind = p.current != null && p.current.getXmlModel() != null
                ? p.current.getXmlModel().getKind() : null;
        if (target != null) PreviewInlayScope.attach(target, kind);
        if (p.psiFile != null) PreviewInlayScope.attach(p.psiFile, kind);
    }

    /**
     * T-DbSchemaCompletion：补全 schema 预热涉及的表——SQL 里出现的表（含 JOIN）+ 预览主表。
     *
     * <p>表名取自 TableExtractor（纯词法，无 PSI）；解析不到时只剩主表兜底。带别名/括号的误提取
     * 会按「确认没有此表」记入负缓存（TTL 30s，DDL/提交后随 MetadataInvalidator 一起失效），
     * 代价只是 30s 内同表不再重扫、少几个补全候选，无害。</p>
     */
    @NotNull
    public static List<String> completionTables(@NotNull SqlPreviewPanel p, @Nullable PreviewResult result) {
        if (result == null) return List.of();
        List<String> merged = new ArrayList<>();
        merged.addAll(TableExtractor.extract(result.getSql()));
        if (result.getTableName() != null) merged.add(result.getTableName());
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String t : merged) {
            String trimmed = t.trim();
            if (!trimmed.isEmpty() && seen.add(trimmed)) out.add(trimmed);
        }
        return out;
    }

    /**
     * T-DbSchemaCompletion：后台预热「本文档涉及的表」的数据源 schema，完成后刷新补全上下文。
     *
     * <p>快照判据与 T-Perf-09 的 JOIN 富化同款（{@code current == result} 才应用）——新预览打开即作废迟到结果。
     * 数据源未连接时 {@code getModel()} 为 null → 解析 0 张且不留负缓存，控制台就绪（数据源已连）时会经
     * {@link SqlPreviewPanelExecutionKt#onNativeEditorReady} 再触发一次，覆盖「预览早于建连」的窗口。</p>
     *
     * <p>补全热路径不做任何遍历：本方法只在后台把结果填进 DatabaseSchemaResolver 缓存，
     * 之后 {@link #buildCompletionContext} 只读缓存（T-Perf-09 红线）。</p>
     */
    public static void prefetchSchemaForCompletion(@NotNull SqlPreviewPanel p, PreviewResult result) {
        Project proj = p.project;
        if (proj == null) return;
        List<String> tables = completionTables(p, result);
        if (tables.isEmpty()) return;
        final PreviewResult resultFinal = result;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            int resolved;
            try {
                resolved = cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver
                        .prefetchTables(proj, tables);
            } catch (Throwable t) {
                resolved = 0;
            }
            Logger.getInstance(SqlPreviewPanel.class)
                    .info("[T-DbSchemaCompletion] schema 预热: tables=" + tables.size() + ", resolved=" + resolved
                            + ", list=" + tables);
            if (resolved == 0) return;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (p.current != resultFinal || p.project == null || p.project.isDisposed()) return;
                refreshCompletionContext(p);
            });
        });
    }

    /** 由当前预览结果构造补全上下文（表名/列名/注释；M16.2 抽为复用函数，Editor 与 PSI 同源）。 */
    @NotNull
    public static SqlCompletionContext buildCompletionContext(@NotNull SqlPreviewPanel p) {
        return new SqlCompletionContext(
                p.current != null ? p.current.getTableName() : null,
                p.current != null ? p.current.getColumns() : Collections.emptyList(),
                null,
                p.current != null ? p.current.getColumnComments() : Collections.emptyMap(),
                p.current != null ? p.current.getTableComment() : null,
                // T-M27.4：联表候选表（XML 预览经 resolveXml 注入；预览补全表名候选）
                p.current != null ? p.current.getCandidateTables() : Collections.emptyList(),
                // T-DbSchemaCompletion：数据源 schema 列（库里存在而实体类没有的列也是候选）。
                // 只读预热缓存（columnsCached 不触发遍历）；未预热/无数据源时空 Map = 改动前语义。
                cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver
                        .columnsCached(p.project, completionTables(p, p.current)));
    }

    /**
     * JDBC 命名参数（NamedParameterJdbcTemplate {@code :name}）按名回填。
     *
     * <p>当 {@code PreviewResult.namedParamHints} 非空时，把 SQL 中 {@code :name} 按用户输入的参数值整体替换
     * （同名参数所有出现共用一值，对齐 NamedParameterJdbcTemplate 语义）；{@code ?} 占位符不受影响，
     * 由调用方继续走 {@link SqlPreviewPanelParamPanelKt#backfillParamsBySource} 按序回填。
     * 命名参数提示为空时原样返回（兼容既有 {@code ?} 顺序回填路径）。</p>
     */
    public static String applyNamedParams(@NotNull SqlPreviewPanel p, String sql) {
        PreviewResult result = p.current;
        if (result == null) return sql;
        if (result.getNamedParamHints().isEmpty()) return sql;
        return ParameterReplacer.replaceNamedParams(sql, p.paramValues, result.getNamedParamHints());
    }

    /**
     * R12 T-PaginationSim：模型级重渲染后把分页 LIMIT 行带回主 SQL 尾部。
     *
     * <p>面板勾选/参数变更走 XmlPreviewModel.renderSql / SqlGenerator 重建——模型层 SQL 是分页后处理
     * <b>之前</b>的形态（服务端 applyPaginationShared 只作用于整条 PreviewResult），LIMIT 行会在此丢失。
     * 服务端把 LIMIT 行存进 {@code PreviewResult.paginationLimitLine}，此处原样带回；主 SQL 已含 LIMIT
     * （手写语句）时跳过。</p>
     */
    public static String withPaginationLimitLine(@NotNull SqlPreviewPanel p, String baseSql) {
        return SqlPreviewPanelTextKt.appendPaginationLimitLine(
                baseSql, p.current != null ? p.current.getPaginationLimitLine() : null);
    }

    /** 按当前压缩/多行模式 + 动态条件启用集合 + 参数回填重渲染 SQL 编辑器。 */
    public static void renderSql(@NotNull SqlPreviewPanel p) {
        // T-WSC-01：写安全自愈闸——renderSql 是面板全部文档写入的唯一上游（showSql 三条分支
        // 都写文档：nativeDoc / 自建 editor / createEditor），而写入合法性取决于「这次 EDT 活动
        // 是否 write-safe」（见 DocWriteContext KDoc）。非 write-safe（如 invokeLater(any) 排出的
        // 活动、Timer 拍）时重投递到 nonModal 的下一拍；renderSql 幂等重算，不会写出过期文本
        // ——这正是闸门放在 renderSql 而非 showSql(text) 的原因（后者收的是调用时刻的快照）。
        if (!DocWriteContext.isWriteAllowedNow()) {
            DocWriteContext.invokeLaterWriteSafe(p, "SqlPreviewPanel.renderSql", () -> renderSql(p));
            return;
        }
        PreviewResult result = p.current;
        if (result == null) return;
        SqlPreviewModel model = result.getPreviewModel();
        cn.song.mybatisplus.visualizer.model.BatchModel batch = result.getBatchModel();
        XmlPreviewModel xml = result.getXmlModel();
        if (xml != null) {
            // T-M24-04：XML 动态 SQL——渲染单源 XmlPreviewModel.renderSql(state)，勾选/次数/覆盖变更重渲染
            XmlPreviewState state = new XmlPreviewState(
                    new LinkedHashSet<>(p.enabledIds),
                    new LinkedHashMap<>(p.xmlForEachCounts),
                    new LinkedHashSet<>(p.xmlForEachNulls),
                    // T-M26.4：bind 覆盖已由「参数」区输入承担（回填层覆盖），渲染走表达式自动求值
                    Collections.emptyMap());
            RenderedXmlSql rendered = xml.renderSql(state);
            p.xmlErrors = rendered.errors();
            p.xmlMappings = rendered.mappings();
            // O-78：foreach 值按本帧新鲜映射清单播种（写安全闸重投递时与映射刷新同帧延迟，
            // 值状态→渲染单向流动，不再有「渲染后同步读键清单」的错位窗口）
            SqlPreviewPanelParamPanelKt.syncForEachItemValues(p, xml);
            p.xmlDollarSubstitutions = rendered.dollarSubstitutions();
            // R30 探镜：本轮条件判定结果刷新条件行尾三态标注（只读观测，不参与渲染文本）
            SqlPreviewPanelParamPanelKt.syncXmlConditionOutcomeLabels(p, xml, rendered);
            // T-M25.5：bind 自动求值结果并入参数回填（用户手动输入优先，清空则恢复自动值）
            for (Map.Entry<String, String> e : rendered.bindValues().entrySet()) {
                String name = e.getKey();
                String value = e.getValue();
                if (p.paramValues.get(name) == null || p.paramValues.get(name).isEmpty()) {
                    p.paramValues.put(name, value);
                }
            }
            String base = withPaginationLimitLine(p, rendered.sql());
            String sql = p.compactMode ? SqlPreviewPanel.WHITESPACE_REGEX.matcher(base).replaceAll(" ").trim() : base;
            List<String> sources = new ArrayList<>();
            for (ParameterMapping m : rendered.mappings()) sources.add(m.property());
            // T-M29.5：参数回填引号按 mapper 方法参数 Java 类型判定（XmlParamTypeResolver 注入
            // model.paramQuotes：DTO 字段 LocalDate→引号/Integer→裸渲染，贴近真实绑定效果）；
            // 未命中（Map/无类型信息）→ 兜底引号（T-M29.4：日期/字符串列安全，数值列隐式转换等价）
            // O-81 T-XmlListParamSplit：提示组装抽为 xmlBackfillHints——签名集合参数
            // （List<String> 等）无条件按逗号多值渲染 + 引号按元素类别派生，
            // 形态探测（detectInClauseParams）降级为无签名信息参数的兜底通道
            Set<Integer> inListIndices = SqlPreviewPanelParamPanelKt.detectInClauseParams(p, sql);
            List<ParamHint> xmlHints = XmlParamBackfillHints.xmlBackfillHints(xml, sources, inListIndices, p.xmlMappings);
            showSql(p, SqlPreviewPanelParamPanelKt.backfillParamsBySource(p, sql, sources, xmlHints));
        } else if (batch != null) {
            // T-M23-04：批量列级勾选路径——BatchModel.renderSql 按活跃列重建 + 按列序回填
            Set<String> active = new LinkedHashSet<>(p.enabledIds);
            String sql = batch.renderSql(active);
            List<String> sources = batch.orderedSources(active);
            List<ParamHint> hints = batch.orderedHints(active);
            showSql(p, SqlPreviewPanelParamPanelKt.backfillParamsBySource(p, sql,
                    sources != null ? sources : Collections.emptyList(),
                    hints != null ? hints : Collections.emptyList()));
        } else if (model != null) {
            // 模型级重生成：按动态条件启用集合重新生成 SQL（含回填提示），再按 source 回填参数
            cn.song.mybatisplus.visualizer.sql.GeneratedSql generated = SqlGenerator.generateWithParams(
                    model.getStatement(),
                    p.compactMode,
                    model.getDialect(),
                    new LinkedHashSet<>(p.enabledIds));
            // JDBC 命名参数按名回填（先），再对残留 `?` 按序回填
            showSql(p, SqlPreviewPanelParamPanelKt.backfillParamsBySource(p,
                    applyNamedParams(p, withPaginationLimitLine(p, generated.getSql())),
                    generated.getUnresolvedParams(),
                    generated.getParamHints()));
        } else {
            // 旧文本路径（无可勾选动态条件，如批量操作）
            String base = p.compactMode ? result.getCompactSql() : result.getSql();
            // JDBC 命名参数按名回填（先），再对残留 `?` 按序回填
            // T-JpaParamOrder F3：JPA 占位符名序非空时按名回填（同名占位复用同值，
            // 与 setParameter 书写顺序解耦）；hints 在服务端已与该 sources 序平行
            List<String> sources = !result.getPlaceholderParamNames().isEmpty()
                    ? result.getPlaceholderParamNames() : result.getUnresolvedParams();
            showSql(p, SqlPreviewPanelParamPanelKt.backfillParamsBySource(p, applyNamedParams(p, base), sources,
                    result.getParamHints()));
        }
        // T-SelToggle：勾选态一切变更均以 renderSql 收尾——切换按钮文案在此单点同步
        SqlPreviewPanelParamPanelKt.syncToggleSelectLabels(p);
        // O-95 S3：绑定数据源指示行同帧刷新（控制台 attach/reuse/clear 链的既有渲染路径即刷新通道）
        SqlPreviewPanelParamPanelKt.refreshBindStateRow(p);
    }

    /**
     * 刷新视图（M19.3）：模式<b>复位为与设置「SQL 格式化输出」对齐</b>（默认多行）后重渲染编辑器——
     * 用户实测：点刷新后 SQL 仍停留在压缩模式（此前手动切换/设置快照的压缩被保留）；
     * 刷新 = 回到默认状态（参数与回填保留，仅模式复位）。
     *
     * <p>T-PreviewDisplayMode：显示模式一并复位为 NORMAL（即回到设置派生形态——设置开启为格式化、
     * 关闭为原样），与「刷新=回到默认状态」语义一致，竖排/关键字换行不跨刷新残留。</p>
     */
    public static void refreshView(@NotNull SqlPreviewPanel p) {
        // T-RefreshDbSteal：刷新是唯一常规触达共享控制台文档的按钮（WriteCommandAction），
        // 入口记 4 态快照——真机复现时对照判归属
        Logger.getInstance(SqlPreviewPanel.class)
                .info("[M36] refreshView entry: compactMode=" + p.compactMode
                        + ", nativeDoc=" + (p.nativeEditorDoc != null)
                        + ", editor=" + (p.editor != null)
                        + ", embedded=" + System.identityHashCode(p.embeddedConsole)
                        + ", attachedToMe=" + SqlPreviewPanelExecutionKt.consoleAttachedToMe(p));
        p.compactMode = !formatSqlSetting(p);
        p.displayMode = SqlPreviewPanel.PreviewDisplayMode.NORMAL;
        p.modeButton.setText(SqlPreviewPanelExecutionKt.modeButtonText(p));
        renderSql(p);
    }

    /** 设置「SQL 格式化输出」（false=压缩）；环境异常兜底 true（多行）。 */
    public static boolean formatSqlSetting(@NotNull SqlPreviewPanel p) {
        try {
            return PluginSettings.getInstance().getState().getFormatSql();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * T-VPairAnno：竖向排列 VALUES 行尾配对列名标注开关；环境异常兜底 true（保持默认标注）。
     * 与 {@link #formatSqlSetting} 同构——保存后的下一次渲染生效，不做已开弹窗即时推送。
     */
    public static boolean verticalPairAnnotationSetting(@NotNull SqlPreviewPanel p) {
        try {
            return PluginSettings.getInstance().getState().getVerticalPairAnnotation();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * T-PreviewDisplayMode：按当前显示模式变换渲染文本（{@link #showSql} 的唯一收敛点调用）。
     *
     * <p>优先级：压缩模式最高——压缩语义是"全部挤一行"，而模式变换会引入换行，二者相抵故短路；
     * 其余按模式分派：NORMAL 交「SQL 格式化输出」设置决定（保持设置关闭时不格式化的既有语义）、
     * FORMAT 强制格式化、KEYWORD_WRAP / VERTICAL 走对应变换（二者对非 INSERT 已内含 format，
     * 无需叠加）。</p>
     *
     * <p>T-PreviewFast P6：显示模式变换 <b>单槽缓存</b>——同一 (SQL, 模式, 压缩态, 格式化设置) 组合
     * 只做一次 SqlFormatter 变换。命中场景：挂载后回填渲染与紧随其后的参数/勾选重渲染
     * （内容未变时反复走格式化管线是纯浪费，大 SQL 上尤甚）。</p>
     */
    public static String applyDisplayMode(@NotNull SqlPreviewPanel p, String sql) {
        if (p.compactMode) return sql;
        String key = p.displayMode + "|" + p.compactMode + "|" + formatSqlSetting(p) + "|" + sql;
        if (p.formatCacheValue != null && key.equals(p.formatCacheKey)) return p.formatCacheValue;
        String out;
        switch (p.displayMode) {
            case NORMAL:
                out = formatSqlSetting(p) ? SqlFormatter.format(sql) : sql;
                break;
            case FORMAT:
                out = SqlFormatter.format(sql);
                break;
            case KEYWORD_WRAP:
                out = SqlFormatter.formatClauseLine(sql);
                break;
            case VERTICAL:
                out = SqlFormatter.formatVertical(sql, verticalPairAnnotationSetting(p));
                break;
            default:
                out = sql;
                break;
        }
        p.formatCacheKey = key;
        p.formatCacheValue = out;
        return out;
    }

    /**
     * T-PreviewDisplayMode：切换编辑器显示模式并重渲染（格式族按钮与 Ctrl+Alt+L 快捷键的统一入口）。
     *
     * <p>与旧实现的区别：不再直接对文档做一次性变换，而是记录模式后交渲染管线产出——这样参数输入、
     * 条件勾选、foreach 次数增减等任何重建都不会再把竖排/关键字换行形态抹掉。</p>
     *
     * <p>压缩模式下切换格式族模式会一并退出压缩：否则模式被压缩短路、点击无可见效果（且"压缩态 +
     * 已格式化文档"是自相矛盾的中间态）。</p>
     */
    public static void setDisplayMode(@NotNull SqlPreviewPanel p, SqlPreviewPanel.PreviewDisplayMode mode) {
        p.displayMode = mode;
        if (p.compactMode) {
            p.compactMode = false;
            // T-M53-II：`::modeButton.isInitialized` 读的是**后备字段**，该类引用只在声明类内可用
            // （搬为同包扩展函数后编译器红：backing field not accessible）。语义不变地改走类内
            // 小组件 `modeButtonOrNull()`（同样的 isInitialized 判定，同文件同包）。
            JButton mb = p.modeButtonOrNull();
            if (mb != null) mb.setText(SqlPreviewPanelExecutionKt.modeButtonText(p));
        }
        renderSql(p);
    }

    /**
     * 格式化当前编辑器 SQL（关键字大写 / 子句换行 / 条件缩进，SqlFormatter）。
     * T-PreviewDisplayMode：等价于「格式化」按钮——设 FORMAT 模式后由渲染管线套用（形态在参数
     * 变更后保持）；不再需要直接改文档，故改走状态路径。
     */
    public static void formatSql(@NotNull SqlPreviewPanel p) {
        String before = p.lastRenderedSql;
        setDisplayMode(p, SqlPreviewPanel.PreviewDisplayMode.FORMAT);
        notifyIfFormatUnchanged(p, before);
    }

    /**
     * T-DdlLogPreview S3（20261006 第三轮走查，D76 用户裁决「可以给」）：格式化后文本<b>零变化</b>时
     * 给一条轻提示。
     *
     * <p>立项动因：`DROP TABLE IF EXISTS x ;` 这类<b>本就单行</b>（无换行点）的语句点「格式化」看不到任何
     * 变化，用户读成「格式化坏了」——实测即由此报障。判据 = 渲染文本前后一致（{@code lastRenderedSql}
     * 是渲染管线唯一产物，含格式化结果）；同文本往返说明「已是最可读形态」，明确告知即可。
     * 与既有轻提示同规格：{@code NOTIFICATION_GROUP_ID} + INFORMATION，非模态；单测环境静默（不产生通知）。</p>
     */
    public static void notifyIfFormatUnchanged(@NotNull SqlPreviewPanel p, @Nullable String before) {
        String after = p.lastRenderedSql;
        if (before == null || after == null || !before.equals(after)) return;
        if (ApplicationManager.getApplication().isUnitTestMode()) return;
        try {
            new Notification(
                    SqlPreviewPanel.NOTIFICATION_GROUP_ID,
                    MpSqlVisualizerBundle.message("panel.format"),
                    MpSqlVisualizerBundle.message("panel.format.alreadyFormatted"),
                    NotificationType.INFORMATION)
                    .notify(p.project);
        } catch (Throwable ignored) {
        }
    }

    /** 关键字换行格式化：子句顶格换行、同行内容压一行（{@code SqlFormatter.formatClauseLine}）。 */
    public static void formatClauseLine(@NotNull SqlPreviewPanel p) {
        setDisplayMode(p, SqlPreviewPanel.PreviewDisplayMode.KEYWORD_WRAP);
    }

    /**
     * 竖向排列格式化：INSERT 列清单/VALUES 逐行竖排、值行尾标注配对列名（{@code SqlFormatter.formatVertical}）。
     * T-VPairAnno：标注是否追加由设置 verticalPairAnnotation 控制（默认开）。
     */
    public static void formatVertical(@NotNull SqlPreviewPanel p) {
        setDisplayMode(p, SqlPreviewPanel.PreviewDisplayMode.VERTICAL);
    }

    /**
     * T-M26.6：格式化快捷键集——当前激活 keymap 中 Reformat Code（Ctrl+Alt+L）的绑定
     * （跟随用户自定义键位）；keymap 无绑定时兜底默认 Ctrl+Alt+L。
     * 注：2025.3 平台 IdeActions 无 ReformatCode 常量，用动作 id 字符串。
     */
    @NotNull
    public static Shortcut[] reformatShortcuts(@NotNull SqlPreviewPanel p) {
        Shortcut[] fromKeymap = KeymapManager.getInstance().getActiveKeymap().getShortcuts("ReformatCode");
        if (fromKeymap.length > 0) return fromKeymap;
        return new Shortcut[]{
                new KeyboardShortcut(
                        KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_MASK | InputEvent.ALT_MASK),
                        null)
        };
    }

    /** 压缩 / 多行切换。T-PreviewDisplayMode：不改显示模式——切回多行时既有模式（如竖排）恢复。 */
    public static boolean toggleCompact(@NotNull SqlPreviewPanel p) {
        p.compactMode = !p.compactMode;
        renderSql(p);
        return p.compactMode;
    }

    /** 一键复制当前 SQL（Kotlin 默认参 {@code offerClosePanelAction=true} 形态）。 */
    public static boolean copySql(@NotNull SqlPreviewPanel p) {
        return copySql(p, true);
    }

    /**
     * 一键复制当前 SQL（取编辑器当前内容，含手动编辑与参数回填）。
     *
     * @param offerClosePanelAction 复制成功通知里是否附带「关闭 SQL 面板」动作。仅当调用方**不**关闭
     *                              面板时才有意义——「复制 SQL 并关闭」本身就会关掉弹窗，再挂一个
     *                              「关闭 SQL 面板」既冗余、点击也无目标可关（UI-044：用户复制后
     *                              弹窗已关，通知里还留着这个链接）。
     */
    public static boolean copySql(@NotNull SqlPreviewPanel p, boolean offerClosePanelAction) {
        // M36.4：优先取原生编辑器文档，兜底自研编辑器
        String raw = p.nativeEditorDoc != null && !p.nativeEditorDoc.getText().isBlank()
                ? p.nativeEditorDoc.getText()
                : (p.editor != null && !p.editor.getDocument().getText().isBlank()
                        ? p.editor.getDocument().getText() : null);
        if (raw == null) return false;
        // 复制时剥离 SQL 注释（-- 行注释 + /* */ 块注释），与 Java 代码复制对齐
        String text = SqlCommentStripper.strip(raw);
        UiTextClipboard.copy(text);
        // UI-044 测试缝：非 null 时记录「本次通知会挂哪些动作」，绕开单测模式下不弹通知的分支
        java.util.List<String> actionSink = p.copyNotificationActionsForTest;
        if (actionSink != null) {
            if (offerClosePanelAction) {
                actionSink.add(MpSqlVisualizerBundle.message("dialog.closeSqlPanel"));
            }
        }
        if (p.project != null) {
            // T-UiNative T-UN-02：复制成功提示改轻通知 + 「关闭 SQL 面板」动作（T-M23.6 原双按钮
            // 模态改为非模态——点动作等价原 index==1 分支，回调关闭预览弹窗）；
            // 测试模式跳过（对齐 CopyExecutableSqlAction.showToast 约定）
            if (!ApplicationManager.getApplication().isUnitTestMode()) {
                try {
                    Notification notification = new Notification(
                            SqlPreviewPanel.NOTIFICATION_GROUP_ID,
                            MpSqlVisualizerBundle.message("plugin.name"),
                            MpSqlVisualizerBundle.message("dialog.copy.done"),
                            NotificationType.INFORMATION);
                    if (offerClosePanelAction) {
                        notification.addAction(NotificationAction.createSimpleExpiring(
                                MpSqlVisualizerBundle.message("dialog.closeSqlPanel"),
                                () -> {
                                    if (p.onClosePanelRequested != null) p.onClosePanelRequested.run();
                                }));
                    }
                    notification.notify(p.project);
                } catch (Throwable ignored) {
                    // headless 无通知系统依赖，与原 runCatching 等价
                }
            }
        }
        return true;
    }

    /**
     * O-4：一键复制可执行 SQL（替换 {@code ?} 为带类型处理的字面值，复制到剪贴板 + toast 提示）。
     * 委托 {@link CopyExecutableSqlAction} 处理替换逻辑与通知。
     */
    public static void copyExecutableSql(@NotNull SqlPreviewPanel p) {
        new CopyExecutableSqlAction(p.project).execute(p);
    }

    /**
     * F-8：显示分享选项对话框（Markdown / HTML / 可执行 SQL）。
     * 委托 {@link ShareDialog}。
     */
    public static void showShareDialog(@NotNull SqlPreviewPanel p) {
        new ShareDialog(p.project, p).show();
    }

    // ------------------------------------------------------------------
    // O-4：SQL 并排对比（IDEA 原生 Diff，SQL 语法高亮）
    // ------------------------------------------------------------------

    /**
     * O-4：使用 IDEA 原生 Diff 对比当前 SQL 与上次预览 SQL。
     * 无上次预览时 toast 提示。
     *
     * <p>接入方式：DiffContentFactory.createEditable() 创建可编辑内容 + SimpleDiffRequest 包装 → DiffManager.showDiff。
     * 原生 diff 支持 SQL 语法高亮、行级对比、同步滚动、可编辑、F7 跳转差异等 IDEA 标准能力。</p>
     */
    public static void showSqlDiff(@NotNull SqlPreviewPanel p) {
        Project proj = p.project;
        if (proj == null) return;
        // M36.4：优先取原生编辑器文档，兜底自研编辑器
        String currentSql = p.nativeEditorDoc != null && !p.nativeEditorDoc.getText().isBlank()
                ? p.nativeEditorDoc.getText()
                : (p.editor != null && !p.editor.getDocument().getText().isBlank()
                        ? p.editor.getDocument().getText() : null);
        if (currentSql == null) return;
        String oldSql = p.previousSql;
        if (oldSql == null) {
            showDiffToast(p, MpSqlVisualizerBundle.message("preview.diff.noPrevious"));
            return;
        }
        // IDEA 原生 Diff（createEditable → 两侧均可编辑，SQL 文件类型 → 语法高亮）
        com.intellij.diff.DiffContentFactory diffContentFactory = com.intellij.diff.DiffContentFactory.getInstance();
        com.intellij.openapi.fileTypes.FileType sqlFileType =
                com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getStdFileType("SQL");
        com.intellij.diff.contents.DocumentContent leftContent =
                diffContentFactory.createEditable(proj, oldSql, sqlFileType);
        com.intellij.diff.contents.DocumentContent rightContent =
                diffContentFactory.createEditable(proj, currentSql, sqlFileType);
        com.intellij.diff.requests.SimpleDiffRequest request = new com.intellij.diff.requests.SimpleDiffRequest(
                MpSqlVisualizerBundle.message("panel.diff.dialogTitle"),
                leftContent,
                rightContent,
                MpSqlVisualizerBundle.message("panel.diff.previous"),
                MpSqlVisualizerBundle.message("panel.diff.current"));
        com.intellij.diff.DiffManager.getInstance().showDiff(proj, request);
    }

    /** O-4：显示 diff 相关 toast 通知。 */
    public static void showDiffToast(@NotNull SqlPreviewPanel p, String message) {
        if (ApplicationManager.getApplication().isUnitTestMode()) return;
        Project proj = p.project;
        if (proj == null) return;
        try {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup(SqlPreviewPanel.NOTIFICATION_GROUP_ID)
                    .createNotification(message, NotificationType.INFORMATION)
                    .notify(proj);
        } catch (Throwable ignored) {
        }
    }

    /**
     * T-M23.6：提示「关闭 SQL 面板」按钮回调（由预览弹窗 {@code SqlPreviewDialog} 注入 {@code dispose()}；
     * null 时提示不出现关闭按钮链路，回调为空操作）。
     */
    public static void clear(@NotNull SqlPreviewPanel p) {
        p.current = null;
        p.enabledIds.clear();
        p.paramValues.clear();
        p.paramFields.clear();
        p.conditionCheckBoxes.clear();
        p.xmlConditionOutcomeLabels.clear();
        p.conditionValueFields.clear();
        p.batchCheckBoxes.clear();
        p.xmlForEachNullBoxes.clear();
        p.xmlForEachValueFields.clear();
        p.xmlForEachCounts.clear();
        p.xmlForEachValues.clear();
        p.xmlForEachNulls.clear();
        p.xmlErrors = List.of();
        p.xmlDollarSubstitutions = 0;
        p.currentWarning = null;
        if (p.errorCopyButton != null) p.errorCopyButton.setVisible(false);
        p.toggleSelectButton = null;
        p.batchToggleSelectButton = null;
        p.paramPanel.removeAll();
        // O-4：重置会话缓存
        p.previousSql = null;
        // T-PreviewDisplayMode：显示模式随面板清空复位（与其余会话状态一致，不跨预览残留）
        p.displayMode = SqlPreviewPanel.PreviewDisplayMode.NORMAL;
        SqlPreviewPanelEditorLifecycleKt.releaseEditor(p);
        p.editorHolder.removeAll();
        p.metaLabel.setText("");
        // T-PreviewTopOverlap F3：元信息原文缓存随清空复位（下次 updateMeta 重建）
        p.metaLineText = "";
        p.metaLineHead = "";
        p.metaLineDigest = "";
        p.metaPerfSegments = List.of();
        p.lastMetaBudget = -1;
        // T-SourceJumpFunnel P6：tooltip 基线同步复位（行内提示态据此拼接，不复用上一份预览的残留）
        p.metaTooltipBase = "";
        p.metaLabel.setToolTipText(null);
        // T-StackHoverUi：调用栈悬停弹层随清空复位（旧弹层即关，下次预览按新数据重弹）
        p.stackHover.updateDigest(null);
        // F-1：清除图集 + 恢复纯 SQL 视图（整图卸载含虚拟外键回收）
        SqlPreviewPanelResultViewKt.removeDiagramTab(p);
        SqlPreviewPanelResultViewKt.unloadJoinDiagram(p);
        // M36：清空结果面板 + 摘除内嵌控制台
        p.resultPanel.clear();
        SqlPreviewPanelExecutionKt.detachConsole(p);
        // R33：作废在途结构化 EXPLAIN 回调 + 清掉结构化视图残留（consoleHost 子层换空，
        // 下次预览重新走 prepareConsole 正常换入控制台；editorHolder 清空在本函数开头已做）
        p.explainStructuredGeneration++;
        p.consoleHost.removeAll();
        // M36.4：重置原生编辑器文档引用
        p.nativeEditorDoc = null;
        // T-AutoFold F5：控制台编辑器引用随清空复位
        // T-PreviewLineCommentDash O-134：复位先减计数（detachConsole 已覆盖常规路径，此处兜底直清分支）
        if (p.nativeConsoleEditor != null) EditorCommentShortcuts.uninstall(p.nativeConsoleEditor);
        p.nativeConsoleEditor = null;
        // M36.5：释放已准备控制台引用（下次预览重新准备）
        SqlExecutor.clearPrepared();
        // T-M36.6-03：clear 释放了临时文件/预热产物——面板重用时重新预热（幂等，保持零慢操作路径）
        if (p.project != null) {
            SqlPreviewPanelEditorLifecycleKt.prewarmPreviewTempFile(p);
        }
        p.revalidate();
        p.repaint();
    }

    // ------------------------------------------------------------------
    // 编辑器（PSI 支撑 + 可编辑 + 高亮）
    // ------------------------------------------------------------------

    /** 渲染 SQL 到可编辑编辑器；已存在时更新文档（保留焦点/撤销）。 */
    public static void showSql(@NotNull SqlPreviewPanel p, String sql) {
        // R33：新预览作废在途结构化 EXPLAIN 回调（慢计划不覆盖用户已切换的新预览）
        p.explainStructuredGeneration++;
        // T-RefreshDbSteal：三分支走查埋点——真机故障期症状②（左参数不再同步右侧 SQL）的
        // 丢失路径必经此方法（三个静默分支之一吞掉了渲染），带分支标签定位
        Logger fLogger = Logger.getInstance(SqlPreviewPanel.class);
        // T-MpjFill：追加 fill 附加查询段（MPJ 1.5.1+）——在主 SQL 之后渲染 fill 附加 SELECT
        String withFill = appendFillSection(p, sql);
        // R12 T-PaginationSim：追加分页 COUNT 包裹段（D1=A 预览附第二条语句，对齐捕获侧 COUNT 独立条目口径）
        String withFillAndCount = appendCountSection(p, withFill);
        // T-PreviewDisplayMode：显示模式在渲染管线内统一套用（唯一收敛点——XML/批量/模型/旧文本
        // 四条重建路径都经此处，故参数输入/勾选/foreach 变更后形态保持）
        String text = applyDisplayMode(p, withFillAndCount);
        // M36.4：优先写入原生编辑器文档（内嵌后）
        Document nativeDoc = p.nativeEditorDoc;
        if (nativeDoc != null) {
            // T-RefreshDbSteal F4：控制台失联时不写"别人的文档"（症状②：参数改动同步进
            // 被抢走的控制台，本面板视觉零变化）——改走失联恢复把控制台要回来；恢复链
            // 内部会同步 SQL 进控制台文档（reuseSharedConsole setText），本次渲染不丢
            if (!SqlPreviewPanelExecutionKt.consoleAttachedToMe(p)) {
                fLogger.warn("[M36] showSql nativeDoc detached (attachedToMe=false) → recoverConsole");
                SqlPreviewPanelExecutionKt.recoverConsole(p, text);
                return;
            }
            if (!nativeDoc.getText().equals(text)) {
                fLogger.info("[M36] showSql branch=nativeDoc: len=" + text.length()
                        + ", attachedToMe=" + SqlPreviewPanelExecutionKt.consoleAttachedToMe(p));
                p.suppressAutoPopup = true;
                try {
                    // T-PreviewFast P4：程序性写入开闸——否则本次写入会触发执行文件的 1.5s 防抖
                    // 自动落盘（内容刚由本写入落盘，再写一遍纯属自己与自己抢）
                    final Document nativeDocFinal = nativeDoc;
                    cn.song.mybatisplus.visualizer.execute.ExecDocAutosaveGate.suppressDuring(() -> {
                        WriteCommandAction.runWriteCommandAction(p.project, () -> nativeDocFinal.setText(text));
                        // T-PS4-12 定案①：同临界区落盘（照 DataSourceSelector.writeVfTextViaDocument
                        // 手法，isDocumentUnsaved → saveDocument）——闸门前提「内容由本写入落盘」
                        // 在此成真，formatted 文本不再滞留 modified-unsaved（第七轮审计发现本体）。
                        //
                        // 「磁盘存原文还是 formatted」核实结论（T-PreviewConsoleFmt F1/F2 +
                        // T-PreviewFast P1/P3 定例）：挂载后磁盘跟随 document 是既定语义——
                        //  1) ConsoleFmt F1 定例 = 控制台挂载后渲染链把 formatted 回填进控制台
                        //     文档，文档内容（=控制台所见）即权威；
                        //  2) T-PreviewFast P1 `execFileContentMatches` 定例 = 有已加载 document
                        //     时以 document 文本为准（注释原文「也是落盘会写入的内容」）；
                        //  3) 「磁盘存预览原文」仅是预热路径的写入，且只发生在文档未挂编辑器时
                        //     （ConsoleFmt F2 + PreviewFast P3：文档打开时预热跳过内容刷新）；
                        //  4) 落盘 formatted 不改变执行语义：执行链执行前同步的是控制台文档内容，
                        //     用户手改兜底（F5）落盘的同样是 document 内容。
                        // 故本写入落盘 formatted 与既有定例一致；孤儿文档（getFile null，测试缝
                        // createDocument 形态）无盘可落，自然跳过。
                        com.intellij.openapi.fileEditor.FileDocumentManager fdm =
                                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance();
                        VirtualFile vf = fdm.getFile(nativeDocFinal);
                        if (vf != null && fdm.isDocumentUnsaved(nativeDocFinal)) {
                            fdm.saveDocument(nativeDocFinal);
                        }
                        return null;
                    });
                } finally {
                    p.suppressAutoPopup = false;
                }
            }
            // T-PrewarmLateRecover F1：记录插件最后一次写入的文本——迟到产物换装据此判「用户没动过」
            p.lastRenderedSql = text;
            // T-AutoFold F4-a：nativeDoc 支链的程序性折叠（关闭态 applyTo 内部即清除短路）
            Editor foldTarget = p.nativeConsoleEditor;
            if (foldTarget != null && !foldTarget.isDisposed()) {
                cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(foldTarget, text);
            }
            return;
        }
        Editor existing = p.editor;
        if (existing != null) {
            fLogger.info("[M36] showSql branch=editor: len=" + text.length() + ", nativeDoc=null");
            if (!existing.getDocument().getText().equals(text)) {
                p.suppressAutoPopup = true;
                try {
                    WriteCommandAction.runWriteCommandAction(p.project,
                            () -> existing.getDocument().setText(text));
                } finally {
                    p.suppressAutoPopup = false;
                }
            }
            // T-PrewarmLateRecover F1：记录插件最后一次写入的文本（同上）
            p.lastRenderedSql = text;
            // T-AutoFold F4-a：自建编辑器支链
            cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(existing, text);
            return;
        }
        fLogger.info("[M36] showSql branch=createEditor: len=" + text.length()
                + ", attachedToMe=" + SqlPreviewPanelExecutionKt.consoleAttachedToMe(p));
        // T-RefreshDbSteal F4：createEditor 是 UX 悬崖（无 DBN 工具栏、形态突变带行号）——
        // 控制台插件级就绪但已失联时，先走恢复链重嵌，恢复链自身按回退编辑器保显示
        if (!SqlPreviewPanelExecutionKt.consoleAttachedToMe(p) && SqlExecutor.isPrepared()) {
            fLogger.warn("[M36] createEditor fallback while prepared console detached → recoverConsole");
            SqlPreviewPanelExecutionKt.recoverConsole(p, text);
        }
        // T-PrewarmLateRecover F1：新建支链同样记录（装配后文档文本即 text）
        p.lastRenderedSql = text;
        SqlPreviewPanelEditorLifecycleKt.createEditor(p, text);
    }

    /**
     * T-MpjFill：追加 fill 附加查询段（MPJ 1.5.1+）。
     *
     * <p>在主 SQL 之后追加 fill 附加 SELECT 渲染（每 fill 调用独立一行），
     * 以注释头 {@code -- fill additional query (MPJ 1.5.1+)} 标识。
     * 无 fill 调用时原样返回。</p>
     */
    public static String appendFillSection(@NotNull SqlPreviewPanel p, String sql) {
        PreviewResult result = p.current;
        if (result == null) return sql;
        List<FillClause> fills = result.getFillClauses();
        if (fills == null) return sql;
        if (fills.isEmpty()) return sql;
        Dialect dialect = result.getPreviewModel() != null ? result.getPreviewModel().getDialect() : MySqlDialect.INSTANCE;
        StringBuilder sb = new StringBuilder(sql);
        sb.append("\n\n-- ").append(MpSqlVisualizerBundle.message("panel.fill.section")).append("\n");
        for (FillClause fill : fills) {
            sb.append(SqlGenerator.generateFill(fill, dialect));
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * R12 T-PaginationSim：追加分页 COUNT 包裹段（D1=A 预览附第二条语句）。
     *
     * <p>在主 SQL（含 fill 段）之后以注释头并列渲染 COUNT 包裹 SQL（对齐捕获侧 MP_PAGINATION_COUNT
     * 独立条目口径——运行时 COUNT 是分页查询之外的独立语句）。无 COUNT 产出时原样返回。</p>
     *
     * <p>T-DevAcceptFix ③（20261006 验收）：两条语句各自以 {@code ;} 收尾——原实现主 SQL 尾直拼注释头，
     * 复制到控制台执行时分页语句与 COUNT 语句粘连（用户实验收实测）；已带分号不重复追加。</p>
     */
    public static String appendCountSection(@NotNull SqlPreviewPanel p, String sql) {
        PreviewResult result = p.current;
        if (result == null) return sql;
        String countSql = result.getCountSql();
        if (countSql == null) return sql;
        String trimmedMain = sql.stripTrailing();
        String terminatedMain = trimmedMain.endsWith(";") ? trimmedMain : trimmedMain + ";";
        String trimmedCount = countSql.stripTrailing();
        String terminatedCount = trimmedCount.endsWith(";") ? trimmedCount : trimmedCount + ";";
        return new StringBuilder(terminatedMain)
                .append("\n\n-- ").append(MpSqlVisualizerBundle.message("panel.pagination.count.section")).append("\n")
                .append(terminatedCount)
                .append("\n")
                .toString();
    }
}
