# T-TestShardParallel（2026-10-20）：全量测试分片并行运行器
#
# 背景：Gradle 只并行**不同项目**的任务，`testShards` 的 5 个分片任务都在 root project，
# 实测严格串行（测试段 826s）。本脚本把每个分片放进独立 Gradle 进程（独立 project-cache），
# 预热一次编译/打包后并发执行；并发不安全类由 `test-shard-serial-classes.txt` 隔离、串行补跑。
#
# 用法（在仓库根目录）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File dev-support\test-shards-parallel.ps1
#   ... -Lanes 5              # 手动指定并发路数（缺省按 Commit 余量自动算）
#   ... -ShardCount 5         # 分片数（与构建默认一致）
#   ... -SkipPrep             # 跳过预热（已在同一工作树预热过）
#   ... -UseDaemon            # 复用常驻 Gradle daemon（缺省 --no-daemon 省内存）
#
# 退出码 = 0 仅当：所有分片、串行隔离类、testExternal、testFast（纯逻辑快桶腿）、agent 单测全部成功。
# 结果聚合复用 dev-support/test-shard-aggregate.py（与 testShards 同口径）。

param(
    [int]$Lanes = 0,
    [int]$ShardCount = 5,
    [int]$MemoryPerLaneGb = 3,
    [string]$QuarantineFile = 'dev-support/test-shard-serial-classes.txt',
    [switch]$SkipPrep,
    [switch]$UseDaemon
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$gradlew = Join-Path $root 'gradlew.bat'
$cacheRoot = Join-Path $root 'build/.parallel-shards'
New-Item -ItemType Directory -Force -Path $cacheRoot | Out-Null

# ---------- 0. 并行登记检查 ----------
& python 'C:\Users\USER\.zcode\cli\parallel_tasks.py' check
if ($LASTEXITCODE -ne 0) { throw '并行登记检查失败：有活跃任务，禁止并行跑全量' }

# ---------- 1. 内存闸门 ----------
$limitGb = (Get-Counter '\Memory\Commit Limit').CounterSamples[0].CookedValue / 1GB
$committedGb = (Get-Counter '\Memory\Committed Bytes').CounterSamples[0].CookedValue / 1GB
$freeCommitGb = $limitGb - $committedGb
$autoLanes = [Math]::Max(1, [Math]::Min($ShardCount, [Math]::Floor(($freeCommitGb - 2) / $MemoryPerLaneGb)))
$useLanes = if ($Lanes -gt 0) { [Math]::Min($Lanes, $ShardCount) } else { [int]$autoLanes }
Write-Output ("[parallel] Commit {0:N1}/{1:N1} GB, free {2:N1} GB -> lanes={3} (per-lane budget {4} GB)" -f `
    $committedGb, $limitGb, $freeCommitGb, $useLanes, $MemoryPerLaneGb)
if ($Lanes -gt 0 -and $useLanes -gt $autoLanes) {
    Write-Output "[parallel] WARN: 手动 -Lanes $useLanes 超过内存闸门建议 $autoLanes，可能 OOM"
}
if ($useLanes -lt 2) {
    Write-Output '[parallel] 内存余量不足两路，回落串行 testShards'
    & $gradlew testShards --no-configuration-cache --continue
    exit $LASTEXITCODE
}

# ---------- 2. 串行隔离类 ----------
$excluded = @()
if (Test-Path $QuarantineFile) {
    $excluded = @(Get-Content -Encoding UTF8 $QuarantineFile |
        Where-Object { $_.Trim() -ne '' -and -not $_.TrimStart().StartsWith('#') } |
        ForEach-Object { $_.Trim() })
}
$excludeProp = if ($excluded.Count -gt 0) { '-PtestShardExcludeClasses=' + ($excluded -join ',') } else { $null }
# 并行轮必须让每条 lane 得到同一份分片计划：开跑前把上一轮 test-results 冻结成快照，
# 所有构建都读这份快照算权重（真实耗时 + 时点无关）。冷树无样本时退到入库画像。
$snap = Join-Path $cacheRoot 'weight-snapshot'
Remove-Item -Recurse -Force $snap -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $snap | Out-Null
$snapDirs = 0
foreach ($n in @('test') + (1..$ShardCount | ForEach-Object { "testShard$_" })) {
    $src = Join-Path $root "build/test-results/$n"
    if (Test-Path $src) {
        Copy-Item -Recurse -Force $src (Join-Path $snap $n)
        $snapDirs++
    }
}
if ($snapDirs -gt 0) {
    $planProps = @('-PtestShardWeightRoot=' + $snap)
    Write-Output ("[parallel] weight snapshot: {0} result dirs frozen at {1}" -f $snapDirs, $snap)
} else {
    $planProps = @('-PtestShardIgnoreLocalWeights=true')
    Write-Output '[parallel] weight snapshot: no previous results -> committed profile only'
}
Write-Output ("[parallel] quarantined classes: {0}" -f ($(if ($excluded.Count) { $excluded -join ', ' } else { '(none)' })))

# ---------- 3. 共享产物排除表（漏一项即写冲突；预热与 lane 必须同表） ----------
$excl = @(
    '-x', 'compileKotlin', '-x', 'compileJava', '-x', 'compileTestKotlin', '-x', 'compileTestJava',
    '-x', 'processResources', '-x', 'processTestResources', '-x', 'processMcpStubResources',
    '-x', 'compileMcpStubKotlin', '-x', 'compileMcpStubJava', '-x', 'mcpStubClasses',
    '-x', 'classes', '-x', 'testClasses', '-x', 'jar', '-x', 'composedJar', '-x', 'embedAgentJar',
    '-x', 'buildPlugin', '-x', 'patchPluginXml', '-x', 'generateManifest',
    '-x', 'instrumentCode', '-x', 'instrumentTestCode',
    '-x', ':sql-visualizer-agent:agentFatJar', '-x', ':sql-visualizer-agent:jar',
    '-x', ':sql-visualizer-agent:classes', '-x', ':sql-visualizer-agent:compileJava',
    '-x', ':sql-visualizer-agent:compileRelocJava', '-x', ':sql-visualizer-agent:relocClasses',
    '-x', ':sql-visualizer-agent:processResources'
)

# ---------- 4. 预热（单构建，消除 buildSrc/共享产物并发写） ----------
if (-not $SkipPrep) {
    $prep = @('help', 'testClasses', 'composedJar')
    for ($i = 1; $i -le $ShardCount; $i++) { $prep += "prepareSandbox_testShard$i" }
    $prep += ':sql-visualizer-agent:testClasses'
    # buildSrc 的脚本编译输入含 Gradle 属性；prep 与 lane 必须带同一个
    # -PtestShardExcludeClasses，否则 lane 侧 buildSrc 会被判输入变化而重编（并发互踩）。
    if ($excludeProp) { $prep += $excludeProp }
    $prep += $planProps
    Write-Output '[parallel] prep: buildSrc + testClasses + composedJar + shard sandboxes'
    & $gradlew @prep --no-configuration-cache --console=plain
    if ($LASTEXITCODE -ne 0) { throw '预热失败，终止并行运行' }
    # 不再给 lane 缓存串行预热：并发互踩的根因是多个 Gradle 进程共享
    # buildSrc/build（任一路重编会让其余路输出指纹失效并级联重编）。
    # 现在每条 lane 用 -PmpvBuildSrcDir 指独立输出目录（见下方 Start-Shard），
    # 各路各编一次、互不共享写；Kotlin 用 in-process 编译避免 daemon 会话互斥。
}

# ---------- 5. 并发跑分片 ----------
$started = Get-Date
$procs = @()
$pending = 1..$ShardCount
$daemonArg = if ($UseDaemon) { @() } else { @('--no-daemon') }

# PowerShell 直接给 .bat 传数组会被拆出裸 '-'（实测 shard1/external/agent 三处
# "Task '-' not found"）；统一走 cmd /c 的单命令行形态。
# 退出码：`Start-Process cmd /c` 的 Process.ExitCode 在本机 PS 5.1 上恒为 null
# （Refresh/WaitForExit 都读不到），改由 cmd 延迟展开把 %ERRORLEVEL% 写进 .exit 文件。
function Start-GradleLine([string]$argsLine, [string]$log) {
    Remove-Item -Force -ErrorAction SilentlyContinue "$log.exit"
    # 仓库根与缓存目录均无空格，cmd 单命令行无需再加引号（嵌套引号更易被 cmd 拆坏）。
    $cmd = "$gradlew $argsLine > `"$log`" 2> `"$log.err`" & echo !ERRORLEVEL!> `"$log.exit`""
    return Start-Process -FilePath 'cmd.exe' -ArgumentList '/v:on', '/c', $cmd -PassThru -NoNewWindow
}

function Get-BuildExit([string]$log) {
    $exitFile = "$log.exit"
    if (Test-Path $exitFile) {
        $raw = (Get-Content -Path $exitFile -Raw -Encoding ASCII).Trim()
        if ($raw -match '^\d+$') { return [int]$raw }
    }
    # 退路：按日志末段判读（cmd 包装异常时仍能给出结论）
    if (Test-Path $log) {
        $tail = (Get-Content -Path $log -Tail 40 -Encoding UTF8) -join "`n"
        if ($tail -match 'BUILD SUCCESSFUL') { return 0 }
    }
    return 1
}

function Start-Shard([int]$i) {
    $log = Join-Path $cacheRoot "shard$i.log"
    # 触发本轮真跑：删该分片的测试产物（Test 输出缺失 → 只重跑该 Test 任务）。
    # 不用 --rerun-tasks——它会把 buildSrc 也强制重编，两个 lane 并发重编同一 buildSrc
    # 会互相踩生成源（实测 :buildSrc:compileKotlin FAILED）。
    Remove-Item -Recurse -Force (Join-Path $root "build/test-results/testShard$i") -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force (Join-Path $root "build/reports/tests/testShard$i") -ErrorAction SilentlyContinue
    $a = @("testShard$i") + $excl
    if ($excludeProp) { $a += $excludeProp }
    $a += @('--project-cache-dir', (Join-Path $cacheRoot "pc$i"),
        '--no-configuration-cache', '--offline', '--console=plain',
        "-PmpvBuildSrcDir=build-lane$i",
        '-Pkotlin.compiler.execution.strategy=in-process') + $planProps + $daemonArg
    $p = Start-GradleLine ($a -join ' ') $log
    return [pscustomobject]@{ Shard = $i; Proc = $p; Log = $log; Err = "$log.err" }
}

# testExternal / agent 与分片并行：两者各自有独立 results 与沙盒目录，只读共享编译产物。
# 尾巴从串行 ~2 min 压到与分片重叠。
Remove-Item -Recurse -Force (Join-Path $root 'build/test-results/testExternal') -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force (Join-Path $root 'build/reports/tests/testExternal') -ErrorAction SilentlyContinue
$extArgs = @('testExternal') + $excl + @(
    '--project-cache-dir', (Join-Path $cacheRoot 'pc-external'),
    '--no-configuration-cache', '--offline', '--console=plain',
    '-PmpvBuildSrcDir=build-lane-external',
    '-Pkotlin.compiler.execution.strategy=in-process') + $planProps + $daemonArg
$extProc = Start-GradleLine ($extArgs -join ' ') (Join-Path $cacheRoot 'external.log')

# T-JUnit5Eval 方案 2：纯逻辑快桶是分片之外的第二条腿（分片只装平台类后，这 4000+ 例只由
# testFast 承载）。它与分片同样只读共享编译产物，单独一条 lane 与分片并行。
# 排除清单照传：testFast 与分片同源尊重 -PtestShardExcludeClasses（被排除的纯逻辑类不能从这条路溜回来）。
Remove-Item -Recurse -Force (Join-Path $root 'build/test-results/testFast') -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force (Join-Path $root 'build/reports/tests/testFast') -ErrorAction SilentlyContinue
$fastArgs = @('testFast') + $excl
if ($excludeProp) { $fastArgs += $excludeProp }
$fastArgs += @(
    '--project-cache-dir', (Join-Path $cacheRoot 'pc-fast'),
    '--no-configuration-cache', '--offline', '--console=plain',
    '-PmpvBuildSrcDir=build-lane-fast',
    '-Pkotlin.compiler.execution.strategy=in-process') + $planProps + $daemonArg
$fastProc = Start-GradleLine ($fastArgs -join ' ') (Join-Path $cacheRoot 'fast.log')

Remove-Item -Recurse -Force (Join-Path $root 'sub-projects/sql-visualizer-agent/build/test-results/test') -ErrorAction SilentlyContinue
$agentArgs = @(':sql-visualizer-agent:test', '--project-cache-dir', (Join-Path $cacheRoot 'pc-agent'),
    '--no-configuration-cache', '--offline', '--console=plain',
    '-PmpvBuildSrcDir=build-lane-agent',
    '-Pkotlin.compiler.execution.strategy=in-process') + $planProps + $daemonArg
$agentProc = Start-GradleLine ($agentArgs -join ' ') (Join-Path $cacheRoot 'agent.log')

$queue = [System.Collections.Generic.Queue[int]]::new()
foreach ($i in $pending) { $queue.Enqueue($i) }
# 判活不能用 Process.HasExited：`Start-Process cmd /c` 返回的对象在 PS 5.1 上
# HasExited 立刻为 true（与 ExitCode 同源），会让闸门失效、一次把全部分片都起出去。
function Get-ActiveProcs([object[]]$items) {
    return @($items | Where-Object { Get-Process -Id $_.Proc.Id -ErrorAction SilentlyContinue })
}
while ($queue.Count -gt 0 -or (Get-ActiveProcs $procs).Count -gt 0) {
    while ($queue.Count -gt 0 -and (Get-ActiveProcs $procs).Count -lt $useLanes) {
        $i = $queue.Dequeue()
        Write-Output "[parallel] launch shard$i"
        $procs += Start-Shard $i
    }
    Start-Sleep -Milliseconds 500
}
$procs | ForEach-Object { $_.Proc.WaitForExit() }
$shardFailed = $false
foreach ($x in $procs | Sort-Object Shard) {
    $code = Get-BuildExit $x.Log
    Write-Output ("[parallel] shard{0} exit={1} log={2}" -f $x.Shard, $code, $x.Log)
    if ($code -ne 0) { $shardFailed = $true }
}
# 计划一致性断言：所有 lane 必须打印同一份 duration-balanced 计划行。
# 并行配置若重新吃进本地历史样本，各 lane 会算出不同分配 → 测试重复/漏跑，必须硬失败。
$planLines = @{}
foreach ($x in $procs | Sort-Object Shard) {
    $hit = Select-String -Path $x.Log -Pattern 'testShards: \d+ shards duration-balanced' `
        -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($hit) { $planLines[$x.Shard] = $hit.Line.Trim() }
}
$distinctPlans = @($planLines.Values | Sort-Object -Unique)
Write-Output ("[parallel] lane plans: {0} distinct (expected 1)" -f $distinctPlans.Count)
if ($distinctPlans.Count -gt 1) {
    $distinctPlans | ForEach-Object { Write-Output "[parallel]   PLAN $_" }
    $shardFailed = $true
}
Write-Output ("[parallel] shard wall: {0:N1} min" -f ((Get-Date) - $started).TotalMinutes)

# ---------- 6. 串行隔离类补跑 ----------
$quarantineFailed = $false
if ($excluded.Count -gt 0) {
    Remove-Item -Recurse -Force (Join-Path $root 'build/test-results/test') -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force (Join-Path $root 'build/reports/tests/test') -ErrorAction SilentlyContinue
    $qargs = @('test') + $excl + @(
        '--project-cache-dir', (Join-Path $cacheRoot 'pc-quarantine'),
        '--no-configuration-cache', '--offline', '--console=plain') + $daemonArg
    foreach ($c in $excluded) { $qargs += @('--tests', $c) }
    Write-Output "[parallel] serial quarantine: $($excluded -join ', ')"
    & $gradlew @qargs
    if ($LASTEXITCODE -ne 0) { $quarantineFailed = $true }
}

# ---------- 7. 等 testExternal + agent（与分片并行启动，此处只收口） ----------
$extProc.WaitForExit()
$extCode = Get-BuildExit (Join-Path $cacheRoot 'external.log')
$externalFailed = $extCode -ne 0
Write-Output "[parallel] testExternal exit=$extCode"

$fastProc.WaitForExit()
$fastCode = Get-BuildExit (Join-Path $cacheRoot 'fast.log')
$fastFailed = $fastCode -ne 0
Write-Output "[parallel] testFast exit=$fastCode"

$agentProc.WaitForExit()
$agentCode = Get-BuildExit (Join-Path $cacheRoot 'agent.log')
$agentFailed = $agentCode -ne 0
Write-Output "[parallel] agent test exit=$agentCode"

# ---------- 8. 聚合 ----------
$agg = @('dev-support/test-shard-aggregate.py', '--results-dir', 'build/test-results', '--shards', $ShardCount)
if ($excluded.Count -gt 0) { $agg += @('--quarantine-dir', 'build/test-results/test') }
& python @agg
Write-Output ("[parallel] total wall: {0:N1} min" -f ((Get-Date) - $started).TotalMinutes)

if ($shardFailed -or $quarantineFailed -or $externalFailed -or $agentFailed -or $fastFailed) {
    Write-Output ("[parallel] FAILED shard={0} quarantine={1} external={2} agent={3} fast={4}" -f `
        $shardFailed, $quarantineFailed, $externalFailed, $agentFailed, $fastFailed)
    exit 1
}
Write-Output '[parallel] ALL GREEN'
exit 0
