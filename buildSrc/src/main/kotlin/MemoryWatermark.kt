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

    fun probe(logger: Logger): Reading? = try {
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
        val proc = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
            .redirectErrorStream(true).start()
        val finished = proc.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            logger.warn("memory watermark: 水位探针超时（30s），按 fail-open 降级")
            proc.destroyForcibly()
            null
        } else {
            // CLIXML 清洗（VerifierHomeGuard 同款）：PS 5.1 重定向 stdout 时混入序列化噪声行
            val lines = proc.inputStream.readBytes().decodeToString()
                .lineSequence().filter { it != "#< CLIXML" && !it.startsWith("<Obj ") && it.isNotBlank() }
                .toList()
            val total = lines.firstOrNull { it.startsWith("totalGB=") }?.removePrefix("totalGB=")?.trim()?.toDoubleOrNull()
            val free = lines.firstOrNull { it.startsWith("freeGB=") }?.removePrefix("freeGB=")?.trim()?.toDoubleOrNull()
            val tops = lines.filter { it.startsWith("proc=") }.map { line ->
                val parts = line.removePrefix("proc=").split('|')
                val mb = parts.getOrNull(2)?.trim()?.toDoubleOrNull()
                val gb = if (mb == null) "?" else "%.1f".format(mb / 1024)
                "${parts.getOrNull(1) ?: "?"}(${parts.getOrNull(0) ?: "?"}) commit≈${gb}GB"
            }
            if (total == null || free == null) {
                logger.warn("memory watermark: 探针输出解析失败（${lines.take(3)}），按 fail-open 降级")
                null
            } else {
                Reading(total, free, tops)
            }
        }
    } catch (e: Exception) {
        logger.warn("memory watermark: 水位探针异常（${e.javaClass.simpleName}: ${e.message}），按 fail-open 降级")
        null
    }

    /**
     * 测试链档位表（券01）：effective = free − reserve（releaseGate 编排内预留两 verifier JVM
     * ~5.1GB＋余量 → 6GB）。档位依据：T9 实测限流 5 在 free 4.8GB 属危险区幸存（execSum +27%
     * 争用膨胀）——5 档要求 ≥24GB 余量；每 lane 测试 JVM ~1.5~2GB commit。
     */
    fun pickLane(logger: Logger, reserveGb: Double, context: String): Int? {
        val reading = probe(logger) ?: return null
        val effective = reading.freeCommitGb - reserveGb
        val lane = when {
            effective >= 24.0 -> 5
            effective >= 18.0 -> 4
            effective >= 12.0 -> 3
            else -> 2
        }
        logger.lifecycle(
            "mpv-test-lane adaptive ($context): free=${reading.freeCommitGb}GB total=${reading.totalCommitGb}GB " +
                "reserve=$reserveGb effective=$effective -> lane=$lane",
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
