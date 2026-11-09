---
name: pitfall-ibatis-log-format-vs-mybatis-param-parser
description: iBatis 2.x 的 JDBC 日志形态（{pstm-N} Executing Statement /
  Parameters:[...] / Types:[...]）被 MyBatis 参数解析器误解析——单值整段括号进 SQL、含 null 的多值只提取
  null 项并错位填 NULL；剥括号必须同批接 Types
metadata:
  node_type: memory
  type: project
  originSessionId: sess_33620d7f-a4ec-4f92-8fbe-dcd146afc43f
---

# iBatis 2.x 日志形态 ≠ MyBatis 参数语法

20261106 用户真机（国产库磐维 Oracle 模式、驱动 `org.panweidb.Driver`、**iBatis 2.x 而非 MyBatis 3**、log4j2）报两条症状：SELECT 变 `'[00340025002200200006]'`、INSERT 参数一片 NULL。两者是同一根因。

## 日志形态

```
{conn-100022} Preparing Statement: select ... where ORGID = ?
{pstm-100021} Executing Statement: INSERT INTO ... VALUES (?,?,...,to_date(?,'YYYYMMDDHH24MISS'))
{pstm-100021} Parameters: [00340025002200200006, null, 测试OrgManager, ...]
{pstm-100021} Types: [java.lang.String, null, java.lang.String, ...]
```

logger 名是 `java.sql.Connection` / `java.sql.PreparedStatement`（`%-40.40logger{39}` 缩写为 `j.s.*`），`{conn-N}`/`{pstm-N}` 是 iBatis JDBC 日志代理的实例计数器。用户把设置里「SQL 前缀」改成 `Executing Statement:` 后 SQL 正文能抓到，参数仍按 MyBatis 语法解析 → 踩坑。

## 机制（实测回放，非推断）

- `MyBatisLogParser.kt:296` `parseParams` / `:321` `PARAM_REGEX` 只认「`值(类型)`」与「`null`」两种 token。
- 单值 `[00340025002200200006]`：无匹配 → `result.isEmpty()` → 回落 `text.split(',')` → 整段带括号 → `LogSqlRestorer.renderUntyped`（`:88`）因首字符 `[` 不匹配数字正则 → 加引号 → `'[00340025002200200006]'`。
- 多值含 `null`（36 项、13 个 null）：正则**只命中 13 个 null 项**，`result` 非空故**不回落**逗号粗分，23 个非空值静默丢弃；参数数 13 ≠ 占位符 36 → `restore` 按序把**前 13 个占位符**填成 `NULL`，其余留 `?`——值丢失 + 位置整体错位。
- `Types:` 行不命中任何分支，被当噪声丢弃，类型信息到此为止。

## 硬约束：剥括号必须同批接 Types

`00340025002200200006` 是纯数字串。只修「剥外层方括号」会让它在 `renderUntyped` 里命中数字正则而**裸出成数值字面量**——Oracle 模式里 `WHERE ORGID = 0034...` 变成数值比较（隐式转换/ORA-01722），比现状（多一对括号）语义更错。必须同批解析紧随的 `Types: [...]`，按位置并入 `LogSqlParam.type`（`java.lang.String` 才会引号、`Timestamp/Date` 才会走 ANSI 字面量）。

## 设计口径（T-IbatisPanwei 券 A/B）

- 在 `MyBatisLogParser` 内加 iBatis 分支（不新造并行解析器，否则同一语句双条目）：按行标记 `\{pstm-\d+\}` 识别，命中即消费并 `return`，不落到用户配置的前缀分支。
- `Executing Statement:` 是唯一 SQL 入口；`{conn-N} Preparing Statement:` **不产条目**（iBatis prepare 与 execute 紧邻成对，双收会翻倍）。
- `Parameters:` 延迟闭合，等同 `pstm id` 的 `Types:` 行再产出；兜底闭合＝新语句行 / `closePending()` / 静默冲刷。
- 边界如实登记：元素内逗号与嵌套方括号（`List.toString` 不可逆）按逗号切分；`Types:` 缺行退回无类型启发式；iBatis batch（一次 prepare 多次 Parameters）一期不支持。

## 值自带方括号不会被吞（用户 20261106 追问）

剥的永远只是**最外一层结构括号**（`List.toString` 那一层），元素内的括号原样保留：

| 真实值 | 日志形态 | 解析结果 |
|---|---|---|
| `[abc]` | `Parameters: [[abc]]` | `[abc]` → `'[abc]'` |
| `[]` | `Parameters: [[]]` | `[]` → `'[]'` |
| `a[b]c` | `Parameters: [a[b]c]` | `a[b]c` |

守卫用例 `IbatisLogParseTest.参数值本身带方括号时只剥结构层一层`（11/0/0/0）；反向探针把 `parseIbatisParams` 改成 `trim('[', ']')` 全剥 → **恰 1 红**，其余 10 例保绿。

真正会丢的只有 `toString` 不可逆的三种（已在类注释登记）：①值内逗号（`a,b` 与两个值 `a`/`b` 打印同形）；②单个空串（打成 `[]`，与空列表同形 → 解析成 0 参，SQL 保留 `?` 不误填）；③字符串值恰为 `null` 四个字母（与真 null 同形）。

**Why**：控制台通道的解析器是按 MyBatis 语法写的，遇到「同类但不是它」的框架日志会静默产出错误 SQL，而不是报错或跳过——错误数据还能继续进预览/复制/执行/去重键。

**How to apply**：任何「参数行形如 `[a, b, null]`」的新日志格式（Druid 族、iBatis 族）都按「行标记定格式、剥一层结构括号、按位置接类型」处理；不要用「值形状猜格式」，更不要只剥括号就收工。

实施状态：20261106 已实施并合入 master（分支与读数见 [[project-ibatis-panwei-20261106]]）。

相关：[[pitfall-cross-channel-dedupe-key-divergence]]（同源问题的另一面：还原口径分叉致 dedupeKey 不折叠）、[[reference-cn-db-driver-families]]、[[project-pending-plans]]。
