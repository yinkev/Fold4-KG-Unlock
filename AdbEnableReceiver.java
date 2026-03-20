package com.kyin.adbenab;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

public class AdbEnableReceiver extends DeviceAdminReceiver {
    private static final String TAG = "AdbEnabler";

    private void enableAdb(Context context) {
        ComponentName admin = getWho(context);
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);

        // Enable USB debugging
        try {
            dpm.setGlobalSetting(admin, Settings.Global.ADB_ENABLED, "1");
            Log.i(TAG, "ADB ENABLED!");
        } catch (Exception e) {
            Log.e(TAG, "Failed to enable ADB: " + e.getMessage());
        }

        // Enable developer options
        try {
            dpm.setGlobalSetting(admin, "development_settings_enabled", "1");
            Log.i(TAG, "Developer options enabled!");
        } catch (Exception e) {
            Log.e(TAG, "Failed to enable dev options: " + e.getMessage());
        }

        // DO NOT disable kgclient — triggers error 8133 (abnormal detection)
        Log.i(TAG, "Skipping kgclient disable to avoid 8133");
    }

    @Override
    public void onProfileProvisioningComplete(Context context, Intent intent) {
        Log.i(TAG, "onProfileProvisioningComplete - enabling ADB");
        enableAdb(context);
    }

    @Override
    public void onEnabled(Context context, Intent intent) {
        Log.i(TAG, "Device admin enabled - enabling ADB");
        enableAdb(context);
    }
}
