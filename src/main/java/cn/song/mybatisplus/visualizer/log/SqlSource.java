package cn.song.mybatisplus.visualizer.log;

/**
 * 日志条目来源（区分不同 ORM 框架的捕获结果）。
 */
public enum SqlSource {
    /** MyBatis-Plus 控制台日志（Preparing: / Parameters: 格式）。 */
    MYBATIS,
    /**
     * iBatis 2.x 控制台日志（T-Ibatis2Support 券 H）：{@code {pstm-N}} / {@code {conn-N}} 标记的
     * <b>三行组</b>——{@code {pstm-N} Executing Statement:}（SQL）+ {@code {pstm-N} Parameters:}
     * （{@code [v1, null, v2]} 列表形态）+ {@code {pstm-N} Types:}（逐位置 JDBC 类型），
     * 产出点见 MyBatisLogParser 的 iBatis 分支。
     *
     * <p>与 MYBATIS 的分工：两者同为「MyBatis 家族控制台日志」，但 iBatis 2.x 是<b>独立框架</b>
     * （{@code com.ibatis.*} 命名空间，{@code #name#} 占位符，非 MyBatis 3 的 {@code #{}}），
     * 日志形态与参数行格式都不同——2026-11-06 T-IbatisPanwei 为控扩散面曾复用本枚举的 MYBATIS 值
     * （决策 D-3），本值即为该欠账的兑现（用户需要按来源区分/筛选）。</p>
     *
     * <p><b>不落盘</b>（与 MYBATIS 同口径）：SqlLogParamCodec 只对 MCP / SHARDINGSPHERE 写 {@code s} 键，
     * 历史读回一律 UNKNOWN。</p>
     */
    IBATIS,
    /** Hibernate/JPA 控制台日志（Hibernate: / binding parameter 格式）。 */
    HIBERNATE,
    /** Spring JdbcTemplate 控制台日志（Executing prepared SQL statement [SQL] / Setting SQL statement parameter value: ... 格式）。 */
    JDBC,
    /**
     * ShardingSphere 双段日志（R10 T-ShardingSphereCompat，5.x sql-show 形态）：
     * {@code Logic SQL:} 主条目 + {@code Actual SQL:} 折叠附注（{@link LogSqlEntry#actualSql()}）。
     * 骨架按公开文档形态实现，真实语料未采样（V36）——行为细节见解析器 TODO。
     */
    SHARDINGSPHERE,
    /** agent 字节码通道（M56 增强捕获）：应用 JVM 内 JDBC 执行点 hook 直取的结构化事件。 */
    AGENT,
    /**
     * MCP 工具通道（T-McpAuditTrail / T-MAT-03）：MCP 执行类工具在真实数据源上跑过的只读语句。
     *
     * <p>当前唯一产出点 = {@code sqlv_explain}（十个 MCP 工具里唯一有执行面的一个）——条目文本恒为
     * 调用方传入的<b>原始 selectSql</b>，绝不落 {@code EXPLAIN <select>} 包装句（T-MAT-02：包装句首 token
     * 为 {@code EXPLAIN}，会被 {@link LogStatementClassifier} 判 {@code OTHER} → 列表无类型染色）。
     * 因此本枚举值同时充当「该条来自 EXPLAIN 包装执行」的独立标记。
     *
     * <p>与 {@link #AGENT} 的分工：agent 是应用进程内 JDBC hook（被观测应用自己执行的 SQL），
     * 本值是本插件 MCP 工具主动执行的语句——两者都不来自控制台日志解析。
     */
    MCP,
    /** 未知来源（兼容旧版本条目）。 */
    UNKNOWN,
}
