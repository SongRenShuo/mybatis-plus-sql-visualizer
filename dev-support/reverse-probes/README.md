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
| `t-ibatis-slowfix-disable-cached-only-probe.patch` | 同步预览实体探针的「零扫描」判定（券 0 T-Ibatis2SlowFix：`EntityAliasSweep.resolveCachedOnly` 直查未命中时不再启动 `@Entity(name)` 别名全扫） | `XmlInlineSqlPreviewServiceTest` + `PagesXmlHqlPreviewTest` + `EntityAliasSweepTest` | 2026-11-09：`reverse_probe.py --patch --base t-ibatis-slowfix` → exit 0「反证通过」，25 例 3 红 22 绿。3 红＝`test物理表名根_同步预览不触发别名全扫`(0→1)、`testUnresolvableEntityClassAndTableRoot_noAliasSweep`(0→2，第 2/4 步各一次)、`testCachedOnlyResolveNeverScans`；同类的 `testCachedOnlyResolveKeepsDirectHitWithoutScan` 仍绿（补丁插在直查命中 return 之后 ⇒ 红来自「扫描被恢复」而非钝刀）。撤销补丁复跑同三例 25/0/0 |
| `ibatis-recognizer-disable-doctype-signal.patch` | iBatis 识别器三选二的 DOCTYPE 信号（T-Ibatis2Support 券 A 第 1 条） | `IbatisXmlRecognizerTest,IbatisXmlRecognizerPsiTest` | 2026-11-09：19 例 8 红 0 错（纯逻辑 6 + PSI 取证 2），exit 0 |
| `ibatis-sqlsource-disable-ibatis-production.patch` | `SqlSource.IBATIS` 产出点（T-Ibatis2Support 券 H：`{pstm-N}` 两个闭合点同时退回 MYBATIS） | `IbatisSqlSourceTest,EnumReachabilityGuardTest` | 2026-11-09：11 例 3 红 0 错（来源断言 2 + 枚举可达性守卫 1），exit 0 |
| `ibatis-dtd-disable-standard-resource.patch` | iBATIS DTD 离线映射登记（T-Ibatis2Support 券 E：`plugin.xml` 的 `standardResource` 段挂到不存在的 EP 命名空间 `com.intellij.reverseProbe`，等价于平台一条都没登记，声明文本原样在场） | `IbatisDtdOfflineMappingTest` | 2026-11-09：`reverse_probe.py --patch --base t-ibatis-dtd` → exit 0「反证通过」，3 例 1 红 2 绿。红例＝`testExternalResourceManager解析到随包DTD`（断言消息：`http://ibatis.apache.org/dtd/sql-map-2.dtd 未被离线映射——解析结果仍是原 url 本身`）；`test随包DTD与jar内副本逐字节一致` 与 `testpluginXml逐一登记解析器认的八个标识` 仍绿（证明这两条只咬资产/声明文本，咬不住「登记是否生效」）。同一补丁在本工作树现场跑读数相同（3/1/0），撤销补丁复跑 3/0/0 |
