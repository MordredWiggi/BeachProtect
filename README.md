# BeachProtect

Group phone and speaker theft protection for the beach.

Every phone in the group keeps a quiet Bluetooth eye on every other phone. If
one of them is carried off, the speaker and all the phones sound off at once.
No internet, no accounts, no server — just Bluetooth between the phones.

---

## The idea in one picture

```
        Lisa                 Ben                  Jan
         📱  ←── RSSI ──→     📱  ←── RSSI ──→     📱
          ╲                    │                   ╱
           ╲                   │                  ╱
            ╰──────── 🔊 speaker (A2DP) ─────────╯

  Someone lifts Jan's phone and walks away:

  1. Jan's phone feels the movement           (accelerometer)
  2. Lisa's and Ben's phones see it fading    (signal strength)
  3. Both agree, independently                (consensus)
  4. Speaker + every phone scream             (alarm)
```

The interesting part is what does **not** trigger it. A stranger walking
between two phones drops the radio signal just as hard as a theft does — but
the phone on the towel keeps reporting "I am lying perfectly still", and that
single cross-check removes the entire class of false alarms.

## What it does

- **Watches every phone in the group** over connectionless Bluetooth LE — no
  pairing, no connections, scales to any group size.
- **Fuses radio distance with the phone's own accelerometer**, so passers-by,
  cool boxes and beach umbrellas do not set it off.
- **Requires several phones to agree** before making noise.
- **Catches a phone that vanishes** — switched off or dropped in a bag — while
  giving the benefit of the doubt to one whose battery was simply dying.
- **Guards the Bluetooth speaker** by watching its audio link and, where the
  speaker supports it, its BLE beacon.
- **Alarms on the speaker and the phones at once** — the speaker alerts the
  group, the phone screaming in the thief's hand makes them drop it.
- **Disarms with a fingerprint or a shared group PIN**, on a full-screen prompt
  that works over the lock screen.
- **Runs all afternoon** for roughly **2–4 % of the battery over six hours** on
  the default setting — hardware-filtered low-duty scanning, no permanent wake
  lock. See [What it costs the battery](#what-it-costs-the-battery).
- **Stops when you close it.** The guard runs while the app does — backgrounded,
  screen off, in a pocket — and shuts down completely, notification and all,
  when you swipe the app away.

## Getting started

| I want to… | Read |
| --- | --- |
| Build it and get it onto my phone | **[SETUP.md](SETUP.md)** |
| Understand how it actually works | **[ARCHITECTURE.md](ARCHITECTURE.md)** |

Quick version, from the repository root in PowerShell:

```powershell
.\tools\build-apk.ps1          # build the release APK
.\tools\devices.ps1            # check the phone is connected
.\tools\install.ps1 -Launch    # install and start it
```

## Using it on the beach

1. **One person creates a group.** Everyone else scans the QR or types the
   16-character code. On a new phone this comes straight after the one-off
   setup — your name, and the permissions Android needs — and it is where the
   app waits any time it has no group.
2. **Agree on a group PIN** (Settings ▸ Disarming). Anyone who knows it can
   silence any phone's alarm, which is what you want when someone forgets to
   disarm.
3. **Lay everything out**, then tap the shield — or **Arm all** to arm the whole
   group at once.
4. Wait a few seconds for "Guarding". The app is learning how strong each
   phone's signal is from where it now lies.
5. **Going for a swim?** Just leave everything. Lifting your own phone makes it
   chirp immediately and show a disarm prompt — you have a few seconds to
   fingerprint or PIN it before the group alarm joins in.
6. **If the group alarm goes off**, the screen that comes up offers two
   different things, and the first one is usually what you want:
   - **Stop the alarm** — silences *every* phone in the group and leaves them
     all guarding. This is the answer to a false alarm.
   - **Stop and disarm everyone** — silences everybody and stands the whole
     group down.

   Both reach the whole group, from whichever phone you happen to be holding —
   including one that has already gone quiet.
7. **Packing up?** **Disarm all.**

## What it costs the battery

Short version: **on the default Balanced setting, guarding an afternoon costs
about as much battery as two minutes of looking at the screen.** The radio is
cheap; the display is not.

### The three settings

Settings ▸ Power. The only thing that really changes is how much of the time the
Bluetooth receiver is listening — which is also what decides how quickly the
group notices anything, so this is a genuine trade rather than a free lunch.

| Setting | Listening | Modelled extra draw | Realistic drain | Over a 6-hour afternoon |
| --- | ---: | ---: | ---: | ---: |
| Maximum | 100 % | ~27 mA | **1–2 %/h** | 6–11 % |
| **Balanced** (default) | 25 % | ~9 mA | **0.3–0.7 %/h** | 2–4 % |
| Saver | 10 % | ~5 mA | **0.15–0.4 %/h** | 1–2.5 % |

For scale, on the same phone doing nothing else:

| For comparison | Drain |
| --- | ---: |
| Screen on, app open | 10–20 %/h |
| Phone idle in a pocket, screen off, nothing running | 0.5–1.5 %/h |
| A siren actually going off | ~0.15 %/minute |

So a five-minute alarm costs less than 1 %, and **leaving the screen on for
three minutes costs more than an hour of guarding**. Lock the phone and put it
down; that is the setup the whole design assumes.

### Where those numbers come from

Nothing here is a guess dressed up as a measurement. The modelled column is
arithmetic on two published sets of figures, and you can redo it for your own
phone.

**What the radio does.** Android's scan modes are fixed duty cycles (AOSP
`ScanManager`):

| Scan mode | Window | Interval | Duty |
| --- | ---: | ---: | ---: |
| `SCAN_MODE_LOW_POWER` (Saver) | 512 ms | 5120 ms | 10 % |
| `SCAN_MODE_BALANCED` (Balanced) | 1024 ms | 4096 ms | 25 % |
| `SCAN_MODE_LOW_LATENCY` (Maximum) | 4096 ms | 4096 ms | 100 % |

**What that costs.** From a typical Android `power_profile.xml`: Bluetooth
controller receiving ≈ 25 mA, idle ≈ 2 mA, transmitting ≈ 30 mA. Balanced is
therefore `0.25 × 25 + 0.75 × 2 ≈ 7.8 mA` of receiver.

Everything else BeachProtect does is small enough to be a rounding error next to
that:

| Part | Draw | Why so little |
| --- | ---: | --- |
| Scanning (Balanced) | ~7.8 mA | The whole cost, essentially |
| Advertising | ~0.04 mA | 3 channels × ~0.4 ms once a second is a 0.1 % transmit duty cycle |
| Accelerometer | ~0.15 mA | 25 Hz, but batched in the sensor hub, so the processor wakes 4 times a second rather than 25 |
| App CPU and wake-ups | ~1.5 mA | One tick a second, and no wake lock at all while calm |

That totals ~9.4 mA, or 0.21 %/h of a 4500 mAh battery — 1.3 % over six hours.

**Why the "realistic" column is higher.** Measured drain generally lands at
1.5–3× the modelled figure, for reasons the power model does not capture: the
application processor cannot reach its deepest sleep states while a scan is
running, OEM Bluetooth stacks differ considerably, and a busy beach means more
advertisements getting past the hardware filter and waking the CPU. The range
given above is the model multiplied out to that band, which is the honest way to
state it.

**Measuring it yourself**, which is the only number that really counts:

```powershell
adb shell dumpsys batterystats --reset
# ...guard for an hour with the screen off...
adb shell dumpsys batterystats | Select-String "com.beachprotect" -Context 0,12
```

Android's own **Settings ▸ Battery ▸ Battery usage** works too, and needs no
cable.

### What the settings actually change

Battery is only half of what you are choosing. The listening duty cycle also
sets how long a gap between two beacons is normal, and therefore how fast the
group reacts:

| | Maximum | Balanced | Saver |
| --- | --- | --- | --- |
| Group list updates | continuously | ~1 s | every few seconds |
| "Arm all" reaches everyone in | ~1 s | 1–3 s | 3–10 s |
| A phone switched off is noticed after | ~13 s | ~13 s | ~15–25 s |

A phone being *carried away* is caught in a few seconds on all three, because
the first hint of trouble puts every radio on maximum regardless of the setting.
The saver profile costs you the calm-state responsiveness, not the detector.

## Testing it

With one phone: turn on **Settings ▸ Testing ▸ Test scenarios** and run the
ten scripted situations — including the ones that must stay silent. They feed
synthetic peers through the real detector and report how long detection took.
Test runs are rehearsals: a short beep instead of the siren, nothing broadcast
to anyone else, and your phone left armed exactly as it was.

With more phones:

```powershell
.\tools\field-test.ps1 -Install -Wireless
```

A guided eight-step field protocol with synchronised log capture from every
device.

Automated suites — 94 Kotlin tests on the detection engine, the wire protocol
and the beacon composer, plus 14 Dart tests on snapshot decoding:

```powershell
.\tools\test-all.ps1
```

## Requirements

- Android 8.0 (API 26) or newer
- Bluetooth LE, and BLE peripheral mode to be *watched* by others (nearly all
  phones since Android 5; the app warns if yours cannot)
- An accelerometer — a hardware significant-motion sensor is strongly preferred
  and is what makes the battery cost negligible

## Honest limitations

- Every phone must have the app installed and be in the same group.
- The app has to stay open. Backgrounded is fine; swiped out of Recents stops
  the guard, and it does not come back by itself after a reboot.
- Distance is approximate; the UI shows coarse buckets rather than fake metres.
- An idle speaker takes a few seconds longer to notice than one playing music.
- Aggressive OEM battery managers can still kill background services — grant
  the battery exemption during onboarding.

See [ARCHITECTURE.md §11](ARCHITECTURE.md#11-known-limits) for the full list.
