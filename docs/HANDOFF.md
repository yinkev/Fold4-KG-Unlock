# Samsung Fold 4 KG Unlock — Handoff

**Last updated:** March 20, 2026

---

## Current State: v12-PERMANENT

- Phone is set up and usable (home screen, apps, calls, everything)
- droppedapk v12-PERMANENT installed with correct UID 1000 via clean exploit
- 4-phase auto-unlock on every BOOT_COMPLETED:
  1. **Wipe** kgclient cached data (File.delete, no 3001 trigger)
  2. **Unlock** via KnoxGuardSeService reflection (same proven sequence as v11)
  3. **Firewall** block kgclient network via NetworkManagementService
  4. **Watchers** — FileObserver on kgclient data dirs + ContentObserver on ADB_ENABLED
- OTA updates permanently disabled (`com.wssyncmldm`, `com.sec.android.soagent`)
- Security policy updater disabled (`com.samsung.android.sm.devicesecurity`)
- Remote support disabled (`com.rsupport.rs.activity.rsupport.aas2`)

### Remaining Issue

**kgclient in-memory re-lock** (~37 min after boot): kgclient's in-memory state survives file wipes and firewall blocks. It eventually calls lockScreen() using cached in-memory lock policy. The file watchers and firewall delay this significantly but don't prevent it entirely. ADB stays alive via ContentObserver, and a background ADB poll script can auto-re-unlock when Locked is detected.

---

## What Was Done (Chronological)

### Phase 1: ADB Access
- Built custom Device Owner APK (`com.kyin.adbenab`) — enables ADB, dev options, pre-loads ADB key
- Updated DO to NOT disable kgclient (original version caused error 8133)
- QR enterprise provisioning on factory-reset phone
- WiFi: `coconutWater` / `9093255140`

### Phase 2: CVE-2024-34740 Exploit
- Built droppedapk release → copied to exploit app assets → built exploit app
- Ran stage 1 (ABX overflow → crash) → reboot → stage 2 (patch packages.xml) → reboot
- droppedapk installed as UID 1000 in system_server

### Phase 3: Unlock (v11-AUTOBOOT → v12-PERMANENT)
- v11-AUTOBOOT: single unlock on BOOT_COMPLETED — worked but kgclient re-locked after ~3 min
- v12-PERMANENT: added 4-phase approach (wipe, unlock, firewall, watchers) — extends stability to ~37 min
- All KnoxGuardSeService calls succeed: setRemoteLockToLockscreen(false), unlockCompleted(), unbindFromLockScreen(), tz_unlockScreen(0), tz_resetRPMB(0)
- Additionally unregisters kgService's system broadcast receivers to prevent CONNECTIVITY_CHANGE re-lock

### Phase 4: Lockdown
- Disabled OTA apps via `pm disable-user` (removed "Software update" from Settings entirely)
- Disabled security policy updater
- Disabled Remote Support
- Phone set up with Google account, no Samsung account

---

## What We Learned the Hard Way

1. **`adb install -r` on droppedapk corrupts UID mapping.** Files become UID 0, package expects UID 1000. BOOT_COMPLETED receiver stops working. Bake ALL code changes into source BEFORE running exploit.

2. **`enforceProtections` in the guardian loop caused flashing.** Calling `setApplicationEnabledSetting` repeatedly from system_server triggered constant system churn.

3. **v11-AUTOBOOT (one-shot) was stable for 30+ minutes.** The guardian thread was unnecessary for that window. But kgclient eventually re-locks via in-memory state regardless.

4. **kgclient caches lock commands from Samsung's server.** After WiFi connects, it stores the lock policy locally AND in memory. File deletion helps on boot but in-memory state persists until process restart.

5. **Direct File.delete() does NOT trigger error 3001.** Only `pm clear` fires PACKAGE_DATA_CLEARED broadcast. Safe to wipe kgclient data from UID 1000.

6. **Firewall + receiver unregistration delays but doesn't prevent re-lock.** kgclient has multiple re-lock paths beyond network connectivity.

---

## Critical Rules

- **NEVER** `adb install -r` on the droppedapk
- **NEVER** `pm disable-user` on kgclient (triggers 8133)
- **NEVER** `pm clear` on kgclient (triggers 3001)
- **NEVER** update firmware
- **NEVER** sign into Samsung account
- **NEVER** factory reset (would need full exploit re-run)

---

## If KG Comes Back

1. Reboot the phone — BOOT_COMPLETED fires the 4-phase auto-unlock
2. If that doesn't work — ADB should still be alive via ContentObserver
3. Run manually: `adb shell am start --activity-clear-task -n com.example.abxoverflow.droppedapk/.MainActivity --ei action 36`
4. If ADB is dead — force reboot (Vol Down + Power 10s), ADB should reconnect on boot

---

## Files

| Location | What |
|---|---|
| `/Users/kyin/Projects/Fold4/` | Working snapshot + docs + research (GitHub: yinkev/Fold4-KG-Unlock) |
| `/Users/kyin/Downloads/AbxOverflow/` | Full exploit source (v12-PERMANENT source of truth) |
| `/Users/kyin/adb_enabler/` | Device Owner APK source |
| `/Users/kyin/Downloads/serve_apk/` | HTTP server dir + QR code |

---

## Remaining Work (Future Session)

1. **Solve kgclient in-memory re-lock** — The cached file wipe works for on-disk state, but kgclient's in-memory lock policy survives. Options:
   - Force-stop kgclient after wiping data (risk: may trigger integrity check)
   - Hook kgclient's lockScreen() call path at the KnoxGuardSeService level
   - Find and null out the in-memory lock policy object via reflection
2. **Achieve TA state Completed(4)** — Currently Active(2). Need to find the right TrustZone call sequence to reach Completed(4) or Prenormal(0) for true permanent unlock.
