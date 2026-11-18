package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.xml.ibatis.SqlMapConfigAliasIndex
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile

/**
 * T-MbgPreviewParityAndOpenPerf P6：iBatis `<typeAlias>` 别名表。
 *
 * iBatis 的 `parameterClass="User"` 里的 `User` 通常是 sqlMapConfig 里声明的别名
 * （`<typeAlias alias="User" type="cn.x.model.User"/>`）。先拿到 FQN 再直查，比
 * 「全项目短名索引 + 模块收窄」（L34）更准（零歧义）也更快（不遍历候选、不做模块过滤）。
 *
 * ## 定位策略（v2，T-PerfSweep31 券01）
 *
 * 走自定义标量索引 [SqlMapConfigAliasIndex]：别名在**索引期**摊平，运行时一次
 * `getContainingFiles` 出候选文件，再按「语句所在模块」收窄 + PSI 侧精确匹配
 * （根标签 `sqlMapConfig` + `<typeAlias alias>` 精确相等——索引键偶有偏差只影响查得到
 * 查不到，不会给出错的 FQN）。失效全交平台增量——**config 改动保存后即生效**
 * （v1 的会话级缓存与本限制一并退役）。
 *
 * 历史（v1，性能分析第八期 §4.3 定性为 P2）：扫语句所在模块内容根下的 `.xml`
 * （`iterateChildrenRecursively` 全走查 + 逐文件建 PSI），跑在预览点击链的
 * `ReadAction.compute`（池线程持读锁）内——大模块首次点击付整模块 PSI 代价、持读锁推迟
 * 平台写动作。守卫 `IbatisTypeAliasResolverHotPathTest` 钉住「本文件不得再出现全走查 API」。
 *
 * ## 模块收窄语义（与 v1 一致）
 *
 * 只认**语句所在模块**内的 config 文件（候选按文件的归属模块过滤）；跨模块共用的 config
 * 不覆盖（v1 已登记的限制，行为不扩面）。命中不到时调用方回落短名索引，行为不变。
 */
object IbatisTypeAliasResolver {

    /** 别名 → FQN；查不到返回 null（调用方回落短名索引 + 模块收窄）。 */
    fun fqnOf(project: Project?, moduleName: String?, alias: String): String? {
        if (project == null || moduleName.isNullOrBlank() || alias.isBlank()) return null
        // 索引查询 + PSI 读都在本读动作内完成；调用方（预览链）已持读锁，重入合法
        val candidates = runCatching { SqlMapConfigAliasIndex.getConfigFilesByAlias(project, alias) }
            .getOrElse { return null } // dumb / 索引未就绪：按「本次没有」回落，不抛穿预览链
        if (candidates.isEmpty()) return null
        val psiManager = PsiManager.getInstance(project)
        return runCatching {
            com.intellij.openapi.application.ReadAction.compute<String?, RuntimeException> {
                for (vf in candidates) {
                    if (!vf.isValid) continue
                    // 模块收窄（与 v1「只扫本模块」语义一致）：文件的归属模块必须等于语句所在模块
                    val module = ModuleUtilCore.findModuleForFile(vf, project)?.name ?: continue
                    if (module != moduleName) continue
                    val file = psiManager.findFile(vf) as? XmlFile ?: continue
                    val root = file.rootTag ?: continue
                    if (root.name != "sqlMapConfig") continue
                    root.children.filterIsInstance<com.intellij.psi.xml.XmlTag>()
                        .firstOrNull { it.name == "typeAlias" && it.getAttributeValue("alias")?.trim() == alias.trim() }
                        ?.getAttributeValue("type")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { return@compute it }
                }
                null
            }
        }.getOrNull()
    }
}
