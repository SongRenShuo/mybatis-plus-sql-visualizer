package cn.song.mybatisplus.visualizer.sql;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 数据库保留字集合（T-M23.4）。
 *
 * <p>按方言判定（用户确认：数据库方言有多种——判定与符号都随方言走）：
 * 每方言一个独立保留字集合（小写），{@link Dialect#quoteIfKeyword} 只查<b>本方言</b>集合——
 * 例如 {@code number} 是 Oracle 保留字但 MySQL 合法：MySQL 场景不包裹、Oracle 场景包裹。
 *
 * <p>集合构成（尽量完整，来源为各数据库官方关键字文档）：
 *  - {@link #MYSQL_RESERVED}：MySQL 8.0 官方保留字全量
 *  - {@link #SQLITE_RESERVED}：SQLite 官方关键字全量
 *  - {@link #PG_RESERVED}：PostgreSQL 官方保留字全量
 *  - {@link #ORACLE_RESERVED}：Oracle 官方保留字全量
 *  - {@link #SQLSERVER_RESERVED}：SQL Server 官方保留字全量
 *  - {@link #H2_RESERVED}：H2 2.x 官方关键字全量
 *  - {@link #DM_RESERVED}：DM8 官方附录 1「带 * 系统保留字」全量
 *  - {@link #KINGBASE_RESERVED}：KingbaseES V9 官方保留字全量
 *  - {@link #MARIA_RESERVED}：MariaDB 官方保留字全量
 *  - {@link #OPENGAUSS_RESERVED}：openGauss 保留字全量（真机 {@code pg_get_keywords()} 的 R/T/C 三类 183 词
 *    ∪ 官方《关键字》页保留列独有词；T-GaussDbDialect 券 01）——<b>不再复用 {@link #PG_RESERVED}</b>
 *  - {@link #GAUSSDB_RESERVED}：{@link #OPENGAUSS_RESERVED} + GaussDB 官方《关键字》页保留列独有 11 词
 *    （{@code maxvalue} 已在 openGauss R 类内；证据为官方文档级，GaussDB 商用版无真机）
 *  - {@link #HIGHGO_RESERVED}：瀚高保留字（官方 V4.5 手册附录 2 的 150 词 ∪ {@link #PG_RESERVED} ∪ 引擎
 *    R 类实测；T-XcDialects3 券 01）
 *  - {@link #VASTBASE_RESERVED}：海量保留字（官方四份兼容模式关键字表「保留」类并集 ∪ {@link #PG_RESERVED}；
 *    T-XcDialects3 券 02）
 *
 * <p>判定：大小写不敏感。纯函数，无 IDE 依赖，可独立单测。
 */
public final class SqlKeywords {

    private SqlKeywords() {}

    /** MySQL 8.0 官方保留字全量。 */
    public static final Set<String> MYSQL_RESERVED = Set.of(
            "accessible", "add", "all", "alter", "analyze", "and", "as", "asc", "asensitive",
            "before", "between", "bigint", "binary", "blob", "both", "by", "call", "cascade",
            "case", "change", "char", "character", "check", "collate", "column", "condition",
            "constraint", "continue", "convert", "create", "cross", "cube", "cume_dist",
            "current_date", "current_time", "current_timestamp", "current_user", "cursor",
            "database", "databases", "day_hour", "day_microsecond", "day_minute", "day_second",
            "dec", "decimal", "declare", "default", "delayed", "delete", "dense_rank", "desc",
            "describe", "deterministic", "distinct", "distinctrow", "div", "double", "drop",
            "dual", "each", "else", "elseif", "empty", "enclosed", "escaped", "except",
            "exists", "exit", "explain", "false", "fetch", "first_value", "float", "float4",
            "float8", "for", "force", "foreign", "from", "fulltext", "function", "generated",
            "get", "grant", "group", "grouping", "groups", "having", "high_priority",
            "hour_microsecond", "hour_minute", "hour_second", "if", "ignore", "in", "index",
            "infile", "inner", "inout", "insensitive", "insert", "int", "int1", "int2",
            "int3", "int4", "int8", "integer", "interval", "into", "io_after_gtids",
            "io_before_gtids", "is", "iterate", "join", "json_table", "key", "keys", "kill",
            "lag", "last_value", "lateral", "lead", "leading", "leave", "left", "like",
            "limit", "linear", "lines", "load", "localtime", "localtimestamp", "lock",
            "long", "longblob", "longtext", "loop", "low_priority", "master_bind",
            "master_ssl_verify_server_cert", "match", "maxvalue", "mediumblob", "mediumint",
            "mediumtext", "middleint", "minute_microsecond", "minute_second", "mod",
            "modifies", "natural", "not", "no_write_to_binlog", "nth_value", "ntile", "null",
            "numeric", "of", "on", "optimize", "optimizer_costs", "option", "optionally",
            "or", "order", "out", "outer", "outfile", "over", "partition", "percent_rank",
            "precision", "primary", "procedure", "purge", "range", "rank", "read", "reads",
            "read_write", "real", "recursive", "references", "regexp", "release", "rename",
            "repeat", "replace", "require", "resignal", "restrict", "return", "revoke",
            "right", "rlike", "row", "rows", "row_number", "schema", "schemas",
            "second_microsecond", "select", "sensitive", "separator", "set", "show",
            "signal", "smallint", "spatial", "specific", "sql", "sqlexception", "sqlstate",
            "sqlwarning", "sql_big_result", "sql_calc_found_rows", "sql_small_result", "ssl",
            "starting", "stored", "straight_join", "system", "table", "terminated", "then",
            "tinyblob", "tinyint", "tinytext", "to", "trailing", "trigger", "true", "undo",
            "union", "unique", "unlock", "unsigned", "update", "usage", "use", "user",
            "using",
            "utc_date", "utc_time", "utc_timestamp", "values", "varbinary", "varchar",
            "varcharacter", "varying", "virtual", "when", "where", "while", "window",
            "with", "write", "xor", "year_month", "zerofill"
    );

    /** SQLite 官方关键字全量。 */
    public static final Set<String> SQLITE_RESERVED = Set.of(
            "abort", "action", "add", "after", "all", "alter", "always", "analyze", "and",
            "as", "asc", "attach", "autoincrement", "before", "begin", "between", "by",
            "cascade", "case", "cast", "check", "collate", "column", "commit", "conflict",
            "constraint", "create", "cross", "current", "current_date", "current_time",
            "current_timestamp", "database", "default", "deferrable", "deferred", "delete",
            "desc", "detach", "distinct", "do", "drop", "each", "else", "end", "escape",
            "except", "exclude", "exclusive", "exists", "explain", "fail", "filter", "first",
            "following", "for", "foreign", "from", "full", "generated", "glob", "group",
            "groups", "having", "if", "ignore", "immediate", "in", "index", "indexed",
            "initially", "inner", "insert", "instead", "intersect", "into", "is", "isnull",
            "join", "key", "last", "left", "like", "limit", "match", "materialized",
            "natural", "no", "not", "nothing", "notnull", "null", "nulls", "of", "offset",
            "on", "or", "order", "others", "outer", "over", "partition", "plan", "pragma",
            "preceding", "primary", "query", "raise", "range", "recursive", "references",
            "regexp", "reindex", "release", "rename", "replace", "restrict", "returning",
            "right", "rollback", "row", "rows", "savepoint", "select", "set", "table",
            "temp", "temporary", "then", "ties", "to", "transaction", "trigger",
            "unbounded", "union", "unique", "update", "using", "vacuum", "values", "view",
            "virtual", "when", "where", "window", "with", "without"
    );

    /** PostgreSQL 官方保留字全量。P-立项-05：补齐「reserved (can be function or type name)」7 词。 */
    public static final Set<String> PG_RESERVED = Set.of(
            "all", "analyse", "analyze", "and", "any", "array", "as", "asc", "asymmetric",
            "authorization", "binary", "both", "case", "cast", "check", "collate", "column",
            "concurrently", "constraint", "create", "current_catalog", "current_date",
            "current_role", "current_time", "current_timestamp", "current_user", "default",
            "deferrable", "desc", "distinct", "do", "else", "end", "except", "false",
            "fetch", "for", "foreign", "freeze", "from", "grant", "group", "having", "in",
            "initially", "intersect", "into", "is", "isnull", "lateral", "leading", "limit",
            "localtime", "localtimestamp", "not", "notnull", "null", "offset", "on", "only",
            "or", "order", "placing", "primary", "procedure", "references", "returning", "select",
            "session_user", "similar", "some", "symmetric", "table", "then", "to",
            "trailing", "true", "union", "unique", "user", "using", "variadic", "when",
            "where", "window", "with"
    );

    /** Oracle 官方保留字全量。 */
    public static final Set<String> ORACLE_RESERVED = Set.of(
            "access", "add", "all", "alter", "and", "any", "as", "asc", "audit", "between",
            "by", "char", "check", "cluster", "column", "comment", "compress", "connect",
            "create", "current", "date", "decimal", "default", "delete", "desc", "distinct",
            "drop", "else", "exclusive", "exists", "file", "float", "for", "from", "grant",
            "group", "having", "identified", "immediate", "in", "increment", "index",
            "initial", "insert", "integer", "intersect", "into", "is", "level", "like",
            "lock", "long", "maxextents", "minus", "mlslabel", "mode", "modify", "noaudit",
            "nocompress", "not", "nowait", "null", "number", "of", "offline", "on",
            "online", "option", "or", "order", "pctfree", "prior", "privileges", "public",
            "raw", "rename", "resource", "revoke", "row", "rowid", "rownum", "rows",
            "select", "session", "set", "share", "size", "smallint", "start", "successful",
            "synonym", "sysdate", "table", "then", "to", "trigger", "uid", "union",
            "unique", "update", "user", "validate", "values", "varchar", "varchar2",
            "view", "whenever", "where", "with"
    );

    /** SQL Server 官方保留字全量。 */
    public static final Set<String> SQLSERVER_RESERVED = Set.of(
            "add", "all", "alter", "and", "any", "as", "asc", "authorization", "backup",
            "begin", "between", "break", "browse", "bulk", "by", "cascade", "case",
            "check", "checkpoint", "close", "clustered", "coalesce", "collate", "column",
            "commit", "compute", "constraint", "contains", "containstable", "continue",
            "convert", "create", "cross", "current", "current_date", "current_time",
            "current_timestamp", "current_user", "cursor", "database", "dbcc", "deallocate",
            "declare", "default", "delete", "deny", "desc", "disk", "distinct",
            "distributed", "double", "drop", "dump", "else", "end", "errlvl", "escape",
            "except", "exec", "execute", "exists", "exit", "external", "fetch", "file",
            "fillfactor", "for", "foreign", "freetext", "freetexttable", "from", "full",
            "function", "goto", "grant", "group", "having", "holdlock", "identity",
            "identity_insert", "identitycol", "if", "in", "index", "inner", "insert",
            "intersect", "into", "is", "join", "key", "kill", "left", "like", "lineno",
            "load", "merge", "national", "nocheck", "nonclustered", "not", "null",
            "nullif", "of", "off", "offsets", "on", "open", "opendatasource", "openquery",
            "openrowset", "openxml", "option", "or", "order", "outer", "over", "percent",
            "pivot", "plan", "precision", "primary", "print", "proc", "procedure",
            "public", "raiserror", "read", "readtext", "reconfigure", "references",
            "replication", "restore", "restrict", "return", "revert", "revoke", "right",
            "rollback", "rowcount", "rowguidcol", "rule", "save", "schema", "select",
            "session_user", "set", "setuser", "shutdown", "some", "statistics",
            "system_user", "table", "tablesample", "textsize", "then", "to", "top",
            "tran", "transaction", "trigger", "truncate", "try_convert", "tsequal",
            "union", "unique", "unpivot", "update", "updatetext", "use", "user", "values",
            "varying", "view", "waitfor", "when", "where", "while", "with", "writetext"
    );

    /** H2 2.x 官方关键字全量（P-立项-05 逐词核对 + 引擎实测）。 */
    public static final Set<String> H2_RESERVED = Set.of(
            "all", "and", "any", "array", "as", "asymmetric", "authorization", "between",
            "both", "case", "cast", "check", "constraint", "cross", "current_catalog",
            "current_date", "current_path", "current_role", "current_schema", "current_time",
            "current_timestamp", "current_user", "day", "default", "distinct", "else", "end",
            "except", "exists", "false", "fetch", "for", "foreign", "from", "full", "grant",
            "group", "groups", "having", "hour", "if", "ilike", "in", "inner", "intersect",
            "interval", "is", "join", "key", "leading", "left", "like", "limit", "localtime",
            "localtimestamp", "minus", "minute", "month", "natural", "not", "null", "offset",
            "on", "or", "order", "over", "partition", "primary", "qualify", "range",
            "regexp", "right", "row", "rownum", "rows", "second", "select", "session_user",
            "set", "some", "symmetric", "system_user", "table", "to", "top", "trailing",
            "true", "uescape", "union", "unique", "unknown", "user", "using", "value",
            "values", "when", "where", "window", "with", "year"
    );

    /** DM8 官方附录 1「带 * 系统保留字」全量（P-立项-05 逐词核对）。 */
    public static final Set<String> DM_RESERVED = Set.of(
            "absolute", "abstract", "add", "admin", "all", "alter", "and", "any", "array",
            "arraylen", "as", "asc", "assign", "audit", "auto_increment",
            "begin", "between", "bigdatediff", "binary", "bool", "both", "break", "bstring",
            "by", "byte",
            "call", "case", "cast", "char", "check", "cluster", "clusterbtr", "collation",
            "column", "comment", "commit", "commitwork", "connect", "connect_by_root",
            "const", "constraint", "contains", "context", "continue", "convert", "create",
            "cross", "crypto", "cube", "current", "current_date", "current_timestamp",
            "dateadd", "datediff", "datepart", "dbtimezone", "declare", "decode", "default",
            "delete", "desc", "dimension", "disable", "diskspace", "distance", "distinct",
            "distributed", "do", "domain", "double", "drop",
            "efconstruction", "else", "elseif", "elsif", "enable", "end", "equ", "exchange",
            "exec", "execute", "exists", "exit", "explain", "extern", "extract",
            "fetch", "final", "finally", "first", "flashback", "float", "for", "foreign",
            "from", "full", "fully", "function",
            "get", "goto", "grant", "group", "grouping",
            "having",
            "identity", "if", "ifnull", "immediate", "in", "index", "inner", "inline",
            "insert", "int", "intersect", "interval", "into", "is",
            "join", "json_arrayagg", "json_table", "json_transform",
            "keep",
            "large", "leading", "left", "less", "lexer", "like", "list", "lnnvl",
            "localtimestamp", "loop",
            "member", "minus", "model", "multiset",
            "natural", "new", "next", "nocopy", "nocycle", "nosort", "not", "null",
            "object", "of", "on", "optimize", "or", "order", "out", "over", "overlay",
            "override",
            "parameters", "partition", "pendant", "percent", "pipe", "primary", "print",
            "prior", "private", "privileges", "procedure", "protected", "public",
            "raise", "record", "ref", "reference", "references", "referencing",
            "regexp_split_to_table", "relative", "replicate", "return", "returning",
            "reverse", "revoke", "right", "rollback", "rollup", "row", "rownum", "rows",
            "savepoint", "sbyte", "schema", "section", "select", "sessiontimezone", "set",
            "sets", "short", "some", "sql_calc_found_rows", "static", "struct",
            "subpartition", "switch", "synonym", "sysdate", "systimestamp",
            "table", "than", "timestampadd", "timestampdiff", "to", "top", "to_vector",
            "trailing", "treat", "trigger", "trim", "truncate", "try", "typedef", "typeof",
            "uint", "ulong", "union", "unique", "until", "update", "user", "ushort", "using",
            "values", "varray", "verify", "versions", "view", "virtual", "void",
            "when", "whenever", "where", "while", "window", "with", "within", "without",
            "xml", "xmlagg", "xmlattributes", "xmlcast", "xmlelement", "xmlnamespaces",
            "xmlparse", "xmlquery", "xmlserialize", "xmltable"
    );

    /**
     * KingbaseES V9 官方保留字全量（P-立项-05 逐词核对；PG 兼容模式）。
     *
     * <p>PG 兼容基线（含 freeze/isnull/similar）+ 金仓特有保留字：auto_increment/collation/ilike/minus/
     * nocycle/nvl/nvl2/online/return/rowid/rownum/synonym/systimestamp/top/verbose/connect_by_* 等——
     * 证伪原「≈PG 集合」近似（rowid/rownum/top 等在金仓裸用为列名会语法错误）。</p>
     *
     * <p>T-XcDialects3 券 05 补 {@code enckey}（118 词）：引擎 {@code pg_get_keywords()} 标 R，列引用
     * {@code SELECT enckey FROM …} 报 {@code kb: syntax error at or near "enckey"}（KingbaseES V009R001C010
     * 真机实测 2026-10-29）；官方 V8/V9 文档表标「非保留」，以引擎为准。已知分歧：别名位
     * {@code SELECT 1 AS enckey} 实测通过——收录后该形态多一对引号，属「宁可多包」口径的可接受多包。</p>
     */
    public static final Set<String> KINGBASE_RESERVED = Set.of(
            "all", "analyse", "analyze", "and", "any", "array", "as", "asc", "asymmetric",
            "authorization", "auto_increment", "binary", "both", "case", "cast", "check",
            "collate", "collation", "column", "concurrently", "connect",
            "connect_by_iscycle", "connect_by_isleaf", "connect_by_root", "constraint",
            "create", "cross", "current_catalog", "current_date", "current_role",
            "current_schema", "current_time", "current_timestamp", "current_user",
            "default", "deferrable", "desc", "distinct", "do", "else", "enckey", "end",
            "except", "false", "fetch", "for", "foreign", "freeze", "from", "full", "grant", "group",
            "having", "ilike", "in", "initially", "inner", "intersect", "into", "is",
            "isnull", "join", "lateral", "leading", "left", "like", "limit", "localtime",
            "localtimestamp", "minus", "natural", "nocycle", "not", "notnull", "null",
            "nvl", "nvl2", "offset", "on", "online", "only", "or", "order", "outer",
            "overlaps", "placing", "primary", "references", "return", "returning", "right",
            "rowid", "rownum", "select", "session_user", "similar", "some", "symmetric",
            "synonym", "sys_connect_by_path", "systimestamp", "table", "tablesample",
            "then", "to", "top", "trailing", "true", "union", "unique", "user", "using",
            "variadic", "verbose", "when", "where", "window", "with"
    );

    /**
     * MariaDB 官方保留字全量（P-立项-05 逐词核对）。
     *
     * <p>= MySQL 8.0 保留字基线（MariaDB 同标保留的部分）+ MariaDB 特有保留字。
     */
    public static final Set<String> MARIA_RESERVED = union(MYSQL_RESERVED, Set.of(
            "binary", "current_role", "cursor", "delete_domain_id", "do_domain_ids",
            "except", "general", "ignore_domain_ids", "ignore_server_ids", "intersect",
            "master_heartbeat_period", "offset", "page_checksum", "parse_vcol_expr",
            "portion", "ref_system_id", "returning", "row_number", "slow",
            "stats_auto_recalc", "stats_persistent", "stats_sample_pages", "vector"
    ));

    /** openGauss 保留字表：`pg_get_keywords()` 的 `R` 类（真机实测 93 词）。 */
    private static final Set<String> OPENGAUSS_KEYWORDS_RESERVED = Set.of(
            "all", "analyse", "analyze", "and", "any", "array", "as", "asc", "asymmetric", "authid",
            "both", "buckets", "case", "cast", "check", "collate", "column", "constraint", "create",
            "current_catalog", "current_date", "current_role", "current_time", "current_timestamp",
            "current_user", "default", "deferrable", "desc", "distinct", "do", "else", "end", "except",
            "excluded", "false", "fetch", "for", "foreign", "from", "grant", "group", "groupparent",
            "having", "in", "initially", "intersect", "into", "is", "leading", "less", "limit",
            "localtime", "localtimestamp", "maxvalue", "minus", "modify", "nocycle", "not", "null",
            "offset", "on", "only", "or", "order", "performance", "placing", "primary", "procedure",
            "references", "reject", "returning", "rownum", "select", "session_user", "shrink", "some",
            "symmetric", "sysdate", "table", "then", "to", "trailing", "true", "union", "unique",
            "user", "using", "variadic", "verify", "when", "where", "window", "with"
    );

    /** openGauss 保留字表：`pg_get_keywords()` 的 `T` 类（保留但可作函数/类型名，真机实测 27 词）。 */
    private static final Set<String> OPENGAUSS_KEYWORDS_TYPE_RESERVED = Set.of(
            "authorization", "binary", "collation", "compact", "concurrently", "cross", "csn",
            "current_schema", "deltamerge", "freeze", "full", "hdfsdirectory", "ilike", "inner",
            "join", "left", "like", "natural", "notnull", "outer", "overlaps", "recyclebin", "right",
            "similar", "tablesample", "timecapsule", "verbose"
    );

    /** openGauss 保留字表：`pg_get_keywords()` 的 `C` 类（非保留但不能作函数/类型名，真机实测 63 词）。 */
    private static final Set<String> OPENGAUSS_KEYWORDS_COL_NAME_RESTRICTED = Set.of(
            "between", "bigint", "binary_double", "binary_integer", "bit", "boolean", "bucketcnt",
            "byteawithoutorder", "byteawithoutorderwithequal", "char", "character", "coalesce",
            "date", "dec", "decimal", "decode", "exists", "extract", "float", "greatest", "grouping",
            "inout", "int", "integer", "interval", "least", "national", "nchar", "none", "nullif",
            "number", "numeric", "nvarchar", "nvarchar2", "nvl", "out", "overlay", "position",
            "precision", "real", "row", "setof", "smalldatetime", "smallint", "substring", "time",
            "timestamp", "timestampdiff", "tinyint", "treat", "trim", "values", "varchar", "varchar2",
            "xmlattributes", "xmlconcat", "xmlelement", "xmlexists", "xmlforest", "xmlparse", "xmlpi",
            "xmlroot", "xmlserialize"
    );

    /**
     * openGauss 保留字全量（T-GaussDbDialect 券 01）。
     *
     * <p>来源<b>两档并集</b>，缺一不可——两个来源对「保留」的定义不同，任取其一都会漏：
     * <ul>
     *  <li><b>真机引擎级</b>：openGauss 5.0.0（DBX 只读通道，2026-10-29）{@code SELECT * FROM pg_get_keywords()}
     *      共 653 词，其中 {@code R}=93 / {@code T}=27 / {@code C}=63（{@code U}=470 不取）。{@code R}/{@code T}/{@code C}
     *      三类全部纳入：列名/别名判定下 {@code T} 类实测可裸用（{@code SELECT 1 AS binary, 2 AS notnull, 3 AS ilike}
     *      → 成功），但按本文件既有口径「宁可多包、不漏包」，多引用一次是幂等安全的，漏引用则是语法错误；</li>
     *  <li><b>官方文档级</b>：openGauss 官方《关键字》页「保留」列。相较引擎 {@code R} 类，该页另有
     *      {@code imcstored} / {@code self} / {@code unimcstored} 三词（{@code groupparent} 已在 {@code R} 类内），一并纳入。</li>
     * </ul></p>
     *
     * <p><b>不要</b>用 {@link #PG_RESERVED} 顶替（本表的前身就是这么干的，是缺陷）：PG 官方附录与 openGauss
     * 双向差异大——openGauss 额外保留 {@code rownum}/{@code minus}/{@code modify}/{@code nocycle}/{@code performance}/
     * {@code shrink}/{@code verify}/{@code sysdate}/{@code excluded}/{@code buckets}/{@code authid}/{@code less}/
     * {@code maxvalue}/{@code groupparent}/{@code reject}/{@code procedure}/{@code is}（17 词）等；PG 保留而
     * openGauss {@code R} 类不含的有 {@code authorization}/{@code binary}/{@code concurrently}/{@code freeze}/
     * {@code isnull}/{@code lateral}/{@code notnull}/{@code similar}（8 词，本表仍<b>保留</b>——多包无害）。</p>
     *
     * <p>真机依据：{@code SELECT 1 AS rownum, 2 AS minus, 3 AS modify} → {@code ROWNUM cannot be used as an alias}；
     * 逐词核对见 {@code DialectDocGoldenTest} 的黄金清单（与实现两侧独立维护）。</p>
     */
    public static final Set<String> OPENGAUSS_RESERVED = union(
            union(union(OPENGAUSS_KEYWORDS_RESERVED, OPENGAUSS_KEYWORDS_TYPE_RESERVED),
                    OPENGAUSS_KEYWORDS_COL_NAME_RESTRICTED),
            Set.of("imcstored", "self", "unimcstored"));

    /**
     * GaussDB（华为商用版）保留字增量（T-GaussDbDialect 券 01）。
     *
     * <p>GaussDB 官方《关键字》页「保留」列相较 openGauss 独有 12 词，其中 {@code maxvalue} 已在 openGauss
     * {@code R} 类内，故本表增量为 11 词。openGauss 独有而 GaussDB 未列的 4 词
     * （{@code groupparent} / {@code imcstored} / {@code self} / {@code unimcstored}）<b>不从基线剔除</b>——多包无害。</p>
     *
     * <p>证据级别：<b>官方文档级</b>（华为云 GaussDB 集中式 V2.0-10.x《关键字》）。GaussDB 商用版无公开
     * 容器镜像，无真机；与 openGauss 同源（{@code server_version} 基线 9.2.4），故以真机实测的 openGauss
     * 全集为基线 + 文档增量，是本环境下能给出的最强证据形态。</p>
     */
    public static final Set<String> GAUSSDB_RESERVED = union(OPENGAUSS_RESERVED, Set.of(
            "crossbucket", "dbtimezone", "fenced", "force", "match", "offline", "online", "priorer",
            "regexp", "rlike", "sessiontimezone"
    ));

    /**
     * 瀚高（HighGo DB）保留字全量（T-XcDialects3 券 01）：三源并集 <b>153 词</b>。
     * <ul>
     *  <li><b>官方文档级</b>：瀚高官方 V4.5 手册附录 2「保留字」150 词（V9 在线文档附录页 404 未取到）；</li>
     *  <li><b>{@link #PG_RESERVED} 基线</b>（86 词）：与瀚高表双向有差集——瀚高表多 65 词（{@code between}/
     *      {@code bigint}/{@code int}/{@code varchar}/{@code timestamp}/{@code coalesce}/{@code trim} 等类型名与
     *      函数名，照抄 PG 表会漏包），PG 侧多 1 词（{@code procedure}），故必须并集；</li>
     *  <li><b>引擎实测 R 类</b>（社区镜像 HighGo V6.0.1 / PostgreSQL 12.3 内核，{@code pg_get_keywords()}
     *      2026-10-30 探针）：比前两源多 {@code flashback} / {@code sysdate} 两词，一并纳入。</li>
     * </ul>
     *
     * <p>引擎读数的如实登记（不因多包而隐藏）：
     * <ul>
     *  <li>{@code values} 在 V6.0.1 引擎实测是<b>非保留</b>（可裸用作列名）——本表按「宁可多包」仍收录
     *      （官方 V4.5 附录 2 在册、引用幂等安全：包裹后在本方言仍合法）；</li>
     *  <li>{@code enckey} <b>不是</b>瀚高关键字（引擎 {@code pg_get_keywords()} 无此词——那是金仓的），故不在本表。</li>
     * </ul></p>
     */
    public static final Set<String> HIGHGO_RESERVED = Set.of(
            "all", "analyse", "analyze", "and", "any", "array", "as",
            "asc", "asymmetric", "authorization", "between", "bigint", "binary", "bit",
            "boolean", "both", "case", "cast", "char", "character", "check",
            "coalesce", "collate", "collation", "column", "concurrently", "constraint", "create",
            "cross", "current_catalog", "current_date", "current_role", "current_schema", "current_time", "current_timestamp",
            "current_user", "dec", "decimal", "default", "deferrable", "desc", "distinct",
            "do", "else", "end", "except", "exists", "extract", "false",
            "fetch", "flashback", "float", "for", "foreign", "freeze", "from",
            "full", "grant", "greatest", "group", "grouping", "having", "ilike",
            "in", "initially", "inner", "inout", "int", "integer", "intersect",
            "interval", "into", "is", "isnull", "join", "lateral", "leading",
            "least", "left", "like", "limit", "localtime", "localtimestamp", "national",
            "natural", "nchar", "none", "not", "notnull", "null", "nullif",
            "numeric", "offset", "on", "only", "or", "order", "out",
            "outer", "overlaps", "overlay", "placing", "position", "precision", "primary",
            "procedure", "real", "references", "returning", "right", "row", "select",
            "session_user", "setof", "similar", "smallint", "some", "substring", "symmetric",
            "sysdate", "table", "tablesample", "then", "time", "timestamp", "to",
            "trailing", "treat", "trim", "true", "union", "unique", "user",
            "using", "values", "varchar", "variadic", "verbose", "when", "where",
            "window", "with", "xmlattributes", "xmlconcat", "xmlelement", "xmlexists", "xmlforest",
            "xmlnamespaces", "xmlparse", "xmlpi", "xmlroot", "xmlserialize", "xmltable"
    );

    /**
     * 海量（Vastbase G100）保留字全量（T-XcDialects3 券 02）：官方四份兼容模式关键字表
     * 「保留」类的<b>四模式并集</b> ∪ {@link #PG_RESERVED}，共 <b>201 词</b>。
     *
     * <p>官方四表（docs.vastdata.com.cn，PG 163 / Oracle 172 / MSSQL 174 / MySQL 差分 22）的
     * 「保留」类逐表机读后取并集。<b>必须取四模式并集而不是只取 PG 模式表</b>：兼容模式是实例级、
     * 初始化后不可改（{@code vb_initdb --dbcompatibility}，默认 <b>A（Oracle）</b>），同一 URL 无法判定
     * 实例落在哪个模式——A 模式比 PG 模式多 {@code excluded}/{@code online}/{@code long} 等 <b>14 个</b>保留词，
     * 只取 PG 表会在默认实例上漏包。</p>
     *
     * <p>证据级别：<b>官方文档级</b>（Vastbase G100 V3.0.9 关键字四表）。无真机（DBX 在册无 vastbase
     * 实例；真机部署在途）——官方自身建议用 {@code pg_get_keywords()} 机读校准，真机到位后按引擎读数订正。</p>
     */
    public static final Set<String> VASTBASE_RESERVED = Set.of(
            "add", "against", "all", "analyse", "analyze", "and", "any",
            "apply", "array", "as", "asc", "asymmetric", "authorization", "auto_increment",
            "begin", "binary", "body", "both", "buckets", "by", "call",
            "case", "cast", "catch", "check", "checktable", "close", "collate",
            "collation", "column", "compact", "concurrently", "connect_by_root", "constraint", "create",
            "cross", "csn", "current_catalog", "current_date", "current_role", "current_schema", "current_time",
            "current_timestamp", "current_user", "dbcc", "dbtimezone", "declare", "default", "deferrable",
            "delete", "deltamerge", "desc", "diagnostics", "distinct", "do", "drop",
            "else", "elseif", "elsif", "end", "except", "exception", "excluded",
            "exec", "execute", "exit", "failed", "false", "fenced", "fetch",
            "flashback", "for", "forall", "foreach", "foreign", "freeze", "from",
            "full", "function", "get", "goto", "grant", "group", "having",
            "hdfsdirectory", "identity", "if", "ignore", "ilike", "image", "in",
            "index", "initially", "inner", "insert", "intersect", "into", "is",
            "isnull", "join", "json_mergepatch", "json_table", "last_day", "lateral", "leading",
            "left", "less", "like", "limit", "localtime", "localtimestamp", "logical_data",
            "logical_file", "long", "loop", "maxvalue", "minus", "modify", "move",
            "natural", "nocycle", "not", "notnull", "null", "of", "off",
            "offset", "on", "online", "only", "open", "openxml", "or",
            "order", "out", "outer", "output", "overlaps", "percent", "performance",
            "phtsical_only", "physical_only", "pivot", "placing", "primary", "prior", "procedure",
            "raise", "recyclebin", "redisanyvalue", "ref", "references", "reject", "returining",
            "return", "returning", "right", "rownum", "sample", "select", "session_user",
            "sessiontimezone", "set", "shrink", "similar", "some", "sp_executesql", "stdin",
            "stdout", "strict", "succeed", "symmetric", "sysdate", "system_function", "systimestamp",
            "table", "tablesample", "then", "ties", "timecapsule", "to", "top",
            "trailing", "true", "try", "union", "unique", "unpivot", "update",
            "updating", "user", "using", "varbinary", "variadic", "verbose", "verify",
            "when", "where", "while", "window", "with"
    );

    /** 集合并集（不可变）。 */
    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> result = new LinkedHashSet<>(a);
        result.addAll(b);
        return Collections.unmodifiableSet(result);
    }
}
