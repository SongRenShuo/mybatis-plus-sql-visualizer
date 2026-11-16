---
name: pitfall-mbg-example-ref-and-or-grouping
description: MBG Example 的 M2 反推第二轮的三个机制坑——ReferencesSearch 命中引用≠可用调用点（实现声明处/方法引用）；`example.or()` 必须按 Criteria 分组、内层点号集合不能被全局绑定遮蔽；`__frch_criterion_N` 的 N 是渲染期计数器不是条件下标
metadata:
  node_type: memory
  type: project
  originSessionId: sess_f586a157-4652-4c9d-b73f-14df449f2952
---

# MBG Example M2 反推的三个机制坑（2026-11-12 真机第二轮）

## 1. 命中引用 ≠ 可用调用点（D6，`bdc1f89ab`）

`ReferencesSearch` 对**接口方法**会把「实现的声明处 / 方法引用」一并返回，这类 ref 的 `element`
上溯**没有** `PsiMethodCallExpression`。旧逻辑取第一个 ref、`getParentOfType(...)` 返回 null 就整条放弃，
trace 落 `noCallSite`——而项目里明明有两个真实调用点。

**判别技巧**：日志里**没有** `findCallSite empty` 那行诊断（只在 ref 为 null 时打）⇒ 说明引用取到了，
是「命中不可用」。修法＝遍历时**跳过不可用的 ref 继续找**，找到第一个可用调用点仍即停；诊断补 `refsSeen=`。

与 D1（`Query.findFirst()` 恒返回 null）是两个失败面，别混为一谈。

## 2. `example.or()` 必须分组，且点号集合不能被全局绑定遮蔽（D7，`ab9a8c308`）

- `createCriteria()` 与 `or()` 在 MBG 里开的是**不同 Criteria**，`Example_Where_Clause` 用
  `separator="or"` 连接。拍平成一条 Criteria ⇒ `( a ) or ( b )` 变 `a and b`（语义错，不是格式错）。
  分组键＝链上最靠近根的 `createCriteria()` / `or()` 调用节点（分步别名用变量初始化表达式）。
- 内层 `collection="criteria.criteria"` 之前被**绑成全局集合**（`mockBindings` 按 collectionExpr 逐键绑），
  于是外层每条 Criteria 都把**全部**条件再渲染一遍 —— 中间态读数 `( a and b ) or ( a and b )`。
  修法：有元素级注入时跳过点号表达式的全局绑定，让路径解析落到每条 Criteria 自己的属性上。
- 顺带：foreach **单词分隔符**（`or`/`and`）后要补空格（`or(` → `or (`），标点分隔符 `,` 不能加
  （否则 `IN (?, ?)` 破坏既有紧凑口径）。

## 3. `__frch_criterion_N` 的 N 是渲染期计数器，不是下标（D11b）

`itemizeItem(item, i)` 的 `i` 来自 `getUniqueNumber()`，全局自增。拿它当 criterion 下标去取条件会落空 ⇒
面板标签退化成「条件 2 值」（真机截图）。按**首次出现顺序**把不同的 `__frch_criterion_N` 前缀与注入的
criterion 顺序对齐才对。标签口径：等值/LIKE → `cust_id = 值`；区间 → `… 起始值` / `… 结束值`。

## 4. 参数区为什么没有可填行（D11，`c33f9542c`）

M2 注入后占位符来源全是 `__frch_criterion_N.*`，而参数区按既有口径把 `__frch_` 前缀**全部过滤**（那是给
foreach 值框统一回填的）⇒ 左栏零可填行。修法＝按真实占位键补行（标签取条件文本），并在区顶加常驻说明
「填值替换 `?`；留空保持 `?`」。**配套坑（D10）**：`清除` 只清了 `xmlForEachValues`，而回收 `__frch_*` 的
`syncForEachItemValues` 以该 map 非空为前提 ⇒ 清空后再没人回收，残留值永久留在产物里（真机点清除后仍是
`BETWEEN '11' AND ?`）；清除必须**显式回收 `__frch_` 键**。

## 5. foreach 值框是「值列表」，不是次数（D9）

M2 注入的元素是**对象**，往框里填 `1` 会把 `__frch_criterion_0` 换成字符串 ⇒ `#{criterion.value}` 取到
那个字符串（按文本加引号 → `'1'`）、`#{criterion.secondValue}` 取不到 → `BETWEEN '1' AND ?` 半填形态。
注入存在时值框必须**只读**。旧 tooltip 还写着「MBG 这类集合通常只有 1 项，可填 1」——等于主动引导踩坑。

## 6. 只读的值框不能回显历史值（D12）

D9 把注入集合的值框置灰后，真机上框里还留着历史召回的旧值（`1,2,3` / `1,2`）——用户读成「填了却改不动」，
比空白更像 bug。两条一起做：**渲染时**注入集合不回显（`text=""` + 占位「已还原 N 项」）、
**历史层**按 `collectionElements.containsKey(expr)` 同时跳过 `SNAPSHOT_FEV` 的写入与召回（存了也没人恢复，
只会让快照与界面不一致）。

相关：[[project-preview-openperf-m2-e2e-20261112]]、[[pitfall-xml-preview-mock-param-blindspots]]、
[[pitfall-reverse-probe-fixture-layer-mismatch]]。
