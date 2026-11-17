---
name: project-comment-shortcut-keymap-20261113
description: T-CommentShortcutKeymap 已收官（真机日志零告警、已合 master）——两处接管都置
  CustomShortcutSet.EMPTY 再 replaceAction；且必须用公共 registerCustomShortcutSet（setShortcutSet 是 Internal，会拦发行）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_0f21a23d-9183-41ed-8ded-ad7572837917
---

用户 2026-11-13 令「按照你的推荐 立项 实施」：修沙盒日志里 7 条
`ShortcutSet of global AnActions should not be changed outside of KeymapManager`——栈落在
`EditorCommentShortcuts.uninstall` ← `SqlPreviewPanel.removeNotify`（每关一次预览面板刷一条）。

**平台判据（243 `lib/app-client.jar` 字节码实证，`msys` 下 javap 需 `MSYS2_ARG_CONV_EXCL='*'`）**：
- `AnAction.setShortcutSet` 打告警四条件：新集合≠当前集合 **且** 当前集合≠`CustomShortcutSet.EMPTY`
  **且** `LoadingState.PROJECT_OPENED.isOccurred()` **且** `ActionManager.getId(this)!=null`；
- `ActionManagerImplKt.registerAction` 对每次注册都 `registerCustomShortcutSet(new ProxyShortcutSet(actionId), null)`；
  `replaceAction`=unregister+register ⇒ 换入/换回各一次、每次新 proxy ⇒ 必然命中；
- `unregisterAction` 会 `actionToId.remove(action)` ⇒ 被换出的动作此刻 `getId` 为 null。

**修法（`3ce4b7c54` 首版 + `e00e779b5` 横向审计）**：共享 `util/ActionReplaceSilently.kt` 的
`ActionManager.replaceActionSilently`——`if (getId(action)==null) action.setShortcutSet(CustomShortcutSet.EMPTY)`
再 `replaceAction`。**两处接管都要走它**：`EditorCommentShortcuts`（行注释）与 `ChooseDataSourceTakeover`
（控制台「选择数据源」动作组的 install/dispose）。键位仍由平台回填的 `ProxyShortcutSet(id)` 惰性解析；
若目标被别人登记着则跳过预置（不叠加告警）。
否决替代：组件级快捷键（`EmptyAction.wrap`）会丢编辑器右键菜单项接管；「换入后不换回」会让全局动作被长期占据。

**教训（本轮最值钱的一条）**：首版只改了「我诊断到的那一处」，真机复验仍有告警；按「每条告警在
`cn.song.mybatisplus` 的第一个栈帧」归因才发现两处调用点（首版覆盖 5 条、漏 3 条）。故守卫必须是**全树源码扫描**
（`src/main` 只允许助手文件出现 `\breplaceAction\(`），而不是单文件断言——单文件守卫会把同族第二处放过去。

测试：全树源码守卫 + 键位数==keymap 用例；定向 146/0/0；反向两轮各恰 1 红（单文件版 + 全树版）。
合并态 trio：`108bd59e5`（首版）→ `05b3348eb`（第二轮），29 类 142/0/0。

**真机复验通过（2026-11-13，第二轮）**：沙盒 `idea.log` 末次启动 11:26 之后
`ShortcutSet of global AnActions` **零条**；本次会话确有 `prepareDefaultConsole`×2、`panel removeNotify`×2、
`Takeover installed`×1（选择数据源接管真的装过）。按调用点归因的历史读数：`EditorCommentShortcuts.uninstall` 5 条
（全在首版前）+ `PreviewDataSourceActionHost.dispose` 3 条（含首版后 1 条）——两处现已归零。
判「新包是否含修复」用 jar 内符号：`ActionReplaceSilentlyKt.class` 在、`EditorCommentShortcuts.class` 里
`CustomShortcutSet` 引用为 0（私有助手已删）。**已随 master `d25e2a653` 合入并发 v2026.11.13-beta.1，最终修复提交 `f43068228`。**

同批发现：沙盒目录两份 `hs_err_pid*.log` 是**系统内存耗尽**（32G 剩 1.2G，C2 编译平台类
`VisualLineFragmentsIterator::init` 时 malloc 失败），与插件无关——同期本机在跑 gradle 测试 JVM，
属「同机并跑沙盒 + 测试」的内存面风险。

**发行轮拦截（2026-11-13 首发实测，重要）**：`AnAction.setShortcutSet` 在平台是 `@ApiStatus.Internal`
（`javap` 看不到运行期注解，`verifyPlugin` 读注解 stub 才看得见），首发轮 `verifyPlugin` 报
`Internal API usages (1): #Internal method ...AnAction.setShortcutSet(ShortcutSet)` 并直接把发行打成红。
**等价公共写法**：`action.registerCustomShortcutSet(CustomShortcutSet.EMPTY, null)`——243 字节码里该方法体
就是 `setShortcutSet(set)` + `component==null` 直接返回，效果完全一致，但调用面只剩公开 API。
注意传的必须是 `CustomShortcutSet.EMPTY` **单例**（平台按引用比较豁免），自建空 CustomShortcutSet 不算。
已加第三条源码守卫：`src/main` 里禁止出现 `setShortcutSet(`。

**已发行（2026-11-13 收官）**：master `aaaec5544`（发行提交 `f43068228`，tag `2026.11.13-beta.1`）；
GH Release `isPrerelease=true`、asset `mybatis-plus-sql-visualizer-2026.11.13-beta.1.zip` 8177568B
sha256 `52faa7e7…`；Gitee release `id=1304101` prerelease、sha256 与 GH 一致（`gitee-release.py --from-github`
本地跑，CI 只发 GitHub）。CI：release run `43365621616` **14/14 绿**、push-test on `f43068228` 绿。
`update/update.json` 按约定未动（用户手改项）。
