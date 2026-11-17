// T-SyncK2JMaster178 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inlay/NativeColumnInlayYieldGate.kt 手工翻译（object → 静态门面）
package cn.song.mybatisplus.visualizer.ui.inlay;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPointName;

import kotlin.jvm.functions.Function0;

import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * T-PreviewInlayNativeYield：INSERT 目标列 inlay 的原生让位门——检测到 IDEA 自带的
 * SQL 列名 inlay 在位时，本插件的同名提示整体不渲染，避免同一 offset 上出现两枚一样的列名胶囊。
 *
 * <p>判据取「能力级」：只看原生 provider 是否在位（providerId 或实现类简单名锚点），
 * 逐位去重不可能（平台收集期各 provider 有独立 HintsBuffer）。开启态刻意不参与判定：
 * 用户关掉原生选项表达的是「别显示列名」，此时本插件接上等于反着执行。任何探测失败按「不在位」
 * 处理（保留本插件提示）。结论进程级懒缓存，插件/EP 启停需重启 IDE。</p>
 */
public final class NativeColumnInlayYieldGate {

    private static final Logger LOG = Logger.getInstance(NativeColumnInlayYieldGate.class);

    /** 原生 provider 的 providerId（DatabaseTools plugin.xml 声明的契约锚点）。 */
    public static final String NATIVE_PROVIDER_ID = "sql.column.names.inlay.hints";

    /** 原生 provider 的实现类简单名（legacy bean 只有这一路可读，也是版本漂移兜底）。 */
    public static final String NATIVE_IMPL_SIMPLE_NAME = "SqlColumnNamesInlayHintsProvider";

    /** 声明式 EP（263 上原生注册在这里）。 */
    public static final String DECLARATIVE_EP_NAME = "com.intellij.codeInsight.declarativeInlayProvider";

    /** legacy EP（243~262 若原生按旧形态注册则在这里；本插件自己也在这条上）。 */
    public static final String LEGACY_EP_NAME = "com.intellij.codeInsight.inlayProvider";

    private static final String SQL_LANGUAGE_ID = "SQL";
    private static final String LOG_PREFIX = "[T-PreviewInlayNativeYield]";

    /** 测试缝：非 null 时完全接管探测（在位 / 不在位 / 抛异常三形态直接注入）。置入后须 {@link #resetForTest()}。 */
    @Nullable
    public static volatile Function0<Boolean> probe;

    /** 懒缓存结论（null = 尚未探测）。 */
    @Nullable
    private static volatile Boolean cachedPresent;

    /** WARN 只打一次的开关。 */
    private static volatile boolean warned;

    private NativeColumnInlayYieldGate() {
    }

    /** 是否让位（= 原生在位）。true ⇒ 本插件 INSERT 目标列提示不应渲染。 */
    public static boolean shouldYield() {
        return isNativeColumnHintPresent();
    }

    /** 原生 SQL 列名 inlay 是否在位（进程内缓存）。探测失败/形态漂移/EP 不存在 → false。 */
    public static boolean isNativeColumnHintPresent() {
        Boolean cached = cachedPresent;
        if (cached != null) {
            return cached;
        }
        synchronized (NativeColumnInlayYieldGate.class) {
            cached = cachedPresent;
            if (cached != null) {
                return cached;
            }
            boolean present;
            try {
                Function0<Boolean> p = probe;
                present = p != null ? Boolean.TRUE.equals(p.invoke()) : detect();
            } catch (Throwable t) {
                warnOnce(t);
                present = false;
            }
            cachedPresent = present;
            return present;
        }
    }

    /** 清缓存与探针（测试专用；生产无调用点）。 */
    public static void resetForTest() {
        synchronized (NativeColumnInlayYieldGate.class) {
            cachedPresent = null;
            warned = false;
            probe = null;
        }
    }

    /** 纯判定：一个扩展 bean 的 (providerId, 实现类名, language) 是否命中原生 provider。 */
    public static boolean matchesAnchor(@Nullable String providerId,
                                        @Nullable String implementationClass,
                                        @Nullable String language) {
        if (providerId != null && !providerId.isEmpty() && providerId.equals(NATIVE_PROVIDER_ID)) {
            return true;
        }
        if (implementationClass == null || implementationClass.isEmpty()) {
            return false;
        }
        // 简单名：去掉包名与内部类后缀（Foo$Bar 形态只取 Foo）
        String simple = implementationClass.substring(implementationClass.lastIndexOf('.') + 1);
        int dollar = simple.indexOf('$');
        if (dollar >= 0) {
            simple = simple.substring(0, dollar);
        }
        simple = simple.trim();
        if (!NATIVE_IMPL_SIMPLE_NAME.equals(simple)) {
            return false;
        }
        return language == null || language.isEmpty() || SQL_LANGUAGE_ID.equalsIgnoreCase(language);
    }

    /** 依次扫两代 EP；任一命中即返回 true。 */
    private static boolean detect() {
        return scanEp(DECLARATIVE_EP_NAME) || scanEp(LEGACY_EP_NAME);
    }

    /** 扫一个 EP 的扩展 bean 列表。EP 不存在或 extensionList 抛错都返回 false。 */
    private static boolean scanEp(String epName) {
        java.util.List<?> beans;
        try {
            beans = ExtensionPointName.create(epName).getExtensionList();
        } catch (Throwable t) {
            return false;
        }
        for (Object bean : beans) {
            if (bean == null) {
                continue;
            }
            boolean hit = matchesAnchor(
                    readString(bean, "getProviderId"),
                    readString(bean, "getImplementationClass", "getImplementationClassName"),
                    readString(bean, "getLanguage"));
            if (hit) {
                return true;
            }
        }
        return false;
    }

    /** 反射读 bean 上的字符串属性：按候选方法名逐个试，读不到返回 null。 */
    @Nullable
    private static String readString(Object bean, String... methodNames) {
        for (String name : methodNames) {
            try {
                Method method = bean.getClass().getMethod(name);
                Object value = method.invoke(bean);
                if (value instanceof String s && !s.isEmpty()) {
                    return s;
                }
            } catch (Throwable ignored) {
                // 换下一个候选方法名
            }
        }
        return null;
    }

    private static void warnOnce(Throwable t) {
        if (warned) {
            return;
        }
        warned = true;
        LOG.warn(LOG_PREFIX + " native column-hint probe unavailable; treat as absent (plugin INSERT column hints stay visible)", t);
    }
}
