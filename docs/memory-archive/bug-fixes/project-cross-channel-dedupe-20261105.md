---
name: project-cross-channel-dedupe-20261105
description: T-CrossChannelDedupe 已实施并合 master（20261105，029864bf）——跨通道去重键失配致同次执行双条目的四笔提交、残余清理与复验状态
metadata:
  node_type: memory
  type: project
  originSessionId: sess_60421ef6-c5f0-4409-8381-b319787a1664
---

用户报障（20261105）：SQL Server + MyBatis-Plus `@Async` 里 `logMapper.insert` 的 INSERT 在 SQL 日志列表出现两条——`#11` 耗时/位置/库角标全空（控制台通道），`#13` 8ms + 1 rows + 源码位置（agent 通道）。结论：信息没丢，是双通道重复且未折叠，根因见 [[pitfall-cross-channel-dedupe-key-divergence]]。

计划册：`docs/开发计划/20261105-1241-T-CrossChannelDedupe-跨通道去重键失配致同次执行双条目-开发计划.md`（§9.1~§9.8 为实施记录）。

**已交付（master `029864bf`，四笔实质提交）**：

| 提交 | 内容 |
|---|---|
| `8301035b` | 主修：`SqlLogEntryBuffer` 增 `crossChannelIndex`（键 = `logTimeKey` + `logTimeParamSignature`；异通道 + 5s 窗命中走 `mergeDuplicateEntry` 原位融合；不计 ⨉N 徽章；与 `dedupeIndex` 同锁同生命周期）+ `跨通道同源未折叠`/`跨通道等价键融合` 两条限频诊断 + `flushSettledEntries` 补通道标记与循环内闸门复查 |
| `2cfd509c` | 残余一：`ParamCapture` 白名单补 `setNClob/setRowId/setSQLXML/setRef`、四参 `setObject` 上限 3→4（此前**织入器按 ≥2 参织了钩子、捕获层却按 >3 拒绝**，形成「织了不捕」）；`parseParams` 类型分组允许点号 |
| `4d423b4f` | 残余二：`paramSignatureToken` 把二进制族归一为 `<binary>`（`[B@hash` vs `<byte[]>` 值文本天然不可比） |
| `ee0547ec`/`029864bf` | 计划 §9.8 与索引状态 |

覆盖的形态：布尔 / 日期族 / LocalDateTime 的 ISO `T` / 注释内 `?` / 参数个数不等 / 点号类型名 / 二进制族。**§4.2（两套还原器渲染口径收敛）与 §4.4（控制台 DML 耗时估算）未做**——前者被「修捕获面 + 放宽解析器」替代，后者要动 `Parameters:` 行即时闭合时序。

反向验证读数：摘折叠分支 → 12 例恰 8 红；还原捕获面 → `ParamCaptureTest` 恰 2 红；还原正则 → 点号用例恰 1 红；摘二进制归一 → 恰 2 红。

**环境与清理**：复现走 DBX 本地 SQL Server 2022 `127.0.0.1:14333`（`connection_id=47b943be-d8a1-43a1-80cd-e09b6d5dbfbd`，容器 `mpsv-mssql` 原为 Exited，`docker start` 后可用）。三套工作树（`cd-impl`/`mpsv-cd2`/`mpsv-bin`）与对应分支均已删除；复现分支 `t-cross-channel-dedupe-repro` 及其工作树保留。**master 未 push**（远端停在 `e20edae7`，本地领先 12 笔，含另两个会话的 T-LogMethodName / T-MapperNavShortcut）。

桥为什么没让位（三条，需 idea.log 裁决）：旧 agent 无 CONFIG_ACK → `captureConfirmedActive()` 恒 false、桥永久放行；`CaptureActivityFallback` 活性回落解封窗；CONFIG 下发→回执窗口抖动。判定 grep：`捕获归因|重复观测跨通道融合|增强捕获闸门回落|增强捕获活性回落|增强捕获接管沿|CONFIG 已生效`。

**Why:** 这个症状看起来像「捕获丢了字段」，实际是两通道各存一份；按「补字段」方向修会白做，必须从去重键口径入手。两条「前提证伪」（参数个数不等、点号类型不可达）最后都是回到源头修捕获面/解析器解决，而不是给去重加二级匹配。

**How to apply:** 后续同类报障先看是否同一秒两条、其中一条三字段全空；真机复验判据＝列表里该 INSERT 只剩一条带耗时位置 + `idea.log` 里 `跨通道同源未折叠` 计数为 0。复验要**复用原复现代理**（SendMessage 续跑，它握着复现夹具与九形态分叉表），不要新派。
