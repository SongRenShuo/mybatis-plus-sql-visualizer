// T-SyncK2JMaster482 更新翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/highlight/XmlSqlCompletionContributor.kt
// T-SyncK2JMaster104 波C 增量同步：T-XmlGap W4/W5 扩面（属性名/属性值分流、#{} 结构属性、${} OGNL、投影/反引号/大小写口径）
package cn.song.mybatisplus.visualizer.ui.highlight;

import cn.song.mybatisplus.visualizer.sql.SqlTextAnalysis;
import cn.song.mybatisplus.visualizer.ui.SourceSqlContext;
import cn.song.mybatisplus.visualizer.ui.XmlParamTypeResolver;
import cn.song.mybatisplus.visualizer.ui.XmlSqlCompletionContextResolver;
import cn.song.mybatisplus.visualizer.ui.gutter.XmlSqlGutterLineMarkerProvider;
import cn.song.mybatisplus.visualizer.ui.inject.ResultMapColumnCompletionMemo;
import cn.song.mybatisplus.visualizer.xml.ParamKind;
import cn.song.mybatisplus.visualizer.xml.ParamTypeInfo;
import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.completion.PrefixMatcher;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.injected.editor.DocumentWindow;
import com.intellij.injected.editor.VirtualFileWindow;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mapper XML 源码补全贡献器（T-M27-03/04；T-XmlGap W4/W5 扩面）。
 *
 * <p>背景：为 {@code <select|insert|update|delete>} 语句与 {@code <sql>} 片段提供补全，全部基于<b>纯文本</b>分析
 * （{@link SqlTextAnalysis#stripXmlTags} 等长剥离 + {@link SourceSqlCompleter}）或<b>有界 PSI</b>
 * （XmlCompletionSymbols）：</p>
 * <ol>
 *   <li><b>动态标签补全</b>：光标前非空白字符是 {@code <} → 补 SourceSqlSuggestionFactory.DYNAMIC_TAGS；</li>
 *   <li><b>属性名补全（T-XmlGap W4-3）</b>：标签内属性名位置 → MyBatisXmlCompletionData.tagAttributes，
 *       含 {@code <select|insert|update|delete|selectKey>} 与 {@code <resultMap|id|result|collection|association|…>}
 *       （DTD 逐条）；</li>
 *   <li><b>属性值补全（T-XmlGap W4-2：按属性名分流）</b>：见 {@link #addAttributeValueCompletions}；</li>
 *   <li><b>{@code #{}} 结构属性（T-XmlGap W4-1）</b>：{@code #{…,} 后切属性键，{@code key=} 后给枚举值；</li>
 *   <li><b>{@code ${}} OGNL（T-XmlGap W5-4/5/6）</b>：变量来源 + 智能片段 + 运算符 + {@code @类@静态成员}；</li>
 *   <li><b>SQL 补全（T-XmlGap W5-7/8/9）</b>：关键字 + 函数表 + 单字母捷径 + 表/列/别名列 +
 *       select 投影位（resultType 类属性）+ 反引号内列名。</li>
 * </ol>
 *
 * <p><b>守卫（性能红线，O(1) 语法判定，零 resolve）</b>：position 祖先链上的<b>最近语句标签或 {@code <sql>} 片段</b>
 * 且根标签名 == "mapper"，否则直接 return。所有补全项 addElement 前经
 * {@code result.prefixMatcher.prefixMatches(...)} 过滤。</p>
 *
 * <p>设计决策（保留）：双语言注册 + 注入片段 caret 宿主还原（T-PerfSweep16/T-XmlCmplDispatch）。</p>
 *
 * <p>注册见 plugin.xml {@code <completion.contributor language="XML" .../>} + {@code language="MpSqlMapper"}
 * （主控统一注册）。</p>
 *
 * <p>实现 {@link DumbAware}（T-XmlCmpl2）：dumb 态照常被咨询；触索引的来源（跨文件 sql id / config properties）
 * 在 dumb 态返回空（见 XmlCompletionSymbols）。</p>
 */
public class XmlSqlCompletionContributor extends CompletionContributor implements DumbAware {

    public XmlSqlCompletionContributor() {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), new CompletionProvider<CompletionParameters>() {
            @Override
            protected void addCompletions(CompletionParameters parameters, ProcessingContext context, CompletionResultSet result) {
                // 宿主还原（T-PerfSweep16 修复 + T-XmlCmplDispatch 二轮修正）：光标落在本插件注入片段内时
                // 传给本回调的 position/offset 已被 toInjectedIfAny 换成注入侧坐标——按三形态还原宿主坐标
                // （优先级降序）：① 注入文件自身的 DocumentWindow（生产形态）；② editor.document 的
                // window 形态（fixture 形态）；③ 都拿不到 → 原样（宿主形态 no-op）。
                PsiElement injectedFile = parameters.getPosition().getContainingFile();
                // getVirtualFile 声明在 PsiFile 接口（PsiElement 无此方法）——containingFile 恒 PsiFile，cast 取用
                PsiFile injectedPsiFile = (PsiFile) injectedFile;
                PsiLanguageInjectionHost injectionHost = null;
                if (injectedFile != null) {
                    injectionHost = InjectedLanguageManager.getInstance(injectedFile.getProject())
                            .getInjectionHost(injectedFile);
                }
                PsiElement hostPsiFile = injectionHost != null ? injectionHost.getContainingFile() : null;
                PsiElement position;
                int offset;
                if (hostPsiFile != null && hostPsiFile.getTextLength() > 0) {
                    Integer hostOffset = null;
                    if (injectedPsiFile.getVirtualFile() instanceof VirtualFileWindow) {
                        hostOffset = ((VirtualFileWindow) injectedPsiFile.getVirtualFile()).getDocumentWindow()
                                .injectedToHost(parameters.getOffset());
                    }
                    if (hostOffset == null && parameters.getEditor().getDocument() instanceof DocumentWindow) {
                        hostOffset = ((DocumentWindow) parameters.getEditor().getDocument())
                                .injectedToHost(parameters.getOffset());
                    }
                    if (hostOffset == null) {
                        hostOffset = parameters.getOffset();
                    }
                    int clamped = Math.max(0, Math.min(hostOffset, hostPsiFile.getTextLength() - 1));
                    position = hostPsiFile.findElementAt(clamped);
                    if (position == null) {
                        return;
                    }
                    offset = Math.max(0, Math.min(hostOffset, hostPsiFile.getTextLength()));
                } else {
                    position = parameters.getPosition();
                    offset = parameters.getOffset();
                }

                // 守卫：祖先链最近语句标签或 `<sql>` 片段 + 根 <mapper>（O(1) 语法判定）
                XmlTag enclosingTag = findEnclosingStatementTag(position);
                if (enclosingTag == null) {
                    return;
                }
                if (!rootTag(enclosingTag).getName().equals("mapper")) {
                    return;
                }

                PrefixMatcher prefix = result.getPrefixMatcher();
                String fileText = position.getContainingFile() != null ? position.getContainingFile().getText() : null;
                if (fileText == null) {
                    return;
                }
                int statementStart = enclosingTag.getTextRange().getStartOffset();
                String statementText = fileText.substring(statementStart, enclosingTag.getTextRange().getEndOffset());
                int caretInStatement = Math.max(0, Math.min(offset - statementStart, statementText.length()));
                String beforeCaret = statementText.substring(0, caretInStatement);

                // a. 动态标签：光标前非空白字符是 `<`（`1=1 <caret>` 等插入点）
                if (SourceSqlSuggestionFactory.DYNAMIC_TAG_PATTERN.matcher(beforeCaret).find()) {
                    for (String tag : SourceSqlSuggestionFactory.DYNAMIC_TAGS) {
                        if (prefix.prefixMatches(tag)) {
                            result.addElement(SourceSqlSuggestionFactory.xmlTag(tag));
                        }
                    }
                    return;
                }

                // b. 属性值位置（position 在 XmlAttributeValue 内）→ 按属性名分流（W4-2）
                XmlAttributeValue attributeValue = PsiTreeUtil.getParentOfType(position, XmlAttributeValue.class, false);
                if (attributeValue != null) {
                    XmlAttribute attribute = attributeValue.getParent() instanceof XmlAttribute
                            ? (XmlAttribute) attributeValue.getParent() : null;
                    XmlTag ownerTag = attribute != null && attribute.getParent() instanceof XmlTag
                            ? (XmlTag) attribute.getParent() : null;
                    if (attribute != null && attribute.getName() != null && ownerTag != null) {
                        addAttributeValueCompletions(
                                attribute.getName(),
                                ownerTag,
                                enclosingTag,
                                position,
                                beforeCaret,
                                result);
                    }
                    return;
                }

                // c. 属性名位置：position 在 XmlAttribute 上（属性名 token 内）或语句文本
                //    光标前匹配 `<tagName  `（标签内属性名位）
                XmlTag nearestTag = PsiTreeUtil.getParentOfType(position, XmlTag.class);
                boolean attributeNamePosition = nearestTag != null && (
                        position.getParent() instanceof XmlAttribute ||
                                SourceSqlSuggestionFactory.ATTRIBUTE_POSITION_PATTERN.matcher(beforeCaret).find()
                );
                if (attributeNamePosition) {
                    for (String attribute : MyBatisXmlCompletionData.tagAttributes(nearestTag.getName())) {
                        if (prefix.prefixMatches(attribute)) {
                            result.addElement(SourceSqlSuggestionFactory.xmlAttribute(attribute));
                        }
                    }
                    return;
                }

                // d. `${…}` 未闭合 → OGNL 变量 + 智能片段 + 运算符 + @类@静态成员（W5-4/5/6）
                if (SourceSqlSuggestionFactory.isInsideDollarBrace(beforeCaret)) {
                    addOgnlCompletions(
                            enclosingTag,
                            position,
                            beforeCaret,
                            result,
                            false,
                            true);
                    return;
                }

                // e. `#{…}` 未闭合 → 逗号后结构属性（W4-1）/ 否则参数名（含 foreach item·index、bind）
                if (SourceSqlSuggestionFactory.isInsideHashBrace(beforeCaret)) {
                    String inside = beforeCaret.substring(beforeCaret.lastIndexOf("#{") + 2);
                    if (inside.contains(",")) {
                        addParamStructureCompletions(inside, result);
                    } else {
                        addHashParamCompletions(inside, enclosingTag, position, result);
                    }
                    return;
                }

                // f. SQL 补全：语句文本等长剥离标签 → 纯文本分析 → prefixMatches 过滤
                String stripped = SqlTextAnalysis.stripXmlTags(statementText);
                SourceSqlContext sqlContext = resolveContext(enclosingTag);
                List<String> projectionProperties = projectionProperties(enclosingTag, sqlContext);
                List<String> quotedCandidates = quotedIdentifierCandidates(enclosingTag);
                // 大小写口径（T-XmlGap 收口第三轮真机实测）：候选词表多为**大写**关键字/函数
                // （SELECT/WHERE/CONCAT…），而编辑器给的前缀匹配器**区分大小写** ⇒ 小写键入
                // `wh` 时 `WHERE` 一个都不出（沙盒对照读数：`wh` 无候选、`WH` 出 `WHEN`/`WHERE`），
                // 用户观感就是「补全不灵」。SQL 关键字/函数与未加引号的标识符在语义上都大小写
                // 不敏感（MySQL/PG/Oracle 的未引号标识符均折叠大小写），故本分支在平台匹配之外
                // 再补一次**忽略大小写**的匹配；其余分支（参数名/实体字段等 Java 侧标识符）保持
                // 平台原语义不变。
                String typedPrefix = prefix.getPrefix();
                for (LookupElementBuilder item : SourceSqlCompleter.sqlCompletions(
                        stripped, caretInStatement, sqlContext, projectionProperties, quotedCandidates)) {
                    boolean anyMatch = item.getAllLookupStrings().stream().anyMatch(candidate ->
                            prefix.prefixMatches(candidate)
                                    || (!typedPrefix.isEmpty()
                                    && candidate.regionMatches(true, 0, typedPrefix, 0, typedPrefix.length())));
                    if (anyMatch) {
                        result.addElement(item);
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // b. 属性值分流（T-XmlGap W4-2）
    // ------------------------------------------------------------------

    /**
     * 属性值补全按<b>属性名</b>分流（W4-2）。每条：
     * <ul>
     *   <li>{@code test}/{@code value}/{@code collection} → OGNL（变量 + 片段 + 运算符；{@code collection} 追加集合快捷别名）；</li>
     *   <li>{@code id}（语句标签）→ namespace 接口方法名；{@code keyProperty} → 方法参数名 + 实体字段名；</li>
     *   <li>{@code refid} → 本文件 {@code <sql id>} + 跨文件 {@code namespace.id}；</li>
     *   <li>{@code resultMap}/{@code extends} → 本文件 {@code <resultMap id>}；{@code parameterMap} → 本文件 {@code <parameterMap id>}；</li>
     *   <li>{@code resultType}/{@code type}/{@code ofType}/{@code parameterType}/{@code typeHandler}/{@code javaType} → 有界类名；</li>
     *   <li>{@code property}（及 {@code <arg name>}）→ 实体字段名（resultMap 上下文取 {@code <resultMap type>} 类字段）；</li>
     *   <li>{@code column}/{@code keyColumn}/{@code columnPrefix} → 列名（resultMap 的 {@code result}/{@code id} 交由
     *       ResultMapColumnCompletionContributor 独占，避免重复候选）；</li>
     *   <li>{@code jdbcType}/{@code mode}/{@code statementType}/{@code resultSetType}/{@code fetchType}/{@code order} → 枚举值；</li>
     *   <li>布尔属性（useGeneratedKeys/flushCache/…）→ true/false；</li>
     *   <li>{@code select} → 本文件语句 id（arg/association/collection 指向的 statement）；</li>
     *   <li>{@code timeout}/{@code fetchSize}/{@code numericScale}/{@code resultSets}/… → 无合理候选，明确不产。</li>
     * </ul>
     *
     * <p>其余属性值 → 不产出（不落入 SQL 分支，避免噪声）。</p>
     */
    private void addAttributeValueCompletions(
            String attributeName,
            XmlTag ownerTag,
            XmlTag enclosingTag,
            PsiElement caretElement,
            String beforeCaret,
            CompletionResultSet result) {
        PrefixMatcher prefix = result.getPrefixMatcher();
        Project project = ownerTag.getProject();
        String tagName = ownerTag.getName();
        boolean inResultMap = ancestorResultMapTag(ownerTag) != null;

        if (attributeName.equals("test") || attributeName.equals("collection") || attributeName.equals("value")) {
            addOgnlCompletions(
                    enclosingTag,
                    caretElement,
                    beforeCaret,
                    result,
                    attributeName.equals("collection"),
                    true);
        } else if (attributeName.equals("id") && MyBatisXmlCompletionData.STATEMENT_TAGS.contains(tagName)) {
            emitAll(XmlCompletionSymbols.mapperMethodNames(project, enclosingTag), prefix, result);
        } else if (attributeName.equals("keyProperty")) {
            SourceSqlContext context = resolveContext(enclosingTag);
            java.util.ArrayList<String> merged = new java.util.ArrayList<>(context.getParamNames());
            merged.addAll(XmlCompletionSymbols.entityFieldNames(context));
            emitAll(merged, prefix, result);
        } else if (attributeName.equals("refid")) {
            XmlFile xmlFile = enclosingTag.getContainingFile() instanceof XmlFile
                    ? (XmlFile) enclosingTag.getContainingFile() : null;
            java.util.ArrayList<String> merged = new java.util.ArrayList<>(XmlCompletionSymbols.localIds(enclosingTag, "sql"));
            merged.addAll(XmlCompletionSymbols.crossFileSqlIds(project, xmlFile));
            emitAll(merged, prefix, result);
        } else if (attributeName.equals("resultMap") || attributeName.equals("extends")) {
            emitAll(XmlCompletionSymbols.localIds(enclosingTag, "resultMap"), prefix, result);
        } else if (attributeName.equals("parameterMap")) {
            emitAll(XmlCompletionSymbols.localIds(enclosingTag, "parameterMap"), prefix, result);
        } else if (MyBatisXmlCompletionData.CLASS_NAME_ATTRIBUTES.contains(attributeName)) {
            SourceSqlContext context = resolveContext(enclosingTag);
            emitAll(XmlCompletionSymbols.classCandidates(project, enclosingTag, context), prefix, result);
        } else if (attributeName.equals("property")) {
            List<String> fields;
            if (inResultMap) {
                fields = XmlCompletionSymbols.resultMapTypeFields(ownerTag);
            } else {
                fields = XmlCompletionSymbols.classPropertyNames(
                        project, ownerTag, XmlCompletionSymbols.entityFieldNames(resolveContext(enclosingTag)));
            }
            emitAll(fields, prefix, result);
        } else if (attributeName.equals("name") && (tagName.equals("arg") || tagName.equals("idArg"))) {
            emitAll(XmlCompletionSymbols.entityFieldNames(resolveContext(enclosingTag)), prefix, result);
        } else if (attributeName.equals("column")) {
            // resultMap 的 result/id：由 ResultMapColumnCompletionContributor 独占（防重复候选）
            if (inResultMap && (tagName.equals("result") || tagName.equals("id"))) return;
            List<String> referenced = resultMapReferencedColumns(ownerTag);
            emitAll(referenced != null ? referenced
                            : XmlCompletionSymbols.entityColumnNames(resolveContext(enclosingTag)),
                    prefix, result);
        } else if (attributeName.equals("columnPrefix") || attributeName.equals("keyColumn")) {
            List<String> referenced = resultMapReferencedColumns(ownerTag);
            emitAll(referenced != null ? referenced
                            : XmlCompletionSymbols.entityColumnNames(resolveContext(enclosingTag)),
                    prefix, result);
        } else if (attributeName.equals("jdbcType")) {
            emitAll(MyBatisXmlCompletionData.JDBC_TYPE_VALUES, prefix, result);
        } else if (attributeName.equals("mode")) {
            emitAll(MyBatisXmlCompletionData.PARAM_MODE_VALUES, prefix, result);
        } else if (attributeName.equals("statementType")) {
            emitAll(MyBatisXmlCompletionData.STATEMENT_TYPE_VALUES, prefix, result);
        } else if (attributeName.equals("resultSetType")) {
            emitAll(MyBatisXmlCompletionData.RESULT_SET_TYPE_VALUES, prefix, result);
        } else if (attributeName.equals("fetchType")) {
            emitAll(MyBatisXmlCompletionData.FETCH_TYPE_VALUES, prefix, result);
        } else if (attributeName.equals("order") && tagName.equals("selectKey")) {
            emitAll(MyBatisXmlCompletionData.ORDER_VALUES, prefix, result);
        } else if (MyBatisXmlCompletionData.BOOLEAN_ATTRIBUTES.contains(attributeName)) {
            emitAll(MyBatisXmlCompletionData.BOOLEAN_VALUES, prefix, result);
        } else if (attributeName.equals("select")) {
            emitAll(XmlCompletionSymbols.localStatementIds(enclosingTag), prefix, result);
        }
        // timeout/fetchSize/numericScale/resultSets/scale/databaseId/lang/name(bind)/… → 无合理候选，不产
    }

    /** 值候选统一出口：非空白 + prefixMatches 过滤后以 literalValue 形态addElement。 */
    private void emitAll(List<String> values, PrefixMatcher prefix, CompletionResultSet result) {
        for (String value : values) {
            if (!value.isBlank() && prefix.prefixMatches(value)) {
                result.addElement(SourceSqlSuggestionFactory.literalValue(value));
            }
        }
    }

    /**
     * resultMap 上下文（{@code result}/{@code id} 之外）的列候选：解析引用该 resultMap 的 {@code <select>}
     * 投影列（T-XmlGap W5-10 的同一来源）；非 resultMap 上下文 → null（调用方回退实体列）。
     */
    @Nullable
    private List<String> resultMapReferencedColumns(XmlTag ownerTag) {
        XmlTag resultMapTag = ancestorResultMapTag(ownerTag);
        if (resultMapTag == null) return null;
        String id = resultMapTag.getAttributeValue("id");
        if (id == null || id.isBlank()) return null;
        if (!(ownerTag.getContainingFile() instanceof XmlFile)) return null;
        return ResultMapColumnCompletionMemo.columnsForResultMap((XmlFile) ownerTag.getContainingFile(), id);
    }

    // ------------------------------------------------------------------
    // c. `#{…}` 结构属性与参数名（T-XmlGap W4-1）
    // ------------------------------------------------------------------

    /** {@code #{…,} 逗号后：{@code key=} 形态给对应枚举值；否则给 7 项属性键（{@code key=} 形态，便于直接续敲）。 */
    private void addParamStructureCompletions(String inside, CompletionResultSet result) {
        PrefixMatcher prefix = result.getPrefixMatcher();
        String afterComma = inside.substring(inside.lastIndexOf(',') + 1);
        int eqIndex = afterComma.indexOf('=');
        if (eqIndex >= 0) {
            String key = afterComma.substring(0, eqIndex).trim();
            for (String value : MyBatisXmlCompletionData.paramAttributeValues(key)) {
                if (prefix.prefixMatches(value)) {
                    result.addElement(SourceSqlSuggestionFactory.literalValue(value));
                }
            }
        } else {
            for (String hint : MyBatisXmlCompletionData.paramAttributeKeyHints()) {
                if (prefix.prefixMatches(hint)) {
                    result.addElement(SourceSqlSuggestionFactory.literalValue(hint));
                }
            }
        }
    }

    /**
     * {@code #{…} 无逗号：参数名（@Param/形参/paramN）+ OGNL 结构性变量（bind / foreach item·index）；
     * 第二段（{@code x.}）时给 foreach item 元素字段或主实体字段名。
     */
    private void addHashParamCompletions(
            String inside, XmlTag enclosingTag, PsiElement caretElement, CompletionResultSet result) {
        PrefixMatcher prefix = result.getPrefixMatcher();
        SourceSqlContext context = resolveContext(enclosingTag);
        boolean secondSegment = SourceSqlSuggestionFactory.OGNL_PREFIX_PATTERN.matcher(inside).find();
        if (secondSegment) {
            String owner = inside;
            int dot = owner.lastIndexOf('.');
            if (dot >= 0) owner = owner.substring(0, dot);
            int comma = owner.lastIndexOf(',');
            if (comma >= 0) owner = owner.substring(comma + 1);
            owner = owner.trim();
            List<String> itemFields = XmlCompletionSymbols.foreachItemFields(enclosingTag, caretElement, owner);
            List<String> fields = !itemFields.isEmpty() ? itemFields : mainEntityFieldNames(context);
            for (String name : fields) {
                if (prefix.prefixMatches(name)) result.addElement(SourceSqlSuggestionFactory.param(name));
            }
            return;
        }
        for (String name : context.getParamNames()) {
            if (prefix.prefixMatches(name)) result.addElement(SourceSqlSuggestionFactory.param(name));
        }
        for (String name : OgnlVariableSources.collect(caretElement, enclosingTag, enclosingTag.getProject(), false)) {
            if (prefix.prefixMatches(name)) result.addElement(SourceSqlSuggestionFactory.param(name));
        }
    }

    // ------------------------------------------------------------------
    // d. OGNL（T-XmlGap W5-4/5/6）
    // ------------------------------------------------------------------

    /**
     * OGNL 值内补全：方法参数名 + 主实体 Java 字段名（fieldName）+ <b>变量来源</b>
     * （{@code <bind>} / foreach item·index / include property / {@code <properties>} key /
     * {@code _databaseId}·{@code _parameter}）+ <b>智能片段</b>（按类型）+ <b>运算符</b> + {@code @类@静态成员}。
     * 光标前有 {@code param.} 前缀时只补第二段（既有语义保留）。
     *
     * <p>移植注记：master 侧具名实参（{@code collectionAliases = ...}/{@code includeConfigProperties = ...}）
     * → Java 位置实参（k2j 惯例）。</p>
     */
    private void addOgnlCompletions(
            XmlTag enclosingTag,
            PsiElement caretElement,
            String beforeCaret,
            CompletionResultSet result,
            boolean collectionAliases,
            boolean includeConfigProperties) {
        PrefixMatcher prefix = result.getPrefixMatcher();
        SourceSqlContext context = resolveContext(enclosingTag);
        List<String> fieldNames = mainEntityFieldNames(context);
        boolean secondSegment = SourceSqlSuggestionFactory.OGNL_PREFIX_PATTERN.matcher(beforeCaret).find();
        if (secondSegment) {
            for (String name : fieldNames) {
                if (prefix.prefixMatches(name)) result.addElement(SourceSqlSuggestionFactory.param(name));
            }
        } else {
            for (String name : context.getParamNames()) {
                if (prefix.prefixMatches(name)) result.addElement(SourceSqlSuggestionFactory.param(name));
            }
            for (String name : fieldNames) {
                if (prefix.prefixMatches(name)) result.addElement(SourceSqlSuggestionFactory.param(name));
            }
            for (String name : OgnlVariableSources.collect(caretElement, enclosingTag, enclosingTag.getProject(), includeConfigProperties)) {
                if (prefix.prefixMatches(name)) result.addElement(SourceSqlSuggestionFactory.param(name));
            }
            if (collectionAliases) {
                for (String name : COLLECTION_ALIAS_NAMES) {
                    if (prefix.prefixMatches(name)) result.addElement(SourceSqlSuggestionFactory.param(name));
                }
            }
        }
        // W5-5 智能片段（按可推断类型；已输入变量名 token 为空则不产）
        Matcher tokenM = SourceSqlSuggestionFactory.OGNL_TOKEN_AT_CARET.matcher(beforeCaret);
        String variableToken = tokenM.find() ? tokenM.group() : "";
        if (!variableToken.isEmpty() && !variableToken.endsWith(".")) {
            ParamKind kind = variableKind(enclosingTag, variableToken);
            for (String snippet : MyBatisXmlCompletionData.ognlSnippets(variableToken, kind)) {
                if (prefix.prefixMatches(snippet)) {
                    result.addElement(SourceSqlSuggestionFactory.ognlSnippet(snippet, variableToken));
                }
            }
        }
        // W5-6 运算符
        for (String op : MyBatisXmlCompletionData.OGNL_OPERATORS) {
            if (prefix.prefixMatches(op)) result.addElement(SourceSqlSuggestionFactory.param(op));
        }
        // W5-6 `@类@静态成员`
        addAtMemberCompletions(enclosingTag.getProject(), beforeCaret, result);
    }

    /** {@code @类@静态成员}（W5-6）：能解析则给静态成员，解析不到或代价大只给语法骨架（不做无界搜索）。 */
    private void addAtMemberCompletions(Project project, String beforeCaret, CompletionResultSet result) {
        Matcher atM = SourceSqlSuggestionFactory.OGNL_AT_TOKEN_AT_CARET.matcher(beforeCaret);
        String atToken = atM.find() ? atM.group() : null;
        if (atToken == null) return;
        PrefixMatcher prefix = result.getPrefixMatcher();
        Matcher closedM = AT_CLOSED.matcher(atToken);
        if (closedM.find()) {
            String className = closedM.group(1);
            PsiClass clazz = XmlCompletionSymbols.resolveClass(project, className);
            if (clazz != null) {
                for (String member : staticMembersOf(clazz)) {
                    String text = "@" + className + "@" + member;
                    if (prefix.prefixMatches(text)) result.addElement(SourceSqlSuggestionFactory.param(text));
                }
            } else {
                // 类不可解析 → 只给语法骨架（不做无界类搜索）
                String skeleton = atToken + "成员";
                if (prefix.prefixMatches(skeleton)) result.addElement(SourceSqlSuggestionFactory.param(skeleton));
            }
        } else {
            // 只敲了 `@…`：给语法骨架
            String skeleton = "@类@静态成员";
            if (prefix.prefixMatches(skeleton)) result.addElement(SourceSqlSuggestionFactory.param(skeleton));
        }
    }

    /** PsiClass 的静态方法与静态字段名（有界：单类成员）。 */
    private List<String> staticMembersOf(PsiClass clazz) {
        try {
            LinkedHashSet<String> names = new LinkedHashSet<>();
            for (com.intellij.psi.PsiMethod method : clazz.getMethods()) {
                if (method.hasModifierProperty(PsiModifier.STATIC) && method.getName() != null) {
                    names.add(method.getName());
                }
            }
            for (com.intellij.psi.PsiField field : clazz.getFields()) {
                if (field.hasModifierProperty(PsiModifier.STATIC) && field.getName() != null) {
                    names.add(field.getName());
                }
            }
            return List.copyOf(names);
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** 变量 token → 类型类别（XmlParamTypeResolver 推断；推不出 → null → 通用片段）。 */
    @Nullable
    private ParamKind variableKind(XmlTag enclosingTag, String token) {
        try {
            Project project = enclosingTag.getProject();
            String namespace = XmlCompletionSymbols.mapperNamespace(enclosingTag);
            if (namespace == null) return null;
            String statementId = enclosingTag.getAttributeValue("id");
            if (statementId == null) return null;
            ParamTypeInfo info = XmlParamTypeResolver.INSTANCE
                    .resolveTypes(project, namespace, statementId).get(token);
            return info != null ? info.kind() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 主实体 Java 字段名（OGNL 用 fieldName）。 */
    private List<String> mainEntityFieldNames(SourceSqlContext context) {
        return XmlCompletionSymbols.entityFieldNames(context);
    }

    // ------------------------------------------------------------------
    // e/f. select 投影位与反引号候选（T-XmlGap W5-7/8）
    // ------------------------------------------------------------------

    /** select 投影位候选：语句 {@code resultType} 类字段（解析不到回退主实体字段名）。 */
    private List<String> projectionProperties(XmlTag enclosingTag, SourceSqlContext context) {
        if (!enclosingTag.getName().equals("select")) return List.of();
        String resultType = enclosingTag.getAttributeValue("resultType");
        if (resultType == null || resultType.isBlank()) return List.of();
        List<String> fields = XmlCompletionSymbols.fieldsOfClass(enclosingTag.getProject(), resultType);
        return !fields.isEmpty() ? fields : mainEntityFieldNames(context);
    }

    /** 反引号内候选：本文件全部 {@code <resultMap>} 的 {@code column} + {@code property} 属性值（W5-8）。 */
    private List<String> quotedIdentifierCandidates(XmlTag enclosingTag) {
        try {
            XmlTag root = rootTag(enclosingTag);
            LinkedHashSet<String> out = new LinkedHashSet<>();
            for (XmlTag resultMap : root.findSubTags("resultMap")) {
                for (XmlTag child : PsiTreeUtil.findChildrenOfType(resultMap, XmlTag.class)) {
                    if (!child.getName().equals("result") && !child.getName().equals("id")) continue;
                    String column = child.getAttributeValue("column");
                    if (column != null && !column.isBlank()) out.add(column);
                    String property = child.getAttributeValue("property");
                    if (property != null && !property.isBlank()) out.add(property);
                }
            }
            return List.copyOf(out);
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** 补全上下文解析（异常 → 空上下文，只补关键字）。 */
    private SourceSqlContext resolveContext(XmlTag enclosingTag) {
        try {
            return XmlSqlCompletionContextResolver.INSTANCE.resolveXml(enclosingTag.getProject(), enclosingTag);
        } catch (Throwable e) {
            return EMPTY_SOURCE_SQL_CONTEXT;
        }
    }

    // ------------------------------------------------------------------
    // 守卫
    // ------------------------------------------------------------------

    /**
     * 祖先链上<b>最近的补全宿主标签</b>：语句标签（select/insert/update/delete）、{@code <sql>} 片段、
     * {@code <resultMap>}、{@code <parameterMap>}。
     *
     * <p>T-XmlGap 扩展：原仅认语句标签（{@code <sql>}/{@code <resultMap>} 内零补全），现纳入——
     * {@code <sql>} 片段是 include 的复用体，含 SQL 与 {@code ${}}（W5-4 include property 可见域）；
     * {@code <resultMap>}/{@code <parameterMap>} 的属性值（property/jdbcType/javaType/typeHandler/column…，
     * W4-2 分流目标）都在其中，原守卫下不可达。从 position 向上爬（strict=false 兼容 position 自身即
     * XmlTag 的补全复制文件形态），跳过中间动态标签——纯语法判定，零 resolve。</p>
     */
    private XmlTag findEnclosingStatementTag(PsiElement position) {
        PsiElement element = position;
        while (element != null) {
            if (element instanceof XmlTag) {
                String name = ((XmlTag) element).getName();
                if (XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS.contains(name)
                        || name.equals("sql") || name.equals("resultMap") || name.equals("parameterMap")) {
                    return (XmlTag) element;
                }
            }
            element = element.getParent();
        }
        return null;
    }

    /** 最近的 {@code <resultMap>} 祖先标签（result/id 等属性值分流用）。 */
    @Nullable
    private XmlTag ancestorResultMapTag(XmlTag tag) {
        XmlTag cur = tag;
        while (cur != null) {
            if (cur.getName().equals("resultMap")) return cur;
            cur = cur.getParentTag();
        }
        return null;
    }

    /** 沿 parentTag 链取根标签（{@code <mapper>} 判定用）。 */
    private XmlTag rootTag(XmlTag tag) {
        XmlTag cur = tag;
        while (cur.getParentTag() != null) cur = cur.getParentTag();
        return cur;
    }

    // ------------------------------------------------------------------
    // 静态数据
    // ------------------------------------------------------------------

    /**
     * foreach `collection` 属性专属快捷名（T-FCA-03）：MyBatis 把「单集合参数且无 {@code @Param}」包装成
     * {@code ParamMap} 后的别名键（{@code list} / {@code collection} / {@code array}）。只对 {@code collection} 属性开放。
     */
    private static final List<String> COLLECTION_ALIAS_NAMES = List.of("list", "collection", "array");

    /** resolve 失败降级上下文（只补关键字/动态标签，不阻塞补全）。 */
    private static final SourceSqlContext EMPTY_SOURCE_SQL_CONTEXT = new SourceSqlContext(null, List.of(), List.of());

    /** {@code @…@…}（已闭合第二段）token。 */
    private static final Pattern AT_CLOSED = Pattern.compile("^@([\\w.$]+)@([\\w]*)$");
}
