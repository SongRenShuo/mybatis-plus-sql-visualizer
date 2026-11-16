# T-HotUpdateSettingsGuard：热更新后设置页「确定」无反应

- 立项：2026-11-13 20:40
- 任务源：用户 2026-11-13 真机反馈「刚刚我从本地安装包热更新后，设置页点击确定没反应 点击取消才可以的 MPV-Build: 1794561507546（2026-11-13 17:18:27）」，并提议「是不是自动关闭设置页比较好一些」
- 归属文档：`docs/架构设计/` 暂无对应子系统（属 `update/` 更新通道面），随实施补 `update` 面小节
- 关联：[`project-startup-read-lock-freeze-20261113`]、记忆 `pitfall-hot-update-settings-apply-cnfe`

## 一、现象与既有证据

现象：从本地安装包**热更新**（动态装载）之后，打开设置页点「确定」无反应，点「取消」正常（能关）。

既有取证（上一轮已做，不重复劳动）：

1. 类**在包里**：tag 源码、`plugin.xml` 的 `applicationConfigurable`/`projectConfigurable` 声明、`plugin.jar` 内容三处核对通过。
2. 完全退出 IDE 再启动即恢复 ⇒ 与「本会话内的类装载状态」有关，与包内容无关。
3. 归因：动态更新时旧 classloader 被卸载，设置页 apply 链路再实例化 configurable 时抛 `ClassNotFoundException`，**异常被平台吞掉** ⇒ 点「确定」静默失败、「取消」不涉及实例化故正常。

## 二、处置方案

用户提议「自动关闭设置页」——采纳为主路径，理由：拦截平台的 apply 链不可能，但「让陈旧窗口先消失」在用户视角等价于消除死按钮。

1. **G1 陈旧窗口自动关闭**：订阅 `DynamicPluginListener.TOPIC`（243 实证：无对应 EP，平台自身在 `META-INF/PlatformExtensions.xml` 里也是用 `<listener topic=...>`，第三方插件无该标签 ⇒ 走 messageBus 编程式订阅，与仓内既有口径一致）。回调 `pluginLoaded(descriptor, isUpdate=true)` 且 `pluginId` 为本插件时，在 EDT 上枚举显示中的窗口，经 `DialogWrapper.findInstance(window)` 向上取回 wrapper，命中「设置对话框」（类名以 `SettingsDialog` 结尾）即关闭。
2. **G2 一并告知**：关闭后弹一次性气泡（复用 `cn.song.mybatisplus.visualizer.updates` 通知组）：热更新已生效；若设置页保存无效请重启 IDE；气泡带「重启 IDE」动作，复用既有 `RestartDialogImpl.restartWithConfirmation`。
3. **G3 不碰 apply 链**：不改平台 apply、不包 `Configurable`、不做自研代理（只会把「静默失败」变成「我们的代码也静默失败」）。
4. **G4 不在卸载回调里做 teardown**：仓内纪律（`ExecDocAutosaveHost` / `SqlMapperInjectionBarrierHost` / `BarrierRegistry` 三处 KDoc）明确「卸载回调运行在将被卸载的旧 classloader 上，回调内触达自身类有时序风险」。本监听只实现 `pluginLoaded`（新代际、已装载完成的时刻），不实现 `pluginUnloaded`/`beforePluginUnload`。

## 三、待验证前提（实施前先证伪）

- **P-1**：243 平台 `DynamicPluginListener.TOPIC` 可被插件通过 application messageBus 订阅，且热更新时 `pluginLoaded` 的 `isUpdate=true`（若 `isUpdate` 恒 false，则退化判据＝「本会话内本插件二次装载」）。
- **P-2**：`DialogWrapper.findInstance(window)` 对 `SettingsDialog` 的 AWT window 能取回 wrapper（javap 243 `DialogWrapper.findInstance` 实证：沿 `getParent` 上溯，命中 `DialogWrapperDialog` 即 `getDialogWrapper()`；`DialogWrapperPeerImpl` 的窗口实现该接口）。若失败 → 退化为按窗口类名匹配后 `dispose`。
- **P-3**：热更新时设置页可能同时存在于两个入口（应用级 Settings / 项目级 Settings），两者类名是否都是 `SettingsDialog`（按 `endsWith("SettingsDialog")` 兜）。
- **P-4**：`pluginLoaded` 回调线程（BGT）与 EDT 动作的先后——用 `invokeLater` 归位 EDT。

## 四、实施记录

**已实施（2026-11-13，提交 `29e872abe`）**：新增 `update/HotUpdateSettingsGuard.kt`（APP 级服务，`@Service`，
由 `StartupUpdateCheck` 启动活动取一次完成订阅）+ 定向用例 + i18n 3 键（中英）+ `StartupUpdateCheck` 一行接线。

与计划的三处偏差（都由平台实证驱动）：

1. **订阅点从 `pluginLoaded` 改到 `beforePluginUnload`**（计划 §二 G1 写的是装载侧）。原因：243 的
   `DynamicPluginListener` 五个方法里只有 `beforePluginUnload` / `pluginUnloaded` 带 `boolean isUpdate`
   （`javap util-8.jar` 实证，`pluginLoaded` 只有 descriptor 一个形参）⇒ 装载回调判不出「这次是热更新」；
   且新代际订阅晚于该事件（服务在启动活动/首次使用时才创建），那时也来不及。`beforePluginUnload`
   是唯一「本代际还活着 + 确定是更新」的时刻。
2. **判据去掉 GENERATION_KEY 代际标记**：首版设计用「本次 classloader ≠ 会话里记下的那个」补事件，
   在确定订阅点后成了多余机制，删掉（少一个跨 classloader 状态位就少一处难查的失配）。
3. **G2 的「重启 IDE」动作用 `RestartDialogImpl.restartWithConfirmation`**（与 `StartupUpdateCheck`
   既有「更新完成，重启生效」气泡同款），文案复用既有 `update.notification.restart`。

读数：

- 定向 `HotUpdateSettingsGuardTest` **4/0/0/0**（含 `plugin.xml <id>` 与 `PLUGIN_ID` 一致性守卫——
  判据靠 id 字符串比对，改了 id 而常量没跟会静默失效）；
- `BundleParityGuardTest` **5/0/0/0**（新增 3 键中英双语齐备）。
- **not run**：`pluginLoaded` 之外的 A1~A3 需要真机/沙盒走一次「热装本地包」才能读，本轮未跑
  （留待与下一轮内测包一起验）。

## 五、验收

- **A1**（沙盒）：热装本地 zip → 热更新完成瞬间设置页若开着应自动关闭，并出现气泡。
- **A2**（沙盒/真机）：热更新后重新打开设置页 → 点「确定」应能保存（或气泡指引重启后能保存）。
- **A3**（回流）：未热更新时不得弹气泡、不得关任何窗口（负门用例）。
