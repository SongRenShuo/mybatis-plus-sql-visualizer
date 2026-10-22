// T-SyncK2JMaster187 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpFilePathResolver.kt
package cn.song.mybatisplus.visualizer.mcp;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * MCP 工具 filePath 统一解析（T-McpFilePathUnify，O-70）。
 *
 * <p><b>为什么统一</b>：2026-09-23 十工具真机复验发现三个带 filePath 的工具语义各不同，且与自身
 * {@code @McpDescription} 不符——{@code sqlv_render_sql} 只收项目相对路径（{@code normalizeAbsolute}
 * 拒盘符）、{@code sqlv_mapper_check} 只收绝对路径（{@code LocalFileSystem.findFileByPath} 直找，
 * 相对路径报 SOURCE_NOT_FOUND，而描述写着 "project-relative or absolute"）、{@code sqlv_crud_sql}
 * 两者都收。于是<b>照描述传参的 agent 必踩坑</b>（同一插件里 filePath 学一次不够用）。</p>
 *
 * <p><b>统一形态</b>：项目相对路径（按 {@code project.basePath} 解析）<b>与</b>项目内绝对路径都收；
 * <b>项目外一律拒绝</b>——这一条是安全边界，不是洁癖：MCP 工具由 agent 调用，{@code ../} 上跳或
 * 指向项目外的绝对路径等于把「读任意磁盘文件并渲染内容」的能力交给 agent（提示注入即可
 * 诱导），{@code McpRenderInput.normalizeFilePath} 早已拒 {@code ..} 与盘符，其测试
 * （{@code McpRenderInputTest} 的「路径归一化_拒绝绝对路径与上跳」）即本次统一要保住的底线。
 * 故统一方向是<b>向严格看齐</b>：相对路径分支继续复用该归一化（正斜杠统一 + 拒 {@code ..} +
 * 拒盘符），绝对路径分支改为「必须在项目根之下」。</p>
 *
 * <p>纯逻辑核心 {@link #resolveInsideProject} 零平台类型（可进 testFast 快桶）；平台取文件走
 * {@link #resolveFile}。</p>
 *
 * <p>移植注记：master 的 {@code object} 按本仓惯例落为 final class + {@link #INSTANCE} + 全静态成员。</p>
 */
public final class McpFilePathResolver {

    public static final McpFilePathResolver INSTANCE = new McpFilePathResolver();

    private McpFilePathResolver() {
    }

    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[A-Za-z]:");

    /**
     * 纯逻辑：入参 → 项目内绝对路径（正斜杠分隔）。
     *
     * <p>返回 null（形态非法或越出项目，调用方按「路径不可用」拒绝）：
     * <ul>
     *   <li>空串；</li>
     *   <li>含 {@code ..}（相对路径上跳与绝对路径内的上跳都拒）；</li>
     *   <li>绝对路径但不在 {@code projectBasePath} 之下（项目外文件）；</li>
     *   <li>项目根未知（{@code basePath} 为空）——无锚点可解析，宁拒不猜。</li>
     * </ul></p>
     */
    public static String resolveInsideProject(String projectBasePath, String rawPath) {
        String trimmed = rawPath.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String unified = trimmed.replace('\\', '/');
        if (unified.contains("..") || unified.startsWith("//")) {
            return null;
        }
        // Kotlin：projectBasePath?.replace('\\','/')?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        String base = null;
        if (projectBasePath != null) {
            String normalized = projectBasePath.replace('\\', '/');
            int end = normalized.length();
            while (end > 0 && normalized.charAt(end - 1) == '/') {
                end--;
            }
            normalized = normalized.substring(0, end);
            if (!normalized.isEmpty()) {
                base = normalized;
            }
        }
        if (base == null) {
            return null;
        }

        if (WINDOWS_DRIVE.matcher(unified).find() || (base.startsWith("/") && unified.startsWith("/"))) {
            // 绝对路径：必须落在项目根之下（含项目根本身的情况由 findFileByPath 判目录后自然失败）
            return unified.startsWith(base + "/") ? unified : null;
        }
        // 项目相对路径：复用既有归一化（统一正斜杠、去前导 /、拒 .. 与盘符）
        String rel = McpRenderInput.normalizeFilePath(trimmed);
        if (rel == null) {
            return null;
        }
        return rel.isEmpty() ? null : base + "/" + rel;
    }

    /** 先解析实际磁盘目标，项目内符号链接若指向外部则不能成为 MCP 文件入口。 */
    public static String canonicalProjectFile(String projectBasePath, String resolvedPath) {
        try {
            Path root = Path.of(projectBasePath).toRealPath();
            Path target = Path.of(resolvedPath).toRealPath();
            if (!target.startsWith(root) || !Files.isRegularFile(target)) {
                return null;
            }
            return target.toString().replace('\\', '/');
        } catch (Exception e) {
            return null;
        }
    }

    /** 平台：解析并取文件；null = 形态非法 / 越出项目 / 不存在（调用方统一给 SOURCE_NOT_FOUND）。 */
    public static VirtualFile resolveFile(Project project, String rawPath) {
        String base = project.getBasePath();
        if (base == null) {
            return null;
        }
        String resolved = resolveInsideProject(base, rawPath);
        if (resolved == null) {
            return null;
        }
        String canonical = canonicalProjectFile(base, resolved);
        if (canonical == null) {
            return null;
        }
        return LocalFileSystem.getInstance().findFileByPath(canonical);
    }
}
