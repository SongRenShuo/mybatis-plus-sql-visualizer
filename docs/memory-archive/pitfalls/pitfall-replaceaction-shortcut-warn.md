---
name: pitfall-replaceaction-shortcut-warn
description: ActionManager.replaceAction 替换全局 AnAction 会被平台打 WARN「ShortcutSet of global AnActions should not be changed outside of KeymapManager」——不是我们调 setShortcutSet，而是 replaceAction→registerAction→registerCustomShortcutSet 的内部副作用；平台建议的 keymap defaults/use-shortcut-of 只适用自定义动作；四条消除路径里两条已证伪
metadata:
  node_type: memory
  type: project
  originSessionId: sess_1caa1cd6-ecff-4769-98f4-48bfeeca1eb0
---

# 用 replaceAction 替换全局动作会被平台打「改快捷键」WARN（2026-11-04 查清）

## 场景

`EditorCommentShortcuts`（T-PreviewLineCommentDash，O-134/D136）要把预览编辑器的行注释前缀从 `#` 换成 `--`：预览文件被 `DialectPreMapping` 强制映射成 MySQL 方言 → 平台注释动作沿 commenter 链拿到 `MysqlCommenter` → 行前缀 `#`（javap 实锤 `SHARP_LINE_PREFIX = "# "`）→ 对 PostgreSQL 执行报 `[42601] syntax error`。

它的接管方式是**替换全局动作**：

```kotlin
// install()：首个打标编辑器出现时换入
manager.replaceAction(IdeActions.ACTION_COMMENT_LINE, DashLineCommentAction(original))
// uninstall()：最后一个打标编辑器卸载时换回（日志栈停在这行）
manager.replaceAction(IdeActions.ACTION_COMMENT_LINE, replacement.original)
```

作用域＝编辑器 userData 打标 + 引用计数；非打标编辑器纯委托原动作（`update`/`actionPerformed` 双向委托，菜单文案与图标从原动作模板 presentation 复制），`Ctrl+/` 键位按 id 天然保留。

## WARN 的根因：不是我们调 API，是 replaceAction 的内部副作用

```
WARN - #c.i.o.a.AnAction - ShortcutSet of global AnActions should not be changed outside of KeymapManager.
```

栈（从 `detachConsole → embedConsole` 下来的 `uninstall`）：

```
ActionManagerImpl.replaceAction          ← 我们的调用
  → ActionManagerImplKt.registerAction   ← 平台重新注册这个动作
    → AnAction.registerCustomShortcutSet
      → AnAction.setShortcutSet          ← 平台在这里打 WARN
```

**我们代码里零处调 `setShortcutSet` / `registerCustomShortcutSet`**（grep 整个文件确认）。只要走 `replaceAction` 替换全局 action，平台重新注册时就顺带把快捷键绑到动作实例上，然后自己提醒。

## 为什么平台那条建议不适用

WARN 建议用 `keymap defaults` 或 `use-shortcut-of`——那是给**自定义动作**的建议（自己新建 action 就该在 keymap 声明快捷键）。这里是**替换既有动作**（`CommentByLineComment`），两个建议都套不上。

## 影响面

| 维度 | 情况 |
|---|---|
| 功能 | 正常（打标编辑器走本地 `--` 切换，其余委托原动作） |
| 级别 | WARN，不弹横幅、不进错误统计 |
| 频率 | 每次预览打开/关闭各一条（install + uninstall）；历史日志累计 54 条，单轮 8 条＝4 次开关 |
| 性质 | 长期既有，不是某轮修复引入 |

## 四条消除路径的评价

| 方案 | 评价 |
|---|---|
| 改走自定义 Language + commenter | 最干净（原生动作自然产 `--`），但要注册 language / parserDefinition / commenter；且「自注册方言须配 `lang.parserDefinition`」是已知坑，计划当初就是怕波及真实 MySQL 控制台才没走 |
| `EditorActionManager.setActionHandler`（原计划 B3） | **已证伪**：`CommentByLineCommentAction` 是 `MultiCaretCodeInsightAction` 子类、不是 `EditorAction`，平台对 id 做 `checkcast EditorAction` → 直接 `ClassCastException`（当时单测 15/15 全红） |
| 常驻替换、不卸载 | 能降到「每会话 1 条」（非打标编辑器是纯委托、语义安全）；代价是全局动作长期处于被替换态，插件动态卸载时要保证恢复。**采用前须验普通文件 `Ctrl+/` 仍正常** |
| 接受现状 | 只是日志噪音，功能无误。**2026-11-04 用户定：优先收尾 master 回 port，本项挂起**，已登记进验收账本（`f26021fb`，含根因与四条路径评价），不要自行开工 |

## 关联

[[pitfall-intellij-actiongroup-takeover]]（同族 API `replaceAction`，但那条是接管 ActionGroup，坑在 presentation 与更新线程）、[[pitfall-edt-psi-warm-and-write-safe-modality]]（同轮另两类线程违规）、[[project-dev-vs-master-262-acceptance-20261104]]（该 WARN 出现在哪次真机复验）。
