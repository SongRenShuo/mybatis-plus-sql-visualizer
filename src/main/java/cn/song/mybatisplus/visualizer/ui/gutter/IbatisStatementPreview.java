package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.ui.IbatisXmlPreviewService;
import cn.song.mybatisplus.visualizer.ui.nav.IbatisXmlStatementTargets;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * T-IbatisCallPreview：iBatis 语句预览的<b>唯一装配点</b>。
 *
 * <p>三处入口共用同一份「语句标签 / statementId → {@link PreviewResult}」装配，产物逐字节同源：
 * gutter 图标（{@code <sqlMap>} 语句行与 Java DAO 调用行）、快捷键/右键（ConvertToSqlAction 的
 * iBatis 回落两档）、MCP（McpSourceLocator 的 sqlMap 语句与 Java 调用行分支）。</p>
 *
 * <p>「同一份装配」不是洁癖：iBatis 的 {@code #name#} 参数名、条件行、{@code <selectKey>} 块与
 * {@code $x$} 闸门都由 {@link IbatisXmlPreviewService} 建模，任何一处另写渲染都会让面板产物分叉
 * （T-IbatisFix2/Fix3 的教训）。</p>
 *
 * <p>本类只做 PSI 读取与调用转接，<b>调用方必须持读锁</b>（gutter 点击在 previewAsync 的
 * ReadAction.compute 内；MCP 在 ReadAction 内；快捷键同样走 previewAsync）。</p>
 */
public final class IbatisStatementPreview {

    private IbatisStatementPreview() {
    }

    /**
     * 语句标签 → 预览产物（fileText 取标签所在文件，statementId 取裸 id——解析模型 id 即裸 id）。
     */
    public static PreviewResult previewTag(@NotNull XmlTag tag) {
        String moduleName;
        try {
            moduleName = ModuleUtilCore.findModuleForPsiElement(tag) != null
                    ? ModuleUtilCore.findModuleForPsiElement(tag).getName() : null;
        } catch (Throwable e) {
            moduleName = null;
        }
        return IbatisXmlPreviewService.preview(
                tag.getContainingFile().getText(),
                tag.getAttributeValue("id"),
                tag.getName(),
                statementOrdinalOf(tag),
                // T-IbatisParamClassQuote：PSI 上下文 + parameterClass 交给服务——裸 `#name#` 的类型
                //（引号）只能从参数类的字段类型推，XML 属性里本来就有，此前没人消费
                tag.getProject(),
                tag.getAttributeValue("parameterClass"),
                // L34：多模块工程里短名重名是常态（demo 三个 User），带上语句所在模块做「就近」收窄
                moduleName);
    }

    /**
     * statementId → 目标语句标签；解析复用 {@link IbatisXmlStatementTargets#resolveStatementAnchors}
     * （与跳转/GTD 同一份 {@code ns.id} / 裸 id 解析与 TTL memo），多目标取首个（与跳转箭头点击落点同口径）。
     */
    @Nullable
    public static XmlTag resolveStatementTag(@NotNull Project project,
                                             @NotNull String statementId,
                                             @Nullable String ownerNamespace) {
        var anchors = IbatisXmlStatementTargets.resolveStatementAnchors(project, statementId, ownerNamespace);
        if (anchors.isEmpty()) {
            return null;
        }
        return anchors.get(0).getParent() != null && anchors.get(0).getParent().getParent() instanceof XmlTag tag
                ? tag : null;
    }

    /**
     * statementId → 目标语句标签 → 预览产物；目标缺失返回「语句未找到」<b>可见失败</b>（不是 null）。
     *
     * <p>目标解析与多目标选择口径见 {@link #resolveStatementTag}。</p>
     */
    @NotNull
    public static PreviewResult previewResolved(@NotNull Project project,
                                                @NotNull String statementId,
                                                @Nullable String ownerNamespace) {
        XmlTag tag = resolveStatementTag(project, statementId, ownerNamespace);
        return tag != null ? previewTag(tag) : statementNotFound();
    }

    /**
     * 同标签名兄弟中的序号（0 起）——语句缺 {@code id} 属性（非法文件）时的定位回落。
     * 纯 PSI 兄弟遍历，O(兄弟数)，不进 gutter 热路径（只在无 id 的小概率分支调用）。
     */
    public static int statementOrdinalOf(@NotNull XmlTag tag) {
        String name = tag.getName();
        if (tag.getParentTag() == null) {
            return 0;
        }
        int ordinal = 0;
        for (com.intellij.psi.PsiElement child : tag.getParentTag().getChildren()) {
            if (!(child instanceof XmlTag childTag) || !childTag.getName().equals(name)) {
                continue;
            }
            if (childTag.getTextRange().getStartOffset() >= tag.getTextRange().getStartOffset()) {
                break;
            }
            ordinal++;
        }
        return ordinal;
    }

    /**
     * 目标缺失的可见失败结果：空 SQL + 「语句未找到」告警。
     *
     * <p>不用 {@code SqlPreviewService.showResult(null)} 的通用「未找到可转换的 Wrapper」文案——
     * 那条提示指向 Wrapper 链，在 iBatis 场景属错源归因（T-IbatisFix3 现场）。空 sql 让面板呈现失败态，
     * 告警文案说明「语句可能已被改名/删除」。</p>
     */
    @NotNull
    public static PreviewResult statementNotFound() {
        return new PreviewResult(
                "",
                "",
                java.util.List.of(),
                null,
                null,
                null,
                MpSqlVisualizerBundle.message("ibatis.preview.statementNotFound.warning"),
                java.util.List.of(),
                java.util.Map.of(),
                null,
                null,
                java.util.List.of(),
                java.util.List.of(),
                null,
                null,
                java.util.Map.of(),
                null,
                null,
                null,
                null);
    }
}
