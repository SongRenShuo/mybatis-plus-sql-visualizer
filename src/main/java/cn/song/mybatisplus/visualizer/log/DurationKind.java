package cn.song.mybatisplus.visualizer.log;

/**
 * T-HdAbsorb D215（吸收自 Hibernate Debugger 的 {@code MeasuredDuration(DurationKind.EXECUTION |
 * APPROXIMATE)} 双档语义）：耗时测量的<b>来源分档</b>——诚实标注读数是精确计时还是近似推算。
 *
 * <ul>
 *   <li>{@link #EXECUTION}：增强捕获通道（agent 侧 {@code System.nanoTime()} 差值直测 JDBC 执行窗），精确档；
 *       经 agent 停驻补偿后仍是 EXECUTION——补偿只收紧精度、不改测量来源；</li>
 *   <li>{@link #APPROXIMATE}：控制台日志回落通道（从日志文本解析「xx ms」，受日志输出时序/采样影响），
 *       展示时以 {@code ~} 前缀标注；</li>
 *   <li>null：未知（历史读回旧条目/未携带耗时），展示与既有行为逐字节一致（零回归）。</li>
 * </ul>
 */
public enum DurationKind {
    /** 精确档：agent 纳秒级计时。 */
    EXECUTION,

    /** 近似档：日志文本解析推算，展示带 {@code ~} 前缀。 */
    APPROXIMATE,
}
