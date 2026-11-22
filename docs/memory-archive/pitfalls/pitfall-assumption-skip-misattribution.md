---
name: pitfall-assumption-skip-misattribution
description: 门禁里 Assumption 跳过的归因必须读 skipped message 原文——本仓 Docker 方言用例有两道闸门（开关 -PmpvDockerTests 在前、daemon ping 在后），20261021 把「开关没开」误写成「daemon 不可用」
metadata:
  node_type: memory
  type: project
  originSessionId: session_dd01afde-607b-4ca3-a136-3c9e32c3b3b1
---

# Assumption 跳过 ≠ 环境不行：必须读 skipped message 原文归因

2026-10-21 T-PerfSweep26 收口：全量 `testShards` 得 8106/0/0/9，9 个跳过。只读收口代理报「Docker daemon 未启动致 5 例跳过」，我照抄进验收文档/计划/变更记录三处 —— **归因是错的**，随后用户一句「方言用例为什么跳过」才逼出来。

## 实测机制（`DialectDockerVerificationTest.kt:51-66`）

两道 `assumeTrue`，顺序决定归因：

```kotlin
assumeTrue("Docker 测试未启用（-PmpvDockerTests=true 开启）…",
    System.getProperty("mpvs.docker.tests.enabled") == "true")   // ① 开关闸门
val available = DockerClientFactory.instance().client().pingCmd().exec() != null
assumeTrue("Docker 不可用…", available)                          // ② daemon 探测
```

本轮没传 `-PmpvDockerTests=true` ⇒ 在 ① 就跳了，**从未执行过 daemon 探测**。daemon 本身确实也没起（`docker ps` 报 `npipe:////./pipe/dockerDesktopLinuxEngine` 不存在），但那是**叠加因素**，不是直接原因。开关前置是 2026-09-23 用户定的例（daemon 半活态时 ping 会阻塞且无超时，发行门禁实录过三片挂起；容器 pull/run 耗时不可控）。

## 判据

1. **读 `build/test-results/**/TEST-*.xml` 里 `<skipped message="…">` 原文再归因**，别从「现象合理」倒推原因——两条闸门同时不满足时，报出来的 message 只说明先命中的那条。
2. 写成「A 叠加 B」时明确标注**哪条是直接原因**、以及**本轮实际执行到哪一步**（本例须写明「未真正探测 daemon」）。
3. 真跑这 5 例需两件事同时满足：启动 Docker Desktop **且** 加 `-PmpvDockerTests=true`（只起 daemon 不够，这是最容易被下一个人踩的点——文档若只写「Docker 不可用」，他会起完 daemon 仍见跳过）。
4. 同族：[[pitfall-blame-environment-before-proving-path]]（先证明路径被走到）、[[pitfall-fake-green-test-assertions]]（跳过也是「没跑到」的一档，与 ㉒ NoClassDefFoundError 同类：报错时机早于用例体）。

相关：[[project-perfsweep26-batch]]（更正提交 `1a6283c8`，三处文档同步改）。
