---
name: project-release-20261106-beta2
description: v2026.11.6-beta.2 发行准备（20261106 同日第二个内测版）——版本/八处同步/产物自检 PASS/两版验收文档；门禁由 GitHub Actions mode=gate 跑；tag、Gitee 发行、update.json 待用户
metadata:
  node_type: memory
  type: project
  originSessionId: sess_33620d7f-a4ec-4f92-8fbe-dcd146afc43f
---

# v2026.11.6-beta.2 发行准备（2026-11-06）

同日第二个内测版（beta.1 已于 07:43 发出；beta.1 在 2026.3 上踩了自更新回退重启，beta.2 带上修复）。用户令：「准备发行今天的第二个内测版，并生成一个功能验收文档，需要有两个内测版的信息」。

## 已完成

- **版本**：`gradle.properties` → `2026.11.6-beta.2`；发行提交 `38b3debe`（其后 `0e94445b` 只加验收文档指路，纯 docs）。
- **八处同步**：发行说明（`docs/发行说明/20261106-1545-内测-beta.2-磐维与iBatis支持及2026.3热替换修复-发行说明.md`）、CHANGELOG（`[Unreleased]` → `[2026.11.6-beta.2] - 2026-11-06`，并补 CONFIG 看门狗可见化一条）、gradle.properties、`gradle/plugin-change-notes.html`（置顶 7 条含评分引导）、`plugin.xml` 描述（控制台日志族 **3→4**，并入 iBatis 2.x——新类别才动）、`update/preview.update.json`、MEMORY 索引 + 变更记录总册；Marketplace 页不动（内测包不上架）；**`update/update.json` 不动**（用户本人改，见 [[feedback-update-json-user-only]]）。
- **产物自检**：`buildPlugin` 出 zip **7,498,236 B**；`dev-support/inspect-plugin-zip.py --zip … --version …` 先 **FAIL** 一次——changeNotes 版本块里有**全角冒号**，触碰「块内 （）：零命中」红线；改掉后重编重扫 **PASS**（内层 jar 名 / plugin.xml `<version>` / MANIFEST / 首个 changeNotes 块全对）。
- **功能验收文档（两版）**：`docs/验收文档/20261106-内测beta.1与beta.2-功能验收文档.md`——能力总表 14 行（beta.1 5 项 / beta.2 9 项）、两版分项步骤（B1-1~B1-5、B2-1~B2-8）、跨版本回归 5 项、已知边界、空白结论表。

## 门禁

- 推送 master 触发 `push-test.yml`（测试），另用 API `POST /actions/workflows/build-plugin.yml/dispatches` 带 `{"ref":"master","inputs":{"mode":"gate"}}` 手动派 `build-plugin.yml`（build + verify×3 + marketplace×3 + verifyplugin）；dispatch 返回 **204**，同组新 run 会取消上一轮 gate。
- 读数轨迹：`c48ef9ac` 轮 `push-test` **success**（42181785287）；`19f72926` 轮 gate/push-test 被下一轮取代；最终 `38b3debe` 上 gate `42185016348` + push-test `42185015317`（另 `0e94445b` 为验收文档指路的纯 docs 提交）。同一 sha 的旧 gate 会被同组新 run 标 `cancelled`，属预期不是失败。
- **产物自检脚本调用形态**：`dev-support/inspect-plugin-zip.py` 只认命名参数——位置参数会被判 `unrecognized arguments`；正确写法 `--zip build/distributions/<名称>.zip --version <版本>`。它的 FAIL 会点名具体条（本轮是 changeNotes 版本块里的全角冒号）。
- **反例（本轮操作失误）**：本地全量 `dialectExternalVerify` 跑着的同时又在**同一工作树**跑 `buildPlugin` 与定向测试 → Gradle 工程锁互斥，矩阵轮挂死（summary 一直停在上一轮），已 TaskStop 后单跑重来。长门禁必须独占其工作树。
- **第二道阻断：评分句守卫（2026-11-06，已修 `0a0ddbf9`）**：`push-test` 在 `38b3debe` 上 **failure**，唯一红点是 `fast (1)` 的 `ChangeNotesRatingLineGuardTest`——beta.2 新块加了评分行、beta.1 历史块仍带同一行，全文计数 2（`expected:<1> but was:<2>`）。修复＝删历史块那行、只留最新块；守卫单跑绿、zip 重编 + 自检再 PASS；同仓 `update/preview.update.json` 只含本版 changeNotes，不受影响。**教训**：一日两个 beta 时「上一发 beta 块」就是历史块，评分句必须随新块生成而删，且这道守卫现在在 CI 的 fast 腿上跑，会直接顶红推送轮。修后重推 `0a0ddbf9`，重新派发 gate `42188153050` / push-test `42188080709`。
- **用户口径（2026-11-06）**：「不阻塞 beta.2，beta.2 先收口发行」+「排子智能体处理这个优化」——即发行不受占位表达优化（T-RuntimeConcat-Polish）影响，优化走独立分支/工作树后台做。

## 已发行（2026-11-06 晚，用户令「可以正常发行这个内测版 记得不要动 update.json」）

- **tag `2026.11.6-beta.2` → `0a0ddbf9`**（轻量，指向门禁绿的那个提交；发行说明/总册两条回填 docs 提交 `ddabcca9`、`e5a76650` 落在 tag 之后——与 beta.1 同款处理：tag 不在 docs 回填提交上）。
- **Gitee 发行版 id 1280174**（prerelease，正文＝发行说明全文，标题带「内测版」）；**GitHub 镜像 id 436309952**；两侧附件回读 sha256 与本地逐字节一致（`11d7e69359e7ee01…`，**7,498,240 字节**）。
- 门禁 run **42188153050**（head `0a0ddbf9`）**15 job 全绿**；`verifyplugin` 日志实证它验的就是待下发 zip，三目标 **Compatible**：IU-243.28141.41 / IU-253.33514.17 / IU-263.7307.200。
- 发布命令序列：`git tag …` → `powershell -File dev-support/push-all.ps1 -Branch master -Tags`（令牌通路，日志显 `x-access-token:***@`、无 GCM 弹窗）→ `python dev-support/gitee-release.py --version 2026.11.6-beta.2 --notes <发行说明> --name "🎉 SQL Visualizer v2026.11.6-beta.2 内测版" --prerelease` → `python dev-support/mirror-releases-to-github.py --tag 2026.11.6-beta.2 --fresh`。
- **回读校验的两个坑**：①`gitee-release.py --verify-only` 默认按 `prerelease=False` 断言，内测版必须**同时带 `--prerelease`**，否则报 `prerelease mismatch: expected False, got True`；②`mirror-releases-to-github.py` 自己不拉 Gitee 清单，必须先 `--fresh`（或已有 `.local/gitee-releases.json` 缓存），否则新版本根本不在处理集里。
- 矩阵读数如实登记：磐维 A 66.4s PASS / 磐维 B PASS / GaussDB 内核 503 PASS；一次性连跑在 `sqlserver2022`、`panwei_a`、`vastbase` 三处被实例侧**读挂起**阻断（线程栈停在驱动 socket 读；磐维实例 `pg_stat_activity` 无 `xact_start` 长事务、无锁等待）→ 三次中止不计入通过口径，写进发行说明与总册。

## 后续

- `update/update.json` 仍由用户手改（本版未动，`git log -1 -- update/update.json` 停在用户那次 Revert）；Marketplace 转正、沙盒 GUI 冒烟（归用户）。
- `t-runtimeconcat-polish` 分支（`.worktrees/concat-polish`）：P1 占位摘要已实施，**未合流**。首轮编译暴露两处代理没跑出来的语法/类型错（KDoc 里写了字面 `*/` 提前闭合注释；新增 4 个测试用例的 `result.` 裸用），主控修后仍在跑定向验证。见 [[project-runtime-concat-delete-anchor-20261106]]、[[pitfall-runtime-placeholder-must-be-one-comment]]。

相关：[[project-ibatis-panwei-20261106]]、[[project-release-workflow]]、[[feedback-rating-line-latest-block-only]]、[[parallel-build-resource-rules]]、[[pitfall-gradle-transform-partial-delete]]。