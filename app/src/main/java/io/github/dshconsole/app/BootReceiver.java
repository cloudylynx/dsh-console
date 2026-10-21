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

        // ★Android 15（API 35）起，targetSdk 35+ 的应用不允许在收到
        //   BOOT_COMPLETED 时启动 dataSync 型前台服务，会抛
        //   ForegroundServiceStartNotAllowedException。
        //   这里直接跳过，避免开机就崩一次；用户手动打开 App 后会正常启动。
        if (Build.VERSION.SDK_INT >= 35) {
            DebugLog.w("BootReceiver: skip auto-start on API 35+ "
                    + "(dataSync FGS is not allowed from BOOT_COMPLETED)");
            return;
        }

        DebugLog.i("BootReceiver: starting monitor");
        Intent i = new Intent(ctx, MonitorService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable t) {
            // Android 12+ 的后台启动限制等情况下也不要让接收器崩掉
            DebugLog.w("BootReceiver: start service failed: " + t);
        }
    }
}
