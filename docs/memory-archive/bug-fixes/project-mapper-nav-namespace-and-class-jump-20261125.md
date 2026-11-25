---
name: project-mapper-nav-namespace-and-class-jump-20261125
description: T-MapperNavNamespaceJump + T-MapperClassJump 已落 master（ddd37a5be）——XML 根标签→接口类、无方法 Ctrl+B 退化跳类、接口类名→XML 三格；让位最终口径=两侧都让 + 设置切换即时重算；定向 148/0、CI 45529195293 8/8 绿
metadata:
  node_type: memory
  type: project
  originSessionId: sess_7933450a-6d57-4756-a185-caac0d14c652
---

2026-11-25 收口（master `ddd37a5be`，已推双仓；前序 `a009cf207` 的「只让 XML」被本轮覆盖）。用户最终口径：**让位两侧都让**（XML 语句行 + Mapper 接口方法行），文案「与同类 MyBatis 导航插件共存时让出 XML 与 Mapper 跳转图标」；**图标仍不改**。

## 三个新能力

- **XML 根标签 → 接口类**（`<mapper namespace>`）：`MapperXmlToJavaLineMarkerProvider` 新增根标签分支（图标复用 `MapperStatement`，五段式缓存 `resolvedClassKeys`；namespace 指向不存在不出死图标；同受 XML 侧让位门）。Ctrl+B 与 GotoRelated 同步（`MapperNavigationTargets.mapperClassOfRootTag` / `rootMapperTagOf`）。
- **语句无同名方法时 Ctrl+B 退化跳接口类**：`MapperNavigationGotoDeclarationHandler.xmlToJava` 在 `methodFor` 为 null 时落 `mapperClassOf(file)`，不造图标、不造死链接。
- **接口类名 → XML 根标签**（用户报「mapper.java 我们少一个类跳转图标」）：我方此前只锚方法名；新增 `createClassMarker`（`resolvedMapperClassKeys` + `classToXmlKey`）、`MapperNavigationTargets.xmlFileForMapperInterface`，三格齐（gutter / Ctrl+B / GotoRelated）。

## 设置切换即时生效

`PluginSettingsPanel.apply()` 记录 `mapperNavYieldToCodeHelper` 旧值，变化则对 `ProjectManager.getOpenProjects()` 逐个 `DaemonCodeAnalyzer.restart()`（243 无参重载可用）。此前用户反馈「设置页调完要再打开一次文件才生效」。

## 能力前提（决定让位是否会让出空位）

- XML 侧：MPJ / MyBatisX / MyBatisPlus 都画；
- Java 侧：MyBatisX / MyBatisPlus 画（`MapperLineMarkerProvider`，language=JAVA，锚方法名 identifier）；**MPJ 的 UAST 箭头在 2026.3 实测不产出**（XML 侧正常）——只装 MPJ 时让位后 Java 行会空，用户已确认接受。详见 [[reference-mybatis-nav-peer-plugins-matrix-20261125]]。

## 验证

- 定向 10 类 **148/0**（新增 6 例：类名图标、类名无 XML 不出、类名让位基线、类名+让位、Ctrl+B 类名、GotoRelated 类名）。
- 反向：类名三面（gutter / Ctrl+B / GotoRelated）同时摘除 → 恰 4 红；两侧让位门摘除 → 恰 3 红；还原后复跑绿。
- 沙盒 GUI（IU-243 + 真实 MPJ / MyBatisX）：根标签图标 + tooltip「跳转到 Mapper 接口」+ 点击跳接口类；`CtAccount0002Mapper.selectByTenant`（接口无此方法）Ctrl+B 跳到接口类；装 MyBatisX 时 XML 侧只剩对方图标（tooltip `Data access object found - …`）。
- CI run **45529195293** 8/8 全绿。

## 测试坑（新增图标会污染既有基线）

- 接口类名新增一枚图标后，按「全文件收集」计数的既有 Java 用例全红（`single()` 炸、计数 +1）——`markers()` 必须默认过滤类名 marker（`isMapperClassMarker`），类名用例改用「指定 leaf」收集。
- 类级正缓存是静态的，同工程同 namespace 跨用例命中 → `setUp` 里必须 `resetClassCacheForTest()`，否则「无 XML 不出图标」用例假失败。
- iBatis 测试的 XML 基线同样要排除新增的根标签图标。

关联 [[project-mapper-nav-yield-by-side-20261124]]（被本轮覆盖）、[[pitfall-cross-surface-test-assertion-coupling]]、[[pitfall-yield-gate-plugin-level-vacuum]]、[[reference-settings-change-does-not-rerun-line-markers]]。
