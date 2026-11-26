package cn.song.mybatisplus.visualizer.util;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.PropertyKey;

import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

/**
 * 消息 Bundle（M6-03 国际化，M14 升级为插件级语言控制）。
 *
 * 由 `DynamicBundle`（跟随 IDE 语言）改为**自管理 ResourceBundle**：
 * 按 [PluginSettings.State.getLanguage] 选择语言（**默认简体中文**，用户要求）：
 *  - `zh`（默认）：简体中文；
 *  - `en`：English；
 *  - `AUTO`：跟随 IDE 界面语言（JVM 默认 locale）；
 *  - 测试环境（settings service 未注册）兜底中文，与默认值一致。
 *
 * properties 文件为 UTF-8 原始中文（非 Unicode 转义形式），需自定义
 * [ResourceBundle.Control] 以 UTF-8 读取，否则乱码。
 * 所有运行时 UI 文案统一经 [message] 获取，禁止硬编码。
 */
public final class MpSqlVisualizerBundle {

    private static final String BUNDLE_NAME = "messages.MpSqlVisualizerBundle";

    /** UTF-8 properties 读取控制（_zh 文件为 UTF-8 原始中文）。 */
    private static final ResourceBundle.Control UTF8_CONTROL = new ResourceBundle.Control() {
        @Override
        public ResourceBundle newBundle(String baseName, Locale locale, String format, ClassLoader loader, boolean reload) {
            if (!"java.properties".equals(format)) return null;
            String bundleName = toBundleName(baseName, locale);
            String resourceName = toResourceName(bundleName, "properties");
            try (java.io.InputStream stream = loader.getResourceAsStream(resourceName)) {
                if (stream == null) return null;
                return new PropertyResourceBundle(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8));
            } catch (java.io.IOException e) {
                return null;
            }
        }

        /**
         * M14.7 修复「中英文切换无效」：禁用 JVM 默认 locale 回退。
         *
         * Java ResourceBundle 的 fallback 链默认包含 [Locale.getDefault]（JVM 语言环境）——
         * 系统为中文时请求 `en` 找不到 `_en` 会回退到系统中文 locale，导致切英文无效。
         * 返回 null 后 fallback 直达 base（英文），`en`/`zh` 由 [currentLocale] 精确控制。
         */
        @Override
        public Locale getFallbackLocale(String baseName, Locale locale) {
            return null;
        }
    };

    private MpSqlVisualizerBundle() {
    }

    /**
     * 取本地化文案。
     *
     * @param key    文案 key（见 messages/MpSqlVisualizerBundle*.properties）
     * @param params 格式化参数（MessageFormat 风格 {0}）
     */
    @NotNull
    public static String message(@NotNull @PropertyKey(resourceBundle = BUNDLE_NAME) String key, Object... params) {
        // 兜底：bundle 加载/key 缺失时返回 key 本身（对齐 IntelliJ BundleBase 行为，避免 UI 抛异常）
        String pattern;
        try {
            pattern = ResourceBundle.getBundle(BUNDLE_NAME, currentLocale(), loader(), UTF8_CONTROL).getString(key);
        } catch (Throwable e) {
            pattern = key;
        }
        if (params == null || params.length == 0) return pattern;
        try {
            return MessageFormat.format(pattern, params);
        } catch (Throwable e) {
            return pattern;
        }
    }

    /**
     * T-McpToolDisplayDesc：缺键返回 <b>null</b>（不回落 key 名）——调用方自带回落语义（如
     * {@code McpToolsetSupport.displayDescription} 缺键回落工具名/空串）时用本方法；
     * {@link #message} 的「缺键返回 key 本身」兜底会把键名字符串漏进 UI。
     *
     * <p>语言选择与 UTF-8 读取链与 {@link #message} 完全一致（currentLocale / UTF8_CONTROL）。</p>
     */
    @Nullable
    public static String messageOrNull(@NotNull @PropertyKey(resourceBundle = BUNDLE_NAME) String key, Object... params) {
        String pattern;
        try {
            pattern = ResourceBundle.getBundle(BUNDLE_NAME, currentLocale(), loader(), UTF8_CONTROL).getString(key);
        } catch (Throwable e) {
            return null;
        }
        if (params == null || params.length == 0) return pattern;
        try {
            return MessageFormat.format(pattern, params);
        } catch (Throwable e) {
            return pattern;
        }
    }

    /**
     * T-McpToolset2：agent 面英文文案——MCP 工具输出的警告/指引恒英文（agent 文案英文口径），
     * 与插件语言设置无关。bundle base 文件即英文（_zh 才是中文覆盖），Locale.ROOT 直读 base，
     * <b>零新键</b>（复用既有键的英文版本）；key 缺失兜底返回 key 本身（与 {@link #message} 同策略）。
     * 消费方：sqlv_rollback_plan / sqlv_perf_advice 的 warnings 渲染。
     */
    @NotNull
    public static String messageEn(@NotNull @PropertyKey(resourceBundle = BUNDLE_NAME) String key, Object... params) {
        String pattern;
        try {
            pattern = ResourceBundle.getBundle(BUNDLE_NAME, Locale.ROOT, loader(), UTF8_CONTROL).getString(key);
        } catch (Throwable e) {
            pattern = key;
        }
        if (params == null || params.length == 0) return pattern;
        try {
            return MessageFormat.format(pattern, params);
        } catch (Throwable e) {
            return pattern;
        }
    }

    /**
     * T-ReportL10n-02：嵌入平台 UI 的文案（如 errorHandler 的「报告到…」菜单项与隐私
     * 提示）恒按 <b>IDE 显示语言</b> 解析，不经插件语言设置。
     *
     * 真机二次反馈：插件语言默认 zh，仅改 AUTO 分支不会生效——这两处文案出现在平台
     * Internal Errors 界面里，周围全是 IDE 语言，必须跟随 IDE 显示语言才不割裂；
     * 插件自己的对话框仍走 {@link #message}（跟随插件语言设置），边界与 M23「平台组件文案
     * 不跟插件语言须显式覆盖」一脉相承。IDE locale 不可用（headless）回退 JVM locale。
     */
    @NotNull
    public static String messageForIde(@NotNull @PropertyKey(resourceBundle = BUNDLE_NAME) String key, Object... params) {
        String pattern;
        try {
            pattern = ResourceBundle.getBundle(BUNDLE_NAME, ideDisplayLocale(), loader(), UTF8_CONTROL).getString(key);
        } catch (Throwable e) {
            pattern = key;
        }
        if (params == null || params.length == 0) return pattern;
        try {
            return MessageFormat.format(pattern, params);
        } catch (Throwable e) {
            return pattern;
        }
    }

    private static ClassLoader loader() {
        return MpSqlVisualizerBundle.class.getClassLoader();
    }

    /**
     * 当前语言 locale：默认简体中文；`en`→English；`AUTO`→跟随 IDE 显示语言；
     * settings 未注册（测试环境）兜底中文。
     */
    private static Locale currentLocale() {
        try {
            String lang = PluginSettings.getInstance().getState().getLanguage();
            if ("en".equals(lang)) return Locale.ENGLISH;
            if ("AUTO".equals(lang)) return ideDisplayLocale();
            return Locale.SIMPLIFIED_CHINESE;
        } catch (Throwable e) {
            return Locale.SIMPLIFIED_CHINESE;
        }
    }

    /**
     * T-ReportL10n：{@code AUTO} 的语言落点从 JVM 进程 locale 改为
     * {@code com.intellij.DynamicBundle.getLocale()}（IDE 实际显示语言）。真机反馈：IDE 界面
     * 已切英文（Internal Errors 全英文），「报告到需求池」菜单项仍显示中文——
     * 语言设置选「跟随系统」时 JVM locale 可能仍是系统中文，与 IDE 显示语言脱节。
     * 平台入口 243（util-8.jar）/262（intellij.platform.core.jar）javap 实证
     * {@code public static Locale getLocale()} 同签名；测试/headless 不可用时回退 JVM locale。
     */
    static Locale ideDisplayLocale() {
        try {
            return com.intellij.DynamicBundle.getLocale();
        } catch (Throwable e) {
            return Locale.getDefault();
        }
    }
}
