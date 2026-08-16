<#
.SYNOPSIS
    Creates a release signing key so you can build APKs that are not signed
    with the throwaway debug key.

.DESCRIPTION
    Until you run this, `build-apk.ps1` signs release builds with Android's
    debug key. That is fine for testing on your own phones, but two things are
    worth knowing:

      * Everyone's debug key is different, so a debug-signed APK built on
        another machine cannot upgrade one built here - Android refuses the
        install with INSTALL_FAILED_UPDATE_INCOMPATIBLE.
      * You cannot publish a debug-signed APK anywhere.

    The generated keystore and its passwords stay on this machine. Back up
    D:\Dokumente\GitHub\BeachProtect\android\beachprotect-release.jks
    somewhere safe: lose it and you can never update an installed copy of the
    app without uninstalling it first.

.EXAMPLE
    .\tools\make-keystore.ps1
#>
[CmdletBinding()]
param(
    [string]$Alias = 'beachprotect',
    [int]$ValidityDays = 10950
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'env.ps1')

$keystore = Join-Path $root 'android\beachprotect-release.jks'
$propsFile = Join-Path $root 'android\key.properties'

if (Test-Path $keystore) {
    Write-Host "A keystore already exists at:" -ForegroundColor Yellow
    Write-Host "  $keystore"
    Write-Host ''
    Write-Host 'Delete it by hand if you really want a new one - but any phone' -ForegroundColor Yellow
    Write-Host 'with the old build installed will then need an uninstall first.' -ForegroundColor Yellow
    return
}

$secure = Read-Host 'Choose a keystore password (at least 6 characters)' -AsSecureString
$plain = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))

if ($plain.Length -lt 6) { throw 'Password must be at least 6 characters.' }

$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
if (-not (Test-Path $keytool)) { throw "keytool not found at $keytool" }

& $keytool -genkeypair `
    -keystore $keystore `
    -alias $Alias `
    -keyalg RSA -keysize 4096 `
    -validity $ValidityDays `
    -storepass $plain -keypass $plain `
    -dname "CN=BeachProtect, OU=Dev, O=BeachProtect, C=DE"

if ($LASTEXITCODE -ne 0) { throw 'keytool failed.' }

@"
storePassword=$plain
keyPassword=$plain
keyAlias=$Alias
storeFile=$keystore
"@ | Set-Content -Path $propsFile -Encoding utf8

Write-Host ''
Write-Host 'Release signing configured.' -ForegroundColor Green
Write-Host "  keystore : $keystore"
Write-Host "  config   : $propsFile"
Write-Host ''
Write-Host 'Both files are already in .gitignore. Back up the .jks somewhere safe.' -ForegroundColor Yellow
