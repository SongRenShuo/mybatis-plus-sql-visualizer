$ErrorActionPreference = 'SilentlyContinue'
$targets = @(61380, 34332)
foreach ($p in $targets) {
  $proc = Get-Process -Id $p -ErrorAction SilentlyContinue
  if ($proc) { taskkill /T /F /PID $p | Out-Null }
}
Start-Sleep -Seconds 4
Get-Process | Where-Object { $_.ProcessName -match 'idea|java' } | Select-Object Id, ProcessName, StartTime | Format-Table -AutoSize | Out-String -Width 160
Write-Output "KILLDONE"
