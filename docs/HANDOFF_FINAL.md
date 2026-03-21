# Samsung Fold 4 KG Unlock + Pokeball Plus Key Extraction — FULL HANDOFF

## What This Project Is

A Samsung Galaxy Z Fold 4 (SM-F936U1) locked by Knox Guard (Samsung's enterprise device lock). We unlocked it using CVE-2024-34740 (AbxOverflow exploit) which gives UID 1000 code execution inside system_server. The unlock works — but KG tries to re-lock on every boot. Our "droppedapk" runs inside system_server and neutralizes KG on every BOOT_COMPLETED.

The secondary goal is extracting a 16-byte AES device key from a Pokeball Plus (Nintendo BLE accessory) so we can build a standalone Anki presentation remote.

---

## Current Phone State (as of March 21, 2026)

**PHONE IS KG-LOCKED. ADB IS DEAD.**

The last deploy (v14d-PMSDEBUG) failed due to a UID/SELinux mismatch in the exploit's file drop mechanism. The droppedapk didn't load on boot, KG re-locked, ADB was killed.

**Recovery path:** Factory reset → QR provisioning → full exploit chain (Stage 1 → Stage 2) with v14d.

---

## Recovery Procedure

### 1. Factory Reset
Hold power + vol up → recovery mode → Wipe data/factory reset → Reboot

### 2. QR Provisioning
APK server must be running on the Mac:
```bash
python3 -m http.server 8888 --directory ~/Downloads/serve_apk
```

After reset, on the phone: Connect to WiFi (SSID: `coconutWater`, password: `9093255140`), tap screen 6 times to open enterprise QR scanner, scan the QR at `~/Downloads/serve_apk/provision_qr.png`. Accept "Always Allow" on USB debugging dialog.

### 3. Deploy v14d
```bash
# Verify ADB
adb devices  # should show RFCW2006DLA

# Install exploit app
adb install -r ~/Projects/Fold4/apk/exploit-app-v14d-pmsdebug.apk

# Stage 1 (crashes system_server)
adb shell am start --activity-clear-task -n com.example.abxoverflow/.MainActivity --ei stage 1

# Wait for reboot (60-90s), verify ADB reconnects
# Stage 2 (drops droppedapk)
adb install -r ~/Projects/Fold4/apk/exploit-app-v14d-pmsdebug.apk
adb shell am start --activity-clear-task -n com.example.abxoverflow/.MainActivity --ei stage 2

# Wait for reboot, verify:
adb shell pm list packages | grep droppedapk  # should show package
adb shell getprop knox.kg.state  # should show Completed or Active
```

### 4. Post-Deploy Hardening
```bash
adb shell settings put global ota_disable 1
adb shell pm disable-user --user 0 com.wssyncmldm
adb shell pm disable-user --user 0 com.sec.android.soagent
adb shell settings put global stay_on_while_plugged_in 3
```

---

## CRITICAL RULES (NEVER VIOLATE)

1. **NEVER `pm uninstall` the droppedapk** — kills all KG defenses, phone bricks on next reboot. The exploit's Stage 2 overwrites it in-place.
2. **NEVER `adb install -r` on the droppedapk** — corrupts UID mapping. All changes must be baked into source before re-running the exploit.
3. **NEVER null `mLockSettingsService`** on KnoxGuardSeService — causes Utils.powerOff() (phone shuts down).
4. **NEVER `pm disable-user` on kgclient** — triggers error 8133.
5. **NEVER `pm clear` on kgclient** — triggers error 3001.
6. **NEVER sign into Samsung account** on the phone.
7. **NEVER update firmware** — may patch the exploit.
8. **Re-exploiting on top of an existing install is UNRELIABLE** — UID/SELinux mismatch. Always factory reset first for clean deploys.

---

## Architecture

### The Exploit (CVE-2024-34740 / AbxOverflow)
- Integer overflow in `BinaryXmlSerializer.attributeBytesBase64()`
- Stage 1: Injects fake PackageInstaller sessions into `install_sessions.xml`, crashes system_server
- Stage 2: Uses fake sessions to drop droppedapk to `/data/app/dropped_apk/` and patches `packages.xml` to register it as UID 1000 via `pastSigs` trick

### The Droppedapk (v14d-PMSDEBUG)
Runs as UID 1000 inside system_server via `android:sharedUserId="android.uid.system"`. On every BOOT_COMPLETED, executes 6 phases:

- **Phase 0:** Wipe kgclient cached data (`File.delete`, doesn't trigger error 3001)
- **Phase 1:** Full unlock (setRemoteLockToLockscreen, unlockCompleted, unbindFromLockScreen, tz_unlockScreen, tz_resetRPMB)
- **Phase 2:** Firewall block kgclient UID 10082 (NetworkManagementService STANDBY chain)
- **Phase 3:** Event-driven watchers (FileObserver on kgclient data, ContentObserver on ADB_ENABLED, 10s force-stop watchdog)
- **Phase 4:** Force-stop kgclient
- **Phase 5:** Neutralize KnoxGuardSeService internals (null mRemoteLockMonitorCallback, unregister ALL BroadcastReceivers including USER_PRESENT, cancel RETRY_LOCK + PROCESS_CHECK alarms)
- **Phase 6:** Enumerate all KnoxGuardSeService methods (log-only recon)

### Additional v14d Capabilities
- `--es action flip-debug` / `restore-debug` — flips FLAG_DEBUGGABLE on a running app's ProcessRecord in AMS (bypasses `am dumpheap` enforceDebuggable check)
- `--es action flip-debug-pms` / `restore-debug-pms` — flips FLAG_DEBUGGABLE in PMS's internal PackageSetting cache (makes app genuinely debuggable from birth on next launch — JDWP + JAVA_DEBUGGABLE at ART level)
- `--es action dump-heap` — triggers heap dump (broken — SELinux blocks file open)
- `--es action list-files` — lists Pokemon GO data files (broken — SELinux blocks system_server reading app_data_file)
- `--es action self-update` — self-update mechanism (broken — SELinux blocks reading from /data/local/tmp/)

---

## Key Technical Findings

### KnoxGuard Internals (verified from Samsung framework source)
- **RPMB TA state is ALWAYS 3 (LOCKED)** — our `tz_unlockScreen` returns success but doesn't actually change RPMB. The unlock is runtime-only.
- **KG re-lock path:** `handleBootCompleted()` → reads TA state → integrity check → if state==3 OR integrity fails → `lockSeDevice()` → `bindAndSetToLockScreen()` → `ILockSettings.setKnoxGuard()` → lock screen applied
- **USER_PRESENT handler** re-checks KG on every screen unlock (we unregister this receiver)
- **RETRY_LOCK alarm** with powerOff() fallback if lock fails (we cancel this alarm)
- **kgclient uses AlarmManager:** PROCESS_CHECK every 180s, RETRY every 300s (force-stop kills these)

### KnoxGuardSeService TX Mapping (Android 13)
| TX | Method | Purpose |
|---|---|---|
| 41 | bindToLockScreen() | Apply lock screen |
| 42 | callKGsv() | Server verification |
| 60 | getTAState() | Read TA state |
| 65 | lockScreen() | Full lock with HOTP |
| 74 | resetRPMB() | RPMB reset |
| 81 | setCheckingState() | Set checking state |
| 82 | setClientData(String) | Set client data |
| 92 | unlockScreen() | Unlock |

### SELinux Constraints
- system_server CANNOT ptrace any app domain (neverallow rule)
- system_server CANNOT read /proc/pid/mem of apps
- system_server CANNOT write to shell_data_file (/data/local/tmp/)
- system_server CANNOT read app_data_file (PoGo's data directory)
- system_server CAN write to system_data_file (/data/system/)
- system_server CAN write to apk_data_file (/data/app/) — but SELinux label may be wrong

### Exploit File Ownership Issue
The CVE-2024-34740 exploit drops files to `/data/app/dropped_apk/` with potentially wrong SELinux labels (`system_data_file` instead of `apk_data_file`). This causes intermittent failures where PMS can't load the droppedapk after reboot. **Factory reset before exploit = reliable. Re-exploit on existing install = unreliable.**

---

## Pokeball Plus Key Extraction Status

### What We Have
- PBP BLE MAC: `94:58:CB:A0:CA:33`
- Chip: Cypress CYW20734
- Firmware: 3.900.1.115r1
- 5 captured auth sessions (4 cold auth State 00, 1 reconnect State 03)
- Two 378-byte cold auth challenges extracted from btsnoop captures
- Known algorithm: AES-128 ECB XOR (verified with BobThePigeon test vectors)
- Dual-session oracle at `/tmp/pbp_key_verify.py` — verifies candidates at ~1M/sec
- Key scanner at `/tmp/pbp_heap_scan.py` — scans binary dumps for the key
- `aeskeyfind` compiled for ARM64 on device at `/data/local/tmp/aeskeyfind`
- Java HPROF dump captured (44MB) — **key is NOT in Java heap** (confirmed: native memory only)

### What We Know
- AES-128 is unbreakable from captured traffic (mathematically proven — no known-plaintext shortcut)
- The key lives in `libpgpplugin.so` native memory inside Pokemon GO's process
- All cross-process memory reading is blocked by SELinux neverallow rules

### The Breakthrough: PMS PackageSetting Flag Flip
v14d includes `flip-debug-pms` which flips FLAG_DEBUGGABLE in PMS's internal PackageSetting cache BEFORE an app launches. This makes the app genuinely debuggable from birth (JDWP + JAVA_DEBUGGABLE at the ART level). Verified by two independent reviewers against AOSP source.

**Extraction sequence (NOT YET TESTED):**
```bash
# 1. Force-stop PoGo
adb shell am force-stop com.nianticlabs.pokemongo

# 2. Flip FLAG_DEBUGGABLE in PMS cache
adb shell am start -n com.example.abxoverflow.droppedapk/.MainActivity \
  --es action flip-debug-pms --es target com.nianticlabs.pokemongo

# 3. Launch PoGo (spawns with JDWP from birth)
adb shell monkey -p com.nianticlabs.pokemongo 1

# 4. Verify JDWP
adb jdwp  # should list PoGo's PID

# 5. Pair Pokeball Plus in PoGo (key enters native memory)

# 6. Forward JDWP, connect, invoke /proc/self/mem read
PID=$(adb shell pidof com.nianticlabs.pokemongo)
adb forward tcp:8700 jdwp:$PID
# Via jdb or JDWP script: read /proc/self/maps → find libpgpplugin.so → read /proc/self/mem → dump to /sdcard/Download/

# 7. Pull + scan
adb pull /sdcard/Download/pogo_native.bin
python3 /tmp/pbp_heap_scan.py pogo_native.bin
# Also: /tmp/aeskeyfind pogo_native.bin (on Mac, built at /tmp/aeskeyfind/aeskeyfind)

# 8. Restore flag
adb shell am start -n com.example.abxoverflow.droppedapk/.MainActivity \
  --es action restore-debug-pms --es target com.nianticlabs.pokemongo
```

### Alternative Paths (if PMS flip doesn't work)
1. **ADDC3E26 factory QA opcode brute-force** — connect directly to PBP via BLE from droppedapk (system_server has full GATT access), fuzz all 256 opcodes on the factory QA service. If any opcode dumps OTP memory, we get the key directly from hardware.
2. **CVE-2024-31317 (Zygote injection)** — device is vulnerable (SPL July 2023, patch June 2024). Makes apps genuinely debuggable at fork time. Works but has a race condition — can't reliably target which process gets the debug flags. Payload generator at `/tmp/pogo_zygote_inject.py`.
3. **Hardware SWD dump** — Flipper Zero or ST-Link connected to PBP's CYW20734 SWD pins. Guaranteed but requires physical teardown.

---

## Project Structure

```
~/Projects/Fold4/                    # Main project repo (GitHub: yinkev/Fold4-KG-Unlock)
├── .claude/CLAUDE.md                # Project-specific Claude instructions
├── apk/                             # Active APKs (v14d-PMSDEBUG)
│   ├── archive/                     # Old versions (v11→v12→v13→v14→v14b→v14c)
│   ├── device-owner.apk             # QR provisioning APK
│   ├── droppedapk-v14d-pmsdebug.apk # Current droppedapk
│   └── exploit-app-v14d-pmsdebug.apk # Current exploit app
├── src/droppedapk/                   # Droppedapk Java source
├── docs/                             # Documentation
├── research/                         # Research outputs
├── tools/                            # Utilities (memdump_agent.c, inject scripts)
└── wallpapers/                       # Chopper wallpapers

~/Downloads/AbxOverflow/              # Exploit source (Gradle project)
├── app/                              # Exploit app (Stage 1/2 controller)
│   └── src/main/assets/              # droppedapk-release.apk baked in here
├── droppedapk/                       # Droppedapk module
│   └── src/main/java/.../            # LaunchReceiver.java + MainActivity.java
└── run_exploit.sh                    # Mac-side orchestrator

~/Downloads/serve_apk/                # APK server directory
├── adbenab.apk                       # Device-owner APK for QR provisioning
└── provision_qr.png                  # QR code (IP: 192.168.4.125, WiFi: coconutWater)
```

---

## Agent Team Template

Spawn the `fold4-ops` team for parallel work. Roles:

| Role | Purpose |
|---|---|
| **researcher** | KG bypass research, KG monitoring, BLE analysis |
| **samsung-re** | Samsung framework RE, KnoxGuardSeService internals, SELinux policy analysis |
| **web-scout** | External research via Exa API (`exa search "query" --project fold4 --num 10`) |
| **builder** | Modify droppedapk source, rebuild APKs, compile native tools |
| **code-reviewer** | Review ALL code before deploy. BLOCKER/SUGGESTION/NIT severity. Adversarial. |
| **repo-ops** | Keep repo organized, sync source, update docs, manage git |

**How to spawn:**
```
TeamCreate → team_name: "fold4-ops"
Agent → name: "researcher", team_name: "fold4-ops", mode: "bypassPermissions", run_in_background: true
# Repeat for each role
```

**Critical team rules:**
- ALWAYS have someone verify before ANY action on the phone
- ALWAYS have someone monitoring KG state when the phone is alive
- NEVER execute without at least one reviewer PASS
- Cross-verify: if one teammate claims something, have another verify it
- Use `exa` CLI for ALL web research (`exa search "query" --project fold4 --num 10`)

---

## Build Environment

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17
NDK=/opt/homebrew/share/android-commandlinetools/ndk/29.0.14206865

# Build droppedapk:
cd ~/Downloads/AbxOverflow
./gradlew :droppedapk:assembleRelease

# Bake into exploit:
cp droppedapk/build/outputs/apk/release/droppedapk-release.apk app/src/main/assets/
./gradlew :app:assembleDebug

# Save versioned copies:
cp droppedapk/build/outputs/apk/release/droppedapk-release.apk ~/Projects/Fold4/apk/droppedapk-vXX.apk
cp app/build/outputs/apk/debug/app-debug.apk ~/Projects/Fold4/apk/exploit-app-vXX.apk

# Cross-compile native tools for ARM64:
$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android33-clang -O2 -static -o tool tool.c
adb push tool /data/local/tmp/
```

---

## Device Details

- Model: SM-F936U1 (US unlocked Galaxy Z Fold 4)
- Android: 13 (One UI 5.x)
- SPL: July 2023 (F936U1UES3CWF3)
- Bootloader: Locked (BIT 3)
- Serial: RFCW2006DLA
- kgclient UID: 10082
- kgclient package: com.samsung.android.kgclient
- KG OTP fuse: ro.boot.kg.bit=01 (permanently set)
- CVE-2024-34740: VULNERABLE
- CVE-2024-31317: VULNERABLE (patched June 2024)
- CVE-2024-0044: VULNERABLE (patched March 2024)

---

## Session History (March 20-21, 2026)

### Version Progression
- **v11-AUTOBOOT** — original working build from last night. Simple BOOT_COMPLETED unlock.
- **v12-PERMANENT** — added data wipe + firewall + watchers. KG re-locked after ~37 min (kgclient's PROCESS_CHECK alarm).
- **v13-NEUTRALIZE** — added force-stop, alarm cancellation, receiver unregistration, service neutralization. Stable 92+ min. Passed v12's failure point.
- **v14-HEAPDUMP** — added heap dump bypass (IApplicationThread.dumpHeap), Pokemon GO file listing, self-update mechanism. SELinux blocked file operations.
- **v14b** — null FD fix for heap dump (dead end — NPE in ActivityThread).
- **v14c** — FLAG_DEBUGGABLE flip on ProcessRecord (works for am dumpheap, not for JDWP/JVMTI). Verified stable.
- **v14d-PMSDEBUG** — PMS PackageSetting flag flip (makes apps genuinely debuggable from birth). Built + reviewed but failed to deploy due to SELinux label mismatch.

### Key Incidents
1. **pm uninstall incident** — uninstalled droppedapk before re-exploiting. KG re-locked, ADB died. Required factory reset. **Rule added: NEVER uninstall.**
2. **CVE-2024-31317 race condition** — Zygote injection worked but Samsung's Find My Mobile stole the spawn slot. Apps broke until reboot. **Abandoned this approach.**
3. **SELinux wall** — system_server can't read app memory (neverallow ptrace), can't open files in /data/local/tmp/ (shell_data_file), can't read PoGo's data dir (app_data_file). Every cross-process memory access blocked.

### Key Discoveries
- `am set-debug-app` does NOT enable JDWP (only shows dialog)
- packages.xml pkgFlags are recomputed from APK on every boot (can't patch debuggable)
- PMS PackageSetting cache IS modifiable at runtime and persists for the session
- FLAG_DEBUGGABLE flip in PMS cache → ProcessList adds JDWP + JAVA_DEBUGGABLE at Zygote fork
- Samsung uses `mPkgFlags` (not `pkgFlags`) on SettingBase

---

## Immediate Next Steps

1. **Factory reset + deploy v14d** (clean install, no UID mismatch)
2. **Test PMS flag flip on PoGo** — does PoGo spawn with JDWP after `flip-debug-pms`?
3. **If JDWP works:** pair PBP → connect via JDWP → read /proc/self/mem → dump libpgpplugin.so → scan for AES key
4. **If JDWP doesn't work (Play Integrity blocks):** try ADDC3E26 factory QA brute-force
5. **Fix the exploit's file ownership** — add `restorecon` to LaunchReceiver or Stage 2 to prevent future UID/label mismatches

---

## Exa API

40 keys, ~$629 credits, 40k free searches/month. CLI installed globally:
```bash
exa search "query" --project fold4 --num 10
exa contents "https://url.com" --project fold4
exa status
exa history --project fold4
```

---

## Memory Files

All persistent context is in `~/.claude/projects/-Users-kyin/memory/`:
- `project_fold4_team_template.md` — team spawn template
- `project_pbp_cve_2024_31317.md` — CVE-2024-31317 research for PBP extraction
- `project_pokeball_key_extraction.md` — PBP key extraction status
- `project_flipper_fold4_ideas.md` — Flipper Zero + Fold4 project ideas
- `feedback_never_uninstall_droppedapk.md` — CRITICAL lesson
- `feedback_use_the_team.md` — delegate, don't solo
- `feedback_always_check_with_team.md` — verify before acting
- `feedback_think_every_step.md` — think before every phone action
- 15+ other feedback files covering verification, precision, persistence
