---
name: project-bind-static-ref-cross-ns-20261116
description: T-BindStaticRefCrossNs（20261116 已实施+已验收，`ad1deacb0`，双仓已推）——bind
  引用「跨命名空间 include 片段」里的 @类@字段 求值为空，根因是静态常量注入表的扫描域只取当前文件文本
metadata:
  node_type: memory
  type: project
  originSessionId: sess_76f10071-704d-480c-be5c-0d8ff4f786f3
---

# T-BindStaticRefCrossNs：跨命名空间 include 片段里的 bind 静态常量认不出来

用户 2026-11-16 反馈「bind 引用的静态字段没有正确识别」，复现工程 `F:\code\Java\Gitee\other\cyxny\cyzc-platform`（IDEA 2026.3）。
计划册：`docs/开发计划/20261116-0854-T-BindStaticRefCrossNs-bind引用跨命名空间片段静态常量未识别-开发计划.md`。状态＝**已实施 + 用户 A1 复验正常**。

根因（逐行核实）：`ui/XmlPreviewService.kt:105` `resolveStaticFields(fileText, …)` 只扫**当前 mapper 文件文本**；而跨命名空间 include（`T-IC-02`，refid 含 `.`）的片段文本来自**另一个文件**，只在 `XmlIncludeExpander` 加载期拼进语句树 ⇒ 注入表缺键 ⇒ `xml/Ognl.kt:487` 求值 null ⇒ `xml/IfSqlNode.kt:76`（VarDeclSqlNode）报「静态常量 … 求值为空」。

差分证据（同语句一成一败即可定性，别按类型/模块猜）：`#statusEnd`（本文件 bind）回填 3，`#scopeCategoryBike`（`common/StationAuthScope.xml` 片段内 bind）为空。已排除：常量 `Integer` vs `int`、跨 Maven 模块、类字段名写错、`computeConstantExpression` 能力边界。

修法（提交 `ad1deacb0`，master）：S1＝`preview()` 把 `:99` 的 `crossNamespace.fragments.values` 并入扫描域（`resolveStaticFields` 收 `Collection<String>`，按 `类@字段` 跨文本去重；单文本重载留给 `AnnotationSqlPreviewService` 注解链）；S2＝`findFieldByName(name, false→true)`，与运行时 `Class.getField`（含继承/接口常量）对齐。S3 两条边界不做：`static {}` 块赋值、`Integer.valueOf()`/`new` 类非常量式（已写进 `docs/架构设计/02-核心流程详解.md` §3 已知边界）。

读数：改前 3 红（T1 `bindValues[scopeCategory]` 为 null、T2 无任何信号、T3 继承常量报无法解析）；改后定向 **74/0/0/0**（`XmlCrossNsFragmentResolverTest` 17 + `AnnotationPreviewServiceTest` 15 + `XmlPreviewServiceTest` 7 + `XmlIncludeExpanderTest` 16 + `OgnlEvaluatorTest` 19）；反向验证 S1 摘 `fragments.values` 恰 2 红、S2 退 `checkBases=false` 恰 1 红；CI `推送测试 · master @ 6c15d7f6` run `43785965787` **8/8 绿**（fast×2 + test 1~5 + nudge，约 8m44s）。落账提交：`6c15d7f67`（CHANGELOG/计划 §九/索引/架构文档/LESSONS L73）、`0efbc648c`（CI 读数）、`cd5b76f8c`（A1 复验 + 真机日志读数）。

真机验收：用户 2026-11-16 复验正常；运行包取证＝已装 jar `MPV-Build=1794796956873`（本地 10:42:36 构建）常量池含 `resolveStaticFields` 双重重载 ⇒ 跑的是带修复的包；对应 `idea.log` 预览轮 10:46:57 / 10:47:15，我方 ERROR 0 条。冷开 `request→dialog=3839ms`（entity resolve 227ms、schema scan 339 表、`parse+ctx=2337`）、18s 后暖开 construct 24 + show 82ms；同毫秒两条 `[open-perf] total` ＝在册项「一次打开装配两次」。日志噪声归属：`createEditor path`/`releaseEditor path` 是我们自己的诊断 Throwable 被平台节流；SEVERE `811.1ms` 落在热更新 `DynamicInstallSupport→DynamicPlugins.loadPlugin` 的 EDT 配置组通知；`SqlLogPanel` 构造期 `refreshSortAction→updateActionsImmediately` 触发 `toolbar manual update is ignored`——2026-11-16 用户问「推荐清吗」，答复推荐清（构造期刷新是平台判定的无操作，只是污染我方 0 WARN 读数），最小改法与真机验证点见 [[pitfall-toolbar-refresh-before-addnotify-warn]]；**未实施**，需等并行车道收口或有独立工作树再动。

**Why:** 这类「文本预扫描域 ⊂ 渲染域」的错配不报错、只静默降级成 null，warning 文案还指向「常量类/字段不可解析」，实际与常量本身无关；同语句本地/片段一成一败就是判据。
**How to apply:** 给任何「基于文件文本的预扫描」（静态常量、`${ew.*}` 占位、参数名收集）加跨文件能力时，同步问「这些文本还会从哪来」；排障先用面板参数行是否回填（`xml/XmlSqlRenderer.kt:81` → `ui/SqlPreviewPanelResultView.kt:235`）做成败差分，再动 Psi 层。**横向核实结论**：`ew` 占位扫的是展开后的 `element.textContent`（已覆盖片段）、参数类型按方法签名（不涉文本），所以当时只有静态常量这一处漏。
