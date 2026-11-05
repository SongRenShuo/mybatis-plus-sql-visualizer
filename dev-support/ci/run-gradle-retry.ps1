# CI 用的 Gradle 调用包装：命中「zip 文件系统竞态」时停 daemon 后重跑一次。
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
$log = Join-Path $env:RUNNER_TEMP 'gradle-run.log'

Write-Host "run-gradle-retry: ./gradlew.bat $GradleArgs"
& ./gradlew.bat @argList 2>&1 | Tee-Object -FilePath $log
$rc = $LASTEXITCODE
if ($rc -eq 0) { exit 0 }

if (-not (Select-String -LiteralPath $log -Pattern 'ClosedFileSystemException' -SimpleMatch -Quiet)) {
    exit $rc
}

Write-Host '::warning::命中 Gradle zip 文件系统竞态（ClosedFileSystemException，非代码缺陷）：停 daemon 后重跑一次'
& ./gradlew.bat --stop 2>&1 | Out-Null
& ./gradlew.bat @argList 2>&1 | Tee-Object -FilePath $log -Append
exit $LASTEXITCODE
