<#
.SYNOPSIS
    Runs a guided, multi-phone field test and collects the logs from every device.

.DESCRIPTION
    The unit tests prove the detector's *rules* are right. This proves the
    radios, sensors and alarm routing behave on real hardware, in a real place,
    with real bodies walking around - which is the part no test suite can cover.

    The script drives the whole thing: it installs the APK on every connected
    phone, starts a synchronised log capture on each, walks you through the
    scenarios one at a time, and then summarises what each phone actually
    decided so you can see whether they agreed.

    Steps that need more phones than you have plugged in are skipped
    automatically, so this is useful with two devices and better with three.

.EXAMPLE
    .\tools\field-test.ps1 -Install
    .\tools\field-test.ps1 -Steps 3,4,5      # just re-run a few

.NOTES
    Unplug the phones before the walking steps - obviously. Plug them back in
    at the end and the script will collect the logs it started.
    Use -Wireless to keep adb attached over Wi-Fi while you walk around.
#>
[CmdletBinding()]
param(
    [switch]$Install,
    [switch]$Wireless,
    [int[]]$Steps,
    [string]$OutDir
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'env.ps1')

# ---------------------------------------------------------------------------
# Devices
# ---------------------------------------------------------------------------

function Get-Devices {
    & adb devices |
        Select-Object -Skip 1 |
        Where-Object { $_ -match '\sdevice$' } |
        ForEach-Object { ($_ -split '\s+')[0] }
}

$serials = @(Get-Devices)
if ($serials.Count -eq 0) {
    throw 'No devices connected. Run .\tools\devices.ps1 for a checklist.'
}

$phones = foreach ($s in $serials) {
    [pscustomobject]@{
        Serial = $s
        Model  = (& adb -s $s shell getprop ro.product.model).Trim()
        Log    = $null
        Proc   = $null
    }
}

Write-Host ''
Write-Host "Phones in this test ($($phones.Count)):" -ForegroundColor Cyan
foreach ($p in $phones) { "  {0,-22} {1}" -f $p.Serial, $p.Model }

if ($phones.Count -lt 2) {
    Write-Host ''
    Write-Host 'Only one phone connected. Peer detection needs at least two;' -ForegroundColor Yellow
    Write-Host 'use the in-app Test scenarios screen for single-phone testing.' -ForegroundColor Yellow
}

# ---------------------------------------------------------------------------
# Install
# ---------------------------------------------------------------------------

if ($Install) {
    Write-Host ''
    Write-Host 'Installing on every phone...' -ForegroundColor Cyan
    & (Join-Path $PSScriptRoot 'install.ps1')
}

if ($Wireless) {
    Write-Host ''
    Write-Host 'Switching every phone to adb over Wi-Fi so you can unplug them...' -ForegroundColor Cyan
    foreach ($p in $phones) {
        $ip = (& adb -s $p.Serial shell ip -f inet addr show wlan0 |
               Select-String -Pattern 'inet (\d+\.\d+\.\d+\.\d+)' |
               ForEach-Object { $_.Matches[0].Groups[1].Value }) | Select-Object -First 1
        if ($ip) {
            & adb -s $p.Serial tcpip 5555 | Out-Null
            Start-Sleep -Milliseconds 1500
            & adb connect "${ip}:5555" | Out-Null
            Write-Host "  $($p.Model) -> ${ip}:5555" -ForegroundColor Green
        }
        else {
            Write-Host "  $($p.Model): no Wi-Fi address, staying on USB" -ForegroundColor Yellow
        }
    }
    $serials = @(Get-Devices)
}

# ---------------------------------------------------------------------------
# Log capture
# ---------------------------------------------------------------------------

if (-not $OutDir) {
    $OutDir = Join-Path $root ("test-logs\" + (Get-Date -Format 'yyyy-MM-dd_HH-mm-ss'))
}
New-Item -ItemType Directory -Force $OutDir | Out-Null

Write-Host ''
Write-Host "Logs -> $OutDir" -ForegroundColor Cyan

foreach ($p in $phones) {
    & adb -s $p.Serial logcat -c 2>&1 | Out-Null
    $safe = ($p.Model -replace '[^\w\-]', '_') + '_' + $p.Serial
    $p.Log = Join-Path $OutDir "$safe.log"
    $p.Proc = Start-Process -FilePath 'adb' `
        -ArgumentList @('-s', $p.Serial, 'logcat', '-v', 'time', '-s',
            'BpGuardService:V', 'BpScanner:V', 'BpAdvertiser:V', 'BpMotion:V',
            'BpBoxGuard:V', 'BpAlarmPlayer:V', 'flutter:V') `
        -RedirectStandardOutput $p.Log `
        -NoNewWindow -PassThru
}

# ---------------------------------------------------------------------------
# The protocol
# ---------------------------------------------------------------------------

$protocol = @(
    @{
        N = 1
        Need = 2
        Title = 'Baseline'
        Body = @'
Put every phone on the towel, a metre or two apart, and arm them all.
Wait until each one says "Guarding" rather than "Getting my bearings".
Then leave them completely alone for one minute.

EXPECT: nothing happens. No alarms, no suspicion. This is the boring
        case that has to be rock solid, because it is 99% of the day.
'@
    },
    @{
        N = 2
        Need = 2
        Title = 'People walking past (the false alarm that matters)'
        Body = @'
Leave every phone where it is. Now walk back and forth between them,
close enough to block the line of sight, about ten times over a minute.
Get someone else to help if you can - more bodies is a better test.

EXPECT: still no alarm. The signal will dip hard, but every phone is
        reporting "stationary", so the detector must ignore it.
        Watch the home screen: peers may briefly show a "-N dB" chip,
        but must never show "Moving away".
'@
    },
    @{
        N = 3
        Need = 2
        Title = 'Phone carried away (the real thing)'
        Body = @'
Pick up ONE phone and walk away at a normal pace, in a straight line.
Do not run. Keep walking until it alarms, and note roughly how far you
got. If you have a PIN set, you will need it to stop the noise.

EXPECT: the carried phone shows the grace countdown almost immediately.
        Within a few seconds all phones and the speaker sound off.
        Typical trigger distance is 10-20 m at walking pace.
'@
    },
    @{
        N = 4
        Need = 2
        Title = 'Owner picks up their own phone'
        Body = @'
Re-arm everything. Wait at least 30 seconds for the phones to settle.
Now pick up one phone as its owner would, and disarm it within the
grace period using the fingerprint or PIN.

EXPECT: countdown appears, disarm works, no siren. This is the flow
        every user hits several times a day, so it has to feel quick.
'@
    },
    @{
        N = 5
        Need = 2
        Title = 'Phone switched off'
        Body = @'
Re-arm everything. Now switch one phone completely off (or turn its
Bluetooth off, which is the same thing as far as the group can tell).

EXPECT: after the "vanished" timeout - 12 seconds by default - the
        remaining phones alarm with "Phone vanished from the group".
'@
    },
    @{
        N = 6
        Need = 3
        Title = 'Consensus with three or more phones'
        Body = @'
With three or more phones armed, carry one away as in step 3.

EXPECT: the alarm only fires once TWO phones agree. On the home screen
        of a watching phone you should briefly see "Moving away (1/2
        agree)" before it escalates. This is the step that proves a
        single flaky radio cannot start a siren on its own.
'@
    },
    @{
        N = 7
        Need = 1
        Title = 'Speaker taken'
        Body = @'
Set a speaker up in Settings > Speaker on whichever phone is playing
music, and arm the group. Now carry the speaker away, or switch it off.

EXPECT: all phones alarm with "Speaker being taken". Note that while
        music is actually playing the drop is noticed within a second
        or two; if the speaker is merely connected and idle it can take
        a few seconds longer for the Bluetooth link to time out.
'@
    },
    @{
        N = 8
        Need = 1
        Title = 'Battery over a real session'
        Body = @'
Charge every phone to 100%, arm the group, note the time, and leave
them alone on the towel for as long as you can stand - ideally a few
hours. Do not touch them.

EXPECT: single-digit percent per hour on Balanced. If it is much worse,
        check that the phone found a significant-motion sensor: the
        home screen shows a warning chip when it did not, and that
        forces the expensive continuous-accelerometer fallback.
'@
    }
)

$selected = if ($Steps) { $protocol | Where-Object { $Steps -contains $_.N } } else { $protocol }

foreach ($step in $selected) {
    if ($phones.Count -lt $step.Need) {
        Write-Host ''
        Write-Host "--- Step $($step.N): $($step.Title)" -ForegroundColor DarkGray
        Write-Host "    Skipped: needs $($step.Need) phones, $($phones.Count) connected." -ForegroundColor DarkGray
        continue
    }

    Write-Host ''
    Write-Host ('=' * 72) -ForegroundColor DarkCyan
    Write-Host "STEP $($step.N)  -  $($step.Title)" -ForegroundColor Cyan
    Write-Host ('=' * 72) -ForegroundColor DarkCyan
    Write-Host $step.Body
    Write-Host ''

    foreach ($p in $phones) {
        & adb -s $p.Serial shell log -t BpFieldTest "STEP $($step.N) START: $($step.Title)" 2>&1 | Out-Null
    }

    Read-Host 'Press Enter when this step is finished (or type s + Enter to skip)' | Out-Null

    foreach ($p in $phones) {
        & adb -s $p.Serial shell log -t BpFieldTest "STEP $($step.N) END" 2>&1 | Out-Null
    }
}

# ---------------------------------------------------------------------------
# Collect and summarise
# ---------------------------------------------------------------------------

Write-Host ''
Write-Host 'Stopping log capture...' -ForegroundColor Cyan
foreach ($p in $phones) {
    if ($p.Proc -and -not $p.Proc.HasExited) {
        Stop-Process -Id $p.Proc.Id -Force -EA SilentlyContinue
    }
}
Start-Sleep -Milliseconds 800

Write-Host ''
Write-Host ('=' * 72) -ForegroundColor DarkCyan
Write-Host 'SUMMARY' -ForegroundColor Cyan
Write-Host ('=' * 72) -ForegroundColor DarkCyan

foreach ($p in $phones) {
    Write-Host ''
    Write-Host "$($p.Model)  ($($p.Serial))" -ForegroundColor White
    if (-not (Test-Path $p.Log)) { Write-Host '  no log captured' -ForegroundColor Yellow; continue }

    $content = Get-Content $p.Log -EA SilentlyContinue
    $alarms = $content | Select-String -Pattern 'ALARM (\w+) subject='
    $states = $content | Select-String -Pattern 'state (\w+) -> (\w+)'

    "  log lines        : {0}" -f $content.Count
    "  alarms raised    : {0}" -f $alarms.Count
    foreach ($a in $alarms) { "      $($a.Line.Trim())" }

    $counts = @{}
    foreach ($s in $states) {
        $to = $s.Matches[0].Groups[2].Value
        $counts[$to] = ($counts[$to] | ForEach-Object { $_ }) + 1
    }
    if ($counts.Count -gt 0) {
        "  state changes    : " + (($counts.GetEnumerator() |
            Sort-Object Name |
            ForEach-Object { "$($_.Key)=$($_.Value)" }) -join '  ')
    }
}

Write-Host ''
Write-Host "Full logs: $OutDir" -ForegroundColor Green
Write-Host ''
Write-Host 'Reading them: every alarm decision is logged by BpGuardService as' -ForegroundColor DarkGray
Write-Host '"ALARM <reason> subject=<deviceId>", and every state change as' -ForegroundColor DarkGray
Write-Host '"state <from> -> <to>". Compare the timestamps across phones to see' -ForegroundColor DarkGray
Write-Host 'how quickly the group agreed.' -ForegroundColor DarkGray
