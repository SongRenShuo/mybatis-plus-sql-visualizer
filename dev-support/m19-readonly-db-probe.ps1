param(
    [Parameter(Mandatory = $true)][string]$PluginJar,
    [Parameter(Mandatory = $true)][string]$JdkHome,
    [string]$GradleHome = (Join-Path $env:USERPROFILE '.gradle')
)
$ErrorActionPreference = 'Stop'
$parser = Get-ChildItem -LiteralPath (Join-Path $GradleHome 'caches/modules-2/files-2.1/com.github.jsqlparser/jsqlparser/5.3') -Filter 'jsqlparser-5.3.jar' -Recurse | Select-Object -First 1
$stdlib = Get-ChildItem -LiteralPath (Join-Path $GradleHome 'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.3.20') -Filter 'kotlin-stdlib-2.3.20.jar' -Recurse | Select-Object -First 1
if (!$parser -or !$stdlib) { throw 'Required cached JSqlParser/Kotlin dependencies were not found.' }
$plugin = (Resolve-Path -LiteralPath $PluginJar).Path
$java = Join-Path $JdkHome 'bin/java.exe'
if (!(Test-Path -LiteralPath $java)) { throw 'JdkHome must contain bin/java.exe (JDK 17 or newer).' }
Write-Output ('plugin_sha256=' + (Get-FileHash -LiteralPath $plugin -Algorithm SHA256).Hash.ToLowerInvariant())
$classPath = [string]::Join(';', @($plugin, $parser.FullName, $stdlib.FullName))
& $java -cp $classPath (Join-Path $PSScriptRoot 'M19ReadOnlyDbProbe.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
