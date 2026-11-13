# T-CommentShortcutKeymap：预览接管行注释动作时刷平台 Keymap 告警

- 立项：2026-11-13（用户令「按照你的推荐 立项 实施」）
- 触发：沙盒真机日志里 7 条 `ShortcutSet of global AnActions should not be changed outside of KeymapManager`，
  栈落在 `EditorCommentShortcuts.uninstall` ← `SqlPreviewPanel.removeNotify`（我方 dispose 路径）
- 关联：[T-PreviewLineCommentDash（O-134/D136）](../变更记录/)——接管层位就是 `ActionManager.replaceAction`，
  见 `util/EditorCommentShortcuts.kt` 类注释

## 一、读数（2026-11-13 沙盒）

- 日志：`.worktrees/merge-preview-trio/.intellijPlatform/sandbox/mybatis-plus-sql-visualizer/IU-2024.3.7.1/log/idea.log`
  - `WARN - #c.i.o.a.AnAction - ShortcutSet of global AnActions should not be changed outside of KeymapManager.` **7 条**
  - 同一日志 `ERROR - ` **0 条**；无 `previewAsync compute FAILED` / 降级 / 慢操作断言
  - 样本栈：`AnAction.setShortcutSet` ← `registerCustomShortcutSet` ← `ActionManagerImplKt.registerAction`
    ← `replaceAction` ← `EditorCommentShortcuts.uninstall(:89)` ← `SqlPreviewPanel.removeNotify(:971)`
- 同目录两份 `hs_err_pid*.log`（PID 87600 / 39700）是**系统内存耗尽**（`Chunk::new` malloc 失败，32G 只剩 1225M，
  崩溃线程 `C2 CompilerThread0` 正在编译平台类 `VisualLineFragmentsIterator::init`），与插件逻辑无关——
  同期本机还在跑 gradle 测试 JVM；属环境面，不在本项范围。

## 二、平台判据（IDEA 2024.3 / `app-client.jar` 字节码实证）

1. `com.intellij.openapi.actionSystem.AnAction.setShortcutSet(ShortcutSet)` 打告警的**四个条件**（偏移 9–80）：
   ```
   新集合 != myShortcutSet
     && myShortcutSet != CustomShortcutSet.EMPTY       // if_acmpeq EMPTY → 直接跳过告警块
     && LoadingState.PROJECT_OPENED.isOccurred()
     && Application.getServiceIfCreated(ActionManager) != null
     && ActionManager.getId(this) != null              // 该动作在当前注册表里有 id
   → LOG.warn(PluginException.createByClass("ShortcutSet of global AnActions …"))
   ```
2. `ActionManagerImplKt.registerAction`（偏移 180–193）对**每次注册**都执行
   `action.registerCustomShortcutSet(new ProxyShortcutSet(actionId), null)` → 内部就是 `setShortcutSet`。
   `replaceAction` = `unregisterAction` + `registerAction`，所以**换入与换回各触发一次**；每次都是新
   `ProxyShortcutSet` 实例 ⇒ 「新集合 != 当前集合」恒成立 ⇒ 只要当前集合不是 `CustomShortcutSet.EMPTY`，
   告警必然命中。
3. `ActionManagerImplKt.unregisterAction`（偏移 54–80）会 `state.actionToId.remove(action)` ⇒
   **被换出的那个动作实例在注册表里已无 id**，`ActionManager.getId(it)` 返回 null。

## 三、方案

| 编号 | 内容 | 落点 |
| --- | --- | --- |
| M1 | 换入/换回统一走 `ActionManager.replaceActionSilently`：**先 `action.setShortcutSet(CustomShortcutSet.EMPTY)`，再 `replaceAction`**。这一步自身不告警（动作此刻未在注册表，`getId` 为 null），随后平台的回填命中「当前集合 == EMPTY」豁免 | `util/EditorCommentShortcuts.kt` |
| M2 | 键位绑定不变：平台回填的仍是 `ProxyShortcutSet(actionId)`（按 keymap 惰性解析），换入/换回后键位数与 keymap 一致 | 同上（由用例钉住） |
| M3 | 源码守卫：`replaceAction(` 在类文件里只允许出现 1 次（即 `replaceActionSilently` 内），且必须先出现 `setShortcutSet(CustomShortcutSet.EMPTY)` | `EditorCommentShortcutsTest.test源码守卫_…` |
| M4 | 行为用例：换入后 / 换回后的 `shortcutSet.keys.size` 必须等于 keymap 对该 id 的映射数 | 同上 |

为什么不改用「组件级快捷键」（`EmptyAction.wrap` + `registerCustomShortcutSet(set, component)`）：
那条路会**丢掉编辑器右键菜单项**（菜单项引用的是全局 action id），等于回退 O-134 已收口的一半覆盖面；
本方案保持 `replaceAction` 语义不变，只消除平台告警。

为什么不改成「换入后不再换回」：那样全局动作会被我方实例长期占据，别的插件在期间的替换会被吞掉；
现有 `uninstall` 的「只在当前在位仍是本接管件时才换回」守卫要继续保留。

## 四、验证

- 定向：`EditorCommentShortcutsTest`（原 14 例 + 新增 2 例）、`convention.*` 全族
- 反向：删掉 `setShortcutSet(CustomShortcutSet.EMPTY)` 一行 → 源码守卫必红（探针读数见实施记录）
- **真机**：用户在沙盒里开一次预览再关面板，`idea.log` 应**不再出现**该 WARN（GUI 验收归用户；
  日志捕获不做单测——仓内没有可注入的 Logger 通道，且该告警在单测环境未必触发
  `LoadingState.PROJECT_OPENED`，假绿风险高于收益，故用源码守卫兜底）

## 五、边界（不做）

- 不动 `DashLineCommentAction` 的接管语义与作用域（编辑器打标 + 引用计数），不扩大接管面。
- 不接管块注释 `Ctrl+Shift+/`（O-134 已裁：MySQL/PG 块注释语义一致）。
- 不改 `SqlPreviewPanel.removeNotify` 的调用时机（那是另一条在途线 T-PreviewLineCommentDash 的既有路径）。

## 六、实施记录

实施于工作树 `.worktrees/t-comment-shortcut-keymap`，分支 `t-comment-shortcut-keymap`，自 `t-merge-preview-trio`
`1dde82c78` 起（该分支即当前验收构建的来源）。

### 6.1 落地

| 项 | 内容 |
| --- | --- |
| M1 | 新增 `private fun ActionManager.replaceActionSilently(actionId, action)`：`if (getId(action) == null) action.setShortcutSet(CustomShortcutSet.EMPTY)` 后 `replaceAction`。`install` / `uninstall` 两处改走它 |
| 细节 | 预置加 `getId(action) == null` 前提——正常换入（新实例未登记）与正常换回（`unregisterAction` 已清 `actionToId`）都成立；若该动作被别人登记着（第三方换过），预置本身会告警，故跳过、交给平台回填 |
| M3 | `EditorCommentShortcutsTest` 新增源码守卫：剥注释后 `\breplaceAction\(` 只允许 1 处，且必须出现在 `setShortcutSet(CustomShortcutSet.EMPTY)` 之后 |
| M4 | `EditorCommentShortcutsTest` 新增行为用例：换入/换回后的 `shortcutSet.shortcuts.size` 必须等于 `activeKeymap.getShortcuts(ACTION_COMMENT_LINE).size` |

### 6.2 读数（2026-11-13）

- 定向：`EditorCommentShortcutsTest` **17/0/0**（原 14 + 新增 2 + 1 例既有并入）+ `EditorCopyShortcutsTest` +
  `convention.*` 28 类，合计 **146/0/0**（30 类）。
- 反向：把 `setShortcutSet(CustomShortcutSet.EMPTY)` 注释掉 → `EditorCommentShortcutsTest` **17 例 1 红**，
  恰为源码守卫，message「必须先 setShortcutSet(CustomShortcutSet.EMPTY)（**-1**）再 replaceAction（105）」；
  还原后 17/0/0。
- not run：**真机日志复验**（需在沙盒里开一次预览再关面板，`idea.log` 应无该 WARN——GUI 验收归用户）；
  全量门禁（交只读收口车道）；`verifyGate/buildPlugin`（未动构建与 EP）。
- 实施中两处自纠：① 测试 API 用 `shortcutSet.keys`（243 已无此 getter，实为 `shortcuts`）；
  ② 源码守卫里最早写成 `".replaceAction("`（辅助函数是扩展函数、调用无点号），改成 `"replaceAction("`——
  两处都被定向跑抓出来，未进提交。

