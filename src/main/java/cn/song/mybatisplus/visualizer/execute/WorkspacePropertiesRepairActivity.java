package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * T-WorkspaceHeal（2026-11-13）：项目打开时<b>自愈</b>被旧版本写坏的 {@code .idea/workspace.xml}。
 *
 * <p>存量治愈的落点：写侧根因（后台线程写项目级 PropertiesComponent）已归位 EDT 修掉，但
 * <b>已经被写坏的工程不会自己好</b>——平台每次启动都报
 * {@code JsonDecodingException: Expected quotation mark '"', but had '8'}，而且不重写该条目。
 * 本 Activity 在项目打开时把那一行修回来：平台先加载（本次启动的报错已产生），插件随后修文件，
 * <b>从下一次启动起彻底干净</b>。</p>
 *
 * <p>纪律（任何一条不成立就什么都不做）：只认 workspace.xml 里 PropertiesComponent 这一块；
 * 只认本插件键前缀，别人的键一个字节都不碰；改完整块必须能重新解析成 JSON，否则不落盘；
 * 落盘前先备份到 {@code .sql-visualizer/backup/}；修完立刻把 PropertiesComponent 预热一次
 * （加载成功即平台持有合法 state，后续保存不会用空 state 覆盖修复）。</p>
 *
 * <p>健康工程的开销＝一次文件读 + 一次子串判定（不含本插件键直接返回），不做 JSON 解析。</p>
 */
public class WorkspacePropertiesRepairActivity implements ProjectActivity {

    private static final Logger LOG = Logger.getInstance(WorkspacePropertiesRepairActivity.class);

    /** 通知组 id（plugin.xml 注册，一域一组惯例）。 */
    public static final String NOTIFICATION_GROUP = "cn.song.mybatisplus.visualizer.repair";

    /** 备份时间戳格式（与日志时间口径一致，便于对照）。 */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Kotlin suspend execute 的 Java 侧等价形态（无实际挂起点，直接同步执行）。 */
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        try {
            heal(project);
        } catch (Throwable t) {
            LOG.warn("T-WorkspaceHeal: 自愈流程异常（已忽略，不影响启动）", t);
        }
        return Unit.INSTANCE;
    }

    /** 读文本并记下 BOM（workspace.xml 实际无 BOM，但写回时必须原样保留工程文件原有 BOM）。 */
    private record SourceText(@NotNull String text, boolean hasBom) {
    }

    /** 自愈主流程（测试可按真实工程目录直接驱动）。 */
    public static void heal(@NotNull Project project) {
        if (project.isDisposed()) {
            return;
        }
        String basePath = project.getBasePath();
        if (basePath == null) {
            return;
        }
        Path workspace = Paths.get(basePath, ".idea", "workspace.xml");
        if (!Files.isRegularFile(workspace)) {
            return;
        }

        SourceText source = readPreservingBom(workspace);
        if (source == null || !source.text().contains(WorkspaceJsonPropertyRepairKt.OWN_PROPERTY_KEY_PREFIX)) {
            return;
        }

        WorkspaceJsonPropertyRepairKt.RepairOutcome outcome =
                WorkspaceJsonPropertyRepairKt.repairWorkspaceFileText(source.text());
        if (outcome.isRepaired()) {
            if (outcome.getText().equals(source.text())) {
                return;
            }
            Path backup = backup(workspace);
            if (!writePreservingBom(workspace, outcome.getText(), source.hasBom())) {
                return;
            }
            LOG.info("T-WorkspaceHeal: 已修复 workspace.xml（keys=" + outcome.getFixedKeys()
                    + ", backup=" + (backup != null ? backup.toString() : "未生成") + "）");
            warmPropertiesComponent(project);
            notifyRepaired(project, outcome.getFixedKeys(), backup);
        } else if (outcome.isNoOwnDamage()) {
            LOG.info("T-WorkspaceHeal: workspace.xml 的 PropertiesComponent 解析失败，"
                    + "但坏的不是本插件的键——按约定不处理");
        } else if (outcome.isUnrepairable()) {
            LOG.warn("T-WorkspaceHeal: 本插件的键已修，但整块 JSON 仍不合法（别处也坏了），本次不落盘");
        }
        // Healthy：什么都不做
    }

    @Nullable
    private static SourceText readPreservingBom(@NotNull Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            boolean hasBom = bytes.length >= 3
                    && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF;
            byte[] body = hasBom ? java.util.Arrays.copyOfRange(bytes, 3, bytes.length) : bytes;
            return new SourceText(new String(body, StandardCharsets.UTF_8), hasBom);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 写回修复后的文本，返回是否真的写了。
     *
     * <p>写安全（照 DataSourceSelector.writeVfTextViaDocument 的既有纪律）：若该文件在编辑器里已有
     * <b>未保存</b>文档，放弃写入——用户手上有未落盘的改动时，程序性覆盖会撞 File Cache Conflict。
     * workspace.xml 几乎不会被人手工编辑，但这是「宁可不修」的一条零成本保险。</p>
     */
    private static boolean writePreservingBom(@NotNull Path path, @NotNull String text, boolean hasBom) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        final byte[] bytes;
        if (hasBom) {
            bytes = new byte[3 + body.length];
            bytes[0] = (byte) 0xEF;
            bytes[1] = (byte) 0xBB;
            bytes[2] = (byte) 0xBF;
            System.arraycopy(body, 0, bytes, 3, body.length);
        } else {
            bytes = body;
        }
        VirtualFile vf;
        try {
            vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(
                    path.toAbsolutePath().toString().replace('\\', '/'));
        } catch (Exception e) {
            vf = null;
        }
        final VirtualFile vfFinal = vf;
        if (vf != null) {
            FileDocumentManager fdm = FileDocumentManager.getInstance();
            var cached = fdm.getCachedDocument(vf);
            if (cached != null) {
                boolean unsaved;
                try {
                    unsaved = fdm.isDocumentUnsaved(cached);
                } catch (Throwable t) {
                    unsaved = false;
                }
                if (unsaved) {
                    LOG.info("T-WorkspaceHeal: workspace.xml 有未保存文档，本次不写（下次启动会重试）");
                    return false;
                }
            }
            try {
                WriteAction.compute(() -> {
                    vfFinal.setBinaryContent(bytes);
                    return Unit.INSTANCE;
                });
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
        // VFS 拿不到条目（工程不在 VFS 里）：退回裸写
        try {
            Files.write(path, bytes);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 改前备份到 {@code <项目>/.sql-visualizer/backup/workspace-<时间戳>.xml}。
     * 该目录已被插件用于其它落点，.gitignore 内容是 {@code *}，不会污染工作树。
     */
    @Nullable
    private static Path backup(@NotNull Path workspace) {
        try {
            Path projectRoot = workspace.getParent() != null && workspace.getParent().getParent() != null
                    ? workspace.getParent().getParent() : null;
            if (projectRoot == null) {
                return null;
            }
            Path storeDir = projectRoot.resolve(".sql-visualizer");
            Path backupDir = storeDir.resolve("backup");
            Files.createDirectories(backupDir);
            Path ignore = storeDir.resolve(".gitignore");
            if (!Files.exists(ignore)) {
                Files.write(ignore, "*\n".getBytes(StandardCharsets.UTF_8));
            }
            Path target = backupDir.resolve("workspace-" + STAMP.format(LocalDateTime.now()) + ".xml");
            Files.copy(workspace, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 修复后预热一次 PropertiesComponent：文件合法了，若平台重新加载成功，本会话的功能立即恢复，
     * 且平台随后保存的是合法 state（P-2）。失败不影响「下次启动干净」。
     */
    private static void warmPropertiesComponent(@NotNull Project project) {
        try {
            PropertiesComponent.getInstance(project);
            LOG.info("T-WorkspaceHeal: 修复后 PropertiesComponent 已重新加载，本会话即恢复");
        } catch (Throwable t) {
            LOG.info("T-WorkspaceHeal: 修复后仍未加载（本会话不恢复，下次启动干净）", t);
        }
    }

    private static void notifyRepaired(@NotNull Project project,
                                       @NotNull java.util.List<String> fixedKeys,
                                       @Nullable Path backup) {
        try {
            Notification notification = new Notification(
                    NOTIFICATION_GROUP,
                    MpSqlVisualizerBundle.message("workspaceRepair.title"),
                    MpSqlVisualizerBundle.message("workspaceRepair.message",
                            backup != null ? backup.toString() : "-"),
                    NotificationType.INFORMATION);
            Notifications.Bus.notify(notification, project);
        } catch (Throwable t) {
            LOG.warn("T-WorkspaceHeal: 通知发送失败（已忽略）", t);
        }
    }
}
