<div align="center">

<br>

<img src="assets/header.svg" width="840" alt="Knox Guard Unlock">

<br>
<br>

![Platform](https://img.shields.io/badge/Android_13-Samsung-blue?style=flat-square)
![CVE](https://img.shields.io/badge/CVE--2024--34740-critical-red?style=flat-square)
![Status](https://img.shields.io/badge/v14c--HEAPDUMP-deployed-brightgreen?style=flat-square)
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

> [!IMPORTANT]
> **v14c-HEAPDUMP** deployed. KG unlock stable (proven 80+ min with v13, v14c inherits same 6-phase approach).
>
> New in v14c: FLAG_DEBUGGABLE flip enables `am dumpheap` on any running app from ADB shell. Used for Pokeball Plus BLE key extraction via Pokemon GO heap dump.

<br>
<br>

## The 6-Phase Approach

On every `BOOT_COMPLETED`, the payload executes inside `system_server` as UID 1000:

| Phase | Action | Effect |
|---|---|---|
| 0 | Wipe kgclient data | Delete cached lock commands (File.delete, no 3001) |
| 1 | Unlock sequence | 7-call reflection sequence + receiver unregistration |
| 2 | Firewall | Block kgclient network via NetworkManagementService |
| 3 | Watchers + Watchdog | FileObserver + ContentObserver + 10s force-stop watchdog |
| 4 | Force-stop kgclient | Kill via ActivityManager.forceStopPackage() |
| 5 | Neutralize KnoxGuardSeService | Null callbacks, unregister receivers, cancel alarms |
| 6 | Method enumeration | Log-only recon of KnoxGuardSeService |

<br>

## v14c Capabilities

### FLAG_DEBUGGABLE Flip

Flips `ApplicationInfo.flags |= FLAG_DEBUGGABLE` on any running app's ProcessRecord from inside system_server. This makes `am dumpheap` work from ADB shell without a debuggable build.

```bash
# Make Pokemon GO debuggable (in-memory only, survives until process restart)
adb shell am start -n com.example.abxoverflow.droppedapk/.MainActivity \
    --es action flip-debug --es target com.nianticlabs.pokemongo

# Dump its heap
adb shell am dumpheap com.nianticlabs.pokemongo /data/local/tmp/pogo.hprof

# Restore original flags
adb shell am start -n com.example.abxoverflow.droppedapk/.MainActivity \
    --es action restore-debug --es target com.nianticlabs.pokemongo
```

### Self-Update Mechanism

Update droppedapk without re-running the exploit (avoids fatal `adb install -r` UID corruption):

```bash
adb push new.apk /data/local/tmp/droppedapk-update.apk
adb shell am start -n com.example.abxoverflow.droppedapk/.MainActivity \
    --es action self-update
```

<br>
<br>

## Do not

> [!CAUTION]
> - **`pm uninstall` droppedapk before Stage 1** — removes BOOT_COMPLETED safety net. Phone bricks on reboot. (Incident: 2026-03-20)
> - **`adb install -r` on droppedapk** — corrupts UID mapping. Use self-update instead.
> - **`pm disable-user` on kgclient** — triggers error 8133 (abnormal detection).
> - **`pm clear` on kgclient** — triggers error 3001 (data cleared detection).
> - **Null `mLockSettingsService`** — causes `Utils.powerOff()` if retry alarm fires.
> - **Update firmware** — may patch the exploit.
> - **Sign into Samsung account** — gives Samsung a path to re-lock.

<br>
<br>

## Project structure

| Directory | Contents |
|---|---|
| `src/droppedapk/` | Payload source — v14c-HEAPDUMP (deployed) |
| `src/exploit/` | CVE-2024-34740 stage controller |
| `src/device-owner/` | QR provisioning APK |
| `apk/` | Pre-built binaries (v11 through v14c) |
| `assets/` | SVG visuals, QR code |
| `research/` | Agent research outputs |
| `docs/` | Full documentation, handoff, session logs |

<br>
<br>

## Lessons

- **KG state lives in TrustZone RPMB**, not in files or settings.
- **The re-lock comes from inside system_server**, not just kgclient. Neutralize the service itself.
- **Force-stop does NOT trigger error 8133.** Only `pm disable-user` does.
- **Direct File.delete() does NOT trigger error 3001.** Only `pm clear` does.
- **FLAG_DEBUGGABLE can be flipped at runtime** by modifying ProcessRecord.info.flags from UID 1000.
- **SELinux blocks IApplicationThread.dumpHeap() FD creation** from system_server context. Use flip-debug + `am dumpheap` from ADB shell instead.
- **NEVER uninstall droppedapk before running Stage 1.** The exploit's two-stage design requires the old payload to protect the phone through the first reboot.
- **CVE-2024-34740 is reliable and repeatable** on Samsung Android 13 with July 2023 SPL.

<br>
<br>

---

<br>

**Sources** — [AbxOverflow (CVE-2024-34740)](https://github.com/michalbednarski/AbxOverflow) · [Samsung Framework (decompiled)](https://github.com/488315/samsung_framework) · [Knox Guard Docs](https://docs.samsungknox.com/admin/knox-guard/)
