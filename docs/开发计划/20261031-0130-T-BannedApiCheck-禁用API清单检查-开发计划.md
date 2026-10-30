# T-BannedApiCheck —— 禁用 API 清单检查（FR-0045 第 2 项）

- 任务编号：T-BannedApiCheck（FR-0045 第 2 项；主册 `20261031-0010-T-Fr45NonMybatisStack-…` §2.2 裁决、§8.4 实施台账）
- 立项日期：2026-10-31（**拍板后径直实施，本册为事后按惯例补立的独立册**，不改变主册裁决）
- 需求来源：官网需求池 FR-0045——「团队有硬规则『批量写方法一律不用』（batchAdd/batchEdit/removeBatch/deleteBatch 等），存量 258 处靠人记 + 评审时 grep；平台封装的『全量查』方法全项目约 3845 处调用」
- 分支：`t-fr45-bannedapi`（基线 `4cd4ac35`），已并入 `t-fr45-integration`
- 性质：新能力面。插件**首个** `LocalInspectionTool`

## 一、为什么单独立册

主册 §2.2 的裁决是「真缺口·新能力面·**待拍板**」，拍板点两条：①产品方向——插件能力面是否从「SQL 可视化」扩展到「团队规范检查」；②`plugin.xml` 里那条「不注册任何 inspection/annotator（红线：不做校验器形态）」的表述须随立项修订。

用户令「两个拍板的可以做」后径直实施，判定落点如下（**这是本册要留的核心决策记录**）：

- **方向**：接受。禁用 API 检查与插件既有能力同属「在编辑器里就地给出证据」，不引入新的运行时责任（只读 PSI）。
- **红线处置**：原注释是 `psi.referenceContributor`（XML 引用）那一段的约束，字面写着「不注册任何 inspection/annotator」。处置为**收窄作用域、保留原意**——改为「**XML 侧**不注册任何 inspection/annotator（红线：mapper XML 不做校验器形态）」，并加一段勘误说明 Java 侧 inspection 是独立能力面、与本贡献器「只产 soft 引用」互不相干。未删红线、未动 `psi.referenceContributor` 注册本身。

## 二、方案券

| 券 | 内容 |
|---|---|
| S1 规则模型与存储 | `inspection/BannedApiRule`（类匹配串 + `classRegex` 模式标记 + 方法名正则 + 说明 + 级别 + createdAt）；`BannedApiRuleManager` 照抄 `SqlSuppressRuleManager` 模板：App 级 `@Service` + 独立 storage `mp-sql-visualizer-banned-api.xml` + `RoamingType.DISABLED`，上限 200 条，`@Volatile compiledRules` 整体替换后发通知 |
| S2 设置页子页 | `config/BannedApiConfigurable`：左 `JBList` + 右 `FormBuilder`（照 `SqlSuppressManagerDialog` 形态），关闭即生效（`isModified/apply` 空实现，单源=manager）；注册进 `PluginSettingsFolderConfigurable` 的 `SubPageRef` 清单与 plugin.xml `applicationConfigurable`（`groupWeight=12`） |
| S3 检查器 | `inspection/BannedApiInspection` 薄壳 + `BannedApiMatcher` 纯逻辑核心（预编译正则、首条命中即返回、非法正则静默失效、`mayMatchMethodName` 廉价短路）；空规则 → `EMPTY_VISITOR`（零 daemon 行为）；逐规则 `ERROR/WARNING` 而非全局 level |
| S4 存量统计 | `actions/ScanBannedApiUsagesAction`（Tools 菜单）+ `BannedApiProjectScanner`（`FileTypeIndex` 只读扫描）+ `BannedApiScanReport`（明细上限 500 条）；`DumbService.isDumb` 期间不跑、后台线程 + `runReadAction`、异常先 `FatalErrors.isFatal` 透传再降级 |

## 三、设计决定

- **匹配语义**：只按方法**声明类**的 `qualifiedName` 判定，不做父类/接口回溯。理由：回溯会让「某处调用命中」变得难以解释，团队规则希望写在哪就是哪；覆写场景写子类即可。已写进 KDoc。
- **只覆盖 Java 调用点**（`language="JAVA"`）。Kotlin 面登记为增量，未实施。
- **级别表达**：EP 的 `level` 只是默认严重度，逐规则级别走 `ProblemHighlightType`（`GENERIC_ERROR_OR_WARNING` 会被 profile level 覆盖，规则级检查不能用它）。
- **反馈通道零模态**：统计动作的五条反馈全部走独立通知组 `cn.song.mybatisplus.visualizer.inspection`（一域一组），报告落盘失败降级为 `LightVirtualFile` 在编辑器打开。这是收口门禁判红后按弹窗克制红线整改的结果，详见主册 §8.5。

## 四、验收与读数

- 定向 **9 类 / 68 例 / 0 失败**（Matcher 9 / RuleManager 11 / ScanReport 4 / Inspection 5 / ProjectScanner 2 / RuleSerialization 3 / Configurable 1 / SettingsFolder 11 / XmlStructureGuard 22）
- 反向验证三次：摘方法名分支 → Matcher 2 红；摘 `registerProblem` → Inspection 4 红（唯「无规则零行为」仍绿，符合语义）；注释掉 `<localInspection>` → 注册守卫 1 红；均还原复绿
- 触链 `testFast` 4344/0/7；合并后全量 `testShards` 8592/0/0/9（含本券）；`verifyGate` 3/3 Compatible
- **未闭环**：`runIde` 真机（设置页观感、标红/黄实际效果、`@SuppressWarnings("BannedApi")` 抑制、统计动作手动执行）；`localInspection` 分组显示名走 IDE 语言，中文 IDE 未真机确认

## 五、风险与边界

- inspection 是插件首例：EDT read access / SlowOperations / DumbService 纪律按平台踩坑清单走，`BannedApiInspection` 只做 PSI 取数 + report，匹配核心在纯逻辑层。
- 规则上限 200（防误配海量规则拖慢 daemon）；`compiledRules` 在 UI 线程整体替换，daemon 侧读同一 `@Volatile` 引用。
- 文档站对应节：`guide/configuration.md` 的「禁用 API」（中英），见兄弟仓 `289a5f1`。

**实施台账**：主册 §8.4（改动清单 file:line、三次反向验证、i18n 57 键、存储键名、未闭环项）。
