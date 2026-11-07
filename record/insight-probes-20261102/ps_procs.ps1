$ErrorActionPreference = 'SilentlyContinue'
Get-CimInstance Win32_Process | Where-Object { $_.Name -match 'idea|java' } | ForEach-Object {
  $cmd = $_.CommandLine
  if ($null -eq $cmd) { $cmd = '' }
  if ($cmd.Length -gt 260) { $cmd = $cmd.Substring(0,260) }
  "{0}`t{1}`t{2}`t{3}" -f $_.ProcessId, $_.Name, $_.CreationDate, $cmd
}
