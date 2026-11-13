// T-McpSettingsJump 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpSettingsNavigation.kt 顶层声明移植（183c98b3d）
package cn.song.mybatisplus.visualizer.mcp;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;

import java.util.Arrays;
import java.util.List;

/**
 * 平台 MCP Server 设置页寻址与回退的<b>单一真相源</b>（T-McpSettingsJump）。
 *
 * <p>master 侧为 McpSettingsNavigation.kt 顶层声明（两常量 + 一顶层函数），按 k2j 惯例落
 * {@code McpSettingsNavigationKt} 门面（{@link McpAuditLogKt} 同款先例）。</p>
 */
public final class McpSettingsNavigationKt {

    private McpSettingsNavigationKt() {
    }

    /**
     * 平台 MCP Server 设置页的稳定标识：<b>页面 id</b>（263 插件描述实证
     * {@code <applicationConfigurable ... id="com.intellij.mcpserver.settings" parentId="ai" />}）。
     * 页面 id 是平台对外的寻址契约，实现类名/包名/module 都是可重构的内部结构。
     */
    public static final String MCP_SETTINGS_PAGE_ID = "com.intellij.mcpserver.settings";

    /**
     * 设置页 Configurable 的实现类候选链，<b>新形态在前</b>。
     *
     * <p>为什么是候选链而不是一个 FQN（T-McpSettingsJump 20261111 真机故障）：平台 263 把 mcpserver 插件
     * 重组成 content module 形态，设置页随 UI 前端代码搬进 {@code intellij.mcpserver.frontend}
     * （{@code required-if-available="intellij.platform.frontend"}），包名从 {@code com.intellij.mcpserver.settings}
     * 改成 {@code com.intellij.mcpserver.frontend.settings}——旧 FQN 在 {@code 263.7307.200} 整安装（lib + plugins，
     * 含 {@code lib/modules/}）零命中。旧的单 FQN 探测因此恒 null，设置页跳转被误报成「IDEA 版本不够」。</p>
     *
     * <p><b>新增/调整本表时的硬约束</b>（{@code McpSettingsNavigationTest} 守着）：设置页类名只允许出现在这里，
     * 不得在别处写 {@code Class.forName("com.intellij.mcpserver.settings…")} 之类单点探测——那正是本次故障形态。
     * 老版本（243/251，无 mcpServer 新形态）两个 FQN 都探不到，走 {@link #MCP_SETTINGS_PAGE_ID} 与提示文案。</p>
     */
    public static final List<String> MCP_SETTINGS_CONFIGURABLE_CLASSES = Arrays.asList(
        "com.intellij.mcpserver.frontend.settings.McpServerSettingsConfigurable",
        "com.intellij.mcpserver.settings.McpServerSettingsConfigurable"
    );

    private static final Logger LOG = Logger.getInstance("cn.song.mybatisplus.visualizer.mcp.McpSettingsNavigation");

    /**
     * 打开平台 MCP Server 设置页并选中该页（T-McpSettingsJump）。
     *
     * <p>顺序与理由：</p>
     * <ol>
     * <li><b>类名候选链优先</b>——这是 262 与 263 都已经走通的路径（263 只是换了类名），
     *     不依赖页面 id 在旧版本的可用性，也不依赖平台在「id 不存在」时的行为；</li>
     * <li><b>页面 id 兜底</b>——照 {@code ui/SqlPreviewPanelExecution.kt} 打开 SQL 方言页 / 文件类型页的同款手法
     *     （T-ExecPsiGate 13.10 实证：必须在同一个 ConfigurableGroup 实例里按 id 查找才能选中目标页，
     *     自己 findById 再把裸 Configurable 交出去会落到设置根页）；</li>
     * <li>都失败才返回 {@link McpSettingsOpenResult#UNAVAILABLE}，由调用方决定文案（本函数零弹窗）。</li>
     * </ol>
     *
     * <p>不得改成 {@code Messages}/{@code JOptionPane} 调用：弹窗白名单（BannedApiSourceScanTest）只放行调用点文件。</p>
     */
    public static McpSettingsOpenResult openMcpServerSettingsPage(Project project) {
        Class<? extends Configurable> configurable = null;
        for (String fqn : MCP_SETTINGS_CONFIGURABLE_CLASSES) {
            try {
                configurable = (Class<? extends Configurable>) Class.forName(fqn);
                break;
            } catch (Throwable t) { // master runCatching.getOrNull 语义
                // 类不可加载（老版本/插件缺失）试下一候选
            }
        }
        if (configurable != null) {
            try {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, configurable);
                return McpSettingsOpenResult.OPENED;
            } catch (Throwable t) { // master runCatching.isSuccess 语义
                // 打开失败走页面 id 兜底
            }
        }

        try {
            com.intellij.ide.actions.ShowSettingsUtilImpl.showSettingsDialog(project, MCP_SETTINGS_PAGE_ID, null);
            return McpSettingsOpenResult.OPENED_BY_ID;
        } catch (Throwable t) { // master runCatching.isSuccess 语义
            // 兜底失败走公开 API 再试
        }

        try {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, MCP_SETTINGS_PAGE_ID);
            return McpSettingsOpenResult.OPENED_BY_ID;
        } catch (Throwable t) { // master runCatching.isSuccess 语义
            // 两条路都不通：落 UNAVAILABLE
        }

        // 诊断线索：真机上「点击前往没反应/弹提示」时靠这一行区分「类没探到」与「页面 id 也打不开」。
        LOG.warn("MCP settings page unreachable: configurableClass=" + configurable + ", "
            + "candidates=" + MCP_SETTINGS_CONFIGURABLE_CLASSES + ", pageId=" + MCP_SETTINGS_PAGE_ID);
        return McpSettingsOpenResult.UNAVAILABLE;
    }
}
