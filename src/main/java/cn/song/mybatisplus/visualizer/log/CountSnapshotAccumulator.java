package cn.song.mybatisplus.visualizer.log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T-SqlListCatchup 券B：面板计数快照的<b>增量累积器</b>（纯逻辑，无平台依赖，可纯 JUnit 做口径等价性验证）。
 *
 * <p>维护两张累积 map——⨉N 去重丢弃徽章计数（键 = dedupeKey）与「×N 同结构」徽章计数（键 = structureKey），
 * 代替旧实现「每拍 dedupeCountsSnapshot() + structureKeyCounts(snapshot) 两份全量 O(N) 重算」：
 * 全量只在 {@link #apply} 收到 {@code fullRebuild}（buffer 侧兜底事件置位，见
 * {@code SqlLogEntryBuffer.drainCountRebuildFlag()}）或本累积器尚无基线（首拍）时执行；其余拍按 drain 到的
 * 变更键（{@code SqlLogEntryBuffer.drainChangedKeys()}）做<b>状态对账式</b>增量——每个变更键用 buffer 的当下
 * 在队计数（presence）与镜像的上次已应用值求净差，节流窗内同键的多次增减折叠为一次净变化、
 * 无悬挂余量；「structureKey 未变的条目计数沿用」即由此成立（未 drain 到的键不动对应条目）。</p>
 *
 * <p>等价性口径（本类正确性的命门，随机操作序列测试钉住）：
 * <ul>
 * <li>{@code currentDedupeCounts} ≡ buffer.dedupeCountsSnapshot()——buffer 侧丢弃计数的全部变更源
 *   （recordDedupeDrop / 整体清零 / 键上限溢出）要么登记变更键、要么置全量重建标记；</li>
 * <li>{@code currentTemplateCounts} ≡ structureKeyCounts(snapshot)——presence 只覆盖「去重开启 + 可去重」
 *   条目，口径外变更（开关翻转 / 接管沿重建 / clear / ShardingSphere 条目）一律由 buffer 置
 *   全量重建标记，增量路径只消费口径内键。淘汰扫出键域（presence 归零摘键）<b>不在此列</b>——
 *   券E 起按变更键净差增量核销（确定性减账，被淘汰键随变更键集出域，见
 *   SqlLogEntryBuffer.countFullRebuildPending KDoc）。</li>
 * </ul></p>
 *
 * <p>线程模型：面板 EDT 单线程消费（与 refreshList 同线程），内部状态无并发面。</p>
 *
 * <p>k2j 翻译注：Kotlin 侧为 SqlLogPanel.kt 文件级顶层类，构造参数是四个函数类型——Java 侧落
 * kotlin.jvm.functions.Function0/Function1（Kotlin 测试以 lambda 直构，禁 Java SAM 接口）；
 * {@code currentDedupeCounts}/{@code currentTemplateCounts} 属性落 getter 形态。</p>
 */
final class CountSnapshotAccumulator {

    /** buffer 丢弃计数快照（全量重建基线）。 */
    private final kotlin.jvm.functions.Function0<Map<String, Integer>> dropCountsSnapshot;
    /** 按键读 ⨉N 丢弃计数（增量重读）。 */
    private final kotlin.jvm.functions.Function1<String, Integer> dropCountForKey;
    /** 按键读在队计数（buffer dedupeKeyPresence 消费出口）。 */
    private final kotlin.jvm.functions.Function1<String, Integer> presenceForKey;
    /** 按键取保留实例 structureKey（buffer dedupeIndex O(1)；键不在索引时为 null）。 */
    private final kotlin.jvm.functions.Function1<String, String> structureKeyForKey;

    private Map<String, Integer> dedupeCounts = null;
    private Map<String, Integer> templateCounts = null;

    /** 上次已应用的在队计数镜像（增量净差的基准；键随淘汰出域，有界性随现存键域封顶）。 */
    private final Map<String, Integer> presenceMirror = new HashMap<>();

    /** dedupeKey → structureKey（structureKey 惰性首算昂贵，键在队期间只算一次）。 */
    private final Map<String, String> keyToStructure = new HashMap<>();

    CountSnapshotAccumulator(
            kotlin.jvm.functions.Function0<Map<String, Integer>> dropCountsSnapshot,
            kotlin.jvm.functions.Function1<String, Integer> dropCountForKey,
            kotlin.jvm.functions.Function1<String, Integer> presenceForKey,
            kotlin.jvm.functions.Function1<String, String> structureKeyForKey) {
        this.dropCountsSnapshot = dropCountsSnapshot;
        this.dropCountForKey = dropCountForKey;
        this.presenceForKey = presenceForKey;
        this.structureKeyForKey = structureKeyForKey;
    }

    /** 当前 ⨉N 徽章计数（无基线时为空 map）。 */
    Map<String, Integer> getCurrentDedupeCounts() {
        return dedupeCounts != null ? dedupeCounts : Map.of();
    }

    /** 当前「×N 同结构」徽章计数（无基线时为空 map）。 */
    Map<String, Integer> getCurrentTemplateCounts() {
        return templateCounts != null ? templateCounts : Map.of();
    }

    /**
     * 应用一拍：fullRebuild（buffer 兜底事件）或本累积器无基线时全量重建并重铸镜像；
     * 否则按 changedKeys 逐键对账。消费顺序约定：调用方<b>先 drain 变更键、后 drain 全量标记</b>
     * ——标记若落在两次 drain 之间则本拍即走全量，不产生一拍陈旧窗口。
     */
    void apply(List<LogSqlEntry> snapshot, boolean fullRebuild, Set<String> changedKeys) {
        if (fullRebuild || dedupeCounts == null || templateCounts == null) {
            // 全量重建：两张累积 map 与镜像一并按当下快照重新锚定（兜底事件点全部由 buffer 置位，
            // 每个事件点为什么必须全量见 SqlLogEntryBuffer.countFullRebuildPending KDoc 与各置位处注释）。
            dedupeCounts = new HashMap<>(dropCountsSnapshot.invoke());
            templateCounts = new HashMap<>(LogSqlModelsKt.structureKeyCounts(snapshot));
            presenceMirror.clear();
            keyToStructure.clear();
            for (LogSqlEntry e : snapshot) {
                keyToStructure.put(e.dedupeKey(), e.structureKey());
                presenceMirror.merge(e.dedupeKey(), 1, Integer::sum);
            }
            return;
        }
        if (changedKeys.isEmpty()) return;
        Map<String, Integer> dc = dedupeCounts;
        Map<String, Integer> tc = templateCounts;
        for (String key : changedKeys) {
            // ⨉N 徽章：buffer 丢弃计数按变更键重读即对齐（唯一变更源是 recordDedupeDrop，逐键 +1）。
            Integer dropObj = dropCountForKey.invoke(key);
            int dropCount = dropObj != null ? dropObj : 0;
            if (dropCount > 0) dc.put(key, dropCount);
            else dc.remove(key);
            // 「×N 同结构」：在队计数净差 → structureKey 计数增减（同 dedupeKey 的条目 structureKey
            // 恒同——两者都是 restoredSql 的确定性指纹，前者仅多一层字面量参数化）。
            Integer newPresenceObj = presenceForKey.invoke(key);
            int newPresence = newPresenceObj != null ? newPresenceObj : 0;
            Integer oldPresenceObj = presenceMirror.get(key);
            int oldPresence = oldPresenceObj != null ? oldPresenceObj : 0;
            if (newPresence != oldPresence) {
                String structure = keyToStructure.get(key);
                if (structure == null) {
                    String fetched = structureKeyForKey.invoke(key);
                    if (fetched != null) {
                        structure = fetched;
                        keyToStructure.put(key, fetched);
                    }
                }
                if (structure != null) {
                    Integer current = tc.get(structure);
                    int merged = (current != null ? current : 0) + (newPresence - oldPresence);
                    if (merged > 0) tc.put(structure, merged);
                    else tc.remove(structure);
                }
                if (newPresence > 0) {
                    presenceMirror.put(key, newPresence);
                } else {
                    // 键出队即出镜像域——镜像/映射有界性随现存键域封顶（presence 同口径）。
                    presenceMirror.remove(key);
                    keyToStructure.remove(key);
                }
            }
        }
    }
}
