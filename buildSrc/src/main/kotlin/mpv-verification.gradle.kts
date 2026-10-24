import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
import mpv.verification.checkMarketplaceVerdicts

plugins {
    id("org.jetbrains.intellij.platform")
}

// T-BuildScriptSplit：兼容验证门禁分治——块级逐字搬运自根 build.gradle.kts（锚点 4c3e268），
// 注释随迁；验收=基线指纹比对（plugin.xml 字节/tasks 清单/分区日志行/verifySmoke 绿）。

// T-M30：跨版本 API 兼容校验（官方推荐流程）——`./gradlew verifyPlugin` 对目标 IDE
// 集合做字节码级检查（插件引用的类/方法在目标版本中必须存在）。目标 = 最低支持
// 版本 + 中段版本 + 最新版本；首次运行下载各发行版（体积大，gradle.properties 已配代理）。
// T-M31.1/2 提速：验证耗时与版本数线性（243≈1min / 253≈5.7min / 262≈7.5min，字节码分析不可缓存）——
// 三态 `-PverifyIdes`：默认全量三版本（发布/里程碑）；minimal 边界 243+262（253 被两头覆盖）；
// smoke 只 243 构建基准（约 1.5min，日常每次开发后兼容冒烟；高版本 API 移除风险由发布前全量兜底）。
intellijPlatform {
    pluginVerification {
        ides {
            // T-VerifyPlugin：从任务名推断验证模式（./gradlew verifySmoke / verifyMinimal 自动选择对应 IDE 集合）
            val verifyIdes = when {
                gradle.startParameter.taskNames.contains("verifySmoke") -> "smoke"
                gradle.startParameter.taskNames.contains("verifyMinimal") -> "minimal"
                else -> providers.gradleProperty("verifyIdes").orNull
            }
            when (verifyIdes) {
                "smoke" -> create(IntelliJPlatformType.IntellijIdeaUltimate, "2024.3.7.1")
                "minimal" -> {
                    create(IntelliJPlatformType.IntellijIdeaUltimate, "2024.3.7.1")
                    create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.2.3")
                }
                else -> {
                    create(IntelliJPlatformType.IntellijIdeaUltimate, "2024.3.7.1")
                    create(IntelliJPlatformType.IntellijIdeaUltimate, "2025.3.5")
                    create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.2.3")
                }
            }
        }
        // T-M30.1：失败等级——IPGP 2.15+ 默认含 INTERNAL_API_USAGES 会失败，本项目
        // `PluginManagerCore.getPlugin`（M28 更新功能定位已装插件）为平台 Internal API 且无公共替代
        // （官方文档即此用法），刻意使用；报告仍生成、问题仍可见。
        // T-ReleasePerf-01（2026-09-15）：本任务降级为「报告 + 弃用清单生成器」——兼容判定交 verifyGate。
        // 依据（javap 反编译 IPGP 2.18.1 VerifyPluginTask，证据可复现）：
        //   ① getOptions() 本身无缺陷，按 getOffline()/getIgnoredProblemsFile() 正确拼 CLI 参数；
        //   ② 但 verifyOutput() → collectProblems() 的判定**完全基于控制台文本**：用
        //      FailureLevel.getSectionHeading()（"Compatibility problems" 等小节标题）逐段切分、
        //      pluginLinePattern = ^Plugin .*? against (\S+): 正则抓问题行，**从不读 -ignored-problems
        //      命中的信息**——CLI 已豁免的问题在控制台仍逐行打印，于是被重新计数判失败。
        //      这正是 dev 分支实测「同输入一次过一次挂」的机制（三轮实证）。
        // 保留除下列四类外的全部等级。T-CiRelease（20261024 CI 实证更新）：除 COMPATIBILITY_PROBLEMS
        // （判定缺陷见上②，交 verifyGate）外，MISSING_DEPENDENCIES / DEPRECATED_API_USAGES /
        // EXPERIMENTAL_API_USAGES 一并移出失败集——GitHub Actions 发行流水线首次运行本任务即以此
        // 三类失败（三目标 verdict 均 Compatible）：① 离线模式下两个可选依赖（com.intellij.mcpServer /
        // com.intellij.ml.llm）报 Unavailable（T-McpToolset 既有形态，在线解析归 verifyMarketplace
        // 口径）；② 基线 243 兼容策略下 deprecated 40~123 处、experimental 96~98 处为长期存量（与编译
        // 告警同源），非发版阻断项。「其余等级均 0 命中」的 2026-09-15 前提已不成立；权威门禁仍是
        // verifyGate（退出码）+ verifyMarketplace（基线比对），报告仍生成、问题仍可见。
        failureLevel = FailureLevel.entries - setOf(
            FailureLevel.COMPATIBILITY_PROBLEMS,
            FailureLevel.MISSING_DEPENDENCIES,
            FailureLevel.DEPRECATED_API_USAGES,
            FailureLevel.EXPERIMENTAL_API_USAGES,
        )
    }
}

// Plugin Verifier 是独立子进程，systemProp 代理不自动透传——显式注入。
// T-CiRelease（20261023）：注入值改从 daemon 系统属性读取（本机 gradle.properties 的
// systemProp.* 会设入 daemon JVM）——本机与原硬编码 127.0.0.1:7890 等价；GitHub Actions
// 发行流水线会剥掉 gradle.properties 的代理行，此时列表为空 → verifier 直连网络，
// 不再撞死 runner 上不存在的本地代理。
val verifierProxyJvmArgs = listOfNotNull(
    System.getProperty("http.proxyHost")?.let { "-Dhttp.proxyHost=$it" },
    System.getProperty("http.proxyPort")?.let { "-Dhttp.proxyPort=$it" },
    System.getProperty("https.proxyHost")?.let { "-Dhttps.proxyHost=$it" },
    System.getProperty("https.proxyPort")?.let { "-Dhttps.proxyPort=$it" },
)

tasks.named<VerifyPluginTask>("verifyPlugin") {
    jvmArgs(verifierProxyJvmArgs)
    jvmArgs(
        // T-VerifierHomeIso（2026-09-17 master c591c7e，本拆分合流时回植至本 convention）：
        // verifier-cli 1.410 在缺省 home（~/.pluginVerifier）下提取插件 zip 恒败（2×2 矩阵实证：
        // 1.410×默认home 恒败、1.410×隔离home 与 1.409×任一 home 全绿；verifyGate 走 walkTopDown
        // 字典序实际取 1.409 故独活）。显式隔离，并与 verifyGate 共用同一 build/verify-home
        //（同 zip 提取缓存互享，~/.pluginVerifier 污染源整类摘除）。
        "-Dplugin.verifier.home.dir=" + layout.buildDirectory.dir("verify-home").get().asFile.absolutePath,
    )
    // T-ReleasePerf-01（P0-1）：离线化 + 豁免清单——本仓库此前只注入了代理，verifier 每个目标都要
    // 联网解析该 IDE 的 bundled 插件集（daemon 日志实证：45 次运行 PluginDetailsCache
    // hits=183/misses=64、单次 247 个插件解析），是 253/262 耗时波动的主因（历史 45 次三目标合计
    // 最快 144s、中位 446s、最慢 740s，5 倍差）。-offline 只断 marketplace 解析，不改兼容性判定；
    // 豁免清单只放行「永不可达」缺类（243 上 MCP 桩类型，作用域由 McpScopeSourceScanTest 守卫），
    // 命中项在报告中仍如实留痕。
    offline.set(true)
    ignoredProblemsFile.set(layout.projectDirectory.file("dev-support/verifier-ignored-problems.txt"))
}

// T-ReleasePerf-05（P2-1）：权威兼容门禁——直驱 verifier-cli。
// 为什么不让 IPGP 的 verifyPlugin 承担门禁（字节码实证见上方 failureLevel 处）：
// 它按控制台小节标题重新计数问题行，不读 -ignored-problems 命中的豁免信息，且输出解析存在竞态
// （dev 分支三轮实证：同输入一次过一次挂）。故门禁改由本任务直驱 CLI，判定只看退出码。
// -offline：断 marketplace 元数据解析（历史 45 次运行里 253/262 耗时波动 138s~725s 的主因），
//           不改兼容性判定；-ignored-problems：豁免清单见 dev-support/verifier-ignored-problems.txt。
//
// verifyOnly（T-ReleasePerf-04 / P1-2）：跳过 buildPlugin，直接对已存在的发行包做验证——
// 改文案/文档后重验不必重打包（Jar 任务带 MPV-Build 时间戳，outputs.upToDateWhen{false} 恒重跑）。
val verifyOnly = providers.gradleProperty("verifyOnly").orNull?.toBoolean() ?: false

val verifyGate = tasks.register<JavaExec>("verifyGate") {
    group = "verification"
    description = "权威兼容门禁：verifier-cli 直驱（-offline + -ignored-problems；-PverifyIdes / -PverifyOnly）"
    if (!verifyOnly) {
        dependsOn("buildPlugin")
    }
    mainClass.set("com.jetbrains.pluginverifier.PluginVerifierMain")
    // T-ReleasePerf-05：verifier 缓存 home 隔离——默认落在 build/verify-home，避开与其他 verify 会话
    // 共享全局 ~/.pluginVerifier/extracted-plugins（Windows 文件锁互踩：2026-09-15 真实验证时
    // 并行会话的 verify 占用该目录，forceRemoveDirectory 失败 exit 1；verifier 支持
    // -Dplugin.verifier.home.dir 覆盖，javap PluginVerifierMain$verifierHomeDirectory$2 实证）。
    // 代价=IDE 元数据缓存随 build 目录走，clean 后首次离线重建；IDE 发行本体在 gradle transforms 不受影响。
    val verifyHome = layout.buildDirectory.dir("verify-home").get().asFile
    jvmArgs("-Dplugin.verifier.home.dir=${verifyHome.absolutePath}")
    // 全部路径在配置期解析为值（configuration cache 兼容：执行期不触达 project/layout）
    val pluginVersion = providers.gradleProperty("version").get()
    val reportsDir = layout.buildDirectory.dir("reports/pluginVerifierGate").get().asFile
    val zipFile = layout.buildDirectory.file("distributions/mybatis-plus-sql-visualizer-$pluginVersion.zip").get().asFile
    val ignoredFile = layout.projectDirectory.file("dev-support/verifier-ignored-problems.txt").asFile
    val gradleUserHome = gradle.gradleUserHomeDir
    // T-ReleasePerf-05：configuration cache 兼容——执行期 lambda 内**不得引用脚本对象**（gradle/providers）。
    // 实测：lambda 里写 gradle.startParameter / providers.gradleProperty 会整段不可序列化（"cannot serialize
    // Gradle script object references"），故在配置期捕获为普通值（startParameter 与属性值在配置期已确定）。
    val invokedTaskNames = gradle.startParameter.taskNames
    val verifyIdesProperty = providers.gradleProperty("verifyIdes").orNull
    // T-ReleasePerf7 N1（20261007）：verifyGate 时间性错峰延迟。缺省逻辑：releaseGate 编排内
    // 自动 285000ms（R1 探针校准的 marketplace 高占用段时长——错峰后 B 组均值 672.5s vs 浮动
    // 754s = −10.8%，parity 全绿）；verifyGate 冒烟/其他场景 0（行为不变）。
    // 显式 -PgateStaggerMs=0 可强制关。分片叶子侧同款=mpv-testing 的 testStaggerMs。
    // 配置缓存约束：必须在 register 块内捕获为普通 Long（脚本顶层 val 经 lambda 引用=持有脚本
    // 对象引用不可序列化，T-ReleasePerf-05 同款坑，S2 round2 实测 5 problems storing）。
    val gateStaggerMs = providers.gradleProperty("gateStaggerMs").orNull?.toLongOrNull()
        ?: if (invokedTaskNames.contains("releaseGate")) 285_000L else 0L
    classpath(
        gradleUserHome.resolve("caches/modules-2/files-2.1/org.jetbrains.intellij.plugins/verifier-cli")
            .walkTopDown().maxDepth(4)
            .filter { it.isFile && it.name.matches(Regex("verifier-cli-.+-all\\.jar")) }
            .firstOrNull() ?: error("verifier-cli jar 未在 gradle 缓存找到（先跑一次 verifyPlugin 重建缓存）"),
    )
    argumentProviders.add(CommandLineArgumentProvider {
        val guh = gradleUserHome
        fun ideDir(name: String): File =
            guh.resolve("caches").listFiles()?.asSequence()
                ?.filter { it.resolve("transforms").isDirectory }
                ?.flatMap { ver -> ver.resolve("transforms").listFiles()?.asSequence() ?: emptySequence() }
                ?.map { hash -> hash.resolve("transformed").resolve(name) }
                ?.firstOrNull { it.isDirectory }
                ?: error("IDE 发行目录 $name 未在 gradle 缓存 transforms 找到（先跑一次 verifyPlugin/构建一次对应平台）")

        val ide243 = ideDir("ideaIU-2024.3.7.1-win")
        val jbr = ide243.resolve("jbr").takeIf { it.isDirectory }
            ?: error("243 发行版缺 jbr（-runtime-dir 必需）")
        val verifyIdes = when {
            invokedTaskNames.contains("verifySmoke") -> "smoke"
            invokedTaskNames.contains("verifyMinimal") -> "minimal"
            else -> verifyIdesProperty
        }
        val targets = when (verifyIdes) {
            "smoke" -> listOf(ide243)
            "minimal" -> listOf(ide243, ideDir("idea-2026.2.3-win"))
            else -> listOf(ide243, ideDir("idea-2025.3.5-win"), ideDir("idea-2026.2.3-win"))
        }
        buildList {
            add("check-plugin")
            add("-offline")
            add("-ignored-problems")
            add(ignoredFile.absolutePath)
            add("-verification-reports-dir")
            add(reportsDir.absolutePath)
            add("-runtime-dir")
            add(jbr.absolutePath)
            add("-verification-reports-formats")
            add("plain,html")
            add(zipFile.absolutePath)
            targets.forEach { add(it.absolutePath) }
        }
    })
    // 并发审计锚点：起止毫秒时间戳（与 testShardN 同款，便于事后核对链路时序）。
    // 配置缓存约束：动作内只引用配置期已解析的值，辅助函数（walkTopDown 链）写在动作体内。
    doFirst {
        if (!zipFile.isFile) {
            throw GradleException(
                "-PverifyOnly=true 需要已存在的发行包：$zipFile 不存在。" +
                    "先跑 ./gradlew buildPlugin 产出，或去掉 -PverifyOnly 走完整链路。",
            )
        }
        // T-ReleasePerf7 N1（20261007）：时间性错峰开关（缺省 0=关）。语义与分片叶子同款
        //（mpv-testing testStaggerMs）：把 verifyGate 真实起点推后 N 毫秒，嵌入 verifyMarketplace
        // 高占用解析段之后的低占用等待窗。仅延迟启动，验证口径零变化。
        if (gateStaggerMs > 0) {
            logger.lifecycle("verifyGate stagger ${gateStaggerMs}ms")
            Thread.sleep(gateStaggerMs)
        }
        logger.lifecycle("verifyGate START ${System.currentTimeMillis()} plugin=$zipFile")
    }
    doLast {
        logger.lifecycle("verifyGate END ${System.currentTimeMillis()}")
        val verdicts = reportsDir.walkTopDown()
            .filter { it.name == "verification-verdict.txt" }
            .sortedBy { it.path }
            .toList()
        if (verdicts.isEmpty()) {
            logger.warn("verifyGate: 未找到 verification-verdict.txt，请检查 $reportsDir")
        }
        verdicts.forEach { logger.lifecycle("verifyGate verdict [${it.parentFile.parentFile.name}] ${it.readText().trim()}") }
    }
}

// T-MarketCompatFix（2026-09-23）：Marketplace 同口径兼容校验——**在线 + 无豁免 + 按问题数判失败**。
// 为什么必须单开一道（20260923 上传 Marketplace 实测，两道屏叠加导致门禁长期失明）：
//   ① `verifyGate` 带 `-offline`——verifier 不解析可选依赖，凡引用可选插件 API 的类直接跳过
//      （同一 zip：离线跑 243 报 0 问题，在线跑报 5 问题）；
//   ② `-ignored-problems` 清单里有按包前缀成族的规则（`com.intellij.mcpserver.` /
//      `io.modelcontextprotocol.`），把 Marketplace 判 Critical 的整族未解析类豁免掉；
//   ③ 判定只看退出码，而实测 verifier-cli **带 4 个 compatibility problems 仍退出 0**（无豁免清单时实测），
//      即「门禁绿」与「verdict 文本里写着 N compatibility problems」可以并存。
// 本任务对齐 Marketplace 行为：在线解析可选依赖（会下载数百 MB 平台插件元数据）、不套用任何豁免、
// 并在 doLast 里**逐份 verdict 判文本**——任何目标不是以 `Compatible` 开头即失败（GradleException）。
// 覆盖面 = 下方 targets（本机 gradle 缓存内已下载的发行版集合，与 verifyGate 同源三目标）；要覆盖
// Marketplace 的 251/252/261/263 需先在 intellijPlatform.pluginVerification.ides 里声明对应版本。
val verifyMarketplace = tasks.register<JavaExec>("verifyMarketplace") {
    group = "verification"
    description = "Marketplace 同口径兼容校验（在线 + 无豁免 + 有问题即失败）；发布/上传前必跑"
    dependsOn("buildPlugin")
    mainClass.set("com.jetbrains.pluginverifier.PluginVerifierMain")
    val verifyHome = layout.buildDirectory.dir("verify-home-marketplace").get().asFile
    jvmArgs(
        "-Dplugin.verifier.home.dir=${verifyHome.absolutePath}",
    )
    // 在线模式必须联网：代理注入同 verifyPlugin——daemon 配了代理（systemProp.*）才注入，
    // CI 直连（原硬编码 127.0.0.1:7890 会让无代理环境的联网解析全部失败）
    jvmArgs(verifierProxyJvmArgs)
    val pluginVersion = providers.gradleProperty("version").get()
    val reportsDir = layout.buildDirectory.dir("reports/pluginVerifierMarketplace").get().asFile
    val zipFile = layout.buildDirectory.file("distributions/mybatis-plus-sql-visualizer-$pluginVersion.zip").get().asFile
    val baselineFile = layout.projectDirectory.file("dev-support/verifier-marketplace-baseline.txt").asFile
    val gradleUserHome = gradle.gradleUserHomeDir
    classpath(
        gradleUserHome.resolve("caches/modules-2/files-2.1/org.jetbrains.intellij.plugins/verifier-cli")
            .walkTopDown().maxDepth(4)
            .filter { it.isFile && it.name.matches(Regex("verifier-cli-.+-all\\.jar")) }
            .firstOrNull() ?: error("verifier-cli jar 未在 gradle 缓存找到（先跑一次 verifyPlugin 重建缓存）"),
    )
    argumentProviders.add(CommandLineArgumentProvider {
        val guh = gradleUserHome
        fun ideDir(name: String): File =
            guh.resolve("caches").listFiles()?.asSequence()
                ?.filter { it.resolve("transforms").isDirectory }
                ?.flatMap { ver -> ver.resolve("transforms").listFiles()?.asSequence() ?: emptySequence() }
                ?.map { hash -> hash.resolve("transformed").resolve(name) }
                ?.firstOrNull { it.isDirectory }
                ?: error("IDE 发行目录 $name 未在 gradle 缓存 transforms 找到（先跑一次 verifyPlugin/构建一次对应平台）")

        val ide243 = ideDir("ideaIU-2024.3.7.1-win")
        val jbr = ide243.resolve("jbr").takeIf { it.isDirectory }
            ?: error("243 发行版缺 jbr（-runtime-dir 必需）")
        buildList {
            add("check-plugin")
            // 刻意不加 -offline、不加 -ignored-problems：这两项正是失明的成因
            add("-verification-reports-dir")
            add(reportsDir.absolutePath)
            add("-runtime-dir")
            add(jbr.absolutePath)
            add("-verification-reports-formats")
            add("plain,html")
            add(zipFile.absolutePath)
            add(ide243.absolutePath)
            add(ideDir("idea-2025.3.5-win").absolutePath)
            add(ideDir("idea-2026.2.3-win").absolutePath)
        }
    })
    doFirst {
        if (!zipFile.isFile) {
            throw GradleException("verifyMarketplace 需要已存在的发行包：$zipFile 不存在。先跑 ./gradlew buildPlugin。")
        }
        logger.lifecycle("verifyMarketplace START ${System.currentTimeMillis()} plugin=$zipFile（在线、无豁免）")
    }
    doLast {
        logger.lifecycle("verifyMarketplace END ${System.currentTimeMillis()}")
        checkMarketplaceVerdicts(reportsDir, baselineFile, logger)
    }
}

// T-VerifyPlugin：封装 verifyPlugin 三态验证为独立 task（方便 ./gradlew verifySmoke / verifyFull 调用）
// T-ReleasePerf-01（2026-09-15）：verifySmoke / verifyFull 改挂 verifyGate（权威门禁，口头 243 单目标、
// 全量三目标），verifyPlugin 退居报告生成器——详见上方 failureLevel 处的判定缺陷字节码实证。
tasks {
    register("verifySmoke") {
        group = "verification"
        description = "日常开发快速兼容冒烟（仅 243，等同 verifyGate -PverifyIdes=smoke）"
        dependsOn("verifyGate")
    }
    register("verifyFull") {
        group = "verification"
        description = "发布前全量验证（三版本 243+253+262，权威门禁 verifyGate）"
        dependsOn("verifyGate")
    }

    // T-ReleasePerf-02（P0-2）：发版链路一命令编排——按正确时序串行执行：
    //   buildPlugin（出含 changeNotes 的最终产物）→ verifyGate（对该产物做三目标兼容门禁）
    //   → testShards（对同一提交跑全量测试）→ e2eFakeIde（真机级探针）。任一环失败即停
    //   （Gradle 依赖失败语义），消除「门禁与测试并发抢 CPU + 门禁结论对被测代码时序空挂」两个问题。
    //
    // T-AgentResiduals R7（2026-09-22）：releaseGate 追加真机级探针（dev-support/e2e-fake-ide）。
    //   它需要真环境（本机 MySQL + demo 工程 + ~/.m2 离线依赖），故**只挂发布前置**、不进 testShards
    //   （日常门禁不因此变慢）；前置缺失时硬失败并给指引，不做静默跳过。
    //   串行顺序：放在最后——它启动真实应用并占用端口，跑在前面会与测试/门禁抢资源。
    // 用法：./gradlew releaseGate [-PverifyIdes=...] [-PverifyOnly=true] [-Pe2eDemoDir=...]
    // T-MarketCompatFix（2026-09-23）：追加 verifyMarketplace——离线 verifyGate 与 Marketplace 判定
    //   之间曾有系统性缺口（详见该任务头注：离线不解析可选依赖 + 成族豁免 + 只看退出码），
    //   20260923 上传 Marketplace 时 7 个目标里 6 个被判 Critical 而本仓门禁全绿即由此而来。
    //   该任务在线、无豁免、按 verdict 文本判失败，故本仓发布链自此会如实暴露 Marketplace 侧读数。
    register("releaseGate") {
        group = "verification"
        description =
            "发版链路一命令：buildPlugin → verifyGate（三目标兼容门禁）→ verifyMarketplace（Marketplace 同口径）" +
                " → testShards（全量测试）→ e2eFakeIde（真机级端到端探针，需本机 MySQL + demo 工程）"
        dependsOn("buildPlugin", "verifyGate", "verifyMarketplace", "testShards", "e2eFakeIde")
        // T-ReleasePerf6 S1：整链起止毫秒锚点——与 verifyGate/testShardN/verifyMarketplace 各自
        // 的锚点配合，一次 releaseGate 日志即可对出各环节分解耗时（此前只有各环节零散锚点，
        // 总墙钟要从 daemon 日志或表纪实测）。动作内零脚本对象引用，配置缓存兼容。
        doFirst { logger.lifecycle("releaseGate START ${System.currentTimeMillis()}") }
        doLast { logger.lifecycle("releaseGate END ${System.currentTimeMillis()}") }
    }
}
