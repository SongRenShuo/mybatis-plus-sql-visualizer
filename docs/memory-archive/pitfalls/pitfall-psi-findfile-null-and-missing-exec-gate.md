---
name: pitfall-psi-findfile-null-and-missing-exec-gate
  description: 执行 SQL 报「创建临时 SQL 文件失败：findPsi: PsiManager.findFile null」的诊断口径与修法——findFile 只有三条平台 null 分支；真凶是**落点**，已反编译定案＝**2026.3 新增 `shouldCreatePsi()` 闸门**（父目录不在内容模型里就直接不建 PSI，243/262 没有这道门）；判据分两支＝registry `ide.hide.excluded.files` 勾选看 excluded、未勾只看 ignored（**所以「Mark as Excluded」在默认配置下不触发**）；方言/vf/竞态/并发四类假说已逐个实验否掉；修法＝可建 PSI 门 + 清映射 + vf 重定位 + 按内容模型判落点（项目根 `.sql-visualizer/`）+ 多字段诊断；20261111 已在 Windows 用「勾 registry + 标 Excluded」复现成功，默认配置下只排除不触发
metadata:
  node_type: memory
  type: project
---

# `PsiManager.findFile` 返回 null 意味着什么，以及 exec 链缺「可建 PSI 门」

20261110 用户转述第三方反馈：Mac + UOS 点「执行 SQL」必报 `执行失败：创建临时 SQL 文件失败：findPsi: PsiManager.findFile null (mpsql-exec.sql)`，Windows 正常；截图实证 `<项目>/.idea/sql-visualizer/exec/mpsql-exec.sql` 存在（531 B）且可读写。立项 `docs/开发计划/20261110-1459-T-ExecPsiGate-…-开发计划.md`。

**平台事实（243 `ideaIU-2024.3.7.1-sources.jar` 逐行核对）**：`FileManagerImpl.findFile` 只有三条 null 分支——① `vFile.isDirectory()`；② `!vFile.isValid()`（记 `InvalidVirtualFileAccessException`，idea.log 有 ERROR）；③ `viewProvider.getPsi(baseLanguage)` 为 null（PSI 创建被平台拒绝）。`findViewProvider` 是 `@NotNull` 且 `createFileViewProvider` 恒返回 provider，所以**只要文件在、vf 有效，null 就只能是第三条**：PSI 语言没有可用的 `ParserDefinition`。判据上：分支 ② 会留下平台 ERROR 日志，拿不到该日志就说明不是切文件失败而是建 PSI 失败。

**exec 文件的 PSI 语言由插件自己写的方言映射决定**：`mapDialectBeforePsi` → `DialectPreMapping.ensureMapped` → `SqlDialectMappings.setMapping(vf, language)`，目标语言三选一（平台真实方言 / 插件自注册方言 DM·GaussDB·HighGo·Vastbase·GoldenDB / 兜底 `GenericSQL`）。**映射失败被四处静默吞掉**：`mapDialectBeforePsi` 的 `if (ensureMapped(...))` 没有 else、`setLanguageOnEdt` 在反射取不到 `SqlDialectMappings` 时直接 `return false` 不打日志、超时只 warn「EDT busy?」、预热链 findFile 失败只 warn（缓存恒空 ⇒ 用户每次点执行都重走失败冷路径）。

**关键不对称（本次最有价值的发现）**：preview 链写映射前有 `canCreateSqlPsiFor(targetLanguage)` 门（`ui/SqlPreviewPanelEditorLifecycle.kt`），不可建 PSI 就跳过映射、保持平台默认；**exec 链没有这道门**。所以同一台机器同一项目会出现「预览看得到结果、点执行必报 findPsi」。修法形状＝exec 链补同款门 + 不可建时不写映射且 `clearMapping` 清掉 `.idea/workspace.xml` 里的历史坏映射（只「不写」不够）+ findFile 失败自愈一次（clear→重取）+ 失败 detail 扩成 `vfValid/dir/fileType/mappedLang/canPsi/path` 五元组。

**OS 不是变量**：exec 写盘→refresh→映射→findFile 链路里零 `os.name`/分隔符分支（grep 实证），别按操作系统打补丁；「Mac/UOS 全中」应解释为这批用户的环境把目标语言解析成了不可建 PSI 的方言（待 Build 号 + SQL Dialects 设置取证）。

**Why:** 这条报错文案完全不透露真因，历史上（M36.5.3 / T-PreviewPsiDialect）已经两次在同一族问题上绕路；把「三条 null 分支 + 谁决定 PSI 语言 + 闸门在哪条链上」钉死后，同类报障可以直接判分支而不是重新反编译平台。

**How to apply:** 再遇到 `findPsi: PsiManager.findFile null`，先要 idea.log 判有无 `InvalidVirtualFileAccessException`（有＝vf 失效，走 refresh 自愈；无＝建 PSI 失败，查 mappedLang 与 `canCreateSqlPsiFor`）。改这类链时两条链（preview / exec）的闸门必须成对，新加一条 PSI 建链路径就要想「它有没有同款门」。相关 [[pitfall-sql-dialect-needs-parser-definition]]、[[pitfall-platform-async-reparse-invalidates-captured-psi]]。

## 2026-11-10 落地：闸门 + 自愈 + 五元组（已合 master 并推双仓）

- 提交链：`87226496`（代码+用例）→ `180399ac`（计划/CHANGELOG）→ 合并 `0094738b` → `8da502d4`（补 vf 失效用例），master head `8da502d4`；Gitee 与 GitHub 均推送成功（`dev-support/push-all.ps1 -Branch master`，GitHub 走令牌直连、日志 `x-access-token:***@`、全程无 GCM 弹窗）。
- 主修 `applyExecDialectMapping` 三态（APPLIED / SKIPPED_NO_PSI / NOT_APPLIED）：目标语言建不出 SQL PSI 就**不写映射并清掉历史映射**（只「不写」不够——`workspace.xml` 里上一版写下的坏值仍然生效），退回平台默认；`findFile` 仍失败则清映射重建一次；失败 detail 换成五元组。
- **实测把机制钉成两种形态**：映射到**非** `SqlLanguageDialect` 的语言 → `findFile` 抛 CCE（栈 `SqlLanguageSubstitutor.getSqlLanguageFromMapping:98 ← getSqlLanguageFromFile:86 ← getConfiguredSqlLanguageDialect:66 ← substituteLanguage:52 ← LanguageUtil.getLanguageForPsi:56 ← FileManagerImpl.createFileViewProvider:286`，反证「映射即 PSI 语言来源」）；方言类语言但缺 parserDefinition → 返回 null。两者对调用方等价（拿不到可用 PSI），闸门一把挡、自愈一把兜。
- **复现边界（用户 20261110 追问「有没有复现用户的问题」）**：用户那台 Mac/UOS 的触发**未复现**（本机 Windows、用户内网无 `idea.log`）；**null 分支本身已复现**（`vf.delete()` 后 `findFile` 返回 null，五元组报 `vfValid=false`）。口径＝修的是这一类机制，落到哪条分支靠新五元组一次判型。（后一笔 `7dde5476` 已把 vf 重定位做成自愈，见下节。）
- 读数：`testDbExecPsiGate` 合并态 `tests=8 skipped=0 failures=0 errors=0`；反向验证临时摘闸门恰 1 红（`expected:<SKIPPED_NO_PSI> but was:<APPLIED>`），恢复复绿。
- 测试作者陷阱两条：`assertNull(SqlDialectMappings.getConfiguredMapping(...))` 不可靠（`configuredLanguageId` 走继承链，项目/全局有默认方言时不返回 null，判据要写「不是那个坏语言」）；自定义 `Language("NoParserForTest")` 不能当「方言缺 parserDefinition」的替身（它会先在 substitutor 里抛 CCE）。相关 [[pitfall-kotlin-trailing-lambda-binds-last-param]]、[[reference-db-integration-test-bucket-wiring]]。

## 2026-11-10 第二轮：两个「能否复现」实验，把 null 来源收窄成只剩 vf 失效

用户追问「如果是 linux 环境是否可以复现」→ 先做两个本地实验（不需要 Linux）再决定修到哪：

- **R2 并发外部写守卫**（`testConcurrentExternalWriteNeverYieldsUndiagnosedNull`）：外部线程绕过 VFS 写 304~344 次 + 每次并发 `refreshAndFindFileByIoFile`，主线程同步 `findFile` → 三轮 `ok=369/506/79，null=0 ex=0`。**本地盘上「外部写 + 并发 refresh」打不出 vf 失效**：平台对内容分叉是自愈的（内容更新、vf 有效、PSI 重建）。
- **R3 坏映射重复取守卫**（`testRepeatedFindFileAfterMappingCceKeepsThrowing`）：坏映射下连取 5 次 `findFile` → 全部 `THROW:ClassCastException`。**否掉「预热先撞 CCE → 冷路径再取即 null」的平台缓存假说** ⇒ 用户文案里的**纯 null 不能归到映射侧**。
- 两条合起来：本地能造出用户 null 形态的路径**只剩 vf 失效**，所以原本「另立项」的 vf 重定位升为**本单自愈支 1**（提交 `7dde5476`）：`healExecPsi` 先判 `vf.isValid`，失效则 `refreshAndFindFileByIoFile(ioFile)` 取新 vf 再建 PSI；调用方一律用 `psi.virtualFile` 取回那个 vf（冷路径 / 预热 / `markCachedTempPsiFile` / 后续 attach 必须同一个）。两支顺序固定：**重定位 → 清映射**。
- 读数：`testDbExecPsiGate` 合并态 `tests=10 skipped=0 failures=0 errors=0`；反向验证把重定位分支条件改成 `false && …` → `10 tests completed, 1 failed`（红在「文件在盘上、vf 失效时必须靠重定位拿回 PSI」），恢复复绿、`RED PROBE` 清零。
- 日志三支都实测跑到：`exec dialect mapping SKIPPED … historical mapping cleared=true` / `exec PSI rebuilt after clearing dialect mapping` / `exec vf was INVALID — re-located by refresh and rebuilt PSI`。
- 尾巴：全量门禁留收口轮（本轮动了 `buildSrc` 测试桶登记，`db=` 计数应变 11）；263 上 `SqlDialectMappings.setMapping` 逐条取证未做；真机验收待用户（Mac/UOS，内网无 idea.log ⇒ 只靠五元组文案）。**这两条实验的开销很低（各 1 秒级、无外部依赖），同类「能不能复现」的追问优先用它替代搭环境。**

## 2026-11-11 第三轮：真凶是**落点**（`.idea`），不是方言

- 用户五元组（UOS 20 + macOS 两台、**同一个项目** `fortress4_basetime`）：`findPsiAfterHeal: vfValid=true, dir=false, fileType=SQL, mappedLang=null, canPsi=false`。读法：vf 失效 / 目录 / 文件类型三条全排除；`mappedLang=null` 是 heal 已清映射的结果；**`canPsi=false` 是派生值不是证据**（`canCreateSqlPsiFor(null)` 恒 false）——诊断里凡是「由另一个字段推出来的字段」都不能当判据。
- 用户截图（Settings → SQL Dialects）：Global/Project 都是 `<None>`，`exec/mpsql-exec.sql = MySQL`、`preview = Oracle` ⇒「继承方言是毒源」被否证；映射到的正是能建 PSI 的 MySQL。
- **R4**（`testProbeEveryDialectForPsiCreation`）：把 38 个平台方言逐个映射上去取 PSI → `bad=0`，243 上没有任何方言建不出 PSI。
- **R5**（`testCreateTempPsiFileUnderReparseRaceProbe`）：池线程跑**生产函数** `createTempPsiFile`，每轮交替 MySQL↔PostgreSQL 强制重映射 + 重解析 → 20 轮 `fail=0`（进常驻套件时缩到 3 轮）。**exec 链没有 preview 的 `awaitPreviewPsiLanguage`，但这条也没能复现。**
- **决定性线索**：用户把同一个 `mpsql-exec.sql` 放桌面（项目外）用数据源右键 script 能执行，**只要放 `.idea/` 下就报错** ⇒ 落点本身在那台机器上建不出 PSI（`.idea` 被 excluded，或平台把它当配置目录）。至此方言 / 映射 / vf / 竞态四类假说全部关闭。
- 修法（`908623ed`）：`healExecPsi` 加**支 4 换落点**——前三支都失败且有 SQL 文本时写 `<base>/.sql-visualizer/exec/mpsql-exec.sql`（仍在内容根内、不在 `.idea` 下），复用 `projectExecFile(preferProjectRoot = true)` 的 document 写与「File Cache Conflict」规避；目录自带 `.gitignore`（内容 `*`）不污染 git status；成功即把项目记进 `DataSourceSelector.execRootFallbackProjects`（弱键集），**后续执行直接走备用落点**（否则每次先撞三轮失败，每轮还带 2s EDT 有界等待）。
- 诊断再补三项（`23457844`）：`psiLang=`（`LanguageUtil.getLanguageForPsi` 实际选中的语言，抛异常则 `EX:<类型>`）、`canPsiActual=`、`ideBuild=`（`ApplicationInfo.build.asString()`）；此前已补 `inheritedLang` / `canPsiInherited`。判读表：`psiLang=MySQL + canPsiActual=false` ⇒ 那台机器 MySQL 方言缺 ParserDefinition；`EX:CCE` ⇒ 替换器崩；`null` ⇒ `.sql` 未识别。
- 读数：`testDbExecPsiGate` **14/0/0/0**（R4/R5/R6 三探针 + 支 4 用例都在内）。支 4 的**触发链未做反向验证**——前三条都失败才走到，而 243 造不出「`.idea` 建不出 PSI」：`ModuleRootModificationUtil.addExcludedRoot` 与 `ModuleRootModel.addExcludedRoot` 在 243 都不可用（sources jar 是**部分工件**，连 `SingleRootFileViewProvider`、`ModuleRootLayer` 都没收），三次尝试均编译失败。**这条只能靠用户真机确认。**
- 遗留：preview 链临时目录也在 `.idea/sql-visualizer/preview/`，若用户那边预览也降级（横幅「临时文件解析失败」）说明同一根因，需另立项迁落点。

**How to apply 追加**：同类「某目录下的临时文件建不出 PSI」优先怀疑**落点**（是否 `.idea` / excluded / 平台配置目录），而不是先怀疑方言；scratch 文件放 `.idea` 在个别 IDE 上不可靠，备用落点选项目根 dot-dir + 自带 `.gitignore` 最稳。用户问「有没有复现」时，如实给「哪条分支复现了、哪条没复现」，别用未复现的机制冒充定案。

## 2026-11-11 第四轮：反编译 2026.3 定案（闸门代码到手）

用户问「能不能下载 Linux 的 IDEA 包反编译复现」——**不需要下载**：平台 jar 跨 OS 是同一份字节码（Linux/Windows/macOS 只差原生库与 JBR），所以能解释差异的只有**版本**，不是 OS。本机已装 `IU-263.7307.200`（`C:\Users\USER\AppData\Local\Programs\IntelliJ IDEA`），直接反编译它即定案。

**2026.3 的 `SingleRootFileViewProvider.createFile()`（243/262 没有这一段）**：

```java
return this.shouldCreatePsi()
     ? this.createFile(manager.getProject(), getVirtualFile(), getFileType())
     : null;              // 闸门不过 → createFile null → getPsi null → findFile null，而 vf 完全有效
```

**闸门本体 `AbstractFileViewProvider.shouldCreatePsi()`**：① `isIgnored()`（`FileTypeRegistry.isFileIgnored`）为真 → false；② 本地实体文件且 `getManager().findDirectory(parent) == null` 且不在 `isInLibrarySource/isInLibraryClasses` → false。**②就是真凶**：`.idea` 被 excluded（`<project>/.idea/misc.xml` 的 `<excludeFolder>`，随仓库共享）⇒ 该目录下任何 `.sql` 都拿不到 PSI ⇒ 项目里每个开发者、任何 OS 一起坏（正是「UOS+Mac 两台、同项目 `fortress4_basetime`」）。

**为什么本地一直复现不出来**：243/262 的 `createFile()` 直接调 `createFile(...)`，没有这道门。所以「Windows 正常、Mac/UOS 坏」是**平台版本差异**，不是 OS/方言/vf/竞态——前面 R2~R6 全空的读数由此全部自洽。

**修法（`61205cf2`，按平台判据提前判定，不再靠撞）**：新增 `execDirCanHostPsi(project, dir)`＝复用平台同一条 `PsiManager.findDirectory(dir)`，按目录路径记忆（会话级，探不出来按「能」处理）。`projectExecFile` 先探 `.idea/sql-visualizer/exec`，探不过**直接**落 `<base>/.sql-visualizer/exec/`；`projectPreviewFile` 同款落 `<base>/.sql-visualizer/preview/`（否则 2026.3 + `.idea` 被排除的项目里预览会一直挂「临时文件解析失败」降级横幅）。自愈支 4 保留为兜底。读数 `testDbExecPsiGate` **14/0/0/0**。

**遗留**：`shouldCreatePsi` 的第一条（`isIgnored()`，文件被 IDE 标成 ignored）也会产生同症状，本单 pre-check 只覆盖第二条；真机若仍残留，把 pre-check 换成「写探针文件试建一次 PSI」即可覆盖（更贵）。

## 2026-11-11 第五轮：影响面审计（用户问「除预览、SQL 历史一类还有没有受影响」）

结论：**只有两个落点在 `.idea` 下的临时 SQL 文件受影响**（exec / preview，均已在 R7 `61205cf2` 迁出），其余功能的 PSI 要么不涉及文件、要么取的是用户自己的源码（在内容根内）。

- **不受影响，且有代码级依据**：SQL 历史（列表/钉住/正则检索/导出/版本对比/**双击打开预览**）——历史文件是纯文本，`SqlLogAutoSaver` 写入与 `HistorySqlLogPanel` 读取里没有任何 `findFile`/`PsiManager`，双击打开的预览是从文本重建的；MCP `sqlv_history_search` 只读文件；日志捕获与列表＝文本解析渲染；CRUD 生成 / 反向转换 / JOIN 图 / EXPLAIN＝取**用户源码**的 PSI；设置与数据源配置＝平台持久化的 xml。
- **旧执行入口 `DatabaseConsoleLauncher`**（系统临时目录 `mpsql-exec-*.sql`）：自己**不取 PSI**（grep `findFile`/`PsiManager` 全空），只写文件 + 打开编辑器；但它打开的是**项目外**文件，2026.3 上同样拿不到 PSI ⇒ 编辑区没有高亮/补全。属 M36.5.11 的老账，与本单无关，要修得把它也挪进内容根。
- **迁落点不会打断既有判据**（两条都核过）：`isExecVfPath(path)` 是 `endsWith("/sql-visualizer/exec/mpsql-exec.sql")`，新旧路径都命中；预览 inlay 认的是**文件名形态**（`mpsql-preview-*`），不锚定目录。
- **迁出 `.idea` 顺带修好一件事**：平台本来就不把 `.idea` 下的文件交给 daemon（`PreviewUnknownColumnAnnotator.isProjectConfigSurface(path) = path.contains("/.idea/")` 就是为此写的跳过判据），所以 ExternalAnnotator 通道对 exec/preview 一直是关的、只靠 `PreviewSchemaHighlighter` 的 RangeHighlighter 兜底；挪到项目根后 daemon 通道对这俩文件也生效。

**How to apply 追加**：回答「这类平台限制影响哪些功能」时按「这个功能的 PSI 取自哪个文件」逐个落位——只有 scratch 文件自身在 `.idea` 下的才中招；同时给出「迁位置会不会打断 path 判据」的核对结论，别只说迁移。

## 2026-11-11 第六轮：Windows 本机怎么复现（registry 决定判据走哪一支）

用户照「Mark `.idea` as Excluded」在自己 demo 项目上**没复现**，其 Registry 截图显示 `ide.hide.excluded.files` **未勾选**（Value 空、Source 默认）。反编译 `FileManagerImpl.isExcludedOrIgnored` 正好解释：

```java
return Registry.is("ide.hide.excluded.files")
     ? fileIndexFacade.isExcludedFile(vFile)    // 勾选才走这条
     : fileIndexFacade.isUnderIgnored(vFile);   // 默认走这条：只看「忽略集」
```

`ProjectFileIndexFacade.isUnderIgnored` → `myFileIndex.isUnderIgnored(file)`。所以 **registry 未勾时「Excluded」完全不参与判定**，必须让目录落进忽略集（`ProjectFileIndex.isUnderIgnored`）才会 `findDirectory == null`。

**复现配方**（2026.3 = `C:\Users\USER\AppData\Local\Programs\IntelliJ IDEA`，`build.txt` = `IU-263.7307.200`；243/262 无这道门，怎么造都不会复现）：
- A（最快、确定）：勾上 `ide.hide.excluded.files` → 工具提示明写**需重启 IDE** → 重启 → `.idea` Mark Directory as → Excluded；
- B（更贴近现场、不用重启）：`Settings → Editor → File Types → "Ignore files and folders"` 加 `.idea`（喂的就是 `isUnderIgnored`）。
- 判据：`.idea/probe.sql`（内容 `select 1`）**没有任何 SQL 语法高亮**，同内容放 `<项目根>/probe.sql` **正常高亮**。**别用波浪线当判据**——`.idea` 下 ExternalAnnotator 本来就不跑（`isProjectConfigSurface` 就是为此写的跳过）。

**推论（回答「为什么只有那个项目坏」时要一起讲）**：「`.idea` 在 `misc.xml` 里被 `<excludeFolder>`」只是半个条件；真触发还要求该目录进忽略集（或 registry 勾上）。企业项目里 `.idea` 通常在 `.gitignore`／忽略列表里，两者叠加才中招——而 demo 项目只 excluded 未 ignored，正是**默认配置下的期望行为**，不是环境没配对。

**Why:** 上一轮把根因写成「`.idea` 被 excluded」，但默认 registry 下 excluded 根本不参与判定；不补这一层，任何人照配方都复现不出来，会把「复现失败」误读成「根因判断错了」。

**How to apply 追加**：给复现步骤前先确认对方 IDE 的 `ide.hide.excluded.files` 取值；只说「标 Excluded」是不够的。

## 2026-11-11 第七轮：Windows 复现成功（配方 A 已验证）+ registry 的两个作用面

- 用户照配方 A 操作（勾 `ide.hide.excluded.files` → 重启 → `.idea` Mark Directory as Excluded），在 `mybatis-plus-sql-visualizer-demo` 项目上复现出与 UOS / Mac **一字不差**的五元组：`findPsiAfterHeal: vfValid=true, dir=false, fileType=SQL, mappedLang=null, canPsi=false, path=F:/code/…/mybatis-plus-sql-visualizer-demo/.idea/sql-visualizer/exec/mpsql-exec.sql`。⇒ 第四轮从反编译得到的 `shouldCreatePsi()` 推断**已由真机证实**，不再是推断；同时**「默认 registry 下只标 Excluded 不触发」也被证实**（用户第一次按 Mark as Excluded 操作没复现）。
- **`ide.hide.excluded.files` 的两个作用面**（回答「这开关干什么用」时两句都要说）：
  - ① 项目视图 / VCS：工具提示原文 *Do not show excluded files in the Project View and exclude them from VCS* —— 勾上后 Excluded 目录不显示、不入版本控制；
  - ② PSI：`FileManagerImpl.isExcludedOrIgnored` 据此在 `isExcludedFile`（勾选）与 `isUnderIgnored`（未勾）之间切换。
  - ⇒ **复现旋钮与用户现场（`.idea` 落在忽略集里）翻的是同一个谓词**，所以修复的 pre-check 用平台原判据 `PsiManager.findDirectory` 两条分支都覆盖，**不依赖 registry 取值**。
- 复现验完**必须提醒用户取消勾选并重启**：勾着会把所有 excluded 目录从 Project 视图隐藏，干扰后续操作。
- 记录提交 `8c266bcf`（计划 §13.8）。
- **仍未闭合的一条**：修复版（`61205cf2` 及之后）在同一复现环境下的行为未验——期望落到 `<demo>/.sql-visualizer/exec/` 并跑通。这是「修复是否真解决用户问题」的唯一缺口；用户若报仍失败，就按报错里的 `psiLang` / `canPsiActual` / `ideBuild` 继续判型（`psiLang=MySQL + canPsiActual=false` ⇒ 那台机器该方言缺 ParserDefinition）。

## 2026-11-11 第八轮：「看不到 `.idea`」与「被排除」是两个独立判据

用户报「我**没勾** `ide.hide.excluded.files`，但 `.idea` 在 Project 视图里**看不到**，是不是还有别的注册表配置？」——**没有第二个注册表键**，这两件事根本不在同一层：

| 「看不到」的来源（都不影响 PSI 判定） | 说明 |
|---|---|
| Project 视图右上齿轮 /「…」菜单的 **Show Excluded Files**、**Show Ignored Files** | 纯视图开关，新 UI 默认隐藏，按项目存在视图配置里 |
| **VCS 忽略**（`.gitignore` 里的 `.idea/`） | 文件被标 ignored，视图默认隐藏（受上面开关控制） |
| `Settings → Editor → File Types → Ignore files and folders` | 命中即视为忽略 |

而 2026.3 的闸门只看两样：**在不在忽略集**（`isUnderIgnored`，registry 未勾时的唯一判据）或**被排除 + registry 勾选**。所以：

- registry 未勾却能报出这个错 ⇒ `.idea` **必在忽略集里**（企业项目里 `.gitignore` 写 `.idea/` 几乎必中），「看不到」与「无 PSI」是同一件事的两面，自洽；
- 反过来若只是「Mark as Excluded」而不在忽略集、registry 也没勾 ⇒ PSI 应该还在、**不会**报这个错。判型时用这条排除。

**诊断再补三项（`1a5ce405`）**：`excluded=` / `underIgnored=` / `regHideExcluded=`（`ProjectFileIndex.isExcluded` / `isUnderIgnored` / `Registry.is`）。判读：`underIgnored=true` ⇒ 忽略集触发（用户现场）；`excluded=true + regHideExcluded=true` ⇒ 排除触发（本机复现旋钮）；两个都 false 却仍失败 ⇒ 还有第三条路。摆在失败文案里，用户截一张图就够，不必让他去翻设置。

**实现坑**：`Registry` 的正确 FQN 是 `com.intellij.openapi.util.registry.Registry`（我第一版写成 `com.intellij.registry.Registry`，编译直接 `Unresolved reference`）。

**How to apply 追加**：凡「某目录本该有 X 却看不到/没有」的问题，先分清是**视图层**（开关、忽略）、**索引层**（内容模型、忽略集）还是**PSI 层**（`shouldCreatePsi`）；三者判据不同、修法也不同。要用户配合定位时，把判据做进**失败文案**比让他去翻设置页更省事。

## 2026-11-11 第九轮：智能失败提示（用户定案「蓝色可点跳设置页」，`a34aef25`）

用户原话：「针对这两个问题做智能错误信息提示，精准识别原因并提供蓝色字体可以点击调整到对应设置页」。三种成因（文件类型忽略 / VCS 忽略 / 排除+registry）在报错上完全同形，而修复点各不相同。

- **判定抽成纯函数**（`execute/ExecPsiFailureHint.kt`）：`classifyExecPsiFailure(excluded, regHideExcluded, fileTypesIgnored, underIgnored, psiLangId, canPsiActual)` → `ExecPsiFailureCause`。**顺序即语义**：排除+registry → 文件类型忽略 → VCS 忽略 → 无语言 → 方言不可用 → UNKNOWN。两条优先级各有理由：`fileTypesIgnored` 必须早于 `underIgnored`（两者都让文件进忽略集，但只有前者能指到可操作的设置页，指去 `.gitignore` 是误导）；忽略类必须早于方言类（目录被忽略时语言往往仍能选中，据此判方言会把用户送到错的页面）。
- **观测取值** `execPsiFailureCauseNow(project, vf)`：`ProjectFileIndex.isExcluded` / `isUnderIgnored` + `Registry.is("ide.hide.excluded.files")`（FQN 是 `com.intellij.openapi.util.registry.Registry`）+ `FileTypeManager.getInstance().isFileIgnored(vf)` + `LanguageUtil.getLanguageForPsi` / `canCreateSqlPsiFor`；任一项取不到按 false/null（判错最多入口指偏，不影响执行链）。
- **传递**：`DataSourceSelector.lastTempFileHint`（`@Volatile`，冷路径与预热入口都清空）+ `consumeTempFileHint()` 取一次即清，避免后续无关错误复用上一个 hint。
- **呈现**：`ui/SqlPreviewPanelExecution.showTempFileError(msg)` 走既有 `SqlResultPanel.showErrorWithActions`（方言闸门同款形态：透明按钮 + `Link.activeForeground`，渲染即蓝字），**5 处 `onError = { resultPanel.showError(errorMsg) }` 全部改走它**；无 hint 时逐字退回原文案，不干扰普通 SQL 错误。
- **入口映射**：文件类型忽略 → 打开设置页（Configurable EP id **`preferences.fileTypes`**，2026.3 jar 内 xml 实证）；VCS 忽略 → 打开项目根 `.gitignore`（不存在先建空文件）；方言不可用 / 无语言 → 复用既有 `openSqlDialectSettings`。
- **Excluded 档刻意只给文案不给按钮**：`ProjectStructureDialog` 在 243 与 2026.3 都已无稳定公开入口（2026.3 `lib/` 里 `ProjectStructure*` 只剩 webide / externalSystem 变体）。**宁可指不出，也不把用户送到错设置页**——这个取舍必须落进代码注释，否则后人会「顺手补个链接」。
- 用例 `ExecPsiFailureHintTest` 7 例表驱动（含「registry 关时 excluded 不算」「文件类型优先于 VCS」「忽略压过方言」「全正常给 UNKNOWN」）；反向验证把两条 `if` 换序 → `7 tests completed, 1 failed`（红在优先级那条），恢复全绿、`RED PROBE` 清零。定向 `ExecPsiGateTest` 14/0/0/0 + 本类 7/0/0/0。
- **适用边界**：这条提示只在「连项目根备用落点也建不出 PSI」的残余场景露头——正常路径下 pre-check 已把文件写到 `.sql-visualizer/`，用户根本看不到报错。别把它当主修。

**How to apply 追加**：做「失败信息可操作化」先问三件事——①这个失败有几种**可区分的**成因？②每种成因的修复点在**设置页 / 文件 / 外部系统**哪一类？③没有稳定入口的那档能不能**诚实地不给按钮**？三问齐了再把判据做进文案。

## 2026-11-11 第十轮：beta.1 验收失败 + 两处自家 bug（beta.2 已提交未发）

`2026.11.12-beta.1` 发出去后用户在**日常 IDEA 2026.3** 验收，看到两处异常（截图）：预览一直「以简化模式打开 · 临时文件解析失败」，点「执行 SQL」只报「创建临时 SQL 文件失败：**unknown**」、两颗蓝字也没出现。两条都是本单上一版引入的，与用户环境无关，详见 [[pitfall-cross-thread-status-field-and-stale-config-memo]]：

- **F3**：失败原因挂在跨线程共享字段 `lastTempFileFailure` 上，预热链在入口清空它 ⇒ 并发时读到 null，文案退化成 `unknown`、依赖同一次记录的两颗蓝字一起消失。修法＝**原因在失败现场就地捕获**、随 `createTempPsiFileAsync` 回调 `(pair, failureReason)` 带回；预热链不再清字段。
- **F4**：`execDirCanHostPsi` 把「目录能否建 PSI」的结论按路径**缓存到会话结束**，而输入是内容模型 + Registry（用户排查时改过）⇒ 旧结论留在「能建」一侧，预览一直写 `.idea`、备用目录没被用上。修法＝**每次现探**。

**版本状态**：修复提交 `aed5f725`，版本号已改 **`2026.11.12-beta.2`**（changeNotes/CHANGELOG/`preview.update.json`/发行说明同步），**已推双仓但未发版**——用户 2026-11-11 令「先不急着发布，更新的信息可以提交」，随后又令「推 master」并明确「`preview.update.json` 先推没关系，不会影响到正常用户」。发版顺序按新规矩：**先派 `mode=gate`，绿了再 tag + buildPlugin + 双平台发行**。

**读数**：`ExecPsiGateTest` 14/0/0/0、`ExecPsiFailureHintTest` 8/0/0/0、`ExecTempFileEdtGuardTest` 2/0/0/0、`ConsolePsiWarmGuardTest` 2/0/0/0（异步三段式守卫未被回调签名改动打穿）、`ChangeNotesRatingLineGuardTest` 3/0/0/0、`BundlePlaceholderArityTest` 4/0/0/0。

**仍未闭合**：修复版（`aed5f725` 及之后）在 2026.3 + `.idea` 不可建 PSI 的环境下**行为未验**（beta.1 那次验收正是失败在这条）。若用户再报失败，用报错行里的 `psiLang` / `canPsiActual` / `excluded` / `underIgnored` / `ideBuild` 判型，并请他看一眼项目根有没有 `.sql-visualizer/`（备用落点是否真被用到）。
