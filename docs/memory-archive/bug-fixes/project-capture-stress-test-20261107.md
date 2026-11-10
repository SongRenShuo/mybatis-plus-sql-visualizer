---
name: project-capture-stress-test-20261107
description: 增强捕获并发压测（20261107）——三阶段已跑完：插件侧零异常、进程无泄漏；demo 两个固有坏接口（page/mapFamily）单请求即 500 与插件无关
metadata:
  node_type: memory
  type: project
  originSessionId: sess_e57e9560-6824-4f8d-9b3d-2634fabc99fc
---

用户要求对已启动的 mybatis-plus-join-demo 做接口压测，验证增强捕获在「控制台日志疯狂输出 + 并发」下有没有问题。脚本已备妥，**用户明令等发话再开跑，不得自启动**。

环境台账（20261107 实测）：
- demo 进程 PID 65608（IDE Run 启动，JDK8），HTTP 端口 **8082**，监听 0.0.0.0
- mps-sql-agent 已挂载：`-javaagent:...\mpsv-agent\mps-sql-agent-*.jar=port=57661`（agent 端口 57661）
- 数据源：MySQL `jdbc:mysql://localhost:3306/mpj_demo`（application.yml 默认 profile mysql）
- demo 源码在 `mybatis-plus-sql-visualizer-demo/mybatis-plus-join-demo`，端点按 controller：`/mpj/user/*`、`/mpj/full/*`、`/mpj/ext/*`、`/mpj/locator/*`、`/mpj/device/*`、`/probe/conn-cast`
- `/mpj/perf/slow`＝SLEEP(1.2) 慢查询素材；`/mpj/perf/n1`＝循环 5 次 selectById，**N+1 一次请求 5 条 SQL，是造日志风暴的现成素材**（PerfDemoController，注释明说是演示录制用）

压测脚本：`mybatis-plus-sql-visualizer-demo/stress-test/stress_capture.py`（Write 工具落盘，非 heredoc）。要点：urllib + `ProxyHandler({})` 本机直连不走代理；三阶段 warm(4×10s)→storm32(32×60s)→storm64(64×45s)；接口池 12 个只读 + 14% n1 + 低比例 `/mpj/user/updateJoin` 造写捕获路径；**排除 deleteJoin/updateAndNull（删数据）**；输出 RPS/avg/p50/p90/p99/max/状态码分布/错误 TOP。

压测前基线（PID 65608）：WorkingSet 202MB、Private 1189MB、线程 43、句柄 645、CPU 累计 16s——压测后同口径重采对比判泄漏。

**20261107 已跑完（用户发令后），结论＝增强捕获全绿**：
- 读数：warm(4×10s) 306 成功/56 失败 RPS 30.4；storm32(32×60s) 2747/542 RPS 45.5 p99 2324ms；storm64(64×45s) 2218/429 RPS 48.5 p99 3953ms max 5386ms
- **全部 HTTP 500 只来自两个 demo 固有坏接口**（单请求也 500，与并发/插件无关）：① `/mpj/user/page`——`UserServiceImpl.java:128` 的 `.setTableName(name -> name + "_2026")` 拼出 `"user"_2026` 非法 MySQL 语法，MP 分页 COUNT 必炸（jsqlparser 也解析不了，控制台大量 `optimize this sql to a count sql has exception`）；② `/mpj/locator/mapFamily`——`mapOne` 期望 1 行实得 3 行。重跑前应把这两个从接口池剔除（错误全是噪音）
- 插件侧证据：压测全程 `idea.log` 里 mpsv/sqlvis 相关 ERROR/SEVERE/Exception＝**0 条**（现存 15 条 SEVERE 全是 GenerateAllSetter/EasyCode 别家插件，压测前就有）；`updateJoin` 写捕获 58 次成功无死锁；压测后 `/mpj/perf/n1` 200
- 进程对比：WS 202→峰值 649→回落 327MB；Private 978MB **低于**基线 1189（GC 回收）；线程 43→113、句柄 645→1255，静置 8s 稳定不涨（Tomcat 并发工作线程，非泄漏）
- 待用户肉眼复核：面板双条目（跨通道去重顶没顶住）、⨉N 徽章、慢查询/N+1 徽章、UI 卡顿——助手看不到 IDE 面板

裁判位：①脚本读数；②进程前后对比；③grep `IntelliJIdea2026.3/log/idea.log` 里 mpsv 插件 SEVERE/异常（注意 [[pitfall-sandbox-log-accumulates-and-default-path]] 的累积问题，用户是真 IDE 非沙盒）；④用户 IDE 预览面板肉眼复核。相关背景：[[pitfall-agent-config-watchdog-15s-silence]]、[[pitfall-cross-channel-dedupe-key-divergence]]。

**第二轮（20261107 下午，新构建 runIde 沙盒）**：用户改走沙盒——从 `.worktrees/sqllist-catchup` 启 runIde 自动部署新构建（部署内容用类内字符串探针验，见 [[project-sqllist-catchup-20261107]]）；demo 重启后 agent 从沙盒 system 目录挂载（PID 84676，端口 8082）。~10 万请求全 200（RPS 876/988）、沙盒 idea.log 插件异常 0；**HTTP 侧与第一轮不可比**（第一轮 demo 多挂 cool-request SQL hook agent，RPS 45→876 是环境差异不是插件效果）；demo 全 GC 后存活集 ~30MB 无泄漏，2GB WS 是 15 倍负载下的堆扩张（commit 不还 OS，正常）。插件侧裁判改看沙盒 log（`<worktree>/.intellijPlatform/sandbox/<插件>/IU-2024.3.7.1/log/idea.log`）里 LogPerfProbe 行。用户肉眼复核仍报「面板落队 + 控制台停输出后列表长追」→ 转入券 E（refreshList 单拍 102~232ms 饿停控制台冲刷主嫌疑）。
