// T-SyncK2JMaster183 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/MbgExampleCallResolver.kt 移植
// （Kotlin object 翻译：成员全静态——缓存/在途/失败记忆均为静态字段，调用方以对象成员形态调用，静态承载可互操作。）
package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.util.FatalErrors;
import cn.song.mybatisplus.visualizer.xml.MbgCriterionMock;
import cn.song.mybatisplus.visualizer.xml.MockObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import kotlin.jvm.functions.Function0;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * MBG Example 调用点解析（T-MbgExamplePreview M2）。
 *
 * <p>MBG 生成器的 Example 用法固定为
 * {@code example.createCriteria().andCustIdEqualTo(v)} + {@code mapper.selectByExample(example)}，
 * 而 {@code andXxxYyy} 的方法体固定是一句 {@code addCriterion("cust_id =", value, "custId")}。
 * 所以反推 Criterion 不需要猜列名：从 mapper 方法的调用点拿到 example 变量，
 * 在同一方法体里找 {@code and*} / {@code or*} 调用，再读对应方法的 {@code addCriterion} 第一个实参常量即可。</p>
 *
 * <p>产出直接喂给 {@code cn.song.mybatisplus.visualizer.xml.XmlPreviewModel.from} 的
 * {@code collectionElements}：</p>
 * <ul>
 *   <li>{@code oredCriteria} → 一个 Criteria 元素（{@code valid=true} + {@code criteria} 列表）</li>
 *   <li>{@code criteria.criteria} → 上述 Criterion 元素</li>
 * </ul>
 *
 * <p>全部失败路径返回 null（无 project / 接口不可解析 / 找不到调用点 / 方法体没有 addCriterion 常量），
 * 上游据此退回升级前的「无属性元素」行为——不报错、不改变既有预览。</p>
 */
public final class MbgExampleCallResolver {

    private MbgExampleCallResolver() {
    }

    private static final Logger LOG = Logger.getInstance(MbgExampleCallResolver.class);

    /** MBG Criteria 的方法名：{@code and} / {@code or} + 大写驼峰首字母。 */
    private static final Pattern AND_OR_METHOD = Pattern.compile("^(and|or)[A-Z]\\w+$");

    /** 单次反推的 Criterion 上限（防御性：方法体里可能有大量链式调用）。 */
    private static final int MAX_CRITERIA = 20;

    /**
     * 缓存条目（T-PreviewOpenPerfM2 M1）：{@code ReferencesSearch} 是<b>全项目索引检索</b>，实测在最小规模
     * demo 上就占装配耗时的 78%（{@code [open-perf] m2=207} / {@code total=266ms}），大工程按命中规模放大；
     * 而原实现每次 {@code preview()} 都重跑。key＝{@code namespace#statementId}，失效判据＝解析时实际读过的
     * 文件的 {@code modificationStamp}（mapper 接口 / Example 方法 / 调用点所在文件，任一改动即失效）。
     */
    public record CachedResolution(
        @NotNull Map<String, List<MockObject>> elements,
        @NotNull Map<VirtualFile, Long> stamps
    ) {
    }

    private static final ConcurrentHashMap<String, CachedResolution> CACHE = new ConcurrentHashMap<>();

    /** 预取在途集合（同一 key 只跑一个后台任务）。 */
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    /** 预取失败记忆（本会话不再重试；预览路径不受影响）。 */
    private static final Set<String> PREFETCH_FAILED = ConcurrentHashMap.newKeySet();

    /** 诊断/测试缝：缓存命中次数与真正执行检索的次数（单线程测试用，生产仅观测）。 */
    private static int cacheHits = 0;

    private static int searchRuns = 0;

    public static int getCacheHits() {
        return cacheHits;
    }

    public static int getSearchRuns() {
        return searchRuns;
    }

    /** 测试缝：清空缓存与计数。 */
    public static void resetCacheForTest() {
        CACHE.clear();
        cacheHits = 0;
        searchRuns = 0;
    }

    @Nullable
    public static Map<String, List<MockObject>> resolve(@Nullable Project project,
                                                        @Nullable String namespace,
                                                        @NotNull String statementId) {
        if (project == null || namespace == null || namespace.isBlank()) {
            return null;
        }
        // key 带 project 身份：本类是进程级，多项目窗口打开时同 `namespace#statementId`
        // 会撞车（不同项目可以有同名 mapper）
        String key = project.getLocationHash() + "#" + namespace + "#" + statementId;
        try {
            // master 侧 runCatching 降级 → try/catch 等义（禁白名单）
            return ReadAction.compute(() -> resolveCached(key, () -> resolveInReadAction(project, namespace, statementId)));
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 缓存外壳（<b>纯逻辑</b>，可单测）：命中且依赖文件未变则直接返回；否则执行 {@code compute} 并落缓存。
     *
     * <p>{@code compute} 返回 null 表示「解析不到」——<b>不写缓存</b>：把「当时索引没就绪 / 找不到调用点」
     * 永久固化下来会让下次打开永远拿不到（宁可比预期多跑一次）。</p>
     */
    @Nullable
    public static Map<String, List<MockObject>> resolveCached(@NotNull String key,
                                                              @NotNull Function0<CachedResolution> compute) {
        CachedResolution cached = CACHE.get(key);
        if (cached != null) {
            if (isFresh(cached)) {
                cacheHits++;
                LOG.info("[m2] cache hit " + describe(cached) + " " + key);
                return cached.elements();
            }
            LOG.info("[m2] cache stale (依赖文件已改) " + key);
        }
        searchRuns++;
        CachedResolution resolved = compute.invoke();
        if (resolved == null) {
            // 不写缓存：把「当时索引没就绪 / 找不到调用点」永久固化会让下次打开永远拿不到
            LOG.info("[m2] unresolved（不写缓存） " + key);
            return null;
        }
        CACHE.put(key, resolved);
        LOG.info("[m2] resolved " + describe(resolved) + " " + key);
        return resolved.elements();
    }

    @NotNull
    private static String describe(@NotNull CachedResolution entry) {
        List<MockObject> criteria = entry.elements().get("criteria.criteria");
        return "criteria=" + (criteria != null ? criteria.size() : 0) + " stamps=" + entry.stamps().size();
    }

    /**
     * 预取（T-PreviewOpenPerfM2 M11）：把 M2 检索挪到「用户还在看文件」的时候。
     *
     * <p>真机首开 {@code [open-perf] … m2=1628/2042ms}、{@code request→dialog 2714/4580ms}，而第二次起 m2≈0、
     * 弹窗 200ms 级——首开慢的全部就是这一次冷检索。gutter 渲染本来就要遍历 mapper 语句，
     * 顺手在后台跑一次即可让「点开」命中缓存；不改变任何交互，失败静默。</p>
     *
     * <p>三条护栏（真机 2026-11-12 14:05 实测教训）：</p>
     * <ul>
     *   <li><b>失败记忆</b>：解析不到（{@code countByExample} / {@code deleteByExample} 这类在本项目里根本没有调用点的
     *       语句就是常态）不写缓存，而 gutter 会反复重绘 —— 不记失败就会变成每次重绘都重跑一次全项目
     *       检索（实测 6 秒内同一语句跑了 4 轮、日志刷屏）。记下 key 后本会话不再预取；
     *       预览路径仍照常现算，不受影响。</li>
     *   <li><b>单条在途</b>：预取是尽力而为，已有任务在跑就不再排（避免打开文件瞬间并发多条冷检索）。</li>
     *   <li>缓存新鲜则直接返回；单元测试模式不触发（避免后台线程干扰断言）。</li>
     * </ul>
     */
    public static void prefetch(@Nullable Project project, @Nullable String namespace, @Nullable String statementId) {
        if (project == null || namespace == null || namespace.isBlank() || statementId == null) {
            return;
        }
        if (ApplicationManager.getApplication().isUnitTestMode()) {
            return;
        }
        String key = project.getLocationHash() + "#" + namespace + "#" + statementId;
        if (PREFETCH_FAILED.contains(key)) {
            return;
        }
        CachedResolution cached = CACHE.get(key);
        if (cached != null && isFresh(cached)) {
            return;
        }
        if (!IN_FLIGHT.isEmpty()) {
            return;
        }
        if (!IN_FLIGHT.add(key)) {
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                if (resolve(project, namespace, statementId) == null) {
                    PREFETCH_FAILED.add(key);
                }
            } catch (Throwable t) {
                PREFETCH_FAILED.add(key);
                if (FatalErrors.isFatal(t)) {
                    throw t;
                }
            } finally {
                IN_FLIGHT.remove(key);
            }
        });
    }

    /** 依赖文件全部仍有效且 stamp 未变才算新鲜（不引文件监听，按需比 stamp）。 */
    private static boolean isFresh(@NotNull CachedResolution entry) {
        for (Map.Entry<VirtualFile, Long> e : entry.stamps().entrySet()) {
            VirtualFile file = e.getKey();
            if (!file.isValid() || file.getModificationStamp() != e.getValue()) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private static CachedResolution resolveInReadAction(@NotNull Project project,
                                                        @NotNull String namespace,
                                                        @NotNull String statementId) {
        StringBuilder trace = new StringBuilder();
        PsiClass mapperClass = JavaPsiFacade.getInstance(project)
            .findClass(namespace, GlobalSearchScope.allScope(project));
        if (mapperClass == null) {
            LOG.info("[m2] trace " + namespace + "#" + statementId + " mapperClass=NOT_FOUND");
            return null;
        }
        Map<VirtualFile, Long> stamps = new LinkedHashMap<>();
        // 局部函数 note(file)：file?.virtualFile?.takeIf { it.isValid }?.let { stamps[it] = it.modificationStamp }
        Consumer<PsiFile> note = file -> {
            if (file != null && file.getVirtualFile() != null && file.getVirtualFile().isValid()) {
                VirtualFile vf = file.getVirtualFile();
                stamps.put(vf, vf.getModificationStamp());
            }
        };
        note.accept(mapperClass.getContainingFile());
        // T-PreviewOpenPerfM2 M10：检索范围收到「mapper 所在模块及其依赖它的模块」。
        // 引用方必须能看见该接口，所以这个范围在语义上不丢命中，却比全项目小得多——
        // 真机首次打开 m2=1628ms（253 处引用、冷索引），全项目作用域是大头。
        Module module = null;
        try {
            module = ModuleUtilCore.findModuleForPsiElement(mapperClass);
        } catch (Throwable ignored) {
            // runCatching 等义：取不到模块就落全项目作用域
        }
        GlobalSearchScope scope = module != null
            ? GlobalSearchScope.moduleWithDependentsScope(module)
            : GlobalSearchScope.projectScope(project);
        trace.append("scope=").append(module != null ? module.getName() : "PROJECT").append(' ');
        // D7（真机 2026-11-12 15:00）：`oredCriteria` 必须按每条 Criteria分组，不能拍平成一条。
        // `example.createCriteria().andA()` 与 `example.or().andB()` 在 MBG 里是两条 Criteria，
        // `Example_Where_Clause` 用 `separator="or"` 连接——拍平就把 `( a ) or ( b )` 错拼成 `a and b`。
        LinkedHashMap<Object, List<MbgCriterionMock>> groups = new LinkedHashMap<>();
        PsiMethod[] methods = mapperClass.findMethodsByName(statementId, true);
        trace.append("methods=").append(methods.length).append(' ');
        for (PsiMethod method : methods) {
            note.accept(method.getContainingFile());
            PsiMethodCallExpression call = findCallSite(scope, method);
            if (call == null) {
                trace.append("noCallSite ");
                continue;
            }
            note.accept(call.getContainingFile());
            String exampleName = exampleVariableName(call);
            if (exampleName == null) {
                PsiExpression[] args = call.getArgumentList().getExpressions();
                String argText = args.length > 0 ? truncate(args[0].getText(), 40) : null;
                trace.append("noExampleArg(arg=").append(argText).append(") ");
                continue;
            }
            PsiCodeBlock body = PsiTreeUtil.getParentOfType(call, PsiCodeBlock.class);
            if (body == null) {
                trace.append("noBody ");
                continue;
            }
            Map<Object, List<MbgCriterionMock>> found = collectCriteria(project, body, exampleName, trace);
            int conditionCount = 0;
            for (List<MbgCriterionMock> list : found.values()) {
                conditionCount += list.size();
            }
            trace.append('[').append(exampleName).append(':').append(conditionCount).append('/').append(found.size()).append("] ");
            for (Map.Entry<Object, List<MbgCriterionMock>> entry : found.entrySet()) {
                groups.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
            }
        }
        List<MbgCriterionMock> flat = new ArrayList<>();
        for (List<MbgCriterionMock> list : groups.values()) {
            flat.addAll(list);
        }
        LOG.info("[m2] trace " + namespace + "#" + statementId + " " + trace + " -> criteria=" + flat.size() + " groups=" + groups.size());
        if (flat.isEmpty()) {
            return null;
        }
        List<MockObject> oredCriteria = new ArrayList<>();
        for (List<MbgCriterionMock> group : groups.values()) {
            oredCriteria.add(MbgCriterionMock.criteriaOf(group));
        }
        List<MockObject> criteriaFlat = new ArrayList<>();
        for (MbgCriterionMock criterion : flat) {
            criteriaFlat.add(criterion.toMockObject());
        }
        return new CachedResolution(
            Map.of(
                "oredCriteria", oredCriteria,
                "criteria.criteria", criteriaFlat
            ),
            stamps
        );
    }

    @Nullable
    private static String truncate(@Nullable String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /**
     * mapper 方法在本项目里的<b>首个可用调用点</b>。
     *
     * <p>T-PreviewOpenPerfM2 M5 的 {@code findFirst()} 在真机大工程上返回 null（同一方法 {@code Find Usages}
     * 有 253 处引用、索引健康）——{@code Query.findFirst()} 在这条检索链上不可靠。改为 {@code forEach}
     * 取首个命中（语义等价、仍是提前终止，不把全项目引用收成 List）。</p>
     *
     * <p>D6（真机 2026-11-12 14:46，{@code mybatis-plus-demo} 的 {@code CtCustmanagerRelMapper#selectByExample}）：
     * <b>命中不等于能当调用点用</b>。{@code ReferencesSearch} 对接口方法会把「实现的声明处 / 方法引用」
     * 一起返回，这类 ref 的 element 上溯没有 {@code PsiMethodCallExpression}——取第一个 ref 就直接放弃，
     * trace 里就是 {@code noCallSite}，而项目里明明有两个真实调用点。现在改为跳过不可用的 ref 继续找。</p>
     */
    @Nullable
    private static PsiMethodCallExpression findCallSite(@NotNull GlobalSearchScope scope, @NotNull PsiMethod method) {
        PsiMethodCallExpression[] site = new PsiMethodCallExpression[1];
        int[] refsSeen = {0};
        ReferencesSearch.search(method, scope).forEach(candidate -> {
            refsSeen[0]++;
            PsiMethodCallExpression call = PsiTreeUtil.getParentOfType(candidate.getElement(), PsiMethodCallExpression.class);
            if (call == null) {
                // 声明处 / 方法引用：继续找下一个
                return true;
            }
            site[0] = call;
            return false;
        });
        if (site[0] == null) {
            Integer total = null;
            try {
                total = ReferencesSearch.search(method, scope).findAll().size();
            } catch (Throwable ignored) {
                // runCatching 等义：仅日志观测
            }
            LOG.info("[m2] findCallSite empty method=" + method + " refsSeen=" + refsSeen[0] + " totalRefs=" + total);
        }
        return site[0];
    }

    /** 调用实参的变量名（{@code selectByExample(example)} → {@code example}）。 */
    @Nullable
    private static String exampleVariableName(@NotNull PsiMethodCallExpression call) {
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (args.length == 0 || !(args[0] instanceof PsiReferenceExpression ref)) {
            return null;
        }
        return ref.getReferenceName();
    }

    /**
     * 方法体内所有以 example 为根的 {@code and*} / {@code or*} 调用（默认 {@code trace = null} 重载见下）。
     */
    @NotNull
    private static LinkedHashMap<Object, List<MbgCriterionMock>> collectCriteria(@NotNull Project project,
                                                                                 @NotNull PsiCodeBlock body,
                                                                                 @NotNull String exampleName) {
        return collectCriteria(project, body, exampleName, null);
    }

    /** 方法体内所有以 example 为根的 {@code and*} / {@code or*} 调用。 */
    @NotNull
    private static LinkedHashMap<Object, List<MbgCriterionMock>> collectCriteria(@NotNull Project project,
                                                                                 @NotNull PsiCodeBlock body,
                                                                                 @NotNull String exampleName,
                                                                                 @Nullable StringBuilder trace) {
        // D7：键＝这条条件所属的 Criteria 节点（`createCriteria()` / `or()` 调用，或别名变量的初始化表达式）；
        // 同一 Criteria 的多条条件落在同一组，不同 Criteria 之间由 `Example_Where_Clause` 的 `or` 连接。
        LinkedHashMap<Object, List<MbgCriterionMock>> groups = new LinkedHashMap<>();
        int matched = 0;
        int noResolve = 0;
        int notExample = 0;
        int notRooted = 0;
        int noCondition = 0;
        int total = 0;
        Collection<PsiMethodCallExpression> calls = PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression.class);
        for (PsiMethodCallExpression call : calls) {
            if (total >= MAX_CRITERIA) {
                break;
            }
            String name = call.getMethodExpression().getReferenceName();
            if (name == null) {
                continue;
            }
            if (!AND_OR_METHOD.matcher(name).matches()) {
                continue;
            }
            matched++;
            PsiMethod method = call.resolveMethod();
            if (method == null) {
                noResolve++;
                continue;
            }
            if (!isExampleCriteria(method)) {
                notExample++;
                continue;
            }
            if (!rootedAtExample(call.getMethodExpression().getQualifierExpression(), exampleName)) {
                notRooted++;
                continue;
            }
            String condition = addCriterionCondition(project, method);
            if (condition == null) {
                noCondition++;
                continue;
            }
            Object groupKey = criteriaGroupKey(call, exampleName);
            if (groupKey == null) {
                groupKey = exampleName;
            }
            List<String> values = new ArrayList<>();
            for (PsiExpression arg : call.getArgumentList().getExpressions()) {
                values.add(arg.getText());
            }
            groups.computeIfAbsent(groupKey, k -> new ArrayList<>())
                .add(new MbgCriterionMock(condition, values, MbgCriterionMock.kindOf(name)));
            total++;
        }
        if (trace != null) {
            trace.append("(and/or=").append(matched).append(" noResolve=").append(noResolve)
                .append(" notExample=").append(notExample).append(" notRooted=").append(notRooted)
                .append(" noCond=").append(noCondition).append(") ");
        }
        return groups;
    }

    /**
     * D7：一条 {@code and*} / {@code or*} 条件归属哪条 Criteria。
     *
     * <p>链上最靠近根的 {@code createCriteria()} / {@code or()} 调用就是分界线——{@code example.createCriteria().andA().andB()} 的
     * 两条都归同一节点，{@code example.or().andC()} 归另一个节点。分步别名（{@code Criteria c = example.createCriteria();
     * c.andA()}）用变量的初始化表达式当键。认不出时返回 null，调用方按「同一个 example」兜底成一组。</p>
     */
    @Nullable
    private static Object criteriaGroupKey(@NotNull PsiMethodCallExpression call, @NotNull String exampleName) {
        PsiExpression cur = call.getMethodExpression().getQualifierExpression();
        while (cur instanceof PsiMethodCallExpression methodCall) {
            String referenceName = methodCall.getMethodExpression().getReferenceName();
            if ("createCriteria".equals(referenceName) || "or".equals(referenceName)) {
                return cur;
            }
            cur = methodCall.getMethodExpression().getQualifierExpression();
        }
        if (!(cur instanceof PsiReferenceExpression ref)) {
            return null;
        }
        if (exampleName.equals(ref.getReferenceName())) {
            return null;
        }
        PsiElement resolved;
        try {
            resolved = ref.resolve();
        } catch (Throwable t) {
            return null;
        }
        if (!(resolved instanceof PsiVariable variable)) {
            return null;
        }
        try {
            return variable.getInitializer();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 方法是否属于 MBG 的 {@code XxxExample}（或其内部类 {@code Criteria}）。 */
    private static boolean isExampleCriteria(@NotNull PsiMethod method) {
        PsiClass owner = method.getContainingClass();
        if (owner == null) {
            return false;
        }
        return endsWithExample(owner.getName())
            || (owner.getContainingClass() != null && endsWithExample(owner.getContainingClass().getName()));
    }

    private static boolean endsWithExample(@Nullable String name) {
        return name != null && name.endsWith("Example");
    }

    /**
     * 调用链的根是否指向<b>本次</b> selectByExample 实参那个 example 变量。
     *
     * <p>覆盖两种写法：链式 {@code example.createCriteria().andXxx(v)}（qualifier 最左端是 example 引用）
     * 与分步 {@code Criteria c = example.createCriteria(); c.andXxx(v)}（根的<b>初始化表达式</b>追溯到 example）。</p>
     *
     * <p>为什么不能按类型名收（E2E 验收缺陷 D4）：同一方法体里常有多个 Example 实例
     * （MBG 生成的服务实现就是 {@code exampleNA} / {@code exampleNB} 各一条链），按「变量类型以 Example 结尾」
     * 收会把<b>另一次查询</b>的条件也并进来——真机产物变成
     * {@code WHERE (status = ? AND cust_name LIKE ? AND tenant_id = ? AND create_time BETWEEN ? AND tenant_id = ?)}，
     * 两个 Example 的 AND 条件被拼成一条永不成立的谓词。所以只认<b>同一对象</b>：变量名相同，或该变量的
     * 初始化表达式追溯后仍落在同一个 example 变量上。</p>
     */
    public static boolean rootedAtExample(@Nullable PsiExpression expr, @NotNull String exampleName) {
        PsiReferenceExpression ref = rootReference(expr);
        if (ref == null) {
            return false;
        }
        if (exampleName.equals(ref.getReferenceName())) {
            return true;
        }
        PsiElement resolved;
        try {
            resolved = ref.resolve();
        } catch (Throwable t) {
            return false;
        }
        if (!(resolved instanceof PsiVariable variable)) {
            return false;
        }
        PsiExpression initializer;
        try {
            initializer = variable.getInitializer();
        } catch (Throwable t) {
            return false;
        }
        if (initializer == null) {
            return false;
        }
        PsiReferenceExpression root = rootReference(initializer);
        return root != null && exampleName.equals(root.getReferenceName());
    }

    /** 把 {@code a.b().c()} 这类链剥到最左端的引用表达式（{@code a}）。 */
    @Nullable
    private static PsiReferenceExpression rootReference(@Nullable PsiExpression expr) {
        PsiExpression cur = expr;
        while (cur instanceof PsiMethodCallExpression methodCall) {
            cur = methodCall.getMethodExpression().getQualifierExpression();
        }
        return cur instanceof PsiReferenceExpression ref ? ref : null;
    }

    /** {@code andXxxYyy} 方法体里 {@code addCriterion("<condition>", ...)} 的第一个实参常量。 */
    @Nullable
    private static String addCriterionCondition(@NotNull Project project, @NotNull PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        if (body == null) {
            return null;
        }
        PsiMethodCallExpression call = PsiTreeUtil.findChildOfType(body, PsiMethodCallExpression.class);
        if (call == null) {
            return null;
        }
        if (!"addCriterion".equals(call.getMethodExpression().getReferenceName())) {
            return null;
        }
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (args.length == 0) {
            return null;
        }
        Object constant = JavaPsiFacade.getInstance(project)
            .getConstantEvaluationHelper()
            .computeConstantExpression(args[0]);
        return constant instanceof String s ? s : null;
    }
}
