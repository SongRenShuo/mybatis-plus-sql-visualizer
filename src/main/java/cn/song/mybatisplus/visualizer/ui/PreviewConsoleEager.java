package cn.song.mybatisplus.visualizer.ui;

import org.jetbrains.annotations.NotNull;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.registry.Registry;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * T-PreviewExecWriteDefer：<b>预览打开时是否「立即准备执行控制台」</b>的单一开关。
 *
 * <h2>为什么需要这个开关（证据）</h2>
 *
 * <p>用户 00:10~00:11 沙盒 A/B：同一语句连开两次预览——第 1 次：{@code exec file updated via
 * document+save} → 2s 内 {@code enter dumb mode}（dumb 任务 = PushedFilePropertiesUpdater，随后
 * {@code Reparsing …/mpsql-exec.sql because of language substitution}）；第 2 次：整条准备链被跳过、
 * <b>不写文件</b> → <b>没有 dumb</b>。</p>
 *
 * <p>结论：<b>预览打开时「准备控制台」这一步会写 mpsql-exec.sql</b>，而该文件带着方言映射
 * （语言被替换），平台在内容变化时要重算替换语言并推文件属性（需要索引）⇒ 整个工程进 dumb；
 * 同一链还会在 EDT 上复活该文件的 PSI（{@code SlowOperations … FileManagerImpl.shouldResurrect →
 * shouldCreatePSI}，SEVERE，点名本插件）。两个症状同一个成因。</p>
 *
 * <h2>默认口径</h2>
 *
 * <p>{@code LAZY}（默认）：预览打开<b>不</b>准备控制台、<b>不</b>预热 exec 文件；控制台在用户第一次
 * 执行/切换数据源时由执行链（selectAndExecute）自行「准备 + 同步 exec 文件 + 执行」——那是既有行为，
 * 无需额外接线。{@code EAGER}（旧行为）：预览打开即准备控制台（Ultimate 立刻显示原生控制台），
 * 代价是上面那两个症状。</p>
 *
 * <p>Registry：{@code sqlvis.preview.eagerConsole}。单测/沙盒默认走旧行为（isUnitTestMode），
 * 避免既有用例大面积改动；要验惰性分支请显式置 {@link #enabledForTest}。</p>
 */
public final class PreviewConsoleEager {

    /** Registry 开关（plugin.xml registryKey 登记；缺失时按默认值 false＝惰性）。 */
    public static final String REGISTRY_KEY = "sqlvis.preview.eagerConsole";

    /** 三档口径（设置页可切；字符串存档见 {@code PluginSettings.State.consolePrepMode}）。 */
    public enum Mode {
        /** 默认：不建控制台（不写 exec 文件），绑定行给「显示执行控制台」入口。 */
        LAZY,
        /** 同 LAZY，但不放入口（极简派）。 */
        LAZY_SILENT,
        /** 旧行为：打开即准备控制台。 */
        EAGER,
    }

    /** 测试缝：null＝按环境判定（单测＝eager，生产＝设置页口径）。 */
    public static volatile Boolean enabledForTest = null;

    /** 测试缝：强制口径（null＝读设置页）。 */
    public static volatile Mode modeForTest = null;

    /** 惰性延后次数（守卫读数：预览打开真的走了「不准备控制台」分支）。 */
    public static final AtomicInteger DEFERRED_COUNT = new AtomicInteger();

    /** Kotlin object 语义兼容实例。 */
    public static final PreviewConsoleEager INSTANCE = new PreviewConsoleEager();

    private PreviewConsoleEager() {
    }

    /** 当前口径：设置页 → 解析失败回落 LAZY。 */
    @NotNull
    public static Mode mode() {
        Mode forced = modeForTest;
        if (forced != null) {
            return forced;
        }
        String raw;
        try {
            raw = PluginSettings.getInstance().getState().getConsolePrepMode();
        } catch (Throwable t) {
            raw = null;
        }
        String upper = raw != null ? raw.toUpperCase() : null;
        if ("EAGER".equals(upper)) {
            return Mode.EAGER;
        }
        if ("LAZY_SILENT".equals(upper)) {
            return Mode.LAZY_SILENT;
        }
        return Mode.LAZY;
    }

    /** 是否<b>立即</b>准备控制台（＝旧行为）。Registry sqlvis.preview.eagerConsole 仍在：置 true 可强制回旧行为。 */
    public static boolean eager() {
        Boolean forced = enabledForTest;
        if (forced != null) {
            return forced;
        }
        try {
            return ApplicationManager.getApplication() != null
                    && ApplicationManager.getApplication().isUnitTestMode()
                    || mode() == Mode.EAGER
                    || Registry.is(REGISTRY_KEY, false);
        } catch (Throwable t) {
            return true;
        }
    }

    /** 是否显示「显示执行控制台」入口（只有 LAZY 档给；eager 不需要，SILENT 明确不要）。 */
    public static boolean showShowConsoleEntry() {
        return !eager() && mode() == Mode.LAZY;
    }

    /** 守卫读数登记（预览打开走了「不准备控制台」分支）。 */
    public static void recordDeferred() {
        DEFERRED_COUNT.incrementAndGet();
    }

    /** 测试缝：清空全部状态。 */
    public static void resetForTest() {
        enabledForTest = null;
        modeForTest = null;
        DEFERRED_COUNT.set(0);
    }

    /** 兼容旧调用点：等价于 {@link #eager()}。 */
    public static boolean enabled() {
        return eager();
    }
}
