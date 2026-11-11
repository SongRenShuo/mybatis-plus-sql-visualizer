package cn.song.mybatisplus.visualizer.sql

/**
 * SQL 注释剥离（复制纯 SQL —— 忽略注释）。
 *
 * 纯文本扫描（不依赖 PSI/IDE，任意环境可单测）：
 *  - `--` 行注释：从 `--` 到行尾全部标记删除
 *  - `/* */` 块注释：单行/多行整段标记删除
 *  - 字符串/字符字面量内的标记不视为注释（转义引号正确处理）
 *  - 代码空行原样保留
 */
object SqlCommentStripper {

    private const val MARK = '\u0000'

    /** 剥离全部 SQL 注释，返回纯 SQL 文本。 */
    fun strip(sql: String): String {
        val chars = sql.toCharArray()
        var i = 0
        while (i < chars.size) {
            when {
                chars[i] == '\'' -> i = skipQuoted(chars, i)
                chars[i] == '-' && i + 1 < chars.size && chars[i + 1] == '-' ->
                    i = markLineComment(chars, i)
                chars[i] == '/' && i + 1 < chars.size && chars[i + 1] == '*' ->
                    i = markBlockComment(chars, i)
                else -> i++
            }
        }
        return filterLines(chars)
    }

    private fun skipQuoted(chars: CharArray, start: Int): Int {
        val quote = chars[start]
        var i = start + 1
        while (i < chars.size) {
            when (chars[i]) {
                '\\' -> i += 2
                quote -> return i + 1
                else -> i++
            }
        }
        return chars.size
    }

    private fun markLineComment(chars: CharArray, start: Int): Int {
        var i = start
        while (i < chars.size && chars[i] != '\n') {
            chars[i] = MARK
            i++
        }
        return i
    }

    private fun markBlockComment(chars: CharArray, start: Int): Int {
        var i = start
        chars[i] = MARK
        chars[i + 1] = MARK
        i += 2
        while (i < chars.size) {
            if (chars[i] == '*' && i + 1 < chars.size && chars[i + 1] == '/') {
                chars[i] = MARK
                chars[i + 1] = MARK
                return i + 2
            }
            if (chars[i] != '\n') chars[i] = MARK
            i++
        }
        return chars.size
    }

    private fun filterLines(chars: CharArray): String {
        val sb = StringBuilder(chars.size)
        var i = 0
        while (i < chars.size) {
            var end = i
            while (end < chars.size && chars[end] != '\n') end++
            val hasCode = (i until end).any { chars[it] != MARK && !chars[it].isWhitespace() }
            val marked = (i until end).any { chars[it] == MARK }
            if (hasCode || !marked) {
                val line = StringBuilder()
                for (j in i until end) {
                    if (chars[j] != MARK) line.append(chars[j])
                }
                while (line.isNotEmpty() && line.last().isWhitespace()) line.deleteCharAt(line.length - 1)
                sb.append(line)
                if (end < chars.size) sb.append('\n')
            }
            i = if (end < chars.size) end + 1 else end
        }
        return sb.toString()
    }
}
