---
name: project-preview-console-ownership
description: T-PreviewConsoleOwnership 收官——预览面板原生控制台嵌入的五层真因、三轮误判与最终提交
metadata:
  node_type: memory
  type: project
  originSessionId: sess_3045a9d4-9fa0-4752-affe-3a191dc8b96f
---

2026-11-03 用户真机报障（IDEA 2026.2.4 / v2026.11.2）：点「执行 SQL」后预览面板没变化、连原生控制台
工具栏都不出现、结果只落在 IDEA 的 Database 控制台。后续又暴露「未执行先出现结果栏」「换 SQL 结果不隔离」
「折叠后结果栏回弹」「数据源下拉只剩图标」。

**五层真因**（每层单独修都不够）：

1. `editorHolder` 单槽位被预览编辑器装配（降级 `onFallback` / 迟到换装 `tryUpgradeToLateProduct`）挤出显示链，
   两个归属判据只看「组件在不在宿主里」，宿主被摘下仍恒真；
2. DBN 对同一 exec 文件复用同一个 `RunnerLayoutUi` 内容面板，新建控制台路径未清场 → 旧 Result 页签跨预览残留；
3. 接管组未覆写 `AnAction.displayTextInToolbar()`，平台按 false 选 `ActionButton`（纯图标）；
4. 结果区 160px `minimumSize` 钳住 `setDividerLocation(1.0)`；
5. `reclaimResultBottom` 无条件写回 160px + split 放大后 divider 停在旧绝对位置。

**最终提交**：`88427dc5`（11 笔试错提交压成 1 笔，双仓 force-push 到 `2aa4f080`；旧历史留
`backup/pre-squash-20261103`）。权威复盘见仓库
`docs/经验总结/20261103-1140-预览面板原生控制台嵌入五层真因与三轮误判复盘-经验总结.md`，
计划册 `docs/开发计划/20261103-0944-…-交接计划.md` §零 是二次复核结论。

**How to apply**：再遇到「面板里该出现的东西没出现」，先看组件树与归属判据（不是看截图），
判据要带前后值与调用点标签；详见 [[pitfall-editorholder-single-slot-console-eviction]]、
[[pitfall-dbn-reuses-content-panel-across-consoles]]、[[pitfall-jsplitpane-divider-clamped-by-minimum]]、
[[pitfall-intellij-actiongroup-takeover]]。
