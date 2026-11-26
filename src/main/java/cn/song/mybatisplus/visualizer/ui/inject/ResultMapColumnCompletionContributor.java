// T-SyncK2JMaster482 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inject/ResultMapColumnCompletionContributor.kt
// T-SyncK2JMaster104 波C 增量同步：W5-10 resultMap 引用列候选 + F89 memo 双槽化 + F93 DumbAware
package cn.song.mybatisplus.visualizer.ui.inject;

import cn.song.mybatisplus.visualizer.psi.EntityFieldCollector;
import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.project.DumbAware;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.ProcessingContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * T-XmlSynHi D5 后半 + T-XmlGap W5-10：resultMap 属性值补全。
 *
 * <p>触发位：{@code <result|id … column="…"|property="…">} 属性值（resultMap 上下文）。</p>
 * <ul>
 *   <li>{@code column} 候选 = <b>引用了该 resultMap 的 {@code <select>} 语句</b>的投影列
 *       （T-XmlGap W5-10 修正：原为 mapper 全文 SELECT 投影列，会混入无关列），
 *       按已输入前缀过滤/排序（{@link ResultMapColumns#suggest}）。</li>
 *   <li>{@code property} 候选 = 该 resultMap {@code <resultMap type="X">} 的类字段（新增，W5-10「property 建议」）。</li>
 * </ul>
 * 非结果映射上下文零候选（反向验证）。
 *
 * <p>T-PerfSweep16 F40：补全链接让位门（{@link XmlSynHiGate#shouldYield}）+ 全文重扫 memo
 * （键=文件 url + modificationStamp [+ resultMapId]；F89 起 {@link ResultMapColumnCompletionMemo}
 * 双槽化）。</p>
 *
 * <p>F93（T-PerfSweep25，顺手件）：补 {@link DumbAware} 对齐三兄弟（XmlSqlCompletionContributor /
 * MybatisConfigCompletionContributor / WrapperCompletionContributor 均为
 * {@code CompletionContributor(), DumbAware}——T-XmlCmpl2 教训：缺该标记接口 dumb 期被静默跳过）。
 * dumb 期列名候选走文件文本提取不受索引影响；类字段候选（{@link #resultMapTypeFields} 的
 * findClass/ShortNamesCache）索引未就绪时可能不全，runCatching 兜底空表，无异常面。</p>
 */
public class ResultMapColumnCompletionContributor extends CompletionContributor implements DumbAware {

    public ResultMapColumnCompletionContributor() {
        extend(CompletionType.BASIC,
                PlatformPatterns.psiElement(PsiElement.class).withParent(XmlAttributeValue.class),
                new CompletionProvider<CompletionParameters>() {
                    @Override
                    protected void addCompletions(CompletionParameters parameters, ProcessingContext context,
                                                  CompletionResultSet result) {
                        PsiElement position = parameters.getPosition();
                        if (!(position.getParent() instanceof XmlAttributeValue)) {
                            return;
                        }
                        XmlAttributeValue attrValue = (XmlAttributeValue) position.getParent();
                        if (!(attrValue.getParent() instanceof XmlAttribute)) {
                            return;
                        }
                        XmlAttribute attr = (XmlAttribute) attrValue.getParent();
                        if (!attr.getName().equals("column") && !attr.getName().equals("property")) {
                            return;
                        }
                        if (!(attr.getParent() instanceof XmlTag)) {
                            return;
                        }
                        XmlTag tag = (XmlTag) attr.getParent();
                        if (!tag.getName().equals("result") && !tag.getName().equals("id")) {
                            return;
                        }
                        XmlTag resultMapTag = enclosingResultMap(tag);
                        if (resultMapTag == null) {
                            return;
                        }
                        // T-PerfSweep16 F40：让位门（设置关/平台注入器/三方插件在场 → 零候选）
                        if (XmlSynHiGate.shouldYield(position.getProject())) {
                            return;
                        }
                        if (!(resultMapTag.getContainingFile() instanceof XmlFile)) {
                            return;
                        }
                        XmlFile file = (XmlFile) resultMapTag.getContainingFile();
                        String typed = currentTyped(attrValue, parameters);
                        List<String> candidates;
                        if (attr.getName().equals("column")) {
                            String resultMapId = resultMapTag.getAttributeValue("id");
                            candidates = ResultMapColumnCompletionMemo.columnsForResultMap(
                                    file, resultMapId != null ? resultMapId : "");
                        } else {
                            candidates = resultMapTypeFields(resultMapTag);
                        }
                        for (String candidate : ResultMapColumns.suggest(candidates, typed)) {
                            result.addElement(LookupElementBuilder.create(candidate));
                        }
                    }
                });
    }

    /** 已输入前缀（属性值内光标前文本；引号由 XmlAttributeValue 边界排除在外）。 */
    private static String currentTyped(XmlAttributeValue attrValue, CompletionParameters parameters) {
        int offset = parameters.getOffset();
        int start = attrValue.getTextRange().getStartOffset() + 1; // 跳过开引号
        if (offset >= start && offset <= attrValue.getTextRange().getEndOffset()) {
            return attrValue.getText().substring(0,
                    Math.min(offset - start, attrValue.getText().length()));
        }
        return attrValue.getText();
    }

    /** 祖先链存在 resultMap 标签（result/id 行属于结果映射上下文）。 */
    private static XmlTag enclosingResultMap(XmlTag tag) {
        XmlTag cur = tag.getParentTag();
        while (cur != null) {
            if (cur.getName().equals("resultMap")) {
                return cur;
            }
            cur = cur.getParentTag();
        }
        return null;
    }

    /** {@code <resultMap type="X">} 的类字段（不可解析 → 空）。 */
    private static List<String> resultMapTypeFields(XmlTag resultMapTag) {
        try {
            String rawType = resultMapTag.getAttributeValue("type");
            String typeName = rawType != null ? rawType.trim() : null;
            if (typeName == null || typeName.isBlank()) {
                return List.of();
            }
            com.intellij.openapi.project.Project project = resultMapTag.getProject();
            GlobalSearchScope scope = GlobalSearchScope.allScope(project);
            PsiClass clazz;
            if (typeName.contains(".")) {
                clazz = JavaPsiFacade.getInstance(project).findClass(typeName, scope);
            } else {
                PsiClass[] classes = PsiShortNamesCache.getInstance(project).getClassesByName(typeName, scope);
                clazz = classes.length > 0 ? classes[0] : null;
            }
            if (clazz == null) {
                return List.of();
            }
            // D254：不用 getAllFields（平台不按名去重、不过静态）——resultMap property 候选按实体列语义收
            java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
            for (com.intellij.psi.PsiField field : EntityFieldCollector.collectColumns(clazz)) {
                String name = field.getName();
                if (name != null && !name.isBlank()) names.add(name);
            }
            return List.copyOf(names);
        } catch (Throwable t) {
            return List.of();
        }
    }
}
