---
name: pitfall-das-seed-model-quarantined
description: 手写 seed 的 DAS 模型会被 IDE 判损坏隔离（.corrupted.*.xml +
  .reason.txt），拿不到表结构时唯一可行路径是在 IDE 里手工新建数据源；附 MSSQL identity 的元素名是
  SequenceIdentity 而非 AutoIncrement
metadata:
  node_type: memory
  type: reference
  originSessionId: sess_cf206a34-afa0-49b1-9d37-2d62fa9fee75
---

要让插件读到表结构（`DatabaseSchemaResolver` / `DasTable.getColumnAttrs`），**不能靠手写 `.idea/dataSources/<uuid>.xml` 喂模型**。

**实测（2026-09-23，262 沙盒 + IU-2024.3.7.1 两套都试过）**：手写的模型文件不是「没被采纳」，而是被 DAS **判为损坏并改名隔离**——目录里留下
`<uuid>.corrupted.<时间戳>.xml` 与同名 `.reason.txt`，原文件消失。所以日志里那句 `No model loaded for: <源名> (<uuid>)` 会一直不变，
看上去像「内省没触发」，实际是「seed 文件已经被移走了」。这个误导很值钱：上一轮为此白跑了六条路
（seed 文件 / 补 `<introspection-scope>` / 展开节点 / Refresh / 对话框新建 / 手喂模型），并据此错误登记成「环境受限未验证」。

**正确路径＝在 IDE 里手工新建数据源**，让 Database 插件走它自己的建源管线并自行内省（步骤见 [[runide-demo-verify-workflow]]）。
内省成功后 IDE 会写出一个 1 MB 量级的 `<新 uuid>.xml`，工具窗节点显示 `1 of 4` / `1 of 13` 之类的 schema 计数。

**✅ 注册表预写路线（2026-10-18 T-DevFullUI 战役实测可行，隔离的只是模型文件）**：被隔离的是 `.idea/dataSources/<uuid>.xml` **模型**；手写 **`.idea/dataSources.xml` 工程组件注册表条目**（IDE 自产格式：`<data-source source="LOCAL" name="…" uuid="…"><driver-ref>postgresql</driver-ref><jdbc-url>jdbc:postgresql://…</jdbc-url></data-source>`）**不被隔离、正常入模型**（打开日志出现 `No model loaded for: …` 属正常——模型等首连自建）。两个配套：①**口令留空**（不依赖存 IDE 级 master_key 的 secret-storage，跨沙盒重启无口令丢失面；真机首连时走 Test Connection→SCRAM 弹窗 Change Credentials 填入，Save=Until restart 重启即失效）；②**驱动免下载**＝把真机 IU 的驱动树（`config/jdbc-drivers/PostgreSQL/` 8 jar）整拷进沙盒 `config/jdbc-drivers/<Driver>/`。局限：**驱动定案/首连前的源不进控制台数据源下拉与 Services 视图**——须先经 IDE 数据源对话框触碰/首连一次才解锁会话选择器。注意 demo `.idea` 与用户日常 IDE 共享，预写的源会同时出现在日常 IDE。

**读模型文件时最容易搞错的一点：identity / 自增的元素名随方言不同**
- MySQL/PostgreSQL 那一套是 `<column>` 里的 **`<AutoIncrement>`**；
- **SQL Server 是 `<SequenceIdentity>1(3)</SequenceIdentity>`**（同一 `<column>` 下），**没有 `<AutoIncrement>`**。

上一轮手写 seed 时按 MySQL 的形态给 MSSQL 列写了 `<AutoIncrement>`，既过不了 DAS 的格式检查（被隔离），
也会让「自增列」这条判据在真实模型上取不到值。**引用某方言的 DAS 元素名前，先在真实内省产物里 grep 一遍**。

**Why**：把「模型文件不存在」当成「内省没跑起来」，会让人去反复折腾触发内省的开关（补 scope、点 Refresh），
而真因是文件被判非法；元素名搞错则会让读取代码看起来「逻辑对但读不到值」，进而误判成平台 API 不可用。

**How to apply**：需要 DAS 模型做验证时，直接进 IDE 手工建源；发现 `No model loaded` 先去 `.idea/dataSources/` 看有没有 `.corrupted.*`；
写/读列属性前先 grep 真实内省产物的元素名。相关：[[runide-demo-verify-workflow]]、[[native-diagram-relation-provider-null-ref]]。
