<#
.SYNOPSIS
    Installs the most recently built APK onto one or all connected phones.

.EXAMPLE
    .\tools\install.ps1                       # every connected device
    .\tools\install.ps1 -Serial R5CT30ABCDE   # just that one
    .\tools\install.ps1 -DebugBuild           # install the debug APK instead
    .\tools\install.ps1 -Launch               # start the app afterwards

.NOTES
    Installing onto several phones at once is the normal way to test this app,
    since it needs at least two to do anything interesting.

    The switch is -DebugBuild rather than -Debug because PowerShell reserves
    -Debug as a common parameter on advanced functions.
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [switch]$DebugBuild,
    [switch]$Launch,
    [switch]$Reinstall
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'env.ps1')

$apkName = if ($DebugBuild) { 'app-debug.apk' } else { 'app-release.apk' }
$apk = Join-Path $root "build\app\outputs\flutter-apk\$apkName"

if (-not (Test-Path $apk)) {
    throw "$apkName not found. Run .\tools\build-apk.ps1$(if ($DebugBuild) { ' -DebugBuild' }) first."
}

# Guard against installing yesterday's build. A -Split build does not refresh
# the universal APK, so it is easy to end up flashing a stale one and chasing
# bugs that were already fixed.
$apkTime = (Get-Item $apk).LastWriteTime
$newestSource = Get-ChildItem -Recurse -File `
    (Join-Path $root 'lib'), (Join-Path $root 'android\app\src\main') `
    -Include *.dart, *.kt, *.xml -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1

if ($newestSource -and $newestSource.LastWriteTime -gt $apkTime) {
    Write-Host ''
    Write-Host 'WARNING: the APK is older than the source.' -ForegroundColor Yellow
    Write-Host ("  $apkName built {0}" -f $apkTime.ToString('yyyy-MM-dd HH:mm:ss'))
    Write-Host ("  {0} changed {1}" -f $newestSource.Name, $newestSource.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))
    Write-Host '  Rebuild with .\tools\build-apk.ps1 to avoid testing stale code.' -ForegroundColor Yellow
    Write-Host ''
}

$targets = @()
if ($Serial) {
    $targets = @($Serial)
}
else {
    $targets = & adb devices |
        Select-Object -Skip 1 |
        Where-Object { $_ -match '\sdevice$' } |
        ForEach-Object { ($_ -split '\s+')[0] }
}

if (-not $targets) {
    throw 'No connected devices. Run .\tools\devices.ps1 for a troubleshooting checklist.'
}

foreach ($target in $targets) {
    $model = (& adb -s $target shell getprop ro.product.model).Trim()
    Write-Host ''
    Write-Host "-> $model ($target)" -ForegroundColor Cyan

    if ($Reinstall) {
        # Wipes settings and the group, which is what you want when testing the
        # first-run flow.
        & adb -s $target uninstall com.beachprotect 2>&1 | Out-Null
    }

    & adb -s $target install -r -d $apk
    if ($LASTEXITCODE -ne 0) { Write-Host "   install failed" -ForegroundColor Red; continue }

    if ($Launch) {
        & adb -s $target shell monkey -p com.beachprotect -c android.intent.category.LAUNCHER 1 | Out-Null
        Write-Host '   launched' -ForegroundColor Green
    }
}
Write-Host ''
