# Research Files

Raw agent research outputs from the KG unlock project. These are full conversation logs (JSON format) containing web search results, analysis, and findings.

| File | Topic | Date |
|---|---|---|
| `01-knoxguard-native-ta-state.txt` | KG TA state machine, RPMB internals, permission bypass methods, KnoxGuardSeService reflection | 2026-03-19 |
| `02-error-8133-integrity-check.txt` | Error 8133 (abnormal detection), IntegritySeUtil checks, what triggers it, how to clear | 2026-03-19 |
| `03-kgclient-cache-behavior.txt` | kgclient lock policy caching, SharedPreferences locations, File.delete() vs pm clear, CONNECTIVITY_CHANGE receiver | 2026-03-20 |

## Key findings across all research

- **TA states**: Prenormal(0), Checking(1), Active(2), Locked(3), Completed(4), Error(5)
- **Error 8133**: Triggered by `pm disable-user` on kgclient — IntegritySeUtil checks 6 components on boot
- **Error 3001**: Triggered by `pm clear` (PACKAGE_DATA_CLEARED broadcast) — direct File.delete() does NOT trigger it
- **kgclient cache**: After contacting Samsung servers, kgclient stores lock policy in local data. Replays on every boot.
- **Re-lock path**: CONNECTIVITY_CHANGE → kgclient contacts server → receives lock command → calls lockScreen() on KnoxGuardSeService
