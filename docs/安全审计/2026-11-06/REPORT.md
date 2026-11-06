# 审计报告 — mybatis-plus-sql-visualizer（run-1）

由 Cloudflare security-audit-skill 工作流于 2026-11-06 生成（skill 安装于目标仓库 `.agents/skills/security-audit/`）。

## 1. 运行档案、范围与执行声明

- **档案**：`standard`。**预算**：未设置（无代理数量上限）。**源码版本**：`30312d461e3fc54d5be553662dc3beb516bfa4e8`（工作区脏仅因审计 skill 本体 `.agents/skills/security-audit/`，未跟踪）。
- **范围**：整个仓库——主插件（`src/main/kotlin`、`src/main/java`）、agent 子项目（`sub-projects/sql-visualizer-agent`）、buildSrc、CI 工作流、dev-support、database-lab、更新清单。该范围内无推迟或无范围外项；波后覆盖批评家追加的两个单元已完成狩猎并关闭。
- **执行策略**：`sandboxed-source-and-local-only`。侦察确认本 Windows 审计主机**无法强制执行所需的 OS 沙箱**（空环境白名单、无外网、只读目标、仅草稿可写、资源上限），也无法提供草稿提升流程。因此本次运行**仅做源码审查**：未构建或执行任何目标受控代码。其结果是结构性的：所有幸存的候选均为 `needs_validation`（skill 禁止在没有「有界本地观测结果」时赋予严重级别或 `confirmed`）。WSL 存在但未安装 Linux 发行版。
- **历史运行**：不存在；本仓库首次审计。无历史确认排除项、无结转记录。
- **代理消耗 vs 计划**：4 侦察 + 14 猎人（13 波次一 + 1 波次二）+ 2 覆盖批评家（波后 + 独立终局）+ 7 个第三阶段候选验证器 + 7 个第五阶段记录验证器 = **34 次代理调用**，与 standard 档案计划一致（验证器按子系统分组；每个候选与每条最终记录均单独裁决）。
- **适配（已记录的偏差）**：(1) `validate-*.cjs` 通过运行本地副本执行，附文档化的 Windows `lstat` 回退以替代不可用的 POSIX `O_NOFOLLOW/O_NONBLOCK` 打开标志（skill 原件未改动；说明见 `run-metadata.json`）；(2) 猎人/验证器提示词经由父级共享上下文文件 `context/hunter-context.md` 嵌入 skill 的逐字方法/契约块；(3) 第三/第五阶段验证器按子系统分组（每组一个代理，按记录逐一裁决），而非每记录一个代理。

## 2. 安全态势摘要

本插件的核心捕获管道是一个**单用户桌面工具，其风险集中在越出 IDE 进程边界之处**：通向字节码 agent 的未认证环回通道、暴露给 AI 代理的 MCP 工具面、针对开发者数据源的实时 SQL 执行，以及安装下载代码的自更新链。源码审查**未发现任何「无需本地立足点或被攻陷的更新通道即可利用」的跨主体权限提升**，并且存在若干真正有力的控制（SecureXml 加固解析器、失败即拒的 MCP SELECT 守卫分层、有界的反馈 / ZIP 处理、发布代码中干净的秘密卫生）。最具所有者可行动性的一项是**提交在文档文件中的真实数据库凭据**。值得一次性修复的系统性模式：存在于一条通道却缺失于通往同一汇聚点的兄弟通道的输入长度上限；逐点临时实现的 HTML 转义；被宣称为更强保证的纯客户端守卫语义（read-only hint、DML 确认）。

## 3. 已确认发现

**审计原判（2026-11-06）：无。** 审计主机没有 OS 级沙箱，任何候选都无法产生 `confirmed` 判定所需的有界本地观测结果。零条 confirmed 记录是本次运行执行边界的属性，并非「代码库无漏洞」的断言——下方 25 条已验证线索正是「一次有界本地检查即可定案」之处。

**修复落地更新（2026-11-07，T-SecurityAuditFixes 分支）：24 条转 `confirmed`（已修复）。** 每条以「先红复现测试在修复前转红（审计缺陷复现）→ 修复提交落地 → 回归通过」定案（配置型控制 T-SAF-14/15 以实跑生成的校验和/校验元数据背书）；严重级别按 skill 校准锚（桌面单用户模型：本地恶意进程边界=medium；AI 代理驱动的控制失效=medium~high；hostile-project/共享设置通道=medium；资源无界/ReDoS=low~medium；更新链/构建防线=medium；UI HTML 注入=low~medium）。`findings.json` 中各 confirmed 记录附 intended_behavior / conditions / execution / remediation / severity / confidence 完整口径：

| # | 线索（一句话） | 修复提交 | 严重级别 |
|---|---|---|---|
| 2 | 环回 agent 通道无对端认证——同机进程可注入伪造 SQL 事件、翻转捕获门、窃听 agent SQL 帧 | 5ac6fdb4f/0495ade42（双向通道令牌认证） | medium |
| 3 | MCP `sqlv_explain` 只读仅在客户端——SELECT 调用的副作用函数以数据源完整权限执行 | 8f556d347/4da7b48a4（setReadOnly 强制，fail-closed） | medium |
| 4 | 内嵌预览面板原生 ▶/Ctrl+Enter 绕过插件 DML 确认/无界写入/方言门链 | 1ba06bf43/10e26eeba（D1=A：撤 CLIENT_KEY/EDITOR 注入，禁原生入口） | medium |
| 5 | 更新 plugin-id 门正则可被 XML 注释与平台描述符解析器去同步 | daf6617af/addc3f3de（SecureXml 真解析） | medium |
| 6 | 更新 zip 防护将条目名清洗完全委托给未验证的平台解压器 | efefc4eaf/98eff6fa8（插件侧条目名校验） | medium |
| 7 | `sqlv_history_search` 无视 MCP 输出脱敏设置，敏感字面量直达代理 | 0619a92d3/79efe38f7（formatResult 接入 maskDisplay） | medium |
| 8 | 导入/共享设置字段无值级校验即拼接进渲染/执行 SQL，可翻转语句语义 | ee31117f9/586d5d40（SettingsValueValidator 双读取路径） | medium |
| 9 | HTML 报告导出未转义插值 tableName/来源名称并自动在浏览器打开 | 7a56f934f/d3e7ebd1b（补转义，共享 HtmlEscape） | low |
| 10 | 三处 Swing HTML 汇聚未转义插值低信任文本（回滚对话框/预览元信息/错误 tooltip） | 7a56f934f/d3e7ebd1b（共享 HtmlEscape + html.disable） | low |
| 11 | MCP 守卫 INTO 检查未递归进 CTE/派生表——嵌套 SELECT INTO 绕过 | a895a914a/14c4574a0/78f41eea4（递归遍历 + 词法兜底） | medium |
| 12 | 无 token 行共用兜底桶——跨线程 Preparing/Parameters 错配直达执行 | 35477751f/4707e1d87（D5 温和版延迟配对） | medium |
| 13 | 自动保存写入器跟随符号链接，追加写入可被重定向到项目外 | d88923012/d4a31a032（NOFOLLOW 对齐修订路径） | low |
| 14 | 依赖解析镜像优先且无校验——替换的镜像字节未经质疑进入发布插件 | b51f664af（mavenCentral 置前 + verification-metadata.xml 356 组件 sha256 严格校验） | medium |
| 15 | Gradle wrapper 分发包来自腾讯云镜像且无 distributionSha256Sum | 965a15168（官方校验和锚定，2026-11-06 实取） | medium |
| 16 | 发行门禁首次匹配遍历选取 verifier-cli jar，多版本缓存下无视 pin | 965a15168（三解析点 pin 精确匹配 + 缺失即失败） | medium |
| 17 | 四个 MCP 文本工具解析 agent SQL 无输入长度上限（兄弟通道 20k） | d02ddec0d/b0c7bf701（20000 字符结构化拒绝） | medium |
| 18 | SqlReverseParser 递归下降无深度守卫——深度嵌套经 sqlv_reverse_sql 触发 StackOverflowError | 5dba1ac62/6c6fb091a（depth 局部贯穿两环，上限 256，结构化拒绝；e8d2a635d 定案口径） | medium |
| 19 | OGNL 求值器把 NumberFormatException/StackOverflowError 泄漏出 Err 契约，炸穿 mapper-check 与 UI 预览 | f07c7d8b7/dcf869d17（toLongOrNull 入 Err + 外层 Throwable 收敛 + 深度上限 512） | medium |
| 20 | MyBatis Parameters 文本未截断进入二次复杂度正则，冻结解析线程 | a3a9ef9cb/9cf2425cd（take(maxParseLength) 同规） | medium |
| 21 | ExecutionTimeParser 行尾 ms 正则对连续空白段平方级回溯 | 302d41947/fbdd4f997（lookbehind 重锚定 + 行尾窗口） | low |
| 22 | MyBatis/Hibernate pending 解析桶永不被淘汰，保留内存无界增长 | da90431c3/9069bad33（静默 >60s forceClose 淘汰） | low |
| 23 | 历史 .sql 读取器无逐行/逐文件字节上限（含 MCP 触发路径） | 3d8150c55/6f778d74c（1MB 行折叠 + 100MB 文件闸） | low |
| 24 | agent 提供的历史搜索正则无成本预算，可 CPU 耗尽 | fc81f5684/f49190dde/78f41eea4（长度/复杂度/求值三道闸） | medium |
| 25 | 导入的动态表名正则规则在渲染路径上无界求值 | 9c122fe68/99ac9a88f（BoundedRegex 预算 + 并发有界缓存） | low |

**唯一遗留 `needs_validation`：#1 凭据**——**所有者明示豁免**（测试服务器口径，2026-11-06 定案；豁免而非缩水）。其余 24 条全部转 confirmed；#14/#18/#19 由车道 D / 构建防线补齐（提交 b51f664af、5dba1ac62/6c6fb091a、f07c7d8b7/dcf869d17，合并验证修正 e8d2a635d）后按同一口径补转。

## 4. 待验证线索（25 条，均经独立复核）

完整轨迹、证据、阻塞点与非破坏性解决计划见 **NEEDS-VALIDATION.md**。按所有者可行动性排序：

| # | 线索 | 边界 | 定案方式 |
|---|---|---|---|
| 1 | 在 `.agents/skills/db-env-provisioning/SKILL.md:160` 为公共测试服务器实例提交了可用数据库凭据（xc3 @ 公网测试服务器 103.36.220.245:25866） | 仓库文档 → 网络可达的数据库实例 | 本+部 — **轮换凭据 + 清理文件与 git 历史** |
| 2 | 环回 agent 通道无对端认证（注入事件 / 夺取捕获控制 / 污染 attach 表 / 窃听 SQL 帧） | 本地进程 ↔ IDE | 本+部 |
| 3 | MCP `sqlv_explain` 只读仅在客户端（SELECT 调用的函数以数据源完整权限执行，而 `readOnlyHint=TRUE`） | AI 代理 → DB | 本+部 |
| 4 | 内嵌预览控制台的 native ▶/Ctrl+Enter 绕过插件 DML 确认 / 无界写入 / 方言门链 | 捕获的 SQL → DB | 本+部 |
| 5 | 更新插件 id 门使用的正则可被 XML 注释与平台描述符解析器去同步 | 更新通道 → IDE | 本+部 |
| 6 | 更新 zip 防护将条目名称清洗（zip-slip / Windows 保留名）完全委托给未经验证的平台解压器 | 更新通道 → 文件系统 | 本+部 |
| 7 | `sqlv_history_search` 忽略 MCP 输出脱敏设置（敏感字面量到达代理） | 历史 → AI 代理 | 本+部 |
| 8 | 导入 / VCS 共享的设置字段未做内容校验便被拼接进渲染 / 执行的 SQL | 设置文件 → SQL | 本+部 |
| 9 | HTML 报告导出未转义插值 `tableName` / 来源名称，并自动在浏览器打开 | 项目元数据 → 浏览器 | 本+部 |
| 10 | 三个 Swing HTML 汇聚未转义插值较低信任文本（回滚对话框、预览元信息、错误工具提示） | DB/控制台文本 → UI | 本+部 |
| 11 | MCP 守卫的 INTO 检查未递归进 CTE / 派生表 | AI 代理 → DB | 本+部 |
| 12 | 无 token 日志行共用一个兜底桶 → 跨线程 Preparing/Parameters 错配直达执行 | 控制台 → DB | 本+部 |
| 13 | 自动保存写入器跟随符号链接（修订路径强制 NOFOLLOW） | 项目目录 → 任意文件追加 | 本 |
| 14 | 依赖解析镜像优先（阿里云）且无 `verification-metadata.xml` | 镜像 → 发布插件 | 本+部 |
| 15 | Gradle wrapper 来自腾讯云镜像且无 `distributionSha256Sum` | 镜像 → 构建工具链 | 本+部 |
| 16 | 发布门禁以首次匹配遍历选取 verifier-cli jar，忽略版本固定（仓库内已记录真实事故） | CI 缓存 → 发布判定 | 本+部 |
| 17 | 四个 MCP 文本工具解析 agent SQL 无输入长度上限（兄弟通道执行 20k 上限） | AI 代理 → IDE 进程 | 本 |
| 18 | SqlReverseParser 无递归深度守卫（经 `sqlv_reverse_sql` 触发 StackOverflowError） | AI 代理 → IDE 进程 | 本 |
| 19 | OGNL 求值器将 NumberFormatException/StackOverflowError 泄漏出其 Err 契约 | mapper XML → MCP/UI | 本 |
| 20 | MyBatis Parameters 文本未截断进入二次复杂度正则 | 控制台 → 解析线程 | 本 |
| 21 | ExecutionTimeParser 对连续空白段产生平方级回溯 | 控制台 → 解析线程 | 本 |
| 22 | MyBatis/Hibernate pending 解析桶永不被淘汰（无界保留） | 控制台 → 内存 | 本 |
| 23 | 历史 `.sql` 读取器无逐行 / 逐文件字节上限（含 MCP 触发路径） | 植入文件 → IDE 堆 | 本 |
| 24 | agent 提供的历史搜索正则无成本预算 | AI 代理 → IDE 线程 | 本 |
| 25 | 导入的动态表名正则规则在渲染路径上无界 | 设置 → IDE 渲染 | 本 |

## 5. 已拒绝记录

无——验证阶段未推翻任何候选。若干侦察级怀疑在狩猎阶段即被证伪、未成为候选（值得注意的如：`CommunityDialog` 转义器完整度、设置导入触及自动保存路径、EXPLAIN 包装执行非 SELECT 语句）；这些作为检查记录在账本内，而非被拒绝的发现。

## 6. 加固备注（非发现类改进）

要点（完整清单见 `coverage-ledger.json` 各单元的 `hunter_hardening`）：

- **统一兄弟通道边界**：将 20k `maxParseLength` 不变量应用于四个 MCP 文本工具；为 `SqlLogFileReader` 增加读取侧逐行 / 逐文件字节上限；为历史搜索中的 agent 正则求值加上成本预算。
- **统一转义**：以一个共享 HTML 转义工具取代六处逐点变体；将既有 `html.disable` 客户端属性模式应用于三个未转义标签 / 工具提示。
- **Agent 通道**：在 HELLO 中向双向加入共享秘密 / 能力令牌；在提供重绑定恢复前验证所声称的 pid；限制失同步告警洪水。
- **更新链**：在 `update/update.json` 中加入产物校验和 / 签名（与 downloadUrl 并列发布 sha256）；在 `UpdateZipGuard` 中校验条目名称；转义 / 限制通知中的清单版本行。
- **构建**：提交 `gradle/verification-metadata.xml`；为 wrapper 加入 `distributionSha256Sum`；在三个解析点强制执行 verifier-cli 固定；将 Actions 固定到提交 SHA。
- **文档**：为 `.agents/**`、`docs/**`、`dev-support/**` 中的凭据字面量加入 pre-commit 门禁。
- **已验证的正面模式**：SecureXml 单解析汇聚 + 失败关闭预扫描；McpSelectGuard 失败即拒解析 + 分层守卫；反馈通道有界读取 + 固定端点；发布源码无秘密；全链路 TLS；SHA-256 使用干净；mcpStub 排除于打包；失败关闭的文件解析器 + 符号链接规范化。

## 7. 覆盖率汇总（来自账本）

| 状态 | 数量 |
|---|---|
| covered | 8 |
| candidate（已关闭并附已验证线索） | 19 |
| blocked / deferred / out_of_scope | 0 |

27 个单元、两个波次（波次二由波后批评家追加：`sqlv_schema_context`、MCP 文本工具解析边界）；**终局批评家返回 `stop=true`**——无缺失单元、无重指派。两份验证器对最终产物均通过（`validate-findings.cjs`：25 条记录有效；`validate-coverage-ledger.cjs`：27 个单元有效）。这是本仓库的首次运行：按 skill 设计，单次运行约覆盖重复运行可发现漏洞的一半——账本与 `findings.json` 是未来任何运行的累加基线。

> **2026-11-07 修复批次尾注（账本闭环）**：修复分支 `t-security-audit-fixes`（提交区间 7389cde46..e8d2a635d，审计基线 30312d461 之后）落地三车道 19 项 src/main 修复 + 车道 D 2 项（T-SAF-18/19）+ 构建防线 3 项（T-SAF-14/15/16），**24 条线索转 `confirmed`**（见 §3；双验证器 PASS），唯一遗留 `needs_validation` 为 #1 所有者豁免。复核波 R-01/R-02/R-03 结论全部**维持**原判定（6 条受审计后代码变化影响的线索病灶未变）；**新增信任面快扫（2 个）结论均 info、无新发现**——DevUpdateMode 预览通道的下载安装走与正式通道同一 downloadAndInstall → UpdateZipGuard.check → validateArtifact 全链（纯内存连点解锁不构成新信任面）；PlatformEpDynamism 纯编译期常量、零反射零 IO，卸载门失败一律 fallbackToRestartScript（fail-closed）。账本单元级 bookkeeping 字段 `fix_dispositions` 已登记（19 个含 result_fingerprints 的单元、28 条指纹：已修复 24 / 豁免 1（T-SAF-01）），单元审计语义字段与 status 保持不动。已知 followup（共享骨架出口脱敏、共享 BoundedRegex 预算统一、剩余局部 escapeHtml 变体、项目级设置 UI 内联校验、所有者 runIde 端到端核对）登记于 lane-reports followups。
