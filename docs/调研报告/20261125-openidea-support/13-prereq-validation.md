# 13 前提验证包（P-1 / P-3 / P-4）

- 日期：2026-11-25
- 目的：在写 P1~P6 之前，用最小代价证伪三条**会改变架构**的前提。任一不成立，`ROADMAP.md` 的前提表与该阶段设计都要改。
- 探针仓：`F:\code\Java\Gitee\SongRenShuo\mpsv-openidea-probe`（独立仓，不发行）
- 配套物：`F:\code\Java\Gitee\SongRenShuo\mpsv-openidea-prereq`（夹具 / ws 服务 / probe.jar / 点验手册）

## 1 验证项与判据

| # | 前提 | 探针命令 | 通过判据 | 不通过的后果 |
| --- | --- | --- | --- | --- |
| P-1 | `Project::symbols` 的容器限定查询能列出实体字段 | `probe-symbols` | `User.` 查询命中 **5 个** `kind=field`、`container=User` 的符号（`id/name/age/serialVersionUID/cache`）；类型为空**不算失败**（平台已知限制） | 实体→列名映射退化为文件名启发，F25/F51/F150/F170 精度再降一档 |
| P-3 | `process:java` 能指向**包内** jar 并被批准运行 | `probe-process` | 输出 `probe-ok hello,world` 且 exit=0；`probe/probe.jar` 被接受 | sidecar 改 GraalVM native 或全 Rust；P5 路线重估 |
| P-4 | `websocket-connect` + `websocket-ready` 在 1.15 真机可用 | `probe-ws` | 进入 ready、累计 ≥5 帧、无错误；帧内容形如 `{"seq":n,"t":"..."}` | 捕获链退化 HTTP 轮询，实时性下降；T4 重估 |

## 2 环境与步骤

前置：OpenIDEA 0.1.51 已装；探针插件已 `openidea-plugin link .`（链接信息在 `%APPDATA%\ai.l0ng.openidea\plugins\links.json`）。

1. 启动 ws 服务（**点验 P-4 前必须手动启动**）：
   - Git Bash：`cd F:/code/Java/Gitee/SongRenShuo/mpsv-openidea-prereq/ws-server && ./start.sh`
   - 期望日志：`LISTEN http://127.0.0.1:18765/  ws://127.0.0.1:18765/ws`
   - 探活：`curl http://127.0.0.1:18765/`
2. OpenIDEA 打开 **目录** `F:\code\Java\Gitee\SongRenShuo\mpsv-openidea-prereq\fixture-java`（首次弹信任 → 信任），等索引完成（右下角索引指示消失）。
3. 设置 › 插件 › 右上角 Reload（让探针插件与包内 jar 生效）。
4. `Shift Shift`（随处搜索）或 `Ctrl+Shift+A` 依次执行：
   - `OpenIDEA Probe: Symbols`
   - `OpenIDEA Probe: Process`
   - `OpenIDEA Probe: WebSocket`
   - 每条执行后打开 **Probe Output** 通道看结果（或在通知里看摘要）。

## 2.5 探针就绪读数（2026-11-25）

| 项 | 读数 |
| --- | --- |
| 探针仓 | `mpsv-openidea-probe`，未 git init；`openidea-plugin check` ok，wasm 144.4 KiB / API 1.15 |
| 能力 | 必需 `project.read`；可选 `process:java`(High) / `net:127.0.0.1`(Medium) / `events`(Low) |
| link | `linked songrenshuo.probe`；`links.json` 现有两条（产品仓 + 探针仓） |
| 本地门禁 | fmt=0、clippy=0、`cargo test` 13 passed、build+check ok |
| **P-3 预警** | **安装态读不到包内 jar**：宿主安装只复制「manifest 引用文件 + wasm」，`probe/probe.jar` 不进包；WIT 无安装目录路径，只有 `fs.plugin_dir()`。link 开发态可用仓库根相对路径命中，安装态不可达 ⇒ sidecar 改为「下载到 plugin_dir + sha256」 |

## 3 结果记录（待填）

| # | 时间 | 读数 | 判定 | 备注 |
| --- | --- | --- | --- | --- |
| P-1 | | `User.` count=? ；命中的字段清单？ | 待填 | |
| P-3 | | stdout / stderr / exit | 待填 | |
| P-4 | | 帧数 / 首帧内容 / 有无错误 | 待填 | |

## 4 失败排查顺序

| 症状 | 先查 |
| --- | --- |
| 命令在 Find Action 里看不到 | `links.json` 是否有该目录；`openidea-plugin check` 是否通过；Reload 是否点了 |
| `probe-symbols` count=0 | 索引是否完成；夹具是否以**目录**打开（非单文件）；查询串是否被大小写/前缀规则影响（试 `User`、`User.`、`User#` 三种） |
| `probe-process` 报 `denied` | 能力是否声明 `process:java`（optional 需在安装/设置页勾选）；`java` 是否在 PATH 且哈希通过 |
| `probe-process` 报 路径/找不到 | jar 是否在插件目录 `probe/probe.jar`；换绝对路径再试一次 |
| `probe-ws` 连不上 | ws 服务是否启动；URL 必须是 `ws://127.0.0.1:18765/ws`（带 `/ws`）；`net:127.0.0.1` 是否授权 |
| `probe-ws` 收到 0 帧但 ready | 事件订阅是否注册（`on-event:websocket-ready`）；drain 是否在回调里调用 |

## 5 结论（待填）

- P-1：待填
- P-3：待填
- P-4：待填
- 对 `ROADMAP.md` 前提表的修订：待填