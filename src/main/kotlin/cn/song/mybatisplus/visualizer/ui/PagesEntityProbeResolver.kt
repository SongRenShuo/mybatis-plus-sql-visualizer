package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.psi.jpa.EntityAliasSweep
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver
import cn.song.mybatisplus.visualizer.psi.jpa.JpaMetadataReader
import cn.song.mybatisplus.visualizer.xml.PagesEntityProbe
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope

/**
 * T-PagesHql：语言判定用的实体探针（名字 → 实体名/表名）。
 *
 * 供两条链共用：`<pages>` 页面语句（[PagesXmlPreviewService]）与通用 XML 内嵌 SQL
 * （[XmlInlineSqlPreviewService]，如无 `entityClass` 的 `<dataValue>`）。判定器本身保持纯函数，
 * PSI/索引访问集中在这里，Dumb 态与解析异常一律返回 null（判定回落 SQL，不抛）。
 */
internal object PagesEntityProbeResolver {

    fun probe(project: Project, name: String, context: PsiElement?): PagesEntityProbe? = runCatching {
        val clazz = resolveEntityClass(project, name, context) ?: return null
        val table = JpaEntityResolver().resolve(clazz, context)
        PagesEntityProbe(
            entityName = JpaMetadataReader.entityNameOf(clazz)?.takeIf { it.isNotBlank() } ?: clazz.name,
            tableName = table.tableName.takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    private fun resolveEntityClass(project: Project, name: String, context: PsiElement?): PsiClass? {
        val byName = runCatching { EntityAliasSweep.resolve(project, name, context).psiClass }.getOrNull()
        if (byName != null) return byName
        // FQCN 兜底：别名扫描在 dumb 态直接 unresolved，限定名还能走 findClass
        if (!name.contains('.')) return null
        return runCatching {
            JavaPsiFacade.getInstance(project).findClass(name, GlobalSearchScope.allScope(project))
        }.getOrNull()
    }
}
