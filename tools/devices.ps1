<#
.SYNOPSIS
    Lists the Android devices adb can see, with a readable model name.

.DESCRIPTION
    Useful before install.ps1 / logs.ps1, because those take a -Serial when you
    have more than one phone plugged in - which is exactly the situation once
    you start testing BeachProtect properly.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')

$lines = & adb devices | Select-Object -Skip 1 | Where-Object { $_.Trim() -ne '' }

if (-not $lines) {
    Write-Host ''
    Write-Host 'No devices found.' -ForegroundColor Yellow
    Write-Host @'

Checklist:
  1. USB cable must carry data, not just power. Cheap charging cables will not work.
  2. On the phone: Settings > About phone > tap "Build number" seven times.
  3. Then: Settings > System > Developer options > USB debugging = on.
  4. Replug, and accept the "Allow USB debugging?" prompt on the phone.
     Tick "Always allow from this computer".
  5. If the device shows as "unauthorized", revoke USB debugging authorisations
     in Developer options and replug.
'@
    return
}

Write-Host ''
foreach ($line in $lines) {
    $parts = $line -split '\s+'
    $serial = $parts[0]
    $state = $parts[1]

    if ($state -eq 'device') {
        $model = (& adb -s $serial shell getprop ro.product.model).Trim()
        $release = (& adb -s $serial shell getprop ro.build.version.release).Trim()
        $sdk = (& adb -s $serial shell getprop ro.build.version.sdk).Trim()
        '{0,-22} {1,-26} Android {2} (API {3})' -f $serial, $model, $release, $sdk
    }
    else {
        '{0,-22} {1}' -f $serial, $state.ToUpper()
    }
}
Write-Host ''
