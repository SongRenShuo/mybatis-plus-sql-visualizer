package cn.song.mybatisplus.visualizer.sql;

/**
 * 预引号字面量识别（T-PreQuotedLiteral，2026-11-18 用户报障修复）：
 * 参数值本身已是完整引号字面量（用户手工加了引号 / 粘贴 {@code 'abc'} 形态）时，回填渲染不得再
 * {@code replace("'","''")} 转义 + 外层包引号——否则引号翻倍（{@code 'abc'} → {@code '''abc'''}、键入 {@code '} 的
 * 防抖中间帧 → {@code ''''}，先红读数见开发计划 §一）。
 *
 * <p>判据刻意从窄：<b>trim 后 ≥ 2 字符且 {@code '} 开头 {@code '} 结尾</b>。
 * <ul>
 * <li>{@code O'Brien} 不以引号开头 → 不判完整，维持既有转义 {@code 'O''Brien'}（真实撇号转义不回退）；</li>
 * <li>孤引号 {@code '}（长度 1）不判完整——维持转义产出 {@code ''''}（合法 SQL 字面量），原样透传反而断引号；</li>
 * <li>{@code ''}（空串字面量）判完整 → 原样，语义正确。</li>
 * </ul>
 *
 * <p>全链唯一实现：面板回填（{@code renderSingleValue} / {@code renderParamValue} LIKE 分支）与可执行 SQL 链
 * （{@code ParameterReplacer.literal}）共用，杜绝两条链口径分叉（回滚链的四引号前科见
 * {@code RollbackSqlGenerator} 的「不可再转义」注释）。
 */
public final class PreQuotedLiteral {

    private PreQuotedLiteral() {
    }

    /** 值是否已是完整引号字面量（{@code '…'} 形态，内层内容不校验——用户自己的文本原样尊重）。 */
    public static boolean isComplete(String value) {
        String t = value.trim();
        return t.length() >= 2 && t.charAt(0) == '\'' && t.charAt(t.length() - 1) == '\'';
    }

    /**
     * 剥一层外引号（供 LIKE / inQuoted 等「外层上下文自带引号」的场景：内层内容交回调用方，
     * 按既有转义语义重拼）。非完整字面量原样返回。
     */
    public static String stripQuotes(String value) {
        String t = value.trim();
        return isComplete(t) ? t.substring(1, t.length() - 1) : t;
    }
}
