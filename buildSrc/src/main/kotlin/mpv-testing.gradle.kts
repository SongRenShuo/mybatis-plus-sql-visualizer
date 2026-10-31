import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildServiceParameters
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    // java 插件只为取得 sourceSets/tasks.test 的类型安全访问器（根侧 kotlin.jvm 已应用 java，幂等）
    id("java")
    id("org.jetbrains.intellij.platform")
}

// T-BuildScriptSplit：测试基建分治——块级逐字搬运自根 build.gradle.kts（锚点 4c3e268），
// 注释随迁；验收=分区指纹与基线一致 + testShards 全量绿。

// ---------- T-ReleasePerf9（20261029）：测试链并发限流 ----------
// 5 个分片（同 root project）在 org.gradle.parallel 下真并发起跑（20261028 三轮实测 START 戳
// 相差 <60ms；「分片严格串行 911s」是 20261020 旧树的过时读数）——内存挤时 5 测试 JVM + verifier
// 同时在场直冲 native OOM（hs_err Chunk::new / G1 virtual space，当日三轮作废 + 人肉分阶段
// 11m13s 兜底）。本机制把「同时执行的测试任务数」钉在 laneLimit：
//   * 缺省 2——10-28 实测 `--max-workers=2` 干净出读数的精准化（只限测试链，编译并行度不动，
//     不再需要手工带 --max-workers 或人肉分阶段）；
//   * releaseGate 编排内缺省 3——分片窗两波 ~360s，配合下方 testShardStagger 共享错峰，
//     仍嵌回 verifyMarketplace 等待窗附近，一条命令总墙钟不劣化出 T8 权威轮 11m15s 量级；
//   * -PtestShardConcurrency=N 显式覆盖（内存干净时可提 4~5 提速；1=测试链全串行）。
// 限流 ≠ 降片：片数、每片类集、覆盖面逐一不变，压的是「同一时刻几片在跑」（R3 禁降片口径
// 的另一维度）。usesService 是配置缓存官方通道；服务类无状态（MpvTestLaneLimiter.kt），
// 只承担并发许可。DB 集成桶豁免——T-TestPerf14 的 testDb* 七路并行语义保持（独立命令，
// 不与 testShards/releaseGate 同批）。
val mpvTestLaneLimiter: Provider<MpvTestLaneLimiterService> = run {
    val invokedTaskNamesForLane = gradle.startParameter.taskNames
    val isGateOrchestration = invokedTaskNamesForLane.contains("releaseGate")
    val laneLimitDefault = if (isGateOrchestration) 3 else 2
    // T-ReleasePerf10 券01：测试链档位从静态缺省改内存水位自适应——内存宽裕时自动提档提速
    // （T9 读数：缺省 2 估 8.5~10min vs 限流 5 实测 5m02s），水位紧张时由券02 的快速失败闸门
    // 拒绝起跑（不靠压档位续命）。只在 testShards/releaseGate 形态采样（其他构建零开销）；
    // 探针失败/不可用时回落静态缺省（fail-open）。
    // 优先级：-PtestShardConcurrency 显式 > max(自适应, 静态缺省)；-PtestLaneAdaptive=off 回落静态。
    val isShardedRun = isGateOrchestration || invokedTaskNamesForLane.contains("testShards")
    val adaptiveOff = providers.gradleProperty("testLaneAdaptive").orNull == "off"
    // releaseGate 编排内并发面还有 verifyGate+verifyMarketplace 两 verifier JVM（market 常驻
    // 2.8GB / gate 2.3GB，错峰下不同时在峰值）——定档前预留 4GB 覆盖叠加段
    // （档位表与物理模型见 mpv.watermark.MemoryWatermarkProbe.pickLane 注释）
    val reserveGb = if (isGateOrchestration) 4.0 else 0.0
    // -PwatermarkOverrideFreeGb=<x>：确定性验证用（把探针读数钉成给定值，只影响档位计算，
    // 不绕过闸门）——档位表的验证不能依赖「此刻机器恰好多少空闲」（并行会话下 free 3~14GB 波动）。
    val overrideFreeGb = providers.gradleProperty("watermarkOverrideFreeGb").orNull?.toDoubleOrNull()
    // 档位上限：缺省 3（5 档实测 execSum 翻倍 + 4 例负载敏感红，见 pickLane 注释）；
    // -PtestShardMaxLane=5 显式放开（本地赶时间、接受负载敏感红时用）
    val maxLane = providers.gradleProperty("testShardMaxLane").orNull?.toIntOrNull() ?: 3
    val adaptiveLane: Int? = if (isShardedRun && !adaptiveOff) {
        val context = if (isGateOrchestration) "releaseGate" else "testShards"
        if (overrideFreeGb != null) {
            mpv.watermark.MemoryWatermarkProbe.laneFor(overrideFreeGb, reserveGb, "$context/override", logger, maxLane)
        } else {
            // 配置期探针必须走 providers.exec（CC 官方支持的外部进程通道）——用 ProcessBuilder
            // 会触发「external process started at configuration time」，Gradle 9 直接把构建判失败
            // （20261030 权威 gate 轮实测：CC problems found → BUILD FAILED）。
            val raw = runCatching {
                // 注意：buildSrc 禁用 `.asText`（T-BS-01：按 daemon 平台字符集解码，GBK 机器读
                // UTF-8 会乱码，守卫 BannedApiSourceScanTest 钉住）——取字节再显式按 UTF-8 解。
                val bytes = providers.exec {
                    commandLine(mpv.watermark.MemoryWatermarkProbe.probeScript())
                }.standardOutput.asBytes.get()
                bytes.toString(Charsets.UTF_8)
            }.getOrElse { e ->
                logger.warn("mpv-test-lane: 配置期水位探针失败（${e.javaClass.simpleName}），回落静态缺省")
                ""
            }
            val reading = mpv.watermark.MemoryWatermarkProbe.parse(raw)
            if (reading == null) {
                logger.warn("mpv-test-lane: 水位探针不可用，回落静态缺省（fail-open）")
                null
            } else {
                mpv.watermark.MemoryWatermarkProbe.laneFor(reading.freeCommitGb, reserveGb, context, logger, maxLane)
            }
        }
    } else {
        null
    }
    val explicitLane = providers.gradleProperty("testShardConcurrency").orNull
    // 自适应只**抬高**、不压低：静态缺省（testShards 2 / releaseGate 3）是 T9 已 A/B 的已知安全档；
    // 低水位场景由券02 的快速失败闸门负责（free < floor 直接拒绝起跑），不靠把档位压到 1 续命
    // （压到 1 = 5 片全串行，实测 16m25s，比快速失败重跑更亏）。
    val laneLimit = explicitLane?.toIntOrNull()?.coerceIn(1, 8)
        ?: maxOf(adaptiveLane ?: 0, laneLimitDefault)
    logger.lifecycle(
        "mpv-test-lane: maxParallelLimit=$laneLimit " +
            "(adaptive=${adaptiveLane ?: "n/a"}, floor=$laneLimitDefault, explicit=${explicitLane ?: "none"}; " +
            "-PtestShardConcurrency > max(adaptive, floor); -PtestLaneAdaptive=off 回落静态)",
    )
    gradle.sharedServices.registerIfAbsent("mpvTestLaneLimiter", MpvTestLaneLimiterService::class.java) {
        // Gradle 9.7 API：BuildServiceSpec.maxParallelUsages = 同时可使用本服务的任务数上限
        // （javap gradle-core-api-9.7.0 BuildServiceSpec 实证；服务实例上无 maxParallelLimit）。
        maxParallelUsages.set(laneLimit)
    }
}

// 测试 classpath 排除 Vue 插件（org.jetbrains.plugins.vue）：
// LightPlatform 测试沙盒会无条件加载所有 bundled 插件，Vue 的 LSP 扩展（VueLspServerSupportProvider）
// 在无 node 的测试环境初始化失败，被 TestLogger 计入断言失败（随机污染测试结果）。
// 已知问题：https://github.com/JetBrains/intellij-platform-gradle-plugin/issues/1887（disabledPlugins 对测试无效）。
// 本插件不依赖 Vue，排除其 jar 安全。
// M17：同样排除 Database 插件（com.intellij.database）——本地平台为 Ultimate 时测试沙盒会加载它，
// 使 ExecuteSqlSupport.isAvailable() 恒为 true（无法单测 Community 降级路径）；排除后测试环境
// 稳定模拟「无 database 插件」（按钮降级/提示分支可测）。排除 jar 名含 database/sql 的模块 jar。
// M36.5.23：**DB 集成任务除外**——DB 集成测试需要 Database 插件真实加载（模拟 Ultimate
// 沙盒环境）。T-TestPerf14 S2：五类拆 testDb* 任务后按前缀特判（原单任务名 testDatabase
// 判断保留，兼容旧树/聚合容器同名不冲突）。
tasks.withType<Test>().configureEach {
    val isDbIntegration = name == "testDatabase" || name.startsWith("testDb")
    // T-ReleasePerf9：测试链并发限流挂接（mpvTestLaneLimiter 注册块见上方）——testShardN 分片、
    // testExternal、默认 test/testFast 全部在此面内；DB 集成桶豁免（testDb* 七路并行语义保持）。
    // 配置期调用，任务执行期共享限流许可；单任务独跑（test/testFast）不受限流影响。
    if (!isDbIntegration) {
        usesService(mpvTestLaneLimiter)
    }
    classpath = classpath.filter {
        !it.name.contains("vue", ignoreCase = true) &&
            (isDbIntegration || (
                !(it.name.contains("database", ignoreCase = true) && it.name.startsWith("intellij.")) &&
                    it.name != "database-plugin.jar" &&
                    !(it.name.contains("intellij.sql", ignoreCase = true))
                ))
    }
    // 默认 test（含平台型测试）保持串行（maxParallelForks=1）：多 fork 共享默认 test 沙盒，
    // PersistentFS 互踩（2026-09-14 T-TestPerf-03 实证 testForks=4 成片 PersistentFSConnector
    // 异常；分片任务注释同根因先例 191 失败）。安全并行走分片（./gradlew testShards 一命令，
    // 各片独立沙盒并发执行），见下方 testShards 定义。
    // T-TestPerf2：测试 JVM 堆可临时覆盖（Gradle Test 默认 maxHeapSize=512m），用法
    // -PtestHeapSize=2g；缺省不动，是否转正由实测收益定（见 T-TestPerf2 计划 P9）。
    providers.gradleProperty("testHeapSize").orNull?.let { maxHeapSize = it }
    // T-TestPerf4：测试 JVM GC 换型开关 -PtestGc=parallel（缺省 G1 不动）。测试负载是
    // 「大量短类 + 少数 UI 重类长跑」的批处理形态，理论契合 ParallelGC 吞吐优先的特性；
    // 2026-09-24 warm 对照实测：4 片 3m28s vs G1 同日 3m16s~3m30s——无明确收益（<5%），
    // 不落默认，开关保留供负载形态变化后复测（堆加大实验 T-TestPerf2 同样无收益，GC 是
    // 另一个维度，两维均已实测销账）。
    if (providers.gradleProperty("testGc").getOrElse("") == "parallel") {
        jvmArgs("-XX:+UseParallelGC")
    }
    // T-TestPerf5：SerialGC 档 -PtestGc=serial。2026-09-25 对照实测全程留档：筛查窗读出
    // −6.4%（177s/173s vs G1 基线 194s/180s），但晚窗收口 193s/188s 不复现，同刻 G1 对照
    // 187s/190s 与 serial 无差——机器窗口漂移 ±5~8%（execSum 配置无关地早窗 ~595s → 晚窗
    // ~630s）与效应同量级，净效应 ≈0，不落默认回退 G1。教训定例：效应量 <10% 的调参实验
    // 必须同窗交错 A/B，单窗成对对照 + 「复跑确认」防不了窗口漂移。开关保留供负载形态变化
    // 后按同窗交错法复测（ParallelGC 无收益是 T-TestPerf4 的另一档，两档均已实测）。
    if (providers.gradleProperty("testGc").getOrElse("") == "serial") {
        jvmArgs("-XX:+UseSerialGC")
    }
    // T-TestPerf5：测试 JVM 线程面治理 -PtestProcCap=N → -XX:ActiveProcessorCount=N（缺省
    // 不动）。2026-09-25 对照实测：cap3 189s / cap4（该跑中途 1 例 FatalPassthroughTest 回调
    // 计数断言失败，定向复跑 cap4×2 + 裸跑×2 全绿不复现——负载形态敏感的时序竞态嫌疑，非
    // 确定性语义影响）vs G1 基线均值 187s——无收益（<5%），路线销账不落默认；「线程面超额
    // 认领是争用主因」假说证伪（片间膨胀主分量不在 JVM 线程层，内存带宽/调度更可能）。
    // 开关保留供负载形态变化后复测。语义中立性前提：grep 实测仓内 src/ 无 availableProcessors
    // 依赖（2026-09-25），EDT 单线程模型不受影响。
    providers.gradleProperty("testProcCap").orNull?.toIntOrNull()?.let {
        jvmArgs("-XX:ActiveProcessorCount=$it")
    }
    // T-TestPerf9：JVM 线程面二代旋钮——一代 ActiveProcessorCount 帽的是应用线程池本身
    // （测试代码并行度一起被压，T5 证伪）；二代只压 JVM 自带线程，应用并发度不动。
    // 全部缺省不动，筛查轮 -P 逐旋钮启用；适用面=根项目全部 Test 任务（分片/外部桶/testFast），
    // agent 子项目不在面内（早收非瓶颈）。flag 合法性已用 JBR 17 -version 预验。
    // -PtestGcThreads=N → ParallelGCThreads=N + ConcGCThreads=N/2：5 并发平台测试 JVM 的
    //   G1 工作线程（Windows 12 逻辑核默认 ~6-8/JVM）×5 风暴压帽，GC 停顿更短更稳。
    providers.gradleProperty("testGcThreads").orNull?.toIntOrNull()?.let { n ->
        jvmArgs("-XX:ParallelGCThreads=$n", "-XX:ConcGCThreads=${maxOf(1, n / 2)}")
    }
    // -PtestCiCount=N → CICompilerCount=N（tiered 下限取 3 档）：JIT 编译线程 ×5 压帽，
    //   头部 EDT 敏感类的 JIT 争用回吐。
    providers.gradleProperty("testCiCount").orNull?.toIntOrNull()?.let {
        jvmArgs("-XX:CICompilerCount=$it")
    }
    // -PtestNoExplicitGc=true → DisableExplicitGC：平台显式 System.gc() 触发的全暂停 ×5
    //   JVM 根除。风险=512m 堆失去显式回收点（V3：OOM/长尾即销案）。
    if (providers.gradleProperty("testNoExplicitGc").getOrElse("") == "true") {
        jvmArgs("-XX:+DisableExplicitGC")
    }
    // -PtestTieredStop=true → TieredStopAtLevel=1：bootstrap JIT 预热提速，长 UI 类回退风险
    // （T8 S5 遗留项，本轮一并筛查销案或转正）。
    if (providers.gradleProperty("testTieredStop").getOrElse("") == "true") {
        jvmArgs("-XX:TieredStopAtLevel=1")
    }
    // Docker 测试默认关闭（20260923 用户定例）：daemon 半活态会让 testcontainers 的 ping
    // 探测阻塞无超时（runCatching 兜不住阻塞形态，20260923 发行门禁实录三片挂起 40 分钟），
    // daemon 活着时真容器方言验证的 pull/run 耗时也不可控。只有显式 -PmpvDockerTests=true
    // 才启用；DialectDockerVerificationTest 读该系统属性做 Assume 前置判定。
    systemProperty("mpvs.docker.tests.enabled", providers.gradleProperty("mpvDockerTests").getOrElse("false"))
    // T-CiRelease（20261024）：测试 JVM 时区钉死 Asia/Shanghai——时间类断言按 CST 写死
    //（SqlLogSearchRowTimeTest：1705285845123 → 2024-01-15 10:30:45 Asia/Shanghai），
    // GitHub runner 为 UTC 会整体差 8 小时成片假失败。钉在 JVM 级而非逐测试的原因：
    // formatTimestampFragment 的 SimpleDateFormat 走 ThreadLocal 缓存、构造时固化默认时区，
    // 测试内 setDefault 管不到已缓存实例。本机即 CST，此参数为无操作。
    jvmArgs("-Duser.timezone=Asia/Shanghai")
}

// 并行分片测试（2026-08-15 提速；2026-09-14 T-TestPerf-04 自动分区；2026-09-15 T-TestPerf2
// 时长均衡分区 + 片数可配 + 一命令并行与聚合报告）。
// 用法：./gradlew testShards 一命令即真并行（org.gradle.parallel=true，各片任务并发执行，
// 并发实证与提速数据见 docs/开发计划/20260915-0238-T-TestPerf2 计划 §六）；串行权威全量
// 仍是 ./gradlew test（对照口径保留）。
// 前提：每片独立 sandbox 目录——多 worker 共享 .intellijPlatform/sandbox/.../system-test 会
// PersistentFS 互踩损坏（实证：191 失败 + 卡死），分片隔离后无共享写点。
// 包含清单为配置期扫描 src/test 自动分区（时长权重贪心均衡，详见 testIde 块注释），
// 新增测试类零维护自动进片；DB 集成三类只在 testDatabase。
// M36.5.23：DB 集成测试默认 demo 项目路径（顶层求值，避免 task lambda 捕获 Project 破坏配置缓存）
val mpDemoProjectDirDefault = rootProject.projectDir.parentFile
    .resolve("mybatis-plus-sql-visualizer-demo").absolutePath
// T-ContribDemoFixture：demo 路径单一来源（-PmpDemoProjectDir 可覆盖），供 testShards 预检、
// testDatabase 系统属性与报错文案共用；预检本身可用 -PskipDemoFixtureCheck=true 显式关闭。
val mpDemoProjectDir = providers.gradleProperty("mpDemoProjectDir").orNull ?: mpDemoProjectDirDefault
val skipDemoFixtureCheck = providers.gradleProperty("skipDemoFixtureCheck").orNull?.toBoolean() ?: false

// T-TestPerf14 S2：DB 集成任务 → 每个 FQCN 一个并行 testIde 任务（UI025 起 6 个），聚合任务 testDatabase
// dependsOn 与各任务 filter 均由本清单驱动。分片排除清单仍是 testIde 块内 dbIntegrationClasses
// ——两份清单类集必须一致，类集漂移时 SHARD TOTAL 计数与 parity 会现形。
// UI025：新增预览错列 ExternalAnnotator 判别测试——它需要 SQL 语言/文件类型（Database 插件），
// 默认 test 的 classpath 已剥离 database jar，故与 DB 集成类同走 testDb* 通道。
val dbIntegrationTaskSpecs: List<Pair<String, String>> = listOf(
    "testDbConsole" to "cn.song.mybatisplus.visualizer.execute.DbConsoleIntegrationTest",
    "testDbSelector" to "cn.song.mybatisplus.visualizer.execute.DataSourceSelectorIntegrationTest",
    "testDbDiagram" to "cn.song.mybatisplus.visualizer.execute.HeavyNativeDatabaseDiagramJoinChainTest",
    "testDbExecDocGate" to "cn.song.mybatisplus.visualizer.execute.ExecDocGateIntegrationTest",
    "testDbDriverPipeline" to "cn.song.mybatisplus.visualizer.execute.DbDriverPipelineTest",
    "testDbSchemaAnnotator" to "cn.song.mybatisplus.visualizer.ui.annotate.PreviewUnknownColumnAnnotatorTest",
    // T-PreviewSideQuery：旁路查询真库链（COUNT/EXPLAIN/失败解包），需 Database 插件与真实 MySQL
    "testDbPreviewSideQuery" to "cn.song.mybatisplus.visualizer.execute.PreviewSideQueryIntegrationTest",
    // T-GaussDbDialect 券 06：平台 SQL 方言注册的运行期可见性（SqlDialectMappings 候选列表）——
    // 默认 test 桶 classpath 无 Database 插件，只能在 DB 集成通道断言；无需真实库连接
    "testDbPlatformDialect" to "cn.song.mybatisplus.visualizer.database.PlatformSqlDialectRegistrationTest",
)

// T-TestPerf2：Gradle JUnit XML testsuite 根属性解析（只读文件头，避免大文件全读）与结果文件枚举。
// 供配置期时长权重与 testShards 聚合报告两处复用；解析失败静默跳过（半截 XML 不挡分区）。
fun parseTestSuiteAttrs(f: File): Map<String, String>? = runCatching {
    val head = f.useLines { lines -> lines.take(8).joinToString("\n") }
    val tag = head.substringAfter("<testsuite", "").substringBefore(">")
    if (tag.isEmpty()) null
    else Regex("([\\w.-]+)=\"([^\"]*)\"").findAll(tag).associate { it.groupValues[1] to it.groupValues[2] }
}.getOrNull()

fun listSuiteXmls(dir: File): List<File> =
    dir.listFiles { f: File -> f.isFile && f.name.startsWith("TEST-") && f.name.endsWith(".xml") }?.toList() ?: emptyList()

// T-TestPerf2：分片数，-PtestShardCount=N（上限 6）。
// T-TestPerf3（2026-09-17）定案 3 片：4 片 3m02s vs 3 片 2m03s——当时片内执行仅 ~84s 量级，
// 固定开销（JVM 冷启+沙盒准备）占比高，片数越多固定开销×片数越亏。
// T-TestPerf4（2026-09-24）在片执行 ~210s 的新负载下 warm 重测：3 片 3m57s / 4 片 3m30s、
// 3m16s / 5 片 3m36s——固定开销占比下降后拐点右移到 4 片，默认改 4。代价是 execSum 总和
// 随并发度膨胀（601→687→858s），5 片起竞争损耗超过切分收益；负载形态再变时按同法重测。
// T-TestPerf5（2026-09-25）：5 片×SerialGC 复测 186s 仍回劣（4 片×serial 同日 173~177s；
// execSum 718s，+20% 并发膨胀不随 GC 换型消失）——4 片定案维持。
// T-TestPerf7（2026-09-27）保质期规则第三次触发后 5 片翻案定案：T6（execSum −13.8%）+T7-S1
// （testExternal 扩容再摘 ~20s）之后片执行降到 ~135s 档、testExternal 桶又把签名护栏的
// 外部 JVM 核验移出分片算术，同窗交错 A/B 实测 4/5/6 片各两轮 warm＝4 片 166s/170s
// （4b 189s 含 1 例负载敏感 flake 嫌疑不复现）/5 片 153s·146s/6 片 167s·160s（execSum +39%
// 并发膨胀、Commit 峰值 66.4GB 触顶）——**5 片中位 150s，−10.7% 达标定案**；Commit 峰值
// 5 片 65.0GB（余量 9.7GB > 8GB 判据），两轮零假失败。负载形态再变（execSum 或固定开销）
// 时按同法重测。
// 顶层声明——分片注册（testIde 块内）与聚合报告（testShards 任务）两处共用。
val testShardCount = providers.gradleProperty("testShardCount").orNull
    ?.toIntOrNull()?.coerceIn(1, 6) ?: 5

// T-TestPerf12 S2 槽位补偿：对恒临界槽位按少装补偿——均衡器贪心比较键对目标槽位除以补偿
// 因子（<1 放大其负载视图 → 更早判满 → 装配量按因子缩水），聚合 expected 同步缩放（告警刻度
// 自洽：临界片的「预期」按补偿后口径对照，结构性因子不再被误判为缺权）。
// -PtestShardHandicap=0.87（因子，钳 0.5..1.0，缺省 1.0=无补偿）+ -PtestShardHandicapTarget=2
// （目标槽位号 1-based）。宁欠勿过：欠补偿只损失部分收益，过补偿制造新失衡（失衡告警是
// 同轮相对量，过补会立刻以新临界片形态现形）。
// T12 定案（2026-10-02，R1~R6 六轮）：临界片**跟类清单走不跟槽位走**（对调探针定案：互换
// shard1/shard2 清单后临界从 shard2 翻到 shard1），T11「OS 调度/核分配的环境因素」猜想证伪；
// 且超权片逐轮漂移（R1/R2=shard2、R3=原清单进 shard1、R4/R6=shard2）——固定槽位补偿打不准，
// **不落默认**（D34）。旋钮保留作未来「某片真恒临界」形态的应急杠杆；主修通道=权重稳态
// （冷轮样本污染经 K=2 mtime 窗口两轮挤出自愈，R4 实证失衡 +30%→+10.8%、drift 1.012）。
val testShardHandicap = providers.gradleProperty("testShardHandicap").orNull
    ?.toDoubleOrNull()?.takeIf { it in 0.5..1.0 } ?: 1.0
val testShardHandicapTarget = providers.gradleProperty("testShardHandicapTarget").orNull
    ?.toIntOrNull()?.coerceIn(1, testShardCount) ?: 2

// T-TestPerf14 S1：auto-handicap——T13 §七.1 裁定规则（同槽 ≥3 连失衡 WARN 且临界片清单
// 更替不漂移 → 按 1/(1+超权比中位) 启用补偿）的机制化执行件。三段式：
//   ① 聚合 doLast 每轮落盘 {临界槽位, 归一化临界比 relCrit, 临界片类清单 hash, 本轮补偿模式}
//      到 build/test-results/shard-imbalance-history.jsonl（写入失败静默跳过——历史是优化
//      输入不是正确性依赖；build/test-results 每轮变化本就使配置缓存失效，下轮读到必含上轮）；
//   ② 配置期（均衡器装箱前）读历史尾部执行触发判定，命中则自动启用补偿（因子现算）；
//   ③ 过补自愈：最近 2 轮 auto 中出现「临界换到非目标槽」或「目标槽被压显著偏轻
//      （relCrit<0.90）」→ 本轮退避（mode=cooldown，无补偿可比），此后需重新攒满 3 连可比
//      WARN 才再启用（防振荡，宁欠勿过）。
// 安全三重：总闸 -PtestShardAutoHandicap（**缺省 true**，T15 定案——T14 D45 曾定缺省关，因
// auto 轮 3 轮中 2 轮踩 SqlPreviewService 族顺序竞态〔O-97 18 例+O-93 族 1 例〕；T15 已两层
// 治理该竞态根因=测试间全局设置态泄漏〔27 类 setUp 进态出厂复位+BindState 堵源，复现实验
// 修复前 18/39 红→修复后同命令 39/0 绿〕，分区变化不再改写测试结果，恢复「确定性门禁」
// 语义后翻回默认开；需要退回旧口径时 -PtestShardAutoHandicap=false 显式关）；手工
// -PtestShardHandicap 显式给定时全程优先（auto 不介入）；零历史（新树/清 build）不触发、
// 行为与无补偿逐字节等价。
// 语义边界（对照 D34）：D34 否决的是「无条件默认开启」；本机制只在触发条件满足时介入，
// 是 T13 既定裁定的执行件而非翻案。
val testShardAutoHandicap = providers.gradleProperty("testShardAutoHandicap").orNull?.toBoolean() ?: true

// 失衡历史一轮的读取形态（自产 jsonl 字段固定，正则提取不引 JSON 依赖）。
// mode：off=无补偿可比轮 / manual|auto=补偿生效轮（expected 已缩放，relCrit≈1 为到位，不可比）
// / cooldown=过补退避轮（无补偿、可比）。触发判定的可比集 = off + cooldown。
data class MpvImbalanceRound(
    val critSlot: Int,
    val relCrit: Double,
    val classSetHash: String,
    val mode: String,
    val factor: Double?,
    val target: Int?,
)

val mpvImbalanceHistory: List<MpvImbalanceRound> = runCatching {
    val f = file("build/test-results/shard-imbalance-history.jsonl")
    if (!f.isFile) emptyList()
    else f.readLines().filter { it.isNotBlank() }.takeLast(12).mapNotNull { line ->
        fun str(key: String) = Regex("\"$key\":\"([^\"]*)\"").find(line)?.groupValues?.get(1)
        fun num(key: String) = Regex("\"$key\":(-?[\\d.]+)").find(line)?.groupValues?.get(1)
        runCatching {
            MpvImbalanceRound(
                critSlot = num("critSlot")?.toDoubleOrNull()?.toInt() ?: return@mapNotNull null,
                relCrit = num("relCrit")?.toDoubleOrNull() ?: return@mapNotNull null,
                classSetHash = str("classSetHash") ?: "",
                mode = str("mode") ?: "off",
                factor = num("factor")?.toDoubleOrNull(),
                target = num("target")?.toDoubleOrNull()?.toInt(),
            )
        }.getOrNull()
    }
}.getOrDefault(emptyList())

// 本轮有效补偿三元组 (因子, 目标槽, 落盘模式)：手工参数优先 > auto 判定命中 > 无补偿。
// 触发判据（T13 规则的机器形态）：按时间序最近 3 轮全部是可比 WARN 轮（off/cooldown 且
// relCrit>1.10）、临界槽位恒定、且临界片类清单 hash ≥2 个不同值（清单换人临界不换槽=结构性
// 恒槽；清单不变=个别类缺权嫌疑，走 weight-refresh 通道不补偿）。auto/manual 轮插队即断链
// ——过补退避后需重新攒满 3 连才再启用（防振荡，宁欠勿过）。
// 过补自愈：最近 2 轮 auto 中任一轮 relCrit 仍 >1.10（auto 轮的 expected 已按补偿缩放，
// 仍超阈=补偿未达标或制造了新失衡）→ 本轮退避（mode=cooldown，无补偿可比）。
val mpvHandicapDecision: Triple<Double, Int, String> = run {
    if (testShardHandicap < 1.0) return@run Triple(testShardHandicap, testShardHandicapTarget, "manual")
    if (!testShardAutoHandicap || mpvImbalanceHistory.size < 3) return@run Triple(1.0, 2, "off")
    val hist = mpvImbalanceHistory
    val recentAuto = hist.takeLast(2)
    val overHeal = recentAuto.size == 2 && recentAuto.all { it.mode == "auto" } &&
        recentAuto.any { it.relCrit > 1.10 }
    if (overHeal) return@run Triple(1.0, 2, "cooldown")
    val recent = hist.takeLast(3)
    val trigger = recent.all { (it.mode == "off" || it.mode == "cooldown") && it.relCrit > 1.10 } &&
        recent.all { it.critSlot == recent[0].critSlot } &&
        recent.map { it.classSetHash }.distinct().size >= 2
    if (!trigger) return@run Triple(1.0, 2, "off")
    val medianOver = recent.map { it.relCrit - 1.0 }.sorted()[1]
    val factor = (1.0 / (1.0 + medianOver)).coerceIn(0.5, 1.0)
    val target = recent[0].critSlot
    logger.lifecycle(
        "testShards AUTO-HANDICAP engaged: shard%d factor=%.2f（近3可比轮超权中位 +%.0f%%；触发=T13 规则：同槽≥3连且清单更替不漂移）"
            .format(target, factor, medianOver * 100),
    )
    Triple(factor, target, "auto")
}
val effHandicapFactor = mpvHandicapDecision.first
val effHandicapTarget = mpvHandicapDecision.second
val effHandicapMode = mpvHandicapDecision.third

// 测试源码单遍扫描（T-TestPerf3 提为顶层：分片分区与 testFast 划桶两处共用；内容提取类名，
// Kotlin 文件名≠类名合法——CrudPreviewFlowAndTypesTest.kt 内是 DbTypeQuoteHintTest 的先例）。
// Triple(包名, 顶层测试类名列表, 原文)。
val testFileEntries: List<Triple<String, List<String>, String>> = file("src/test/kotlin").walkTopDown()
    .filter { it.isFile && (it.name.endsWith("Test.kt") || it.name.endsWith("Test.java")) }
    .map { f ->
        val text = f.readText()
        val pkg = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
            .find(text)?.groupValues?.get(1) ?: ""
        val classNames = Regex(
            """(?m)^(?:public\s+|internal\s+|open\s+|abstract\s+|final\s+|data\s+|sealed\s+|annotation\s+)*class\s+(\w+)\b""",
        ).findAll(text).mapNotNull { it.groupValues[1].takeIf { cn -> cn.endsWith("Test") } }.toList()
        Triple(pkg, classNames.ifEmpty { listOf(f.name.substringBeforeLast('.')) }, text)
    }.toList()

// T-TestPerf3（2026-09-17）：testFast 秒级反馈通道。
// 划桶规则（保守）：源码零 `com.intellij` 引用的测试类才进快桶——凡是 import 平台类的
// 一律留平台桶。第一轮 T-TestPerf-03 实证 68 个「纯逻辑」用例隐性依赖同 worker JVM 平台
// 测试先建立的 Application/LaF 状态（脱离平台桶即红）；本轮基线数据（平台型 1790 用例
// 222.1s = 94.7%、纯逻辑 2531 用例 12.5s = 5.3%）同时证伪了「纯逻辑分桶给全量提速」的
// 价值（12.5s 总量无提速空间，68 例债务清偿亦无收益——正式销账，勿再立项）。
// 本任务的价值重定位：**日常开发的秒级反馈**——改纯逻辑面（parser/converter/tokenizer/
// 守卫测试）时 ./gradlew testFast 半分钟内验证，不必等 4 分钟全量。全量权威仍是 test/testShards。
// classpath 复用默认 test 任务成品（IPGP 把平台 jar 直配任务不进 sourceSets.runtimeClasspath，
// T-TestPerf-03 实验趟通）；DB 集成四类 import 平台 Database 链天然不进桶；forks=1（12s 总量
// 无并行收益，避免新假失败形态）。
val fastBucketClasses: List<String> = testFileEntries
    .filter { (_, _, text) -> !text.contains("com.intellij") }
    .flatMap { (pkg, classNames, _) -> classNames.map { cn -> if (pkg.isEmpty()) cn else "$pkg.$cn" } }
    // 隐性平台依赖排除名单：测试源码无 com.intellij 字面引用，但被测生产类内部走平台 UI/
    // 静态状态，脱离平台 JVM 即红（testFast 首跑 4 类 28 例实证；失败形态=NoClassDefFoundError
    // UIUtil / Application NPE / Must be precomputed）。新类踩同坑时在此追加。
    .filter { it !in setOf(
        "cn.song.mybatisplus.visualizer.ui.diagram.DiagramPanelTest",
        "cn.song.mybatisplus.visualizer.ui.diagram.DiagramRendererTest",
        "cn.song.mybatisplus.visualizer.actions.CrudSqlPreviewFlowTest",
        "cn.song.mybatisplus.visualizer.feedback.ErrorReportDialogLayoutTest",
    ) }

// T-TestPerf6：S3 外部进程型护栏桶——签名护栏类对本机发行版逐个起外部 JBR JVM 核验，
// 不需要平台沙盒（testFast 非沙盒 JVM 定向全绿实证，20260926）；从分片类集摘出后与 4 片
// 并行执行（桶内 invokeAll 池并行核验 + 桶与分片并发），护栏族扩容不再挤占片墙钟。
// T-TestPerf7 S1 扩容四类（20260927，桶内单跑行为验证绿后入册）：AcpSignature（纯 JUnit 声明，
// 源文本 com.intellij 均为字面量非 import，T6 留尾巴「验证通过后移入」）+ S2 快照化三类守卫
// （源码扫描守卫，repoRoot 走 user.dir 上溯，Gradle Test 工作目录=项目根同样满足；
// EnumReachability 本就是 testFast 桶成员=日常非沙盒实证）。全部红则回退留分片（68 例隐性
// 平台依赖教训口径），分片 execSum 相应 −19s 档。
val testExternalClasses = setOf(
    "cn.song.mybatisplus.visualizer.mcp.McpDbSignatureCrossVersionTest",
    "cn.song.mybatisplus.visualizer.update.PlatformSignatureCrossVersionTest",
    "cn.song.mybatisplus.visualizer.acp.AcpSignatureCrossVersionTest",
    "cn.song.mybatisplus.visualizer.config.SettingsRealityGuardTest",
    "cn.song.mybatisplus.visualizer.convention.BannedApiSourceScanTest",
    "cn.song.mybatisplus.visualizer.convention.EnumReachabilityGuardTest",
)

tasks.register<Test>("testExternal") {
    group = "verification"
    description = "签名护栏外部分离桶（非沙盒 JVM，与 testShards 分片并行；一命令全量自动触发）"
    testClassesDirs = sourceSets["test"].output.classesDirs
    // classpath 三段构造同 testFast（T-TestPerf3 实测定案）：main+test output 前置（mpvBuild
    // manifest 读取前提）、平台 jar 复用默认 test 成品、剔除插件自身 jar
    classpath = sourceSets["main"].output + sourceSets["test"].output +
        tasks.named<Test>("test").get().classpath.filter {
            !it.name.startsWith("mybatis-plus-sql-visualizer-")
        }
    filter {
        testExternalClasses.forEach { includeTestsMatching(it) }
    }
    maxParallelForks = 1
    // T-TestPerf8 S6：强制每轮真跑。分片任务因 MPV-Build 时间戳（Jar 任务恒重跑）+ prepareSandbox
    // 链而恒重跑，本桶 classpath 不含插件 jar，无源码变更的连跑会被判 UP-TO-DATE 静默跳过
    // （20260928 warm2 轮实证）——聚合报告照读旧 XML，护栏「本轮已验」是假。与分片语义对齐；
    // 墙钟代价≈0（桶与分片并行且早收，warm1 实证 59.4s ≪ 片窗口 120s+）。
    outputs.upToDateWhen { false }
    // T-TestPerf10 S2：与分片同款 .round-start 戳（聚合的 mtime 闸需要），语义见分片 doFirst 注释
    inputs.property("mpvResultsRootAbs", project.projectDir.absolutePath)
    doFirst {
        val root = inputs.properties["mpvResultsRootAbs"] as String
        val dir = File(root, "build/test-results/testExternal")
        dir.mkdirs()
        File(dir, ".round-start").writeText(System.currentTimeMillis().toString())
    }
    // T-ReleasePerf6 S1：起止毫秒锚点（与 testShardN/verifyGate 同款），供 releaseGate 整链
    // 分解读数（本桶与分片并行早收，锚点重叠与否则是「浮动态任务真并行」的持续审计点）
    doLast { logger.lifecycle("testExternal END ${System.currentTimeMillis()}") }
}

// ---------- T-DialectExternalGate（2026-10-31，用户定例） ----------
// 真机方言验证的门禁口径：**测试服务器外部实例**，DBX 只做临时探测，Docker 用例维持默认关
// （DialectDockerVerificationTest 仍可 -PmpvDockerTests=true 本地手工开，但不再承担发版判据）。
//
// 为什么单独成任务而不是塞进 testShards：它要连外部网络与一批真库，离线/实例下线会把权威全量
// 拖红；作为独立 verification 任务显式触发，读数写 build/reports/dialectExternalVerify/summary.md
// 并在发行说明里登记，既不污染日常门禁，也不再出现「Docker 不可用→静默跳过→真机读数空缺还写成通过」。
//
// 矩阵文件默认 `.local/dialect-external-matrix.properties`（含明文凭据，.local 已 gitignore），
// 可用 -PdialectExternalMatrix=<路径> 覆盖；模板见 dev-support/dialect-external/matrix.example.properties。
// 矩阵路径在配置期就求成普通 String：用 provider 包 layout 访问器会把 Gradle 脚本对象引用
// 带进配置缓存（实测 "cannot serialize Gradle script object references"），故不用 provider 惰性求值。
val dialectExternalMatrixPath: String = providers.gradleProperty("dialectExternalMatrix")
    .getOrElse(layout.projectDirectory.file(".local/dialect-external-matrix.properties").asFile.absolutePath)

tasks.register<Test>("dialectExternalVerify") {
    group = "verification"
    description =
        "真机方言验证（外部实例，无 Docker）：把插件渲染的 SQL 套件与保留字扫描在真实引擎上执行；" +
            "前置＝矩阵文件（默认 .local/dialect-external-matrix.properties）与实例可达，缺失硬失败并给指引"
    testClassesDirs = sourceSets["test"].output.classesDirs
    // classpath 构造同 testExternal：main+test output 前置，剔除插件自身 jar（其 classpath 由沙盒提供）
    classpath = sourceSets["main"].output + sourceSets["test"].output +
        tasks.named<Test>("test").get().classpath.filter {
            !it.name.startsWith("mybatis-plus-sql-visualizer-")
        }
    filter {
        includeTestsMatching("cn.song.mybatisplus.visualizer.sql.verify.DialectExternalMatrixVerificationTest")
    }
    maxParallelForks = 1
    // 每轮真跑：实例侧数据会被套件自行清理，但任务本身无源码变更时会被判 UP-TO-DATE，
    // 读旧 XML 等于拿上一轮读数冒充本轮（与 testExternal 同款理由）
    outputs.upToDateWhen { false }
    systemProperty("mpvs.external.matrix", dialectExternalMatrixPath)
    systemProperty(
        "mpvs.external.summary",
        layout.buildDirectory.file("reports/dialectExternalVerify/summary.md").get().asFile.absolutePath,
    )
    // 路径经 inputs.property 传给 doFirst：直接闭包引用脚本级 val 会被配置缓存判为
    // 「Gradle script object references」（实测两次红），inputs 是官方 CC 兼容通道
    inputs.property("mpvDialectMatrix", dialectExternalMatrixPath)
    doFirst {
        // 前置缺失硬失败并给可执行指引——不做静默跳过（跳过会让「没验」伪装成「验过」）
        val matrixPath = inputs.properties["mpvDialectMatrix"] as String
        val matrix = File(matrixPath)
        if (!matrix.isFile) {
            throw GradleException(
                "dialectExternalVerify 需要外部实例矩阵文件：$matrixPath 不存在。\n" +
                    "  模板：dev-support/dialect-external/matrix.example.properties\n" +
                    "  实际文件放 .local/（整目录 gitignore，可含明文凭据）：.local/dialect-external-matrix.properties\n" +
                    "  或用 -PdialectExternalMatrix=<路径> 指向别处；实例清单见 .local/test-databases.md",
            )
        }
        logger.lifecycle("dialectExternalVerify START ${System.currentTimeMillis()} matrix=$matrixPath")
    }
    doLast { logger.lifecycle("dialectExternalVerify END ${System.currentTimeMillis()}") }
}

tasks.register<Test>("testFast") {
    group = "verification"
    description =
        "纯逻辑快测（源码零 com.intellij 引用的测试类，秒级反馈通道；全量权威仍是 test/testShards）"
    testClassesDirs = sourceSets["test"].output.classesDirs
    // classpath 三段构造（T-TestPerf3 实测两轮定案）：
    //  ① main+test output **前置**——PluginBuildInfo 的「开发/测试形态类从 classes 目录加载、
    //     无 jar 可读 → mpvBuild()=null」是测试前提（testMpvBuildNullInTestEnvironment）；
    //     直接复用默认 test 成品 classpath 时插件自身 jar（IPGP/mcpStub 挂载、含构建脚本注入的
    //     MPV-Build manifest）排在可达位，类解析撞 jar 读出真实构建时间戳 → 假失败；
    //  ② 平台 jar 仍须复用默认 test 成品（IPGP 平台 jar 直配任务、不进 sourceSets.runtimeClasspath，
    //     T-TestPerf-03 趟通；生产类 companion 的 Logger.getInstance 等静态初始化在类加载期解析平台类）；
    //  ③ 剔除插件自身 jar——①前置后它只剩干扰面（manifest 读取前提 + 陈旧产物劫持形态）无收益。
    classpath = sourceSets["main"].output + sourceSets["test"].output +
        tasks.named<Test>("test").get().classpath.filter {
            !it.name.startsWith("mybatis-plus-sql-visualizer-")
        }
    filter {
        fastBucketClasses.forEach { includeTestsMatching(it) }
    }
    maxParallelForks = 1
}

// T-TestPerf4：聚合报告「预期 vs 实测」对照与并集 parity 校验的数据通道——testIde 配置期
// 算出的预期各片 execSum 与计划类全集，先落这两个顶层 var，testShards 任务注册时写进
// inputs.property（配置期即固化为 String，规避 doLast 引用脚本顶层对象导致的整段不可序列化，
// 运行期经 inputs.properties 读回，同 testDatabase 的 mpProjectDirAbs 先例）。权重与分区
// 每轮随结果目录变化重算（配置缓存对配置期目录列举做输入追踪，run2 实证日志
// 「cannot be reused because directory build\test-results\testShard1 has changed」），
// 因此对照行每轮反映最新权重表的预期，与实测并排后权重失准一眼可见。
var shardPlanExpectedLoads: String = ""
var shardPlanClasses: String = ""
// T-TestPerf14 S1：各片类清单 hash（片序→hash，"|" 连接）——聚合落盘时取临界片的 hash，
// 供 auto-handicap 判定「临界不随清单更替漂移」。
var shardPlanHashes: String = ""

// T-ReleasePerf9（20261029）：分片错峰共享前置——T-ReleasePerf7 N1「分片叶子 doFirst sleep」的
// 迁移版。叶子在 doFirst 内 sleep 会占住 T-ReleasePerf9 限流槽：releaseGate 编排内限流 3 时
// 首波三片各睡 285s 占槽正常，第二波分片排队拿到槽后会再白睡整段 285s（总墙钟 +5min 量级）。
// 改为所有分片 dependsOn 本任务——它只执行一次、睡满 testStaggerMs 后释放，分片们随后抢槽：
// 分片真实起点仍 ≥ t0+错峰值，时间线与原「每片各自 doFirst sleep」逐字等价（仅延迟启动，
// 不删不减不重排任何测试语义）；差异只在限流下不再有第二波白睡。缺省逻辑与 verifyGate 侧
// gateStaggerMs 同款机制（invokedTaskNames 判定），但值独立定案：releaseGate 编排内 **240000ms**
// （S3 A/B 同窗对照实测——A 轮 285s 全链 12m1s vs B 轮 240s 完整轮 10m48s 且 e2e PASS=26/0：
// 分片窗（240s+两波）嵌回 verifyMarketplace 长杆（~583s 段）内，总墙钟回到 T8 权威轮 675s 线
// 以内；285s 时分片尾超窗 ~46s 拖长总链），日常 testShards 0，-PtestStaggerMs 显式覆盖。
// 块内声明局部值（CC 约束：脚本顶层 val 经任务动作引用不可序列化，T-ReleasePerf7 S2 实测）。
tasks.register("testShardStagger") {
    group = "verification"
    description =
        "分片错峰共享前置（releaseGate 编排内延迟 testStaggerMs 默认 240s，T-ReleasePerf9 S3 A/B 定案；仅延迟启动，时间线与 T7 叶子 sleep 等价）"
    val invokedTaskNamesForStagger = gradle.startParameter.taskNames
    val testStaggerMs = providers.gradleProperty("testStaggerMs").orNull?.toLongOrNull()
        ?: if (invokedTaskNamesForStagger.contains("releaseGate")) 240_000L else 0L
    inputs.property("mpvStaggerMs", testStaggerMs)
    // T-ReleasePerf10 券02：执行期水位快速失败闸门——testShardN 全体 dependsOn 本任务且本任务无
    // 输出恒执行，闸门在分片测试 JVM 出生前判定（配置期捕获 floor，CC 约束同 mpvStaggerMs；
    // 缺省 floor 6GB，-PwatermarkFloorGb 覆盖，-PwatermarkGate=off 关闭；探针失败 fail-open）。
    val watermarkFloorGb = if (providers.gradleProperty("watermarkGate").orNull == "off") {
        0.0
    } else {
        providers.gradleProperty("watermarkFloorGb").orNull?.toDoubleOrNull() ?: 6.0
    }
    inputs.property("mpvWatermarkFloorGb", watermarkFloorGb)
    doFirst {
        val ms = (inputs.properties["mpvStaggerMs"] as? Number)?.toLong() ?: 0L
        mpv.watermark.MemoryWatermarkProbe.checkGate(
            (inputs.properties["mpvWatermarkFloorGb"] as? Number)?.toDouble() ?: 6.0,
            "testShardStagger（分片测试 JVM 出生前）",
            logger,
        )
        if (ms > 0) {
            logger.lifecycle("testShardStagger sleep ${ms}ms")
            Thread.sleep(ms)
        }
    }
}

intellijPlatformTesting {
    testIde {
        // T-TestPerf-04（2026-09-14）分片包含清单自动分区 + T-TestPerf2（2026-09-15）时长均衡：
        // 配置期扫描 src/test 推导 FQCN 全集（DB 集成三类仍只在 testDatabase，与默认 test 口径一致），
        // 权重来自 build/test-results 下历史 XML（test/ 与 testShardN/ 全扫，同类取 mtime 最新——
        // 串行与分片互相喂养）；无数据类取已知权重中位数，全新检出零数据时全体同权，
        // 退化为按类名字典序贪心均分，仍确定性。贪心排序键 = (时长降序, 类名升序)，
        // 同输入必得同分区——双开终端两进程与配置缓存复用前后结果一致。
        // 配置缓存语义与类发现一致：新增测试类/时长变化在下一次配置失效后生效，短暂失衡不影响正确性。
        // 片数：-PtestShardCount=N（默认 4，T-TestPerf4 定案，依据见顶层 testShardCount 注释）。旧类名哈希两片实测失衡 26%（157.9s/93.7s），
        // 时长均衡两片 125.8s/125.8s——并行墙钟被重片拖死的问题根除（数据见 T-TestPerf2 计划 §二）。
        // 类发现走顶层 testFileEntries（T-TestPerf3 提升为顶层，与 testFast 划桶共用单遍扫描）；
        // 列 0 的 class 声明含 Kotlin/Java 常见修饰符；无顶层类声明时回退文件名（嵌套类套件等形态）。
        // 新增测试类零维护自动进片。
        // DB 集成三类仍只在 testDatabase 运行（与默认 test 的排除口径一致）。
        val dbIntegrationClasses = setOf(
            "cn.song.mybatisplus.visualizer.execute.DbConsoleIntegrationTest",
            "cn.song.mybatisplus.visualizer.execute.DataSourceSelectorIntegrationTest",
            "cn.song.mybatisplus.visualizer.execute.HeavyNativeDatabaseDiagramJoinChainTest",
            // T-ExecDocGate：执行文档落盘纪律断言（testDatabase 通道，Database 插件链）
            "cn.song.mybatisplus.visualizer.execute.ExecDocGateIntegrationTest",
            // T-TestDbDriver：驱动解析管线回归（artifact 清单/版本解析/classpath 落点）
            "cn.song.mybatisplus.visualizer.execute.DbDriverPipelineTest",
            // UI025：预览错列 ExternalAnnotator（需要 SQL 语言/文件类型 = Database 插件）
            "cn.song.mybatisplus.visualizer.ui.annotate.PreviewUnknownColumnAnnotatorTest",
            // T-PreviewSideQuery：旁路查询真库链（Database 插件 + 真实 MySQL）
            "cn.song.mybatisplus.visualizer.execute.PreviewSideQueryIntegrationTest",
            // T-GaussDbDialect 券 06：平台 sql.dialect 运行期可见性断言（需要 Database 插件）。
            // 补登（T-XcDialects3 收口门禁实测 2026-10-30）：当时只加了 tasks.test 的
            // excludeTestsMatching（mpv-testing 下方 filter 块），漏登本清单——分片类发现扫描
            // 仍把它捞进 testShardN，而分片沙盒无 database jar，两用例必红（「沙盒未安装
            // com.intellij.database」）。本清单才是分片侧的唯一排除口径。
            "cn.song.mybatisplus.visualizer.database.PlatformSqlDialectRegistrationTest",
        )
        // T-TestShardParallel：并发不安全类隔离口（逗号分隔 FQCN）。这些类共享应用级单例/
        // 端口/全局态，多构建并发跑会 flake；并行运行器把它们从分片计划剔除，改由串行补偿
        // 任务单独跑。缺省空集 = 既有串行语义逐字不变。
        val shardExcludeClasses: Set<String> = providers.gradleProperty("testShardExcludeClasses").orNull
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        val discoveredTestClasses: List<String> = testFileEntries
            .flatMap { (pkg, classNames, _) -> classNames.map { cn -> if (pkg.isEmpty()) cn else "$pkg.$cn" } }
            .filter { it !in dbIntegrationClasses }
            .filter { it !in testExternalClasses }
            .filter { it !in shardExcludeClasses }
            .sorted()
        // 时长权重表：discoveredTestClasses 全集覆盖。T-TestPerf4 起同类取 mtime 最新的
        // K 份取中位数（-PtestShardWeightSamples，默认 2）——分片跑出的单类耗时带 ±7% 级
        // 并发膨胀噪声且各片随机分布（预期均衡 209.8s×3 实测 195.3/209.3/196.7，T-TestPerf4
        // run2），单份最新值会让均衡器逐轮追噪声；K 份中位数平滑。样本不足 K 份退化为现有
        // 取法，全新检出零数据时全体同权，退化路径与 T-TestPerf2 一致。
        // T-TestPerf12 K=3 销案（2026-10-02 R5 实测）：K=3 失衡 +18% vs K=2 同带 +10.8%，无优势，
        // 默认 2 维持；附带定例=新树冷轮样本会污染 K 窗（R1 冷值致 R2/R3 装箱错位、失衡 +21~30%），
        // 但 K=2 mtime 窗两轮自然挤出自愈（R4 双 warm 失衡回落、drift 1.012），纪律=新树冷轮
        // 读数不作判据、均衡观察等两轮 warm（dev-support/test-shard-aggregate.py 的 drift 读数可见）。
        val durationWeights: Map<String, Double> = run {
            val weightSamples = providers.gradleProperty("testShardWeightSamples").orNull
                ?.toIntOrNull()?.coerceIn(1, 5) ?: 2
            // T-TestShardParallel：并行多构建同时配置时，各 lane 看到的 build/test-results
            // 历史样本不同（别的 lane 正在删/写结果目录），会算出不同的分片分配 → 测试
            // 重复或漏跑（20261020 实测两轮总数 7949 vs 8119）。并行运行器开跑前把上一轮
            // 结果快照到固定目录，并用 -PtestShardWeightRoot 让所有 lane 读同一份冻结样本，
            // 既保留真实权重（均衡）又让计划与配置时点无关；缺省仍是 build/test-results。
            // -PtestShardIgnoreLocalWeights=true 是只吃入库画像的退路（冷树/快照缺失时用）。
            val ignoreLocalWeights =
                providers.gradleProperty("testShardIgnoreLocalWeights").orNull?.toBoolean() ?: false
            val weightRoot = providers.gradleProperty("testShardWeightRoot").orNull
                ?.let { file(it) } ?: file("build/test-results")
            val resultDirs = if (ignoreLocalWeights) emptySequence() else
                sequenceOf(File(weightRoot, "test")) +
                    (1..testShardCount).asSequence().map { File(weightRoot, "testShard$it") }
            val samples = HashMap<String, MutableList<Pair<Long, Double>>>() // FQCN -> [(mtime, seconds)]
            for (dir in resultDirs) {
                if (!dir.isDirectory) continue
                for (f in listSuiteXmls(dir)) {
                    val attrs = parseTestSuiteAttrs(f) ?: continue
                    val name = attrs["name"] ?: continue
                    val time = attrs["time"]?.toDoubleOrNull() ?: continue
                    samples.getOrPut(name) { mutableListOf() }.add(f.lastModified() to time)
                }
            }
            val smoothed = samples.mapValues { (_, list) ->
                list.sortedByDescending { it.first }.take(weightSamples).map { it.second }.sorted()
                    .let { sorted -> sorted[sorted.size / 2] }
            }
            // T-TestPerf10 S1：权重画像回填（dev-support/test-shard-weights.tsv，weight-refresh.ps1
            // 从 clean 轮生成的派生物入库）。本地样本优先、画像只补缺类——根治两个失衡源：
            // ①新落地重类零本地权重被中位数插值成轻类（批次8 PluginSettingsPanelUiTest 4例19.5s
            // 被按 ~1.5s 插值，20260929 门禁轮临界片失衡 +13.7% 的实证形态）；②新工作树零数据
            // 退化为类数均分（T-TestPerf10 S0 冷轮临界片失衡 +15.4% 实证）。画像陈旧的退化面=
            // 回到插值现状；贪心装箱只需相对量级，画像绝对刻度的跨机差异不影响均衡。
            val weightProfile: Map<String, Double> = run {
                val f = file("dev-support/test-shard-weights.tsv")
                if (!f.isFile) emptyMap<String, Double>() else runCatching {
                    f.readLines().mapNotNull { line ->
                        if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
                        val tab = line.indexOf('\t')
                        if (tab <= 0) return@mapNotNull null
                        val seconds = line.substring(tab + 1).trim().toDoubleOrNull()
                            ?: return@mapNotNull null
                        if (seconds <= 0.0) return@mapNotNull null
                        line.substring(0, tab).trim() to seconds
                    }.toMap()
                }.getOrDefault(emptyMap())
            }
            val known = (smoothed.values + weightProfile.values).sorted()
            val median = if (known.isEmpty()) 1.0 else known[known.size / 2]
            discoveredTestClasses.associateWith { smoothed[it] ?: weightProfile[it] ?: median }
        }
        // 机器级全局资源亲和（T-TestPerf2 补丁）：Windows 剪贴板全机唯一（WClipboard.openClipboard
        // 互斥），并发分片 JVM 互抢 → cannot open system clipboard（2026-09-15 冷启动第一跑
        // shard2 实证 1 例，假失败清单「剪贴板 flake」的并行放大版）。测试源码含
        // CopyPasteManager/systemClipboard 的类固定进第 1 片——片内串行天然无争用，不改任何测试代码；
        // 与类发现同源单遍扫描，新增剪贴板用例零维护。
        val machineGlobalAffinityClasses: Set<String> = testFileEntries
            .filter { (_, _, text) -> text.contains("CopyPasteManager") || text.contains("systemClipboard") }
            .flatMap { (pkg, classNames, _) -> classNames.map { cn -> if (pkg.isEmpty()) cn else "$pkg.$cn" } }
            .toSet()

        // T-TestPerf8 S3：头部巨类方法级跨片拆分（-PtestSplitHeavy=false 可关，默认开）。
        // 均衡器以类为最小调度单元，头部巨类是单片内不可分割的原子载荷（SqlPreviewPanelTest
        // 43.4s=片均值 35%，T7 收口 ±7% 失衡的主分量）。开启时把候选类的测试方法按实测
        // 逐例权重贪心对半拆成两片段：主体片 include 全类 + exclude 片段 B 方法清单；片段 B
        // （include "FQCN.method" 清单）挂到除主体片外最轻的片。
        // 默认开的依据（20260928 实验轮）：机制全证绿（两轮 parity 6082 全绿 + union 配置期
        // 断言 + 今日 3 例 flake 全部发生在关闭态=零拆分归因）；exec 临界片 −1~3s（方向性，
        // <5% 判据如实入账），收益随头部类增长复利；回退= -PtestSplitHeavy=false 一词。
        // 红线：零删例（两片段并集=该类全集，同一份数据配置期生成、天然互补）、零断言改动
        // （不动测试源码）；隔离语义只增不减（两 JVM 各自完整平台 bootstrap，TestCase 每例
        // 新实例 setUp/tearDown 不变）。数据驱动自维护：新增用例不在片段 B 清单→自然落主体片；
        // 已删用例的残留 pattern 无害；数据缺失/半截 XML/例数不足→退化不拆，确定性不破坏。
        // 安全前提（20260928 实施轮预检口径）：候选类不得含剪贴板亲和（跨片会复刻互抢 flake）、
        // 不得含 companion/@BeforeClass 类内共享状态（V4，逐候选源码审计）。
        fun parseTestCaseEntries(xml: File): List<Pair<String, Double>>? = runCatching {
            Regex("""<testcase name="([^"]+)"[^>]*?time="([\d.]+)"""")
                .findAll(xml.readText())
                .map { it.groupValues[1] to it.groupValues[2].toDouble() }
                .toList()
        }.getOrNull()?.takeIf { it.isNotEmpty() }

        // T-TestPerf10 S3 处置（20260930）：默认改关，转 opt-in（-PtestSplitHeavy=true 显式开启）。
        // 证据：执行侧 exclude 间歇性失效——round2 与 round7 双重执行 37 例（盘面：home 片整类 73 例
        // 全跑 + 片段B 37 例复跑，SHARD TOTAL 虚胖 6222 vs 真实 6185；round3/5/6 同机制干净），
        // 接线自证桩显示配置期计划自洽仍挡不住执行侧失火，根因在 Gradle filter/JUnit RT 之下
        // 未能定位。期望值裁定：T8 自己的诚实测量收益仅临界片 −1~3s（<5% 判据如实入账），失火
        // 一轮即 +21.9s 双跑 + 计数污染——负 EV。零删例零语义变化（关=整类原子装箱，冷态模拟
        // 已证画像均衡下无拆分临界 150.4s 照样贴地板带）。接线自证桩与告警保留，供显式开启时排查。
        val testSplitHeavy = providers.gradleProperty("testSplitHeavy").orNull?.toBoolean() ?: false
        val splitHeavyClassCandidates = listOf(
            "cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelTest",
        )
        val splitResultDirs = sequenceOf(file("build/test-results/test")) +
            (1..testShardCount).asSequence().map { file("build/test-results/testShard$it") }
        // 拆分计划：FQCN -> 该类逐例 (method, seconds)。门槛：例数 ≥30（太小的类不值 exclude/
        // include 维护面）；候选含机器全局亲和类则跳过；testShardCount<2 无处可搬不拆。
        val splitPlan: Map<String, List<Pair<String, Double>>> =
            if (!testSplitHeavy || testShardCount < 2) emptyMap() else {
                splitHeavyClassCandidates
                    .filter { it !in machineGlobalAffinityClasses }
                    .mapNotNull { fqcn ->
                        val xmls = splitResultDirs.map { File(it, "TEST-$fqcn.xml") }
                            .filter { it.isFile }.toList()
                        val latest = xmls.maxByOrNull { it.lastModified() } ?: return@mapNotNull null
                        val entries = parseTestCaseEntries(latest) ?: return@mapNotNull null
                        // 拆分开启后同类 XML 一分为二分布两目录：mtime 最新半 + 互补半合并成全集
                        val merged = if (xmls.size > 1) {
                            (entries + xmls.filter { it != latest }
                                .maxByOrNull { it.lastModified() }
                                ?.let { parseTestCaseEntries(it) ?: emptyList() }.orEmpty())
                                .distinctBy { it.first }
                        } else entries
                        if (merged.size < 30) null else fqcn to merged
                    }.toMap()
            }
        // 片段划分：逐例按（权重降序, 名字升序）贪心进轻半，轻半=跨片的片段 B。
        val splitFragments: Map<String, Pair<List<Pair<String, Double>>, List<Pair<String, Double>>>> =
            splitPlan.mapValues { (_, entries) ->
                var ta = 0.0
                var tb = 0.0
                val fa = mutableListOf<Pair<String, Double>>()
                val fb = mutableListOf<Pair<String, Double>>()
                entries.sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
                    .forEach { e ->
                        if (ta <= tb) { fa += e; ta += e.second } else { fb += e; tb += e.second }
                    }
                fa to fb
            }
        // 拆分类的类权重=各结果目录最新 XML 之和（逐类 K 中位数会把半截 XML 当全类——
        // 片段 XML 各含约一半用例，mtime 取新只读到一截）。
        val effectiveWeights: Map<String, Double> = if (splitFragments.isEmpty()) durationWeights else {
            durationWeights.mapValues { (fqcn, w) ->
                if (fqcn !in splitFragments) w
                else splitResultDirs.mapNotNull { d ->
                    val f = File(d, "TEST-$fqcn.xml")
                    if (f.isFile) parseTestSuiteAttrs(f)?.get("time")?.toDoubleOrNull() else null
                }.sum().takeIf { it > 0.0 } ?: w
            }
        }
        // 贪心均衡（S3 片段感知）：亲和类先固定第 1 片；其余逐项放入当前最轻片（并列取最小
        // 下标，确定性）。拆分类产生两项：主体（权重=全类−片段B）与片段B——排序键
        // （权重降序, 非片段优先, 名字升序）保证主体先落位；片段B落位时排除主体所在片。
        class ShardPlan(
            val classes: List<List<String>>,
            val loads: List<Double>,
            val fragExcludes: Map<Int, List<String>>,
            val fragIncludes: Map<Int, List<String>>,
            val fragHome: Map<String, Int>,
            val fragTarget: Map<String, Int>,
        )
        val plan = run {
            val shards = List(testShardCount) { mutableListOf<String>() }
            val loads = DoubleArray(testShardCount)
            val fragExcludes = HashMap<Int, MutableList<String>>()
            val fragIncludes = HashMap<Int, MutableList<String>>()
            val classShard = HashMap<String, Int>()
            val fragOfHome = HashMap<String, Int>()
            val fragOfTarget = HashMap<String, Int>()
            discoveredTestClasses.filter { it in machineGlobalAffinityClasses }.forEach { name ->
                shards[0] += name
                loads[0] += effectiveWeights[name] ?: 0.0
                classShard[name] = 0
            }
            val items = ArrayList<Triple<String, Double, String?>>() // (名, 权重, 非null=片段B所属类)
            effectiveWeights.entries
                .filter { it.key !in machineGlobalAffinityClasses }
                .forEach { (name, w) ->
                    val frag = splitFragments[name]
                    if (frag == null) items += Triple(name, w, null)
                    else {
                        val fb = frag.second
                        val fbW = fb.sumOf { it.second }
                        items += Triple(name, w - fbW, null)
                        items += Triple("$name#fragB", fbW, name)
                    }
                }
            // T-TestPerf12 S2：补偿槽位的贪心比较键——负载除以补偿因子（<1 放大视图 → 更早判满
            // → 少装）。loads 数组仍存原始权重和（日志/parity 语义不变），缩放只影响「哪个片被
            // 视为最轻」的判定；确定性不变（并列取最小下标的次序与因子无关）。
            // T-TestPerf14 S1：补偿来源三选一（手工 > auto > 无），见顶层 mpvHandicapDecision。
            fun greedyKey(i: Int): Double =
                loads[i] / (if (i == effHandicapTarget - 1) effHandicapFactor else 1.0)
            items.sortedWith(
                compareByDescending<Triple<String, Double, String?>> { it.second }
                    .thenBy { if (it.third != null) 1 else 0 }
                    .thenBy { it.first },
            ).forEach { (name, w, fragOf) ->
                if (fragOf == null) {
                    val target = loads.indices.minByOrNull { greedyKey(it) }!!
                    shards[target] += name
                    loads[target] += w
                    classShard[name] = target
                } else {
                    val home = classShard[fragOf] ?: 0
                    val target = loads.indices.filter { it != home }.minByOrNull { greedyKey(it) }!!
                    shards[target] += name
                    loads[target] += w
                    splitFragments[fragOf]!!.second.forEach { (method, _) ->
                        val pattern = "$fragOf.$method"
                        fragExcludes.getOrPut(home) { mutableListOf() } += pattern
                        fragIncludes.getOrPut(target) { mutableListOf() } += pattern
                    }
                    fragOfHome[fragOf] = home
                    fragOfTarget[fragOf] = target
                }
            }
            ShardPlan(shards, loads.toList(), fragExcludes, fragIncludes, HashMap(fragOfHome), HashMap(fragOfTarget))
        }
        val shardAssignment = plan.classes
        // task{} 惰性块只捕获可序列化的纯 Map/List（局部类实例进 CC 序列化有拒收风险）
        val fragExcludesByShard: Map<Int, List<String>> =
            plan.fragExcludes.mapValues { (_, v) -> v.toList() }
        val fragIncludesByShard: Map<Int, List<String>> =
            plan.fragIncludes.mapValues { (_, v) -> v.toList() }
        if (effHandicapFactor < 1.0) {
            logger.lifecycle(
                "testShards S2 handicap ON ($effHandicapMode): shard%d ÷%.2f in greedy key & expected（上方 loads 显示原始权重和）"
                    .format(effHandicapTarget, effHandicapFactor),
            )
        }
        if (splitFragments.isEmpty()) {
            logger.lifecycle(
                "testShards: $testShardCount shards duration-balanced, loads=" +
                    plan.loads.indices.joinToString(" ") { i ->
                        "shard${i + 1}=%.1fs/%dclasses".format(plan.loads[i], shardAssignment[i].size)
                    },
            )
        } else {
            splitFragments.forEach { (fqcn, frag) ->
                val (fa, fb) = frag
                val home = plan.fragHome[fqcn]?.plus(1) ?: "?"
                val target = plan.fragTarget[fqcn]?.plus(1) ?: "?"
                logger.lifecycle(
                    "testShards S3 split $fqcn: fragA=%.1fs/%dm + fragB=%.1fs/%dm (union=%d)，主体片=shard$home 片段B=shard$target"
                        .format(fa.sumOf { it.second }, fa.size, fb.sumOf { it.second }, fb.size, fa.size + fb.size),
                )
            }
            logger.lifecycle(
                "testShards: $testShardCount shards duration-balanced (S3 split on), loads=" +
                    plan.loads.indices.joinToString(" ") { i ->
                        "shard${i + 1}=%.1fs/%dclasses".format(plan.loads[i], shardAssignment[i].size)
                    },
            )
        }
        // T-TestPerf10 S3 接线自证：拆分类的类项落位与 exclude/include 片归属一行可见。
        // 20260930 T-TestPerf10 S0 round2 实证双重执行 37 例（盘面 shard4 整类 73 例 + shard5
        // 片段B 37 例，而计划行打印主体片=shard1）——接线与执行不符必须当场可见，不能靠
        // 计数漂移倒查。
        if (splitFragments.isNotEmpty()) {
            splitFragments.forEach { (fqcn, _) ->
                val holder = shardAssignment.withIndex().firstOrNull { fqcn in it.value }?.index
                logger.lifecycle(
                    "testShards S3 wiring: %s class-item=shard%s home=shard%s target=shard%s excludeOn=%s includeOn=%s"
                        .format(
                            fqcn.substringAfterLast('.'),
                            (holder?.plus(1) ?: -1).toString(),
                            (plan.fragHome[fqcn]?.plus(1) ?: -1).toString(),
                            (plan.fragTarget[fqcn]?.plus(1) ?: -1).toString(),
                            plan.fragExcludes.keys.map { it + 1 }.sorted().toString(),
                            plan.fragIncludes.keys.map { it + 1 }.sorted().toString(),
                        ),
                )
            }
        }
        // T-TestPerf4：喂给聚合任务的两份配置期产物（见 shardPlanExpectedLoads 注释）
        // T-TestPerf12 S2：expected 存补偿后有效预期（目标槽位 ÷ 因子）——聚合的超预期告警
        // 与「预期 vs 实测」对照自动吃到补偿刻度，结构性因子（若存在）不再被误判为缺权；
        // 无补偿（因子 1.0）时与原始 loads 逐位相等，行为不变。
        shardPlanExpectedLoads = plan.loads.indices.joinToString("|") { i ->
            "%.1f".format(plan.loads[i] / (if (i == effHandicapTarget - 1) effHandicapFactor else 1.0))
        }
        shardPlanClasses = discoveredTestClasses.joinToString("\n")
        shardPlanHashes = shardAssignment.joinToString("|") { it.hashCode().toUInt().toString(16) }
        // T-ReleasePerf9：错峰延迟迁至共享前置任务 testShardStagger（原 T-ReleasePerf7 N1
        // 叶子 doFirst sleep 的迁移版，等价性论证见该任务注释）——限流下叶子 sleep 会占槽
        // 令第二波分片白睡整段延迟，共享前置睡一次后释放。分片真实起点不变（≥ t0+285s）。
        for (i in 1..testShardCount) {
            val idx = i - 1
            register("testShard$i") {
                testFramework(TestFrameworkType.Platform)
                sandboxDirectory.set(layout.buildDirectory.dir("test-sandbox/shard$i"))
                task {
                    // T-ContribDemoFixture：每片都先过 demo 预检；直接跑 testShardN 也不会绕过。
                    dependsOn("verifyDemoFixture")
                    // T-ReleasePerf9：错峰共享前置（releaseGate 编排内延迟，日常 0）。
                    dependsOn("testShardStagger")
                    // IPGP 只给默认 test 任务自动配置 sourceSets 输出，分片任务需补齐
                    testClassesDirs = sourceSets["test"].output.classesDirs
                    classpath = classpath + sourceSets["test"].output
                    // T-CiRelease（20261025）：失败用例全格式输出（消息+完整栈）——Gradle 精简格式
                    // 只打「AssertionError at xx:NN」不含断言消息，CI 上 O147 子进程日志等关键证据
                    // 落不进控制台（agent 子项目侧同款配置，其失败日志一直带消息即此机制）。
                    testLogging {
                        events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
                        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                    }
                    // T-McpToolset2：分片同样需要 mcpStub 桩产物（工具级门控测试实例化 Tool 类，
                    // 超类型 McpToolset 来自桩源集；root 侧 test 任务已挂，分片默认不含——
                    // 漏挂致 NoClassDefFoundError 成片）。mcpStub 源集由根脚本在 convention
                    // 应用后创建，此 task{} 块惰性求值（任务实现期）故 named 可解析。
                    classpath = classpath + files(sourceSets.named("mcpStub").map { it.output })
                    filter {
                        shardAssignment[idx].forEach { includeTestsMatching(it) }
                        // T-TestPerf8 S3 拆分互补过滤：主体片 exclude 片段 B 方法；片段 B 所在片
                        // include 之。两份清单出自同一份片段集（配置期生成），天然互补防双跑/漏跑。
                        fragExcludesByShard[idx]?.forEach { excludeTestsMatching(it) }
                        fragIncludesByShard[idx]?.forEach { includeTestsMatching(it) }
                    }
                    // 并发审计锚点：起止毫秒时间戳，验证一命令多片是否真并行（T-TestPerf-05）。
                    // T-TestPerf10 S2：doFirst 落 .round-start 毫秒戳，聚合报告只计 mtime ≥ 该戳的
                    // 结果 XML——权重变化使类跨片后，旧分片目录残留上一轮 XML，按目录全扫会把
                    // 两轮混计（20260930 S0 round2 假读数 6222 vs 真实 6185 即此形态）。
                    // 项目根绝对路径走 inputs.property（CC 官方通道，doFirst 相对路径按 daemon
                    // CWD 解析的坑与 testDatabase 同源）。
                    inputs.property("mpvResultsRootAbs", project.projectDir.absolutePath)
                    doFirst {
                        val root = inputs.properties["mpvResultsRootAbs"] as String
                        val dir = File(root, "build/test-results/testShard$i")
                        dir.mkdirs()
                        File(dir, ".round-start").writeText(System.currentTimeMillis().toString())
                        // T-ReleasePerf7 N1（20261007）的错峰延迟已迁至共享前置任务 testShardStagger
                        // （T-ReleasePerf9，限流下叶子 sleep 占槽的结构性缺陷，等价性论证见该任务注释）。
                        // T-ReleasePerf10：laneOthers = 本片起跑瞬间**其他**在跑分片执行器数
                        // （本片自己的 JVM 尚未出生）。与档位日志并排即可判「档位是否真的变成并发」，
                        // 见 MpvLaneMeter 注释（计数器版本已被实测否掉）。
                        val laneOthers = MpvLaneMeter.activeShardExecutors(root, logger)
                        logger.lifecycle("testShard$i START ${System.currentTimeMillis()} laneOthers=$laneOthers")
                    }
                    doLast { logger.lifecycle("testShard$i END ${System.currentTimeMillis()}")
                    }
                }
            }
        }
        // T-TestPerf14 S2（D44 转正）：testDatabase 五类并行化。原形态=单任务 filter 五类 +
        // forkEvery=1 串行换 JVM（+2~3min，T-Fix-TestDb 20260918 在案）。拆五任务后每类：
        //   ① 独占 JVM（原 forkEvery=1 的「类间换 JVM」语义由「每类一任务」承载，应用级单例
        //      串扰三形态——数据源残留/DBN 清扫抢控制台/exec 路径恒定——的隔离不弱化）；
        //   ② 独占沙盒 build/test-sandbox/database/<任务名>（隔离面只增不减：原方案 5 类还共享
        //      同一 config_testDatabase 目录，现各享 config_<任务名>；测试侧 DbIntegrationSeedSupport
        //      按 PathManager.getConfigPath() 绝对锚定，零测试源码改动自动适配新 config 目录）。
        // 并行前提（T13 S4 评估 + 本轮反转的前提）：五类对 demo MySQL 全只读（源码面零 DML/DDL
        // 实证）→ 五路并发读无共享写冲突；Commit 峰值≈5 分片量级（评估③在案）；真库在跑
        // （20261004 netstat 3306 LISTENING 实测，T13「真库未起」前提反转）。
        // 命令语义不变：./gradlew testDatabase 仍是「一命令全部 testDb* 全跑」（块外聚合任务
        // dependsOn 全部任务，org.gradle.parallel 并行执行）。
        // 种子逻辑与原单任务逐字等价、仅 configRoot 参数化（config_<任务名> 规则 M36.5.23d 在案，
        // IPGP testIde 任务的实际 config 目录 = sandboxDirectory 下 config_<任务名>）。
        fun registerDbShard(name: String, fqcn: String) {
            register(name) {
                testFramework(TestFrameworkType.Platform)
                sandboxDirectory.set(layout.buildDirectory.dir("test-sandbox/database/$name"))
                task {
                    // T-ContribDemoFixture：DB 集成任务同样先过 demo 预检。
                    dependsOn("verifyDemoFixture")
                    testClassesDirs = sourceSets["test"].output.classesDirs
                    classpath = classpath + sourceSets["test"].output
                    // M36.5.23：DB 集成测试配置
                    //  - mpDemoProjectDir：demo 项目路径（读 dataSources.xml 连接信息），默认兄弟目录。
                    //    密码硬编码在测试里（root/root，从 demo application.yml 复制，本地开发库无敏感性）。
                    systemProperty(
                        "mpDemoProjectDir",
                        mpDemoProjectDir,
                    )
                    // 代理透传（与 runIde/verifyPlugin 一致）——DBN createBlocking 内部 HttpRequests
                    // 访问外部（驱动元数据/校验）需要代理；无代理时建连失败 → introspect 返回 false → skip
                    systemProperty("http.proxyHost", "127.0.0.1")
                    systemProperty("http.proxyPort", "7890")
                    systemProperty("https.proxyHost", "127.0.0.1")
                    systemProperty("https.proxyPort", "7890")
                    // T-ExecDocGate 20260917：doFirst 需要项目根绝对路径——相对路径在 doFirst 按
                    // daemon CWD 解析（实测为 .gradle/daemon/<ver>，跨构建不一致）；直接捕获
                    // project 字符串又是脚本对象引用（配置缓存拒绝）。inputs.property 是官方
                    // CC 兼容通道：执行期经 inputs.properties 读回
                    inputs.property("mpProjectDirAbs", project.projectDir.absolutePath)
                    // T-TestPerf14 S2：config 目录随任务名（IPGP 规则 config_<任务名>，M36.5.23d）
                    inputs.property("mpvDbConfigDirRel", "build/test-sandbox/database/$name/config_$name")
                    // 沙盒首次启动前预置 runIde 沙盒已下载的 jdbc-drivers（MySQL Connector/J），
                    // 免集成测试联网下驱动；runIde 沙盒不存在时静默跳过（首次连接时 DBN 自行下载）。
                    // 注意：doFirst 内不可引用 rootProject/layout（配置缓存不支持序列化 Project），
                    // 用相对路径 File（Test 任务工作目录 = 项目根）。
                    doFirst {
                        // T-ExecDocGate 20260917：种子源三级回退（本地 runIde 沙盒 → dev 工作树沙盒 →
                        // 日常 IDE 驱动存储）。runIde 沙盒 clean 后原版静默跳过 → 驱动缺失 →
                        // RemoteJdbcServer ClassNotFound（四集成类 introspect 全跳，本日实测）。
                        // 一切路径绝对锚定（见上方 inputs.property 注释）。
                        val root = inputs.properties["mpProjectDirAbs"] as String
                        val configRoot = File(root, inputs.properties["mpvDbConfigDirRel"] as String)
                        val driverDir = File(configRoot, "jdbc-drivers")
                        val sandboxDrivers = File(
                            root,
                            ".intellijPlatform/sandbox/mybatis-plus-sql-visualizer/IU-2024.3.7.1/config/jdbc-drivers",
                        )
                        val devSandboxDrivers = File(
                            root,
                            "../mybatis-plus-sql-visualizer-dev/.intellijPlatform/sandbox/mybatis-plus-sql-visualizer/IU-2024.3.7.1/config/jdbc-drivers",
                        )
                        // ① artifact 清单：两级沙盒源（xml；通常不含 jar）
                        if (!File(driverDir, "jdbc-drivers.xml").isFile) {
                            val src = listOf(sandboxDrivers, devSandboxDrivers).firstOrNull { it.isDirectory }
                            if (src != null) {
                                driverDir.mkdirs()
                                File(src, "jdbc-drivers.xml").copyTo(File(driverDir, "jdbc-drivers.xml"))
                                logger.lifecycle("$name: jdbc-drivers.xml pre-seeded from ${src.path}")
                            }
                        }
                        // ② jar 树：日常 IDE 驱动存储（Roaming，maven 布局；本机实存 9.7.0）。
                        //    逐文件 mkdirs——copyRecursively walkBottomUp 深层文件先于父目录落地会
                        //    ERROR_PATH_NOT_FOUND（实测）
                        val artifactVersion = "9.7.0"
                        val roamingVersionDir = File(
                            System.getProperty("user.home"),
                            "AppData/Roaming/JetBrains/IntelliJIdea2024.3/jdbc-drivers/MySQL ConnectorJ/$artifactVersion",
                        )
                        val versionDir = File(driverDir, "MySQL ConnectorJ/$artifactVersion")
                        if (roamingVersionDir.isDirectory && !versionDir.isDirectory) {
                            var copied = 0
                            roamingVersionDir.walkTopDown().filter { it.isFile }.forEach { f ->
                                val out = File(versionDir, f.relativeTo(roamingVersionDir).path)
                                out.parentFile.mkdirs()
                                if (runCatching { f.copyTo(out, overwrite = true) }.isSuccess) copied++
                            }
                            logger.lifecycle("$name: mysql connector jar tree pre-seeded (copied=$copied)")
                        }
                        // ③ driver 就绪状态：必须含 mysql.8 的 artifact 版本条目（真实 IDE 同款；
                        //    空壳文件时 DBN 不知道 mysql.8 解析哪个版本 → classpath 恒空 → CNFE）
                        val driverState = File(configRoot, "options/databaseDrivers.xml")
                        if (!driverState.isFile || driverState.readText().contains("mysql.8").not()) {
                            File(configRoot, "options").mkdirs()
                            driverState.writeText(
                                "<application>\n" +
                                    "  <component name=\"LocalDatabaseDriverManager\" version=\"201\">\n" +
                                    "    <driver id=\"mysql.8\">\n" +
                                    "      <artifact id=\"MySQL ConnectorJ\" name=\"MySQL ConnectorJ\" use=\"true\" version=\"$artifactVersion\" />\n" +
                                    "    </driver>\n" +
                                    "  </component>\n" +
                                    "</application>\n",
                            )
                        }
                        // ④ 单测模式 artifact 清单副本 + ⑤ driver 定义钉版本（T-Embed-08 原逻辑）
                        // T-TestDbDriver 20260918：落点改 config 根下的 options/ ——
                        // DatabaseArtifactLoaderImpl.getTestArtifactLists() 经
                        // DatabaseDriverManagerImpl.existentTestPath(name) 只认
                        // `<homePath>/dbe/database/testResources/<name>` 与 `<configPath>/options/<name>`
                        // （bytecode 常量池 \u0001/options/\u0001 实证）；原落 config 根 → 探针实测
                        // getTestArtifactLists() 恒空（该文件从未被 DBN 读到）。
                        val manifest = File(driverDir, "jdbc-drivers.xml")
                        val dstArtifactList = File(configRoot, "options/test-database-artifacts.xml")
                        if (manifest.isFile && !dstArtifactList.isFile) {
                            dstArtifactList.parentFile.mkdirs()
                            manifest.copyTo(dstArtifactList)
                            logger.lifecycle("$name: test-database-artifacts.xml seeded (options/)")
                        }
                        // ⑤ driver 定义（test-database-drivers.xml）不再由本块落盘：
                        //    ① DBN 在单测模式显式忽略该名——DatabaseDriverManagerImpl.existentTestPath
                        //       bytecode 实证 `isUnitTestMode() && name.endsWith("test-database-drivers.xml")`
                        //       → 直接 return null；② 原落盘用的是相对路径 `build/test-sandbox/...`，
                        //       按 Gradle daemon 进程 CWD 解析（见上方注释实测）。落盘职责归测试侧
                        //       DbIntegrationSeedSupport（PathManager.getConfigPath() 绝对锚定）。
                    }
                    filter {
                        includeTestsMatching(fqcn)
                    }
                }
            }
        }
        dbIntegrationTaskSpecs.forEach { (taskName, fqcn) -> registerDbShard(taskName, fqcn) }
    }
}

// T-ContribDemoFixture：全量/DB 测试的 demo 前置预检——只读检查，不修改 demo。
// 5 个 Demo* 用例硬断言 demo 源码存在；缺 demo 时提前失败并给出一键设置指引，避免 5 个分片
// 各跑一遍才在同一个断言上成片报红。可用 -PskipDemoFixtureCheck=true 显式跳过本预检。
tasks.register("verifyDemoFixture") {
    group = "verification"
    description = "校验测试 demo 是否存在且包含测试读取的关键文件（-PmpDemoProjectDir 覆盖）"
    inputs.property("mpvDemoRoot", mpDemoProjectDir)
    inputs.property("mpvSkipDemoFixtureCheck", skipDemoFixtureCheck.toString())
    doLast {
        if (inputs.properties["mpvSkipDemoFixtureCheck"] == "true") {
            logger.lifecycle("verifyDemoFixture: skipped (-PskipDemoFixtureCheck=true)")
            return@doLast
        }
        val root = File(inputs.properties["mpvDemoRoot"] as String)
        val required = listOf(
            "mybatis-plus-demo/src/main/java/cn/song/mybatisplusdemo/service/impl/UserServiceImpl.java",
            "mybatis-plus-demo/src/main/resources/db/schema.sql",
            "mybatis-plus-join-demo/src/main/resources/db/schema.sql",
            "jpa-hibernate-demo/src/main/java/cn/song/jpademo",
            "jpa-embedded-prefix-demo/src/main/java/cn/song/embedprefixdemo/service",
            "jpa-jar-model-demo/src/main/java/cn/song/jarmodeldemo/service",
            "jpa-jar-model-lib/pom.xml",
        )
        val missing = required.filter { !File(root, it).exists() }
        if (!root.isDirectory || missing.isNotEmpty()) {
            val detail = if (missing.isEmpty()) "" else "\n缺少：" + missing.joinToString("、")
            throw GradleException(
                "测试 demo 未就绪：$root$detail\n" +
                    "全量 testShards 中 5 个 Demo* 用例会硬断言 demo 源码存在。\n" +
                    "请运行：dev-support/test-demo/setup-test-env.ps1（或 setup-test-env.sh）\n" +
                    "或指定：-PmpDemoProjectDir=<demo 路径>\n" +
                    "只想跳过本预检：-PskipDemoFixtureCheck=true",
            )
        }
        logger.lifecycle("verifyDemoFixture: OK ($root)")
    }
}

// T-TestPerf14 S2：testDatabase 聚合任务（命令名与语义不变，一命令全部 testDb* 全跑）——纯容器，
// 6 个 testDb* 任务在 org.gradle.parallel 下并行执行。原单任务串行形态见 git 历史。
tasks.register("testDatabase") {
    group = "verification"
    description =
        "DB 插件 classpath 门禁（T-TestPerf14 S2：各任务独占 JVM+沙盒；UI025 起 6 个、T-PreviewSideQuery 起 7 个，原单任务串行形态见 git 历史）"
    dependsOn("verifyDemoFixture")
    dependsOn(*dbIntegrationTaskSpecs.map { it.first }.toTypedArray())
}

tasks.register("testShards") {
    group = "verification"
    description =
        "分片全量测试（时长均衡自动分区 $testShardCount 片；测试链并发限流见 -PtestShardConcurrency，缺省 2、" +
            "releaseGate 编排内 3；限流压的是同时执行的片数，片数与覆盖面不变；跨进程并行用 dev-support/test-shards-parallel.ps1）"
    dependsOn("verifyDemoFixture")
    dependsOn(*(1..testShardCount).map { "testShard$it" }.toTypedArray())
    // T-TestPerf6：S3 外部护栏桶与分片同命令并行（org.gradle.parallel 已开）
    dependsOn("testExternal")
    // T-TestPerf4：预期各片 execSum 与计划类全集——配置期固化成 String（见
    // shardPlanExpectedLoads 注释），doLast 只做文件 IO + 读 inputs，不触达脚本对象。
    inputs.property("mpvExpectedShardLoads", shardPlanExpectedLoads)
    inputs.property("mpvPlannedClasses", shardPlanClasses)
    // T-TestPerf14 S1：落盘通道——本轮补偿模式/因子/目标槽、各片类清单 hash、历史文件目录，
    // 配置期固化成 String（CC 约束同上），doLast 落盘 shard-imbalance-history.jsonl 用。
    inputs.property("mpvHandicapMode", effHandicapMode)
    inputs.property("mpvHandicapFactor", effHandicapFactor.toString())
    inputs.property("mpvHandicapTarget", effHandicapTarget.toString())
    inputs.property("mpvShardHashes", shardPlanHashes)
    inputs.property("mpvHistDirAbs", file("build/test-results").absolutePath)
    // 聚合报告（T-TestPerf2）：分片跑完直接打印各片与总数四元组，与串行口径的计数 parity 目测即得。
    // 配置缓存约束：结果目录在配置期解析为 File，doLast 只做文件 IO 不触达 Project/layout，
    // 且解析辅助函数须定义在 doLast 体内——引用脚本顶层函数会整段不可序列化（实证报错）。
    val shardResultDirs = (1..testShardCount).map { file("build/test-results/testShard$it") } +
        file("build/test-results/testExternal")
    doLast {
        fun suiteXmls(dir: File): List<File> =
            dir.listFiles { f: File -> f.isFile && f.name.startsWith("TEST-") && f.name.endsWith(".xml") }?.toList() ?: emptyList()
        fun suiteAttrs(f: File): Map<String, String>? = runCatching {
            val head = f.useLines { lines -> lines.take(8).joinToString("\n") }
            val tag = head.substringAfter("<testsuite", "").substringBefore(">")
            if (tag.isEmpty()) null
            else Regex("([\\w.-]+)=\"([^\"]*)\"").findAll(tag).associate { it.groupValues[1] to it.groupValues[2] }
        }.getOrNull()
        // T-TestPerf4：预期 loads（配置期权重表的各片合计，秒）与计划类全集
        val expectedLoads = (inputs.properties["mpvExpectedShardLoads"] as? String ?: "")
            .split("|").mapNotNull { it.toDoubleOrNull() }
        val plannedClasses = (inputs.properties["mpvPlannedClasses"] as? String ?: "")
            .split("\n").filter { it.isNotBlank() }.toSet()
        var total = 0
        var failed = 0
        var errored = 0
        var skipped = 0
        var execSeconds = 0.0
        val seenClasses = HashSet<String>()
        // T-TestPerf10 S2：各分片本轮实测 exec（不含 testExternal），供失衡/缺权告警
        val shardExec = ArrayList<Double>()
        shardResultDirs.forEachIndexed { idx, dir ->
            // T-TestPerf6：末位目录是 testExternal 桶；标签用目录名（testShardN 名即标签，
            // 不引用脚本顶层 testShardCount——doLast 引用脚本对象会炸配置缓存，本轮实证）
            val label = dir.name
            var n = 0
            var f = 0
            var e = 0
            var s = 0
            var t = 0.0
            var counted = 0
            val xmls = suiteXmls(dir)
            if (xmls.isEmpty()) {
                logger.lifecycle("$label: $dir 下无结果 XML（该分片未跑？）")
            }
            // T-TestPerf10 S2：mtime 闸——只计本轮（≥ .round-start 戳）的结果 XML。权重变化使
            // 类跨片后，旧分片目录残留上一轮 XML，按目录全扫会把两轮混计（20260930 S0 round2
            // 假读数 6222 vs 真实 6185 即此形态）；戳缺失（首次出现该目录/手工单跑）退化为全计。
            val roundStart = File(dir, ".round-start").let { m ->
                if (m.isFile) m.readText().trim().toLongOrNull() ?: 0L else 0L
            }
            for (xml in xmls) {
                if (roundStart > 0 && xml.lastModified() < roundStart) continue
                val attrs = suiteAttrs(xml) ?: continue
                attrs["name"]?.let { seenClasses.add(it) }
                fun num(key: String) = attrs[key]?.toDoubleOrNull() ?: 0.0
                n += num("tests").toInt()
                f += num("failures").toInt()
                e += num("errors").toInt()
                s += num("skipped").toInt()
                t += num("time")
                counted++
            }
            if (xmls.isNotEmpty() && counted == 0) {
                logger.lifecycle("$label: 本轮无新结果 XML（${xmls.size} 个文件全为陈旧残留，已按 mtime 闸排除——该分片本轮真跑了吗？）")
            }
            // 预期 vs 实测对照（T-TestPerf4）：差值持续偏向同一片=权重失准信号；
            // 随机 ±7% 内=并发膨胀噪声，均衡器无法也不必消除
            val expectedTxt = expectedLoads.getOrNull(idx)
                ?.let { " expected=%.1fs".format(it) } ?: ""
            logger.lifecycle(
                "$label: tests=$n failures=$f errors=$e skipped=$s time=%.1fs$expectedTxt".format(t),
            )
            total += n; failed += f; errored += e; skipped += s; execSeconds += t
            if (idx < shardResultDirs.size - 1) shardExec += t
        }
        // T-TestPerf10 S2：失衡/缺权告警（只增 WARN，不改执行面）——把「预期 vs 实测」从人眼
        // 比对固化为机器判据，防 20260929 dev 树 3m07s 式漂移静默复发（批次8 新类零本地权重
        // 被中位数插值 → 所在片实际超重 +13.7%）。失衡判据 12%（并发噪声正常带 ±7% 上限加余量）；
        // 缺权判据 = 临界片实测超预期 10%（新类被插值成轻类的特征形态）。
        if (shardExec.size >= 2) {
            val mx = shardExec.max()
            val avg = shardExec.average()
            val critIdx = shardExec.indexOf(mx)
            if (avg > 0 && (mx - avg) / avg > 0.12) {
                logger.warn(
                    "testShards WARN: 临界片失衡 +%.0f%%（shard%d %.1fs vs 片均值 %.1fs）——权重失准/新类缺权嫌疑，clean 轮后跑 dev-support/weight-refresh.ps1 刷新画像"
                        .format((mx - avg) / avg * 100, critIdx + 1, mx, avg),
                )
            }
            // T-TestPerf12 S3：缺权判据改全局漂移归一化——原判据「临界片实测>预期×1.10」是跨刻度
            // 比较，execSum 窗口漂移（T11 夜间 +5% 实录）下对逐类无失准的轮次连发误鸣 4 轮
            // （T11 §三.2）。改判据＝临界片的（实测/预期）相对全体片平均比（=漂移因子）的偏离
            // >10%：全局刻度整体漂移被除法消去，只剩该片真超预期（缺权/新类插值形态）才鸣。
            // 自参照零跨轮状态；失衡告警（上方 12% 同轮相对量）本就漂移免疫，不动。
            val expCrit = expectedLoads.getOrNull(critIdx)
            val expSum = expectedLoads.filter { it > 0 }.sum()
            val drift = if (expSum > 0 && shardExec.sum() > 0) shardExec.sum() / expSum else 1.0
            val relCrit = if (expCrit != null && expCrit > 0) (mx / expCrit) / drift else 1.0
            if (relCrit > 1.10) {
                logger.warn(
                    "testShards WARN: 临界片超预期 +%.0f%%（%.1fs vs expected %.1fs，已按全局漂移 %.2f 归一化）——新类零权重插值/画像过期嫌疑"
                        .format((relCrit - 1) * 100, mx, expCrit ?: 0.0, drift),
                )
            }
            // T-TestPerf14 S1：失衡历史落盘（auto-handicap 跨轮状态通道）。每轮一行追加，
            // 写入失败静默跳过（历史是优化输入不是正确性依赖，runCatching 兜磁盘异常）。
            runCatching {
                val histDir = File(inputs.properties["mpvHistDirAbs"] as String)
                val hashes = (inputs.properties["mpvShardHashes"] as? String ?: "").split("|")
                val critHash = hashes.getOrNull(critIdx)?.takeIf { it.isNotBlank() } ?: "unknown"
                val mode = inputs.properties["mpvHandicapMode"] as? String ?: "off"
                val factor = (inputs.properties["mpvHandicapFactor"] as? String)?.toDoubleOrNull()
                val target = (inputs.properties["mpvHandicapTarget"] as? String)?.toIntOrNull()
                val line = "{\"ts\":${System.currentTimeMillis()}" +
                    ",\"critSlot\":${critIdx + 1}" +
                    ",\"relCrit\":${"%.3f".format(relCrit)}" +
                    ",\"classSetHash\":\"$critHash\"" +
                    ",\"execSum\":${"%.1f".format(shardExec.sum())}" +
                    ",\"mode\":\"$mode\"" +
                    ",\"factor\":${factor?.let { "%.2f".format(it) } ?: "null"}" +
                    ",\"target\":${target ?: "null"}}\n"
                histDir.mkdirs()
                File(histDir, "shard-imbalance-history.jsonl").appendText(line)
            }
        }
        logger.lifecycle(
            "SHARD TOTAL: tests=$total failures=$failed errors=$errored skipped=$skipped " +
                "(parity 口径 = 串行 ./gradlew test 计数；两者同出一份自动分区清单，DB 集成三类都在 testDatabase)"
        )
        // 计划类并集校验（T-TestPerf4）：某个计划类没产出结果 XML=进了分片却没被执行——
        // 类发现回归或假绿嫌疑，必须可见。抽象基类/测试全在嵌套类的形态会误报，只 WARN 不 fail。
        if (plannedClasses.isNotEmpty() && seenClasses.isNotEmpty()) {
            val missing = plannedClasses - seenClasses
            if (missing.isEmpty()) {
                logger.lifecycle("testShards parity: 计划类全集 ${plannedClasses.size} 个均有结果 XML")
            } else {
                logger.warn(
                    "testShards parity: ${missing.size} 个计划类无结果 XML（抽象基类/嵌套套件形态可忽略，其余排查类发现回归）:",
                )
                missing.sorted().forEach { logger.warn("  - $it") }
            }
        }
    }
}

// P-立项-05 真机验证：-Dmpvs.* 透传到测试 JVM（渲染导出 DialectRenderDumpTest / 外部实例入口）
tasks.test {
    System.getProperties().forEach { (k, v) ->
        if (k is String && k.startsWith("mpvs.") && v is String) systemProperty(k, v)
    }
    filter {
        // M36.6.1：DB 集成测试仅在 testDatabase 任务运行（其 classpath 含 database jar）；
        // 默认 test 已排除 database jar（上方 withType<Test>），该测试类加载即 NoClassDefFoundError，
        // 必须排除——否则 "./gradlew test 不受影响" 的约定（M36.5.23）被破坏
        excludeTestsMatching("cn.song.mybatisplus.visualizer.execute.DbConsoleIntegrationTest")
        // M36.5.23 续：DataSourceSelector 选择/发现层集成骨架同样依赖 database jar（默认 test 已剥离）
        excludeTestsMatching("cn.song.mybatisplus.visualizer.execute.DataSourceSelectorIntegrationTest")
        // T-Embed-03/04/08：B 路径 Database 原生图嵌入链路（虚拟外键注入），同上仅在 testDatabase 运行
        excludeTestsMatching("cn.song.mybatisplus.visualizer.execute.HeavyNativeDatabaseDiagramJoinChainTest")
        // T-ExecDocGate：执行文档落盘纪律断言，同上仅在 testDatabase 运行
        excludeTestsMatching("cn.song.mybatisplus.visualizer.execute.ExecDocGateIntegrationTest")
        // T-TestDbDriver：驱动解析管线回归，同上仅在 testDatabase 运行
        excludeTestsMatching("cn.song.mybatisplus.visualizer.execute.DbDriverPipelineTest")
        // UI025：错列标注判别测试需要 Database 插件提供 SQL 语言/文件类型，同上
        excludeTestsMatching("cn.song.mybatisplus.visualizer.ui.annotate.PreviewUnknownColumnAnnotatorTest")
        // T-PreviewSideQuery：旁路查询真库链需要 Database 插件与真实 MySQL，同上
        excludeTestsMatching("cn.song.mybatisplus.visualizer.execute.PreviewSideQueryIntegrationTest")
        // T-GaussDbDialect 券 06：平台 sql.dialect 运行期可见性断言需要 Database 插件，同上
        excludeTestsMatching("cn.song.mybatisplus.visualizer.database.PlatformSqlDialectRegistrationTest")
    }
}

// T-TestPerf-03（2026-09-14）实验结论：不引入纯逻辑并行分桶任务。
// 三点实证：① 多 fork 并行平台型测试共享默认 test 沙盒 → PersistentFS 互踩成片失败；
// ② 独立纯逻辑桶 task 可行（classpath 须复用默认 test 任务成品、排除须统一走 filter 机制——
//    legacy exclude 与 filter 混用时 filter 优先级覆盖 exclude、159 条 exclude 全被忽略）；
// ③ 但 2246 个纯逻辑分类用例中 68 个（forks=1 对照同败）隐性依赖同 worker JVM 里
//    平台测试先建立的 Application/LaF 状态，脱离平台桶即红——作为快测任务会误报。
// 结论：全量权威=串行 ./gradlew test；真并行=./gradlew testShards 一命令（T-TestPerf2 收编，
// 时长均衡自动分区；旧「双开终端」用法仍可用但已非必需）；
// 日常提速最大头=关闭 build cache 根除假失败整轮重跑（gradle.properties）。
