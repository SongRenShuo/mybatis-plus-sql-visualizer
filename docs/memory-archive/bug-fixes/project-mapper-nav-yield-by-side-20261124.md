---
name: project-mapper-nav-yield-by-side-20261124
description: T-MapperNavYieldBySide（20261124，历史）——曾把让位收窄为「只让 XML 语句行」；**已被 20261125 的「两侧都让 + 类级跳转」覆盖**，现行口径看 project-mapper-nav-namespace-and-class-jump-20261125
metadata:
  node_type: memory
  type: project
  originSessionId: sess_7933450a-6d57-4756-a185-caac0d14c652
---

> ⚠️ **口径已被 2026-11-25 覆盖**：用户最终拍板「让位两侧都让」，并新增 XML 根标签→接口类、接口类名→XML 两跳。现行实现与读数见 [[project-mapper-nav-namespace-and-class-jump-20261125]]；本文件保留 20261124 当时的「只让 XML」决策与其沙盒验收记录。

2026-11-24 收口。用户最终口径（三句原话）：**图标先不换**（角标与换色/换形的预览都被否：「这些图标不合适」）、**设置中的那个配置不用拆**、「应该插件自动处理」。

落地（master `beef3fa18`，文档回填 `f59c1124d`，均已双仓推送，令牌通路无弹窗）：

- `MapperJavaToXmlLineMarkerProvider` **移除让位门** → Mapper 接口方法行恒出本插件图标（总闸 `showGutterIcon` 与 `mapperXml` 组闸保留）。
- `MapperXmlToJavaLineMarkerProvider` 让位门保留 → 只剩 XML 语句行让位（对方箭头在同一行，且同行已有我方预览图标）。
- 设置项仍是单个 `mapperNavYieldToCodeHelper`，语义收窄为「让出 XML 语句行的跳转图标」；中英 bundle（`settings.mapperNavYield` / `.tip`）、`PluginSettings` KDoc、设置页三处注释同步。
- 测试：`MapperNavigationGutterGateTest` Java 侧断言 0→1；`MapperNavCoexistUastReproTest` 让位开用例 1→2 枚（类头 KDoc 改现行语义）。

验证：

- 定向 47/0（`MapperNavigationGutterGateTest` 11、`MapperNavCoexistUastReproTest` 6、`MapperNavigationLineMarkerProvidersTest` 21、`BundleParityGuardTest` 5、`SettingsPanelSearchableGuardTest` 4）。
- 反向：把 Java 侧让位门加回 → 两测试类各恰 1 红（正是两条新断言），还原复绿（11/0、6/0）。
- 非 GUI 复现成立（见 [[reference-intellij-uast-linemarker-metamatch]]）：UAST provider 被 Java 文件收集并真实调用；改动前实测让位开 1 枚 / 关 2 枚。

验证闭环（后续已全部完成）：

- 推送后 CI run **45400815977**：`test (5)` 红——旧断言 `IbatisNavigationLineMarkerProvidersTest` 把 Java 侧当「对照半边」断言 `assertEmpty`；已改新语义（XML 侧让位 + Java 侧 1 枚）→ 提交 `1fbc45c58`，CI run **45403978602** 全绿 8/8。见 [[pitfall-cross-surface-test-assertion-coupling]]。
- **243 沙盒 GUI 实测**（提交 `b3f8e8173`，截图 `build/MPSV-NavYield-shots/`，配方 `.local/start-navyield-ui243.ps1` + `navyield-ui243.init.gradle`）：
  - Java `CustInfoMapper.selectTopCustomers` 方法行 **2 枚同款红色图标**；hover 左枚 tooltip＝「跳转到 Mapper XML 语句」（我方），右枚为 MPJ → Java 侧不让位生效。
  - 同名 XML `<select>` 行只有 1 枚青色 MyBatis 图标（MPJ）+ 我方预览（紫 Q），**无我方跳转** → XML 侧让位生效。
  - 沙盒三坑：①`prepareSandbox` 会清空沙盒 `plugins/`，启动前复制 MPJ 不生效，须给 `runIde` 加 `doFirst` 复制；②Trust 对话框合成输入无效且挡项目打开，须写 `config/options/trusted-paths.xml`（`Trusted.Paths / TRUSTED_PROJECT_PATHS`）预信任；③多模块同名文件 Ctrl+Shift+N 会选错（用路径片段或 Ctrl+N 按 FQN）。
- 图标改款：用户暂缓（角标 A/B/C/D 与 E1 蓝鸟 / E2 青鸟 / F 圆角底牌 / G 双向箭头均未采纳）。

现行口径（2026-11-25：两侧都让 + 类级跳转 + 设置切换即时刷新）见 [[project-mapper-nav-namespace-and-class-jump-20261125]]。

关联 [[pitfall-yield-gate-plugin-level-vacuum]]、[[reference-mapper-jump-icons-identical-to-mpj]]、[[reference-mpj-linemarker-providers-20261124]]。