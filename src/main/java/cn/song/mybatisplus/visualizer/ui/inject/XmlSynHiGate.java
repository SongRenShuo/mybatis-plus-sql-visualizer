// T-SyncK2JMaster104 波C 增量翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inject/XmlSynHiGate.kt（W1 区间级互补）
package cn.song.mybatisplus.visualizer.ui.inject;

import cn.song.mybatisplus.visualizer.util.FatalErrors;
import com.intellij.lang.Language;
import com.intellij.lang.injection.MultiHostInjector;
import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import kotlin.jvm.functions.Function2;
import org.jetbrains.annotations.Nullable;

/**
 * T-XmlSynHi D2/D3 门控 + T-XmlGap W1 区间级互补：让位从<b>宿主级</b>下沉到<b>区间级</b>。
 *
 * <p>让位清单（T-DevAcceptFix ④ 定案 + W1 修订）：</p>
 * <ol>
 *   <li>设置开关 {@code PluginSettings.state.xmlSynHiEnabled} 关闭（用户显式停用）→ 全族零注入；</li>
 *   <li><b>准入区间</b>（{@link #occupiedRanges}，D68 + W1）——枚举 {@code com.intellij.multiHostInjector} EP 的
 *     <b>全部</b>在册注入器（除本插件自身族），按喂入契约预筛（{@link MultiHostInjector#elementsToInjectIn()}
 *     含宿主类型才会被平台喂入）后以 stub registrar 试调 {@code getLanguagesToInject}，记录其
 *     {@code addPlace} 的<b>区间</b>；{@code SqlMapperInjector} 只注入「这些区间之外」的余下部分。
 *     W1 前的形态是「任一命中即整宿主让位」——语句里只要有一个 {@code #{}}，整条语句的关键字/注释/
 *     字符串全部无色（Community 版 + 装第三方 MyBatis 插件即中招）。单个注入器试探抛异常 →
 *     跳过它继续问下一个（不让一个坏注入器把本插件也拖成零着色）；EP 访问/枚举整体异常 →
 *     返回全覆盖哨兵（fail-safe 保守让位）。</li>
 * </ol>
 *
 * <p>两条历史让位线<b>同批废止</b>（20261006 验收实测证伪「在位=覆盖」这一共同前提）：
 *  - 「平台 {@code SqlLanguageInjector} 类在场即让位」：该类在 243 构建发行物（599 jar）与用户真机
 *    IU-262.10968.63（1809 jar）全量扫描均不存在（现行平台 SQL 注入器为 EP 注册的
 *    SqlTypeBasedInjector，两代 javap 实证 {@code implements MultiHostInjector}，喂入类型全为 SQL PSI
 *    ——mapper XML 宿主平台从不会喂入、更不会着色）——类在场判定既失效又误伤；
 *  - 「第三方 MyBatis 插件安装且启用即让位」：用户真机上第三方插件处于启用状态，mapper 语句文本
 *    关键字仍无色（在位≠覆盖）——批发让位正是本次验收缺陷现场；现并入上条 per-host 探测，
 *    它真会给该宿主上色才让位。</p>
 *
 * <p>W1 实测补充（20261012，证据见 T-XmlGap 计划 §实施记录）：某商业插件的社区轨注入器被其自身的
 * 平台让位门挡住（Ultimate 专用能力缺席时整段跳过），其平台轨注入器的喂入类型是 {@code XmlTag}
 *（不在本门探测面）；用户真机另装的第三方插件<b>不注册任何注入器</b>。
 * 故 Ultimate 真机上本门不触发——W1 的价值在 Community 版（社区轨注入器活跃）与任何未来第三方注入器。</p>
 *
 * <p>守卫族（非让位，自身 O(1)）：
 *  - 根/祖先标签 mapper（与 STATEMENT_TAGS 消费口径一致）；
 *  - 文本长度熔断（超长文本不扫描——万行级 mapper 的输入卡顿防线，V2/§D6）。</p>
 */
public final class XmlSynHiGate {

    private XmlSynHiGate() {
    }

    /** 文本长度熔断阈值：超过即不注入（防万行级标签的区间扫描卡顿；§D6 验收口径）。 */
    public static final int MAX_TEXT_LENGTH = 100_000;

    /** 本插件族包前缀：EP 枚举命中自身注册项时排除（自身让位语义由本门其余各线负责）。 */
    private static final String SELF_PACKAGE_PREFIX = "cn.song.mybatisplus.";

    /**
     * D68 per-host 探测缝（布尔形态）。<b>null = 未覆写</b>——此时让位判定由 W1 的区间级探测推导
     * （{@link #foreignOccupiedRangesProbe}，语义等价但信息更细：布尔形态表达不了「对方只占了一个
     * {@code #{}}」）。既有用例仍可置非 null 直接钉布尔形态（会注入/不会注入/抛异常三形态）。
     */
    @Nullable
    public static Function2<Project, PsiElement, Boolean> foreignInjectionProbe = null;

    /**
     * T-XmlGap W1 区间级探测缝：外来注入器对该宿主<b>实际占用</b>的区间（宿主坐标系）。
     *
     * <p>生产路径（SqlMapperInjector）只走区间——由 {@link InjectRangePlan} 算出「未被子集覆盖的余下区间」
     * 再注入，取代原「命中即整宿主放弃」。测试可直注区间清单（含全覆盖 = 让位、部分覆盖 = 互补）。</p>
     */
    public static Function2<Project, PsiElement, List<TextRange>> foreignOccupiedRangesProbe =
            (project, host) -> defaultForeignOccupiedRanges(project, host);

    /** F79 刀二（T-PerfSweep24）：per-host 探针结论 stamped memo（戳=含文件 PSI 修改戳＋在册注入器清单实例）。 */
    private static final class ProbeMemo {
        final long psiStamp;
        final List<MultiHostInjector> injectorList;
        final List<TextRange> ranges;

        ProbeMemo(long psiStamp, List<MultiHostInjector> injectorList, List<TextRange> ranges) {
            this.psiStamp = psiStamp;
            this.injectorList = injectorList;
            this.ranges = ranges;
        }
    }

    private static final Map<PsiElement, ProbeMemo> foreignProbeMemo =
            Collections.synchronizedMap(new WeakHashMap<>());

    // ------------------------------------------------------------------
    // V38 计数缝（诊断，默认关）
    // ------------------------------------------------------------------

    /**
     * T-PerfSweep25 通道 1 ③（V38）：F79 残留探针税<b>量级实测</b>所需的计数缝。
     *
     * <p>量级不可静态推算——探针被问几次取决于真机编辑动作与在册注入器形态（含第三方），
     * 故以开关式计数记录「{@link #occupiedRanges} 每问：memo 命中 / 实探」的累计读数，每 100 问
     * 打一行到 idea.log（logger 名 {@code mpsv.v38}），供真机（沙盒 vmoptions 注
     * {@code -Dmpsv.probeTrace=true}）直读：某次编辑动作前后两行读数之差 = 该动作的问询量与实探量。</p>
     *
     * <p>成本：开关为类加载期读一次系统属性的字段（测试可翻转），<b>关闭时每问只多一次布尔读</b>
     * （零增量分配、无 Timer/无正则，符合红线族）；开启时每问两次 AtomicLong 增量，仅诊断期承担。</p>
     */
    public static volatile boolean probeTraceEnabled = "true".equals(System.getProperty("mpsv.probeTrace"));

    private static final AtomicLong traceCalls = new AtomicLong();
    private static final AtomicLong traceMemoHits = new AtomicLong();
    private static final AtomicLong traceProbeRuns = new AtomicLong();
    private static final Logger traceLog = Logger.getInstance("mpsv.v38");

    private static void traceCall(boolean memoHit) {
        if (!probeTraceEnabled) return;
        long total = traceCalls.incrementAndGet();
        if (memoHit) {
            traceMemoHits.incrementAndGet();
        } else {
            traceProbeRuns.incrementAndGet();
        }
        if (total % 100L == 0L) {
            traceLog.info("[V38] probeCalls=" + total + " memoHits=" + traceMemoHits.get()
                    + " probeRuns=" + traceProbeRuns.get());
        }
    }

    /**
     * V38 计数缝读数快照（calls / memoHits / probeRuns 三累计）。真机读数由 idea.log 的
     * {@code [V38]} 行直读；本快照供测试直读（免日志解析）。
     */
    public static long[] probeTraceSnapshot() {
        return new long[]{traceCalls.get(), traceMemoHits.get(), traceProbeRuns.get()};
    }

    /** V38 计数缝复位（测试用；真机不需要——进程级累计即读数）。 */
    public static void resetProbeTraceForTest() {
        traceCalls.set(0);
        traceMemoHits.set(0);
        traceProbeRuns.set(0);
    }

    /**
     * 默认区间探针：EP 枚举（项目级）+ {@link #foreignInjectorsOccupiedRanges}（读
     * {@code MultiHostInjector.MULTIHOST_INJECTOR_EP_NAME}，平台 util 模块编译期 API，243/262 双版
     * javap 实证同形态）。EP 访问/解析任何异常 → 返回<b>全覆盖哨兵</b>（保守让位，D68 口径）；
     * {@link ProcessCanceledException}/{@link CancellationException} 照平台契约原样上抛（吞掉会破坏取消协议）。
     */
    private static List<TextRange> defaultForeignOccupiedRanges(@Nullable Project project, PsiElement host) {
        try {
            Project proj = project != null ? project : host.getProject();
            return foreignInjectorsOccupiedRanges(MultiHostInjector.MULTIHOST_INJECTOR_EP_NAME.getExtensions(proj), host);
        } catch (ProcessCanceledException cancelled) {
            throw cancelled;
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw t; // T-FP-02：fatal 透传
            return InjectRangePlan.yieldAll(host.getTextLength());
        }
    }

    /**
     * T-XmlGap W1：该宿主被外来注入器占用的区间表（生产入口）。
     *
     * <p>三条线：设置开关关闭 → 全覆盖哨兵（等价让位）；布尔缝被测试覆写 → 以它为准（true 全覆盖、
     * false 空表）；否则走区间探针。任一线抛异常 → 全覆盖哨兵（fail-safe 保守让位，D68 口径）。</p>
     */
    public static List<TextRange> occupiedRanges(@Nullable Project project, PsiElement host) {
        if (!xmlSynHiEnabled()) return InjectRangePlan.yieldAll(host.getTextLength());
        try {
            // F79 刀二（T-PerfSweep24）：per-host 探针结论 stamped memo——喂入面上探针是唯一重操作
            //（EP 枚举 O(1) 之后的逐注入器试探链，成本三方决定不可控），结论按「宿主弱身份＋含文件
            // PSI 修改戳＋在册注入器清单实例」缓存：修改戳变化 / 宿主重解析换对象 / 注入器清单换代
            // → 结论自动失效；探针异常走外层 catch 的 fail-safe 全覆盖且**不入缓存**（瞬时故障不得
            // 跨修改残留，恢复后下一问重探）。memo 覆盖布尔缝与区间探针**两条路径**（测试缝计数同口径）。
            PsiFile file = host.getContainingFile();
            long psiStamp = file != null ? file.getModificationStamp() : 0L;
            List<MultiHostInjector> injectorList =
                    MultiHostInjector.MULTIHOST_INJECTOR_EP_NAME.getExtensions(project != null ? project : host.getProject());
            if (file != null) {
                ProbeMemo memo = foreignProbeMemo.get(host);
                if (memo != null && memo.psiStamp == psiStamp && memo.injectorList == injectorList) {
                    traceCall(true);
                    return memo.ranges;
                }
            }
            traceCall(false);
            List<TextRange> fresh;
            if (foreignInjectionProbe != null) {
                fresh = Boolean.TRUE.equals(foreignInjectionProbe.invoke(project, host))
                        ? InjectRangePlan.yieldAll(host.getTextLength())
                        : List.of();
            } else {
                fresh = foreignOccupiedRangesProbe.invoke(project, host);
            }
            if (file != null) {
                // 全覆盖哨兵可能是 fail-safe 形态（探测失败）——不入缓存，恢复后下一问重探
                boolean failSafeShape = fresh.size() == 1 && fresh.get(0).getStartOffset() == 0
                        && fresh.get(0).getEndOffset() == host.getTextLength();
                if (!failSafeShape) {
                    foreignProbeMemo.put(host, new ProbeMemo(psiStamp, injectorList, fresh));
                }
            }
            return fresh;
        } catch (ProcessCanceledException cancelled) {
            throw cancelled;
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw t; // T-FP-02：fatal 透传
            return InjectRangePlan.yieldAll(host.getTextLength());
        }
    }

    /**
     * 探测主体（internal 供测试直注注入器清单）：逐在册注入器（自身族除外）——
     * <ol>
     *   <li>喂入契约预筛：{@code elementsToInjectIn} 含宿主类型才会被平台喂入（243
     *     {@code InjectedLanguageUtilBase.probeElementsUpInner} 同口径），不命中直接跳过（不咨询）；</li>
     *   <li>命中者以 {@link RangeRegistrar} 试调 {@code getLanguagesToInject}，{@code startInjecting}
     *     被调用 = 该注入器会对该宿主上色 → 让位；</li>
     *   <li>单个注入器试探抛异常 → <b>跳过它继续问下一个</b>（T-DevAcceptFix ④：该注入器覆盖不可判，
     *     不让一个坏/怪注入器把本插件一起拖成零着色——「坏注入器⇒零着色」正是本次验收缺陷的
     *     原始形态；反方向的双写叠色风险由「真 startInjecting 才让位」的精确判定本身兜住）。</li>
     * </ol>
     *
     * <p>F86 补口径（性能二十五轮，随 F79 同批）：咨询核心自身不缓存（PSI 随重解析失效）；生产路径
     * {@link #occupiedRanges} 在本核心之上按 F79 刀二 stamped memo 缓存结论（宿主弱键＋文件修改戳＋
     * 注入器清单实例，任一换代即失效重探）。注入器清单每问每取（EP 内部有平台级缓存，
     * 取列表本身 O(1) 级）。性能注：喂入契约预筛使 SQL 系注入器（喂入类型全为 SQL PSI）对
     * XML 宿主零试探成本，但<b>喂入契约含 XML 宿主的三方注入器（如 MCH-Pro 社区轨）仍按次
     * 全价试调</b>——F79 前 probe 是每问成本主项，现由谓词前置（非 mapper/非白名单宿主零探针）
     * ＋stamped memo（通过守卫的宿主每修改戳至多一探）摊薄。</p>
     */
    public static boolean foreignInjectorsWouldInject(List<MultiHostInjector> injectors, PsiElement host) {
        for (ProbeRecord record : probeInjectors(injectors, host)) {
            if (record.started) return true;
        }
        return false;
    }

    /**
     * T-XmlGap W1 区间主体（与 {@link #foreignInjectorsWouldInject} 共用同一咨询核心）：返回外来注入器
     * 对该宿主<b>记录到的 addPlace 区间</b>并集（宿主坐标系，未排序未合并——由 {@link InjectRangePlan} 归一）。
     *
     * <p>与布尔形态的差别：布尔只要 {@code startInjecting} 被调即算「对方覆盖」，区间形态要求<b>确有 addPlace</b>
     * ——「调了 startInjecting 却没落任何 place」属畸形注入（平台侧会断言失败），不该让我们整宿主放弃。</p>
     */
    public static List<TextRange> foreignInjectorsOccupiedRanges(List<MultiHostInjector> injectors, PsiElement host) {
        List<TextRange> out = new ArrayList<>();
        for (ProbeRecord record : probeInjectors(injectors, host)) {
            out.addAll(record.ranges);
        }
        return out;
    }

    /** 单次咨询记录：{@code started} = 调过 {@code startInjecting}；{@code ranges} = 记录到的 addPlace 区间。 */
    private static final class ProbeRecord {
        final boolean started;
        final List<TextRange> ranges;

        ProbeRecord(boolean started, List<TextRange> ranges) {
            this.started = started;
            this.ranges = ranges;
        }
    }

    /**
     * 咨询核心（internal 供测试直注注入器清单）：逐在册注入器（自身族除外）——
     * <ol>
     *   <li>喂入契约预筛：{@code elementsToInjectIn} 含宿主类型才会被平台喂入（243
     *     {@code InjectedLanguageUtilBase.probeElementsUpInner} 同口径），不命中直接跳过（不咨询）；</li>
     *   <li>命中者以 {@link RangeRegistrar} 试调 {@code getLanguagesToInject}，记录 {@code startInjecting}
     *     与每个 {@code addPlace} 的区间；</li>
     *   <li>单个注入器试探抛异常 → <b>跳过它继续问下一个</b>（T-DevAcceptFix ④：该注入器覆盖不可判，
     *     不让一个坏/怪注入器把本插件一起拖成零着色——「坏注入器⇒零着色」正是该次验收缺陷的
     *     原始形态）。</li>
     * </ol>
     *
     * <p>F86 补口径同 {@link #foreignInjectorsWouldInject}：本核心不缓存，生产路径由
     * {@link #occupiedRanges} 的 F79 刀二 stamped memo 缓存结论。</p>
     */
    private static List<ProbeRecord> probeInjectors(List<MultiHostInjector> injectors, PsiElement host) {
        List<ProbeRecord> out = new ArrayList<>();
        for (MultiHostInjector inj : injectors) {
            if (isSelfProvided(inj)) continue;
            RangeRegistrar registrar = new RangeRegistrar();
            try {
                boolean feeds = false;
                for (Class<? extends PsiElement> type : inj.elementsToInjectIn()) {
                    if (type.isInstance(host)) {
                        feeds = true;
                        break;
                    }
                }
                if (!feeds) continue;
                inj.getLanguagesToInject(registrar, host);
            } catch (ProcessCanceledException cancelled) {
                throw cancelled;
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (Throwable t) {
                if (FatalErrors.isFatal(t)) throw t; // T-FP-02：fatal 透传
                continue;
            }
            out.add(new ProbeRecord(registrar.started, registrar.ranges));
        }
        return out;
    }

    /** 自身族判定：本插件包前缀的在册项不参与探测（自身让位由门其余各线负责）。 */
    private static boolean isSelfProvided(MultiHostInjector inj) {
        return inj.getClass().getName().startsWith(SELF_PACKAGE_PREFIX);
    }

    /** 记录 {@code startInjecting} 与每个 {@code addPlace} 区间 的 stub registrar（探针专用；二参
     *  startInjecting 的接口默认实现委托一参——243 lib/util-8.jar 字节码实证，故只覆写一参
     *  即覆盖两种调用形）。 */
    private static final class RangeRegistrar implements MultiHostRegistrar {
        boolean started = false;
        final List<TextRange> ranges = new ArrayList<>();

        @Override
        public MultiHostRegistrar startInjecting(Language language) {
            started = true;
            return this;
        }

        @Override
        public MultiHostRegistrar addPlace(
                @Nullable String prefix,
                @Nullable String suffix,
                PsiLanguageInjectionHost host,
                TextRange rangeInsideHost) {
            ranges.add(rangeInsideHost);
            return this;
        }

        @Override
        public void doneInjecting() {
        }
    }

    /** 共通线让位判定（D2/D3 共用；不含 per-host 线——喂入税基准/缓存语义等 host 无关口径沿用此形）。 */
    public static boolean shouldYield(@Nullable Project project) {
        return !xmlSynHiEnabled();
    }

    /**
     * per-host 让位判定（D68 + W1）：<b>整宿主</b>都被对方占用才算让位（我方无处可注入）。
     *
     * <p>W1 语义修订留痕：本方法曾以「占用区间非空」为判据——在区间形态下会把「对方只占了一个
     * {@code #{}}」也判成让位，正好抵消 W1 的互补收益（本类自测
     * {@code XmlSynHiGateRangeTest.testW1_部分占用_不让位} 抓出）。现改为按「宿主全段是否被覆盖」判定，
     * 与 SqlMapperInjector 的零注入条件严格一致（plan 为空）。</p>
     */
    public static boolean shouldYield(@Nullable Project project, PsiElement host) {
        List<TextRange> occupied = occupiedRanges(project, host);
        if (occupied.isEmpty()) return false;
        return InjectRangePlan.plan(host.getText(), new TextRange(0, host.getTextLength()), occupied).isEmpty();
    }

    /**
     * F47（T-PerfQuick5，V38 选型）：设置开关快照——喂入税消解。
     *
     * <p>喂入面（性能十七轮 §5.1）= 任意 XML 族文件每 XmlText/XmlAttributeValue 每 pass 一次
     * shouldYield，原形态每次付 1 次 app 级服务查找（85ns/元素实测）。V38 两案实测（3002 喂入位）：
     * 快照直读 14ns/元素、per-psi-vfs-epoch memo 202ns/元素（查表+containingFile 反超服务查找）
     * ——<b>快照案占优落地</b>。F86/F79 更新（性能二十五轮）：快照读之外 W1 形态还有 per-host 探针
     * （F79 前 probe 每问全链白付、曾是每问成本主项而本注未登记）——现谓词前置（非 mapper/
     * 非白名单宿主零探针，SqlMapperInjector 守卫族全在 probe 之前）＋刀二 stamped memo（通过
     * 守卫的宿主每修改戳至多一探），设置快照读重新是常态唯一成本。刷新漏斗唯一：
     * {@code PluginSettings.loadState}（设置页 apply 与 JSON 导入均汇入，PluginSettingsPanel.apply
     * 末尾恒 loadState(s.copy())）；快照默认 true 同 State 默认值，测试环境无服务时语义不变。</p>
     */
    private static volatile boolean xmlSynHiEnabledSnapshot = true;

    /** 快照刷新（PluginSettings.loadState 唯一漏斗调用；测试经 loadState 走同一路径）。 */
    public static void refreshXmlSynHiEnabledSnapshot(boolean enabled) {
        xmlSynHiEnabledSnapshot = enabled;
    }

    private static boolean xmlSynHiEnabled() {
        return xmlSynHiEnabledSnapshot;
    }

    /** 语句文本守卫：祖先链含 mapper 标签 + 文本长度未超熔断。 */
    public static boolean allowStatementText(XmlTag tag) {
        if (!inMapperFile(tag)) return false;
        if (tag.getTextLength() > MAX_TEXT_LENGTH) return false;
        return true;
    }

    /** 属性值守卫：属性名 ∈ 注入白名单（test/value/collection/item/open/close/separator/prefix/overrides）+ 长度熔断。 */
    public static boolean allowAttributeValue(String name, String value) {
        if (value.length() > 4096) return false;
        return ATTRIBUTE_WHITELIST.contains(name);
    }

    private static final Set<String> ATTRIBUTE_WHITELIST = Set.of(
            "test", "value", "collection", "item", "index",
            "open", "close", "separator", "prefix", "prefixOverrides", "suffixOverrides");

    /** 祖先链是否 mapper（含根标签首元素 name 含 mapper 的宽松口径——友商/自研 mapper 形态）。 */
    public static boolean inMapperFile(XmlTag tag) {
        XmlTag cur = tag;
        while (cur != null) {
            if (cur.getName().equalsIgnoreCase("mapper")) return true;
            cur = cur.getParentTag();
        }
        PsiFile containingFile = tag.getContainingFile();
        XmlTag root = containingFile instanceof XmlFile ? ((XmlFile) containingFile).getRootTag() : null;
        return root != null && root.getName().toLowerCase(Locale.ROOT).contains("mapper");
    }
}
