---
name: project-architecture-docs-sync-20261117
description: docs/架构设计 五篇随 2026.11.13~11.16 批次同步收官（提交
  092f54940，未推）；含可复用的文档对账作业法与 2026-11-17 实测计数快照
metadata:
  node_type: memory
  type: project
  originSessionId: sess_0048a3a5-2323-4dd4-aeca-d2f00583f1a6
---

docs/架构设计 五篇（01 总览 / 02 流程详解 / 03 测试指南 / 预览触发面清单 / PSI 协议）已于 2026-11-17 按 2026.11.13~11.16 代码批次同步完毕，提交 `092f54940`（master，+106/−62，**未推双仓，等用户令**）。判定不改：两份 ORM 覆盖矩阵、M37M38、task-queue 说明、备份体系文档（对账无漂移）。

**Why:** 架构文档是行为域 source of truth，落后四天六个任务批次就会误导后续实施；同步轮的方法与计数快照对下一轮直接可复用。

**How to apply:**
- **对账作业法**：先 `git log -1 -- <文档>` 取每篇最后同步提交，再对同窗口的 `src/main` 提交列清单定缺口；派 3 路 Explore 代理按「机制清单 × 文档落点（行号+摘录）× 代码事实（文件:行）」三栏输出审计；主控只抽查 load-bearing 事实（注册行/常量值/计数）再落笔。**陷阱：代理报告的文档原文摘录可能少报行尾内容**（本轮一处 Edit old_string 不匹配，实文里 ui 行列表尾部还有 `+ ui/nav（4）`）——Edit 前先 grep 实际文本。
- **2026-11-17 实测计数快照**（写文档引用计数时的基线，引用前仍须重测）：`*Test.kt` 922（纯 JUnit 493 / Light 410 含 3 个 FQCN 全写 / Heavy 22；P∩L 交集 4 + JUnit3 例外 1 才对上 922——「三档互斥加和=总数」的旧口径已废）；守卫 61+6、元守卫 GUARD_CLASSES 73 条；服务 ×17（APP 8 + PROJECT 9）；postStartupActivity 主 ×5；notificationGroup ×6；fileBasedIndex ×2；registryKey ×6；ui/gutter 18 文件/19 provider 类；agent 子项目 51 文件/282 @Test；executeOnPooledThread 74 / ReadAction.nonBlocking 19 / schedule 4 处。
- 本轮起架构文档固化了三条新定例（写后续文档勿再回退旧表述）：①跨文件定位一律走两枚自定义索引、正确性闸门留 PSI 侧精确匹配；②gutter 目标解析三态（只有 Absent 才收回摘图标）；③psiCapture 守卫只扫 executeOnPooledThread，nonBlocking/schedule 派发体是盲区、合规靠自律。
- 提交信息照例走 `-F` 文件（`.local/commit-msg-*.txt`，用完即删）；纯 docs 提交不回填 CHANGELOG。

相关：[[project-branch-align-merge-20261116]]（同窗口合流轮）、[[pitfall-scheduled-pool-callback-psi-needs-read-action]]（PSI 协议新增段的出处）。
