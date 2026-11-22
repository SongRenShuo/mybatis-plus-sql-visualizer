---
name: pitfall-release-action-leaves-no-trace
description: 发布/上架类动作「做完了但仓库零留痕、状态���档与事实相反」——判动作做没做必须查外部端点 + 门禁产物目录，不能读文档；含
  verifyMarketplace 产物路径判据与第三次同族事故
metadata:
  node_type: memory
  type: project
  originSessionId: session_a5cf3aa3-fe1e-4c1f-b9c6-9af6c299300c
---

# 发布动作零留痕：状态文档与事实相反（20261025 Marketplace ��架，第三次同族）

## 现象

2026-10-25T15:32Z Marketplace 上架 v2026.10.23（id 1202730，approve=true）**确实发生了**，但：

- `grep -rn "1202730"`（排 `build/`）全仓 **0 命中**；
- `docs/MEMORY.md:310` 与 `docs/发行说明/20261024-1030-…md:156` 到 HEAD 仍写「**Marketplace 未上传**」「上架 Marketplace 前需补跑」；
- `docs/变更记录.md:22-23` 只记了 **Gitee 侧**两次同 tag 重发，**不含 Marketplace 字样**；
- `verifyMarketplace` **确定没跑**（判据见下）。

后果：下一轮任何基于文档的判断都会错——本轮竞品调研 B 路子智能体初稿就照 `MEMORY.md:310` 写了「货架停更第 9 天」，被主控 API 实测推翻。

## 判据：怎么判「某道门禁 / 某个动作到底做没做」

**不看文档，看产物目录与外部端点。**

| 对象 | 产物路径（存在 = 跑过） |
|---|---|
| `verifyGate`（离线三目标） | `build/reports/pluginVerifierGate/IU-{243,253,262}.*/plugins/<id>/<ver>/verification-verdict.txt` |
| `verifyMarketplace`（在线无豁免） | `build/reports/pluginVerifierMarketplace/`（**目录不存在 = 没跑**） |
| 辅助：verifier home 时间戳 | `GRADLE_USER_HOME/mpv-verifier-home/{gate,marketplace}/` 各子目录最新写入时间 |
| Marketplace 上架 | `curl /api/plugins/33633/updates` 顶行 id/version/approve/listed + `cdate` |
| Gitee 发行版 | `curl /api/v5/repos/.../releases/tags/<tag>` 的 id/created_at/assets |

本轮实测：`pluginVerifierGate/IU-{243,253,262}` 三份 verdict 时间戳 **10-25 21:54~21:55**（读数 弃用 41/52/124、实验性 98/96/96，与变更记录逐位吻合）⇒ `verifyGate` 跑了；`pluginVerifierMarketplace` 全盘（含 `mpsv-release` 等全部工作树）**搜不到**，两个 verifier home 的 `marketplace` 子目录最新写入都停在 **10-24 03:15** ⇒ `verifyMarketplace` 没跑。

**注意**：`C:\Users\USER\.gradle` 是 `F:\gradle-home` 的 junction，两个 home 实为同一目录（131 文件、同一时间戳），别当成「两处都没跑」的独立证据。

## 时间线（本轮）

10-25 21:54 `verifyGate` → 22:39 第二次同 tag 重发落位（`00153b0b`）→ 23:32 Marketplace 上架 → **之后无任何提交与文档更新**。

## 为什么「包能上传」不等于「包能加载」

`releaseGate` = `buildPlugin → verifyGate → verifyMarketplace → testShards → e2eFakeIde`（`buildSrc/src/main/kotlin/mpv-verification.gradle.kts` 的 `register("releaseGate")`）。`verifyMarketplace` 任务头注写明：离线 `verifyGate` **不解析可选依赖 + 有成族豁免 + 只看退出码**，与 Marketplace 判定之间曾有系统性缺口——**20260923 上传时 7 个目标里 6 个被判 Critical 而本仓离线门禁全绿**即由此而来。��以「离线绿 + 上传成功」不构成「Marketplace 能加载」的证据。

`verifyMarketplace` 要点：在线（刻意不加 `-offline`、不加 `-ignored-problems`）、覆盖 243/253/262、`dependsOn("buildPlugin")`（**必须先出 zip**）、硬编码代理 `127.0.0.1:7890`、首次下载约 3.33GB、判定走 `checkMarketplaceVerdicts(reportsDir, dev-support/verifier-marketplace-baseline.txt)`。

## 防复发手段早就写在权威清单里��但没被执行

`docs/发行说明/T-ReleaseChecklist-发行检查清单.md`（T-RC-01 唯一权威清单）已有：

- **§四 回读断言**（T-RC-03）：给出可直接执行的 curl 命令 + **三档判定表**——①`updates` 新条目在场且 `approve=true`/`listed=true` = 已过审上架，可勾 RC-R7；②`hasUnapprovedUpdate=true` = 在审，等审期不重传；③**无新条目且旗标 false = 「队列延迟」与「上传未落位」不可区分，不得静默通过**。开头一句「不执行回读不算发布完成」。
- **§七 事故登记**：已有两案例——2026.9.18 断供（**没传**）、2026.9.23 零痕迹 + B5 重传（**传了但 API 不可见**，根因原文「回读时机与语义，不是执行��步：重传后没回读；『无条目 + 旗标 false』被留成静默；**且过审后本地文档仍写『B5 待重传』——已上架版与仓库叙述不一致，状态漂移再次现形**」）。

**本轮是第三次同族**，形态与第二案例完全一致（传了、过了、文档还写没传）。⇒ 清单本身没问题，是 **RC-R7 回读断言这一步没被执行**；若执行了，`approve=true` 属第一档，文档当场就该改。

## 处置（D183，三件）——20261026 用户逐项裁决

① **状态文档订正：已执行**（用户令「第一件现在做」）。改动**四处**：`docs/MEMORY.md:310`（v2026.10.23 正式版条目）「Marketplace 未上传」→「2026-10-25 已上架 JetBrains Marketplace（货架 id 1202730、approve=true、listed=true、cdate 2026-10-25T15:32:11Z；此前货架停 2026.10.16）」；`docs/发行说明/20261024-1030-…md` §兼容性 新增上架事实行 + 原「上架 Marketplace 前需补跑」改为「未跑 + 判定依据为平台侧接受」；`docs/变更记录.md` 新增 2026-10-25 上架条目（标注「补记，2026-10-26 回填」，含零留痕缺口与同族事故第三次登记）；**顺带订正同类历史错误**——`docs/MEMORY.md:718`（**v2026.10.16 正式版**条目）同样写「Marketplace 未上传」，但该版实为货架在售版（id 1195760、approve=true），已改为「2026-10-16 已上架 JetBrains Marketplace（…至 2026-10-25 被 v2026.10.23 取代）」。**未动** `docs/MEMORY.md:311/716/717`（beta.1~beta.3 内测版条目）——内测版从不上架，该字样**正确**。

② **`verifyMarketplace`：用户判定不补跑**。理由（用户原话口径）＝上架已被平台接受（`approve=true`/`listed=true`）即 JetBrains 侧校验通过，**比本地复现更强**。对照 2026-09-23 案例：**上传 ≠ 接受**（当时上传成功但 7 目标 6 个被判 Critical 遭拒）——所以「接受」这个事实本身就够，本地复现是它的弱替代。该判定依据已写入发行说明与变更记录，避免下一轮当未解释缺口重提。

③ **`update/update.json` 升版：用户暂缓**（铁律：只有用户能改）。当前 `2026.10.23-beta.2` ⇒ Gitee 抢先体验通道推不出比货架更新的版本；两条通道为何都收不到提示，见 [[project-release-workflow]] 的「更新通道两条分工」节。

**判据 V208（20261026 收紧后）**：①`grep -rn "1202730"`（排 build/）有命中；②`MEMORY.md`/发行说明的**正式版条目**中无「Marketplace 未上传」字样（**内测版条目 beta.1~beta.3 保留该字样是正确的，勿误删**）；③`verifyMarketplace` 读数在场，或文档明示「未跑」并给出替代判据（平台侧 `approve=true`/`listed=true`）；④`update/update.json` version = `2026.10.23`。

**可复用的一条**：订正这类「文档与事实相反」时，**要按同一错误类扫全仓**而不是只改被点名的那一处——本轮被点名的只有 `MEMORY.md:310`，扫下来 `:718`（上一版正式版）同病；同时要能区分「同类但正确」的条目（内测版条目）以免误删。

**Why:** 发布类动作的「完成」由外部端点定义，不由仓库文档定义；文档滞后会把「已完成」误判成「未完成」，进而重复动作或漏掉后续步骤。

**How to apply:** 接手任何「上架/发行/门禁是否做过」的问题，先按上表查产物目录与外部端点，再读文档；发现文档与事实相反时，把「订正文档」���身当成一个待办项登记，而不是只在报告里注一句。关联 [[project-release-workflow]]、[[project-competitive-round30]]、[[pitfall-verification-blindspots-and-wrapup]]。
