package cn.song.mybatisplus.visualizer.execute

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * T-WorkspaceHeal（2026-11-13）：修复被本插件旧版本写坏的 `.idea/workspace.xml`。
 *
 * 缺陷背景（真机实证，见 `DataSourceSelector` 的 `rememberRunner` KDoc）：`PropertiesComponent` 存的是
 * **项目级**持久化状态，平台用 kotlinx 序列化成**一整块 JSON** 落在 workspace.xml 的
 * `<component name="PropertiesComponent">` 里。旧版本在后台线程写该服务，与平台保存竞争，把
 * `mpsql.visualizer.last.runner.id` 那条值的**开头引号**削掉了（真机样本：`...&quot;id&quot;: 8d6c2a1b-…&quot;,`）。
 *
 * 后果不是「这条记忆丢了」而是「整块 JSON 解析失败」——平台每次启动报
 * `JsonDecodingException: Expected quotation mark '"', but had '8'`，且**不会重写该条目**，
 * 等于用户工程文件被永久污染。写侧根因已修（写入归位 EDT），本文件负责**存量治愈**。
 *
 * 修复策略＝**文本层定点手术**，不重建 JSON：
 *  - 只认 [OWN_PROPERTY_KEY_PREFIX] 前缀的键，别人的键一律不碰；
 *  - 值缺引号的，保留载荷补回引号；载荷含引号/反斜杠/控制字符这类无法安全重建的，降级成空串
 *    （宁可丢一条「上次用哪个数据源」的启发式记忆，也不能写出一块新的坏 JSON）；
 *  - 改完整块重新解析，**仍不合法就一个字节都不写**（说明别处也坏了，不归本插件管）。
 *
 * 本文件**刻意不引平台类**：判定内核是纯字符串函数，测试因此落在 `testFast` 快桶。
 */

/** 本插件在项目级 `PropertiesComponent` 里使用的键前缀（与 `DataSourceSelector` 的三个常量对齐）。 */
internal const val OWN_PROPERTY_KEY_PREFIX = "mpsql.visualizer."

/** workspace.xml 里承载项目级 PropertiesComponent 的组件标签。 */
internal const val PROPERTIES_COMPONENT_OPEN_TAG = "<component name=\"PropertiesComponent\">"

/** 组件闭合标签（JSON 内容里不可能出现该字面量）。 */
internal const val PROPERTIES_COMPONENT_CLOSE_TAG = "</component>"

/** 修复判定结果。 */
internal sealed class RepairOutcome {

    /** 组件 JSON 本就合法——健康工程走这条，调用方零写入。 */
    object Healthy : RepairOutcome()

    /** JSON 不合法，但坏的不是本插件的键：按约定不碰别人的数据。 */
    object NoOwnDamage : RepairOutcome()

    /** 本插件的键已改，但整块 JSON 仍不合法（别处也坏了），**不落盘**。 */
    object Unrepairable : RepairOutcome()

    /**
     * 修好且整块 JSON 已重新校验通过，可安全落盘。
     *
     * @param text      修复后的组件文本（与输入同一转义形态）
     * @param fixedKeys 本次修动的键（按出现顺序，供日志与气泡点名）
     */
    class Repaired(val text: String, val fixedKeys: List<String>) : RepairOutcome()
}

private const val QUOT = "&quot;"

/**
 * 整文件级入口：定位 `<component name="PropertiesComponent">` 块并把修复结果拼回原文。
 *
 * 拼接只替换该块的**内容**，前后文（其它 component、标签、缩进）逐字节保留——工程文件是用户资产，
 * 除被修的那一行外不允许有任何字符变化。
 *
 * @param raw workspace.xml 的完整文本（UTF-8 已还原）
 * @return `Repaired.text` 是**整文件**新文本
 */
internal fun repairWorkspaceFileText(raw: String): RepairOutcome {
    if (!raw.contains(OWN_PROPERTY_KEY_PREFIX)) return RepairOutcome.Healthy
    val openIdx = raw.indexOf(PROPERTIES_COMPONENT_OPEN_TAG)
    if (openIdx < 0) return RepairOutcome.Healthy
    val contentStart = openIdx + PROPERTIES_COMPONENT_OPEN_TAG.length
    val closeIdx = raw.indexOf(PROPERTIES_COMPONENT_CLOSE_TAG, contentStart)
    if (closeIdx < 0) return RepairOutcome.Healthy
    return when (val inner = repairPropertiesComponentText(raw.substring(contentStart, closeIdx))) {
        is RepairOutcome.Repaired -> RepairOutcome.Repaired(
            raw.substring(0, contentStart) + inner.text + raw.substring(closeIdx),
            inner.fixedKeys,
        )
        else -> inner
    }
}

/**
 * 对 `<component name="PropertiesComponent">` 的**内容**（XML 转义后的 JSON）做定点修复。
 *
 * 输入输出都是 workspace.xml 里的原始文本形态（含 `&quot;` 转义），调用方无需再做转义/还原，
 * 这样落盘时除被修的那几行外**逐字节不变**。
 */
internal fun repairPropertiesComponentText(escaped: String): RepairOutcome {
    if (isPlatformLoadable(unescapeXml(escaped))) return RepairOutcome.Healthy

    // split('\n') 把每行的行尾 '\r'（CRLF 工程文件）留在该行内，再用 '\n' 原样拼回即逐字节保真。
    val fixedKeys = ArrayList<String>(2)
    val lines = escaped.split('\n')
    var changed = false
    val rebuilt = lines.map { line ->
        val fixed = repairOneLine(line)
        if (fixed == null) {
            line
        } else {
            fixedKeys += fixed.key
            changed = true
            fixed.line
        }
    }
    if (!changed) return RepairOutcome.NoOwnDamage

    val fixedText = rebuilt.joinToString("\n")
    if (!isPlatformLoadable(unescapeXml(fixedText))) return RepairOutcome.Unrepairable
    return RepairOutcome.Repaired(fixedText, fixedKeys)
}

/** `repairOneLine` 的产物：修真后的一行 + 被修的键。 */
private class FixedLine(val key: String, val line: String)

/**
 * 单行修复：命中 `<缩进>&quot;<本插件键>&quot;:<空白><值><逗号?><尾随空白>` 形态且值未成对加引号时，
 * 补回引号（或降级空串）。未命中/本就健康返回 null。
 */
private fun repairOneLine(line: String): FixedLine? {
    val m = ENTRY.matchEntire(line) ?: return null
    val indent = m.groupValues[1]
    val key = m.groupValues[2]
    val gap = m.groupValues[3]
    val value = m.groupValues[4]
    val comma = m.groupValues[5]
    val tail = m.groupValues[6]
    if (value.length >= QUOT.length * 2 && value.startsWith(QUOT) && value.endsWith(QUOT)) return null

    // 两端引号可能缺任意一端（真机形态是缺开头），故两端各自剥一刀再判载荷是否可安全重建。
    var payload = value
    if (payload.startsWith(QUOT)) payload = payload.substring(QUOT.length)
    if (payload.endsWith(QUOT)) payload = payload.dropLast(QUOT.length)
    payload = payload.trim()
    val rebuiltValue = if (isSafelyQuotable(payload)) "$QUOT$payload$QUOT" else "$QUOT$QUOT"
    return FixedLine(key, "$indent$QUOT$key$QUOT:$gap$rebuiltValue$comma$tail")
}

/**
 * 键行形态。`${'$'}` 锚点在 Kotlin 正则里默认不吃 `\r`，故尾随空白单列一组原样带走，
 * 保证 CRLF 工程文件修完仍是 CRLF。
 */
private val ENTRY = Regex(
    """^(\s*)&quot;(${Regex.escape(OWN_PROPERTY_KEY_PREFIX)}.*?)&quot;:(\s*)(.*?)(,?)(\s*)$""",
)

/**
 * 载荷能否安全地重新包成 JSON 字符串：
 * 不含裸引号、反斜杠、控制字符，也不含 `&quot;` 实体（那说明原值自带引号，边界已歧义）。
 */
private fun isSafelyQuotable(payload: String): Boolean {
    if (payload.isEmpty()) return false
    for (i in payload.indices) {
        val c = payload[i]
        if (c == '"' || c == '\\' || c.code < 0x20 || c.code == 0x7F) return false
    }
    return !payload.contains(QUOT)
}

/**
 * 判定这份组件文本能否被**平台**加载成功。
 *
 * 关键坑（实测踩到）：`Json.parseToJsonElement` 对**未加引号的值是宽松的**——它把
 * `{"k": 8d6c…}` 解析成一个非字符串 `JsonPrimitive` 而不报错；平台走的却是
 * `BasePropertyService.MyState`（`Map<String, String>`）的**流式严格解码**，遇到裸值直接抛
 * `JsonDecodingException: Expected quotation mark '"'`。若拿 `parseToJsonElement` 的成败当判据，
 * 真机这个损坏形态会被判成「健康」，自愈永不触发（本文件首版即因此三例测试红）。
 *
 * 所以这里按平台的**类型要求**校验：`keyToString` 的每个值必须是 JSON 字符串，
 * `keyToStringList` 的每个值必须是字符串数组。形态对得上，平台才不会再报那条错。
 */
internal fun isPlatformLoadable(text: String): Boolean = runCatching {
    val root = Json.parseToJsonElement(text) as? JsonObject ?: return@runCatching false
    val strings = root["keyToString"] ?: return@runCatching true
    val stringsObj = strings as? JsonObject ?: return@runCatching false
    val lists = root["keyToStringList"]
    val listsOk = lists == null || (lists is JsonObject && lists.values.all { it.isStringArray() })
    listsOk && stringsObj.values.all { (it as? JsonPrimitive)?.isString == true }
}.getOrDefault(false)

private fun JsonElement.isStringArray(): Boolean =
    this is JsonArray && all { (it as? JsonPrimitive)?.isString == true }

/** XML 实体还原（只覆盖 workspace.xml 里会出现的几种，含数字引用）。 */
private fun unescapeXml(text: String): String {
    if (!text.contains('&')) return text
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '&') {
            val semi = text.indexOf(';', i + 1)
            if (semi > i && semi - i <= 12) {
                val decoded = decodeEntity(text.substring(i + 1, semi))
                if (decoded != null) {
                    out.append(decoded)
                    i = semi + 1
                    continue
                }
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}

private fun decodeEntity(entity: String): String? = when (entity) {
    "quot" -> "\""
    "apos" -> "'"
    "lt" -> "<"
    "gt" -> ">"
    "amp" -> "&"
    else -> when {
        entity.startsWith("#x") || entity.startsWith("#X") ->
            entity.substring(2).toIntOrNull(16)?.let(::codePointToString)
        entity.startsWith("#") ->
            entity.substring(1).toIntOrNull()?.let(::codePointToString)
        else -> null
    }
}

private fun codePointToString(codePoint: Int): String? =
    if (!Character.isValidCodePoint(codePoint)) null
    else runCatching { String(Character.toChars(codePoint)) }.getOrNull()