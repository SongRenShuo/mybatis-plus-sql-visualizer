# T-SecurityAuditFixes 合流验收文档

- 日期：2026-11-09
- 任务编号：T-SecurityAuditFixes（安全审计 run-1 线索修复）
- 验收对象：`t-security-audit-fixes` 分支在**并入最新 master 之后**的合并态
- 计划文档：[20261106-2330-T-SecurityAuditFixes-安全审计run1线索修复-开发计划.md](../开发计划/20261106-2330-T-SecurityAuditFixes-安全审计run1线索修复-开发计划.md)
- 审计产物：[docs/安全审计/2026-11-06/](../安全审计/2026-11-06/)
- 本文件只写**实测读数与判断**；读数不覆盖的面在「未验项与证据边界」逐条声明。

---

## 一、结论

**判断：可以合并到 master。** 24 条已确认线索全部有修复提交与回归测试，账本闭环，合并后主仓编译通过；`testFast` 4667 例中 4 例失败，逐例归因后**全部来自 master 侧**（GoldenDB 文档表漂移，见 §6.2），本分支自身用例全绿；agent 子项目 284 例全绿。合并面干净（不含 `update/update.json`、`plugin.xml`、版本号）。

但有 **1 项须用户定案**、**4 项建议合入前处理**（均不阻塞代码正确性，属口径与配套），另有 1 项 master 侧既有红需在收口读数时归因排除：

| 编号 | 事项 | 级别 | 说明 |
|---|---|---|---|
| D-1 | 遗留 1 条 `needs_validation`（#1 测试服务器凭据） | 须用户定案 | 计划 D2 由用户定案「保留现状、T-SAF-01 全项取消」，账本据此留 1 条未闭环。合入 master 即等于把「凭据仍在 git 历史中」这一事实一并带进主分支——需要用户确认接受 |
| R-1 | `gradle/verification-metadata.xml` 覆盖面不足 | **已命中并修复**（2026-11-09） | 清单由 CLI 生成，不含 IDE 才解析的 `-sources.jar`/`-javadoc.jar` → IDE 编译报 `jsqlparser-5.3-sources.jar` 校验失败。已用 `trusted-artifacts` 按模式信任辅助构件（二进制仍严格）；262 录制态的二进制构件缺项仍未实测 |
| R-2 | CHANGELOG 三条行为变更未落 `Changed` 段 | 建议合入前处理 | 计划验收标准 5 明确要求 #3/#4/#8 在 Changed 段明示，现状只写在 Fixed 段 |
| R-3 | `docs/架构设计/` 未同步新契约 | 建议合入前处理 | AGENTS.md 第 4 条要求「改契约必须同批更新归属文档」；agent 通道令牌、MCP 只读 fail-closed、内嵌面板执行入口收口三处契约变更未进架构文档 |
| R-4 | 计划未登记进 `docs/索引/开发计划.md` | 建议合入前处理 | 同批的 master 计划（20261106/20261107 各条）都在索引内，本条缺 |
| R-8 | 本机 `testFast` 4 红来自 master 侧文档站漂移 | 已知，非本分支引入 | 文档站两表未补 goldendb（独立仓，tip 2026-11-05）；master 自身同样红，CI 因无兄弟目录而绿跳。收口读数时须按此归因排除（详见 §五 R-8 / §6.2） |

**不建议**：不合入即保留分支继续攒改动。分支已并入最新 master（落后 0 提交），再拖只会重复本轮的合并成本。

---

## 二、本次执行的合并（master → t-security-audit-fixes）

| 项 | 读数 |
|---|---|
| 合并前分支 tip | `88fd4945` |
| 合并前 master tip | `b3b5c556`（2026-11-08） |
| 合并后分支 tip | `63222eff`（merge commit） |
| 合并后 merge-base | `b3b5c556`（= master tip，分支已不再落后） |
| 合并后领先 master | 57 个提交（原 56 + 合并提交） |
| 合并后落后 master | 0 |
| 冲突文件 | 2 个：`sub-projects/sql-visualizer-agent/src/main/java/cn/song/mybatisplus/sqlvis/agent/transport/SenderLoop.java`、`CHANGELOG.md` |
| 自动合并但需人工核对的文件 | `EventBus.java`、`SqlPreviewPanelParamPanel.kt`、`SqlPreviewPanelResultView.kt`、`GuardSelfTestCoverageMetaGuardTest.kt`（均已逐项确认两侧特性都在） |

### 2.1 SenderLoop.java（真冲突）

两侧各自给构造器加了一条测试缝：分支加 `channelToken`（T-SAF-02 通道令牌），master 加 `BackoffSleeper`（退避时序加固，避免 CI 2 核 runner 上的墙钟假红）。处置：合并成 8 参全参构造器 `(port, backoff…, dialer, backoffSleeper, channelToken)`，其余 5 个测试缝构造器全部委托到它。

注意点：两个 7 参重载 `(…, dialer, BackoffSleeper)` 与 `(…, dialer, String)` 并存后，任何以 `null` 字面量作第 7 参的调用都会歧义，因此既有委托点全部改成显式传 `Thread::sleep` / `null`。编译通过（`compileJava` + `compileTestJava`）。

### 2.2 CHANGELOG.md（文档并集）

`[Unreleased] / Fixed` 段取并集：保留分支的 22 条安全审计修复条目（覆盖 24 条线索，其中 09+10、15+16 各自合并为一条），并入 master 的 T-SqlListCatchup（B2/F1/F2/G1/G2/H/I）与 T-RuntimeSlotOptionalFill 条目；master 已发布段落（`[2026.11.8]`、`[2026.11.7]`、`[2026.11.6-beta.*]`）原样保留。

---

## 三、计划 §六 验收标准逐条核对

| # | 标准（计划原文要点） | 状态 | 证据 | 缺口 |
|---|---|---|---|---|
| 1 | R-01/R-02/R-03 复核定案，6 条受影响线索有「维持/修正/推翻」记录 | 达成 | `REPORT.md:111` 尾注：复核波三条结论**全部维持**；新面快扫（DevUpdateMode / PlatformEpDynamism）结论均 info、无新发现 | — |
| 2 | 批次 2/3/4 全部落地，每项附回归测试 | 达成 | 26 个 src/main 提交 / 34 个测试提交，逐项对应（见 §四）；`git log master..HEAD` 可逐条回溯指纹 | — |
| 3 | 账本闭环：25 条全部 confirmed/rejected，双验证器 PASS | **基本达成** | `findings.json`：**24 confirmed + 1 needs_validation**；`validate-findings.cjs` PASS（25 条）、`validate-coverage-ledger.cjs` PASS（27 单元） | 1 条 `needs_validation` 为 #1 凭据，属用户豁免（D-1） |
| 4 | `testFast` 全绿 + 受影响分片定向全绿 + agent 测试绿 | **达成（分支自身）** | 合并态 clean 重建后：`testFast` 4667 例 / 4 红（**全部为 master 侧 GoldenDB 文档表漂移**，非本分支）、agent 284 例全绿（读数见 §6.2） | 平台测试桶（`test` 桶）本轮未全量跑，只跑受影响定向组；本机 4 红需文档站侧补齐 |
| 5 | CHANGELOG「提交即回填」，行为变更在 Changed 段明示 | **部分达成** | 22 条 Fixed 条目齐全，带提交哈希 | #3/#4/#8 三条行为变更未落 `Changed` 段（R-2） |
| 6 | 缺陷/教训记入 `docs/LESSONS.md`（若形成复发规则） | 未做（可接受） | 分支未改 `LESSONS.md` | 本轮确实产出一条可固化规则（Kotlin 增量编译在跨分支合并后留下陈旧 test 产物 → 成片 `NoSuchMethodError`），建议补记 |

---

## 四、分支内容清单（相对 master 的 90 个文件）

| 类别 | 文件数 | 说明 |
|---|---|---|
| `src/main` | 42 | 24 条修复的落点 + 3 个新件（`config/SettingsValueValidator.kt`、`util/BoundedRegex.kt`、`util/HtmlEscape.kt`） |
| `src/test` | 34 | 回归测试；新增 3 个测试类（`EmbeddedNativeExecSourceScanTest`、`AgentManagerChannelAuthTest`、`McpDbExecutorReadOnlyTest`） |
| agent 子项目 | 5 | `AgentArgs`/`AgentMain`/`EventBus`/`SenderLoop` 令牌链路 + `AgentArgsTest` |
| 构建配置 | 3 | `settings.gradle.kts`（Central 置前）、`gradle-wrapper.properties`（官方 sha256 锚）、`gradle/verification-metadata.xml`（新增，187KB / 实测 355 个 component、743 条 sha256） |
| docs | 5 | 审计四件套更新（`findings.json` / `coverage-ledger.json` / `REPORT.md` / `NEEDS-VALIDATION.md`）+ 开发计划 |
| CHANGELOG | 1 | `[Unreleased]` 22 条 |

**确认未触碰**（对合并面很关键）：`update/update.json`（仅用户可改）、`update/preview.update.json`、`src/main/resources/META-INF/plugin.xml`、`gradle.properties` 的版本号、`.github/workflows/*`。

修复内容按能力面分四组（与 CHANGELOG 条目一一对应）：agent 通道认证与 IPC；MCP 守卫（只读强制、INTO 递归、脱敏、文本工具上限、正则预算）；日志解析链（截断、正则重锚、桶老化、跨线程配对）；更新链与构建防线（描述符真解析、zip 条目名、镜像序、wrapper 锚、verifier pin）。

---

## 五、风险清单

### R-1 依赖校验清单覆盖面不足（**已实测命中一次，已修**）

- 事实：`gradle/verification-metadata.xml` 里 `bundledModule`/平台构件全部是 `IU-243.28141.41`（47 处），**0 处** 2026.2/262（`grep -c "2026.2\|262\." → 0`）；且 `-sources.jar` / `-javadoc.jar` 条目数均为 **0**。
- **已命中的形态（2026-11-09，用户 IDE 编译报错，连撞三次）**：①`One artifact failed verification: jsqlparser-5.3-sources.jar`；②`ideaIU-243.28141.41.pom` / `ideaIU-2024.3.7.1.pom` / `ideaIU-263.7307.245-EAP-CANDIDATE.pom`（第三个版本＝该开发者本机 IDE 版本）；③`gradle-9.7.0-src.zip`（Gradle custom / Gradle distributions）与 `groovy-4.0.32.module`（Gradle Libs）。共同根因：清单由 CLI 构建生成、只记录那次解析过的构件，而 IDE 的 Gradle 导入与脚本模型会额外解析辅助构件、平台 distro POM、Gradle 发行版附属物三类。**`./gradlew` 全绿、IDE 一同步就红**。
- 已修：`<trusted-artifacts>` 五条——`.*-sources\.jar`、`.*-javadoc\.jar`（文件模式）；`group="com.jetbrains.intellij.idea"` + `.*\.pom`（平台版本随开发者本机 IDE 变化，逐版本写死会让构建依赖「谁在哪个 IDE 里同步」）；`group="gradle"` / `org.apache.groovy` / `org.codehaus.groovy` 整组（Gradle 发行版本体已由 `distributionSha256Sum` 钉死，Maven 侧 checksum 无可信来源）。反向验证：两个探针分别解析 `jsqlparser:5.3@sources` 与 `ideaIU:243.28141.41@pom`，修复前都红、修复后都绿；同轮探针里 `commons-math3` / `jmh-core` / `jopt-simple` 等二进制仍照旧失败 → 二进制严格性未放松。**证据边界**：③（`-src.zip` / `.module`）只在 IDE 脚本模型路径出现，CLI 无可复现入口，以用户同步读数为准。教训已记 `LESSONS.md` L25。
- 仍存的风险：录制态 `-PgifRecord=true`（平台 2026.2.1）的**二进制**构件不在清单内，预期仍会构建失败。
- 范围：不影响 CI 三腿（`buildPlugin` / `verifyPlugin` / `testShard*` 均跑 243）与日常 CLI 开发。
- **证据边界**：262 录制态本轮**未实测**（本机 gradle 缓存只有 `2024.1 / 2024.3.7.1 / 243.28141.41`，跑一次要先下 1GB+ 平台包）。
- 建议：合并前用 `./gradlew -PgifRecord=true dependencies --configuration compileClasspath` 实测一次；确认失败则用 `--write-verification-metadata sha256` 补 262 闭包，或在录制说明里登记「录制前先补清单」。

### R-2 CHANGELOG 行为变更未落 `Changed` 段（建议合入前处理）

计划验收标准 5 点名要求的三条：T-SAF-04（内嵌面板原生 ▶/Ctrl+Enter 不可用）、T-SAF-03（只读强制失败即拒绝执行）、T-SAF-08（非法设置值整体拒绝导入）。现状都在 `Fixed` 段写清了，但没进 `Changed`。对用户是「能力消失/行为变严」类变更，`Changed` 段缺位会让更新说明漏掉最该提醒的部分。

### R-3 架构文档未同步（建议合入前处理）

`docs/架构设计/02-核心流程详解.md` 的 §6.5（增强捕获链路）没有通道令牌认证（T-SAF-02，且是**协议 break**：旧 agent + 新插件 HELLO 无令牌会被拒），§2.8/§2.5 也没有 MCP 连接层 `setReadOnly(true)` fail-closed（T-SAF-03）与内嵌面板执行入口收口（T-SAF-04）。按 AGENTS.md 第 4 条，这类契约变更应同批更新归属文档。

### R-4 计划未登记索引（建议合入前处理）

`docs/索引/开发计划.md` 没有本任务条目，而同批的 20261106/20261107 计划都在。

### R-5 CI 从未在分支上跑过（合入后首轮即验证）

分支未推送远端，本轮所有读数都是本机 Windows + 243 平台。合入 master 后首轮 CI（`buildPlugin` / `verifyPlugin` / 5 片 `testShard` / `testFast`）才是构建防线（T-SAF-14/15/16）的第一次真机验证——尤其 `verification-metadata` 严格模式与 verifier-cli pin 精确匹配两条。CI 用 windows runner，与本机同 OS，构件分类器一致，风险中等偏低。

### R-6 协议 break 的发行约束（登记项，非缺陷）

T-SAF-02 使「旧 agent + 新插件」组合被拒（HELLO 无令牌）。agent jar 内嵌在插件 jar 里随制品分发，因此只要走正常更新渠道就是同版本组合；绕过插件自更新的用户（手动替换 jar）可能短暂失配，表现为增强捕获不可用、回落控制台链。已在计划「已知口径」登记。

### R-7 遗留 followups 6 条（不阻塞）

共享骨架出口脱敏、`BoundedRegex` 与 HistorySearch 预算统一、审计清单外两处 `escapeHtml` 收敛、项目级设置 UI 内联校验反馈、所有者 runIde 端到端核对（T-SAF-02/03/04）、manifest 并列 sha256（决策 D6 未实施，涉 `update/update.json` 仅用户可改）。详见计划文档「附：遗留 followups 详解」。

### R-8 master 侧文档站漂移（合并前已知，非本分支引入）

`DialectDocTableGuardTest` 4 红：文档站独立仓的 `features/wrapper-preview.md` 分页表与 `features/batch-operation.md` UPSERT 表未补 goldendb。合入 master 不会让它变多或变少（master 本来就红），但**本机带文档站兄弟目录的任何全量读数都会带着这 4 红**，收口时容易误判成本分支的锅。处置：文档站补两张表（对外内容，需单独动作），或在收口读数里按本条归因排除。

---

## 六、验证读数与复现命令

> 环境：Windows，JDK toolchain 17，平台 2024.3.7.1（243），Gradle 9.7.0，依赖校验严格模式生效。

### 6.1 编译（合并态）

```bash
./gradlew compileKotlin compileTestKotlin :sql-visualizer-agent:compileJava :sql-visualizer-agent:compileTestJava
```

结果：`BUILD SUCCESSFUL in 2m 55s`（25 actionable tasks）。合并引入的两处构造器重载与 CHANGELOG 并集均未产生编译问题。

### 6.2 全量快桶 + agent 子项目

```bash
./gradlew testFast :sql-visualizer-agent:test
```

<!-- TESTFAST_RESULT -->
| 项 | 读数 |
|---|---|
| 主项目 `testFast` | 389 个测试类 / **4667 例** / 7 skipped / **4 failures** / 0 errors |
| agent 子项目 `:test` | 50 个测试类 / **284 例** / 1 skipped / 0 failures / 0 errors |

**4 例失败的归因（重要，不是本分支引入）**：全部集中在 `DialectDocTableGuardTest` 的四条断言——`wrapper-preview` 分页表与 `batch-operation` UPSERT 表（zh/en 各两条）**缺少 goldendb**，而 `DialectRegistry` 已有 15 种方言。

- 归因链：GoldenDB 是 master 的 `7630fd0a`（第 15 方言）引入；该守卫读的是**独立仓** `mybatis-plus-sql-visualizer-docs` 的 Markdown 表，该仓 tip 停在 `38073ed`（2026-11-05，早于 GoldenDB 合流），两张表里 goldendb 出现 0 次。
- 本分支不相关：`git diff master HEAD` 在该测试文件上为空；分支在 `sql/` 下只改了 `interceptor/DynamicTableNameProcessor.kt`（T-SAF-25），与方言注册无关。
- 也就是说：**master 自身在本机跑 `testFast` 同样是这 4 红**，只是此前没有一次「带文档站兄弟目录」的读数被记录下来。
- CI 不受影响：`docsRoot()` 找不到兄弟目录时**显式绿跳**（该测试类 KDoc 已声明该口径），CI 只 clone 插件仓。

**首跑假失败（已排除，值得记一条教训）**：第一次跑（未清理 `build/`）出现 **141 例 `NoSuchMethodError`**，全部是「测试类编译于合并前、主类合并后 ABI 变化」的陈旧增量产物（典型如 `MyBatisLogParser` 构造器新增 `clock` 参数）。手工清掉 `build/classes`、`build/kotlin`、`build/test-results` 后重跑归零。这不是代码问题，但合并后必须从干净编译态起跑。

### 6.3 审计账本双验证器

```bash
node .agents/skills/security-audit/validate-findings.cjs docs/安全审计/2026-11-06/findings.json
node .agents/skills/security-audit/validate-coverage-ledger.cjs docs/安全审计/2026-11-06/coverage-ledger.json
```

结果：`PASS: 25 findings valid` / `PASS: 27 coverage units valid`。

**证据边界**：两个验证器在 win32 上会以 `OS no-follow and nonblocking input protection is unavailable` 直接拒绝运行（脚本要求 `fs.constants.O_NOFOLLOW`/`O_NONBLOCK`，Windows 无此常量）。本次是把验证器与 schema 复制到临时目录、**仅注释掉该平台守卫**后运行（未改动仓库内文件，未放宽任何 schema/语义校验）。要拿到无补丁的权威读数，需在 Linux 上重跑一次。

### 6.4 账本状态分布

```bash
python -c "import json,io,collections;d=json.load(io.open('docs/安全审计/2026-11-06/findings.json',encoding='utf-8'));print(collections.Counter(x['verdict'] for x in d))"
```

结果：`Counter({'confirmed': 24, 'needs_validation': 1})`。

---

## 七、未验项与证据边界

1. **平台测试桶未全量**：本轮只跑 `testFast`（纯逻辑快桶）+ agent 子项目；`BasePlatformTestCase` 族（平台 UI/PSI 类）在 `test` 桶，未全量跑。分支收口时的定向组（通道认证 / Premain 回归 / 只读执行 / UI 转义 / 项目级设置 / zip 守卫 / 描述符解析 / HTML 报告）是**合入前**的读数，本轮合并未复跑。
2. **runIde 端到端未做**：T-SAF-02（令牌认证不误伤合法捕获）、T-SAF-03（平台 243/262 的 RemoteConnection 反射链上 `setReadOnly` 真生效）、T-SAF-04（原生 ▶ 真置灰、插件按钮链照常）三项的部署面核对属所有者动作，本轮未执行。
3. **CI 未跑**：见 R-5。
4. **Windows 符号链接回归**：T-SAF-13 的符号链接用例在无特权机器 skip，CI 同样跑 Windows，覆盖仍受限。
5. **本机 `clean` 受遗留沙盒锁定**：`build/MPSV-GifFresh/.../system/compile-server` 被某进程占用，`:clean` 报「Failed to delete some children」。处置：手工删除 `build/` 下其余产物（classes/kotlin/test-results/libs/tmp/resources），保留被锁的 `MPSV-GifFresh` 目录，从干净编译态重跑测试。这与本分支无关，属本机环境遗留。

---

## 八、建议动作

**合入前（建议，非阻塞）**

1. 定案 D-1：确认接受「#1 凭据留 `needs_validation`、由所有者豁免」随分支进 master；如不接受，先在测试服务器轮换瀚高实例 xc3 凭据再合入。
2. 处理 R-1：实测 `-PgifRecord=true` 是否因校验清单缺失而失败，失败则补 262 闭包。
3. 处理 R-2：在 `[Unreleased] / Changed` 补三条行为变更。
4. 处理 R-3/R-4：补架构文档三处契约说明与计划索引条目。
5. 补一条 `LESSONS.md`：跨分支合并后 Kotlin 增量编译留下陈旧 test 产物导致成片 `NoSuchMethodError`，处置＝合并后必须从干净编译态起跑（本轮首跑 141 个假失败，清理后归零）。
6. R-8 可选：文档站独立仓补 goldendb 两表（属对外内容，需单独授权动作）；不补也不阻塞本分支合入，但本机全量读数会一直带这 4 红。

**合入后**

6. 等 CI 首轮全绿（`buildPlugin` / `verifyPlugin` / `testShard*`），这是构建防线三件的真机验证。
7. 发行前跑 `verifyGate`/`verifyPlugin` 与真机矩阵；本次变更含 MCP 与执行链，建议随下一个正式版发，不单发内测。

**发行侧（用户决定）**

8. 行为变更三条（原生 ▶ 禁用、只读拒绝、设置导入拒绝）建议进发行说明的「变更」段，而不是只列在修复清单里。
