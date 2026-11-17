// T-SyncK2JMaster104 波C 翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inject/MybatisSqlTemplateContextType.kt
package cn.song.mybatisplus.visualizer.ui.inject;

import com.intellij.codeInsight.template.TemplateContextType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * T-XmlGap W6：live template 的<b>自定义上下文</b>——只在 mapper 语句标签内可用。
 *
 * <p>与竞品的取舍：竞品有 6 个模板集，其中 {@code table}/{@code sql}/{@code ignore} 三个是它的<b>引导注释体系</b>
 * （用户手写 {@code <!--@Table-->}/{@code <!--@sql-->} 喂平台注入）；我方不做校验器、不需要这套注释依赖，
 * <b>不照搬</b>（内部调研报告 §2.5 已留痕）。本类只服务两个与注释体系无关的纯骨架模板：{@code cd}
 * （CDATA 包裹）、{@code co}（foreach 骨架）。</p>
 *
 * <p>模板文件在 {@code resources/templates/mybatis-sql.xml}（平台自动扫描插件 {@code templates/} 目录），
 * 其中 {@code <context><option name="MYBATIS_SQL" .../></context>} 的 name 必须等于本类的 {@link #ID}。</p>
 */
public class MybatisSqlTemplateContextType extends TemplateContextType {

    public MybatisSqlTemplateContextType() {
        super(ID, "MyBatis SQL");
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean isInContext(@NotNull PsiFile file, int offset) {
        if (!(file instanceof XmlFile)) return false;
        XmlFile xmlFile = (XmlFile) file;
        if (xmlFile.getRootTag() == null || !xmlFile.getRootTag().getName().equals("mapper")) return false;
        int safeOffset = Math.max(0, Math.min(offset, Math.max(0, file.getTextLength() - 1)));
        PsiElement element = file.findElementAt(safeOffset);
        if (element == null) return false;
        XmlTag tag = PsiTreeUtil.getParentOfType(element, XmlTag.class, false);
        int depth = 0;
        while (tag != null && depth++ < MAX_ANCESTOR_DEPTH) {
            if (STATEMENT_TAGS.contains(tag.getName())) return true;
            tag = tag.getParentTag();
        }
        return false;
    }

    /** 上下文 id（模板 XML 的 {@code <option name>} 必须与之一致）。 */
    public static final String ID = "MYBATIS_SQL";

    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete", "sql");

    private static final int MAX_ANCESTOR_DEPTH = 24;
}
