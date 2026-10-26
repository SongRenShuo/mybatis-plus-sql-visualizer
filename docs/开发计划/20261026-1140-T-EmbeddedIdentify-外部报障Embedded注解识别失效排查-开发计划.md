# T-EmbeddedIdentify：外部报障「@Embedded 注解无法正常识别」排查与修复计划

> **创建日期**：2026-10-26 11:40
> **触发**：外部插件用户反馈 @Embedded 注解无法正常识别；本方在主分支（master，插件 jar 2026-10-26 10:40 部署默认沙盒）复现
> **本轮性质**：**诊断先行的排查计划**——复现会话的可测产物全部对账为「渲染正确」，真根因不在 demo 语料已覆盖面内，须先补取证再落修
> **相关**：`T-Embedded`/`T-J18`（展平）、`T-Embedded D1~D3`（点路径覆写/SD 形态/混合注解）、`T-EmbeddedColPrefix`（命名三通道，`a33466c9`/`6919d8ef`/`86e2eb42`）、`T-DialectInferPollution`（实体仲裁重建，10-22~23）

---

## 〇、一句话结论

**master 上 @Embedded 识别链逐环节实测为正确**（生产管线探针 + 复现会话自身的 exec 落盘双重证实）；外部报障的真实形态必然落在 demo 语料与既有测试**未覆盖的工程形态**里。本轮不做盲修，先按 §五 W0/W1 补两样东西（外部用户原始形态 + 生产级诊断日志），拿到后再按 §六 候选面定根因落修。

---

## 一、识别链现状（master `048d4e03`）

```
预览触发（gutter/动作）
  → resolveHqlTable（SqlPreviewServiceEnhancements.kt:510）
      → EntityAliasSweep.resolve → EntityClassResolver（模块作用域 + 歧义 fail-closed，10-22~23 重建）
      → JpaEntityResolver.resolve（JPA 轨）/ EntityResolver.resolve（MP 轨）
          → resolveField（JpaEntityResolver.kt:180）
              → isSpringDataEmbeddedField（SD 裸/捷径，轨1全名/轨2文本）
              → isEmbeddedField（jakarta FQN → javax FQN → 短名三轨，JpaMetadataReader.findFlex:147）
              → flattenEmbeddedColumns（JpaMetadataReader.kt:704）
                  → 类型解析失败 ⇒ 空清单 ⇒ 调用方降级单列（伪列 fieldName 推断名）★唯一降级出口
          → ColumnInfo(embedded/embeddedParent/embeddedPath)（JpaEntityResolver.kt:333-353）
  → DerivedQueryConverter（embeddedPath 全路径轨）/ HqlConverter / JpqlConverter（嵌入段限定符剥离）
  → ColumnNameCalibrator（真库对照改名，86e2eb42 自愈档）
  → SqlGenerator.generate → 对话框（formatSql=true 格式化）；exec 落盘用同一 result.sql（紧凑版）
```

自 10-13（`6919d8ef`）起 **master 未改动过任何识别/展平/映射代码**；10-22~23 的实体仲裁重建（`54059ef0`/`91400a3e`/`a52d083d`/`1b61964f`）只动「按名找实体类」，不触嵌入展平。`sql-insight`（`0f5a4dc5`）与 `magpie`（`713416bb`）**未合入 master**（git merge-base 实测 + 用户确认），不在嫌疑内。

## 二、本轮取证：四路证据全部指向「渲染正确」

### 2.1 复现会话还原（沙盒 idea.log，2026-10-26 10:46~10:47）

沙盒 = 默认沙盒 `.intellijPlatform/sandbox/.../IU-2024.3.7.1`，插件 jar 构建于 10-26 10:40（master 源码）。打开工程 = demo 仓根（多模块）。三次预览全部命中 **WmsDockBooking**（javax.persistence + Lombok @Data + `@Embedded private UpdateInfo updateInfo`，UpdateInfo 为 `@Embeddable`、内层字段**无 @Column**——兜底命名档）：

| 时刻 | 触发 gutter | 查询 | 对话框 len | 生产管线探针渲染 len | 差额 |
|---|---|---|---|---|---|
| 10:46:14 | JpqlGutter | `@Query` select b.updateInfo.createdTime… | 103 | 99 | +4（formatSql 换行） |
| 10:46:17 | JpaGutter | findByUpdateInfoCreatedTimeAfter | 197 | 157 | +40（formatSql 换行） |
| 10:46:59 / 10:47:19 | CustomDaoHql | `from WmsDockBooking booking where booking.warehouseId = :warehouseId` | 293 | 253 | +40（formatSql 换行） |

**exec 落盘实证**：10:46:59 那次预览同时写了 `demo/.idea/sql-visualizer/exec/mpsql-exec.sql`，内容 = 253 字符紧凑版，**`booking.created_time` 在 SELECT 列清单中**——与对话框同源（`prewarmExecTempFile(project, result.sql)`，DataSourceSelector 预热链写的就是对话框那份 `result.sql` 的紧凑形态）。10-02 模块级 exec 落盘（派生查询渲染）同样正确。

### 2.2 生产管线探针（临时测试，已删，源码见附录 A）

以 demo 仓 jpa-hibernate-demo **逐字形态**（javax 短名导入、Lombok、无内层 @Column）走真实解析+转换链：

- SELECT 全列展开：`SELECT booking.id, …, booking.atd, booking.created_time from wms_dock_booking booking where booking.type = ?` ✓
- 派生查询 embeddedPath 全路径轨：`WHERE created_time > ?`，无伪列 `update_info_created_time` ✓
- JPQL 嵌入段限定符剥离（SELECT 投影 + ORDER BY 双位）：`select b.created_time … order by b.created_time desc` ✓

### 2.3 既有定向测试全绿

`JpaEmbeddedColumnNamingTest` + `EntityResolverEmbeddedTest` + `JpaEntityResolverMappingDeepTest`（含 SD 桩 FQN 形态、派生查询 `findByUpdateInfoCreatedTimeAfter` 同款、点路径覆写）master 全绿（定向 3 类，BUILD SUCCESSFUL）。

### 2.4 环境变量排查全排除

| 排除项 | 证据 |
|---|---|
| 持久化设置错档 | 沙盒 config 无 `embeddedColumnNaming` 键（默认 AUTO） |
| AUTO 探测误判 | jpa-hibernate-demo 无 component-path 配置 → NONE ✓；jpa-embedded-prefix-demo 有 `implicit_naming_strategy: component-path` → COMPONENT_PATH ✓（两模块按各自 resources 判定，`6919d8ef` 模块收范围生效） |
| 实体歧义/降级 task.* | 日志无「候选：…[工程]/[库]」歧义通知；schema 预热拿到表名 wms_dock_booking ⇒ TableInfo 建成 |
| 部署了旧插件 | 沙盒 jar mtime 10-26 10:40，先于 10:41 IDE 启动 |

## 三、根因判断

master 识别代码无回归；外部报障形态在**未覆盖工程形态**内。按可能性排序的候选根因面（每项附可观测签名，W0 取证后即可对号）：

### H1（高）：混合注解实体的「按设计不识别」（T-Embedded D3 决策 A）

`EntityResolver.kt:189`：MP 实体（`@TableName`）的嵌入字段若带**显式 `@TableField("xxx")`** ⇒ 走单列路径不展平——MP 运行时本身不展平，插件口径=跟真实数据库。外部用户若在 `@TableName` 实体上写了 `@TableField + @Embedded`，看到的正是「@Embedded 不生效」。
**签名**：SELECT/INSERT 出现 `update_info` 这类伪列（字段名 snake），且实体类头有 `@TableName`。

### H2（高）：实体/嵌入类型在依赖 JAR 里的组合盲区

`flattenEmbeddedColumns` 第一行 `(field.type as? PsiClassType)?.resolve()` 为 null（嵌入值对象类索引不可达）⇒ 空清单 ⇒ **降级单列伪列**（`JpaEntityResolver.kt:316-331`）——整段 @Embedded 字段变成一个不存在的列。T-JarEmbedded 治的是「实体反查」，嵌入**值对象**的跨 JAR 解析无定向验证。
**签名**：预览列清单里 @Embedded 字段整段消失、只剩一个与值对象同名的伪列；或 IDE 日志可见实体来自 jar:// 条目。

### H3（中）：Kotlin 实体 @Embedded——零覆盖

`mybatis-plus-kotlin-demo` 存在，但 Kotlin 实体（property + synthetic PsiField）的 @Embedded 展平全链无任何测试。Kotlin 属性注解在 synthetic Java 视图的 modifierList 可见性、`field.type` 的平台类型解析，均未验证。
**签名**：实体文件为 .kt。

### H4（中）：自定义命名策略在 JAR 里（已登记边界，T-EmbeddedColPrefix W1b）

`HibernateNamingConfigProbe` 只扫工程源码与 resources；自定义策略类在依赖 JAR 时探测必 miss ⇒ AUTO 判 NONE，真库却是 component-path ⇒ 列名整体缺前缀（真库 `qty_base_qty`、预览 `base_qty`）。
**签名**：列名能对上「缺一段嵌入前缀」的形态；设置页手动切 COMPONENT_PATH 即恢复。

### H5（中低）：新实体仲裁 fail-closed 的误伤面

10-22~23 仲裁（`54059ef0` W3 零候选拓宽 / P0a/P0b / W6 模块方向 / `1b61964f` 三步收窄）在特定工程布局（同 FQN 双模块、宿主在 JAR、workspace 多模块同载）会**有意**歧义终态（不挑胜者），预览降级。外部用户从旧版升到 v2026.10.23 首次踩到新语义，可能把「实体没解析」笼统报成「@Embedded 不识别」。
**签名**：IDE 右下角通知「实体名歧义：候选 … [工程]/[库]」；或预览出现 `task.*` 形态降级注释。

### H6（低）：对话框非 SQL 面的呈现（参数面板/列元数据/补全）

SQL 文本已证正确，若用户看到的异常在参数对照、错列标注（本会话 7/7 数据源查不了，属沙盒数据源未内省，非缺陷）或列补全对嵌入列的呈现。
**签名**：SQL 正确但参数面板/补全对嵌入列显示异常。

## 四、待验证前提（实施者先证伪再动手）

1. **「复现会话三次预览即用户所指的复现」**——本计划按此假设取证；若用户复现的是别的文件/动作，§二 全部读数作废，W0 必须先问清。
2. **「对话框 len 与探针 len 的差额是 formatSql 换行」**——依据是持久化 `formatSql: true` 与同一 `result.sql` 双消费；未逐字比对格式化器输出，若差额另有来源（如注释头），§2.1 对账表需修正。
3. **「H1 的 @TableField + @Embedded 组合真会同时出现」**——取自 D3 计划决策 A 的描述；外部用户实体是否此形态待 W0 确认。
4. **「Kotlin synthetic field 的注解/modifierList 读取与 Java 一致」**——未做过任何实验，H3 立项前先跑一个 Kotlin 夹具探针。
5. **「外部用户版本 = Marketplace v2026.10.23」**——未与反馈渠道核对；若对方更旧（如 v2026.09.x），回归窗口要按其版本重划。

## 五、工作分解（诊断先行，取证齐全才落修）

### W0 取证补全（对外一次问清，纯人工件）
向反馈渠道（或复现同事）收集三样：
1. 实体源码原文（含 import 区——区分 jakarta/javax/SD、@TableName 有无、@TableField 有无、Kotlin/Java、类是否来自依赖 JAR）；
2. 触发预览的查询（派生方法名 / @Query / HQL 原文）；
3. 预期 vs 实际的截图或 SQL 文本（哪个列错/缺/多前缀）。
附一句环境问询：插件版本号、单模块还是多模块、实体是否在依赖 JAR。
**产出**：本计划 §六 定根因；W0 无产出则整个任务挂起，不做预防性改动。

### W1 生产级嵌入解析诊断日志（无论根因是哪个都需要，先实施）
`JpaEntityResolver.resolveInternal`（或 flattenEmbeddedColumns 出口）加一条 INFO：实体 FQN、命名档判定结果（NONE/COMPONENT_PATH 及来源：显式设置/探测/默认）、嵌入字段清单（字段名 → 列名，embeddedPath、fromAttributeOverride）、展平降级（specs 空而走单列伪列）显式打 WARN。`resolveHqlTable` 补实体解析结果（命中类 URL、候选数）。
约束：走既有 LOG 常量、INFO 级、不新增配置开关；异常路径 runCatching 不改语义。
**验收**：沙盒打开 demo 逐 gutter 预览，日志能逐字回答「这条预览的嵌入列是怎么判的」。

### W2 沙盒复现走查（依赖 W1）
用带 W1 的构建在默认沙盒按 W0 形态复现，抓日志定根因；同时把 demo 现有语料（WmsDockBooking/CustomerEntity/jpa-embedded-prefix-demo）过一遍防意外。
**产出**：根因归类到 H1~H6（或新形态 H7，登记进本档）。

### W3 按 W2 结论落修（候选分支预写判定）
- H1 ⇒ 评估「@TableField 显式名 + @Embedded」改成展平 + 以 @TableField 值作前缀/告警提示（**须先与用户定语义**，动的是 D3 决策 A）；
- H2 ⇒ 嵌入类型解析失败时增设 jar 兜底（复用 `EntityClassFinder` 库扫描/`JpaJarClassFileAnnotationTest` 既有机制）；
- H3 ⇒ Kotlin 实体嵌入全链适配（先探针证伪 §四-4）；
- H4 ⇒ 探测补「依赖 JAR 内策略类」一档（读 jar 索引或复用库扫描），或引导用户显式设置；
- H5 ⇒ 按歧义通知文案与日志现场收窄仲裁（延续 T-ShadowEntityResolve 纪律：不伪造胜者）；
- H6 ⇒ 对应 UI 面修复。
每分支独立提交、达标即提交、附反向验证（摘修复必红）。

### W4 守卫补齐
把 W2 定位的形态固化进定向测试（light 夹具优先；Kotlin 形态若成立补 Kotlin 夹具）。守卫规则按「类级不变式成对 + 反向对照」纪律写。

### W5 收口
定向 → 全量 `testShards` 单路收口 → 大改动判据命中则 `verifyPlugin`；CHANGELOG `[Unreleased] › Fixed` 与本档 §实施记录回填；真机沙盒验收结论回填。构建前跑 `python C:\Users\USER\.zcode\cli\parallel_tasks.py check`。

## 六、实施记录

### W0 复现方法已到位（2026-10-26 用户提供）

复现入口＝demo 仓 `jpa-hibernate-demo/.../dao/impl/CommonDaoHibernateImpl.java#findBookingByValueBean`：

```java
public List<?> findBookingByValueBean(WmsDockBooking sample) {
    return getHibernateTemplate().findByValueBean(
            "from WmsDockBooking booking where booking.warehouseId = :warehouseId", sample);
}
```

即 HibernateTemplate **findByValueBean 值 bean 传参**形态（与 10:46:59/10:47:19 两次 len=293 预览同一 HQL 串 `from WmsDockBooking booking where booking.warehouseId = :warehouseId`）。该 HQL 的 SQL 渲染已由 exec 落盘证明正确（§2.1），故用户所指异常更可能在**对话框非 SQL 面**（值 bean 参数面板/参数对照，H6 方向）或我们尚未捕获的观察点——W2 复现时以 W1 日志＋对话框截图共同定位。

### W1 诊断日志已实施（2026-10-26）

改动三文件，全部 INFO 级、无新配置开关、日志本体 `runCatching` 包裹不改语义：

1. `EmbeddedColumnNaming.kt`：新增 `NamingResolution(naming, source)` + `resolveDetailed()`，`resolve()` 改为委托（判定语义逐字不变）；source 标签＝`explicit`（设置页显式）/`probe-hit`（工程配置探测命中）/`probe-miss`（AUTO 回落默认）/`default-no-project`。
2. `JpaEntityResolver.kt`：`flattenEmbeddedColumns` 出口——展平成功打 INFO（`entity/field/naming=档/来源/sdPrefix/columns=[内层字段→列名,…]`）；specs 空降级单列升 **WARN**（`嵌入展平降级单列（类型不可解析/全被跳过）…`，即 §H2 的直接签名）。
3. `EntityResolver.kt`（MP 轨 D3）：`resolveEmbeddedColumns` 同构日志，命名档标 `NONE/mp-track`（MP 轨恒默认 NONE，防与 JPA 轨混淆）；降级同样 WARN。
4. `SqlPreviewServiceEnhancements.kt#resolveHqlTable`：三个解析出口各一条 INFO——歧义终态（带候选清单）、未解析（走 HBM 回退）、命中（class/url/columns/embedded 计数）。

日志统一前缀 `[T-EmbeddedIdentify]`，IDE 日志（Help → Show Log）可直接过滤。

验证：定向 6 类 **48/0/0/0**（`JpaEmbeddedColumnNamingTest`/`EntityResolverEmbeddedTest`/`JpaEntityResolverMappingDeepTest`/`HibernateNamingConfigProbeTest`/`SqlPreviewServiceJpaEmbeddedNestedPathTest`/`SqlPreviewServiceSdHemostasisTest`，BUILD SUCCESSFUL）；行为零改动的反向面＝`resolve()` 委托 `resolveDetailed().naming`（纯函数，既有 247 例命名/展平用例全绿即证）。🆕实施坑＝`EntityResolver`/`JpaEntityResolver` 均已有 companion object，新增 companion 报 Conflicting declarations——LOG 并入既有块即消。全量未跑（日志改动不在大改动判据内）。

### W2 第一项：三版本对比已做——复现链路逐字节一致（2026-10-26 用户指示「用 2026.10.19/16 版本对比」）

版本串全史无 2026.10.19；用户确认基线是 **v2026.10.16 发行版**。三方基线各跑同款探针（demo 仓 jpa-hibernate-demo 逐字形态）：

| 探针 | `2026.10.16` tag（`92232d23`） | 10-19 tip（`5846ac3c`，版本串 2026.10.16） | HEAD（`8267394d`） |
|---|---|---|---|
| SELECT 全列展开（type 条件） | `SELECT booking.id, …, booking.created_time …` | 逐字节相同 | 逐字节相同 |
| 复现 HQL（warehouseId） | len=253（与用户贴出的预览内容一致） | len=253 | len=253 |
| 派生查询 | len=157 | len=157 | len=157 |
| JPQL 投影 | `select b.created_time …` | 相同 | 相同 |

代码层面窗口内（`2026.10.16..HEAD`）复现链路零行为变更：gutter 目录、ArgResolver、HqlConverter/JpqlConverter/DerivedQueryConverter、`JpaMetadataReader`、`previewCustomDaoHql`、`resolveHqlTable`（除 W1 日志）全部无 diff；`JpaEntityResolver`/`EmbeddedColumnNaming` 的 diff 即 W1 本身；`HbmXmlEntityResolver` +61 仅命名查询消歧（不在该链）；`EntityClassResolver` +253 为仲裁加固（用户自己的复现会话 W1 日志证明命中正确类）。

**结论：以 `findBookingByValueBean` 的 HQL 预览为复现手段时，「问题」在 2026.10.16 与最新版同样（不）存在，SQL 渲染三版一致且与真库 schema 逐列相符（真表 `created_time` 列注释即「内嵌 updateInfo.createdTime」）。** 用户所观察的异常必然落在：①复现方法之外的面（参数面板交互/停靠预览/SQL 转 Java/补全/执行链——执行链正是本窗口大改区：方言放行/取源/参数历史回落 recallRecent）；②外部用户工程特有形态（§三 H1~H5）；③首次点击的 IndexNotReadyException 失败（IDE 启动 24 秒内点预览必失败报「未找到可转换的内容」，三版行为同源非本窗口回归，但用户可感）。**待用户提供「错误输出长什么样」（截图/文本或指认界面元素）后归类落修；在拿到前禁止预防性改动。**

### W2 待办

用带 W1 的构建在默认沙盒打开 demo → 触发 `findBookingByValueBean` 的 gutter 预览 → 对照 `[T-EmbeddedIdentify]` 日志与对话框（SQL＋参数面板）截图定位；结果回填 §六并归入 §三 H1~H6。

---

## 附录 A：生产管线探针（本轮已删，复用时按此重建）

夹具 = demo 仓 jpa-hibernate-demo 逐字形态（javax 短名导入 + Lombok + UpdateInfo 无内层 @Column）：
`WmsDockBooking implements Serializable`（id/relatedBillId/warehouseId/status/type/arrivalTime/initialETA/latestETA/ATD + `@Embedded private UpdateInfo updateInfo`），`@Data @Embeddable class UpdateInfo { private LocalDateTime createdTime; }`。

调用：`JpaEntityResolver().resolve(clazz)` → `HqlConverter.convertWithEntityResolver` / `PartTreeParser.parse + DerivedQueryConverter.convert + SqlGenerator.generate` / `JpqlConverter.convert`。

三条读数（master `048d4e03`）：

```
SELECT booking.id, booking.related_bill_id, booking.warehouse_id, booking.status, booking.type,
booking.arrival_time, booking.initial_eta, booking.latest_eta, booking.atd, booking.created_time
from wms_dock_booking booking where booking.type = ?                    （len=245）

SELECT id, related_bill_id, warehouse_id, status, type, arrival_time, initial_eta, latest_eta,
atd, created_time … WHERE created_time > ?                              （len=157，无伪列）

select b.created_time from wms_dock_booking b where b.warehouse_id = ?
order by b.created_time desc                                            （len=99）
```
