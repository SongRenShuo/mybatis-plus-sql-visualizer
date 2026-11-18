# T-RouteTsaf10Align：route 分支栈摘要转义分叉对齐 — 开发计划

- 立项：2026-11-18 10:38
- 任务源：用户 2026-11-18「现在立项 做掉」——针对第四十轮审计发现的问题：`t-branch-merge-route` 与 `t-security-audit-fixes` 在 T-SAF-10（预览 meta 行调用栈摘要转义）上语义分叉，直接合 route 会把用户可见缺陷带进 master。
- 性质：缺陷修复 + 合流前置。仅动 route 分支，不碰 master。

## 一、现状取证（三态实测）

判据文件：`src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelText.kt` 的 `buildStackDigestInlineLine` 返回语句。

| 分支 | 返回 | 注入面 | 纯文本态显示 | 测试口径 |
|---|---|---|---|---|
| master | `summary`（不转义） | 有 | 正常 | 无 T-SAF 加固 |
| t-security-audit-fixes | `summary`，转义挪到 `<html>` 包装边界（`8a67887ae`） | 封住 | 正常（用户见 `<-`） | 原始预算断言 + HTML 态恶意帧回归 |
| t-branch-merge-route | `HtmlEscape.escape(summary)` 在组装点 | 封住 | **显示字面 `&lt;-`** | `45d5a9aa6` 把 5 例改为迁就组装点转义 |

关键事实：

- meta 行是 **plain / `<html>` 双形态**：`applyMetaTruncation` 只在 `metaPerfSegments` 非空时包 `<html>`；`fullDisplayText` 同理。纯文本态不解码 HTML 实体。
- 栈摘要分隔符恒含 `<-`，因此组装点转义在常态（无 perf 警告）下直接把 `<-` 变成 `&lt;-` 呈现给用户。
- security 侧 `8a67887ae` 的根因判据是 CI run 43925934655 `test(2)` 的 5 例 ComparisonFailure，判为**真缺陷**，不是断言漂移。
- route 侧 `45d5a9aa6` 未改实现，改的是 5 例断言期望（并顺带把方言数声明 15→25，**这一半要保留**）。
- 两支测试文件的全部差异只与转义口径有关（`git diff t-branch-merge-route t-security-audit-fixes -- <测试文件>` 逐行核过），取 security 版本不丢 route 内容。
- route 已有 `metaLineHead` / `metaLineDigest` / `metaLineWithinBudget` 三件（P5 结构），security 的修法可原样落位。

## 二、方案

在 `t-branch-merge-route` 工作树：

1. `git cherry-pick 8a67887ae`（T-SAF-10 转义位置修正）。
2. 冲突处置：
   - `SqlPreviewPanelText.kt` / `SqlPreviewPanelResultView.kt`：取 security 侧（转义回归纯文本组装、在 `<html>` 包装边界转义）。
   - `SqlPreviewPanelStackDigestTest.kt`：取 security 侧（含新增 HTML 态恶意栈帧回归用例，且原始预算断言恢复）。
   - 计划文档片段：按并集补。
3. **不回退** `45d5a9aa6` 的 plugin.xml 部分（方言数 15→25 必须保留，route 现为 25）。

## 三、验收

| 项 | 判据 |
|---|---|
| 定向 | `SqlPreviewPanelStackDigestTest` + `StackDigestFramesTest` + `MetaHintTooltip` / `TopRowLayout` / `SqlResultPanelUi` / `RollbackSqlDialog`（security 侧同口径 91 例），全绿 |
| 反向验证 | 临时只回退 `SqlPreviewPanelResultView.kt` 的边界转义 → 新增 HTML 态用例必须变红（证明该用例真看守转义位置），验完立即还原 |
| 编译 | `compileKotlin + compileTestKotlin` 成功 |
| 守卫 | `PluginXmlStructureGuardTest`（方言数声明仍在 25） |
| CI | 推 route 后 `build-plugin.yml mode=gate` 一轮 success |

## 四、风险与回退

- 风险 1：cherry-pick 带入 security 侧其他上下文改动（该提交只动 2 个源文件 + 1 测试 + 1 计划文档，面已核）。
- 风险 2：纯文本态 / HTML 态两种渲染路径各有断言，若只改一处会半边红——定向用例已覆盖两态。
- 回退：`git reset --hard <cherry-pick 前 sha>`；route 未合 master，回退零外溢。

## 五、实施记录

（待回填）
