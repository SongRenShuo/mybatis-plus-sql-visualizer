---
name: project-perfsweep33-tenth-perf-audit-20261120
description: 第十期性能分析 + T-PerfSweep33 三券已全链收口并**已于 2026-11-20 合入 master**
  （A 对齐 49058391c / perf33 53dc6ca40 / B 载入文件后台化 f20a79205→0b948e221 / 落账 c41ee8a78，
  13 类 130 例 0 红，守卫未改）；**已推双仓 c41ee8a78（用户「可以推 全量门禁交 CI」），
  CI 推送测试 run 44681024478 全绿 8/8（test×5 + fast×2 + nightly-nudge，13:26→13:36 一次通过无 rerun）**；
  hiddenbug 9 性能券待随 route 合流（2 裁决点）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_fe3357c5-8b9d-4dd6-87e4-a5ecb22264d5
---

第十期「IDEA 性能影响深度分析」（2026-11-20，`ec39a7518` 在 master）：报告 `docs/性能分析/20261120-0213-IDEA性能影响-性能分析.md` + 计划 `docs/开发计划/20261120-0220-T-PerfSweep33-*`。

- **销账兑现**：t-perfsweep31 五券 + PerfSweep32 两券已全在 master。F47/F48/F51 代码层均已修/收敛——引用前别再按「未修」立项。
- **T-PerfSweep33 全链收口（分支 `t-perfsweep33`，tip `01f94b679`，已推双仓，合 master 待用户令）**：券03 `ca1a38a3b` / 券01 `b64e71e43`（池线程 + 代次守卫 + 测试缝，删 suppressPasteReparse 与 reparseText 死代码）/ 券02 `7b318a5d1`（双帽 64/1024）/ 守卫 `54d1103f1` / 落账 `378c7fac2` / GUI 读数 `1bc05846d` / CI 终态 `01f94b679`。定向 8 类 102 例 0 红 + 反向探针恰 4 红复绿。
- **CI gate**（run 44592868282）：attempt1 test(4) 1 红＝SqlStandalonePreviewPanelDialogTest 未识别文本用例，与本分支改动面无机制交集 + beta.2 同类瞬态先例（L90）→ rerun-failed **attempt 2 全绿**，瞬态定性坐实。门禁终态 build+test×6+verifyplugin+verify×3+marketplace×3 全绿。
- **沙盒 GUI 验收零缺陷**（243 隔离沙盒 MPSV-PerfSweep33-UI，非提权合成输入，IDE 全屏+TOPMOST）：A1 70KB 歧义片段（MyBatis `<if>`×500+iBatis `<isNotNull>`×500）贴入识别完成 + 阳性对照 toast + 探针窗口期 0 freeze/0 ERROR；A2 贴入后 250ms 追加编辑不被冲掉（识别消耗最终文本 `FROM DUALX`）+「命中一次交还手动」GUI 实证；A3 从文件加载全链后台完成。A4/A5 测试收口。场景脚本 `record/probe_perf33_dialog{,2,3}.py` 留档。
- **t-hiddenbug-perf-scan**（tip deefbb038，45 笔 src/main 未合）：9 性能券 master 全未修；route 已完整包含（0 丢失）；冲突 6 文件含裁决点 W（建议 master 广播）/Y1（建议 master Task.Backgroundable）；分支自身零新性能面。

## 合流作业（2026-11-20 12:44 起，用户令「对齐 合流 补完 全部都做」——**已完成、已合 master、已推双仓**）

**结果**：master 现为 `c41ee8a78`（落账）→ `0b948e221`（B merge: 53dc6ca40+f20a79205）→ `53dc6ca40`（perf33 merge: 49058391c+01f94b679），A 车道 merge `49058391c`（提交 `516acc09d`）。
- 车道 A：`SqlPreviewDialog` 收敛到唯一出口 `resolveOffEdt(textSource, applyOnEdt)`（池线程 + `resolveGeneration` + `invokeLater(nonModal)`），贴入识别与补齐回填共用；`DialogPasteReparseTest` 两条改有界轮询。
- 合流解冲突（2 处）：`SqlPreviewDialog.kt` 取分支基底保住 `resolveOffEdt`/`resolveOffEdtDispatcher`/`deliverResolved`/`applyResolvedAsFresh`，再补回 master 的 `sourceText`+补齐链；**踩坑**：取 theirs 复活了分支仍保留的 `loadFileIntoPreview` 与已被删的 `dialog.preview.loadFile` 键 → 编译 `Unresolved reference`（见 LESSONS L92）；CHANGELOG 取并集、券01 措辞按实情改写。
- 车道 B：`MapperFileLoader.chooseFile`（EDT）+ `loadText`（BGT）；两处调用点三段化 + `file.length > 4×上限` 读前粗筛 + `loadFileGeneration`/`disposed` 守卫；测试缝 `fileChooserForTest`/`loadTextInvoker`，新增「读盘不在 EDT」「超长不读取」两例。
- **读数**：13 类 **130 例 0 红**（守卫 4 / DialogTest 16 / SizeTest 3 / PasteReparse 4 / SqlLogEntryBuffer 60 / NplusOneWindowBuffer 10 / ExecFileWriteCount 7 / ExecTempFileEdtGuard 2 / LoadFile 6 / ActionRow 4 / StandaloneDialog 6 / ExecuteAction 3 / MissingFragmentHint 5）；两条车道各自反向验证（A 形态 2 处调用判红、B 读盘回退 2 红）。
- 收尾：两条 worktree 已删除（分支保留）；历史曾因 amend 打歪（把 B merge 覆盖成 perf33 merge、留下不可编译的 aba75fb87）→ 已重建为上面这条干净链，树与原 tip 逐字节一致（`git diff` 空）。
- **推送（2026-11-20 13:26 前后，用户「可以推 全量门禁交 CI」）**：`powershell -NoProfile -ExecutionPolicy Bypass -File dev-support/push-all.ps1 -Branch master` → Gitee ✅ + GitHub ✅（回显 `GitHub:master 代理探测不通…走直连`，令牌通路 `x-access-token:***@`、无凭据弹窗）。推前 `git fetch` 实测 `origin/master...master = 0/20`（fast-forward，20 笔）。远端 tip 与本地一致 `c41ee8a781dd…`。CI 自动触发「推送测试」**run 44681024478**（master @ c41ee8a78），常态 10~12 分钟 / 14 job；红灯优先按 L90 做同 SHA 跨 attempt 对照（`rerun --failed`），不先动代码。

## 合流前的原始编排（留档）

计划 `docs/开发计划/20261120-1244-T-Perf33MergeAlign-*`（`9c9829694`）。三件事与落点：

1. **对齐**：master 的 `SqlPreviewDialog` 收成单点 `private fun resolveOffEdt`（贴入识别 `reparseDraftFromEditor` + 补齐回填 `fillMissingFragment` 共用；池线程 + 代次守卫 + `invokeLater(nonModal)`）。为什么必须先做：分支守卫断言「`StandaloneSqlSourceResolver.resolve(` 恰 1 处、且在 `resolveOffEdt` 体内（调用行 > 声明行）、文件内有 `executeOnPooledThread`」——不满足则合流后守卫必红，而**改守卫=把防漂移哨兵变橡皮章**（唯一正解是让生产形态满足它）。
2. **合流**：`git merge-tree --write-tree master t-perfsweep33` 预演＝**2 处冲突**：`CHANGELOG.md`（append 撞车，取并集）＋`ui/SqlPreviewDialog.kt`（实质；解＝取 master 的对齐版，分支那半边已被超集吸收）。券02（`SqlLogEntryBuffer` 帽 64/1024）、券03（`SqlPreviewPanelExecution` 加 `cacheOnly = true`）零冲突。
3. **补完**：券01 的「载入文件改后台」半边在 master 上**已无对象**（入口被 T-PreviewActionRow 迁到工作台），须重指到 `MapperFileLoader`（`chooseFile` 只选文件在 EDT / `loadText` 在 BGT / `file.length` 读前粗筛 / 回填回 EDT），否则等于删掉它的修复对象、把 EDT 读盘留在工作台。

**并行编排（文件集不相交，各一独立 worktree）**：车道 A `.worktrees/wt-perf-align`（`t-perf-align`，白名单＝`SqlPreviewDialog.kt` + 3 个弹窗测试类）；车道 B `.worktrees/wt-loadfile-bg`（`t-loadfile-bg`，白名单＝`MapperFileLoader`/`SqlStandalonePreviewPanel`/`MissingFragmentDialog` + `SqlStandaloneLoadFileTest`）。主控串行做集成：A → t-perfsweep33 → B，最后统一落账（`CHANGELOG`/计划/`LESSONS`，子代理禁碰 docs）。

合流后必跑的分支 8 类（master 侧均已存在，仅守卫类随合并新增）：`SqlPreviewDialogOffEdtResolveHotPathTest`(新) / `SqlPreviewDialogTest` / `SqlPreviewDialogSizeTest` / `DialogPasteReparseTest` / `NplusOneWindowBufferTest` / `SqlLogEntryBufferTest` / `ExecFileWriteCountTest` / `ExecTempFileEdtGuardTest`。

**Why:** hiddenbug 用户点名过；合流前发行版带着 9 项病态形态（历史文件加载 15~16s 大头）。

**How to apply:** 推送后盯 CI 的可复用形态＝写一份只读 watcher 脚本进 `build/`（gitignored）：令牌读 `.local/github-token.json`（禁 `git credential fill`）、`curl` 轮询 `actions/runs/<id>` 每 30s 打印 `status conclusion`、`completed*` 即跳出并打 `runs/<id>/jobs` 逐 job 结论；后台跑，不占前台。合流编排随 [[project-pending-plans]] 的 T-BranchMergeRoute 主通道走；沙盒验收三坑复用：①worktree 沙盒 layout 多一层插件名目录（sandboxContainer/<plugin>/<version>），config 播种别错位；②本会话 gradle 起的沙盒 IDE 非提权，非提权合成输入可用（`-Verb RunAs` 被 ZCode bash 拦，127）；③弹窗类 CI 瞬态红先 rerun-failed 对照再查代码（L90）。
