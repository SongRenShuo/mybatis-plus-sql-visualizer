param(
    [Parameter(Mandatory = $true)][string]$PluginJar,
    [Parameter(Mandatory = $true)][string]$IdeHome,
    [int]$Forks = 5,
    [string]$GradleHome = (Join-Path $env:USERPROFILE '.gradle')
)
$ErrorActionPreference = 'Stop'
if ($Forks -lt 1 -or $Forks -gt 30) { throw 'Forks must be between 1 and 30.' }
$stdlib = Get-ChildItem -LiteralPath (Join-Path $GradleHome 'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.3.20') -Filter 'kotlin-stdlib-2.3.20.jar' -Recurse | Select-Object -First 1
if (!$stdlib) { throw 'Required cached Kotlin stdlib was not found.' }
$plugin = (Resolve-Path -LiteralPath $PluginJar).Path
$java = Join-Path $IdeHome 'jbr/bin/java.exe'
if (!(Test-Path -LiteralPath $java)) { throw 'IdeHome must contain jbr/bin/java.exe.' }
Write-Output ('plugin_sha256=' + (Get-FileHash -LiteralPath $plugin -Algorithm SHA256).Hash.ToLowerInvariant())
$os = Get-CimInstance Win32_OperatingSystem
$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
Write-Output (ConvertTo-Json -Compress ([ordered]@{
    timestamp = (Get-Date).ToString('o'); os = $os.Caption; cpu = $cpu.Name
    freePhysicalKB = $os.FreePhysicalMemory; freeVirtualKB = $os.FreeVirtualMemory
    totalVisibleKB = $os.TotalVisibleMemorySize; forks = $Forks; heapLimit = '256m'
    coldDefinition = 'fresh JVM first read; OS cache is not flushed; source launch setup excluded from timed operations'
}))
$classPath = [string]::Join(';', @($plugin, $stdlib.FullName, (Join-Path $IdeHome 'lib/*')))
for ($i = 1; $i -le $Forks; $i++) {
    Write-Output ('fork=' + $i)
    & $java -Xmx256m -cp $classPath (Join-Path $PSScriptRoot 'M19HistoryBenchmark.java')
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
