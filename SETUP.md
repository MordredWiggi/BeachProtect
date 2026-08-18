# Development setup — this machine, step by step

Everything in this document has already been done and verified on this laptop.
It is written out in full so you can repeat it, undo it, or fix it when
something drifts.

---

## 0. TL;DR — the three commands you actually need

Open PowerShell in `D:\Dokumente\GitHub\BeachProtect` and:

```powershell
.\tools\build-apk.ps1              # release APK
.\tools\devices.ps1                # is my phone connected?
.\tools\install.ps1 -Launch        # push it to every connected phone and start it
```

Everything else below is explanation and troubleshooting.

---

## 1. What was already installed

Verified with `flutter doctor -v`:

| Component | Version | Location |
| --- | --- | --- |
| Flutter | 3.41.7 (stable) | `D:\flutter` |
| Dart | 3.11.5 | bundled with Flutter |
| Android SDK | 36.1.0, all licences accepted | `D:\AppData\Local\Programs\AndroidSDK` |
| JDK | OpenJDK 21 (Android Studio's JBR) | `D:\Program Files\Android\AndroidStudio\jbr` |
| Git | — | `C:\Program Files\Git` |

`flutter doctor` also complains about **Chrome** and an incomplete **Visual
Studio Build Tools**. Both are irrelevant here — they are only needed for web
and Windows desktop targets, and this app is Android-only. You can ignore them.

During the first build the toolchain also auto-installed NDK 28.2, Build-Tools
35, Android Platform 36 and CMake 3.22. That was expected and needed no action.

---

## 2. ⚠️ The disk problem, and what was changed because of it

**Your C: drive was completely full — 0 bytes free.** Gradle failed outright
with `java.io.IOException: Es steht nicht genug Speicherplatz auf dem
Datenträger zur Verfügung`.

Android builds need several gigabytes of cache, so two caches were moved off
C: and onto D: (which has ~230 GB free):

| Cache | Was | Now | Size |
| --- | --- | --- | --- |
| Gradle | `C:\Users\JanCS\.gradle` | `D:\gradle-home` | 2.33 GB |
| Dart/Flutter packages | `C:\Users\JanCS\AppData\Local\Pub\Cache` | `D:\pub-cache` | 0.24 GB |

Two **persistent user environment variables** were set to point at the new
locations:

```
GRADLE_USER_HOME = D:\gradle-home
PUB_CACHE        = D:\pub-cache
```

Moving the pub cache had a second benefit: while it sat on C: and the project
on D:, Kotlin's incremental compiler could not compute relative paths across
drives and spewed `this and base files have different roots` stack traces on
every build. Those are now gone.

### To undo this

```powershell
[Environment]::SetEnvironmentVariable("GRADLE_USER_HOME", $null, "User")
[Environment]::SetEnvironmentVariable("PUB_CACHE", $null, "User")
# then move D:\gradle-home and D:\pub-cache back, or just delete them —
# both are caches and rebuild themselves from the network.
```

### Still worth your attention

C: now has roughly **2.4 GB free**, which came entirely from the caches that
were moved. That is enough to build, but it is not comfortable — Windows itself
wants headroom for updates, paging and temp files. Something else is using
~118 GB on that drive and it is worth a look with WinDirStat or
`Settings ▸ System ▸ Storage`. This is outside the app, so nothing was touched.

---

## 3. Other environment changes made

| Change | Why |
| --- | --- |
| `D:\AppData\Local\Programs\AndroidSDK\platform-tools` added to user `Path` | So `adb` works in any terminal. It was installed but not on the path. |
| `ANDROID_HOME` set to the SDK root | Standard, and some tooling looks for it. |

`JAVA_HOME` was deliberately **not** set globally, in case you have other
projects expecting a different JDK. The scripts in `tools\` set it per-session
via `tools\env.ps1`. If you ever want it globally:

```powershell
[Environment]::SetEnvironmentVariable("JAVA_HOME", "D:\Program Files\Android\AndroidStudio\jbr", "User")
```

> **Open a new terminal** after any of this. PowerShell reads environment
> variables at startup; an already-open window will not see them.

---

## 4. Preparing your phone (one time)

The app needs a real device. It cannot be meaningfully tested on an emulator,
because emulators have no Bluetooth radio and no accelerometer.

1. **Settings ▸ About phone**
2. Tap **Build number** seven times. It will count down: "You are now 3 steps
   away from being a developer."
3. Go back. **Settings ▸ System ▸ Developer options** (on Samsung it is at the
   bottom of the main Settings list).
4. Turn on **USB debugging**.
5. Plug the phone into the laptop with a cable that **carries data**. A lot of
   cheap cables are charge-only and will look like nothing is plugged in.
6. The phone shows *"Allow USB debugging?"* — tick **Always allow from this
   computer**, then **Allow**.
7. Verify:

```powershell
.\tools\devices.ps1
```

You should see something like:

```
R5CT30ABCDE            SM-G991B                   Android 14 (API 34)
```

### If it does not appear

| Symptom | Fix |
| --- | --- |
| Nothing listed at all | Try another cable, then another USB port. Charge-only cables are the single most common cause. |
| Listed as `unauthorized` | The prompt was dismissed. Developer options ▸ **Revoke USB debugging authorisations**, then unplug and replug. |
| Listed as `offline` | `adb kill-server` then `adb start-server`. |
| Windows shows an unknown device | Install your manufacturer's USB driver (Samsung: "Samsung USB Driver for Mobile Phones"; Google/Pixel: the Google USB Driver from the SDK Manager). |

---

## 5. Building APKs

```powershell
.\tools\build-apk.ps1                 # release (what you share with friends)
.\tools\build-apk.ps1 -DebugBuild     # debug — faster to build, much larger
.\tools\build-apk.ps1 -Split          # one APK per CPU architecture, much smaller
.\tools\build-apk.ps1 -Clean          # after weird build errors
.\tools\build-apk.ps1 -Install        # build then push to the phone
```

Output lands in:

```
build\app\outputs\flutter-apk\app-release.apk
```

Or use Flutter directly if you prefer:

```powershell
flutter build apk --release
```

### About the size

The universal release APK is **68.4 MB**, because it carries native code for
every CPU architecture. Most of the rest is the ML Kit barcode scanner that
powers QR-code group joining.

`.\tools\build-apk.ps1 -Split` builds one APK per architecture instead
(measured on this machine):

| APK | Size | Use it for |
| --- | ---: | --- |
| `app-arm64-v8a-release.apk` | 28.8 MB | Any phone from roughly 2016 onwards — almost certainly yours |
| `app-armeabi-v7a-release.apk` | 24.8 MB | Older 32-bit phones |
| `app-x86_64-release.apk` | 31.1 MB | Emulators only |
| `app-release.apk` | 68.4 MB | Universal, works everywhere |

Sending friends `app-arm64-v8a-release.apk` is the sensible default.

If you want it smaller still, drop the `mobile_scanner` dependency from
`pubspec.yaml` and join groups by typing the 16-character code instead. The QR
*display* uses `qr_flutter`, which is pure Dart and costs almost nothing.

### First build is slow

Expect 5–10 minutes the first time, and roughly 30–90 seconds after that.
Gradle caches aggressively in `D:\gradle-home`.

---

## 6. Installing and running on the phone

### Just install the APK

```powershell
.\tools\install.ps1                       # every connected phone
.\tools\install.ps1 -Launch               # and start it
.\tools\install.ps1 -Serial R5CT30ABCDE   # one specific phone
.\tools\install.ps1 -Reinstall            # wipe settings first — for testing onboarding
```

### Develop with hot reload

This is what you want while changing the Flutter UI:

```powershell
flutter run --release        # or --debug
```

Then press `r` to hot-reload, `R` to hot-restart, `q` to quit.

> **Important:** hot reload only applies to Dart. The guard itself is Kotlin, so
> **any change under `android/` needs a full rebuild** — stop, then `flutter run`
> again.

> Prefer `--release` for real testing. Debug builds disable Dart JIT
> optimisations and are noticeably heavier on battery, which will mislead you
> when you are trying to judge power consumption.

### Watching what it decides

```powershell
.\tools\logs.ps1                # just BeachProtect's own tags
.\tools\logs.ps1 -All           # everything from the app process
.\tools\logs.ps1 -Clear         # clear the buffer first
```

The interesting lines are:

```
BpGuardService: state ARMED -> SUSPICIOUS
BpGuardService: ALARM THEFT_CONSENSUS subject=41377
```

---

## 7. Running the tests

```powershell
.\tools\test-all.ps1              # everything
.\tools\test-all.ps1 -Kotlin      # detection engine and protocol only
.\tools\test-all.ps1 -Dart        # snapshot decoding + static analysis
```

- **58 Kotlin/JUnit tests** cover the detection rules — occlusion suppression,
  consensus, replay protection, the pickup grace period, and the reaction-time
  budgets for both the victim and the peer path. These run on the JVM in a
  couple of seconds and are the fastest way to know you have not broken the
  logic.
- **9 Dart tests** cover snapshot decoding, the consensus rule mirror and
  first-run completion.

An HTML report is written to
`build\app\reports\tests\testDebugUnitTest\index.html`.

---

## 8. Testing with one phone

You said you have one phone for now. The app was built with that in mind.

Turn on **Settings ▸ Testing ▸ Test scenarios** in the app. A flask icon
appears in the toolbar leading to a screen with ten scripted situations, each
declaring up front whether it *must* alarm or *must stay silent*.

**These are rehearsals.** A scenario that trips the detector plays a short
confirmation beep instead of the real siren, does not tell anybody else's
phone, and stands the guard straight back up — so the run keeps going and your
phone is left exactly as it was. Each scenario also stops the moment its verdict
is decided and reports **how long detection took**, so you can see directly
whether the reaction time is acceptable.

| Scenario | Expected |
| --- | --- |
| Calm group | silent |
| People walking past | **silent** — this is the important one |
| Phone carried away | alarm |
| Phone grabbed and run with | alarm |
| Pocketed then stopped | alarm |
| Theft confirmed by a second phone | alarm |
| Phone switched off | alarm |
| Phone dies at 3 percent | **silent** |
| This phone picked up | alarm |
| Speaker unplugged | alarm |

Tap **Run all scenarios** (a couple of minutes) and leave the phone lying
still. Each card turns into a green tick or a red cross, and alarming scenarios
report the measured time to detection.

These feed synthetic beacons into exactly the same engine entry points the real
Bluetooth radio uses — nothing is stubbed or bypassed, so a pass genuinely
means the detection logic works.

There is also **Settings ▸ Testing ▸ Test the lock screen alarm**: tap it, lock
your phone within six seconds, and the disarm prompt should appear over the lock
screen. If it does not, Android is withholding the full-screen alarm permission
— the home screen will be showing a red banner with a one-tap fix.

---

## 9. Testing with two or more phones

Once you have organised more phones:

```powershell
.\tools\field-test.ps1 -Install
```

This installs on every connected phone, starts synchronised log capture on each
one, and walks you through eight real-world steps — baseline, people walking
past, a phone carried away, the owner picking up their own phone, a phone
switched off, three-phone consensus, speaker theft, and a battery run. Steps
that need more phones than you have connected are skipped automatically.

At the end it prints what each phone independently decided, so you can see
whether they agreed and how fast.

Useful extra:

```powershell
.\tools\field-test.ps1 -Install -Wireless
```

`-Wireless` switches adb to Wi-Fi so you can unplug the phones and actually
walk away with one while still collecting logs.

### Setting the group up across phones

On a fresh install every phone first walks through **your name** and **the
permissions Android needs** — once, and never again. It then lands on the group
screen, which is also where a phone goes whenever it has no group.

1. On the first phone: **Create**.
2. On the others: **Join** ▸ scan the QR from phone one, or type its
   16-character code.
3. In **Settings ▸ Disarming**, set the **same group PIN** everywhere — that is
   what lets any of you silence a false alarm on anyone's phone. Without one,
   a phone falls back to its fingerprint reader, or to a single tap.
4. Leave the app open on every phone. Backgrounded and screen off is fine;
   swiped out of Recents stops that phone's guard entirely.

Within about ten seconds each phone should list the others under **Group**, and
the `group beacons` chip at the bottom of the home screen should be counting up.
Neither phone has to be armed for that.

Names take a few seconds longer than the phones themselves do: they are dripped
out two characters at a time in the beacon's spare bytes, and a phone runs its
radio fast for the first 25 seconds after meeting somebody new specifically so
this does not take a minute. Until a name arrives the others show a hexadecimal
id, and you can always override one locally by tapping it.

---

## 10. Release signing (optional)

Until you do this, release APKs are signed with Android's debug key. That works
fine for your own phones, with one catch: everyone's debug key is different, so
an APK built on a different machine cannot upgrade one built here — Android
refuses with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

To create a proper key:

```powershell
.\tools\make-keystore.ps1
```

It writes `android\beachprotect-release.jks` and `android\key.properties`, both
already git-ignored. `build-apk.ps1` picks them up automatically from then on.

**Back the `.jks` file up.** Lose it and you can never update an installed copy
of the app without uninstalling it first.

---

## 11. Troubleshooting

| Problem | Cause and fix |
| --- | --- |
| `Es steht nicht genug Speicherplatz…` | C: full again. See §2. |
| `JAVA_HOME is not set` | You ran `gradlew` directly. Use the `tools\` scripts, or `. .\tools\env.ps1` first. |
| `e: Daemon compilation failed: null` | Stale Kotlin incremental caches, usually after moving the pub cache or the project. `.\tools\build-apk.ps1 -Clean` clears them. |
| `Ein Parameter mit dem Namen "Debug"…` | You typed `-Debug`. PowerShell reserves that name; the switch is `-DebugBuild`. |
| `adb` not recognised | New terminal needed after the PATH change in §3. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Signed with a different key. `adb uninstall com.beachprotect`, then install again. |
| `INSTALL_FAILED_USER_RESTRICTED` (Xiaomi) | Developer options ▸ turn on **Install via USB**. |
| Build fails after editing Kotlin | `.\tools\build-apk.ps1 -Clean` |
| Gradle daemon stuck | `cd android; .\gradlew.bat --stop` |
| Phones cannot see each other | Both must be in the *same group* with the app open, and Bluetooth on. Give it ~10 s: the calm scan only listens for about half a second in every five. Arming is *not* required to appear in each other's group list. |
| No peers ever appear | Read the home screen's diagnostics chips in order. `broadcasting` unlit means this phone is invisible to the others — usually a chipset without BLE peripheral mode, which the app warns about. `no beacons heard` means nothing is arriving at all. `N packets, none in this group` means packets are arriving and being rejected: the two phones are in different groups, or running different builds. |
| Guard dies after a while | Grant the battery-optimisation exemption in onboarding. On Xiaomi/Huawei/Samsung also allow "background activity" / "auto-start" in the manufacturer's own battery settings. |
| Guard stops when I close the app | Working as intended since 1.4.0 — the guard runs while the app does. Leave BeachProtect open (backgrounded and screen off is fine) while it is guarding. |
| Alarm screen does not cover the lock screen | Android 14+: **Settings ▸ Full screen alarm permission** inside the app. |

---

## 12. Editing the project

Any editor works; the project is a normal Flutter app.

- **VS Code** — install the Flutter extension; `F5` runs on the connected phone.
- **Android Studio** — `File ▸ Open` the repository root. Gives the best Kotlin
  experience, which matters since the guard is Kotlin.

Layout:

```
lib/                    Flutter UI (Dart)
android/app/src/main/kotlin/com/beachprotect/
                        The guard itself (Kotlin)
android/app/src/test/   JUnit tests for the detection engine
tools/                  PowerShell build/install/test scripts
ARCHITECTURE.md         What is implemented and how — keep this updated
```
