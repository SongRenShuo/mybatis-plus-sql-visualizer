# T-ShadowEntityResolve：jar 内宿主叠加工程同 FQN 影子实体时，HQL 预览解析不出根实体（开发计划）

**创建时间**：2026-10-22 20:19
**触发源**：用户真机报障——「hql 场景，用户的方法在 jar 中、hql 也在 jar 中、实体类也在 jar 中；但是有时候会在项目下新增一个同包名同类名的类，调整些字段；目前这种场景插件无法正确识别，显示为实体不可解析，降级为 task.*」。附码片段为 `commonDao.findByQuery(hql, new String[]{...}, new Object[]{...})` 形态（自定义 DAO 链）。
**追加要求（2026-10-22 同日）**：用户明确「两种 jar 形态都要正常识别」——jar 源码以 **sources jar 附加**呈现、以及**无源码时的反编译视图**，两者都要能预览出正确 SQL；并问「三个方案是否一起实施」。本计划已按此补齐平台侧可达性结论（§1）与批次建议（§4.0）。
**基线**：`master@82ee88b1`（分析时 tip）。`psi/EntityClassResolver.kt` 在 master 与 dev 两树字节一致（md5 `b67a92d2…`），本计划对两树同源适用。
**状态**：仅归因分析与立计划。未改生产代码、未跑测试、未复现用户原始工程、未预占 O/R/D/V 编号、未提交、未推送。

---

## 一、现象与读数落点

用户读数「实体不可解析，降级为 task.*」是**降级注释第一档**，出处唯一：

- `converter/jpa/SelectClauseExpander.kt:101-112` `renderColumns`：列清单为空且 `table.entityClassName` 为空 → `"$alias.* /* 实体不可解析，降级为 $alias.* */"`（此处 alias 即 HQL 里的 `task`）。第二档（108 行）措辞不同（`实体 X 列信息未解析（检查继承链父类映射）`），可排除。
- 同现场还有 FROM 表名 fallback：`converter/jpa/HqlConverter.kt:170`「实体不可解析时，从 FROM 子句提取实体名替换为 unknown_table」（`HqlConverterSelectExpandTest.kt:195` 钉住了 `FROM unknown_table u` 这个形态）。用户完整产物应是 `... FROM unknown_table task ...` 一类。
- 第三处可核对现象：解析器会弹一次歧义气球（`preview.entityNameAmbiguity.title/content`，`MpSqlVisualizerBundle_zh.properties:1293-1294`：「实体名 WmsTask 有多个同名候选／范围 project / libraries 中存在实体名歧义，未选择任何实体。候选：…」）。**同 FQN 双份时候选清单会出现两条完全相同的全限定名**，这是现场最好用的指纹。

触发入口不要求「宿主在项目内」——这条已按代码核对，是本缺陷成立的前提之一：

- `ui/SqlPreviewService.kt:1075-1076` `previewCustomDaoHql(anchor)` 只依赖 PSI 与 locator 判定，没有文件位置门控；
- `ui/gutter/CustomDaoHqlGutterLineMarkerProvider.kt:37-74`、`ui/gutter/HqlGutterLineMarkerProvider.kt:32-65` 同样不判 `isInContent` / `isInLibrary`。

即：库（sources jar）里的源码文件在编辑器打开时照样出图标、照样走完整预览链。

**两种 jar 形态的可达性（平台级，2026-10-22 补核）**：

- **sources jar 形态**：宿主是库里真实存在的 `.java`（`isInLibrarySource`），图标与动作都不判位置，链路直达。
- **反编译形态**：`.class` 的桩 PSI **没有方法体**（`ClsMethodImpl.getBody()` 直接 `return null`），但平台把编译文件的 daemon 整体指向**反编译 PSI**：`DaemonCodeAnalyzerImpl.findFileToHighlight`（243 sources `com/intellij/codeInsight/daemon/impl/DaemonCodeAnalyzerImpl.java:1318-1330`）对 `PsiCompiledFile` 取 `getDecompiledPsiFile()`；`ClsFileImpl.getMirror()` 用反编译文本经 `PsiFileFactory.createFileFromText` 造出一份**真 Java PSI**（有方法体、有字符串字面量）；`ClassFileViewProvider.findElementAt/findReferenceAt`（`com/intellij/psi/ClassFileViewProvider.java:127-145`）同样落 mirror。⇒ 反编译视图里 LineMarker 与编辑器动作拿到的元素来自 mirror，**理论可达**（须沙盒实证，见 P-8）。
- 两种形态的共同点：宿主元素都不在 content（sources jar 是库文件；反编译是 mirror 的 `LightVirtualFile`，见 `com/intellij/psi/impl/PsiFileFactoryImpl.java:63-75`）⇒ `getModuleForFile` 均为 null ⇒ **本缺陷根因对两种形态同效**，P0 的消解规则一体适用。

## 二、已核事实（代码级 + 平台级）

### 2.1 实体解析全链（当前实现）

1. 预览与转换链共用同一入口：`ui/SqlPreviewServiceEnhancements.kt:510-537` `resolveHqlTable(...)` → 519 行 `EntityAliasSweep.resolve(project, className, context)`；`context` 由调用方给，自定义 DAO 链是 `info.method ?: anchor`（`ui/SqlPreviewService.kt:1113`，即 **HQL 所在方法**），转换链同样 `context = info.method ?: anchor`（同文件 `1118` 行）。
2. `psi/jpa/EntityAliasSweep.kt:42-80`：先 `EntityClassResolver.resolveByName`；无候选再走有界别名扫描——两条都收口到 `resolveWithAliasCandidates`。
3. `psi/EntityClassResolver.kt:48-88` 判据：
   - 56 行 `contextModule(project, context)`；96-104 行实现＝`ProjectFileIndex.getModuleForFile(vf)`；
   - 91-93 行 `searchScope`：模块已知 → `moduleWithDependenciesAndLibrariesScope(模块)`；**模块未知 → `GlobalSearchScope.allScope(project)`**；
   - 60-65 行按短名/FQN 取候选并按 `scope.contains` 过滤可见性；
   - **67-77 行同 FQN 多份定义的消解**：`module == null || fqn == null || definitions.size == 1` 时**原样保留全部**；只有模块已知时才用 `facade.findClass(fqn, scope)` 按 classpath 序挑一份；
   - 78-85 行过滤 + 稳定排序；86 行多候选提示一次；87 行 `singleOrNull()` ⇒ **多候选＝解析失败**（fail closed，T-EntityNameScope 定下的契约）。

### 2.2 库文件宿主 ⇒ 模块上下文恒为 null（平台语义）

- `ProjectFileIndexImpl.getModuleForFile` 只查 `ModuleRelatedRootData` 的 fileSet；`ModuleUtilCore.findModuleForFile` 的 javadoc 直写：**"null for project files outside module content roots or library files"**（243 sources `com/intellij/openapi/module/ModuleUtilCore.java`）。
- 所以「HQL/DAO 方法在 jar（源码附加）里」⇒ `contextModule == null` ⇒ `searchScope = allScope` ⇒ 2.1 第 4 步的同 FQN 消解**整段跳过**。
- 反编译形态同样命中：宿主元素来自 `ClsFileImpl` 的 mirror，其 `containingFile.virtualFile` 是 `LightVirtualFile`（`PsiFileFactoryImpl.createFileFromText` 里 `new LightVirtualFile(name, language, text)`），既不在 content 也不是库文件 ⇒ `getModuleForFile` 同样 null。两种 jar 形态都落在本缺陷的根因分支上。
- allScope 也兜不了底：`GlobalSearchScope.compare` 默认 `return 0`（243 sources `com/intellij/psi/search/GlobalSearchScope.java:58-60`），而 `JavaFileManagerImpl.findClass` 的择优靠 `scope.compare(...) > 0` 取「最大者」（`com/intellij/psi/impl/file/impl/JavaFileManagerImpl.java`）——全 0 ⇒ 取索引首个＝**实现序**，不是 classpath 序。`EntityClassResolver.kt:67-68` 那句「Without a module there is no meaningful classpath winner」正是这个由来。

### 2.3 模块已知时为什么能解（对照面）

- `ModuleWithDependenciesScope.compare`（243 sources `com/intellij/openapi/module/impl/scopes/ModuleWithDependenciesScope.java:149-164`）按 `myRoots`（＝`OrderRootsEnumerator` 给出的 **classpath 序**）倒序比较：越靠前越大越胜；`LibraryScopeBase.compare` 同理。⇒ 模块作用域内「工程内容/输出」先于依赖库，`findClass` 取的是工程那份。
- 既有 heavy 用例把这条契约钉住了：`psi/EntityClassResolverHeavyTest.kt:145-155`（同 FQN 源码 + 库二进制 → 与 `findClass` 同解、candidates=1）。

### 2.4 用户场景逐条套合

| 现场元素 | 形态 | 后果 |
| --- | --- | --- |
| HQL 宿主（DAO 方法） | 在 jar 内（源码可见） | `contextModule = null`（平台语义）→ `searchScope = allScope` |
| 实体类 | 在 jar 内（同 FQN 二进制） | 候选第 1 份 |
| 工程影子类 | 同包同名（同 FQN 源码） | 候选第 2 份 |
| 同 FQN 消解 | `module == null` ⇒ 跳过 | `candidates.size == 2` ⇒ `ambiguous` ⇒ `psiClass = null` |
| 下游 | `resolveHqlTable` 见 `resolution.ambiguous → return null`（`SqlPreviewServiceEnhancements.kt:521`），HBM fallback 也空 | `TableInfo = null` ⇒ fallback 表 + `task.* /* 实体不可解析… */` |

两条推论（同样按代码核对过）：

- **工程内宿主不复现**：模块已知 ⇒ 走 2.3 的 classpath 序 ⇒ 取工程影子 ⇒ 正常。所以「HQL 在 jar 里」不是巧合，是本缺陷的必要条件。
- **当前没有用户侧绕法**：连在 HQL 里写全限定名也救不了——限定名走 `facade.findClasses(fqn, scope)`（60 行）返回的仍是两份同 FQN 定义；`module == null` 不消解，78-85 行两道过滤都留 ⇒ 依旧 2 候选。

## 三、根因判定

主因（高置信，代码级可证）：**同 FQN 多份定义的消解被 `module == null` 整段关掉，而库内宿主恰好必然落在这条分支上**（`psi/EntityClassResolver.kt:67-77` + 平台 `getModuleForFile` 的库文件语义）。「工程内影子类」只是让这份同 FQN 双份出现的触发条件——工程内容优先本可一次分对，缺的只是「无模块上下文时也承认这条确定性」。

放大因素：

1. `searchScope` 退到 `allScope`（91-93 行），同短名的**不同 FQN**实体会一并进候选，把无关模块也拖进歧义（本缺陷场景不涉及，但同根）；
2. 歧义提示只列 FQN（106-150 行），同 FQN 双份时打印「候选：com.x.WmsTask, com.x.WmsTask」，现场可读性为零。

已排除的非根因：

- 不是嵌入列前缀/字段映射问题（那会走第二档注释或原样透传，不是第一档）；
- 不是 `HqlEntityNameExtractor` 提取问题（`from WmsTask task` 语句头提取正常，错误读数发生在类解析之后）；
- 不是缓存过期（`EntityAliasSweep` 缓存随 PSI/根版本失效，且本场景根本没进别名扫描路径）。

## 四、修复方案

### 4.0 实施批次建议（回答「三个方案是否一起实施」）

- **W1（本批必做＝P0 + P2，两个一起）**：P0 是主修；P2 是同一次改动面内的诊断补强——一个新私有函数 + 两处文案（中英两份须同步，过既有 bundle 对账守卫），不多动别的文件，收益是下次复现能一眼看出「两个同 FQN 候选」。
- **W2（本批必做；「两种 jar 形态」不是三个方案之一，是并行工作面）**：按 §1 的平台结论，反编译形态**大概率天然可达**（daemon 已指向反编译 PSI）。本批的工作是取证 + 补测试：heavy 夹具覆盖 class 宿主（mirror 元素）、沙盒覆盖反编译视图，并把 mirror 的 `LightVirtualFile` 对下游（方言决策、历史记录、错误定位）的影响查清。若取证发现反编译视图里图标不出现、或 locator 认不领、或下游另有降级读数，再按取证结果补能力——当前证据不支持预先动刀。
- **W3（单独一轮，不建议与 P0 同批）：P1**。两条理由：
  1. **会和 P0 打架**——P1 先把 `visible` 收窄到库的消费者模块；影子类若不是全部 owner 模块可见（多模块工程里 jar 常被多个模块引用，影子只落在其中一个模块），多 owner 不一致按现契约就是 fail closed ⇒ **把 P0 刚修好的场景重新打回降级**；
  2. **牵动面大、语义未定**——`searchScope` 同步收窄会波及 HBM 解析、NamedQuery、EntityFinder 等共用方，且需要先定「工程内容优先 vs 按消费者 scope 收窄」谁优先。要上先把这条优先级写清，再单独跑反证。
  P1 的真实收益是**另一类**缺陷：库内宿主下「同短名但不同 FQN」的无关模块候选造成假歧义（P0 治不了，FQN 不同不构成同组）。建议按独立一轮立项。

### 4.1 P0（必做，最小闭环）：同 FQN 组内「工程内容优先」预过滤

位置：`psi/EntityClassResolver.kt:48-88` `resolveWithAliasCandidates`，在 `visible` 之后、`canonical` 之前插入一步**组内预过滤**：

```
同 FQN 组 =
  组内存在「工程内容」定义（ProjectFileIndex.isInContent(vf) == true）
    → 只留工程内容定义
  否则 → 原样
```

- 口径理由：任何标准布局里工程输出都先于依赖 jar（IDE 运行配置、fat jar 的 `BOOT-INF/classes` 先于 `BOOT-INF/lib`、WAR 的 `WEB-INF/classes` 先于 `WEB-INF/lib`），所以工程内同 FQN 类就是**运行期实际生效**的那份——用户「新增同包同名类调整字段」的用意正是让运行期用影子。
- 与模块路径**同向**：模块已知时平台 `compare` 也是内容优先（2.3），所以 P0 只是把模块路径已有的选择**显式化**到无模块路径，两条路径不会打架。
- 不破坏既有契约：`module == null` 时**依然不借平台序**（67-77 行的限制保留），只承认「内容 vs 库」这一条确定性；同 FQN 的「两份工程源码」「两份库二进制」等组合照旧 fail closed（既有 `EntityClassResolverHeavyTest.kt:170-192` 必须保持绿）。

条件分支（由 P-3 取证决定）：若实测工程输出目录（`out/`、`build/`）的 `.class` 也进短名索引，同 FQN 组会是 3 份（工程源码 + 工程输出 + 库二进制），此时预过滤需追加第二档：**源码形态（`.java`／`PsiJavaFile`）优先于二进制形态**。取证方式见 §6.1。

### 4.2 P1（可选，结构性；需先把语义谈定）：库文件 → 消费者模块上下文

给 `contextModule`（96-104 行）加库兜底：文件不在 content 时，用 `ProjectFileIndex.getOrderEntriesForFile(vf)` 过滤 `LibraryOrderEntry` 取 `ownerModule`（公共 API，与平台 `ModuleUtilCore.findModuleForPsiElement` 的库分支同源）：

- **唯一 owner** → 采用，`searchScope` 随之收窄（顺带治 §3 放大因素 1）；
- **多 owner** → 建议逐 owner 作用域各解一次，**全一致才采用**，不一致维持 fail closed（沿用「歧义即终态、排序只用于展示」的既有纪律）；
- 语义代价必须先讲清：它意味着「按消费者模块的 classpath 判可见」，与 P0 的「工程内容无条件优先」在多 owner 且部分 owner 看不到影子时**结论不同**（P1 更可能判未解析）。**默认先只上 P0**；P1 若上，须单独反向验证并在文档里写清选取口径。
- 同源旁支（同批评估、可后置）：`context/ModuleIdentityResolver.kt:20-25` 现在用 `ModuleUtilCore.moduleContainsFile(module, file, false)`（只查 content），库文件恒 `None` ⇒ 方言决策走 fallback（`ui/SqlPreviewService.kt:254`）。它与实体解析同根不同链，不是本缺陷读数来源，登记为待评估项。

### 4.3 P2（体验，可后置）：歧义提示可诊断化

`EntityClassResolver.kt:106-150` + bundle（`MpSqlVisualizerBundle.properties:291-292`／`_zh.properties:1293-1294`）：候选清单在 FQN 重复时附**文件路径或「工程/库」标签**，避免「候选：A, A」。

### 4.4 明确不做

- 不给「两份工程源码」「两份库二进制」等同 FQN 组合猜胜者（除 4.1 两条明示规则外一律 fail closed）；
- 不改 `searchScope` 的 allScope 兜底（P1 单列）；
- 不新增全库扫描，不动 `EntityAliasSweep` 的预算与缓存口径；
- 不动 XML/注解补全、引用导航、反向生成器（SQL→Java）那几处独立的取类口径（§7 登记为已知边界）。

## 五、改动清单

| 文件 | 位置 | 改动 | 备注 |
| --- | --- | --- | --- |
| `src/main/kotlin/…/psi/EntityClassResolver.kt` | 48-88 | 插入同 FQN 组内预过滤（新私有函数，如 `preferProjectContent(definitions, project)`）；`module == null` 的「不借平台序」保持 | P0；第二档见 4.1 条件分支 |
| `…/psi/EntityClassResolver.kt` | 106-150 | 歧义提示补路径/标签 | P2，可选 |
| `…/psi/EntityClassResolver.kt` | 96-104 | 库文件 → ownerModule 兜底（唯一 owner / 一致性） | P1，可选 |
| `…/context/ModuleIdentityResolver.kt` | 20-25 | `isLibraryElement=true` 的第二档（方言决策用） | P1 旁支，可选 |
| `…/psi/jpa/NamedQueryLocator.kt` | 111-128 | 类候选过一个共享消解（复用 P0 的预过滤） | 同族，见 §7 |
| bundle 两份 | 291-292／1293-1294 | 文案 | 随 P2 |
| （W2 取证后确认需要时再列） | — | 反编译形态的补强（mirror 元素 / light VF 相关兼容） | 待 §6.1-W2 取证结论，当前证据不预设改动 |

## 六、验证方案

### 6.1 复现装置（heavy，先红后绿）

夹具沿用既有惯例（`ui/SqlPreviewEntityScopeHeavyTest.kt:87-117` 用 javac 造库类 + `ModuleRootModificationUtil.addModuleLibrary`；`psi/EntityClassResolverHeavyTest.kt:68-100` 断言读到 `.class` 而非 source）。本场景额外需要**库源码根**：

- `PsiTestUtil.addLibrary(module, libName, libDir, classRoots[], sourceRoots[])`（243 sources `com/intellij/testFramework/PsiTestUtil.java:477`）：`sourceRoots` 放 DAO 源码，`classRoots` 放 javac 编出的实体 `.class`；
- 工程侧 `addSourceRoot` 放影子实体（同包同名，表名/字段与库版不同）。

**W0 探针（动手前先跑，三条读数）**：

1. `EntityClassResolver.contextModule(project, <库内 DAO 方法元素>) == null`（P-1）；
2. `EntityClassResolver.resolveByName(project, "WmsTask", <库内宿主>)` 的 `candidates.size` 与各候选 `containingFile.virtualFile.path`（P-2、P-3）——这一步决定 P0 是否需要第二档；
3. `JavaPsiFacade.findClass(fqn, moduleWithDependenciesAndLibrariesScope(module))` 在同 FQN 双份下取到哪一份（P-4）。

**W2 取证（两种 jar 形态都要覆盖）**：

1. **heavy（sources jar 形态）**：夹具见上，是主用例的载体。
2. **heavy（反编译形态）**：库类根里放 javac 编出的 DAO（含 HQL 字面量），用与平台一致的取元素方式（`ClassFileViewProvider.findElementAt(offset)`，或 `((PsiCompiledFile) file).getDecompiledPsiFile()` 后遍历）拿到 mirror 里的 `PsiMethodCallExpression`，喂 `CustomDaoHqlLocator.locate` / `service().previewCustomDaoHql(call)`；断言与 sources jar 形态**产物一致**（同一 SQL）。同时断言桩 PSI 路径看不见 HQL（`method.body == null`）——这条是「必须走 mirror」的反证。
3. **沙盒走查**：无源码 jar → 打开 `.class` 反编译视图 → ①gutter 图标出现；②点击出预览；③除实体解析外没有别的降级读数（见 P-7/P-8）。

### 6.2 用例清单

新增（建议 `src/test/kotlin/…/psi/ShadowEntityResolveHeavyTest.kt`，或并入 `EntityClassResolverHeavyTest`）：

1. `库内宿主 + 工程同 FQN 影子 → 解析到工程影子`：断言 `psiClass` = 影子定义；端到端 `previewCustomDaoHql` 的表名/列名来自影子，SQL **不含** `实体不可解析`。**先红后绿**（当前实现应红）。
2. `库内宿主 + 无影子 → 解析到库内实体`（护栏：P0 不得误伤）。
3. `同 FQN 两份都在库 → 仍 fail closed`（反向契约保持：P0 不越权）。
4. `同 FQN 两份都是工程源码（跨模块）→ 仍 fail closed`（既有 `testContextlessDuplicateFqnIsNotArbitrarilyDeduplicated` 等值形态的回归）。
5. `工程内宿主 + 影子 → 与今天一致`（模块路径无回归；既有 `testDuplicateVisibleFqnUsesPlatformClasspathNotIterationOrder` 保持）。
6. `@NamedQuery 宿主同 FQN 双份`：取证现状（`NamedQueryLocator.kt:111-128` 直取类候选 + `singleOrNull`；预览侧 `SqlPreviewService.kt:1084` 多候选即 null），决定是否随 P0 一起收口（§7）。
7. `反编译宿主（mirror 元素）+ 工程同 FQN 影子 → 解析到工程影子`，且 SQL 与 sources jar 形态一致（W2 的验收用例；mirror 元素经 `getDecompiledPsiFile()` 取得）。

### 6.3 反向验证（每组贴两次读数）

- a) 摘掉 P0 预过滤 → 用例 1 变红（期望**恰 1 红**）；
- b) 把 P0 方向反转成「库优先」→ 用例 1 变红（证规则方向被钉住）；
- c) 放宽「无模块不借平台序」→ 既有 `testContextlessDuplicateFqnIsNotArbitrarilyDeduplicated` 应变红（证既有契约仍被守护）；
- d)（若做 P1）摘掉 owner 一致性判定 → 多 owner 用例变红。

### 6.4 门禁与真机

- 定向：`--tests …EntityClassResolverHeavyTest --tests …ShadowEntityResolveHeavyTest --tests …SqlPreviewEntityScopeHeavyTest`（heavy 桶；判据＝落盘 XML 条数，别信退出码——过滤式部分不匹配时 Gradle 静默跳过）；
- 收口：`testShards` 全量（勿降片），配置缓存须 `--no-configuration-cache`；
- 真机走查（验收档，建议）：runIde 沙盒里造「jar（源码附加）内含 DAO+HQL + 工程内同包同名影子」工程，点 jar 内 HQL 的 gutter 图标，确认列名来自影子、歧义气球不再出现。demo 仓可选加载体：`../mybatis-plus-sql-visualizer-demo` 的 `jpa-jar-model-lib` 增一个带 HQL 的 DAO 与 `-sources.jar`，宿主工程加影子实体。
- 真机走查（第二形态，同批做）：同一载体**去掉源码附加**，打开 `.class` 的反编译视图重复一遍上面的操作与判据——两种形态结论都要落档（用户 20261022 明确两者都要能识别）。

## 七、同族站点清单（同一消解口径的牵动面，逐条判）

以 `getClassesByName` / `searchScope` 为线普查（本轮只读核对，未改）：

| 站点 | 现状 | 本轮处置 |
| --- | --- | --- |
| `psi/jpa/EntityAliasSweep.kt:65` | 收口到 `resolveWithAliasCandidates` | **随 P0 自动修好** |
| `converter/jpa/JpaAliasTableResolver.kt:30/146`、`JpaFromEntityMapper.kt:70`、`JpaJoinOnResolver.kt:217` | 走 `EntityAliasSweep.resolve` | 随 P0 自动修好 |
| `psi/jpa/NamedQueryLocator.kt:120-124` | 直取类候选 + `singleOrNull()`；预览侧 `SqlPreviewService.kt:1084` 多候选即 null | **需决策**：随 P0 收口，或登记为已知边界 |
| `psi/jpa/HbmXmlEntityResolver.kt:71/116/154/196` | 用 `searchScope`，但按 XML 映射解析 | 不受本缺陷影响；若 P1 收窄 scope，须回归 HBM 用例 |
| `psi/EntityClassFinder.kt:259/293/353/370` | 表名→实体反查（库兜底，T-JarEmbedded） | 不在本轮；登记「同 FQN 影子下可能取错一份」为已知边界 |
| `ui/highlight/XmlCompletionSymbols.kt:136`、`ui/ref/MybatisXmlTargets.kt:163`、`ui/inject/ResultMapColumnCompletionContributor.kt:107`、`reverse/SqlToJavaConverter.kt:167/197/212` | 各自 `firstOrNull` / `allScope` | 不在本轮（不产出列映射，选错代价低）；登记 |

## 八、待验证前提（实施者先证伪再动手）

1. **P-1** 库源码文件（sources jar / 库源码根）上 `ProjectFileIndex.getModuleForFile == null`。平台 javadoc 已述，但夹具实证必做——它是整条链的地基。
2. **P-2（最关键）** 该场景 `resolveByName("WmsTask", 库内宿主)` 实测 `ambiguous == true`、`candidates.size == 2`。**若实测 candidates 已经为 1**（例如库定义被 scope 过滤），则本计划的根因判断被推翻，须回到取证重判。
3. **P-3** 工程输出目录的 `.class` 是否进短名索引（决定同 FQN 组是 2 份还是 3 份、P0 是否需要「源码优先」第二档）。
4. **P-4** `facade.findClass(fqn, moduleWithDependenciesAndLibrariesScope(m))` 在「工程源码 + 库二进制」同 FQN 下取工程源码（决定「P0 与模块路径同向」这条论证是否成立）。
5. **P-5** 用户 IDE 里 jar 源码的可见形态（sources jar 附加 / 反编译视图 / 完全无源码）。主路按 sources-jar 夹具覆盖；若用户看到的是反编译视图，`containingFile` 与 PSI 形态不同，须另测。
6. **P-6** `PsiTestUtil.addLibrary(module, name, libDir, classRoots, sourceRoots)` 能造出「库源码宿主」且其 PSI 被 `CustomDaoHqlLocator` 正常认领（否则夹具改走等价手法）。
7. **P-7** 库内宿主下是否另有降级来源：方言决策在无模块上下文时走 fallback（`SqlPreviewService.kt:254` + `ModuleIdentityResolver` 只查 content）——修完实体后需真机确认预览上不再有其他降级读数，避免「修完仍显示降级」的误判。
8. **P-8（W2 地基，两种 jar 形态的第二形态）** 反编译视图里 gutter 图标是否真的出现。平台代码指向「会」（daemon 的 `findFileToHighlight` 对编译文件取 `getDecompiledPsiFile`，LineMarker 从该 PSI 取 leaf；`ClassFileViewProvider.findElementAt` 也落 mirror），但**本仓库未实测**，且存在两处未定：①mirror 元素的 `containingFile` 是 `LightVirtualFile`，插件里凡按 `virtualFile` 取模块/方言/缓存键的地方都要复核；②`insert`/navigation 动作在只读 mirror 上的行为未验。**先证伪再决定是否补能力**。
9. **P-9** 反编译文本是否受「字节码无局部变量名」影响：`HQL` 常量若被内联/未保留（`String hql = "FROM ..."` 编译后在反编译文本里通常仍是字面量，但 `static final String` 可能被常量折叠）——若真机出现「字面量消失了」，属**另一条**缺陷（HQL 常量折叠），与 P0 无关，须单独立项，不得混入本修复的验收口径。

## 九、风险与已知代价

- P0 把「工程内容优先」当确定性使用，前提假设是**影子类确实在运行期生效**（工程输出先于依赖 jar）。若某工程手工把影子类所在模块排到依赖库之后（罕见），预览会与运行期不符——代价可接受，且与 IDE 自身的解析口径一致。
- P0 会让「工程里误放的同 FQN 类」压过库定义（此前是 fail closed 不选）。这是从「不选」变成「按运行期取胜者选」，方向上是纠偏而非引入不确定性。
- 多 owner 场景（P1）的口径分歧见 4.2，默认不做。
- 反编译形态凡是「只靠 mirror 才可达」的能力，都带一层额外依赖（平台 mirror 机制、反编译文本质量）。P0 不依赖它，故 W1 的收益与形态无关；W2 若发现某处只在 mirror 下失效，处置是**补兜底而不是改口径**。

## 十、实施记录（2026-10-22，已实施）

### 10.1 W0 取证矩阵：**计划的核心前提被实测修正**

在 `mpsv-shadow-entity` 工作树（分支 `t-shadow-entity-resolve`）用 heavy 夹具（javac 造库 class 根 + 库源码根、
`addModuleLibrary` 双根挂载）跑七个变体。宿主恒在库源码根（`inLibSrc=true`、`inContent=false`、`contextModule=null`，
P-1 成立）：

| 变体 | 形态 | 修复前读数 | 修复后 |
| --- | --- | --- | --- |
| V1 | 同 FQN 影子在**同一模块** | 候选 1，工程胜出，**不复现** | 不变 |
| V2 | 同 FQN 影子在**另一模块** | 2 候选 → 降级 | **修复** |
| V3 | 异 FQN 双 `@Entity` 同模块 | 2 候选 → 降级 | 不变（真歧义，fail closed 正确） |
| V4 | 异 FQN 双 `@Entity` 跨模块 | 2 候选 → 降级 | 不变（同上） |
| V5 | HQL 写全限定名 | 仍降级 | **修复** |
| V6 | 同 FQN 影子在**依赖 jar 模块的业务模块** | 2 候选 → 降级 | **修复**（最贴近用户） |
| V7 | 影子是**非实体类**（普通 DTO） | 2 候选 → 降级 | **修复** |

**P-2 部分被推翻**：原假设「同 FQN 双份一律 2 候选」只在**跨模块**成立。同一模块内
`PsiShortNamesCacheImpl.getClassesByName`（平台源码）自己对同 FQN 组做 classpath 序消解
（`scope.compare`），工程内容胜出——所以 V1 不复现。跨模块时 `ProjectAndLibrariesScope.compare`
对「order-entry 数不同」或「各 entry 比较结果不一致」的组**返回 0**（平台源码明写），于是两份都留。
⇒ 原计划的 P0 方向正确但**适用范围写错了**（不是「所有同 FQN」，是「跨模块/跨 order-entry 的同 FQN」）。

**新增发现（原计划没覆盖）**：V7 表明真正的病根比同 FQN 更宽——**任何同短名的非实体类都会制造假歧义**。
Hibernate 解析 `FROM <名>` 只认映射实体，普通 DTO 根本不是候选。

### 10.2 改动清单（2 个生产文件 + 2 个 bundle + 1 个新测试）

`psi/EntityClassResolver.kt`：
1. **P0a** `preferProjectContent`——逐 FQN 组内「工程内容优先于依赖库」（治 V2/V5/V6）；
2. **P0b** 同短名候选里**非映射实体不参与歧义**（`JpaMetadataReader.isLikelyMappedEntityClass`，治 V7）；
3. **门控 `context != null`**：无宿主元素（外部工具按名反查）时两条规则都**不生效**，保持既有
   「跨源码/库必须唯一」的 fail-closed 契约。这条门控是回归逼出来的（见 10.4 的首次失败）。
4. **P2** 歧义通知的**用户可见文案**带 `[工程]`/`[库]` 来源标签；**测试缝与日志保留原始 FQN**
   （标签属展示层，不进可编程契约——第一版把标签塞进 sink，破坏了既有用例的 FQN 断言）。

`messages/MpSqlVisualizerBundle{,_zh}.properties`：新增 `preview.entityNameAmbiguity.originProject/originLibrary`。

`src/test/.../psi/ShadowEntityResolveTest.kt`：5 条新护栏（同 FQN 依赖模块胜出、全限定名查找、
非实体影子不制造歧义、双真实体仍 fail closed、单份库实体正常解析）。

### 10.3 定向测试读数（干净沙盒，2026-10-22 21:5x）

`EntityClassResolverHeavyTest` **13/0/0/0**、`ShadowEntityResolveTest` **5/0/0/0**、
`BundleParityGuardTest` **5/0/0/0**、`SqlPreviewEntityScopeHeavyTest` 见 10.6。

### 10.4 反向验证（三组，各贴两次读数）

- **A 摘掉 P0a**：`ShadowEntityResolveTest` 5 例 → **恰 2 红**（`testSameFqnShadowInDependentModuleWins`、
  `testSameFqnShadowResolvesForQualifiedNameLookup`），其余 3 绿。恢复后回 5/0/0/0。
- **B 摘掉 P0b**：同一组 → **恰 1 红**（`testNonEntityShadowDoesNotCreateAmbiguity`）。恢复后回 5/0/0/0。
- **C 摘掉两处 `context == null` 门控**：`EntityClassResolverHeavyTest` 13 例 → **恰 1 红**
  （`testContextlessLookupRequiresUniqueAcrossSourceAndLibrary`），`ShadowEntityResolveTest` 仍全绿
  ⇒ 门控确实只被这条既有契约守护，反向对照成立。

### 10.5 实施期踩到的三个坑（都会伪装成产品缺陷）

1. **`PsiTestUtil.addLibrary(module, name, libDir, classRoots, sourceRoots)` 的 URL 拼接**：该重载内部是
   `parentUrl + classRoot` 字符串相加，`constructUrl` 只补 `protocol://`、不补尾斜杠 ⇒ 拼出
   `.../xxxclasses` 这种不存在的路径。改用 `ModuleRootModificationUtil.addModuleLibrary(module, name, classesUrls, sourceUrls)` 直接给 URL。
2. **javac 产物要递归 refresh**：`refreshAndFindFileByIoFile(classes)` 后不 `refresh(false, true)`，
   子目录不在 VFS 里 → 库类不进短名索引 → 探针读到「只有工程一份」（我第一次就是这样拿到假读数）。
3. **OOM 后残留测试 JVM 会锁住沙盒 jar**：`prepareTestSandbox` 报「请求的操作无法在使用用户映射区域打开的文件上执行」，
   真因是上一轮 OOM 留下的 `java.exe` 仍持着 `.intellijPlatform/sandbox/.../jsqlparser-5.3.jar`。
   处置＝按命令行核对自己工作树后 kill，再删整个 sandbox 目录（否则 `PersistentEnumeratorBase: CorruptedException`
   会把后续用例全染红，看起来像产品缺陷）。

### 10.6 W2 反编译形态取证（已做，20261022）

用户明确两种 jar 形态都要识别，本轮补齐第二形态的实测。夹具＝**只挂 class 根**（无 source 根）的库，
实体与宿主 DAO 都在其中；读数逐条：

| 读数 | 值 | 含义 |
| --- | --- | --- |
| `psiClass` / `isCompiled` | `ClsFileImpl` / `true` | 打开 `.class` 得到的是编译文件 PSI |
| `stubClass` / `stubMethods` | `ClsClassImpl` / 三个方法**全 body=false** | **桩 PSI 没有方法体** ⇒ 桩上不可能看见 HQL（机制证据，非推断） |
| `mirrorClass` | `PsiJavaFileImpl` | `getDecompiledPsiFile()` 给的是**真 Java PSI** |
| `mirrorText(head)` | FernFlower 反编译头 + `package com.acme;` | mirror 由反编译文本构成 |
| `mirrorCalls` | `[Collections.emptyList(), this.findByQuery(...)]` | mirror 里**调用表达式可见** |
| `locatorAccepted` | `FROM WmsTask task WHERE task.status = :status AND task.type = :type` | 生产 locator 在 mirror 上**正常认领** |
| `table` / `sql` | `wms_task_jar` / `SELECT task.id, task.status, task.type FROM wms_task_jar task ...` | 产物与 sources 形态**一致** |
| `DEGRADED` | `false` | 反编译形态**不降级** |

**结论**：反编译形态下「HQL 提取 → 实体解析 → 预览渲染」整链在 mirror PSI 上可达且产物正确
（与 §1 的平台机制推论一致）。**剩余未证**：编辑器 gutter 图标在反编译视图里是否真的出现——那属平台
daemon 的 LineMarker 行为，本仓 headless 夹具证不了，须真机走查（用户执行；demo 仓两种形态的走查步骤已备好）。

**P-9 附带证伪**：反编译文本里 HQL 字面量**没有**消失——`String hql = "..."` 编译后局部变量名退化成
`var1`，但字面量本身保留在反编译文本里，locator 的变量引用解析照常取到完整 HQL。故「常量折叠致字面量消失」
这条假设在本形态**不成立**，不必单独立项。

新增护栏 `DecompiledJarHostPreviewTest`（1 例）：桩 PSI 无 body（机制）+ mirror 可达（链路）+ 产物与
sources 形态一致（表名列名、不降级、无 fallback 表）。

### 10.7 未做 / 待办

- **P1（库文件 → 消费者模块上下文）未做**：按 §4.0 的决定单独一轮；且现在看它的收益面进一步收窄
  （P0a 已治同 FQN 跨模块），只剩「异 FQN 假歧义」那一类。**注意**：P0a 的「工程内容优先」是**全工程范围**的，
  不依赖消费者模块——多 owner 场景下 P1 若上，须先解决它与 P0a 的优先级（见 §4.2）。
- **真机走查未做**：两种形态的 gutter 图标出现与否、点击产物，均由用户执行。demo 仓复现用例已交付
  （子智能体，提交 `cb9279b2`/`089b5a06`/`9764328a`/`04e0c189`，两个模块 + 两种形态操作步骤 + 对照组）。
- `EntityClassFinder`（表名→实体反查）、`NamedQueryLocator` 等 §7 同族站点仍按原计划登记未动。
- 全量 `testShards` 已跑两次：第一次 **8162/0/0/9**（含取证期临时探针类），W2 换成正式护栏类后复跑
  **8122/0/0/9**（分片 1904+1560+1453+1504+1670 + external 31），两次零失败。**8122 为权威读数**
  （生产代码在两次之间未变，仅测试类替换）。

## 十一、附：本轮未做的事

未改任何生产代码；未跑 gradle（未做并行登记）；未提交、未推送；未在用户原始工程复现；未做真机走查；未预占 O/R/D/V 编号（缺陷级修复，按 T-JRebelPremainCrash 先例不占号）。
