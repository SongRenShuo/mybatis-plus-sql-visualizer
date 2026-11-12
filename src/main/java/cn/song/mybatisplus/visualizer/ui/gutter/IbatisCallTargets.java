// T-SyncK2JMaster183 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/IbatisStatementPreview.kt 移植（拆件：internal object IbatisCallTargets）
package cn.song.mybatisplus.visualizer.ui.gutter;

import cn.song.mybatisplus.visualizer.ui.gutter.JdbcTemplateSqlGutterLineMarkerProvider.IbatisCallYieldGate;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 光标 / 方法体 → {@link IbatisCallTarget} 的定位助手（快捷键与 MCP 共用；gutter provider 复用
 * {@link #ownerNamespace} 与同一形状门）。
 *
 * <p>形状门直接复用 {@link IbatisCallYieldGate#isIbatisStatementIdCall}——与 JdbcTemplate 通道让位、
 * 方法定义行让位是<b>同一个函数</b>，三处不会漂移出第二份正则。</p>
 */
public final class IbatisCallTargets {

    private IbatisCallTargets() {
    }

    /** 方法体文本长度护栏（字符）：超过则不扫描（与 T-DaoSql-04 同量级）。 */
    private static final int MAX_BODY_TEXT_LENGTH = 200_000;

    /**
     * 最近的外层调用是 iBatis 形状即返回；否则 null（O(1) 语法门，零 resolve）。
     *
     * <p>两跳定位：① 光标在调用表达式内（方法名 / 实参 / 限定符）→ 直接上爬；
     * ② 光标在<b>包含调用的语句</b>上（{@code return client.queryForX(...)} 的 {@code return} 关键字不是调用表达式
     * 的祖先，是最常见的落点）→ 在该语句子树里取首个 iBatis 形状调用。</p>
     */
    @Nullable
    public static IbatisCallTarget resolve(@NotNull PsiElement element) {
        IbatisCallTarget direct =
            targetOf(PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression.class, false));
        if (direct != null) {
            return direct;
        }
        PsiStatement statement = PsiTreeUtil.getParentOfType(element, PsiStatement.class, false);
        if (statement == null) {
            return null;
        }
        IbatisCallTarget earliest = null;
        int earliestOffset = Integer.MAX_VALUE;
        for (PsiMethodCallExpression call : PsiTreeUtil.collectElementsOfType(statement, PsiMethodCallExpression.class)) {
            IbatisCallTarget target = targetOf(call);
            if (target == null) {
                continue;
            }
            int offset = target.call() != null ? target.call().getTextRange().getStartOffset() : Integer.MAX_VALUE;
            if (offset < earliestOffset) {
                earliestOffset = offset;
                earliest = target;
            }
        }
        return earliest;
    }

    /**
     * 方法体内全部 iBatis 形状调用（按 offset 序）。
     *
     * <p>带两道廉价预筛（对齐 {@code StringSqlMethodGutterLineMarkerProvider.hasStringSqlCall} 的形态）：
     * 无方法体 / 超长方法体 / 方法体不含引号 → 直接空；避免快捷键在普通方法上白付子树遍历。</p>
     */
    @NotNull
    public static List<IbatisCallTarget> callsInMethod(@Nullable PsiMethod method) {
        if (method == null || method.getBody() == null) {
            return List.of();
        }
        PsiElement body = method.getBody();
        if (body.getTextLength() > MAX_BODY_TEXT_LENGTH) {
            return List.of();
        }
        if (!body.getText().contains("\"")) {
            return List.of();
        }
        List<IbatisCallTarget> targets = new ArrayList<>();
        for (PsiMethodCallExpression call : PsiTreeUtil.collectElementsOfType(body, PsiMethodCallExpression.class)) {
            IbatisCallTarget target = targetOf(call);
            if (target != null) {
                targets.add(target);
            }
        }
        targets.sort((a, b) -> {
            int oa = a.call() != null ? a.call().getTextRange().getStartOffset() : Integer.MAX_VALUE;
            int ob = b.call() != null ? b.call().getTextRange().getStartOffset() : Integer.MAX_VALUE;
            return Integer.compare(oa, ob);
        });
        return targets;
    }

    /** 调用点所属类的限定名（裸 id 的「同 namespace 优先」排序偏好，与 L8 handler 同语义）。 */
    @Nullable
    public static String ownerNamespace(@NotNull PsiElement element) {
        PsiElement current = element;
        int depth = 0;
        while (current != null && depth++ < 6) {
            if (current instanceof PsiClass owner) {
                return owner.getQualifiedName();
            }
            current = current.getParent();
        }
        return null;
    }

    @Nullable
    private static IbatisCallTarget targetOf(@Nullable PsiMethodCallExpression call) {
        if (call == null) {
            return null;
        }
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null) {
            return null;
        }
        // 形状门与 gutter 让位同一函数：入口方法名 + 首参 String 字面量 + 标识符形态
        if (!IbatisCallYieldGate.isIbatisStatementIdCall(call, methodName)) {
            return null;
        }
        com.intellij.psi.PsiExpression[] expressions = call.getArgumentList().getExpressions();
        if (expressions.length == 0 || !(expressions[0] instanceof PsiLiteralExpression literal)) {
            return null;
        }
        Object value = literal.getValue();
        if (!(value instanceof String s)) {
            return null;
        }
        String statementId = s.trim();
        if (statementId.isEmpty()) {
            return null;
        }
        return new IbatisCallTarget(call, statementId, ownerNamespace(call));
    }
}
