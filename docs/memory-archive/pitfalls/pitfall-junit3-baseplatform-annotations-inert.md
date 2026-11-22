---
name: pitfall-junit3-baseplatform-annotations-inert
description: JUnit3 基类的平台测试里 @Test/@Before/@Rule 全无效、Assume 会被记成 failed 而不是
  skipped；仓内跳过口径是「打印 SKIP + return」
metadata:
  node_type: memory
  type: project
  originSessionId: sess_1caa1cd6-ecff-4769-98f4-48bfeeca1eb0
---

本仓测试类多继承 IntelliJ 平台的 **JUnit3** 基类（`BasePlatformTestCase` / `LightJavaCodeInsightFixtureTestCase` 等，2026-11-05 扫描 398 个）。由此有三条硬事实：

1. **用例收集只看方法名**：必须是 `test` 开头（Kotlin 可用反引号，名字仍要以 `test` 起头）。`@Test` 注解是**无效装饰**，`@Before/@After/@Rule/@RunWith` 一律不执行。反过来别误判——`fun \`test 某某\`()` **会**被收集，只有不以 `test` 开头的 13 个才真漏网（详见 [[project-junit3-migration-20261105]]）。
2. **`org.junit.Assume` 不生效**：JUnit3 的 runner 把 `AssumptionViolatedException` 当失败，日志与计数上是 `8 tests completed, 2 failed` 而不是 skipped（2026-11-05 夜间 run#3 实证）。所以「环境不具备就 assume 跳过」在这类类里等于「自己制造红」。
3. **仓内跳过口径＝打印 + return**：

```kotlin
if (!precondition.isFile) {
    println("[DB-IT] SKIP ${this.name}：缺少 <具体缺什么> $precondition")
    return
}
```

仓库已有 13 处这种先例（`DataSourceSelectorIntegrationTest` 的 `[DB-IT] SKIP: introspect 失败…` 等）。要点：**打印出缺什么**（否则等于静默通过），命名统一用 `[DB-IT] SKIP` 便于 grep。

## 判据与教训

- 判断一个测试类是不是 JUnit3：看 `class X : BasePlatformTestCase` 的继承链，而不是看有没有 `@Test`。
- 在 JUnit3 类里想「按环境跳过」，别写 `assumeTrue`；写 `println + return`。
- JUnit4/5 基类确实存在（`LightPlatformCodeInsightFixture4TestCase`），但迁移要重写 fixture 生命周期，属单独立项（T-Junit4Migration 的 P3，不排期）。
- 反证方法：改完看测试结果 XML 的 `tests=/skipped=/failures=`，别只看控制台的 FAILED 字样（assumption 与真失败在 Gradle 控制台都显示 FAILED）。
