// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/PremainRunConfigurationExtension.kt 移植
package cn.song.mybatisplus.visualizer.log.agent;

import cn.song.mybatisplus.visualizer.util.FatalErrors;
import com.intellij.execution.RunConfigurationExtension;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.RunConfigurationBase;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration;

import java.util.List;

/**
 * T-M56Phase2-Premain：Java 运行配置准备期注入 {@code -javaagent}（「启动即捕获」补位）。
 *
 * 位置依据（243 javap 实证）：{@code JavaRunConfigurationExtensionManager.updateJavaParameters} 由
 * {@code com.intellij.execution.application.BaseJavaApplicationCommandLineState} 在<b>构建 JavaParameters
 * 之后、进程启动之前</b>调用（javap -c 常量池 #198），这正是 {@code -javaagent} 必须写入的时机——再晚
 * 进程已经起好，注入无效。EP 由 {@code com.intellij.java} 声明（{@code java-impl.jar!/META-INF/JavaPlugin.xml}
 * 的 {@code qualifiedName="com.intellij.runConfigurationExtension"}），本插件 plugin.xml:66 已有硬依赖，
 * 故注册形态就是一条 {@code <runConfigurationExtension implementation="…"/>}，无需 epName。
 * 平台自身同类先例（243 sources 实测）：Kotlin 插件 {@code CoroutineAgentConnector.kt}
 * 同样是 {@code params.vmParametersList.add("-javaagent:$jarPath")}。
 *
 * 零劣化约定：本类只做「按任务语义过范围闸门（T-GradleTaskInjectScope，{@link PremainTaskScope}）+
 * 问 AgentManager 要一个 VM 选项字符串，非空就加」这两件事，全链
 * {@code runCatching} 包裹——注入链任何异常（jar 落盘失败/端口协商失败/平台 API 意外形态）只记
 * WARN，绝不抛 {@code ExecutionException}、绝不弹窗、绝不动用户保存的配置（JavaParameters 是每次
 * 执行现建的瞬时对象，改动不落盘）。本类不弹窗。
 *
 * 与 attach 链的关系：本扩展只在「增强捕获开关打开」时产出参数（复用
 * {@link AgentManager#isEnhancedEnabled}）。T-EnhancedCaptureDefaultOn（2026-10-19）起注入确认闸门
 * 已移除：不再有前置弹窗与 EDT 依赖，开关打开即在本轮带 -javaagent；premain 不可用时仍回落
 * attach 链（AgentManager.onProcessAppeared），零劣化。
 */
public class PremainRunConfigurationExtension extends RunConfigurationExtension {

    @Override
    public <T extends RunConfigurationBase<?>> void updateJavaParameters(
            T configuration, JavaParameters params, RunnerSettings runnerSettings) {
        try {
            // T-GradleTaskInjectScope：范围闸门前置——测试/IDE 任务的 fork JVM 不注入，
            // 且 skip 时不得走到 preparePremainVmOption（那会落盘 jar、开 ServerSocket）。
            PremainTaskScope.Decision scope = decideScope(configuration);
            if (scope instanceof PremainTaskScope.Decision.Skip skip) {
                LOG.info("premain 注入跳过（范围闸门 " + skip.reason() + "，config=" + configuration.getName()
                    + "）——测试/IDE 任务 fork JVM 不挂 agent");
                return;
            }
            AgentManager manager = configuration.getProject().getService(AgentManager.class);
            if (manager == null) {
                return;
            }
            String option = manager.preparePremainVmOption(probeTargetJvmMaxMajor(params));
            if (option == null) {
                return;
            }
            params.getVMParametersList().add(option);
            LOG.info("premain 注入完成（config=" + configuration.getName() + "）：" + option);
        } catch (Throwable t) {
            LOG.warn("premain 注入链异常——本次不注入，回落 attach/桥（零劣化）", t);
        }
    }

    /**
     * T-OldJvmPremainGuard 券01：目标 JVM 能加载的最高类文件 major。
     *
     * <p>事实来源是 {@code JavaParameters.getJdkPath()}（243 javap 实证：{@code public String getJdkPath()}）——
     * 探测本体在纯函数 {@link TargetJvmCompat}（release 文件 → rt.jar 兜底 → 未知），结果按 JDK 路径缓存。
     * 平台异常按「未知」处理（零突变：放行注入，由 agent 侧引导类兜底）；fatal 照例透传。</p>
     */
    private Integer probeTargetJvmMaxMajor(JavaParameters params) {
        // Kotlin: runCatching { TargetJvmCompat.maxSupportedMajor(params.jdkPath) }.getOrElse { … }
        try {
            return TargetJvmCompat.maxSupportedMajor(params.getJdkPath());
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw (Error) t;
            LOG.warn("目标 JVM 版本探测失败——按未知处理（注入后由 agent 侧引导类兜底）", t);
            return null;
        }
    }

    /**
     * 恒 true：本 EP 只在 Java 运行器链路上被求值（{@code JavaRunConfigurationExtensionManager} 由
     * Java state 调用），非 Java 配置根本不会进入；反过来若在此收窄（如只认 ModuleBasedConfiguration），
     * 会把 Spring Boot/自定义 Java 运行配置一并挡在门外。真正的「要不要注入」判定在范围闸门
     * {@link #decideScope}（任务语义）与 {@link AgentManager#preparePremainVmOption} 的开关闸门里。
     */
    @Override
    public boolean isApplicableFor(RunConfigurationBase<?> configuration) {
        return true;
    }

    /**
     * T-GradleTaskInjectScope 范围闸门平台探针：识别 external-system 配置并取 taskNames，
     * 判定本体在纯函数 {@link PremainTaskScope#decide}。平台类为平台核心（243 {@code lib/app-client.jar} /
     * 262 {@code intellij.platform.externalSystem.*.jar}，javap 实证见计划文档 §四），编译期直引、
     * 无反射；探针任何异常按 {@link PremainTaskScope.Decision.Inject} 处理（回落既有行为，零突变）。
     */
    private PremainTaskScope.Decision decideScope(RunConfigurationBase<?> configuration) {
        try {
            if (!(configuration instanceof ExternalSystemRunConfiguration external)) {
                return PremainTaskScope.decide(false, List.of());
            }
            List<String> taskNames = external.getSettings().getTaskNames();
            return PremainTaskScope.decide(true, taskNames != null ? taskNames : List.of());
        } catch (Throwable t) {
            // T-FatalPassthrough T-FP-02：JVM 级致命错误透传（isFatal 蕴含 Error，显式收窄后 throw）
            if (FatalErrors.isFatal(t)) throw (Error) t;
            LOG.warn("premain 范围闸门探针异常——按既有行为注入（零突变）", t);
            return PremainTaskScope.Decision.Inject.INSTANCE;
        }
    }

    private static final Logger LOG = Logger.getInstance(PremainRunConfigurationExtension.class);
}
