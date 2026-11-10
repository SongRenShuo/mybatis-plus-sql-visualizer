---
name: pitfall-unregistered-jdbc-noise-vs-registration
description: 「发现未登记 JDBC 实现」的判别法——驱动把 java.sql.Connection
  摊在多层继承链上时，链上其余类是包装层噪音而不是白名单缺口；登记它们会零命中
metadata:
  node_type: memory
  type: project
  originSessionId: sess_7d697b0d-ac13-4572-98f7-54950999cf1e
---

# 「未登记 JDBC 实现」≠ 需要登记

2026-11-09 现场（用户日常 IDEA 2026.3 + `v2026.11.8`）：SQL 日志空态诊断行常驻 8 条「未登记 JDBC 实现」，恰好占满 `CaptureBudget.MAX_UNREGISTERED`（=8，先到先得，满即静默丢弃）。8 条 = 5 个 Hikari 池代理 + 3 个 Oracle 连接层类。

**Oracle 驱动真实层级**（本机 ojdbc11 23.3.0.23.09 与 23.7.0.25.01 字节实测同形，自写 class 解析器读 constant pool，本机 JDK8 的 javap 读不了 major 55）：

```
Object → OracleConnectionWrapper → OracleConnection → GeneratedPhysicalConnection
       → PhysicalConnection（白名单 prepare 面） → T4CConnection
```

declared `prepareStatement`/`prepareCall` 计数：`OracleConnectionWrapper` 14 / `OracleConnection` **0** / `GeneratedPhysicalConnection` **0** / `PhysicalConnection` 22 / `T4CConnection` 0（全继承）。

**判据**：白名单只该登记真正声明 prepare 族的那个类（这里是 `PhysicalConnection`）。把链上其余类登记进 `WeaveSpecs` 会零命中，直接撞 `StatementWeaverVendorCoverageTest` 的「织入产物必须 changed——登记了却零命中即假覆盖」断言。

**危害不是难看，是取证出口失效**：上限 8 被包装层占满后，真出现未登记驱动会被静默挤掉。

**修法**：同族继承链双向排除（候选是白名单类的祖先 / 白名单类在候选祖先链上）只影响曝光面，`ACCEPTED`/retransform/计数一律不动。Hikari 5 条已由 `886a0d33`（T-SqlListCatchup 券 G1，前缀表）修掉但**未发版**（最新 tag `2026.11.8`，G1 在 `[Unreleased]`）。

**Why:** 这条诊断天然诱导「去补白名单」，补了反而红；且包装层与真缺口的形态在名字上看不出区别。

**How to apply:** 遇到这条诊断先查两件事——该类的 declared 方法数是否为 0、现场 `接口兜底匹配 N（已织入 N）` 是否已覆盖它。已覆盖 + 零声明 = 噪音，按同族/包装层排除；未覆盖 + 有声明 = 真缺口，才补白名单。

计划与逐条证据：`docs/开发计划/20261109-1607-T-CatchAllFamilyFilter-Oracle驱动连接层被误报未登记JDBC实现-开发计划.md`。相关：[[reference-cn-db-driver-families]]、[[pitfall-oracle-statement-wrapper-registry-mismatch]]
