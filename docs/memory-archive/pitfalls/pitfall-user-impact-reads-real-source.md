---
name: pitfall-user-impact-reads-real-source
description: 判「缺陷/断链影响多少用户」必须读生产代码里真实的检查源/消费方并实测现值——O-109 网站 update 路径 404
  连续三轮被定 P0，代码级核实后零用户受影响（插件检查源自初版就是 Gitee raw）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_83e9fb4b-a18e-4207-b2e7-0f0c493ae6c7
---

20261012 实例（竞品二十六轮收尾追问「update.json 双重失守影响现有用户吗」）：O-109（官网 `jvtools.cn/update/*` 5 路径 404）自十六轮审计起连续三轮被定性 P0「355/414 装机自更新通道完全死亡、存量触达为零」，r25/r26 报告沿用。代码级核实后修正：

- 插件内置「Gitee 抢先体验通道」（设置页勾选、**默认关**）的清单 URL 在 `UpdateSource.kt` 写死为 Gitee raw，**从初版 `9b2db2a` 起从未指向网站**（`5633dfe` 只把语义从自动更新转为选内测，URL 未变过）。
- Marketplace 用户（绝对主力）走 JetBrains 官方更新通道，与任何 update.json 无关。
- 官网 getting-started 实测只引导 Marketplace；README 写的检查源也是 Gitee raw ⇒ 网站 5 路径没有任何消费方，404 零用户受影响，**O-109 降 P2 能力补全**。
- 真实受损窗口唯一一处＝仓内 update.json 停在 9.23 的 10-05~10-09 约 4 天（Gitee raw 同步停 9.23，抢先体验内测用户错过 10.6/10.7 提示），`0c27bb9` 后恢复——**真风险是流程债（每版手拷 update.json），不是通道死**。

**Why**：三轮报告的 P0 定性来自「网站 update 目录=自更新通道」的部署面假设，从未有人打开 `UpdateSource.kt` 核对真实检查源；「404+叙事完整」营造了影响面很大的假象。

**How to apply**：判「某断链/缺陷影响多少用户」三步——①grep 生产代码找该路径/URL/配置的**真实消费方**（谁会去请求它）；②实测该源现值（raw 内容、附件活性）；③查官网/README 有无引导用户走该路径。三者都查不到消费方 ⇒ 影响面为零，降级。与 [[pitfall-verification-blindspots-and-wrapup]]「看产物不看文档自述」同族，本条补「看代码消费方不看部署面假设」；与 [[pitfall-blame-environment-before-proving-path]] 共享「先证明路径被走到」内核。
