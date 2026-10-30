package cn.song.mybatisplus.visualizer.ui.gutter

import com.intellij.openapi.diagnostic.Logger

/**
 * T-MapperNavCoexistYield：gutter 面的「同类插件共存让位」门——检测到同类 MyBatis 导航插件
 * （当前登记 MyBatisCodeHelperPro，下称 MPJ）处于启用状态时，本插件的 Mapper 双向跳转图标让位，
 * 不再与对方画在同一行的跳转箭头叠在一起。登记面见 [PEER_PLUGIN_ID_PREFIXES]。
 *
 * ## 与 XmlSynHiGate 的关系（两面，勿混）
 *
 * [cn.song.mybatisplus.visualizer.ui.inject.XmlSynHiGate] 的区间级探测解决的是**注入/着色**面：
 * 「对方是否真的给这段区间上了色」，在位 ≠ 覆盖，故必须逐区间问。gutter 图标不是区间问题——
 * 同一行两个来源各画一个图标**都合规**，纪律是「对方在位且启用即让位」，判据取插件启停，
 * 不做「对方是否真画了」的运行期推断（那是无法收敛的猜测）。
 *
 * ## API 形态与降级
 *
 * 探测走**全反射**（`PluginManagerCore.getPlugins()` → `PluginDescriptor.getPluginId()` →
 * `PluginId.getIdString()` → `PluginDescriptor.isEnabled()`），不静态引用 `PluginId` /
 * `PluginManagerCore`：该组 API 在 2025.3~2026.2 间有形态漂移与 Kotlin 可见性隐藏
 * （`update/PluginVersion.kt`、`execute/ExecuteSqlSupport.kt` 的既有实证），编译基线 2024.3 与
 * 运行 IDE 2026.2 双端直接引用有 NoClassDefFoundError 风险。
 *
 * 任何反射失败都按「不在位」处理并只 WARN 一次：不确定时不删功能，宁可多画一个图标。
 * 插件启停需重启 IDE，故结果在 object 内懒缓存；测试用 [resetForTest] 清缓存、用 [probe] 注入形态。
 */
object MyBatisCompetitorGate {

    private val LOG: Logger = Logger.getInstance(MyBatisCompetitorGate::class.java)

    /**
     * 同类 MyBatis 导航插件的 pluginId 前缀（当前登记一项）。实测本机 MPJ 3.6.8 的 id 为
     * `com.ccnode.codegenerator.MyBatisCodeHelperPro`（`META-INF/plugin.xml` 读取）；前缀匹配兜住
     * Marketplace 版与历史 id 变体。设置页文案写的是「同类插件」，与这里的登记面保持一致——
     * 将来登记更多会把跳转箭头画在同一行的同类插件时，在本表加前缀即可（探测逻辑无需改）。
     */
    internal val PEER_PLUGIN_ID_PREFIXES: List<String> = listOf("com.ccnode.codegenerator")

    private const val LOG_PREFIX = "[T-MapperNavCoexistYield]"

    /** 懒缓存结论（null = 尚未探测；插件启停需重启 IDE，故不做时效失效）。 */
    @Volatile
    private var cachedActive: Boolean? = null

    /** WARN 只打一次的开关（反射链整体失效时事前可解释，事后不刷屏）。 */
    @Volatile
    private var warned = false

    /**
     * 测试缝：非 null 时完全接管探测（在位 / 不在位 / 抛异常三形态直接注入）。
     * 置入后须 [resetForTest] 才能生效于已缓存的结论。
     */
    internal var probe: (() -> Boolean)? = null

    /**
     * 同类 MyBatis 导航插件是否有任一处于启用状态（当前登记面见 [PEER_PLUGIN_ID_PREFIXES]）。
     *
     * 语义：`true` = 对方在位且启用 → gutter 跳转图标应让位（是否真的让位还取决于用户开关，
     * 见 [shouldYield]）。探测或反射失败返回 `false`（保留本插件功能）。
     */
    fun isPeerPluginActive(): Boolean {
        cachedActive?.let { return it }
        synchronized(this) {
            cachedActive?.let { return it }
            val active = runCatching { probe?.invoke() ?: detectActive() }
                .onFailure { warnOnce(it) }
                .getOrDefault(false)
            cachedActive = active
            return active
        }
    }

    /**
     * 纯判定：用户开着让位（[yieldEnabled]）且竞品在位（[competitorActive]）才让位。
     * 抽成纯函数是为了让真值表单测不必触碰平台 API。
     */
    internal fun shouldYield(yieldEnabled: Boolean, competitorActive: Boolean): Boolean =
        yieldEnabled && competitorActive

    /** 清缓存与探针（测试专用；生产无调用点）。 */
    internal fun resetForTest() {
        synchronized(this) {
            cachedActive = null
            warned = false
            probe = null
        }
    }

    /**
     * 反射探测已加载的插件描述符。任何形态漂移返回 `false`（视为不在位），不抛异常。
     *
     * `getPlugins()` 在 243 / 262 两端 javap 实证均返回 `IdeaPluginDescriptor[]`；此处仍兼容
     * `Iterable` 形态，防 253 等中间线的容器类型漂移。方法一律解析在**公开接口**上再 invoke
     * （`PluginDescriptor` / `PluginId`），避免在 package-private 实现类上触发
     * `IllegalAccessException`（本仓既有实证的反射陷阱）。
     */
    private fun detectActive(): Boolean {
        val loader = MyBatisCompetitorGate::class.java.classLoader
        val coreClass = Class.forName("com.intellij.ide.plugins.PluginManagerCore", true, loader)
        val raw = coreClass.getMethod("getPlugins").invoke(null)
        val descriptors: List<Any?> = when (raw) {
            is Array<*> -> raw.toList()
            is Iterable<*> -> raw.toList()
            else -> return false
        }

        val descriptorClass = Class.forName("com.intellij.openapi.extensions.PluginDescriptor", true, loader)
        val pluginIdClass = Class.forName("com.intellij.openapi.extensions.PluginId", true, loader)
        val getPluginId = descriptorClass.getMethod("getPluginId")
        val isEnabled = descriptorClass.getMethod("isEnabled")
        val getIdString = pluginIdClass.getMethod("getIdString")

        for (descriptor in descriptors) {
            if (descriptor == null) continue
            val id = getPluginId.invoke(descriptor) ?: continue
            val idString = getIdString.invoke(id) as? String ?: continue
            if (PEER_PLUGIN_ID_PREFIXES.none { idString.startsWith(it) }) continue
            // 命中前缀但多个变体并存时，任一启用即让位
            if (isEnabled.invoke(descriptor) as? Boolean == true) return true
        }
        return false
    }

    private fun warnOnce(t: Throwable) {
        if (warned) return
        warned = true
        LOG.warn("$LOG_PREFIX competitor probe unavailable; treat as absent (Mapper jump icons stay visible)", t)
    }
}
