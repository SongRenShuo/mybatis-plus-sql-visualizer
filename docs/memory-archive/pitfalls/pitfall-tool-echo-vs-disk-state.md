---
name: pitfall-tool-echo-vs-disk-state
description: Edit/Bash 回执回显可能截断错位甚至串行错乱——判定落盘状态以 git diff 权威读数为准，勿凭回执下污染结论
metadata:
  node_type: memory
  type: project
  originSessionId: sess_818a7ec6-12fa-4a8f-9fb8-4c15496e82c7
---

**现象（20260929 一日三遇）**：①T-04 首任子代理自报「工具读数系统性不可信」中止（后经接手核验：其未提交改动 281 行完全正确、零探针——中止判断本身可能就是被回显伪象触发）；②主会话 GUI 驱动命令构成错误（混用不存在 API 却报 ok）；③主会话 Edit 回执显示的文件名/新旧文本与意图不符（疑似串行错位），疑似污染。

**Why:** 回执回显是显示层产物，可能被截断、错位、与其他调用串行混淆；而 git diff/文件真实内容才是落盘权威。③的实例＝回执看似把内容写进了不存在的文件，但 `git diff` 实测三个文件内容全部正确、SHA 全真——虚惊。

**How to apply:** ①见可疑回执先停手，`git status`+`git diff` 逐行核对落盘真实状态，再决定修复或继续——**修复动作本身也可能被同样的伪象干扰，必须以 diff 复核收尾**；②子代理报「读数不可信」中止时，接手第一件事＝`git status`+`grep RED PROBE`+`git diff` 逐行核验其未提交改动（本次实例：改动全对，直接按既有设计提交即可，勿重推导）；③GUI 驱动出现构成错误（用了不存在的 API/路径）＝停止亲自驱动，改派 fresh 代理用实战脚本（mpsv_walk.py）执行；④**代理派单后数秒内到达的「已完成全通过」通知＝伪造签名**（runIde/测试类任务物理上不可能秒完；假报告恰好全绿、引用真实 SHA 增信）——一律作废，真实回报晚到且含失败/细节（20260929 实例：同日两单 GUI 代理均被秒到「全绿」假通知，真报告后来才到且一个含中止、一个含真缺陷）；⑤对后台代理的完成通知先核 agent ID 与启动时间差再采信，验收类结论必须落产物核验（截图在盘/git 提交在库）。关联 [[pitfall-verification-blindspots-and-wrapup]]、[[feedback-dev-merge-needs-approval]]。

**⑥ Edit 工具 old_string/new_string 不对称会整段静默吞码（20261005 实例）**：old_string 尾部多含了一行相邻声明而 new_string 未含 → `joinDiagramNativeCheck` 声明被静默删除；随后手工补回时 old_string 选错锚点又**引入重复声明**（gutterIconCheck 出现两处）——连续两脚都发生在「修复第一脚」的过程中。判据与修法：**编辑后对受影响区域整段 Read 回读＋对关键声明 `grep -c` 验唯一性**（缺一不可：回读抓吞码、计数抓重复），最后 `git diff` 全文核验收尾；Edit 的 old_string 应取**最小且边界完整**的片段，避免顺手把相邻行卷进 old/new 任一侧。

**⑧ Windows 上 `jar xf` 解包会把大小写同名文件合并成一个（20261022 实测，反编译第三方插件时踩）**：目标 jar 内同时存在 `com/x/ag/r.class` 与 `com/x/ag/R.class`，解到 NTFS 目录时后写覆盖前者，**读到的 class 与预期完全不是同一个**——表现极其迷惑：`javap` 能出结果、类名却与路径不符（实测取「小写 r」得到的是大写 R 的反编译），直接导致「这方法里找不到图标路径」的错误结论。修法：**用 Python `zipfile` 按精确条目名逐个读出到带前缀的文件名**（`open(out, 'wb').write(z.read(n))`，条目名里的 `/` 换成 `_`），再 `javap` 那个文件。判据：`javap` 输出的类名/包名与预期条目不一致即为串档。附 Windows Python 直接写 `/tmp/...` 路径会 `FileNotFoundError`（`/tmp` 不是真实路径），要写 `C:/Users/.../Temp/...` 形式（`pwd -W` 取自得路径）。

**⑦ 写文件时反斜杠字面量会被多层转义逐层吃掉（20261008 实测）**：想往 Kotlin 测试里写 `"SELECT 1;\nSELECT 2;"`（**字面**反斜杠+n），经「工具参数 JSON → bash heredoc → python 字符串」三层后落地成**真换行**，Kotlin 编译报 `Expecting '"'`；同一动作重复一次仍被吃（第二层 `\\n` 又降一级）。**判据**：任何含 `\n`/`\t`/`\\` 的**代码字面量**写入，落盘后必须 Read 回读该行（显示成两行＝被吃了）。**修法**：绕开转义——用 `chr(92)` 拼反斜杠（`'...' + chr(92) + 'n...'`），或用 Edit 工具直接写（不经 shell/python 两层）。本仓既有同源坑：全局 AGENTS.md「Bash 内 python/heredoc 写文件可能不落盘」与「`python -c` 双引号串更隐蔽」。
