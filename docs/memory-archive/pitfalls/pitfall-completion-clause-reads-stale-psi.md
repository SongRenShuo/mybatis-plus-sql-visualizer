---
name: pitfall-completion-clause-reads-stale-psi
description: 补全的子句判定读 PSI 文本会拿到「改动前」的 SQL（补全期 PSI 未 commit）——判成 TABLE 就把列名分支整段跳过，表现为「刚开始有补全、删几个字符再补就没了」；必须读编辑器文档文本 + 编辑器 offset
metadata:
  node_type: memory
  type: project
  originSessionId: sess_4043a4bb-4569-4ef9-8e28-84e10cd22b20
---

用户现象（2026-11-13，预览面板 SQL 编辑器里补列名）：「刚开始有补全，如果删除最后几个字符再接着补就没提示了」——看着像补全完全失效，实际是**列名分支被跳过**，表名分支还在。

根因：`SqlCompletionContributor` 用

```kotlin
detectClause(parameters.position.containingFile?.text, parameters.position.textRange.startOffset)
```

判定当前子句。补全触发时文档**刚被改**、而预览/嵌入式编辑器的非项目 PSI commit 是惰性的，读到的还是**改动前**的 SQL：比如已经删到 `... where `，仍按旧文本判成 `TABLE`，于是 `if (clause != SqlClause.TABLE)` 里的列名候选整段不执行。

修法（`11a8c097`）：改读**编辑器文档**与**编辑器坐标系**：

```kotlin
val docText = parameters.editor.document.text
detectClause(docText, parameters.offset.coerceIn(0, docText.length))
```

offset 必须 clamp（旧 PSI offset 与新文档长度可能不一致，`substring` 越界会让 Contributor 抛异常、被 IDE 吞掉 ⇒ 补全静默失效，历史上踩过一次）。

**如何验证（非 GUI）**：行为依赖 commit 时机，单测难稳定复现，改用**源码守卫** `SqlCompletionClauseSourceTest`：断言文件里出现 `parameters.editor.document.text`、且**不再出现** `containingFile?.text`；反向把调用面改回 PSI 文本 → 1 红。该类守卫已在 CI 里连续绿。

**同类面**：任何「在补全/高亮回调里读上下文」的地方都可能拿到落后一拍的 PSI；判据是「这份文本是否可能与当前文档不一致」，能读 document 就别读 PSI。参见 [[pitfall-preview-open-stage-split]] 的 completion 段。

关联：[[project-startup-read-lock-freeze-20261113]]、[[feedback-verify-perf-non-gui-first]]。
