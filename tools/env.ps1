# Sets up the BeachProtect build environment for the current PowerShell session.
#
# The persistent user-level variables were already configured during setup, so
# in a fresh terminal you normally do not need this. Dot-source it when a shell
# was opened before that happened, or when something looks misconfigured:
#
#     . .\tools\env.ps1
#
# Everything lives on D: on purpose: the C: drive on this machine was down to
# zero free bytes, and Android builds need several gigabytes of cache.

$ErrorActionPreference = 'Stop'

$FlutterRoot = 'D:\flutter'
$AndroidSdk  = 'D:\AppData\Local\Programs\AndroidSDK'
$JavaHome    = 'D:\Program Files\Android\AndroidStudio\jbr'

$env:GRADLE_USER_HOME = 'D:\gradle-home'
$env:PUB_CACHE        = 'D:\pub-cache'
$env:JAVA_HOME        = $JavaHome
$env:ANDROID_HOME     = $AndroidSdk
$env:ANDROID_SDK_ROOT = $AndroidSdk

$extra = @(
    "$FlutterRoot\bin",
    "$AndroidSdk\platform-tools",
    "$AndroidSdk\cmdline-tools\latest\bin",
    "$JavaHome\bin"
)

foreach ($dir in $extra) {
    if ((Test-Path $dir) -and ($env:Path -notlike "*$dir*")) {
        $env:Path = "$dir;$env:Path"
    }
}

Write-Host 'BeachProtect environment ready.' -ForegroundColor Green
Write-Host ("  flutter : " + (Get-Command flutter -EA SilentlyContinue).Source)
Write-Host ("  adb     : " + (Get-Command adb -EA SilentlyContinue).Source)
Write-Host ("  java    : " + $env:JAVA_HOME)
Write-Host ("  gradle  : " + $env:GRADLE_USER_HOME)
Write-Host ("  pub     : " + $env:PUB_CACHE)
