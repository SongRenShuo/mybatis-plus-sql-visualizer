package cn.song.mybatisplus.visualizer.ui.standalone;

import java.util.regex.Pattern;

/**
 * XML 片段风味判定（T-StandaloneIbatisRecognize D-1）：判断一段 XML 语句片段属于
 * <b>MyBatis 3</b> 还是 <b>iBatis 2</b>。
 *
 * <h2>为什么需要它</h2>
 *
 * {@code <select>} / {@code <insert>} / {@code <update>} / {@code <delete>} 四个标签名在两代框架里<b>同名</b>，
 * 独立预览的检测门只看「标签名 + id 属性」时无法区分；把 iBatis 2 片段送进 MyBatis 链会
 * 得到 {@code Unknown element <dynamic> in SQL statement.}（{@code xml/XmlScriptBuilder}）这种
 * <b>假错误</b>——用户 2026-11-18 报障的根因。
 *
 * <h2>为什么不能复用 {@link cn.song.mybatisplus.visualizer.xml.ibatis.IbatisXmlRecognizer}</h2>
 *
 * 那个是<b>文件级</b>三选二（根元素 {@code sqlMap} / DOCTYPE / 工程 classpath 有 {@code SqlMapClient}）。
 * 粘贴片段三条全取不到（根是 {@code select}、无 DOCTYPE、无工程上下文），必然判否。本判定器是
 * <b>片段级</b>，只读文本。两处的标签/DTD 名单保持同源（新增 iBatis 标签时两处一起加）。
 *
 * <h2>判据（纯文本、零平台依赖）</h2>
 *
 * 加权计分，iBatis 分数严格高于 MyBatis 才判 iBatis；<b>同分判 MyBatis</b>（保住既有行为，
 * iBatis 需要证据才抢）。四类信号：
 *
 * <table border="1">
 * <tr><th>类别</th><th>iBatis 2</th><th>MyBatis 3</th></tr>
 * <tr><td>根元素</td><td>{@code sqlMap} / {@code sqlMapConfig}</td><td>{@code mapper} / {@code configuration}</td></tr>
 * <tr><td>DOCTYPE</td><td>{@code sql-map-2.dtd} / {@code SQL Map 2.0}</td><td>{@code mybatis-3-mapper.dtd}</td></tr>
 * <tr><td>专有标签</td><td>{@code dynamic} / {@code isXxx} 十四族 / {@code iterate}</td><td>{@code if} / {@code foreach} / {@code choose} / {@code where} / {@code set} / {@code trim} / {@code bind}</td></tr>
 * <tr><td>专有属性</td><td>{@code parameterClass} / {@code resultClass} / {@code parameterMap} / {@code listClass} / {@code compareValue}</td><td>{@code parameterType} / {@code statementType} / {@code resultOrdered}</td></tr>
 * <tr><td>占位语法</td><td>{@code #name#} / {@code $name$}</td><td>{@code #{name}} / {@code ${name}}</td></tr>
 * </table>
 *
 * <b>占位语法必须 string-aware</b>：MyBatis 里写 {@code WHERE a = '#x#'}（字符串字面量内的 iBatis 风格
 * 文本）不得给出 iBatis 信号——否则会因一处字面量把整段判错。注释内的信号同样不计。
 *
 * <h2>判错也不致命</h2>
 *
 * {@link StandaloneSqlSourceResolver} 在首选链失败后会跑另一条链择优，所以本判定器只需「大概率对」，不必追求绝对。
 */
public final class XmlFlavorDetector {

    private XmlFlavorDetector() {
    }

    /** 片段风味。 */
    public enum Flavor {
        MYBATIS, IBATIS
    }

    /** 判定入口：返回首选解析链的风味。 */
    public static Flavor detect(String text) {
        if (text.isBlank()) {
            return Flavor.MYBATIS;
        }
        String stripped = stripXmlComments(text);
        int ibatis = 0;
        int mybatis = 0;

        if (ROOT_IBATIS.matcher(stripped).find()) {
            ibatis += ROOT_WEIGHT;
        }
        if (ROOT_MYBATIS.matcher(stripped).find()) {
            mybatis += ROOT_WEIGHT;
        }
        if (stripped.toLowerCase(java.util.Locale.ROOT).contains("sql-map-2.dtd")
            || stripped.toLowerCase(java.util.Locale.ROOT).contains("sql map 2.0")) {
            ibatis += DOCTYPE_WEIGHT;
        }
        if (stripped.toLowerCase(java.util.Locale.ROOT).contains("mybatis-3-mapper.dtd")) {
            mybatis += DOCTYPE_WEIGHT;
        }

        if (IBATIS_TAG.matcher(stripped).find()) {
            ibatis += TAG_WEIGHT;
        }
        if (MYBATIS_TAG.matcher(stripped).find()) {
            mybatis += TAG_WEIGHT;
        }
        if (IBATIS_ATTR.matcher(stripped).find()) {
            ibatis += ATTR_WEIGHT;
        }
        if (MYBATIS_ATTR.matcher(stripped).find()) {
            mybatis += ATTR_WEIGHT;
        }

        Flavor ph = placeholderFlavor(text);
        if (ph == Flavor.IBATIS) {
            ibatis += PLACEHOLDER_WEIGHT;
        } else if (ph == Flavor.MYBATIS) {
            mybatis += PLACEHOLDER_WEIGHT;
        }
        return ibatis > mybatis ? Flavor.IBATIS : Flavor.MYBATIS;
    }

    // ---------- 占位语法（string-aware） ----------

    /**
     * 占位语法风味：按字符串外出现次数比较两种风格。都出现且数量相等时返回 null
     * （不给信号，交其它判据决定）；都不出现同样 null。
     */
    @org.jetbrains.annotations.Nullable
    private static Flavor placeholderFlavor(String text) {
        int ibatis = 0;
        int mybatis = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'') {
                i = skipString(text, i);
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
            } else if (c == '#') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '{') {
                    mybatis++;
                    i += 2;
                    continue;
                }
                int end = bracedEnd(text, i, '#');
                if (end > 0) {
                    ibatis++;
                    i = end;
                    continue;
                }
            } else if (c == '$') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '{') {
                    mybatis++;
                    i += 2;
                    continue;
                }
                int end = bracedEnd(text, i, '$');
                if (end > 0) {
                    ibatis++;
                    i = end;
                    continue;
                }
            }
            i++;
        }
        if (ibatis > mybatis) {
            return Flavor.IBATIS;
        }
        if (mybatis > ibatis) {
            return Flavor.MYBATIS;
        }
        return null;
    }

    /**
     * {@code #name#} / {@code $name$} 的结束位（首符之后是标识符且以同款定界符收尾）；不是该形态返回 -1。
     * （master 侧 {@code bracedHashEnd} / {@code bracedDollarEnd} 两函数同形，仅收尾定界符不同，Java 侧合一。）
     */
    private static int bracedEnd(String text, int start, char delimiter) {
        int i = start + 1;
        if (i >= text.length() || !isNameStart(text.charAt(i))) {
            return -1;
        }
        while (i < text.length() && isNamePart(text.charAt(i))) {
            i++;
        }
        return i < text.length() && text.charAt(i) == delimiter ? i + 1 : -1;
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == ':';
    }

    /** 跳过单引号字符串（{@code ''} 转义），返回字符串后的位点。 */
    private static int skipString(String text, int start) {
        int i = start + 1;
        while (i < text.length()) {
            if (text.charAt(i) == '\'') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return text.length();
    }

    /** 剥 XML 注释——注释掉的标签/属性不得当信号。 */
    private static String stripXmlComments(String text) {
        if (!text.contains("<!--")) {
            return text;
        }
        return XML_COMMENT.matcher(text).replaceAll(" ");
    }

    // ---------- 权重与名单 ----------

    private static final int ROOT_WEIGHT = 3;
    private static final int DOCTYPE_WEIGHT = 3;
    private static final int TAG_WEIGHT = 2;
    private static final int ATTR_WEIGHT = 2;
    private static final int PLACEHOLDER_WEIGHT = 2;

    private static final Pattern XML_COMMENT = Pattern.compile("<!--[\\s\\S]*?-->");

    private static final Pattern ROOT_IBATIS = Pattern.compile("<\\s*(sqlMap|sqlMapConfig)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROOT_MYBATIS = Pattern.compile("<\\s*(mapper|configuration)\\b", Pattern.CASE_INSENSITIVE);

    /** iBatis 2 专有条件/结构标签（与 {@code xml/ibatis/IbatisModel} 的 IbatisNodeKind 同源）。 */
    private static final Pattern IBATIS_TAG = Pattern.compile(
        "<\\s*(dynamic|isNull|isNotNull|isEmpty|isNotEmpty|isEqual|isNotEqual|isGreaterThan|"
            + "isGreaterEqual|isLessThan|isLessEqual|isPropertyAvailable|isNotPropertyAvailable|"
            + "isParameterPresent|iterate)\\b",
        Pattern.CASE_INSENSITIVE);

    /** MyBatis 3 专有动态标签。 */
    private static final Pattern MYBATIS_TAG = Pattern.compile(
        "<\\s*(if|foreach|choose|when|otherwise|where|set|trim|bind)\\b",
        Pattern.CASE_INSENSITIVE);

    /** iBatis 2 专有属性（{@code resultMap} 两代同名，不计入）。 */
    private static final Pattern IBATIS_ATTR = Pattern.compile(
        "\\b(parameterClass|resultClass|parameterMap|listClass|compareValue)\\s*=",
        Pattern.CASE_INSENSITIVE);

    /** MyBatis 3 专有属性。 */
    private static final Pattern MYBATIS_ATTR = Pattern.compile(
        "\\b(parameterType|statementType|resultOrdered)\\s*=",
        Pattern.CASE_INSENSITIVE);
}
