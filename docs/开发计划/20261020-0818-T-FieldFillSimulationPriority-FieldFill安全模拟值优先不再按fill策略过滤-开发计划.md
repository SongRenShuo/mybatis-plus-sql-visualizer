# T-FieldFillSimulationPriority：FieldFill 安全模拟——模拟值优先不再按 fill 策略过滤

2026-10-20。来源：用户在 dev 分支真机验收 U20/FieldFill 时发现——UPDATE 预览中 `create_time [fill=INSERT]` 字段的模拟值 `111212` 不生效，SQL 用了灰框里的普通值 `'111'`。

## 一、现象与判定

- 面板对 fill 字段一律展示「模拟值」行，并在模拟值非空时把普通输入框置灰，tooltip 写明「已由模拟值覆盖；清空模拟值后可改回手填」。
- 但生成的 SQL 与提示相反：`create_time = '111'`（灰框值），模拟值被静默忽略。
- 判定：真缺陷（UI-031），且与 b6a88945「模拟值优先」的 UI 承诺自相矛盾。

## 二、根因（代码证据）

- `FieldFillSimulation.overlay` 内 `applies(kind, fill)` 闸门：`UPDATE` 只认 `fill=UPDATE/INSERT_UPDATE`，`UPDATE + fill=INSERT` 返回 false → 整段跳过。
- b6a88945 只改了面板侧（把被模拟覆盖的普通参数从 explicitValues 中预剔），模型闸门未同步；面板又对所有 fill 字段提供模拟行，于是「可编辑的模拟值被忽略、置灰的普通值生效」。

## 三、待验证前提（已核实）

1. 面板确实为 `[fill=INSERT]` 字段提供模拟行（`buildBatchColumnRow` 的 `hasFill && isToggle`）——成立。
2. `BatchModel.orderedColumns` 在 UPDATE 下先 toggle 后 fixed，模拟值索引映射正确——成立。
3. UPSERT 仍需排除（运行期分支不可知，UI 文案已披露）——保留。
4. 既有契约用例断言的是旧矩阵语义（按 fill 过滤）——确认需按用户口径更新。

## 四、方案与改动

- 模型层：INSERT/UPDATE 下模拟值非空即覆盖，不再按 fill 策略过滤；UPSERT 提前返回空 Overlay。去掉 `applies` 函数、`explicitValues` 参数与 explicit 优先守卫（优先级由模型单点裁决）。
- 面板层：去掉 `fieldFillOverlay()` 里的 explicitValues 预剔逻辑（模型已裁决）。
- 测试：`FieldFillSimulationTest` 增「UPDATE + fill=INSERT 模拟值生效」「UPSERT 仍排除」；`FieldFillSimulationPanelTest` 增面板级复现用例，并把既有 fill 矩阵用例改为「两种语句下用户手填值均生效」。

## 五、验证读数

- 定向：FieldFill 模型 + 面板 + CRUD 生成器 **47/0/0**（独立沙盒，`--no-configuration-cache`）。
- 反向：临时恢复旧 fill 闸门 → **恰 3 红**（模型回归、面板回归、契约用例），恢复后全绿、`REVERSE-PROBE` 残留 0。
- 提交：`1e20bb2b`。

## 六、遗留与注意

- 语义变化需知会：INSERT 预览里手填 `fill=UPDATE` 字段、UPDATE 预览里手填 `fill=INSERT` 字段的模拟值现在都会生效（模拟行是用户显式 what-if，面板文案已说明结果可能与实际运行不同）。
- 真机复验待用户（需重建插件包并重启 IDE）。
- 验收台账登记：UI-031。
