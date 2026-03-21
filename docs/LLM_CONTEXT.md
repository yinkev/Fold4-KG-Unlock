# LLM Context: Samsung Galaxy Z Fold 4 Knox Guard Unlock

**Read this first when picking up this project.**

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
| OTP Fuse | `ro.boot.kg.bit` = 01 (permanent) |
| WiFi | SSID: `coconutWater` / Pass: `9093255140` |

---

## What is Installed on the Phone

| Component | Package | Status |
|---|---|---|
| Dropped APK | `com.example.abxoverflow.droppedapk` | UID 1000, system_server, **v14c-HEAPDUMP** |
| Device Owner | `com.kyin.adbenab` | Active Device Owner |
| kgclient | `com.samsung.android.kgclient` | Enabled (must stay enabled) |
| TA State (RPMB) | N/A | Active(2) |

**On boot (v14c):** 6-phase unlock sequence fires on BOOT_COMPLETED:
1. Wipe kgclient data (File.delete)
2. Unlock via KnoxGuardSeService reflection
3. Firewall block kgclient network
4. Watchers + 10s force-stop watchdog
5. Force-stop kgclient
6. Neutralize KnoxGuardSeService internals

---

## v14c Commands

```bash
# Check state
adb shell getprop knox.kg.state          # Should be "Completed"
adb shell getprop ro.boot.kg             # 0x2=Active
adb logcat -d | grep "BUILD v"           # Version check

# Manual unlock
adb shell am start -n com.example.abxoverflow.droppedapk/.MainActivity --ei action 36 --activity-clear-task

# FLAG_DEBUGGABLE flip (makes any app dumpable)
adb shell am start -n com.example.abxoverflow.droppedapk/.MainActivity --es action flip-debug --es target <package>
adb shell am dumpheap <package> /data/local/tmp/dump.hprof
adb shell am start -n com.example.abxoverflow.droppedapk/.MainActivity --es action restore-debug --es target <package>

# List PoGO files + copy prefs to /data/system/heapdump/
adb shell am start -n com.example.abxoverflow.droppedapk/.MainActivity --es action list-files

# Self-update (bypasses adb install -r UID corruption)
adb push new.apk /data/local/tmp/droppedapk-update.apk
adb shell am start -n com.example.abxoverflow.droppedapk/.MainActivity --es action self-update
```

---

## What NOT to Do

1. **NEVER `pm uninstall` droppedapk before Stage 1** — Removes BOOT_COMPLETED safety net. Phone bricks. (Incident: 2026-03-20)
2. **NEVER `adb install -r` on droppedapk** — Corrupts UID mapping. Use self-update.
3. **NEVER `pm disable-user` on kgclient** — Triggers error 8133.
4. **NEVER `pm clear` on kgclient** — Triggers error 3001.
5. **NEVER null `mLockSettingsService`** — Causes `Utils.powerOff()`.
6. **NEVER update firmware or sign into Samsung account.**

---

## Key Technical Findings

### SELinux Boundaries (verified against AOSP android-13.0.0_r75)
- system_server CAN read/write `apk_tmp_file` (`/data/app/vmdl*.tmp/`)
- system_server CANNOT read `shell_data_file` (`/data/local/tmp/`)
- system_server CANNOT `connectto` shell's abstract Unix sockets
- Shell CAN stage via `pm install-create/write` (app_domain + binder_call)
- PackageInstaller sessions can sit uncommitted indefinitely

### FLAG_DEBUGGABLE Flip
- `ProcessRecord.info.flags |= 0x2` makes `am dumpheap` work from ADB shell
- In-memory only, no disk persistence
- No SELinux issues (modification within system_server address space)
- Direct `IApplicationThread.dumpHeap()` FAILS: SELinux blocks FD creation from system_server to shell_data_file

### KnoxGuardSeService Internals
- `mRemoteLockMonitorCallback` — safe to null (prevents lock monitor)
- `mLockSettingsService` — DO NOT NULL (causes powerOff on retry)
- `mSystemReceiver`, `mServiceSystemReceiver` — safe to unregister
- `RETRY_LOCK` alarm — safe to cancel
- `PROCESS_CHECK` alarm — safe to cancel

---

## How the Exploit Works

1. QR provisioning → Device Owner enables ADB
2. CVE-2024-34740 overflows ABX binary XML length field
3. Stage 1: Inject fake sessions → crash system_server → reboot
4. Stage 2: Drop APK to `/data/app/dropped_apk/` + patch `packages-backup.xml` → crash → reboot
5. droppedapk loads as UID 1000, fires on BOOT_COMPLETED

---

## Build and Run

```bash
cd /Users/kyin/Downloads/AbxOverflow
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :droppedapk:assembleRelease
bash utils/moveapk.sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
bash run_exploit.sh
```

---

## File Paths

```
PAYLOAD SOURCE (v14c):
  ~/Downloads/AbxOverflow/droppedapk/src/main/java/com/example/abxoverflow/droppedapk/
    MainActivity.java     -- KG exploitation + flip-debug + heap dump + self-update
    LaunchReceiver.java   -- 6-phase BOOT_COMPLETED unlock + neutralize

PREBUILT APKS:
  apk/droppedapk-v11-autoboot-fresh.apk
  apk/droppedapk-v12-stable.apk
  apk/droppedapk-v12-permanent.apk
  apk/droppedapk-v13-forcestop.apk
  apk/droppedapk-v13-neutralize.apk      -- v13 (proven 80+ min)
  apk/droppedapk-v14-heapdump.apk
  apk/droppedapk-v14b-heapdump.apk
  apk/droppedapk-v14c-heapdump.apk       -- DEPLOYED
  apk/exploit-app-v14c-heapdump.apk      -- matching exploit app
  apk/device-owner.apk
```

---

## Session Pickup Checklist

1. `adb devices` — phone connected?
2. `adb shell getprop knox.kg.state` — should be "Completed"
3. `adb logcat -d | grep "BUILD v"` — version check
4. Read HANDOFF.md for current goals
5. For code changes: use self-update, NEVER `adb install -r`
6. For research: use `exa search "query" --project fold4 --num 10`
