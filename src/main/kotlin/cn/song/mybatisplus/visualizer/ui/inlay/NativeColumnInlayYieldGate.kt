package cn.song.mybatisplus.visualizer.ui.inlay

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.ExtensionPointName

/**
 * T-PreviewInlayNativeYield：INSERT 目标列 inlay 的**原生让位门**——检测到 IDEA 自带的
 * SQL 列名 inlay 在位时，本插件的同名提示整体不渲染，避免同一 offset 上出现两枚一样的列名胶囊。
 *
 * ## 原生那一侧是谁（263 发行物实测，IU-263.7307.200）
 *
 * `plugins/DatabaseTools/lib/database-plugin.jar!/META-INF/plugin.xml`：
 *
 * ```xml
 * <codeInsight.declarativeInlayProvider providerId="sql.column.names.inlay.hints" language="SQL"
 *     implementationClass="com.intellij.sql.inlays.SqlColumnNamesInlayHintsProvider"
 *     isEnabledByDefault="true" group="PARAMETERS_GROUP">
 *   <option optionId="sql.show.column.names.in.insert.values" enabledByDefault="true"/>
 *   <option optionId="sql.show.column.names.in.select"        enabledByDefault="true"/>
 * </codeInsight.declarativeInlayProvider>
 * ```
 *
 * 即「INSERT … VALUES 的值前挂目标列名」，与本插件 O-44b 逐字同一件事；`in.select` 一项还覆盖
 * `INSERT … SELECT` 投影分支。两者默认全开，故不装不配也会重复。
 *
 * ## 为什么判据只能取「能力级」，不做「逐位判定」（这是本门的设计前提）
 *
 * 直觉做法是「投提示前先看这个 offset 上原生有没有画」。平台实证否决它（平台 263，类均在
 * `lib/intellij.platform.lang.impl.jar`）：
 *
 *  - `InlayHintsSinkImpl` 不直接写编辑器，而是写进 `HintsBuffer`（收口 `complete()`）；
 *  - `CollectorWithSettings` 给**每个 collector 一个独立 sink**，收集结束才 `sink.complete()`
 *    出一份 per-provider 的 buffer；
 *  - `InlayHintsPass$Companion.applyCollected(...)` 在**全部收集完成之后**才
 *    `editor.inlayModel.addInlineElement(...)`。
 *
 * ⇒ 收集期各 provider 互相看不见；再叠上平台把分片元素并发交给各 collector（本仓
 * [InsertColumnInlayGateTest] 的 KDoc 已就 243 源码实证过并发分片），「先看对方画没画」是一个
 * 必输的竞态。故本门只看「原生是否在位」这一个静态事实。
 *
 * ## 开启态**刻意不参与判定**（D2-A）
 *
 * 用户在 IDEA 设置里关掉 `sql.show.column.names.in.insert.values`（或整组 SQL 列名提示），表达的
 * 意图就是「别在我这儿显示列名」。此时本插件若接上，等于把用户的关闭动作反着执行。故本门不看
 * 原生选项的开闭、也不看原生 provider 的启停：**在位即让位**。这条与
 * [cn.song.mybatisplus.visualizer.ui.gutter.MyBatisCompetitorGate] 同一条纪律——判据取「对方在位」，
 * 不做「对方是否真画了」的运行期推断（那是无法收敛的猜测）。
 *
 * 代价（已知残余，计划 §六 已登记）：原生在位但该表在本项目解析不到时，两侧都不显示列名。
 *
 * ## 探测链与降级方向
 *
 * 枚举**两代 EP**（原生在 243~262 若按旧 EP 注册也能命中）：
 *
 *  1. `com.intellij.codeInsight.declarativeInlayProvider`（263 上原生走这条；EP 声明 beanClass =
 *     `com.intellij.codeInsight.hints.declarative.InlayHintsProviderExtensionBean`，`dynamic="true"`
 *     ⇒ `extensionList` 返回 bean 实例、不触发 provider 实例化）；
 *  2. `com.intellij.codeInsight.inlayProvider`（legacy，本插件自己也注册在这条上；
 *     beanClass = `com.intellij.codeInsight.hints.InlayHintsProviderExtensionBean`）。
 *
 * 命中判据取**双锚点候选链**：`providerId == "sql.column.names.inlay.hints"`（DatabaseTools 自己
 * 声明的契约，声明式 bean 才有）**或**实现类简单名 == `SqlColumnNamesInlayHintsProvider`
 * （legacy bean 只有实现类名可读，也是版本漂移时的兜底）。属性一律反射读（`getProviderId` /
 * `getImplementationClass` / `getImplementationClassName` / `getLanguage`）——两代 bean 的公开
 * 接口在版本间有过形态漂移，静态引用 `InlayHintsProviderExtensionBean` 会绑死编译基线。
 *
 * **任何探测失败都按「不在位」处理**（只 WARN 一次）：方向必须是「保留本插件提示」，不因探测链
 * 失效把功能丢掉。同理，`extensionList` 抛错、EP 在旧平台不存在，都归到「不在位」。
 *
 * ## 缓存
 *
 * 结论懒缓存到进程级（插件/EP 启停需重启 IDE，不做时效失效；形态照
 * [MyBatisCompetitorGate] 的 `@Volatile` 双检）。测试用 [probe] 注入形态、用 [resetForTest] 清槽。
 */
object NativeColumnInlayYieldGate {

    private val LOG: Logger = Logger.getInstance(NativeColumnInlayYieldGate::class.java)

    /** 原生 provider 的 providerId（DatabaseTools `plugin.xml` 声明的契约锚点）。 */
    internal const val NATIVE_PROVIDER_ID: String = "sql.column.names.inlay.hints"

    /** 原生 provider 的实现类简单名（legacy bean 只有这一路可读，也是版本漂移兜底）。 */
    internal const val NATIVE_IMPL_SIMPLE_NAME: String = "SqlColumnNamesInlayHintsProvider"

    /** 声明式 EP（263 上原生注册在这里）。 */
    internal const val DECLARATIVE_EP_NAME: String = "com.intellij.codeInsight.declarativeInlayProvider"

    /** legacy EP（243~262 若原生按旧形态注册则在这里；本插件自己也在这条上）。 */
    internal const val LEGACY_EP_NAME: String = "com.intellij.codeInsight.inlayProvider"

    /** SQL 语言 id（`Language.findLanguageByID("SQL")` 的 id；bean 上的 language 属性是字符串）。 */
    private const val SQL_LANGUAGE_ID: String = "SQL"

    private const val LOG_PREFIX = "[T-PreviewInlayNativeYield]"

    /**
     * 测试缝：非 null 时完全接管探测（在位 / 不在位 / 抛异常三形态直接注入）。
     * 置入后须 [resetForTest] 才能生效于已缓存的结论。
     */
    @Volatile
    internal var probe: (() -> Boolean)? = null

    /** 懒缓存结论（null = 尚未探测）。 */
    @Volatile
    private var cachedPresent: Boolean? = null

    /** WARN 只打一次的开关（探测链整体失效时事前可解释，事后不刷屏）。 */
    @Volatile
    private var warned = false

    /**
     * 是否让位（= 原生在职）。语义与 [MyBatisCompetitorGate.shouldYield] 同向：`true` ⇒ 本插件的
     * INSERT 目标列提示不应渲染。
     */
    fun shouldYield(): Boolean = isNativeColumnHintPresent()

    /**
     * 原生 SQL 列名 inlay 是否在位（进程内缓存）。
     *
     * @return `true` = 在位 → 让位；探测失败/形态漂移/EP 不存在 → `false`（保留本插件提示）
     */
    fun isNativeColumnHintPresent(): Boolean {
        cachedPresent?.let { return it }
        synchronized(this) {
            cachedPresent?.let { return it }
            val present = runCatching { probe?.invoke() ?: detect() }
                .onFailure { warnOnce(it) }
                .getOrDefault(false)
            cachedPresent = present
            return present
        }
    }

    /** 清缓存与探针（测试专用；生产无调用点）。 */
    internal fun resetForTest() {
        synchronized(this) {
            cachedPresent = null
            warned = false
            probe = null
        }
    }

    /**
     * 纯判定：一个扩展 bean 的 `(providerId, 实现类名, language)` 是否命中原生 provider。
     *
     * 抽成纯函数是为了让锚点真值表单测不必触碰平台 EP（三参形态对照
     * [MyBatisCompetitorGate.shouldYield] 的纯判定口径）。
     *
     * @param providerId          bean 的 providerId（legacy bean 上没有，传 null）
     * @param implementationClass bean 的实现类全名（读不到传 null）
     * @param language            bean 上的 language 属性（读不到传 null）
     */
    internal fun matchesAnchor(providerId: String?, implementationClass: String?, language: String?): Boolean {
        if (!providerId.isNullOrEmpty() && providerId == NATIVE_PROVIDER_ID) return true
        if (implementationClass.isNullOrEmpty()) return false
        // 简单名：去掉包名与内部类后缀（`Foo$Bar` 形态只取 `Foo`）
        val simple = implementationClass.substringAfterLast('.').substringBefore('$').trim()
        if (simple != NATIVE_IMPL_SIMPLE_NAME) return false
        // 语言锚：能读到 language 时必须是 SQL；读不到（旧 bean 形态漂移）只靠类名这一路，
        // 这种放宽是安全的——同一个类名不会在别的语言上注册第二次。
        return language.isNullOrEmpty() || language.equals(SQL_LANGUAGE_ID, ignoreCase = true)
    }

    /** 依次扫两代 EP；任一命中即返回 true。 */
    private fun detect(): Boolean = scanEp(DECLARATIVE_EP_NAME) || scanEp(LEGACY_EP_NAME)

    /**
     * 扫一个 EP 的扩展 bean 列表。
     *
     * `ExtensionPointName.create` 按名字查（不静态引用 bean 类），EP 在旧平台不存在或 `extensionList`
     * 抛错都返回 `false`（= 该路无命中，继续下一条 EP）。
     */
    private fun scanEp(epName: String): Boolean {
        val beans = runCatching { ExtensionPointName.create<Any>(epName).extensionList }.getOrNull() ?: return false
        for (bean in beans) {
            if (bean == null) continue
            val hit = matchesAnchor(
                providerId = readString(bean, "getProviderId"),
                implementationClass = readString(bean, "getImplementationClass", "getImplementationClassName"),
                language = readString(bean, "getLanguage"),
            )
            if (hit) return true
        }
        return false
    }

    /**
     * 反射读 bean 上的字符串属性：按候选方法名逐个试，读不到（无此方法 / 返回非 String / 抛异常）
     * 即换下一个，全空返回 null。
     */
    private fun readString(bean: Any, vararg methodNames: String): String? {
        for (name in methodNames) {
            val value = runCatching { bean.javaClass.getMethod(name).invoke(bean) as? String }.getOrNull()
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }

    private fun warnOnce(t: Throwable) {
        if (warned) return
        warned = true
        LOG.warn("$LOG_PREFIX native column-hint probe unavailable; treat as absent (plugin INSERT column hints stay visible)", t)
    }
}