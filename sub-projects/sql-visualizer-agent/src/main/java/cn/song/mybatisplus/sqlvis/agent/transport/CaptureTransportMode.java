package cn.song.mybatisplus.sqlvis.agent.transport;

/**
 * T4-1：传输旁路开关。
 *
 * <p><b>默认行为零变化</b>：不设属性时仍走 loopback 裸 TCP（{@link SenderLoop}），存量 301 例
 * 语义等价由「默认路径不变」保证——本类只被 {@link EventBus#start(int)} 读一次。
 *
 * <p>OpenIDEA 平台（wasm 沙箱）没有裸 socket，只能 `WebSocket::connect`，因此捕获链在
 * 该系统属性存在时改走 loopback WebSocket（{@link WsSenderLoop} 在 agent 侧监听，插件作客户端
 * 连入）。这是 additive 旁路：{@link SenderLoop} 与 {@link EventBus} 的帧协议/事件语义都不动，
 * WS 信封里的 {@code body} 就是 TCP 下会写出的同一串 JSON 字节。
 *
 * <p>取值：{@code ws} / {@code websocket} / {@code web-socket}（大小写不敏感，两侧空白容忍）→
 * WebSocket；{@code tcp}、缺省与任何其他值 → TCP。非法值回落 TCP 而不是抛错：这是宿主 JVM 的
 * 系统属性，错误配置不允许影响目标应用启动（与心跳/看门狗属性的回落口径一致）。
 */
public final class CaptureTransportMode {

    /** 选择捕获传输的系统属性名（T4-1 契约）。 */
    public static final String TRANSPORT_PROP = "mpsv.capture.transport";

    /** 捕获传输类型。 */
    public enum Mode {
        TCP,
        WS
    }

    private CaptureTransportMode() {
    }

    /** 解析系统属性；任何读失败/非法值都回落 TCP。 */
    public static Mode resolve() {
        try {
            final String raw = System.getProperty(TRANSPORT_PROP);
            if (raw == null) {
                return Mode.TCP;
            }
            final String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if ("ws".equals(value) || "websocket".equals(value) || "web-socket".equals(value)) {
                return Mode.WS;
            }
        } catch (Throwable ignore) {
            // 宿主 VM 属性读取异常不得影响启动：回落默认 TCP
        }
        return Mode.TCP;
    }

    /** 便捷判据；{@link EventBus#start(int)} 用它选择发送线程实现。 */
    public static boolean isWebSocket() {
        return resolve() == Mode.WS;
    }
}