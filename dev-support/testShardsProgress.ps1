# =============================================================================
# testShardsProgress.ps1 —— 全量门禁（testShards）实时进度包装
#
# 为什么需要：`gradlew testShards` 在 `--console=plain` 下只有任务开始/结束两行，
# 五个分片并发跑十几分钟期间终端没有任何读数，判断「跑到哪了 / 卡没卡」只能靠等。
#
# 用法（在你自己的终端里跑，进度就刷在同一个窗口）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File dev-support\testShardsProgress.ps1
#   ... -IntervalSeconds 5                    # 刷新间隔（默认 10 秒）
#   ... -NoConfigCache:$false                 # 需要复用配置缓存时关掉（默认关缓存，防分片清单被钉死）
#   ... -ProgressLogPath D:\tmp\gate.log       # 进度文件落点（默认 build/test-shards-progress.log）
#   ... -ExtraArgs "--tests", "..."            # 追加任意 gradle 参数
#   ... -ShardCount 4                          # 分片数（须与 -PtestShardCount 一致，默认 5）
#
# 两路输出（内容一致）：
#   1. 当前终端：每 IntervalSeconds 一行
#   2. 进度文件（默认 build/test-shards-progress.log，每次运行覆盖）——另开窗口跟随：
#        Get-Content build\test-shards-progress.log -Wait -Tail 20
#
# 输出形态：
#   [03:12] 已跑 3m12s | 分片 2/5 完成 | 测试 3120 | 失败 0 | 正在: shard2 shard3 | 日志尾: > Task :testShard3
# 结束打印每个分片的 tests/failures/errors/skipped 与失败用例清单，退出码 = gradle 退出码。
#
# 读数来源（都是既有产物，不额外埋点）：
#   - 分片完成：build/test-results/testShard<N>/ 下**本次运行产出**的 TEST-*.xml
#     （按 mtime ≥ 启动时刻过滤——目录里保留着上一轮的 XML，不过滤会一开场就显示「5/5 完成」）
#   - 计数：解析各 XML 根节点的 tests/failures/errors/skipped
#   - 正在跑哪个分片：日志里出现 `> Task :testShard<N>` 且该片尚未产出结果
# =============================================================================
param(
    [int]$IntervalSeconds = 10,
    [switch]$NoConfigCache = $true,
    [string]$ProgressLogPath = "",
    [string[]]$ExtraArgs = @(),
    # T-TestGapSpeed T6：与 gradlew -PtestShardCount 对齐（缺省 5 = mpv-testing 默认片数）；
    # 调片数跑分片时两边要一起改，否则进度行的「分片 N/M」与结果解析都会错。
    [int]$ShardCount = 5
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$log = Join-Path $env:TEMP "mpsv-testshards-$stamp.log"
$errLog = Join-Path $env:TEMP "mpsv-testshards-$stamp.err.log"
$shardCount = $ShardCount

if ([string]::IsNullOrWhiteSpace($ProgressLogPath)) {
    $ProgressLogPath = Join-Path $root 'build/test-shards-progress.log'
}
$progressDir = Split-Path -Parent $ProgressLogPath
if ($progressDir -and -not (Test-Path $progressDir)) { New-Item -ItemType Directory -Path $progressDir -Force | Out-Null }

# 两路输出：终端 + 进度文件（同一行文本）。写文件失败不影响门禁本身。
function Write-Line {
    param([string]$Text)
    Write-Host $Text
    try { Add-Content -LiteralPath $ProgressLogPath -Value $Text -Encoding utf8 } catch { }
}

$gradleArgs = @('testShards', '--console=plain', '--continue')
if ($NoConfigCache) { $gradleArgs += '--no-configuration-cache' }
if ($ExtraArgs.Count -gt 0) { $gradleArgs += $ExtraArgs }

$started = Get-Date
# 本次运行的起点（UTC）——分片目录里保留着上一轮的 XML，用它过滤「本次是否已产出结果」。
$script:startedUtc = $started.ToUniversalTime()

try { Set-Content -LiteralPath $ProgressLogPath -Value "" -Encoding utf8 } catch { }
Write-Line ("=== testShards 进度（开始 {0}） ===" -f $started.ToString('yyyy-MM-dd HH:mm:ss'))
Write-Line ("命令：gradlew " + ($gradleArgs -join ' '))
Write-Line "门禁原始日志：$log"
Write-Line "（跟随原始输出：Get-Content '$log' -Wait -Tail 30）"
Write-Line ""

$proc = Start-Process -FilePath (Join-Path $root 'gradlew.bat') -ArgumentList $gradleArgs `
    -RedirectStandardOutput $log -RedirectStandardError $errLog -PassThru -NoNewWindow

# 只认本次运行产出的结果文件（mtime ≥ 启动时刻），避免上一轮残留被当成「已完成」。
function Get-ShardStats {
    param([int]$Index = 0, [string]$DirName = '')
    # T-TestGapSpeed T10：支持按目录名统计——T5 新增的 testShardTail 与 testExternal 桶
    # 此前不在汇总面内，尾道失败在汇总里完全不可见（20261105 实测踩到）。
    $bucket = if ($DirName) { $DirName } else { "testShard$Index" }
    $dir = Join-Path $root "build/test-results/$bucket"
    if (-not (Test-Path $dir)) { return $null }
    $all = @(Get-ChildItem -Path $dir -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue)
    $files = @($all | Where-Object { $_.LastWriteTimeUtc -ge $script:startedUtc })
    if ($files.Count -eq 0) { return $null }
    $tests = 0; $failures = 0; $errors = 0; $skipped = 0
    foreach ($f in $files) {
        try {
            # 用 DocumentElement.GetAttribute 读根节点属性：`$x.testsuite.tests` 在个别文件上会静默
            # 算出 0（实测汇总 395 vs 真实 1568），属性式读取稳定。
            [xml]$doc = Get-Content -LiteralPath $f.FullName -Raw -Encoding UTF8 -ErrorAction Stop
            $root = $doc.DocumentElement
            $tests += [int]$root.GetAttribute('tests')
            $failures += [int]$root.GetAttribute('failures')
            $errors += [int]$root.GetAttribute('errors')
            $skipped += [int]$root.GetAttribute('skipped')
        } catch { }
    }
    return [pscustomobject]@{ Tests = $tests; Failures = $failures; Errors = $errors; Skipped = $skipped; Classes = $files.Count }
}

function Get-LogTail {
    if (-not (Test-Path $log)) { return '' }
    try {
        $last = Get-Content -LiteralPath $log -Tail 1 -ErrorAction Stop
        if ($null -eq $last) { return '' }
        $line = ($last -join ' ').Trim()
        if ($line.Length -gt 70) { $line = $line.Substring(0, 70) + '…' }
        return $line
    } catch { return '' }
}

while (-not $proc.HasExited) {
    Start-Sleep -Seconds $IntervalSeconds
    $done = @(); $running = @(); $totalTests = 0; $totalFail = 0
    for ($i = 1; $i -le $shardCount; $i++) {
        $s = Get-ShardStats -Index $i
        if ($s) {
            $done += "shard$i"
            $totalTests += $s.Tests
            $totalFail += ($s.Failures + $s.Errors)
        }
    }
    $text = ''
    if (Test-Path $log) {
        $text = Get-Content -LiteralPath $log -Raw -ErrorAction SilentlyContinue
        for ($i = 1; $i -le $shardCount; $i++) {
            if ("shard$i" -notin $done -and $text -and $text.Contains("> Task :testShard$i")) {
                $running += "shard$i"
            }
        }
    }
    # T-JUnit5Eval 方案 2：快桶腿与分片并行，进度行也要能看出它在不在跑（约 4~5 分钟一段）。
    $fastStats = Get-ShardStats -DirName 'testFast'
    if ($fastStats) {
        $totalTests += $fastStats.Tests
        $totalFail += ($fastStats.Failures + $fastStats.Errors)
    } elseif ($text -and $text.Contains('> Task :testFast')) {
        $running += 'fast'
    }
    $elapsed = (Get-Date) - $started
    $elapsedText = '{0:mm\:ss}' -f $elapsed
    $tail = Get-LogTail
    $line = "[{0}] 已跑 {1} | 分片 {2}/{3} 完成 | 测试 {4} | 失败 {5}" -f `
        (Get-Date -Format 'HH:mm'), $elapsedText, $done.Count, $shardCount, $totalTests, $totalFail
    if ($running.Count -gt 0) { $line += " | 正在: $($running -join ' ')" }
    if ($tail) { $line += " | 日志尾: $tail" }
    Write-Line $line
}

$proc.Refresh()
# T-TestGapSpeed T10：Start-Process -PassThru 的 ExitCode 在未 WaitForExit 时读不到
# （20261105 实测：尾道失败但脚本报「退出码 」，把失败伪装成通过）。此处显式等进程收尾。
if (-not $proc.HasExited) { $proc.WaitForExit() }
$exit = $proc.ExitCode
if ($null -eq $exit) {
    # 仍读不到时按日志判定，绝不静默当成功
    $logText = if (Test-Path $log) { Get-Content -LiteralPath $log -Raw -ErrorAction SilentlyContinue } else { '' }
    $exit = if ($logText -match 'BUILD SUCCESSFUL') { 0 } elseif ($logText -match 'BUILD FAILED') { 1 } else { -1 }
    Write-Line "WARN: 进程退出码不可读，按日志判定为 $exit"
}
Write-Line ""
Write-Line "=== 门禁结束（退出码 $exit，耗时 $('{0:mm\:ss}' -f ((Get-Date) - $started)) ） ==="
if ($exit -ne 0) { Write-Line "*** 门禁失败（退出码 $exit）——下方计数仅供参考，以退出码与失败用例清单为准 ***" }
$grandTests = 0; $grandFail = 0; $grandErr = 0; $grandSkip = 0
$failNames = New-Object System.Collections.Generic.List[string]
# T-TestGapSpeed T10：汇总面 = 分片 + 尾道 + 外部护栏桶（后两者 T5/T-TestPerf6 引入，
# 此前脚本只统计分片，尾道失败不会出现在任何计数里）。
$buckets = @(1..$shardCount | ForEach-Object { @{ Label = "shard$_"; Dir = "testShard$_" } })
$buckets += @{ Label = 'tail'; Dir = 'testShardTail' }
# T-JUnit5Eval 方案 2：纯逻辑快桶从分片摘出后单独成腿（testShards 聚合 dependsOn testFast），
# 不进汇总面就等于「4000+ 例跑了但看不见」——失败也不会出现在失败用例清单里。
$buckets += @{ Label = 'fast'; Dir = 'testFast' }
$buckets += @{ Label = 'external'; Dir = 'testExternal' }
foreach ($b in $buckets) {
    $s = Get-ShardStats -DirName $b.Dir
    if ($s) {
        Write-Line ("  {0}: tests={1} failures={2} errors={3} skipped={4} classes={5}" -f `
            $b.Label, $s.Tests, $s.Failures, $s.Errors, $s.Skipped, $s.Classes)
        $grandTests += $s.Tests; $grandFail += $s.Failures; $grandErr += $s.Errors; $grandSkip += $s.Skipped
        $dir = Join-Path $root "build/test-results/$($b.Dir)"
        Get-ChildItem -Path $dir -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue |
            Where-Object { $_.LastWriteTimeUtc -ge $script:startedUtc } |
            ForEach-Object {
                try {
                    [xml]$doc = Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8 -ErrorAction Stop
                    foreach ($tc in $doc.DocumentElement.SelectNodes('testcase')) {
                        if ($tc.failure -or $tc.error) { $failNames.Add("$($tc.classname).$($tc.name)") }
                    }
                } catch { }
            }
    } else {
        Write-Line ("  {0}: 无结果（未执行或产物缺失）" -f $b.Label)
    }
}
Write-Line ("  合计: tests={0} failures={1} errors={2} skipped={3}" -f $grandTests, $grandFail, $grandErr, $grandSkip)
if ($failNames.Count -gt 0) {
    Write-Line "  失败用例："
    $failNames | Sort-Object -Unique | ForEach-Object { Write-Line "   - $_" }
}
Write-Line "  进度文件：$ProgressLogPath"
Write-Line "  原始日志：$log"
exit $exit
