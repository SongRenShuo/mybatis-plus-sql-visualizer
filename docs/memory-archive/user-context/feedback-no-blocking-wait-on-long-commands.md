---
name: no-blocking-wait-on-long-commands
description: 长命令一律后台启动等完成通知；sleep 轮询与 block=true 的阻塞等待都算阻塞，用户会当场提醒
metadata:
  node_type: memory
  type: feedback
  originSessionId: sess_f586a157-4652-4c9d-b73f-14df449f2952
---

用户在本轮里两次当场纠正（2026-11-12）：「少用sleep 应该用后台运行 等通知 提高效率不要阻塞主控」，随后又一次「等命令执行不要阻塞」。口径比 AGENTS 里那条「长命令用后台任务启、不许 sleep 轮询」更严：**任何让主控停在原地等结果的做法都算阻塞**——`sleep N`、`sleep` 之后回读日志、`TaskOutput(block=true)` 静等长任务，全在内。

**Why:** 用户看的是「主控还在不在干活」。阻塞等待期间既不产出也不汇报，体感跟卡住没区别；而后台任务跑完会自动唤醒，中间那几分钟本该拿去写文档、备探针、推下一步，白等就是净损失。

**How to apply:** 长命令（gradle/构建/runIde/推送/CI）一律 `run_in_background` + 日志重定向到文件（并紧跟 `echo "EXIT=$?"` 把真退出码写进日志，见 [[pitfall-pipe-tail-swallows-exit-code]]）；等结果期间去做**不依赖它**的下一件事，靠完成通知回来收口。要盯 CI 就挂一条后台 `gh run watch <id> --exit-status` 当通知源，别在主控里循环轮询。相关：[[project-release-20261112-beta3]]。
