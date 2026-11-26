---
name: project-7shell-docs-migration-20261117
description: 20261117 插件仓精华迁移到 7shell 新项目落地清单——账本31条/规范5份/流程清单/2技能，未提交待用户
metadata:
  node_type: memory
  type: project
  originSessionId: sess_4028dec3-1670-45ce-b1fd-c36c86c69761
---

2026-11-17 把插件仓可沉淀精华迁移到新项目 7shell（F:\code\Java\Github\SongRenShuo\7shell，Rust+TS Tauri 桌面应用，SSH/终端工具，Cargo+pnpm workspace，已有英文用户文档/PIPs/security-model 不变量机制）。**已提交 `f460da4`（7shell main 分支，35 文件 6159 行，作者 SongRenShuo <3064597532@qq.com>，未 push）**；第二批记忆沉淀（账本追加 I32~I44 + 协作偏好 + git 与多仓配方两份新规范 + 索引更新）与第一批同笔提交。**7shell 是基于原作者 qwdn <qwdn.dev@gmail.com> 的已有项目**（历史提交身份即原作者，20261117 用户确认「那是原作者」——勿建议改历史、勿误判为身份泄露），用户自己的新提交走仓配置的 SongRenShuo 身份。

落地清单：根 `AGENTS.md`（9 条代理指令，~2.7KB 控制在建议线内）；`docs/LESSONS.md`（magpie 账本机制 + I1~I31 导入条目，源仓 80 条教训中跨技术栈通用的精选，Seen 从 0 起算）；`docs/规范/` 5 份（编码与验收标准 V1-V6/C1-C3/M1-M3、grep 优先读取、文档命名、记忆精简、弹窗克制红线——触点清单按源仓行号漂移教训直接写成 grep 模式空表）；`docs/开发流程-派活与验收清单.md`（cargo/pnpm 命令面适配 + token 交接条目）；`docs/MEMORY.md` 索引；`docs/经验总结/20261117-2200-并行开发与子代理协作方法论`（5 篇汇编）；`.agents/skills/` task-queue（删 gradle 耦合换 cargo/pnpm）+ security-audit（原样 23 文件）。

判定不迁：ide-ui-acceptance/ide-demo-gif/k2j-port-sync/release-workflow/db-env-provisioning/srv-backup-restore（IntelliJ 插件或特定服务器绑定），其中 ide-demo-gif 改造成 desktop-demo-gif 的改法已写进盘点报告（插件仓 `build/7shell-import/inventory.md`，256 行，含 30 条教训全文与 8 技能适配要点），等 7shell 需要文档站动图时按它立项。后续可选：CI 守卫脚本（Rust/TS 钉 SSH 红线+元守卫登记表）、reverse_probe 四态反打脚本 cargo 适配。
