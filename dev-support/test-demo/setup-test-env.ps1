#requires -Version 5.1
<#
.SYNOPSIS
  Prepare the SQL Visualizer test demo and local MySQL databases.

.DESCRIPTION
  The full test shards include Demo* tests that hard-assert files in the sibling
  demo repository. testDatabase/testDb* and e2eFakeIde additionally need a local
  MySQL with the mpj_demo database.

  This script:
    1. clones mybatis-plus-sql-visualizer-demo at the pinned revision if missing;
    2. verifies the files the tests actually read;
    3. installs minimal .idea/dataSources*.xml templates when absent;
    4. creates mpj_demo/mp_demo and runs dev-support/test-demo/db/*.sql when the
       mysql client is available;
    5. prints the next commands.

  Output messages are intentionally English-only: Windows PowerShell 5.1 reads
  UTF-8 scripts without BOM as ANSI, which would mangle Chinese output.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File dev-support/test-demo/setup-test-env.ps1

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File dev-support/test-demo/setup-test-env.ps1 -CheckOnly
#>
param(
    [string]$DemoDir = "",
    [string]$Revision = "",
    [switch]$CheckOnly,
    [switch]$SkipDb,
    [switch]$RequireDb,
    [string]$DbHost = "127.0.0.1",
    [int]$DbPort = 3306,
    [string]$DbUser = "root",
    [string]$DbPassword = $env:MYSQL_PWD,
    [string]$MysqlBin = $env:MYSQL_BIN
)

$ErrorActionPreference = "Stop"

function Write-Step([string]$Message) {
    Write-Host "[test-demo] $Message"
}

function Fail([string]$Message) {
    Write-Host "[test-demo] ERROR: $Message" -ForegroundColor Red
    exit 1
}

$scriptDir = $PSScriptRoot
$devSupportDir = Split-Path -Parent $scriptDir
$repoRoot = Split-Path -Parent $devSupportDir
$demoRepoUrl = "https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer-demo.git"

if ([string]::IsNullOrWhiteSpace($DemoDir)) {
    $DemoDir = Join-Path (Split-Path -Parent $repoRoot) "mybatis-plus-sql-visualizer-demo"
}
$DemoDir = [System.IO.Path]::GetFullPath($DemoDir)

if ([string]::IsNullOrWhiteSpace($Revision)) {
    $Revision = (Get-Content (Join-Path $scriptDir "demo-revision.txt") -Raw).Trim()
}

Write-Step "repository : $repoRoot"
Write-Step "demo dir   : $DemoDir"
Write-Step "revision   : $Revision"

# ---------- 1. demo checkout ----------
if (-not (Test-Path $DemoDir)) {
    if ($CheckOnly) {
        Fail "demo directory is missing. Run without -CheckOnly, or set -DemoDir/`$env:MPSV_DEMO_ROOT."
    }
    Write-Step "cloning demo repository..."
    & git clone $demoRepoUrl $DemoDir
    if ($LASTEXITCODE -ne 0) { Fail "git clone failed: $demoRepoUrl" }
    & git -C $DemoDir checkout $Revision
    if ($LASTEXITCODE -ne 0) { Fail "git checkout $Revision failed in $DemoDir" }
} else {
    if (Test-Path (Join-Path $DemoDir ".git")) {
        $head = (& git -C $DemoDir rev-parse --short HEAD 2>$null) -join ""
        if ($LASTEXITCODE -eq 0 -and $head -and -not $head.StartsWith($Revision)) {
            Write-Warning "demo HEAD ($head) differs from pinned revision ($Revision); tests may drift."
        } else {
            Write-Step "demo revision OK ($head)"
        }
    } else {
        Write-Warning "demo directory exists but is not a git checkout; skip revision check."
    }
}

# ---------- 2. required files ----------
$required = @(
    "mybatis-plus-demo/src/main/java/cn/song/mybatisplusdemo/service/impl/UserServiceImpl.java",
    "mybatis-plus-demo/src/main/resources/db/schema.sql",
    "mybatis-plus-join-demo/src/main/resources/db/schema.sql",
    "jpa-hibernate-demo/src/main/java/cn/song/jpademo",
    "jpa-embedded-prefix-demo/src/main/java/cn/song/embedprefixdemo/service",
    "jpa-jar-model-demo/src/main/java/cn/song/jarmodeldemo/service",
    "jpa-jar-model-lib/pom.xml"
)
$missing = @()
foreach ($rel in $required) {
    if (-not (Test-Path (Join-Path $DemoDir $rel))) { $missing += $rel }
}
if ($missing.Count -gt 0) {
    Fail ("demo checkout is incomplete; missing:`n  " + ($missing -join "`n  ") + "`nCheck the pinned revision or update demo-revision.txt.")
}
Write-Step "required demo files OK"

# ---------- 3. IDE data source templates ----------
if (-not $CheckOnly) {
    $ideaDir = Join-Path $DemoDir ".idea"
    New-Item -ItemType Directory -Force -Path $ideaDir | Out-Null
    foreach ($name in @("dataSources.xml", "dataSources.local.xml")) {
        $target = Join-Path $ideaDir $name
        if (Test-Path $target) {
            Write-Step "keep existing .idea/$name"
        } else {
            Copy-Item (Join-Path $scriptDir "templates/$name") $target
            Write-Step "installed .idea/$name (template)"
        }
    }
    $dataSources = Join-Path $ideaDir "dataSources.xml"
    if ((Test-Path $dataSources) -and -not (Select-String -Path $dataSources -Pattern "mpj_demo" -Quiet)) {
        Write-Warning "existing .idea/dataSources.xml has no data source containing 'mpj_demo'; testDatabase will skip."
    }
}

# ---------- 4. MySQL databases ----------
if ($SkipDb) {
    Write-Step "DB init skipped (-SkipDb)"
} elseif ($CheckOnly) {
    Write-Step "DB init skipped (-CheckOnly)"
} else {
    $mysqlExe = $null
    if (-not [string]::IsNullOrWhiteSpace($MysqlBin)) {
        if (-not (Test-Path $MysqlBin)) { Fail "mysql client not found: $MysqlBin" }
        $mysqlExe = (Resolve-Path $MysqlBin).Path
    } else {
        $mysqlCmd = Get-Command mysql -ErrorAction SilentlyContinue
        if ($null -ne $mysqlCmd) { $mysqlExe = $mysqlCmd.Source }
    }
    if ($null -eq $mysqlExe) {
        Write-Warning "mysql client not found; DB init skipped. Use -MysqlBin <path-to-mysql.exe> if it is installed outside PATH."
        Write-Host ""
        Write-Host "Run these commands manually (MySQL 5.7+/8.0):"
        Write-Host "  mysql -h$DbHost -P$DbPort -u$DbUser -p -e `"CREATE DATABASE IF NOT EXISTS mpj_demo DEFAULT CHARSET utf8mb4; CREATE DATABASE IF NOT EXISTS mp_demo DEFAULT CHARSET utf8mb4;`""
        Write-Host "  mysql -h$DbHost -P$DbPort -u$DbUser -p --default-character-set=utf8mb4 mpj_demo < dev-support/test-demo/db/mpj_demo.sql"
        Write-Host "  mysql -h$DbHost -P$DbPort -u$DbUser -p --default-character-set=utf8mb4 mp_demo < dev-support/test-demo/db/mp_demo.sql"
        if ($RequireDb) { Fail "MySQL is required (-RequireDb) but the mysql client is unavailable." }
    } else {
        if ($null -eq $DbPassword) { $DbPassword = "" }
        $env:MYSQL_PWD = $DbPassword

        & $mysqlExe --default-character-set=utf8mb4 -h $DbHost -P $DbPort -u $DbUser -e "SELECT 1" 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "cannot connect to MySQL at ${DbHost}:${DbPort} as $DbUser; DB init skipped."
            if ($RequireDb) { Fail "MySQL connection failed (-RequireDb)." }
        } else {
            & $mysqlExe --default-character-set=utf8mb4 -h $DbHost -P $DbPort -u $DbUser `
                -e "CREATE DATABASE IF NOT EXISTS mpj_demo DEFAULT CHARSET utf8mb4; CREATE DATABASE IF NOT EXISTS mp_demo DEFAULT CHARSET utf8mb4;"
            if ($LASTEXITCODE -ne 0) { Fail "failed to create mpj_demo/mp_demo." }

            $databases = @(
                @{ Name = "mpj_demo"; Script = "db/mpj_demo.sql" },
                @{ Name = "mp_demo"; Script = "db/mp_demo.sql" }
            )
            $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
            foreach ($db in $databases) {
                $sqlPath = Join-Path $scriptDir $db.Script
                Write-Step "loading $($db.Script) into $($db.Name)..."
                Get-Content -Raw -Encoding UTF8 $sqlPath | & $mysqlExe --default-character-set=utf8mb4 `
                    -h $DbHost -P $DbPort -u $DbUser $db.Name
                if ($LASTEXITCODE -ne 0) { Fail "failed to load $($db.Script) into $($db.Name)." }
            }

            $count = ((& $mysqlExe -N -B --default-character-set=utf8mb4 -h $DbHost -P $DbPort -u $DbUser `
                mpj_demo -e "SELECT COUNT(*) FROM user;") -join "").Trim()
            if ($LASTEXITCODE -eq 0 -and $count -match '^\d+$' -and [int]$count -gt 0) {
                Write-Step "mpj_demo.user rows = $count"
            } else {
                Write-Warning "mpj_demo.user is empty or unreadable; e2e/testDb data may be incomplete."
            }
        }
    }
}

Write-Host ""
Write-Step "done. next commands:"
Write-Host "  ./gradlew testFast        # no demo / DB required"
Write-Host "  ./gradlew testShards      # requires the demo checkout"
Write-Host "  ./gradlew testDatabase    # requires demo + MySQL mpj_demo"
Write-Host "  ./gradlew e2eFakeIde      # requires demo + MySQL mpj_demo + Maven cache"
Write-Host ""
Write-Host "If the demo lives elsewhere, set -PmpDemoProjectDir=<path> for testShards/testDatabase,"
Write-Host "or `$env:MPSV_DEMO_ROOT=<path> for e2eFakeIde."
