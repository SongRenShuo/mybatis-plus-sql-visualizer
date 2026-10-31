import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
import mpv.verification.assertNoForeignVerifier
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

// T-ReleasePerf8（20261024）N3：verifier home 外迁机器级持久目录（T6 L5 按新证据复审转正）。
// T6 L5 的销案理由「发行日历史全暖窗」被发行日三读数证伪（v2026.10.9 31m9s / v2026.10.13 54m11s /
// v2026.10.16 55m8s，10-08 记录明确「verifyMarketplace 单步下载 3.33GB 耗 30m」）——home 在 build/ 下
// 意味着换工作树（发行常规操作：detached 验证树）= 全量冷下载税。本轮探针读数（rp8 树，20261024）：
//   R0 冷树：下载 3.33GB/3m50s（快代理窗 ~14MB/s；慢窗 ~2MB/s 时同一笔膨胀到 ~30m＝55min 轮主因）；
//   R1 warm：下载 0 B（loaded-plugins 131 文件全 kept），verifier 本体 9m15s；
//   跨树跨 10 天比对主树(10-14) vs rp8(10-24)：131 个 build 号 130 个一致（仅 1 个插件前进 +89MB）。
// 故 home 缺省迁到 GRADLE_USER_HOME/mpv-verifier-home/{gate,marketplace}（机器级持久、跨工作树/跨
// clean 复用；gate 与 marketplace 保持分家，离线/在线语义互不污染，同链并发跑也无共享写点）。
// 回退：-PverifierHomeRoot=build 一词回落每工作树 build/ 下的既有形态；给绝对路径则用该目录作根。
// 并发面：共享 home 重开 T-VerifierHomeIso 消灭过的冲突类（两 verifier 并发写同一 extracted-plugins），
// 防线两道——①全量门禁互斥既有定例（两份 releaseGate 叠跑本就因 Commit 破线被禁）；②下方
// assertNoForeignVerifier 锁探测（doFirst 扫描整机 java 进程命令行，发现非本进程的 PluginVerifierMain
// 指向同一 home 即硬失败给指引，不静默共写）。
fun resolveVerifierHome(role: String): File {
    val prop = providers.gradleProperty("verifierHomeRoot").orNull
    return when {
        prop == null -> gradle.gradleUserHomeDir.resolve("mpv-verifier-home").resolve(role)
        prop == "build" ->
            layout.buildDirectory.dir(if (role == "gate") "verify-home" else "verify-home-$role").get().asFile
        else -> File(prop).resolve(role)
    }
}

// 共享 home 并发锁探测（assertNoForeignVerifier）落在 buildSrc/src/main/kotlin/VerifierHomeGuard.kt
// ——doFirst lambda 引用脚本顶层函数触发配置缓存「cannot serialize」（mpv-testing 同款实证），
// 独立 .kt 文件的顶层函数编译为普通类可序列化（MarketVerdictChecker 同款先例）。

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
        // 字典序实际取 1.409 故独活）。显式隔离。T-ReleasePerf8 后 verifyGate 缺省迁到
        // GRADLE_USER_HOME/mpv-verifier-home/gate；本任务（报告生成器，不在发版链）维持
        // build/verify-home——隔离面不变，只是不再与 verifyGate 互享缓存（两者都离线零下载，损失≈0）。
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
    // T-ReleasePerf8（20261024）N3 转正：缺省改落 GRADLE_USER_HOME/mpv-verifier-home/gate（机器级
    // 持久，跨工作树/clean 复用，发行 detached 树不再付冷税），并发面由 assertNoForeignVerifier
    // 锁探测兜底；-PverifierHomeRoot=build 回落本行原有形态。依据（R0/R1 探针 + 跨树复用率 130/131）
    // 见上方 N3 总注释。
    val verifyHome = resolveVerifierHome("gate")
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
    // T-ReleasePerf10 券02：执行期水位快速失败闸门——在错峰 sleep 之前判定（不白睡 285s）。
    // 缺省 floor 6GB，-PwatermarkFloorGb 覆盖，-PwatermarkGate=off 关闭；探针失败 fail-open
    // （机制与档位依据见 mpv.watermark.MemoryWatermarkProbe 与 testShardStagger 同款）。
    // 配置缓存约束同 gateStaggerMs：register 块内捕获为普通 Double，执行期经 inputs 读回。
    val watermarkFloorGb = if (providers.gradleProperty("watermarkGate").orNull == "off") {
        0.0
    } else {
        providers.gradleProperty("watermarkFloorGb").orNull?.toDoubleOrNull() ?: 6.0
    }
    inputs.property("mpvWatermarkFloorGb", watermarkFloorGb)
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
        // T-ReleasePerf10 券02：水位闸门在 285s 错峰 sleep 之前判定——水位不足秒级红，不白睡。
        mpv.watermark.MemoryWatermarkProbe.checkGate(
            (inputs.properties["mpvWatermarkFloorGb"] as? Number)?.toDouble() ?: 6.0,
            "verifyGate（verifier JVM 出生前）",
            logger,
        )
        assertNoForeignVerifier(verifyHome, logger)
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
    // T-ReleasePerf8（20261024）N3：marketplace home 同 verifyGate 外迁机器级持久目录——这是
    // 发行日冷下载税的主体（3.33GB 依赖插件 zip，R1 实证 warm=0 B 全命中、跨树 10 天仍 130/131
    // 复用）。-PverifierHomeRoot=build 回落 build/verify-home-marketplace 既有形态。
    val verifyHome = resolveVerifierHome("marketplace")
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
        assertNoForeignVerifier(verifyHome, logger)
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

// T-ReleasePerf10（20261030）券04：verifyMarketplace home 闲时保温——发行日下载税的常态化解。
// 现状：home 机器级持久（T8 N3）但保温靠「恰好最近跑过」；上游依赖插件前进一周，发行日
// verifyMarketplace 对增量重下载（10-29 实测单步 18m29s 占整链 88%；慢代理窗同一笔 3.33GB
// 膨胀到 ~30m）。本任务对**最近可用发行包**跑与 verifyMarketplace 同参数面的在线 check，
// 把依赖插件解析/下载回填 home，使正式门禁的 verifyMarketplace 回到暖窗本体 ~9m15s。
// 语义红线不触碰：正式门禁仍在线、无豁免、按 verdict 判失败——本任务只是把「下载」段搬运出
// 关键路径，不做任何 verdict 缓存（T7 红线）。与 verifyMarketplace 的差异仅三处：
//   ① 报告落 build/reports/pluginVerifierRefresh（不参与门禁读数）；
//   ② doLast 不做 baseline verdict 判定——只打锚点＋写 home/.mpv-refresh-stamp（gate 脚本
//      按它判陈旧并 WARN，>36h 建议先跑本任务）；
//   ③ zip 解析 = -PrefreshZip 显式 → distributions/*.zip mtime 最新（配置期解析；refresh 独立
//      命令跑、从不与 buildPlugin 同批，配置与执行间 distributions 不变；CC 复用冻结旧路径时
//      zip 缺失会被 doFirst 拦下给指引）→ 无 zip 即 StopExecutionException 跳过（新树首跑不炸）。
// 并发面：同 marketplace home，assertNoForeignVerifier 锁探测同款兜底；闲时登记单 verifier
// JVM ~2.8GB commit（计划 docs/开发计划/20261030-0827-T-ReleasePerf10-… §三 券04）。
val verifyMarketplaceRefresh = tasks.register<JavaExec>("verifyMarketplaceRefresh") {
    group = "verification"
    description =
        "verifyMarketplace home 闲时保温：对最近发行包回填在线依赖缓存（无 baseline 判定；-PrefreshZip 显式指定）"
    mainClass.set("com.jetbrains.pluginverifier.PluginVerifierMain")
    val verifyHome = resolveVerifierHome("marketplace")
    jvmArgs("-Dplugin.verifier.home.dir=${verifyHome.absolutePath}")
    jvmArgs(verifierProxyJvmArgs)
    val pluginVersion = providers.gradleProperty("version").get()
    val reportsDir = layout.buildDirectory.dir("reports/pluginVerifierRefresh").get().asFile
    val preferredZip = layout.buildDirectory
        .file("distributions/mybatis-plus-sql-visualizer-$pluginVersion.zip").get().asFile
    val gradleUserHome = gradle.gradleUserHomeDir
    classpath(
        gradleUserHome.resolve("caches/modules-2/files-2.1/org.jetbrains.intellij.plugins/verifier-cli")
            .walkTopDown().maxDepth(4)
            .filter { it.isFile && it.name.matches(Regex("verifier-cli-.+-all\\.jar")) }
            .firstOrNull() ?: error("verifier-cli jar 未在 gradle 缓存找到（先跑一次 verifyPlugin 重建缓存）"),
    )
    val explicitZip = providers.gradleProperty("refreshZip").orNull?.let { File(it) }
    val zipFile = explicitZip?.takeIf { it.isFile }
        ?: preferredZip.takeIf { it.isFile }
        ?: layout.buildDirectory.dir("distributions").get().asFile
            .listFiles { f: File -> f.isFile && f.name.endsWith(".zip") }
            ?.maxByOrNull { it.lastModified() }
        ?: preferredZip
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
        assertNoForeignVerifier(verifyHome, logger)
        if (!zipFile.isFile) {
            throw StopExecutionException(
                "verifyMarketplaceRefresh 跳过：无可用发行包（$zipFile 不存在）。" +
                    "新树首跑属预期（home 尚空、无冷税可省）；要跑保温请 -PrefreshZip=<zip> 显式指定，或先 buildPlugin。",
            )
        }
        logger.lifecycle("verifyMarketplaceRefresh START ${System.currentTimeMillis()} zip=$zipFile（在线、无豁免、保温用）")
    }
    doLast {
        logger.lifecycle("verifyMarketplaceRefresh END ${System.currentTimeMillis()}")
        verifyHome.mkdirs()
        val stamp = File(verifyHome, ".mpv-refresh-stamp")
        stamp.writeText(System.currentTimeMillis().toString())
        logger.lifecycle("verifyMarketplaceRefresh stamp -> $stamp")
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
