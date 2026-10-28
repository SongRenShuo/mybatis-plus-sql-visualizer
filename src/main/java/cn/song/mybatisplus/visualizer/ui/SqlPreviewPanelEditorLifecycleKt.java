// T-SyncK2JMaster482 波D2 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelEditorLifecycle.kt
// （T-M53-II 2026-10-10：SqlPreviewPanel 的「预览编辑器装配、预热链与临时文件回收」内聚成员，
//   以同包扩展函数形式搬出——方法体逐字符搬运，被触及的私有成员已按需放宽为 internal）
// 扩展函数 fun SqlPreviewPanel.foo(...) → public static R foo(SqlPreviewPanel receiver, ...)。
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContributor;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlSyntaxHighlighter;
import cn.song.mybatisplus.visualizer.ui.inlay.PreviewInlayScope;
import cn.song.mybatisplus.visualizer.util.EditorCommentShortcuts;
import cn.song.mybatisplus.visualizer.util.EditorCopyShortcuts;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiManager;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import kotlin.Pair;
import kotlin.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code SqlPreviewPanelEditorLifecycle.kt}（T-M53-II 拆分件）的 Java 承载类——扩展函数
 * {@code fun SqlPreviewPanel.foo(...)} 落为 {@code public static R foo(SqlPreviewPanel receiver, ...)}。
 *
 * <p>含预览编辑器装配、预热链与临时文件回收相关成员。</p>
 */
public final class SqlPreviewPanelEditorLifecycleKt {

    private SqlPreviewPanelEditorLifecycleKt() {
    }

    public static void prewarmPreviewTempFile(@NotNull SqlPreviewPanel p) {
        Logger LOG = Logger.getInstance(SqlPreviewPanel.class);
        // T-PerfTail #2：预热线程产出 PrewarmAwait 完成态（成功 complete / 失败 fail）——
        // 消费侧（createEditor）不再 EDT sleep-poll
        // T-FixN1（N1 写安全）：派发模态由 any() 改 defaultModalityState()（写安全模态）。回调体是
        // resolvePreviewEditorSource 的 onReady/onFallback → assemblePreviewEditor，其内**写文档**
        // （WriteCommandAction { d.setText(sql) } + commitDocument）——而 any() **恒 write-unsafe**。
        // T-PrewarmLateRecover F1：轮次令牌与迟到产物接收方都必须在预热线程可能发布**之前**就位。
        final int round = ++p.prewarmRound;
        PrewarmAwait<Pair<VirtualFile, PsiFile>> await =
                new PrewarmAwait<>(SqlPreviewPanel.PREWARM_WAIT_MS, p.prewarmDispatcher);
        await.onLateProduct(product -> p.prewarmDispatcher.accept(() -> tryUpgradeToLateProduct(p, product)));
        p.prewarmAwait = await;
        // T-StabilityTail：把「项目开始销毁」接到取消门——池线程不得比项目活得更久
        bindPrewarmToProjectLifecycle(p);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            // T-SqlDialectPrewarm：本线程先完成 SQL 方言映射的一次性加载（BGT 上做，EDT 上做即平台慢操作
            // 断言）。顺序意图：预热产物 await 完成 → EDT 才装配预览编辑器（createEditor(doc, proj,
            // sqlFileType, …) 会按 SQL FileType 取高亮器）——排在产物之前即继承这个先后关系。
            // 放在耗时打点之前：prewarmStarteAt 量的仍是临时文件产物的自身耗时，不被这次预热污染。
            if (p.project != null) {
                cn.song.mybatisplus.visualizer.ui.highlight.SqlDialectMappingPrewarm.prewarmNow(p.project);
            }
            // T-PrewarmBudget（2026-09-20）：预热**自身耗时**打点起点
            long prewarmStartedAt = System.nanoTime();
            if (p.previewTempFile != null || prewarmAbandoned(p)) {
                await.fail(new IllegalStateException("prewarm skipped: disposed or already prepared"));
                return;
            }
            try {
                // T-PreviewExecDs-01a：预览临时文件迁项目内 .idea/sql-visualizer/preview/
                File io = projectPreviewFile(p);
                if (io == null) {
                    p.prewarmFailed = true;
                    await.fail(new IllegalStateException("project preview file unavailable"));
                    LOG.warn("[M36] prewarm FAILED in " + prewarmElapsedMs(p, prewarmStartedAt)
                            + "ms: project preview file unavailable (project/basePath null or mkdirs false)");
                    return;
                }
                // T-GifRerecord：VFS 忙时 refreshAndFindFileByIoFile 偶发查不到新文件——重试到 3 次带退避
                LocalFileSystem lfs = LocalFileSystem.getInstance();
                VirtualFile vf = lfs.refreshAndFindFileByIoFile(io);
                int retries = 0;
                while (vf == null && retries < 3 && !prewarmAbandoned(p)) {
                    Thread.sleep(100L * (retries + 1));
                    vf = lfs.refreshAndFindFileByIoFile(io);
                    retries++;
                }
                if (vf == null) {
                    p.prewarmFailed = true;
                    await.fail(new IllegalStateException("VFS refresh null after " + (retries + 1) + " tries"));
                    LOG.warn("[M36] prewarm FAILED in " + prewarmElapsedMs(p, prewarmStartedAt)
                            + "ms: VFS refresh null after " + (retries + 1) + " tries (" + io.getPath()
                            + ", exists=" + io.exists() + ")");
                    return;
                }
                if (prewarmAbandoned(p)) {
                    new File(vf.getPath()).delete();
                    await.fail(new IllegalStateException("disposed during prewarm"));
                    return;
                }
                Project proj = p.project;
                if (proj == null) return;
                // T-StabilityTail：紧贴取服务再判一次——从上一处判定到这里之间项目可能已开始销毁
                if (prewarmAbandoned(p)) {
                    await.fail(new IllegalStateException("abandoned before service lookup"));
                    return;
                }
                // T-PreviewExecDs-01c：方言映射须在 findFile/PSI 创建前（映射决定 PSI 语言）
                mapPreviewDialectBeforePsi(p, proj, vf);
                // T-StabilityTail：判据放读动作**内部**才管用——平台读动作可被 closeProject 的写动作阻塞，
                // 放行时窗口已开；在外面判会漏掉这条窄缝。
                final VirtualFile vfForPsi = vf;
                PsiFile psi = ReadAction.compute(
                        (com.intellij.openapi.util.ThrowableComputable<PsiFile, Throwable>) () ->
                                prewarmAbandoned(p) ? null : PsiManager.getInstance(proj).findFile(vfForPsi));
                if (psi == null) {
                    // 放弃（项目/面板已销毁）与真失败分开记账：前者不置 prewarmFailed、不记 WARN
                    if (prewarmAbandoned(p)) {
                        await.fail(new IllegalStateException("abandoned during findFile"));
                        return;
                    }
                    p.prewarmFailed = true;
                    await.fail(new IllegalStateException("PsiManager.findFile null for " + vf.getName()));
                    LOG.warn("[M36] prewarm FAILED in " + prewarmElapsedMs(p, prewarmStartedAt)
                            + "ms: PsiManager.findFile null for " + vf.getName());
                    return;
                }
                p.previewTempFile = vf;
                publishPrewarmProduct(p, await, new Pair<>(vf, psi), prewarmStartedAt, round);
            } catch (Throwable e) {
                p.prewarmFailed = true;
                await.fail(e);
                LOG.warn("[M36] prewarm FAILED in " + prewarmElapsedMs(p, prewarmStartedAt) + "ms: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            }
        });
    }

    /**
     * T-PreviewDocLock2 F3 回归修复：预热产物**发布**——字段与 await 必须拿到**同一个 Pair 实例**。
     *
     * <p>消费侧 {@link #assemblePreviewEditor} 以「引用相等 + 随即置 null」表达「产物尚未被消费」，
     * 故发布侧不能把 {@code vf to psi} 写两遍（原实现各造一个实例 → {@code ===} 恒假 → 主路径恒判
     * 「产物不可用」，预览对话框每次打开都降级到内存 PSI 并挂降级横幅）。抽成本方法后「同一实例」
     * 由单次构造保证。</p>
     */
    public static void publishPrewarmProduct(
            @NotNull SqlPreviewPanel p,
            @NotNull PrewarmAwait<Pair<VirtualFile, PsiFile>> await,
            @NotNull Pair<VirtualFile, PsiFile> product,
            long startedAtNanos,
            int round) {
        // T-PrewarmBudget（2026-09-20）：预热**成功**耗时打点
        Logger.getInstance(SqlPreviewPanel.class)
                .info("[M36] prewarm done in " + prewarmElapsedMs(p, startedAtNanos) + "ms "
                        + "(file=" + product.getFirst().getName() + ", psi=" + product.getSecond().getName() + ")");
        // T-PrewarmLateRecover F1：旧轮产物不入字段（见 prewarmRound）——文件仍由 releaseEditor 按登记表回收
        if (round != p.prewarmRound) {
            Logger.getInstance(SqlPreviewPanel.class)
                    .info("[M36] prewarm product dropped: stale round=" + round + " (current=" + p.prewarmRound + ")");
            return;
        }
        p.prewarmedTempFile = product;
        // T-PrewarmLateRecover F1：`complete` 返回 false = 消费侧已放弃（超时以 null 完成 / 失败），
        // 此时产物走迟到通道换装。
        if (!await.complete(product)) {
            p.prewarmDispatcher.accept(() -> tryUpgradeToLateProduct(p, product));
        }
    }

    /** T-PrewarmBudget：预热打点用耗时（毫秒）；{@code nanoTime} 差值，不受系统时钟回拨影响。 */
    public static long prewarmElapsedMs(@NotNull SqlPreviewPanel p, long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    /**
     * T-StabilityTail：把「项目开始销毁」接到预热取消门（{@link #prewarmAbandoned} 的项目侧判据）。
     *
     * <p>**为什么挂 {@code ProjectEx.getEarlyDisposable()}**——平台 KDoc 原文即此用途。
     * **为什么不挂 {@code Disposer.register(project, …)}**：light 项目的**主** disposable 在片内跨测试方法
     * 一直存活，挂主树等于整轮不触发。**取消而非 join**：门一置，预热线程在其下一个检查点放弃。
     * **降级**：earlyDisposable 树的取入口是 {@code @ApiStatus.Internal}，取树改走**反射**，
     * 并用 {@link Disposer#tryRegister} 加外层 runCatching 兜底；反射失败或挂不上时静默返回。</p>
     */
    public static void bindPrewarmToProjectLifecycle(@NotNull SqlPreviewPanel p) {
        if (p.prewarmLifecycleGate != null) return; // 已挂：clear() 重预热不重复登记
        Project proj = p.project;
        if (proj == null) return;
        Disposable gate = () -> p.previewDisposed = true;
        boolean registered;
        try {
            // T-MarketCompatProxy：getEarlyDisposable 标 @ApiStatus.Internal 且无公开等价——
            // 反射取树（仅此一处调用），消除 verifier 的 internal API usage。
            Disposable early;
            try {
                Object earlyObj = proj.getClass().getMethod("getEarlyDisposable").invoke(proj);
                early = earlyObj instanceof Disposable ? (Disposable) earlyObj : null;
            } catch (Throwable t) {
                early = null;
            }
            if (early == null) {
                registered = false;
            } else {
                registered = Disposer.tryRegister(early, gate);
            }
        } catch (Throwable t) {
            registered = false;
        }
        if (registered) p.prewarmLifecycleGate = gate;
    }

    /**
     * T-StabilityTail：预热线程的「放弃」判据——面板已销毁 或 项目已开始销毁。
     *
     * <p>项目侧判据取 {@code Project.isDisposed}：平台口径为 {@code super.isDisposed() || isTemporarilyDisposed}，
     * temporarily-disposed 窗口内为真。它在这里是**门判据的补充**而非「盲目守卫」。</p>
     */
    public static boolean prewarmAbandoned(@NotNull SqlPreviewPanel p) {
        return p.previewDisposed || (p.project != null && p.project.isDisposed());
    }

    /**
     * T-PreviewExecDs-01a：项目内预览临时文件——{@code <project>/.idea/sql-visualizer/preview/}。
     * 独立子目录（exec/ 互不污染，同 M36.5.13 语义）。VFS 安全写入三态（document 驱动 &gt;
     * setBinaryContent &gt; FileUtil 兜底），杜绝 MemoryDiskConflictResolver 的 File Cache Conflict 弹窗。
     *
     * <p>T-FixN2：文件名由固定 {@code mpsql-preview.sql} 改为**每次预热唯一**（见
     * {@code SqlPreviewPanel.nextPreviewTempFileName}）。唯一文件名让每次预热都落在**没有任何 document
     * 的全新路径**上 → 首次创建裸写安全，冲突链从根上消失。建文件即登记进
     * {@code previewTempFilesCreated}，由 {@link #releaseEditor} 回收。</p>
     */
    public static File projectPreviewFile(@NotNull SqlPreviewPanel p) throws java.io.IOException {
        Project proj = p.project;
        if (proj == null) return null;
        String base = proj.getBasePath();
        if (base == null) return null;
        File dir = new File(
                base,
                ".idea" + File.separator + cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.DATA_DIR_NAME
                        + File.separator + "preview");
        if (!dir.exists() && !dir.mkdirs()) return null;
        // T-PerfSweep6 T-PS6-04（发现 4）：本面板首次解析预热目录时做一次目录级兜底清扫
        // （年龄 > 7 天 / 条数 > 200），只认本插件自有命名——见 {@link #sweepStalePreviewFiles}。
        // 每次面板生命周期只扫一次（清扫是兜底，不必每次预览都付一遍 listFiles）。
        if (p.previewDirSwept.compareAndSet(false, true)) {
            sweepStalePreviewFiles(p, dir);
        }
        // T-FixN2 × T-InsertColumnInlay O-44b 合并口径：文件名**唯一**且**由 inlay 作用域锚点同一份
        // 常量生成**——锚点靠文件名形态判定「是不是本面板预览编辑器」。
        File f = new File(dir, SqlPreviewPanel.nextPreviewTempFileName());
        VirtualFile existing = LocalFileSystem.getInstance().findFileByIoFile(f);
        if (existing != null) {
            boolean ok;
            try {
                final VirtualFile existingF = existing;
                WriteAction.compute((com.intellij.openapi.util.ThrowableComputable<Unit, Throwable>) () -> {
                    Document fileDoc = com.intellij.openapi.fileEditor.FileDocumentManager
                            .getInstance().getDocument(existingF);
                    if (fileDoc != null) {
                        fileDoc.setText(""); // 预热占位空内容，createEditor 装配时按实际 SQL 填充
                    } else {
                        existingF.setBinaryContent(new byte[0]);
                    }
                    return Unit.INSTANCE;
                });
                ok = true;
            } catch (Throwable t) {
                ok = false;
            }
            if (ok) {
                p.previewTempFilesCreated.add(f);
                return f;
            }
        }
        com.intellij.openapi.util.io.FileUtil.writeToFile(f, "");
        p.previewTempFilesCreated.add(f);
        return f;
    }

    /**
     * T-PreviewExecDs-01c：预览文件方言预映射（exec 路径 DataSourceSelector.mapDialectBeforePsi 同款）——
     * PSI 创建时即按映射方言解析，Generic SQL 下 Database 补全不触发。
     *
     * <p>T-FixN2：映射对象由「文件」改为**父目录**（文件名每次预热唯一后，按文件映射会让每个新文件名
     * 都判「未映射」）。平台 {@code PerFileMappingsBase.getMappingInner → getMappingForHierarchy}
     * 自下而上逐级查表，故**目录映射被其下所有文件继承**；{@code previewDialectMappedVf} 随之记
     * 「已映射的那个目录 vf」，身份判定比较的是目录。目录 vf 取不到（极端）时退回按文件映射。</p>
     */
    public static void mapPreviewDialectBeforePsi(@NotNull SqlPreviewPanel p, @NotNull Project proj,
                                                  @NotNull VirtualFile vf) {
        VirtualFile target = SqlPreviewPanel.previewDialectMappingTarget(vf);
        boolean inProcessMapped = p.previewDialectMappedVf != null && p.previewDialectMappedVf.isValid()
                && p.previewDialectMappedVf == target;
        if (cn.song.mybatisplus.visualizer.execute.DialectPreMapping.ensureMapped(proj, target, inProcessMapped)) {
            p.previewDialectMappedVf = target;
        }
    }

    /**
     * T-PerfTail #2：预热产物消费决策（替代原 {@code awaitPrewarmedTempFile} 的 EDT sleep-poll）。
     *
     * <ul>
     *  <li>**热态（结果已出）**：同步取产物装配——主路径（成功）或快速降级（失败），EDT 零等待；</li>
     *  <li>**冷态（预热线程未完成）**：{@code scheduleOnce} 注册完成回调，超时预算语义保留；</li>
     *  <li>**单元测试模式**：退回同步有界等待（awaitOrNullForTest，生产路径不走）。</li>
     * </ul>
     *
     * @return 产物（热态主路径非 null）；null = 本调用不装配（回调挂起中）或快速降级。
     */
    @NotNull
    public static Pair<Boolean, Pair<VirtualFile, PsiFile>> resolvePreviewEditorSource(
            @NotNull SqlPreviewPanel p, @NotNull String sql) {
        PrewarmAwait<Pair<VirtualFile, PsiFile>> await = p.prewarmAwait;
        if (await == null) return new Pair<>(true, null);
        if (await.isDone()) return new Pair<>(true, await.joinOrNull());
        if (ApplicationManager.getApplication().isUnitTestMode()) {
            return new Pair<>(true, await.awaitOrNullForTest());
        }
        // 冷态：注册回调（幂等），本次渲染不装配（编辑器挂起至回调完成，≤ 超时预算）
        await.scheduleOnce(
                pair -> {
                    if (!p.previewDisposed) assemblePreviewEditor(p, sql, pair);
                },
                () -> {
                    if (!p.previewDisposed) assemblePreviewEditor(p, sql, null);
                });
        return new Pair<>(false, null);
    }

    /** T-DarkTheme：主题变更回调——重绘面板（命名色已自动更新，仅需触发 UI 刷新）。 */
    public static void lookAndFeelChanged(@NotNull SqlPreviewPanel p) {
        p.revalidate();
        p.repaint();
    }

    public static void createEditor(@NotNull SqlPreviewPanel p, @NotNull String sql) {
        // T-RefreshDbSteal：createEditor 是症状①的特征终点（自建编辑器带行号，DBN 控制台无），
        // 但真机故障期它零日志（71s 空窗）——带栈埋点定调用人路径，兜底断言非用户路径
        Logger.getInstance(SqlPreviewPanel.class)
                .info("[M36] createEditor: len=" + sql.length() + ", nativeDoc=" + (p.nativeEditorDoc != null)
                        + ", embedded=" + System.identityHashCode(p.embeddedConsole),
                        new Throwable("createEditor path"));
        Project proj = p.project;
        if (proj == null) {
            assemblePreviewEditor(p, sql, null);
            return;
        }
        // T-PerfTail #2：预热产物消费决策——热态同步装配、冷态注册回调回 EDT 装配、单元测试模式同步有界等待
        Pair<Boolean, Pair<VirtualFile, PsiFile>> resolved = resolvePreviewEditorSource(p, sql);
        if (!resolved.getFirst()) {
            // T-PrewarmBudget：装配延后至预热回调——这期间编辑区本来是**纯空面板**，故补一个轻量
            // 「准备中」占位；回调走到 assemblePreviewEditor 时它会 removeAll 掉，无需另行清理。
            showPreviewPreparingPlaceholder(p);
            return;
        }
        assemblePreviewEditor(p, sql, resolved.getSecond());
    }

    /**
     * T-PrewarmBudget：预热等待期的编辑区占位。
     *
     * <p>装配延后到预热回调时，编辑区原本是**纯空面板**——真机连拍实测亮像素计数 0，冷启动空窗
     * 584~926ms 肉眼可见。这里放一个居中的轻量提示，回调装配时由 {@link #assemblePreviewEditor} 的
     * {@code editorHolder.removeAll()} 自然替换，无需另行清理。</p>
     *
     * <p>用 {@code GridBagLayout} 居中而非直接 add：{@code editorHolder} 是 {@code GridLayout(1,1)}，
     * 直接加会把标签拉满整格（文字贴左上角），与「准备中」应有的视觉不符。</p>
     */
    public static void showPreviewPreparingPlaceholder(@NotNull SqlPreviewPanel p) {
        JBPanel<JBPanel<?>> box = new JBPanel<JBPanel<?>>(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.anchor = GridBagConstraints.CENTER;
        constraints.weightx = 1.0;
        constraints.weighty = 1.0;
        box.add(new JBLabel(MpSqlVisualizerBundle.message("panel.preview.preparing")), constraints);
        p.editorHolder.removeAll();
        p.editorHolder.add(box);
        p.editorHolder.revalidate();
        p.editorHolder.repaint();
    }

    /** T-PrewarmBudget：等待期占位（断言用）——显示路径的观测口（容器观测口复用既有的 editorHolderForTest）。 */
    public static void showPreparingPlaceholderForTest(@NotNull SqlPreviewPanel p) {
        showPreviewPreparingPlaceholder(p);
    }

    /**
     * T-PerfTail #2：编辑器装配（原 createEditor 后半抽取，同步主路径与异步回调共用同一收口）。
     *
     * @param prewarmed 预热产物（项目内文件 + PSI）；null → 降级链（内存 PSI → 裸 document）。
     *   主路径对 {@code prewarmedTempFile} 做**一次性消费**（同引用校验）——releaseEditor 后二次
     *   createEditor 时产物已被消费，与原 await 一次性语义一致地走降级。
     */
    public static void assemblePreviewEditor(@NotNull SqlPreviewPanel p, @NotNull String sql,
                                             @Nullable Pair<VirtualFile, PsiFile> prewarmed) {
        Project proj = p.project;
        FileType sqlFileType = FileTypeManager.getInstance().getStdFileType("SQL");
        Document doc;
        if (proj != null) {
            // T-PreviewDocLock2 F3：临时文件支链「尽力」——产物缺失时降级到内存 PSI → 裸文档。
            // T-PreviewDocLock2 F3：`==` 是「本次交付的产物就是字段里那份未被消费的产物」——
            // 实例由 publishPrewarmProduct 单次构造保证。
            if (prewarmed != null && p.prewarmedTempFile == prewarmed) {
                p.prewarmedTempFile = null;
                try {
                    VirtualFile vFile = prewarmed.getFirst();
                    PsiFile psi = prewarmed.getSecond();
                    // T-PreviewUx-Pack #2：主路径成功——清除降级横幅
                    // T-PrewarmLateRecover F2：动作一并摘除
                    p.degradationBanner.onRetry = null;
                    p.degradationBanner.clear(DegradationBanner.Kind.DEGRADED);
                    // T-PrewarmLateRecover F1：主路径装配 → 当前文档不再属于降级态
                    p.degradedPreviewDocument = null;
                    p.previewTempFile = vFile;
                    p.psiFile = psi;
                    // T-PreviewDocLock：262 平台给「PSI 文件 → 文档」解析加了运行期读权限断言，故 EDT 上
                    // 同步执行 ReadAction，语义不变。约束：lambda 内只能做纯内存模型读。
                    Document d = ReadAction.compute(
                            (com.intellij.openapi.util.ThrowableComputable<Document, Throwable>) () ->
                                    PsiDocumentManager.getInstance(proj).getDocument(psi));
                    if (d == null) throw new IllegalStateException("临时 SQL 文件无关联文档");
                    // T-M36.6-03：预热文件为占位空内容 → 首次装配按实际 SQL 填充并同步 PSI
                    if (!d.getText().equals(sql)) {
                        final Document dF = d;
                        WriteCommandAction.runWriteCommandAction(proj, () -> {
                            dF.setText(sql);
                            PsiDocumentManager.getInstance(proj).commitDocument(dF);
                        });
                    }
                    doc = d;
                } catch (Throwable t) {
                    doc = fallbackPreviewDocument(p, sql,
                            t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
                }
            } else {
                doc = fallbackPreviewDocument(p, sql, "prewarmed product unavailable (timeout/failed/consumed)");
            }
        } else {
            doc = null;
        }
        Editor created;
        if (proj != null && doc != null) {
            created = EditorFactory.getInstance().createEditor(doc, proj, sqlFileType, false);
        } else {
            created = EditorFactory.getInstance().createEditor(EditorFactory.getInstance().createDocument(sql));
        }
        // M16.2：补全上下文双载体——Editor userData 为主载体、PSI 文件 userData 作兜底
        SqlCompletionContext ctx = SqlPreviewPanelSqlViewKt.buildCompletionContext(p);
        created.putUserData(SqlCompletionContext.KEY, ctx);
        if (p.psiFile != null) p.psiFile.putUserData(SqlCompletionContext.KEY, ctx);
        // T-InsertColumnInlay O-44b：新编辑器同样写语句类型载体。显式传 created。
        SqlPreviewPanelSqlViewKt.attachPreviewStatementKind(p, created);
        // M34 回归修复：三级回退的 Document→Context 映射须在此登记
        SqlCompletionContributor.registerContext(created.getDocument(), ctx);
        EditorEx createdEx = (EditorEx) created;
        // F-CASE：自研 SQL 高亮器（SqlLexer 将 CASE/WHEN/THEN/ELSE/END 标记为 KEYWORD）
        createdEx.setHighlighter(new LexerEditorHighlighter(
                new SqlSyntaxHighlighter(), EditorColorsManager.getInstance().getGlobalScheme()));
        createdEx.getSettings().setLineNumbersShown(true);
        createdEx.getSettings().setFoldingOutlineShown(true);
        createdEx.getSettings().setIndentGuidesShown(false);
        createdEx.getSettings().setAdditionalPageAtBottom(false);
        // T-PreviewWhitespaceToggle O-44a：空白符可视化（默认关=不设置该属性、继承 IDE 全局）
        EditorWhitespaceSettings.applyTo(createdEx);
        createdEx.setPlaceholder(p.previewEmptyHint); // T-UxOnboard T-UX-02：空态提示原生件
        p.editorHolder.removeAll();
        // T-M27.1：编辑器组件链补 DataProvider——外层包装 DataProvider 提供 EDITOR
        final Editor createdForData = created;
        JBPanel<JBPanel<?>> wrapper = new EditorDataProviderPanel(createdForData);
        wrapper.add(created.getComponent(), BorderLayout.CENTER);
        p.editorHolder.add(wrapper, BorderLayout.CENTER);
        p.editorHolder.revalidate();
        p.editorHolder.repaint();
        // T-SqlCopyShortcuts O-122：无选中 Ctrl+C 复制全文、有选中只复制片段
        EditorCopyShortcuts.install(created, UiTextClipboard::copy);
        // T-PreviewLineCommentDash O-134/D136：打标接管行注释统一插 `--`
        EditorCommentShortcuts.install(created);
        p.editor = created;
        bindAutoPopup(p, created);
        // T-AutoFold F4-a：createEditor 支链——新编辑器按初始内容应用
        SqlAutoFolding.applyTo(created, sql);
    }

    /**
     * T-PreviewExecDs-01d / T-PerfTail #2：预览文档降级链（第二级 → 第三级）——
     * 第二级 = 内存 PSI（PsiFileFactory.createFileFromText，无 VFS 文件但保留补全上下文载体）；
     * 第三级（兜底）= 纯内存文档编辑器（无 PSI，补全/注释灭）。
     * T-PreviewUx-Pack #2：降级链层级横幅——触发时明示当前层级与恢复指引。
     * T-PrewarmLateRecover F1：产出的文档登记为 degradedPreviewDocument；F2：横幅挂上「重试」动作。
     */
    @NotNull
    public static Document fallbackPreviewDocument(@NotNull SqlPreviewPanel p, @NotNull String sql,
                                                   @NotNull String msg) {
        // 第二级：内存 PSI（保留补全上下文载体）
        Document memoryPsiDoc = createInMemoryPreviewPsi(p, sql);
        if (memoryPsiDoc != null) {
            showDegradedBanner(p, "panel.banner.degradedPsi");
            p.degradedPreviewDocument = memoryPsiDoc;
            return memoryPsiDoc;
        }
        // 第三级：裸 document（内存 PSI 也失败）
        Logger.getInstance(SqlPreviewPanel.class)
                .warn("[M36] preview temp file unavailable, fall back to bare in-memory editor: " + msg);
        p.psiFile = null;
        showDegradedBanner(p, "panel.banner.degradedPlain");
        Document doc = EditorFactory.getInstance().createDocument(sql);
        p.degradedPreviewDocument = doc;
        return doc;
    }

    /**
     * T-PrewarmLateRecover F2：挂降级横幅并接上「重试」动作。
     *
     * <p>动作覆盖的是 F1 够不到的那一类——预热**硬失败**（VFS refresh 失败 / findFile null /
     * 项目内目录建不出来）：没有产物可等，只能由用户点一次重新臂预热。</p>
     */
    public static void showDegradedBanner(@NotNull SqlPreviewPanel p, @NotNull String messageKey) {
        p.degradationBanner.onRetry = () -> retryPrewarm(p);
        p.degradationBanner.show(
                DegradationBanner.Kind.DEGRADED,
                MpSqlVisualizerBundle.message(messageKey));
    }

    /**
     * T-PrewarmLateRecover F1：迟到产物换装（自动形态）。
     *
     * <p>Kotlin 默认参数 {@code userRequested = false} 的等价重载。</p>
     */
    public static void tryUpgradeToLateProduct(@NotNull SqlPreviewPanel p,
                                               @NotNull Pair<VirtualFile, PsiFile> product) {
        tryUpgradeToLateProduct(p, product, false);
    }

    /**
     * T-PrewarmLateRecover F1：迟到产物换装——把已经装配好的降级编辑器换成项目内文件背书的编辑器。
     *
     * <p>这是「首开预热超过预算 → 降级挂横幅 → 产物随后到达」的恢复出口（实测形态：预算 500ms、
     * 冷启首开预热 3016ms）。**调用方负责线程归位**（prewarmDispatcher 回 EDT，写安全模态）。</p>
     *
     * <p>换装判据（全部满足才动，任一条不满足就静默保持现状——产物留在 prewarmedTempFile 里，
     * 留给后续 createEditor 消费）：面板与项目未销毁；已有编辑器，且当前文档就是降级装配的那一份；
     * 文档文本与插件最后一次写入一致（lastRenderedSql）——用户动过就不换。该条**只在自动换装时**生效：
     * 用户显式点「重试」时按 {@code userRequested} 跳过。</p>
     */
    public static void tryUpgradeToLateProduct(@NotNull SqlPreviewPanel p,
                                               @NotNull Pair<VirtualFile, PsiFile> product,
                                               boolean userRequested) {
        if (p.previewDisposed || (p.project != null && p.project.isDisposed())) return;
        Editor current = p.editor;
        if (current == null) return;
        Document degradedDoc = p.degradedPreviewDocument;
        if (degradedDoc == null) return;
        if (current.getDocument() != degradedDoc) return;
        String text = current.getDocument().getText();
        if (!userRequested && !text.equals(p.lastRenderedSql)) {
            // 用户已经在编辑（或另有渲染尚未落定）：不抢编辑现场，产物留给下一次装配
            Logger.getInstance(SqlPreviewPanel.class)
                    .info("[M36] late prewarm product kept: document edited since last render (len=" + text.length() + ")");
            return;
        }
        com.intellij.openapi.editor.LogicalPosition caret;
        try {
            caret = current.getCaretModel().getLogicalPosition();
        } catch (Throwable t) {
            caret = null;
        }
        boolean hadFocus;
        try {
            hadFocus = current.getContentComponent() != null && current.getContentComponent().isFocusOwner();
        } catch (Throwable t) {
            hadFocus = false;
        }
        disposeCurrentEditorOnly(p);
        // 与 assemblePreviewEditor 的「引用相等 + 随即置 null」消费判定对齐：本次要消费的就是这一份
        p.prewarmedTempFile = product;
        assemblePreviewEditor(p, text, product);
        if (caret != null) {
            try {
                if (p.editor != null) p.editor.getCaretModel().moveToLogicalPosition(caret);
            } catch (Throwable ignored) {
            }
        }
        if (hadFocus) {
            try {
                if (p.editor != null && p.editor.getContentComponent() != null) {
                    p.editor.getContentComponent().requestFocusInWindow();
                }
            } catch (Throwable ignored) {
            }
        }
        Logger.getInstance(SqlPreviewPanel.class)
                .info("[M36] late prewarm product consumed: editor upgraded to " + product.getFirst().getName());
    }

    /**
     * T-PrewarmLateRecover F2：横幅「重试」——重新臂一轮预热，产物到达后原地换装并清横幅。
     *
     * <p>两条出口都接上：预算内就绪走 {@code PrewarmAwait.scheduleOnce} 的 onReady；超预算到达走
     * {@code PrewarmAwait.onLateProduct}——所以重试**不会**因为「预热又慢了一次」而白点。失败（硬失败）
     * 时什么都不做：横幅与动作保留，用户可再点。</p>
     *
     * <p>重臂本身受 prewarmRound 保护：新轮自增令牌，旧轮迟到产物不再入字段。</p>
     */
    public static void retryPrewarm(@NotNull SqlPreviewPanel p) {
        if (p.previewDisposed || p.project == null) return;
        // 上一轮若已有产物躺着（迟到但没人消费），直接换装，不必再预热一轮
        Pair<VirtualFile, PsiFile> pending = p.prewarmedTempFile;
        if (pending != null) {
            tryUpgradeToLateProduct(p, pending, true);
            if (p.degradedPreviewDocument == null) return;
        }
        // 降级态下「当前在用文件」这个指针没有意义，而 prewarmPreviewTempFile 的入口守卫见它非空即判
        // 「已备好」直接 fail——这里只丢指针、不删文件：文件仍在 previewTempFilesCreated 登记表里。
        p.previewTempFile = null;
        p.prewarmFailed = false;
        prewarmPreviewTempFile(p);
        PrewarmAwait<Pair<VirtualFile, PsiFile>> await = p.prewarmAwait;
        if (await != null) {
            await.scheduleOnce(
                    product -> {
                        if (!p.previewDisposed) tryUpgradeToLateProduct(p, product, true);
                    },
                    // 超时/失败：保持现状（横幅与动作仍在）——迟到通道会在产物真正到达时收尾
                    () -> {
                    });
        }
    }

    /**
     * T-PreviewExecDs-01d：降级链第二级——内存 PSI 预览文档（无项目内 VFS 文件，但保留
     * {@link SqlCompletionContext} 补全载体，避免掉到裸 document 后补全/注释全灭）。createFileFromText
     * 包 ReadAction（T-M42 同机制先例：EDT 直调触发 markGenerated softAssertReadAccess 硬抛）。
     * 失败返回 null，由调用方回落到第三级裸文档。
     */
    @Nullable
    public static Document createInMemoryPreviewPsi(@NotNull SqlPreviewPanel p, @NotNull String sql) {
        Project proj = p.project;
        if (proj == null) return null;
        Logger LOG = Logger.getInstance(SqlPreviewPanel.class);
        try {
            FileType fileType = FileTypeManager.getInstance().getStdFileType("SQL");
            PsiFile psi = ReadAction.compute(
                    (com.intellij.openapi.util.ThrowableComputable<PsiFile, Throwable>) () ->
                            PsiFileFactory.getInstance(proj)
                                    .createFileFromText(PreviewInlayScope.MEMORY_PREVIEW_FILE_NAME, fileType, sql));
            p.psiFile = psi;
            LOG.info("[M36] in-memory PSI preview document created (len=" + sql.length() + ")");
            Document d = PsiDocumentManager.getInstance(proj).getDocument(psi);
            return d != null ? d : EditorFactory.getInstance().createDocument(sql);
        } catch (Throwable e) {
            LOG.warn("[M36] in-memory PSI preview fallback FAILED: " + e.getMessage());
            p.psiFile = null;
            return null;
        }
    }

    /**
     * 创建预览 PSI 文件（测试缝）：抽取 createEditor 中的 PsiFileFactory 创建逻辑为独立方法，
     * 供测试直接调用（绕过 EDT 回调 + EditorFactory 创建）。
     *
     * <p>T-M42（用户实测堆栈）：PsiFileFactory.createFileFromText 在 EDT 上直接调用会触发
     * 2026.2 平台 markGenerated 树遍历 softAssertReadAccess 硬抛——包 ReadAction（EDT 上同步执行，语义不变）。</p>
     *
     * @param proj 当前项目（非 null）
     * @param sql  预览 SQL 文本
     * @return 创建的 PsiFile
     */
    @NotNull
    public static PsiFile createPreviewFileForTest(@NotNull SqlPreviewPanel p, @NotNull Project proj,
                                                   @NotNull String sql) {
        // ThrowableComputable<PsiFile, Throwable> 的 compute 声明抛 Throwable——该方法实际只做
        // PsiFileFactory 纯内存创建（不产受检异常），照原 Kotlin 形态在此 sneaky 透传即可。
        try {
            return ReadAction.compute(
                    (com.intellij.openapi.util.ThrowableComputable<PsiFile, Throwable>) () -> {
                        FileType fileType = FileTypeManager.getInstance().getStdFileType("SQL");
                        return PsiFileFactory.getInstance(proj)
                                .createFileFromText("preview.sql", fileType, sql);
                    });
        } catch (RuntimeException | java.lang.Error e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * T-M27.2：自建输入自动补全（用户实测：面板内输入字符无自动提示，与 Java 代码编辑器行为不一致）。
     *
     * <p>平台 autopopup 链路对**非项目文件编辑器**不生效——{@code CompletionProgressIndicator} 的
     * autopopup 流程显示**空弹窗**。修复：面板监听文档输入（标识符字符），直接调**手动模式**补全处理器，
     * 绕开平台 autopopup 调度。关键参数：构造第 4 参 explicit=true + 补全前 commitDocument。
     * 防回环：有效 lookup 激活/删除/非标识符/调度中均不重复触发。</p>
     */
    public static void bindAutoPopup(@NotNull SqlPreviewPanel p, @NotNull Editor created) {
        com.intellij.openapi.editor.event.DocumentListener listener = new com.intellij.openapi.editor.event.DocumentListener() {
            @Override
            public void documentChanged(com.intellij.openapi.editor.event.DocumentEvent event) {
                if (p.suppressAutoPopup) return;
                if (event.getNewLength() == 0 || event.getOldLength() > event.getNewLength()) return;
                Editor ed = p.editor;
                if (ed == null) return;
                if (ed.isDisposed() || p.project == null) {
                    return;
                }
                // 平台 autopopup 会先弹**空弹窗**（contributor 未调度）——空弹窗不拦截，
                // 由自建补全接管；仅"已有有效项"的补全（lookup 选中插入回环）拦截
                Lookup active = LookupManager.getActiveLookup(ed);
                if (active != null && !active.getItems().isEmpty()) {
                    return;
                }
                if (p.autoPopupScheduled) {
                    return;
                }
                // 插入的最后一个字符为标识符（字母/数字/_）才弹（中文输入等场景不触发）。
                // 用**事件插入位置**而非 caret——DocumentListener 在 caret 移动前触发（真实输入同时序）
                int insertEnd = event.getOffset() + event.getNewLength();
                if (insertEnd <= 0) return;
                String prevText = ed.getDocument().getText(TextRange.create(insertEnd - 1, insertEnd));
                if (prevText.isEmpty()) return;
                char prev = prevText.charAt(0);
                if (!Character.isLetterOrDigit(prev) && prev != '_') {
                    return;
                }
                p.autoPopupScheduled = true;
                // write-safe: 同 SqlInputEditor.bindAutoPopup——本拍只由**用户输入活动**派生，
                // 无进度模态时平台取模态链（defaultModalityImpl）落 NON_MODAL（恒 write-safe）；
                // commitDocument 只做 PSI 同步、不改文档文本。
                ApplicationManager.getApplication().invokeLater(() -> {
                    p.autoPopupScheduled = false;
                    Editor ed2 = p.editor;
                    if (ed2 == null) return;
                    Project proj = p.project;
                    if (proj == null) return;
                    if (ed2.isDisposed()) return;
                    Lookup active2 = LookupManager.getActiveLookup(ed2);
                    if (active2 != null && !active2.getItems().isEmpty()) return;
                    try {
                        // 先提交文档（PSI 同步）——补全前缀按编辑器文档计算，未 commit 时用旧 PSI
                        // 文本算前缀导致误过滤（如 "FROM Stbl" 按旧文本 "FROM tbl" 前缀算成 "t"）
                        PsiDocumentManager.getInstance(proj).commitDocument(ed2.getDocument());
                        new CodeCompletionHandlerBase(CompletionType.BASIC, false, false, true)
                                .invokeCompletion(proj, ed2, 1, false);
                    } catch (Throwable t) {
                        // 补全异常不影响输入（弹窗失败下次输入重试）
                    }
                });
            }
        };
        // T-M52：双参数版（Disposable 管理监听生命周期，releaseEditor 时自动移除）。
        // T-RA-02：移除职责只归 autoPopupDisposable——重新注册前先 dispose 旧 disposable，
        // 防同一文档挂上两个 listener。
        if (p.autoPopupDisposable != null) Disposer.dispose(p.autoPopupDisposable);
        p.autoPopupDisposable = Disposer.newDisposable("SqlPreviewPanel.autoPopup");
        created.getDocument().addDocumentListener(listener, p.autoPopupDisposable);
    }

    /**
     * T-PrewarmLateRecover F1：只释放当前编辑器（含 autopopup 监听），不动临时文件与预热产物。
     *
     * <p>从 {@link #releaseEditor} 抽出——迟到产物换装要在**保留**临时文件与产物的前提下换掉编辑器，
     * 两条路径共用同一段释放逻辑，避免「谁负责 releaseEditor」出现两个说法（Editor 泄漏会被平台报
     * "Memory leak detected"）。</p>
     */
    public static void disposeCurrentEditorOnly(@NotNull SqlPreviewPanel p) {
        Editor ed = p.editor;
        if (ed != null) {
            // T-M52：监听器由 Disposable 管理，dispose 即移除
            if (p.autoPopupDisposable != null) Disposer.dispose(p.autoPopupDisposable);
            p.autoPopupDisposable = null;
            // T-PreviewLineCommentDash O-134：行注释接管的减计数端点（与 assemblePreviewEditor 的
            // install 对称；引用归零即换回平台原动作。幂等，未打标零动作）
            EditorCommentShortcuts.uninstall(ed);
            // T-K2JWalk1 KW-1：跨工程切换残留的预览弹窗关闭时，两个面板实例可能先后持有同一编辑器
            // （showSql 双走 createEditor 后字段被覆盖转移），后到方的 releaseEditor 会被平台
            // TraceableDisposable 断言为「Double release of editor」SEVERE（DisposalException 是
            // package-private，不可 catch）——isDisposed 前置判断幂等跳过，首次释放已生效无资源可补
            if (ed.isDisposed()) {
                com.intellij.openapi.diagnostic.Logger.getInstance(SqlPreviewPanel.class)
                        .warn("[KW-1] editor already disposed, skip release: " + System.identityHashCode(ed));
            } else {
                EditorFactory.getInstance().releaseEditor(ed);
            }
            p.editor = null;
        }
        // T-PrewarmLateRecover F1：编辑器没了，降级文档身份随之失效
        p.degradedPreviewDocument = null;
    }

    /** 释放当前编辑器（幂等）。 */
    public static void releaseEditor(@NotNull SqlPreviewPanel p) {
        // T-RefreshDbSteal：createEditor 兜底断言的对称端点——谁释放了自建编辑器决定了
        // 下一次 showSql 走哪个分支，旧实现零日志
        Logger.getInstance(SqlPreviewPanel.class)
                .info("[M36] releaseEditor: editor=" + System.identityHashCode(p.editor)
                        + ", nativeDoc=" + (p.nativeEditorDoc != null), new Throwable("releaseEditor path"));
        disposeCurrentEditorOnly(p);
        p.psiFile = null;
        // M34 回归修复：临时 SQL 文件删除（java.io 删除免 write action；deleteOnExit 兜底）
        VirtualFile vFile = p.previewTempFile;
        if (vFile != null) new File(vFile.getPath()).delete();
        p.previewTempFile = null;
        // T-FixN2：唯一文件名后本面板**创建过的全部**预览临时文件统一回收——除上面那个「当前在用」
        // 的以外，还含「预热已发布但从未被装配消费」与「两次预热竞态中晚到的那一个」两类无主文件，
        // 否则 preview/ 目录会随预览次数只增不减。清理策略三选一取「按面板生命周期删除」。
        // T-PerfSweep6 T-PS6-04③：抽成 recoverPreviewTempFiles，与 dispose 共用同一份回收逻辑
        recoverPreviewTempFiles(p);
        // T-M36.6-03：预热产物作废（面板重用时由 clear() 重新预热；dispose 由 previewDisposed 兜底）
        p.prewarmedTempFile = null;
    }

    /**
     * T-PerfSweep6 T-PS6-04③：回收本面板创建过的全部预览临时文件（幂等）。
     *
     * <p>抽成独立方法是为了让 {@link #releaseEditor} 与 dispose 共用同一份回收逻辑——此前只有
     * {@code removeNotify} → releaseEditor 一条链会走到，而三种宿主（弹窗 / 工具窗 / 嵌入控制台）下
     * dispose 与 removeNotify 的先后未穷尽，dispose 先到即漏删。</p>
     */
    public static void recoverPreviewTempFiles(@NotNull SqlPreviewPanel p) {
        for (File f : new ArrayList<>(p.previewTempFilesCreated)) {
            try {
                f.delete();
            } catch (Throwable ignored) {
            }
        }
        p.previewTempFilesCreated.clear();
    }

    /**
     * T-PerfSweep6 T-PS6-04（性能报告发现 4）：预览目录级兜底清扫（**每个面板启动时一次**）。
     *
     * <p>**为什么需要**：唯一命名（T-FixN2）把「覆盖写同一路径」变成「每次新建一个文件」，异常路径
     * （kill / 崩溃 / 未走 removeNotify / 未走 dispose）留下的文件不再被任何后续预览复用，
     * preview/ 只增不减。三条兜底按性价比取二：① **年龄**：mtime 早于 STALE_PREVIEW_MAX_AGE_MS（7 天）
     * 的残留直接删；② **条数**：本插件命名的文件超过 PREVIEW_DIR_MAX_FILES（200）时按 mtime 从最旧删到上限。</p>
     *
     * <p>**宁漏删不误删**：只删**本插件自有命名**的文件——判据直接复用建名处的
     * {@link PreviewInlayScope#isPrimaryPreviewFileName}（与生成端同一份形态常量，杜绝判据漂移）。
     * 目录不存在/不可读时静默返回——清扫是兜底，不得影响预览主链。</p>
     */
    public static void sweepStalePreviewFiles(@NotNull SqlPreviewPanel p, @NotNull File dir) {
        File[] files = dir.listFiles(f -> f.isFile() && PreviewInlayScope.isPrimaryPreviewFileName(f.getName()));
        if (files == null || files.length == 0) return;
        long now = System.currentTimeMillis();
        for (File f : files) {
            if (now - f.lastModified() > SqlPreviewPanel.STALE_PREVIEW_MAX_AGE_MS) {
                try {
                    f.delete();
                } catch (Throwable ignored) {
                }
            }
        }
        List<File> rest = new ArrayList<>();
        for (File f : files) {
            if (f.exists()) rest.add(f);
        }
        if (rest.size() > SqlPreviewPanel.PREVIEW_DIR_MAX_FILES) {
            rest.sort(java.util.Comparator.comparingLong(File::lastModified));
            int removeCount = rest.size() - SqlPreviewPanel.PREVIEW_DIR_MAX_FILES;
            for (int i = 0; i < removeCount; i++) {
                try {
                    rest.get(i).delete();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * T-M27.1：编辑器组件链补 DataProvider——2025.3 平台编辑器内部改为 UiCompatibleDataProvider
     * （仅新 UI 兼容查询路径生效），传统 DataManager.getDataContext 沿父链只认 DataProvider 接口，
     * 不实现则编辑器无补全（断言 editor != null 静默失败）。master 为匿名 object 表达式
     * {@code object : JBPanel<JBPanel<*>>(BorderLayout()), DataProvider}——Java 匿名类无法声明
     * implements 子句，故落命名静态内部类承载（语义等价：BorderLayout 布局 + EDITOR 数据应答）。
     */
    private static final class EditorDataProviderPanel extends JBPanel<JBPanel<?>>
            implements com.intellij.openapi.actionSystem.DataProvider {

        private final Editor editor;

        private EditorDataProviderPanel(Editor editor) {
            super(new BorderLayout());
            this.editor = editor;
        }

        @Override
        public Object getData(@NotNull String dataId) {
            return CommonDataKeys.EDITOR.is(dataId) ? editor : null;
        }
    }

    /** 结果已到达时把实际生成方言同步到预览目录；预热尚在途时由其稍后读取同一字段。
     *  注：Java 端未重建 mapPreviewDialectBeforePsi/预热目录方言映射，仅同步期望方言名（行为差距已登记）。 */
    public static void syncPreviewDialectMapping(@NotNull SqlPreviewPanel p,
                                                 @NotNull cn.song.mybatisplus.visualizer.model.PreviewResult result) {
        cn.song.mybatisplus.visualizer.sql.Dialect effective = result.getEffectiveDialect();
        p.previewDesiredDialectName = effective != null ? effective.getName() : null;
    }

}
