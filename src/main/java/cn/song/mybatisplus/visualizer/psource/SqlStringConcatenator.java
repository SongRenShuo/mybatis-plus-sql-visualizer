package cn.song.mybatisplus.visualizer.psource;

import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.sql.RuntimeSlot;
import cn.song.mybatisplus.visualizer.sql.SlotKind;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiBinaryExpression;
import com.intellij.psi.PsiConditionalExpression;
import com.intellij.psi.PsiDoWhileStatement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiForStatement;
import com.intellij.psi.PsiForeachStatement;
import com.intellij.psi.PsiIfStatement;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiPolyadicExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiSwitchStatement;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.PsiWhileStatement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * SQL 字符串拼接聚合器（JDBC 模板 SQL 字符串预览）：把分散在字面量 `+` 拼接 /
 * StringBuilder 分片 / 变量引用中的 SQL 片段聚合为单一文本。
 *
 * <p>两路实现：</p>
 * <ul>
 *  <li>路径 A — 字面量 `+` 拼接（{@code "SELECT " + "..."}`）→ 递归收集 {@code PsiLiteralExpression} 并 join</li>
 *  <li>路径 B — StringBuilder 聚合（{@code new StringBuilder().append(...)} 或
 *      {@code StringBuilder sql = new StringBuilder(); sql.append(querySelect)...}）→
 *      收集同一方法体内对目标变量的 {@code append} 调用（按 offset 排序），递归聚合每个实参</li>
 * </ul>
 *
 * <p>变量引用沿 initializer 递归追踪（不跨方法体），含运行时值时标记 {@code complete=false}
 * 并在结果中保留占位标记。</p>
 *
 * <p>零 resolve（仅结构遍历 + initializer 读取），单测可注入假 PSI。</p>
 */
public final class SqlStringConcatenator {

    /** Kotlin object 单例兼容入口。 */
    public static final SqlStringConcatenator INSTANCE = new SqlStringConcatenator();

    private SqlStringConcatenator() {
    }

    /** 字符串聚合结果。 */
    public record ConcatenationResult(
            /** 聚合后的 SQL 文本（含可能残留的运行时值占位标记）。 */
            String sql,
            /** true = 完全静态可聚合；false = 含运行时值（预览降级）。 */
            boolean complete,
            /**
             * T-RuntimeSlotFill 券 A：本次聚合产出的<b>可回填</b>运行期占位，按产物中首次出现顺序。
             *
             * <p>只收可填值的占位（{@link SlotKind#VALUE} / {@link SlotKind#LOOP} / {@link SlotKind#TERNARY} /
             * {@link SlotKind#OPTIONAL}）。纯说明性注释（{@code 条件追加，可能不出现}、{@code 互斥分支追加}、
             * {@code 回调内追加} 等）不是可填值对象——它们标注的是「那段内容在不在」，本身没有可填的值，
             * 因此不带 id、也不进本表。</p>
             */
            List<RuntimeSlot> runtimeSlots
    ) {

        /** Kotlin 默认参数（runtimeSlots=空表）等价重载：既有构造点与调用点零改动。 */
        public ConcatenationResult(String sql, boolean complete) {
            this(sql, complete, List.of());
        }
    }

    /**
     * 运行时值占位标记前缀（预览 warning 与 SQL 中保留位置）。
     *
     * <p>R1：取值带空格与冒号，配合 {@code DYNAMIC_PLACEHOLDER_SUFFIX} 的收尾构成<b>单条自洽块注释</b>。</p>
     */
    public static final String DYNAMIC_PLACEHOLDER_PREFIX = "/* 运行时值: ";

    /** 占位注释收尾（与 {@link #DYNAMIC_PLACEHOLDER_PREFIX} 构成单条自洽块注释，R1）。 */
    private static final String DYNAMIC_PLACEHOLDER_SUFFIX = " */";

    /**
     * T-RuntimeSlotFill 券 A：占位身份标记的分隔符，产物形如 {@code 运行时值: <摘要> · rt3}。
     *
     * <p>身份写在注释文本里而不是 char range：格式化器会重排空白、把注释挪到独立行（实测见
     * {@code RuntimeSlotTest} 的 P-1 用例），字符区间必然失效，而注释<b>文本</b>被逐字保留。</p>
     */
    private static final String SLOT_ID_SEPARATOR = " · ";

    /** T-RuntimeSlotFill 券 A：占位 id 前缀（按产物首次出现顺序 {@code rt1}..{@code rtN}）。 */
    private static final String SLOT_ID_PREFIX = "rt";

    /** 占位文本长度上限（R1.4：超出截断加 {@code …}，避免整段三元把 SQL 撑爆）。 */
    private static final int DYNAMIC_PLACEHOLDER_MAX_LENGTH = 120;

    /** R3：循环体内追加的降级占位说明（运行期重复 N 次，线性拼接必错）。摘要取不到时回落本条。 */
    private static final String LOOP_APPEND_LABEL = "循环内追加，运行时重复执行";

    /** P1：循环占位带模板摘要时的前缀（后接清洗后的模板摘要）。 */
    private static final String LOOP_APPEND_SUMMARY_PREFIX = "循环内追加 ";

    /** P1：循环占位带模板摘要时的后缀（摘要取不到时整条回落 {@link #LOOP_APPEND_LABEL}，不加本后缀）。 */
    private static final String LOOP_APPEND_SUMMARY_SUFFIX = "（运行时重复）";

    /** P1：模板摘要里「非字符串字面量片段」（变量引用 / 方法调用 / 任意表达式）的替代符。 */
    private static final String TEMPLATE_ELLIPSIS = "…";

    /** P1：三元两支模板的分隔符——运行期只走一支，接起来会读成「两段都在」。 */
    private static final String TEMPLATE_BRANCH_SEPARATOR = " / ";

    /**
     * P1：模板摘要长度预算。
     *
     * <p>摘要先按本上限截断，再拼前后缀——保证整条占位不超 {@link #DYNAMIC_PLACEHOLDER_MAX_LENGTH}，
     * 否则外层清洗的长度上限会把 {@code （运行时重复）} 从尾巴上截掉（那正是这条摘要的意义所在）。</p>
     */
    private static final int TEMPLATE_SUMMARY_MAX_LENGTH =
            DYNAMIC_PLACEHOLDER_MAX_LENGTH - LOOP_APPEND_SUMMARY_PREFIX.length() - LOOP_APPEND_SUMMARY_SUFFIX.length();

    /** R3：if/else 互斥两支都对同一 builder 追加时的降级占位说明。 */
    private static final String PAIRED_APPEND_LABEL = "互斥分支追加，运行时只取一支";

    /** R3：无 else 的条件追加 — 保留片段，前面插本条说明注释。 */
    private static final String CONDITIONAL_APPEND_NOTE = "/* 运行时值: 条件追加，可能不出现 */";

    /** R3：switch 分支内追加 — 保留片段，前面插本条说明注释。 */
    private static final String SWITCH_APPEND_NOTE = "/* 运行时值: 分支追加，可能不出现 */";

    /** R3：lambda / 回调内追加 — 执行时机与次数由调用方决定。 */
    private static final String LAMBDA_APPEND_NOTE = "/* 运行时值: 回调内追加，执行时机不定 */";

    /** 变量 initializer 追踪最大深度（防循环引用）。 */
    private static final int MAX_TRACE_DEPTH = 5;

    /** 跟随「源码可见方法返回值」的最大层数（T-HqlHelperReturn：防递归/环）。 */
    private static final int MAX_METHOD_FOLLOW_DEPTH = 3;

    /** 占位文本清洗用的空白折叠（Kotlin {@code Regex("\\s+")} 等价）。 */
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /**
     * 一次顶层聚合的占位草稿会话（线程内有效，见 {@link #concat} 的入口/出口配对）。
     *
     * <p>为什么用会话而不是逐层返回：id 要按<b>产物中首次出现顺序</b>分配，而占位是在递归深处造出来的；
     * 草稿先入会话，顶层出口再统一分配 id 并把 {@code  · rtN} 写回文本（{@link #finalizeSlots}）。</p>
     */
    private static final class SlotSession {
        /** 待分配 id 的占位草稿，入表顺序 = 产物中的出现顺序。 */
        final List<DraftSlot> drafts = new ArrayList<>();
    }

    /**
     * 一条待分配 id 的占位草稿。
     *
     * @param baseComment 产物里<b>不带 id</b> 的精确注释文本（分配 id 时按它 + 出现序号定位）
     * @param kind        语义类别
     * @param expression  面板显示的表达式摘要（已清洗）
     * @param template    展开模板；null = 展开形态未知
     */
    private record DraftSlot(String baseComment, SlotKind kind, String expression, String template) {
    }

    /** 当前线程的占位草稿会话；非 null 表示正处在一次顶层聚合内（递归层共享同一会话）。 */
    private static final ThreadLocal<SlotSession> SLOT_SESSION = new ThreadLocal<>();

    /**
     * 聚合表达式为 SQL 文本。
     *
     * <p>顶层调用负责「开会话 → 聚合 → 分配占位 id → 关会话」；递归层共享同一会话，
     * 不重复分配 id（否则同一产物里会出现多个 {@code rt1}）。</p>
     *
     * @param expr SQL 字符串实参表达式（字面量 / 拼接 / 变量引用 / StringBuilder 变量）
     * @return 聚合结果；无法聚合返回 {@code null}
     */
    public static ConcatenationResult concat(PsiExpression expr) {
        if (expr == null) return null;
        if (SLOT_SESSION.get() != null) return concatExpression(expr);
        SlotSession session = new SlotSession();
        SLOT_SESSION.set(session);
        try {
            ConcatenationResult raw = concatExpression(expr);
            if (raw == null) return null;
            return finalizeSlots(raw, session);
        } finally {
            SLOT_SESSION.remove();
        }
    }

    /** 递归层的聚合分派（不带会话管理，见 {@link #concat}）。 */
    private static ConcatenationResult concatExpression(PsiExpression expr) {
        // 括号只是书写形态，不是语义：`"A" + (cond ? x : y) + "B"` 里的操作数是括号包着的三元。
        // 不剥括号就认不出三元（会被当成普通运行时值），剥了之后聚合交给内层表达式，
        // 但**占位文本仍用外层原文**——括号在既有产物里是保留的，去掉会动手写回来的那段文本。
        PsiExpression stripped = PsiUtil.skipParenthesizedExprDown(expr);
        if (stripped != null && stripped != expr) {
            if (stripped instanceof PsiConditionalExpression) {
                return concatConditional((PsiConditionalExpression) stripped, expr.getText());
            }
            return concatExpression(stripped);
        }
        if (expr instanceof PsiLiteralExpression) {
            return concatLiteral((PsiLiteralExpression) expr);
        }
        if (expr instanceof PsiConditionalExpression) {
            return concatConditional((PsiConditionalExpression) expr);
        }
        if (expr instanceof PsiBinaryExpression || expr instanceof PsiPolyadicExpression) {
            return concatPolyadic(expr);
        }
        if (expr instanceof PsiReferenceExpression) {
            return concatReference((PsiReferenceExpression) expr);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // R1 — 运行时值占位（单条自洽块注释）
    // ------------------------------------------------------------------

    /**
     * 生成一条<b>自洽的</b>运行时值占位注释（R1）：块注释起始符 + {@code  运行时值: } + 清洗后文本 + 空格 + 块注释结束符。
     *
     * <p>输出必须是一条完整块注释 —— 不允许出现「注释先闭合 + 源码裸露 + 悬空收尾」的形态
     * （旧实现把前缀写成当场闭合的注释、源码原样跟在后面、末尾再补一个结束符，
     * 正是用户截图里右括号丢失、引号错位的根因，见开发计划 §2.2）。</p>
     *
     * <p>清洗规则（缺一不可，见开发计划 R1.1）：</p>
     * <ol>
     *  <li>注释结束符 → {@code * /}：否则注释提前闭合，后面的源码变成活 SQL；</li>
     *  <li>单/双引号 → 同形安全字符（{@code ’} / {@code ”}）：占位可能落在字符串字面量内部，原文引号会截断字符串；</li>
     *  <li>空白（换行 / 制表 / 连续空格）折叠为单空格：多行注释会打乱格式化器的行结构；</li>
     *  <li>长度上限 {@link #DYNAMIC_PLACEHOLDER_MAX_LENGTH}，超出截断并加 {@code …}。</li>
     * </ol>
     *
     * @param exprText 静态不可求值的表达式原文
     */
    private static String dynamicPlaceholder(String exprText) {
        return DYNAMIC_PLACEHOLDER_PREFIX + sanitizePlaceholderText(exprText) + DYNAMIC_PLACEHOLDER_SUFFIX;
    }

    /**
     * 生成一条<b>可回填</b>的运行时值占位注释，并在当前会话登记草稿（T-RuntimeSlotFill 券 A）。
     *
     * <p>产物文本与 {@link #dynamicPlaceholder} 逐字相同（不带 id）；id 由顶层出口统一分配并写回
     * （{@link #finalizeSlots}），因此「不填任何值」时的 SQL 与既有行为只在 {@code  · rtN} 这一处不同。</p>
     *
     * @param rawComment 进注释的摘要原文（本方法内清洗）
     * @param kind       显式类别；null = 单值 {@link SlotKind#VALUE}（可选片段/循环/三元由调用方显式传）
     * @param expression 面板显示的表达式摘要
     * @param template   显式模板；null = 展开形态未知（不猜）
     */
    private static String slotPlaceholder(String rawComment, SlotKind kind, String expression, String template) {
        String summary = sanitizePlaceholderText(rawComment);
        String baseComment = DYNAMIC_PLACEHOLDER_PREFIX + summary + DYNAMIC_PLACEHOLDER_SUFFIX;
        SlotSession session = SLOT_SESSION.get();
        if (session != null) {
            session.drafts.add(new DraftSlot(
                    baseComment,
                    kind != null ? kind : SlotKind.VALUE,
                    sanitizePlaceholderText(expression),
                    template));
        }
        return baseComment;
    }

    /** 便捷形态：kind=VALUE、expression=rawComment、template=null（对应 Kotlin 默认参）。 */
    private static String slotPlaceholder(String rawComment) {
        return slotPlaceholder(rawComment, null, rawComment, null);
    }

    // ------------------------------------------------------------------
    // T-RuntimeSlotFill 券 A — 占位 id 与回填语义
    // ------------------------------------------------------------------

    /**
     * 给草稿分配 id 并写回产物（会话的出口）。
     *
     * <p>定位方式：按草稿的<b>无 id 注释文本</b>在产物里顺序查找（{@code indexOf(from)}，游标只前进不后退）。
     * 只前进是有意的，它兜住两类「草稿多出来」的情形：</p>
     * <ul>
     *  <li>三元两支文本比较后只留一支、{@code concatMethodReturn} 多返回比较后丢弃、聚合整体为空的中间层——
     *      这些子树算出的草稿所对应的文本<b>没进产物</b>，{@code indexOf} 找不到就跳过；</li>
     *  <li>同一段注释文本在产物里出现多次——按出现顺序一一对上，不会把 {@code rt2} 安到 {@code rt1} 的位置。</li>
     * </ul>
     *
     * <p>id 按<b>匹配成功的顺序</b>编号，也就是产物里的出现顺序。</p>
     *
     * @param raw     顶层聚合结果（文本已定型，尚未带 id）
     * @param session 本次聚合的草稿会话
     */
    private static ConcatenationResult finalizeSlots(ConcatenationResult raw, SlotSession session) {
        if (session.drafts.isEmpty()) return raw;
        String text = raw.sql();
        StringBuilder rebuilt = new StringBuilder(text.length() + session.drafts.size() * 8);
        List<RuntimeSlot> slots = new ArrayList<>();
        int cursor = 0;
        for (DraftSlot draft : session.drafts) {
            int at = text.indexOf(draft.baseComment(), cursor);
            if (at < 0) continue;
            // 已有 slot（来自内层已收口的聚合，见 concatMethodReturn）先占位，避免 id 撞号
            String id = SLOT_ID_PREFIX + (raw.runtimeSlots().size() + slots.size() + 1);
            String token = withSlotId(draft.baseComment(), id);
            rebuilt.append(text, cursor, at).append(token);
            cursor = at + draft.baseComment().length();
            slots.add(new RuntimeSlot(
                    id,
                    draft.kind(),
                    draft.expression(),
                    draft.template(),
                    inferHint(text, at, draft.template()),
                    token));
        }
        if (slots.isEmpty()) return raw;
        rebuilt.append(text, cursor, text.length());
        List<RuntimeSlot> merged = new ArrayList<>(raw.runtimeSlots());
        merged.addAll(slots);
        return new ConcatenationResult(rebuilt.toString(), raw.complete(), merged);
    }

    /** 把 id 写进占位注释：{@code 运行时值: X} → {@code 运行时值: X · rt1}（注释符原样保留）。 */
    private static String withSlotId(String baseComment, String id) {
        String head = baseComment.endsWith(DYNAMIC_PLACEHOLDER_SUFFIX)
                ? baseComment.substring(0, baseComment.length() - DYNAMIC_PLACEHOLDER_SUFFIX.length())
                : baseComment;
        return head + SLOT_ID_SEPARATOR + id + DYNAMIC_PLACEHOLDER_SUFFIX;
    }

    /**
     * 从产物文本推断该占位的回填语义（D-4 的上下文推导，保守优先）。
     *
     * <ul>
     *  <li>占位落在单引号字符串内部（{@code at} 之前单引号个数为奇数）→ {@code quote=true}；</li>
     *  <li>整段 slot 的 token 在字符串外、但模板里的值位落在引号内（{@code '%<value>%'}）→ {@code quote=true}
     *      （T-RuntimeSlotOptionalFill：R2 的整段 slot 需要按模板判断，而不是按 token 位置）；</li>
     *  <li>落在 {@code IN (...)} 的括号里（向前找到最近未闭合的 {@code (}，其前的词是 {@code IN}）→ {@code inList=true}；</li>
     *  <li>其余留空（{@code quote} / {@code likePosition} / {@code inList} 全默认），交给面板启发式。</li>
     * </ul>
     *
     * <p>都是文本启发式：向前扫描时<b>不</b>跳过引号内内容，落在字符串内部的占位已由第一条分流
     * （不参与 IN 判定），因此不会把 {@code '('} 里的括号当真。</p>
     */
    private static ParamHint inferHint(String text, int at, String template) {
        if (template != null) {
            int markAt = template.indexOf(RuntimeSlot.VALUE_MARK);
            if (markAt >= 0 && countChar(template, markAt, '\'') % 2 == 1) {
                return new ParamHint(true);
            }
        }
        if (countChar(text, at, '\'') % 2 == 1) return new ParamHint(true);
        return insideInList(text, at) ? new ParamHint(null, null, true) : new ParamHint();
    }

    /** {@code text[0, limit)} 内 {@code c} 的出现次数（Kotlin {@code text.take(at).count { it == c }} 等价）。 */
    private static int countChar(String text, int limit, char c) {
        int count = 0;
        for (int i = 0; i < limit && i < text.length(); i++) {
            if (text.charAt(i) == c) count++;
        }
        return count;
    }

    /** 占位是否落在 {@code IN (...)} 的括号里（向前找最近未闭合的 {@code (}，看它前面的词）。 */
    private static boolean insideInList(String text, int at) {
        int depth = 0;
        int index = at - 1;
        while (index >= 0) {
            char c = text.charAt(index);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                if (depth == 0) {
                    String before = stripTrailingWhitespace(text.substring(0, index));
                    String word = takeLastLetterRun(before);
                    return word.equalsIgnoreCase("IN");
                }
                depth--;
            }
            index--;
        }
        return false;
    }

    /** Kotlin {@code String.trimEnd()} 等价。 */
    private static String stripTrailingWhitespace(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    /** Kotlin {@code String.takeLastWhile { it.isLetter() }} 等价（取尾部连续字母段）。 */
    private static String takeLastLetterRun(String s) {
        int start = s.length();
        while (start > 0 && Character.isLetter(s.charAt(start - 1))) start--;
        return s.substring(start);
    }

    /**
     * 占位文本清洗（规则见 {@link #dynamicPlaceholder}）。
     *
     * @param exprText  原文
     * @param maxLength 长度上限；默认 {@link #DYNAMIC_PLACEHOLDER_MAX_LENGTH}，「还要再包一层前后缀」的
     *                  场景（循环模板摘要）传更小的预算，见 {@link #TEMPLATE_SUMMARY_MAX_LENGTH}
     */
    private static String sanitizePlaceholderText(String exprText, int maxLength) {
        String normalized = WHITESPACE_RUN.matcher(exprText
                        .replace("*/", "* /")
                        .replace('\'', '\u2019')
                        .replace('"', '\u201D'))
                .replaceAll(" ")
                .trim();
        String text = normalized.isEmpty() ? "表达式" : normalized;
        if (text.length() > maxLength) {
            return text.substring(0, maxLength - 1) + "…";
        }
        return text;
    }

    /** {@link #sanitizePlaceholderText(String, int)} 默认上限便捷形态。 */
    private static String sanitizePlaceholderText(String exprText) {
        return sanitizePlaceholderText(exprText, DYNAMIC_PLACEHOLDER_MAX_LENGTH);
    }

    // ------------------------------------------------------------------
    // R2 — 条件表达式（三元）分派
    // ------------------------------------------------------------------

    /**
     * 聚合 Java 三元表达式（R2）。
     *
     * <p>三元在运行期<b>只会走一支</b>，因此绝不把两支都拼进 SQL（那等于编造 SQL）：</p>
     * <ul>
     *  <li>条件是 {@code true} / {@code false} 字面量 → 直接聚合对应分支；</li>
     *  <li>两支都聚合成功且文本相同 → 折叠取该文本（{@code complete} = 两支的与）；</li>
     *  <li>其余（分支文本不同 / 有一支聚合失败）→ 单条占位注释，{@code complete=false}。</li>
     * </ul>
     *
     * @param placeholderText 写进占位的原文；默认三元自身文本。三元被括号包住时由调用方传外层
     *                        原文（含括号），保持既有产物逐字不变
     */
    private static ConcatenationResult concatConditional(PsiConditionalExpression expr) {
        return concatConditional(expr, expr.getText());
    }

    private static ConcatenationResult concatConditional(PsiConditionalExpression expr, String placeholderText) {
        Boolean conditionValue = null;
        if (expr.getCondition() instanceof PsiLiteralExpression literal) {
            Object value = literal.getValue();
            if (value instanceof Boolean) conditionValue = (Boolean) value;
        }
        if (conditionValue != null) {
            PsiExpression branch = conditionValue ? expr.getThenExpression() : expr.getElseExpression();
            return branch != null ? concat(branch) : null;
        }
        ConcatenationResult thenResult = expr.getThenExpression() != null ? concat(expr.getThenExpression()) : null;
        ConcatenationResult elseResult = expr.getElseExpression() != null ? concat(expr.getElseExpression()) : null;
        if (thenResult != null && elseResult != null && thenResult.sql().equals(elseResult.sql())) {
            return new ConcatenationResult(thenResult.sql(), thenResult.complete() && elseResult.complete());
        }
        // T-RuntimeSlotFill 券 A：两支文本在这里确实分别拿得到（thenResult / elseResult），
        // 只是产物只留一条占位；两支的**展开形态**（只取字符串字面量）随占位一起留给用户挑。
        return new ConcatenationResult(
                slotPlaceholder(placeholderText, SlotKind.TERNARY, placeholderText,
                        twoBranchTemplate(expr.getThenExpression(), expr.getElseExpression())),
                false);
    }

    // ------------------------------------------------------------------
    // toString() 解包 / 方法返回跟随
    // ------------------------------------------------------------------

    /**
     * 聚合「StringBuffer/StringBuilder 变量 {@code .toString()} 调用链」形态：先把
     * {@code xxx.toString()} / {@code xxx.append(y).toString()} / {@code xxx.append(y)} 归约到持有变量引用，
     * 再交 {@link #concat} 走既有聚合（变量 initializer 为 {@code new StringBuilder()} / {@code new StringBuffer()}
     * 时聚合其同方法体内全部 {@code append} 调用）。
     *
     * <p>{@link #concat} 自身对 {@code PsiMethodCallExpression} 无分支（返回 null），直接把 {@code sb.toString()}
     * 喂进去聚合不出内容——JdbcTemplate 通道靠 {@code JdbcTemplateSqlLocator.resolveToStringCall}
     * 做这步解包。本方法把同一语义提为共享入口，供 CustomDaoHqlLocator / HqlQueryLocator
     * 复用（T-HqlBufConcat），避免各处再写一份。</p>
     *
     * @param expr SQL 字符串实参表达式（字面量 / 拼接 / 变量引用 / {@code sb.toString()} 调用链）
     * @return 聚合结果；无法聚合返回 {@code null}
     */
    public static ConcatenationResult concatUnwrappingToString(PsiExpression expr) {
        return concatUnwrappingToString(expr, 0);
    }

    /**
     * 聚合入口（带跟随深度）。
     *
     * <p>{@code PsiMethodCallExpression} 分两种承载：</p>
     * <ol>
     *   <li>{@code sb.toString()} / {@code sb.append(x)} → 解包到持有变量后走 {@link #concat}（T-HqlBufConcat）；</li>
     *   <li><b>源码可见的方法返回的字符串</b>（{@code findByQuery(buildHql(), …)}）→ 跟随到方法体聚合其
     *       {@code return} 表达式（T-HqlHelperReturn）。</li>
     * </ol>
     */
    private static ConcatenationResult concatUnwrappingToString(PsiExpression expr, int depth) {
        if (expr == null) {
            return null;
        }
        if (expr instanceof PsiMethodCallExpression) {
            PsiExpression unwrapped = unwrapToStringTarget((PsiMethodCallExpression) expr);
            if (unwrapped != null) {
                return concat(unwrapped);
            }
            return concatMethodReturn((PsiMethodCallExpression) expr, depth);
        }
        return concat(expr);
    }

    /**
     * 跟随「首参是被调方法」的形态：{@code commonDao.findByQuery(buildHql(), …)} 这类把 SQL 交给一个
     * 同工程方法去拼的写法（T-HqlHelperReturn）。
     *
     * <p>判定与护栏（保守优先，与「宁缺不错」一致）：</p>
     * <ul>
     *   <li>方法必须<b>源码可见</b>（{@code PsiMethod.getBody() != null}）：JDK / 三方库方法不跟随；</li>
     *   <li>聚合其全部 {@code return} 表达式：一个都聚合不出 → null；聚合结果<b>文本不一致</b>（多分支返回不同
     *       内容）→ null（不猜哪条分支）；</li>
     *   <li>只收本方法自身的 {@code return}，排除嵌套方法 / lambda 体内的（那些不属于本方法的返回值）；</li>
     *   <li>深层跟随有上限 {@link #MAX_METHOD_FOLLOW_DEPTH}，防递归与环。</li>
     * </ul>
     *
     * <p>运行时值照既有口径降级：方法体内出现参数 / 调用等静态不可求值片段时，聚合结果带
     * 单条自洽占位注释（见 {@link #dynamicPlaceholder}）并置 {@code complete=false}，不编造内容。</p>
     */
    private static ConcatenationResult concatMethodReturn(PsiMethodCallExpression call, int depth) {
        if (depth >= MAX_METHOD_FOLLOW_DEPTH) return null;
        PsiMethod method;
        try {
            method = call.resolveMethod();
        } catch (Throwable ignored) {
            return null;
        }
        if (method == null) return null;
        PsiElement body = method.getBody();
        if (body == null) return null;
        List<ConcatenationResult> aggregated = new ArrayList<>();
        for (PsiReturnStatement stmt : PsiTreeUtil.collectElementsOfType(body, PsiReturnStatement.class)) {
            // 只收本方法自身的 return：排除嵌套方法体内的（不属于本方法的返回值）
            if (PsiTreeUtil.getParentOfType(stmt, PsiMethod.class) != method) continue;
            // 排除 lambda 体内的 return（同上）
            if (PsiTreeUtil.getParentOfType(stmt, PsiLambdaExpression.class, true) != null) continue;
            PsiExpression returnValue = stmt.getReturnValue();
            if (returnValue == null) continue;
            ConcatenationResult r = concatUnwrappingToString(returnValue, depth + 1);
            if (r != null) aggregated.add(r);
        }
        if (aggregated.isEmpty()) return null;
        String text = aggregated.get(0).sql();
        boolean complete = true;
        for (ConcatenationResult r : aggregated) {
            if (!r.sql().equals(text)) return null;
            if (!r.complete()) complete = false;
        }
        // 券 A：文本一致才有意义，slot 随保留下来的那份文本一起交出去
        return new ConcatenationResult(text, complete, aggregated.get(0).runtimeSlots());
    }

    /**
     * 把 {@code .toString()} / {@code .append(y)} 调用链归约到根变量引用
     * （{@code xxx.append(y).toString()} → {@code xxx}）。
     *
     * <p>方法名不在 {toString, append} 内、或 qualifier 链不落到变量引用时返回 null。</p>
     */
    private static PsiExpression unwrapToStringTarget(PsiMethodCallExpression call) {
        String name = call.getMethodExpression().getReferenceName();
        if (name == null) return null;
        if (!"toString".equals(name) && !"append".equals(name)) return null;
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (qualifier == null) return null;
        while (qualifier instanceof PsiMethodCallExpression) {
            qualifier = ((PsiMethodCallExpression) qualifier).getMethodExpression().getQualifierExpression();
            if (qualifier == null) return null;
        }
        return qualifier;
    }

    // ------------------------------------------------------------------
    // 路径 A — 字面量 + 拼接
    // ------------------------------------------------------------------

    private static ConcatenationResult concatLiteral(PsiLiteralExpression literal) {
        Object value = literal.getValue();
        if (!(value instanceof String)) {
            return null;
        }
        return new ConcatenationResult((String) value, true);
    }

    private static ConcatenationResult concatPolyadic(PsiExpression expr) {
        if (!(expr instanceof PsiPolyadicExpression)) {
            return null;
        }
        PsiPolyadicExpression polyadic = (PsiPolyadicExpression) expr;
        if (polyadic.getOperationTokenType() != JavaTokenType.PLUS) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        boolean complete = true;
        for (PsiExpression operand : polyadic.getOperands()) {
            ConcatenationResult r = concat(operand);
            if (r == null) {
                // 不可静态求值的操作数（运行时值）→ 标记降级 + 单条自洽占位注释
                complete = false;
                parts.add(slotPlaceholder(operand.getText()));
            } else {
                if (!r.complete()) {
                    complete = false;
                }
                parts.add(r.sql());
            }
        }
        return new ConcatenationResult(String.join("", parts), complete);
    }

    // ------------------------------------------------------------------
    // 变量引用（路径 A/B 共享）
    // ------------------------------------------------------------------

    private static ConcatenationResult concatReference(PsiReferenceExpression ref) {
        return concatReference(ref, 0);
    }

    private static ConcatenationResult concatReference(PsiReferenceExpression ref, int depth) {
        if (depth > MAX_TRACE_DEPTH) {
            return null;
        }
        PsiElement resolved = ref.resolve();
        if (!(resolved instanceof PsiVariable)) {
            return null;
        }
        PsiVariable variable = (PsiVariable) resolved;
        PsiExpression initializer = variable.getInitializer();
        if (initializer == null) {
            return null;
        }
        if (initializer instanceof PsiLiteralExpression) {
            return concatLiteral((PsiLiteralExpression) initializer);
        }
        if (initializer instanceof PsiBinaryExpression || initializer instanceof PsiPolyadicExpression) {
            return concatPolyadic(initializer);
        }
        if (initializer instanceof PsiReferenceExpression) {
            return concatReference((PsiReferenceExpression) initializer, depth + 1);
        }
        if (initializer instanceof PsiNewExpression) {
            return concatNewStringBuilder((PsiNewExpression) initializer, variable, depth);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 路径 B — StringBuilder 聚合
    // ------------------------------------------------------------------

    /**
     * 聚合 `new StringBuilder(...)` 初值 + 同一方法体内全部 `append` 调用。
     *
     * @param newExpr  `new StringBuilder(...)` 表达式
     * @param variable 持有该 new 表达式的变量（用于查找 append 调用）
     * @param depth    当前追踪深度
     */
    private static ConcatenationResult concatNewStringBuilder(
            PsiNewExpression newExpr,
            PsiVariable variable,
            int depth
    ) {
        List<String> parts = new ArrayList<>();
        boolean complete = true;

        // 初值（`new StringBuilder("SELECT ...")`）
        PsiExpressionList argumentList = newExpr.getArgumentList();
        PsiExpression[] args = argumentList != null ? argumentList.getExpressions() : null;
        if (args != null && args.length > 0) {
            PsiExpression initExpr = args[0];
            ConcatenationResult r = concat(initExpr);
            if (r == null) {
                complete = false;
                parts.add(slotPlaceholder(initExpr.getText()));
            } else {
                if (!r.complete()) {
                    complete = false;
                }
                parts.add(r.sql());
            }
        }

        // 查找同一方法体内对目标变量的 `append` 调用
        List<ConcatenationResult> appendResults = collectAppendCalls(variable, depth);
        for (ConcatenationResult r : appendResults) {
            if (!r.complete()) {
                complete = false;
            }
            parts.add(r.sql());
        }

        String sql = String.join("", parts);
        if (sql.isBlank() && appendResults.isEmpty()) {
            return null;
        }
        return new ConcatenationResult(sql, complete);
    }

    /**
     * 收集同一方法体内对目标变量的 `append` 调用（按 offset 排序）。
     *
     * <p>查找范围：目标变量所在方法体（PsiCodeBlock），过滤
     * {@code methodExpression.referenceName == "append"} 且 qualifier 解析到目标变量的调用。</p>
     */
    private static List<ConcatenationResult> collectAppendCalls(PsiVariable variable, int depth) {
        PsiMethod method = PsiTreeUtil.getParentOfType(variable, PsiMethod.class);
        if (method == null) {
            return Collections.emptyList();
        }
        PsiElement body = method.getBody();
        if (body == null) {
            return Collections.emptyList();
        }
        String varName = variable.getName();
        if (varName == null || varName.isBlank()) {
            return Collections.emptyList();
        }

        List<PsiMethodCallExpression> appendCalls = new ArrayList<>();
        for (PsiMethodCallExpression call : PsiTreeUtil.collectElementsOfType(body, PsiMethodCallExpression.class)) {
            String methodName = call.getMethodExpression().getReferenceName();
            if (methodName == null || !"append".equals(methodName)) {
                continue;
            }
            // qualifier 可以是直接变量引用，也可以是链式方法调用（sql.append("A").append(x)）——
            // 沿 qualifier 链回溯到根变量，判定是否为目标变量
            PsiVariable rootVar = resolveRootVariable(call.getMethodExpression().getQualifierExpression());
            if (rootVar == variable) {
                appendCalls.add(call);
            }
        }
        // 按 startOffset 升序；链式调用中多层 append 共享同一 startOffset（都从 `sql` 起），
        // 此时按文本长度升序（内层最短 → 排前面），保证 append 实参按正确顺序拼接
        appendCalls.sort(Comparator.comparingInt((PsiMethodCallExpression c) -> c.getTextRange().getStartOffset())
                .thenComparingInt(PsiMethodCallExpression::getTextLength));

        List<ConcatenationResult> results = new ArrayList<>();
        // R3：同一控制流上下文（同一个循环 / if / switch / lambda）只降级或只标注一次，避免同一片段刷三条注释
        java.util.Set<PsiElement> degradedContexts = new java.util.HashSet<>();
        // R2：OPTIONAL 上下文按「整段一个 slot」产出，同一上下文只处理一次
        java.util.Set<PsiElement> emittedOptionalContexts = new java.util.HashSet<>();

        for (PsiMethodCallExpression call : appendCalls) {
            PsiExpression[] args = call.getArgumentList().getExpressions();
            if (args.length == 0) {
                continue;
            }
            PsiExpression argExpr = args[0];
            AppendPosition position = analyzeAppendPosition(call, variable);
            PsiElement anchor = position.anchor();
            switch (position.flow()) {
                case LOOP -> {
                    // 循环体内：贡献整体降级为占位注释（运行期重复 N 次，线性拼接必错）
                    if (anchor != null && !degradedContexts.add(anchor)) continue;
                    // P1：占位带上循环体的模板摘要（只取字符串字面量，其余替换为 …），
                    // 摘要取不到时回落 LOOP_APPEND_LABEL 现文案
                    List<PsiExpression> loopArgs = contextArguments(appendCalls, variable, anchor);
                    String summary = summarizeLoopTemplate(loopArgs);
                    String label = summary == null
                            ? LOOP_APPEND_LABEL
                            : LOOP_APPEND_SUMMARY_PREFIX + summary + LOOP_APPEND_SUMMARY_SUFFIX;
                    // 券 A：循环占位是可回填对象（用户给一组值），展开形态 = 循环体字面量模板；
                    // 模板抽不出来时给 null（「展开形态未知」），不猜连接符
                    results.add(new ConcatenationResult(
                            slotPlaceholder(label, SlotKind.LOOP,
                                    summary != null ? summary : LOOP_APPEND_LABEL,
                                    repetitionTemplate(loopArgs)),
                            false));
                }
                case PAIRED -> {
                    // if/else 互斥两支都追加：互斥片段不能同时出现在 SQL 里 → 两支都降级
                    if (anchor != null && !degradedContexts.add(anchor)) continue;
                    // 券 A：互斥两支各是哪一段、各自形态都不知道（两支都已丢弃），没有可填的值对象——
                    // 只留说明性注释，不造 slot
                    results.add(new ConcatenationResult(dynamicPlaceholder(PAIRED_APPEND_LABEL), false));
                }
                case OPTIONAL -> {
                    // R2（T-RuntimeSlotOptionalFill）：可选片段按「整段一个 slot」产出——token 覆盖整段片段、
                    // template 也是整段片段，填值/取消/留空三条路径才一致。旧形态是「静态片段在 token 外 +
                    // 值位置一个 token」，template 却是整段：填值会重复静态前缀，落在字符串内的 token 会被
                    // 回填引擎直接拒绝，取消包含又会残留静态前后缀（见计划 §3）。
                    if (anchor != null && !emittedOptionalContexts.add(anchor)) continue;
                    List<PsiMethodCallExpression> contextCalls =
                            anchor == null ? List.of(call) : contextCalls(appendCalls, variable, anchor);
                    List<PsiExpression> contextArgs = new ArrayList<>();
                    for (PsiMethodCallExpression contextCall : contextCalls) {
                        PsiExpression[] contextCallArgs = contextCall.getArgumentList().getExpressions();
                        if (contextCallArgs.length > 0) contextArgs.add(contextCallArgs[0]);
                    }
                    String fragmentTemplate = repetitionTemplate(contextArgs);
                    int marks = templateMarkCount(fragmentTemplate);
                    String note = position.note != null ? position.note : "";
                    if (fragmentTemplate != null && marks == 1) {
                        // 单值片段：整段一个 slot（说明注释并入 slot 文案，不再单独发）
                        PsiExpression runtimeArg = null;
                        for (PsiExpression candidate : contextArgs) {
                            String shape = candidate != null ? shapeTemplate(candidate) : null;
                            if (shape == null || shape.contains(RuntimeSlot.VALUE_MARK)) {
                                runtimeArg = candidate;
                                break;
                            }
                        }
                        if (runtimeArg == null) runtimeArg = argExpr;
                        String expression = summarizeExpression(runtimeArg);
                        if (expression == null) expression = runtimeArg.getText();
                        results.add(new ConcatenationResult(
                                // 前置空格：整段 slot 是一条注释，和相邻片段之间留出可读间隔
                                // （模板自身的尾随空格在未填值时不在产物里）
                                " " + slotPlaceholder(optionalSlotLabel(note) + "（" + expression + "）",
                                        SlotKind.OPTIONAL, expression, fragmentTemplate),
                                false));
                    } else {
                        // 0 个 mark（全静态）/ 多个 mark（多值片段）/ 模板未知：逐 append 输出。
                        // 运行期值走「值位置 slot」（template=null，不展开整段）——多值片段暂不支持整段开关，
                        // 取消包含只移除当前值 token，静态前后缀保留（回填引擎给提示，已知限制 D-2）。
                        if (!note.isEmpty()) {
                            results.add(new ConcatenationResult(note, false));
                        }
                        for (PsiMethodCallExpression contextCall : contextCalls) {
                            PsiExpression[] contextCallArgs = contextCall.getArgumentList().getExpressions();
                            if (contextCallArgs.length == 0) continue;
                            PsiExpression contextArg = contextCallArgs[0];
                            ConcatenationResult r = concat(contextArg);
                            if (r != null) {
                                results.add(r.complete()
                                        ? new ConcatenationResult(r.sql(), false, r.runtimeSlots())
                                        : r);
                            } else {
                                results.add(new ConcatenationResult(
                                        slotPlaceholder(contextArg.getText(), SlotKind.OPTIONAL, contextArg.getText(), null),
                                        false));
                            }
                        }
                    }
                }
                default -> {
                    // 直线代码：维持既有输出
                    ConcatenationResult r = concat(argExpr);
                    results.add(r != null ? r
                            : new ConcatenationResult(slotPlaceholder(argExpr.getText()), false));
                }
            }
        }
        return results;
    }

    // ------------------------------------------------------------------
    // R3 — append 的控制流位置
    // ------------------------------------------------------------------

    /** append 相对方法体的控制流位置（R3）。 */
    private enum AppendFlow {
        /** 直线代码：维持既有输出。 */
        STRAIGHT,
        /** 循环体内：运行期重复 N 次，线性拼接必错 → 整体降级为占位注释。 */
        LOOP,
        /** 条件 / 分支 / 回调内：保留片段，前置一条说明注释。 */
        OPTIONAL,
        /** if/else 互斥两支都对该 builder 追加 → 两支贡献都降级为占位注释。 */
        PAIRED
    }

    /**
     * append 的控制流判定结果。
     *
     * @param flow   控制流形态
     * @param anchor 同一上下文的锚点（loop / if / switch / 三元 / lambda），用于同上下文只标注一次
     * @param note   {@link AppendFlow#OPTIONAL} 的前置说明注释
     */
    private record AppendPosition(AppendFlow flow, PsiElement anchor, String note) {

        AppendPosition(AppendFlow flow) {
            this(flow, null, null);
        }

        AppendPosition(AppendFlow flow, PsiElement anchor) {
            this(flow, anchor, null);
        }
    }

    /**
     * 判定一条 `append` 调用相对所在方法体的控制流位置（R3）。
     *
     * <p>向上找最近的 {@code PsiForStatement} / {@code PsiForeachStatement} / {@code PsiWhileStatement} /
     * {@code PsiDoWhileStatement} / {@code PsiIfStatement} / {@code PsiSwitchStatement} /
     * {@code PsiConditionalExpression} / {@code PsiLambdaExpression}；遇到所在方法即停（不看外层类）。</p>
     *
     * <p>循环优先于条件分支：{@code if} 落在循环内时按循环处理（重复 + 可选，降级即可覆盖）。</p>
     */
    private static AppendPosition analyzeAppendPosition(PsiMethodCallExpression call, PsiVariable variable) {
        PsiElement loop = null;
        PsiElement branch = null;
        PsiElement current = call.getParent();
        while (current != null && !(current instanceof PsiMethod)) {
            if (current instanceof PsiForStatement || current instanceof PsiForeachStatement
                    || current instanceof PsiWhileStatement || current instanceof PsiDoWhileStatement) {
                if (loop == null) loop = current;
            } else if (branch == null
                    && (current instanceof PsiIfStatement || current instanceof PsiSwitchStatement
                    || current instanceof PsiConditionalExpression || current instanceof PsiLambdaExpression)) {
                branch = current;
            }
            current = current.getParent();
        }
        if (loop != null) return new AppendPosition(AppendFlow.LOOP, loop);
        if (branch == null) return new AppendPosition(AppendFlow.STRAIGHT);
        if (branch instanceof PsiIfStatement) {
            PsiIfStatement ifStatement = (PsiIfStatement) branch;
            PsiElement thenBranch = ifStatement.getThenBranch();
            PsiElement elseBranch = ifStatement.getElseBranch();
            boolean inThenBranch = thenBranch != null && PsiTreeUtil.isAncestor(thenBranch, call, false);
            PsiElement otherBranch = inThenBranch ? elseBranch : thenBranch;
            if (containsAppendTo(otherBranch, variable)) {
                return new AppendPosition(AppendFlow.PAIRED, branch);
            }
            return new AppendPosition(AppendFlow.OPTIONAL, branch, CONDITIONAL_APPEND_NOTE);
        }
        if (branch instanceof PsiSwitchStatement) {
            return new AppendPosition(AppendFlow.OPTIONAL, branch, SWITCH_APPEND_NOTE);
        }
        if (branch instanceof PsiConditionalExpression) {
            return new AppendPosition(AppendFlow.OPTIONAL, branch, CONDITIONAL_APPEND_NOTE);
        }
        if (branch instanceof PsiLambdaExpression) {
            return new AppendPosition(AppendFlow.OPTIONAL, branch, LAMBDA_APPEND_NOTE);
        }
        return new AppendPosition(AppendFlow.STRAIGHT);
    }

    /** 指定分支内是否存在对同一 builder 变量的 `append`（if/else 成对判定）。 */
    private static boolean containsAppendTo(PsiElement branch, PsiVariable variable) {
        if (branch == null) return false;
        for (PsiMethodCallExpression call : PsiTreeUtil.collectElementsOfType(branch, PsiMethodCallExpression.class)) {
            if ("append".equals(call.getMethodExpression().getReferenceName())
                    && resolveRootVariable(call.getMethodExpression().getQualifierExpression()) == variable) {
                return true;
            }
        }
        return false;
    }

    /**
     * 沿 qualifier 链回溯根变量。
     *
     * <p>处理两种形态：</p>
     * <ul>
     *  <li>直接调用 `sql.append(...)` → qualifier 是 PsiReferenceExpression → 直接解析</li>
     *  <li>链式调用 `sql.append("A").append(x).append("B")` → 外层 qualifier 是 PsiMethodCallExpression →
     *      递归取 qualifier 的 qualifier，直到回溯到 PsiReferenceExpression</li>
     * </ul>
     *
     * @param qualifier 方法调用的 qualifier 表达式（可能 null）
     * @return 根变量；无法解析返回 null
     */
    private static PsiVariable resolveRootVariable(PsiExpression qualifier) {
        PsiExpression current = qualifier;
        while (current != null) {
            if (current instanceof PsiReferenceExpression) {
                PsiElement resolved = ((PsiReferenceExpression) current).resolve();
                return resolved instanceof PsiVariable ? (PsiVariable) resolved : null;
            }
            if (current instanceof PsiMethodCallExpression) {
                current = ((PsiMethodCallExpression) current).getMethodExpression().getQualifierExpression();
                continue;
            }
            return null;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // P1（polish）— 循环占位的「模板摘要」
    // ------------------------------------------------------------------

    /**
     * 收集同一控制流上下文内全部 `append` 的实参（按源码顺序），作为模板取样集合。
     *
     * <p>循环体 / 可选片段里的追加常见形态是<b>跨多条 append</b>（{@code sb.append("or id='").append(ids.get(k)).append("' ")}），
     * 只取「第一个触发降级的 append」会把摘要截成半截，所以按同一锚点整段取样。</p>
     *
     * @param appendCalls 已按源码顺序排好、且都已筛成「对目标 builder 的 append」的调用列表
     * @param variable    目标 builder 变量
     * @param anchor      控制流锚点（{@link #analyzeAppendPosition} 给出的 loop / if / switch / lambda 元素）
     * @return 该上下文内各 append 的首个实参（顺序同源码）
     */
    private static List<PsiExpression> contextArguments(
            List<PsiMethodCallExpression> appendCalls, PsiVariable variable, PsiElement anchor) {
        if (anchor == null) return List.of();
        List<PsiExpression> out = new ArrayList<>();
        for (PsiMethodCallExpression call : contextCalls(appendCalls, variable, anchor)) {
            PsiExpression[] args = call.getArgumentList().getExpressions();
            if (args.length > 0) out.add(args[0]);
        }
        return out;
    }

    /**
     * 收集同一控制流上下文内全部 `append` 调用（按源码顺序）。
     *
     * <p>与 {@link #contextArguments} 同源；R2 的「多值片段回退」需要按调用逐个处理（既要实参也要调用本身）。</p>
     */
    private static List<PsiMethodCallExpression> contextCalls(
            List<PsiMethodCallExpression> appendCalls, PsiVariable variable, PsiElement anchor) {
        if (anchor == null) return List.of();
        List<PsiMethodCallExpression> out = new ArrayList<>();
        for (PsiMethodCallExpression call : appendCalls) {
            if (analyzeAppendPosition(call, variable).anchor() == anchor) {
                out.add(call);
            }
        }
        return out;
    }

    /** 模板里 {@link RuntimeSlot#VALUE_MARK} 的个数（不重叠计数；null 模板按 0 算）。 */
    private static int templateMarkCount(String template) {
        if (template == null) return 0;
        String mark = RuntimeSlot.VALUE_MARK;
        int count = 0;
        int from = 0;
        while (true) {
            int at = template.indexOf(mark, from);
            if (at < 0) return count;
            count++;
            from = at + mark.length();
        }
    }

    /**
     * 可选片段整段 slot 的文案标签：把「运行时值: 条件追加，可能不出现」这类说明注释
     * 剥成纯文本（{@code 条件追加，可能不出现}），拼成 {@code 条件追加，可能不出现（keyword）}。
     */
    private static String optionalSlotLabel(String note) {
        String raw = note;
        if (raw.startsWith(DYNAMIC_PLACEHOLDER_PREFIX)) {
            raw = raw.substring(DYNAMIC_PLACEHOLDER_PREFIX.length());
        }
        if (raw.endsWith(DYNAMIC_PLACEHOLDER_SUFFIX)) {
            raw = raw.substring(0, raw.length() - DYNAMIC_PLACEHOLDER_SUFFIX.length());
        }
        raw = raw.trim();
        return raw.isEmpty() ? "条件追加" : raw;
    }

    /**
     * 把循环体的 append 序列汇成一条<b>模板摘要</b>（P1）：告诉用户「循环里追加的是什么」。
     *
     * <p>硬约束（与 {@link #dynamicPlaceholder} 的清洗口径一致，见开发计划 R1.1）：</p>
     * <ul>
     *  <li>只保留<b>字符串字面量片段</b>，变量引用 / 方法调用 / 任意表达式一律替换为 {@link #TEMPLATE_ELLIPSIS}
     *      ——{@code k == 0 ?}、{@code ids.get(k)} 这类源码绝不出现在产物里；</li>
     *  <li>取不到字面量的实参贡献一个 {@link #TEMPLATE_ELLIPSIS} 占位——相邻片段之间必须有分隔，
     *      否则 {@code append("or id='")} + {@code append(变量)} + {@code append("' ")} 会读成 {@code or id=''}（像空字面量）；</li>
     *  <li>整段一个字面量都取不到 → 返回 null，调用方回落 {@link #LOOP_APPEND_LABEL} 现文案，不凭空编造；</li>
     *  <li>摘要仍走 {@link #sanitizePlaceholderText(String, int)}（注释结束符拆成 {@code * /}、引号换安全同形字符、空白折叠、长度上限）。</li>
     * </ul>
     *
     * @param args 循环体内各 append 的实参（源码顺序）
     */
    private static String summarizeLoopTemplate(List<PsiExpression> args) {
        if (args.isEmpty()) return null;
        StringBuilder raw = new StringBuilder();
        boolean hasLiteral = false;
        for (PsiExpression arg : args) {
            String fragment = summarizeExpression(arg);
            if (fragment == null) {
                raw.append(TEMPLATE_ELLIPSIS);
            } else {
                raw.append(fragment);
                hasLiteral = true;
            }
        }
        if (!hasLiteral) return null;
        return sanitizePlaceholderText(raw.toString(), TEMPLATE_SUMMARY_MAX_LENGTH);
    }

    /**
     * 表达式 → 模板片段（P1），<b>只读结构、不 resolve</b>。
     *
     * <ul>
     *  <li>字符串字面量 → 字面量值（{@code "or id='" → or id='}，后续由清洗换安全同形引号）；</li>
     *  <li>`+` 拼接 → 各操作数片段级联，非字面量操作数占一个 {@link #TEMPLATE_ELLIPSIS}；</li>
     *  <li>三元 → 两支片段以 {@link #TEMPLATE_BRANCH_SEPARATOR} 分隔（缺片段的一支用 {@code …} 顶位）；</li>
     *  <li>其余（变量引用 / 方法调用 / 任意表达式）→ null（由调用方决定补 {@code …} 还是回落）。</li>
     * </ul>
     *
     * <p>返回 null 的语义是「本表达式里没有字符串字面量」，不是「算不出来」——所以
     * {@code ids.get(k)} / {@code k == 0} 这类内容结构上就不可能被带出来。</p>
     */
    private static String summarizeExpression(PsiExpression expr) {
        if (expr instanceof PsiLiteralExpression) {
            Object value = ((PsiLiteralExpression) expr).getValue();
            if (value instanceof String && !((String) value).isEmpty()) {
                return (String) value;
            }
            return null;
        }
        if (expr instanceof PsiConditionalExpression) {
            PsiConditionalExpression conditional = (PsiConditionalExpression) expr;
            String thenFragment = conditional.getThenExpression() != null
                    ? summarizeExpression(conditional.getThenExpression()) : null;
            String elseFragment = conditional.getElseExpression() != null
                    ? summarizeExpression(conditional.getElseExpression()) : null;
            if (thenFragment == null && elseFragment == null) return null;
            // 两支都要在场：只留一支会读成「循环里只有这一种形态」
            return (thenFragment != null ? thenFragment : TEMPLATE_ELLIPSIS)
                    + TEMPLATE_BRANCH_SEPARATOR
                    + (elseFragment != null ? elseFragment : TEMPLATE_ELLIPSIS);
        }
        if (expr instanceof PsiPolyadicExpression) {
            PsiPolyadicExpression polyadic = (PsiPolyadicExpression) expr;
            if (polyadic.getOperationTokenType() != JavaTokenType.PLUS) return null;
            StringBuilder raw = new StringBuilder();
            boolean hasLiteral = false;
            for (PsiExpression operand : polyadic.getOperands()) {
                String fragment = summarizeExpression(operand);
                if (fragment == null) {
                    raw.append(TEMPLATE_ELLIPSIS);
                } else {
                    raw.append(fragment);
                    hasLiteral = true;
                }
            }
            return hasLiteral ? raw.toString() : null;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // T-RuntimeSlotFill 券 A — 展开形态模板（只报事实，不猜）
    // ------------------------------------------------------------------

    /**
     * 控制流上下文的「一次重复」展开模板（P-3 / D-2）。
     *
     * <p>形态判定（保守，宁缺不错）：</p>
     * <ul>
     *  <li>上下文内只有<b>一条</b> append，且实参是三元 → 两支各自的形态（循环里 {@code k == 0 ? a : b}
     *      正是「首项用一支、其余用另一支」），编码为两支模板；</li>
     *  <li>其余情况 → 把所有 append 的实参按源码顺序拼成<b>一个</b>形态（每次重复都长这样），
     *      非字面量位置用 {@link RuntimeSlot#VALUE_MARK} 占位；</li>
     *  <li>整段一个字面量都没有 → {@code null}（展开形态未知，不许按猜测拼 SQL）。</li>
     * </ul>
     *
     * @param args 上下文内各 append 的实参（源码顺序，见 {@link #contextArguments}）
     */
    private static String repetitionTemplate(List<PsiExpression> args) {
        if (args.isEmpty()) return null;
        if (args.size() == 1 && args.get(0) instanceof PsiConditionalExpression) {
            PsiConditionalExpression single = (PsiConditionalExpression) args.get(0);
            return twoBranchTemplate(single.getThenExpression(), single.getElseExpression());
        }
        StringBuilder shape = new StringBuilder();
        boolean hasLiteral = false;
        for (PsiExpression arg : args) {
            String fragment = shapeTemplate(arg);
            if (fragment == null) {
                shape.append(RuntimeSlot.VALUE_MARK);
            } else {
                shape.append(fragment);
                hasLiteral = true;
            }
        }
        if (!hasLiteral) return null;
        return RuntimeSlot.joinBranches(List.of(shape.toString()));
    }

    /**
     * 两支形态模板（三元 then/else；循环占位里则读作「首项 / 其余」）。
     *
     * @return 两支都取得到形态时返回编码后的模板；任一支取不到 → {@code null}
     */
    private static String twoBranchTemplate(PsiExpression first, PsiExpression second) {
        if (first == null || second == null) return null;
        String firstShape = shapeTemplate(first);
        if (firstShape == null) return null;
        String secondShape = shapeTemplate(second);
        if (secondShape == null) return null;
        return RuntimeSlot.joinBranches(List.of(firstShape, secondShape));
    }

    /**
     * 表达式 → <b>展开形态</b>片段，只读结构、不 resolve。
     *
     * <p>与 {@link #summarizeExpression} 的区别（两者不能复用）：本函数产出的是<b>能拿去生成 SQL 的模板</b>，
     * 所以字符串字面量取<b>原值</b>（真实单引号），不做注释清洗、不做长度截断；摘要那边为了塞进
     * 一条块注释必须换安全同形字符。</p>
     *
     * <ul>
     *  <li>字符串字面量 → 原值（空字面量给 {@code ""}，表示这支不贡献内容）；</li>
     *  <li>`+` 拼接 → 各操作数级联，非字面量操作数占一个 {@link RuntimeSlot#VALUE_MARK}；</li>
     *  <li>其余（变量引用 / 方法调用 / 三元 / 任意表达式）→ {@code null}，由调用方决定补 mark 还是判「未知」。</li>
     * </ul>
     *
     * <p>返回 {@code null} 的语义是「本表达式里没有可用的字符串字面量」——所以 {@code ids.get(k)} 这类源码
     * 结构上就不可能被带进模板。</p>
     */
    private static String shapeTemplate(PsiExpression expr) {
        if (expr instanceof PsiLiteralExpression) {
            Object value = ((PsiLiteralExpression) expr).getValue();
            return value instanceof String ? (String) value : null;
        }
        if (expr instanceof PsiPolyadicExpression) {
            PsiPolyadicExpression polyadic = (PsiPolyadicExpression) expr;
            if (polyadic.getOperationTokenType() != JavaTokenType.PLUS) return null;
            StringBuilder shape = new StringBuilder();
            boolean hasLiteral = false;
            for (PsiExpression operand : polyadic.getOperands()) {
                String fragment = shapeTemplate(operand);
                if (fragment == null) {
                    shape.append(RuntimeSlot.VALUE_MARK);
                } else {
                    shape.append(fragment);
                    hasLiteral = true;
                }
            }
            return hasLiteral ? shape.toString() : null;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 递归收集 PSI 树中全部 `PsiLiteralExpression` 的字符串值（路径 A 兜底）。
     * 用于处理嵌套拼接表达式。
     */
    public static List<String> collectStringLiterals(PsiElement element) {
        if (element instanceof PsiLiteralExpression) {
            Object value = ((PsiLiteralExpression) element).getValue();
            if (!(value instanceof String)) {
                return Collections.emptyList();
            }
            return Collections.singletonList((String) value);
        }
        List<String> out = new ArrayList<>();
        for (PsiElement child : element.getChildren()) {
            out.addAll(collectStringLiterals(child));
        }
        return out;
    }
}
