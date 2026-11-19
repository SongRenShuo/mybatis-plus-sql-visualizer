package cn.song.mybatisplus.visualizer.model;

import cn.song.mybatisplus.visualizer.sql.PreQuotedLiteral;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * R92: explicit, literal-only preview overlay. This is NOT a MetaObjectHandler emulator:
 * no user class loading, scripts, clock, account lookup or implicit column selection.
 */
public final class FieldFillSimulation {

    public static final String SNAPSHOT_PREFIX = "__field_fill__:";

    // Deliberately do not persist the enable switch: every new preview starts disabled.
    private static final Pattern NUMBER =
            Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");

    private FieldFillSimulation() {
    }

    /** 模拟值覆盖层：index → 替换字面量；invalidSources = 未通过校验的来源（保留占位符不注入）。 */
    public record Overlay(Map<Integer, String> literals, Set<String> invalidSources) {
    }

    /** Null means invalid/unprovided; callers retain the placeholder rather than raw-inserting input. */
    public static String literal(String value, Boolean quote) {
        if (value == null || value.isBlank() || hasForbiddenChar(value)) {
            return null;
        }
        String trimmed = value.trim();
        // T-PreQuotedLiteral：用户已手工加引号的值原样（放在安全闸门之后——分号/反斜杠拒绝语义不变）
        if (!Boolean.FALSE.equals(quote) && PreQuotedLiteral.isComplete(trimmed)) {
            return trimmed;
        }
        if (Boolean.FALSE.equals(quote)) {
            return NUMBER.matcher(trimmed).matches() ? trimmed : null;
        }
        return "'" + trimmed.replace("'", "''") + "'";
    }

    private static boolean hasForbiddenChar(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ';' || c == '\\' || Character.isISOControl(c)) {
                return true;
            }
        }
        return false;
    }

    public static Overlay overlay(BatchModel model, Set<String> active, Map<String, String> simulationValues,
                                  boolean enabled) {
        if (!enabled) {
            return new Overlay(Map.of(), Set.of());
        }
        // T-FieldFillPriority（用户真机反馈）：UPSERT 的运行期分支不可知，仍不模拟（UI 文案已披露）；
        // INSERT/UPDATE 下用户手填的模拟值是显式意图，不再按字段 fill 策略过滤——面板对所有 fill
        // 字段都提供模拟行并置灰普通输入（tooltip「已由模拟值覆盖」），按 fill 过滤会让模拟值被
        // 静默忽略、SQL 回落灰框值（UPDATE 预览里的 [fill=INSERT] 字段即此形态）。
        if (model.getKind() == BatchKind.UPSERT) {
            return new Overlay(Map.of(), Set.of());
        }
        Map<Integer, String> literals = new LinkedHashMap<>();
        Set<String> invalid = new LinkedHashSet<>();
        List<BatchColumn> ordered = model.orderedColumns(active);
        for (int index = 0; index < ordered.size(); index++) {
            BatchColumn col = ordered.get(index);
            // Fixed columns belong to WHERE (id/version), never to the write overlay.
            boolean writableIndex;
            if (model.getKind() == BatchKind.UPDATE) {
                long activeToggleCount = model.getToggleColumns().stream()
                        .filter(c -> active.contains(c.getColumnName()))
                        .count();
                writableIndex = index < activeToggleCount;
            } else if (model.getKind() == BatchKind.INSERT) {
                writableIndex = index >= model.getFixedColumns().size();
            } else {
                writableIndex = false;
            }
            if (!writableIndex || !active.contains(col.getColumnName())) {
                continue;
            }
            // 模拟值优先：非空即覆盖普通参数（普通输入已置灰并给出覆盖提示）。
            String raw = simulationValues.get(col.getSource());
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            String lit = literal(raw, col.getQuote());
            literals.put(index, lit != null ? lit : "?");
            if (lit == null) {
                invalid.add(col.getSource());
            }
        }
        return new Overlay(literals, invalid);
    }
}
