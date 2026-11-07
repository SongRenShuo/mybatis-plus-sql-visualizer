package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.sql.ParamHint;

/**
 * {@code LogSqlRestorer.kt} 顶层扩展函数（{@code LogSqlParam.toHint()}）的 Java 承载类。
 */
public final class LogSqlRestorerKt {
    private LogSqlRestorerKt() {
    }

    /**
     * 日志参数 → 回填提示（T-M42）：数值/布尔原样直出（{@link LogSqlRestorer#PLAIN_TYPES}），
     * 其余类型标注引号（对齐 {@link LogSqlRestorer} 还原语义）；无类型标注走面板启发式（null）。
     * 日期族额外带 {@code temporal = true}（T-DateLiteralBackfill）：面板参数行据此产出 ANSI 类型字面量，
     * 与还原链同口径。
     * 供 {@code LogSqlEntry.toRecognizedPreview} 识别参数时生成参数行回填提示。
     */
    public static ParamHint toHint(LogSqlParam param) {
        if (param.type() == null) {
            return new ParamHint(null, null, false);
        }
        if (LogSqlRestorer.PLAIN_TYPES.contains(param.type())) {
            return new ParamHint(false, null, false);
        }
        return new ParamHint(true, null, false,
                cn.song.mybatisplus.visualizer.sql.SqlDateLiteral.isTemporalType(param.type()));
    }

    // T-PSG-01：string-aware `?` 占位符定位实现已上提至
    // cn.song.mybatisplus.visualizer.sql.PlaceholderPositionsKt.placeholderPositions（日志还原 / 复制可执行 SQL /
    // 面板回填三条拼接链跨包共用同一份位点口径；`internal` 跨包不可达，故搬迁至 sql 包而非放宽可见性）。
    // 本包内的调用点经顶部 import 解析到新落点，行为逐字节不变；本包的 countPlaceholders
    // （见 LogEntryPreviewRecognizerKt）仍是薄封装。
}
