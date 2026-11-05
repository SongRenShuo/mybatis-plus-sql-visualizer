# T-Junit4Migration：平台测试的 JUnit3 语义清理与 JUnit4 迁移

- 立项：2026-11-05 18:06
- 目标分支：`master`（tip `e1053a07` 附近）
- 触发：CI 夜间全量接上后，修 DB 集成用例时发现「JUnit3 的 `BasePlatformTestCase` 不认 `Assume`」，追查后扫出全仓范围的问题
- 性质：测试基建重构，不改产品行为

## 一、实测现状（2026-11-05 全仓扫描）

| 项 | 读数 | 影响 |
|---|---|---|
| 继承 JUnit3 基类的测试类（`BasePlatformTestCase` 等） | **398 个** | `@Test` / `@Before` / `@After` / `@Rule` 全部**无效**（JUnit3 按方法名 `test*` 收集） |
| 反引号命名但**不以 `test` 开头**的用例 | **13 个**（散在 5 个类） | **从未执行过**（JUnit3 不收集），测试结果 XML 里根本不出现 |
| 使用 `org.junit.Assume` 的类 | 5 个 | `assume` 不生效：本想「环境不具备就跳过」，实际被当失败或直接跑下去 |
| 使用 `@Before/@After/@Rule/@RunWith` 的类 | 1 个 | 前置/收尾不执行 |

**最值钱的 13 个**（真·从不执行）：

- `BannedApiScanServiceStateTest`（4 个，含两条 `自证` 用例）——**禁止 API 扫描守卫的灵敏度自证一直没跑过**，「该守卫能抓住违规」的声明属空口；
- `SqlToJavaPanelTest`（5 个 `canAutoInferEntitySelection_*`）；
- `ResultMapColumnsGapTest`、其余 3 个（见扫描命令复现）。

复现命令（不改文件）：

```bash
python -c "
import os,re
for root,_,files in os.walk('src/test/kotlin'):
    for fn in files:
        if not fn.endswith('.kt'): continue
        p=os.path.join(root,fn); s=open(p,encoding='utf-8').read()
        if not re.search(r'class\s+\w+\s*:\s*BasePlatformTestCase', s): continue
        for m in re.finditer(r'^    fun \`([^\`]+)\`', s, re.M):
            if not m.group(1).startswith('test'): print(p, m.group(1))
"
```

## 二、目标与非目标

**目标**

1. 消除「写了却从不执行」的用例（13 个）——先让它们真的跑，再看红不红；
2. 让「环境不具备就跳过」这类语义真正生效（5 个 `Assume` 类 + 1 个 `@Before` 类）；
3. 清掉 JUnit3 类上无效的 `@Test` 死注解，避免后来人误判框架。

**非目标**

- 不追求「398 个类全部迁到 JUnit4」。平台确实有 JUnit4 基类（`testFramework.jar` 内 `LightPlatformCodeInsightFixture4TestCase`），但 `BasePlatformTestCase` 的 fixture 生命周期、`UsefulTestCase` 的清理钩子、`myFixture` 装配方式都要逐个改；398 个类全迁是另一量级的工程，收益集中在上面三条。
- 不改产品代码。

## 三、分档实施

### P0：让 13 个「从不执行」的用例真正跑起来（最高优先）

做法二选一，按用例性质判断：

- **改名**：把反引号名改成以 `test` 开头（如 ``fun `test 初始化入口改项目服务级状态须复位`()``）。零结构风险，JUnit3 立刻收集。
- **迁 JUnit4**：仅当该类还需要 `@Before/@After/Assume` 语义时才做。

判据：目标类在测试结果 XML 里的 `tests=` 从 0（或缺失）变成实际条数；首次跑起来后露出的红，逐条定性（测试陈旧 / 环境 / 真缺陷）。

### P1：让跳过语义生效（5 个 `Assume` 类 + 1 个 `@Before` 类）

- 若该类是 JUnit3：`Assume` 一律改成本仓既有的「打印 `[DB-IT] SKIP` + `return`」口径（仓库已有 13 处先例）；
- 若该类确实需要前置/收尾（`@Before`）：迁 JUnit4 基类，或把前置内联到每个用例开头。

判据：跳过时测试结果里是 `skipped` 而非 `failed`，且跳过原因出现在日志里。

### P2：清理 JUnit3 类上的 `@Test` 死注解

- 只删注解与 `import org.junit.Test`，行为零变化；
- 判据：删除前后同一分片的 `tests=` 计数一致（证明没有任何用例因此被停跑）。

### P3（可选，不排期）：全量迁移到 JUnit4 基类

只在 P0/P1 完成、且确认有持续收益（例如需要 `@Rule` 做统一超时）时再评估。

## 四、风险

| 风险 | 说明 | 对冲 |
|---|---|---|
| P0 改名后一次性暴露大量红 | 13 个用例从没跑过，可能已经腐烂 | 逐条定性，允许「测试陈旧」修正断言，但**不得**改成恒真 |
| P2 误删 | 若某类其实是 JUnit4（不继承 JUnit3 基类），删 `@Test` 会让用例停跑 | 只对「继承 JUnit3 基类」的类动手；删完比对 `tests=` 计数 |
| 分片权重漂移 | 新跑起来的用例会改变分片时长表 | P0 完成后跑一次全量，按新读数刷新权重（`test-shard-weights.tsv` 由任务自动积累） |
| 与并行会话撞车 | 同一时段可能有人在改测试 | 派活时限定写集；每个可提交单元立即提交 |

## 五、验证

1. **P0 定向**：目标 5 个类逐个 `--tests` 跑，贴 `tests=/skipped=/failed=`；再从分片 XML 复读一次（防「单跑绿、分片不收集」）。
2. **P1 定向**：构造「环境不具备」的场景（例如临时把 dev 沙盒路径改名），确认输出是 skipped 且带原因。
3. **P2 计数**：删注解前后各跑一次同一分片，`tests=` 必须一致。
4. **收口**：一次 `testShards` 全量（读数对比基线）；夜间全量一轮。

## 六、待验证前提（实施者先证伪）

- [x] 「反引号命名一律不执行」——**已证伪**：以 `test` 开头的反引号方法 JUnit3 会收集（`SelectedTableCaretTextTest` 30 个照常执行）。只有 13 个不以 `test` 开头的才是漏网。
- [ ] 「`@Test` 在这些类里完全无效」——需在一个类上做对照实验（加/删 `@Test`，看 `tests=` 是否变化）。
- [ ] 「平台 JUnit4 基类可用」——需确认 `LightPlatformCodeInsightFixture4TestCase` 在 243/262 双目标下都能用（我们编译基准是 243）。
- [ ] 「P1 的 5 个 Assume 类都在 JUnit3 基类上」——扫描口径需逐条核对继承链（可能有中转基类）。

## 七、与既有纪律的关系

- 本任务不放松任何判据：13 个用例跑起来后若红，属「测试腐烂」或「真缺陷」，按证据处理；
- 取号前已 `grep` 查重（`T-Junit4Migration` 无冲突）。

## 八、实施记录

> 实施：2026-11-05。范围按用户后续定调扩为 P0 / P1 / P2 / P2.5；**P3 不做**（理由见 8.6）。
> 提交（只提交未推送）：P0 `9dc0c263`、P1 `0aa0542d`、P2 `3a07ee7a`、P2.5 `99f4c0bb`。

### 8.1 动手前的前提证伪：三条被推翻

**① 「13 个从不执行」——只有 5 个是真的。** §一 的扫描正则没限定「顶格类声明」，两类假阳性：

- `BannedApiScanServiceStateTest.kt` 里 17 处 `class XxxSampleTest : BasePlatformTestCase()` 全在**字符串字面量**里（该扫描守卫自己的夹具）；文件本身是纯 JUnit4 类（`class ... : BannedApiScanSupport()`），4 条用例都带 `@Test`；
- `ResultMapColumnsGapTest.kt` 里第 2、3 个类才是 JUnit3，正则却把 4 条注解归属记到了同文件的纯 JUnit4 类 `ResultMapColumnsGapTest` 头上。

`./gradlew test --tests <三个类>` 的 XML 读数（`build/test-results/test`）：

| 类 | tests= | 5/4 条目标用例是否在 XML 里 | 结论 |
|---|---|---|---|
| BannedApiScanServiceStateTest | 4 | 4 条（含两条「自证」）全部在 | 一直在跑 |
| ResultMapColumnsGapTest（纯 JUnit4 类） | 4 | 4 条全在 | 一直在跑 |
| SqlToJavaPanelTest | 23 | 5 条 `canAutoInferEntitySelection_*` 缺席 | 真·漏网 |

因此 P0 的实际范围是 **5 条（一个类）**，不是 13 条（五个类）。两条「守卫灵敏度自证」用例一直绿，守卫的灵敏度声明本来就成立；本轮只是把它从「被误记为没跑」纠正为「有 XML 证据在跑」。

**② 「`@Test` 在 JUnit3 类里无效」——成立。** 同一张表就是对照实验：JUnit3 的 `SqlToJavaPanelTest` 里 5 条带 `@Test` 的方法不被收集，同文件 23 条 `test*` 方法照常执行；纯 JUnit4 类里的 `@Test` 正常执行。机制是 JUnit38ClassRunner 只按方法名收集。

**③ 「1 个用 `@Before/@After/@Rule/@RunWith` 的类」——不存在。** 扫描命中的是 `BannedApiScanServiceStateTest:493/730`，两处 `@After`/`@Before` 都在三引号字符串夹具里（该守卫自测「收尾/初始化入口」规则的示例源码）。全仓真注解使用者只有纯 JUnit4 类，那里的注解有效，不需处理。

前提③（平台 JUnit4 基类可用）未验证也不需要——全程按「向 JUnit3 收口」实施，零 JUnit4 迁移。

### 8.2 P0：让漏网用例真正执行（`9dc0c263`）

做法取计划里的「改名」（零结构风险），与仓内 144 处 `fun \`test ...\`` 先例同形态：
`fun \`canAutoInferEntitySelection_xxx\`` → `fun \`test canAutoInferEntitySelection_xxx\``。

读数（`build/test-results/test/TEST-...SqlToJavaPanelTest.xml`）：

- 改前：`tests=23 skipped=0 failures=0`（5 条目标缺席）
- 改名后首跑：`tests=28 failures=5` —— 5 条全红
- 修完：`tests=28 skipped=0 failures=0`

5 条红的定性是**测试自身缺陷**（既非产品缺陷、也非断言陈旧）：用例体里 `SqlToJavaPanel(project)` 每次新建面板且从不 dispose，`tearDown` 的平台检查报 `Editor EditorImpl[null] hasn't been released`。改为复用 `setUp` 创建、`tearDown` 释放的 `panel` 字段即全绿；断言与输入数据一行未改（不存在「改成恒真」）。

其余 8 条（BannedApiScanServiceStateTest 4 条、ResultMapColumnsGapTest 4 条）确认一直在跑，未改动。

### 8.3 P1：跳过语义（`0aa0542d`）

5 个类全部按仓内既有口径改：`assumeTrue(cond, msg)` → `if (!cond) { println("[DB-IT] SKIP: <原因>"); return }`；前置/收尾仍走 `override setUp/tearDown`，**没有迁 JUnit4 基类**。

| 类 | 处数 |
|---|---|
| DataSourceSelectorIntegrationTest | 14 |
| DbConsoleIntegrationTest | 3（含「未配置 mpTestDbPassword」那条多行 assume） |
| HeavyNativeDatabaseDiagramJoinChainTest | 2 |
| PreviewSideQueryIntegrationTest | 1 |
| ExecDocGateIntegrationTest | 0（此前已改口径），本轮只清残留的 `import org.junit.Assume` 死 import |

判据：构造「环境不具备」——`-PmpDemoProjectDir` 指向空目录 + `-PskipDemoFixtureCheck=true`，逐任务跑：

| 任务 | tests= | skipped= | failures= | `[DB-IT] SKIP` 打印 |
|---|---|---|---|---|
| testDbSelector | 5 | 0 | 0 | 4 |
| testDbConsole | 9 | 0 | 0 | 1 |
| testDbDiagram | 1 | 0 | 0 | 1 |
| testDbExecDocGate | 1 | 0 | 0 | 0（本类不依赖 demo 配置，正常执行） |
| testDbPreviewSideQuery | 1 | 0 | 0 | 1 |

反向实验（PreviewSideQueryIntegrationTest，把判定临时改回 `assumeTrue` 再跑同一场景）：

- 改前（`assumeTrue`）：`tests=1 skipped=0 failures=1`，失败信息 `org.junit.AssumptionViolatedException: REVERSE-PROBE ...`
- 改后（打印+return）：`tests=1 skipped=0 failures=0`，system-out 含 `[DB-IT] SKIP: MySQL 未就绪，跳过真库旁路查询：demo dataSources.xml 未找到 mpj_demo`

这条把「JUnit3 下 assume 记 failed」从注释里的经验变成可复现读数；探针改动已还原。

### 8.4 P2：清死注解（`3a07ee7a`）

范围：只动**类本身（或经中转基类）继承 JUnit3 基类**的顶层测试类（46 个文件、47 个类），其中 `ResultMapColumnsGapTest.kt` 是混合文件——同文件 1 个纯 JUnit4 类（4 条 `@Test` 保留）+ 2 个 JUnit3 类（4 条删除）。

数量：删 **313 条 `@Test`** + **52 条**已无用的 `import org.junit.Test`（唯一保留 import 的是上述混合文件）。diff 形态 0 insertions / 365 deletions，无行尾换行被改动。

判据（同一批 5 个类，覆盖 config/execute/log/mcp/ui.ref 五个包，改前改后各跑一次）：

| 类 | 改前 tests= | 改后 tests= |
|---|---|---|
| ProjectPluginSettingsTest | 7 | 7 |
| ConsoleErrorExtractTest | 21 | 21 |
| SqlToJavaPanelTest | 28 | 28 |
| CrudSqlToolTest | 4 | 4 |
| MybatisXmlReferenceTest | 20 | 20 |

合计 80 → 80，无用例因删注解停跑。ProjectPluginSettingsTest 改前改后同为 `failures=3`，失败是既有的 `NoSuchMethodError: PluginSettings$State.copy$default`（陈旧编译产物，与本轮无关，两轮读数相同）。

### 8.5 P2.5：JUnit3 语义守卫（`99f4c0bb`）

新增 `convention/Junit3SemanticsGuardTest.kt`，扫 `src/test/kotlin` 里继承 JUnit3 基类的类（含中转基类），断言三条：

1. 类上不得出现 `@Test/@Before/@After/@Rule/@RunWith/@Ignore`；
2. 不得 `import org.junit.Assume` 或调用 `Assume.assume*`（多类文件只校验函数体调用，避免 import 归属误报）；
3. 反引号方法名必须以 `test` 开头。

实现上两处关键：扫描前先做**字符串/注释掩码**（否则示例源码会被当真类——正是 §一 那 13 个假阳性的成因）；继承链按简单类名做**固定点传播**，中转基类可识别。

读数：`Junit3SemanticsGuardTest tests=5 failures=0`（1 条全仓用例 + 4 条自证/边界用例：注解规则、Assume 规则、命名规则各「违规必红 + 合规必绿」，另加中转基类传播与字符串字面量不参与扫描两个边界）。
已按判据 4 登记 `GUARD_CLASSES`（4 个 `*Violations` 判定函数逐条登记），元守卫 `GuardSelfTestCoverageMetaGuardTest tests=8 failures=0`。

反向验证（真实文件探针，不是只看合成样本）：在 `ProjectPluginSettingsTest` 上临时加 `@Test` + 把一个方法改成不以 test 开头的反引号名 → 守卫变红并逐条点名：

```
ProjectPluginSettingsTest.kt:24 类 ProjectPluginSettingsTest 上的 @Test 在 JUnit3 下不生效（JUnit38ClassRunner 只按 test* 方法名收集用例）：请删除该注解
ProjectPluginSettingsTest.kt:25 方法 `REVERSE_PROBE_never_runs` 不以 test 开头——JUnit3 按方法名收集用例，这条用例永远不会执行：改名为 `test REVERSE_PROBE_never_runs`
```

还原探针后 `tests=5 failures=0`。

### 8.6 P3 不做（本轮结论）

全量迁移到 JUnit4 基类不排期，理由：平台自身演进方向是 JUnit5（243 的 `testFramework.jar` 里 90+ 个 `com.intellij.testFramework.junit5.*` 类），JUnit4 基类只剩一个薄适配器；迁 JUnit4 是往旁支走。将来若升级，应直接评估 JUnit5（`@TestApplication` / `@RunInEdt`），那是独立工程。本轮 P2.5 的守卫已把「新代码再写回 JUnit3 无效语义」堵住，是这条路的长期解。

### 8.7 遗留与待决定

- **分片复读未做**：本轮按任务红线未跑 `testShards`（也不跑 `testDatabase` 整体）。P0 后该类在全量里的条数会从 23 变 28，需要在收口轮从分片 XML 复读一次确认收集面一致；若分片权重表（`test-shard-weights.tsv`）按类时长自动积累，这 5 条首次执行会带来一次微小的权重漂移，建议随收口轮一起刷新。
- 五个 DB 集成类只验证了「环境不具备 → SKIP」路径；「环境具备 → 真库断言」路径本轮未跑（需真实 MySQL，属 `testDatabase` 通道）。

