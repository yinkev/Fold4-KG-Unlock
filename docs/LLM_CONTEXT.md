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
| KG State | Locked by carrier for trade-in balance |
| OTP Fuse | `ro.boot.kg.bit` = 01 (permanent, cannot change) |
| WiFi | SSID: `coconutWater` / Pass: `9093255140` |

---

## What is Installed on the Phone Right Now

| Component | Package | Status |
|---|---|---|
| Dropped APK | `com.example.abxoverflow.droppedapk` | UID 1000, runs in system_server |
| Device Owner | `com.kyin.adbenab` | Active Device Owner, enables ADB |
| kgclient | `com.samsung.android.kgclient` | Enabled (must stay enabled to avoid 8133) |
| TA State (RPMB) | N/A | Active(2), changed from Locked(3) |

**On boot:** droppedapk's `LaunchReceiver` runs on `BOOT_COMPLETED`, executes the unlock sequence (clears KG overlay, re-enables ADB, sets `knox.kg.state=Completed`). Phone is usable. After ~3 min, kgclient reads cached lock command and re-locks.

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
| `/Users/kyin/Downloads/AbxOverflow/droppedapk/` | Payload APK source (UID 1000) |
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
| `droppedapk/.../LaunchReceiver.java` | BOOT_COMPLETED receiver. Runs unlock sequence as UID 1000 |
| `droppedapk/.../MainActivity.java` | UI + 10-step KG service exploitation via reflection |
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
```

---

## What NOT to Do

These are hard-won lessons. Each one cost hours of debugging or a factory reset.

### 1. NEVER `pm disable-user` on kgclient
```bash
# DO NOT RUN THIS:
adb shell pm disable-user --user 0 com.samsung.android.kgclient
```
**Why:** Triggers error 8133 (abnormal detection). Disables app + all 6 components. Shows error on KG screen. Requires UID 1000 to fix.

### 2. NEVER `pm clear` on kgclient
```bash
# DO NOT RUN THIS:
adb shell pm clear com.samsung.android.kgclient
```
**Why:** Triggers error 3001 (data cleared detection). Hard lock with visible error code.

### 3. NEVER `adb install -r` on droppedapk After Exploit
```bash
# DO NOT RUN THIS after the exploit has installed droppedapk:
adb install -r droppedapk-debug.apk
```
**Why:** Corrupts UID mapping. Files end up owned by UID 0 instead of UID 1000. The app crashes or loses privileges. Bake ALL code changes into droppedapk source BEFORE running the exploit.

### 4. NEVER Factory Reset Without Good Reason
**Why:** Samsung server re-locks the device on next internet connection. The TA state (Active/2) survives, but kgclient gets a fresh lock command and transitions back to Locked(3).

### 5. NEVER Assume TX 36 is "Disable KG"
TX 36 on `knoxguard_service` is `isVpnExceptionRequired()` (read-only). TX 22 is `unlockScreen`. Both require UID 1000 regardless.

---

## The Two Remaining Problems

### Problem 1: kgclient Re-Lock (~3 min after boot)

**What happens:** kgclient's `BOOT_COMPLETED` receiver reads a cached lock command from its local data and calls `lockScreen()` on KnoxGuardSeService. This re-enables the KG overlay and disables ADB.

**Solution A -- Guardian Thread:** Bake a persistent background thread into `LaunchReceiver.java` that re-runs the unlock sequence every 5-30 seconds. This counters kgclient's re-lock continuously. MUST be compiled into source before running the exploit (no `adb install -r`).

**Solution B -- Delete Cached Data:** From UID 1000, use `File.delete()` to remove kgclient's cached lock command files under `/data/data/com.samsung.android.kgclient/`. Direct file deletion does NOT trigger error 3001 (only `pm clear` does).

**Solution C -- Disable CONNECTIVITY_CHANGE Receiver:** kgclient has additional receivers beyond the 6 checked by IntegritySeUtil. Disabling the `CONNECTIVITY_CHANGE` receiver may prevent kgclient from contacting Samsung servers and receiving lock commands.

### Problem 2: No Permanent RPMB State Change

**What happens:** TA state is Active(2), not Completed(4) or Prenormal(0). Active means "KG provisioned but not currently locked" -- kgclient can still transition back to Locked(3) if it receives a server command.

**Solution:** Find a way to set TA state to Completed(4). The `tz_unlockScreen(0)` call moved from 3->2 but not to 4. May require a specific sequence of TrustZone calls, or a different method entirely. The method `tz_resetRPMB` succeeded (err=0) but didn't change state to 0 or 4.

---

## How the Exploit Works (Summary)

1. **QR provisioning** installs Device Owner APK that enables ADB
2. **CVE-2024-34740** overflows ABX binary XML length field (65536 -> 0 via `writeShort`)
3. **Stage 1:** Injects fake PackageInstaller sessions into `install_sessions.xml`, crashes system_server
4. **Stage 2:** Uses fake sessions to drop APK to `/data/app/dropped_apk/` and write patched `packages-backup.xml` that registers it as UID 1000, crashes system_server
5. **After reboot:** droppedapk is loaded into system_server as UID 1000
6. **LaunchReceiver** fires on `BOOT_COMPLETED`, uses Java reflection to call `KnoxGuardSeService` methods and `KnoxGuardNative` TrustZone calls
7. **KG overlay clears**, ADB re-enables, phone is usable

### Samsung-Specific Quirks
- Samsung does **full reboot** (not userspace restart) on system_server crash -> requires Mac-side orchestration (`run_exploit.sh`)
- Must use `--activity-clear-task` when launching exploit app after reboot
- `KnoxGuardNative` is in `services.jar` classes3.dex -> must use service's classloader, not `Class.forName()`
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

PAYLOAD SOURCE:
  /Users/kyin/Downloads/AbxOverflow/droppedapk/src/main/java/com/example/abxoverflow/droppedapk/
    MainActivity.java          -- KG service exploitation (10-step sequence)
    LaunchReceiver.java        -- BOOT_COMPLETED auto-unlock (v11-AUTOBOOT)
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
  /Users/kyin/Projects/Fold4/FULL_DOCUMENTATION.md
  /Users/kyin/Projects/Fold4/LLM_CONTEXT.md
  /Users/kyin/Projects/Fold4/HANDOFF.md
  /Users/kyin/Projects/Fold4/KG_UNLOCK_GUIDE.md

RESEARCH:
  /Users/kyin/Downloads/AbxOverflow/kg_research_agent_output.txt
  /Users/kyin/Downloads/AbxOverflow/kg_8133_research_output.txt
  /Users/kyin/Downloads/AbxOverflow/kg_kgclient_research.txt

PREBUILT APKS:
  /Users/kyin/Projects/Fold4/droppedapk-v11-autoboot-fresh.apk  -- clean, correct UID 1000
  /Users/kyin/Projects/Fold4/droppedapk-v12-stable.apk          -- v12 label, v11 code
  /Users/kyin/Projects/Fold4/exploit-app-fresh.apk               -- exploit app
```

---

## KG State Machine Quick Reference

```
Prenormal(0) -- KG not activated. Device is free.
Checking(1)  -- Checking with Samsung server.
Active(2)    -- Provisioned, not locked. << CURRENT STATE
Locked(3)    -- Actively locked. This is what we started at.
Completed(4) -- Obligations met. Device permanently free. << GOAL
Error(5)     -- Error state.
```

**Current:** Active(2) in RPMB. Survives reboots and factory resets.
**Goal:** Completed(4) or Prenormal(0) for permanent unlock.
**Interim:** Keep Active(2) + prevent kgclient from re-locking.

---

## Session Pickup Checklist

When starting a new session on this project:

1. Check if the phone is connected: `/opt/homebrew/bin/adb devices`
2. Check KG state: `/opt/homebrew/bin/adb shell getprop ro.boot.kg`
3. Check if droppedapk is installed: `/opt/homebrew/bin/adb shell pm list packages | grep droppedapk`
4. If droppedapk is NOT installed: the exploit needs to be re-run (see Build and Run section)
5. If ADB is not connected: phone may be KG-locked. Factory reset and re-provision.
6. Read the "Two Remaining Problems" section above for current goals.
7. All code changes to droppedapk MUST be made in source before running the exploit. No `adb install -r`.
