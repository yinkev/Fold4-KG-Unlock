# LLM Context: Samsung Galaxy Z Fold 4 Knox Guard Unlock

**Read this first when picking up this project.** This is a condensed context document for LLM sessions. For full details, see `FULL_DOCUMENTATION.md`.

---

## Device

| Key | Value |
|---|---|
| Model | SM-F936U1 (Galaxy Z Fold 4, US unlocked) |
| Serial | RFCW2006DLA |
| IMEI | 356954474735469 |
| Firmware | F936U1UES3CWF3 |
| Android | 13, API 33 |
| SPL | July 1, 2023 (vulnerable to CVE-2024-34740) |
| Chipset | Snapdragon 8+ Gen 1 (SM8475) |
| Bootloader | Locked (BIT 3) |
| KG State | Active(2) in RPMB, `knox.kg.state=Completed` via sysprop |
| OTP Fuse | `ro.boot.kg.bit` = 01 (permanent, cannot change) |
| WiFi | SSID: `coconutWater` / Pass: `9093255140` |

---

## What is Installed on the Phone Right Now

| Component | Package | Status |
|---|---|---|
| Dropped APK | `com.example.abxoverflow.droppedapk` | UID 1000, runs in system_server, **v13-NEUTRALIZE** |
| Device Owner | `com.kyin.adbenab` | Active Device Owner, enables ADB |
| kgclient | `com.samsung.android.kgclient` | Enabled (must stay enabled to avoid 8133) |
| TA State (RPMB) | N/A | Active(2), changed from Locked(3) |

**On boot (v13-NEUTRALIZE):** droppedapk's `LaunchReceiver` runs on `BOOT_COMPLETED` and executes a 6-phase sequence:

1. **Phase 0: Wipe** kgclient cached data via File.delete() (no 3001 trigger)
2. **Phase 1: Unlock** via KnoxGuardSeService reflection (7-call sequence + receiver unregistration)
3. **Phase 2: Firewall** block kgclient network via NetworkManagementService
4. **Phase 3: Watchers** — FileObserver on kgclient data + ContentObserver on ADB_ENABLED + 10s force-stop watchdog
5. **Phase 4: Force-stop** kgclient immediately
6. **Phase 5: Neutralize** KnoxGuardSeService internals (null callbacks, unregister receivers, cancel alarms)

Plus Phase 6 (method enumeration, log only).

**Result:** KG=Completed, 80+ minutes stable. kgclient respawns every ~10s but cannot re-lock. All re-lock paths severed.

---

## What is on the Mac

### Tools
| Tool | Path |
|---|---|
| ADB | `/opt/homebrew/bin/adb` |
| Java 17 | `/opt/homebrew/opt/openjdk@17` |
| Android SDK | `/opt/homebrew/share/android-commandlinetools` |
| Build Tools | `/opt/homebrew/share/android-commandlinetools/build-tools/33.0.2` |

### Project Files
| Path | Description |
|---|---|
| `/Users/kyin/Downloads/AbxOverflow/` | Exploit source (CVE-2024-34740) |
| `/Users/kyin/Downloads/AbxOverflow/droppedapk/` | Payload APK source (UID 1000) — currently v14-HEAPDUMP |
| `/Users/kyin/Downloads/AbxOverflow/run_exploit.sh` | Mac-side orchestrator |
| `/Users/kyin/adb_enabler/` | Device Owner APK source |
| `/Users/kyin/adb_enabler/adbenab.apk` | Built DO APK |
| `/Users/kyin/Downloads/serve_apk/` | HTTP server dir (port 8888) |
| `/Users/kyin/Downloads/serve_apk/provision_qr.png` | QR code for provisioning |
| `/Users/kyin/kg_block_dns.py` | DNS blocker for Samsung KG domains |
| `/Users/kyin/Projects/Fold4/` | Documentation + snapshot |

### Key Source Files
| File | What It Does |
|---|---|
| `droppedapk/.../LaunchReceiver.java` | BOOT_COMPLETED receiver. 6-phase unlock + neutralize as UID 1000 |
| `droppedapk/.../MainActivity.java` | UI + 10-step KG service exploitation + self-update + heap dump + PoGO file listing |
| `app/.../MainActivity.java` | Exploit controller. Accepts `--ei stage 1` or `--ei stage 2` |
| `app/.../Main.java` | Exploit core: stage1 (ABX injection), stage2 (packages.xml patch) |
| `app/.../AbxInjector.java` | Constructs raw ABX tokens for injection |

---

## Commands to Check State

```bash
# Is the phone connected?
/opt/homebrew/bin/adb devices

# What KG state?
/opt/homebrew/bin/adb shell getprop ro.boot.kg         # 0x2=Active, 0x3=Locked
/opt/homebrew/bin/adb shell getprop knox.kg.state       # Human-readable
/opt/homebrew/bin/adb shell getprop ro.boot.kg.bit      # 01 = KG fuse set (permanent)

# Is droppedapk installed?
/opt/homebrew/bin/adb shell pm list packages | grep droppedapk

# Is droppedapk running as UID 1000?
/opt/homebrew/bin/adb shell dumpsys package com.example.abxoverflow.droppedapk | grep userId

# Is kgclient enabled? (must be enabled)
/opt/homebrew/bin/adb shell pm list packages -e | grep kgclient

# Monitor KG state in real-time
while true; do echo "$(date '+%H:%M:%S') kg=$(/opt/homebrew/bin/adb shell getprop knox.kg.state 2>/dev/null) adb=$(/opt/homebrew/bin/adb get-state 2>/dev/null)"; sleep 3; done

# Launch droppedapk manually
/opt/homebrew/bin/adb shell am start -n com.example.abxoverflow.droppedapk/.MainActivity --ei action 36 --activity-clear-task

# Check droppedapk logs
/opt/homebrew/bin/adb logcat -d | grep -E "AbxDropped|AbxBootUnlock"

# Self-update (v14+)
/opt/homebrew/bin/adb push new-apk.apk /data/local/tmp/droppedapk-update.apk
/opt/homebrew/bin/adb shell am start -n com.example.abxoverflow.droppedapk/.MainActivity --es action self-update

# Heap dump (v14+, Pokemon GO must be running)
/opt/homebrew/bin/adb shell am start -n com.example.abxoverflow.droppedapk/.MainActivity --es action dump-heap

# List PoGO files (v14+)
/opt/homebrew/bin/adb shell am start -n com.example.abxoverflow.droppedapk/.MainActivity --es action list-files
```

---

## What NOT to Do

### 1. NEVER `pm disable-user` on kgclient
**Why:** Triggers error 8133 (abnormal detection). Disables app + all 6 components. Requires UID 1000 to fix.

### 2. NEVER `pm clear` on kgclient
**Why:** Triggers error 3001 (data cleared detection). Hard lock with visible error code.

### 3. NEVER `adb install -r` on droppedapk After Exploit
**Why:** Corrupts UID mapping. Files end up owned by UID 0. Use self-update mechanism instead.

### 4. NEVER Null `mLockSettingsService`
**Why:** If retry alarm fires and `setRetryLock` fails, `Utils.powerOff()` shuts down the phone.

### 5. NEVER Factory Reset Without Good Reason
**Why:** Requires full exploit re-run.

### 6. NEVER Assume TX 36 is "Disable KG"
TX 36 on `knoxguard_service` is `isVpnExceptionRequired()` (read-only). TX 22 is `unlockScreen`. Both require UID 1000.

---

## Current State: v13-NEUTRALIZE (What Works)

### All Working
- 6-phase auto-unlock on every boot
- File.delete() on kgclient data: safe, no 3001 trigger
- Firewall blocks kgclient network access
- FileObserver instantly deletes new kgclient files
- ContentObserver keeps ADB alive through everything
- ALL BroadcastReceivers on KnoxGuardSeService unregistered
- mRemoteLockMonitorCallback nulled
- RETRY_LOCK and PROCESS_CHECK alarms cancelled
- Force-stop watchdog kills kgclient every 10s
- **80+ minutes stable, survived 37-min danger zone**

### Confirmed Non-Issues
- SELinux: UID 1000 has full system_server context
- DEFEX: Does not block UID 1000 operations
- Battery: Watchdog polling negligible
- Error 8133: NOT triggered by force-stop (only pm disable-user)
- Error 3001: NOT triggered by File.delete() (only pm clear)

### Remaining Goals (not blockers)
1. **Achieve TA state Completed(4)** — Currently Active(2). True permanent fix, but v13 is functionally equivalent.
2. **Deploy v14-HEAPDUMP** — For Pokeball Plus key extraction via Pokemon GO heap dump.

---

## How the Exploit Works (Summary)

1. **QR provisioning** installs Device Owner APK that enables ADB
2. **CVE-2024-34740** overflows ABX binary XML length field (65536 -> 0 via `writeShort`)
3. **Stage 1:** Injects fake PackageInstaller sessions into `install_sessions.xml`, crashes system_server
4. **Stage 2:** Uses fake sessions to drop APK to `/data/app/dropped_apk/` and write patched `packages-backup.xml` that registers it as UID 1000, crashes system_server
5. **After reboot:** droppedapk is loaded into system_server as UID 1000
6. **LaunchReceiver** fires on `BOOT_COMPLETED`, executes 6-phase unlock + neutralize
7. **KG overlay clears**, ADB re-enables, phone is permanently usable

### Samsung-Specific Quirks
- Samsung does **full reboot** (not userspace restart) on system_server crash
- Must use `--activity-clear-task` when launching exploit app after reboot
- `KnoxGuardNative` is in `services.jar` classes3.dex -> must use service's classloader
- Native methods return `KgErrWrapper` objects -> need reflection to unwrap

---

## Build and Run from Scratch

```bash
# 1. Serve DO APK
cd /Users/kyin/Downloads/serve_apk && python3 -m http.server 8888

# 2. Factory reset phone, QR provision (scan provision_qr.png on WiFi setup screen)

# 3. Verify ADB
/opt/homebrew/bin/adb devices  # should show RFCW2006DLA

# 4. Build exploit
cd /Users/kyin/Downloads/AbxOverflow
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :droppedapk:assembleRelease
bash utils/moveapk.sh
./gradlew :app:assembleDebug

# 5. Install and run
/opt/homebrew/bin/adb install -r app/build/outputs/apk/debug/app-debug.apk
bash run_exploit.sh

# 6. Verify droppedapk
/opt/homebrew/bin/adb shell pm list packages | grep droppedapk
/opt/homebrew/bin/adb shell dumpsys package com.example.abxoverflow.droppedapk | grep userId

# 7. Reboot to trigger auto-unlock
/opt/homebrew/bin/adb reboot
```

---

## File Paths Quick Reference

```
EXPLOIT SOURCE:
  /Users/kyin/Downloads/AbxOverflow/app/src/main/java/com/example/abxoverflow/
    MainActivity.java          -- exploit controller (stage 1/2)
    Main.java                  -- exploit core (ABX injection, packages.xml patch)
    AbxInjector.java           -- ABX token construction
    RebootBackgroundRunner.java -- unused on Samsung (full reboot kills it)

PAYLOAD SOURCE (v14-HEAPDUMP — current dev):
  /Users/kyin/Downloads/AbxOverflow/droppedapk/src/main/java/com/example/abxoverflow/droppedapk/
    MainActivity.java          -- KG service exploitation + self-update + heap dump + PoGO files
    LaunchReceiver.java        -- BOOT_COMPLETED 6-phase unlock + neutralize
  /Users/kyin/Downloads/AbxOverflow/droppedapk/src/main/AndroidManifest.xml

DEVICE OWNER:
  /Users/kyin/adb_enabler/
    src/com/kyin/adbenab/AdbEnableReceiver.java
    src/com/kyin/adbenab/ProvisioningActivity.java
    AndroidManifest.xml
    adbenab.apk

TOOLS:
  /Users/kyin/Downloads/AbxOverflow/run_exploit.sh
  /Users/kyin/Downloads/AbxOverflow/utils/moveapk.sh
  /Users/kyin/kg_block_dns.py
  /Users/kyin/Downloads/serve_apk/provision_qr.png

DOCUMENTATION:
  /Users/kyin/Projects/Fold4/README.md
  /Users/kyin/Projects/Fold4/docs/FULL_DOCUMENTATION.md
  /Users/kyin/Projects/Fold4/docs/LLM_CONTEXT.md
  /Users/kyin/Projects/Fold4/docs/HANDOFF.md
  /Users/kyin/Projects/Fold4/docs/KG_UNLOCK_GUIDE.md

RESEARCH:
  /Users/kyin/Projects/Fold4/research/01-knoxguard-native-ta-state.txt
  /Users/kyin/Projects/Fold4/research/02-error-8133-integrity-check.txt
  /Users/kyin/Projects/Fold4/research/03-kgclient-cache-behavior.txt

PREBUILT APKS:
  /Users/kyin/Projects/Fold4/apk/droppedapk-v11-autoboot-fresh.apk  -- v11, clean UID 1000
  /Users/kyin/Projects/Fold4/apk/droppedapk-v12-stable.apk          -- v12 label, v11 code
  /Users/kyin/Projects/Fold4/apk/droppedapk-v12-permanent.apk       -- v12-PERMANENT, 4-phase
  /Users/kyin/Projects/Fold4/apk/droppedapk-v13-forcestop.apk       -- v13 intermediate
  /Users/kyin/Projects/Fold4/apk/droppedapk-v13-neutralize.apk      -- v13-NEUTRALIZE, 6-phase (DEPLOYED)
  /Users/kyin/Projects/Fold4/apk/exploit-app-fresh.apk               -- exploit app (v11 era)
  /Users/kyin/Projects/Fold4/apk/exploit-app-v12-permanent.apk       -- exploit app (v12)
  /Users/kyin/Projects/Fold4/apk/exploit-app-v13-forcestop.apk       -- exploit app (v13 intermediate)
  /Users/kyin/Projects/Fold4/apk/exploit-app-v13-neutralize.apk      -- exploit app (v13-NEUTRALIZE)
  /Users/kyin/Projects/Fold4/apk/device-owner.apk                    -- QR provisioning DO
```

---

## KG State Machine Quick Reference

```
Prenormal(0) -- KG not activated. Device is free.
Checking(1)  -- Checking with Samsung server.
Active(2)    -- Provisioned, not locked. << CURRENT STATE
Locked(3)    -- Actively locked. This is what we started at.
Completed(4) -- Obligations met. Device permanently free. << ASPIRATIONAL GOAL
Error(5)     -- Error state.
```

**Current:** Active(2) in RPMB + v13-NEUTRALIZE = functionally unlocked permanently.
**Aspirational:** Completed(4) or Prenormal(0) for true hardware-level unlock.

---

## Session Pickup Checklist

When starting a new session on this project:

1. Check if the phone is connected: `/opt/homebrew/bin/adb devices`
2. Check KG state: `/opt/homebrew/bin/adb shell getprop knox.kg.state`
3. Check if droppedapk is installed: `/opt/homebrew/bin/adb shell pm list packages | grep droppedapk`
4. Check version: `/opt/homebrew/bin/adb logcat -d | grep "BUILD v" | tail -1`
5. If droppedapk is NOT installed: exploit needs to be re-run (see Build and Run section)
6. If ADB is not connected: phone may be KG-locked. Reboot first. If still dead, factory reset and re-provision.
7. For code changes: use self-update mechanism (v13+), NOT `adb install -r`.
8. Read HANDOFF.md for current goals and development status.
