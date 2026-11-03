# T-PreviewDocPersistLock：预览渲染落盘落在写锁外，EDT 硬抛 `Access is allowed from write thread only`

> 2026-11-02 立项。来源＝用户贴来的 idea.log 双栈（2026.2.1，IU-262）：
> `RuntimeExceptionWithAttachments: Access is allowed from write thread only`，两个入口
> （参数面板勾选 `SqlPreviewPanelParamPanel.kt:936`、执行页「返回预览」`SqlPreviewPanelExecution.kt:1654`）
> 都收敛到同一帧 `SqlPreviewPanelSqlView.kt:705 → FileDocumentManagerImpl.saveDocument`。
> 用户当前跑的代码为 master `3ac007d7`（v2026.11.2 发行提交），行号与栈逐帧吻合。

## 一、结论（修没修、谁坏）

| 项 | 判定 | 依据 |
|---|---|---|
| 最新代码有没有修 | **没有**。master `3ac007d7`、dev `f261e25a`、`feature/kotlin-to-java` 三条线全部保留该写法 | 三棵树 `grep -n saveDocument`：`SqlPreviewPanelSqlView.kt:705` / `SqlPreviewPanelSqlViewKt.java:744` |
| 缺陷性质 | **插件缺陷，P1**｜EDT 上在写意图（write-intent）之外调用 `FileDocumentManager.saveDocument` | 平台 243 源码实证 `saveDocument` 首行即断言写意图；本仓其余 5 处 `saveDocument` 全在写动作内，唯独此处是孤例 |
| 触发条件 | 控制台已挂到预览面板（`nativeDoc != null`）＋ 渲染文本与文档不同 ＋ 临时执行文件已在 VFS 中有对应 `VirtualFile` | 三个条件在「参数面板勾选 / 切显示模式 / 返回预览 / 刷新」这些日常操作里全都成立 |
| 版本面 | **不是 262 专属**：243（源码实证）与 262（用户栈实证）都断言；261 按同源推断未实测 | 243 发行源 `FileDocumentManagerImpl:323-336` → `TransactionGuardImpl:135-146` → `ApplicationImpl:946-948` → `ThreadingAssertions:173-180` 一链四跳，无版本开关 |
| 为什么现在才炸 | 只报在 262，但与 262 的读锁严格化**无因果关系**；更可能是上游执行链 P0（T-Exec262ReadLock）修好后，用户第一次顺畅走完「勾选参数→返回预览」这条会触达 705 行的路径 | 推测，见 P-1；不作为根因 |

用户可见后果不止弹一个异常框：

1. `nativeDoc.setText` 在 685 行已执行，SQL 确实换了新文本，所以界面上「看起来正常」，只是随后弹红；
2. 异常从 `showSql` 往外抛，`renderSql` 尾部两条刷新（`syncToggleSelectLabels()` / `refreshBindStateRow()`，`SqlPreviewPanelSqlView.kt:265/267`）被跳过——勾选按钮文案与绑定数据源指示行停在旧态；
3. `lastRenderedSql`（`:713`）没更新，`SqlAutoFolding.applyTo`（`:715-719`）没执行——迟到产物换装判定与自动折叠同时失效；
4. 落盘始终失败 → 磁盘上的 mpsql-exec 文件长期落后于控制台文档；执行链执行前会同步文档内容，所以**执行结果不受影响**，但外部读盘（另一实例、VCS 差异）看到的是旧内容；
5. 每次渲染都重试、每次都抛，IDE 侧是重复的 unhandled exception。

## 二、根因（代码级）

### 2.1 现场代码

`src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelSqlView.kt:684-707`（master）：

```kotlin
ExecDocAutosaveGate.suppressDuring {
    WriteCommandAction.runWriteCommandAction(project) { nativeDoc.setText(text) }   // 685：写意图在此获得，随即释放
    val fdm = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
    val vf = fdm.getFile(nativeDoc)
    if (vf != null && fdm.isDocumentUnsaved(nativeDoc)) {
        fdm.saveDocument(nativeDoc)      // 705：写意图已释放，且在 EDT → 断言硬抛
    }
}
```

`WriteCommandAction.runWriteCommandAction` 是**一次性**写动作：lambda 返回时写意图释放。705 行因此运行在「EDT ＋ 无写意图」的帧里，正是平台断言要拦的形态。

### 2.2 平台契约（243 发行源逐行实证，262 同形）

243（编译基准 `2024.3.7.1`，`lib/` 源码 jar）：

```
FileDocumentManagerImpl.java:323-336
  saveDocument(doc) → saveDocument(doc, true)
  ├─ ThreadingAssertions.assertEventDispatchThread()
  └─ TransactionGuardImpl.assertWriteActionAllowed()      TransactionGuardImpl.java:135-146
       └─ ApplicationManager.getApplication().assertWriteIntentLockAcquired()
            ApplicationImpl.java:946-948 → ThreadingAssertions.assertWriteIntentReadAccess()
              ThreadingAssertions.java:173-180
                if (!isWriteIntentLockAcquired()) throwWriteIntentReadAccess()   ← 抛
```

262 的栈（用户现场）是同一链的改名版：`saveDocument(...:344/348)` → `TransactionGuardImpl.assertWriteSafeEnvironment(:142)` → `ApplicationImpl.assertWriteIntentLockAcquired(:1259)` → `ThreadingAssertions.throwWriteIntentReadAccess(:230)`。名字换了，语义没变；**243 上同样会抛**，只是 243 时代码路径没被人踩到，或者被 `runCatching` 吞了。

### 2.3 为什么只有这一处

全仓 `saveDocument(` 调用点（`src/main`）共 5 处，其余四处都在写动作内：

| 站点 | 写动作来源 |
|---|---|
| `execute/DataSourceSelector.kt:1804`（`writeVfTextViaDocument`） | 外层 `WriteAction.compute<Unit, Throwable>`（`:1772`） |
| `execute/DataSourceSelector.kt:2051`（`autosaveExecDocNow`） | 自身包 `WriteAction.compute<Unit, Throwable>`（`:2050`） |
| `execute/DataSourceSelectorResults.kt:808` | 外层 `WriteCommandAction.runWriteCommandAction`（`:796`） |
| `execute/DataSourceSelectorConsole.kt:112` | 经 `writeVfTextViaDocument`，同第一行 |
| `log/SqlSourceInterceptorAutoInjector.kt:409` | 外层 `runWriteCommandAction` |
| **`ui/SqlPreviewPanelSqlView.kt:705`** | **无——本券对象** |

引入提交 `210ccbb28`（2026-10-10，T-PS4-12 定案①「同临界区落盘」）。原始意图是对的：`setText` 与 `saveDocument` 要在同一个临界区，避免「已修改未保存」窗口。落地时把 `setText` 放进了写动作、`saveDocument` 留在了写动作外——**恰好把要消除的那个窗口变成了永久窗口**，因为落盘一次都没成功过。

### 2.4 为什么现有测试全绿（关键盲区）

`SqlPreviewPanelDocLifecycleTest.testShowSqlNativeDocBranchPersistsAndClearsUnsavedFlag` 正是钉这条落盘的用例，并且断言了「写入后不滞留 modified」与「磁盘与文档一致」。它在 2026-11-02 10:10 的发行门禁里是 **3/0/0 绿**（`build/test-results/testShard5/TEST-cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelDocLifecycleTest.xml`）。原因不在用例写得不对，而在**平台测试框架替被测代码兜了底**：

```
UsefulTestCase.java:515      EdtTestUtil.runInEdtAndWait(() -> defaultRunBare(wrappedRunnable))
EdtTestUtil.java:45-58       runInEdtAndWait(runnable, writeIntent = true)   ← 默认 true
                             → app.runWriteIntentReadAction { runnable.run() }
```

也就是说，`BasePlatformTestCase` 的**整个测试体（setUp + 测试方法 + tearDown）都跑在一条 `WriteIntentReadAction` 里**，写意图全程持有。于是「EDT 上没有写意图」这个条件在平台单测里根本构造不出来，`saveDocument` 的断言永远通过——这是本缺陷能带着全绿测试上线一个月的直接原因，也是仓库里已记录过的「假绿」家族的又一形态。

推论：**只加一个普通 `BasePlatformTestCase` 用例复现不了本缺陷**，必须显式剥掉写意图（见 §五）。

## 三、修复方案

### 3.1 主修复：落盘移进同一个写命令块（推荐）

`SqlPreviewPanelSqlView.kt:684-707` 改为：

```kotlin
ExecDocAutosaveGate.suppressDuring {
    // T-PreviewDocPersistLock：setText 与 saveDocument 必须在同一个写动作内——
    // FileDocumentManager.saveDocument 首行断言写意图（243 FileDocumentManagerImpl:323-336
    // → TransactionGuardImpl.assertWriteActionAllowed → ThreadingAssertions），
    // 写命令返回后写意图即释放，落盘留在块外会在 EDT 上硬抛
    // 「Access is allowed from write thread only」（262 / 243 同形）。
    WriteCommandAction.runWriteCommandAction(project) {
        nativeDoc.setText(text)
        val fdm = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
        val vf = fdm.getFile(nativeDoc)
        if (vf != null && fdm.isDocumentUnsaved(nativeDoc)) {
            fdm.saveDocument(nativeDoc)
        }
    }
}
```

选这个形态的理由：

- **语义最贴原意**：T-PS4-12 要的就是「同一个临界区」，挪进去以后窗口真的为零，不再是「名义上同临界区、实际永久未落盘」；
- **与仓内既有生产形态一致**：`DataSourceSelectorResults.kt:796-811` 就是「`runWriteCommandAction` 里 setText → commitDocument → saveDocument」，在跑了一年多没出问题；
- 改动面最小：一段 6 行，不动 `ExecDocAutosaveGate`、不动调用方、不动 `lastRenderedSql` 之后的逻辑。

备选（不推荐，仅记录）：保留原结构、把 `saveDocument` 两行单独包 `WriteAction.compute<Unit, Throwable> { … }`（照 `autosaveExecDocNow` 手法）。能修好崩溃，但 `setText` 结束到落盘之间又出现了一个异步窗口，与 T-PS4-12 定案相反。

### 3.2 顺带确认（不改，只记录）

- **editor 支链**（`SqlPreviewPanelSqlView.kt:725-732`）：只 `setText` 不落盘，不存在本崩溃；是否也该补落盘属另一件事（那条支链的文档是自建编辑器文档，落盘语义由 F5 防抖兜底），本券不动。
- **`PsiDocumentManager.commitDocument` 要不要加**：`writeVfTextViaDocument` 与 `DataSourceSelectorResults` 在落盘前都 commit 过。本路径的 native doc 挂在执行临时文件上，写命令结束平台自身会 commit；若 P-3 验证时发现 `saveDocument` 之前仍有未提交 PSI 影响，再按那两处的顺序补 `commitDocument`，不要预先加。

### 3.3 可选加固：源码扫描守卫（优先级低，可拆券）

运行时回归用例（§五）是真正的闸门；文本守卫只是把「下一次有人把 `saveDocument` 写到写动作外」提前拦在编译期之外。若要加，形态建议：

- 扫描 `src/main` 里所有 `saveDocument(` 站点，要求所在**函数体内**同时出现 `WriteAction.` 或 `runWriteCommandAction`，并且该站点位于这些写动作块的**花括号深度内**（简单的括号计数即可，`SyncRefreshWriteIntentGuardTest` 已验证同类做法的可行性与漏报边界）；
- 反向验证必做：把 `saveDocument` 手动挪回写块外 → 必须变红。

**风险提示**：本仓库已有 21 个 convention 守卫，新增一条要连带 `GuardSelfTestCoverageMetaGuardTest` 的元守卫登记。若实施时发现括号计数为覆盖这 6 处站点需要一堆特判，直接放弃守卫、只留运行时用例——这不影响本券验收。

## 四、待验证前提（实施者先证伪再动手）

- **P-1（归因，非修复前提）**：本缺陷是否自 2026-10-10 起就在线上反复触发？检验方式＝查用户/测试机 `idea.log` 在 v2026.10.29~11.2 之间是否已有 `write thread only` 记录。若是，则「269 行全绿 3 周」本身就是测试盲区的证据，发行说明该写「长期存在的异常弹窗」而不是「新引入」；若否，则说明触达条件（控制台挂载 + 文档变脏）近期才被普遍满足，需在发行说明里写清前置。**结论只影响文案，不影响修法。**
- **P-2**：`runInEdtAndWait(writeIntent = false)` 能在 243 测试框架里构造出「EDT 无写意图」帧，并且**当前代码在该帧下确实抛**（先用反向用例证明测试造的形态能红，再修）。243 源码 `EdtTestUtil.java:45-58` 已证 `writeIntent=false` 时不包 `runWriteIntentReadAction`，但「平台测试线程本身就在 EDT」这一分支（`:48-59`）要实跑确认。
- **P-3**：修复后，同一帧下 `showSql` 的三条支链（nativeDoc / editor / createEditor）都不再有别的平台断言。`createEditor` → `assemblePreviewEditor` 会建编辑器、可能写文档；若新用例带出第二处断言，按本券口径记录、拆下一券，不要顺手扩大改动面。
- **P-4**：`saveDocument` 移进写命令块后，`CommandProcessor` 不报「在命令内落盘」相关问题；`FileDocumentManagerListener.beforeDocumentSaving` 的回调里不会反向触发面板重渲染（闸门 `ExecDocAutosaveGate` 在整个块外层开着，理论上覆盖）。
- **P-5**：`project` 为 null 的路径（`SqlPreviewPanel.project: Project?`，`:143`）在 `runWriteCommandAction(null)` 下行为不变——现状本来就传 nullable，只是把落盘挪进了同一个 lambda，无新增风险，跑一次对应用例即可。
- **P-6**：`feature/kotlin-to-java` 端口分支同一处（`SqlPreviewPanelSqlViewKt.java:744`）写法逐字相同，修法可逐字翻译；该分支的 `WriteCommandAction.runWriteCommandAction(project, runnable)` 两参重载在 243 编译目标下可用（现状已在用）。
- **P-7**：261（2026.1.5.1）上 `FileDocumentManagerImpl.saveDocument` 是否同样断言——本轮只证了 243（编译基准源码）与 262（用户栈），261 是按同源推断。真机/反编译任一方式补一次即可，**不影响修法**（修法本来就不做版本分叉）。

## 五、测试

### 5.1 主回归用例（本券的核心产出）

放 `SqlPreviewPanelDocLifecycleTest`（已有 nativeDoc 夹具，`writeAndRefresh` + `markNativeDocForTest` 直接复用），新增：

```
testShowSqlNativeDocPersistWorksWithoutWriteIntent
```

要点：**必须把渲染体放进 `writeIntent = false` 的 EDT 帧**，否则用例恒绿、等于没写：

```kotlin
com.intellij.testFramework.runInEdtAndWait(writeIntent = false) {
    panel.renderSqlForTest()
}
```

断言三件：

1. 不抛异常（当前代码在此必抛 `RuntimeExceptionWithAttachments: Access is allowed from write thread only`）；
2. `fdm.isDocumentUnsaved(consoleDoc) == false`；
3. 磁盘内容 == `consoleDoc.text`（行尾按 LF 归一，照既有用例）。

**反向验证（AGENTS.md 铁律）**：先落用例、不动生产代码 → 必红，贴「未修复红」读数；修复后 → 绿，贴「修复后绿」读数。两次读数都要进实施记录，不接受只贴绿。

### 5.2 支链扫雷用例（可选，视 P-3 结果）

同文件加 `testShowSqlEditorBranchWorksWithoutWriteIntent`（`editor` 支链）与 `testShowSqlCreateEditorBranchWorksWithoutWriteIntent`（`createEditor` 支链），同样 `writeIntent = false`。若跑出第二处断言，按 P-3 拆券，不在本券里扩改。

### 5.3 定向清单（实施代理只跑这些）

```
--tests "cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelDocLifecycleTest"
--tests "cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelTest"
--tests "cn.song.mybatisplus.visualizer.execute.ExecDoc*Test"
--tests "cn.song.mybatisplus.visualizer.execute.DocWriteContextTest"
```

不跑全量、不 clean、不 `--stop`；全量由收口代理单派。本改动不触 `build.gradle.kts`、`plugin.xml` EP、不引入新平台 API 面（`WriteCommandAction` 已在用），**不触发构建校验档**。

## 六、临时缓解（用户现在就能用）

- 异常只在「预览面板挂了 DBN 控制台」（即点过「执行 SQL」或控制台已嵌入）时出现；纯预览/复制 SQL/执行计划不受影响。
- 把控制台从预览面板收起（或在执行页点回预览后不勾选参数）可暂时不触发；异常本身不影响 SQL 展示与执行结果，只是红框 + 同步刷新滞后。
- 不想看到弹框：设置 → 通知 → 关掉该插件错误提示属治标，不推荐（会连带屏蔽真实错误）。

## 七、影响面与发行

- **用户可见**：262/261/243 上凡是「控制台已挂载 + 勾选参数/切模式/返回预览/刷新」都会弹 `Access is allowed from write thread only`，且每次渲染都弹；文档落盘长期失败。
- **发行口径**：CHANGELOG「修复」段一条，措辞用用户视角——「预览面板在控制台已打开时切换参数或返回预览会弹“Access is allowed from write thread only”异常，且预览 SQL 无法自动写回执行文件」。按 P-1 结论补「该异常自 v2026.10.x 起存在」或不写。
- **文档站**：无需改（属内部实现缺陷，无行为约定变化）。
- 不动 `update/update.json`（用户本人改）。

## 八、实施安排

- **分支**：扁平名 `t-preview-doc-persist-lock`，基于 master 切（缺陷在已发布版本上）；是否直接合回 master 由用户拍板。
- **顺序**：① 落反向用例（红）→ ② 改 6 行（绿）→ ③ 反向再验一次（临时撤回改动 → 红）→ ④ 定向四类绿 → ⑤ 交收口。每完成一件可提交的立即提交（提交信息中文、`-F` 文件写入）。
- **并行约束**：跑 gradle 前 `python C:\Users\USER\.zcode\cli\parallel_tasks.py check`；立项时点（2026-11-02 11:25）登记表里有活跃任务 `release-20261102`（sandbox `master-default(release-1102)`，预计 11:55 结束），**沙盒与构建槽位冲突时先等它注销或用独立 sandboxContainer**，不要抢跑。
- **端口分支**：`feature/kotlin-to-java` 同形缺陷按 k2j 惯例在同步轮次里一并翻译（P-6），本券不单独占端口分支。
- **收口三档**：实施代理定向绿 → 只读收口代理单派 `testShards` 权威全量（主控并行写文档、不碰 `src/main`）→ 本券不触发构建校验档。
- **回写**：实施记录写回工作树副本，主仓副本只读。

## 九、证据清单

- 用户栈：两条，入口分别 `SqlPreviewPanelParamPanel.kt:936`（条件勾选）与 `SqlPreviewPanelExecution.kt:1654`（`backToPreview`），共同帧 `SqlPreviewPanelSqlView.kt:705 → ExecDocAutosaveGate.kt:28 → showSql:684`
- 插件源码（master `3ac007d7`）：`ui/SqlPreviewPanelSqlView.kt:684-707`（缺陷本体）、`:713-719`（被跳过的后续）、`:265/267`（被跳过的尾部刷新）、`:725-732`（editor 支链）、`ui/SqlPreviewPanel.kt:143`（`project: Project?`）、`execute/ExecDocAutosaveGate.kt:25-32`（闸门）
- 仓内对照站点：`execute/DataSourceSelector.kt:1772/1804/2050/2051`、`execute/DataSourceSelectorResults.kt:796-811`、`execute/DataSourceSelectorConsole.kt:110-114`、`log/SqlSourceInterceptorAutoInjector.kt:405-410`
- 平台基准（243 源码 jar，`F:\gradle-home\caches\...\ideaIU\243.28141.41\...-sources.jar`）：`FileDocumentManagerImpl.java:323-336`、`TransactionGuardImpl.java:135-146`、`ApplicationImpl.java:946-948`、`ThreadingAssertions.java:173-180`
- 测试盲区证据：`UsefulTestCase.java:515`、`EdtTestUtil.java:39-58`、`EdtTestUtil.kt:30-42`（`runInEdtAndWait(writeIntent: Boolean, …)`）；`build/test-results/testShard5/TEST-cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelDocLifecycleTest.xml`（3/0/0，2026-11-02 10:10）
- 引入提交：`210ccbb28`（2026-10-10，T-PS4-12 定案①），`git tag --contains` 命中 2026.10.29 起全部发行 tag
- 三树现状：master `3ac007d7`、dev `f261e25a`、`feature/kotlin-to-java`（`SqlPreviewPanelSqlViewKt.java:744`）——均未修

## 十、实施记录（2026-11-02）

按用户令「把这个 bug 修复掉再发版」，直接在 master 实施（未切 §八 原定的 `t-preview-doc-persist-lock`），
改动并入同日 v2026.11.2 发行。

### 10.1 计划前提证伪（两条，均影响 §五 用例设计）

| 前提 | 结论 | 证据 |
|---|---|---|
| P-2「`runInEdtAndWait(writeIntent = false)` 能构造出 EDT 无写意图帧」 | **不成立** | `UsefulTestCase.java:515` 用 `runInEdtAndWait(runnable)`（默认 `writeIntent=true`）包住**整个测试体**；`EdtTestUtil.java` 的 `isDispatchThread` 分支在 `writeIntent=false` 时只是直接 `runnable.run()`，外层写意图仍由内层 lambda 持有——嵌套调用剥不掉 |
| 「改判 `isWriteAccessAllowed` 可判别」 | **不成立（假红）** | 未修复代码下该值仍恒 true（框架写意图帧内），首版用例跑出 **4 tests / 0 failures** ——红灯不红＝断言无判别力 |
| §五 原用 `beforeDocumentSaving` 观察落盘 | **不判别** | 它在 `FileDocumentManagerImpl.lambda$doSaveDocumentInWriteAction$3` 内部触发，栈里恒有平台自己的写动作帧；实测未修复时代码也通过 |

### 10.2 最终判据（可判别的不变式）

监听 `FileDocumentManagerListener.beforeAnyDocumentSaving`——它在 `saveDocument` 的断言之后、
`doSaveDocument` 之前触发，栈里只有**调用方**的真实帧。断言该回调栈中必须出现
`WriteCommandAction.*` / `WriteAction.*` / `WriteCommandActionHelper*` 帧。

### 10.3 读数

- **未修复（红线）**：`SqlPreviewPanelDocLifecycleTest` **4 tests / 1 failed / 0 errors / 0 skipped**
  （BUILD FAILED，1m 内），保存栈为
  `…beforeAnyDocumentSaving ← FileDocumentManagerImpl.saveDocument ← ExecDocAutosaveGate.suppressDuring ← showSql`
  ——无写命令帧，正是用户真机崩溃的形态
- **修复后（绿）**：同一类 **4 / 0 / 0 / 0**（BUILD SUCCESSFUL in 56s），栈中 `beforeAnyDocumentSaving`
  之上多出 `WriteCommandAction` 帧
- 反向验证口径说明：红线读数就是在**未经修改的生产代码**上取的（最强形态），修复后回绿；本券不再做
  「修好再撤」的第二次反向（同一份代码的两次读数已在档）
- **定向四类**（计划 §5.3，一条 `./gradlew test --tests …` 串跑，1m19s）：**74 / 0 / 0 / 0** ——
  `SqlPreviewPanelDocLifecycleTest` 4、`SqlPreviewPanelTest` 31、`ExecDoc*Test` 30
  （ExecDocSync 14 / ExecDocSync2 8 / ExecDocAutosaveHost 4 / ExecDocPrewarmDisposeGuard 4）、
  `DocWriteContextTest` 9

### 10.4 改动

| 文件 | 内容 |
|---|---|
| `ui/SqlPreviewPanelSqlView.kt` | nativeDoc 分支：`saveDocument` 移进 `runWriteCommandAction` 块内（`setText` 与落盘同临界区） |
| `ui/SqlPreviewPanelDocLifecycleTest.kt` | 新增 `testShowSqlNativeDocPersistHappensInsideWriteAction`（`beforeAnyDocumentSaving` 栈判据 + `sawSave` 前置防假绿） |

### 10.5 未做与理由

- §3.3 源码扫描守卫**未采纳**：`beforeAnyDocumentSaving` 运行时判据已具备真实灵敏度（红→绿可复现），
  再加一条 convention 守卫要连带 `GuardSelfTestCoverageMetaGuardTest` 元守卫登记与自证语料，
  收益不抵成本；将来若出现「有人又把落盘挪到块外」的复发，运行时用例会先红
- §5.2 支链扫雷用例（editor / createEditor 支链）：`grep` 确认这两条支链只 `setText` 不落盘，不存在本崩溃形态，
  未加
- `feature/kotlin-to-java` 端口分支（`SqlPreviewPanelSqlViewKt.java:744`）：按 k2j 惯例留到同步轮次一并翻译

