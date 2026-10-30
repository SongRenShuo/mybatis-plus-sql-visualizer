# =============================================================================
# memory-hygiene.ps1 -- memory watermark report & orphan cleanup (T-ReleasePerf10, 20261030)
#
# Context: build/test-chain native OOM (hs_err Chunk::new / errno 1455) is caused by the
# machine Commit ceiling being exhausted by co-residents: parallel-session test chains,
# user runIde sandbox IDE, ORPHANED test executors, headless CDP browser orphans
# (20261029: 64 orphans = 20.5GB), stale Kotlin daemons. Commit, not CPU, is the wall
# (see project memory parallel-build-resource-rules R1/R21~R24).
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File dev-support\memory-hygiene.ps1           # report
#   powershell -NoProfile -ExecutionPolicy Bypass -File dev-support\memory-hygiene.ps1 -Clean    # cleanup
#
# Report mode (default): classify java/chrome/node processes by command line, print
#   per-category commit totals and suspects (orphan executors, CDP headless, zombies).
#
# -Clean mode kills ONLY these three categories (everything else is never touched):
#   1. test executors (build/test-sandbox JVMs) whose parent gradlew shell is DEAD
#   2. headless CDP browsers (chrome/node with --headless or --remote-debugging-port)
#   3. gradle daemons only when ZERO gradlew client shells are alive (no mapping exists
#      between daemon and client, so any live client means daemons stay untouched)
#   Plus zombie Kotlin daemons (workingset < 50MB, the R23 4-day-leftover signature).
# NEVER killed: user IDE, sandbox IDE, verifier processes, other java processes.
#
# ASCII-only output on purpose: PS 5.1 reads BOM-less UTF-8 scripts as ANSI and Chinese
# comments/strings have silently broken conditions before (T-ReleasePerf9 lesson).
# =============================================================================

param([switch]$Clean)

$ErrorActionPreference = 'Continue'

function Get-Watermark {
    $os = Get-CimInstance Win32_OperatingSystem
    [pscustomobject]@{
        TotalGb = [math]::Round($os.TotalVirtualMemorySize / 1MB, 1)
        FreeGb  = [math]::Round($os.FreeVirtualMemory / 1MB, 1)
    }
}

$watermark = Get-Watermark
Write-Output ("memory-hygiene report @ {0}  commit total {1} GB / free {2} GB" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $watermark.TotalGb, $watermark.FreeGb)

# --- collect: processes with command line (Win32_Process) + commit bytes (Get-Process) ---
$names = @('java.exe', 'javaw.exe', 'chrome.exe', 'node.exe')
$procs = @(Get-CimInstance Win32_Process | Where-Object { $names -contains $_.Name })
$commitBytes = @{}
Get-Process | ForEach-Object {
    try { $commitBytes[[uint32]$_.Id] = [uint64]$_.PagedMemorySize64 } catch { }
}
$allPids = @{}
Get-CimInstance Win32_Process | ForEach-Object { $allPids[[uint32]$_.ProcessId] = $true }

function Get-CommitGb([uint32]$ProcId) {
    $b = $commitBytes[$ProcId]
    if ($null -eq $b) { return 0.0 }
    return [math]::Round($b / 1GB, 2)
}

function Classify($p) {
    $cl = [string]$p.CommandLine
    if ($cl -match 'KotlinCompileDaemon') { return 'kotlin-daemon' }
    if ($cl -match 'GradleDaemon') { return 'gradle-daemon' }
    if ($cl -match 'test-sandbox') { return 'test-executor' }
    if ($cl -match 'gradle\.appname=gradlew|gradle-wrapper') { return 'gradlew-shell' }
    if ($cl -match 'PluginVerifierMain') { return 'verifier' }
    if ($cl -match '--headless|remote-debugging-port') { return 'cdp-headless' }
    if ($cl -match 'idea-sandbox|idea\.config\.path') { return 'sandbox-ide' }
    if ($cl -match 'idea64|IntelliJIdea|JetBrains') { return 'user-ide' }
    if ($p.Name -like 'chrome*' -or $p.Name -like 'node*') { return 'cdp-other' }
    return 'java-other'
}

# --- classify & aggregate ---
$classified = foreach ($p in $procs) {
    [pscustomobject]@{
        Pid      = [uint32]$p.ProcessId
        Ppid     = [uint32]$p.ParentProcessId
        Name     = $p.Name
        Category = Classify $p
        CommitGb = Get-CommitGb ([uint32]$p.ProcessId)
        Cl       = ([string]$p.CommandLine)
        # zombie signature uses WORKING SET (R23: 4-day-old kotlin daemon squeezed to 2.3MB WS);
        # commit stays high for such a process, so commit alone cannot detect it
        WsMb     = [math]::Round([double]$p.WorkingSetSize / 1MB)
    }
}

$classified | Group-Object Category | Sort-Object { ($_.Group | Measure-Object CommitGb -Sum).Sum } -Descending |
    ForEach-Object {
        $gb = [math]::Round(($_.Group | Measure-Object CommitGb -Sum).Sum, 2)
        Write-Output ("{0,-14} x{1,-3} commit {2,7} GB" -f $_.Name, $_.Count, $gb)
    }

# --- suspects ---
$suspects = @()
foreach ($p in $classified) {
    if ($p.Category -eq 'test-executor' -and -not $allPids.ContainsKey($p.Ppid)) {
        $suspects += [pscustomobject]@{ Kind = 'ORPHAN-EXECUTOR'; Item = $p }
    }
    if ($p.Category -eq 'cdp-headless') {
        $suspects += [pscustomobject]@{ Kind = 'CDP-HEADLESS'; Item = $p }
    }
    if ($p.Category -eq 'kotlin-daemon' -and $null -ne $p.WsMb -and $p.WsMb -lt 50) {
        $suspects += [pscustomobject]@{ Kind = 'ZOMBIE-KOTLIN-DAEMON'; Item = $p }
    }
}
$kotlinCount = @($classified | Where-Object Category -eq 'kotlin-daemon').Count
if ($kotlinCount -gt 1) {
    Write-Output ("NOTE: {0} kotlin daemons in service (R23: stale ones waste commit; kill manually if zombie-free)" -f $kotlinCount)
}

Write-Output ("suspects: {0}" -f $suspects.Count)
foreach ($s in $suspects) {
    Write-Output ("  [{0}] pid={1} ppid={2} commit={3}GB  {4}" -f $s.Kind, $s.Item.Pid, $s.Item.Ppid, $s.Item.CommitGb, $s.Item.Cl.Substring(0, [math]::Min(140, $s.Item.Cl.Length)))
}

if (-not $Clean) {
    Write-Output 'hint: -Clean kills ONLY ORPHAN-EXECUTOR / CDP-HEADLESS / clientless gradle-daemon / ZOMBIE-KOTLIN-DAEMON. Never user IDE or live-session processes.'
    exit 0
}

# --- clean mode ---
$killed = 0
foreach ($s in $suspects) {
    $pid2 = $s.Item.Pid
    try {
        Stop-Process -Id $pid2 -Force -ErrorAction Stop
        Write-Output ("KILLED [{0}] pid={1} commit={2}GB" -f $s.Kind, $pid2, $s.Item.CommitGb)
        $killed++
    } catch {
        Write-Output ("SKIP  [{0}] pid={1} ({2})" -f $s.Kind, $pid2, $_.Exception.Message)
    }
}

# gradle daemons: kill only when zero gradlew shells alive (no daemon<->client mapping exists)
$shells = @($classified | Where-Object Category -eq 'gradlew-shell')
$daemons = @($classified | Where-Object Category -eq 'gradle-daemon')
if ($daemons.Count -gt 0 -and $shells.Count -eq 0) {
    foreach ($d in $daemons) {
        try {
            Stop-Process -Id $d.Pid -Force -ErrorAction Stop
            Write-Output ("KILLED [CLIENTLESS-DAEMON] pid={0} commit={1}GB" -f $d.Pid, $d.CommitGb)
            $killed++
        } catch {
            Write-Output ("SKIP  [CLIENTLESS-DAEMON] pid={0} ({1})" -f $d.Pid, $_.Exception.Message)
        }
    }
} elseif ($daemons.Count -gt 0) {
    Write-Output ("KEEP  gradle daemons: {0} gradlew shell(s) alive, daemon<->client mapping unknown" -f $shells.Count)
}

$after = Get-Watermark
Write-Output ("cleaned: {0} process(es); commit now free {1} GB (was {2} GB)" -f $killed, $after.FreeGb, $watermark.FreeGb)
