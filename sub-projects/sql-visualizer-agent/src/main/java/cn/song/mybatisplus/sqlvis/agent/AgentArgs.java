package cn.song.mybatisplus.sqlvis.agent;

/**
 * loadAgent 参数契约（§4.3 端口前置协商）：k=v 逗号分隔——
 * {@code port=53210;logLevel=warn;disable=0;stackFrames=24[;token=<hex>]}。外部配置文件兜底不实现
 * （优先级低于 args，一期由 IDE 独占下发，杜绝多方写配置的竞争面）。
 *
 * <p>T-StackHoverUi：新增可选键 {@code stackFrames}（调用栈快照总帧数，钳位见
 * [AgentState#setStackMaxFrames]；缺省 24）。旧 IDE 不下发该键 → 缺省值兜底；未知键继续容忍。
 *
 * <p>T-SAF-02：新增可选键 {@code token}（通道令牌，IDE 侧逐通道生成的 128bit hex）——agent 在
 * HELLO 帧原样回带，IDE 侧验令牌通过才提交会话（loopback 绑定不是对端认证）。旧 IDE 不下发该键
 * → token 为 null、HELLO 不带 token 键（行为与修复前逐字一致）；未知键继续容忍。
 */
public final class AgentArgs {

    private final int port;
    private final String logLevel;
    private final boolean startDisabled;
    private final int stackMaxFrames;
    private final String token;

    private AgentArgs(int port, String logLevel, boolean startDisabled, int stackMaxFrames, String token) {
        this.port = port;
        this.logLevel = logLevel;
        this.startDisabled = startDisabled;
        this.stackMaxFrames = stackMaxFrames;
        this.token = token;
    }

    public static AgentArgs parse(String raw) {
        int port = 0;
        String logLevel = "warn";
        boolean startDisabled = false;
        int stackMaxFrames = 24;
        String token = null;
        if (raw != null && !raw.isEmpty()) {
            // loadAgent 的 args 按 Agentmain（或 -javaagent）原样传入：分号/逗号都容忍
            String[] pairs = raw.replace(';', ',').split(",");
            for (String pair : pairs) {
                if (pair == null || pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                final String k;
                final String v;
                if (eq < 0) {
                    k = pair.trim();
                    v = "";
                } else {
                    k = pair.substring(0, eq).trim();
                    v = pair.substring(eq + 1).trim();
                }
                if ("port".equals(k)) {
                    try {
                        port = Integer.parseInt(v);
                    } catch (NumberFormatException ignore) {
                        // 非法端口留 0——install 处放弃挂载
                    }
                } else if ("logLevel".equals(k)) {
                    logLevel = v.isEmpty() ? "warn" : v;
                } else if ("disable".equals(k)) {
                    startDisabled = "1".equals(v) || "true".equalsIgnoreCase(v);
                } else if ("stackFrames".equals(k)) {
                    try {
                        stackMaxFrames = Integer.parseInt(v);
                    } catch (NumberFormatException ignore) {
                        // 非法值留缺省——AgentState 写入口会再钳位
                    }
                } else if ("token".equals(k)) {
                    // T-SAF-02：通道令牌（hex 原样持有，不校验形态——IDE 侧验令牌是唯一判定位）
                    token = v.isEmpty() ? null : v;
                }
            }
        }
        return new AgentArgs(port, logLevel, startDisabled, stackMaxFrames, token);
    }

    public int getPort() {
        return port;
    }

    public String getLogLevel() {
        return logLevel;
    }

    public boolean isStartDisabled() {
        return startDisabled;
    }

    public int getStackMaxFrames() {
        return stackMaxFrames;
    }

    /** 通道令牌（T-SAF-02；旧 IDE 不下发该键时为 null——HELLO 不带 token 键）。 */
    public String getToken() {
        return token;
    }
}
