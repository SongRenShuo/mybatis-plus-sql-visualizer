---
name: pitfall-record-tmp-syspath-after-move
description: record/ 一次性探针移入 record/tmp/ 后导入失效——dirname(__file__) 只指向 tmp，import
  ac/bc_common 必失败；幂等兜底块模板 + PowerShell 5.1 按 ANSI 读无 BOM .ps1 导致中文注释乱码粘连的坑
metadata:
  node_type: memory
  type: project
  originSessionId: sess_13bd728a-c47f-4204-a267-f528b9f57f9d
---

2026-10-19 整理主仓 `record/`：可复用六件入库（`ac.py`、`bc_common.py`、`BC_RUNBOOK.md`、`corpus-feed.sh`、`_run_elev_ac.bat`、`probe_menu_pix.py`，提交 `e5beefe1`），332 个一次性探针/日志/剪贴板读数移入 `record/tmp/`（已在 .gitignore）。

## 坑一：移动后 sys.path 断链

`record/tmp/` 里的探针有三类路径写法，只有一类会坏：

| 写法 | 移入 tmp 后 | 处置 |
|---|---|---|
| `sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))` / `HERE=dirname(__file__)` 派生 | **坏**：只把 tmp 加进路径，`import ac/bc_common` 失败 | 插入兜底块 |
| `ROOT=Path(__file__).resolve().parents[1]` | **正常**（tmp 的父就是 record/） | 不用改 |
| 硬编码绝对路径 | **正常** | 不用改 |

幂等兜底块（纯 ASCII、可重复执行）：

```python
# [tmp-fix] ensure script dir and record/ root on sys.path (idempotent)
import os as _os, sys as _sys
_p = _os.path.dirname(_os.path.abspath(__file__))
for _q in (_p, _os.path.dirname(_p)):
    if _q not in _sys.path:
        _sys.path.insert(0, _q)
```

插在编码声明/#!/usr/bin/env python 之类的 header 之后、首个 import 之前。共 132 个文件需要（含 20 个内联 `__file__` 与 34 个 `HERE` 组）。验证用 `python -m compileall record/tmp` + 在 tmp 下真实跑一个 `import ac; import bc_common` 的最小脚本（`exec` 片段验证不成立——没有 `__file__`）。

## 坑二：PowerShell 5.1 读无 BOM 的 .ps1 按 ANSI 解码

批量改文件时先写了带中文注释的 `_fix_tmp_syspath.ps1`，运行后中文注释变成乱码**并与下一行 `import os as _os` 粘连**（该 import 行失效 ⇒ 运行期 NameError，但 `compileall` 仍是绿的，因为粘连后的乱码落在注释行内）。判据：`grep` 文件头出现 `鑴|锛岃|鍏滃簳` 等乱码特征。处置＝**批处理脚本内容必须纯 ASCII**（注释也用英文），且分两步：先正则清除已插入的坏块，再插干净块。

## 复核建议

移动后除了 compileall，还应扫一遍 `grep -l "__file__" record/tmp/*.py | wc -l` 与兜底块计数是否吻合；`REVERSE`/临时修复脚本用完即删，别留在 record/。

相关：[[project-devfullui-acceptance-campaign]]（探针与工具的归属分层：可复用件入库、一次性件进 tmp）。
