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
- NEVER `adb install -r` on droppedapk — corrupts UID mapping
- NEVER `pm disable-user` on kgclient — triggers error 8133
- NEVER `pm clear` on kgclient — triggers error 3001
- NEVER sign into Samsung account
- NEVER update firmware
- All droppedapk changes MUST be baked into source before re-running exploit
- Always save versioned APK copies before building new ones

### Current State (v12-PERMANENT)
- 4-phase approach: wipe kgclient data → unlock → firewall block → event-driven watchers
- KG unlocks successfully on boot but kgclient re-locks via in-memory state after ~37 min
- ADB stays alive via ContentObserver
- Background ADB poll auto-re-unlocks when Locked detected
- Remaining problem: kgclient's in-memory re-lock bypasses file watchers and firewall
