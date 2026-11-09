---
name: project-runtime-concat-delete-anchor-20261106
description: T-RuntimeConcatAndDeleteNull + T-JpaDeleteAnchor
  实施战役（20261106）——运行时拼接占位/三元/控制流、MP 空 Wrapper 写操作锚点、JPA delete/update
  锚点；三路并发合入单独集成分支 t-runtime-write-integration，master 只落两册计划文档（9390b461）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_a1b62012-6329-49f6-8f63-ac96cb7d21a6
---

2026-11-06 用户两条报障（运行期拼接的 SQL 预览出现 Java 源码与悬空 `*/`；`deviceInfoMapper.delete(null)` 无预览图标）+ 三点补充（同类审计 insert/update、占位注释形态、JPA `repository.delete` 另立项）+ 防重复图标提醒，形成两册计划并立即实施。

- 计划册：`docs/开发计划/20261106-1255-T-RuntimeConcatAndDeleteNull-…-开发计划.md`（R1 占位符 / R2 三元 / R3 控制流 / R4 写族 / R5 文档）、`20261106-1330-T-JpaDeleteAnchor-…-开发计划.md`。
- 工作流（用户令）：计划先提交 master（`9390b461`）→ 三条实施分支从 master 拉、各自工作树（`mpsv-t-concat`/`t-runtime-concat`、`mpsv-t-write`/`t-write-family`、`mpsv-t-jpa`/`t-jpa-delete`）→ 并发 3 路、只跑定向、不跑门禁 → **先合到单独集成分支 `t-runtime-write-integration`（工作树 `mpsv-t-integration`），不直接合 master**。
- 已合入集成分支：C（JPA）`0c26618b`+`b47a08d4`，定向 18+28 全绿；A（R1/R2/R3）`53de1ee4`+`2176902e`，定向 52 例全绿（15+10+27），用户样本产物已是 `WHERE ( /* 运行时值: 循环内追加，运行时重复执行 */ )` 与 `AND time >= '/* 运行时值: startTime */'`；合并提交 `008c5c9a`、`9b81f72a`。
- 后续（20261106）：三券全部合入并进 master，随 **v2026.11.6-beta.2** 发行（beta.1 不含本修复——判断用户截图是否旧版用 tag 祖先判定，见 [[pitfall-runtime-placeholder-must-be-one-comment]]「版本先验」）。
- 用户复盘时要求在 demo 里留**可复现用例**：已加 `mybatis-plus-demo/.../controller/RuntimeConcatReproController`（`listByLoopTernary`/`listByTernaryBranch`/`listByOptionalIf`），验收文档 B2-7 指到它；效果追问的三条优化（占位文案带模板摘要 / 引号内占位面板提示 / 符号展开示例）登记为 **T-RuntimeConcat-Polish** 候选，不阻塞发行。**口径可推广**：用户报障类修复默认配一份 demo 复现用例（类注释写「修复前/修复后预期 + 复现步骤」），尽量由单测把同一骨架逐字钉住，并在验收文档里指路。
- 已知偏差：A 的三元同文折叠判 `complete=true`（与「非直线一律 false」表述有细微差异）；C 的 R2 字面形态不可证伪、改用 R2b 补读数；P-3（`ht.update` 预览 SQL 形态）未跑。

## 续：T-RuntimeSlotFill（2026-11-06 立项，运行期占位可填值）

用户在同一主题上追加需求：「运行期 sql 还得优化，最好能让用户自己填参数然后 sql 动态变」，夹具就是本册留下的 demo `RuntimeConcatReproController` 三个方法。计划册 `docs/开发计划/20261106-2130-T-RuntimeSlotFill-…-开发计划.md`（本地提交 `17d94bb1`，按口径未单独推）。

- **为什么成本低**：三块基建已在——`ParamHint(quote/likePosition/inList)`、`ParameterReplacer`（`replaceNamedParams` 的 `'`→`''` 与数值启发式、`buildExecutableSql`）、面板 `paramValues` + `unresolvedParams`/`initialParamValues` 建行。
- **缺的是「占位的身份」**：`ConcatenationResult` 只有文本、占位不进 `unresolvedParams`、文本会重复、且四类语义（单值/循环/三元/可选 if）一个文本框表达不了。
- **关键设计**：身份**内嵌在注释文本**里（`/* 运行时值: … · rtN */`）而不是 char range——因为 `SqlFormatter` 的自述不变式是「只动空白与行尾注释」，格式化后位置必失效、token 文本不会；拼接器产出**结构化模板**（循环首项/其余两支、三元两支、可选片段）而非人读摘要；`replaceRuntimeSlots` 先槽后 `?`/`:name` 并记账已替换区间；**留空＝保留占位**（默认行为与 beta.2 逐字一致）。
- 拆 **券 A（模型+拼接器）→ B（回填引擎+三处链路）→ C（面板按 kind 建控件）** 串行；2026-11-06 已派券 A（分支 `t-runtime-slot-a`），要求先证伪 P-1（格式化器会不会改块注释内容）/P-3（循环模板能否稳定抽）/P-4（三元两支文本能否拿到）。

**Why**：`delete`/`update`/`remove` 既是 MP 写操作名，又是 Wrapper 工厂名/链终结点/服务链消费名，方法名级锚点稍不注意就会让同一语句出现两枚图标；`update(entity,null)` 复用 `modelBuild` 还会凭空多出 `WHERE id=?`（`BatchModel.UPDATE` 硬编码 WHERE）。
**How to apply**：后续加 MP 写操作锚点一律先做「空 Wrapper 精确形态 + `hasMpCrudReceiver` + `hasWrapperArgument` 让位」，测试用 `collectedAnchors()` 整文件集合断言；预览模板分支不复用带硬编码 WHERE 的 `BatchModel`。关联 [[pitfall-gutter-anchor-method-name-collision]]、[[pitfall-runtime-placeholder-must-be-one-comment]]、[[reference-mp-null-wrapper-write-sql]]。
