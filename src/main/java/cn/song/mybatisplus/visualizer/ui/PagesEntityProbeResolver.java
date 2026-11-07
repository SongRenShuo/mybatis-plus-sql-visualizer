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

    @Nullable
    public static PagesEntityProbe probe(Project project, String name, @Nullable PsiElement context) {
        try {
            PsiClass clazz = resolveEntityClass(project, name, context);
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
    private static PsiClass resolveEntityClass(Project project, String name, @Nullable PsiElement context) {
        EntityClassResolver.Resolution byName;
        try {
            byName = EntityAliasSweep.resolve(project, name, context);
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
