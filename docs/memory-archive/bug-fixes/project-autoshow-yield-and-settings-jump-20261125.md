---
name: project-autoshow-yield-and-settings-jump-20261125
description: 2026-11-25 两项 UX 修复已推双仓并 CI 全绿（12b84ae03 / 52322da26 / 634812225 / 0f8d3646c / 9eeee009e）——日志窗自动打开让位 + 从面板点设置落点复位；用户 GUI 实测通过
metadata:
  node_type: memory
  type: project
  originSessionId: sess_3519720c-19c0-41d5-b32b-eabbec094c57
---

2026-11-25 用户两问收口，**已推 Gitee + GitHub 双仓**（走 `dev-support/push-all.ps1`），推送后 CI run 45562438538 8/8 全绿：

- `12b84ae03` **T-AutoShowYield**：自动展开前按锚点查同侧可见工具窗，被占用则不 `show()`——条目照常入缓冲、60s 防抖照常消费；左/底侧栏不冲突；读取失败按「未占用」处理。新增子开关 `logAutoOpenYield`（默认开，父项「发现 SQL 时自动打开日志窗口」关闭时禁用）。定向 5 类 41 例 0 红；反向摘分支恰 1 红。
- `52322da26` **T-SqlLogSettingsJumpAnchor**：`navigateToPage` 在 `hint == null` 时切页后 `scrollRectToVisible(0,0)` 复位到页顶（平台 `ConfigurableCardPanel` 按 configurable 缓存页面并外包 JScrollPane，滚动位置会残留到下次打开；带 hint 的搜索/引导链接仍走 `focusTarget` 滚动+高亮）。计划 `docs/开发计划/20261125-1520-...`；定向 12/0、反向恰 1 红。
- `634812225` 回填 CHANGELOG 哈希、计划 §七、LESSONS L21 Seen 5×、新增 L99（平台 Kotlin 只读属性用属性语法）。
- `0f8d3646c` **CI 红修复**：CI run 45560193140 的 test(1) 红在 `PluginSettingsPersistenceTest` 的「复选框总数应为 51」实得 52——新增 `logAutoOpenYieldCheck` 漏同步全页普查断言；51→52 后本地 20/0。教训已入 LESSONS L100（既有 [[pitfall-new-setting-field-trips-three-guards]] 覆盖同类面）。
- `9eeee009e` 记录**用户 2026-11-25 沙盒实测通过**：让位不抢面板、齿轮跳设置页落在页顶；计划 §六/§七 与索引状态同步。

**没做**：设置跳转的 B（行级锚定 + 黄闪）/ C（SQL 日志页分组重排）/ D（面板内常用项浮层）——用户只拍了 A（页顶复位）。产品口径见 [[feedback-auto-open-must-yield-and-be-switchable]]。CI 重试阶梯重构见 [[project-ci-retry-classify-and-shard-timing-20261125]]。