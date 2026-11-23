---
name: pitfall-uniapp-component-swap-dom-and-state
description: uni-app 把手写控件换成 uni-ui 时的三类坑——uni-search-bar 空值态 DOM 里根本没有 input（focusSearchInput 必失手，只能靠 :focus prop）、组件自带内边距只能靠负边距硬抵、旧实现的显式样式（字色/圆角/最小高度）会被组件默认值静默吃掉
metadata:
  node_type: memory
  type: project
  originSessionId: sess_fd128d0e-d320-4865-9871-208872610cd6
---

2026-11-02 openget 把 miniapp 手写交互件换成 uni-ui（`@dcloudio/uni-ui@1.5.12`）时实测三类坑：

1. **`uni-search-bar` 空值态不渲染 `<input>`**——只渲染占位文本。原来那套 `document.querySelector('.og-search-input input').focus()` 在空值时**必然失手**（不报错，就是没反应）。正解＝改用组件的 `:focus` prop 边沿触发（先置 `false` 再 `$nextTick` 抬起），别去查 DOM。
2. **组件自带上下内边距**，与小程序的紧凑布局对不上，实施者用 `margin: -20rpx 0` 硬抵。这类负边距补丁在换机型/换组件版本时会歪，属于**已知技术债**——视觉走查时要专门看这一处纵向对齐。
3. **替换静默丢状态**：旧实现显式设过的字色、背景、圆角、最小高度，新组件一律回默认值。判据＝`git show <替换提交> -- <文件> | grep -E "^[-+].*(color|background|border|radius|height|padding)"`，逐项确认新等价物。

**Why**：这三类都不产生构建错误、不产生运行时异常，只表现为「样式变丑」或「快捷键不灵」——纯靠门禁发现不了，只能靠视觉走查与交互走查。第 1 类尤其隐蔽：`focusSearchInput()` 保留着、测试也不报错，只是永远不生效。

**How to apply**：替换组件前先把「旧实现显式设置过的属性」列成清单逐项核对；替换后必须跑一次真交互（快捷键、空态输入、长列表分页脚），不能只跑构建。uni-ui 里没有的组件（`uni-empty` 在 1.5.12 不存在）就老实留自写，别硬凑。相关：[[project-openget-multiclient]]、[[feedback-ui-match-existing]]。
