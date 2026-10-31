// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/log/CaptureDiagnostics.kt 移植
package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * T-CaptureTruth T-CT-03：捕获链路零产出诊断（SQL 日志面板空态第二行）。
 *
 * <p>立项动因：捕获零产出时面板只给一个空列表，用户无从判断卡在哪一环——本次现场（沙盒里
 * 「增强已开启但未授权 → 24h 冷却 → 不挂载」+「应用控制台本就没有 SQL 日志」两条通道同时归零）
 * 就是被这条沉默拖了整整一轮排障。诊断行把两条通道的状态摆到台面上：
 * <ul>
 * <li>增强通道：复用 {@code agent.AgentManager.statusSummary} 的五态文案
 * （未授权/冷却中/等待进程/已挂载/已回落），未开启时显示「未启用」；</li>
 * <li>控制台通道：已挂载控制台数 + 已扫描行数（{@code SqlLogConsoleBridge.consoleLinesSeen}）；</li>
 * <li>提示语按「等待控制台 / 控制台无输出 / 有输出但没匹配到 SQL」三档给出可执行的下一步。</li>
 * </ul>
 *
 * <p>T-RA-03：控制台通道再加两个「行去哪了」的计数——{@code SqlLogConsoleBridge.replayDropCount}
 * （重放守卫丢弃）与 {@code SqlLogConsoleBridge.epActiveShortCircuitCount}（EP 短路）。二者原本只在
 * 测试里读，生产侧零出口：守卫误把正常流式行判成重放、或 EP 短路后条目未落到本面板时，
 * 面板照旧显示「已扫描 N 行 / 产出 0 条」，把「有行但被吃掉」伪装成「压根没输出」。
 * 只在计数 &gt; 0 时显示——常态零值不应给正常场景添噪。
 *
 * <p>纯函数（零平台依赖）便于全分支单测；面板只负责取数（{@code SqlLogPanel.syncDiagnostics}）。
 */
public record CaptureDiagnostics(
    /** 是否处于捕获开启状态。 */
    boolean capturing,
    /** 是否仍在等待应用控制台出现（控制台通道未挂载）。 */
    boolean waitingForConsole,
    /** 面板当前条目数（&gt;0 即有产出，不出诊断行）。 */
    int entryCount,
    /** 已挂载的应用控制台数。 */
    int consolesAttached,
    /** 控制台通道已扫描行数。 */
    long linesSeen,
    /** 增强通道状态文案（null = 增强未开启）。 */
    @Nullable String enhancedStatus,
    /** 重放守卫丢弃行数（T-RA-03；0 = 无丢弃，不显示）。 */
    long replayDropped,
    /** EP 通道短路行数（T-RA-03；0 = 无短路，不显示）。 */
    long epShortCircuited,
    /**
     * T-DevAcceptFix2 ⑤（20261006 第二轮验收）：<b>增强通道已收事件数</b>（agent 事件到达并适配的累计）。
     *
     * <p>与上面两个计数不同，本字段<b>恒显示</b>（不设「非零才显示」）——它是「事件到没到」的唯一判别量：
     * 用户现场「增强捕获已挂载但产出 0 条」时，N=0 说明事件压根没到（要查 agent 侧织入/门禁），
     * N&gt;0 说明到了但被去重/屏蔽/展示链吃掉（要查消费侧）。口径 = 收到即计数（含后续被丢弃的）。
     */
    long agentEventsReceived,
    /**
     * T-DevAcceptFix2 ⑩（20261006 第三轮走查）：<b>屏蔽规则命中次数</b>（应用级累计，规则面板同源计数）。
     *
     * <p>立项动因＝用户现场「增强捕获已挂载、已收事件 8 条、列表 0 条」却无任何提示——事后查明是用户
     * 自建的<b>结构匹配</b>屏蔽规则按设计拦下了那些 SQL（入队前丢弃，设计内行为），可面板一个字没说，
     * 只能靠翻配置文件才判得出来。与重放/EP 两个计数同口径：<b>&gt; 0 才显示</b>（零值不添噪）。
     */
    long suppressed,
    /**
     * T-CaptureCatchAll 券02：控制台桥**活性回落已解封**——agent 在线但长期零事件、控制台仍有
     * SQL 行，桥已恢复解析。必须恒显示（不设「非零才显示」）：这是「增强已挂载却零产出」的
     * 唯一解释，藏起来就退回静默。
     */
    boolean activityFallbackActive,
    /**
     * T-CaptureCatchAll 券03：agent 侧 attach 覆盖自检计数（null = 旧版 agent 未上报）。
     * 零命中/零织入/有失败/接口兜底命中这几类事实按「异常才显示」进首行（见 {@link #attachCoverageNotes}）。
     */
    @Nullable cn.song.mybatisplus.visualizer.log.agent.AgentAttachStats attachStats
) {

    /** K2J 互操作：master 侧后两参为带默认值 0L 的后置字段（Kotlin 默认参）——保留历史 8 参同形重载。 */
    public CaptureDiagnostics(boolean capturing, boolean waitingForConsole, int entryCount,
                              int consolesAttached, long linesSeen, String enhancedStatus,
                              long replayDropped, long epShortCircuited) {
        this(capturing, waitingForConsole, entryCount, consolesAttached, linesSeen, enhancedStatus,
                replayDropped, epShortCircuited, 0L, 0L);
    }

    /** K2J 互操作：activityFallbackActive / attachStats 缺省（Kotlin 默认参）的历史 10 参同形重载。 */
    public CaptureDiagnostics(boolean capturing, boolean waitingForConsole, int entryCount,
                              int consolesAttached, long linesSeen, String enhancedStatus,
                              long replayDropped, long epShortCircuited,
                              long agentEventsReceived, long suppressed) {
        this(capturing, waitingForConsole, entryCount, consolesAttached, linesSeen, enhancedStatus,
                replayDropped, epShortCircuited, agentEventsReceived, suppressed, false, null);
    }

    /**
     * T-CaptureCatchAll 券03：attach 覆盖事实 → 诊断片段（纯函数，零平台依赖）。
     *
     * <p>只报<b>用户需要据此行动</b>的形态：
     * <ul>
     * <li>{@code matched == 0}：本次 attach 一个驱动都没命中 → 增强通道本会话不可能有事件，白名单/加载时机问题；</li>
     * <li>{@code matched > 0 && woven == 0}：命中了却没织上 → 织入器/可见性闸门（不是「没装 agent」）；</li>
     * <li>{@code failed > 0}：JVM 拒绝 retransform（版本/VerifyError）——数量足够说明形态漂移；</li>
     * <li>{@code matchedByInterface > 0}：接口兜底生效的<b>正向事实</b>（旧版 agent 恒缺省 0，不显示）。</li>
     * </ul></p>
     */
    public static List<String> attachCoverageNotes(
            @Nullable cn.song.mybatisplus.visualizer.log.agent.AgentAttachStats stats) {
        if (stats == null) {
            return List.of();
        }
        List<String> notes = new ArrayList<>();
        if (stats.zeroMatched()) {
            notes.add(MpSqlVisualizerBundle.message("log.diag.attachZero"));
        } else if (!stats.anyWoven()) {
            // 命中数取 matched/weavable 较大值（心跳只带 weavable 时不得报「命中 0」）
            notes.add(MpSqlVisualizerBundle.message("log.diag.attachNoWeave",
                    stats.matchedTotal(), stats.weavable(), stats.woven()));
        }
        if (stats.failed() > 0L) {
            notes.add(MpSqlVisualizerBundle.message("log.diag.attachFailed", stats.failed()));
        }
        // C1 路新增：major 版本闸挡下的类数——「命中了却没织」的一条硬原因
        if (stats.skippedByVersion() > 0L) {
            notes.add(MpSqlVisualizerBundle.message("log.diag.attachSkippedVersion", stats.skippedByVersion()));
        }
        // C1 路新增：transformer 兜底熔断（整体退回白名单）——覆盖变窄的直接证据
        if (stats.catchAllKilled()) {
            notes.add(MpSqlVisualizerBundle.message("log.diag.attachCatchAllKilled"));
        }
        if (stats.matchedByInterface() > 0L) {
            notes.add(MpSqlVisualizerBundle.message("log.diag.attachInterface",
                    stats.matchedByInterface(), stats.interfaceWoven()));
        }
        return notes;
    }

    /**
     * 诊断行文案；不需要诊断时返回 null（未捕捉 / 已有条目——空态文案由面板还原为默认）。
     */
    @Nullable
    public static String captureDiagnosticsText(@NotNull CaptureDiagnostics d) {
        if (!d.capturing || d.entryCount > 0) return null;
        String head = MpSqlVisualizerBundle.message("log.diag.capturing");
        String enhanced = d.enhancedStatus == null
            ? MpSqlVisualizerBundle.message("log.diag.enhancedOff")
            // T-DevAcceptFix2 ⑤：增强通道状态后恒挂「已收事件 N 条」——判「事件到没到」的唯一判别量
            : MpSqlVisualizerBundle.message("log.diag.enhancedWithEvents", d.enhancedStatus, d.agentEventsReceived);
        String console = MpSqlVisualizerBundle.message("log.diag.console", d.consolesAttached, d.linesSeen);
        // T-RA-03：丢弃/短路计数只在非零时挂尾——零值是常态，常态不该给诊断行添噪；
        // 非零则意味着「桥看到过行却没产出」，是排障时最先要看的两条线索。
        List<String> dropped = new ArrayList<>();
        if (d.replayDropped > 0L) {
            dropped.add(MpSqlVisualizerBundle.message("log.diag.replayDropped", d.replayDropped));
        }
        if (d.epShortCircuited > 0L) {
            dropped.add(MpSqlVisualizerBundle.message("log.diag.epShortCircuit", d.epShortCircuited));
        }
        // T-DevAcceptFix2 ⑩：屏蔽规则命中——用户自建规则的拦截是设计内行为，但必须可见
        if (d.suppressed > 0L) {
            dropped.add(MpSqlVisualizerBundle.message("log.diag.suppressed", d.suppressed));
        }
        List<String> parts = new ArrayList<>(List.of(head, enhanced, console));
        parts.addAll(dropped);
        // T-CaptureCatchAll 券02/03：增强通道的**事实补充**与状态同处首行（不新增语义行——空态折行
        // 守卫锁定「三行语义」形态，第四行会被面板右边界裁掉）：
        //  - 活性回落解封：桥为什么在产出；
        //  - attach 覆盖：为什么 agent 没产出。
        List<String> enhancedParts = new ArrayList<>();
        enhancedParts.add(enhanced);
        if (d.activityFallbackActive) {
            enhancedParts.add(MpSqlVisualizerBundle.message("log.diag.activityFallback"));
        }
        enhancedParts.addAll(attachCoverageNotes(d.attachStats));
        // T-AgentDiagWrap T-04：语义行按「读的人下一步要干什么」切——
        // 第一行「在捕捉吗 + 增强通道通不通」，第二行「控制台通道通不通 + 行被谁吃了」，
        // 第三行「所以下一步该做什么」。三者混成一行时，被面板右边界裁掉的总是第三行（可执行的那句）。
        List<String> channelParts = new ArrayList<>(enhancedParts.size() + 1);
        channelParts.add(head);
        channelParts.addAll(enhancedParts);
        String channels = String.join(SEP, channelParts);
        List<String> consoleParts = new ArrayList<>(dropped.size() + 1);
        consoleParts.add(console);
        consoleParts.addAll(dropped);
        String consoleLine = String.join(SEP, consoleParts);
        String hintKey = d.waitingForConsole
            ? "log.diag.hint.waiting"
            : (d.linesSeen <= 0L ? "log.diag.hint.noOutput" : "log.diag.hint.noSql");
        return channels + "\n" + consoleLine + "\n" + MpSqlVisualizerBundle.message(hintKey);
    }

    /** 通道片段分隔符（同一语义行内的并列项）。 */
    private static final String SEP = "｜";

    // ---------- T-AgentDiagWrap T-04：空态文案「文本 → 显示」 ----------

    /** 折行宽度下限：窗口尚未布局（首帧 / 隐藏卡）时算出的可用宽度可能是 0 或负值，clamp 到这里。 */
    public static final int EMPTY_TEXT_MIN_WIDTH_PX = 200;

    /** 列表尚未布局时的兜底宽度（不 clamp 到下限，而是给一个像样的初始排版宽度）。 */
    public static final int EMPTY_TEXT_FALLBACK_WIDTH_PX = 400;

    /** 折行时左右各留的余量：空态文案居中，贴边会让人以为还有下文。 */
    public static final int EMPTY_TEXT_EDGE_PADDING_PX = 12;

    /** 空态引导链接之间的分隔（T-SettingsFocusFromLink）。 */
    public static final String EMPTY_LINK_SEPARATOR = "　·　";

    /**
     * 空态引导链接行渲染（T-SettingsFocusFromLink，<b>唯一入口</b>）。
     *
     * <p>必须「{@code appendLine("")} 起新行 + {@code appendText} 追加」——平台 {@code StatusText.appendSecondaryText}
     * 内部固定写 <b>row=1</b>（第二行/副列语义：243 源码 {@code appendText(true, 1, …)}）：多行空态文案下
     * 用它追加链接会把链接叠印进第二行中段（20261010 真机实证「三个链接贴在一起」）；单链接历史
     * 实现显正常只因文案恰好两行、row 1 恰为末行。复发由源码守卫 {@code EmptyTextLinkApiGuardTest} 拦截。
     */
    public static void appendLinkRow(
            com.intellij.util.ui.StatusText status,
            List<kotlin.Pair<String, java.awt.event.ActionListener>> links) {
        status.appendLine("");
        for (int index = 0; index < links.size(); index++) {
            kotlin.Pair<String, java.awt.event.ActionListener> link = links.get(index);
            if (index > 0) {
                status.appendText(EMPTY_LINK_SEPARATOR);
            }
            status.appendText(link.getFirst(), com.intellij.ui.SimpleTextAttributes.LINK_ATTRIBUTES, link.getSecond());
        }
    }

    /**
     * 空态文案折行（纯函数，零平台依赖）：把「语义行（{@code \n} 分隔）」按像素宽度软折成「视觉行」列表。
     *
     * <p>为什么折行必须在写入前自己算：面板空态用的是 {@code JBList} 的 {@code StatusText}（内部是
     * {@code SimpleColoredComponent}），它<b>既不解析 HTML、也不把 {@code \n} 当换行</b>——
     * {@code \n} 被当普通字符画成零宽，两段文字被无声拼接成一行，超宽只裁不折
     * （本次现场读数里被裁掉的恰是「控制台有输出但未见 MyBatis SQL 日志……请确认 mapper 包日志级别为 debug」
     * 这句唯一可执行的提示）。平台侧唯一的多行手段是 {@code StatusText.appendLine}（每行一个 Fragment，
     * {@code paintColumnInBounds} 逐 Fragment 单独绘制），但它同样不折行——所以折行只能自己算，
     * 再把视觉行逐条 {@code appendLine}。
     *
     * @param widthPx 可用像素宽度；小于 {@link #EMPTY_TEXT_MIN_WIDTH_PX} 时按该下限排版（防 0 宽死循环/整段压平）
     * @param measure 单串像素宽度测量（渲染层传 {@code FontMetrics::stringWidth}；测试传可预测桩，保持纯函数）
     */
    public static List<String> wrapEmptyTextLines(String text, int widthPx,
            kotlin.jvm.functions.Function1<String, Integer> measure) {
        int width = widthPx < EMPTY_TEXT_MIN_WIDTH_PX ? EMPTY_TEXT_MIN_WIDTH_PX : widthPx;
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            out.addAll(wrapSingleLine(line, width, measure));
        }
        return out;
    }

    /** 单条语义行的贪心折行：优先在空白处断（英文 bundle），无空白则按字符断（中文）。 */
    private static List<String> wrapSingleLine(String line, int widthPx,
            kotlin.jvm.functions.Function1<String, Integer> measure) {
        if (line.isEmpty()) return List.of(line);
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < line.length()) {
            int width = 0;
            int i = start;
            int lastSpace = -1;
            while (i < line.length()) {
                int cw = measure.invoke(String.valueOf(line.charAt(i)));
                // i > start：首个字符即便自身超宽也必须吃掉，否则死循环
                if (width + cw > widthPx && i > start) break;
                if (line.charAt(i) == ' ') lastSpace = i;
                width += cw;
                i++;
            }
            if (i >= line.length()) {
                out.add(line.substring(start).stripTrailing());
                break;
            }
            int cut = lastSpace > start ? lastSpace : i;
            out.add(line.substring(start, cut).stripTrailing());
            start = cut;
            while (start < line.length() && line.charAt(start) == ' ') start++;
        }
        return out.isEmpty() ? List.of(line) : out;
    }
}
