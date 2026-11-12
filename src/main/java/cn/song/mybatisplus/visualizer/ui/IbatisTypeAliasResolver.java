// T-SyncK2JMaster183 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/IbatisTypeAliasResolver.kt 移植
// （Kotlin object 翻译：成员全静态——缓存即静态字段，调用方以对象成员形态调用，静态承载可互操作。）
package cn.song.mybatisplus.visualizer.ui;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T-MbgPreviewParityAndOpenPerf P6：iBatis {@code <typeAlias>} 别名表。
 *
 * <p>iBatis 的 {@code parameterClass="User"} 里的 {@code User} 通常是 sqlMapConfig 里声明的别名
 * （{@code <typeAlias alias="User" type="cn.x.model.User"/>}）。先拿到 FQN 再直查，比
 * 「全项目短名索引 + 模块收窄」（L34）更准（零歧义）也更快（不遍历候选、不做模块过滤）。</p>
 *
 * <p>定位策略（v1）：扫<b>语句所在模块</b>内容根下的 {@code .xml}，只认根标签为 {@code sqlMapConfig} 的文件，
 * 读其中的 {@code <typeAlias>}；结果按模块缓存。</p>
 *
 * <p>v1 取舍：缓存是<b>会话级</b>（config 改了要重启会话才生效）、只扫模块内（跨模块共用的 config
 * 不覆盖）——两处都在计划 §五 V5 登记为待真机确认项；命中不到时调用方回落短名索引，行为不变。</p>
 */
public final class IbatisTypeAliasResolver {

    private IbatisTypeAliasResolver() {
    }

    /** 模块名 → 别名表（会话级缓存）。 */
    private static final ConcurrentHashMap<String, Map<String, String>> CACHE = new ConcurrentHashMap<>();

    /** 别名 → FQN；查不到返回 null（调用方回落短名索引 + 模块收窄）。 */
    @Nullable
    public static String fqnOf(@Nullable Project project, @Nullable String moduleName, @NotNull String alias) {
        if (project == null || moduleName == null || moduleName.isBlank() || alias.isBlank()) {
            return null;
        }
        Map<String, String> table = CACHE.computeIfAbsent(moduleName, name -> {
            try {
                // master 侧 runCatching 降级：等义 try/catch，禁自行加白名单
                return ReadAction.compute(() -> scan(project, name));
            } catch (Throwable e) {
                return Map.<String, String>of();
            }
        });
        return table.get(alias);
    }

    /** 测试缝：清空会话缓存（Heavy 用例之间不串）。 */
    public static void clearCache() {
        CACHE.clear();
    }

    @NotNull
    private static Map<String, String> scan(@NotNull Project project, @NotNull String moduleName) {
        Module module = ModuleManager.getInstance(project).findModuleByName(moduleName);
        if (module == null) {
            return Collections.emptyMap();
        }
        PsiManager psiManager = PsiManager.getInstance(project);
        Map<String, String> table = new LinkedHashMap<>();
        for (VirtualFile root : ModuleRootManager.getInstance(module).getContentRoots()) {
            VfsUtilCore.iterateChildrenRecursively(
                root,
                vf -> vf.isDirectory() || "xml".equals(vf.getExtension()),
                vf -> {
                    if (!vf.isDirectory() && "xml".equals(vf.getExtension())) {
                        if (psiManager.findFile(vf) instanceof XmlFile xmlFile) {
                            collectFrom(xmlFile, table);
                        }
                    }
                    return true;
                },
            );
        }
        return table;
    }

    /** 只认 {@code sqlMapConfig} 根（iBatis 配置文件的 DTD 根标签），避免误读业务 XML。 */
    private static void collectFrom(@Nullable XmlFile file, @NotNull Map<String, String> table) {
        if (file == null) {
            return;
        }
        XmlTag root = file.getRootTag();
        if (root == null || !"sqlMapConfig".equals(root.getName())) {
            return;
        }
        for (PsiElement child : root.getChildren()) {
            if (child instanceof XmlTag tag && "typeAlias".equals(tag.getName())) {
                String alias = tag.getAttributeValue("alias");
                String type = tag.getAttributeValue("type");
                if (alias != null && !alias.isBlank() && type != null && !type.isBlank()) {
                    table.put(alias, type);
                }
            }
        }
    }
}
