# build.ps1 — build the RadioShed Windows MSI installer.
$ErrorActionPreference = 'Stop'

# Repo root = the folder this script lives in, so it works from any checkout
# location (C:, Z:, a cloned path on another machine, …) with no editing.
$RepoRoot = $PSScriptRoot
Set-Location -LiteralPath $RepoRoot

# Relocate Gradle's caches onto C: -- this is what avoids the broken lock on the Z: share
$env:GRADLE_USER_HOME = 'C:\gradle-home'

# Stop any half-stuck daemon, then build the MSI with the project cache on C:
.\gradlew.bat --stop
.\gradlew.bat :composeApp:packageMsi --project-cache-dir C:\gradle-cache\radioshed

# gradlew is a native command, so $ErrorActionPreference won't catch a failed build -- check manually
if ($LASTEXITCODE -ne 0) {
    Write-Host "Build FAILED (exit $LASTEXITCODE)." -ForegroundColor Red
    exit $LASTEXITCODE
}

# Report the installer that was ACTUALLY produced. Globbing the newest *.msi
# (instead of hardcoding a version) keeps this in lockstep with the version in
# version.properties -- jpackage names the file RadioShed-<version>.msi, so a
# hardcoded path silently goes stale on every version bump.
$msiDir = Join-Path $RepoRoot 'composeApp\build\compose\binaries\main\msi'
$msi = Get-ChildItem -LiteralPath $msiDir -Filter '*.msi' -ErrorAction SilentlyContinue |
       Sort-Object LastWriteTime -Descending | Select-Object -First 1

if ($msi) {
    Write-Host "Build succeeded. Installer:" -ForegroundColor Green
    Write-Host "  $($msi.FullName)"
} else {
    Write-Host "Build finished, but no .msi was found in $msiDir." -ForegroundColor Yellow
}

Read-Host "`nPress Enter to close"   # drop this line if you run it from an existing terminal
