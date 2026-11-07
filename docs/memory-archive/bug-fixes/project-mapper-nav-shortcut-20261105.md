---
name: project-mapper-nav-shortcut-20261105
description: T-MapperNavShortcut（20261105 立项，**20261106 已随 v2026.11.6-beta.1 发行**）——Mapper
  接口与 XML 语句跳转接入 IDEA 原生 Ctrl+B/Ctrl+Click 与 Ctrl+Alt+Home，注册 gotoDeclarationHandler + gotoRelatedProvider 两个 EP
metadata:
  node_type: memory
  type: project
  originSessionId: sess_1b38c306-e217-4a0a-9293-ab84e2d7162f
---

# T-MapperNavShortcut（2026-11-05 立项，待拍板，未动生产代码）

用户报障原文：「mapper 和 xml 跳转不支持快捷键，正常应该跟着 IDEA 原生的快捷键支持跳转」。

计划册＝仓库 `docs/开发计划/20261105-1404-T-MapperNavShortcut-Mapper接口与XML语句跳转接入IDEA原生快捷键-开发计划.md`，已登记 `docs/索引/开发计划.md` 表首；立项时 master `8bf489ff`。

## 现状（实测，非推断）

- Mapper 双向跳转只有 gutter 图标：`ui/gutter/MapperNavigationLineMarkerProviders.kt:57-68`（XML→Java）、`:107-119`（Java→XML），导航闭包是 `GutterIconNavigationHandler`，纯鼠标。
- 两个 provider 继承裸 `LineMarkerProvider`，不是 `RelatedItemLineMarkerProvider`，所以 `Ctrl+Alt+Home` 关联符号也弹不出来。
- **Java→XML 整条方向缺失**：Java 侧零 `PsiReference`、零 `GotoDeclarationHandler`（全仓 grep 命中 0）。
- XML→Java 只有 `id` **属性值**可用（T-XmlGap W7 的 soft 引用 `MapperMethodReference`，`ui/ref/MybatisXmlReferenceContributor.kt:67-68`）；语句**标签名**上按 Ctrl+B 无反应。
- 接口方法上 Alt+F7 查找用法已可用（XML 侧引用反查命中方法），属回归保护项，不许改坏。

## 方案

单个双向 `GotoDeclarationHandler`（EP 不分语言，一个类同时覆盖 Java 与 XML）：

- Java 侧：`PsiIdentifier` → 父 `PsiMethod` 且是 `nameIdentifier` → `containingClass.isInterface` → `qualifiedName` → 查 XML 语句；命中返回语句 `id` 值 token，未命中返回 `null`（回落原生）。
- XML 侧：`XmlToken` ∈ {起始标签名, `id` 属性名, `id` 值} → 宿主标签 ∈ select/insert/update/delete 且为 `<mapper>` 直接子级 → `resolveInterfaceMethod` → 返回 `PsiMethod`。
- 新增 `MybatisXmlTargets.resolveStatementTag(project, namespace, id)`，复用既有 namespace 单槽 memo + 清单 TTL 5s；**不新建缓存、不引监听器、不改 `MapperXmlLocator`**（行号版仍服务日志跳转）。
- dumb 态与异常一律返回 `null`（`JavaPsiFacade.findClass` 在 dumb 态抛 `IndexNotReadyException`）。
- 可选 W3：`GotoRelatedProvider` 覆盖 `Ctrl+Alt+Home`。

明确不做：把 gutter 改成 `RelatedItemLineMarkerProvider`、rename 联动、`Ctrl+Alt+B`、新增设置开关、报红、Kotlin mapper 接口（与 gutter 同口径只覆盖 Java）。

## 待用户拍板

- D-1：`Ctrl+Alt+Home` 关联符号是否纳入本券（建议纳入）。
- D-2：键盘导航是否跟随 `mapperNavYieldToCodeHelper` 让位（建议不跟随——handler 首个非空即返回，同目标不产生双目标；让位反而可能造出死键）。
- D-3：是否新增「Mapper 跳转」开关（建议不加）。

关键平台事实见 [[reference-intellij-navigation-action-mechanism]]，键位继承要求见 [[feedback-shortcuts-follow-native-keymap]]。

## 状态：已实施并随 v2026.11.6-beta.1 发行（2026-11-06）

提交 `f299417b`（+ 合并 `0d6a242a`）。三个 D 点全按建议拍板：D-1 纳入 `Ctrl+Alt+Home`、D-2 不跟随竞品让位、D-3 不加开关。

落地形态：`ui/nav/MapperNavigationGotoDeclarationHandler` + `MapperNavigationGotoRelatedProvider` + `MapperNavigationTargets`（复用既有 namespace 单槽 memo + 清单 TTL 5s，未新建缓存），`plugin.xml` 注册两个 EP。
**注册段带硬注释「不得加 `<keyboard-shortcut>` / `<mouse-shortcut>`」**，测试有断言钉住（用户令：键位归平台 action）。
handler 返回 `null` 即回落原生，非 Mapper 位置行为零变化；`Ctrl+Alt+Home` 的分组名走新增 bundle 键 `nav.related.group.mapperStatement` / `.mapperMethod`。
