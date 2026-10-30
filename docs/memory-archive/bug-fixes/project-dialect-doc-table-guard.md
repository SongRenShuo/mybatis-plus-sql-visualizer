---
name: project-dialect-doc-table-guard
description: 文档站方言表 ↔ Dialect 实现族一致性守卫 DialectDocTableGuardTest（637da736）——判据、三个反向探针读数，以及实施中自造的三种假绿与「ROWNUM/FETCH 顺序让真实错误形态不可见」这条最关键教训
metadata:
  node_type: memory
  type: project
  originSessionId: session_e78fc4db18e9fe0ea0e501cef4f21a12
---

# 文档站方言表守卫（T-DialectDocDrift）

2026-10-26 写对外稿时逐行对账，在文档站查出五处已上线的事实错误（详见 [[project-wechat-article-20261026]]）。这些错误不是笔误，是「按族推定」的思维习惯在文档里的投影，而 `DialectDocGoldenTest` 只钉住了达梦/金仓的保留字与 upsert 形态，**分页表与批量表完全在覆盖之外**。故新增 `src/test/kotlin/.../sql/verify/DialectDocTableGuardTest.kt`（提交 `637da736`）。

## 三条判据

1. **覆盖完整**：表格声明的方言集合必须恰好等于 `DialectRegistry.dialects`（不重不漏）。漏一种即红——「表只列 4 种」正是查出的错误之一。
2. **族归属正确**：文档与实现走**同一个归类器**（`classifyPagination` / `classifyUpsert`），比对族而非逐字文本。这样文档可以自由改写措辞（`LIMIT [offset,] count` ≡ `LIMIT n, m`），但族归错必红。
3. **identity 包装声明覆盖完整**：`crud-sql.md` 那句必须点到全部 `renderInsertWrapper` 非 null 的方言。只做正向（需要包装的必须被点名），不做反向——那句里同时有「其他方言无需包装」的概括表述，反向检查会把概括词误判成方言名。

文档站是兄弟目录、不随插件仓分发：**不在场时四个文档表用例显式绿跳**（外部贡献者只 clone 插件仓不该因此红），但**表格在场却解析不出内容必红**（防「空扫描面空绿」）。用 `println` 跳过而非 `assumeTrue`——平台 runner 把 `AssumptionViolatedException` 记成失败。

## 归类器顺序（每条都有反向验证撑腰）

- `ROWNUM` 判**最前**（判为 `ROWNUM_UNSUPPORTED`，实现里根本没有这个形态）
- `FETCH` 先于 `OFFSET`（Oracle/SQL Server 形态同时含两词）
- `LIMIT [` 与 `LIMIT n,` 先于裸 `OFFSET`
- upsert：`ON DUPLICATE` 先于 `ON CONFLICT`（openGauss 那行写着「不是 PG 的 ON CONFLICT」，两词都在场）

## 三个反向探针，各恰 1 红且点名到方言

1. Oracle 改回 `` `ROWNUM` / `FETCH FIRST` `` → `方言 oracle 文档声明 ROWNUM_UNSUPPORTED，实现实际为 OFFSET_FETCH`
2. 删掉 openGauss 那行 → `缺少方言 opengauss（表必须覆盖全部 11 种）`
3. 达梦改回 `MERGE INTO` → `方言 dm 文档声明 MERGE_STATEMENT，实现实际为 PLAIN_INSERT`

## 实施中自造的三种假绿（都是跑起来才发现的）

1. **表头用 `contains` 匹配，命中了数据行**。`line.contains("方言") && line.contains("冲突子句")` 命中 `batch-operation.md` 里的 `| \`saveOrUpdate\` | UPSERT | 插入或更新（冲突子句按方言渲染） |`，解析出 1 行 `upsert` 后报「未知方言 upsert」。**表头定位必须按单元格精确相等**，不能子串包含——否则表头判据本身成了假绿入口。
2. **按三列解析，而两张受检表都是两列** → 四个文档用例全部「未解析到任何表格行」。这个报错还算诚实，但原因是解析器写错了。
3. **最关键的一条**：归类器让 `FETCH` 先判，而 2026-10-26 那处**真实错误写法**是 `` `ROWNUM` / `FETCH FIRST` ``——两个词都在场，于是被归进 `OFFSET_FETCH` 族，**与 Oracle 实现的族恰好一致**，反向验证实测抓不住。这正是「合成样本只能证明规则看得见我造的形态」的反面案例：**如果不去真改文档试，这条守卫会带着致命盲区通过所有自证用例**。修法是让 ROWNUM 优先判、单独成一族。

详见 [[pitfall-fake-green-test-assertions]] 新增条目。

## 已登记进元守卫

`GuardSelfTestCoverageMetaGuardTest` 的 `GUARD_CLASSES` 加了该项：`docTableViolations` 记 VIOLATIONS，两个 `classify*` 记 SITES（族归类器，族判定的唯一事实源）。自证语料四条：`漂移自证_分页族归错被抓` / `漂移自证_ROWNUM写法被抓` / `漂移自证_漏方言被抓` / `漂移自证_upsert族归错被抓`。

## 读数

定向 9/0/0/0；元守卫 8/0/0/0（两个类一起跑确认都真跑了，不是被 `--tests` 过滤掉）；`testFast` 全量 345 类 **4215/0/0/skip7**（6m31s）。

## 留下的可做项

文档站目前仍缺「表格 ↔ 实现」的通用自动比对（现在只覆盖这两张表 + identity 一句）。要扩到其他表格（EXPLAIN 验证程度表等）需先确认那些表的「族」该怎么定义——EXPLAIN 是纯文本形态，没有 `Dialect` 上的对应方法可反推。

关联：[[project-wechat-article-20261026]]、[[project-test-conventions]]
