# T-DualRepo：Gitee 主仓 + GitHub 镜像双仓同步

**创建时间**：2026-10-22 11:40
**触发源**：用户令「将插件仓库同步到 github 了，之后就是双仓同步，gitee 是主仓」
**基线**：`master@21ac0f28`（Gitee 与本地一致，0 提交差；28 个 tag 两边一致）
**状态**：GitHub 仓库已建并完成首次推送（master / dev / feature 三支 + 28 tag）；文档与模板侧改动本计划覆盖，尚未全部落地。

## 一、目标与边界

- **Gitee 是主仓**：发行（Release + 自更新清单）、Issue 受理、日常开发提交的权威源。GitHub 只做镜像。
- **GitHub 是镜像 + 源码链接载体**：Marketplace 开源许可证要求「源码链接公网可达」，官方文档只举例 GitHub/GitLab，未明确列 Gitee——镜像的首要作用是满足这条。
- **不做的事**（本轮明确不引入）：
  - 不做 GitHub Actions 自动构建/校验。理由见 `docs/开发计划/20260906-0170-CI自动verifyPlugin与适配承诺-开发计划.md` §2.1：`gradle.properties` 硬编码 `127.0.0.1:7890` 代理、`VerifyPluginTask` 对 verifier 子进程硬编码同地址代理、`-Xmx4096m` 对免费 runner 偏紧，三项改造代价远超收益。CI 仍走本机计划任务（`dev-support/ci-verify.ps1`）。
  - 不做 GitHub Release 回填。自更新清单 `update/update.json` 的 `downloadUrl` 指向 Gitee Release，GitHub Release 无消费方；回填 28 版附件约需多推数十 MB 且长期无收益。后续版本是否顺带同步 GitHub Release，留待有实际需求时再定。
  - 不推 `t-sql-insight`（本地独有分支，Gitee 上也没有，无镜像义务）。

## 二、本机已完成的配置改动（一次性，不进仓库）

| 项 | 动作 | 验证 |
|---|---|---|
| 全局 URL 重写 | `git config --global --unset url."https://ghfast.top/https://github.com/".insteadOf` | `git config --global --get-regexp "url\."` 返回空 |
| GitHub 令牌 | 写入 `.local/github-token.json`（字段 `github_personal_access_token` / `owner` / `repo` / `login`），整目录被 `.gitignore` 的 `.local/*` 忽略 | `git check-ignore -v` 命中 `.gitignore:98`；`git status` 中零命中 |
| remote | `git remote add github https://github.com/SongRenShuo/mybatis-plus-sql-visualizer.git` | `git remote -v` 四条 |

**为什么去掉 ghfast 重写而不是绕开**：原配置把 `https://github.com/` 重写到 `ghfast.top` 只读加速代理，push 会被拒（Connection reset）。绕法是用 `https://<login>@github.com/...` 形态躲过前缀匹配，但那只是让每条 push 命令都变成特例。用户 2026-10-22 明确要求「ghfast.top 去掉，用正常的 https://github.com/」。去掉后 fetch/push 都走直连 + 本地代理（`127.0.0.1:7890`），代价是 clone 大仓时慢一些。

**令牌来源**：Windows 凭据管理器里已有的 `git:https://github.com`（GCM 存的用户名标签是 `XiaoSongTX`，实际 login 是 `SongRenShuo`，两者不一致是既有已知现象）。scope 实测为 `gist, repo, workflow`，够建仓与推送。**未新建令牌**。

## 三、推送方案

### 3.1 首次推送（已完成）

```bash
TOK=$(python -c "import json;print(json.load(open('.local/github-token.json',encoding='utf-8'))['github_personal_access_token'])")

# master（GitHub 建仓默认分支是 main，先推 master 再切默认分支）
git -c http.proxy=http://127.0.0.1:7890 -c credential.helper= \
  push "https://x-access-token:${TOK}@github.com/SongRenShuo/mybatis-plus-sql-visualizer.git" master:master

# dev + feature
git -c http.proxy=http://127.0.0.1:7890 -c credential.helper= \
  push "https://x-access-token:${TOK}@github.com/SongRenShuo/mybatis-plus-sql-visualizer.git" \
  dev:dev feature/kotlin-to-java:feature/kotlin-to-java

# tags
git -c http.proxy=http://127.0.0.1:7890 -c credential.helper= \
  push "https://x-access-token:${TOK}@github.com/SongRenShuo/mybatis-plus-sql-visualizer.git" --tags
```

`-c credential.helper=` 置空是必须的：否则 GCM 会弹 GUI 抢凭据（本轮实测卡住三次，进程 `git-credential-manager get` 常驻不退）。URL 里用 `x-access-token:<token>` 形态，GitHub 对 PAT 接受任意用户名。

### 3.2 日常双推（待落地脚本）

拟新增 `dev-support/push-all.ps1`：一次推 Gitee + GitHub，失败时逐端报告而不中断另一端。设计要点：

- 两端各自独立 `git push`，**不用 `&&` 串联**（AGENTS.md 收口纪律：失败会伪装成「进行中」）。
- GitHub 端复用上面的 `-c http.proxy` + `-c credential.helper=` + `x-access-token` 组合。
- 默认推当前分支 + 当前分支的 tag；`-All` 时推 master/dev/feature 三支。
- 令牌缺失时只推 Gitee 并提示，不报错退出。

### 3.3 发版时的双仓动作

发版流程（`docs/发行说明/T-ReleaseChecklist-发行检查清单.md`）**不变**，仍在 Gitee 建 Release。追加一条可选动作：GitHub 建同 tag 的 Release（不附 zip，正文用发行说明全文），让 GitHub 侧有版本叙事。是否要做由用户在每次发版时决定，不写进强制清单。

## 四、仓库元信息

GitHub 仓库已按以下设置创建：

| 项 | 值 |
|---|---|
| 名称 | `mybatis-plus-sql-visualizer`（与 Gitee 同名，跨平台找仓不迷路） |
| 可见性 | Public |
| 描述 | 英文摘要（GitHub 以英文为主流受众；Gitee 侧保留中文长描述不动） |
| Homepage | `https://sql-visualizer.jvtools.cn` |
| License | 由仓库内 `LICENSE` 自动识别（Apache-2.0） |
| Issues | 开启（见 §五） |
| Wiki | 关闭（内容已在官网文档站，双份维护无收益） |
| Projects | 关闭 |
| Topics | `intellij-plugin` `mybatis` `mybatis-plus` `sql` `sql-visualizer` `jpa` `hibernate` `mcp` |
| 默认分支 | 首次推完 master 后改为 `master`（建仓默认是 `main`） |

**待用户操作**：Marketplace 作者后台把 Source code 字段填成 `https://github.com/SongRenShuo/mybatis-plus-sql-visualizer`（当前 `sourceCodeUrl` 为空，vendor 仍是 Gitee 地址）。

## 五、Issue 受理口径

**两个平台都开启 Issue，但都置顶一条引导，主入口仍是官网需求池。**

理由：关掉 Issues 会让「想提但找不到入口」的用户直接流失，也会让 GitHub 侧显得仓库无人维护；开着但置顶引导，既不挡人也不分裂反馈。

- GitHub：`README.md` 顶部加一行 GitHub 链接；置顶 Issue 正文见 §七模板。
- Gitee：同样置顶一条（Gitee 的置顶靠 Issue 的「置顶」标记，需在网页上操作一次）。
- 官网需求池 `https://sql-visualizer.jvtools.cn/about/feedback` 是唯一主入口（README 第 7 行已如此，plugin.xml 描述里的「需求池」链接也是它）。

## 六、竞品名分级处置（详见 §八分析）

沿用 20261012 已定案判据（`~/.zcode` 记忆 `project-competitor-docs-open-source-exposure`）：

- **不清**：只提及竞品名（横向对比、能力矩阵、下载数、Marketplace 链接、README 致谢）。
- **要清**：点名商业闭源插件的**实现细节**——混淆类名、EP 注册链、反编译产物路径、内部配置字段。
- **本轮新增处置**：`docs/MEMORY.md` 第 251 行仍写着「（已移出公开仓）」，这正是 20261012 用户明令禁止的形态（占位指针本身即敏感）。属**违规残留**，须改中性标签。

## 七、实施清单

| # | 动作 | 文件 | 状态 |
|---|---|---|---|
| 1 | 去掉 ghfast 全局重写 | 本机 git config | ✅ 完成 |
| 2 | 令牌落 `.local/github-token.json` | `.local/`（已忽略） | ✅ 完成 |
| 3 | 建 GitHub 仓库 | GitHub | ✅ 完成 |
| 4 | 推 master / dev / feature + 28 tag | GitHub | 进行中 |
| 5 | 默认分支改 master | GitHub API | 待做 |
| 6 | 新增 `.github/PULL_REQUEST_TEMPLATE.md` | 新文件 | 待做 |
| 7 | 新增 `.github/ISSUE_TEMPLATE/bug_report.yml` + `feature_request.yml` + `config.yml` | 新文件 | 待做 |
| 8 | `CONTRIBUTING.md` 双仓口径 | 3 处 clone/PR/Issue 段落 | 待做 |
| 9 | `README.md` 顶部加 GitHub 链接 | 第 7 行导航行 | 待做 |
| 10 | `docs/MEMORY.md` 第 251 行去占位措辞 | 1 行 | 待做 |
| 11 | 清 `XmlSynHiGate.kt` 闭源指纹（进插件包发给用户） | `src/main` 注释块 | ✅ 完成 |
| 11b | 测试夹具 `com.ccnode.fakeprobe` 改中性包名 | `src/test`（2 文件移动 + 2 处引用） | ✅ 完成 |
| 12 | 新增 `dev-support/push-all.ps1` | 新文件 | ✅ 完成 |
| 13 | demo 仓库同步 GitHub | 独立仓库 | ✅ 完成 |
| 14 | 两平台置顶 Issue | 网页操作 | 待用户 |

## 八、竞品名分析结论

### 8.1 分级口径

按 20261012 判据把仓内提及分三档。**关键区分：开源有公开仓 ≠ 闭源**。MyBatisX（`github.com/baomidou/MybatisX`）、MyBatis Log Free（`github.com/starxg/mybatis-log-plugin-free`）、Cool Request（`github.com/houxinlin/cool-request`，GPL-3.0）、MyBatis-Flex（`github.com/baomidou/mybatis-flex`）都有公开源码，读其公开源码属署名式引用，不在清理范围。真正闭源的是 **MyBatisCodeHelperPro**（`plugin/9837` + MP 付费版 `14522`）、**Mybatis Log Ultra**（`plugin/26550`）、**zMyBatis**（`plugin/30447`）、**JTracker**（`plugin/24694`）——Marketplace API 登记均无 `sourceCodeUrl`。

### 8.2 逐项处置

| 位置 | 性质 | 处置 |
|---|---|---|
| `README.md`「参考与致谢」6 项 | 名字 + Marketplace 链接，纯致谢 | **保留**。这是正当的致谢与引用，删掉反而不礼貌 |
| `src/main/kotlin/.../XmlSynHiGate.kt` 第 34-42 行 | **闭源实现细节**：`plugin.xml:484` 可选依赖链、`plugin_database.xml` 的 `ultimateVersionChecker`、混淆方法名 `i.b.c()`、版本号 `MCH-Pro 3.6.7` | **必须清**。这段注释会随插件 jar 分发到每个用户机器上，是「逆向闭源插件」最直接的物证。技术结论（「竞品的社区轨注入器被自身平台让位门挡住」）保留，手段与指纹改为中性表述 |
| `src/main/resources/META-INF/plugin.xml` 第 26、366 行 | 仅名字（「参考件 X 同形」「交互参考 X 面板」） | **保留**。属名字级提及，且「参考」是事实陈述 |
| `src/main/kotlin/.../PluginSettings.kt` 第 270 行、`PluginSettingsPanel.kt` 第 560 行、`SqlLogEntryBuffer.kt` 第 10 行、`SqlLogManager.kt` 第 89 行 | 仅名字（「参考 X 的 Y 设置项」） | **保留**，同上 |
| `docs/MEMORY.md` 第 251 行 | 「（已移出公开仓）」占位指针 | **改中性标签**，去掉文件名与「已移出」字样 |
| `docs/MEMORY.md` 第 324 行 | 「MBCHP-Pro 反编译对照」——缩写 + 反编译字样 | **改为中性表述**（「商业闭源插件能力对照」） |
| `docs/memory-archive/bug-fixes/reference-competitor-plugin-links.md` | 竞品链接总表，含闭源插件 Marketplace id | **保留**。id 与下载数是 Marketplace 公开信息，不含实现细节 |
| `docs/memory-archive/bug-fixes/project-competitive-round7.md` | 标题含「zMyBatis 深度解剖」，正文含类级结论 | **评估后保留正文、改标题措辞**。zMyBatis 闭源，但正文是能力级结论；「深度解剖」字样在公开仓显得像逆向宣告，改中性标题 |
| `docs/调研报告/竞品横向对比-第N轮`（26 份） | 多为 Marketplace API 数据 + 能力矩阵；个别含 `plugin.xml` 关键词扫描、包体条目数 diff | **保留**。这是「功能对比」而非「实现逆向」；`plugin.xml 关键词扫描`（mcp/preview/explain 命中数）属公开包的可见信息，不涉反编译。个别轮次若含类名级细节，按 §8.3 复核 |
| `docs/内容营销/老版本承接-*.md` | 竞品兼容区间与下载数 | **保留**。纯市场数据 |
| `docs/变更记录/*`、`docs/发行说明/*` | 历史记录里的名字级提及 | **保留**。已发布内容，改动会与已发布版本叙述冲突 |

### 8.3 复核方法（实施时执行）

```bash
# 闭源实现细节指纹（20261012 基线应 0 命中）
git grep -n -iE "MBCHP|com\.ccnode\.codegenerator|i\.b\.c\(|BatsLogConfig|maxPrepare|ultimateVersionChecker|paramLanguage|MybatisOgnl|fernflower.*CodeHelper" -- . | grep -v "^docs/内部"
```

命中即处置。注意 `fernflower` 单独出现不算（本仓既定方法论是反编译 **IDEA 平台 jar**，对象不是竞品）。

### 8.4 关于「移到内部仓」

本轮**不做大批量迁移**。理由：

1. 20261012 已把真正的实现细节（19 组 40KB）迁完并存档，当前公开仓没有成规模的逆向材料。
2. 把 26 份竞品横向对比报告全迁内部，等于让公开仓的「为什么这么设计」失去依据——这些报告是功能决策的论证链，不是逆向产物。
3. 真正需要动的是**两处**：`XmlSynHiGate.kt` 的闭源指纹（会进插件包）和 `MEMORY.md` 的占位残留（违反既有定案）。改动量小、风险低。

## 九、待验证前提

| # | 前提 | 验证方式 | 结论 |
|---|---|---|---|
| P-1 | GitHub 建仓默认分支是 `main` | 建仓 API 返回 `default_branch: main` | ✅ 成立，需手动切 master |
| P-2 | GCM 存的 GitHub 凭据可直接用 | `/user` 返回 `login: SongRenShuo` | ✅ 成立 |
| P-3 | 去掉 ghfast 重写后 push 能直连 | master 推送成功 | ✅ 成立 |
| P-4 | `-c credential.helper=` 能阻止 GCM 弹窗 | 三次弹窗后加该参数即成功 | ✅ 成立 |
| P-5 | 仓库体积可接受 | 跟踪文件 45.9MB，`.git` 83MB | ✅ 首推约 2 分钟 |
| P-6 | `docs/内部/` 从未入库 | `git log --all --diff-filter=A -- docs/内部` 空 | ✅ 成立，不会随镜像泄露 |
| P-7 | 历史无 token 泄露 | `git log --all -S"gitee_personal_access_token"` 仅命中计划文档说明文字 | ✅ 成立 |

## 十、风险

1. **搜索引擎收录带来的可见度跃升**：GitHub 被搜录强度高于 Gitee，仓内竞品对比内容的曝光会变大。按 §八处置后，留下的是名字级提及与能力矩阵，属行业常规做法。
2. **双仓漂移**：若某次只推了 Gitee，GitHub 会落后。缓解=`push-all.ps1` 作为默认动作，且发版清单追加一条「GitHub 镜像是否同步」核对。
3. **令牌是账号级 PAT（`gho_` 前缀 + `repo` scope）**：权限比只推单仓所需的大。它只存在于本机 `.local/`，不入库。若日后要在多机使用，建议换成 fine-grained token 限定到该仓库。

## 十一、实施记录

### 11.1 完成情况（2026-10-22）

| 项 | 结果 |
|---|---|
| GitHub 插件仓 | `SongRenShuo/mybatis-plus-sql-visualizer`，Public，默认分支 `master`，8 个 topics |
| 首次镜像 | master / dev / feature/kotlin-to-java 三支 + 28 个 tag |
| 最终镜像 | 三支 SHA 三端逐一相同（master `4b50fa27` / dev `a7ef5b76` / feature `a37ef021`），tag **29/29/29** 齐平 |
| GitHub demo 仓 | `SongRenShuo/mybatis-plus-sql-visualizer-demo`，默认分支 `master`，7 个 topics |
| demo 镜像 | 按 Gitee head `74cc257` 首推，随后两端同步至 `af346ad` |
| 提交 | 插件仓 `e45294eb` / `5b4d8b65` / `d1edf65f` / `5c39034e` / `4b50fa27`；demo 仓 `ef94e97` + `af346ad` |
| 定向测试 | `XmlSynHiGatePlatformProbeTest` 17 + `XmlSynHiGateRangeTest` 10 = **27/0/0/0** |
| 置顶引导 | GitHub Issue #1 已 pin（GraphQL `pinIssue`，`isPinned: true`）；Gitee Issue `#IKPMBU` 已建，**置顶待网页操作** |

### 11.2 实施中踩到的四个坑（前三个在脚本层，已修进 `push-all.ps1`）

**① PowerShell 把 git 的 stderr 当致命错误。** `$ErrorActionPreference = 'Stop'` 下，`git push` 输出 `Everything up-to-date`（走 stderr）会被包成 `NativeCommandError` 终止脚本——表现是 Gitee 段成功后整个脚本退出，GitHub 段根本没执行，但退出码看着正常。修法：调 git 的代码块内局部降为 `Continue`，只用 `$LASTEXITCODE` 判成败。

**② 网络双向间歇中断。** 实测同一分钟内直连前 4 次 `Couldn't connect / Connection was reset / SSL_ERROR_SYSCALL`、第 5 次成功；同期 `curl` 打 `api.github.com` 又稳定 200。修法：`Invoke-PushWithFallback` 带重试（默认 5 次，间隔 5 秒）。

**③ DryRun 输出过令牌明文。** 首版 `-DryRun` 把完整 URL（含 `x-access-token:gho_...@`）打到屏幕。修法：display 字符串统一 `-replace 'x-access-token:[^@]+@', 'x-access-token:***@'`，并实测 `grep -c "gho_"` 为 0。

**④ 代理「端口在听」≠「能用」（2026-10-22 用户口径：网络问题用本地代理解决）。** 本机 `mihomo`（ClashParty 1.9.6）进程在跑、`127.0.0.1:7890` LISTENING，但对 `api.github.com` / `google.com` / `1.1.1.1` 全部返回 `000`（5/5 轮）——上游节点失效。此时若只看端口会不会听就会误判「代理可用」。修法：新增 `Test-ProxyUsable`，实打一次 5 秒超时的 HEAD 探通再决定走哪条路；顺序改为**代理优先 → 探不通回落直连**。

另有一处非脚本坑：`.ps1` 若存为无 BOM 的 UTF-8，PowerShell 5.1 按 GBK 解码中文注释会导致语法解析失败（报 `Missing closing '}'`）。本仓 `dev-support/*.ps1` 统一 UTF-8 BOM + CRLF，新脚本照此保存。

另有一处非脚本坑：`.ps1` 若存为无 BOM 的 UTF-8，PowerShell 5.1 按 GBK 解码中文注释会导致语法解析失败（报 `Missing closing '}'`）。本仓 `dev-support/*.ps1` 统一 UTF-8 BOM + CRLF，新脚本照此保存。

### 11.3 竞品名处置的实际落地

| 位置 | 动作 |
|---|---|
| `XmlSynHiGate.kt` 注释块 | 去 `plugin.xml:484` / `ultimateVersionChecker` / `i.b.c()` / `MCH-Pro 3.6.7`，技术结论保留为「某商业插件的社区轨注入器被其自身平台让位门挡住」 |
| `src/test/.../com/ccnode/fakeprobe/` | 整体迁至 `com/example/thirdparty/fakeprobe/`，`FakeCodeHelperInjector` 更名 `FakeThirdPartyInjector`。生产判定只看 `SELF_PACKAGE_PREFIX`，夹具包名无功能含义 |
| `docs/MEMORY.md` 第 251 行 | 「（已移出公开仓）+ 文件名」占位指针 → 「第一轮 XML 语法补全与高亮能力对照调研（内部资料库）」 |
| `docs/MEMORY.md` 第 324 行 | 「MBCHP-Pro 反编译对照」→「商业闭源插件能力对照」 |
| `docs/memory-archive/.../project-competitive-round7.md` | 「深度解剖 / 源码解剖 / 入场解剖」→「源码通读」；zMyBatis 经 Marketplace API 核实**有公开 GitHub 仓**（104 提交），属公开源码引用 |

**未动**：README 的「参考与致谢」6 项（正当致谢）、`plugin.xml` 与若干 Kotlin 注释里的名字级提及（「参考 X 的设置项」）、26 份竞品横向对比调研报告（Marketplace 公开数据 + 能力矩阵）、`reference-competitor-plugin-links.md`（公开 id 与下载数）。判据见 §8.1。

### 11.4 遗留

- ~~**Gitee Issue 置顶**需网页操作~~ → **用户已完成（2026-10-22）**。
- ~~**Marketplace Source code 字段**待用户改 GitHub~~ → **用户已完成（2026-10-22）**，见 §11.7。
- `dev-support/push-all.ps1` 未接 GitHub demo 仓（demo 是独立仓，脚本只处理当前仓库）。若日后频繁改 demo，可复制一份到 demo 仓或加 `-Repo` 参数。

### 11.7 Marketplace 源码链接改填 GitHub + 发布文档订正（2026-10-22 追加）

**用户反馈两件事**：① Gitee Issue 置顶已完成；② 「Source Code 不是空的，目前指向 Gitee，推荐改成 GitHub 吗？目前好像没影响」。

#### 先纠正我上一轮的错误

我此前说「Marketplace Source code 字段是空的」是**查错了层级**——该字段在 `GET /api/plugins/33633` 的 `urls` 子对象里（`urls.sourceCodeUrl`），不在顶层。实际值一直是 Gitee 地址，用户没记错。

#### 判断：该换，但「目前没影响」在事实上成立

- **没影响的依据**：官方要求只有「公网可达、公开可访问」一条；该字段填 Gitee 从 2026-08-18 首版起 **9 个版本一路过审**，审核从未要求换。所以这不是缺陷修复。
- **仍建议换的三条**：①受众匹配（Marketplace 是全球平台，海外用户见到 Gitee 多半不点）；②可达性（Gitee 境外不稳、GitHub 境内不稳，此字段选 GitHub 给全球一个默认能打开的面）；③口径风险（官方文档举例只列 GitHub/GitLab，未列 Gitee）。

#### 已完成

- 用户在作者后台把 Source Code URL 改为 `https://github.com/SongRenShuo/mybatis-plus-sql-visualizer`；
- 线上复核：`GET /api/plugins/33633` → `urls.sourceCodeUrl` = GitHub 地址 ✅；`vendor.url` 仍为 Gitee（两个独立字段，未一并改）。

#### 文档订正（这项之前自相矛盾，必须修）

`docs/发行说明/Marketplace发布流程-说明文档.md` 原本四处口径冲突：§1.2 说「源码字段填 GitHub」，§5.1/§5.4/§10.2 又说 2026-08-18 定案「直接用 Gitee，GitHub 镜像未启用」，还附「仓库未配置 github 远程、`scripts/push-all.sh` 不存在」——**这三句在双仓落地后全部不成立**。

| 位置 | 订正 |
|---|---|
| §1.2 结论表 | 「Gitee 能否作源码链接」改为「能，且实测 9 版过审」，并注明 2026-10-22 改填 GitHub 及其理由 |
| §5 标题与导读 | 「Gitee 直连；GitHub 镜像可选未启用」→「GitHub 镜像；Gitee 仍为主仓」，旧定案标为**已废止**并说明三个前提为何不成立 |
| §5.1 新增「为什么换成 GitHub」 | 显式写明「换不是因为它不改就会出问题」+ 三条理由（受众 / 可达性 / 口径），避免后人误以为是修 bug |
| §5.2 现状与依据 | 保留「Gitee 曾过审」这一事实，标注为可达性优化而非合规修复 |
| §5.3 / §5.4 | 原「GitHub 镜像未启用」的建仓步骤改为「已就位，仅换机重建时备忘」；日常同步改为 `push-all.ps1` 并补脚本要点 |
| §5.5 协作约定表 | 「Gitee 唯一写源 + 源码链接」拆开：Gitee 是写源（Release/清单/部署），GitHub 是源码链接载体 |
| §10.2 推送源码行 | `git push origin master` → `push-all.ps1`，补「镜像过期等于给用户看旧代码」 |
| §10.1 收尾清单第 5 项 | 旧手工命令 → `push-all.ps1 -All -Tags` 同 SHA 核对 |
| `T-ReleaseChecklist-发行检查清单.md` RC-R4 | 「源码链接（公网可达）」→ 写死 GitHub 地址 + 注明 Gitee 仍为主仓 |
| `marketplace-custom-page.html` + `.md` 两版 | 「源码：Gitee」→「GitHub（镜像）· Gitee（主仓）」双链（两版同改是红线；**线上 Documentation 标签要用户重新粘贴才生效**，随下一版 zip 进包） |
| `docs/MEMORY.md` 两处重复索引行 | 补「2026-10-22 双仓订正」摘要 |
| 顺手修两处既有失效引用 | §1.1「详见 §5.7」→ §10.2（§5 从无 5.7）；§1.2「见 §5.4」→ §7（代码签名在 7 节） |
| `docs/memory-archive/.../project-marketplace-publishing.md` | 2026-08-18 的「Gitee 作源码链接」决策后追加**现状变更警示**（历史记录不改写，但必须标出已被取代） |

**未改动**：`docs/开发计划/20260919-0449-T-ReleaseChecklist-*` 里「GitHub 条目降级或删除」那条——它是当时对账结论的正确记录（那时确实没有 github 远程），属于已交付计划的历史事实。



### 11.5 官网文档站同步（2026-10-22 追加）

用户追问「文档站是不是也要调整，例如仓库图标新增 GitHub 一类的」。查证后确认仓库只有一个 Gitee 入口，改动落在四处，另加一个图标资源。

| 位置 | 改动 |
|---|---|
| `public/github-icon.png` | **新增**。与 `gitee-icon.png` 同规格：64×64 实心圆 + 白色字形，字形占直径 0.625（与 Gitee 图标白色字形实测 40/64 一致），用官方 Octocat path 经 `@resvg/resvg-js` 渲染 |
| `.vitepress/theme/components/home/NavHeader.vue` | 首页导航栏在 Gitee 图标旁并列 GitHub 图标；`.nav-gitee + .nav-gitee { margin-left: -0.625rem }` 把两个图标收成一簇 |
| `.vitepress/theme/components/NavBarGitee.vue` | 文档页导航改为 Gitee + GitHub 两个图标。**顺带修一处既有不一致**：该组件原本用外链 `https://gitee.com/favicon.ico`，首页用的是本地 `/gitee-icon.png`，现统一为本地 |
| `.vitepress/theme/components/home/WelfareCard.vue` | 开源区两个仓库卡并排（96px/60px 图标，原 120px 竖排会把右列撑高一倍） |
| `about/index.md` + `en/about/index.md` | 联系方式表格拆为「源码仓库（主）/（镜像）」两行，中英双语 |
| `.vitepress/theme/data/homeContent.js` | 补 `nav.githubTitle` 与 `welfare.githubCaption` 的 zh/en 文案；welfare caption 由「前往 Gitee 仓库」改「前往源码仓库」 |
| `README.md` | 相关链接补 GitHub 镜像 |

**未改（有意保留）**：所有 `/releases` 链接（Hero「下载离线包」、changelog 的 more-link、guide 的离线安装、`update-changelog.mjs`）。原因＝GitHub 侧不回填 Release，自更新清单 `downloadUrl` 与离线包只在 Gitee 发布，指向 GitHub 会是死链。

**验证**：`npm run build` 通过（46 页索引、exit 0，产物含 `/github-icon.png` 且首页同时含两个仓库 URL）；`vitepress preview` 实机核对了首页中英双语导航栏与开源区、`/about/` 联系方式表格，DOM snapshot 与截图均符合预期；生成的图标与 Gitee 图标做过 128px 并排像素对照。

### 11.6 官网仓镜像（2026-10-22 追加）

| 项 | 结果 |
|---|---|
| GitHub 文档站仓 | `SongRenShuo/mybatis-plus-sql-visualizer-docs`，Public，默认分支 `master`，5 topics |
| 同步 | master `2365ce7` 三端一致 |
| 安全性核查 | `.cf-token` / `.secrets` / `.wrangler` / `.workbuddy-ai` / `.playwright-mcp` **跟踪文件数均为 0**；`guide/faq.md` 提及竞品名属「共存行为」说明（名字级，非实现细节），按 §8.1 保留 |
| 部署口径 | Cloudflare Pages **只从 Gitee 主仓触发**，GitHub 镜像仅作代码可见性备份（已写进该仓 README） |

三仓合计：插件仓、demo 仓、文档站仓，均 Gitee 主 + GitHub 镜像。

### 11.8 发现已有 --from-github 同步链路，撤销 CI 自动 Gitee 步骤（2026-10-26 追加）

用户批准「GitHub 流水线 publish job 末尾加一步自动推 Gitee」。实施前通读 `dev-support/gitee-release.py`
（准备复用其 API 逻辑）时发现：**该脚本当天上午刚提交了 `--from-github` 模式**（`048d4e03`，2026-10-26 10:38），
注释里写着用户同日定案——「Gitee 仍是主仓，GitHub 触发的发布只能**经人确认**后才落一份到 Gitee；
本脚本不自动跑、也不由 CI 调用」。与刚批准的方案直接冲突，故停止实施。

**冲突消解：不加 CI 步骤，改用已有链路。** `--from-github` 就是「更省事的做法」的正确形态：
一条命令自动从 GitHub Release 取附件（sha256 校验、本地同名不同哈希拒绝覆盖、草稿态拒绝同步、
tag 缺失时给出补推命令而不自动推），摘要后人工输入 `yes` 才落 Gitee。与 CI 自动步相比，
只多一次回车，换来「Gitee 落版永远经人确认」——这对自更新通道数据源是合理保守。

RC-R2 已改写为双路：CI 发布走 `--from-github`，本机发布走网页；RC-R2b 标注仅本机发布场景需要。

**验证（2026-10-26 只读实测）**：`gitee-release.py --version 2026.10.26 --from-github --verify-only`
全绿——且本地 build/distributions zip、GitHub Release 附件、Gitee Release 附件三方 sha256 一致
（`7f0231ea…`），v2026.10.26 的「CI 建 GitHub Release → 人确认同步 Gitee」链路字节级闭环。

Gitee Go 空白流水线**不建**：Gitee 侧不需要 runner（同步是本机一条命令），建了反而引入第二套
流水线的维护面。


