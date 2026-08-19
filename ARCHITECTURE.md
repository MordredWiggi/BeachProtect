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
with the screen off, the phone in a pocket and the UI long since discarded to
save memory. A background Dart isolate would keep a Flutter engine alive the
whole time, and could not reach the APIs that make this cheap —
hardware-offloaded scan filters, sensor FIFO batching, and the
significant-motion wake-up sensor. The Flutter engine is a *window* onto the
service; it can be destroyed at any moment without the guard noticing.

**How long it lives.** For exactly as long as the app does — see §6b. The
guard is a foreground service, so the screen going off, the app going to the
background, and Android reclaiming the Flutter engine all leave it running.
Swiping the app out of Recents stops it.

**One source of truth.** All configuration and all guard state live natively
(`GuardStore`, `ThreatEngine`). The UI reads them over the channel and writes
back patches. There is no second copy in Dart that could drift.

That extends to *how* the guard's state changes. Plenty of things arm and
disarm a phone without the UI being involved at all — "arm all" from somebody
else's phone, "disarm all", the Disarm action on the notification, the
lock-screen alarm surface — so the persisted armed flag follows every state
transition the engine makes, rather than being written by whichever command
handler happened to run. Setting it only in the handlers meant a phone armed by
the group came back **disarmed** after a restart, having spent the afternoon
guarding.

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

Bytes 13–15 are **reinterpreted for two event types**, because twenty bytes is
what a legacy advertisement can carry and there is nothing else to give:

| Event | 13–14 | 15 |
| --- | --- | --- |
| `NAME` | `chunkIndex`, `char0` | `char1` |
| `ARM_ALL` · `DISARM_ALL` · `ALARM_CLEAR` | issuing device | `commandCounter` |

Both are safe for the same reason: `FLAG_STATIONARY`, which is what the
occlusion gate actually keys on, still travels untouched in the flags byte, and
a receiver keeps the motion score it already had rather than reading one from a
packet that is not carrying one.

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
- It is **never reset**, not even when the group changes, and each receiver
  follows it on **every** beacon rather than only on control ones. Both of
  those are corrections. This device's id is derived from the group secret and
  the install id, so leaving a group and rejoining it comes back as the *same*
  device — with a counter that used to restart at zero, which every phone that
  remembered the old number then read as a replay. And a reference that only
  moved when a command happened to arrive could sit thousands of packets behind
  the sender: blocks are burned four thousand at a time, and once the gap
  passes half the 16-bit range the wraparound comparison reads genuinely newer
  as older and throws the command away.
- The group secret is 80 bits, shown as a 16-character Crockford base-32 code
  (`ABCD-EFGH-JKMN-PQRS` — no I, L, O or U, so it cannot be misread aloud) and
  as a QR code. It never leaves the phones and there is no server.

### Events

`SUSPECT`, `LOST` (observer votes) · `ALARM`, `BOX_ALARM`, `PANIC` ·
`ALARM_CLEAR`, `DISARM_ALL`, `ARM_ALL` · `NAME`

### Group commands are repeated, relayed, and remembered

`ARM_ALL`, `DISARM_ALL` and `ALARM_CLEAR` are one-shot decisions travelling
over a channel with no acknowledgement, to phones that are listening a fraction
of the time. A short announcement can fall **entirely between two scan
windows** — which is exactly what happened: "arm all" reached the other phone
about half the time, and there was no way to tell the difference between "it did
not arrive" and "it did not work".

Five things carry that load together, because no one of them is sufficient:

- **A command is identified, not merely typed.** Every press carries a
  `commandCounter` from the phone that issued it, and origin and counter travel
  unchanged through every relay. Without that, "arm all, disarm all, arm all"
  is three copies of two indistinguishable messages, and there is no way to be
  right about both halves of the problem — the field test walked into each of
  them in turn. Obey every copy, and a relay still circulating half a minute
  later quietly stands down a phone somebody has just armed. Remember the
  *type* for a while instead, and the third press is ignored by every phone that
  heard the first — which is exactly the sequence anybody testing the app
  performs in its first minute. With a counter, a stale copy is simply older and
  is dropped, and every genuine press is newer and is obeyed. The counter is
  persisted, because a phone that came back from a restart at zero would have its
  next hundred commands read as stale.

- **The issuer repeats itself for 30 s.** Twelve seconds was the previous answer
  and it was still one or two chances on the saver profile.
- **The issuer lifts its own advertising** out of the calm profile for the
  duration, because the listener's duty cycle is the one thing the sender cannot
  change. A profile change requested while the advertising stack was still
  bringing a set up used to be *dropped on the floor* — so the announcement went
  out at the calm one-per-second rate, which is most of the other half of the
  missing commands. Requests are now queued and applied when the stack returns,
  and the radio health check verifies the rate as well as the fact of
  advertising.
- **Every phone passes a command on** the first time it sees it, for 10 s. This
  is what makes a three-phone group work when the far phone is in range of
  nobody but the middle one: one burst becomes an epidemic that reaches everyone
  in range of *anyone*.
- **A copy that is not newer is neither obeyed nor passed on.** That one rule is
  what makes the flood terminate: each phone repeats each press at most once.

Votes are interleaved rather than starved, so a suspicion in flight when
somebody presses a button still gets aired every other packet.

**An alarm is not a group command, and must not be sent like one.** It rides on
the guard's live state in every beacon (`BeaconComposer.chooseEvent`) and leaves
the air the instant the alarm does. Queuing it as a repeating message *as well* —
which is what happened, through the same path a group command takes, with
nothing ever cancelling the queue — meant a phone went on broadcasting "theft!"
for the whole repeat window after its siren had been silenced. Every other phone
heard an incident that no longer existed: the group-alarm banner came back by
itself long after everybody had stood down, and once the echo window lapsed a
phone could be pulled into a fresh, real siren by a packet describing something
that had ended half a minute earlier. Lengthening the repeat window for the
commands' sake made it worse in exact proportion.

A command is never *assumed* to have landed. The count under **Group** is the
only honest answer, and pressing the button again is safe: a phone that already
applied the command ignores the repeat, and a phone that never heard it applies
it.

### Names for free

There is no room for a display name in 20 bytes, so names are dripped out two
characters at a time in the event slot that would otherwise be idle — six
packets for a 12-character name, at zero extra radio cost.

For `NAME` packets only, bytes 13–15 carry `chunkIndex, char0, char1` instead
of telemetry. That is safe because `FLAG_STATIONARY` — which is what the
occlusion gate actually keys on — still travels in the untouched flags byte,
and because an **armed** phone only spends the slot while it is lying still.

Two rules make that actually deliver a name, rather than merely make one
possible:

- **A disarmed phone introduces itself whatever it is doing.** Nobody is
  guarding it, so nothing is reading its motion score and the slot is free.
  This matters more than it sounds: people are *holding* their phones while
  they set a group up, and requiring stillness meant neither phone said a word
  about who it was during the one minute both of them were listening hardest.
  Everybody put their phone down on the towel already anonymous.
- **Meeting somebody new is worth 25 s of fast radio.** Six different packets
  have to be caught before a name can be read; at the calm duty cycle that is
  one packet every few seconds out of a rotation of six, so a full name takes
  well over a minute of both phones lying perfectly still. Both ends escalate
  simultaneously — each is new to the other — and the name arrives in a couple
  of seconds.
- **A phone that stayed anonymous gets another try**, 12 s every 75 s or so,
  for as long as it is still being heard. The window used to be a single roll of
  the dice anchored to when the peer was first heard, so a phone that spent those
  twenty-five seconds in somebody's pocket was "Phone A31F" for the rest of the
  afternoon.
- **A name, once assembled, is kept.** It is persisted against the device id, so
  a peer that goes quiet for five minutes or a service that restarts does not
  drop the group list back to hexadecimal — which from the outside is
  indistinguishable from names never having worked at all. The names are
  discarded whenever the group changes, because device ids are derived from the
  group secret.
- **Calibration counts as idle.** Name chunks used to be suppressed for the eight
  seconds after arming, which is squarely the window in which two phones that
  have just met are both listening hardest. Nothing is being voted on yet, so the
  slot is genuinely free.

A name packet also implies its sender is lying still, so the receiver now reads
the missing motion score as **zero** rather than keeping whatever the peer last
reported while moving. Keeping the old value made a stationary phone look like a
moving one to the occlusion gate — the one gate that stops a passer-by starting
a siren.

---

## 5. Detection

`android/.../guard/ThreatEngine.kt` — deliberately free of Android imports, so
every rule is exercised by plain JUnit tests with a fake clock.

### Filtering

| Filter | File | Why |
| --- | --- | --- |
| **Kalman** on RSSI | `Filters.kt` | Raw RSSI jitters ±5 dB between two phones lying perfectly still. An average is too laggy to catch a theft; a raw sample is far too noisy to threshold. Process noise is scaled by the **time since the last sample**, so the lag stays around a second whether results arrive every 200 ms or every 5 s. |
| **Rolling median** baseline | `Filters.kt` | The reference level must survive exactly the events we are detecting. A mean would let a few seconds of occlusion drag the baseline down and blind the detector. |
| **Least-squares slope** | `Filters.kt` | A passer-by is a symmetric notch — down and straight back up. A theft is a sustained negative slope. Bounded to the last 5 s. |

The baseline only learns while the state is calm **and** both ends report being
still. Learning during an incident would let the detector talk itself out of a
real theft.

**Why the filter is told how old its last sample is.** It used to add a fixed
amount of process noise per update, which quietly assumed a constant sample
rate. The scanner does nothing of the sort: roughly one result every five
seconds while calm, several a second once it escalates. At the slow rate that
filter lagged a receding phone by three to four *seconds*, and every one of
those seconds went straight onto the detection time, because a lagging estimate
reaches the drop threshold late. Scaling the noise by elapsed time makes the
filter open up in proportion to how long it has been flying blind. The same
reasoning applies to the slope window: bounded by sample count it was 4 s long
when the radio was busy and nearly 30 s when it was idle, so "the signal is
still falling" meant two different things depending on the duty cycle.

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
if I am not stationary             → abstain entirely
if the peer is not armed           → ignore it
if silent for longer than normal   → escalate the radios; vote LOST only if
   (see below)                        the silence survives another 3 s

if drop ≥ 11 dB and the peer says  → occlusion. Do not vote, and do NOT
   it is lying still                  escalate the radios

if drop ≥ 6 dB and peer moving     → start an episode, escalate the radios
   and slope ≤ −0.7 dB/s
   in an episode, drop ≥ 11 dB     → vote, once the episode is 2 s old
                                      (1 s if the drop exceeds 20 dB)
   in an episode, drop < 4.5 dB    → forget the episode; signal came back
```

Five details that matter:

0. **"Silent for longer than normal" is not a constant**, and treating it as one
   was the single noisiest bug in the field test. How long a gap between two
   beacons is ordinary depends entirely on the scan duty cycle the user chose:
   at the sparse profile a twelve second gap is completely routine. A flat ten
   second rule therefore had an observer voting `LOST` about a phone lying on the
   same towel — and in a two-phone group one vote *is* the consensus, so it
   alarmed on the spot, in the same tick, with no chance for the peer to
   reappear. The threshold is now the larger of `lostTimeout` and two and a half
   times the worst gap that peer has actually been producing, and the vote is
   only cast if the silence survives a further three seconds — during which
   noticing it has already pushed the scanner to low latency, so a peer that was
   merely missed is back within a second. The same threshold is what the UI uses
   to decide a peer is unheard, so the two can no longer disagree.
1. **A moving observer abstains.** It cannot tell "you walked away" from "I
   walked away", so it stops voting entirely rather than voting badly.
2. **Occlusion does not escalate the radios.** People walk past constantly;
   reacting to every one would hold the scanner at high duty all afternoon for
   nothing.
3. **The slope test gates the *start* of an episode, not its continuation.** A
   thief who walks twenty metres and then stops produces a flat slope again,
   but the signal never comes back — and that must still count.
4. **The episode starts well before the vote does.** The confirmation window
   used to begin only once the signal had already fallen the full 11 dB, so the
   two costs were paid one after the other: seconds of fading, then seconds of
   confirming. Starting the episode at 6 dB runs the confirmation *alongside*
   the fade. Nothing is voted on that would not have been before — the vote
   still needs the whole drop — and for a step change, which is what occlusion
   looks like, both clocks still start on the very same sample. It is worth
   two to three seconds on a phone being walked away with.

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
  `settleMs` (8 s), so arming while still holding it cannot trip it. **This is
  surfaced in the UI** — the home screen shows "Pickup protection active" or a
  countdown, because a detector that is silently not armed yet is
  indistinguishable from one that is broken.
- **Readiness is latched for the duration of a movement.** See below; this is
  the difference between a detector that works and one that does not.
- A lift that never produces a decisive sample still counts once the phone has
  been **moving continuously for 3 s** — a phone slid off a towel into a bag
  rather than snatched. The 3 s floor is comfortably longer than the motion
  monitor's own settling delay, so a knock to the towel cannot reach it.
- **Corroboration cuts the countdown short**: if the others can already see this
  phone receding, waiting out the grace is pointless.
- `alarmOnPickupAlone` (default on) decides what happens when the grace expires
  with no corroboration.

> **Why readiness is latched.** Readiness is derived from "how long has this
> phone been lying still", and the very first sample that reports movement has
> to clear that marker. So a lift that *began* gently — below
> `motionScoreThreshold`, which is a careful hand or a slow slide — disarmed the
> detector permanently: every stronger sample that followed found a phone that
> had not been lying still, and did nothing at all. The phone could then be
> carried away in complete silence, and the only thing the owner ever saw was
> the app asking them to put it down so it could start guarding again. Whether
> the detector was ready is now decided **once**, when the movement starts, and
> held until the phone comes to rest.

The peer path is inherently slower — RSSI has to fall 11 dB and hold — so in
practice the group finds out via the victim's own broadcast first. The peer path
is the backup for when the victim cannot self-report: powered off, in a bag, or
already out of range.

### Measured reaction times

From the in-app simulator, which feeds the engine through its real entry
points. "Incident" is the moment the phone starts moving.

| Scenario | Before | Now |
| --- | ---: | ---: |
| This phone picked up | ~3.5 s | ~3.5 s |
| Phone carried away, 1.3 m/s | 8.8 s | ~4 s |
| Phone grabbed and run with | ~5.5 s | ~3 s |
| Pocketed, then the thief stands still | ~8 s | ~4 s |
| Speaker unplugged | ~3.5 s | ~3.5 s |
| Phone switched off | ~10.5 s | ~13.5 s |

Two thirds of that came from the detector (a filter that no longer lags by
three seconds, and a confirmation window that overlaps the fade), and one third
from the scenario itself: it used to fade **linearly**, at a rate picked to
look plausible on a graph. Real path loss is logarithmic — the first two metres
cost more dB than the next ten — so a linear ramp spends several seconds in a
shallow slope that never happens in the field. The walk-away scenarios now play
the log-distance curve for a real walking pace, which is both a harder test in
the first second and an honest one thereafter.

A phone that is switched off got **three seconds slower**, and that is a price
paid on purpose. It is bounded by `lostTimeout` (10 s) plus the confirmation
window, and waiting is the whole point of the case: a peer is only "gone" if it
has missed several scan windows. The three seconds bought the end of spurious
`PEER_LOST` sirens, which were costing far more than three seconds of anyone's
attention.

### Handled failure modes

| Situation | Behaviour |
| --- | --- |
| Thief switches the phone off or bags it | It vanishes from the mesh; observers vote `LOST` |
| Victim's battery simply died | The peer broadcast its battery right up to the end — a vanish at ≤ 8 % is a warning, not a theft |
| Two-phone group | The proportional requirement clamps to the single available witness |
| Observer is walking around | Abstains from voting |
| Peer is disarmed | Not guarded |
| Bluetooth switched off mid-session | Warning surfaced; radios restart automatically when it returns |
| A phone misses a group command | It is repeated for 30 s at a lifted advertising rate, and every phone that hears it passes it on; the group list says who is actually guarding, so the command is never *assumed* to have landed |
| A phone misses the "everybody stop" | Only the deciding phone keeps the alarm on the air, and every phone that has already been silenced refuses that specific incident for as long as it can still hear it — so the last phone to obey cannot restart the first, however long it takes |
| One phone is silenced, the rest are not | The alarm controls follow *the group*, not this handset, so both "stop the alarm" and "disarm everyone" stay reachable from a phone that has already gone quiet |
| The scan duty cycle is slow | Timeouts scale with the gaps actually being observed rather than assuming a fast radio (§5) |

### What the group list is allowed to say

Everything on a peer card — armed, alarming, moving, how far away — is read off
the last beacon this phone happened to catch. That is fine while beacons are
arriving and actively misleading the moment they stop, and the card used to
render stale values with no indication that they were stale: "Alarming" for
minutes after an incident was over, "Not guarding" from a packet caught
mid-rearm, "moving away" from a two-hundred-millisecond dip. Watching that
flicker between four states while nothing at all was happening is what "the
state handling is completely broken" looked like from the outside.

Four rules now:

- **Silence is its own state and it outranks memories.** `linkStale` is decided
  natively, against the same adaptive threshold the `LOST` vote uses, and the
  card tests it first. A peer we cannot currently hear is reported as unheard,
  and its telemetry chips are hidden rather than shown as though current. The
  UI had its own flat eight second rule, which at the old default duty cycle was
  shorter than an ordinary gap between two scan results.
- **The threshold does not wander.** "How long a gap is normal here" is the
  maximum over a window of recent arrivals, not a decaying average. A decay is
  the wrong shape: a few quiet seconds after one unusually long gap would return
  the threshold to its floor, and the next perfectly ordinary long gap would flip
  the peer to "no signal" for a tick. A number that answers "is this unusual?"
  must not itself be unstable.
- **"N of M guarding" counts phones we can currently hear.** A peer whose last
  beacon said "armed" half a minute ago is not evidence of anything.
- **A suspicion has to last a second before it is drawn.** Brief episodes are
  constant on a busy beach; they are exactly what the detector is built to sit
  through, and repainting the card for each one says the opposite.

Disarming also clears every in-flight episode. Leaving them set is what left the
group list reading "moving away" about phones nobody was watching any more, for
as long as the app stayed open.

**And an accusation is withdrawn the moment it is disproved.** A `LOST` vote
used to survive the peer's return for the whole eight second vote lifetime,
because the branch that noticed the peer was back skipped past the retraction
whenever that peer had no learned baseline. A second observer agreeing with a
withdrawn accusation is a siren about a phone lying on the towel.

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

1. **Filtered in hardware.** A `ScanFilter` on our **service data** is pushed
   into the Bluetooth controller, so the CPU is never woken for the hundreds of
   unrelated advertisements on a busy beach. Filtering is not optional, either:
   Android 8.1 and up simply ignores an unfiltered scan while the screen is off.
2. **At the lowest duty cycle that works.** It escalates to
   `SCAN_MODE_LOW_LATENCY` when the engine has real evidence to chase, and drops
   straight back. Two other things buy a bounded escalation, both because the
   calm duty cycle is too sparse for a short-lived message to survive: **meeting
   a phone whose name we do not know yet** (§4) and **announcing a group
   command** (§4).

   > **The calm duty cycle is not only an energy setting.** It decides how long a
   > gap between two beacons is *normal*, and therefore what the group can tell
   > apart at all. `SCAN_MODE_LOW_POWER` — a 512 ms window every 5.12 s — was the
   > default, and at that rate the group list spent its time flickering between
   > "watched" and "no signal", names took minutes to assemble, and half the
   > group commands landed between two windows. It is still available, but it is
   > now the setting you have to choose rather than the one you get. See the
   > profile table below.

3. **Start-budgeted, not throttled.** Android silently blocks an app that starts
   scans more than five times in thirty seconds, and a blocked scanner is a guard
   that does not work — but the old answer, "never restart more often than once
   every six seconds", is too blunt. Hearing a group command, or the first hint
   of a theft, has to raise the duty cycle *now*, and up to six seconds of
   waiting is several scan windows of exactly the thing being chased. The
   scanner tracks its own recent starts instead and spends four of the five
   allowed, keeping the last in reserve.

The speaker's BLE address is added as a **second hardware filter** rather than
forcing an unfiltered scan, so tracking it costs essentially nothing.

> **The filter has to match the shape of the advertisement, not just its UUID.**
> Our beacon is a bare service-data field (AD type `0x16`) carrying the 20
> bytes; there is no "list of service UUIDs" field in it, because that would
> cost four more of the 31 bytes a legacy advertisement gets and buy nothing.
> `ScanFilter.setServiceUuid` tests `ScanRecord.getServiceUuids()`, which
> Android populates *only* from the service-UUID list types — never from service
> data. A service-UUID filter therefore matched **no packet at all**, on any
> device, and the software filter in `GattService` applies regardless of what
> the controller offloads, so there was no version of this that happened to
> work. Both radios ran perfectly and no phone ever saw another phone. The
> filter now matches on service data, with the version byte as its pattern so
> the controller can still reject foreign traffic before waking the CPU.

**Two numbers on the home screen exist because of that bug.** "Packets heard"
counts everything that got past the filter; "group beacons" counts those that
authenticated as ours. Nothing arriving at all and things arriving but being
discarded are completely different faults, and from the outside — an empty
group list — they look identical.

### Advertising

`ADVERTISE` interval follows the same escalation: ~1 s when calm, ~250 ms under
suspicion, ~100 ms while alarming. Non-connectable, non-scannable legacy PDUs —
the cheapest thing BLE offers.

**Transmit power never changes.** It would be tempting to turn it up when
something looks wrong, but observers detect theft by comparing RSSI against a
learned baseline, so raising TX power mid-incident would lift every reading and
mask exactly the drop we are trying to catch.

> **A re-tune that is refused has to be retried.** Changing the interval means
> tearing the advertising set down and building a new one, which is
> asynchronous — and a request that arrived while a start was still in flight
> used to be dropped silently, leaving the profile field on its old value with
> nothing to retry it: the health check only asked whether advertising was
> running at all. So a phone announcing a group command could sit at the calm
> one-per-second rate for the entire announcement, to a listener sampling ten
> percent of the time. Queued requests are now applied when the stack returns, a
> rejected start is retried rather than waited out, and the health check compares
> the rate on the air against the rate that was asked for.

**"Can this phone advertise" is not `isMultipleAdvertisementSupported`.** That
is the usual shorthand for the question and it answers a different one — whether
several advertising sets can run at once — and we only ever run one. Several
otherwise capable phones report false for it, and believing them left those
phones silently invisible to their own group. The test is now simply whether the
platform hands us an advertiser, and a start that the stack *rejects* raises the
same "the others cannot see you" warning as no support at all.

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
| Maximum | `LOW_LATENCY` (continuous) | Reacts hardest, only setting that noticeably shortens the day |
| **Balanced** (default) | `BALANCED` (~25 % duty) | Escalates on evidence. Peers update roughly once a second |
| Saver | `LOW_POWER` (~10 % duty) + hardware batching | Cheapest by a good margin; peers update every few seconds and group commands take longer to reach everybody |

What each of them costs, with the arithmetic shown, is in
**[README ▸ What it costs the battery](README.md#what-it-costs-the-battery)**.
The short version is that Balanced adds under half a percent an hour, and the
screen costs thirty times that.

**Every one of these moved up a notch in 1.6.0**, which is a deliberate reversal
of what the table used to say. The old default listened about a tenth of the
time, and the rest of the system had been tuned as though it listened
continuously: a ten second peer-loss timeout against gaps that were routinely
longer than that, an eight second staleness rule in the UI against the same
gaps, six name packets that had to be caught inside a single 25 s window, and
group commands that had to survive on one burst. Every one of those failures
read to the user as "the app is unreliable", and none of them read as "the scan
duty cycle is too low". The timeouts now scale with the observed rate *and* the
default duty cycle is high enough to be honest; the sparse setting is still
there for anyone who wants it, and the settings screen says plainly what it
costs.

---

## 6b. Lifetime — when the guard runs, and when it stops

`service/GuardService.kt`

**The guard lives exactly as long as the app does.** Not longer.

| Event | Guard | Notification |
| --- | --- | --- |
| Screen off, phone in a pocket | runs | stays |
| App backgrounded, Flutter engine reclaimed | runs | stays |
| App swiped out of Recents | **stops** | **gone** |
| Process killed under memory pressure, task still in Recents | restarts | stays |
| Phone rebooted | does not come back | none |

This is a deliberate reversal. A theft alarm that outlives its app is
defensible on paper, and the service used to do exactly that — but an ongoing
notification for something with no window anywhere is indistinguishable from an
app that will not go away, and "I closed it and it is still there" is not a
trade anyone agreed to. Closing the app closes the app.

Three details make that hold in practice:

- **`onTaskRemoved`, not `stopWithTask="true"`.** The manifest flag makes the
  system kill the service outright, with no callback and no chance to shut the
  radios, the wake lock and the heartbeat alarm down in order. Keeping it
  `false` and handling `onTaskRemoved` gives an orderly stop — and somewhere to
  make the one exception below.
- **An alarm in progress is not interrupted.** Silencing a live siren by
  flicking a card off the Recents screen would be a gift to whoever is holding
  the phone. The shutdown is deferred until the incident is resolved, and then
  happens by itself.
- **A sticky restart with no task stands down.** Several OEMs kill the process
  on a swipe *without* delivering `onTaskRemoved`, and `START_STICKY` would then
  put the notification straight back up for an app the user has closed. On a
  restart with a null intent the service checks whether the app still has a task
  in Recents, and stops if it does not. It still recovers normally from a
  genuine memory-pressure kill, which is what stickiness is for.
- **Only the app's *own* task counts.** `onTaskRemoved` is delivered for **any**
  task belonging to the app, not only the one the user swiped — and the
  lock-screen alarm surface deliberately runs in a task of its own. So dealing
  with an alarm from the lock screen tidied that task away, the service read it
  as "the app was closed", and the guard shut down mid-incident: radios off, the
  group command it had just queued never transmitted, and the phone gone from
  everybody else's mesh until somebody opened the app again. That is why
  silencing an alarm from the lock screen appeared to work locally and reach
  nobody, and why the same decision taken from inside the app did work. Two
  defences now: the alarm surface calls `finish()` rather than
  `finishAndRemoveTask()`, and the service ignores the removal of any task not
  rooted at `MainActivity`.

  > This was also a large part of "the peer states flicker". A phone whose guard
  > had silently stopped is a phone that has vanished from the mesh, and a phone
  > that has vanished is exactly what the rest of the group is built to react to.

**No boot receiver.** The guard used to restore itself after a reboot if it had
been armed. It no longer does, for the same reason: nobody opened the app, so
nothing should be running. The armed flag is still persisted, so opening the app
picks the guard straight back up where it was.

The cost is stated plainly rather than hidden: **swiping the app away while it
is guarding stops guarding.** The home screen and the ongoing notification are
the only places the guard's state is visible, so if the notification is gone,
nothing is being watched.

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

### Streaming, not a looped static buffer

The obvious implementation is an `AudioTrack` in `MODE_STATIC` holding two
seconds of PCM, with `setLoopPoints` doing the repetition. That was the original
implementation and it was unreliable: the static buffer is ~176 kB, which some
devices refuse outright, and `setLoopPoints` reports failure through a *return
code* rather than an exception — so a rejected loop looked like success and
produced silence.

The failure mode was the worst possible one: the guard believed it was screaming
while the phone sat there mutely. `MODE_STREAM` with a small buffer and a writer
thread has none of those limits, runs indefinitely, and reports failure
honestly. `sirenAudible` says whether audio genuinely started and is surfaced in
the app, so a mute alarm is visible rather than silent.

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

### One incident has exactly one source

Every phone in the group makes noise, but only the phone that **decided** on the
incident keeps `EVENT_ALARM` on the air. A phone joining in stays silent on the
wire.

That is not a bandwidth optimisation. While every alarming phone repeated the
event, two phones sustained each other indefinitely: silencing one made it fall
quiet for a fraction of a second, hear the other still repeating, and start
again — which the other then heard, and so on. Neither phone could be stood down
at all. Closing the app did not help, because an alarm in progress deliberately
defers the shutdown (§6b), so both services kept broadcasting with no window
anywhere. The only way out was for **everybody to leave the group**, which
changes the group id so the packets stop authenticating. With one source per
incident, silencing that source ends it.

The second half of the same problem is timing: a group-wide "stop" cannot land
on every phone in the same millisecond, so for a few seconds afterwards the air
still carries packets from the incident that was just called off. Every phone
therefore ignores **relayed** alarms for 15 s after a group stop.

> **A fixed window is not enough on its own, and 1.5.0 only postponed the
> problem.** The phone that *decided* on an incident keeps `EVENT_ALARM` in every
> beacon for as long as it is alarming — which is minutes, if nobody reaches it.
> So a phone whose owner had silenced it went quiet, waited the fifteen seconds
> out, heard the originator still shouting about the very same incident, and
> started screaming again. Silencing an alarm now also **declines that
> incident**, identified by *(the device that put it on the air, the device it is
> about)*, and the refusal lasts for as long as that incident is still audible
> rather than for a fixed time. It is forgotten twelve seconds after the source
> genuinely stops, so a second theft moments later is still heard, and arming the
> phone again clears the slate deliberately.

Three things keep that from becoming a hole:

- **Local detection is untouched.** A phone picked up during the window still
  alarms, and observers still reach consensus about a peer that is receding.
  Only the relay shortcut is muted, and the relay is the fast path, not the
  only one.
- **A panic is exempt from the window** — somebody's thumb on a button is news
  by definition, and since relays no longer repeat alarms, nothing but the phone
  whose button was pressed can put that event on the air. It is *not* exempt
  from a decision the user has already made about that same incident, or nobody
  could ever silence a panicking phone whose owner is still holding the button.

  > A panic used to go out as a plain `EVENT_ALARM`, which quietly made the one
  > thing this paragraph promises impossible: nothing ever put `EVENT_PANIC` on
  > the air, so the code exempting it could never run, and the unit test covering
  > it was feeding the engine a packet the app does not send. A panic pressed
  > just after somebody called an incident off was swallowed like any other echo.
- **It is bounded, and arming clears it.** Fifteen seconds, against a command
  that is repeated for twelve — long enough that the last phone to obey cannot
  re-trigger the first, short enough that a real theft moments after a false
  alarm is still heard.

A plain local disarm — switching your own phone off guard — deliberately does
**not** start that window. It is not about an incident, and it must not stop
your phone hearing that somebody else's is being taken.

### Getting *out* of a group alarm

There are two genuinely different things somebody standing over a screaming
towel might want, and the app used to offer neither of them properly:

| | What it does |
| --- | --- |
| **Stop the alarm** | Silences every phone in the group and leaves them **all guarding**. Baselines are relearned from wherever things now lie. |
| **Stop and disarm everyone** | Silences everybody and stands the whole group down. |

Both are on the lock-screen alarm surface, on the alarm notification, and on the
home screen, and **both reach the whole group from whichever phone is being
held** — including one that has already gone quiet. Authenticating on the
lock-screen surface carries out the choice that was tapped, whichever it was;
which button was pressed is held until the fingerprint or PIN comes back, and
the surface no longer takes the guard down with it on the way out (§6b).

That last clause is the fix. Three separate things conspired to make an alarm
almost impossible to get out of cleanly:

- **The alarm controls were gated on this phone's own state.** The moment
  somebody silenced their own handset the panel disappeared and the quick action
  flipped to "Arm all", while every other phone carried on screaming with nothing
  left to reach them by. Whether the *group* is in an incident is a different
  question from whether this phone is, and the snapshot now answers it
  separately — held for twelve seconds past the last alarm packet heard from
  anyone.
- **The lock-screen "Disarm" did two contradictory things at once.** It disarmed
  the phone it was pressed on and told everybody *else* to stop but keep
  guarding, so the group came out of every incident half armed and half not, and
  the only way back to a consistent state was to create a new group. The default
  on that screen is now "stop the alarm", which leaves the whole group —
  this phone included — guarding, and standing everybody down is a separate,
  deliberate button next to it.
- **The stop had to survive the originator.** See the note above on declined
  incidents.

The grace period is the one exception, and it keeps the old behaviour: nothing
has been announced to the group yet, so there is nothing to call off, and what
the owner picking up their own phone wants is to disarm it.

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

## 8b. First run, groups and permissions

`lib/screens/onboarding_screen.dart` · `lib/screens/group_gate_screen.dart` ·
`lib/screens/permissions_screen.dart` · `lib/core/permissions.dart`

### Three surfaces, and why the split matters

The root widget picks one of three, in this order:

| Condition | Screen |
| --- | --- |
| First run not finished | **Onboarding** — your name, then permissions |
| No group | **Group** — create or join |
| Otherwise | **Home** |

Setting this phone up and belonging to a group are **not the same event**.
Setting up happens once in the life of the install. A group is a state the app
moves in and out of all afternoon — people leave one and join another, and a
group ends the moment everybody does.

They used to be one four-step wizard, and conflating them was worse than
untidy: leaving a group threw the user back to a welcome screen, a name field
they had already filled in, and a progress bar counting through a PIN and a
permissions walkthrough that were both long since done. The wizard is now two
steps, neither of which mentions groups, and the group screen is a first-class
destination rather than a stage of something else. The PIN moved to
**Settings ▸ Disarming**, where it can be changed rather than only set.

Completion is **recorded natively**, not inferred. It used to be inferred from
"does a group exist", which became true in the middle of the wizard — so the
root widget swapped the whole thing out for the home screen the instant the
group was created, and the permissions walkthrough was never seen at all,
leaving a guard that could not raise a lock-screen prompt and would be suspended
by the battery manager within the hour. `onboardingComplete` is set at the end
of the last step and nowhere else; an interrupted first run resumes where it
stopped, and installs that predate the flag are migrated as already complete.

> **The name is saved when it is typed, not when a group is created.** It used
> to ride along with `createGroup`, which is no longer part of this flow — and
> an unsaved name is a phone that introduces itself to the whole group as a
> hexadecimal id.

### The permission list

Android needs six separate grants before the guard works properly, three of
which are only reachable through its own settings app. The screen is
deliberately **explain-first**: it lists every permission, what it is for in
plain language, and specifically *what stops working without it*, before asking
for anything.

That ordering is not decoration. An app that fires four system dialogs within
ten seconds of first launch gets refused out of reflex, and on Android a
permission that has been denied twice can only be restored by digging through
Settings — so a confusing first run permanently degrades the app.

| Permission | Required | Why |
| --- | :---: | --- |
| Bluetooth (scan / advertise / connect) | ● | The entire protocol |
| Bluetooth switched on | ● | The permission alone is not enough |
| Notifications | ● | Android only allows background work with an ongoing notification |
| Ignore battery optimisation | | Otherwise the guard is suspended after 15–30 minutes |
| Full screen alarms | | Lock-screen disarm prompt (Android 14+) |
| Camera | | QR joining only; typing the code always works |

The screen re-reads every status on `AppLifecycleState.resumed`, because half of
them are granted in another app entirely. It is reachable afterwards from
**Settings ▸ System ▸ Permissions and setup**, and it notes explicitly that no
internet permission is requested, because the app never uses one.

### The standing reminder

The home screen carries a banner for anything still outstanding, until it is
dealt with — red when a *required* grant is missing (the guard cannot run at
all), amber for the optional ones (it runs, but is more easily interrupted). It
names what is missing and what that specifically costs, and one tap opens the
walkthrough.

This exists because every one of these failures is **silent**. A phone with no
notification permission shows an armed shield and guards nothing; a phone whose
battery exemption was never granted works perfectly for twenty minutes. The list
itself lives in `core/permissions.dart` and is shared by the walkthrough and the
banner, because two copies of "what is still missing" is one copy too many.
Status is re-read whenever the app returns to the foreground — permissions can
be revoked, and Bluetooth switched off, from outside the app.

## 9. Testing

### Automated — `tools\test-all.ps1`

**94 Kotlin/JUnit tests** covering the rules that matter. Every one corresponds
to a real situation:

- *Suppression*: person walking between phones; sustained drop from a stationary
  peer; a moving observer abstaining; **a knock to the towel is not a pickup**.
- *Detection*: peer carried away; thief who walks off then stops; single
  observer insufficient with three phones; second observer completing consensus.
- *Disappearance*: armed peer vanishing; low battery treated as a warning;
  disarmed peers not guarded; **an ordinary twelve second gap between scan
  results is not a vanished phone** (regression test); a peer missed for a
  moment is never voted lost; a peer that has gone quiet is reported as unheard.
- *Victim side*: pickup → grace → alarm; **a lift detected by the accelerometer
  alone starts the countdown** (regression test, see below); **a lift that
  starts gently still trips it** (regression test); movement that never stops
  counting as a pickup; a phone still in the owner's hand never trips it;
  picking it up again before it has settled; disarm cancelling; corroboration
  cutting the grace short.
- *Reaction time*: a lifted phone alarms inside the budget; **a peer walking
  away at 1.3 m/s is caught inside 5 s**, against the log-distance fade rather
  than a hand-picked ramp; a shortened grace period really does shorten the
  wait.
- *Consensus*: the requirement scales with group size; one witness suffices with
  three phones but not with five; a second witness completes it.
- *Lone phone*: a single phone with no peers at all still alarms when lifted,
  leaves calibration promptly, and reports when its pickup detector is ready.
- *Speaker*: a guarded speaker losing its link alarms; one that was never
  connected does not; pointing at a different speaker clears the latch.
- *Group control*: relayed alarms; **a cleared alarm is not restarted by the
  phone that has not caught up yet** (regression test — this is the two-phone
  deadlock); **joining somebody else's alarm does not put it back on the air**;
  a phone that decides for itself does broadcast; "disarm all" stays disarmed;
  a genuinely new alarm is still heard once the echo window passes; a plain
  local disarm does not deafen the phone to the group; a panic gets through
  immediately after a group stop; **replayed control packets rejected**; a
  sender that has run far ahead is still obeyed; sequence wraparound.
- *Commands reaching everybody*: a command is passed on, exactly once, with its
  origin intact; the phone that issued it ignores it coming back; **a relay
  arriving after the user re-armed does not stand them down again** and
  **pressing the same button again is obeyed** (regression tests — the two
  halves that cannot both be satisfied without a command counter); a stale copy
  of an older command is ignored; counter wraparound.
- *The event slot* (`BeaconComposerTest`): **an alarm is on the air only while
  it is actually sounding** (regression test); a panic goes out as a panic; a
  command carries its origin and counter and stops when its window closes; an
  alarm outranks a queued command and the command survives it; votes are
  interleaved rather than starved; a name is dripped out and reassembles.
- *Getting out of an alarm*: **an incident the user silenced cannot restart
  itself a minute later** (regression test — this is the originator that keeps
  shouting); a phone that has stood itself down still knows the group is
  alarming; stopping a false alarm leaves the phone guarding; disarming forgets
  what it was suspicious about; **a lost vote is withdrawn as soon as the peer is
  heard again** (regression test).
- *Names*: meeting a phone with no name speeds the radio up and drops back the
  moment the name arrives; a phone that never sends one does not hold the radio
  up forever; **a phone that stayed anonymous gets another chance later**; a
  disarmed phone introduces itself even while it is being handled, and an armed
  one does not; a calibrating phone does; a reassembled name is reported once so
  it can be persisted.
- *Energy*: calm guard stays on the low-power profile; passers-by do not
  escalate the radios; suspicion escalates then relaxes.
- *Settings*: every tunable round-trips; partial patches leave the rest alone;
  any numeric type is accepted; nonsense values are clamped rather than obeyed.
- *Protocol*: round-trips, tampering, foreign groups, wrong keys, truncation,
  group-code encoding including O/0 confusion, name reassembly.

The Kotlin suite is split across `ThreatEngineTest` (the rules), `ProtocolTest`
(the wire format), `BeaconComposerTest` (what goes into the one event slot, and
what stops going into it) and `EngineConfigCodecTest` (settings round-trips).

Plus **14 Dart tests** on snapshot decoding and the consensus mirror — malformed
payloads, NaN values, unknown enum names from a future native build, that the
UI's copy of the consensus rule agrees with Kotlin's, that an unfinished first
run is never mistaken for a finished one, that **leaving a group does not
undo the first run**, that staleness comes from the engine rather than a rule of
thumb in the UI, that a peer we cannot hear is not counted as guarding, and that
a group alarm outlives this phone leaving it.

### In-app simulator — Settings ▸ Testing

Feeds synthetic beacons into **exactly the same engine entry points** the real
radio uses; nothing is stubbed. Ten scenarios, each declaring whether it must
alarm or must stay silent, with a pass/fail verdict, a **budget**, and the
**measured time to detection**:

`CALM_GROUP` · `PASSER_BY` · `THEFT_WALK` · `THEFT_RUN` · `POCKETED` ·
`THEFT_CONSENSUS` · `VANISH` · `VANISH_LOW_BATTERY` · `SELF_PICKUP` ·
`BOX_TAKEN`

This is what makes the app fully testable **on a single phone**.

**The fades are physics, not curves.** Every walk-away scenario derives its RSSI
from the same log-distance model the UI uses for proximity — a peer starting
1.5 m away and moving at 1.3 m/s (or 3.6 m/s for the run). The scripts used to
fade linearly at a rate chosen to look plausible, which understated the first
second badly and overstated the tenth; a detector tuned against that is tuned
against fiction.

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
| `lib/core/` | Theme, models, platform-channel client, `GuardController`, the permission list |
| `lib/widgets/` | Shield button, peer/box cards, shared chrome |
| `lib/screens/` | Onboarding, group gate, permissions, home, group, settings, box setup, simulator, QR scan |
| `android/.../ble/` | `Protocol`, `BleAdvertiser`, `BleScanner`, `BeaconComposer`, `BleDiscovery` |
| `android/.../guard/` | `ThreatEngine`, `Filters`, `Models`, `BoxGuard` |
| `android/.../sensors/` | `MotionMonitor` |
| `android/.../alarm/` | `AlarmPlayer` |
| `android/.../service/` | `GuardService`, `GuardIntents` |
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
- **The app has to stay open** — backgrounded is fine, swiped away is not, and
  the guard does not return by itself after a reboot (§6b).
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
| 1.6.1 | Follow-up to 1.6.0, from the same session. The user's own diagnosis was right: **things that were over were still being broadcast.** **(a) A silenced alarm stayed on the air for the whole command repeat window.** Raising an alarm queued `EVENT_ALARM` as a *repeating group command* as well as riding on the guard's live state, and nothing ever cancelled that queue — so a phone went on shouting "theft!" for up to half a minute after its siren had been silenced. Every other phone heard an incident that no longer existed: the group-alarm banner reappeared by itself long after everybody had stood down, peers showed as "Alarming", and once the fifteen second echo window lapsed a phone could be dragged into a fresh, real siren by a packet describing something that had ended. Lengthening the repeat window in 1.6.0 for the commands' sake had made it proportionally worse. The alarm is now derived from live state only and leaves the air on the very next beacon; `BeaconComposerTest` exists to keep it that way. **(b) Dealing with an alarm from the lock screen shut the guard down.** `onTaskRemoved` is delivered for *any* task belonging to the app, and the alarm surface deliberately runs in a task of its own — so tidying it away read as "the user closed the app", and the service stopped mid-incident with the group command it had just queued still unsent. That is exactly why the decision "only worked from inside the app", and a phone whose guard has silently stopped is a phone that has vanished from the mesh, which is most of the remaining state churn. Now `finish()` rather than `finishAndRemoveTask()`, and the service ignores any task not rooted at `MainActivity`. **(c) Group commands now carry a counter**, persisted, identifying one press. 1.6.0 remembered commands by *type* for ninety seconds to stop a circulating relay re-applying itself, which broke the first sequence anybody performs: arm all, disarm all, arm all — the third press was ignored by every phone that had heard the first. Obeying every copy instead reintroduces the original problem. Neither is fixable without an identity, so one press is now one number, riding in the motion-score byte exactly as name chunks ride in the subject field. **(d) A `LOST` vote was never withdrawn** when the peer came back, if that peer had no learned baseline — the branch that noticed the return skipped straight past the retraction. **(e) The staleness threshold wandered**: it was a decaying average, so a few quiet seconds after one long gap returned it to its floor and the next ordinary long gap flipped the peer to "no signal" for a tick. It is now a maximum over a window of arrivals. **(f) A panic went out as a plain alarm**, so the exemption that is the entire point of the panic button could never run — and the test covering it was feeding the engine a packet the app never sent. **(g)** The group-alarm belief is reset by any group stop, so the banner goes when the group does and comes back within a second if somebody genuinely has not stopped. Also: a section on what each power setting actually costs the battery, with the arithmetic shown, in the README. |
| 1.6.0 | Second two-phone test. Four reports, one root cause behind most of them: **the rest of the system was tuned as though the radio listened continuously, and by default it listened about a tenth of the time.** **(a) Group commands were unreliable, and visibly better at the Maximum battery setting.** A command was announced in a single twelve second burst, and a phone whose advertising re-tune was requested while the Bluetooth stack was still bringing a set up had that request *dropped silently* — so the announcement went out at the calm one-per-second rate, to a listener sampling 10 % of the time, with nothing to retry it because the health check only asked whether advertising was running at all. Commands are now repeated for 30 s, queued re-tunes are applied when the stack returns, the health check compares the rate on the air against the rate asked for, and **every phone passes a command on the first time it sees it** — so one burst becomes an epidemic that reaches everyone in range of anyone. Commands are remembered by *(command, issuing device)*, which terminates the flood and stops a relay still circulating a minute later from standing down a phone the user has just armed again. The scanner also stopped rationing its restarts evenly and now spends four of Android's five allowed starts per thirty seconds where they matter, so an escalation is immediate rather than up to six seconds late. **(b) Peer state churned between arbitrary values, and names never arrived.** The peer-loss test was a flat ten seconds against a duty cycle whose ordinary gap between two beacons was routinely longer than that, so observers voted `LOST` about phones lying on the same towel — and in a two-phone group one vote is the whole consensus, evaluated in the same tick, so it alarmed on the spot. That threshold now scales with the gaps actually observed from each peer and needs the silence to survive three further seconds of escalated radio; the UI's separate eight second staleness rule is gone, replaced by the engine's own answer; silence outranks stale telemetry on the card; and a suspicion has to last a second before it is drawn. Names get repeated attempts rather than one 25 s window, are sent during calibration too, and are **persisted once learned**. **(c) Disarming one phone left the others screaming with no way back to them.** The alarm controls were gated on the local state, so silencing your own handset replaced them with "Arm all"; they now follow *the group*. The lock-screen prompt disarmed the phone it was pressed on while telling everybody else to keep guarding, which is why the group came out of every incident in a mixed state — it now offers **"Stop the alarm"** (silences everyone, everyone keeps guarding) and **"Stop and disarm everyone"**, and both work from a phone that has already gone quiet. **(d) A silenced phone re-joined the alarm fifteen seconds later.** The originator keeps `EVENT_ALARM` in every beacon for as long as it is alarming, so a fixed echo window only postponed the problem. Silencing an alarm now declines that *incident* — identified by source and subject — for as long as it is still audible. Disarming also clears every in-flight episode. **(e) The power profiles moved up a notch**: Balanced is now `SCAN_MODE_BALANCED`, Saver keeps the old `LOW_POWER`, Maximum is continuous, and the settings screen says plainly what the sparse setting costs. **(f)** The alarm notification and the full-screen prompt used to hang around for the whole eight second calibration window after "stop, keep guarding", because the state they were waiting for was `CALIBRATING` and only `ARMED`/`DISARMED` cleared them. A phone switched off is now detected in ~13.5 s rather than ~10.5 s, which is the deliberate price of (b). |
| 1.5.0 | First test with two phones actually in a group. **(a) The group could not be got out of an alarm.** Every alarming phone repeated `EVENT_ALARM` continuously, so two phones sustained each other: silencing one made it fall quiet for a fraction of a second, hear the other still repeating, and start again. "Disarm all" and "stop and disarm everyone" therefore appeared to do nothing, closing the app did not help — an alarm in progress defers the shutdown on purpose — and the only escape was for everybody to leave the group, which changes the group id so the packets stop authenticating. Now exactly one phone speaks for each incident: a phone joining in makes just as much noise but stays silent on the wire, and every phone ignores *relayed* alarms for 15 s after a group stop so the last phone to obey cannot re-trigger the first. Local detection is untouched throughout, and a panic is exempt. **(b) "Arm all" reached the other phone about half the time.** A group command was repeated for four seconds; a listener on the calm profile samples for 512 ms every 5.12 s, so the whole announcement could fall between two windows. Commands are now repeated for 12 s and the sender lifts its own advertising rate for the duration, since the listener's duty cycle is the one thing it cannot change. **(c) A phone armed by the group came back disarmed after a restart.** The persisted armed flag was written by the command handlers, so nothing that changed the guard's state from outside the UI — "arm all", "disarm all", the notification action, the lock-screen disarm — was recorded. It now follows every state transition the engine makes. **(d) Nobody's name ever appeared.** Names are dripped out six packets at a time and were only sent while the phone was lying still, which is precisely not what a phone is doing while its owner sets a group up — and at the calm duty cycle assembling one takes over a minute anyway. A disarmed phone now introduces itself whatever it is doing, and meeting a new phone buys 25 s of fast radio at both ends, so names arrive in seconds. **(e) Leaving a group stopped the app updating** until it was restarted, because the service cleared the Flutter bridge's snapshot listener on shutdown and nothing put it back — which is why creating the next group reported Bluetooth as off. The listener belongs to the bridge; the UI also no longer claims the radio is off merely because it has not heard yet. **(f) Rejoining a group looked like a replay attack**: the sequence counter was reset when the group changed, but the device id is derived from the secret and the install id, so the same device came back with a lower number and had its commands discarded. The counter is never reset now, and receivers follow it on every beacon rather than only on commands. **(g) First run and groups are separate screens.** Leaving a group used to reopen the four-step wizard — welcome, a name already set, a PIN and a permissions walkthrough long since done. The wizard is two steps and runs once; creating or joining a group is its own screen, shown whenever there is no group. The PIN moved to Settings ▸ Disarming. |
| 1.4.0 | Two-phone field test. **(a) No phone had ever seen another phone.** The scanner filtered on a *service UUID*, while the beacon carries its payload as *service data* and nothing else. `ScanRecord.getServiceUuids()` is populated only from the service-UUID list AD types, never from service data, and the framework applies that filter in software regardless of what the controller offloads — so the filter matched no packet at all, on every device, since the first commit. Both radios worked perfectly and the mesh simply never formed; with one phone there is nothing to notice. The filter now matches service data, keyed on the version byte so foreign traffic is still rejected in hardware. Two counters — packets heard, and how many of those authenticated as this group — are now on the home screen, because "nothing is arriving" and "things arrive and are discarded" are different faults that look identical from an empty group list. The capability check for advertising was also over-strict: it used `isMultipleAdvertisementSupported`, which answers whether *several* sets can run at once, so phones that can advertise perfectly well were refusing to. **(b) The guard now stops when the app is closed.** It used to outlive the app deliberately; an ongoing notification for an app with no window is indistinguishable from one that will not go away. Swiping BeachProtect out of Recents shuts the radios, the wake lock, the heartbeat and both notifications down in order, a sticky restart with no task in Recents stands down rather than reposting the notification, and the boot receiver is gone. An alarm already sounding is the one exception: it finishes first, so a siren cannot be silenced by flicking a card off the Recents screen. |
| 1.3.0 | Third field-test round. **(a) A lift that started gently switched the pickup detector off** instead of triggering it: readiness is derived from "how long has this phone been lying still", and the first sample reporting movement clears that marker — so any lift whose opening sample fell below `motionScoreThreshold` left every later, stronger sample looking at a phone that had not been lying still. The phone could be carried off in silence, and all the owner saw was the app asking them to put it down again. Readiness is now decided once, when movement begins, and latched until the phone comes to rest; sustained movement counts even with no decisive sample; and the home screen no longer shows the "put the phone down" chip during the grace period, where it read as the opposite of what the countdown meant. **(b) The peer path was two to three times slower than it needed to be.** The RSSI filter added a fixed amount of process noise per sample, which assumed a constant sample rate and lagged by three to four seconds at the calm scan duty cycle, and the confirmation window only started once the drop was already complete. Process noise now scales with the time since the last sample, the slope window is bounded in time rather than in samples, and an episode starts at 6 dB so confirmation runs alongside the fade — voting still requires the full 11 dB, and a step change behaves exactly as before. A phone carried away at walking pace: 8.8 s → ~4 s. **(c) The first-run walkthrough ended after two of its four steps**, because completion was inferred from "a group exists" — which becomes true at step two — so the PIN page and the entire permissions walkthrough were never shown. Completion is now recorded natively at the end of the last step, an interrupted first run resumes where it stopped, and the home screen carries a standing reminder of anything Android has not allowed yet, since every one of those failures is silent. |
| 1.2.0 | Second field-test round. **(a) Silent siren**: the alarm used a 176 kB `MODE_STATIC` buffer with `setLoopPoints`, which some devices reject — and `setLoopPoints` signals failure by return code, not exception, so a rejected loop looked like success and played nothing. Rebuilt around `MODE_STREAM` with a writer thread; `sirenAudible` now reports whether audio actually opened, and the UI says so during an alarm. This is why a lone phone detected the lift but never made a sound: rehearsals only play the chirp, so no test ever exercised the siren. **(b) Pickup readiness is now visible** — the detector only arms after the phone has lain still for `settleMs`, and the home screen now shows "Pickup protection active" or a countdown, rather than leaving the user guessing. **(c) Permissions**: a proper explain-then-grant walkthrough replaces the old terse checklist, listing what each permission is for and what breaks without it, re-runnable from Settings. **(d) `BOX_TAKEN` scenario** could never pass without a real paired speaker, and sent a disconnect without a preceding connect; it now borrows a virtual speaker and the engine resets its box-alarm latch when the device changes. Also: the tick loop can no longer be killed by a stray exception, and simulation tidy-up runs even when a scenario ends by itself. |
| 1.1.0 | Field-test fixes: connectionless BLE mesh, RSSI + accelerometer fusion, multi-observer consensus, peer-loss detection, speaker guarding via A2DP link loss and BLE beacon, native alarm surface with three disarm modes, adaptive radio/sensor energy management, in-app simulator, 43 automated tests. |
| 1.1.0 | Field-test fixes. **(a) Reaction time**: the accelerometer pickup path was dead code — the handler cleared the "lying still since" marker before the readiness test that reads it, leaving the slow `SIGNIFICANT_MOTION` sensor as the only trigger and taking ~30 s. Fixed, and the armed guard now runs a batched accelerometer instead of waiting on that sensor; detection is ~0.3 s and the default grace period dropped from 10 s to 3 s, with an immediate warning chirp on lift. **(b) Consensus** changed from a fixed observer count to a proportion of the group (default one third), so three phones no longer require two witnesses. **(c) Test scenarios** now run as rehearsals — no real siren, no group broadcast, armed state restored — finish as soon as the verdict is known, and report measured detection times. Several scenarios previously could never pass, because two simulated peers implied a two-witness requirement that only one voter could ever meet. **(d) Lock screen**: a full-screen-intent notification is now posted for the grace period, not only for the alarm, so the disarm prompt appears on a locked phone; the manifest used the non-public `showOnLockScreen` instead of `showWhenLocked`; the home screen warns when Android 14+ withholds the permission, and a lock-screen self-test was added. Also: detector settings extracted into a pure, unit-tested codec with clamping; sliders commit on release rather than on every pixel; `install.ps1` warns when the APK is older than the source. |
