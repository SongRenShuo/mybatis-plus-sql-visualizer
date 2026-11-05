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

# ---------- 第三、四级（2026-11-05 深夜 run#23/#24 两次实测后重写） ----------
# 已被证伪的三条路（别再走）：
#   ❌ 第二级（--stop + --max-workers=1 + 删 transforms/*/transformed/idea*）：run#23 的 test(6)、
#      run#24 的 test(5)/fast(2) 三次命中，全部在重解包完成后 1m32s~1m41s 报同一条错。
#   ❌ 删 modules-2 的 IDE 发行版产物（原第三级）：run#24 实测，删完 1m32s 又同错，且**没有触发重新下载**
#      （1GB 的发行版不可能 1 分半下完）——说明坏的不是磁盘字节。
# 现在的事实（run#24 两条腿的完整栈）：
#   失败点在**配置期**解析 :compileClasspath 时，plugin-structure 的 JarsResourceResolver 去
#   `Files.exists(.../transforms/<hash>/transformed/ideaIU-2024.3.7.1-win/plugins/DatabaseTools/lib/database-plugin.jar)`，
#   而那个 jar 的 ZipFileSystem 已经关掉了；栈里出现 `NioFileInterceptors.intercept_exists`
#   ——即**配置缓存（CC）的类路径插桩**在中间。所以「重跑同一条命令」不改任何状态：同一份
#   确定性执行序，第一次踩坑，第一百次还踩坑（这正是三级重跑全灭的原因）。
# 因此第三、四级改成**换执行路径**（都不再下载任何东西，每条 1~2 分钟）：
#   第三级：`--no-configuration-cache`（去掉插桩那条路径）
#   第四级：再加 `-Dorg.gradle.vfs.watch=false`（去掉文件系统监听，排除「监听器关闭 FS」这条因）
Write-Host '::warning::zip-FS 竞态第二级未恢复：换执行路径重试（关配置缓存）'
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

# 第三级：只换执行路径，不动缓存（保留 IDE 解包产物，删掉上一次可能半截的产物）
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
Write-Host '第三级重跑：--no-configuration-cache'
& ./gradlew.bat @argList --max-workers=1 --no-configuration-cache 2>&1 | Tee-Object -FilePath $log -Append
$rc3 = $LASTEXITCODE
if ($rc3 -eq 0) { exit 0 }
if (-not (Select-String -LiteralPath $log -Pattern 'ClosedFileSystemException' -SimpleMatch -Quiet)) {
    exit $rc3
}

# 第四级：再关文件系统监听（判「监听器触发 FS 失效」这条因）
Write-Host '::warning::zip-FS 竞态第三级（关配置缓存）未恢复：再关 VFS 监听重试第四级'
& ./gradlew.bat --stop 2>&1 | Out-Null
Write-Host '第四级重跑：--no-configuration-cache -Dorg.gradle.vfs.watch=false'
& ./gradlew.bat @argList --max-workers=1 --no-configuration-cache '-Dorg.gradle.vfs.watch=false' 2>&1 |
    Tee-Object -FilePath $log -Append
exit $LASTEXITCODE
