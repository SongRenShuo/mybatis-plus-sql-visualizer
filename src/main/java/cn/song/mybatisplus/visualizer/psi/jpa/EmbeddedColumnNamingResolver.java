package cn.song.mybatisplus.visualizer.psi.jpa;

import cn.song.mybatisplus.visualizer.config.PluginSettings;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import org.jetbrains.annotations.Nullable;

/**
 * 嵌入列命名约定的解析入口（T-EmbeddedColPrefix 方案 A+B）。
 *
 * <p>优先级：<b>显式设置 &gt; 工程配置探测 &gt; 默认 {@link EmbeddedColumnNaming#NONE}</b>。</p>
 *
 * <p>默认（AUTO + 探测不中）取 NONE 而非 COMPONENT_PATH，是为了让 JPA 默认工程与既有用例逐字不回归；
 * 探测命中（工程里确实声明了组件路径命名策略）才改口径。设置项的存在则保证探测判不出的场景
 * （自定义 NamingStrategy 类、非标准配置键）用户仍能显式纠正。</p>
 */
public final class EmbeddedColumnNamingResolver {

    private EmbeddedColumnNamingResolver() {
    }

    /**
     * 命名档判定结果（T-EmbeddedIdentify 诊断日志用）。
     *
     * @param naming 判定的命名约定
     * @param source 来源标签：{@code explicit}（设置页显式指定）/ {@code probe-hit}（实体所在模块配置命中）/
     *               {@code probe-hit-host}（实体模块判不出、查询宿主模块配置命中——实体在依赖 JAR/lib 模块、
     *               命名策略配在宿主 App 的形态）/ {@code probe-miss}（探测未命中回落默认）/ {@code default-no-project}（无工程上下文）
     */
    public record NamingResolution(EmbeddedColumnNaming naming, String source) {
    }

    /**
     * 带来源标签的完整判定（诊断日志用）。
     *
     * @param project    宿主工程（用于配置探测）；为 null 时跳过探测，只认显式设置
     * @param anchor     正在解析的实体文件——探测范围收敛到它<b>所在模块</b>；null 时按整个工程扫
     * @param hostAnchor 查询宿主元素所在文件（T-EmbeddedHostCascade，可空）——命名策略属于
     *                   <b>持久化单元（宿主 App 模块）</b>而非实体文件所在模块：实体打包进依赖 JAR/lib 模块时
     *                   {@code component-path} 配置写在宿主模块的 {@code application.yml} 里，实体自身模块扫不到。
     *                   级联次序＝实体所在模块 → 宿主模块；<b>两档都判不出不回退工程级扫描</b>（多模块聚合根
     *                   打开时兄弟模块配置外溢正是 {@code 6919d8ef} 要防的形态）
     */
    public static NamingResolution resolveDetailed(@Nullable Project project, @Nullable VirtualFile anchor,
            @Nullable VirtualFile hostAnchor) {
        String configured;
        try {
            configured = PluginSettings.getInstance().getState().getEmbeddedColumnNaming();
        } catch (Throwable ignored) {
            configured = null;
        }
        if (PluginSettings.EMBEDDED_NAMING_NONE.equals(configured)) {
            return new NamingResolution(EmbeddedColumnNaming.NONE, "explicit");
        }
        if (PluginSettings.EMBEDDED_NAMING_COMPONENT_PATH.equals(configured)) {
            return new NamingResolution(EmbeddedColumnNaming.COMPONENT_PATH, "explicit");
        }
        // AUTO（或取值不认识）：探测工程配置，判不出按 NONE
        if (project == null) {
            return new NamingResolution(EmbeddedColumnNaming.NONE, "default-no-project");
        }
        if (HibernateNamingConfigProbe.projectUsesComponentPath(project, anchor)) {
            return new NamingResolution(EmbeddedColumnNaming.COMPONENT_PATH, "probe-hit");
        }
        if (hostAnchor != null && !java.util.Objects.equals(
                hostAnchor.getPath(), anchor != null ? anchor.getPath() : null)
                && HibernateNamingConfigProbe.projectUsesComponentPath(project, hostAnchor)) {
            return new NamingResolution(EmbeddedColumnNaming.COMPONENT_PATH, "probe-hit-host");
        }
        return new NamingResolution(EmbeddedColumnNaming.NONE, "probe-miss");
    }

    /**
     * 解析当前应使用的嵌入列命名约定。
     *
     * @param project 宿主工程（用于配置探测）；为 null 时跳过探测，只认显式设置
     * @param anchor  正在解析的实体文件——探测范围收敛到它<b>所在模块</b>（命名策略是每个持久化单元
     *                的事，多模块工程里各模块配置互不影响）；null 时按整个工程扫
     */
    public static EmbeddedColumnNaming resolve(@Nullable Project project, @Nullable VirtualFile anchor) {
        return resolveDetailed(project, anchor, null).naming();
    }

    /** {@link #resolve(Project, VirtualFile)} 的无锚文件形态（Kotlin 默认参数 {@code anchor = null}）。 */
    public static EmbeddedColumnNaming resolve(@Nullable Project project) {
        return resolve(project, null);
    }
}
