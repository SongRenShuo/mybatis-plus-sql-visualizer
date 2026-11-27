# T-DrainRateTestIsolation — 排水速率用例跨用例串态导致 off-by-one 偶发红 — 开发计划

- 立项时间：2026-11-26 09:33
- 触发：`SenderLoopDrainRateTest > 风暴排水期CONFIG到达_即被处理且看门狗不误触发` 在 CI 二次复发
  （`expected:<512> but was:<513>`），前次见 master `docs/LESSONS.md` L102 证据（run 45578089046
  attempt 1），本次为 run 45730701535（提交 `0763bb643`，纯 docs 提交）`extras` job。
- 归属：`sub-projects/sql-visualizer-agent`（Java agent 子项目，测试面）

## 一、现象与证据

| 项 | 读数 |
|---|---|
| 失败断言 | `风暴排水期CONFIG到达_即被处理且看门狗不误触发` 末行 `assertEquals("风暴期处理的 CONFIG 不得影响事件投递完整性", DRAIN_TARGET, stub.outbound.eventCount())` |
| 实际读数 | `expected:<512> but was:<513>`（恰好多 1 条 EVENT 帧） |
| 复发次数 | 2 次（run 45578089046 attempt 1、run 45730701535），两次提交都与 agent 传输层无交集 |
| 复跑结果 | 同提交复跑即绿（两次都是 rerun 后全绿） |

## 二、根因（已证，非推断）

1. **共享静态队列**：`EventBus.QUEUE` 是 `private static final BlockingQueue`（容量 2048），
   整个测试 JVM 内所有用例共用；`EventBus.clearQueued()` 是唯一清理入口。
2. **执行序相邻**：JUnit4 默认 `MethodSorters.DEFAULT`（按方法名 hashCode 升序）在本类的顺序为
   `风暴期持续排水…` → **`持续风暴从未收到CONFIG…`** → **`风暴排水期CONFIG到达…`** → `静默期队列空…`
   （hashCode 实测：-591959699 / -340849885 / 751907909 / 1019529204）。即污染源与被污染用例**紧邻**。
3. **生产线程未 join**：`持续风暴从未收到CONFIG…` 起了一个不设上限的 daemon 生产线程：

   ```java
   while (producing.get()) { EventBus.offer(event("SELECT storm-" + (i++))); }
   ```
   finally 里只有 `producing.set(false); run.stop();`——**没有 `producer.join()`**，
   也**没有在本用例内 `clearQueued()`**。
4. **窗口**：置 false 后线程可能已通过 `producing.get()` 判断、正停在 `EventBus.offer(...)` 之前；
   主线程继续往下走 `run.stop()` → `@After tearDown` 的 `clearQueued()` → 下一用例 `setUp` 的
   `clearQueued()`，任何一步之后该线程的最后一轮 offer 落地，就在共享队列留下 **1 条**存量。
5. **放大**：下一用例句首 `prefillEvents(512)` 把存量算在自己的账上 → 队列 513 条 → 排水 513 条 →
   `assertEquals(512, 513)` 恰好差 1。这与观测到的 off-by-one 完全一致（不是丢事件、不是多写帧）。

**为什么不是生产代码缺陷**：`SenderLoop` 的事件投递完整性本身没问题——513 条是「队列里真的有 513 条」，
排水一条不差；缺陷在**测试之间的静态状态隔离**。

## 三、方案

### 3.1 确定性收尾（治本）

`持续风暴从未收到CONFIG_看门狗仍按窗口触发停采`：

- `finally` 内改为：`producing.set(false)` → `producer.join(2000L)` → 断言 `!producer.isAlive()`
  → `EventBus.clearQueued()`。join 之后不可能再有 offer 落地，清理才真正完成。

### 3.2 前提显式化（让污染自报家门）

`prefillEvents(int)` 填完后立刻断言 `EventBus.queuedCount() == count`，失败信息写明
「共享队列起点非空（上一个用例残留 N 条）」。`queuedCount()` 是包内可见（`static int`），
测试与 `EventBus` 同包，无需新增生产 API。

这样将来再有任何 polluter，红的是「起点不干净」而不是莫名其妙的 513，一次定位。

### 3.3 不改的东西

- 不动 `SenderLoop` 生产代码（缺陷不在那里，改生产码等于掩盖测试隔离问题）。
- 不改 `DRAIN_TARGET=512` 与各断言语义（样本量是刻意选 8×64 整批）。
- 不给 `EventBus` 加新 public API。

## 四、验收判据

| # | 判据 | 命令 / 期望 |
|---|---|---|
| V1 | 定向全绿 | `:sql-visualizer-agent:test --tests "*SenderLoopDrainRateTest"` → 4 例 / 0 失败 |
| V2 | 反向探针（缺陷层） | 临时撤 `producer.join` + 临时 `clearQueued`，并在 storm 用例 finally 末尾**显式补一条 `EventBus.offer(...)`** 模拟「最后一轮迟到的 offer」→ 后继用例必须在 `prefillEvents` 起点断言处红；还原后复绿 |
| V3 | 整个 transport 包回归 | `--tests "cn.song.mybatisplus.sqlvis.agent.transport.*"` 全绿（确认无其它 polluter 被新断言抓出） |
| V4 | 连续 3 轮同 JVM 顺序 | 同一条命令跑 3 次全绿（偶发项必须多次采样） |

## 五、待验证前提

- ~~「513 来自共享队列残留」~~ → 已由执行序 hashCode + 未 join 的生产线程 + 精确 off-by-one 三证。
- 前提 A：`EventBus.queuedCount()` 包内可见可直接从测试调用 —— **已核**（`EventBus.java:327` 为
  `static int queuedCount()`，测试同包 `cn.song.mybatisplus.sqlvis.agent.transport`）。
- 前提 B：`持续风暴…` 的 finally 之外没有别处 offer —— **已核**：agent 测试面只有本类存在
  `while(...) offer` 形态的生产线程（全包 grep 唯一命中）。

## 六、实施记录

**改动（`src/test` 单文件，不动生产码）**

1. `持续风暴从未收到CONFIG_看门狗仍按窗口触发停采` 的 finally：
   `producing.set(false)` → **`producer.join(2000L)`** → `assertFalse(producer.isAlive())`
   → `EventBus.clearQueued()` → `run.stop()`。join 之后不可能再有 offer 落地，清理才真正完成。
2. `prefillEvents(int)`：填之前断言 `EventBus.queuedCount() == 0`（消息写明「上一个用例残留 N 条」），
   填之后断言 `== count`。把「起点干净」这个隐含前提变成显式判据。

**读数**

| 判据 | 命令 | 读数 |
|---|---|---|
| V1 定向 | `:sql-visualizer-agent:test --tests "*SenderLoopDrainRateTest"` | **4 tests / 0 fail / 0 err / 0 skip**（XML 实读） |
| V2 反向探针 | 在失败用例体首行种 1 条残留 `EventBus.offer(event("SELECT red-probe-leftover"))` | **恰 1 红**：`共享队列起点必须为空：上一个用例残留 1 条… expected:<0> but was:<1>`；撤探针后复绿（4/0） |
| V3 包回归 | `--tests "cn.song.mybatisplus.sqlvis.agent.transport.*"` | **12 类 / 59 tests / 0 红**（新起点断言未误伤其它类） |
| V4 连跑 | `:sql-visualizer-agent:cleanTest :test --tests "*SenderLoopDrainRateTest"` × 3 | 3 轮全 BUILD SUCCESSFUL，末轮 4/0 |

**关于 V2 的口径**：反向探针落在**缺陷发生的那一层**（共享队列的残留），而不是改断言或改样本量；
因此它能证「起点断言看得见真实形态（1 条残留）」，但不能直接证「join 消除了竞态」——后者靠
机制（join 之后线程不可能再执行 offer）+ V4 连跑采样。这条不夸大：join 的收益是**消除窗口**，
不是让某个断言变红/变绿。

**未做**：没改 `DRAIN_TARGET`、没改任何断言语义、没给 `EventBus` 加 public API、没动 `SenderLoop`。

## 七、影响面

- 仅 `src/test`（agent 子项目），无用户可感改动 → 不动 `CHANGELOG.md`。
- 教训入册 `docs/LESSONS.md`：共享静态状态 + 未 join 的生产线程 = 偶发 off-by-one，收尾必须
  join 后再清理。