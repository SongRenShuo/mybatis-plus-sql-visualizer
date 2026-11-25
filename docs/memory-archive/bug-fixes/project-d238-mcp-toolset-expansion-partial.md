---
name: project-d238-mcp-toolset-expansion-partial
description: D238/D239（20261119 已收口 756011136）——MCP tools/list 只列前 1~3 个：四层缺陷叠加（枚举 type_name / 原始数组 / metadata 成员缺占位 / 平台清单缓存复用 previous）；真机 165/11 稳定，含诊断口径与两个读数坑
metadata:
  node_type: memory
  type: project
  originSessionId: sess_a383805c-722d-4353-8ff2-a7e7a0f56f84
---

D238（现象）＋ D239（修法）已在 2026-11-19 真机收口，修复提交 `756011136`，文档 `1ad55b2c8`。
**未推远端**（等用户点名）。

## 四层根因（逐层真机读数才见底，缺一层都还是坏的）

1. **枚举注解 `type_name` 写错**：`javaClass.enclosingClass ?: superclass` 对无类体常量退化成
   `java.lang.Enum`，JDK `AnnotationParser.parseEnumValue` 要求与声明类型逐字相等 →
   `AnnotationTypeMismatchException (Found data of type java.lang.Enum.TRUE)` → toolset 加载失败。
2. **原始类型数组漏编码**：Kotlin `IntArray` 不是 `Array<*>`，掉进 `else → toString()` 写成 `"[I@…"`，
   `kotlin.Metadata.mv()` 读出 String → 同一异常。
3. **metadata 成员缺占位**：复制的 metadata 描述业务类全部成员（含私有/内部辅助函数），壳类只声明
   工具方法 → `KotlinReflectionInternalError: Could not compute caller for function` → 该 toolset 归零。
4. **平台清单缓存复用 previous**：`McpToolsListProvider.requestUpdate` 走
   `computeAllMcpTools(previous)`，只在 previous 缺失时才真算 ⇒ 注册初期「只含前 1 个」的结果被永久沿用。
   必须先经合成访问器 `access$invalidateReflectionProviders` 摘缓存再 `requestUpdate`（延迟重算 3 次也救不回来）。

## 真机判据（已过）

冷启动 165 个工具 / 11 个 `sqlv_*`（连续三轮一致）→ 关总开关 154 / 0 → 再开 165 / 11；
`tools/call sqlv_check_sql` 返回 verdict。IDEA 2026.3 在 `127.0.0.1:64342`，
`tools/call` 必须带 `projectPath`。

## 多版本（20261119 核对，各版发行版 jar 字节码 + 运行期实测）

| 版本 | kotlin-reflect | CallableBridge | 清单缓存 |
|---|---|---|---|
| 243 | 2.0.21 | 无（旧 mcpServer，插件整体跳过） | — |
| 253 | 2.2.20 | 有 | 无 provider，现算 |
| 262 | 2.3.20 | 有 | 有类无缓存，无 requestUpdate |
| 263 | 2.4.0 | 有 | toolsByProvider 缓存 + requestUpdate + invalidate |

⇒ 253/262 同一缺陷、同一修法；只有 263 要「摘缓存再重算」。核对法：用各版发行版自带的
kotlin-reflect + 平台 jar 直接跑 D239 两个测试类（`build/classes/kotlin/{main,mcpStub,test}` +
`build/classes/java/test` + `<distro>/lib/*` + `lib/modules/*` + `plugins/java/lib/*` +
`kotlin-reflect.jar` + junit4），243/253/262/263 各 **16/16 通过**；`MetadataVersion.INSTANCE
.isCompatible(2.3.0)` 各版全 true。**262 沙盒端到端已补（20261119）**：`runIde -PgifRecord=true`
（沙盒 MPSV-GifFresh，MCP 端口 seed 64343 避开日常 IDE）四判据全过——冷启 68/11 → 关 57/0 → 开 68/11 →
`sqlv_check_sql` 实调 OK；日志见 `平台工具清单无缓存入口（253/262 形态），跳过主动重算`。**未做** 253 整机
（本机无该版本 IDE；切 gradle 平台到 262 曾报 无法访问 ToolWindow，重跑即愈＝增量混编假象，非真编译门）。
注意 `cygpath -w "dir/*"` 会把 `*` 吃掉 → 类路径变成目录本身，须写成 `"$(cygpath -w dir)\*"`。

## 保留在 master 的诊断

- `T-McpDynamicToolset: refl <壳类> declared=… all=… kotlinMeta=…`（每壳类一行）
- `T-McpDynamicToolset: shell self-check <壳类>=[<工具名>] | …`：直接调平台自己的 `asTools` 逐个展开，
  平台侧异常被吞成一行 WARN 时这行能点名到具体 toolset 并打全栈

## 两个读数坑

- **关闭态新 JVM 一条日志都没有**：`unregisterAll()` 在「从未注册过」时提前 return，
  零 MCP 行 + `tools/list` 0 个是正常关闭态，别误判成插件没加载。
- **别用截断正则读日志**：`sed 's/.*\(EP self-check[^,]*\).*/\1/'` 会把 `shells=11` 截成 `shells=1`。

相关：[[reference-local-mcp-curl-probe-and-codeatlas]]、[[project-pending-plans]]、
[[feedback-no-gui-while-user-uses-computer]]。计划册
`docs/开发计划/20261119-1710-D239-MCP壳类反射可见性修复-开发计划.md`（§九 实施记录含四层对照表）；
教训 `docs/LESSONS.md` L88/L89。
