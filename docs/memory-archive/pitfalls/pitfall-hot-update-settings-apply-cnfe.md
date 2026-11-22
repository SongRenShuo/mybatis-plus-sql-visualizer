---
name: pitfall-hot-update-settings-apply-cnfe
description: 从「本地安装包目录」热更新后，设置页点「确定」没反应（只有取消能用）——apply 链实例化 configurable 命中已卸载的旧 classloader，ClassNotFoundException 被平台吞掉；完全退出 IDE 即恢复；附「用 MPV-Build 时间戳对提交时间」判包内容的读法
metadata:
  node_type: memory
  type: project
  originSessionId: sess_4043a4bb-4569-4ef9-8e28-84e10cd22b20
---

# 热更新后设置页「确定」无效（2026-11-13 用户真机实证）

## 症状

用户从插件的「本地安装包目录」功能热更新到新构建后：**设置页点「确定」毫无反应（对话框不关），
点「取消」正常**。同日更早还报过一次 `Cannot create configurable: AboutConfigurable`
（`PluginException` + `ClassNotFoundException`）。

## 机制（栈已给出，不要往「打包缺类」方向查）

```
SettingsNonModalDialog$okAction → SettingsEditor.apply → ConfigurableWrapper.apply
 → ConfigurableFactory.createConfigurable → instantiateClass(AboutConfigurable)
 → ClassNotFoundException   （Current thread: EDT，插件 classloader 已被卸载）
```

- 「确定」走 `apply` 链，要对每个 Configurable 实例化并落设置；旧 loader 已卸载 ⇒ 实例化抛 CNFE；
  异常被平台吞掉 ⇒ apply 中断、对话框保持打开，用户看到的就是「点了没反应」。
- 「取消」不触发 apply，所以永远正常。

## 排除「打包少类」的判据（本轮三条，逐条可复现）

1. tag `2026.11.13-beta.1` 的 `src/main/.../config/SectionConfigurable.kt` 里有 `class AboutConfigurable`；
2. 同 tag 的 `plugin.xml` 有该 EP（`instance="…AboutConfigurable"/>`）；
3. 沙盒包 `unzip -l …/mybatis-plus-sql-visualizer-*.jar | rg -c AboutConfigurable` → **1**。

⇒ 类就在包里，这是**运行时 classloader 生命周期**问题，不是发行物缺陷。

## 处置

**完全退出 IDE 再启动**（不是关工程/关设置窗）；重启后设置页保存与 About 页均正常。
这条要写进发行说明的「已知问题」，别让用户以为设置项坏了。

## 待补的代码守卫（尚未实施）

热更新成功后主动探测一次「设置页类能否被当前 loader 实例化」，不可用就直接提示
「请重启 IDE 完成更新」——比让 `apply` 静默失败体面得多。

## 附：判「用户手上的包含不含某修复」用构建戳对提交时间

`MPV-Build` 是**构建时刻的 epoch 毫秒**，比提交时间更直接：

```
MPV-Build 1794561507546 → 2026-11-13 17:18:27
866c6e651（FROM 表名反查实体）        → 17:17:03   ⇒ 在包里
```

所以那句「包里有几笔修复」可以当场答，不必让用户去翻版本号——**版本号不是判据**
（同 [[pitfall-sandbox-jar-contents-not-version]] 的精神：判包内容看字节/时间，不看版本串）。

相关：[[project-startup-read-lock-freeze-20261113]]（本轮的修复主线）、
[[pitfall-bgt-write-project-propertiescomponent]]（同一次日常 IDE 里另一类 workspace.xml 污染）、
[[pitfall-ci-release-notes-and-publish-run-gate]]（发行链路另两处硬口径）。
