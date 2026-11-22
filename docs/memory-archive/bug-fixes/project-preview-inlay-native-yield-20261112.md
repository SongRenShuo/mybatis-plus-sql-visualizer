---
name: project-preview-inlay-native-yield-20261112
description: T-PreviewInlayNativeYield（20261112）——预览 INSERT 列名 inlay 与 IDEA 原生
  sql.column.names.inlay.hints 重复，计划只出文档：让位闸门 NativeColumnInlayYieldGate + D1~D3
  待拍板 + P-1 分诊先行
metadata:
  node_type: memory
  type: project
  originSessionId: sess_4c2cb0e3-1fcd-4800-9cf6-25b2032acad2
---

2026-11-12 用户贴 cust-big-demo `CustAddressMapper.xml` 的 insert 预览截图报障：「插件的字段提示和 idea 原生的字段提示冲突了」+ 诉求「优先 idea 原生，原生没有的插件再提示」（原则见 [[feedback-prefer-ide-native-yield-on-overlap]]）。本轮**只出计划、未动 `src/main`**。

- 计划：`docs/开发计划/20261112-1350-T-PreviewInlayNativeYield-预览INSERT列名提示让位IDEA原生-开发计划.md`；已在 `docs/索引/开发计划.md` 首行登记（状态「仅出计划」）。建议分支 `t-preview-inlay-native-yield`。
- 我方当事人：`plugin.xml:314-315` 注册的 `PreviewInsertColumnInlayProvider`（O-44b，20260920 照 DBX `insertValueHints.ts` 做），只在预览编辑器挂 INSERT 的 VALUES 表达式 / SELECT 投影目标列名；闸门三道具在 `PreviewInsertColumnInlayProvider.kt:163-171`，**没有让位门**；零设置项（EP 未注册 settingsKeyId），平台 inlay 开关是语言级的 ⇒ 用户无法只关我方。
- 原生当事人与平台机制见 [[reference-intellij-native-sql-column-inlay]]（providerId `sql.column.names.inlay.hints`，`in.insert.values` 默认开；逐位去重机制上不可行）。
- 方案：新建 `ui/inlay/NativeColumnInlayYieldGate.kt`（双 EP 枚举 + providerId/类名候选链 + `DeclarativeInlayHintsSettings` 读开启态 + 懒缓存只缓存"在位" + 探测失败**保留功能** WARN 一次 + `probe`/`resetForTest` 测试缝），在 `getCollectorFor` 最前 `return null`。步骤 T-NCY-01~07。
- 待用户拍板：D1 让位粒度（推荐 A 能力级整体让位；C 用我方 schema 缓存做启发式会因缓存未预热复发重复，不推荐）/ D2 原生被用户手动关掉时（推荐 A 同样让位，尊重其明确意图）/ D3 是否加插件三态设置项（推荐 A v1 不加）。
- **P-1 必须先分诊**：截图头部写着「未绑定数据源」，与「原生要 schema 解析才画得出」有张力。判据＝263 上 Settings → Editor → Inlay Hints → SQL 取消 `INSERT … VALUES` 列名后重开预览，重复的那一枚应消失；不消失则重复另有成因（我方重复投递等），D1~D3 全部重排。
- 其余待验证：P-2 243/253/262 上的原生注册面；P-3 `isOptionEnabled` 的 providerId 口径（源码有 `getSerializedId`，先用运行时探针打实测）；P-4 243 的声明式 EP bean 形态；P-5 测试平台（`build.gradle.kts:157` 是 **IU 2024.3.7.1**，DatabaseTools 在位）里原生是否被 EP 枚举到 ⇒ 既有 4 个 inlay 测试类必须注入 `probe={false}`，否则整体短路假红；P-6 `isOptionEnabled` 带 `@RequiresReadLock`，BGT 上是否要包 ReadAction。