---
name: pitfall-screenshot-gutter-icon-attribution
description: 用户贴的 IDE 截图里 gutter 图标归属怎么判（按配色比对插件 svg，别把第三方图标算进本插件缺陷面）＋ EDT freeze 转储的归因口径（第三方插件在 EDT 抢读许可）＋冻结真根因＝预览链持读锁做无界全项目索引扫描
metadata:
  node_type: memory
  type: project
  originSessionId: sess_d3d0be86-cba0-4f5a-9f36-9e8ea33eec30
---

用户报「很多 SQL 预览图标、重复了」时，先判**哪些图标真是本插件的**，再谈重复。

**图标归属按配色判死**：本插件所有 gutter provider 共用 `src/main/resources/icons/sqlGutter.svg`——绿色双尖括号 `#4FB477` + 紫色放大镜 `#B48EFF`（另有近白 `#F4EEFF`）。截图里不含这两色的其他标志（如红圈+箭头）是第三方插件（用户日常 IDE 装 CoolRequest/JRebel），**不属于本插件缺陷面**。20261008 实测：用户贴图 21 枚标记里只有 10 枚是本插件的（绿+紫配色逐行聚类出 190 行 2 枚、191/199/206/211×2/214×2/215 各 1 枚，合计 10），其余 13 枚是 CoolRequest 一类按行渲染的标记。

**行号定位法**（截图像素 → 源文件行）：先找行号列文字的 y 起点，行高通常 22px（`y = y0 + 22k`），用已知锚点（如方法签名行）定 y0（本次 y0=14 ⇒ 行号 186）；再按 x 区间（gutter 图标在行号列左侧、分隔竖线右边）做「非背景像素 + 配色分类」按 y 聚类，得到每一行的图标枚数与归属。比肉眼数图可靠，且能判「一行两枚是不是同一插件」。

**每行图标数本身就是判别指征**（20261008 实测与根因逐条吻合）：`selectList(<wrapper 链>)` 2 枚＝假 HQL 锚点（`*Mapper` 结尾接收者 + 内容启发把整条链源码当 HQL）+ 批量锚点（`selectList` 在批量注册表，`WrapperAnchorDetector` 场景 D）；`Wrappers.<User>lambdaQuery(...)` 1 枚＝正确（场景 C 工厂锚点）；`selectPage(new Page<>(1,10), …)` 0 枚＝**负对照**（首参文本无 SELECT/FROM、不在批量注册表），它反过来证明误判判据是「首参文本」而非「链里有没有 SQL 串」。

**EDT freeze 转储别条件反射认领**：`Freeze in EDT` 栈里若 EDT 卡在 `smartAcquireReadPermit → RunSuspend.await → SuvorovProgress.showNiceOverlay`，那只是「EDT 在等读许可」；发起方要看该栈下面第一帧业务类——20261008 实测是 CoolRequest 的 `dev.coolrequest.idea.listener.RunMethodEditorMouseMotionListener.mouseMoved` 在 EDT 直调 `ApplicationImpl.runReadAction`。**归因口径**：我们自己的池化线程若同刻持读锁做长任务（转储里能看到 `ReadAction.compute` + 业务帧 + 索引 IO），那部分是我们的，要修（有界 + 可取消）；EDT 那次读请求的因果顺序需另一态对照实测，不能据一条转储写成结论。

**「预览弹窗空白且关不掉」的真根因形态（20261008 实测，70 秒）**：池化线程在**不可取消的 `ReadAction.compute`** 里做**无上限全项目类短名扫描**（`findClassByEntityName` 慢路径，逐短名一次 StubIndex 查询，索引构建期每次都是随机磁盘读），读锁全程不放 → EDT 等读许可被冻结、平台 `SuvorovProgress` 覆盖层接管事件（EventStealer）＝空白窗关不掉。识别帧＝`SqlPreviewService.kt:1994`（该循环体内层）+ `FreezeCheckerTask.startFreezeReporting`。修法（T-GutterDupFreeze S3）＝慢路径加 `DumbService.isDumb` 护栏（索引构建期按未命中降级且**不写负缓存**）+ 扫描上限与 `checkCanceled` + 逻辑抽成 `psi/jpa/EntityAliasSweep` 便于纯用例钉住「超限即停」；同类第二站在册＝`EntityClassFinder.scanAllClassesByTableName`（同款无界扫描、有自己的缓存与 VO/DTO 快筛但无 dumb 判定，建议随 R70 一并处置）。

**Why**：截图与转储都是「看起来全是我们的」的高误导证据，先做归属再定位，能避免把第三方插件的行为写进本插件缺陷、也避免把「等锁」误判成「死循环」。

**How to apply**：收到同类截图/转储（尤其「重复图标」「点开卡死」类反馈）时按上法先分类，再去看 provider 判定链；写分析与计划时把「实测」与「推断」分开标注。计划落地记录见 [[project-pending-plans]] 的 T-GutterDupFreeze 条（O-124/R70/D108~D110/V132~V134）。相关：[[pitfall-guard-covers-audited-targets]]、[[reference-real-ide-debug-entry]]。
