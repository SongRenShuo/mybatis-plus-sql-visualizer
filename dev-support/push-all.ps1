# push-all.ps1 —— 双仓推送：Gitee（主仓）+ GitHub（镜像）
#
# 为什么需要它：本仓双平台同步，Gitee 是主仓、GitHub 是镜像。手工双推要记三件事——
#   ① GitHub 端必须挂本地代理（127.0.0.1:7890），Gitee 端不需要；
#   ② GitHub 端必须 `-c credential.helper=` 置空，否则 GCM 会弹 GUI 抢凭据并常驻不退；
#   ③ 令牌从 .local/github-token.json 读，不进命令行历史。
# 本脚本把这三点固定下来，并保证一端失败不阻断另一端（不用 && 串联，见 AGENTS.md 收口纪律）。
#
# 用法（在仓库根或任意子目录执行均可）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File dev-support/push-all.ps1
#   powershell ... -File dev-support/push-all.ps1 -All          # master + dev + feature 三支
#   powershell ... -File dev-support/push-all.ps1 -Tags         # 附带推送全部 tag
#   powershell ... -File dev-support/push-all.ps1 -DryRun       # 只打印将执行的命令
#   powershell ... -File dev-support/push-all.ps1 -GiteeOnly    # 只推主仓（网络受限时）
#
# 退出码：0 = 两端都成功（或 DryRun）；1 = 至少一端失败。失败端逐条列在末尾摘要里。

[CmdletBinding()]
param(
    [switch]$All,
    [switch]$Tags,
    [switch]$DryRun,
    [switch]$GiteeOnly,
    [string]$Branch,
    [int]$ProxyPort = 7890,
    [switch]$NoProxy
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Ok([string]$Message) { Write-Host "    OK   $Message" -ForegroundColor Green }
function Write-Fail([string]$Message) { Write-Host "    FAIL $Message" -ForegroundColor Red }
function Write-Note([string]$Message) { Write-Host "    ..   $Message" -ForegroundColor DarkGray }

# ---- 定位仓库根（兼容从任意子目录调用）----
$repoRoot = (git -C $PSScriptRoot rev-parse --show-toplevel 2>$null)
if (-not $repoRoot) {
    Write-Fail "无法定位 git 仓库根：$PSScriptRoot 不在任何 git 工作树内"
    exit 1
}
$repoRoot = $repoRoot.Trim()
Write-Note "仓库根：$repoRoot"

# ---- 待推分支 ----
$currentBranch = (git -C $repoRoot rev-parse --abbrev-ref HEAD).Trim()
if ($currentBranch -eq 'HEAD') {
    Write-Fail "当前处于 detached HEAD，请先切到分支再推"
    exit 1
}

$branches = @()
if ($Branch) {
    $branches = @($Branch)
} elseif ($All) {
    $branches = @('master', 'dev', 'feature/kotlin-to-java')
    # 只推实际存在的分支
    $existing = @(git -C $repoRoot for-each-ref --format='%(refname:short)' refs/heads)
    $branches = @($branches | Where-Object { $existing -contains $_ })
} else {
    $branches = @($currentBranch)
}
Write-Note "待推分支：$($branches -join ', ')"

# ---- GitHub 令牌 ----
$tokenFile = Join-Path $repoRoot '.local/github-token.json'
$ghToken = $null
if (Test-Path $tokenFile) {
    try {
        $cfg = Get-Content $tokenFile -Raw -Encoding UTF8 | ConvertFrom-Json
        $ghToken = $cfg.github_personal_access_token
        $ghOwner = if ($cfg.owner) { $cfg.owner } else { 'SongRenShuo' }
        $ghRepo = if ($cfg.repo) { $cfg.repo } else { 'mybatis-plus-sql-visualizer' }
    } catch {
        Write-Fail "读取 $tokenFile 失败：$($_.Exception.Message)"
    }
} else {
    Write-Note "未找到 $tokenFile（GitHub 端将跳过）"
}

# ---- 推送实现：一端失败不影响另一端 ----
$results = @()

function Invoke-Push {
    param(
        [string]$Name,
        [string[]]$GitArgs
    )
    # 令牌脱敏：DryRun 与日志都不得出现明文（URL 里的 x-access-token:<token>@ 段替换为 ***）
    $display = "git " + (($GitArgs -join ' ') -replace 'x-access-token:[^@]+@', 'x-access-token:***@')
    if ($DryRun) {
        Write-Note "[$Name] $display"
        return @{ Name = $Name; Ok = $true; Detail = 'dry-run' }
    }
    Write-Note "[$Name] $display"
    # git 把进度与 "Everything up-to-date" 都写到 stderr。在 $ErrorActionPreference='Stop' 下，
    # PowerShell 5.1 会把非空 stderr 当成 NativeCommandError 抛成终止错误，脚本会在第一端就中断、
    # 后面的 GitHub 段根本不执行（2026-10-22 实测：Gitee up-to-date → 整个脚本退出）。
    # 故此处局部放宽为 Continue，只用 $LASTEXITCODE 判成败。
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $output = & git -C $repoRoot @GitArgs 2>&1
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prevEap
    if ($code -eq 0) {
        Write-Ok "$Name 推送成功"
        return @{ Name = $Name; Ok = $true; Detail = 'ok' }
    }
    Write-Fail "$Name 推送失败（exit $code）"
    $output | Select-Object -Last 6 | ForEach-Object { Write-Host "         $_" -ForegroundColor DarkYellow }
    return @{ Name = $Name; Ok = $false; Detail = ($output | Select-Object -Last 3) -join ' / ' }
}

# GitHub 专用：代理优先（含重试）→ 代理不通时回落直连（含重试）
#
# 传输策略（2026-10-22 用户口径：网络问题用本地代理解决）：
#   先探测代理是否真的能出去（不是只看端口在听），探通就走代理；探不通再回落直连。
#   探测选 5 秒超时的一次 HEAD 请求——mihomo 节点失效时端口照常在听、但握手一律 000，
#   只看端口会把「代理挂了」误判成「能用」。
#
# 为什么还要重试：两个方向都会间歇性中断（实测同一分钟内直连前 4 次
# "Couldn't connect / Connection was reset / SSL_ERROR_SYSCALL"、第 5 次成功）。
# 不重试会把偶发抖动报成「推送失败」。
function Test-ProxyUsable {
    param([int]$TimeoutSec = 5)
    if ($NoProxy) { return $false }
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $code = & curl.exe -s -x "http://127.0.0.1:$ProxyPort" -m $TimeoutSec -o NUL -w "%{http_code}" "https://api.github.com/zen" 2>$null
    $ErrorActionPreference = $prevEap
    return ($code -eq '200')
}

function Invoke-PushWithFallback {
    param(
        [string]$Name,
        [string]$Refspec,
        [int]$MaxAttempts = 5,
        [int]$RetryDelaySec = 5
    )

    function Attempt([string]$Label, [string[]]$ExtraGitArgs) {
        # 注意：不能起名 $args（PowerShell 自动变量，赋值会污染并报错）
        $pushArgs = $ExtraGitArgs + @('push', $script:ghUrl, $Refspec)
        for ($i = 1; $i -le $MaxAttempts; $i++) {
            if ($i -gt 1) {
                Write-Note "$Label 第 $i/$MaxAttempts 次重试…"
                Start-Sleep -Seconds $RetryDelaySec
            }
            $r = Invoke-Push -Name $Label -GitArgs $pushArgs
            if ($r.Ok) { return $r }
        }
        return $r
    }

    $proxyArgs = @(
        '-c', "http.proxy=http://127.0.0.1:$ProxyPort",
        '-c', 'credential.helper='
    )
    $directArgs = @('-c', 'credential.helper=')

    if (Test-ProxyUsable) {
        Write-Note "$Name 走本地代理 127.0.0.1:$ProxyPort"
        $r = Attempt -Label $Name -ExtraGitArgs $proxyArgs
        if ($r.Ok) { return $r }
        Write-Note "$Name 代理 $MaxAttempts 次均失败，回落直连"
    } else {
        Write-Note "$Name 代理探测不通（或 -NoProxy），走直连"
    }
    return Attempt -Label "$Name(direct)" -ExtraGitArgs $directArgs
}

# ---- 1. Gitee（主仓）----
Write-Step "推送 Gitee（主仓）"
foreach ($b in $branches) {
    $results += Invoke-Push -Name "Gitee:$b" -GitArgs @('push', 'origin', "${b}:${b}")
}
if ($Tags) {
    $results += Invoke-Push -Name 'Gitee:tags' -GitArgs @('push', 'origin', '--tags')
}

# ---- 2. GitHub（镜像）----
if ($GiteeOnly) {
    Write-Step "跳过 GitHub（-GiteeOnly）"
} elseif (-not $ghToken) {
    Write-Step "跳过 GitHub（无令牌）"
    Write-Note "创建 $tokenFile，内容形如：{ ""github_personal_access_token"": ""gho_..."", ""owner"": ""SongRenShuo"", ""repo"": ""mybatis-plus-sql-visualizer"" }"
} else {
    Write-Step "推送 GitHub（镜像）"
    $script:ghUrl = "https://x-access-token:${ghToken}@github.com/${ghOwner}/${ghRepo}.git"
    # 传输策略：先直连，失败再挂本地代理重试一次。
    # 为什么不是「先代理」：本机代理对 github.com:443 会间歇性 SSL_ERROR_SYSCALL（2026-10-22 实测
    # 同一分钟内多次失败），而直连稳定可达；但部分网络环境只有代理能出去，故保留回退。
    # -NoProxy 强制只走直连，跳过回退。
    foreach ($b in $branches) {
        $results += Invoke-PushWithFallback -Name "GitHub:$b" -Refspec "${b}:${b}"
    }
    if ($Tags) {
        $results += Invoke-PushWithFallback -Name 'GitHub:tags' -Refspec '--tags'
    }
}

# ---- 摘要 ----
Write-Step "摘要"
$failed = @($results | Where-Object { -not $_.Ok })
foreach ($r in $results) {
    if ($r.Ok) { Write-Ok $r.Name } else { Write-Fail "$($r.Name) —— $($r.Detail)" }
}

if ($failed.Count -gt 0) {
    Write-Host ""
    Write-Host "有 $($failed.Count) 项失败。常见原因：" -ForegroundColor Yellow
    Write-Host "  - GitHub 端 000 / exit 35：本地代理未启动或抖动，确认 127.0.0.1:$ProxyPort 可用；直连通常也能通（去掉 -c http.proxy 试一次）" -ForegroundColor DarkGray
    Write-Host "  - GitHub 端认证失败：令牌过期或 scope 不含 repo，重新取令牌写入 .local/github-token.json" -ForegroundColor DarkGray
    Write-Host "  - Gitee 端失败：检查 origin 远端与网络（Gitee 不走代理）" -ForegroundColor DarkGray
    exit 1
}

Write-Host ""
Write-Ok "双仓推送完成"
exit 0
