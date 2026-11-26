<#
.SYNOPSIS
    Diagnose and repair the Gradle artifact-transform cache problems that kill
    `runIde` / `:test` with "Unable to delete directory ...\transforms\<hash>".

.DESCRIPTION
    Background (docs/LESSONS.md L106 / L109):

    The IntelliJ Platform distribution is unpacked into
    ~/.gradle/caches/<ver>/transforms/<hash>/transformed/ideaIU-*. Two failures
    hit exactly that directory:

      1. Foreign files land inside it. A sandbox IDE JVM crash writes
         hs_err_pid*.log / replay_pid*.log into its working directory, which IS
         that unpacked platform home. Gradle validates the transform output, sees
         it modified, and re-runs the transform -- which means deleting the dir.
      2. The delete fails, because the user's IDE, its Kotlin compile daemon, or
         JPS javac workers orphaned by the crashed sandbox still hold the jars.

    Default run is read-only: lists platform transform dirs, flags contamination
    (crash logs inside) and incompleteness (too few lib jars), names lock holders
    through the Windows Restart Manager, and lists orphan JPS javac workers.

    -Fix kills orphan JPS javac workers and renames contaminated transform dirs
    aside ("<hash>.locked-<stamp>"). Windows allows renaming a directory whose
    files are open, so this works while the IDE is running; Gradle re-extracts
    the platform into the original path on the next build.

.PARAMETER Fix
    Kill orphan JPS javac workers and rename contaminated transform dirs aside.

.PARAMETER GradleUserHome
    Gradle user home to inspect. Defaults to $env:GRADLE_USER_HOME, else ~\.gradle.

.PARAMETER MinLibJars
    Below this many jars under the transform's lib dir the extraction is treated
    as incomplete. Default 40.

.EXAMPLE
    pwsh -File dev-support/gradle-transform-doctor.ps1

.EXAMPLE
    pwsh -File dev-support/gradle-transform-doctor.ps1 -Fix
#>
param(
    [switch]$Fix,
    [string]$GradleUserHome = "",
    [int]$MinLibJars = 40
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------- Restart Manager
$rmSig = @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

public static class MpvRm {
    [StructLayout(LayoutKind.Sequential)]
    public struct RM_UNIQUE_PROCESS { public int dwProcessId; public System.Runtime.InteropServices.ComTypes.FILETIME ProcessStartTime; }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct RM_PROCESS_INFO {
        public RM_UNIQUE_PROCESS Process;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string strAppName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)] public string strServiceShortName;
        public int ApplicationType; public uint AppStatus; public uint TSSessionId; [MarshalAs(UnmanagedType.Bool)] public bool bRestartable;
    }
    [DllImport("rstrtmgr.dll", CharSet = CharSet.Unicode)] static extern int RmStartSession(out uint pSessionHandle, int dwSessionFlags, string strSessionKey);
    [DllImport("rstrtmgr.dll")] static extern int RmEndSession(uint pSessionHandle);
    [DllImport("rstrtmgr.dll", CharSet = CharSet.Unicode)] static extern int RmRegisterResources(uint pSessionHandle, uint nFiles, string[] rgsFilenames, uint nApplications, [In] RM_UNIQUE_PROCESS[] rgApplications, uint nServices, string[] rgsServiceNames);
    [DllImport("rstrtmgr.dll")] static extern int RmGetList(uint dwSessionHandle, out uint pnProcInfoNeeded, ref uint pnProcInfo, [In, Out] RM_PROCESS_INFO[] rgAffectedApps, ref uint lpdwRebootReasons);

    // Returns "PID=<id> <app>" lines; empty when nobody holds the file.
    public static List<string> Who(string path) {
        var res = new List<string>();
        uint handle; string key = Guid.NewGuid().ToString();
        if (RmStartSession(out handle, 0, key) != 0) return res;
        try {
            if (RmRegisterResources(handle, 1, new string[] { path }, 0, null, 0, null) != 0) return res;
            uint needed = 0, count = 0, reasons = 0;
            int rc = RmGetList(handle, out needed, ref count, null, ref reasons);
            if (rc == 0 || rc != 234) return res;
            count = needed;
            var arr = new RM_PROCESS_INFO[count];
            if (RmGetList(handle, out needed, ref count, arr, ref reasons) != 0) return res;
            for (uint i = 0; i < count; i++) res.Add("PID=" + arr[i].Process.dwProcessId + "  " + arr[i].strAppName);
            return res;
        } finally { RmEndSession(handle); }
    }
}
'@
Add-Type -TypeDefinition $rmSig -Language CSharp

function Get-LockHolders([string]$path) {
    if (-not (Test-Path -LiteralPath $path)) { return @() }
    return @([MpvRm]::Who($path))
}

# ---------------------------------------------------------------- inputs
if ([string]::IsNullOrWhiteSpace($GradleUserHome)) {
    $GradleUserHome = $env:GRADLE_USER_HOME
    if ([string]::IsNullOrWhiteSpace($GradleUserHome)) {
        $GradleUserHome = Join-Path $HOME '.gradle'
    }
}
$GradleUserHome = (Resolve-Path -LiteralPath $GradleUserHome).Path
Write-Host "gradle user home : $GradleUserHome"

$transformsRoots = @()
if (Test-Path (Join-Path $GradleUserHome 'caches')) {
    foreach ($v in Get-ChildItem -LiteralPath (Join-Path $GradleUserHome 'caches') -Directory) {
        $t = Join-Path $v.FullName 'transforms'
        if (Test-Path $t) { $transformsRoots += $t }
    }
}
if ($transformsRoots.Count -eq 0) {
    Write-Host "no transforms cache found - nothing to inspect"
    exit 0
}

# ---------------------------------------------------------------- platform dirs
$targets = @()
foreach ($root in $transformsRoots) {
    foreach ($hashDir in Get-ChildItem -LiteralPath $root -Directory) {
        $unpacked = Join-Path $hashDir.FullName 'transformed'
        if (-not (Test-Path $unpacked)) { continue }
        foreach ($ide in Get-ChildItem -LiteralPath $unpacked -Directory -Filter 'ideaIU-*') {
            $targets += [pscustomobject]@{
                Transform = $hashDir.FullName
                TransformName = $hashDir.Name
                IdeDir = $ide.FullName
            }
        }
    }
}

if ($targets.Count -eq 0) {
    Write-Host "no unpacked IntelliJ platform transform found under $($transformsRoots -join ', ')"
}

$fixActions = @()

foreach ($t in $targets) {
    Write-Host ""
    Write-Host "=== $($t.TransformName)"
    Write-Host "    $($t.IdeDir)"

    $libDir = Join-Path $t.IdeDir 'lib'
    $libJars = @()
    if (Test-Path $libDir) {
        $libJars = @(Get-ChildItem -LiteralPath $libDir -Recurse -File -Filter '*.jar' -ErrorAction SilentlyContinue)
    }
    $fileCount = @(Get-ChildItem -LiteralPath $t.IdeDir -Recurse -File -ErrorAction SilentlyContinue).Count
    Write-Host "    files=$fileCount  libJars=$($libJars.Count)"

    $contamination = @(Get-ChildItem -LiteralPath $t.IdeDir -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like 'hs_err_pid*.log' -or $_.Name -like 'replay_pid*.log' })
    if ($contamination.Count -gt 0) {
        Write-Host "    CONTAMINATED - JVM crash logs inside the transform output:" -ForegroundColor Yellow
        foreach ($c in $contamination) {
            Write-Host "      $($c.Name)  ($([math]::Round($c.Length / 1MB, 1)) MB, $($c.LastWriteTime))"
        }
    }

    $incomplete = $libJars.Count -lt $MinLibJars
    if ($incomplete) {
        Write-Host "    INCOMPLETE - only $($libJars.Count) lib jars (threshold $MinLibJars)" -ForegroundColor Yellow
    }

    # lock probe on a few representative jars
    $probes = @('lib\app.jar', 'lib\util.jar', 'lib\annotations.jar', 'lib\idea_rt.jar') |
        ForEach-Object { Join-Path $t.IdeDir $_ } | Where-Object { Test-Path -LiteralPath $_ }
    $holders = @()
    foreach ($p in $probes) {
        foreach ($h in (Get-LockHolders $p)) {
            if ($holders -notcontains $h) { $holders += $h }
        }
    }
    if ($holders.Count -gt 0) {
        Write-Host "    LOCKED by:"
        foreach ($h in $holders) { Write-Host "      $h" }
    } else {
        Write-Host "    not locked (no handle on app/util/annotations/idea_rt)"
    }

    $needsRename = $contamination.Count -gt 0 -or $incomplete
    if ($needsRename) { $t | Add-Member -NotePropertyName NeedsRename -NotePropertyValue $true -Force }
}

# ---------------------------------------------------------------- orphan compilers
Write-Host ""
Write-Host "=== orphan JPS javac workers (crashed/closed sandbox leftovers)"
$today = (Get-Date).Date
$alive = @{}
foreach ($p in (Get-CimInstance Win32_Process -ErrorAction SilentlyContinue)) { $alive[[int]$p.ProcessId] = $true }
$orphans = @()
foreach ($p in (Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction SilentlyContinue)) {
    $cmd = $p.CommandLine
    if (-not $cmd) { continue }
    if ($cmd -notmatch '-Djps\.java\.compiling\.tool=') { continue }
    if ($cmd -notmatch '[\\/]transforms[\\/]') { continue }
    # Orphan = the JVM that spawned this external javac worker is gone, or the worker
    # predates today. Parent-dead is the precise test: a running IDE keeps its workers.
    $parentDead = -not $alive.ContainsKey([int]$p.ParentProcessId)
    $stale = ($p.CreationDate -and $p.CreationDate -lt $today)
    if (-not ($parentDead -or $stale)) { continue }
    $why = if ($parentDead) { "parent $($p.ParentProcessId) is gone" } else { "started before today" }
    $orphans += [pscustomobject]@{ Pid = $p.ProcessId; Started = $p.CreationDate; Why = $why }
}
if ($orphans.Count -eq 0) {
    Write-Host "    none (every JPS javac worker under transforms has a live parent and started today)"
} else {
    foreach ($o in $orphans) {
        Write-Host "    PID=$($o.Pid)  started=$($o.Started)  ($($o.Why))"
    }
}

# ---------------------------------------------------------------- fix
if (-not $Fix) {
    Write-Host ""
    Write-Host "read-only run. Re-run with -Fix to kill the orphan workers and rename contaminated dirs aside."
    exit 0
}

Write-Host ""
Write-Host "=== applying fixes"
foreach ($o in $orphans) {
    try {
        Stop-Process -Id $o.Pid -Force -ErrorAction Stop
        Write-Host "    killed orphan javac worker PID=$($o.Pid)"
    } catch {
        Write-Host "    could not kill PID=$($o.Pid): $($_.Exception.Message)" -ForegroundColor Yellow
    }
}

# Reap dirs parked by earlier runs first, so repeated fixes cannot pile up 3 GB copies.
foreach ($root in $transformsRoots) {
    foreach ($stale in (Get-ChildItem -LiteralPath $root -Directory -Filter '*.locked-*' -ErrorAction SilentlyContinue)) {
        $mb = [math]::Round((Get-ChildItem -LiteralPath $stale.FullName -Recurse -File -ErrorAction SilentlyContinue |
            Measure-Object -Property Length -Sum).Sum / 1MB, 1)
        try {
            Remove-Item -LiteralPath $stale.FullName -Recurse -Force -ErrorAction Stop
            Write-Host "    deleted stale parked dir $($stale.Name) ($mb MB)"
        } catch {
            Write-Host "    kept parked dir $($stale.Name) ($mb MB) - still referenced by a live process; delete it later" -ForegroundColor Yellow
        }
    }
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
foreach ($t in $targets) {
    if (-not $t.PSObject.Properties['NeedsRename']) { continue }
    # The parked-dir sweep above may already have removed this one - nothing left to rename.
    if (-not (Test-Path -LiteralPath $t.Transform)) { continue }
    $dst = "$($t.Transform).locked-$stamp"
    try {
        Move-Item -LiteralPath $t.Transform -Destination $dst -ErrorAction Stop
        Write-Host "    renamed $($t.TransformName) -> $([System.IO.Path]::GetFileName($dst))"
        Write-Host "      Gradle re-extracts the platform at the original path on the next build;"
        Write-Host "      delete the .locked-* directory later once no IDE holds it."
    } catch {
        Write-Host "    could not rename $($t.TransformName): $($_.Exception.Message)" -ForegroundColor Yellow
        Write-Host "      close the IDE / kill its Kotlin daemon and retry."
    }
}

Write-Host ""
Write-Host "done."
