---
name: pitfall-cli-ampersand-in-jdbc-url-swallowed-by-bat
description: 含 & 的 -P 参数穿过 gradlew.bat 会被 cmd 当命令分隔符吃掉——JDBC URL 这种值必须写进
  gradle.properties 由 providers.gradleProperty 读回
metadata:
  node_type: memory
  type: project
  originSessionId: sess_1caa1cd6-ecff-4769-98f4-48bfeeca1eb0
---

**症状**（2026-11-05 夜间 e2e 腿实测）：e2e 探针 14 项 PASS、12 项全 FAIL，失败信息一律是「业务请求返回 HTTP 500」，而 agent 心路（HELLO / HEARTBEAT / CONFIG_ACK）全正常——典型「应用起来了但连不上库」。

**根因**：JDBC URL 里带 `&`（`?useUnicode=true&characterEncoding=utf8&...`），作为 `-Pe2eDbUrl=jdbc:mysql://...&...` 传给 `gradlew.bat` 时，**cmd 把 `&` 当命令分隔符**，参数被截断/串味，Gradle 拿到的属性是残值 → Spring 的 `SPRING_DATASOURCE_URL` 没被正确设置 → demo 回落到 application.yml 里的 `localhost:3306` → 无库 → 所有 DB 端点 500。

**修法**：**文件注入**，别走命令行。

```powershell
Add-Content -LiteralPath 'gradle.properties' -Value @(
  "", "# CI 注入（仅本 job 的 runner 副本）",
  "mpvDbUrl=$env:MPV_DB_URL", "mpvDbUser=$env:MPV_DB_USER", "mpvDbPassword=$env:MPV_DB_PASSWORD"
) -Encoding utf8
./gradlew.bat :testDatabase -PgifRecord=false --console=plain "-PmpDemoProjectDir=$demo"
```

buildSrc 侧用 `providers.gradleProperty("mpvDbUrl").orNull?.let { systemProperty("mpvDbUrl", it) }` 读回；e2e 侧同理映射成 `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`（demo 是 Spring Boot，松散绑定会覆盖 application.yml）。

## 为什么值得记

- 同一个坑对**任何**含 `&`（或 cmd 元字符 `|` `<` `>` `^`）的 `-P` 值成立，不只 JDBC URL；
- 失败信息离根因很远（表现为 HTTP 500 / 连不上，而不是「参数解析错误」）——先怀疑参数传递，再怀疑网络或 schema；
- 判别捷径：**agent 心路正常但 DB 端点全 500** ⇒ 库连接问题；再看传参方式是否经过 `.bat`。

配套：失败时把 `dev-support/e2e-fake-ide/evidence/` 作为 artifact 上传（首轮只有一句「500」，拿不到 `app.log`，只能靠猜）。
