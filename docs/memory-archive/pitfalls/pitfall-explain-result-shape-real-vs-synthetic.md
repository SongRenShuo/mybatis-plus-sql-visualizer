---
name: pitfall-explain-result-shape-real-vs-synthetic
description: 真机 EXPLAIN 结果形态与单测合成样本不一致——OB 返回"单列×多行"（一行计划=一个 row），解析器却按"一个 cell 装整段多行文本"写；127 例单测全绿而真机返回 null 回退。判据：解析器样本必须照抄真机原始结果，不能按文档描述合成
metadata:
  node_type: memory
  type: project
  originSessionId: sess_da4753b3-ef7f-4a1f-b5d2-a46a94883c80
---

# EXPLAIN 解析器：单测合成样本 vs 真机原始形态（2026-11-01 实锤）

S2-15「OB TEXT 计划进结构化面板」带 **127/0 单测**合入 dev，真机一跑就露馅：面板报「计划文本解析不出结构化行列（计划形态不支持）」并回退原生控制台——**功能实际没生效**。

## 形态差异（根因）

`McpExplainFormatter.RawResult(headers, rows: List<List<String>>)`。

- **解析器假设**：一个 cell 里装着整段多行计划文本 → `for (row) for (cell) { cell.lines() }` 逐 cell 扫表头再取后续行。
- **真实 OB（CE 4.3.5.6）**：`EXPLAIN` 返回**单列 × 多行**——每一行计划文本就是结果集的一行，即 `rows = [["|ID|OPERATOR |NAME |EST.ROWS|EST.TIME(us)|"], [""], ["|0 |TABLE FULL SCAN|t_order|3 |3 |"], ...]`。

于是每个 cell 只有一行：表头单独命中 → `lines.drop(headerIdx + 1)` 为空 → `dataRows.isEmpty()` → `return null`。

单测过是因为样本按"文档描述的形状"合成（整段塞进一个 cell），**没有一份样本取自真机原始结果**。

## 真机原始输出（照录，可作夹具）

```
|ID|OPERATOR |NAME |EST.ROWS|EST.TIME(us)|
|0 |TABLE FULL SCAN|t_order|3 |3 |
Outputs & filters:
  ------------------------------------
  output([t_order.id], [t_order.order_no], [t_order.amount], [t_order.status], [t_order.create_time])
```

注意表头列名后有**尾随空格**（`OPERATOR `、`NAME `），正则要容忍 `\s*`；数据行列序与表头一致，按**列名**取值即可，不要按列位猜。

## 通用判据（比这个 bug 本身重要）

1. **解析类功能的单测样本必须来自真机原始产物**（日志原文、结果集 dump、接口响应字节），不能按"文档说它长这样"合成。合成样本只能证"规则能解析我造的形态"，证不了"规则能解析真实形态"。
2. **fail-closed 设计掩盖了功能失效**：解析失败静默回退原生控制台，用户看不出异常、开发者不看日志就以为在跑——所以"有回退"不等于"功能可用"，真机必须验到**成功路径**而不是"没崩"。
3. 验收要看**成功证据**（结构化面板出现 + 建议条），不能把"回退提示语正常显示"当成通过。

相关：[[pitfall-fake-green-test-assertions]]、[[pitfall-guard-covers-audited-targets]]、[[pitfall-verification-blindspots-and-wrapup]]。
