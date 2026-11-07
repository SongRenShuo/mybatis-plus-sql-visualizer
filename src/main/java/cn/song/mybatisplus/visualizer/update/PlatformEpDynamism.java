package cn.song.mybatisplus.visualizer.update;

import java.util.ArrayList;
import java.util.List;

/**
 * 平台 EP 动态性事实表（T-HotSwapUnloadSafety，2026-11-06 立项）。
 *
 * <h2>为什么需要这张表</h2>
 *
 * <p>平台的动态插件机制有一条硬规则：<b>插件只要扩展了非动态（{@code dynamic="true"} 缺席）的 EP，
 * 就不可被动态卸载</b>（{@code DynamicPlugins.allowLoadUnloadWithoutRestart} 返回 false，平台自己在
 * idea.log 打 {@code Plugin … is not unload-safe because of extension to non-dynamic EP …}）。
 * 更新链因此在最后一步回退「重启生效」——这不是插件 bug，是平台判据。</p>
 *
 * <p>2026-11-06 用户报「内测更新渠道还是提示重启」，真机日志（243 沙盒 {@code log_runIdeHotSwap/idea.log}
 * 18:11:05）逐字命中上面这条，点名的是 {@code com.intellij.sql.dialect}——我们在
 * {@code META-INF/database-optional.xml} 里为 DM / GaussDB / HighGo / Vastbase 注册的那四个方言。</p>
 *
 * <h2>结论（真机取证，不是推断）</h2>
 *
 * <table border="1">
 *   <caption>平台 EP 动态性取证表</caption>
 *   <tr><th>平台</th><th>{@code com.intellij.sql.dialect}</th><th>本插件可热卸载</th></tr>
 *   <tr><td>243（IU-2024.3.7.1）</td><td><b>无 {@code dynamic}</b></td><td>❌ 只能重启生效</td></tr>
 *   <tr><td>253（IU-253.33514.17）</td><td>{@code dynamic="true"}</td><td>✅</td></tr>
 *   <tr><td>262（2026.2 最新发行）</td><td>{@code dynamic="true"}</td>
 *       <td>✅（用户 2026-11-06 真机确认「262 能正常热更新」，与本结论一致）</td></tr>
 *   <tr><td>263（IU-263.7307.200）</td><td>{@code dynamic="true"}</td><td>✅</td></tr>
 * </table>
 *
 * <p>取证方法：对官方发行 zip 按 HTTP Range 只抽 {@code plugins/DatabaseTools/lib/database-plugin.jar}
 * 及其 {@code META-INF/plugin.xml}（不落 1.6GB 整包），脚本见
 * {@code dev-support/probe-platform-ep-dynamism.py}（可复跑）。243 为本地解包目录直读，两处
 * （{@code plugin.xml} 与 {@code SqlPluginCore.xml}）均无 {@code dynamic}。</p>
 *
 * <p>时间线佐证：{@code <sql.dialect>} 注册自 2026-10-14（达梦，{@code 6199a2a7}）起陆续加入；仓库里
 * 2026-09-11 那次「免重启热替换全链实测通过」发生在这批注册之前——即能力是<b>后来退化</b>的，
 * 不是 beta.2 的探测修复没生效。</p>
 *
 * <p>本表是「运行时拒绝原因」与「静态守卫」的唯一事实来源：改动前先重跑取证脚本，
 * 平台若哪天把 243 那条也标成 dynamic，或再把某个 EP 收回成非动态，这里与文案必须一起改。</p>
 *
 * <p>k2j 翻译注：master 侧为 Kotlin {@code internal object}；Java 承载为静态门面
 * （{@code DynamicInstallSupport.platformEpDynamismExplanation} 以
 * {@code Class.forName + getMethod("refusalExplanation", int.class)} 静态反射探达，
 * {@link #refusalExplanation(int)} 签名逐字保持）。</p>
 */
public final class PlatformEpDynamism {

    private PlatformEpDynamism() {
    }

    /**
     * 一个「我们扩展了、但在部分目标平台上非动态」的 EP。
     *
     * @param qualifiedName         平台的 EP 全名（与描述文件里解析出的名字逐字一致）
     * @param dynamicSinceBaseline  平台从该 baseline 版本（含）起把它标为 {@code dynamic="true"}；
     *                              小于它的平台上，扩展该 EP 即意味着本插件不可热卸载
     * @param evidence              取证出处（哪个平台、哪个文件、什么时候测的）
     * @param declaredIn            本插件声明该扩展的描述文件（静态守卫据此对账：声明文件里必须真有它）
     */
    public record NonDynamicEp(String qualifiedName, int dynamicSinceBaseline, String evidence, String declaredIn) {
    }

    /** 四个自注册方言共用的平台 EP（见 {@code META-INF/database-optional.xml} 的 {@code <sql.dialect>}）。 */
    public static final NonDynamicEp SQL_DIALECT = new NonDynamicEp(
            "com.intellij.sql.dialect",
            253,
            "243 IU-2024.3.7.1：database-plugin.jar!/META-INF/plugin.xml 与 SqlPluginCore.xml 两处声明均无 dynamic；"
                    + "253 IU-253.33514.17 与 263 IU-263.7307.200：同一 EP 声明带 dynamic=\"true\""
                    + "（2026-11-06 发行 zip Range 抽包实测，见 dev-support/probe-platform-ep-dynamism.py）",
            "META-INF/database-optional.xml");

    /** 全部登记项。新增条目必须带非空 {@link NonDynamicEp#evidence()}，守卫会校验。 */
    public static final List<NonDynamicEp> EXTENSIONS = List.of(SQL_DIALECT);

    /** 在给定平台 baseline 上会挡住热卸载的条目（空 = 可热卸载）。 */
    public static List<NonDynamicEp> blockingOn(int baselineVersion) {
        List<NonDynamicEp> result = new ArrayList<>();
        for (NonDynamicEp extension : EXTENSIONS) {
            if (baselineVersion < extension.dynamicSinceBaseline()) {
                result.add(extension);
            }
        }
        return result;
    }

    /** 该平台版本上本插件是否可被动态卸载。 */
    public static boolean isUnloadSafeOn(int baselineVersion) {
        return blockingOn(baselineVersion).isEmpty();
    }

    /**
     * 面向日志的确定原因（该平台版本上存在阻塞项时）；可热卸载时返回 null，
     * 此时调用方应回落「平台未给原因 + idea.log 指路」。
     */
    public static String refusalExplanation(int baselineVersion) {
        List<NonDynamicEp> blocking = blockingOn(baselineVersion);
        if (blocking.isEmpty()) return null;
        List<String> names = new ArrayList<>();
        int since = Integer.MAX_VALUE;
        for (NonDynamicEp extension : blocking) {
            names.add(extension.qualifiedName());
            since = Math.min(since, extension.dynamicSinceBaseline());
        }
        return "本插件扩展了非动态 EP " + String.join("、", names)
                + "（平台自 baseline " + since + " 起才把它标为 dynamic，当前是 " + baselineVersion + "），"
                + "平台据此拒绝卸载本插件，更新只能走重启生效";
    }
}
