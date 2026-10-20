package io.github.dshconsole.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 开机自启监控服务 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (a == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;

        AppSettings s = new AppSettings(ctx);
        if (!s.isMonitorEnabled()) return;

        DebugLog.i("BootReceiver: starting monitor");
        Intent i = new Intent(ctx, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }
}
