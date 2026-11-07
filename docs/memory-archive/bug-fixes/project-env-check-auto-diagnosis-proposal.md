---
name: project-env-check-auto-diagnosis-proposal
description: 环境体检自动诊断已立项并实施（20261104）——档1 症状→原因→动作三段式 + 档2 日志模式匹配（10 条规则）；体检单不落盘（LightVirtualFile）只在编辑器页签
metadata:
  node_type: memory
  type: project
  originSessionId: sess_1caa1cd6-ecff-4769-98f4-48bfeeca1eb0
---

2026-11-04 用户提需求「既然都有体检了能不能自动诊断推断问题原因和解决方法？」→ 主控给三档方案 → **用户批「立项并实施」** → 当天落地。

## 已实施（dev）

| 提交 | 内容 |
|---|---|
| `ed5cf853` | 档 1 三段式 + `KnownIssueCatalog` 规则表 |
| `360b37b5` | `IdeaLogTails` 读日志尾部 + collector 第 6 项「近期日志诊断」 |
| `0a3d3ade` | 主控补：有源但未绑定时事实行加提示（补代理登记的偏差 1） |
| 后续 | 诊断规则从「缺陷指纹」改「症状类别」，7 → 10 条（见 [[pitfall-known-issue-rules-must-be-symptom-class]]） |

- **档 1**：`EnvironmentCheckItem` 增 `symptom` / `cause`（默认 null，既有构造点零改动），`guidance` 语义收窄为「动作」但**不改字段名**；非绿灯项渲染「症状 / 原因 / 动作」三行、缺段跳过；绿灯项只留 facts。五个既有规则逐条改写（方言按 unresolved/ambiguous/conflict/unavailable 四态分叉原因，增强捕获七态各一对文案）。
- **档 2**：读 `PathManager.getLogPath()/idea.log` **尾部 256 KB**（`RandomAccessFile` seek 后跳到首个 `\n` 之后，规避多字节截断与半行；只读当前文件不读轮转）；匹配单位＝「一行 + 其缩进栈续行」；**大小写不敏感子串不用正则**（用户日志特殊字符会炸回溯）；命中去重 + 次数 + 上限 5 条，按严重度→次数排序。
- **隐私硬约束**：报告只含规则 id / 结论文案 / 命中次数，**绝不写日志原文**（用户 SQL、连接串、路径）。测试写法：先用真 ui-002 记录 + 独有秘密串（`payroll_secret_zzz`、`jdbc:mysql://10.9.8.7`、`/home/alice/...`），**先断言命中 ui-002（防假绿）再逐片段断言不含**。
- 档 3（推理引擎/因果图）不做。

## 两个实测事实

1. **体检单不落盘**：`PluginSettingsPanelEnvCheck` 用 `LightVirtualFile`，只在编辑器开一个页签（文件名类似 `sql-visualizer-environment-check.txt`），关掉就没了、磁盘上不存在。用户问「生成到哪了」时的标准答法＝那个内存页签，要留档用 `Ctrl+Shift+S` 另存。
2. **有源但未绑定这个态原先是绿灯**：`count > 0` 就返回 OK，而用户真机最关心的恰是「7 个源但右上显示未绑定」。修法＝等级仍保持 OK（不打扰刚配好还没执行的人、不翻转总评），**但事实行加一句**「未绑定任何数据源、也没有上次使用记录——预览打开时可能需要选一次」。
3. **真机实测**（用户 2026-11-04 12:42 沙盒）：体检 **361 ms** 出结果（不再卡住），三段式在方言/增强捕获/捕获覆盖三项都生效，日志诊断扫了 256 KB/12 ms——顺带把「`PathManager` 日志路径」这条待验点验掉了。

相关：[[pitfall-unbounded-collect-leaves-ui-in-progress]]（同一条链的 UI-005）、[[pitfall-known-issue-rules-must-be-symptom-class]]（规则写法）、[[dev-vs-master-262-acceptance-20261104]]。
