# Session Handoff — March 20, 2026

This document captures everything that happened in the March 20, 2026 session for continuity into the next session.

---

## Session Summary

Marathon session covering KG unlock stabilization (v11 → v14c), a phone-loss incident, recovery, and pivot to Pokeball Plus key extraction. Ended with a working heap dump pipeline and scan in progress.

---

## Version Progression

### v11-AUTOBOOT (inherited from prior session)
- Single unlock on BOOT_COMPLETED
- Worked but kgclient re-locked after ~3 minutes
- Proved the core 7-call reflection sequence works

### v12-PERMANENT
- Added 4-phase approach: wipe kgclient data → unlock → firewall → watchers
- Extended stability from ~3 min to ~37 min
- Failed because: kgclient's **in-memory** lock policy survived file wipes and network blocks

### v13-NEUTRALIZE (breakthrough)
- Added force-stop + KnoxGuardSeService neutralization
- Null mRemoteLockMonitorCallback, unregister ALL receivers, cancel RETRY_LOCK + PROCESS_CHECK alarms
- 10-second watchdog kills kgclient on every respawn
- **80+ minutes stable** — survived the 37-minute danger zone
- kgclient respawns but has no path to re-lock

### v14-HEAPDUMP
- Added heap dump capability via IApplicationThread.dumpHeap()
- Self-update mechanism for safe APK replacement
- Phase 6 changed from blind method invocation to log-only enumeration
- **Failed**: SELinux blocks FD creation from system_server to shell_data_file. IApplicationThread.dumpHeap() cannot write to /data/local/tmp/.

### v14b-HEAPDUMP
- AMS binder unwrap fix (this$0 field)
- mProcessList fallback for pid map access
- Output path changed to /data/system/heapdump/
- Still blocked by SELinux on the dumpHeap FD path

### v14c-HEAPDUMP (current, deployed)
- **FLAG_DEBUGGABLE flip**: Modifies ProcessRecord.info.flags in-memory from UID 1000
- Makes any app appear debuggable to `am dumpheap` from ADB shell
- Deprecated direct IApplicationThread.dumpHeap() in favor of flip-debug + `am dumpheap` workflow
- **Working**: heap dump pipeline confirmed functional

---

## The Incident (21:12 PDT)

### What Happened
1. `pm uninstall com.example.abxoverflow.droppedapk` — removed v13
2. Stage 1 ran — crashed system_server → full reboot
3. Phone rebooted with NO droppedapk installed
4. KG re-locked, ADB killed
5. Phone bricked from our perspective

### Root Cause
Uninstalling droppedapk before Stage 1 removed the BOOT_COMPLETED safety net. Samsung's full-reboot-on-crash behavior meant Stage 2 could never run. The exploit's two-stage design assumes the old payload survives the Stage 1 reboot.

### Recovery
Factory reset → QR provision → full exploit re-run → v14c deployed. ~15 minute process.

### Lesson Learned
**NEVER `pm uninstall` droppedapk before running Stage 1.** Added as first Critical Rule in CLAUDE.md and all docs. Safe deployment sequence documented.

---

## Current Device State

- **Phone**: Galaxy Z Fold 4 (SM-F936U1), Android 13, July 2023 SPL
- **droppedapk**: v14c-HEAPDUMP, UID 1000, running in system_server
- **KG state**: `knox.kg.state=Completed` (sysprop), Active(2) in RPMB
- **ADB**: Connected, "Always Allow" set
- **kgclient**: Enabled (must stay), respawns but neutralized
- **OTA**: Disabled
- **Pokeball Plus**: Connected via BLE to Pokemon GO
- **Heap dump**: In progress, scan pending

---

## Team Template

Spawn with `fold4-ops`. Defined in `/Users/kyin/Projects/Fold4/.claude/CLAUDE.md` and user memory.

| Role | Responsibility |
|---|---|
| researcher | KG bypass, kgclient behavior, Samsung framework internals |
| samsung-re | Reverse engineering KnoxGuardSeService, Binder TX mapping |
| web-scout | External research via `exa search "query" --project fold4 --num 10` |
| builder | Modify droppedapk source, rebuild APKs, save versioned copies |
| code-reviewer | Review all changes before deploying to device |
| repo-ops | Source sync, docs, APK inventory, git status |

---

## PBP Key Extraction Status

### Algorithm (verified)
- Pokeball Plus uses AES-ECB encryption for BLE communication
- Key extraction: XOR oracle with dual-session approach
- Need: memory dump from Pokemon GO containing the BLE encryption key

### Pipeline (working)
```bash
# 1. Flip debuggable
am start -n com.example.abxoverflow.droppedapk/.MainActivity \
    --es action flip-debug --es target com.nianticlabs.pokemongo

# 2. Dump heap
am dumpheap com.nianticlabs.pokemongo /data/local/tmp/pogo.hprof

# 3. Restore
am start ... --es action restore-debug --es target com.nianticlabs.pokemongo

# 4. Pull and scan
adb pull /data/local/tmp/pogo.hprof
```

### Status
Heap dump obtained. Scan for BLE key material in progress at end of session.

---

## Verified Technical Findings

### SELinux (verified against AOSP android-13.0.0_r75 sepolicy)
- `/data/app/vmdl*.tmp/` = `apk_tmp_file` → system_server has `create_file_perms`
- `/data/local/tmp/` = `shell_data_file` → system_server CANNOT read
- Shell CAN stage via `pm install-create/write` → Binder to PackageManagerService
- system_server CANNOT `connectto` shell's abstract Unix sockets
- Sessions can sit uncommitted indefinitely

### FLAG_DEBUGGABLE Flip
- `ProcessRecord.info.flags |= 0x2` from UID 1000 → makes app debuggable
- In-memory only, no disk persistence
- No SELinux issues (modification within system_server address space)
- Enables `am dumpheap` from ADB shell on any app

### Direct dumpHeap (FAILED — SELinux)
- `IApplicationThread.dumpHeap()` requires passing a ParcelFileDescriptor
- FD must be created by system_server but point to shell_data_file location
- SELinux denies this: no `allow system_server shell_data_file:file create`
- Solution: flip-debug + `am dumpheap` from ADB shell (shell context CAN create FDs in /data/local/tmp/)

### KnoxGuardSeService Safe vs Unsafe Fields
| Field | Action | Safe? |
|---|---|---|
| mRemoteLockMonitorCallback | Null | YES |
| mSystemReceiver | Unregister + null | YES |
| mServiceSystemReceiver | Unregister + null | YES |
| RETRY_LOCK alarm | Cancel | YES |
| PROCESS_CHECK alarm | Cancel | YES |
| mLockSettingsService | Null | **NO — causes powerOff()** |

### Force-Stop Safety
- `forceStopPackage` does NOT trigger error 8133 (only `pm disable-user` does)
- kgclient respawns via Samsung's restart policy (~10s)
- Watchdog catches every respawn

---

## Self-Update Architecture

### Current (v14c) — Direct file copy
Source: `/data/local/tmp/droppedapk-update.apk` → Target: `/data/app/dropped_apk/base.apk`
**Known issue**: SELinux may block system_server reading from shell_data_file.

### Planned — PackageInstaller staging
Verified at SELinux level. ADB stages via `pm install-write` → lands in `/data/app/vmdl*.tmp/` (apk_tmp_file) → system_server copies to target. Implementation needed.

---

## Next Steps for Next Session

1. **Analyze heap dump** — Scan pogo.hprof for BLE key material (AES-ECB key for Pokeball Plus)
2. **Implement PackageInstaller self-update** — Replace current direct-copy selfUpdate() with the verified staging approach
3. **Achieve TA Completed(4)** — Aspirational: find TrustZone call to move from Active(2) to Completed(4)
4. **Long-term monitoring** — v14c inherits v13's proven stability but hasn't been tested for 80+ min yet in current deploy

---

## Recovery Procedure (if phone is lost again)

```bash
# 1. Serve DO APK
cd /Users/kyin/Downloads/serve_apk && python3 -m http.server 8888

# 2. Factory reset phone, QR provision (tap 6x, scan provision_qr.png)

# 3. Verify ADB
adb devices  # RFCW2006DLA

# 4. Build
cd ~/Downloads/AbxOverflow
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :droppedapk:assembleRelease && bash utils/moveapk.sh && ./gradlew :app:assembleDebug

# 5. Run exploit
adb install -r app/build/outputs/apk/debug/app-debug.apk
bash run_exploit.sh
# Stage 1 → reboot → Stage 2 → reboot → droppedapk fires → unlocked
```

All APKs preserved in `/Users/kyin/Projects/Fold4/apk/`. ~15 minute recovery.
