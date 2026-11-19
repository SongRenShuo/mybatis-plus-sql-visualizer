---
name: pitfall-mustrunafter-lost-on-skipped-aggregate
description: 把 Gradle mustRunAfter 挂在聚合任务上会失效——任一依赖失败时聚合任务被跳过，跳过任务的排序边一并丢弃，被约束的任务提前与剩余叶子并发（releaseGate 的 e2e 实测早跑 4 分钟）
metadata:
  node_type: memory
  type: project
  originSessionId: session_4be84543-82ad-434c-bee2-874cc717cd69
---

**形态（20261030 T-ReleasePerf10 实测，`releaseGate` 轮）**：`tasks.named("e2eFakeIde") { mustRunAfter("testShards") }`——`testShards` 是纯聚合任务（只 `dependsOn` 五个 `testShardN` + `testExternal`）。权威轮用 `--continue` 跑到底，`testShard2` 有失败用例 ⇒ **`testShards` 因依赖失败被 Gradle 跳过** ⇒ **跳过任务的排序边不再约束任何人**（Gradle 语义：被跳过的任务不参与排序）⇒ `e2eFakeIde` 在剩余分片还在跑时起跑。

实测证据：e2e START 12:13:20 < shard5 END 12:17:24（早 4 分钟）。而 e2e 会启动真实应用 + 占用 MySQL 端口，与分片并发有互相污染的风险。

**修法**：把定序挂到**叶子任务**而不是聚合任务——

```kotlin
tasks.named("e2eFakeIde") {
    mustRunAfter(tasks.matching { it.name.matches(Regex("testShard[0-9]+")) })
    mustRunAfter("testExternal")
}
```

（Kotlin 字符串里正则要写 `[0-9]+` 或 `\\d+`；`"\d"` 是非法转义，编译直接报错。）这样即使某个 shard 失败，e2e 仍等所有分片收尾——`--continue` 语义不变（e2e 照样跑，只是不再并发）。

**验证法（可复用）**：挑一个**必然失败**的叶子（本仓 `testShard2` 含 4 例文档站方言表已知红）跑 `./gradlew testShard2 e2eFakeIde --continue`，只看两个任务的 START/END 毫秒锚点先后；修复后实测 shard2 12:25:53 失败收尾、e2e 12:30:06 才起跑、28s 通过。

**判读要点**：只看「任务都执行了」看不出这个缺陷——`mustRunAfter` 失效不会报错、不会跳过任何任务，只是**时间线变了**。要抓到它必须比对同一次构建里的起止戳。

相关：[[release-gate-timing-floor]]、[[project-releaseperf10-watermark-lanes]]、[[pitfall-verification-blindspots-and-wrapup]]
