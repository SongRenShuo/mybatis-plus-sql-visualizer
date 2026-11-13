---
name: project-ibatis2-gap-fix-round
description: T-Ibatis2Gap 券 A/B/C/D 已实施；beta.1~beta.3 三轮真机缺陷全部修完并发行；2026-11-10 以纯数字正式版 2026.11.10 转正；沙盒验收 A1~A3 通过、A4 读数作废、A5~A7 交回用户；**已知问题 selectByCondition 勾选联动已于 2026-11-11 由 e1cf3872（T-IbatisSelectAll）修复，随 2026.11.11 重发版发行**
metadata:
  node_type: memory
  type: project
  originSessionId: sess_c0d561d8-362b-43ca-913b-a6bfb07cc567
---

# T-Ibatis2Gap：交互补全已发行，发布后真机四项缺陷在修

## 已落地并发布（2026-11-10）

三车道并行 → 主控合并（`00fd57d4` 券B / `84efd117` 券A）→ 沙盒 GUI 验收 → 随 `v2026.11.10-beta.1` 发布（见 [[project-release-20261110-beta1]]）。

| 券 | 内容 | 关键提交 |
|---|---|---|
| B | sqlMap 语句行 ↔ Java DAO 调用行**双向跳转箭头**（点击期词索引解析，热路径零查询，`IbatisGutterHotPathTest` 扩面 7→9） | `d04033aa` `896a1b4d` |
| A | 预览**参数可交互**：`IbatisParamModel` 行清单（占位/条件/iterate/dollar）+ Map 装配（勾选=放 key）+ 数值比较修正 + `PreviewResult.rerender` 回调接缝（`PreviewRerenderInput`，MyBatis 链零感知） | `92c515ec` `367561f3` |
| C | `parameterMap` 语句参数名**按位对齐**（string-aware 位点计数取前 N，剩余进 `unusedDeclaredParams`） | `f2ae5fbb` |
| D | 降级**结构化**（`degraded`/`unrenderedNodes`/`IbatisRenderFailureKind`）+ 文案分档 + 横幅跟随重渲染（UI-001） | `f2ae5fbb` `367561f3` |

**主控拦下的原实现缺陷（重要）**：L1 起初让 iBatis 箭头共用 MyBatis 的 `mapperNavYieldToCodeHelper` 让位门。用户日常 IDE 装着 MyBatisCodeHelperPro 3.6.8，而**竞品无 iBatis 能力**（发行包解包扫描 iBatis 命中 0）——共用让位门等于把箭头白藏掉。已改为**让位门解耦**，并配「竞品在位 → iBatis 箭头仍产出 / MyBatis 对仍让位」的钉死用例 + 反向探针（恢复让位恰 1 红）。

**合并态读不出来的一条真红**：`BannedApiScanRuleTest` 抓 L2 新增 `catch (Throwable)` 未判 fatal（`SqlMapSqlRenderer` 逐节点降级处）→ `b3958e35` 加 `if (FatalErrors.isFatal(e)) throw e`。合并态定向 435 类 4461 例、唯一这一红。

**L3 实现期自查修掉两个真缺口**：①条件未勾时同名占位符的值仍进 Map（改为条件行「接管」属性，取消=模拟属性不存在）；②`$x$` 站点落在条件子树内时无参基线渲染不进去、`dollarRawCount=0` 漏注入闸（用行清单站点补置闸）。

## 发布后真机四项缺陷（2026-11-10，用户 2026.3）

本地探针 `IbatisGapProbeTest`（临时文件，已定性后由 F1 转正/删除）拿读数：

| # | 用户现象 | 本地 | 定性 |
|---|---|---|---|
| ① | `selectByCondition` 勾四条件+填值后 SQL 保持在无参基线 | **不可复现**（本地 rerender 正常出 `WHERE ID = ? AND USER_NAME LIKE '%111%' AND STATUS = ?`；`age=1` 不满足 `>18` 被跳过属正确语义） | 263 上 rerender 抛异常被面板吞掉 → 修法＝**失败可见化**（面板显示「重渲染失败：<异常>」+ idea.log 带栈 warn），下次复现即成取证 |
| ② | `insertUser` 参数不回显 | 截图里参数框是**空的**（未填值），`VALUES (?,?,?,?)` 是静态语句正确渲染 | 待填值后确认；若仍不内联由 ① 的可见化暴露 |
| ③ | 两条查询只显示一条（selectKey 丢失） | **已复现**：`sql 不含 SEQ_USER_ID`、result 无 selectKey 字段 | 渲染器有 `renderSelectKey`（保真 4/4）但服务未调用 → 修法＝组装展示，但**主语句必须恒在最前**（顺序会改执行链判型，见 [[pitfall-multi-statement-preview-first-keyword]]） |
| ④ | SQL 里 `# username #` 被拆散 | **已复现**：`format 后含 '#username#'=false`、`含 '# username #'=true` | `SqlFormatter` 不认 iBatis `#x#` 原子 token → 三条显示路径（format/keyword-wrap/vertical/压缩）同源修 |
| ⑤ | parameterMap 的 ℹ 说明混进红色 ⚠ 行 | 截图可见 | 信息级只进 ℹ 行，真失败（方言未验证）留 ⚠ |

**探针手法**：Gradle 下 `println` 既不进控制台日志也不进 JUnit XML 的 `system-out`——把输出**写文件**（`build/probe-out.txt`）再读，最省事。

## 明确不是缺陷（引擎忠实行为，勿"修好"）

- **`ANDUSER_NAME` 粘连**：iBatis `prepend` 逐字拼接不补空格，真实项目必须写 `prepend="AND "`；demo 保留原样作保真样本，另加 `selectPrependWithSpace` 作可执行对照。用户问「直接执行不报错吗」的答案：**会报语法错**。
- **`#username#` 在 parameterMap 语句里保留原文**（引擎不转 `?`，真正生效的是手写 `?`）。
- 数值/字典序：`compareValue=18` 与值 9 按数值比较已在券 A 修好（修前 `"9">"18"` 误判成立）。

## 修复车道 F1 已交付（2026-11-10，分支 `t-ibatis-fix2`）

| 提交 | 内容 |
|---|---|
| `35f0eae4` | ④ `SqlFormatter` 占位符原子化——`#x#`（含三段形态）在 format / keyword-wrap / vertical 三路同源；**MyBatis `#{x}`/`${}` 同病同修**（此前也拆成 `# { x }`）；未闭合残段不合并 |
| `08f0aefe` | ③ selectKey 展示 + ① rerender 失败可见化 + ⑤ 告警分档 |
| `9e78abfa` / `792de9b7` | 4 条反证补丁 + README 登记 |

**读数**：`IbatisXmlPreviewServiceTest` 43 例、四个 SqlFormatter 测试 80 例、`xml.ibatis.*` + `SqlMapFidelityTest` → 合计 **144 例 0 失败 0 错 0 skip**，**保真 20/20 未动**；4 条反向验证摘除分别 10 红 / 5 红 / 1 红 / 1 红（非钝刀）。

**主控拦下的第二条原实现缺陷（安全面）**：F1 起初把 pre 的 selectKey 块拼在**主语句之前**——`SqlTypeClassifier.getFirstKeyword` 取首条语句、`ExecutionScriptScope.shouldExecWholeScript` 在多语句时只跑「当前语句」，于是带上键的 INSERT 被**判成 QUERY（只读）**、写安全闸放过它。已要求改成**主语句恒在最前**、selectKey 块在后并用注释标明 pre 的真实时序，配「预览 SQL 必须以 `INSERT` 开头」的钉死用例 + 反向验证。详见 [[pitfall-multi-statement-preview-first-keyword]]。

**F1 额度中断的处置（可复用）**：代理报 `exceed quota limit` 时，工作树里 264 行改动 **0 提交**。主控先审计（`git status` + `compileKotlin compileTestKotlin` → BUILD SUCCESSFUL、无「先红」标记），再 `SendMessage` 就地恢复（不新派代理），恢复时把未提交清单写进消息里让它接着做。

## 第二轮真机四条缺陷（2026-11-10，用户在 `ibatis2-demo` 上验收 beta.1）

| # | 现象 | 根因（主控定位） |
|---|---|---|
| 1 | Java DAO 每个调用行**两枚图标** | `JdbcTemplateMethodDetector` 方法名白名单与 `SqlMapExecutor` 入口名完全重叠、纯方法名不查接收者 → 两通道同认领。详见 [[pitfall-ibatis-dao-call-double-icon-and-direct-call-green]] |
| 2 | **Ctrl+Click 不跳**（gutter 箭头却可点） | handler 已注册、15 例单测绿，但那是**直调**；真实 Ctrl+Click 走 `GotoDeclarationAction`，尚有未定因数（多候选/他方 handler 短路）。要求改走平台真实入口复现并定位 |
| 3 | `#email:VARCHAR:0#` 填 `11212` 渲染成 `EMAIL = 11212`（**无引号**） | jdbcType 未贯通到回填的引号判定（`#age:INTEGER:0#` 有「数字，如 42」提示说明类型已到面板一部分，断在 quote 那条路） |
| 4 | 四条件全勾、参数全填，SQL 仍是零 WHERE 基线 | 主控此前 probe1 显示**服务层 rerender 正常**，故根因大概率在**面板侧装配**（勾选态或输入值未装进 `PreviewRerenderInput`）；分层定位后再修 |

**派活纪律（本轮新增硬约束）**：凡「用户看到的行为」，断言必须走**平台真实入口**；直调内部函数绿不算证据（第 2/4 条就是直调绿、真机红）。车道 F2（`t-ibatis-fix3`）在途。

## v2026.11.10-beta.2 状态（已推、门禁在跑、被四条新缺陷卡住）

版本号按用户口径取 **`2026-11-10-beta.2`**（沿用同日线，见 [[feedback-beta-same-day-version-numbering]]）。已提交 `5a752688`：版本号 / changeNotes 新块（4 条）/ CHANGELOG / `preview.update.json` / 发行说明。**两条 changeNotes 机器判据在打 tag 之前就 PASS**（`ChangeNotesRatingLineGuardTest` 2/0 + `inspect-plugin-zip` PASS，本版块零全角）——L25 本轮未重演。

**已执行**：`7611dca6` 推双仓 → 合并态定向 **165 类 1398 例 0/0/0** → 推送轮 run 42737409387（基线 `5a752688`）**7/7 绿** → 门禁 gate run 42738888232 在跑。

**发布节奏决定（主控建议，等用户确认）**：四条新缺陷归入下一版；`beta.2` 只要门禁+沙盒绿就发，让用户先验上一批四项。若用户要求一次发完，则压住 `beta.2` 等 F2 合并后一起发。

待 F2 落地后：合并 → 合并态定向 → 双仓推送 → `mode=gate` 15 腿 → `mode=release` 17 腿 → `gitee-release.py --from-github --yes` + `--verify-only --prerelease` 回读。

相关：[[project-release-20261110-beta1]]、[[project-ibatis2-demo-real-exec]]、[[pitfall-github-release-bytag-404-after-tag-move]]、[[pitfall-multi-statement-preview-first-keyword]]。

## beta.2 / beta.3 均已发行（2026-11-10）

- `beta.2`：GitHub Release **439883402** + Gitee **1292702**（tag @ `7611dca6`，附件 7,857,579 字节，`--verify-only --prerelease` [3/3]）。
- `beta.3`：GitHub Release **439939643** + Gitee **1293016**（tag `2026.11.10-beta.3` @ `843b615f`，双仓 ls-remote 一致；附件 7,868,431 字节、sha256 `1e78afbc06c07d00…`；gate run `42750544594` @ `582c7c1e` 15/15、release run `42752602048` @ `843b615f` 17/17）。
- 用户真机第三轮两条缺陷已修并入 beta.3（`63853e5d`）：①XML→DAO 的 Ctrl+Click（原先 XML 侧根本没接 GTD）②`UserDaoImpl` 方法定义行的预览图标（来源是 `StringSqlMethodGutterLineMarkerProvider`，不是 JdbcTemplate 通道）。细节见 [[pitfall-ibatis-dao-call-double-icon-and-direct-call-green]] 第三节、账本 `docs/LESSONS.md` **L26**。
- 定向 5 类 39 例 0/0/0；反向验证同时摘两处修复 → 恰 2 例红，完整还原后回绿。
- 发版前踩点：`MPV-Build` 是**每次构建唯一的毫秒时间戳**（`build.gradle.kts:273`），本地 zip 哈希必与 CI 包不同 ⇒ 跑 `gitee-release.py --from-github` 前先把 `build/distributions/` 里的同名旧包挪走，避免 sha256 比对炸。
- `record/` 清理（`34e0d70a`）：一次性场景脚本 22 个删除、5 个可复用件入库（`_run_elev_fwd.bat` / `f1_gui.py` / `scene_trust_project.py` / `scene_imgR_exit.py` / `scene_imgR_enumwins.py`）。判据＝参数化 + 有 usage 契约才留；写死绝对坐标/会话 PID 的一次性探针一律清掉（`scene_fill_params` 写死 `638,579` 这类）。

## 沙盒验收（2026-11-10 子代理跑，用户中途接手）

| 项 | 结论 | 证据要点 |
|---|---|---|
| A1 方法定义行无预览图标 / 调用行单箭头 | **通过** | 对 gutter 列做品牌色逐行聚类：定义行与调用行各恰 1 枚（定义行是平台「Implements method」图标），**紫色像素 0**（= 零预览图标），无任何一行两枚 |
| A2 XML 语句 `id` → DAO 字面量 | **通过** | 光标放进 `id="selectById"` 按 Ctrl+B → 切到 UserDaoImpl 第 32 行 `"User.selectById"` |
| A3 DAO 字面量 → XML 语句 | **通过** | 第 32 行 Ctrl+B → 回 User.xml，id 值被选中 |
| A4 语句行预览出 SQL | **失败但读数作废** | 预览窗 ≤5 s 打开，内容却是 `XML 解析失败：前言中不允许有内容` —— 子代理自己把 buffer 弄脏（见 [[pitfall-buffer-ghost-document-fakes-xml-parse-error]]），磁盘文件完好 |
| A5/A6/A7（selectKey 展示 / VARCHAR 引号 / 勾选联动） | 未执行 | 用户叫停验收、改由用户自己接手 |

**产品缺陷：无**。两个真问题都在探针/环境侧（脏 buffer、裸 Alt 开 Alt+Space 吞按键），后者已修进入库驱动 `1d35f7ca`。

## 正式版 2026.11.10（同日内测线转正，2026-11-10）

用户令：「先发行今天的正式版，目前就差 selectByCondition 勾选联动，下个版本修复」。

- 版本号 `2026.11.10`（同基号纯数字 > `-beta.N`，内测用户能收到更新）；tag `2026.11.10` @ `8561fafc`，双仓 ls-remote 一致（对象 `5f2cb44d`）。
- 打 tag 前判据：`ChangeNotesRatingLineGuardTest` 2/2 + `inspect-plugin-zip.py --version 2026.11.10` PASS（首块 `v2026.11.10`、8 条、零全角）。
- release run `42758016138` @ `8561fafc` 已派发（**交接时会话结束时仍在跑，落位读数未回**）；Gitee 正式版发行版与 `--verify-only` 回读待做；Marketplace 上架与 `update/update.json` 提升仍归用户。
- 转正的表达层清理与全角坑见 [[feedback-beta-same-day-version-numbering]]。

**已知问题（2026-11-11 已修）**：iBatis 预览里 `selectByCondition` 这类多条件语句的**勾选联动**——已由 `e1cf3872`（T-IbatisSelectAll）修复：根因＝setAllConditions/clearConditionValues 两条批量路径只枚举 Wrapper 与 MyBatis XML 两份条件视图清单，iBatis 条件行持有在 `PreviewResult.ibatisParamModel` 枚举不到；修法＝两条路径一并枚举 ibatisParamModel.conditionRows（清除同时清参数值与可见字段文本）。定向 `SqlPreviewPanelDynamicBatchTest` +2、反向摘 ibatisRows 枚举转红、恢复 23/0。随 v2026.11.11 同版重发发行（见 [[project-release-20261111-formal]]）。


