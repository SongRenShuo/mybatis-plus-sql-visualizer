---
name: project-merge-master-to-dev-round3
description: T-MergeMasterToDev3（2026-10-12）master 82 笔并入 dev——T-M53-II 巨型文件拆分与
  dev 8 特性移植的实录与读数
metadata:
  node_type: memory
  type: project
  originSessionId: sess_2b5062e9-21ee-418e-83b4-1ca64f07a665
---

2026-10-12 用户令「再次将主分支合并到 dev，testShards 通过即可，verifyGate 这次不用跑」。master 自
v2026.10.13 后又推进 **82 笔**，最大宗是 **T-M53-II 巨型文件拆分**（SqlPreviewPanel 5893→1323 拆入 6 个
同包协作文件 `SqlPreviewPanel{Execution,ParamPanel,ResultView,SqlView,Text,EditorLifecycle}.kt`、
DataSourceSelector、SqlPreviewService(+Enhancements)、PluginSettingsPanel(+Sections)、SqlLogPanel(+Actions)、
HistorySqlLogPanel、AgentManager(+AgentSessionSupport)、LogEntryRendering/LogEntryFiltering 等），
正好撞上 dev 改过的同一批文件 ⇒ 13 文件冲突（文档 6 / 代码 4 / 测试 3）。

**核心工作量＝dev 特性的逐处移植**（master 只搬代码不改行为，故 dev 特性必须落到 master 的新文件里）：
8 个特性 93 行 / 26 hunk——R36 回放脚本导出、R37 AI 上下文、R42 DML 影响面预演、R46 执行前 Schema 校验、
R49 Wrapper 布尔探镜、R51 参数快照回落与回写、R52 方言能力矩阵 tooltip、R61 EXPLAIN 跨次对比；
外加 R60 displayMask 复选框（→ PluginSettingsPanelSections.kt）、R53 暂停三处（→ AgentSessionSupport.kt）、
FLUSH_SYNC_MAX 常量（→ LogEntryFiltering.kt）、R49 两个测试（→ SqlPreviewPanelDynamicBatchTest.kt）。

**移植期的系统性坑（编译门 457 错 → 0）**：① master 侧冲突行与自动合并副本并存 ⇒ 重复声明（countQueryNotes/
detectMainAlias/isNumericParam/bindStateNameProvider/checkBoxFont/lastStructuredExplain）；② dev 的成员函数
搬进 master 的扩展函数文件后缺 `SqlPreviewPanel.` 接收者；③ 跨文件可见性（字段/常量 private→internal、
companion 常量需 `import ...Companion.X`）；④ R51 测试缝从顶层属性移回面板成员位；⑤ sed 按行号在位移文件上
删除会错位（改用模式锚点脚本 `ins2.sh`/`repl2.sh`：锚点文件传参避开 CJK 命令行）。

**门禁读数**：编译门（主+测试+agent）exit 0；定向 57 例 0 失败；**权威 testShards 6989-0-0-8**；
verifyGate 本轮未跑（用户令）。首轮 testShards 4 红全处置（见 [[pitfall-refactor-move-missing-line-check]]）。
提交链：`2224d44`（合并）→ `383391f`（.gitattributes 标记清理）→ `c2abeed`（4 红修复）→ `1157ca8`（master
再 2 笔 docs/record，0 src）→ `e7f6947`（变更记录）。**未 push**（dev 领先 origin/dev 若干笔，待用户令）。
关联 [[project-pending-plans]]、[[project-line-ending-policy]]。
