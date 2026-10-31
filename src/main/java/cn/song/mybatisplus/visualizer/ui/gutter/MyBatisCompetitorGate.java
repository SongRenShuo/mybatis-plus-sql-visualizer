// T-SyncK2JMaster175 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/MyBatisCompetitorGate.kt
package cn.song.mybatisplus.visualizer.ui.gutter;

import com.intellij.openapi.diagnostic.Logger;

import kotlin.jvm.functions.Function0;

import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * T-MapperNavCoexistYield：gutter 面的「同类插件共存让位」门——检测到同类 MyBatis 导航插件
 * （当前登记 MyBatisCodeHelperPro，下称 MPJ）处于启用状态时，本插件的 Mapper 双向跳转图标让位，
 * 不再与对方画在同一行的跳转箭头叠在一起。登记面见 {@link #PEER_PLUGIN_ID_PREFIXES}。
 *
 * <h2>与 XmlSynHiGate 的关系（两面，勿混）</h2>
 *
 * <p>{@code cn.song.mybatisplus.visualizer.ui.inject.XmlSynHiGate} 的区间级探测解决的是<b>注入/着色</b>面：
 * 「对方是否真的给这段区间上了色」，在位 ≠ 覆盖，故必须逐区间问。gutter 图标不是区间问题——
 * 同一行两个来源各画一个图标<b>都合规</b>，纪律是「对方在位且启用即让位」，判据取插件启停，
 * 不做「对方是否真画了」的运行期推断（那是无法收敛的猜测）。</p>
 *
 * <h2>API 形态与降级</h2>
 *
 * <p>探测走<b>全反射</b>（{@code PluginManagerCore.getPlugins()} → {@code PluginDescriptor.getPluginId()} →
 * {@code PluginId.getIdString()} → {@code PluginDescriptor.isEnabled()}），不静态引用 {@code PluginId} /
 * {@code PluginManagerCore}：该组 API 在 2025.3~2026.2 间有形态漂移与 Kotlin 可见性隐藏
 * （{@code update/PluginVersion.kt}、{@code execute/ExecuteSqlSupport.kt} 的既有实证），编译基线 2024.3 与
 * 运行 IDE 2026.2 双端直接引用有 NoClassDefFoundError 风险。</p>
 *
 * <p>任何反射失败都按「不在位」处理并只 WARN 一次：不确定时不删功能，宁可多画一个图标。
 * 插件启停需重启 IDE，故结果在 object 内懒缓存；测试用 {@link #resetForTest} 清缓存、用 {@link #probe} 注入形态。</p>
 *
 * <p>移植注记：master 的 {@code object} 按本仓惯例落 final class + {@link #INSTANCE} + 全静态成员，
 * {@code internal} 升 public；{@code synchronized(this)} 落 {@code synchronized(INSTANCE)}；
 * Kotlin 函数类型字段 {@code (() -> Boolean)?} 落 {@link Function0}{@code <Boolean>}（本仓既有先例
 * IdeErrorReportSubmitter/JdbcTemplateLogParser 同款）。</p>
 */
public final class MyBatisCompetitorGate {

    public static final MyBatisCompetitorGate INSTANCE = new MyBatisCompetitorGate();

    private MyBatisCompetitorGate() {
    }

    private static final Logger LOG = Logger.getInstance(MyBatisCompetitorGate.class);

    /**
     * 同类 MyBatis 导航插件的 pluginId 前缀（当前登记一项）。实测本机 MPJ 3.6.8 的 id 为
     * {@code com.ccnode.codegenerator.MyBatisCodeHelperPro}（{@code META-INF/plugin.xml} 读取）；前缀匹配兜住
     * Marketplace 版与历史 id 变体。设置页文案写的是「同类插件」，与这里的登记面保持一致——
     * 将来登记更多会把跳转箭头画在同一行的同类插件时，在本表加前缀即可（探测逻辑无需改）。
     */
    public static final List<String> PEER_PLUGIN_ID_PREFIXES = List.of("com.ccnode.codegenerator");

    private static final String LOG_PREFIX = "[T-MapperNavCoexistYield]";

    /** 懒缓存结论（null = 尚未探测；插件启停需重启 IDE，故不做时效失效）。 */
    @Nullable
    private static volatile Boolean cachedActive;

    /** WARN 只打一次的开关（反射链整体失效时事前可解释，事后不刷屏）。 */
    private static volatile boolean warned;

    /**
     * 测试缝：非 null 时完全接管探测（在位 / 不在位 / 抛异常三形态直接注入）。
     * 置入后须 {@link #resetForTest} 才能生效于已缓存的结论。
     */
    @Nullable
    public static volatile Function0<Boolean> probe;

    /**
     * 同类 MyBatis 导航插件是否有任一处于启用状态（当前登记面见 {@link #PEER_PLUGIN_ID_PREFIXES}）。
     *
     * <p>语义：{@code true} = 对方在位且启用 → gutter 跳转图标应让位（是否真的让位还取决于用户开关，
     * 见 {@link #shouldYield}）。探测或反射失败返回 {@code false}（保留本插件功能）。</p>
     */
    public static boolean isPeerPluginActive() {
        Boolean cached = cachedActive;
        if (cached != null) {
            return cached;
        }
        synchronized (INSTANCE) {
            cached = cachedActive;
            if (cached != null) {
                return cached;
            }
            boolean active;
            try {
                Function0<Boolean> p = probe;
                active = p != null ? p.invoke() : detectActive();
            } catch (Throwable t) {
                warnOnce(t);
                active = false; // runCatching{...}.onFailure{...}.getOrDefault(false)
            }
            cachedActive = active;
            return active;
        }
    }

    /**
     * 纯判定：用户开着让位（yieldEnabled）且竞品在位（competitorActive）才让位。
     * 抽成纯函数是为了让真值表单测不必触碰平台 API。
     */
    public static boolean shouldYield(boolean yieldEnabled, boolean competitorActive) {
        return yieldEnabled && competitorActive;
    }

    /** 清缓存与探针（测试专用；生产无调用点）。 */
    public static void resetForTest() {
        synchronized (INSTANCE) {
            cachedActive = null;
            warned = false;
            probe = null;
        }
    }

    /**
     * 反射探测已加载的插件描述符。任何形态漂移返回 {@code false}（视为不在位），不抛异常
     * （受检反射异常上抛由 {@link #isPeerPluginActive} 的 catch 承接）。
     *
     * <p>{@code getPlugins()} 在 243 / 262 两端 javap 实证均返回 {@code IdeaPluginDescriptor[]}；此处仍兼容
     * {@code Iterable} 形态，防 253 等中间线的容器类型漂移。方法一律解析在<b>公开接口</b>上再 invoke
     * （{@code PluginDescriptor} / {@code PluginId}），避免在 package-private 实现类上触发
     * {@code IllegalAccessException}（本仓既有实证的反射陷阱）。</p>
     */
    private static boolean detectActive() throws ReflectiveOperationException {
        ClassLoader loader = MyBatisCompetitorGate.class.getClassLoader();
        Class<?> coreClass = Class.forName("com.intellij.ide.plugins.PluginManagerCore", true, loader);
        Object raw = coreClass.getMethod("getPlugins").invoke(null);
        List<Object> descriptors;
        if (raw instanceof Object[]) {
            descriptors = Arrays.asList((Object[]) raw);
        } else if (raw instanceof Iterable) {
            descriptors = new ArrayList<>();
            for (Object descriptor : (Iterable<?>) raw) {
                descriptors.add(descriptor);
            }
        } else {
            return false;
        }

        Class<?> descriptorClass = Class.forName("com.intellij.openapi.extensions.PluginDescriptor", true, loader);
        Class<?> pluginIdClass = Class.forName("com.intellij.openapi.extensions.PluginId", true, loader);
        Method getPluginId = descriptorClass.getMethod("getPluginId");
        Method isEnabled = descriptorClass.getMethod("isEnabled");
        Method getIdString = pluginIdClass.getMethod("getIdString");

        for (Object descriptor : descriptors) {
            if (descriptor == null) {
                continue;
            }
            Object id = getPluginId.invoke(descriptor);
            if (id == null) {
                continue;
            }
            Object idStringRaw = getIdString.invoke(id);
            if (!(idStringRaw instanceof String)) {
                continue; // Kotlin `as? String ?: continue` 语义
            }
            String idString = (String) idStringRaw;
            boolean matched = false;
            for (String prefix : PEER_PLUGIN_ID_PREFIXES) {
                if (idString.startsWith(prefix)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                continue; // none { startsWith } 语义
            }
            // 命中前缀但多个变体并存时，任一启用即让位
            if (Boolean.TRUE.equals(isEnabled.invoke(descriptor))) {
                return true;
            }
        }
        return false;
    }

    private static void warnOnce(Throwable t) {
        if (warned) {
            return;
        }
        warned = true;
        LOG.warn(LOG_PREFIX + " competitor probe unavailable; treat as absent (Mapper jump icons stay visible)", t);
    }
}
