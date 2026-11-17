// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpSourceLocator.kt 移植
package cn.song.mybatisplus.visualizer.mcp;

import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlLocator;
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlPreviewService;
import cn.song.mybatisplus.visualizer.psi.WrapperLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.CriteriaQueryLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.CustomDaoHqlLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.DerivedMethodLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.HqlQueryLocator;
import cn.song.mybatisplus.visualizer.psi.jpa.JpqlQueryLocator;
import cn.song.mybatisplus.visualizer.ui.AnnotationSqlPreviewService;
import cn.song.mybatisplus.visualizer.ui.SourceSqlContext;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import cn.song.mybatisplus.visualizer.ui.gutter.IbatisCallTarget;
import cn.song.mybatisplus.visualizer.ui.gutter.IbatisCallTargets;
import cn.song.mybatisplus.visualizer.ui.gutter.IbatisStatementPreview;
import cn.song.mybatisplus.visualizer.ui.gutter.IbatisXmlGutterLineMarkerProvider;
import cn.song.mybatisplus.visualizer.ui.XmlPreviewService;
import cn.song.mybatisplus.visualizer.ui.XmlSqlCompletionContextResolver;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext;
import cn.song.mybatisplus.visualizer.ui.gutter.XmlSqlGutterLineMarkerProvider;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;

import java.util.List;
import java.util.Locale;

/**
 * T-McpToolset sqlv_render_sql 定位与渲染适配层。
 *
 * 复刻 {@code cn.song.mybatisplus.visualizer.actions.ConvertToSqlAction} 光标分支的服务层等价物：
 * 「文件 + 行号」→ PSI 锚点 → 13 类触发面定位链（Wrapper 链 / MyBatis 注解 / mapper XML /
 * HQL / 自定义 DAO HQL / JdbcTemplate / @Query / 派生查询 / CriteriaQuery / 方法体字符串 SQL）。
 * 与 Action 的差异（MCP 约束）：
 * <ul>
 *  <li>全程 {@link ReadAction}（MCP 调用在平台协程线程，读 PSI 必须持读锁）；</li>
 *  <li>零弹窗：多候选不弹选择框，取首个候选并在结果中说明（方法体多 SQL / 文件级注解候选
 *    场景由 agent 换行号重试）；</li>
 *  <li>Repository 调用点回落（Action 的 tryRepositoryCallSiteFallback）与文件级注解候选弹窗
 *    一期不带，命中失败即降级文案（写进实施记录）。</li>
 * </ul>
 */
public final class McpSourceLocator {

    private McpSourceLocator() {
    }

    /** 单例（Kotlin object 惯例形态；静态方法为主）。 */
    public static final McpSourceLocator INSTANCE = new McpSourceLocator();

    /** 单次渲染结果（SQL 缺失时 error 非空——适配层转 McpExpectedError）。 */
    public record Outcome(
        /** 渲染出的可执行 SQL（失败时 null）。 */
        String sql,
        /** 触发面类型（agent 面展示用）。 */
        String sourceType,
        /** 目标表名（可得时非空）。 */
        String tableName,
        /** 渲染警示（如占位符未回填）。 */
        String warning,
        /** 参数对照表文本。 */
        String paramTable,
        /** 降级说明（多候选取首个等；无则 null）。 */
        String degraded,
        /** 结构化错误文本（成功时 null）。 */
        String error
    ) {
        /** Kotlin 默认参数等价重载：degraded/error 缺省 null。 */
        public Outcome(String sql, String sourceType, String tableName, String warning, String paramTable) {
            this(sql, sourceType, tableName, warning, paramTable, null, null);
        }
    }

    private static Outcome fail(String code, String message) {
        return new Outcome(
            null, null, null, null, null,
            null,
            McpToolErrors.code(code, message)
        );
    }

    /**
     * 「文件路径 + 行号」渲染（内部 ReadAction；行号 1-based，null = 首个可预览语句）。
     *
     * <p>路径形态经 {@link McpFilePathResolver} 统一（T-McpFilePathUnify，O-70）：项目相对路径与项目内
     * 绝对路径都收，项目外拒绝（安全边界，见该对象头注）。
     */
    public static Outcome renderAtLine(Project project, String filePath, Integer line) {
        return ReadAction.compute(() -> {
            VirtualFile vFile = McpFilePathResolver.resolveFile(project, filePath);
            if (vFile == null) {
                return fail(
                    McpToolErrors.SOURCE_NOT_FOUND,
                    "file not found in project: " + filePath + " (pass a project-relative path, "
                        + "or an absolute path inside the project)."
                );
            }
            PsiFile psiFile = PsiManager.getInstance(project).findFile(vFile);
            if (psiFile == null) {
                return fail(McpToolErrors.SOURCE_NOT_FOUND, "cannot parse file: " + filePath);
            }
            var document = psiFile.getViewProvider().getDocument();
            int offset;
            if (document == null) {
                offset = 0;
            } else if (line == null) {
                offset = 0;
            } else {
                int lastLine = document.getLineNumber(document.getTextLength());
                if (line - 1 > lastLine) {
                    return fail(
                        McpToolErrors.SOURCE_NOT_FOUND,
                        "line " + line + " is beyond the end of " + filePath + " (" + (lastLine + 1) + " lines)."
                    );
                }
                // T-McpWalkFindings S1：定位锚取**行内首个非空白字符**——gutter/编辑器标注的
                // 行号是语句标签行，而标签行行首是缩进空白 leaf（XML PSI 里挂父级 content），
                // findElementAt 沿 parentOf 爬到的是 mapper 根而非语句 tag（真机 20261005 实测：
                // line=标签行 SOURCE_NOT_FOUND、line=语句体首行命中）。行内首非空白直接落在
                // 标签/语句文本上；纯空白行顺延下一行，全落空退回行首旧行为（少新增失败模式，
                // 定位链自行报错）。
                Integer first = firstNonBlankOffset(document, line - 1);
                offset = (first != null) ? first : document.getLineStartOffset(line - 1);
            }
            return renderAtOffset(project, psiFile, offset, filePath, line);
        });
    }

    /** 纯空白行顺延上限（起始行之外最多再试 2 行：标签行与语句体之间夹空行/注释行的容错边界）。 */
    private static final int MAX_BLANK_LINE_SKIP = 2;

    /**
     * 行内首个非空白字符偏移（{@code startLine} 0-based）；该行纯空白时顺延下一行，
     * 最多越界 {@link #MAX_BLANK_LINE_SKIP} 行，全部落空返回 null。
     */
    private static Integer firstNonBlankOffset(com.intellij.openapi.editor.Document document, int startLine) {
        int textLength = document.getTextLength();
        int lastLine = document.getLineNumber(textLength);
        CharSequence chars = document.getCharsSequence();
        int line = startLine;
        int skips = 0;
        while (line <= lastLine && skips <= MAX_BLANK_LINE_SKIP) {
            int offset = document.getLineStartOffset(line);
            int end = Math.min(document.getLineEndOffset(line), textLength);
            while (offset < end && Character.isWhitespace(chars.charAt(offset))) {
                offset++;
            }
            if (offset < end) {
                return offset;
            }
            line++;
            skips++;
        }
        return null;
    }

    /**
     * mapper XML 片段直渲（XmlPreviewService 为 text 形态入口，零 PSI 锚点需求）。
     *
     * <p>T-McpRenderDialectFix：「零 PSI 锚点」不等于零 PSI 读——text 链传 project 后会走跨 ns
     * include（T-IC-02）/静态常量求值（T-M25.2）/mapper 参数类型（M29.5）三处 PSI 读，
     * 平台协程线程（MCP 通道）无读锁即硬抛 read access（2026-09-23 真机实测 + 夹具回归
     * McpRenderSnippetReadActionTest 双证）。与 {@link #renderAtLine} 同口径整链包 ReadAction。
     */
    public static Outcome renderXmlSnippet(Project project, String snippet) {
        return ReadAction.compute(() -> {
            PreviewResult result = XmlPreviewService.INSTANCE.preview(snippet, null, project);
            return toOutcome(result, "mapper XML (snippet)", null);
        });
    }

    /** 统一入口：在 psiFile 的 offset 处按 13 类触发面定位渲染。 */
    private static Outcome renderAtOffset(
        Project project,
        PsiFile psiFile,
        int offset,
        String filePath,
        Integer line
    ) {
        String location = filePath + (line != null ? ":" + line : "");
        PsiElement element = psiFile.findElementAt(Math.max(Math.min(offset, psiFile.getTextLength() - 1), 0));
        if (element == null) {
            return fail(McpToolErrors.SOURCE_NOT_FOUND, "no code element at the given location (" + location + ").");
        }

        // mapper XML：光标所在语句 tag → 语句渲染（同 ConvertToSqlAction 分发语义）
        if (psiFile instanceof XmlFile xmlFile) {
            if (xmlFile.getRootTag() != null && "pages".equals(xmlFile.getRootTag().getName())) {
                return fail(
                    McpToolErrors.SOURCE_NOT_FOUND,
                    "<pages> XML preview is not exposed via MCP; open it in the IDE."
                );
            }
            XmlTag tag = PsiTreeUtil.getParentOfType(element, XmlTag.class);
            while (tag != null && !isXmlStatementTag(tag)) {
                tag = tag.getParentTag();
            }
            if (tag == null) {
                return fail(
                    McpToolErrors.SOURCE_NOT_FOUND,
                    "no mapper statement (select/insert/update/delete) or iBatis sqlMap statement "
                        + "at the given location (" + location + ")."
                        + " Pass the line of a statement tag or any line inside it."
                );
            }
            // T-IbatisCallPreview：iBatis <sqlMap> 语句走自己的渲染链——`#name#` 语义与 MyBatis 的
            // `#{name}` 不同，交给 XmlPreviewService 会原样保留 # 而渲染成空参数。
            if (IbatisXmlGutterLineMarkerProvider.isStatementAnchor(tag)) {
                String ibatisId = tag.getAttributeValue("id");
                return toOutcome(
                        IbatisStatementPreview.previewTag(tag),
                        "iBatis sqlMap" + (ibatisId != null ? " (" + ibatisId + ")" : ""),
                        null);
            }
            String statementId = tag.getAttributeValue("id");
            // T-M27.3/4 实体上下文注入（ConvertToSqlAction.previewXml 同链）：SourceSqlContext →
            // SqlCompletionContext（namespace → Mapper 接口 → 主实体 + @TableName 联表候选）
            SourceSqlContext src = null;
            try {
                src = XmlSqlCompletionContextResolver.INSTANCE.resolveXml(project, tag);
            } catch (Throwable ignored) {
                // 解析失败降级为无实体上下文（runCatching 语义）
            }
            SqlCompletionContext entity = null;
            if (src != null && src.getMain() != null) {
                SqlCompletionContext m = src.getMain();
                // 直读公有字段（SqlCompletionContext 为公有 final 字段值对象，无 getter；master Kotlin 同形）
                entity = new SqlCompletionContext(m.tableName, m.columns, m.tableAlias,
                    m.columnComments, m.tableComment, src.getCandidates());
            }
            PreviewResult result = XmlPreviewService.INSTANCE.preview(psiFile.getText(), statementId, project, entity);
            return toOutcome(result, "mapper XML" + (statementId != null ? " (" + statementId + ")" : ""), null);
        }

        // MyBatis SQL 注解（@Select/@Update/Provider 族）——先于 Wrapper 链（注解内光标无链可定位）
        PsiAnnotation annotation = PsiTreeUtil.getParentOfType(element, PsiAnnotation.class, false);
        if (annotation != null && AnnotationSqlPreviewService.INSTANCE.isSqlAnnotation(annotation)) {
            return toOutcome(
                AnnotationSqlPreviewService.INSTANCE.preview(annotation, project),
                "annotation SQL",
                null
            );
        }

        SqlPreviewService service = project.getService(SqlPreviewService.class);
        PreviewResult wrapperPreview = service.preview(element);
        if (wrapperPreview != null) {
            return toOutcome(wrapperPreview, sourceTypeOf(wrapperPreview), null);
        }

        // HQL / 自定义 DAO HQL（同 Action 的 tryHqlFallback 顺序）
        if (HqlQueryLocator.locate(element) != null) {
            return toOutcome(service.previewHql(element), "HQL", null);
        }
        if (CustomDaoHqlLocator.locate(element) != null) {
            return toOutcome(service.previewCustomDaoHql(element), "custom DAO HQL", null);
        }

        // iBatis DAO 调用行（T-IbatisCallPreview）：与 ConvertToSqlAction.tryIbatisCallFallback 同序
        // （先于 JDBC/HQL）——JdbcTemplateSqlLocator 不含 iBatis 让位门，晚一步就会被它认领后拒绝。
        IbatisCallTarget callTarget = IbatisCallTargets.resolve(element);
        if (callTarget != null) {
            XmlTag ibatisTag = IbatisStatementPreview.resolveStatementTag(
                    project, callTarget.statementId(), callTarget.ownerNamespace());
            if (ibatisTag == null) {
                return fail(
                        McpToolErrors.SOURCE_NOT_FOUND,
                        "iBatis statement \"" + callTarget.statementId() + "\" was not found in any <sqlMap> file; "
                            + "check the statement id or the sqlMap file that declares it.");
            }
            return toOutcome(
                    IbatisStatementPreview.previewTag(ibatisTag),
                    "iBatis (" + callTarget.statementId() + ")",
                    null);
        }

        // JdbcTemplate 字符串 SQL
        if (JdbcTemplateSqlLocator.locate(element) != null) {
            return toOutcome(JdbcTemplateSqlPreviewService.preview(element, project), "JdbcTemplate", null);
        }

        // JPA：@Query → 派生查询 → CriteriaQuery（同 Action 优先序）
        PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
        if (method != null && JpqlQueryLocator.hasQueryAnnotation(method)) {
            return toOutcome(service.previewJpql(method), "JPA @Query", null);
        }
        if (method != null && DerivedMethodLocator.isDerivedQuery(method)) {
            return toOutcome(service.previewDerivedQuery(method), "JPA derived query", null);
        }
        if (CriteriaQueryLocator.locate(element) != null) {
            return toOutcome(service.previewCriteriaQuery(element), "CriteriaQuery", null);
        }

        // 方法体 iBatis 候选（T-IbatisCallPreview）：与 ConvertToSqlAction.tryIbatisMethodFallback 同序，
        // 先于方法体字符串 SQL（后者同样没有 iBatis 让位门）。零弹窗取首个可解析目标。
        if (method != null && method.getBody() != null) {
            List<IbatisCallTarget> ibatisTargets = IbatisCallTargets.callsInMethod(method);
            for (IbatisCallTarget target : ibatisTargets) {
                XmlTag ibatisTag = IbatisStatementPreview.resolveStatementTag(
                        project, target.statementId(), target.ownerNamespace());
                if (ibatisTag == null) continue;
                String degraded = ibatisTargets.size() > 1
                        ? "method body has " + ibatisTargets.size() + " iBatis calls; rendering the first resolvable one — "
                            + "pass a line number inside another call to pick it."
                        : null;
                return toOutcome(
                        IbatisStatementPreview.previewTag(ibatisTag),
                        "iBatis (" + target.statementId() + ")",
                        degraded);
            }
        }

        // 方法体字符串 SQL 多候选：零弹窗取首个（Action 弹选择框；agent 可换行号取其他候选）
        if (method != null && method.getBody() != null) {
            List<JdbcTemplateSqlLocator.JdbcTemplateSqlCall> candidates =
                JdbcTemplateSqlPreviewService.collectMethodCandidates(method);
            if (!candidates.isEmpty()) {
                PreviewResult result = JdbcTemplateSqlPreviewService.preview(candidates.get(0), method);
                String degraded = null;
                if (candidates.size() > 1) {
                    degraded = "method body has " + candidates.size() + " string-SQL candidates; rendering the first one — "
                        + "pass a line number inside another call to pick it.";
                }
                return toOutcome(result, "string SQL in method body", degraded);
            }
        }

        // 兜底线索：该行是不是 Wrapper 链候选（给 agent 一个可操作的提示）
        boolean chainHint = !WrapperLocator.sqlCandidatesInRange(psiFile, offset, offset).isEmpty();
        return fail(
            McpToolErrors.SOURCE_NOT_FOUND,
            "no renderable SQL source at " + location + "." + (chainHint
                ? " A Wrapper chain was detected nearby; retry with the exact line of the chain head call."
                : " Supported: MyBatis-Plus Wrapper chains, mapper XML statements, MyBatis annotation SQL, "
                    + "JPA (@Query/derived/CriteriaQuery), HQL, JdbcTemplate string SQL, "
                    + "iBatis <sqlMap> statements, iBatis DAO calls (SqlMapExecutor entry names with a statement id).")
        );
    }

    /** Wrapper 预览的触发面描述（wrapperType 小写 + 下划线转空格；无类型回落 Wrapper chain）。 */
    private static String sourceTypeOf(PreviewResult result) {
        if (result.getWrapperType() != null) {
            return result.getWrapperType().name().toLowerCase(Locale.ROOT).replace("_", " ");
        }
        return "Wrapper chain";
    }

    /** PsiElement 锚点直渲（Wrapper 链头已定位场景——供 toolset 内部复用）。 */
    public static Outcome previewAnchor(Project project, PsiElement anchor) {
        SqlPreviewService service = project.getService(SqlPreviewService.class);
        PreviewResult result = service.preview(anchor);
        if (result == null) {
            return fail(
                McpToolErrors.SOURCE_NOT_FOUND,
                "the given location does not resolve to a renderable SQL source."
            );
        }
        return toOutcome(result, sourceTypeOf(result), null);
    }

    private static Outcome toOutcome(PreviewResult result, String sourceType, String degraded) {
        if (result == null) {
            return fail(
                McpToolErrors.SOURCE_NOT_FOUND,
                "the source at the given location resolved to no SQL (unsupported shape)."
            );
        }
        return new Outcome(
            result.getSql(),
            sourceType,
            result.getTableName(),
            result.getWarning(),
            result.getParamTable(),
            degraded,
            null
        );
    }

    /**
     * XML 语句判定：MyBatis mapper 4 类标签 ∪ iBatis {@code <sqlMap>} 6 类语句（T-IbatisCallPreview）。
     * 两族标签名有交集（select/insert/update/delete），按父标签分流——sqlMap 直接子级走 iBatis 渲染。
     */
    private static boolean isXmlStatementTag(com.intellij.psi.xml.XmlTag tag) {
        return XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS.contains(tag.getName())
                || IbatisXmlGutterLineMarkerProvider.isStatementAnchor(tag);
    }
}