package cn.song.mybatisplus.visualizer.util

import cn.song.mybatisplus.visualizer.config.PluginSettings
import org.jetbrains.annotations.PropertyKey
import java.nio.charset.StandardCharsets
import java.text.MessageFormat
import java.util.Locale
import java.util.PropertyResourceBundle
import java.util.ResourceBundle

/**
 * 消息 Bundle（M6-03 国际化，M14 升级为插件级语言控制）。
 *
 * 由 `DynamicBundle`（跟随 IDE 语言）改为**自管理 ResourceBundle**：
 * 按 [PluginSettings.state.language] 选择语言（**默认简体中文**，用户要求）：
 *  - `zh`（默认）：简体中文；
 *  - `en`：English；
 *  - `AUTO`：跟随 IDE 界面语言（JVM 默认 locale）；
 *  - 测试环境（settings service 未注册）兜底中文，与默认值一致。
 *
 * properties 文件为 UTF-8 原始中文（非 `\uXXXX` 转义），需自定义
 * [ResourceBundle.Control] 以 UTF-8 读取，否则乱码。
 * 所有运行时 UI 文案统一经 [message] 获取，禁止硬编码。
 */
object MpSqlVisualizerBundle {

    private const val BUNDLE_NAME = "messages.MpSqlVisualizerBundle"

    /** UTF-8 properties 读取控制（_zh 文件为 UTF-8 原始中文）。 */
    private val utf8Control = object : ResourceBundle.Control() {
        override fun newBundle(
            baseName: String,
            locale: Locale,
            format: String,
            loader: ClassLoader,
            reload: Boolean,
        ): ResourceBundle? {
            if (format != "java.properties") return null
            val bundleName = toBundleName(baseName, locale)
            val resourceName = toResourceName(bundleName, "properties")
            val stream = loader.getResourceAsStream(resourceName) ?: return null
            return stream.use { PropertyResourceBundle(it.reader(StandardCharsets.UTF_8)) }
        }

        /**
         * M14.7 修复「中英文切换无效」：禁用 JVM 默认 locale 回退。
         *
         * Java ResourceBundle 的 fallback 链默认包含 [Locale.getDefault]（JVM 语言环境）——
         * 系统为中文时请求 `en` 找不到 `_en` 会回退到系统中文 locale，导致切英文无效。
         * 返回 null 后 fallback 直达 base（英文），`en`/`zh` 由 [currentLocale] 精确控制。
         */
        override fun getFallbackLocale(baseName: String, locale: Locale): Locale? = null
    }

    /**
     * 取本地化文案。
     *
     * @param key    文案 key（见 messages/MpSqlVisualizerBundle*.properties）
     * @param params 格式化参数（MessageFormat 风格 {0}）
     */
    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE_NAME) key: String, vararg params: Any?): String {
        // 兜底：bundle 加载/key 缺失时返回 key 本身（对齐 IntelliJ BundleBase 行为，避免 UI 抛异常）
        val pattern = runCatching {
            ResourceBundle.getBundle(BUNDLE_NAME, currentLocale(), loader, utf8Control).getString(key)
        }.getOrDefault(key)
        return if (params.isEmpty()) pattern else runCatching {
            MessageFormat.format(pattern, *params)
        }.getOrDefault(pattern)
    }

    /**
     * T-McpToolDisplayDesc：缺键返回 **null**（不回落 key 名）——调用方自带回落语义（如
     * `McpToolsetSupport.displayDescription` 缺键回落工具名/空串）时用本方法；`[message]`
     * 的「缺键返回 key 本身」兜底会把键名字符串漏进 UI。
     * 语言选择与 UTF-8 读取链与 [message] 完全一致（currentLocale / utf8Control）。
     */
    @JvmStatic
    fun messageOrNull(@PropertyKey(resourceBundle = BUNDLE_NAME) key: String, vararg params: Any?): String? {
        val pattern = runCatching {
            ResourceBundle.getBundle(BUNDLE_NAME, currentLocale(), loader, utf8Control).getString(key)
        }.getOrNull() ?: return null
        return if (params.isEmpty()) pattern else runCatching {
            MessageFormat.format(pattern, *params)
        }.getOrDefault(pattern)
    }

    /**
     * T-McpToolset2：agent 面英文文案——MCP 工具输出的警告/指引恒英文（agent 文案英文口径），
     * 与插件语言设置无关。bundle base 文件即英文（_zh 才是中文覆盖），Locale.ROOT 直读 base，
     * **零新键**（复用既有键的英文版本）；key 缺失兜底返回 key 本身（与 [message] 同策略）。
     * 消费方：sqlv_rollback_plan / sqlv_perf_advice 的 warnings 渲染。
     */
    @JvmStatic
    fun messageEn(@PropertyKey(resourceBundle = BUNDLE_NAME) key: String, vararg params: Any?): String {
        val pattern = runCatching {
            ResourceBundle.getBundle(BUNDLE_NAME, Locale.ROOT, loader, utf8Control).getString(key)
        }.getOrDefault(key)
        return if (params.isEmpty()) pattern else runCatching {
            MessageFormat.format(pattern, *params)
        }.getOrDefault(pattern)
    }

    private val loader: ClassLoader get() = MpSqlVisualizerBundle::class.java.classLoader

    /**
     * T-ReportL10n-02：嵌入平台 UI 的文案（如 errorHandler 的「报告到…」菜单项与隐私
     * 提示）恒按 **IDE 显示语言** 解析，不经插件语言设置。
     *
     * 真机二次反馈：插件语言默认 zh，仅改 AUTO 分支不会生效——这两处文案出现在平台
     * Internal Errors 界面里，周围全是 IDE 语言，必须跟随 IDE 显示语言才不割裂；
     * 插件自己的对话框仍走 [message]（跟随插件语言设置），边界与 M23「平台组件文案
     * 不跟插件语言须显式覆盖」一脉相承。IDE locale 不可用（headless）回退 JVM locale。
     */
    @JvmStatic
    fun messageForIde(@PropertyKey(resourceBundle = BUNDLE_NAME) key: String, vararg params: Any?): String {
        val pattern = runCatching {
            ResourceBundle.getBundle(BUNDLE_NAME, ideDisplayLocale(), loader, utf8Control).getString(key)
        }.getOrDefault(key)
        return if (params.isEmpty()) pattern else runCatching {
            MessageFormat.format(pattern, *params)
        }.getOrDefault(pattern)
    }

    /**
     * 当前语言 locale：默认简体中文；`en`→English；`AUTO`→跟随 IDE 显示语言；
     * settings 未注册（测试环境）兜底中文。
     */
    private fun currentLocale(): Locale = runCatching {
        when (PluginSettings.getInstance().state.language) {
            "en" -> Locale.ENGLISH
            "AUTO" -> ideDisplayLocale()
            else -> Locale.SIMPLIFIED_CHINESE
        }
    }.getOrElse { Locale.SIMPLIFIED_CHINESE }

    /**
     * T-ReportL10n：`AUTO` 的语言落点从 [Locale.getDefault]（JVM 进程 locale）改为
     * [com.intellij.DynamicBundle.getLocale]（IDE 实际显示语言）。真机反馈：IDE 界面
     * 已切英文（Internal Errors 全英文），「报告到需求池」菜单项仍显示中文——
     * 语言设置选「跟随系统」时 JVM locale 可能仍是系统中文，与 IDE 显示语言脱节。
     * 平台入口 243（util-8.jar）/262（intellij.platform.core.jar）javap 实证
     * `public static Locale getLocale()` 同签名；测试/headless 不可用时回退 JVM locale。
     */
    internal fun ideDisplayLocale(): Locale = runCatching {
        com.intellij.DynamicBundle.getLocale()
    }.getOrElse { Locale.getDefault() }
}
