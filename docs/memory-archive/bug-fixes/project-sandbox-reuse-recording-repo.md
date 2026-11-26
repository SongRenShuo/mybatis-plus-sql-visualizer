---
name: project-sandbox-reuse-recording-repo
description: sql-visualizer-recording 归档仓 20261114 重构为沙盒复用设施 + 同日热更新/autoReload 三项实测结论（两死一稳）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_d77391b6-4612-4d43-8173-2ded6a71c328
---

**T-SandboxReuse（2026-11-14 重构，插件仓分支 `28531e967`/归档仓 `dd83958`/`84379a1`/`14d4d1b`）**：用户专用沙盒复用仓 = `F:/code/Java/Gitee/SongRenShuo/sql-visualizer-recording`（Gitee 私有），从被动归档升级为主动设施，依据同日提效调研 [[project-20261114-perf-research]]。**同日晚已合 master（merge `5cc998574`）并双仓全推**（插件仓 Gitee+GitHub 经 push-all.ps1 令牌通路、归档仓 Gitee `6117a83..14d4d1b`）。

- **P0-1 沙盒 home 迁移**：插件仓 `build.gradle.kts` gifRecord 分支 `sandboxContainer.fileValue(file("../sql-visualizer-recording/sandbox-live/MPSV-GifFresh"))`——`build/` clean 不再带走 idea.key/索引/信任态；旧 `build/MPSV-GifFresh` 可整目录挪过去继承状态。
- **P0-2 状态工具**：`scripts/sandbox-state.py`（check/backup/restore，白名单制、运行中拒写）+ `seed/` 模板（trusted-paths.xml/proxy/idea.key/c.kdbx/c.pwd，出自 2026-10-12 快照）。**20261114 实证**：restore 后启动无 Trust 弹窗、无 Start Free Trial、暖容器进工程 ≈84s（旧冷启动 5~15 分钟 + Trust 卡 8 分钟）；中文语言切换与 MCP Server 授权已落盘并 backup 到 seed-live。
- **P0-3 热更新链**：`scripts/hot-reload-plugin.py`（buildPlugin `-PgifRecord=false` 出包→`sandbox-live/hot-repo/` 落位，增量 4~76s）+ `scripts/auto-reload-probe.py`（step1/2/3 探针）。
- **敏感文件**：`seed/` 与 `legacy/sandbox-config/` 各一份 idea.key/c.kdbx/c.pwd，`.gitattributes` 三组路径全声明 binary/-text；仓转公开/加协作者前须撤下轮换。
- **skill 已同步**：ide-demo-gif 补沙盒新家判据 + **JBPopup 订正**：`ActionMenuItem` 在主窗组件树内可达，`record/robot.py menu_items()` 封装（菜单项无 name 只有 className+几何，按屏幕边界过滤残留坐标）——旧「robot 查不到弹层」系 XPath 写错。

## 20261114 下午真机实测：三条「免重启改版」路，两死一稳（报告 `014a7d912`）

1. **插件自更新链（installFromLocal）**：设置页「检测本地包→下载并安装」全链打通（`localUpdateDir` 预置 `mp-sql-visualizer.xml` 即可），但 262 runIde 沙盒里 `DynamicPlugins.unloadPlugin` 返回 false（`codeSource.location is null`、目录开发态 descriptor）→ 回退重启脚本；正常关 IDE 不执行 DeleteCommand/UnzipCommand（jar 仍是旧版）⇒ **开发沙盒免重启不成立**。
2. **官方开发态 `autoReload`**（用户「一直没成功」的谜底）：`mpv-runide.gradle.kts:50` 本来就是 `autoReload=true`；反编译 Gradle 插件 jar 实证它只传系统属性 `idea.auto.reload.plugins=true`，**监听沙盒 plugins/ 目录变化**的是 IDE 自己。运行中 IDE 把 plugins/ 下已加载 jar 全部内存映射锁死 → `prepareSandbox` 报「用户映射区域打开的文件」、手工 `copy2` 报 `Errno 22` ⇒ 目录永远不会变、重载永不触发。**Windows 死结**，不是配置没开对。另：243 上 `com.intellij.sql.dialect` EP 非 dynamic 平台必然拒卸；验收态 init 脚本主动 `autoReload.set(false)`（ide-ui-acceptance skill §2）。
3. **正路**：暖容器 + 外部重跑 `runIde`（prepareSandbox 重装插件，实测 beta.3 jar 落位，~1.5 分钟/轮）。用户提的「正常模式常驻实例 + 本地测试版本号热更新」机制上能绕开开发态 unload 否决（标准 zip 安装 descriptor），但**必须先实测一轮 unload 能否成功**；版本号卫生＝临时改 gradle.properties、不提交、不写 CHANGELOG、不碰 `update.json`/tag。

**其他 P1 实证**：MCP 导航（启用 MCP Server 后端口 64342、`open_file` 真实文件 1.8s、零像素）✅；robot 语义点击**会动物理指针**（1011,359→按钮中心 1183,833）⇒ 双路并行验收前提证伪。

## 20261114 晚三批收官（分支 `t-standalone-hotreload` → 已合 master `5cc998574`，归档仓 `dd83958`/`84379a1`/`14d4d1b`，两仓已推）

- **第二批（专用常驻 zip 安装实例）未走通**：三轮启动全败且是**在册坑 331 的活体复现**——`-Didea.plugins.path` 被启动器静默重置回 `%APPDATA%` selector 目录（config/system/log 却生效；jcmd VM.system_properties 实证），换 IDEA_PROPERTIES 环境变量/全新 `-Didea.paths.selector` 依旧加载日常插件，standalone/plugins 的 beta.3 未被锁（**rename 探针**：rename 成功=没被加载）。AppData 插件目录改名实验已完整恢复。**标准安装态 unload 验证降级为：用户日常 IDE（2026.11.4 正式版）一键实验（待授权）或反编译 262 插件扫描链立专项**。
- **第三批（Starter+Driver 试点）✅ 打通**：`driver-pilot/` 八条配方（Kotlin≥2.4.0／测试执行器用平台 JBR25／useJUnitPlatform 排 vintage／补 coroutines+serviceMessages／`IdeInfo.ideaUltimate` 扩展属性用 Java 反射调／**必钉 withVersion("2026.2.1")** 否则拉 263 EAP／NoProject 无 IdeFrame 走 `welcomeScreen{createNewProjectButton}`／import 对齐官方 demo MaXal blog-part2）→ `driverPilotUiTest` 全绿 **28s/轮**（IDE 缓存命中）。262 验收腿替代 robot-server 的路径已验证可行，真实验收项 A/B 计时待做；243 腿不适用（框架要 2025.1+）。
## 20261114 夜终审：标准安装态热更新实证成功（用户亲手，263 日常 IDE）

**「正常模式+本地测试版本号」方案终审可行**：用户日常 IDE 实为 **IDEA 2026.3（263）+ marketplace 标准安装**，走设置页本地目录链装 2026.11.14 正式版 zip，`IntelliJIdea2026.3/log/idea.log` 实证 `old version unloaded=true` → `dynamic install succeeded — hot-reloaded to 2026.11.14`，全链 26s（卸载 ~20s+装载 ~6s）。**卸载成败分界=安装形态（标准 zip vs 开发目录），非平台版本**；顺带完成在册「T-DynUpdate263 真机 263 复验」。适用面收窄结论：免重启仅标准安装形态；开发沙盒维持重跑正路。版本号坑：pre-release 低于已发行正式版时扫描器判「已是最新」（2026.11.4 日常装 → 需 ≥2026.11.14 的包）。用户日常 IDE 插件现停 2026.11.14 正式版（无需回滚）。

## 20261114 夜收尾三件（插件仓 `b6e7d72c7`/归档仓 `28046ff`，已推）

- **Driver 第二步**：`DemoProjectPilotTest`（`LocalProjectInfo` 打开 mpj demo）→ IDE 拉起+工程打开+索引就绪 **82.2s**、全链 1m58s 全自动——与暖容器 84s 相当且免 gradle 重建/bootstrap，**262 腿切主链条件满足**，剩插件面板交互用例迁移。共享 util `IdeInfoReflex.kt`。JBPopup 探针未做（driver-client 无显式右键 API，待摸底 `components/elements/PopupUIKt`）。
- **插件裁剪判定不做**：裁 `training` 后 smart-mode 完成时点 9542ms 落在未裁剪历史波动区间（5035~12242ms）内，收益≈0；`disabled_plugins.txt` 维持空。
- CI：合并轮与正式版轮 push-test 均 success；终审 docs 轮 in_progress（docs-only）。

- 本轮全程用独立工作树（用户令：主工作树发行中不干涉）；主树未提交的 docs/skill 改动 cp 入工作树提交后 checkout 还原。
