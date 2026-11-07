# =============================================================================
# ci-verify.ps1 —— 每周自动 verifyFull（权威门禁 verifyGate 三目标）兼容性巡检包装脚本（T-CiVerifyPromise / O-5）
#
# 流程：拉 dev 最新（非 git 仓库 / git 不可用 / 无网络 / 非 dev 分支时容错跳过）
#       → 调 verify-full.ps1（verifyFull = verifyGate 三目标 243/253/263，约 3.5 分钟；T-RC-02 改挂权威门禁，
#         verifyPlugin 已降级为报告生成器不承担门禁）
#       → 时间戳日志落 dev-support/verify-logs/verify-<时间戳>.log
#       → 失败额外写 dev-support/verify-logs/LAST-FAILED.txt（成功自动清除）
#       → 日志轮转仅保留最近 8 份
#
# Windows 计划任务注册命令（唯一注册来源，在任意 PowerShell 窗口执行一次即可；
# 系统重装或仓库迁移后重跑此命令恢复。dev 检出工作树路径以实际为准）：
#
#   $action   = New-ScheduledTaskAction -Execute "powershell.exe" `
#               -Argument '-NoProfile -ExecutionPolicy Bypass -File "F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer-dev\dev-support\ci-verify.ps1"' `
#               -WorkingDirectory "F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer-dev"
#   $trigger  = New-ScheduledTaskTrigger -Weekly -WeeksInterval 1 -DaysOfWeek Wednesday -At 03:00
#   $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable
#   Register-ScheduledTask -TaskName "MPSqlVisualizer-WeeklyVerify" `
#       -Action $action -Trigger $trigger -Settings $settings `
#       -Description "每周三 03:00 自动 verifyFull 三目标（243/253/263，权威门禁 verifyGate）兼容性巡检；关机错过调度开机补跑；失败留痕 dev-support/verify-logs/LAST-FAILED.txt" -Force
#
# 注册后核对：
#   Get-ScheduledTask -TaskName "MPSqlVisualizer-WeeklyVerify" | Get-ScheduledTaskInfo
#
# 手动验证等价入口：直接调 verify-full.ps1，或本脚本（非 dev 分支时自动跳过拉取）。
# =============================================================================

try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$repoRoot     = Split-Path -Parent $PSScriptRoot
$logDir       = Join-Path $PSScriptRoot "verify-logs"
$stamp        = Get-Date -Format "yyyyMMdd-HHmmss"
$logFile      = Join-Path $logDir "verify-$stamp.log"
$failedMarker = Join-Path $logDir "LAST-FAILED.txt"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

# 统一日志出口：控制台显示一次 + 追加落文件（Tee-Object 单一写入者，避免编码混写）
function Write-Log([string]$Message) {
    $line = "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] $Message"
    Write-Host $line
    $line | Tee-Object -FilePath $logFile -Append | Out-Null
}

Write-Log "=== ci-verify 巡检开始（日志：$logFile）==="

# ---- 1. 拉 dev 最新（容错：非 git 仓库 / git 不可用 / 无网络 / 非 dev 分支均跳过） ----
$gitCmd = Get-Command git -ErrorAction SilentlyContinue
$branch = $null
if ($gitCmd -and (Test-Path (Join-Path $repoRoot ".git"))) {
    $branch = & git -C $repoRoot rev-parse --abbrev-ref HEAD 2>$null
}
if (-not $branch) {
    Write-Log "WARN 非 git 仓库或 git 不可用，跳过拉取，按当前工作树代码验证"
} elseif ($branch.Trim() -ne "dev") {
    Write-Log "WARN 当前分支为 '$($branch.Trim())' 非 dev（自动巡检以 dev 检出为准），跳过拉取，按当前工作树代码验证"
} else {
    Write-Log "拉取 dev 最新..."
    & git -C $repoRoot pull --ff-only origin dev 2>&1 | ForEach-Object { Write-Log "git: $_" }
    if ($LASTEXITCODE -eq 0) {
        Write-Log "dev 已更新到最新"
    } else {
        Write-Log "WARN 拉取 dev 失败（可能无网络或本地状态不允许快进），继续用本地代码验证"
    }
}

# ---- 2. 调 verify-full.ps1（子进程调用隔离其内部 exit；输出同步落控制台与日志） ----
# verify-full.ps1 内部以相对路径 ./gradlew 调构建，而计划任务默认工作目录是 System32，
# 必须先切到仓库根（Push-Location 兜底，不依赖注册命令的 WorkingDirectory）。
Write-Log "开始 verifyFull 全量三目标校验（IU-2024.3.7.1 / 2025.3.5 / 2026.2.1，权威门禁 verifyGate，约 3.5 分钟）..."
Push-Location $repoRoot
try {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "verify-full.ps1") 2>&1 |
        Tee-Object -FilePath $logFile -Append
    $exitCode = $LASTEXITCODE
} finally {
    Pop-Location
}

# ---- 3. 结果标记：失败写 LAST-FAILED.txt，成功清除旧标记 ----
if ($exitCode -ne 0) {
    Write-Log "FAIL verifyFull 校验失败（exit=$exitCode）"
    @(
        "verifyFull 巡检失败",
        "时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')",
        "退出码：$exitCode",
        "日志：$logFile"
    ) | Set-Content -Path $failedMarker -Encoding UTF8
    Write-Log "失败标记已写入：$failedMarker"
} else {
    Remove-Item -Path $failedMarker -Force -ErrorAction SilentlyContinue
    Write-Log "OK verifyFull 校验通过（三目标 Compatible，0 COMPATIBILITY_PROBLEMS），无失败标记"
}

# ---- 4. 日志轮转：仅保留最近 8 份 verify-*.log（不动 LAST-FAILED.txt） ----
$logs = @(Get-ChildItem -Path $logDir -Filter "verify-*.log" -File | Sort-Object LastWriteTime -Descending)
if ($logs.Count -gt 8) {
    $logs | Select-Object -Skip 8 | ForEach-Object {
        Remove-Item -Path $_.FullName -Force
        Write-Log "轮转删除旧日志：$($_.Name)"
    }
}

Write-Log "=== ci-verify 巡检结束 ==="
exit $exitCode
