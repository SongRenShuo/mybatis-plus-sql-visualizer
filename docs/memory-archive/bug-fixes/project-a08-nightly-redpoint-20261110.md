---
name: project-a08-nightly-redpoint-20261110
description: A-08 夜间流水线红点巡检（20261110）——run 23/24 红点归类、dialect 失败诊断+上传条件修复合入
  master 并起 run 25 验证；服务器故障红属正常
metadata:
  node_type: memory
  type: project
  originSessionId: sess_3111cf66-9bce-4284-ae1a-46c00894655d
---

闲时任务 A-08（2026-11-10 三路并行之一，独立分支 `t-idle-a08` / `2ef332d0`）：

**红点归因**（GitHub API 令牌取 run 23/24 日志）：
- run 24（11-10，head `e65535c8`）红 e2e + dialect；run 23（11-09，head `52f84f95`）红 fast(1)/e2e/dialect；run 17~22 全绿。**平台能力类 0 个**。
- fast(1)＝测试断言（`IbatisXmlPreviewServiceTest UI-001_参数数量不符仍为失败档`），已由 `7d640980` 修复，run 24 快桶绿。
- e2e＝**环境/配置面**：`HikariPool ... CommunicationsException ... ConnectException: Connection timed out: connect`，MySQL 从 runner 连不通，secret `MPV_DB_URL` 在场，连续两夜同构；按授权未处置（属测试服务器问题）。
- dialect＝`DialectExternalMatrixVerificationTest.kt:196` **FAIL 桶**断言（非 TIMEOUT，实例级看门狗假设证伪；对照绿轮 run 22 同 4 实例子集、零代码改动）→ 外部实例可用性。
- `4c23e020`/`c69346bb` 已覆盖 demo 克隆类红，本两轮不属于该类。

**修复**（`.github/workflows/nightly-full.yml` +31/−1）：①补「失败诊断输出（断言消息与 summary 打到任务日志）」——读 JUnit XML `failure/error message` + `build/reports/dialectExternalVerify/summary.md` 四桶明细，与 fast/tests/extras 同口径；②「上传方言验证摘要」`if` 改 `!cancelled() && steps.matrix.outputs.ready == 'true'`（原条件隐含 `success()`，测试一红整步 skipped、工件丢失，正是「红点不可归因」根因）。PyYAML 结构断言 + pwsh 复刻失败布局实跑 + 反向两次读数自证，未跑 gradle，`src/main` 零改动。

**收口与验证**（用户「按照你的建议来」→「现在起一次夜间轮验证，目前测试服务器有问题，对应的用例红是正常」）：
- 三路文档合入 master `26836152`；A-08 的 workflow 修复先留在分支，后按授权合入 master `b52de7e2`（只该文件 +31/−1，master 与 A-08 基底的 workflow diff=0 行，零冲突）。
- 双仓推送走 `push-all.ps1 -Branch master`（日志 `x-access-token:***@` 在场），远端 master=`b52de7e2`，API 回读确认两处修复在远端 workflow。
- `POST /actions/workflows/nightly-full.yml/dispatches {"ref":"master"}` → **run 25**（id `42776988535`，head `b52de7e2`，`workflow_dispatch`）。
- **判读口径（用户给定）**：e2e/dialect 因测试服务器不可用的红属正常，不判修复回归；唯一验证目标＝dialect 再红时日志出现新诊断步的四桶明细、`上传方言验证摘要` 不再 skipped、失败轮也能下到 `dialect-verify-summary` 工件。
- ⚠️ run 25 的后台 watcher 因脚本恒真条件提前退出（见 [[pitfall-gha-jobs-watcher-implicit-success-gate]]），日志 FINAL 段 12/13 job 仍 `in_progress None`——**轮次未确认终态，结论不可下**，需重挂 watcher 或按 run 级 `status` 重查。
- `t-idle-a08` 工作树与分支保留（待验证生效后再决定删）；`t-idle-a11`/`t-idle-a15` 已删。

相关：[[feedback-verify-status-before-dispatch]]、[[feedback-ci-trigger-push-cadence-and-watcher]]、[[pitfall-gha-jobs-watcher-implicit-success-gate]]、[[reference-github-api-token-authoritative-readings]]。

## 2026-11-13 收官：dialect 腿转绿，夜间 17/17 全绿

那批「服务器故障红属正常」已经不再是现状：`103.36.220.245` 恢复可达后，dialect 的真凶是**实例侧四层问题**（gaussdb 只听 loopback、三套 `pg_hba` 只放行容器网段），已修并验证——run `43337831586` 出 `通过 4 / 跳过 0 / 超时 0 / 失败 0`（mysql84 262 词 / pg16 86 词 / panwei_a 109 词 / gaussdb 197 词），夜间 **17/17 全绿**，是 11-09 以来第一轮。同轮 e2e `PASS=26 FAIL=0`、database 腿 `files=10 tests=86 skipped=0`。

排查方法已固化：skill `db-env-provisioning` §4.5（四层：宿主机端口→DNAT→容器监听→pg_hba）、`docs/LESSONS.md` L60、`docs/经验总结/20261113-0845-…-经验总结.md`。服务器侧改动（`listen_addresses='*'` + hba `0.0.0.0/0 sha256` + `gs_ctl reload`）细节见 [[reference-dbx-ssh-test-server]]。
