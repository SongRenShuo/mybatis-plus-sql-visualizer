// 自 master ui/annotate/PreviewUnknownColumnAnnotator.kt 移植（UI025：ExternalAnnotator 通道）
package cn.song.mybatisplus.visualizer.ui.annotate;

import cn.song.mybatisplus.visualizer.execute.DataSourceSelector;
import cn.song.mybatisplus.visualizer.execute.SchemaValidationGate;
import cn.song.mybatisplus.visualizer.sql.schema.SchemaValidator;
import cn.song.mybatisplus.visualizer.ui.inlay.PreviewInlayScope;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 标注作用域：<b>只认本插件预览面板的 SQL 编辑器</b>。
 *
 * <p>两条紧致判据（任一成立）：</p>
 * <ol>
 *  <li><b>预览临时文件</b>：复用 {@link PreviewInlayScope#isPreviewFileName}——一级形态
 *      {@code mpsql-preview-<pid>-<seq>.sql} 四段全中，二级 {@code mpsql-preview-memory.sql} 精确匹配；
 *      不是 {@code startsWith("mpsql-preview")} 这类宽松前缀（用户自建同前缀文件不得命中）；</li>
 *  <li><b>原生控制台执行文件</b>：{@code <project>/.idea/sql-visualizer/exec/mpsql-exec.sql}
 *      （{@link DataSourceSelector#isExecVfPath} 精确路径判据）。预览面板在 Ultimate 下真正展示的是
 *      挂在这个文件上的原生控制台编辑器；只认插件自有路径，用户在其他目录自建的
 *      {@code mpsql-exec.sql} 不满足路径判据，不会被标注。</li>
 * </ol>
 *
 * <p>为什么不做「所有 SQL 文件」：ExternalAnnotator 的 EP 是 language 级的，注册 {@code language="SQL"}
 * 后 IDE 内所有 SQL 编辑器都会向本 annotator 索取结果。作用域闸门是唯一的「不打扰用户自己的
 * {@code .sql} 文件」防线，必须在第一行就判定。</p>
 */
final class PreviewSchemaAnnotationScope {

    private PreviewSchemaAnnotationScope() {
    }

    /** 是否为本插件预览面板的 SQL 编辑器载体（预览临时文件或原生控制台 exec 文件）。 */
    static boolean isPreviewSurface(@Nullable PsiFile file) {
        if (file == null) {
            return false;
        }
        if (PreviewInlayScope.isPreviewFileName(file.getName())) {
            return true;
        }
        if (file.getVirtualFile() == null) {
            return false;
        }
        String path = file.getVirtualFile().getPath();
        return DataSourceSelector.isExecVfPath(path);
    }

    /**
     * 是否位于项目配置目录（{@code .idea}）下。
     *
     * <p>平台不把配置目录里的文件交给 daemon（测试实证：{@code .idea/sql-visualizer/exec/mpsql-exec.sql}
     * 的 ExternalAnnotator 永不触发，而同一内容放到 {@code sql-visualizer/exec/} 下立即触发）。
     * 本插件预览/执行临时文件恰好都在 {@code .idea/sql-visualizer/} 下，故 ExternalAnnotator 只作
     * 非配置目录的通用通道；配置目录内的真实编辑器由 {@link PreviewSchemaHighlighter} 的
     * RangeHighlighter 通道兜底。</p>
     */
    static boolean isProjectConfigSurface(@Nullable PsiFile file) {
        if (file == null || file.getVirtualFile() == null) {
            return false;
        }
        String path = file.getVirtualFile().getPath();
        return path.contains("/.idea/");
    }
}

/**
 * 预览编辑器「表中无此列」标注（UI025）。
 *
 * <h2>数据来源（只读、零 DB I/O）</h2>
 *
 * 目标优先取自 {@link DataSourceSelector#schemaValidationTarget}：<b>只认当前已备/共享控制台的执行文档</b>
 * （文档身份相等 + 控制台 isValid + MySQL/MariaDB 的 URL 库与当前库一致）。仅当它返回 null 时，
 * 才回落到 {@link PreviewFallbackTarget}——面板指示行已展示的「数据源（上次使用）」记忆，且要求
 * MySQL/MariaDB、库名非空、目标缓存已热。列集只读
 * {@code cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver#columnNamesCachedForTarget}
 * 的已预热缓存（经 {@code SchemaValidationGate.check(Project, String, Target)} 三参入口），
 * <b>绝不触发内省/建连</b>。
 *
 * <p>从 SQL 日志条目打开的预览没有已备控制台，旧入口恒为 null——回落分支正是为该场景而设
 * （UI029）；用户自己的 {@code .sql} 文件仍在作用域闸门处被挡下，不经过回落。</p>
 *
 * <h2>作用域与诚实降级</h2>
 *
 * <p>判定完全复用 {@code SchemaValidationGate.check} 的精确目标与 {@link SchemaValidator} 的 SQL 作用域规则：
 * 多语句、JOIN、CTE、子查询、限定列、显式库名、缓存冷、非 MySQL/MariaDB 目标一律
 * {@code Skipped}/空清单——<b>宁缺勿错</b>。标注位点由 {@link PreviewUnknownColumnPlanner} 从 JSqlParser
 * AST token 位置换算，不用字符串 {@code indexOf} 猜。</p>
 *
 * <h2>生命周期</h2>
 *
 * <p>ExternalAnnotator 由平台在文档变更后重跑；每次 {@code apply} 只投递本次结果，平台会清掉上一轮
 * 外部标注，因此编辑后不会残留旧波浪线。{@code collectInformation} 取不到目标时返回 null，同样
 * 使平台清空旧标注。</p>
 */
public class PreviewUnknownColumnAnnotator extends
        ExternalAnnotator<Object, List<PreviewUnknownColumnPlanner.Mark>> {

    /** collect 阶段冻结的输入：项目 + 文档文本 + 已证实的执行目标。 */
    private record Info(@NotNull Project project, @NotNull String text,
                        @NotNull SchemaValidationGate.Target target) {
    }

    /**
     * 无 Editor 的批量路径拿不到「文档 → 已备控制台」身份，故不产结果（宁缺勿错）。
     * 真机 daemon 走带 Editor 的重载。
     */
    @Override
    public Object collectInformation(@NotNull PsiFile file) {
        return null;
    }

    @Override
    public Object collectInformation(@NotNull PsiFile file, @NotNull Editor editor, boolean hasErrors) {
        if (!PreviewSchemaAnnotationScope.isPreviewSurface(file)) {
            return null;
        }
        String text = editor.getDocument().getText();
        if (text.isBlank()) {
            return null;
        }
        // 旧入口零变化：仅在其返回 null 时，才用面板自身已确定的目标（last-used 记忆）回落。
        SchemaValidationGate.Target target = DataSourceSelector.schemaValidationTarget(editor.getDocument());
        if (target == null) {
            target = PreviewFallbackTarget.resolve(file.getProject(), text);
        }
        if (target == null) {
            return null;
        }
        return new Info(file.getProject(), text, target);
    }

    @Override
    public List<PreviewUnknownColumnPlanner.Mark> doAnnotate(Object collectedInfo) {
        if (!(collectedInfo instanceof Info info)) {
            return List.of();
        }
        SchemaValidationGate.Outcome outcome;
        try {
            outcome = SchemaValidationGate.check(info.project(), info.text(), info.target());
        } catch (Throwable t) {
            if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) {
                throw (Error) t;
            }
            return List.of();
        }
        if (!(outcome instanceof SchemaValidationGate.Checked checked)) {
            return List.of();
        }
        List<SchemaValidator.Issue> issues = checked.issues();
        return PreviewUnknownColumnPlanner.plan(info.text(), issues);
    }

    @Override
    public void apply(@NotNull PsiFile file, List<PreviewUnknownColumnPlanner.Mark> annotationResult,
                      @NotNull AnnotationHolder holder) {
        int length = file.getTextLength();
        for (PreviewUnknownColumnPlanner.Mark mark : safeMarks(annotationResult)) {
            if (mark.startOffset() < 0 || mark.endOffset() > length
                    || mark.startOffset() >= mark.endOffset()) {
                continue;
            }
            holder.newAnnotation(HighlightSeverity.ERROR, PreviewSchemaHighlighterKt.previewUnknownColumnMessage(mark))
                    .range(new TextRange(mark.startOffset(), mark.endOffset()))
                    .create();
        }
    }

    @NotNull
    private static List<PreviewUnknownColumnPlanner.Mark> safeMarks(
            @Nullable List<PreviewUnknownColumnPlanner.Mark> marks) {
        if (marks == null || marks.isEmpty()) {
            return List.of();
        }
        return marks;
    }
}
