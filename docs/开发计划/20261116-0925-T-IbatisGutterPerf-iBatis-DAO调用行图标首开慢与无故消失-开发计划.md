# T-IbatisGutterPerf：iBatis DAO 调用行两枚 gutter 图标首开慢与「无故消失」

- 立项：2026-11-16 09:25
- 任务源：用户 2026-11-16 真机反馈（IDEA 2026.3，demo 根工程 `mybatis-plus-sql-visualizer-demo`）
- 反馈原话要点：「UserDaoImpl 的预览图标和跳转图标加载的很慢，可能存在性能问题」「图标一开始显示过，后面也无故不显示了」
- 观察文件：`ibatis2-demo/src/main/java/cn/song/ibatis2demo/UserDaoImpl.java`（135 行，21 个 `SqlMapExecutor` 调用点，全部 `ns.id` 形态：`client.queryForList("User.selectAll")`）

## 一、问题界定

两个症状，指向**同一条解析链**：

| # | 症状 | 涉及图标 | 产出者 |
|---|---|---|---|
| S1 | 打开 `UserDaoImpl.java` 后图标要等很久才出现 | 预览图标（`sqlGutter.svg`，锚首参字面量）+ 跳转箭头（`MpvsIcons.MapperMethod`，锚方法名 identifier） | 同一 provider `IbatisJavaToXmlLineMarkerProvider` |
| S2 | 图标出现过一次，之后不再回来（不滚动、不编辑就一直是空的） | 同上，两枚**同生共死** | 同上 + 解析链失败语义 |

两枚图标由同一个 `createMarkers` 一次产出（`src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/IbatisJavaToXmlLineMarkerProvider.kt:96-140`），门槛是同一句 `anchors.isEmpty()` 判定，所以「同慢、同灭」是设计必然，不是两个 bug。

顺带一个未报告但同源的第三症状：

| # | 症状 | 链路 |
|---|---|---|
| S3 | 在 `UserDaoImpl.java` 上 Ctrl+B 跳转也会卡一下（EDT） | `IbatisDaoStatementGotoDeclarationHandler.targetsFor` 在 EDT 上直调同一条解析链 |
| S4 | 同一解析链在快捷键/预览通道上「时好时坏」：09:03:19 与 09:06:02 两次预览 `len=0`（空白面板 = `statementNotFound()`），而 09:04:22 / 09:05:05 / 09:07:38 / 09:08:01 同一操作 `len=84~97` 正常 | `ConvertToSqlAction.previewIbatisTarget` → 同一个 `IbatisStatementPreview.previewResolved` |

## 二、现场证据（2026.3 真机日志 + 语料实测）

### 2.1 语料规模（demo 根工程，`F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer-demo`）

| 项 | 读数 | 取法 |
|---|---|---|
| 工程内 XML 文件数 | **2493** 个 / **16.58 MB**（排除 `target/`） | `find -name "*.xml" -not -path "*/target/*"` |
| 其中 `<sqlMap>` 根（iBatis） | **303** 个 | `grep -rl "<sqlMap"` |
| 其中 `<mapper>` 根（MyBatis 3） | **2054** 个 | `grep -rl "<mapper "` |
| 索引扫描文件总数（工程打开时） | **202833** 个（`UnindexedFilesScanner`，09:03:10） | 见 2.2 引用的日志 |

### 2.2 日志锚点（`C:\Users\USER\AppData\Local\JetBrains\IntelliJIdea2026.3\log\idea.log`）

| 行号 | 时间 | 事件 | 意义 |
|---|---|---|---|
| 497 | 08:43:57 | `dynamic install succeeded — plugin hot-reloaded to 2026.11.16` | 本轮是**热更新后**的会话，插件 classloader 换代 |
| 397-398 | 08:43:56 | `Clearing storage data for: MapperNamespaceIndex` / `Indices to be built: FilenameIndex(v=258), cn.song.mybatisplus.mapperNamespace(v=1)` | 热更新使自定义索引存储被清，全量重建（MyBatis 侧 F1''' 索引） |
| 3604-3613 | 09:03:06 | `Scheduling indexing of pom.xml ... by request of indexes: [cn.song.mybatisplus.mapperNamespace->NOT_INDEXED]` | 同一批里 XML 全量进索引——**证明自定义 XML 索引的输入面就是全部 XML** |
| 3617 → 3850 | 09:03:06.715 → 09:03:14.871 | `enter dumb mode [demo]` → `exit dumb mode [demo]` | 工程打开后有 **8.1 秒 dumb 窗** |
| 3779 | 09:03:10 | `Scanning completed ... Number of scanned files: 202833` | 根工程 20 万文件；用户是「大工程根目录打开」场景 |
| 263-264, 3403 | 09:03:04 起 | `project=mybatis-plus-sql-visualizer` / `cyzc-platform` / `mybatis-plus-sql-visualizer-demo` 三工程同进程 | **三个工程窗口同开**（下文 RC-2 的关键前提） |
| 3926 → 5473 | 09:03:19 ~ 09:08:01 | 八次 `[M36] preview root` | 用户在本窗口反复点预览图标（S1 的用户动作） |
| 3928 / 5119 | 09:03:19 / 09:06:02 | `[M36] showSql ... len=0` | 预览面板**空白**——即 `IbatisStatementPreview.statementNotFound()` 的可见失败形态 |
| 4466 / 4794 / 5465 | 09:04:22 / 09:05:05 / 09:08:01 | `[M36] showSql ... len=86 / 86 / 84` | 同一条解析链**同时段又能解析**——好/坏交替 |
| 4887 / 4918 | 09:05:44 / 09:05:47 | `BUILDER_PROCESS [stderr]: Process creation` | 紧接着 09:06:02 的 `len=0`（JPS 写盘 → 读被取消的窗口） |
| 4434 / 5648 | 09:03:41 / 09:08:33 | `Using preloaded build process to compile F:/.../mybatis-plus-sql-visualizer-demo` | demo 工程两次编译，均在用户操作时段内 |

补充事实：日志里 **0 条** iBatis 侧解析日志，因为该链路全程静默——`runCatching` 把异常吞掉（RC-4），没有任何 `ERROR`/`WARN` 会让用户或我们看到「这一趟没算完」。

## 三、静态归因（代码面，逐条可复核）

链路：`collectSlowLineMarkers`（每个 `PsiIdentifier` 一次）→ `IbatisXmlStatementTargets.resolveStatementAnchors` → `findSqlMapFileByNamespace` → `sqlMapListing`。

### RC-1（慢的主因）清单构建是「全项目 XML 的 PSI 解析」

`src/main/kotlin/cn/song/mybatisplus/visualizer/ui/nav/IbatisXmlStatementTargets.kt:309-317`：

```kotlin
val files = runCatching {
    FileTypeIndex.getFiles(xmlFileType, GlobalSearchScope.projectScope(project))
        .mapNotNull { vf -> PsiManager.getInstance(project).findFile(vf) as? XmlFile }  // ← 2493 次建 PSI
        .filter { it.rootTag?.name == SQL_MAP_ROOT_TAG }                              // ← 2493 次整文件解析
        ...
}
```

`FileTypeIndex.getFiles(XML, projectScope)` 返回全部 2493 个 XML，然后**每一个都 `findFile` + 取 `rootTag`**（等于解析整份 XML）才筛出 303 个 sqlMap。这与 MyBatis 侧在 F1'''（`7dc6c1c73`）里删掉的老实现是**同一份形态**：当时正是因为「4841 个 XML 的 PSI 解析」把慢趟饿死，才换成了 `MapperNamespaceIndex` 自定义索引。**iBatis 这条平行实现没跟着换，是 F1''' 横向审计的漏网面。**

### RC-2（慢的放大器）单槽 + 全局 + 5 秒 TTL，且被多工程互相踢

同一文件的 259-299 行：`fileListSlot` 是**进程级单槽**（`@Volatile private var fileListSlot: FileListSlot?`），槽的有效性判据里带 `slot.project.get() === project`；`fileListTtlMs = 5_000L`。

- 编辑器保持打开时，慢趟每 5 秒就会重扫一次全项目 XML（滚动 / 光标 / 编辑都会触发新的 pass）；
- 用户在同一个 IDE 进程里开着**三个工程**（2.2 已证），任一工程窗口的 pass 都会把另一个工程的槽挤掉 → 「槽命中率≈0」；
- `listingGenerationCounter`（第 289 行）是**非 volatile 的裸 `var`**，慢趟（BGT）与 Ctrl+B（EDT）并发自增，换代戳本身也不可靠。

### RC-3（慢的次因）namespace 二次解析

`findSqlMapFileByNamespace`（第 165-190 行）拿到清单后，又对清单里的文件逐个 `PsiManager.findFile(vf)` + `rootTag?.getAttributeValue("namespace")`。清单是 303 个文件，而 demo 的 `User.xml` 在 `ibatis2-demo/`，索引序里排在 `cust-big-demo/cust-order/sqlmap/Od*.xml`（303 个里的绝大多数）之后 → **每个 `User.*` 调用点首次解析要再摸一遍 300 来个文件**。

`UserDaoImpl` 有 21 个调用点，同一趟 pass 里 21 次 `resolveStatementAnchors`（每处只由方法名 identifier 触发一次；两枚 marker 在同一次调用里一并产出）；`namespaceSlot` 只缓存**最后一个** namespace，勉强能吃住同一 pass 内的重复，但跨 pass 只要 TTL 过期就全部重来。

### RC-4（S2 的主因）「没算完」被写成「没有」

`sqlMapListing` 用 `runCatching { ... }.getOrDefault(emptyList())` 收尾：

- dumb/索引未就绪时 `FileTypeIndex.getFiles` 抛 `IndexNotReadyException`；
- 慢趟的读操作被写操作打断时抛 `ProcessCanceledException`（PCE）。

两种异常都被吞成**空清单**，并被当作一次正常结果写进槽（换代 + 5s TTL）。随后 `resolveStatementAnchors` 返回空 → `createMarkers` 返回 null → 平台在**本趟换装**时把上一趟已渲染的两枚图标摘掉。

关键在平台语义：慢趟结果按趟整体替换，**一趟没产出就等于「这个元素没有图标」**，而 daemon 不会因为「上次空」自己再跑一遍。这一条在 F1' 复盘里已经被实测钉死（`CustChannelMapper.java` 敲空格+撤销即恢复，滚动不恢复）。所以 S2 一旦发生就是**粘性的**，直到下一次真正的编辑触发重分析——正是用户说的「无故不显示」。

`status`: 这也是为什么日志里一条痕迹都没有：吞异常 + 静默返回 null。

**用户自己的点击记录已经把这个「时好时坏」拍在日志里**（不是纯推断）：09:03:19（工程刚退出 dumb 5 秒）与 09:06:02（紧跟 09:05:44/09:05:47 的构建进程写盘）两次预览打开的是**空面板**——`previewResolved` 在解析不到语句时返回 `statementNotFound()`（`sql=""` + 「语句未找到」告警），所以 `[M36] showSql ... len=0` 就是这个失败态的直接读数；而 09:04:22 / 09:05:05 / 09:07:38 / 09:08:01 同样操作分别是 86 / 86 / 85 / 84 字符。**同一个文件、同一个语句、十几分钟内好/坏交替**，正是「取消窗口把空清单固化」的指纹。

### RC-5（S2 的兜底面）dumb 直接弃权，没有 F1' 的乐观产出

`IbatisJavaToXmlLineMarkerProvider.kt:110`：`if (DumbService.isDumb(project)) return null`。工程打开后的 8 秒 dumb 窗（2.2 第 3617/3850 行）里，任何一次 pass 都会把图标摘掉；而 MyBatis 侧 F1-b 已有「dumb 窗乐观产出（不入缓存）」兜底，iBatis 侧没有对标实现。

### RC-6（S3）Ctrl+B 在 EDT 上跑同一条重链

`IbatisDaoStatementGotoDeclarationHandler.getGotoDeclarationTargets` 在 EDT 被调用，`targetsFor` 里 `ReadAction.compute` 是**内联执行**（EDT 上），于是 RC-1 的 2493 次 PSI 解析直接压在 EDT 上——用户感知为「跳转卡一下」。

### 归因结论（一句话）

不是「图标渲染慢」，是**每次判定目标是否存在，都要把项目里 2493 个 XML 重新 PSI 解析一遍去找那 303 个 sqlMap**；而失败/取消会被静默固化成「不存在」，慢趟换装后不再重试。MyBatis 侧三刀（F1 / F1' / F1'''）修的就是这两件事，iBatis 侧是同一形态的未迁移副本。

## 四、修法设计

### P0 主刀：新增 iBatis 自定义索引，替换清单状态机

新增 `src/main/kotlin/cn/song/mybatisplus/visualizer/xml/ibatis/SqlMapNamespaceIndex.kt`：

- `ScalarIndexExtension<String>`，`ID.create("cn.song.mybatisplus.sqlMapNs")`，`getVersion() = 1`；
- 每份 XML 产**两个键**：
  - `all`——常量键，`getContainingFiles("all")` 直接得到「项目内全部 sqlMap 文件」（裸 id 路径的清单，**零 PSI**）；
  - `ns:<namespace>`——根标签 `namespace` 非空时产出（`ns.id` 路径的主查询）。
  - 键前缀 `ns:` 避免 namespace 字面量等于 `all` 时撞键。
- 提取内核与 `MapperNamespaceIndex.namespaceFromHeadText` 同形（原始文本 16KB 头扫、双/单引号都认），但**锚根标签名 `sqlMap`**：`<mapper>` 不产键，`<sqlMap>` 与 MyBatis 互斥，不扩面。
- `getInputFilter() = DefaultFileTypeSpecificInputFilter(XmlFileType.INSTANCE)`、`dependsOnFileContent() = true`（与已发行的 `MapperNamespaceIndex` 同一条已验证形态；`plugin.xml` 里 `<fileBasedIndex>` 的 `dynamic="true"` 平台声明已核，热更新不受影响）。
- 正确性闸门仍在 PSI 侧：`statementAnchorsIn` 校验根标签 + `id` 精确相等；索引只负责「去哪几个文件里找」，键偶有偏差只会漏查不会错跳。

**为什么新开索引而不是复用 `MapperNamespaceIndex`**：复用要把已发行、已验收的 MyBatis 索引版本从 1 升到 2 并触发全量重建，同时把两个 ORM 域的失效/失败耦合在一起；新索引各自独立 bump，代价是 XML 文件多跑一遍 16KB 头扫（相对 2493 次整文件解析可以忽略）。此方案已在调研报告 MPJ 的形态里有先例（它同时挂着多个 `fileBasedIndex`）。

**注册**：`src/main/resources/META-INF/plugin.xml` 在现有 `MapperNamespaceIndex` 注册（第 234-235 行）旁边加一条 `<fileBasedIndex implementation="...SqlMapNamespaceIndex"/>`，注释写清「iBatis 2.x，根标签 `sqlMap`」。

### P1 瘦身：`IbatisXmlStatementTargets` 退掉 TTL/换代/清单槽

- `sqlMapListing(project)` → `SqlMapNamespaceIndex.getAllSqlMapFiles(project)`（索引查询）；
- `findSqlMapFileByNamespace` → `SqlMapNamespaceIndex.getSqlMapFilesByNamespace(project, ns)`，取第一个 root namespace 精确匹配的文件；
- 删除：`FileListSlot`、`Listing`、`listingGenerationCounter`、`fileListTtlMs`、`clockMs`、`indexScanCount`、`clearCachesForTest`、`listing.files.all { isValid }` 那套失效判据；
- 保留 `statementIdSlot`（裸 id 结果 memo），但失效戳改成 `PsiModificationTracker.getInstance(project).modificationCount` + 命中元素 `isValid`，不再依赖「清单代」；
- 对外签名 `resolveStatementAnchors` / `resolveSameFileIdReference` / `findIdAttributeInFile` / `STATEMENT_ENTRY_METHODS` 全部不变——`ui/ref`、`ui/nav`、`actions`、`mcp` 六处调用方零改动。

预期形态与 F1''' 后的 `MapperXmlLocator`（303 → ~100 行）一致：只留「索引查询 + PSI 校验」。

### P2 兜底：provider 失败分支照 F1' 四段式

`IbatisJavaToXmlLineMarkerProvider.createMarkers` 改为：

1. `resolveStatementAnchors` 非空 → 入**正缓存** `resolvedIbatisKeys`（`ConcurrentHashMap.newKeySet`，键 = 解析成功用的 `statementId` 原文）→ 产出；
2. 空 + 正缓存命中 → 仍产出（慢趟换装不再摘已确认的图标）；
3. 空 + dumb（`isDumbProvider` 测试缝）→ 乐观产出，**不入缓存**；
4. 其余 → 不产出。

缓存是**会话级不失效**（与 MyBatis 侧 F1-a 同口径）：语句被删/改名后最坏表现为「多一枚点了会提示『语句未找到』的图标」，可接受；换来的是「一次成功、此后不再无故消失」。

XML→Java 方向（`IbatisXmlToJavaLineMarkerProvider`）横向评估后决定是否同改：其锚在 XML 语句行、不依赖跨文件解析，**本轮不动**，写入计划留观察。

### P3 异常语义：不许把「取消」当「没有」

- `sqlMapListing`（改造后就是一次索引查询）**不得**再包 `runCatching{}.getOrDefault(emptyList())`；
  - `ProcessCanceledException` → 直接上抛（本趟作废，平台自行重跑）；
  - `IndexNotReadyException` → 返回 null 语义（「未知」而非「空」），且**不写任何负缓存**。
- 仓内既有正解形态：`FatalErrors.isFatal(t) ?: throw t`（F1''' / `MapperNamespaceIndex` 调用侧同款）。

### P4 横向审计（同类面，本轮只出结论，不顺手扩面）

| 面 | 现形态 | 判定 |
|---|---|---|
| `ui/ref/MybatisXmlTargets`（`FileTypeIndex` + 单槽 TTL，第 314 行） | 挂在 XML 属性值 reference 热路径（每个 `XmlAttributeValue` resolve） | **同病**，但用户在 vivo 工程实测未报；列为下一批候选（本批不动，避免与已验收的 F1''' 交叠） |
| `psi/XmlCrossNsFragmentResolver`（第 107 行） | 同上形态，跨命名空间片段解析 | 冷路径（预览/构建期），暂留 |
| `psi/jpa/HbmXmlEntityResolver`（第 221 行） | `FileTypeIndex` + 后缀过滤 | 冷路径（仅 JPA 预览），暂留 |
| `log/MapperXmlLocator` | 已索引化（F1'''） | 已修 |
| `log/MapperNamespaceIndex` | 已索引化 | 已修 |

判定口径：**进不进 gutter / reference 热路径**。进则索引化，不进则留。

### P5 观测与回退

- 新增 INFO 日志（只打一次/每文件）：`[gutter-perf] ibatis resolve: source=index|memo|optimistic statementId=… file=… ms=…`——把「为什么这一刻没图标」从不可见变成可读；
- 回退开关（Registry）：`sqlvis.gutter.ibatisIndexedResolve`（默认 true）→ false 走旧 `FileTypeIndex` 全扫路径；旧路径保留一个会话用于 A/B（下一版删除）。

## 五、兼容面（不得收窄）

- 只认根标签 `<sqlMap>`（iBatis 2.x，`sql-map-2.dtd`）：MyBatis 3 `<mapper>`、iBatis 3、JPA/HBM、MBG 全链**零改动**；
- 索引输入过滤是「全部 XML」，比旧路径的「全部 XML + PSI 解析」**只多不少**，不引入漏查；
- 顺带收益：`target/classes/sqlmap/User.xml` 这类产物副本此前会参与多命中，改走平台索引后由「排除目录」统一处理，多命中噪音下降；
- 索引失效全交平台增量（`dependsOnFileContent=true`）：新增/删除/改 namespace 的 sqlMap 文件立刻可查，**不再有 5 秒真空窗**；
- ORM 覆盖面不变：`StatementEntryMethods` 仍是 `javap` 实测的 `SqlMapExecutor` 清单，不增不减。

## 六、验收清单（可照抄）

| 项 | 判据 | 期望读数 |
|---|---|---|
| A1 | demo 根工程冷开 `UserDaoImpl.java`，图标出现耗时 | 首趟 pass 完成即出；日志 `[gutter-perf] ibatis resolve: source=index` 的 `ms` 单项 < 30ms（对比改前同锚点从打开到出图 ≥ 数秒） |
| A2 | 图标不再无故消失 | 打开后连续 60s 不做编辑、只滚动；再触发一次 Build（进 dumb 窗）；两轮后两枚图标仍在 |
| A3 | Ctrl+B 不卡 EDT | 点击到跳转 < 200ms；日志 `source=index`，无 `source=scan` |
| A4 | dumb 窗乐观产出 | dumb 期间图标在；dumb 结束后图标仍在（且此时才入正缓存） |
| A5 | 索引正确性 | 改 `User.xml` 的 `namespace`，图标随之消失（索引失效生效）；改回即恢复 |
| A6 | ORM 不串 | 同一工程里 `CustAddressMapper.java`（MyBatis）图标行为不变；`<mapper>` 文件不产任何 iBatis 键（单测钉） |
| A7 | 回退通道 | Registry `sqlvis.gutter.ibatisIndexedResolve=false` 后行为回到旧路径（`source=scan`） |
| A8 | 噪音 | 全 log 0 ERROR；无新增 `SlowOperations` SEVERE |
| A9 | S4 空面板不再出现 | 冷开工程后 1 分钟内连点同一条语句的预览 ≥10 次（含 Build 触发写盘的窗口），`[M36] showSql` 的 `len` 必须恒 > 0，不得出现 `len=0`；出现即判失败并附 `[gutter-perf]` 行 |

> 用户 2026-11-16 的原始反馈（S1/S2）在 A1/A2 覆盖；用户若继续在 2026.3 真机复验，取证口径＝「打开文件时刻 → 首枚图标时刻」+ 该时段的 `[gutter-perf]` 行。

## 七、实施顺序与提交切分

1. `SqlMapNamespaceIndex` + 内核单测（纯文本提取矩阵）+ `plugin.xml` 注册 → 提交一笔；
2. `IbatisXmlStatementTargets` 瘦身 + 现有 12 个测试文件的机制断言迁移 → 提交一笔；
3. provider 失败分支 + 接线守卫（新 `Guard` 文件名必须登记 `GuardSelfTestCoverageMetaGuardTest`，否则 CI `fast` 红——L13 Seen 4×）+ 正向矩阵 → 提交一笔；
4. 日志/Registry 回退 + 文档回填（`CHANGELOG [Unreleased]`、计划册、索引行、`docs/架构设计/02-核心流程详解.md` 里 iBatis 解析链的描述、`LESSONS` 收口条目）→ 提交一笔。

每笔单独可编译、单独定向测试；禁止攒最后一次性提交（LESSONS 既有条目）。

## 八、待验证前提（实施者先证伪再动手）

| # | 推断 | 证伪方式 |
|---|---|---|
| P-1 | 「2493 次 PSI 解析」是 S1 的主因，量级在秒级 | 改前在 demo 根工程加一次性计时探针（`System.err` + `nanoTime`），记录 `sqlMapListing` 单次耗时；若实测 < 300ms，则 S1 另有主因，须回头重查慢趟排队 |
| P-2 | S2 的触发窗是 dumb/取消导致空清单被固化 | 复现时把 `runCatching` 临时改成「吞异常打日志」，看是否出现 `[IndexNotReady|PCE]`；若一次都不出现，则 S2 是别的失败面 |
| P-3 | 慢趟结果确实整体替换、且平台不自动重跑 | 已有 F1' 真机实证（空格+撤销恢复、滚动不恢复）；若本次复验推翻，P2 设计要重估 |
| P-4 | 自定义索引在 2026.3 上对 `<sqlMap>` 文件的覆盖与 `FileTypeIndex` 等价（含 `.xml` 大小写、排除目录口径） | 单测 + 真机 A5（改 namespace）双证 |

## 九、实施记录

实施：2026-11-16，主分支 master 直改，两笔代码 + 一笔文档（用户令「在主分支实施开发计划的所有内容」）。

### 9.1 改动点与提交

| 提交 | 范围 | 内容 |
|---|---|---|
| `b5ccf14c7` | P0 | 新增 `xml/ibatis/SqlMapNamespaceIndex.kt`（`all` / `ns:` / `stmt:` 三类键，纯文本内核 + 三类查询）；`plugin.xml` 注册 `<fileBasedIndex>`；新增 `xml/ibatis/SqlMapNamespaceIndexTest`（内核 5 + 索引集成 2） |
| `bcad37b84` | P1/P2/P3/P5 | `ui/nav/IbatisXmlStatementTargets.kt` 瘦身（索引查询 + PSI 校验，TTL/清单代/单槽/三个计数缝/`clearCachesForTest` 全部退役）；`IbatisJavaToXmlLineMarkerProvider` 四段式 + `buildMarkers` 抽取 + `resolvedIbatisKeys` / `isDumbProvider` / `resetResolvedCacheForTest`；Registry `sqlvis.gutter.ibatisIndexedResolve`；`[gutter-perf]` 日志；新守卫 `IbatisGutterFailBranchGuardTest`（登记元守卫）；矩阵 `testF1i_*` 六例；两条计数缝机制用例改行为/源码扫描形态 |
| 本笔（文档） | P4/P5 账 | CHANGELOG `[Unreleased]`、本计划 §九、`docs/索引/开发计划.md` 状态、`docs/架构设计/02-核心流程详解.md` iBatis 双向跳转段、`docs/LESSONS.md` L74 |

`plugin.xml` 两处登记：`<fileBasedIndex implementation="…SqlMapNamespaceIndex"/>`（与 MyBatis 侧 `MapperNamespaceIndex` 相邻）与 `<registryKey key="sqlvis.gutter.ibatisIndexedResolve" defaultValue="true">`（纯英文 description，`BundleParityGuard` 对 registryKey 族整元素豁免）。

### 9.2 与计划的偏离（三处，均有理由）

1. **P0 键面扩到三类**：计划写 `all` + `ns:<namespace>`；实现加了 `stmt:<id>`（6 类语句标签的 id）。理由：iBatis 默认 `useStatementNamespaces=false`，裸 id 是**默认形态**，只索引 namespace 会让裸 id 仍需遍历全部 sqlMap 文件；P1 原方案「裸 id 保留 memo，失效戳改 `PsiModificationTracker`」会被每次击键推高计数而不停失效（等于每趟 pass 重扫），故直接索引化后把该 memo 一并删除，本节吸收 P1 的「保留 memo」项。
2. **根标签判定用全文而非 16KB 头**：索引期本来就拿到了 `contentAsText` 全文，截 16KB 只会引入「长注释/长头文件漏收」风险；改为「首个 `<sqlMap` 锚 + `<sqlMapConfig` 位置比较」（配置文件里的 `<sqlMap resource=…>` 子元素已排除，`SqlMapNamespaceIndexTest` 两例钉住）。语句 id 本来就只能在全文里找。
3. **顺带修一处潜伏缺陷**：gutter 侧 `IbatisCallTargets.ownerNamespace(element)` 从 identifier 起爬要 7 跳才到 `PsiClass`，超出其 6 跳上限 → 静默取到 null，裸 id 的「同命名空间优先」偏好一直没生效。改为从调用表达式起爬（5 跳），并把所属类限定名放进正缓存键（裸 id 形态），`testF1i_裸id缓存键带所属类限定名` 钉住。该缺陷由本轮 `testF1i_*` 首次暴露（改前无用例断言过这条偏好）。

### 9.3 定向读数（`./gradlew test --tests … -Pkotlin.compiler.execution.strategy=in-process --no-configuration-cache`，非全量）

| 测试类 | tests | failures | errors |
|---|---|---|---|
| `xml.ibatis.SqlMapNamespaceIndexTest` | 7 | 0 | 0 |
| `ui.gutter.IbatisNavigationLineMarkerProvidersTest` | 26 | 0 | 0 |
| `ui.gutter.IbatisGutterFailBranchGuardTest` | 2 | 0 | 0 |
| `ui.gutter.IbatisGutterHotPathTest` | 9 | 0 | 0 |
| `ui.gutter.IbatisStatementPreviewTest` | 9 | 0 | 0 |
| `ui.gutter.JdbcTemplateIbatisMutualExclusionTest` | 4 | 0 | 0 |
| `ui.gutter.MapperXmlSlowResolveFailGuardTest` | 4 | 0 | 0 |
| `ui.nav.IbatisDaoStatementGotoDeclarationHandlerTest` | 15 | 0 | 0 |
| `ui.nav.IbatisGotoRealEntryTest` | 2 | 0 | 0 |
| `ui.ref.IbatisXmlIdReferenceContributorTest` | 14 | 0 | 0 |
| `actions.ConvertToSqlActionTest` | 9 | 0 | 0 |
| `convention.GuardSelfTestCoverageMetaGuardTest` | 8 | 0 | 0 |
| **合计** | **109** | **0** | **0** |

`plugin.xml` 动了两处 EP/注册（`<fileBasedIndex>` + `<registryKey>`，属「大改动」判据），故按纪律补跑构建校验与 EP 守卫：

- `./gradlew verifyPlugin --no-configuration-cache`：**BUILD SUCCESSFUL**（IU-243.28141.41 / IU-253.33514.17 / IU-263.7307.200 三份报告），`Dynamic Plugin Eligibility: Plugin can probably be enabled or disabled without IDE restart`；输出里只有改造前既有的 Experimental / OverrideOnly 警告（`NewDataSourceEntry` 等，与本次无关）；
- EP/本地化守卫三件：`config.PluginXmlExtensionPointResolutionTest` 4/0、`config.PluginXmlStructureGuardTest` 23/0、`i18n.BundleParityGuardTest` 5/0（registryKey 族整元素豁免仍生效，新键纯英文 description 未被判缺 bundle）。

### 9.3.1 CI 读数（推送后）

- 推送：`dev-support/push-all.ps1 -Branch master` 双仓成功（Gitee 主仓 + GitHub 直连兜底，日志 `x-access-token:***@`＝走令牌通路、无 GUI 弹窗）。
- `推送测试 · master` run **`43790850727`**（head `cfbf47168`）：**8/8 全绿** —— fast(1)、fast(2)、test(1)~test(5)、nightly-nudge 全 success；`nightly-nudge` 注记「本轮周期已有夜间轮，跳过兜底派发」（与本次改动无关）。
- 桶归属核对：`SqlMapNamespaceIndexTest`（`BasePlatformTestCase`）→ 分片腿；`IbatisGutterFailBranchGuardTest`（纯 JUnit）→ fast 快桶腿。两条腿都绿；CI 侧 config cache 显示 `no cached configuration is available`（分片计划按当前源码重算），新类不存在「不在任何片里」的空绿面。fast 绿同时证明新 Guard 文件在 `GuardSelfTestCoverageMetaGuardTest` 的登记被接受（漏登即 fast 红，L13）。

实施期两处先红后修（记录在案，供后续复盘）：`SqlMapNamespaceIndexTest` 首跑 1 红（`<sqlMapConfig>` 内嵌 `<sqlMap>` 被收成 `all`）→ 加配置文件根锚排除；矩阵首跑 1 红（裸 id 缓存键取不到所属类）→ 即 9.2 第 3 条缺陷。

### 9.4 反向验证（摘分支 → 两红 → 恢复 → 全绿）

临时注释掉 `IbatisJavaToXmlLineMarkerProvider.createMarkers` 的正缓存命中分支（③）：

- **第一读数**：28 例 **2 红** —— `IbatisNavigationLineMarkerProvidersTest.testF1i_解析失败但缓存命中_图标不缺席`（图标被摘形态复现）＋ `IbatisGutterFailBranchGuardTest.P2接线守卫_createMarkers必须挂dumb门与正缓存兜底`（wiring=false）；
- **第二读数**（Edit 恢复分支、`git diff -- src/main` 为空后复跑）：**0 红**，BUILD SUCCESSFUL。

### 9.5 未做与遗留

- **A1~A9 真机/沙盒验收未跑**：本刀按要求只做定向测试收口；A1/A2/A9 需要真机 2026.3（用户环境）或 runIde 沙盒，留给用户复验或下一轮 runIde。注意首次启用索引时工程会全量建一次索引（`Indices to be built` 里会多出 `cn.song.mybatisplus.sqlMapNs`），属一次性成本。
- **P-1（2493 次解析的确切耗时）未做数值实测**：修复后链路已无全扫，该前提对结论不再必要；日志锚点 `[gutter-perf] ibatis resolve: source=index … ms=` 可在真机直接读数（A1）。
- **P4 横向审计仅出结论**：`ui/ref/MybatisXmlTargets`（同病、在 XML 属性 reference 热路径）、`psi/XmlCrossNsFragmentResolver`、`psi/jpa/HbmXmlEntityResolver` 三面未动，仍按 §四 P4 表留待下批。
- **点击期 `runCatching` 未收紧**：`IbatisDaoStatementGotoDeclarationHandler.getGotoDeclarationTargets` 与两个 gutter 点击 handler 仍用 `runCatching` 包裹（契约是「任何异常按本次不接管」）。P3 只约束定位链本身不再把取消吞成结果；点击链若也要严格遵守 PCE 语义需另立项（改动面涉及用户可感的「点了没反应」时机）。
- **Registry 回退通道**（`sqlvis.gutter.ibatisIndexedResolve=false`）按计划只保留一版，下一版删除 `legacySqlMapFiles` 与 `indexedResolveEnabledProvider`。

红线遵守：未触碰 `update/update.json`；未 push；未跑全量测试；未 `clean` / `--stop`。

### 9.6 增补 P2.1（2026-11-16 用户沙盒复验报障）：目标确定不存在时必须收回正缓存

**报障**：用户按 A5 把 `User.xml` 的 `namespace="User"` 改掉，**图标没消失**。定性为 bug：P2 的正缓存
只进不出（为修 S2「无故消失」而设的会话级缓存无失效），真删/改名/改 namespace 时图标靠旧记忆续命。

**修法**：把「查不到」拆成三态，判据是**查询是否成功执行**，不是返回值是否为空：

| 三态 | 触发 | provider 处置 |
|---|---|---|
| `Found` | 索引查询成功且有 PSI 精确命中 | 入正缓存 + 产出 |
| `Unknown` | dumb / 索引重建中 `IndexNotReadyException` / 候选文件 PSI 瞬时取不到 | 缓存命中仍产出、**不动缓存**（保住 S2） |
| `Absent` | 查询成功执行、候选面确无目标 | **`resolvedIbatisKeys.remove`** + 不产出（P2.1） |

- `IbatisXmlStatementTargets.lookupStatementAnchors` 新增三态入口；`resolveStatementAnchors` 退化成
  它的列表视图（预览 / Ctrl+B / MCP 口径零变化）；`IndexNotReadyException` 归 `Unknown`，
  `ProcessCanceledException` 仍上抛。
- provider 由四段式改五段式，`Absent` 分支负责摘图标 + 收回缓存；新增 `lookupOverrideForTest`
  测试缝（`resetResolvedCacheForTest` 一并清）。
- 守卫 `IbatisGutterFailBranchGuardTest`：锚点改 `lookupStatementAnchors(`，并新增「`Absent` 分支必须
  `resolvedIbatisKeys.remove(`」的强制项；自证语料扩到五向，其中「缓存只进不出」必须报红。

**提交**：`8eca5211f`。

**读数**：定向 9 类 **95/0/0/0**（`IbatisNavigationLineMarkerProvidersTest` 28 例，含新增
`testF1i_确定不存在_摘图标并收回缓存`、`testF1i_索引重建中未知_缓存命中仍产出`、
`testF1i_改namespace后查询真实返回absent`——最后一条用真实索引 + VFS 改 namespace 钉住分类
不是靠猜）。反向验证：摘掉 `resolvedIbatisKeys.remove(key)` 一行 → 30 例 **恰 2 红**
（矩阵「确定不存在」+ 守卫），Edit 恢复后 **0 红**。

**同族（已于同日收口，见 §9.7）**：MyBatis 侧的 `MapperXmlToJavaLineMarkerProvider` /
`MapperJavaToXmlLineMarkerProvider` 用的是同一套「`resolvedStatementKeys` 只进不出」形态（删掉 mapper
方法 / 改 `<mapper namespace>` 时图标同样滞留），用户 2026-11-16 同一轮要求「接着修」，见下节。

### 9.7 增补 T-MapperGutterEvict（2026-11-16 同日收口）：MyBatis 侧同族缓存收回

**修法**（与 P2.1 同构，用户令「接着修，修完可以推」）：

- `MybatisXmlTargets.lookupInterfaceMethod` → `MethodLookup.Found/Absent/Unknown`（`IndexNotReadyException` 归 Unknown）；
- `MapperXmlLocator.locateOrUnknown` → `Lookup.Found/Absent/Unknown`（同上）；
- 两个 provider 改五段式：**dumb 门前移**（避免索引重建期查索引）→ `Found` 入缓存 → `Unknown` 保留
  缓存命中 → `Absent` `resolvedStatementKeys.remove` 并摘图标 → 其余不产出。
- `catch` 只捕获 `IndexNotReadyException` 且**收敛在 resolver 内**，provider 方法体仍零 `catch`
  （`BannedApiScanRuleTest` 与守卫的「禁 catch PCE」口径不变，PCE 照旧上抛）。

**守卫改造**：`MapperXmlSlowResolveFailGuardTest` 锚点改为 `lookupInterfaceMethod(` /
`MapperXmlLocator.locateOrUnknown(`；判定条件由「dumb 门与 contains 都在解析之后」改为
「dumb 门在查询**之前** + contains **之后** + **remove 之后**」；自证语料扩到五向（修复后 /
缓存只进不出 / 无兜底 / dumb 门错位 / 缓存先于查询），另加 Java 侧同构四向。

**矩阵改造**：`MapperNavigationLineMarkerProvidersTest` 两条「失败 + 缓存命中」用例改走 Unknown 缝
（`methodLookupOverrideForTest` / `lookupOverrideForTest`），新增 XML 侧与 Java 侧各一条
「确定不存在 → 摘图标 + 收回缓存」；`resetResolvedCacheForTest` 一并复位两个缝。

**读数**：定向 8 类 **93/0/0/0**（`MapperNavigationLineMarkerProvidersTest` 21、`MapperXmlSlowResolveFailGuardTest` 4、
`MapperNavigationGutterGateTest` 11、`MapperXmlLocatorTest` 10、`GuardSelfTestCoverageMetaGuardTest` 8、
iBatis 回归 28/2/9）。反向验证：摘掉 XML 侧 `resolvedStatementKeys.remove(key)` 一行 → 25 例 **恰 2 红**
（矩阵「确定不存在」+ 守卫），Edit 恢复后 **0 红**。

**提交**：`c0bc9b043`。

### 9.8 增补 T-MechanismSweep（2026-11-16 同日）：P4 横向审计落地 + 删 iBatis 回退通道

用户令「下一批待办，现在做」。§四 P4 表的三面按「进不进热路径」逐一判：

| 面 | 一审判定 | 本刀处置 | 依据 |
|---|---|---|---|
| `ui/ref/MybatisXmlTargets.findMapperFileByNamespace` | 进 reference 热路径 | **索引化** | 每个 `XmlAttributeValue` 的 resolve 都走；旧实现 `FileTypeIndex` 全项目 XML + 逐个 PSI 解析筛 `<mapper>`，配 5s TTL 单槽 |
| `psi/XmlCrossNsFragmentResolver.mapperFragments` | 常见冷路径（每次预览渲染一次） | **索引化** | 旧实现同样全项目扫描 + 单槽 TTL；索引化后扫描面降到「目标 namespace 的 1~3 个文件」，缓存整套退役，顺带消掉「改公共片段后 5 秒内预览按旧片段表」的窗口 |
| `psi/jpa/HbmXmlEntityResolver.findHbmXmlFiles` | 冷路径（JPA 预览） | **保留** | 复查发现它**不 PSI 解析非 hbm XML**（先用文件名后缀过滤再建 PSI），全项目 `FileTypeIndex` 只取一次文件列表；索引化要新增一套 `.hbm.xml` 实体索引，收益小于成本。留账待评估 |
| `ui/highlight/XmlCompletionSymbols` | 补全上下文 | 保留 | 另有 dumb 闸，且不在本次报障面 |

另删掉 T-IbatisGutterPerf P5 的 Registry 回退通道（`sqlvis.gutter.ibatisIndexedResolve` + `legacySqlMapFiles` + `indexedResolveEnabledProvider`，计划里约定只留一版）：索引化只有一条路，回退只能靠版本回滚。`[gutter-perf]` 日志同步去掉恒定的 `source=index` 字段（保留 `state/hits/file/ms`）。

**防回退**：新增 `MapperFileLookupIndexTest`（源码扫描、剥注释）钉住两个文件「必须用
`MapperNamespaceIndex.getMapperXmlFiles`、且不得再出现 `FileTypeIndex`」，自带老形态样本自证；
文件名不含 `Guard`/`SourceScanTest`，无需登记元守卫。反向验证：把 `FileTypeIndex` import 加回
`XmlCrossNsFragmentResolver` → **恰 1 红**，移除后 0 红。

**测试迁移**：`MybatisXmlReferenceTest` 三条计数缝用例 → 行为面（同 ns 连续解析稳定 / 负结果不缓存 /
新增文件立即可见）；`XmlCrossNsFragmentResolverTest` 四条 TTL/memo 用例 → 行为面（二次渲染一致 /
未声明 ns 给失败原因 / 新增片段文件立即可见 / 删除后降级）；两个 goto 测试删掉已无意义的
`clearFileListCacheForTest` 调用。

**读数**：定向 25 类 **264/0/0/0**；`verifyPlugin` 三腿 **BUILD SUCCESSFUL**（243 腿报
`Dynamic Plugin Eligibility: negative due to 1 restrictions — non-dynamic extensions: com.intellij.sql.dialect`，
属**改造前既有**的方言映射 EP 限制，与本次无关）。净 diff：**-266 / +66 行**（三处状态机退役）。

**提交**：`501db7d89`。

### 9.9 增补（2026-11-16 用户沙盒第二轮报障）：延迟预热补读动作 + XML 侧箭头加调用点门槛

用户沙盒实测报两件，均同日修复（`e4b3fdd27`）。

**A. `GutterWarmScheduler` 延迟派发缺读动作（IDE 日志 3 条 SEVERE）**

- 现场：`idea.log` 12:35:42 连出 3 条 `SEVERE - #c.i.u.c.ThreadingAssertions - Read access is allowed
  from inside read-action only`，三帧同源 `GutterWarmScheduler.scheduleWarmIfDue$lambda$0`（`:126` 的
  `tag.isValid`；`:130` 经 `kickFileWarmers` 进 `warmCompletionContextOnce` / `PreviewAssemblyWarm.warmOnce`
  的 `tag.containingFile`）。
- 根因：F2 的延迟派发跑在 `AppScheduledExecutorService` 池线程上，**不持读动作**；F2 之前的「首渲染直踢」
  跑在平台给 `getLineMarkerInfo` 的读动作里，故只有延迟路径会炸。
- 修法：把「到点复检 + 踢出」整体包一次 `runReadAction`（三链内部重活各自 `ReadAction.nonBlocking`）。
- **为什么两天没被发现**：`GutterWarmSchedulerTest` 的单测 seam（`syncDispatchForTest`/unit-test 模式）
  让延迟体**同步跑在已持读动作的测试线程**里，断言永远不触发 ⇒ 新增源码扫描守卫
  `GutterWarmSchedulerTest.延迟派发体必须包读动作`（含老形态自证），并记入 LESSONS **L78**。
- 同日志 11-13 另有 41 条同族报错（`XmlSqlCompletionContextResolver.warmCandidates` 当时未包读动作，
  已在 §二十四 修）——这是**同族在不同链路上的第二次**，故本轮补的是「机制级守卫」而不是再修一处。

**B. iBatis XML 语句行箭头无调用点也显示（用户报障「改 id 后 XML 图标没消失」）**

- 现场：`User.xml` 的 `<select id="selectAll">` 改名 `selectAll02` 后，Java 调用行图标正确消失
  （P2.1 已修），XML 语句行**仍挂箭头**且点击无反应。
- 根因（设计如此，非回归）：`IbatisXmlToJavaLineMarkerProvider.createMarker` 原为**无条件出箭头**，
  目标存在性留到点击时查；书面证据＝仓内 `test六种语句标签全部出XML侧箭头`（夹具无 Java 调用点却断言 6 箭头）。
- 修法：加三态调用点门槛（`IbatisJavaCallSites.lookupCallSite` → Found/Absent/Unknown），
  provider 五段式（dumb 乐观不入缓存 / Found 入缓存 / Unknown 保留 / **Absent `remove` 并摘箭头** / 其余不出）；
  回退通道 Registry `sqlvis.gutter.ibatisXmlArrowRequiresCaller`（默认 true）。
- 守卫扩面：`IbatisGutterFailBranchGuardTest` 判定函数参数化，新增「XML 侧 createMarker 必须挂三态」
  （缺 `remove` 即红）+ 缺 remove/漂移自证；`IbatisGutterHotPathTest` 禁用词表不变——搜索能力只准从
  `IbatisJavaCallSites` 收口走，KDoc 写明这条边界。
- 测试：矩阵 +4（命中入缓存 / 确定无调用点摘箭头并收回 / 判定未知缓存命中仍出 / dumb 乐观不入缓存）、
  用户场景 +1、回退通道 +1；两条既有正例补 Java 调用点夹具以保持原断言。

**读数**：定向 49 类 **408/0/0/0**；反向验证摘掉 `resolvedIbatisCallKeys.remove(key)` 恰 2 红
（矩阵「确定无调用点」+ 守卫 XML 侧项）→ 恢复 0 红。

**性能取舍（如实记录，待真机观察）**：调用点存在性走词索引查询，**常见词命中面大**（`selectAll`
在 Java 侧也是常见方法名）——按「命中入缓存 / 确认缺失收回」处理，稳态零查询；若真机出现可测的
慢趟变慢，先看 `[gutter-perf]` 与慢趟时长，再决定是否收紧（回退通道已就位）。
