---
name: k2j-port-sync
description: k2j 端口分支同步作业法——把 master 的 Kotlin 生产增量手工翻译进 feature/kotlin-to-java（100% Java 端口分支，同步功能不合并分支）。当用户要求「同步 master 到 k2j/Java 端口分支」「kotlin-to-java 增量同步」「端口分支跟版」时使用；覆盖侦察冻结、计划文件、多子代理波次、中央编译收口、测试移植、守卫基线重登、五项门禁与锚点收官的完整 SOP。
---

# k2j-port-sync 端口分支同步作业法

master 的 Kotlin 生产代码**手工翻译成 Java** 移植进 `feature/kotlin-to-java`（独立 worktree，通常在
`F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-kotlin-to-java`）。**同步功能，绝不合并分支**——
master 的 .kt 一行都不进 k2j 生产树；共享文件（构建链/bundle/plugin.xml/CHANGELOG/docs）走直取或三方合并。

## 0. 侦察（开工实测，产出写集清单）

1. 读 k2j 树 `.port-sync` 拿锚点 `synced_master`；`git rev-list --count <锚点>..master` 定增量规模。
2. 增量清单**按目录分别扫**：`src/main/kotlin`、`src/test`、`src/main/resources`、`sub-projects`、`buildSrc`、
   `.github`、docs/record、根级散件——**严禁只扫 kotlin 目录**：master 侧本身有 .java 生产文件
   （如 SqlLogToolWindowFactory），只看 kotlin 目录会漏掉整件（136 轮实证）。
3. `git diff <锚点>..master --name-status` 建 delta 表；docs 面注意 D（删除）/R（改名）形态必须同步处理，
   禁目录级 checkout（会连带覆盖 k2j 专属内容）。
4. 上轮中断遗产排查：`git status`（staged/unstaged 半成品）+ 逐件判向（无临时探针/RED PROBE 才收编；
   先原样入库固化，再按 master 终态刷新）。
5. 范围冻结：目标取开工时 master tip，中途前进不追留下一轮；记入计划文档。

## 1. 计划文件（先计划后实施，用户硬要求）

落 `docs/开发计划/<时间戳>-T-SyncK2JMaster<N>-主分支功能同步-开发计划.md`，必含：
范围冻结（逐件清单）、功能族归组、**写集划分表**（各批互不相交，共享文件裁决归主控）、波次表、
门禁标准、已知坑预案、**待验证前提**（把推断的形态/落点显式列出，实施者先证伪再动手）、
结构性决策保护清单（勿当缺陷「修正」回去的条目）。

## 2. 波次作业（并发上限 3，写集互不相交）

- **波次 0（主控）**：遗产收编笔 + 计划立项笔。提交信息一律写 UTF-8 文件 `git commit -F`。
- **波次 A（3 子代理并行，生产翻译）**：按包/功能族分批，每批写集列死；一律以 `git show <冻结tip>:<path>`
  读 master 终态（禁用主仓工作树，可能被并行会话推进）；**每完成一件立即提交一笔（禁攒批）**，
  且必须 pathspec 限定（`git commit -F msg -- <文件>`）——共享工作树 index 随时有并行暂存，裸 commit
  会卷走他人文件（136 轮两起事故）。子代理禁跑 gradle，编译统一中央收口。
- **中央编译**：compileJava + compileTestJava + compileTestKotlin 三目标；判绿双证
  （BUILD SUCCESSFUL 行 + `echo EXIT=${PIPESTATUS[0]}`——`| tail` 管道退出码是假的）。
- **波次 B（2 子代理，测试移植）**：先查每件在 k2j 的承载形态（.kt/.java 混放，Java 承载件继续 .java，
  禁恢复同名 .kt）；直取件必做互操作三形态 grep（具名实参/record 解构/函数式接口变量赋值）；
  每件跑定向测试（`--tests "<FQCN>"`，禁全量），跑 gradle 前走 parallel_tasks 登记。
- **波次 C（主控）**：资源 union（bundle 双册先比对键集合——k2j 无专属键则整册直取）、plugin.xml、
  agent 子项目 diff 实证后直取、docs 直取刷新、两册（docs/MEMORY.md + docs/变更记录.md）三方合并
  （`git merge-file`，base 取锚点 blob、current 取 `git show HEAD:<file>` 禁取工作树文件、tmp 按册隔离）。
  **直取后必须全量对账**：`git diff <master-tip> --name-only` 逐件判向，抓回被直取误冲的 k2j 专属行
  （索引行/专属文档，136 轮抓回 9 条）。
- **波次 D（主控，单路）**：全量门禁 → 修红 → 收官。

## 3. 翻译硬规则速查（每轮必踩的互操作形态）

- Kotlin 具名实参→位置实参；`object`→`INSTANCE` 或既有静态形态；顶层函数→`XxxKt` 门面类；
  record 访问器**无 get 前缀**；`copy()` 手工重建；`data class` 拆独立文件。
- Kotlin 函数类型字段落 `Function0/Function1/...`（禁 Supplier/Predicate——Kotlin 调用方不做 SAM 转换）。
- 默认参数→显式重载（旧参量重载委托新构造器传默认；注意泛型擦除同疑符冲突时放弃重载直呼全参构造器）。
- **Kotlin 默认参落 Java 双重载后，形态锚点型守卫（functionBody 断言）会命中 1 参委托壳必红**——
  守卫锚点须迁到含真身的全参签名（136 轮 ExplainPanelWiringGuardTest 实证）。
- **箭头 switch 语句穷尽枚举也不满足 javac 明确赋值分析**——`val x = when(...)` 必须落 switch **表达式**+yield。
- 受检异常 override 用既有 `sneakyThrow`；`@Service` 轻服务类显式 `final`；新枚举常量产出写限定形态
  `return EnumName.X;`（守卫按限定形态统计）；elvis 内层 null 合并勿漏（`a != null && a.b != null ? … : …`）。
- Kotlin 属性初始化器引用主构造参数→挪构造器体；`by lazy` 属性→record 无实例字段时用
  WeakHashMap 侧表缓存（键=record 值相等语义，性能预算类测试会抓纯函数现算）。
- fatal 透传写 `throw (Error) t`；catch(Throwable) 新增站点 master 侧为 runCatching 降级则 Java 侧照落 try/catch 即可（基线重登）。

## 4. 守卫基线与扫描根适配

- **fatal 基线三处联动**：`BannedApiSourceScanTest.fatalCatchSiteTotalBaseline`（总量）、
  `BannedApiScanRuleTest` 的镜像未判定基线 + 全量副本——改一处必查另两处，**一律以守卫实测读数登记
  （失败 message 里的 expected/was），禁行计数预判**。落地集新文件按判定点数入 `fatalPassthroughGuardedFiles`。
- 守卫扫描根硬编码 `src/main/kotlin`+`.kt` 的必须适配 `src/main/java`+`.java`（否则命中 0 文件静默假绿）；
  Kotlin 专有判据改 Java 等价形态。
- 新守卫类必须登记 `GuardSelfTestCoverageMetaGuardTest` 的 GUARD_CLASSES（含判定内核 FnKind 与自证语料名）。
- 枚举守卫报「白名单条目已获产出点」→ 从 WHITELIST 删除该条（master 侧本就无）。

## 5. 门禁标准（D 波，单路收口）

1. `testShards --no-configuration-cache` 5 片（禁降片）→ 判绿双证；守卫红逐轮清零后重跑。
2. `:sql-visualizer-agent:test`（纯 Java 面）。
3. testExternal；4. testDatabase（先 `timeout 10 docker info`，down 则登记受限/按用户指示跳过）；
5. verifyGate 三目标 Compatible 与上轮对比零漂移；6. buildPlugin（登记 zip 字节数+sha256）。
跑 gradle 前 `python parallel_tasks.py check/register/done`（**裸 done 会注销全部任务**）。

## 6. 收官清单

1. `.port-sync` 锚点推进到本轮冻结 tip，note 重写为本轮记录（范围/修复笔/基线/门禁读数全文）。
2. 计划文档 §8 实施记录回填（波次产出+提交哈希+事故+坑沉淀）。
3. 台账双行：`docs/索引/开发计划.md` 加本轮行 + `docs/索引/索引变更记录.md` 加日期行。
4. `git status` 复核工作树净；**push 留用户指令**（历轮惯例）。
5. 更新 `~/.zcode/.../memory/project-kotlin-to-java-port-sync.md` 与 MEMORY.md 索引行。

## 7. 高频坑备忘（136 轮实测新增，历轮详单见项目记忆）

- 共享工作树多代理：**pathspec 限定提交是铁律**；开工/收口各查一次 `git log` 分支是否前进
  （以旧 blob 为 current 的合并稿会静默抹掉对方已提交改动）。
- 子代理掉线≠工作丢失：先 `git status`+`git log` 现场审计，半成品质检后续完，勿重做。
- 判测试红前先看报告 XML 的 tests 计数（缓存锁/transform 争用会让 BUILD FAILED 而测试根本没跑）；
  freetype UnsatisfiedLinkError、dbx_test 两败=环境限制复跑排除，不改码。
- gradle 输出中文错误乱码：`| iconv -f GBK -t UTF-8`；并发 gradle 加
  `-Pkotlin.compiler.execution.strategy=in-process` 防 Kotlin daemon 串行化。
- 直取 vs 增量的判据：k2j HEAD 与 master 的差异若**全部**是 master 侧新增（纯落后）→ 整件直取；
  有 k2j 专属行（互操作适配/专属守卫锚点）→ 增量合并保专属。
- delta 外的历轮遗留分歧（约 600+ 测试件是 k2j 适配语料本体）：不扩面追平，靠全量门禁实证抓真破损。
