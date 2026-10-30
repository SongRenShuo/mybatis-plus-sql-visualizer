---
name: pitfall-pause-resume-socket-channel-races
description: 暂停/恢复式 TCP 监听通道的三道竞态（accept-close 触发对端放弃计数、哑元滞留
  backlog、清尾与重启并发吞噬）与修法模式——agent premain 通道 D1/P4 实施实证
metadata:
  node_type: memory
  type: project
  originSessionId: sess_0dea6e50-c5f8-423c-8ac1-e579696d9eba
---

# 暂停/恢复 socket 通道的竞态三坑（T-PremainReattach D1/P4 实施实证，20261009）

把 TCP 监听 socket 当「可暂停的服务状态」管理（pause=accept 线程退出、监听保留；resume=重建
accept 循环）时，三道竞态单跑盖不住、全量门禁时序才抖出来。适用于本仓 agent premain 通道及
未来一切「通道暂停/恢复」类改动（详见仓 `docs/经验总结/20261009-1215-*` §四）：

1. **绝不能 accept-then-close 拒绝对端**：SenderLoop 有「连上即被关」计数（`MAX_FAST_DROPS=10`
   计满即 PORT_ABANDONED 永久放弃端口），1s→5s 退避下 accept-close 约 40s 就把对端逼疯。
   暂停期必须让对端连接停在内核 backlog（不被 accept 即不计数）。
2. **哑元自连唤醒会滞留 backlog**：自连唤醒阻塞的 accept 线程，若线程恰在循环条件处退出
   （未消费哑元），哑元占满槽位（backlog=1 时占满），此后一切回连被 Windows 拒绝
   （Connection refused）。修法=暂停退出由 accept 线程**清空 backlog**（时间有界 accept+close），
   保证 resume 后第一个 accept 必是真连接（顺带消灭 pid=0 闪断会话）。
3. **清尾与重启不能并发**：drain 循环与 resume 新 accept 循环共用 ServerSocket——并发互抢连接 +
   **共享 soTimeout 字段互踩**（新循环改写 30s 后旧 drain 超时条件失效，变 30s「连接吞噬僵尸」，
   把真回连 accept 后弃掉=恢复永久失效）。修法双保险：drain **时间有界 500ms**（绝不能在暂停期
   常驻服务，否则退回坑 1 灾难）+ 重启前对旧线程**无界 join**（其退出路径全部有界：清尾拍 100ms/
   读帧 5s/accept tick 30s，join 最长数秒换零并发窗口）。

**Why**：三道全是「多线程生命周期状态机」的时序缺陷——单跑 3 遍全绿、只在全量门禁负载下现形；
且失败签名会伪装（Connection refused 像端口没开、「连接已被中止」像对端主动断），必须读沙盒
日志逐帧推演时序才定位得了。

**How to apply**：改「accept/pause/resume/drain」链前先读 [[project-pending-plans]] 里
T-PremainReattach 条与仓内经验总结 §四；守卫 await 的前置要对齐判据确切前置（如「pid 已登记」
而非「会话已建立」）；这类改动必须过全量门禁，门禁红时先按 [[parallel-build-resource-rules]]
R16/R18 分流环境事故，签名不符再怀疑竞态。关联 [[pitfall-regression-false-failure-flakes]]。
