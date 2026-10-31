package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.psi.PreviewReceiverResolver;
import cn.song.mybatisplus.visualizer.psi.PsiSnapshot;
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateMethodDetector;
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlLocator;
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlPreviewService;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService;
import cn.song.mybatisplus.visualizer.ui.SqlSelectionDialog;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.List;
import javax.swing.Icon;
import kotlin.Pair;
import kotlin.Unit;

/**
 * 方法定义锚点 SQL 预览图标（T-DaoSql-04）：方法体内存在字符串 SQL 调用
 * （`beanCruder.selectList(Class, sql, ...)` / `jdbcTemplate.queryForList(sql, ...)` /
 * `selectListPagination(classType, sql, paramMap, ...)` 等）时，在<b>方法定义名</b>行显示预览图标。
 *
 * <p>覆盖用户自研框架分层场景：SQL 拼接在业务方法内组装后经 DAO 委托链执行
 * （`selectListPagination(Class, sql, ...)` → `selectListPagination(Class, PaginQuery)` →
 * `selectListPagination(PaginQuery, RowMapper)`），调用行图标无法覆盖「光标停在方法定义名」
 * 的入口诉求；本 provider 让方法定义行也具备预览入口，点击预览方法体内全部 SQL 候选
 * （0/1/多 → {@link SqlSelectionDialog} 分发）。</p>
 *
 * <p><b>性能红线</b>：daemon 快速路径锚点为方法名 {@link PsiIdentifier}（每个方法唯一 leaf），命中后做
 * <b>一次</b>方法体子树纯结构遍历（零 resolve 主路径、零索引访问；{@link PreviewReceiverResolver#hasStringLikeArg}
 * 仅对白名单命中调用生效，变量引用形态做一次 resolve，与 {@code cn.song.mybatisplus.visualizer.psi.WrapperAnchorDetector}
 * 的 `selectAll` 撞名守卫同量级）；方法体文本长度超 {@link #MAX_BODY_TEXT_LENGTH} 直接跳过
 * （极端大方法护栏）。完整聚合仅在点击时后台 ReadAction 执行。</p>
 *
 * <p><b>去重保证</b>：锚点为 PsiMethod.nameIdentifier（方法定义），与调用行 provider
 * （{@link JdbcTemplateSqlGutterLineMarkerProvider} 等锚方法调用方法名）分行显示，天然不冲突；
 * 接口/抽象方法无 body 不标。方法体扫描带家族守卫（CUSTOM_DAO/HIBERNATE_TEMPLATE/JPA_SESSION
 * 调用由专属 provider 在调用行认领，此处跳过）——防专属家族调用在方法定义行重复亮图标。</p>
 *
 * <p>注册见 plugin.xml {@code <codeInsight.lineMarkerProvider language="JAVA" .../>}。</p>
 */
public class StringSqlMethodGutterLineMarkerProvider implements LineMarkerProvider {

    /** 方法体文本长度护栏（字符）：超过则 daemon 快速路径跳过、点击按无候选处理。 */
    private static final int MAX_BODY_TEXT_LENGTH = 200_000;

    /** 券04（T-PerfSweep28-04）测试缝：引号预筛放行后的全子树遍历次数（断言预筛零触达）。 */
    static final java.util.concurrent.atomic.AtomicInteger BODY_SCAN_COUNT =
            new java.util.concurrent.atomic.AtomicInteger(0);

    private static final Icon SQL_PREVIEW_ICON =
            IconLoader.getIcon("/icons/sqlGutter.svg", StringSqlMethodGutterLineMarkerProvider.class);

    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        // F36（T-PerfSweep16）：组显隐门前置——组关时 hasStringSqlCall 方法体扫描重活零触达
        // （原门在重活判定之后，组关省不掉重活）；单次取 state，总闸与组显隐共用
        // （原双路径每到达元素 3 次 app 级查找收敛为 1 次）
        PluginSettings.State gutterState;
        try {
            gutterState = PluginSettings.getInstance().getState();
        } catch (Throwable e) { // Kotlin runCatching.getOrNull() 语义
            gutterState = null;
        }
        if (gutterState != null && !gutterState.getShowGutterIcon()) return null;
        if (!GutterGroups.enabledFor(getClass(), gutterState)) return null; // R2 分组显隐
        // LineMarker 框架只传递 leaf 元素：锚点为方法定义名 PsiIdentifier
        if (!(element instanceof PsiIdentifier)) return null;
        if (!(element.getParent() instanceof PsiMethod)) return null;
        PsiMethod method = (PsiMethod) element.getParent();
        if (method.getNameIdentifier() != element) return null;
        PsiCodeBlock body = method.getBody();
        if (body == null) return null;

        if (!hasStringSqlCall(body)) return null;

        return new LineMarkerInfo<PsiElement>(
                element,
                element.getTextRange(),
                SQL_PREVIEW_ICON,
                elt -> MpSqlVisualizerBundle.message("gutter.stringSqlMethod.tooltip"),
                StringSqlMethodNavigationHandler.INSTANCE,
                GutterIconRenderer.Alignment.CENTER,
                () -> MpSqlVisualizerBundle.message("gutter.stringSqlMethod.accessible.name"));
    }

    /**
     * 方法体内是否存在字符串 SQL 调用（daemon 快速路径判定）：
     * 白名单方法名 + 存在字符串形态实参 + <b>家族守卫</b>（T-GutterDedup 补盲区）。
     * 纯结构遍历限方法体子树。
     *
     * <p>家族守卫：CUSTOM_DAO / HIBERNATE_TEMPLATE / JPA_SESSION 家族的调用已在调用行被各自
     * 专属 provider（{@link CustomDaoHqlGutterLineMarkerProvider} 等）认领，方法定义行再亮图标即成
     * 同语句双图标（demo {@code commonDao.execute(...)} 实证）——与 {@link JdbcTemplateSqlGutterLineMarkerProvider}
     * 的家族排除制镜像，方法锚点只聚合并不归属专属家族的 JDBC / UNKNOWN 家族调用。
     */
    private static boolean hasStringSqlCall(PsiCodeBlock body) {
        // 极端大方法护栏：结构遍历成本与方法体大小线性，超限跳过（不标）
        if (body.getTextLength() > MAX_BODY_TEXT_LENGTH) return false;
        // 券04（T-PerfSweep28-04，R28-5）：引号预筛——字符串 SQL 必为字符串字面量，方法体文本
        // 不含引号即不可能有候选。单次字符扫描（零分配）替代 collectElementsOfType 全子树遍历；
        // 判据精度 100%（无引号 → 必无候选），修前每个方法定义每趟都要付一次子树遍历。
        if (!body.getText().contains("\"")) return false;
        BODY_SCAN_COUNT.incrementAndGet();
        for (PsiMethodCallExpression call : PsiTreeUtil.collectElementsOfType(body, PsiMethodCallExpression.class)) {
            if (JdbcTemplateMethodDetector.isStringSqlMethodCandidate(call.getMethodExpression().getReferenceName())
                    && PreviewReceiverResolver.hasStringLikeArg(call)) {
                PreviewReceiverResolver.Family family = PreviewReceiverResolver.classify(call);
                if (family == PreviewReceiverResolver.Family.CUSTOM_DAO
                        || family == PreviewReceiverResolver.Family.HIBERNATE_TEMPLATE
                        || family == PreviewReceiverResolver.Family.JPA_SESSION) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    /**
     * 点击处理：EDT 捕获锚点方法，候选收集走后台 ReadAction（EDT 禁止 PSI 慢操作），
     * 0/1/多候选在 EDT 分发（多候选弹 {@link SqlSelectionDialog}，交互对齐 ConvertToSqlAction 候选链）。
     */
    private enum StringSqlMethodNavigationHandler implements GutterIconNavigationHandler<PsiElement> {
        INSTANCE;

        @Override
        public void navigate(MouseEvent e, PsiElement elt) {
            PsiMethod method = PsiTreeUtil.getParentOfType(elt, PsiMethod.class, false);
            if (method == null) return;
            Project project = elt.getProject();
            previewMethodCandidates(project, method, null);
        }
    }

    /**
     * 预览方法体内字符串 SQL 候选（gutter 点击 / action 方法定义 fallback 共用入口）。
     *
     * <p>候选收集在后台 ReadAction（EDT 慢操作守卫，对齐 T-M23.8 交互），0/1/多分发在 EDT：
     * 0 → 通用未找到提示；1 → 直接预览；多 → {@link SqlSelectionDialog} 选择后预览。</p>
     *
     * @param project 当前项目
     * @param method 目标方法（候选收集范围 = 方法体）
     * @param sourceLocation 来源位置（`文件名:行号`，可空）
     */
    public static void previewMethodCandidates(Project project, PsiMethod method, String sourceLocation) {
        // T-PCP-02：捕获锚点方法快照（文件 + textRange + 文档 modificationStamp），后台读取前校验。
        // 包 ReadAction 以保留本入口「EDT / 后台均可调用」的既有语义（EDT 下 compute 内联，无额外开销）。
        PsiSnapshot snapshot = ReadAction.compute(() -> PsiSnapshot.capture(method));
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            // T-M23.8 模式：候选 + 显示行一并后台 ReadAction 预计算（弹窗只渲染）
            Pair<List<JdbcTemplateSqlLocator.JdbcTemplateSqlCall>, List<SqlSelectionDialog.Row>> payload;
            try {
                payload = ReadAction.compute(
                        () -> StringSqlMethodGutterLineMarkerProviderKt.validatedMethodRows(method, snapshot));
            } catch (Throwable t) {
                payload = new Pair<>(Collections.emptyList(), Collections.emptyList());
            }
            List<JdbcTemplateSqlLocator.JdbcTemplateSqlCall> calls = payload.getFirst();
            List<SqlSelectionDialog.Row> rows = payload.getSecond();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (calls.isEmpty()) {
                    SqlPreviewService.showResult(project, null, sourceLocation);
                } else if (calls.size() == 1) {
                    previewCall(project, calls.get(0), sourceLocation);
                } else {
                    new SqlSelectionDialog<>(project, calls, rows,
                            selected -> previewCall(project, selected, sourceLocation)).show();
                }
            });
        });
    }

    /** 预览单个候选调用（后台转换，EDT 渲染）。 */
    private static void previewCall(Project project,
                                    JdbcTemplateSqlLocator.JdbcTemplateSqlCall call,
                                    String sourceLocation) {
        SqlPreviewService.previewAsync(project,
                () -> JdbcTemplateSqlPreviewService.preview(call, call.callExpression()),
                (PreviewResult result) -> {
                    SqlPreviewService.showResult(project, result, sourceLocation);
                    return Unit.INSTANCE;
                });
    }
}
