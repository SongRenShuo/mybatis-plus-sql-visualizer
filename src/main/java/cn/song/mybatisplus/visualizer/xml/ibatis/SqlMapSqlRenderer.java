// T-SyncK2JMaster178 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/xml/ibatis/SqlMapSqlRenderer.kt 移植
package cn.song.mybatisplus.visualizer.xml.ibatis;

import cn.song.mybatisplus.visualizer.sql.PlaceholderPositionsKt;
import cn.song.mybatisplus.visualizer.util.FatalErrors;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisParamMapping;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisRenderResult;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisSelectKey;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisSqlNode;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisStatement;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisStatementKind;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisTagNode;
import cn.song.mybatisplus.visualizer.xml.ibatis.IbatisModel.IbatisTextNode;
import cn.song.mybatisplus.visualizer.xml.ibatis.SqlMapPrependMarker.IbatisIterateContext;
import cn.song.mybatisplus.visualizer.xml.ibatis.SqlMapPrependMarker.IbatisTagContext;
import cn.song.mybatisplus.visualizer.xml.ibatis.SqlMapTokenParser.DollarOutcome;
import kotlin.jvm.functions.Function1;

import java.util.ArrayList;
import java.util.List;

/**
 * iBatis 2.x SQL 渲染器（T-Ibatis2Support 券 B 主体）。
 *
 * <p>逐行复刻 {@code XMLSqlSource} + {@code MappedStatementConfig.setSqlForStatement} +
 * {@code DynamicSql.process} 的执行链：静态语句走「配置期一次解析」，动态语句走「每次执行重跑标签树」。
 * 两条链的 {@code #} 处理时机不同，且<b>声明了 {@code parameterMap} 的守卫只对非动态语句生效</b>（见下）。</p>
 *
 * <h2>三条不许「顺手改好」的硬口径</h2>
 * <ol>
 * <li><b>逐字拼接、绝不补空格</b>：{@code open}/{@code prepend}/{@code conjunction}/{@code close} 全部原样
 * {@code insert}，所以真实产物会出现 {@code ... ANDORG_NAME LIKE ...} 这种粘连——这是引擎的输出，
 * 必须原样复现（渲染结果<b>禁止美化重排</b>，与既有 XML 链的输出习惯相反）；</li>
 * <li><b>{@code $} 永远在动态标签求值之后替换</b>（{@code DynamicSql.process} 末尾才 new {@code SimpleDynamicSql}）；</li>
 * <li><b>{@code parameterMap} 守卫只作用于非动态语句</b>：{@code MappedStatementConfig.setSqlForStatement} 里
 * {@code sql instanceof DynamicSql} 直接 {@code setSql(sql)}，根本不会进 {@code applyInlineParameterMap}——
 * 即「有动态标签 + 声明 parameterMap」的语句，{@code #} <b>仍然</b>转 {@code ?}。</li>
 * </ol>
 *
 * <h2>静态语句路径的空白口径（两条分支不同，实测自 {@code SqlText.setText} 与 {@code StaticSql} 构造器）</h2>
 * <ul>
 * <li>{@code parameterMap == null}：文本经 {@code InlineParameterMapParser} → {@code SqlText.setText}，
 * {@code \r} {@code \n} {@code \t} <b>全部</b>换空格；</li>
 * <li>{@code parameterMap != null}：文本直接进 {@code StaticSql}，只把 {@code \r} {@code \n} 换空格，
 * <b>制表符保留</b>。</li>
 * </ul>
 *
 * <h2>已知偏差（未与 oracle 对齐的形态；对照集 {@code SqlMapFidelityTest} 覆盖域之外）</h2>
 *
 * <h3>A. 真正语义不同的形态（引擎成功、本链不同）——只有一条</h3>
 * <ul>
 * <li><b>{@code ${...}} 属性占位替换</b>：引擎在配置期用 {@code NodeletUtils.parsePropertyTokens(data, globalProps)}
 * 把 {@code ${key}} 换成全局属性值，取不到时退化为 {@code key} 本身（例如 {@code ${driver}} → {@code driver}）；
 * 本链<b>不做该替换</b>，按原文保留并在解析期给告警。最小复现：
 * {@code <select id="s">SELECT * FROM ${tableName}</select>} → 引擎 = {@code SELECT * FROM tableName}
 * （无 {@code <properties>} 时），本链 = {@code SELECT * FROM ${tableName}}。
 * 原因：本链是单文件解析器，不读 {@code sqlMapConfig} 的 {@code <properties>}，无全局属性表可用。</li>
 * </ul>
 *
 * <h3>B. 引擎硬失败的形态（无 oracle 读数可比，本链按券 B 口径降级）</h3>
 * <p>以下形态引擎抛异常、整篇加载失败，逐字节对照无定义，故本链统一「原文保留 + warning」：
 * 未知标签（引擎 {@code SqlTagHandlerFactory} 返回 null 后<b>静默丢弃整个子树</b>）；未闭合 {@code #}/{@code $}；
 * 内联参数格式错；{@code compareValues} 换算失败；{@code <include refid>} 找不到片段；
 * {@code parameterMap} 属性指向不存在的图；{@code <iterate property>} 取到的不是集合/数组/迭代器；
 * {@code addIndex} 的 {@code charAt} 越界。</p>
 *
 * <p>k2j 承载形态：master 侧本文件的 {@code IbatisRenderFailureKind} 枚举、{@code IbatisUnrenderedNode}
 * data class、{@code IbatisIterateNotCollectionException} 异常类聚合落为本类的嵌套类型。</p>
 */
public final class SqlMapSqlRenderer {

    /** 引擎 {@code SqlTagHandler.INCLUDE_BODY}。 */
    private static final int INCLUDE_BODY = 0;

    /** 引擎 {@code SqlTagHandler.SKIP_BODY}。 */
    private static final int SKIP_BODY = 1;

    /** 引擎 {@code SqlTagHandler.REPEAT_BODY}。 */
    private static final int REPEAT_BODY = 2;

    private static final String RFP_ITERATE = "iterate";

    private final SqlMapTokenParser tokenParser = new SqlMapTokenParser();

    public SqlMapSqlRenderer() {
    }

    /**
     * 渲染降级的失败原因分类（T-Ibatis2Gap 券 D）。
     *
     * <p><b>没有 UNKNOWN_TAG</b>：未知标签在解析期就被 {@link SqlMapScriptBuilder} 降级成文本节点收下
     * （「原文保留 + 告警」），根本到不了渲染抛错点；给它留档位只会造出一个永远走不到的分支
     * （{@code convention.EnumReachabilityGuardTest} 的零产出守卫会红）。</p>
     */
    public enum IbatisRenderFailureKind {
        /** {@code <iterate>} 的 property 取值不是集合/数组/迭代器（引擎 {@code SqlMapException}；无参基线下这是「待填值」态，不是错误）。 */
        ITERATE_NOT_COLLECTION,

        /** 引擎同样硬失败的渲染期错误（如 iterate 迭代上下文缺失等内部不变量被破坏）。 */
        RENDER_ERROR,

        /** 其余意外异常（渲染器外部的解析/配置问题）。 */
        PARSE_ERROR,
    }

    /**
     * 券 D：降级时「哪个顶层节点之后开始未渲染」的结构化事实。
     *
     * <p>位置口径：解析器（DOM）没有保留原文行号/偏移，故只给<b>失败节点在 {@link IbatisStatement#body()}
     * 顶层的位置（0 基）</b>——即「第 N 个顶层节点（含其后全部内容）未渲染」。渲染到此节点前已写入的
     * 输出与既有回退拼装（顶层文本节点）口径一致。</p>
     */
    public record IbatisUnrenderedNode(
            /** 失败的顶层标签名；顶层文本节点抛错时为 null（文本节点路径 token 解析只告警不抛，实践中不会出现）。 */
            String tagName,
            /** 失败节点在 {@link IbatisStatement#body()} 顶层的位置（0 基）。 */
            int topNodeIndex,
            /** 失败原因分类（UI 分档显示：ITERATE_NOT_COLLECTION = 待填值，不是错误）。 */
            IbatisRenderFailureKind reason,
            /** 失败涉及的属性名（来自抛点：{@code <iterate property="ids">} 的 {@code ids}）——供 UI 提示「该语句需要此参数」。 */
            String property,
            /** 引擎/渲染器的原始错误消息（可回溯）。 */
            String message
    ) {

        /** master 侧默认参量便捷构造器（{@code property = null}、{@code message = null}）。 */
        public IbatisUnrenderedNode(String tagName, int topNodeIndex, IbatisRenderFailureKind reason) {
            this(tagName, topNodeIndex, reason, null, null);
        }
    }

    /**
     * 券 D：{@code <iterate>} 取值非集合的分类载体。
     *
     * <p>抛点在 {@link SqlMapSqlRenderer#startIterate}——那里已知标签与 property，包一层才能把
     * 「取值不是集合」升级成可分类（ITERATE_NOT_COLLECTION）与可出分档文案的形态；
     * 上层（{@code renderBody} 的顶层回退）据此填 {@link IbatisUnrenderedNode#reason()} /
     * {@link IbatisUnrenderedNode#property()}。</p>
     *
     * <p>直接继承 {@code RuntimeException} 而不是 {@code IbatisRenderException}：后者是 final 类；
     * 分类走 {@link #classifyFailure} 的 {@code instanceof} 判定型分支，不依赖类型层级。</p>
     */
    public static final class IbatisIterateNotCollectionException extends RuntimeException {

        /** {@code <iterate property="...">} 的 property 原值（可为 null——无 property 时取参数对象本身）。 */
        private final String property;

        public IbatisIterateNotCollectionException(String property, String message) {
            super(message);
            this.property = property;
        }

        public String getProperty() {
            return property;
        }
    }

    /**
     * 渲染一条语句。
     *
     * @param params 参数对象（{@code Map} / JavaBean / 标量 / null）。IDE 侧通常传 null（等价于引擎的
     *               「无参基线渲染」，此时 {@code <isNull>}/{@code <isEmpty>}/{@code <isNotParameterPresent>} 分支成立）。
     */
    public IbatisRenderResult render(IbatisStatement statement, Object params) {
        List<String> warnings = new ArrayList<>(statement.warnings());
        Function1<String, kotlin.Unit> warn = message -> { warnings.add("语句 [" + statement.id() + "]：" + message); return kotlin.Unit.INSTANCE; };
        List<IbatisParamMapping> mapped = new ArrayList<>();
        // 券 D：降级事实收集器——renderBody 抛错时由顶层节点循环填入，回退拼装本身不变
        List<IbatisUnrenderedNode> unrendered = new ArrayList<>();
        // 券 C：parameterMap 声明了、但语句体参数位点没消费到的映射
        List<IbatisParamMapping> unusedDeclared = new ArrayList<>();

        IbatisRenderFailureKind[] failureKind = new IbatisRenderFailureKind[] { null };
        String body;
        try {
            body = renderBody(statement, params, mapped, warn, unrendered, unusedDeclared);
        } catch (Throwable error) {
            IbatisRenderFailureKind kind = classifyFailure(error);
            failureKind[0] = kind;
            // 券 D：文案按失败形态分档——禁止再产出与事实不符的「按原文保留 # 直替」
            warn.invoke(degradedWarning(kind, unrendered.isEmpty() ? null : unrendered.get(0), error));
            StringBuilder fallback = new StringBuilder();
            for (IbatisSqlNode node : statement.body()) {
                if (node instanceof IbatisTextNode textNode) {
                    fallback.append(textNode.getText());
                }
            }
            body = fallback.toString();
        }

        DollarOutcome dollars = tokenParser.processDollarSubstitution(body, params);
        for (String message : dollars.warnings()) {
            warn.invoke(message);
        }

        List<String> paramNames = new ArrayList<>();
        List<String> paramJdbcTypes = new ArrayList<>();
        List<String> paramNullValues = new ArrayList<>();
        List<Boolean> nullBindFlags = new ArrayList<>();
        for (IbatisParamMapping mapping : mapped) {
            paramNames.add(mapping.propertyName());
            paramJdbcTypes.add(mapping.jdbcTypeName());
            paramNullValues.add(mapping.nullValue());
            nullBindFlags.add(IbatisProbe.matchesNullValue(
                    params != null ? IbatisProbe.getObject(params, mapping.propertyName()) : null,
                    mapping.nullValue()
            ));
        }

        return new IbatisRenderResult(
                dollars.text(),
                compact(dollars.text()),
                paramNames,
                paramJdbcTypes,
                paramNullValues,
                nullBindFlags,
                warnings,
                dollars.siteCount(),
                dollars.renderedValues(),
                List.of(statement.id()),
                statement.usedSqlFragmentIds(),
                failureKind[0] != null,
                List.copyOf(unrendered),
                List.copyOf(unusedDeclared)
        );
    }

    /** master 侧默认参量便捷重载（{@code params = null}——无参基线渲染）。 */
    public IbatisRenderResult render(IbatisStatement statement) {
        return render(statement, null);
    }

    /**
     * 渲染 {@code <selectKey>} 生成的语句（引擎里它是独立 {@code MappedStatement}，id = {@code <insertId>-SelectKey}）。
     */
    public IbatisRenderResult renderSelectKey(IbatisSelectKey selectKey, Object params) {
        IbatisStatement synthetic = new IbatisStatement(
                selectKey.id(),
                IbatisStatementKind.INSERT,
                "selectKey",
                selectKey.attributes(),
                null,
                false,
                selectKey.body(),
                null,
                List.of(),
                List.of(),
                selectKey.warnings()
        );
        return render(synthetic, params);
    }

    /** master 侧默认参量便捷重载（{@code params = null}）。 */
    public IbatisRenderResult renderSelectKey(IbatisSelectKey selectKey) {
        return renderSelectKey(selectKey, null);
    }

    // ------------------------------------------------------------------ 两条路径

    private String renderBody(
            IbatisStatement statement,
            Object params,
            List<IbatisParamMapping> mapped,
            Function1<String, kotlin.Unit> warn,
            List<IbatisUnrenderedNode> unrendered,
            List<IbatisParamMapping> unusedDeclared
    ) {
        if (!statement.isDynamic()) {
            return renderStatic(statement, mapped, warn, unusedDeclared);
        }
        IbatisTagContext ctx = new IbatisTagContext();
        // 顶层片段写进本方法的缓冲（引擎里就是 SqlTagContext 自己的 StringWriter）；
        // 嵌套标签各自用独立缓冲，处理完 doEndFragment/doPrepend 后才条件性地写回父缓冲。
        StringBuilder out = new StringBuilder();
        // 券 D：按顶层节点逐个求值——任一节点抛错时，把「失败的是哪个顶层节点、为什么」记成
        // 结构化事实再原样上抛（回退拼装仍由 render() 的 catch 负责，这里不改产出逻辑）。
        for (int index = 0; index < statement.body().size(); index++) {
            IbatisSqlNode child = statement.body().get(index);
            try {
                if (child instanceof IbatisTextNode textNode) {
                    appendText(ctx, textNode, out);
                } else if (child instanceof IbatisTagNode tagNode) {
                    processTag(ctx, params, tagNode, out);
                }
            } catch (Throwable e) {
                // 禁用 API 守卫口径（BannedApiScanRuleTest）：fatal 一律立即透传，不落「未渲染节点」事实——
                // OOM/栈溢出不是某个标签渲染失败，把它记成渲染事实既误导又可能二次OOM。
                if (FatalErrors.isFatal(e)) {
                    throw e;
                }
                unrendered.add(new IbatisUnrenderedNode(
                        child instanceof IbatisTagNode tagNode ? tagNode.getKind().getTagName() : null,
                        index,
                        classifyFailure(e),
                        e instanceof IbatisIterateNotCollectionException iterateError ? iterateError.getProperty() : null,
                        e.getMessage()
                ));
                throw e;
            }
        }
        mapped.addAll(ctx.getParameterMappings());
        for (String message : new ArrayList<>(ctx.getWarnings())) {
            warn.invoke(message);
        }
        return out.toString();
    }

    /** 非动态语句：{@code XMLSqlSource.getSql()} → {@code RawSql} → {@code applyInlineParameterMap} 的口径。 */
    private String renderStatic(
            IbatisStatement statement,
            List<IbatisParamMapping> mapped,
            Function1<String, kotlin.Unit> warn,
            List<IbatisParamMapping> unusedDeclared
    ) {
        StringBuilder rawBuilder = new StringBuilder();
        for (IbatisSqlNode node : statement.body()) {
            if (node instanceof IbatisTextNode textNode) {
                rawBuilder.append(textNode.getRawText());
            }
        }
        String raw = rawBuilder.toString();
        IbatisModel.IbatisParameterMap declared = statement.parameterMap();
        if (declared != null) {
            // 引擎的 if (parameterMap == null) 守卫：原样保留 `#`，只做 \r\n 归一（制表符保留）
            //
            // 券 C（参数名按位对齐）：这类语句体的绑定语义是**按位置**把 parameterMap 的映射依序
            // 绑到语句体的参数位点（手写 `?`，或 `#x#` 原样保留形态下的内联参数对）上。故
            // paramNames 只取**前 N 个**声明映射（N = string-aware 位点数），剩余映射不丢——
            // 透出 unusedDeclaredParams 供 UI 提示「声明了但未消费」。位点数多于声明数时全部
            // 声明都被消费（与保真对照 oracle 一致：oracle 返回全部声明映射）。
            int siteCount = countParameterSites(raw);
            List<IbatisModel.IbatisParameterDef> declaredParameters = declared.parameters();
            int consumed = Math.min(siteCount, declaredParameters.size());
            for (int i = 0; i < consumed; i++) {
                IbatisModel.IbatisParameterDef parameter = declaredParameters.get(i);
                mapped.add(new IbatisParamMapping(
                        parameter.property(),
                        parameter.jdbcType(),
                        parameter.nullValue(),
                        parameter.javaType(),
                        parameter.mode()
                ));
            }
            for (int i = consumed; i < declaredParameters.size(); i++) {
                IbatisModel.IbatisParameterDef parameter = declaredParameters.get(i);
                unusedDeclared.add(new IbatisParamMapping(
                        parameter.property(),
                        parameter.jdbcType(),
                        parameter.nullValue(),
                        parameter.javaType(),
                        parameter.mode()
                ));
            }
            return raw.replace('\r', ' ').replace('\n', ' ');
        }
        SqlMapTokenParser.InlineParseOutcome parsed = tokenParser.parseInlineParameterMap(
                raw.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
        );
        for (String message : parsed.warnings()) {
            warn.invoke(message);
        }
        mapped.addAll(parsed.mappings());
        return parsed.text();
    }

    // ------------------------------------------------------------------ 标签树求值

    private void processBodyChildren(
            IbatisTagContext ctx,
            Object params,
            List<IbatisSqlNode> children,
            StringBuilder out
    ) {
        for (IbatisSqlNode child : children) {
            if (child instanceof IbatisTextNode textNode) {
                appendText(ctx, textNode, out);
            } else if (child instanceof IbatisTagNode tagNode) {
                processTag(ctx, params, tagNode, out);
            }
        }
    }

    private void appendText(IbatisTagContext ctx, IbatisTextNode child, StringBuilder out) {
        if (child.isWhitespace()) {
            out.append(child.getRenderText());
            return;
        }
        if (!child.isPostParseRequired()) {
            out.append(child.getRenderText());
            ctx.getParameterMappings().addAll(child.getMappings());
            return;
        }
        // iterate 内的文本：`#`/`$` 只存原文，每次迭代重算（引擎 postParseRequired 分支）
        String text = child.getText();
        IbatisIterateContext iterate = ctx.peekIterateContext();
        if (iterate != null && iterate.isAllowNext()) {
            iterate.next();
            iterate.setAllowNext(false);
            if (!iterate.hasNext()) {
                iterate.setFinal(true);
            }
        }
        if (iterate != null) {
            text = iteratePropertyReplace(text, iterate);
        }
        SqlMapTokenParser.InlineParseOutcome parsed = tokenParser.parseInlineParameterMap(text);
        ctx.getWarnings().addAll(parsed.warnings());
        out.append(parsed.text());
        ctx.getParameterMappings().addAll(parsed.mappings());
    }

    /**
     * 引擎 {@code DynamicSql.iteratePropertyReplace}：对 {@code #...#} / {@code $...$} 逐点做下标替换。
     *
     * <p><b>照抄它的两个怪异之处</b>：① 替换后循环仍用旧的 {@code startIndex}/{@code endIndex} 继续扫
     * （长度变化不重算）；② 替换的是 {@code #} 与 {@code $} 之间<b>整段文本</b>（不只是属性名），
     * 所以 {@code #x#} 里的 {@code x} 若无 {@code []} 则原样返回。</p>
     */
    private String iteratePropertyReplace(String text, IbatisIterateContext iterate) {
        StringBuilder buffer = new StringBuilder(text);
        String[] markers = {"#", "$"};
        for (String marker : markers) {
            int startIndex = 0;
            int endIndex = -1;
            while (startIndex > -1 && startIndex < buffer.length()) {
                startIndex = buffer.indexOf(marker, endIndex + 1);
                endIndex = buffer.indexOf(marker, startIndex + 1);
                if (startIndex > -1 && endIndex > -1) {
                    String replacement;
                    try {
                        replacement = iterate.addIndexToTagProperty(buffer.substring(startIndex + 1, endIndex));
                    } catch (Throwable t) {
                        return buffer.toString();
                    }
                    buffer.replace(startIndex + 1, endIndex, replacement);
                }
            }
        }
        return buffer.toString();
    }

    /** 引擎 {@code DynamicSql.processBodyChildren} 的 {@code child instanceof SqlTag} 分支（do-while 结构逐条照搬）。 */
    private void processTag(IbatisTagContext ctx, Object params, IbatisTagNode tag, StringBuilder out) {
        int response = INCLUDE_BODY;
        do {
            StringBuilder body = new StringBuilder();
            response = doStartFragment(ctx, params, tag);
            if (response != SKIP_BODY) {
                processBodyChildren(ctx, params, tag.getChildren(), body);
                response = doEndFragment(ctx, params, tag, body);
                doPrepend(ctx, params, tag, body);
                if (response != SKIP_BODY && body.length() > 0) {
                    out.append(body);
                }
            }
        } while (response == REPEAT_BODY);

        ctx.popRemoveFirstPrependMarker(tag);
        IbatisIterateContext iterate = ctx.peekIterateContext();
        if (iterate != null && iterate.getTag() == tag) {
            ctx.setAttribute(tag, null);
            ctx.popIterateContext();
        }
    }

    private int doStartFragment(IbatisTagContext ctx, Object params, IbatisTagNode tag) {
        if (tag.getKind() == IbatisModel.IbatisTagKind.DYNAMIC) {
            ctx.pushRemoveFirstPrependMarker(tag);
            return INCLUDE_BODY;
        }
        if (tag.getKind() == IbatisModel.IbatisTagKind.ITERATE) {
            return startIterate(ctx, params, tag);
        }
        // 引擎 ConditionalTagHandler.doStartFragment：先压 marker，再判条件
        ctx.pushRemoveFirstPrependMarker(tag);
        return conditionEvaluator(ctx).isCondition(tag.getKind(), ctx, tag, params) ? INCLUDE_BODY : SKIP_BODY;
    }

    /** 引擎 {@code IterateTagHandler.doStartFragment}（{@code prop} 会被改写为加下标形态，并用于 {@code setProperty}）。 */
    private int startIterate(IbatisTagContext ctx, Object params, IbatisTagNode tag) {
        IbatisIterateContext existing = ctx.getAttribute(tag);
        if (existing != null) {
            if (RFP_ITERATE.equals(tag.getRemoveFirstPrepend())) {
                ctx.reEnableRemoveFirstPrepend();
            }
            return existing.hasNext() ? INCLUDE_BODY : SKIP_BODY;
        }
        IbatisIterateContext parentIterate = ctx.peekIterateContext();
        ctx.pushRemoveFirstPrependMarker(tag);
        String prop = tag.getProperty();
        Object collection;
        if (prop != null && !prop.isEmpty()) {
            if (parentIterate != null && parentIterate.isAllowNext()) {
                parentIterate.next();
                parentIterate.setAllowNext(false);
                if (!parentIterate.hasNext()) {
                    parentIterate.setFinal(true);
                }
            }
            if (parentIterate != null) {
                prop = parentIterate.addIndexToTagProperty(prop);
            }
            collection = IbatisProbe.getObject(params, prop);
        } else {
            collection = params;
        }
        IbatisIterateContext iterate;
        try {
            iterate = new IbatisIterateContext(collection, tag, parentIterate);
        } catch (IbatisModel.IbatisRenderException e) {
            // 抛点已知标签与 property——包成可分类异常（券 D：ITERATE_NOT_COLLECTION 分档 + property 透出）
            throw new IbatisIterateNotCollectionException(tag.getProperty(),
                    e.getMessage() != null ? e.getMessage() : "iterate 取值不是集合");
        }
        iterate.setProperty(prop != null ? prop : "");
        ctx.setAttribute(tag, iterate);
        ctx.pushIterateContext(iterate);
        return iterate.hasNext() ? INCLUDE_BODY : SKIP_BODY;
    }

    private int doEndFragment(IbatisTagContext ctx, Object params, IbatisTagNode tag, StringBuilder body) {
        if (tag.getKind() == IbatisModel.IbatisTagKind.ITERATE) {
            return endIterate(ctx, tag, body);
        }
        if (tag.getKind() != IbatisModel.IbatisTagKind.DYNAMIC && tag.getKind() != IbatisModel.IbatisTagKind.ITERATE) {
            // 引擎 ConditionalTagHandler.doEndFragment：先推进 iterate，再走基类 close 逻辑
            IbatisIterateContext iterate = ctx.peekIterateContext();
            if (iterate != null && iterate.isAllowNext()) {
                iterate.next();
                iterate.setAllowNext(false);
                if (!iterate.hasNext()) {
                    iterate.setFinal(true);
                }
            }
        }
        return baseEndFragment(tag, body);
    }

    /** 引擎 {@code BaseTagHandler.doEndFragment}：非 iterate 标签的 {@code close} 在原体非空时<b>原样追加</b>。 */
    private int baseEndFragment(IbatisTagNode tag, StringBuilder body) {
        if (tag.hasClose() && tag.getKind() != IbatisModel.IbatisTagKind.ITERATE && hasContent(body)) {
            body.append(tag.getClose());
        }
        return INCLUDE_BODY;
    }

    /** 引擎 {@code IterateTagHandler.doEndFragment}。 */
    private int endIterate(IbatisTagContext ctx, IbatisTagNode tag, StringBuilder body) {
        IbatisIterateContext iterate = ctx.getAttribute(tag);
        if (iterate == null) {
            throw new IbatisModel.IbatisRenderException("iterate 迭代上下文缺失：property='" + tag.getProperty() + "'");
        }
        if (!iterate.hasNext() && !iterate.isFinal()) {
            return baseEndFragment(tag, body);
        }
        if (iterate.isAllowNext()) {
            iterate.next();
        }
        if (hasContent(body)) {
            if (iterate.isSomeSubElementsHaveContent()) {
                if (tag.hasConjunction()) {
                    body.insert(0, tag.getConjunction());
                }
            } else {
                iterate.setPrependEnabled(true);
                if (tag.hasOpen()) {
                    body.insert(0, tag.getOpen());
                }
            }
            iterate.setSomeSubElementsHaveContent(true);
        }
        if (iterate.isLast() && iterate.isSomeSubElementsHaveContent() && tag.hasClose()) {
            body.append(tag.getClose());
        }
        iterate.setAllowNext(true);
        return iterate.isFinal() ? baseEndFragment(tag, body) : REPEAT_BODY;
    }

    private void doPrepend(IbatisTagContext ctx, Object params, IbatisTagNode tag, StringBuilder body) {
        if (tag.getKind() == IbatisModel.IbatisTagKind.ITERATE) {
            IbatisIterateContext iterate = ctx.getAttribute(tag);
            if (iterate == null) {
                return;
            }
            if (iterate.isPrependEnabled()) {
                basePrepend(ctx, tag, body);
                iterate.setPrependEnabled(false);
            }
            return;
        }
        basePrepend(ctx, tag, body);
    }

    /**
     * 引擎 {@code BaseTagHandler.doPrepend}——<b>逐字 insert，不补空格</b>。
     *
     * <p>{@code open}/{@code close} 对 iterate 由 {@link #endIterate} 处理，故这里显式排除 iterate
     * （与引擎的 {@code !(handler instanceof IterateTagHandler)} 同口径）。</p>
     */
    private void basePrepend(IbatisTagContext ctx, IbatisTagNode tag, StringBuilder body) {
        if (tag.hasOpen() && tag.getKind() != IbatisModel.IbatisTagKind.ITERATE && hasContent(body)) {
            body.insert(0, tag.getOpen());
        }
        if (tag.hasPrepend() && hasContent(body)) {
            if (tag.getParent() != null && ctx.peekParentRemoveFirstPrepend()) {
                ctx.disableParentRemoveFirstPrepend();
            } else {
                body.insert(0, tag.getPrepend());
            }
        }
    }

    private SqlMapConditionEvaluator conditionEvaluator(IbatisTagContext ctx) {
        return new SqlMapConditionEvaluator(message -> { ctx.getWarnings().add(message); return kotlin.Unit.INSTANCE; });
    }

    // ------------------------------------------------------------------ 工具

    /** 券 D：抛错 → 失败原因分类（枚举三个常量的唯一产出点，{@code EnumReachabilityGuardTest} 口径）。 */
    private static IbatisRenderFailureKind classifyFailure(Throwable error) {
        if (error instanceof IbatisIterateNotCollectionException) {
            return IbatisRenderFailureKind.ITERATE_NOT_COLLECTION;
        }
        if (error instanceof IbatisModel.IbatisRenderException) {
            return IbatisRenderFailureKind.RENDER_ERROR;
        }
        return IbatisRenderFailureKind.PARSE_ERROR;
    }

    /** 券 D：按失败形态分档的降级告警文案（bundle 三键，中英双份；禁出现「按原文保留 # 直替」）。 */
    private static String degradedWarning(
            IbatisRenderFailureKind kind,
            IbatisUnrenderedNode firstUnrendered,
            Throwable error
    ) {
        if (kind == IbatisRenderFailureKind.ITERATE_NOT_COLLECTION) {
            String property = error instanceof IbatisIterateNotCollectionException iterateError
                    ? iterateError.getProperty()
                    : null;
            return MpSqlVisualizerBundle.message(
                    "ibatis.render.degrade.iterateNotCollection",
                    property != null ? property : ""
            );
        }
        String tag = firstUnrendered != null ? firstUnrendered.tagName() : null;
        if (tag != null) {
            return MpSqlVisualizerBundle.message("ibatis.render.degrade.abortedAtTag", tag);
        }
        return MpSqlVisualizerBundle.message("ibatis.render.degrade.abortedUnknown");
    }

    /**
     * 券 C：语句体的参数位点数 = string-aware {@code ?}（复用 {@code sql/PlaceholderPositions.kt} 的既有口径，
     * 字符串/注释里的 {@code ?} 不算）+ 内联 {@code #x#} 对数（{@code parameterMap} 守卫分支 {@code #} 原样保留，
     * 成对出现即一个参数位点；{@code ##} 转义字面量与字符串内的 {@code #} 不算）。
     */
    private static int countParameterSites(String text) {
        return PlaceholderPositionsKt.placeholderPositions(text).size() + inlineHashPairCount(text);
    }

    /** 内联 {@code #x#} 参数对计数（string-aware；{@code ##} 转义按两个字面量 {@code #} 跳过；未闭合残段不成对不计数）。 */
    private static int inlineHashPairCount(String text) {
        boolean inString = false;
        int hashCount = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                        i += 2;
                        continue;
                    }
                    inString = false;
                }
            } else {
                if (c == '\'') {
                    inString = true;
                } else if (c == '#') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '#') {
                        i += 2;
                        continue;
                    }
                    hashCount++;
                }
            }
            i++;
        }
        return hashCount / 2;
    }

    /** 引擎的 {@code bodyContent.toString().trim().length() > 0}——Java {@code trim()} 剔的是 {@code <= ' '}，非 {@code isWhitespace}。 */
    private static boolean hasContent(StringBuilder body) {
        return body.length() > 0 && !body.toString().trim().isEmpty();
    }

    /** 空白折叠（仅用于 {@code compactSql}；不改 {@link IbatisRenderResult#sql()} 的逐字节形态）。 */
    private static String compact(String sql) {
        String[] parts = sql.split("[ \r\n\t]");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(part);
        }
        return result.toString();
    }
}
