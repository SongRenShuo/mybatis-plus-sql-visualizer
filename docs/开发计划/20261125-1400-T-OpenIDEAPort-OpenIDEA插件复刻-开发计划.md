# T-OpenIDEAPort：在 OpenIDEA 上复刻 MyBatis-Plus SQL Visualizer 开发计划

- 日期：2026-11-25
- 依据：`docs/调研报告/20261125-1336-OpenIDEA全面支持-调研报告.md` 与分册 `docs/调研报告/20261125-openidea-support/`（00~11）
- 目标读者：实施者（新仓从零开发）与本仓维护者（决定迁移边界与节奏）

## 0 目标与边界

### 0.1 目标

在一个**全新独立仓库**里，用 OpenIDEA 插件体系（Rust WASM 组件，`engines.openidea = "^1.15"`）交付一个可用产品。范围按调研的四档划分：

| 档位 | 条数 | 本计划处置 |
| --- | ---: | --- |
| 现成可做 | 98 | 全部纳入 |
| 变通可做 | 83 | 纳入主干；其中依赖类型推断的按「不猜」原则降级（带 confidence） |
| 需上游 | 1 | 先做替代（`Save console output to file` + 读文件），同时提 PR |
| 不可行 | 19 | 不迁移，在文档与设置页明确「本平台不支持」并给出替代路径 |

### 0.2 明确不迁移（19 条）

F13 CriteriaQuery 链、F21 MBG 调用点还原、F26 数据库表节点右键、F59 MBG 条件还原、F64 CriteriaQuery、F65 Specification lambda、F95 平台方言注册、F98 方言映射到 IDE SQL 语言、F131 源码拦截器模板注入、F142 JOIN 虚拟外键注入、F145 数据源下拉接管、F147 执行文档写入纪律、F175 ACP 会话 MCP 传输、F178 设置搜索索引、F182 自建更新三通道、F187 workspace.xml 自愈、F196 iBatis DTD 离线映射、F200 编辑器缩进偏好、F201 竞品共存让位。

### 0.3 产品定位（调研结论的直接推论）

- 主卖点：**SQL 渲染 + 方言校验 + 写安全预检/回滚 + 日志还原（项目感知）+ 11 个 MCP 工具**。这五块在 OpenIDEA 市场无人做，且大多不受「无类型解析」限制。
- 次卖点：Mapper 双向导航。已商品化（官方 MyBatis Navigator 0.4.2、MyBatis Duo 0.3.6），且宿主不去重两枚图标都会画——**默认关闭**，作为设置项提供。
- 不碰：mapper XML 的 SQL 着色/表列补全（宿主 PR #3641 已内置）。

## 1 待验证前提（动手前先证伪）

| # | 前提 | 怎么验 | 若不成立的后果 |
| --- | --- | --- | --- |
| P-1 | `symbols("User.")` 能列出实体字段名 | 在 262/1.15 宿主用最小插件打日志；或读 `crates/ide-index/src/matching.rs` 的单测 | 实体→列名映射退化为纯文件名启发，F25/F51/F150/F170 精度再降一档 |
| P-2 | 50 ms 预算内能完成单文档增量解析 | 用 2,000 行 XML + 800 行 Java 实测 provider 延迟 p95 | 需把 gutter 触发面缩到「仅 XML」或改「命令式触发」 |
| P-3 | `process:java` 指向包内 jar 可被批准运行 | 用 JPA Power Tools 同款做法实测一次安装→授权→运行 | sidecar 方案降级为 GraalVM native，或全 Rust |
| P-4 | `websocket-ready` 事件在 1.15 真机可用 | 最小插件连本地 ws:// 服务，收帧 | 捕获链退化为 HTTP 轮询，实时性下降 |
| P-5 | `injections` 与宿主 PR #3641 的内置 SQL 支持不打架 | 真机在 mapper XML 上同时开两者 | 放弃 injections，只保留我方渲染 |
| P-6 | 自建索引（`openidea-plugin index` + `merge --keep`）在中国网络可用 | 用 Gitee Pages 托管 index.json 实测安装/更新 | 只走官方注册表（要求开源） |
| P-7 | 宿主在 1.15 的 `documentIndexer` 图能驱动 Ctrl+B 与 gutter | 最小插件建一个假 Mapper 图 | 导航退回 provider 现算，大项目可能超时 |

## 2 仓库结构

```
openidea-mybatis-sql-visualizer/
├─ Cargo.toml                  # workspace；成员：crate/、sidecar-java/（非 cargo）
├─ plugin.toml                 # id = "<publisher>.mybatis-sql-visualizer"，engines.openidea = "^1.15"
├─ rust-toolchain.toml         # 钉 stable 版本，与 CI 一致
├─ .nvmdrc                     # Node 版本锁定（NVM Desktop 目录级切换）：22.22.3
├─ .gitignore                  # /target、/dist、sidecar-java/build
├─ README.md                   # 功能、每个高危能力的必要性说明（市场审核要求）
├─ CHANGELOG.md                # 单一事实源，change-notes 由它生成
├─ LICENSE / NOTICE            # Apache-2.0 + 第三方清单（含 JSqlParser 双许可分支声明）
├─ crate/                      # Rust 插件本体（wasm32-wasip2）
│  ├─ src/
│  │  ├─ lib.rs                # register_plugin! + 生命周期
│  │  ├─ commands/             # 9 个 Action 等价物
│  │  ├─ providers/            # definition / line-markers / completion / inlay / diagnostics / injections
│  │  ├─ indexer/              # Mapper↔XML 跨文件关联图（documentIndexer）
│  │  ├─ views/                # 日志面板 / 预览工作台 / 设置页数据
│  │  ├─ core/                 # 纯逻辑：model、xml、sql、dialect、log、rules、generate、reverse
│  │  ├─ sidecar/              # process:java 生命周期与 JSON 协议
│  │  └─ mcp/                  # 11 个工具声明 + mcp-provider 实现
│  └─ tests/                   # 对拍主仓语料的期望输出
├─ web/                        # 预览工作台/EXPLAIN 视图页面（sandbox iframe）
├─ locales/                    # zh-CN.json / en.json
├─ icons/                      # markerIcons、fileIcons、toolbar（亮暗双份）
├─ engine/                     # 引擎产物引用：ENGINE_VERSION（版本号 + sha256），jar 不入库
├─ sidecar/                    # Rust 侧：JSON 协议、进程管理、降级判定（不含 Java 代码）
├─ .worktrees/                 # 并行开发工作树（gitignore，不进包）
├─ docs/                       # 用户文档、能力说明、限制清单
└─ .github/workflows/          # build → test → check → pack → release
```

要点：

1. **`crate/` 与 `engine/` 分离**：Java 引擎不入新仓源码树，而是由主仓 `sub-projects/engine-jar` 导出为带版本号的 jar（源集指向现有源码、按纯包圈定），新仓用 `engine/ENGINE_VERSION` 钉版本与校验和，发布流水线从主仓 release 拉取并校验。
2. 主仓的 Kotlin 代码**不复制**（复制必然分叉）；只把 08 分册 D1 的 P0/P1 清单（26,168 行）圈定为 `engine-jar` 的内容边界。
3. `engines.openidea` 写 `^1.15`；1.16 的 `frames`/`markdown_hover`/`customEditors.binary` 不得使用。
4. 语料不搬测试代码，抽成语言中立的 `cases.jsonl`（输入 + 期望输出 + 方言 + 置信度），两仓共用对拍。
5. 并行开发按主仓惯例用 `.worktrees/`（已忽略）；`cargo` 的 `target/` 不要跨工作树软链共享。

## 3 阶段划分

| 阶段 | 目标 | 主要交付 | 退出门禁 |
| --- | --- | --- | --- |
| P0 骨架（1–2 周） | 端到端跑通一次「命令 → 渲染 → 视图」 | 主仓 `engine-jar` 首次产出、建仓、脚手架（用 v0.1.51 的 `openidea-plugin` 生成以钉住 1.15）、CI、`.nvmdrc`/`rust-toolchain.toml`、settings/locales/icons 骨架、`link` 热更回路、一个 XML 片段渲染命令、记忆播种完成 | `openidea-plugin check` 通过；真机 link 后命令可见并出结果；新仓会话能读到播种后的记忆索引 |
| P1 纯逻辑引擎（3–5 周） | 把 98 条现成档里的核心算法搬进 Rust | XML 动态 SQL、OGNL 子集、iBatis2、15 方言、参数还原、格式化、21 条性能规则、CRUD/反向/回滚、日志四族解析 | 与主仓语料对拍：每类 ≥20 例，输出逐字相等或差异列入白名单 |
| P2 界面面（2–3 周） | 用户可见的面全部成形 | 日志面板、预览工作台（web 视图）、设置 8 页、通知、双语、图标 | 真机走查：8 条主流程无阻塞；双语覆盖 100% 声明文本 |
| P3 导航与注解（2–3 周） | Mapper↔XML 导航与 XML 渲染触发 | `documentIndexers` 图、line-markers + markerIcons、definition、injections、inlay、diagnostics | Ctrl+B 双向跳转在 3 个真实项目（单模块/多模块/MPJ）可用；默认关闭的设置项生效 |
| P4 捕获链（2–3 周） | agent 通道打通 | agent 改为 loopback WebSocket 服务端、`contributes.javaAgents`、插件 `websocket-connect` 消费、日志面板实时刷新、降级说明 | Spring Boot 示例项目启动后 3 秒内面板出 SQL；无 agent 时给出替代指引 |
| P5 sidecar 与执行（3–4 周） | Wrapper 渲染、EXPLAIN、schema | jar 打包与随包分发、JSON 契约、`process:java` 管理、Rust 侧「文本 → WrapperChain」、EXPLAIN 与 schema 查询 | Wrapper 对拍：主仓 30 个语料，`exact` 一致率 ≥90%，其余标 `partial`；EXPLAIN 在 3 个真实库出结果 |
| P6 MCP（1 周） | 11 个工具可用 | `contributes.mcpTools` + `mcp-provider.call`、task 续期、只读/破坏性标注 | 外部 agent `tools/list` 见 11 个工具，`render_logs`/`check_sql` 端到端通 |
| P7 发行（1–2 周） | 可安装、可更新 | keygen、pack、sha256、GitHub Release、Gitee 镜像、自建索引（`merge --keep`）、文档站、市场 PR（若开源） | 干净机器从索引安装成功；版本升级走通；回读断言三处一致 |
| P8 上游提案（并行） | 解掉结构缺口 | 4 条提案（见报告 4.7） | 至少 P0 提案有维护者回应 |

## 4 门禁与工程纪律

| 门禁 | 命令 / 动作 | 触发时机 |
| --- | --- | --- |
| 格式与静态检查 | `cargo fmt --all --check`、`cargo clippy --all-targets -- -D warnings` | 每次提交（CI） |
| 单测 | `cargo test`（SDK mock host，无需起 IDE） | 每次提交 |
| 包校验 | `openidea-plugin check <dir>` | 每次提交；打包前必跑 |
| 组件新鲜度 | 先 `openidea-plugin build` 再 `pack` | 发布流水线（`pack` 不校验新鲜度，官方靠 `.sources` 指纹补） |
| 对拍 | `sidecar-java` 输出 vs Rust 输出 vs 主仓基线 | P1/P5 每阶段 |
| 依赖合规 | 依赖许可白名单；双许可组件必须显式记录所选分支 | 每次新增依赖 |
| Node 版本一致 | 本地由仓根 `.nvmdrc`（22.22.3）锁定，CI 用 `node-version: 22` | 建仓时设定，发版前核对 |
| 真机验收 | `link` + 设置 › 插件 › Reload，跑 8 条主流程 | 每阶段末 |

## 5 与主仓的关系

- 主仓（IntelliJ 版）继续作为**行为基准**与**测试语料来源**，不合并分支、不共享代码仓。
- 引擎以产物形态复用：主仓新增 `sub-projects/engine-jar`，源集指向现有源码、按纯包（`model` `xml` `sql` `reverse` `generator` `psource/util` 的纯部分）圈定，产出 `mpsv-engine-<版本>.jar` + sha256；新仓 `engine/ENGINE_VERSION` 钉版本，发布时校验和一致才打包。
- 版本号与主仓日历版**解耦**（避免与 JetBrains 发行节奏互相牵制）；`CHANGELOG` 各自维护，但功能条目名保持一致，便于对照。
- 共享的判定逻辑（性能规则、方言表、写风险规则）以 `cases.jsonl` **对拍**保证一致，不靠人工同步。
- 主仓多了一个对外产物契约（engine-jar 的版本与校验和），需在 CI 里固定生成并归档，避免「新仓拉到的是未发布版本」。

## 9 协同与经验继承（决策已定：独立工作区 + 按需附加 + engine-jar 导出）

### 9.1 工作区形态

- 新仓是**独立工作区**（自己的 `AGENTS.md` 生效、自己的记忆目录）。
- 需要同时改两仓时，把另一个仓加为**附加工作目录**。实测结论：附加目录**不改变记忆根**（仍用主仓的记忆目录），但**附加目录的 `AGENTS.md` 不会被加载**——所以新仓的规范不能只写在附加目录里。
- 记忆目录名公式（已复算验证）：`<basename>-sha256(小写绝对路径，反斜杠分隔)[:16]`。例：`f:\code\java\gitee\songrenshuo\mybatis-plus-sql-visualizer` → `09474abd1ce62ee6`。

### 9.2 经验继承的三条通道（按可靠性排序）

| 通道 | 内容 | 维护方式 |
| --- | --- | --- |
| 用户级 `C:\Users\USER\.zcode\AGENTS.md` | 跨仓硬规则（≤10~15 行）：如「不调用不存在的 API」「耗时命令后台化」「中文乱码/PS 编码坑」「提交前对拍」 | 单点维护，两仓自动生效；控制在 40 KB 内 |
| 项目记忆播种 | 把主仓瘦身后的 `MEMORY.md` 与精选记忆文件复制到新仓记忆目录（键按新仓路径算） | 首次建仓时播种一次，之后各自演进；只搬「适用多仓」的条 |
| 共享知识 | 可移植教训、契约 JSON（渲染请求/响应）、`cases.jsonl` 语料、方言/兼容矩阵 | 独立小仓或 `mpsv-shared`，两仓以固定 tag 引用，**禁止复制正文** |

### 9.3 前置动作：先瘦身 `MEMORY.md`

当前主仓 `MEMORY.md` 为 214 行 / 67,735 字符，**超过注入上限（约 200 行 / 25,000 字符）**，本会话只加载了前一部分。不先瘦身，播种出去的是残缺索引。方法：把「进行中」里已完成/已过期的条目移入 `docs/memory-archive/`，只保留钩子行；重建索引后再播种。

### 9.4 反模式（实测支持）

| 反模式 | 证据 |
| --- | --- |
| 手工镜像 `AGENTS.md` 到各仓/worktree | `mpsv-sql-insight/AGENTS.md` 32 行 vs 主仓 98 行（缺 E1 一条）；用户级 AGENTS 写「115 个文件」实际 129 |
| 整包复制主仓 docs 到新仓 | 必然双份腐化；`LESSONS.md` 已出现 L12/L13 撞号，`reviewed-through` 停在 10-27 而文件 11-25 仍在改 |
| 复制 26,168 行 Kotlin 到新仓 | 两份实现必然漂移；改用 engine-jar 产物 |
| submodule 整个主仓 / composite build | 把 33 MB docs 与 771 个计划带进新仓视角，并把 cargo 与 Gradle 两套构建绑死 |
| 用 hardlink/junction 共享可变文件 | 一处改动另一处静默变化，无法审计 |

## 6 风险登记

| # | 风险 | 影响 | 缓解 |
| --- | --- | --- | --- |
| R1 | 无类型解析导致静默错 SQL | 用户信任崩塌 | 「不猜」原则：confidence 三档 + 未解析占位 + 禁止把 partial 当 exact |
| R2 | provider 50 ms 预算 | gutter 体验差 | 缓存 + 增量解析 + 首次返回空；必要时改命令式触发 |
| R3 | `process:java` 哈希钉住（JDK 升级要重批） | 用户流失 | optional 降级、设置里可选 java 路径、文档提前说明 |
| R4 | API 预览态（2.0 可能破坏性变更） | 重写 | 只依赖 1.15 已发布接口；把 1.16+ 特性列黑名单 |
| R5 | 市场审核（开源要求） | 闭源受限 | 闭源走自建索引（已验证可行）；开源版走官方注册表 |
| R6 | 导航与官方/Duo 撞车、图标重复 | 体验噪音 | 导航默认关；只提供名字级 + 明确设置说明 |
| R7 | 宿主 PR #3641 已覆盖 mapper XML SQL 智能 | 我方某功能显得多余 | 主打「渲染出可执行 SQL」，不与着色/补全竞争 |
| R8 | 双发行（IntelliJ + OpenIDEA）维护成本 | 人力 | 共享语料与规则表；Rust 侧只做必要移植，不追平 201 条 |
| R9 | 自建索引在中国网络的可达性 | 安装失败 | Gitee Pages + 备用对象存储；索引 URL 可切换 |
| R10 | 包内 jar 被安全审查质疑 | 上架受阻 | README 逐项说明 `process:java` 必要性；提供「无 sidecar 的纯文本模式」 |

## 7 首批可派任务

1. **T0-0 主仓 engine-jar 导出模块**：在 `sub-projects/` 加 `engine-jar`，源集指向现有源码、圈定纯包边界，产出 `mpsv-engine-<版本>.jar` + sha256，CI 归档。验收：jar 不含 IntelliJ 类型（`javap` 抽检 + 依赖树无平台 jar）。
2. **T0-1 建仓与骨架**：按 §2 建目录、脚手架、CI、`check` 通过、`.nvmdrc` 与 `rust-toolchain.toml` 就位。
3. **T0-2 前提验证包**：把 P-1~P-7 各写成一个最小验证用例，出一份「前提验证结论」。
4. **T0-3 记忆播种**：先把主仓 `MEMORY.md` 瘦身到注入上限内，再复制到新仓记忆目录（键按新仓路径算），另在用户级 AGENTS.md 加跨仓硬规则。
5. **T1-1 XML 引擎移植**：`xml/` + `xml/ibatis/` 换 Rust，含 OGNL 子集；对拍 20 例。
6. **T1-2 方言与规则**：15 方言表 + 21 规则 + 格式化；对拍主仓输出。
7. **T2-1 预览工作台**：web 视图 + 参数区 + 复制/导出；真机走查。
8. **T4-1 agent 传输改造**：裸 TCP → loopback WebSocket，保持事件语义不变。
9. **T8-1 上游提案草稿**：`database.execute` 一页设计稿（WIT 变更 + host 落点 + 测试）。

## 8 开发环境（已就绪，依据分册 11）

### 8.1 最终读数（均在按注册表复刻的全新子进程中测得）

| 项 | 值 | 备注 |
| --- | --- | --- |
| Rust | `rustc 1.98.1` / `cargo 1.98.1` / `rustup 1.29.3` | 默认家目录 `C:\Users\USER\.rustup`，无任何 Rust 用户级环境变量 |
| targets | `wasm32-wasip2`、`x86_64-pc-windows-msvc` | 已补 `wasm32-wasip2` |
| components | clippy 0.1.98、rustfmt 1.9.0-stable | 齐全 |
| Node | `v22.22.3`（NVM Desktop / nvm-windows 1.2.2，根 `D:\HuanJing\nvm`） | 仓根 `.nvmdrc` 写 `22.22.3` 锁定；离开目录回 22.10.0 |
| npm / pnpm | `10.9.8` / `11.29.0` | pnpm 装在 `D:\HuanJing\nvm\22.22.3` 全局前缀，随版本切换 |
| openidea-plugin | `0.1.51 (plugin API 1.15.0)` | 由 `--tag v0.1.51` 源码编译，位于 `C:\Users\USER\.cargo\bin\openidea-plugin.exe` |
| MSVC | VS2022 BuildTools 14.44 + SDK 26100 | 只影响 native 构建；wasm 不需要 |
| smoke 构建 | `cargo build --release --target wasm32-wasip2` → 7.00 s，14,355 B，头 8 字节 `00 61 73 6d 0d 00 01 00` | 证明产出的是 **component** 而非 core module |

### 8.2 使用方式

- 新仓根提交 `.nvmdrc`（`22.22.3`）与 `rust-toolchain.toml`；CI 用 `node-version: 22` 与同一 Rust 版本。
- 非交互子进程里若 `.nvmdrc` 未生效（nvmd shim 只在交互式 shell 挂载），显式用 `D:\HuanJing\nvm\22.22.3\node.exe`。
- 新仓 CI 固定：Rust 1.98.1、Node 22、pnpm 11、`openidea-plugin` 0.1.51。

### 8.3 遗留项

1. 用户 PATH 尾部有历史遗留的 `D:\HuanJing\nvm\22.10.0`，会与 NVM 切换抢优先级——**建议在 NVM Desktop 里清理**（子代理未动）。
2. `D:\HuanJing\rust` 是回退后遗留的冗余 toolchain，实测 **2.73 GB**（含 registry 缓存 0.92 GB），可删，删除交用户决定。
3. 网络：Rust 走 rsproxy、npm 走 npmmirror、其余依赖 `127.0.0.1:7890` 代理；CI 不受影响。
