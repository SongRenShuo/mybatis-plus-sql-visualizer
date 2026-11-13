# T-TestPerf10 S1: refresh the committed shard-weight profile (dev-support/test-shard-weights.tsv)
# from a clean local round's JUnit XML. The profile backfills classes with no local weight data
# in mpv-testing.gradle.kts (fresh worktrees / newly landed test classes), so the duration
# balancer starts warm instead of degrading to count-based greedy.
#
# Usage (repo root):
#   powershell -ExecutionPolicy Bypass -File dev-support/weight-refresh.ps1
#   ... -ShardCount 6 -Samples 2 -PollutionThresholdSec 900 -Force
#
# Semantics mirror the balancer (mpv-testing.gradle.kts durationWeights):
#   - scan build/test-results/test + testShard1..N (bounded 1..ShardCount, never a wildcard --
#     lesson 7: stale shard dirs from old shard counts must not leak into the profile) + testFast
#   - per class keep the K most recent samples by mtime, value-median = sorted ascending [count/2]
#
# testFast in the scan (T-JUnit5Eval 方案 2, 2026-11-05): the pure-logic bucket left the shards and
# now runs as its own `testFast` leg, so its ~370 classes have NO samples in testShardN anymore.
# Without scanning testFast the refreshed profile would silently drop them, and the balancer's
# staleness gate (uncovered > 15%) would start warning every round. They still matter: a class that
# gains a `com.intellij` reference moves back into the shard bucket and then needs a real weight
# instead of median interpolation.
#
# The pollution gate refuses to write the profile when the latest round's execSum exceeds
# -PollutionThresholdSec unless -Force is given. Default raised 900 -> 1500 when testFast joined the
# scan: a clean round is now shards (~880s, platform only) + testFast (~276s) + tail (~31s) ≈ 1200s,
# so 900 would reject every clean round; 1500 keeps the same "≈1.25x clean = polluted" margin.

param(
    [string]$ResultsDir = "build/test-results",
    [int]$ShardCount = 5,
    [int]$Samples = 2,
    [double]$PollutionThresholdSec = 1500,
    [switch]$Force,
    [string]$Out = "dev-support/test-shard-weights.tsv"
)

$ErrorActionPreference = "Stop"
if ($ShardCount -lt 1 -or $ShardCount -gt 6) { throw "ShardCount must be 1..6" }
if ($Samples -lt 1 -or $Samples -gt 5) { throw "Samples must be 1..5" }

function Read-SuiteAttrs([string]$path) {
    $head = New-Object System.Collections.Generic.List[string]
    $reader = [System.IO.File]::OpenText($path)
    try {
        for ($i = 0; $i -lt 8 -and -not $reader.EndOfStream; $i++) { $head.Add($reader.ReadLine()) }
    } finally { $reader.Close() }
    $joined = $head -join "`n"
    $start = $joined.IndexOf("<testsuite")
    if ($start -lt 0) { return $null }
    $tagEnd = $joined.IndexOf(">", $start)
    if ($tagEnd -lt 0) { return $null }
    $tag = $joined.Substring($start + 10, $tagEnd - $start - 10)
    $attrs = @{}
    foreach ($m in [regex]::Matches($tag, '([\w.-]+)="([^"]*)"')) { $attrs[$m.Groups[1].Value] = $m.Groups[2].Value }
    return $attrs
}

# bounded dir set: default test/ + testShard1..N + testFast（快桶腿，见头注释）
$dirs = @((Join-Path $ResultsDir "test"))
for ($i = 1; $i -le $ShardCount; $i++) { $dirs += (Join-Path $ResultsDir ("testShard" + $i)) }
$dirs += (Join-Path $ResultsDir "testFast")

$byClass = @{}          # fqcn -> list of [mtime, seconds] (NOT named $samples: PS vars are case-insensitive)
$roundSum = 0.0         # latest-round execSum across dirs (30 min window per dir)
$roundFiles = 0
$roundStamp = $null

foreach ($d in $dirs) {
    if (-not (Test-Path $d -PathType Container)) { continue }
    $xmls = Get-ChildItem -LiteralPath $d -Filter "TEST-*.xml" -File
    if ($xmls.Count -eq 0) { continue }
    $newest = ($xmls | Measure-Object -Property LastWriteTimeUtc -Maximum).Maximum
    if ($null -eq $roundStamp -or $newest -gt $roundStamp) { $roundStamp = $newest }
    foreach ($f in $xmls) {
        $attrs = Read-SuiteAttrs $f.FullName
        if ($null -eq $attrs) { continue }
        $name = $attrs["name"]; $time = 0.0
        if (-not $name -or -not [double]::TryParse($attrs["time"], [ref]$time)) { continue }
        $mtime = $f.LastWriteTimeUtc.Ticks
        if (-not $byClass.ContainsKey($name)) { $byClass[$name] = New-Object System.Collections.Generic.List[object] }
        $byClass[$name].Add([pscustomobject]@{ M = $mtime; T = $time; D = $d })
        if (($newest - $f.LastWriteTimeUtc).TotalMinutes -lt 30) { $roundSum += $time; $roundFiles++ }
    }
}

if ($byClass.Count -eq 0) { throw "no TEST-*.xml found under $ResultsDir -- run a round first" }

if (-not $Force -and $roundSum -gt $PollutionThresholdSec) {
    throw ("pollution gate: latest-round execSum={0:N1}s > threshold={1:N1}s over {2} files. " +
           "Concurrent-build pollution likely (clean is ~600s scale); identify a clean round or pass -Force.") -f $roundSum, $PollutionThresholdSec, $roundFiles
}

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("# generator=weight-refresh.ps1 (T-TestPerf10 S1)")
$lines.Add(("# source=latest round execSum={0:N1}s files={1} samples={2} perClass={3} generated={4:yyyy-MM-dd HH:mm}" -f $roundSum, $roundFiles, $Samples, $byClass.Count, (Get-Date)))
foreach ($name in ($byClass.Keys | Sort-Object)) {
    # S3-split-aware class weight (matches the balancer's effectiveWeights semantics):
    # if the class's freshest samples come from 2+ shard dirs within a 10 min window
    # (one gate round), it is a method-level split class -- record the SUM (whole-class
    # weight, what a fresh tree without split data will actually run). Otherwise take
    # the K most recent samples by mtime, value-median = sorted ascending [count/2].
    $list = $byClass[$name]
    $dirLatest = $list | Group-Object -Property D | ForEach-Object {
        $_.Group | Sort-Object -Property M -Descending | Select-Object -First 1
    }
    $maxM = ($dirLatest | Measure-Object -Property M -Maximum).Maximum
    $recent = @($dirLatest | Where-Object { ($maxM - $_.M) -lt [long](10 * 60 * 10000000) })
    if ($recent.Count -ge 2) {
        $median = ($recent | Measure-Object -Property T -Sum).Sum
    } else {
        $vals = @($list | Sort-Object -Property M -Descending | Select-Object -First $Samples | ForEach-Object { $_.T } | Sort-Object)
        $median = $vals[[int]($vals.Count / 2)]  # same rule as the balancer: sorted ascending [size/2]
    }
    $lines.Add(("{0}`t{1:F3}" -f $name, $median))
}

$dir = Split-Path -Parent $Out
if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
[System.IO.File]::WriteAllLines((Join-Path (Get-Location) $Out), $lines)
Write-Output ("wrote {0}: {1} classes, round execSum={2:N1}s" -f $Out, $byClass.Count, $roundSum)
