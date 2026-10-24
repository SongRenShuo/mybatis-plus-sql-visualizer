# T-SelfUpdateRollback：自更新链「回滚上一版」出口（开发计划）

**创建时间**：2026-10-24 08:41
**触发源**：用户 2026-10-24 点名立项（背景：T-MagpieGems 发行节奏调研指出「二进制侧自更新链要有回滚上一版出口，这条目前没核过，用之前要补」），并指定**参考 DBX 的设计**。
**参考设计来源**：
- **DBX（本地调研报告 `docs/内部/调研报告/20260914-0211-DBX数据库工具深度调研与MCP吸收规划-调研报告.md`）**：其数据网格保存的回滚设计＝**保留原态 + 反向操作**（`build_data_grid_rollback_statements`，server.rs:1581 起：改前先留全行原值，回滚时 new→按行值 DELETE、deleted→INSERT 原值、dirty→SET 旧值；不可靠回滚的引擎诚实返回空）。本仓已从 DBX 吸收过 `UpdateZipGuard`（O-29，b0d30e6）。
- **magpie（`docs/内部/调研报告/20261023-2311-magpie-*.md` §5.3）**：更新器双保险——换名暂存（运行中的 exe 移到 `exe.old` 再放新文件）+ 起新失败自动还原（`update.go:453-502、721-728`）。
**基线**：`t-magpie-gems@e35a0d7c`（自更新链 13 类：`update/PluginUpdateRunner` 编排、`DynamicInstallSupport` 热替换、`PlatformPluginInstaller` 脚本兜底、`UpdateZipGuard`、`VersionIgnore`…）。
**编号**：占 **O-150**（竞品 28 轮册口径「下一全新号自 O-150 起」；子序列取号先查主线台账）。
**状态**：计划待拍板。

---

## 一、现状与缺口（代码级）

自更新两条安装路径，**都没有回滚源**：

1. **热替换路径**（`DynamicInstallSupport.kt`，动态装载免重启）：平台动态安装新包；旧包去向待核实（见 P-1）。
2. **脚本兜底路径**（`PlatformPluginInstaller.installRestartScript`，`PlatformPluginInstaller.kt:38-60`）：调度**DeleteCommand 删旧目录 → UnzipCommand 解新包**，重启执行——删旧在解新之前，**一旦解压失败/新包起不来，旧版已物理删除，用户只能手动找旧 zip 重装**。
3. 更新检查链（`UpdateManifest`/`VersionComparator`/`StartupUpdateCheck`）只管「发现新版本」，装完之后没有「这个新版我不满意」的任何出口；`VersionIgnore`（忽略某版本不再提醒）已存在，可复用为回滚后的压制机制。

与 DBX 思想对齐的缺口：**升级动作没有「保留原态」这一步**，因此永远无法「反向操作」。

## 二、待验证前提（实施者先证伪再动手）

1. **P-1（决定 W1 形态）**：`DynamicInstallSupport` 热替换成功后，旧 zip/目录是否仍留在 plugins 目录？平台 `PluginsDisposer`/动态装载对旧包的处置（保留待重启清理 vs 立即删）——用 243 sources `DynamicPluginInstaller` 证实。若旧包天然保留，W1 热替换路径只需「标记 + 认领」，零拷贝。
2. **P-2（决定 W2 时序）**：`installRestartScript` 的 Delete/Unzip 命令由谁在重启哪个阶段执行（平台 startup script 机制）；回滚脚本能否同样调度（Delete 当前 + 把备份改名回正式目录），与更新脚本同一套机制还是有额外约束。
3. **P-3（决定备份落点）**：plugins 目录下并存 `xxx.backup-<ver>` zip/目录时，平台会不会把它当第二个插件装载（discovered 但 disabled？还是直接装两个？）——决定备份必须放 plugins 目录外（如 `PathManager.getPluginTempPath()`）还是可以就地改名。
4. **P-4（决定 W3 边界）**：Marketplace 渠道装的用户（非自更新链）能否用本出口——回滚=引导用户从 Marketplace 版本历史手动装旧版（插件侧只给指引），自更新链用户才有真一键回滚。两条渠道的文案与能力要分开。

## 三、修法（DBX 反向恢复两步 + magpie 双保险裁剪）

### W1 升级前快照（保留原态——DBX 第一步）

- 安装新包成功后（两条路径收口处），把**升级前版本**留存一份：
  - 热替换路径：按 P-1 结论——旧包若被平台清理，则在安装前 `Files.copy` 当前 zip → `<backupDir>/<pluginId>-<旧版本>.zip`；若天然保留，仅登记 manifest（旧版本号+sha256）。
  - 脚本兜底路径：DeleteCommand **改为**「把旧目录 move 到备份目录」（保留原态的反向操作：删除≠回滚源消失）而非物理删除；解新失败时脚本自动把备份 move 回（magpie「起不来就把 old 放回去」的降级版——脚本阶段可做到，运行阶段见 W3 边界声明）。
- 备份目录：按 P-3 结论定（默认 `PathManager.getPluginTempPath()/update-backup/`，只留最近 1 份，装新版成功且稳定（次次启动均正常）后清旧）。
- 快照登记：版本号、sha256、时间、来源渠道写 `update-backup/manifest.json`（幂等可重建认知）。

### W2 「回滚上一版」入口（反向操作——DBX 第二步）

- 入口两处：设置页「更新」分组新增按钮「回滚到上一版」（备份存在才可用，tooltip 显示旧版本号）；更新通知（StartupUpdateCheck 的「已更新」横幅）追加「回滚」动作。
- 动作：校验备份 sha256 → 调度回滚脚本（删/移当前目录 + 备份恢复回 plugins）→ **自动 `VersionIgnore.ignore(当前新版号)`**（复用既有类，防止回滚后又被更新提醒拉回来）→ 提示重启生效。
- 不可回滚的诚实降级（DBX ClickHouse 返回空的同款纪律）：备份缺失/校验失败 → 明确告知「无可用回滚源」+ 指引手动下载旧版（附 Gitee releases 链接），**绝不半恢复**。

### W3 边界声明与免责（计划内明确不做的事）

- **不做「新版起不来自动回滚」**：插件与桌面应用的本质差异——新版类加载失败时插件代码根本跑不了，无法自救（magpie 的 relaunch 自动还原在插件形态没有执行者）。自动化的上限=脚本阶段（解压失败自动还原，W1 内含）；运行阶段的兜底是**下一版修复 + 用户手动回滚**。
- Marketplace 渠道（P-4）：只提供指引文案，不承诺一键回滚。
- 热更新（免重启）回滚同样免重启与否跟随平台能力，不自行实现。

### 测试（S2/S3 纪律）

- 定向：`UpdateBackupStore`（快照/校验/清理纯逻辑）、回滚脚本编排（fake seam，对齐 `PluginUpdateRunner` 注入式测试先例）、`VersionIgnore` 联动、设置页按钮态（备份有/无）。
- 反向探针：摘掉「sha256 校验」→ 损坏备份被恢复必红；摘掉「DeleteCommand 改 move」→ 脚本路径备份缺失必红；摘掉 VersionIgnore 联动 → 回滚后更新提醒再现必红。
- 真机：243 沙盒实测「升级→回滚→重启→旧版可用、新版被忽略」全链（headless 盖不住平台脚本执行阶段，如实标注）。

## 四、排期建议

W1+P-1/P-2/P-3 核实一批（改动集中在 `PlatformPluginInstaller`/新 `UpdateBackupStore`），W2 入口一批（设置页+通知+VersionIgnore 联动），W3 只写文档声明。预计两个工作日内完成；不阻塞 T-MagpieGems 的合流（写集与 W2 脱敏不重叠，仅 `PluginSettingsPanel` 更新分组需错车道）。

## 五、风险与红线

- `update/update.json 只有用户能改`是铁律——本计划**不动清单与发行面**，只动客户端安装/回滚侧；
- 回滚脚本与更新脚本同用平台 startup script 机制，调度冲突（同插件两条 pending 命令）须在 W1 排队互斥；
- 备份目录写盘失败（磁盘满）不得阻断正常升级（fail-open：快照失败=可升级但记录「本轮无回滚源」日志与提示），与脱敏 fail-closed 形成显式对照。
