# CI 用的 Gradle 调用包装：命中「zip 文件系统竞态」时逐级处置后重跑（三级，见文件末尾）。
#
# 背景（T-CiZipFsFlake，2026-11-05 实证）：Gradle 配置期解析 IDE 自带插件（com.intellij.database
# 来自 243 发行版的 DatabaseTools/lib/database-plugin.jar）时会偶发
# java.nio.file.ClosedFileSystemException（栈顶 plugin-structure 的
# FsHandlerFileSystemProvider.checkAccess → Files.exists），随后被报成
# "Could not find bundled plugin with ID: 'com.intellij.database'"。
# 实证：run#8 1/5 片、run#11 2/5 片、run#16 1/5 片；失败片的 gradle 缓存恢复是完整的。
#
# 关键观察（run#16）：原地重跑**没用**——第二次 4 秒就同样失败。原因是 Gradle daemon 跨两次
# 客户端调用复用，已关闭的 zip 文件系统状态留在 daemon 里。所以重跑前必须 `--stop` 换新 daemon。
#
# 用法：pwsh -NoProfile -File dev-support/ci/run-gradle-retry.ps1 -GradleArgs ':testShard1 -PgifRecord=false'
# 只对 ClosedFileSystemException 这一个签名重试；测试真失败不会命中，因此不会掩盖问题。

param(
    [Parameter(Mandatory = $true)][string]$GradleArgs
)

$ErrorActionPreference = 'Continue'
$argList = $GradleArgs -split '\s+' | Where-Object { $_ -ne '' }
# RUNNER_TEMP 只在 GitHub runner 上有；本机试跑时为空会让 Join-Path 产出 null，
# 进而 Tee-Object/Select-String 报错、`-not (...)` 误判成「命中竞态」。回落到系统临时目录。
$logDir = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [System.IO.Path]::GetTempPath() }
$log = Join-Path $logDir 'gradle-run.log'

Write-Host "run-gradle-retry: ./gradlew.bat $GradleArgs"
& ./gradlew.bat @argList 2>&1 | Tee-Object -FilePath $log
$rc = $LASTEXITCODE
if ($rc -eq 0) { exit 0 }

if (-not (Test-Path -LiteralPath $log)) { exit $rc }
if (-not (Select-String -LiteralPath $log -Pattern 'ClosedFileSystemException' -SimpleMatch -Quiet)) {
    exit $rc
}

Write-Host '::warning::命中 Gradle zip 文件系统竞态（ClosedFileSystemException，非代码缺陷）：停 daemon + 清 IDE 解包目录后重跑一次'
& ./gradlew.bat --stop 2>&1 | Out-Null

# 2026-11-05 run#16/#17 实证：只 --stop（甚至在重跑加 --max-workers=1）都救不回来——说明坏状态落在
# 磁盘上的 IDE 解包产物里（Gradle 的 zip 文件系统缓存把已关闭的 FS 又交回给 plugin-structure）。
# 所以重跑前把 IDE 解包目录（transforms/*/transformed/idea*）删掉，逼 Gradle 重新解包：
# 换掉那个目录 = 换掉绑定在上面的 FS 句柄，这是目前唯一有效的状态重置。
# 只删 idea*（IDE 发行版解包），不动其它 jar 的 transform，重解包代价约 30~60 秒。
$transformsRoot = Join-Path $env:GRADLE_USER_HOME 'caches'
$removed = 0
Get-ChildItem -Path $transformsRoot -Directory -ErrorAction SilentlyContinue | ForEach-Object {
    $td = Join-Path $_.FullName 'transforms'
    if (-not (Test-Path -LiteralPath $td)) { return }
    Get-ChildItem -Path $td -Directory -ErrorAction SilentlyContinue | ForEach-Object {
        $transformed = Join-Path $_.FullName 'transformed'
        if (-not (Test-Path -LiteralPath $transformed)) { return }
        Get-ChildItem -Path $transformed -Directory -Filter 'idea*' -ErrorAction SilentlyContinue | ForEach-Object {
            Remove-Item -LiteralPath $_.FullName -Recurse -Force -ErrorAction SilentlyContinue
            $script:removed++
        }
    }
}
Write-Host "已清理 IDE 解包目录 $script:removed 个（重跑会重新解包）"
# 重跑加 --max-workers=1：该异常的形态是「同一份 classpath 被两个任务并行解析，先完成的把 zip
# 文件系统关掉，另一个撞上 ClosedFileSystemException」。run#16 与夜间首轮实证：只换 daemon 不够
# （第二次 11 秒同样失败），必须同时把并行度压到 1 才能换掉触发条件。
& ./gradlew.bat @argList --max-workers=1 2>&1 | Tee-Object -FilePath $log -Append
$rc2 = $LASTEXITCODE
if ($rc2 -eq 0) { exit 0 }
if (-not (Select-String -LiteralPath $log -Pattern 'ClosedFileSystemException' -SimpleMatch -Quiet)) {
    exit $rc2
}

# ---------- 第三、四级（2026-11-05 深夜 run#23/#24/#25 三次实测后重写） ----------
# 已被**实测**证伪的五条（别再走）：
#   ❌ 第二级 --stop + --max-workers=1 + 删 transforms/*/transformed/idea*：run#23/#24/#25 五次命中，
#      全部在重解包完成后 54s~1m41s 报同一条错。
#   ❌ 删 modules-2 的 IDE 发行版产物：run#24，1m32s 同错且没有触发重新下载。
#   ❌ --no-configuration-cache（run#25 第三级）：54s 同错。
#   ❌ 再加 -Dorg.gradle.vfs.watch=false（run#25 第四级）：16s 同错。
# run#25 的诊断还排除了「文件坏了」：那个 jar 存在、147,727,903 字节，与本机同一份字节数完全一致。
#
# 现在最像的因：IPGP 把 IDE 的**布局索引**（product-info / bundled plugin 描述符路径）缓存在
# **项目目录** `.intellijPlatform/layoutIndex/*.json` 与 `extracted-plugins/`，不是 GRADLE_USER_HOME。
# 前面的每一级都在清 GRADLE_USER_HOME（transforms / modules-2），而 CI 的 workspace 是复用的
# （同一 job 内四次重跑共享同一份 `.intellijPlatform`），所以清错地方了。
# 第三、四级改为清**项目侧**缓存；每条 1~4 分钟：
#   三级：--stop + 删 transforms/*/transformed/idea* + 删 .intellijPlatform/layoutIndex 与 extracted-plugins
#   四级：再退到「干净工作树」——git clean -xdf（保留 .local）后重跑，等价于换一份 workspace
Write-Host '::warning::zip-FS 竞态第二级未恢复：清项目侧 IPGP 缓存（.intellijPlatform/layoutIndex）后重试'
& ./gradlew.bat --stop 2>&1 | Out-Null

# 诊断（写进日志，下一轮判因用）：那个 jar 到底在不在、多大
$cachesRootForDiag = Join-Path $env:GRADLE_USER_HOME 'caches'
Get-ChildItem -Path $cachesRootForDiag -Directory -ErrorAction SilentlyContinue | ForEach-Object {
    $td = Join-Path $_.FullName 'transforms'
    if (-not (Test-Path -LiteralPath $td)) { return }
    Get-ChildItem -Path $td -Directory -ErrorAction SilentlyContinue | ForEach-Object {
        $jar = Join-Path $_.FullName 'transformed\ideaIU-2024.3.7.1-win\plugins\DatabaseTools\lib\database-plugin.jar'
        if (Test-Path -LiteralPath $jar) {
            $item = Get-Item -LiteralPath $jar
            Write-Host ("诊断：{0} 存在，{1} 字节，mtime={2}" -f $jar, $item.Length, $item.LastWriteTime)
        }
    }
}

# 第三级：清项目侧 IPGP 缓存 + 重解包 IDE
foreach ($rel in @('.intellijPlatform\layoutIndex', '.intellijPlatform\extracted-plugins')) {
    $pth = Join-Path (Get-Location) $rel
    if (Test-Path -LiteralPath $pth) {
        Remove-Item -LiteralPath $pth -Recurse -Force -ErrorAction SilentlyContinue
        Write-Host "已清理项目侧缓存：$pth"
    }
}
Get-ChildItem -Path $cachesRootForDiag -Directory -ErrorAction SilentlyContinue | ForEach-Object {
    $td = Join-Path $_.FullName 'transforms'
    if (-not (Test-Path -LiteralPath $td)) { return }
    Get-ChildItem -Path $td -Directory -ErrorAction SilentlyContinue | ForEach-Object {
        $transformed = Join-Path $_.FullName 'transformed'
        if (-not (Test-Path -LiteralPath $transformed)) { return }
        Get-ChildItem -Path $transformed -Directory -Filter 'idea*' -ErrorAction SilentlyContinue |
            ForEach-Object { Remove-Item -LiteralPath $_.FullName -Recurse -Force -ErrorAction SilentlyContinue }
    }
}
Write-Host '第三级重跑：清项目侧 IPGP 缓存后重来'
& ./gradlew.bat @argList --max-workers=1 2>&1 | Tee-Object -FilePath $log -Append
$rc3 = $LASTEXITCODE
if ($rc3 -eq 0) { exit 0 }
if (-not (Select-String -LiteralPath $log -Pattern 'ClosedFileSystemException' -SimpleMatch -Quiet)) {
    exit $rc3
}

# 第四级：干净工作树（保留 .local——方言矩阵/凭据在那里）。等价于给这条腿换一份 workspace。
# **只在 CI 上做**：本机跑这条脚本时工作树可能有未提交改动，git clean -xdf 会直接抹掉别人的活。
if (-not $env:GITHUB_ACTIONS) {
    Write-Host '本机执行：跳过第四级（git clean -xdf 会抹掉工作树，仅 CI 上允许）'
    exit $rc3
}
Write-Host '::warning::zip-FS 竞态第三级未恢复：退到干净工作树（git clean -xdf，保留 .local）重试第四级'
& ./gradlew.bat --stop 2>&1 | Out-Null
git clean -xdf -e .local 2>&1 | Select-Object -Last 5
Write-Host '第四级重跑：干净工作树'
& ./gradlew.bat @argList --max-workers=1 2>&1 | Tee-Object -FilePath $log -Append
exit $LASTEXITCODE
