import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.time.Duration

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
    // T-BuildScriptSplit：构建逻辑分治——convention 插件见 buildSrc/src/main/kotlin/，
    // 块级逐字搬运自本文件（锚点 4c3e268，注释随迁），来源与去向见计划 §2.1。
    id("mpv-verification")
    id("mpv-testing")
    id("mpv-runide")
    id("mpv-release")
}

// T-GifRecordSwitch（2026-09-11）：演示 GIF 录制环境开关。录制需在 2026.2 沙盒运行（官方同款
// 暗色基准 + record/ 脚本管线），但 262 下 test 源码编译不过——com.intellij.diagram.* 于 262 移入
// Database 插件（测试 classpath 未含），且新版 Kotlin 把 String.toLowerCase() 升为硬错误，导致
// 分支自身无法跑 ./gradlew test。故把录制所需四项配置整体收敛为本开关：
//   -PgifRecord=true  -> 262 录制态（平台 2026.2.1 / toolchain 24 / jcef / 独立沙盒 MPSV-GifFresh）
//   -PgifRecord=false -> 243 常规开发态（平台 2024.3.7.1 / toolchain 17 / 无 jcef / 默认沙盒）
// 四项必须整体切换、不可拆：jcef 自 262 起才从平台核心拆为独立模块（243 distro 无此模块，
// 单独声明会导致依赖解析失败）；jvmToolchain(24) 用于读 262 平台 v69 类文件（javac 17/21 报
// 「错误的版本 69.0」）；独立沙盒避免与并行任务互锁。
//
// 【2026-09-20 合回 master 已还原】录制分支 t-gif-rec-mcp 曾把默认值翻为 true（便于直接
// `./gradlew runIde` 起 2026 沙盒）；该分支自带说明要求「合回 master 前必须把默认值还原为
// false，否则 262 录制态会静默成为默认构建基准」——本行即该还原（副作用同步消失：默认态下
// `./gradlew test` 因 262 限制不可用的问题不再存在）。
val gifRecord = providers.gradleProperty("gifRecord").orNull?.toBoolean() ?: false

// 闸门：录制开关若与发布/打包任务同用，配置期直接失败——防止 262 录制态流入发行产物。
// 与既有 verifyIdes 同款写法（读 startParameter.taskNames），兼容 configuration-cache。
// 局限：仅拦截「命令行直接点名」的发布任务（./gradlew buildPlugin 等）；经依赖间接触达的不拦。
if (gifRecord) {
    val releaseTasks = setOf("buildPlugin", "publishPlugin", "publish")
    val invoked = gradle.startParameter.taskNames
        .map { it.substringAfterLast(':') }
        .filter { it in releaseTasks }
    if (invoked.isNotEmpty()) {
        throw GradleException(
            "gifRecord=true 是演示录制专用（构建基准 2026.2.1），禁止用于发布/打包任务 $invoked。" +
                "本分支默认即录制态，请改用 -PgifRecord=false 重试。"
        )
    }
}

// T-McpToolset / T-AcpMcpTransport：平台可选插件编译期 API 桩源集。
// 基线平台 243 不含 mcpServer 插件、也不含 AI Assistant（com.intellij.ml.llm），而本机 262 的
// mcpserver.jar 与 ml-llm 模块 jar 类文件都是 major 69（Java 25），JDK 17 编译读不了
// （javac 17/21/24 报「错误的版本 69.0」，T-GifRecordSwitch 注释同源实证）——故用桩源码提供与
// 真实 jar 完全一致的编译期签名（签名逐条反编译自 262 jar，见各桩文件头注释）。
// 源集名沿用 mcpStub 未改：testShards 的桩挂载在 buildSrc/mpv-testing.gradle.kts 内按此名引用
// （T-McpToolset2 接线），改名会牵动 convention 插件；本任务只**扩内容**（新增
// src/mcpStub/kotlin/com/intellij/ml/llm/... 三个桩类型）而不动接线，故零 buildSrc 改动。
// 接线方式（防 task 依赖环）：
//  - 主编译任务 compileKotlin.libraries 挂桩产物目录（task 依赖：compileKotlin → compileKotlinMcpStub）；
//  - 桩源集编译类路径复用 main 的 compileClasspath（平台 jar/stdlib/JSqlParser 等，不含桩产物，
//    天然无环——若桩 classpath 反向含 main.compileClasspath 且 main 含桩产物则成环）；
//  - 桩产物只进编译 classpath，**不进运行时与打包产物**（运行时类型由平台内置插件提供，
//    253+/262 内置；插件 jar 携带 com.intellij.mcpserver.* 会与平台类冲突）。
val mcpStub: SourceSet by sourceSets.creating

sourceSets {
    named("mcpStub") {
        compileClasspath += sourceSets.main.get().compileClasspath
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)
    // T-M23.13：真实 MP jar 进测试 classpath（Heavy 语义黄金用例——方法签名/注解元数据可解析；
    // 仅测试依赖，插件运行时零依赖不变；MPJ 1.5.x 不在 Central，暂不加）
    testImplementation(libs.mp.core)
    // P-立项-05：方言真实验证——H2 嵌入式（兼容模式模拟 MySQL/MariaDB/PG/Oracle/MSSQLServer 语法）
    // 与 sqlite-jdbc 内嵌原生 SQLite 引擎。仅测试 classpath，插件运行时零依赖不变。
    testImplementation(libs.h2)
    testImplementation(libs.sqlite.jdbc)
    // P-立项-05 Phase 2：Docker 真实环境验证（Testcontainers + 各库 JDBC 驱动，仅测试 classpath）。
    // 本机无 Docker 时 DialectDockerVerificationTest 自动 Assume 跳过；依赖照常解析不影响 CI。
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.mssqlserver)
    testImplementation(libs.testcontainers.oracle.xe)
    testImplementation(libs.mysql.connector)
    testImplementation(libs.postgresql)
    testImplementation(libs.mssql.jdbc)
    testImplementation(libs.mariadb.java.client)
    testImplementation(libs.ojdbc11)
    // T-XcDialects3：信创三族驱动——DialectDockerVerificationTest 外部实例模式
    //（-Dmpvs.dialect.jdbcUrl=... 指向 103.36.220.245 自建实例 / DBX 金仓）渲染套件真执行用；
    // 仅测试 classpath，插件运行时零依赖不变（与 ojdbc11 同口径）
    testImplementation(libs.highgo.jdbc)
    testImplementation(libs.vastbase.jdbc)
    testImplementation(libs.kingbase.jdbc)
    // T-DialectDriverGap（2026-10-30）：达梦 / openGauss / OceanBase 外部实例验证驱动。
    // openGauss 取 `-og` 变体（org.opengauss.Driver）；无后缀变体是 pgjdbc 改名包会遮蔽真 PG 驱动。
    testImplementation(libs.dameng.jdbc)
    testImplementation(libs.opengauss.jdbc)
    testImplementation(libs.oceanbase.client)
    // T-DialectLabMatrix：配方一致性守卫的 YAML 解析（仅测试 classpath）
    testImplementation(libs.snakeyaml)
    // T-TailSweep2 T-FS-01：真 MyBatis 引擎语义对照测试集（真引擎 jar 仅进**测试 classpath**）。
    // 来源：真引擎 jar 经 `testImplementation(libs.mp.core)`（com.baomidou:mybatis-plus-core:3.5.17）
    // 传递带入 `org.mybatis:mybatis:3.5.19`，**无需显式声明**——实测
    // `./gradlew dependencies --configuration testRuntimeClasspath` 可复核（该配置上无直接依赖项）。
    // 红线边界：零 mybatis 依赖仅约束 `implementation`（生产依赖图不变，生产仍只有 jsqlparser）。
    // 真引擎类一律经隔离 classloader 反射驱动，测试源码零 `org.apache.ibatis` import
    // （见 xml/fidelity/RealEngineDriver.kt）；传递版本由对照集内的版本钉住断言防静默漂移。

    // P-立项-05：jackson 版本约束——testcontainers 传递的 jackson-annotations 2.10.3 缺失
    // JsonProperty.namespace()（2.11+ 才有），在测试 classpath 上遮蔽 IDEA 平台所用的新版
    // jackson，导致平台 XML PSI 代码 NoSuchMethodError。三件套统一对齐 2.17.1
    // （testcontainers 1.19.3 兼容；jackson-annotations 向后兼容）。
    constraints {
        testImplementation("com.fasterxml.jackson.core:jackson-annotations:2.17.1") {
            because("IDEA 平台调用 JsonProperty.namespace()，testcontainers 传递的 2.10.3 缺失该方法")
        }
        testImplementation("com.fasterxml.jackson.core:jackson-core:2.17.1")
        testImplementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    }
    // F-1/F-xx：JOIN 关系可视化图（自绘 Swing Graphics2D 渲染，零外部依赖）
    // F-xx 替代方案：移除 PlantUML 1.2024.8 (21.9MB) + Batik (~8MB)，改用自绘引擎
    // F-2：SQL 性能分析（JSqlParser 5.3 — 零依赖 SQL 解析器，静态分析用）
    implementation("com.github.jsqlparser:jsqlparser:5.3") {
        // T-LicenseCompliance（20261021）：上游 POM 把 org.openjdk.jmh:jmh-core 声明为编译期
        // 传递依赖（仅其基准测试用——jar 内 0 个类引用 org/openjdk/jmh，已逐类核对），会导致
        // jmh-core、jopt-simple、commons-math3 三件套约 2.8MB 被打进插件运行期 lib（打包泄漏，
        // 2026-09-20 验收文档已标注待确认）。显式排除。
        exclude(group = "org.openjdk.jmh", module = "jmh-core")
    }

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // M17：构建平台 = **最低支持版本 Ultimate 2024.3.7.1**（T-M30：最低兼容降至 IDEA 243/2024.3）。
        // 官方兼容策略：以最低版本为编译基准，公共 API 向后兼容保证 2025.x/2026.x 可运行；
        // Plugin Verifier 按目标 IDE 集合校验。database API 在 com.intellij.database（仅 Ultimate）。
        // Kotlin 规则（T-M30）：多版本支持须以最低捆绑 stdlib 为编译目标——2024.3 捆绑 stdlib 2.0.21，
        // 平台 distro 进编译 classpath 后自动满足（kotlin.stdlib.default.dependency=false 不打包 stdlib）。
        // 注：本机日常 IDE 为 Ultimate 2026.2.3（2026-09-17 起，T-VerifyIde2623），243 平台需下载（gradle.properties 已配代理）。
        intellijIdeaUltimate(if (gifRecord) "2026.2.1" else "2024.3.7.1")
        testFramework(TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here:
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        // T-GifRecordSwitch：JCEF 自 262 起才从平台核心拆为独立模块（243 distro 无此模块），
        // 故仅录制态声明；常规态声明会导致依赖解析失败。
        if (gifRecord) {
            bundledPlugin("com.intellij.modules.jcef")
        }
        // T-M24-04：XML 平台模块（XML PSI 定位语句 + XML gutter；平台 bundled，IDE 恒启用）。
        // T-M30：构建基准降至 2024.3 后 XML PSI 类在平台核心（app.jar，lib/modules 无独立模块），
        // bundledModule("intellij.xml.psi") 在 2024.3 distro 中不存在（253+ 才拆分为该模块）——移除声明，
        // 编译期类来自平台核心，运行期 253+ 由平台 XML 模块提供（com.intellij.modules.xml 依赖已声明）。
        // bundledModule("intellij.xml.psi")
        // M17：Database 插件（数据源/控制台 API，仅 Ultimate 捆绑）。
        // 注：2026.2 中 SQL 语言支持（com.intellij.sql）已并入平台核心，无需/无法单独声明
        bundledPlugin("com.intellij.database")
        // T-Dgm-01：Diagrams 插件（类图/关系图底座，仅 Ultimate 捆绑）——JOIN 图原生渲染编译期类。
        // 与 database 同为运行期可选依赖（plugin.xml optional depends + diagram-optional.xml EP 注册）。
        bundledPlugin("com.intellij.diagram")

        // T-GifRec-MCP（2026-09-19）：录制态把 AI Assistant 装进沙盒。AI Assistant 不是 distro
        // 内置插件（262 distro 163 个插件里无 ml-llm），而 M1-c 分镜要用它的 ACP 面板驱动 agent。
        // 必须声明式安装：prepareSandbox 每次重建沙盒 plugins/ 目录，手工拷贝进去的插件会被清掉（实测）。
        // 版本与沙盒平台精确对齐——插件约束为 since-build 262.9437.1 / until-build 262.9437.*。
        // T-GifRec-MCP（2026-09-19）：录制态把 AI Assistant 装进沙盒。AI Assistant 不是 distro
        // 内置插件（262 distro 163 个插件里无 ml-llm），而 M1-c 分镜要用它的 ACP 面板驱动 agent。
        // 必须声明式安装：prepareSandbox 每次重建沙盒 plugins/ 目录，手工拷贝进去的插件会被清掉（实测）。
        // 版本与沙盒平台精确对齐——插件约束为 since-build 262.9437.1 / until-build 262.9437.*。
        //
        // 2026-09-20 复启说明：本声明会把真 ml-llm 带进编译类路径，曾与 T-AcpMcpTransport 的桩冲突
        // （桩把 AcpAgentId 误置于 com.intellij.ml.llm.agents.acp.api，真接口用平台的
        // com.intellij.platform.acp.AcpAgentId）→ override 失配。已修正桩的归属（迁至平台包），
        // 冲突消除，本声明复启。
        if (gifRecord) {
            plugin("com.intellij.ml.llm", "262.9437.276")
        }
        // 录制态可选：把 robot-server 作为本地插件依赖交给 prepareSandbox 一起安装，
        // 不覆盖 idea.plugins.path（覆盖会让主插件与 AI Assistant 消失）。
        // 首次使用先运行 record/bootstrap-robot-server.py 下载到被忽略的本地目录。
        // T-DevFullUI：-PgifRobotServer=true 独立生效（不再要求 gifRecord 同开——
        // gifRecord 会切 262 平台与 GifFresh 沙盒，UI243 验收沙盒用不了）。
        if (providers.gradleProperty("gifRobotServer").orNull == "true") {
            val robotServer = file("record/robot-plugins/robot-server-plugin")
            require(robotServer.resolve("lib").isDirectory) {
                "缺少 robot-server：先运行 python record/bootstrap-robot-server.py"
            }
            localPlugin(robotServer)
        }
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// F-xx：已移除 Batik/PlantUML 依赖，无需 xml-apis 排除规则。

// 纯 Kotlin 插件：无需 @NotNull 字节码插桩（Kotlin 编译器已处理空安全）。
// 禁用后可绕开 JDK 环境下 instrumentCode 的 jrt 模块解析问题（Windows 中文环境实测报
// "java.home/Packages does not exist"）。若后续引入 Java 源码或 GUI Forms 再评估开启。
intellijPlatform {
    instrumentCode = false
    // Windows 中文环境实测 buildSearchableOptions 报 "Locale must be default"（IDEA
    // 搜索索引器要求 JVM locale 为默认值）。禁用后 Settings 页面不进入 IDE 设置搜索
    // 索引（其余功能不受影响）；在非中文 locale 环境可改回 true 以获得设置搜索支持。
    buildSearchableOptions = false

    // T-GifRecordSwitch：录制态用独立沙盒，避免与并行任务（record/ 脚本、其他会话）
    // 的沙盒/索引互锁；常规态用默认沙盒。
    if (gifRecord) {
        sandboxContainer = layout.buildDirectory.dir("MPSV-GifFresh")
    }
    // 注：并行会话临时隔离请在本块内临时加自己的 sandboxContainer（用后还原并立即提交），
    // 勿让任务专属沙盒名随提交入库（T-ListParamCollect/T-HqlInhCols 两轮实证）。
}

kotlin {
    // T-GifRecordSwitch：常规态 243 平台用 17；录制态 262 平台类文件为 v69，
    // 须 javac 24 才能读（javac 17/21 报「错误的版本 69.0」）。
    jvmToolchain(if (gifRecord) 24 else 17)
}

// T-McpToolset：主编译 classpath 追加 MCP 桩产物（详见文件头 mcpStub 源集注释）。
// 只挂编译任务 libraries，不动 runtime classpath/打包——桩不进插件 jar。
// T-McpToolset2：test 编译同样需要桩（工具级门控测试直接实例化 Tool 类——其超类型
// McpToolset 来自桩源集）；依旧不进 runtime/打包。
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    libraries.from(sourceSets.named("mcpStub").map { it.output })
}
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    libraries.from(sourceSets.named("mcpStub").map { it.output })
}
tasks.named<Test>("test") {
    classpath += files(sourceSets.named("mcpStub").map { it.output })
}

// Java 源文件使用 UTF-8 编码（与 Kotlin 一致）；Windows 默认 GBK 会导致中文注释/字符串编译失败
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// M14.7：开发期热重载——runIde 沙盒中修改代码即时生效（要求插件支持动态加载）
// T-M23.10 内部构建号（用户确认保留，独立于已回退的检测功能）：jar manifest 注入
// MPV-Build（执行期毫秒时间戳，每次构建唯一）——对外版本号一日一版（同日多次构建相同），
// 构建号用于区分同日构建与问题定位；对所有 Jar 任务注入（jar + composedJar——
// buildPlugin 最终产物由 composedJar 生成，只配 jar 会导致 zip 内 jar 丢失该属性），
// 强制每次执行（刷新时间戳）。
tasks.withType<Jar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    doFirst {
        manifest.attributes["MPV-Build"] = System.currentTimeMillis().toString()
    }
    outputs.upToDateWhen { false }
}

// ---------- T-LicenseCompliance（20261021）：分发包许可合规 ----------
// ① 插件主 jar 的 META-INF/ 打进 LICENSE 与 THIRD-PARTY-NOTICES（源码单点，避免两处副本漂移）；
// ② buildPlugin zip 的插件目录同级再放一份（IDE 插件包惯例，安装后可见）。
// 守卫：convention/DistributionComplianceGuardTest（源码级钉住本接线与 jsqlparser 的 jmh-core 排除）。
tasks.named<ProcessResources>("processResources") {
    from(layout.projectDirectory.file("LICENSE")) { into("META-INF") }
    from(layout.projectDirectory.file("THIRD-PARTY-NOTICES.md")) { into("META-INF") }
}
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.BuildPluginTask>("buildPlugin") {
    // buildPlugin 的根 dest 已是 archiveBaseName（插件目录名），此处不写 into 即落在
    // <插件目录>/LICENSE 与 <插件目录>/THIRD-PARTY-NOTICES.md（写 into 会多套一层同名目录，20261021 实测）。
    from(layout.projectDirectory.file("LICENSE"))
    from(layout.projectDirectory.file("THIRD-PARTY-NOTICES.md"))
}

// T-BuildScriptSplit：root 收口——tasks{} 块已全部分治（patchPluginXml/writeUpdateManifest →
// mpv-release；runide 四块 → mpv-runide；verify 三任务 → mpv-verification），此块撤销。

// T-ReleasePerf-02（P0-2）：releaseGate 的显式串行顺序——dependsOn 只保证「都执行」不保证先后，
// mustRunAfter 才钉死时序（buildPlugin → verifyGate → testShards）。放 root body（各 convention
// 应用之后执行）——verifyGate/testShards 分属 mpv-verification/mpv-testing 注册，此处天然晚于两者。
tasks.named<Task>("verifyGate") { mustRunAfter("buildPlugin") }
tasks.named<Task>("testShards") { mustRunAfter("verifyGate") }

// T-ReleasePerf6 S1：buildPlugin 起止毫秒锚点——发版链各环节分解台账补缺口（此前 buildPlugin
// 无单独读数，Jar 恒重跑语义不变，只加观测不动行为；配置缓存兼容=动作内零脚本对象引用）。
tasks.named<Task>("buildPlugin") {
    doFirst { logger.lifecycle("buildPlugin START ${System.currentTimeMillis()}") }
    doLast { logger.lifecycle("buildPlugin END ${System.currentTimeMillis()}") }
}

// T-PerfSweep6 T-PS6-09（性能报告发现 14b）：`:sql-visualizer-agent:test` 挂进收口清单。
// agent 子项目是**独立构建/测试通道**，默认门禁 testShards 此前不覆盖它——心跳节奏、退避语义、
// attach 端到端这些 agent 侧用例只有手动点名才跑。接线点在 root body（本块），**不是**下方
// `embedAgentJar` 那一段（那是 agent fat jar 内嵌块，与门禁无关）。
// 代价：每次 testShards 会连带构建 agent fat jar 并起子 JVM（attach 集成测试），收口时间变长；
// 清理口径见计划 §六——清 build 时须连 `sub-projects/sql-visualizer-agent/build` 一起清。
tasks.named<Task>("testShards") { dependsOn(":sql-visualizer-agent:test") }
// T-TestPerf8 S6：agent 测试与 testExternal 的「UP-TO-DATE 静默跳过」修复不在本文件——
// agent 侧落在子项目脚本（tasks.named("test") 同项目配置，免跨项目 task 解析），
// testExternal 侧落在 mpv-testing 桶注册块。两处均为强制每轮真跑，理由见各自注释。

// ---------- M56 T-M56-01：agent fat jar 内嵌主插件资源（resources/agent/mps-sql-agent.jar） ----------
// 运行时 AgentManager 解压落盘 + deleteOnExit 后经 attach loadAgent 挂载；
// 落盘失败显式上报（禁静默吞）。跨项目任务经路径引用，配置缓存兼容。
evaluationDependsOn(":sql-visualizer-agent")
val agentEmbedDir = layout.buildDirectory.dir("agent-embed")
val embedAgentJar = tasks.register<Copy>("embedAgentJar") {
    from(tasks.getByPath(":sql-visualizer-agent:agentFatJar"))
    into(agentEmbedDir.map { it.dir("agent") })
    rename { "mps-sql-agent.jar" }
}
sourceSets.named("main") {
    resources.srcDir(agentEmbedDir)
}
tasks.named("processResources") {
    dependsOn(embedAgentJar)
}

// ---------- T-AgentResiduals R7：真机级探针纳发布前置（不挂 testShards） ----------
// dev-support/e2e-fake-ide（假 IDE + `-javaagent` 注入真 Spring Boot/MPJ 应用）此前只有手动运行
// 一条路——本轮 T-08（连接级跳过致恢复采集失效）正是靠它抓到的，却不在任何可脚本化验收里。
// 这里包成 gradle 任务，挂到 releaseGate（发布前置一命令）而非 testShards：
//   * 它需要真环境（本机 MySQL 3306 + demo 工程 + ~/.m2 离线依赖），不适合进日常门禁；
//   * 它是**真机级**验收（真应用 + 真驱动 + 真控制通道），价值恰在「单元测试到不了的地方」，
//     故作为发版前的可脚本化验收，而不是又一次单元测试。
// 前置缺失时**硬失败并给可执行指引**——不做静默跳过（跳过会让「没验」伪装成「验过」）。
// 环境覆盖：`-Pe2eDemoDir=` / `-Pe2eMvn=`（透传给脚本的 DEMO_DIR / MVN）。
val e2eFakeIde = tasks.register<Exec>("e2eFakeIde") {
    group = "verification"
    description = "真机级端到端探针（假 IDE + -javaagent 注入真 Spring Boot/MPJ 应用）：发布前置验收。" +
        "前置＝本机 MySQL 127.0.0.1:3306(root/root, 库 mpj_demo) + demo 工程 + ~/.m2 离线依赖；" +
        "可用 -Pe2eDemoDir= / -Pe2eMvn= 覆盖脚本默认路径"
    dependsOn(":sql-visualizer-agent:agentFatJar")
    val e2eDir = layout.projectDirectory.dir("dev-support/e2e-fake-ide")
    workingDir = e2eDir.asFile
    // Git Bash 优先（Windows 上脚本依赖 cygpath/msys 语义）；找不到再退回 PATH 里的 bash
    val gitBash = file("C:/Program Files/Git/bin/bash.exe")
    commandLine(if (gitBash.isFile) gitBash.absolutePath else "bash", "run-e2e.sh")
    // 脚本自带 300s 探针超时 + 应用启动/收口等待；给足余量（首跑含 demo classpath 生成）
    timeout.set(Duration.ofMinutes(20))
    // 环境覆盖透传（配置期取值，兼容配置缓存）
    providers.gradleProperty("e2eDemoDir").orNull?.let { environment("DEMO_DIR", it) }
    providers.gradleProperty("e2eMvn").orNull?.let { environment("MVN", it) }
    // JDK：Gradle daemon 的 PATH 不含交互式 shell 里的本机 JDK8（脚本首跑实测 `javac: command not found`），
    // 故显式把 JDK 交给脚本（脚本会把 $JAVA_HOME/bin 前置进 PATH）。默认取本机 JDK8，不存在则不设
    // （留给调用方 PATH / -Pe2eJavaHome=），脚本侧另有「缺 javac 即硬失败」的检查。
    val e2eJavaHome = providers.gradleProperty("e2eJavaHome").orNull
        ?: file("D:/HuanJing/Java/jdk1.8.0_181").takeIf { it.isDirectory }?.absolutePath
    e2eJavaHome?.let { environment("JAVA_HOME", it) }
    // T-ReleasePerf6 S1：起止毫秒锚点（与 testShardN/verifyGate 同款）——历史读数 24~45s 量级，
    // 锚点让它进 releaseGate 整链分解台账有数可对
    doFirst { logger.lifecycle("e2eFakeIde START ${System.currentTimeMillis()}") }
    doLast { logger.lifecycle("e2eFakeIde END ${System.currentTimeMillis()}") }
}
// R7 时序：真机级探针放在发版链路最后——它启动真实应用并占用端口，跑在前面会与测试/门禁抢资源。
// （与文件顶部的 verifyGate→testShards 同款做法：依赖只保证「都执行」，mustRunAfter 才定序。）
// T-ReleasePerf10（20261030 实测）：定序必须挂**分片叶子**而不是聚合任务 testShards——
// 任一 shard 失败时（权威轮用 --continue 跑到底），testShards 因依赖失败被 Gradle 跳过，
// **跳过任务的 mustRunAfter 边会被一并丢弃**，e2e 于是提前 4min 与剩余分片并发起跑
// （实测 e2e START 12:13:20 < shard5 END 12:17:24；e2e 跑真实应用+MySQL，与分片并发有污染风险）。
// 挂到叶子后，即使某个 shard 失败，e2e 仍等所有分片收尾再起（--continue 语义保留：e2e 照样跑）。
tasks.named("e2eFakeIde") {
    mustRunAfter(tasks.matching { it.name.matches(Regex("testShard[0-9]+")) })
    mustRunAfter("testExternal")
}
