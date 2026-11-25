import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.jvm.toolchain.JavaLanguageVersion

// T-OpenIDEAPort T0-0（2026-11-25）：纯逻辑引擎导出子项目。
//
// 目标：把主仓 P0 纯逻辑打成**不含任何 IntelliJ 类型**的 mpsv-engine jar，供下一代的非 IntelliJ
// 宿主（OpenIDEA / Rust 侧）经 JSON 门面（mpsv.engine.EngineApi）消费。
//
// 三条硬约束（与立项书 docs/开发计划/20261125-1530-...-P0-建仓与引擎导出-开发计划.md §1 一致）：
//  1. **不改任何生产源码**。源集不是手工抄一份，而是构建期由 prepareEngineSources 从
//     ../../src/main/kotlin 机械裁剪生成（含 5 处「投影」：去掉文件里零星的平台耦合，见任务 KDoc）。
//  2. 依赖面只有 jsqlparser + Kotlin stdlib。
//  3. 产物零平台渗透由 verifyNoPlatformImports 逐 class 常量池扫描守住（命中即构建失败）。

plugins {
    kotlin("jvm")
}

// 引擎版本与插件版本解耦（插件版本 = 发布日期；引擎是独立制品，语义不同，不进同一条演进线）。
val engineVersion = providers.gradleProperty("engineVersion").orElse("0.1.0")

// 字节码目标压到 11（可跑在 JDK 11+ 宿主）；工具链用本机既有的 JDK 17（与根项目同款）——
// 本机无 JDK 11，jvmToolchain(11) 会触发 foojay 下载。API 面用 -Xjdk-release=11 收窄到 JDK 11，
// 语义等价于「以 11 为目标编译」。
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
        freeCompilerArgs.add("-Xjdk-release=11")
    }
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
    // 根 gradle.properties 设 kotlin.stdlib.default.dependency=false（插件依赖平台捆绑 stdlib）；
    // 本模块是独立制品，必须自带 stdlib 才能被任意 JVM 宿主加载。stdlib 的编译期注解传递依赖
    // org.jetbrains:annotations 必须留在 classpath——从编译面摘掉会让编译器读 stdlib inline 函数时
    // 抛 NoClassDefFoundError: org/jetbrains/annotations/NotNull（实测 ICE），故随 stdlib 一并进产物。
    implementation(kotlin("stdlib"))

    // T5-5 真连库 EXPLAIN：两个方言的官方驱动，随 jar 自包含（许可见主仓 THIRD-PARTY-NOTICES.md）。
    // 版本与主仓 `gradle/libs.versions.toml` 的 testImplementation 同值，本机 Gradle 缓存已有。
    implementation("org.postgresql:postgresql:42.7.1") {
        // checker-qual 只服务 pgjdbc 的编译期注解（class 文件里无运行期引用），不打进自包含产物。
        exclude(group = "org.checkerframework", module = "checker-qual")
    }
    // `-jre11` 变体的字节码是 Java 11，与引擎的 -Xjdk-release=11 目标一致。
    implementation("com.microsoft.sqlserver:mssql-jdbc:12.4.2.jre11")
}

// ---------------------------------------------------------------------------------------------
// 源集裁剪：把生产源码按「代码零平台引用 + 引用闭包自洽」机械投影到 build/generated/engine-src。
// ---------------------------------------------------------------------------------------------

/** 主仓 Kotlin 源根（只读；构建只读它，从不写回）。 */
val productionKotlinRoot: Directory = rootProject.layout.projectDirectory.dir("src/main/kotlin")
val engineGeneratedSources: Provider<Directory> = layout.buildDirectory.dir("generated/engine-src")

/**
 * 从生产源码生成引擎源集。规则（全部机械判定，人工只提供包根白名单与 5 处投影）：
 *
 * 1. **代码级平台引用**（注释先剥掉，KDoc 提到 `com.intellij` 不算耦合）→ 整文件剔除；
 *    08 分册的「纯逻辑」是 import 文本判定，漏掉了同包全限定名引用（`sql/Dialect.kt` 实例）——
 *    本任务不看 import，看剥注释后的文本，天然覆盖该盲区。
 * 2. **import 白名单**：只允许 kotlin./java./javax./net.sf.jsqlparser/org.w3c./org.xml./
 *    以及 cn.song.mybatisplus.visualizer 下**在包根白名单内**的包。平台自带但不在依赖面的库
 *    （Gson、kotlinx-serialization、Spring、MyBatis、JetBrains annotations）一并剔除。
 * 3. **引用闭包收敛**：被剔除文件里声明的顶层类型/函数，若被某个保留文件在代码里引用，则该保留文件
 *    也剔除（同包同名不需要 import，编译器看不见、文本扫描看得见）。迭代到不动点。
 * 4. **5 处投影**（生产文件整体不可用、但只差一条缝的）：
 *    - `sql/Dialect.kt`：Dialect 接口与 15 个方言对象纯；平台耦合全在文件尾部 DialectRegistry 的
 *      decision/effective/... 一组读项目与 PSI 的成员 → 截断于首个 `    fun decision(` 并补 `}`。
 *    - `log/LogSqlModels.kt`：LogSqlEntry 的 UI 渲染缓存字段（类型 IElementType/SimpleTextAttributes
 *      来自 UI 侧）→ 删掉 `T-ListMode` 到「脱敏展示前…」之间那段体字段（UI 专用，引擎不需要）。
 *    - `log/SqlSourceMarker.kt`：只保留 `SourceMarkerData` + `SourceMarkers`（MP 分页 COUNT 标记，
 *      被 LogSqlModels 的去重融合直接调用）——其余段引用拦截器模板等平台相邻类型。
 *    - `util/MpSqlVisualizerBundle.kt`：语言来自 PluginSettings、`AUTO` 落点来自 DynamicBundle、
 *      参数注解来自 JetBrains annotations → 三处换成本地等价的 zh/`Locale.getDefault()`、去注解。
 *    - `xml/SecureXml.kt`：唯一平台面是一行 warn 日志 → 换成 no-op。
 *    投影后**再断言零平台引用**，不干净就构建失败（不允许静默退回剔除）。
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
    abstract val dialectCutMarker: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    private companion object {
        /**
         * 引擎依赖面之外的类型引用（含内联全限定名，故按文本匹配而非只看 import）：
         * 平台 `com.intellij`、平台自带库（Gson / Baomidou / Spring / JetBrains annotations / kotlinx）、
         * 以及 JDK 桌面面（AWT / Swing / ImageIO——引擎是无头制品，不进 UI 渲染类）。
         */
        val EXTERNAL_REF_REGEX = Regex(
            """(?<![\w.])((?:com\.intellij|com\.google|com\.baomidou|org\.apache|org\.springframework|""" +
                """org\.jetbrains|org\.intellij|kotlinx|com\.sun|java\.awt|javax\.swing|javax\.imageio)\.[\w.]+)""",
        )

        /** 标识符 token（闭包收敛时的引用判定口径）。 */
        val TOKEN_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }

    @TaskAction
    fun prepare() {
        val srcRoot = sourceRoot.get().asFile
        val outRoot = outputDir.get().asFile
        outRoot.deleteRecursively()
        outRoot.mkdirs()

        val prefix = packagePrefix.get()
        val roots = packageRoots.get().toSet()
        val allowedPrefixes = listOf(
            "kotlin.", "java.", "javax.", "net.sf.jsqlparser.", "org.w3c.", "org.xml.",
        )

        // 1) 读入全部候选文件（rel → 原始文本）
        val candidates = LinkedHashMap<String, String>()
        roots.sorted().forEach { root ->
            val dir = File(srcRoot, "$prefix/$root")
            check(dir.isDirectory) { "引擎包根不存在：$dir（08 分册 D1 清单漂移？）" }
            dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.sorted().forEach { file ->
                // CRLF → LF 归一（主仓生产文件是 CRLF；投影规则按行匹配，统一后再改，输出也统一为 LF）。
                candidates[file.relativeTo(srcRoot).invariantSeparatorsPath] =
                    file.readText().replace("\r\n", "\n")
            }
        }

        // 2) 投影：先改写，再走平台检查（投影后仍带 com.intellij 就直接失败，不静默剔除）
        val projected = LinkedHashMap<String, String>()
        val projectionNotes = mutableListOf<String>()
        candidates.forEach { (rel, raw) ->
            val out = project(rel, raw, prefix) { projectionNotes += it }
            projected[rel] = out
        }

        // 3) 逐文件初筛：平台引用 + import 白名单
        val excluded = LinkedHashMap<String, String>() // rel -> reason
        val kept = LinkedHashMap<String, String>()     // rel -> 剥注释文本
        projected.forEach { (rel, text) ->
            val code = stripComments(text)
            // 平台类型（含同包全限定名写法）与「平台自带但不在引擎依赖面」的库一律剔除；
            // 后者常以全限定名内联出现（不写 import），单看 import 抓不到（ExplainIndexAdvisor 实例）。
            val external = EXTERNAL_REF_REGEX.find(code)?.groupValues?.get(1)
            if (external != null) {
                excluded[rel] = if (external.startsWith("com.intellij")) "platform-reference" else "external-reference:$external"
                return@forEach
            }
            val badImport = Regex("""(?m)^\s*import\s+([\w.]+)""").findAll(code)
                .map { it.groupValues[1] }
                .firstOrNull { fq ->
                    allowedPrefixes.none { fq.startsWith(it) } &&
                        !(fq.startsWith(prefix.replace('/', '.') + ".") &&
                            fq.removePrefix(prefix.replace('/', '.') + ".").substringBefore('.').substringBefore('/') in roots)
                }
            if (badImport != null) {
                excluded[rel] = "external-import:$badImport"
                return@forEach
            }
            kept[rel] = code
        }

        // 4) 引用闭包收敛：剔除文件的顶层声明被保留文件引用 → 连带剔除。
        //    工作队列版（不做「每轮全量重扫」）：先建 name→声明文件 索引与每文件的标识符 token 集，
        //    某个名字的声明文件全部被剔后入队；处理时按 token 集命中即剔除，并把新剔文件声明的名字
        //    重新判定入队。生产源码里同包同名不需要 import，编译器看不见、文本扫描看得见。
        val declarationsByFile = HashMap<String, Set<String>>()
        val tokensByFile = HashMap<String, Set<String>>()
        val declIndex = HashMap<String, MutableSet<String>>() // name -> 声明它的文件
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
        check(kept.size >= 60) {
            "引擎源集只剩 ${kept.size} 个文件（预期 ≥60）——裁剪规则疑似失效，拒绝产出空引擎" +
                "（明细见 ${File(outRoot, "engine-sources.txt")}）"
        }
    }

    /** 5 处投影；`note` 写进清单便于审计。返回的文件若仍带平台引用，步骤 3 会把它剔除——故投影自带断言。 */
    private fun project(rel: String, text: String, prefix: String, note: (String) -> Unit): String = when (rel) {
        "$prefix/sql/Dialect.kt" -> {
            val marker = "\n" + dialectCutMarker.get()
            val cut = text.indexOf(marker)
            check(cut > 0) { "$rel 未找到截断标记 `${dialectCutMarker.get()}`——生产文件结构变了，投影规则需复核" }
            note("$rel  # 截断 DialectRegistry 平台成员（保留 dialects/byName）")
            text.substring(0, cut) + "\n}\n"
        }
        "$prefix/log/LogSqlModels.kt" -> {
            val start = text.indexOf("    // ---------- T-ListMode")
            val end = text.indexOf("    /** 脱敏展示前先处理完整 SQL")
            check(start > 0 && end > start) { "$rel 的 UI 缓存块标记不见了——投影规则需复核" }
            note("$rel  # 删 LogSqlEntry 的 UI 渲染缓存体字段（T-ListMode 段）")
            text.substring(0, start) + text.substring(end)
        }
        "$prefix/util/MpSqlVisualizerBundle.kt" -> {
            var out = text
                .replace("import cn.song.mybatisplus.visualizer.config.PluginSettings\n", "")
                .replace("import org.jetbrains.annotations.PropertyKey\n", "")
                .replace("PluginSettings.getInstance().state.language", "\"zh\"")
                .replace("com.intellij.DynamicBundle.getLocale()", "Locale.getDefault()")
            out = Regex("""@PropertyKey\([^)\n]*\)\s*""").replace(out, "")
            check(!stripComments(out).contains("com.intellij")) { "$rel 投影后仍带平台引用" }
            note("$rel  # 语言/注解/DynamicBundle 三处换本地等价物")
            out
        }
        "$prefix/log/SqlSourceMarker.kt" -> {
            // 该文件的 `SourceMarkers`（MP 分页 COUNT 特殊来源标记）是模型层纯逻辑，被 LogSqlModels 的
            // 去重融合直接调用；其余部分（标记行格式、拦截器模板协作）引用了拦截器模板与平台相邻类型。
            val idx = text.indexOf("/**\n * 标记行解析结果")
            check(idx > 0) { "$rel 的 SourceMarkerData 段标记不见了——投影规则需复核" }
            note("$rel  # 只保留 SourceMarkerData + SourceMarkers（MP 分页 COUNT 标记）")
            "package ${prefix.replace('/', '.')}.log\n\n" + text.substring(idx)
        }
        "$prefix/xml/SecureXml.kt" -> {
            val out = text
                .replace("import com.intellij.openapi.diagnostic.Logger\n", "")
                .replace("Logger.getInstance(SecureXml::class.java).warn(message)", "Unit")
            check(!stripComments(out).contains("com.intellij")) { "$rel 投影后仍带平台引用" }
            note("$rel  # 唯一 warn 日志换 no-op")
            out
        }
        else -> text
    }

    /** 顶层声明名（类/对象/接口/typealias/顶层函数，**列 0 起**——只取真顶层，不含类成员）。 */
    private fun declarationsOf(code: String): Set<String> {
        val typeName = Regex(
            """(?m)^(?:(?:public|internal|private|protected|abstract|open|data|sealed|enum|""" +
                """annotation|value|expect|actual|override)\s+)*(?:class|interface|object|typealias)\s+""" +
                """([A-Za-z_][A-Za-z0-9_]*)""",
        )
        // 扩展函数形如 `fun Receiver.name()`——取最后一个点后的真函数名，避免把接收者类型当声明名。
        val funName = Regex(
            """(?m)^(?:(?:public|internal|private|protected|abstract|open|inline|suspend|operator|""" +
                """infix|tailrec|expect|actual|override)\s+)*fun\s+(?:<[^>\n]*>\s*)?""" +
                """(?:[A-Za-z_][A-Za-z0-9_.<>?, ]*\.)?([A-Za-z_][A-Za-z0-9_]*)""",
        )
        return (typeName.findAll(code).map { it.groupValues[1] } +
            funName.findAll(code).map { it.groupValues[1] }).toSet()
    }

    /**
     * 剥 Kotlin 注释与字符/字符串字面量的转义处理（保留字面量本身——字符串里的类名也算代码形状，
     * 宁可多剔不可漏剔）。块注释按 Kotlin 规则支持嵌套。
     */
    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var blockDepth = 0
        var inLine = false
        var inString = false
        var inRaw = false
        var inChar = false
        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else '\u0000'
            when {
                inLine -> {
                    if (c == '\n') { inLine = false; out.append('\n') }
                    i++
                }
                blockDepth > 0 -> {
                    when {
                        c == '/' && next == '*' -> { blockDepth++; i += 2 }
                        c == '*' && next == '/' -> { blockDepth--; i += 2 }
                        else -> i++
                    }
                }
                inRaw -> {
                    if (c == '"' && next == '"' && i + 2 < text.length && text[i + 2] == '"') {
                        inRaw = false; out.append("\"\"\""); i += 3
                    } else { out.append(c); i++ }
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
                c == '/' && next == '*' -> { blockDepth = 1; i += 2 }
                c == '"' && next == '"' && i + 2 < text.length && text[i + 2] == '"' -> { inRaw = true; out.append("\"\"\""); i += 3 }
                c == '"' -> { inString = true; out.append(c); i++ }
                c == '\'' -> { inChar = true; out.append(c); i++ }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}

val prepareEngineSources = tasks.register<PrepareEngineSources>("prepareEngineSources") {
    sourceRoot.set(productionKotlinRoot)
    packagePrefix.set("cn/song/mybatisplus/visualizer")
    // 08 分册 §D1 的 P0/P1 清单：model / sql / xml(+ibatis) / reverse / generator / psource 纯类 /
    // util 纯类，加 log / execute（门面三函数所需：日志还原、写风险、性能规则）。
    // converter / psi / config / context / ui / mcp / history / share 不入（PSI 输入或平台服务）。
    packageRoots.set(listOf("model", "sql", "xml", "reverse", "generator", "psource", "util", "log", "execute"))
    dialectCutMarker.set("    fun decision(")
    outputDir.set(engineGeneratedSources)
}

sourceSets {
    main {
        // 本模块自有源码（JSON 门面 + 冒烟 main）。
        kotlin.srcDir("src/main/kotlin")
        // 机械裁剪出来的生产代码投影（任务依赖由 provider 自动推导）。
        kotlin.srcDir(prepareEngineSources.flatMap { it.outputDir })
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
// 产物：mpsv-engine-<version>.jar（自包含：engine classes + Kotlin stdlib + jsqlparser）
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
            // P5 的 stdio 分发入口：产物已自包含（stdlib + jsqlparser），`java -jar` 即可逐行收发 JSON。
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
            // stdlib 传递的注解包只服务编译期（org.jetbrains:annotations 里同时带 org.intellij.lang.annotations）。
            // 运行时注解类缺失对 JVM 无影响；产物里不出现任何 intellij 命名的类型。
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
 * 类名字符串只有 class 常量池看得见（本轮 Dialect.kt 即该盲区实例）。
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
                if (text.contains("com/intellij") || text.contains("com.intellij") || text.contains("org/intellij")) {
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
        report.get().asFile.apply { parentFile.mkdirs() }.writeText("ok: $classCount classes, 0 platform references\n")
    }
}

val verifyNoPlatformImports = tasks.register<VerifyNoPlatformImports>("verifyNoPlatformImports") {
    jarFile.set(tasks.jar.flatMap { it.archiveFile })
    report.set(layout.buildDirectory.file("reports/engine-no-platform.txt"))
}

tasks.named("check") { dependsOn(verifyNoPlatformImports) }