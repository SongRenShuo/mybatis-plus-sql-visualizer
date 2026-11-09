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
 *
 * 券 0（T-Ibatis2SlowFix）：本探针是**同步预览热路径**，只能走「零扫描」形态（[probeCheap]）——
 * 「直查（类短名 / 全限定名索引）＋只读别名缓存」覆盖常规写法（根标识符＝实体简单名，或带
 * `entityClass` 时按类直查，`@Entity(name=...)` 别名与根的比对也随之成立）；而**全项目短名扫描**
 * 形态的 `@Entity(name)` 反查（[EntityAliasSweep.resolve]，实测单次约 1s、历史上同形态 70s 冻结
 * EDT）不得再出现在这条路径上。反查能力本身未删：缓存被预热后 [probeCheap] 即命中。
 */
internal object PagesEntityProbeResolver {

    /**
     * 同步路径探针（零扫描）：直查 + **只读**别名缓存，见类注释。
     *
     * 直查命中即返回（含按 `entityClass` 全限定名直查的常规形态）；打不到一律返回 null，
     * 由判定器按「无证据」回落保守默认（SQL），**不**触发全项目别名扫描。
     */
    fun probeCheap(project: Project, name: String, context: PsiElement?): PagesEntityProbe? = runCatching {
        val clazz = resolveEntityClass(project, name, context) ?: return null
        val table = JpaEntityResolver().resolve(clazz, context)
        PagesEntityProbe(
            entityName = JpaMetadataReader.entityNameOf(clazz)?.takeIf { it.isNotBlank() } ?: clazz.name,
            tableName = table.tableName.takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    private fun resolveEntityClass(project: Project, name: String, context: PsiElement?): PsiClass? {
        val byName = runCatching {
            EntityAliasSweep.resolveCachedOnly(project, name, context).psiClass
        }.getOrNull()
        if (byName != null) return byName
        // FQCN 兜底：别名扫描在 dumb 态直接 unresolved，限定名还能走 findClass
        if (!name.contains('.')) return null
        return runCatching {
            JavaPsiFacade.getInstance(project).findClass(name, GlobalSearchScope.allScope(project))
        }.getOrNull()
    }
}
