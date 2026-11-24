# T-MapperNavYieldBySide —— 让位后「同款图标分辨不出」与切换不重算

- 任务编号：T-MapperNavYieldBySide（沿用原立项编号；文件名不改，避免索引链接分裂）
- 立项时间：2026-11-24 16:15；修订 2026-11-24 17:05（反编译平台与 MPJ 后推翻初版根因）
- 触发：用户真机报障（2026-11-24）。原话：「mapper和xml的跳转图标 mapper中的图标没显示 xml的跳转图标正常 取消勾选【与同类MyBatis导航插件共存时让出 Mapper 跳转图标】后出现两个图标 是和mp插件共存的正常情况 但是他再勾选上 又变成正常的一个跳转图标了 现在有可能是和mp插件还有些冲突没解决掉」；随后补充：「不是我方重复了 是两个插件的图标一样」。
- 关联：T-MapperNavCoexistYield（让位功能本体）、T-MapperNavShortcut（`f299417b`，Ctrl+B 兜底）

---

## 一、现象（已由截图与用户补充确认）

| 状态 | 让位开关 | Java `MerchantWalletFlowMapper` 方法行 | XML `<select id=...>` 行 |
|---|---|---|---|
| ① | 勾选（默认） | 1 枚（用户感知为「我们的图标没显示」） | 有图标（同类插件画的 + 我方预览图标） |
| ② | 取消勾选 | **2 枚**，图标图形完全一样 | 我方跳转 + 对方箭头 + 我方预览 |
| ③ | 再勾选 | 又回到 1 枚 | 同 ① |

截图放大（`image-2645f84ad5d721480423b9bc16afccef.png` 第 38 行，NEAREST 6×）：两枚图标是同一图形，红色主体 `#D40000` + 深色 `#1A1A1A` + 白点，不是 IDEA 原生的 implemented/overridden 箭头。

---

## 二、我方代码事实（已核实）

1. 两个 provider 各有一道同一形态的让位门，判据＝`mapperNavYieldToCodeHelper && isPeerPluginActive()`：
   - XML 语句行 → Java 方法：`MapperNavigationLineMarkerProviders.kt:75-86`
   - Java 方法行 → XML 语句：同文件 `:149-157`
2. 探测只回答「同类插件是否启用」（`MyBatisCompetitorGate.kt:62-72`，懒缓存、无失效）。设计原意：同行两个图标都合规，纪律是让位。
3. XML 语句行还有第二枚我方图标：`XmlSqlGutterLineMarkerProvider`（快趟，`:44-62`，SQL 预览），**不在让位范围**（原计划非目标）。
4. 跳转能力另有兜底：`MapperNavigationGotoDeclarationHandler`（Ctrl+B / Ctrl+Click），不受让位影响。
5. 切换开关不触发重算：生产代码唯一的 `DaemonCodeAnalyzer.restart` 在 `ui/SqlPreviewPanelExecution.kt:2460-2465`；设置面板 `apply()` 后没有针对 gutter 的重启，图标只等下一次慢趟换装。

---

## 三、对方侧实证（fernflower 反编译，MPJ `3.7.0+2321`，IDE IU-263.7307.200）

### 3.1 MPJ 的 Java 侧箭头确实在渲染

- `META-INF/plugin.xml:586`：`<codeInsight.lineMarkerProvider language="UAST" implementationClass="com.ccnode.codegenerator.view.A" />`。
- `view/A` 反编译＝`MybatisJavaLineMarkerProvider`，方法分支确实是「PsiMethod → XML」：`UastUtils.getUParentForIdentifier` → `getAsJavaPsiElement(..., PsiMethod.class)` → `E.a.a(method, project, className).d()` → `NavigationGutterIconBuilder`（反编译产物 `A.java:117-170`）。
- MPJ 全包只有 5 个类引用 `GutterIconRenderer`，正是 5 个 lineMarker provider；**没有**手动 `MarkupModel`/`addLineHighlighter` 旁路。

### 3.2 `language="UAST"` 会被 Java 文件投递（初版结论在此处出错）

- `com.intellij.uast.UastMetaLanguage`（`plugins/java/lib/modules/intellij.java.analysis.jar`）反编译：

```java
public final class UastMetaLanguage extends MetaLanguage {
   public static final UastMetaLanguage INSTANCE = new UastMetaLanguage();
   private UastMetaLanguage() { super("UAST"); }
   public boolean matchesLanguage(Language l) { return getUastMetaLanguages().contains(l); }
   public Collection<Language> getMatchingLanguages() { return getUastMetaLanguages(); }
   public static final class Provider implements MetaLanguageProvider { ... }
}
```

- `LanguageExtension.buildExtensions(stringKey, JAVA)`（`intellij.platform.core.jar`）在 `LanguageUtil.matchingMetaLanguages(JAVA)` 非空时，会把 meta language 的 key（这里是 `"UAST"`）并入收集键集合。于是 `LineMarkersPass` 给 Java 文件取 provider 时，**UAST 注册的 provider 会被取到并调用**。
- 结论：MPJ 在 Mapper 接口方法行正常渲染，`view/I`（`language="XML"`）在 XML 侧正常渲染。**没有「对方没画」这回事。**

### 3.3 两家图标是同一图形（客观事实）

| 图标 | 文件 | path 数 | 主色 | md5 前 12 |
|---|---|---|---|---|
| 我方 Java | `src/main/resources/icons/toolbar/mapperMethod.svg` | 11 | `#D40000` | `7b6e7913ff2c` |
| MPJ Java | MPJ 包 `icon/mybatis_java.svg` | 11 | `#D40000` | `2243c425825b` |
| 我方 XML | `src/main/resources/icons/toolbar/mapperStatement.svg` | 11 | `#00CBCC` | `f4541765d977` |
| MPJ XML | MPJ 包 `icon/mybatis_xml.svg` | 11 | `#00CBCC` | `63f0168ccb97` |

图形路径序列一致（11 条 path、同三色），差异只在 SVG 包裹头注释/格式化 ⇒ 我方图标与 MPJ 用同款图形。用户「两个插件的图标一样」「两个鸟」的判断成立。

---

## 四、根因

### R1：让位逻辑没有算错——它按设计让位，且对方确实在画

勾选时 Java 行剩的那 1 枚就是 MPJ 画的（§3.1/3.2）；取消勾选后多出的 1 枚是我方的。两次读数分别是「1 枚」与「2 枚」，与让位语义完全一致。

### R2：真正的问题是「同款图标 + 让位后只剩对方那枚」，用户无法分辨

- 两枚图标图形、颜色完全相同（§3.3），用户无法通过外观判断「剩下这枚是谁画的」。
- 让位把**我方**藏起来后，Java 行仍有一枚同款图标——看起来像「我方图标没让位」或「我方图标根本没渲染」；用户读到的就是「mapper 中的图标没显示」，进而怀疑让位有冲突。
- 取消勾选后两枚同款并排，看起来像「我方重复绘制」，用户第一反应也是缺陷。

### R3（次因）：切换不重算，读数滞后

设置 apply 不重启 daemon，三次切换之间的图标变化取决于哪趟慢趟先跑（§二.5）。这加剧了「一会儿 1 枚一会儿 2 枚」的错觉，也可能造成第一次观察时「0 枚」的瞬时态。

---

## 五、目标与非目标

**目标**

- 让用户在任何状态下都能分辨「哪枚图标是本插件的」。
- 让位开关的后果在设置页可预期（让位后这一行还剩什么、由谁画）。
- 切换开关后图标即刻按新值重算。

**非目标**

- 不改 MPJ 行为、不做跨插件写动作。
- 不改变让位语义（「同类插件在位即让位」仍是纪律，除非 D-1 另定）。
- 不让位 SQL 预览图标。

---

## 六、实施方案

### S1 我方图标可辨识（主修，需 D-2 定样式）

现状：我方 Java/XML 跳转图标与 MPJ 完全同款（§3.3）。两种改法：

- **S1-a 加角标**：保留 MyBatis 主图形，右下角叠加我方标识（例如 4px 的「SQL」/青色方块），16×16 下仍可辨。改动仅 `mapperMethod.svg` / `mapperStatement.svg`（以及 dark 变体若有）。
- **S1-b 换主色/换形**：把我方 Java 图标从红色改成青色或其他图形；XML 侧同理。代价是与「MyBatis 官方图标」的既有认知脱钩。

两种都要在 `MpvsIcons` 与 `GutterGroups` 不变的前提下替换资源文件；不新增设置项。

### S2 让位分侧（视 D-1 决定）

若用户希望「Java 行始终有我方图标」，则：
- `mapperNavYieldToCodeHelper` 语义收窄为「让出 XML 语句行」；新增 `mapperNavYieldJavaSide: Boolean = false`；
- Java provider 的门改为 `shouldYield(yield, peer) && state.mapperNavYieldJavaSide`；
- 配合 S1 后两枚图标可区分，并存不再造成「重复」错觉。

若用户接受「让位后 Java 行只显示对方那枚」，则保持现状（S1 仍需要，否则用户永远分不清谁是谁）。

### S3 切换即重算

`PluginSettingsPanel.apply()` 比较两项让位字段新旧值；变化则对 `ProjectManager.getInstance().openProjects` 中未 dispose 的项目逐个 `DaemonCodeAnalyzer.getInstance(project).restart()`（EDT + `invokeLater` + `ModalityState.any()`，照 `SqlPreviewPanelExecution.kt:2450-2465`；243 只能用 `restart(psi)` 逐文件）。

### S4 设置页文案

- `settings.mapperNavYield.tip` 补一句：「开启让位后，XML 语句行 / Mapper 接口方法行仍可能显示同类插件画的同款图标，那不是本插件；本插件的跳转始终可用 Ctrl+B / Ctrl+Click」。
- 若启用 S2，第二项文案写清「当前同类插件在 Mapper 接口方法行也会画同款图标，勾选后该行不再有两枚同款」。

### S5 测试与反向

- `MapperNavCoexistGateTest`：若走 S2，补分侧真值表。
- `MapperNavigationGutterGateTest`：S2 的六条（XML 让位 0 / Java 默认 1 / Java 子开关开 0 / 总开关关两侧出 / 不在位基线 / 抛异常不让位）。
- 图标资源改动：`MpvsIcons` 相关测试只断言图标非空，不需要改；`BundleParityGuardTest` 只在文案改动时跑。
- 反向验证：S2 的 Java 门短路 → 分侧用例恰红；S3 的 `shouldRestartGutter` 恒 false → 重算用例恰红。

---

## 七、待拍板

- **D-1**：让位的默认形态。
  - A（保持现状）：XML + Java 都让位；配合 S1 让用户能认出「剩下的是对方的」。
  - B（分侧）：XML 让位、Java 不让位；两枚同款并排但都可见（配合 S1 可分辨）。
- **D-2**：图标区分样式。推荐 S1-a 加角标（保留 MyBatis 图形认知，改动最小）；S1-b 换色会与竞品彻底区分，但用户可能认不出这是 Mapper 图标。
- **D-3**：S2 是否要做（若 D-1 选 A 则不做）。

---

## 八、验收

- A1：勾选让位、Java 行 1 枚 → 该枚是本插件图标（hover tooltip「跳转到 Mapper XML 语句」），或明确是对方的但外观可区分（S1 后）。
- A2：取消勾选 → Java 行 2 枚，外观可区分（S1 后），不再出现「两枚一样」。
- A3：切换开关 1s 内图标数量按新值变化（S3）。
- A4：XML 行让位后仍保留预览图标；不出现两枚我方跳转图标。
- A5：未装 MPJ 的环境图标外观与行为按 S1 预期，既有基线测试不红。

---

## 九、风险

| 风险 | 处置 |
|---|---|
| S1 改图标后老用户认不出 Mapper 跳转 | 角标方案保留主图形；发行说明写一条 |
| 让位后 Java 行只剩对方图标，用户仍觉得「我方消失」 | S4 文案说明 + Ctrl+B 兜底；D-1 选 B 则不存在此问题 |
| MPJ 将来改图标 | 我方不再与其同款，问题自然消失 |
| S3 重启 daemon 造成短暂卡顿 | 只在两项让位开关变化时触发，`invokeLater` 合并同帧 apply |

---

## 十、实施记录（含被推翻的初版结论）

- 初版（16:15）曾判定「MPJ 的 Java 侧 provider 是 UAST 死注册、对方不渲染」。**该结论错误**：`com.intellij.uast.UastMetaLanguage extends MetaLanguage`（`super("UAST")` + `MetaLanguageProvider`），平台把 meta language 的 key 并入 Java 文件的 provider 收集键集合，UAST provider 会被调用。证据见 §3.2。
- 初版把「两枚图标」判断为「我方重复绘制」，用户 17:00 纠正为「两个插件的图标一样」；§3.3 的 SVG 对比证实两家同款。
- 待实施：S1/S3/S4（S2 待 D-1 拍板）；P-1~P-3 已由反编译与截图解决。

---

## 十一、实施记录（2026-11-24，master 直改）

### 11.1 拍板结果

- 图标先不换（S1 暂缓，`mapperMethod.svg` / `mapperStatement.svg` 维持原样）。
- 设置项不拆：保持单一 `mapperNavYieldToCodeHelper`。
- 由插件自动处理：Java 侧恒不让位、XML 侧继续让位；实现为内部固定，不新增子开关（等价 D-1 的 B 案，但对用户只暴露一个开关）。

### 11.2 变更清单

- `ui/gutter/MapperNavigationLineMarkerProviders.kt`
  - `MapperJavaToXmlLineMarkerProvider.collectSlowLineMarkers` 移除让位门（保留总闸与组闸），KDoc 写清三条依据：对方 UAST provider 在当前平台正常投递、两家图标同款、让位后该行只剩对方一枚无法分辨。
  - `MapperXmlToJavaLineMarkerProvider` 的让位门保留，注释改为「仅 XML 语句行让位」。
- `config/PluginSettings.kt`：`mapperNavYieldToCodeHelper` KDoc 收窄为「只让 XML 语句行；Mapper 接口方法行恒显示」。
- `messages/MpSqlVisualizerBundle.properties` / `_zh.properties`：`settings.mapperNavYield` / `.tip` 中英改写（说明 Java 侧恒显示、原因、关闭后的效果）。
- 设置页三处注释同步：`PluginSettingsPanel.kt`、`PluginSettingsPanelSections.kt`、`PluginSettingsFolderConfigurable.kt`。
- 测试：
  - `MapperNavigationGutterGateTest.test竞品在位且让位开_Java侧零图标` → `…_Java侧仍保留图标`，断言 0 → 1。
  - `MapperNavCoexistUastReproTest.test竞品在位且让位开方法行只剩1枚图标` → `…仍2枚图标`，断言 own 0→1、total 1→2；类头 KDoc 同步为现行语义。
- `CHANGELOG.md`：`[Unreleased]` 新增一条（Fixed）。

### 11.3 定向测试读数（2026-11-24）

| 测试类 | tests | failures | errors |
|---|---|---|---|
| `ui.gutter.MapperNavigationGutterGateTest` | 11 | 0 | 0 |
| `ui.gutter.MapperNavCoexistUastReproTest` | 6 | 0 | 0 |
| `ui.gutter.MapperNavigationLineMarkerProvidersTest` | 21 | 0 | 0 |
| `i18n.BundleParityGuardTest` | 5 | 0 | 0 |
| `config.SettingsPanelSearchableGuardTest` | 4 | 0 | 0 |

### 11.4 反向验证

（待填：把 Java 侧让位门加回 → 两条用例应变红；还原后复绿）