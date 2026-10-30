package cn.song.mybatisplus.visualizer.sql.verify

import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.sql.DM_RESERVED
import cn.song.mybatisplus.visualizer.sql.DmDialect
import cn.song.mybatisplus.visualizer.sql.GAUSSDB_RESERVED
import cn.song.mybatisplus.visualizer.sql.H2_RESERVED
import cn.song.mybatisplus.visualizer.sql.H2Dialect
import cn.song.mybatisplus.visualizer.sql.KINGBASE_RESERVED
import cn.song.mybatisplus.visualizer.sql.KingBaseDialect
import cn.song.mybatisplus.visualizer.sql.MARIA_RESERVED
import cn.song.mybatisplus.visualizer.sql.MYSQL_RESERVED
import cn.song.mybatisplus.visualizer.sql.MariaDbDialect
import cn.song.mybatisplus.visualizer.sql.OPENGAUSS_RESERVED
import cn.song.mybatisplus.visualizer.sql.OpenGaussDialect
import cn.song.mybatisplus.visualizer.sql.PG_RESERVED
import cn.song.mybatisplus.visualizer.sql.PostgreSQLDialect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3（Tier 3）：DM（达梦）/ KingBase（人大金仓）**官方文档黄金断言**（P-立项-05）。
 *
 * 两款国产商业库无公开容器镜像可自动化（真实环境测试后续进行），本测试把官方文档逐词核对的
 * 结论固化为黄金断言，防止未来改动悄悄破坏已验证行为：
 *
 *  - DM8：官方《DM8 SQL 语言使用手册》（eco.dameng.com/document/dm/zh-cn/pm/）
 *      - 分页文法（check-phrases.html 4.10）：`LIMIT M,N` / `LIMIT M OFFSET N` / `OFFSET..FETCH` 均
 *        为核心语法（COMPATIBLE_MODE=0 默认模式即可用）——插件取 `LIMIT [offset,] count` ✓
 *      - 附录 1 保留字（sql-appendix.html）：带 * 系统保留字全量 → [DM_RESERVED]（黄金清单逐词对齐）
 *      - FAQ（faq-sql-gramm.html）：仅双引号可屏蔽关键字/引用标识符；CASE_SENSITIVE=Y 默认下
 *        双引号内大小写敏感
 *      - UPSERT：官方仅 MERGE INTO（社区文章《浅谈达梦与 MySQL 的差异》明确不支持
 *        ON DUPLICATE KEY UPDATE；词表无 CONFLICT）→ 降级纯 INSERT ✓
 *  - KingbaseES V9：官方手册（bbs.kingbase.com.cn/kingbase-html/v9/）
 *      - 《SQL保留字和关键字》章节 22：分类「保留」的全量词 → [KINGBASE_RESERVED]（黄金清单对齐）
 *      - 《SQL 语句:INSERT》（help.kingbase.com.cn V8 SQL_Statements_9.html）：on_conflict_clause
 *        官方文法 + 官方示例 `ON CONFLICT (did) DO UPDATE SET dname = EXCLUDED.dname` ✓
 *      - 分页：PG 兼容 `LIMIT count OFFSET offset` ✓
 *
 * 另含 H2 / MariaDB / PG 保留字集合的官方对齐断言（P-立项-05 修正的回归防护）。
 */
class DialectDocGoldenTest {

    // ------------------------------------------------------------------
    // DM（达梦）：官方附录 1 保留字黄金清单（带 * 系统保留字，逐词核对于 2026-08-28）
    // ------------------------------------------------------------------

    /** 黄金清单与 [DM_RESERVED] 独立维护——任何一侧改动不一致立即红。 */
    private val dmOfficialReserved: Set<String> = setOf(
        "absolute", "abstract", "add", "admin", "all", "alter", "and", "any", "array",
        "arraylen", "as", "asc", "assign", "audit", "auto_increment",
        "begin", "between", "bigdatediff", "binary", "bool", "both", "break", "bstring", "by", "byte",
        "call", "case", "cast", "char", "check", "cluster", "clusterbtr", "collation", "column",
        "comment", "commit", "commitwork", "connect", "connect_by_root", "const", "constraint",
        "contains", "context", "continue", "convert", "create", "cross", "crypto", "cube",
        "current", "current_date", "current_timestamp",
        "dateadd", "datediff", "datepart", "dbtimezone", "declare", "decode", "default", "delete",
        "desc", "dimension", "disable", "diskspace", "distance", "distinct", "distributed", "do",
        "domain", "double", "drop",
        "efconstruction", "else", "elseif", "elsif", "enable", "end", "equ", "exchange", "exec",
        "execute", "exists", "exit", "explain", "extern", "extract",
        "fetch", "final", "finally", "first", "flashback", "float", "for", "foreign", "from",
        "full", "fully", "function",
        "get", "goto", "grant", "group", "grouping",
        "having",
        "identity", "if", "ifnull", "immediate", "in", "index", "inner", "inline", "insert",
        "int", "intersect", "interval", "into", "is",
        "join", "json_arrayagg", "json_table", "json_transform",
        "keep",
        "large", "leading", "left", "less", "lexer", "like", "list", "lnnvl", "localtimestamp", "loop",
        "member", "minus", "model", "multiset",
        "natural", "new", "next", "nocopy", "nocycle", "nosort", "not", "null",
        "object", "of", "on", "optimize", "or", "order", "out", "over", "overlay", "override",
        "parameters", "partition", "pendant", "percent", "pipe", "primary", "print", "prior",
        "private", "privileges", "procedure", "protected", "public",
        "raise", "record", "ref", "reference", "references", "referencing",
        "regexp_split_to_table", "relative", "replicate", "return", "returning", "reverse",
        "revoke", "right", "rollback", "rollup", "row", "rownum", "rows",
        "savepoint", "sbyte", "schema", "section", "select", "sessiontimezone", "set", "sets",
        "short", "some", "sql_calc_found_rows", "static", "struct", "subpartition", "switch",
        "synonym", "sysdate", "systimestamp",
        "table", "than", "timestampadd", "timestampdiff", "to", "top", "to_vector", "trailing",
        "treat", "trigger", "trim", "truncate", "try", "typedef", "typeof",
        "uint", "ulong", "union", "unique", "until", "update", "user", "ushort", "using",
        "values", "varray", "verify", "versions", "view", "virtual", "void",
        "when", "whenever", "where", "while", "window", "with", "within", "without",
        "xml", "xmlagg", "xmlattributes", "xmlcast", "xmlelement", "xmlnamespaces", "xmlparse",
        "xmlquery", "xmlserialize", "xmltable",
    )

    @Test
    fun `DM保留字集合与官方附录黄金清单一致`() {
        val missingInImpl = dmOfficialReserved - DM_RESERVED
        val extraInImpl = DM_RESERVED - dmOfficialReserved
        assertTrue("DM_RESERVED 缺失官方保留字: $missingInImpl", missingInImpl.isEmpty())
        assertTrue("DM_RESERVED 含官方附录之外的词: $extraInImpl", extraInImpl.isEmpty())
    }

    @Test
    fun `DM方言行为锚点_官方文档结论`() {
        // 分页：官方文法 LIMIT M,N（官方示例 LIMIT 4,3）与 LIMIT N
        assertEquals("LIMIT 10", DmDialect.renderLimit(LimitClause(count = 10)))
        assertEquals("LIMIT 5, 10", DmDialect.renderLimit(LimitClause(offset = 5, count = 10)))
        // 引用：仅双引号（官方 FAQ：双引号屏蔽关键字；反引号需 8.1.3.37+ 且 MySQL 兼容模式，不采用）
        assertEquals("\"order\"", DmDialect.quoteIdentifier("order"))
        // 保留字判定（官方附录实证的双向差异）：
        //   DM 保留而原 Oracle 集合没有 → 必须包裹（漏包即语法错误）
        assertEquals("\"top\"", DmDialect.quoteIfKeyword("top"))
        assertEquals("\"domain\"", DmDialect.quoteIfKeyword("domain"))
        assertEquals("\"section\"", DmDialect.quoteIfKeyword("section"))
        assertEquals("\"identity\"", DmDialect.quoteIfKeyword("identity"))
        assertEquals("\"int\"", DmDialect.quoteIfKeyword("int"))
        //   Oracle 保留而 DM 仅关键字（NUMBER/SIZE/LEVEL/RAW/DATE）→ 不包裹
        //   （DM CASE_SENSITIVE=Y 下错包 + 小写原文 = 双引号大小写敏感失配风险）
        assertEquals("number", DmDialect.quoteIfKeyword("number"))
        assertEquals("size", DmDialect.quoteIfKeyword("size"))
        assertEquals("level", DmDialect.quoteIfKeyword("level"))
        // UPSERT：官方仅 MERGE INTO 复杂形态 → 降级纯 INSERT
        assertNull(DmDialect.renderUpsert("id", listOf("name")))
        assertNull(DmDialect.renderUpsertStatement("t", listOf("id", "name"), "id"))
    }

    // ------------------------------------------------------------------
    // KingBase（人大金仓）：官方 V9 保留字黄金清单 + 行为锚点
    // ------------------------------------------------------------------

    /**
     * 黄金清单：V9 手册《SQL保留字和关键字》章节 22 分类「保留」的词（逐词核对于 2026-08-28，
     * 117 词）+ T-XcDialects3 券 05 引擎补词 `enckey`（KingbaseES V009R001C010 真机
     * `pg_get_keywords()` 标 R、列引用报 `syntax error at or near "enckey"`，2026-10-29
     * 实测；官方 V8/V9 文档表标「非保留」，以引擎为准）= **118 词**。
     */
    private val kingbaseOfficialReserved: Set<String> = setOf(
        "all", "analyse", "analyze", "and", "any", "array", "as", "asc", "asymmetric",
        "authorization", "auto_increment", "binary", "both", "case", "cast", "check",
        "collate", "collation", "column", "concurrently", "connect", "connect_by_iscycle",
        "connect_by_isleaf", "connect_by_root", "constraint", "create", "cross",
        "current_catalog", "current_date", "current_role", "current_schema", "current_time",
        "current_timestamp", "current_user", "default", "deferrable", "desc", "distinct",
        "do", "else", "enckey", "end", "except", "false", "fetch", "for", "foreign", "freeze",
        "from", "full", "grant", "group", "having", "ilike", "in", "initially", "inner",
        "intersect", "into", "is", "isnull", "join", "lateral", "leading", "left", "like",
        "limit", "localtime", "localtimestamp", "minus", "natural", "nocycle", "not",
        "notnull", "null", "nvl", "nvl2", "offset", "on", "online", "only", "or", "order",
        "outer", "overlaps", "placing", "primary", "references", "return", "returning",
        "right", "rowid", "rownum", "select", "session_user", "similar", "some",
        "symmetric", "synonym", "sys_connect_by_path", "systimestamp", "table",
        "tablesample", "then", "to", "top", "trailing", "true", "union", "unique", "user",
        "using", "variadic", "verbose", "when", "where", "window", "with",
    )

    @Test
    fun `KingBase保留字集合与官方V9黄金清单一致`() {
        val missingInImpl = kingbaseOfficialReserved - KINGBASE_RESERVED
        val extraInImpl = KINGBASE_RESERVED - kingbaseOfficialReserved
        assertTrue("KINGBASE_RESERVED 缺失官方保留字: $missingInImpl", missingInImpl.isEmpty())
        assertTrue("KINGBASE_RESERVED 含官方清单之外的词: $extraInImpl", extraInImpl.isEmpty())
    }

    @Test
    fun `KingBase方言行为锚点_官方文档结论`() {
        // 分页：PG 兼容形态
        assertEquals("LIMIT 10", KingBaseDialect.renderLimit(LimitClause(count = 10)))
        assertEquals("LIMIT 10 OFFSET 5", KingBaseDialect.renderLimit(LimitClause(offset = 5, count = 10)))
        // 引用：双引号（PG 内核）
        assertEquals("\"order\"", KingBaseDialect.quoteIdentifier("order"))
        // 保留字判定（官方清单实证的金仓特有保留字——原 ≈PG 集合漏包项）
        assertEquals("\"rowid\"", KingBaseDialect.quoteIfKeyword("rowid"))
        assertEquals("\"rownum\"", KingBaseDialect.quoteIfKeyword("rownum"))
        assertEquals("\"top\"", KingBaseDialect.quoteIfKeyword("top"))
        assertEquals("\"binary\"", KingBaseDialect.quoteIfKeyword("binary"))
        assertEquals("\"minus\"", KingBaseDialect.quoteIfKeyword("minus"))
        // T-XcDialects3 券 05：引擎标 R、列引用语法错误（真机 2026-10-29）——文档表标非保留，以引擎为准；
        // 别名位 SELECT 1 AS enckey 实测通过，收录后该形态多一对引号属可接受多包
        assertEquals("\"enckey\"", KingBaseDialect.quoteIfKeyword("enckey"))
        //   常见业务词在金仓非保留（裸用合法）→ 不包裹
        assertEquals("value", KingBaseDialect.quoteIfKeyword("value"))
        assertEquals("name", KingBaseDialect.quoteIfKeyword("name"))
        assertEquals("status", KingBaseDialect.quoteIfKeyword("status"))
        // UPSERT：官方 INSERT 文法 on_conflict_clause + 官方示例 EXCLUDED 用法
        assertEquals(
            "ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name",
            KingBaseDialect.renderUpsert("id", listOf("name")),
        )
        assertNull(KingBaseDialect.renderUpsertStatement("t", listOf("id", "name"), "id"))
    }

    // ------------------------------------------------------------------
    // H2 / MariaDB / PG：P-立项-05 修正的回归防护（引擎实测 + 官方关键字表）
    // ------------------------------------------------------------------

    @Test
    fun `H2保留字集合_引擎实测项回归防护`() {
        // H2 2.3.232 引擎实测裸用即语法错误的词（原 ≈PG 集合全部漏包）——必须恒在集合内
        listOf(
            "key", "value", "values", "day", "year", "interval", "row", "if", "minus",
            "qualify", "rownum", "top", "unknown", "uescape", "current_catalog",
            "current_schema", "user", "order", "group", "limit", "offset", "table",
        ).forEach { assertTrue("H2 保留字缺失: $it", it in H2_RESERVED) }
        // 常见业务词 H2 非保留（引擎实测裸用合法）→ 不包裹
        assertEquals("name", H2Dialect.quoteIfKeyword("name"))
        assertEquals("status", H2Dialect.quoteIfKeyword("status"))
        // UPSERT 整语句形态（H2 原生 MERGE..KEY；ON CONFLICT 在 H2 任何模式语法错误）
        assertNull(H2Dialect.renderUpsert("id", listOf("name")))
    }

    @Test
    fun `MariaDB保留字集合_官方差量回归防护`() {
        // MariaDB 官方保留字含 MySQL 8.0 非保留词（原 = MySQL 集合漏包项）
        listOf(
            "except", "intersect", "binary", "offset", "returning", "current_role",
            "cursor", "row_number", "stats_persistent",
        ).forEach { assertTrue("MariaDB 保留字缺失: $it", it in MARIA_RESERVED) }
        // MySQL 8.0 保留字基线全部保留（复用关系不回退）
        assertTrue(MYSQL_RESERVED.all { it in MARIA_RESERVED })
        // UPSERT 同 MySQL 形态
        assertEquals(
            "ON DUPLICATE KEY UPDATE name = VALUES(name)",
            MariaDbDialect.renderUpsert("id", listOf("name")),
        )
    }

    @Test
    fun `PG保留字集合_官方补齐项回归防护`() {
        // PG 官方附录「reserved (can be function or type name)」类别（原集合漏包项）
        listOf(
            "authorization", "binary", "concurrently", "freeze", "isnull", "notnull", "similar",
        ).forEach { assertTrue("PG 保留字缺失: $it", it in PG_RESERVED) }
        // UPSERT：PG 9.5+ 官方文法（真 PG 容器验证在 Docker 测试）
        assertEquals(
            "ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name",
            PostgreSQLDialect.renderUpsert("id", listOf("name")),
        )
    }

    // ------------------------------------------------------------------
    // openGauss / GaussDB（T-GaussDbDialect 券 01）
    // ------------------------------------------------------------------

    @Test
    fun `PG保留字_官方保留类补项_两词回归防护`() {
        // PG 官方附录标 reserved、但原集合只有 isnull 而没有 is、且完全缺 procedure
        listOf("is", "procedure").forEach { assertTrue("PG 保留字缺失: $it", it in PG_RESERVED) }
        assertEquals("\"is\"", PostgreSQLDialect.quoteIfKeyword("is"))
        assertEquals("\"procedure\"", PostgreSQLDialect.quoteIfKeyword("procedure"))
    }

    /**
     * openGauss 保留字黄金清单——与 [OPENGAUSS_RESERVED] **独立维护**：任一侧漂移即红。
     *
     * 内容 = openGauss 5.0.0 真机 `pg_get_keywords()` 的 `R`(93) + `T`(27) + `C`(63) 三类
     * （DBX 只读通道，2026-10-29）∪ 官方《关键字》页保留列独有 3 词（`imcstored` / `self` /
     * `unimcstored`；`groupparent` 已在 `R` 类内）。
     *
     * 用空白分隔的文本块而非 `setOf(...)` 逐项展开，只是因为 186 词会让本文件膨胀到千行；
     * 「独立维护」的语义不变——**不得**改成引用生产侧常量，否则本用例只剩自证。
     */
    private val openGaussOfficialReserved: Set<String> = """
        all analyse analyze and any array
        as asc asymmetric authid authorization between
        bigint binary binary_double binary_integer bit boolean
        both bucketcnt buckets byteawithoutorder byteawithoutorderwithequal case
        cast char character check coalesce collate
        collation column compact concurrently constraint create
        cross csn current_catalog current_date current_role current_schema
        current_time current_timestamp current_user date dec decimal
        decode default deferrable deltamerge desc distinct
        do else end except excluded exists
        extract false fetch float for foreign
        freeze from full grant greatest group
        grouping groupparent having hdfsdirectory ilike imcstored
        in initially inner inout int integer
        intersect interval into is join leading
        least left less like limit localtime
        localtimestamp maxvalue minus modify national natural
        nchar nocycle none not notnull null
        nullif number numeric nvarchar nvarchar2 nvl
        offset on only or order out
        outer overlaps overlay performance placing position
        precision primary procedure real recyclebin references
        reject returning right row rownum select
        self session_user setof shrink similar smalldatetime
        smallint some substring symmetric sysdate table
        tablesample then time timecapsule timestamp timestampdiff
        tinyint to trailing treat trim true
        unimcstored union unique user using values
        varchar varchar2 variadic verbose verify when
        where window with xmlattributes xmlconcat xmlelement
        xmlexists xmlforest xmlparse xmlpi xmlroot xmlserialize
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

    @Test
    fun `openGauss保留字_真机黄金清单逐词一致`() {
        assertEquals("黄金清单自检：应为 186 词", 186, openGaussOfficialReserved.size)
        assertEquals(
            "OPENGAUSS_RESERVED 与 openGauss 5.0.0 真机 pg_get_keywords() 黄金清单不一致",
            openGaussOfficialReserved,
            OPENGAUSS_RESERVED,
        )
    }

    /**
     * 反向锚点：这 17 词是旧 `PG_RESERVED` 基线**漏包**的 openGauss 保留字。
     *
     * 只要有人把 `OpenGaussDialect.reservedWords` 改回 `PG_RESERVED`（或删掉本表里的词），
     * 本用例立刻变红——`rownum` 真机报 `ROWNUM cannot be used as an alias`，
     * 旧基线下列名为 `rownum` 的表会产出语法错误 SQL。
     */
    @Test
    fun `openGauss保留字_旧PG基线漏包十七词回归防护`() {
        listOf(
            "rownum", "minus", "modify", "nocycle", "performance", "shrink", "verify", "sysdate",
            "excluded", "buckets", "authid", "less", "maxvalue", "groupparent", "reject",
            "procedure", "is",
        ).forEach { assertTrue("openGauss 保留字缺失（旧 PG 基线漏包项）: $it", it in OPENGAUSS_RESERVED) }
        assertEquals("\"rownum\"", OpenGaussDialect.quoteIfKeyword("rownum"))
        assertEquals("\"sysdate\"", OpenGaussDialect.quoteIfKeyword("sysdate"))
        // 不在集合内的普通列名不得被引用（防「整表加引号」式过包）
        assertEquals("user_name", OpenGaussDialect.quoteIfKeyword("user_name"))
    }

    @Test
    fun `gaussDB保留字_在openGauss基线上叠加官方增量`() {
        assertTrue(
            "GAUSSDB_RESERVED 应包含 OPENGAUSS_RESERVED 全量（多包无害，不剔除 openGauss 独有词）",
            GAUSSDB_RESERVED.containsAll(OPENGAUSS_RESERVED),
        )
        // GaussDB 官方《关键字》页保留列独有 11 词（maxvalue 已在 openGauss R 类内，不重复计）
        listOf(
            "crossbucket", "dbtimezone", "fenced", "force", "match", "offline", "online",
            "priorer", "regexp", "rlike", "sessiontimezone",
        ).forEach { assertTrue("GaussDB 官方增量缺失: $it", it in GAUSSDB_RESERVED) }
        assertEquals(OPENGAUSS_RESERVED.size + 11, GAUSSDB_RESERVED.size)
    }

    // ------------------------------------------------------------------
    // PostgreSQL / GaussDB 的全量独立黄金清单（券 01 第 3 项：三方言各一份）
    // ------------------------------------------------------------------

    /**
     * PostgreSQL 官方保留字全量黄金清单——与 [PG_RESERVED] **独立维护**（任一侧漂移即红）。
     *
     * 来源：PG 官方附录《SQL Key Words》中标 `reserved` 与 `reserved (can be function or type name)`
     * 的全量，含 T-GaussDbDialect 券 01 补入的 `is` / `procedure`（原集合只有 `isnull`、且完全缺
     * `procedure`）。
     */
    private val postgresOfficialReserved: Set<String> = """
        all analyse analyze and any array
        as asc asymmetric authorization binary both
        case cast check collate column concurrently
        constraint create current_catalog current_date current_role current_time
        current_timestamp current_user default deferrable desc distinct
        do else end except false fetch
        for foreign freeze from grant group
        having in initially intersect into is
        isnull lateral leading limit localtime localtimestamp
        not notnull null offset on only
        or order placing primary procedure references
        returning select session_user similar some symmetric
        table then to trailing true union
        unique user using variadic when where
        window with
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

    @Test
    fun `PG保留字_官方全量黄金清单逐词一致`() {
        assertEquals("黄金清单自检：应为 86 词", 86, postgresOfficialReserved.size)
        assertEquals(
            "PG_RESERVED 与 PG 官方附录保留字全量黄金清单不一致",
            postgresOfficialReserved,
            PG_RESERVED,
        )
    }

    /**
     * GaussDB 官方保留字全量黄金清单——与 [GAUSSDB_RESERVED] **独立维护**（任一侧漂移即红）。
     *
     * 内容 = openGauss 5.0.0 真机 `pg_get_keywords()` 的 R∪T∪C（183）∪ 官方关键字页保留列独有
     * 3 词（`imcstored` / `self` / `unimcstored`）∪ GaussDB 官方页独有增量 11 词 = 197 词。
     *
     * 口径说明（为什么是并集而不是「GaussDB 页那一列」）：两个来源对「保留」的定义不同，且
     * GaussDB 商用版无真机可复核；按 `SqlKeywords` 既有「宁可多包、不漏包」口径取并集——
     * 多引用一次是幂等安全的，漏引用则是语法错误。
     */
    private val gaussDbOfficialReserved: Set<String> = """
        all analyse analyze and any array
        as asc asymmetric authid authorization between
        bigint binary binary_double binary_integer bit boolean
        both bucketcnt buckets byteawithoutorder byteawithoutorderwithequal case
        cast char character check coalesce collate
        collation column compact concurrently constraint create
        cross crossbucket csn current_catalog current_date current_role
        current_schema current_time current_timestamp current_user date dbtimezone
        dec decimal decode default deferrable deltamerge
        desc distinct do else end except
        excluded exists extract false fenced fetch
        float for force foreign freeze from
        full grant greatest group grouping groupparent
        having hdfsdirectory ilike imcstored in initially
        inner inout int integer intersect interval
        into is join leading least left
        less like limit localtime localtimestamp match
        maxvalue minus modify national natural nchar
        nocycle none not notnull null nullif
        number numeric nvarchar nvarchar2 nvl offline
        offset on online only or order
        out outer overlaps overlay performance placing
        position precision primary priorer procedure real
        recyclebin references regexp reject returning right
        rlike row rownum select self session_user
        sessiontimezone setof shrink similar smalldatetime smallint
        some substring symmetric sysdate table tablesample
        then time timecapsule timestamp timestampdiff tinyint
        to trailing treat trim true unimcstored
        union unique user using values varchar
        varchar2 variadic verbose verify when where
        window with xmlattributes xmlconcat xmlelement xmlexists
        xmlforest xmlparse xmlpi xmlroot xmlserialize
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

    @Test
    fun `gaussDB保留字_官方全量黄金清单逐词一致`() {
        assertEquals("黄金清单自检：应为 197 词", 197, gaussDbOfficialReserved.size)
        assertEquals(
            "GAUSSDB_RESERVED 与 GaussDB 官方保留字全量黄金清单不一致",
            gaussDbOfficialReserved,
            GAUSSDB_RESERVED,
        )
    }
}
