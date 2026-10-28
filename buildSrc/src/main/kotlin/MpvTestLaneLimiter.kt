import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

// T-ReleasePerf9（20261029）：测试链并发限流共享服务。
// 无状态——只承担「任意时刻最多 maxParallelLimit 个挂接任务在执行」的许可语义；
// 挂接面（root 全部 Test 任务，DB 集成桶豁免）与缺省值（独跑 2 / releaseGate 编排内 3、
// -PtestShardConcurrency 显式覆盖）见 mpv-testing.gradle.kts 的 mpvTestLaneLimiter 注册块。
// BuildService 契约要求 public abstract 无参构造；放独立文件避免脚本顶层类声明在
// precompiled script plugin 编译中的歧义。
abstract class MpvTestLaneLimiterService : BuildService<BuildServiceParameters.None>
