# Samsung Fold 4 KG Unlock — Handoff

**Last updated:** March 20, 2026, 00:15 AM PDT

---

## Current State: WORKING

- Phone is set up and usable (home screen, apps, calls, everything)
- `knox.kg.state = Completed` — stable for 30+ minutes with zero drops
- ADB connected via "Always Allow" — persists through KG cycles
- droppedapk (v11-AUTOBOOT) installed with correct UID 1000 via clean exploit
- OTA updates permanently disabled (`com.wssyncmldm`, `com.sec.android.soagent`)
- Security policy updater disabled (`com.samsung.android.sm.devicesecurity`)
- Remote support disabled (`com.rsupport.rs.activity.rsupport.aas2`)

---

## What Was Done (Chronological)

### Phase 1: ADB Access
- Built custom Device Owner APK (`com.kyin.adbenab`) — enables ADB, dev options, pre-loads ADB key
- Updated DO to NOT disable kgclient (original version caused error 8133)
- QR enterprise provisioning on factory-reset phone
- WiFi: `coconutWater` / `9093255140`

### Phase 2: CVE-2024-34740 Exploit
- Restored v11-AUTOBOOT source from snapshot (`/Users/kyin/Projects/Fold4/src/`)
- Built droppedapk release → copied to exploit app assets → built exploit app
- Ran stage 1 (ABX overflow → crash) → reboot → stage 2 (patch packages.xml) → reboot
- droppedapk installed as UID 1000 in system_server

### Phase 3: Unlock
- Launched droppedapk with `--ei action 36`
- All calls succeeded: setRemoteLockToLockscreen(false), unlockCompleted(), unbindFromLockScreen(), tz_unlockScreen(0), tz_resetRPMB(0), ADB re-enable, sysprop Completed
- KG cleared, phone usable

### Phase 4: Lockdown
- Disabled OTA apps via `pm disable-user` (removed "Software update" from Settings entirely)
- Disabled security policy updater
- Disabled Remote Support
- Phone set up with Google account, no Samsung account

---

## What We Learned the Hard Way

1. **`adb install -r` on droppedapk corrupts UID mapping.** Files become UID 0, package expects UID 1000. BOOT_COMPLETED receiver stops working. The fix: bake all code changes into the source BEFORE running the exploit. Never reinstall after.

2. **`enforceProtections` in the guardian loop caused the flashing.** Calling `setApplicationEnabledSetting` on packages every 5 seconds from inside system_server triggered constant system churn. kgclient detected the activity and fought harder.

3. **v11-AUTOBOOT (one-shot unlock on boot, no guardian) is what actually works.** The guardian thread (v12+) was unnecessary — v11's single unlock on BOOT_COMPLETED was stable for 30+ minutes. Adding complexity made everything worse.

4. **kgclient caches the lock command from Samsung's server.** After WiFi connects and kgclient contacts Samsung, it stores the lock policy locally. On subsequent boots, it replays the cached command. Fresh factory reset = no cache = stable. Established connection = cached = may re-lock.

5. **The 30-minute monitor showed zero drops this time.** Previous instability was caused by our code changes (enforceProtections, guardian loop, repeated reinstalls), not by kgclient. When we stopped touching things, it stayed stable.

6. **File history saved us.** Claude's `.claude/file-history/` had every version of every file. We recovered the exact working code from the 16:32 stable moment.

7. **Samsung's `pm disable-user` on OTA apps removes the Settings menu entry entirely.** Unexpected but perfect — no way to accidentally trigger an update.

8. **Security policy updates are separate from firmware updates.** Handled by `com.samsung.android.sm.devicesecurity`, not the OTA apps. Had to disable separately.

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

1. Reboot the phone — BOOT_COMPLETED fires the auto-unlock
2. If that doesn't work — ADB should still be alive via "Always Allow"
3. Run manually: `adb shell am start --activity-clear-task -n com.example.abxoverflow.droppedapk/.MainActivity --ei action 36`
4. If ADB is dead — force reboot (Vol Down + Power 10s), ADB should reconnect on boot

---

## Files

| Location | What |
|---|---|
| `/Users/kyin/Projects/Fold4/` | Working snapshot + docs + research (GitHub: yinkev/Fold4-KG-Unlock) |
| `/Users/kyin/Downloads/AbxOverflow/` | Full exploit source (source matches snapshot) |
| `/Users/kyin/adb_enabler/` | Device Owner APK source |
| `/Users/kyin/Downloads/serve_apk/` | HTTP server dir + QR code |

---

## Remaining Work (Future Session)

1. **kgclient cache deletion** — delete cached lock command via `File.delete()` from UID 1000 to prevent re-locking permanently
2. **Guardian thread** — bake persistent unlock loop into droppedapk source, re-run exploit (for insurance, not strictly needed if current stability holds)
3. **Disable non-essential kgclient components** — beyond the 6 checked by IntegritySeUtil, disable server communication receivers
