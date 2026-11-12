package cn.song.mybatisplus.visualizer.ui

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import java.util.concurrent.ConcurrentHashMap

/**
 * T-MbgPreviewParityAndOpenPerf P6：iBatis `<typeAlias>` 别名表。
 *
 * iBatis 的 `parameterClass="User"` 里的 `User` 通常是 sqlMapConfig 里声明的别名
 * （`<typeAlias alias="User" type="cn.x.model.User"/>`）。先拿到 FQN 再直查，比
 * 「全项目短名索引 + 模块收窄」（L34）更准（零歧义）也更快（不遍历候选、不做模块过滤）。
 *
 * 定位策略（v1）：扫**语句所在模块**内容根下的 `.xml`，只认根标签为 `sqlMapConfig` 的文件，
 * 读其中的 `<typeAlias>`；结果按模块缓存。
 *
 * v1 取舍：缓存是**会话级**（config 改了要重启会话才生效）、只扫模块内（跨模块共用的 config
 * 不覆盖）——两处都在计划 §五 V5 登记为待真机确认项；命中不到时调用方回落短名索引，行为不变。
 */
object IbatisTypeAliasResolver {

    /** 模块名 → 别名表（会话级缓存）。 */
    private val cache = ConcurrentHashMap<String, Map<String, String>>()

    /** 别名 → FQN；查不到返回 null（调用方回落短名索引 + 模块收窄）。 */
    fun fqnOf(project: Project?, moduleName: String?, alias: String): String? {
        if (project == null || moduleName.isNullOrBlank() || alias.isBlank()) return null
        val table = cache.computeIfAbsent(moduleName) { name ->
            runCatching {
                ReadAction.compute<Map<String, String>, RuntimeException> { scan(project, name) }
            }.getOrDefault(emptyMap())
        }
        return table[alias]
    }

    /** 测试缝：清空会话缓存（Heavy 用例之间不串）。 */
    internal fun clearCache() = cache.clear()

    private fun scan(project: Project, moduleName: String): Map<String, String> {
        val module = ModuleManager.getInstance(project).findModuleByName(moduleName) ?: return emptyMap()
        val psiManager = PsiManager.getInstance(project)
        val table = mutableMapOf<String, String>()
        for (root in ModuleRootManager.getInstance(module).contentRoots) {
            VfsUtilCore.iterateChildrenRecursively(
                root,
                { vf -> vf.isDirectory || vf.extension == "xml" },
                { vf ->
                    if (!vf.isDirectory && vf.extension == "xml") {
                        collectFrom(psiManager.findFile(vf) as? XmlFile, table)
                    }
                    true
                },
            )
        }
        return table
    }

    /** 只认 `sqlMapConfig` 根（iBatis 配置文件的 DTD 根标签），避免误读业务 XML。 */
    private fun collectFrom(file: XmlFile?, table: MutableMap<String, String>) {
        val root: XmlTag = file?.rootTag ?: return
        if (root.name != "sqlMapConfig") return
        root.children.filterIsInstance<XmlTag>()
            .filter { it.name == "typeAlias" }
            .forEach { tag ->
                val alias = tag.getAttributeValue("alias")
                val type = tag.getAttributeValue("type")
                if (!alias.isNullOrBlank() && !type.isNullOrBlank()) table[alias] = type
            }
    }
}
