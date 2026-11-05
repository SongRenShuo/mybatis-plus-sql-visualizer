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
