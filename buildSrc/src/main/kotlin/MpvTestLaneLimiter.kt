import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

// T-ReleasePerf9（20261029）：测试链并发限流共享服务。
// 无状态——只承担「任意时刻最多 maxParallelLimit 个挂接任务在执行」的许可语义；
// 挂接面（root 全部 Test 任务，DB 集成桶豁免）与缺省值（独跑 2 / releaseGate 编排内 3、
// -PtestShardConcurrency 显式覆盖）见 mpv-testing.gradle.kts 的 mpvTestLaneLimiter 注册块。
// BuildService 契约要求 public abstract 无参构造；放独立文件避免脚本顶层类声明在
// precompiled script plugin 编译中的歧义。
abstract class MpvTestLaneLimiterService : BuildService<BuildServiceParameters.None>

/**
 * T-ReleasePerf10（20261030）：分片并发度观测点。
 *
 * 动机：限流服务的 `maxParallelUsages` 是**许可上限**，不等于执行期实际并发度。20261030
 * 实测里同一个 `maxParallelLimit=2/3` 多轮走出 1 片与 2 片并发的不同形态；只看档位日志与
 * START/END 戳分不清「档位没生效」与「调度只给一个 worker」。
 *
 * 实现走**进程扫描**而不是进程内计数器：测试执行器命令行带显式系统属性标记
 * `-DmpvShardMarker=<构建目录哈希>`（分片任务 doFirst 所在的 task 配置块注入，见
 * mpv-testing.gradle.kts 注册循环），数一遍就是此刻真实在跑的分片 JVM 数。
 * T-ReleasePerf11 修正（20261103）：原实现匹配「构建目录 + /test-sandbox/」路径字面量，
 * 但 IPGP testIde 执行器 JVM 的沙盒路径走环境变量/系统属性传递，**命令行里不含该字面量**
 * ——E1 两轮实测 laneOthers 恒 0（round2 的 START 戳证明 shard1/4 同毫秒齐发为真并发），
 * 观测点自身失效。改用显式 `-D` 标记进命令行（用构建目录哈希隔离并行会话/其他工作树，
 * 不用可读路径避免命令行超长与路径形态差异）。计数器版本仍被否（失败分片 doLast 不执行，
 * 残值漂移，T10 坑5 在案）——进程扫描语义保留，只换匹配键。
 */
object MpvLaneMeter {
    /**
     * 分片执行器 JVM 命令行上的显式标记值（构建目录哈希）——注入方与扫描方共用此函数，
     * 两端必一致。
     */
    fun markerValue(buildDir: String): String =
        Integer.toHexString(buildDir.replace('\\', '/').hashCode())

    /**
     * 此刻存活的、属于本构建的分片测试执行器数量（含本片自己：本片 JVM 已出生后 doFirst
     * 才执行，命令行已带标记）。
     *
     * T-ReleasePerf11 W3 轮实证：JDK `ProcessHandle.allProcesses()` 在 Windows daemon JVM 内
     * 对其他进程的 CommandLine 可见性受限（W3 shard4 doLast 时 shard5 JVM 确定在跑——XML
     * mtime 证据——而扫描读 0；对照 PowerShell CIM 同刻全进程 531 个、命令行全可读），故改走
     * PowerShell CIM 通道（base64 -EncodedCommand，零转义歧义；assertNoForeignVerifier 同款
     * 实现模式，W2 轮已实证该通道在 daemon JVM 内可读命令行）。失败降级 -1（fail-open）。
     */
    fun activeShardExecutors(buildDir: String, logger: org.gradle.api.logging.Logger): Int = try {
        val needle = "-DmpvShardMarker=" + markerValue(buildDir)
        // 计数在 PS 内转字符串前缀输出——Measure-Object 直接吐对象会被 CLIXML 包成
        // <Objs>...（W3 后 round4 实测「输出不可解析「1<Objs」」），带前缀的 Write-Output
        // 字符串在 CLIXML 流里原样可见（assertNoForeignVerifier 的 CLEAR/FOREIGN 同款经验）。
        val ps = (
            "\$c = @(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | " +
                "Where-Object { \$null -ne \$_.CommandLine -and \$_.CommandLine.Contains('$needle') }); " +
                "Write-Output ('MPVLANE=' + \$c.Count)"
            )
        val encoded = java.util.Base64.getEncoder().encodeToString(ps.toByteArray(Charsets.UTF_16LE))
        val proc = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
            .redirectErrorStream(true).start()
        val finished = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
        val out = if (finished) proc.inputStream.readBytes().decodeToString()
            .lineSequence().filter { it != "#< CLIXML" && !it.startsWith("<Obj ") && it.isNotBlank() }
            .joinToString("\n").trim() else ""
        if (!finished) {
            logger.warn("lane meter: CIM 扫描超时（30s），laneAtEnd=-1")
            -1
        } else {
            Regex("MPVLANE=(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: run {
                logger.warn("lane meter: CIM 输出不可解析「${out.take(120)}」，laneAtEnd=-1")
                -1
            }
        }
    } catch (e: Exception) {
        logger.warn("lane meter: 进程扫描失败（${e.javaClass.simpleName}），laneAtEnd=-1")
        -1
    }
}
