import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.util.concurrent.atomic.AtomicInteger

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
 * 实测里同一个 `maxParallelLimit=2` 两轮走出两种形态——B 轮 2 片真并发（测试窗 670s ≈
 * execSum/1.7），A 轮严格串行（测试窗 1083s ≈ execSum/1.08）；只看档位日志与 START/END 戳
 * 分不清「档位没生效」与「档位生效但调度只给一个 worker」。
 *
 * 每片 doFirst 自增并打印当前活跃片数：`laneActive=N` 即**执行期真实并发度**，与档位日志
 * 并排即可判定。失败分片不执行 doLast、计数会漂移——只用作 START 时刻的瞬时读数，不作
 * 正确性依赖（daemon 复用 JVM，故配置期重置）。
 */
object MpvLaneMeter {
    private val active = AtomicInteger(0)

    fun reset() = active.set(0)

    /** 返回自增后的活跃片数（本片已计入）。 */
    fun enter(): Int = active.incrementAndGet()

    fun leave(): Int = active.decrementAndGet()
}
