---
name: pitfall-dynamic-ep-requires-dynamic-points
description: 插件热更新（无需重启）的判定链——EP 必须落在平台 dynamic 点位上，否则静默退化为必须重启
metadata:
  node_type: memory
  type: reference
  originSessionId: session_fd35401215781ef6fcc66f662b2fad7b
---

# 热更新「无需重启」的判定链与 EP dynamic 守卫

2026-10-23（T-SettingsSearchA 收尾时核实，提交 `a6a0a1ac`）。**本插件自更新走动态热替换**，这条链决定了它能不能真热起来。

## 判定链（243 平台 `app-client.jar` 字节码实证）

```
DynamicInstallSupport.doInstall
  → PlatformApis.probe() 反射取 DynamicPlugins
  → DynamicPlugins.checkCanUnloadWithoutRestart
      → IdeaPluginDescriptorImpl.isRequireRestart()      ← 首道闸门
      → findMissingRequiredDependency / ide.dynamicPluginVetoer / Registry 开关
```

EP 的 `dynamic` 属性经 `ExtensionPointImpl.isDynamic`（字段 `private final boolean isDynamic`）参与 `isRequireRestart` 的推导。**只要插件有任一注册落在平台声明为非 dynamic 的 EP 点位上，整个插件就退化为「必须重启」**——`allowLoadUnload` 返回 false，`DynamicInstallSupport` 静默回落到 `installRestartScript` 启动脚本路径。**它不报错**，用户只看到「点了更新但没热生效」，属最难发现的体验降级。

## 已立的守卫

`PluginXmlExtensionPointResolutionTest` 两条用例（纯 JUnit 快测，无平台依赖）：
- `插件注册的EP均落在平台dynamic点位上`——把 plugin.xml 每个 EP 注册与平台声明表的 dynamic 属性对账，非 dynamic 即红；
- `自证_dynamic判定能识别非dynamic点位`——正对照，防止扫描退化把所有 EP 都读成 true。

判定面读数（2026-10-23）：平台 EP 声明 2051 条，其中非 dynamic 125 条；本插件 57 个 EP 注册 0 违规。

## 另一个容易漏的点：热更新后索引要能重建

新增 `search.optionContributor` 这类**喂索引**的 EP 时，要确认卸载/装载后索引不会残留旧条目。实证：`SearchableOptionsRegistrarImpl$1` 实现 `DynamicPluginListener`，`pluginLoaded` 与 `pluginUnloaded` **都调 `dropStorage()`**（索引是应用级惰性单次初始化，靠这两个回调失效）。所以热更新后平台会自动重建索引，新版本的设置项能被搜到。

## 陷阱

- **注释里的旧统计数字会误导**：`DynamicInstallSupport` 原写「EP dynamic 属性（本插件 9/9 合规）」，实际当时已 57 个注册。做判定前先实测，别照注释里的数字推断。
- **平台 EP 声明不能硬编码本机路径**：守卫要走 `java.class.path` 收集（既有 `declaredFromClasspath` 的成熟写法），换机器/换版本才不失效。
- **`dynamic="false"` 与「没写 dynamic」等价**（都非 dynamic），解析时按「属性存在且为 true」判。

相关：[[project-settings-search-index-gap]]、[[project-release-workflow]]
