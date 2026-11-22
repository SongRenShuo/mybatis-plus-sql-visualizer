---
name: pitfall-gradle-verification-ide-auxiliary-artifacts
description: 开 Gradle 依赖校验（verification-metadata）后 IDE 同步必红的三类清单外构件——sources/javadoc、平台 distro POM、Gradle 发行版附属物；CLI 全绿会掩盖，须同批写 trusted-artifacts
metadata:
  node_type: memory
  type: project
  originSessionId: sess_5db122bd-2663-485c-b919-011cee6ca8e4
---

# 依赖校验清单只覆盖 CLI 解析面 → IDE 侧三类构件整片撞红（2026-11-09 实测，连撞三轮）

**规则**：`gradle/verification-metadata.xml` 由 `--write-verification-metadata` 生成，**只记录那次构建真正解析过的构件**。IDE 的 Gradle 导入与脚本模型（`prepareKotlinBuildScriptModel`）会额外解析三类清单外的东西，于是 `./gradlew` 全绿、IDE 一同步就 `Dependency verification failed`：

1. **辅助构件**：`-sources.jar` / `-javadoc.jar`（挂源码导航，栈顶 `LegacyAuxiliaryArtifactResolver.getResolvedAuxiliaryArtifactFiles`）。
2. **平台 distro 的 POM**：`com.jetbrains.intellij.idea:ideaIU:<本机 IDE 版本>`——版本随每个开发者本机 IDE 变化（本轮该用户命中 `263.7307.245-EAP-CANDIDATE`，另有 `243.28141.41` / `2024.3.7.1`）。
3. **Gradle 发行版自身的附属物**：`gradle:gradle:<ver>` 的 `-src.zip`（Gradle custom / Gradle distributions 仓库）、`org.apache.groovy:*` 的 `.module`（Gradle Libs 仓库）。

**修法（五条，缺一条就会再红一轮）**：

```xml
<trusted-artifacts>
   <trust file=".*-sources\.jar" regex="true"/>
   <trust file=".*-javadoc\.jar" regex="true"/>
   <trust group="com.jetbrains.intellij.idea" file=".*\.pom" regex="true"/>
   <trust group="gradle"/>
   <trust group="org.apache.groovy"/>
   <trust group="org.codehaus.groovy"/>
</trusted-artifacts>
```

依据：sources/javadoc 不进编译类路径也不进运行时；平台 POM 不含代码且**不能逐版本写死 checksum**（否则构建依赖「谁在哪个 IDE 里同步」）；`gradle`/Groovy 三组来自本机 Gradle 发行版，而发行版本体已由 `gradle-wrapper.properties` 的 `distributionSha256Sum` 钉死（T-SAF-15），Maven 侧 checksum 无源可依。其余第三方构件（含 `org.jetbrains.kotlin`、`com.github.jsqlparser`）照旧严格校验。

**验证读数**：探针（init 脚本解析 `jsqlparser:5.3@sources`、`ideaIU:243.28141.41@pom`）修复前都红、修复后都绿（`PROBE_RESOLVED` / `POM_PROBE_RESOLVED`）；同轮探针里 `commons-math3` / `jmh-core` / `jopt-simple` 等**二进制**构件仍照旧失败 → 严格性只对上述三类开口。**证据边界**：第 3 类只在 IDE 脚本模型路径出现，CLI 无可复现入口（`localGroovy()` 走自解析依赖、不经校验面），该条以用户同步读数为准。

**Why:** T-SAF-14 的验收只跑了 CLI（`./gradlew` 绿就收工），漏掉了「IDE 解析面」这一整维；结果是用户每次同步撞一类，三轮才收敛。这类失败不伤产物，但直接卡住开发者编译。

**How to apply:** 任何项目引入依赖校验时，生成清单的同一提交里就把上面五条 `trusted-artifacts` 写全；验收必须「CLI + IDE 双读数」，不能只看 `./gradlew`。用户报「IDE 编译报依赖校验失败」时先问是哪个构件：`-sources`/`-javadoc` → 第 1 类；`ideaIU-*.pom` → 第 2 类；`gradle-*-src.zip` / `*.module` → 第 3 类。**不要**用「关掉 `verify-metadata` 或删清单」来止血——那会把二进制构件的字节级保护一并撤掉（若确要这么做，属安全姿态变更，须用户拍板）。已同步落仓 `docs/LESSONS.md` L25。相关：[[project-security-audit-fixes-merge-20261109]]、[[pitfall-gradle97-tasks-all-asmfat]]。
