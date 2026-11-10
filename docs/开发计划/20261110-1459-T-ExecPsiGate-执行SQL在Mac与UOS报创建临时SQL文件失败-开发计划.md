# T-ExecPsiGate：执行 SQL 在 Mac / UOS 报「创建临时 SQL 文件失败：findPsi: PsiManager.findFile null」（2026-11-10）

- 状态：**已实施（2026-11-10，分支 `t-execpsigate` / 工作树 `mpsv-execpsigate`）；真机验收（Mac / UOS）待用户**
- 来源：用户转述第三方反馈 + 两张截图（2026-11-10 14:46:58）：
  - 报错原文：`执行失败：创建临时 SQL 文件失败：findPsi: PsiManager.findFile null (mpsql-exec.sql)`
  - 反馈口径：「Mac、UOS 都是这样」；「文件是有的，权限也没有问题，可读写可执行」；用户构建 `MPV-Build 1794286367039（2026-11-10 12:52:47）`
  - 截图同时给出 UOS 文件管理器读数：`<项目>/.idea/sql-visualizer/exec/mpsql-exec.sql`，531 B，修改时间与报错同一分钟
  - 用户自测 Windows 正常；**用户内网环境，`idea.log` 无法提供**
- 取证方式：本仓代码静态走查 + **平台源码逐行核对**（`ideaIU-2024.3.7.1-sources.jar` 内的 `FileManagerImpl` / `AbstractFileViewProvider` / `LanguageUtil`）+ 仓内同族缺陷复盘（M36.5.3、T-PreviewPsiDialect、T-ExecCopyFix、T-ExecSlowOps F1）+ 实施轮沙盒实测（DB 集成通道，见 §十二）。

---

## 一、结论

1. **失败点是 `PsiManager.findFile` 返回 null，不是文件写不进去。** 报错由 `createTempPsiFile` 的 `fail("findPsi", "PsiManager.findFile null (...)")` 分支抛出（`execute/DataSourceSelectorResults.kt:1845`）；此时 `refreshAndFindFileByIoFile` 已经返回了非空 `VirtualFile`（`:1817-1829`），磁盘写入也已成功（用户截图 531 B 为证）。所以「权限/磁盘/路径」这一层可以排除。

2. **平台侧 `findFile` 只有三条 null 分支**（243 平台源码逐行，`FileManagerImpl.findFile`）：
   - `vFile.isDirectory()` → null；
   - `!vFile.isValid()` → 记 `InvalidVirtualFileAccessException` 后 null；
   - `viewProvider.getPsi(viewProvider.getBaseLanguage())` → null（PSI 创建被平台拒绝）。
   前两条与「文件存在、可读写」互斥（但**要日志证伪**，见 §八 V3）；第三条的充分条件是 **PSI 语言没有可用的 `ParserDefinition`**——`SingleRootFileViewProvider.createFile` 取不到 definition 就返回 null（T-PreviewPsiDialect 已在 262 真机用 CCE 变体验证过同一条链）。

3. **exec 文件的 PSI 语言不是 `.sql` 的默认语言，而是插件自己写的方言映射决定的**：`mapDialectBeforePsi`（`execute/DataSourceSelector.kt:2137-2165`）→ `DialectPreMapping.ensureMapped`（`execute/DialectPreMapping.kt:238`）→ `SqlDialectMappings.setMapping(vf, language)`。目标语言三选一：平台里查得到的真实方言、插件自注册方言（DM/GaussDB/HighGo/Vastbase/GoldenDB）、兜底 `GenericSQL`。

4. **映射失败/映射到不可建 PSI 的语言时，exec 链没有任何闸门，直接继续 `findFile`**：
   - `mapDialectBeforePsi` 只写 `if (ensureMapped(...)) { ...日志... }`，**没有 else**（`:2160`）；
   - `ensureMapped` 的 KDoc 明写「false 不阻断调用方 findFile（最差 = GenericSQL PSI 旧行为）」（`DialectPreMapping.kt:224`）；
   - `setLanguageOnEdt` 在 `instanceOf(project)` 取不到实例时**静默返回 false，一行日志都不打**（`:201-217`）。
   于是「映射没落地 / 落到了没有 parser 的方言」这个真实原因被完全吞掉，用户只能看到笼统的 `findPsi`。

5. **同一台机器上 preview 能活、exec 必死，是因为「可建 PSI 门」只有 preview 有**（本次最关键的结构性发现）：
   - preview 链在写映射**之前**先过 `canCreateSqlPsiFor(targetLanguage)`，不可建 PSI 就**跳过映射、保持平台默认**，并补一条 info 日志（`ui/SqlPreviewPanelEditorLifecycle.kt:401`，实现 `:515`）；
   - exec 链**没有这道门**，照写不误。
   也就是说：只要目标语言在这台机器上建不出 SQL PSI，同一次预览「看得到结果、点执行就报 findPsi」。

6. **OS 不是变量**：整条 exec 写盘 → refresh → 映射 → findFile 链路里没有任何 `os.name` / 分隔符分支（`grep` 实证，§3.4）。「Mac / UOS 全中、Windows 正常」只能来自**环境差异**（IDE 版本、SQL 方言设置、目标语言是否会解析到不可建 PSI 的方言、项目是否首建）。本单按「去掉静默失败、补齐与 preview 对称的闸门」修，不按操作系统打补丁。

---

## 二、现象与证据

### 2.1 用户读数（三份，互相印证）

| 项 | 读数 | 推出 |
|---|---|---|
| 报错文案 | `findPsi: PsiManager.findFile null (mpsql-exec.sql)` | 走到 `:1845` 的 **null 分支**，不是异常分支（`:1844`） |
| 文件状态 | 531 B、14:46:58、可读写可执行 | 写盘成功；`projectExecFile` 与 `refreshVfs` 两段都没失败 |
| 平台 | Mac + UOS 复现，Windows 不复现 | 与 OS 无关的代码路径（§3.4），应为环境差异 |

### 2.2 代码定位（失败点与调用链）

```
chooseAndPrepare（DataSourceSelectorResults.kt:341）
└─ createTempPsiFileAsync            :1688   EDT 派发到池线程
   └─ createTempPsiFile              :1758
      ├─ projectExecFile             :1779   写 .idea/sql-visualizer/exec/mpsql-exec.sql
      ├─ consumeCachedTempPsiFile    :1790   缓存命中即返回（预热失败时恒 miss）
      ├─ findFileByIoFile + converge :1801   refresh 前收敛
      ├─ refreshAndFindFileByIoFile  :1817   → vf（用户机器上非空）
      ├─ mapDialectBeforePsi         :1837   ← 本单主修点（无闸门）
      └─ PsiManager.findFile(vf)     :1838-1845 → **null**
```

失败文案装配点：`DataSourceSelectorResults.kt:416`（`execute.tempFile.failed` + `lastTempFileFailure`）；文案键 `messages/MpSqlVisualizerBundle_zh.properties:551`。

同构链还有两处，**修复必须同批覆盖**：
- `prewarmExecTempFileInner`（`DataSourceSelectorResults.kt:1932-2021`）：findFile 失败只 `LOG.warn` 不抛（`:2013/:2016`），所以失败是**静默**的，用户点执行时预热缓存恒空，冷路径必走一次。
- preview 链（`ui/SqlPreviewPanelEditorLifecycle.kt`）：已有闸门与重取兜底，作为本次修法的**参照实现**。

### 2.3 平台源码实证（`ideaIU-2024.3.7.1-sources.jar`）

`com/intellij/psi/impl/file/impl/FileManagerImpl.java`：

```java
public @Nullable PsiFile findFile(@NotNull VirtualFile vFile) {
    if (vFile.isDirectory()) return null;                       // ← 分支 A
    if (!vFile.isValid()) {
      LOG.error(new InvalidVirtualFileAccessException(vFile));  // ← 分支 B（有 ERROR 日志）
      return null;
    }
    dispatchPendingEvents();
    FileViewProvider viewProvider = findViewProvider(vFile);
    return viewProvider.getPsi(viewProvider.getBaseLanguage());  // ← 分支 C（PSI 创建被拒）
}
```

- `findViewProvider` 是 `@NotNull`，`createFileViewProvider` 末尾恒返回 `SingleRootFileViewProvider`，**不会**给 null；
- `AbstractFileViewProvider.getPsi` 是 `final`，直接转 `getPsiInner(target)`；`SingleRootFileViewProvider.createFile` 在**拿不到 ParserDefinition 时返回 null**——该类源码不在 sources 工件内，这一句按平台行为 + T-PreviewPsiDialect 真机现象归纳，**列为 §八 V4 的首个待证**（要求先在沙盒测试里把 null 复现出来，再改生产逻辑）；
- `LanguageUtil.getLanguageForPsi`（同 sources jar）先取文件类型语言，再依次过 `LanguageSubstitutors` —— **Database 插件的 SQL substitutor 在这里把 `.sql` 换成映射到的方言语言**。这就是「映射决定 PSI 语言」的平台级证据。

结论：分支 C 的触发条件 = **映射到的语言没有可用 ParserDefinition**。这也正是 T-PreviewPsiDialect 当初在 262 真机上 10/10 次建不出 PSI 的同一族根因（那次表现为 CCE，因为平台沿语言链回落到了基语言 `SQL` 的 parser definition）。

---

## 三、根因链

### 3.1 映射目标语言的三种来源，每一种都可能建不出 PSI

`mapDialectBeforePsi`（`DataSourceSelector.kt:2143-2155`）：

| 输入 | 目标语言 | 备注 |
|---|---|---|
| 有 `renderedDialectName` 且能解析 | `PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS[name]` 命中项 | MySQL / MariaDB / PostgreSQL / Oracle / TSQL / H2 / SQLite / DM / KingBase / OceanBase / openGauss / GaussDB / HighGo / Vastbase / GoldenDB |
| 有 `renderedDialectName` 但平台无该方言 | **`GenericSQL`**（`languageById`） | 代码注释：「平台无对应 parser 时降级 GenericSQL，不冒充 MySQL」 |
| 无 `renderedDialectName` | `resolveTargetLanguage(project, targetDs)`（数据源方言路由，O-136） | 同上兜底 GenericSQL / 保持 MySQL |

三种来源**都只保证「语言对象拿得到」，不保证「这个语言能建 PSI」**。插件自注册方言的 `lang.parserDefinition` 在 `database-optional.xml:63-76` 成对注册（配对守卫 `SqlDialectParserDefinitionPairingGuardTest`），但那是**静态注册面**的守卫：EP 实例化失败、平台版本 API 变动、实现类构造抛错，都会让「方言注册上了、parser definition 没注册上」——而这正是 `canCreateSqlPsiFor` 要判的运行时状态。

### 3.2 四个静默点（为什么用户和我们都看不到真因）

| 位置 | 现状 | 后果 |
|---|---|---|
| `DataSourceSelector.kt:2160` | `if (ensureMapped(...)) { 日志 }`，**无 else** | 映射失败零日志 |
| `DialectPreMapping.kt:201-217` | `instanceOf(project) ?: return false` 在 `runCatching` 内 | 反射拿不到 `SqlDialectMappings` 时**连 warn 都没有** |
| `DialectPreMapping.kt:274` | 超时才 warn「EDT busy?」，随后 continue | 只是「没等到」，不区分「写了但无效」 |
| `DataSourceSelectorResults.kt:2013/2016` | 预热 findFile 失败仅 warn | 预热静默报废，用户点执行时必然重走失败冷路径 |

### 3.3 关键不对称：`canCreateSqlPsiFor` 只有 preview 有

`ui/SqlPreviewPanelEditorLifecycle.kt:401`（preview）：

```kotlin
if (!canCreateSqlPsiFor(targetLanguage)) {
    LOG.info("[T-PreviewPsiDialect] skip dialect mapping for '$desiredName': language '...' has no SQL parser definition; keep platform default")
    return null
}
```

`canCreateSqlPsiFor`（`:515`）判据：`LanguageParserDefinitions.forLanguage(lang).fileNodeType.language` 必须是 `SqlLanguageDialect`（按类名沿父类链判，刻意不静态引用 `com.intellij.sql.*`）；拿不到 definition 时 `runCatching` 兜成 false。

**exec 链没有这一句。** 所以同一个项目、同一个目标方言、同一台机器：preview 跳过映射保平安，exec 写完映射去 `findFile` 撞 null。这与用户反馈的「预览能用、点执行报错」完全吻合（用户未报预览异常）。

### 3.4 为什么 OS 不是变量

- `grep -rn "os.name|SystemInfo|isWindows|File.separatorChar" execute/ database/` → **零命中**（exec 链无 OS 分支）；
- 路径用 `java.io.File` + `File.separator` 拼接（`projectExecFile:1873`），VFS 侧恒 `/`，两侧都合法；
- 文件系统路径不含非 ASCII 以外的平台差异点时，VFS refresh 返回非空即说明路径解析成功（用户机器上已成立）。

因此「Mac/UOS 全中」应理解为**这批用户的环境把目标语言解析成了不可建 PSI 的方言**（例如数据源/渲染方言落到插件自注册方言，而该方言在他们那版 IDE 上 parser definition 未生效），而不是平台在 Unix 上有不同契约。§八 V1/V2 给出证伪路径。

---

## 四、修复设计

### S1 主修：exec 链补「可建 PSI 门」，与 preview 对齐

`mapDialectBeforePsi` 在 `ensureMapped` 之前插入与 preview 完全同款的判定：

1. `targetLanguage == null` → 现有兜底逻辑不变；
2. `!canCreateSqlPsiFor(targetLanguage)` →
   - **不写映射**；
   - **调用 `DialectPreMapping.clearMapping(project, vf)`** 清掉可能已持久化在 `.idea/workspace.xml` 的坏映射（这是关键：仅「不写」不够，历史坏映射仍在，`findFile` 照样 null）；
   - `LOG.warn` 一行带 `language id` / `renderedDialectName` / `vf.path`，可 grep；
   - 继续走 `findFile`（此时用平台默认语言，preview 链已实证这条状态可用）。
3. 其余分支保持现状（映射 → 日志 → `dialectMappedVf` 记录）。

`canCreateSqlPsiFor` 从 `ui` 包移到 `execute`（或 `sql`）包并 `internal` 暴露，**两侧共用一份实现**，避免再次分叉。

### S2 findFile 失败自愈一次（不清旧账就永远失败）

`createTempPsiFile` 在 `findFile` 返回 null 时插入一次自愈：

```
refresh 后仍 null
  → clearMapping(vf)（清持久坏映射）
  → 重取一次 findFile（同一次 ReadAction）
  → 仍 null：按新阶段名 findPsiAfterHeal 失败，携带五元组
```

### S3 映射结果可观测（把静默点补齐）

- `mapDialectBeforePsi` 增加 else 分支 warn：`[M36] exec dialect mapping NOT applied (reason=...)`，reason 取 `ensureMapped` 的 false 与 §3.2 的静默点；
- `DialectPreMapping.setLanguageOnEdt` / `setMySqlOnEdt` 在 `instanceOf == null` 时补一条 warn（把「反射面整体不可用」这件事说出来）；
- 预热链的 findFile 失败日志补 language id 与 `canCreateSqlPsiFor` 结果（现在只有「findFile null (name)」）。

### S4 失败文案可归因（不再只报 findPsi）

`fail("findPsi", ...)` 的 detail 扩成五元组（单行，便于用户复制）：

```
findPsi: null (name=mpsql-exec.sql, vfValid=true, dir=false, fileType=SQL,
               mappedLang=DM, canPsi=false, path=<abs>)
```

用户侧文案保持 `execute.tempFile.failed` 不变（不新增双语键），只把 detail 变厚。

### S5 明确不改什么

- 不动 `setMapping` 的反射面（T-ConsoleBind263 已把 `DataSourcePerFileMappings` 那套适配成多版本链；`SqlDialectMappings` 这一套 263 上「日志显示成功但未逐条验证」，本单只加可观测性，不改探测策略）；
- 不动 runner 枚举与换源链（`getAttachDataSourceRunners` / `getSwitchDataSourceRunners`）；
- 不动 preview 侧闸门语义（只把实现搬成共用 + 保持行为逐字不变）；
- 不为 OS 加任何分支。

---

## 五、改造清单

### P0（本单必做）

| # | 文件 | 改动 |
|---|---|---|
| P0-1 | `ui/SqlPreviewPanelEditorLifecycle.kt` → `sql/`（或 `execute/`） | `canCreateSqlPsiFor` 迁为共用 internal，preview 调用点改引用 |
| P0-2 | `execute/DataSourceSelector.kt` `mapDialectBeforePsi` | 插入 §S1 闸门 + `clearMapping` 兜底 + else 分支 warn |
| P0-3 | `execute/DataSourceSelectorResults.kt` `createTempPsiFile` | `findFile` null → `clearMapping` → 重取一次（§S2），失败阶段名区分 `findPsi` / `findPsiAfterHeal`；detail 扩五元组（§S4） |
| P0-4 | `execute/DataSourceSelectorResults.kt` `prewarmExecTempFileInner` | 失败日志补 language id + `canCreateSqlPsiFor` 结果（§S3） |

### P1（建议同批）

| # | 内容 |
|---|---|
| P1-1 | `DialectPreMapping.setLanguageOnEdt/setMySqlOnEdt/ensureMapped` 的静默点补 warn（`instanceOf` 为 null 时点名「反射面不可用」） |
| P1-2 | 一条症状类日志汇总：`[M36] exec psi build: mappedLang=<id> canPsi=<b> healed=<b> final=<ok/null>`，每次建链一行，便于用户一次贴全 |
| P1-3 | 单测：`canCreateSqlPsiFor` 表驱动（平台内置 + 五个自注册方言 → true；无 parser 的自定义 Language → false） |

### P2（登记，不阻塞）

| # | 内容 |
|---|---|
| P2-1 | 263/262 上 `SqlDialectMappings.setMapping(VirtualFile, Any)` 的逐条字节码取证（T-ConsoleBind263 §十二 P2-6 已登记，未做） |
| P2-2 | 预热失败的「坏映射」是否会在 `workspace.xml` 里累积（多目录/多项目）——按需做清理策略 |

---

## 六、测试计划

### 6.1 新增用例（放 `ExecTempFileFixTest` 同族或新建 `ExecPsiGateTest`）

1. **G1 静态面**：`canCreateSqlPsiFor` 对 MySQL / PostgreSQL / GenericSQL / SQL92 / DM / GaussDB / HighGo / Vastbase / GoldenDB 全 true（`PlatformSqlDialectRegistrationTest` 已有同款断言，可复用），对 `Language.findLanguageByID("JAVA")` 与基语言 `SQL` 为 false。
2. **G2 行为（核心）**：自定义一个**无 ParserDefinition 的 `Language`**（测试内 `object : Language("NoParserForTest")`），经 `setMapping` 写到 exec 文件上 → 断言 `PsiManager.findFile(vf) == null`（**先在测试里把缺陷形态造出来**）→ 调 `mapDialectBeforePsi(vf, targetLanguage=NoParserForTest)` → 断言：映射被清、`findFile` 非空。
3. **G3 自愈**：同 G2 的坏映射前置，直接调 `createTempPsiFile` → 断言返回非空 pair（走 `findPsiAfterHeal` 路径），且 `lastTempFileFailure == null`。
4. **G4 反向探针可读**：失败 detail 含 `mappedLang=` / `canPsi=` / `path=` 三项。

### 6.2 反向验证（先红后绿）

- 摘掉 P0-2 的闸门（只留 `ensureMapped`） → **G2/G3 必红**，并把 `PsiManager.findFile null` 的原始形态复现出来；
- 恢复闸门 → 绿；
- 贴上两次读数（红/绿各一次），照 `docs/LESSONS.md` 的反向验证纪律。

### 6.3 真机验收（用户自做，助手只给步骤）

1. 装本次构建（内测号按发行流程另定），在 Mac 与 UOS 各开一个此前必然失败的项目；
2. 预览任一 SQL（确认结果区正常）→ 点「执行 SQL」；
3. **判据 A**：不再出现「创建临时 SQL 文件失败」；控制台/结果区正常出结果；
4. **判据 B**：`idea.log` 出现以下之一（二选一即算本单生效）：
   - `[M36] exec dialect mapping NOT applied`（说明确实踩到了坏语言，已被闸门挡下）；
   - `[M36] exec psi build: mappedLang=... canPsi=true healed=false final=ok`（说明映射本身正常，问题是别的，需回传日志）；
5. **失败时请回传**：`grep -n "M36\|T-PreviewPsiDialect\|InvalidVirtualFileAccessException\|dialect" idea.log` 的完整段（含时间戳）+ IDE Build 号（`Help → About`）；
6. 另请回传三项环境读数（定位「为什么是这批人」）：
   - `Settings → Languages & Frameworks → SQL Dialects` 里该项目/全局的默认方言；
   - 数据源类型（MySQL / DM / GaussDB / 磐维 / …）；
   - 该项目 `.idea/workspace.xml` 中是否已有 `mpsql-exec.sql` 的方言映射条目。

### 6.4 定向命令

```
./gradlew test --tests "cn.song.mybatisplus.visualizer.execute.ExecPsiGateTest" \
                --tests "cn.song.mybatisplus.visualizer.execute.ExecTempFileFixTest" \
                --tests "cn.song.mybatisplus.visualizer.database.PlatformSqlDialectRegistrationTest"
```

（纯逻辑改动可 `testFast`；只跑本单相关类，不跑全量、不 `clean`。）

---

## 七、影响面与回归风险

| 面 | 影响 | 风险 | 处置 |
|---|---|---|---|
| 执行链冷路径 | 映射前多一次 gate 判定（`LanguageParserDefinitions` 查询，微秒级） | 低 | 与 preview 同款，已在真机跑了一个月 |
| 映射语义 | 坏语言不再被写入映射，改为「保持/退回平台默认」 | 中 | 只有「不可建 PSI」这一态被改变；该态**现在必然失败**，改后至少能执行 |
| `clearMapping` 调用 | 会清掉该 vf 的历史映射（写 `workspace.xml`） | 中 | 只在 gate 拒绝或 findFile null 时调用；成功路径零调用 |
| preview 链 | 只是把 `canCreateSqlPsiFor` 换成共用引用 | 低 | 行为逐字不变，测试须保持全绿 |
| 方言正确性 | 退回默认语言后 runner 过滤可能变宽/变窄 | 低 | 现在这条路是**完全不可执行**；退化到「方言不匹配」提示优于硬失败（UI-049 已有可操作提示通道） |

---

## 八、待验证前提（先证伪再动手）

| # | 前提 | 证伪方式 | 若不成立 |
|---|---|---|---|
| V1 | 用户机器的目标语言确实「拿得到但建不出 PSI」 | 让他们贴 `idea.log` 里 `dialect`/`M36` 段；或用 §6.3 的探针构建回传 | 转 V2 |
| V2 | Mac/UOS 与 Windows 的差异来自 IDE 版本或 SQL 方言设置，而非平台契约 | 要 Build 号 + SQL Dialects 设置 | 若真是平台契约差异，需另立一单做三平台取证 |
| V3 | 用户机器上 `vf.isValid()` 为 true（排除 `FileManagerImpl` 分支 B） | 探针五元组里的 `vfValid` | **已升级为已实施**：R2/R3 实测后（本地能造出用户 null 形态的唯一路径就是 vf 失效）不再等真机读数，直接做成自愈支 1（`refreshAndFindFileByIoFile` 重定位 + 新 vf 建 PSI）；真机读数仍用于确认用户到底落在哪一支 |
| V4 | `canCreateSqlPsiFor` 判 false ⟺ `findFile` 返回 null | 沙盒里用自定义无 parser 的 Language 造形态（§6.1 G2）**必须先在测试里把 null 复现出来** | **已实测修正（见 §12.3）**：false 时形态有两种——非 `SqlLanguageDialect` → 平台在 `SqlLanguageSubstitutor` 里直接抛 CCE；方言类但缺 parser definition → null。两者对调用方等价（拿不到可用 PSI），闸门一把挡住，判据不必换 |
| V5 | `setMapping` 接受非 SQL 方言的 `Language`（构造 G2 前置态用） | 测试内实跑；不接受则给 `createTempPsiFile` 加 targetLanguage 注入缝 | 注入缝方案（纯测试面，不进生产语义） |
| V6 | `clearMapping` 在该态下真的能把语言退回平台默认 | G2 断言 `configuredLanguageId(vf) !in {NoParserForTest}` 且 findFile 非空 | 若清不掉，改「显式映射到 GenericSQL」兜底 |
| V7 | 预热链与冷路径共用同一 `mapDialectBeforePsi`，修一处即覆盖两处 | 代码走查（已确认 `:2006` 调用同函数） | 若将来分叉，需同步加闸门 |
| V8 | 263 上 `SqlDialectMappings.setMapping` 仍可用 | P2-1 的字节码取证 | 若已移除，那 263 用户的根因是另一条（映射整体不可用），本单只保证「失败可归因」 |

---

## 九、决策点

- **D1**：坏语言时是「清映射退回默认」（本单推荐，preview 已实证可用）还是「直接报错拒绝执行」（保方言正确性但用户仍然不能用）？推荐前者 + 日志点名，让用户至少能跑。
- **D2**：`canCreateSqlPsiFor` 落到哪个包（`sql/` 更中性，`execute/` 改动最小）。建议 `sql/`，两面共用。
- **D3**：P1-2 的单行汇总要不要常态化（每次执行一行）。建议要：用户反馈只需贴一行。
- **D4**：是否同批做 P2-1（263 反射面取证）。建议单独立项，避免本单扩散。

---

## 十、任务拆分

| 序 | 任务 | 产出 |
|---|---|---|
| 1 | 实现 `canCreateSqlPsiFor` 共用化 + preview 侧改引用 | 编译过 + preview 既有用例绿 |
| 2 | `mapDialectBeforePsi` 加闸门 + `clearMapping` + else warn | P0-2 |
| 3 | `createTempPsiFile` / `prewarm` 自愈与五元组 | P0-3/P0-4 |
| 4 | `DialectPreMapping` 静默点补 warn | P1-1 |
| 5 | 新增 G1~G4 用例 + 反向验证两次读数 | §六 |
| 6 | 文档回填（本计划 §十二 + CHANGELOG `[Unreleased]`） | 提交同批 |
| 7 | 真机验收（用户，Mac/UOS 各一台） | §6.3 判据 |

---

## 十一、变更记录

- 2026-11-10 14:59 立项：完成代码走查、平台源码逐行核对、同族缺陷复盘，落根因链与修法；**未动 `src/main`**，未跑测试。真机环境（Mac/UOS）本地不具备，取证项全部登记在 §八。
- 2026-11-10 15:45 实施：P0-1~P0-4 + P1-1 落地；新增 DB 集成通道用例 `ExecPsiGateTest`（7 例）并登记 `testDbExecPsiGate`；定向读数 7/0/0/0，反向验证 7 tests / 1 failed（闸门摘掉后 `expected:<SKIPPED_NO_PSI> but was:<APPLIED>`），恢复后复跑 7/0/0/0。实测把 V4 修正为「CCE 与 null 两种形态」（§12.3）。真机验收待用户。

## 十二、实施记录（2026-11-10，工作树 `mpsv-execpsigate` / 分支 `t-execpsigate`，基于 master `2c23085e`）

### 12.1 落地内容

| 项 | 文件 | 改动 |
|---|---|---|
| P0-1 | `sql/SqlPsiCapability.kt`（新增）+ `ui/SqlPreviewPanelEditorLifecycle.kt` | `canCreateSqlPsiFor` 从 ui 私有实现提为 `sql` 包共用（判据 KDoc 一并搬入），preview 侧改成 import 引用，行为逐字不变 |
| P0-2 | `execute/DataSourceSelector.kt` | `mapDialectBeforePsi` 拆成「解析 + `applyExecDialectMapping`」；新增三态结果 `ExecMappingOutcome{APPLIED / SKIPPED_NO_PSI / NOT_APPLIED}`；闸门分支 = 不写映射 + 清历史映射 + warn；补上原来缺失的 else 分支 warn |
| P0-3 | `execute/DataSourceSelectorResults.kt` | 新增 `findExecPsi` / `healExecPsi` / `execPsiDiagnostics`；冷路径 findFile 失败 → 清映射重建一次 → 仍失败则 `fail("findPsiAfterHeal", 五元组)` |
| P0-4 | 同上 | 预热链同款自愈；findFile null 的日志从「只有文件名」改为五元组 |
| P1-1 | `execute/DialectPreMapping.kt` | `setMySqlOnEdt` / `setLanguageOnEdt` / `clearMapping` 的「反射面不可用」由静默 `return false` 改 warn；`ensureMapped` 的探测失败补一行 info |
| 用例 | `src/test/.../execute/ExecPsiGateTest.kt`（新增）+ `buildSrc/.../mpv-testing.gradle.kts` | 7 例覆盖方言可建 PSI 基线 / 非 SQL 语言 / 闸门拒绝与放行 / 清映射自愈 / 五元组诊断；登记 `testDbExecPsiGate` 任务并与 `dbIntegrationClasses` 同步（分片侧唯一排除口径） |

### 12.2 测试读数（`./gradlew testDbExecPsiGate`）

| 轮次 | 读数 |
|---|---|
| 常规 | `<testsuite tests="7" skipped="0" failures="0" errors="0" time="6.032">` |
| 反向（临时把闸门条件改成 `false && …`，代码内标 `RED PROBE`） | `7 tests completed, 1 failed`；`testGateSkipsMappingForNonPsiLanguage` → `AssertionFailedError: 目标语言建不出 PSI 时，闸门必须拒绝映射（APPLIED = 没拦住）expected:<SKIPPED_NO_PSI> but was:<APPLIED>` |
| 恢复后复跑 | `<testsuite tests="7" skipped="0" failures="0" errors="0" time="5.767">` |
| 并入 master 后（merge `0094738b`）+ 补第 8 例（vf 失效分支复现） | `<testsuite tests="8" skipped="0" failures="0" errors="0" time="27.537">`；同轮打印 `[ExecPsiGate] invalid-vf diagnostics=name=…, vfValid=false, dir=false, fileType=SQL, mappedLang=null, canPsi=false, path=…` |

### 12.3.0 复现边界（用户问「有没有复现用户的问题」，如实记）

| 项 | 结论 |
|---|---|
| 用户那一形态（Mac / UOS 上 `findPsi: PsiManager.findFile null`） | **未复现**。本机 Windows、用户的 Mac/UOS 项目与 IDE 版本都拿不到，且用户内网无 `idea.log`（用户 2026-11-10 明确） |
| null 分支本身 | **已复现**：`testFindFileNullFromInvalidVf` 用「refresh 后 vf 失效」把 `findFile` 打成 null，五元组报出 `vfValid=false`（区分力实证） |
| 坏映射打断 PSI 的分支 | **已复现**：映射到非 `SqlLanguageDialect` 语言时 `findFile` 抛 CCE（栈见 §12.3） |
| 因此 | 本单修的是「这一类失败机制」而不是「已证实的那一台机器的原因」。用户侧真正落在哪条分支，靠新版本的**五元组文案**一次判型（`vfValid` / `canPsi` 两项直接分开两条分支）；修复对 `canPsi=false` 与映射失败的路径立刻生效，对 `vfValid=false` 的路径只提供定位信息（V3 另立项） |
| **R2 并发外部写探针（2026-11-10，Windows）** | `testConcurrentExternalWriteRaceProbe`：外部绕过 VFS 写 **344** 次 + 每次并发 `refreshAndFindFileByIoFile`，主线程同步 **369** 次 `findFile` → **null=0 / ex=0 / vf 全程有效**。结论：本地盘上「外部写 + 并发 refresh」**打不出 vf 失效**，平台对内容分叉是自愈的（内容更新、vf 有效、PSI 重建）⇒ 用户那条 null 更可能在**映射侧**（`canPsi=false`），或在 Unix watcher / 网络盘、同步盘这类 VFS 行为不同的环境（本机无 ≥16GB Linux 机器可验，`北京-16G` 已连不上、其余在役机仅 3GB 内存） |
| R2 之后的落点 | 该探针已转为正式守卫：断言「**只要出现 null，五元组必须报 `vfValid=false`**」（不允许出现没有分支名的 null）。若将来在别的平台打出 `null 且 vfValid=true`，说明还有第三条来源，用例会先红 |
| **R3 重复取 PSI 探针（2026-11-10）** | 坏映射下连取 5 次 → `[CCE] sequence=[THROW:ClassCastException ×5]`，**没有一次退化成 null**。否掉「预热先撞 CCE → 冷路径再取即 null」这条平台缓存假说 ⇒ 用户文案里的**纯 null 不能归到映射侧**，只能是 `vfValid=false`（vf 失效）或第三条来源 |
| 批次结论（R2+R3） | 本地（Windows / 本地盘）能造出用户 null 形态的唯一路径是**显式让 vf 失效**；并发外部写与坏映射都造不出来。故用户的触发更可能在「VFS 行为不同的环境」（Unix watcher / 网络盘 / 同步盘）或「IDE 版本差异」，两者都需要真机五元组才能定案——本单不宣称已定位 |

`RED PROBE` 标记已确认清零（`grep -c "RED PROBE"` = 0），工作树无残留探针。

### 12.3 实测修正 §八 V4：null 之外还有 CCE 形态

把 `.sql` 文件映射到**非** `SqlLanguageDialect` 的语言（测试用自定义 `Language("NoParserForTest")`）后，
`findFile` 抛的是 **ClassCastException**，栈为：

```
SqlLanguageSubstitutor.getSqlLanguageFromMapping(SqlLanguageSubstitutor.java:98)
  ← getSqlLanguageFromFile(:86) ← getConfiguredSqlLanguageDialect(:66) ← getSqlLanguage(:40)
  ← LanguageSubstitutorAdapter ← LanguageSubstitutors.substituteLanguage(:52)
  ← LanguageUtil.getLanguageForPsi(:56) ← FileManagerImpl.createFileViewProvider(:286)
  ← findViewProvider(:216) ← findFile(:383) ← PsiManagerImpl.findFile(:163)
```

两条结论：
1. **映射就是 PSI 语言的来源**——平台栈逐级印证 §2.3 的推断，坏映射直接打断 PSI 创建；
2. `canCreateSqlPsiFor=false` 有**两种**可观测形态：CCE（映射到非方言语言）与 null（方言类但缺 parser
   definition，T-PreviewPsiDialect 真机两种都出过）。对调用方观测一致（拿不到可用 PSI），所以闸门与自愈
   都不需要按形态分叉；用户报的 null 形态仍以真机日志为最终判据（V1/V3 未关闭）。

### 12.4 R2/R3 实验与 V3 升格（2026-11-10，同一工作日第二轮）

用户问「有没有复现用户的问题」→ 补两个实验，结论都不利于「随便试一下就能复现」：

| 实验 | 做法 | 读数 | 结论 |
|---|---|---|---|
| R2 并发外部写 | 外部线程绕过 VFS 写 304~344 次 + 每次并发 `refreshAndFindFileByIoFile`，主线程同步 `findFile` | `ok=369/506 null=0 ex=0 nullWithValidVf=0`（三轮） | 本地盘上「外部写 + 并发 refresh」**打不出 vf 失效**，平台对内容分叉自愈 |
| R3 坏映射重复取 | 坏映射下连取 5 次 `findFile` | `[THROW:ClassCastException ×5]` | 否掉「先 CCE 后 null」的平台缓存假说——纯 null **不能**归到映射侧 |

两条合起来把「本地能造出用户 null 形态」的路径收窄到**只剩 vf 失效**，于是 V3 从「另立项」升为**本单自愈支 1**（`healExecPsi` 先判 `vf.isValid`，失效则 `refreshAndFindFileByIoFile` 重定位、用新 vf 建 PSI，调用方以 `psi.virtualFile` 取回那个 vf）。四条链路（冷路径 / 预热 / 缓存写点 / 后续 attach）都用同一个 vf。

反向验证：临时把该分支条件改成 `false && …`（代码内标 `RED PROBE`）→ `10 tests completed, 1 failed`，`testHealRefindsVfWhenInvalid` 红在「文件在盘上、vf 失效时必须靠重定位拿回 PSI」；恢复后复跑全绿，`grep -c "RED PROBE"` = 0。

### 12.5 未做 / 留待

- **P2-1 未做**：263 上 `SqlDialectMappings.setMapping(VirtualFile, Any)` 的逐条字节码取证（T-ConsoleBind263 §十二 P2-6 已在册）。
- **真机验收未做**：Mac / UOS 各一台，按 §6.3 判据；用户为内网环境，`idea.log` 只能靠 §S4 的五元组从失败弹窗文案里取。**Linux 侧本轮无法验**：`北京-16G` 连不上，其余在役机仅 3GB 内存（百度云 2 核 / 香港-4G 4 核，均无 Xvfb），跑不动平台测试沙盒，更别说 Linux IDE 沙盒。
- 未跑全量门禁（按纪律由收口轮统一跑）；本次改动了 `buildSrc` 的测试桶登记，收口轮需确认分片 bucket 汇总行 `db=` 计数 +1。

---

## 参考

- `execute/DataSourceSelectorResults.kt:1758-1848`（`createTempPsiFile`）、`:1932-2021`（prewarm）、`:416`（文案装配）
- `execute/DataSourceSelector.kt:2137-2165`（`mapDialectBeforePsi`）、`:1717-1718`（dumb mode 下 push-all 推迟注记）
- `execute/DialectPreMapping.kt:48/201-217/238-278`（映射编排与静默点）
- `execute/ExecDialectRouting.kt:56-90/213-219`（方言名 → 平台 language id 表）
- `ui/SqlPreviewPanelEditorLifecycle.kt:401/460/515`（preview 闸门与 `canCreateSqlPsiFor`）
- `sql/SqlFamilyParserDefinitions.kt:19-40`、`resources/META-INF/database-optional.xml:63-76`（自注册方言的 parser definition 注册面）
- `resources/messages/MpSqlVisualizerBundle_zh.properties:551`（文案键）
- 平台源码：`ideaIU-2024.3.7.1-sources.jar` → `com/intellij/psi/impl/file/impl/FileManagerImpl.java`、`com/intellij/psi/AbstractFileViewProvider.java`、`com/intellij/lang/LanguageUtil.java`
- 同族复盘：`docs/开发计划/20261030-1540-T-PreviewPsiDialect-...-开发计划.md`、`docs/开发计划/20261107-1645-T-ConsoleBind263-...-开发计划.md`
