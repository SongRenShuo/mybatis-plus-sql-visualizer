package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.xml.EwPlaceholder;
import cn.song.mybatisplus.visualizer.xml.XmlEwExpander;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MP 占位符 Java 侧关联（T-M26-B，P-立项-03）：`${ew.customSqlSegment}` 等占位 → wrapper 实参 → SQL 片段。
 */
public final class XmlEwResolver {

    public static final XmlEwResolver INSTANCE = new XmlEwResolver();

    private XmlEwResolver() {
    }

    /** 调用处查找器（可注入：生产用 ReferencesSearch，测试用轻量查找）。 */
    public interface CallFinder {
        @NotNull
        List<PsiElement> find(@NotNull PsiMethod method);
    }

    @NotNull
    public Map<String, Map<String, String>> resolve(
            @Nullable String namespace,
            @NotNull String statementId,
            @NotNull List<EwPlaceholder> placeholders,
            @NotNull Project project,
            @NotNull List<String> warnings) {
        return resolve(namespace, statementId, placeholders, project, warnings, XmlEwResolver::defaultCallFinder);
    }

    @NotNull
    public Map<String, Map<String, String>> resolve(
            @Nullable String namespace,
            @NotNull String statementId,
            @NotNull List<EwPlaceholder> placeholders,
            @NotNull Project project,
            @NotNull List<String> warnings,
            @NotNull CallFinder callFinder) {
        if (placeholders.isEmpty()) return java.util.Collections.emptyMap();
        if (namespace == null || namespace.isEmpty()) {
            warnings.add("mapper 缺 namespace，无法关联 Java Mapper 方法展开 ${" + placeholders.get(0).variable() + "...}");
            return java.util.Collections.emptyMap();
        }
        PsiClass mapperClass = JavaPsiFacade.getInstance(project)
                .findClass(namespace, GlobalSearchScope.allScope(project));
        if (mapperClass == null) {
            warnings.add("找不到 Mapper 接口 " + namespace + "，无法展开 MP 占位 ${" + placeholders.get(0).variable() + "...}");
            return java.util.Collections.emptyMap();
        }
        PsiMethod[] methods = mapperClass.findMethodsByName(statementId, false);
        if (methods.length == 0) {
            warnings.add("Mapper 接口 " + namespace + " 无方法 " + statementId + "，无法展开 MP 占位");
            return java.util.Collections.emptyMap();
        }
        PsiMethod method = methods[0];

        PsiParameter wrapperParam = findWrapperParam(method, placeholders);
        if (wrapperParam == null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < placeholders.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(placeholders.get(i).variable());
            }
            warnings.add("方法 " + statementId + " 无 Wrapper 参数（" + sb + "），无法展开 MP 占位");
            return java.util.Collections.emptyMap();
        }

        List<PsiElement> calls = callFinder.find(method);
        if (calls.isEmpty()) {
            warnings.add("未找到 Mapper 方法 " + statementId + " 的调用，无法展开 MP 占位（可在预览中填写覆盖值）");
            return java.util.Collections.emptyMap();
        }
        if (calls.size() > 1) {
            warnings.add("Mapper 方法 " + statementId + " 有 " + calls.size() + " 处调用，取第一处展开");
        }
        PsiElement call = calls.get(0);
        PsiMethodCallExpression callExpression;
        if (call instanceof PsiMethodCallExpression) {
            callExpression = (PsiMethodCallExpression) call;
        } else {
            callExpression = PsiTreeUtil.getParentOfType(call, PsiMethodCallExpression.class);
            if (callExpression == null) return java.util.Collections.emptyMap();
        }
        PsiParameter[] params = method.getParameterList().getParameters();
        int argIndex = -1;
        for (int i = 0; i < params.length; i++) {
            if (params[i].equals(wrapperParam)) {
                argIndex = i;
                break;
            }
        }
        PsiExpression[] args = callExpression.getArgumentList().getExpressions();
        if (argIndex < 0 || argIndex >= args.length) return java.util.Collections.emptyMap();
        PsiExpression arg = args[argIndex];

        SqlPreviewService service = project.getService(SqlPreviewService.class);
        PreviewResult preview = service.preview(arg);
        if (preview == null) return java.util.Collections.emptyMap();
        String sql = preview.getSql();
        Map<String, Map<String, String>> result = new HashMap<>();
        result.put(wrapperParamName(wrapperParam), extractFragments(sql, placeholders));
        return result;
    }

    @NotNull
    private static List<PsiElement> defaultCallFinder(@NotNull PsiMethod method) {
        List<PsiElement> result = new ArrayList<>();
        // T-StartupReadLockFreeze S5（同族面）：ReferencesSearch 依赖索引，索引构建期会同步等待
        // ——预览装配线程在这里持锁等索引，撞上 EDT 的写锁升级就是冻结（计划 §三）。
        // 与 MapperNavigationTargets 同口径：dumb 时降级为空（预览退回占位提示），不做检索。
        long startedMs = System.currentTimeMillis();
        if (com.intellij.openapi.project.DumbService.isDumb(method.getProject())) {
            com.intellij.openapi.diagnostic.Logger.getInstance(XmlEwResolver.class).info(
                    "[m2] resolve skip reason=dumb-mode stage=ew-refs elapsedMs="
                            + (System.currentTimeMillis() - startedMs));
            return result;
        }
        for (com.intellij.psi.PsiReference ref : ReferencesSearch.search(method).findAll()) {
            result.add(ref.getElement());
        }
        return result;
    }

    /** 按片段类型提取（同变量多个占位 → 各自片段）。 */
    private Map<String, String> extractFragments(String sql, List<EwPlaceholder> placeholders) {
        Map<String, String> fragments = new HashMap<>();
        for (EwPlaceholder ph : placeholders) {
            String fragment;
            switch (ph.kind()) {
                case "customSqlSegment":
                    fragment = XmlEwExpander.extractWhere(sql);
                    break;
                case "sqlSelect":
                    fragment = XmlEwExpander.extractSelect(sql);
                    break;
                default:
                    fragment = XmlEwExpander.extractSet(sql);
            }
            if (fragment != null) fragments.put(ph.kind(), fragment);
        }
        return fragments;
    }

    /** 定位 wrapper 参数：@Param 名命中占位变量优先；否则类型简单名含 Wrapper。 */
    private PsiParameter findWrapperParam(PsiMethod method, List<EwPlaceholder> placeholders) {
        PsiParameter[] params = method.getParameterList().getParameters();
        for (EwPlaceholder ph : placeholders) {
            for (PsiParameter param : params) {
                for (PsiAnnotation anno : param.getAnnotations()) {
                    String qn = anno.getQualifiedName();
                    String simple = qn != null ? qn.substring(qn.lastIndexOf('.') + 1) : null;
                    if ("Param".equals(simple)) {
                        PsiAnnotationMemberValue v = anno.findAttributeValue(null);
                        String text = v != null ? v.getText() : null;
                        String trimmed = text != null ? text.replace("\"", "").trim() : null;
                        if (trimmed != null && trimmed.equals(ph.variable())) return param;
                    }
                }
            }
        }
        for (PsiParameter param : params) {
            String canonical = param.getType().getCanonicalText();
            String simple = canonical.substring(canonical.lastIndexOf('.') + 1);
            if (simple.contains("Wrapper")) return param;
        }
        return null;
    }

    /** wrapper 参数名（@Param 值优先，用于 mock 变量名对齐占位）。 */
    public String wrapperParamName(PsiParameter param) {
        for (PsiAnnotation anno : param.getAnnotations()) {
            String qn = anno.getQualifiedName();
            String simple = qn != null ? qn.substring(qn.lastIndexOf('.') + 1) : null;
            if ("Param".equals(simple)) {
                PsiAnnotationMemberValue v = anno.findAttributeValue(null);
                if (v != null) {
                    String t = v.getText().replace("\"", "").trim();
                    if (t != null && !t.isEmpty()) return t;
                }
            }
        }
        return param.getName();
    }
}
