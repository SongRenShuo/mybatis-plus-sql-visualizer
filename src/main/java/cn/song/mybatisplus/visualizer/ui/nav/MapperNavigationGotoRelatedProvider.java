package cn.song.mybatisplus.visualizer.ui.nav;

import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.navigation.GotoRelatedItem;
import com.intellij.navigation.GotoRelatedProvider;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import java.util.ArrayList;
import java.util.List;

/**
 * T-MapperNavShortcut：{@code Ctrl+Alt+Home}（Navigate → Related Symbol / {@code GotoRelated}）的 Mapper 关联项。
 *
 * <p>与 gutter 箭头同义、只是换成键盘入口：光标在接口方法上时列出对应 XML 语句，光标在 XML 语句上时
 * 列出对应接口方法。平台传入的是<b>光标处的叶子元素</b>（javap 实证 {@code GotoRelatedSymbolAction} 走
 * {@code file.findElementAt(offset)} 再 {@code NavigationUtil.collectRelatedItems}），所以两个方向都从祖先链判定。</p>
 *
 * <p>快捷键归属同 {@link MapperNavigationGotoDeclarationHandler}：不持有 keymap，跟随平台 {@code GotoRelated} action。</p>
 */
public class MapperNavigationGotoRelatedProvider extends GotoRelatedProvider {

    private static final String GROUP_STATEMENT = MpSqlVisualizerBundle.message("nav.related.group.mapperStatement");
    private static final String GROUP_METHOD = MpSqlVisualizerBundle.message("nav.related.group.mapperMethod");

    @Override
    @NotNull
    public List<GotoRelatedItem> getItems(@NotNull PsiElement element) {
        try {
            return itemsFor(element);
        } catch (Throwable ignored) {
            // runCatching.getOrDefault(emptyList()) 语义：解析失败按无关联项，不弹异常
            return List.of();
        }
    }

    @Override
    @NotNull
    public List<GotoRelatedItem> getItems(@NotNull DataContext context) {
        return List.of();
    }

    private List<GotoRelatedItem> itemsFor(PsiElement element) {
        PsiMethod method = MapperNavigationTargets.nearestMapperMethod(element);
        if (method != null) {
            List<GotoRelatedItem> statements = new ArrayList<>();
            for (XmlTag tag : MapperNavigationTargets.statementTagsFor(method)) {
                statements.add(new RelatedItem(tag, statementLabel(tag), GROUP_STATEMENT, MpvsIcons.MapperStatement));
            }
            if (!statements.isEmpty()) return statements;
        }
        XmlTag tag = MapperNavigationTargets.nearestStatementTag(element);
        if (tag == null) return List.of();
        PsiMethod target = MapperNavigationTargets.methodFor(tag);
        if (target == null) return List.of();
        PsiClass owner = target.getContainingClass();
        String ownerName = owner != null ? owner.getName() : null;
        String label = ownerName == null || ownerName.isEmpty()
                ? target.getName() : ownerName + "#" + target.getName();
        return List.of(new RelatedItem(target, label, GROUP_METHOD, MpvsIcons.MapperMethod));
    }

    /** 语句项展示名：{@code selectById}（{@code databaseId} 变体补后缀，避免两项同名看不出区别）。 */
    private static String statementLabel(XmlTag tag) {
        String id = tag.getAttributeValue("id") != null ? tag.getAttributeValue("id") : tag.getName();
        String databaseId = tag.getAttributeValue("databaseId");
        return databaseId == null || databaseId.isBlank() ? id : id + " [" + databaseId + "]";
    }

    /** 关联项：自定义展示名与图标（默认取元素自身的 name/icon，XML 标签会显示成 {@code select}）。 */
    private static final class RelatedItem extends GotoRelatedItem {
        private final String label;
        private final Icon icon;

        RelatedItem(PsiElement element, String label, String groupName, Icon icon) {
            super(element, groupName);
            this.label = label;
            this.icon = icon;
        }

        @Override
        public String getCustomName() {
            return label;
        }

        @Override
        public Icon getCustomIcon() {
            return icon;
        }
    }
}
