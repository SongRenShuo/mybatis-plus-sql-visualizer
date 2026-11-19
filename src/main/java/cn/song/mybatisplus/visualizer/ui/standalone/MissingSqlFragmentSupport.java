// T-SyncK2JMaster180 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/standalone/MissingSqlFragmentSupport.kt 移植
package cn.song.mybatisplus.visualizer.ui.standalone;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 缺失 {@code <include refid>} 片段的检测与补齐（T-IncludePlaceholder A 券）。
 *
 * <h2>背景</h2>
 *
 * <p>用户只贴「语句」不贴「被引用的 {@code <sql id>} 片段」时，两代链都只能丢弃 include：
 * 渲染出来是 {@code SELECT} 后面直接接 {@code FROM} 的残句（2026-11-18 用户截图）。渲染侧已改为留一条
 * 「TODO 缺失片段」注释占位，本条补的是「让用户把它补上」的入口。</p>
 *
 * <h2>判定口径</h2>
 *
 * <ul>
 *  <li>引用 = 文本里 {@code <include refid="X">} 的 X 集合；</li>
 *  <li>定义 = 文本里 {@code <sql id="X">} 的 X 集合；</li>
 *  <li>缺失 = 引用 − 定义。{@code refid} 可能带 namespace 前缀（{@code ns.id}），而定义常写成裸 {@code id}，
 *      故两边都按「末段名」再对一次，避免把「其实定义过」误报成缺失。</li>
 * </ul>
 *
 * <p>纯逻辑、零平台依赖（可直接进快桶单测）。</p>
 */
public final class MissingSqlFragmentSupport {

    private MissingSqlFragmentSupport() {
    }

    private static final Pattern INCLUDE_REFID = Pattern.compile(
        "<include\\s+[^>]*refid\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private static final Pattern SQL_ID = Pattern.compile(
        "<sql\\s+[^>]*id\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    /** 引用但未定义的片段 id（排序稳定，便于 UI 文案与断言）。 */
    public static List<String> missingRefids(String text) {
        if (text.isBlank() || !containsIgnoreCase(text, "include")) {
            return List.of();
        }
        Set<String> referenced = collectGroups(INCLUDE_REFID, text);
        if (referenced.isEmpty()) {
            return List.of();
        }
        Set<String> defined = collectGroups(SQL_ID, text);
        Set<String> definedLeaf = new HashSet<>();
        for (String id : defined) {
            definedLeaf.add(leafName(id));
        }
        List<String> missing = new ArrayList<>();
        for (String refid : referenced) {
            if (!defined.contains(refid) && !definedLeaf.contains(leafName(refid))) {
                missing.add(refid);
            }
        }
        missing.sort(java.util.Comparator.naturalOrder());
        return missing;
    }

    /**
     * 从一段 XML 文本里抽出指定 {@code <sql id="refid">} 的<b>内部内容</b>（找不到返回 null）。
     *
     * <p>用途：用户在「补齐片段」框里选一个 mapper 文件时，通常只想拿那一段，而不是把整个文件
     * 倒进输入框（整段倒进去会和用户已贴的语句重复、还可能触发重复声明告警）。</p>
     */
    @Nullable
    public static String extractFragment(String text, String refid) {
        if (text.isBlank()) {
            return null;
        }
        String id = Pattern.quote(refid);
        Pattern regex = Pattern.compile(
            "<sql\\s+[^>]*id\\s*=\\s*[\"']" + id + "[\"'][^>]*>([\\s\\S]*?)</sql>",
            Pattern.CASE_INSENSITIVE);
        Matcher matcher = regex.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String content = matcher.group(1).trim();
        return content.isEmpty() ? null : content;
    }

    /**
     * 把用户补齐的片段拼到待解析文本之前。
     *
     * <p>拼在<b>前面</b>的理由：两代解析器都按「根元素下的 {@code <sql>} 片段表」收集，片段先于语句声明
     * 不受顺序影响；而用户补的内容若已是完整的 {@code <sql …>…</sql>} 则原样拼入（不二次包壳）。</p>
     */
    public static String withFragments(String text, Map<String, String> fragments) {
        if (fragments.isEmpty()) {
            return text;
        }
        Map<String, String> ordered = new LinkedHashMap<>(fragments);
        StringBuilder block = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : ordered.entrySet()) {
            if (!first) {
                block.append('\n');
            }
            first = false;
            String body = entry.getValue();
            String trimmed = body.stripLeading();
            if (trimmed.regionMatches(true, 0, "<sql", 0, 4)) {
                block.append(body);
            } else {
                block.append("<sql id=\"").append(entry.getKey()).append("\">").append(body).append("</sql>");
            }
        }
        return block + "\n" + text;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 正则全量捕获组 1 收集（{@code findAll().map { groupValues[1] }.toSet()} 等价）。 */
    private static Set<String> collectGroups(Pattern pattern, String text) {
        Set<String> out = new HashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
        return out;
    }

    /** 末段名（{@code ns.id} → {@code id}；无点号原样，{@code substringAfterLast('.')} 等价）。 */
    private static String leafName(String id) {
        int dot = id.lastIndexOf('.');
        return dot < 0 ? id : id.substring(dot + 1);
    }

    private static boolean containsIgnoreCase(String text, String needle) {
        return text.toLowerCase(Locale.ROOT).contains(needle);
    }
}
