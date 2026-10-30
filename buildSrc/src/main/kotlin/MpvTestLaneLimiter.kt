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
 * 实现走**进程扫描**而不是进程内计数器：测试执行器命令行里带 `test-sandbox/shardN`，
 * 数一遍就是此刻真实在跑的分片 JVM 数。计数器版本被实测否掉——失败分片不执行 doLast，
 * 计数只增不减，第二轮起读数全是漂移（本轮 gate 轮实测 laneActive=2 实为 shard2 失败留下的残值）。
 */
object MpvLaneMeter {
    /**
     * 此刻存活的、属于本构建沙盒的分片测试执行器数量。
     * 取「本构建目录 + test-sandbox」双条件，避免把并行会话/其他工作树的分片算进来。
     */
    fun activeShardExecutors(buildDir: String, logger: org.gradle.api.logging.Logger): Int = try {
        val needle = buildDir.replace('\\', '/') + "/test-sandbox/"
        ProcessHandle.allProcesses()
            .filter { handle ->
                val cmd = handle.info().commandLine().orElse("")
                cmd.contains(needle) && cmd.contains("test-sandbox")
            }
            .count()
            .toInt()
    } catch (e: Exception) {
        logger.warn("lane meter: 进程扫描失败（${e.javaClass.simpleName}），laneActive=-1")
        -1
    }
}
