# T-Ibatis2Gap：iBatis 预览参数不可交互与无跳转图标（2026-11-09 真机反馈）

- 立项：2026-11-09 22:52
- 状态：**实施完成并已发布（2026-11-10，master；券 A/B/C/D 全落地 + 沙盒 GUI 验收通过 + 随 `v2026.11.10-beta.1` 内测发布）**——发布 run `42693049970`（mode=release）17/17 全绿，GitHub Release 与 Gitee 发行版 1292357 落位，实施记录见 §八
- 来源：用户 2026-11-09 在日常 IDEA 2026.3 上测 `mybatis-plus-sql-visualizer-demo/ibatis2-demo/src/main/resources/sqlmap/User.xml`，反馈「还是没有完整支持：一些参数没有正常识别到，左侧也没有跳转图标」
- 前序：`20261109-1130-T-Ibatis2Support-…-开发计划.md`（券 0/A/B/D/E/F/H 已合入 master 并过 CI）

---

## 零、证据分级

| 档 | 含义 | 本册占比 |
|---|---|---|
| **实测** | IDEA 日志、已装 jar 字节层、设置文件、真实文件内容、源码逐行 | 全部结论 |
| **文档推断** | 由实测推语义，未被真机复现 | G4/G5 的「影响程度」 |
| **未验证** | 需在用户机器上跑一次才能定论 | G5 项目模型是否干扰图标 |

---

## 一、现场取证（先排除「装错版本」这条）

| 事实 | 读数 | 怎么取的 |
|---|---|---|
| 运行的是**本次新构建**，不是旧版 | `SQL Visualizer (…mybatis-plus-sql-visualizer, 2026.11.9-beta.1)`，22:41:37 那次启动加载 | `%LOCALAPPDATA%\JetBrains\IntelliJIdea2026.3\log\idea.log` 第 34101 行（22:41 启动会话）；前两次启动（16:27 / 16:55）加载的是 2026.11.8 |
| jar 内确实有本次全部新件 | 2234 条目，其中 **59 个 ibatis 相关**：`IbatisXmlGutterLineMarkerProvider`、`IbatisXmlPreviewService`、`IbatisDaoStatementGotoDeclarationHandler`、`IbatisXmlStatementTargets`… | `zipfile` 枚举 `plugins/mybatis-plus-sql-visualizer/lib/mybatis-plus-sql-visualizer-2026.11.9-beta.1.jar` |
| DTD 随包在位 | `dtd/ibatis/sql-map-2.dtd`、`dtd/ibatis/sql-map-config-2.dtd` 均存在 | 同上 |
| 该会话**没有**本插件的任何异常 | 最新会话 78 条 ERROR/SEVERE 全部来自 `MCP Server` 与 `ComponentStoreImpl`；`visualizer|ibatis|sqlMap` 关键字命中 0 | `idea.log` 34101 行之后过滤 |
| 相关设置都在默认值（开） | `options/mp-sql-visualizer.xml` 里**没有** `ibatisXmlEnabled` / `showGutterIcon` / `gutterGroupMapperXml` 任何一项 ⇒ 全部走默认 | 同上 |
| 被测项目已打开 | 最新会话打开了两个项目：`…\mybatis-plus-sql-visualizer-demo` 与 `…\cyxny\cyzc-platform`；iBatis 文件在**前者** | `idea.log` 的 `Project store initialization started for path` |
| 那次 466 字符的预览**不是** iBatis 文件 | `cyzc-platform` 全仓 162 个 xml，`<sqlMap` 根命中 **0**；22:42 那次预览走 `cyzc_db1@121.40.45.91`（PostgreSQL） | grep + `idea.log` 的 `[M36] prepareDefaultConsole: sqlLen=466` |

**结论：不是版本问题、不是崩溃、不是设置被关。** 是我们**实现到的那一层之外**确实还缺东西。

---

## 二、根因

### G1（主因，解释「一些参数没有正常识别到」）预览固定用**无参基线**，且没有参数输入/条件勾选

- `xml/ibatis/SqlMapSqlRenderer.kt:58` —— `fun render(statement: IbatisStatement, params: Any? = null)`
- `ui/IbatisXmlPreviewService.kt:69` —— 预览服务调用的是 `SqlMapSqlRenderer().render(statement)`，**不给 params**
- 结果：`User.xml` 里 `selectByCondition` 的四个条件
  （`<isNotNull property="id">`、`<isNotEmpty property="username">`、`<isEqual compareValue="1">`、`<isGreaterThan compareValue="18">`）
  在 `params == null` 下**全部不成立** → 渲染成 `SELECT … FROM T_USER ORDER BY ID`，**零参数**。

这不是渲染器错——iBatis 真引擎在 null 参数下输出完全相同（保真对照 `SqlMapFidelityTest` 20/20 逐字节一致）。**缺的是交互层**：

- MyBatis 链有 `xml/XmlPreviewModel` + `SqlPreviewPanel` 的**条件勾选行**（勾上 `<if>` 就能看到那段 SQL 与它带来的参数）；
- iBatis 链**没有**等价模型（券 B 的 MVP 范围明确把「条件勾选模型」列为后续，见前序计划册 §3.1 券 B 的 C-3 子项与 §8.3）。
- 因此用户视角就是：打开带动态条件的语句 → 看不到参数、也看不到那些条件对应的 SQL 片段 → 「参数没识别」。

### G2（主因，解释「左侧也没有跳转图标」）iBatis 完全没有 gutter 跳转图标

| provider | 是否存在 | 判定 |
|---|---|---|
| `IbatisXmlGutterLineMarkerProvider` | ✅ 有 | **预览**图标（点开预览窗），`plugin.xml:270` |
| iBatis 的「XML 语句 → Java 调用点」箭头 | ❌ **没有** | — |
| iBatis 的「Java 调用 → XML 语句」箭头 | ❌ **没有** | — |
| MyBatis 侧的对应成对实现 | ✅ | `MapperXmlToJavaLineMarkerProvider`（`MapperNavigationLineMarkerProviders.kt:19`，`:52` 处 `MybatisXmlTargets.isMapperFile(...)` 门控 = **MyBatis 专属**）、`MapperJavaToXmlLineMarkerProvider`（`:73`），注册在 `plugin.xml:245/247` |

券 F 只做了 **Ctrl+B / Ctrl+Click**（`IbatisXmlIdReferenceContributor` + `IbatisDaoStatementGotoDeclarationHandler`），**没有做行标记**。MyBatis mapper 文件左侧有成对箭头，iBatis 没有——用户按同一直觉比较，自然判为「没支持」。这是**设计缺口，不是 bug**。

### G3（次因，同一观感问题）`parameterMap` 语句的参数名错位

`SqlMapSqlRenderer.kt:139-155`：声明了 `parameterMap` 的语句，我们把 `<parameter>` 的 `property` 全部收进 `paramNames`；但这类语句体内是**手写 `?`**，`#x#` 原样保留。以 `User.xml` 的 `viaParameterMapRawHash` 为例：`parameterMap` 声明 4 个参数，语句体只有 3 个 `?` → 数量不匹配 → `IbatisXmlPreviewService.kt` 回落成 `param1..N` + 「参数个数不符」告警。

引擎实际是按位置把前 N 个映射绑到前 N 个 `?` 上，所以**名字本该可用**（`param1..3` 应显示成 `username` / `email` / `age`）。

### G4（次因）`<iterate>` / `<dynamic>` 在无参下的降级没有可读提示

`User.xml` 的 `selectByIterate` 用 `<iterate property="ids">`。`params == null` 时引擎语义是**硬失败**（`SqlMapException: ParameterObject or property was not a Collection…`），我方降级成语句级 `IbatisRenderException` + 告警。用户看到的是一个没有参数的报错式结果，而非「需要先给 ids 才能展开」。

### G5（**已排除**，2026-11-09 用户确认「只是没有跳转图标」）`ibatis2-demo` 未导入为模块

用户确认**预览图标存在**，说明该文件在 content root 内、provider 正常工作，项目模型干扰不成立。G5 可从根因里划掉，仅在排查同类「全无图标」时留作参考。

> 原始怀疑依据（保留备查）：`.idea/misc.xml` 的 `MavenProjectsManager.originalFiles` 只列 5 个 pom、无 `ibatis2-demo/pom.xml`；`.idea/modules.xml` 只登记一个模块。若目录不在 content root 内，平台 gutter 能力会整体受限。

### G6（截图新增，真缺陷）降级路径把标签子树**整段丢弃**，且告警文案与行为不符

`xml/ibatis/SqlMapSqlRenderer.kt:63-67`：

```kotlin
val body = runCatching { renderBody(statement, params, mapped, warn) }
    .getOrElse { error ->
        warn("渲染降级（按原文保留 # 直替，参数名不可知）：${error.message}")
        statement.body.filterIsInstance<IbatisTextNode>().joinToString("") { it.text }
    }
```

任一节点渲染抛错时，回退只拼接**顶层文本节点**：

- `<include>` 在解析期已内联成文本 → 展开正确（截图里 `ID, USER_NAME, EMAIL, AGE, STATUS` 是对的）；
- `<dynamic>` / `<iterate>` 是**标签节点**，被整体丢弃 → 截图里 `WHERE ( ... )` 整段消失；
- 因此告警里的「按原文保留 `#` 直替」**与事实不符**：既没有保留 `#`，也没有保留标签内容；「参数名不可知」也不准（`ids` 是知道的，缺的是值）；
- 面板同时显示「无未解析参数」与「参数名不可知」，**自相矛盾**。

这违反本仓既有的「降级可用 + 失败可见」口径：半截 SQL 看起来像一条合法语句，用户无法从 SQL 本身看出丢了半句。正确形态应是**可见的失败**（明确标注哪一段没渲染、为什么），而不是静默产出半截 SQL 再配一句不准确的说明。

---

## 三、方案（分券）

### 券 A（P0）参数可交互：条件行模型 + 参数输入

目标：把「无参基线」从**唯一输出**变成**初始态**，让用户能把条件打开、把参数填上、SQL 随值变化。

1. **条件行模型**：按 `<dynamic>` 的子标签建立可勾选行（`isNotNull` / `isNotEmpty` / `isEqual` / `isGreaterThan` …），每行带
   - 触发条件摘要（属性名 + 比较值），
   - 该行展开后的 SQL 文本与它引入的参数名，
   - 默认态＝无参求值结果（保持现状口径）。
2. **参数输入**：条件行的比较值（`compareValue`）与 `#x#` 的值可填；填了即按该值求值（`isEqual compareValue="1"` 勾上时 `status` 参与 WHERE）。
   - 复用既有 `sql/ParameterReplacer` + `sql/ParamHint`，不要新造回填引擎。
3. **`<iterate>` 单独一档**：给「值列表」输入（逗号/换行分隔），按列表长度决定迭代次数与参数名（`ids[0..n-1]`）。
4. **落点**：本券不做新的 UI 控件族——照 MyBatis 链的条件勾选行形态做（`XmlPreviewModel` + `SqlPreviewPanelParamPanel` 的既有渲染路径），iBatis 侧新增一个模型适配层。
5. **前置成本**：`xmlModel` 在面板有 37 处引用（集中在 `ui/SqlPreviewPanelParamPanel.kt` 15 处），需先把「条件勾选模型」抽成接口，再让 iBatis 模型实现它——与券 B 当初同一条欠账。

### 券 B（P0）gutter 跳转图标对（观感对齐 MyBatis）

1. `ui/gutter/IbatisXmlToJavaLineMarkerProvider`：锚 `<select|insert|update|delete|statement|procedure>` 的 `id` 属性值 → 落到 Java 侧 `SqlMapExecutor` 调用（`queryForObject("ns.id", …)` 等）的字面量实参。
2. `ui/gutter/IbatisJavaToXmlLineMarkerProvider`：锚 Java 调用首参字符串 → 落到 XML 语句。
3. **复用券 F 已有的解析**：`ui/nav/IbatisXmlStatementTargets` 已经能按 `ns.id` / 裸 id 定位语句，**不要重写**；本券只加行标记 + 图标 + 让位门。
4. 图标：复用 `MpvsIcons.MapperMethod` / `MapperStatement`（MyBatis 那对已用），保持视觉一致；`GutterGroups` 仍归 `mapperXml` 组。
5. 红线：`getLineMarkerInfo` **O(1)**（照券 A 已落地的 `isStatementAnchor` 与 `IbatisGutterHotPathTest` 的扫描口径）；跨线程 PSI 读取按 `PsiSnapshot` 协议。

### 券 C（P1）`parameterMap` 语句参数名按位对齐

`SqlMapSqlRenderer.renderStatic` 的 `declared != null` 分支：把 `parameterMap` 的映射按**位置**对齐到语句体里手写 `?` 的个数（取前 N 个），而不是把全部映射塞进 `paramNames` 导致数量不符回落 `param1..N`。对齐不上时保留告警。

### 券 D（P0，升级：原为 P2 文案）降级必须**失败可见**，且文案必须与行为一致（解 G6）

三件事，同批：

1. **不再静默产出半截 SQL**：`renderBody` 抛错时，把**已渲染部分**与**未渲染的节点**都标出来——面板上明确显示「`<dynamic>` 段（第 N 行起）未渲染：`iterate` 的 `property='ids'` 取值不是集合」，而不是给一条看着合法的完整语句。
2. **文案按失败形态分档**（现在是恒定拼一句「按原文保留 `#` 直替，参数名不可知」，与实际三种情况都不符）：
   - 无参基线导致条件不成立 →「该语句含动态条件，需提供参数才能展开」（不是错误，是初始态）；
   - `iterate` 取值非集合 →「`ids` 需要值列表」；
   - 真正的解析/配置错 →「按原文保留」。
3. **面板一致性**：左侧「无未解析参数」与顶部「参数名不可知」不得同时出现；`<iterate>` 场景下左侧应显示**该语句需要的参数名**（`ids`），只是值为空。

附：`<iterate>` 在 `params` 为 null 时直接抛异常是否合理，也要一并定——引擎确实抛，但预览应把它降级成「待填值」而不是「渲染失败」。

### 券 E（**已关闭**，原为零代码排查）排除 G5 干扰

用户 2026-11-09 确认预览图标存在于该文件，等于证明文件在 content root、gutter provider 正常工作 ⇒ G5 不成立，本券销案，不再执行。保留记录仅为同类「全无图标」问题提供排查入口（项目模型/模块导入）。

---

## 四、待验证前提

| # | 前提 | 状态 |
|---|---|---|
| P-1 | G5（模块未导入导致整体无图标） | **已排除**（2026-11-09 用户确认只有跳转图标缺失，预览图标正常 ⇒ 文件在 content root、provider 工作正常） |
| P-2 | 「左侧没有跳转图标」是否只指跳转箭头 | **已答：只是没有跳转图标** ⇒ 不存在「连预览图标都没有」的第三根因，G5 关闭 |
| P-3 | 参数交互粒度 | **已答：要「能填具体值」**（不是只要勾选）⇒ D-1 取后者 |
| P-4 | `selectByIterate` 真机显示 | **已答（截图）**：降级成 `SELECT ID, … FROM T_USER`（`<dynamic>` 整段丢失）+「渲染降级（按原文保留 # 直替，参数名不可知）」⇒ 新增 G6 |
| P-5 | 真实项目（`cyzc-platform`）里是否也有 iBatis 文件 | 本轮 grep `<sqlMap` 命中 0；若无，则只有 demo 可测 |
| P-6（**已答：不是缺陷**） | 「字段名和表名也不对」 | 用户 2026-11-09 订正：初以为大小写有问题，核对后确认**正确**（`User.xml` 里本就写大写，SQL 与文件逐字一致）。该项销案，不生成改动项；仅记录「方言兜底 MySQL 时标识符不做引号/大小写改写」为既有正确行为 |

---

## 五、验收

1. **券 E**：`ibatis2-demo` 成为模块后，`User.xml` 上 `<select>` 行左侧有**预览图标**（这条也顺带回答 P-2）。
2. **券 A**：`selectByCondition` 打开后，面板列出四个条件行；勾上 `<isEqual compareValue="1">` 后 SQL 出现 `AND STATUS = ?`，参数面板出现 `status`；勾上 `<isGreaterThan compareValue="18">` 后出现 `AND AGE > ?` 与 `age`。填值后 SQL 随值变化。
3. **券 A（iterate）**：`selectByIterate` 填入 3 个 id → SQL 出现 `( ID = ? OR ID = ? OR ID = ? )`，参数名 `ids[0..2]`。
4. **券 B**：`User.xml` 的 `selectById` 行左侧出现跳转箭头，点击落到 Java DAO 的 `queryForObject("user.selectById", …)`；反向从 Java 调用点落到 XML 语句。
5. **券 C**：`viaParameterMapRawHash` 的参数名显示为 `username` / `email` / `age`（不再是 `param1..3`）。
6. 定向：`xml.ibatis.*`、`ui.gutter.*`、`ui.IbatisXmlPreviewServiceTest`、`ui.ref.*`、`ui.nav.*` 全绿；反向验证每条修复至少一条「摘掉必红」。
7. 全量与门禁交 CI（推送轮 + 夜间轮）；真机观感由用户最终判。

---

## 六、风险与口径

1. **不要为了让观感「看起来支持」而伪造参数值**：`params == null` 时给空条件的 SQL 是**正确**行为（与引擎一致），错的是没有交互入口。任何「瞎猜参数名/默认值」都会产出与真实执行不一致的 SQL。
2. **`xmlModel` 抽象是共享面**：37 处引用、MyBatis 链重度依赖；抽接口必须先跑既有 `xml.*` + `ui.*` 定向建基线，改后逐位比对（券 B 当初的 C-3 欠账同理）。
3. **图标性能红线**：新增两个 provider 后 gutter 热路径的调用数翻倍，`IbatisGutterHotPathTest` 的扫描面必须同步扩到新 provider，否则等于给性能回归开了口子。
4. **观感对齐 MyBatis 是合理预期**：MyBatis mapper 文件有成对箭头 + 条件勾选，iBatis 只有预览图标 + 无参基线，用户按同一产品内的一致性比较，判定「没支持」是对的。这不是用户误解。
5. **前序交付口径复核**：券 B 把「条件勾选模型」列为后续（§3.1 C-3 子项）、券 F 只做 Ctrl+B —— 本册是**兑现这两处欠账**，不是推翻前序结论。

---

## 七、决策点

| # | 决策 | 推荐 | 理由 |
|---|---|---|---|
| D-1 | 券 A 的交互粒度 | **条件勾选 + 值可填**（用户 2026-11-09 明确要「后者」） | 勾选解决「看不看得见」，填值解决「能不能验证」；两者都要 |
| D-2 | 券 B 的图标形态 | **完全复用 MyBatis 那对图标与让位门** | 同一产品内一致优先（既有反馈口径：新增 UI 交互照抄既有同类实现） |
| D-3 | 是否先做券 E（零代码排查） | **先做** | 若 G5 成立，能立刻把「两个都没有」收窄一半，避免白改 |
| D-4 | 目标分支 | **master 起步**（同前序） | 与上一批一致，减少分支拓扑 |
| D-5 | 是否顺带补 `<sqlMapConfig>` 文件的支持（全局 id 表） | **不做** | 与本次反馈无关；且 `cyzc-platform` 里无 sqlMap 文件，需求未证 |

---

## 八、实施记录（2026-11-10，三车道并行 + 沙盒验收 + 随内测版发布）

用户拍板：按推荐执行全部券、master 起步、三车道并行（独立工作树/分支）、主控合并清理、只跑定向、沙盒用 `ide-ui-acceptance` skill 验收修到全绿、全量交 CI、发内测版（已授权）。

### 8.1 实施与验证

| 车道 | 分支 / 提交 | 内容与读数 |
|---|---|---|
| L2 | `t-ibatis-paramalign` / `f2ae5fbb`+`21a00086` | 券 C 按位对齐（string-aware 位点计数，`unusedDeclaredParams` 透出）+ 券 D 降级结构化（`degraded`/`unrenderedNodes`/`IbatisRenderFailureKind`，文案分档）；93 例 0 失败，保真 20/20 不动；反证 2 条 |
| L1 | `t-ibatis-navicon` / `d04033aa`+`896a1b4d` | 券 B 双向跳转箭头（点击期词索引解析，热路径零查询；热路径守卫扩面 7→9 例）；基线 133 例→152 例 0 失败；反证 3 条。**收尾**（主控发现的缺陷）：让位门与 MyBatisCodeHelperPro **解耦**（竞品无 iBatis 能力，共用让位门会让典型用户看不到箭头；用户环境两者同装）+ 竞品在位用例 + 反证（恢复让位恰 1 红）+ 独立 i18n 键 + 入口方法集单一事实源 |
| L3 | `t-ibatis-paraminput` / `92c515ec`+`367561f3` | 券 A 参数行/条件行模型（`IbatisParamModel`）+ Map 装配（勾选=放 key）+ 数值比较修正 + `PreviewResult.rerender` 回调接缝（`PreviewRerenderInput`，MyBatis 链零感知）+ 面板呈现；批 1~5 约 1380 例全绿；反证 2 条。实现期自查修掉两个真缺口：条件取消后同名占位符仍进 Map、`$` 站点在条件子树内漏闸 |

### 8.2 合并与合并态定向

三路全合入（`00fd57d4` / `84efd117`）；冲突均在 `reverse-probes/README.md` 与两份 bundle（追加型，并集解）。合并态定向 435 类 4461 例——唯一红＝守卫 `BannedApiScanRuleTest` 抓 L2 新增 `catch(Throwable)` 未判 fatal（`b3958e35` 修复后守卫 12/12 + iBatis 全套 + 保真 20/20 复绿）。L3 明示的「合流后需重跑」联动在 CI 上二次兑现（见 8.4）。

### 8.3 沙盒 GUI 验收（`ide-ui-acceptance` skill，51+若干证据帧）

- **首轮六项**：A1 XML 行预览+跳转箭头并存且点击落位 ✅、A2 Java 反向箭头 ✅、A3 四条件勾选+填值 SQL 随值变 ✅、A4 iterate 三段 OR 展开（不再半截）✅、A5 真实参数名 ✅、A6 降级可见 部分（**UI-001**：供值后横幅仍称「未完整渲染」且刷新不消失）。
- **UI-001 根因**：`updateMeta` 把基线结果的告警又并回来——修复＝rerender 链以本轮 fresh 结果为唯一事实源；另加 `informationalWarning` 分档（ℹ 说明 vs ⚠ 未完整还原；parameterMap 的引擎行为说明不再冒充失败）。
- **复验**：R1 三态（基线 ℹ 提示 → 供值消失 → 清空回归 → 刷新不漂移）/ R2 ℹ 前缀 / R3 无横幅——三条全过，零新缺陷，UI-001 闭环。

### 8.4 CI 与发布（两次事故 + 恢复，均按既有 runbook）

| 轮次 | sha | 结果 |
|---|---|---|
| 推送轮 push-test | `e6f5ccc9`（前批） | 7/7 |
| 门禁第 1 轮 | `52f84f95` | 红在快桶腿：`IbatisXmlPreviewServiceTest` 数量不符夹具是**按位对齐前**的旧语义（L3 预警过的合流联动，本地基线绿/CI 红的根因）→ 夹具改真错配形态（声明 1/位点 2）+ 补按位对齐信息级正例（`7d640980`，31/0） |
| 门禁第 2 轮 | `7d640980` | 15/15 全绿 |
| 发布第 1 轮 | `7d640980` | 17/17（含 publish），但 `inspect-plugin-zip.py` 对 Release 正文 FAIL——本版块含全角**冒号**（L25 第 2 例：beta.1 是括号；守卫测试绿 ≠ 检查器过，检查器需先有 zip）→ 文案修正（`44ab2393`）+ 本地 buildPlugin+检查器 PASS |
| 门禁第 3 轮 | `44ab2393` | 15/15 全绿 |
| 发布第 2 轮 | `44ab2393` | 17/17（含 publish） |

**发布落位**：tag `2026.11.10-beta.1` 经两次前移最终指向 `44ab2393`（双仓 ls-remote 回读一致）；GitHub Release 439581247（prerelease，附件 7,852,093 字节）；期间发现 GitHub `/releases/tags/{tag}` 对**强移后的 tag** 恒 404（`/releases` 列表在、PATCH 重关联无效）→ 删 release 对象 + `mode=publish` 重建（run `42696477178`）后 by-tag 恢复 200；在售 zip 复检 PASS；Gitee 发行版 **1292357**（`gitee-release.py --from-github --yes`，sha256 `8185814a65e5e4fe…` 校验 OK，`--verify-only --prerelease` [3/3] Verified，downloadUrl 与 `preview.update.json` 逐字一致）；`update/update.json` 未动（待用户本人提升）。

### 8.5 遗留与责任项

- **北京测试服务器（103.36.220.245）整机离线**：9 实例端口 + SSH + ping 全不通（对照机 114.66.55.245 全通），导致夜间轮 `dialect`（矩阵 9 实例连接秒失败，65s 即终）与 `e2e`（demo 应用 120s 未就绪，库指向 CI 本机、与北京无关，属可重试抖动）两腿红。**服务器需用户到供应商控制台处置**；恢复后重跑夜间轮即可闭合。
- `feature/kotlin-to-java` 当日多轮 CI 红，属 k2j 车道事项，未动。
- 券 G（`parameterClass`/`parameterMap` 完整类型映射）、`<sqlMapConfig>` 全局 id 表、跨文件 `<include>`——维持待办。
- 快桶腿在 CI 上单独跑 `IbatisXmlPreviewServiceTest` 曾红（本地绿）：分桶 JVM 共享状态或 locale 差异的排查入口已留（本册 8.4 第 1 行），后续同类问题先查这里。

派活纪律沿用前序：独立工作树 + 扁平分支名、只跑定向、`parallel_tasks.py` 登记、禁 `git add -A`、不 push 不合流、报告须给读数与反向验证。
