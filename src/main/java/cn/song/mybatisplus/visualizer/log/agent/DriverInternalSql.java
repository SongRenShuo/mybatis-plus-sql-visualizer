// T-SyncK2JMaster181 车道C 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/DriverInternalSql.kt 移植
package cn.song.mybatisplus.visualizer.log.agent;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * T-DriverInternalSqlFilter（2026-11-21）：驱动内部语句识别（连接初始化族），纯函数零平台依赖。
 *
 * <p>动因（沙盒实证）：mysql-connector 5.1.49 建连接时自执行一批系统变量查询——带版本注释的
 * {@code SELECT @@session.auto_increment_increment AS …} 大查询、{@code SELECT @@session.autocommit;}、
 * {@code SELECT @@session.transaction.read_only;}——agent 挂上后全部进了 SQL 日志（Jdk16Demo 现场，
 * 46 条事件头部全是这些，业务 SQL 一条没跑）。它们不是业务 SQL，在插件入口层过滤：
 * 对已挂载的存量 agent 立即生效（用户应用不用重启），agent 侧继续全量上报
 * （判据留调整余地，事件流量开销可忽略）。</p>
 *
 * <p>判据闭集（扩展须先有实证，见计划 20261121-1412 §一）：
 * <ol>
 *   <li>归一化后以驱动版本注释开头——Connector/J 5.0/5.1 初始化查询自钉
 *       「斜杠星号 + mysql-connector-java-…」形态的指纹注释（字面常量见 {@link #CRITERION_COMMENT}）；</li>
 *   <li>归一化后以 {@code select @@session.} / {@code set @@session.} 开头——同驱动系统变量族，无注释形态。</li>
 * </ol>
 *
 * <p>刻意不收（防误伤，教训：保守兜底须先量真实数据）：
 * <ul>
 *   <li>{@code @@} 宽匹配——SQL Server 的 {@code SELECT @@IDENTITY} / {@code @@ROWCOUNT} 是真业务 SQL 常客，
 *       {@code @@} 家族只收 {@code @@session.} 带点形态；</li>
 *   <li>{@code SET NAMES} / {@code SET character_set_results} 等——本沙盒未见实证，拿到证据再登记。</li>
 * </ul>
 *
 * <p>大小写与前导空白归一后比较（驱动实际输出为小写注释 + 原样 SQL，归一只放宽不收紧）。</p>
 *
 * <p>master 的 {@code internal object DriverInternalSql} 的 Java 承载（final class + 私有构造 + 全静态）。</p>
 */
public final class DriverInternalSql {

    private DriverInternalSql() {
    }

    /** 判据 1 的注释指纹字面量（KDoc 里不能写字面「斜杠星号」——Kotlin 块注释嵌套会吞文档）。 */
    private static final String CRITERION_COMMENT = "/* mysql-connector-java-";

    /** 判据 2 的系统变量族前缀（无注释形态）。 */
    private static final List<String> CRITERION_SESSION_PREFIXES =
            List.of("select @@session.", "set @@session.");

    /** 被过滤的驱动内部语句计数（诊断缝；测试读）。 */
    private static final AtomicLong droppedCount = new AtomicLong();

    public static boolean isInternal(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        String lower = sql.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith(CRITERION_COMMENT)) {
            return true;
        }
        for (String prefix : CRITERION_SESSION_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 丢弃计数 +1（{@link AgentSession} 入队点调用）。 */
    static void markDropped() {
        droppedCount.incrementAndGet();
    }

    /** 已丢弃的驱动内部语句数（测试可读）。 */
    public static long droppedEvents() {
        return droppedCount.get();
    }
}
