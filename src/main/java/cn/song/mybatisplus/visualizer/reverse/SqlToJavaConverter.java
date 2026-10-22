package cn.song.mybatisplus.visualizer.reverse;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.psi.EntityClassFinder;
import cn.song.mybatisplus.visualizer.psi.EntityResolver;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;

import kotlin.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQL → Java 转换共享编排（T-M36.4）。
 *
 * parse / generateCode 为纯函数（EDT 安全）；findCandidates 等访问 PSI 索引，
 * 须在后台 ReadAction 内调用。批量转换见 splitStatements / parseBatch / findCandidatesBatch / generateBatchCode。
 */
public final class SqlToJavaConverter {

    /** 单例（Kotlin object 转换）。 */
    public static final SqlToJavaConverter INSTANCE = new SqlToJavaConverter();

    /** 逻辑删除「未删除」值默认值（与 MP `logic-not-delete-value` 默认一致）。 */
    public static final String DEFAULT_LOGIC_DELETE_VALUE = "0";

    private SqlToJavaConverter() {
    }

    /** 实体候选（类 + 已解析表信息）。 */
    public record EntityCandidate(PsiClass psiClass, TableInfo tableInfo) {
    }

    /** 单条解析结果（成功 or 失败）。 */
    public record BatchItemResult(SqlReverseResult result, SqlReverseException error, int sourceIndex) {
    }

    /** 纯解析（可能抛 [SqlReverseException]）。 */
    public static SqlReverseResult parse(String sqlText) {
        return SqlReverseParser.INSTANCE.parse(sqlText);
    }

    /** 错误偏移量 → 行号（1 起）。 */
    public static int errorLine(String sqlText, int offset) {
        return SqlReverseParser.lineOf(sqlText, offset);
    }

    // ------------------------------------------------------------------
    // T-BatchSql：批量 SQL → Java 转换
    // ------------------------------------------------------------------

    /** 按顶层分号拆分多语句文本（不拆分字符串/注释内的分号）。
     * T-SemicolonGuard（O-13）：实现收敛委托至 util/SqlStatementSplitter（与
     * LogToSqlPanel/SqlTypeClassifier 单实现合一）。 */
    public static List<String> splitStatements(String text) {
        return cn.song.mybatisplus.visualizer.util.SqlStatementSplitter.split(text);
    }

    /** 批量解析（纯函数，EDT 安全；错误隔离）。 */
    public static List<BatchItemResult> parseBatch(List<String> statements) {
        List<BatchItemResult> out = new ArrayList<>();
        for (int index = 0; index < statements.size(); index++) {
            String stmt = statements.get(index);
            try {
                out.add(new BatchItemResult(SqlReverseParser.INSTANCE.parse(stmt), null, index));
            } catch (SqlReverseException e) {
                out.add(new BatchItemResult(null, e, index));
            }
        }
        return out;
    }

    /** 批量实体查找（单次 ReadAction，所有语句共享）。 */
    public static Pair<Map<Integer, List<EntityCandidate>>, Map<Integer, Map<String, List<EntityCandidate>>>> findCandidatesBatch(
            Project project, List<BatchItemResult> results) {
        Map<Integer, List<EntityCandidate>> mainCandidates = new HashMap<>();
        Map<Integer, Map<String, List<EntityCandidate>>> subCandidates = new HashMap<>();
        for (int index = 0; index < results.size(); index++) {
            BatchItemResult item = results.get(index);
            SqlReverseResult result = item.result();
            if (result == null) continue;
            mainCandidates.put(index, findCandidates(project, result.tableName()));
            subCandidates.put(index, findSubEntityCandidates(project, result.joins()));
        }
        return new Pair<>(mainCandidates, subCandidates);
    }

    /** 批量生成代码（单条失败输出错误注释占位；Kotlin 默认参重载，logicDeleteValue = 默认值）。 */
    public static String generateBatchCode(
            List<BatchItemResult> results,
            Map<Integer, List<EntityCandidate>> mainCandidates,
            Map<Integer, Map<String, List<EntityCandidate>>> subCandidates,
            ReverseWrapperFormat format,
            JavaCodeGenerator.InvocationTarget invocation) {
        return generateBatchCode(results, mainCandidates, subCandidates, format, invocation, DEFAULT_LOGIC_DELETE_VALUE);
    }

    /** 批量生成代码（单条失败输出错误注释占位）。 */
    public static String generateBatchCode(
            List<BatchItemResult> results,
            Map<Integer, List<EntityCandidate>> mainCandidates,
            Map<Integer, Map<String, List<EntityCandidate>>> subCandidates,
            ReverseWrapperFormat format,
            JavaCodeGenerator.InvocationTarget invocation,
            String logicDeleteValue) {
        List<String> parts = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            BatchItemResult item = results.get(index);
            SqlReverseResult result = item.result();
            if (result == null) {
                SqlReverseException err = item.error();
                String message = MpSqlVisualizerBundle.message(err.getKey(), err.getDetail());
                parts.add(MpSqlVisualizerBundle.message("reverse.batch.error.placeholder", index + 1, message));
            } else {
                List<EntityCandidate> candidates = mainCandidates.getOrDefault(index, List.of());
                Map<String, List<EntityCandidate>> subs = subCandidates.getOrDefault(index, Map.of());
                Map<String, JavaCodeGenerator.EntityTarget> subEntities = new HashMap<>();
                for (Map.Entry<String, List<EntityCandidate>> en : subs.entrySet()) {
                    List<EntityCandidate> candidateList = en.getValue();
                    EntityCandidate first = candidateList.isEmpty() ? null : candidateList.get(0);
                    if (first == null) continue;
                    String className = first.psiClass().getName();
                    if (className == null) continue;
                    Map<String, String> columnToField = new HashMap<>();
                    for (ColumnInfo col : first.tableInfo().getColumns()) {
                        columnToField.put(col.getColumnName(), col.getFieldName());
                    }
                    subEntities.put(en.getKey(), new JavaCodeGenerator.EntityTarget(
                            className, columnToField, Map.of(),
                            first.tableInfo().getLogicDeleteColumn(), logicDeleteValue));
                }
                parts.add(generateCode(result, candidates.isEmpty() ? null : candidates.get(0), format, invocation,
                        subEntities, logicDeleteValue));
            }
        }
        return String.join("\n\n", parts);
    }

    /** 实体查找（须在 ReadAction 内；表名来自解析结果）。 */
    public static List<EntityCandidate> findCandidates(Project project, String tableName) {
        List<EntityCandidate> out = new ArrayList<>();
        for (PsiClass clazz : new EntityClassFinder(project).find(tableName)) {
            out.add(new EntityCandidate(clazz, new EntityResolver().resolve(clazz)));
        }
        return out;
    }

    /** 手动选择的 PsiClass → 候选（实体选择弹窗回调用）。 */
    public static EntityCandidate candidateOf(PsiClass psiClass) {
        return new EntityCandidate(psiClass, new EntityResolver().resolve(psiClass));
    }

    /** 调用类是否具备 MPJ 能力（须 ReadAction 内调用）。 */
    public static boolean invocationHasMpj(Project project, String callerClass) {
        if (callerClass == null || callerClass.isBlank()) return false;
        if ("this".equals(callerClass) || "baseMapper".equals(callerClass)) return true;
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        PsiClass[] found = PsiShortNamesCache.getInstance(project).getClassesByName(callerClass, scope);
        PsiClass clazz = found.length > 0 ? found[0] : null;
        if (clazz == null) return false;
        return hasMpjInterface(clazz, project);
    }

    /** 继承链遍历匹配 MPJ 接口。 */
    public static boolean hasMpjInterface(PsiClass clazz, Project project) {
        return hasMpjInterface(clazz, project, new HashSet<>());
    }

    /** 继承链遍历（超类 + 超接口递归）匹配 MPJ 接口。 */
    private static boolean hasMpjInterface(PsiClass clazz, Project project, Set<String> seen) {
        String qn = clazz.getQualifiedName();
        if (qn == null) return false;
        if (!seen.add(qn)) return false;
        if (MPJ_INTERFACES.contains(qn)) return true;
        for (PsiClassType type : clazz.getSuperTypes()) {
            PsiClass resolved = type.resolve();
            if (resolved != null) {
                if (hasMpjInterface(resolved, project, seen)) return true;
            } else if (matchesMpjInterfaceFallback(type, project)) {
                return true;
            }
        }
        return false;
    }

    /** resolve() 失败时的回退：先以 canonicalText 剥泛型直接匹配全限定名；未命中时按简单名全项目查找。 */
    private static boolean matchesMpjInterfaceFallback(PsiClassType type, Project project) {
        String raw = type.getCanonicalText();
        int lt = raw.indexOf('<');
        raw = lt >= 0 ? raw.substring(0, lt) : raw;
        if (MPJ_INTERFACES.contains(raw)) return true;
        String rawName = raw.substring(raw.lastIndexOf('.') + 1);
        for (PsiClass it : PsiShortNamesCache.getInstance(project).getClassesByName(rawName, GlobalSearchScope.allScope(project))) {
            if (MPJ_INTERFACES.contains(it.getQualifiedName()) || hasMpjInterface(it, project)) return true;
        }
        return false;
    }

    /** 调用类继承链方法名集合（须 ReadAction 内调用）。 */
    public static Set<String> invocationMethods(Project project, String callerClass) {
        if (callerClass == null || callerClass.isBlank()) return Set.of();
        if ("this".equals(callerClass) || "baseMapper".equals(callerClass)) return Set.of();
        PsiClass[] found = PsiShortNamesCache.getInstance(project)
                .getClassesByName(callerClass, GlobalSearchScope.projectScope(project));
        PsiClass clazz = found.length > 0 ? found[0] : null;
        if (clazz == null) return Set.of();
        return collectMethodNames(clazz, new HashSet<>());
    }

    /** 继承链方法名收集（接口 + 父类递归，seen 防循环继承）。 */
    private static Set<String> collectMethodNames(PsiClass clazz, Set<PsiClass> seen) {
        if (!seen.add(clazz)) return Set.of();
        Set<String> result = new HashSet<>();
        for (PsiMethod m : clazz.getMethods()) result.add(m.getName());
        for (PsiClass it : clazz.getInterfaces()) result.addAll(collectMethodNames(it, seen));
        PsiClass superClass = clazz.getSuperClass();
        if (superClass != null) result.addAll(collectMethodNames(superClass, seen));
        return result;
    }

    /** MPJ 1.5.6 能力接口（javap 实证全限定名）。 */
    private static final Set<String> MPJ_INTERFACES = Set.of(
            "com.github.yulichang.base.MPJBaseService",
            "com.github.yulichang.base.JoinService",
            "com.github.yulichang.base.MPJBaseMapper",
            "com.github.yulichang.base.JoinMapper"
    );

    /** 副表实体候选（T-M37 P1，须在 ReadAction 内调用）。 */
    public static Map<String, List<EntityCandidate>> findSubEntityCandidates(Project project, List<ReverseJoin> joins) {
        Map<String, List<EntityCandidate>> result = new HashMap<>();
        for (ReverseJoin join : joins) {
            String alias = join.alias();
            if (alias == null) continue;
            result.put(alias, findCandidates(project, join.tableName()));
        }
        return result;
    }

    /** 副表实体查找（T-M37 多表 JOIN，须在 ReadAction 内调用）。 */
    public static Map<String, JavaCodeGenerator.EntityTarget> findSubEntities(Project project, List<ReverseJoin> joins) {
        Map<String, JavaCodeGenerator.EntityTarget> result = new HashMap<>();
        Map<String, List<EntityCandidate>> subs = findSubEntityCandidates(project, joins);
        for (Map.Entry<String, List<EntityCandidate>> en : subs.entrySet()) {
            List<EntityCandidate> candidates = en.getValue();
            EntityCandidate first = candidates.isEmpty() ? null : candidates.get(0);
            if (first == null) continue;
            String className = first.psiClass().getName();
            if (className == null) continue;
            Map<String, String> columnToField = new HashMap<>();
            for (ColumnInfo col : first.tableInfo().getColumns()) {
                columnToField.put(col.getColumnName(), col.getFieldName());
            }
            result.put(en.getKey(), new JavaCodeGenerator.EntityTarget(className, columnToField));
        }
        return result;
    }

    /**
     * 生成代码：候选非 null → 按设置形态生成 lambda 版本；null → 字符串列名形态；多表 JOIN → 强制 MPJ 形态。
     *
     * @param format      设置页形态配置
     * @param invocation  调用上下文（null → 纯表达式）
     * @param subEntities T-M37：联表副实体（别名 → 实体）
     */
    public static String generateCode(
            SqlReverseResult result,
            EntityCandidate candidate,
            ReverseWrapperFormat format,
            JavaCodeGenerator.InvocationTarget invocation,
            Map<String, JavaCodeGenerator.EntityTarget> subEntities) {
        return generateCode(result, candidate, format, invocation, subEntities, DEFAULT_LOGIC_DELETE_VALUE);
    }

    /**
     * 生成代码（T-ReverseGroupLogicDelete：logicDeleteValue 全参形态）。
     *
     * @param format          设置页形态配置（QUERY 形态的调用方应跳过实体查找直接传 null 候选）
     * @param invocation      T-M36.7：调用上下文（Service/Mapper + 可选 VO），null → 纯表达式
     * @param subEntities     T-M37：联表副实体（别名 → 实体），多表列归属用
     * @param logicDeleteValue T-ReverseGroupLogicDelete：逻辑删除「未删除」值（设置页
     *                        {@code logicDeleteValue}，默认 "0"）——生成器据此识别 MP 自动注入的
     *                        {@code AND <列> = <值>} 并跳过；由调用方读取配置传入，保持本组件纯函数。
     */
    public static String generateCode(
            SqlReverseResult result,
            EntityCandidate candidate,
            ReverseWrapperFormat format,
            JavaCodeGenerator.InvocationTarget invocation,
            Map<String, JavaCodeGenerator.EntityTarget> subEntities,
            String logicDeleteValue) {
        boolean isJoin = !result.joins().isEmpty();
        GeneratedWrapper wrapper = isJoin && candidate != null ? GeneratedWrapper.MPJ_LAMBDA
                : isJoin ? GeneratedWrapper.MPJ_QUERY
                : WrapperFormatKt.resolveWrapperFormat(format, candidate != null);
        JavaCodeGenerator.EntityTarget target = null;
        if (candidate != null) {
            String className = candidate.psiClass().getName() != null
                    ? candidate.psiClass().getName() : JavaCodeGenerator.entityClassName(result.tableName());
            Map<String, String> columnToField = new HashMap<>();
            for (ColumnInfo col : candidate.tableInfo().getColumns()) {
                columnToField.put(col.getColumnName(), col.getFieldName());
            }
            target = new JavaCodeGenerator.EntityTarget(className, columnToField, subEntities,
                    candidate.tableInfo().getLogicDeleteColumn(), logicDeleteValue);
        }
        return JavaCodeGenerator.generate(result, target, wrapper, invocation);
    }
}
