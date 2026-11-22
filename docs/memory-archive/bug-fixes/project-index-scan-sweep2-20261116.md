---
name: project-index-scan-sweep2-20261116
description: T-IndexScanSweep2（2026-11-16 已实施并合入 master 978c7f289，双仓已推、CI 8/8）：补全候选名称清单改项目级服务一次构建；P3 三处判不做
metadata:
  node_type: memory
  type: project
  originSessionId: sess_4043a4bb-4569-4ef9-8e28-84e10cd22b20
---

用户 2026-11-16 令「候选推荐做的话 先立项」→ 出计划 → 令「派子智能体后台 从主分支新建分支和工作树 实施 先不要往主分支合并」→ 完成后令主控「合并 master + 清理分支工作树 + 推送盯 CI」。计划：`docs/开发计划/20261116-1235-T-IndexScanSweep2-…-开发计划.md`（§八 有实施记录，索引行已改「已实施」）。

**已交付**：分支 `t-index-scan-sweep2` 5 笔（`bf2f61010` 索引 v2 加 `#mapper`/`#config` 标记键 → `b77180ee6` `XmlSymbolNameIndexCache` + 两处接线 + `legacyXxx` 回退 + Registry `sqlvis.completion.nameIndexCache` → `1184529a2` 行为 8 例 + 防回退方法体扫描 2 例 → `8bb718909` CHANGELOG → `c96b3e5a0` 计划 §八），主控 `--no-ff` 合并 `978c7f289` + 索引行 `3a47b831f`；worktree/分支已删、并行登记已注销；双仓已推，CI run `43816779504`（head `3a47b831f`）**8/8 全绿**。

**关键读数与发现**：
- P-1/P-2 证伪通过：`XmlSqlCompletionContributor.kt:301` 每次补全调 `crossFileSqlIds`，改造前方法体无缓存；N=100/200/400 连续两次耗时同量级（3.46/2.88ms 等）⇒ 每次全付。
- **`FileBasedIndex.getModificationStamp(Project, String)` 在 243/262 都不存在**（javap 实证），在册的是 `getIndexModificationStamp(ID, Project)`；且它只随「索引产出」变，**改已存在 mapper 的 `<sql id>` 代戳恒不变** ⇒ 失效口径＝索引代戳 ⊕ 逐文件 `VirtualFile.modificationStamp`。
- 定向 10 类 **98/0/0/0**；反向验证（塞回现扫 / 失效做坏）红 1+3 例 → 恢复 0 红。
- 合并冲突只在 `CHANGELOG.md`：master 刚发 `2026.11.16-beta.1` 把 `[Unreleased]` 收走，解法＝取 master 版 + 只补本刀一条（别把旧条目再抄一遍）。

**边界（子代理登记，如实保留）**：① 失效边界＝落盘（别的文件里未保存的编辑要等保存才进清单；当前文件自身的 `<sql id>` 不受影响）；② 「排除当前文件」语义从「按文件身份」变「按名字集合」，同 `ns.sqlId` 跨文件重名（工程错误形态）会一并排除。

**Why:** 上一轮 T-MechanismSweep 之后剩下的扫描面量级差一个数量级，先量化再决定才避免了「新开一套索引换几个毫秒」的赔本改动；同理 P3 三处（`HbmXmlEntityResolver` 等）继续不做。
**How to apply:** 下一批候选＝`HbmXmlEntityResolver` 若日后 JPA 预览出现可测卡顿，先按计划 §五 探针实测 >50ms 再立项；回退通道 `sqlvis.completion.nameIndexCache` 与 `legacyCrossFileSqlIds`/`legacyConfigPropertyKeys` 约定下一版删除。相关：[[project-ibatis-gutter-perf-20261116]]
