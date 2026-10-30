---
name: project-marketplace-freeze-reports-assessment
description: 20261028 评估 JetBrains Marketplace 新「UI 冻结报告」功能的适配结论——不换自建
  errorHandler，冻结事件本就走自建需求池，可选混合方案留待需求多时立项
metadata:
  node_type: memory
  type: project
  originSessionId: session_5819b59d59bd38cbc07426b8c9ec72bf
---

2026-10-28 用户让评估 JetBrains 平台博客（2026/10《UI Freeze Reports in Marketplace》）是否适用于本插件，结论已给出（仅分析，零代码改动）：

**文章要点**：Marketplace 插件管理页新增 Freezes 标签页，展示归因到插件的 UI 冻结线程转储（dump-N.txt 每 5 秒一份 + JFR + OTel 指标）；接入方式＝plugin.xml 注册内置 `com.intellij.diagnostic.JetBrainsMarketplaceErrorReportSubmitter`（com.intellij 命名空间，2023.3 起有；Split Mode 须注册在 frontend/shared 模块）；报告到达取决于用户 IDE 设置同意 + 平台归因。

**评估结论（定案）**：
1. **不照做**——本插件 `plugin.xml:344` 已注册自定义提交器 `IdeErrorReportSubmitter`（T-ErrReport-04，接官网需求池，配套 24h 指纹去重/批次合并/双语文案/隐私声明）。`com.intellij.errorHandler` 是按插件归属解析的单值扩展点，换内置提交器＝自建反馈闭环作废，反之 Freezes 页收不到数据；双注册是否被平台容忍未实证。
2. **隐私主张有张力**——全站讲「不发任何使用数据」；自建通道是用户逐条确认的最强同意模型，Marketplace 通道在用户开 IDE 全局「自动发送」后后台自动提交含用户全线程栈的 dump。
3. **零成本已享受的部分**——`IdeErrorReportSubmitter.kt:21` 有真机实证：「Freeze 事件已被归属插件，同通道可达」；文章真正可复用的是转储排查方法论（先看 EDT→多份 dump 对比找持续操作→`analyze-freeze` AI 技能），应作冻结类需求池工单的 SOP 收进排障手册。
4. **可选混合方案**（留待冻结反馈多时立项）：在自定义提交器内部对冻结类事件追加委托给内置提交器（普通类，理论可在 submit() 里实例化转发），保留弹框确认；前提＝javap 验证 243/262 平台 jar 上双注册/程序化委托行为、以及后台自动提交通道是否绕过 submit()。

**2026-10-28 补充（同日第二会话执行「按建议来」）**：排查 SOP 已落档 `docs/调研报告/20261028-0815-Marketplace冻结报告功能评估与冻结工单排查SOP-调研报告.md`（§一功能概述/§二不接入理由/§三冻结工单排查六步/§四混合方案立项前三验证）。执行范围＝只落文档与 SOP，不动 `errorHandler`；后续冻结类需求池工单按 §三 处理；再评估触发条件＝冻结类工单量涨到自建需求池看不过来。

关联：[[project-extension-points]]、[[project-wechat-article-20261026]]（隐私主张）。
