package cn.song.mybatisplus.sqlvis.agent.util;

import cn.song.mybatisplus.sqlvis.agent.AgentState;

import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调用栈定位（T-M56-04 / T-AgentSourceJump / T-SourceJumpFunnel / T-PS4-09）：
 * execute 时对本次栈快照扫描一次，跳过框架/基础设施帧，取用户代码帧；同一快照还可喂
 * {@link StackDigest} 产摘要——产线经 {@link #locate(StackTraceElement[], ClassLoader)} 携栈出口
 * 一次捕获两用（T-PS4-09，禁每条 SQL 两次 getStackTrace）。
 *
 * <p>三层判定（T-AgentSourceJump）：
 * <ol>
 *   <li><b>L2 前缀名单</b>：{@link #FRAME_SKIP_PREFIXES} 廉价跳过已知基础设施家族；</li>
 *   <li><b>L1 来源过滤</b>：开发态用户业务类来自<b>目录</b>（target/classes、build/classes），
 *       第三方依赖恒来自 <b>JAR</b>——按 {@code ProtectionDomain.codeSource} 判定，与类名无关，
 *       根治「前缀黑名单打地鼠」（PageHelper 等未收录插件被误选为用户帧）；</li>
 *   <li><b>安全网</b>：L1 过滤后无候选（典型=fat jar 运行，业务类也在 jar 内）→ 回退首个带调试信息的非框架帧，
 *       禁止「过滤到空导致跳转能力消失」的劣化。</li>
 * </ol>
 *
 * <p>T-SrcGenFrame（字节码生成类帧过滤，2026-10 真机 `<code>[&lt;generated&gt;]</code>` 报障定谳）：
 * CGLIB 增强类（{@code $$EnhancerBySpringCGLIB$$}/​{@code $$FastClassBySpringCGLIB$$} 族）的
 * SourceFile 属性是 Enhancer 字节码里 ldc 硬编码的 {@code <generated>}（spring-core 5.1.19
 * {@code Enhancer.generate → ClassEmitter.begin_class} 第 6 参 javap 实证），行号恒 -1。此类帧
 * <b>类名前缀即业务包名</b>、codeSource 亦随宿主类落业务目录/JAR——L1 来源判据天然放行；且 Spring
 * 代理调用经<b>跳过包内真实方法体</b>（典型=MP {@code ServiceImpl} 继承方法经代理触发）时，生成类帧
 * 就是<b>最深非框架帧</b>（池首），旧逻辑选中它 → 事件 sf=「业务包/&lt;generated&gt;」→ 面板渲染
 * {@code [<generated>]}。真机混合形态实证：同批条目直接调 mapper 接口的拿到真实业务行，经代理调
 * Service 继承方法的全部落到 <code>[&lt;generated&gt;]</code>。对策：<b>主位置选择（L1/上顶收敛/
 * 缓存命中深帧探测/候选列）一律要求真实源文件</b>，生成类帧让位给池中更深扫描到的真实业务帧（=
 * 触发本次调用的调用方行）；仅当池内<b>无任何</b>真实源文件帧（真无主：调度器直发/懒加载回调等）
 * 才由安全网原样采用，<code>[&lt;generated&gt;]</code> 标注保留作真无主信号。
 *
 * <p>T-SourceJumpFunnel（自研框架汇聚门面适配）：公司内自研数据访问框架（不经 MyBatis）的引擎全链帧
 * 不在 L2 名单、且可能来自目录（同工程多模块）——L1 会把「最深引擎帧」选成主位置，且所有业务调用共用
 * 同一个汇聚帧。对策：
 * <ol>
 *   <li><b>候选增厚</b>：{@link Frame#candidates} 每条含 [file, line, className, method] 四列（列尾追加，旧 IDE 只读前两列兼容）；</li>
 *   <li><b>只缓存判定、不缓存结果帧</b>：{@link #DECISIONS} 存的是「主位置身份」（via + class#method），
 *       行号与候选列每次执行按<b>当前栈</b>重建——整帧入缓存会冻结行号（T-AgentSourceLocateStale 根因：
 *       入口键落在全应用共享的库帧上时，首个查询的行号被钉死并服务此后一切业务语句），禁；</li>
 *   <li><b>上顶收敛</b>：主位置优先取首个「带调试信息」的候选，无调试信息的 null-file 帧不再顶上（M4 修复）；</li>
 *   <li><b>诊断打点</b>：{@code logLevel=info} 时输出判定路径（primaryKey / 来源路径 L1|SAFETY / pool 帧），真机定谳用；
 *       仅全量判定路径（缓存 miss）输出，缓存命中路径静默。产线固定 {@code logLevel=warn}，故宿主控制台零输出
 *       ——曾有一条与档位无关的「入口键非业务帧」一次性摘要（T-AgentSourceLocateStale T-05），2026-10-14 移除，
 *       缘由见 {@link #diagnose}。</li>
 * </ol>
 *
 * <p>T-PS4-09 扫描与判定缓存（热点红线 §4.2 #5）：
 * <ol>
 *   <li><b>缓存查询前移</b>：key = 首个非框架帧 class#method，前缀扫描到即查 {@link #DECISIONS}；</li>
 *   <li><b>判定缓存（L1 与安全网合并为一条）</b>：命中须经「身份在场」校验（主位置 class#method 仍在本次
 *       pool 中）→ 用<b>当前 pool</b> 的行号/候选列构造结果帧；校验失败落回全量路径并刷新判定。
 *       被省掉的是逐帧 {@code Class.forName} 来源解析——pool 构建（一次栈数组遍历、无反射）仍要付，
 *       因为行号与候选列都取自它（T-AgentSourceLocateStale T-01；原实现在此路径返回冻结整帧）。</li>
 * </ol>
 *
 * <p><b>跳过规则与 IDE 侧 {@code CallerSourceLocator} 的关系（事实描述，勿再假设必然同值）</b>：
 * 两侧是<b>各自独立的两份实现</b>（agent 不引 IDE 类，IDE 侧也绝不引 agent 代码——IDE 侧是降级主路径，
 * 必须在 agent 缺席时独立可用），但前缀集合自 <b>T-AgentResiduals R1（2026-09-22）起已同集</b>：
 * IDE 侧原只收 JDK/平台/插件/MyBatis 系 15 项，本侧另收驱动族 / 连接池 / 数据层插件族三十余项，于是
 * 同一次执行两侧可能给出<b>不同位置</b>（标记行指到框架帧）；现 IDE 侧已按本表逐项补齐，唯一刻意差异
 * 只剩<b>各自的自身包</b>（本侧 {@code cn.song.mybatisplus.sqlvis.} vs IDE 侧
 * {@code cn.song.mybatisplus.visualizer.}，语义等价）。两侧仍<b>在不同时机取栈</b>（IDE 侧拦截器在
 * SQL 生成期，agent 在 JDBC 执行期），故同一次执行的两处位置<b>仍可能不同</b>（帧构成不同），但
 * <b>跳过规则不再构成差异来源</b>；跨侧漂移由 {@code LocatorSkipPrefixSyncTest}（IDE 侧）以源码扫描 +
 * 差异登记锁定。动态前缀（用户登记的自研框架包名）经 CONFIG 下发，见 {@link #setExtraSkipPrefixes(String[])}。
 */
public final class StackLocator {

    /**
     * 框架/基础设施帧前缀（agent 侧独立副本，与 IDE 侧 {@code CallerSourceLocator} 跳过规则
     * <b>自 T-AgentResiduals R1 起同集</b>——唯一差异是各自的自身包前缀；跨侧差异由 IDE 侧
     * {@code LocatorSkipPrefixSyncTest} 登记锁定）。**改本表必须同步 IDE 侧
     * {@code CallerSourceLocator.isFrameworkFrame} 与示例模板 {@code docs/instrumentation/SqlSourceInterceptor.kt}**。
     */
    private static final String[] FRAME_SKIP_PREFIXES = {
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "jdk.internal.",
            "com.intellij.", "org.jetbrains.",
            // 本 agent
            "cn.song.mybatisplus.sqlvis.",
            // 驱动家族
            "com.mysql.", "org.mariadb.", "org.postgresql.", "org.h2.", "org.hsqldb.",
            "org.sqlite.", "com.microsoft.sqlserver.", "oracle.jdbc.", "dm.jdbc.",
            "com.gbasedbt.", "com.kingbase8.",
            // 信创 PG 系新族：海量 Vastbase（cn.com.vastbase.*）/ 瀚高 HighGo（com.highgo.*）
            "cn.com.vastbase.", "com.highgo.",
            // 华为 GaussDB 商用（com.huawei.gaussdb.*）与 openGauss 品牌（com.huawei.opengauss.*）
            // 共用 com.huawei.；社区 openGauss 2.x/3.x 在 org.opengauss.*
            "com.huawei.", "org.opengauss.",
            // 连接池 / ORM / Spring 数据层
            "com.zaxxer.", "com.alibaba.druid.", "com.mchange.", "org.apache.commons.dbcp2.",
            "org.apache.tomcat.",
            "org.apache.ibatis.", "com.baomidou.", "org.hibernate.",
            "org.springframework.", "org.aspectj.", "net.sf.cglib.", "org.objectweb.asm.",
            "com.sun.proxy.", "org.jboss.",
            // T-AgentSourceJump：数据层插件/基础设施家族补齐（漏收即被误选为用户帧——PageHelper 实证）
            "com.github.pagehelper.", "org.mybatis.", "net.sf.jsqlparser.",
            "com.alibaba.", "org.apache.", "com.google.", "com.fasterxml.",
            "io.netty.", "io.lettuce.", "redis.clients.", "io.micrometer.", "io.seata.",
            "org.slf4j.", "ch.qos.", "org.ehcache.", "net.sf.ehcache.", "com.github.benmanes.",
            "org.quartz.", "com.netflix.", "org.elasticsearch.", "com.mongodb.",
            // T-AgentSourceLocateStale T-02：MPJ（mybatis-plus-join）全族。依据：1.5.8 starter 自动配置
            // 注册 com.github.yulichang.interceptor.MPJInterceptor（MyBatis 插件链一环，类声明 implements
            // org.apache.ibatis.plugin.Interceptor）——它每次查询都在栈上、且对全应用所有查询是<b>同一帧</b>，
            // 未收则 entryKey 恒为该共享帧（用户报障形态：同一应用里所有条目的源码位置被首个查询钉死）；
            // 同族先例：com.github.pagehelper./com.github.benmanes. 已收。Maven groupId com.github.* 只是
            // 坐标不是类前缀，本表按<b>类包名</b>匹配，故只收真实包名前缀。
            "com.github.yulichang.",
            // 注：刻意不收通用工具库（如 cn.hutool.）——工具类可能被用户业务代码直接调用，误收即跳错
    };

    /** T-SourceJumpFunnel P1-3：用户登记的动态跳过前缀（CONFIG 热下发，volatile 整组替换）。 */
    private static volatile String[] EXTRA_SKIP_PREFIXES = new String[0];

    /**
     * T-ToolSiteBizFrame（机制 A）：用户登记的 <b>JAR 业务帧反向名单</b>（CONFIG 热下发，volatile
     * 整组替换）——名单内包名前缀的帧即使来自 JAR 也按业务帧对待（L1 不再上顶到调用方）。
     *
     * <p><b>语义与「跳过前缀」相反，不得接入 {@link #isFrameworkFrame}</b>：那等价于跳过语义，
     * 会把业务帧从候选池抽空、跳转能力消失（demo PR !1 E29 P2 档实证「登记 JAR 业务包救不回落点」）。
     */
    private static volatile String[] EXTRA_BIZ_PREFIXES = new String[0];

    /** 候选帧上限（IDE 侧解析回退用；过深无意义且加长事件体）。T-SourceJumpFunnel：3→8——汇聚门面下深层业务帧须可见。 */
    private static final int MAX_CANDIDATES = 8;

    /**
     * T-SrcGenFrame：CGLIB 增强类 SourceFile 属性的字面值（Enhancer 字节码 ldc 实证，非猜测）。
     * {@code toRelativePath} 产出形如「业务包/.../&lt;generated&gt;」的伪路径、行号恒 0——
     * IDE 面板渲染成 {@code [<generated>]}（无行号段）。
     */
    static final String GENERATED_SOURCE_NAME = "<generated>";

    /** T-SrcGenFrame：帧是否来自字节码生成类（源文件为 {@code <generated>} 占位——无真实 .java 可解析）。 */
    static boolean isGeneratedSourceFrame(String file) {
        return file != null
                && (file.equals(GENERATED_SOURCE_NAME) || file.endsWith("/" + GENERATED_SOURCE_NAME));
    }

    /** 判定缓存上限（溢出整体清空——既有粗粒度策略）。 */
    private static final int CACHE_MAX = 512;

    /** F30 类来源记忆化（loader 作用域 + className → loadedFromJar 结果；Optional 承载 null「未知」负缓存——CHM 禁 null 值；同 CACHE_MAX 界）。
     *  键含 loader 身份：同名类在不同 loader 下 codeSource 可不同（测试夹具动态 define 形态），纯 className 键会跨 loader 污染。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<Boolean>> ORIGIN_MEMO =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static String originMemoKey(String className, ClassLoader loader) {
        return Integer.toHexString(System.identityHashCode(loader)) + "#" + className;
    }

    /**
     * 定位「判定」缓存（T-AgentSourceLocateStale T-01 起 L1 与安全网<b>合并为一条</b>）：
     * key = 首个非框架帧 class#method → [via, 主位置 className, 主位置 method]。
     *
     * <p>只存判定不存结果帧——整帧入缓存会冻结行号与候选列（P0-3 缺陷形态，禁）；命中须经
     * 「身份在场」校验（见 {@link #locate(StackTraceElement[], ClassLoader)}），通过才用<b>当前 pool</b>
     * 的行号/候选列构造结果帧。有界同 {@link #CACHE_MAX}。
     *
     * <p>包内可见 = 同包测试断言/清场通道，产线勿外用。
     */
    static final ConcurrentHashMap<String, String[]> DECISIONS = new ConcurrentHashMap<String, String[]>();

    public static final class Frame {
        public final String sourceFile;
        public final int line;
        public final String methodKey;
        /** 栈序候选 [file, line, className, method]（T-SourceJumpFunnel 增厚至四列；≤8 条，排除主位置；IDE 侧解析回退用）。 */
        public final List<String[]> candidates;
        /** 诊断：主位置判定路径（L1=目录帧命中 / SAFETY=安全网，上顶收敛与首帧兜底均计入 SAFETY）。info 日志与测试断言用。 */
        public final String via;
        /**
         * 主位置的 FQCN 与原始方法名（T-StackBizFrame S2）。
         *
         * <p>为什么不复用 {@link #methodKey}：那是<b>简名</b>（`UserService#query`），两个不同包的
         * 同名类会撞键；摘要侧要按身份在同一份栈快照里精确定位这一帧，必须用 FQCN。
         */
        public final String primaryClassName;
        public final String primaryMethod;

        Frame(String sourceFile, int line, String methodKey, List<String[]> candidates, String via,
              String primaryClassName, String primaryMethod) {
            this.sourceFile = sourceFile;
            this.line = line;
            this.methodKey = methodKey;
            this.candidates = candidates;
            this.via = via;
            this.primaryClassName = primaryClassName;
            this.primaryMethod = primaryMethod;
        }
    }

    public static Frame locate() {
        return locate((ClassLoader) null);
    }

    /**
     * @param hint 能看见应用类的 ClassLoader（Spring Boot 下 = 上下文 loader / 被织入对象 loader）——
     *             用于判定帧来源是否 JAR；null 时取下线程上下文 loader。
     */
    public static Frame locate(ClassLoader hint) {
        // 自捕获出口保留（兼容/测试）；产线主路径走下方携栈出口（T-PS4-09 一次捕获两用）
        return locate(Thread.currentThread().getStackTrace(), hint);
    }

    /**
     * T-PS4-09 携栈出口（一次捕获两用）：调用方已持有本线程快照时复用之——StatementHandler 用同一
     * 快照喂本入口与 {@link StackDigest#build(StackTraceElement[])}，消除每条 SQL 第二次全栈捕获。
     *
     * @param stack 须为当前线程 {@code Thread.currentThread().getStackTrace()} 的原样产物
     *              （本方法按数组内容扫描，不再自取、不校验线程归属）。
     * @param hint  能看见应用类的 ClassLoader；null 时取下线程上下文 loader。
     */
    public static Frame locate(StackTraceElement[] stack, ClassLoader hint) {
        final ClassLoader loader = resolveLoader(hint);
        // ── 阶段一（廉价前缀扫描）：key = 首个非框架帧 class#method。
        // [0]=getStackTrace [1]=locate 自身帧——从 0 扫描无妨，反射/agent 自身帧被前缀规则跳过
        String entryKey = null;
        int firstNonFramework = -1;
        for (int i = 0; i < stack.length; i++) {
            if (!isFrameworkFrame(stack[i].getClassName())) {
                entryKey = stack[i].getClassName() + "#" + stack[i].getMethodName();
                firstNonFramework = i;
                break;
            }
        }
        if (entryKey == null) {
            return null;   // 全栈皆框架帧（原「pool 为空」语义）
        }

        // ── 阶段二：非框架帧 pool（栈序）：[file, line, className, method]。
        // 首帧（firstNonFramework 处）必入池，pool 恒非空。
        // T-AgentSourceLocateStale T-01：判定命中路径同样需要本 pool——行号与候选列一律按当前栈现算，
        // 故不再有「命中即免建池」。池构建只是一次栈数组遍历（无反射、无类加载），被省掉的仍是
        // 逐帧 Class.forName 来源解析（T-PS4-09 的性能意图不变）。
        final List<String[]> pool = new ArrayList<String[]>(8);
        for (int i = firstNonFramework; i < stack.length; i++) {
            final StackTraceElement f = stack[i];
            final String cn = f.getClassName();
            if (isFrameworkFrame(cn)) {
                continue;
            }
            final String file = toRelativePath(cn, f.getFileName());
            final int line = f.getLineNumber() >= 0 ? f.getLineNumber() : 0;
            pool.add(new String[]{file, String.valueOf(line), cn, f.getMethodName()});
        }

        // ── 阶段三：判定缓存命中 + 「身份在场」校验（T-AgentSourceLocateStale T-01）。
        // 缓存的是「判定」（via + 主位置身份 class#method），不是结果帧：命中后必须在本执行 pool 里
        // 按身份找到同一帧，才用它的当前行号与当前候选列构造结果帧。于是：
        // ① 键是否精确不再决定正确性（入口键落在共享库帧上也不再冻结行号）；
        // ② 同一方法内多个调用点、文件编辑后行号漂移，全部自愈；
        // ③ 共享键被别的调用点改写判定（如 MPJ 拦截器帧先后承载两个业务方法）时身份不在场 →
        //    落回全量路径重算，正是本缺陷现场（42 vs 63）被根治的路径。
        final String[] decision = DECISIONS.get(entryKey);
        if (decision != null) {
            final int at = indexOfIdentity(pool, decision[1], decision[2]);
            if (at >= 0 && pool.get(at)[0] != null) {
                // T-ToolSiteBizFrame（机制 B′，demo PR !1 E34 实测钉死）：「身份在场」还不够——
                // 缓存身份可能是本执行栈的<b>祖先浅帧</b>（同一 Service 方法先直发过一条 SQL 时，
                // 它以「最深业务帧」身份写入共享 entryKey 的判定；其后经业务工具类发的 SQL 栈上
                // 该方法是祖先帧、必然在场），直接采用会把主位置钉在「调用工具类」那一行。
                // 命中后与全量路径对齐：在 [0..at-1]（比缓存身份更深的调用链段）里找首个业务来源帧，
                // 找到即改用并刷新判定。at==0（直发形态，pool[0] 本就是最深业务帧）无更深层段，
                // 零反射直取——健康路径不付来源解析成本。
                final int deeper = at == 0 ? -1 : firstBusinessFrameBefore(pool, loader, at);
                if (deeper < 0) {
                    return buildFrame(pool, at, decision[0]);
                }
                final Frame refreshed = buildFrame(pool, deeper, "L1");
                if (refreshed == null) {
                    // 与全量路径对齐：改用深帧后无调试信息 → 本次不产出位置、不刷新判定（下次重算）
                    return null;
                }
                final String[] p = pool.get(deeper);
                writeCaches(entryKey, p[2] + "#" + p[3], "L1");
                return refreshed;
            }
        }

        // ── 阶段四：全量判定（缓存 miss 或身份不在场才付）。
        int primaryAt = -1;
        String via = "SAFETY";

        // L1 来源过滤：优先取「非 JAR 来源」帧（开发态用户业务类在目录）；
        // T-ToolSiteBizFrame（机制 A）：反向名单登记的包名前缀，其帧即使来自 JAR 也按业务帧对待；
        // T-SrcGenFrame：字节码生成类帧（SourceFile=<generated>）不算数——类名/codeSource 都像业务帧，
        // 但无真实源文件，选中即产出现场可见的 [<generated>] 标注，让位给池中真实业务帧（调用方行）
        for (int i = 0; i < pool.size(); i++) {
            final String[] c = pool.get(i);
            if (isSelectableBusinessFrame(c[0], c[2], loader)) {
                primaryAt = i;
                via = "L1";
                break;
            }
        }
        // P0-4 上顶收敛：L1 无命中 → 优先取首个「带调试信息」的非框架帧（安全网不劣化、
        // 且无调试信息的帧不可作为定位——旧逻辑会拿 null-file 帧再从候选上顶，可达汇聚门面）；
        // T-SrcGenFrame：<generated> 占位虽有「文件名」但解析不出任何 .java，同按无调试信息处理
        if (primaryAt < 0) {
            for (int i = 0; i < pool.size(); i++) {
                if (pool.get(i)[0] != null && !isGeneratedSourceFrame(pool.get(i)[0])) {
                    primaryAt = i;
                    break;
                }
            }
        }
        // 全无调试信息 → 原样取首个非框架帧（file 为 null，本次不产出位置）；
        // T-SrcGenFrame：池内只剩生成类帧（真无主：调度器直发/框架回调等）时同落此处——
        // <generated> 标注保留，作为「连业务帧都没有」的显式信号（修复后它只出现在真无主形态）
        if (primaryAt < 0) {
            primaryAt = 0;
        }

        final Frame frame = buildFrame(pool, primaryAt, via);
        if (frame == null) {
            // 全链无调试信息：本次不产出位置、不缓存（下次重试）
            return null;
        }
        final String[] primary = pool.get(primaryAt);
        writeCaches(entryKey, primary[2] + "#" + primary[3], via);
        diagnose(entryKey, frame, pool, loader);
        return frame;
    }

    /** pool 中按身份（FQCN + 方法名）定位：命中返回下标，未命中 -1。 */
    private static int indexOfIdentity(List<String[]> pool, String className, String method) {
        for (int i = 0; i < pool.size(); i++) {
            final String[] c = pool.get(i);
            if (className.equals(c[2]) && method.equals(c[3])) {
                return i;
            }
        }
        return -1;
    }

    /**
     * {@code pool[0..bound-1]}（比缓存身份<b>更深</b>的调用链段）中首个「业务来源帧」的下标，
     * 无则 -1。T-ToolSiteBizFrame（机制 B′）：缓存身份是祖先浅帧而本执行经业务工具类下钻时，
     * 真执行点就在这一段里——全量路径 L1 本会选中它，命中路径须对齐。
     *
     * <p>T-SrcGenFrame：判据升级为 [isSelectableBusinessFrame]（业务来源<b>且</b>带真实源文件）——
     * 生成类帧的 origin 判据天然放行（类名带业务包名 + 宿主 codeSource），不挡会把命中路径的
     * 深帧探测拖回「选中 CGLIB 代理帧」的老坑（共享代理方法键下，这正是真机 <code>[&lt;generated&gt;]</code>
     * 现场的另一半通路：全量路径修对了、命中路径再选回去）。
     */
    private static int firstBusinessFrameBefore(List<String[]> pool, ClassLoader loader, int bound) {
        for (int i = 0; i < bound; i++) {
            final String[] c = pool.get(i);
            if (isSelectableBusinessFrame(c[0], c[2], loader)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * L1 业务来源判据（T-ToolSiteBizFrame 起 L1 与缓存命中路径的深帧探测共用）：
     * 命中用户反向名单（{@link #EXTRA_BIZ_PREFIXES}）或目录来源（{@link #loadedFromJar} = false）。
     * 刻意与 {@link #isFrameworkFrame} 分立——跳过语义与业务帧提升是两回事，混用即 PR P2 档实证的
     * 「候选池抽空」劣化。
     */
    private static boolean isBusinessOrigin(String className, ClassLoader loader) {
        return isRegisteredBusinessFrame(className) || Boolean.FALSE.equals(loadedFromJar(className, loader));
    }

    static boolean isRegisteredBusinessFrame(String className) {
        for (String p : EXTRA_BIZ_PREFIXES) {
            if (className.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * T-SrcGenFrame：可作<b>主位置</b>的业务帧判据（L1 全量路径与命中路径深帧探测共用）——
     * [isBusinessOrigin] 命中<b>且</b>带真实源文件。
     *
     * <p>为什么 origin 判据不够：CGLIB 增强帧类名前缀即业务包名、codeSource 随宿主类落业务
     * 目录/业务 JAR（反向名单登记业务包时同样命中），来源维度上它与真实业务帧不可区分；
     * 唯一稳定信号是 SourceFile 占位 {@code <generated>}（{@link #isGeneratedSourceFrame}）。
     * 真机实证（2026-10）：经 Spring 代理调 MP {@code ServiceImpl} 继承方法（真实方法体所在类
     * 被 {@code com.baomidou.} 前缀跳过）时，池内生成类帧之外的真实帧只剩<b>调用方</b>——本判据
     * 让位后 L1 恰好落到调用方行，即「真实调用点」。
     */
    private static boolean isSelectableBusinessFrame(String file, String className, ClassLoader loader) {
        return !isGeneratedSourceFrame(file) && isBusinessOrigin(className, loader);
    }

    /**
     * T-StackBizFrame S1：调用栈摘要的栈底锚点复用的「业务帧」判据——与定位侧 L1
     * （{@link #isSelectableBusinessFrame}）<b>同源</b>，不再另造第二套名单。
     *
     * <p>为什么必须同源：摘要与定位是同一份栈快照上的两条判定，各自维护一套「什么算业务帧」
     * 必然漂移——漂移的后果正是用户报障形态（位置列有项目类、栈快照里没有）。
     *
     * @param frame  待判帧；null 恒 false
     * @param loader 能看见应用类的 loader（与 locate 的 hint 同一个）；null 退上下文 loader
     */
    public static boolean isBusinessFrame(StackTraceElement frame, ClassLoader loader) {
        if (frame == null) {
            return false;
        }
        final String cn = frame.getClassName();
        // 自身包永不算业务帧：开发/测试态 agent 类同样来自目录（codeSource 非 JAR），来源判据会
        // 把它当成「用户业务类」，于是摘要锚点落到采集入口帧、整条栈被截成两帧（T-StackBizFrame
        // 单测首轮实测）。产线 agent 来自 JAR 时这条不触发，但判据不该依赖部署形态。
        // 字面量与 FRAME_SKIP_PREFIXES 里「本 agent」项同值——那里刻意保持字面量，因为
        // LocatorSkipPrefixSyncTest 按源码扫描该数组提取前缀，换成常量会让守卫提取不到。
        if (cn == null || cn.startsWith("cn.song.mybatisplus.sqlvis.")) {
            return false;
        }
        // T-PerfSweep29 P-5 压测发现：原实现走 isSelectableBusinessFrame(toRelativePath(...))，
        // 每帧拼一次「pkg/File.java」字符串（栈底扫描最多几十帧，实测摘要侧 +2.6µs/SQL）。
        // 这里直接判文件名——<generated> 是 SourceFile 的裸值，不需要先拼相对路径。
        final String fileName = frame.getFileName();
        if (fileName == null || GENERATED_SOURCE_NAME.equals(fileName)) {
            return false;
        }
        return isBusinessOrigin(cn, resolveLoader(loader));
    }

    /**
     * 由<b>当前 pool</b> 的第 {@code primaryAt} 条构造结果帧（T-AgentSourceLocateStale T-01：行号与
     * 候选列每执行重建，禁整帧缓存）。主位置无调试信息（file 为 null）时返回 null = 本次不产出位置。
     */
    private static Frame buildFrame(List<String[]> pool, int primaryAt, String via) {
        final String[] primary = pool.get(primaryAt);
        if (primary[0] == null) {
            return null;
        }
        final List<String[]> candidates = new ArrayList<String[]>(MAX_CANDIDATES);
        for (String[] c : pool) {
            // T-SrcGenFrame：生成类帧不进候选列——IDE 侧按路径解析必 404，白占候选预算
            if (c[0] == null || c[0].equals(primary[0]) || isGeneratedSourceFrame(c[0])) {
                continue;
            }
            // T-SourceJumpFunnel P0-2：候选增厚为 [file, line, className, method] 四列
            candidates.add(new String[]{c[0], c[1], c[2], c[3]});
            if (candidates.size() >= MAX_CANDIDATES) {
                break;
            }
        }
        return new Frame(primary[0], Math.max(Integer.parseInt(primary[1]), 0),
                simpleMethodKey(primary[2] + "#" + primary[3]), candidates, via, primary[2], primary[3]);
    }

    /**
     * 缓存写入（T-SourceJumpFunnel P0-3 定案 + T-PS4-09 策略 A + T-AgentSourceLocateStale T-01），
     * 仅全量判定路径到达。
     *
     * <p><b>P0-3 缓存防合流（定案依据，禁回退）</b>：整帧结果缓存会冻结「候选列 + 行号」。自研框架
     * 汇聚门面（不经 MyBatis）下所有业务调用共享同一汇聚帧 → 同一 key——若把结果帧入缓存，
     * 「第一个调用者」的引擎帧、行号与候选列会服务此后一切业务语句（一次错=处处错）。
     * 故本类<b>任何路径都不缓存结果帧</b>：只写判定（via + 主位置身份），行号/候选列按当前栈现算。
     *
     * <p><b>T-AgentSourceLocateStale T-01（L1 与安全网合并为一条判定缓存）</b>：原实现 L1 路径
     * {@code putIfAbsent} 缓存整帧并「先到者永久服务该 key」——当入口键是全应用共享的库帧
     * （如 MyBatis 插件链上的 {@code MPJInterceptor#intercept}，前缀表未收时即被选为入口键）时，
     * 首个查询的行号被钉死并服务此后一切业务语句，这正是用户报障「位置串用」的根因。
     * 判定缓存 + 身份在场校验使「键是否精确」不再决定正确性。
     *
     * <p><b>为何判定缓存不复蹈 P0-3</b>：缓存的是「判定」而非结果帧——候选列与行号每执行从当前栈重建，
     * 各调用方自身的业务帧信息永不冻结；命中须校验主位置身份仍在本执行 pool 中（不在即全量重算并刷新
     * 判定），调用链变化可自愈。被冻结的身份本身对共享 key 的调用方是确定性相同的（安全网取首个带调试
     * 信息帧，共享汇聚帧在各调用方栈中同形，重算也得到同一身份）。原实现安全网每执行全量重扫的所谓
     * 「上界=调用方种类数」并不成立——fat jar 下每条 SQL 都在重扫（per-execute 全栈解析），正是本策略
     * 消除的成本。
     *
     * <p><b>已知残面（如实记录）</b>：混合部署下同 key 先被全 JAR 调用链写成 SAFETY 判定、后被含目录帧的
     * 调用链命中时，判定在场会掩盖一次 L1 机会（主位置落到 JAR 帧）——候选列仍含该目录帧兜底，可恢复。
     */
    private static void writeCaches(String entryKey, String primaryKey, String via) {
        if (DECISIONS.size() >= CACHE_MAX) {
            DECISIONS.clear();
        }
        // 拆开存 className/method，命中校验按两段比较，免逐帧拼串
        final int hash = primaryKey.indexOf('#');
        DECISIONS.put(entryKey,
                new String[]{via, primaryKey.substring(0, hash), primaryKey.substring(hash + 1)});
    }

    /**
     * T-SourceJumpFunnel P0-1 诊断打点：logLevel=info 时输出判定路径（真机定谳 M2/M3/M4 用），默认档零输出。
     * 仅全量判定路径调用（缓存命中路径静默，每条 SQL 零噪音）；产线固定 {@code logLevel=warn} 下本打点
     * 不可达——真机需要 entryKey 时把 attach 参数改成 {@code logLevel=info} 即可拿到（含 entry 与 pool）。
     *
     * <p><b>2026-10-14 移除「入口键非业务帧」无条件摘要（原 T-AgentSourceLocateStale T-05）</b>：该摘要与
     * 档位无关、按 entryKey 每次 JVM 只打一条，设计前提是「身份不一致＝异常（入口键落在共享库帧）」。
     * 但 T-SrcGenFrame 之后，Spring 代理调用（{@code $$SpringCGLIB$$0} 生成类帧）必然构成「入口键是生成类、
     * 主位置是真实业务帧」——这正是<b>常态健康形态</b>，于是摘要对每个被代理的 Service 方法各打一条，
     * 退化为宿主控制台噪音（用户报障实例：{@code CarStationInfoServiceImpl$$SpringCGLIB$$0#getById}）。
     * 诊断前提已不成立且 info 档已含同款信息（entry + pool），故整条路径删除；宿主侧不再有本条输出。
     */
    private static void diagnose(String entryKey, Frame frame, List<String[]> pool, ClassLoader loader) {
        if (!"info".equalsIgnoreCase(AgentState.getLogLevel())) {
            return;
        }
        final StringBuilder sb = new StringBuilder(256);
        sb.append("[T-SourceJumpFunnel] primary=").append(frame.sourceFile).append(':').append(frame.line)
                .append(" via=").append(frame.via).append(" entry=").append(entryKey)
                .append(" pool=").append(pool.size());
        for (int i = 0; i < Math.min(pool.size(), 8); i++) {
            final String[] c = pool.get(i);
            sb.append(" | #").append(i).append(' ').append(c[2]).append('#').append(c[3])
                    .append(':').append(c[1]).append(" jar=").append(loadedFromJar(c[2], loader));
        }
        System.err.println(sb);
    }

    /** T-SourceJumpFunnel P1-3：CONFIG 下发的用户前缀登记（整组替换，空/畸形输入安全）。 */
    public static void setExtraSkipPrefixes(String[] prefixes) {
        if (prefixes == null || prefixes.length == 0) {
            EXTRA_SKIP_PREFIXES = new String[0];
            return;
        }
        final List<String> cleaned = new ArrayList<String>(prefixes.length);
        for (String p : prefixes) {
            if (p != null && !p.trim().isEmpty()) {
                cleaned.add(p.trim());
            }
        }
        EXTRA_SKIP_PREFIXES = cleaned.toArray(new String[0]);
    }

    public static String[] getExtraSkipPrefixes() {
        return EXTRA_SKIP_PREFIXES;
    }

    /** T-ToolSiteBizFrame：CONFIG 下发的 JAR 业务帧反向名单（整组替换，空/畸形输入安全）。 */
    public static void setExtraBizJarPrefixes(String[] prefixes) {
        if (prefixes == null || prefixes.length == 0) {
            EXTRA_BIZ_PREFIXES = new String[0];
            return;
        }
        final List<String> cleaned = new ArrayList<String>(prefixes.length);
        for (String p : prefixes) {
            if (p != null && !p.trim().isEmpty()) {
                cleaned.add(p.trim());
            }
        }
        EXTRA_BIZ_PREFIXES = cleaned.toArray(new String[0]);
    }

    public static String[] getExtraBizJarPrefixes() {
        return EXTRA_BIZ_PREFIXES;
    }

    public static boolean isFrameworkFrame(String className) {
        for (String p : FRAME_SKIP_PREFIXES) {
            if (className.startsWith(p)) {
                return true;
            }
        }
        for (String p : EXTRA_SKIP_PREFIXES) {
            if (className.startsWith(p)) {
                return true;
            }
        }
        return className.contains("$Proxy");
    }

    /** T-PS4-09 测试注入口（包内可见）：非 null 时替代默认来源判定（fat jar 模拟/计数探针）；产线恒 null，仅一次 volatile 读。 */
    interface JarOriginProbe {
        Boolean isFromJar(String className, ClassLoader loader);
    }

    static volatile JarOriginProbe jarOriginProbe;

    /**
     * 帧来源是否 JAR（L1）：true=第三方依赖；false=目录（开发态用户业务代码）；null=未知
     * （类不可加载 / codeSource 缺失——不武断判为用户帧，仅在安全网阶段兜底采用）。
     */
    public static Boolean loadedFromJar(String className, ClassLoader loader) {
        final JarOriginProbe probe = jarOriginProbe;
        if (probe != null) {
            return probe.isFromJar(className, loader);
        }
        return defaultLoadedFromJar(className, loader);
    }

    /** 默认来源判定（codeSource 路径形态；注入口非 null 时被替代）。
     *  F30：per-class 记忆化（含 null 负缓存）——「缓存身份=祖先浅帧 + 更深段全为名单外 JAR 帧」
     *  形态下同一类每事件重付 at 次 Class.forName + CodeSource 解析（无记忆化），B′ 深帧探测
     *  的唯一实质重付点；类的 codeSource 在同一 JVM 内不变，记忆化语义安全。EXTRA_BIZ_PREFIXES
     *  仍每事件活读（名单 volatile 整组替换，缓存化会读旧名单）。 */
    static Boolean defaultLoadedFromJar(String className, ClassLoader loader) {
        if (className == null) {
            return null;
        }
        Boolean result = null;
        final String memoKey = originMemoKey(className, loader);
        final java.util.Optional<Boolean> memo = ORIGIN_MEMO.get(memoKey);
        if (memo != null) {
            return memo.orElse(null);
        }
        try {
            final Class<?> c = Class.forName(className, false, loader);
            final CodeSource cs = c.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                result = null;
            } else {
                final String loc = cs.getLocation().toString();
                result = loc.endsWith(".jar") || loc.contains(".jar!") || loc.contains(".jar/");
            }
        } catch (Throwable t) {
            result = null;
        }
        if (ORIGIN_MEMO.size() >= CACHE_MAX) {
            ORIGIN_MEMO.clear();
        }
        ORIGIN_MEMO.put(memoKey, java.util.Optional.ofNullable(result));
        return result;
    }

    /** 仅供同包测试清场（判定缓存/来源记忆为全局静态，跨用例隔离用）；产线勿调。 */
    static void clearLocatorCachesForTest() {
        DECISIONS.clear();
        ORIGIN_MEMO.clear();
    }

    private static ClassLoader resolveLoader(ClassLoader hint) {
        if (hint != null) {
            return hint;
        }
        final ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        return ctx != null ? ctx : StackLocator.class.getClassLoader();
    }

    /** FQCN + 文件名 → 相对路径（pkg/.../File.java，正斜杠；与标记行格式一致）。 */
    public static String toRelativePath(String className, String fileName) {
        if (fileName == null) {
            return null;
        }
        final int dot = className.lastIndexOf('.');
        final String pkg = dot < 0 ? "" : className.substring(0, dot).replace('.', '/');
        return pkg.isEmpty() ? fileName : pkg + "/" + fileName;
    }

    /** com.example.service.UserService#query → UserService#query。 */
    private static String simpleMethodKey(String fqMethod) {
        final int hash = fqMethod.indexOf('#');
        final String cn = hash < 0 ? fqMethod : fqMethod.substring(0, hash);
        final int dot = cn.lastIndexOf('.');
        final String simple = dot < 0 ? cn : cn.substring(dot + 1);
        return hash < 0 ? simple : simple + fqMethod.substring(hash);
    }

    private StackLocator() {
    }
}
