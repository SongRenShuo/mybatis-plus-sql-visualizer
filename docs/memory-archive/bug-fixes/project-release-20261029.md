---
name: project-release-20261029
description: v2026.10.29 发行（20261029 进行中，等用户 bug 修复并入本版）——范围 tag
  2026.10.28..HEAD＝T-StackBizFrame 修复 + T-PerfSweep28 三券性能，八处同步已就绪，首轮
  releaseGate 读数与 agent 环境性红，剩重跑门禁/tag/双推/Gitee 建版；用户已授权触发 gitee 工作流
metadata:
  node_type: memory
  type: project
  originSessionId: session_8fb4af0d4034e9ed136592efaba010d4
---

20261029 用户令「插件要发行今天的版本，参考下之前的流程和规范，更新发行相关信息，gitee 工作流允许触发」，
随后追加「你先把该准备的准备好，我需要修复一个 bug 加入这个版本发布」⇒ **本版范围在用户 bug 落盘后还要重估一次**。

**范围（`git log 2026.10.28..HEAD`，纯 docs/build 提交不计）**

- 修复 **T-StackBizFrame**：`@Scheduled` 等非 Web 线程下增强捕获的栈摘要/弹层看不到项目帧（栈底锚点只认自有 infra
  白名单、不含 `org.springframework.scheduling.*` / `io.micrometer.*`；修法＝三层锚点 + 定位帧成为摘要必含项 + 展示面三处）。
  技术档见 [[project-stack-digest-biz-frame]]。
- 性能 **T-PerfSweep28-01/03/04**：Mapper 双跳 gutter 接口前置 + 定位 memo；Hibernate 命名探测缓存 512 上限 +
  预览弹窗登记弱键化；StringSqlMethod gutter 方法体引号预筛。**T-PerfSweep28-05 只动测试**、T-ReleasePerf9 只动构建链 ⇒ 都不进对外清单。

**八处同步（已在工作树完成，未提交）**：`gradle.properties`=`2026.10.29`、`CHANGELOG.md` 新块、`gradle/plugin-change-notes.html`
新 `<h2>v2026.10.29</h2>` 8 条（inspect 扫「（ ） ：」零命中）、`update/preview.update.json`、新发行说明
`docs/发行说明/20261029-1345-定时任务调用栈项目帧修复与性能收口-发行说明.md`、`docs/MEMORY.md` 索引行 + 篇数改
「42 文件/36 篇按期说明」、`docs/变更记录.md` 台账行；`plugin.xml` description **不动**（本轮无新能力类别）。

**首轮 releaseGate 读数（21m02s，BUILD FAILED 在最后一环）**：`testShards` **8343/0/0/8**（778 类，Docker 引擎离线）；
`verifyGate` 三目标 Compatible（243 5/41/98、253 6/52/96、262 8/125/96）；`verifyMarketplace` 三目标 Compatible（18m29s，
0 未覆盖块）；**唯一红＝agent `AttachPoolWarmGateTest.模式B2_JDK8目标_Hikari池存量连接复用`**（子 JVM 连本机 MySQL 握手超时，
同轮定向复跑该类 11/0/0/1 全绿 ⇒ 环境性假失败，见 [[pitfall-regression-false-failure-flakes]]）；因构建在此中断，
**`e2eFakeIde` 未执行**（发行说明里已如实写「读数待最终轮」，没有拿旧读数冒充）。
产物 `mybatis-plus-sql-visualizer-2026.10.29.zip` 7,231,799 字节 sha256 `f3e9fa88…`、`inspect-plugin-zip.py` PASS。

**剩余步骤**：①用户 bug 落盘 + 写进三处文案 → ②重跑 `releaseGate`（不再用 `| tail`，见 [[pitfall-verification-blindspots-and-wrapup]]）
取权威读数 → ③发行提交 + 轻量 tag `2026.10.29` + `push-all.ps1` 双推 → ④`gitee-release.py --version 2026.10.29 --notes …`
建版 + 回读校验 + 登记 Gitee id 到 MEMORY 索引行（占位 `{GITEE_ID}` 待填）→ ⑤留用户：`update/update.json` 手改、
Marketplace 网页上传。**未注册并行任务的沙盒冲突**：本轮只跑测试/打包，未动 `sandboxContainer`。
