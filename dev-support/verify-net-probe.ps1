# T-ReleasePerf8 S1 helper: snapshot + diff for the verifier's loaded-plugins dir.
# Purpose (ASCII-only on purpose: PS 5.1 reads UTF-8-no-BOM as ANSI and Chinese strings
# break the parser, same class as the Git Bash CJK pitfall):
#   1) which files are new after a verification round (mtime/build-number advance);
#   2) reuse rate of build numbers between two rounds (added/removed/kept counts+bytes).
# Usage:
#   snapshot: powershell -File verify-net-probe.ps1 snapshot -Dir <loaded-plugins dir> -Out <manifest>
#   diff:     powershell -File verify-net-probe.ps1 diff -Before <old manifest> -After <new manifest>
# Manifest format: name<TAB>bytes<TAB>mtimeTicks, files only (loaded-plugins is flat).
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$Mode,
    [string]$Dir,
    [string]$Out,
    [string]$Before,
    [string]$After
)
if ($Mode -eq "snapshot") {
    if (-not $Dir -or -not $Out) { throw "snapshot needs -Dir and -Out" }
    $dirInfo = Get-Item -LiteralPath $Dir -ErrorAction SilentlyContinue
    if ($null -eq $dirInfo) {
        "EMPTY" | Out-File -FilePath $Out -Encoding ascii
        exit 0
    }
    Get-ChildItem -LiteralPath $Dir -File | ForEach-Object {
        "$($_.Name)`t$($_.Length)`t$($_.LastWriteTimeUtc.Ticks)"
    } | Out-File -FilePath $Out -Encoding ascii
    exit 0
}
if ($Mode -eq "diff") {
    if (-not $Before -or -not $After) { throw "diff needs -Before and -After" }
    function Read-Manifest($path) {
        $m = @{}
        if (Test-Path -LiteralPath $path) {
            Get-Content -LiteralPath $path | ForEach-Object {
                $p = $_ -split "`t"
                if ($p.Count -ge 3) { $m[$p[0]] = [long]$p[1] }
            }
        }
        return $m
    }
    $b = Read-Manifest $Before
    $a = Read-Manifest $After
    $added = $a.Keys | Where-Object { -not $b.ContainsKey($_) }
    $removed = $b.Keys | Where-Object { -not $a.ContainsKey($_) }
    $kept = $a.Keys | Where-Object { $b.ContainsKey($_) }
    $addedBytes = ($added | ForEach-Object { $a[$_] } | Measure-Object -Sum).Sum
    $removedBytes = ($removed | ForEach-Object { $b[$_] } | Measure-Object -Sum).Sum
    $keptBytes = ($kept | ForEach-Object { $a[$_] } | Measure-Object -Sum).Sum
    "added=$($added.Count)/$([math]::Round($addedBytes/1MB,1))MB removed=$($removed.Count)/$([math]::Round($removedBytes/1MB,1))MB kept=$($kept.Count)/$([math]::Round($keptBytes/1MB,1))MB total=$($a.Count)/$([math]::Round(($a.Values | Measure-Object -Sum).Sum/1MB,1))MB"
    $added | Sort-Object | ForEach-Object { "ADD  $_" }
    $removed | Sort-Object | ForEach-Object { "DEL  $_" }
    exit 0
}
throw "unknown mode $Mode (snapshot | diff)"
