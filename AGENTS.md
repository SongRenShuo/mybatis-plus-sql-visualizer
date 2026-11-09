# AGENTS.md（仓库级代理与贡献者指令）

本仓由多会话并行开发（多工作树 + 子代理车道）。开始任何改动前，按顺序读：

1. **改代码前先读 [`docs/LESSONS.md`](docs/LESSONS.md)**——教训账本，每条都是带证据的复发规则；Seen 满 3 天的已固化进 [`docs/规范/编码与验收标准-规范.md`](docs/规范/编码与验收标准-规范.md)，审查与收口逐条对照。
2. **跑任何 Gradle 命令前先过并行登记表**——`python C:\Users\USER\.zcode\cli\parallel_tasks.py check` → 有活跃任务先 register/done（禁裸 done）；构建并发上限 2 路，禁降片。
3. 定向测试纪律、验收清单（含 not run 条款）、派活模板：[`docs/开发流程-派活与验收清单.md`](docs/开发流程-派活与验收清单.md)。
4. 行为域文档（子系统职责与 source of truth）：[`docs/架构设计/`](docs/架构设计/) 下 `子系统-*.md`；改契约必须同批更新归属文档。
5. 文档写现状，提案写进 `docs/开发计划/`；新缺陷/教训在收口后补记 `docs/LESSONS.md`（规则一句话 + hash 证据 + Seen 计数，不写流水账）。
6. **「提交即回填」**：`src/main` 用户可感改动随提交回填 `CHANGELOG.md` 的 `[Unreleased]` 段（一句一条，带提交哈希）；发行批只做核对不做补账——发行时漏账不可再靠发行批兜底。
7. **`update/update.json` 只有用户本人能改**，助手与子代理一律不动此文件。
8. `docs/变更记录.md` 的发行条目随发行提交落账，不攒批。
9. **对外动作须用户明确授权**：push 任何远端、建 tag、发行、上传 Marketplace、外发内容（公众号/群/评论/issue）均不得自动执行；合流（dev↔master、insight→dev）同样等用户点名。
10. **推送 GitHub 一律走项目令牌，禁裸 `git push github`**（用户 2026-10-25 纠正、2026-11-06 复发一次）：裸推会走 `credential.helper` 指向的 Git Credential Manager，**弹 GUI 抢焦点**、可能常驻不退，且用户会被迫手点。
    - 正路：`powershell -NoProfile -ExecutionPolicy Bypass -File dev-support/push-all.ps1 -Branch <分支>`（`-All` 三支 / `-Tags` / `-GiteeOnly` / `-DryRun`）。脚本已固定三件事：令牌读 `.local/github-token.json` 的 `github_personal_access_token`、命令行 `-c credential.helper=` 置空绕开 GCM、GitHub 段代理探测不通则回落直连。
    - 手工等价写法：`git -c credential.helper= push "https://x-access-token:<token>@github.com/SongRenShuo/mybatis-plus-sql-visualizer.git" <ref>`（令牌从 `.local/github-token.json` 读入变量，别写进命令行历史）。
    - **调 GitHub API 也别用 `git credential fill` 取令牌**（同样会问 GCM）——直接读 `.local/github-token.json`。
    - 判定读数：日志里出现 `x-access-token:***@` 且全程无弹窗才算走了令牌通路；看到裸 `git push github` 就是又滑回 GUI 凭据了。
