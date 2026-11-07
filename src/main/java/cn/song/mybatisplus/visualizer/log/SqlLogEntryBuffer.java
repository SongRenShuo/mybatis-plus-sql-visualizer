package cn.song.mybatisplus.visualizer.log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 日志条目缓冲（T-M31-03，纯逻辑，不依赖平台）：条目列表（最早在前、上限裁剪）、
 * 暂停态、去重（对齐 MybatisCodeHelper-Pro「移除重复的sql」——相同 SQL 在窗口内重复丢弃）、
 * 监听器登记——线程安全（任意线程可 {@link #addEntry(LogSqlEntry)}，监听器回调不在此处调度）。
 *
 * <p>T-PerfBatch 监听器分层：原先单一 notifyChanged 通道同时承载三种及时性语义——
 * 首条自动开窗（须即时）、徽章计数（须即时）、列表内容（可批量）——任何「取消逐条通知」
 * 的批量节流都会同时切断前两者（初版全量回归 11 败的根因）。分层后拆为三个独立出口：
 * {@link #setOnEntryAccepted}（每次成功入队，自动展开等「有新 SQL」语义，即时）、
 * {@link #setOnDedupeCountsChanged}（徽章计数变更，即时）、内容监听器（{@link #addListener}，仅列表内容
 * 变更，入队路径走「数量 20 + 时间 200ms」节流，未达阈值条目由 {@link #setOnContentNotifySuppressed}
 * 兜底给管理器的幂等 flush）。
 *
 * <p>与平台壳 {@link SqlLogManager} 分离：本类可纯 JUnit 测试（无 Application 依赖）。
 */
public class SqlLogEntryBuffer {

    private static final int DEFAULT_MAX_ENTRIES = 200;
    private static final long DEFAULT_DEDUPE_WINDOW_MS = 60000L;

    /** T-M55-02：错误反向绑定时间窗默认值（慢查询执行期异常打印仍落在窗口内）。 */
    private static final long DEFAULT_ERROR_BIND_WINDOW_MS = 5 * 60_000L;

    /** T-PerfBatch：内容通知数量阈值——时间窗内累计 N 条才再通知（首条不受限，即时）。 */
    private static final int CONTENT_NOTIFY_BATCH = 20;

    /** T-PerfBatch：内容通知时间阈值（200ms）——距上次通知超窗即即时通知，保证低流量等价即时。 */
    private static final long CONTENT_NOTIFY_INTERVAL_NANOS = 200_000_000L;

    /** T-PerfSweep F3：去重丢弃计数键上限——超限淘汰最旧（见 dedupeCounts 字段注释）。 */
    private static final int MAX_DEDUPE_COUNT_KEYS = 4096;

    /** 归因诊断限频窗（10s）：缺源码入队与跨通道融合两条诊断共用。 */
    private static final long ATTRIB_DIAG_INTERVAL_NANOS = 10_000_000_000L;

    /** 接管沿重建索引时保留的新鲜键窗（5s）：同一次执行双通道副本间隔为毫秒级。 */
    private static final long HANDOFF_MERGE_GRACE_MS = 5_000L;

    /**
     * T-CrossChannelDedupe：跨通道等价键兜底折叠的时间窗（5s）。
     *
     * <p>取 5s 的依据与 {@link #HANDOFF_MERGE_GRACE_MS} 同源：同一次执行被两通道各捕一次的间隔是毫秒级
     * （真机实测 6ms）；而同一模板的两次独立执行在正常业务里不会落在 5s 内还带完全相同的参数值。
     * 远小于 60s 去重窗——兜底只救「同一次执行的两份观测」，不扩大既有的去重语义。</p>
     */
    private static final long CROSS_CHANNEL_WINDOW_MS = 5_000L;

    /** 最大条目数（可变——运行时动态调整上限，超出自动淘汰最早）。 */
    private volatile int maxEntries;

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<LogSqlEntry> entries = new ArrayDeque<>();

    /** T-LogRowNumber：会话内捕获序号计数器（成功入队时分配；启动项目/手动清空归零。可从任意线程调用故原子）。 */
    private final AtomicLong sequenceCounter = new AtomicLong(0);

    /** T-Perf-03（自 dev 移植）：自上次 drain 以来的头部淘汰条数（面板增量刷新对齐用；drain 约定单消费方——面板 EDT 刷新）。 */
    private final java.util.concurrent.atomic.AtomicInteger evictedSinceDrain = new java.util.concurrent.atomic.AtomicInteger();

    /** T-PerfBatch：内容通知节流原子量——时间窗内累计的未通知条数与上次通知时刻（无 Timer）。 */
    private final AtomicInteger pendingContentNotify = new AtomicInteger(0);
    private final AtomicLong lastContentNotifyNanos = new AtomicLong(0);

    // ---------- T-SqlListCatchup 券B：计数变更键增量出口 ----------

    /**
     * T-SqlListCatchup 券B：内容通知节流窗内累积的「计数变更键」集合（键 = dedupeKey）。
     * 内容侧每发生一次会改变展示层计数的队列变更（新条目入队 / ⨉N 重复丢弃 / 头部淘汰 /
     * removeEntry），把受影响的 dedupeKey 记进来；面板 {@link #drainChangedKeys()} 消费后按键增量更新
     * 徽章累积 map，不再每拍全量重算。
     *
     * <p>有界性（照 {@link #dedupeKeyPresence} KDoc 口径）：风暴期同键反复变化<b>只记键不记次数</b>（Set 语义），
     * 集合上限以现存键域封顶——键随淘汰/清空同步出域（{@link #clear()} 一并清空），不引入新增长面。
     * 自身锁独立（{@code synchronized(changedCountKeys)}），登记发生在 {@code synchronized(entries)} 内
     * （与 presence 维护同锁登记），锁序恒为 entries → changedCountKeys，无反向获取。</p>
     */
    private final java.util.HashSet<String> changedCountKeys = new java.util.HashSet<>();

    /**
     * T-SqlListCatchup 券B：计数全量重建标记——增量口径的<b>失效事件点</b>置位，面板 drain 到后
     * 回退全量重算一次（对齐 P6 既有「已知事件点全量重建」口径）。置位点及各自为什么必须全量：
     * <ul>
     * <li>{@link #setDedupeEnabled(boolean)} 翻转：false 期间 presence 冻结不维护，true 时索引全量重建——键域与
     *   presence 都可能整体跳变，增量键集表达不了；</li>
     * <li>{@link #rebuildDedupeIndexAfterHandoff(long, long)}：接管沿索引按 grace 截断只留新鲜键而条目仍留队，
     *   索引键域与 presence 口径分叉，按键取 structureKey 的出口可能失配；</li>
     * <li>{@link #clear()}：全清事件，累积 map 必须与 buffer 双双清零；
     *   淘汰把键扫出键域（presence 计数归零摘键）——<b>券E 升级为增量</b>：淘汰是确定性核销
     *   （presence 减一、归零摘键），被淘汰键已随 {@link #markCountKeyLocked} 进变更键集，面板按
     *   presence 当下值与镜像求净差即可正确扣账（structureKey 从键域内期缓存取，键出域时
     *   缓存仍在），无需回退全量。等价性证据见 SqlLogCountAccumulatorEquivalenceTest 的
     *   「恒满异键风暴」场景（buffer 恒满、键持续进出的随机序列逐键逐值相等）；</li>
     * <li>去重关闭期间的入队/淘汰与不可去重条目（ShardingSphere）变更：presence 口径外，
     *   增量键集无法表达；</li>
     * <li>{@link #dedupeCounts} 键上限溢出（静默淘汰最旧键）：键集无法表达「哪个键被逐出」。</li>
     * </ul>
     *
     * <p>原子量：置位发生在任意生产线程（含 entries 锁内），消费在面板 EDT——getAndSet 保证
     * 「置位落在消费窗口内不丢标记」（标记落两次 drain 之间时本拍即走全量，不产生陈旧窗口）。</p>
     */
    private final java.util.concurrent.atomic.AtomicBoolean countFullRebuildPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 暂停态。 */
    private volatile boolean paused;

    /** 去重开关（默认开——用户实测后要求默认勾选，T-M31.13）。
     *  T-SqlLogPerf P6：从 false 翻 true 时全量重建 [dedupeIndex]——关闭期间入队不走索引维护，
     *  重开必须补账，否则新窗口内的重复漏判（等价性红线，见 isDuplicate 注释）。 */
    private volatile boolean dedupeEnabled = true;

    /** O-147：已存条目的元数据升级，不是新捕获；锁外回调，不追加第二条历史。 */
    public interface EntryMergeListener {
        void onMerged(LogSqlEntry oldEntry, LogSqlEntry merged);
    }

    private volatile EntryMergeListener onEntryMerged = null;

    public EntryMergeListener getOnEntryMerged() {
        return onEntryMerged;
    }

    public void setOnEntryMerged(EntryMergeListener listener) {
        this.onEntryMerged = listener;
    }

    /**
     * UI013：Logic 未绑定，文本相同不能证明同一次执行；Actual 仅属该次配对，禁止裁剪或融合。
     * 反向验证锚点：临时改为 true，UI013 自动/手动保真用例必须变红。
     */
    private static boolean canDedupe(LogSqlEntry entry) {
        return entry.source() != SqlSource.SHARDINGSPHERE;
    }

    /**
     * T-SqlLogPerf P6（20261006-1020 计划）：dedupeKey → 队内**最近**时间戳索引——isDuplicate
     * 从锁内 O(n) 全表扫描（65 条/秒压测下每条线性扫 200 条 + 三通道写线程与 EDT snapshot 抢锁）
     * 降为 O(1) 查表。窗口判定取每键最大时间戳与旧 firstOrNull 全扫逐字等价：
     * 「存在同键条目使 now-timestamp &lt; 窗口」⟺ 最大时间戳落窗口内。
     *
     * <p>维护面（全部须在 {@code synchronized(entries)} 内）：入队 upsert / 头部淘汰回扫 / dedupeExisting
     * 与 dedupeEnabled 翻 true 全量重建 / clear 清空 / removeEntry 单键重算。dedupeEnabled=false
     * 期间不维护（不触 dedupeKey lazy——T-PerfSweep9 同款口径），重开时补账。
     */
    private final HashMap<String, LogSqlEntry> dedupeIndex = new HashMap<>();

    /**
     * T-PerfSweep26-01（F83）：dedupeKey → <b>队内现存条数</b>计数——淘汰回扫的短路判据。被淘汰者恰为
     * 索引最新时，仅当同键仍有余条（计数 &gt;0）才付 O(maxEntries) 全队反扫重指次新；唯一键稳态计数
     * 归零，直接摘索引跳过必空的反扫（原状：唯一键下每接受一条新 SQL 都在 {@code synchronized(entries)}
     * 内付一次 O(maxEntries) 反扫且结果必空）。
     *
     * <p>有界性：键域 = {@link #dedupeIndex} 键域（canDedupe 条目的 dedupeKey），随索引同步增删、
     * {@link #clear} 一并清空，不引入新增长面。维护面（全部在 {@code synchronized(entries)} 内、与索引
     * 同条件）：接受入队 +1 / 头部淘汰 -1（先减后判）/ removeEntry -1 / {@link #rebuildDedupeIndexLocked}
     * 与 {@link #rebuildDedupeIndexAfterHandoff} 全量重建 / clear 清空。dedupeEnabled=false 期间不维护
     * 也不读（stale 值零消费，重开时随索引补账）；接管沿重建按「队内现存」口径补账（索引按 grace 截断
     * 只留新鲜键，条目仍留队的陈旧键计数不缺账）。融合（mergeDuplicateEntry）原位替换不改键
     * （restoredSql 保留），计数不动。
     */
    private final HashMap<String, Integer> dedupeKeyPresence = new HashMap<>();

    /** T-PerfSweep26-01（F83）测试缝：淘汰路径全队反扫（{@code lastEntryWithKey} 扫描）执行计数——
     *  唯一键稳态下应恒 0，仅测试断言用（V38 计数缝同款形态），生产零读。 */
    private final java.util.concurrent.atomic.AtomicInteger evictionRescanCount = new java.util.concurrent.atomic.AtomicInteger();

    public int getEvictionRescanCountForTest() {
        return evictionRescanCount.get();
    }

    /**
     * T-CrossChannelDedupe（20261105）：<b>跨通道等价索引</b>——crossKey → 队内<b>保留实例</b>。
     *
     * <p>为什么需要它：{@link #dedupeIndex} 的键是「参数还原后的 SQL 指纹」（{@code LogSqlEntry.dedupeKey()}），而两个
     * 通道各有一套还原器（控制台 LogSqlRestorer / agent AgentEventAdapter.restore），布尔参数、
     * 日期族、注释内 {@code ?}、参数个数守卫、全限定类型名任一处口径分叉就产出不同指纹——同一次执行于是
     * 各占一行（用户报障：同一 INSERT 一条带耗时/位置、一条全是 {@code —}）。本索引改用<b>两侧同源、不经
     * 参数还原</b>的原材料：{@code LogSqlModelsKt.logTimeKey}（占位符版 rawSql 指纹）+ 参数值签名，即
     * T-AgentLogTime 已验证能跨通道命中的口径。</p>
     *
     * <p>只在 {@link #dedupeIndex} 未命中时才查（命中即走既有同键融合，零额外成本）；命中后先走
     * {@link #canFoldAcrossChannels}（两侧通道不同、时间差 ≤ {@link #CROSS_CHANNEL_WINDOW_MS}）的窗内折叠；
     * <b>T-SqlListCatchup 券 F1：超窗时改走 {@link #findStaleSameSourceCandidateLocked} 的越窗兜底对账</b>
     * （去重开启态、异通道标记、仍在去重窗内 → 折叠）——控制台条目经平台 ConsoleViewImpl 冲刷
     * 延迟 &gt;5s 到达时窗内折叠必然错过，此前同一次执行就此留两行（真机第三轮压测缺陷）。</p>
     *
     * <p>维护面与 {@link #dedupeIndex} <b>完全同锁同生命周期</b>（全部在 {@code synchronized(entries)} 内）：入队 upsert /
     * 头部淘汰摘键 / 融合原位替换重指 / {@link #removeEntry} 单键重算 / {@link #clear()} 清空 /
     * {@link #rebuildDedupeIndexLocked()} 与 {@link #rebuildDedupeIndexAfterHandoff(long, long)} 全量重建。</p>
     *
     * <p>有界性（券 F1 调整）：键域随条目淘汰同步摘除（索引存该键<b>最后入队</b>实例、淘汰取<b>最旧</b>，索引
     * 命中被淘汰者即说明队内已无同键余条，无需反扫）。<b>不再做 5s 时间窗惰性清除</b>——超窗键是越窗
     * 兜底的候选源，清了它兜底就永远接不住迟到副本；键域仍以队内条目数封顶（淘汰/删除/清空同步出域），
     * 驻留面无新增长点。接管沿重建（{@link #rebuildDedupeIndexAfterHandoff(long, long)}）仍按 grace 只留新鲜键——越窗
     * 兜底对被截断的键天然无候选，P3「attach 即生效」可见性不受本调整影响。</p>
     *
     * <p>误配面（计划 §五-4 已裁决可接受）：同一语句 5s 内以相同参数执行多次时，跨通道配对按 FIFO 可能
     * 错位——融合只补空不覆盖，错位只影响毫秒级耗时与同一条语句同一栈帧的位置，不产生错误数据。</p>
     */
    private final HashMap<String, LogSqlEntry> crossChannelIndex = new HashMap<>();

    /** T-CrossChannelDedupe：跨通道等价键（两侧同源原材料，见 {@link #crossChannelIndex}）。 */
    private String crossChannelKey(LogSqlEntry entry) {
        return LogSqlModelsKt.logTimeKey(entry) + '\u0002' + LogSqlModelsKt.logTimeParamSignature(entry.params());
    }

    /** T-CrossChannelDedupe：两侧时间差是否落在跨通道折叠窗内（毫秒级同一次执行）。 */
    private boolean withinCrossChannelWindow(LogSqlEntry a, LogSqlEntry b) {
        long delta = b.timestamp() - a.timestamp();
        return (delta < 0 ? -delta : delta) <= CROSS_CHANNEL_WINDOW_MS;
    }

    /**
     * T-CrossChannelDedupe：跨通道折叠判据——通道口径见 {@link #crossChannelProvenanceFoldable}（两侧已知
     * 异通道、或恰好一侧已知可折；两侧同已知通道 / 两侧都 UNKNOWN 不折），且时间差在窗内。
     *
     * <p>同通道已知标记一律不折叠：同通道同 crossKey 而异 dedupeKey 不是「两份观测」，是同一通道内的两条
     * 不同语句（或同一渲染器两次不同输出），折叠会吞掉真实条目。</p>
     */
    private boolean canFoldAcrossChannels(LogSqlEntry existing, LogSqlEntry incoming) {
        if (!crossChannelProvenanceFoldable(existing, incoming)) return false;
        return withinCrossChannelWindow(existing, incoming);
    }

    /**
     * T-SqlListCatchup 券 F1：跨通道对账的<b>通道半边</b>判据——两侧已知同通道不可对账（同通道同
     * crossKey 而异 dedupeKey 是真不同语句/两次渲染，不是两份观测）；<b>两侧都未打标
     * （{@link CaptureProvenance#UNKNOWN}）也不可对账</b>——观测合并的语义前提是「可识别的两条通道」，
     * UNKNOWN 对 UNKNOWN 的折叠会把历史读回/旧条目的同语句条目误折，并让「去重关闭=全保留」的
     * 既有语义失真（本券取消时间清除后由旧时间戳夹具暴露：purge 此前在静默挡住该形态）；
     * 一侧已知（含 MERGED 保留行）→ 可对账。窗内折叠（{@link #canFoldAcrossChannels}）与越窗兜底
     * （{@link #findStaleSameSourceCandidateLocked}）共用，保证两路折叠的通道口径永不漂移。
     */
    private boolean crossChannelProvenanceFoldable(LogSqlEntry existing, LogSqlEntry incoming) {
        if (existing.captureProvenance() != CaptureProvenance.UNKNOWN
                && incoming.captureProvenance() != CaptureProvenance.UNKNOWN
                && existing.captureProvenance() == incoming.captureProvenance()) {
            return false;
        }
        if (existing.captureProvenance() == CaptureProvenance.UNKNOWN
                && incoming.captureProvenance() == CaptureProvenance.UNKNOWN) {
            return false;
        }
        return true;
    }

    /**
     * T-SqlListCatchup 券 F1：<b>越窗兜底对账候选</b>（调用方须持 {@code synchronized(entries)}；仅在既有
     * 同键去重与 5s 窗内跨通道折叠都未命中、即将开新行的分支里调用）。
     *
     * <p>候选 = {@link #crossChannelIndex} 命中的该 crossKey 最后入队实例（跨通道索引已取消时间清除，超窗键
     * 仍在场），须同时满足：
     * <ul>
     * <li><b>超窗</b>：不在 {@link #CROSS_CHANNEL_WINDOW_MS} 内（窗内未折只会是同通道闸门拦下，另案处理）；</li>
     * <li><b>异通道标记</b>：{@link #crossChannelProvenanceFoldable} 成立（同通道已知标记不互折）；</li>
     * <li><b>去重窗内</b>：{@code entry.timestamp - candidate.timestamp < dedupeWindowMs}（与 {@link #isDuplicate}
     *   同口径的单边窗判定——兜底不扩大去重语义：同一条语句 60s 后的再执行照常开新行）。</li>
     * </ul></p>
     *
     * <p>红线：融合走 {@code LogSqlModelsKt.mergeDuplicateEntry}（只补空不覆盖——agent 先到的耗时/角标/位置全保留）；接管沿
     * grace 截断摘掉的键不在索引里 → 本函数天然无候选 → P3「attach 即生效」可见性不回破。</p>
     */
    private LogSqlEntry findStaleSameSourceCandidateLocked(LogSqlEntry entry, LogSqlEntry crossHit) {
        if (crossHit == null) return null;
        if (withinCrossChannelWindow(crossHit, entry)) return null;
        if (!crossChannelProvenanceFoldable(crossHit, entry)) return null;
        if (entry.timestamp() - crossHit.timestamp() >= dedupeWindowMs) return null;
        return crossHit;
    }

    /** java.util.ArrayDeque 无按下标替换：按同一性重建（融合低频，O(n) 锁内可接受）。 */
    private void replaceEntryLocked(LogSqlEntry target, LogSqlEntry replacement) {
        ArrayDeque<LogSqlEntry> rebuilt = new ArrayDeque<>(entries.size());
        for (LogSqlEntry e : entries) rebuilt.add(e == target ? replacement : e);
        entries.clear();
        entries.addAll(rebuilt);
    }

    /** T-CrossChannelDedupe：融合换实例后把跨通道索引重指到队内新实例（键不变——rawSql/params 不参与融合）。 */
    private void repointCrossChannelLocked(LogSqlEntry previous, LogSqlEntry merged) {
        String ck = crossChannelKey(previous);
        if (crossChannelIndex.get(ck) == previous) {
            crossChannelIndex.put(ck, merged);
        }
    }

    private void rebuildDedupeIndexLocked() {
        // 券B：索引随队全量重建（dedupeExisting 裁剪 / 开关翻 true）——presence 同拍全量重算，
        // 面板累积 map 必须同拍回退全量锚定（增量键集表达不了重建前后的键域跳变）。
        countFullRebuildPending.set(true);
        dedupeIndex.clear();
        rebuildDedupeKeyPresenceLocked(); // F83：计数随索引一并全量重建
        crossChannelIndex.clear(); // T-CrossChannelDedupe：跨通道索引同锁同生命周期全量重建
        for (LogSqlEntry e : entries) {
            if (!canDedupe(e)) continue;
            LogSqlEntry latest = dedupeIndex.get(e.dedupeKey());
            if (latest == null || e.timestamp() > latest.timestamp()) {
                dedupeIndex.put(e.dedupeKey(), e);
            }
            crossChannelIndex.put(crossChannelKey(e), e);
        }
    }

    /**
     * F83：键级在队计数全量重建——按「队内现存」口径统计（不看索引成员资格：接管沿重建只把陈旧键
     * 摘出索引、条目仍留队，计数必须把这类在队不在索引的实例也补上）。调用方须已持 {@code synchronized(entries)}。
     */
    private void rebuildDedupeKeyPresenceLocked() {
        dedupeKeyPresence.clear();
        for (LogSqlEntry e : entries) {
            if (!canDedupe(e)) continue;
            Integer current = dedupeKeyPresence.get(e.dedupeKey());
            dedupeKeyPresence.put(e.dedupeKey(), (current != null ? current : 0) + 1);
        }
    }

    /** {@code entries.lastOrNull { it.dedupeKey == k }} 的等价实现（自队尾向队首扫描取第一个同键条目）。 */
    private LogSqlEntry lastEntryWithKey(String k) {
        return lastEntryWithKey(k, null);
    }

    /** UI013：带谓词形态——Sharding 条目不参与去重索引（{@code lastOrNull { canDedupe(it) && ... }}）。 */
    private LogSqlEntry lastEntryWithKey(String k, java.util.function.Predicate<LogSqlEntry> predicate) {
        for (java.util.Iterator<LogSqlEntry> dit = entries.descendingIterator(); dit.hasNext(); ) {
            LogSqlEntry e = dit.next();
            if (e.dedupeKey().equals(k) && (predicate == null || predicate.test(e))) {
                return e;
            }
        }
        return null;
    }

    /**
     * T-AttachZeroEvent P3（2026-10-09 现场复盘）：<b>通道纪元重置</b>——增强捕获接管沿（桥让位、
     * agent 接管，{@code SqlCaptureGate.paused} false→true）由 {@code AgentManager.updateOnline} 调用。
     *
     * <p>为什么重置：dedupeKey 跨通道统一（agent 与桥同 SQL 产出同指纹，AgentEventAdapter 契约），
     * 目的是折叠接管/回落<b>重叠期</b>的双通道双报；桥一旦封停，重叠窗语义即结束——接管前桥纪元的
     * 索引不得再吞 agent 纪元的首批同键事件。现场：接管前 60s 内桥刚捕过同端点，接管后同 SQL 的
     * agent 事件全部落窗口内被当重复丢弃，面板零新条目、「attach 即生效」看似失效。
     *
     * <p>只清索引，<b>不清条目、不清 ⨉N 徽章计数</b>（历史留痕）；此后 agent↔agent 短窗重复照常折叠
     * （索引由新纪元条目在入队 upsert 面重建）。O(1)（清 map），entries 锁内执行。
     * 回落沿（true→false）<b>不</b>调用本方法——理由见 {@code AgentManager.updateOnline} 尾注。
     */
    public void rebuildDedupeIndexAfterHandoff() {
        rebuildDedupeIndexAfterHandoff(HANDOFF_MERGE_GRACE_MS, System.currentTimeMillis());
    }

    public void rebuildDedupeIndexAfterHandoff(long graceMs) {
        rebuildDedupeIndexAfterHandoff(graceMs, System.currentTimeMillis());
    }

    public void rebuildDedupeIndexAfterHandoff(long graceMs, long nowMs) {
        synchronized (entries) {
            // 券B：接管沿重建是计数增量口径的失效点（grace 截断让索引键域与 presence 分叉——
            // 条目仍留队的陈旧键被摘出索引，按键取 structureKey 的出口会失配），面板回退全量一次。
            countFullRebuildPending.set(true);
            dedupeIndex.clear();
            // T-CrossChannelDedupe：跨通道索引与 dedupeIndex 同口径——接管沿同样只留「新鲜键」。
            // 陈旧键若留在跨通道索引里，接管后 agent 首批事件会被折进桥纪元条目（P3 症状的跨通道形态）。
            crossChannelIndex.clear();
            // T-DedupeDecouple：跨通道索引与去重开关解耦——去重关闭期间它照样要重建，
            // 否则「关掉 SQL 去重」会让兜底整段失效（catch 只在 dedupeEnabled 时建索引）。
            if (dedupeEnabled) {
                rebuildDedupeKeyPresenceLocked(); // F83：计数按队内现存全量补账（与索引的 grace 截断口径不同，条目仍留队的陈旧键不缺账）
            }
            long cutoff = nowMs - graceMs;
            for (LogSqlEntry entry : entries) {
                if (!canDedupe(entry) || entry.timestamp() < cutoff) continue;
                if (dedupeEnabled) {
                    LogSqlEntry latest = dedupeIndex.get(entry.dedupeKey());
                    if (latest == null || entry.timestamp() > latest.timestamp()) {
                        dedupeIndex.put(entry.dedupeKey(), entry);
                    }
                }
                crossChannelIndex.put(crossChannelKey(entry), entry);
            }
        }
    }

    /**
     * 归因诊断限频：{@code ATTRIB_DIAG_INTERVAL_NANOS} 窗内首条即时输出、其余累计抑制数随下次输出带上。
     * message 为惰性——被抑制时不构造字符串。
     * 平台 Logger 经 {@link #LOG()} 每次取（纯 JUnit 不触发诊断的用例零平台触达）。
     *
     * <p><b>T-CaptureGateStability P3（2026-11-05 真机实测）</b>：跨通道折叠类诊断改用<b>独立通道</b>
     * （{@link #crossChannelLogNanos}）。此前三类共用本通道，而控制台条目入队必然先打「捕获归因」（缺源码），
     * 紧随其后的折叠日志被同一 10s 窗吃掉——真机复验四次折叠、日志一条都没有，现场无法确认折叠发生。</p>
     */
    private final AtomicLong attributionLogNanos = new AtomicLong(Long.MIN_VALUE / 2);
    private final AtomicLong attributionSuppressed = new AtomicLong();

    /** P3：跨通道折叠/未折叠诊断的独立限频通道（不与「捕获归因」互相压窗口）。 */
    private final AtomicLong crossChannelLogNanos = new AtomicLong(Long.MIN_VALUE / 2);
    private final AtomicLong crossChannelSuppressed = new AtomicLong();

    private void logAttributionThrottled(java.util.function.Supplier<String> message) {
        logThrottled(attributionLogNanos, attributionSuppressed, message);
    }

    private void logCrossChannelThrottled(java.util.function.Supplier<String> message) {
        logThrottled(crossChannelLogNanos, crossChannelSuppressed, message);
    }

    private void logThrottled(AtomicLong nanos, AtomicLong suppressed, java.util.function.Supplier<String> message) {
        long now = System.nanoTime();
        long last = nanos.get();
        if (now - last >= ATTRIB_DIAG_INTERVAL_NANOS && nanos.compareAndSet(last, now)) {
            long dropped = suppressed.getAndSet(0);
            String text = message.get();
            LOG().info(dropped > 0L ? text + "（期间另有 " + dropped + " 条同形态未逐条记）" : text);
        } else {
            suppressed.incrementAndGet();
        }
    }

    /**
     * T-CaptureGateStability P3 测试缝：两条诊断通道各自的「最近一次真正输出」时刻。
     * 初值 {@code Long.MIN_VALUE / 2}（与字段初值同源）表示从未输出。仅供 {@code SqlLogEntryBufferTest}
     * 断言两者<b>互不吞窗口</b>（改回共用通道时第二个断言必红），生产零读。
     */
    long attributionDiagStampForTest() {
        return attributionLogNanos.get();
    }

    long crossChannelDiagStampForTest() {
        return crossChannelLogNanos.get();
    }

    /** 诊断用平台 Logger 懒加载：纯 JUnit 用例不触诊断时零平台依赖。 */
    private com.intellij.openapi.diagnostic.Logger LOG() {
        return com.intellij.openapi.diagnostic.Logger.getInstance(SqlLogEntryBuffer.class);
    }

    /**
     * 去重丢弃计数（T-Perf-19：键 = restoredSql 指纹 sqlFingerprint，非 SQL 原文——
     * 原文键在条目淘汰后文本永久驻留，长会话可达数十 MB）：去重开启期间每丢弃
     * 一条相同 SQL 计数 +1，供列表「⨉N」徽章展示该 SQL 被去掉过几次。
     * 仅在 dedupeEnabled 为 true 时累计；{@link #clear()} 与 {@link #resetDedupeCounts()} 归零。
     *
     * <p>T-PerfSweep F3 有界化：插入序 LinkedHashMap + 上限 {@link #MAX_DEDUPE_COUNT_KEYS} 淘汰最旧
     * （原 ConcurrentHashMap 无界，条目淘汰后计数不随移除，十万级去重会话可累积百万级键）。
     * 徽章「本次去重开启以来的总丢弃量」语义在上限窗口内不变；超限淘汰最旧属极端会话的
     * 软降级（&gt;4096 种不同 SQL 且都发生过重复），只丢最旧键的计数，列表内容不受影响。
     */
    private final LinkedHashMap<String, Integer> dedupeCounts = new LinkedHashMap<>();

    /** 去重窗口（毫秒）：相同还原 SQL 在该窗口内重复出现时丢弃后到者。 */
    private volatile long dedupeWindowMs = DEFAULT_DEDUPE_WINDOW_MS;

    /** 过滤关键字（T-M31.14，`;` 分隔后传入）：还原 SQL 包含任一关键字则丢弃。 */
    private volatile List<String> filterKeywords = List.of();

    /**
     * T-SqlSuppress（O-69）：屏蔽规则指纹集合（各规则规范化文本的 {@link LogSqlModelsKt#sqlFingerprint}，由
     * {@link SqlSuppressRuleManager} 的项目订阅者 {@link SqlLogManager} 整体替换写入）。入队闸门命中即丢弃——
     * 语义与关键字过滤一致（「从未捕获过」：不进去重计数、不占捕获序号、不触发任何内容/徽章/
     * 入队回调）。空集时短路零成本（不触 {@code entry.dedupeKey()} 的惰性计算——无规则的常态）。
     */
    private volatile Set<String> suppressKeys = Set.of();

    /** F20（T-PerfSweep9）：键集中是否存在结构口径键——与 {@link #suppressKeys} 同点写入（SqlLogManager
     *  订阅者同步刷写），false 时结构键第二查找整体跳过（structureKey lazy 不触达）。 */
    private volatile boolean hasStructureKeys = false;

    /**
     * T-SqlSuppress：屏蔽闸门命中回调（参数 = 命中指纹键）——{@link SqlSuppressRuleManager#recordHit}
     * 命中计数的唯一出口（管理对话框「已拦截 N 次」，D4）。专用通道，不与既有回调混用：
     * 被拦条目不触发 onEntryAccepted/onEntryStored/onDedupeCountsChanged/内容监听（从未捕获过）。
     */
    private Consumer<String> onSuppressedHit = null;

    /** 条目被淘汰时的回调（最早条目超出上限时被移除）；用于自动保存历史 SQL（F-43）。 */
    private Consumer<LogSqlEntry> onEvicted = null;

    /**
     * T-SqlHistoryFull：成功入队回调（非暂停/过滤/去重丢弃）——历史「全量」写入出口，带条目标。
     * 与 {@link #onEntryAccepted} 的分工：onEntryAccepted 是「有新 SQL」即时语义（无参数）；
     * 本回调把成功入队条目交给写盘方（自动保存），每条成功入队触发一次。
     */
    private Consumer<LogSqlEntry> onEntryStored = null;

    /**
     * T-PerfBatch：每次成功入队（非暂停/过滤/去重丢弃）即回调——「有新 SQL」语义（自动展开）
     * 的即时性出口；去重丢弃分支不回调（重复 SQL 不是新 SQL，且空缓冲不可能产生重复）。
     */
    private Runnable onEntryAccepted = null;

    /**
     * T-PerfBatch：徽章计数变更出口（去重丢弃/裁剪计数/关闭清零/清空列表归零）——
     * ⨉N 徽章必须即时刷新（用户实测「不及时」是缺陷），不走内容节流。
     */
    private Runnable onDedupeCountsChanged = null;

    /**
     * T-PerfBatch：内容通知被节流抑制时的兜底出口——管理器把它接到幂等 flush 上，
     * 保证未达阈值条目最迟在下一次 EDT flush 可见（flush 读当下快照，不丢条目）。
     */
    private Runnable onContentNotifySuppressed = null;

    public SqlLogEntryBuffer() {
        this(DEFAULT_MAX_ENTRIES);
    }

    public SqlLogEntryBuffer(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public void setMaxEntries(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean isDedupeEnabled() {
        return dedupeEnabled;
    }

    /** Kotlin {@code var dedupeEnabled} 的 JVM 访问器等价入口（Kotlin 调用方按属性名读取）。 */
    public boolean getDedupeEnabled() {
        return dedupeEnabled;
    }

    /**
     * 去重开关 setter（Kotlin 自定义 setter 的等价翻译）：从 false 翻 true 时全量重建索引
     * （关闭期间入队不走索引维护，重开必须补账）。
     */
    public void setDedupeEnabled(boolean dedupeEnabled) {
        boolean changed = dedupeEnabled != this.dedupeEnabled;
        this.dedupeEnabled = dedupeEnabled;
        if (changed) {
            // 券B：开关翻转是计数增量口径的失效点——false 期间 presence 冻结不维护、true 时索引
            // 全量重建，面板累积 map 都须回退全量重算一次（P6 重建口径对齐，见 countFullRebuildPending KDoc）。
            countFullRebuildPending.set(true);
            if (dedupeEnabled) {
                synchronized (entries) {
                    rebuildDedupeIndexLocked();
                }
            }
        }
    }

    public long getDedupeWindowMs() {
        return dedupeWindowMs;
    }

    public void setDedupeWindowMs(long dedupeWindowMs) {
        this.dedupeWindowMs = dedupeWindowMs;
    }

    public List<String> getFilterKeywords() {
        return filterKeywords;
    }

    public void setFilterKeywords(List<String> filterKeywords) {
        this.filterKeywords = filterKeywords;
    }

    public Set<String> getSuppressKeys() {
        return suppressKeys;
    }

    public void setSuppressKeys(Set<String> suppressKeys) {
        this.suppressKeys = suppressKeys;
    }

    public boolean getHasStructureKeys() {
        return hasStructureKeys;
    }

    public void setHasStructureKeys(boolean hasStructureKeys) {
        this.hasStructureKeys = hasStructureKeys;
    }

    public Consumer<String> getOnSuppressedHit() {
        return onSuppressedHit;
    }

    public void setOnSuppressedHit(Consumer<String> onSuppressedHit) {
        this.onSuppressedHit = onSuppressedHit;
    }

    public Consumer<LogSqlEntry> getOnEvicted() {
        return onEvicted;
    }

    public void setOnEvicted(Consumer<LogSqlEntry> onEvicted) {
        this.onEvicted = onEvicted;
    }

    public Consumer<LogSqlEntry> getOnEntryStored() {
        return onEntryStored;
    }

    public void setOnEntryStored(Consumer<LogSqlEntry> onEntryStored) {
        this.onEntryStored = onEntryStored;
    }

    public Runnable getOnEntryAccepted() {
        return onEntryAccepted;
    }

    public void setOnEntryAccepted(Runnable onEntryAccepted) {
        this.onEntryAccepted = onEntryAccepted;
    }

    public Runnable getOnDedupeCountsChanged() {
        return onDedupeCountsChanged;
    }

    public void setOnDedupeCountsChanged(Runnable onDedupeCountsChanged) {
        this.onDedupeCountsChanged = onDedupeCountsChanged;
    }

    public Runnable getOnContentNotifySuppressed() {
        return onContentNotifySuppressed;
    }

    public void setOnContentNotifySuppressed(Runnable onContentNotifySuppressed) {
        this.onContentNotifySuppressed = onContentNotifySuppressed;
    }

    /** 计数登记（去重丢弃/裁剪共用）；超限淘汰最旧键。 */
    private void recordDedupeDrop(String key) {
        synchronized (dedupeCounts) {
            dedupeCounts.merge(key, 1, Integer::sum);
            if (dedupeCounts.size() > MAX_DEDUPE_COUNT_KEYS) {
                var it = dedupeCounts.keySet().iterator();
                it.next();
                it.remove();
                // 券B：键上限溢出静默淘汰最旧键——变更键集表达不了「哪个键被逐出」，面板回退全量对齐。
                countFullRebuildPending.set(true);
            }
        }
    }

    /**
     * 新条目入队（最早在前，追加到队尾；暂停态丢弃；过滤关键字命中丢弃；去重窗口内相同 SQL 丢弃并计数；超过上限裁掉最旧）。
     *
     * <p>T-PerfBatch 分层通知：去重丢弃分支只走 {@link #onDedupeCountsChanged}（徽章即时，列表内容未变）；
     * 成功入队走 {@link #onEntryAccepted}（即时）+ 内容通知节流（{@link #notifyContentChangedThrottled()}）。
     *
     * <p>T-Perf-06（自 dev 移植）：去重判定与入队合并进同一 synchronized(entries) 临界区——此前 isDuplicate
     * 在锁外无锁遍历 ArrayDeque，与 EDT 清空/删除（clear/removeEntry）并发修改会触发
     * ConcurrentModificationException，经 LogFilterProxyHandler（无 catch）上浮为 IDE 红色报错弹窗。
     */
    /** 普通入队：受「SQL 日志捕获」开关门控（{@code paused} 期间丢弃）。 */
    public boolean addEntry(LogSqlEntry entry) {
        return addEntryInternal(entry, true);
    }

    /**
     * 审计入队：<b>不受「SQL 日志捕获」开关约束</b>（D1 定案，2026-09-22 用户）。
     *
     * <p>MCP 执行类工具（当前唯一产出点 {@code sqlv_explain}）在真实数据源上跑过的语句属<b>审计留痕</b>，
     * 其可用性只由 MCP 工具自身的开关决定（设置页「启用 SQL Visualizer MCP 工具」＋工具级禁用清单，
     * 见 {@code McpToolGate}）——与「SQL 日志」页签的捕获按钮无关。故开关关着（含用户显式停止后的
     * {@code paused=true}）时审计条目<b>照样落列表</b>。
     *
     * <p>其余门与 {@link #addEntry(LogSqlEntry)} 完全一致：关键字过滤 / 去重窗口 / 上限淘汰——它们是列表展示偏好，不是开关。
     */
    public boolean addAuditEntry(LogSqlEntry entry) {
        return addEntryInternal(entry, false);
    }

    private boolean addEntryInternal(LogSqlEntry entry, boolean respectPaused) {
        if (respectPaused && paused) {
            return false;
        }
        if (isFiltered(entry.restoredSql())) {
            return false;
        }
        // T-SqlSuppress（O-69）：屏蔽规则命中 → 入队前丢弃（isFiltered 之后、去重/上限之前——
        // 「从未捕获过」：不进去重计数、不占捕获序号、不触发任何内容回调；命中计数走
        // onSuppressedHit 专用出口）。空集短路：无规则时零成本，不触 dedupeKey 惰性计算。
        // 【反向验证探针锚点】注释掉下面 if 块 = 摘除屏蔽闸门，SqlLogEntryBufferTest 屏蔽用例必红。
        // T-SqlSuppressUx 二段：精确键（默认口径规则）或结构键（手动开启结构匹配的规则）任一命中即拦；
        // 命中键回传 recordHit 归账到对应规则。空集短路下 structureKey 的惰性亦不被触达。
        // F20（T-PerfSweep9）：结构键独立门控——键集里无任何结构口径键（SqlSuppressRuleManager 的
        // 「(结构)」后缀约定）时跳过第二查找，避免「全部精确口径规则」形态每条捕获白付一次
        // structureKey lazy（O(n) 字面量参数化扫描 + StringBuilder 分配）。
        if (!suppressKeys.isEmpty()) {
            String hitKey = null;
            if (suppressKeys.contains(entry.suppressKey())) {
                hitKey = entry.suppressKey();
            } else if (hasStructureKeys && suppressKeys.contains(entry.structureKey())) {
                hitKey = entry.structureKey();
            }
            if (hitKey != null) {
                Consumer<String> hit = onSuppressedHit;
                if (hit != null) {
                    hit.accept(hitKey);
                }
                return false;
            }
        }
        boolean duplicate = false;
        boolean mergeChanged = false;
        LogSqlEntry mergeDiagExisting = null;
        LogSqlEntry mergeDiagMerged = null;
        // T-CrossChannelDedupe：跨通道兜底折叠的锁外诊断载荷——只放指纹前缀与通道，绝不含 SQL 原文/参数值
        boolean crossMerged = false;
        String crossDiagKey = null;
        LogSqlEntry crossDiagExisting = null;
        LogSqlEntry crossDiagMerged = null;
        boolean crossUnfolded = false;
        // 券F1：越窗兜底对账的锁外诊断载荷（同 crossMerged 口径——只放指纹与通道，绝不含 SQL 原文/参数值）
        boolean staleMerged = false;
        String staleDiagKey = null;
        LogSqlEntry staleDiagExisting = null;
        LogSqlEntry staleDiagMerged = null;
        List<LogSqlEntry> evictedEntries = new ArrayList<>();
        LogSqlEntry[] storedEntry = {null};
        synchronized (entries) {
            if (dedupeEnabled && canDedupe(entry) && isDuplicate(entry)) {
                duplicate = true;
                // 券B：重复丢弃走 recordDedupeDrop（⨉N 计数 +1），登记变更键供面板按键重读徽章计数；
                // 同桶融合（mergeChanged）不改 restoredSql → dedupeKey/structureKey 都不变，键登记足够。
                markCountKeyLocked(entry.dedupeKey());
                // 【反向验证探针锚点】T-AgentCaptureProvenance O-147 W1：注释掉下方 if 块 =
                // 摘除「同桶信息融合」——SqlLogEntryBufferTest 的「控制台先到_agent后到_融合升级」
                // 用例必红（保留条目缺源码/耗时/库标，跳转仍 MissingSource），恢复后转绿。
                LogSqlEntry existing = dedupeIndex.get(entry.dedupeKey());
                if (existing != null) {
                    LogSqlEntry merged = LogSqlModelsKt.mergeDuplicateEntry(existing, entry);
                    if (!merged.equals(existing)) {
                        replaceEntryLocked(existing, merged);
                        dedupeIndex.put(entry.dedupeKey(), merged);
                        // T-CrossChannelDedupe：跨通道索引持有的是**队内实例**——融合换实例后同步重指，
                        // 否则后续跨通道兜底会融合进一个已不在队内的陈旧副本（升级丢失）。
                        repointCrossChannelLocked(existing, merged);
                        mergeChanged = true;
                        mergeDiagExisting = existing;
                        mergeDiagMerged = merged;
                    }
                }
            } else {
                // T-CrossChannelDedupe（20261105）：dedupeKey 未命中时的**跨通道等价键兜底**。
                // 两侧还原器口径分叉（布尔/日期族/注释内 ?/参数个数守卫/全限定类型名）会让同一次执行产出
                // 两个不同 dedupeKey，同键分支永远折不上——本分支用「占位符版 rawSql + 参数值签名」再判一次。
                // 只在未命中时计算（命中即返回，热路径零额外成本）。
                // T-CrossChannelDedupe：跨通道兜底**不依赖** dedupeEnabled——它折叠的是「同一次执行
                // 的两份观测」，不是「重复执行」。绑在用户去重开关上会让「关掉 SQL 去重」直接退化成
                // 「同一次执行出现两条」（一条带耗时位置、一条全空），即 T-DedupeDecouple 的真机报障：
                // 状态栏「控制台已捕获 N / 增强已收 M」并存 + 列表里出现无字段副本。
                String crossKey = canDedupe(entry) ? crossChannelKey(entry) : null;
                LogSqlEntry crossHit = crossKey != null ? crossChannelIndex.get(crossKey) : null;
                boolean crossDivergent = crossHit != null && !crossHit.dedupeKey().equals(entry.dedupeKey());
                // 【反向验证探针锚点】T-CrossChannelDedupe：注释掉下面这个 if 分支 = 摘除跨通道兜底折叠，
                // CrossChannelDedupeTest 的布尔/日期族/注释内 ?/全限定类型名/先后到 用例必红（同一次执行留两行），
                // 恢复后转绿。
                //
                // T-DedupeDecouple：折叠条件**不再要求 crossDivergent**（dedupeKey 不同）。原判据假定
                // 「同键副本由上面的同键去重分支处理」，但那条分支受 dedupeEnabled 门控——用户关掉
                // 「SQL 去重」后，键相同的跨通道副本两边都不管，同一次执行照样两条。去重开启时同键分支
                // 先命中、根本走不到这里，故去掉该条件不改变既有路径。
                if (crossKey != null && crossHit != null && canFoldAcrossChannels(crossHit, entry)) {
                    // 兜底折叠：复用同键融合（补空不覆盖、通道归并 MERGED），原位替换不新增行。
                    // 不计 recordDedupeDrop——这不是「重复 SQL 被丢弃」，是同一次执行两份观测的合并。
                    crossMerged = true;
                    crossDiagKey = crossKey;
                    crossDiagExisting = crossHit;
                    LogSqlEntry merged = LogSqlModelsKt.mergeDuplicateEntry(crossHit, entry);
                    if (merged != crossHit) {
                        replaceEntryLocked(crossHit, merged);
                        crossChannelIndex.put(crossKey, merged);
                        // 同键索引若指向被替换实例，同步重指（键不变：融合不动 restoredSql）
                        if (dedupeIndex.get(merged.dedupeKey()) == crossHit) {
                            dedupeIndex.put(merged.dedupeKey(), merged);
                        }
                        crossDiagMerged = merged;
                    }
                } else {
                    // §4.3 哨兵：同 crossKey、异 dedupeKey、同在窗内，但折叠条件不满足（同通道已知标记）
                    // ——「还原器又分叉了且兜底也接不住」的长期证据行。只含指纹前缀与通道。
                    if (crossKey != null && crossHit != null && crossDivergent
                            && withinCrossChannelWindow(crossHit, entry)) {
                        crossUnfolded = true;
                        crossDiagKey = crossKey;
                        crossDiagExisting = crossHit;
                    }
                    // 【反向验证探针锚点】T-SqlListCatchup 券F1：注释掉下面 if 块 = 摘除越窗兜底对账，
                    // CrossChannelStaleReconcileTest 的「agent先到/控制台先到」两超窗用例必红
                    // （同一次执行留两行——真机第三轮压测缺陷形态），恢复后转绿。
                    // 去重开启态：同 crossKey 的既有实例仍在队（索引不再做时间清除）但已超 5s 折叠窗、
                    // 仍在去重窗内、异通道标记 → 按同一次执行的两份观测合并（先到者保留、补空不覆盖）。
                    // 接管沿截断的键不在索引 → 无候选 → P3 可见性不回破；去重关闭态不折（语义不动）。
                    if (dedupeEnabled && canDedupe(entry)) {
                        LogSqlEntry staleCandidate = findStaleSameSourceCandidateLocked(entry, crossHit);
                        if (staleCandidate != null) {
                            staleMerged = true;
                            staleDiagKey = crossKey;
                            staleDiagExisting = staleCandidate;
                            LogSqlEntry merged = LogSqlModelsKt.mergeDuplicateEntry(staleCandidate, entry);
                            if (merged != staleCandidate) {
                                replaceEntryLocked(staleCandidate, merged);
                                // 同键索引若指向被替换实例，同步重指（键不变：融合不动 restoredSql）
                                if (dedupeIndex.get(merged.dedupeKey()) == staleCandidate) {
                                    dedupeIndex.put(merged.dedupeKey(), merged);
                                }
                                // 跨通道索引重指到保留实例（候选与来件同 crossKey，键不变）
                                repointCrossChannelLocked(staleCandidate, merged);
                                staleDiagMerged = merged;
                            }
                        }
                    }
                    if (!staleMerged) {
                        // T-LogRowNumber：入队时分配捕获序号（去重丢弃/暂停/过滤不占号，列表不跳号）
                        LogSqlEntry added = addNumbered(entry);
                        storedEntry[0] = added;
                        entries.addLast(added);
                        if (dedupeEnabled && canDedupe(added)) {
                            // T-SqlLogPerf P6：索引 upsert（dedupeKey lazy 首触与旧版 isDuplicate 同价，
                            // 仅去重开启时支付）
                            dedupeIndex.put(added.dedupeKey(), added);
                            // F83：键级在队计数 +1（与索引同条件同锁维护）
                            Integer current = dedupeKeyPresence.get(added.dedupeKey());
                            dedupeKeyPresence.put(added.dedupeKey(), (current != null ? current : 0) + 1);
                            // 券B：在队计数 +1，登记变更键供面板按 presence 净差增量（同键入队与后续淘汰的
                            // 增减在 drain 间折叠为一次净变化，无悬挂余量）。
                            markCountKeyLocked(added.dedupeKey());
                        } else {
                            // 券B：去重关闭期间 presence 不维护 / 不可去重条目（ShardingSphere）不在
                            // presence 口径内——增量键集无法表达，面板回退全量重算（与全量重算的现状同价）。
                            countFullRebuildPending.set(true);
                        }
                        // T-DedupeDecouple：跨通道索引与去重开关**解耦**——去重关闭时它照样维护，
                        // 否则兜底查不到候选（真机：关掉「SQL 去重」后同一次执行又出现两条）。
                        // 券F1：不再做 5s 时间窗惰性清除——超窗键是越窗兜底的候选源（见 crossChannelIndex KDoc）。
                        if (canDedupe(added)) {
                            crossChannelIndex.put(crossChannelKey(added), added);
                        }
                        while (entries.size() > maxEntries) {
                            LogSqlEntry evicted = entries.removeFirst();
                            if (dedupeEnabled) {
                                String k = evicted.dedupeKey();
                                // F83：键级在队计数随淘汰同步 -1（canDedupe=false 条目从未入计）。先减后判：
                                // 淘汰者自身出队后剩余 >0 才算「队内仍有同键余条」。
                                if (canDedupe(evicted)) {
                                    Integer presence = dedupeKeyPresence.get(k);
                                    int remaining = (presence != null ? presence : 0) - 1;
                                    if (remaining > 0) dedupeKeyPresence.put(k, remaining);
                                    else dedupeKeyPresence.remove(k);
                                    // 券B：在队计数 -1，登记被淘汰键（面板等价性用例断言 drain 集合包含被淘汰键）。
                                    markCountKeyLocked(k);
                                    // 券E：键被扫出键域（presence 摘键）**不再回退全量**——淘汰是确定性核销
                                    // （presence 减一、归零摘键，被淘汰键已在变更键集内），面板按净差即可正确
                                    // 扣账（structureKey 从键域内期缓存取）；此前「扫键是历史缺陷高发点 → 回退
                                    // 全量锚定」的保守口径在 buffer 恒满 + 异键风暴下每拍触发整表重算
                                    // （券E 账单与真机 102~232ms 单拍的共同背景），等价性由恒满淘汰序列
                                    // 随机对账用例钉住（SqlLogCountAccumulatorEquivalenceTest）。
                                } else {
                                    // 券B：不可去重条目淘汰不在 presence 口径内——面板回退全量。
                                    countFullRebuildPending.set(true);
                                }
                                // T-SqlLogPerf P6：索引存的是「最近」实例，仅当被淘汰者恰为该键最新时才需要重算；
                                // F83：队内仍有同键余条（计数 >0）才付 O(maxEntries) 反扫重指次新（超窗重复入队
                                // 形态，低频）——唯一键稳态计数归零，直接摘索引跳过必空的反扫（每接受一条省一次锁内全队扫）。
                                if (dedupeIndex.get(k) == evicted) {
                                    Integer presence = dedupeKeyPresence.get(k);
                                    if (presence != null && presence > 0) {
                                        evictionRescanCount.incrementAndGet(); // F83 测试缝：全队反扫执行计数
                                        LogSqlEntry stillThere = lastEntryWithKey(k, SqlLogEntryBuffer::canDedupe);
                                        if (stillThere != null) {
                                            dedupeIndex.put(k, stillThere);
                                        } else {
                                            dedupeIndex.remove(k);
                                        }
                                    } else {
                                        dedupeIndex.remove(k);
                                    }
                                }
                            } else {
                                // 券B：去重关闭期间 presence 不维护——面板回退全量。
                                countFullRebuildPending.set(true);
                            }
                            // T-DedupeDecouple：跨通道索引回收与去重开关解耦（去重关闭时索引仍在维护）。
                            // 索引存该键**最后入队**实例、淘汰取**最旧**——命中被淘汰者即说明队内已无同键余条。
                            if (canDedupe(evicted)) {
                                String ck = crossChannelKey(evicted);
                                if (crossChannelIndex.get(ck) == evicted) {
                                    crossChannelIndex.remove(ck);
                                }
                            }
                            evictedEntries.add(evicted);
                            evictedSinceDrain.incrementAndGet(); // T-Perf-03：头部淘汰计数（面板增量刷新）
                        }
                    }
                }
            }
        }
        // T-SqlListCatchup 券F1：越窗兜底对账——同一次执行的两份观测合并，**不是新捕获**：返回 false
        // （不触发 onEntryAccepted）、不计 ⨉N 徽章（不调 recordDedupeDrop，与窗内跨通道折叠同口径）；
        // 元数据升级落历史（onEntryMerged）并重绘列表（内容通道）。
        if (staleMerged) {
            if (staleDiagExisting != null && staleDiagMerged != null && staleDiagKey != null) {
                notifyContentChangedThrottled();
                EntryMergeListener mergeListener = onEntryMerged;
                if (mergeListener != null) {
                    mergeListener.onMerged(staleDiagExisting, staleDiagMerged);
                }
                final LogSqlEntry diagExisting = staleDiagExisting;
                final LogSqlEntry diagMerged = staleDiagMerged;
                final String diagKey = staleDiagKey;
                logCrossChannelThrottled(() -> LogSqlModelsKt.crossChannelStaleReconcileDiagText(
                        diagKey, diagExisting, entry, diagMerged));
            }
            return false;
        }
        if (duplicate) {
            recordDedupeDrop(entry.dedupeKey());
            Runnable badge = onDedupeCountsChanged;
            if (badge != null) {
                badge.run(); // 徽章计数变了 → 即时刷新（不走内容节流）
            }
            // 跨通道融合改变了保留条目的可见元数据 → 走内容通道重绘；仍非「新 SQL」。
            if (mergeChanged) {
                notifyContentChangedThrottled();
                // O-147：合并是元数据升级不是新捕获——历史落盘链（SqlLogAutoSaver）据此原地更新
                EntryMergeListener mergeListener = onEntryMerged;
                if (mergeListener != null && mergeDiagExisting != null && mergeDiagMerged != null) {
                    mergeListener.onMerged(mergeDiagExisting, mergeDiagMerged);
                }
                if (mergeDiagExisting != null && mergeDiagMerged != null) {
                    final LogSqlEntry diagExisting = mergeDiagExisting;
                    final LogSqlEntry diagMerged = mergeDiagMerged;
                    logCrossChannelThrottled(() -> "重复观测跨通道融合: 指纹=" + entry.dedupeKey().substring(0, Math.min(8, entry.dedupeKey().length()))
                            + " 通道=" + diagExisting.captureProvenance() + "+" + entry.captureProvenance()
                            + "→" + diagMerged.captureProvenance()
                            + " 升级=" + LogSqlModelsKt.mergeUpgradedAspects(diagExisting, diagMerged));
                }
            }
            return false;
        }
        // T-CrossChannelDedupe：兜底折叠——同一次执行的两份观测合并，**不是新捕获**：返回 false
        // （不触发 onEntryAccepted）、不计 ⨉N 徽章（不调 recordDedupeDrop）；但元数据升级要落历史
        // （onEntryMerged）并重绘列表（内容通道）。
        if (crossMerged) {
            if (crossDiagExisting != null && crossDiagMerged != null && crossDiagKey != null) {
                notifyContentChangedThrottled();
                EntryMergeListener mergeListener = onEntryMerged;
                if (mergeListener != null) {
                    mergeListener.onMerged(crossDiagExisting, crossDiagMerged);
                }
                final LogSqlEntry diagExisting = crossDiagExisting;
                final LogSqlEntry diagMerged = crossDiagMerged;
                final String diagKey = crossDiagKey;
                logCrossChannelThrottled(() -> LogSqlModelsKt.crossChannelMergeDiagText(
                        diagKey, diagExisting, entry, diagMerged));
            }
            return false;
        }
        // T-CrossChannelDedupe §4.3 哨兵：同 crossKey + 异 dedupeKey + 同窗内却未折叠（同通道已知标记）
        // ——「还原器又分叉了，且兜底也接不住」的长期证据行；条目照常入队。
        if (crossUnfolded) {
            if (crossDiagExisting != null && crossDiagKey != null) {
                final LogSqlEntry diagExisting = crossDiagExisting;
                final String diagKey = crossDiagKey;
                logCrossChannelThrottled(() -> LogSqlModelsKt.crossChannelUnfoldedDiagText(
                        diagKey, diagExisting, entry));
            }
        }
        // 缺源码条目的通道归因诊断（限频）——「增强已开启却无法跳转」现场的直接证据行。
        // T-CaptureCatchAll 券02：取「有效封停态」而非裸 paused——活性回落解封期间桥确实在产出，
        // 打「桥封停=true」会把排障引向错误方向。
        if (entry.sourceFile() == null && entry.sourceCandidates().isEmpty()) {
            logAttributionThrottled(() -> LogSqlModelsKt.provenanceDiagText(entry, SqlCaptureGate.bridgeSuppressed()));
        }
        // T-Perf-12（自 dev 移植）：onEvicted 回调移出 synchronized(entries)——回调链（自动保存磁盘写）
        // 在锁内同步执行会阻塞 EDT snapshot() 抢同一把锁（控制台输出热路径互相卡）
        // T-SqlHistoryFull：被淘汰条目先经 onEntryStored 写过（入队成功即写），此处 onEvicted 不再重复消费
        for (LogSqlEntry evicted : evictedEntries) {
            if (onEvicted != null) {
                onEvicted.accept(evicted);
            }
        }
        // T-SqlHistoryFull：成功入队即写历史——「全量」出口（含随后被淘汰的条目，它们也先进了列表）
        if (storedEntry[0] != null && onEntryStored != null) {
            onEntryStored.accept(storedEntry[0]);
        }
        Runnable accepted = onEntryAccepted;
        if (accepted != null) {
            accepted.run(); // 「有新 SQL」语义即时出口（自动展开等），先于内容节流
        }
        notifyContentChangedThrottled();
        return true;
    }

    /**
     * 手动触发去重裁剪（用户勾选「移除重复的sql」时）：扫描现有 entries，
     * 相同 SQL（按 {@link LogSqlEntry#dedupeKey()} 空白规范化指纹）保留最早一条、丢弃后续；丢弃数累加到 dedupeCounts
     * （与运行时去重共享计数——徽章反映「本次去重开启以来的总丢弃量」，含开启前已存在的重复）。
     *
     * <p>T-LogRowNumber 追加（2026-09-10）：手动全量裁剪<b>不看时间窗口</b>——去重窗口
     * （dedupeWindowMs，默认 60s）是运行时的「相同 SQL 短时间内重复打印」语义，而「点击移除重复」
     * 是用户显式的一次性全量去重，跨窗口的重复（用户不勾选去重运行很久后累积的同文本 SQL）也必须裁掉，
     * 否则点击后重复项仍在列表（用户反馈「显示不正确」）。仅按键判重，不比较时间戳。
     *
     * @return 本次裁剪移除的条目数（面板日志/测试断言）。
     */
    public int dedupeExisting() {
        if (!dedupeEnabled) {
            return 0;
        }
        int[] removed = {0};
        synchronized (entries) {
            if (entries.size() < 2) {
                return 0;
            }
            List<LogSqlEntry> keep = new ArrayList<>();
            for (LogSqlEntry entry : entries) {
                boolean duplicate = false;
                // T-LogDedupeMpMark：与 isDuplicate 同键（空白规范化指纹），勾选时对既有跨格式副本同样生效；
                // T-LogRowNumber 追加：手动全量裁剪不比较时间窗口（见方法 KDoc），纯按指纹判重。
                for (LogSqlEntry k : keep) {
                    if (k.dedupeKey().equals(entry.dedupeKey())) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) {
                    recordDedupeDrop(entry.dedupeKey());
                    removed[0]++;
                } else {
                    keep.add(entry);
                }
            }
            if (removed[0] > 0) {
                entries.clear();
                entries.addAll(keep);
                if (dedupeEnabled) {
                    rebuildDedupeIndexLocked(); // T-SqlLogPerf P6：裁剪后索引随队重建
                }
            }
        }
        if (removed[0] > 0) {
            notifyChanged(); // 用户操作：内容即时刷新（不节流）
            Runnable badge = onDedupeCountsChanged;
            if (badge != null) {
                badge.run();
            }
        }
        return removed[0];
    }

    /**
     * 关闭去重：清空累计计数（语义：「关闭」= 清零；再次开启时从零起算）。
     * 若原本未开启或计数本就为空，跳过通知（避免无意义 EDT 调度）。
     * T-PerfBatch：只动徽章数据（列表内容不变）——走 {@link #onDedupeCountsChanged}，不走内容通道。
     */
    public void disableDedupe() {
        if (!dedupeEnabled) {
            return;
        }
        dedupeEnabled = false;
        boolean hadCounts;
        synchronized (dedupeCounts) {
            hadCounts = !dedupeCounts.isEmpty();
        }
        resetDedupeCounts();
        if (hadCounts) {
            Runnable badge = onDedupeCountsChanged;
            if (badge != null) {
                badge.run();
            }
        }
    }

    // ---------- T-M55-02：执行报错反向绑定（原地更新最近条目） ----------

    /** 错误反向绑定结果（丢弃原因显式化——桥层据此 LOG.debug，不阻塞主链）。 */
    public enum ErrorBindResult {
        /** 已绑定（含同文本幂等重绑）。 */
        BOUND,
        /** 缓冲为空（条目已被清空/尚未捕获）→ 错误丢弃。 */
        DROPPED_EMPTY,
        /** 最近条目超出绑定时间窗（{@link #getErrorBindWindowMs()}）→ 错误丢弃。 */
        DROPPED_WINDOW_EXPIRED,
        /** 错误文本为空 → 无事可绑。 */
        DROPPED_BLANK,
    }

    /**
     * 错误绑定时间窗（毫秒）：异常打印在 Preparing/Parameters 之后，仅反向绑定该窗口内
     * 入列的最近条目——超窗视为「与缓冲内条目无关的错误」（启动期残留/应用级报错），显式丢弃。
     */
    private volatile long errorBindWindowMs = DEFAULT_ERROR_BIND_WINDOW_MS;

    public long getErrorBindWindowMs() {
        return errorBindWindowMs;
    }

    public void setErrorBindWindowMs(long value) {
        this.errorBindWindowMs = value;
    }

    /**
     * T-M55-02：错误文本反向绑定<b>时间窗内同控制台最近条目</b>（原地更新 errorText）。
     *
     * <ul>
     * <li>原地更新 = 队尾条目 copy(errorText=...) 替换——{@link LogSqlEntry#dedupeKey()} 不含 errorText
     * （SQL 指纹稳定），去重计数与 ⨉N 徽章语义不受影响；</li>
     * <li>绑定成功走 {@link #notifyChanged()}（面板差分刷新；尾位替换在 VisibleDiff 下为全量重建，
     * 由管理器置脏+幂等 flush 合并频率，错误块逐行多次绑定最终只刷一次 EDT）；</li>
     * <li>条目可能已被去重丢弃/淘汰/清空——此处表现为「最近条目缺位或超窗」，显式返回丢弃原因，
     * 绝不阻塞主链（错误块解析失败不影响 SQL 捕获）。</li>
     * </ul>
     */
    public ErrorBindResult bindErrorToRecent(String errorText) {
        if (errorText.isBlank()) return ErrorBindResult.DROPPED_BLANK;
        boolean[] replaced = {false};
        ErrorBindResult[] result = {null};
        synchronized (entries) {
            LogSqlEntry recent = entries.isEmpty() ? null : entries.getLast();
            ErrorBindResult r;
            if (recent == null) {
                r = ErrorBindResult.DROPPED_EMPTY;
            } else if (System.currentTimeMillis() - recent.timestamp() > errorBindWindowMs) {
                r = ErrorBindResult.DROPPED_WINDOW_EXPIRED;
            } else if (errorText.equals(recent.errorText())) {
                // 幂等：同一错误块逐行追加会重复绑定，文本相同视为已绑（不替换不刷新）
                r = ErrorBindResult.BOUND;
            } else {
                entries.removeLast();
                LogSqlEntry updated = recent.withErrorText(errorText);
                entries.addLast(updated);
                // T-CrossChannelDedupe：两个索引持有的是**队内实例**——原位替换后必须同步重指，
                // 否则后续同键去重/跨通道兜底会融合进已不在队内的旧副本（升级在列表上不可见）。
                // 键不变（errorText 不入 dedupeKey / crossKey），只换值。
                // T-DedupeDecouple：去重索引受 dedupeEnabled 约束，跨通道索引不受。
                if (dedupeEnabled && dedupeIndex.get(updated.dedupeKey()) == recent) {
                    dedupeIndex.put(updated.dedupeKey(), updated);
                }
                if (canDedupe(updated)) {
                    String ck = crossChannelKey(updated);
                    if (crossChannelIndex.get(ck) == recent) {
                        crossChannelIndex.put(ck, updated);
                    }
                }
                replaced[0] = true;
                r = ErrorBindResult.BOUND;
            }
            result[0] = r;
        }
        if (replaced[0]) notifyChanged();
        return result[0];
    }

    private boolean isFiltered(String sql) {
        if (filterKeywords.isEmpty()) {
            return false;
        }
        String lower = sql.toLowerCase();
        for (String k : filterKeywords) {
            if (lower.contains(k.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 去重判定（T-Perf-06：必须在 synchronized(entries) 内调用——无锁遍历 ArrayDeque 会与清空/删除并发 CME）。
     * T-LogDedupeMpMark：按 LogSqlEntry.dedupeKey（空白规范化指纹）比较——JdbcTemplate 双路日志副本仅差
     * 首尾空白，原文精确相等永不命中；预计算指纹串比较也快于全文 ==。
     * T-SqlLogPerf P6：O(n) 全表扫改 dedupeIndex O(1) 查表——「存在同键条目使 now-timestamp &lt; 窗口」
     * ⟺ 该键最近时间戳落窗口内（最大时间戳是窗口判定的唯一有效候选，逐字等价）。
     */
    private boolean isDuplicate(LogSqlEntry entry) {
        if (!canDedupe(entry)) return false;
        LogSqlEntry latest = dedupeIndex.get(entry.dedupeKey());
        if (latest == null) {
            return false;
        }
        return entry.timestamp() - latest.timestamp() < dedupeWindowMs;
    }

    /** 最早在前的快照（正序）。 */
    public List<LogSqlEntry> snapshot() {
        synchronized (entries) {
            return new ArrayList<>(entries);
        }
    }

    /**
     * 当前条目数（T-UxOnboard T-UX-03 增设）：入队热路径（onEntryAccepted 回调）需要 O(1) 判
     * 「本条是否会话首条」——snapshot() 全量拷贝在流式灌入下是 O(n²) 累积成本，不得用于热路径。
     */
    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    /**
     * T-Perf-03（自 dev 移植）：取走自上次调用以来的头部淘汰条数并归零。
     * 单消费方约定（面板 EDT 刷新）：与 snapshot() 搭配，用于增量刷新的头部对齐；
     * 其他调用方调用会偷走面板的对齐信息（回退全量重建，正确性不受影响）。
     */
    public int drainEvictedCount() {
        return evictedSinceDrain.getAndSet(0);
    }

    // ---------- T-SqlListCatchup 券B：计数快照增量出口 ----------

    /**
     * T-SqlListCatchup 券B：未消费的内容通知计数——内容通知节流窗（数量 20 + 时间 200ms 双阈值）内
     * 已入队但尚未触发内容监听器的条目数（读 {@link #pendingContentNotify}，<b>只读不消费</b>）。
     * 追赶期「落后 N 条」量化读数的缓冲段数据源；券 D 追赶读数消费方
     * （{@code CaptureStatusBar} 的 catchUpBehind 据此取值）。
     */
    public int pendingNotifyCount() {
        return pendingContentNotify.get();
    }

    /**
     * T-SqlListCatchup 券B：取走自上次调用以来累积的计数变更键（dedupeKey）并清空（消费即清）。
     * 节流窗内同键反复变化只记键不记次数（Set 语义），有界性见 {@link #changedCountKeys} KDoc。
     * 单消费方约定（面板 EDT 刷新）：与 {@link #drainEvictedCount()} 同款——其他调用方调用会偷走面板的
     * 增量信息（面板回退全量重算，正确性不受影响）。
     */
    public Set<String> drainChangedKeys() {
        synchronized (changedCountKeys) {
            if (changedCountKeys.isEmpty()) {
                return Set.of();
            }
            Set<String> drained = new java.util.HashSet<>(changedCountKeys);
            changedCountKeys.clear();
            return drained;
        }
    }

    /**
     * T-SqlListCatchup 券B：取走计数全量重建标记并复位。置位点与各点为什么必须全量逐条见
     * {@link #countFullRebuildPending} KDoc。单消费方约定同 {@link #drainChangedKeys()}。
     */
    public boolean drainCountRebuildFlag() {
        return countFullRebuildPending.getAndSet(false);
    }

    /** T-SqlListCatchup 券B：按键读 ⨉N 去重丢弃计数（{@link #dedupeCount(String)} 的按键版，供面板按变更键重读；无记录返回 0）。 */
    public int dedupeCountForKey(String key) {
        Integer count;
        synchronized (dedupeCounts) {
            count = dedupeCounts.get(key);
        }
        return count != null ? count : 0;
    }

    /**
     * T-SqlListCatchup 券B：按键读在队计数（{@link #dedupeKeyPresence} 的消费出口）。
     * 注意：仅「去重开启 + 可去重条目」有准确读数——去重关闭期间 presence 冻结不维护，读数无效
     * （该状态下增量口径整体失效，buffer 置全量重建标记，本出口不会被面板在增量路径消费）。
     */
    int dedupePresenceForKey(String key) {
        synchronized (entries) {
            Integer count = dedupeKeyPresence.get(key);
            return count != null ? count : 0;
        }
    }

    /**
     * T-SqlListCatchup 券B：按键取该键保留实例的 structureKey（走 {@link #dedupeIndex} O(1) 查表；
     * structureKey 惰性缓存首算一次后直达，仅增量路径的新键支付一次指纹计算）。键不在索引返回 null。
     */
    String structureKeyForKey(String key) {
        synchronized (entries) {
            LogSqlEntry e = dedupeIndex.get(key);
            return e != null ? e.structureKey() : null;
        }
    }

    /**
     * T-SqlListCatchup 券B：登记计数变更键。调用方须已持 {@code synchronized(entries)}——与 presence
     * 维护同锁登记，保证 drain 读到的键在「去重开启 + 可去重」增量口径内有效。
     */
    private void markCountKeyLocked(String key) {
        synchronized (changedCountKeys) {
            changedCountKeys.add(key);
        }
    }

    /**
     * 清空全部（去重计数一并归零——徽章与列表内容同生命周期；
     * T-LogRowNumber：捕获序号一并归零——清空后下一条从 #1 起计数）。
     */
    public void clear() {
        synchronized (entries) {
            entries.clear();
            dedupeIndex.clear(); // T-SqlLogPerf P6：索引随队清空
            dedupeKeyPresence.clear(); // F83：键级在队计数随队清空
            crossChannelIndex.clear(); // T-CrossChannelDedupe：跨通道索引同生命周期清空
            // 券B：清空后悬挂的变更键作废（面板全量重建不消费键集），集合一并清空保持有界口径。
            synchronized (changedCountKeys) {
                changedCountKeys.clear();
            }
        }
        // 券B：clear 是兜底全量重建点——累积 map 必须与 buffer 双双清零对齐。
        countFullRebuildPending.set(true);
        resetDedupeCounts();
        sequenceCounter.set(0);
        pendingContentNotify.set(0); // 面板即将全量重建，悬挂的节流余量作废
        notifyChanged(); // 用户操作：内容即时刷新（不节流）
        Runnable badge = onDedupeCountsChanged;
        if (badge != null) {
            badge.run(); // 计数一并归零（T-PerfBatch 徽章通道）
        }
    }

    /** 去重丢弃计数归零（清空列表、面板重新开启去重时调用）。 */
    public void resetDedupeCounts() {
        synchronized (dedupeCounts) {
            dedupeCounts.clear();
        }
        // 券B：丢弃计数整体清零——变更键集表达不了「全部清零」，面板 ⨉N 累积 map 回退全量对齐。
        countFullRebuildPending.set(true);
    }

    /**
     * T-LogRowNumber：捕获序号归零（项目启动 processStarted 回调调用）。
     * 不清列表——旧条目保留原行号，仅新一轮启动后的 SQL 从 #1 重新计数。
     */
    public void resetEntrySequence() {
        sequenceCounter.set(0);
    }

    /** 指定 SQL 的去重丢弃次数（参数为还原 SQL 原文，内部按规范化+指纹查，与 LogSqlEntry.dedupeKey 同键；无丢弃记录返回 0）。 */
    public int dedupeCount(String sql) {
        Integer count;
        synchronized (dedupeCounts) {
            count = dedupeCounts.get(LogSqlModelsKt.sqlFingerprint(LogSqlModelsKt.normalizeSqlWhitespace(sql)));
        }
        return count != null ? count : 0;
    }

    /** 去重丢弃计数快照（键 = sqlFingerprint 指纹，值 = 丢弃次数；供面板渲染徽章）。 */
    public Map<String, Integer> dedupeCountsSnapshot() {
        synchronized (dedupeCounts) {
            return new HashMap<>(dedupeCounts);
        }
    }

    /** T-LogRowNumber：入队时以 entry 为原型分配捕获序号（copy 出带号实例入队）。 */
    private LogSqlEntry addNumbered(LogSqlEntry entry) {
        return entry.withSequenceNumber(sequenceCounter.incrementAndGet());
    }

    /**
     * 删除单条（面板右键菜单）。
     *
     * <p>T-LogRowNumber：buffer 入库的是带行号 copy（{@link #addNumbered}），而面板持有的引用来自
     * 列表模型（即入库实例本身）——两者同一，按 <b>同一性</b> 删除即可；不能用 record equals：
     * 序号字段参与 equals，外部构造/改造过的等值条目不应误删列表中的真身。
     */
    public void removeEntry(LogSqlEntry entry) {
        synchronized (entries) {
            boolean removed = entries.removeIf(it -> it == entry);
            // T-SqlLogPerf P6：单条删除仅重算该键（用户右键低频，O(n) 一次可接受）
            if (removed && dedupeEnabled && canDedupe(entry)) {
                String k = entry.dedupeKey();
                // F83：键级在队计数 -1（回扫本身用户右键低频保留 O(n) 一次，不属本券热路径）
                Integer presence = dedupeKeyPresence.get(k);
                int remaining = (presence != null ? presence : 0) - 1;
                if (remaining > 0) dedupeKeyPresence.put(k, remaining);
                else dedupeKeyPresence.remove(k);
                // 券B：登记变更键供面板按 presence 净差增量。
                // 券E：键被扫出键域不再回退全量——与淘汰路径同一机制（确定性核销：presence 减一、
                // 归零摘键，被淘汰键随变更键集出域），见 addEntry 淘汰循环内的券E注释。
                markCountKeyLocked(k);
                // T-SqlLogPerf P6：单条删除仅重算该键——反扫队内余条重指或摘键
                LogSqlEntry stillThere = lastEntryWithKey(k);
                if (stillThere != null) {
                    dedupeIndex.put(k, stillThere);
                } else {
                    dedupeIndex.remove(k);
                }
            } else if (removed) {
                // 券B：去重关闭期间 presence 不维护——面板回退全量。
                countFullRebuildPending.set(true);
            }
            // T-DedupeDecouple：跨通道索引重算与去重开关解耦（去重关闭时索引仍在维护）。
            // 删除可能拿走该键**最新入队**实例（与淘汰不同），故必须反扫队内余条而非直接摘键。
            if (removed && canDedupe(entry)) {
                String ck = crossChannelKey(entry);
                if (crossChannelIndex.get(ck) == entry) {
                    final String crossKey = ck;
                    LogSqlEntry crossStillThere = null;
                    for (java.util.Iterator<LogSqlEntry> cit = entries.descendingIterator(); cit.hasNext(); ) {
                        LogSqlEntry cand = cit.next();
                        if (canDedupe(cand) && crossChannelKey(cand).equals(crossKey)) {
                            crossStillThere = cand;
                            break;
                        }
                    }
                    if (crossStillThere != null) {
                        crossChannelIndex.put(crossKey, crossStillThere);
                    } else {
                        crossChannelIndex.remove(crossKey);
                    }
                }
            }
        }
        notifyChanged();
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void notifyChanged() {
        for (Runnable it : listeners) {
            it.run();
        }
    }

    /**
     * T-PerfBatch：列表内容通知节流——「数量 {@link #CONTENT_NOTIFY_BATCH} 条 + 时间
     * {@link #CONTENT_NOTIFY_INTERVAL_NANOS}」双阈值（原子量，无 Timer）：时间窗内快速灌入时
     * 首条即时通知、后续累计到阈值才再通知；未达阈值条目由 {@link #onContentNotifySuppressed}
     * 兜底给管理器的幂等 flush（flush 读当下快照，不丢条目）。低流量（两条间隔超窗）下
     * 每条即时，行为与节流前等价。
     */
    private void notifyContentChangedThrottled() {
        int pending = pendingContentNotify.incrementAndGet();
        long now = nowNanos();
        if (pending >= CONTENT_NOTIFY_BATCH
                || now - lastContentNotifyNanos.get() >= CONTENT_NOTIFY_INTERVAL_NANOS) {
            pendingContentNotify.set(0);
            lastContentNotifyNanos.set(now);
            notifyChanged();
        } else {
            Runnable suppressed = onContentNotifySuppressed;
            if (suppressed != null) {
                suppressed.run();
            }
        }
    }

    // ---------- T-SqlListCatchup 券B2：悬挂节流余量的过期冲洗 ----------

    /**
     * 券B2 测试缝：注入纳秒时钟（null = 生产 {@code System.nanoTime}）。悬挂态判定与节流时间戳
     * 统一走 {@link #nowNanos()}——时间注入缝照券 B 既有口径（别赌墙钟，用例注入固定时钟）。
     */
    private volatile Long nowNanosForTest = null;

    private long nowNanos() {
        Long injected = nowNanosForTest;
        return injected != null ? injected : System.nanoTime();
    }

    /**
     * T-SqlListCatchup 券B2：悬挂态判定——pending&gt;0 且距上次内容通知已超
     * {@link #CONTENT_NOTIFY_INTERVAL_NANOS}。这是「按既有双阈值规则早该通知、却没有后续条目再触发」
     * 的悬挂态：风暴末尾末批变更不足 {@link #CONTENT_NOTIFY_BATCH} 且落在上次通知 200ms 窗内，之后
     * 无新条目则再无任何触发源（pendingContentNotify 只在通知触发或 clear 归零）——这几条变更
     * 一直不落 UI，追赶徽章滞留「落后 N 条」直到下一条 SQL 或 clear，风暴结束后的静默期正好
     * 命中该形态。供面板 2s 诊断拍探询，命中即 {@link #flushPendingNotify()}。
     */
    public boolean hasStalePendingNotify() {
        if (pendingContentNotify.get() <= 0) return false;
        return nowNanos() - lastContentNotifyNanos.get() >= CONTENT_NOTIFY_INTERVAL_NANOS;
    }

    /**
     * T-SqlListCatchup 券B2：过期冲洗——pending&gt;0 时补上「按双阈值规则本应发生却无人触发」的
     * 那次内容通知：归零 + 刷新时间戳（冲洗视同一次真实通知，此后新条目照常走既有双阈值路径，
     * 窗内抑制、超窗即时）+ 触发 listeners（管理器侧 notifyUi 合拍链幂等，refreshList 读当下
     * 快照，不丢条目）。pending=0 时 no-op（不触发 listeners）。消费即清语义对齐 {@link #drainChangedKeys()}。
     *
     * <p>线程安全：pendingContentNotify 为原子量；与并发生产者的竞争最坏是「冲洗与阈值触发各发
     * 一次幂等通知」（两者都不丢变更），不会吞掉对方的归零——本该由阈值触发的通知被冲洗替代时，
     * 生产者的 pending 归零后其变更已被冲洗的那次通知覆盖（refreshList 读触发时刻之后的快照）。</p>
     */
    public void flushPendingNotify() {
        if (pendingContentNotify.get() <= 0) return;
        pendingContentNotify.set(0);
        lastContentNotifyNanos.set(nowNanos());
        notifyChanged();
    }
}
