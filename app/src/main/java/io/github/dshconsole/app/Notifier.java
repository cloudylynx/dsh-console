package io.github.dshconsole.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 通知统一入口 */
public class Notifier {

    private static final String CH_SERVICE = "console_service";
    private static final String CH_ALERT = "console_alert";
    private static final String CH_MSG = "console_message";

    private static final int ID_SERVICE = 9001;
    private static final int ID_ALERT_BASE = 9100;
    private static final int ID_MSG_BASE = 9200;

    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);

        NotificationChannel svc = new NotificationChannel(
                CH_SERVICE, "后台监控", NotificationManager.IMPORTANCE_MIN);
        svc.setShowBadge(false);
        svc.setSound(null, null);
        nm.createNotificationChannel(svc);

        NotificationChannel alert = new NotificationChannel(
                CH_ALERT, "系统告警", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("内存/磁盘/温度异常");
        nm.createNotificationChannel(alert);

        NotificationChannel msg = new NotificationChannel(
                CH_MSG, "DSH 消息", NotificationManager.IMPORTANCE_DEFAULT);
        msg.setDescription("发来的消息和需要选择的问题");
        nm.createNotificationChannel(msg);
    }

    /** 前台服务通知 */
    public static Notification buildServiceNotification(Context ctx, String text) {
        ensureChannels(ctx);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, MainActivity.class),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(ctx, CH_SERVICE)
                : new Notification.Builder(ctx);

        return b.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("DSH 控制台")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    /** 告警通知 */
    public static void alert(Context ctx, String title, String detail, int key) {
        ensureChannels(ctx);
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);

        PendingIntent pi = PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, MainActivity.class),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(ctx, CH_ALERT)
                : new Notification.Builder(ctx);

        Notification n = b.setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("⚠ " + title)
                .setContentText(detail)
                .setStyle(new Notification.BigTextStyle().bigText(detail))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .build();

        nm.notify(ID_ALERT_BASE + (key % 100), n);
        DebugLog.i("alert notification: " + title + " - " + detail);
    }

    /** 消息通知 */
    public static void message(Context ctx, String from, String title, String body, int key) {
        ensureChannels(ctx);
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);

        PendingIntent pi = PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, MainActivity.class),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(ctx, CH_MSG)
                : new Notification.Builder(ctx);

        Notification n = b.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title.isEmpty() ? "DSH" : title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();

        nm.notify(ID_MSG_BASE + (key % 100), n);
        DebugLog.i("message notification: " + title + " - " + body);
    }

    /** 测试通知 */
    public static void test(Context ctx) {
        alert(ctx, "测试告警", "如果你看到这条，说明通知通道正常 ✅", 0);
    }
}
