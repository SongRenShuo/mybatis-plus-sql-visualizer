package cn.song.mybatisplus.visualizer.update

/**
 * 开发者模式（T-DevUpdateChannel）：设置页「构建版本号」在 [CLICK_WINDOW_MS] 内连点
 * [REQUIRED_CLICKS] 次即解锁；解锁后多出「检测更新（开发版）」按钮，读
 * [UpdateSource.PREVIEW_MANIFEST_PATH] 指向的预览清单。
 *
 * 状态**只在内存**（D-2）：不解锁就不存在、不写配置，支持排查时能区分「用户真开了」与
 * 「残留」。`-Dmpvs.update.devMode=true` 是自动化/截图用的直通开关，与
 * `mpvs.update.manifest.url` 同口径——object 首次触达时读一次，启动后改属性需重启。
 *
 * 连点窗口按「与上一次点击的间隔 ≤ [CLICK_WINDOW_MS]」判定（D-1：每次点击都重开窗口），
 * 所以慢慢点不会累积；间隔超窗则计数从 1 重新开始。
 */
object DevUpdateMode {

    /** 解锁所需连点次数（D-1：Android「关于手机」惯例量级，7 次够防误触）。 */
    const val REQUIRED_CLICKS = 7

    /** 连点窗口：相邻两次点击的最大间隔（毫秒）。 */
    const val CLICK_WINDOW_MS = 2_000L

    /** 直通系统属性（`true` 即视为已解锁；大小写不敏感）。 */
    const val ENABLED_PROPERTY = "mpvs.update.devMode"

    /** 属性直通值（object 首次触达时固化，同既有换源属性口径）。 */
    private val enabledByProperty: Boolean = parseEnabledProperty(System.getProperty(ENABLED_PROPERTY))

    /** `-D` 直通口径：去空白后等于 `true`（大小写不敏感）；其余（含未设/空串/`false`）均不解锁。 */
    internal fun parseEnabledProperty(raw: String?): Boolean =
        raw?.trim()?.equals("true", ignoreCase = true) == true

    @Volatile
    private var unlocked = false

    @Volatile
    private var clickCount = 0

    @Volatile
    private var lastClickAt = 0L

    /** 开发模式是否可用（已连点解锁，或属性直通）。 */
    fun isEnabled(): Boolean = unlocked || enabledByProperty

    /** 直接解锁（测试与「已知要开发模式」的自动化路径；用户路径走 [registerClick]）。 */
    fun unlock() {
        unlocked = true
        clickCount = 0
        lastClickAt = 0L
    }

    /**
     * 记录一次对解锁入口的点击；本次点击达阈值时解锁并返回 true（调用方据此刷新 UI）。
     *
     * @param now 点击时刻（毫秒；测试注入固定时钟，默认真实时）。
     */
    @Synchronized
    fun registerClick(now: Long = System.currentTimeMillis()): Boolean {
        clickCount = if (now - lastClickAt in 0..CLICK_WINDOW_MS) clickCount + 1 else 1
        lastClickAt = now
        if (clickCount >= REQUIRED_CLICKS) {
            unlock()
            return true
        }
        return false
    }

    /** 清空内存态（测试隔离用；不暴露给产品路径）。 */
    internal fun resetForTest() {
        unlocked = false
        clickCount = 0
        lastClickAt = 0L
    }
}
