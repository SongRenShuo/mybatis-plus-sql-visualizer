---
name: project-nightly-local-mysql-20261112
description: 2026-11-12 夜间全量三红归因（dialect 服务器容器 / e2e 远端库不可达 / extras 类计数抖动）与
  e2e+database 腿改 runner 本地 MySQL 的落地状态
metadata:
  node_type: memory
  type: project
  originSessionId: sess_5abdc5bc-7142-48cc-a6f8-3d8379788c57
---

2026-11-12 夜间全量（run 43125167048 @`8b1f579e7`）3 红，归因：

- **dialect（确定性，非本轮引入）**：`通过 2 / 失败 2`——panwei_a `103.36.220.245:26101`、gaussdb_kernel503 `:25882` 均 **Connection refused**（主机活着、容器不在监听）；mysql84/pg16 在 `114.66.55.245:20084/:20033` 照常 PASS。同一读数在 11-10 23:03 那轮一字不差，夜间从 11-09 19:03 起连红 6 轮。只能服务器侧修。
- **e2e（确定性）**：demo 应用 3.5s 正常起在 8082，第一条查库就 HikariPool 初始化 `CommunicationsException → ConnectException: Connection timed out`（secret `MPV_DB_URL` = `103.36.220.245:25900`）→ 探针「等应用就绪超时 120000ms」→ 13 条连带断言全红，根因被埋。真因只在 `e2e-evidence` 工件的 app.log 里。
- **extras（抖动）**：`ZeroLoadClassWriterWeaveTest` 的「零类加载增量层」两例（PS面/prepare面）expected 0 实得 1/2；同提交 20 分钟前的 dispatch 轮 extras 绿 ⇒ 测量抖动，非产品回归（用例 javadoc 自述增量层受 JVM 预载态影响，红信号由 findLoadedClass / 字节码红线两层兜底）。

修复（已实施并推双仓 master `0e6de036f` + `547514f99`）：e2e 与 database 两条腿改 job 自起 runner 本地 MySQL（`.github/actions/local-mysql` 复合动作 + 灌库步骤 `-RequireDb`），删掉 `MPV_DB_URL/USER/PASSWORD` 注入与「未配置就跳过」分支。做法见 [[reference-windows-runner-local-mysql]]，推送见 [[reference-dual-remote-token-push]]。

顺带查明 database 腿此前是**空绿**：用例（DataSourceJdbcSmokeTest 等）在库不可达时「显式绿跳」（println+return，不是 assume），远端库不通它也 PASS；头注释里「testDatabase 硬编码 127.0.0.1:7890 代理需改代码」早已由 T-CiDbConn 解决（gradle 有代理才转发，CI 剥了代理行就直连），是条过期注释。

验证（三轮 dispatch 实证，全在 master）：
- run 43143657370（@`547514f99`）：e2e `断言 PASS=26 FAIL=0 / RESULT: ALL-PASS`（探针 16.6s）；database success；extras 绿；本机没权限验的 `mysqld --install` + `Start-Service` 两行实证通过。17 job 唯一红＝dialect。
- run 43146861779（@`c3197e009`，补逐例读数步）：`DB 腿读数：files=10 tests=86 skipped=0 failures=0 errors=0` ⇒ database 腿从空绿变真跑、0 跳过。
- run 43154447017（@`8542a8d19`，读数步加闸门）：同一读数 `tests=86 skipped=0`，闸门未触发；仍只红 dialect。

闸门口径＝`files=0` 或 `tests=0` 直接 throw（只钉「检测本身失效」），跳过率不设阈值。反向验证三档在 pwsh 7 下逐字跑过：空目录→THROW / tests=0→THROW / tests=13 skipped=2→PASS。**注意**：本机 `powershell`（5.1）读无 BOM 的 UTF-8 脚本会把中文串按 ANSI 读成乱码并直接解析失败——CI 走 pwsh 才没事，本地跑这类脚本一律用 `pwsh`。

教训入账 L42（空绿先补可见性）/L43（外部实例先问 runner 能否自给）。仍开：dialect 只能服务器侧修（panwei A 26101 / GaussDB 25882 refused）。

**Why**：夜间红要先按 job 构成分层，别把环境腿红当门禁红；一条腿的连带断言越多，越要先看工件里的应用日志而不是断言列表。
**How to apply**：下次夜间红先查 `dialect` 是否只红外部实例（环境）→ 再看 `database`/`e2e` 起库是否成功 → `extras` 的类计数类断言先复跑一次再定性。
