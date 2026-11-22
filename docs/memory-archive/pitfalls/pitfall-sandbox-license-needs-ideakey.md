---
name: pitfall-sandbox-license-needs-ideakey
description: 录制沙盒弹 Start Free Trial 的真因常是 config/idea.key 缺失（clean 掉 build/
  就没了），不是 javaagent 没加载；2.x 插件只读平台 bin vmoptions
metadata:
  node_type: memory
  type: reference
  originSessionId: session_502360ae808bfb35c69ac231233cbe92
---

# 录制沙盒许可失效：先查 `idea.key`，再查 agent

2026-10-25 实测。这一轮在「补丁没加载」这个方向上误判了三轮，真因其实是**沙盒 config 里没有 `idea.key`**。

## 症状极具误导性

- IDE 进程命令行里 `-javaagent:C:\Users\USER\.7t\patch\qwdn-afb.jar` **逐字在**
- `jcmd <pid> VM.class_hierarchy` 能列出 **53 个** `com.janetfilter.*` 类，连 `plugins.power` 都在
- 界面照样弹 `Start Free Trial`，`//div[@text='Database']` 零命中

也就是说：agent 确实加载了，但许可还是没生效。

## 根因

**补丁只绕过许可签名校验，本身不产生许可。** 许可实体是沙盒 `config/idea.key`（日常那份在 `%APPDATA%\JetBrains\IntelliJIdea<ver>\idea.key`，5300 字节，`certificate-key` 开头）。

**为什么突然缺**：`build/` 一旦被 clean（发版流程必做），整个沙盒目录消失，config 里那份 key 跟着没了。下次 `runIde` 重建沙盒时不会自动恢复。

## 处置

```bash
SBX=build/MPSV-<沙盒名>/mybatis-plus-sql-visualizer/IU-2026.2.1/config
cp "C:/Users/USER/AppData/Roaming/JetBrains/IntelliJIdea2026.2/idea.key" "$SBX/idea.key"
```

然后重启沙盒。

## 判定成功的两条硬证据（缺一不可）

1. 右上角 `Start Free Trial` 消失
2. `//div[@accessiblename='Database']` 命中 1 个 `com.intellij.openapi.wm.impl.SquareStripeButton`

第 2 条是 Ultimate 能力真解锁的判据——只看横幅会漏判。

## 附带的 API 事实（纠正旧口径）

**IntelliJ Platform Gradle Plugin 2.x 不再 merge 沙盒 `config/idea64.exe.vmoptions`。** 源码实证：`IntelliJPlatformArgumentProvider.asArguments()` 只读 `productInfo.launchFor(arch).vmOptionsFilePath`，即平台 `bin/idea64.exe.vmoptions`。所以：

- 写沙盒 `config/idea64.exe.vmoptions` → **2.x 下无效**（旧记忆里「gradle 会 merge 它」这条已过期）
- 写平台 `C:/Users/USER/.gradle/caches/9.7.0/transforms/<hash>/transformed/idea-2026.2.1-win/bin/idea64.exe.vmoptions` → 有效，但该文件会被 transform 缓存重建覆盖
- 最稳：`buildSrc/src/main/kotlin/mpv-runide.gradle.kts` 的 `runIde` 块里加 `jvmArgumentProviders`，本项目已落 `-Pmpvs.licenseAgent=<jar 路径>` 支持

## 变体：Marketplace 详情页里，水印只落在「内嵌预览窗口」右上角（2026-10-27）

同一份 `Start Free Trial` 水印的另一种出现位置，比沙盒整体未授权更容易漏看：

- 截图 = 设置 → 插件 → Marketplace 搜到本插件，右侧详情页里那台**内嵌的小预览窗口**（截图示例里显示预览图的那一屏）。水印在这个小窗**自己的标题栏右上角**，而整张截图最外层（真实 IDE 窗口）的工具栏是干净的。
- **它跟插件装没装完全无关**——用户问「是不是把插件卸载再截图就行」，答案是**不行**。实测文档站已入库的 `public/demo/install-marketplace.png`（早前的另一轮截图）同一个位置也有它，说明只要在这台机器/这个 IDE 里截 Marketplace 详情页就会带上。
- 放大 3 倍才看得清，正常缩略浏览会漏判。**判据：任何要发布的 Marketplace 截图，先裁出预览区右上角放大检查。**

处置优先级：① 把带水印的内嵌预览区整块裁掉（这一节要传达的「搜什么、结果第一条、安装按钮」全在左半部分和右上角，裁掉反而更聚焦）；② 换非沙盒环境重截。

**延伸（2026-10-27）：水印不止在预览小窗，Marketplace 上架页 10 张全中。** 线上下载的第 1 张截图（API `screens` → `/files/33633/screenshot_<uuid>`，下载须 `-A "Mozilla/5.0"` + `-L`，否则 0 字节）水印落在**主窗口工具栏**上；用户核完全部 10 张确认都有。本地源文件即 `docs/images/demo-*.gif`（`demo-01` 与线上第 1 张 MD5 同为 `64159b20…`/1636719 字节），说明水印是从录制环境带进仓库再上传的。**所以「重传截图」这件事的前提是先把许可修好，否则重截仍会带上。** 详见 [[project-marketplace-screenshot-watermark-cleanup]]。

## 两个假线索（别再追）

1. **`~/.7t/logs/7-tool.log` 里的 `[license] generate ok codes=[IU]`** 是独立 GUI 工具「7-Tool」写的，与沙盒 javaagent 无关，不能当 agent 生效的证据
2. **IDE 日志里 `JVM options: [...]` 列表不含 qwdn 是正常的**——那行只记录平台 vmoptions 文件展开的结果，不是完整命令行；要看真实参数用 `jcmd <pid> VM.command_line`

## 两个反复出现的启动坑（2026-10-25 实测）

1. **transform 缓存重建会清掉平台 `bin/idea64.exe.vmoptions` 的 qwdn 行** → agent 没进 JVM → 许可失效（`Manage Subscriptions`）。手写回 `bin/` 那份能用，但下次重建又没了。
   **根治＝启动时显式传**：`./gradlew runIde … -Pmpvs.licenseAgent=C:/Users/USER/.7t/patch/qwdn-afb.jar`（`buildSrc/src/main/kotlin/mpv-runide.gradle.kts` 已支持，走 `jvmArgumentProviders`，不依赖任何文件）。实测 `HAS qwdn` 稳定。
2. **demo 应用进程会占着 transform 里的 `idea_rt.jar`** → 下次构建报 `Unable to delete directory '…\transforms\af228…'`、`Failed to delete some children`，构建 14s 就 FAILED。
   判据＝`Get-CimInstance Win32_Process` 里那条 `jdk1.8 … -javaagent:…MPSV-GifFresh…mps-sql-agent…jar` 的 java 进程（是 demo 应用不是 IDE）。杀它即释放。
   另一条常被误认的占用源是 **kotlin daemon**；先 `./gradlew --stop` 再查文件锁（`os.rename` 试探）。

## 排查顺序（定例）

1. `jcmd <pid> VM.class_hierarchy -s | grep -c janetfilter` — agent 加载了吗（>0）
2. `ls <沙盒>/config/idea.key` — 许可实体在吗
3. 两条都齐才看界面横幅与 Database 命中

相关：[[pitfall-recording-sandbox-license-agent]]（旧档，merge 口径部分已过时）、[[project-docsrefresh1023-remaining-recordings]]
