# Samsung Galaxy Z Fold 4 -- Knox Guard Unlock

**A UID 1000 exploit chain that bypasses Samsung Knox Guard on a KG-locked Galaxy Z Fold 4, using CVE-2024-34740 to gain system-level code execution and manipulate TrustZone RPMB state.**

> Status: **Working but temporary** -- unlock fires on every boot via BOOT_COMPLETED, but kgclient may re-lock after ~3 minutes when it receives a cached server command. Reboot restores access.

---

## Device

| Field | Value |
|---|---|
| Model | Samsung Galaxy Z Fold 4 (SM-F936U1) |
| Serial | RFCW2006DLA |
| Firmware | F936U1UES3CWF3 |
| Android | 13 (API 33) |
| Security Patch | July 1, 2023 |
| Chipset | Qualcomm Snapdragon 8+ Gen 1 (SM8475) |
| Bootloader | Locked (BIT 3, SECURE DOWNLOAD ENABLE) |
| KG Status | Locked by carrier for "balance due for failure to meet trade-in terms" |
| IMEI | 356954474735469 |

---

## What is Knox Guard?

Knox Guard (KG) is Samsung's enterprise device management lock. When active, it displays a full-screen overlay blocking all interaction, disables USB debugging, and survives factory resets because its state is stored in TrustZone RPMB (hardware-backed storage). The lock is enforced at the TrustZone level -- software-only approaches (clearing data, disabling packages, blocking servers) are insufficient.

**Why this project exists:** The phone was purchased secondhand and arrived KG-locked. Samsung support will not remove KG for secondhand buyers. Every commercial unlock service for this model is sold out or Windows-only. macOS USB flashing tools (Heimdall, Thor, Odin) are all broken on Apple Silicon due to Apple's CDC driver claiming the USB interface.

---

## The Approach

The exploit chain has four stages:

```
QR Provisioning          CVE-2024-34740             Reflection               TrustZone
(get ADB access)    (get UID 1000 execution)    (call KG service)       (change RPMB state)
      |                      |                        |                       |
      v                      v                        v                       v
 Device Owner APK   -->  ABX overflow in       -->  Call methods on     -->  tz_unlockScreen(0)
 enables ADB via         install_sessions.xml       KnoxGuardSeService      moves TA state
 enterprise QR           drops APK as UID 1000      via Java reflection     from Locked(3)
 provisioning            inside system_server        from same process       to Active(2)
```

### Stage 1: Get ADB Access
KG blocks everything on the phone. But during factory reset setup, Android Enterprise QR provisioning can install a Device Owner app before KG fully activates. A custom Device Owner APK (`com.kyin.adbenab`) enables ADB, developer options, and pre-loads the Mac's ADB public key into `/data/misc/adb/adb_keys`.

### Stage 2: CVE-2024-34740 (AbxOverflow)
An integer overflow in Android's Binary XML serializer (`BinaryXmlSerializer.attributeBytesBase64()`) allows injecting arbitrary XML content into `install_sessions.xml`. This creates fake PackageInstaller sessions that write a new APK to `/data/app/dropped_apk/` and patch `packages.xml` to register it with `sharedUserId="1000"` (system). After two system_server crashes, the dropped APK runs as UID 1000 inside system_server.

### Stage 3: KG Service Exploitation
With UID 1000 code execution in system_server, Java reflection calls methods directly on `KnoxGuardSeService` -- bypassing all Binder permission checks because we're in the same process. Key calls: `setRemoteLockToLockscreen(false)`, `unlockCompleted()`, `unbindFromLockScreen()`.

### Stage 4: TrustZone RPMB Manipulation
`KnoxGuardNative` (in `services.jar` classes3.dex) provides JNI methods that talk directly to the TrustZone Trusted Application. `tz_unlockScreen(0)` changes the RPMB state from Locked(3) to Active(2), which persists through reboots and factory resets.

---

## Architecture

```
+------------------+     QR provisioning     +------------------+
|   Mac (macOS)    | ----------------------> |  Galaxy Z Fold 4 |
|                  |     USB / ADB           |  (Android 13)    |
+------------------+                         +------------------+
        |                                            |
        |  1. Serve DO APK (port 8888)               |
        |  2. Install exploit APK                    |
        |  3. Orchestrate stage 1 -> reboot          |
        |     -> stage 2 -> reboot                   |
        |                                            |
        v                                            v
+------------------+                         +------------------+
| run_exploit.sh   |                         | droppedapk       |
| (Mac-side brain) |                         | (UID 1000)       |
|                  |                         |                  |
| Polls ADB,      |                         | LaunchReceiver:  |
| launches stages, |                         |  BOOT_COMPLETED  |
| verifies result  |                         |  -> unlock chain |
+------------------+                         +------------------+
                                                     |
                                                     | Java reflection
                                                     v
                                             +------------------+
                                             | KnoxGuardSe-     |
                                             | Service          |
                                             | (system_server)  |
                                             +------------------+
                                                     |
                                                     | JNI via KnoxGuardNative
                                                     v
                                             +------------------+
                                             | TrustZone TA     |
                                             | (RPMB storage)   |
                                             +------------------+
                                             | State: 0=Pre     |
                                             |        1=Check   |
                                             |        2=Active  |
                                             |        3=Locked  |
                                             |        4=Complete|
                                             +------------------+
```

---

## Current Status

**What works:**
- droppedapk (v11-AUTOBOOT) is installed with correct UID 1000
- On BOOT_COMPLETED, LaunchReceiver runs the full unlock sequence
- KG overlay clears within seconds of boot
- ADB re-enables automatically
- Phone is usable after boot
- TA state is Active(2) in RPMB (persists through factory reset)

**What doesn't work yet:**
- kgclient reads a cached lock command from Samsung servers and re-locks after ~3 minutes
- When kgclient re-locks, it disables ADB and shows the KG overlay again
- Rebooting triggers the unlock sequence again, making the phone usable for another ~3 minutes

**The 30-minute monitor log** (`30min_monitor.log`) confirms the unlock holds with `kg=Completed` and `adb=device` for sustained periods after fresh boot.

---

## Quick Start

### Prerequisites
- macOS with Homebrew
- ADB: `/opt/homebrew/bin/adb`
- Java 17: `/opt/homebrew/opt/openjdk@17`
- Android SDK: `/opt/homebrew/share/android-commandlinetools`
- WiFi network: SSID `coconutWater`, password `9093255140`

### Step 1: Factory Reset the Phone
Hold Power + Volume Down to enter recovery. Select "Wipe data/factory reset".

### Step 2: Serve the Device Owner APK
```bash
cd /Users/kyin/Downloads/serve_apk
python3 -m http.server 8888
```

### Step 3: QR Provision
On the Setup Wizard WiFi screen, tap 6 times to open the QR scanner. Scan `/Users/kyin/Downloads/serve_apk/provision_qr.png`. The phone will download and install the Device Owner APK, which enables ADB.

### Step 4: Verify ADB
```bash
/opt/homebrew/bin/adb devices
# RFCW2006DLA    device
```

### Step 5: Build and Run the Exploit
```bash
cd /Users/kyin/Downloads/AbxOverflow
export JAVA_HOME=/opt/homebrew/opt/openjdk@17

# Build droppedapk (the payload that will run as UID 1000)
./gradlew :droppedapk:assembleRelease
bash utils/moveapk.sh

# Build the exploit app
./gradlew :app:assembleDebug

# Install and run
/opt/homebrew/bin/adb install -r app/build/outputs/apk/debug/app-debug.apk
bash run_exploit.sh
```

### Step 6: Verify Success
```bash
/opt/homebrew/bin/adb shell pm list packages | grep droppedapk
# package:com.example.abxoverflow.droppedapk

/opt/homebrew/bin/adb shell dumpsys package com.example.abxoverflow.droppedapk | grep userId
# userId=1000
```

### Step 7: Reboot and Watch
```bash
/opt/homebrew/bin/adb reboot
```
After reboot, droppedapk's LaunchReceiver fires on BOOT_COMPLETED and runs the full unlock sequence automatically. KG overlay flashes briefly then clears.

---

## File Inventory

### Project Snapshot (`/Users/kyin/Projects/Fold4/`)
| File | Description |
|---|---|
| `README.md` | This file |
| `FULL_DOCUMENTATION.md` | Exhaustive technical documentation |
| `LLM_CONTEXT.md` | Condensed context for LLM sessions |
| `LaunchReceiver.java` | v11-AUTOBOOT boot receiver (the working code) |
| `MainActivity.java` | droppedapk UI + KG service exploitation |
| `ExploitMainActivity.java` | AbxOverflow exploit controller |
| `AdbEnableReceiver.java` | Device Owner APK source |
| `droppedapk-v11-autoboot-fresh.apk` | Clean droppedapk with correct UID 1000 |
| `droppedapk-v12-stable.apk` | Labeled v12 but contains v11-AUTOBOOT code |
| `exploit-app-fresh.apk` | Built exploit app |
| `KG_UNLOCK_GUIDE.md` | Original walkthrough |
| `HANDOFF.md` | Session handoff state |
| `session_log.txt` | Raw ADB + logcat capture (~29 MB) |
| `kg_research_agent_output.txt` | Research: KnoxGuardNative, TA state machine |
| `kg_8133_research_output.txt` | Research: 8133 bitfield, IntegritySeUtil |
| `kg_kgclient_research.txt` | Research: kgclient behavior, cached data |

### Exploit Source (`/Users/kyin/Downloads/AbxOverflow/`)
| File | Description |
|---|---|
| `app/` | Exploit app (CVE-2024-34740 implementation) |
| `droppedapk/` | Payload APK (runs as UID 1000 in system_server) |
| `run_exploit.sh` | Mac-side orchestrator script |
| `utils/moveapk.sh` | Copies built droppedapk into exploit app assets |
| `droppedapk-v12-stable.apk` | Pre-built droppedapk |
| `droppedapk-v15-recon.apk` | Recon build |

### Tools
| Tool | Path |
|---|---|
| Device Owner APK | `/Users/kyin/adb_enabler/adbenab.apk` |
| APK HTTP server | `/Users/kyin/Downloads/serve_apk/` (port 8888) |
| QR code | `/Users/kyin/Downloads/serve_apk/provision_qr.png` |
| DNS blocker | `/Users/kyin/kg_block_dns.py` |

---

## Known Issues

1. **kgclient re-lock (~3 min):** kgclient's BOOT_COMPLETED receiver contacts Samsung servers (or reads cached policy) and re-locks the device, re-enabling the KG overlay and disabling ADB.

2. **`adb install -r` corrupts UID mapping:** Reinstalling droppedapk via `adb install -r` after the exploit can cause files to be owned by UID 0 instead of UID 1000, breaking the app. The fix is to bake all code changes into the droppedapk source before running the exploit -- never use `adb install -r` on droppedapk after it is installed.

3. **Samsung full reboot vs userspace restart:** The original AbxOverflow PoC relies on a background process surviving a userspace restart. Samsung does a full reboot instead, killing all processes. The Mac-side `run_exploit.sh` orchestrator works around this.

---

## Future Work

1. **Bake guardian thread into droppedapk source:** Add a persistent background thread that re-runs the unlock sequence every 5-30 seconds, countering kgclient's re-lock. This must be compiled into the droppedapk source before running the exploit (not added via `adb install -r`).

2. **Delete kgclient cached data:** Use `File.delete()` from UID 1000 to remove kgclient's cached lock command files. Direct file deletion does NOT trigger error 3001 (only `pm clear` does).

3. **Disable non-essential kgclient components:** Beyond the 6 components checked by IntegritySeUtil, kgclient has additional broadcast receivers (e.g., CONNECTIVITY_CHANGE) that trigger re-lock. Disabling these specific receivers from UID 1000 may prevent re-lock without triggering 8133.

4. **CVE-2024-34664 multi-user bypass:** Theoretical approach using Samsung's multi-user implementation bug (pre-October 2024 SPL). Untested.

5. **NetworkPolicyManager:** Block kgclient's network access programmatically from UID 1000. Initial attempts failed but the API surface hasn't been fully explored.

---

## Lessons Learned

1. **KG state lives in TrustZone RPMB.** Software settings are just cache. To permanently change KG, you must talk to the TrustZone TA via KnoxGuardNative JNI methods.

2. **Never `pm disable-user` on kgclient.** Triggers error 8133, a dynamically computed bitfield. The damage is visible on the KG screen and requires re-enabling kgclient + all 6 components to fix.

3. **Never `pm clear` on kgclient.** Triggers error 3001 (data cleared detection). Also visible and hard to recover from.

4. **Never `adb install -r` on droppedapk.** Corrupts UID mapping -- files end up owned by UID 0 instead of UID 1000. Bake all changes into source before running the exploit.

5. **CVE-2024-34740 is reliable and repeatable** on Samsung Android 13 with July 2023 SPL. The exploit works on first try if the orchestration is correct.

6. **KnoxGuardNative is loaded via the service's classloader**, not the boot classpath. Use `kgService.getClass().getClassLoader().loadClass()`, not `Class.forName()`.

7. **Active(2) is not the same as Completed(4).** Active means KG is provisioned but not currently locking -- kgclient can still receive a lock command and transition back to Locked(3). The true goal is Completed(4) or Prenormal(0).

8. **Samsung server contact is the real enemy.** The local unlock works perfectly. The problem is kgclient downloading a fresh lock command. The solution is either blocking server contact permanently or deleting the cached command.

---

## Timeline

| Date | Milestone |
|---|---|
| March 18, 2026 | Initial recon. Dead ends: AT modem, Heimdall, Thor, emergency dialer |
| March 19, AM | QR provisioning breakthrough. ADB access achieved |
| March 19, PM | CVE-2024-34740 exploit working. UID 1000 code execution |
| March 19, PM | TrustZone RPMB state changed: Locked(3) -> Active(2) |
| March 19, EVE | Auto-unlock on boot working. kgclient re-lock identified as remaining problem |

---

## Credits

- [AbxOverflow](https://github.com/michalbednarski/AbxOverflow) by Michal Bednarski -- the CVE-2024-34740 exploit
- Samsung Knox Guard reverse engineering via reflection from UID 1000
- QR enterprise provisioning technique for ADB access on KG-locked devices
