// T-SyncK2JMaster178 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/xml/ibatis/IbatisModel.kt 移植
package cn.song.mybatisplus.visualizer.xml.ibatis;

import java.util.List;

/**
 * iBatis 2.x 静态渲染链的数据模型（T-Ibatis2Support 券 B）。
 *
 * <p>本包<b>与 MyBatis 3 链完全解耦</b>：不 import {@code xml/} 下既有解析/渲染件（{@code Ognl} /
 * {@code XmlScriptBuilder} / {@code TokenParser} 均不适用——iBatis 2 无表达式语言、标签集不同、定界符是单字符
 * {@code #}/{@code $}），只复用 {@code xml/SecureXml}（全仓唯一 {@code DocumentBuilder} 工厂）与
 * {@code xml/ParamKind}（只读引用）。</p>
 *
 * <p>所有语义均来自 {@code org.apache.ibatis:ibatis-sqlmap:2.3.4.726} 的 jar 内源码逐行实测
 * （{@code -sources.jar} 202 个 {@code .java}），关键落点在各实现类 KDoc 里逐条标注。</p>
 *
 * <p><b>与本仓既有 XML 链相反的两条口径</b>（券 B 硬要求）：</p>
 * <ol>
 * <li>未知标签 / 解析失败 → <b>降级为「原文保留 + 收集 warning」</b>，不抛穿（对照 {@code xml/XmlScriptBuilder.kt:42}）；</li>
 * <li>{@code open}/{@code prepend}/{@code conjunction}/{@code close} <b>逐字拼接、绝不补空格</b>
 * （{@code ANDORG_NAME} 必须原样复现）。</li>
 * </ol>
 *
 * <p>k2j 承载形态：master 侧本文件聚合的 2 枚举 + 12 数据/异常类型在 Java 侧落为本类的嵌套类型
 * （枚举 / record / sealed interface / final class），访问器沿用 record 无 get 前缀口径。</p>
 */
public final class IbatisModel {

    private IbatisModel() {
    }

    /**
     * {@code <sqlMap>} 直接子元素里的 6 种可执行语句标签（{@code sql-map-2.dtd}）。
     *
     * <p>{@code insert}/{@code update}/{@code delete} 的属性集<b>没有</b>
     * {@code resultMap}/{@code resultClass}（DTD 实测）。{@code procedure} 额外少 {@code resultSetType}。
     * 三者差异对静态渲染无影响（只影响 resultMap 装配），故此处只保留种类用于 UI 展示与 DTD 校验提示。</p>
     */
    public enum IbatisStatementKind {

        SELECT("select"),
        INSERT("insert"),
        UPDATE("update"),
        DELETE("delete"),
        STATEMENT("statement"),
        PROCEDURE("procedure");

        private final String tagName;

        IbatisStatementKind(String tagName) {
            this.tagName = tagName;
        }

        public String getTagName() {
            return tagName;
        }

        /**
         * 全量常量表（顺序 = {@code sql-map-2.dtd} 声明顺序），也是 {@link #byTagName(String)} 的<b>唯一查表源</b>。
         *
         * <p>逐项写成限定形态 {@code IbatisStatementKind.X} 是 {@code convention/EnumReachabilityGuardTest}
         * 的硬要求：该守卫只统计 {@code EnumName.CONST} 限定形态的引用，隐式查表产出对它不可见，
         * 会被判「死分支 / 死常量」。本枚举的产出是真实存在的（查表即产出），故显式列出——
         * <b>不要退回 {@code values()} 形态</b>，否则门禁红。</p>
         */
        private static final List<IbatisStatementKind> ALL = List.of(
                IbatisStatementKind.SELECT,
                IbatisStatementKind.INSERT,
                IbatisStatementKind.UPDATE,
                IbatisStatementKind.DELETE,
                IbatisStatementKind.STATEMENT,
                IbatisStatementKind.PROCEDURE
        );

        /** 按标签名查表（未命中返回 {@code null}，对齐 Kotlin 可空返回）。 */
        public static IbatisStatementKind byTagName(String name) {
            IbatisStatementKind found = null;
            for (IbatisStatementKind kind : ALL) {
                if (kind.tagName.equals(name)) {
                    found = kind;
                    break;
                }
            }
            return found;
        }
    }

    /**
     * 动态标签处理器种类——逐项对应 iBatis {@code SqlTagHandlerFactory} 的 16 个注册项
     * （14 个条件标签 + {@code iterate} + {@code dynamic}）。
     *
     * <p>判定语义见 {@link SqlMapConditionEvaluator}；{@code dynamic} 只做 prepend 消费、
     * {@code iterate} 是唯一有状态的处理器。</p>
     */
    public enum IbatisTagKind {

        DYNAMIC("dynamic"),
        ITERATE("iterate"),
        IS_EMPTY("isEmpty"),
        IS_EQUAL("isEqual"),
        IS_GREATER_EQUAL("isGreaterEqual"),
        IS_GREATER_THAN("isGreaterThan"),
        IS_LESS_EQUAL("isLessEqual"),
        IS_LESS_THAN("isLessThan"),
        IS_NOT_EMPTY("isNotEmpty"),
        IS_NOT_EQUAL("isNotEqual"),
        IS_NOT_NULL("isNotNull"),
        IS_NOT_PARAMETER_PRESENT("isNotParameterPresent"),
        IS_NOT_PROPERTY_AVAILABLE("isNotPropertyAvailable"),
        IS_NULL("isNull"),
        IS_PARAMETER_PRESENT("isParameterPresent"),
        IS_PROPERTY_AVAILABLE("isPropertyAvailable");

        private final String tagName;

        IbatisTagKind(String tagName) {
            this.tagName = tagName;
        }

        public String getTagName() {
            return tagName;
        }

        /**
         * 全量常量表（顺序 = 引擎 {@code SqlTagHandlerFactory} 的 16 个注册项顺序），也是
         * {@link #byTagName(String)} 的<b>唯一查表源</b>。
         *
         * <p>逐项写成限定形态 {@code IbatisTagKind.X} 的原因同 {@link IbatisStatementKind#ALL}：守卫
         * {@code convention/EnumReachabilityGuardTest} 只统计限定形态引用，隐式查表产出对它不可见。
         * <b>不要退回 {@code values()} 形态</b>。</p>
         */
        private static final List<IbatisTagKind> ALL = List.of(
                IbatisTagKind.DYNAMIC,
                IbatisTagKind.ITERATE,
                IbatisTagKind.IS_EMPTY,
                IbatisTagKind.IS_EQUAL,
                IbatisTagKind.IS_GREATER_EQUAL,
                IbatisTagKind.IS_GREATER_THAN,
                IbatisTagKind.IS_LESS_EQUAL,
                IbatisTagKind.IS_LESS_THAN,
                IbatisTagKind.IS_NOT_EMPTY,
                IbatisTagKind.IS_NOT_EQUAL,
                IbatisTagKind.IS_NOT_NULL,
                IbatisTagKind.IS_NOT_PARAMETER_PRESENT,
                IbatisTagKind.IS_NOT_PROPERTY_AVAILABLE,
                IbatisTagKind.IS_NULL,
                IbatisTagKind.IS_PARAMETER_PRESENT,
                IbatisTagKind.IS_PROPERTY_AVAILABLE
        );

        /** 按标签名查表（未命中返回 {@code null}，对齐 Kotlin 可空返回）。 */
        public static IbatisTagKind byTagName(String name) {
            IbatisTagKind found = null;
            for (IbatisTagKind kind : ALL) {
                if (kind.tagName.equals(name)) {
                    found = kind;
                    break;
                }
            }
            return found;
        }
    }

    /**
     * {@code #...#} 内联参数解析结果（对应引擎 {@code ParameterMapping} 的 SQL 文本相关字段）。
     *
     * <p>只有 {@code propertyName} / {@code jdbcTypeName} / {@code nullValue} 影响面板可见面；
     * {@code javaType}/{@code mode}/{@code numericScale} 只影响 JDBC 绑定（{@code setParameters}），
     * 对 SQL 文本零影响——但保留下来供接线车道做类型提示。</p>
     */
    public record IbatisParamMapping(
            String propertyName,
            String jdbcTypeName,
            String nullValue,
            String javaTypeName,
            String mode,
            Integer numericScale
    ) {

        /** master 侧默认参量便捷构造器（{@code jdbcTypeName} 起全默认 {@code null}）。 */
        public IbatisParamMapping(String propertyName) {
            this(propertyName, null, null, null, null, null);
        }

        /** master 侧命名实参 {@code IbatisParamMapping(propertyName, jdbcTypeName)} 的便捷构造器。 */
        public IbatisParamMapping(String propertyName, String jdbcTypeName) {
            this(propertyName, jdbcTypeName, null, null, null, null);
        }
    }

    /** 一条 {@code #...#} 的解析产物：SQL 文本中的 {@code ?} 与紧随其后的原始文本段（{@code $} 替换后仍是字面量）。 */
    public record IbatisParsedText(String text, List<IbatisParamMapping> mappings) {
    }

    /** XML 脚本节点（iBatis 的动态树）。 */
    public sealed interface IbatisSqlNode
            permits IbatisTextNode, IbatisTagNode {
    }

    /**
     * 文本节点（{@code #PCDATA} / CDATA）。
     *
     * <p><b>空白归一化是引擎行为，不是我们的美化</b>：{@code SqlText.setText} 把 {@code \r}/{@code \n}/{@code \t}
     * 一律替换为空格（{@code \t} 只在 {@code parameterMap} 分支的静态语句里被保留——见
     * {@link SqlMapSqlRenderer} 的静态路径）。{@link #isWhitespace()} 与引擎同口径＝按 Java
     * {@code String.trim()}（≤ {@code ' '}）判空。</p>
     *
     * <p>{@code postParseRequired} 为 true 表示本节点的 {@code #}/{@code $} <b>不在配置期解析</b>，
     * 留给 iterate 每次迭代重算（引擎 {@code XMLSqlSource.parseDynamicTags} 的 {@code postParseRequired}
     * 形参 + {@code IterateTagHandler.isPostParseRequired}）。</p>
     */
    public static final class IbatisTextNode implements IbatisSqlNode {

        /** DOM 取到的原始文本（保留换行/制表符，静态路径的 {@code parameterMap} 分支要用）。 */
        private final String rawText;

        /**
         * {@code #}/{@code $} 是否不在配置期解析、留给 iterate 每次迭代重算
         * （引擎 {@code XMLSqlSource.parseDynamicTags} 形参 + {@code IterateTagHandler.isPostParseRequired}）。
         */
        private final boolean postParseRequired;

        /** 非 postParse 节点在<b>配置期</b>完成 {@code #} 解析后的文本（{@code ?} 形态）；postParse 节点为 null。 */
        private final String parsedText;

        /** 配置期解析出的内联参数（postParse 节点恒为空，运行时逐次重算）。 */
        private final List<IbatisParamMapping> mappings;

        /** 引擎 {@code SqlText.setText} 归一化后的文本（{@code \r} {@code \n} {@code \t} → 空格）。 */
        private final String text;

        /** 实际写入 SQL 的文本：非 postParse 用配置期解析产物，postParse 用归一化原文（运行时再解析）。 */
        private final String renderText;

        /** 引擎 {@code SqlText.isWhiteSpace}（按写入文本的 Java {@code trim()} 判空）。 */
        private final boolean whitespace;

        /** master 侧全参构造器（默认参量由便捷构造器补齐）。 */
        public IbatisTextNode(String rawText, boolean postParseRequired, String parsedText, List<IbatisParamMapping> mappings) {
            this.rawText = rawText;
            this.postParseRequired = postParseRequired;
            this.parsedText = parsedText;
            this.mappings = mappings;
            this.text = rawText.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
            this.renderText = parsedText != null ? parsedText : this.text;
            this.whitespace = renderText.trim().isEmpty();
        }

        /** 默认参量便捷构造器（{@code parsedText = null}、{@code mappings = List.of()}）。 */
        public IbatisTextNode(String rawText, boolean postParseRequired) {
            this(rawText, postParseRequired, null, List.of());
        }

        public String getRawText() {
            return rawText;
        }

        public boolean isPostParseRequired() {
            return postParseRequired;
        }

        public String getParsedText() {
            return parsedText;
        }

        public List<IbatisParamMapping> getMappings() {
            return mappings;
        }

        public String getText() {
            return text;
        }

        public String getRenderText() {
            return renderText;
        }

        public boolean isWhitespace() {
            return whitespace;
        }
    }

    public static final class IbatisTagNode implements IbatisSqlNode {

        private final IbatisTagKind kind;
        private final String prepend;
        private final String property;
        private final String removeFirstPrepend;
        private final String open;
        private final String close;
        private final String conjunction;
        private final String compareProperty;
        private final String compareValue;
        private final boolean postParseRequired;
        private final List<IbatisSqlNode> children;

        /**
         * 父标签（引擎 {@code SqlTag.parent}）；根层节点为 null——{@code doPrepend} 的 marker 消费以此为前提。
         *
         * <p>两段式构建（先建子节点、再回填父指针）用 {@link #attachParent(IbatisTagNode)}，故不是构造参数。</p>
         */
        private IbatisTagNode parent;

        /** master 侧全参构造器。 */
        public IbatisTagNode(
                IbatisTagKind kind,
                String prepend,
                String property,
                String removeFirstPrepend,
                String open,
                String close,
                String conjunction,
                String compareProperty,
                String compareValue,
                boolean postParseRequired,
                List<IbatisSqlNode> children
        ) {
            this.kind = kind;
            this.prepend = prepend;
            this.property = property;
            this.removeFirstPrepend = removeFirstPrepend;
            this.open = open;
            this.close = close;
            this.conjunction = conjunction;
            this.compareProperty = compareProperty;
            this.compareValue = compareValue;
            this.postParseRequired = postParseRequired;
            this.children = children;
        }

        public IbatisTagKind getKind() {
            return kind;
        }

        public String getPrepend() {
            return prepend;
        }

        public String getProperty() {
            return property;
        }

        public String getRemoveFirstPrepend() {
            return removeFirstPrepend;
        }

        public String getOpen() {
            return open;
        }

        public String getClose() {
            return close;
        }

        public String getConjunction() {
            return conjunction;
        }

        public String getCompareProperty() {
            return compareProperty;
        }

        public String getCompareValue() {
            return compareValue;
        }

        public boolean isPostParseRequired() {
            return postParseRequired;
        }

        public List<IbatisSqlNode> getChildren() {
            return children;
        }

        public IbatisTagNode getParent() {
            return parent;
        }

        /** 脚本构建器的两段式挂载入口（同一包内可见）。 */
        void attachParent(IbatisTagNode node) {
            this.parent = node;
        }

        /** {@code prepend} 非 null 且非空（引擎 {@code isPrependAvailable()} 同口径）。 */
        public boolean hasPrepend() {
            return prepend != null && !prepend.isEmpty();
        }

        public boolean hasOpen() {
            return open != null && !open.isEmpty();
        }

        public boolean hasClose() {
            return close != null && !close.isEmpty();
        }

        public boolean hasConjunction() {
            return conjunction != null && !conjunction.isEmpty();
        }
    }

    /** {@code <parameterMap>} / {@code <parameter>} 声明。 */
    public record IbatisParameterDef(
            String property,
            String javaType,
            String jdbcType,
            String nullValue,
            String mode,
            String typeHandler
    ) {
    }

    /** {@code <parameterMap id class>}（DTD 里 {@code class} 是 {@code #REQUIRED}）。 */
    public record IbatisParameterMap(String id, String className, List<IbatisParameterDef> parameters) {
    }

    /** {@code <selectKey>} 生成的独立语句（引擎 statement id = {@code <insertId>-SelectKey}）。 */
    public record IbatisSelectKey(
            String id,
            /** 引擎 {@code SelectKeyStatement.runAfterSQL}：{@code type="post"} 优先，否则由位置推断（selectKey 前是否已有非空 SQL 文本）。 */
            boolean runAfterSql,
            java.util.Map<String, String> attributes,
            List<IbatisSqlNode> body,
            List<String> warnings
    ) {

        public IbatisSelectKey(String id, boolean runAfterSql, java.util.Map<String, String> attributes, List<IbatisSqlNode> body) {
            this(id, runAfterSql, attributes, body, List.of());
        }
    }

    /** 一条可执行语句（{@code select}/{@code insert}/{@code update}/{@code delete}/{@code statement}/{@code procedure}）。 */
    public record IbatisStatement(
            String id,
            IbatisStatementKind kind,
            String kindTagName,
            java.util.Map<String, String> attributes,
            /** {@code parameterMap} 属性原值（未加 namespace 前缀）；缺省 null。 */
            String parameterMapRef,
            /**
             * 引擎 {@code MappedStatementConfig.applyInlineParameterMap} 的 {@code if (parameterMap == null)} 守卫判据。
             *
             * <p><b>只在非动态语句上生效</b>：动态语句（有任一动态标签）走 {@code DynamicSql}，{@code #} 一律转
             * {@code ?}（{@code setSqlForStatement} 的 {@code sql instanceof DynamicSql} 分支）——这是本链必须复刻的形态。</p>
             */
            boolean hasParameterMapDeclaration,
            List<IbatisSqlNode> body,
            /** 命中的 {@code <parameterMap>} 声明（按 {@code applyNamespace} 解析）；缺省 null。 */
            IbatisParameterMap parameterMap,
            /** 语句体内展开过的 {@code <sql id>}（解析期收集，渲染产物直接透出）。 */
            List<String> usedSqlFragmentIds,
            List<IbatisSelectKey> selectKeys,
            List<String> warnings
    ) {

        /**
         * 引擎 {@code XMLSqlSource} 的 {@code isDynamic}：出现任一已注册标签即为动态语句。
         *
         * <p>master 侧是构造期初始化器 {@code val isDynamic = body.any { it is IbatisTagNode }}，
         * record 改为派生方法（调用口径由属性访问改为方法调用）。</p>
         */
        public boolean isDynamic() {
            for (IbatisSqlNode node : body) {
                if (node instanceof IbatisTagNode) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 一个 {@code <sqlMap>} 文件的解析产物（语句 + 参数图 + 片段清单 + 降级告警）。 */
    public record IbatisSqlMap(
            /** {@code namespace} 属性原值；{@code #IMPLIED}，可为 null（iBatis 2 里 namespace 不是必填）。 */
            String namespace,
            List<IbatisStatement> statements,
            List<IbatisParameterMap> parameterMaps,
            /** {@code <sql id>} 清单（原样 id，与 engine 默认 {@code useStatementNamespaces=false} 的注册键一致）。 */
            List<String> sqlFragmentIds,
            List<String> warnings
    ) {

        public IbatisStatement statementById(String id) {
            IbatisStatement found = null;
            for (IbatisStatement statement : statements) {
                if (statement.id().equals(id)) {
                    found = statement;
                    break;
                }
            }
            return found;
        }

        /** 含 {@code selectKey} 生成的语句在内的全部 id（供 UI 列出）。 */
        public List<String> allStatementIds() {
            java.util.List<String> result = new java.util.ArrayList<>();
            for (IbatisStatement statement : statements) {
                result.add(statement.id());
                for (IbatisSelectKey selectKey : statement.selectKeys()) {
                    result.add(selectKey.id());
                }
            }
            return result;
        }
    }

    /** iBatis 文件结构级失败（XML 语法错 / 根元素不是 {@code <sqlMap>}）——语句级问题一律降级为 warning。 */
    public static final class IbatisParseException extends RuntimeException {
        public IbatisParseException(String message) {
            super(message);
        }
    }

    /** 渲染期硬错误（引擎同样抛出的形态，如 iterate 的属性不是集合/数组/迭代器）。 */
    public static final class IbatisRenderException extends RuntimeException {
        public IbatisRenderException(String message) {
            super(message);
        }
    }

    /**
     * 渲染产物（纯数据，不依赖 {@code model/PreviewResult.kt}——类型转换由接线车道负责）。
     */
    public record IbatisRenderResult(
            /** {@code ?} 形态的 SQL（{@code #} 已转 {@code ?}；{@code $} 已按参数值直替）。 */
            String sql,
            /** 空白折叠后的单行形态（面板「紧凑视图」用；<b>不改变 {@link #sql()} 的保真性</b>）。 */
            String compactSql,
            /** 按 {@code ?} 出现顺序的参数名（iterate 内为 {@code ids[0]}/{@code ids[1]} 形态）。 */
            List<String> paramNames,
            /** 与 {@code paramNames} 同序的 {@code jdbcType}（{@code #p:TYPE#} 的第二段）。 */
            List<String> paramJdbcTypes,
            /** 与 {@code paramNames} 同序的 {@code nullValue} 哨兵（{@code #p:TYPE:NULL值#} 的第三段）。 */
            List<String> paramNullValues,
            /** 与 {@code paramNames} 同序的哨兵命中标记（参数值 == {@code nullValue} ⇒ 引擎按 SQL NULL 绑定）。 */
            List<Boolean> nullBindFlags,
            /** 降级/配置错误告警（含语句 id 可回溯）。 */
            List<String> warnings,
            /** {@code $x$} 直替<b>站点数</b>（含值为 null 被静默丢弃的站点）。 */
            int dollarRawCount,
            /** 实际写入 SQL 的 {@code $} 取值（<b>注入闸门接既有 {@code ${}} 确认通道</b>用）。 */
            List<String> dollarRenderedValues,
            /** 本次渲染涉及的语句 id（含展开的 {@code <include>} 归属主语句）。 */
            List<String> usedStatementIds,
            /** 展开过的 {@code <sql id>} 清单。 */
            List<String> usedSqlFragmentIds,
            /**
             * 券 D（T-Ibatis2Gap）：本次渲染是否走了降级回退（{@code renderBody} 抛错后仅按顶层文本节点拼装）。
             * 默认 false——既有构造点零影响；UI 侧据此区分「正常渲染」与「半截 SQL」，不再静默。
             */
            boolean degraded,
            /** 券 D：降级时未渲染的顶层节点事实（失败节点 + 失败原因分类 + 涉及的 property）。非降级恒空。 */
            List<SqlMapSqlRenderer.IbatisUnrenderedNode> unrenderedNodes,
            /**
             * 券 C：{@code parameterMap} 声明了、但语句体参数位点（手写 {@code ?} / 内联 {@code #x#} 对）没消费到的映射
             * ——供 UI 提示「声明了但未消费」。按位取前 N 之后的剩余，声明数 ≤ 位点数时恒空。
             */
            List<IbatisParamMapping> unusedDeclaredParams
    ) {

        /** master 侧默认参量便捷构造器（{@code degraded = false}、{@code unrenderedNodes = List.of()}、{@code unusedDeclaredParams = List.of()}）。 */
        public IbatisRenderResult(
                String sql,
                String compactSql,
                List<String> paramNames,
                List<String> paramJdbcTypes,
                List<String> paramNullValues,
                List<Boolean> nullBindFlags,
                List<String> warnings,
                int dollarRawCount,
                List<String> dollarRenderedValues,
                List<String> usedStatementIds,
                List<String> usedSqlFragmentIds
        ) {
            this(sql, compactSql, paramNames, paramJdbcTypes, paramNullValues, nullBindFlags,
                    warnings, dollarRawCount, dollarRenderedValues, usedStatementIds, usedSqlFragmentIds,
                    false, List.of(), List.of());
        }

        /** 是否含 {@code $} 直替段（注入闸门判据，等价于既有 {@code PreviewResult.hasDollarRawSegments} 语义）。 */
        public boolean hasDollarRawSegments() {
            return !dollarRenderedValues.isEmpty();
        }
    }
}
