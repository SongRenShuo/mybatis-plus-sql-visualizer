package cn.song.mybatisplus.visualizer.context;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * O-145 W1 的瞬态来源上下文。
 *
 * <p>该模型只随一次预览调用存活，不参与历史、设置或复制序列化。尤其是 {@link #capturedJdbcUrl}，仅允许承载
 * 当前事件已经安全提供的运行时事实；旧日志的脱敏库标不能反推成 URL。</p>
 *
 * <p>master 的 Kotlin data class 的 Java 承载（伸缩构造器 + getter；Kotlin 默认参数 → 显式重载）。</p>
 */
public record PreviewSourceContext(
        Project project,
        PreviewSourceOrigin origin,
        @Nullable PsiFile hostPsiFile,
        @Nullable VirtualFile hostVirtualFile,
        SqlPsiResolution sqlPsiResolution,
        ModuleIdentityResolution moduleResolution,
        @Nullable String capturedJdbcUrl,
        @Nullable String selectedDataSourceId
) {

    /** 全参构造（Kotlin 声明序）。 */
    public PreviewSourceContext {
        if (sqlPsiResolution == null) {
            sqlPsiResolution = SqlPsiResolution.None.INSTANCE;
        }
        if (moduleResolution == null) {
            moduleResolution = ModuleIdentityResolution.None.INSTANCE;
        }
    }

    /** 便捷构造：hostPsiFile 兜 hostVirtualFile，其余缺省（Kotlin 默认参数）。 */
    public PreviewSourceContext(Project project, PreviewSourceOrigin origin, @Nullable PsiFile hostPsiFile) {
        this(project, origin, hostPsiFile, hostPsiFile != null ? hostPsiFile.getVirtualFile() : null,
                SqlPsiResolution.None.INSTANCE, ModuleIdentityResolution.None.INSTANCE, null, null);
    }

    /** 便捷构造：仅 project + origin（Kotlin 默认参数）。 */
    public PreviewSourceContext(Project project, PreviewSourceOrigin origin) {
        this(project, origin, null);
    }

    /** 实际 SQL PSI 命中文件：Exact / UniqueInOwner 取 file，其余 null。 */
    public @Nullable PsiFile sqlPsiFile() {
        if (sqlPsiResolution instanceof SqlPsiResolution.Exact exact) {
            return exact.file();
        }
        if (sqlPsiResolution instanceof SqlPsiResolution.UniqueInOwner unique) {
            return unique.file();
        }
        return null;
    }

    /** 唯一模块身份的 canonicalKey（歧义/无命中为 null）。 */
    public @Nullable String canonicalModuleKey() {
        if (moduleResolution instanceof ModuleIdentityResolution.Unique unique) {
            return unique.identity().canonicalKey();
        }
        return null;
    }

    /** 预览来源（PSI 直取 / XML 文档）。 */
    public enum PreviewSourceOrigin {
        PSI, XML,
    }

    /**
     * 实际 SQL PSI 的解析结果；失败和歧义均为一等状态，不以 null 混淆。
     * master 的 sealed interface 的 Java 承载（permits 收口在宿主接口）。
     */
    public sealed interface SqlPsiResolution permits SqlPsiResolution.Exact, SqlPsiResolution.UniqueInOwner,
            SqlPsiResolution.None, SqlPsiResolution.Ambiguous, SqlPsiResolution.Uncommitted,
            SqlPsiResolution.DatabaseToolsUnavailable {

        /** 光标精确落在一个真实 DatabaseTools SQL PSI 中。 */
        record Exact(PsiFile file) implements SqlPsiResolution {
        }

        /** 光标未精确命中，但限定 owner 内只发现一个真实 SQL PSI。 */
        record UniqueInOwner(PsiFile file) implements SqlPsiResolution {
        }

        /** owner 内没有可采信的真实 SQL PSI，或有界扫描被截断而无法证明唯一。 */
        final class None implements SqlPsiResolution {
            public static final None INSTANCE = new None();

            private None() {
            }
        }

        /** owner 内存在多个真实 SQL PSI；候选顺序只用于诊断，调用方不得取第一项。 */
        record Ambiguous(List<PsiFile> files) implements SqlPsiResolution {
        }

        /** 宿主文档尚未提交；此时注入缓存不是可靠证据，调用方应在提交后重试。 */
        final class Uncommitted implements SqlPsiResolution {
            public static final Uncommitted INSTANCE = new Uncommitted();

            private Uncommitted() {
            }
        }

        /** DatabaseTools/SqlFile 类型不可见；Community 或可选插件禁用时诚实降级。 */
        final class DatabaseToolsUnavailable implements SqlPsiResolution {
            public static final DatabaseToolsUnavailable INSTANCE = new DatabaseToolsUnavailable();

            private DatabaseToolsUnavailable() {
            }
        }
    }

    /** 模块的会话内身份。canonicalKey 可用于后续绑定，但本模型自身不持久化。 */
    public record ModuleIdentity(Module module, String canonicalKey, ModuleKeySource keySource) {
    }

    public enum ModuleKeySource {
        EXTERNAL_SYSTEM_ID, EXTERNAL_SYSTEM_PATH, MODULE_DIR, MODULE_NAME,
    }

    /** 模块归属解析结果（sealed：None / Unique / Ambiguous）。 */
    public sealed interface ModuleIdentityResolution permits ModuleIdentityResolution.None,
            ModuleIdentityResolution.Unique, ModuleIdentityResolution.Ambiguous {

        final class None implements ModuleIdentityResolution {
            public static final None INSTANCE = new None();

            private None() {
            }
        }

        record Unique(ModuleIdentity identity) implements ModuleIdentityResolution {
        }

        record Ambiguous(List<ModuleIdentity> identities) implements ModuleIdentityResolution {
        }
    }
}
