$ErrorActionPreference = 'Stop'
Set-Location 'F:\code\Java\Gitee\SongRenShuo\mpsv-magpie-gems'
$env:JAVA_HOME = 'C:\Users\USER\.jdks\ms-21.0.11'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$unmerged = & git diff --name-only --diff-filter=U
if ($unmerged) { throw 'Merge conflicts must be resolved before launching the acceptance build' }
$check = (& python 'C:\Users\USER\.zcode\cli\parallel_tasks.py' check 2>&1) -join "`n"
Write-Output $check
$code = $LASTEXITCODE
if ($code -notin 0,1) { throw 'Parallel check error' }
if ($code -eq 1) {
    $names = @([regex]::Matches($check, 'name=([^\s]+)') | ForEach-Object { $_.Groups[1].Value })
    if (!$names -or $names.Count -ge 3 -or ($names | Where-Object { $_ -notin @('magpie-accept-ui243','k2j-master136-gate') })) { throw 'No reviewed concurrent slot' }
}
& python 'C:\Users\USER\.zcode\cli\parallel_tasks.py' register --name 'magpie-accept-ui243' --sandbox 'F:/code/Java/Gitee/SongRenShuo/mpsv-magpie-gems/build/MPSV-MagpieGems-UI243'
if ($LASTEXITCODE -ne 0) { throw 'Registration failed' }
& .\dev-support\gradle-gate.ps1 -MaxConcurrent 3 -WaitTimeoutSeconds 1 -Command '.\gradlew.bat -I .local/magpie-gems-ui243.init.gradle runIde --args="F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-demo" -Pkotlin.compiler.execution.strategy=in-process -PgifRobotServer=true --no-configuration-cache --console=plain'
$code = $LASTEXITCODE
& python 'C:\Users\USER\.zcode\cli\parallel_tasks.py' done --name 'magpie-accept-ui243'
exit $code
