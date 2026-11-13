---
name: project-junit3-migration-20261105
description: T-Junit4Migration（20261105 立项，子代理后台实施 P0 让 13 个从未执行的用例真跑 + P1 让
  Assume/@Before 生效）——398 个类继承 JUnit3 基类使注解全失效，扫描口径与四档划分
metadata:
  node_type: memory
  type: project
  originSessionId: sess_1caa1cd6-ecff-4769-98f4-48bfeeca1eb0
---

2026-11-05 18:06 立项（计划书 `docs/开发计划/20261105-1806-T-Junit4Migration-平台测试JUnit3语义清理与迁移-开发计划.md`，已登记 `docs/索引/开发计划.md`，提交 `d6c826b3`）。**触发**：修 DB 集成用例时发现 JUnit3 的 `BasePlatformTestCase` 不认 `Assume`，追查后扫出全仓范围问题。

## 实测读数（全仓扫描，20261105）

| 项 | 读数 |
|---|---|
| 继承 JUnit3 基类的测试类 | **398 个**（`@Test`/`@Before`/`@After`/`@Rule` 全部无效） |
| 反引号命名但**不以 `test` 开头**的用例 | **13 个**（散在 5 个类）⇒ **从未执行过**，测试结果 XML 里根本不出现 |
| 用 `org.junit.Assume` 的类 | 5 个（不生效） |
| 用 `@Before/@After/@Rule/@RunWith` 的类 | 1 个（不执行） |

那 13 个里最要紧的是 `BannedApiScanServiceStateTest` 的 4 个（含两条 `自证` 用例）——**禁止 API 扫描守卫的灵敏度声明一直没被验证**；其余是 `SqlToJavaPanelTest` 5 个 `canAutoInferEntitySelection_*`、`ResultMapColumnsGapTest` 等。

## 分档

- **P0**（本次做）：13 个用例真跑。优先「改名成以 `test` 开头」（零结构风险）；判据＝XML 里该类的 `tests=` 从 0/缺失变成实际条数，红的逐条定性（测试陈旧/环境/真缺陷）。
- **P1**（本次做）：5 个 Assume 类改本仓既有的 `[DB-IT] SKIP` + return 口径；1 个 `@Before` 类内联前置或迁 JUnit4 基类。
- **P2**（零风险可选）：清 JUnit3 类上的 `@Test` 死注解，判据＝同一分片 `tests=` 计数不变。
- **P3**（不排期）：398 个类全量迁 JUnit4。平台确有 `LightPlatformCodeInsightFixture4TestCase`（`testFramework.jar` 实证），但 `BasePlatformTestCase` 的 fixture 生命周期要逐个重写，是另一量级。

## 已证伪的一条前提（写进计划书「待验证前提」）

「反引号命名一律不执行」**错**。JUnit3 只看方法名是否以 `test` 开头：`fun \`test 光标在标识符中间\`()` 会被收集（`SelectedTableCaretTextTest` 30 个照常跑、XML `tests=30`）。真正漏网的只有 13 个不以 `test` 开头的。⇒ **别拿「反引号=不执行」当先验，按方法名前缀筛。**

机制与跳过口径见 [[pitfall-junit3-baseplatform-annotations-inert]]。相关：[[project-github-release-pipeline]]（夜间全量为什么能抓到守卫违规）、[[feedback-merge-subagent-branch-must-run-guards]]。
