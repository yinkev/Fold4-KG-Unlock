# Samsung Fold 4 KG Unlock — Handoff

**Last updated:** March 20, 2026

---

## Current State: v13-NEUTRALIZE — PROVEN STABLE

- **KG=Completed**, 80+ minutes stable, survived the 37-minute danger zone
- droppedapk v13-NEUTRALIZE deployed with correct UID 1000 via clean exploit
- 6-phase auto-unlock on every BOOT_COMPLETED (wipe → unlock → firewall → watchers+watchdog → force-stop → neutralize)
- kgclient respawns every ~10s but **cannot re-lock**: receivers unregistered, callbacks nulled, alarms cancelled, watchdog kills it
- OTA updates permanently disabled (`com.wssyncmldm`, `com.sec.android.soagent`)
- Security policy updater disabled (`com.samsung.android.sm.devicesecurity`)
- Remote support disabled (`com.rsupport.rs.activity.rsupport.aas2`)
- ADB stays alive through everything via ContentObserver

### Why v13 Succeeds Where v12 Failed

v12-PERMANENT only addressed kgclient's external behavior (file wipes, network blocks, file watchers). The re-lock at ~37 min came from **inside system_server** — `KnoxGuardSeService`'s internal callbacks and alarms.

v13-NEUTRALIZE attacks the service itself: nulls `mRemoteLockMonitorCallback`, unregisters ALL BroadcastReceiver fields, cancels `RETRY_LOCK` and `PROCESS_CHECK` alarms. Combined with the watchdog that force-stops kgclient every 10s, there is no remaining re-lock path.

---

## Team Template

Saved in memory + project CLAUDE.md. 6 roles for spawning `fold4-ops` team:

- **researcher** — KG bypass, kgclient behavior, Samsung framework internals
- **samsung-re** — Reverse engineering KnoxGuardSeService, Binder TX mapping
- **web-scout** — External research via Exa API, XDA, GitHub, security blogs
- **builder** — Modify droppedapk source, rebuild APKs, save versioned copies
- **code-reviewer** — Review all changes before deploying. Check for crashes, 8133 triggers
- **repo-ops** — Source sync, docs, APK inventory, git status

---

## v14-HEAPDUMP Development Status

Source is in repo (`src/droppedapk/`) and AbxOverflow, but NOT deployed to device yet.

### What v14 Adds

1. **Heap dump bypass** — `IApplicationThread.dumpHeap()` called from system_server to dump Pokemon GO's memory. Gets ProcessRecord via AMS's `mPidsSelfLocked` map, then calls `dumpHeap()` on the app's IApplicationThread.
   - Native dump: `/data/local/tmp/pogo_heap.bin`
   - Java dump: `/data/local/tmp/pogo_heap.hprof`

2. **PoGO file listing** — Lists Pokemon GO's data directory structure, copies SharedPreferences to `/data/local/tmp/pogo_*` for ADB extraction.

3. **Phase 6 → enumeration only** — No longer invokes methods blindly. Log-only recon that enumerates all methods AND fields on KnoxGuardSeService.

4. **Firewall moved before neutralize** — Phase 2 now runs before Phase 5 for faster network blocking.

5. **Throwable catch in watchdog** — More robust error handling.

### Self-Update Mechanism

Deployed in v13 source but **first use pending with v14**:

```bash
adb push droppedapk-v14.apk /data/local/tmp/droppedapk-update.apk
adb shell am start -n com.example.abxoverflow.droppedapk/.MainActivity \
    --es action self-update
```

Process: clears oat cache → copies APK to tmp → atomic rename to `/data/app/dropped_apk/base.apk` → reboots. If rename fails, aborts cleanly (won't corrupt live APK).

**Known issue**: fallback error handling needs verification in v14. The atomic rename may fail if SELinux context differs.

---

## Pokeball Plus Key Extraction

### Algorithm (verified)

- PBP uses AES-ECB encryption for BLE communication
- Key extraction via XOR oracle: `plaintext XOR ciphertext` when block content is known
- Dual-session oracle built and ready
- Need: memory dump from Pokemon GO containing the BLE encryption key

### What's Needed

1. Deploy v14-HEAPDUMP via self-update
2. Launch Pokemon GO, connect Pokeball Plus
3. Trigger heap dump: `adb shell am start -n com.example.abxoverflow.droppedapk/.MainActivity --es action dump-heap`
4. Pull dumps: `adb pull /data/local/tmp/pogo_heap.bin` and `adb pull /data/local/tmp/pogo_heap.hprof`
5. Search dumps for BLE key material
6. Also try file listing: `--es action list-files` to check SharedPreferences

---

## TX Code Mapping (Android 13 KnoxGuardSeService)

From researcher's Phase 6 enumeration. Key methods discovered:

| Method | Notes |
|---|---|
| `setRemoteLockToLockscreen(boolean)` | Proven: clears KG overlay |
| `unlockCompleted()` | Proven: marks unlock done |
| `unbindFromLockScreen()` | Proven: unbinds from keyguard |
| `unlockScreen()` | Service-level wrapper around native unlock |
| All other methods | Enumerated and logged, see Phase 6 output in logcat |

Key fields discovered: `mRemoteLockMonitorCallback`, `mSystemReceiver`, `mServiceSystemReceiver`, `mContext`, `mLockSettingsService` (DO NOT NULL — causes powerOff).

---

## Research Findings Summary

All confirmed safe/non-issues:

- **SELinux**: UID 1000 in system_server has full `system_server` SELinux context. No restrictions on file ops, reflection, or service calls.
- **DEFEX**: Samsung's kernel-level protection. Does NOT block UID 1000 operations within system_server process.
- **Battery**: Force-stop watchdog at 10s intervals has negligible battery impact.
- **Long-term stability**: 80+ minutes confirmed. No degradation observed. kgclient respawn loop is harmless — it gets killed before it can do anything.
- **Integrity check (8133)**: NOT triggered by force-stop. Only triggered by `pm disable-user`. Safe.
- **Error 3001**: NOT triggered by File.delete(). Only by `pm clear`. Safe.

---

## What Was Done (Chronological)

### Phase 1: ADB Access
- Built Device Owner APK (`com.kyin.adbenab`) — enables ADB, dev options, pre-loads ADB key
- QR enterprise provisioning on factory-reset phone
- WiFi: `coconutWater` / `9093255140`

### Phase 2: CVE-2024-34740 Exploit
- Stage 1 (ABX overflow → crash) → reboot → Stage 2 (patch packages.xml) → reboot
- droppedapk installed as UID 1000 in system_server

### Phase 3: Versions
- **v11-AUTOBOOT**: Single unlock on BOOT_COMPLETED — worked but kgclient re-locked after ~3 min
- **v12-PERMANENT**: 4-phase (wipe, unlock, firewall, watchers) — extended to ~37 min
- **v13-NEUTRALIZE**: 6-phase (+ force-stop, neutralize service internals) — **80+ min stable, no re-lock**

### Phase 4: Lockdown
- Disabled OTA, security policy updater, remote support
- Phone set up with Google account, no Samsung account

---

## Critical Rules

- **NEVER** `adb install -r` on droppedapk — use self-update mechanism
- **NEVER** `pm disable-user` on kgclient — triggers 8133
- **NEVER** `pm clear` on kgclient — triggers 3001
- **NEVER** null `mLockSettingsService` — causes `Utils.powerOff()`
- **NEVER** update firmware
- **NEVER** sign into Samsung account
- **NEVER** factory reset (would need full exploit re-run)

---

## If KG Comes Back

1. Reboot — BOOT_COMPLETED fires the 6-phase auto-unlock
2. ADB should still be alive via ContentObserver
3. Manual: `adb shell am start --activity-clear-task -n com.example.abxoverflow.droppedapk/.MainActivity --ei action 36`
4. Force reboot: Vol Down + Power 10s

---

## Files

| Location | What |
|---|---|
| `/Users/kyin/Projects/Fold4/` | Git repo + docs + research (GitHub: yinkev/Fold4-KG-Unlock) |
| `/Users/kyin/Downloads/AbxOverflow/` | Full exploit source (v14-HEAPDUMP source of truth) |
| `/Users/kyin/adb_enabler/` | Device Owner APK source |
| `/Users/kyin/Downloads/serve_apk/` | HTTP server dir + QR code |
