---
name: project-param-name-truncation-20261112
description: T-ParamNameTruncation（2026-11-12 已落本地 master
  `2096fa2f5`，未推送）——预览左栏长参数名被截断且悬停拿不到全名，方案 A＝列宽随视口自适应 + 全名 tooltip 恒可得
metadata:
  node_type: memory
  type: project
  originSessionId: sess_70366d0b-7686-488d-b278-95e52f9f5325
---

用户真机反馈：SQL 预览弹框左栏参数名（foreach 集合表达式 `criteria.criteria` 一类）被中段省略，鼠标悬停也拿不到完整名字。

三条根因（读码 + 截图几何反推，写在计划 §2.3）：

- 列宽只在建左栏那一瞬算一次——`labelColumnWidth` 取 `paramScroll.viewport.width`，而 `showResult` 发生在 `SqlPreviewDialog` 构造期、`init()` 定尺之前；之后拖分隔条、拉窗口都不重算（面板里只有 meta 行挂了 `componentResized`）。用户截图里左栏约 490px 宽，标签仍按 ~90px 排版，这是「隐藏了」的直接原因。
- 上限硬编码 260px（约 35 个 ASCII 字符），MBG Example 这类嵌套名天生放不下，任何窗口尺寸都显示不全。
- 全名 tooltip 只在「构建时判定超宽」的分支里设，列宽事后一变就漏；且 Swing 纯文本 tooltip 不折行，单行超屏宽会被裁。

方案 A（用户从五方案里选定，A 为底座、不加换行/弹层/可拖列宽）：列宽预算抽成纯函数（视口 × 55%、硬上限 480px、下限 80px、未布局按 640 兜底视口）＋ `paramScroll` 尺寸监听防抖 120ms 原地重排（只改已有标签的文本/首选尺寸/tooltip，**不重建左栏**，避免丢焦点与已填值）＋ tooltip 无条件设全名、超长名 HTML 软折行（优先 `.` `_` `)` 后断）。复选框（wrapper/xml/ibatis 三类条件行）同口径；iBatis iterate 行改用本分区统一列宽（旧实现按本行单条文本自算）。

提交：`2096fa2f5`（代码+用例+CHANGELOG+计划）、`84f1020df`（回填哈希与实施记录）。**未 push。**

读数：隔离工作树 test 桶 12 类 **206/0/0**；反向两轮各恰 1 红（A 把宽度冻结 → `testParamLabelColumnFollowsViewportWidth`；B 把 tooltip 退回条件化 → `testParamRowLabelWidthAdaptsAndTooltipForLongNames`）。not run＝testShards 全量、verifyGate/verifyPlugin/buildPlugin、真机 GUI（五步验收在计划 §六）。

## 20261112 更正：这版没解决真机可见的截断

`2096fa2f5` 已随 β4 包到用户手上（用户日常 IDE `plugins/…/lib/…-beta.4.jar`），但真机截图里 `criteria.criteria` / `criterion.value` 仍被截成 `criteria.crit…` / `criterion.va…`。**这版只改了上限（260→视口×55%）与 tooltip，没把「行内 8px 缩进」算进预算**——最宽的标签恰好差那一截，被 **Swing 自己**摘掉尾巴（保留头部，不是我方中段省略）。真因、探针与修法见 [[reference-swing-jlabel-self-truncation-and-insets]]，最终由 `e9b140fb1`（T-LabelInset）修好，用户截图确认全名可见。⇒ 引用本文件时别把「已修」当成「真机已好」。

遗留：计划 §七 P1「悬停完全没 tooltip」仍未真机复现（源码在截断时确实设了全名 tooltip，2026.11.8/11.10/11.11/master 四版逐字相同，普通参数行还有单测钉着）——别把用户这句话当已知事实写进结论；共享索引 `docs/索引/开发计划.md` 未登记本任务行（别的车道占着该文件，处置见 [[feedback-commit-other-lane-dirty-files-judge-by-diff]]）。测试读数取自隔离工作树的原因见 [[pitfall-shared-worktree-cross-lane-test-pollution]]。
