---
name: pitfall-reflection-invoke-visibility
description: 反射调平台 package-private 实现类的方法会抛 IllegalAccessException（invoke
  按声明类可见性检查）——被 runCatching 吞成 null 后表现为「功能静默失效」；本仓实测于组 Runner 展开，20261019 已修复
  0f06bcc4
metadata:
  node_type: memory
  type: project
  originSessionId: session_7c4f9878c3e52efd1a834ad34e2354ac
---

# 反射调 package-private 实现类：必须 `isAccessible = true`

`Method.invoke` 的访问检查**按「方法声明所在类」的可见性**做，不是只看方法本身 public。平台常把实现类写成 package-private、方法签成 public——从插件包拿到该 `Method` 直接 `invoke`，在 Java 11+ 抛：

```
java.lang.IllegalAccessException: class X cannot access a member of class
com.intellij.database.console.DatabaseRunners$3 with modifiers "public"
```

JDK9+ 走 `MethodHandles` 路径时 `--add-opens` 对「类本身是 package-private」无效，仍需 `setAccessible(true)`。

## 本仓实测形态（2026-10-19，已修复 0f06bcc4）

`ExecDialectRouting.subRunnersOf` 用 `runner.javaClass.getMethod("getSubRunners").invoke(runner)` 没设 `isAccessible`，而同包 `getRunnerName`/`shouldBeChosenIfAlone`/`runRunner` 三处都设了——唯独这一处漏。后果链：

1. 平台返回 **"All" 组 Runner**（`DatabaseRunners$3`，package-private）→ `invoke` 抛 IllegalAccessException；
2. `runCatching{}.getOrNull()` **静默吞掉**，组项被当普通 Runner 保留；
3. 组项的 `run()` 字节码绑定 `EmptyRunnable`，且 `shouldBeChosenIfAlone = true`；
4. `runners=1` → "single runner, run directly" → 执行空操作 → **永远等不到 session 回调**；
5. 表现＝准备门 60 秒后报 `execute.prepare.timeout`「数据库控制台准备超时，未提交 SQL」。

日志判据：`runRunner invoked OK` 之后 60 秒内**没有任何** `[M36][O-145] session created`。

**触发条件值得记住**：SQL 用双引号标识符（`FROM "user"`）→ 预览方言被判 PostgreSQL → 工程内无 PG 数据源 → 平台只返回 All 组。MySQL 场景（`runners=5` 走弹窗）不复现，所以问题藏得深。

## 修复（0f06bcc4，含三层）

- `subRunnersOf` 补 `method.isAccessible = true`，反射失败**记 WARN**（`getMethod`  NotFound 属正常形态不记）；
- 组展开改最多 3 轮递归（平台两级组 `DatabaseRunners$4 → $4$1`），超深度仍成组（含自引用）时剔除并 WARN——保留空操作组等于把 60s 悬挂留给用户；
- `runRunner` 执行前用 `ExecDialectRouting.isNoOpRunner`（沿继承链找 `runnable` 字段，判 `is EmptyRunnable`）拦截，立即回 `execute.datasource.noRunnable` 可行动文案。

## 同族第二种：换入口时按错参数个数反射 → 异常被吞成空集合 → 下游归因成另一个原因（20261024 T-ExplainSwitchNative W8-hotfix 实证）

`DatabaseRunners` 两个「看起来同族」的入口签名**不同**：

```
getAttachDataSourceRunners(PsiFile, String, Consumer)                          // 三参
getSwitchDataSourceRunners(PsiFile, String, DatabaseSession, Consumer)          // 四参（多当前会话）
```

改「切换数据源」入口时我只确认了方法名存在就按三参反射调用 → `NoSuchMethodException` →
`runCatching` 吞成 `emptyList()` → `runners=0` → 走进「有在册数据源但枚举为空 = 方言不匹配」的
既有归因分支 → 用户看到红条「没有与该 SQL 方言（MySQL）匹配的已配置数据源」，而 7 个源都在
Database 视图里。**真因（反射签名错）与呈现原因（方言不匹配）完全无关**，误导排查方向。

修法两条（结构性，别只补参数）：
1. 按**真实四参签名**调用（`Class.forName(SESSION_CLASS)` 作第 3 参类型）；
2. **失败/空必须回退旧入口**：`switched.ifEmpty { attach() }`——`runCatching` + `getOrDefault(emptyList())`
   这种「静默吞成空」的组合，任何一处都可能在别处变成一条完全错误的用户可见原因。

**判据**：①换平台反射入口前，javap 核**完整签名**（参数个数与类型），不能只看方法名；
②凡「新入口失败就回退旧入口」的地方，用 `ifEmpty { legacy() }` 之类的显式回退，**不允许**让
`runCatching` 的默认值一路流到 UI；③用户报的原因若与代码里能找到的证据链不符（「方言不匹配」
但方言明明一致），先怀疑**中间层把异常吞成了空值**，而不是继续顺着 UI 文案查业务逻辑。

## 同族第三种：参数「个数对、类型错」→ `argument type mismatch`（20261024 W11 实测）

`createDataSourceConsolesRunner(DbDataSource, Consumer)` 按「`(Object, Consumer)`」反射传入
`LocalDataSource` 实例（而非 `DbDataSource`）→ `IllegalArgumentException: argument type mismatch`
（与 `NoSuchMethodException` 不同，这是**参数个数对、运行时类型不符**）。用户侧同样只看到一条红条。

**判据**：反射实参必须是该参数**声明类型的实例**——平台把 `LocalDataSource`（配置层）与 `DbDataSource`
（PSI 层）分成两个类型，需要 `DbPsiFacade.findDataSource(uniqueId)` 之类转换，不能凭「都是数据源对象」直接传。

## 方法与判据

- 反射失败被 `runCatching` 吞掉后只剩「功能没反应」——**先查 invoke 的返回值到没到**，别先怀疑业务逻辑。
- 快速证伪：独立 Java 程序（`javac`/`java -cp` IDE 的 `lib/*` 与 `DatabaseTools/lib/*`），`getDeclaredConstructor`+`setAccessible(true)` 造实例，分别测「不设 isAccessible」「设了」两次 `invoke`——本机实测前者抛、后者成功（用 `ideaIU-2024.3.7.1` 的 `database-plugin.jar`）。`EmptyRunnable` 在 `lib/util-8.jar`。
- 单测夹具：把 package-private 桩放在 `src/test/java/<同一包>/HiddenRunnerFixture.java`（类与构造器包私有、方法 public），测试包只经 public 入口拿到 `Object`——这样「漏设 isAccessible → 用例红」才是真判别。
- 同族纪律：反射调用一律显式 `isAccessible = true`；`runCatching` 包反射至少记 warn，别让失败与「本来就是 null」不可区分。

读数：`ExecDialectRoutingTest` 27/0/0/0，反向摘 isAccessible 恰 3 红、摘空操作拦截恰 1 红、摘超深度剔除恰 1 红；相邻 `ExecutionDialectGateTest` 17 + `LogToSqlPanelExecuteTest` 18 全绿。

## 后续观察（未做）

修复后方言闸正常拦截：用户报「当前 SQL 按 PostgreSQL 渲染，但所选数据源的实际方言是 MySQL」。这是 `estimateDmlImpact` 里 `canLosslesslyRerender = false` 的 fail-closed 设计，不是缺陷。若要「选 MySQL 数据源后自动把 COUNT 试算重渲染」属新增强（需补目标固定/二次重渲染链），单独立计划。

相关：[[project-ai-context-v2-unreadable]]（同轮另一任务）、[[project-dynamic-unload-barriers]]（反射入口登记纪律）、[[runide-demo-verify-workflow]]、[[pitfall-sandbox-jar-staleness-and-window-enum]]（先核在役 jar 再判修复）。
