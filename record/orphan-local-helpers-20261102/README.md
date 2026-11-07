# 孤儿工作树 .local 辅助脚本归档（2026-11-02）

来源：`mpsv-fr45-integration` 与 `mpsv-magpie-gems` 两个孤儿目录（对应分支 tip 分别为
`d631b2f3`、`146e3b3c`，均已并入 dev；`.git` 在更早的清理中已被破坏）。
这两个目录的跟踪文件经 `git archive` + `diff --strip-trailing-cr` 整树比对，
与各自分支 tip **完全一致**（无未提交改动），但 `.local/` 下有 5 个从未入库的本地脚本，回收前留底。

| 文件 | 用途 |
|---|---|
| `mpsv-fr45-integration--fr45-walk.init.gradle` | FR-0045 走查用 Gradle init 脚本（注入沙盒参数） |
| `mpsv-fr45-integration--fr45-walk262.init.gradle` | 同上，2026.2 平台变体 |
| `mpsv-magpie-gems--magpie-gems-ui243.init.gradle` | magpie 线 UI-243 沙盒启动 init 脚本 |
| `mpsv-magpie-gems--start-magpie-ui243.ps1` | 配套 runIde 启动脚本 |
| `mpsv-magpie-gems--gate-readout.json` | 当次门禁读数快照 |

已排除：`.local/README.md`、`.local/local.env.example`（与主仓 `.local/` 同名文件逐字节相同）。

复用提示：init 脚本用法见 `.agents/skills/ide-ui-acceptance/SKILL.md` 与
`runide-demo-verify-workflow` 记忆条目。
