---
name: pitfall-msys-comm-zero-overlap
description: Git Bash 的 comm 对含中文/CRLF 的清单给出「零交集」假结果——collation 不一致、CRLF
  行尾、cygheap fork 失败三因叠加；改用 grep -F 文本模式做集合运算
metadata:
  node_type: memory
  type: project
  originSessionId: session_93770b14-7ec0-4d2f-9147-e70319b1c716
---

Git Bash (MSYS) 里 `comm -23 <(sort a) <(sort b)` 对文件名含中文的两份清单可能报**全部不交集**（616/616 互缺），即便逐行字节完全一致（od 验证过）。三种诱因：①两次独立 sort 调用的 collation 不同（locale 漂移）；②一侧清单带 CRLF（grep/重定向混用），comm 字节比较把 `\r` 当内容；③cygheap fork 失败（`child_copy … Win32 error 299` / `fork: retry: Resource temporarily unavailable`）时 comm/sort 输出静默失真。机器内存吃紧（并行 gradle/Docker）时 ③ 高发。

**Why**: 本会话实测对账 docs/索引 缺口时，comm 报 616 全缺、复算又自相矛盾，险些按错误清单回填 600+ 行。

**How to apply**: 集合运算用 `grep -Fxf listA listB`（交集计数）/ `grep -vFxf listA listB`（差集），文本模式自动容 CRLF 与 locale；行数对账用 `wc -l` 两侧独立核；见 `child_copy`/`fork: retry` 字样时本轮所有管线结果作废重跑。关联 [[git-bash-cjk-mojibake-fix]]。
