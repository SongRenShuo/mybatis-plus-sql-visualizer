package cn.song.mybatisplus.visualizer.ui

/**
 * P-05：参数批量导入解析器——支持 JSON / CSV / key=value 三种格式自动识别。
 *
 * 纯逻辑、无依赖，可独立单测。格式兼容清单：
 *  - JSON：Postman/Insomnia 导出形态（仅扁平 object，嵌套对象/数组跳过该 key）。
 *  - CSV：DBeaver 导出形态（首行 header + 单行值，RFC 4180 最小兼容——引号包裹含逗号值）。
 *  - key=value：手写形态（行分隔，`=` 左侧 trim 为 key，右侧 trim 为 value）；
 *    URL query string（`&` 视为分隔符）归入本路径。
 *
 * 边界：空文本 / 解析失败 → 返回空 map；无 `=` 的行丢弃；值含 `=` 取第一个 `=` 分割。
 */
object ParamImportParser {

    /**
     * 自动识别格式并解析为参数映射。
     *
     * 识别顺序：
     *  1. 以 `{` 或 `[` 开头 → [parseJson]
     *  2. 含换行且首行含 `,` → [parseCsv]
     *  3. 否则 → [parseKeyValue]
     *
     * @param text 剪贴板原始文本
     * @return 参数映射（key → value），解析失败或空文本返回空 map
     */
    fun parse(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        return when {
            text.trimStart().startsWith("{") || text.trimStart().startsWith("[") -> parseJson(text)
            // T-HiddenBugPerfScan 券 I②：CSV 分流前守卫——多行 key=value 的首行值含逗号
            // （`a=1,2\nb=3`）曾被误分流进 parseCsv，键值全错位且无提示；首行有 `=` 即
            // key=value 形态（CSV 首行是 header，不含 `=`）
            text.contains("\n") && text.lineSequence().first().contains(",") &&
                !text.lineSequence().first().contains("=") -> parseCsv(text)
            else -> parseKeyValue(text)
        }
    }

    /**
     * 解析扁平 JSON（仅 String / Number / Boolean 值；嵌套对象 / 数组跳过该 key）。
     *
     * 自研轻量解析器（不依赖 Jackson）：逐字符扫描，提取顶层 `"key": value` 对。
     * value 为字符串时去引号；为数字/布尔时取原文；为对象/数组/null 时跳过。
     */
    fun parseJson(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val trimmed = text.trim()
        // 仅处理 object（{...}）；数组形态走本函数但无顶层 key → 返回空 map
        if (!trimmed.startsWith("{")) return emptyMap()
        var i = 1 // 跳过首 { （已校验）
        val len = trimmed.length
        while (i < len) {
            // 跳过空白与逗号
            while (i < len && (trimmed[i] == ' ' || trimmed[i] == '\t' || trimmed[i] == '\n' || trimmed[i] == '\r' || trimmed[i] == ',')) i++
            if (i >= len || trimmed[i] == '}') break
            // 读取 key（必须双引号包裹）
            if (trimmed[i] != '"') return result // 非预期格式，返回已解析部分
            i++
            val keyStart = i
            while (i < len && trimmed[i] != '"') {
                if (trimmed[i] == '\\' && i + 1 < len) i++ // 跳过转义字符
                i++
            }
            val key = trimmed.substring(keyStart, i)
            if (i < len) i++ // 跳过闭合引号
            // 跳过空白到 :
            while (i < len && (trimmed[i] == ' ' || trimmed[i] == '\t')) i++
            if (i >= len || trimmed[i] != ':') return result
            i++ // 跳过 :
            // 跳过空白到 value 起始
            while (i < len && (trimmed[i] == ' ' || trimmed[i] == '\t')) i++
            if (i >= len) break
            // 读取 value
            when {
                trimmed[i] == '"' -> {
                    // 字符串值
                    i++
                    val sb = StringBuilder()
                    while (i < len && trimmed[i] != '"') {
                        if (trimmed[i] == '\\' && i + 1 < len) {
                            // 转义：取转义字符后的原始字符（\" → "，\\ → \，\/ → /，\n → 换行等）
                            i++
                            sb.append(unescape(trimmed[i]))
                        } else {
                            sb.append(trimmed[i])
                        }
                        i++
                    }
                    if (i < len) i++ // 跳过闭合引号
                    result[key] = sb.toString()
                }
                trimmed[i] == '{' || trimmed[i] == '[' -> {
                    // 嵌套对象 / 数组 → 跳过该 key（不递归展平）
                    // T-HiddenBugPerfScan 券 I①：返回值必须接回 i——曾丢弃返回值不推进，
                    // 外层 while 停在 '{' 处被「非引号即返回」分支截断，嵌套 key 之后的
                    // 全部顶层键静默丢失（Postman/Insomnia 嵌套导出粘贴即 0 匹配）。
                    i = skipValue(trimmed, i)
                }
                else -> {
                    // 数字 / true / false → 取到分隔符或结束
                    val valueStart = i
                    while (i < len && trimmed[i] != ',' && trimmed[i] != '}' && trimmed[i] != '\n' && trimmed[i] != '\r') i++
                    val raw = trimmed.substring(valueStart, i).trim()
                    if (raw.isNotEmpty() && raw != "null") result[key] = raw
                }
            }
        }
        return result
    }

    /** JSON 转义字符还原（最小集：支持 \" \\ \/ \n \r \t \b \f）。 */
    private fun unescape(c: Char): Char = when (c) {
        '"' -> '"'
        '\\' -> '\\'
        '/' -> '/'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'b' -> '\u0008'
        'f' -> '\u000c'
        else -> c
    }

    /**
     * 跳过 JSON 中一个 value（嵌套对象 / 数组），返回跳过后的位置。
     * 从 value 首字符（{ 或 [）开始，按括号深度跳过。
     */
    private fun skipValue(text: String, start: Int): Int {
        val open = text[start]
        val close = if (open == '{') '}' else ']'
        var depth = 0
        var inString = false
        var i = start
        val len = text.length
        while (i < len) {
            val c = text[i]
            if (inString) {
                if (c == '\\' && i + 1 < len) i++ // 跳过转义
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return i + 1
                    }
                }
            }
            i++
        }
        return i
    }

    /**
     * 解析 CSV（首行 header + 第二行值，RFC 4180 最小兼容——引号包裹含逗号值）。
     *
     * 多行值场景本函数不支持（仅取第二行），复杂结构建议用 JSON 或 key=value。
     */
    fun parseCsv(text: String): Map<String, String> {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return emptyMap()
        val headers = parseCsvRow(lines[0])
        val values = parseCsvRow(lines[1])
        if (headers.isEmpty()) return emptyMap()
        val result = linkedMapOf<String, String>()
        headers.forEachIndexed { idx, key ->
            val k = key.trim()
            if (k.isNotEmpty()) {
                result[k] = values.getOrNull(idx)?.trim() ?: ""
            }
        }
        return result
    }

    /** 解析 CSV 单行（RFC 4180 最小兼容：引号包裹含逗号/引号值，"" 转义为 "）。 */
    private fun parseCsvRow(line: String): List<String> {
        val fields = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        val len = line.length
        while (i < len) {
            val c = line[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && line[i + 1] == '"') {
                        sb.append('"')
                        i++ // 跳过转义引号第二字符
                    } else {
                        inQuotes = false
                    }
                } else {
                    sb.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> {
                        fields += sb.toString()
                        sb.clear()
                    }
                    else -> sb.append(c)
                }
            }
            i++
        }
        fields += sb.toString()
        return fields
    }

    /**
     * 解析 key=value 格式（行分隔；URL query string 的 `&` 视为分隔符）。
     *
     * 每行取第一个 `=` 分割：左侧 trim 为 key，右侧 trim 为 value。
     * 无 `=` 的行丢弃；空文本返回空 map。
     */
    fun parseKeyValue(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        // 统一分隔符：含 `&` 视为 URL query string，按 `&` 和换行同时分隔
        val lines = text.split("&", "\n", "\r")
        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue
            val eq = trimmedLine.indexOf('=')
            if (eq <= 0) continue // 无 = 或 = 在行首（空 key）丢弃
            val key = trimmedLine.substring(0, eq).trim()
            val value = trimmedLine.substring(eq + 1).trim()
            if (key.isNotEmpty()) result[key] = value
        }
        return result
    }
}
