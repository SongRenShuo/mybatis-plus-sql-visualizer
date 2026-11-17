// T-SyncK2JMaster183 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlWriteRiskText.kt 移植（顶层函数门面）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * T-FullTableWriteRisk 全表写风险判据（纯函数层）。
 * Kotlin 顶层函数 {@code fullTableWriteRisk} / {@code maskSqlLiterals} → 本门面类静态方法。
 */
public final class SqlWriteRiskTextKt {

    private SqlWriteRiskTextKt() {
    }

    /** 字词级 WHERE 判定：{@code where} 必须独立成词（列名 {@code whereabouts} / {@code anywhere} 不误配）。 */
    private static final Pattern WHERE_WORD_REGEX = Pattern.compile("\\bWHERE\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 全表写风险判据（纯函数）：首条语句是 DELETE / UPDATE 且<b>没有 WHERE 子句</b>时给出风险类型。
     *
     * <p>判据细节与边界：</p>
     * <ul>
     *   <li>只看<b>首条语句</b>——分页 COUNT 是并列渲染的第二条，风险恒在主语句上；</li>
     *   <li>首关键字不是 DELETE/UPDATE（空文本、以未还原占位开头的骨架）→ 无风险，不加噪声；</li>
     *   <li>字面量先挖空：{@link #maskSqlLiterals} —— {@code update t set c = 'where'} 不是条件（该报），
     *       {@code delete from t where c = 'x'} 是条件（不该报）；</li>
     *   <li>注释由 {@link SqlTypeClassifier#splitStatements} 同源剥掉，{@code delete from t -- where x} 该报。</li>
     * </ul>
     */
    @Nullable
    public static FullTableWriteRisk fullTableWriteRisk(@NotNull String sql) {
        FullTableWriteRisk kind;
        String firstKeyword = SqlTypeClassifier.getFirstKeyword(sql);
        if ("DELETE".equals(firstKeyword)) {
            kind = FullTableWriteRisk.DELETE;
        } else if ("UPDATE".equals(firstKeyword)) {
            kind = FullTableWriteRisk.UPDATE;
        } else {
            return null;
        }
        var statements = SqlTypeClassifier.splitStatements(sql);
        if (statements.isEmpty()) {
            return null;
        }
        String statement = statements.get(0);
        return WHERE_WORD_REGEX.matcher(maskSqlLiterals(statement)).find() ? null : kind;
    }

    /**
     * 把 SQL 里的字符串字面量（{@code '...'} / {@code "..."}，含 {@code ''} 双写转义）整体挖成空格。
     *
     * <p>只服务 {@link #fullTableWriteRisk} 的关键词判定：长度保持不保证，只保证「字面量内部的词不再被看见」。</p>
     */
    @NotNull
    public static String maskSqlLiterals(@NotNull String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char quote = sql.charAt(i);
            if (quote != '\'' && quote != '"') {
                sb.append(quote);
                i++;
                continue;
            }
            sb.append(' ');
            i++;
            while (i < sql.length()) {
                char c = sql.charAt(i);
                if (c == quote) {
                    // 双写引号 = 转义，仍在字面量内
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                        i += 2;
                        continue;
                    }
                    i++;
                    break;
                }
                i++;
            }
            sb.append(' ');
        }
        return sb.toString();
    }
}
