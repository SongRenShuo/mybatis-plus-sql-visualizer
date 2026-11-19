package cn.song.mybatisplus.visualizer.log;

import java.util.regex.Pattern;

/**
 * 执行耗时解析器（O-1 Phase B，纯逻辑，可独立单测）：从日志文本中解析执行耗时（毫秒）。
 *
 * 支持格式（正则匹配，< 1ms）：
 * - **p6spy**：`elapsed 123 ms` / `consumed 456ms`
 * - **MP TimeCount**：`TimeCount: 789ms` / `time: 100 ms`
 * - **druid**：`executeMillis:123` / `"executeMillis":123`
 * - **MP ExecuteTime**：`ExecuteTime: 456ms`
 * - **行尾纯数字 + ms**：`select ... ] 789ms`（通用兜底）
 * - **自定义前缀**：通过 `logTimePrefix` 配置（如 `Time:`）+ 数字 + `ms`
 *
 * 解析策略：先尝试通用格式（p6spy/TimeCount/druid/MP），再尝试自定义前缀。都不命中返回 null。
 */
public final class ExecutionTimeParser {

    public static final ExecutionTimeParser INSTANCE = new ExecutionTimeParser();

    private ExecutionTimeParser() {
    }

    /** p6spy elapsed/consumed 格式：`elapsed 123 ms` / `consumed 456ms`（数字前后可有空格，ms 可选）。 */
    private static final Pattern P6SPY_REGEX = Pattern.compile("(?i)\\b(?:elapsed|consumed)\\s+(\\d+)\\s*ms?\\b");

    /** MP TimeCount 格式：`TimeCount: 789ms` / `time: 100 ms`。 */
    private static final Pattern TIMECOUNT_REGEX = Pattern.compile("(?i)\\bTimeCount\\s*[:=]\\s*(\\d+)\\s*ms?\\b");

    /** 通用 `time` 关键字格式：`time: 100 ms` / `time 200ms`。 */
    private static final Pattern GENERIC_TIME_REGEX = Pattern.compile("(?i)\\btime\\s*[:=]?\\s+(\\d+)\\s*ms?\\b");

    /** druid 格式：`executeMillis:123` / `"executeMillis":123`。 */
    private static final Pattern DRUID_REGEX = Pattern.compile("(?i)executeMillis[\"']?\\s*[:=]\\s*(\\d+)");

    /** MP ExecuteTime 格式：`ExecuteTime: 456ms` / `ExecuteTime=789`。 */
    private static final Pattern MP_EXECUTE_TIME_REGEX = Pattern.compile("(?i)\\bExecuteTime\\s*[:=]\\s*(\\d+)\\s*ms?\\b");

    /** 行尾纯数字 + ms 兜底：行尾 ` 123ms` 或 `] 456ms`（避免误匹配 SQL 内数字）。 */
    private static final Pattern TRAILING_MS_REGEX = Pattern.compile("[\\]\\s]\\s*(\\d+)\\s*ms\\s*$", Pattern.MULTILINE);

    /**
     * 自定义前缀正则缓存——键为前缀原文（用户配置串，天然有界），每条 SQL 不再动态编译。
     *
     * <p>T-PerfSweep30 05a：加显式上限（同族 countCache 形态——超限整体清空）。键域是「用户配置的
     * 耗时前缀」，通常个位数；但配置可被脚本反复改写，无上限的 CHM 在长跑 IDE 里没有回收路径。
     * 清空代价 = 下次重编译一条 Regex（微秒级）。</p>
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Pattern> CUSTOM_PREFIX_REGEX_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final int CUSTOM_PREFIX_CACHE_MAX = 64;

    private static Pattern customPrefixRegex(String customPrefix) {
        if (CUSTOM_PREFIX_REGEX_CACHE.size() >= CUSTOM_PREFIX_CACHE_MAX) CUSTOM_PREFIX_REGEX_CACHE.clear();
        return CUSTOM_PREFIX_REGEX_CACHE.computeIfAbsent(customPrefix,
                p -> Pattern.compile("(?i)" + Pattern.quote(p) + "\\s*(\\d+)\\s*ms?\\b"));
    }

    /**
     * 从日志文本中解析执行耗时（毫秒）。
     *
     * 解析顺序：p6spy → TimeCount → 通用 time 格式 → druid → MP ExecuteTime → 行尾兜底 → 自定义前缀。
     *
     * @param text         日志文本（可能含多行）
     * @param customPrefix 自定义耗时前缀（如 `Time:`）；null 时跳过自定义匹配
     * @return 耗时毫秒；无法解析返回 null
     */
    public static Long parse(String text) {
        return parse(text, null);
    }

    /**
     * 从日志文本中解析执行耗时（毫秒）。
     *
     * @param text         日志文本（可能含多行）
     * @param customPrefix 自定义耗时前缀（如 `Time:`）；null 时跳过自定义匹配
     * @return 耗时毫秒；无法解析返回 null
     */
    public static Long parse(String text, String customPrefix) {
        if (text.isBlank()) {
            return null;
        }

        // 1. p6spy elapsed/consumed
        java.util.regex.Matcher m1 = P6SPY_REGEX.matcher(text);
        if (m1.find()) {
            return toLongOrNull(m1.group(1));
        }
        // 2. MP TimeCount
        java.util.regex.Matcher m2 = TIMECOUNT_REGEX.matcher(text);
        if (m2.find()) {
            return toLongOrNull(m2.group(1));
        }
        // 3. 通用 time 格式
        java.util.regex.Matcher m3 = GENERIC_TIME_REGEX.matcher(text);
        if (m3.find()) {
            return toLongOrNull(m3.group(1));
        }
        // 4. druid executeMillis
        java.util.regex.Matcher m4 = DRUID_REGEX.matcher(text);
        if (m4.find()) {
            return toLongOrNull(m4.group(1));
        }
        // 5. MP ExecuteTime
        java.util.regex.Matcher m5 = MP_EXECUTE_TIME_REGEX.matcher(text);
        if (m5.find()) {
            return toLongOrNull(m5.group(1));
        }
        // 6. 行尾纯数字 + ms 兜底
        java.util.regex.Matcher m6 = TRAILING_MS_REGEX.matcher(text);
        if (m6.find()) {
            return toLongOrNull(m6.group(1));
        }
        // 7. 自定义前缀（T-PerfSweep F2：正则按前缀缓存，捕捉热路径不再每条动态编译）
        if (customPrefix != null && !customPrefix.isBlank()) {
            java.util.regex.Matcher m7 = customPrefixRegex(customPrefix).matcher(text);
            if (m7.find()) {
                return toLongOrNull(m7.group(1));
            }
        }
        return null;
    }

    private static Long toLongOrNull(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
