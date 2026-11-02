# magpie 孤儿工作树探针归档（2026-11-02）

来源：`mpsv-magpie-gems` 目录（分支 `t-magpie-gems` 的旧工作树残留，`.git` 已被上轮清理破坏，
目录树遗留约 2.4GB）。该分支 tip `146e3b3c` 经 `git merge-base --is-ancestor` 实测已完全并入 dev，
远端分支本轮已删；但工作树 `record/` 下有 68 个探针脚本从未提交、dev 中不存在，回收前留底。

## 内容

`b1_*`、`mg8v_*`、`re_*`、`trust_dialog*`、`scene_*` 等 GUI 自动化探针（录制/回放/像素锚点类）。

## 未归档的部分（有意排除）

| 类别 | 数量 | 理由 |
|---|---|---|
| `tmp/` 下文件 | 91 | 临时中间产物 |
| `robot-plugins/.../lib` | 55 | 第三方 vendor 依赖（node 模块） |
| 与 dev 同名异内容 | 3 | `scene_n08_dialect_gate.py` / `scene_n10_community.py` / `scene_t3_completion.py`：孤儿侧版本更旧更短（98/50/45 行 vs dev 的 142/245/320 行），dev 为超集 |

## 复用提示

与 `record/insight-probes-20261102/` 同类，方法见 `.agents/skills/ide-ui-acceptance/SKILL.md`。
