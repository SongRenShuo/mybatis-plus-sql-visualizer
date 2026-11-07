// T-SyncK2JMaster177 95e107b 自 master sql/RuntimeSlot.kt（RuntimeSlot data class，新增）移植
// （master 单 .kt 文件双顶层类；Java 侧 public 顶层类须独立文件，沿用 k2j 既有拆分布局）
package cn.song.mybatisplus.visualizer.sql;

import java.util.List;
import java.util.Objects;

/**
 * 一个可回填的运行期占位（T-RuntimeSlotFill 券 A 模型）。
 *
 * <h2>为什么身份写在注释文本里</h2>
 *
 * <p>预览 SQL 在呈现前要过 {@code cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter}（重排空白、
 * 关键字大写、注释单独成行），字符区间（char range）在格式化后必然错位；而块注释的<b>文本</b>
 * 被格式化器原样保留（实测见 {@code RuntimeSlotTest} 的 P-1 用例）。因此产物里的占位统一写成</p>
 *
 * <p>占位正文（整条由 SQL 块注释符包住，{@code token} 是含注释符的完整文本）：</p>
 * <pre>{@code
 * 运行时值: <表达式摘要> · <id>
 * }</pre>
 *
 * <p>{@code token} 就是这段<b>精确的</b>占位文本（含 id），回填时按它定位（重复 token 再按出现序号，从后往前替换）。
 * 代价是 id 会显示在屏幕上——它本来就在一条注释里，且「复制可执行 SQL」路径会整段替换掉注释。</p>
 *
 * <h2>模板：只报事实，不猜</h2>
 *
 * <p>{@code template} 是<b>展开形态</b>的结构化原文模板，只由源码里真实存在的字符串字面量拼成，非字面量
 * 位置一律是 {@link #VALUE_MARK}（如 {@code id='%s' }）。取不到时给 {@code null}，含义是「展开形态未知」——
 * 此时面板与回填必须明确告知用户，<b>不许</b>按猜测的形态拼 SQL。</p>
 *
 * <p>两支形态（LOOP 的「首项 / 其余」、TERNARY 的「then / else」）用 {@link #BRANCH_SEPARATOR} 拼在同一个
 * 字符串里：它是 NUL 码位，正常 SQL 字面量里不会出现；构造时若某一支里含该字符，
 * {@link #joinBranches} 直接返回 {@code null}（宁可报「未知」也不做有损编码）。解码请用
 * {@link #getTemplateBranches()} / {@link #getFirstTemplate()} / {@link #getRestTemplate()}，不要自己 split。</p>
 */
public final class RuntimeSlot {

    /** 模板里「非字面量位置」的取值标记：回填时由真实值替换。 */
    public static final String VALUE_MARK = "%s";

    /**
     * 两支模板的分隔符（NUL 码位）。
     *
     * <p>选它是因为正常 SQL 字符串字面量里不会出现；一旦出现，{@link #joinBranches} 判为「无法无损
     * 表达」并返回 {@code null}，而不是把两支拼错。</p>
     */
    public static final String BRANCH_SEPARATOR = "\u0000";

    private final String id;
    private final SlotKind kind;
    private final String expression;
    private final String template;
    private final ParamHint hint;
    private final String token;

    /**
     * @param id         同一次预览内唯一的稳定标识（{@code rt1}..{@code rtN}，按产物中首次出现顺序分配）
     * @param kind       语义类别，决定面板控件与展开方式
     * @param expression 清洗后的表达式摘要（面板显示用，已是安全文本：无反引号问题、引号换同形字符）
     * @param template   展开形态模板；{@code null} = 展开形态未知（不许猜）
     * @param hint       上下文推断出的回填语义（{@code quote} / {@code inList}），推不出就留空
     * @param token      产物里那段精确的占位注释文本（含 id），回填据此定位
     */
    public RuntimeSlot(String id, SlotKind kind, String expression, String template, ParamHint hint, String token) {
        this.id = id;
        this.kind = kind;
        this.expression = expression;
        this.template = template;
        this.hint = hint;
        this.token = token;
    }

    public String getId() {
        return id;
    }

    public SlotKind getKind() {
        return kind;
    }

    public String getExpression() {
        return expression;
    }

    public String getTemplate() {
        return template;
    }

    public ParamHint getHint() {
        return hint;
    }

    public String getToken() {
        return token;
    }

    /**
     * 模板的分支列表（解码 {@code template}）。
     *
     * <ul>
     *  <li>空表 = 无模板（形态未知）；</li>
     *  <li>一个元素 = 单形态（循环体每次追加同样的片段）；</li>
     *  <li>两个元素 = 两支形态（LOOP 首项/其余、TERNARY then/else）。</li>
     * </ul>
     */
    public List<String> getTemplateBranches() {
        if (template == null) {
            return List.of();
        }
        // Kotlin split 保留尾部空串——Java split 须显式 limit=-1 等价
        String[] parts = template.split(BRANCH_SEPARATOR, -1);
        return List.of(parts);
    }

    /**
     * 首项（LOOP）/ 真分支（TERNARY）模板；单形态时与 {@link #getRestTemplate()} 相同。
     *
     * <p>{@code null} = 无模板，或形态无法无损表达。</p>
     */
    public String getFirstTemplate() {
        List<String> branches = getTemplateBranches();
        if (branches.size() == 1 || branches.size() == 2) {
            return branches.get(0);
        }
        return null;
    }

    /**
     * 其余项（LOOP）/ 假分支（TERNARY）模板；单形态时与 {@link #getFirstTemplate()} 相同。
     *
     * <p>{@code null} = 无模板，或形态无法无损表达。</p>
     */
    public String getRestTemplate() {
        List<String> branches = getTemplateBranches();
        if (branches.size() == 1) {
            return branches.get(0);
        }
        if (branches.size() == 2) {
            return branches.get(1);
        }
        return null;
    }

    /** Kotlin data class {@code copy} 的手工重建（全参形态）。 */
    public RuntimeSlot copy(String id, SlotKind kind, String expression, String template, ParamHint hint, String token) {
        return new RuntimeSlot(id, kind, expression, template, hint, token);
    }

    /**
     * 把若干模板分支拼成 {@code RuntimeSlot.template} 的编码形态。
     *
     * @param branches 分支原文（1 个 = 单形态；2 个 = 首项/其余 或 then/else）
     * @return 编码后的模板；分支为空、超过 2 支、或某一支含 {@link #BRANCH_SEPARATOR} 时返回 {@code null}
     *         （= 展开形态未知，调用方不得自行补形态）
     */
    public static String joinBranches(List<String> branches) {
        if (branches.isEmpty() || branches.size() > 2) return null;
        for (String branch : branches) {
            if (branch.contains(BRANCH_SEPARATOR)) return null;
        }
        return String.join(BRANCH_SEPARATOR, branches);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RuntimeSlot)) return false;
        RuntimeSlot other = (RuntimeSlot) o;
        return Objects.equals(id, other.id)
                && kind == other.kind
                && Objects.equals(expression, other.expression)
                && Objects.equals(template, other.template)
                && Objects.equals(hint, other.hint)
                && Objects.equals(token, other.token);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, kind, expression, template, hint, token);
    }

    @Override
    public String toString() {
        return "RuntimeSlot(id=" + id + ", kind=" + kind + ", expression=" + expression
                + ", template=" + template + ", hint=" + hint + ", token=" + token + ")";
    }
}
