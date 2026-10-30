# =============================================================================
# gradle-gate.ps1 —— 机器级 Gradle 构建并发闸门（T-BuildConcurrency C 项 / O-65）
#
# 作用：同一时刻最多允许 -MaxConcurrent（默认 2）路 gradle 构建并行，第 3 路起排队等待。
# 上限依据是内存而非 CPU：32 GB 物理内存下双路全量构建 Commit 峰值 66.9/74.7 GB
# （见 T-BuildConcurrency 计划 §实施记录 一），加路先加内存。
# 闸门只限流路数，不带任何降片逻辑（-PtestShardCount 调小已被实测证伪，禁用）。
#
# 用法一句话（推荐，一次性模式，自动拿锁-执行-还锁并传播退出码）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File dev-support\gradle-gate.ps1 -Command ".\gradlew testFast --tests <FQCN>"
# 两段式（同一脚本进程内先拿锁后还锁，跨进程退出即视为陈旧锁、由他人探测接管）：
#   gradle-gate.ps1 -Acquire -HoldSeconds 30     # 拿锁并保持 30 秒后自动还
#   gradle-gate.ps1 -Release -Session <guid>     # 显式还锁（与 -Acquire 输出的 SESSION 对应）
#
# 实现与自愈：
#   锁目录 %TEMP%\<GateName>\slot-0..slot-(N-1)，目录创建（create-or-fail）为原子互斥原语；
#   槽内 holder.txt 记 pid/session/utc/cmd。持有者进程异常退出不删目录，下一路按
#   「holder pid 已死即接管」自愈；15 秒初始化宽限防「拿锁-写 holder」窗口期被误判，
#   StaleAfterMinutes 兜底 pid 复用。陈旧锁接管不需要人工清理。
#
# 实现注意（本轮实测踩过的两个坑，改脚本前先读）：
#   1. 所有函数体禁用 Write-Output 报告状态——顶层用 $got = <函数> 接返回值时会把
#      输出流一并捕获进变量，状态行全部丢失（2026-09-23 实测）。状态行一律由顶层
#      打印，函数内部事件写入 $script:GateEvents 由顶层转储。
#   2. 新建 holder.txt 后立刻写可能撞实时扫描（Defender 等）对新生文件的瞬时独占，
#      报「正由另一进程使用」——WriteAllLines 带重试，重试耗尽则删掉自建槽目录返回
#      失败（绝不能带着无 holder 的槽假装持锁）。
#
# Windows 平台坑（与仓内纪律同源，写在这里防止后人踩）：
#   1. 带 /X 形式参数的 Windows 工具（robocopy /E 等）禁在 Git Bash 里直接调——MSYS 会把
#      /X 转成路径。本脚本是 PowerShell，天然规避；在 Git Bash 里调本脚本用
#      powershell -File 而不是直接执行脚本体。
#   2. -Command 里如果跑 robocopy 类工具：退出码 0~7 均为成功、8+ 才是失败，本闸门只
#      原样传播退出码，成功与否的判断留给调用方，禁用 && 串联推断成败。
#   3. %TEMP% 按用户隔离——本闸门信号量是「同一 Windows 用户」级；全部车道同一用户运行
#      时即等价机器级。
# =============================================================================

param(
    [string]$Command = '',
    [int]$MaxConcurrent = 2,
    [int]$WaitTimeoutSeconds = 3600,
    [switch]$Acquire,
    [int]$HoldSeconds = 0,
    [switch]$NoRelease,
    [switch]$Release,
    [string]$Session = '',
    [switch]$Force,
    [string]$GateName = 'mpsv-gradle-gate',
    [int]$PollIntervalMs = 2000,
    [int]$StaleAfterMinutes = 240
)

$ErrorActionPreference = 'Stop'

$GraceSeconds = 15   # 槽目录已建但 holder 未写完的初始化宽限，窗口期内不判陈旧
$root = Join-Path $env:TEMP $GateName
$script:GateEvents = New-Object 'System.Collections.Generic.List[string]'

function Test-ProcessAlive {
    param([int]$ProcId)
    return $null -ne (Get-Process -Id $ProcId -ErrorAction SilentlyContinue)
}

function Read-Holder {
    param([string]$SlotDir)
    $holderFile = Join-Path $SlotDir 'holder.txt'
    if (-not (Test-Path -LiteralPath $holderFile)) { return $null }
    foreach ($attempt in 1..3) {
        try {
            $map = @{}
            foreach ($line in (Get-Content -LiteralPath $holderFile -ErrorAction Stop)) {
                $kv = $line -split '=', 2
                if ($kv.Count -eq 2) { $map[$kv[0]] = $kv[1] }
            }
            if ($map.ContainsKey('pid')) { return $map }
            return $null
        } catch {
            Start-Sleep -Milliseconds 150   # 与持有者的写入瞬态冲突，等重试
        }
    }
    return $null
}

# 返回 'free-initializing'（窗口期，等）/ 'stale'（可接管）/ 'alive'（有人持有）
function Get-SlotState {
    param([string]$SlotDir)
    $holder = Read-Holder $SlotDir
    if ($null -eq $holder) {
        $created = (Get-Item -LiteralPath $SlotDir -ErrorAction Stop).CreationTimeUtc
        if (((Get-Date).ToUniversalTime() - $created).TotalSeconds -gt $GraceSeconds) { return 'stale' }
        return 'free-initializing'
    }
    $holderPid = 0
    if (-not [int]::TryParse([string]$holder['pid'], [ref]$holderPid)) { return 'stale' }
    if (-not (Test-ProcessAlive $holderPid)) { return 'stale' }
    $utc = [datetime]::MinValue
    if (-not [datetime]::TryParse([string]$holder['utc'], [ref]$utc)) { return 'stale' }
    if (((Get-Date).ToUniversalTime() - $utc.ToUniversalTime()).TotalMinutes -gt $StaleAfterMinutes) { return 'stale' }
    return 'alive'
}

# 返回 $true/$false；重试耗尽抛异常，由调用方清理自建槽
function Write-Holder {
    param([string]$SlotDir, [string]$Sess, [string]$CmdText)
    $holderFile = Join-Path $SlotDir 'holder.txt'
    $lines = @(
        "pid=$PID",
        "session=$Sess",
        "utc=$((Get-Date).ToUniversalTime().ToString('o'))",
        "cmd=$CmdText"
    )
    foreach ($attempt in 1..5) {
        try {
            [IO.File]::WriteAllLines($holderFile, $lines)
            return $true
        } catch {
            Start-Sleep -Milliseconds 200   # 新建文件遭实时扫描瞬时独占，等重试
        }
    }
    throw "Write-Holder failed after 5 attempts on $holderFile"
}

# 返回 @{slot=int; session=guid} 或 $null；静默，事件写 $script:GateEvents
function Try-TakeSlot {
    param([int]$Index, [string]$CmdText)
    $slotDir = Join-Path $root ("slot-{0}" -f $Index)
    $sess = [guid]::NewGuid().ToString('N')
    try {
        New-Item -ItemType Directory -Path $slotDir -ErrorAction Stop | Out-Null
    } catch {
        # 槽已被占：探陈旧，可接管则拆掉立即重建
        try {
            if ((Test-Path -LiteralPath $slotDir) -and ((Get-SlotState $slotDir) -eq 'stale')) {
                $script:GateEvents.Add("TAKEOVER slot=$Index previous holder dead or expired") | Out-Null
                Remove-Item -LiteralPath $slotDir -Recurse -Force -ErrorAction Stop
                try {
                    New-Item -ItemType Directory -Path $slotDir -ErrorAction Stop | Out-Null
                } catch { return $null }   # 拆完被并发者抢建，下轮再说
                Write-Holder $slotDir $sess $CmdText
                return @{ slot = $Index; session = $sess }
            }
        } catch { }
        return $null
    }
    try {
        Write-Holder $slotDir $sess $CmdText
    } catch {
        # 写 holder 失败绝不能带槽假装持锁：删自建目录，本轮放弃
        $script:GateEvents.Add("HOLDER-WRITE-FAILED slot=$Index cleaned up own dir") | Out-Null
        try { Remove-Item -LiteralPath $slotDir -Recurse -Force -ErrorAction SilentlyContinue } catch { }
        return $null
    }
    return @{ slot = $Index; session = $sess }
}

# 返回 @{slot=int; session=guid} 或超时抛异常；静默
function Acquire-Gate {
    param([string]$CmdText)
    if (-not (Test-Path -LiteralPath $root)) { New-Item -ItemType Directory -Path $root -Force | Out-Null }
    $deadline = (Get-Date).AddSeconds($WaitTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        for ($i = 0; $i -lt $MaxConcurrent; $i++) {
            $got = Try-TakeSlot $i $CmdText
            if ($null -ne $got) { return $got }
        }
        Start-Sleep -Milliseconds $PollIntervalMs
    }
    throw "GATE TIMEOUT no slot acquired within $WaitTimeoutSeconds s"
}

# 返回 $true/$false；静默
function Release-Gate {
    param([string]$Sess, [int]$Slot = -1)
    $targets = @()
    if ($Slot -ge 0) { $targets = @(("slot-{0}" -f $Slot)) }
    else {
        if (-not (Test-Path -LiteralPath $root)) { return $true }
        $dirs = Get-ChildItem -LiteralPath $root -Directory -Filter 'slot-*' -ErrorAction SilentlyContinue
        $targets = @($dirs | ForEach-Object { $_.Name })
    }
    foreach ($name in $targets) {
        $slotDir = Join-Path $root $name
        if (-not (Test-Path -LiteralPath $slotDir)) { continue }
        $holder = Read-Holder $slotDir
        $mine = $Force -or ($null -ne $holder -and ([string]$holder['session'] -eq $Sess -or [string]$holder['pid'] -eq [string]$PID))
        if ($mine) {
            try {
                Remove-Item -LiteralPath $slotDir -Recurse -Force -ErrorAction Stop
                return $true
            } catch {
                $script:GateEvents.Add("RELEASE-FAILED $name : $($_.Exception.Message)") | Out-Null
                return $false
            }
        }
    }
    return $false
}

function Emit-Events {
    foreach ($e in $script:GateEvents) {
        Write-Output ("GATE {0} at {1}" -f $e, (Get-Date -Format 'HH:mm:ss'))
    }
    $script:GateEvents.Clear() | Out-Null
}

# ---- 主流程（所有状态行只在顶层输出） ----
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
function Now-Stamp { Get-Date -Format 'HH:mm:ss' }

# ---- T-ReleasePerf10 券04：verifyMarketplace home 保温陈旧警告（只警告不阻断） ----
# 命中 releaseGate/verifyMarketplace 时查保温 stamp：>36h 陈旧或缺失 → WARN，建议先跑
# verifyMarketplaceRefresh。verifier home 机器级持久（GRADLE_USER_HOME/mpv-verifier-home，
# T-ReleasePerf8 N3），但上游依赖插件前进一周即可令发行日 market 单步膨胀到 18m29s+（10-29 实测）。
if ($Command -match 'releaseGate|verifyMarketplace') {
    $guh = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
    $stampFile = Join-Path $guh 'mpv-verifier-home\marketplace\.mpv-refresh-stamp'
    if (Test-Path -LiteralPath $stampFile) {
        $ageHours = ((Get-Date) - (Get-Item -LiteralPath $stampFile).LastWriteTime).TotalHours
        if ($ageHours -gt 36) {
            Write-Output ("MPV-VERIFIER-WARN: marketplace home refresh stamp is {0:N1}h old - release-day verifyMarketplace may pay incremental download tax (20261029 measured 18m29s). Prefer running first: .\gradlew verifyMarketplaceRefresh" -f $ageHours)
        } else {
            Write-Output ("MPV-VERIFIER: marketplace home refresh stamp fresh ({0:N1}h ago)" -f $ageHours)
        }
    } else {
        Write-Output 'MPV-VERIFIER-WARN: marketplace home has no refresh stamp - prefer running first: .\gradlew verifyMarketplaceRefresh'
    }
}

if ($Release) {
    if ([string]::IsNullOrWhiteSpace($Session) -and -not $Force) {
        Write-Output 'USAGE: -Release requires -Session <guid from ACQUIRED output> (or -Force)'
        exit 2
    }
    $ok = Release-Gate -Sess $Session
    Emit-Events
    if ($ok) { Write-Output ("GATE RELEASED session={0} at {1}" -f $Session, $(Now-Stamp)); exit 0 }
    Write-Output ("GATE RELEASE not found: session={0} (use -Force) at {1}" -f $Session, $(Now-Stamp))
    exit 3
}

if ($Acquire) {
    $got = Acquire-Gate '<-Acquire mode>'
    Emit-Events
    Write-Output ("GATE ACQUIRED slot={0} session={1} holderPid={2} at {3}" -f $got.slot, $got.session, $PID, $(Now-Stamp))
    if ($HoldSeconds -gt 0) { Start-Sleep -Seconds $HoldSeconds }
    if (-not $NoRelease) {
        Release-Gate -Sess $got.session -Slot $got.slot | Out-Null
        Emit-Events
        Write-Output ("GATE RELEASED slot={0} at {1}" -f $got.slot, $(Now-Stamp))
    } else {
        Write-Output ("GATE LEFT HELD slot={0} session={1} (process exit leaves stale lock; next acquirer takes over) at {2}" -f $got.slot, $got.session, $(Now-Stamp))
    }
    exit 0
}

if ([string]::IsNullOrWhiteSpace($Command)) {
    Write-Output 'USAGE: gradle-gate.ps1 -Command "<gradle command>"  |  -Acquire [-HoldSeconds N] [-NoRelease]  |  -Release -Session <guid>'
    exit 2
}

$got = Acquire-Gate $Command
Emit-Events
Write-Output ("GATE ACQUIRED slot={0} session={1} holderPid={2} at {3}" -f $got.slot, $got.session, $PID, $(Now-Stamp))
$code = 1
try {
    & cmd.exe /d /c $Command
    $code = $LASTEXITCODE
    if ($null -eq $code) { $code = 0 }   # 命令未产生退出码时按成功处理
    Write-Output ("GATE COMMAND EXIT={0} at {1}" -f $code, $(Now-Stamp))
} finally {
    Release-Gate -Sess $got.session -Slot $got.slot | Out-Null
    Emit-Events
    Write-Output ("GATE RELEASED slot={0} at {1}" -f $got.slot, $(Now-Stamp))
}
exit $code
