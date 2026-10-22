package cn.song.mybatisplus.visualizer.context;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * O-145 的模块归属解析器。
 *
 * <p>不使用 {@code findModuleForFile} 的单值便利入口：共享 content/source root 时它会隐藏歧义。本实现枚举
 * {@link ModuleManager#getModules()}，逐个以 {@link ModuleUtilCore#moduleContainsFile} 判断并保留全部候选。</p>
 *
 * <p>master 的 {@code object ModuleIdentityResolver} 的 Java 承载（全 static 形态）。</p>
 */
public final class ModuleIdentityResolver {

    private ModuleIdentityResolver() {
    }

    public static @NotNull PreviewSourceContext.ModuleIdentityResolution resolve(@NotNull Project project,
                                                                                 @NotNull VirtualFile file) {
        ApplicationManager.getApplication().assertReadAccessAllowed();
        return resolveCandidates(List.of(ModuleManager.getInstance(project).getModules()),
                module -> ModuleUtilCore.moduleContainsFile(module, file, false));
    }

    /**
     * 测试与调用边界共用的候选收口：输入已经是平台判定「包含该文件」的模块事实。
     * 包私有（Kotlin internal）——同模块守护测试缝。
     */
    static PreviewSourceContext.ModuleIdentityResolution resolveCandidates(Iterable<Module> modules,
                                                                           Predicate<Module> containsFile) {
        List<PreviewSourceContext.ModuleIdentity> identities = new ArrayList<>();
        for (Module module : modules) {
            if (module.isDisposed()) {
                continue;
            }
            if (!containsFile.test(module)) {
                continue;
            }
            identities.add(identityOf(module));
        }
        if (identities.isEmpty()) {
            return PreviewSourceContext.ModuleIdentityResolution.None.INSTANCE;
        }
        if (identities.size() == 1) {
            return new PreviewSourceContext.ModuleIdentityResolution.Unique(identities.get(0));
        }
        return new PreviewSourceContext.ModuleIdentityResolution.Ambiguous(identities);
    }

    /**
     * 外部系统的 linked project id 优先于可变模块名；缺 id 时退到 linked path，再退模块文件和名称。
     * Project 本身已构成命名空间，因此 canonical key 不拼本机绝对 root path，避免项目搬家即失效。
     */
    public static @NotNull PreviewSourceContext.ModuleIdentity identityOf(@NotNull Module module) {
        ExternalSystemModulePropertyManager external = ExternalSystemModulePropertyManager.getInstance(module);
        String systemId = trimToEmpty(external.getExternalSystemId());
        String linkedProjectId = trimToEmpty(external.getLinkedProjectId());
        if (!systemId.isEmpty() && !linkedProjectId.isEmpty()) {
            return new PreviewSourceContext.ModuleIdentity(
                    module,
                    "external:" + systemId + ":" + linkedProjectId,
                    PreviewSourceContext.ModuleKeySource.EXTERNAL_SYSTEM_ID);
        }
        String linkedProjectPath = trimToEmpty(external.getLinkedProjectPath());
        if (!systemId.isEmpty() && !linkedProjectPath.isEmpty()) {
            return new PreviewSourceContext.ModuleIdentity(
                    module,
                    "external-path:" + systemId + ":" + normalizePath(linkedProjectPath),
                    PreviewSourceContext.ModuleKeySource.EXTERNAL_SYSTEM_PATH);
        }
        // 模块目录走公开 API（ModuleUtilCore.getModuleDirPath）；Module.getModuleFilePath /
        // getModuleNioFile 均标 @Internal，插件字节码不得直接调用（Marketplace 校验会点名）。
        // 「目录 + 模块名」在同一项目内唯一，且目录比可被单独改名的 .iml 更稳定。
        String moduleDirPath = trimToEmpty(ModuleUtilCore.getModuleDirPath(module));
        if (!moduleDirPath.isEmpty()) {
            return new PreviewSourceContext.ModuleIdentity(
                    module,
                    "module-dir:" + normalizePath(moduleDirPath) + "/" + module.getName(),
                    PreviewSourceContext.ModuleKeySource.MODULE_DIR);
        }
        return new PreviewSourceContext.ModuleIdentity(
                module, "module-name:" + module.getName(), PreviewSourceContext.ModuleKeySource.MODULE_NAME);
    }

    /** Kotlin {@code ?.trim().orEmpty()} 等价。 */
    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizePath(String path) {
        String normalized = FileUtil.toSystemIndependentName(path);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
