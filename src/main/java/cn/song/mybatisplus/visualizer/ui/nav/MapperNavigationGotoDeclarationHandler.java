package cn.song.mybatisplus.visualizer.ui.nav;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.xml.XmlToken;
import org.jetbrains.annotations.Nullable;

/**
 * T-MapperNavShortcut：Mapper 双向跳转接入 IDEA 原生 <b>Ctrl+B / Ctrl+Click</b>（{@code GotoDeclaration}）。
 *
 * <h2>为什么用 {@code GotoDeclarationHandler} 而不是给 Java 侧补 {@code PsiReference}</h2>
 * <ul>
 *   <li>平台顺序是 <b>handler 优先、返回 null 才回落原生</b>（javap 实证 {@code GotoDeclarationUtil}：
 *       {@code findTargetElementsFromProviders} 只遍历 handler，首个非空即返回）。因此「非 Mapper 位置
 *       返回 null」即可保证原生跳转零变化，不存在「引用解析不到 → Cannot find declaration」风险。</li>
 *   <li>handler 只在用户按键时被调用，不进 daemon/索引热路径；Java 文件量远大于 mapper XML，
 *       给每个接口方法名造 {@code PsiReference} 会在高亮侧反复付成本。</li>
 * </ul>
 *
 * <h2>快捷键归属（用户 2026-11-05 明确要求）</h2>
 * <p>本类不持有任何 keymap，挂在平台 action 上——用户在 {@code Settings → Keymap} 把 Declaration
 * 改绑成别的键、加第二快捷键、改 Ctrl+Click 鼠标快捷键，本功能全部自动跟随；插件侧没有
 * 第二份键位配置需要同步。</p>
 *
 * <h2>覆盖方向</h2>
 * <ul>
 *   <li>Java 接口方法名 → XML 同名语句（落点取语句 {@code id} 的文本 token）；</li>
 *   <li>XML 语句的起始标签名 / {@code id} 属性名 / {@code id} 属性值 → 接口方法。</li>
 * </ul>
 *
 * <p>解析与判定集中在 {@link MapperNavigationTargets}，本类只做「元素形态分流」。</p>
 */
public class MapperNavigationGotoDeclarationHandler implements GotoDeclarationHandler {

    @Override
    @Nullable
    public PsiElement[] getGotoDeclarationTargets(
            @Nullable PsiElement sourceElement,
            int offset,
            @Nullable Editor editor) {
        PsiElement element = sourceElement;
        if (element == null) return null;
        // 任何解析异常都按「本次不接管」处理：回落原生跳转，不弹提示、不写 ERROR
        try {
            if (element instanceof PsiIdentifier) {
                return javaToXml((PsiIdentifier) element);
            }
            if (element instanceof XmlToken) {
                return xmlToJava((XmlToken) element);
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private PsiElement[] javaToXml(PsiIdentifier identifier) {
        if (!(identifier.getParent() instanceof PsiMethod)) return null;
        PsiMethod method = (PsiMethod) identifier.getParent();
        if (method.getNameIdentifier() != identifier) return null;
        java.util.List<com.intellij.psi.xml.XmlTag> tags = MapperNavigationTargets.statementTagsFor(method);
        if (tags.isEmpty()) return null;
        return tags.stream().map(MapperNavigationTargets::statementAnchor).toArray(PsiElement[]::new);
    }

    @Nullable
    private PsiElement[] xmlToJava(XmlToken token) {
        com.intellij.psi.xml.XmlTag tag = MapperNavigationTargets.ownerStatementTag(token);
        if (tag == null) return null;
        PsiMethod method = MapperNavigationTargets.methodFor(tag);
        if (method == null) return null;
        return new PsiElement[]{method};
    }
}
