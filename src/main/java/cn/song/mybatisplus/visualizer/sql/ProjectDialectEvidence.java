// T-SyncK2JMaster175 afd3d09 自 master sql/ProjectDialectEvidence.kt（新增）移植
package cn.song.mybatisplus.visualizer.sql;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T-DialectInferFix（增补券）：<b>没有配置 IDE 数据源</b>时的方言证据。
 *
 * <p>用户实际场景里很常见「只在 {@code application.yml} 里写了 JDBC URL，Database 工具窗一个数据源都没配」，
 * 或「项目里配了数据源但从没连接过」。旧实现只认 agent 捕获 URL 与 IDE 数据源，两者都没有就一路
 * 兜底 MySQL——对这些项目等于「智能推断从未生效」。</p>
 *
 * <p>这里补三层<b>只读、可解释、fail-closed</b> 的证据（全部低置信度，UI 必须披露来源）：</p>
 * <ol>
 *  <li>{@link #configDialect(Project, Module)}：{@code application*} / {@code bootstrap*} 配置文件里的
 *      {@code jdbc:<子协议>:}。
 *      <ul>
 *       <li><b>模块优先</b>（T-DialectInferSmart）：传了 {@link Module} 就只扫该模块自己的文件（多模块各自连不同库时，
 *           模块 A 的预览不再被模块 B 的配置搅成「无共识」）；模块内无结论才由调用方回落到项目级。</li>
 *       <li><b>排除测试资源</b>：{@code src/test/resources} 下的 {@code application-test.yml} 不参与（否则「主库 PG + 测试 H2」
 *           会被判成无共识，整条推断退化）。</li>
 *       <li><b>激活 profile 优先</b>：读 {@code spring.profiles.active} / {@code spring.profiles.include}，激活 profile 的
 *           {@code application-<profile>.*} 覆盖基础 {@code application.*}；<b>其它环境的文件直接不投票</b>。
 *           拿不到激活 profile（没写/占位符无默认值）时退回「全部文件求共识」，与旧行为一致。</li>
 *      </ul></li>
 *  <li>{@link #dependencyDialect(Project)}：{@code pom.xml} / {@code build.gradle(.kts)} 里的数据库驱动坐标。多个不同驱动 → 不猜。</li>
 *  <li>{@link #syntaxHint(String)}：SQL 文本里的<b>方言独占</b>特征（反引号、{@code LIMIT a, b}、{@code ::type}、{@code ROWNUM}、{@code TOP n}…）。
 *      只认强特征，且要求「只有一个方言命中」，否则 null。</li>
 * </ol>
 *
 * <p>缓存：TTL（默认 30s）+ 项目/模块弱引用，模块各占一槽；{@link #enabled} 是测试缝（单测默认关闭真实扫描）。
 * 扫描范围有上界（{@code MAX_CONFIG_FILES} / {@code MAX_BUILD_FILES} / {@code MAX_TEXT_CHARS}），不会扫整仓。</p>
 */
public final class ProjectDialectEvidence {

    private ProjectDialectEvidence() {}

    public record Evidence(String dialectName, DialectDecisionEngine.Source source) {
    }

    /** 一个配置/构建文件的「文件名 + 正文」——profile 判定需要文件名后缀。 */
    public record ConfigFile(String name, String text) {
    }

    /** 驱动坐标标记（顺序敏感，见 {@code DRIVER_MARKERS}）。 */
    private record DriverMarker(String marker, String dialect) {
    }

    /** 方言独占语法特征表项。 */
    private record SyntaxMarker(String dialect, List<Pattern> patterns) {
    }

    /** 扫描的配置文件扩展名（按扩展名走 FilenameIndex，覆盖 Boot 的连字符命名）。 */
    private static final List<String> CONFIG_EXTENSIONS = List.of("yml", "yaml", "properties");

    /** 参与驱动坐标扫描的构建文件（按名索引）。 */
    private static final List<String> BUILD_FILE_NAMES = List.of("pom.xml", "build.gradle", "build.gradle.kts");

    private static final int MAX_CONFIG_FILES = 24;
    private static final int MAX_BUILD_FILES = 12;
    private static final int MAX_TEXT_CHARS = 262_144;

    /** JDBC URL 定位（只需子协议；连接串里的账号密码不进入任何返回值）。 */
    private static final Pattern JDBC_URL = Pattern.compile("jdbc:([a-zA-Z0-9]+):");

    /** 扁平键形态：`spring.profiles.active=dev` / `spring.profiles.active: dev`。 */
    private static final Pattern FLAT_PROFILE_KEY = Pattern.compile(
            "(?i)^\\s*spring\\.profiles\\.(active|include)\\s*[:=]\\s*(.+?)\\s*$");

    private static final Pattern SPRING_KEY = Pattern.compile("^\\s*spring\\s*:\\s*$");
    private static final Pattern PROFILES_KEY = Pattern.compile("^\\s*profiles\\s*:\\s*$");
    private static final Pattern PROFILE_VALUE_KEY = Pattern.compile("^\\s*(active|include)\\s*:\\s*(.*)$");

    /**
     * 驱动坐标标记 → 方言名（小写子串匹配，<b>顺序敏感</b>：{@code dialectsInDependencies} 按本表顺序
     * 收集命中项）。键取各驱动<b>发布坐标里独有</b>的片段，不用 {@code h2}/{@code dm} 这类短词（会在任意
     * 文本里误命中）。
     *
     * <p>PG 协议版金仓驱动（{@code cn.com.kingbase:postgresql-for-kingbase}，驱动类 {@code org.postgresql.Driver}、
     * 子协议 {@code jdbc:postgresql:}）无法从 URL 区分，靠坐标标记纠正——故其标记<b>必须排在</b>
     * {@code org.postgresql:postgresql} 之前；用户用 {@code jdbc:postgresql:} 连金仓时仍按 PostgreSQL
     * 渲染属已知边界（URL 层无 distinguishing 特征）。</p>
     */
    private static final List<DriverMarker> DRIVER_MARKERS = List.of(
            new DriverMarker("mysql-connector-j", "MySQL"),
            new DriverMarker("mysql-connector-java", "MySQL"),
            new DriverMarker("com.mysql:mysql", "MySQL"),
            new DriverMarker("mariadb-java-client", "MariaDB"),
            new DriverMarker("org.mariadb.jdbc", "MariaDB"),
            // 金仓 PG 协议版必须先于 org.postgresql:postgresql（见上 KDoc；子串含 "kingbase" 不会误伤原版 pg 坐标）
            new DriverMarker("postgresql-for-kingbase", "KingBase（人大金仓）"),
            new DriverMarker("org.postgresql:postgresql", "PostgreSQL"),
            new DriverMarker("com.oracle.database.jdbc", "Oracle"),
            new DriverMarker("ojdbc", "Oracle"),
            new DriverMarker("mssql-jdbc", "SQL Server"),
            new DriverMarker("sqljdbc", "SQL Server"),
            new DriverMarker("sqlite-jdbc", "SQLite"),
            new DriverMarker("com.h2database", "H2"),
            new DriverMarker("com.dameng", "DM（达梦）"),
            new DriverMarker("dmjdbc", "DM（达梦）"),
            new DriverMarker("kingbase8", "KingBase（人大金仓）"),
            new DriverMarker("opengauss", "openGauss"),
            new DriverMarker("gaussdb", "openGauss"),
            // T-XcDialects3（券 05）：瀚高两个制品 com.highgo:HgdbJdbc（jdbc:highgo:）与
            // com.highgo:hgdb-pgjdbc（jdbc:postgresql:，原版 pgjdbc 直用）都归 HighGo——后者
            // 用户拿它连瀚高时坐标纠正是唯一线索；海量 cn.com.vastdata:vastbase-jdbc。
            new DriverMarker("com.highgo", "HighGo"),
            new DriverMarker("hgdb", "HighGo"),
            new DriverMarker("vastbase", "Vastbase"));

    /** 方言独占语法特征（小写正则；只认强特征，宁可漏判）。 */
    private static final List<SyntaxMarker> SYNTAX_MARKERS = List.of(
            new SyntaxMarker("MySQL", List.of(
                    Pattern.compile("`[A-Za-z_][A-Za-z0-9_]*`"),
                    Pattern.compile("\\blimit\\s+\\d+\\s*,\\s*\\d+"),
                    Pattern.compile("\\bon\\s+duplicate\\s+key\\b"),
                    Pattern.compile("\\bifnull\\s*\\("),
                    Pattern.compile("\\bgroup_concat\\s*\\("),
                    Pattern.compile("\\bauto_increment\\b"))),
            new SyntaxMarker("PostgreSQL", List.of(
                    Pattern.compile("::[a-z][a-z0-9_]*(\\[\\])?"),
                    Pattern.compile("\\bilike\\b"),
                    Pattern.compile("\\bnextval\\s*\\("),
                    Pattern.compile("\\bjsonb\\b"),
                    Pattern.compile("\\bserial\\b"))),
            new SyntaxMarker("Oracle", List.of(
                    Pattern.compile("\\bnvl\\s*\\("),
                    Pattern.compile("\\brownum\\b"),
                    Pattern.compile("\\(\\+\\)"),
                    Pattern.compile("\\bminus\\b"),
                    Pattern.compile("\\bvarchar2\\b"),
                    Pattern.compile("\\bdual\\b"))),
            new SyntaxMarker("SQL Server", List.of(
                    Pattern.compile("\\bselect\\s+top\\s+\\d+"),
                    Pattern.compile("\\bgetdate\\s*\\("),
                    Pattern.compile("\\bisnull\\s*\\("),
                    Pattern.compile("\\[[A-Za-z_][A-Za-z0-9_]*\\]"))));

    private static final class Slot {
        final java.lang.ref.WeakReference<Project> project;
        final long atMs;
        final Evidence evidence;

        Slot(java.lang.ref.WeakReference<Project> project, long atMs, Evidence evidence) {
            this.project = project;
            this.atMs = atMs;
            this.evidence = evidence;
        }
    }

    /** 项目级槽（module == null）。 */
    private static volatile Slot projectSlot = null;

    /** 模块级槽：多模块各自连不同库时必须分开缓存，否则 A 模块的结论会喂给 B。 */
    private static final java.util.Map<Module, Slot> moduleSlots =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 测试缝：TTL 上界（毫秒）。 */
    public static volatile long ttlMs = 30_000L;

    /** 测试缝：时间源。 */
    public static volatile LongSupplier clockMs = System::currentTimeMillis;

    /**
     * 是否允许<b>真实扫描</b>。单元测试模式默认关闭：
     * <ul>
     *  <li>全量 8500+ 例里凡走 {@code DialectRegistry.effective(project)} 的都会各扫一遍工程文件，纯浪费；</li>
     *  <li>更危险的是<b>仓库自身</b>的 {@code build.gradle.kts} 含 {@code libs.postgresql}（测试依赖），
     *      任何以真实工程为 basePath 的测试项目都会被它判成 PostgreSQL，污染既有断言。</li>
     * </ul>
     * 语义由 {@link #configFilesForTest} / {@link #buildTextsForTest} 缝覆盖，接线由真机覆盖。
     */
    public static volatile boolean enabled = defaultEnabled();

    /** 测试缝：配置文件替身（含文件名，供 profile 判定；null = 走真实扫描）。 */
    public static volatile Function<Project, List<ConfigFile>> configFilesForTest = null;

    /** 测试缝：构建文件文本替身。 */
    public static volatile Function<Project, List<String>> buildTextsForTest = null;

    private static boolean defaultEnabled() {
        com.intellij.openapi.application.Application app = ApplicationManager.getApplication();
        return app == null || !app.isUnitTestMode();
    }

    /** Kotlin 默认参数等价重载（module 缺省 null）。 */
    @Nullable
    public static Evidence forProject(@Nullable Project project) {
        return forProject(project, null);
    }

    /**
     * 补充证据：配置文件优先、驱动依赖次之；两者都无结论 → null（调用方保持 fail-closed）。
     *
     * @param module 预览来源文件所属模块；非 null 时配置扫描<b>只收该模块自己的文件</b>，
     *               模块内无结论由调用方再问项目级（{@link Module} 为 null 的那次调用）
     */
    @Nullable
    public static Evidence forProject(@Nullable Project project, @Nullable Module module) {
        if (project == null) return null;
        // 测试缝注入时不看 enabled——语义用例要能在「真实扫描关闭」的环境里跑
        boolean seamsInjected = configFilesForTest != null || buildTextsForTest != null;
        if (!enabled && !seamsInjected) return null;
        long now = clockMs.getAsLong();
        Slot cached = cachedSlot(project, module, now);
        if (cached != null) {
            return cached.evidence;
        }
        Evidence evidence;
        try {
            String byConfig = configDialect(project, module);
            evidence = byConfig != null
                    ? new Evidence(byConfig, DialectDecisionEngine.Source.PROJECT_CONFIG)
                    : evaluateDependency(project);
        } catch (Throwable t) {
            evidence = null;
        }
        Slot slot = new Slot(new java.lang.ref.WeakReference<>(project), now, evidence);
        if (module == null) {
            projectSlot = slot;
        } else {
            moduleSlots.put(module, slot);
        }
        return evidence;
    }

    @Nullable
    private static Evidence evaluateDependency(Project project) {
        String byDependency = dependencyDialect(project);
        return byDependency != null
                ? new Evidence(byDependency, DialectDecisionEngine.Source.PROJECT_DEPENDENCY)
                : null;
    }

    /** Kotlin 默认参数等价重载（module 缺省 null）。 */
    @Nullable
    public static Evidence cachedOrNull(@Nullable Project project) {
        return cachedOrNull(project, null);
    }

    /** 只读缓存（EDT 调用路径用：绝不在 EDT 上触发文件扫描）。 */
    @Nullable
    public static Evidence cachedOrNull(@Nullable Project project, @Nullable Module module) {
        if (project == null) return null;
        Slot cached = cachedSlot(project, module, clockMs.getAsLong());
        return cached != null ? cached.evidence : null;
    }

    @Nullable
    private static Slot cachedSlot(Project project, @Nullable Module module, long now) {
        Slot cached = module == null ? projectSlot : moduleSlots.get(module);
        if (cached == null) return null;
        if (cached.project.get() != project) return null;
        if (now - cached.atMs > ttlMs) return null;
        return cached;
    }

    /** Kotlin 默认参数等价重载（module 缺省 null）。 */
    @Nullable
    public static String configDialect(Project project) {
        return configDialect(project, null);
    }

    /**
     * 配置文件里的 JDBC 子协议共识（唯一才返回）。
     *
     * <p>激活 profile 已知时按 Spring 的覆盖语义分层：{@code application-<profile>} 优先，
     * <b>其它 profile 的文件不投票</b>；profile 段没有任何 URL 才看基础文件。</p>
     */
    @Nullable
    public static String configDialect(Project project, @Nullable Module module) {
        try {
            Function<Project, List<ConfigFile>> seam = configFilesForTest;
            List<ConfigFile> files = seam != null ? seam.apply(project) : readConfigFiles(project, module);
            Set<String> active = activeProfilesIn(files);
            if (active.isEmpty()) {
                // 拿不到激活 profile：全部文件求共识（旧行为）
                List<String> all = new ArrayList<>();
                for (ConfigFile f : files) {
                    all.addAll(dialectsInUrls(f.text()));
                }
                return consensus(all);
            }
            List<ConfigFile> profileFiles = new ArrayList<>();
            List<ConfigFile> baseFiles = new ArrayList<>();
            for (ConfigFile f : files) {
                String suffix = profileSuffix(f.name());
                if (suffix == null) {
                    baseFiles.add(f);
                } else if (active.contains(suffix.toLowerCase(Locale.ROOT))) {
                    profileFiles.add(f);
                }
            }
            List<String> profileUrls = new ArrayList<>();
            for (ConfigFile f : profileFiles) {
                profileUrls.addAll(dialectsInUrls(f.text()));
            }
            if (!profileUrls.isEmpty()) {
                // profile 段有证据：只认它（冲突即 null，不拿 base 的旧值兜底）
                return consensus(profileUrls);
            }
            List<String> baseUrls = new ArrayList<>();
            for (ConfigFile f : baseFiles) {
                baseUrls.addAll(dialectsInUrls(f.text()));
            }
            return consensus(baseUrls);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 构建文件里的数据库驱动坐标共识（唯一才返回）。 */
    @Nullable
    public static String dependencyDialect(Project project) {
        try {
            Function<Project, List<String>> seam = buildTextsForTest;
            List<ConfigFile> files = new ArrayList<>();
            if (seam != null) {
                for (String text : seam.apply(project)) {
                    files.add(new ConfigFile("", text));
                }
            } else {
                files.addAll(readBuildTexts(project));
            }
            List<String> all = new ArrayList<>();
            for (ConfigFile f : files) {
                all.addAll(dialectsInDependencies(f.text()));
            }
            return consensus(all);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 纯函数：文本里出现的 JDBC 子协议 → 注册方言名（未收录子协议自然被忽略）。 */
    public static List<String> dialectsInUrls(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = JDBC_URL.matcher(text);
        while (m.find()) {
            String dialect = UrlSubprotocolDialects.dialectName("jdbc:" + m.group(1) + ":");
            if (dialect != null) {
                out.add(dialect);
            }
        }
        return out;
    }

    /** 纯函数：构建文件文本里的驱动坐标 → 注册方言名。 */
    public static List<String> dialectsInDependencies(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (DriverMarker marker : DRIVER_MARKERS) {
            if (lower.contains(marker.marker())) {
                out.add(marker.dialect());
            }
        }
        return out;
    }

    /**
     * 纯函数：从配置文本里取激活 profile 集合（{@code spring.profiles.active|include}）。
     *
     * <p>支持：扁平的 {@code spring.profiles.active: dev} / {@code .properties} 的 {@code =dev} / YAML 缩进块
     * （{@code spring: → profiles: → active:}）/ 逗号与 {@code [a, b]} 列表 / 后续 {@code - item} 列表 /
     * {@code ${ENV:default}} 取默认值。{@code ${ENV}}（无默认值）与 {@code @maven.filter@} 视为未知 → 跳过。
     * 拿不到任何值 → 空集（调用方按「未知」处理，全量求共识）。</p>
     */
    public static Set<String> activeProfilesIn(List<ConfigFile> files) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (ConfigFile f : files) {
            for (String profile : activeProfilesIn(f.text())) {
                out.add(profile.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    public static List<String> activeProfilesIn(String text) {
        List<String> out = new ArrayList<>();
        int springIndent = -1;
        int profilesIndent = -1;
        List<String> lines = text.lines().toList();
        int i = 0;
        while (i < lines.size()) {
            String raw = lines.get(i);
            i++;
            String line = stripYamlComment(raw);
            if (line.isBlank()) continue;
            int indent = leadingWhitespace(line);
            // 缩进回退即离开 spring/profiles 块
            if (springIndent >= 0 && indent <= springIndent && !SPRING_KEY.matcher(line).matches()) {
                springIndent = -1;
                profilesIndent = -1;
            } else if (profilesIndent >= 0 && indent <= profilesIndent && !PROFILES_KEY.matcher(line).matches()) {
                profilesIndent = -1;
            }
            Matcher flat = FLAT_PROFILE_KEY.matcher(line);
            if (flat.matches()) {
                out.addAll(parseProfileValues(flat.group(2)));
                continue;
            }
            if (SPRING_KEY.matcher(line).matches()) {
                springIndent = indent;
                continue;
            }
            if (springIndent >= 0 && indent > springIndent && PROFILES_KEY.matcher(line).matches()) {
                profilesIndent = indent;
                continue;
            }
            if (profilesIndent >= 0 && indent > profilesIndent) {
                Matcher value = PROFILE_VALUE_KEY.matcher(line);
                if (value.matches()) {
                    String v = value.group(2).trim();
                    if (v.isEmpty()) {
                        // 列表形态：后续更深缩进的 "- item"
                        List<String> items = new ArrayList<>();
                        while (i < lines.size()) {
                            String next = stripYamlComment(lines.get(i));
                            if (next.isBlank()) {
                                i++;
                                continue;
                            }
                            int nextIndent = leadingWhitespace(next);
                            if (nextIndent <= indent) break;
                            i++;
                            String item = next.trim();
                            if (item.startsWith("-")) {
                                item = item.substring(1).trim();
                            }
                            if (!item.isEmpty()) {
                                items.add(item);
                            }
                        }
                        v = String.join(",", items);
                    }
                    out.addAll(parseProfileValues(v));
                }
            }
        }
        return out;
    }

    /**
     * 纯函数：SQL 文本的方言独占特征。
     *
     * <p>只有<b>恰好一个</b>方言命中才返回（{@code LIMIT a, b} 与反引号同时出现仍算 MySQL 一个方言）；
     * 两个方言各有特征 → null（例如混写了方言方言的脚本）。</p>
     */
    @Nullable
    public static String syntaxHint(@Nullable String sql) {
        if (sql == null || sql.isBlank()) return null;
        String lower = sql.toLowerCase(Locale.ROOT);
        java.util.LinkedHashSet<String> hits = new java.util.LinkedHashSet<>();
        for (SyntaxMarker marker : SYNTAX_MARKERS) {
            for (Pattern pattern : marker.patterns()) {
                if (pattern.matcher(lower).find()) {
                    hits.add(marker.dialect());
                    break;
                }
            }
        }
        return hits.size() == 1 ? hits.iterator().next() : null;
    }

    /** 唯一共识：去重后恰好一个才采用；否则不猜。 */
    @Nullable
    private static String consensus(List<String> names) {
        java.util.LinkedHashSet<String> distinct = new java.util.LinkedHashSet<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                distinct.add(name);
            }
        }
        return distinct.size() == 1 ? distinct.iterator().next() : null;
    }

    /** `application-dev.yml` → `dev`；`application.yml` / `bootstrap.yml` → null（基础文件）。 */
    public static @Nullable String profileSuffix(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String stem = dot >= 0 ? lower.substring(0, dot) : lower;
        if (stem.equals("application") || stem.equals("bootstrap")) return null;
        if (!stem.startsWith("application-") && !stem.startsWith("bootstrap-")) return null;
        String suffix = stem.substring(stem.indexOf('-') + 1);
        return suffix.isBlank() ? null : suffix;
    }

    /** `dev, test` / `[dev, test]` / `${ENV:dev}` → 去引号后的 profile 列表。 */
    private static List<String> parseProfileValues(String raw) {
        String stripped = removeSurrounding(raw, "[", "]");
        List<String> out = new ArrayList<>();
        for (String part : stripped.split(",")) {
            String token = part.trim();
            token = removeSurrounding(token, "\"", "\"");
            token = removeSurrounding(token, "'", "'");
            token = normalizeProfileToken(token);
            if (!token.isEmpty()) {
                out.add(token);
            }
        }
        return out;
    }

    /** `${ENV:dev}` → `dev`；`${ENV}` 与 `@x@`（Maven 过滤）→ 空（未知，不猜）。 */
    private static final Pattern PROFILE_PLACEHOLDER = Pattern.compile("^\\$\\{([^:}]+)(?::([^}]*))?}$");

    private static String normalizeProfileToken(String token) {
        if (token.isEmpty()) return "";
        if (token.startsWith("@") && token.endsWith("@")) return "";
        Matcher placeholder = PROFILE_PLACEHOLDER.matcher(token);
        if (!placeholder.matches()) return token;
        return placeholder.group(2).trim();
    }

    /** 去掉行尾 `#` 注释（只在引号外生效，够 YAML/properties 用）。 */
    private static String stripYamlComment(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            switch (c) {
                case '\'' -> {
                    if (!inDouble) inSingle = !inSingle;
                }
                case '"' -> {
                    if (!inSingle) inDouble = !inDouble;
                }
                case '#' -> {
                    if (!inSingle && !inDouble) return line.substring(0, i);
                }
                default -> {
                }
            }
            i++;
        }
        return line;
    }

    /** 首个非空白字符的下标（无空白/空行 → 0）。 */
    private static int leadingWhitespace(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (!Character.isWhitespace(line.charAt(i))) {
                return i;
            }
        }
        return 0;
    }

    /** Kotlin `removeSurrounding(prefix, suffix)` 等价（两端齐备才去，否则原样）。 */
    private static String removeSurrounding(String value, String prefix, String suffix) {
        if (value.length() >= prefix.length() + suffix.length()
                && value.startsWith(prefix) && value.endsWith(suffix)) {
            return value.substring(prefix.length(), value.length() - suffix.length());
        }
        return value;
    }

    private static List<ConfigFile> readConfigFiles(Project project, @Nullable Module module) {
        return readProjectTexts(project, module, MAX_CONFIG_FILES,
                file -> isApplicationConfig(file.getName()),
                List.of(), CONFIG_EXTENSIONS);
    }

    private static List<ConfigFile> readBuildTexts(Project project) {
        return readProjectTexts(project, null, MAX_BUILD_FILES,
                file -> true,
                BUILD_FILE_NAMES, List.of());
    }

    /** `application.yml` / `application-dev.yml` / `bootstrap.properties` 一族。 */
    private static boolean isApplicationConfig(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String stem = dot >= 0 ? lower.substring(0, dot) : lower;
        return stem.equals("application") || stem.equals("bootstrap") || stem.startsWith("application-")
                || stem.startsWith("bootstrap-");
    }

    /**
     * 有界读取：按名 + 按扩展名两次索引查询，读前限制文件大小，ReadAction 内完成。
     *
     * <p>过滤两件事：<b>测试资源不参与</b>（{@code isInTestSourceContent}，否则测试库会把真实库挤成「无共识」）；
     * 传了 {@code module} 时<b>只收该模块的文件</b>（多模块各自连不同库时互不干扰）。
     * 读失败的文件静默跳过（推断是可选能力，不得影响预览主链）。</p>
     */
    private static List<ConfigFile> readProjectTexts(Project project, @Nullable Module module, int maxFiles,
                                                     java.util.function.Predicate<VirtualFile> accept,
                                                     List<String> byName, List<String> byExtensions) {
        return ReadAction.compute(() -> {
            if (project.isDisposed()) return List.of();
            GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
            ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
            LinkedHashSet<VirtualFile> files = new LinkedHashSet<>();
            for (String name : byName) {
                try {
                    files.addAll(FilenameIndex.getVirtualFilesByName(project, name, scope));
                } catch (Throwable ignored) {
                    // runCatching().getOrDefault(emptyList()) 等价
                }
            }
            for (String ext : byExtensions) {
                try {
                    files.addAll(FilenameIndex.getAllFilesByExt(project, ext, scope));
                } catch (Throwable ignored) {
                    // runCatching().getOrDefault(emptyList()) 等价
                }
            }
            List<ConfigFile> out = new ArrayList<>();
            int taken = 0;
            for (VirtualFile file : files) {
                if (taken >= maxFiles) break;
                if (!file.isValid() || file.isDirectory() || !accept.test(file)) continue;
                if (fileIndex.isInTestSourceContent(file)) continue;
                if (module != null && ModuleUtilCore.findModuleForFile(file, project) != module) continue;
                taken++;
                try {
                    if (file.getLength() > MAX_TEXT_CHARS) continue;
                    out.add(new ConfigFile(file.getName(), VfsUtilCore.loadText(file)));
                } catch (Throwable ignored) {
                    // 读失败的文件静默跳过
                }
            }
            return out;
        });
    }

    /** 测试缝：清缓存。 */
    public static void invalidate() {
        projectSlot = null;
        moduleSlots.clear();
    }

    /** 测试缝：清缓存 + 恢复默认（用例隔离）。 */
    public static void resetForTest() {
        projectSlot = null;
        moduleSlots.clear();
        configFilesForTest = null;
        buildTextsForTest = null;
        enabled = defaultEnabled();
        ttlMs = 30_000L;
        clockMs = System::currentTimeMillis;
    }
}
