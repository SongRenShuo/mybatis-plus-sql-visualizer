# dev-support — 开发辅助脚本与装置

本目录是**开发辅助资产**（脚本、验证装置、基线文件），**不参与插件构建**，不进 `buildPlugin` 产物，因此这里的改动不影响插件二进制与门禁读数。

> 本机特有值（verifier jar / JDK / 三个 IDE 目录 / 代理 / demo 工程 / Maven 路径）不写死在脚本里：
> 放在 `.local/local.env`（键名见 `.local/local.env.example`）。脚本的取值顺序是
> 「`.local/local.env` → 环境变量 `MPSV_*` → 动态发现或 PATH」，缺失给可读报错。

## 门禁与兼容校验

| 文件 | 用途 | 入口 |
|---|---|---|
| `run-verifier.sh` | 手动跑 IntelliJ Plugin Verifier（绕过被文件锁阻塞的 gradle `buildPlugin`） | `bash dev-support/run-verifier.sh` |
| `verify-full.sh` / `.ps1` | 发布前全量兼容验证（三目标 243/253/262，约 14min） | `./gradlew verifyGate` 等价 |
| `verify-smoke.sh` / `.ps1` | 日常快速冒烟（仅 243，约 1.5min） | — |
| `gradle-gate.ps1` | 机器级 Gradle 并发闸门（同时最多 2 路构建，按内存而非 CPU 定上限） | `powershell -File dev-support\gradle-gate.ps1 -Command ".\gradlew ..."` |
| `ci-verify.ps1` | 每周自动 verifyPlugin 巡检（Windows 计划任务包装，日志落 `verify-logs/`） | 计划任务注册，见文件头 |
| `verifier-ignored-problems.txt` | `verifyPlugin` / `verifyGate` 的豁免清单（`-ignored-problems` 文件） | 被 gradle 任务引用 |
| `verifier-marketplace-baseline.txt` | `verifyMarketplace` 基线（单包形态下已登记的兼容问题块，逐块带运行期安全论证） | 被 gradle 任务引用 |
| `verify-target/` | verifier 的被测插件目录（产物，`.gitignore` 已忽略） | `buildPlugin` 后拷入 |

## 发行

| 文件 | 用途 | 入口 |
|---|---|---|
| `gitee-release.py` | Gitee 自动发行（建版 / 上传附件 / 回读校验 / `--replace` / `--verify-only` / `--delete-only`） | `python dev-support/gitee-release.py --version <ver> --notes <file>` |
| `inspect-plugin-zip.py` | 发布前置检查：插件 zip 的内层 jar 名 / plugin.xml version / MANIFEST / change-notes 目标块与红线扫描 | `python dev-support/inspect-plugin-zip.py <zip>` |
| `export_zcode_wiki_to_gitee.py` | 把 ZCode repo-wiki 导出为 Gitee/GitHub wiki 的 Markdown 布局 | `python dev-support/export_zcode_wiki_to_gitee.py` |

## 更新通道本地验证

| 文件 | 用途 |
|---|---|
| `local-manifest-server.py` + `run-ide-local-manifest.sh` | 本地清单服务器（serve `update/update.json`）+ 起沙盒 IDE，验证更新检查链（不影响 Gitee 用户） |
| `hotswap-local-server.py` | T-DynUpdate 热替换本地验证：serve 真实新版 zip + 动态生成 `update.json`，验证免重启动态安装 |

## 测试辅助

| 文件 | 用途 |
|---|---|
| `test-shard-weights.tsv` | 分片时长权重表（`testShards` 贪心均衡分区的输入） |
| `test-demo/` | 全量测试前置：测试 demo 检出/校验脚本、`mpj_demo`/`mp_demo` 初始化 SQL、`dataSources` 模板；见其 `README.md` |
| `weight-refresh.ps1` | 刷新上表（按历史测试 XML 重新统计） |
| `test-shard-aggregate.py` | `testShards` 聚合公式的仓外替身 |
| `sleep-audit.py` | 固定 `sleep` 审计（扫 `src/test` 里的睡眠等待） |
| `replay-r0-packing.py` | 重放 R0 冷启分片装配，恢复分片包含顺序 |

## 重构辅助（巨型文件拆分，T-M53-II）

两个脚本都按「**逐字符搬运 + 对账**」工作：搬运前后做非空行多重集比对，丢一行即拒绝写盘，
被搬代码不做任何语义改动。用法与语义见脚本头部 KDoc。

| 文件 | 用途 |
|---|---|
| `split-kotlin-test.py` | 拆测试类：按连续区间/主题把方法与顶层声明搬到新的独立测试类（新类自动沿用原父类型）；子命令 `--inspect` / `--apply` / `--list-toplevel` / `--extract-toplevel` |
| `extract-kotlin-members.py` | 拆生产单类巨文件：把类的内聚成员抽为**同包扩展函数**（`internal fun Cls.foo()`）——方法体一字不改、原类内调用点自动落到同包扩展；自动放宽被触及的 `private` 为 `internal`、按引用裁剪新文件 import、补齐 companion 与嵌套类型导入 |

## 性能与资源采样

| 文件 | 用途 |
|---|---|
| `proc-sampler.ps1` | 逐进程 CPU% + Commit 采样（java 进程） |
| `commit-sampler.ps1` | 定时采样本机 Commit charge（内存提交量） |

## 端到端装置

| 目录 | 用途 |
|---|---|
| `e2e-fake-ide/` | 假 IDE 探针（`FakeIde.java`）+ 真实 Spring Boot/MPJ 应用端到端驱动（`run-e2e.sh`），含 `evidence/` 取证日志 |
| `agent-capture-repro/` | agent 捕获链路本机复现装置 + 取证日志（原 `tmp-capturerepro`，2026-10-10 正名；用于「已挂载但 0 条」类问题的复用复现） |
| `plain-fjpf/` | `PlainForkJoinWorkerThreadFactory` 源码——被 `buildSrc/.../mpv-runide.gradle.kts` 编译进 `plain-fjpf.jar` 供 runIde 沙盒启动（**是构建输入，勿删勿移**） |

## 子目录（文档型）

| 目录 | 用途 |
|---|---|
| `mcp-test/` | MCP 工具的端到端测试客户端（`mpsv-mcp-test.mjs`），用法见其 `README.md` |
| `market-probe/` | Marketplace verifier 在线口径复现步骤，见其 `README.md` |
| `sandbox-preset/` | runIde 沙盒走查预置步骤（含 a11y 浅 / pyautogui 坐标路线），见其 `README.md` |

## 其他

| 文件 | 用途 |
|---|---|
| `audit_reachability.py` | 隐藏不可达 / 静默失效的四轴审计（T-ReachAudit 沉淀） |
| `make-screenshot-placeholders.ps1` | 生成 1200×760 截图占位图（二维码居中）供 `docs/images` 用 |
| `check_competitor_watch.ps1` | 竞品 Marketplace 更新哨兵（拉三个竞品插件最近版本做关键词扫描） |
| `append_spec_batch7.py` | **一次性历史脚本**：向 `SqlPreviewSpecMatrixTest.kt` 追加第七批 spec 用例（已执行，保留备查） |
