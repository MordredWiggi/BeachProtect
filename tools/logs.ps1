<#
.SYNOPSIS
    Tails the guard's logs from a connected phone.

.EXAMPLE
    .\tools\logs.ps1                        # BeachProtect logs only
    .\tools\logs.ps1 -All                   # everything from the app process
    .\tools\logs.ps1 -Serial R5CT30ABCDE

.NOTES
    The native tags are BpGuardService, BpScanner, BpAdvertiser, BpMotion,
    BpBoxGuard, BpAlarmPlayer and BpDiscovery. State transitions and alarms are
    logged at info/warn, so this is the fastest way to see what the detector
    decided and why.
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [switch]$All,
    [switch]$Clear
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')

$deviceArgs = @()
if ($Serial) { $deviceArgs = @('-s', $Serial) }

if ($Clear) { & adb @deviceArgs logcat -c }

Write-Host 'Tailing logs. Ctrl+C to stop.' -ForegroundColor Cyan
Write-Host ''

if ($All) {
    # Not $pid: that is a read-only automatic variable in PowerShell.
    $appPid = (& adb @deviceArgs shell pidof com.beachprotect).Trim()
    if (-not $appPid) { throw 'BeachProtect is not running on that device.' }
    & adb @deviceArgs logcat --pid=$appPid
}
else {
    & adb @deviceArgs logcat -s `
        BpGuardService:V BpScanner:V BpAdvertiser:V BpMotion:V `
        BpBoxGuard:V BpAlarmPlayer:V BpDiscovery:V BpBootReceiver:V `
        flutter:V AndroidRuntime:E
}
