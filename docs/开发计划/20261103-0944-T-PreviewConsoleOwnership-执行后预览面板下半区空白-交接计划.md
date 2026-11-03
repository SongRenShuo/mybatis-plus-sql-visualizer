# T-PreviewConsoleOwnership：执行 SQL 后预览面板下半区空白（原生控制台被平台接管）——交接计划

> 2026-11-03 立项交接。来源＝用户真机（IDEA 2026.2.4 / 插件 v2026.11.2，master `6863399a`）连续多轮反馈：
> 「点执行 SQL 后预览窗口没有变化」「连原生工具栏都不出现」「IDEA 控制台有结果、预览窗口空白」。
> 本文件用于**交接**：把已查明的因果链、已落地/已回退的改动、当前卡点与下一步诊断写清，
> 接手者不必重走前几轮的弯路。

## 零、二次复核结论（2026-11-03 10:00，**覆盖下文旧判断**）

前一轮把问题归因成「结果区组件被 DBN 抽走、`ensureResultBottomMounted` 没触发」，并据此加了三层补挂。
二次复核用真机日志逐帧对时序后确认：**这个方向不是根因**——补挂逻辑本身没错，但它补的是一块
**根本不在显示链上的** SplitPane。

真正的因果链（`idea.log` 09:42 逐行实证）：

```
09:42:24.509  embedConsole identity: id=941301681 ... embedMode=PREVIEW      ← 控制台已嵌进 editorHolder
09:42:24.811  in-memory PSI preview document created (len=204)               ← 降级装配晚于嵌入到达
09:42:25.107  late prewarm product consumed: editor upgraded                 ← 迟到换装再装配一次
09:42:27.171  [A49] result host at submit: ... mySplitBottom=null
09:42:30.848  showSql branch=nativeDoc: ... attachedToMe=true                ← 判断说"还挂着"，其实没有
```

1. `editorHolder` 是「预览编辑器 / 原生控制台」**共用且唯一**的显示槽；
2. 冷启首开预热超过预算（实测 820ms > 500ms），`onFallback` 走降级装配、随后迟到产物再换装一次，
   两拍都落在**控制台嵌入之后**；
3. `assemblePreviewEditor` 与 `showPreviewPreparingPlaceholder` 都是无条件
   `editorHolder.removeAll()` 后挂自己的编辑器 —— 于是 `consoleHost` 整块被挤出显示链；
4. 控制台组件本身仍完整躺在 `consoleHost` 里，而 `consoleAttachedToMe()` 只查
   `consoleHost.isAncestorOf(console)`、`embedConsole` 的同实例早退只查 `console.parent === consoleHost`
   —— 两个判据都继续为真，谁都不会把面板修回来；
5. 用户看到的就是插件自建编辑器（带行号、无原生工具栏），结果只落在 IDEA 的 Database 控制台；
   `swapResultIntoPersistentConsole` 每次照样把结果区挂回 split，只是那个 split 不在显示链上
   （`sp.isShowing=false` 也顺带让 `installResultReclaimGuard` 永远静默返回）。

**修复（`mountEditorHolderContent` 单一装配口）**：
- `assemblePreviewEditor` / `showPreviewPreparingPlaceholder` 统一走该装配口；控制台在位时保留
  `consoleHost`，预览编辑器只作文档/状态载体；
- `consoleAttachedToMe()` 与 `embedConsole` 的 `stillMounted` 增加「宿主仍在 `editorHolder` 上」判据，
  宿主被摘下即走重挂路径；`rehostConsoleForTest` 同样复位宿主；`onNativeEditorReady` 不再盲清显示槽；
- 真机判读锚点：`[M36] editor assembly kept native console mounted (console=...)`。

**复现与验证**：`PreviewEditorAssemblyKeepsConsoleTest`（4 例，修复前全红、修复后全绿）；
`ConsoleHostTest`/`ConsoleStealRemountTest`/`SqlPreviewPanelPreparingPlaceholderTest`/
`SqlPreviewPanelPrewarmLateRecoverTest` + 面板与结果换入回归共 134 例全绿。

下文 §二/§四 保留为过程记录，其中「卡点」一节的前提（`consoleComponent` 不是 JSplitPane）已被证伪：
真机 `embed component=...buildConsoleSplitPane$1[...VERTICAL_SPLIT]` 明示它就是 SplitPane。

### 第二轮真机反馈（10:08 会话，控制台已正常显示后暴露）

| 现象 | 根因 | 修法 |
|---|---|---|
| 还没执行 SQL 就先出现结果栏 | DBN 对同一个 exec 文件复用**同一个** `ContentManagerImpl$MyNonOpaquePanel`（真机两次预览 `resultComponent` 同为 id=839493854、首次取到就带 `889x248` 已布局尺寸），新建 `JdbcConsole` 时旧 `Result 1` 页签原样留着；`resultAreaHasContent` 被旧页签喂成 true → PREVIEW 折叠判据失效 | `prepareAfterEditorResolved` 预览路径补 `clearResultContents` + `clearConsoleHistory`（`executeNow` 时不清）；复用路径本来就有，新建路径一直缺 |
| 预览 A 后再预览 B，结果栏还是 A 的 | 同上（旧结果页签没清） | 同上 |
| 控制台工具栏最右侧数据源只剩图标、名称只在 tooltip | 接管组没覆写 `AnAction.displayTextInToolbar()`（默认 false），平台在 `createTemplatePresentation()` 里据此不写 `SHOW_TEXT_IN_TOOLBAR`，`ActionToolbarImpl.createToolbarButton()` 于是选 `ActionButton` 而非 `ActionButtonWithText`（243 `app-client.jar` 字节码实证）；原生 `ChooseDataSourceAction` 该方法返回 **true** | 覆写 `displayTextInToolbar() = true`。**不要用 `isPopup=false`**：非 popup 组会把子项摊平、把整条 header 工具栏挤没（`31d6db38` 已回退） |
| 预览态结果栏仍占下半屏（divider 推到 1.0 底部仍留 ~160px） | 结果区为「执行后不被压回 preferred 84px」设了 160px `minimumSize` 兜底（M36.5.64），`JSplitPane.setDividerLocation` 被它钳住——真机 `bottom≈165px`、divider≈0.67 | 新增 `applyConsoleBottomCollapse`：折叠时清零兜底 + `divider=1.0` + 打 `BOTTOM_COLLAPSED_MARK`，展开时恢复 160px 并清标记；`realignDividerOnce` 的非 force 校正对折叠态让路（否则任何 resize 都会把结果栏弹回 0.6） |
| 每次预览都 WARN `clear console history FAILED: DocumentImpl.setText` | 262 文档写断言：裸反射 `setText` 不在写命令里（旧实现只被 runCatching 吞成 WARN，Output 页签一直留着上一轮回显） | `clearConsoleHistory` 增 `project` 参数，写入包 `WriteCommandAction`；失败日志改打 `cause.message`（原先只有 InvocationTargetException 的方法签名） |
| 折叠生效 2.5 秒后结果栏又长回来（divider 停在 ~0.68） | **`result reclaim guard` 把 160px 最小高度写了回去**：DBN 抢走结果组件 → 守卫挂回时无条件 `candidate.minimumSize = (…,160)` → divider 立刻被钳到 `h-163`。真机日志铁证：`collapse=true divider=304→508` 后紧跟 `result reclaim guard: bottom re-mounted (n=1)` | `reclaimResultBottom` 读 `BOTTOM_COLLAPSED_MARK`：折叠态保持 0 兜底并重新贴底，展开态才恢复 160px；日志加 `collapsed=` 判读位 |
| 折叠后放大又被弹开（h 从 150 涨到 490） | split 变高时 divider 停在**旧的绝对位置**，新增高度全分给结果区 | 折叠态挂常驻 resize 监听重新贴底；`prepare/reuse` 两处折叠加 `height > 0` 守卫（h=0 上算出的 divider=0 更糟）；同实例 PREVIEW 回调也补一次折叠 |

用例：`PreviewResultIsolationTest`（清场语义 + 两条接线断言）。



## 一、现象（当前仍存在）

在 SQL 预览弹窗里点「执行 SQL」后：

- IDEA 的 Database 工具窗（原生控制台）里能看到结果；
- 预览弹窗内**下半区空白**，多数情况下连原生控制台工具栏（▶ / Tx / Playground / 数据源下拉）都不出现；
- 换一个数据源（走「切换数据源」重建控制台）之后，控制台与结果区会正常出现。

## 二、已查明的原因（日志逐条实证）

真机日志（`%LOCALAPPDATA%\JetBrains\IntelliJIdea2026.2\log\idea.log`）每次执行都给出同一组三行：

```
09:42:24  [M36] attachConsole -> JdbcConsole{title=mpsql-exec.sql}
09:42:24  [M36] embedConsole identity: id=941301681, prevEmbedded=null, sameAsPrev=false, embedMode=PREVIEW
09:42:27  [M36][A49] result host at submit: parent=com.intellij.execution.services.ServiceViewComponentWrapper, mySplitBottom=null, stolen=false, viewShowing=true
09:42:27  [M36] embedConsole identity: id=941301681, prevEmbedded=941301681, sameAsPrev=true, sameInstance=true, embedMode=EXECUTE
```

因果链：

1. 预览阶段控制台**成功** attach 并嵌入（`embedConsole ... PREVIEW`）；
2. 平台（DBN）在用户点执行前后，把控制台的**结果组件**重新挂回
   `com.intellij.execution.services.ServiceViewComponentWrapper`（Database 工具窗侧的服务视图宿主）；
3. AWT 重挂父时会把组件从旧容器摘除，而 `JSplitPane.remove(Component)` 会把 `rightComponent`
   置 **null** —— 于是我们持久整控台 split 的下半区变空（`mySplitBottom=null`）；
4. 执行时 `embedConsole` 收到**同一个**实例 → 旧实现按「同实例」早退，只补 header/divider，
   从不检查下半区 → 面板永久空白，结果只留在 IDEA 控制台。

旁证：`[T-ConsoleBind] exec file pin=… → REALIGN`、`runners=1`、`session created` 均正常，
说明**不是**数据源/方言/runner 问题；`viewShowing=true` 与 `mySplitBottom=null` 同拍出现，
是「整块还在面板、下半区被掏」的签名。

## 三、已落地 / 已回退的改动（按时间）

| 提交 | 内容 | 结果 |
|---|---|---|
| `c1b9ed71` | 修 exec 写盘**锁反转**：`projectExecFile` 的 2s 有界等待移出 `synchronized(execFileWriteLock)` | ✅ **已验证**：`request→console-visible` 3.2–4.1s → **1619ms**，`exec doc write EDT dispatch timeout` 归零 |
| `96d854fd` | 注解预览 `prepareDefaultConsole` 的 SQL 源三级回落（editor 为空时用 `current.sql`） | ✅ 已验证：`editor=false, sqlLen=152` 仍能准备成功 |
| `f55126e5` / `027e2aaa` | 数据源枚举改用「原生同源 + 全量并集」 | ✅ 已验证：`getChildren: sources=13` |
| `0761f957` → `b8c5fb12` | 工具栏下拉改为**自己用 `JBPopupFactory` 弹纯列表**（`BaseListPopupStep`） | ✅ 已验证：弹层位置锚按钮、名字不再丢下划线、选源可换 |
| `31d6db38` | 回退 `isPopup=false`（它把控制台 header 工具栏整个搞坏） | ✅ 已回退 |
| `ef6163d5` | `embedConsole` 同实例早退加 `parent === consoleHost` 校验（整块被抢时重挂） | ⚠️ 只覆盖「整块被抢」，不覆盖「下半区被掏」 |
| `2807f0cf` / `16b9d98b` / `e0a8a99b` | 下半区补挂 `ensureResultBottomMounted`（三级回落 + 失败告警），两条 embed 路径都调 | ⚠️ **真机仍未触发**（见第四节） |

## 四、当前卡点（接手第一件事）

**最新一轮（09:41 启动的 IDE，加载了 `e0a8a99b`）执行后，两条补挂分支都没有留下日志**
——既没有 `result bottom re-mounted on embed (was stolen)`，也没有
`result bottom MISSING but no component to re-mount (...)`。

`ensureResultBottomMounted` 的调用条件只有两个：

```kotlin
// SqlPreviewPanelExecution.kt：embedConsole
if (consoleComponent === embeddedConsole && stillMounted) {          // 分支 A
    if (consoleComponent is JSplitPane && consoleComponent.bottomComponent == null) {
        ensureResultBottomMounted(consoleComponent)
    }
}
...
consoleHost.add(consoleComponent, BorderLayout.CENTER)              // 分支 B（整块重挂）
if (consoleComponent is JSplitPane) ensureResultBottomMounted(consoleComponent)
```

两个分支都没日志 ⇒ 只可能是 **`consoleComponent` 不是 `JSplitPane`**（`is` 判定为 false），
而不是「组件取不到」或「路径没走到」。

**下一步诊断（一次构建即可定位）**：在 `embedConsole` 入口加一行

```kotlin
Logger.getInstance(SqlPreviewPanel::class.java).info(
    "[M36] embedConsole enter: class=${consoleComponent.javaClass.name}, isSplit=${consoleComponent is JSplitPane}, " +
    "parent=${consoleComponent.parent?.javaClass?.name}")
```

判读：
- `isSplit=false` → 面板嵌的不是 `buildConsoleSplitPane` 产物，而是别的东西（很可能
  `showSql branch=nativeDoc` 把控制台编辑器单独塞进了 editorHolder，此时工具栏/结果区
  由另一个组件承载）。**那时要改的就不是 split.bottom，而是 nativeDoc 模式下结果区的挂载点。**
- `isSplit=true` 但 `parent` 不是 `consoleHost` → 走分支 B，再查为什么 B 里没日志
  （可能是 `consoleComponent` 已被 dispose / 类型擦除后 `is` 判定异常）。

## 五、验证手段（已就绪，不用点 GUI）

| 层 | 用例 / 命令 | 现状 |
|---|---|---|
| 纯 Swing（秒级） | `./gradlew test --tests "cn.song.mybatisplus.visualizer.ui.ConsoleStealRemountTest"` | **3/3 绿**；反向验证：摘掉 `ensureResultBottomMounted` → 恰「下半区被抢走」1 红，恢复复绿 |
| 真库集成 | `./gradlew testDatabase`（本机 MySQL root/root，`DbConsoleIntegrationTest`） | 9 例：新增「结果组件归属」断言通过；1 例既有 UI016 生命周期用例失败（`BridgeDataBus` 在已 dispose console 上注册，与本改动无调用关系，待单独跟踪） |
| 真机 | 日志锚点 `[A49] ... mySplitBottom=` / `result bottom re-mounted` / `MISSING` | 每次执行都可用 |

## 六、接手须知（这几条踩过坑，务必先读）

1. **换 jar 必须重启 IDE 才生效**。判据：`Get-Process idea64 | Select StartTime` 与
   `plugins/.../lib/*.jar` 的 mtime 比，jar 晚于进程启动时间就是没加载。本轮因此白跑过 3 轮。
2. **构建可能被锁死的 gradle 缓存挡死**（`Failed to delete ... ideaIU-2024.3.7.1-win/jbr/bin/*.dll`）：
   真凶是早前跑 `testDatabase`/`runIde` 留下的**孤儿 `jcef_helper` 提权进程**。判据见记忆
   `pitfall-gradle-cache-transform-locked-build`；必须管理员 PowerShell 杀，杀掉即恢复。
   绕不过时用 `-PgifRecord=true :jar` + `build/libs/*-base.jar`（普通 `.jar` 是旧产物）。
3. **测试没复现不等于产品没问题**：headless 里没有 `ServiceViewComponentWrapper`，平台不会
   做那次重挂；纯 Swing 用例必须**按真实形态造**（split 留在面板、只掏下半区）。
4. 本线所有改动都在 master，未 push；`d9b9ffd8` 之后的调研类提交与本事无关，勿混入。

## 七、建议的收口顺序

1. 加第四节那行 `embedConsole enter` 诊断 → 一次构建 + 重启 → 读 `isSplit`；
2. 若 `isSplit=false`：改查 `showSql branch=nativeDoc` 分支（`SqlPreviewPanelSqlView.kt`）里
   控制台编辑器与结果区各自的挂载点，按同一思路（被摘走即补挂）修；
3. 修完补一条**对应形态**的纯 Swing 用例（nativeDoc 模式：编辑器在 editorHolder、结果区被抢走）；
4. 真机复验原路径（执行一次能跑通的 SQL → 面板下半区出现结果网格），贴日志锚点；
5. 最后跑一次 `testDatabase` 确认归属断言仍绿。
