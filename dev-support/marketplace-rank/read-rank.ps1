# read-rank.ps1 - Marketplace rank monitor for plugin 33633 "SQL Visualizer" (task D250).
#
# Read-only monitor: issues GET requests only, against the public JetBrains Marketplace API.
# For each keyword below it pages /api/searchPlugins until plugin id 33633 is found, an empty
# page is reached, or the 400-item scan cap is hit; then appends one CSV row per keyword to
# .local/marketplace-rank/rank-history.csv and prints a human-readable summary.
#
# Usage (from repo root):
#   pwsh -NoProfile -File dev-support/marketplace-rank/read-rank.ps1
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File dev-support/marketplace-rank/read-rank.ps1
#
# Notes:
# - ASCII-only on purpose: Windows PowerShell 5.1 misparses UTF-8 scripts containing non-ASCII
#   text (same convention as dev-support/test-demo/setup-test-env.ps1).
# - The server ignores the searchPlugins "limit" param; a page returns about 10-11 items, so the
#   rank is computed as the cumulative 1-based index across pages (offset incremented by the
#   number of items actually returned per page), independent of the page size.
# - All requests go through the local proxy (curl.exe -x). A request is retried up to
#   $MaxAttempts times with backoff; a keyword whose request still fails is recorded as ERR
#   in the CSV and the batch continues.
# - Idempotent/repeatable: the CSV is append-only (header written only on file creation).

$ErrorActionPreference = 'Stop'

$Base        = 'https://plugins.jetbrains.com'
$Proxy       = 'http://127.0.0.1:7890'
$PluginId    = 33633
$MaxItems    = 400   # per-keyword scan cap (cumulative items across pages)
$MaxAttempts = 4     # 1 initial try + 3 retries per request
$InterReqMs  = 250    # pause between successful requests (be polite to proxy/API)

$Keywords = @(
    'sql visualizer',
    'sql preview',
    'mybatis sql',
    'mybatis',
    'mybatis log',
    'mybatis plus',
    'sql log',
    'sql formatter',
    'slow query',
    'jpa sql',
    'hibernate sql',
    'database sql'
)

# --- resolve output paths from the script location (cwd-independent) ---
$scriptDir = $PSScriptRoot
if (-not $scriptDir) { $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path }
$repoRoot = (Resolve-Path (Join-Path $scriptDir '..\..\')).Path
$outDir   = Join-Path $repoRoot '.local\marketplace-rank'
$csvPath  = Join-Path $outDir 'rank-history.csv'
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }

$tmpBody = Join-Path $outDir ('tmp-response-' + [System.Guid]::NewGuid().ToString('N') + '.json')
$tmpErr  = Join-Path $outDir ('tmp-response-' + [System.Guid]::NewGuid().ToString('N') + '.err')

# Invoke-MpCurl: run curl.exe through the proxy with retries; return raw response body as
# UTF-8 string, or $null after $MaxAttempts failed attempts (never throws to caller).
function Invoke-MpCurl {
    param([string]$Url)
    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        if ($attempt -gt 1) { Start-Sleep -Seconds (2 * ($attempt - 1)) }
        & curl.exe -x $Proxy -sS --max-time 30 --stderr $tmpErr -o $tmpBody $Url
        $rc = $LASTEXITCODE
        $sizeOk = (Test-Path $tmpBody) -and ((Get-Item $tmpBody).Length -gt 0)
        if ($rc -eq 0 -and $sizeOk) {
            $text = [System.IO.File]::ReadAllText($tmpBody, [System.Text.Encoding]::UTF8)
            Clear-Content -Path $tmpBody -ErrorAction SilentlyContinue  # reset temp body for next call
            return $text
        }
        $errText = ''
        if (Test-Path $tmpErr) { $errText = ([System.IO.File]::ReadAllText($tmpErr)).Trim() }
        Write-Host ("  [net] attempt {0}/{1} failed (curl rc={2}): {3}" -f $attempt, $MaxAttempts, $rc, $errText)
    }
    return $null
}

# Parse-RankScan: page one keyword's search results and locate the plugin.
# Returns: Position (int 1-based | 'not-found' | 'ERR'), Pool (total from API | ''),
#          Pages (int), Status.
function Find-Rank {
    param([string]$Keyword)
    $enc     = [System.Uri]::EscapeDataString($Keyword)
    $offset  = 0
    $pages   = 0
    $scanned = 0
    $total   = $null
    while ($true) {
        $url  = "$Base/api/searchPlugins?search=$enc&offset=$offset"
        $resp = Invoke-MpCurl $url
        if ($null -eq $resp) {
            $pool = ''
            if ($null -ne $total) { $pool = $total }
            return [pscustomobject]@{ Position='ERR'; Pool=$pool; Pages=$pages; Status='request-failed' }
        }
        $obj = $null
        try { $obj = $resp | ConvertFrom-Json } catch { $obj = $null }
        if ($null -eq $obj -or $null -eq $obj.plugins) {
            return [pscustomobject]@{ Position='ERR'; Pool=''; Pages=$pages; Status='bad-json' }
        }
        $items = @($obj.plugins)
        if ($null -ne $obj.total) { $total = [int]$obj.total }
        if ($items.Count -eq 0) {
            $pool = ''
            if ($null -ne $total) { $pool = $total } else { $pool = $scanned }
            return [pscustomobject]@{ Position='not-found'; Pool=$pool; Pages=$pages; Status='empty-page' }
        }
        $pages++
        foreach ($it in $items) {
            $scanned++
            if ([int]$it.id -eq $PluginId) {
                $pool = ''
                if ($null -ne $total) { $pool = $total } else { $pool = $scanned }
                return [pscustomobject]@{ Position=$scanned; Pool=$pool; Pages=$pages; Status='found' }
            }
        }
        if ($scanned -ge $MaxItems) {
            $pool = ''
            if ($null -ne $total) { $pool = $total } else { $pool = $scanned }
            return [pscustomobject]@{ Position='not-found'; Pool=$pool; Pages=$pages; Status='cap-400' }
        }
        $offset += $items.Count
        if ($null -ne $total -and $total -gt 0 -and $offset -ge $total) {
            $pool = $total
            return [pscustomobject]@{ Position='not-found'; Pool=$pool; Pages=$pages; Status='exhausted' }
        }
        Start-Sleep -Milliseconds $InterReqMs
    }
}

# --- plugin detail readings (downloads / rating / last update), one fetch per run ---
$runTs = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
Write-Host ("Marketplace rank monitor run start: {0} (plugin {1}, cap {2} items, {3} keywords)" -f $runTs, $PluginId, $MaxItems, $Keywords.Count)

$dDownloads = 'ERR'
$dRating    = 'ERR'
$dUpdate    = 'ERR'
$detailResp = Invoke-MpCurl "$Base/api/plugins/$PluginId"
if ($null -ne $detailResp) {
    $dObj = $null
    try { $dObj = $detailResp | ConvertFrom-Json } catch { $dObj = $null }
    if ($null -ne $dObj) {
        if ($null -ne $dObj.downloads) { $dDownloads = [string]$dObj.downloads } else { $dDownloads = '' }
        if ($null -ne $dObj.rating)    { $dRating    = [string]$dObj.rating }    else { $dRating = '' }
        $tsField = $dObj.lastUpdateDate
        if (-not $tsField -and $dObj.cdate) { $tsField = $dObj.cdate }
        if ($tsField) {
            $dUpdate = [DateTimeOffset]::FromUnixTimeMilliseconds([int64]$tsField).UtcDateTime.ToString('yyyy-MM-dd')
        } else {
            $dUpdate = ''
        }
    }
}
Start-Sleep -Milliseconds $InterReqMs
Write-Host ("Detail readings: downloads={0} rating={1} lastUpdate={2}" -f $dDownloads, $dRating, $dUpdate)

# --- scan all keywords ---
$rows = @()
foreach ($kw in $Keywords) {
    Write-Host ("Scanning keyword '{0}' ..." -f $kw)
    $r = Find-Rank -Keyword $kw
    Write-Host ("  -> status={0} pool={1} position={2} pages={3}" -f $r.Status, $r.Pool, $r.Position, $r.Pages)
    $rows += [pscustomobject]@{
        Keyword = $kw
        Pool    = $r.Pool
        Position = $r.Position
        Pages   = $r.Pages
        Status  = $r.Status
    }
    Start-Sleep -Milliseconds $InterReqMs
}

# --- append CSV (header only when the file is created fresh) ---
$csvLines = @()
if (-not (Test-Path $csvPath)) {
    $csvLines += 'timestamp,keyword,pool,position,downloads,rating'
}
foreach ($row in $rows) {
    $csvLines += ('{0},{1},{2},{3},{4},{5}' -f $runTs, $row.Keyword, $row.Pool, $row.Position, $dDownloads, $dRating)
}
Add-Content -Path $csvPath -Value $csvLines -Encoding Ascii
Write-Host ("CSV appended: {0} (+{1} rows)" -f $csvPath, $rows.Count)

# --- human-readable summary table ---
Write-Host ''
Write-Host ('Summary for run {0} (downloads={1}, rating={2}, lastUpdate={3}):' -f $runTs, $dDownloads, $dRating, $dUpdate)
$rows | Format-Table -AutoSize Keyword, Pool, Position, Pages, Status | Out-String | ForEach-Object { Write-Host $_ }

# cleanup temp files (best effort)
foreach ($f in @($tmpBody, $tmpErr)) { if (Test-Path $f) { Remove-Item $f -Force -ErrorAction SilentlyContinue } }
Write-Host 'Done.'
