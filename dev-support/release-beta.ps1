# release-beta.ps1 - one command for an internal beta release (gates -> tag -> push -> CI release -> GitHub/Gitee readings)
#
# Usage (run from the repo root or any sub directory):
#   pwsh -File dev-support/release-beta.ps1 -Version 2026.11.12-beta.5 -Notes "docs/发行说明/xxx-内测说明.md"
#   pwsh -File dev-support/release-beta.ps1 -Version ... -Notes ... -DryRun      # print only, run nothing
#   pwsh -File dev-support/release-beta.ps1 -Version ... -Notes ... -SkipGates    # rerun from tag step
#   pwsh -File dev-support/release-beta.ps1 -Version ... -Notes ... -SkipCi -SkipGitee   # tag+push only
#
# Rules baked in here (each one is a paid-for lesson, do not "simplify" them away):
#   - GitHub push always goes through dev-support/push-all.ps1. A bare `git push github` pops the
#     Git Credential Manager window and steals focus (AGENTS.md rule 10).
#   - The verdict of the release run is `gh run view --json conclusion`. `gh run watch` exits early
#     when the proxy drops the connection, so its exit code is NOT a result (LESSONS L51).
#   - An existing tag pointing at another commit fails loudly. Never move a tag silently.
#   - update/update.json belongs to the repo owner. This script never touches it.
#   - The GitHub asset download inside gitee-release.py is resumable now; if it still cannot finish,
#     the script falls back to the same bytes from the release run artifact (also L51).
#
# Exit code: 0 = every step passed, 1 = at least one step failed (summary at the end).

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$Notes,
    [switch]$DryRun,
    [switch]$SkipGates,
    [switch]$SkipCi,
    [switch]$SkipGitee,
    [string]$Branch = "master",
    [int]$CiTimeoutMin = 45,
    [int]$ProxyPort = 7890
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$repoRoot = (git -C $PSScriptRoot rev-parse --show-toplevel 2>$null)
if (-not $repoRoot) { Write-Host "cannot locate git repo root" -ForegroundColor Red; exit 1 }
$repoRoot = $repoRoot.Trim()
Set-Location $repoRoot

$script:done = @()
$script:failed = @()

function Write-Step([string]$m) { Write-Host ""; Write-Host "==> $m" -ForegroundColor Cyan }
function Write-Ok([string]$m) { Write-Host "    OK   $m" -ForegroundColor Green }
function Write-Fail([string]$m) { Write-Host "    FAIL $m" -ForegroundColor Red }
function Write-Note([string]$m) { Write-Host "    ..   $m" -ForegroundColor DarkGray }

# Run an external command; print it, honor -DryRun, and fail loudly on a non-zero exit code.
function Step-Run([string]$description, [string]$file, [string[]]$arguments) {
    Write-Note ("{0}" -f $description)
    Write-Note ("   " + $file + " " + ($arguments -join ' '))
    if ($DryRun) { return }
    & $file @arguments
    if ($LASTEXITCODE -ne 0) { throw ("{0} failed (exit {1})" -f $description, $LASTEXITCODE) }
}

function Fail([string]$step, [string]$message) {
    Write-Fail ("{0}: {1}" -f $step, $message)
    $script:failed += $step
}

# GitHub API helper: direct first (today's proxy drops long responses), proxy as fallback.
function Invoke-Gh([string[]]$arguments) {
    $saved = @{ HTTPS_PROXY = $env:HTTPS_PROXY; HTTP_PROXY = $env:HTTP_PROXY }
    try {
        $env:HTTPS_PROXY = $null; $env:HTTP_PROXY = $null
        $out = & gh @arguments 2>&1
        if ($LASTEXITCODE -eq 0) { return $out }
        $env:HTTPS_PROXY = ("http://127.0.0.1:{0}" -f $ProxyPort)
        $env:HTTP_PROXY = $env:HTTPS_PROXY
        $out = & gh @arguments 2>&1
        if ($LASTEXITCODE -eq 0) { return $out }
        throw ("gh {0} failed: {1}" -f ($arguments -join ' '), ($out -join "`n"))
    } finally {
        $env:HTTPS_PROXY = $saved.HTTPS_PROXY
        $env:HTTP_PROXY = $saved.HTTP_PROXY
    }
}

Write-Host "release-beta: version=$Version branch=$Branch dryRun=$($DryRun.IsPresent)" -ForegroundColor Yellow

# ---------------------------------------------------------------- 0. preconditions
Write-Step "preconditions"
$dirty = (git status --porcelain) -join "`n"
if ($dirty.Trim().Length -gt 0) {
    Fail "preconditions" "working tree is not clean - commit or stash first (a tainted build must never ship):`n$dirty"
}
$headBranch = (git rev-parse --abbrev-ref HEAD).Trim()
if ($headBranch -ne $Branch) {
    Fail "preconditions" ("on branch {0}, expected {1} (use -Branch to override)" -f $headBranch, $Branch)
}
$gradleVersion = ((Select-String -Path (Join-Path $repoRoot "gradle.properties") -Pattern '^version=(.+)$').Matches[0].Groups[1].Value).Trim()
if ($gradleVersion -ne $Version) {
    Fail "preconditions" ("gradle.properties version is {0}, expected {1} - bump it first" -f $gradleVersion, $Version)
}
if ($Version -notmatch '-') {
    Fail "preconditions" ("{0} has no -beta.N suffix; this script is for internal betas only" -f $Version)
}
if (-not (Test-Path -LiteralPath $Notes)) {
    Fail "preconditions" ("release notes not found: {0}" -f $Notes)
}
$headSha = (git rev-parse HEAD).Trim()
Write-Note ("HEAD = {0}" -f $headSha)
if ($script:failed.Count -gt 0) { Write-Fail "aborting before any outward action"; exit 1 }

# ---------------------------------------------------------------- 1. gates (change notes guard + package + inspector)
if (-not $SkipGates) {
    Write-Step "gates: change notes guard + buildPlugin + inspect-plugin-zip"
    try {
        Step-Run "change notes rating guard" ".\gradlew.bat" @("test", "--tests", "cn.song.mybatisplus.visualizer.convention.ChangeNotesRatingLineGuardTest")
        Step-Run "buildPlugin" ".\gradlew.bat" @("buildPlugin")
        Step-Run "inspect-plugin-zip" "python" @("dev-support/inspect-plugin-zip.py", "--version", $Version)
        Write-Ok "gates passed"
        $script:done += "gates"
    } catch { Fail "gates" $_.Exception.Message }
    if ($script:failed.Count -gt 0) { Write-Fail "aborting: gates failed, nothing was tagged or published"; exit 1 }
}

# ---------------------------------------------------------------- 2. tag (never move an existing one)
Write-Step "tag $Version"
$existing = (git tag --list $Version) -join ""
if ($existing.Trim().Length -gt 0) {
    $tagSha = (git rev-list -n 1 $Version).Trim()
    if ($tagSha -ne $headSha) {
        Fail "tag" ("tag {0} already exists at {1}, HEAD is {2} - refusing to move it silently" -f $Version, $tagSha, $headSha)
    } else {
        Write-Note ("tag already at HEAD ({0}), reusing" -f $headSha)
        $script:done += "tag"
    }
}
if ($script:failed.Count -eq 0 -and $existing.Trim().Length -eq 0) {
    try {
        Step-Run "create tag" "git" @("tag", $Version, $headSha)
        Write-Ok ("tag {0} -> {1}" -f $Version, $headSha)
        $script:done += "tag"
    } catch { Fail "tag" $_.Exception.Message }
}
if ($script:failed.Count -gt 0) { Write-Fail "aborting: tag step failed"; exit 1 }

# ---------------------------------------------------------------- 3. push branch + tags to both remotes
Write-Step "push $Branch + tags (Gitee + GitHub, token path)"
try {
    Step-Run "push-all.ps1" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "dev-support/push-all.ps1", "-Branch", $Branch, "-Tags")
    Write-Ok "pushed"
    $script:done += "push"
} catch { Fail "push" $_.Exception.Message }
if ($script:failed.Count -gt 0) { Write-Fail "aborting: push failed"; exit 1 }

# ---------------------------------------------------------------- 4. CI release run
if (-not $SkipCi) {
    Write-Step "CI release run (build-plugin.yml mode=release)"
    $tokenFile = Join-Path $repoRoot ".local/github-token.json"
    if (-not (Test-Path -LiteralPath $tokenFile)) {
        Fail "ci" ("token file missing: {0}" -f $tokenFile)
    } else {
        $env:GH_TOKEN = (Get-Content -LiteralPath $tokenFile -Raw | ConvertFrom-Json).github_personal_access_token
    }
    if ($script:failed.Count -eq 0) {
        try {
            if (-not $DryRun) {
                Invoke-Gh @("workflow", "run", "build-plugin.yml", "--ref", $Branch, "-f", "mode=release") | Out-Null
                Start-Sleep -Seconds 15
                $runId = $null
                for ($i = 0; $i -lt 10 -and -not $runId; $i++) {
                    $list = (Invoke-Gh @("run", "list", "--limit", "3", "--json", "databaseId,headSha,name")) | Out-String
                    $runs = $list | ConvertFrom-Json
                    $hit = $runs | Where-Object { $_.headSha -eq $headSha -and $_.name -like "*release*" } | Select-Object -First 1
                    if ($hit) { $runId = $hit.databaseId } else { Start-Sleep -Seconds 10 }
                }
                if (-not $runId) { throw "could not find the dispatched release run" }
                Write-Ok ("run {0} dispatched" -f $runId)

                $deadline = (Get-Date).AddMinutes($CiTimeoutMin)
                $conclusion = $null
                while ((Get-Date) -lt $deadline) {
                    try {
                        $conclusion = ((Invoke-Gh @("run", "view", "$runId", "--json", "conclusion", "--jq", ".conclusion")) | Out-String).Trim()
                    } catch { Write-Note ("poll failed (network): {0}" -f $_.Exception.Message); $conclusion = "" }
                    if ($conclusion -and $conclusion -ne "null") { break }
                    Start-Sleep -Seconds 30
                }
                if (-not $conclusion -or $conclusion -eq "null") { throw ("release run did not finish within {0} min" -f $CiTimeoutMin) }
                if ($conclusion -ne "success") { throw ("release run {0} concluded {1}" -f $runId, $conclusion) }
                Write-Ok ("run {0} success" -f $runId)
                $script:runId = $runId
                $script:done += "ci"
            } else {
                Write-Note ("DRY  gh workflow run build-plugin.yml --ref {0} -f mode=release" -f $Branch)
            }
        } catch { Fail "ci" $_.Exception.Message }
    }
    if ($script:failed.Count -gt 0) { Write-Fail "aborting: CI release failed"; exit 1 }
}

# ---------------------------------------------------------------- 5. GitHub readings
Write-Step "GitHub release readings"
$ghRelease = $null
try {
    if (-not $DryRun) {
        $raw = (Invoke-Gh @("release", "view", $Version, "--json", "databaseId,isPrerelease,publishedAt,assets,url")) | Out-String
        $ghRelease = $raw | ConvertFrom-Json
        $asset = @($ghRelease.assets)[0]
        Write-Ok ("id={0} prerelease={1} asset={2} bytes" -f $ghRelease.databaseId, $ghRelease.isPrerelease, $(if ($asset) { $asset.size } else { "?" }))
        $script:done += "github"
    }
} catch { Fail "github" $_.Exception.Message }

# ---------------------------------------------------------------- 6. Gitee sync + verify
if (-not $SkipGitee) {
    Write-Step "Gitee sync (--from-github --prerelease) + verify"
    try {
        Step-Run "gitee-release.py create" "python" @("dev-support/gitee-release.py", "--version", $Version, "--notes", $Notes, "--prerelease", "--from-github", "--yes")
        Step-Run "gitee-release.py verify-only" "python" @("dev-support/gitee-release.py", "--version", $Version, "--prerelease", "--verify-only", "--yes")
        Write-Ok "gitee synced (same bytes as GitHub)"
        $script:done += "gitee"
    } catch { Fail "gitee" $_.Exception.Message }
}

# ---------------------------------------------------------------- 7. summary
Write-Step "summary"
Write-Host ("    done    : {0}" -f ($(if ($script:done.Count) { $script:done -join ', ' } else { "-" })))
if ($script:runId) { Write-Host ("    ci run  : https://github.com/SongRenShuo/mybatis-plus-sql-visualizer/actions/runs/{0}" -f $script:runId) }
Write-Host ("    tag     : {0} @ {1}" -f $Version, $headSha)
if ($ghRelease) { Write-Host ("    github  : id={0} url={1}" -f $ghRelease.databaseId, $ghRelease.url) }
Write-Host ("    gitee   : https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/releases/tag/{0}" -f $Version)
Write-Host "    reminder: update/update.json is owner-only - this script never touches it" -ForegroundColor DarkGray

if ($script:failed.Count -gt 0) {
    Write-Fail ("failed steps: {0}" -f ($script:failed -join ', '))
    exit 1
}
Write-Ok "release finished"
exit 0
