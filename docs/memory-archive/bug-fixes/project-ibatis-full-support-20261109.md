---
name: project-ibatis-full-support-20261109
description: T-Ibatis2Support（20261109）已实施并合 master + CI 全绿（券 0/A/B/D/E/F/H，保真 20/20）；T-Ibatis2Gap 已获用户全量开工令（含沙盒验收走 skill、修到全绿、CI 门禁、再发内测版），三车道并行中——L2 券C+D 已交付（f2ae5fbb），L1 跳转图标 / L3 参数交互在途；含真机取证三步法与本批全部落点
metadata:
  node_type: memory
  type: project
  originSessionId: sess_c0d561d8-362b-43ca-913b-a6bfb07cc567
---

# iBatis 2.x 支持：已交付的第一批 + 新立的口子（2026-11-09）

## 一、已交付（master，已推双仓，CI 全绿）

T-Ibatis2Support 券 0/A/B/D/E/F/H 全部落地。提交：`3080cad3`（第一波合并）/`d593e253`（枚举产出点具名化）/`f6209b02`+`1c1c07ae`（接线+DTD+注入）/`c587e911`（Alt+Shift+Q 补候选）/`80e26053`（跳转）/`e6f5ccc9` 与 `30fdcab2`（文档）。

- **渲染核心**（券 B）：独立 `xml/ibatis/**` 解析/求值/渲染，**以 iBatis 2.3.4.726 官方 jar 做 golden oracle**（`StatementScope` + `initRequest` + `Sql.getSql`，不连库），20/20 形态逐字节一致。硬语义：`prepend/open/close/conjunction` 逐字拼接**不补空格**（真实输出有 `ANDORG_NAME` 这种粘连）、`#x:TYPE#` 两段式第二段永远是 jdbcType、`##`/`$$` 是转义、`$x$` null 静默丢弃、声明 `parameterMap` 的语句 `#` 不转 `?`、`nullValue` 是哨兵语义。
- **接线**（券 A）：`ui/gutter/IbatisXmlGutterLineMarkerProvider`（O(1) 标签名+父标签判定，**含 `<dynamic>` 子标签的语句也出图标**）+ 通用兜底 `isClaimedByKnownStructure` 让位（**让位直接复用接管方的 `isStatementAnchor`**，避免双图标/空洞）+ `ui/IbatisXmlPreviewService` + 设置 `ibatisXmlEnabled` + 触发面清单加行。
- **编辑器**（券 D）：注入宿主管宽到 `sqlMap`、`#name#` 四态词法 + `##`/`$$` 转义、折叠/补全根守卫。**iBatis 属性值一律不注入**（属性名与 MyBatis 大面积重叠，注入会改 `<mapper>` 既有行为；iBatis 无表达式语言）。
- **DTD**（券 E）：两份 jar 内权威副本随包（`sql-map-config-2` 网站版是 `sqlMap*`、jar 内 `sqlMap+`，以 jar 内为准），8 条标识经**声明式 `<standardResource url= path=>`** 映射（属性名是 `path` 不是 `resourcePath`），发行包 +9 KiB。
- **跳转**（券 F）：Ctrl+B 双向（`IbatisXmlIdReferenceContributor` + `IbatisDaoStatementGotoDeclarationHandler` + `ui/nav/IbatisXmlStatementTargets`），全部 soft 引用无目标零报红。
- **枚举**（券 H）：`SqlSource.IBATIS` 独立取值。
- 读数：最终合并态定向 **397 类 3906 例 0 失败**；推送轮 `push-test` 7/7；夜间全量 `nightly-full` **17/17**（含 extras 的 `testExternal` + agent）。

**注意**：`GiantFileBudgetTest` 只在 dev、不在 master；`<sql>` 前向引用我们能解析而引擎要求「声明在引用之前」——**我方更宽容但不给告警**，已登记待办。

## 二、用户真机反馈 → T-Ibatis2Gap（**已获全量开工令，三车道并行中**）

用户日常 IDEA `IU-263.7307.200` 测 `mybatis-plus-sql-visualizer-demo/ibatis2-demo/src/main/resources/sqlmap/User.xml`：参数没正常识别、左侧没有跳转图标。计划册：`docs/开发计划/20261109-2252-T-Ibatis2Gap-…-开发计划.md`（`4e56f253` 立项、`e0ce4505` 按用户回答更新、`585dbb32` P-6 销案）。

### 「装错版本」先排除（可复用的三步法）

1. `%LOCALAPPDATA%\JetBrains\IntelliJIdea<ver>\log\idea.log` 里 `Loaded custom plugins:` 那行读版本 —— 本机 22:41 会话加载的是 **`2026.11.9-beta.1`**（就是新构建）；
2. 对 `%APPDATA%\JetBrains\IntelliJIdea<ver>\plugins\<插件>\lib\*.jar` 做 `zipfile` 枚举，数 ibatis 相关条目（本例 59 个 + 两份 DTD 在位）；
3. `%APPDATA%\...\options\mp-sql-visualizer.xml` 里查 `ibatisXmlEnabled`/`showGutterIcon`/`gutterGroupMapperXml` —— **键不存在即默认（开）**。
   三步都排除后，才是真缺口。另：日志里 `Project store initialization started for path:` 可确认被测项目；本例那次 466 字符预览在 `cyzc-platform`（该仓 `<sqlMap` 命中 0），**不是 iBatis 预览**，别拿它当证据。

### 根因四条（均带 file:line）

- **G1 参数不可交互（主因）**：`SqlMapSqlRenderer.render(statement, params = null)`（`:58`）而预览服务调 `render(statement)`（`IbatisXmlPreviewService:69`）→ `<dynamic>` 条件在 null 下全不成立 → 零参数。**iBatis 链没有 MyBatis 那样的条件勾选/参数输入模型**（券 B 的 C-3 欠账）。与引擎行为一致，缺的是交互层。
- **G2 无 gutter 跳转图标（主因）**：iBatis 只有预览图标；MyBatis 有成对 `MapperXmlToJavaLineMarkerProvider`/`MapperJavaToXmlLineMarkerProvider`（`plugin.xml:245/247`，`MapperNavigationLineMarkerProviders.kt:52` 用 `isMapperFile` 门控 = MyBatis 专属）。券 F 只做了 Ctrl+B，没做行标记。
- **G3 `parameterMap` 参数名错位**：渲染器把 `<parameter>` 全部塞进 `paramNames`（4 个），而语句体只有 3 个手写 `?` → 数量不符 → 回落 `param1..N`。引擎按位绑前 N 个，**本可按位对齐**。
- **G4/G6 降级是半截 SQL 且文案说谎**：`SqlMapSqlRenderer.kt:63-67` 的 `runCatching{...}.getOrElse{}` 只拼**顶层文本节点** → `<include>`（解析期已内联成文本）保留正确，`<dynamic>/<iterate>` 是标签节点被**整段丢弃**；同时 `warn("渲染降级（按原文保留 # 直替，参数名不可知）…")` 与事实不符（既没保留 `#` 也没保留标签），面板还同时显示「无未解析参数」与「参数名不可知」。违反「降级可用 + 失败可见」。

### 用户已拍板的三点 + 一条已销案的虚警

1. **只是没有跳转图标**（预览图标正常）⇒ 「模块未导入/content root」假设（G5）销案；
2. 参数交互粒度要**「能填具体值」**（不只是条件勾选）⇒ D-1 定为「条件勾选 + 值可填」；
3. iterate 现状＝截图（半截 SQL + 上述误导文案）；
4. **P-6 销案（虚警）**：用户先说「字段名和表名也不对」，随后自行订正——「没有不对，我一开始以为大小写有问题，目前来看是正确的，因为 xml 中写的就是大写」。截图里 SQL 的标识符与 `User.xml` 逐字一致，**不生成任何改动项**。

计划分券：A 条件行模型+值可填 / B 跳转图标对（复用 `IbatisXmlStatementTargets`，别重写）/ C parameterMap 按位对齐 / D 降级失败可见（P0 升级）/ E 已销案。

### 开工令（2026-11-09 深夜，用户原话要点）与三车道

用户 `/goal`：**在主分支实施开发计划的所有内容**；可派多智能体后台并行、独立分支+工作树防冲突；最后定向测试，主控合并并清理工作树/分支；**用沙盒验收、记得用 skill（ide-ui-acceptance）**，有问题修掉继续验收直到全功能正常；**全量门禁交 CI 减少本地占用**；**问题全修完后再发布一个内测版**；「允许授权」。基线 `585dbb32`，三工作树：`.worktrees/ibatis-{navicon,paramalign,paraminput}`。

**L2 券C+D 已交付**（`t-ibatis-paramalign`，`f2ae5fbb` 实现 + `21a00086` 探针，5 文件 +364/-6）：
- 券 C：`paramNames` 取**前 N 个**声明映射（N = string-aware 位点数，复用 `sql/PlaceholderPositions.kt`；`##` 转义与字符串内 `#` 不算），剩余透出新字段 `unusedDeclaredParams`；位点数 ≥ 声明数时全部消费（与 oracle 一致）。
- 券 D：`renderBody` 按**顶层节点逐个** try/catch → `IbatisUnrenderedNode(tagName, topNodeIndex 0基, reason, property, message)` 后原样上抛（回退拼装逐字未动）；新枚举 `IbatisRenderFailureKind{ITERATE_NOT_COLLECTION, RENDER_ERROR, PARSE_ERROR}`——**故意没有 UNKNOWN_TAG**（未知标签解析期已降级为文本，留空档枚举会触 EnumReachabilityGuard 零产出）；新异常 `IbatisIterateNotCollectionException` **直接继承 RuntimeException**（`IbatisRenderException` 是 final，写集禁开 open）。文案分档走 bundle 三键（`ibatis.render.degrade.iterateNotCollection/abortedAtTag/abortedUnknown`），全部不再含「按原文保留 # 直替」。
- 读数：93 例 0 失败；**fidelity 20/20 零回退**（fidelity 目录零改动）；反向 2 枚（摘对齐 2 红 / 摘结构化 2 红，均非钝刀）。
- 已知降级口径：`unrenderedNodes` 无原文行号/偏移（DOM 不留位置），给「顶层节点序 0 基」，KDoc 已写明。
- not run：`ui.IbatisXmlPreviewServiceTest`（属 L3 写集，paramNames 回落逻辑会自然改善，交收口复跑）、全量（交 CI）。
- 方法论：改前基线跑被在途编辑污染（编译失败）时，**改前读数可由反向探针的「机制禁用」跑代证**（旧口径代码上 17 条既有断言全绿）——不必强求重跑基线。

L1（`t-ibatis-navicon`，券 B 跳转图标对）与 L3（`t-ibatis-paraminput`，券 A 参数交互）**在途**。L3 的关键机制决定：iBatis 条件求值走 `IbatisProbe`（**原生支持 Map 参数**：`containsKey`/取值）⇒ **条件开关 = Map 里放不放 key**，`isGreaterThan` 数值形态需修（Map 下取到 String 会字典序比较，`"9" > "18"` 误判成立）；重渲染接缝优先调查「`PreviewResult` 加带默认值的渲染回调」而非抽象 `xmlModel`（面板 37 处引用）。后续阶段：合并 → 定向 → **沙盒验收走 ide-ui-acceptance skill**（GUI 操作者同时刻唯一）→ 修到全绿 → 推送+CI 门禁 → **发内测版**（`preview.update.json` 不受限，`update/update.json` 仍用户专属）。

## 三、demo 侧（已完成）

demo 仓新增 `ibatis2-demo`（`94b6285a`：17 条语句 / 20 次渲染全通，`buildSqlMapClient` 成功＝语料语法合法），并修掉 `mybatis-plus-demo/pom.xml` 既有 `Duplicated tag: 'profiles'`（`16f4310`）；Maven 三验（模块编译 / root reactor 18 模块 / **离线复编**）全 SUCCESS。

**Why**：这一批既有「已验证交付」也有「用户真机打回的口子」，两者混在一起最容易误判——下轮若只读记忆里的「已实施」就会以为不用再动；只读计划册又会重复排查版本问题。而「用户报的症状」里还会混入虚警（P-6 大小写），不先核对源文件原文就会凭空多出一条改动项。

**How to apply**：动 iBatis 前先分清是改第一批（有 oracle 对照）还是补 G1~G6（交互与可见性面）；新增 gutter provider 必须同批登记 `GutterGroups` 并把扫描面扩进 `IbatisGutterHotPathTest`；判「插件没生效」照上面三步法，别先怀疑代码；用户报「字段名/大小写/标识符不对」时**先拿源文件字面量逐字比对**，本例即为虚警。

相关：[[project-pending-plans]]、[[pitfall-ibatis-log-format-vs-mybatis-param-parser]]、[[pitfall-regression-false-failure-flakes]]、[[feedback-ci-trigger-push-cadence-and-watcher]]。
