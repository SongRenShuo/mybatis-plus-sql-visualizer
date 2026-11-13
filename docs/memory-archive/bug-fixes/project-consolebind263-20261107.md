---
name: project-consolebind263-20261107
description: IDE 2026.3 删了 DataSourcePerFileMappingsImpl.setMapping(VirtualFile,
  LocalDataSource)，致预览换源退化为单源；已修并合入 master（4ef1fe9c），真机验收待用户
metadata:
  node_type: memory
  type: project
  originSessionId: sess_fe142790-fdcd-414a-8e54-f5d6baf781ae
---

2026-11-07 用户在日常 IDEA 2026.3（`IU-263.7307.200`）报「SQL 预览换源失效：列表只剩当前一个、点选没反应」。根因是**平台 API 移除**，不是插件回退：263 删掉了 `com.intellij.database.dataSource.DataSourcePerFileMappingsImpl.setMapping(VirtualFile, LocalDataSource)`，`ExecFileBinding.realignExecBind` 的反射恒抛 `NoSuchMethodException` → `detachConsole` 成功但 per-file 关联没清 → `getForcedDataSourceForExec` 把 runner 枚举短路成 1 条 → 弹窗只有当前源、点选重建同一方言控制台；另一张脸更危险：工具栏挑 B 源时 `preferredRunnerName` 在单 runner 集合里未命中，链路落 `single runner, run directly`，**静默建在 A 源上**。

263 替代面（本机 jar 字节码实测）：接口 `com.intellij.database.console.DataSourcePerFileMappings` 收窄成 `setMappingWithWriteAction(VirtualFile @NotNull, LocalDataSource @Nullable, Runnable @Nullable)`；impl 另有 `setMapping(VirtualFile, String @Nullable)`（按 id 写/null 清）与静态 `setDataSourcePerFileMapping(Project, VirtualFile, LocalDataSource)`。同版本 `DatabaseRunners.getSwitchDataSourceRunners`、`DbImplUtil.getAllDataSourcesForExec/getForcedDataSourceForExec`、`JdbcConsoleProvider.detachConsole`、`DataSourceStorage`、`LocalDataSource.getName/getIcon`、`AbstractDataSource.getUniqueId` 全部未变。

**Why**：这是「262 反编译实证」式注释的典型失效——依赖的私有实现重载会被大版本直接下架，而单测里全走 `runCatching` 退化分支、CI 只验能装能起，真机点击前不会有红。

**How to apply**：写平台反射时按「新→旧→只写」多策略探测并**写后回读验证**，日志必须带策略名；任何「偏好源未命中」不得静默改用被钉住的源。计划文件 `docs/开发计划/20261107-1645-T-ConsoleBind263-IDE2026.3移除setMapping致换源退化为单源-开发计划.md`（待拍板）。相关：[[pitfall-datasource-only-one-source-forced]]（2026-10-24 复盘的那张脸）、[[project-dev-vs-master-262-acceptance-20261104]]。

## 20261107 16:45 起的推进状态（后续轮次接手先读这段）

- **已实施并合入 master（2026-11-07 17:40 收口）**：实现提交 `bd4eff85`（策略链+回读+阻断）、`40b72e8d`（计划/CHANGELOG）、`151573f2`（P1-5 提示条件收紧为 `mapped==false`）、`848b7292`（补记），合并提交 `4ef1fe9c`；分支 `t-consolebind263` 与工作树 `.worktrees/consolebind263` 已删除，并行登记已注销。
- **合并态复跑（主仓，非代理工作树）**：`ExecFileBindingTest` 19 / `ExecFileBindingStrategyTest` 7 / `DataSourceSelectorPreferredMissTest` 6 / `PlatformMappingContractTest` 1 / `DataSourceSelectorTest` 2 → **35 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESSFUL**。`PlatformMappingContractTest` 在本机是**环境受限未验证**（测试平台无 Database 类），不算契约通过。
- **V1 被推翻**：263 的 `setMappingWithWriteAction` 是 `launch(EDT)+edtWriteAction` 的异步协程，调用返回时写还没落地 → 判定改为写后回读，S3 `setMapping(VirtualFile,String)` 同步兜底。
- **未提交（主仓工作树，等用户点名）**：`docs/LESSONS.md`（L20）、`docs/索引/开发计划.md`（新计划行）、计划文件的合并态状态行。
- **仍待办**：真机（日常 IDE 2026.3）按计划 §6.3 五步验收；P2-6 反射点专项体检未立项。
- 派活时对「所有内容」的收窄（当时口径）：P0-1/P0-2/P0-3/P1-4/P1-5 全做，P2 只做核查登记。
- **顺手处理的日志刷屏已结案并写进仓内 `docs/LESSONS.md` 的 L20**（主仓工作树未提交）：根因是 16:15 那次「就地换 jar」的旧 classloader 残留，处置＝重启 IDE，且不影响 263 换源的结论。详见 [[pitfall-inplace-plugin-update-classloader-residue]]。
