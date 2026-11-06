# T-MatrixInstanceTimeout：矩阵实例级超时 + CI 方言腿扩容（2026-11-06）

> 立项人：主控（用户令 2026-11-06「先立项，然后交给子智能体去实施这两个」）。
> 券 A＝实例级预算/看门狗（让一个挂起的实例不再拖死整轮）；券 B＝把磐维 A/B 与 GaussDB 内核 503 纳入 CI 方言冒烟子集。
> 两券写集互斥、**串行交付**（B 的预算算式取 A 的实测读数），不合流不成 CI 项。

## 一、现象与证据（本轮实测，不是推断）

**实例侧读挂起，每次都要人工杀，且没有超时兜底：**

| 时点 | 卡在哪 | 线程栈（jstack 实证） | 人工处置 |
|---|---|---|---|
| 15:44（PID 22088/40060） | `vastbase` | `cn.com.vastbase.core.PGStream.receiveChar` → `SocketDispatcher.read0` | 杀进程 |
| 17:15（全量 14 实例连跑） | `sqlserver2022` | `com.microsoft.sqlserver.jdbc.TDSChannel.read` → `SocketDispatcher.read0` | 杀进程 |
| 17:24（批 A） | `panwei_a` | `org.panweidb.core.PGStream.receiveChar` → `SocketDispatcher.read0` | 杀进程 |

- 三次都**超过 3 分钟无任何进展**（CPU 累计仅数秒），只能人工中止；中止后整轮读数作废。
- **服务端不是元凶**：磐维 A 实例 `pg_stat_activity` 查过——无 `xact_start` 非空的长事务、无锁等待，连接多为 `idle`。判**实例/驱动侧读挂起**，属环境/内核，不是产品缺陷。
- **杀进程的连带损害（已修）**：强杀正好落在 Gradle transform 解包窗口，把共享的 `caches/9.7.0/transforms/cfd91130…/ideaIU-2024.3.7.1-win` 删成半截（`bin/` 空、缺 `idea64.exe.vmoptions`），此后任何构建报 `NoSuchFileException`／`Cannot resolve 'product-info.json'`；修法＝删整个 hash 目录 + 按 PID 重启 daemon（全局记忆 `pitfall-gradle-transform-partial-delete`）。**这条本身就是「必须让实例级超时自己收口、别再靠人工杀」的理由。**

**夹具现状**（`src/test/kotlin/cn/song/mybatisplus/visualizer/sql/verify/DialectExternalMatrixVerificationTest.kt`，172 行）：

- 只有一个 `@Test`，`for (key in keys)` 串行跑实例；每实例的工作＝`DialectVerifySupport.runTcSuite(c, spec)` + `sweepReservedWords(c, spec, swept)`。
- 唯一的超时是 `DriverManager.setLoginTimeout(20)`——**登录**超时，管不到登录之后的语句读。
- 逐实例 `try/catch(Throwable)`：**只有抛异常能中断**；挂在 socket 读里既不抛也不返回，`catch` 永不进入 → 整轮无限期停在该实例上。
- 结局桶只有 `passed / skipped / failed` 三个，没有「超时」这个可辨类别。

**CI 现状**（`.github/workflows/nightly-full.yml`，job `dialect`）：

- 矩阵内容来自 secret `DIALECT_MATRIX_PROPERTIES`（**已配置**，2026-11-05 run 42128985512 实测跑过：日志「方言矩阵已落盘（子集 **mysql84,pg16**；原矩阵 13 个实例中取 2 个）」，该腿耗时 1147.6s ≈ **19.1 分钟**）；步骤 `timeout-minutes: 40`。
- 只跑子集是 run#6 实测定的：全量 12 实例在 2 核 runner 上 **91 分钟仍未完**（跨洋 RTT × 每实例上千次建表/查询），本地同一套 5~10 分钟。
- 本轮主打的磐维方言**不在**子集里 → 目前 CI 冒烟对磐维零覆盖。

## 二、目标

**券 A**：任一实例「无进展到点」即判该实例**未验证**（可辨结局）并继续下一个实例，整轮不再被单个坏实例拖死；超时必须写进 summary 与 JUnit 输出，**既不计 PASS 也不静默跳过**。

**券 B**：CI `dialect` 腿的子集覆盖本轮主打方言（磐维 A Oracle 兼容 / 磐维 B MySQL 兼容 / GaussDB 内核 503），并把步骤预算与「实例级超时」对齐，使得**即使全部实例都挂起，这一步也能在预算内自己结束并按设计判红**。

## 三、非目标

- `src/main` **零改动**（纯测试装置与 CI 配置）。
- 不追查「实例侧为什么挂起」（环境/内核问题，只在文档里登记为已知边界）。
- 不动 `update/update.json`（用户专属）。
- 不新增 gradle 任务名，沿用 `:dialectExternalVerify`。

## 四、待验证前提（P——实施者先证伪再动手，报告里逐条给结论）

| 编号 | 前提 | 不成立时的退路 |
|---|---|---|
| P1 | 从看门狗线程调 `Connection.close()` / `Statement.cancel()` 能让挂在 socket 读的驱动**抛错返回**（pg 族 close 会关 socket，通常有效；SQL Server TDS 待验） | 退到「守护线程 + 放弃该实例」；此时**必须**同时验证被放弃的线程不阻止 JVM 退出（见 P4） |
| P2 | 实例开始后连接对象能被另一线程看见（需在拿到连接后立刻放进共享引用，而不是 `use{}` 里藏着） | 把「建连接」也算进预算内，超时前用 `DriverManager.setLoginTimeout` 兜住 |
| P3 | `Controller.close()` 对正在执行的语句有效（而非只标记连接关闭） | 改调 `statement.cancel()`；两者都无效就只能靠 P4 |
| P4 | 「放弃的线程」不会让 JVM 无法退出（本机 Windows 与 **Linux runner** 行为可能不同） | 若 Linux 上会吊住 JVM，则本层只做「标记 + 继续」，并明确写出「整轮结束仍依赖外层 shell/CI 步骤超时兜底」 |
| P5 | CI 新增 3 实例的耗时预算（参照：mysql84 18.6 min、pg16 3.3 min；磐维 A 109 词 / 磐维 B 262 词 / GaussDB 503 197 词） | 超预算就减实例（先保磐维 A + GaussDB），或按实测上调步骤 `timeout-minutes` |
| P6 | `nightly-full.yml` 支持 `workflow_dispatch` 手动触发（券 B 的验收要靠它，不必等 nightly 排期） | 用 `gh workflow run` + 读 job 日志；若 dispatch 不带 secret，则在计划册登记「只能等 nightly」 |

## 五、券 A：实例级预算与看门狗

分支 `t-matrix-instance-timeout`，工作树 `.worktrees/matrix-timeout`（自 master 切）。

**写集**（不要碰别的文件）：

- `src/test/kotlin/cn/song/mybatisplus/visualizer/sql/verify/DialectExternalMatrixVerificationTest.kt`
- 新增支撑类（择一：`DialectVerifySupport` 内新增顶层可见函数，或新建同包 `InstanceBudgetSupport.kt`）——**在报告里写明选择理由**
- 新增/改名其单测（同包，例如 `InstanceBudgetSupportTest.kt`）
- `dev-support/dialect-external/matrix.example.properties`（补 `timeoutSeconds` 说明）
- `docs/开发计划/20261106-1757-T-MatrixInstanceTimeout-矩阵实例级超时与CI方言腿扩容-开发计划.md`（**只改你工作树里的副本**，追加 §十 实施记录）

**设计要点**：

1. 预算取值优先级：实例条目 `timeoutSeconds` → 系统属性 `-Dmpvs.external.instanceTimeout`（秒）→ 默认 **300s**。
2. 每实例在**独立单线程 executor** 上执行；`future.get(budget)` 超时即判 `TIMEOUT`。
3. 结局分四桶：`PASS / SKIP / TIMEOUT / FAIL`，summary 汇总行写成 `通过 X / 跳过 Y / 超时 Z / 失败 W`，**每桶各列明细行**（超时行必须带预算值与该实例 key）。
4. `optional=true` 的实例按既有语义把 TIMEOUT 降为 SKIP（**这是唯一的降级通道**，且降级要在 summary 里可见：「SKIP(超时)」）。
5. 超时处置顺序：`statement.cancel()` → `connection.close()` → `thread.interrupt()` → 仍未结束则标 daemon 并放弃（按 P1/P3/P4 结论决定做到第几步，报告里写清做到哪一步、哪些没做到）。
6. 保留既有四条判红底线（方言名逐字命中 / 词表 ≥ 50 / 至少一条 PASS / `skipWords` 必带 `skipReason`），**不得为了超时改动它们**。
7. 不许用「把预算调大」当修复；不许把 `skipWords` 用成超时兜底。

**定向测试与反向验证（必须交读数）**：

- 新增一个**不摸真机**的用例：注入「永久阻塞的假任务」，断言 ①到点判 TIMEOUT ②后续实例照常执行 ③TIMEOUT **不计入** PASS——这是 P1 不成立时仍然站得住的唯一部分，必须独立存在。
- **反向验证**：把看门狗摘掉（不设预算直接跑）→ 该用例必须转红；贴「摘掉前红 / 恢复后绿」两次读数。
- **真机超时路径实证**：拿一个单实例子集（例如 `panwei_a`），把预算设成 `-Dmpvs.external.instanceTimeout=1`，必须看到该实例落在 TIMEOUT 桶且整轮在秒级结束（**证明超时路径在真机上真的能收口**）——这条是本券最关键的读数。
- 实例耗时读数（供券 B 定预算）：分别用单实例子集跑 `panwei_a` / `panwei_b` / `gaussdb_kernel503`，交出 summary 里的 `PASS key … Nms` 三行原样。

**禁止**：不跑全量、不跑 `testShards`/`verifyGate`/`verifyPlugin`/`buildPlugin`、不 `clean`、不 `--stop`；长命令一律后台启（Bash 的 `run_in_background`），不要 sleep 轮询。

## 六、券 B：CI 方言腿扩容

分支 `t-ci-dialect-keys`，工作树 `.worktrees/ci-dialect-keys`（自 master 切）。

**写集**：

- `.github/workflows/nightly-full.yml`
- `docs/发行说明/T-ReleaseChecklist-发行检查清单.md`（第 86 行「CI 上只跑代表实例子集」那段）
- 本计划册（你工作树里的副本，追加 §十一）

**内容**：

1. `CI_DIALECT_KEYS` 从 `'mysql84,pg16'` 扩到 `'mysql84,pg16,panwei_a,gaussdb_kernel503,panwei_b'`（顺序＝挑选顺序，预算吃紧时按券 A 读数从尾部砍）。
2. 步骤 `timeout-minutes` 按算式定：**最坏情况＝实例数 × 默认预算 300s + 冷启动余量**（5 × 5 min = 25 min，+10 min ≈ 35 min，现 40 min 仍够；若券 A 实测单实例耗时更长，按实测上调并把算式写进注释）。
3. 注释里保留「全量仍以本地 `releaseGate` 为准」这条口径**不变**，并新增一句本版改动原因：磐维是 2026.11.6 的主打方言，CI 冒烟必须覆盖。
4. 若 P6 成立，在注释里写上手动触发命令（`gh workflow run nightly-full.yml`）便于事后复验。

**验收（硬性）**：至少一次 `workflow_dispatch` 跑通，贴出 `dialect` job 的「方言矩阵已落盘（子集 …）」行 + summary 工件内容（`通过/跳过/超时/失败` 行）。**未跑通前不得写「CI 已覆盖」**（对照读数：当前 run 的落盘行只含 `mysql84,pg16`）。触发 CI 属对外动作，由主控等用户授权后执行或由用户手动触发。

## 七、门禁与合流

- 券 A 的验证＝定向类 + 反向验证 + 真机单实例子集读数（含 1s 预算的 TIMEOUT 实证）。
- 券 B 的验证＝一次真实 CI `dialect` 跑通（日志 + 工件）。
- 合流顺序：**A 先合，B 后合**（B 的预算与「超时不会拖死」都依赖 A）。两券写集互斥，但 B 的结论依赖 A → 串行交付，不合流不成 CI 项。
- 本改动不触 `build.gradle.kts`／依赖版本／`plugin.xml` → 无需 `verifyGate`／`buildPlugin`（CI 那一步本身就是验证）。
- 合流后按「提交即回填」补 `CHANGELOG.md`：本改动对用户不可感（测试装置与 CI），按既有惯例可在 `[Unreleased]` 记一条内部项或省略——由合流时对照 `CHANGELOG.md` 现状决定。

## 八、风险与口径

- **看门狗留下线程**：CI 跑 5 个实例，若「放弃」路线累积线程/连接，长跑有隐患。报告必须给出每实例的线程名与 daemon 标记取证。
- **纳入磐维后 nightly 会因实例抖动变红**：这是把问题可见化的代价，口径上接受；若要给磐维 A 标 `optional=true`，**必须写明理由**，不得用来掩盖渲染缺陷。
- 若 P1/P3/P4 都不成立：券 A 只能做到「标记 + 继续 + 依赖外层超时」，此时**如实登记**，不许写成「已解决挂起」。
- 口径红线：**超时 ≠ 通过；超时 ≠ 跳过**（除非矩阵里显式 `optional=true`）。

## 九、派活说明（两券共用）

- 开工前 `python C:\Users\USER\.zcode\cli\parallel_tasks.py check` → `register --name <券名> --sandbox <你的工作树>` → 完工 `done --name <券名>`（**禁裸 done**）。
- 构建并发上限 2 路；如与主控或其他券撞车，记录证据交回，不要抢占。
- **共享 Gradle transform 已因本轮强杀损坏过一次**：若遇到 `NoSuchFileException … idea64.exe.vmoptions` 或 `Cannot resolve 'product-info.json'`，先停手、在报告里点名，不要自己删别人的缓存。
- 每完成一件可提交的就**立即提交**（用 `-F <文件>` 传信息，别用 `-m` 带中文/反引号）。
- 计划册回写**只写你工作树里的副本**。
- 报告格式：读数（命令 + 原始输出关键行）／未做项／待验证前提逐条证伪结论／残留风险。

## 十、实施记录（券 A）

<!-- 待回填 -->

## 十一、实施记录（券 B）

分支 `t-ci-dialect-keys`，工作树 `.worktrees/ci-dialect-keys`（自 master `629dcba8`）。

**提交**：

- `85ddc139` `ci(dialect): CI 方言冒烟子集 2 → 5 实例（纳入磐维 A/B 与 GaussDB 内核 503）+ 按算式重算预算`

### 改动清单（前后对照）

| 文件 | 位置 | 改前 → 改后 |
|---|---|---|
| `.github/workflows/nightly-full.yml` | `dialect` job 的 `timeout-minutes` | `50` → **`100`** |
| 同上 | 「真机方言验证」步的 `timeout-minutes` | `40` → **`90`** |
| 同上 | 「落方言矩阵」步 `env.CI_DIALECT_KEYS` | `'mysql84,pg16'` → `'mysql84,pg16,panwei_a,gaussdb_kernel503,panwei_b'` |
| 同上 | 三处注释 | ①`dialect` job 头部补预算算式/取数/两个前提 ②`CI_DIALECT_KEYS` 处补扩子集原因、砍序、手动触发命令 ③落盘步注释补「本步只按 key 摘录、不解析 `instances` 行」与口径重申 |
| `docs/发行说明/T-ReleaseChecklist-发行检查清单.md` | 第 86 行「CI 只跑代表实例子集」段 | 子集 2 → 5 实例 + 原因 + 预算算式 + 触发复验命令 + 两个前置 + 一处口径缺口；**「全量矩阵以本地 `releaseGate` 为准」原样保留** |

### 预算算式（已写进 workflow 的 `dialect` job 头部注释）

`步骤 timeout = Σ(每实例预算) + 冷启动与收尾余量(≈8min)`；
每实例预算 ＝ 矩阵条目 `timeoutSeconds` → `-Dmpvs.external.instanceTimeout` → 默认 300s。
必须 ≥ Σ 的理由：最坏情况是**每个实例都挂到各自预算**，那时期望看门狗逐个判 TIMEOUT 并照常产出
`summary`；步骤上限小于 Σ 就退化成 CI 掐断进程——没有 summary 工件，也分不清「超时」与「崩溃」。

按 CI 实测最坏率 **4.3s/字**（mysql84 262 字 → 1113s；pg16 86 字 → 196s ≈2.3s/字）外推本子集：

| 实例 | 保留字 | CI 投影 | CI 实测 | 建议矩阵 `timeoutSeconds` |
|---|---|---|---|---|
| `mysql84` | 262 | 1127s | 1113s（模型自校准） | 1200 |
| `pg16` | 86 | 370s | 196s | 400 |
| `panwei_a` | 109 | 470s | 本机 66.5s（非 CI 读数） | 500 |
| `gaussdb_kernel503` | 197 | 850s | — | 900 |
| `panwei_b` | 262 | 1130s | — | 1200 |

Σ 预期 ≈ 3950s ≈ 66min；Σ 预算上界 = 4200s = 70min → 步骤 90min（70+8=78，再留 12min 抗抖动）、
job 100min。

### P5 / P6 结论

- **P6 成立**：`nightly-full.yml` 的 `on:` 同时声明 `schedule` 与 `workflow_dispatch`（文件第 28~31 行），
  pyyaml 解析出的触发器为 `['schedule', 'workflow_dispatch']`；仓库历史夜间轮次本就全是 `workflow_dispatch`
  （清单第 83 行：「历史上 9 轮夜间全是 workflow_dispatch」）。故 `gh workflow run nightly-full.yml`
  可用，命令已写进 workflow 注释供事后复验。
- **P5 不成立（计划里两个默认假设都站不住），且无法由现有读数定论**：
  1. **「5 × 默认 300s = 25min，+10min 余量 → 现值 40min 仍够」不成立**。300s 是券 A 的**默认**单实例
     预算，而 mysql84 在 CI 实测 1113s；矩阵不给显式 `timeoutSeconds`，看门狗会在第 5 分钟把这条
     现在会通过的腿判成 TIMEOUT（现存绿腿变红）。最坏值必须按矩阵显式预算的 Σ 计，不是 5×300。
  2. **「B 的预算取券 A 的实测读数」不能直接照搬**。券 A 的读数是**本机国内网络**读数（`panwei_a`
     109 词 66.5s ≈ 0.61s/字），比 CI 低一个量级（同字数按 CI 的 4.3s/字 ≈ 470s）。CI 预算只能按跨洋率
     外推；券 A 的读数在预算上的作用是「证明实例没挂、能跑完」，不是给秒数。
  3. 结论：变更后步骤预算由 40min 提到 90min。这是 cap 不是耗时，只有挂起时才生效；按外推的 66min
     预期，与两实例子集时的 27.8min 相比，增加的部分就是追加三个方言的必然成本（跨洋按字计费）。
     若要压回去，只有三条路：减实例（按尾部砍序）、减保留字扫描量、或把 runner 挪到近网。

### 取证读数（本券不跑 gradle、不触发 CI）

1. YAML 语法：`python -c "import yaml; yaml.safe_load(...)"` 通过；解析结果
   `dialect.timeout-minutes = 100`、验证步 `90`、
   `CI_DIALECT_KEYS = mysql84,pg16,panwei_a,gaussdb_kernel503,panwei_b`、触发器 `['schedule','workflow_dispatch']`。
2. 落盘逻辑仿真（把 workflow 里「落方言矩阵」步的摘录代码原样搬进临时 `.ps1`，喂**本机**矩阵文件
   充当看不到的 secret，输出只落 `$TEMP`、不覆盖仓库 `.local/`）：
   - 命中 **5/5** key（缺 key 会硬抛，未抛）；口径行
     `方言矩阵已落盘（子集 mysql84,pg16,panwei_a,gaussdb_kernel503,panwei_b；原矩阵 15 个实例中取 5 个）`；
   - `panwei_a.skipWords = rowid` 与 `skipReason` 原文带过来 → **CI 不会因 rowid 假红**。

### 未做项 / 未核实项（交回主控）

1. **未触发 CI**：验收硬性要求「至少一次 `workflow_dispatch` 跑通」属对外动作，等用户授权后由主控执行。
   在此之前**不得写「CI 已覆盖」**（对照读数：当前最后一次 run 的落盘行只含 `mysql84,pg16`）。
2. **secret `DIALECT_MATRIX_PROPERTIES` 内容未核实**（助手看不到）。间接推断它**大概率仍是 11-05 版、
   缺 `panwei_a`/`panwei_b`**：2026-11-05 run#9 日志记「原矩阵 **13** 个实例」（＝当时 secret 的 key 数），
   而本机 `.local/dialect-external-matrix.properties` 今天已是 **15** 个 key，多出的两条正是磐维。
   若未同步，落盘步会对缺 key **硬抛**（整条腿红）。→ 首次 dispatch 前必须把更新后的矩阵文件全文重写进 secret。
3. **矩阵实例预算未落地**：`mysql84`/`panwei_b` 的显式 `timeoutSeconds`（≥1200s，`pg16` ≥400s）要写进
   `.local/dialect-external-matrix.properties`，该文件由主控维护，不在本券写集内；券 A 的默认 300s 若直接
   生效会把 mysql84 判 TIMEOUT。**这是 A、B 合流前必须一起处理的前置**。
4. **口径缺口（本券发现）**：本机矩阵的 `instances` 行目前**不含** `panwei_a`/`panwei_b`（条目在文件里、
   不在 `instances` 列表）→ **本地 `releaseGate` 眼下不跑磐维**，「全量矩阵以本地 `releaseGate` 为准」
   对磐维暂时不成立，磐维的常驻覆盖只有本条 CI 冒烟。要恢复口径需把这两个 key 加进 `instances`。
5. 券 A 的实例级超时（`timeoutSeconds` 语义、TIMEOUT 桶）**尚未合流**，本券注释里的预算语义按本计划
   §五 的口径书写；若 A 落地时改了属性名或默认值，需按同一算式回算这两处 `timeout-minutes`。

### 还差哪一步才算「CI 已覆盖」

① 把含磐维条目的矩阵全文同步进 secret `DIALECT_MATRIX_PROPERTIES`；② 矩阵给 `mysql84`/`panwei_b`
显式 `timeoutSeconds`（≥1200s）；③ A 先合流、B 后合流；④ 跑一次
`gh workflow run nightly-full.yml`，在 `dialect` job 里读到
`方言矩阵已落盘（子集 mysql84,pg16,panwei_a,gaussdb_kernel503,panwei_b；原矩阵 N 个实例中取 5 个）`，
且工件 `dialect-verify-summary` 的 `summary.md` 有「通过/跳过/超时/失败」行、`panwei_a`/`panwei_b`/
`gaussdb_kernel503` 三条各有 PASS 或可辨结局（超时必须带预算值，不计 PASS）。
