---
name: pitfall-refactor-move-missing-line-check
description: 大重构搬运后合并的两条方法学坑——dev 特性需逐处移植进新文件；「逐行在场检查」用 grep -F 全包匹配会被同包其他文件同名行骗过
metadata:
  node_type: memory
  type: project
  originSessionId: sess_2b5062e9-21ee-418e-83b4-1ca64f07a665
---

T-MergeMasterToDev3（2026-10-12）实证两条：

**① 对端大重构（拆分/外移）后的合并，工作重心是「把本侧特性移植进对端的新文件」，不是解冲突本身**：
master 的 T-M53-II 把 SqlPreviewPanel/SqlLogPanel/SqlPreviewService/PluginSettingsPanel/AgentManager 的成员
逐字符搬进同包新文件（行为不变）⇒ 冲突块常常是「dev 的整段 vs master 的空/一行声明」，取 master 侧后
**dev 的改动会随之消失**，必须按特性逐处移植回新文件。判据：`git diff <merge-base> dev -- <file>` 列出
dev 的全部改动 → 逐项在合并树里找落点（找法：按特性名 grep 对端新文件；`SqlPreviewPanel{Execution,…}.kt`
这类拆分文件就是落点）。移植进扩展函数文件时注意加 `SqlPreviewPanel.` 接收者与跨文件可见性。

**② 「逐行在场检查」会假绿**：本轮的检查是「把 dev 新增行去重后逐行 `grep -rF` 整个 ui/log 包，缺哪行就移植哪行」
——它漏掉了 **R60 日志列表脱敏**（dev 的 `DisplayMasker.maskDisplay(entry.summary, maskOn)` 行在**历史页**
`HistorySqlLogPanel.kt` 里有同名行 ⇒ 检查通过，但**日志页**的 `LogEntryRendering.kt` 里没有 ⇒ 测试红）。
即：**`grep -F` 全包匹配证明不了「这一行在它该在的文件里」**。改法：按「文件 × 行」比对（对每个候选落点文件
单独查），或直接以门禁红为准反查。同轮另一处同源：R51 生产接线守卫硬编码只读主文件，拆分后读取面必须改
「面板全体源文件并集」（照 `ExplainPanelWiringGuardTest`/`PrepareSingleFlightGateTest` 的 `panelUnion()` 先例）。

**③ 自查「本侧独有行」必须用合并前 tip 作参照**（T-MergeMasterToDev4 2026-10-13 实证）：合并**提交后**
`dev` 这个分支名已指向合并提交本身，`git diff master dev` / `git diff dev -- <file>` 等于拿合并结果跟自己比
（全空或方向反转）⇒ 会误判「对端改动全丢」。正确参照＝**合并前的本侧 tip**（如 `git diff --numstat e7f6947 -- <file>`）。

**④ 第 0 条陈旧测试类在合并后高发**：对端改了带默认参数的数据类（如 `PluginSettings.State` 加字段）后
跑 `testShards`，若测试类未随主源重编译 ⇒ 成片 `NoSuchMethodError: ...State.<init>(...)`（T-MergeMasterToDev4
首跑 645 红中 618 属此类）。跑门禁前先 `compileTestKotlin --rerun-tasks`，或见成片 NoSuchMethodError 直接补跑。

**Why:** 大重构合并的失败模式不是「冲突解错」，而是「冲突解对了、特性没了」，且自制的逐行检查会给出假绿。

**How to apply:** 合并对端大重构分支后，按特性清单逐项在合并树里核（不靠 grep -F 全包）；门禁红优先按
「哪条链断了」反查移植漏点。关联 [[project-merge-master-to-dev-round3]]、[[pitfall-verification-blindspots-and-wrapup]]。
