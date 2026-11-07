package cn.song.mybatisplus.visualizer.update;

/**
 * 开发者模式（T-DevUpdateChannel）：设置页「构建版本号」在 {@link #CLICK_WINDOW_MS} 内连点
 * {@link #REQUIRED_CLICKS} 次即解锁；解锁后多出「检测更新（开发版）」按钮，读
 * {@code UpdateSource.PREVIEW_MANIFEST_PATH} 指向的预览清单。
 *
 * <p>状态<b>只在内存</b>（D-2）：不解锁就不存在、不写配置，支持排查时能区分「用户真开了」与
 * 「残留」.{@code -Dmpvs.update.devMode=true} 是自动化/截图用的直通开关，与
 * {@code mpvs.update.manifest.url} 同口径——类首次触达时读一次，启动后改属性需重启。</p>
 *
 * <p>连点窗口按「与上一次点击的间隔 ≤ {@link #CLICK_WINDOW_MS}」判定（D-1：每次点击都重开窗口），
 * 所以慢慢点不会累积；间隔超窗则计数从 1 重新开始。</p>
 *
 * <p>k2j 翻译注：Kotlin 侧为 {@code object}——Java 落 final 工具类 + 静态方法
 * （常量/parseEnabledProperty/registerClick 默认参数重载形态照 master）。</p>
 */
public final class DevUpdateMode {

    private DevUpdateMode() {
    }

    /** 解锁所需连点次数（D-1：Android「关于手机」惯例量级，7 次够防误触）。 */
    public static final int REQUIRED_CLICKS = 7;

    /** 连点窗口：相邻两次点击的最大间隔（毫秒）。 */
    public static final long CLICK_WINDOW_MS = 2_000L;

    /** 直通系统属性（{@code true} 即视为已解锁；大小写不敏感）。 */
    public static final String ENABLED_PROPERTY = "mpvs.update.devMode";

    /** 属性直通值（类首次触达时固化，同既有换源属性口径）。 */
    private static final boolean ENABLED_BY_PROPERTY = parseEnabledProperty(System.getProperty(ENABLED_PROPERTY));

    /** {@code -D} 直通口径：去空白后等于 {@code true}（大小写不敏感）；其余（含未设/空串/{@code false}）均不解锁。 */
    static boolean parseEnabledProperty(String raw) {
        return raw != null && raw.trim().equalsIgnoreCase("true");
    }

    private static volatile boolean unlocked = false;

    private static volatile int clickCount = 0;

    private static volatile long lastClickAt = 0L;

    /** 开发模式是否可用（已连点解锁，或属性直通）。 */
    public static boolean isEnabled() {
        return unlocked || ENABLED_BY_PROPERTY;
    }

    /** 直接解锁（测试与「已知要开发模式」的自动化路径；用户路径走 {@link #registerClick()}）。 */
    public static void unlock() {
        unlocked = true;
        clickCount = 0;
        lastClickAt = 0L;
    }

    /** 记录一次对解锁入口的点击（真实时刻形态；Kotlin 默认实参的等价重载）。 */
    public static boolean registerClick() {
        return registerClick(System.currentTimeMillis());
    }

    /**
     * 记录一次对解锁入口的点击；本次点击达阈值时解锁并返回 true（调用方据此刷新 UI）。
     *
     * @param now 点击时刻（毫秒；测试注入固定时钟）。
     */
    public static synchronized boolean registerClick(long now) {
        clickCount = (now - lastClickAt >= 0 && now - lastClickAt <= CLICK_WINDOW_MS) ? clickCount + 1 : 1;
        lastClickAt = now;
        if (clickCount >= REQUIRED_CLICKS) {
            unlock();
            return true;
        }
        return false;
    }

    /** 清空内存态（测试隔离用；不暴露给产品路径）。 */
    static void resetForTest() {
        unlocked = false;
        clickCount = 0;
        lastClickAt = 0L;
    }
}
