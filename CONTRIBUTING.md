# 参与贡献

感谢你愿意为 SQL Visualizer 提交代码、文档或问题反馈。本文说明本仓库的分支模型、开发环境、测试门槛、代码风格与 PR 流程。提交前请至少读完「分支与 PR」「构建与测试」「代码与文案约定」三节。

## 仓库位置

本仓库双平台同步：**Gitee 是主仓**（发行与自更新清单的权威源），**GitHub 是镜像**。两边内容一致，PR 两个平台都收，你可以就近选择。

| 平台 | 地址 | 说明 |
|---|---|---|
| Gitee（主仓） | <https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer> | 发行版与插件内更新清单指向这里 |
| GitHub（镜像） | <https://github.com/SongRenShuo/mybatis-plus-sql-visualizer> | 与 Gitee 同步，源码链接填的是这个 |

## 许可与行为

- 本项目以 Apache License 2.0 发布，见 [LICENSE](LICENSE)。提交 PR 即表示你同意贡献内容按该许可发布；无需签署 CLA。
- 请围绕问题本身讨论，不人身攻击，不泄露他人隐私或未授权的商业代码。
- 安全问题不要公开提 Issue，请通过 README「联系作者」中的方式私下联系维护者。

## 先讨论，再动手

- 新功能、行为变更、架构调整：先开 Issue 说明使用场景与方案，确认方向后再写代码，避免大 PR 返工。使用问题与功能建议优先走[官网需求池](https://sql-visualizer.jvtools.cn/about/feedback)，那里有统一的排期视图。
- 较大的改动按仓库惯例先写开发计划：`docs/开发计划/{YYYYMMDD}-{HHMM}-{功能名}-开发计划.md`，写清目标、方案、影响面与验证方式；实施完成后在同一文档回填实施记录和测试读数，并在 `docs/变更记录.md` 登记任务级变更。
- 拼写、文档、明确的 bug、小范围修复：可以直接提 PR。
- 一个 PR 只做一件事；不要把格式化、重命名和功能改动混在一起。

## 开发环境

| 项 | 要求 |
|---|---|
| JDK | 17（Kotlin `jvmToolchain(17)`） |
| 构建 | 项目自带 Gradle Wrapper（`./gradlew` / `gradlew.bat`） |
| IDE 编译基线 | IntelliJ IDEA Ultimate 2024.3.7.1 |
| 日常 IDE | 2024.3 及以上；2026.x 也可运行 |
| 平台 | Windows / macOS / Linux 均可；仓库脚本以 Windows + Git Bash 为主要验证环境 |

首次构建会下载 IntelliJ Platform 与依赖，耗时和磁盘占用都较大，属正常现象。平台依赖下载失败时检查网络/代理配置，不要删除或改写 `gradle.properties` 中的版本基线。

第一次接触代码，建议按《[架构总览](docs/架构设计/01-项目架构总览.md)》→《[核心流程详解](docs/架构设计/02-核心流程详解.md)》→《[开发与测试指南](docs/架构设计/03-开发与测试指南.md)》的顺序阅读，再挑一条链路跟着测试类走一遍。

## 分支与 PR

- `master` 是发布分支，`dev` 是在途集成分支。外部 PR 请以 `dev` 为基分支；`master` 只由维护者在发版时合入。若 `dev` 落后于 `master`，先基于最新 `dev` 建分支，维护者会在合入前处理同步。
- Fork 后从 `dev` 切分支，分支名用 `feature/<简短英文名>`、`fix/<简短英文名>` 或 `docs/<简短英文名>`。下面的示例以 Gitee 为准，换成 GitHub 时把 `gitee.com/<你的用户名>` 与 `gitee.com/songrenshuo` 分别改为 `github.com/<你的用户名>` 与 `github.com/SongRenShuo` 即可：

  ```bash
  git clone https://gitee.com/<你的用户名>/mybatis-plus-sql-visualizer.git
  cd mybatis-plus-sql-visualizer
  git remote add upstream https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer.git
  git fetch upstream
  git checkout -b feature/<简短英文名> upstream/dev
  ```
- 分支尽量短生命周期；合并后由维护者删除。
- PR 描述请按 [PR 模板](.github/PULL_REQUEST_TEMPLATE.md) 填写：改了什么、为什么、怎么验证、兼容性影响、UI 截图（如有）。Gitee 侧的同款模板在 [.gitee/PULL_REQUEST_TEMPLATE.zh-CN.md](.gitee/PULL_REQUEST_TEMPLATE.zh-CN.md)。
- 提交信息格式：

  ```
  type(scope): 摘要
  ```

  `type` 使用 `feat` / `fix` / `docs` / `test` / `refactor` / `perf` / `chore`；`scope` 可省略，常用 `preview` / `log` / `mcp` / `test` / `架构设计`。摘要用中文或英文均可，但同一 PR 内保持一致；一个提交只做一件事。
- 不要自行修改 `gradle.properties` 的版本号、`CHANGELOG.md` 的发行段落或 `update/update.json`；这些由维护者在发版时处理。行为变更可在 PR 中说明建议的变更记录文案。

## 构建与测试

常用命令：

| 命令 | 用途 |
|---|---|
| `./gradlew testFast` | 纯逻辑快测（秒级） |
| `./gradlew test --tests "完整类名"` | 定向测试 |
| `./gradlew testShards` | 全量分片测试（默认 5 片，含 testExternal 与 agent 子项目） |
| `./gradlew testDatabase` | DB 集成测试（需本机 MySQL + demo 工程） |
| `./gradlew runIde` | 启动开发沙盒 |
| `./gradlew buildPlugin` | 生成插件 ZIP |
| `./gradlew verifyGate` | 三目标平台兼容门禁 |
| `./gradlew verifyDemoFixture` | 检查测试 demo 是否就绪（`testShards` / `testDatabase` 会自动先跑） |
| `powershell -File dev-support/test-demo/setup-test-env.ps1` | 准备测试 demo 与本地数据库（Windows；bash 同名 `.sh`） |

要求：

- 改纯逻辑（`model` / `converter` / `sql` / `xml` / `reverse` / `generator` / `util`）至少跑 `testFast`。
- 改平台相关代码（PSI、UI、Action、日志捕获）跑对应 `test --tests`；PR 中贴命令与 `X/Y/Z` 读数（用例数/失败数/错误数）。
- 改 DB 链跑对应 `testDb*`；本机没有 MySQL 时在 PR 中如实说明「未跑 DB 集成」，不要用 headless 结果冒充真库验证。
- 全量 `testShards` 与 `releaseGate` 由维护者在合入前收口；外部贡献者不要求跑全量，但请确保定向测试真实通过。
- 失败先复跑确认是否稳定，不要把 flaky 当通过。测试计数异常（如断言数 4→5、`NoSuchMethodError`）优先怀疑增量编译残留，可对该模块执行 `--rerun-tasks`。
- 新增测试类自动进分片；测试类命名 `*Test.kt`，JUnit 4；纯逻辑测试不要引入平台 fixture。
- 缺陷修复优先先补一个能复现问题的测试，再改生产代码；说不清怎么复现时，在 PR 中说明原因。
- 守卫/扫描类规则改动必须做反向验证：临时移除修复分支后测试必须变红，再恢复并贴出两次读数。只证明「规则看得见我造的样本」不算数。

更细的分片、沙盒与并行约定见《[开发与测试指南](docs/架构设计/03-开发与测试指南.md)》。

## 全量测试前置：测试 demo 与本地数据库

**只克隆插件仓库直接跑 `testShards` 会失败。** 分片里有 5 个 `Demo*` 用例会硬断言兄弟目录 `../mybatis-plus-sql-visualizer-demo` 下的真实源码存在；`testDatabase` / `testDb*` 和 `e2eFakeIde` 还需要本地 MySQL。`testFast` 不需要这些前置。

测试 demo 是独立仓库 [mybatis-plus-sql-visualizer-demo](https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer-demo)（MIT，[GitHub 镜像](https://github.com/SongRenShuo/mybatis-plus-sql-visualizer-demo)），包含 MP / MPJ / JPA / 多 ORM 等各主题的 Spring Boot 示例工程；插件仓库不复制它的源码，只钉定版本。

| 测试入口 | 测试 demo | 本地 MySQL | 缺省行为 |
|---|---|---|---|
| `./gradlew testFast` | 不需要 | 不需要 | 直接跑 |
| `./gradlew test --tests "…"` | 看用例 | 看用例 | 普通平台测试不需要；`Demo*` 普查类需要 demo |
| `./gradlew testShards` | **需要** | 不需要 | 缺 demo 时 `Demo*` 用例失败 |
| `./gradlew testDatabase` / `testDb*` | **需要** | **需要** `mpj_demo` | 缺库时多数用例显式跳过 |
| `./gradlew e2eFakeIde` | **需要**（含 Maven 与 `~/.m2`） | **需要** `mpj_demo` + 初始数据 | 前置缺失硬失败并给指引 |

### 准备 demo 与数据库

推荐一键设置（会 clone/校验 demo、安装 `.idea/dataSources*.xml` 模板，并在 `mysql` 客户端可用时建库）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File dev-support/test-demo/setup-test-env.ps1
```

```bash
bash dev-support/test-demo/setup-test-env.sh
```

只检查、不改环境：PowerShell 加 `-CheckOnly`，Bash 使用 `CHECK_ONLY=1`。只准备 demo 不建库：`-SkipDb` / `SKIP_DB=1`。如果 `mysql` 客户端不在 PATH（phpstudy、XAMPP 等自带客户端常见），PowerShell 加 `-MysqlBin <.../mysql.exe>`，Bash 用 `MYSQL_BIN=<.../mysql.exe>`；密码默认取 `MYSQL_PWD`，也可用 `-DbPassword` / `MYSQL_PASSWORD` 传入。

`testShards` 与 `testDatabase` 会先执行 `verifyDemoFixture`：缺 demo 时在测试启动前失败并给出上述设置命令，不会等 5 个分片各跑一遍才在 `Demo*` 断言上报红。确实要跳过预检时加 `-PskipDemoFixtureCheck=true`（测试仍可能因缺 demo 失败）。

demo 默认放在插件仓库的兄弟目录，版本钉在 `dev-support/test-demo/demo-revision.txt`。手动检出：

```bash
git clone https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer-demo.git \
  ../mybatis-plus-sql-visualizer-demo
# 或 GitHub 镜像（内容一致）
git clone https://github.com/SongRenShuo/mybatis-plus-sql-visualizer-demo.git \
  ../mybatis-plus-sql-visualizer-demo
```

如果放在别处，用 `-PmpDemoProjectDir=<路径>`（`testShards` / `testDatabase`）或环境变量 `MPSV_DEMO_ROOT`（`e2eFakeIde`）覆盖。

### 数据库与 SQL 脚本

当前测试只依赖两个库，脚本已随仓库提供：

| 数据库 | 脚本 | 用途 |
|---|---|---|
| `mpj_demo` | `dev-support/test-demo/db/mpj_demo.sql` | `testDatabase` / `testDb*` 与 `e2eFakeIde` 主库 |
| `mp_demo` | `dev-support/test-demo/db/mp_demo.sql` | 部分数据源名/库名语料 |

手工初始化（MySQL 5.7+/8.0，默认 `127.0.0.1:3306`、`root/root`）：

```bash
mysql -h127.0.0.1 -P3306 -uroot -p \
  -e "CREATE DATABASE IF NOT EXISTS mpj_demo DEFAULT CHARSET utf8mb4; CREATE DATABASE IF NOT EXISTS mp_demo DEFAULT CHARSET utf8mb4;"

mysql -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 \
  mpj_demo < dev-support/test-demo/db/mpj_demo.sql

mysql -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 \
  mp_demo < dev-support/test-demo/db/mp_demo.sql
```

脚本自带 `DROP TABLE`，可重复执行，但会清掉这两个库里的同名表，不要指向有业务数据的库。`testDatabase` 读取 demo 的 `.idea/dataSources.xml` 获取 JDBC URL；设置脚本会从 `dev-support/test-demo/templates/` 安装一份最小模板，已存在则不覆盖。

完整的 demo 模块清单、路径覆盖、排障表和快照更新步骤见 [`dev-support/test-demo/README.md`](dev-support/test-demo/README.md)。

## 代码与文案约定

- 以周边代码为准：命名、缩进、注释密度、异常处理方式保持一致；不要整文件重排。
- 分层：纯逻辑层（`model` / `converter` / `sql` / `xml` / `reverse` / `generator` / `util`）不得依赖 PSI 或 IDE 平台类；平台读取放 `psi` / `ui` / `log`。
- 注释：本仓库以中文 KDoc 为主，KDoc 应说明约束与踩坑背景；新增代码可用中文或英文，但同一文件保持一致。
- 不在代码里留 `TODO` / `FIXME`；待办写进 `docs/开发计划/`（命名见 [文档命名规范](docs/规范/文档命名规范-说明文档.md)）。
- 用户可见文案必须走 i18n：`src/main/resources/messages/MpSqlVisualizerBundle.properties`（英文）与 `_zh`（中文）同时补齐；`BundleParityGuardTest` 会检查键一致。
- 平台 API：不要引用 Internal API；新增反射入口须登记 `PlatformSignatureCrossVersionTest`；可选依赖（Database / Diagrams / Kotlin / MCP / AI Assistant）必须走 optional XML + 桩源集隔离，主源集不得直接引用。
- UI：优先复用现有组件与面板内嵌提示；新增弹窗需说明必要性（见[弹窗克制红线](docs/规范/设计规范-弹窗克制红线.md)）。UI 改动附沙盒截图。
- 安全：不要提交密钥、token、真实连接串、绝对路径或构建产物；示例数据使用公开测试库。
- AI 辅助可以，但提交者需对代码、测试与许可证负责；不要提交自己未理解的生成代码。

## 文档与变更记录

- 任务级变更记录：`docs/变更记录.md`（维护者收口时更新）。
- 版本级变更：`CHANGELOG.md`（维护者发版时更新）。
- 架构/流程变更：同步 `docs/架构设计/01-项目架构总览.md`、`02-核心流程详解.md`、`03-开发与测试指南.md`。
- 新功能使用说明：放 `docs/架构设计/` 或 `docs/需求文档/`，按现有命名规范。
- 文档只写当前真实行为；不要把计划中的能力写成已实现。
- 文档用自然中文，避免 emoji 标题和空泛表述；技术结论尽量附命令、读数或「文件:行号」出处。

## 并行开发与工作树

仓库日常使用多工作树并行开发。外部贡献者只需保证自己的 clone 独立即可；若同时维护多个分支：

- 每个分支使用独立 clone 或 `git worktree`，不要在同一工作树切换分支跑构建。
- 不要停止他人的 Gradle daemon 或删除共享缓存。
- 内存有限时串行跑构建；全量分片测试一次只跑一份。
- 同一文件多人修改时只动自己的行，避免大范围格式化造成冲突。

## 评审与合并

- 维护者会检查：行为是否正确、测试是否真实覆盖、是否破坏 243/253/262 兼容、是否补齐 i18n 与文档。
- 可能要求补测试、拆小 PR、压缩提交或改写提交信息；请按评审意见更新同一分支，不要反复新开 PR。
- 合并方式由维护者决定（普通合并或压缩合并）；合并后你的提交可能以维护者整理后的形式出现在 `master`。
- 当前仓库没有强制 CI 门禁，PR 中的测试命令与读数是评审的主要依据，请如实填写。

## 问题反馈

- 使用问题、功能建议：优先走[官网需求池](https://sql-visualizer.jvtools.cn/about/feedback)。两个平台的 Issue 区都置顶了这条引导，那里能看到统一的处理进度。
- 开发相关缺陷、可复现的代码问题：可在 [Gitee](https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/issues) 或 [GitHub](https://github.com/SongRenShuo/mybatis-plus-sql-visualizer/issues) 提 Issue，按对应平台的模板填写（`.gitee/ISSUE_TEMPLATE/` / `.github/ISSUE_TEMPLATE/`）。
- 提交 Issue 前先搜索是否已有同类问题；附上插件版本、IDE 版本、复现步骤、期望/实际结果和相关日志。
