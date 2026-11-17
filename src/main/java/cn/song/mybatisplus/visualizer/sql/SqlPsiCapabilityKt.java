package cn.song.mybatisplus.visualizer.sql;

import com.intellij.lang.Language;
import com.intellij.lang.LanguageParserDefinitions;
import org.jetbrains.annotations.Nullable;

/**
 * T-ExecPsiGate：<b>该语言能否建出 SQL PSI</b>——preview 链与 exec 链共用的唯一判据。
 *
 * <p>由来（T-PreviewPsiDialect，2026-10-30 真机 262）：插件自注册方言（DM / GaussDB / HighGo /
 * Vastbase / GoldenDB）若只有 {@code <sql.dialect>} 而没有配套的 {@code <lang.parserDefinition>}，
 * 平台会沿语言链回落到基语言 {@code SQL} 的 parser definition，其 file node type 绑的是基类
 * SqlLanguage → {@code SqlParserDefinitionBase.createFile} 的强转抛 ClassCastException，
 * 于是 {@code PsiManager.findFile} 拿不到 PSI（真机 10/10 次）。</p>
 *
 * <p>判据取自平台行为本身——{@code SqlParserDefinitionBase.createFile} 要求
 * {@code getFileNodeType().getLanguage()} 是 {@code SqlLanguageDialect}。所以直接查「当前生效的
 * ParserDefinition 的 file node type 绑定的语言是不是 SQL 方言」。平台内置方言
 * （MySQL / PostgreSQL / GenericSQL / SQL92）与补齐后的自注册方言都返回 true；
 * 只有回落到基语言 SQL 的实现（以及根本没有 parser definition 的语言）返回 false。</p>
 *
 * <p><b>刻意不静态引用 {@code com.intellij.sql.*}</b>：本函数在常驻加载面上被调用，
 * DatabaseTools 是可选依赖，直接引用会让无 Database 插件的环境类加载失败。故按类名沿继承链判定。</p>
 *
 * <p>T-ExecPsiGate（2026-11-10）：本函数原为 ui 包内 preview 私有实现（canCreateSqlPsiFor）。
 * exec 链缺的正是这一道门：它把 exec 文件映射到「建不出 PSI」的语言后照常 findFile，直接撞
 * {@code PsiManager.findFile null}（Mac / UOS 用户报障的形态）。现提到 sql 包，两条链共用一份，
 * 避免再次分叉。</p>
 *
 * <p>master 侧为顶层函数，Java 侧按 k2j 惯例落 {@code SqlPsiCapabilityKt} 门面类。</p>
 */
public final class SqlPsiCapabilityKt {

    private SqlPsiCapabilityKt() {
    }

    /** 该语言能否建出 SQL PSI（见类 KDoc；失败一律按 false 降级）。 */
    public static boolean canCreateSqlPsiFor(@Nullable Object language) {
        try {
            if (!(language instanceof Language lang)) {
                return false;
            }
            var parserDefinition = LanguageParserDefinitions.INSTANCE.forLanguage(lang);
            if (parserDefinition == null) {
                return false;
            }
            var fileNodeType = parserDefinition.getFileNodeType();
            Language fileNodeLanguage = fileNodeType != null ? fileNodeType.getLanguage() : null;
            return isSqlLanguageDialectInstance(fileNodeLanguage);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 按类名沿父类链判断（SqlLanguageDialect 是抽象类，不是接口）。 */
    private static boolean isSqlLanguageDialectInstance(@Nullable Object language) {
        Class<?> c = language != null ? language.getClass() : null;
        while (c != null) {
            if ("com.intellij.sql.dialects.SqlLanguageDialect".equals(c.getName())) {
                return true;
            }
            c = c.getSuperclass();
        }
        return false;
    }
}
