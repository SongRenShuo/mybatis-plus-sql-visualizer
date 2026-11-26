---
name: project-coolrequest-vip-update-channel-port
description: T-内测渠道复刻到 coolrequest-vip（20261107 实施完毕+渠道仓已建成：impl/wt-update-channel 8 笔提交、定向 101/0/0、四探针闭环、buildPlugin 52MB 出包；Gitee songrenshuo/coolrequest-vip 公开仓 c0b15cf 建成并匿名 raw 验证）——待用户发首个内测版+真机 GUI 七步验收；两项 zip 阈值适配与 @Service 前提推翻留档
metadata:
  node_type: memory
  type: project
  originSessionId: sess_cddb53d3-1842-447e-a693-e38c5fbee3e5
---

# coolrequest-vip 内测更新渠道复刻（2026-11-06 立项）

用户令：把 mpvs 的插件内测更新渠道（[[project-dev-update-channel-20261106]]）**完完整整复刻**到 coolrequest-vip（`F:\code\Java\Github\other\houxinlin\coolrequest-vip`，**song 分支**，Java 为主）；用户将另建一个 **Gitee 公开仓**专管渠道产物（只放 `update/preview.update.json` + 脚本 + README，源码不进）。计划册：`doc/开发计划/插件内测更新渠道复刻开发计划.md`（目标仓格式无时间戳前缀，「结论先行」节）。

## 关键侦察结论（Explore 实测，file:line 在计划册）

- 目标仓**零更新机制**（fetch/解析/比较/下载/安装/UI 全无）——复刻实为「整套装地基 + 开发者模式入口」两件事。
- 设置页是**私有对话框**（`SettingDialog.java:42-103` 9 页数组，非 plugin.xml EP），新页走 MCP 页模式（`ConfigurableBase`+`ConfigurableUi`）；主设置页 Kotlin DSL **没有版本标签** → 连点锚点要新增「当前版本」行。
- **版本两处不同步**：plugin.xml 2026.11.1（唯一真相）vs `CoolRequestConfigConstant.VERSION` 2026.10.1（滞后）；只读 descriptor 不顺手修（D-9）。
- HTTP 用 OkHttp 4.11（`HttpRequests` 全仓 0 用，`OKHttpInstance` 带 trust-all + 代理）；JSON Jackson；i18n `messages_en/zh.properties` + `ResourceBundleUtils`；测试 LightPlatformTestCase JUnit3 风格零 `org.junit`。
- **版本比较坑平移**：目标 `utils/Version` 点分比较不识 `-beta.N`（正是 mpvs 已修的倒挂坑）→ 必须委托平台 `VersionComparatorUtil`（P-1 先验 2024.1 基线）。
- 陷阱：`build.gradle.kts2` 是废弃草稿别碰；`publishPlugin` 硬编码 token 别扩散；song 领先 origin/song 39 笔未推 + `gradle.properties` 有本地改动；`require-restart="true"` → 安装层收敛为重启生效一条路（D-3 有意裁剪热替换）；zip 顶层短名 `cool-request-plugin` ≠ id（mpvs 2026-08-20 同款坑）。

## 券结构

A 更新地基（9 文件纯逻辑）→ B 更新设置页（UI+兜底安装+SettingDialog 挂页）→ **B2 热替换链**（DynamicInstallSupport.java ≈600 行翻译 + PlatformEpDynamism.java + 插件侧三处收口 + InstalledLive 态，≈36 测试例移植）→ C 开发者模式（连点+预览按钮+可见性保持）串行；D 渠道仓库（新 Gitee 仓+脚本）独立并行；E 启动静默检查挂起（D-7）。反向验证三探针同 mpvs + B2 反探（卸载门恒 false→必须回退重启不报错）。系统属性前缀 `coolrequest.update.*`；bundle 键 `update.*`。实施分支 `impl/wt-update-channel`。

## 热更新二次调研定案（2026-11-06，用户令改免重启 + 新仓定名）

- **用户定案**：热更新免重启（D-3 改「动态优先+脚本兜底」双路）；新仓定名 **`coolrequest-vip`**（与 GitHub 仓同名，owner 按 Gitee 账号 songrenshuo）。
- **EP 审计（本机 IU-2024.3.7.1 发行 zip，Python zipfile 嵌套扫 META-INF/*.xml）**：插件 17 个平台 EP 全 `dynamic="true"`（含 runConfigurationExtension/completion.contributor/lineMarkerProvider 历史坑位）——比 mpvs 干净（那边栽在 sql.dialect）。`supportsKotlinK2Mode` 在 243 **连 EP 都不存在**（死条目；241+ 动态性未探=P-9）。探针注意：EP 短名按末段比（completion.contributor 的短名是 contributor）。
- **插件侧三处收口（热卸载才暴露）**：① plugin.xml `require-restart="true"` 删除；② `StaticResourceServerServiceImpl.dispose()` 是**空的**（applicationService，executor/runningServer 线程不停）→ 补停；③ `CoolRequestMCPServer` 手动静态单例**全仓无 Disposer.register**（dispose 注释说「IDE 退出时收掉」但没人调）→ applicationService 化或挂 app Disposable。
- **mpvs 热替换链体量**（移植源）：`DynamicInstallSupport.kt` 558 行（反射五入口+卸载门三族**按返回类型判别**——243 的 check 同名返 String，只按名字匹配 CCE 恒判不可卸+写安全模态派发禁 any()+自更新 classloader 陷阱）+ `PlatformEpDynamism.kt` 87 行 + 测试 19/6/4/7 例。243 全绿、262 用户真机热替换成功；223~240 未探（本地无包），平台否决自动回退重启属设计内。

## 闲时任务全量实施（2026-11-07 用户令「按推荐来，实施开发计划所有内容并端到端测试，有问题修复后继续」）

- **任务**：`offpeak-b845d89d-c7d8-40e2-aefc-6eea5d0544ef`（队列 #98 起跑），在本会话内无人值守续跑；分支 `impl/wt-update-channel`（从 song 拉）。
- **决策口径写死**：D-6 稳定「检查更新」按钮首批可见（未发布走 CHANNEL_MISSING 友好态）、D-7 启动静默检查不做（券 E 跳过）、D-3 双路热替换必做——剩余拍板全部落定。
- **约束**：工作树里 gradle.properties 等本机改动保留原样不回退也不混提交（按文件白名单 add）；每券达标即提交（-F 文件防反引号）；gradle 前过 parallel_tasks.py check、长命令后台跑、只跑定向不跑全量；**不 push 不建 tag 不动 mpvs 仓**（渠道发布等用户授权）。
- **端到端口径**：全部定向测试绿 → 先编三个子工程再 buildPlugin 出 zip 自检 → 本地 HTTP server 仿 Gitee raw（`-Dcoolrequest.update.*` 覆盖）验「fetch→解析→比较→404 友好态」全链；**真实安装链受开发态守卫限制，seam 全覆盖 + 如实写边界**，真机 GUI 走查交付编号步骤+判读口径给用户本人做（沿 [[feedback-user-runs-gui-acceptance]] 惯例）。

## 实施终态（2026-11-07 收官）

**已完成并全绿**：8 笔提交 `5b7225d3`→`89708f96`；券 A 地基 10 类 → B 设置页+SettingDialog 挂页（9→10 页）→ B2 热替换链+两处收口 → C 连点解锁；定向 **101/0/0**（十二类，含真 HTTP e2e 6 例用真实 52MB zip 过守卫/前置校验）；四反向探针红绿两读数全闭环（探针 1 抓到断言自证空绿盲区，已改字面锚定）；buildPlugin 52,255,621B，zip 顶层短名 `cool-request-plugin`/plugin.xml version/require-restart 移除全实证；计划册 10.1~10.7 回填（提交 `89708f96`）；并行登记已注销。

**实施中证伪的三条计划前提**：① `CoolRequestMCPServer` 已带 @Service（平台自动 dispose），「挂 Disposer 树」收口不实施；② 真实包 69 条目 > MAX_ENTRIES 64（mpvs 包仅 5 条目定的阈值）→ 调 128；③ 主 jar 3320 条目、plugin.xml 在第 3300 位 > 内层扫描 2000 → 调 20000。另：`UpdateHttp` 不走 OKHttpInstance（其绑 Project+debugNoTimeout+trust-all），自建 client 读代理设置+固定 15s/30s 超时+默认 TLS。

**渠道仓已建成（2026-11-07 用户授权代建）**：`songrenshuo/coolrequest-vip` 公开仓，本地工作副本 `F:\code\Java\Gitee\SongRenShuo\coolrequest-vip`（tip `c0b15cf`：README+双清单 2026.11.1 基线+scripts/gitee-release.py）；Gitee 规则「空仓不能转公开」→ 先推内容再 PATCH private=false；匿名 raw 两清单 200 实证。令牌在插件仓 `.local/gitee-token.json`（repo_purpose=update-channel-only）。**剩余（用户侧）**：发布首个内测版走 scripts/gitee-release.py --prerelease → 所有者手改清单 → 真机 GUI 七步验收（计划册 10.7）；插件分支未推送、合 song 待用户点名。


## 2026-11-10 热替换上线后的两处连带缺陷（已修并发布 2026.11.10）

用户真机报障两条，均从 idea.log 取到完整栈：
1. **更新页永远停在「正在下载并安装…」**——安装回调跑在已卸载的旧 classloader 上，`renderState` 读语言包 →
   `SettingPersistentState.getInstance()` 拿到新 loader 实例 → 跨 loader CCE。修法：构造期捕获文案、渲染只读快照 +
   版本标签就地刷新 + 兜底收尾。见 [[pitfall-self-update-callback-must-be-service-free]]。
2. **MCP 起不来（`A child container failed during start`）**——`URL.setURLStreamHandlerFactory` JVM 级一次性，
   热替换后新 loader 的 Tomcat 再注册抛 `factory already defined`。修法：`TomcatUrlFactoryCompat` 预 `disable()`
   + 冲突重试，静态资源服务器同源处理。见 [[pitfall-tomcat-url-factory-jvm-once-after-swap]]。

读数：update 17 类 + mcp 5 类 **155/0/0**；反向探针必红（首轮假绿=gradle 未重编，`javap -c` 核对后才算数）。
发布：release `2026.11.10`（id 1292416），清单双份已指向 2026.11.10 并匿名 raw 回读确认；插件仓提交
`cb268a05`（代码）+ `166d7d7c`（计划册 10.8），song 分支未推送远端。

## 测试包与版本口径（2026-11-10）

- **2026.11.9 不含两处修复**：发行提交 `7fb5e0b2` 早于修复 `cb268a05`（实测无 `TomcatUrlFactoryCompat`、`renderState` 仍 7 处查语言包）→ **2026.11.10 才是首个含修复版本**（release id 1292416，清单双份已指向它）。
- 本地产物（`build/distributions/`）四件：`cool-request-plugin.zip` = 2026.11.10 正式（与线上附件同 sha256）；`-2026.11.10-test.zip` 同字节改名；`-2026.11.8-lowtest.zip` = 版本号 2026.11.8 + 当前代码，专供测更新；`-2026.11.8-test.zip` = 用户 11-09 旧包（旧代码，含两个 bug）。
- 低版本测试包做法、`-test` 后缀含义与降级安装限制见 [[feedback-test-build-lowversion-package-recipe]]。

## 2026-11-10 第三轮：设置对话框「确定」点不动（版本 2026.11.11）

用户真机栈点名两处，同一根因（旧 loader 取应用级服务）：
1. `SettingsEditor.doOKAction` → `ConfigurableBase.isModified` → **`UpdateConfigurable.getSettings()`**
   → `SettingPersistentState.getInstance()` → 跨 loader CCE，异常打断对话框关闭（「确定没反应，只有取消可用」）。
   修法：`getSettings()` 返回**脱离持久化**的 `new SettingsState()`；成功收尾时 `setOKActionEnabled(false)`
   ＋ 1.5s 后向祖先窗口派发 `WINDOW_CLOSING`（取消语义，不触发 apply）；本页 `apply/reset` 加 `selfUpdated` 守卫。
2. 工具栏 `GenerateCommitMessageAction.update` 同样崩在 `getInstance()`：`getInstance()`/`getInstance(project)`
   加跨 loader 兜底（不落盘的进程内兜底实例），让旧 action 安静退场。
详见 [[pitfall-self-update-callback-must-be-service-free]] 的「第二条路径」节。

读数：update + mcp 兼容层定向 **115/0/0**；反向探针（`getSettings()` 改回取应用级服务）必红（javap 核对产物后取证）。
发布 release **2026.11.11**（id 1292491，52,270,708 B，sha256 `c95b70df…`），清单双份指向并匿名 raw 逐字节比对一致。

## 发布与提交台账（2026-11-09~11）

- 插件仓（song，**均未推远端**）：`6fd000ff` 版本常量对齐（2026.10.1→2026.11.1，D-9 被用户推翻：两处必须同步）→
  `cb268a05` .10 两修 → `166d7d7c` 计划册 10.8 → `8dfb8586` .11 三修 + 计划册 10.9。
- 渠道仓（master，已推）：`75dc82d` 修正提交身份（见 [[pitfall-commit-identity-wrong-email-owns-attribution]]）→
  `4fb2e50` 发布脚本令牌路径三级回退 → `1043e2a` 清单 .10 → `7c77c55` 清单 .11。
- Release 序列：2026.11.1（id 1289879）→ 2026.11.10（1292416）→ 2026.11.11（1292491）；**2026.11.9（7fb5e0b2）不含任何热更新修复**。
- **版本真源**：`build.gradle.kts` 的 `version=""`，唯一真源是 plugin.xml `<version>`；buildPlugin 产物名固定
  `cool-request-plugin.zip`（不带版本）→ 由发布脚本改名 `cool-request-plugin-<version>.zip` 上传。
- **发布脚本令牌查找**：`--token-file` > 环境变量 `COOLREQUEST_GITEE_TOKEN_FILE` > 插件仓 `.local/gitee-token.json` 候选
  （原相对路径上跳四级算错，指向 `F:\code\Java\Java\...`，已修）。
- **清单更新口径变更**：README 曾写「清单只有所有者能改」，但为让修复真正下发，助手已代改 `update/preview.update.json`
  与 `update/update.json`（每次发布同步、附 sha256 与匿名 raw 比对）——若要恢复「仅所有者手改」，需用户明示。


## 2026-11-10 收尾状态（用户确认问题已解决）

- **渠道仓只剩两个发行版**：`2026.11.1`（老版，保底/测更新用）+ `2026.11.10`（**id 1292879，现行版**）。
  已删除：`2026.11.9`、旧 `2026.11.10`、`2026.11.11`（release 与 tag 都清；Gitee v5 无删 tag 的 API，
  用 `git push <url> :refs/tags/<t>` 删、`git tag -f` + `push -f` 移）。删前三个包备份在插件仓
  `build/release-backup/`（git 忽略）。
- **版本号钉在 2026.11.10**（plugin.xml + 常量）：虽然内容变过，用户选择沿用同号重发；代价是
  「同号不能覆盖安装」，必须卸载后再装。
- **累计五项修复**（提交 `cb268a05` / `8dfb8586` / `c6d6896b`，song 分支未推远端）：
  ① 更新页收尾不触插件服务（跨 loader CCE）；② MCP/静态服务器 Tomcat URL 工厂预禁用+重试；
  ③ 「确定」点不动（UpdateConfigurable 脱持久化 + 置灰延时关窗 + getInstance 兜底）；
  ④ 版本改读**已加载描述符**（装盘未重启时不再拿磁盘版本误报可更新）；
  ⑤ 拒绝原因给可操作中文提示 + 进度条 setIndeterminate(false)。
- **低版本测试包**按「当前代码 + 低版本号」出，命名 `<版本>-lowtest.zip`（用户要装旧版测更新时必须
  含最新代码，否则跑的是旧代码的安装路径）。装完**必须重启**再测，否则 IDE 的「挂起插件变更」会
  把热替换挡回去（平台级判定，插件无法绕过）。


## 推送约定（2026-11-10 用户纠正）

**推 GitHub 一律走 token，禁用凭证助手/GCM**——这轮裸 `git push origin song` 触发了 GCM 弹窗，用户是手动点的。
正路：`git -c credential.helper= push "https://x-access-token:<token>@github.com/houxinlin/coolrequest-vip.git" <ref>`。
令牌已落到本仓 `.local/github-token.json`（账号 SongRenShuo，gho_ 前缀，实测对 houxinlin/coolrequest-vip `push: true`，
`.local/*` 已 gitignore）；调 GitHub API 也直接读它，别用 `git credential fill`。

相关：[[project-dev-update-channel-20261106]]、[[project-release-20261106-beta2]]（发布流程改编源）。
