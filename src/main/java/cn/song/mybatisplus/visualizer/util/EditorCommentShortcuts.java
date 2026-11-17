// T-SyncK2JMaster482 自 master util/EditorCommentShortcuts.kt 移植（T-PreviewLineCommentDash O-134/D136）
package cn.song.mybatisplus.visualizer.util;

import com.intellij.ide.lightEdit.LightEditCompatible;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预览编辑器「行注释快捷键」统一为 {@code --} 双横线（T-PreviewLineCommentDash O-134/D136）。
 *
 * <p>为什么必须在这个层位（开发计划 §一 全链实证）：预览文件被 DialectPreMapping 强制映射 MySQL 方言
 * → 平台注释动作取 commenter 链拿到 {@code MysqlCommenter} → 行前缀 {@code #}（javap 双版本实锤
 * {@code SHARP_LINE_PREFIX = "# "}）→ 对 PostgreSQL 执行报 {@code [42601] syntax error}。
 *
 * <p><b>接管层位（实施期前提被推翻后的修正，见计划 §六）</b>：计划原定 B3＝{@code EditorActionManager.setActionHandler}
 * 链式覆写，实测<b>不可用</b>——平台把 {@link IdeActions#ACTION_COMMENT_LINE}（"CommentByLineComment"）注册到
 * {@code CommentByLineCommentAction}（{@code LangActions.xml} 实锤），它是 {@code MultiCaretCodeInsightAction} 的子类、
 * <b>不是</b> {@code EditorAction}，而 {@code EditorActionManagerImpl.getActionHandler/setActionHandler} 对 id 做
 * {@code checkcast EditorAction}（字节码实证）⇒ 调用即 {@code ClassCastException}（单测首跑 15/15 红实证）。
 * 修正为在<b>动作注册层</b>接管：{@code ActionManager.replaceAction(ACTION_COMMENT_LINE, 自有 AnAction)}，
 * 自有动作只对<b>打标编辑器</b>走本地逐行切换，其余一律原样委托平台原动作（update/actionPerformed 双向委托，
 * 菜单文案与图标从原动作模板 presentation 复制，键位绑定按 id 天然保留）。
 *
 * <p>作用域＝编辑器 userData 打标 + 引用计数：首个打标编辑器出现时换入，最后一个卸载时换回原动作。
 * 不触语言级 {@code lang.commenter} EP（B1 会波及真实 MySQL 控制台/文件）；不接管块注释 Ctrl+Shift+/（D137：
 * javap 双版本核实基类 {@code SqlCommenter.getBlockCommentPrefix()} 为斜杠星号、{@code getBlockCommentSuffix()} 为
 * 星号斜杠，MySQL/PG 块注释语义一致）。
 *
 * <p>语义契约：受影响行集 = 选区触及的行（行粒度；无选区 = 光标行，多光标各算各的、自底向上套用）；
 * <b>整段同态切换</b>（对齐平台手感）：全部非空行已是注释 → 剥除；否则补 {@code -- }，其中已是注释的行幂等跳过。
 * 前缀双认 {@code --} 与遗留 {@code #}（本轮修复前插入的 {@code #} 残留行也要能剥掉；双认先例 MysqlClientGSuffix.isCommentLine），
 * 剥除时连带吞掉前缀后<b>恰好一个</b>空格。文档改动包 WriteCommandAction（一步撤销）。
 *
 * <p>测试缝照 {@link EditorCopyShortcuts}：纯逻辑（行级切换）与 {@link DashLineCommentAction} 均 public，
 * 单测可直接构造动作 + 自建 DataContext 驱动（不依赖真键盘分发）。
 *
 * <p>Kotlin {@code object} → 全静态工具类；嵌套 {@code internal class DashLineCommentAction} → public 静态嵌套类。
 */
public final class EditorCommentShortcuts {

    private EditorCommentShortcuts() {}

    /** 打标 Key：userData 在场 = 该编辑器的行注释走本接管。 */
    private static final Key<Boolean> MARKER = Key.create("mpsv.editor.lineCommentDash");

    /** 行注释插入前缀（PG/MySQL 通用双横线）。 */
    private static final String DASH_PREFIX = "--";

    /** 遗留 {@code #} 前缀（本轮修复前 MySQL commenter 产出，残留行兼容剥除）。 */
    private static final String LEGACY_PREFIX = "#";

    private static DashLineCommentAction installedAction = null;
    private static int refCount = 0;

    /** 给 {@code editor} 打标并确保全局动作已换入（幂等：重复打标不重复计数）。 */
    public static synchronized void install(@NotNull Editor editor) {
        if (Boolean.TRUE.equals(editor.getUserData(MARKER))) return;
        editor.putUserData(MARKER, true);
        if (installedAction == null) {
            ActionManager manager = ActionManager.getInstance();
            AnAction original = manager.getAction(IdeActions.ACTION_COMMENT_LINE);
            if (original != null) {
                DashLineCommentAction replacement = new DashLineCommentAction(original);
                cn.song.mybatisplus.visualizer.util.ActionReplaceSilentlyKt.replaceActionSilently(manager, IdeActions.ACTION_COMMENT_LINE, replacement);
                installedAction = replacement;
                refCount = 0;
            }
        }
        refCount++;
    }

    /** 摘除 {@code editor} 打标；最后一个打标编辑器卸载时换回原动作（幂等：未打标零动作）。 */
    public static synchronized void uninstall(@NotNull Editor editor) {
        if (!Boolean.TRUE.equals(editor.getUserData(MARKER))) return;
        editor.putUserData(MARKER, null);
        refCount--;
        if (refCount <= 0) {
            if (installedAction != null) {
                DashLineCommentAction replacement = installedAction;
                ActionManager manager = ActionManager.getInstance();
                // 只在当前在位的仍是本接管件时换回（防覆盖别的组件在期间的替换）
                if (manager.getAction(IdeActions.ACTION_COMMENT_LINE) == replacement) {
                    cn.song.mybatisplus.visualizer.util.ActionReplaceSilentlyKt.replaceActionSilently(manager, IdeActions.ACTION_COMMENT_LINE, replacement.getOriginal());
                }
            }
            installedAction = null;
            refCount = 0;
        }
    }

    /** 该编辑器是否已被本接管打标（接线覆盖测试与排障用；判定与动作内一致）。 */
    public static boolean isMarked(@NotNull Editor editor) {
        return Boolean.TRUE.equals(editor.getUserData(MARKER));
    }

    /**
     * 行级切换纯函数：<b>全已注释 → 全剥；否则补 {@code -- }，已注释行幂等跳过</b>。
     * 空白行参与范围但双态都原样保留（剥无可剥、补了徒增噪音）。
     */
    public static List<String> toggleLines(@NotNull List<String> lines) {
        List<String> nonBlank = new ArrayList<>();
        for (String line : lines) {
            if (!line.isBlank()) nonBlank.add(line);
        }
        boolean uncomment = !nonBlank.isEmpty();
        for (String line : nonBlank) {
            if (!isCommentedLine(line)) {
                uncomment = false;
                break;
            }
        }
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line.isBlank()) {
                out.add(line);
            } else if (uncomment) {
                out.add(stripCommentPrefix(line));
            } else if (isCommentedLine(line)) {
                out.add(line);
            } else {
                out.add(addCommentPrefix(line));
            }
        }
        return out;
    }

    /** 行内容（跳过前导空白）是否以行注释开头——{@code --} 与遗留 {@code #} 双认（先例 MysqlClientGSuffix.kt:99-104）。 */
    public static boolean isCommentedLine(@NotNull String line) {
        String t = line.stripLeading();
        return t.startsWith(DASH_PREFIX) || t.startsWith(LEGACY_PREFIX);
    }

    /** 在首个非空白字符前插 {@code -- }（空白行原样保留，由调用方过滤）。 */
    private static String addCommentPrefix(@NotNull String line) {
        int i = -1;
        for (int k = 0; k < line.length(); k++) {
            if (!Character.isWhitespace(line.charAt(k))) {
                i = k;
                break;
            }
        }
        return i < 0 ? line : line.substring(0, i) + DASH_PREFIX + " " + line.substring(i);
    }

    /** 剥 {@code --} / {@code #} 前缀并连带吞掉前缀后恰好一个空格；无前缀原样返回。 */
    private static String stripCommentPrefix(@NotNull String line) {
        String t = line.stripLeading();
        String prefix;
        if (t.startsWith(DASH_PREFIX)) {
            prefix = DASH_PREFIX;
        } else if (t.startsWith(LEGACY_PREFIX)) {
            prefix = LEGACY_PREFIX;
        } else {
            return line;
        }
        int start = line.length() - t.length();
        int end = start + prefix.length();
        if (end < line.length() && line.charAt(end) == ' ') end++;
        return line.substring(0, start) + line.substring(end);
    }

    /**
     * 对打标编辑器执行逐行切换（WriteCommandAction 包裹，一步撤销）。
     * 多光标各算各的受影响行集、自底向上套用（后套用的不挪先套用的偏移）。
     */
    private static void toggleLineComments(@NotNull Editor editor) {
        Document document = editor.getDocument();
        if (!document.isWritable() || editor.isViewer()) return;
        // 每个光标的受影响行集：有选区 → 选区触及的行（行粒度）；无选区 → 光标所在行
        List<Affected> raw = new ArrayList<>();
        for (Caret caret : editor.getCaretModel().getAllCarets()) {
            int caretOffset = caret.getOffset();
            if (caret.hasSelection()) {
                int startLine = document.getLineNumber(caret.getSelectionStart());
                int endLine = document.getLineNumber(caret.getSelectionEnd());
                raw.add(new Affected(caret, startLine, Math.max(startLine, endLine), true,
                        caretOffset - document.getLineStartOffset(startLine)));
            } else {
                int line = document.getLineNumber(caretOffset);
                raw.add(new Affected(caret, line, line, false, caretOffset - document.getLineStartOffset(line)));
            }
        }
        // distinctBy { startLine to endLine }（保首现顺序）→ sortedByDescending { startLine }（稳定）
        Map<String, Affected> distinct = new LinkedHashMap<>();
        for (Affected a : raw) {
            distinct.putIfAbsent(a.startLine + ":" + a.endLine, a);
        }
        List<Affected> affected = new ArrayList<>(distinct.values());
        affected.sort((x, y) -> Integer.compare(y.startLine, x.startLine));
        if (affected.isEmpty()) return;
        Project project = editor.getProject();
        String commandName = MpSqlVisualizerBundle.message("preview.lineComment.commandName");
        Runnable applyEdits = () -> {
            for (Affected a : affected) {
                int startOffset = document.getLineStartOffset(a.startLine);
                int endOffset = document.getLineEndOffset(a.endLine);
                String oldText = document.getText(new TextRange(startOffset, endOffset));
                List<String> oldLines = List.of(oldText.split("\n", -1));
                List<String> newLines = toggleLines(oldLines);
                if (newLines.equals(oldLines)) continue;
                String newText = String.join("\n", newLines);
                document.replaceString(startOffset, endOffset, newText);
                // 行索引不因切换而变（不增删行），光标/选区按新文本复位
                int newEnd = startOffset + newText.length();
                if (a.hasSelection) {
                    a.caret.setSelection(startOffset, newEnd);
                }
                int newLineStart = document.getLineStartOffset(a.startLine);
                a.caret.moveToOffset(Math.min(newLineStart + a.caretColumn, document.getLineEndOffset(a.startLine)));
            }
        };
        if (project != null) {
            WriteCommandAction.runWriteCommandAction(project, commandName, null, applyEdits);
        } else {
            // 无项目编辑器（三级降级兜底分支）：无命令宿主，仅保证写动作合法
            ApplicationManager.getApplication().runWriteAction(applyEdits);
        }
    }

    /** 单光标受影响行集（局部 {@code data class Affected} → 私有静态类）。 */
    private static final class Affected {
        final Caret caret;
        final int startLine;
        final int endLine;
        final boolean hasSelection;
        final int caretColumn;

        Affected(Caret caret, int startLine, int endLine, boolean hasSelection, int caretColumn) {
            this.caret = caret;
            this.startLine = startLine;
            this.endLine = endLine;
            this.hasSelection = hasSelection;
            this.caretColumn = caretColumn;
        }
    }

    /**
     * 换入的动作：打标编辑器 → 本地逐行切换；其余 → <b>原样委托平台原动作</b>（update/actionPerformed 双向，
     * 零行为差异）。{@code DumbAware} + {@code LightEditCompatible} 与原动作（{@code CommentByLineCommentAction} 实现两者）
     * 对齐——否则换入后在索引/轻编辑态会被平台整体禁用，Ctrl+/ 在所有编辑器里失效（回归）。
     */
    public static class DashLineCommentAction extends DumbAwareAction implements LightEditCompatible {

        private final AnAction original;

        public DashLineCommentAction(@NotNull AnAction original) {
            this.original = original;
            // 换入的是同一 action id：菜单/工具栏文案与图标沿用原动作模板，表现层不得退化
            getTemplatePresentation().copyFrom(original.getTemplatePresentation());
        }

        /** 被委托的平台原动作（Kotlin {@code internal val original} → public getter）。 */
        @NotNull
        public AnAction getOriginal() {
            return original;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            Editor marked = markedEditor(e);
            if (marked != null) {
                e.getPresentation().setEnabled(marked.getDocument().isWritable() && !marked.isViewer());
                e.getPresentation().setVisible(true);
                return;
            }
            original.update(e);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Editor marked = markedEditor(e);
            if (marked != null) {
                toggleLineComments(marked);
                return;
            }
            original.actionPerformed(e);
        }

        /** 事件上下文里的编辑器仅当已打标才由本接管处理（EDITOR 缺失/未打标 → null → 委托）。 */
        @Nullable
        private Editor markedEditor(@NotNull AnActionEvent e) {
            Editor editor = e.getData(CommonDataKeys.EDITOR);
            if (editor == null) return null;
            return Boolean.TRUE.equals(editor.getUserData(MARKER)) ? editor : null;
        }
    }
}
