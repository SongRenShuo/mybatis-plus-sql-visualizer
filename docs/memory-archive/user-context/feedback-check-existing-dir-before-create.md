---
name: feedback-check-existing-dir-before-create
description: 用户 20261005 指令——新建目录/沉淀脚本前先查项目有没有对应的既有目录，有就不再建；本仓当前无 scripts/tools 目录
metadata:
  node_type: memory
  type: feedback
  originSessionId: sess_0092be8e-1acb-4e8f-9be9-f120dd4fc677
---

2026-10-05（v2026.10.6 发行轮）用户两次明示：

1. 让我把 Gitee API 发行脚本沉淀到「项目下的脚本目录」时，我直接 `mkdir scripts` 并写入——用户随即纠正「你先别创建 scripts 目录 你先看下当前项目有没有对应的目录 如果有的话不再创建了」。
2. 实测本仓**没有** `scripts`/`tools`/`bin`/`util*` 之类的既有脚本目录（`find . -maxdepth 2 -type d -name "script*" -o -name "tool*" -o -name "bin" -o -name "util*"` 排除 build/.gradle/.git/out/node_modules 后零命中），故该 `scripts` 目录已按用户要求删除，脚本未落盘。

**Why**：用户偏好「先看既有约定再动手」，反感代理凭习惯新建目录/文件——仓内目录结构本身是一种约定，擅自新增会引入用户没批准的形态（与 [[feedback-ui-match-existing]]「照抄既有同类实现、禁自创形态」同源，只是对象从 UI 换成目录/文件布局）。

**How to apply**：①要沉淀脚本/工具/新文件时，先 `find`/`ls` 查项目有没有语义对应的既有目录（scripts、tools、dev-support、buildSrc 等），有就用现成的；②确实没有时**先问用户**再决定是否新建，不要直接 mkdir；③本仓已知的「非源码承载目录」＝`dev-support/`（走查/验证辅助物）、`docs/`（文档）、`buildSrc/`（构建逻辑）——脚本类可优先考虑放 `dev-support/` 而非新建 `scripts/`。

**补充（20261008 用户令「这类脚本是不是可以沉淀到项目下对应目录 后面可以复用」）——沉淀时优先「折进既有工具」而非并列新增**：当能力与既有脚本近重复时，把新能力做成既有脚本的**模式/开关**（本轮＝给已有 `dev-support/gitee-release.py` 加 `--replace/--delete-only/--verify-only`，而不是再放两个近乎重复的脚本），只有确实是**新职责**才新建文件（本轮新增 `inspect-plugin-zip.py`，职责＝核产物与发行信息一致，仓内无同类）。**可复用脚本的三条硬要求**：①纯标准库（不引第三方依赖）；②路径用 `os.path.dirname(os.path.dirname(os.path.abspath(__file__)))` 定位仓库根，不写死绝对路径；③**正反向各验**——正向跑通真数据，反向至少造 3~4 个必红用例（本轮 4 例全红：版本不符 rc=2 / 断言不存在的符号 rc=1 / 断言必须存在的符号已被移除 rc=1 / 传入与声称版本不符的 zip rc=1）。
