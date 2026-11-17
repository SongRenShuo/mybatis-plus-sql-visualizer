package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.psi.EntityClassResolver;
import cn.song.mybatisplus.visualizer.psi.jpa.EntityAliasSweep;
import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver;
import cn.song.mybatisplus.visualizer.psi.jpa.JpaMetadataReader;
import cn.song.mybatisplus.visualizer.xml.PagesEntityProbe;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.Nullable;

/**
 * T-PagesHql：语言判定用的实体探针（名字 → 实体名/表名）。
 *
 * <p>供两条链共用：{@code <pages>} 页面语句（{@link PagesXmlPreviewService}）与通用 XML 内嵌 SQL
 * （{@link XmlInlineSqlPreviewService}，如无 {@code entityClass} 的 {@code <dataValue>}）。判定器本身保持纯函数，
 * PSI/索引访问集中在这里，Dumb 态与解析异常一律返回 null（判定回落 SQL，不抛）。</p>
 */
public final class PagesEntityProbeResolver {

    private PagesEntityProbeResolver() {
    }

    /**
     * 券 0（T-Ibatis2SlowFix）：同步路径探针（<b>零扫描</b>）——直查 + 只读别名缓存。
     * 直查命中即返回（含按 entityClass 全限定名直查的常规形态）；打不到一律返回 null，
     * 由判定器按「无证据」回落保守默认（SQL），<b>不</b>触发全项目别名扫描。
     * 反查能力本身未删：缓存被预热后 probeCheap 即命中。
     */
    @Nullable
    public static PagesEntityProbe probeCheap(Project project, String name, @Nullable PsiElement context) {
        // T-Ibatis2SlowFix（master 券 0）：同步预览热路径只读别名缓存，绝不触发全项目短名扫描。
        return probe(project, name, context, true);
    }

    @Nullable
    public static PagesEntityProbe probe(Project project, String name, @Nullable PsiElement context) {
        return probe(project, name, context, false);
    }

    @Nullable
    private static PagesEntityProbe probe(Project project, String name, @Nullable PsiElement context, boolean cheap) {
        try {
            PsiClass clazz = resolveEntityClass(project, name, context, cheap);
            if (clazz == null) return null;
            cn.song.mybatisplus.visualizer.model.TableInfo table = new JpaEntityResolver().resolve(clazz, context);
            String entityName = JpaMetadataReader.entityNameOf(clazz);
            return new PagesEntityProbe(
                    entityName != null && !entityName.isBlank() ? entityName : clazz.getName(),
                    table != null && table.getTableName() != null && !table.getTableName().isBlank()
                            ? table.getTableName() : null);
        } catch (Throwable ignored) {
            // runCatching.getOrNull 语义：Dumb/解析异常一律 null（判定回落 SQL，不抛）
            return null;
        }
    }

    @Nullable
    private static PsiClass resolveEntityClass(Project project, String name, @Nullable PsiElement context, boolean cheap) {
        EntityClassResolver.Resolution byName;
        try {
            // 同步热路径（probeCheap）只读缓存；全量扫描形态（resolve）单次约 1s，历史实测可冻结 EDT。
            byName = cheap
                    ? EntityAliasSweep.resolveCachedOnly(project, name, context)
                    : EntityAliasSweep.resolve(project, name, context);
        } catch (Throwable ignored) {
            byName = null;
        }
        if (byName != null && byName.psiClass() != null) return byName.psiClass();
        // FQCN 兜底：别名扫描在 dumb 态直接 unresolved，限定名还能走 findClass
        if (!name.contains(".")) return null;
        try {
            return JavaPsiFacade.getInstance(project).findClass(name, GlobalSearchScope.allScope(project));
        } catch (Throwable ignored) {
            return null;
        }
    }
}
