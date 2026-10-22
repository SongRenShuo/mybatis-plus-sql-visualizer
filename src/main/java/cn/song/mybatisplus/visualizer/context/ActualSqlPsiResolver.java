package cn.song.mybatisplus.visualizer.context;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 从宿主 PSI 中解析真正的 DatabaseTools SQL PSI。
 *
 * <p>约束：</p>
 * <ol>
 * <li>光标精确命中优先于 owner 扫描；</li>
 * <li>owner 扫描同时受节点数和文本长度约束，截断时绝不把已见单候选冒认成唯一；</li>
 * <li>{@code com.intellij.sql.psi.SqlFile} 只按类名反射检查，DatabaseTools 缺席不会污染插件类加载；</li>
 * <li>本插件自研的 {@code MpSqlMapper} 注入只负责着色/补全，不冒充平台 SQL PSI。</li>
 * </ol>
 *
 * <p>master 的 {@code object ActualSqlPsiResolver} 的 Java 承载（全 static 形态；Kotlin 默认参数 → 重载）。</p>
 */
public final class ActualSqlPsiResolver {

    private ActualSqlPsiResolver() {
    }

    /** Kotlin internal const 的包私有等价（同模块守护测试缝）。 */
    static final int DEFAULT_MAX_OWNER_ELEMENTS = 2_048;
    static final int DEFAULT_MAX_OWNER_TEXT_LENGTH = 128 * 1024;
    private static final String SQL_FILE_FQN = "com.intellij.sql.psi.SqlFile";
    private static final String MP_SQL_MAPPER_LANGUAGE_ID = "MpSqlMapper";

    /** 测试缝：生产始终走字符串类名加载；不得改成 SqlFile.class 直接链接。 */
    interface SqlFileClassLookup {
        @Nullable Class<?> lookup();
    }

    static SqlFileClassLookup sqlFileClassLookup = () -> {
        try {
            return Class.forName(SQL_FILE_FQN, false, ActualSqlPsiResolver.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    };

    public static PreviewSourceContext.SqlPsiResolution resolve(@NotNull PsiFile hostFile) {
        return resolve(hostFile, hostFile, null, DEFAULT_MAX_OWNER_ELEMENTS, DEFAULT_MAX_OWNER_TEXT_LENGTH);
    }

    public static PreviewSourceContext.SqlPsiResolution resolve(@NotNull PsiFile hostFile,
                                                                @Nullable PsiElement owner) {
        return resolve(hostFile, owner, null, DEFAULT_MAX_OWNER_ELEMENTS, DEFAULT_MAX_OWNER_TEXT_LENGTH);
    }

    /** Kotlin 默认参数 {@code maxOwnerElements/maxOwnerTextLength} 的显式重载（带 caretOffset 的调用形态）。 */
    public static PreviewSourceContext.SqlPsiResolution resolve(@NotNull PsiFile hostFile,
                                                                @Nullable PsiElement owner,
                                                                @Nullable Integer caretOffset) {
        return resolve(hostFile, owner, caretOffset, DEFAULT_MAX_OWNER_ELEMENTS, DEFAULT_MAX_OWNER_TEXT_LENGTH);
    }

    public static PreviewSourceContext.SqlPsiResolution resolve(@NotNull PsiFile hostFile,
                                                                @Nullable PsiElement owner,
                                                                @Nullable Integer caretOffset,
                                                                int maxOwnerElements,
                                                                int maxOwnerTextLength) {
        ApplicationManager.getApplication().assertReadAccessAllowed();
        Class<?> sqlFileClass = sqlFileClassLookup.lookup();
        if (sqlFileClass == null) {
            return PreviewSourceContext.SqlPsiResolution.DatabaseToolsUnavailable.INSTANCE;
        }

        PsiDocumentManager documentManager = PsiDocumentManager.getInstance(hostFile.getProject());
        com.intellij.openapi.editor.Document document = documentManager.getDocument(hostFile);
        if (document != null && !documentManager.isCommitted(document)) {
            return PreviewSourceContext.SqlPsiResolution.Uncommitted.INSTANCE;
        }

        InjectedLanguageManager injectionManager = InjectedLanguageManager.getInstance(hostFile.getProject());
        if (caretOffset != null && caretOffset >= 0 && caretOffset < hostFile.getTextLength()) {
            PsiElement injectedElement = injectionManager.findInjectedElementAt(hostFile, caretOffset);
            PsiFile exact = injectedElement != null ? injectedElement.getContainingFile() : null;
            if (exact != null && isActualSqlFile(exact, sqlFileClass)) {
                return new PreviewSourceContext.SqlPsiResolution.Exact(exact);
            }
        }

        if (owner == null || !owner.isValid() || owner.getContainingFile() != hostFile) {
            return PreviewSourceContext.SqlPsiResolution.None.INSTANCE;
        }
        PsiElement boundedOwner = owner;
        if (maxOwnerElements <= 0 || maxOwnerTextLength <= 0
                || boundedOwner.getTextLength() > maxOwnerTextLength) {
            return PreviewSourceContext.SqlPsiResolution.None.INSTANCE;
        }

        Set<PsiFile> candidates = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.List<PsiFile> orderedCandidates = new java.util.ArrayList<>();
        ArrayDeque<PsiElement> stack = new ArrayDeque<>();
        stack.addLast(boundedOwner);
        int visited = 0;
        boolean truncated = false;

        while (!stack.isEmpty()) {
            if (visited >= maxOwnerElements) {
                truncated = true;
                break;
            }
            PsiElement element = stack.removeLast();
            visited++;

            if (element instanceof PsiLanguageInjectionHost host) {
                List<Pair<PsiElement, com.intellij.openapi.util.TextRange>> injectedFiles =
                        injectionManager.getInjectedPsiFiles(host);
                if (injectedFiles != null) {
                    for (Pair<PsiElement, com.intellij.openapi.util.TextRange> injected : injectedFiles) {
                        if (injected.first == null || injected.first.getContainingFile() == null) {
                            continue;
                        }
                        PsiFile file = injected.first.getContainingFile();
                        if (isActualSqlFile(file, sqlFileClass) && candidates.add(file)) {
                            orderedCandidates.add(file);
                        }
                    }
                }
            }

            PsiElement child = element.getLastChild();
            while (child != null) {
                stack.addLast(child);
                child = child.getPrevSibling();
            }
        }

        if (truncated) {
            return PreviewSourceContext.SqlPsiResolution.None.INSTANCE;
        }
        if (orderedCandidates.isEmpty()) {
            return PreviewSourceContext.SqlPsiResolution.None.INSTANCE;
        }
        if (orderedCandidates.size() == 1) {
            return new PreviewSourceContext.SqlPsiResolution.UniqueInOwner(orderedCandidates.get(0));
        }
        return new PreviewSourceContext.SqlPsiResolution.Ambiguous(List.copyOf(orderedCandidates));
    }

    private static boolean isActualSqlFile(PsiFile file, Class<?> sqlFileClass) {
        return !MP_SQL_MAPPER_LANGUAGE_ID.equals(file.getLanguage().getID())
                && sqlFileClass.isInstance(file);
    }
}
