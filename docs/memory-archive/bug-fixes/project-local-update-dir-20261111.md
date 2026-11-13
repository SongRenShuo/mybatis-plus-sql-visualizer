---
name: project-local-update-dir-20261111
description: T-LocalUpdateDir（20261112
  已实施 + 已合 master `bf258ec02` 并推双仓，工作树/分支已清理）——关于页新增「本地安装包目录」，自动检测最新版并免重启安装；三道归属闸门（G1 id
  匹配/G2 顶层唯一目录/G3 zip 守卫）、定向 299/0/0/0、反向五轮成对读数、testFast 4898/0/0/7
metadata:
  node_type: memory
  type: project
  originSessionId: sess_322d2772-c638-4faa-8a34-39507879169e
---

# T-LocalUpdateDir：关于页「本地安装包目录」自动检测与免重启安装（20261112 已实施）

- **状态：已实施 → 已合流 → 已推双仓 → 已清理**。分支 `t-local-update-dir` @ 工作树 `.worktrees/t-local-update-dir`（从 master `34d3af5ec` 切出，本会话独立实施未派子代理）：`f0c7c30d5`（实现 25 files +2467：LocalUpdateDir/LocalPackageScanner/PluginPackageOwnership/installFromLocal/UpdateSection Channel.LOCAL/StartupUpdateCheck 本地分支/i18n 25 键/文档四处）+ `744e4b8ea`（收口补测与守卫登记）+ `0910c8e83`（把 master 当轮 6 笔并入分支：冲突仅 CHANGELOG 与计划索引两处共享文档，按**两侧全留**解决）。
- **合流与推送（用户「合流 清理 推送」令）**：主仓 `--no-ff` 合入 **`bf258ec02`**；合并态定向 274/0/0/0（选择面更宽：加对方车道 `DsListAddSourceTest` 与 `BrowserUtilConvergenceGuardTest`，去掉 SettingsIO 等）；`git worktree remove` + `git branch -d` 清理完毕；`push-all.ps1 -Branch master` 推 Gitee + GitHub，两仓与本地同为 `bf258ec02`。**合并态证据**：分支尖树与 master 树哈希逐字节相同（`dd137609`）⇒ 被测树 == 已推树，无需复跑。
- 合流时踩到既有坑一次：`docs/索引/开发计划.md` 两车道在同一位置各加行，取整侧 `--theirs` 会丢掉对方行（→ [[pitfall-merge-take-one-side-loses-half]]）；正解＝取 master 侧保住对方新增行，再用唯一行尾锚（`含 D-1~D-8、P-1~P-9、T1~T6/T2b、A1~A13`）小 Edit 改回自己那行的状态列。
- 来源：用户「内网伙伴更新不顺利」，关于页版本信息附近指定存放新 zip 的目录，自动检测最新版、免重启安装、路径跨平台、校验目录合法性；中途追加硬要求「必须校验是不是我的插件，别装成别的插件」（→ [[feedback-package-ownership-gates]]）。D-1~D-8 全部按推荐拍板（不静默自动装/配置目录即 opt-in/不递归/只认 zip/since-build 高于当前 IDE 阻止安装/指向插件目录拒装/共用 ignoredUpdateVersions/夹带整包拒）。

## 落点与读数

- 定向终跑 **299/0/0/0**（新增 LocalUpdateDirTest 22 / LocalPackageScannerTest 22 / PluginPackageOwnershipTest 12 / PluginUpdateRunnerLocalTest 10 + UpdateSection/StartupUpdateCheck/Binding 扩展 12 例）。
- **反向验证五轮成对读数**（Edit 摘除→红→还原→绿）：A1 摘安装期 G1（NotOurs 分支）恰 1 红；A2 摘安装期 G2（Tainted 分支）恰 1 红；B 摘校验层 G2 布局块恰 5 红（夹带/改名/大小写/顶层杂物 + 扫描期夹带）且标准/平坦布局仍绿；C 摘只升不降恰 3 红；D 摘临时副本恰 2 红（install 收到源文件 + 复制失败形态消失）。
- testFast **4898/0/0/7 skipped** 全绿；首跑唯一红＝`BrowserUtilConvergenceGuardTest`（新增 `localDirectoryOpener` 默认 lambda 的 `BrowserUtil.open` 调用点未登记）→ 照 `captureDirectoryOpener` 先例登记豁免 + `expectedTotalCallSites` 5→6。
- 落点微调：本地目录行放进 `UpdateSection` 内部（版本信息行的组件就是 updateSection），未在 PluginSettingsPanelSections 另起 addRow；标签列由行内 `localDirLabel` 承担，搜索索引/守卫对账不受影响。

## 实测订正（前提对账）

- **P-9 已证**：真实 buildPlugin 产物（`mybatis-plus-sql-visualizer-2026.11.12.zip`，6 条目）顶层**只有一个目录**，LICENSE/THIRD-PARTY-NOTICES 在插件目录内（非 zip 根）⇒ G2 严格拒绝顶层外来条目不误杀真包。
- **G2 平坦布局**：直属 `META-INF/plugin.xml` 的 zip 会登记出顶层段 `META-INF`，首版把它当外来目录误拒（测试抓出）；修法＝判定前摘出 `META-INF` 段（flat = 除 META-INF 外无顶层目录），并新增「插件目录与 META-INF 混排 → Tainted」。
- **大小写变体**：首版 `equals(ignoreCase=true)` 会放行 `MYBATIS-...`，Linux/macOS 解包得第二个同 id 插件目录；改**精确匹配**，变体一律 Tainted。
- **dotDot 测试订正**：`dir/a/b/../a/b` 归一＝`dir/a/a/b`（探针实证 exists=false），实现判 NotFound 正确；用例改 `dir/a/b/../b`。
- Kotlin KDoc 嵌套注释坑 → [[pitfall-kotlin-nested-comment-in-kdoc]]。

## not run（交收口/用户）

- `testShards` 全量门禁与 `verifyGate`（计划 §八属收口段；本轮以定向 + testFast 补日常验证，**合流推送后仍未跑全量**——下次发版前须补）。
- runIde 沙盒热替换矩阵（P-1：243 必回退重启 / 253+/262/263 热生效——`PlatformEpDynamism` 既有取证，本地通道未单独实测）与 GUI 验收 A1~A14（判据在计划 §九；UNC 死路径/长路径/网络盘耗时 P-3/P-4/P-6 需真网络环境）。

相关：[[feedback-package-ownership-gates]]、[[feedback-all-plan-content-means-implementation]]、[[pitfall-install-from-disk-without-restart-blocks-hot-swap]]、[[pitfall-guard-expectation-drift-latent-red]]（BrowserUtil 守卫计数）。
