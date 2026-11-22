---
name: feedback-no-local-docker-start
description: 用户 2026-11-07 明令：测试服务器已有 SQL Server 等环境，助手永远不要自行启动本地 Docker
  Desktop；引擎不在跑就上报，不许擅自拉起
metadata:
  node_type: memory
  type: feedback
  originSessionId: sess_505ef444-5ce6-4a63-a7c9-e10d760149f4
---

# 禁止自行启动本地 Docker Desktop

2026-11-07 用户纠正：「我的测试服务器有 sqlserver 环境，**下次不要再启动本地的 Docker 了**」。

**Why：** 本地 Docker Desktop 吃本机资源（32GB 内存机器上与并行构建争 Commit 量），而 Docker 类需求的真环境（SQL Server、各国产库容器）已在测试服务器（[[project-server-backup]]，103.36.220.245）上；擅自拉起还改变了用户机器的运行状态。当日背景：全量门禁预检发现 Docker 引擎未启动，主控替用户启动了 Docker Desktop——被用户点名不要再做。

**How to apply：**
- **预检条款已改窄（20261107 用户授权，AGENTS.md 已改）**：Docker 测试腿默认关（`-PmpvDockerTests=true`）且在 testFast 桶不进分片，日常门禁与本地 Docker 解耦——`timeout 10 docker info` 预检仅在显式跑 Docker 测试腿时需要；跑普通全量/定向测试不需要 Docker 在场。
- Docker 引擎不在跑（无论普通门禁还是 Docker 腿）：**停下如实上报，等用户处置**；不许 `start Docker Desktop.exe`、不许重启引擎、不许 `wsl --shutdown` 之类环境动作。
- 需要 Docker 环境的验证（数据库容器、docker-in-test 类）优先考虑测试服务器已有实例（方案 T-DockerGateOffload：容器用例外指收编进矩阵通道）；确实必须本地 Docker 时，明确向用户提出并等授权。
- 若某轮经授权启动过本地 Docker，**用完必须关回去**（本次已按此执行：门禁跑完关闭 Docker Desktop 恢复原状）。
