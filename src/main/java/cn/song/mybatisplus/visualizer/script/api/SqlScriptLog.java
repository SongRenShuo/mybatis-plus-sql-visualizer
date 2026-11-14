package cn.song.mybatisplus.visualizer.script.api;

/**
 * T-RollbackSnapshotPreExec P6：脚本专属输出通道（照 CoolRequest SimpleScriptLog 形态收敛）。
 *
 * 脚本里**不要用 System.out**——它直通 IDE 控制台，违反「控制台 [SqlVisAgent] 只保留
 * 必要的」口径；所有输出走本对象，编排层把缓冲内容显示在脚本日志区。
 *
 * 线程模型：脚本在独立线程执行，println 与缓冲读取跨线程——buffer 用 synchronized 保护。
 * 刻意不继承 PrintStream：PrintStream 的写出终点与自己无关的字节流会产生双份缓冲陷阱，
 * 脚本面只需要 println/printf 两个入口。
 */
public class SqlScriptLog {

    private final StringBuilder buffer = new StringBuilder();
    private final int maxChars;

    public SqlScriptLog() {
        this(64 * 1024);
    }

    public SqlScriptLog(int maxChars) {
        this.maxChars = maxChars;
    }

    public void println(String x) {
        synchronized (buffer) {
            appendLine(x == null ? "null" : x);
        }
    }

    public void println(Object x) {
        println(String.valueOf(x));
    }

    public SqlScriptLog printf(String format, Object... args) {
        println(String.format(format, args));
        return this;
    }

    private void appendLine(String line) {
        if (buffer.length() >= maxChars) {
            // 超预算丢弃最旧的一半（保尾段——脚本日志关注最近输出）
            buffer.delete(0, buffer.length() / 2);
            buffer.append("…（早期输出已达预算，截断）\n");
        }
        buffer.append(line).append('\n');
    }

    /** 当前缓冲内容（编排层展示脚本日志区用）。 */
    public String content() {
        synchronized (buffer) {
            return buffer.toString();
        }
    }
}
