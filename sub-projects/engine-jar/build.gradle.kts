import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.jvm.toolchain.JavaLanguageVersion

// T-OpenIDEAPort T0-0（2026-11-25，master）→ T-SyncK2JMaster181（2026-11-26，k2j Java 投影口径）。
//
// 目标：把主仓 P0 纯逻辑打成**不含任何 IntelliJ 类型**的 mpsv-engine jar，供下一代的非 IntelliJ
// 宿主（OpenIDEA / Rust 侧）经 JSON 门面（mpsv.engine.EngineApi）消费。
//
// **k2j 与 master 的实现口径差（本次移植的核心，逐条实测得来）**：
//  1. **语言**：master 的 `plugins { kotlin("jvm") }` 与整个 `kotlin {}` 块删除——引擎门面
//     （EngineApi / StdioMain / SmokeMain）在 k2j 是手写 Java。**但 `plugins {}` 块本身不能删**：
//     没有它，`java {}` / `tasks.jar` / `configurations` / `JavaCompile` 全部在脚本求值时不可用
//     （实测报 42 处 `Unresolved reference`：`tasks.jar`、`runtimeClasspath`、`dependsOn`、
//     `from` 全红）。故这里显式 apply `java` 插件。
//  2. **源根**：`src/main/java` + `.java`（k2j 生产树 `src/main/kotlin` 恒空，是 100% Java 端口）。
//  3. **字节码目标 17，不是 master 的 11**。k2j 生产树**实测**含 Java 17 语法：record 106 件、
//     sealed interface（`ExplainGate.SubmitPlan` / `DmlImpactEstimator`）、`instanceof` 模式匹配。
//     实测证据（本机 JDK 17 javac）：`--release 11` 报「-source 11 中不支持 记录 / 密封类 /
//     instanceof 中的模式匹配」；`--release 17` 0 error。Java 侧用 `options.release = 17`
//     取代 Kotlin 的 `-Xjdk-release`。
//  4. **`kotlin-stdlib` 不能删**（与「Java 产物不需要 stdlib」的直觉相反）：k2j 生产 Java 树把
//     Kotlin 的值类型落成了**真 kotlin 类型**——保留集里 15 件引用 `kotlin.Pair` /
//     `kotlin.jvm.functions.Function0` / `kotlin.text.Regex`（`LogSqlEntry.sourceCandidates`
//     就是 `List<kotlin.Pair<String,Integer>>`）。摘掉 stdlib，`new MyBatisLogParser(...)` 立刻
//     `NoClassDefFoundError: kotlin/jvm/functions/Function0`。改成显式坐标（不带 Kotlin 插件时
//     `kotlin("stdlib")` accessor 不解析）。stdlib jar 实测 0 个 intellij 路径条目、0 个 class
//     常量池命中，不破坏零平台渗透守卫。
//  5. **新增 `compileOnly(org.jetbrains:annotations)`**：k2j 生产树 406/498 件带
//     `@NotNull`/`@Nullable`/`@PropertyKey`（master 的 Kotlin 树一件都没有）。这是
//     PrepareEngineSources 移植里最大的一处口径差。`compileOnly` 不进产物（annotations 是
//     CLASS 保留策略，运行期缺失无害）。
//
// 三条硬约束（继承 master / 立项书 §1）：
//  1. **不改任何生产源码**。源集是构建期由 prepareEngineSources 从 `../../src/main/java`
//     机械裁剪生成（含投影，见任务 KDoc）。
//  2. 依赖面只有 jsqlparser + kotlin-stdlib + 两个 EXPLAIN 驱动（+ 编译期 annotations）。
//  3. 产物零平台渗透由 verifyNoPlatformImports 逐 class 常量池扫描守住（命中即构建失败）。

plugins {
    java
}

// 引擎版本与插件版本解耦（插件版本 = 发布日期；引擎是独立制品，语义不同，不进同一条演进线）。
val engineVersion = providers.gradleProperty("engineVersion").orElse("0.1.0")

// 工具链用本机既有的 JDK 17（与根项目同款）。见文件头第 3 条：目标必须是 17。
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    // 语义等价于 master 的 `-Xjdk-release=11`（收窄到目标 JDK 的 API 面），只是目标换成 k2j 树
    // 真正需要的 17。生产树是 UTF-8 原始中文（注释/字面量），显式固定编码避免跟随平台默认。
    options.release = 17
    options.encoding = "UTF-8"
    // 投影产物里有 jsqlparser 5.3 的 `[removal]` 弃用面（Parenthesis 等），上游既有 API，不属本模块。
    options.compilerArgs.add("-Xlint:-removal,-deprecation")
}

repositories {
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
}

dependencies {
    // 唯一生产依赖（双许可择 Apache-2.0；与主仓 build.gradle.kts 同版本同排除项）。
    implementation("com.github.jsqlparser:jsqlparser:5.3") {
        // 上游把 jmh-core 声明为编译期传递依赖（只用于其基准测试，jar 内 0 个引用）——主仓同款排除。
        exclude(group = "org.openjdk.jmh", module = "jmh-core")
    }
    // 见文件头第 4 条：k2j 保留集真用 kotlin 类型，stdlib 必须留。显式坐标（无 Kotlin 插件）。
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.20")

    // 见文件头第 5 条：k2j 生产树大面积使用这些注解；compileOnly 不进产物。
    compileOnly("org.jetbrains:annotations:23.0.0")

    // T5-5 真连库 EXPLAIN：两个方言的官方驱动，随 jar 自包含（许可见主仓 THIRD-PARTY-NOTICES.md）。
    // 版本与主仓 `gradle/libs.versions.toml` 同值，本机 Gradle 缓存已有。
    implementation("org.postgresql:postgresql:42.7.1") {
        // checker-qual 只服务 pgjdbc 的编译期注解（class 文件里无运行期引用），不打进自包含产物。
        exclude(group = "org.checkerframework", module = "checker-qual")
    }
    // `-jre11` 变体的字节码是 Java 11，可被 17 目标产物加载。
    implementation("com.microsoft.sqlserver:mssql-jdbc:12.4.2.jre11")
}

// ---------------------------------------------------------------------------------------------
// 源集裁剪：把生产源码按「代码零平台引用 + 引用闭包自洽」机械投影到 build/generated/engine-src。
// ---------------------------------------------------------------------------------------------

/** k2j 生产 Java 源根（只读；构建只读它，从不写回）。 */
val productionJavaRoot: Directory = rootProject.layout.projectDirectory.dir("src/main/java")
val engineGeneratedSources: Provider<Directory> = layout.buildDirectory.dir("generated/engine-src")

/**
 * 从生产源码生成引擎源集。规则（全部机械判定，人工只提供包根白名单与投影）。
 *
 * k2j 与 master 的逐条口径差（主控实测 2026-11-26 重建轮，见文件头）：
 *
 * 1. **平台引用判定**：同 master——剥注释后扫全限定名文本，命中即整文件剔除。k2j 差异：`org.jetbrains`
 *    用**负向先行断言**放行 `org.jetbrains.annotations.`（那些是 compileOnly 注解，不是平台耦合）。
 * 2. **import 白名单**：master 是 kotlin./java./javax./net.sf.jsqlparser./org.w3c./org.xml./ + 自家包根。
 *    k2j **保留 `kotlin.`**（见文件头第 4 条），并**新增** `org.jetbrains.annotations.`。
 *    Java 的 import 还要处理 `static` 修饰（`^\s*import\s+(?:static\s+)?([\w.]+)`）。
 * 3. **引用闭包收敛**：同 master，迭代到不动点。
 * 4. **投影**：master 的 5 条按 Kotlin 文件结构写，k2j 实测复核结论是 **3 保留 + 2 消失 + 1 换落点**
 *    （逐条见下面 [project] 的 KDoc）。
 * 5. **阈值**：master `>= 60`；k2j 实测收敛 **345**，取 `>= 300`（打 87 折留漂移余量）。
 *    master 的 60 在 k2j 口径下形同空转，等于没设门。
 */
abstract class PrepareEngineSources : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceRoot: DirectoryProperty

    @get:Input
    abstract val packagePrefix: Property<String>

    @get:Input
    abstract val packageRoots: ListProperty<String>

    @get:Input
    abstract val dialectRegistryCutMarker: Property<String>

    @get:Input
    abstract val minimumKeptFiles: Property<Int>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    private companion object {
        /**
         * 引擎依赖面之外的类型引用（含内联全限定名，故按文本匹配而非只看 import）：
         * 平台 `com.intellij`、平台自带库（Gson / Baomidou / Spring / kotlinx）、以及 JDK 桌面面
         * （AWT / Swing / ImageIO——引擎是无头制品，不进 UI 渲染类）。
         *
         * **k2j 差异**：`org.jetbrains` 后接负向先行断言 `(?!annotations\.)`——放行 compileOnly 的
         * `org.jetbrains.annotations.*`，而 `.concurrency` / `.jps` / `.kotlin` 等仍整文件剔除。
         */
        val EXTERNAL_REF_REGEX = Regex(
            """(?<![\w.])((?:com\.intellij|com\.google|com\.baomidou|org\.apache|org\.springframework|""" +
                """org\.jetbrains\.(?!annotations\.)|org\.intellij|kotlinx|com\.sun|java\.awt|javax\.swing|""" +
                """javax\.imageio)\.[\w.]+)""",
        )

        /** 标识符 token（闭包收敛时的引用判定口径）。 */
        val TOKEN_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /** Java import 声明（多一个 `static` 修饰）。 */
        val IMPORT_REGEX = Regex("""(?m)^\s*import\s+(?:static\s+)?([\w.]+)""")

        /**
         * Java 顶层类型声明名（**列 0 起**——只取真顶层，不含类成员；包级私有顶层同样列 0）。
         * master 的 Kotlin 版还认 `object` / `typealias` / 顶层 `fun`（含扩展函数取末段名）；
         * Java 没有顶层函数，那一支整条删除。
         */
        val DECLARATION_REGEX = Regex(
            """(?m)^(?:(?:public|protected|private|abstract|final|static|strictfp|sealed|non-sealed)\s+)*""" +
                """(?:class|interface|enum|record|@interface)\s+([A-Za-z_][A-Za-z0-9_]*)""",
        )
    }

    @TaskAction
    fun prepare() {
        val srcRoot = sourceRoot.get().asFile
        val outRoot = outputDir.get().asFile
        outRoot.deleteRecursively()
        outRoot.mkdirs()

        val prefix = packagePrefix.get()
        val roots = packageRoots.get().toSet()
        // k2j：保留 kotlin.；新增 org.jetbrains.annotations.（compileOnly 面）。
        val allowedPrefixes = listOf(
            "kotlin.", "java.", "javax.", "net.sf.jsqlparser.", "org.w3c.", "org.xml.",
            "org.jetbrains.annotations.",
        )
        val selfPrefix = prefix.replace('/', '.')

        // 1) 读入全部候选文件（rel → 原始文本；CRLF → LF 归一）
        val candidates = LinkedHashMap<String, String>()
        roots.sorted().forEach { root ->
            val dir = File(srcRoot, "$prefix/$root")
            check(dir.isDirectory) { "引擎包根不存在：$dir（包根清单漂移？）" }
            dir.walkTopDown().filter { it.isFile && it.name.endsWith(".java") }.sorted().forEach { file ->
                candidates[file.relativeTo(srcRoot).invariantSeparatorsPath] =
                    file.readText().replace("\r\n", "\n")
            }
        }

        // 2) 投影：先改写，再走平台检查（投影后仍带 com.intellij 就直接失败，不静默剔除）
        val projected = LinkedHashMap<String, String>()
        val projectionNotes = mutableListOf<String>()
        candidates.forEach { (rel, raw) ->
            projected[rel] = project(rel, raw, prefix) { projectionNotes += it }
        }

        // 3) 逐文件初筛：平台引用 + import 白名单
        val excluded = LinkedHashMap<String, String>() // rel -> reason
        val kept = LinkedHashMap<String, String>()     // rel -> 剥注释文本
        projected.forEach { (rel, text) ->
            val code = stripComments(text)
            val external = EXTERNAL_REF_REGEX.find(code)?.groupValues?.get(1)
            if (external != null) {
                excluded[rel] = if (external.startsWith("com.intellij")) {
                    "platform-reference"
                } else {
                    "external-reference:$external"
                }
                return@forEach
            }
            val badImport = IMPORT_REGEX.findAll(code)
                .map { it.groupValues[1] }
                .firstOrNull { fq ->
                    allowedPrefixes.none { fq.startsWith(it) } &&
                        !(fq.startsWith("$selfPrefix.") &&
                            fq.removePrefix("$selfPrefix.").substringBefore('.').substringBefore('/') in roots)
                }
            if (badImport != null) {
                excluded[rel] = "external-import:$badImport"
                return@forEach
            }
            kept[rel] = code
        }

        // 4) 引用闭包收敛：剔除文件的顶层声明被保留文件引用 → 连带剔除。工作队列版。
        val declarationsByFile = HashMap<String, Set<String>>()
        val tokensByFile = HashMap<String, Set<String>>()
        val declIndex = HashMap<String, MutableSet<String>>()
        (kept.keys + excluded.keys).forEach { rel ->
            val code = kept[rel] ?: stripComments(projected.getValue(rel))
            val decls = declarationsOf(code)
            declarationsByFile[rel] = decls
            tokensByFile[rel] = TOKEN_REGEX.findAll(code).mapTo(HashSet()) { it.value }
            decls.forEach { name -> declIndex.getOrPut(name) { mutableSetOf() }.add(rel) }
        }
        val pending = ArrayDeque<String>()
        val enqueued = HashSet<String>()
        fun enqueueIfAllDeclarersExcluded(name: String) {
            val files = declIndex[name] ?: return
            if (files.all { it in excluded } && enqueued.add(name)) pending.add(name)
        }
        declIndex.keys.forEach { enqueueIfAllDeclarersExcluded(it) }
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            val victims = kept.keys.filter { rel ->
                name in tokensByFile.getValue(rel) && name !in declarationsByFile.getValue(rel)
            }
            victims.forEach { rel ->
                kept.remove(rel)
                excluded[rel] = "references-excluded-declaration:$name"
                declarationsByFile.getValue(rel).forEach { enqueueIfAllDeclarersExcluded(it) }
            }
        }

        // 5) 落盘 + 清单（先落清单再断言：失败时也能看清剔了谁、为什么）
        kept.keys.sorted().forEach { rel ->
            val out = File(outRoot, rel)
            out.parentFile.mkdirs()
            out.writeText(projected.getValue(rel))
        }
        File(outRoot, "engine-sources.txt").writeText(buildString {
            append("# included (${kept.size})\n")
            kept.keys.sorted().forEach { append(it).append('\n') }
            append("\n# excluded (${excluded.size})\n")
            excluded.toSortedMap().forEach { (rel, why) -> append(rel).append("  # ").append(why).append('\n') }
            append("\n# projections (${projectionNotes.size})\n")
            projectionNotes.forEach { append(it).append('\n') }
        })
        val minimum = minimumKeptFiles.get()
        check(kept.size >= minimum) {
            "引擎源集只剩 ${kept.size} 个文件（预期 ≥$minimum）——裁剪规则疑似失效，拒绝产出空引擎" +
                "（明细见 ${File(outRoot, "engine-sources.txt")}）"
        }
    }

    /**
     * 投影：生产文件整体不可用、但只差一条缝的，就地改写。`note` 写进清单便于审计。
     * 返回的文件若仍带平台引用，步骤 3 会把它剔除——故每条投影自带断言。
     *
     * **master 5 条 → k2j 逐件复核结论**（依据 k2j 生产树实测）：
     *  - `sql/Dialect.kt` → **保留但换落点到 `sql/DialectRegistry.java`**：k2j 已把 `Dialect` 接口
     *    与 15 个方言单列成件（`Dialect.java` 实测零平台引用，无需截断），平台耦合只剩
     *    `DialectRegistry.java` 的 `decision/effective` 一组读 Project/PSI 的成员。
     *  - `log/LogSqlModels.kt` → **消失**：k2j 已按「一文件一 public 类型」拆开。UI 缓存耦合换了
     *    形态：master 是「cite IElementType/SimpleTextAttributes 的体字段」，k2j 是 record 无实例
     *    字段 + `WeakHashMap` 侧表，耦合落在 `SqlSemanticFragment`/`SqlTextFragment` 两件的类型上。
     *    投影因此改为「删 `LogSqlEntry.java` 里的 UI 缓存侧表与访问器」（下面 2、3 条）。
     *  - `log/SqlSourceMarker.kt` → **消失**：k2j 已拆成 `SqlSourceMarker.java` + `SourceMarkers.java`
     *    + `SourceMarkerData.java`。无需投影：`SqlSourceMarker.java` 因引用
     *    `SourceInterceptorTemplates`（依赖 MyBatis 平台库）被闭包自动剔除，而 `SourceMarkers` /
     *    `SourceMarkerData` 零平台引用、自动保留——正是 master 投影想留下的那部分。
     *  - `util/MpSqlVisualizerBundle.kt` → **保留**，但三处缝换了形态（见第 4 条）。
     *  - `xml/SecureXml.kt` → **保留**（见第 5 条）。
     */
    private fun project(rel: String, text: String, prefix: String, note: (String) -> Unit): String = when (rel) {
        // 1) master `sql/Dialect.kt` 的对应物：Dialect 接口与 15 个方言在 k2j 已分件且纯，
        //    平台耦合只剩本文件的 decision/effective 一组成员 → 截断。
        "$prefix/sql/DialectRegistry.java" -> {
            val marker = "\n" + dialectRegistryCutMarker.get()
            val cut = text.indexOf(marker)
            check(cut > 0) {
                "$rel 未找到截断标记 `${dialectRegistryCutMarker.get()}`——生产文件结构变了，投影规则需复核"
            }
            note("$rel  # 截断 DialectRegistry 平台成员（保留 dialects/byName）")
            text.substring(0, cut) + "\n}\n"
        }
        // 2+3) master `log/LogSqlModels.kt` 的 UI 缓存段：k2j 里是 LogSqlEntry 的两组 WeakHashMap
        //      侧表（记录类型引用 SqlSemanticFragment/SqlTextFragment，后者绑
        //      SimpleTextAttributes）。整段删除——引擎不需要渲染缓存。
        "$prefix/log/LogSqlEntry.java" -> {
            // 2) 先删侧表：LineFragmentCacheEntry/HeaderCacheEntry 两个 record 与对应的 WeakHashMap。
            val start = text.indexOf("    /** 行片段缓存载荷（片段 + 模式一并存取，保证两值一致）。 */")
            val end = text.indexOf("    /** R60：脱敏摘要缓存", start)
            check(start > 0 && end > start) { "$rel 的 UI 缓存侧表标记不见了——投影规则需复核" }
            note("$rel  # 删 UI 渲染缓存侧表（引用 SqlSemanticFragment/SqlTextFragment 的部分）")
            val trimmed = text.substring(0, start) + text.substring(end)
            // 3) 再删访问器（cachedListLineFragments / cachedHeader* 一族），保留 dedupeKey 起的纯逻辑。
            val accessorStart = trimmed.indexOf("    public List<List<SqlSemanticFragment>> cachedListLineFragments() {")
            val accessorEnd = trimmed.indexOf("    /**\n     * T-Perf-19（自 dev 移植）：去重键指纹", accessorStart)
            check(accessorStart > 0 && accessorEnd > accessorStart) {
                "$rel 的 UI 缓存访问器标记不见了——投影规则需复核"
            }
            note("$rel  # 删 UI 渲染缓存访问器（cached*Fragment / cachedHeader*）")
            val out = trimmed.substring(0, accessorStart) + trimmed.substring(accessorEnd)
            check(!stripComments(out).contains("SqlSemanticFragment")) { "$rel 投影后仍引用 SqlSemanticFragment" }
            check(!stripComments(out).contains("SqlTextFragment")) { "$rel 投影后仍引用 SqlTextFragment" }
            out
        }
        // 4) master `util/MpSqlVisualizerBundle.kt`：语言来自 PluginSettings、AUTO 落点来自
        //    DynamicBundle。k2j 形态与 master 的 Kotlin 源有三处**字面差**（均实测踩过，勿照搬）：
        //      a. import 行带 Java 结尾分号（Kotlin 源没有）。
        //      b. 语言读取是 `getState().getLanguage()`（Java getter），不是属性访问 `state.language`。
        //      c. `DynamicBundle.getLocale()` 在 k2j 的 try/catch 里，与 master 略不同。
        "$prefix/util/MpSqlVisualizerBundle.java" -> {
            val out = text
                .replace("import cn.song.mybatisplus.visualizer.config.PluginSettings;\n", "")
                .replace(
                    "PluginSettings.getInstance().getState().getLanguage()",
                    "\"zh\"",
                )
                .replace("com.intellij.DynamicBundle.getLocale()", "Locale.getDefault()")
            check(!stripComments(out).contains("com.intellij")) { "$rel 投影后仍带平台引用" }
            check(!stripComments(out).contains("PluginSettings")) { "$rel 投影后仍带平台服务引用" }
            note("$rel  # 语言来源换常量 zh、DynamicBundle.getLocale 换 Locale.getDefault")
            out
        }
        // 5) master `xml/SecureXml.kt`：唯一平台面是一行 warn 日志 → 换成 stderr（与 k2j 生产代码
        //    里已有的兜底分支同口径，不再 try/catch 包一层）。k2j 差异：import 带分号。
        "$prefix/xml/SecureXml.java" -> {
            val out = text
                .replace("import com.intellij.openapi.diagnostic.Logger;\n", "")
                .replace(
                    "        try {\n" +
                        "            Logger.getInstance(SecureXml.class).warn(message);\n" +
                        "        } catch (Throwable t) {\n" +
                        "            System.err.println(message);\n" +
                        "        }\n",
                    "        System.err.println(message);\n",
                )
            check(!stripComments(out).contains("com.intellij")) { "$rel 投影后仍带平台引用" }
            note("$rel  # 唯一 warn 日志换 stderr")
            out
        }
        else -> text
    }

    /** 顶层类型声明名（**列 0 起**——只取真顶层，不含类成员；包级私有顶层同样列 0）。 */
    private fun declarationsOf(code: String): Set<String> =
        DECLARATION_REGEX.findAll(code).map { it.groupValues[1] }.toSet()

    /**
     * 剥 Java 注释与字符/字符串字面量的转义处理（保留字面量本身——字符串里的类名也算代码形状，
     * 宁可多剔不可漏剔）。
     *
     * k2j 与 master（Kotlin 规则）的差别：
     *  - 块注释**不支持嵌套**：Kotlin 支持嵌套（`blockDepth` 计数），Java 遇第一个结束符即结束
     *    （改成 `inBlock` 布尔）。若沿用 Kotlin 规则，Java 的块注释会被误判成未闭合。
     *    （本行故意不写出字面开符：Kotlin 会把它当作嵌套注释开头，吞掉后续全部代码。）
     *  - 没有 `"""` 原始字符串（那是 Kotlin 语法），整条 `inRaw` 分支删除。
     *  - `"…"` 与 `'c'` 的转义规则相同（`\` 吞下一个字符），保留。
     */
    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inLine = false
        var inBlock = false
        var inString = false
        var inChar = false
        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else '\u0000'
            when {
                inLine -> {
                    if (c == '\n') { inLine = false; out.append('\n') }
                    i++
                }
                inBlock -> {
                    if (c == '*' && next == '/') { inBlock = false; i += 2 } else i++
                }
                inString -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < text.length) { out.append(next); i += 2; continue }
                    if (c == '"') inString = false
                    i++
                }
                inChar -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < text.length) { out.append(next); i += 2; continue }
                    if (c == '\'') inChar = false
                    i++
                }
                c == '/' && next == '/' -> { inLine = true; i += 2 }
                c == '/' && next == '*' -> { inBlock = true; i += 2 }
                c == '"' -> { inString = true; out.append(c); i++ }
                c == '\'' -> { inChar = true; out.append(c); i++ }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}

val prepareEngineSources = tasks.register<PrepareEngineSources>("prepareEngineSources") {
    sourceRoot.set(productionJavaRoot)
    packagePrefix.set("cn/song/mybatisplus/visualizer")
    // 08 分册 §D1 的 P0/P1 清单。k2j 实测 9 个包根**全部存在**（含 psource 6 件），一个都不删：
    // model 50 / sql 92 / xml 104 / reverse 16 / generator 6 / psource 6 / util 24 / log 147 /
    // execute 53，含子包共 500 件候选。
    packageRoots.set(listOf("model", "sql", "xml", "reverse", "generator", "psource", "util", "log", "execute"))
    // k2j 落点：DialectRegistry 的 `public Dialect decision(` 前是 `public Dialect effective(` 一族。
    dialectRegistryCutMarker.set("    /** {@link #effective(com.intellij")
    // 实测收敛 345 件（剔 155：platform-reference 116 / closure 19 / external-reference 14 /
    // external-import 6）。取 300 = 实测值打 87 折，留漂移余量但远高于「只留几十件」的失效态。
    minimumKeptFiles.set(300)
    outputDir.set(engineGeneratedSources)
}

sourceSets {
    main {
        // 本模块自有源码（JSON 门面 + 冒烟 main）。
        java.srcDir("src/main/java")
        // 机械裁剪出来的生产代码投影（任务依赖由 provider 自动推导）。
        java.srcDir(prepareEngineSources.flatMap { it.outputDir })
    }
}

// 消息 Bundle 走 UTF-8 properties（util/MpSqlVisualizerBundle 的 ResourceBundle 查找口径
// `messages.MpSqlVisualizerBundle`）；不打进资源时 message() 会回落键名，仍可用但与主仓文案不同。
tasks.processResources {
    from(rootProject.file("src/main/resources/messages")) {
        into("messages")
    }
}

// ---------------------------------------------------------------------------------------------
// 产物：mpsv-engine-<version>.jar（自包含：engine classes + kotlin-stdlib + jsqlparser + 两驱动）
// ---------------------------------------------------------------------------------------------

// JDBC 驱动服务声明：两个驱动各自带一份 META-INF/services/java.sql.Driver，
// duplicatesStrategy=EXCLUDE 只会保留先到的一份（实测 SQL Server 驱动从 DriverManager 消失、
// 报 "No suitable driver"）。这里把依赖里的服务文件排除，写一份合并声明，让
// DriverManager.getConnection（ServiceLoader）与 Class.forName 两条路都能找到驱动。
val jdbcDriverServices = layout.buildDirectory.dir("generated/jdbc-services")
val writeJdbcDriverServices = tasks.register("writeJdbcDriverServices") {
    val outDir = jdbcDriverServices
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("META-INF/services/java.sql.Driver").asFile
        file.parentFile.mkdirs()
        file.writeText("org.postgresql.Driver\ncom.microsoft.sqlserver.jdbc.SQLServerDriver\n")
    }
}

tasks.jar {
    archiveBaseName.set("mpsv-engine")
    archiveVersion.set(engineVersion.get())
    archiveClassifier.set("")
    manifest {
        attributes(
            "Implementation-Title" to "mpsv-engine",
            "Implementation-Version" to engineVersion.get(),
            "Built-From" to "mybatis-plus-sql-visualizer@${rootProject.version}",
            "Engine-Api" to "mpsv.engine.EngineApi",
            // P5 的 stdio 分发入口：产物已自包含，`java -jar` 即可逐行收发 JSON。
            "Main-Class" to "mpsv.engine.StdioMain",
        )
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // 自包含：宿主只需 java -cp mpsv-engine-x.jar（P5 的跨进程调用面不组装 classpath）。
    from({
        configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }
    }) {
        exclude(
            "META-INF/MANIFEST.MF",
            "META-INF/*.SF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
            "module-info.class",
            "META-INF/versions/**",
            // 两个驱动各带一份，改为下面合并声明。
            "META-INF/services/java.sql.Driver",
            // stdlib 传递的注解包只服务编译期（org.jetbrains:annotations 里同时带
            // org.intellij.lang.annotations）。运行时注解类缺失对 JVM 无影响；
            // 产物里不出现任何 intellij/jetbrains 注解命名的类型。
            "org/intellij/**",
            "org/jetbrains/annotations/**",
        )
    }
    from(jdbcDriverServices)
    dependsOn(writeJdbcDriverServices)
}

/** jar 的 sha256 落盘（build/libs/mpsv-engine-<version>.jar.sha256，`<hex>  <文件名>` 口径）。 */
abstract class WriteSha256 : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val inputFile: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun write() {
        val digest = MessageDigest.getInstance("SHA-256")
        inputFile.get().asFile.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        outputFile.get().asFile.writeText("$hex  ${inputFile.get().asFile.name}\n")
    }
}

val sha256Task = tasks.register<WriteSha256>("sha256") {
    inputFile.set(tasks.jar.flatMap { it.archiveFile })
    outputFile.set(layout.buildDirectory.file(engineVersion.map { "libs/mpsv-engine-$it.jar.sha256" }))
}

tasks.named("jar") { finalizedBy(sha256Task) }

// ---------------------------------------------------------------------------------------------
// 零平台渗透守卫
// ---------------------------------------------------------------------------------------------

/**
 * 解包产物 jar 的每个 `.class`，在 class 文件字节里查 `com/intellij` / `com.intellij`
 * （覆盖常量池的 UTF8 形态：全限定名、内部名、字符串字面量三种都可能出现）。命中即失败。
 *
 * 为什么不用 `unzip -l`/strings：源码级扫描只能看见源码形态；同包全限定名引用、常量折叠出的
 * 类名字符串只有 class 常量池看得见。与实现语言无关，从 master 原样保留。
 */
abstract class VerifyNoPlatformImports : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jarFile: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val jar = jarFile.get().asFile
        val hits = mutableListOf<String>()
        var classCount = 0
        ZipFile(jar).use { zip ->
            for (entry in zip.entries()) {
                if (entry.name.contains("/intellij/")) hits += "${entry.name} (entry)"
                if (!entry.name.endsWith(".class")) continue
                classCount++
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val text = String(bytes, Charsets.ISO_8859_1)
                if (text.contains("com/intellij") || text.contains("com.intellij") ||
                    text.contains("org/intellij")
                ) {
                    hits += entry.name
                }
            }
        }
        check(classCount > 0) { "产物 jar 里没有任何 .class：$jar" }
        // 门面必须真的在（防「扫了个空 jar 全绿」）。
        check(ZipFile(jar).use { it.getEntry("mpsv/engine/EngineApi.class") != null }) {
            "产物缺少 mpsv/engine/EngineApi.class——引擎门面没打进去"
        }
        if (hits.isNotEmpty()) {
            throw GradleException(
                "零平台渗透守卫失败：${hits.size} 个 class 引用了 com.intellij（$classCount 个 class 中）：\n" +
                    hits.take(40).joinToString("\n") { "  - $it" },
            )
        }
        report.get().asFile.apply { parentFile.mkdirs() }
            .writeText("ok: $classCount classes, 0 platform references\n")
    }
}

val verifyNoPlatformImports = tasks.register<VerifyNoPlatformImports>("verifyNoPlatformImports") {
    jarFile.set(tasks.jar.flatMap { it.archiveFile })
    report.set(layout.buildDirectory.file("reports/engine-no-platform.txt"))
}

tasks.named("check") { dependsOn(verifyNoPlatformImports) }
