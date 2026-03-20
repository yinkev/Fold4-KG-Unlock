<div align="center">

<br>

<img src="assets/header.svg" width="840" alt="Knox Guard Unlock">

<br>
<br>

![Platform](https://img.shields.io/badge/Android_13-Samsung-blue?style=flat-square)
![CVE](https://img.shields.io/badge/CVE--2024--34740-critical-red?style=flat-square)
![Status](https://img.shields.io/badge/status-working-brightgreen?style=flat-square)
![macOS](https://img.shields.io/badge/macOS-Apple_Silicon-black?style=flat-square)

</div>

<br>
<br>

## Overview

Knox Guard is Samsung's enterprise device lock. It survives factory resets because its state is stored in TrustZone hardware. Samsung will not remove it for secondhand buyers. Every commercial tool is Windows-only or sold out.

This project unlocks a KG-locked Galaxy Z Fold 4 entirely from macOS, for free.

<br>
<br>

## The Chain

<br>

<img src="assets/chain.svg" width="840" alt="Exploit chain">

<br>
<br>

## Status

> [!NOTE]
> The unlock fires automatically on every boot via `BOOT_COMPLETED`. The phone is usable.
>
> kgclient may re-lock after a few minutes when it contacts Samsung's servers. Rebooting restores access instantly.

<br>
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

<br>

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

<br>

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

<br>

</details>

<br>
<br>

## What the unlock does

On `BOOT_COMPLETED`, the payload executes inside `system_server` as UID 1000:

<br>

| Call | Effect |
|---|---|
| `setRemoteLockToLockscreen(false)` | Clear KG overlay |
| `unlockCompleted()` | Mark unlock done |
| `unbindFromLockScreen()` | Unbind from keyguard |
| `tz_unlockScreen(0)` | RPMB: Locked(3) → Active(2) |
| `tz_resetRPMB(0)` | Reset RPMB state |
| `ADB_ENABLED = 1` | Re-enable USB debugging |
| `knox.kg.state = "Completed"` | Set system property |

<br>
<br>

## TA state machine

<br>

```mermaid
stateDiagram-v2
    direction LR
    [*] --> Prenormal
    Prenormal --> Checking : enroll
    Checking --> Active : enrolled
    Active --> Locked : server lock
    Locked --> Active : tz_unlockScreen(0)
    Active --> Completed : server complete
    Locked --> Completed : verifyCompleteToken

    classDef target fill:#eff6ff,stroke:#2563eb,color:#1e40af
    classDef danger fill:#fef2f2,stroke:#ef4444,color:#991b1b
    classDef success fill:#f0fdf4,stroke:#16a34a,color:#166534

    class Active target
    class Locked danger
    class Completed success
```

<br>

The device starts at **Locked** (red). We move it to **Active** (blue) via `tz_unlockScreen(0)`.

kgclient can push it back to Locked if it contacts Samsung's servers — that's the remaining problem.

<br>
<br>

## Do not

> [!CAUTION]
> - **`adb install -r` on droppedapk** — corrupts UID mapping. Bake changes into source before exploit.
> - **`pm disable-user` on kgclient** — triggers error 8133 (abnormal detection).
> - **`pm clear` on kgclient** — triggers error 3001 (data cleared detection).
> - **Update firmware** — may patch the exploit.
> - **Sign into Samsung account** — gives Samsung a path to re-lock.

<br>
<br>

## Project structure

<br>

| Directory | Contents |
|---|---|
| `src/droppedapk/` | Payload — runs as UID 1000 in system_server |
| `src/exploit/` | CVE-2024-34740 stage controller |
| `src/device-owner/` | QR provisioning APK |
| `apk/` | Pre-built binaries |
| `assets/` | SVG visuals, QR code |
| `research/` | Agent research outputs |
| `docs/` | Full documentation, handoff, session log |

<br>
<br>

## Remaining work

<br>

**kgclient cache deletion** — Delete the cached lock command from kgclient's data directory using `File.delete()` from UID 1000. Direct file deletion does not trigger error 3001. This stops kgclient from re-locking.

<br>

**Guardian thread** — A persistent background thread in the payload that re-runs the unlock every 30 seconds. Must be baked into the source before running the exploit. Catches any re-lock attempts.

<br>

Both are documented in detail in [`docs/FULL_DOCUMENTATION.md`](docs/FULL_DOCUMENTATION.md).

<br>
<br>

## Lessons

<br>

- **KG state lives in TrustZone RPMB**, not in files or settings. To change it, you must call `KnoxGuardNative` JNI methods from inside `system_server`.

- **The class isn't on the boot classpath.** Load it via `kgService.getClass().getClassLoader()`, not `Class.forName()`.

- **Active(2) is not the same as Completed(4).** kgclient can still receive a server command and transition back to Locked(3).

- **The local unlock works perfectly every time.** Samsung server contact is the real enemy.

- **CVE-2024-34740 is reliable and repeatable** on Samsung Android 13 with July 2023 SPL.

- **Never reinstall the droppedapk.** The UID corruption from `adb install -r` was the single biggest setback in this project.

<br>
<br>

---

<br>

**Sources** — [AbxOverflow (CVE-2024-34740)](https://github.com/michalbednarski/AbxOverflow) · [Samsung Framework (decompiled)](https://github.com/488315/samsung_framework) · [Knox Guard Docs](https://docs.samsungknox.com/admin/knox-guard/)
