package cn.song.mybatisplus.visualizer.log.agent;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T-CaptureCatchAll 券03：agent 侧 <b>attach 覆盖自检</b> 计数的 IDE 侧镜像。
 *
 * <p>立项动因：agent 的 {@code retransformLoadedDrivers} 只在宿主 stderr 打一行 {@code matched=N wove=M …}，
 * IDE 侧完全看不到——白名单漏一个驱动时，用户视角是「插件坏了」，面板连一句「本次 attach
 * 命中 0 个驱动」都说不出来（调研报告 §1.2「看着支持、一条不出」的静默来源）。</p>
 *
 * <p>传输约定：agent 把计数放进任意<b>入站帧 body</b> 的 JSON 键（C1 路当前挂在心跳 body：
 * {@code {"weavable":…,"woven":…,"failed":…,"matchedByInterface":…,"interfaceWoven":…,"skippedByVersion":…,"catchAll":…}}）。
 * 本侧解析刻意做成<b>宽容扫描</b>（只认 {@code "key":数字} 形态，不要求整体是合法 JSON、不要求键序）：
 * <ul>
 * <li>旧版 agent 的 body（{@code {}} 或事件 JSON）不含这些键 → {@link #parse} 返回 null，零行为变化；</li>
 * <li>新键（如 C1 后续再加计数）无需改本侧即可忽略；</li>
 * <li>任何解析异常都被吞掉——诊断计数绝不能影响事件主链（红线：失败不影响既有采集）。</li>
 * </ul></p>
 *
 * <p>口径：数值为 agent 侧<b>单调累计</b>的最近一次上报值（每次上报整体覆盖，不做加法——重连/重附后
 * agent 侧计数从零起算，加法会把上一轮的值带进来）。</p>
 *
 * <p>注意 {@code matched} 与 {@code weavable} 的关系：attach 循环的 {@code matched=N} 目前只打宿主 stderr，心跳里
 * 未携带；心跳的 {@code weavable} 是 transformer 入口命中数（同一批类的另一视角）。故「零命中」判据
 * 取两者较大值 {@link #matchedTotal()}，避免「心跳只有 weavable 却报命中 0」的假事实。</p>
 */
public record AgentAttachStats(
        /** 白名单 + 接口兜底合计命中的驱动类数（agent 侧 {@code matched}；心跳可能不携带）。 */
        long matched,
        /** transformer 入口可织类数（agent 侧 {@code weavable}）。 */
        long weavable,
        /** 真正产出新字节的类数（agent 侧 {@code woven}）。 */
        long woven,
        /** 仅由接口兜底（白名单未命中）命中的类数——C1 路新增；缺省 0 表示旧版 agent 未上报。 */
        long matchedByInterface,
        /** 接口兜底命中里真正织入的类数（C1 路 {@code interfaceWoven}）。 */
        long interfaceWoven,
        /** retransform 失败的类数（JVM 拒绝/版本不支持等）。 */
        long failed,
        /** 不可重转换（{@code isModifiableClass} 为假）的类数。 */
        long unmodifiable,
        /** 类文件 major 版本闸挡下的类数（C1 路 {@code skippedByVersion}）——「命中了但没织」的一条硬原因。 */
        long skippedByVersion,
        /** 兜底状态码（C1 路 {@code catchAll}）：0=纯白名单，1=transformer 兜底开，2=已熔断。 */
        int catchAll
) {

    /** 命中类数（{@code matched} 与 {@code weavable} 取大——心跳只带后者时的零命中判据）。 */
    public long matchedTotal() {
        return Math.max(matched, weavable);
    }

    /** 本次 attach 是否零命中——「增强捕获本会话无事件来源」的硬事实。 */
    public boolean zeroMatched() {
        return matchedTotal() <= 0L;
    }

    /** 是否有任何一类织入产物（transformer 白名单织入 + 接口兜底织入）。 */
    public boolean anyWoven() {
        return woven > 0L || interfaceWoven > 0L;
    }

    /** transformer 兜底已触发 kill switch（C1 路熔断态）。 */
    public boolean catchAllKilled() {
        return catchAll == 2;
    }

    /** 单键匹配：{@code "matched":12}（允许冒号后空白；值域只认整数，防止把 JSON 字符串误读成数字）。 */
    private static final Pattern KEY_VALUE = Pattern.compile(
            "\"(matched|matchedByInterface|weavable|woven|failed|unmodifiable|interfaceWoven|skippedByVersion|catchAll)\"\\s*:\\s*(-?\\d{1,15})");

    /** 单帧 body 上限：attach 计数行是短 JSON；超长 body（事件/堆栈）直接跳过，避免无谓扫描。 */
    private static final int MAX_BODY_CHARS = 8 * 1024;

    /**
     * 一次上报里至少要有一个已知键才认；否则返回 null（事件帧/旧心跳形态一律不认）。
     */
    public static AgentAttachStats parse(String body) {
        if (body == null || body.isEmpty() || body.length() > MAX_BODY_CHARS) return null;
        // 廉价前置门：事件帧（含 SQL 文本）逐帧跑正则不值得；只对含 attach 计数字样的 body 解析。
        if (!body.contains("\"matched\"") && !body.contains("\"weavable\"")
                && !body.contains("\"matchedByInterface\"")) {
            return null;
        }
        Map<String, Long> values = new HashMap<>(8);
        Matcher m = KEY_VALUE.matcher(body);
        while (m.find()) {
            // 同名键重复时以最后一次为准（agent 侧同一 body 不应重复；重复即畸形，取最新无害）
            Long parsed = toLongOrNull(m.group(2));
            if (parsed != null) {
                values.put(m.group(1), parsed);
            }
        }
        if (values.isEmpty()) return null;
        return new AgentAttachStats(
                values.getOrDefault("matched", 0L),
                values.getOrDefault("weavable", 0L),
                values.getOrDefault("woven", 0L),
                values.getOrDefault("matchedByInterface", 0L),
                values.getOrDefault("interfaceWoven", 0L),
                values.getOrDefault("failed", 0L),
                values.getOrDefault("unmodifiable", 0L),
                values.getOrDefault("skippedByVersion", 0L),
                values.getOrDefault("catchAll", 0L).intValue());
    }

    /** Kotlin {@code String.toLongOrNull} 等价（非数值/溢出 → null）。 */
    private static Long toLongOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
