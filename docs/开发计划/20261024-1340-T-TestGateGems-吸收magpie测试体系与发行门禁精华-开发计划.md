# T-TestGateGems：吸收 magpie 测试体系与发行门禁精华（开发计划）

**创建时间**：2026-10-24 13:45
**触发源**：用户指派——「深度分析 magpie 是怎么自动化测试保证所有功能无误才发行的，特别是 UI 交互相关的问题，吸收精华」。
**调研对象**：github.com/yetone/magpie（MIT，本地 `F:\code\Java\Github\other\magpie`，tip `fa22bc64`，1349 tag）。
**配套报告**：[20261024-1340-magpie-测试体系与发行门禁-调研报告.md](../内部/调研报告/20261024-1340-magpie-测试体系与发行门禁-调研报告.md)（全部结论带 magpie 侧 `文件:行号` 证据）。
**前序计划**：[20261023-2311-T-MagpieGems-magpie精华吸取-开发计划.md](20261023-2311-T-MagpieGems-magpie精华吸取-开发计划.md)（架构与工程文化线，W1~W7；本计划是其中「测试/门禁」专题的展开，编号不冲突）。
**基线**：`master@7731d395`（前序计划提交后 tip）。
**状态**：计划待拍板。**未占 O/R/D/V 编号**。

---

## 一、结论先说：magpie 的答案不是「测得多」，是「把能机械判定的全部机械化」

实测四层拦截（详见报告 §一）：①UI 约束的 Go 守卫（**硬**，进 CI）②原生外壳纯函数/子进程测试（**硬**，进 CI）③849 个浏览器回归 127k 行（**软**，本地手工）④ui-preview 自动录屏（**软**，PR 证据）。

**最反直觉的一条**：`docs/code-standards.md:78` 原文承认「CI doesn't run this suite, so it is run locally」——那 849 个用例**从设计上就不在 CI 里**。真正拦在发行前的是第①层几十个秒级 Go 守卫。这个取舍是本计划要搬的核心。

---

## 二、待验证前提（实施前先证伪，别让计划建在猜测上）

- **P-1（已部分核实）**：本仓已有 97 个守卫类测试，且已有源码扫描基建 `BannedApiScanSupport`（`src/test/kotlin/.../convention/BannedApiSourceScanTest.kt`）。**但**：`BundleParityGuardTest` 已覆盖「bundle key 全等 + 占位符形态 + 源码/plugin.xml 引用 key 存在」三项；**「硬编码中文字面量扫描」与「图标资源存在性」两项未发现等价物**（grep 命中均为注释或无关类）。实施 W1/W2 前须逐项 grep 确认，若已有则跳过或收窄为补测试。
- **P-2（未核实，风险最高）**：「动作可用性矩阵」（`ActionManager.getAction(id).update()` 后断言 `isEnabled`/`text`）在本仓**零先例**——`grep ActionManager|isEnabled` 在 `src/test/kotlin` 无命中。这条依赖「headless 测试里 `ActionManager` 是否可用、`update()` 是否需要 EDT/是否需要已打开的编辑器上下文」。**必须先写一个最小探针验证**，验证不过就整条撤掉，不要硬做。
- **P-3**：本仓 UI 是否全是 Swing/JB 组件、有无 JCEF/HTML 面板。若有 JCEF，报告 §三的浏览器范式可部分搬；若无（纯 Swing），则第①层守卫 + 快照断言是唯一路线。需先确认。
- **P-4**：`docs/规范/测试沙盒验证清单.md` 里哪些条目**其实已可 headless 判定**（例如「设置页状态行文案」「空态三行完整可见」「工具窗最窄换行」）——需逐条过一遍，才能算出「下沉后能砍掉多少人工验收」。别按印象估。
- **P-5**：读者门禁是否真的缺「验证 HEAD sha 比对」机制。需确认 `dev-support/` 现有脚本是否已有等价物（前序 memory 提过 `push-all.ps1` 双仓推送，未提 sha 校验）。

---

## 三、产品/测试代码线（动 src/test，不动 src/main）

### W1（最高优先）：三类 UI 腐化守卫下沉（照 magpie 六条守卫的空缺项）

magpie 出处：`core_budget_test.go:22-50`、`builtins_test.go:191-244/246`、`intl_lang_test.go:17-38`、`purpose_asset_test.go`、`web_compress_test.go:109`（详见报告 §二）。

- **W1-a 硬编码 UI 中文字面量扫描**（等价 `TestDatesFollowGUILanguage` 的正则扫描思路）：扫 `src/main/kotlin` 里 `JLabel("`、`JButton("`、`action.text = "`、`JCheckBox("`、`JBLabel("` 后直接跟中文字面量的位置，要求走 bundle。**必须配白名单**（注释说明为何豁免），且白名单本身要能被审计——照 magpie 的做法在常量旁写理由。
  - 反向验证：造一个 `JLabel("测试中文")` 用例 → 必须红；再从白名单里删一条 → 对应豁免点必须红。
  - 风险：误报率高（现有代码里可能大量存在）。**先跑一次只统计不判红**，拿到真实命中数再决定是「全量修复」还是「白名单压住存量 + 只拦新增」（推荐后者，与 `SettingsPanelSearchableGuardTest` 的白名单台账同构）。
- **W1-b 图标资源存在性守卫**（等价 `TestBuiltinClientsLoaded` 的 hash 校验部分）：解析 `plugin.xml` 里所有 `icon=` 与 `<action>` 的图标引用，断言资源文件在 `src/main/resources` 里真实存在；再加一条「资源文件必须被至少一处引用」（防僵尸资源，对应 magpie 的 `n == 0` 防空跑）。
- **W1-c 单文件体积预算**（等价 `TestCoreScriptBudget`）：给已知巨型文件（先用 `wc -l` 排序取 top 10 定基线）设行数上限，**常量注释逐条记录提额理由与日期**，错误信息给两条出路（「拆出去」或「提额并说明为什么」）。这条的价值观比实现重要：预算不是拦增长，是**让每次增长都必须留一句理由**。
- **W1-d 生成物一致性**（若 P-1 发现有代码生成链）：断言生成物与源一致，不一致时报「run xxx 重新生成」。

### W2：外观配置参数化断言（照 `TestPanelOptionsCaptionButtons`）

magpie 出处：`panel_options_test.go:14-38`（详见报告 §三）。

- 把「窗口/对话框/面板的外观与尺寸配置」抽成返回数据结构的纯函数（如 `createDialogConfig()` / `panelFrame()`），在 headless 测试里断言字段——**不起 IDE 窗口**。
- 本仓候选落点：`DialectGateExitPolicy`（已有测试，可补字段断言）、`ExplainPlanDialog` 的尺寸/按钮配置、预览面板结果行横幅的布局参数。
- **实施前先找现有可抽的纯函数**：如果相关配置散落在构造器里，抽取本身是重构（动 src/main），需单独评估收益。**不为了凑这条而强行重构**。

### W3：动作可用性矩阵（依赖 P-2 探针，可能整条撤掉）

magpie 没有等价物（它用浏览器套件覆盖同类问题），这是**本仓比 magpie 更容易做的一条**——IntelliJ 的 `ActionManager` 是天然 headless 面。

- 先写最小探针：`ActionManager.getInstance().getAction(id)` → `update()` → 断言 `isEnabled`/`getTemplatePresentation().text`。验证 headless 可用后，再给工具栏 9 枚动作 + 右键菜单动作建矩阵（「有选中表 → 可用」「无选中 → 灰」这类）。
- 若探针失败（需要 EDT 上下文/需要真实编辑器），**整条撤掉并如实记录「headless 不可行」**，不硬做。

### W4：验收报告「not run」硬条款（流程，零风险）

magpie 出处：`code-standards.md:38-40`「A check that wasn't run is listed as not run, never left out」。

- 写进派活模板与收口报告格式：子代理报告必须逐条列「跑了什么 / 读数多少 / 哪些没跑（not run）及原因」。**没跑的不能省略**。
- 这条是本计划里成本最低、收益最直接的一项。

---

## 四、流程线（不动代码）

### W5：门禁读数时效性机械化（依赖 P-5）

magpie 出处：`code-standards.md:108-112` 的 `gh pr merge --match-head-commit <reviewed sha>`——合并点必须等于验证过的 commit，验证后再 push 就合并失败。

- 读者对应动作：收口脚本记下「验证时的 HEAD sha」，push/合流前比对；不一致就强制复跑并说明。
- **先确认现有脚本是否已有**（P-5）。若无，实现成本约几十行 PowerShell。

### W6：UI 变更的自动证据链（借鉴 ui-preview，落到 ide-demo-gif）

magpie 出处：`.github/ui-preview/` 全套（详见报告 §五）。按投入从低到高：

- **W6-a 禁用词表**（成本极低，立即可做）：`ide-demo-gif` 重放器执行每个点击前读控件文本，命中 `退出|删除|移除|更新|重启|卸载|Invalidate Caches|Update Plugin` 就中止并记一条。照抄 magpie `FORBIDDEN` 正则。
- **W6-b 产物自带局限声明**：GIF 说明里写「本演示在 runIde 沙盒录制，X 行为未覆盖」——等价 `unseen` 字段。防的是「截图里没看到 → 被误读成功能不存在」。
- **W6-c plan 入库 + 确定性重放**：演示脚本从「人手写坐标」改为「LLM 生成一次语义 plan（JSON）→ plan 入库 → 之后纯确定性重放」。等价 magpie 的 `PLAN_FILE` 机制。**LLM 的不确定性只发生一次。**
- **W6-d 可定位树喂 LLM**：用 `UIUtil.uiTraverser()` dump `类名[accessibleName] "文本"` 缩进树，让 LLM 产出语义脚本，再由图像锚点层落地坐标。**这条收益最大但工作量也最大**，建议 W6-a/b/c 先落地验证价值后再做。

---

## 五、实施排期

| 批次 | 内容 | 依赖 | 风险 |
|---|---|---|---|
| 第一批 | W4（not run 条款）+ W6-a（禁用词表）+ W6-b（局限声明） | 无 | 零，纯流程/小改 |
| 第二批 | W1-a 先只统计（不改判红）+ W1-b + W1-c | P-1 核实 | 低 |
| 第三批 | W1-a 正式落判红（白名单压存量）+ W2 | P-1/P-3 | 中（白名单台账维护成本） |
| 第四批 | W3（动作矩阵，先探针）+ W5（sha 校验） | P-2/P-5 | W3 可能整条撤 |
| 后置 | W6-c / W6-d | W6-a/b 验证价值 | 高工作量 |

**并行性**：W4/W6 不动代码，可与 W1 并行；W1 各子项同属测试目录但不同文件，可双车道。

---

## 六、明确不吸收的

- **849 个浏览器回归套件本身**：本仓 UI 是 Swing/JB 组件，无「真实页面 + 假后端」的天然边界（P-3 若发现 JCEF 再重估）。
- **双引擎 × 四语言 × 多宽度矩阵**：本仓只有中英两语言、无引擎维度；宽度维度可部分借鉴（工具窗最窄场景已有清单条目），但不建矩阵。
- **AppKit 子进程自我 exec**：macOS 专属，Windows/Linux 场景无对应物。
- **`release.yml` 的 repository-dispatch 双仓发行**：读者已有自己的发行链（`releaseGate` + 双仓推送），领域不同。
- **magpie 的「每日多次发行」节奏**：依赖其 CI 与 LLM 预算，不照搬。

---

## 七、一句话

本计划要搬的不是「更多测试」，而是 magpie 那条取舍原则：**能机械判定的 UI 规则全部下沉成秒级守卫进日常门禁，剩下的必须人工的部分强制留可审计记录，且明确登记哪一层测不到什么。**
