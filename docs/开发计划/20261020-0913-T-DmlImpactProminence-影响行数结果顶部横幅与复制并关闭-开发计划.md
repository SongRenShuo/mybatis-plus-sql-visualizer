# T-DmlImpactProminence：影响行数结果顶部横幅、复制并关闭与返回预览主行动化

2026-10-20。来源：用户在 dev 分支真机验收 R42/预估影响行数时提出四项：结果只在左下角不够明显；右下角气泡内容不对；底部缺「复制 SQL 并关闭」；执行视图「返回预览」不够显眼。

## 一、现象与判定

- 预估影响行数结果只在左侧参数区底部以灰字显示「预估影响行数：1」，与右侧执行结果区相距很远。
- 每次点击都会弹一条「影响面预演（只读 COUNT 试算）· 试算语句/目标表」通知——讲的是探针而非结果，与内联结果并存时容易误读（UI-034）。
- 预览弹窗底部只有「复制 SQL」，没有复制后关闭的出口。
- 执行视图头部的「返回预览」是普通小按钮。

## 二、根因（代码证据）

- `showDmlImpactResult` 把 `JBLabel` 追加到 `paramPanel`（左栏底部），灰色说明色。
- `estimateDmlImpact` 在试算前调用 `notifyDmlImpact(title=dml.impact.probe.title, body=probeBody)`，内容为探针说明而非结果。
- `SqlPreviewDialog.createActions()` 只有执行/复制/取消；`executeViewHeader()` 用 `smallButton`。

## 三、待验证前提（已核实）

1. 复用降级横幅承载结果会被 DEGRADED 常驻态压住（优先级更高）——实测成立，故改为**独立横幅行**。
2. 结果横幅与降级横幅可并存（上下各一行，不抢位）——成立。
3. 结果详情（探针 SQL/目标表/上限）可放进 tooltip，不丢失透明度——成立。
4. 复制并关闭动作可复用 `panel.copySql()` 后 `doDispose()`——成立。

## 四、方案与改动

- 面板新增 `dmlImpactBanner` 独立横幅行（蓝色加粗；失败红色），挂在降级横幅之下；`showDmlImpactResult` 同时更新内联标签与横幅，`clear()` 复位。
- 删除试算前的探针预演通知；探针详情保留在结果横幅 tooltip。
- `SqlPreviewDialog` 底部新增蓝色「复制 SQL 并关闭」（`createJButtonForAction` 应用主行动样式）；执行视图「返回预览」改主行动样式（`applyPrimaryButtonStyle`/`primaryButton`）。
- 测试：`DmlImpactSideQueryPanelTest` 增结果横幅/失败横幅断言；`SqlPreviewDialogTest` 增复制并关闭用例。

## 五、验证读数

- 定向：影响面预演 + 预览弹窗 + 降级横幅 + 预览面板 **49/0/0**（独立沙盒）。
- 反向：摘结果横幅 → 2 红；摘复制后关闭 → 1 红（合计**恰 3 红**），恢复后全绿、探针零残留。
- 提交：`d65fb457`。

## 六、遗留与注意

- 若用户更希望看到「原始控制台 + 原生结果网格」形态，可在此基础上追加「点击横幅在弹框展示原始控制台/结果」；当前方案保留了只读旁路、不替换预览编辑器。
- 真机复验待用户。
- 验收台账登记：UI-034。
