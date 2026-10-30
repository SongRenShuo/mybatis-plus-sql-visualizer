---
name: project-magpie-merge-master-20261025
description: t-magpie-gems 合入 master 72 笔（2026-10-25，merge 713416bb），冲突与守卫红项的处置记录
metadata:
  node_type: memory
  type: project
  originSessionId: session_baf1a6860046f3231a35b405c5ab4037
---

2026-10-25 在 `F:\code\Java\Gitee\SongRenShuo\mpsv-magpie-gems`（分支 `t-magpie-gems`）把 master（`179cdc86`，72 笔）合入，产出 merge 提交 `713416bb`（双父）。合并后该分支相对于 master 为「落后 0 / 领先 18」，**未 push**。

冲突只有 2 处，都在文档；`src/` 全部自动合并且无冲突：
- `docs/开发计划/20261024-1340-T-TestGateGems-…-开发计划.md`（add/add）取**分支侧**——master 侧副本停在「仅计划」阶段（末节还是「七、一句话」），P-1~P-5 前提核销与实施记录只在分支侧。
- `docs/索引/开发计划.md` 取 **master 侧**——master 是超集（新增 7 条登记），分支侧无独有条目。

合并暴露 3 条测试红，性质不同：
1. `GiantFileBudgetTest` —— **本分支新增的守卫**（master 无此文件），首次与 master 体量对账即红：6 个文件超预算。逐文件测 HEAD/master/merged 行数证明增长全部来自 master 既有代码（合并本身只给 `SqlPreviewPanelExecution` 多 2 行）。按守卫自带流程提额留痕（日期+理由+新增行数），重定基线保留 1.5% 余量——注意有 3 处初值正好差 1 行不满足 1.5%，须逐条核算。
2. `BannedApiSourceScanTest` —— **合并前就红**：本分支 `5776bf23`（T-SelfUpdateRollback）的回滚确认框未进白名单。加白并注明依据（重启级不可逆确认）。
3. `GuardSelfTestCoverageMetaGuardTest` —— **合并前就红**：本分支两个新守卫类未登记。该表 `decisionFns` 类型是嵌套私有 `DecisionFn(name, kind, exempt, via)`（**不是 `List<String>`**，写字符串会编译失败）；`FnKind` 三分 VIOLATIONS/SITES/HELPER。登记时发现 `declaredIconPaths` 只有 SITES 无自转边也无自证引用 → 没有干净豁免路径，改为**补自证断言**（提取器须提出非空站点集合），实质提升守卫强度。

读数：`compileKotlin`+`compileTestKotlin` 绿（3m55s，仅既有警告）；`convention.*` + `ActionAvailabilityMatrixTest` + `SettingsPanelSearchableGuardTest` + `SettingsRealityGuardTest` 共 **108/0/0/0**。未跑全量 `testShards`、`verifyGate`/`verifyPlugin`、真机沙盒走查。

## 全量 testShards（2026-10-25，用户令「跑，有问题修」）

首跑 **8267 例 6 红**，集中在 4 个设置页 UI 测试类。**三组对照定性质**：master(`179cdc86`) 18/0/0/0 全绿；分支(`0fbb43aa`) 与合并后同为 6 红 ⇒ **非合并引入，是分支既有缺陷**（这 4 类是平台 UI 测试，不在 testFast 快照内，分支此前未跑全量故未暴露）。

根因＝T-MagpieGems W2 把「展示脱敏」复选框改成「模式下拉 + 白名单文本域」，两处连带：① 长文案从复选框自身文本挪到**行标签**（GridBag 标签列宽=该段最长标签）⇒ 英文 UI 整页 preferred 838→1042px 溢出视口；② SQL 日志段多一个 JTextArea + 一个 JComboBox ⇒ 按位置取控件的测试取错目标。

修法（`f9341c3f`）：两个测试类改**直接引用面板字段**（不按树里第 N 个 JTextArea 取）；下拉计数 2→3；英文 bundle 两个行标签去掉括号内说明（模式列表由紧邻下拉自明、格式说明移入 tooltip）。**反向验证**：恢复长标签→宽度用例恰红；摘除生产高度保底→分栏用例恰红（均还原、零残留）。

终局读数 **8267/0/0/9**，9 跳过按 skip message 原文归因全为环境门控（Docker 未开 6 / Database 插件不在测试 classpath 2 / Windows 符号链接不可建 1）。未跑 verifyGate/verifyPlugin、真机沙盒走查。

**教训**：分支上「定向+testFast 绿」不等于全量绿——平台 UI 测试（`BasePlatformTestCase`）不在 testFast 桶内，改设置页布局必须跑全量。

相关：[[project-magpie-research-gems]] [[pitfall-guard-invariants-must-be-contrastive]] [[pitfall-assumption-skip-misattribution]]
