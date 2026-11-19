# T-Nplus1Aggregate：N+1 嫌疑徽章聚合视图「点开看全」（D235）开发计划

- **立项**：2026-11-19 08:25，用户拍板 D235 立项（第四十二轮调研册）；本册＝实施计划，分支 `t-nplus1-aggregate`（自 master `0a82f410c` 起），工作树 `.worktrees/t-nplus1-aggregate`。
- **红线**：不新增工具窗/面板；聚合判定**必须复用** D216 既有口径（线程+60 秒滑动窗），禁另起一套；不动 `update/update.json`；不 push。

## §一 关键锚点（20261119 08:20 实测，master）

| 锚点 | 事实 |
|---|---|
| `log/NplusOneSuspect.kt` | internal object；`REGISTRY_KEY`/`THRESHOLD_KEY`/`DEFAULT_MIN_COUNT`/`DEFAULT_WINDOW_MS`；`isSuspect(count, threshold)` 纯函数；KDoc 明示计数源＝既有同模板聚合 `structureKeyCounts` |
| `log/SqlLogPanel.kt:1965~1969` | 徽章消费点：`entryCellRenderer.nplusOneSuspects = manager.entries.windowSuspectKeys(..., windowMs = NplusOneSuspect.DEFAULT_WINDOW_MS, threshold = ...)` |
| `log/SqlLogEntryBuffer.kt` / `log/RailCellRendererBase.kt` / `log/LogEntryRendering.kt` / `log/SqlEntryPresentation.kt` | D216 徽章渲染与数据面落点 |
| `SqlLogPanel.kt`（2768 行，巨文件） | 新增逻辑能拆小文件的拆（F-13「单一纯比较器」先例）；聚合视图做**过滤态**，不加新面板 |

## §二 设计要点

1. **交互**：点击带红色 N+1 嫌疑徽章的条目（rail 徽章命中区）→ 列表进入过滤态：只显示该 `structureKey` 的条目组，顶部一行聚合摘要（模板条数 / 首末时间 / 线程名单；跨线程组如实标注「分散在 N 个线程」）；「返回全部」按钮/动作退出过滤态。Esc 也退出。
2. **口径一致性（生命线）**：过滤态的分组键＝徽章判定用的同一个 structureKey；**聚合组内条目数必须等于该键在完整列表中的条目数**（一致性断言钉死）。徽章在、点开组空＝缺陷。
3. **过滤态与既有筛选并存**：若面板已有文本过滤/级别过滤，进入模板过滤态时叠加（AND）并在摘要行注明当前过滤条件；退出恢复原状。
4. **渲染**：摘要行复用 JBLabel 单行 `｜` 分段纪律（`\n` 不渲染坑）；模板 SQL 折叠态可展开（复用既有条目展开形态，不造新组件）。

## §三 待验证前提（先证伪再动手）

1. 徽章是画在 rail（`RailCellRendererBase`）上的——rail 的点击命中判定现状有没有可复用的 mouse listener（iBatis gutter/跳转先例）？没有则给 rail 加一次性点击检测，命中区=徽章绘制矩形。
2. `windowSuspectKeys` 返回的键形态与条目 `structureKey` 字段可直接对上的依据（读 buffer 代码确认，勿凭 KDoc 转述）。
3. 过滤态走 JList filter 还是重建 model：先看面板既有筛选的实现形态，跟既有形态走。

## §四 验收清单（可照抄）

| # | 用例 | 期望 |
|---|---|---|
| A1 | 纯逻辑：同线程 60s 内 6 条同模板不同参数 → `isSuspect` 真 + 过滤模型恰好 6 条 | 组内条目数==完整列表中该键条目数 |
| A2 | D216 负例集复用：同模板分散多线程各一条 → 无徽章、无过滤组；阈值调 10 后 6 条不再出徽章 | 双负例成立 |
| A3 | 一致性：徽章桶数 == 过滤态组数（断言） | 恰等 |
| A4 | 反向验证：摘掉「点击进入过滤态」接线 → A1 的 UI 用例变红（两次读数贴报告） | 反向必红 |
| A5 | 过滤态进出：进入→「返回全部」→列表恢复完整且滚动位置不丢 | 恢复态断言 |
| B1 | 定向测试：新增测试类 + 既有 D216 相关测试类（`--tests` FQCN 列表）全绿 | 0 failed；全量未跑＝not run 声明 |
| C1 | 真机 GUI 验收（用户）：循环 6 次查询出徽章、点击出聚合组、Esc 退出 | 用户点头 |

回退开关：Registry `sqlvis.log.nplusOneSuspect.aggregate`（默认开，关=徽章纯行级、点击无过滤态），plugin.xml `<registryKey>` 登记。

## §五 实施纪律

- 每完成一件可提交的就立即提交（节点：锚点核实→判定/过滤模型纯逻辑→UI 接线→守卫/bundle→定向收口）；只 add 明确路径禁 `-A`。
- gradle 前先 `python C:/Users/USER/.zcode/cli/parallel_tasks.py check`，本任务 `register --name t-nplus1-aggregate --sandbox t-nplus1-aggregate`；跑完 `done --name t-nplus1-aggregate`（禁裸 done）。
- 只跑定向测试（`--tests "<FQCN>"`），禁全量、禁构建校验、禁 clean、禁 `--stop`。
- 报告须回答：定向读数、反向读数、提交哈希清单（按时间序）、收口时树是否干净。

## §六 实施记录

- **20261119 08:25～10:40（车道变更：子代理两度失败于并发/配额限额，主控接手收口）**：子代理已完成实现与测试落盘但未提交、未跑测试；主控审查后接手，修完下列五项后定向 14/14 全绿并提交 `cc8b2d960`（11 文件 666 行）。
- **§三.1 前提证伪（子代理实测）**：徽章不在 rail 矩形上，而是元信息行 `headerLine` 的文本片段——实现改「片段 tag + `SimpleColoredComponent.getFragmentTagAt(x)` 片段级命中」，并用 `SqlTextFragment.tag` 透传（含 `clipSqlFragments` 裁剪保留 tag）。
- **§二.3 形态选择**：过滤复用既有 `FocusTab.Template`（右键「只看同结构」同一过滤器），未另造过滤机制。
- **主控接手修复清单（均为子代理未跑测试留下的真实缺陷）**：
  1. `NplusOneAggregate.REGISTRY_KEY` 读取漏反引号 → `Registry.is` 是 Kotlin 关键字，编译失败；改 ```Registry.`is`(...)```。
  2. 摘要断言硬编码英文文案（受测试 JVM locale 影响）→ 改 bundle 同源构造期望 + 参数前导前缀判据（跨语言稳健）。
  3. 面板用例缺 EDT 泵：刷新拍重建链分批 `invokeLater` 追加模型，单拍刷新后模型仍空 → 加 `pumpEdtUntil` 轮询。
  4. 条目匹配用引用相等（`===`）→ 刷新拍重建模型后实例不同，改值匹配（`restoredSql`+`timestamp`）。
  5. 测试经 `list.cellRenderer` 取渲染器 → 平台包 `ExpandedItemListCellRendererWrapper`，cast 抛 CCE；面板补 `renderEntryForTest` 缝。**并用 `model` 里的实例渲染**（入队分发的 `#N` 序号影响 header 宽度，2px 偏差即命中失败——本轮最隐蔽的一条）。
- **定向读数**：`NplusOneAggregateSummaryTest` 8/0/0/0；`SqlLogPanelNplusOneAggregateTest` 6/0/0/0（全量未跑＝not run）。
- **反向验证**：摘掉 `tryEnterNplusOneAggregate` 内 `addFocusTab` 接线 → 点击进入/返回全部/Esc 三条恰红，回退开关/非徽章区/接线守卫三条保持绿（探针精度良好）；探针已还原，树干净。
- 待办：真机 GUI 验收（用户）；`SqlLogPanel` 已 2900+ 行，本批新增逻辑已尽量外置到 `NplusOneAggregate.kt`/`NplusOneBadgeClick.kt` 两个小文件。
- **20261119 11:05 CI 红 → 修复（`846c66f3f`）**：合流推 master 后 run 44447731812 分片 1/2 各 1 红——`SqlLogPanelRequestFocusTest`（expected 10 was 12）与 `SqlLogPanelFocusTabTest`（expected 4 was 6）。根因：摘要 label 与「返回全部」按钮被无条件挂进 `focusTabBar`，而既有守卫按「前缀标签 + N chips + 计数标签」断言组件数。修法不改期望：新增 `nplusOneSummaryBar` 独立一行挂在页签栏之下，页签栏恢复原构成。复验 FocusTab 4/0/0/0 + RequestFocus 3/0/0/0 + Nplus1Panel 6/0/0/0 + Summary 8/0/0/0。教训入 `docs/LESSONS.md` L85（改共享容器必跑该容器既有守卫类）。
