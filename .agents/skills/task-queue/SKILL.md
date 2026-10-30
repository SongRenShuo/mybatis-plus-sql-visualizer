---
name: task-queue
description: 任务目录监视器——把任务 .md 文件丢进 .tasks/pending/，当前会话挂起一个后台守望进程自动发现新任务，唤醒本会话实施并归档，长期循环。当用户说「启动任务监视」「挂起任务监视」「停止任务监视」「任务队列状态」「手动跑一轮任务队列」「暂停任务队列」「任务队列开关」，或询问 .tasks/ 目录协议、queue.state 开关用法、任务文件格式、队列卡住排障时使用。
---

# task-queue 任务目录监视器（会话挂起模式）

原理：本会话用 run_in_background 挂起守望进程 `scripts/watchdog.py`，它每隔几秒扫 `.tasks/pending/`，发现新任务立即输出一行报告并退出；后台任务退出会自动唤醒本会话，代理按 SOP 认领、实施、归档，然后重新挂起下一个守望进程。如此循环，会话保持挂着不结束，来任务自动干活。

生命周期铁律：

- 守望进程依附当前会话，会话关闭监视即停。向用户交代这一点。
- 同一时刻只挂一个守望进程；重挂前确认上一个已结束。
- 唤醒后无论走了哪条分支，只要继续监视就必须重挂守望进程，这是循环的接力棒，忘了挂监视就死了。

## 目录协议

```
.tasks/
├── pending/    新任务入口，.md 文件，文件名即任务名，内容即任务指令
├── doing/      实施中
├── done/       完成归档
├── failed/     失败归档
├── queue.state 手动开关，一行文本：on / paused / off
└── journal.log 心跳与动作流水
```

- 任务文件命名 `YYYYMMDD-HHMM-<slug>.md`，内容是给实施会话的完整任务指令，不要求结构化头部。
- 认领就是原子移动 `pending/x.md → doing/x.md`，同一时刻只有一个守望循环在跑，无并发竞争。
- 一轮只做一个任务，文件名排序最老优先；做不完的留给下一轮。
- 失败移入 `failed/`，不自动重试，防止坏任务文件无限烧 token。
- `doing/` 非空视为上一轮未完（多半是会话中途被杀），journal 告警并提醒用户人工处置，不自动回收。

## queue.state 开关

一行纯文本，三个合法值，用户直接用编辑器手改，守望进程每轮轮询时读取：

- `on` — 正常发现并报告任务。
- `paused` — 守望进程跳过检测但继续轮询，任务在 pending 堆积，改回 `on` 后自动恢复。
- `off` — 守望进程立即输出 `TASKQUEUE STATE off` 退出，代理收到后结束监视循环。

启动写 `on`，停止写 `off`，其余值只由用户手改。文件缺失按 `on` 处理并补写。开关语义即时生效：任务实施到一半改 `off`，本轮照常做完，重挂的守望进程读到 `off` 即退出收尾。

## 任务实施规约

以任务文件内容为指令实施。通用约束：

- 跑任何 `./gradlew` 前先 `python C:\Users\USER\.zcode\cli\parallel_tasks.py check`，有活跃任务则 register 登记、跑完 done 注销（项目并行铁律）。
- 任务文件里的「验收标准」「约束」是硬要求，逐条满足；做不到就按失败归档，不要硬凑。
- 实施会话就是当前会话，AGENTS.md、项目记忆、工具权限全部在场，无需额外注入。

## 启动

1. 创建 `.tasks/` 下四个目录（已存在则跳过），queue.state 写入 `on`（已存在则覆盖，启动即复位开关），journal.log 不存在则建空文件。
2. 与用户确认轮询间隔（默认 10 秒）与守望超时（默认 30 分钟，超时自动重挂）。
3. 后台挂起守望进程（run_in_background）：
   ```
   python <skill目录绝对路径>\scripts\watchdog.py --dir <workspace>\.tasks --interval 10 --timeout 1800
   ```
4. journal 记 `MONITOR ON`，向用户报告：监视已挂起、参数、queue.state 位置与改 `off` 即停的用法。

## 唤醒处理 SOP（后台任务退出时执行）

读后台输出行，按前缀分支：

1. `TASKQUEUE NEW <文件名>` → 认领实施：
   - `mv pending/<文件名> doing/`，journal 记 `CLAIM`，notify CLAIM（可选）。
   - 按任务实施规约执行任务。
   - 成功 → `mv` 进 `done/`，文件末尾追加「--- 实施记录 ---」：日期、commit hash（若涉及提交）、要点三五行；journal 记 `DONE`；notify DONE。
   - 失败 → `mv` 进 `failed/`，追加失败原因与已尝试手段；journal 记 `FAIL`；notify FAIL。
   - 重挂守望进程。
2. `TASKQUEUE STATE off` → journal 记 `MONITOR OFF (by switch)`，确认 queue.state 为 off，notify STOPPED，向用户报告监视已关闭。不重挂。
3. `TASKQUEUE TIMEOUT` → 空转超时，journal 记一行心跳，直接重挂守望进程。不发通知。

## 通知

只在有动作的轮次发系统通知（Windows toast），空轮静默。调用：

```
powershell -NoProfile -ExecutionPolicy Bypass -File <skill目录>\scripts\notify.ps1 <TYPE> <MESSAGE>
```

TYPE 取 `CLAIM` / `DONE` / `FAIL` / `STOPPED`，MESSAGE 为任务文件名（纯 ASCII）。通知失败只记 journal，不重试不阻塞。

## 停止

杀掉后台守望任务（TaskStop），queue.state 覆写为 `off`，journal 记 `MONITOR OFF (manual)`。`doing/` 有滞留文件时如实告知位置，不代处置。

## 查状态

向用户报告：queue.state 当前值、守望进程是否在跑、pending/doing/done/failed 各自的文件数与文件名、journal.log 尾部 20 行。

## 手动跑一轮

不动后台守望，直接按「唤醒处理 SOP」的认领实施分支跑一轮（挑 pending 里最老的任务）。适合验证链路或补跑，做完守望循环照旧。
