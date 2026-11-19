---
name: pitfall-browserutil-open-dir-silent-noop
description: BrowserUtil.open(file URI) 打开目录在 Windows 静默无效——用
  ShowFilePathAction.openFile(File)；另附 isDisplayable 丢弃渲染让状态行永久停在旧读数的坑
metadata:
  node_type: memory
  type: project
  originSessionId: sess_322d2772-c638-4faa-8a34-39507879169e
---

**Why:** T-LocalUpdateDir 真机 20261114 反馈「填好目录点『打开』毫无反应」：默认实现走 `BrowserUtil.open(dir.toUri().toString())`，浏览器不处理目录 URI、失败也不抛异常，整条链静默。`ShowFilePathAction.openFile(File)`（`com.intellij.ide.actions`，243 `app-client.jar` javap 实证 public static）才是按操作系统文件管理器打开路径的专用 API。

**How to apply:**
- 打开本地目录/文件一律用 `ShowFilePathAction.openFile(file.toFile())`，并对异常写回状态行，不再吞；`BrowserUtil.open` 只留给真网页。
- 同轮第二坑：防抖校验回渲染曾按 `if (isDisplayable)` 丢弃——懒加载的设置页未挂载时渲染被丢，状态行**永久停在旧读数**（用户看到「未设置」而输入框有值）。状态行类渲染应无条件落（设文本无副作用）；浏览选完目录后立即校验一次，不等 400ms 防抖。
- 守卫连坐：撤掉 `BrowserUtil.open` 调用点后要同步 `BrowserUtilConvergenceGuardTest` 的豁免清单与 `expectedTotalCallSites` 计数（加时登记、删时回收）。
