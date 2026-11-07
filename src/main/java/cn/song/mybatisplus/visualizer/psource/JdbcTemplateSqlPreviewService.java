package cn.song.mybatisplus.visualizer.psource;

import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer;
import cn.song.mybatisplus.visualizer.sql.PlaceholderPositionsKt;
import cn.song.mybatisplus.visualizer.sql.RuntimeSlot;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JDBC 模板 SQL 字符串预览服务（正向入口）：
 * JdbcTemplateSqlLocator + SqlStringConcatenator + NamedParamParser → {@link PreviewResult}。
 *
 * <p>组装规则（对齐 {@code cn.song.mybatisplus.visualizer.ui.AnnotationSqlPreviewService.buildResult}）：</p>
 * <ul>
 *  <li>SQL 文本经 {@link SqlFormatter#format} 格式化（多行关键字换行），末尾加 {@code ;}（与注解预览一致）</li>
 *  <li>命名参数（{@code :CUR_USER} / {@code :flowKey}）→ {@code unresolvedParams}（面板参数行）+ {@code namedParamHints}</li>
 *  <li>可执行 SQL 生成时走 {@link ParameterReplacer#replaceNamedParams}（按名回填）+ 残留 {@code ?} 顺序回填</li>
 *  <li>含运行时拼片段 → {@code warning} 降级提示</li>
 * </ul>
 *
 * <p>入口：gutter 点击 / ConvertToSqlAction 光标定位 → {@code cn.song.mybatisplus.visualizer.ui.SqlPreviewService.preview}</p>
 */
public final class JdbcTemplateSqlPreviewService {

    /** Kotlin object 单例兼容入口。 */
    public static final JdbcTemplateSqlPreviewService INSTANCE = new JdbcTemplateSqlPreviewService();

    private JdbcTemplateSqlPreviewService() {
    }

    /** SQL 末尾分隔符（与注解预览 / 日志还原一致）。 */
    private static final String SQL_TERMINATOR = ";";

    /**
     * 预览 JDBC 模板 SQL 字符串（gutter / action 通用入口）。
     *
     * @param anchor 光标所在 PSI 元素
     * @param project 当前项目（保留扩展点，暂未使用）
     * @return 预览结果；未命中返回 null
     */
    public static PreviewResult preview(PsiElement anchor) {
        return preview(anchor, null);
    }

    /**
     * 预览 JDBC 模板 SQL 字符串（gutter / action 通用入口）。
     *
     * @param anchor 光标所在 PSI 元素
     * @param project 当前项目（保留扩展点，暂未使用）
     * @return 预览结果；未命中返回 null
     */
    public static PreviewResult preview(PsiElement anchor, Project project) {
        JdbcTemplateSqlLocator.JdbcTemplateSqlCall call = JdbcTemplateSqlLocator.locate(anchor);
        if (call == null) {
            return null;
        }
        return preview(call, anchor);
    }

    /**
     * 收集方法体内全部可静态定位的字符串 SQL 调用候选（T-DaoSql-04，单一来源）。
     *
     * <p>供方法定义 gutter 点击（{@code cn.song.mybatisplus.visualizer.ui.gutter.StringSqlMethodGutterLineMarkerProvider}）
     * 与 action 方法定义 fallback 共用；须在后台线程 ReadAction 内调用。</p>
     *
     * <p>候选 = 方法体内「方法名 ∈ 字符串 SQL 方法候选」且 {@link JdbcTemplateSqlLocator#locateFromCall}
     * 聚合成功的调用（按文件偏移升序，与方法内出现顺序一致）。聚合失败的调用
     * （SQL 为参数传递 / 运行时构造形态）不在候选内——MVP 边界，见开发计划「已知边界」。</p>
     *
     * @param method 目标方法
     * @return 候选调用列表（可能为空）
     */
    public static List<JdbcTemplateSqlLocator.JdbcTemplateSqlCall> collectMethodCandidates(PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        if (body == null) {
            return Collections.emptyList();
        }
        List<JdbcTemplateSqlLocator.JdbcTemplateSqlCall> result = new ArrayList<>();
        for (PsiMethodCallExpression call : PsiTreeUtil.collectElementsOfType(body, PsiMethodCallExpression.class)) {
            if (JdbcTemplateMethodDetector.isStringSqlMethodCandidate(call.getMethodExpression().getReferenceName())) {
                JdbcTemplateSqlLocator.JdbcTemplateSqlCall located = JdbcTemplateSqlLocator.locateFromCall(call);
                if (located != null) {
                    result.add(located);
                }
            }
        }
        result.sort(Comparator.comparingInt(c -> c.callExpression().getTextRange().getStartOffset()));
        return result;
    }

    /**
     * 预览从已定位的 JdbcTemplate 调用组装结果。
     *
     * @param call 已定位的 JdbcTemplate SQL 调用
     * @param anchor 原始锚点元素（用于错误信息）
     */
    public static PreviewResult preview(JdbcTemplateSqlLocator.JdbcTemplateSqlCall call, PsiElement anchor) {
        String sqlText = call.sqlString();
        if (sqlText.isBlank()) {
            return null;
        }
        // SQL 形态校验：误将通用字符串方法（list.insert("x")、log.delete("...") 等）当 SQL 预览——
        // 仅在聚合文本像是可执行 SQL 时才接住，否则返回 null（对齐 T-M20-08 防误伤语义）
        if (!isLikelySql(sqlText)) {
            return null;
        }

        List<String> warnings = new ArrayList<>();
        if (!call.isLiteral()) {
            warnings.add("SQL 含运行时拼接片段（方法返回值 / 外部字段等），完整 SQL 在运行期才确定；仅预览静态片段。");
        }

        // 收集命名参数
        List<NamedParam> namedParams = NamedParamParser.collectNamedParams(sqlText);
        List<String> namedSources = new ArrayList<>();
        Map<String, ParamHint> namedHints = new LinkedHashMap<>();
        for (NamedParam p : namedParams) {
            namedSources.add(p.name());
            namedHints.put(p.name(), NamedParamParser.quoteHint(p));
        }

        // `?` 占位符来源（按出现顺序，`?` 数量）——用 string-aware 位点扫描：块注释里的
        // 三元问号（`/* 运行时值: status == null ? … */`）与字符串字面量里的 `?` 都不是 JDBC 占位符。
        // 朴素 count 会造出没有落点的 `#param1`（T-RuntimeSlotOptionalFill）。
        int placeholderCount = PlaceholderPositionsKt.placeholderPositions(sqlText).size();
        List<String> placeholderSources = new ArrayList<>();
        if (placeholderCount > 0) {
            for (int i = 1; i <= placeholderCount; i++) {
                placeholderSources.add("param" + i);
            }
        }

        // 合并参数来源：命名参数在前（按名回填），`?` 占位符在后（按序回填）
        List<String> unresolvedParams = new ArrayList<>(namedSources);
        unresolvedParams.addAll(placeholderSources);

        // 合并引号提示（与 unresolvedParams 按序对应）
        List<ParamHint> namedParamHintsList = new ArrayList<>();
        for (NamedParam p : namedParams) {
            namedParamHintsList.add(NamedParamParser.quoteHint(p));
        }
        List<ParamHint> placeholderHints = new ArrayList<>();
        for (String ignored : placeholderSources) {
            placeholderHints.add(new ParamHint()); // 无类型标注走面板启发式
        }
        List<ParamHint> paramHints = new ArrayList<>(namedParamHintsList);
        paramHints.addAll(placeholderHints);

        // 格式化 SQL（多行关键字换行），末尾加分隔符
        String formattedSql;
        try {
            formattedSql = SqlFormatter.format(sqlText).stripTrailing() + SQL_TERMINATOR;
        } catch (Throwable e) {
            formattedSql = sqlText.stripTrailing() + SQL_TERMINATOR;
        }
        String compactSql = sqlText.stripTrailing() + SQL_TERMINATOR;

        // 参数对照表（供 UI 展示）
        String paramTable = ParameterReplacer.buildParamTable(unresolvedParams);

        return new PreviewResult(
                formattedSql,
                compactSql,
                unresolvedParams,
                paramTable,
                null,
                extractFirstTableName(sqlText),
                warnings.isEmpty() ? null : warnings.get(0),
                Collections.emptyList(),
                Collections.emptyMap(),
                null,
                null,
                paramHints,
                Collections.emptyList(),
                null,
                null,
                Collections.emptyMap(),
                null,
                null,
                null,
                null,
                namedHints,
                collectRuntimeSlots(call)
        );
    }

    /**
     * 重新聚合一次拿到结构化运行期占位（T-RuntimeSlotFill 券 A）。
     *
     * <p>为什么要重算：{@code JdbcTemplateSqlLocator.JdbcTemplateSqlCall} 只带聚合后的<b>文本</b>，而 slot 的
     * id、模板、类别只能由拼接器产出。重算对同一 PSI 是确定性的，且与 {@code call.sqlString} 逐字相同；
     * 一旦两者不一致（理论上不该发生——两边走的是同一个 {@code SqlStringConcatenator.concat}），
     * 就放弃 slot：宁可面板上没有可填值，也不能给出一份与 SQL 文本错位的身份。</p>
     */
    private static List<RuntimeSlot> collectRuntimeSlots(JdbcTemplateSqlLocator.JdbcTemplateSqlCall call) {
        SqlStringConcatenator.ConcatenationResult aggregated = SqlStringConcatenator.concat(call.sqlExpr());
        if (aggregated == null) return List.of();
        if (!aggregated.sql().equals(call.sqlString())) return List.of();
        return aggregated.runtimeSlots();
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** SQL 语句特征关键字（转小写比对）。匹配任意即视为 SQL，避免把通用字符串方法当 SQL 预览。 */
    private static final List<String> SQL_KEYWORDS = List.of(
            "select", "insert", "update", "delete", "with", "values", "create", "alter",
            "drop", "truncate", "merge", "replace", "grant", "revoke", "show", "describe",
            "explain", "call", "begin", "commit", "rollback"
    );

    /** 语句内部结构词（不限于开头，覆盖以 `(`/注释开头的 SQL、子查询等）。 */
    private static final List<String> SQL_FRAGMENTS = List.of(
            " from ", " into ", " set ", " where ", " join ", " left join ", " right join ",
            " order by ", " group by ", " having ", " limit ", " union ", " values (",
            " select ", " insert into ", " update ", " delete from "
    );

    /**
     * SQL 形态校验：聚合后的字符串是否<b>像可执行的 SQL</b>。
     *
     * <p>防误成分略（对齐 T-M20-08）：`insert`/`delete`/`update` 等是通用方法名，
     * `list.insert("x")`、`log.delete("...")` 这类第 1 实参为普通字符串的调用不应触发
     * SQL 预览。判定规则（大小写不敏感，去掉首尾空白后）：</p>
     * <ul>
     *  <li>以 SQL 语句关键字开头（select/insert/update/delete/with...）</li>
     *  <li>或含语句内结构词（from/into/set/where/join...）——覆盖以 `(`/注释开头的情形</li>
     * </ul>
     * <p>两者皆不满足 → false（非 SQL，不预览）。</p>
     */
    private static boolean isLikelySql(String text) {
        String t = text.strip();
        if (t.isEmpty()) {
            return false;
        }
        String lower = t.toLowerCase(Locale.ROOT);
        for (String keyword : SQL_KEYWORDS) {
            if (lower.startsWith(keyword)) {
                return true;
            }
        }
        for (String fragment : SQL_FRAGMENTS) {
            if (lower.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** 启发式提取首个表名（FROM / JOIN 后首个标识符）；提取失败返回 null。 */
    private static String extractFirstTableName(String sql) {
        String upper = sql.toUpperCase(Locale.ROOT);
        int fromIdx = upper.indexOf("FROM");
        if (fromIdx < 0) {
            return null;
        }
        String afterFrom = sql.substring(fromIdx + 4).stripLeading();
        // 取首个非空白字符序列（表名或子查询 `(`）
        String[] tokens = afterFrom.split("\\s+");
        String firstToken = tokens.length > 0 ? tokens[0].strip() : null;
        if (firstToken == null) {
            return null;
        }
        if (firstToken.startsWith("(")) {
            return null; // 子查询，暂不展开
        }
        // 去除可能的别名（如 `WKFL_TASK WT` → `WKFL_TASK`）
        int backtickIdx = firstToken.indexOf('`');
        String result = backtickIdx >= 0 ? firstToken.substring(0, backtickIdx) : firstToken;
        int bracketIdx = result.indexOf('[');
        result = bracketIdx >= 0 ? result.substring(0, bracketIdx) : result;
        return result.isBlank() ? null : result;
    }
}
