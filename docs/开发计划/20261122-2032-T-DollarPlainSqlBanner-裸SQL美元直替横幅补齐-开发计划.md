# 裸 SQL 美元直替横幅补齐（T-DollarPlainSqlBanner）-开发计划

- **日期**：2026-11-22 20:32
- **编号声明**：本册新占 **D252**（1748 册 :116「下一全新 D252/V245/O-163」口径一致，grep 全 docs 零占用）；下一全新 **D253**。
- **上游依据**：D222 定谳销账（2026-11-22，0631 册 D222 备注）——`${}` 注入风险标记经「主面板 9-15 横幅 → 9-18 闸门 → 独立工作台 11-14 内嵌继承 → 11-21 执行环 D233」已全覆盖 XML/iBatis 预览链，唯一残余即本册要补的裸 SQL 形态。
- **范围**：仅插件仓；体量小（一处计数 + 一处条件拓宽 + 测试）。编译零错误 + headless 测试绿为硬门禁。

## 一、现状核查（2026-11-22 源码级实证，非推断）

| 事实 | 证据 |
|---|---|
| 裸 SQL 解析链**已计算** `${}` raw 标记（string-aware：字符串字面量里的 `${}` 是字面量不是占位，与 `?` 扫描同口径） | `StandaloneSqlSourceResolver.kt:388` `dollarRaw = hasDollarRawSegment(input)`，`:398` `hasDollarRawSegments = dollarRaw` 填入 PreviewResult |
| 执行闸门已覆盖裸 SQL（读口统一） | `SqlPreviewPanel.kt:1356` `hasDollarRawSegments()` 读 `current?.hasDollarRawSegments`；独立工作台经 `ensurePreviewPanel().showResult(result)` 灌入（SqlStandalonePreviewPanel.kt:550） |
| 复制可执行 SQL 的产物标记注释已覆盖裸 SQL | T-InjectionGate 既有口径：复制注释追加读同一读口（SqlPreviewPanel.kt:1356 KDoc） |
| **横幅未覆盖裸 SQL**（本册唯一缺口） | `SqlPreviewPanelResultView.kt:937-945` updateMeta 横幅条件＝`result.xmlModel != null && xmlDollarSubstitutions > 0 && !dollarMarkDismissedSession`——纯 SQL 形态 `xmlModel == null`，即使 `hasDollarRawSegments == true` 也不显示 |

口径差异警示（勿统一错）：XML/iBatis 引擎链是「字符串字面量内同样计数」（MyBatis 渲染后字符串内同样直替，`IbatisXmlPreviewService.kt:409-414`）；裸 SQL 链是「字符串内为字面量不标记」（`hasDollarRawSegment` string-aware）。两链各自忠实各自引擎，**本册不跨链统一**，裸 SQL 侧维持 string-aware 口径。

## 二、实施内容

### M1 裸 SQL 计数与横幅条件拓宽（1 次提交）
1. `StandaloneSqlSourceResolver`：`hasDollarRawSegment(input)`（Boolean）旁加一个计数变体（非转义 `${` 出现次数，string-aware 同口径），`resolveAsPlainSql` 把 `PreviewResult.dollarRawCount` 一并填上（字段已存在，iBatis 链在用）。
2. `SqlPreviewPanelResultView.updateMeta`（:937-945）：横幅条件拓宽为「XML 路径（xmlModel 计数）**或** 非 XML 路径（`result.dollarRawCount > 0`）」，文案复用既有 `panel.banner.dollarInject` 键（计数参数传对应来源的计数）；优先级、让位、✕ 会话记忆行为不动（DegradationBanner 既有机制原样复用）。
3. 面板侧若另有 `${}` 计数读口与横幅文案拼接处，同步核对（以 grep `xmlDollarSubstitutions` 全消费点为准，不漏不改多余）。

### M2 测试（随 M1 同批或紧随提交）
1. headless 面板测试（对齐 `DollarInjectBannerTest` 既有形态）：裸 SQL 粘贴含 `${id}` → 横幅出现、文案含计数；执行闸门弹确认（TestDialogManager 拦截断言，复用 T-InjectionGate 测试手法）；复制可执行 SQL 产物含 `-- contains ${} raw segments` 注释。
2. 边界用例：`\${` 转义不计；字符串字面量内 `${}` 不标记（裸 SQL string-aware 口径，与 XML 链刻意不同，用例注释写明口径出处）；`#{}`/`?` 不触发；XML 形态行为回归不变（既有 DollarInjectBannerTest 全绿即证）。
3. 反向验证：临时注释 M1 条件拓宽分支 → 裸 SQL 用例必须变红（贴两次读数）；恢复后回绿。

### M3 收尾（1 次提交）
1. CHANGELOG `[Unreleased]` Added 补一条（裸 SQL 形态 `${}` 风险横幅，提及独立工作台/弹窗同一面板继承），描述区不动。
2. 计划册回填实施记录；`mvnd`（插件仓 gradle 门禁按仓惯例跑定向测试即可，不跑全量）。

## 三、待验证前提（实施者先证伪再动手）

| # | 前提 | 证伪方法 |
|---|---|---|
| P1 | `PreviewResult.dollarRawCount` 字段存在且 iBatis 链在用、纯 SQL 链未填 | grep `dollarRawCount` 定义与全部写入点 |
| P2 | 横幅只缺条件拓宽、无需新 Kind/新文案键 | 读 DegradationBanner 三级优先与让位逻辑，确认 DOLLAR_INJECT 挂非 XML 结果不与 WRITE_RISK/DEGRADED 冲突 |
| P3 | 闸门对裸 SQL 实际生效（不只在读口存在） | headless 起面板灌纯 SQL 含 `${}`，执行断言确认弹窗出现（若证伪＝闸门另有形态门，范围回扩） |
| P4 | 主面板无裸 SQL 粘贴入口（横幅拓宽只惠及独立工作台+弹窗，无主面板回归面） | grep 主面板的 preview 源枚举确认无 plain-SQL flavor |

## 四、验收标准

1. 定向测试全绿 + 反向验证红/绿两读数在案。
2. 裸 SQL 含 `${}` 粘贴进独立工作台：横幅出现（信息级可 ✕）、执行弹 `${}` 确认门、复制产物带标记注释——三处与 XML 形态行为对齐。
3. 转义/字符串字面量边界不误报；XML 形态既有测试零回归。
4. CHANGELOG [Unreleased] 条目与计划实施记录齐。

## 五、边界

- 不改 XML/iBatis 引擎链的「字符串内同样计数」口径；不改闸门/复制注释既有行为（已覆盖裸 SQL，本册只补可见性）。
- 不动 update/、发行节奏；随下一发行批亮相（可与 D222 销账说明同批）。
