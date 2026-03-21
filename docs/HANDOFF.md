# Samsung Fold 4 KG Unlock — Handoff

**Last updated:** March 20, 2026, ~22:30 PDT

---

## Current State: v14c-HEAPDUMP Deployed

- **KG=Completed** (sysprop), TA state Active(2) in RPMB
- droppedapk v14c-HEAPDUMP deployed with correct UID 1000 via clean exploit
- 6-phase auto-unlock on every BOOT_COMPLETED
- kgclient respawns every ~10s but cannot re-lock (receivers unregistered, callbacks nulled, alarms cancelled, watchdog kills it)
- Pokeball Plus connected via BLE to Pokemon GO
- Heap dump in progress for PBP key extraction
- OTA, security policy updater, remote support all disabled

### What v14c Adds Over v13
- **FLAG_DEBUGGABLE flip** — Modifies ProcessRecord.info.flags in-memory to make any app debuggable. Enables `am dumpheap` from ADB shell.
- **Deprecated direct dumpHeap** — IApplicationThread.dumpHeap() fails due to SELinux FD creation block. Replaced with flip-debug + `am dumpheap` workflow.
- **AMS unwrap fix** — Properly unwraps AMS binder stub via `this$0` field, falls back through `mProcessList.mPidsSelfLocked`.
- **PoGO file output to /data/system/heapdump/** — Avoids SELinux shell_data_file issues.

---

## Deployment Sequence (CRITICAL — read before touching droppedapk)

### Option A — Self-update (preferred):
```bash
adb push new.apk /data/local/tmp/droppedapk-update.apk
adb shell am start -n com.example.abxoverflow.droppedapk/.MainActivity --es action self-update
```

### Option B — Exploit re-run (DO NOT uninstall first):
```bash
# Stage 1 — old droppedapk stays, protects through reboot
adb shell am start ... --ei stage 1
# [reboot — old droppedapk fires]
# Stage 2 — replaces with new version
adb shell am start ... --ei stage 2
# [reboot — new droppedapk fires]
```

> [!CAUTION]
> **NEVER `pm uninstall` droppedapk before Stage 1.** This caused the 2026-03-20 incident where the phone was lost and required a full factory reset + exploit re-run to recover.

---

## Team Template

Spawn with `fold4-ops`. 6 roles:

- **researcher** — KG bypass, kgclient behavior, Samsung framework internals
- **samsung-re** — Reverse engineering KnoxGuardSeService, Binder TX mapping
- **web-scout** — External research via `exa search "query" --project fold4 --num 10`
- **builder** — Modify droppedapk source, rebuild APKs, save versioned copies
- **code-reviewer** — Review all changes before deploying
- **repo-ops** — Source sync, docs, APK inventory, git status

---

## PBP Key Extraction Status

### What's Known
- Pokeball Plus uses AES-ECB encryption for BLE communication
- Key extraction via XOR oracle: `plaintext XOR ciphertext` when block content is known
- Dual-session oracle built and ready
- Pokemon GO is running, PBP is connected via BLE

### Heap Dump Workflow (v14c)
```bash
# 1. Flip debuggable flag on Pokemon GO
adb shell am start -n com.example.abxoverflow.droppedapk/.MainActivity \
    --es action flip-debug --es target com.nianticlabs.pokemongo

# 2. Dump heap
adb shell am dumpheap com.nianticlabs.pokemongo /data/local/tmp/pogo.hprof

# 3. Restore flags
adb shell am start ... --es action restore-debug --es target com.nianticlabs.pokemongo

# 4. Pull and analyze
adb pull /data/local/tmp/pogo.hprof
# Search for BLE key material
```

### Status
Heap dump scan in progress. Looking for BLE encryption key in PoGO's heap.

---

## Verified Findings

### SELinux
- UID 1000 in system_server has full `system_server` SELinux context
- system_server CAN read/write `/data/app/vmdl*.tmp/` (apk_tmp_file) — verified against AOSP android-13.0.0_r75 sepolicy
- system_server CANNOT read `/data/local/tmp/` (shell_data_file) — blocks IApplicationThread.dumpHeap() FD creation
- system_server CANNOT connect to shell's abstract Unix sockets (no connectto permission)

### FLAG_DEBUGGABLE Flip
- Modifying `ProcessRecord.info.flags |= 0x2` in-memory makes `am dumpheap` work from ADB shell
- In-memory only — survives until process restart, does not persist
- No SELinux issues — modification happens within system_server's own address space

### DEFEX, Battery, Stability
- DEFEX does not block UID 1000 operations within system_server
- Watchdog polling (10s) has negligible battery impact
- Error 8133 NOT triggered by force-stop (only pm disable-user)
- Error 3001 NOT triggered by File.delete() (only pm clear)

---

## Critical Rules

- **NEVER** `pm uninstall` droppedapk before Stage 1 (incident: 2026-03-20)
- **NEVER** `adb install -r` on droppedapk — use self-update
- **NEVER** `pm disable-user` on kgclient — triggers 8133
- **NEVER** `pm clear` on kgclient — triggers 3001
- **NEVER** null `mLockSettingsService` — causes powerOff()
- **NEVER** update firmware or sign into Samsung account
- **NEVER** factory reset without good reason

---

## Files

| Location | What |
|---|---|
| `/Users/kyin/Projects/Fold4/` | Git repo (GitHub: yinkev/Fold4-KG-Unlock) |
| `/Users/kyin/Downloads/AbxOverflow/` | Full exploit source (v14c source of truth) |
| `/Users/kyin/adb_enabler/` | Device Owner APK source |
| `/Users/kyin/Downloads/serve_apk/` | HTTP server dir + QR code |
