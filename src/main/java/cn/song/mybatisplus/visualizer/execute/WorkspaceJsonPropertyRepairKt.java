package cn.song.mybatisplus.visualizer.execute;

import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonArray;
import kotlinx.serialization.json.JsonElement;
import kotlinx.serialization.json.JsonObject;
import kotlinx.serialization.json.JsonPrimitive;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T-WorkspaceHeal（2026-11-13）：修复被本插件旧版本写坏的 {@code .idea/workspace.xml}。
 *
 * <p>缺陷背景（真机实证，见 DataSourceSelector 的 rememberRunner KDoc）：PropertiesComponent 存的是
 * <b>项目级</b>持久化状态，平台用 kotlinx 序列化成<b>一整块 JSON</b> 落在 workspace.xml 的
 * {@code <component name="PropertiesComponent">} 里。旧版本在后台线程写该服务，与平台保存竞争，
 * 把 {@code mpsql.visualizer.last.runner.id} 那条值的<b>开头引号</b>削掉了
 * （真机样本：{@code ..."id": 8d6c2a1b-…"}）。</p>
 *
 * <p>后果不是「这条记忆丢了」而是「整块 JSON 解析失败」——平台每次启动报
 * {@code JsonDecodingException: Expected quotation mark '"', but had '8'}，且<b>不会重写该条目</b>，
 * 等于用户工程文件被永久污染。写侧根因已修（写入归位 EDT），本文件负责<b>存量治愈</b>。</p>
 *
 * <p>修复策略＝<b>文本层定点手术</b>，不重建 JSON：只认本插件键前缀，别人的键一律不碰；
 * 值缺引号的保留载荷补回引号；载荷含引号/反斜杠/控制字符这类无法安全重建的，降级成空串
 * （宁可丢一条「上次用哪个数据源」的启发式记忆，也不能写出一块新的坏 JSON）；
 * 改完整块重新解析，<b>仍不合法就一个字节都不写</b>（说明别处也坏了，不归本插件管）。</p>
 *
 * <p>本文件<b>刻意不引平台类</b>：判定内核是纯字符串函数，测试因此落在 testFast 快桶。
 * master 侧为顶层声明集合，Java 侧按 k2j 惯例落 {@code WorkspaceJsonPropertyRepairKt} 门面类。</p>
 */
public final class WorkspaceJsonPropertyRepairKt {

    /** 本插件在项目级 PropertiesComponent 里使用的键前缀（与 DataSourceSelector 的三个常量对齐）。 */
    public static final String OWN_PROPERTY_KEY_PREFIX = "mpsql.visualizer.";

    /** workspace.xml 里承载项目级 PropertiesComponent 的组件标签。 */
    public static final String PROPERTIES_COMPONENT_OPEN_TAG = "<component name=\"PropertiesComponent\">";

    /** 组件闭合标签（JSON 内容里不可能出现该字面量）。 */
    public static final String PROPERTIES_COMPONENT_CLOSE_TAG = "</component>";

    private static final String QUOT = "&quot;";

    /**
     * 键行形态。{@code ^} 锚点在 Java 正则里默认不吃 {@code \r}（未开 UNIX_LINES），
     * 故尾随空白单列一组原样带走，保证 CRLF 工程文件修完仍是 CRLF。
     */
    private static final Pattern ENTRY = Pattern.compile(
            "^(\\s*)&quot;(" + Pattern.quote(OWN_PROPERTY_KEY_PREFIX) + ".*?)&quot;:(\\s*)(.*?)(,?)(\\s*)$",
            Pattern.DOTALL);

    private WorkspaceJsonPropertyRepairKt() {
    }

    /** 修复判定结果（Kotlin sealed class 的 Java 等价形态）。 */
    public static final class RepairOutcome {

        /** 单例形态：组件 JSON 本就合法 / 坏的不是本插件的键 / 整块仍不合法不落盘。 */
        public static final RepairOutcome HEALTHY = new RepairOutcome("Healthy", null, null);
        public static final RepairOutcome NO_OWN_DAMAGE = new RepairOutcome("NoOwnDamage", null, null);
        public static final RepairOutcome UNREPAIRABLE = new RepairOutcome("Unrepairable", null, null);

        private final String kind;
        @Nullable
        private final String text;
        @Nullable
        private final List<String> fixedKeys;

        private RepairOutcome(@NotNull String kind, @Nullable String text, @Nullable List<String> fixedKeys) {
            this.kind = kind;
            this.text = text;
            this.fixedKeys = fixedKeys;
        }

        public String getKind() {
            return kind;
        }

        public boolean isHealthy() {
            return this == HEALTHY;
        }

        public boolean isNoOwnDamage() {
            return this == NO_OWN_DAMAGE;
        }

        public boolean isUnrepairable() {
            return this == UNREPAIRABLE;
        }

        /** 修好且整块 JSON 已重新校验通过时的载体（kind=Repaired）。 */
        public static RepairOutcome repaired(@NotNull String text, @NotNull List<String> fixedKeys) {
            return new RepairOutcome("Repaired", text, List.copyOf(fixedKeys));
        }

        public boolean isRepaired() {
            return text != null;
        }

        /** 修复后的组件文本（kind=Repaired；其余形态恒 null）。 */
        @Nullable
        public String getText() {
            return text;
        }

        /** 本次修动的键（按出现顺序，供日志与气泡点名）。 */
        @NotNull
        public List<String> getFixedKeys() {
            return fixedKeys != null ? fixedKeys : List.of();
        }
    }

    /** {@code repairOneLine} 的产物：修真后的一行 + 被修的键。 */
    private record FixedLine(@NotNull String key, @NotNull String line) {
    }

    /**
     * 整文件级入口：定位 {@code <component name="PropertiesComponent">} 块并把修复结果拼回原文。
     *
     * <p>拼接只替换该块的<b>内容</b>，前后文（其它 component、标签、缩进）逐字节保留——
     * 工程文件是用户资产，除被修的那一行外不允许有任何字符变化。</p>
     *
     * @param raw workspace.xml 的完整文本（UTF-8 已还原）
     * @return {@code Repaired.text} 是<b>整文件</b>新文本
     */
    @NotNull
    public static RepairOutcome repairWorkspaceFileText(@NotNull String raw) {
        if (!raw.contains(OWN_PROPERTY_KEY_PREFIX)) {
            return RepairOutcome.HEALTHY;
        }
        int openIdx = raw.indexOf(PROPERTIES_COMPONENT_OPEN_TAG);
        if (openIdx < 0) {
            return RepairOutcome.HEALTHY;
        }
        int contentStart = openIdx + PROPERTIES_COMPONENT_OPEN_TAG.length();
        int closeIdx = raw.indexOf(PROPERTIES_COMPONENT_CLOSE_TAG, contentStart);
        if (closeIdx < 0) {
            return RepairOutcome.HEALTHY;
        }
        RepairOutcome inner = repairPropertiesComponentText(raw.substring(contentStart, closeIdx));
        if (inner.isRepaired()) {
            return RepairOutcome.repaired(
                    raw.substring(0, contentStart) + inner.getText() + raw.substring(closeIdx),
                    inner.getFixedKeys());
        }
        return inner;
    }

    /**
     * 对 {@code <component name="PropertiesComponent">} 的<b>内容</b>（XML 转义后的 JSON）做定点修复。
     *
     * <p>输入输出都是 workspace.xml 里的原始文本形态（含 {@code &quot;} 转义），调用方无需再做
     * 转义/还原，这样落盘时除被修的那几行外<b>逐字节不变</b>。</p>
     */
    @NotNull
    public static RepairOutcome repairPropertiesComponentText(@NotNull String escaped) {
        if (isPlatformLoadable(unescapeXml(escaped))) {
            return RepairOutcome.HEALTHY;
        }

        // split('\n') 把每行的行尾 '\r'（CRLF 工程文件）留在该行内，再用 '\n' 原样拼回即逐字节保真。
        List<String> fixedKeys = new ArrayList<>(2);
        String[] lines = escaped.split("\n", -1);
        boolean changed = false;
        String[] rebuilt = new String[lines.length];
        for (int i = 0; i < lines.length; i++) {
            FixedLine fixed = repairOneLine(lines[i]);
            if (fixed == null) {
                rebuilt[i] = lines[i];
            } else {
                fixedKeys.add(fixed.key());
                changed = true;
                rebuilt[i] = fixed.line();
            }
        }
        if (!changed) {
            return RepairOutcome.NO_OWN_DAMAGE;
        }

        String fixedText = String.join("\n", rebuilt);
        if (!isPlatformLoadable(unescapeXml(fixedText))) {
            return RepairOutcome.UNREPAIRABLE;
        }
        return RepairOutcome.repaired(fixedText, fixedKeys);
    }

    /**
     * 单行修复：命中 {@code <缩进>&quot;<本插件键>&quot;:<空白><值><逗号?><尾随空白>} 形态且值未成对
     * 加引号时，补回引号（或降级空串）。未命中/本就健康返回 null。
     */
    @Nullable
    private static FixedLine repairOneLine(@NotNull String line) {
        Matcher matcher = ENTRY.matcher(line);
        if (!matcher.matches()) {
            return null;
        }
        String indent = matcher.group(1);
        String key = matcher.group(2);
        String gap = matcher.group(3);
        String value = matcher.group(4);
        String comma = matcher.group(5);
        String tail = matcher.group(6);
        if (value.length() >= QUOT.length() * 2 && value.startsWith(QUOT) && value.endsWith(QUOT)) {
            return null;
        }

        // 两端引号可能缺任意一端（真机形态是缺开头），故两端各自剥一刀再判载荷是否可安全重建。
        String payload = value;
        if (payload.startsWith(QUOT)) {
            payload = payload.substring(QUOT.length());
        }
        if (payload.endsWith(QUOT)) {
            payload = payload.substring(0, payload.length() - QUOT.length());
        }
        payload = payload.trim();
        String rebuiltValue = isSafelyQuotable(payload) ? QUOT + payload + QUOT : QUOT + QUOT;
        return new FixedLine(key, indent + QUOT + key + QUOT + ":" + gap + rebuiltValue + comma + tail);
    }

    /**
     * 载荷能否安全地重新包成 JSON 字符串：不含裸引号、反斜杠、控制字符，
     * 也不含 {@code &quot;} 实体（那说明原值自带引号，边界已歧义）。
     */
    private static boolean isSafelyQuotable(@Nullable String payload) {
        if (payload == null || payload.isEmpty()) {
            return false;
        }
        for (int i = 0; i < payload.length(); i++) {
            char c = payload.charAt(i);
            if (c == '"' || c == '\\' || c < 0x20 || c == 0x7F) {
                return false;
            }
        }
        return !payload.contains(QUOT);
    }

    /**
     * 判定这份组件文本能否被<b>平台</b>加载成功。
     *
     * <p>关键坑（实测踩到）：{@code Json.parseToJsonElement} 对<b>未加引号的值是宽松的</b>——它把
     * {@code {"k": 8d6c…}} 解析成一个非字符串 JsonPrimitive 而不报错；平台走的却是
     * {@code BasePropertyService.MyState}（Map&lt;String, String&gt;）的<b>流式严格解码</b>，
     * 遇到裸值直接抛 JsonDecodingException。若拿 parseToJsonElement 的成败当判据，
     * 真机这个损坏形态会被判成「健康」，自愈永不触发（本文件首版即因此三例测试红）。</p>
     *
     * <p>所以这里按平台的<b>类型要求</b>校验：keyToString 的每个值必须是 JSON 字符串，
     * keyToStringList 的每个值必须是字符串数组。形态对得上，平台才不会再报那条错。</p>
     */
    public static boolean isPlatformLoadable(@Nullable String text) {
        try {
            if (text == null) {
                return false;
            }
            JsonElement rootElement = Json.Default.parseToJsonElement(text);
            if (!(rootElement instanceof JsonObject root)) {
                return false;
            }
            JsonElement stringsElement = root.get("keyToString");
            if (stringsElement == null) {
                return true;
            }
            if (!(stringsElement instanceof JsonObject strings)) {
                return false;
            }
            boolean stringsOk = true;
            for (JsonElement value : strings.values()) {
                if (!(value instanceof JsonPrimitive primitive) || !primitive.isString()) {
                    stringsOk = false;
                    break;
                }
            }
            boolean listsOk = true;
            JsonElement lists = root.get("keyToStringList");
            if (lists != null) {
                if (!(lists instanceof JsonObject listsObject)) {
                    listsOk = false;
                } else {
                    for (JsonElement value : listsObject.values()) {
                        if (!isStringArray(value)) {
                            listsOk = false;
                            break;
                        }
                    }
                }
            }
            return listsOk && stringsOk;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isStringArray(@Nullable JsonElement element) {
        if (!(element instanceof JsonArray array)) {
            return false;
        }
        for (JsonElement value : array) {
            if (!(value instanceof JsonPrimitive primitive) || !primitive.isString()) {
                return false;
            }
        }
        return true;
    }

    /** XML 实体还原（只覆盖 workspace.xml 里会出现的几种，含数字引用）。 */
    @NotNull
    static String unescapeXml(@NotNull String text) {
        if (!text.contains("&")) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '&') {
                int semi = text.indexOf(';', i + 1);
                if (semi > i && semi - i <= 12) {
                    String decoded = decodeEntity(text.substring(i + 1, semi));
                    if (decoded != null) {
                        out.append(decoded);
                        i = semi + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    @Nullable
    private static String decodeEntity(@NotNull String entity) {
        switch (entity) {
            case "quot":
                return "\"";
            case "apos":
                return "'";
            case "lt":
                return "<";
            case "gt":
                return ">";
            case "amp":
                return "&";
            default:
                Integer codePoint = null;
                if (entity.startsWith("#x") || entity.startsWith("#X")) {
                    try {
                        codePoint = Integer.valueOf(entity.substring(2), 16);
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                } else if (entity.startsWith("#")) {
                    try {
                        codePoint = Integer.valueOf(entity.substring(1));
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
                return codePointToString(codePoint);
        }
    }

    @Nullable
    private static String codePointToString(@Nullable Integer codePoint) {
        if (codePoint == null || !Character.isValidCodePoint(codePoint)) {
            return null;
        }
        try {
            return new String(Character.toChars(codePoint));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
