<#
.SYNOPSIS
    Builds a BeachProtect APK and reports where it landed.

.EXAMPLE
    .\tools\build-apk.ps1                 # release build
    .\tools\build-apk.ps1 -DebugBuild     # debug build, faster, larger
    .\tools\build-apk.ps1 -Split          # one APK per CPU architecture
    .\tools\build-apk.ps1 -Install        # build, then install on the phone

.NOTES
    The switch is -DebugBuild rather than -Debug because PowerShell reserves
    -Debug as a common parameter on advanced functions.
#>
[CmdletBinding()]
param(
    [switch]$DebugBuild,
    [switch]$Split,
    [switch]$Install,
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'env.ps1')

Push-Location $root
try {
    if ($Clean) {
        Write-Host 'Cleaning...' -ForegroundColor Cyan
        flutter clean | Out-Null
    }

    $mode = if ($DebugBuild) { '--debug' } else { '--release' }
    # Not $args: that is an automatic variable and is unavailable here.
    $buildArgs = @('build', 'apk', $mode)
    if ($Split) { $buildArgs += '--split-per-abi' }

    Write-Host "Building $mode ..." -ForegroundColor Cyan
    & flutter @buildArgs
    if ($LASTEXITCODE -ne 0) { throw "flutter build failed with exit code $LASTEXITCODE" }

    $outDir = Join-Path $root 'build\app\outputs\flutter-apk'
    $apks = Get-ChildItem $outDir -Filter '*.apk' -EA SilentlyContinue |
            Where-Object { $_.Name -notlike '*.sha1' } |
            Sort-Object LastWriteTime -Descending

    Write-Host ''
    Write-Host 'Built:' -ForegroundColor Green
    foreach ($apk in $apks) {
        '{0,-40} {1,7:N1} MB   {2}' -f $apk.Name, ($apk.Length / 1MB), $apk.LastWriteTime.ToString('HH:mm:ss')
    }
    Write-Host ''
    Write-Host "Folder: $outDir"

    if ($Install) {
        & (Join-Path $PSScriptRoot 'install.ps1') -DebugBuild:$DebugBuild
    }
}
finally {
    Pop-Location
}
