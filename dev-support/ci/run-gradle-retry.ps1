# CI 用的 Gradle 调用包装：失败按「竞态 / 测试失败 / 其它」三类分流处置（2026-11-25 重写）。
#
# 背景一（T-CiZipFsFlake，2026-11-05 实证）：Gradle 配置期解析 IDE 自带插件
# （com.intellij.database 来自 243 发行版的 DatabaseTools/lib/database-plugin.jar）时会偶发
# java.nio.file.ClosedFileSystemException（栈顶 plugin-structure 的
# FsHandlerFileSystemProvider.checkAccess → Files.exists），随后被报成
# "Could not find bundled plugin with ID: 'com.intellij.database'"。实证约 4/25 片命中。
# 原地重跑没用（daemon 复用，已关闭的 zip FS 状态留在 daemon 里）⇒ 必须先 --stop；
# 更有效的重置是清**项目侧** IPGP 缓存（.intellijPlatform/layoutIndex 与 extracted-plugins，
# 2026-11-05 深夜 run#23/#24/#25 三连实证），清 GRADLE_USER_HOME 的 transforms 只是辅助。
#
# 背景二（2026-11-25 修，本轮根因）：旧版把每次尝试的输出 append 进同一个日志文件，再用
# `Select-String` 对**整个文件**匹配 ClosedFileSystemException。于是第一次命中竞态之后，任何
# 后续失败（含真实测试红）都会命中上一次留下的签名，被误判成「竞态未恢复」继续升级，最后用
# 干净工作树全量重跑：run 45562438538 的 test(4) 因此多烧 5m44（16m21s vs 正常 ~9m30s），
# 并把一次已知 flaky 用例的红**掩盖成绿**。现在每次尝试写独立日志，只判本次正文。
#
# 现在的分流：
#   ① 竞态（本次日志里有 ClosedFileSystemException）→ 停 daemon + 清项目侧 IPGP 缓存与 IDE 解包
#      + --max-workers=1 重跑；仍命中 → CI 上退到干净工作树（git clean -xdf，保留 .local）再跑一次。
#   ② 测试失败（本次日志里有 "tests completed, N failed" / "There were failing tests"）→ 只重跑
#      **失败类**一次（同一 job，编译已 UP-TO-DATE，约 1 分钟）；重跑通过则记 warning 放行，
#      仍红则照常失败——失败类名进注解与日志，不静默。
#   ③ 其它失败（编译错误、依赖解析失败等）→ 立即失败，不重试、不清盘。
#
# 用法：pwsh -NoProfile -File dev-support/ci/run-gradle-retry.ps1 -GradleArgs ':testShard1 -PgifRecord=false'
# 自检：pwsh -NoProfile -File dev-support/ci/run-gradle-retry.ps1 -SelfTest
#       （不跑 gradle，只验分类函数与失败类提取——旧版「陈旧竞态签名误判」形态就在自检里钉住）

param(
    [string]$GradleArgs,
    [switch]$SelfTest
)

$ErrorActionPreference = 'Continue'

function Get-LogDir {
    # RUNNER_TEMP 只在 GitHub runner 上有；本机试跑时为空会让 Join-Path 产出 null。
    if ($env:RUNNER_TEMP) { return $env:RUNNER_TEMP }
    return [System.IO.Path]::GetTempPath()
}

# ---- 分类判定（纯函数式，-SelfTest 覆盖）----

function Test-RaceInLog {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $false }
    return [bool](Select-String -LiteralPath $Path -Pattern 'ClosedFileSystemException' -SimpleMatch -Quiet)
}

function Test-TestFailureInLog {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $false }
    if (Select-String -LiteralPath $Path -Pattern 'There were failing tests' -SimpleMatch -Quiet) { return $true }
    return [bool](Select-String -LiteralPath $Path -Pattern 'tests completed, \d+ failed' -Quiet)
}

function Get-FailedTestClasses {
    param([string]$ResultsRoot)
    if (-not (Test-Path -LiteralPath $ResultsRoot)) { return @() }
    $classes = New-Object System.Collections.Generic.List[string]
    foreach ($dir in Get-ChildItem -Path $ResultsRoot -Directory -ErrorAction SilentlyContinue) {
        foreach ($xml in Get-ChildItem -Path $dir.FullName -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue) {
            $raw = Get-Content -LiteralPath $xml.FullName -Raw -Encoding UTF8
            if ($raw -match '<(failure|error)[\s/>]') {
                if ($xml.BaseName -match '^TEST-(.+)$') { $classes.Add($Matches[1]) }
            }
        }
    }
    return @($classes | Sort-Object -Unique)
}

# ---- 自检：不跑 gradle，只验判定逻辑 ----

function Invoke-SelfTest {
    $script:selfTestFailed = 0
    function Assert-True {
        param([bool]$Condition, [string]$Message)
        if ($Condition) { Write-Host "SELFTEST OK   $Message" }
        else { Write-Host "SELFTEST FAIL $Message"; $script:selfTestFailed++ }
    }
    $tmp = Join-Path (Get-LogDir) ('retry-selftest-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null

    $raceLog = Join-Path $tmp 'race.log'
    Set-Content -LiteralPath $raceLog -Value 'java.nio.file.ClosedFileSystemException' -Encoding UTF8
    $testFailLog = Join-Path $tmp 'testfail.log'
    Set-Content -LiteralPath $testFailLog -Value "1117 tests completed, 1 failed`n> There were failing tests." -Encoding UTF8
    $compileFailLog = Join-Path $tmp 'compilefail.log'
    Set-Content -LiteralPath $compileFailLog -Value 'e: file:///x.kt:1:1 Unresolved reference' -Encoding UTF8

    Assert-True (Test-RaceInLog $raceLog) '竞态日志判为竞态'
    Assert-True (-not (Test-RaceInLog $testFailLog)) '测试失败日志**不得**判为竞态（旧版误判根因）'
    Assert-True (Test-TestFailureInLog $testFailLog) '测试失败日志判为测试失败'
    Assert-True (-not (Test-TestFailureInLog $compileFailLog)) '编译失败不得判为测试失败'
    Assert-True (-not (Test-TestFailureInLog $raceLog)) '竞态不得判为测试失败'

    $resultsRoot = Join-Path $tmp 'build/test-results'
    $shardDir = Join-Path $resultsRoot 'testShard4'
    New-Item -ItemType Directory -Force -Path $shardDir | Out-Null
    Set-Content -LiteralPath (Join-Path $shardDir 'TEST-a.BFlaky.xml') -Encoding UTF8 `
        -Value '<testsuite><testcase name="x"><failure message="boom"/></testcase></testsuite>'
    Set-Content -LiteralPath (Join-Path $shardDir 'TEST-a.CGreen.xml') -Encoding UTF8 `
        -Value '<testsuite><testcase name="y"/></testsuite>'
    $classes = @(Get-FailedTestClasses $resultsRoot)
    Assert-True ($classes.Count -eq 1) "失败类只挑出 1 个（实得 $($classes.Count)）"
    Assert-True ($classes -contains 'a.BFlaky') '失败类名取到 a.BFlaky'

    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    if ($script:selfTestFailed -gt 0) {
        Write-Host "SELFTEST FAILED：$($script:selfTestFailed) 项不通过"
        exit 1
    }
    Write-Host 'SELFTEST PASSED'
    exit 0
}

if ($SelfTest) { Invoke-SelfTest }

if (-not $GradleArgs) {
    Write-Host '缺少 -GradleArgs（例：-GradleArgs ":testShard1 -PgifRecord=false"）'
    exit 2
}

$argList = @($GradleArgs -split '\s+' | Where-Object { $_ -ne '' })
$logDir = Get-LogDir
$repoRoot = (Get-Location).Path
$script:attemptNo = 0
$script:removedCount = 0

function Invoke-Attempt {
    param([string[]]$ExtraArgs = @(), [string]$Label)
    $script:attemptNo++
    $log = Join-Path $logDir ("gradle-run-{0}.log" -f $script:attemptNo)
    if (Test-Path -LiteralPath $log) { Remove-Item -LiteralPath $log -Force }
    $display = (($argList + $ExtraArgs) -join ' ')
    Write-Host ("==> 第 {0} 次尝试（{1}）：./gradlew.bat {2}" -f $script:attemptNo, $Label, $display)
    # 必须收尾 Out-Host：Tee-Object 的输出否则会进入函数输出流，把返回对象变成「字符串数组 + 对象」，
    # 调用方读 $r.Code 就是 null，分流全部走错。
    & ./gradlew.bat @argList @ExtraArgs 2>&1 | Tee-Object -FilePath $log | Out-Host
    return [pscustomobject]@{ Code = $LASTEXITCODE; Log = $log; Label = $Label }
}

function Clear-IdeCaches {
    foreach ($rel in @('.intellijPlatform\layoutIndex', '.intellijPlatform\extracted-plugins')) {
        $p = Join-Path $repoRoot $rel
        if (Test-Path -LiteralPath $p) {
            Remove-Item -LiteralPath $p -Recurse -Force -ErrorAction SilentlyContinue
            Write-Host "已清理项目侧缓存：$p"
        }
    }
    # GRADLE_USER_HOME 在 runner 上有；本机 harness/试跑可能没有——回落 ~/.gradle，免得 Join-Path 抛错
    # 让「清缓存」这一步整段失效（错误只报不中断，重试于是带着坏状态白跑）。
    $gradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $HOME '.gradle' }
    $caches = Join-Path $gradleHome 'caches'
    Get-ChildItem -Path $caches -Directory -ErrorAction SilentlyContinue | ForEach-Object {
        $td = Join-Path $_.FullName 'transforms'
        if (-not (Test-Path -LiteralPath $td)) { return }
        Get-ChildItem -Path $td -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            $tr = Join-Path $_.FullName 'transformed'
            if (-not (Test-Path -LiteralPath $tr)) { return }
            Get-ChildItem -Path $tr -Directory -Filter 'idea*' -ErrorAction SilentlyContinue | ForEach-Object {
                Remove-Item -LiteralPath $_.FullName -Recurse -Force -ErrorAction SilentlyContinue
                $script:removedCount++
            }
        }
    }
    Write-Host "已清理 IDE 解包目录 $script:removedCount 个（重跑会重新解包）"
}

Write-Host "run-gradle-retry: ./gradlew.bat $GradleArgs"
$first = Invoke-Attempt -Label '原始'
if ($first.Code -eq 0) { exit 0 }

$last = $first
if (Test-RaceInLog $first.Log) {
    Write-Host '::warning::命中 Gradle zip 文件系统竞态（ClosedFileSystemException，非代码缺陷）：停 daemon + 清项目侧 IPGP 缓存与 IDE 解包后重跑'
    & ./gradlew.bat --stop 2>&1 | Out-Null
    Clear-IdeCaches
    $second = Invoke-Attempt -ExtraArgs @('--max-workers=1') -Label '竞态恢复一级'
    if ($second.Code -eq 0) { exit 0 }
    $last = $second

    if (Test-RaceInLog $second.Log) {
        if (-not $env:GITHUB_ACTIONS) {
            Write-Host '本机执行：跳过干净工作树重试（git clean -xdf 会抹掉工作树，仅 CI 上允许）'
        } else {
            Write-Host '::warning::竞态一级未恢复：退到干净工作树（git clean -xdf，保留 .local）重跑'
            & ./gradlew.bat --stop 2>&1 | Out-Null
            git clean -xdf -e .local 2>&1 | Select-Object -Last 5
            $third = Invoke-Attempt -ExtraArgs @('--max-workers=1') -Label '干净工作树'
            if ($third.Code -eq 0) { exit 0 }
            $last = $third
        }
    }
}

# 非竞态失败：测试红只重跑失败类一次（编译已 UP-TO-DATE，约 1 分钟）；其它失败立即退出。
if (Test-TestFailureInLog $last.Log) {
    $failedClasses = @(Get-FailedTestClasses (Join-Path $repoRoot 'build/test-results'))
    if ($failedClasses.Count -gt 0) {
        $list = ($failedClasses -join ', ')
        Write-Host "::warning::测试失败（$list）：只重跑失败类一次（编译已 UP-TO-DATE）"
        $scoped = @()
        foreach ($cls in $failedClasses) { $scoped += @('--tests', $cls) }
        $retry = Invoke-Attempt -ExtraArgs $scoped -Label '失败类重跑'
        if ($retry.Code -eq 0) {
            Write-Host "::warning::失败类重跑通过，判定为 flaky（已点名，未静默）：$list"
            exit 0
        }
        exit $retry.Code
    }
}

exit $last.Code
