# gh.ps1 —— 用仓库 .local/github-token.json 的令牌调 GitHub CLI（看 CI / 发行 / PR）
#
# 背景：本仓的 PAT 只有 gist/repo/workflow，没有 `gh auth login --with-token` 强校验要求的
# read:org，直接登录会被拒。本脚本把令牌填进进程级 `GH_TOKEN`（不进命令行 argv、不落 keyring），
# 并**刻意不执行** `gh auth setup-git`——那会改 git 的 credential.helper，与仓内
# 「禁裸 push / 禁 GCM 弹窗」纪律冲突；推送一律仍走 dev-support/push-all.ps1。
#
# 用法（PowerShell 或 Git Bash 均可）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File dev-support/gh.ps1 run list --branch master
#   powershell -NoProfile -ExecutionPolicy Bypass -File dev-support/gh.ps1 run watch <run-id> --exit-status
#   powershell -NoProfile -ExecutionPolicy Bypass -File dev-support/gh.ps1 release view 2026.11.11
#   bash .local/gh.sh run list --branch master      # Git Bash 薄包装（本脚本的本地别名）

[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GhArgs
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

# ---- 定位仓库根（兼容从任意子目录调用）----
$repoRoot = (git -C $PSScriptRoot rev-parse --show-toplevel 2>$null)
if (-not $repoRoot) {
    Write-Error "无法定位 git 仓库根：$PSScriptRoot"
    exit 1
}
$repoRoot = $repoRoot.Trim()

# ---- 读令牌（只取 github_personal_access_token，不打印）----
$tokenFile = Join-Path $repoRoot '.local/github-token.json'
if (-not (Test-Path $tokenFile)) {
    Write-Error "未找到令牌文件：$tokenFile（应由用户本人在 .local/ 放置，勿入库）"
    exit 1
}
try {
    $cfg = Get-Content $tokenFile -Raw -Encoding UTF8 | ConvertFrom-Json
} catch {
    Write-Error "读取 $tokenFile 失败：$($_.Exception.Message)"
    exit 1
}
$token = $cfg.github_personal_access_token
if ([string]::IsNullOrWhiteSpace($token)) {
    Write-Error "令牌文件缺少 github_personal_access_token 字段：$tokenFile"
    exit 1
}

# ---- 定位 gh（PATH 优先；刚 winget 装完的会话 PATH 还是旧的，回落安装路径）----
$gh = $null
$cmd = Get-Command gh -ErrorAction SilentlyContinue
if ($cmd) { $gh = $cmd.Source }
if (-not $gh) {
    $candidates = @(
        (Join-Path $env:ProgramFiles 'GitHub CLI\gh.exe'),
        (Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Links\gh.exe')
    )
    $gh = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $gh) {
    Write-Error "找不到 gh 可执行文件；先装：winget install --id GitHub.cli --source winget"
    exit 1
}

$env:GH_TOKEN = $token
& $gh @GhArgs
exit $LASTEXITCODE