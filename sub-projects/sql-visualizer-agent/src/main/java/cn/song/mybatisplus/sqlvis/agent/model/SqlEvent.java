package cn.song.mybatisplus.sqlvis.agent.model;

import java.util.List;

/**
 * SQL 事件（agent → IDE 单向流）：字段名与传输 JSON 键一一对应（Jsons.writeEvent）。
 * 全部 JDK 结构，无外部依赖——事件对象在采集线程构造，发送线程序列化。
 */
public final class SqlEvent {

    /** SQL 文本（PreparedStatement 为 prepare 时的 SQL，Statement 为 execute 参数，批为拼接）。 */
    public String sql;
    /** 采集时间戳（毫秒）：execute **开始**那一刻（T-AgentLogTime——面板回落值与日志行对齐）。 */
    public long timestamp;
    /** 精确耗时（nanoTime，§4.2 #5——毫秒级 currentTimeMillis 是两竞品共同坑）。 */
    public long durationNanos;
    /** 影响行数（DML/batch 语义；查询 null）。 */
    public Long updateCount;
    /** 执行报错（类名: 消息；成功 null）。 */
    public String errorText;
    /** 调用栈过滤后的源文件（相对路径，正斜杠）。 */
    public String sourceFile;
    /** 行号（0 未知）。 */
    public int sourceLine;
    /** 源码位置候选 [file, line, className, method]（T-AgentSourceJump 栈序；T-SourceJumpFunnel 增厚四列 ≤8 条；IDE 侧解析回退用）。 */
    public List<String[]> sourceCandidates;
    /** 调用栈快照「类#方法:行」压缩单串（T-SourceJumpFunnel P2-2，≤16 帧超长截断；人工往上追踪用）。 */
    public String stackDigest;
    /** 方法聚焦键 {SimpleClass}#{method}（T-M56-06）。 */
    public String methodKey;
    /** 请求聚合标签（MDC 兜底，T-M56-07）。 */
    public String requestTag;
    /** 参数快照 [index, valueText|null, type|null]（已过脱敏层）。 */
    public List<String[]> params;
    /** connect 时的 URL（T-AgentDialectLoop 起透传；面板据此识别目标库）。 */
    public String url;
    /** 执行线程名。 */
    public String threadName;
    /**
     * T-SqlListCatchup 券 F2b：**聚合计数**——本事件代表的同模板（同 sql）执行条数，恒 ≥1。
     *
     * <p>为什么需要：风暴期事件队列（{@code EventBus}，有界 2048）打满时 {@code offer()} 满即丢
     * （非阻塞红线，丢事件不丢线程），而同模板语句在风暴里大量重复——盲丢会把「执行了 N 次」
     * 这个事实整段丢掉。队列满时同模板事件在队内聚合为一条（保留最新、计数累加），腾出的容量
     * 让给新模板。正常路径恒为 1，零语义变化。
     *
     * <p>协议兼容：JSON 键 {@code c} 仅在 &gt;1 时写出（Jsons.writeEvent）——旧 IDE 读新事件忽略
     * 未知键、新 IDE 读旧事件（无 {@code c} 键）按 1 计，双向兼容。
     */
    public int aggregatedCount = 1;
}
