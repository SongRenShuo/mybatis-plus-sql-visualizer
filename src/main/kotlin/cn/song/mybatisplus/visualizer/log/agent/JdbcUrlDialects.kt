package cn.song.mybatisplus.visualizer.log.agent

/**
 * JDBC URL → 数据库显示名映射（T-AgentDialectLoop）。
 *
 * agent 事件携带 connect 时 URL，据此在面板角标展示目标库类型。映射独立于
 * [cn.song.mybatisplus.visualizer.sql.DialectRegistry]：角标是事实展示而非渲染配置，
 * 不能走 `byName` 的未知回退 MySQL（会把 GBASE/openGauss 等误标）。
 *
 * 纯函数、无状态；未知/畸形 URL 返回 null（不显示角标）。
 */
internal object JdbcUrlDialects {

    /** 子协议 → 显示名。键统一小写；匹配时按 URL 子协议小写比对。 */
    private val SUBPROTOCOL_NAMES: Map<String, String> = mapOf(
        "mysql" to "MySQL",
        "mariadb" to "MariaDB",
        "postgresql" to "PostgreSQL",
        "opengauss" to "openGauss",
        "oracle" to "Oracle",
        "sqlserver" to "SQL Server",
        "h2" to "H2",
        "sqlite" to "SQLite",
        "hsqldb" to "HSQL",
        "hsql" to "HSQL",
        "dm" to "DM（达梦）",
        "kingbase8" to "KingBase（人大金仓）",
        "kingbase" to "KingBase（人大金仓）",
        // T-DialectXcFamily（O-63）：gaussdb 原与 opengauss 同归 openGauss 角标（D2=A 一 object 两前缀）；
        // T-GaussDbDialect（券 04）起拆开——华为 GaussDB 官方驱动（gaussdbjdbc.jar / gsjdbc200.jar）
        // 的 URL 前缀是 jdbc:gaussdb，与 jdbc:opengauss 是两个产品线，角标应如实显示。
        "oceanbase" to "OceanBase",
        "gaussdb" to "GaussDB",
        // T-XcDialects3（券 03）：瀚高 jdbc:highgo: 与海量 jdbc:vastbase: / jdbc:dws:iam:
        //（cn.com.vastbase.Driver 的 parseURL 常量池，调研报告 §6.2）
        "highgo" to "HighGo",
        "vastbase" to "Vastbase",
        "dws" to "Vastbase",
        "gbasedbt-sqli" to "GBASE",
        "gbasedbt" to "GBASE",
        "gbase" to "GBASE",
    )

    /**
     * 从 JDBC URL 提取子协议并映射显示名。
     * 形态：`jdbc:<子协议>:...`（子协议大小写不敏感）；非 jdbc: 前缀/空 URL/未知子协议 → null。
     */
    fun displayName(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        if (!trimmed.startsWith("jdbc:", ignoreCase = true)) return null
        val rest = trimmed.substring(5)
        val subprotocol = rest.substringBefore(':').lowercase()
        if (subprotocol.isEmpty()) return null
        return SUBPROTOCOL_NAMES[subprotocol]
    }

    // ---------- R32 弱形态：url 解析真值充实角标 ----------

    /**
     * R32 弱形态：url 解析连接事实（库名@host:port）与方言显示名合成角标文案。
     *
     * 合成规则：连接事实与方言名都有 → `"方言 · 库名@host:port"`（方言在前保持既有阅读顺序）；
     * 只有其一输出其一；两者皆无（畸形 URL）→ null。端口全数字才带上（畸形端口丢弃）。
     * 凭据安全：解析走 [targetOf]（user/password 参数与 Oracle thin 登录前缀在解析期剔除），
     * query 串整体不进结果——凭据串绝不入标注。
     */
    fun badgeName(url: String?): String? {
        val dialect = displayName(url)
        val t = targetOf(url) ?: return dialect
        val fact = buildString {
            if (t.dbName != null) append(t.dbName)
            if (t.host != null) {
                if (isNotEmpty()) append('@')
                append(t.host)
                if (t.port != null) append(':').append(t.port)
            }
        }
        if (fact.isEmpty()) return dialect
        return if (dialect == null) fact else "$dialect · $fact"
    }

    /**
     * R32 弱形态：从 JDBC URL 解析连接事实（库名/host/port）。
     *
     * 覆盖形态：
     *  - 通用 authority 形态 `jdbc:<子协议>://host[:port]/db[?query]`（MySQL/PG/DM/KingBase…）；
     *  - 分号参数形态 `jdbc:sqlserver://host[:port];databaseName=dbx;...`——库名**只认 databaseName=
     *    键**（与 execute/JdbcUrlDatabase 同口径），其余参数（含 user/password）一律不入结果；
     *  - Oracle thin 两种形态：service_name `...thin:[u/p]@//host:port/service` 与
     *    SID `...thin:[u/p]@host:port:SID`；TNS 长描述 `(DESCRIPTION=...)` 不猜（已知限制）。
     *
     * 凭据剔除：取**最后一个 `@`** 之后参与解析（内联 `user[:password]@` 与 thin 登录前缀一并丢弃），
     * query 串整体丢弃——凭据串绝不进入返回值。嵌入形态（`jdbc:h2:mem:...`、`jdbc:sqlite:file`）
     * 无 host 可标 → null（角标保持纯方言名）。
     *
     * 不复用 execute/JdbcUrlDatabase.nameFromUrl：其保守契约（Oracle SID 形态不猜）被预览会话库
     * 对齐消费（T-ConsoleDbContext），扩 Oracle 形态会改变预览对齐行为——R32 角标域独立解析，
     * 不越界改它。
     */
    fun targetOf(url: String?): JdbcUrlTarget? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        if (!trimmed.startsWith("jdbc:", ignoreCase = true)) return null
        val rest = trimmed.substring(5)
        val subprotocol = rest.substringBefore(':').lowercase()
        if (subprotocol.isEmpty()) return null
        val data = rest.substringAfter(':')
        return when (subprotocol) {
            "oracle" -> oracleTarget(data)
            else -> authorityTarget(data)
        }
    }

    /** 通用 authority 解析：`//[u[:p]@]host[:port]/db[?query]`，含分号参数形态的 authority 截断。 */
    private fun authorityTarget(data: String): JdbcUrlTarget? {
        var body = data
        var semiDb: String? = null
        val semi = data.indexOf(';')
        if (semi >= 0) {
            body = data.substring(0, semi)
            // 分号参数：库名只认 databaseName= 键（大小写不敏感），其余参数（含 user/password）不入结果
            data.substring(semi + 1).split(';').forEach { kv ->
                val eq = kv.indexOf('=')
                if (eq > 0 && kv.substring(0, eq).trim().equals("databaseName", ignoreCase = true)) {
                    semiDb = kv.substring(eq + 1).trim().takeIf { it.isNotEmpty() }
                }
            }
        }
        if (!body.startsWith("//")) return null // 嵌入形态（H2 mem/file、SQLite 文件）无 host 可标
        val after = body.substring(2)
        val authorityEnd = after.indexOfFirst { it == '/' || it == '?' }
        val rawAuthority = if (authorityEnd >= 0) after.substring(0, authorityEnd) else after
        // 凭据剔除（段内）：authority 里内联的 user[:password]@ 取最后一个 '@' 之后——
        // 只在 authority 段内削，不碰 '//' 前缀与 path
        val at = rawAuthority.lastIndexOf('@')
        val authority = if (at >= 0) rawAuthority.substring(at + 1) else rawAuthority
        var dbName = semiDb
        if (authorityEnd >= 0 && after[authorityEnd] == '/') {
            val seg = after.substring(authorityEnd + 1).substringBefore('?').substringBefore('/').trim()
            if (dbName == null && seg.isNotEmpty()) dbName = seg
        }
        val (host, port) = parseAuthority(authority)
        if (dbName == null && host == null) return null
        return JdbcUrlTarget(dbName, host, port)
    }

    /** Oracle thin 专项：`//host:port/service`（service_name）、`host:port:SID`；TNS 描述不猜。 */
    private fun oracleTarget(data: String): JdbcUrlTarget? {
        if (data.contains('(')) return null // TNS 长描述形态不猜（登记限制）
        // 凭据剔除：thin 登录前缀 `thin:[user[/password]]@` ——取最后一个 '@' 之后参与解析
        val at = data.lastIndexOf('@')
        val credFree = if (at >= 0) data.substring(at + 1) else data
        if (credFree.startsWith("//")) return authorityTarget(credFree) // service_name 形态同 authority 解析
        val parts = credFree.split(':')
        return when (parts.size) {
            3 -> JdbcUrlTarget( // host:port:SID
                dbName = parts[2].takeIf { it.isNotEmpty() },
                host = parts[0].takeIf { it.isNotEmpty() },
                port = parts[1].takeIf { it.isNotEmpty() && it.all(Char::isDigit) },
            )
            2 -> JdbcUrlTarget( // host:port（无 SID 段）
                dbName = null,
                host = parts[0].takeIf { it.isNotEmpty() },
                port = parts[1].takeIf { it.isNotEmpty() && it.all(Char::isDigit) },
            )
            else -> null // TNS 别名单段等：无可标注事实
        }
    }

    /** authority 拆 host/port：port 全数字才认（防误切畸形）；多冒号裸 IPv6 不标 host（防御）。 */
    private fun parseAuthority(authority: String): Pair<String?, String?> {
        if (authority.isEmpty()) return null to null
        val colon = authority.lastIndexOf(':')
        if (colon < 0) return authority to null
        val p = authority.substring(colon + 1)
        val host = authority.substring(0, colon)
        return if (p.isNotEmpty() && p.all(Char::isDigit)) {
            (host.takeIf { it.isNotEmpty() && !it.contains(':') }) to p
        } else {
            authority.takeIf { !it.contains(':') } to null
        }
    }
}

/** R32 弱形态：JDBC URL 解析出的连接事实（全部可标注字段；凭据永不入本模型）。 */
data class JdbcUrlTarget(
    /** 库名（url path 段 / SQL Server databaseName= 参数 / Oracle service_name 或 SID）。 */
    val dbName: String?,
    /** 主机（脱敏：只标注 host 本身；内联凭据段解析期剔除）。 */
    val host: String?,
    /** 端口（全数字才解析；缺省 null）。 */
    val port: String?,
)
