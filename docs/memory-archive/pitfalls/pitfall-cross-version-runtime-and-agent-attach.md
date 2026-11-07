---
name: pitfall-cross-version-runtime-and-agent-attach
description: 两条真机验证硬约束——跨平台版本跑真机别改构建基准（高版本拆模块，compileJava 报找不到符号），用基线产物装进目标 IDE；增强捕获 agent 挂不到非 IDE 启动的外部 JVM，必须走 demo pom 加 system-scope 驱动 + IDE Run
metadata:
  node_type: memory
  type: project
  originSessionId: sess_da4753b3-ef7f-4a1f-b5d2-a46a94883c80
---

# 跨版本真机 + 增强 agent 挂载：两条走通过的路（2026-11-01~02）

## 一、跨平台版本真机：改构建基准是死路，装产物才是

验 MCP261 时先走「`-PplatformVersion=2026.1.5.1` 编 261 沙盒」→ `compileJava` **35 个错误**
（`ContentManagerEvent`、`SearchSession` 等在 261 已从平台核心拆成独立模块，需补一串 `bundledModule`）。
每换一个目标版本都要重补一遍，成本不可控。

**正解＝基线版本产物装进目标 IDE**：plugin.xml `since-build=243` 且**不设 `until-build`**（向上兼容是设计内），
243 构建的 zip 可以直接丢进 2026.1 的 `plugins/`。

1. `./gradlew buildPlugin -PgifRecord=false` → `build/distributions/<name>-<ver>.zip`
2. 目标 IDE 用**私有副本**启动：`-Didea.config.path` / `-Didea.system.path` / `-Didea.plugins.path`
   全指到独立目录；zip 解压进 plugins；`idea.key` 拷进 config；许可 agent 写进**该副本**的
   `bin/idea64.exe.vmoptions`（IPGP 2.x 只读平台 vmoptions）
3. 加载判据：`grep "SQL Visualizer (" idea.log` 或设置树出现插件页组
4. 功能判据（以 MCP 为例）：端点 200+sessionId / `initialize` **202 非 500** / `tools/list` 个数对 /
   `tools/call` `isError:false`，再加负向对照——目标缺陷的特征签名日志**零命中**

IDE 发行版直接取官网 zip：先查 `https://data.services.jetbrains.com/products/releases?code=IIU&type=release`
的 `downloads.windowsZip.link`，**别自己拼 `ideaIU-<ver>.zip`**（那个 404，正确形如 `idea-<ver>.win.zip`）。

## 二、增强捕获 agent：外部 JVM 挂不上，必须走 IDE Run

想让 agent 挂到三个自己起的 JDBC 探针 JVM 上验事件回流，**全部失败**：

- 捕获开关已开、进程在跑、面板零条目；**重启进程**也不挂——日志无 `onProcessAppeared` / `attachToPid`。
  原因：`onProcessAppeared` 只接 `RunConfigurationProcessListener`（IDE Run 链路）。
- 唯一覆盖外部 JVM 的是设置变更**上升沿**（`enhancedCaptureEnabled` false→true →
  `scanAndAttachRunningProcesses`）；真机关→开→Apply 后 `设置变更：增强捕获重新开启` 与
  `在跑进程补挂 N 个` **都没出现**。**是否设置页 apply 真跑了至今未定论**（该字段默认 true，不落盘，证明不了）。

**正解（三族跑通）**：

1. demo `pom.xml` 加**system-scope** 依赖指向厂商驱动 jar（取插件沙盒 `plugins-test/<plugin>/lib/` 那份，
   与插件在用的是同一批）
2. 改 `src/main/resources/application.yml` 的 datasource 四项
3. IDE 里 Shift+F10 起应用 → `updateJavaParameters` 注入 `-javaagent` → premain 回连 → 事件进面板
4. 换族点弹窗「Stop and Run」，每族 40s

**往 `target/classes` 解压驱动再跑不成立**：`DataSourceProperties.determineDriverClassName` 断言失败
（驱动类不在运行 classpath），必须走 pom。

### 顺带钉住的两条语义

- **失败语句同样回流**：三族都对 PG 族保留字 `user` 报 `column "id" does not exist`，面板照常出条目并带
  `Caused by: … PSQLException`——钩子挂在执行入口而非成功分支，所以「条目带错误链」不是缺陷。
- **角标取自 JDBC URL**（`JdbcUrlDialects.badgeName`）：同一份代码换 datasource 即换角标，
  三族互不串味——这也是「角标对不对」能当判据的原因。

相关：`pitfall-sandbox-jdbc-driver-needs-installed-state.md`（驱动两份文件）、
`pitfall-runners-zero-means-unbound-not-unconnected.md`（执行链绑定语义）。
