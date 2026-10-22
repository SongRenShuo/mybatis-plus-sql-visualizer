package cn.song.mybatisplus.visualizer.psi;

import cn.song.mybatisplus.visualizer.history.ExplainPlanKey;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;

import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * T-PresetMethodScope：预览语句的「声明作用域」——参数预设与参数历史按它区分归属。
 *
 * <p>缓存键的既有两维（表名 + SQL 指纹）区分不了「同一段 SQL 写在两个方法里」：
 * demo 仓 {@code WmsInventoryJarQueryService} 的拼接版与 StringBuffer 版渲染结果逐字相同，键相同，
 * 于是预设、激活指针、参数历史全部共享。本对象把「出处」压成短哈希追加到键尾。
 *
 * <p>组成：项目身份（{@link ExplainPlanKey#projectIdentity}，SHA-256，不落本机路径）+ 项目内相对路径
 * + 方法签名（名 + 参数 canonicalText，重载可区分）。取不到方法上下文返回空串——调用方
 * 原样使用旧键，行为与升级前一致（日志/历史双击预览等没有 PSI 方法的链路）。
 */
public final class PreviewOriginScope {

    /** 单例（Kotlin object 转换）。 */
    public static final PreviewOriginScope INSTANCE = new PreviewOriginScope();

    /** 短哈希字节数：8 字节 = 16 hex，足够区分方法，又不把路径/类名写进 mp-sql-visualizer.xml。 */
    private static final int HASH_BYTES = 8;

    private PreviewOriginScope() {
    }

    /**
     * @param method  预览语句的宿主方法（null = 无 PSI 上下文）
     * @param project 用于算项目内相对路径与项目身份（null = 退化用文件名，仍能区分方法）
     * @return 作用域短哈希；无法定位方法时返回空串
     */
    public static String of(@Nullable PsiMethod method, @Nullable Project project) {
        String raw = label(method, project);
        if (raw.isEmpty()) return "";
        return hash(raw);
    }

    /**
     * 可读形态（日志/排障用，不持久化）：`<项目身份>|<相对路径>|<方法名>(<参数类型>)`。
     * 取不到方法返回空串。
     */
    public static String label(@Nullable PsiMethod method, @Nullable Project project) {
        if (method == null) return "";
        if (method.getContainingFile() == null || method.getContainingFile().getVirtualFile() == null) return "";
        var file = method.getContainingFile().getVirtualFile();
        var baseDir = project != null ? project.getBaseDir() : null;
        String relative;
        if (baseDir != null) {
            String rel = null;
            try {
                rel = VfsUtilCore.getRelativePath(file, baseDir, '/');
            } catch (Exception ignored) {
            }
            relative = rel != null ? rel : file.getName();
        } else {
            relative = file.getName();
        }
        StringBuilder signature = new StringBuilder();
        signature.append(method.getName()).append('(');
        PsiParameter[] parameters = method.getParameterList().getParameters();
        for (int index = 0; index < parameters.length; index++) {
            if (index > 0) signature.append(',');
            signature.append(parameters[index].getType().getCanonicalText());
        }
        signature.append(')');
        String projectIdentity;
        try {
            projectIdentity = ExplainPlanKey.projectIdentity(project != null ? project.getBasePath() : null);
        } catch (Exception e) {
            projectIdentity = "";
        }
        return projectIdentity + "|" + relative + "|" + signature;
    }

    private static String hash(String value) {
        byte[] digest = sha256(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(HASH_BYTES * 2);
        for (int i = 0; i < HASH_BYTES && i < digest.length; i++) {
            sb.append(String.format("%02x", digest[i]));
        }
        return sb.toString();
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
