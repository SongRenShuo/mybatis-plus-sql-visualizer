# T-ReleasePerf7 S1 helper: per-process CPU% + Commit sampler for java processes.
# Increment over commit-sampler.ps1 (Commit only): classifies JVM roles by command line so the
# verifier CPU curve (N1/N2 probe) and the shard-JVM curve can be told apart in one log.
#   kind=gate    -> PluginVerifierMain with -offline (verifyGate)
#   kind=market  -> PluginVerifierMain without -offline (verifyMarketplace)
#   kind=test    -> other java (test shard JVMs, agent JVM, gradle daemon)
#   kind=system  -> whole-machine commit total
# Usage: powershell -ExecutionPolicy Bypass -File proc-sampler.ps1 -IntervalSec 5 -OutFile proc-log.csv [-StopFile stop-sampler.flag]
# Clean exit: create the stop file (any content); loop exits within one interval.
param(
    [int]$IntervalSec = 5,
    [string]$OutFile = "proc-log.csv",
    [string]$StopFile = "stop-sampler.flag"
)
"timestamp,pid,kind,cpuPct,commitGB,commitTotalGB" | Out-File -FilePath $OutFile -Encoding ascii
$peakTotal = 0.0
$prev = @{}   # pid -> total processor seconds from previous tick
function Get-Kind([string]$cmd) {
    if ($cmd -match "PluginVerifierMain") {
        if ($cmd -match "\-offline") { return "gate" } else { return "market" }
    }
    return "test"
}
while ($true) {
    $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $os = Get-CimInstance Win32_OperatingSystem
    $totalGB = [math]::Round(([double]$os.TotalVirtualMemorySize - [double]$os.FreeVirtualMemory) / 1MB, 2)
    if ($totalGB -gt $peakTotal) { $peakTotal = $totalGB }
    $procs = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'"
    $seen = @{}
    foreach ($p in $procs) {
        $seen[[string]$p.ProcessId] = $true
        $kind = Get-Kind ([string]$p.CommandLine)
        $commitKB = 0.0
        try {
            $ws = Get-Process -Id $p.ProcessId -ErrorAction Stop
            # Commit approximation: PagedMemorySize64 (pageable commit charge of the process)
            $commitKB = [double]$ws.PagedMemorySize64 / 1KB
        } catch { continue }
        $cpuSec = [double]$p.UserModeTime + [double]$p.KernelModeTime
        $cpuSec = $cpuSec / 10000000.0   # 100ns ticks -> seconds
        $key = [string]$p.ProcessId
        $pct = 0.0
        if ($prev.ContainsKey($key)) {
            $delta = $cpuSec - [double]$prev[$key]
            if ($delta -ge 0) { $pct = [math]::Round($delta / $IntervalSec * 100.0, 1) }
        }
        $prev[$key] = $cpuSec
        $commitGB = [math]::Round($commitKB / 1MB, 2)
        "$ts,$($p.ProcessId),$kind,$pct,$commitGB,$totalGB" | Out-File -FilePath $OutFile -Append -Encoding ascii
    }
    foreach ($k in @($prev.Keys)) { if (-not $seen.ContainsKey($k)) { $prev.Remove($k) } }
    if (Test-Path $StopFile) { break }
    Start-Sleep -Seconds $IntervalSec
}
"peakGB,$peakTotal" | Out-File -FilePath $OutFile -Append -Encoding ascii
Write-Output "sampler stopped, peak commit(total) = $peakTotal GB (log: $OutFile)"
