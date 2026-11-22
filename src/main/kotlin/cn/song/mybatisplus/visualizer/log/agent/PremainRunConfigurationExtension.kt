package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.util.FatalErrors
import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration

/**
 * T-M56Phase2-Premain：Java 运行配置准备期注入 `-javaagent`（「启动即捕获」补位）。
 *
 * 位置依据（243 javap 实证）：`JavaRunConfigurationExtensionManager.updateJavaParameters` 由
 * `com.intellij.execution.application.BaseJavaApplicationCommandLineState` 在**构建 JavaParameters
 * 之后、进程启动之前**调用（javap -c 常量池 #198），这正是 `-javaagent` 必须写入的时机——再晚
 * 进程已经起好，注入无效。EP 由 `com.intellij.java` 声明（`java-impl.jar!/META-INF/JavaPlugin.xml`
 * 的 `qualifiedName="com.intellij.runConfigurationExtension"`），本插件 plugin.xml:66 已有硬依赖，
 * 故注册形态就是一条 `<runConfigurationExtension implementation="…"/>`，无需 epName。
 * 平台自身同类先例（243 sources 实测）：Kotlin 插件 `CoroutineAgentConnector.kt`
 * 同样是 `params.vmParametersList.add("-javaagent:$jarPath")`。
 *
 * 零劣化约定：本类只做「按任务语义过范围闸门（T-GradleTaskInjectScope，[PremainTaskScope]）+
 * 问 AgentManager 要一个 VM 选项字符串，非空就加」这两件事，全链 `runCatching` 包裹——注入链
 * 任何异常（jar 落盘失败/端口协商失败/平台 API 意外形态）只记 WARN，绝不抛 `ExecutionException`、
 * 绝不动用户保存的配置（JavaParameters 是每次执行现建的瞬时对象，改动不落盘）。本类不弹窗。
 *
 * 与 attach 链的关系：本扩展只在「增强捕获开关打开」时产出参数（复用
 * [AgentManager.isEnhancedEnabled]）。T-EnhancedCaptureDefaultOn（2026-10-19）起注入确认闸门
 * 已移除：不再有前置弹窗与 EDT 依赖，开关打开即在本轮带 -javaagent；premain 不可用时仍回落
 * attach 链（AgentManager.onProcessAppeared），零劣化。
 */
class PremainRunConfigurationExtension : RunConfigurationExtension() {

    override fun <T : RunConfigurationBase<*>> updateJavaParameters(
        configuration: T,
        params: JavaParameters,
        runnerSettings: RunnerSettings?,
    ) {
        runCatching {
            // T-GradleTaskInjectScope：范围闸门前置——测试/IDE 任务的 fork JVM 不注入，
            // 且 skip 时不得走到 preparePremainVmOption（那会落盘 jar、开 ServerSocket）。
            when (val scope = decideScope(configuration)) {
                is PremainTaskScope.Decision.Skip -> {
                    LOG.info("premain 注入跳过（范围闸门 ${scope.reason}，config=${configuration.name}）——测试/IDE 任务 fork JVM 不挂 agent")
                    return
                }
                PremainTaskScope.Decision.Inject -> Unit
            }
            val manager = configuration.project.getService(AgentManager::class.java) ?: return
            // T-OldJvmPremainGuard 券01：目标 JVM 版本探测前置——JDK 6/7 载不动 major 52 的入口类，
            // -javaagent 处理失败会让目标 JVM 启动即终止（用户现场：JDK 1.6 项目起不来）。
            // 探测失败按「未知」放行（结构性保护在 agent 侧引导类，见券02），不阻断注入。
            val option = manager.preparePremainVmOption(probeTargetJvmMaxMajor(params)) ?: return
            params.vmParametersList.add(option)
            LOG.info("premain 注入完成（config=${configuration.name}）：$option")
        }.onFailure { LOG.warn("premain 注入链异常——本次不注入，回落 attach/桥（零劣化）", it) }
    }

    /**
     * T-OldJvmPremainGuard 券01：目标 JVM 能加载的最高类文件 major。
     *
     * 事实来源是 `JavaParameters.getJdkPath()`（243 javap 实证：`public String getJdkPath()`）——
     * 探测本体在纯函数 [TargetJvmCompat]（release 文件 → rt.jar 兜底 → 未知），结果按 JDK 路径缓存。
     * 平台异常按「未知」处理（零突变：放行注入，由 agent 侧引导类兜底）；fatal 照例透传。
     */
    private fun probeTargetJvmMaxMajor(params: JavaParameters): Int? = runCatching {
        TargetJvmCompat.maxSupportedMajor(params.jdkPath)
    }.getOrElse { t ->
        if (FatalErrors.isFatal(t)) throw t
        LOG.warn("目标 JVM 版本探测失败——按未知处理（注入后由 agent 侧引导类兜底）", t)
        null
    }

    /**
     * 恒 true：本 EP 只在 Java 运行器链路上被求值（`JavaRunConfigurationExtensionManager` 由
     * Java state 调用），非 Java 配置根本不会进入；反过来若在此收窄（如只认 ModuleBasedConfiguration），
     * 会把 Spring Boot/自定义 Java 运行配置一并挡在门外。真正的「要不要注入」判定在范围闸门
     * [decideScope]（任务语义）与 [AgentManager.preparePremainVmOption] 的开关闸门里。
     */
    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean = true

    /**
     * T-GradleTaskInjectScope 范围闸门平台探针：识别 external-system 配置并取 taskNames，
     * 判定本体在纯函数 [PremainTaskScope.decide]。平台类为平台核心（243 `lib/app-client.jar` /
     * 262 `intellij.platform.externalSystem.*.jar`，javap 实证见计划文档 §四），编译期直引、
     * 无反射；探针任何异常按 [PremainTaskScope.Decision.Inject] 处理（回落既有行为，零突变）。
     */
    private fun decideScope(configuration: RunConfigurationBase<*>): PremainTaskScope.Decision = runCatching {
        val external = configuration as? ExternalSystemRunConfiguration
            ?: return@runCatching PremainTaskScope.decide(false, emptyList())
        PremainTaskScope.decide(true, external.settings.taskNames.orEmpty())
    }.getOrElse { t ->
        if (FatalErrors.isFatal(t)) throw t // T-FP-02：fatal 透传
        LOG.warn("premain 范围闸门探针异常——按既有行为注入（零突变）", t)
        PremainTaskScope.Decision.Inject
    }

    private companion object {
        private val LOG = Logger.getInstance(PremainRunConfigurationExtension::class.java)
    }
}
