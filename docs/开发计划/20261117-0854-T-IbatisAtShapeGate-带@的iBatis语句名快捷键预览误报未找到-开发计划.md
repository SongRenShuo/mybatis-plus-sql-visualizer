# T-IbatisAtShapeGate：带 `@` 的 iBatis 语句名 Alt+Shift+Q 预览误报「未在光标处找到可预览的 SQL 源」——开发计划

- 立项：2026-11-17
- 触发：用户报障——同一条 iBatis DAO 调用行，点击 gutter 预览图标能正常打开预览，光标定位在语句名上按 Alt+Shift+Q 却弹「未在光标处找到可预览的 SQL 源」信息提示
- 涉及语句形态（用户截图）：`baseDao.queryForObject("cQAccountTypeByAccountType@custmg.qAccountTypeByAccountType", inMap)`——`IBaseDao` 自研封装，底层 `SqlMapClientTemplate` → `SqlMapExecutor.queryForObject`，语句名是 **`组名@命名空间.语句id`** 形态（`@` 是 namespace 段的一部分，站点自研框架的路由约定）

## 一、结论（一句话）

**快捷键路径的形状门正则不认 `@`，把这条调用让给了 JDBC 通道；JDBC 通道认领后发现字符串不是 SQL，返回 null，弹出「未找到」错源提示。而 gutter 路径根本没有这道正则，所以图标正常、点击正常。**

两条路径用的是**不同的判定集**，`@` 形态恰好落在缝隙里。

## 二、两条路径逐段对账（源码实证）

### gutter 路径（正常）——`IbatisJavaToXmlLineMarkerProvider.createMarkers`

`src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/IbatisJavaToXmlLineMarkerProvider.kt:198` 起，判定只有三道：

1. 方法名 ∈ `IbatisXmlStatementTargets.STATEMENT_ENTRY_METHODS`；
2. 首参是 String 字面量且非空；
3. `IbatisXmlStatementTargets.lookupStatementAnchors(project, statementId, ownerNamespace)` 三态命中 Found。

**没有标识符正则**。语句名 `cQAccountTypeByAccountType@custmg.qAccountTypeByAccountType` 三道全过 → 图标产出；点击走 `IbatisCallPreviewHandler` → `IbatisStatementPreview.previewResolved` → 同一个 `resolveStatementAnchors`（按 `lastIndexOf('.')` 拆 `ns` / `id`，namespace 是任意字符串键，`@` 原样进索引查询）→ 命中 → 预览正常。

### 快捷键路径（报错）——`ConvertToSqlAction.actionPerformed` 回落链

`src/main/kotlin/cn/song/mybatisplus/visualizer/actions/ConvertToSqlAction.kt:194` 起，Wrapper 主链返回 null 后依次回落：

1. **`tryIbatisCallFallback`** → `IbatisCallTargets.resolve(element)`（`ui/gutter/IbatisStatementPreview.kt:122`）→ `targetOf` → **`IbatisCallYieldGate.isIbatisStatementIdCall`**（`ui/gutter/JdbcTemplateSqlGutterLineMarkerProvider.kt:177`）→ `isStatementIdShape(...)`：

   ```kotlin
   // JdbcTemplateSqlGutterLineMarkerProvider.kt:170
   private val STATEMENT_ID_SHAPE = Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
   ```

   **`@` 不在字符集里，整串 `matches()` 失败** → iBatis 通道放弃认领。
   `resolve` 的第二跳（语句子树扫描）与 `tryIbatisMethodFallback`（方法体扫描）走同一个 `targetOf`，同样全空。

2. **`tryJdbcTemplateFallback`** → `JdbcTemplateSqlLocator.locate(element)`（`psource/JdbcTemplateSqlLocator.kt:55`）——**这条定位器没有 iBatis 让位门**（gutter 侧的门在 `JdbcTemplateSqlGutterLineMarkerProvider.kt:67`，action 侧只靠回落顺序兜）。`queryForObject` 在 `JdbcTemplateMethodDetector` 名白名单里、首参是 String 形态 → **认领**，`tryJdbcTemplateFallback` 返回 true。

3. `JdbcTemplateSqlPreviewService.preview`（`psource/JdbcTemplateSqlPreviewService.kt:76`）：`isLikelySql("cQAccountTypeByAccountType@custmg.qAccountTypeByAccountType")` → 不是 SQL → **return null** → `SqlPreviewService.showResult(project, null, ...)`（`ui/SqlPreviewService.kt:2060`）→ 弹 `action.notFound.message`＝「未在光标处找到可预览的 SQL 源……」。

这正是 `tryIbatisCallFallback` 的 KDoc 里记过的旧坑（T-IbatisCallPreview：「此前该调用被 JDBC 通道认领后 isLikelySql 拒绝、通道又已 return true，用户只拿到错源提示」）——当时用「形状门 + 回落排序」堵住了 `id` / `ns.id` 形态，但 `@` 形态从正则缝里漏了过去，同一错源提示换个形态复发。

### 为什么 gutter 与快捷键会分叉

形状门 `IbatisCallYieldGate` 的职责是「让 JDBC 通道在 SQL 拼接调用与 iBatis 语句 id 调用之间分辨让位」，只在**快捷键 / MCP / JDBC-gutter 让位 / 字符串方法-gutter 让位**四处消费；`IbatisJavaToXmlLineMarkerProvider` 的慢趟判定不经过它（它靠解析命中与否决定产出），所以 gutter 看不到这个缺陷。

## 三、待验证前提（实施前先证伪）

| 编号 | 前提 | 证伪/证实方式 |
| --- | --- | --- |
| P-1 | 用户的 sqlMap XML 根标签 `namespace` 属性值就是 `cQAccountTypeByAccountType@custmg`（gutter 解析按 `lastIndexOf('.')` 拆出的 ns 段原样命中索引） | 请用户提供该 sqlMap 文件头两行；或本地用 ibatis2-demo 造一个 `namespace="组名@模块"` 夹具复现两条路径的分叉。**注意：即使此前提不成立（gutter 走了别的命中路径），形状门是独立瓶颈，P0 修复依然成立** |
| P-2 | 用户构建包含 T-IbatisCallPreview 的回落链（2026.11.12+ 批次已发行） | 报错文案与「图标正常」行为已间接印证；实施时不必再验 |

## 四、方案

| 编号 | 内容 | 优先级 |
| --- | --- | --- |
| S1 | **形状门字符集放行 `@`**：`STATEMENT_ID_SHAPE` 段字符集加 `@`，即 `[A-Za-z_$][A-Za-z0-9_$@]*(?:\.[A-Za-z_$][A-Za-z0-9_$@]*)*`。段首仍必须字母/`_`/`$`（数字开头仍拒绝），空格/括号等 SQL 文本仍不命中——让位门的分辨职责不收窄 | 高 |
| S2 | **测试钉住新形态**：`JdbcTemplateIbatisMutualExclusionTest.test让位判据_标识符形态与SQL文本` 补正例（用户真实语句名全串、`User@module.selectById` 短例），负例清单原样保留；再补一条端到端——光标落在 `baseDao.queryForObject("组名@模块.语句id", map)` 字面量内 → `tryIbatisCallFallback` 命中且 `IbatisStatementPreview.previewResolved` 被走到（目标缺失时是「语句未找到」可见失败，不是 notFound 错源提示） | 高 |
| S3 | **反向验证**：把 S1 的 `@` 从正则撤掉 → S2 新正例必须变红（贴两次读数）；夹具层级注意放在形状门纯函数这一层（参照 [[pitfall-reverse-probe-fixture-layer-mismatch]]） | 高 |
| S4 | **错源兜底加固（可选）**：`tryJdbcTemplateFallback` 认领后若 `JdbcTemplateSqlPreviewService.preview` 返回 null，且该调用命中 `STATEMENT_ENTRY_METHODS` + 首参 String 字面量（gutter 同口径三判），回落 `tryIbatisCallFallback` 再试一次——防今后语句名再出现第三种自定义字符时同一错源提示复发。实现位置：`ConvertToSqlAction` 回落链改为 JDBC 失败不吞 | 中 |
| S5 | **MCP 同面核查**：`McpSourceLocator` 共用 `IbatisCallTargets`，S1 生效后 MCP 日志还原链自动受益；跑该文件定向测试确认无连带红 | 中 |

**不做的事**：

- 不动 `IbatisXmlStatementTargets` 解析链（namespace 本来就是任意字符串键，`@` 已天然支持——gutter 正常就是证据）；
- 不动 `IbatisJavaToXmlLineMarkerProvider`（它没有形状正则，无需改）；
- 不把 `tryIbatisCallFallback` 的形状门整个摘掉——它的职责是让 JDBC 的 SQL 拼接调用不被 iBatis 抢认领，摘掉会反向破坏 `test普通Java类_SQL文本调用仍走JdbcTemplate` 的既有语义。

## 五、影响面清单（同一函数四处消费，S1 改一处全改）

1. `JdbcTemplateSqlGutterLineMarkerProvider.kt:67`——JDBC gutter 行让位门（`@` 形态的 iBatis 调用行，JDBC 图标今后正确让位，消灭双图标残留面）；
2. `StringSqlMethodGutterLineMarkerProvider.kt:107`——字符串方法 gutter 让位门（同上）；
3. `IbatisStatementPreview.kt:170`（`IbatisCallTargets.targetOf`）——快捷键 + MCP 的认领门（本缺陷主修点）；
4. 测试 `JdbcTemplateIbatisMutualExclusionTest`——判据正负例清单。

## 六、验收清单

- [ ] V1：形状门纯函数——`isStatementIdShape("cQAccountTypeByAccountType@custmg.qAccountTypeByAccountType")` 为 true；`isStatementIdShape("SELECT ID FROM T_USER WHERE ID = ?")`、`"User.select ById"`、`"fn(x)"`、`"1abc"`、`""` 仍为 false（S2 用例 + S3 反向读数）
- [ ] V2：action 级——光标在 `@` 形态语句名字面量内按回落链走，`tryIbatisCallFallback` 命中（不再弹 action.notFound 通知）；目标语句缺失时呈现 iBatis「语句未找到」可见失败而非「未在光标处找到可预览的 SQL 源」
- [ ] V3：定向测试——`JdbcTemplateIbatisMutualExclusionTest` + `ConvertToSqlAction` 相关测试类全绿；`IbatisCallTargets` 相关 MCP 定向测试全绿
- [ ] V4：反向——撤掉 `@` 放行后 V1 新正例恰红（两次读数：修复绿 / 回退红）
- [ ] V5：真机（用户侧）——在报障工程上：光标置于该语句名任意位置按 Alt+Shift+Q，弹出与 gutter 点击一致的预览窗口；顺带确认 `@` 形态调用行无 JDBC 双图标

## 七、实施记录

实施日 2026-11-17，实施方＝主控（用户令「在主分支实施开发计划的所有内容」）。

### S1 形状门放行 `@`

`ui/gutter/JdbcTemplateSqlGutterLineMarkerProvider.kt`：`STATEMENT_ID_SHAPE` 两处处段字符集加 `@`

```
[A-Za-z_$][A-Za-z0-9_$@]*(?:\.[A-Za-z_$][A-Za-z0-9_$@]*)*
```

段首字符集未动（`@foo` 仍拒绝），负例（SQL 文本 / 空格 / `fn(x)` / `1abc` / 空串）全部保留。一处改动覆盖四处消费面：JDBC gutter 让位、字符串方法 gutter 让位、`IbatisCallTargets`（快捷键 + MCP）、测试判据。

### S2 测试

| 测试类 | 用例数 | 新增 |
| --- | --- | --- |
| `JdbcTemplateIbatisMutualExclusionTest` | 5 | `test带At符号的自研语句名_JdbcTemplate同样让位`（gutter 级：iBatis 1 枚 / JDBC 0 枚）；判据用例补 6 条断言（真机语句名全文、`User@module.selectById`、末段带 `@`、`@foo` 拒绝、带空格 `@` 形态拒绝） |
| `ConvertToSqlActionTest` | 13 | `test带At符号的自研语句名_iBatis形状门认领且渲染出SQL`（action 级三段：JDBC 认领但渲染 null → 形状门认领 → 渲染出 SQL + 参数 `accountType`）；另 3 条为 S4 用例（命中接管 / 未命中返回 null / 结构守卫） |

用例名不能含 `@`（Kotlin 把 `@` 当注解标记，`fun test带@…` 直接编译失败），统一写 `带At符号`。

### S3 反向验证（两次读数）

**第一次（夹具用 `baseDao` 作接收者名）：只 2 红**，gutter 级用例反而不红。加探针（`RED PROBE jdbcMarkers=`）取读数 `jdbcMarkers=[]`——根因是 `PreviewReceiverResolver.DAO_NAME_SUFFIXES` 含 `BaseDao`，接收者叫 `baseDao` 时 JDBC 通道在形状门**之前**就按家族排除让位，反向验证压根没打到形状门。这正是 LESSONS「夹具必须落在缺陷发生的那一层」的复现。夹具接收者改为 `client`（并在用例注释里写明为什么不能叫 `baseDao`）后重跑：

**第二次（撤掉 `@` 放行，源码反向态）：3 红**

- `test让位判据_标识符形态与SQL文本` → `AssertionFailedError: 带 @ 的自研语句名（真机全文）`
- `test带At符号的自研语句名_JdbcTemplate同样让位` → `JdbcTemplate 通道必须让位（0 枚）：实际 [queryForObject]`
- `test带At符号的自研语句名_iBatis形状门认领且渲染出SQL` → `iBatis 形状门应认领 @ 形态调用（修复前为 null → 错源提示）`

**恢复 `@` 后正向：36 用例 0 失败**（5 + 13 + 9 + 8 + 1，含 S5 的 MCP 与 iBatis 预览三档）。

### S4 错源兜底（与计划原文的差异）

计划原文写「回落 `tryIbatisCallFallback` 再试一次」。实施时改了两点，理由写进代码注释：

1. 新增 `IbatisCallTargets.resolveIgnoringShape`（同两跳定位，**跳过形状正则**、只判入口方法名 + 首参 String 字面量）——若仍走 `resolve`，形状门拒绝的语句名第二次也必然拒绝，兜底等于空转，挡不住「第三种自定义字符」。
2. 新增 `IbatisStatementPreview.previewResolvedOrNull`（目标缺失返回 null）并**内联进** `tryJdbcTemplateFallback` 的同一个 `previewAsync` compute 块——**命中才接管、未命中保持原「未找到」提示**。若沿用 `previewResolved`（返回「语句未找到」可见失败），非 iBatis 工程里 `cache.update("refreshCache")` 这类调用会被改写成指向 iBatis 语句的误导文案。

落点：`actions/ConvertToSqlAction.kt` 的 `tryJdbcTemplateFallback`——JDBC 结果为空时在**同一读锁内**再判一次 iBatis，EDT 只渲染最终结果。

### S5 MCP 同面核查

`McpSourceLocator` 复用 `IbatisCallTargets`，S1 生效后自动受益。定向读数：

- `IbatisStatementPreviewTest` 9 / 0
- `McpSourceLocatorRenderAtLineTest` 8 / 0
- `McpRenderSnippetReadActionTest` 1 / 0

### 未做 / 边界

- 未改 `IbatisXmlStatementTargets` 解析链（namespace 本就是任意字符串键，`@` 天然支持——gutter 正常即为证据）。
- 未动 `IbatisJavaToXmlLineMarkerProvider`（它没有形状正则）。
- 真机验收 V5 仍待用户：在报障工程上按 `Alt+Shift+Q` 应与 gutter 点击结果一致（demo 仓已同步补语料，见 `mybatis-plus-sql-visualizer-demo` 的 `docs/复现用例/20261117-T-IbatisAtShapeGate-带At的iBatis语句名快捷键预览误报-复现用例.md`）。
