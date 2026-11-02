$ErrorActionPreference = 'SilentlyContinue'
$targets = @(82676)
foreach ($p in $targets) {
  $proc = Get-Process -Id $p -ErrorAction SilentlyContinue
  if ($proc) {
    Write-Output ("killing " + $p + " " + $proc.ProcessName)
    taskkill /T /F /PID $p | Out-Null
  } else {
    Write-Output ("absent " + $p)
  }
}
Start-Sleep -Seconds 4
Get-Process | Where-Object { $_.ProcessName -match 'idea|java' } | Select-Object Id, ProcessName, StartTime | Format-Table -AutoSize | Out-String -Width 160
Write-Output "KILLDONE"
