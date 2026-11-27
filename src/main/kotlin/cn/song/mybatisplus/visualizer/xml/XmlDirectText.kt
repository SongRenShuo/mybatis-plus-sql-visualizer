package cn.song.mybatisplus.visualizer.xml

import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText

/*
 * XML 直属文本段提取、CDATA 剥离与实体还原（O-72 延伸修复：hbm 命名查询链同口径缺口；
 * T-XmlEntityDecode：PSI 源文本实体未还原）。
 *
 * 实测（O-72 判别性读数）：本平台 `XmlText.text` 对 CDATA 返回带 `<![CDATA[ ... ]]>`
 * 包裹的**字面全文**，并非展开内容——裸拼接会把包裹原样带进 SQL/HQL 转换。
 * 统一剥包裹后再拼（混排文本/多 CDATA 段均安全）。
 *
 * T-XmlEntityDecode（用户报障：`<sql>` 片段预览把 `&gt;=` 带进 SQL）：`XmlText.text` 同时是
 * **PSI 源文本**，标准实体/字符引用原样保留（平台只对 `&quot;` 提前解掉）。MyBatis 运行时由
 * XML 解析器还原实体（真机 SQL 得到 `>=`），预览链若直拼原文就与真机语义分叉。故拼接口径改为
 * [decodeXmlTextSegment]：普通文本段还原实体，**CDATA 段只剥包裹、绝不解码**（与 DOM 链
 * `PagesXmlParser`「CDATA 内文本为字面量」及 MyBatis 行为一致）。
 */

/** 直属 XmlText 拼接提取（CDATA 剥包裹 + 普通文本实体还原，trim）；排除子标签内容（调用方按需）。 */
internal fun directTextOf(tag: XmlTag): String =
    tag.children
        .filterIsInstance<XmlText>()
        .joinToString("") { decodeXmlTextSegment(it.text) }
        .trim()

/**
 * CDATA 感知的文本段还原（T-XmlEntityDecode）：
 *  - `<![CDATA[ … ]]>` 区间内**原样保留**（字面量，含 `&`，绝不解码）；
 *  - 区间外按 XML 规则还原：五个标准实体 + `&#nn;` / `&#xNN;` 字符引用；
 *  - 未闭合的 `<![CDATA[` → 其后内容原样（PSI 容错文本，不猜）；
 *  - 只解码一次：`&amp;gt;` → `&gt;`（单趟扫描，命中即替换，不做二次重扫）；
 *  - 未知实体（`&nbsp;`）与非法字符引用保留原文，不抛异常。
 */
internal fun decodeXmlTextSegment(raw: String): String {
    val sb = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val cdataStart = raw.indexOf(CDATA_START, i)
        if (cdataStart < 0) {
            appendEntityDecoded(sb, raw, i, raw.length)
            break
        }
        appendEntityDecoded(sb, raw, i, cdataStart)
        val contentStart = cdataStart + CDATA_START.length
        val closeIdx = raw.indexOf(CDATA_END, contentStart)
        if (closeIdx < 0) {
            sb.append(raw, contentStart, raw.length)
            break
        }
        sb.append(raw, contentStart, closeIdx)
        i = closeIdx + CDATA_END.length
    }
    return sb.toString()
}

/** 把 `[from, to)` 区间按 XML 实体规则解码后追加（区间内不得含 CDATA 区间）。 */
private fun appendEntityDecoded(sb: StringBuilder, text: String, from: Int, to: Int) {
    var i = from
    while (i < to) {
        val amp = text.indexOf('&', i)
        if (amp < 0 || amp >= to) {
            sb.append(text, i, to)
            return
        }
        sb.append(text, i, amp)
        val semi = text.indexOf(';', amp + 1)
        if (semi < 0 || semi >= to) {
            // 无配对分号的 `&`：按字面保留（可能是 SQL 位运算/拼接符，不猜）
            sb.append(text, amp, to)
            return
        }
        val entity = text.substring(amp + 1, semi)
        val decoded = decodeEntity(entity)
        if (decoded != null) {
            sb.append(decoded)
        } else {
            sb.append(text, amp, semi + 1)
        }
        i = semi + 1
    }
}

/** 单个实体/字符引用 → 字面字符；无法识别返回 null（调用方保留原文）。 */
private fun decodeEntity(entity: String): String? = when (entity) {
    "lt" -> "<"
    "gt" -> ">"
    "amp" -> "&"
    "quot" -> "\""
    "apos" -> "'"
    else -> decodeCharReference(entity)
}

/**
 * `&#nn;`（十进制）/ `&#xNN;`（十六进制，x 大小写均可）→ 字符。
 * 非法输入（空、非数字、0、除代理区、超出 Unicode 上限）返回 null。
 */
private fun decodeCharReference(entity: String): String? {
    if (entity.length < 2 || entity[0] != '#') return null
    val hex = entity[1] == 'x' || entity[1] == 'X'
    val digits = if (hex) entity.substring(2) else entity.substring(1)
    if (digits.isEmpty()) return null
    val valid = digits.all { c ->
        if (hex) c.isDigit() || c.lowercaseChar() in 'a'..'f' else c.isDigit()
    }
    if (!valid) return null
    val code = digits.toIntOrNull(if (hex) 16 else 10) ?: return null
    if (code <= 0 || code > Character.MAX_CODE_POINT) return null
    // 代理区码点单独不是合法字符（XML 规范要求成对代理，字符引用不得指向半个代理）
    if (code in 0xD800..0xDFFF) return null
    return String(Character.toChars(code))
}

/** 纯 CDATA 包裹剥离（首词判定等「只需去掉标记、不需要实体语义」的场景）。 */
internal fun unwrapCdata(text: String): String = text.replace(CDATA_WRAPPER, "$1")

private const val CDATA_START = "<![CDATA["
private const val CDATA_END = "]]>"
private val CDATA_WRAPPER = Regex("(?s)<!\\[CDATA\\[(.*?)]]>")