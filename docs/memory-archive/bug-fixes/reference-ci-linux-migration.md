---
name: reference-ci-linux-migration
description: CI 从 Windows 换到 Linux 的提速空间、七处硬阻塞与混合方案结论（2026-11-05 调研）
metadata:
  node_type: memory
  type: reference
  originSessionId: sess_1caa1cd6-ecff-4769-98f4-48bfeeca1eb0
---

门禁 CI 从 `windows-latest` 换 `ubuntu-latest` 的可行性调研，完整报告在仓 `docs/调研报告/20261105-1106-CI换Linux的提速空间与迁移成本-调研报告.md`。

结论：收益只有 2~4 分钟（19.4 → 约 16~18），不是数量级；首轮冷缓存 22~30 分钟。两平台 runner 规格相同（2 vCPU/8GB），且 GitHub Windows 镜像已关闭 Defender 实时监控并全盘排除，所以「Defender 税」不成立。

七处硬阻塞（均已实测复核）：
- `gradlew` 在 git index 是 `100644`、`.gitattributes` 给 `eol=crlf` → Linux 上不可执行且 shebang 带 CR
- `buildSrc/src/main/kotlin/VerifierHomeGuard.kt` 用 `ProcessBuilder("powershell", ...)` 且无 try/catch → Linux 只有 `pwsh`，verify/marketplace 四条腿全红
- `buildSrc/src/main/kotlin/mpv-verification.gradle.kts` 硬编码 `-win` 目录名（10 处），Linux transform 目录不带 `-win`
- `src/test/kotlin/.../log/O147HistoryUpgradeTest.kt` 硬检查 `bin/java.exe`
- `src/test/kotlin/.../config/UpdateSectionTest.kt` 用可见 `JFrame`，headless 下抛 HeadlessException
- workflow 里 `./gradlew.bat` ×4 与 `setup-test-env.ps1` ×2（`.sh` 版本已存在）

混合迁移（只迁部分腿）不划算：GitHub 缓存每仓库 10GB，现有 Windows 侧已约 9.6GB，两套并存会按 LRU 互踢，每轮都冷启动。

相关：[[project-overview]]
