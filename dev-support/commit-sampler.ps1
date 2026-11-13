# T-TestPerf7 S3 helper: sample committed memory (Commit charge) every N seconds.
# Usage: powershell -File commit-sampler.ps1 -IntervalSec 3 -OutFile commit-log.csv [-StopFile stop-sampler.flag]
# R6 formula: Commit = TotalVirtualMemorySize - FreeVirtualMemory (KB -> GB). Logs timestamp, commit GB.
# Clean exit: create the stop file (any content); loop exits within one interval.
param(
    [int]$IntervalSec = 3,
    [string]$OutFile = "commit-log.csv",
    [string]$StopFile = "stop-sampler.flag"
)
"timestamp,commitGB" | Out-File -FilePath $OutFile -Encoding ascii
$peak = 0.0
while ($true) {
    $os = Get-CimInstance Win32_OperatingSystem
    $commitKB = [double]$os.TotalVirtualMemorySize - [double]$os.FreeVirtualMemory
    $commitGB = [math]::Round($commitKB / 1MB, 2)
    $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    "$ts,$commitGB" | Out-File -FilePath $OutFile -Append -Encoding ascii
    if ($commitGB -gt $peak) { $peak = $commitGB }
    if (Test-Path $StopFile) { break }
    Start-Sleep -Seconds $IntervalSec
}
"peakGB,$peak" | Out-File -FilePath $OutFile -Append -Encoding ascii
Write-Output "sampler stopped, peak commit = $peak GB (log: $OutFile)"
