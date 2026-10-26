package cn.song.mybatisplus.visualizer.psi.jpa

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * `@Embedded` 展平列的命名约定（T-EmbeddedColPrefix）。
 *
 * 嵌入对象没有自己的表，其字段直接落在宿主表的列上，但**列名怎么拼**取决于工程的命名策略：
 *  - [NONE]：JPA/Hibernate 默认（`ImplicitNamingStrategyJpaCompliantImpl`）——列名就是内层字段名
 *    （`qty.baseQty` → `base_qty`）；
 *  - [COMPONENT_PATH]：Hibernate `ImplicitNamingStrategyComponentPathImpl`（配置关键字
 *    `component-path`，Hibernate 4 的 `DefaultComponentSafeNamingStrategy` 同源）——把属性路径
 *    各段拼进列名（`qty.baseQty` → `qty_base_qty`）。
 *
 * 语义经 hibernate-core 源码核对（5.6.4 `ImplicitNamingStrategyComponentPathImpl.transformAttributePath`）：
 * 隐式名用**原始属性名**按 `_` 连接（`qty` + `_` + `baseQty`），下划线化由 PhysicalNamingStrategy
 * 完成，故最终形态等价于「各段 snake 后以 `_` 连接」。
 */
enum class EmbeddedColumnNaming { NONE, COMPONENT_PATH }

/**
 * 嵌入列命名约定的解析入口（T-EmbeddedColPrefix 方案 A+B）。
 *
 * 优先级：**显式设置 > 工程配置探测 > 默认 [EmbeddedColumnNaming.NONE]**。
 *
 * 默认（AUTO + 探测不中）取 NONE 而非 COMPONENT_PATH，是为了让 JPA 默认工程与既有用例逐字不回归；
 * 探测命中（工程里确实声明了组件路径命名策略）才改口径。设置项的存在则保证探测判不出的场景
 * （自定义 NamingStrategy 类、非标准配置键）用户仍能显式纠正。
 */
object EmbeddedColumnNamingResolver {

    /**
     * 命名档判定结果（T-EmbeddedIdentify 诊断日志用）。
     *
     * @param naming 判定的命名约定
     * @param source 来源标签：`explicit`（设置页显式指定）/ `probe-hit`（实体所在模块配置命中）/
     *   `probe-hit-host`（实体模块判不出、查询宿主模块配置命中——实体在依赖 JAR/lib 模块、
     *   命名策略配在宿主 App 的形态）/ `probe-miss`（探测未命中回落默认）/ `default-no-project`（无工程上下文）
     */
    data class NamingResolution(val naming: EmbeddedColumnNaming, val source: String)

    /**
     * 带来源标签的完整判定（诊断日志用）。
     *
     * @param project 宿主工程（用于配置探测）；为 null 时跳过探测，只认显式设置
     * @param anchor 正在解析的实体文件——探测范围收敛到它**所在模块**；null 时按整个工程扫
     * @param hostAnchor 查询宿主元素所在文件（T-EmbeddedHostCascade，可空）——命名策略属于
     *   **持久化单元（宿主 App 模块）**而非实体文件所在模块：实体打包进依赖 JAR/lib 模块时
     *   `component-path` 配置写在宿主模块的 `application.yml` 里，实体自身模块扫不到。
     *   级联次序＝实体所在模块 → 宿主模块；**两档都判不出不回退工程级扫描**（多模块聚合根
     *   打开时兄弟模块配置外溢正是 `6919d8ef` 要防的形态）。
     */
    fun resolveDetailed(
        project: Project?,
        anchor: VirtualFile? = null,
        hostAnchor: VirtualFile? = null,
    ): NamingResolution {
        val configured = runCatching { PluginSettings.getInstance().state.embeddedColumnNaming }.getOrNull()
        return when (configured) {
            PluginSettings.EMBEDDED_NAMING_NONE -> NamingResolution(EmbeddedColumnNaming.NONE, "explicit")
            PluginSettings.EMBEDDED_NAMING_COMPONENT_PATH -> NamingResolution(EmbeddedColumnNaming.COMPONENT_PATH, "explicit")
            // AUTO（或取值不认识）：探测工程配置，判不出按 NONE
            else -> {
                if (project == null) return NamingResolution(EmbeddedColumnNaming.NONE, "default-no-project")
                if (HibernateNamingConfigProbe.projectUsesComponentPath(project, anchor)) {
                    NamingResolution(EmbeddedColumnNaming.COMPONENT_PATH, "probe-hit")
                } else if (hostAnchor != null && hostAnchor.path != anchor?.path &&
                    HibernateNamingConfigProbe.projectUsesComponentPath(project, hostAnchor)
                ) {
                    NamingResolution(EmbeddedColumnNaming.COMPONENT_PATH, "probe-hit-host")
                } else {
                    NamingResolution(EmbeddedColumnNaming.NONE, "probe-miss")
                }
            }
        }
    }

    /**
     * 解析当前应使用的嵌入列命名约定。
     *
     * @param project 宿主工程（用于配置探测）；为 null 时跳过探测，只认显式设置
     * @param anchor 正在解析的实体文件——探测范围收敛到它**所在模块**（命名策略是每个持久化单元
     *   的事，多模块工程里各模块配置互不影响）；null 时按整个工程扫
     */
    fun resolve(project: Project?, anchor: VirtualFile? = null): EmbeddedColumnNaming =
        resolveDetailed(project, anchor).naming
}
