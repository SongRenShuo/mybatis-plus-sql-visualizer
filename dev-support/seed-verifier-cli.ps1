# Seed the verifier-cli fat jar into the Gradle cache, same as the "Seed verifier-cli" step
# in .github/workflows/build-plugin.yml. Version comes from dev-support/verifier-cli-version.txt
# (the pin that must stay in sync with the IPGP version in settings.gradle.kts).
#
# Why this matters: the verification tasks (verifyGate / verifyMarketplace*) resolve their
# classpath from the Gradle cache. When the jar is missing, those tasks fail at execution
# time with a clear message (since 2026-11-21; before that they broke IDE Gradle sync entirely).
#
# ASCII-only text by repo convention, so this runs under both Windows PowerShell 5.1 and pwsh 7.
# Usage:  powershell -NoProfile -ExecutionPolicy Bypass -File dev-support/seed-verifier-cli.ps1
#         (add -Proxy http://127.0.0.1:7890 when direct access to Maven Central is blocked)
param(
    [string]$Proxy = ''
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$versionFile = Join-Path $repoRoot 'dev-support/verifier-cli-version.txt'
$v = (Get-Content -LiteralPath $versionFile -Raw).Trim()
if (-not $v) { throw 'dev-support/verifier-cli-version.txt is empty' }

$guh = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $HOME '.gradle' }
$dst = Join-Path $guh 'caches/modules-2/files-2.1/org.jetbrains.intellij.plugins/verifier-cli/ci-seed'
New-Item -ItemType Directory -Force -Path $dst | Out-Null
$jar = Join-Path $dst "verifier-cli-$v-all.jar"

if (Test-Path -LiteralPath $jar) {
    $size = (Get-Item -LiteralPath $jar).Length
    Write-Host "Already seeded: $jar ($size bytes)"
    exit 0
}

$url = "https://repo1.maven.org/maven2/org/jetbrains/intellij/plugins/verifier-cli/$v/verifier-cli-$v-all.jar"
Write-Host "Downloading $url"
if ($Proxy) {
    Invoke-WebRequest -Uri $url -OutFile $jar -UseBasicParsing -Proxy $Proxy
} else {
    Invoke-WebRequest -Uri $url -OutFile $jar -UseBasicParsing
}
$size = (Get-Item -LiteralPath $jar).Length
Write-Host "Seeded verifier-cli $v : $jar ($size bytes)"
