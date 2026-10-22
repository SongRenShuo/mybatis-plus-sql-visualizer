# T-DmDialect 达梦 SQL 方言平台注册与预览执行 DM 路由闭合 开发计划

- **立项**：2026-10-14 07:54 立，承 O-136（T-PreviewExecDsDialect）达梦端到端的真机走查发现（同日，dev 沙盒 IU-2026.2.1）
- **定位**：O-136 交付后真机首测发现「达梦数据源在 262 真机上预览执行路由断在方言解析层」——本计划补齐该断点，使 O-136 的「按数据源方言出 Runner」对达梦真正兑现。写完停下等用户拍板（D1/D2），未获令不实施
- **证据链（20261014 实测，全部可复核）**：
  1. dev 沙盒 idea.log：`dialect pre-mapped to MySQL for mpsql-exec.sql (O-136 ds-dialect routing, ds=LocalDataSource)`——路由链跑了、目标数据源解析**非空**，方言解析落 MySQL；
  2. 262 平台方言注册表实测（`plugins/DatabaseTools/lib/database-plugin.jar` 的 plugin.xml）：`<sql.dialect dbms=…>` 共 **35 项（AZURE~VERTICA）无 DM**；`<dialect dbms=…>`（数据库方言）同样无 DM → `findSqlDialect` 必然不产 DM；
  3. `UrlSubprotocolDialects` 对 `jdbc:dm://` 的候选 [DM, Dameng] 均不在 262 `SqlDialectMappings.getAvailableValues` → URL 兜底落空；
  4. 按设计「不猜近似方言」诚实回落 MySQL → 执行绑定 mpj_demo（`[1045]` 为新沙盒未存密码，另行 Change Credentials 即可，非本任务面）。
- **已就位的验证环境（保留中）**：dev 沙盒（`build/MPSV-GifFresh`，262）；mpj_dm 驱动已注入（本机 .m2 `DmJdbcDriver18-8.1.3.140`）；达梦 `MPJ_DEMO.T_ORDER` 已建+5 行造数（dbx 达梦 20096 连接可复核，期望结果 user_id=2 / order_count=2 / total_amount=350）；last-used 记忆已可经 workspace.xml 预写。

## §一 方案

| 方案 | 内容 | 代价/收益 |
|---|---|---|
| **A（推荐）插件注册自有 DM SQL 方言** | 注册 `com.intellij.sql.sqlDialect` EP（DM 语言 + 方言实现，最小实现=继承 Generic 并挂 DM 关键字集）；`UrlSubprotocolDialects` 的 DM 候选即可命中 → O-136 全链打通 | **大改动判据命中（新增 EP）→ verifyGate 三目标 + 全量 testShards 必跑**；收益=一次投入激活平台方言消费方全家桶：预览执行 DM 路由、`#{}` 预览着色按 DM 关键字、T-DialectQuoteWarn 的 DM 引号档、控制台 DM 控制台体验 |
| B（轻量）不注册方言，插件侧旁路 | O-136 组展开已证 All 组含 mpj_dm（真机 dropdown 可见）；在 `chooseAndPrepare` 的 pick 段补「目标数据源方言与草稿不一致时，插件直连目标数据源构造 Runner」的旁路，绕开平台方言过滤 | 不动 EP/不动门禁面；但 DM 执行语义由插件自担（绕过平台方言一致性检查），且平台方言消费方（着色/告警）仍不认 DM |

**D1 决策点**：A or B。倾向 A——B 的旁路等于插件为 DM 单独背书执行语义，长期维护两套；A 把达梦升为一等方言。

**D2 决策点**（仅 A）：DM 方言实现深度——**最小**（Generic 继承 + DM 关键字集，无自定义 parser，倾向此）vs 完整（自定义 lexer/parser，量级数倍，本轮不做）。

## §二 待验证前提（动手前逐条证伪）

1. **EP 可注册性**：`com.intellij.sql.sqlDialect` EP 允许第三方插件注册（非平台保留）——最小 spike 验证：临时分支注册测试方言，重启后 `SqlDialectMappings.getAvailableValues` 含它即证。
2. **枚举打通**：注册 DM 方言后，`getAppropriateDataSourcesForExec`（草稿=DM 方言）能枚举出 mpj_dm 的 Runner（similarTo 链对自定义方言成立）——spike 内一并验证。
3. **O-136 候选命中**：注册后 `availableLanguageIds` 含 DM 方言 id → `UrlSubprotocolDialects` DM 候选改指真实 id。
4. **测试环境**：MPJ_DEMO.T_ORDER 与造数在位（dbx 可查；已建）。
5. **不影响既有**：新方言注册不改变既有 11 方言的识别与行为（既有守卫族全绿为准）。

## §三 影响面与门禁

- 新增：plugin.xml EP 注册一条 + DM 方言实现（语言/方言/关键字集）+ `UrlSubprotocolDialects` 候选修正 + 判别用例（方言注册可见性/枚举含 DM/O-136 全链）。
- 门禁：**大改动判据命中**——verifyGate 三目标（243/253/262）+ 全量 testShards（`--no-configuration-cache`）+ O-136 真机复测（本沙盒现成）。
- 实施分支：dev（O-136 同域）；真机验证环境沿用本次保留的沙盒与造数。

## §四 红线与纪律

承袭在册全部（dev 未经允许禁并 master、锚点核实先行、编号取号先查台账、先红后绿、大改动门禁）。本计划含 EP 新增，按「大改动判据」执行门禁与评审。

## §五 实施记录

- 20261014 07:54 主控立项：承当日真机走查发现（证据链见上）；写完停下等用户拍板 D1/D2，未获令不实施。
