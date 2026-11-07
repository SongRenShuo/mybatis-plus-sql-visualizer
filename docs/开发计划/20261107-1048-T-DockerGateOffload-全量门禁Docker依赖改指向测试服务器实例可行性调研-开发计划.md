# T-DockerGateOffload：全量门禁 Docker 依赖改指向测试服务器实例——可行性调研（2026-11-07）

- 立项：2026-11-07 10:48
- 状态：**已拍板并实施**（用户定「方案一：容器用例外指收编进矩阵通道 + 方案三收尾：预检条款改窄」组合；实施记录见 §十一）
- 目标分支：`master`（如立项实施）
- 来源：2026-11-07 用户明令「不许自行启动本地 Docker Desktop，Docker 类需求优先用测试服务器已有实例」；当日全量门禁预检 `timeout 10 docker info` 发现本地引擎未启动即止步，用户令调研「全量门禁里依赖 Docker 的测试改指向测试服务器实例」的可行性
- 取证口径：全部只读（未跑 gradle、未跑 docker 命令、未连测试服务器）；服务器侧现状只来自仓内文档与既往经验记录，拿不准的列「待上服务器核实」

## 一、结论先行（三条）

1. **全量门禁（testShards）今天已经不依赖本地 Docker**。唯一 Testcontainers 测试类 `DialectDockerVerificationTest` 自 2026-09-23 起被显式开关前置（`mpvs.docker.tests.enabled` 默认 false，只有 `-PmpvDockerTests=true` 才触达 daemon），默认形态下 6 例在 Assume 处秒级跳过，不会挂、不会拉镜像。`timeout 10 docker info` 预检是**人工纪律**（AGENTS.md / docs/MEMORY.md:30），不在任何构建脚本里——它是开关落地前的止血措施，如今对默认门禁已是**过期守则**（见 §二）。
2. **「改指向测试服务器实例」的那条路已经建好了**：`./gradlew dialectExternalVerify`（T-DialectExternalGate，2026-10-31 用户定例）连的就是测试服务器外部实例矩阵，2026-10-31 起它是**发版门禁的唯一真机判据**，Docker 用例明确「不承担发版判据」（`DialectDockerVerificationTest.kt:45-47`）。CI 侧夜间方言腿（secret `DIALECT_MATRIX_PROPERTIES`）与 DB 集成腿（secret `MPV_DB_URL` → `103.36.220.245:25900/mpj_demo`）也早已指向服务器。
3. 真正的悬置项只剩一个：五个 Testcontainers 容器用例（本地手工 `-PmpvDockerTests=true` 才跑）要不要也外指。四个方案的分券见 §六——**倾向方案一（外指改造，收编进既有矩阵通道）+ 顺手清理过期预检守则**，但它改的是测试类源码且牵涉共享实例并发污染（§五-3），不拍板，等用户定。

## 二、Docker 依赖清单（src/test 全量 grep 取证）

### 分类口径
- **A 类＝已支持外指**（现成环境变量/系统属性/矩阵文件入口，改配置即可指向任意端点）
- **B 类＝连 localhost 但易改 env**（写死本地端点，加属性透传即可外指）
- **C 类＝必须掌控容器生命周期**（Testcontainers 形态，起容器/拉镜像/随机端口）

### 清单

| 测试类 | 依赖形态 | 分档 | 证据（文件:行） |
|---|---|---|---|
| `sql/verify/DialectDockerVerificationTest`（5 个容器用例：MySQL/PG/MariaDB/SQLServer/Oracle） | Testcontainers 1.19.3 拉容器（`mysql:8.0.36` / `postgres:16.2` / `mariadb:11.2` / `mcr.microsoft.com/mssql/server:2022-CU12` / `gvenzl/oracle-free:23.5-slim`），默认关（`-PmpvDockerTests=true` 才触达 daemon） | **C** | `src/test/kotlin/.../DialectDockerVerificationTest.kt:56-72`（守卫+开关）、:81/:103/:122/:141/:161（五容器）；`gradle/libs.versions.toml:9`（1.19.3）；`buildSrc/src/main/kotlin/mpv-testing.gradle.kts:182-186`（构建侧注入） |
| 同类第 6 例 `外部实例_方言套件与保留字扫描` | 读 `-Dmpvs.dialect.jdbcUrl/user/password/name` 连任意外部实例，连不上/未提供即跳过 | **A** | `DialectDockerVerificationTest.kt:206-240` |
| `sql/verify/DialectExternalMatrixVerificationTest`（`dialectExternalVerify` 任务） | 读 `.local/dialect-external-matrix.properties` 矩阵连**测试服务器实例**，实例级预算看门狗 + 判红底线五条；已是发版门禁 | **A** | `DialectExternalMatrixVerificationTest.kt:26-58`、:124-128；`mpv-testing.gradle.kts:519-598` |
| `sql/verify/DockerComposeLabConsistencyTest` | **无 Docker 运行时依赖**——纯文本结构校验（只读 docker-compose-dialects.yml 配方 + database-lab 目录存在性），随常规测试自动跑 | 不入档 | `DockerComposeLabConsistencyTest.kt:11`（自述）、:25-81；注意 `docs/memory-archive/pitfalls/pitfall-docker-guard-hangs-indefinitely.md:68-70` 曾把它误排进「docker 依赖类全集＝2 个」，实际它零 `DockerClientFactory`/零容器操作，O-72 排除它是多余的（无害） |
| 其余 src/test、`sub-projects/sql-visualizer-agent/src/test` | grep `docker\|Testcontainers\|DOCKER_HOST` 零命中（命中均为注释提及） | 不入档 | 本调研 2026-11-07 全量 grep 实证 |

**分桶落点**：`DialectDockerVerificationTest` 源码零 `com.intellij` 引用 → 进 `fastBucketClasses`，由 `testFast` 腿承载、**不进五片分片**（`mpv-testing.gradle.kts:763-765` 分片显式剔除 fastBucket 类）——即全量门禁里它只在一个 JVM、一条腿上跑，**不存在「5 分片并行打同一实例」的形态**（这直接削掉方案一的最大并发顾虑，见 §五-3）。

### 半活态挂起机制（既有实证，引用）
`docs/memory-archive/pitfalls/pitfall-docker-guard-hangs-indefinitely.md` 三态实录：引擎完全没起 → 快速跳过（0.275s，6 skipped）；**半活态 → 无限挂死**（testcontainers 选定 npipe 策略后卡在 `versionCmd().exec()`，HTTP 读无超时，`catch(Throwable)` 兜不住阻塞；`docker info` 30s 超时 rc=124 是可靠判读）；健康态 → 6 例真跑、首拉数 GB。根治＝显式开关 `mpvs.docker.tests.enabled`（commit `e166ee1`），半活挂死与真容器不可控两形态都已封死。

### 预检链定位
`timeout 10 docker info` **不在任何构建脚本/workflow 里**（全仓 grep `build.gradle.kts`/`buildSrc`/`dev-support`/`.github/workflows` 零命中脚本化实现）。它的落点是三处人工纪律：用户级 `AGENTS.md`（跑全量前验 daemon 活性）、`docs/MEMORY.md:30`、`docs/经验总结/20260923-1055-多工作树并行构建资源实测与定例-经验总结.md` R4 表行。**判断**：开关落地（2026-09-23）后，该预检对默认门禁已无保护对象——`mpvs.docker.tests.enabled=false` 时连 daemon 都不会被探测，预检成了「每次全量先白等/止步一次」的过期守则；只在 `-PmpvDockerTests=true` 手工跑时仍有意义。→ 可作为本计划的低成本清理券（§六-券 3）。

## 三、测试服务器资产盘点与匹配表

来源：`database-lab/README.md`（内存基线表 + 验证状态台账）、`.local/test-databases.md`（.local 整目录 gitignore，`.gitignore:98`，不进仓）、`.local/dialect-external-matrix.properties`（明文凭据只在 .local）。**凭据纪律**：docs/ 与 src/ 禁抄凭据；矩阵模板无凭据（`dev-support/dialect-external/matrix.example.properties`），实例清单与凭据一律指向 `.local/test-databases.md`。

两台服务器：
- **103.36.220.245「不大-北京-16G」**（Ubuntu 22.04 / 16G / Docker 28；宿主端口放行段 20000-29999；12 容器，内存收缩治理后 cap 合计 19.25GiB + 4GB swap，`docs/LESSONS.md` L17）
- **114.66.55.245「DBX smoke 测试环境」**（24+ 实例，通用凭据 `dbx_test`；openGauss 20105 无 CREATE 权限不可用于 DDL 验证）

### 与 Docker 用例的逐引擎匹配

| Docker 用例（容器镜像） | 服务器侧对位 | 版本差 | 匹配判读 |
|---|---|---|---|
| MySQL `mysql:8.0.36` | `xc3-mysql8`（MySQL 8.0.46，`103.36.220.245:25900/mpj_demo`，root；已供 testDatabase 链）；矩阵另有 `mysql84`（8.4.6，`114.66.55.245:20084/dbx_test`） | 8.0.36 → 8.0.46 微差 | 可对上 |
| PostgreSQL `postgres:16.2` | `pg16`（PostgreSQL 16.14，`114.66.55.245:20033/dbx_test`，已在矩阵） | 16.2 → 16.14 微差 | 可对上（103.36.220.245 上无原生 PG，GaussDB/openGauss/HighGo/Vastbase/磐维均为 PG 系但非原生 PG） |
| MariaDB `mariadb:11.2` | `xc3-mariadb`（MariaDB 10.11.11，`103.36.220.245:23306/xc3_test`）；`114.66.55.245:20081`（10.11.11） | **11.2 → 10.11 大版本差** | 可连但方言面有缺口（11.x 新语法面无对位实例）——需登记「10.11 等价性，11.2 缺真机」 |
| SQL Server `mssql/server:2022-CU12` | `xc3-mssql`（SQL Server 2022 CU27-GDR Developer，`103.36.220.245:21433/master`，sa；已在矩阵 sqlserver2022） | 2022 内更新 | 可对上 |
| Oracle `gvenzl/oracle-free:23.5-slim` | `oracle23`（Oracle Free 23，`114.66.55.245:20095/FREEPDB1`；已在矩阵） | 同代 | 可对上 |

另：服务器上已在跑的同套件证据——`.local/dialect-external-matrix.properties` 14 实例（mysql84/pg16/oracle23/sqlserver2022/mariadb/dm8/kingbase/oceanbase/highgo/gaussdb_kernel503/opengauss_gate/opengauss_official_nocreate/panwei_a/panwei_b）就是「插件渲染套件 + 保留字扫描」跑在服务器实例上的现行读数，证明外指通道本身已真机验证过。

## 四、时间阈值雷区与网络延迟评估

- **既有教训 L18**（`docs/LESSONS.md:193-198`）：`SenderLoopBackoffTest` 用「墙钟间隔比值」当判据，2 核 CI runner 放大一次性开销（类加载/JIT/首握手）→ 假红；正例是断言「被请求睡多久」的调度值序列。**与远程 DB 的交集**：方言套件内部**没有墙钟比值断言**（套件只断言执行成败与保留字计数），L18 形态不因外指引入。
- **真正的网络敏感判据＝实例级预算看门狗**（T-MatrixInstanceTimeout，2026-11-06）：实例在 `timeoutSeconds` 预算内无进展即判 **TIMEOUT 桶=红**（`DialectExternalMatrixVerificationTest.kt:48-58`；默认 300s，矩阵逐实例显式覆盖，如 mysql84=900s）。远程实例网络变慢会把「本来能过的实例」推过预算线——这正是 L18 同族的「持续 N 秒判据在慢环境假红」。既有缓解三件套：① 矩阵显式 `timeoutSeconds` 且纪律「不要低于该实例 batch=off 耗时」；② 批处理 + 词级并行把逐词往返 7→3、再按 4 路摊 RTT（T-DialectSweepSpeed/2，2026-11-06/07）；③ 实例慢网络时标 `optional=true` 是唯一降级通道（且必须写理由，可审计）。
- **量级实测**（nightly-full.yml dialect job 注释，2026-11-05~07 run 实测）：GitHub runner（跨洋）→ 两台服务器 RTT 200ms+，改前 mysql84 约 4.3s/词、整腿 50min 跑不完 → 砍成代表子集 4 key + 预算重标后 ≈9min；**本地（国内网络）全量矩阵 5~10min**。本机 → 103.36.220.245 是北京同城链路，RTT 远小于跨洋——本地门禁外指服务器的延迟代价远低于 CI 那条腿已经扛住的量级。
- 结论：外指不新增 L18 形态假红；需要盯的是**预算口径随链路重标**（换实例/换链路后首跑先取实测耗时再定 `timeoutSeconds`）。

## 五、五分片并行的数据污染/隔离风险（方案一专项核查）

1. **分片不会同时打一个实例**：Docker/方言套件类归 `fastBucketClasses`（`mpv-testing.gradle.kts:763-765`），testShards 五片里没有它们；`dialectExternalVerify` 是独立任务不挂 testShards。五分片并行外指同一实例的形态**不存在**。
2. **单 JVM 内并发已有隔离**：保留字扫描词级并行每 worker 独占 `kw_probe_<词下标>`（下标不相交，`DialectVerifySupport.kt:437`）；实例级并发每实例独立连接池。同一 JVM 一轮内自洽。
3. **跨运行（真正的风险面）：固定表名 + 无运行唯一前缀**。套件固定表 `dev_info`（:265/:269/:317）与 `mpv_batch_probe`（:345/:358），扫描表名 `kw_probe_$index` 按词下标编号（:437）——**两轮并发打同一实例会在同一张表上互删**：`createWithRetry` 开跑先 `DROP TABLE`（:521-528），finale 再 `DROP`（:601/:606/:654/:674）。既有缓解只有一个弱机制：建表失败清残留重试一次（:521-528 注释自认「唯一表名也可能撞上一轮崩溃的残留」）。当前共享实例上已有三个真实并发源：本地 releaseGate、夜间 CI 方言腿、其他会话的 DBX 临时探测——**今天的外指矩阵同样暴露在这个风险下**（不是方案一新增，而是既有债务）。若外指扩大（方案一把 Docker 用例也指过去），建议同券补「运行唯一 schema/表前缀」或运行期互斥标记（先登记方案，实施时定）。
4. **污染面**：扫描在共享库（`dbx_test` / `xc3_test` / `master`）里成百次建删表，表用完即删、无残留常态，但对共享实例是真实的瞬时负载与锁窗口；磐维 `panweidb` 库另有 demo schema 共存（`.local/test-databases.md` 灌入情况记录），套件表名与其不重叠（`dev_info`/`kw_probe_*`/`mpv_batch_probe` vs demo 的业务表）。

## 六、方案分券（每档给判据与代价，不拍板）

### 方案一：env 外指——把 C 类五用例改造/收编进外指通道
- 形态：给 `DialectDockerVerificationTest` 五个容器用例加「外部端点优先、无容器回退」分支（或直接以 `mpvs.dialect.*` 六参数形态与既有外部实例入口合流），指向 §三 匹配表实例；矩阵/属性仍放 `.local/`。
- 判据（何时该选它）：用户要求「本地不再依赖 Docker Desktop」且想保留 Docker 套件等同的读数面。
- 代价：改 1 个测试类（src/test，非生产代码）；补 MariaDB 10.11 与容器 11.2 的版本差登记；§五-3 的并发污染若要根治需加运行唯一前缀（+1 个小改动）。
- 风险：共享实例并发互删（§五-3）；保留字扫描对共享库的瞬时负载（本地链路快，量级远低于 CI 腿已扛住的读数）。
- 收益：本地门禁与 Docker Desktop 彻底解耦；读数面与 dialectExternalVerify 统一，符合 2026-10-31 「真机验证走测试服务器」定例的方向。

### 方案二：`DOCKER_HOST=tcp://<服务器>` 远程 daemon
- 形态：Testcontainers 1.19.3 只读环境变量即可外指 daemon（`EnvironmentAndSystemPropertyClientProviderStrategy`，实证见 `pitfall-docker-guard-hangs-indefinitely.md:57-64`），容器在服务器起、测试本地跑。
- **服务器侧现状（文档推断，未验证）**：仓内文档没有任何「服务器 docker daemon 暴露 TCP」的记录；宿主放行端口段是 20000-29999（`.local/test-databases.md` 部署记录），2375/2376 不在段内，**大概率未暴露且被防火墙挡住**。→ 待上服务器核实项：① `dockerd` 是否配了 tcp:// 监听；② 防火墙是否放行 2375/2376。
- 安全代价（判据性硬伤）：未认证 Docker API ＝ 服务器 root 等价权；这台机器跑着 12 个数据库容器与备份链路（srv-backup-restore 作业法），裸开 2375 不可接受；上 TLS + 客户端证书是必须项，运维面陡增。
- 次生代价：镜像要在服务器上再拉/存一份（mssql/oracle 数 GB，拉取需走镜像加速——服务器上 daocloud/1ms.run 经验已有）；Testcontainers 随机端口映射落在放行段外 → 本地连不上容器（须用 `testcontainers.port.range` 类机制钉进 20000-29999，占用宝贵的放行额度）；ryuk 资源回收、Windows 客户端路径语义都要过一遍。
- 判据（何时才值得）：需要「每次门禁都拉起全新干净引擎」的强隔离语义、且实例池满足不了时。当前外指实例池已够 → 本方案性价比最低。

### 方案三：无 Docker 档位（Docker 测试显式 skip/独立任务，门禁与 Docker 彻底解耦）
- **现状已经是这个形态**：默认 `-PmpvDockerTests` 缺省 false → 6 例秒级跳过；真机判据走 `dialectExternalVerify`（独立任务，不挂 testShards）。`DockerComposeLabConsistencyTest` 本来就无 Docker 依赖、照常跑。
- 剩余可做（低成本清理券，与本调研同批立项即可实施）：① 更新 `docs/MEMORY.md:30` 与用户级 AGENTS.md 的预检条款——把 `timeout 10 docker info` 从「跑全量前必做」改为「仅 `-PmpvDockerTests=true` 手工跑前必做」，消掉 2026-11-07 这类「预检止步」白等；② 把 pitfall 文档里 DockerComposeLabConsistencyTest 的误排更正。
- 风险：几乎为零（文档-only）。收益：纪律与现实对齐，省每轮全量的一次预检摩擦。

### 方案四：维持现状（本地 Docker + 预检止步上报）
- 即保持「默认门禁不碰 Docker + 想跑容器用例时人工 `-PmpvDockerTests=true` + 预检纪律照旧」。零改动，但保留两个已知的摩擦：预检与默认门禁已脱钩造成的语义混乱（§二）；用户新纪律「不许自行启动本地 Docker」与「想跑容器用例必须本地 Docker」的矛盾将继续悬置（五容器用例实际上进入长期不可跑状态，读数面完全靠方案一/三的真机矩阵）。

### 倾向（不拍板）
方案三（立即，文档-only）＋ 方案一（择期，测试类改造）。理由：三直接兑现用户「不碰本地 Docker」的纪律且零风险；一的方向与 2026-10-31 定例同向且资产全部现成（匹配表、矩阵通道、CI 腿都已实证），改造面只在一个测试类；二的安全代价与运维面在这个资产结构下没有必要性。

## 七、CI 影响（GitHub Actions）

- **gate（build-plugin.yml workflow_dispatch mode=gate，默认）**：5 片 testShard + testFast + verifyGate + verifyMarketplace。Docker 测试默认关 → **gate 现在就不跑任何 Docker 测试**，本地改指向不影响 CI 读数面（`mpv-testing.gradle.kts:182-186` 构建侧注入对所有任务生效）。
- **nightly-full.yml**：① 方言腿＝真机矩阵代表子集（secret `DIALECT_MATRIX_PROPERTIES`，CI_DIALECT_KEYS = mysql84,pg16,panwei_a,gaussdb_kernel503，35min 步骤预算，跨洋 RTT 已按往返数折算进预算口径）——**CI 已经在吃「服务器实例外指」的收益**；② DB 集成腿＝secret `MPV_DB_URL` → `103.36.220.245:25900/mpj_demo`（2026-11-05 部署，发行检查清单 §83）——testDb* 通道已指向服务器。
- 改指向后的 CI 受益评估：方案一不改变 CI（CI 本就不跑 Testcontainers）；唯一潜在增益是把 Docker 等价读数面从「本地手工」并入矩阵（CI 方言腿自动继承）。方案二若实施，GitHub runner 也无需本地 Docker——但 CI 侧同样已经不需要。

## 八、待验证前提（P）

- **P-1**（实测）：五分片/testFast 现行轮次里 `DialectDockerVerificationTest` 的实际落桶与跳过读数（预期：fastBucket、6 skipped ~0s）——下次全量轮从 test-results XML 直接取证即可，零成本。
- **P-2**（待上服务器核实）：103.36.220.245 的 dockerd 是否监听 TCP、2375/2376 是否被防火墙放行——方案二的前置，未核实前方案二只能停在「文档推断：未暴露」。
- **P-3**（实测）：MariaDB 10.11 服务器实例能否承载容器 11.2 用例的同套读数（套件通过即等价成立；不通过的差异面需登记「11.2 缺真机」）。首跑用 `mpvs.dialect.*` 单例入口即可验，零代码改动。
- **P-4**（实测）：共享实例上两轮套件并发的实际互删概率与形态（§五-3）——如实施方案一，先造一次双轮并发对照再决定要不要加运行唯一前缀。
- **P-5**（文档推断，需用户确认口径）：`timeout 10 docker info` 预检条款改窄是否用户认可——涉及用户级 AGENTS.md 与 docs/MEMORY.md 两处纪律文件。

## 九、验收（若后续立项实施）

1. 方案三券：docs/MEMORY.md 与（用户授权后）AGENTS.md 预检条款改窄，且 pitfall 文档误排更正——grep 全仓不再出现「跑全量前必验 docker」的未限定条款。
2. 方案一券（如立项）：`-PmpvDockerTests` 缺省路径下五容器用例改为外指后，`dialectExternalVerify` 或单例入口对 §三 匹配表五实例全绿（贴 JUnit 计数与 summary.md 摘要）；反向验证＝把外指 URL 指向不可达端点 → 必须落在 TIMEOUT/SKIP(连不上) 桶而非静默假绿。
3. 时间预算口径：换链路/换实例后首跑先取实测耗时回填 `timeoutSeconds`，不留默认 300s 裸奔（T-MatrixInstanceTimeout 纪律延续）。

## 十、证据分级台账

| 级别 | 内容 |
|---|---|
| 实测（文件:行 / run 号） | §二 清单全部 grep 读数；§三 匹配表实例端点（.local 文档 + 现行矩阵文件）；§四 CI 跨洋读数（nightly run 42315646150 / 2026-11-05 run#9）；半活态三态（pitfall 文档 2026-09-23 实测）；§五 代码级表名/清理逻辑 |
| 文档推断 | 服务器 dockerd 未暴露 TCP（P-2，未连服务器）；方案二各项次生代价；MariaDB 版本差影响面 |
| 未验证 | P-1 的现行轮次 XML 读数；P-3/P-4 的对照实验；方案一实施后的端到端读数（本调研未实施） |

## 十一、实施记录（2026-11-07，方案一 + 方案三收尾）

用户拍板组合「方案一（容器用例外指收编进矩阵通道）+ 方案三收尾（预检条款改窄）」。分支 `t-docker-gate-offload`（基线 master `31e3c66d`）；提交 `a7c924e2`（测试类 + gradle 注入）＋ 文档笔（本节、MEMORY、经验总结 R4、LESSONS L19）。

### 11.1 前提核实结果（P-1~P-5 逐条）

| 前提 | 结论 | 证据 |
|---|---|---|
| P-1 | **成立**。类落 fastBucket：源码零 `com.intellij` 引用（`mpv-testing.gradle.kts:399-402` 划桶规则）、分片显式剔除 fastBucket 类（`mpv-testing.gradle.kts:765`）、`shardExcludeClassNames` 缺省为空（:385-387）；默认路径 XML：`tests=6 skipped=6 failures=0 errors=0`，类耗时 **0.014s** | 本工作树定向跑 `build/test-results/test/TEST-...DialectDockerVerificationTest.xml`（2026-11-07）。注：全量轮未跑（本任务禁全量），以定向 XML + 静态分桶证据采信；全量轮 XML 读数留待下次收口顺带核对 |
| P-2 | **不适用**——方案二未实施，未连服务器（派活边界禁 SSH），dockerd TCP 暴露问题继续挂「待上服务器核实」 | — |
| P-3 | **实测通过**：MariaDB 10.11.11（`103.36.220.245:23306/xc3_test`，矩阵条目 `mariadb`）承载容器 11.2 用例的同套读数——runTcSuite + 全量保留字扫描全绿（单腿 469.6s）→ 按等价读数采信，登记「MariaDB 11.x 新语法面无真机对位，等价性以套件通过为界」 | 五实例绿跑 XML（11.3 表） |
| P-4 | **未实验，按派活边界只登记**：共享实例固定表名互删债入 LESSONS L19（Seen 1×），根治方案「运行唯一 schema/表前缀」挂账待复发或外指面再扩大时立项 | `docs/LESSONS.md` L19 |
| P-5 | **成立**：用户级 AGENTS.md 第 19 行已由主控改为「Docker 腿默认关且在 testFast 桶不进分片；仅显式跑 Docker 测试腿时才需 `timeout 10 docker info` 预检；禁自行拉起本地 Docker Desktop」（本任务未碰该文件）；仓内两处同批改窄（11.5） | 用户级 AGENTS.md:19（只读对齐）、本仓 git diff |

### 11.2 方案一改动面

- `src/test/kotlin/cn/song/mybatisplus/visualizer/sql/verify/DialectDockerVerificationTest.kt`
  - :62-77（EXTERNAL_MATRIX_PROPERTY / DEFAULT_EXTERNAL_MATRIX_PATH / DEFAULT_ENGINE_MATRIX_KEYS / 登录超时常量）：五引擎 → 矩阵 key 默认映射 mysql→mysql84、postgresql→pg16、mariadb→mariadb、sqlserver→sqlserver2022、oracle→oracle23，同文件写 `dockerext.<engine> = <key>` 可逐引擎改指（key 指到不存在条目＝该腿回落容器）；
  - :107-125（resolveExternalEndpoint / channelFor）：读矩阵文件解析外指端点，文件缺失/条目缺 url 或方言名 → null（回落容器），只读文件不连库；
  - :129-134（assumeRealEngineTestsEnabled）：总开关前置——`mpvs.docker.tests.enabled` 非 true 时六例全跳（外指腿零网络触达、容器腿零 daemon 触达）；
  - :158-195（runExternalLeg）：外指腿——方言名逐字核对防静默回退 MySQL、`upsertModeOf` 推导 upsert 策略、登录超时 20s、**连不上判红不跳过**、stdout 留 channel=key=url 取证行；
  - :196-345：五用例改「外指优先、容器兜底」双通道，容器路径原样保留为私有方法（runMysqlContainerLeg 等），仍受 assumeDockerAvailable 守卫。
- `buildSrc/src/main/kotlin/mpv-testing.gradle.kts` :187-196：向全部 Test 任务注入 `mpvs.dockerext.matrix`（默认 `layout.projectDirectory/.local/dialect-external-matrix.properties`，`-PdialectExternalMatrix=<路径>` 覆盖；providers 配置期求值，与配置缓存兼容）。
- `dialectExternalVerify`、发版门禁口径、CI workflow：**零改动**（2026-10-31 定例不变）。

### 11.3 容器路径取舍 + 反向验证红绿读数

**取舍：保留容器路径作兜底（外指优先）**——调研册方案一写「外部端点优先、无容器回退」分支，未写死是否移除容器；按保守项保留。理由：无矩阵文件的环境（其他开发者机器、离线场景）仍可用原容器路径手工验证，路径语义零退化；外指优先则兑现「本地不依赖 Docker Desktop」。

| 场景 | 命令要点 | 读数（JUnit XML） |
|---|---|---|
| 默认门禁（P-1 动态） | 无 `-PmpvDockerTests` | `tests=6 skipped=6 failures=0 errors=0`，类耗时 0.014s——零网络零 daemon |
| 外指端点不可达 → 必红 | 探针矩阵（工作树 `.local/docker-offload-probe.properties`，跑后已删）`dockerext.mysql=mysql_probe` 指向 `127.0.0.1:1` + `-PmpvDockerTests=true` | `tests=6 failures=1 skipped=5`：真实MySQL80 **FAIL** `CommunicationsException: Communications link failure`（0.341s 快败，判红不静默跳过）；其余四腿 SKIP「Docker 不可用，跳过…」（无条目回落容器、无 daemon 显式跳过，**非假绿**）；第 6 例 SKIP「未提供 -Dmpvs.dialect.jdbcUrl」——同轮钉住全部三条路径 |
| 五实例绿跑（验收 2） | `-PmpvDockerTests=true -PdialectExternalMatrix=<主仓 .local 矩阵>` | `tests=6 failures=0 errors=0 skipped=1`（第 6 例按设计跳过）；分腿耗时：MySQL84（8.4.6）51.5s / PG16（16.14）6.0s / MariaDB（10.11.11）469.6s / SQLServer2022（CU27-GDR）310.1s / Oracle23（Free 23）25.9s，墙钟 14m39s（串行单 JVM，词级扫走默认串行） |

时间预算口径（验收 3）：五腿串行 ≈ 15min，远低于方言矩阵全量（14 实例）本机读数；MariaDB 腿 469.6s 侧证矩阵 `mariadb.timeoutSeconds=600` 余量合理，无新预算裸奔。

### 11.4 方案三收尾（措辞同步清单）

- `docs/MEMORY.md:30`：「跑全量前先 `timeout 10 docker info` 验 daemon 活性」→「Docker 测试腿默认关（`-PmpvDockerTests=true` 才启用）且在 testFast 桶、不进五片分片（20261107 T-DockerGateOffload 定例）；仅显式跑 Docker 测试腿时才需预检；引擎未启动禁自行拉起本地 Docker Desktop」——与用户级 AGENTS.md 逐字同口径。
- `docs/经验总结/20260923-1055-*.md` R4 行：同向改窄，注明取代旧「跑全量前必验」口径与开关前置依据。
- pitfall 文档（`pitfall-docker-guard-hangs-indefinitely.md`）中 DockerComposeLabConsistencyTest 的误排更正：该文档属 memory-archive 历史存档，历史记录不改写原貌，勘误落在本节——O-72 清单把 DockerComposeLabConsistencyTest 排进 Docker 依赖类是多余的（它零 DockerClientFactory/零容器操作，见调研册 §二）。

### 11.5 债务与 not-run

- **共享实例互删债**（§五-3）：LESSONS L19 登记，运行唯一前缀未实施（派活边界「只登记不重构」）。
- CHANGELOG `[Unreleased]`：**不回填**——本改动面为测试内部与构建脚本，非 `src/main` 用户可感改动；真机读数面由 dialectExternalVerify 承担的定例不变。
- not-run：全量 `testShards`/`testFast` 整腿未跑（派活明令禁全量）；`dialectExternalVerify` 未跑（非本券对象）；CI workflow 未改未跑；方案二（DOCKER_HOST）未实施未核实。
- 探针文件 `.local/docker-offload-probe.properties` 验证后已删。
