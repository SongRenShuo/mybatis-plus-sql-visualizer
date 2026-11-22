---
name: reference-intellij-native-sql-column-inlay
description: IDEA 原生 SQL 列名 inlay 的确切注册面（providerId/选项/默认/jar），以及「inlay 逐位去重不可能」的平台证据（per-provider HintsBuffer 收集后才落 InlayModel）
metadata:
  node_type: memory
  type: reference
  originSessionId: sess_4c2cb0e3
---

查「IDEA 原生是不是已经有这个编辑器提示能力」以及「能不能让两个 inlay provider 逐位去重」的实测口径（2026-11-12，IU-263.7307.200 发行物一级）。

**原生 INSERT 列名提示的注册面**（`plugins/DatabaseTools/lib/database-plugin.jar!/META-INF/plugin.xml:2533-2538`）：

```xml
<codeInsight.declarativeInlayProvider providerId="sql.column.names.inlay.hints" language="SQL"
  implementationClass="com.intellij.sql.inlays.SqlColumnNamesInlayHintsProvider"
  isEnabledByDefault="true" group="PARAMETERS_GROUP">
  <option optionId="sql.show.column.names.in.insert.values"   enabledByDefault="true"/>
  <option optionId="sql.show.column.names.in.select"          enabledByDefault="true"/>
  <option optionId="sql.show.column.names.for.asterisk"       enabledByDefault="true"/>
  <option optionId="sql.show.column.names.for.set.operations" enabledByDefault="true"/>
</codeInsight.declarativeInlayProvider>
```

- 实现类在 `plugins/DatabaseTools/lib/modules/intellij.database.sql.core.impl.ex.jar`，实现的是**声明式** `com.intellij.codeInsight.hints.declarative.InlayHintsProvider`（走 `DeclarativeInlayHintsPass`，**不是** legacy `InlayHintsPass`），选项由 `InlayTreeSink.whenOptionEnabled(...)` 读；预览样例在同 jar `inlayProviders/sql.column.names.inlay.hints/*.sql`（`VALUES (/*<# id #>*/1, …)`）。
- 判「原生开没开」的 API：`com.intellij.codeInsight.hints.declarative.DeclarativeInlayHintsSettings`（`lib/intellij.platform.lang.jar`）→ `isProviderEnabled(providerId): Boolean?`、`isOptionEnabled(providerId, optionId): Boolean?`（带 `@RequiresReadLock`）；legacy 一侧是 `InlayHintsSettings.Companion.instance` 的 `getDisabledHintProviderIds()` / `getDisabledLanguages()` / `hintsEnabled(Language)`。
- EP bean 类 `com.intellij.codeInsight.hints.declarative.InlayHintsProviderExtensionBean`（读 providerId/implementationClass/language）。**两代 EP 都要枚举**：`codeInsight.declarativeInlayProvider` 与 `codeInsight.inlayProvider`（243~262 时期原生可能按旧 EP 注册）。

**逐位去重为什么不可行**（本机 `lib/intellij.platform.lang.impl.jar` 常量池实测）：

1. `InlayHintsSinkImpl` 不直接写编辑器，`addInlineElement` 写进 `HintsBuffer`（收口 `complete()`）；
2. `CollectorWithSettings` **每个 collector 自带一个 sink**，`collectHints` 只转发 `collector.collect`，结束时各自 `sink.complete()` 出 per-provider buffer；
3. `InlayHintsPass$Companion.applyCollected(HintsBuffer, PsiElement, Editor, …)` 才在**收集全部结束之后**写 `editor.inlayModel.addInlineElement`（它自己也按 offset `inlayModel.getInlineElementsInRange(...)` 取"已有 inlay"）。

⇒ 收集期各 provider 互相看不见（还叠着平台并发分片跑），在 collector 里"先看对方画没画"读到的是上一轮 pass 的残影。**让位判据只能取能力级（在位 + 开启），不能取位置级。**

**读 263 class 的可行手法**：本机 PATH 只有 JDK8（`D:/HuanJing/Java/jdk1.8.0_181`），JBR 不带 `javap`（见 [[reference-platform-artifact-keymap-lookup]]）；只想拿「类实现了什么接口、有哪些方法名/字符串常量」时，用零依赖 Python 读 class 常量池 UTF8 表即可（跳过 tag 3/4/9~12/17/18 各 4 字节、5/6 各 8 字节、15 各 3 字节、7/8/16/19/20 各 2 字节），比上 Fernflower 快得多。

相关：[[project-preview-inlay-native-yield-20261112]]（把这份事实用在本仓的让位计划上）、[[feedback-prefer-ide-native-yield-on-overlap]]。