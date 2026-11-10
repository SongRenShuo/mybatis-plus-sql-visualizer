package cn.song.mybatisplus.visualizer.sql

import com.intellij.lang.Language
import com.intellij.lang.LanguageParserDefinitions

/**
 * T-ExecPsiGate：**该语言能否建出 SQL PSI**——preview 链与 exec 链共用的唯一判据。
 *
 * 由来（T-PreviewPsiDialect，2026-10-30 真机 262）：插件自注册方言（DM / GaussDB / HighGo /
 * Vastbase / GoldenDB）若只有 `<sql.dialect>` 而没有配套的 `<lang.parserDefinition>`，平台会沿
 * 语言链回落到基语言 `SQL` 的 parser definition，其 file node type 绑的是基类
 * `SqlLanguage` → `SqlParserDefinitionBase.createFile` 的强转抛
 * `ClassCastException`，于是 `PsiManager.findFile` 拿不到 PSI（真机 10/10 次）。
 *
 * 判据取自平台行为本身——`SqlParserDefinitionBase.createFile` 要求
 * `getFileNodeType().getLanguage()` 是 `SqlLanguageDialect`。所以直接查「当前生效的
 * ParserDefinition 的 file node type 绑定的语言是不是 SQL 方言」。平台内置方言
 * （MySQL / PostgreSQL / GenericSQL / SQL92）与补齐后的自注册方言都返回 true；
 * 只有回落到基语言 `SQL` 的实现（以及根本没有 parser definition 的语言）返回 false。
 *
 * **刻意不静态引用 `com.intellij.sql.*`**：本函数在常驻加载面上被调用，DatabaseTools 是可选依赖，
 * 直接引用会让无 Database 插件的环境类加载失败。故按类名沿继承链判定。
 *
 * T-ExecPsiGate（2026-11-10）：本函数原为 `ui` 包内 preview 私有实现（`canCreateSqlPsiFor`）。
 * exec 链缺的正是这一道门：它把 exec 文件映射到「建不出 PSI」的语言后照常 `findFile`，直接
 * 撞 `PsiManager.findFile null`（Mac / UOS 用户报障的形态）。现提到 `sql` 包，两条链共用一份，
 * 避免再次分叉。
 */
internal fun canCreateSqlPsiFor(language: Any?): Boolean = runCatching {
    val lang = language as? Language ?: return false
    val fileNodeLanguage = LanguageParserDefinitions.INSTANCE.forLanguage(lang).fileNodeType.language
    isSqlLanguageDialectInstance(fileNodeLanguage)
}.getOrDefault(false)

/** 按类名沿父类链判断（`SqlLanguageDialect` 是抽象类，不是接口）。 */
private fun isSqlLanguageDialectInstance(language: Any?): Boolean {
    var c: Class<*>? = language?.javaClass
    while (c != null) {
        if (c.name == "com.intellij.sql.dialects.SqlLanguageDialect") return true
        c = c.superclass
    }
    return false
}
