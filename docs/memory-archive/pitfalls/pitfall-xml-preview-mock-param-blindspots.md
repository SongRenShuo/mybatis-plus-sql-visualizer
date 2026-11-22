---
name: pitfall-xml-preview-mock-param-blindspots
description: XML 动态 SQL 预览的模拟参数七类失真面——foreach item 恒为空对象、OBJECT 参数无属性、迭代体 trim
  致粘连、choose 互斥分支默认全开、基线错误混进本轮告警
metadata:
  node_type: memory
  type: project
  originSessionId: sess_f586a157-4652-4c9d-b73f-14df449f2952
---

XML 动态 SQL 预览（T-M24 引擎）用「模拟参数」渲染，MBG Example 这类 `${item.attr}` 形态会成片失真。2026-11-11 用 MBG 生成的三件套（`selectByExample` + `Example_Where_Clause` + 内层 `trim`）实测四组面板状态：

| 面板状态 | 产物（空白已压缩） |
| --- | --- |
| 默认（三处集合各 2 次、8 条件全勾） | `... WHERE ( and )or( and ) order by 1` |
| 各 1 次、条件全勾 | `... WHERE ( ) order by 1` |
| 1 次 + 只开 `singleValue` | `... WHERE ( ? ) order by 1` |
| 1 次 + 只开 `betweenValue` | `... WHERE ( ? and ? ) order by 1` |
| 1 次 + `oredCriteria` 勾「为 null」 | `... order by 1` + 英文报错一句 |

七条机制（2026-11-11 master 的定位）：

- `MockIterable` 的元素恒为 `MockObject(emptyMap())`（`xml/Ognl.kt`）⇒ 任何 `item.属性` 与 `${item.属性}` 都取不到值；`mockValueOf` 对 `ParamKind.OBJECT` 也返回空 `MockObject()`（`xml/XmlPreviewModel.kt`）⇒ DTO 型 `_parameter.xxx` 一样全空。
- `${}` 求值为 null → `TextSqlNode` 输出空串（对齐 MyBatis issue #274）⇒ `and ${criterion.condition}` 退化成 `and`。
- `ForeachSqlNode.FilteredContext.appendSql` 对每轮产物 `.trim()`（T-M25.5 为紧凑展示）⇒ 迭代内容首尾相接：`andand`、`)or(`。
- `TrimSqlNode.applyPrefix` 只裁一次前缀，与 MyBatis 3.5.19 一致（**不是 bug**），但会把上一条残留的 `and` 留在括号里 ⇒ `( and )`。
- `XmlPreviewModel.defaultTruth = !test.contains("== null")` ⇒ choose 的四个互斥 when 全为 true，短路只走第一个 `noValue`（唯一不带 `#{}` 的分支）。
- `DEFAULT_FOREACH_SIZE = 2`，面板输入框留空也回落 2；Example 一次典型调用是 1 Criteria / 1 Criterion。
- `XmlPreviewService.toResult` 把**基线渲染**（默认 state）的 `rendered.errors` 写进 `result.warning`，面板 `updateMeta` 再与**本轮** `xmlErrors` 合并 ⇒ 同一告警行出现两条互斥信息（用户截图实证）。

判据：模拟参数下产物出现 `( and )` / `order by 1`，先按这七条查，别怀疑 XML 解析或 trim 语义。`order by 1` 是**可执行**的（语义被改写成按第一列排序），比语法错更危险。

临时复现配方（不连库、不启沙盒）：`XmlMapperParser().parse(mapperText)` → `XmlPreviewModel.from(statement, parsed.errors)` → `renderSql(XmlPreviewState(enabledIds, forEachCounts, forEachNulls))`。println 只进 `build/test-results/test/TEST-*.xml` 的 `<system-out>`（控制台要 `-i`，多行 grep 会截断）——直接读那份 XML 报告取全量产物。用完删临时用例，别留探针。

**20261111 已按 M1（`2d8798a9`）+ M2（`6fb76205`）逐条修掉**，两处实现订正（详见
[[project-mbg-example-preview-20261111]]）：

- 迭代分隔最终落在 `TrimSqlNode` 的 filtered 缓冲（相邻段都无空白才补空格）；做在 `ForeachSqlNode` 层会
  与根 `appendSql` 的段分隔叠加成双空格。
- choose 默认分支取「含 `#{}` 的分支里最先出现者」而非参数最多——按后者 MBG 会选到 `betweenValue`。

仍然成立、不要当 bug 的两条：`TrimSqlNode.applyPrefix` 只裁一次前缀与 MyBatis 3.5.19 一致；`order by 1`
是**可执行**的（语义被改写成按第一列排序），比语法错更危险。

## 第八面（2026-11-12 用户真机实测）：面板的 foreach 值列表框会覆盖 M2 注入的对象

左侧「foreach 集合」那三个框（`oredCriteria` / `criteria.crit…` / `criterion.va…`）是**值列表输入**，
不是「展开次数」：填逗号分隔的值会播种 `__frch_<item>_n`，把循环变量整个换成字符串。

用户把 `1` 填进 `criteria.crit…`（collection `criteria.criteria`，item `criterion`）后的产物：

```sql
WHERE (create_time BETWEEN '1' AND ? AND tenant_id = ?)
```

`criterion` 变成字符串 `"1"` ⇒ `#{criterion.value}` 取到 `1` 并按文本加引号变成 `'1'`，
`#{criterion.secondValue}` 取不到 ⇒ 保留 `?`。**这是面板值框覆盖注入，不是渲染 bug**——本地用同一套模板
与同一组条件（between + tenant_id）跑模型层，输出固定是全 `?`。

判据与用法：

- M2 注入存在时这几个框**应当留空**（留空恢复默认展开、占位保持 `?`）；要填值走参数区或直接改 SQL；
- 看到「成对占位只填了一半」（`BETWEEN '1' AND ?`）先怀疑值框被填，别去查渲染器；
- 区间条件目前**没有成对值输入位**（`value` / `secondValue` 两位），填一个值必然造成半填形态；
- `'1'` 给 `create_time` 这类时间列，真执行会报 `Incorrect datetime value`——它不代表「正确值长什么样」。

**已修（`89c74c663`，2026-11-12）**：`buildXmlForEachRow` 增 `injectedCount` 形参，M2 注入存在时值框
`isEnabled = false` 并提示「已按调用点还原 N 项对象，不能在此填值」；旧提示里那句「MBG 这类集合通常只有 1 项，
**可填 1**」是主动引导用户踩坑，已删。未注入时提示改成「逗号分隔，按该集合展开出的占位顺序依次填入」。

**为什么不另做成对值输入（同轮判断，用户问过「新交互推荐吗」）**：`syncForEachItemValues` 本来就按渲染映射顺序
把逗号分隔的值依次写进 `__frch_<item>_*`，`BETWEEN` 的两个占位正好是 `.value` / `.secondValue` 两个映射——
填 `2026-01-01,2026-03-01` 就能填满两个位置。缺的不是交互，是「别让值框冲掉还原出来的对象」。

新坑的另一面：面板提示里写「可填 N」等于把内部实现的取值方式教给用户，一旦实现变了就是错误引导——
提示只描述**用户能得到什么**，不教他怎么绕过机制。

方案与任务拆分见 `docs/开发计划/20261111-1043-T-MbgExamplePreview-MBG-Example动态SQL预览失真-开发计划.md`。关联：[[project-mbg-example-preview-20261111]]、[[pitfall-reverse-probe-fixture-layer-mismatch]]、[[xml-engine]]、[[project-preview-openperf-m2-e2e-20261112]]。
