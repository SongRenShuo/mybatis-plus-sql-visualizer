# T-Perf33MergeAlign：弹窗解析链对齐 + 合流 t-perfsweep33 + 补完载入文件后台化 开发计划

- 立项时间：2026-11-20 12:44
- 任务源（用户原话）：「对齐 合流 补完 全部都做 先立项 再派多个子智能体后台并行实施提高效率 注意任务分配避免冲突」
- 前置结论（本日实测）：
  - `git merge-tree --write-tree master t-perfsweep33` → 冲突 2 处：`CHANGELOG.md`（append 撞车）、`src/main/kotlin/.../ui/SqlPreviewDialog.kt`（实质）
  - 分支守卫 `SqlPreviewDialogOffEdtResolveHotPathTest` 断言：`SqlPreviewDialog` 内 `StandaloneSqlSourceResolver.resolve(` **恰 1 处**、且必须位于 `private fun resolveOffEdt` 体内（调用行号 > 声明行号）、文件内必须有 `executeOnPooledThread`；另断言 `SqlPreviewPanelExecution` 的 EXPLAIN 前热调用带 `cacheOnly = true`
  - 主分支当前形态：`resolveForDisplay`（同步纯函数）+ `fillMissingFragment` 里自带的 `executeOnPooledThread` → **不满足**上述形状 ⇒ 直接合流守卫必红
- 状态：**已立项，实施中**

## 一、三件事的定义与验收

| 件 | 内容 | 验收 |
|---|---|---|
| **对齐** | master 的 `SqlPreviewDialog` 把「解析 → 应用」收成**单点** `private fun resolveOffEdt(...)`（池线程解析 + 代次守卫 + `invokeLater(nonModal)`），`reparseDraftFromEditor`（贴入识别）与 `fillMissingFragment`（补齐回填）两个入口共用 | ① `SqlPreviewDialogTest` / `DialogPasteReparseTest` / `SqlStandalonePreviewPanelDialogTest` 全绿；② 把分支守卫类临时拷进工作树跑 **券01 那条单测** 必须绿；③ 反向：把入口改回直调 → 守卫必红 |
| **合流** | `t-perfsweep33` → master（券01 弹窗链后台解析、券02 windowStamps 双帽、券03 EXPLAIN 前热 cacheOnly + 源码守卫 + 计划/CHANGELOG） | ① `CHANGELOG` 取并集；② `SqlPreviewDialog.kt` 取 master（对齐后的超集，含 `sourceText`/补齐）；③ 合流后跑分支那 8 类定向全绿，其中 `SqlPreviewDialogOffEdtResolveHotPathTest` 两条都要绿（**不改守卫**） |
| **补完** | 券01 的「载入文件改后台」重指到 master 的新入口：`MapperFileLoader` 只做「选文件」（EDT）+「读文本」（BGT 调用），`SqlStandalonePreviewPanel.LoadFileAction` 与 `MissingFragmentDialog.pickFromFile` 两处改为：先 `file.length` 预检超限 → 池线程读 → `invokeLater` 回填（带 disposed/代次守卫） | ① 载入大文件时 EDT 不再读盘；② 超限文件不写入输入区/不塞进补齐框；③ `SqlStandaloneLoadFileTest` 迁移到新注入缝后全绿，并补「超长不读取」用例 |

## 二、并行方案与文件所有权（避免冲突）

**主控**：立项/落账（`CHANGELOG.md`、本计划、`LESSONS.md`）、三次合流、合流后复跑、最终读数。
**两个子智能体各自独立 git worktree（互不共享工作树、互不共享 build 目录）**：

| 车道 | worktree / 分支 | 允许改的文件（白名单） | 禁止 |
|---|---|---|---|
| **A 对齐** | `.worktrees/wt-perf-align` / `t-perf-align` | `ui/SqlPreviewDialog.kt`；测试 `ui/SqlPreviewDialogTest.kt`、`ui/DialogPasteReparseTest.kt`、`ui/standalone/SqlStandalonePreviewPanelDialogTest.kt` | 不得改 `CHANGELOG.md`/`docs/**`/`LESSONS.md`；不得改 B 的白名单；不得改守卫（临时拷入的守卫文件**不得提交**） |
| **B 补完** | `.worktrees/wt-loadfile-bg` / `t-loadfile-bg` | `ui/standalone/MapperFileLoader.kt`、`ui/standalone/SqlStandalonePreviewPanel.kt`、`ui/standalone/MissingFragmentDialog.kt`；测试 `ui/standalone/SqlStandaloneLoadFileTest.kt`（可新增同族测试文件） | 不得改 `SqlPreviewDialog.kt`（A 的）、`CHANGELOG.md`/`docs/**`/`LESSONS.md` |

两条车道文件集**不相交**（A 只碰弹窗与其测试；B 只碰 MapperFileLoader 族与工作台载入），唯一共同依赖是只读的 `MapperFileLoader.loadText` 语义——B 保留其签名，A 不引用它。

**集成顺序（串行，主控做）**：A 合入 master → `t-perfsweep33` 合入 master（解 2 处冲突）→ B 合入 master → 落账与复跑。
为什么这个顺序：A 先落，合流时 `SqlPreviewDialog.kt` 的冲突解就是「取 master」；否则要先手工造出 A 的成果。

## 三、风险与前提（P-x）

- **P-1 磁盘**：`C:` 可用仅 146MB（`F:` 6.8G）。两条车道各跑 gradle 测试会写临时文件；两路并发的构建数=2（仓规上限，禁再加）。任一车道出现 `IOException: 磁盘空间不足` 一律**停止并如实上报**，禁止换目录重试掩盖。
- **P-2 守卫不改**：合流后若守卫红，正确处置是让**生产形态**满足守卫，而不是改断言（改断言=把防漂移哨兵变橡皮章）。唯一允许的改动是「守卫里对 `loadFileIntoPreview` 的措辞」——该函数已在 master 删除，守卫只断言其"不得直调"，不引用其存在性，故无需改。
- **P-3 券01 的另一半**：`b64e71e43` 的「载入文件改后台」原本改的是弹窗里的 `loadFileIntoPreview`；master 已把该入口迁到工作台，故合流后这半边由 **B 车道**在 `MapperFileLoader` + `LoadFileAction` 上重新落地，合流时该函数的删除与 B 的实现互不冲突（不同文件）。
- **P-4 测试缝迁移**：B 把 `SqlStandalonePreviewPanel.filePicker: (() -> LoadedFile?)?` 换成 `fileChooserForTest: (() -> VirtualFile?)?`（让异步读盘走真实路径），既有 4 条用例必须同步迁移，避免出现"缝还在、被测路径已被旁路"的假绿。
- **P-5 合流后 CHANGELOG 账**：分支已自带的券01/02/03 三条与 master 侧今日三条（含本轮的 `ed081cbdd`）并存，按「新功能→修复→优化」重排，一句一条带哈希；券01 那句要改成"工作台载入文件"口径。

## 四、验收清单（A-x）

- **A-1** 对齐后：`SqlPreviewDialog` 内 `StandaloneSqlSourceResolver.resolve(` 仅 1 处且在 `resolveOffEdt` 体内；贴入识别与补齐回填都走它。
- **A-2** 合流后：分支守卫两条子测试全绿（且守卫文件来自分支，未被修改——用 `git log -1 --format=%H -- <guard>` 核对来源）。
- **A-3** 合流后：`SqlLogEntryBuffer` 双帽（64/1024）与 `SqlPreviewPanelExecution` 的 `cacheOnly = true` 在 master 上生效；`NplusOneWindowBufferTest`/`SqlLogEntryBufferTest`/`ExecFileWriteCountTest`/`ExecTempFileEdtGuardTest` 全绿。
- **A-4** 补完后：工作台「从文件加载…」与补齐框「从文件选择…」均不再在 EDT 读盘；超 100k 文件在**读取前**按 `file.length` 拦下。
- **A-5** 合流后弹窗功能不退化：`sourceText` 补齐入口、`autoParseOnPaste` 贴入识别、底部四按钮计数均保持（`SqlPreviewDialogTest` 全量绿）。
- **A-6** 主控统一复跑：`SqlPreviewDialog*` + `SqlStandalone*` + 分支 8 类 + 快桶相关类 0 红；`grep "RED PROBE"` 为空。

## 五、实施记录

（待回填）
