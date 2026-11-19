---
name: reference-documentimpl-settext-equals-replace-string
description: DocumentImpl.setText 的实现就是 replaceString(0, getTextLength, 0, text,
  …)——不存在更便宜的「整文档增量写」，省钱只能靠「内容相等就不写」
metadata:
  node_type: memory
  type: reference
  originSessionId: sess_06adaf7c-f880-4246-bacf-d7ccd625f041
---

243.28141.41 sources jar（`F:/gradle-home/caches/modules-2/files-2.1/com.jetbrains.intellij.idea/ideaIC/243.28141.41/*-sources.jar` 里的 `com/intellij/openapi/editor/impl/DocumentImpl.java:1108-1118`）：

```java
public void setText(@NotNull CharSequence text) {
  Runnable runnable = () -> replaceString(0, getTextLength(), 0, text, LocalTimeCounter.currentTime(), true);
  if (CommandProcessor.getInstance().isUndoTransparentActionInProgress() || !myAssertThreading) runnable.run();
  else CommandProcessor.getInstance().executeCommand(null, runnable, "", DocCommandGroupId.noneGroupId(this));
  clearLineModificationFlags();
}
```

即 `setText` 与 `replaceString(0, len, 0, text, …)` 是同一条路径，唯一额外动作是 `clearLineModificationFlags()`。推论：**「大文本改整文档增量写」这类优化提案是空转**；能省的只有「文本相等就直接返回、不开写命令」（不开写命令就不必抢写锁，这一点在外部持长读锁时才是真收益）。

用途：本仓热路径写点（预览刷新、控制台文档、exec 文件文档）已有 5 处内容判等短路（T-PerfSweep30 / T-ExecDocSync2 落的），2026-11-19 又补 2 处（复用控制台写文档、预览切换清 history）。

相关：[[project-userfreeze-triage-20261119]]、[[pitfall-preview-open-lock-inversion]]。
