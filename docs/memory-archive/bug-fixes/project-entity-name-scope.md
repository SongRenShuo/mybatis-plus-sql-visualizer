---
name: project-entity-name-scope
description: 同名实体跨模块误选（T-EntityNameScope）：按查询宿主模块可见性解析、歧义即终态、预览与转换链提取口径合一
metadata:
  node_type: memory
  type: project
  originSessionId: sess_00a325f2-4943-4c18-87d1-b80d31784e6f
---

# 同名实体跨模块误选：按查询宿主模块可见性解析（T-EntityNameScope，20261016）

**用户报障形态**：预览 demo 的 `WmsInventoryJarQueryService#findAvailableJarInventory`，产物却是
`wms_inventory_embed_demo` + `move_tool_pallet_no`，而 HQL 里只存在于 jar 实体的两条嵌入路径
（`sku.inventoryStatus` / `itemKey.lotInfo.receivedDate`）原样透传。**根因不是嵌入列前缀**——HQL 的
`from WmsInventoryEntity` 是**简单名**，而 demo 仓里 `jpa-embedded-prefix-demo` 与 `jpa-jar-model-lib`
**各有一份同名实体**，此前 `getClassesByName(name, scope).firstOrNull()` 按索引顺序取第一个，取到了另一个。

**判据（可用）**：查询宿主模块已知时只查 `moduleWithDependenciesAndLibrariesScope(宿主模块)`
——jar 实体是宿主模块的依赖（可见），嵌入列 demo 不是（不可见），一次分对。
**必须带 Libraries**：`moduleWithDependenciesScope`（不含库）对 jar 里的类恒 null（仓内
`ProjectTypeDetector` 已留过同款注释）。

## 三条踩过的坑（都是「看起来对、实际错」）

1. **「显式 `@Entity(name=X)` 一定优先于同名默认实体」是错的**：JPA 里显式实体名与默认的简单类名**同处
   一个命名空间**；两个可见实体实际用同一名字时就是歧义，**没有谁优先**。排序只能用于稳定展示，不能用来
   伪造确定性。**同级多候选 → fail closed（不选、返回未解析、提示一次）**，不静默挑一个。
2. **给显式类加门禁时，很容易顺手把「根本没有 Java 类」这条合法形态一起判死**：第一版在
   `resolveHqlTable` 里「类解析不到 → `return null`」，切断 HBM 纯 XML 映射工程的既有路径
   （`testHbmRootAndNamedQueryCachesAreModuleScoped` 由绿转红，199/1）。正确写法＝类解析不到时**仍按
   既有路径查 XML 映射**。**教训：收窄一条路径的入口时，先把这条路径上所有合法「空结果」形态列一遍。**
3. **显式传入的 `entityClass` 不是根实体**：它是 `createQuery(hql, X.class)` 的结果类型、`@NamedQuery`
   的**注解宿主**。只做「名字匹配」就直用＝旁路掉 scope 与歧义判定；转换链随后优先采用这个非空
   TableInfo，无法补救。正确门禁＝**与 scope 解析结果一致，或本身在查询宿主可见范围内**。

## 跨链路绕过（三车道各自全绿也盖不住，只读复核才查出来）

- **① 显式类旁路**（见坑 3）。
- **② 未解析的副表别名借根表列**：`(owner ?: rootTable).columnByField(field)` 会把「已声明但解析失败
  （含歧义）」的副表限定符改写成根表列名——「歧义实体不选类」做到了，「歧义别名不借列」没做；且首轮
  限定符守卫会被后续**裸词替换阶段**二次绕过（裸词扫描须先吃掉「完整点号路径」且不带捕获组）。
- **③ 预览与转换链的根实体提取口径不一致**：预览用旧弱提取器（不掩码字面量、不看括号深度、只取包名
  首段），转换链用强算法，而**预览先产出的非空 TableInfo 会被转换链优先采用** ⇒ 弱提取压过强提取。
  实测反例 `select (select max(d.id) from Detail d) from Inventory i` → 按 `Detail` 渲染。
  修法＝把强算法**收口进 `HqlEntityNameExtractor.extract`**，`JpaQueryEntityLookup.rootEntity` 改为纯委托，
  生产只剩一处实现（字面量掩码 + 括号深度 0 + **全限定名保留**）。

**教训**：**分车道全绿只证明各自契约，证明不了跨链路的绕过路径**；链路两端「谁的结果被优先采用」
决定了不一致时的实际产物。

## 落地与验证

共享接口基底先落地并提交，再三条车道**独立工作树 + 独立沙盒**并行（解析器 / 预览入口 / 转换链）——
写集互不重叠；后段用户要求收归主控，遂停子智能体、cherry-pick 两侧成果、主控补齐 ①③ 并跑集成定向与反向。

- 修前红基线（旧版 + 仅新增用例）：2 类 32 例中 **10 红**（提取器 5 + 预览 5），即 ①③ 判别集。
- 修后集成定向：**19 类 199 例 0 失败 0 错误 0 跳过**。
- 反向：一次摘掉三处保护 → **恰 12 红**（③7 / ②3 / ①2），其余 187 绿；单独摘掉「歧义即终态」→
  58 例中**恰 1 红**；两轮均还原后复跑回绿。

**边界**：普通短名快路**不**额外扫描「另一 Java 类名声明了同名 `@Entity(name)`」的实体（不新增全库
遍历的代价），故不能声称穷尽实体名冲突；`EntityAliasSweep` 慢路径到 5000 短名预算上限时按未解析处理
（而非证明不存在）。**真机走查（在用户 IDEA 安装包上点预览）未做**——本轮全部为 headless 定向。

相关：[[project-embedded-col-prefix-gap]]（同一报障链上的嵌入列前缀缺口，与之独立）、
[[pitfall-verification-blindspots-and-wrapup]]、[[pitfall-guard-covers-audited-targets]]（守卫看不见要防的站点）、
[[project-pending-plans]]。

## 追加（同日）：性能收口 `2ca6ce4c`（触顶结果落缓存）

用户追问「这次改动会不会带来性能问题」→ 在共享入口挂临时探针实测：`EntityAliasSweep.resolve` 92 次调用里
**16 次走扫描**（约 17%），其余为**直接命中（不扫描）**；触发扫描的都是按名直接查不到的名字；预览是
**点击才转换**（gutter 悬停零转换、tooltip 静态），扫描跑在 `previewAsync` 的阻塞读动作里。

真机问题：真实工程的短名空间（含 JDK）恒 > `SCAN_LIMIT=5000` ⇒ **触顶是常态**，而触顶原本不写缓存 ⇒
**同一个名字每次预览都重扫一整段预算**（按本机实测 0.19–0.27 ms/短名外推单次约 1.0–1.35s，且持读锁同等
时长）。收口＝**触顶也落缓存（空候选）**：结果不变（触顶本就恒为未解析）、只去掉重复代价；**直接命中在
缓存查询之前就已返回**，故空缓存不会遮蔽真实实体。读数 **19 类 205/0/0**；反向摘掉「触顶落缓存」→
**恰 1 红**（`testTruncatedScanIsCachedAndNotRepeated`）。

**同一陷阱本轮再次命中（提醒自己）**：`BundleParityGuardTest` 的包是 `…visualizer.i18n`，我在 `--tests`
里写成 `…config` ⇒ Gradle **在「部分」过滤式不匹配时不报错**，结果目录里根本没有它的 XML；前两次「19 类」
读数实际只跑了 **18 类**。**判据＝「过滤条数 == 落盘 XML 条数」**，不是退出码。

**新增并行纪律（本轮两次踩到）**：`git commit` / `commit --amend` **提交的是整个索引**——别的会话已
`git add` 的内容（本轮是一笔发行说明删除）会被顺手带进我的提交。提交前先看 `git status` **第一列**的
`D `/`M `（＝他人已暂存），或直接用 `git commit -- <显式路径>`（路径限定提交，绕过索引）。

## 追加（20261022）：jar 内宿主 + 工程同名实体 → 降级（**已实施**，`54059ef0`+`b55a23ec`）

新报障形态（用户真机）：HQL/DAO 方法在 jar（源码可见）、实体类也在 jar，工程里又新增一份**同包同名**类
（影子，字段有调整）→ 预览出 `SELECT task.* /* 实体不可解析，降级为 task.* */`。

**根因（经 W0 七变体实测修正，与初版计划不同）**：库内宿主 `contextModule == null`（平台对库文件
`getModuleForFile` 恒 null）→ `searchScope` 退到 `allScope`；`ProjectAndLibrariesScope.compare` 对
「order-entry 数不同」或「各 entry 比较结果不一致」的组合**返 0**，故**跨模块**的同 FQN 两份不消解 →
假歧义 → fail closed。**注意：同一模块内平台 `PsiShortNamesCacheImpl.getClassesByName` 自己就按
`scope.compare` 消解了（V1 不复现）**——初版计划写的「所有同 FQN」是错的，实际是「跨模块/跨 order-entry」。
`allScope.compare` 并非恒 0，别沿用旧结论。

**另发现更宽的一条**：`FROM <名>` 解析里**任何同短名的非实体类**都会制造假歧义（V7：普通 DTO 连
`@Entity` 都没有，照样把预览打成降级）——Hibernate 只认映射实体，普通类不是候选。

**修法**（`psi/EntityClassResolver.kt`，两条都以 `context != null` 门控）：
① `preferProjectContent`——逐 FQN 组内「工程内容优先于依赖库」（工程输出恒先于依赖 jar）；
② 同短名候选里 `isLikelyMappedEntityClass` 为假者不参与歧义判定；
③ 无宿主元素（context==null，外部按名反查）时两条都不生效，保持既有 fail-closed 契约——
**这条门控是回归逼出来的**：首版没加，`testContextlessLookupRequiresUniqueAcrossSourceAndLibrary` 立刻变红。
④ P2＝歧义通知**用户可见文案**带 `[工程]`/`[库]`；测试缝与日志留原始 FQN（首版把标签塞进 sink，
又打破了既有 FQN 断言）。

**W2 反编译形态已取证**（用户要求两种 jar 形态都识别）：桩 PSI（`ClsClassImpl`）方法 **body 恒 false**
⇒ 桩上看不见 HQL；`getDecompiledPsiFile()`（FernFlower 文本）给真 Java PSI，生产链在它上面产物与
sources 形态一致（`wms_task_jar`、列正确、不降级）。**P-9 证伪**：反编译文本里 HQL 字面量没消失
（局部变量名退化成 `var1`，字面量保留）。剩余未证＝编辑器 gutter 图标在反编译视图里是否真出现（平台
daemon 行为，须真机走查）。

**读数**：定向 41 例全绿（EntityClassResolverHeavy 13 + ShadowEntityResolve 5 + DecompiledJarHostPreview 1
+ BundleParityGuard 5 + SqlPreviewEntityScopeHeavy 17）；反向三组分别**恰 2/1/1 红**；全量 **8122/0/0/9**。

**实施期三坑**：① `PsiTestUtil.addLibrary(5 参)` 用 `parentUrl + classRoot` 拼串，目录不带尾斜杠会拼坏
→ 改 `addModuleLibrary(module, name, classesUrls, sourceUrls)` 直给 URL；② javac 产物须 `refresh(false,true)`
否则库类不进短名索引（我第一次就拿到假读数）；③ **OOM 后残留测试 JVM 会锁住沙盒 jar**（`prepareTestSandbox`
报「无法在使用用户映射区域打开的文件上执行」）→ 核对命令行后 kill 再删 sandbox，否则索引 `CorruptedException`
把后续用例全染红、伪装成产品缺陷。

计划（含 §10 实施记录与前提修正表）：`docs/开发计划/20261022-2019-T-ShadowEntityResolve-jar内宿主叠加工程同FQN影子实体致实体不可解析-开发计划.md`
（分支 `t-shadow-entity-resolve`，工作树 `mpsv-shadow-entity`，未合 master、未 push）。
demo 仓复现用例已备（两个模块 + sources jar/反编译两形态步骤 + 对照组，提交 `cb9279b2`/`089b5a06`/`9764328a`/`04e0c189`），真机走查待用户。

## 追加二（20261023）：workspace 零候选形态（W3，已修 `91400a3e` 合 master）

用户真机复现仍降级，且把 lib 实体源码注释掉后 demo 影子也不识别。**第三种形态**：IDEA Maven workspace
把提供方与业务模块**都加载为模块**（README 预警的「workspace 假绿」以反向应验——不是缺陷消失，是换成零候选）：
宿主 DAO 在 lib 模块源码＝模块内容，`contextModule`=lib；实体只在下游 demo（依赖方向 demo→lib，lib 不可见
demo）→ 宿主作用域**零候选** → 降级。前两轮治「多候选」，治不了「零候选」。**别再犯**：demo 仓复现必须确认
IDEA 的模块加载方式；`target/classes` 旧 class 在注释源码后不会消失（excluded 不进索引但 jar 已打包）。

**修法（W3 零候选拓宽）**：宿主作用域零候选且 `context != null`（scope 尚非 allScope）→ 放宽 allScope 再查；
**全项目唯一命中且 `isLikelyMappedEntityClass` 才采用**；多命中走歧义终态（首版写成静默空候选，被自家反向
用例逼回——「让用户看到候选清单」优于无声降级）。**契约有意识变更一处**：
`testExplicitInvisibleClassCannotBypassHostScope` 原钉「不可见显式类→null」翻转，改造为窄域**有候选**形态下
钉同一防护（local 可见胜出、wrong 不得顶掉）；防挑错实体核心（多候选不猜）不放松。

读数：红基线恰 2 红 → 定向 **37/0/0/0** → 反向摘拓宽恰 2 红 → 全量 **8124/0/0/9**（上轮 8122 + 2 条新用例）。
另：全量首轮挂 `SenderLoopBackoffTest`（退避毫秒断言 296/352ms），与改动无关、单跑 11s 回绿＝并行 gif 录制
抢 CPU 的时序抖动——**时间敏感断言在共享机器上必抖，别急着归因自己的改动**。

## 追加三（20261023）：demo 复现装置改成「三种 jar 形态」，本地 JAR 放 `resources/lib`

用户要求「再测 jar 包非依赖场景」，并指定**本地 jar 放 demo 的 `src/main/resources/lib/`**（该模块的既有约定位置）。

**形态三（非依赖，推荐）**：`shadow-entity-demo/pom.xml` 的依赖从兄弟模块改成
`<scope>system</scope> + <systemPath>${project.basedir}/src/main/resources/lib/shadow-entity-lib-1.0.0.jar</systemPath>`。
这样该 JAR 在 IDE 里**只以 Library 出现**，与聚合工程加载了哪些模块无关，可对 `shadow-entity-lib` 执行
Unload 做最纯粹的非依赖走查。配合 `<includeSystemScope>true</includeSystemScope>` 才能进 Boot 重打包。
三种形态（本地 jar 文件 / sources jar 附加 / 无源码反编译）目标读数一致：`wms_task_project` + `task_type`/`remark`。

**两条实测纠正**（写 pom 前先看，省一轮）：
1. **Maven 默认 resources 就会把 `src/main/resources` 下的 `.jar` 拷进 `target/classes`**——我加的
   `<resources>` 覆盖块（排除再单独 include）是多余的，删掉后行为不变。
2. **`target/classes` 里的旧 class 不会因为注释掉源码而消失**：用户把 lib 的 `WmsTask.java` 整个注释后，
   `target/classes` 仍留着注释前编的 class、jar 也是注释前打的 ⇒ 「注释源码」不等于「jar 里没这个类」，
   要恢复「jar 双份」形态必须重新 `mvnd -pl shadow-entity-lib -DskipTests install` 再拷新产物。

**重建本地 jar 的配方**：`mvnd -pl shadow-entity-lib -DskipTests install` → 把 `target/` 下新的
`shadow-entity-lib-1.0.0.jar` 与 `-sources.jar` 覆盖到 `shadow-entity-demo/src/main/resources/lib/`
（`-sources.jar` 要留着，「sources jar 附加」形态靠它）。核内容用 `javap -v` 看常量池里的表名
（`wms_task_jar`）比看时间戳可靠。

## 追加四（20261023）：W4「jar 在内容根内」已核实被覆盖 + 用户第三次报障的真因是沙盒陈旧

用户把本地 jar 放进 `resources/lib` 后仍见降级，我怀疑这是第四种未覆盖形态（jar 类同时满足
`isInContent` 与 `isInLibraryClasses`，可能让 P0a 误判成「两份都是工程内容」）。**W4 取证推翻了该怀疑**：
读数 `libRoot.inContent=true` / `jar.inLibraryClasses=true` / `host.module=<模块>`（宿主拿到模块上下文了，
不像纯库文件恒 null），但解析仍**候选 1、非歧义、取工程源码那份**；端到端从**反编译 PSI**（桩 PSI
body 恒 false）取调用点，产出 `wms_task_project`、`DEGRADED=false`。⇒ 现有修复已覆盖，**不需要改码**；
已落护栏 `JarInsideContentRootTest`（`7916f639`）。

**真因**：用户跑的 `IntelliJIdea2026.2` 是 gif 录制沙盒 `build/MPSV-GifFresh`，其中插件是 10-22 22:08 构建、
字节码里 `preferProjectContent`/`isLikelyMappedEntityClass` 两个标记**都不存在**（零修复）。详见
[[pitfall-sandbox-jar-staleness-and-window-enum]] 的 20261023 节（含多沙盒对照表与字节码标记法）。
**别再犯**：用户说「沙盒运行」不等于「跑的是带修复的那份」——先查进程 `-Didea.config.path` 定位到具体沙盒，
再对该沙盒 jar 做字节码标记检查，最后才谈代码。

**W4 夹具两坑**：① `LocalFileSystem` 打开 `.jar` 时 `children=0`（只当普通二进制），`JarFileSystem.refreshAndFindFileByPath` 在
light/heavy 测试环境注册不稳、恒返 null ⇒ 改用「解包成 class 目录 + `addModuleLibrary` 挂库根」，
语义等价且可重复；② 断言「jar 类也在库根」时不能用 `findClass(fqn)` 取对象（同 FQN 影子已存在时它返回
影子那份），必须按路径直接取 jar 内 `.class` 文件。

**收口状态**：W0/P0a/P0b/P2/W2 已 ff 合入 master（`002d929f`），W3 `91400a3e`、W4 护栏 `7916f639` 均在 master；
分支 `t-shadow-entity-resolve` 与工作树 `mpsv-shadow-entity` 已删除（`worktree remove` 报长路径失败，robocopy
镜像空目录后 Remove-Item 清掉 1.5G 残留）。**全部未 push**。

## 追加五（20261023）：W5/W6——「两份都判工程内容」「两份都是跨模块源码」两形态，收窄改三步

用户又两轮报障，两轮都由**歧义诊断日志**定案。该日志是本轮加的（`entity-ambiguity[0/1]`，逐候选打
`ext/inContent/inLibClasses/inLibSource/module/url`）——**现场不给读数时，先加这条日志比继续推断划算**。

**W5 形态（`a52d083d`）**：歧义气球里两个候选**都标 `[工程]`** ⇒ 那个环境下 jar 内 class 的 `isInContent` 也返
true（jar 物理放在 `src/main/resources/lib/`），第一步「内容优先」因 `inContent.size == definitions.size`
分不出胜负 ⇒ 加第二步**源码形态优先**（`.java` 胜 `.class`），即 `narrowByContentAndForm`。
配套纠正两条：①我此前用「解包目录」代替 jar 写端到端用例 ⇒ **假绿**（jar 那份根本没进候选），
已用真 jar 重写（`414c6542`），详见 [[pitfall-fake-green-test-assertions]] ㊴；②**「测试环境里
`JarFileSystem.refreshAndFindFileByPath` 恒返 null」的旧结论不成立**——先
`LocalFileSystem.refreshAndFindFileByIoFile(jar)` 让 VFS 认识文件、再取 `path + "!/"` 即可挂上
（当时的失败真因是 jar 刚由 `setBinaryContent` 写入、VFS 没刷新）。

**W6 形态（`1b61964f`，决定性）**：日志显示两个候选**都是 `.java`**——`shadow-entity-demo` 一份 +
`shadow-entity-lib` 一份（用户仍把 lib 当模块加载），**jar 内那份 class 已被平台同 FQN 去重淘汰**
（`PsiShortNamesCacheImpl.getClassesByName` 自己按 `scope.compare` 消解，工程内容胜库）。两边都是内容、
都是源码 ⇒ 前两步全失效。**真正的判据＝模块依赖方向**：demo（宿主所在 jar 的属主）依赖 lib，运行期
demo 自己的输出先于被依赖方 ⇒ demo 那份生效——正是「工程里新增同名类覆盖依赖模块」的运行期规则。
- 「交给平台 `scope.compare` 仲裁」**实测失败**：`ProjectAndLibrariesScope.compare` 对「各 order-entry
  比较结果不一致」返 0，语义不透明，**不能当依赖方向用**。
- 改用**显式**规则：`ModuleUtilCore.getDependencies(module, set)`（公开 API，**会把模块自身也放进集合**）
  判「一方（传递）依赖另一方」，取**依赖方**那份；无关模块/互为依赖 ⇒ 不收窄（fail closed）。
- 收窄重写为 **`narrowAmbiguous` 三步串联：工程内容 → 模块依赖方向 → 源码形态**。顺序有意：先剔库定义，
  模块方向才可能落在「恰好两个模块」上（组内含库定义时模块不可归属，方向无从判定）。
- 删掉原「`module == null` 就跳过平台仲裁」分支——它白白丢掉了可用的模块身份信息。
- 调私有纯函数要**命名参数**：`narrowAmbiguous(list, isContent = { … })`，尾随 lambda 会绑到最后一个形参
  （`dependsOn`）上、编译报 `No value passed for parameter 'isContent'`。

**读数**：红基线 `ShadowModuleOrderTest#testDependentModuleSourceWins` 失败（candidates=2，依赖方向未生效）
→ 修复后 W6 两文件 **7/0/0/0**、回归 4 类 **39/0/0/0**；反向契约 `testUnrelatedModulesStayAmbiguous`
（两模块无依赖）保持歧义。**收口**：`a52d083d`/`414c6542`/`573e379d`(merge)/`e6b1a2b0`/`1b61964f` 均在
master（**未 push**）；分支 `t-w5-reslib` 与工作树已删除。

**沙盒复测链条**：新插件必须同时装进 `.intellijPlatform/.../IU-2024.3.7.1/` 的 `plugins/` **与**
`plugins_runIdeHotSwap/`（用户 `runIde` 实操走的是后者），装前清 `log`/`log-test`/`log_runIdeHotSwap` 里的
`idea*.log` 留干净现场（用户 20261023 明确要求「先删沙盒日志再复测」），核验一律用**字节码标记**
（`preferDependentModule` / `narrowAmbiguous` / `entity-ambiguity` 字符串在场）。细节见
[[pitfall-sandbox-jar-staleness-and-window-enum]] 的 20261023-2 节。
