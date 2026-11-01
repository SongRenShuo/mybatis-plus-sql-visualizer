---
name: project-settings-convergence
description: #18 设置收敛（S2-6 / NEW-14）——54 个 Boolean 的四类分档、四阶段实施顺序与两条红线（不删设置项、不改默认值）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_da4753b3-ef7f-4a1f-b5d2-a46a94883c80
---

# #18 设置收敛方案（2026-10-31 实测 54 个 Boolean）

**红线（用户明确）**：**不删任何设置项**（用户已有配置会静默丢失）；**不改任何默认值**（行为变化必须逐项单独评估，混在收敛里做等于埋雷）。

## 四类分档（实测 `PluginSettings.State` 里 54 个 `var xxx: Boolean`）

| 类 | 数量 | 成员 | 处置 |
|---|---|---|---|
| **A 状态位**（不是设置） | 4 | `sqlListPreviewHintSeen` / `enhancedCaptureConfirmed` / `onboardingAnchorTipShown` / `explainStructuredOfferedShown` | 标为内部状态、**设置页不呈现**（行为零变化）→ 54 变 50 |
| **B 成组开关** | 6 | `gutterGroupMapperXml` / `Annotation` / `JpaHql` / `Jdbc` / `Other` / `InlineFallback` | 合成一个「显示分组」多选，**存储不变**，纯视觉收敛 |
| **C 已被取代的旧字段** | 1 | `displayMaskEnabled`（已被三档 `displayMaskMode` 取代，仅作迁移源） | 设置页撤下，只留迁移读 + KDoc 标注 |
| **D 真独立开关** | 43 | 日志 8 个、注入/模拟 5 个、其余分散 | 归入场景组，**不删不减** |

## 四阶段（每阶段独立可回退）

1. **阶段 1（零风险）**：A 类剔除 + C 类隐藏 + 落地「场景分组常量」`SettingsScenarioGroups.kt`（`REVIEW` 审阅 / `TUNING` 调优 / `AI_COLLAB` AI 协作 / `CAPTURE_DIAG` 捕获诊断），**覆盖全部 54 字段**，只打标不改值不改 UI。
2. **阶段 2（小 UI）**：B 类 6 个 gutter 开关合成一个多选控件，写回仍逐个字段；形态照抄本仓既有同类多选。
3. **阶段 3（NEW-14）**：四场景预设一键切换（预设只批量写值，用户仍可逐项改）。
4. **配守卫**：`SettingsScenarioGroupCoverageGuardTest` 源码扫描 `var xxx: Boolean` 与分组清单对账，**出现未归组的 Boolean 即红**——否则收敛一次又会长回去。

## 实施注意

- `PluginSettingsPanel.kt` 受 `GiantFileBudgetTest` 预算约束（当时 1835 行 / 预算 1840）——新增控件若超线，**拆到同包新文件**，**不要提预算数值**（预算表自身注释写明「到了就提等于没有预算」）。
- 新增用户可见文案一律双语进 bundle（parity 守卫会查）；遵守「零模态弹窗」红线。
- 相关：[[project-dev-batch-20261031]]（同日批次）、R45 结构化通道默认开与 NEW-18 体检入口都动过设置页，收敛宜排在设置页稳定一轮之后。
