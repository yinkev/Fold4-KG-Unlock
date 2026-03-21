## Fold4 KG Unlock Project

### Team Setup
On session start, offer to spawn the ops team (`fold4-ops`). Roles:

- **researcher** — Deep research into KG bypass, kgclient behavior, Samsung framework internals
- **samsung-re** — Reverse engineering KnoxGuardSeService, mapping Binder transactions, finding re-lock call paths
- **web-scout** — External research via Exa API, XDA, GitHub, security blogs
- **builder** — Modify droppedapk source, rebuild APKs, save versioned copies
- **code-reviewer** — Review all code changes before deploying to device. Check for crashes, 8133 triggers, missing methods
- **repo-ops** — Keep repo organized: source sync, docs, APK inventory, git status

### Key Paths
- Project root: /Users/kyin/Projects/Fold4
- Exploit source: ~/Downloads/AbxOverflow/
- Droppedapk source: ~/Downloads/AbxOverflow/droppedapk/src/main/java/com/example/abxoverflow/droppedapk/
- Pre-built APKs: /Users/kyin/Projects/Fold4/apk/
- Exa API keys: /Users/kyin/Projects/Fold4/../tavily-key-generator/exa_accounts.txt

### Device
- Samsung Galaxy Z Fold 4, SM-F936U1, Android 13, July 2023 SPL
- Serial: RFCW2006DLA
- kgclient UID: 10082
- Exploit: CVE-2024-34740 (AbxOverflow)

### Critical Rules
- NEVER `pm uninstall` droppedapk before running Stage 1 — removes BOOT_COMPLETED safety net, phone bricks on reboot (INCIDENT: lost phone on 2026-03-20)
- NEVER `adb install -r` on droppedapk — corrupts UID mapping
- NEVER `pm disable-user` on kgclient — triggers error 8133
- NEVER `pm clear` on kgclient — triggers error 3001
- NEVER null `mLockSettingsService` — causes Utils.powerOff() if retry alarm fires
- NEVER sign into Samsung account
- NEVER update firmware
- All droppedapk changes MUST be baked into source before re-running exploit
- Always save versioned APK copies before building new ones

### Safe Deployment Sequence (MANDATORY)
To deploy a new droppedapk version, use ONE of these approaches:

**Option A — Self-update (preferred, no exploit re-run):**
```
adb push new.apk /data/local/tmp/droppedapk-update.apk
# Use PackageInstaller staging to bypass SELinux:
SIZE=$(wc -c < new.apk); SESSION=$(pm install-create -S $SIZE | grep -oE '[0-9]+')
cat new.apk | pm install-write -S $SIZE $SESSION base -
am start -n com.example.abxoverflow.droppedapk/.MainActivity --es action grab-staged --ei session $SESSION
```

**Option B — Exploit re-run (DO NOT uninstall first):**
```
# Stage 1 — old droppedapk stays in packages.xml, protects through reboot
adb shell am start ... --ei stage 1
# [reboot — old droppedapk fires, phone stays alive]
# Stage 2 — replaces droppedapk with new version
adb shell am start ... --ei stage 2
# [reboot — new droppedapk fires]
```

### Current State (v14-HEAPDUMP)
- 6-phase approach: wipe → unlock → firewall → watchers+watchdog → force-stop → neutralize
- v13-NEUTRALIZE proven stable (80+ min), v14 adds heap dump + PoGO file listing
- Self-update mechanism deployed (PackageInstaller staging for SELinux bypass)
- kgclient respawns every ~10s but cannot re-lock (receivers unregistered, callbacks nulled, alarms cancelled)
