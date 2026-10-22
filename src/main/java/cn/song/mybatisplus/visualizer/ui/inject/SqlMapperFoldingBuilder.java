// T-SyncK2JMaster104 波C 翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inject/SqlMapperFoldingBuilder.kt
package cn.song.mybatisplus.visualizer.ui.inject;

import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.FoldingBuilderEx;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * T-XmlGap W6：mapper XML 语句标签的<b>带 id 折叠</b>（竞品 {@code v.a} 的同型能力，内部调研报告 §2.3）。
 *
 * <h2>与平台 XML 折叠的关系（这是本类的设计前提，勿删）</h2>
 *
 * <p>平台自带的 {@code com.intellij.lang.xml.XmlFoldingBuilder}（基类 {@code XmlCodeFoldingBuilder}）<b>已经</b>为 XML
 * 标签建 fold 区，占位文本是标签名本身（{@code XmlCodeFoldingBuilder.getRangeToFold} 取
 * {@code XmlTagUtil.getStartTagNameElement}）；{@code XmlCodeFoldingSettings.isCollapseXmlTags()} 只控制
 * <b>默认是否折叠</b>，不影响 fold 区是否存在（javap 实证，2026.2 平台）。</p>
 *
 * <p>因此本类的价值<b>不是</b>「让标签可折叠」（平台已有），而是<b>把占位文本换成带 id 的形态</b>
 * （{@code select#findById}）——长 mapper 里折叠后一眼能认出是哪个语句，这是平台给不了的。</p>
 *
 * <p>作用面刻意收窄到最小，避免与平台抢 fold 区：</p>
 * <ul>
 *   <li>只在<b>根标签为 {@code mapper}</b> 的文件里生效；</li>
 *   <li>只对<b>语句标签</b>（select/insert/update/delete）与带 {@code id} 的 {@code <sql>} 建区；</li>
 *   <li>只对<b>跨多行</b>的标签建区（单行标签折叠无视觉意义，且更易与平台区重叠）；</li>
 *   <li>不自带默认折叠（{@code isCollapsedByDefault = false}，与平台「不自动折叠 XML 标签」一致）；</li>
 *   <li>{@code quick=true} 短路、超 [XmlSynHiGate.MAX_TEXT_LENGTH] 熔断返回空（T-PerfSweep26 F95，
 *       编辑期快速重建与万行级文件不做子树遍历，见 [buildFoldRegions] 内注释）。</li>
 * </ul>
 *
 * <p>与平台区<b>范围相同</b>时的占位优先级属渲染期行为，单测只能钉本类产出；真机观感列入 W8 走查项
 * （计划 §十 遗留）。</p>
 */
public class SqlMapperFoldingBuilder extends FoldingBuilderEx {

    @Override
    @NotNull
    public FoldingDescriptor[] buildFoldRegions(@NotNull PsiElement root, @NotNull Document document, boolean quick) {
        // T-PerfSweep26 F95：`quick=true` 只发生在编辑器打开瞬间的初始折叠
        // （`CodeFoldingManagerImpl.buildInitialFoldings` → `FoldingUpdate.getFoldingsFor(file, quick=true)`，
        // 243 sources jar 实证），平台契约要求该 pass "avoid complex checks if possible"
        // （`FoldingBuilderEx.buildFoldRegions` javadoc）；随后 daemon `CodeFoldingPass` 的
        // `updateFoldRegionsAsync` 恒以 `quick=false` 全量重建（`updateFoldRegions(editor, firstTime, quick=false)`）。
        // 本类不自动折叠、无打开瞬间必须立即可见的区（对比 Java imports），quick pass 直接短路，
        // 全量区由 daemon 补齐——省掉一次全 PSI 子树遍历。
        if (quick) return FoldingDescriptor.EMPTY;
        if (!(root.getContainingFile() instanceof XmlFile)) return FoldingDescriptor.EMPTY;
        XmlFile file = (XmlFile) root.getContainingFile();
        if (file.getRootTag() == null || !file.getRootTag().getName().equals("mapper")) {
            return FoldingDescriptor.EMPTY;
        }
        // T-PerfSweep26 F95：体量熔断——超万行级 mapper 不做子树遍历（与注入族 XmlSynHiGate 同阈值同防线）。
        if (document.getTextLength() > XmlSynHiGate.MAX_TEXT_LENGTH) return FoldingDescriptor.EMPTY;
        List<FoldingDescriptor> out = new ArrayList<>();
        for (XmlTag tag : PsiTreeUtil.findChildrenOfType(root, XmlTag.class)) {
            if (!FOLDABLE_TAGS.contains(tag.getName())) continue;
            String id = tag.getAttributeValue("id");
            if (id == null || id.isBlank()) continue;
            com.intellij.openapi.util.TextRange range = tag.getTextRange();
            if (!spansMultipleLines(document, range.getStartOffset(), range.getEndOffset())) continue;
            out.add(new FoldingDescriptor(tag, range.getStartOffset(), range.getEndOffset(), null,
                    tag.getName() + "#" + id));
        }
        return out.toArray(new FoldingDescriptor[0]);
    }

    /** 与平台一致：不自动折叠（平台侧由 {@code isCollapseXmlTags} 用户设置决定）。 */
    @Override
    public boolean isCollapsedByDefault(@NotNull ASTNode node) {
        return false;
    }

    /**
     * 占位文本（FoldingBuilder 接口的抽象成员；FoldingBuilderEx 只提供二参重载）。
     *
     * <p>本类的区都在 {@link #buildFoldRegions} 里带了显式占位，故本方法实际不被走到；仍按同一形态实现，
     * 避免被平台在其它调用点问到时空手（返回 null 会落到「用原文」的默认行为）。</p>
     */
    @Override
    @Nullable
    public String getPlaceholderText(@NotNull ASTNode node) {
        if (!(node.getPsi() instanceof XmlTag)) return null;
        XmlTag tag = (XmlTag) node.getPsi();
        if (!FOLDABLE_TAGS.contains(tag.getName())) return null;
        String id = tag.getAttributeValue("id");
        if (id == null) return null;
        return tag.getName() + "#" + id;
    }

    private static boolean spansMultipleLines(Document document, int start, int end) {
        if (document.getTextLength() == 0) return false;
        int safeStart = Math.max(0, Math.min(start, document.getTextLength() - 1));
        int safeEnd = Math.max(0, Math.min(end, document.getTextLength() - 1));
        return document.getLineNumber(safeStart) != document.getLineNumber(safeEnd);
    }

    /** 可折叠标签：语句标签 + 带 id 的 {@code <sql>} 片段（resultMap 族不折——它们通常很短且 id 语义弱）。 */
    private static final Set<String> FOLDABLE_TAGS = Set.of("select", "insert", "update", "delete", "sql");
}
