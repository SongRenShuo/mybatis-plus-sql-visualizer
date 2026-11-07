package cn.song.mybatisplus.visualizer.psi;

import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.LimitClause;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.WrapperCall;
import cn.song.mybatisplus.visualizer.model.WrapperChain;
import cn.song.mybatisplus.visualizer.model.WrapperType;
import cn.song.mybatisplus.visualizer.util.NamingStrategy;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassObjectAccessExpression;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiIfStatement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiMethodReferenceExpression;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiParameterList;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import kotlin.Pair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Wrapper 定位器（M1-02，F-01 入口）：从光标所在元素定位 Wrapper 调用链。
 *
 * <p>支持三种写法（对照 docs/项目介绍.md）：</p>
 * <ol>
 *  <li>链式：{@code new LambdaQueryWrapper<Entity>().eq(...).like(...)}</li>
 *  <li>分步：{@code LambdaQueryWrapper<Entity> wrapper = new LambdaQueryWrapper<>(); wrapper.eq(...)}</li>
 *  <li>混合：初始化链 + 后续分步调用</li>
 * </ol>
 *
 * <p>当前实现聚焦 Java PSI（项目示例为 Java 代码）；Kotlin 文件支持列入后续里程碑。</p>
 */
public final class WrapperLocator {

    private WrapperLocator() {}

    // ------------------------------------------------------------------
    // 定位入口
    // ------------------------------------------------------------------

    /**
     * 从锚点元素定位并解析 Wrapper 调用链。
     *
     * @param anchor 光标所在元素（编辑器 PSI 元素）
     * @return 调用链模型；未找到 Wrapper 或类型不支持时返回 null
     */
    public static WrapperChain locate(PsiElement anchor) {
        LocatedWrapper located = locateWithTable(anchor);
        return located != null ? located.getChain() : null;
    }

    /**
     * 定位并同时返回实体表信息（供 converter 全链路使用）。
     *
     * <p>支持三类 Wrapper 来源：</p>
     * <ul>
     *  <li>变量声明（{@code LambdaQueryWrapper<X> w = ...}，含分步续链）</li>
     *  <li>内联 new 表达式（{@code list(new MPJLambdaQueryWrapper<X>().select(...).eq(...))}，无变量）</li>
     *  <li>Wrappers 静态工厂 / 服务链（{@code Wrappers.<X>lambdaQuery()...} / {@code service.lambdaQuery()...}）</li>
     * </ul>
     * T-M23.7：末尾追加 {@link #locateFromChainCall} 兜底——链中/链尾（eq/list 调用处）任意位置定位。
     */
    public static LocatedWrapper locateWithTable(PsiElement anchor, boolean resolveVariables) {
        // Kotlin 文本检测不产出可生成 SQL 的链（无可靠表名与列/值实参），直接短路
        String fileExtension = null;
        if (anchor.getContainingFile() != null && anchor.getContainingFile().getVirtualFile() != null) {
            fileExtension = anchor.getContainingFile().getVirtualFile().getExtension();
        }
        if ("kt".equals(fileExtension) || "kts".equals(fileExtension)) return null;

        PsiVariable variable = findWrapperVariable(anchor);
        if (variable != null) return buildChain(variable, resolveVariables);
        PsiNewExpression newExpr = findWrapperNewExpression(anchor);
        if (newExpr != null) return buildChainFromNew(newExpr, resolveVariables);
        PsiMethodCallExpression factoryCall = findWrapperFactoryCall(anchor);
        if (factoryCall != null) return buildChainFromFactory(factoryCall, resolveVariables);
        return locateFromChainCall(anchor, resolveVariables);
    }

    /** 1 参便利重载（Kotlin 默认参数 {@code resolveVariables = true}）。 */
    public static LocatedWrapper locateWithTable(PsiElement anchor) {
        return locateWithTable(anchor, true);
    }

    // ------------------------------------------------------------------
    // T-M23.7：链中/链尾任意位置定位（eq/list 调用处）与选择区扫描
    // ------------------------------------------------------------------

    /**
     * 从<b>任意相关位置</b>定位 wrapper 链（用户需求：光标在 {@code eq} 调用处、{@code .list()} 调用处等
     * 链中/链尾也可触发）：
     * <ol>
     *  <li>沿 parent 链找<b>最近的方法调用</b>（含锚点自身）；</li>
     *  <li>沿 {@code methodExpression.qualifierExpression} <b>回溯到链首</b>（与工厂链收集同构）；</li>
     *  <li>链首判定（{@link #isChainHeadCall}）：new wrapper 构造 → {@link #buildChainFromNew}；
     *      Wrappers 工厂/服务链 → {@link #buildChainFromFactory}；</li>
     *  <li>非 wrapper 链首（普通方法调用，如 {@code helper.applyFilter(w)}）→ null（防误伤）。</li>
     * </ol>
     *
     * <p>仅用户显式触发（快捷键/右键）时调用，无 M10 daemon 性能红线。</p>
     */
    private static LocatedWrapper locateFromChainCall(PsiElement anchor, boolean resolveVariables) {
        PsiMethodCallExpression call = null;
        for (PsiElement cur = anchor; cur != null; cur = cur.getParent()) {
            if (cur instanceof PsiMethodCallExpression) {
                call = (PsiMethodCallExpression) cur;
                break;
            }
        }
        if (call == null) return null;
        // 回溯到链首（qualifier 不再是方法调用为止）；至少含 call 自身，兜底自身
        PsiMethodCallExpression chainHead = call;
        while (true) {
            PsiExpression qualifier = chainHead.getMethodExpression().getQualifierExpression();
            if (qualifier instanceof PsiMethodCallExpression) {
                chainHead = (PsiMethodCallExpression) qualifier;
            } else {
                break;
            }
        }
        PsiExpression headQualifier = chainHead.getMethodExpression().getQualifierExpression();
        if (headQualifier instanceof PsiNewExpression) {
            PsiNewExpression newExpr = (PsiNewExpression) headQualifier;
            if (detectWrapperType(newExpr.getType()) != null) {
                return buildChainFromNew(newExpr, resolveVariables);
            }
            return null;
        }
        if (isWrapperFactoryCall(chainHead)) return buildChainFromFactory(chainHead, resolveVariables);
        return null;
    }

    /**
     * T-M23.7：调用是否为 wrapper <b>链首</b>（new wrapper 构造链首 / 工厂·服务链首）。
     * 供选择区扫描（{@link #isSqlCandidate}）与 ConvertToSqlAction 复用（原 Kotlin internal）。
     */
    public static boolean isChainHeadCall(PsiMethodCallExpression call) {
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        return (qualifier instanceof PsiNewExpression && detectWrapperType(qualifier.getType()) != null)
            || isWrapperFactoryCall(call);
    }

    /** T-M23.7：链首 wrapper 类型（new 构造 → 构造类型；工厂/服务链 → 工厂类型）；非链首 null。 */
    private static WrapperType wrapperTypeOfChainHead(PsiMethodCallExpression call) {
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (qualifier instanceof PsiNewExpression) {
            return detectWrapperType(qualifier.getType());
        }
        if (isWrapperFactoryCall(call)) return factoryWrapperType(call);
        return null;
    }

    /**
     * T-M23.7：调用是否为 SQL 候选——<b>wrapper 链首</b> 或 <b>批量操作方法调用</b>
     * （{@code getById}/{@code save}/{@code removeById}/{@code selectAll} 等，注册表
     * {@link BatchOperationConverter} 批量方法名；用户实测：选择区含 {@code bannerService.getById(id)}
     * 时也应进多 SQL 选择列表）。供选择区扫描与 ConvertToSqlAction 复用（原 Kotlin internal）。
     * T-WriteFamily（R4）：写族新增的 {@code delete}/{@code update}/{@code remove} 措辞与链工厂/终结点撞名，
     * 须叠加「空 Wrapper 精确形态 + MP receiver」门（{@link BatchOperationConverter#isBatchOperationCall}），
     * 否则 {@code redisTemplate.delete(null)} / {@code list.remove(0)} 会进候选列表。
     */
    public static boolean isSqlCandidate(PsiMethodCallExpression call) {
        return isChainHeadCall(call) || BatchOperationConverter.isBatchOperationCall(call);
    }

    /**
     * T-M23.7：扫描选择区（offset 范围）内全部 SQL 候选调用（wrapper 链首 + 批量操作方法，
     * 按 offset 去重排序）——同一链的 eq/list 多处只计 1 个；批量方法（getById 等）单调用各计 1；
     * 嵌套子查询链首在实参子树内为独立链首（合理行为，文档化）。
     *
     * @param file  目标文件
     * @param start 选择区起始 offset（含）
     * @param end   选择区结束 offset（不含）
     */
    public static List<PsiMethodCallExpression> sqlCandidatesInRange(PsiFile file, int start, int end) {
        LinkedHashSet<Integer> seen = new LinkedHashSet<>();
        List<PsiMethodCallExpression> result = new ArrayList<>();
        List<PsiMethodCallExpression> calls = new ArrayList<>(
            PsiTreeUtil.collectElementsOfType(file, PsiMethodCallExpression.class));
        calls.sort(Comparator.comparingInt(c -> c.getTextRange().getStartOffset()));
        for (PsiMethodCallExpression call : calls) {
            int offset = call.getTextRange().getStartOffset();
            if (offset >= start && offset < end && isSqlCandidate(call) && seen.add(offset)) {
                result.add(call);
            }
        }
        return result;
    }

    /**
     * T-M23.7：多 SQL 选择列表项摘要：{@code L{行号} {Wrapper 类型} {表名}}（wrapper 链首）/
     * {@code L{行号} {方法名}}（批量方法调用，如 {@code L18 getById}）。行号按文档行（1-based）。
     */
    public static String sqlCandidateSummary(PsiMethodCallExpression call) {
        int line = 0;
        try {
            var doc = PsiDocumentManager.getInstance(call.getProject()).getDocument(call.getContainingFile());
            if (doc != null) line = doc.getLineNumber(call.getTextRange().getStartOffset()) + 1;
        } catch (Throwable ignored) {
            // 兜底 0
        }
        if (isChainHeadCall(call)) {
            WrapperType type = wrapperTypeOfChainHead(call);
            String typeName = type != null ? type.name() : "?";
            LocatedWrapper located = locateWithTable(call);
            String tableName = located != null && located.getTable() != null ? located.getTable().getTableName() : null;
            return "L" + line + " " + typeName + (tableName != null ? "  " + tableName : "");
        }
        String refName = call.getMethodExpression().getReferenceName();
        return "L" + line + " " + (refName != null ? refName : "?");
    }

    /**
     * T-M23.8：候选调用所在<b>代码行完整文本</b>（trim；选择弹窗第二行预览）。
     * 文档不可得时返回空串（兜底，不抛异常）。
     */
    public static String sqlCandidateLineText(PsiMethodCallExpression call) {
        var doc = PsiDocumentManager.getInstance(call.getProject()).getDocument(call.getContainingFile());
        if (doc == null) return "";
        int line = doc.getLineNumber(call.getTextRange().getStartOffset());
        return doc.getText(new TextRange(doc.getLineStartOffset(line), doc.getLineEndOffset(line))).trim();
    }

    /**
     * T-M23.8：候选元素所在行的 {@code (行首, 行尾)} offset（编辑器定位跳转用——
     * 光标到行首 + 整行选中；T-M29.6 泛化支持任意 PSI 元素：Wrapper 链首/XML 语句/注解）。
     * 文档不可得时回退元素自身范围（兜底）。
     */
    public static Pair<Integer, Integer> lineOffsetsOf(PsiElement element) {
        var doc = PsiDocumentManager.getInstance(element.getProject()).getDocument(element.getContainingFile());
        if (doc == null) {
            return new Pair<>(element.getTextRange().getStartOffset(), element.getTextRange().getEndOffset());
        }
        int line = doc.getLineNumber(element.getTextRange().getStartOffset());
        return new Pair<>(doc.getLineStartOffset(line), doc.getLineEndOffset(line));
    }

    // ------------------------------------------------------------------
    // 定位 Wrapper 变量
    // ------------------------------------------------------------------

    /**
     * 从锚点向上查找 Wrapper 类型变量。
     *
     * <p>查找路径：</p>
     * <ul>
     *  <li>锚点本身/父链上的局部变量或字段（类型为 Wrapper）</li>
     *  <li>锚点在方法调用上时，其 qualifier 引用的变量</li>
     * </ul>
     */
    private static PsiVariable findWrapperVariable(PsiElement anchor) {
        PsiElement current = anchor;
        while (current != null) {
            if (current instanceof PsiLocalVariable) {
                if (isWrapperVariable((PsiVariable) current)) return (PsiVariable) current;
            } else if (current instanceof PsiField) {
                if (isWrapperVariable((PsiVariable) current)) return (PsiVariable) current;
            } else if (current instanceof PsiParameter) {
                if (isWrapperVariable((PsiVariable) current)) return (PsiVariable) current;
            } else if (current instanceof PsiReferenceExpression) {
                // 实参位置的 wrapper 引用（`update(null, wrapper)` / `selectCount(wrapper)`，U08，T-M15-05）：
                // 直接 resolve 引用到 wrapper 变量即命中（isWrapperVariable 类型检查兜底，不会误判普通变量）
                PsiVariable resolved = asPsiVariable(((PsiReferenceExpression) current).resolve());
                if (resolved != null && isWrapperVariable(resolved)) return resolved;
            } else if (current instanceof PsiMethodCallExpression) {
                PsiMethodCallExpression call = (PsiMethodCallExpression) current;
                // ① 直接 qualifier 引用：wrapper.eq(...) / wrapper.lambda()
                PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
                PsiVariable resolved = qualifier instanceof PsiReferenceExpression
                    ? asPsiVariable(((PsiReferenceExpression) qualifier).resolve()) : null;
                if (resolved != null && isWrapperVariable(resolved)) return resolved;
                // ② 链式中间节点：set(...) 的 qualifier 是 eq 调用——沿 qualifier 回溯到链首
                //    （`wrapper.lambda().eq(...).set(...)` 的 lambda()，U09-④），仅在当前表达式内，不会跨方法误命中
                PsiMethodCallExpression chainHead = call;
                while (true) {
                    PsiExpression q = chainHead.getMethodExpression().getQualifierExpression();
                    if (q instanceof PsiMethodCallExpression) {
                        chainHead = (PsiMethodCallExpression) q;
                    } else {
                        break;
                    }
                }
                PsiExpression headQualifier = chainHead.getMethodExpression().getQualifierExpression();
                PsiVariable headResolved = headQualifier instanceof PsiReferenceExpression
                    ? asPsiVariable(((PsiReferenceExpression) headQualifier).resolve()) : null;
                if (headResolved != null && isWrapperVariable(headResolved)) return headResolved;
                // ③ 实参位置的 wrapper（`update(null, wrapper)` / `selectCount(wrapper)`，U08，T-M15-05）：
                //    只查<b>直接实参</b>的引用（不递归嵌套调用，避免跨方法误命中；内联工厂走 findWrapperFactoryCall）
                for (PsiExpression arg : call.getArgumentList().getExpressions()) {
                    if (arg instanceof PsiReferenceExpression) {
                        PsiVariable argResolved = asPsiVariable(((PsiReferenceExpression) arg).resolve());
                        if (argResolved != null && isWrapperVariable(argResolved)) return argResolved;
                    }
                }
            }
            current = current.getParent();
        }
        return null;
    }

    private static PsiVariable asPsiVariable(com.intellij.psi.PsiElement element) {
        return element instanceof PsiVariable ? (PsiVariable) element : null;
    }

    /**
     * 变量是否为 Wrapper（含基类型降级）：类型直接是具体 Wrapper，或类型为抽象基类
     * （{@code Wrapper<X>} / {@code AbstractWrapper<X>}）但初始化表达式链首是具体 Wrapper 构造 / Wrappers 工厂。
     *
     * <p>降级必须限定基类型（T-M15-01 回归）：{@code long total = service.lambdaQuery()...count()} 的 total
     * 类型不是 Wrapper 基类，若仅按 initializer 链首判断会被误判为 wrapper 变量（链被截断、table 为 null）。</p>
     *
     * <p>O-8（缺陷 A）：{@code var} 声明（LightPlatform 下类型推断不可靠，常落 Object/未知类型）——
     * initializer 链头为 wrapper 构造/工厂 <b>且链上无终结消费方法</b> 时接受为 wrapper 变量，
     * 使 var 形态走变量路径（ReferencesSearch 消费检测天然生效：{@code service.remove(w)} 等不再整体丢失）。
     * 终结点黑名单守卫（{@link #initializerIsWrapperChainWithoutTerminal}，单一来源 {@link #CHAIN_TERMINAL_METHOD_NAMES}）
     * 保住 T-M15-01/T-M19.5 反向语义：{@code var d = service.lambdaQuery()...one()} 的结果变量仍被拒绝。</p>
     *
     * <p>M19.5（T-M19.5-01a）起为 internal（Java public static）：供 {@link WrapperAnchorDetector} 复用
     * （结果变量赋值形态不应抑制服务链锚点，见 20260813-1536 开发计划）。判定为 O(1) 纯语法
     * （文本匹配 + initializer 链首回溯，无 resolve），符合 M10 性能红线。</p>
     */
    public static boolean isWrapperVariable(PsiVariable variable) {
        if (detectWrapperType(variable.getType()) != null) return true;
        // var 声明的语法判定走 typeElement 文本（不依赖 getType() 推断成败，O(1) 纯文本）；
        // var 仅限局部变量，字段/形参不可能命中
        if (variable instanceof PsiLocalVariable) {
            PsiElement typeElement = ((PsiLocalVariable) variable).getTypeElement();
            if (typeElement != null && "var".equals(typeElement.getText())) {
                return initializerIsWrapperChainWithoutTerminal(variable.getInitializer());
            }
        }
        String typeName = variable.getType() instanceof PsiClassType ? ((PsiClassType) variable.getType()).getClassName() : null;
        if (typeName == null || !WRAPPER_BASE_TYPE_NAMES.contains(typeName)) return false;
        return detectWrapperTypeFromInitializer(variable.getInitializer()) != null;
    }

    /**
     * O-8（缺陷 A）：从 initializer 链头推断 Wrapper 类型（var 推断形态专用，仅 {@link #buildChain} 的
     * var 分支调用，不改变非 var 声明的既有判定）。
     * 与 {@link #detectWrapperTypeFromInitializer} 的差异：<b>工厂调用自身即链首</b>——
     * {@code Wrappers.<Device>query().eq(...)} 沿 qualifier 回溯的终点是 Wrappers 引用（非调用/new），
     * 原实现落 {@code else -> null} 恒不识别工厂链头；本实现取链上<b>最后一次调用</b>为链首候选
     * （isWrapperFactoryCall 门控，与 {@link #findWrapperFactoryCall} 同一判定），new 构造链头同 {@link #detectWrapperTypeFromInitializer}。
     */
    private static WrapperType wrapperTypeFromInitializerChain(PsiElement init) {
        if (init == null) return null;
        PsiElement expr = init;
        PsiMethodCallExpression lastCall = null;
        while (expr instanceof PsiMethodCallExpression) {
            lastCall = (PsiMethodCallExpression) expr;
            expr = lastCall.getMethodExpression().getQualifierExpression();
        }
        if (expr instanceof PsiNewExpression) {
            return detectWrapperType(((PsiNewExpression) expr).getType());
        }
        if (lastCall != null && isWrapperFactoryCall(lastCall)) {
            return factoryWrapperType(lastCall);
        }
        return null;
    }

    /**
     * initializer 是否为「无终结消费方法」的 wrapper 构造/工厂链（O-8 缺陷 A 接受门）：
     * 链头经 {@link #wrapperTypeFromInitializerChain} 判为 wrapper，且链上任一调用都不在
     * {@link #CHAIN_TERMINAL_METHOD_NAMES}（one/list/count/remove 等消费终结点）——
     * 链含终结点即为消费结果赋值（{@code var d = service.lambdaQuery()...one()}），不是 wrapper 变量。
     */
    private static boolean initializerIsWrapperChainWithoutTerminal(PsiElement init) {
        if (init == null) return false;
        PsiElement expr = init;
        while (expr instanceof PsiMethodCallExpression) {
            PsiMethodCallExpression call = (PsiMethodCallExpression) expr;
            String referenceName = call.getMethodExpression().getReferenceName();
            // T-ExistsChainTerminal：带参 exists 是条件方法（非终结点），不得据此拒收 wrapper 变量
            if (referenceName != null && CHAIN_TERMINAL_METHOD_NAMES.contains(referenceName)
                    && !isExistsConditionCall(call)) return false;
            expr = call.getMethodExpression().getQualifierExpression();
        }
        return wrapperTypeFromInitializerChain(init) != null;
    }

    /** Wrapper 抽象基类型（基类型声明降级仅限这两类，见 {@link #isWrapperVariable}）。 */
    private static final Set<String> WRAPPER_BASE_TYPE_NAMES = Set.of("Wrapper", "AbstractWrapper");

    /**
     * 从初始化表达式链首推断 Wrapper 类型（基类型声明降级用）：
     * 沿 {@code methodExpression.qualifierExpression} 回溯到链起点，返回其 wrapper 类型。
     */
    private static WrapperType detectWrapperTypeFromInitializer(PsiElement init) {
        if (init == null) return null;
        PsiElement expr = init;
        while (expr instanceof PsiMethodCallExpression) {
            expr = ((PsiMethodCallExpression) expr).getMethodExpression().getQualifierExpression();
        }
        if (expr instanceof PsiNewExpression) {
            return detectWrapperType(((PsiNewExpression) expr).getType());
        }
        if (expr instanceof PsiMethodCallExpression) {
            return factoryWrapperType((PsiMethodCallExpression) expr);
        }
        return null;
    }

    /**
     * 表达式链首的最近消费调用：向上找到第一个实参列表（{@code PsiExpressionList}）的父方法调用。
     * 用于判断 wrapper 作为实参传给了哪个方法（{@code service.remove(new W()...)} 取 remove 而非更外层）。
     */
    private static PsiMethodCallExpression consumingCall(PsiElement element) {
        PsiElement cursor = element.getParent();
        while (cursor != null) {
            if (cursor instanceof PsiExpressionList) {
                return cursor.getParent() instanceof PsiMethodCallExpression
                    ? (PsiMethodCallExpression) cursor.getParent() : null;
            }
            if (cursor instanceof PsiStatement || cursor instanceof PsiMethod || cursor instanceof PsiClass) {
                return null;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    /**
     * 从锚点查找内联 Wrapper 构造表达式（{@code list(new MPJLambdaQueryWrapper<X>().eq(...))}，清单 P17）。
     *
     * <p>定位策略（防跨方法误命中）：</p>
     * <ul>
     *  <li>向下扫锚点<b>子树</b>只在锚点初始位置执行一次（锚点可能是包裹调用，如 {@code list(new W()...)}）；</li>
     *  <li>沿祖先链只检查元素<b>自身</b>是否为 wrapper new 表达式——绝不能每层都向下扫，
     *      否则走到 PsiClass 级别会命中类内其他方法的 wrapper（回归：删除/修改被串成查询，2026-08-12）。</li>
     * </ul>
     */
    private static PsiNewExpression findWrapperNewExpression(PsiElement anchor) {
        // T-M29-03：扫锚点子树全部 new 表达式取<b>首个 wrapper 构造</b>（原 findChildOfType 只取第一个——
        // `selectPage(new Page<>(...), new LambdaQueryWrapper<>()...)` 的 Page 先于 wrapper 时漏检）
        for (PsiNewExpression newExpr : PsiTreeUtil.collectElementsOfType(anchor, PsiNewExpression.class)) {
            if (detectWrapperType(newExpr.getType()) != null) return newExpr;
        }
        PsiElement current = anchor;
        while (current != null) {
            if (current instanceof PsiNewExpression && detectWrapperType(((PsiNewExpression) current).getType()) != null) {
                return (PsiNewExpression) current;
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * 从内联 new 表达式构建调用链（无变量声明场景）。
     *
     * <p>收集以该 new 表达式为链起点的所有链式调用（{@code new W().select(...).eq(...)}），
     * 按源码结束位置排序。</p>
     */
    private static LocatedWrapper buildChainFromNew(PsiNewExpression newExpr, boolean resolveVariables) {
        WrapperType wrapperType = detectWrapperType(newExpr.getType());
        if (wrapperType == null) return null;
        // T-M20-03：`new MPJLambdaWrapper<>(userEntity)` 菱形 + 实体变量实参（无泛型实参，
        // entityInfoFromType 解析不到实体）时，从构造第一参表达式类型解析实体；
        // (Class) / (Class, alias) 构造（第一参 PsiClassObjectAccessExpression）不受影响
        EntityInfo info = entityInfoFromType(newExpr.getType());
        PsiClass entityFromArg = info.entityClass == null ? entityClassFromConstructorArg(newExpr) : null;
        // 实参解析出的实体（T-M20-03）与类型实参解析结果一致地参与 table/chain 全链路
        PsiClass resolvedEntity = info.entityClass != null ? info.entityClass : entityFromArg;
        String typeArgName = info.typeArgName != null ? info.typeArgName
            : (resolvedEntity != null ? resolvedEntity.getName() : null);
        // T-TS-07：兜底推断显式传本 new 表达式所属项目（多项目同开时不再受「首个打开项目」影响）
        TableInfo table = resolveTableInfo(resolvedEntity, typeArgName, newExpr.getProject());
        String constructorAlias = extractConstructorAlias(newExpr);

        // 宿主 = 包含该 new 表达式的最外层表达式（如 list(new W().select(...).eq(...)) 的整个调用）
        PsiElement host = newExpr;
        for (PsiElement cursor = newExpr.getParent(); cursor != null; cursor = cursor.getParent()) {
            if (cursor instanceof PsiExpression) host = cursor;
        }
        List<PsiMethodCallExpression> chainCalls = new ArrayList<>();
        List<PsiMethodCallExpression> hostCalls = new ArrayList<>(
            PsiTreeUtil.collectElementsOfType(host, PsiMethodCallExpression.class));
        // 去重 + 按源码结束位置排序
        LinkedHashSet<Integer> seenEnds = new LinkedHashSet<>();
        for (PsiMethodCallExpression call : hostCalls) {
            if (PsiTreeUtil.getParentOfType(call, PsiLambdaExpression.class) != null) continue;
            // 链首的 qualifier 是 newExpr（即以该 new 为起点的 wrapper 链）
            List<PsiMethodCallExpression> chain = ArgResolver.collectCallChain(call);
            if (chain.isEmpty()) continue;
            if (chain.get(0).getMethodExpression().getQualifierExpression() != newExpr) continue;
            if (!seenEnds.add(call.getTextRange().getEndOffset())) continue;
            chainCalls.add(call);
        }
        chainCalls.sort(Comparator.comparingInt(c -> c.getTextRange().getEndOffset()));
        List<WrapperCall> wrapperCalls = new ArrayList<>();
        for (PsiMethodCallExpression call : chainCalls) {
            wrapperCalls.add(ArgResolver.toWrapperCall(call, table,
                resolvedEntity != null ? resolvedEntity.getName() : null, resolveVariables));
        }

        // 消费识别：wrapper 作为实参传给删除/计数/exists/实体更新方法（`service.remove(new W()...)` / `service.count(...)` / `service.exists(...)` / `service.update(entity, ...)`）
        PsiMethodCallExpression consuming = consumingCall(newExpr);
        boolean deleteConsumed = consuming != null
            && DELETE_CONSUMER_METHOD_NAMES.contains(consuming.getMethodExpression().getReferenceName());
        boolean countConsumed = consuming != null
            && COUNT_CONSUMER_METHOD_NAMES.contains(consuming.getMethodExpression().getReferenceName());
        boolean existsConsumed = consuming != null
            && EXISTS_CONSUMER.equals(consuming.getMethodExpression().getReferenceName());
        boolean updateEntityConsumed = isEntityUpdateConsumer(newExpr);
        // T-M29-02/03：分页消费（`service.page(page, new W()...)`）——Page 实参解析为 LIMIT 子句
        // R12 T-PaginationSim：连同 searchCount 标志一并解析（D3=A）
        PageArg pageArg = consuming != null
            && PAGE_CONSUMER_METHOD_NAMES.contains(consuming.getMethodExpression().getReferenceName())
            ? parsePageArg(consuming) : null;

        WrapperChain chain = new WrapperChain(wrapperType, resolvedEntity != null ? resolvedEntity.getName() : null,
            wrapperCalls, constructorAlias, deleteConsumed, countConsumed, existsConsumed,
            updateEntityConsumed, pageArg != null ? pageArg.limit : null,
            pageArg != null ? pageArg.searchCount : true);
        return new LocatedWrapper(chain, table, resolvedEntity != null ? resolvedEntity.getContainingFile() : null);
    }

    // ------------------------------------------------------------------
    // Wrappers 静态工厂链（getOne(Wrappers.<T>lambdaQuery().select(...).eq(...)) 等）
    // ------------------------------------------------------------------

    /**
     * 从锚点查找内联 Wrapper 工厂调用（{@code getOne(Wrappers.<T>lambdaQuery().select(...).eq(...))}）。
     *
     * <p>{@code Wrappers.<T>lambdaQuery()} 是静态工厂调用，不产生 {@code new} 表达式、也不一定绑定 Wrapper 类型变量。
     * 定位策略同 {@link #findWrapperNewExpression}：向下扫子树只在锚点初始位置一次，向上只检查元素自身，
     * 避免走到类级别时误命中其他方法的工厂调用。</p>
     */
    private static PsiMethodCallExpression findWrapperFactoryCall(PsiElement anchor) {
        for (PsiMethodCallExpression call : PsiTreeUtil.collectElementsOfType(anchor, PsiMethodCallExpression.class)) {
            if (isWrapperFactoryCall(call)) return call;
        }
        PsiElement current = anchor;
        while (current != null) {
            if (current instanceof PsiMethodCallExpression && isWrapperFactoryCall((PsiMethodCallExpression) current)) {
                return (PsiMethodCallExpression) current;
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * 从内联工厂调用构建调用链（无变量声明场景）。
     *
     * <p>收集以该工厂调用为链起点的所有链式调用（{@code Wrappers.<T>lambdaQuery().select(...).eq(...)}、
     * {@code service.lambdaQuery()...one()} 服务链，T-M15-01），按源码结束位置排序；
     * 工厂调用自身（非 Wrapper 操作）不入链；链式终结点（one/list/count/update/remove 等）识别消费语义后剔除。</p>
     */
    private static LocatedWrapper buildChainFromFactory(PsiMethodCallExpression factoryCall, boolean resolveVariables) {
        WrapperType wrapperType = factoryWrapperType(factoryCall);
        if (wrapperType == null) return null;
        // 实体类优先取显式类型实参（`Wrappers.<MerchantEntity>lambdaQuery()`），失败时用类名按命名策略兜底；
        // 服务链（`service.lambdaQuery()`，无类型实参）回退到调用表达式返回类型（LambdaQueryChainWrapper<T>），
        // 仍失败时从链内首个「首参方法引用」推断（T-M15-01）；
        // O-8（缺陷 B）第三通道：服务链 qualifier 变量的服务类型泛型实参（`DeviceService extends IService<Device>`）
        PsiType[] typeArguments = factoryCall.getTypeArguments();
        PsiClassType typeArg = typeArguments.length > 0 && typeArguments[0] instanceof PsiClassType
            ? (PsiClassType) typeArguments[0] : null;
        // XB（M44）：xbatis 工厂实体**优先取 Class 字面量实参**（`QueryChain.of(mapper, User.class)`，
        // javap 实证签名）——stub/LightPlatform 下返回类型泛型常为未推断的类型形参 T，不可作实体
        PsiClass entityClass;
        if (wrapperType == WrapperType.XBATIS) {
            entityClass = entityClassFromFactoryArgs(factoryCall);
            if (entityClass == null && typeArg != null && typeArg.resolve() != null
                    && !(typeArg.resolve() instanceof com.intellij.psi.PsiTypeParameter)) {
                entityClass = typeArg.resolve();
            }
            if (entityClass == null) {
                entityClass = resolveEntityClass(factoryCall.getType());
            }
        } else {
            entityClass = typeArg != null ? typeArg.resolve() : null;
            if (entityClass == null) {
                entityClass = resolveEntityClass(factoryCall.getType());
                if (entityClass == null) {
                    entityClass = entityClassFromFactoryArgs(factoryCall);
                }
                if (entityClass == null) {
                    entityClass = entityClassFromServiceQualifier(factoryCall);
                }
            }
        }
        String typeArgName = typeArg != null ? typeArg.getClassName() : null;
        // XB（M44）：xbatis 链实体解析——xbatis @Table 注解实体走 JpaEntityResolver
        // （@Table value / @TableField / @TableId / @Ignore 轨），MP 兼容注解实体走既有 MP EntityResolver
        // T-TS-07：兜底推断显式传工厂调用所属项目（多项目同开时不再受「首个打开项目」影响）
        TableInfo table = wrapperType == WrapperType.XBATIS
                ? resolveTableInfoForXbatis(entityClass, typeArgName, factoryCall.getProject())
                : resolveTableInfo(entityClass, typeArgName, factoryCall.getProject());
        if (table == null) {
            table = inferEntityFromChain(factoryCall);
        }

        // 宿主 = 包含该工厂调用的最外层表达式（如 getOne(Wrappers.<T>lambdaQuery()...) 的整个调用）
        PsiElement host = factoryCall;
        for (PsiElement cursor = factoryCall.getParent(); cursor != null; cursor = cursor.getParent()) {
            if (cursor instanceof PsiExpression) host = cursor;
        }
        List<PsiMethodCallExpression> chainCalls = new ArrayList<>();
        LinkedHashSet<Integer> seenEnds = new LinkedHashSet<>();
        for (PsiMethodCallExpression call : PsiTreeUtil.collectElementsOfType(host, PsiMethodCallExpression.class)) {
            if (PsiTreeUtil.getParentOfType(call, PsiLambdaExpression.class) != null) continue;
            // 链起点是工厂调用（沿 qualifier 回溯命中）；排除工厂调用自身
            if (call == factoryCall) continue;
            if (!ArgResolver.collectCallChain(call).contains(factoryCall)) continue;
            if (!seenEnds.add(call.getTextRange().getEndOffset())) continue;
            chainCalls.add(call);
        }
        chainCalls.sort(Comparator.comparingInt(c -> c.getTextRange().getEndOffset()));

        // 链式终结点（Q17/U07-④⑤/U11-⑤，T-M15-01）：`service.lambdaQuery()...one()` / `service.lambdaUpdate()...remove()`。
        // 终结点是链的消费方法（非 Wrapper 操作），从链中剔除并记录消费语义。
        // M19.6：wrapper.clear() 截断（MP 语义清空全部条件）——遇 clear 丢弃此前收集（与 buildChain 一致）
        List<PsiMethodCallExpression> clearedCalls = new ArrayList<>();
        for (PsiMethodCallExpression call : chainCalls) {
            if (CLEAR_METHOD.equals(call.getMethodExpression().getReferenceName())) {
                clearedCalls.clear();
            } else {
                clearedCalls.add(call);
            }
        }
        PsiMethodCallExpression terminal = null;
        // T-ExistsChainTerminal：终结点判定排除「带参 exists」——它是条件方法而非终结点。裸工厂链直接作
        // service 实参时链内无真终结点，旧判定会选中带参 exists → 条件丢失 + 整链误包 SELECT EXISTS
        for (int i = clearedCalls.size() - 1; i >= 0; i--) {
            PsiMethodCallExpression candidate = clearedCalls.get(i);
            String candidateName = candidate.getMethodExpression().getReferenceName();
            if (candidateName != null && CHAIN_TERMINAL_METHOD_NAMES.contains(candidateName)
                    && !isExistsConditionCall(candidate)) {
                terminal = candidate;
                break;
            }
        }
        List<PsiMethodCallExpression> nonTerminalCalls = new ArrayList<>(clearedCalls);
        if (terminal != null) nonTerminalCalls.remove(terminal);
        String terminalName = terminal != null ? terminal.getMethodExpression().getReferenceName() : null;
        // XB（M44）：DeleteChain 条件链转 DELETE 语义（无 set 调用，终结点 execute 已入终结名单剔除）
        boolean chainDeleteConsumed = REMOVE_TERMINAL.equals(terminalName)
                || XBATIS_DELETE_CHAIN.equals(xbatisChainFamilyOf(factoryCall));
        boolean chainCountConsumed = COUNT_TERMINAL.equals(terminalName);
        boolean chainExistsConsumed = EXISTS_TERMINAL.equals(terminalName);
        // P1-04（M19.5）：链上 `update(entity)` 终结点（带实参）——实体字段展开 SET
        // （复用 SqlPreviewService.updateEntityConsumed 分支：实体全列 SET + 主键进 WHERE）
        boolean chainUpdateEntity = UPDATE_TERMINAL.equals(terminalName)
            && terminal != null && terminal.getArgumentList().getExpressions().length > 0;
        // T-M29-02/03：链式分页终结点（`service.lambdaQuery()...page(new Page<>(...))`）——Page 实参解析为 LIMIT
        // R12 T-PaginationSim：连同 searchCount 标志一并解析（D3=A）
        PageArg chainPageArg = PAGE_TERMINAL.equals(terminalName) ? parsePageArg(terminal) : null;

        List<WrapperCall> wrapperCalls = new ArrayList<>();
        for (PsiMethodCallExpression call : nonTerminalCalls) {
            wrapperCalls.add(ArgResolver.toWrapperCall(call, table,
                entityClass != null ? entityClass.getName() : null, resolveVariables));
        }

        // 消费识别：wrapper 作为实参传给删除/计数/exists/实体更新方法（`service.remove(Wrappers.<T>lambdaQuery()...)` 等）
        PsiMethodCallExpression consuming = consumingCall(factoryCall);
        boolean deleteConsumed = chainDeleteConsumed
            || (consuming != null && DELETE_CONSUMER_METHOD_NAMES.contains(consuming.getMethodExpression().getReferenceName()));
        boolean countConsumed = chainCountConsumed
            || (consuming != null && COUNT_CONSUMER_METHOD_NAMES.contains(consuming.getMethodExpression().getReferenceName()));
        boolean existsConsumed = chainExistsConsumed
            || (consuming != null && EXISTS_CONSUMER.equals(consuming.getMethodExpression().getReferenceName()));
        boolean updateEntityConsumed = isEntityUpdateConsumer(factoryCall) || chainUpdateEntity;
        // T-M29-02/03：分页消费（链式终结点 page / `service.page(page, Wrappers.lambdaQuery()...)` 实参形态）
        // R12 T-PaginationSim：链式终结点优先，实参形态次之；searchCount 取命中形态的解析值
        PageArg pageArg = chainPageArg;
        if (pageArg == null && consuming != null
            && PAGE_CONSUMER_METHOD_NAMES.contains(consuming.getMethodExpression().getReferenceName())) {
            pageArg = parsePageArg(consuming);
        }
        LimitClause pageLimit = pageArg != null ? pageArg.limit : null;

        String entityName = table != null && table.getEntityClassName() != null
            ? table.getEntityClassName() : (entityClass != null ? entityClass.getName() : null);
        WrapperChain chain = new WrapperChain(wrapperType, entityName, wrapperCalls, null,
            deleteConsumed, countConsumed, existsConsumed, updateEntityConsumed, pageLimit,
            pageArg != null ? pageArg.searchCount : true);

        // O-8（缺陷 B）：字符串服务链实体解析失败标记（preview 侧显式降级，不再静默 null）——
        // 仅服务链形态（接收者非静态工厂类：Wrappers/ChainWrappers/MPJ/Db 解析为 PsiClass 排除，
        // this/无 qualifier 服务链同按服务链计），工厂形态维持既有 null 语义
        PsiElement qualifierResolve = factoryCall.getMethodExpression().getQualifierExpression() instanceof PsiReferenceExpression
            ? ((PsiReferenceExpression) factoryCall.getMethodExpression().getQualifierExpression()).resolve() : null;
        String factoryRefName = factoryCall.getMethodExpression().getReferenceName();
        boolean serviceChainEntityUnresolved = table == null
            && factoryRefName != null && SERVICE_CHAIN_METHOD_NAMES.contains(factoryRefName)
            && !(qualifierResolve instanceof PsiClass);

        return new LocatedWrapper(chain, table, entityClass != null ? entityClass.getContainingFile() : null,
            serviceChainEntityUnresolved);
    }

    /**
     * O-8（缺陷 B）第三通道：服务链 qualifier 变量的服务类型泛型实参 → 实体 PsiClass。
     * {@code service.query()} 的 service 引用 resolve 到变量 → 其类型（{@code DeviceService}）→
     * 父接口/父类泛型实参（{@code IService<Device>}）→ 实体。真实 PSI 解析，LightPlatform 可测
     * （fixture 内声明 {@code interface IService<T>} 与 {@code interface DeviceService extends IService<Device>} 桩即可）。
     *
     * <p>实体取<b>末位</b>泛型实参：MP 惯例实体在末位（{@code IService<T>} 单参、{@code ServiceImpl<M, T>} 双参，
     * 首参可能是 Mapper）；类型自身已有泛型实参（{@code IService<Device> service} 形态）时优先。
     * PsiTypeParameter 守卫（宁可不识别不出错表），全失败返回 null 交由降级/推断链兜底。</p>
     */
    private static PsiClass entityClassFromServiceQualifier(PsiMethodCallExpression factoryCall) {
        PsiExpression qualifier = factoryCall.getMethodExpression().getQualifierExpression();
        if (!(qualifier instanceof PsiReferenceExpression)) return null;
        PsiElement resolved = ((PsiReferenceExpression) qualifier).resolve();
        if (!(resolved instanceof PsiVariable)) return null;
        PsiType serviceTypeRaw = ((PsiVariable) resolved).getType();
        if (!(serviceTypeRaw instanceof PsiClassType)) return null;
        PsiClassType serviceType = (PsiClassType) serviceTypeRaw;
        PsiClass serviceClass = serviceType.resolve();
        if (serviceClass == null) return null;
        // ① 服务类型自身泛型实参（`IService<Device> service` 参数直以接口类型声明）
        PsiClass selfEntity = lastEntityOf(serviceType);
        if (selfEntity != null) return selfEntity;
        // ② 父接口/父类泛型实参（`DeviceService extends IService<Device>`，接口 extends 与类 implements 皆扫）
        List<PsiClassType> supers = new ArrayList<>();
        if (serviceClass.getExtendsList() != null) {
            for (PsiClassType t : serviceClass.getExtendsList().getReferencedTypes()) supers.add(t);
        }
        if (serviceClass.getImplementsList() != null) {
            for (PsiClassType t : serviceClass.getImplementsList().getReferencedTypes()) supers.add(t);
        }
        for (PsiClassType superType : supers) {
            PsiClass entity = lastEntityOf(superType);
            if (entity != null) return entity;
        }
        return null;
    }

    /** Kotlin 局部函数 lastEntityOf：类型末位泛型实参 → 实体 PsiClass（PsiTypeParameter 守卫）。 */
    private static PsiClass lastEntityOf(PsiClassType type) {
        PsiType[] parameters = type.getParameters();
        if (parameters.length == 0) return null;
        PsiType last = parameters[parameters.length - 1];
        if (!(last instanceof PsiClassType)) return null;
        PsiClass resolved = ((PsiClassType) last).resolve();
        return resolved instanceof com.intellij.psi.PsiTypeParameter ? null : resolved;
    }

    /**
     * 服务链实体推断（T-M15-01）：{@code service.lambdaQuery()} 无类型实参时，从链内调用<b>首参</b>的方法引用
     * （{@code eq(Entity::getX, v)} / {@code set(Entity::getX, v)} 的列位置）推断实体与列。
     *
     * <p>优先 resolve 方法引用的所属类（真实 IDE 环境）；resolve 不可用时（LightPlatform 测试环境
     * 方法引用 resolve 返回 null，与既有「类型参数类名兜底」同一原则）按
     * qualifier 文本类名 + getter 反解属性列兜底。</p>
     */
    private static TableInfo inferEntityFromChain(PsiMethodCallExpression chainHead) {
        PsiElement host = chainHead;
        for (PsiElement cursor = chainHead.getParent(); cursor != null; cursor = cursor.getParent()) {
            if (cursor instanceof PsiExpression) host = cursor;
        }

        PsiClass resolvedClass = null;
        // (类名文本, 属性名) 对，按收集顺序
        List<String[]> refs = new ArrayList<>();
        List<PsiMethodCallExpression> calls = new ArrayList<>(
            PsiTreeUtil.collectElementsOfType(host, PsiMethodCallExpression.class));
        // collectElementsOfType 为 pre-order（外层调用先于内层 qualifier 链）；
        // 链式调用各节点 textOffset 均指向链起点，须按 endOffset 恢复源码顺序（同 buildChainFromFactory）
        calls.sort(Comparator.comparingInt(c -> c.getTextRange().getEndOffset()));
        for (PsiMethodCallExpression call : calls) {
            if (call == chainHead) continue;
            if (!ArgResolver.collectCallChain(call).contains(chainHead)) continue;
            PsiExpression[] expressions = call.getArgumentList().getExpressions();
            for (int index = 0; index < expressions.length; index++) {
                PsiExpression expr = expressions[index];
                if (!(expr instanceof PsiMethodReferenceExpression)) continue;
                PsiMethodReferenceExpression ref = (PsiMethodReferenceExpression) expr;
                // 仅列位置：首参（eq/set/orderBy 等）+ select 的任意位置；selectAs 第二参（VO 字段）跳过
                if (index != 0 && !"select".equals(call.getMethodExpression().getReferenceName())) continue;
                String refName = ref.getReferenceName();
                if (refName == null) continue;
                String property = getterToProperty(refName);
                if (property == null) continue;
                if (resolvedClass == null && ref.resolve() instanceof PsiMethod) {
                    resolvedClass = ((PsiMethod) ref.resolve()).getContainingClass();
                }
                PsiExpression qualifierExpr = ref.getQualifierExpression();
                String qualifierName = qualifierExpr instanceof PsiReferenceExpression
                    ? ((PsiReferenceExpression) qualifierExpr).getReferenceName() : null;
                if (qualifierName != null) refs.add(new String[]{qualifierName, property});
            }
        }
        if (refs.isEmpty()) return null;

        // resolve 优先（真实 IDE）：@TableName/@TableField 注解解析
        if (resolvedClass != null) return new EntityResolver().resolve(resolvedClass);

        // T-M23.10：方法引用 resolve 不可用时（LightPlatform 测试环境），按类名文本在**宿主文件**内
        // 解析 PsiClass（嵌套 static class 可解析；类声明不在表达式子树内，须以文件为遍历根），
        // 使 @TableLogic/@Version 等注解在 JoinWrappers.update/delete 等工厂形态同样生效；
        // 解析失败退回纯文本兜底（既有行为）
        String className = refs.get(0)[0];
        if (host.getContainingFile() != null) {
            for (PsiClass clazz : PsiTreeUtil.collectElementsOfType(host.getContainingFile(), PsiClass.class)) {
                if (className.equals(clazz.getName())) return new EntityResolver().resolve(clazz);
            }
        }

        // 文本兜底：类名推断表名（走统一推断策略 + 修饰链，T-M19.4 全局语义）+ getter 属性列（与既有命名策略兜底一致）
        // T-TS-07：显式传宿主表达式所属项目——多项目同开且项目级表配置不同时，兜底推断不再
        // 受「首个打开项目」影响（宿主是 PSI，project 必可得）
        LinkedHashSet<String> distinctProps = new LinkedHashSet<>();
        for (String[] ref : refs) distinctProps.add(ref[1]);
        List<ColumnInfo> columns = new ArrayList<>();
        for (String prop : distinctProps) {
            columns.add(new ColumnInfo(prop, NamingStrategy.INSTANCE.toSnakeCase(prop),
                false, false, null, null, null, null));
        }
        return new TableInfo(className, EntityResolver.inferDecoratedTableName(className, host.getProject()),
            columns, null, null, null, List.of());
    }

    /** getter 方法名转属性名（{@code getStationId} → {@code stationId}，{@code isEnabled} → {@code enabled}）；非 getter/is 前缀返回 null。 */
    private static String getterToProperty(String methodName) {
        String prefix;
        if (methodName.startsWith("get") && methodName.length() > 3) prefix = "get";
        else if (methodName.startsWith("is") && methodName.length() > 2) prefix = "is";
        else return null;
        String rest = methodName.substring(prefix.length());
        return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
    }

    /**
     * 识别 Wrapper 工厂/链起点调用：
     * <ul>
     *  <li>{@code Wrappers.<T>lambdaQuery()} 静态工厂 / {@code ChainWrappers.lambdaQueryChain(Class)} 等
     *      （P1-05，qualifier 指向工具类）；</li>
     *  <li>{@code service.lambdaQuery()} / {@code service.lambdaUpdate()} 服务链（T-M15-01，qualifier 为任意
     *      service 引用或隐式 this 无 qualifier）；仅放行 {@link #SERVICE_CHAIN_METHOD_NAMES}，避免把
     *      {@code service.update(wrapper)} 等消费调用误判为链起点（P1-03：query/update 为歧义方法，
     *      需 {@link #isServiceChainStart} 守卫）。</li>
     * </ul>
     */
    private static boolean isWrapperFactoryCall(PsiMethodCallExpression call) {
        // XB（M44）：xbatis 双坐标静态工厂（QueryChain.of/create 等）优先识别
        if (isXbatisFactoryCall(call)) return true;
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null || !WRAPPER_FACTORY_METHOD_NAMES.contains(methodName)) return false;
        PsiExpression qualifierExpr = call.getMethodExpression().getQualifierExpression();
        if (!(qualifierExpr instanceof PsiReferenceExpression)) {
            return SERVICE_CHAIN_METHOD_NAMES.contains(methodName) && isServiceChainStart(call);
        }
        PsiReferenceExpression qualifier = (PsiReferenceExpression) qualifierExpr;
        com.intellij.psi.PsiElement resolved = qualifier.resolve();
        if (resolved instanceof PsiClass) {
            return ((PsiClass) resolved).getName() != null
                && WRAPPER_FACTORY_CLASS_NAMES.contains(((PsiClass) resolved).getName());
        }
        return qualifier.getReferenceName() != null && WRAPPER_FACTORY_CLASS_NAMES.contains(qualifier.getReferenceName())
            || (SERVICE_CHAIN_METHOD_NAMES.contains(methodName) && isServiceChainStart(call));
    }

    /**
     * 服务链起点守卫（P1-03，M19.5）：{@code lambdaQuery}/{@code lambdaUpdate} 恒为链起点；
     * {@code query}/{@code update} 同时是<b>消费方法名</b>（{@code service.update(wrapper)}）——仅当调用<b>无实参</b>
     * 且存在<b>后续链调用</b>（该调用是某外层方法调用的 qualifier）时才按链起点识别。
     * 纯语法 O(1)（不 resolve），符合 M10 性能红线；供 {@link WrapperAnchorDetector} 复用（原 Kotlin internal）。
     */
    public static boolean isServiceChainStart(PsiMethodCallExpression call) {
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null || !AMBIGUOUS_SERVICE_CHAIN_METHODS.contains(methodName)) return true;
        if (call.getArgumentList().getExpressions().length > 0) return false;
        PsiElement parent = call.getParent();
        return parent instanceof PsiReferenceExpression && parent.getParent() instanceof PsiMethodCallExpression;
    }

    /** 与消费方法名冲突的服务链起点方法（需 {@link #isServiceChainStart} 守卫）。 */
    private static final Set<String> AMBIGUOUS_SERVICE_CHAIN_METHODS = Set.of("query", "update");

    /** MPJ 官方/旧工厂类名（T-M20-02）：JoinWrappers（com.github.yulichang.toolkit）与 MPJWrappers——
     *  query/update/lambda 的映射语义与 Wrappers/ChainWrappers 不同，需按 qualifier 区分。 */
    private static final Set<String> MPJ_FACTORY_CLASS_NAMES = Set.of("JoinWrappers", "MPJWrappers");

    // ------------------------------------------------------------------
    // XB（M44 其他 ORM 第一批）：xbatis（原 mybatis-mp）双坐标链识别
    // ------------------------------------------------------------------

    /**
     * xbatis 链类 FQN 前缀（双坐标）：新版 {@code cn.xbatis.}（1.10.8）与旧版 {@code cn.mybatis.mp.}
     * （坐标 cn.mybatis-mp，冻结 1.8.1-RC3——javap 实证 Java 包名为 cn.mybatis.mp）。
     * 变量/工厂类型的 canonicalText 前缀匹配即可区分，
     * 防与 MP {@code QueryWrapper} 等同名类混淆（按 FQN 前缀而非短名）。
     */
    private static final List<String> XBATIS_FQN_PREFIXES = List.of("cn.xbatis.", "cn.mybatis.mp.");

    /**
     * xbatis 工厂/链类简单名（javap cn.xbatis.core.sql.executor.chain 实证：QueryChain/UpdateChain/
     * DeleteChain/InsertChain；1.x 系无 QueryWrapper 类，QueryWrapper/UpdateWrapper 为防御性超集，
     * 仅在静态工厂形态 {@code Xxx.of(...)} 下生效——MP 的 QueryWrapper 无静态 of/create，不产生误判）。
     */
    private static final Set<String> XBATIS_FACTORY_CLASS_NAMES = Set.of(
            "QueryChain", "UpdateChain", "DeleteChain", "InsertChain", "QueryWrapper", "UpdateWrapper");

    /** xbatis 工厂/链类简单名（锚点快速路径 O(1) 文本匹配用；语义见上）。 */
    static final Set<String> xbatisFactoryClassNames = XBATIS_FACTORY_CLASS_NAMES;

    /** xbatis 静态工厂方法名（javap 实证：`QueryChain.of(mapper, Class)` / `QueryChain.create()`）。 */
    private static final Set<String> XBATIS_FACTORY_METHOD_NAMES = Set.of("of", "create");

    /** xbatis 静态工厂方法名（锚点快速路径 O(1) 文本匹配用）。 */
    static final Set<String> xbatisFactoryMethodNames = XBATIS_FACTORY_METHOD_NAMES;

    /** xbatis 链家族（XbatisConverter UPDATE/DELETE 语义分派用）。 */
    private static final String XBATIS_DELETE_CHAIN = "DeleteChain";

    /**
     * XB：调用是否为 xbatis 静态工厂（`QueryChain.of(userDao, UserEntity.class)` /
     * `QueryChain.create()`，双坐标同形）。qualifier 简名 + 工厂方法名双重门控——
     * `Optional.of` / `Map.of` 等通用 `of` 调用因 qualifier 不在 XBATIS_FACTORY_CLASS_NAMES 而天然排除。
     */
    static boolean isXbatisFactoryCall(PsiMethodCallExpression call) {
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null || !XBATIS_FACTORY_METHOD_NAMES.contains(methodName)) return false;
        PsiExpression qualifierExpr = call.getMethodExpression().getQualifierExpression();
        String qualifierName = qualifierExpr instanceof PsiReferenceExpression
                ? ((PsiReferenceExpression) qualifierExpr).getReferenceName() : null;
        return qualifierName != null && XBATIS_FACTORY_CLASS_NAMES.contains(qualifierName);
    }

    /** XB：类型是否为 xbatis 链类（canonicalText 按双坐标 FQN 前缀判定，不 resolve）。 */
    private static boolean isXbatisType(PsiType type) {
        if (type == null) return false;
        try {
            String canonical = type.getCanonicalText();
            for (String prefix : XBATIS_FQN_PREFIXES) {
                if (canonical.startsWith(prefix)) return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 工厂/链起点调用 → Wrapper 类型映射（T-M20-02 起按 (qualifier 类名, 方法名) 映射，
     * 不能只按方法名——{@code Wrappers.query()} → QUERY 而 {@code JoinWrappers.query()} → MPJ_QUERY）：
     * <ul>
     *  <li>Wrappers / ChainWrappers 静态工厂（P1-05）：lambdaQuery → LAMBDA_QUERY、query → QUERY 等；</li>
     *  <li>MPJ 工厂（JoinWrappers / MPJWrappers）：{@code lambda()} / {@code lambdaJoin()} → MPJ_LAMBDA、
     *      {@code query()} / {@code queryJoin()} → MPJ_QUERY；{@code delete()} / {@code update()} 返回
     *      DeleteJoinWrapper / UpdateJoinWrapper（T-M21-08，原 P3-01 提前：UPDATE_JOIN/DELETE_JOIN）；</li>
     *  <li>服务链（{@code service.lambdaQuery()} 等，T-M15-01，qualifier 非工厂类）：按方法名映射。</li>
     * </ul>
     * qualifier 取引用文本（referenceName）即可分辨，无需 resolve（LightPlatform 无 MPJ classpath 也生效）。
     */
    private static WrapperType factoryWrapperType(PsiMethodCallExpression call) {
        String methodName = call.getMethodExpression().getReferenceName();
        PsiExpression qualifierExpr = call.getMethodExpression().getQualifierExpression();
        String qualifierName = qualifierExpr instanceof PsiReferenceExpression
            ? ((PsiReferenceExpression) qualifierExpr).getReferenceName() : null;
        // XB（M44）：xbatis 双坐标工厂（QueryChain/UpdateChain/DeleteChain/InsertChain 的 of/create）→ XBATIS，
        // 先于 MP 分支——"of"/"create" 不在 MP 工厂映射内，避免落到服务链兜底返回 null
        if (qualifierName != null && XBATIS_FACTORY_CLASS_NAMES.contains(qualifierName)
                && methodName != null && XBATIS_FACTORY_METHOD_NAMES.contains(methodName)) {
            return WrapperType.XBATIS;
        }
        if (qualifierName != null && WRAPPER_FACTORY_CLASS_NAMES.contains(qualifierName)) {
            if (methodName == null) return null;
            switch (methodName) {
                case "lambdaQuery": return WrapperType.LAMBDA_QUERY;
                case "lambdaUpdate": return WrapperType.LAMBDA_UPDATE;
                case "lambdaDelete": return WrapperType.LAMBDA_DELETE;
                case "query": return MPJ_FACTORY_CLASS_NAMES.contains(qualifierName) ? WrapperType.MPJ_QUERY : WrapperType.QUERY;
                // T-M21-08：JoinWrappers.update(Class) → UpdateJoinWrapper（UPDATE_JOIN）；
                // Wrappers.update()（字符串版 UpdateWrapper 工厂）仍为 UPDATE
                case "update": return MPJ_FACTORY_CLASS_NAMES.contains(qualifierName) ? WrapperType.UPDATE_JOIN : WrapperType.UPDATE;
                // ChainWrappers 静态工厂（MP 官方 API，P1-05）
                case "queryChain": return WrapperType.QUERY;
                case "lambdaQueryChain": return WrapperType.LAMBDA_QUERY;
                case "lambdaUpdateChain": return WrapperType.LAMBDA_UPDATE;
                case "lambdaDeleteChain": return WrapperType.LAMBDA_DELETE;
                case "updateChain": return WrapperType.UPDATE;
                case "deleteChain": return WrapperType.LAMBDA_DELETE;
                // T-M20-02：MPJ 工厂方法（lambda/lambdaJoin/queryJoin 仅 JoinWrappers/MPJWrappers 收录）
                case "lambda":
                case "lambdaJoin":
                    return MPJ_FACTORY_CLASS_NAMES.contains(qualifierName) ? WrapperType.MPJ_LAMBDA : null;
                case "queryJoin":
                    return MPJ_FACTORY_CLASS_NAMES.contains(qualifierName) ? WrapperType.MPJ_QUERY : null;
                // T-M21-08：JoinWrappers.delete(Class) → DeleteJoinWrapper（DELETE_JOIN）；
                // 非 MPJ 工厂无 delete 静态工厂方法 → null
                case "delete":
                    return MPJ_FACTORY_CLASS_NAMES.contains(qualifierName) ? WrapperType.DELETE_JOIN : null;
                default: return null;
            }
        }
        // 服务链（service.lambdaQuery() / service.lambdaUpdate()，T-M15-01）按方法名映射
        if (methodName == null) return null;
        switch (methodName) {
            case "lambdaQuery": return WrapperType.LAMBDA_QUERY;
            case "lambdaUpdate": return WrapperType.LAMBDA_UPDATE;
            case "query": return WrapperType.QUERY;
            case "update": return WrapperType.UPDATE;
            default: return null;
        }
    }

    // ------------------------------------------------------------------
    // 构建调用链
    // ------------------------------------------------------------------

    /**
     * M19.6（2026-08-13 用户实测）：wrapper 作为实参传入自定义 helper 方法
     * （{@code applyBusinessOpenInfoFilter(wrapper, dto)}，清单 P16 "wrapper 作为方法入参就地修改"形态）——
     * 展开方法体内对该形参的<b>直接链式调用</b>并入主链（helper 内追加的 apply/in/eq 等条件参与 SQL 生成）。
     *
     * <p>规则：</p>
     * <ul>
     *  <li>仅收集链首 qualifier resolve 到对应形参的调用（{@code wrapper.apply(...)} / {@code wrapper.in(...)}）；</li>
     *  <li>lambda 体内调用（{@code wrapper.and(w -> w.eq(...))}）的 w 是 lambda 参数，不在此收集（由 NestedArg 解析）；</li>
     *  <li>只展开一层（helper 内不再递归传参）；</li>
     *  <li>展开调用的逻辑位置由调用方锚定在 helper <b>调用点</b>之后（文件偏移可能早于主链）。</li>
     * </ul>
     */
    private static List<PsiMethodCallExpression> expandHelperCalls(PsiMethodCallExpression call, PsiReferenceExpression ref) {
        PsiMethod method = call.resolveMethod();
        if (method == null || method.getBody() == null) return List.of();
        PsiExpression[] expressions = call.getArgumentList().getExpressions();
        int paramIndex = -1;
        for (int i = 0; i < expressions.length; i++) {
            if (expressions[i] == ref) {
                paramIndex = i;
                break;
            }
        }
        if (paramIndex < 0) return List.of();
        // Kotlin getParameter(index) 越界返回 null（→ emptyList）；Java 数组访问须显式边界检查
        // （调用点实参数可能多于方法形参数：varargs/重载解析差异）
        PsiParameter[] parameters = method.getParameterList().getParameters();
        if (paramIndex >= parameters.length) return List.of();
        PsiParameter param = parameters[paramIndex];
        List<PsiMethodCallExpression> result = new ArrayList<>();
        for (PsiMethodCallExpression inner : PsiTreeUtil.collectElementsOfType(method.getBody(), PsiMethodCallExpression.class)) {
            List<PsiMethodCallExpression> chain = ArgResolver.collectCallChain(inner);
            if (chain.isEmpty()) continue;
            PsiExpression headQualifier = chain.get(0).getMethodExpression().getQualifierExpression();
            if (headQualifier instanceof PsiReferenceExpression
                && ((PsiReferenceExpression) headQualifier).resolve() == param) {
                result.addAll(chain);
            }
        }
        return result;
    }

    /**
     * 从 Wrapper 变量构建完整调用链。
     *
     * <p>调用来源（按源码 offset 合并排序、去重）：</p>
     * <ul>
     *  <li>变量初始化表达式中的链式调用（new W().eq(...)）</li>
     *  <li>变量后续引用上的方法调用（wrapper.eq(...)）</li>
     * </ul>
     */
    private static LocatedWrapper buildChain(PsiVariable variable, boolean resolveVariables) {
        // 基类型声明降级：类型不是具体 Wrapper 时从初始化表达式链首推断（`Wrapper<X> w = new LambdaQueryWrapper<X>()...`）；
        // O-8（缺陷 A）：var 推断形态（typeElement 文本 "var"）工厂链头原实现不识别，补第三顺位
        PsiElement varTypeElement = variable instanceof PsiLocalVariable
            ? ((PsiLocalVariable) variable).getTypeElement() : null;
        boolean isVarDeclaration = varTypeElement != null && "var".equals(varTypeElement.getText());
        WrapperType wrapperType = detectWrapperType(variable.getType());
        if (wrapperType == null) wrapperType = detectWrapperTypeFromInitializer(variable.getInitializer());
        if (wrapperType == null && isVarDeclaration) wrapperType = wrapperTypeFromInitializerChain(variable.getInitializer());
        if (wrapperType == null) return null;
        // O-8（缺陷 A）：var/推断形态声明类型不可识别 → 泛型实参解析不到实体，
        // 从 initializer 链头补实体解析（`var w = Wrappers.<Device>query()` 显式类型实参 /
        // `var w = new QueryWrapper<Device>()` 构造类型），与工厂路径实体解析同源
        EntityInfo info = entityInfoFromType(variable.getType());
        PsiClass entityClass = info.entityClass;
        String typeArgName = info.typeArgName;
        if (entityClass == null && typeArgName == null && detectWrapperType(variable.getType()) == null) {
            EntityInfo initInfo = entityInfoFromInitializerChain(variable.getInitializer());
            entityClass = initInfo.entityClass;
            typeArgName = initInfo.typeArgName;
        }
        // XB（M44）：xbatis 变量声明形态实体解析与工厂形态同轨（xbatis @Table → JpaEntityResolver）
        // T-TS-07：兜底推断显式传变量所属项目（多项目同开时不再受「首个打开项目」影响）
        TableInfo table = wrapperType == WrapperType.XBATIS
                ? resolveTableInfoForXbatis(entityClass, typeArgName, variable.getProject())
                : resolveTableInfo(entityClass, typeArgName, variable.getProject());
        String constructorAlias = extractConstructorAlias(variable.getInitializer());

        // (调用, 逻辑顺序锚点) 对；锚点=所属主链调用的 endOffset
        List<Object[]> callElements = new ArrayList<>();
        // XB（M44）：DeleteChain 变量分步形态（`DeleteChain c = DeleteChain.of(mapper, X.class); c.eq(...)`)
        // → DELETE 语义（XbatisConverter 按 deleteConsumed 分派）
        boolean deleteConsumed = variable.getType() instanceof PsiClassType
                && "DeleteChain".equals(((PsiClassType) variable.getType()).getClassName());
        boolean countConsumed = false;
        boolean existsConsumed = false;
        boolean updateEntityConsumed = false;
        // T-M29-02/03：分页消费（`service.page(new Page<>(...), wrapper)`）→ Page 实参解析的 LIMIT 子句
        // R12 T-PaginationSim：连同 searchCount 标志一并解析（D3=A，默认 true 对齐 MP Page 默认）
        LimitClause pageLimit = null;
        boolean pageSearchCount = true;

        // 1. 初始化表达式中的链式调用；若链首为工厂方法调用（baseJoinWrapper()），展开其方法体内的 wrapper 链（P03）；
        //    Wrappers 静态工厂调用（Wrappers.<T>lambdaQuery()）不是 Wrapper 操作，从链中剔除
        if (variable.getInitializer() != null) {
            List<PsiMethodCallExpression> initCalls =
                ArgResolver.collectChainFromInitializer(variable.getInitializer());
            // dropWhile { isWrapperFactoryCall(it) }
            int start = 0;
            while (start < initCalls.size() && isWrapperFactoryCall(initCalls.get(start))) start++;
            for (PsiMethodCallExpression call : expandFactoryCalls(initCalls.size() > start ? initCalls.subList(start, initCalls.size()) : java.util.List.of())) {
                callElements.add(new Object[]{call, call.getTextRange().getEndOffset()});
            }
        }

        // 2. 变量引用上的方法调用（分步写法）+ 消费识别（`service.remove(wrapper)` / `service.count(wrapper)` 等）
        for (com.intellij.psi.PsiReference psiRef : ReferencesSearch.search(variable)) {
            if (!(psiRef.getElement() instanceof PsiReferenceExpression)) continue;
            PsiReferenceExpression refExpression = (PsiReferenceExpression) psiRef.getElement();
            if (isConsumerArgument(refExpression, DELETE_CONSUMER_METHOD_NAMES)) deleteConsumed = true;
            if (isConsumerArgument(refExpression, COUNT_CONSUMER_METHOD_NAMES)) countConsumed = true;
            if (isConsumerArgument(refExpression, Set.of(EXISTS_CONSUMER))) existsConsumed = true;
            if (isEntityUpdateArgument(refExpression)) updateEntityConsumed = true;
            // T-M29-02：分页消费（`service.page(page, wrapper)` / `mapper.selectPage(page, wrapper)`）
            if (pageLimit == null) {
                PsiMethodCallExpression pageCall = pageConsumingCall(refExpression);
                if (pageCall != null) {
                    PageArg arg = parsePageArg(pageCall);
                    if (arg != null) {
                        pageLimit = arg.limit;
                        pageSearchCount = arg.searchCount;
                    }
                }
            }
            // w 引用的 PSI 结构：w.eq(...) 中 w 引用 → methodExpression(eq 引用) → PsiMethodCallExpression
            // 向上遍历找最近的调用表达式
            PsiMethodCallExpression call = null;
            for (PsiElement cur = refExpression.getParent(); cur != null; cur = cur.getParent()) {
                if (cur instanceof PsiMethodCallExpression) {
                    call = (PsiMethodCallExpression) cur;
                    break;
                }
            }
            if (call == null) continue;
            // 仅收集 qualifier 是该引用的调用（wrapper.eq(...) 而非方法实参中的引用）；
            // 链式延续（`wrapper.set(a).set(b)` 的 set(b) / `wrapper.lambda().eq(...)` 的 eq）不直接引用
            // wrapper——由首个直接调用扩展收集整条延续链（T-M15-05/U09-④）
            PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
            boolean isDirect = qualifier == refExpression;
            if (isDirect) {
                for (PsiMethodCallExpression c : collectExtendingChain(call)) {
                    callElements.add(new Object[]{c, call.getTextRange().getEndOffset()});
                }
            }
            boolean isLambdaConverted = qualifier instanceof PsiMethodCallExpression
                && "lambda".equals(((PsiMethodCallExpression) qualifier).getMethodExpression().getReferenceName())
                && ((PsiMethodCallExpression) qualifier).getMethodExpression().getQualifierExpression() == refExpression;
            if (isLambdaConverted) {
                callElements.add(new Object[]{call, call.getTextRange().getEndOffset()});
            }
            // M19.6（2026-08-13 用户实测）：wrapper 作为<b>实参</b>传入自定义 helper 方法
            // （`applyBusinessOpenInfoFilter(wrapper, dto)`，清单 P16 "wrapper 作为方法入参就地修改"）——
            // 展开方法体内对该形参的链式调用；展开调用<b>锚定在 helper 调用点</b>（逻辑顺序，而非文件偏移）
            for (PsiExpression arg : call.getArgumentList().getExpressions()) {
                if (arg == refExpression) {
                    for (PsiMethodCallExpression c : expandHelperCalls(call, refExpression)) {
                        callElements.add(new Object[]{c, call.getTextRange().getEndOffset()});
                    }
                    break;
                }
            }
        }

        // 合并去重（注意：链式调用表达式的 textOffset 可能相同——都从 new 起始，
        // 必须用 endOffset 区分）；按**逻辑顺序**排序：主链按源码偏移，跨方法展开的调用
        // 锚定其 helper 调用点（M19.6），组内按自身源码偏移
        LinkedHashSet<Integer> seenEnds = new LinkedHashSet<>();
        List<Object[]> distinctElements = new ArrayList<>();
        for (Object[] pair : callElements) {
            int endOffset = ((PsiMethodCallExpression) pair[0]).getTextRange().getEndOffset();
            if (seenEnds.add(endOffset)) distinctElements.add(pair);
        }
        distinctElements.sort(Comparator
            .comparingInt((Object[] pair) -> (Integer) pair[1])
            .thenComparingInt(pair -> ((PsiMethodCallExpression) pair[0]).getTextRange().getEndOffset()));
        List<PsiMethodCallExpression> sortedCalls = new ArrayList<>();
        for (Object[] pair : distinctElements) sortedCalls.add((PsiMethodCallExpression) pair[0]);

        // M19.6（2026-08-13 用户实测）：wrapper.clear() 截断——MP 语义清空全部条件；
        // 按源码顺序遇 clear() 丢弃此前已收集的调用（clear 自身不入链）
        List<PsiMethodCallExpression> truncatedCalls = new ArrayList<>();
        for (PsiMethodCallExpression call : sortedCalls) {
            if (CLEAR_METHOD.equals(call.getMethodExpression().getReferenceName())) {
                truncatedCalls.clear();
            } else {
                truncatedCalls.add(call);
            }
        }

        // P15（T-M15-05）：if/else 互斥分支——排序后（源码序）同一 if 语句内的调用只保留第一个分支
        // （then），避免 `if (x) { w.eq(...) } else { w.in(...) }` 合并成 AND 双条件
        Set<PsiIfStatement> seenIfStatements = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        List<PsiMethodCallExpression> dedupedCalls = new ArrayList<>();
        for (PsiMethodCallExpression call : truncatedCalls) {
            PsiIfStatement enclosingIf = PsiTreeUtil.getParentOfType(call, PsiIfStatement.class, false);
            if (enclosingIf == null || seenIfStatements.add(enclosingIf)) dedupedCalls.add(call);
        }

        List<WrapperCall> wrapperCalls = new ArrayList<>();
        for (PsiMethodCallExpression call : dedupedCalls) {
            wrapperCalls.add(ArgResolver.toWrapperCall(call, table,
                info.entityClass != null ? info.entityClass.getName() : null, resolveVariables));
        }
        WrapperChain chain = new WrapperChain(wrapperType,
            info.entityClass != null ? info.entityClass.getName() : null,
            wrapperCalls, constructorAlias, deleteConsumed, countConsumed, existsConsumed,
            updateEntityConsumed, pageLimit, pageSearchCount);
        return new LocatedWrapper(chain, table, null);
    }

    /**
     * 收集调用之后的整条链式延续（T-M15-05）：{@code wrapper.set(a).set(b)} 中 set(b) 不直接引用 wrapper 变量，
     * 由首个直接调用（set(a)）向上延伸「作为外层调用 qualifier 的延续链」后回溯收集。
     *
     * <p>仅当调用是外层调用的 qualifierExpression 时才延伸（链式延续）；
     * 实参容器（{@code service.list(wrapper.eq(a))} 的 list）不延伸，避免跨方法误收集。</p>
     */
    private static List<PsiMethodCallExpression> collectExtendingChain(PsiMethodCallExpression call) {
        PsiMethodCallExpression outermost = call;
        PsiElement cursor = call.getParent();
        while (cursor != null) {
            if (cursor instanceof PsiMethodCallExpression
                && ((PsiMethodCallExpression) cursor).getMethodExpression().getQualifierExpression() == outermost) {
                // 链式延续：外层调用，其 qualifier 是当前链尾（wrapper.set(a).set(b)）
                outermost = (PsiMethodCallExpression) cursor;
                cursor = cursor.getParent();
            } else if (cursor instanceof PsiReferenceExpression
                && ((PsiReferenceExpression) cursor).getQualifierExpression() == outermost) {
                // 引用层延续：`wrapper.lambda().eq(...)` 中 lambda() 的父是 eq 的 methodExpression 引用
                // （其 qualifier 是当前链尾）→ 延伸到引用所属的调用
                if (!(cursor.getParent() instanceof PsiMethodCallExpression)) break;
                PsiMethodCallExpression next = (PsiMethodCallExpression) cursor.getParent();
                outermost = next;
                cursor = next.getParent();
            } else {
                break;
            }
        }
        return ArgResolver.collectCallChain(outermost);
    }

    /**
     * 判断引用是否作为<b>实体更新消费</b>的 wrapper 实参（双参 update 且 wrapper 位于非首参，U07-②）。
     *
     * <p>引用是方法调用 qualifier（{@code wrapper.eq(...)} 的链调用）或位于其他上下文时返回 false。</p>
     */
    private static boolean isEntityUpdateArgument(PsiReferenceExpression ref) {
        PsiElement cursor = ref.getParent();
        while (cursor != null) {
            if (cursor instanceof PsiExpressionList) {
                if (!(cursor.getParent() instanceof PsiMethodCallExpression)) return false;
                PsiMethodCallExpression call = (PsiMethodCallExpression) cursor.getParent();
                if (!UPDATE_CONSUMER_METHODS.contains(call.getMethodExpression().getReferenceName())) return false;
                PsiExpression[] args = call.getArgumentList().getExpressions();
                // 双参、wrapper 不是首参，且首参不是 null 字面量（`update(null, wrapper)` 为 mapper 批量更新，非实体更新）
                if (args.length < 2) return false;
                if (args[0] instanceof PsiLiteralExpression && ((PsiLiteralExpression) args[0]).getValue() == null) return false;
                for (int i = 1; i < args.length; i++) {
                    if (args[i] == ref) return true;
                }
                return false;
            }
            // 引用是链调用的 qualifier / 语句 / 方法 / 类边界，不是消费实参
            if (cursor instanceof PsiMethodCallExpression || cursor instanceof PsiStatement
                || cursor instanceof PsiMethod || cursor instanceof PsiClass) return false;
            cursor = cursor.getParent();
        }
        return false;
    }

    /**
     * 判断表达式是否作为<b>实体更新消费</b>的 wrapper 实参（{@code service.update(entity, new W()...)} / 工厂内联形态，
     * U07-②，T-M15-04）：消费方法为 update、实参 &gt;= 2 且 wrapper 位于非首参实参子树内
     * （entity 在前、wrapper 在后；wrapper 可能是链表达式，用 isAncestor 判定归属）。
     */
    private static boolean isEntityUpdateConsumer(PsiElement expr) {
        PsiMethodCallExpression call = consumingCall(expr);
        if (call == null) return false;
        if (!UPDATE_CONSUMER_METHODS.contains(call.getMethodExpression().getReferenceName())) return false;
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (args.length < 2) return false;
        // `update(null, wrapper)` 第一参为 null 字面量 → mapper 批量更新，非实体更新
        if (args[0] instanceof PsiLiteralExpression && ((PsiLiteralExpression) args[0]).getValue() == null) return false;
        for (int i = 1; i < args.length; i++) {
            if (PsiTreeUtil.isAncestor(args[i], expr, false)) return true;
        }
        return false;
    }

    /** 实体更新消费方法名（`service.update(entity, wrapper)` 双参形态；updateJoin/updateJoinAndNull 为 MPJ 官方联表更新，M15 增补；
     *  T-M20-10：`saveOrUpdate(entity, wrapper)` 双参按实体字段展开 UPDATE（近似 MP 先更新语义，upsert 预览以 UPDATE 呈现））。 */
    private static final Set<String> UPDATE_CONSUMER_METHODS = Set.of("update", "updateJoin", "updateJoinAndNull", "saveOrUpdate");

    /**
     * 判断引用是否作为<b>实参</b>传给指定消费方法集合（{@code service.remove(wrapper)} / {@code service.count(wrapper)} 等）。
     *
     * <p>引用是方法调用 qualifier（{@code wrapper.eq(...)} 的链调用）或位于其他上下文时返回 false。</p>
     */
    private static boolean isConsumerArgument(PsiReferenceExpression ref, Set<String> consumerMethods) {
        PsiElement cursor = ref.getParent();
        while (cursor != null) {
            if (cursor instanceof PsiExpressionList) {
                if (!(cursor.getParent() instanceof PsiMethodCallExpression)) return false;
                PsiMethodCallExpression call = (PsiMethodCallExpression) cursor.getParent();
                return consumerMethods.contains(call.getMethodExpression().getReferenceName());
            }
            // 引用是链调用的 qualifier / 语句 / 方法 / 类边界，不是消费实参
            if (cursor instanceof PsiMethodCallExpression || cursor instanceof PsiStatement
                || cursor instanceof PsiMethod || cursor instanceof PsiClass) return false;
            cursor = cursor.getParent();
        }
        return false;
    }

    /**
     * T-M29-02：引用是否作为<b>分页消费方法</b>的实参（{@code service.page(page, wrapper)} / {@code selectPage} 等）；
     * 命中返回消费调用（{@link #parsePageLimit} 从其实参解析 Page），否则 null。判定规则同 {@link #isConsumerArgument}。
     */
    private static PsiMethodCallExpression pageConsumingCall(PsiReferenceExpression ref) {
        PsiElement cursor = ref.getParent();
        while (cursor != null) {
            if (cursor instanceof PsiExpressionList) {
                if (!(cursor.getParent() instanceof PsiMethodCallExpression)) return null;
                PsiMethodCallExpression call = (PsiMethodCallExpression) cursor.getParent();
                return PAGE_CONSUMER_METHOD_NAMES.contains(call.getMethodExpression().getReferenceName()) ? call : null;
            }
            if (cursor instanceof PsiMethodCallExpression || cursor instanceof PsiStatement
                || cursor instanceof PsiMethod || cursor instanceof PsiClass) return null;
            cursor = cursor.getParent();
        }
        return null;
    }

    /**
     * T-M29-03：解析分页消费调用的 Page 实参（{@code new Page<>(current, size)}）为 {@link LimitClause}。
     *
     * <ul>
     *  <li>Page 实参：直接 new 表达式，或可解析到 new Page 初始化表达式的变量引用（分步写法）；</li>
     *  <li>空构造 {@code new Page<>()} → 默认 current=1、size=10（对齐 MP 默认）；</li>
     *  <li>current/size 字面量 → 直接计算 offset=(current-1)*size（current&lt;=0 按 0，对齐 MP getOffet）；</li>
     *  <li>任一为运行时值 → {@code ?} 占位（offset 来源标注计算公式，面板回填后即真实偏移）；</li>
     *  <li>解析失败（Page 变量不可解析 / 非 Page 类型等）→ null（不渲染 LIMIT，维持现状）。</li>
     * </ul>
     */
    private static LimitClause parsePageLimit(PsiMethodCallExpression call) {
        PageArg arg = parsePageArg(call);
        return arg != null ? arg.limit : null;
    }

    /**
     * R12 T-PaginationSim：解析分页消费调用的 Page 实参全量（LIMIT 子句 + searchCount 标志）。
     *
     * <p>searchCount 判定（D3=A）：Page 构造器第 3/4 参的布尔字面量（MP 3.5.17 反编译实证构造器全集
     * {@code (long,long)} / {@code (long,long,long)} / {@code (long,long,boolean)} / {@code (long,long,long,boolean)}，
     * boolean 位于下标 2 或 3）；布尔字面量缺失（无此参 / 变量实参静态不可判）→ 保守 true
     * （对齐 MP {@code Page()} 默认 searchCount=true，宁多勿少）。</p>
     */
    private static PageArg parsePageArg(PsiMethodCallExpression call) {
        PsiNewExpression pageExpr = null;
        for (PsiExpression arg : call.getArgumentList().getExpressions()) {
            pageExpr = resolvePageNewExpression(arg);
            if (pageExpr != null) break;
        }
        if (pageExpr == null) return null;
        PsiExpression[] args = pageExpr.getArgumentList() != null
            ? pageExpr.getArgumentList().getExpressions() : new PsiExpression[0];
        SqlValue current = args.length > 0 ? ExpressionEvaluator.evaluate(args[0]) : null;
        if (current == null) current = new SqlValue.Literal("1");
        SqlValue size = args.length > 1 ? ExpressionEvaluator.evaluate(args[1]) : null;
        if (size == null) size = new SqlValue.Literal("10");
        SqlValue offset;
        if (current instanceof SqlValue.Literal && size instanceof SqlValue.Literal) {
            Long c = asLong(((SqlValue.Literal) current).getUnquoted());
            Long s = asLong(((SqlValue.Literal) size).getUnquoted());
            if (c != null && s != null) {
                long off = c > 0 ? (c - 1) * s : 0L;
                offset = new SqlValue.Literal(String.valueOf(off));
            } else {
                offset = pageOffsetPlaceholder(current, size);
            }
        } else {
            offset = pageOffsetPlaceholder(current, size);
        }
        boolean searchCount = true;
        for (int i = 2; i < args.length; i++) {
            if (args[i] instanceof PsiLiteralExpression) {
                Object value = ((PsiLiteralExpression) args[i]).getValue();
                if (value instanceof Boolean) {
                    searchCount = (Boolean) value;
                    break;
                }
            }
        }
        return new PageArg(new LimitClause(offset, size), searchCount);
    }

    /** Page 实参解析产物（R12 T-PaginationSim）：LIMIT 子句 + searchCount 标志（见 {@link #parsePageArg}）。 */
    private record PageArg(LimitClause limit, boolean searchCount) {
    }

    private static Long asLong(String text) {
        if (text == null) return null;
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** new Page 表达式解析：直接 new 表达式，或变量引用 → 初始化表达式（分步写法 `Page p = new Page<>(...); service.page(p, w)`）。 */
    private static PsiNewExpression resolvePageNewExpression(PsiExpression expr) {
        if (expr instanceof PsiNewExpression && isPageType((PsiNewExpression) expr)) return (PsiNewExpression) expr;
        if (expr instanceof PsiReferenceExpression) {
            PsiVariable variable = asPsiVariable(((PsiReferenceExpression) expr).resolve());
            if (variable == null) return null;
            if (!(variable.getInitializer() instanceof PsiNewExpression)) return null;
            PsiNewExpression init = (PsiNewExpression) variable.getInitializer();
            return isPageType(init) ? init : null;
        }
        return null;
    }

    /** Page 类型判定（Page / PageImpl 简单类名，不 resolve——LightPlatform 无 MP classpath 也生效）。 */
    private static boolean isPageType(PsiNewExpression newExpr) {
        String className = newExpr.getType() instanceof PsiClassType
            ? ((PsiClassType) newExpr.getType()).getClassName() : null;
        return "Page".equals(className) || "PageImpl".equals(className);
    }

    /** 分页 offset 运行时占位来源：`(current - 1) * size` 计算公式文本（对齐 MP getOffet 语义）。 */
    private static SqlValue pageOffsetPlaceholder(SqlValue current, SqlValue size) {
        String currentText = current instanceof SqlValue.Literal
            ? ((SqlValue.Literal) current).getUnquoted() : ((SqlValue.Placeholder) current).getSource();
        String sizeText = size instanceof SqlValue.Literal
            ? ((SqlValue.Literal) size).getUnquoted() : ((SqlValue.Placeholder) size).getSource();
        return new SqlValue.Placeholder("(" + currentText + " - 1) * " + sizeText);
    }

    /**
     * 提取表别名构造的别名（清单 C4/P11）：`new MPJLambdaWrapper<>(Entity.class, "apply_info")` 的第二参。
     * 非两参构造或无字符串第二参时返回 null（使用默认主表别名）。
     */
    private static String extractConstructorAlias(PsiElement initializer) {
        PsiNewExpression newExpr;
        if (initializer instanceof PsiNewExpression) {
            newExpr = (PsiNewExpression) initializer;
        } else if (initializer != null) {
            newExpr = PsiTreeUtil.findChildOfType(initializer, PsiNewExpression.class);
        } else {
            return null;
        }
        if (newExpr == null || newExpr.getArgumentList() == null) return null;
        PsiExpression[] args = newExpr.getArgumentList().getExpressions();
        if (args.length < 2) return null;
        if (!(args[1] instanceof PsiLiteralExpression)) return null;
        Object value = ((PsiLiteralExpression) args[1]).getValue();
        if (!(value instanceof String)) return null;
        String alias = (String) value;
        return alias.isEmpty() ? null : alias;
    }

    /**
     * 从构造实参第一参解析实体类（T-M20-03）：`new MPJLambdaWrapper<>(userEntity)` 菱形 + 实体变量实参
     * （无泛型实参，{@link #entityInfoFromType} 解析为 null）时，从第一参表达式类型
     * （PsiClassType → resolve → PsiClass）解析实体，供 {@link #resolveTableInfo} 走既有流程接表名。
     * `(Class)` / `(Class, alias)` 构造的第一参是 PsiClassObjectAccessExpression（类字面量，非实体实例）→
     * 返回 null；字面量实参（字符串等）同理跳过。
     */
    private static PsiClass entityClassFromConstructorArg(PsiNewExpression newExpr) {
        if (newExpr.getArgumentList() == null) return null;
        PsiExpression[] args = newExpr.getArgumentList().getExpressions();
        if (args.length == 0) return null;
        PsiExpression firstArg = args[0];
        if (firstArg instanceof PsiClassObjectAccessExpression || firstArg instanceof PsiLiteralExpression) return null;
        return firstArg.getType() instanceof PsiClassType ? ((PsiClassType) firstArg.getType()).resolve() : null;
    }

    /**
     * XB（M44）：从工厂调用实参解析实体类——扫描全部实参取首个 Class 字面量
     * （`QueryChain.of(userDao, UserEntity.class)` 的第二参，javap 实证签名）。
     * 与 {@link #entityClassFromConstructorArg} 相反：Class 字面量即目标，其余实参跳过。
     */
    private static PsiClass entityClassFromFactoryArgs(PsiMethodCallExpression call) {
        for (PsiExpression arg : call.getArgumentList().getExpressions()) {
            if (arg instanceof PsiClassObjectAccessExpression classArg
                    && classArg.getOperand() != null
                    && classArg.getOperand().getType() instanceof PsiClassType classType) {
                PsiClass resolved = classType.resolve();
                if (resolved != null) return resolved;
            }
        }
        return null;
    }

    /**
     * XB（M44）：xbatis 链家族语义分派——qualifier 类名 → "delete"（DeleteChain 条件链转 DELETE）；
     * QueryChain/UpdateChain 返回 null（SELECT 由 XbatisConverter 默认、UPDATE 由 set 家族调用判定）。
     */
    private static String xbatisChainFamilyOf(PsiMethodCallExpression call) {
        PsiExpression qualifierExpr = call.getMethodExpression().getQualifierExpression();
        String qualifierName = qualifierExpr instanceof PsiReferenceExpression
                ? ((PsiReferenceExpression) qualifierExpr).getReferenceName() : null;
        if (XBATIS_DELETE_CHAIN.equals(qualifierName) || "UpdateChain".equals(qualifierName)) {
            return qualifierName;
        }
        return null;
    }

    /**
     * XB（M44）：xbatis 链实体表解析。实体类标注 xbatis @Table → JpaEntityResolver
     * （@Table value / @TableField / @TableId / @Ignore 轨）；MP 兼容注解（@TableName）实体
     * → 既有 MP EntityResolver。实体类缺失时类型参数类名按命名策略兜底（与 resolveTableInfo 一致）。
     *
     * @param project 调用方 PSI 元素所属项目（T-TS-07）；null = 未提供，兜底推断沿用
     *                「首个打开项目」的既有语义（纯单测/无 PSI 上下文）
     */
    private static TableInfo resolveTableInfoForXbatis(PsiClass entityClass, String typeArgName, Project project) {
        // PsiTypeParameter 守卫：变量声明 `QueryChain<T>` 等未推断泛型场景下实体解析到类型形参，
        // 不可作实体（推断表名 "t"、列清单空——宁缺不可错）
        if (entityClass == null || entityClass instanceof com.intellij.psi.PsiTypeParameter) {
            String name = typeArgName;
            if (name == null || (entityClass != null && name.equals(entityClass.getName()))) return null;
            return new TableInfo(name, EntityResolver.inferDecoratedTableName(name, project),
                    List.of());
        }
        if (cn.song.mybatisplus.visualizer.psi.jpa.JpaMetadataReader.findFlex(entityClass,
                cn.song.mybatisplus.visualizer.psi.jpa.JpaMetadataReader.FQN_XBATIS_TABLE) != null) {
            return new cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver().resolve(entityClass);
        }
        return new EntityResolver().resolve(entityClass);
    }

    /** Kotlin 默认参数等价重载：{@code resolveTableInfoForXbatis(entityClass, typeArgName)}（project = null）。 */
    private static TableInfo resolveTableInfoForXbatis(PsiClass entityClass, String typeArgName) {
        return resolveTableInfoForXbatis(entityClass, typeArgName, null);
    }

    // ------------------------------------------------------------------
    // 跨方法工厂链（清单 P03/P16）：baseJoinWrapper() 返回 wrapper，调用方继续续链
    // ------------------------------------------------------------------

    /**
     * 展开工厂方法调用：当初始化链首元素是「非构造的独立方法调用」（如 `baseJoinWrapper()`）时，
     * 通过 {@link PsiMethodCallExpression#resolveMethod} 找到方法定义，解析其 `return` 表达式中的 wrapper 链，
     * 返回「工厂内部链 + 续链」的完整调用列表。
     *
     * <p>非工厂调用（new 构造链）或解析失败时原样返回。</p>
     */
    private static List<PsiMethodCallExpression> expandFactoryCalls(List<PsiMethodCallExpression> calls) {
        if (calls.isEmpty()) return calls;
        PsiMethodCallExpression first = calls.get(0);
        PsiExpression qualifier = first.getMethodExpression().getQualifierExpression();
        boolean isFactoryCall = qualifier == null
            && first.getMethodExpression().getReferenceName() != null
            && !(first instanceof PsiNewExpression);
        if (!isFactoryCall) return calls;

        PsiMethod method = first.resolveMethod();
        if (method == null) return calls;
        List<PsiMethodCallExpression> factoryChain = findFactoryReturnChain(method);
        if (factoryChain.isEmpty()) return calls;
        // P03 3 级链（T-M15-05）：工厂方法 return 链首的 qualifier 是**方法参数**
        // （`applyWithdrawFilters(baseWithdrawQueryWrapper(), dto)` 中 return `wrapper.like(...)`）时，
        // 把调用点对应实参（baseWithdrawQueryWrapper()）的初始链递归展开并前置
        List<PsiMethodCallExpression> argumentChain = resolveFactoryArgumentChain(first, method);
        List<PsiMethodCallExpression> result = new ArrayList<>(argumentChain);
        result.addAll(factoryChain);
        result.addAll(calls.size() > 1 ? calls.subList(1, calls.size()) : java.util.List.of());
        return result;
    }

    /**
     * 解析工厂方法「参数 wrapper 实参」的初始链（P03 3 级链，T-M15-05）：
     * `applyWithdrawFilters(baseWithdrawQueryWrapper(), dto)` 的 return 链首 `wrapper.like(...)` 的 qualifier
     * 是方法参数 wrapper —— 取调用点对应位置的实参（baseWithdrawQueryWrapper()），
     * 若实参本身是工厂调用则递归展开其 return 链（参数链前置到方法内部链之前）。
     */
    private static List<PsiMethodCallExpression> resolveFactoryArgumentChain(PsiMethodCallExpression call, PsiMethod method) {
        List<PsiMethodCallExpression> returnChain = findFactoryReturnChain(method);
        if (returnChain.isEmpty()) return List.of();
        PsiExpression headQualifier = returnChain.get(0).getMethodExpression().getQualifierExpression();
        if (!(headQualifier instanceof PsiReferenceExpression)) return List.of();
        com.intellij.psi.PsiElement resolved = ((PsiReferenceExpression) headQualifier).resolve();
        if (!(resolved instanceof PsiParameter)) return List.of();
        PsiParameter param = (PsiParameter) resolved;
        if (!(param.getParent() instanceof PsiParameterList)) return List.of();
        PsiParameterList paramList = (PsiParameterList) param.getParent();
        if (paramList.getParent() != method.getParameterList().getParent()) return List.of();
        PsiExpression[] args = call.getArgumentList().getExpressions();
        int paramIndex = paramList.getParameterIndex(param);
        if (paramIndex < 0 || paramIndex >= args.length) return List.of();
        if (!(args[paramIndex] instanceof PsiMethodCallExpression)) return List.of();
        PsiMethodCallExpression argCall = (PsiMethodCallExpression) args[paramIndex];
        // 递归展开实参工厂；过滤掉工厂调用自身（其 return 链并入）
        List<PsiMethodCallExpression> expanded = expandFactoryCalls(List.of(argCall));
        List<PsiMethodCallExpression> result = new ArrayList<>();
        for (PsiMethodCallExpression c : expanded) {
            if (c != argCall) result.add(c);
        }
        return result;
    }

    /**
     * 解析工厂方法体 `return` 表达式中的 wrapper 构造链。
     * 如 `return new MPJLambdaWrapper<>().selectAll(...).leftJoin(...)` → [new, selectAll, leftJoin]。
     */
    private static List<PsiMethodCallExpression> findFactoryReturnChain(PsiMethod method) {
        if (method.getBody() == null) return List.of();
        PsiReturnStatement returnStatement =
            PsiTreeUtil.findChildOfType(method.getBody(), PsiReturnStatement.class);
        if (returnStatement == null) return List.of();
        PsiExpression expr = PsiTreeUtil.getChildOfType(returnStatement, PsiExpression.class);
        if (expr == null) return List.of();
        return ArgResolver.collectChainFromInitializer(expr);
    }

    // ------------------------------------------------------------------
    // 类型识别
    // ------------------------------------------------------------------

    /** Wrappers 静态工厂方法名（`Wrappers.<T>lambdaQuery()` 等）。M10 起供 {@link WrapperAnchorDetector} 复用（原 Kotlin internal）。
     *  P1-05（M19.5）：ChainWrappers 工厂方法（queryChain/lambdaQueryChain 等）一并收录。
     *  T-M20-02：MPJ 官方工厂 JoinWrappers（lambda/delete；query/update 已在集合中，映射按 qualifier 区分）
     *  与旧工厂 MPJWrappers（lambdaJoin/queryJoin）方法名一并收录。 */
    public static final Set<String> WRAPPER_FACTORY_METHOD_NAMES = Set.of(
        "lambdaQuery", "lambdaUpdate", "lambdaDelete", "query", "update",
        "queryChain", "lambdaQueryChain", "lambdaUpdateChain", "lambdaDeleteChain", "updateChain", "deleteChain",
        "lambda", "delete", "lambdaJoin", "queryJoin"
    );

    /**
     * Wrappers / ChainWrappers 静态工厂类名（P1-05，M19.5）；
     * T-M20-02：MPJ 官方工厂 JoinWrappers（com.github.yulichang.toolkit）与旧工厂 MPJWrappers 一并收录。
     * T-M22-04：Db kit（com.baomidou.mybatisplus.extension.toolkit.Db 静态工具）——
     * `Db.lambdaQuery(User.class)...` 与 Wrappers 同映射（lambdaQuery → LAMBDA_QUERY，
     * factoryWrapperType 的 qualifier 分支恒映射；实体从链内方法引用推断）。
     * M10 起供 {@link WrapperAnchorDetector} 复用（gutter 锚点快速路径文本匹配，原 Kotlin internal）。
     */
    public static final Set<String> WRAPPER_FACTORY_CLASS_NAMES =
        Set.of("Wrappers", "ChainWrappers", "JoinWrappers", "MPJWrappers", "Db");

    /**
     * 服务链起点方法名（`service.lambdaQuery()` / `service.lambdaUpdate()`，T-M15-01；
     * P1-03 扩展 `service.query()` / `service.update()` 字符串链——歧义方法经 {@link #isServiceChainStart} 守卫）。
     * 与 {@link #WRAPPER_FACTORY_METHOD_NAMES} 不同：只放行这些方法（`service.update(wrapper)` 等消费调用
     * 不在此列，避免误判为链起点）。
     */
    public static final Set<String> SERVICE_CHAIN_METHOD_NAMES = Set.of("lambdaQuery", "lambdaUpdate", "query", "update");

    /**
     * 链式 wrapper 终结点（消费方法，非 Wrapper 操作，T-M15-01）：
     * `service.lambdaQuery()...one()/.list()/.count()/.exists()/.page()` 与
     * `service.lambdaUpdate()...update()/.remove()`。
     * 官方 API 补充（M15-P0）：MPJ Chain 的 `first`（取首条）与 `mapOne/mapFirst/mapList/mapPage`（Map 结果）。
     * T-M20-06：ChainQuery（MP 3.5.4+）的 `getObj()`（取单条字段值）补注册为终结点——
     * 消费方法全集核对发现缺口（此前按普通链调用入链，被解析器静默忽略）。
     * T-M21-08：UpdateJoin 链终结点 `updateAndNull()` / `updateAndNull(T)`（MPJ 1.4.10+，
     * 含 null 字段一并 set 的联表更新）——与 `update`/`update(T)` 同列剔除出链。
     */
    private static final Set<String> CHAIN_TERMINAL_METHOD_NAMES = Set.of(
        "one", "oneOpt", "list", "count", "exists", "page",
        "getOne", "getMap", "listMaps", "listObjs", "getObj", "map",
        "first", "mapOne", "mapFirst", "mapList", "mapPage",
        "update", "updateAndNull", "remove",
        // XB（M44）：xbatis 链终结点（javap QueryChain/UpdateChain/DeleteChain 实证）——
        // get/getOptional/list/cursor/count/exists/paging（查询）、execute（更新/删除）。
        // 仅在已识别的 wrapper 链内参与终结判定，普通 Stream/map 调用不受影响
        "get", "getOptional", "paging", "cursor", "execute"
    );

    private static final String REMOVE_TERMINAL = "remove";
    private static final String COUNT_TERMINAL = "count";
    private static final String EXISTS_TERMINAL = "exists";
    private static final String UPDATE_TERMINAL = "update";
    /** T-M29-02：链式分页终结点（`service.lambdaQuery()...page(Page)`）。 */
    private static final String PAGE_TERMINAL = "page";
    private static final String CLEAR_METHOD = "clear";

    /**
     * exists 双语义守卫（T-ExistsChainTerminal）：`exists` 在 MP 里同时是**条件方法**与**链终结点**——
     * `AbstractWrapper.exists(boolean, String, Object...)` 是条件（Q14-①，javap 实证 3.5.7/3.5.15/3.5.17 均只有带参形态），
     * `ChainQuery.exists()` 无参才是终结点（Q14-③）。二者落在链尾时结构完全同形，**只能按实参个数区分**。
     *
     * <p>带参形态被当终结点会同时错两处（裸工厂链直接作 service 实参、链内无真终结点时暴露）：
     * ①该调用被剔除 → `EXISTS (...)` 条件整条丢失；②`existsConsumed` 置位 → 整链被误包成 `SELECT EXISTS (...)`。</p>
     *
     * <p>单一来源：终结点筛选（{@link #buildChainFromFactory}）与 wrapper 变量接受门
     * （{@link #initializerIsWrapperChainWithoutTerminal}）共用，避免同一语义两处各判一遍。</p>
     */
    private static boolean isExistsConditionCall(PsiMethodCallExpression call) {
        return EXISTS_TERMINAL.equals(call.getMethodExpression().getReferenceName())
            && call.getArgumentList().getExpressions().length > 0;
    }

    /** 删除类消费方法：wrapper 作为实参传入时，即使类型为查询类也应转 DELETE（MP 惯用删除写法；
     *  deleteJoin 为 MPJ 官方联表删除，M15 增补）。原 Kotlin internal `deleteConsumerMethodNames`。 */
    public static final Set<String> DELETE_CONSUMER_METHOD_NAMES = Set.of("remove", "delete", "deleteJoin");

    /**
     * T-M29-02：分页类消费方法——wrapper 作为实参传入时查询转分页渲染（LIMIT 子句）：
     * `IService.page(Page, wrapper)` / `BaseMapper.selectPage(Page, wrapper)` / MPJ `selectJoinPage` /
     * `Db.page(Page, wrapper)`（链式 `lambdaQuery()...page(...)` 终结点由 {@link #PAGE_TERMINAL} 单独识别）。
     *
     * <p>R12 T-PaginationSim：补注册 BaseMapper 3.5.17 的 IPage 重载族（本地 jar 反编译实证全集 =
     * selectList(IPage,Wrapper) / selectMaps(IPage,Wrapper) / selectPage / selectMapsPage，
     * <b>无 selectObjs(IPage,...) 重载故不收</b>）——`mapper.selectList(page, w)` 此前按无分页
     * SELECT 渲染（LIMIT 缺失）。原 Kotlin internal `pageConsumerMethodNames`。</p>
     */
    public static final Set<String> PAGE_CONSUMER_METHOD_NAMES =
        Set.of("page", "selectPage", "selectJoinPage", "selectJoinMapsPage", "selectList", "selectMaps", "selectMapsPage");

    /**
     * 计数类消费方法：wrapper 作为实参传入时，查询类 wrapper 转 `SELECT COUNT(*) FROM ...`。
     * T-M20-06：BaseMapper.selectCount(wrapper) 补注册（消费方法全集核对缺口——
     * 此前 selectCount 消费被默认 SELECT 分支吞掉，SQL 形态错误）。
     * 原 Kotlin internal `countConsumerMethodNames`。
     */
    public static final Set<String> COUNT_CONSUMER_METHOD_NAMES = Set.of("count", "selectJoinCount", "selectCount");

    /** exists 消费方法（Q14-②，T-M15-03）：`service.exists(wrapper)` / `mapper.exists(wrapper)` → `SELECT EXISTS (...)`。 */
    private static final String EXISTS_CONSUMER = "exists";

    /**
     * 识别 PsiType 对应的 Wrapper 类型（按简单类名匹配）。
     *
     * <p>注意：不能用 {@code canonicalText.substringAfterLast('.')} 提取类名——当泛型实参带包名时
     * （真实项目常态，如 {@code LambdaQueryWrapper<com.biz.BikeDeviceProductInfoEntity>}），
     * 最后一个 {@code .} 会落在泛型参数内导致提取错误（回归：M10 gutter 图标 + WrapperLocator 定位）。
     * {@code PsiClassType.getClassName()} 在第一个 {@code <} 之前取末段，语义正确且无需 resolve（快速路径安全）。</p>
     *
     * <p>M10 起供 {@link WrapperAnchorDetector} 复用（gutter 锚点判定，快速路径
     * 只做 canonicalText 文本匹配，不 resolve，原 Kotlin internal），保证 Wrapper 类名集合单一来源。</p>
     */
    public static WrapperType detectWrapperType(PsiType type) {
        if (!(type instanceof PsiClassType)) return null;
        String simpleName = ((PsiClassType) type).getClassName();
        if (simpleName == null) return null;
        // XB（M44）：双坐标 FQN 前缀识别（cn.xbatis. / cn.mybatis-mp.）——QueryChain/UpdateChain/
        // DeleteChain/InsertChain 变量声明；防与 MP QueryWrapper 撞类名（canonicalText 文本匹配，不 resolve）。
        // 前缀命中后须叠加链类简单名白名单：用户工程 cn.xbatis.* 包下的任意类（实体/DAO/枚举等）
        // 均命中前缀，若仅凭前缀会把「like(LikeMode.LEFT, ...) 实参解析到的字段变量」等非链类型
        // 误判为链变量（实证：字段类型 cn.xbatis.core.LikeMode → 变量路径抢跑 → table=null）
        if (isXbatisType(type) && XBATIS_FACTORY_CLASS_NAMES.contains(simpleName)) return WrapperType.XBATIS;
        switch (simpleName) {
            case "LambdaQueryWrapper": return WrapperType.LAMBDA_QUERY;
            case "LambdaUpdateWrapper": return WrapperType.LAMBDA_UPDATE;
            case "LambdaDeleteWrapper": return WrapperType.LAMBDA_DELETE;
            case "MPJLambdaWrapper": return WrapperType.MPJ_LAMBDA;
            case "MPJQueryWrapper": return WrapperType.MPJ_QUERY;
            case "MPJLambdaQueryWrapper": return WrapperType.MPJ_LAMBDA_QUERY;
            case "QueryWrapper": return WrapperType.QUERY;
            case "UpdateWrapper": return WrapperType.UPDATE;
            // T-M21-08：MPJ 官方联表更新/删除 wrapper（1.4.5+，UpdateJoinWrapper / DeleteJoinWrapper）
            case "UpdateJoinWrapper": return WrapperType.UPDATE_JOIN;
            case "DeleteJoinWrapper": return WrapperType.DELETE_JOIN;
            // 链式 wrapper 显式声明（`LambdaQueryChainWrapper<X> w = lambdaQuery()...`，Q17，T-M15-01）
            case "LambdaQueryChainWrapper": return WrapperType.LAMBDA_QUERY;
            case "LambdaUpdateChainWrapper": return WrapperType.LAMBDA_UPDATE;
            default: return null;
        }
    }

    /**
     * 从 Wrapper 泛型参数解析实体类：`LambdaQueryWrapper<DeviceInfoEntity>` → PsiClass。
     */
    private static PsiClass resolveEntityClass(PsiType type) {
        if (!(type instanceof PsiClassType)) return null;
        PsiClassType classType = (PsiClassType) type;
        PsiType[] parameters = classType.getParameters();
        if (parameters.length == 0) return null;
        if (!(parameters[0] instanceof PsiClassType)) return null;
        // PsiTypeParameter 守卫（XB/M44）：LightPlatform/stub 下 `QueryChain<T>` 的泛型参数可能
        // 仍为类型形参 T（未被推断替换），resolve() 返回 PsiTypeParameter（PsiClass 子类）——
        // 若当实体解析，表名推断成 "t"、列清单为空（宁可不识别不可出错误 SQL）
        PsiClass resolved = ((PsiClassType) parameters[0]).resolve();
        return resolved instanceof com.intellij.psi.PsiTypeParameter ? null : resolved;
    }

    /**
     * 从 Wrapper 类型解析 (实体类, 类型参数类名)。
     *
     * <p>实体类可能不可解析（如粘贴片段无实体类定义）：此时类型参数类名仍可获取，
     * 供 {@link #resolveTableInfo} 按命名策略兜底推断表名。</p>
     */
    private static EntityInfo entityInfoFromType(PsiType type) {
        if (!(type instanceof PsiClassType)) return new EntityInfo(null, null);
        PsiClassType param = null;
        PsiType[] parameters = ((PsiClassType) type).getParameters();
        if (parameters.length > 0 && parameters[0] instanceof PsiClassType) {
            param = (PsiClassType) parameters[0];
        }
        if (param == null) return new EntityInfo(null, null);
        return new EntityInfo(param.resolve(), param.getClassName());
    }

    /** (实体类, 类型参数类名) 二元组（替代 Kotlin 解构）。 */
    private static final class EntityInfo {
        final PsiClass entityClass;
        final String typeArgName;

        EntityInfo(PsiClass entityClass, String typeArgName) {
            this.entityClass = entityClass;
            this.typeArgName = typeArgName;
        }
    }

    /**
     * O-8（缺陷 A）：从 initializer 链头解析 (实体类, 类型参数类名)——var/推断形态变量声明类型
     * 无泛型实参可取。链头是 wrapper 构造（{@code new QueryWrapper<Device>()}）时取构造类型泛型；
     * 链头是工厂调用（{@code Wrappers.<Device>query()}）时取显式类型实参——链首候选为链上<b>最后一次调用</b>
     * （qualifier 回溯终点是引用，工厂调用自身即链首，同 {@link #wrapperTypeFromInitializerChain}），
     * 解析顺位与 {@link #buildChainFromFactory} 同源（显式类型实参 → Class 字面量实参 → 调用返回类型）。
     */
    private static EntityInfo entityInfoFromInitializerChain(PsiElement init) {
        if (init == null) return new EntityInfo(null, null);
        PsiElement expr = init;
        PsiMethodCallExpression lastCall = null;
        while (expr instanceof PsiMethodCallExpression) {
            lastCall = (PsiMethodCallExpression) expr;
            expr = lastCall.getMethodExpression().getQualifierExpression();
        }
        if (expr instanceof PsiNewExpression) {
            return entityInfoFromType(((PsiNewExpression) expr).getType());
        }
        if (lastCall != null) {
            PsiType[] typeArguments = lastCall.getTypeArguments();
            PsiClassType typeArg = typeArguments.length > 0 && typeArguments[0] instanceof PsiClassType
                ? (PsiClassType) typeArguments[0] : null;
            PsiClass resolvedTypeArg = typeArg != null ? typeArg.resolve() : null;
            PsiClass entityClass;
            if (resolvedTypeArg != null && !(resolvedTypeArg instanceof com.intellij.psi.PsiTypeParameter)) {
                entityClass = resolvedTypeArg;
            } else {
                entityClass = entityClassFromFactoryArgs(lastCall);
                if (entityClass == null) {
                    entityClass = resolveEntityClass(lastCall.getType());
                }
            }
            return new EntityInfo(entityClass, typeArg != null ? typeArg.getClassName() : null);
        }
        return new EntityInfo(null, null);
    }

    /**
     * 解析实体表信息；实体类不可解析时，用类型参数类名按命名策略推断表名
     * （列名由 getter → 命名策略兜底，见 {@link ArgResolver} 的 lambda 列解析）。
     * T-M19.4：兜底推断走统一入口（策略 + 修饰链，全局语义，与 {@link EntityResolver#resolve} 一致）。
     *
     * @param project 调用方 PSI 元素所属项目（T-TS-07）；null = 未提供，兜底推断沿用
     *                「首个打开项目」的既有语义（纯单测/无 PSI 上下文）
     */
    private static TableInfo resolveTableInfo(PsiClass entityClass, String typeArgName, Project project) {
        if (entityClass != null) return new EntityResolver().resolve(entityClass);
        if (typeArgName == null) return null;
        return new TableInfo(typeArgName, EntityResolver.inferDecoratedTableName(typeArgName, project),
            List.of(), null, null, null, List.of());
    }

    /** Kotlin 默认参数等价重载：{@code resolveTableInfo(entityClass, typeArgName)}（project = null）。 */
    private static TableInfo resolveTableInfo(PsiClass entityClass, String typeArgName) {
        return resolveTableInfo(entityClass, typeArgName, null);
    }
}
