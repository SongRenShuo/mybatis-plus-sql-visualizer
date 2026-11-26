package cn.song.mybatisplus.visualizer.ui.completion;

import cn.song.mybatisplus.visualizer.model.WrapperType;
import cn.song.mybatisplus.visualizer.psi.WrapperLocator;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Wrapper 链状态检测器（F-5，阶段 A2）。
 *
 * <p>在补全触发时 O(1) 文本判定当前位置的链状态——<b>无 resolve</b>，符合 M10 性能红线。
 * 复用 {@link WrapperLocator} 的文本匹配模式（类名集合 + 工厂方法名集合 + 服务链方法名集合）。
 *
 * <p>检测逻辑：
 * <ol>
 *   <li>从锚点向上找最近的方法调用（含锚点自身）；</li>
 *   <li>沿 {@code methodExpression.qualifierExpression} 回溯到链首；</li>
 *   <li>链首判定（new 构造 / 工厂调用 / 服务链）→ WrapperType；</li>
 *   <li>收集链首到当前位置之间的已调用方法名。</li>
 * </ol>
 *
 * 非 Wrapper 链返回 null（不触发补全，不干扰正常代码）。
 */
public final class ChainStateDetector {

    /** MPJ 工厂类名（JoinWrappers / MPJWrappers）——query/update 映射与 Wrappers 不同。 */
    private static final Set<String> MPJ_FACTORY_CLASS_NAMES = Set.of("JoinWrappers", "MPJWrappers");

    private ChainStateDetector() {
    }

    /**
     * 链状态检测结果。
     *
     * @param wrapperType        Wrapper 类型
     * @param calledMethods      当前位置之前已调用的方法名（按源码顺序，不含当前正在输入的方法）
     * @param lastMethod         最近一个已调用的方法名（{@code calledMethods} 末项，便捷访问）；链首时为 null
     * @param isInsideMethodArgs 锚点是否在方法实参列表内（{@code .eq(|)} 的 {@code |} 位置）
     * @param currentMethodName  若在实参内，当前所在方法的名称（如 "eq"）；否则为 null
     */
    public static final class ChainState {
    private final @NotNull WrapperType wrapperType;
    private final @NotNull List<String> calledMethods;
    private final @Nullable String lastMethod;
    private final boolean insideMethodArgs;
    private final @Nullable String currentMethodName;

    public ChainState(@NotNull WrapperType wrapperType, @NotNull List<String> calledMethods, @Nullable String lastMethod, boolean isInsideMethodArgs, @Nullable String currentMethodName) {
        this.wrapperType = wrapperType;
        this.calledMethods = calledMethods;
        this.lastMethod = lastMethod;
        this.insideMethodArgs = isInsideMethodArgs;
        this.currentMethodName = currentMethodName;
    }

    public @NotNull WrapperType getWrapperType() { return wrapperType; }
    public @NotNull WrapperType wrapperType() { return wrapperType; }
    public @NotNull List<String> getCalledMethods() { return calledMethods; }
    public @NotNull List<String> calledMethods() { return calledMethods; }
    public @Nullable String getLastMethod() { return lastMethod; }
    public @Nullable String lastMethod() { return lastMethod; }
    public boolean isInsideMethodArgs() { return insideMethodArgs; }
    public @Nullable String getCurrentMethodName() { return currentMethodName; }
    public @Nullable String currentMethodName() { return currentMethodName; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChainState)) return false;
        ChainState that = (ChainState) o;
        return java.util.Objects.equals(this.wrapperType, that.wrapperType) && java.util.Objects.equals(this.calledMethods, that.calledMethods) && java.util.Objects.equals(this.lastMethod, that.lastMethod) && java.util.Objects.equals(this.insideMethodArgs, that.insideMethodArgs) && java.util.Objects.equals(this.currentMethodName, that.currentMethodName);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(wrapperType, calledMethods, lastMethod, insideMethodArgs, currentMethodName);
    }

    @Override
    public String toString() {
        return "ChainState[" + "wrapperType=" + wrapperType + ", " + "calledMethods=" + calledMethods + ", " + "lastMethod=" + lastMethod + ", " + "isInsideMethodArgs=" + insideMethodArgs + ", " + "currentMethodName=" + currentMethodName + "]";
    }
}

    /**
     * 检测当前位置的链状态。
     *
     * @param position 补全位置 PSI 元素（{@code com.intellij.codeInsight.completion.CompletionParameters.position}）
     * @return 链状态；非 Wrapper 链或无法判定时返回 null
     */
    @Nullable
    public static ChainState detect(@NotNull PsiElement position) {
        // 判定锚点是否在方法实参列表内（`.eq(|)` 的 `|` 位置）
        PsiExpressionList expressionList = PsiTreeUtil.getParentOfType(position, PsiExpressionList.class, false);
        boolean isInsideArgs = expressionList != null;

        // 从锚点向上找最近的方法调用（含锚点自身）
        PsiMethodCallExpression currentCall;
        if (expressionList != null) {
            currentCall = (expressionList.getParent() instanceof PsiMethodCallExpression)
                    ? (PsiMethodCallExpression) expressionList.getParent() : null;
        } else {
            currentCall = firstMethodCallAncestor(position);
        }
        if (currentCall == null) {
            return null;
        }

        // 回溯到链首（沿 qualifier 链走到头）
        PsiMethodCallExpression chainHead = lastMethodCallInQualifierChain(currentCall);

        // 链首判定 → WrapperType（纯文本匹配，无 resolve）
        WrapperType wrapperType = detectChainHeadType(chainHead);
        if (wrapperType == null) {
            return null;
        }

        // 收集已调用方法
        List<String> calledMethods = collectCalledMethods(chainHead, currentCall);

        // 当前方法名（实参内或正在输入方法名时取）
        String currentMethodName = currentCall.getMethodExpression().getReferenceName();

        // 最近已调用方法：calledMethods 末项
        String lastMethod = calledMethods.isEmpty() ? null : calledMethods.get(calledMethods.size() - 1);

        return new ChainState(wrapperType, calledMethods, lastMethod, isInsideArgs, currentMethodName);
    }

    // ------------------------------------------------------------------
    // 链首类型判定（纯文本匹配，O(1)，无 resolve）
    // ------------------------------------------------------------------

    /**
     * 链首 Wrapper 类型判定（F-5，复用 {@link WrapperLocator} 文本模式）：
     * <ul>
     *   <li>new 构造链首 → 按构造类型类名匹配</li>
     *   <li>工厂/服务链首 → 按 (qualifier 类名, 方法名) 匹配</li>
     * </ul>
     * 非 Wrapper 链首返回 null。
     */
    @Nullable
    private static WrapperType detectChainHeadType(@NotNull PsiMethodCallExpression chainHead) {
        PsiExpression qualifier = chainHead.getMethodExpression().getQualifierExpression();

        // new 构造链首：new LambdaQueryWrapper<X>()
        if (qualifier instanceof PsiNewExpression) {
            PsiType type = ((PsiNewExpression) qualifier).getType();
            // D256 M1：与预览链路同源（自研 Wrapper 子类走父链/规则 4 识别）
            return WrapperLocator.detectWrapperTypeDeep(type);
        }

        // 工厂/服务链首：Wrappers.<X>lambdaQuery() / service.lambdaQuery()
        String methodName = chainHead.getMethodExpression().getReferenceName();
        if (methodName == null) {
            return null;
        }
        String qualifierName = (qualifier instanceof PsiReferenceExpression)
                ? ((PsiReferenceExpression) qualifier).getReferenceName() : null;
        return detectFactoryChainHeadType(qualifierName, methodName);
    }

    /**
     * 工厂/服务链首类型判定（复用 {@link WrapperLocator#getWrapperFactoryClassNames()} /
     * {@link WrapperLocator#getServiceChainMethodNames()} 文本集合）：
     * <ul>
     *   <li>qualifier 是工厂类（Wrappers/ChainWrappers 等）→ 按 (qualifier, method) 映射</li>
     *   <li>qualifier 非工厂类（service 等）→ 按服务链方法名映射</li>
     * </ul>
     * 纯文本匹配，无 resolve。
     */
    @Nullable
    private static WrapperType detectFactoryChainHeadType(@Nullable String qualifierName, @NotNull String methodName) {
        if (qualifierName != null && WrapperLocator.WRAPPER_FACTORY_CLASS_NAMES.contains(qualifierName)) {
            return mapWrapperFactoryMethod(methodName, qualifierName);
        }
        // 服务链（service.lambdaQuery() 等）按方法名映射
        if (!WrapperLocator.SERVICE_CHAIN_METHOD_NAMES.contains(methodName)) {
            return null;
        }
        return mapServiceChainMethod(methodName);
    }

    @Nullable
    private static WrapperType mapWrapperFactoryMethod(@NotNull String methodName, @NotNull String qualifierName) {
        switch (methodName) {
            case "lambdaQuery":
                return WrapperType.LAMBDA_QUERY;
            case "lambdaUpdate":
                return WrapperType.LAMBDA_UPDATE;
            case "lambdaDelete":
                return WrapperType.LAMBDA_DELETE;
            case "query":
                return MPJ_FACTORY_CLASS_NAMES.contains(qualifierName) ? WrapperType.MPJ_QUERY : WrapperType.QUERY;
            case "update":
                return MPJ_FACTORY_CLASS_NAMES.contains(qualifierName) ? WrapperType.UPDATE_JOIN : WrapperType.UPDATE;
            case "queryChain":
                return WrapperType.QUERY;
            case "lambdaQueryChain":
                return WrapperType.LAMBDA_QUERY;
            case "lambdaUpdateChain":
                return WrapperType.LAMBDA_UPDATE;
            case "lambdaDeleteChain":
                return WrapperType.LAMBDA_DELETE;
            case "updateChain":
                return WrapperType.UPDATE;
            case "deleteChain":
                return WrapperType.LAMBDA_DELETE;
            case "lambda":
            case "lambdaJoin":
                return MPJ_FACTORY_CLASS_NAMES.contains(qualifierName) ? WrapperType.MPJ_LAMBDA : null;
            case "queryJoin":
                return MPJ_FACTORY_CLASS_NAMES.contains(qualifierName) ? WrapperType.MPJ_QUERY : null;
            case "delete":
                return MPJ_FACTORY_CLASS_NAMES.contains(qualifierName) ? WrapperType.DELETE_JOIN : null;
            default:
                return null;
        }
    }

    @Nullable
    private static WrapperType mapServiceChainMethod(@NotNull String methodName) {
        switch (methodName) {
            case "lambdaQuery":
                return WrapperType.LAMBDA_QUERY;
            case "lambdaUpdate":
                return WrapperType.LAMBDA_UPDATE;
            case "query":
                return WrapperType.QUERY;
            case "update":
                return WrapperType.UPDATE;
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------
    // 已调用方法收集
    // ------------------------------------------------------------------

    /**
     * 收集链首到当前调用之间的已调用方法名（按源码顺序，不含 currentCall 自身）。
     */
    @NotNull
    private static List<String> collectCalledMethods(
            @NotNull PsiMethodCallExpression chainHead,
            @NotNull PsiMethodCallExpression currentCall) {
        List<String> methods = new ArrayList<>();
        // 从 currentCall.qualifier 回溯到 chainHead（不含 currentCall 自身）
        PsiMethodCallExpression cursor = asMethodCall(currentCall.getMethodExpression().getQualifierExpression());
        while (cursor != null && cursor != chainHead) {
            String name = cursor.getMethodExpression().getReferenceName();
            if (name != null) {
                methods.add(name);
            }
            cursor = asMethodCall(cursor.getMethodExpression().getQualifierExpression());
        }
        // chainHead 是 Wrapper 操作（qualifier 是 new 构造）时计入已调用；
        // chainHead 是工厂调用（Wrappers.lambdaQuery()）时不计入（非 Wrapper 操作）。
        if (cursor == chainHead
                && chainHead.getMethodExpression().getQualifierExpression() instanceof PsiNewExpression) {
            String name = chainHead.getMethodExpression().getReferenceName();
            if (name != null) {
                methods.add(name);
            }
        }
        // 回溯收集是逆序的（从近到远），反转恢复源码顺序
        Collections.reverse(methods);
        return methods;
    }

    @Nullable
    private static PsiMethodCallExpression asMethodCall(@Nullable PsiExpression expr) {
        return (expr instanceof PsiMethodCallExpression) ? (PsiMethodCallExpression) expr : null;
    }

    @Nullable
    private static PsiMethodCallExpression firstMethodCallAncestor(@NotNull PsiElement position) {
        PsiElement cur = position;
        while (cur != null) {
            if (cur instanceof PsiMethodCallExpression) {
                return (PsiMethodCallExpression) cur;
            }
            cur = cur.getParent();
        }
        return null;
    }

    @NotNull
    private static PsiMethodCallExpression lastMethodCallInQualifierChain(@NotNull PsiMethodCallExpression start) {
        PsiMethodCallExpression cur = start;
        PsiMethodCallExpression last = start;
        while (true) {
            PsiMethodCallExpression next = asMethodCall(cur.getMethodExpression().getQualifierExpression());
            if (next == null) {
                break;
            }
            last = next;
            cur = next;
        }
        return last;
    }
}
