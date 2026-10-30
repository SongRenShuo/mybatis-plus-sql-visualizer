package mpv.watermark

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * T-ReleasePerf10（20261030）：内存水位探针（Commit 口径）＋测试链自适应档位＋快速失败闸门。
 *
 * 独立 .kt 文件而非 gradle.kts 顶层函数的原因与 [mpv.verification.VerifierHomeGuard] 相同：
 * doFirst/注册块 lambda 引用脚本顶层函数触发配置缓存「cannot serialize」。
 *
 * 背景与机制（计划 docs/开发计划/20261030-0827-T-ReleasePerf10-…）：
 * 全量测试/releaseGate 的墙钟方差与 OOM 复发同根——内存墙。Commit 上限 ~67~75GB，
 * 机器基线常驻 ~62GB 起（用户 IDE/并行会话/孤儿执行器/CDP 孤儿浏览器）。同一机制两头收：
 *  - [pickLane]（配置期）：水位宽裕自动提档提速（T9：缺省 2 估 8.5~10min vs 限流 5 实测 5m02s），
 *    紧张自动收缩防 native OOM；
 *  - [checkGate]（执行期）：free commit 低于 floor 秒级显式红＋top 消费者诊断，
 *    替代「13 分钟后分片执行器散成片 native OOM」（R24 实测 13m28s 三份 hs_err）。
 *
 * Commit 口径＝Win32_OperatingSystem 的 TotalVirtualMemorySize/FreeVirtualMemory（KB→GB），
 * 与 R1「判构建慢先采 Commit 不是 CPU」定例同源；物理内存空闲不等于 commit 空闲。
 *
 * 实现注记：PowerShell 调用样式照抄 VerifierHomeGuard——base64 -EncodedCommand（UTF-16LE）
 * 零转义歧义、CLIXML 清洗、超时降级。输出仅 ASCII（进程名含非 ASCII 时按原样展示不参与判定）。
 * 探针任何失败（PS 不在/超时/解析失败）一律返回 null → 调用方 fail-open：
 * 闸门降级 warn 放行、档位回落静态缺省——闸门自身故障不阻断构建。
 */
object MemoryWatermarkProbe {

    data class Reading(
        val totalCommitGb: Double,
        val freeCommitGb: Double,
        /** Commit 占用前 8，展示串：`name(pid) commit≈X.XGB`，按占用降序。 */
        val topProcesses: List<String>,
    )

    /** PowerShell 单行脚本（base64 UTF-16LE 下发，零转义歧义；VerifierHomeGuard 同款）。 */
    fun probeScript(): List<String> {
        val ps = (
            "\$os = Get-CimInstance Win32_OperatingSystem; " +
                "Write-Output ('totalGB=' + [math]::Round(\$os.TotalVirtualMemorySize/1MB,2)); " +
                "Write-Output ('freeGB=' + [math]::Round(\$os.FreeVirtualMemory/1MB,2)); " +
                "Get-Process | ForEach-Object { try { " +
                "[pscustomobject]@{ PId=\$_.Id; PName=\$_.ProcessName; PMC=[math]::Round(\$_.PagedMemorySize64/1MB,0) } " +
                "} catch { } } | Sort-Object PMC -Descending | Select-Object -First 8 | " +
                "ForEach-Object { Write-Output ('proc=' + \$_.PId + '|' + \$_.PName + '|' + \$_.PMC) }"
        )
        val encoded = Base64.getEncoder().encodeToString(ps.toByteArray(Charsets.UTF_16LE))
        return listOf("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
    }

    /**
     * 解析探针文本（纯函数，CC 通道与 ProcessBuilder 通道共用）。
     * CLIXML 清洗：PS 5.1 重定向 stdout 时混入序列化噪声行。
     */
    fun parse(rawText: String): Reading? {
        val lines = rawText.lineSequence()
            .filter { it != "#< CLIXML" && !it.startsWith("<Obj ") && it.isNotBlank() }
            .toList()
        val total = lines.firstOrNull { it.startsWith("totalGB=") }?.removePrefix("totalGB=")?.trim()?.toDoubleOrNull()
        val free = lines.firstOrNull { it.startsWith("freeGB=") }?.removePrefix("freeGB=")?.trim()?.toDoubleOrNull()
        if (total == null || free == null) return null
        val tops = lines.filter { it.startsWith("proc=") }.map { line ->
            val parts = line.removePrefix("proc=").split('|')
            val mb = parts.getOrNull(2)?.trim()?.toDoubleOrNull()
            val gb = if (mb == null) "?" else "%.1f".format(mb / 1024)
            "${parts.getOrNull(1) ?: "?"}(${parts.getOrNull(0) ?: "?"}) commit≈${gb}GB"
        }
        return Reading(total, free, tops)
    }

    /**
     * **执行期**探针（doFirst 内用）：直接起子进程。
     * 注意：只能在任务动作里调用——配置期用 ProcessBuilder 会触发配置缓存
     * 「external process started at configuration time」，Gradle 9 直接把构建判失败
     * （20261030 权威 gate 轮实测，见经验总结坑 4）。配置期请走 mpv-testing 里的
     * `providers.exec` 通道（CC 官方支持）再交给 [parse]。
     */
    fun probe(logger: Logger): Reading? = try {
        val proc = ProcessBuilder(probeScript())
            .redirectErrorStream(true).start()
        val finished = proc.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            logger.warn("memory watermark: 水位探针超时（30s），按 fail-open 降级")
            proc.destroyForcibly()
            null
        } else {
            val text = proc.inputStream.readBytes().decodeToString()
            parse(text) ?: run {
                logger.warn("memory watermark: 探针输出解析失败，按 fail-open 降级")
                null
            }
        }
    } catch (e: Exception) {
        logger.warn("memory watermark: 水位探针异常（${e.javaClass.simpleName}: ${e.message}），按 fail-open 降级")
        null
    }

    /**
     * 测试链档位表（券01）：effective = free − reserve。
     *
     * 物理模型（本轮实测校准，替代初版的 12/18/24 拍脑袋门槛）：
     *  - 单 lane 测试 JVM 的 commit 实测 **1.4~1.6GB**（`memory-hygiene.ps1` 分类合计 +
     *    test-executor ws 1175~1529MB，20261030 两轮读数）；
     *  - 安全余量 5GB（机器基线抖动 + 分片尾段 JIT/GC 抬升）；
     *  - 档位 = 满足 `effective ≥ lane × 1.6 + 5` 的最大 lane（上限 5）：
     *    5→13GB / 4→11.4GB / 3→9.8GB / 2→8.2GB，低于 2 档门槛仍取 2（下限，不是 1）。
     *  - releaseGate 编排内额外 reserve 4GB（verifyMarketplace 常驻 2.8GB + verifyGate 2.3GB，
     *    两者错峰不同时在峰值，取 4GB 覆盖叠加段）。
     *  - T9 实测 lane 5 在 free 4.8GB 幸存（execSum +27% 争用膨胀）是**危险区读数**，
     *    不作为放宽依据；本模型在该点仍取 2 档。
     *
     * 与快速失败闸门的分工：档位表是**吞吐旋钮**（选几片同时跑），闸门是**安全底线**
     * （free < floor 直接拒绝起跑）。两者独立生效，闸门不因档位低而放宽。
     */
    private const val PER_LANE_GB = 1.6
    private const val SAFETY_GB = 5.0

    /**
     * 默认上限 **3**（不是 5）。20261030 实测 5 档代价：
     *  - 测试窗 670s（2 档）→ **459s**（5 档），−31%，看起来很美；
     *  - 但 execSum 从 1000.8s 涨到 **1996.8s（+100%）**——5 个平台测试 JVM 互相争用，
     *    每个分片的用例耗时整体翻倍；
     *  - 并多出 4 例负载敏感红（`LogPipelinePerfTest` 耗时预算类 + `testExternal` 1 例），
     *    均为本仓已登记的「并行分片下资源竞争」形态。
     * 门禁要的是**可信读数**而不是最短墙钟：多出来的假红每次都要人肉定向复跑定责，
     * 净收益为负。故缺省封顶 3（T9 编排内档位，已 A/B），5 档走
     * `-PtestShardMaxLane=5` 显式开启（本地赶时间、且愿意接受负载敏感红时用）。
     */
    private const val DEFAULT_MAX_LANE = 3

    fun pickLane(logger: Logger, reserveGb: Double, context: String): Int? {
        val reading = probe(logger) ?: return null
        return laneFor(reading.freeCommitGb, reserveGb, context, logger)
    }

    /**
     * 档位计算的纯函数（与探针解耦，供 `-PwatermarkOverrideFreeGb` 确定性验证与单点推理）。
     * 抽出来的理由：档位逻辑的验证不该依赖「此刻机器恰好有多少空闲」——20261030 实测期间
     * 机器常被并行会话占到 free 3~14GB 波动，靠真机水位只能撞运气验档。
     */
    fun laneFor(
        freeCommitGb: Double,
        reserveGb: Double,
        context: String,
        logger: Logger,
        maxLane: Int = DEFAULT_MAX_LANE,
    ): Int {
        val effective = freeCommitGb - reserveGb
        val cap = maxLane.coerceIn(2, 5)
        val lane = (cap downTo 2).firstOrNull { effective >= it * PER_LANE_GB + SAFETY_GB } ?: 2
        logger.lifecycle(
            "mpv-test-lane adaptive ($context): free=${freeCommitGb}GB reserve=$reserveGb effective=$effective " +
                "-> lane=$lane (perLane=${PER_LANE_GB}GB safety=${SAFETY_GB}GB cap=$cap)",
        )
        return lane
    }

    /**
     * 快速失败闸门（券02）：floor ≤ 0 视为显式关闭（-PwatermarkGate=off）；
     * 探针失败降级 warn 放行（fail-open）；低于 floor 硬失败并附诊断与处置指引。
     */
    fun checkGate(floorGb: Double, context: String, logger: Logger) {
        if (floorGb <= 0.0) {
            logger.lifecycle("memory watermark: gate off（floor=0），跳过水位检查（$context）")
            return
        }
        val reading = probe(logger)
        if (reading == null) {
            logger.warn("memory watermark: 探针不可用，闸门降级放行（$context）")
            return
        }
        if (reading.freeCommitGb < floorGb) {
            throw GradleException(
                "memory watermark: free commit ${reading.freeCommitGb}GB < floor ${floorGb}GB（$context）" +
                    "——继续跑大概率触发 native OOM（hs_err Chunk::new / errno 1455 形态，R15~R24 在案）。" +
                    "Commit 占用前 8：\n" + reading.topProcesses.joinToString("\n") { "  $it" } +
                    "\n处置：① powershell -NoProfile -ExecutionPolicy Bypass -File dev-support/memory-hygiene.ps1 " +
                    "（报告孤儿占位者，-Clean 只清无父壳 executor/CDP headless/无客户端 daemon）；" +
                    "② 等并行会话或用户 runIde 沙盒收口；③ 机器空闲后重跑；紧急放行 -PwatermarkGate=off。",
            )
        }
        logger.lifecycle(
            "memory watermark OK: free ${reading.freeCommitGb}GB ≥ floor ${floorGb}GB（$context）",
        )
    }
}
