package com.micklab.llamachat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * FloatOverlayServiceのクラッシュ後の自動再起動と、
 * デバイス再起動後のサービス復旧を担当するBroadcastReceiver。
 */
public final class ServiceRestartReceiver extends BroadcastReceiver {
    static final String ACTION_RESTART = "com.micklab.llamachat.action.RESTART_FLOAT_SERVICE";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (!ACTION_RESTART.equals(action) && !Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            return;
        }
        try {
            Intent serviceIntent = new Intent(context, FloatOverlayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception ignored) {}
    }
}
