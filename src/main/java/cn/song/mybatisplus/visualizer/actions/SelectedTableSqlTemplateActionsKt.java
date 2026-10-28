// T-SyncK2JMaster104 波B 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/actions/SelectedTableSqlTemplateActions.kt 移植
// （T-SqlTemplateCaret + T-SqlTemplateUx P2 新增顶层函数族——原文件顶层仅类声明，本门面为本轮增量新建）
package cn.song.mybatisplus.visualizer.actions;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import cn.song.mybatisplus.visualizer.util.UiTextClipboard;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code SelectedTableSqlTemplateActions.kt} 顶层函数（T-SqlTemplateCaret / T-SqlTemplateUx P2）的 Java 承载类。
 *
 * <p>目标文本解析（选区优先、光标处标识符退化）、错误定位锚点、失败通知的可操作项装配。</p>
 */
public final class SelectedTableSqlTemplateActionsKt {

    private SelectedTableSqlTemplateActionsKt() {
    }

    /**
     * T-SqlTemplateCaret：编辑器目标文本——<b>非空选区优先</b>（逐字节原样返回选区文本），
     * 否则取光标处标识符。两路都取不到返回 null（动作据此禁用）。
     *
     * <p>空白选区也退化到光标处：真机实证预览弹窗的 SQL 文本区在「无选区」时会报告一个纯空格选区
     * （{@code hasSelection=true}、{@code selectedText} 为 14 个空格），只按 {@code hasSelection()} 分流
     * 会把这个场景重新判成「无内容」而保持禁用。</p>
     */
    @Nullable
    public static String editorTemplateText(Editor editor) {
        String selected = editor.getSelectionModel().hasSelection()
                ? editor.getSelectionModel().getSelectedText() : null;
        String caretIdentifier = identifierAtCaret(editor.getDocument().getCharsSequence(), editor.getCaretModel().getOffset());
        return resolveTemplateText(selected, caretIdentifier);
    }

    /**
     * T-SqlTemplateCaret：目标文本优先级（纯逻辑，便于单测）——selectedText 非空则原样返回；
     * 否则退回 caretIdentifier（仍需非空）；都没有返回 null。
     */
    @Nullable
    public static String resolveTemplateText(@Nullable String selectedText, @Nullable String caretIdentifier) {
        if (selectedText != null && !selectedText.isBlank()) return selectedText;
        return caretIdentifier != null && !caretIdentifier.isBlank() ? caretIdentifier : null;
    }

    /** 标识符字符：字母/数字/下划线（与 log/LiteralParamRecovery、sql/ExplainGate 等既有 isWordChar 同族）。 */
    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** 表名位置不可能出现的关键字（小写比较）——出现即判非法（master 686f582f）。 */
    private static final Set<String> NON_TABLE_KEYWORDS = Set.of(
            "select", "insert", "update", "delete", "drop", "truncate", "alter", "create",
            "from", "where", "join", "with", "limit", "explain");

    /**
     * 只接受一个表名或以点连接的标识符段；不截取候选前缀来掩盖多语句输入
     * （master 686f582f：单表标识符校验后才提交快捷 SQL）。
     */
    @Nullable
    public static String validatedTableIdentifier(@Nullable String candidate) {
        if (candidate == null) return null;
        String text = candidate.trim();
        if (text.isEmpty() || text.length() > 512) return null;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isISOControl(text.charAt(i))) return null;
        }
        if (text.contains(";") || text.contains("--") || text.contains("/*") || text.contains("*/")) return null;
        int index = 0;
        while (index < text.length()) {
            Character closer = QUOTE_OPENERS.get(text.charAt(index));
            if (closer != null) {
                index++;
                boolean content = false;
                boolean closed = false;
                while (index < text.length()) {
                    if (text.charAt(index) == closer) {
                        if (index + 1 < text.length() && text.charAt(index + 1) == closer) {
                            content = true;
                            index += 2;
                            continue;
                        }
                        index++;
                        closed = true;
                        break;
                    }
                    content = true;
                    index++;
                }
                if (!content || !closed) return null;
            } else {
                int start = index;
                while (index < text.length() && isIdentifierChar(text.charAt(index))) index++;
                if (start == index || NON_TABLE_KEYWORDS.contains(text.substring(start, index).toLowerCase())) {
                    return null;
                }
            }
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
            if (index == text.length()) return text;
            if (text.charAt(index) != '.') return null;
            index++;
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
            if (index == text.length()) return null;
        }
        return null;
    }

    /** 开引号 → 对应闭引号（SQL 三种标识符引号：反引号 / 双引号 / 方括号）。 */
    private static final Map<Character, Character> QUOTE_OPENERS = Map.of('`', '`', '"', '"', '[', ']');

    /** 闭引号 → 对应开引号。 */
    private static final Map<Character, Character> QUOTE_CLOSERS = Map.of('`', '`', '"', '"', ']', '[');

    /**
     * T-SqlTemplateCaret：取 caretOffset 处的标识符（表名/片段），取不到返回 null。
     *
     * <p>实现选型：<b>纯 {@code CharSequence} + 偏移量的词边界扫描</b>，不碰 PSI、不取编辑器高亮器/Lexer。
     * 理由是三条硬约束：</p>
     * <ol>
     *  <li>动作会在<b>任意编辑器</b>里被调用（mapper XML、{@code .sql}、Java、预览弹窗内的 SQL 文本区），
     *      语言未知、SQL 注入也未必生效——PSI 锚点与 Lexer token 都依赖具体语言/注入，命中面随宿主而变；</li>
     *  <li>{@code Document.getCharsSequence()} 是免拷贝视图（mcp/McpSourceLocator 同款用法），
     *      扫描只与光标所在词的长度相关，无慢操作；</li>
     *  <li>纯函数（入参文本 + 偏移）可脱离编辑器做纯逻辑单测。</li>
     * </ol>
     *
     * <p>规则：</p>
     * <ul>
     *  <li>光标优先压右侧字符、否则压左侧字符，取该字符所在的极大标识符串；</li>
     *  <li><b>引号包裹一起取</b>：紧贴的 {@code `} / {@code "} / {@code [ ]} 成对出现时连引号一并返回
     *      （与选区路径「原样当表名」一致）；</li>
     *  <li><b>限定名跨点</b>：{@code db.tbl} / {@code `db`.`tbl`} / {@code [db].[tbl]} 这类以 {@code .}
     *      连接的段一并取全；</li>
     *  <li>单引号字符串<b>不</b>连引号（{@code 'x'} 取 {@code x}）：{@code FROM 'x'} 非法，带单引号只会
     *      制造必然失败；</li>
     *  <li>光标落在空白/标点/单引号字符串边界上（两侧都无标识符字符）返回 null。</li>
     * </ul>
     */
    @Nullable
    public static String identifierAtCaret(CharSequence text, int caretOffset) {
        int n = text.length();
        if (n == 0) return null;
        int caret = Math.max(0, Math.min(caretOffset, n));

        int start;
        int end;
        if (caret < n && isIdentifierChar(text.charAt(caret))) {
            start = caret;
            while (start > 0 && isIdentifierChar(text.charAt(start - 1))) start--;
            end = caret + 1;
            while (end < n && isIdentifierChar(text.charAt(end))) end++;
        } else if (caret > 0 && isIdentifierChar(text.charAt(caret - 1))) {
            start = caret - 1;
            while (start > 0 && isIdentifierChar(text.charAt(start - 1))) start--;
            end = caret;
            while (end < n && isIdentifierChar(text.charAt(end))) end++;
        } else if (caret < n && QUOTE_OPENERS.containsKey(text.charAt(caret))) {
            Integer scanned = scanSegmentRight(text, caret);
            if (scanned == null) return null;
            start = caret;
            end = scanned;
        } else if (caret > 0 && QUOTE_CLOSERS.containsKey(text.charAt(caret - 1))) {
            Integer scanned = scanSegmentLeft(text, caret);
            if (scanned == null) return null;
            start = scanned;
            end = caret;
        } else {
            return null;
        }

        // 引号包裹扩展：紧贴的成对引号把整段一并纳入
        Character wrapOpener = start > 0 && end < n ? QUOTE_OPENERS.get(text.charAt(start - 1)) : null;
        if (wrapOpener != null && wrapOpener == text.charAt(end)) {
            start--;
            end++;
        }

        // 限定名扩展（右：seg.next）
        while (end < n && text.charAt(end) == '.') {
            Integer nextEnd = scanSegmentRight(text, end + 1);
            if (nextEnd == null) break;
            end = nextEnd;
        }
        // 限定名扩展（左：prev.seg）
        while (start >= 2 && text.charAt(start - 1) == '.') {
            Integer prevStart = scanSegmentLeft(text, start - 1);
            if (prevStart == null) break;
            start = prevStart;
        }

        return text.subSequence(start, end).toString();
    }

    /** 从 startIn 起扫一段标识符（可带引号包裹），返回该段结束（exclusive）偏移；不成段返回 null。 */
    private static Integer scanSegmentRight(CharSequence text, int startIn) {
        int n = text.length();
        if (startIn >= n) return null;
        Character closerBoxed = QUOTE_OPENERS.get(text.charAt(startIn));
        if (closerBoxed != null) {
            char closer = closerBoxed;
            int i = startIn + 1;
            while (i < n && isIdentifierChar(text.charAt(i))) i++;
            // 至少一个内容字符 + 匹配闭引号才成段（"" 这类空引号不成段）
            return i < n && text.charAt(i) == closer && i > startIn + 1 ? i + 1 : null;
        }
        if (!isIdentifierChar(text.charAt(startIn))) return null;
        int i = startIn + 1;
        while (i < n && isIdentifierChar(text.charAt(i))) i++;
        return i;
    }

    /** 扫到 endEx（exclusive）之前为止的一段标识符（可带引号包裹），返回该段起始偏移；不成段返回 null。 */
    private static Integer scanSegmentLeft(CharSequence text, int endEx) {
        if (endEx <= 0) return null;
        Character openerBoxed = QUOTE_CLOSERS.get(text.charAt(endEx - 1));
        if (openerBoxed != null) {
            char opener = openerBoxed;
            int i = endEx - 2;
            while (i >= 0 && isIdentifierChar(text.charAt(i))) i--;
            // 找到匹配开引号且中间至少一个内容字符才成段
            return i >= 0 && text.charAt(i) == opener && i <= endEx - 3 ? i : null;
        }
        if (!isIdentifierChar(text.charAt(endEx - 1))) return null;
        int i = endEx - 1;
        while (i > 0 && isIdentifierChar(text.charAt(i - 1))) i--;
        return i;
    }

    /**
     * T-SqlTemplateUx P2：错误定位锚点（触发编辑器 + 选区起始行，0-based）。
     *
     * @param file  语句来源文件
     * @param line0 选区起始行（0-based）
     */
    public record ErrorLocateAnchor(VirtualFile file, int line0) {
    }

    /**
     * T-SqlTemplateUx P2：从触发上下文解析定位锚点——编辑器、文件、选区三者缺一或文件已失效
     * 一律返回 null（调用方据此不给定位动作，退化为只给「复制错误信息」）。
     *
     * <p>与预览面板的 {@code SqlPreviewService.SourceAnchor} 同语义——都是「语句来源位置」；差别只在来源：
     * 面板锚点由预览链登记，快捷模板动作没有预览链，故在动作触发时（EDT）就地取触发编辑器。
     * 只存 VirtualFile 与行号（不持有 Editor 引用，避免动作后编辑器被释放导致的悬空引用）。
     * 锚点取<b>选区起始行</b>：快捷模板的语句由选区文本生成，选区即语句来源；不解析错误文本
     * （错误文本里的位置指向被执行 SQL，与 mapper 原文行不一一对应，v1 不换算——与面板口径一致）。</p>
     */
    @Nullable
    public static ErrorLocateAnchor resolveErrorLocateAnchor(@Nullable Editor editor, @Nullable VirtualFile file) {
        if (editor == null) return null;
        VirtualFile target = file != null ? file : FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (target == null || !target.isValid()) return null;
        int offset;
        try {
            offset = editor.getSelectionModel().getSelectionStart();
        } catch (Throwable ignored) {
            return null; // getOrNull 语义
        }
        int line0;
        try {
            line0 = editor.getDocument().getLineNumber(offset);
        } catch (Throwable ignored) {
            return null; // getOrNull 语义
        }
        return new ErrorLocateAnchor(target, line0);
    }

    /**
     * T-SqlTemplateUx P2：失败通知里的一个可操作项（文案 key + 点击行为）。
     *
     * <p>用「key + runnable」而非直接构造 {@link NotificationAction}，是为了让「挂了哪些动作、点了做什么」
     * 成为不依赖真机即可断言的纯逻辑（NotificationAction 的点击回调只能由平台事件驱动）。</p>
     */
    public static final class SqlTemplateErrorAction {
        public final String titleKey;
        public final Runnable run;

        public SqlTemplateErrorAction(String titleKey, Runnable run) {
            this.titleKey = titleKey;
            this.run = run;
        }
    }

    /**
     * T-SqlTemplateUx P2：失败通知的可操作项清单——locate 非 null 才给「定位到出错语句」，
     * 「复制错误信息」恒给。文案 key 复用面板既有项（{@code execute.error.locate} / {@code panel.copyError}），
     * 保证两条入口（预览面板 / 快捷模板动作）用户可见的措辞一致。
     */
    public static List<SqlTemplateErrorAction> sqlTemplateErrorActions(@Nullable Runnable locate, Runnable copy) {
        List<SqlTemplateErrorAction> actions = new ArrayList<>();
        if (locate != null) actions.add(new SqlTemplateErrorAction("execute.error.locate", locate));
        actions.add(new SqlTemplateErrorAction("panel.copyError", copy));
        return actions;
    }

    /**
     * T-SqlTemplateUx P2：快捷模板执行失败的富反馈通知——与预览面板同款的可操作项接在气泡上
     * （做法②：不新建面板，直接给 Notification 挂 NotificationAction）。
     */
    public static Notification buildSqlTemplateErrorNotification(String errorMsg, @Nullable Runnable locate, Runnable copy) {
        Notification notification = new Notification(
                SelectedTableSqlTemplateAction.NOTIFICATION_GROUP_ID,
                MpSqlVisualizerBundle.message("sqlTemplate.notify.title"),
                errorMsg,
                NotificationType.WARNING);
        for (SqlTemplateErrorAction action : sqlTemplateErrorActions(locate, copy)) {
            Runnable behavior = action.run;
            notification.addAction(NotificationAction.createSimpleExpiring(
                    MpSqlVisualizerBundle.message(action.titleKey), behavior));
        }
        return notification;
    }

    /** T-SqlTemplateUx P2：投递失败反馈通知（复制动作走 UiTextClipboard 统一收口）。 */
    public static void notifySqlTemplateError(Project project, String errorMsg, @Nullable Runnable locate) {
        buildSqlTemplateErrorNotification(errorMsg, locate, () -> UiTextClipboard.copy(errorMsg)).notify(project);
    }
}
