# T-DdlTypeColor：DDL 语句（CREATE/ALTER/DROP/TRUNCATE）纳入类型着色与筛选

> **创建日期**：2026-10-26 21:10
> **触发**：用户实测发现建表/删表语句左侧没有类型色条——`CREATE`/`DROP`/`TRUNCATE` 全被归为 `OTHER`，
> 而 `OTHER` 不参与着色，在列表里完全看不出类型
> **前序**：`4ba423d3`（T-LogListHeader，头部行 + 留白分割）
> **实施分支**：`master`
> **用户两项定案**（2026-10-26）：①视觉用**虚线色条**区分 DDL 与 DML；②归类按主控推荐

---

## 一、现状与问题

`LogStatementClassifier.classify()` 只识别五个首 token：`SELECT`/`WITH` → SELECT、
`INSERT`、`UPDATE`、`DELETE`，**其余全部落入 `OTHER`**（`LogStatementClassifier.kt:38-46`）。

而 `SqlListTypeColor.colorOf()` 对 `OTHER` **硬编码返回 null**（`SqlListTypeColor.kt:103`）——
不画色条。结果：

- 建表（`CREATE TABLE`）、删表（`DROP TABLE`）、清表（`TRUNCATE`）在列表里**没有任何类型标识**；
- 「SQL 类型」筛选弹窗里也勾不到它们（`FILTERABLE_TYPES` 只有 SELECT/INSERT/UPDATE/DELETE，
  `LogEntryFiltering.kt:236-241`），想「只看结构变更」做不到；
- 排查「谁在偷偷建表/删表」时只能靠肉眼读 SQL 首词。

## 二、归类方案（主控推荐，用户已采纳）

`StatementType` 新增两档，**不复用现有 INSERT/DELETE**：

| 新类型 | 覆盖的首 token | 着色 | 语义 |
|---|---|---|---|
| `CREATE` | `CREATE`、`ALTER`、`RENAME` | 复用 INSERT 绿 | 结构性新增/变更 |
| `DROP` | `DROP`、`TRUNCATE` | 复用 DELETE 红 | 结构性删除/清空 |

**为什么不把 CREATE 直接归进 INSERT**（尽管颜色相同）：
「SQL 类型」筛选是按 `StatementType` 勾选的。若 CREATE 就是 INSERT，用户勾「只看插入」时
会把建表语句一起筛出来——那是错的语义。类型是新档、颜色是复用，两者解耦。

**`ALTER TABLE` 归 CREATE**：它改结构但不删数据，与建表同属「结构性变更」。
`RENAME` 同理。

**`MERGE` / `REPLACE` / 存储过程调用等仍留 `OTHER`**：它们既非纯读也非结构性 DDL，
归任何一档都会误导。`OTHER` 保持不着色。

## 三、视觉方案（用户定案：虚线色条）

| 语句 | 色条 |
|---|---|
| DML（SELECT/INSERT/UPDATE/DELETE） | 3px 实心（现状不变） |
| DDL（CREATE/DROP） | 3px **虚线**，按 3px 实 / 3px 空绘制 |

语义：虚线 = 「不连续、非普通数据操作」，比加粗/双色更含蓄，也不会额外占宽度。

> **待验证前提 P1：虚线在深色主题下是否够清楚**。
> 预览里用的 `linearGradient` 在 22px 行高下只有 ~3.5 段，实测观感偏弱。
> **实施时必须先量**：若深色下辨识度不足，处置优先级为
> ① 虚线段加长（3 实 / 2 空）→ ② 线的可读性靠色值提亮（而非加宽）→ ③ 回退为实心但加宽。
> 不允许「因为虚线不好看就悄悄改成实心」——用户明确选了虚线，观感问题应如实回报告知。

> **待验证前提 P2：色条绘制方式**。当前色条是 `JPanel` + `background`（`RailCellRendererBase.colorBar`），
> 实心填充。虚线需要在 `paintComponent` 里自绘。**渲染热路径**——必须确认自绘不引入
> 每格对象分配（不能每次 paint 新建 `BasicStroke`/`Color`）。虚线周期与颜色都应是常量。

## 四、实施步骤

### W1｜分类器扩展

`LogStatementClassifier`：
```kotlin
"SELECT", "WITH" -> SELECT
"INSERT" -> INSERT
"UPDATE" -> UPDATE
"DELETE" -> DELETE
"CREATE", "ALTER", "RENAME" -> CREATE
"DROP", "TRUNCATE" -> DROP
else -> OTHER
```
`StatementType` 加 `CREATE`、`DROP` 两档。

### W2｜着色复用

`SqlListTypeColor`：
- `defaultOf(CREATE)` → `COLOR_INSERT`（绿）
- `defaultOf(DROP)` → `COLOR_DELETE`（红）
- `colorOf` 的 `OTHER` 短路保持不变；新增两档走正常着色路径
- `hexOf(type)` 的设置映射：CREATE 读 `crudColorInsert`、DROP 读 `crudColorDelete`
  （**不新增设置项**——用户不想为 DDL 单独配两色）

### W3｜虚线色条绘制

`RailCellRendererBase.colorBar` 由 `JPanel` 改为自绘面板：
- 实心（DML / 非 DDL）：照旧填满
- 虚线（CREATE/DROP）：3px 实 / 3px 空，按 `BAR_WIDTH` 竖直绘制
- `paintComponent` 内不新建对象（`Color` 走 `SqlListTypeColor` 的实例缓存）

### W4｜筛选与文案

- `FILTERABLE_TYPES` 加 `CREATE`、`DROP`（顺序：查询/插入/更新/删除/建表/删表）
- 新增 bundle 键 `log.filter.create` = 建表、`log.filter.drop` = 删表
- `labelForType` 补两支

### W5｜测试

- `LogStatementClassifierTest`：新增 6 个用例（CREATE/ALTER/RENAME → CREATE；
  DROP/TRUNCATE → DROP；MERGE 仍 OTHER）
- `SqlListTypeColorTest`：CREATE 取绿、DROP 取红、OTHER 仍 null
- 新增虚线绘制守卫：DDL 条目色条为虚线形态、DML 为实心（结构断言，不比对像素）
- 筛选：勾「建表」只出 CREATE 条目

### W6｜压测复跑

`LogEntryRailPerfTest` 单格预算不变（自绘色条不得拖慢）；
`LogPipelinePerfTest` 的类型筛选成本不变。

## 五、反向验证设计

| 探针 | 还原的缺陷 | 应红用例 |
|---|---|---|
| R1 | CREATE/ALTER 退回 `OTHER` | 分类断言 + 筛选断言 |
| R2 | DROP/TRUNCATE 退回 `OTHER` | 分类断言 |
| R3 | DDL 色条画成实心（虚线与实心无差别） | 虚线形态守卫 |
| R4 | `FILTERABLE_TYPES` 漏掉新档 | 筛选弹窗项数断言 |

## 六、验收口径

- 定向：`log` 包全量（当前 1768 例）
- 压测：渲染单格预算不变
- 反向验证：R1~R4
- 真机：深色主题下确认虚线可辨（**这是本次唯一的主观判断项，必须实测**）

---

## 七、实施记录

### 7.1 待验证前提的实测结果

| 前提 | 结论 | 证据 |
|---|---|---|
| **P1** 虚线在深色下是否够清楚 | **形态实现正确，观感待真机确认** | 像素序列实测 `###...###...` 每 6px 一周期，实/空各 3px；深色主题下的辨识度需真机走查 |
| **P2** 自绘色条的渲染热路径开销 | **成立，已按约束实现** | `paintComponent` 只用 `fillRect`，不新建 Color/Stroke；颜色来自实例缓存；`dashed` 同值不重绘 |

### 7.2 实施中抓出的真 bug：同色画同色 = 没画

初版 `paintComponent` 的处理是「先铺 background 底色，再按段填 background 前景色」——
前景与底色同色，渲染出来**整条全实心**，虚线完全没生效。

这个 bug 的隐蔽性在于：**形态断言（`dashed == true`）照样通过**，肉眼在深色背景上看
一条 3px 宽的实心绿条与虚线绿条也容易糊过去。最终靠**像素序列实测**抓出：

```
修复前：DASH pattern=[########################################]   ← 全实心
修复后：DASH pattern=[###...###...###...###...###...###...###.]  ← 正确交替
```

修法：空白段**不铺任何底色**，让父容器背景自然透出。

已沉淀为两条像素级守卫：
- `test虚线色条_像素序列为实空交替`——断言存在 `#` 与 `.` 且以 `###...` 开头
- `test实心色条_像素全填满`——实心形态不得出现空白段

### 7.3 分类与着色落地

`StatementType` 新增 `CREATE`（CREATE/ALTER/RENAME）与 `DROP`（DROP/TRUNCATE）两档；
着色复用（CREATE→增绿、DROP→删红），**不新增设置项**；`FILTERABLE_TYPES` 从 4 项扩到 6 项，
筛选文案新增「建表」「删表」。

`MERGE` / `REPLACE` / `CALL` 仍留 `OTHER`（不着色、不进筛选）——归任何一档都会误导。

### 7.4 测试读数

| 项目 | 结果 |
|---|---|
| `log` 包全量 | **1777 / 0 失败 / 0 跳过**（改造前 1768，净增 9 条） |
| 新增用例 | 分类 5 条、着色 3 条、虚线形态 4 条（含 2 条像素级）、筛选 3 条 |

### 7.5 需要同步更新的既有断言

三处既有测试断言了旧语义（DDL 归 OTHER、不着色），已按新语义更新并注明改判理由：
- `LogStatementClassifierTest`：`TRUNCATE 归 OTHER` → `TRUNCATE 归 DROP`；
  「全类型等价」表里 `TRUNCATE TABLE user` 的期望同步改
- `HistoryCellRendererTest`：`OTHER 不应着色`（原用 TRUNCATE 举例）→ 改为断言
  CREATE 取绿、DROP/TRUNCATE 取红、MERGE 仍不着色

### 7.6 遗留

- **真机确认虚线观感**：深色主题下 3px 宽的虚线是否可辨，是本次唯一主观项。
  若偏弱，调 `TypeColorBar.DASH_ON/DASH_OFF`（常量，改一个数）而非改回实心。
- **全量 `testShards` 未跑**：只跑 `log` 包 1777 例。
