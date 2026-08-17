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
- **Runs all afternoon.** No continuous sensors while calm, hardware-filtered
  low-duty scanning, no permanent wake lock.

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
   16-character code.
2. **Agree on a group PIN.** Anyone who knows it can silence any phone's alarm,
   which is what you want when someone forgets to disarm.
3. **Lay everything out**, then tap the shield — or **Arm all** to arm the whole
   group at once.
4. Wait a few seconds for "Guarding". The app is learning how strong each
   phone's signal is from where it now lies.
5. **Going for a swim?** Just leave everything. Lifting your own phone makes it
   chirp immediately and show a disarm prompt — you have a few seconds to
   fingerprint or PIN it before the group alarm joins in.
6. **Packing up?** **Disarm all.**

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

Automated suites — 58 Kotlin tests on the detection engine, 9 Dart tests on
snapshot decoding:

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
- Distance is approximate; the UI shows coarse buckets rather than fake metres.
- An idle speaker takes a few seconds longer to notice than one playing music.
- Aggressive OEM battery managers can still kill background services — grant
  the battery exemption during onboarding.

See [ARCHITECTURE.md §11](ARCHITECTURE.md#11-known-limits) for the full list.
