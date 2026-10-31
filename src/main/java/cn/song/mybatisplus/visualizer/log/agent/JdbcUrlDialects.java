package cn.song.mybatisplus.visualizer.log.agent;

import java.util.Locale;
import java.util.Map;

/**
 * JDBC URL → 数据库显示名映射（T-AgentDialectLoop）。
 *
 * agent 事件携带 connect 时 URL，据此在面板角标展示目标库类型。映射独立于
 * {@link cn.song.mybatisplus.visualizer.sql.DialectRegistry}：角标是事实展示而非渲染配置，
 * 不能走 byName 的未知回退 MySQL（会把 GBASE/openGauss 等误标）。
 *
 * 纯函数、无状态；未知/畸形 URL 返回 null（不显示角标）。
 */
public final class JdbcUrlDialects {

    private JdbcUrlDialects() {
    }

    /** 子协议 → 显示名。键统一小写；匹配时按 URL 子协议小写比对。 */
    private static final Map<String, String> SUBPROTOCOL_NAMES = Map.ofEntries(
        Map.entry("mysql", "MySQL"),
        Map.entry("mariadb", "MariaDB"),
        Map.entry("postgresql", "PostgreSQL"),
        Map.entry("opengauss", "openGauss"),
        Map.entry("oracle", "Oracle"),
        Map.entry("sqlserver", "SQL Server"),
        Map.entry("h2", "H2"),
        Map.entry("sqlite", "SQLite"),
        Map.entry("hsqldb", "HSQL"),
        Map.entry("hsql", "HSQL"),
        Map.entry("dm", "DM（达梦）"),
        Map.entry("kingbase8", "KingBase（人大金仓）"),
        Map.entry("kingbase", "KingBase（人大金仓）"),
        // T-DialectXcFamily（O-63）：gaussdb 原与 opengauss 同归 openGauss 角标（D2=A 一 object 两前缀）；
        // T-GaussDbDialect（券 04）起拆开——华为 GaussDB 官方驱动（gaussdbjdbc.jar / gsjdbc200.jar）
        // 的 URL 前缀是 jdbc:gaussdb，与 jdbc:opengauss 是两个产品线，角标应如实显示。
        Map.entry("oceanbase", "OceanBase"),
        Map.entry("gaussdb", "GaussDB"),
        // T-XcDialects3（券 03）：瀚高 jdbc:highgo: 与海量 jdbc:vastbase: / jdbc:dws:iam:
        //（cn.com.vastbase.Driver 的 parseURL 常量池，调研报告 §6.2）
        Map.entry("highgo", "HighGo"),
        Map.entry("vastbase", "Vastbase"),
        Map.entry("dws", "Vastbase"),
        Map.entry("gbasedbt-sqli", "GBASE"),
        Map.entry("gbasedbt", "GBASE"),
        Map.entry("gbase", "GBASE"));

    /**
     * 从 JDBC URL 提取子协议并映射显示名。
     * 形态：{@code jdbc:<子协议>:...}（子协议大小写不敏感）；非 jdbc: 前缀/空 URL/未知子协议 → null。
     */
    public static String displayName(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url.trim();
        if (!trimmed.regionMatches(true, 0, "jdbc:", 0, 5)) {
            return null;
        }
        String rest = trimmed.substring(5);
        int colon = rest.indexOf(':');
        String subprotocol = (colon >= 0 ? rest.substring(0, colon) : rest).toLowerCase(Locale.ROOT);
        if (subprotocol.isEmpty()) {
            return null;
        }
        return SUBPROTOCOL_NAMES.get(subprotocol);
    }

    // ---------- R32 弱形态：url 解析真值充实角标 ----------

    /**
     * R32 弱形态：url 解析连接事实（库名@host:port）与方言显示名合成角标文案。
     *
     * <p>合成规则：连接事实与方言名都有 → {@code "方言 · 库名@host:port"}（方言在前保持既有阅读顺序）；
     * 只有其一输出其一；两者皆无（畸形 URL）→ null。端口全数字才带上（畸形端口丢弃）。
     * 凭据安全：解析走 {@link #targetOf}（user/password 参数与 Oracle thin 登录前缀在解析期剔除），
     * query 串整体不进结果——凭据串绝不入标注。
     */
    public static String badgeName(String url) {
        String dialect = displayName(url);
        JdbcUrlTarget t = targetOf(url);
        if (t == null) {
            return dialect;
        }
        StringBuilder fact = new StringBuilder();
        if (t.dbName() != null) {
            fact.append(t.dbName());
        }
        if (t.host() != null) {
            if (fact.length() > 0) {
                fact.append('@');
            }
            fact.append(t.host());
            if (t.port() != null) {
                fact.append(':').append(t.port());
            }
        }
        if (fact.length() == 0) {
            return dialect;
        }
        return dialect == null ? fact.toString() : dialect + " · " + fact;
    }

    /**
     * R32 弱形态：从 JDBC URL 解析连接事实（库名/host/port）。
     *
     * <p>覆盖形态：
     * <ul>
     *   <li>通用 authority 形态 {@code jdbc:<子协议>://host[:port]/db[?query]}（MySQL/PG/DM/KingBase…）；</li>
     *   <li>分号参数形态 {@code jdbc:sqlserver://host[:port];databaseName=dbx;...}——库名<b>只认 databaseName=
     *       键</b>（与 execute/JdbcUrlDatabase 同口径），其余参数（含 user/password）一律不入结果；</li>
     *   <li>Oracle thin 两种形态：service_name {@code ...thin:[u/p]@//host:port/service} 与
     *       SID {@code ...thin:[u/p]@host:port:SID}；TNS 长描述 {@code (DESCRIPTION=...)} 不猜（已知限制）。</li>
     * </ul>
     *
     * <p>凭据剔除：取<b>最后一个 {@code @}</b> 之后参与解析（内联 {@code user[:password]@} 与 thin 登录前缀一并丢弃），
     * query 串整体丢弃——凭据串绝不进入返回值。嵌入形态（{@code jdbc:h2:mem:...}、{@code jdbc:sqlite:file}）
     * 无 host 可标 → null（角标保持纯方言名）。
     *
     * <p>不复用 execute/JdbcUrlDatabase.nameFromUrl：其保守契约（Oracle SID 形态不猜）被预览会话库
     * 对齐消费（T-ConsoleDbContext），扩 Oracle 形态会改变预览对齐行为——R32 角标域独立解析，
     * 不越界改它。
     */
    public static JdbcUrlTarget targetOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url.trim();
        if (!trimmed.regionMatches(true, 0, "jdbc:", 0, 5)) {
            return null;
        }
        String rest = trimmed.substring(5);
        int colon = rest.indexOf(':');
        String subprotocol = (colon >= 0 ? rest.substring(0, colon) : rest).toLowerCase(Locale.ROOT);
        if (subprotocol.isEmpty()) {
            return null;
        }
        String data = colon >= 0 ? rest.substring(colon + 1) : rest;
        return "oracle".equals(subprotocol) ? oracleTarget(data) : authorityTarget(data);
    }

    /** 通用 authority 解析：{@code //[u[:p]@]host[:port]/db[?query]}，含分号参数形态的 authority 截断。 */
    private static JdbcUrlTarget authorityTarget(String data) {
        String body = data;
        String semiDb = null;
        int semi = data.indexOf(';');
        if (semi >= 0) {
            body = data.substring(0, semi);
            // 分号参数：库名只认 databaseName= 键（大小写不敏感），其余参数（含 user/password）不入结果
            for (String kv : data.substring(semi + 1).split(";", -1)) {
                int eq = kv.indexOf('=');
                if (eq > 0 && kv.substring(0, eq).trim().equalsIgnoreCase("databaseName")) {
                    String v = kv.substring(eq + 1).trim();
                    semiDb = v.isEmpty() ? null : v;
                }
            }
        }
        if (!body.startsWith("//")) {
            return null; // 嵌入形态（H2 mem/file、SQLite 文件）无 host 可标
        }
        String after = body.substring(2);
        int authorityEnd = indexOfFirstSlashOrQuestion(after);
        String rawAuthority = authorityEnd >= 0 ? after.substring(0, authorityEnd) : after;
        // 凭据剔除（段内）：authority 里内联的 user[:password]@ 取最后一个 '@' 之后——
        // 只在 authority 段内削，不碰 '//' 前缀与 path
        int at = rawAuthority.lastIndexOf('@');
        String authority = at >= 0 ? rawAuthority.substring(at + 1) : rawAuthority;
        String dbName = semiDb;
        if (authorityEnd >= 0 && after.charAt(authorityEnd) == '/') {
            String seg = after.substring(authorityEnd + 1);
            int q = seg.indexOf('?');
            if (q >= 0) {
                seg = seg.substring(0, q);
            }
            int sl = seg.indexOf('/');
            if (sl >= 0) {
                seg = seg.substring(0, sl);
            }
            seg = seg.trim();
            if (dbName == null && !seg.isEmpty()) {
                dbName = seg;
            }
        }
        HostPort hp = parseAuthority(authority);
        if (dbName == null && hp.host() == null) {
            return null;
        }
        return new JdbcUrlTarget(dbName, hp.host(), hp.port());
    }

    /** Kotlin {@code indexOfFirst { it == '/' || it == '?' }} 等价：返回首个 '/' 或 '?' 的下标（无则 -1）。 */
    private static int indexOfFirstSlashOrQuestion(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '?') {
                return i;
            }
        }
        return -1;
    }

    /** Oracle thin 专项：{@code //host:port/service}（service_name）、{@code host:port:SID}；TNS 描述不猜。 */
    private static JdbcUrlTarget oracleTarget(String data) {
        if (data.indexOf('(') >= 0) {
            return null; // TNS 长描述形态不猜（登记限制）
        }
        // 凭据剔除：thin 登录前缀 `thin:[user[/password]]@` ——取最后一个 '@' 之后参与解析
        int at = data.lastIndexOf('@');
        String credFree = at >= 0 ? data.substring(at + 1) : data;
        if (credFree.startsWith("//")) {
            return authorityTarget(credFree); // service_name 形态同 authority 解析
        }
        String[] parts = credFree.split(":", -1);
        if (parts.length == 3) {
            return new JdbcUrlTarget( // host:port:SID
                parts[2].isEmpty() ? null : parts[2],
                parts[0].isEmpty() ? null : parts[0],
                (!parts[1].isEmpty() && isAllDigits(parts[1])) ? parts[1] : null);
        }
        if (parts.length == 2) {
            return new JdbcUrlTarget( // host:port（无 SID 段）
                null,
                parts[0].isEmpty() ? null : parts[0],
                (!parts[1].isEmpty() && isAllDigits(parts[1])) ? parts[1] : null);
        }
        return null; // TNS 别名单段等：无可标注事实
    }

    /** authority 拆 host/port：port 全数字才认（防误切畸形）；多冒号裸 IPv6 不标 host（防御）。 */
    private static HostPort parseAuthority(String authority) {
        if (authority.isEmpty()) {
            return new HostPort(null, null);
        }
        int colon = authority.lastIndexOf(':');
        if (colon < 0) {
            return new HostPort(authority, null);
        }
        String p = authority.substring(colon + 1);
        String host = authority.substring(0, colon);
        if (!p.isEmpty() && isAllDigits(p)) {
            return new HostPort((!host.isEmpty() && host.indexOf(':') < 0) ? host : null, p);
        }
        return new HostPort(authority.indexOf(':') < 0 ? authority : null, null);
    }

    /** Kotlin {@code String.all(Char::isDigit)} 等价（空串返回 true，调用处已另判非空）。 */
    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** parseAuthority 返回值（Kotlin {@code Pair<String?, String?>} 的 Java 等价）。 */
    private record HostPort(String host, String port) {
    }
}
