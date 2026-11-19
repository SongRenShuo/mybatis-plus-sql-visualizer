// T-SyncK2JMaster180 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/standalone/StandaloneSqlSourceResolver.kt 移植
package cn.song.mybatisplus.visualizer.ui.standalone;

import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer;
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning;
import cn.song.mybatisplus.visualizer.sql.PlaceholderPositionsKt;
import cn.song.mybatisplus.visualizer.sql.SqlPerfAnalyzer;
import cn.song.mybatisplus.visualizer.ui.IbatisXmlPreviewService;
import cn.song.mybatisplus.visualizer.ui.XmlPreviewService;
import cn.song.mybatisplus.visualizer.util.FatalErrors;
import com.intellij.openapi.util.registry.Registry;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 独立预览工作台的双源解析器（T-SqlPreviewStandalone D-1 / D-1b）。
 *
 * <p>纯逻辑（无 PSI / 无 IDE 依赖，可独立单测）：把用户粘贴的一段文本解析成
 * {@link PreviewResult}，供独立预览 tab / 弹框共用同一份识别口径（dbx「执行器认什么检测器
 * 就认什么」纪律——填值面板与检测门同源，不出现两套认知漂移）。</p>
 *
 * <p>判定门优先级：</p>
 * <ol>
 *  <li><b>XML 语句块</b>：文本含 {@code <select|insert|update|delete|statement|procedure>} 带 {@code id} 属性的
 *     标签 → 先由 {@link XmlFlavorDetector} 判风味，再走对应纯文本解析链（MyBatis 3 =
 *     {@link XmlPreviewService#preview}；iBatis 2 = {@link IbatisXmlPreviewService#preview}，两者 {@code project}
 *     均可空、零 PSI 读），首选链不可用时跑另一条链<b>择优</b>——{@code <select>} 标签名两代同名，
 *     证据不足的片段只能靠回退兜底（T-StandaloneIbatisRecognize）。
 *     主体自研、不引外部实现；dbx 只吸三点精华：J-1（引号感知标签配对——本链经 DOM 解析天然
 *     规避字面量 {@code <} 干扰，检测门只需正则锚定 + 标签名配对）、J-2（XML 实体解码，仅当 DOM 原文
 *     解析失败才解码重试——保证合法 XML 里 {@code a &lt; b} 的实体语义不被破坏）、J-3（空值=不解析
 *     逃生门由填值面板既有语义承接，见计划 D-6）。</li>
 *  <li><b>纯 SQL</b>：string-aware {@code ?} 定位复用 {@link PlaceholderPositionsKt#placeholderPositions}
 *     （T-PSG 三链共用口径），{@code #{name}} 手写扫描（同样跳过字符串字面量与注释），{@code ${}} 原样保留
 *     （raw 语义，置 {@code PreviewResult.hasDollarRawSegments} 由执行确认闸门承接）。</li>
 * </ol>
 *
 * <p>解析失败永不抛异常：XML 门限命中但两条链都还原不了 → 返回带 {@link Resolved#failure()} 的结果
 * （面板据此组装「标题｜来源｜语句｜原因｜建议」的可复制文案）；非语句块的 XML 等形态返回 null
 * （调用方给引导文案）。</p>
 */
public final class StandaloneSqlSourceResolver {

    private StandaloneSqlSourceResolver() {
    }

    /**
     * 输入长度上限（对齐 MCP {@code cn.song.mybatisplus.visualizer.mcp.McpRenderInput.MAX_SNIPPET_LENGTH}
     * 的既有口径）。超长返回 null，调用方给「过长」提示而不是静默失败。
     */
    public static final int MAX_TEXT_LENGTH = 100_000;

    /** 识别结果类别。 */
    public enum Kind {
        XML_STATEMENT, PLAIN_SQL
    }

    /**
     * 解析产物。
     *
     * @param result         直接喂面板/弹框的载荷（XML 路径含 xmlModel/ibatisParamModel，面板自动获得
     *                       条件勾选/foreach 重放/iBatis 条件行）
     * @param kind           识别类别（状态条展示「识别为 mapper XML 语句块」/「按纯 SQL 解析」）
     * @param statementId    XML 路径取到的语句 id（多语句片段默认取第一个，与 XmlPreviewService
     *                       statementId=null 的既有口径一致）
     * @param statementCount XML 路径检测到的语句块总数（状态条提示「共 N 个，取第一个」）
     * @param paramCount     纯 SQL 路径的占位符总数（{@code ?} + {@code #{}}；{@code ${}} 不计）
     * @param flavor         XML 路径实际生效的解析链（MyBatis 3 / iBatis 2）；纯 SQL 为 null
     * @param failure        XML 路径两条链都未能还原时的失败事实（供面板组装可复制的结构化文案）；
     *                       成功为 null
     */
    public record Resolved(PreviewResult result,
                           Kind kind,
                           @Nullable String statementId,
                           int statementCount,
                           int paramCount,
                           @Nullable XmlFlavorDetector.Flavor flavor,
                           @Nullable Failure failure) {

        /** XML 路径形态（paramCount 取默认 0）。 */
        public Resolved(PreviewResult result,
                        Kind kind,
                        @Nullable String statementId,
                        int statementCount,
                        @Nullable XmlFlavorDetector.Flavor flavor,
                        @Nullable Failure failure) {
            this(result, kind, statementId, statementCount, 0, flavor, failure);
        }

        /** 纯 SQL 路径形态（statementId=null / statementCount=0 / flavor=null / failure=null）。 */
        public Resolved(PreviewResult result, Kind kind, int paramCount) {
            this(result, kind, null, 0, paramCount, null, null);
        }
    }

    /**
     * XML 路径的失败事实（T-StandaloneIbatisRecognize D-4）。
     *
     * <p>只承载<b>事实</b>，文案由面板按 bundle 组装——解析器保持零平台/零 i18n 依赖，
     * 才能在快桶（纯逻辑）里被单测直接驱动。</p>
     *
     * @param flavor           首选链（也就是本次给出的错误来源链）
     * @param statementId      片段里识别到的语句 id（可能为 null）
     * @param statementTagName 语句标签名（{@code select}/{@code statement}/…，可能为 null）
     * @param reason           首选链给出的原始原因（通常是引擎级英文报错）
     * @param otherChainFailed 另一条链是否也失败（true 时文案提示「两条链都没能还原」）
     */
    public record Failure(XmlFlavorDetector.Flavor flavor,
                          @Nullable String statementId,
                          @Nullable String statementTagName,
                          @Nullable String reason,
                          boolean otherChainFailed) {

        /** 仅给首选链的短形态（其余取默认）。 */
        public Failure(XmlFlavorDetector.Flavor flavor) {
            this(flavor, null, null, null, false);
        }
    }

    /**
     * 主入口：按判定门优先级解析。null = 无法识别（空文本 / 超长 / 非 SQL 的 XML 等）。
     */
    @Nullable
    public static Resolved resolve(String text) {
        String input = stripCodeFence(text.trim());
        if (input.isEmpty() || input.length() > MAX_TEXT_LENGTH) {
            return null;
        }
        String decoded = decodeXmlEntitiesIfEscaped(input);
        if (STATEMENT_TAG_REGEX.matcher(decoded).find()) {
            return resolveAsXml(input, decoded);
        }
        // 非 SQL 的 XML（pom 片段等）：以 `<` 开头但检测门未命中 → 不硬塞纯 SQL 解析（A-7 降级）
        if (input.startsWith("<") || decoded.startsWith("<")) {
            return null;
        }
        return resolveAsPlainSql(input);
    }

    /**
     * 剪贴板预填候选判定（D-4）：比 {@link #resolve} 宽松——只要「疑似 SQL 或 mapper XML」即预填，
     * 识别结果交给用户按「解析预览」后由状态条如实呈现。null 文本（空/超长）返回 false。
     */
    public static boolean isClipboardCandidate(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            return false;
        }
        String input = stripCodeFence(text.trim());
        if (input.isEmpty()) {
            return false;
        }
        if (STATEMENT_TAG_REGEX.matcher(decodeXmlEntitiesIfEscaped(input)).find()) {
            return true;
        }
        if (input.startsWith("<")) {
            return false; // XML 但非 mapper 语句块 → 不预填
        }
        return SQL_KEYWORD_START_REGEX.matcher(input).find()
            || !PlaceholderPositionsKt.placeholderPositions(input).isEmpty();
    }

    // ---------- XML 路径（自研链，D-1b） ----------

    @Nullable
    private static Resolved resolveAsXml(String original, String decoded) {
        // T-StandaloneIbatisRecognize：片段级风味判定 → 首选链；首选链不可用则跑另一条链择优。
        // 为什么必须双链：`<select>` 标签名两代同名，判定只能靠专有标签/属性/占位语法等**证据**，
        // 证据不足的片段（只有 `<select id>` + 纯文本）判成哪一边都可能错——回退是兜底，不是冗余。
        XmlFlavorDetector.Flavor flavor = XmlFlavorDetector.detect(decoded);
        ChainRun primary = runChain(flavor, original, decoded);
        if (primary.usable()) {
            return toResolved(primary, decoded, flavor, false);
        }
        XmlFlavorDetector.Flavor otherFlavor = flavor == XmlFlavorDetector.Flavor.IBATIS
            ? XmlFlavorDetector.Flavor.MYBATIS
            : XmlFlavorDetector.Flavor.IBATIS;
        ChainRun secondary = runChain(otherFlavor, original, decoded);
        if (secondary.usable()) {
            return toResolved(secondary, decoded, otherFlavor, false);
        }
        // 两条链都没还原：返回**首选链**结果（原因更贴近判定），并带失败事实供面板组装文案
        return toResolved(primary, decoded, flavor, secondary.result() != null);
    }

    /** 一条解析链的产物。 */
    private record ChainRun(@Nullable PreviewResult result, boolean usable) {
    }

    /** 按风味跑对应解析链（纯文本、project = null，零 PSI 读）。 */
    private static ChainRun runChain(XmlFlavorDetector.Flavor flavor, String original, String decoded) {
        return switch (flavor) {
            case MYBATIS -> runMybatis(original, decoded);
            case IBATIS -> runIbatis(original, decoded);
        };
    }

    private static ChainRun runMybatis(String original, String decoded) {
        List<String> candidates = new ArrayList<>();
        candidates.add(original);
        if (!decoded.equals(original)) {
            candidates.add(decoded);
        }
        candidates.add(wrapAsMapper(decoded, true));
        candidates.add(wrapAsMapper(decoded, false));
        Set<String> distinct = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (!candidate.isEmpty()) {
                distinct.add(candidate);
            }
        }
        PreviewResult last = null;
        for (String candidate : distinct) {
            PreviewResult result = XmlPreviewService.INSTANCE.preview(candidate, null, null);
            if (result.getXmlModel() != null) {
                return new ChainRun(result, true);
            }
            last = result;
        }
        return new ChainRun(last, false);
    }

    /**
     * iBatis 2 链：{@link IbatisXmlPreviewService#preview} 是纯文本入口（project 可空），
     * 要求根元素 {@code <sqlMap>}——粘贴片段与 MyBatis 侧同样需要包根。
     */
    private static ChainRun runIbatis(String original, String decoded) {
        List<String> candidates = new ArrayList<>();
        Set<String> distinctInputs = new LinkedHashSet<>();
        distinctInputs.add(original);
        distinctInputs.add(decoded);
        for (String text : distinctInputs) {
            if (text.toLowerCase(Locale.ROOT).startsWith("<sqlmap")) {
                candidates.add(text);
            }
        }
        candidates.add(wrapAsSqlMap(decoded, true));
        candidates.add(wrapAsSqlMap(decoded, false));
        Set<String> distinct = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (!candidate.isEmpty()) {
                distinct.add(candidate);
            }
        }
        PreviewResult last = null;
        for (String candidate : distinct) {
            PreviewResult result;
            try {
                result = IbatisXmlPreviewService.preview(candidate, null, null, 0);
            } catch (Throwable t) {
                if (FatalErrors.isFatal(t)) throw (Error) t;
                continue;
            }
            if (result.getIbatisParamModel() != null) {
                return new ChainRun(result, true);
            }
            last = result;
        }
        return new ChainRun(last, false);
    }

    private static Resolved toResolved(ChainRun run,
                                       String decoded,
                                       XmlFlavorDetector.Flavor flavor,
                                       boolean otherChainFailed) {
        // 语句定位：优先用成对标签块（含 id/标签名）；片段被截断（无闭合标签）时块提取为空，
        // 退到「扫首个语句标签」——失败文案仍能报出 id 与标签名，用户才知道说的是哪一段。
        StatementBlock block = firstStatementBlock(decoded);
        Matcher tagMatcher = STATEMENT_TAG_REGEX.matcher(decoded);
        boolean tagFound = tagMatcher.find();
        String statementId = null;
        if (block != null && block.id() != null) {
            statementId = block.id();
        } else if (tagFound) {
            Matcher idMatcher = ID_ATTR_REGEX.matcher(decoded.substring(tagMatcher.start()));
            if (idMatcher.find()) {
                statementId = idMatcher.group(1);
            }
        }
        String tagName = null;
        if (block != null) {
            tagName = block.tagName();
        } else if (tagFound) {
            tagName = tagMatcher.group(1).toLowerCase(Locale.ROOT);
        }
        Failure failure = null;
        if (!run.usable()) {
            String reason = run.result() != null ? run.result().getWarning() : null;
            if (reason != null && reason.isBlank()) {
                reason = null;
            }
            failure = new Failure(flavor, statementId, tagName, reason, otherChainFailed);
        }
        PreviewResult payload = run.result() != null ? withPerfCheck(run.result()) : emptyPreviewResult();
        return new Resolved(payload, Kind.XML_STATEMENT, statementId,
            countStatementBlocks(decoded), flavor, failure);
    }

    /** 空载荷（两条链都拿不到结果时的兜底，面板仍可渲染空态）。 */
    public static PreviewResult emptyPreviewResult() {
        return new PreviewResult("", "", List.of(), null, null, null, List.of());
    }

    // ------------------------------------------------------------------
    // D234/T-StandaloneExecPhase2：粘贴即体检
    // ------------------------------------------------------------------

    /** Registry 键（plugin.xml {@code <registryKey>} 登记，默认开）：关=不填 perfWarnings（旧行为，建议行不出现）。 */
    public static final String PERF_CHECK_REGISTRY_KEY = "sqlvis.preview.standalonePerfCheck";

    /** 生产默认读取（单独暴露：测试用例还原缝不引平台类字面量，避免 testFast 划桶误踢）。 */
    public static final BooleanSupplier defaultPerfCheckEnabledProvider = () -> {
        try {
            return Registry.is(PERF_CHECK_REGISTRY_KEY, true);
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw (Error) t;
            return true;
        }
    };

    /** 测试缝：总开关读取（生产＝{@code Registry.is(KEY, true)}）。 */
    public static volatile BooleanSupplier perfCheckEnabledProvider = defaultPerfCheckEnabledProvider;

    /**
     * 解析产物补填 {@code PreviewResult.perfWarnings}（粘贴即体检）：内嵌面板 ResultView 的既有
     * 性能建议渲染面直接消费——与 ORM 消费路径（SqlPreviewServiceEnhancements F-2）同一
     * 渲染面、同一规则引擎，<b>无双报</b>（面板只渲染 result 携带的一份）。
     *
     * <p>分析是纯文本函数（{@link SqlPerfAnalyzer} 自带 LRU 缓存），方言取默认推断；失败静默返回
     * 原载荷——体检是增强面，绝不影响识别主链。</p>
     */
    private static PreviewResult withPerfCheck(PreviewResult result) {
        if (!perfCheckEnabledProvider.getAsBoolean()) {
            return result;
        }
        if (result.getSql().isBlank()) {
            return result;
        }
        List<PerfWarning> warnings;
        try {
            warnings = SqlPerfAnalyzer.analyze(result.getSql());
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw (Error) t;
            return result;
        }
        return result.withPerfWarnings(warnings);
    }

    /**
     * 包根：{@code XmlMapperParser} 要求根元素 {@code <mapper>}（解析失败告「不是 MyBatis mapper 文件」）。
     * preferFullText=true：整段包根（保留 {@code <sql>} include 片段的同文解析）；false：只包检测门
     * 提取出的语句块（正文混入说明文字时兜底）。
     */
    private static String wrapAsMapper(String text, boolean preferFullText) {
        if (text.toLowerCase(Locale.ROOT).startsWith("<mapper")) {
            return text;
        }
        if (!preferFullText) {
            StringBuilder blocks = new StringBuilder();
            boolean first = true;
            for (StatementBlock block : extractStatementBlocks(text)) {
                if (!first) {
                    blocks.append('\n');
                }
                first = false;
                blocks.append(block.blockText());
            }
            if (blocks.length() == 0) {
                return "";
            }
            return "<mapper>" + blocks + "</mapper>";
        }
        return "<mapper>" + text + "</mapper>";
    }

    /**
     * iBatis 侧包根：{@code SqlMapParser} 要求根元素 {@code <sqlMap>}（解析失败告「不是 iBatis sqlMap 文件」）。
     * 形态与 {@link #wrapAsMapper} 对称：整段包根保留同文 {@code <sql id>} 片段，或只包检测门提取的语句块。
     */
    private static String wrapAsSqlMap(String text, boolean preferFullText) {
        if (text.toLowerCase(Locale.ROOT).startsWith("<sqlmap")) {
            return text;
        }
        if (!preferFullText) {
            StringBuilder blocks = new StringBuilder();
            boolean first = true;
            for (StatementBlock block : extractStatementBlocks(text)) {
                if (!first) {
                    blocks.append('\n');
                }
                first = false;
                blocks.append(block.blockText());
            }
            if (blocks.length() == 0) {
                return "";
            }
            return "<sqlMap>" + blocks + "</sqlMap>";
        }
        return "<sqlMap>" + text + "</sqlMap>";
    }

    /** 语句块提取：标签名配对（同种标签不嵌套——语句映射的 XML 结构保证），不做正则整段匹配。 */
    private static List<StatementBlock> extractStatementBlocks(String text) {
        List<StatementBlock> blocks = new ArrayList<>();
        Matcher matcher = STATEMENT_TAG_REGEX.matcher(text);
        while (matcher.find()) {
            String tag = matcher.group(1).toLowerCase(Locale.ROOT);
            int start = matcher.start();
            boolean overlaps = false;
            for (StatementBlock existing : blocks) {
                if (start >= existing.openStart() && start < existing.closeEnd()) {
                    overlaps = true;
                    break;
                }
            }
            if (overlaps) {
                continue;
            }
            String closeTag = "</" + tag + ">";
            int closeIdx = text.indexOf(closeTag, matcher.end());
            if (closeIdx < 0) {
                continue;
            }
            int end = closeIdx + closeTag.length();
            Matcher idMatcher = ID_ATTR_REGEX.matcher(text.substring(start, end));
            String id = idMatcher.find() ? idMatcher.group(1) : null;
            blocks.add(new StatementBlock(text.substring(start, end), start, end, id, tag));
        }
        return blocks;
    }

    private static int countStatementBlocks(String text) {
        return extractStatementBlocks(text).size();
    }

    @Nullable
    private static StatementBlock firstStatementBlock(String text) {
        List<StatementBlock> blocks = extractStatementBlocks(text);
        return blocks.isEmpty() ? null : blocks.get(0);
    }

    private record StatementBlock(String blockText,
                                  int openStart,
                                  int closeEnd,
                                  @Nullable String id,
                                  /** 语句标签名（小写）：{@code select}/{@code insert}/{@code update}/{@code delete}/{@code statement}/{@code procedure}。 */
                                  String tagName) {
    }

    /**
     * J-2：XML 实体解码——仅当文本出现 {@code &lt;}/{@code &gt;}（网页/文档复制的整段转义形态）才做，
     * 且只解码五个标准实体。合法 XML 里的 {@code a &lt; b} 由 DOM 解析正确还原（先试原文、解码仅作
     * 重试候选，见 {@code resolveAsXml} 的 attempts 序），不重演 dbx #11699 的实体分号切分问题。
     */
    private static String decodeXmlEntitiesIfEscaped(String text) {
        if (!text.contains("&lt;") && !text.contains("&gt;")) {
            return text;
        }
        return text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&");
    }

    // ---------- 纯 SQL 路径（D-1） ----------

    private static Resolved resolveAsPlainSql(String input) {
        List<Integer> qPositions = PlaceholderPositionsKt.placeholderPositions(input);
        List<BracedToken> braced = scanBracedParams(input);
        // 事件合并（按位点排序）：`?` 原位保留、`#{name}` → `?`；同名参数共用一个填值行
        //（MyBatis 语义，与 XML 链 mappings 的同名复用一致）
        List<Event> events = new ArrayList<>();
        for (Integer pos : qPositions) {
            events.add(new Event(pos, pos + 1, "", false));
        }
        for (BracedToken token : braced) {
            events.add(new Event(token.start(), token.endExclusive(), token.name(), true));
        }
        events.sort(Comparator.comparingInt(Event::pos));
        StringBuilder placeholderSql = new StringBuilder(input.length());
        List<String> sources = new ArrayList<>();
        int qIndex = 0;
        int prev = 0;
        for (Event event : events) {
            if (event.pos() < prev) {
                continue; // `#{}` 扫描与 `?` 位点在字符串/注释上口径不同，重叠事件跳过
            }
            placeholderSql.append(input, prev, event.pos());
            placeholderSql.append('?');
            prev = event.endExclusive();
            String name;
            if (event.isBraced()) {
                name = event.name().isBlank() ? "param" + (qIndex + 1) : event.name();
            } else {
                qIndex++;
                name = "param" + qIndex;
            }
            sources.add(name);
        }
        placeholderSql.append(input, prev, input.length());
        // `${}` raw 段识别（string-aware，与 `?` 同口径——字符串字面量里的 `${}` 是字面量不是占位）
        boolean dollarRaw = hasDollarRawSegment(input);
        String sql = placeholderSql.toString();
        PreviewResult payload = withPerfCheck(new PreviewResult(
            sql, sql, sources, ParameterReplacer.buildParamTable(sources), null, null,
            null, List.of(), Map.of(), null, null, List.of(), List.of(), null, null,
            Map.of(), null, null, null, null, null, null, dollarRaw));
        return new Resolved(payload, Kind.PLAIN_SQL, events.size());
    }

    /**
     * {@code #{name}} 扫描（string-aware）：跳过单引号字符串（{@code ''} 转义）、{@code --} 行注释、块注释——
     * 字面量里的 {@code #{x}} 是普通文本不当参数（dbx 同语法的 string-fragment 策略属进阶形态，
     * v1 不做，识别失败即如实不识别）。{@code ${}} 不在扫描范围（raw 语义不参与填值）。
     */
    private static List<BracedToken> scanBracedParams(String text) {
        List<BracedToken> tokens = new ArrayList<>();
        boolean inString = false;
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
                } else if (c == '-' && i + 1 < text.length() && text.charAt(i + 1) == '-') {
                    while (i < text.length() && text.charAt(i) != '\n') {
                        i++;
                    }
                } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                    i += 2;
                    while (i + 1 < text.length() && !(text.charAt(i) == '*' && text.charAt(i + 1) == '/')) {
                        i++;
                    }
                    i++;
                } else if (c == '#' && i + 1 < text.length() && text.charAt(i + 1) == '{') {
                    int close = text.indexOf('}', i + 2);
                    if (close >= 0) {
                        String name = text.substring(i + 2, close).trim();
                        tokens.add(new BracedToken(i, close + 1, name));
                        i = close + 1;
                        continue;
                    }
                }
            }
            i++;
        }
        return tokens;
    }

    /** {@code ${}} raw 段检测（string-aware，口径与 {@link #scanBracedParams} 一致）。 */
    private static boolean hasDollarRawSegment(String text) {
        boolean inString = false;
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
                } else if (c == '-' && i + 1 < text.length() && text.charAt(i + 1) == '-') {
                    while (i < text.length() && text.charAt(i) != '\n') {
                        i++;
                    }
                } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                    i += 2;
                    while (i + 1 < text.length() && !(text.charAt(i) == '*' && text.charAt(i + 1) == '/')) {
                        i++;
                    }
                    i++;
                } else if (c == '$' && i + 1 < text.length() && text.charAt(i + 1) == '{') {
                    return true;
                }
            }
            i++;
        }
        return false;
    }

    private record BracedToken(int start, int endExclusive, String name) {
    }

    private record Event(int pos, int endExclusive, String name, boolean isBraced) {
    }

    // ---------- 通用预处理 ----------

    /**
     * markdown 代码围栏剥离（P-8 语料）：{@code ```sql} / {@code ```xml} / 裸 {@code ```} 围栏取首个围栏体；
     * 无围栏原样返回。
     */
    private static String stripCodeFence(String text) {
        if (!text.contains("```")) {
            return text;
        }
        Matcher matcher = CODE_FENCE_REGEX.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        return matcher.group(1).trim();
    }

    /**
     * 语句标签检测门：{@code <select|insert|update|delete|statement|procedure>} 带 {@code id} 属性
     * （语句映射的结构性特征，普通 XML 如 pom 的 {@code <dependency>} 不命中）。对<b>解码后</b>文本判定
     * ——网页复制的 {@code &lt;select id=&quot;x&quot;&gt;} 形态先解码才看得见。
     *
     * <p>{@code statement} / {@code procedure} 是 iBatis 2 专有语句标签（MyBatis 3 无此二名），纳入不误伤。</p>
     */
    private static final Pattern STATEMENT_TAG_REGEX = Pattern.compile(
        "<(select|insert|update|delete|statement|procedure)\\b[^>]*\\bid\\s*=",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern ID_ATTR_REGEX = Pattern.compile(
        "\\bid\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private static final Pattern CODE_FENCE_REGEX = Pattern.compile("```[A-Za-z]*\\s*\\n([\\s\\S]*?)\\n?```");

    private static final Pattern SQL_KEYWORD_START_REGEX = Pattern.compile(
        "^\\s*(select|insert|update|delete|with|explain|show|desc)\\b",
        Pattern.CASE_INSENSITIVE);
}
