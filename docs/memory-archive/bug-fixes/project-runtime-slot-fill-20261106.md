---
name: project-runtime-slot-fill-20261106
description: T-RuntimeSlotFill（20261106 立项，券 A 实施中）——让用户在预览面板给运行期拼接的占位填值、SQL 随之变；含三块可复用基建、四类 slot 与身份内嵌注释的设计、A/B/C 串行拆分，以及「263 沙盒开关暂缓」的用户决定
metadata:
  node_type: memory
  type: project
  originSessionId: sess_33620d7f-a4ec-4f92-8fbe-dcd146afc43f
---

# T-RuntimeSlotFill：运行期拼接的占位可填值（2026-11-06 立项）

用户原话：「我觉得运行期 sql 还得优化，最好是能让用户自己填参数，然后 sql 动态变」，并指定复现夹具
`mybatis-plus-sql-visualizer-demo/mybatis-plus-demo/.../RuntimeConcatReproController.java`
（三个方法 `listByLoopTernary` / `listByTernaryBranch` / `listByOptionalIf`）。
计划册＝`docs/开发计划/20261106-2130-T-RuntimeSlotFill-运行期拼接参数可填值与SQL动态化-开发计划.md`（已登记索引）；
**仅本地提交 `17d94bb1`，未推**（按 [[feedback-ci-trigger-push-cadence-and-watcher]]：文档-only 不单独推）。

## 现状与缺口

β.2 之后运行期片段已收口成合法块注释 `/* 运行时值: … */`（见 [[pitfall-runtime-placeholder-must-be-one-comment]]），
括号与引号不再被吃，但**没有输入口**：占位只是注释文本，`ConcatenationResult` 只有 `(sql, complete)`，
没有 id/span，也不进 `unresolvedParams`，面板不会为它建行。

可复用三块基建（这是成本不高的原因）：
- `sql/ParamHint.kt`：`ParamHint(quote, likePosition, inList)` 三件语义已有；
- `sql/ParameterReplacer.kt`：`replaceNamedParams`（`'`→`''` + 数值启发式）、`buildExecutableSql`（`?` 顺序回填）；
- 面板参数区：`paramValues` 映射 + 按 `unresolvedParams` 建行 + `initialParamValues` 预填 + 导入/导出/差异/回放导出。

## 设计要点（已写进计划册，实施按此）

1. **身份内嵌在注释文本里**（`/* 运行时值: <expr> · rt<N> */`），不用 char range——`SqlFormatter` 自述不变式是
   「只动空白与行尾注释」，格式化后字符位置必失效而 token 文本不会。
2. **四类 slot**：`VALUE / LOOP / TERNARY / OPTIONAL`；LOOP 要「首项/其余两支」模板（从循环体字面量片段抽，不许编造），
   TERNARY 给两支文本，OPTIONAL 给片段模板；取不到就置 null 并标注「展开形态未知」。
3. **回填**：`replaceRuntimeSlots` 先于 `?`/`:name`，并按已替换区间记账（防用户值里的 `?`/`:name` 被二次当占位）。
4. **面板**：一行一 slot，kind 定控件（单值+类型 / 值列表 / 选支 / 包含开关）；**留空＝保留占位**，默认行为与 β.2 逐字一致。
5. **三处可执行链路必须都取替换后文本**（复制可执行 / 导出回放 / 直接执行），否则“看到替换了、复制出来还是注释”。

## 拆分与状态

- **券 A（地基，2026-11-06 已派子代理、后台实施中）**：`RuntimeSlot` 模型 + 拼接器产出结构化 slot + `PreviewResult.runtimeSlots`（默认空）+ 填充 + 单测；**不碰 UI、不碰 ParameterReplacer**。
  必须先证伪三条：P-1 格式化器会不会改注释内容（决定身份载体）、P-3 循环模板能否稳定抽出、P-4 三元两支能否分别拿到。
- **券 B**：回填引擎 + 三处链路串联（含与 `?`/`:name` 混用）。
- **券 C**：面板按 kind 建行与控件 + UI 测试 + 真机走查步骤。

验收直接拿 demo 三方法当夹具；三条反向验证（留空改成 NULL 必红 / 去掉模板展开必红 / 复制链路跳过替换必红）。

## 同轮的用户决定

- **263 沙盒开关（`-PmpvPlatform=263`）暂缓**：平台版本目前写死在 `build.gradle.kts:150`
  `intellijIdeaUltraimate(if (gifRecord) "2026.2.1" else "2024.3.7.1")`，没有 263 入口；要做需加开关 + 与发布任务互斥的配置期闸门。用户说先不做。
- 热替换的能力矩阵见 [[project-dynamic-unload-barriers]]（243 不可、253/262/263 可）。
- 四个已合流工作树（`concat-polish`/`matrix-timeout`/`ci-dialect-keys`/`dev-update`）与分支已全部清理，`.worktrees/` 现为空；长路径残留按 [[pitfall-worktree-remove-longpath]] 处理。
