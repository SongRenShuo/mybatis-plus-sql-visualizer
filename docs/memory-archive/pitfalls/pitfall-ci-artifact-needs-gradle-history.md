---
name: pitfall-ci-artifact-needs-gradle-history
description: CI 把编译产物传给下游 job 却仍每片重编译——UP-TO-DATE 判据在项目级 .gradle 执行历史里，不在
  build/ 产物里；且 upload-artifact 默认跳过隐藏目录。⚠️ 2026-11-05 run#8 实测：把 .gradle 塞进工件会让
  分片编译期撞 ClosedFileSystemException（一片红），已撤回——机制成立但这条修法被证伪一次
metadata:
  node_type: memory
  type: project
  originSessionId: sess_1caa1cd6-ecff-4769-98f4-48bfeeca1eb0
---

# 传了编译产物，下游照样重编译（2026-11-05 run#7 实测；修法在 run#8 被证伪后撤回）

## 现象

matrix 拆分后，`build` job 已经把 `build/classes`、`build/libs`、`build/distributions` 打进 `build-outputs` 工件，`test`/`verify`/`marketplace` 各自下载。但下游 job 的日志里：

```
> Task :compileKotlin          ← 1m45s
> Task :jar                    ←   30s
> Task :compileTestKotlin      ← 1m00s
> Task :testShard4             ← 3m15s（真正的测试）
```

每个分片花 **2 分 45 秒重编译**，真正跑测试只有 3 分钟。五个分片白烧约 14 分钟机时。

## 真因（两条，都属静默型，机制本身已证实）

**① Gradle 判「任务是否 UP-TO-DATE」靠的是项目级 `.gradle/<gradle版本>/` 里的执行历史与输入指纹**（`executionHistory` / `fileHashes`），不是 `build/` 里的产物。产物在、历史不在 ⇒ 全部任务视为未执行，重新跑一遍（输出虽相同）。

**② `actions/upload-artifact` v4.4+ 默认 `include-hidden-files: false`**，`.gradle` 是隐藏目录，会被**静默跳过**——因为同一 `path:` 列表里还有别的命中项，`if-no-files-found: error` 也不会报错。于是「我明明加了 `.gradle/`」这一步看起来生效、实际零文件。

## ⚠️ 那条「把 .gradle 传下去」的修法已撤回（run#8 实测反噬）

`34269a86` 曾按上面的推理把 `.gradle/` 加进 `build-outputs` 并开 `include-hidden-files: true`。run#8 结果：**test(4) 编译期直接红**，3.6 分钟即失败：

```
Unable to read descriptor [plugin.xml] from [.../transforms/cfd9113.../transformed/ideaIU-2024.3.7.1-win/plugins/DatabaseTools/lib/database-plugin.jar]
java.nio.file.ClosedFileSystemException
...
* What went wrong: Could not determine the dependencies of task ':compileMcpStubJava'
   > Could not find bundled plugin with ID: 'com.intellij.database'
```

同一次运行里该 job 的 `gradle-transforms` 缓存恢复只花 **77s**、体积明显小于 run#7 的 **117s / 4775MB**（换提交后子 key 全部换新，恢复的是 build 刚重存的那份不完整条目）——即变换缓存没真正到位，编译期重解 243 的 IDE 包时撞上被关闭的 zip 文件系统。

处置：`7e393ba4` 撤回该改动，工件恢复为只传 `build/` 产物。**结论：收益（每片约 2 分钟）与「发布门禁偶发红」的风险不对等。要再试必须先把 transform 缓存恢复口径（setup-gradle 同时往 `caches/transforms-4/*` 与 `caches/*/transforms/*` 两个 glob 恢复，而 Gradle 9.7 实际用 `caches/9.7.0/transforms`）与并发时序验干净，别只凭机制推理就上。**

另一条同样被堵死的路：**不要**用「缓存上一提交的 `build/` + restore-keys」做跨提交增量——本仓 `org.gradle.caching=false` 正是三次复用陈旧 `compileTestKotlin` 产物造成成片 `NoSuchMethodError` 假失败后关掉的（`gradle.properties` 第 14~20 行，另在 `settings.gradle.kts` 的 `buildCache.local` 落闸）。

## 同族：Node 20 弃用告警（已修并验证）

`upload-artifact@v4` / `download-artifact@v4` 的 `runs.using` 是 `node20`，runner 强制用 node24 跑，Actions 页面每个 job 一条 warning（一次运行 9 条）。node24 默认从 **upload v6 / download v7** 开始，升到当前最新 `upload@v7` + `download@v8` 即可；本仓用法（多路径打包上传、按 `name` 整包下载、跨 run 用 `run-id`+`github-token`）在 v7/v8 无破坏性变化（v8 新增「下载哈希不匹配默认报错」，托管 runner 上只有好处）。

**判据（别只看日志里搜不到就以为好了）**：注解不在 job 日志里，要查 `GET /repos/{owner}/{repo}/check-runs/{id}/annotations`。run#8 实测 `build` 与 `test(1)` 各 **0 条**（run#7 是 9 条），只剩一条真实失败的 `Process completed with exit code 1.`。

相关：[[project-github-release-pipeline]]、[[pitfall-ci-slow-runner-amplifies-time-threshold-tests]]、[[pitfall-targeted-test-unmatched-pattern-false-green]]。
