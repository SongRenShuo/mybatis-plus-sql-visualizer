# `:engine-jar` — 纯逻辑引擎导出（k2j Java 投影口径）

把主仓 P0 纯逻辑打成**不含任何 IntelliJ 类型**的 `mpsv-engine` jar，供非 IntelliJ 宿主
（OpenIDEA / Rust sidecar）经 JSON 门面消费。master（`f06828c2d`）是 Kotlin 实现，k2j 生产树是
100% Java，故门面与源集裁剪规则一并落 Java 形态。

## 产物形态

- `build/libs/mpsv-engine-<engineVersion>.jar` + 同名 `.sha256`；Manifest 带
  `Main-Class: mpsv.engine.StdioMain`、`Engine-Api: mpsv.engine.EngineApi`，
  `Implementation-Version = engineVersion`（与插件版本解耦）。
- **自包含**：引擎类 + `kotlin-stdlib` + `jsqlparser` + PG/SQL Server 两个 EXPLAIN 驱动，宿主只需
  `java -cp mpsv-engine-x.jar`（不组装 classpath）。字节码目标 **17**（master 是 11）：k2j 生产树含
  record / sealed / `instanceof` 模式匹配。
- **零平台渗透**：`verifyNoPlatformImports` 逐 class 常量池扫 `com.intellij`/`org.intellij` 即失败，并断言 `mpsv/engine/EngineApi.class` 真在 jar 里（防「扫了个空 jar」全绿）。

## 三个门面（master `object` → k2j `final class` + 私有构造 + 全 `static`）

- `EngineApi`：JSON 门面 `renderLogs`/`checkSql`/`perfAdvice`/`render`/`explain`/`explainExecute`/`schema`。
- `StdioMain`：`--stdio` 逐行 JSON 分发（含 `ping`/`explain.execute`），坏输入不退出。
- `SmokeMain`：产物可用性自检，三个门面各跑一例。`StdioMain.kt` 三个顶层声明拆为 `StdioMain`（public）+
  包级私有 `StdioMainKt`（顶层函数 `jsonStr`，项目 `XxxKt` 惯例）+ `MiniJson`（JSON 解析）。

## 源集与构建

`prepareEngineSources` 构建期从 `../../src/main/java` 机械裁剪到 `build/generated/engine-src`（剥注释判平台引用 → import 白名单 → 引用闭包收敛 → 4 处投影 → `check(kept >= 300)`），生产源码从不被改写。

```bash
./gradlew :engine-jar:jar                        # 产物 + 自动 sha256
./gradlew :engine-jar:verifyNoPlatformImports    # 零平台渗透守卫（check 已 dependsOn）
cd sub-projects/engine-jar && ./smoke.sh         # 用 JAVA_HOME 的 java（PATH 默认 JDK 8 加载不了 major 61）
```

## 与 master Kotlin 版的差异要点

- **`when` 表达式 → `switch` 表达式 + `yield`**（箭头 `switch` 语句穷尽枚举也过不了 javac 明确赋值分析）；带 `is`
  分支的 `when`（`SubmitPlan` 三分支）落 `instanceof` 判断链——Java 17 模式 `switch` 仍是 preview。
- **`?.`/elvis 全展开**（`a?.b ?: c` 的内层 null 合并不可漏）；具名实参 → 位置实参；`runCatching` → `try/catch`；
  `Char.isWhitespace()` → `Character.isWhitespace || Character.isSpaceChar`（JDK 版不含 NBSP）；
  `smoke.sh` 与 master **逐字节相同**（直取未改写）。
