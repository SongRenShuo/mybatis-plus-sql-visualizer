package cn.song.mybatisplus.visualizer.sql

/**
 * 数据库保留字集合（T-M23.4）。
 *
 * **按方言判定**（用户确认：数据库方言有多种——判定与符号都随方言走）：
 * 每方言一个独立保留字集合（小写），`Dialect.quoteIfKeyword` 只查**本方言**集合——
 * 例如 `number` 是 Oracle 保留字但 MySQL 合法：MySQL 场景不包裹、Oracle 场景包裹。
 *
 * 集合构成（尽量完整，来源为各数据库官方关键字文档）：
 *  - [MYSQL_RESERVED]：MySQL 8.0 官方保留字全量（dev.mysql.com/doc/refman/8.0/en/keywords.html）
 *  - [SQLITE_RESERVED]：SQLite 官方关键字全量（sqlite.org/lang_keywords.html）
 *  - [PG_RESERVED]：PostgreSQL 官方保留字全量（postgresql.org/docs/current/sql-keywords-appendix.html；
 *    P-立项-05 补齐「reserved (can be function or type name)」类别 7 词——AUTHORIZATION/BINARY/
 *    CONCURRENTLY/FREEZE/ISNULL/NOTNULL/SIMILAR 同样不能裸用作列名，原集合缺漏已由金仓官方
 *    保留字表交叉验证（help.kingbase.com.cn V9 保留字章节同标「保留」））
 *  - [ORACLE_RESERVED]：Oracle 官方保留字全量
 *  - [SQLSERVER_RESERVED]：SQL Server 官方保留字全量（learn.microsoft.com 保留字文档）
 *  - [H2_RESERVED]：H2 2.x 官方关键字全量（h2database.com/html/keywords.html——Regular 模式
 *    always-keyword + context-sensitive + mode-sensitive 全量；P-立项-05 引擎实测核对）
 *  - [DM_RESERVED]：DM8 官方附录 1「带 * 系统保留字」全量（eco.dameng.com
 *    /document/dm/zh-cn/pm/sql-appendix.html；P-立项-05 逐词核对）
 *  - [KINGBASE_RESERVED]：KingbaseES V9 官方保留字全量（bbs.kingbase.com.cn V9 手册
 *    《SQL保留字和关键字》章节 22，分类为「保留」的词；P-立项-05 逐词核对）
 *  - [MARIA_RESERVED]：MariaDB 官方保留字全量（mariadb.com/kb/en/reserved-words/，
 *    = MySQL 8.0 保留字 + MariaDB 增量；P-立项-05 逐词核对）
 *  - [OPENGAUSS_RESERVED]：openGauss 保留字全量（真机 `pg_get_keywords()` 的 R/T/C 三类 183 词
 *    ∪ 官方《关键字》页保留列独有词；T-GaussDbDialect 券 01）——**不再复用 [PG_RESERVED]**
 *  - [GAUSSDB_RESERVED]：[OPENGAUSS_RESERVED] + GaussDB 官方《关键字》页保留列独有 11 词
 *    （`maxvalue` 已在 openGauss R 类内；证据为官方文档级，GaussDB 商用版无真机）
 *
 * 近似归组已消除（P-立项-05）：H2 / DM / KingBase / MariaDB 均改为**本方言官方保留字集合**，
 * 不再近似复用其他方言的集合（原近似在 H2（key/value/values/day/year 等漏包）与
 * DM（top/section/domain 等漏包 + number/size 错包）上经真实引擎/官方文档证伪）。
 *
 * 范围边界：
 *  - 各方言**非保留字**（MySQL 的 `name`/`status`/`type`、PG 的 `value` 等——不包裹不报错）
 *    **不包含**，避免高频字段全量加引号的输出噪音；
 *  - 「宁可多包、不漏包」仅限**本方言保留字**（引用幂等安全——包裹后在本方言合法）。
 *
 * 判定：大小写不敏感（`identifier.lowercase() in 集合`）。
 * 纯函数，无 IDE 依赖，可独立单测。
 */

/** MySQL 8.0 官方保留字全量。 */
val MYSQL_RESERVED: Set<String> = setOf(
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
    "with", "write", "xor", "year_month", "zerofill",
)

/** SQLite 官方关键字全量。 */
val SQLITE_RESERVED: Set<String> = setOf(
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
    "virtual", "when", "where", "window", "with", "without",
)

/** PostgreSQL 官方保留字全量。P-立项-05：补齐「reserved (can be function or type name)」7 词。 */
val PG_RESERVED: Set<String> = setOf(
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
    "where", "window", "with",
)

/** Oracle 官方保留字全量。 */
val ORACLE_RESERVED: Set<String> = setOf(
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
    "view", "whenever", "where", "with",
)

/** SQL Server 官方保留字全量。 */
val SQLSERVER_RESERVED: Set<String> = setOf(
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
    "varying", "view", "waitfor", "when", "where", "while", "with", "writetext",
)

/**
 * H2 2.x 官方关键字全量（P-立项-05：h2database.com/html/keywords.html 逐词核对 +
 * H2 2.3.232 引擎实测）。
 *
 * 构成：官方表中 Regular 模式为关键字（+）的词 + context-sensitive（CS）+ mode-sensitive
 * 中 Regular 模式为关键字的词（LIMIT/MINUS/TOP）。引擎实测证伪原「≈PG 集合」近似：
 * H2 保留而 PG 集合没有的 `key`/`value`/`values`/`day`/`year`/`interval`/`row`/`if`/
 * `minus`/`qualify`/`rownum`/`top`/`unknown`/`uescape` 等裸用作列名直接语法错误（漏包）。
 */
val H2_RESERVED: Set<String> = setOf(
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
    "values", "when", "where", "window", "with", "year",
)

/**
 * DM8 官方附录 1「带 * 系统保留字」全量（P-立项-05：eco.dameng.com
 * /document/dm/zh-cn/pm/sql-appendix.html 逐词核对）。
 *
 * 与原「≈Oracle 集合」近似的双向差异（官方文档证实）：
 *  - DM 保留而 Oracle 集合没有（漏包风险）：binary/domain/double/do/identity/if/ifnull/
 *    index/int/array/exec/execute/function/get/large/list/member/model/new/next/object/
 *    out/over/partition/print/record/ref/return/section/sets/short/struct/switch/top/
 *    trim/extract 等；
 *  - Oracle 保留而 DM 仅关键字（错包风险，引用后大小写敏感易失配）：number/size/level/
 *    raw/varchar/date 等——不再包含，裸用折叠大写后合法。
 * 注：ROWID/TRXID/VERSIONS_* 等在 DM 中即使加双引号也不能作列名（官方附录注记），
 * 插件不输出此类标识符（实体列名不会是系统伪列），不进集合。
 */
val DM_RESERVED: Set<String> = setOf(
    // A
    "absolute", "abstract", "add", "admin", "all", "alter", "and", "any", "array",
    "arraylen", "as", "asc", "assign", "audit", "auto_increment",
    // B
    "begin", "between", "bigdatediff", "binary", "bool", "both", "break", "bstring",
    "by", "byte",
    // C
    "call", "case", "cast", "char", "check", "cluster", "clusterbtr", "collation",
    "column", "comment", "commit", "commitwork", "connect", "connect_by_root",
    "const", "constraint", "contains", "context", "continue", "convert", "create",
    "cross", "crypto", "cube", "current", "current_date", "current_timestamp",
    // D
    "dateadd", "datediff", "datepart", "dbtimezone", "declare", "decode", "default",
    "delete", "desc", "dimension", "disable", "diskspace", "distance", "distinct",
    "distributed", "do", "domain", "double", "drop",
    // E
    "efconstruction", "else", "elseif", "elsif", "enable", "end", "equ", "exchange",
    "exec", "execute", "exists", "exit", "explain", "extern", "extract",
    // F
    "fetch", "final", "finally", "first", "flashback", "float", "for", "foreign",
    "from", "full", "fully", "function",
    // G
    "get", "goto", "grant", "group", "grouping",
    // H
    "having",
    // I
    "identity", "if", "ifnull", "immediate", "in", "index", "inner", "inline",
    "insert", "int", "intersect", "interval", "into", "is",
    // J
    "join", "json_arrayagg", "json_table", "json_transform",
    // K
    "keep",
    // L
    "large", "leading", "left", "less", "lexer", "like", "list", "lnnvl",
    "localtimestamp", "loop",
    // M
    "member", "minus", "model", "multiset",
    // N
    "natural", "new", "next", "nocopy", "nocycle", "nosort", "not", "null",
    // O
    "object", "of", "on", "optimize", "or", "order", "out", "over", "overlay",
    "override",
    // P
    "parameters", "partition", "pendant", "percent", "pipe", "primary", "print",
    "prior", "private", "privileges", "procedure", "protected", "public",
    // R
    "raise", "record", "ref", "reference", "references", "referencing",
    "regexp_split_to_table", "relative", "replicate", "return", "returning",
    "reverse", "revoke", "right", "rollback", "rollup", "row", "rownum", "rows",
    // S
    "savepoint", "sbyte", "schema", "section", "select", "sessiontimezone", "set",
    "sets", "short", "some", "sql_calc_found_rows", "static", "struct",
    "subpartition", "switch", "synonym", "sysdate", "systimestamp",
    // T
    "table", "than", "timestampadd", "timestampdiff", "to", "top", "to_vector",
    "trailing", "treat", "trigger", "trim", "truncate", "try", "typedef", "typeof",
    // U
    "uint", "ulong", "union", "unique", "until", "update", "user", "ushort", "using",
    // V
    "values", "varray", "verify", "versions", "view", "virtual", "void",
    // W
    "when", "whenever", "where", "while", "window", "with", "within", "without",
    // X
    "xml", "xmlagg", "xmlattributes", "xmlcast", "xmlelement", "xmlnamespaces",
    "xmlparse", "xmlquery", "xmlserialize", "xmltable",
)

/**
 * KingbaseES V9 官方保留字全量（P-立项-05：bbs.kingbase.com.cn V9 手册《SQL保留字和关键字》
 * 章节分类为「保留」的词逐词核对；PG 兼容模式）。
 *
 * = PG 保留字基线（含金仓同标「保留」的 authorization/binary/concurrently/freeze/isnull/
 * notnull/similar）+ 金仓特有保留字：auto_increment/collation/ilike/minus/nocycle/nvl/nvl2/
 * online/return/rowid/rownum/synonym/systimestamp/top/verbose/connect_by_* 等——
 * 证伪原「≈PG 集合」近似（rowid/rownum/top 等在金仓裸用为列名会语法错误）。
 */
val KINGBASE_RESERVED: Set<String> = setOf(
    "all", "analyse", "analyze", "and", "any", "array", "as", "asc", "asymmetric",
    "authorization", "auto_increment", "binary", "both", "case", "cast", "check",
    "collate", "collation", "column", "concurrently", "connect",
    "connect_by_iscycle", "connect_by_isleaf", "connect_by_root", "constraint",
    "create", "cross", "current_catalog", "current_date", "current_role",
    "current_schema", "current_time", "current_timestamp", "current_user",
    "default", "deferrable", "desc", "distinct", "do", "else", "end", "except",
    "false", "fetch", "for", "foreign", "freeze", "from", "full", "grant", "group",
    "having", "ilike", "in", "initially", "inner", "intersect", "into", "is",
    "isnull", "join", "lateral", "leading", "left", "like", "limit", "localtime",
    "localtimestamp", "minus", "natural", "nocycle", "not", "notnull", "null",
    "nvl", "nvl2", "offset", "on", "online", "only", "or", "order", "outer",
    "overlaps", "placing", "primary", "references", "return", "returning", "right",
    "rowid", "rownum", "select", "session_user", "similar", "some", "symmetric",
    "synonym", "sys_connect_by_path", "systimestamp", "table", "tablesample",
    "then", "to", "top", "trailing", "true", "union", "unique", "user", "using",
    "variadic", "verbose", "when", "where", "window", "with",
)

/**
 * MariaDB 官方保留字全量（P-立项-05：mariadb.com/kb/en/reserved-words/ 逐词核对）。
 *
 * = MySQL 8.0 保留字基线（MariaDB 同标保留的部分）+ MariaDB 特有保留字——
 * 证伪原「= MySQL 集合」近似：MariaDB 因实现 INTERSECT/EXCEPT 集合运算等，
 * `except`/`intersect`/`binary`/`offset`/`returning`/`row_number` 等为 MariaDB 保留字
 * 而 MySQL 8.0 非保留（裸用作列名在 MariaDB 直接语法错误）。
 * 注：MySQL 8.0 保留而 MariaDB 非保留的词（cube/cume_dist/lag/of 等）不剔除——
 * 多包无害（引用后 MariaDB 合法），保持与 MYSQL_RESERVED 的复用基线。
 */
val MARIA_RESERVED: Set<String> = MYSQL_RESERVED + setOf(
    "binary", "current_role", "cursor", "delete_domain_id", "do_domain_ids",
    "except", "general", "ignore_domain_ids", "ignore_server_ids", "intersect",
    "master_heartbeat_period", "offset", "page_checksum", "parse_vcol_expr",
    "portion", "ref_system_id", "returning", "row_number", "slow",
    "stats_auto_recalc", "stats_persistent", "stats_sample_pages", "vector",
)

/** openGauss 保留字表：`pg_get_keywords()` 的 `R` 类（真机实测 93 词）。 */
private val OPENGAUSS_KEYWORDS_RESERVED: Set<String> = setOf(
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
    "user", "using", "variadic", "verify", "when", "where", "window", "with",
)

/** openGauss 保留字表：`pg_get_keywords()` 的 `T` 类（保留但可作函数/类型名，真机实测 27 词）。 */
private val OPENGAUSS_KEYWORDS_TYPE_RESERVED: Set<String> = setOf(
    "authorization", "binary", "collation", "compact", "concurrently", "cross", "csn",
    "current_schema", "deltamerge", "freeze", "full", "hdfsdirectory", "ilike", "inner",
    "join", "left", "like", "natural", "notnull", "outer", "overlaps", "recyclebin", "right",
    "similar", "tablesample", "timecapsule", "verbose",
)

/** openGauss 保留字表：`pg_get_keywords()` 的 `C` 类（非保留但不能作函数/类型名，真机实测 63 词）。 */
private val OPENGAUSS_KEYWORDS_COL_NAME_RESTRICTED: Set<String> = setOf(
    "between", "bigint", "binary_double", "binary_integer", "bit", "boolean", "bucketcnt",
    "byteawithoutorder", "byteawithoutorderwithequal", "char", "character", "coalesce",
    "date", "dec", "decimal", "decode", "exists", "extract", "float", "greatest", "grouping",
    "inout", "int", "integer", "interval", "least", "national", "nchar", "none", "nullif",
    "number", "numeric", "nvarchar", "nvarchar2", "nvl", "out", "overlay", "position",
    "precision", "real", "row", "setof", "smalldatetime", "smallint", "substring", "time",
    "timestamp", "timestampdiff", "tinyint", "treat", "trim", "values", "varchar", "varchar2",
    "xmlattributes", "xmlconcat", "xmlelement", "xmlexists", "xmlforest", "xmlparse", "xmlpi",
    "xmlroot", "xmlserialize",
)

/**
 * openGauss 保留字全量（T-GaussDbDialect 券 01）。
 *
 * 来源**两档并集**，缺一不可——两个来源对「保留」的定义不同，任取其一都会漏：
 *  - **真机引擎级**：openGauss 5.0.0（DBX 只读通道，2026-10-29）`SELECT * FROM pg_get_keywords()`
 *    共 653 词，其中 `R`=93 / `T`=27 / `C`=63（`U`=470 不取）。`R`/`T`/`C` 三类全部纳入：
 *    列名/别名判定下 `T` 类实测可裸用（`SELECT 1 AS binary, 2 AS notnull, 3 AS ilike` → 成功），
 *    但按本文件既有口径「宁可多包、不漏包」，多引用一次是幂等安全的，漏引用则是语法错误；
 *  - **官方文档级**：openGauss 官方《关键字》页「保留」列。相较引擎 `R` 类，该页另有
 *    `imcstored` / `self` / `unimcstored` 三词（`groupparent` 已在 `R` 类内），一并纳入。
 *
 * **不要**用 `PG_RESERVED` 顶替（本表的前身就是这么干的，是缺陷）：PG 官方附录与 openGauss
 * 双向差异大——openGauss 额外保留 `rownum`/`minus`/`modify`/`nocycle`/`performance`/
 * `shrink`/`verify`/`sysdate`/`excluded`/`buckets`/`authid`/`less`/`maxvalue`/`groupparent`/
 * `reject`/`procedure`/`is`（17 词）等；PG 保留而 openGauss `R` 类不含的有
 * `authorization`/`binary`/`concurrently`/`freeze`/`isnull`/`lateral`/`notnull`/`similar`
 * （8 词，本表仍**保留**——多包无害）。
 *
 * 真机依据：`SELECT 1 AS rownum, 2 AS minus, 3 AS modify` → `ROWNUM cannot be used as an alias`；
 * 逐词核对见 `DialectDocGoldenTest` 的黄金清单（与实现两侧独立维护）。
 */
val OPENGAUSS_RESERVED: Set<String> = OPENGAUSS_KEYWORDS_RESERVED +
    OPENGAUSS_KEYWORDS_TYPE_RESERVED +
    OPENGAUSS_KEYWORDS_COL_NAME_RESTRICTED +
    setOf("imcstored", "self", "unimcstored")

/**
 * GaussDB（华为商用版）保留字增量（T-GaussDbDialect 券 01）。
 *
 * GaussDB 官方《关键字》页「保留」列相较 openGauss 独有 12 词，其中 `maxvalue` 已在 openGauss
 * `R` 类内，故本表增量为 11 词。openGauss 独有而 GaussDB 未列的 4 词
 * （`groupparent` / `imcstored` / `self` / `unimcstored`）**不从基线剔除**——多包无害。
 *
 * 证据级别：**官方文档级**（华为云 GaussDB 集中式 V2.0-10.x《关键字》）。GaussDB 商用版无公开
 * 容器镜像，无真机；与 openGauss 同源（`server_version` 基线 9.2.4），故以真机实测的 openGauss
 * 全集为基线 + 文档增量，是本环境下能给出的最强证据形态。
 */
val GAUSSDB_RESERVED: Set<String> = OPENGAUSS_RESERVED + setOf(
    "crossbucket", "dbtimezone", "fenced", "force", "match", "offline", "online", "priorer",
    "regexp", "rlike", "sessiontimezone",
)
