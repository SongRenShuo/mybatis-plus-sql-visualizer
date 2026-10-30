package cn.song.mybatisplus.visualizer.mcp

/**
 * T-McpToolset MCP 工具错误码（DBX 语义对齐，调研报告 §5.3 验收标准）。
 *
 * 本文件与同包 [McpSelectGuard]/[McpSchemaFormatter]/[McpExplainFormatter]/[McpDialectDetector]
 * 构成**纯逻辑层**：不 import 任何 mcpServer/com.intellij 类型，单测无需平台与桩 classpath。
 * McpToolset 适配层只做注解声明、ReadAction 包裹与参数转发。
 *
 * 文案面向 agent（英文常量，不进 bundle——bundle 是用户 UI 文案；计划 §架构定案 i18n 口径）。
 */
object McpToolErrors {

    /** 只读白名单拒绝（非 SELECT 语句）。 */
    const val READ_ONLY = "MCP_READ_ONLY"

    /** 语句形态拒绝（多语句 / FOR UPDATE 锁读 / SELECT INTO / 解析失败 fail closed）。 */
    const val SQL_BLOCKED = "SQL_BLOCKED"

    /** 入参缺失/形态非法。 */
    const val INVALID_ARGUMENT = "INVALID_ARGUMENT"

    /** 数据源别名不匹配 / 无数据源。 */
    const val DATASOURCE_NOT_FOUND = "DATASOURCE_NOT_FOUND"

    /** 数据源不是关系型 SQL 库（Redis/Mongo 等 URL 子协议命中非 SQL 家族，早拒）。 */
    const val NOT_A_SQL_DATASOURCE = "NOT_A_SQL_DATASOURCE"

    /** 建连/执行失败（不可达、鉴权失败等）。 */
    const val DATASOURCE_UNREACHABLE = "DATASOURCE_UNREACHABLE"

    /** 表在所有数据源均无元数据（未连接 / introspection 未完成）。 */
    const val SCHEMA_NOT_AVAILABLE = "SCHEMA_NOT_AVAILABLE"

    /** 当前方言不支持 EXPLAIN。 */
    const val DIALECT_UNSUPPORTED = "DIALECT_UNSUPPORTED"

    /** 源码定位失败（文件/行号无可识别 SQL 源）。 */
    const val SOURCE_NOT_FOUND = "SOURCE_NOT_FOUND"

    /** 工具虽仍可被已知名称直接调用，但当前设置已禁用。 */
    const val TOOL_DISABLED = "TOOL_DISABLED"

    /** 项目未就绪（索引未完成或项目关闭中）。 */
    const val PROJECT_NOT_READY = "PROJECT_NOT_READY"

    /** 结构化错误文本：`[CODE] message`（agent 可读；DBX `[MCP_READ_ONLY]` 同款形态）。 */
    fun code(code: String, message: String): String = "[$code] $message"
}
