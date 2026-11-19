// T-SyncK2JMaster180 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpGateFilterRegistrar.kt（顶层 enum GateRegistrationState）
package cn.song.mybatisplus.visualizer.mcp;

/**
 * 外部会话门控的注册状态（设置页只读展示 + 日志；不表示工具是否可调用）。
 */
public enum GateRegistrationState {
    /** 尚未探测（项目还没打开过）。 */
    PENDING,

    /** 已挂到平台过滤 EP，门控生效。 */
    REGISTERED,

    /** 平台契约不在白名单内（含形态 C），不注册——门控不生效，MCP 服务不受影响。 */
    UNRECOGNIZED,

    /** 总开关关闭或 Registry 逃生开关关闭，不注册。 */
    DISABLED,

    /** 当前 IDE 没有该平台能力（253 及更早）。 */
    UNSUPPORTED,

    /** 运行期发现契约漂移，已自动摘除注册——门控不生效，MCP 服务不受影响。 */
    TRIPPED,
}
