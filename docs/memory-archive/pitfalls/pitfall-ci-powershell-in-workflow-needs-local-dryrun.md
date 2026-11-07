---
name: pitfall-ci-powershell-in-workflow-needs-local-dryrun
description: workflow 里的 PowerShell/命令行片段必须先用真实输入本地预演——本轮两处错误各吃掉一轮 CI（非法 -join 表达式、数组字面量把一行拆成两个元素）；另 JDBC URL 的 & 穿过 gradlew.bat 会被 cmd 吃掉
metadata:
  node_type: memory
  type: project
  updated: 2026-11-05
  originSessionId: sess_1caa1cd6-ecff-4769-98f4-48bfeeca1eb0
---

2026-11-05 夜间全量接上线时，workflow 里的 PowerShell 片段连炸两轮，都是**语法层**问题，本地 10 秒就能发现：

## 两处实证

1. **非法表达式**：`Write-Host "…$($keep -join ',' / '')…"` —— 想写 `-join ','` 手滑成 `-join ',' / ''`。
   表现：步骤 2 分钟就失败（`ParserError` 一类），因为这一行在 `run:` 里执行到才炸。
2. **数组字面量把一行拆成两个元素**：`$out = @("# 注释", "instances = " + ($keep -join ', '))`。
   `@(...)` 里 `+` 与 `,` 的优先级让第二个元素被切成 `"instances = "` 和 `"mysql84, pg16"` 两项，
   落盘变成两行 → 下游测试判「矩阵未声明任何实例」。
   修法：`$out += ("instances = {0}" -f ($keep -join ', '))`（一律用 `-f`，不在数组字面量里做 `+` 拼接）。

## 纪律

- **先在本地用真实输入跑一遍**再推：把 `run:` 块抄进 `pwsh -NoProfile -Command '...'`，
  输入用真实的 secret 替代物（例如本机 `.local/dialect-external-matrix.properties`）。
- **落盘类操作要把产物读回来验形态**，不只看退出码：`Get-Content … | Select-Object -First 3`
  逐行打出来看「是不是我以为的那一行」。本轮第 2 条正是这样抓到的（退出码 0、文件也生成了）。
- 整段脚本可用 `[System.Management.Automation.Language.Parser]::ParseFile` 做纯语法校验。

## 同一类：命令行参数里的特殊字符

JDBC URL 带 `&`（`?a=1&b=2`）时，作为 `-P` 参数穿过 `gradlew.bat` 会被 **cmd 当命令分隔符**截断：

- 表现：e2e 的业务请求全 HTTP 500，而 agent 心路（HELLO/HEARTBEAT）正常——库里没连上；
- 修法：不要走命令行，**写进 `gradle.properties`**（`Add-Content`），由 `providers.gradleProperty` 读回。
  文件注入完全绕开 cmd 解析（本仓 `-PmpvDbUrl` / `-Pe2eDbUrl` 已按此改）。

相关：[[pitfall-ci-artifact-needs-gradle-history]]、[[pitfall-ipgp-product-releases-cache-stale]]

## 同一课第三次：`run:` 的 shell 包装是 `bash -e {0}`

2026-11-06 加夜间兜底派发时，本地用 `bash 脚本` 预演两个分支都过，推到 CI 却报
`Process completed with exit code 1`——日志首行写着 `shell: /usr/bin/bash -e {0}`。

根因：`count=$(... | grep -c '"databaseId"')`，**grep 0 命中时退出码是 1**（而「本轮 0 轮夜间轮」
是正常路径），`-e` 直接中止 step；我自己加的 `set -uo pipefail` 只是"没开 -e"，并不能关掉外层已有的 `-e`。

纪律补充：**预演 workflow 里的 shell 片段必须按平台包装方式跑**（ubuntu 是 `bash -e 文件`，
PowerShell 侧则是 `pwsh -NoProfile`），别用裸 `bash 文件`。零命中属正常的分支一律 `|| true`，
并显式裁剪输出（`tr -d ' '`）。
