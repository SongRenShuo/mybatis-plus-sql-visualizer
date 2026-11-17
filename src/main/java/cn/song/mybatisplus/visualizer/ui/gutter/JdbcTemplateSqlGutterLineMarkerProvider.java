package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateMethodDetector;
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlLocator;
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlPreviewService;
import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.psi.PreviewReceiverResolver;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.MouseEvent;
import javax.swing.Icon;

/**
 * JdbcTemplate / NamedParameterJdbcTemplate SQL 字符串预览水印图标（JDBC 模板 SQL 字符串预览）。
 *
 * <p>在 `queryForList` / `update` / `queryForObject` / `execute` 等 JDBC 模板方法调用行显示预览图标，
 * 点击弹出 SQL 预览（复用 {@link SqlPreviewService} 弹窗链路，支持命名参数回填）。
 *
 * <p><b>性能红线</b>：快速路径只做 O(1) 语法判定——锚点为方法名 {@link PsiIdentifier}，方法名 ∈
 * JdbcTemplateMethodDetector.JDBC_TEMPLATE_METHODS 且第 1 实参存在；零 resolve、零聚合、
 * 零转换；完整解析（字符串聚合 / 命名参数识别）仅在点击时走 SqlPreviewService.previewAsync。
 *
 * <p><b>去重保证</b>：与既有 SqlGutterLineMarkerProvider（Wrapper 链）、
 * AnnotationSqlGutterLineMarkerProvider（@Select 注解）、
 * CustomDaoHqlGutterLineMarkerProvider（自定义 DAO HQL）互不冲突——方法名集合天然区分
 * （`query*` 是 Spring JDBC 模板方法名，`createQuery`/`findByQuery` 是 Hibernate / 自定义 DAO）。
 *
 * <p>注册见 plugin.xml `codeInsight.lineMarkerProvider language="JAVA"`。
 */
public class JdbcTemplateSqlGutterLineMarkerProvider implements LineMarkerProvider {

    private static final Icon SQL_PREVIEW_ICON =
            IconLoader.getIcon("/icons/sqlGutter.svg", JdbcTemplateSqlGutterLineMarkerProvider.class);

    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        // LineMarker 框架只传递 leaf 元素：锚点为 PsiIdentifier（方法名 leaf）。
        if (!(element instanceof PsiIdentifier)) return null;

        // 锚定在方法调用的方法名 PsiIdentifier 上：
        // element = "queryForList" 等方法名 identifier
        // element.parent = PsiReferenceExpression（方法调用的方法名引用）
        // element.parent.parent = PsiMethodCallExpression
        PsiElement parentRaw = element.getParent();
        if (!(parentRaw instanceof PsiReferenceExpression)) return null;
        PsiReferenceExpression parent = (PsiReferenceExpression) parentRaw;
        PsiElement grandParentRaw = parent.getParent();
        if (!(grandParentRaw instanceof PsiMethodCallExpression)) return null;
        PsiMethodCallExpression grandParent = (PsiMethodCallExpression) grandParentRaw;

        // 确认 element 是方法名而非 qualifier
        String refName = grandParent.getMethodExpression().getReferenceName();
        if (!element.getText().equals(refName)) return null;

        // 方法名 ∈ 字符串 SQL 方法候选（Spring JDBC 模板 ∪ 自研 DAO select 族，O(1) 判定，T-DaoSql-01）
        if (!JdbcTemplateMethodDetector.isStringSqlMethodCandidate(element.getText())) return null;

        // T-IbatisFix3 缺陷一：iBatis 优先、JDBC 模板让位——`SqlMapExecutor` 的入口名
        // （queryForObject/queryForList/queryForMap/update/delete）与本 provider 的白名单**完全重叠**，
        // 且本 provider 不查接收者类型；`client.queryForObject("User.selectById", id)` 于是被两条通道
        // 同时认领（本 provider 的预览图标 + IbatisJavaToXmlLineMarkerProvider 的跳转箭头叠在同一枚
        // 方法名 identifier 上 → 用户看到两个图标）。让位判据是**纯语法 O(1)**（见
        // IbatisCallYieldGate.isIbatisStatementIdCall）：零 resolve、零项目扫描、零类型推断。
        if (IbatisCallYieldGate.INSTANCE.isIbatisStatementIdCall(grandParent, element.getText())) return null;

        // T-GutterDedup + T-DaoSql-03：家族排除制守卫——CUSTOM_DAO / HIBERNATE_TEMPLATE / JPA_SESSION
        // 各有专属 provider（防双图标），拒绝；JDBC_TEMPLATE 与 UNKNOWN（自研框架 `beanCruder` 等
        // 无法归类接收者）通过。误标由「存在字符串形态实参」+ 点击时 isLikelySql 双层拦截。
        PreviewReceiverResolver.Family family = PreviewReceiverResolver.classify(grandParent);
        if (family == PreviewReceiverResolver.Family.CUSTOM_DAO
                || family == PreviewReceiverResolver.Family.HIBERNATE_TEMPLATE
                || family == PreviewReceiverResolver.Family.JPA_SESSION) {
            return null;
        }

        // T-DaoSql-02：存在字符串形态实参（自研框架 (Class, sql, ...) 形态 SQL 在第 2 参），
        // 杜绝 `execute(callback)` / MP select 族（Wrapper 实参）误标
        if (!PreviewReceiverResolver.hasStringLikeArg(grandParent)) return null;

        // 开关关闭时不显示。F36（T-PerfSweep16）：单次取 state——本 provider 在总闸之外还受
        // jdbcSqlPreviewEnabled 约束（原 gutterEnabled 语义等价保留：失败回退按 true），总闸/组显隐/
        // JDBC 预览开关共用这一次读取（原双路径每到达元素 3 次 app 级查找收敛为 1 次）
        PluginSettings.State gutterState;
        try {
            gutterState = PluginSettings.getInstance().getState();
        } catch (Throwable e) { // Kotlin runCatching.getOrNull() 语义
            gutterState = null;
        }
        if (gutterState != null && (!gutterState.getShowGutterIcon() || !gutterState.getJdbcSqlPreviewEnabled())) {
            return null;
        }
        if (!GutterGroups.enabledFor(getClass(), gutterState)) return null; // R2 分组显隐

        return new LineMarkerInfo<PsiElement>(
                element,
                element.getTextRange(),
                SQL_PREVIEW_ICON,
                elt -> MpSqlVisualizerBundle.message("gutter.jdbcTemplate.tooltip"),
                JdbcTemplateGutterNavigationHandler.INSTANCE,
                GutterIconRenderer.Alignment.CENTER,
                () -> MpSqlVisualizerBundle.message("gutter.jdbcTemplate.accessible.name"));
    }

    /**
     * 点击处理：在 EDT 捕获锚点元素，转换走后台线程 + ReadAction（EDT 禁止 PSI 慢操作），
     * 结果按显示方式配置展示。
     */
    private enum JdbcTemplateGutterNavigationHandler implements GutterIconNavigationHandler<PsiElement> {
        INSTANCE;

        @Override
        public void navigate(MouseEvent e, PsiElement elt) {
            Project project = elt.getProject();
            SqlPreviewService.previewAsync(project, () -> {
                JdbcTemplateSqlLocator.JdbcTemplateSqlCall call = JdbcTemplateSqlLocator.INSTANCE.locate(elt);
                if (call != null) {
                    return JdbcTemplateSqlPreviewService.preview(call, elt);
                }
                return null;
            }, (PreviewResult result) -> {
                SqlPreviewService.showResult(project, result);
                return kotlin.Unit.INSTANCE;
            });
        }
    }
}
