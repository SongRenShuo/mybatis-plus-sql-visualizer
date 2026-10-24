# AGENTS.md（仓库级代理与贡献者指令）

本仓由多会话并行开发（多工作树 + 子代理车道）。开始任何改动前，按顺序读：

1. **改代码前先读 [`docs/LESSONS.md`](docs/LESSONS.md)**——教训账本，每条都是带证据的复发规则； Seen 满 3 天的已固化进 [`docs/code-standards.md`](docs/code-standards.md)，审查与收口逐条对照。
2. **跑任何 Gradle 命令前先过并行登记表**——`docs/MEMORY.md` 顶部 🔴「并行测试协调登记表」节：`parallel_tasks.py check` → 有活跃任务先 register/done（禁裸 done）；构建并发上限 2 路，禁降片。
3. 定向测试纪律、验收清单（含 not run 条款）、派活模板：[`docs/开发流程-派活与验收清单.md`](docs/开发流程-派活与验收清单.md)；夜间复盘协议（提示词+水位线）：[`docs/开发流程-夜间复盘协议.md`](docs/开发流程-夜间复盘协议.md)。
4. 行为域文档（子系统职责与 source of truth）：[`docs/架构设计/`](docs/架构设计/) 下 `子系统-*.md`；改契约必须同批更新归属文档。
5. 文档写现状，提案写进 `docs/开发计划/`；新缺陷/教训在收口后补记 `docs/LESSONS.md`（规则一句话 + hash 证据 + Seen 计数，不写流水账）。
