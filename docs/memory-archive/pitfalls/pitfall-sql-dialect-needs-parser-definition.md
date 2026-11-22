---
name: pitfall-sql-dialect-needs-parser-definition
description: 插件用 sql.dialect 自注册 SQL 方言时必须同时注册 lang.parserDefinition——否则平台回落到基语言
  SQL 的 parser definition，createFile 强转 SqlLanguageDialect 必抛 CCE，预览 PSI 建不出来；20261030 已按
  T-PreviewPsiDialect 修复（四方言补齐 + 映射前探测 + findFile 兜底 + 配对守卫）
metadata:
  node_type: memory
  type: project
  updated: 2026-10-30
  originSessionId: session_ed208fad1e5bbee65786fb80020e8654
---

# 自注册 SQL 方言缺 parserDefinition → 预览 PSI 创建抛 CCE

2026-10-30 用户在日常 IDE（IDEA 2026.2.3 / IU-262.10968.63，插件 v2026.10.31-beta.1）验 GaussDB 时，预览面板常驻黄色横幅「预览以简化模式打开——临时文件解析失败；表/列补全暂不可用」。真机日志 10/10 次预热全失败。**已修复并提交（`906c66d4`）**。

## 证据链

```
[M36] prewarm FAILED in 74ms: PsiManager.findFile null for mpsql-preview-69432-5.sql
[M36] in-memory PSI preview document created (len=140)
```
紧邻的 CCE 当天 9 次（3 次 SEVERE 且点名插件）：
```
ClassCastException: class com.intellij.sql.psi.SqlLanguage
  cannot be cast to class com.intellij.sql.dialects.SqlLanguageDialect
	at com.intellij.sql.dialects.base.SqlParserDefinitionBase.createFile(SqlParserDefinitionBase.java:90)
	at com.intellij.psi.SingleRootFileViewProvider.getPsiInner
	at com.intellij.psi.impl.file.impl.FileManagerImpl.findFile
	at ...SqlPreviewPanelEditorLifecycleKt.awaitPreviewPsiLanguage(SqlPreviewPanelEditorLifecycle.kt:463)
Plugin to blame: SQL Visualizer version: 2026.10.31-beta.1
```

## 根因（比「缺注册」更精确一层）

四个自注册方言（`DmSqlDialect` / `GaussDbSqlDialect` / `HighGoSqlDialect` / `VastbaseSqlDialect`，均登记在 `META-INF/database-optional.xml`）**只注册了 `<sql.dialect>`，没为同名语言注册 `<lang.parserDefinition>`**。平台 `LanguageParserDefinitions.forLanguage` 查不到就沿语言链上溯，落到基语言 `SQL` 那份（`database-plugin.jar` 的 `plugin.xml:1253` = `SqlParserDefinitionBase$Sql`）。

2024.3 制品 javap 把第 90 行前后定死了：`createFile` 里是
`new SqlCodeFragmentImpl(viewProvider, getFileNodeType().getLanguage(), …)` 再 `checkcast SqlLanguageDialect`
—— **强转的源头是「file node type 绑定的语言」**，基语言那份的 file node type 绑的是 `SqlLanguage`，所以必挂。

平台内置方言全是**成对注册**：`SQL`→`SqlParserDefinitionBase$Sql`（:1253）、`GenericSQL`→`GenericParserDefinition`（:1913）、`SQL92`→`Sql92ParserDefinition`（:1925）。这就是判据。

## 关键事实

- **不是 GaussDB 专属**：四个自注册方言注册形态相同，达梦/瀚高/海量同样会撞；没人报是因为「预览方言落到这四个语言」这条路径随 GaussDB 独立身份才真正跑起来
- **两条链都中**：预览预热与执行临时文件共用 `execute/DialectPreMapping.kt` 的映射入口
- **质量问题**：每次预览往 `idea.log` 写一条点名本插件的 SEVERE
- 平台会「频繁异常抑制」这条 CCE，只露一行 WARN，**排障时必须看紧邻的 SEVERE 原文**，否则会误判成「临时文件建不出来」
- 预览目录映射变更会一次性重解析该目录下所有历史残留文件（实测 16 个），放大触发面

## 实施结果（2026-10-30，计划 `docs/开发计划/20261030-1540-T-PreviewPsiDialect-…`）

**P-1 被证伪**：`Sql92ParserDefinition` 与 `GenericParserDefinition` **都是 `final`**，不能继承——只能照它们的实现内容自己派生。落地物：

1. **新增 `sql/SqlFamilyParserDefinitions.kt`**：`MpvSqlFamilyParserDefinition(dialect, fileTypeName) : SqlParserDefinitionBase()` + 四个无参子类（EP 要求无参构造）。
   关键三点：静态 `SqlFileElementType(常量名, 本方言语言)` 绑方言（这才是修复点）；`createElementFactory/createLexer/createParser` 全委托一个 `Sql92ParserDefinition` 实例（与方言 `createTokensHelper` 委托 Sql92 同口径）；`database-optional.xml` 成对补四条注册
2. **S2 保险 `canCreateSqlPsiFor(language)`**：取 `LanguageParserDefinitions.INSTANCE.forLanguage(lang).fileNodeType.language`，按**类名沿父类链**判是不是 `com.intellij.sql.dialects.SqlLanguageDialect`（`SqlLanguageDialect` 是抽象类不是接口；**刻意不静态引用 `com.intellij.sql.*`**——该文件是常驻加载面，DatabaseTools 可选）。探不到就**跳过映射**，把「漏注册」的最坏后果从「整条预览降级」降为「补全退 GenericSQL」
3. **S3 兜底**：两处 `PsiManager.findFile` 包 `runCatching`，平台契约异常降为带栈 WARN
4. **S4 守卫**：`convention/SqlDialectParserDefinitionPairingGuardTest`（静态配对扫描，2/0）**必须登记到 `GuardSelfTestCoverageMetaGuardTest` 的 `GUARD_CLASSES`**（文件名含 `Guard` 就会被元守卫扫）；DB 通道加 `PlatformSqlDialectRegistrationTest#testPluginDialectLanguagesCanCreatePsiWithoutClassCastException`（3/0），用 `PsiFileFactory.createFileFromText(名字, 方言语言, sql)` 建 PSI——**同步、不涉映射时序**，因此能稳定复现
5. `docs/架构设计/03` 新增方言清单补第 12 步「平台 SQL 方言注册必须成对」

**反向验证**：摘掉 GaussDB 那条注册 → 静态守卫 2 例 1 红 + DB 用例 3 例 1 红，失败形态正是 `ClassCastException`（`PlatformSqlDialectRegistrationTest.kt:119`）；还原 → 全绿。

**未闭环**：runIde 真机复验（横幅消失 / 日志零 CCE / 补全能列 `t_order`）。

## ⚠️ 第二层坑：文件根元素类型的类只在编译基线存在（`verifyGate` 才报）

第一版按上面第 1 点用 `com.intellij.sql.psi.stubs.SqlFileElementType(常量名, 方言语言)` 构造 file node type，**单测与 DB 通道用例全绿**，但 `verifyGate` 直接判：

```
Constructor MpvSqlFamilyParserDefinition.<init>(SqlLanguageDialect, String) references an
unresolved class com.intellij.sql.psi.stubs.SqlFileElementType.
This can lead to NoSuchClassError exception at runtime.
→ IU-243 Compatible；IU-253 / IU-262 各 1 problem
```

**该类只在 2024.3（编译基线）存在，253 / 262 已删掉整个 `com.intellij.sql.psi.stubs` 包**（253 制品里只剩 `hsql/psi/stubs/HsqlStubRegistryExtension`）。这类「编译期有、运行期没有」的缺口**测试跑不出来**，只有 verifier 按每个目标 IDE 逐个解析字节码引用才报。

**修法（`aaf97345`）**：改用核心 `com.intellij.psi.tree.IFileElementType(String, Language)` 直接构造——三个目标版本都在。查证链：

- `StubFileElementType` 是抽象类且要求实现 StubSerializer 四件套（`getExternalId` / `serialize` / `deserialize` / `indexStub`），用不上（预览临时文件不建存根、不进索引）；
- `IFileElementType` 在本版本线上**本身就是抽象类**（不是接口，`StubFileElementType extends IFileElementType`），恰好带 `(String, Language)` 构造器 ⇒ 一行 `class X(name, lang) : IFileElementType(name, lang)` 即可；
- `com.intellij.psi.tree.FileElementType` 在 243 的 `lib/*.jar` 里**根本不存在**（别按印象用它）。

**通用教训**：给「只在 SQL/DB 侧存在」的类型做静态引用前，先问一句「它在 253/262 还在吗」——本仓的 `verifyGate` 是唯一判据，`testFast`/`test`/DB 通道都给不出答案。

## 收口读数（2026-10-30）

`verifyGate` 三目标全 Compatible（243 = 5/42/98，253 = 6/53/96，262 = 8/128/96）；`testDbPlatformDialect` 3/0；静态守卫 2/0；agent 子项目 240/0/0/1。三轮 `testShards` 各有负载敏感用例抖红（`SqlPreviewPanelPrewarmLateRecoverTest` 三次里红两次、`LogPipelinePerfTest` 2 例一次）；最终判据＝`./gradlew testShard1 --rerun-tasks` 单跑该分片 9m10s 绿且该用例绿，定性为 5 片并发负载敏感而非回归（见 [[pitfall-regression-false-failure-flakes]]，判据要从「单类绿」升级到「单分片绿」）。

相关：[[project-degraded-preview-ux]]（降级横幅分型）、[[pitfall-platform-async-reparse-invalidates-captured-psi]]（映射→重解析时序）、[[project-xc-dialects3-research]]（四方言来历）、[[reference-gaussdb-demo-fixture-pg-ddl]]（本次验收现场）
