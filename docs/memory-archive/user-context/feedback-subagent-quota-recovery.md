---
name: feedback-subagent-quota-recovery
description: 子代理掉线/卡住时的零丢失恢复协议——**优先 SendMessage 续跑同一 agentId（保留其上下文，20261024 用户纠正）**，TaskStop 只用于防并发写同一工作树；含配额掉线、1310 硬封顶、前任真死核实与孤儿 gradle 读数采信
metadata:
  node_type: memory
  type: feedback
  originSessionId: sess_a26c167f-3260-4e61-8c89-9eb7ff14ae8f
---

子代理会因**账户 5 小时用量配额**中途掉线（状态 failed，error 文本含 "You have exceeded the 5-hour usage quota"，附重置时刻）。此时**它的进程可能仍挂在 running**（额度没了但任务未自行退出）。

**Why**：同一工作树若被旧（僵死）代理与新代理同时写，两边互相覆盖；而掉线代理的成果分两种落点——已提交的（在分支上，安全）与未提交的（只在工作树里，随时可能丢）。

**How to apply**（2026-10-08 两路实测，零丢失）：
1. **先 `TaskStop <agentId>` 停掉旧代理**，再派新的（不 TaskStop 就重派＝两进程写同一工作树）。
   **但「子代理卡住/掉线」优先走 `SendMessage` 续跑同一 agentId，而不是新派一个**（20261024 用户当场纠正：「你应该给让子智能体继续 因为他有上下文」）——同一 agent 保留它已读过的全部文件与判断链，续跑只需把主控已挖到的新证据喂进去即可，重派等于让它从零重读、白烧一遍 token；`TaskStop` 只用于「进程仍活着且与新代理会并发写同一工作树」这一种情形。另：**续跑指令里要写明「哪些证据主控已代挖、直接用不必重复找」**，否则它会重跑一遍已完成的取证。
2. **核对现场**：对该工作树 `git log --oneline <基线>..HEAD`（已提交几笔）+ `git status --short`（未提交哪些文件）+ `grep -rln "RED PROBE\|REVERSE-PROBE\|TEMP sandbox"`（确认没停在「先红/探针在源码里」的中间态）。
3. **未提交件按「掉线保存」提交并 push 留底**：commit message 明写 `WIP(掉线保存,未验证)` 与「收尾先跑哪个测试验证」；**不改写历史**，转正另起一笔。
4. **重派指令必须写「接手勿重做」**：列出工作树现状（哪些件已完成/哪些是半成品/待做哪几件），让新代理先跑一次验证把 WIP 转正，再继续剩余件。
5. 若某路**零产出**（工作树干净无提交），直接重跑即可。
6. 崩溃/掉线常伴**内存顶格**（工作树根出现 `hs_err_pid*.log`，首行 `Native memory allocation (malloc) failed`）——见 [[parallel-build-resource-rules]] R15/R16 处置（清自家 daemon 重跑、禁杀用户进程）；这些 log 是崩溃产物，收口时清理、勿提交。
7. **重派前必核实前任真死（20261014 O-136 实测教训）**：某路发来 failed 通知 ≠ 全部都死——同批另一路可能只是**没发通知而进程仍存活并在推进**（本例：接手代理凭「文件写入序列 + Win32_Process 查到该工作树 gradle 进程」发现前任活着，立即停写上报）。核实手段=Win32_Process 查该工作树的 gradle/java 进程 + `git status`/文件 mtime 连续观察。**前任真活着的更优收口＝双代理制**：前任保持独写跑完，接手转只读验收位（等三触发条件齐：分支出新提交+写入静默≥10min+gradle 进程归零，再补做现状核实/重跑读数/反向验证）。注意 Bash 后台命令的 gradle 是代理的**孤儿子进程**——代理死后 gradle 可能把活干完（20261014 门禁代理掉线后其孤儿 gradle 跑完全量 5 分片并写齐盘上结果：任务级收尾产物 mtime+新测试类入场两个证据判完整性，读数可直接采信，不必重跑）。
8. **派子代理一律后台执行，不阻塞主控**（20261027 用户中途插话纠正：「子智能体不要阻塞主控 应该在后台执行 最后通知即可」）：`Agent` 调用带 `run_in_background: true`，完成通知会自动唤醒，主控在等待期**并行做不冲突的活**（本轮＝自己审计另外三份矩阵 + 复核代理已交付的 01/02）。**并发上限实测**：3 路同时发必报 `user concurrency limit exceeded`；2 路同时发可能报 `Model request failed`——**逐路发最稳**，需要多路就依次补齐。
9. **后台代理「只核不写」要及时催办或接手**（20261027 实测：两路后台代理跑了约 20 分钟、工作树零改动，全在反复读代码）：判据＝`git status --short -- <目标文件>` 连续多轮为空 + mtime 未变。处置顺序＝①先 `SendMessage` 问「仍在核实还是已开始写盘」并要求**先落笔再补充**；②若仍无产出，`TaskStop` 停掉、**主控自己写**（本轮就是这样完成 03 与三份小件），并把「已核实且正确、无需改动」的文件在报告里明说。不要无限等——委派的目的是省时间，等 20 分钟的成本已经超过自己写的成本。
10. **多路并发同时掉线＝额度/供应商侧拒绝，不是某一路做错了（20261030 实测）**：三路后台子代理在同一时刻全部 failed，error 分别是 `ZCode has no active limited quota for GLM-5.3-Flash-Trial` 与 `Provider rejected the model request`（同批两路同因）。用户当日口径：「额度已恢复 继续刚刚的进度 **子智能体直接恢复 不要新派**」——恢复动作＝对原 `agentId` 逐路 `SendMessage`，正文写「**从上次中断处继续（不要重做已完成的步骤）**」+ 已完成产物落点（容器名/已落盘报告路径）+ 主控已代办事项（如 DBX 登记已代做），它就能接着跑。**不要**因看到 failed 就重建代理或重做部署——额度恢复前它们其实已经把容器拉起来了。
11. **三路同时恢复也撞限流，且限流与「模型流忙」会叠加（20261031 竞品 r34 实测）**：三路后台子代理**首派秒挂** `user concurrency limit exceeded`（连 3 路都超了，比此前 4 路才挂的读数更紧）；逐路 `SendMessage` 恢复后 B 路又连挂两次 `Start Plan is busy and automatic model stream recovery reached the maximum retry count`（供应商侧模型流忙，非配额）。用户当日口径：「额度已恢复……如有子智能体**直接恢复不要新派**，**本次任务并发临时调整为 1**」——即把该任务的执行改成严格串行（一路完成再恢复下一路），恢复指令照第 10 条写法。
12. **1310（周/月配额）与 1302/5 小时配额不同**：1310 是硬封顶（重置时刻可能是几天后），期间子代理通道全废——剩余工作改主会话直接做（Bash 后台跑长命令不占子代理额度）。
13. **1308（5 小时上限）打断且掉线前零提交时，恢复指令第一条＝先验收落袋（20261102 openget 后端实测）**：该路写了 auth+quota 两模块但零提交，全在工作树（含 JWT→Sa-Token 中间态）。恢复令的顺序＝①先跑定向测试验证工作树里未提交成果→②绿了立即作为一个提交推走（防二次掉线再丢）→③再续剩余模块。审计先行：`git log`（确认零提交）+ `git status`（区分 M/D/?? 三类）+ 读它自己的实施记录文件（可能还没写）。用户口径：「额度已恢复 继续刚刚的进度 如有子智能体直接恢复 不要新派」。

14. **反复 1308 掉线且用户说「先停一下」时，改交闲时任务续作（20261102 openget 实测）**：`OffPeakCreate` 建的任务在**本会话上下文里**继续跑（历史可见，故任务书可直接引用已建立的决策、agentId 与文件落点），但必须自带恢复锚点：①开工第一步＝推送已提交未上行提交 + `git status` 审计未提交中间态（区分「已提交」与「半成品」）；②未提交件**验编译后再落袋**，不许把不可编译的中间态当 WIP 直接提交；③逐项列剩余范围 + 技术拍板清单（防接手者另起炉灶）；④写明收口门禁命令与「没跑的不许写绿」。交棒前主控应先代推已提交未上行的提交（本轮 16 笔）把成果先保住。

15. **`status=completed` 不等于任务做完——先读它的 output 末行（20261102 openget S1 实测）**：该路状态是 completed，但 `output.txt` 只有一句「This part of the response was interrupted before it finished」——响应中途被打断，成果全留在工作树未提交（V7/V8 迁移 + 状态机改动 + 三个测试类），而埋点后端整块**根本没开始**。所以恢复前必做三件事：①读 `<agents>/<sessionId>/<agentId>/output.txt` 末行而不是只看 status；②`git status --short` + `git log --oneline -3` 分清「已提交/已落盘未提交/完全没做」三档；③自己 `find`/`ls` 目标产物目录确认哪几块真的不存在（别信它自述的清单）。恢复令要按这三档分别写：「某项判定已完成只需复核」「接着往下做不要重写」「完全没做的是这三件」。

16. **辨认「哪个 agentId 是哪一路」用 metadata.json，别靠目录重名（20261102 实测）**：`C:\Users\USER\.zcode\cli\agents\<parentSessionId>\agent_<uuid>\metadata.json` 里有 `description`（任务短名）、`status`、`prompt`（完整任务书）、`createdAt`/`completedAt`、`totalTokens`。多路并发掉线后要精确恢复某一路时，按 `ls -lt` 找最近目录 + 读 `description` 即可对上号；`output.txt`/`task.output` 同目录，可直接读末行判中断形态。

18. **`exceed quota limit` 是第三种掉线措辞，处置与 1308/1310 同——恢复令第一条写「先核对现场」（20261107 T-SqlListCatchup 券 I 实测）**：该路报 `Agent ... failed: exceed quota limit`（既不是 1308 的 5 小时配额文案、也不是 1310 的硬封顶），用户口径仍是「额度已恢复 继续刚刚的进度 **如有子智能体直接恢复 不要新派**」→ 对原 agentId `SendMessage` 续跑。恢复令里必须显式要求它**先做状态核对再继续**：①`git status --short` + `git log --oneline -3` 分清「已提交 / 落盘未提交 / 完全没做」；②若有半成品先判断能否收尾成可提交的一笔（能就立即提交）；③查 `build/` 残留编译产物（改过 data class 签名的，必须清 `build/classes` 再跑，否则伪红）；④明确写「不要重做已完成的步骤」。这样它不会从零重读、也不会把半成品当成品续写。
19. **SendMessage「queued for delivery」≠ 已送达——对已死代理要发两次并实测核销（20261106 F 队列组3 实测）**：额度中断杀掉后台子代理后，向其 agentId 发的恢复消息返回 `Message queued for delivery at its next tool round`，但死代理永远没有下一个 tool round，消息只是排队挂着；等用户再报「额度已恢复」时，**先核实它真的没动过**（工作树 `git log`/`git status` + 关键文件 mtime 对时间戳），再把完整续作指令（含主控实测的现场快照：已提交 hash、在途未提交文件清单、沙盒临时改动须还原的提醒）**重发一遍**才真正唤醒。首次消息不删不撤——同一 agentId 的第二次 SendMessage 会带着两条排队内容一起送达，不冲突。

相关：[[feedback-parallel-subagents-no-blocking-wait]]（并发上限与派工切分）、[[parallel-build-resource-rules]]。
