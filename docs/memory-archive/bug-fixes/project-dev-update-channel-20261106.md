---
name: project-dev-update-channel-20261106
description: T-DevUpdateChannel（20261106 已实施并合 master 84829511，push-test 8 job 全绿，用户真机走查通过）——连点构建号 7 次解锁「检测更新（开发版）」读 preview.update.json；**P-1 证伪：尾随 lambda 绑最后一个形参，所以 check 签名是 (version, manifestUrl, fetch)**；含 404 友好态 NotPublished、sealed 新分支连带编译点、updateTexts 里恢复可见性
metadata:
  node_type: memory
  type: project
  originSessionId: sess_33620d7f-a4ec-4f92-8adbf35a8fc0
---

# T-DevUpdateChannel：开发者模式读预览更新清单（2026-11-06 立项）

计划册 `docs/开发计划/20261106-1955-T-DevUpdateChannel-开发者模式预览更新通道-开发计划.md`（已登记 `docs/索引/开发计划.md`）。
用户设想：**多次点击设置页「构建版本号」→ 出现「检测更新（开发版）」按钮 → 读 `update/preview.update.json`**。结论可行，且不必新造链路。

## 现状锚点（读过的真实位置）

- 构建号标签是 `PluginSettingsPanelSections.kt:713` 处**内联创建的 JBLabel**（`text = PluginBuildInfo.displayText()`，旁边一个复制按钮）——要挂监听得先提成变量。
- `UpdateSection.checkRunner: () -> CheckResult` seam（`UpdateSection.kt:60-61`）默认 `PluginUpdateRunner.check(PluginVersion.current())`。
- `PluginUpdateRunner.check(currentVersion, fetch)` **内部**取 `UpdateSource.manifestUrl()`，没有 URL 参数。
- `UpdateSource.MANIFEST_PATH = "update/update.json"`，`DEFAULT_MANIFEST_URL` = Gitee raw master，`-Dmpvs.update.manifest.url` 覆盖（进程内首读固化）。
- `update/preview.update.json` 在仓库里但**运行期无人读**。

## 用户拍定的决策（2026-11-06「按你的推荐」）

| 编号 | 决策 |
|---|---|
| D-1 | 连点 **7 次 / 2 秒**（每次点击重开窗口） |
| D-2 | **不持久化**（内存 object）+ `-Dmpvs.update.devMode=true` 直通 |
| D-3 | 只挂构建号，不额外挂版本标签 |
| D-4 | 解锁只改 tooltip + 出现按钮，**不弹窗** |
| D-5 | 失败态复用既有四类分诊（NETWORK / CHANNEL_MISSING / MANIFEST_MALFORMED / VERSION_UNREADABLE） |
| D-6 | **下载 404 友好提示**：新增 `InstallResult.NotPublished`（404/410 专用）+ 发布页兜底链接；其余失败仍 `Failed` |

## 关键设计（三件，缺一不可）

1. **URL 分流**：`UpdateSource.previewManifestUrl()` 优先级＝`-Dmpvs.update.previewManifestUrl` → 通用覆盖的同目录兄弟文件（`manifestUrl().replace(MANIFEST_PATH, PREVIEW_MANIFEST_PATH)`，本地 server 只需加一条路由）→ `DEFAULT_PREVIEW_MANIFEST_URL`；`check()` 加**带默认值**的第三参 `manifestUrl`，既有两处调用 + 5 处测试的 lambda 尾参写法零改动。
2. **渲染保持（最容易漏）**：`updateTexts()` / 语言切换 rebuild 会重建按钮，新按钮可见性必须在渲染路径里按解锁态重算——否则切一次语言按钮消失，会被当成「彩蛋失效」报障。
3. **404 不报错**：现状 `UpdateApi.download` 走 `.throwStatusCodeException(true)` → 404 抛 `HttpStatusException` → `InstallResult.Failed(<异常原文>)` → UI 显示「更新失败：一串 HTTP 文本」。要改成友好文案 + 发布页链接，日志只 `info` 一行。

## 事实（已实测）

- `update/preview.update.json` 的 Gitee raw 地址：先 **302** → 跟随 → **200**，1164 字节，内容正确（现有 `update.json` 同款链路，生产已在用）。
- 写集 10 项（`UpdateSource` / `PluginUpdateRunner` / 新增 `DevUpdateMode` / `UpdateSection` / `PluginSettingsPanelSections` / 中英 bundle / CHANGELOG / 3 个测试类 / 计划册回填）。

## 状态：已交付（2026-11-06）

- 实现 `cda8f3e5` + 计划/CHANGELOG 回填 `7f519f58`，合并 **`84829511`**；**push-test 8 job 全 success**；用户真机走查「连点 7 次 / 切语言 / 404」确认无问题。工作树与分支已清理。
- **P-1（计划里的前提）被证伪并改了签名**：Kotlin **尾随 lambda 绑定最后一个形参**，若把 `manifestUrl` 放末尾，仓库里 14 处 `check(x) { … }` 全编译失败 → 最终签名 `check(currentVersion, manifestUrl = …, fetch = …)`，既有调用点零改动；`checkDefaultManifestUrlIsStableChannel` 钉住「不传第三参仍读正式清单」。
- **`sealed` 加分支会连带编译失败点**：`InstallResult` 新增 `NotPublished` 后，`StartupUpdateCheck` 的 exhaustive `when` 编译不过（写集外必须补一支：复用 WARNING 通知 + 友好文案，去掉设置页链接行的尾冒号）。
- 其余落地细节：`httpStatusOf()` 挖异常链（含被 IOException 包一层）判 404/410；`applyDevVisibility()` 是**唯一**可见性赋值点；`previewManifestUrl()` 在换源值不含 `update/update.json` 时回落默认预览地址（不静默复用正式 URL）。
- 复核读数（主控自己跑，不只信子代理）：`DevUpdateModeTest 7` + `UpdateSourceTest 6` + `PluginUpdateRunnerTest 21` + `UpdateSectionTest 24` + `SettingsRealityGuardTest 4` + 元守卫 `8` ＝ 70 例全绿；**独立反向验证**把 404 分支改成永不命中 → `21/0/0/0` 变 `failures=3`（404/410/wrapped-404 三条），还原后转绿。探针写法注意：`if (false && …)` 会让 `status` 丢 smart-cast 报 `actual type is 'Int?', but 'Int' expected`，要用负值比较（`status == -404`）。
- 提交节奏：按用户令文档-only 不单独推，最后与代码一起推（见 [[feedback-ci-trigger-push-cadence-and-watcher]]）。

相关：[[project-release-workflow]]、[[feedback-update-json-user-only]]（`update/update.json` 仍只有用户能改）、[[reference-local-update-channel-testing]]。
