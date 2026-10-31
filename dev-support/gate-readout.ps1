# gate-readout.ps1 —— 门禁读数时效性台账（T-TestGateGems W5，2026-10-24）
#
# 为什么需要它：magpie 的 code-standards 有一条机械化护栏（`gh pr merge --match-head-commit
# <reviewed sha>`）——合并点必须等于验证过的那个 commit，验证之后有人再 push 就合并失败。
# 本仓的对应纪律是「收口读数时效性：主控写文档期间不得碰 src/main，改了绿就过期」，
# 但此前**只有纪律、没有机制**：跑完门禁到 push 之间若 HEAD 变了，谁也不知道那道绿还作不作数。
#
# 本脚本把「验证时的 HEAD」钉成一个可核对的凭据：
#   - 跑门禁前：record —— 记下当前 HEAD sha + 分支 + 时间，写入 .local/gate-readout.json
#   - 推之前：  verify —— 比对当前 HEAD 与记录是否一致；不一致即失败（绿已过期，必须复跑）
#
# 用法（在仓库根或任意子目录执行均可）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File dev-support/gate-readout.ps1 record -Label testShards
#   powershell -NoProfile -ExecutionPolicy Bypass -File dev-support/gate-readout.ps1 verify
#   powershell -NoProfile -ExecutionPolicy Bypass -File dev-support/gate-readout.ps1 show
#
# 退出码：record/show = 0；verify = 0（一致）或 1（过期 / 无记录）。
# 与 push-all.ps1 串联建议：
#   gate-readout.ps1 verify; if ($LASTEXITCODE -eq 0) { push-all.ps1 }   # 用分号+显式判退出码，不用 &&

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('record', 'verify', 'show')]
    [string]$Command = 'show',

    # record 时的标签（哪道门禁），仅用于台账可读性
    [string]$Label = 'gate'
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

function Write-Ok([string]$Message) { Write-Host "    OK   $Message" -ForegroundColor Green }
function Write-Fail([string]$Message) { Write-Host "    FAIL $Message" -ForegroundColor Red }
function Write-Note([string]$Message) { Write-Host "    ..   $Message" -ForegroundColor DarkGray }

$repoRoot = (git -C $PSScriptRoot rev-parse --show-toplevel 2>$null)
if (-not $repoRoot) {
    Write-Fail "无法定位 git 仓库根：$PSScriptRoot 不在任何 git 工作树内"
    exit 1
}
$repoRoot = $repoRoot.Trim()

# 台账放 .local/（已被 .gitignore 忽略，不进版本库、不跨工作树污染）
$stateDir = Join-Path $repoRoot '.local'
$stateFile = Join-Path $stateDir 'gate-readout.json'

function Get-HeadSha {
    $sha = (git -C $repoRoot rev-parse HEAD 2>$null)
    if (-not $sha) { return $null }
    return $sha.Trim()
}

function Get-HeadBranch {
    $b = (git -C $repoRoot rev-parse --abbrev-ref HEAD 2>$null)
    if (-not $b) { return '(unknown)' }
    return $b.Trim()
}

switch ($Command) {
    'record' {
        $sha = Get-HeadSha
        if (-not $sha) { Write-Fail "取不到 HEAD sha"; exit 1 }
        $branch = Get-HeadBranch
        if (-not (Test-Path $stateDir)) { New-Item -ItemType Directory -Path $stateDir -Force | Out-Null }
        $payload = [ordered]@{
            sha       = $sha
            branch    = $branch
            label     = $Label
            recordedAt = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss zzz')
            worktree  = $repoRoot
        }
        ($payload | ConvertTo-Json) | Set-Content -Path $stateFile -Encoding UTF8
        Write-Ok "已记录门禁读数凭据"
        Write-Note "  label : $Label"
        Write-Note "  sha   : $sha"
        Write-Note "  branch: $branch"
        Write-Note "  文件  : $stateFile"
    }

    'verify' {
        $sha = Get-HeadSha
        if (-not $sha) { Write-Fail "取不到 HEAD sha"; exit 1 }
        if (-not (Test-Path $stateFile)) {
            Write-Fail "没有门禁读数记录——先跑 gate-readout.ps1 record（在跑门禁之前）"
            exit 1
        }
        $state = Get-Content -Path $stateFile -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($state.sha -ne $sha) {
            Write-Fail "门禁读数已过期：记录时 HEAD=$($state.sha)（$($state.label), $($state.recordedAt)），当前 HEAD=$sha"
            Write-Note "  验证之后 HEAD 变了 ⇒ 那道绿不再覆盖当前代码，必须复跑门禁再推。"
            Write-Note "  若只是文档/非 src/main 改动，可用 record 重新登记并在提交信息里说明。"
            exit 1
        }
        if ($state.branch -ne (Get-HeadBranch)) {
            Write-Fail "分支已变更：记录时 $($state.branch)，当前 $(Get-HeadBranch)"
            exit 1
        }
        Write-Ok "门禁读数有效：HEAD 与记录一致（$sha，$($state.label)）"
    }

    'show' {
        if (-not (Test-Path $stateFile)) {
            Write-Note "尚无门禁读数记录（$stateFile 不存在）"
            exit 0
        }
        $state = Get-Content -Path $stateFile -Raw -Encoding UTF8 | ConvertFrom-Json
        Write-Note "门禁读数台账："
        Write-Note "  label : $($state.label)"
        Write-Note "  sha   : $($state.sha)"
        Write-Note "  branch: $($state.branch)"
        Write-Note "  时间  : $($state.recordedAt)"
        Write-Note "  工作树: $($state.worktree)"
        $sha = Get-HeadSha
        if ($sha -and $sha -ne $state.sha) {
            Write-Note "  ⚠ 当前 HEAD=$sha 与记录不一致（读数已过期）"
        }
        exit 0
    }
}
