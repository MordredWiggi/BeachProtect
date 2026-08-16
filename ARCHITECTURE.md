# BeachProtect — how it works

This document explains what is implemented and why. **Keep it updated whenever
functionality is added or changed.**

---

## 1. The problem

A group of friends lies on a beach. Their phones and a Bluetooth speaker are on
a towel. Somebody walks off with one of them.

We want an alarm — but only for real thefts. The hard part is not detecting
that a phone moved away. The hard part is *not* screaming every time a stranger
walks between two phones, which on a busy beach happens hundreds of times an
hour.

## 2. The core idea

Two independent signals must agree before anything makes noise:

| Signal | Who measures it | What it proves |
| --- | --- | --- |
| **Radio distance** | Every *other* phone, from Bluetooth signal strength | "You are getting further away from me" |
| **Physical motion** | The phone itself, from its accelerometer | "I am actually being moved" |

A person walking through the group blocks the radio path and drops the signal
by 10–20 dB — but the victim phone is still lying on the towel and says so.
That single cross-check kills the entire class of false alarms.

A phone genuinely being carried away reports motion *and* fades at every other
phone in the group. And because one flaky radio should never start a siren,
**several phones must independently agree** before the alarm goes off.

---

## 3. System shape

```
┌─────────────────────────────────────────────────────────────┐
│  Flutter UI  (lib/)                                         │
│  Renders snapshots. Owns no authoritative state.            │
└───────────────┬─────────────────────────────────────────────┘
                │  MethodChannel  (commands)
                │  EventChannel   (guard snapshots, ~1/s)
┌───────────────┴─────────────────────────────────────────────┐
│  GuardService — Android foreground service (Kotlin)         │
│                                                             │
│   BleAdvertiser ──┐                     ┌── MotionMonitor   │
│   BleScanner ─────┼──▶  ThreatEngine ◀──┤                   │
│   BoxGuard ───────┘         │           └── Simulator       │
│                             ▼                               │
│                    AlarmPlayer / AlarmActivity              │
└─────────────────────────────────────────────────────────────┘
```

**Why the guard is native Kotlin, not Dart.** It has to keep working for hours
with the screen off and the app swiped away. A background Dart isolate would
keep a Flutter engine alive the whole time, and could not reach the APIs that
make this cheap — hardware-offloaded scan filters, sensor FIFO batching, and
the significant-motion wake-up sensor. The Flutter engine is a *window* onto
the service; it can be destroyed at any moment without the guard noticing.

**One source of truth.** All configuration and all guard state live natively
(`GuardStore`, `ThreatEngine`). The UI reads them over the channel and writes
back patches. There is no second copy in Dart that could drift.

---

## 4. The wire protocol

`android/.../ble/Protocol.kt`

Everything is **connectionless BLE advertising**. No pairing, no GATT, no
central node. Every phone broadcasts a 20-byte packet; every phone scans for
it. RSSI comes free with each scan result, so presence sensing and the control
channel share one radio activity.

This scales to any group size, survives any phone leaving, and costs a fraction
of what a mesh of connections would.

### Packet layout (20 bytes)

| Offset | Size | Field | Purpose |
| ---: | ---: | --- | --- |
| 0 | 1 | `version` | |
| 1 | 4 | `groupId` | Truncated hash of the group secret |
| 5 | 2 | `deviceId` | Stable id within the group |
| 7 | 1 | `flags` | armed, alarming, **stationary**, box guardian, low battery, … |
| 8 | 1 | `txPowerRef` | Calibrated RSSI at 1 m, for distance estimates |
| 9 | 1 | `battery` | 0–100 % |
| 10 | 2 | `seq` | Monotonic, persisted — replay protection |
| 12 | 1 | `eventType` | See below |
| 13 | 2 | `subjectId` | Which device the event is about |
| 15 | 1 | `motionScore` | 0–255 recent motion energy |
| 16 | 4 | `mac` | `HMAC-SHA256(groupKey, bytes[0..15])[0..3]` |

### Security

- The **HMAC** means an outsider cannot inject a fake alarm or a fake
  "everybody disarm". Packets that fail verification are dropped before they
  can influence any decision.
- The **monotonic sequence number** means a recorded packet cannot be replayed
  later. Without it, someone could capture the "disarm everyone" broadcast from
  packing-up time and replay it the next afternoon.
- The sequence counter is persisted in **reserved blocks** (4096 at a time)
  rather than on every packet — otherwise it would mean a flash write every
  second for hours. A crash simply burns the remainder of a block; a number is
  never reused.
- The group secret is 80 bits, shown as a 16-character Crockford base-32 code
  (`ABCD-EFGH-JKMN-PQRS` — no I, L, O or U, so it cannot be misread aloud) and
  as a QR code. It never leaves the phones and there is no server.

### Events

`SUSPECT`, `LOST` (observer votes) · `ALARM`, `BOX_ALARM`, `PANIC` ·
`ALARM_CLEAR`, `DISARM_ALL`, `ARM_ALL` · `NAME`

### Names for free

There is no room for a display name in 20 bytes, so names are dripped out two
characters at a time in the event slot that would otherwise be idle — six
packets for a 12-character name, at zero extra radio cost.

For `NAME` packets only, bytes 13–15 carry `chunkIndex, char0, char1` instead
of telemetry. That is safe because a phone only sends name chunks while it is
**stationary and calm**, and because `FLAG_STATIONARY` — which is what the
occlusion gate actually keys on — still travels in the untouched flags byte.

---

## 5. Detection

`android/.../guard/ThreatEngine.kt` — deliberately free of Android imports, so
every rule is exercised by plain JUnit tests with a fake clock.

### Filtering

| Filter | File | Why |
| --- | --- | --- |
| **Kalman** on RSSI | `Filters.kt` | Raw RSSI jitters ±5 dB between two phones lying perfectly still. An average is too laggy to catch a theft; a raw sample is far too noisy to threshold. Tuned so a 20 dB step registers within ~4 samples. |
| **Rolling median** baseline | `Filters.kt` | The reference level must survive exactly the events we are detecting. A mean would let a few seconds of occlusion drag the baseline down and blind the detector. |
| **Least-squares slope** | `Filters.kt` | A passer-by is a symmetric notch — down and straight back up. A theft is a sustained negative slope. |

The baseline only learns while the state is calm **and** both ends report being
still. Learning during an incident would let the detector talk itself out of a
real theft.

### The state machine

```
DISARMED ──arm──▶ CALIBRATING ───8 s───▶ ARMED ◀──────┐
                       │                  │           │ nothing for 12 s
             (2 s if no peers yet)        ▼           │
                                     SUSPICIOUS ──────┘
                                          │
              own motion ▼                ▼ consensus reached
                      PENDING ────────▶ ALARM
                    (grace 3 s)
```

Calibration exists to let per-peer RSSI baselines settle. With nobody else
around there is nothing to settle, so a lone phone leaves it after two seconds
rather than sitting unprotected for the full window.

### Observer rules — deciding to vote about a peer

```
if I am not stationary            → abstain entirely
if the peer is not armed          → ignore it
if silent for lostTimeout (10 s)  → vote LOST
if drop ≥ 11 dB and peer says     → occlusion. Do not vote, and do NOT
   it is stationary                  escalate the radios
if drop ≥ 11 dB and peer moving
   and slope ≤ −0.7 dB/s          → start an episode; vote after 2 s
                                     (1 s if the drop exceeds 20 dB)
```

Three details that matter:

1. **A moving observer abstains.** It cannot tell "you walked away" from "I
   walked away", so it stops voting entirely rather than voting badly.
2. **Occlusion does not escalate the radios.** People walk past constantly;
   reacting to every one would hold the scanner at high duty all afternoon for
   nothing.
3. **The slope test gates the *start* of an episode, not its continuation.** A
   thief who walks twenty metres and then stops produces a flat slope again,
   but the signal never comes back — and that must still count.

### Consensus — proportional, not a fixed count

Votes are shared over the same beacon channel and expire after 8 s. Our own
vote counts as one observer, so the counting is uniform.

The requirement is a **fraction of the other phones**, not a fixed number,
because "enough witnesses" means something different in a group of two than in
a group of ten. One agreeing phone out of two is convincing; one out of nine
probably is not.

```
required = ceil(otherPhones × consensusRatio)      clamped to [minObservers, otherPhones]
```

At the default third:

| Other phones | Must agree |
| ---: | ---: |
| 1 | 1 |
| 2 | 1 |
| 3 | 1 |
| 4 | 2 |
| 6 | 2 |
| 7 | 3 |
| 10 | 4 |

It is always clamped to what the group can actually supply, so a two-phone
group still works. The settings screen states the rule in concrete terms for
the group you currently have, rather than showing a bare percentage.

### Victim-side detection — the fast path

The observers cannot help if the thief is quick, so the phone being taken also
detects itself, and this is the path that carries the reaction-time budget:

| | |
| --- | --- |
| **t ≈ 0.3 s** | The accelerometer notices the lift. |
| **t ≈ 0.5 s** | `PENDING`. The phone chirps loudly, the disarm screen comes up over the lock screen, and the grace countdown starts. |
| **t ≈ 3.5 s** | Grace expires with no disarm → full siren, and `EVENT_ALARM` goes out so every other phone and the speaker join in immediately. |

Supporting rules:

- The pickup detector only arms once the phone has been left alone for
  `settleMs` (8 s), so arming while still holding it cannot trip it.
- **Corroboration cuts the countdown short**: if the others can already see this
  phone receding, waiting out the grace is pointless.
- `alarmOnPickupAlone` (default on) decides what happens when the grace expires
  with no corroboration.

The peer path is inherently slower — RSSI has to fall 11 dB and hold, which at
walking pace takes around five seconds — so in practice the group finds out via
the victim's own broadcast first. The peer path is the backup for when the
victim cannot self-report: powered off, in a bag, or already out of range.

### Handled failure modes

| Situation | Behaviour |
| --- | --- |
| Thief switches the phone off or bags it | It vanishes from the mesh; observers vote `LOST` |
| Victim's battery simply died | The peer broadcast its battery right up to the end — a vanish at ≤ 8 % is a warning, not a theft |
| Two-phone group | The proportional requirement clamps to the single available witness |
| Observer is walking around | Abstains from voting |
| Peer is disarmed | Not guarded |
| Bluetooth switched off mid-session | Warning surfaced; radios restart automatically when it returns |

---

## 6. Energy

The stated requirement was "run all afternoon on a towel". Every design choice
below exists for that.

### Motion

`sensors/MotionMonitor.kt`

The obvious design is to wait on `TYPE_SIGNIFICANT_MOTION`, a hardware wake-up
sensor that costs essentially nothing while idle. **That was the original
implementation, and it was wrong.** That sensor is specified to fire on
*sustained* movement — it exists for step and activity detection — so in
practice it takes five to fifteen seconds of walking before it triggers. The
phone is twenty metres away before it notices.

So the strategy depends on whether the guard is armed:

| State | Strategy | Detection latency |
| --- | --- | --- |
| **Armed** | Accelerometer at 25 Hz, batched through the hardware FIFO with a 250 ms report latency | ~0.3 s |
| **Disarmed** | `TYPE_SIGNIFICANT_MOTION` wake-up sensor, no continuous sampling | irrelevant |

**What the armed mode actually costs.** Much less than it sounds. The sensor hub
does the sampling and delivers batches, so the application processor wakes about
four times a second rather than twenty-five. A batched accelerometer draws on
the order of 0.1–0.5 mA — an order of magnitude below the BLE scanner. The
radio, not the accelerometer, is what drains the battery, and the earlier
"zero sensors while calm" claim was optimising the wrong thing at the cost of
the app's core function.

Devices with no wake-up sensor fall back to a heavily batched accelerometer
even while disarmed. The home screen shows a warning chip when this happens.

### Scanning

`ble/BleScanner.kt` — every scan is:

1. **Filtered in hardware.** A `ScanFilter` on our service UUID is pushed into
   the Bluetooth controller, so the CPU is never woken for the hundreds of
   unrelated advertisements on a busy beach.
2. **At the lowest duty cycle that works.** `SCAN_MODE_LOW_POWER` is roughly a
   512 ms window every 5.12 s. It escalates to `SCAN_MODE_LOW_LATENCY` only
   when the engine has real evidence to chase, and drops straight back.
3. **Restart-throttled.** Android silently blocks an app that starts/stops
   scans more than five times in thirty seconds, and a blocked scanner is a
   guard that does not work. Restarts are coalesced to one per 6 s.

The speaker's BLE address is added as a **second hardware filter** rather than
forcing an unfiltered scan, so tracking it costs essentially nothing.

### Advertising

`ADVERTISE` interval follows the same escalation: ~1 s when calm, ~250 ms under
suspicion, ~100 ms while alarming. Non-connectable, non-scannable legacy PDUs —
the cheapest thing BLE offers.

**Transmit power never changes.** It would be tempting to turn it up when
something looks wrong, but observers detect theft by comparing RSSI against a
learned baseline, so raising TX power mid-incident would lift every reading and
mask exactly the drop we are trying to catch.

### CPU

There is **no permanent wake lock**. While calm, the tick loop is driven by
events that wake the CPU anyway — BLE scan results arrive through the Bluetooth
stack, and significant-motion triggers come from a hardware wake-up sensor. A
wake lock is taken only from `ALERT` upwards, where precise timing matters, and
is capped at 10 minutes.

An **inexact `AlarmManager` heartbeat** (60 s) covers the one case where nothing
would wake us: a two-phone group whose only peer has been switched off, leaving
the air completely silent. Inexact on purpose — an exact alarm would require
`SCHEDULE_EXACT_ALARM`, and this is only a backstop.

### User-visible profiles

| Profile | Calm scan mode | Notes |
| --- | --- | --- |
| Maximum | `BALANCED` (~25 % duty) | Reacts hardest, only setting that noticeably shortens the day |
| **Balanced** (default) | `LOW_POWER` (~10 % duty) | Escalates on evidence |
| Saver | `LOW_POWER` + hardware batching | CPU sleeps longer; costs a second or two of reaction |

---

## 7. Protecting the speaker

`guard/BoxGuard.kt`

A cheap speaker has no app and no sensors, so we infer from the only two things
it emits.

**The audio link.** One phone holds the A2DP connection. Classic Bluetooth
gives up at roughly 10–15 m, and the link also drops the moment the speaker is
switched off — the first thing anyone walking away with it will do. Either way
the guardian phone finds out and alarms the group.

> **Timing caveat:** while music is actually streaming, a link loss is noticed
> within a second or two. While the speaker is merely connected and idle, the
> drop is only detected when the Bluetooth supervision timeout expires, which
> is usually a handful of seconds longer.

**Its BLE beacon.** Many modern speakers also advertise over BLE. Where one
does, its address becomes a second hardware scan filter, giving a graded
distance warning before the audio link fails. Discovery uses the app's only
unfiltered scan — strictly user-initiated, a few seconds long, self-stopping.

A 3 s debounce absorbs the blips that A2DP produces normally, and repeated
flapping is surfaced as a warning rather than an alarm.

---

## 8. The alarm

`alarm/AlarmPlayer.kt`

The siren is **synthesised, not shipped as an asset**: a continuous-phase sweep
between 700 Hz and 1500 Hz with a little third harmonic, which sits where human
hearing is most sensitive and cheap speakers are most efficient, and cuts
through wind and surf far better than a single tone.

### Routing

Android sends `USAGE_ALARM` to the phone's own loudspeaker — right for every
phone, but it will *not* reliably reach a Bluetooth speaker. Audio destined for
the box therefore goes out as `USAGE_MEDIA` with an explicit preferred device
pinned to the A2DP output, the only combination that lands on the box on every
version from Android 8 up.

Both streams run at once by default (`BOX_AND_PHONES`): the speaker alerts the
group back at the towel, while the phone screaming in the thief's hand is what
actually makes them put it down. Falls back to phones automatically when no
speaker is connected.

The player takes exclusive audio focus, raises the alarm and music streams to
maximum, and **restores the previous volumes afterwards**.

### The immediate chirp

Before any of that, the moment a phone is lifted it plays a short two-beep
chirp on its own speaker and raises the disarm screen. This is deliberate: it
gives the owner instant feedback that the guard noticed, and it tells a thief
straight away that the phone is protected — which is most of the deterrent, and
costs nothing when it turns out to be the owner. The group siren is still a
grace period away.

### Disarming

`ui/AlarmActivity.kt` is a plain Android activity, not a Flutter route: it has
to light up over the lock screen in a few hundred milliseconds, even when the
Flutter engine has long since been torn down.

**Getting onto a locked screen.** A bare `startActivity` from a service is
blocked in the background on Android 10 and up, so that alone is not enough —
on a locked phone nothing appears at all. The supported route is a
**full-screen-intent notification** on a high-importance alarm channel, and one
is posted for the `PENDING` grace period as well as for the alarm itself. The
direct activity launch is still attempted alongside it, for the cases where it
is permitted and is faster.

On Android 14+ `USE_FULL_SCREEN_INTENT` is withheld from apps that are not
phone or clock apps until the user grants it explicitly. Without it the prompt
degrades to a heads-up notification, so the home screen carries a prominent
warning with a one-tap link to the right settings page, and
**Settings ▸ Testing ▸ Test the lock screen alarm** raises the real surface
after a six-second delay so the behaviour can be verified directly.

Three modes, chosen per device:

- **Fingerprint with PIN backup** (default) — `BiometricPrompt` with
  `BIOMETRIC_WEAK`, falling back to the group PIN keypad.
- **Group PIN only** — works on any phone, and lets any member of the group
  disarm any phone.
- **Single tap** — convenient, but anyone holding the phone can silence it.

PINs are stored salted and iterated (20 000 × SHA-256), never in the clear.
The back button is deliberately inert; disarming is the only way out.

Both routes to the screen are used on purpose: a direct `startActivity` works
when the app may launch from the background, and a full-screen-intent
notification covers the case where it may not. On Android 14+ the user must
grant `USE_FULL_SCREEN_INTENT` explicitly; without it the alarm degrades to a
heads-up notification and the siren still plays.

---

## 9. Testing

### Automated — `tools\test-all.ps1`

**49 Kotlin/JUnit tests** covering the rules that matter. Every one corresponds
to a real situation:

- *Suppression*: person walking between phones; sustained drop from a stationary
  peer; a moving observer abstaining.
- *Detection*: peer carried away; thief who walks off then stops; single
  observer insufficient with three phones; second observer completing consensus.
- *Disappearance*: armed peer vanishing; low battery treated as a warning;
  disarmed peers not guarded.
- *Victim side*: pickup → grace → alarm; **a lift detected by the accelerometer
  alone starts the countdown** (regression test, see below); a phone still in
  the owner's hand never trips it; picking it up again before it has settled;
  disarm cancelling; corroboration cutting the grace short.
- *Reaction time*: a lifted phone alarms inside the budget; **a shortened grace
  period really does shorten the wait**.
- *Consensus*: the requirement scales with group size; one witness suffices with
  three phones but not with five; a second witness completes it.
- *Group control*: relayed alarms; **replayed control packets rejected**;
  sequence wraparound.
- *Energy*: calm guard stays on the low-power profile; passers-by do not
  escalate the radios; suspicion escalates then relaxes.
- *Settings*: every tunable round-trips; partial patches leave the rest alone;
  any numeric type is accepted; nonsense values are clamped rather than obeyed.
- *Protocol*: round-trips, tampering, foreign groups, wrong keys, truncation,
  group-code encoding including O/0 confusion, name reassembly.

Plus **8 Dart tests** on snapshot decoding and the consensus mirror — malformed
payloads, NaN values, unknown enum names from a future native build, and that
the UI's copy of the consensus rule agrees with Kotlin's.

### In-app simulator — Settings ▸ Testing

Feeds synthetic beacons into **exactly the same engine entry points** the real
radio uses; nothing is stubbed. Ten scenarios, each declaring whether it must
alarm or must stay silent, with a pass/fail verdict and the **measured time to
detection**:

`CALM_GROUP` · `PASSER_BY` · `THEFT_WALK` · `THEFT_RUN` · `POCKETED` ·
`THEFT_CONSENSUS` · `VANISH` · `VANISH_LOW_BATTERY` · `SELF_PICKUP` ·
`BOX_TAKEN`

This is what makes the app fully testable **on a single phone**.

**Rehearsals, not real incidents.** A test that sets off the actual siren is
worse than useless: silencing it disarms the phone, which invalidates every
scenario after it, and the alarm would be broadcast to everyone else's phone as
well. So while a scenario is running:

- the alarm plays a short confirmation blip instead of the siren;
- no alarm event is broadcast to the group;
- no full-screen alarm surface is raised;
- the guard stands itself back up after ~1.4 s so the run can continue;
- the phone's real armed state is restored when the run ends.

Scenarios also **stop the moment their verdict is decided** rather than running
out the clock, so the whole suite takes a couple of minutes rather than ten.

### On real hardware — `tools\field-test.ps1`

Guided multi-phone protocol: installs on every connected device, starts
synchronised log capture, walks you through eight scenarios, then summarises
what each phone independently decided. Steps needing more phones than are
connected are skipped automatically.

---

## 10. File map

| Path | What lives there |
| --- | --- |
| `lib/core/` | Theme, models, platform-channel client, `GuardController` |
| `lib/widgets/` | Shield button, peer/box cards, shared chrome |
| `lib/screens/` | Onboarding, home, group, settings, box setup, simulator, QR scan |
| `android/.../ble/` | `Protocol`, `BleAdvertiser`, `BleScanner`, `BeaconComposer`, `BleDiscovery` |
| `android/.../guard/` | `ThreatEngine`, `Filters`, `Models`, `BoxGuard` |
| `android/.../sensors/` | `MotionMonitor` |
| `android/.../alarm/` | `AlarmPlayer` |
| `android/.../service/` | `GuardService`, `GuardIntents`, `BootReceiver` |
| `android/.../ui/` | `AlarmActivity` |
| `android/.../sim/` | `Simulator` |
| `android/.../store/` | `GuardStore` |
| `android/.../bridge/` | `GuardBridge`, `Codec` |
| `android/app/src/test/` | JUnit tests |
| `tools/` | Build, install, log, test and field-test scripts |

---

## 11. Known limits

- **Both phones must have BeachProtect installed and be in the same group.**
  There is no way to guard a phone that is not running the app.
- **BLE peripheral mode is required** to be *watched* by others. Almost every
  phone since Android 5 supports it, but a handful of budget chipsets do not;
  the app detects this and warns, and such a phone can still watch others.
- **Distance is approximate.** RSSI-to-distance is good to about a factor of
  two in the open and worse behind a body or a cool box. The UI therefore shows
  coarse buckets, never metres, and the detector works on *changes* rather than
  absolute distance.
- **An idle speaker is noticed more slowly** than one that is playing (§7).
- **Aggressive OEM battery managers** (Xiaomi, Huawei, Samsung, OnePlus) may
  still kill a foreground service. Granting the battery-optimisation exemption
  during onboarding is what prevents this; some OEMs need an extra "allow
  background activity" toggle in their own settings app.
- **Android 14+ full-screen intents** need explicit user permission; without it
  the disarm screen becomes a heads-up notification.

---

## 12. Changelog

| Version | Change |
| --- | --- |
| 1.0.0 | Initial implementation: connectionless BLE mesh, RSSI + accelerometer fusion, multi-observer consensus, peer-loss detection, speaker guarding via A2DP link loss and BLE beacon, native alarm surface with three disarm modes, adaptive radio/sensor energy management, in-app simulator, 43 automated tests. |
| 1.1.0 | Field-test fixes. **(a) Reaction time**: the accelerometer pickup path was dead code — the handler cleared the "lying still since" marker before the readiness test that reads it, leaving the slow `SIGNIFICANT_MOTION` sensor as the only trigger and taking ~30 s. Fixed, and the armed guard now runs a batched accelerometer instead of waiting on that sensor; detection is ~0.3 s and the default grace period dropped from 10 s to 3 s, with an immediate warning chirp on lift. **(b) Consensus** changed from a fixed observer count to a proportion of the group (default one third), so three phones no longer require two witnesses. **(c) Test scenarios** now run as rehearsals — no real siren, no group broadcast, armed state restored — finish as soon as the verdict is known, and report measured detection times. Several scenarios previously could never pass, because two simulated peers implied a two-witness requirement that only one voter could ever meet. **(d) Lock screen**: a full-screen-intent notification is now posted for the grace period, not only for the alarm, so the disarm prompt appears on a locked phone; the manifest used the non-public `showOnLockScreen` instead of `showWhenLocked`; the home screen warns when Android 14+ withholds the permission, and a lock-screen self-test was added. Also: detector settings extracted into a pure, unit-tested codec with clamping; sliders commit on release rather than on every pixel; `install.ps1` warns when the APK is older than the source. |
