---
name: project-ci-retry-classify-and-shard-timing-20261125
description: 2026-11-25 CI 提速收口——test(4) 16m21s 的真因是重试阶梯把测试红误判成竞态；已重写 run-gradle-retry.ps1（fbe0ce7aa，未 push）；附稳态 9m05s 构成与被否提速手段的实证
metadata:
  node_type: memory
  type: project
  originSessionId: sess_3519720c-19c0-41d5-b32b-eabbec094c57
---

2026-11-25 用户问「test(4) 16m21s，拆成两个分片会不会更快」。结论：**拆片不会更快**；那 7 分钟是重试阶梯误判烧掉的。已实施 P0（`fbe0ce7aa`，master 本地、**尚未 push**——master 当时 ahead 16，含另一会话的 D256 合并 `736b6a272`，推送需用户点头）。

## 坏轮次构成（run 45562438538 test(4)）

| 尝试 | 用时 | 结果 |
|---|---|---|
| 1 | 1m02 | 配置期 `ClosedFileSystemException`（zip-FS 竞态，约 4/25 片） |
| 2（停 daemon+清 transforms） | 49s | 同错（该级 2026-11-05 已被实测证伪） |
| 3（清项目侧 IPGP 缓存） | 7m04 | 竞态已恢复，真跑完 1117 例 / 1 红（`SqlStandalonePreviewPanelDialogTest`，L90 flaky） |
| 4（`git clean -xdf`） | 5m44 | 通过 |

真因：旧脚本 `Tee-Object -Append` 共用一份日志、每级对**整个文件**匹配 `ClosedFileSystemException`；首次竞态后的任何失败（含测试红）都命中旧签名 → 升级全量重跑，**既多烧 5m44 又把真红掩盖成绿**。

## 稳态构成（绿色片 9m05s）

setup ~70s（缓存恢复 3.5GB ≈44s + 准备 demo 9s）+ Gradle 7m37：daemon/配置/buildSrc ≈1m45、`compileKotlin` 1m35、sandbox/jar ≈30s、`compileTestKotlin` 1m17、`testShardN` 2m07。**测试本体只占 2 分钟，固定成本占 7 分钟** ⇒ 拆片每片仍付 4~5 分钟固定成本，墙上时间由最慢 job 决定，不变；还扩大竞态命中面。

## 被证据否决的提速手段

- **Gradle build cache**：`gradle.properties` 的 T-TestPerf-01（2026-09-14）明令关闭——三次实证复用陈旧 `compileTestKotlin` 产物造成片 `NoSuchMethodError` 假失败；同族风险见 [[pitfall-constructor-param-stale-test-classes]]。
- **`maxParallelForks>1`**：`mpv-testing.gradle.kts:124` 明确保持 1，多 fork 共享默认 test 沙盒会 PersistentFS 互踩成片异常。
- **传 `build/classes` + 项目 `.gradle/` 工件**：workflow 注释 2026-11-05 实测会触发同一 zip-FS 竞态（已撤回）；无执行历史时照样重编。
- **配置缓存工件化**（省 ~1m45/job）与**单 job 多片**（编译一次）：收益 0.5~1.5 分钟但风险不对等，未做，记在 `docs/经验总结/20261125-1615-CI分片耗时构成与提速取舍-经验总结.md`。

## 新版脚本行为（`dev-support/ci/run-gradle-retry.ps1`）

每次尝试独立 `gradle-run-N.log`，只读本次正文；三类分流：竞态→停 daemon+清项目侧 IPGP 缓存与 IDE 解包+`--max-workers=1`（仍命中才退干净树）；测试红→按 `build/test-results/**/TEST-*.xml` 取失败类，只重跑这些类一次（编译 UP-TO-DATE，~1 分钟），通过则 `::warning::` 点名放行；其它失败→立即红、不清盘。附带修三处会静默走错分支的缺陷：`GRADLE_USER_HOME` 缺失时 `Join-Path` 抛错让清缓存整段失效（本机 harness 复现）、单 token 参数被 `-split` 拼成一串、`Tee-Object` 输出污染函数返回值（需收尾 `Out-Host`，否则 `$r.Code` 为 null）。

验证：`-SelfTest` 7 项断言（含「测试红不得判为竞态」）+ 本机 fake-wrapper 四场景（纯编译错 1 次即退 / 竞态两次升干净树 / 竞态后成功 / 测试红只重跑失败类）全部符合预期。预期坏轮次 16m21s → ~10 分钟。