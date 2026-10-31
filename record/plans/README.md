# 演示 plan 目录（T-TestGateGems W6-c）

`*.json` 是**可确定性重放的演示动作序列**——来源是 magpie `.github/ui-preview/record.mjs:455` 的
`PLAN_FILE` 机制：LLM/人只规划一次，之后同一份 plan 反复重放，不再依赖现场判断。

## 为什么要 plan 而不是直接写脚本

82 个手写场景脚本的共同问题：坐标、动作顺序、说明文字混在 Python 里，构图一漂移就要改代码，
而且「这段演示到底点了什么」无法 review。plan 把三者分离：

| 关注点 | 落在哪 |
|---|---|
| 点哪个控件（语义） | `steps[].target`（语义键）+ `steps[].label`（人读的名字，同时喂 W6-a 护栏） |
| 它在屏幕哪里（坐标） | `targets` 映射——构图变化只改这一处 |
| 成片看不到什么（局限） | `unseen` 数组——直接进成片说明（W6-b） |

## 用法

```bash
python record/plan_runner.py record/plans/<name>.json --dry-run   # 只校验 + 打印，不动鼠标
python record/plan_runner.py record/plans/<name>.json             # 真重放
```

`--dry-run` 会校验：动作类型合法、每个 target 有坐标映射、必填字段齐全。**新写 plan 一律先 dry-run。**

## 语义键从哪来

不要目测坐标猜控件名。用 `record/uitree.py` 的 `dump_tree(root)` 把当前界面 dump 成可定位树
（类名 / 无障碍名 / 可见文本 / 屏幕矩形），照树里的文本给 target 起名；坐标从树的 `rect=` 取，
再按 `record/BASELINE.md` 的标定纪律核对（弹窗位置会漂）。

这条对应 magpie 的 `outline()`：**给规划器的是结构，不是截图**——截图猜坐标不可靠，结构才能给出稳定选择器。

## 自检

```bash
python record/verify_uitree.py                                  # uitree 判定内核正反例（Swing 桩，无需 IDE）
python -m py_compile record/uitree.py record/plan_runner.py     # 语法
```

`verify_uitree.py` 用最小 Swing 桩验证 `build_tree` / `find_by_text`：正例证明看得见该看的
（可见组件 / 类名 / role / rect / 嵌套递归），反例证明看不见不该看的（隐藏组件不进树且不可命中、
不存在返回 None、`max_lines` 截断生效）。**改了 `uitree.py` 就跑一遍**——它依赖 JVM 组件，
正常环境跑不到真实树，桩测试是唯一的低成本回归。

## 现成样例

见同目录 `example-preview.json`（演示「打开预览 → 执行 SQL → 截终帧」三步，坐标为示意值，实拍前按 BASELINE 重标）。
