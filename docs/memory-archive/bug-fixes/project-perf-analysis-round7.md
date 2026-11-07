---
name: project-perf-analysis-round7
description: 第七期 IDEA 性能影响深查（20261104，基线首次移 dev 1bf55efa）与 T-PerfSweep30 已实施并合 dev 25cb1eec——券结果、三处真陷阱与门禁读数
metadata:
  node_type: memory
  type: project
  originSessionId: sess_52a699bb-570e-47c1-95f6-b547e186a309
---

# 第七期 IDEA 性能影响深查与 T-PerfSweep30（2026-11-04，已实施）

报告 `docs/性能分析/20261104-0401-IDEA性能影响-性能分析.md`，计划 `docs/开发计划/20261104-0401-T-PerfSweep30-预览首开与DumbMode防护面收口-开发计划.md`。**已实施并合入 dev `25cb1eec`（双仓已推），全量门禁 864 套件/9015 用例/0 失败/skipped 17。**

- **基线首次从 master 移到 dev `1bf55efa`**（日更 jar 在役形态；428 笔、+15544/−3477，系列最大增量）。此后对账一律 dev 口径。
- 「新增常驻 0」不变式**破例**：S2 状态栏 widget 默认启用、每项目 2s 池化轮询。券03 先测后判：**tick 实测 4.61µs**（占 CPU ≈0.0002%）→ 判「事件化收益不成立、登记不动」，用例钉住「快照不得创建 SqlLogManager」。
- [[pitfall-preview-open-lock-inversion]] 五项账本：四项坐实（重复写 exec／DumbMode 吞 IndexNotReady／预算 500ms vs 3016ms／装配两次），「指标重复计数」改判为打点口径。**01d 预算调参未动**（等 T0 重采样，禁盲调）。

## 实施中挖出的三个真陷阱（接手车道 A 时逐一撞上）

1. **RED-PROBE 撤闸残留会让功能形同虚设**：`if (existing != null && false && execFileContentMatches(...))` 探针未清，cache-only 恒不生效，而「写次数」测试仍绿（被 P1 差量快路径兜住）——**判据选错会掩盖探针残留**。
2. **01b 判据恒 false**：`lastConsoleEmbeddedId == null` 判在 `embedConsole()` 之后，而后者内部就赋值 → 打点永不输出。判据必须在嵌入**前**捕获。
3. **判据口径错**：cache-only 的真实收益不是「省写盘」（P1 已兜住），而是省掉其后两段 `syncExecVfContentIfNeeded`（各付 invokeLater＋2s 有界等待）→ 改用「是否进入 projectExecFile」计数才测得出来。

## 前提核销（七条，两条推翻推断）

- 前提 1 **部分成立**：IndexNotReady 确实从 `findClass`/`PsiShortNamesCache` 抛；但 **FileTypeIndex 在 dumb 态不抛、返回真清单**——「直抛」推断证伪，MapperXmlLocator 空表缓存主害是 `runCatching` 吞成空表 + 逐 vf `findFile` 走索引。
- 前提 3 **证伪**：showSql 早已内容判等（`SqlPreviewPanelSqlView.kt:678/:733`），计划原文「setText 无条件」不成立。

## 收口纪律沉淀

- 全量首轮 9015 用例 3 失败，全是「新增能力面连坐既有守卫」+「断言前提在分片同 JVM 不成立」：新守卫类必须登记 `GUARD_CLASSES`（含长期欠登的 `ScanBannedApiEdtRefreshGuardTest`），且自证用例名须含「自证/自检/selftest」。
- 计数缝从成员函数改扩展函数后，测试 class 增量编译不重编 → `NoSuchMethodError`；`touch` 无效，删 `build/classes/kotlin/test` 才恢复。
- `./gradlew ... | tail -40` 在后台跑会吞掉全部进度且退出码取管道末命令（首轮全量「exit 0」实为 BUILD FAILED）——**重定向到日志文件再看 EXIT**。

真机欠账四项归 T0：大项目 daemon 墙钟／Prewarm R1/R2 Slow operations／头部行缓存 JFR／**预热预算重采样**。
