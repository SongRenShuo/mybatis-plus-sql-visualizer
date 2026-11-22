---
name: project-mcp-global-break-hardening-20261118
description: T-McpGlobalBreakHardening 全收官（20261118~19 已实施+真机三线验证+随 2026.11.19-beta.1 发行）——MCP 结构性免疫批：契约白名单/未知方法熔断/总开关联动/Registry 逃生开关/门控状态行；真机走查顺带修出两个真缺陷（263 工具集三钩子致只剩 1 工具、MCP 子页 apply 不广播致热同步从未触发）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_c2c2736b-1885-49ae-88c7-df7f389dfdec
---

T-McpGlobalBreakHardening（计划册 `docs/开发计划/20261118-1749-T-McpGlobalBreakHardening-…`，§十二 实施记录）**已全部实施+真机验证+随 v2026.11.19-beta.1 发行**。缘起＝用户报障「插件导致 IDEA 所有 MCP 无法使用」（=20261029 T-Mcp261FilterContract 同一事，报障用户插件版本 2026.10.28；修复 `7e647174` 自 2026.10.29 起全 tag 携带）。

**四笔生产提交**：
- `db2eaaaef` 券01/05：契约**白名单**（接口上出现任何白名单外方法＝UNKNOWN 不注册，防「applyFilters 还在、平台又加方法」的漂移复发）+ suspend 判据（实为冗余——suspend 必然被参数个数/返回类型覆盖，反向 0 红，如实登记）+ **未知方法熔断**（tripBreaker：按返回类型给安全值后立刻 unregisterExtension 摘除，本次运行期不重挂）；262/263 字节级解析器绕开「major 69 载不进 JBR21」限制扩真 jar 探测
- `d00b49801` 券03/05：总开关关闭两注册面都不挂（toolset unregisterAll + 过滤 provider 不注册）、Registry 逃生开关 `sqlvis.mcp.gateFilterEp`、设置页门控状态行（已生效/未识别/已关闭/无此能力/已熔断）、注册日志带 form+build
- `c75203c7b` **263 真缺陷**：`McpToolset` 新增 isUserConfigurable/displayName/displayDescription 三钩子（都有接口默认体），动态壳基类不补齐 ⇒ 263 真机 tools/list 92 工具只剩 1 个 sqlv（注册清单第一个，**idea.log 无任何异常**）；基类补齐后 102/11。守卫=McpToolsetShellFactoryTest 钉基类钩子面
- `e08f7430e` **261 真缺陷**：MCP 子页 `McpConfigurable.apply()` 不广播 SETTINGS_CHANGE_TOPIC（主面板有、子页漏）⇒ 热同步订阅从未触发——总开关变更要重启才生效，Apply 后工具消失只是平台 isEnabled 过滤的副作用；修复后 261 GUI 双向真机复验：勾上→热注册 65/11、取消→热注销 50/0，均无重启。守卫=McpConfigurableGateTest 钉「apply 必须广播」

**验证读数**：261.25134.95（报障版本）/262.9437.185/263.7307.200 真机 `/stream` 全 200 + 11 个 `sqlv_*` 在列 + 零 addAll NPE；总开关关/逃生开关关两态复验 200；`verifyMarketplace` 三目标 Compatible（17m33s）；定向 mcp 包 46 suites/337 例 0 红，反向五轮各恰 1 红（白名单/熔断通知/总开关守卫〔首轮守卫 0 红——只查文件内任意位置，收窄到 substringAfter 过滤注册面后 1 红〕/263 三钩子/子页广播）。

**方法论结论**：
1. 真机 E2E 判据＝tools/list 里 `sqlv_*` 全在 + idea.log `O-68: 注册完成（form=…, build=…）`——`/stream` 200 本身不算数（263 首跑加载的是用户真实安装的同版本号插件，照样 200）；`Loaded custom plugins:` 必须只有 SQL Visualizer 一条。详版=[[reference-261-e2e-local-recipe]]
2. 「平台给接口加钩子」的症状同形于 261 事故但**无异常日志**（静默丢工具）——过滤 EP 已有白名单防护，toolset EP 仍是「类可达即注册」，下次 toolset 接口再长钩子症状不变（守卫只钉钩子面在基类）
3. 主/子设置页广播不对称：子页 Configurable 自己写状态就必须自己广播，别假设主面板的广播覆盖
4. 报障版本对照：先 `git tag --contains <修复提交>` 确认用户版本区间，旧版用户升级即修

相关：[[project-pending-plans]]、[[pitfall-sqlv-render-logs-missing-ibatis-stmt-form]]（仍开的 MCP 缺口）
