package cn.song.mybatisplus.visualizer.log;

import com.intellij.openapi.util.registry.Registry;
import kotlin.jvm.functions.Function0;

/**
 * T-Nplus1Aggregate D235：N+1 嫌疑徽章聚合视图「点开看全」——开关面（模型 + 摘要构建器在
 * {@code NplusOneAggregateKt}，纯逻辑面；F-13「单一纯比较器」拆小文件先例）。
 *
 * <p>设计要点（计划册 §二）：
 * <ul>
 *   <li><b>口径一致性是生命线</b>：过滤态复用既有 {@code FocusTab.Template}（O-90「只看同结构」形态，
 *       过滤键＝徽章判定用的同一个 structureKey，见 NplusOneSuspect / buffer windowSuspectKeys）；</li>
 *   <li><b>回退开关</b>：{@link #REGISTRY_KEY}（默认开；关＝徽章纯行级，点击无过滤态；徽章本身仍由
 *       {@code NplusOneSuspect.REGISTRY_KEY} 独立控制——两层开关语义分层）。</li>
 * </ul>
 */
public final class NplusOneAggregate {

    private NplusOneAggregate() {}

    /** Registry 总开关（plugin.xml {@code <registryKey>} 登记）；关闭＝徽章纯行级、点击无过滤态。 */
    public static final String REGISTRY_KEY = "sqlvis.log.nplusOneSuspect.aggregate";

    /** 测试缝：总开关读取（生产＝{@code Registry.is(REGISTRY_KEY, true)}）。 */
    public static volatile Function0<Boolean> enabledProvider =
            () -> {
                try {
                    return Registry.is(REGISTRY_KEY, true);
                } catch (Throwable t) {
                    return true;
                }
            };

    public static boolean enabled() {
        Boolean v = enabledProvider.invoke();
        return v != null && v;
    }
}
