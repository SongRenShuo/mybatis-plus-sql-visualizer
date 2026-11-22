---
name: reference-agentdesktop-uia-verdict-20261114
description: agent-desktop（vercel-labs）选型定案——UIA 路线对 IntelliJ
  平台级不可用，不引入验收/录制主链；含工具用法要点与坐标点击遮挡坑
metadata:
  node_type: memory
  type: reference
  originSessionId: sess_d77391b6-4612-4d43-8173-2ded6a71c328
---

20261114 调研 vercel-labs/agent-desktop（AI 桌面控制 CLI，v1.3.1 已 npm 全局安装，Apache-2.0）能否替代/提速 [[runide-demo-verify-workflow]] 的验收与动图录制。**定案：不引入主链**。报告 `docs/调研报告/20261114-1415-agent-desktop桌面自动化选型-调研报告.md`（commit b68b80d34）。

核心结论（平台级，非工具缺陷）：UIA 客户端看不到 IntelliJ 的 Swing 内容树——262 沙盒主窗口 snapshot 仅 5 个窗口装饰 ref（min/max/close），编辑器/工程树/菜单零暴露，`find` 对可见文本 0 命中，Trust Project 对话框同样空树。用独立实现复核了 skill 坑表在册的「SunAwtFrame children=0」先例，两实现同结论（Swing a11y 走 JAB/IA2，不映射原生 UIA）。复评触发＝JetBrains 官方原生 UIA 支持落地或验收目标迁 Electron/WinUI；届时重跑报告 §2.2 两条探针。

**基线（证明工具本身可用）**：WinUI3 记事本语义闭环全通——snapshot 中文元素正常、点「设置」→快照验证 refs 22→30→点「返回」验证恢复，全程无头；ZCode（Electron）snapshot 3s/15s 均超时（个别 Electron 也会拖死它）。

工具用法要点：`agent-desktop snapshot/find/click/set-value` 输出统一 JSON envelope（ok/error.code/recovery）；语义 click/set-value/expand 无头可用，`type`/`double-click`/`focus`/`mouse-click` 须 `--headed`；ref 全窗口唯一否则 AMBIGUOUS_TARGET；PowerShell 里 `@ref` 要加引号（splatting），Git Bash 不用；`--xy=940,582` 必须用等号形式（空格形式报 INVALID_ARGS）。

**新坑（本轮踩实）**：①`screenshot` 走 PrintWindow 能拍到**被遮挡窗口**内容，但 `--headed mouse-click --xy` 是真实点击——点位被别的窗口盖住时点击落进顶层窗口（本轮三下落进用户记事本，幸而只移光标）；坐标操作前先 WindowFromPoint 验归属，或先 `focus-window`。②沙盒 IDE 卡住不动时先 screenshot 看是不是信任工程对话框挡着（UIA 树空，抓不到）。③`close-app --app java` 对沙盒 IDE 可能 graceful/force 都 false 但进程仍在——按登记的沙盒名 grep 命令行后 Stop-Process。④runIde 后台任务随杀 IDE 退出报 exit 1，属正常关停路径不是构建失败。
