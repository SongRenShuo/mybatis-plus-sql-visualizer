---
name: pitfall-install-from-disk-without-restart-blocks-hot-swap
description: 用 Install from Disk 装完插件不重启就去点「检查更新」，会踩两个坑——平台以「There are pending changes that require restart」拒绝动态卸载（热替换必然回退重启），且插件读磁盘 plugin.xml 当运行版本导致误报可更新；解析已加载描述符 + 给可操作提示
metadata:
  node_type: memory
  type: project
  originSessionId: sess_cddb53d3-1842-447e-a693-e38c5fbee3e5
---

# 装盘未重启 = 热替换被平台否决 + 版本号读错（coolrequest-vip 2026-11-10 真机）

## 现场（两条日志铁证）

用户装完低版本测试包**没重启**就点检查更新，界面显示 `当前版本: 2026.11.10`，但平台日志里
被卸载的对象是 `2026.11.11`：

```
exclude=[plugin 'Cool Request(REST Client)' (com.hxl.plugin.cool-request, 2026.11.11)]
dynamic unload rejected by platform (family=validate): There are pending changes that require restart
falling back to restart script → InstalledRestartRequired   ← 兜底按设计生效
```

- **磁盘/内存分叉**：Install from Disk 直接把磁盘上的插件文件换掉、并置「挂起变更」；内存里跑的
  仍是旧版。`plugin.xml` 是**磁盘**内容，所以版本标签/更新检查拿到的是新号，实际运行的是旧号。
- **平台级否决**：「有挂起变更需重启」期间，平台拒绝一切动态卸载——这是平台判定，插件无法绕过，
  只能回退「重启生效」（这不是缺陷）。

## 两条落地修法

1. **版本改读已加载描述符**（`PluginManagerCore.getPlugin(PluginId).getVersion()`，反射以防跨版本
   API 漂移；失败才回落 classloader 的 `META-INF/plugin.xml`）。判据：检查更新的基准必须是
   **内存里真正在跑的版本**，否则会出现「明明已是新版却提示可更新」。
2. **拒绝原因给可操作中文提示**：平台原话（英文）保留，另按关键词附一句能照做的说明——
   `pending changes` → 「先点立即重启，重启后再更新就能热替换」；`non-dynamic`/`not unload-safe`
   → 「这类变更只能重启生效」；`dependenc` → 「先关闭/禁用依赖插件或直接重启」。
   不认识的否决原因不要硬凑提示（返回 null，原话照发）。

## 操作纪律（给自己和用户）

- 装完 `Install from Disk` **必须重启**再测更新链路，否则必然撞上面两条；
- 低版本测试包要**含最新代码**（否则跑的是旧代码的安装/收尾路径，测不到修复）——见
  [[feedback-test-build-lowversion-package-recipe]]；
- 状态行显示「已安装，重启后生效」+ 回退原因时，先看原因再判「是不是插件坏了」；这通常是兜底正确工作。

相关：[[project-coolrequest-vip-update-channel-port]]、[[pitfall-self-update-callback-must-be-service-free]]
