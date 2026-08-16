<#
.SYNOPSIS
    Runs every automated test in the project.

.DESCRIPTION
    Two suites, because the app has two halves:

      * Kotlin / JUnit  - the detection engine and the wire protocol. This is
        where the rules that matter live: occlusion suppression, consensus,
        replay protection, the pickup grace period.

      * Dart / flutter test - snapshot decoding, so the UI cannot be broken by
        a malformed or partial payload from the native side.

.EXAMPLE
    .\tools\test-all.ps1
    .\tools\test-all.ps1 -Kotlin      # engine tests only
#>
[CmdletBinding()]
param(
    [switch]$Kotlin,
    [switch]$Dart
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'env.ps1')

$runAll = -not ($Kotlin -or $Dart)
$failed = @()

if ($Kotlin -or $runAll) {
    Write-Host ''
    Write-Host '=== Detection engine and protocol (Kotlin / JUnit) ===' -ForegroundColor Cyan
    & (Join-Path $root 'android\gradlew.bat') -p (Join-Path $root 'android') `
        ':app:testDebugUnitTest' --console=plain
    if ($LASTEXITCODE -ne 0) { $failed += 'Kotlin' }

    $report = Join-Path $root 'build\app\reports\tests\testDebugUnitTest\index.html'
    if (Test-Path $report) { Write-Host "Report: $report" }
}

if ($Dart -or $runAll) {
    Write-Host ''
    Write-Host '=== Snapshot decoding (Dart) ===' -ForegroundColor Cyan
    Push-Location $root
    try {
        & flutter test
        if ($LASTEXITCODE -ne 0) { $failed += 'Dart' }
    }
    finally { Pop-Location }

    Write-Host ''
    Write-Host '=== Static analysis ===' -ForegroundColor Cyan
    Push-Location $root
    try {
        & flutter analyze
        if ($LASTEXITCODE -ne 0) { $failed += 'analyze' }
    }
    finally { Pop-Location }
}

Write-Host ''
if ($failed.Count -gt 0) {
    Write-Host ("FAILED: " + ($failed -join ', ')) -ForegroundColor Red
    exit 1
}
Write-Host 'All suites passed.' -ForegroundColor Green
