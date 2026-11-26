# T-OpenIDEAPort-P0：建仓与引擎导出（T0-0 / T0-1）立项

- 立项日期：2026-11-25
- 依据：`docs/调研报告/20261125-1336-OpenIDEA全面支持-调研报告.md`、`docs/开发计划/20261125-1400-T-OpenIDEAPort-OpenIDEA插件复刻-开发计划.md` §2/T0-0/T0-1
- 新仓：`F:\code\Java\Gitee\SongRenShuo\openidea-mybatis-plus-sql-visualizer`（本地空目录已建、记忆已播种；远端 Gitee `songrenshuo/openidea-mybatis-plus-sql-visualizer` + GitHub `SongRenShuo/openidea-mybatis-plus-sql-visualizer`）
- 命名口径：仓库名用 `openidea-` 前缀；`plugin.toml` 的 `id` 用产品名 `songrenshuo.mybatis-plus-sql-visualizer`
- 阶段：P0 骨架；本批只做 T0-0 与 T0-1，**不做** engine-jar 与新仓的联调（留给 P5）

## 1 T0-0：主仓 `sub-projects/engine-jar` 导出模块

### 目标

把 08 分册 D1 的 P0/P1 纯逻辑（约 26,168 行）打成**不含 IntelliJ 类型**的 jar，供新仓 `process:java` 调用；不改这些源码，只新增构建模块。

### 范围

| 项 | 内容 |
| --- | --- |
| 新模块 | `settings.gradle.kts` 增加 `include(":engine-jar")`，projectDir 指向 `sub-projects/engine-jar` |
| 源集 | `srcDirs` 指回现有源码目录，只包含纯包：`model`、`sql/**`、`xml/**`、`reverse`、`generator`、`psource`（NamedParamParser 等纯类）、`util`（纯工具类）、`converter` 的纯部分（按 08 分册 D1 清单逐包圈定） |
| 排除 | 任何 import `com.intellij.*` 的文件（构建前用脚本扫描并**失败**，不是警告） |
| 依赖 | 仅 `jsqlparser`（Apache-2.0 分支，附 NOTICE）；**不得**依赖平台 jar、Kotlin 平台库 |
| 产物 | `mpsv-engine-<version>.jar` + `.sha256`，version 取自主仓 `pluginVersion` 或独立 `engineVersion`（建议独立，避免与插件版本绑死） |
| 入口 | 一个极薄的 `EngineApi` 门面：`render(RenderRequest): RenderResponse`、`perf(sql, dialect)`、`renderLogs(text, opts)`，入参出参用 JSON 字符串（先用 Gson/Jackson 之一，或手写） |
| CI | 新增 `engineJar` 任务并在 push 到 master 时归档产物（先只做本地任务，CI 接线留给发行阶段） |

### 验收

1. `./gradlew :engine-jar:jar` 成功，产物 `build/libs/mpsv-engine-*.jar`。
2. 产物**零平台依赖**：`jdeps` 或 `unzip -l` + 逐包扫描无 `com/intellij`；`gradle dependencies --configuration runtimeClasspath` 只出现 jsqlparser 及其自身依赖。
3. 冒烟：用 `java -cp mpsv-engine.jar` 跑 `EngineApi.render` 渲染 3 个既有语料（XML 动态 SQL、Wrapper 中间模型 JSON、日志还原），输出与主仓测试期望一致。
4. `git status` 只含新增目录与 `settings.gradle.kts` 一行；**不改任何生产源码**。

### 约束

- 当前有活跃并行任务 `mapper-method-preview`（sandbox `mpsv-mappreview`）；跑 gradle 前先 `parallel_tasks.py register --name engine-jar --sandbox mpsv-enginejar`，跑完 `done --name engine-jar`（禁裸 done）。
- 长命令（gradle 构建/测试）一律后台跑；不跑全量测试、不 `clean`、不 `--stop`。

## 2 T0-1：新仓骨架

### 目标

新仓可直接 `openidea-plugin build` + `check` + `link` 跑通，形成最小闭环（一个命令渲染一段 XML 片段）。

### 交付文件

```
plugin.toml                 # id=songrenshuo.mybatis-plus-sql-visualizer, engines.openidea="^1.15",
                            # capabilities=[project.read], activation=[on-command:*]
Cargo.toml                  # openidea-plugin = { git=..., tag="v0.1.51" }, crate-type=[cdylib]
rust-toolchain.toml         # 1.98.1（与 CI 一致）
.nvmdrc                     # 22.22.3
.gitignore                  # /target /dist *.oiplugin
README.md                   # 功能、每个高危能力的必要性（市场审核要求）
CHANGELOG.md                # [Unreleased] 骨架
LICENSE / NOTICE            # Apache-2.0 + JSqlParser 双许可声明（择 Apache-2.0 分支）
src/lib.rs                  # register_plugin! + 一个命令 render_xml_snippet（读编辑器选区，输出到 output tab）
src/commands/render_xml.rs
web/.gitkeep
locales/{en,zh-CN}.json     # 至少一条命令标题的对照
icons/.gitkeep
engine/ENGINE_VERSION       # 先写 "0.0.0-pending"
.github/workflows/build.yml # fmt/clippy/test/build/check
.worktrees/.gitkeep（gitignore 内）
docs/LIMITS.md              # 19 条不可行清单摘要（对用户明示）
```

### 验收

1. `openidea-plugin check .` 通过（清单、贡献、SVG、组件导入面、函数签名）。
2. `openidea-plugin build` 产出 `dist/plugin.wasm`；`openidea-plugin link .` 后设置 › 插件 › Reload 可见命令。
3. `cargo fmt --check`、`cargo clippy -- -D warnings`、`cargo test` 通过。
4. 命令在真机点一次，能读当前 XML 选区并输出结果（哪怕只是原样回显），证明端到端链路通。

### 约束

- **不要** `git init`、不要建远端、不要 push（远端由主控统一建与推）。
- 只用 `openidea-plugin` 0.1.51（`~/.cargo/bin`）生成/校验，避免 SDK 落到 1.16。
- 长命令后台跑；不跑 `cargo build` 的全 workspace（只有本 crate）。

## 3 远端与双仓

| 远端 | 仓库 | 方式 |
| --- | --- | --- |
| Gitee | `songrenshuo/openidea-mybatis-plus-sql-visualizer` | 用户当前在 Gitee「新建项目」页，走内置浏览器创建 |
| GitHub | `SongRenShuo/openidea-mybatis-plus-sql-visualizer` | 令牌 API 创建（classic PAT，scopes=`repo,workflow`，无需加权限） |

初始提交与推送在 T0-1 交付后由主控执行，遵守仓 AGENTS.md 规则 10（GitHub 走令牌，禁裸 `git push github`）。

### 落地状态（2026-11-25）

| 项 | 结果 |
| --- | --- |
| Gitee 仓库 | 已建（内置浏览器表单，公开、未初始化）；`songrenshuo/openidea-mybatis-plus-sql-visualizer` |
| GitHub 仓库 | 已建（classic PAT API，公开、未初始化）；`SongRenShuo/openidea-mybatis-plus-sql-visualizer` |
| 首次提交 | `e2e6e38` feat: 初始化 OpenIDEA 插件骨架（T0-1），21 个文件 |
| 双推 | `origin`（Gitee）与 `github`（GitHub）均 `master` 已建；两仓 API 回读分支 `master` 均在 |
| GitHub 凭据 | `gh auth setup-git` 已装 hosts 级助手，推送走 `gh auth git-credential`，无 GCM 弹窗 |
| Gitee 令牌 | **仓库级令牌（选择仓库 6→7）**：需在令牌编辑页把新仓加入范围，提交时账号密码二次验证（用户本人完成），令牌串不变 |
| T0-0 | 完成（`b3a8aeb9a`）：`sub-projects/engine-jar`，151 文件投影、jar 4,543,729 B + sha256，`verifyNoPlatformImports` 扫 2059 class 零 `com/intellij`，15 方言齐全，冒烟三例通过 |
| 主控复核 | 15 个具体方言类在 jar 内（Dm/GaussDb/GoldenDb/H2/HighGo/KingBase/MariaDb/MySql/OceanBase/OpenGauss/Oracle/PostgreSQL/SqlServer/Sqlite/Vastbase）；`DialectRegistry` 保留 `getDialects()`/`byName()`、`decision` 已按设计剔除；冒烟复跑输出与子代理一致 |

### T0-0 遗留（交 P5 处理）

1. **投影规则是构建期文本变换**：`sql/Dialect.kt` 截断、`LogSqlModels`/`SqlSourceMarker` 删段、`MpSqlVisualizerBundle` 替换、`SecureXml` logger→no-op。已带结构守卫（找不到截断标记即构建失败），但生产文件重构时必须同步改规则；P5 接入后要补一条「投影后行为对拍」（同一输入，jar 输出与主仓测试期望一致）。
2. **`messageKey` 未本地化**：jar 不带 `messages/*.properties`，`perfAdvice` 返回的是键名（如 `perf.leading_wildcard`）。新仓必须自带 `messageKey → 文案` 映射（可复用主仓 1557 键里的相关子集），否则用户看到的是键名。
3. **CI 未归档产物**：目前只有本地 `jar` 任务，`engineVersion` 独立于插件版本。P5 需要把产物挂到主仓 Release 或独立分发，新仓 `ENGINE_VERSION` 才可钉版本 + 校验 sha256。