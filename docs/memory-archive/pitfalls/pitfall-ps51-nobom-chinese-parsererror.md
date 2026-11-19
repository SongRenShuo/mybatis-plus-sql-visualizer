---
name: pitfall-ps51-nobom-chinese-parsererror
description: PowerShell 5.1 把无 BOM UTF-8 的 .ps1 按 ANSI 解析，中文字符串直接炸
  ParserError——子代理「写完没实跑」掩盖此雷；根治=纯 ASCII 重写
metadata:
  node_type: memory
  type: project
  originSessionId: sess_fd128d0e-d320-4865-9871-208872610cd6
---

# PS5.1 无 BOM UTF-8 中文 .ps1 → ParserError（20261101 openget push 脚本实测）

初始化子代理生成的 `push-gitee.ps1` 全文 UTF-8 无 BOM、含中文字符串（"Gitee 推送完成"等），实跑时 PowerShell 5.1 按 ANSI/GBK 解析直接 `ParserError: The string is missing the terminator: "`（中文注释同时变乱码）。**写脚本的代理自己没跑过，雷在主控首次实跑才炸**——「达标即验证」对脚本类交付同样适用。

根治：脚本**重写为纯 ASCII**（注释与输出全英文），任何代码页下都可解析；或保存为带 BOM 的 UTF-8（Write 工具默认无 BOM，故 ASCII 是更稳的路）。判据：`TerminatorExpectedAtEndOfString` + 中文注释乱码粘连（本仓第三次同族，前两次见 [[pitfall-record-tmp-syspath-after-move]]）。

**20261102 第四次（工作区清理脚本）**：`clean-bcde.ps1` 全文只有**一行**中文注释（`# 大日志（>100KB）也清掉，保留 txt/json/py 元数据`），报 `Unexpected token '}' in expression or statement`——**一行中文注释就够炸掉整个脚本**，而且报错位置（文件末尾）与真凶（中间那行注释）完全对不上，很容易去翻括号配对。同批的 `clean-a.ps1` 因注释全英文而正常跑完 26GB 删除，形成干净对照。改成纯 ASCII 后一次通过。

**Why:** 本机 powershell.exe（5.1）无 BOM 即按 ANSI 读，Write 工具产出恰恰是无 BOM UTF-8。
**How to apply:** 让子代理生成 .ps1 时在任务书写死「纯 ASCII，禁中文字面量」；接手脚本先实跑一次 DryRun 再信任。
