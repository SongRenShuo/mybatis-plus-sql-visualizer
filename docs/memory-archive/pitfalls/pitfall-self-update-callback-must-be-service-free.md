---
name: pitfall-self-update-callback-must-be-service-free
description: 自更新（热替换）后的旧实例运行在已卸载的 classloader 上——收尾回调、设置页 isModified/getSettings、
  action update 任何插件服务调用都会跨 loader 强转 CCE；正确做法＝构造期捕获文案 + 无持久化页用脱离状态
  + 关掉死对话框 + 服务入口兜底
metadata:
  node_type: memory
  type: feedback
  originSessionId: sess_cddb53d3-1842-447e-a693-e38c5fbee3e5
---

# 热替换收尾回调必须「服务无关」（coolrequest-vip 2026-11-10 实证）

## 症状

插件热更新**成功**（日志有 `dynamic update: old version unloaded=true` → `new version installed=true` → `dynamic install succeeded`），但设置页「更新」一直停在「正在下载并安装…」，永不前进。

## 根因（idea.log 取到的完整栈）

```
ClassCastException: dev.coolrequest.common.state.SettingPersistentState cannot be cast to
  dev.coolrequest.common.state.SettingPersistentState
  (loader PluginClassLoader @365bbc22; ... loader PluginClassLoader @43e02fa3)
  at SettingPersistentState.getInstance:39
  at ResourceBundleUtils.getString:35
  at UpdateSettingConfigUI.renderState:298   ← renderInstall ← 安装回调
```

热替换后旧 `PluginClassLoader` 已卸载，但**安装回调仍在这个死 loader 上执行**。回调里读语言包 → `ResourceBundleUtils` → `SettingPersistentState.getInstance()`（应用级服务）→ 平台服务表返回的是**新 loader** 的实例 → 跨 loader 强转直接崩，渲染第一步就抛。

mpvs 的 `DynamicInstallSupport` KDoc 早写过这条（「回调内只触达平台 API 与纯函数」），但在 **UI 收尾**这一层没有落实——踩的正是这条。

## 修法（可复用模式）

1. 构造期/语言切换时把渲染需要的文案全部**捕获到字段**（`captureTexts()`）；
2. 渲染路径（`renderState` / 失败文案 / 回退原因行 / 兼容性提示）**只读字段**，不调语言包；
3. 收尾若再抛，兜底 `catch (Throwable)` 用已捕获文案做最小收尾（清进度条 + 置终态），绝不停在中间态；
4. 连 `Logger.getInstance(插件类)` 在兜底里都别用（同样可能触插件查找）——用 `System.err`；
5. 热更新成功后可以就地刷新版本标签（用包里的新版本号 + 已捕获前缀）。

## 护栏（防回潮）

`ResourceBundleUtils` 加 JVM 内查询计数 `lookupCount()`，用例断言「renderInstall 前后计数不变」= 安装收尾零查包。反向探针：把 `getString` 塞回 `renderState` 必须变红。

⚠️ **探针假绿陷阱**：本轮第一次跑反向探针时测试仍绿——gradle 用了**旧 class**（源码 mtime 新于 .class，但没重编）。判据：探针后先 `javap -c` 确认产物里真有那处调用，再跑测试；否则「绿」只是没测到。


## 第二条路径：设置对话框的「确定」（2026-11-10 真机栈，版本 2026.11.11 修复）

热替换后**整只设置对话框**都属于旧 classloader。平台 `doOKAction` 会先遍历组内每个页调
`ConfigurableBase.isModified()` → 各页的 `getSettings()`。任何一页走
`SettingPersistentState.getInstance()`（应用级服务）都会在返回处跨 loader 强转崩，异常打断
对话框关闭 → 现象是「确定点了没反应，只有取消能用」。

修法（三层，缺一不可）：
1. **无持久化项的页改用脱离状态**：`UpdateConfigurable.getSettings()` 返回 `new SettingsState()`，
   不碰应用级服务（护栏用例：`assertNotSame(appState, getSettings())`）；
2. **成功收尾时关掉这只死窗口**：`DialogWrapper.findInstance(root).setOKActionEnabled(false)`
   + `Timer(1500)` 后向祖先窗口派发 `WINDOW_CLOSING`（DialogWrapper 走取消语义，不触发 apply）
   —— 注意 `getOKAction()` 是 protected，只能用公开的 `setOKActionEnabled`；
3. **服务入口加兜底**：`SettingPersistentState.getInstance()/getInstance(project)` catch 后返回
   不落盘的进程内兜底实例——覆盖旧 loader 残留的 action 刷新（真机上 `GenerateCommitMessageAction.update`
   就是这么崩的）。旧实例马上会被丢弃，兜底只为让它安静退场。

同源教训：**热替换后旧实例的所有 UI/action 都是「早晚要死」的**，凡是平台会主动回调的入口
（isModified/apply/reset/update action）都不能再取插件服务。

相关：[[project-coolrequest-vip-update-channel-port]]、[[pitfall-fake-green-test-assertions]]、[[pitfall-tomcat-url-factory-jvm-once-after-swap]]
