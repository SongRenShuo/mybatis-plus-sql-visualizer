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

## 六、实施记录（2026-11-22 落地回填）

### P1~P4 证伪结论

| # | 结论 | 证据 |
|---|---|---|
| P1 | **部分证伪（已按计划意图改判落地）**：`dollarRawCount` 字段**不在** `PreviewResult` 上，而在 `IbatisRenderResult`（`IbatisModel.kt:309`，iBatis 渲染层，`SqlMapSqlRenderer.kt:152` / `IbatisXmlPreviewService.kt:213`/`:414` 在用）；`PreviewResult` 只有 Boolean `hasDollarRawSegments`（`PreviewResult.kt:87`）。计划册「字段已存在」不成立，按「字段应当存在」的意图**新增** `PreviewResult.dollarRawCount: Int = 0`（默认 0 向后兼容，全仓构造点具名零错位） | grep `dollarRawCount` 全部定义与写入点 |
| P2 | **成立**：DegradationBanner 优先级 DEGRADED(2) > WRITE_RISK/RENDER_WARNING(1) > DOLLAR_INJECT(0)，show 低优先让位、clear 只清本类别；纯 SQL 链 `currentWarning=null`、`xmlModel=null`（无 WRITE_RISK/RENDER_WARNING 竞争），挂 DOLLAR_INJECT 无冲突，无需新 Kind/新文案键（`panel.banner.dollarInject` 带 `{1}` 计数参数，全仓仅 `SqlPreviewPanelResultView` 一处消费，无第三处拼接） | 读 DegradationBanner.kt:38-46/:126-155 + grep `dollarInject`/`xmlDollarSubstitutions` 全消费点 |
| P3 | **成立（headless 实证）**：`confirmDollarRawSegments`（`SqlPreviewPanelExecution.kt:631`）读 `current?.hasDollarRawSegments`，裸 SQL 链已填 true——测试 `test执行_裸SQL含直替弹确认且NO中断` 断言确认弹窗正文逐字等于 `execute.confirm.dollarRaw.message` 且 NO 中止在空态。附带发现 :624 KDoc「只在 XML/注解预览链生效」是过时注释（实现读任何链），未动 | M2 headless 测试 |
| P4 | **成立**：`StandaloneSqlSourceResolver.Kind` 只两值（XML_STATEMENT/PLAIN_SQL），消费点仅独立工作台（`SqlStandalonePreviewPanel.kt:632`）与弹窗（`SqlPreviewDialog` 同链）；主面板 preview 源（gutter/annotation → Wrapper/XML/iBatis 链）无 PLAIN_SQL flavor。横幅拓宽惠及独立工作台 + 弹窗，无主面板回归面 | grep `Kind.PLAIN_SQL` 全消费点 |

### 范围改判（P1 证伪的处理依据）

1. **新增字段而非复用**：`PreviewResult` 加 `dollarRawCount: Int = 0`（`PreviewResult.kt:88-96`），仅裸 SQL 链（`StandaloneSqlSourceResolver.resolveAsPlainSql`）填值；iBatis 链不填（恒 0，可见性走 `$x$` 风险行既有渠道）——横幅行为面精确限定裸 SQL，避免范围外行为变化。
2. **`hasDollarRawSegments` 改由计数派生**（计划册允许的二选一）：原 Boolean 独立扫描**不识别转义**（`\${` 照样命中返回 true，与 XML 链 TokenParser 口径存在偏差），计数派生后转义形态两处口径天然一致。行为变化面＝含 `\${` 的裸 SQL 从「执行弹确认」变「不弹」——与 XML 链同口径（`InjectionGateTest.test信号_纯井号与转义形态为假`），且消除「横幅不显示但闸门弹」的分裂态。
3. **横幅计数按形态互斥取值而非「或」拼接**：实施中发现 `xmlDollarSubstitutions` 是**面板渲染态**（`SqlPreviewPanel.kt:792`，仅 `xmlModel != null` 轮复位，`SqlPreviewPanelResultView.kt:229`），纯 SQL 结果读它会残留上一轮 XML 计数造成误挂——改为 `val dollarBannerCount = if (result.xmlModel != null) xmlDollarSubstitutions else result.dollarRawCount`，并加用例 `test横幅_纯SQL轮不读XML渲染态残留` 钉死。

### 改动文件:行

- `src/main/kotlin/.../model/PreviewResult.kt`：88-96 新增 `dollarRawCount` 字段（KDoc 写明口径分链）
- `src/main/kotlin/.../ui/standalone/StandaloneSqlSourceResolver.kt`：`resolveAsPlainSql`（计数赋值+产物双填，原 :376/:392/:403 一带）；`hasDollarRawSegment(Boolean)` 改造为 `countDollarRawSegments(Int)`（string-aware + 转义识别，原 :457-488）
- `src/main/kotlin/.../ui/SqlPreviewPanelResultView.kt`：`updateMeta` 横幅条件拓宽（原 :934-945 一带，现 :934-951）
- `src/test/kotlin/.../ui/DollarPlainSqlBannerTest.kt`：新增 296 行（14 用例）
- `CHANGELOG.md`：`[Unreleased]` 新增 `### Added` 条目

### 测试读数（定向，未跑全量）

```
gradlew.bat test --tests "cn.song.mybatisplus.visualizer.ui.DollarPlainSqlBannerTest"
  --tests "cn.song.mybatisplus.visualizer.ui.DollarInjectBannerTest"
  --tests "cn.song.mybatisplus.visualizer.ui.InjectionGateTest"

DollarPlainSqlBannerTest  => tests=14 failures=0 errors=0
DollarInjectBannerTest    => tests=6  failures=0 errors=0   （XML 形态回归）
InjectionGateTest         => tests=18 failures=0 errors=0   （闸门/复制链回归）
BUILD SUCCESSFUL
```

过程记录：首轮编译失败 4 处（测试侧笔误：`updateMeta` 漏 receiver、消息文本里 `${b}`/`${}` 被当 Kotlin 模板、函数名含 `✕` 非法标识符字符）——均为新测试文件自身问题，修正后第二轮撞上 Kotlin 增量编译未重编既有测试类（`PreviewResult` 构造器 NoSuchMethodError ×10，产物错配伪故障），删 `build/classes/kotlin/test` 强制重编后全绿。

### 反向验证（守卫纪律，红/绿两读数）

| 步骤 | 操作 | 读数 |
|---|---|---|
| 红 | 临时短路 M1 拓宽分支：`val dollarBannerCount = if (result.xmlModel != null) xmlDollarSubstitutions else 0 // TEMP-PROBE` → 只跑 `DollarPlainSqlBannerTest` | `14 tests completed, 1 failed`——失败用例 `test横幅_裸SQL直替按计数显示`：`AssertionFailedError: 两处直替 → DOLLAR_INJECT 挂起 expected:<DOLLAR_INJECT> but was:<null>`（恰好命中被测分支，其余 13 例不受影响，无假红扩散） |
| 绿 | 恢复短路（`else result.dollarRawCount`）→ 重跑定向三件套 | `DollarPlainSqlBannerTest 14/0 + DollarInjectBannerTest 6/0 + InjectionGateTest 18/0`，`BUILD SUCCESSFUL` |

附带实证：红读数 `but was:<null>` 同时证明 headless 下 DEGRADED 降级横幅在该形态**未**挂起——横幅断言走的是真实装配链，未依赖降级规避分支。

### 提交列表

| 里程碑 | hash | 内容 |
|---|---|---|
| M1 | `15a166f43` | 计数变体 + 横幅条件拓宽（3 文件，+41/−12） |
| M2 | `8ceced35b` | headless 测试 14 用例 + 反向验证红/绿在案（+296） |
| M3 | 本提交 | CHANGELOG `[Unreleased]` Added + 本实施记录回填 |
