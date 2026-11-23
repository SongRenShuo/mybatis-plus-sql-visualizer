---
name: pitfall-versioned-file-newline-crlf
description: 【20261010 起已被 project-line-ending-policy 取代，仅存历史】旧前提「仓库无 .gitattributes」已不成立；
  仍有效两条＝写入后跨调用二进制回读核验、python 一律 wb 写；本条并入「裸 CR 致 i/-text」实例
metadata:
  node_type: memory
  type: project
  updated: 2026-10-10
  originSessionId: sess_56f1c4cc-eafc-4475-9a01-b8837ec5766e
---

# 换行符：改本仓任何文件前先看这条（2026-10-08 T-PluginDescReshape 实测）

> **2026-10-10 已由 [[project-line-ending-policy]] 取代**：仓库已落 `.gitattributes`（`28103a1`，规则＝仓库 LF / 检出 CRLF），行尾不再依赖 `core.autocrlf`；本条前提「仓库无 .gitattributes」已不成立。下面「新建文件默认 LF 要随批转回 CRLF」的人工步骤随之作废（`git add` 由 `text=auto` 自动归一）。**保留作历史踩坑记录**——其中「写入后跨调用回读核验」「用二进制 wb 写」两条仍有效。

**实测事实**：`git config core.autocrlf=true`，仓库**无 `.gitattributes`**（`cat .gitattributes` 报 No such file）；工作区文件是**实体 CRLF**（逐文件实测 `build.gradle.kts` 309/309、`CHANGELOG.md` 1237/1237、`MpSqlVisualizerBundle.properties` 1039/1039、`mcp-optional.xml` 29/29 —— 即 `\r\n` 计数 == `\n` 计数）。`git diff` 会在 touched 文件上报 `warning: LF will be replaced by CRLF the next time Git touches it`——**这条警告本身无害，但它同时是「该文件当前是 LF」的信号**。

**触发本条的场景**：用 `Bash` 内的 python heredoc 批量改文案（本轮改 `plugin.xml` 的 `<description>`，7 处微调一次替换）。

踩到的两个坑：

1. **`io.open(p,'w',encoding='utf-8')` 文本模式写入会把 CRLF 压成 LF**（Windows 上必须显式 `newline=''` 才不转换）——结果整文件换行翻面，`git diff` 从「几十行改动」变成**全文件 442 行重写**（`git diff --stat` 是权威读数：本轮实测两侧都显示「38 insertions / 37 deletions」，但只有回读二进制才能确认换行没被翻）。
2. **Bash 内 python 写文件可能不落盘**（本仓在册坑，见「Bash 内 heredoc 写文件可能不落盘」条）——同一次调用内回读正常，跨调用才见真章。

**规程（照抄）**：

- 首选 **Edit / Write 工具改文件**（保留原换行，无转换）；非要用 python 就加 `newline=''`，并**统一用 `wb` 二进制写**以杜绝转换。
- 写入后**跨调用**回读核验（`open(p,'rb').read()` + `count(b'\r\n')` vs `count(b'\n')`），别信同调用内的回读。
- 若已经翻成 LF：`raw.replace(b'\n', b'\r\n')` 用 `wb` 写回（本轮即此救回），再确认 `lone LF == 0`。
- 判断「文件本来是什么换行」的正确方法＝**同一批文件的二进制计数对照**，不要照抄历史记忆（本仓 `CHANGELOG.md` 换行从 LF 变 CRLF 已发生两次，旧口径全过期）。
- 顺带：Bash 控制台输出**中文会乱码**（GBK 终端），解析带中文的构建日志时按 `raw.decode('gbk','replace')` 读，或只看 ASCII 断言行；`<description>` 这类源文件一律走 python `io.open(..., encoding='utf-8')` 读。

**被改过的文件要逐个验（20261008 本会话扩展）**：同一批改动里**新建**的文件（本轮 `docs/开发计划/20261008-1501-…-开发计划.md`）默认是 LF——若仓库惯例是 CRLF，随批一起用 `wb` 转回（`raw.replace(b'\n', b'\r\n')`），否则该文件在 git 眼里是「整文件重写」。批量验法：一次 python 循环对所有本次改动的文件打印 `CRLF` 与 `LF` 计数，**`lf != crlf` 即为可疑**（正常态两者恒等）。

**Write 工具建的新文档同样是 LF，且工作区 CRLF ≠ 入库 CRLF（20261008 晚验收文档实测）**：`Write` 建 `docs/验收文档/20261008-1623-…-验收文档.md` 后实测 CRLF 0 / LF 388，必须随批转回。**关键补充＝验证要分两层看**：`autocrlf=true` 会让 `git add` 时把工作区 CRLF **归一成 LF 入库**（`git show HEAD:<file>` 实测 blob CRLF 0 / LF 388，与该目录既有文件一致，且 `git status` 干净）——**所以「入库是 LF」是本仓常态，不是问题；唯一要防的是「这个文件在工作区里跟邻居不一致」**（用 `git show` 看既有同类文件到底入库成什么，再决定转不转）。另实测 `command -v unix2dos` 在 Git Bash 可用（0.2-4），但**直接 write 二进制回写更可控**，不必依赖它。坑点复现：`open(p,'wb')` 前先 `open(p,'rb')` 读、替换、写回，**两行若拆到不同 Bash 调用之间会中断**（本会话第一次尝试即因此白跑）。

**同族在册坑**：`pitfall-tool-echo-vs-disk-state`（工具回执不可信，以落盘为准）、`project-doc-conventions`（「CHANGELOG.md 现为全 CRLF」条）。
