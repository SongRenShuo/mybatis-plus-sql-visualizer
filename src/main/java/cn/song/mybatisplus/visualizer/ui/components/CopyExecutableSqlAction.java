// T-SyncK2JMaster115 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/components/CopyExecutableSqlAction.kt 移植（T-PSG-04 头部标记改标记列表 + `?`/参数条数对账）
package cn.song.mybatisplus.visualizer.ui.components;

import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer;
import cn.song.mybatisplus.visualizer.sql.PlaceholderCardinality;
import cn.song.mybatisplus.visualizer.sql.PlaceholderPositionsKt;
import cn.song.mybatisplus.visualizer.sql.RuntimeSlot;
import cn.song.mybatisplus.visualizer.sql.RuntimeSlotValues;
import cn.song.mybatisplus.visualizer.sql.SlotWarning;
import cn.song.mybatisplus.visualizer.sql.SqlCommentStripper;
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一键复制可执行 SQL（O-4 Phase A）。
 *
 * <p>取当前面板 SQL（含参数回填与手动编辑），将 {@code ?} 替换为带类型处理的字面值
 * （复用 {@link ParameterReplacer#buildExecutableSql}），复制到系统剪贴板并 toast 提示。
 *
 * <p>复制统一走 {@link UiTextClipboard}（平台 CopyPasteManager）：在 headless 测试环境下
 * 剪贴板写入不影响复制逻辑本身。</p>
 *
 * <p>T-RuntimeSlotFill 券 B：运行期占位必须<b>在剥注释之前</b>填掉——占位本身就是一条块注释，
 * 先剥就再也定位不到（会留下 {@code WHERE ( )}）。产物组装收敛到 {@link #assembleProduct}（纯函数），
 * 与「分享 / 执行 / 导出」共用 {@code ExecutableSqlFormatter} 同一条回填链，保证四处文本一致。</p>
 */
public final class CopyExecutableSqlAction {

    /** 通知分组 id（plugin.xml 注册）。 */
    private static final String NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview";

    /**
     * T-InjectionGate T-IG-03：产物含 `${}` raw 段时的头部标记注释。
     *
     * <p>复制不是执行——这条链不弹确认（弹窗克制红线：模态确认只留给执行），改让风险随产物走：
     * 产物粘到别处时读者能看到 raw 段带的是模拟值。SQL 注释行（`--`）不改产物可执行性。</p>
     */
    public static final String DOLLAR_RAW_MARKER = "-- contains ${} raw segments (mock values)";

    /**
     * 复制链产物（纯数据，{@code execute} 与链路一致用例共用）。
     *
     * @param text                    真正写进剪贴板的文本（含头部标记行）
     * @param slotsApplied            被填进 SQL 的运行期占位 id
     * @param warnings                占位没填进去的原因（面板可展示）
     * @param replacedPlaceholderCount 被替换掉的 {@code ?} 个数（toast 用）
     * @param mismatchMarker          占位/参数条数不符时的头部标注行（null = 无）
     */
    public static final class Product {
        public final String text;
        public final List<String> slotsApplied;
        public final List<SlotWarning> warnings;
        public final int replacedPlaceholderCount;
        @Nullable
        public final String mismatchMarker;

        public Product(String text, List<String> slotsApplied, List<SlotWarning> warnings,
                       int replacedPlaceholderCount, @Nullable String mismatchMarker) {
            this.text = text;
            this.slotsApplied = slotsApplied;
            this.warnings = warnings;
            this.replacedPlaceholderCount = replacedPlaceholderCount;
            this.mismatchMarker = mismatchMarker;
        }
    }

    /**
     * 组装复制产物（<b>纯函数</b>，零 UI / 零剪贴板依赖）。
     *
     * <p>顺序（P-2 / 券 B 设计要点 3）：运行期占位 → {@code ?}（一次扫描，由
     * {@link ParameterReplacer#buildExecutableSqlWithSlots} 完成）→ 剥注释。</p>
     */
    public static Product assembleProduct(
            @NotNull String rawSql,
            @NotNull List<RuntimeSlot> slots,
            @NotNull RuntimeSlotValues slotValues,
            @NotNull List<String> sources,
            @NotNull Map<String, String> paramValues,
            boolean hasDollarRawSegments,
            @Nullable String dialectName) {
        ParameterReplacer.RuntimeSlotRender render = ParameterReplacer.buildExecutableSqlWithSlots(
                rawSql, slots, slotValues, sources, paramValues, List.of(), Map.of(), dialectName);
        // 复制时剥离 SQL 注释（与 copySql 对齐）；占位已在上一步换成字面量，不会再被剥掉
        String executable = SqlCommentStripper.strip(render.getSql());
        String strippedRaw = SqlCommentStripper.strip(rawSql);

        // T-PSG-04（N-33）：`?` 位点数与参数条数对账——产物必然残留无参数可填的裸 `?` 时在头部
        // 如实标注（不阻断）。只认「占位符多于参数」这一方向：本链拼的是面板**已回填**文本，
        // 位点少于参数是常态（见 PlaceholderCardinality.excessPlaceholderMarker 的 KDoc）。
        String mismatch = sources.isEmpty()
                ? null
                : PlaceholderCardinality.excessPlaceholderMarker(
                        PlaceholderPositionsKt.placeholderPositions(strippedRaw).size(), sources.size());

        // T-InjectionGate T-IG-03：含 `${}` raw 段时产物头部加标记注释；无标记时产物逐字节不变
        List<String> markers = new ArrayList<>();
        if (hasDollarRawSegments) markers.add(DOLLAR_RAW_MARKER);
        if (mismatch != null) markers.add(mismatch);
        String text = markers.isEmpty() ? executable : String.join("\n", markers) + "\n" + executable;
        return new Product(text, render.getAppliedSlotIds(), render.getWarnings(),
                countReplaced(strippedRaw, executable), mismatch);
    }

    /** Kotlin 默认参等价重载：slots/slotValues/sources/paramValues 缺省（行为零变化形态）。 */
    public static Product assembleProduct(@NotNull String rawSql) {
        return assembleProduct(rawSql, List.of(), RuntimeSlotValues.EMPTY, List.of(), Map.of(), false, null);
    }

    /** 统计被替换的 {@code ?} 数量（原始 SQL 中真实占位数 - 结果中真实占位数；注释/字符串里的 {@code ?} 不计）。 */
    private static int countReplaced(@NotNull String original, @NotNull String result) {
        int originalCount = PlaceholderPositionsKt.placeholderPositions(original).size();
        int resultCount = PlaceholderPositionsKt.placeholderPositions(result).size();
        return Math.max(originalCount - resultCount, 0);
    }

    private final Project project;

    public CopyExecutableSqlAction(@Nullable Project project) {
        this.project = project;
    }

    /** Kotlin 默认参等价重载：{@code slotValues} 缺省空表 = 占位原样保留（行为零变化）。 */
    public boolean execute(@NotNull SqlPreviewPanel panel) {
        return execute(panel, RuntimeSlotValues.EMPTY);
    }

    /**
     * 执行复制：取面板当前 SQL + 参数值 → 回填占位与参数 → 剪贴板 → toast。
     *
     * @param panel      预览面板（取 SQL / 参数值 / 回填提示 / 运行期占位）
     * @param slotValues 运行期占位的用户输入；默认空表 = 占位原样保留（行为零变化）
     * @return 是否成功复制
     */
    public boolean execute(@NotNull SqlPreviewPanel panel, @NotNull RuntimeSlotValues slotValues) {
        String raw = panel.editorDocumentText();
        if (raw == null || raw.isBlank()) {
            showToast(MpSqlVisualizerBundle.message("preview.copyExecutableSql.nothing"));
            return false;
        }
        PreviewResult current = panel.current;
        Product product = assembleProduct(
                raw,
                // 跨层依赖（model/ 波次 PreviewResult 尾参增量）：runtimeSlots 按 master 签名照译
                current != null ? current.getRuntimeSlots() : List.of(),
                slotValues,
                // T-JpaParamOrder F3：JPA 占位符名序优先（按名回填），与面板渲染同口径
                panel.backfillSources(),
                panel.paramValuesSnapshot(),
                panel.hasDollarRawSegments(),
                current != null && current.getEffectiveDialect() != null
                        ? current.getEffectiveDialect().getName() : null);

        copyToClipboard(product.text);

        String message = product.replacedPlaceholderCount == 0 && product.slotsApplied.isEmpty()
                ? MpSqlVisualizerBundle.message("preview.copyExecutableSql.noParams")
                : MpSqlVisualizerBundle.message("preview.copyExecutableSql.done", product.replacedPlaceholderCount);
        showToast(message);
        return true;
    }

    /** 复制文本到系统剪贴板（统一走 UiTextClipboard 收口）。 */
    private void copyToClipboard(@NotNull String text) {
        UiTextClipboard.copy(text);
    }

    /** Toast 通知（仅在非测试模式下显示，测试模式跳过避免通知系统依赖）。 */
    private void showToast(@NotNull String message) {
        if (ApplicationManager.getApplication().isUnitTestMode()) {
            return;
        }
        if (project == null) {
            return;
        }
        try {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(message, NotificationType.INFORMATION)
                    .notify(project);
        } catch (Throwable ignored) {
            // 与原 runCatching 等价
        }
    }
}
