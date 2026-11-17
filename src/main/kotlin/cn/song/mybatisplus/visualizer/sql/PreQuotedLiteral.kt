package cn.song.mybatisplus.visualizer.sql

/**
 * 预引号字面量识别（T-PreQuotedLiteral，2026-11-18 用户报障修复）：
 * 参数值本身已是完整引号字面量（用户手工加了引号 / 粘贴 `'abc'` 形态）时，回填渲染不得再
 * `replace("'","''")` 转义 + 外层包引号——否则引号翻倍（`'abc'` → `'''abc'''`、键入 `'` 的
 * 防抖中间帧 → `''''`，先红读数见开发计划 §一）。
 *
 * 判据刻意从窄：**trim 后 ≥ 2 字符且 `'` 开头 `'` 结尾**。
 * - `O'Brien` 不以引号开头 → 不判完整，维持既有转义 `'O''Brien'`（真实撇号转义不回退）；
 * - 孤引号 `'`（长度 1）不判完整——维持转义产出 `''''`（合法 SQL 字面量），原样透传反而断引号；
 * - `''`（空串字面量）判完整 → 原样，语义正确。
 *
 * 全链唯一实现：面板回填（`renderSingleValue` / `renderParamValue` LIKE 分支）与可执行 SQL 链
 * （`ParameterReplacer.literal`）共用，杜绝两条链口径分叉（回滚链的四引号前科见
 * `RollbackSqlGenerator` 的「不可再转义」注释）。
 */
object PreQuotedLiteral {

    /** 值是否已是完整引号字面量（`'…'` 形态，内层内容不校验——用户自己的文本原样尊重）。 */
    fun isComplete(value: String): Boolean {
        val t = value.trim()
        return t.length >= 2 && t.startsWith('\'') && t.endsWith('\'')
    }

    /**
     * 剥一层外引号（供 LIKE / inQuoted 等「外层上下文自带引号」的场景：内层内容交回调用方，
     * 按既有转义语义重拼）。非完整字面量原样返回。
     */
    fun stripQuotes(value: String): String {
        val t = value.trim()
        return if (isComplete(t)) t.substring(1, t.length - 1) else t
    }
}
