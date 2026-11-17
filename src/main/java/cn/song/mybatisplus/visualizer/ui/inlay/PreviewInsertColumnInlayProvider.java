// T-SyncK2JMaster115 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inlay/PreviewInsertColumnInlayProvider.kt 移植
package cn.song.mybatisplus.visualizer.ui.inlay;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.xml.XmlStatementKind;
import com.intellij.codeInsight.hints.ChangeListener;
import com.intellij.codeInsight.hints.ImmediateConfigurable;
import com.intellij.codeInsight.hints.InlayHintsCollector;
import com.intellij.codeInsight.hints.InlayHintsProvider;
import com.intellij.codeInsight.hints.InlayHintsSink;
import com.intellij.codeInsight.hints.InlayPresentationFactory;
import com.intellij.codeInsight.hints.NoSettings;
import com.intellij.codeInsight.hints.SettingsKey;
import com.intellij.codeInsight.hints.presentation.PresentationFactory;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * INSERT 目标列 inlay provider（T-InsertColumnInlay O-44b）。
 *
 * <p>在<strong>本面板预览编辑器</strong>的 INSERT 语句上，为每个 VALUES 表达式 / SELECT 投影挂目标列名
 * 提示，让多列动态 INSERT 的「列-值错位」一眼可见。规划规则见 {@link InsertColumnInlayPlanner}。
 *
 * <p>三道闸门（任一不满足即返回 null，平台侧「无 collector」= 零 inlay）：
 * <ol>
 *  <li><strong>作用域</strong>：{@link PreviewInlayScope#previewStatementKind} 双锚点（文件名 + userData）；</li>
 *  <li><strong>语句类型</strong>：{@code xmlModel.kind == INSERT}（面板经 {@link PreviewInlayScope#attach} 注入；
 *      非 INSERT 与「无 xmlModel 的非 XML 预览链」都取不到 INSERT 值 → 不提示）；</li>
 *  <li><strong>静态可判</strong>：{@link InsertColumnInlayPlanner} 内部的列/值对应判定（不可判即不提示）。</li>
 * </ol>
 *
 * <p><strong>零设置项</strong>：EP 未注册 {@code settingsKeyId}，平台按
 * {@code InlayHintsSettings.computeIsEnabledByDefault} 的「无匹配 bean → true」口径默认开启
 * （243 源码实证），用户可用平台的 inlay 全局开关或语言级开关关闭；本 provider 不新增设置页面
 * （{@code createConfigurable} 为接口强制实现的空壳，无 {@code settingsKeyId} 即无设置 UI 入口）。
 *
 * <p><strong>纯显示层</strong>：只读编辑器文档文本 + 向 InlayModel 投提示，不改 SQL 文本、不写文档。
 * 提示内容只依赖文档文本（不依赖 PSI 树/索引），故实现 {@link DumbAware}（索引期间照常可用）。
 */
public final class PreviewInsertColumnInlayProvider implements InlayHintsProvider<NoSettings>, DumbAware {

    /** 设置键（Kotlin {@code override val key} 等价；{@link SettingsKey} 不可变，无实例态）。 */
    private static final SettingsKey<NoSettings> KEY = new SettingsKey<>("sqlVisualizer.insertColumn");

    @Override
    @Nullable
    public InlayHintsCollector getCollectorFor(
            @NotNull PsiFile file,
            @NotNull Editor editor,
            @NotNull NoSettings settings,
            @NotNull InlayHintsSink sink) {
        // 第 0 道（T-PreviewInlayNativeYield）：IDEA 自带 SQL 列名 inlay 在位即整体让位。
        // 放在最前——它是「这条功能还要不要」的开关，作用域/类型/可判三道只决定「画哪些」。
        if (NativeColumnInlayYieldGate.shouldYield()) return null;
        if (PreviewInlayScope.previewStatementKind(file, editor) != XmlStatementKind.INSERT) return null;
        return new InsertColumnInlayCollector(new PresentationFactory(editor));
    }

    @Override
    @NotNull
    public NoSettings createSettings() {
        return new NoSettings();
    }

    @Override
    @NotNull
    public String getName() {
        return MpSqlVisualizerBundle.message("inlay.insertColumn.name");
    }

    @Override
    @NotNull
    public SettingsKey<NoSettings> getKey() {
        return KEY;
    }

    @Override
    @Nullable
    public String getPreviewText() {
        return null;
    }

    @Override
    @NotNull
    public ImmediateConfigurable createConfigurable(@NotNull NoSettings settings) {
        return NoOpConfigurable.INSTANCE;
    }
}

/**
 * INSERT 目标列提示收集器。
 *
 * <p>平台两处调用形态不同，故用「一次性」闸门：
 * <ul>
 *  <li>真机 pass（{@code InlayHintsPass.doCollectInformation}）按 Divider 分段元素逐个调用，返回 false
 *      即把本 collector 移出后续调度；</li>
 *  <li>平台测试链（{@code CollectorWithSettings.collectTraversing}，{@code SyntaxTraverser.psiTraverser(file)}）
 *      <strong>忽略返回值</strong>、对每个 PSI 元素都调用一次——无闸门会按元素数量重复投提示。</li>
 * </ul>
 *
 * <p>故首次调用即投完全部提示并置位（提示位点取自编辑器当前文档全文，与传入元素无关）。
 *
 * <p><strong>闸门必须是原子的</strong>（T-PerfSweep6 T-PS6-01，本轮唯一带正确性的发现）：平台真机 pass
 * 不是单线程遍历——{@code InlayHintsPass.doCollectInformation} 把分片元素交给
 * {@code JobLauncher.invokeConcurrentlyUnderProgress(…, Processor { element -> … })} <strong>并发</strong>跑，
 * 闭包内共享同一份 {@code enabledCollectors}（243 装机版源码实证：{@code InlayHintsPass.kt} 的
 * {@code val skippedCollectors = ConcurrentCollectionFactory.createConcurrentSet<Int>()} +
 * 对 {@code enabledCollectors.indices.minus(skippedCollectors)} 的并发回调）。{@code skippedCollectors}
 * 只在 {@code collectHints} <strong>返回之后</strong>才置位，故「判定 + 置位」两步之间是一个真实的竞态窗口：
 * 普通布尔下两个分片线程可同时通过判定、各投一遍提示 → 同一 offset 上出现两份 presentation。
 * 改 {@code AtomicBoolean.compareAndSet(false, true)} 后只有一个线程能进入收集体。
 *
 * <p><strong>CAS 失败路径直接 {@code return false}，不做「结果就绪后共享」</strong>：平台只把返回值当
 * 「后续还要不要回调本 collector」用（{@code CollectorWithSettings.collectHints} 仅转发
 * {@code collector.collect} 的返回值，{@code InlayHintsPass} 见 false 即记入 {@code skippedCollectors}）
 * ——胜者必定完成收集并投递，失败者返回 false 只是让平台更快把它移出调度。
 * 若失败路径返回 true，平台会对<strong>每个</strong>分片元素继续回调它，是更差的形态。
 */
final class InsertColumnInlayCollector implements InlayHintsCollector {

    private final InlayPresentationFactory factory;

    private final AtomicBoolean collected = new AtomicBoolean(false);

    InsertColumnInlayCollector(InlayPresentationFactory factory) {
        this.factory = factory;
    }

    @Override
    public boolean collect(PsiElement element, Editor editor, InlayHintsSink sink) {
        if (!collected.compareAndSet(false, true)) return false;
        Document document = editor.getDocument();
        // 越界防御用 textLength（O(1)），不读全文——缓存命中时连 text 都不取（见 InsertColumnInlayPlanCache）
        int textLength = document.getTextLength();
        for (InsertColumnInlayPlanner.Hint hint : InsertColumnInlayPlanCache.planFor(document)) {
            // 防御：位点由本次同一份文本算出，理论上必在范围内；越界时平台 addInlineElement 会硬断言
            //（显示层功能不得因断言升级成异常），故越界条目直接跳过。
            if (hint.offset() < 0 || hint.offset() > textLength) continue;
            // relatesToPrecedingText=false：提示归属其后的值表达式（同参数名提示口径）；
            // placeAtTheEndOfLine=false：不占行尾锚点（行内提示，允许光标落在其后继续编辑）
            sink.addInlineElement(hint.offset(), false, factory.text(hint.columnName()), false);
        }
        return false;
    }
}

/**
 * 规划结果单槽缓存（T-PerfSweep6 T-PS6-01）。
 *
 * <p><strong>为什么必须落在进程级而不是 collector 内</strong>：平台<strong>每个 highlighting pass 都新建
 * collector</strong>——{@code InlayHintsPassFactoryInternal.createHighlightingPass} 里
 * {@code val hintSink = InlayHintsSinkImpl(editor)} 与
 * {@code getProviders(file, editor).mapNotNull { it.getCollectorWrapperFor(file, editor, language, hintSink) }}
 * 都是逐 pass 执行（243 装机版源码实证），collector 内缓存跨不过 pass；而一次性闸门已保证
 * 每 collector 只规划一次，故「collector 内缓存」是零收益的死代码。缓存落在进程级单例上，
 * 才真正省掉「同一文档连续多轮 pass 之间文本未变」的重规划。
 *
 * <p><strong>键 =（文档身份, {@code modificationStamp}）</strong>：stamp 是<strong>全局</strong> 24 位计数器
 * （{@code LocalTimeCounter.MOD_COUNTER_MASK = 0x00ff_ffff}，{@code DocumentImpl} 取全局 AtomicInteger），
 * 跨文档理论上可碰撞——只按 stamp 建键会把别的文档的提示搬到本文档上，故文档身份必须一起比。
 *
 * <p><strong>弱键</strong>：缓存不得钉住文档/编辑器（照 {@code DatabaseSchemaResolver} 的
 * {@code synchronizedMap(WeakHashMap)} 弱键纪律）；单槽形态下旧文档随下一次 miss 被替换，
 * {@link WeakReference} 只是防「最后一次命中的文档被本对象长期钉住」。
 *
 * <p><strong>线程安全</strong>：平台分片并发下 {@code planFor} 可被多线程同时调用；命中判定与写入都收在锁内，
 * 未命中时的重规划在锁外做（重复算一遍只是白付一次，不会产出错结果）。
 */
final class InsertColumnInlayPlanCache {

    private InsertColumnInlayPlanCache() {
    }

    private static final Object LOCK = new Object();
    private static WeakReference<Document> lastDocument = null;
    private static long lastStamp = Long.MIN_VALUE;
    private static List<InsertColumnInlayPlanner.Hint> lastHints = List.of();

    /**
     * 取文档当前文本的规划结果：{@code (文档身份, stamp)} 与上次一致直接复用，否则重规划并刷新单槽。
     *
     * <p>命中时<strong>不读 {@code document.text}</strong>——这是本缓存除「省重规划」外的第二项收益
     * （未变文档上连文本引用都不取）。
     */
    static List<InsertColumnInlayPlanner.Hint> planFor(Document document) {
        long stamp = document.getModificationStamp();
        synchronized (LOCK) {
            if (lastStamp == stamp && lastDocument != null && lastDocument.get() == document) return lastHints;
        }
        List<InsertColumnInlayPlanner.Hint> hints = InsertColumnInlayPlanner.plan(document.getText());
        synchronized (LOCK) {
            lastDocument = new WeakReference<>(document);
            lastStamp = stamp;
            lastHints = hints;
        }
        return hints;
    }

    /**
     * T-PerfSweep6 T-PS6-01 测试缝：清空单槽。
     *
     * <p>本对象是<strong>进程级静态状态</strong>，同 JVM 内多个测试类共享（默认 {@code maxParallelForks=1}、
     * 非 testDatabase 无 forkEvery）——用例必须在开始处清槽，否则前一个用例留下的
     * {@code (文档, stamp)} 会让后一个用例的首次规划直接命中缓存（读数随用例顺序漂移）。
     * 形态对照 {@code MetadataInvalidator.clearParseCacheForTest}。
     */
    static void clearForTest() {
        synchronized (LOCK) {
            lastDocument = null;
            lastStamp = Long.MIN_VALUE;
            lastHints = List.of();
        }
    }
}

/**
 * 空壳配置页（接口强制实现）。
 *
 * <p>EP 未注册 {@code settingsKeyId} ⇒ 平台设置页不会为本 provider 建入口（{@code createConfigurable}
 * 无调用方），本对象只是接口契约的落点；不做任何 UI（返回空面板，无控件即无状态）。
 */
final class NoOpConfigurable implements ImmediateConfigurable {

    /** Kotlin object 单例等价。 */
    static final NoOpConfigurable INSTANCE = new NoOpConfigurable();

    private NoOpConfigurable() {
    }

    @Override
    public JComponent createComponent(ChangeListener listener) {
        return new JPanel();
    }
}
