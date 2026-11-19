package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.xml.ibatis.SqlMapConfigAliasIndex;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * T-MbgPreviewParityAndOpenPerf P6：iBatis {@code <typeAlias>} 别名表。
 *
 * <p>iBatis 的 {@code parameterClass="User"} 里的 User 通常是 sqlMapConfig 里声明的别名
 * （{@code <typeAlias alias="User" type="cn.x.model.User"/>}）。先拿到 FQN 再直查，比
 * 「全项目短名索引 + 模块收窄」（L34）更准（零歧义）也更快（不遍历候选、不做模块过滤）。</p>
 *
 * <h2>定位策略（v2，T-PerfSweep31 券01）</h2>
 *
 * <p>走自定义标量索引 {@link SqlMapConfigAliasIndex}：别名在<b>索引期</b>摊平，运行时一次
 * {@code getContainingFiles} 出候选文件，再按「语句所在模块」收窄 + PSI 侧精确匹配
 * （根标签 {@code sqlMapConfig} + {@code <typeAlias alias>} 精确相等——索引键偶有偏差只影响查得到
 * 查不到，不会给出错的 FQN）。失效全交平台增量——<b>config 改动保存后即生效</b>
 * （v1 的会话级缓存与本限制一并退役）。</p>
 *
 * <p>历史（v1，性能分析第八期 §4.3 定性为 P2）：扫语句所在模块内容根下的 {@code .xml}
 * （{@code iterateChildrenRecursively} 全走查 + 逐文件建 PSI），跑在预览点击链的
 * {@code ReadAction.compute}（池线程持读锁）内——大模块首次点击付整模块 PSI 代价、持读锁推迟
 * 平台写动作。守卫 {@code IbatisTypeAliasResolverHotPathTest} 钉住「本文件不得再出现全走查 API」。</p>
 *
 * <h2>模块收窄语义（与 v1 一致）</h2>
 *
 * <p>只认<b>语句所在模块</b>内的 config 文件（候选按文件的归属模块过滤）；跨模块共用的 config
 * 不覆盖（v1 已登记的限制，行为不扩面）。命中不到时调用方回落短名索引，行为不变。</p>
 */
public final class IbatisTypeAliasResolver {

    /** Kotlin object 语义兼容实例。 */
    public static final IbatisTypeAliasResolver INSTANCE = new IbatisTypeAliasResolver();

    private IbatisTypeAliasResolver() {
    }

    /** 别名 → FQN；查不到返回 null（调用方回落短名索引 + 模块收窄）。 */
    @Nullable
    public static String fqnOf(@Nullable Project project, @Nullable String moduleName, @NotNull String alias) {
        if (project == null || moduleName == null || moduleName.isBlank() || alias.isBlank()) {
            return null;
        }
        // 索引查询 + PSI 读都在本读动作内完成；调用方（预览链）已持读锁，重入合法
        java.util.List<VirtualFile> candidates;
        try {
            candidates = SqlMapConfigAliasIndex.getConfigFilesByAlias(project, alias);
        } catch (Throwable t) {
            return null; // dumb / 索引未就绪：按「本次没有」回落，不抛穿预览链
        }
        if (candidates.isEmpty()) {
            return null;
        }
        PsiManager psiManager = PsiManager.getInstance(project);
        try {
            return ReadAction.compute(() -> {
                String trimmedAlias = alias.trim();
                for (VirtualFile vf : candidates) {
                    if (!vf.isValid()) continue;
                    // 模块收窄（与 v1「只扫本模块」语义一致）：文件的归属模块必须等于语句所在模块
                    com.intellij.openapi.module.Module module = ModuleUtilCore.findModuleForFile(vf, project);
                    if (module == null || !module.getName().equals(moduleName)) continue;
                    var file = psiManager.findFile(vf);
                    if (!(file instanceof XmlFile xmlFile)) continue;
                    XmlTag root = xmlFile.getRootTag();
                    if (root == null || !"sqlMapConfig".equals(root.getName())) continue;
                    for (PsiElement child : root.getChildren()) {
                        if (child instanceof XmlTag tag
                                && "typeAlias".equals(tag.getName())
                                && trimmedAlias.equals(tag.getAttributeValue("alias") != null
                                        ? tag.getAttributeValue("alias").trim() : null)) {
                            String type = tag.getAttributeValue("type");
                            if (type != null && !type.trim().isEmpty()) {
                                return type.trim();
                            }
                        }
                    }
                }
                return null;
            });
        } catch (Throwable t) {
            return null;
        }
    }
}
