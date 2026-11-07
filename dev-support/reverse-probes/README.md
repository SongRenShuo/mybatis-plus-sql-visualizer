# 反证补丁库（reverse-probes）

`reverse_probe.py` 手术式反证模式（`--patch`）所用的机制禁用补丁，一补丁一机制，命名 `<机制标识>.patch`。

## 口径（先读 reverse_probe.py 头注释）

- **整提交反打**（`--commit`）只适用于「改既有行为 + 既有测试」的提交；**新增能力型提交**（新机制与测试缝同批落地）整提交摘除必然 `compileTestKotlin FAILED`（脚本会如实报「不可判」exit 3）——此时用本目录的手术式补丁。
- 手术式补丁 = 正向 diff，只改机制判定点（如 `if (cond)` → `if (false && cond)`），**测试缝/注入点一律保留**；每行改动带 `REVERSE-PROBE` 注释说明反证锚。
- 判定：锚定测试出现红例 = 反证通过（exit 0）；全绿 = 反证失败（锚定用例没咬住机制，exit 1）。

## 补丁清单

| 补丁 | 机制 | 锚定测试 | 校验记录 |
|---|---|---|---|
| `7be82118-disable-flush-window.patch` | notifyUi 150ms 刷新时间窗（券 A） | `SqlLogManagerFlushWindowTest` | 2026-11-07：7 例 5 红 2 绿，与该券人工反证分布逐位一致 |
