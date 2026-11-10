# T-CatchAllFamilyFilter：Oracle 驱动内部连接层被误报「未登记 JDBC 实现」（2026-11-09）

- 立项：2026-11-09 16:07
- 状态：**已实施**（提交 `4fdd7d5e`；agent 模块 50 类 286 例 0 failures 0 errors 1 skipped；反向验证成对两红两绿；未跑 IDE 侧全量与门禁，按券纪律交收口轮/CI；真机 GUI 观感待用户验收）
- 目标分支：`master`
- 来源：用户在日常 IDEA 2026.3 使用 `v2026.11.8`（内部构建号 `MPV-Build: 1794049171707`，2026-11-07 18:59:31）时，SQL 日志面板空态诊断行常驻 8 条「未登记 JDBC 实现」
- 取证方式：用户截图读数 + `master`（`30fdcab2`）源码静态走查 + 本机 `ojdbc11` 23.7.0.25.01 / 23.3.0.23.09 两个 jar 的字节实测（自写 class 解析器读 constant pool 的 `this_class/super_class/interfaces` 与 declared methods，不依赖 javap；本机只有 JDK 8 的 javap，读不了 major 55）。**未跑构建、未跑测试。**

---

## 一、结论

1. 截图里的 8 条里，**5 条 Hikari 池代理已经修掉**（`886a0d33`，T-SqlListCatchup 券 G1），但**尚未发版**——最新 tag 是 `2026.11.8`（2026-11-07），G1 还在 `CHANGELOG.md` 的 `[Unreleased]` 段。用户跑的是正式版，所以看得到。
2. 剩下 **3 条 Oracle 是同一类噪音**：`oracle.jdbc.driver.GeneratedPhysicalConnection` / `oracle.jdbc.driver.OracleConnection` / `oracle.jdbc.OracleConnectionWrapper` 是 Oracle 驱动自身连接继承链上的**基类与包装器**，不是驱动。
3. **不需要、也不能靠「登记」解决**：这三个类声明的 `prepareStatement` / `prepareCall` 方法数都是 0，登记进 prepare 面会零命中，直接违反 `StatementWeaverVendorCoverageTest` 的「登记了却零命中即假覆盖」断言（见 §四）。
4. 真正的危害不是「提示难看」，而是**取证出口被噪音占满**：上报上限 8 条（`CaptureBudget.MAX_UNREGISTERED`，`CaptureBudget.java:101`），用户现场恰好 8 条全被包装层占满。真出现未登记驱动时，它会被静默挤掉——这个诊断存在的唯一理由就失效了。
5. 修法＝把 G1 的「包装层不参与曝光」语义从**连接池前缀表**推广到**驱动同族继承链**：候选类只要是某个白名单类的祖先或子孙，就说明该厂商已被登记，不算「未登记驱动」。织入面零变化。

---

## 二、现象与证据

### 2.1 用户现场读数（2026.11.8 / IDEA 2026.3）

空态诊断行原文：

```
捕获中｜增强捕获：已挂载 pid=48760｜已收事件 94 条｜接口兜底匹配 8 个驱动（已织入 8 个）｜发现未登记 JDBC 实现：
  com.zaxxer.hikari.pool.HikariProxyPreparedStatement,
  com.zaxxer.hikari.pool.ProxyPreparedStatement,
  com.zaxxer.hikari.pool.ProxyStatement,
  com.zaxxer.hikari.pool.HikariProxyConnection,
  com.zaxxer.hikari.pool.ProxyConnection,
  oracle.jdbc.driver.GeneratedPhysicalConnection,
  oracle.jdbc.driver.OracleConnection,
  oracle.jdbc.OracleConnectionWrapper
  （未纳入驱动白名单，增强仅靠接口兜底）
控制台：已挂载 1 个，已扫描 567 行，产出 0 条｜重放守卫已丢弃 25 行｜EP 通道已追平 454 行
```

关键读数：`接口兜底匹配 8（已织入 8）` 与 8 条未登记**一一对应**——这 8 个类全部已被兜底织上，采集不受影响。上限 8 已占满。

### 2.2 Oracle 驱动的真实层级（jar 字节实测）

`ojdbc11` 23.7.0.25.01 与 23.3.0.23.09（后者是 agent 子项目的 `testImplementation` 版本）层级完全一致：

```
java.lang.Object
└─ oracle.jdbc.OracleConnectionWrapper                    implements oracle.jdbc.OracleConnection
   └─ oracle.jdbc.driver.OracleConnection
      └─ oracle.jdbc.driver.GeneratedPhysicalConnection     (abstract)
         └─ oracle.jdbc.driver.PhysicalConnection           (abstract)   ← 白名单 prepare 面
            └─ oracle.jdbc.driver.T4CConnection             ← 实际连接类，未白名单
```

declared methods 实测（只看 `prepare*` / `createStatement` / `execute*`）：

| 类 | prepareStatement/prepareCall 声明数 | 是否白名单 |
|---|---|---|
| `oracle.jdbc.OracleConnectionWrapper` | 14（`prepareStatement` ×6 重载 + `prepareCall` ×3 + `*WithKey` 等） | 否 |
| `oracle.jdbc.driver.OracleConnection` | **0** | 否 |
| `oracle.jdbc.driver.GeneratedPhysicalConnection` | **0** | 否 |
| `oracle.jdbc.driver.PhysicalConnection` | 22（`prepareStatement` 全重载 + `prepareCall` 全重载 + 内部 `prepare*Internal`） | **是** |
| `oracle.jdbc.driver.T4CConnection` | 0（全部继承 `PhysicalConnection`） | 否 |

结论：**只有 `PhysicalConnection` 真正声明了 prepare 族**，现有白名单登记位置是对的；链上其余类登记进去必然零命中。

### 2.3 代码路径（唯一曝光点）

- `CatchAllMatcher.evaluate`（`CatchAllMatcher.java:510`）：`isWhitelisted`（:590）→ `excludedName` → `cheapExcluded` → 扫描预算 → `interfaceSpecOf` → `!isKnownPoolWrapper`（:120）→ `CaptureBudget.noteUnregisteredDriver`（:535）。
- `CaptureBudget.noteUnregisteredDriver`（`CaptureBudget.java:331`）：有界 8 条、去重、先到先得，满了静默丢弃。
- `isKnownPoolWrapper` 只覆盖六族**连接池**前缀（`CatchAllMatcher.java:110-117`）。
- IDE 侧只渲染快照，不参与判定：`CaptureDiagnostics.attachCoverageNotes`（`CaptureDiagnostics.kt:78-105`）→ `log.diag.attachUnregistered`（`MpSqlVisualizerBundle_zh.properties:846`）。

全仓 grep 确认 `noteUnregisteredDriver` 只有 `CatchAllMatcher.java:535` 一个调用点——**曝光面单点在 agent 侧**，IDE 侧零改动。

---

## 三、根因链

1. Oracle 驱动把 `java.sql.Connection` 的实现摊在一条 5 层继承链上（§2.2），白名单只登记了真正声明 prepare 的 `PhysicalConnection`；
2. 兜底扫描用 `java.sql.Connection.class.isAssignableFrom(c)` 判定（继承可见），于是链上其余 3 个类全部命中 prepare 面并被织入；
3. 曝光判据只有两条——「在不在白名单」+「是不是已知连接池前缀」——都不覆盖「同族继承链」；
4. 于是这 3 个类被当作「下一个未知驱动」记账；再加上上限 8 先到先得，真取证位被挤掉。

---

## 四、为什么「登记」是错误修法

三条独立证据，任一条都足以否决：

1. **零命中即假覆盖**：三个类 declared prepare 方法数为 0（§2.2 实测）。`WeaveSpecs.putSpec` 登记后，`StatementWeaverVendorCoverageTest.assertWovenAndVerifiable` 断言「必须被真实织入（changed）——登记了却零命中即假覆盖」会直接红。这是仓内既有的反假覆盖红线，不能绕。
2. **采集零增益**：三者现场已经由兜底织入（`interfaceWoven=8`），登记只是把「兜底命中」换个名义叫「白名单命中」，采集语义一字不变。
3. **语义错位**：这条报告要回答的是「现场到底是哪个**驱动**没登记」（T-IbatisPanwei 券 D 的立项动因）。基类/包装器不是驱动，登记它们等于把取证出口改成一个永远回答不了原问题的清单。

---

## 五、修法设计

### 5.1 推荐方案 A：同族继承链排除（双向）

只动曝光面，不动织入面。

- **预扫**：`runPass`（`CatchAllMatcher.java:435`）在评估前遍历一次 `loaded`，对每个 `isWhitelisted(name)` 的类走 `getSuperclass()` 链，把祖先类名收进本轮 `whitelistAncestors`。有界：白名单类最多约 40 个 × 链深 ≤6。
- **判定**：`evaluate` 在 `noteUnregisteredDriver` 前加两道，命中任一即跳过曝光：
  - 候选名 ∈ `whitelistAncestors` → 候选是白名单类的**祖先**（本单的 3 个 Oracle 类）；
  - 候选自身父类链里出现白名单名 → 候选是白名单类的**子孙**（如 `T4CConnection`）。
- **织入面零变化**：`ACCEPTED`、`retransform`、`matched/wove/failed` 计数、`isKnownPoolWrapper` 全部不动；只改「要不要记账曝光」这一处。

伪代码（Java，与现文件同风格）：

```java
// runPass：评估前建一次本轮快照
final Set<String> whitelistAncestors = collectWhitelistAncestors(loaded);
...
evaluate(inst, c, name, stats, whitelistAncestors);

/** 白名单类的祖先名集合（只做名字收集，不初始化任何类）。 */
private static Set<String> collectWhitelistAncestors(Class<?>[] loaded) {
    final Set<String> out = new HashSet<String>();
    for (Class<?> c : loaded) {
        try {
            final String n = nameOf(c);
            if (n == null || !isWhitelisted(n)) continue;
            for (Class<?> s = c.getSuperclass(); s != null; s = s.getSuperclass()) {
                final String sn = nameOf(s);
                if (sn == null) break;
                out.add(sn);
            }
        } catch (Throwable ignore) {
            // 单类解析失败 = 该类不参与同族判定，绝不影响整轮
        }
    }
    return out;
}

/** 同族判定：候选是白名单类的祖先，或白名单类在候选的祖先链上。 */
private static boolean isWhitelistFamily(Class<?> c, String name, Set<String> whitelistAncestors) {
    if (whitelistAncestors.contains(name)) return true;
    try {
        for (Class<?> s = c.getSuperclass(); s != null; s = s.getSuperclass()) {
            final String sn = nameOf(s);
            if (sn == null) return false;
            if (isWhitelisted(sn)) return true;
        }
    } catch (Throwable ignore) {
        return false;
    }
    return false;
}

// evaluate：曝光前
if (!isKnownPoolWrapper(name) && !isWhitelistFamily(c, name, whitelistAncestors)) {
    CaptureBudget.noteUnregisteredDriver(name);
}
```

### 5.2 否决的备选

- **B：把 `oracle.jdbc.` 加进 `POOL_WRAPPER_PREFIXES`**。会让未来 Oracle 新语句类对取证出口整族失明，且「连接池前缀」语义被污染。
- **C：把三个类名硬编码进排除表**。能解本单，但 SQL Server / PG / 达梦 的基类噪音下次还得逐个补名字，是把一次性缺陷固化成长期维护项。

### 5.3 已知边界（写进实现注释，不扩范围）

- 只做**继承链**同族。**接口委托型包装**（不继承白名单类、只 implements 接口并持有一个内层连接）不在本券面——本单的 `OracleConnectionWrapper` 恰好在链上，所以没漏；未来若出现纯委托包装，按名登记或另立券。
- 若白名单类本身未被加载，它的祖先自然也不会被加载，同族判定不会误吞任何在册类。
- 同族判定只影响「曝光」，不影响「是否织入」——候选照旧走 `ACCEPTED` + `retransform`。

---

## 六、影响面与写集

| 面 | 文件 | 改动 |
|---|---|---|
| agent 生产 | `sub-projects/sql-visualizer-agent/src/main/java/cn/song/mybatisplus/sqlvis/agent/CatchAllMatcher.java` | +约 40 行（604 → ~645），`runPass`/`evaluate` 签名各加一个参数 |
| agent 测试 | `.../agent/CatchAllMatcherTest.java` | +3~4 例（见 §七） |
| IDE 侧 | 无 | 零改动（协议、bundle、诊断渲染全部不动） |

无协议变更、无 bundle 变更、无设置项、无平台 API 触碰。

---

## 七、测试计划

### 7.1 夹具来源

`ojdbc11:23.3.0.23.09` 已在 `sub-projects/sql-visualizer-agent/build.gradle.kts` 的 `testImplementation` 里（`StatementWeaverVendorCoverageTest` 的既有夹具），**真 Oracle 类可直接用**，不需要隔离 loader、不需要新增依赖。加载一律 `Class.forName(name, false, loader)`（不初始化）。

### 7.2 用例

1. `Oracle同族三类_不报未登记但仍织入`（attach 级）
   - loaded = {`PhysicalConnection`（白名单）, `GeneratedPhysicalConnection`, `OracleConnection`, `OracleConnectionWrapper`, `FakeVendorFixtures$VendorPs`（真未知）}
   - 断言：未登记快照**只含** `VendorPs`；四个 Oracle 类仍全部进 `ACCEPTED` 且被 retransform；`matched/wove` 计数不变。
2. `白名单子类_不报未登记`（子孙方向）
   - loaded = {`PhysicalConnection`, `T4CConnection`} → 未登记为空，`T4CConnection` 仍织入。
3. `同族判定_不放行真驱动`（反向对照，防误吞）
   - `VendorPs` / Hikari 池类 / 无关普通类 → 判定为 false；池类仍按 G1 前缀排除。
4. `同族判定_纯函数正反例`（可选，字符串级）
   - 用构造的祖先链样本覆盖「祖先命中 / 子孙命中 / 无关类 / 空链 / 链中断」五态。

### 7.3 反向验证（S2，必须成对）

- 摘掉「祖先方向」判定 → 用例 1 必红；
- 摘掉「子孙方向」判定 → 用例 2 必红；
- 恢复后全绿。两次读数都要贴出来（只贴一次不算）。

### 7.4 回归面

`CatchAllMatcherTest` 全类 + `CaptureBudgetTest` + `HeartbeatDiagnosticsTest`（agent 侧定向，`--tests` 精确类名，核对 test-results XML 的用例数）。

---

## 八、待验证前提（实施者先证伪再动手，报告逐条给结论）

- **V1**：Oracle 19c（ojdbc8）/ 21c 的层级是否与 23.x 同形？→ 只要 `PhysicalConnection` 仍在链上、且白名单类被加载，祖先方向就成立；子孙方向取决于是否有未白名单子类。若形态不同，按实测版本登记，不扩判定。
- **V2**（2026-11-09 已部分证伪）：真机上 `T4CConnection` 为什么**没有**出现在 8 条里？原计划的取证路径（读 agent stderr 的 `scan=` 行）**已确认不可用**：用户现场 agent 以 `logLevel=warn` 注入（IDE 日志 2026-11-09 15:41:18 `premain 注入完成（config=CyzcPlatformApplication）：-javaagent:…=port=60389;logLevel=warn;stackFrames=24`），而 `CaptureBudget.diag()` 只在 `verboseLogging`（`logLevel=debug|verbose|trace`）下输出——**`scan=` 行在任何日志里都不存在**。同一段日志还确认 pid=48760 走的是 premain 通道（`premain 会话建立：pid=48760（启动即捕获，attach 链已跳过）`），不是 attach。结论：V2 降级为不阻塞——子孙方向成本极低且语义正确，保留实现；若日后要取证，把 `logLevel` 调到 debug 重跑一次即可。
- **V3**：`getSuperclass()` 是否只触发加载、不触发 `<clinit>`？JDK 语义上安全（类初始化只由 `new`/静态访问/反射初始化触发），但用例里要用未初始化类验证一次，避免误判成「为了排除噪音把驱动初始化了」。
- **V4**：预扫成本。白名单类约 40 个、链深 ≤6，`whitelistAncestors` 规模 ≤240；`loaded` 多一遍遍历（2 万类约 1~2ms）。若实测偏高，可只在 `prioritize` 分支建集合（大应用）——但小应用同样需要，故默认无条件建，读数说话。
- **V5**：`isKnownPoolWrapper` 与同族判定的先后。建议先池前缀后同族（语义正交、互不依赖）；若 Hikari 场景下两者都命中，以先命中者为准，不重复记账。

---

## 九、验收判据

1. **现场复现**：Oracle + Hikari 的应用 attach 后，空态诊断行**不再出现**「发现未登记 JDBC 实现」；`接口兜底匹配 N 个驱动（已织入 N 个）` 读数与修复前一致（织入面零变化的直接证据）。
2. **真未知驱动仍报**：同一进程里注入一个假驱动实现（测试用 `VendorPs`），该行仍出现并**点名它**。
3. **反向验证**：两处判定各摘一次，各恰红一次，恢复全绿，两次读数入计划册。
4. **定向读数**：agent 侧定向 3 类，用例数与 `test-results` XML 对得上（S3 口径），0 failures / 0 errors。
5. 真机 GUI 观感（诊断行消失）由用户验收——按「GUI 验收用户自做」的既有约定，交付时给编号步骤与判据。

---

## 十、范围外与后续

- **「已收事件 94 条 / 列表 0 条」不在本券**：这是另一条线（事件到了但没上屏）。若用户侧列表不是刚清空的，需要单独取证——可能落在去重、屏蔽规则或消费侧过滤。建议单独立券，本券不碰。
- **Hikari 5 条**：已由 `886a0d33` 修复，等下次发版自动消失，本券不重复处理。
- **`update/update.json` 仍停在 `2026.11.8-beta.1`**：该文件按仓库纪律只有用户本人能改，本券不动。
- 若后续出现**接口委托型**未登记包装（§5.3 边界），按名登记或另立券，不在本券扩范围。

---

## 十一、实施记录（2026-11-09）

### 11.1 落地内容（提交 `4fdd7d5e`）

- `CatchAllMatcher.java`（+87/-4）：
  - `runPass` 增 `collectWhitelistAncestors(loaded)`——遍历本轮已加载类，对每个 `isWhitelisted(name)` 的类走 `getSuperclass()` 链收集祖先名（只做名字收集，不初始化、不加载新类；单类异常吞掉）；集合以参数传入两条扫描路径的 `evaluate`。
  - `evaluate` 曝光门改为 `!isKnownPoolWrapper(name) && !isWhitelistFamily(c, name, whitelistAncestors)`。
  - 新增 `isWhitelistFamily`（包私有，便于直测）：祖先方向查集合，子孙方向走候选父类链看是否出现白名单名。
  - 类级 KDoc 与 `evaluate` 内注释同步（券 D/G1/本次三条曝光排除规则并列写清）。
- `CatchAllMatcherTest.java`（+95）：+3 例，夹具用 agent `testImplementation` 既有的真 `ojdbc11:23.3.0.23.09`（新增 `oracleClass()` 助手，`initialize=false`）。
  1. `同族祖先类_不报未登记但仍织入`——loaded = {真未知夹具, `OracleConnectionWrapper`, `OracleConnection`, `GeneratedPhysicalConnection`, `PhysicalConnection`(白名单)}：真未知仍报、三类不报、**四类各织一次**（曝光面与织入面解耦的直接证据）、接受表登记不变。
  2. `白名单子类_不报未登记`——`T4CConnection` 继承白名单 `PhysicalConnection`：不报，仍织入。
  3. `同族判定_祖先与子孙命中且不放行无关类`——判定本体三态。

### 11.2 读数

- 定向首跑（6 类）：`CatchAllMatcherTest` 17（14→17，+3 落地确认）/ `CaptureBudgetTest` 10 / `CatchAllRedLineTest` 5 / `CatchAllStructureTest` 4 / `CatchAllTransformTest` 6 / `HeartbeatDiagnosticsTest` 6 = **6 类 48 例 0 failures 0 errors 0 skipped**（`--tests` 定向 + XML 判据数核对，S3 口径）。
- 恢复态复跑（模块全量）：**50 类 286 例 0 failures 0 errors 1 skipped**（该 skip 为既有预置条件用例）。
- 反向验证（S2，成对，各贴两次）：
  - 摘「祖先方向」判定（`whitelistAncestors.contains` 短路为 `false &&`）→ `CatchAllMatcherTest` **恰 2 红**：`同族祖先类_不报未登记但仍织入` + `同族判定_祖先与子孙命中且不放行无关类`；子孙方向用例保持绿（隔离性成立）。
  - 摘「子孙方向」判定（父类链循环条件加 `&& false`）→ **恰 2 红**：`白名单子类_不报未登记` + 判定本体；祖先方向用例保持绿。
  - 探针已全量移除（`grep "false &&"` 返回空），随后模块全量复跑全绿。

### 11.3 前提订正（实施期实测）

- §八 **V2 原取证路径作废**：现场 agent 以 `logLevel=warn` 注入（IDE 日志 2026-11-09 15:41:18），`CaptureBudget.diag()` 只在 `verboseLogging` 下输出，`scan=` 行**在任何日志里都不存在**；同段日志确认 pid=48760 走 **premain 通道**（`premain 会话建立：pid=48760（启动即捕获，attach 链已跳过）`），不是 attach 链。故「`T4CConnection` 为何未出现在 8 条里」本轮无法从日志坐实——不阻塞：子孙方向成本极低且语义正确，保留实现；日后取证把 `logLevel` 调到 debug 重跑一次即可。
- §八 **V3 已由用例覆盖**：夹具一律 `Class.forName(..., false, ...)`，判定走 `getSuperclass()`，本券未触发任何驱动类初始化。
- 日志侧旁证：插件自己在这条线上已留痕 `增强通道首条事件已到达并进入消费链（pid=48760）——若面板仍零产出，查消费链（去重窗口/屏蔽/过滤）`（2026-11-09 15:42:06），即 §十「94 事件 0 条上屏」的归因入口已有现成提示。

### 11.4 not run

IDE 侧全量 testShards 与 convention/门禁（`verifyGate`/`verifyPlugin` 等）本机未跑——按券纪律交收口轮；本券只动 agent 子项目，无平台 API/EP/依赖版本变更，未触大改动判据。真机 GUI 观感（诊断行消失）待用户验收。

### 11.5 CI 读数（2026-11-09 推送轮）

双仓推送完成（Gitee 主仓 + GitHub 镜像，走 `push-all.ps1` 令牌通路，无 GCM 弹窗），GitHub Actions `推送测试 · master` run **42587364463**（head `c4fde445`）**completed / success**，8 个 job 全绿：

| job | 结论 |
|---|---|
| `fast (1)` / `fast (2)` | success |
| `test (1)` ~ `test (5)` | success（分片全量在 CI Linux runner 上跑过） |
| `nightly-nudge` | success |

即 §11.4 的「本机未跑分片全量」已由 CI 补齐；本机读数仍只有 agent 模块 286 例那一档。门禁类任务（`build-plugin.yml` 的 gate）本轮未触发（该工作流是 `workflow_dispatch`）。

