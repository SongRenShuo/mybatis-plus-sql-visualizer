# 发布前全量验证（三版本 243+253+263，权威门禁 verifyGate，约 3.5min）
# T-RC-02：原跑 verifyPlugin（已降级为报告生成器，判定缺陷见 mpv-verification.gradle.kts failureLevel 处），
# 改挂 verifyFull（= verifyGate 三目标权威门禁）。
# 用法：powershell -ExecutionPolicy Bypass -File dev-support/verify-full.ps1
Write-Host "🔍 Running plugin verification (full mode - 3 versions, authoritative verifyGate, ~3.5min)..."
& ./gradlew verifyFull --no-daemon
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
