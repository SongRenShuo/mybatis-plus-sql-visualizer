---
name: pitfall-test-unique-data-by-clock-tail
description: 时钟漏进测试断言的两族形态——①nanoTime 尾巴拼「唯一」键会撞（共用限流计数）；②断言扫「整个导出产物」时命中自动生成的时间戳（毫秒恰为 001 即 1/1000 必红）；都改成不依赖时钟
metadata:
  node_type: memory
  type: project
  originSessionId: sess_fd128d0e-d320-4865-9871-208872610cd6
---

2026-11-02 openget 手机号登录用例（`PhoneLoginIntegrationTest`）出现「第 3 发就超限、发送前 ip 计数已是 4」的怪读数。

## 形态

用例需要「每个 case 一个唯一手机号 / 唯一来源 IP」来隔离小时配额与 60 秒重发锁，实现是取 `System.nanoTime()` 的后几位拼：

```java
String tail = Long.toString(System.nanoTime());
return "10.1." + (Integer.parseInt(tail.substring(tail.length() - 3)) % 200 + 10) + "." + tail.substring(tail.length() - 3);
```

`nanoTime` 只有末尾 3 位参与（~1000 个取值），密集调用下两个用例会拿到**同一个 IP**，于是共用同一个限流计数键。表现是：

- 第一次跑 10 例全绿（执行顺序凑巧，撞键的两个用例恰好没互相踩）
- 换 `-Dtest` 子集重跑就红，报「第 3 发不超限（发送前 ip 计数=4）」
- 断言消息里那句「发送前」是**假的**——`peekCounter` 在 assert 时求值，是发送后的数

## 修法

改成类级原子序号，不依赖时钟：

```java
private static final AtomicInteger IP_SEQ = new AtomicInteger(1);
private String uniqueIp() { int n = IP_SEQ.getAndIncrement(); return "10.9." + (n / 250) + "." + (n % 250 + 1); }
private String uniquePhone() { return String.format("138%08d", PHONE_SEQ.getAndIncrement()); }
```

手机号同理（`138` + 8 位序号 = 11 位，满足 `^1[3-9]\d{9}$`）。

**Why:** 这类失败最容易被误判成「限流实现有 bug」，去改生产代码；实际是被测系统没错、夹具不唯一。而且它天然是「一次绿一次红」的，单次通过不能当证据。

**How to apply:** 凡是测试里出现「按某某维度隔离计数/锁/配额」的夹具，唯一键**一律用原子序号或 UUID**，不要用时钟尾巴（`nanoTime`/`currentTimeMillis` 截位都只有千级取值）。同族判据：串行绿、换顺序或换子集就红，且失败信息指向共享计数器。

## 同族第二形态：断言扫整个导出产物，命中自动生成的时间戳（20261106 定因）

`ShardingSphereLogParserTest` 断言「导出产物不应含 `001`（占位符/控制字符残留）」，扫的是
`SqlExportService.format(...)` 的**整段输出**——而导出头部是
`-- Exported 1 SQL statements by MyBatis-Plus SQL Visualizer at 2026-11-06 08:22:36.001`。
毫秒恰为 `001` 时（1/1000 概率）断言必然红。

- 形态：CI 偶发红、本地与相邻轮全绿；失败信息只有一句空 `AssertionError`（断言没带消息时），完全看不出是时间戳。
- 定因靠**先给断言补消息**（打印 format 与产物片段）——下一轮 CI 直接给出 `产物=-- Exported … at …:36.001`。
- 修法：`exported.lines().filterNot { it.startsWith("-- Exported") }.joinToString("\n")` 后再扫。

**判据**：断言「产物不应含 X」时，先问产物里有没有**自动生成的**内容（时间戳、构建号、随机 ID、临时路径）。
有就必须先剔除生成段再扫，否则这是概率型假红，`1/1000` 也迟早撞上。
同族还有「断言取自身后重算而非构造期真实产出」（见 [[pitfall-fake-green-test-assertions]] ㉙）。

相关：[[pitfall-test-determinism-production-topic-and-async-gate]]、[[pitfall-fake-green-test-assertions]]、[[project-openget-multiclient]]、[[project-github-release-pipeline]]。
