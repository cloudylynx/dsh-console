package io.github.dshconsole.app;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.HashSet;
import java.util.Set;

/**
 * 后台监控服务
 *
 * 职责：
 *   1. 即使 App 在后台/锁屏，也定时拉 /progress
 *   2. 检测告警（内存/磁盘/温度）→ 发通知
 *   3. 检测新消息 → 发通知
 *   4. 前台服务保活（低优先级通知）
 */
public class MonitorService extends Service {

    private static final String TAG_NOTIF = "monitor";
    private static final int NOTIF_ID = 9001;

    private AppSettings settings;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean running = false;

    /** 已通知过的告警 ID，避免重复骚扰（内存即可，告警本身会恢复） */
    private final Set<String> notifiedAlerts = new HashSet<>();

    /**
     * ★已通知过的消息 ID —— 必须持久化
     *   原来用内存 Set，App 重启就清空
     *   → 服务端 messages[] 里的历史消息会全部重新弹一遍
     *   → 表现为"每次开软件都自动发测试消息"
     *   现在存到 SharedPreferences
     */
    private final Set<String> notifiedMsgs = new HashSet<>();
    private static final String PREF_NOTIFIED = "notified_msg_ids";
    private SharedPreferences spNotified;

    /** 首次启动时的水印时间：早于这个时间的消息不弹 */
    private static final String PREF_WATERMARK = "msg_watermark_ts";
    private long watermarkTs = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new AppSettings(this);

        // ★加载已通知消息 ID（持久化）
        spNotified = getSharedPreferences("dsh_console_notified", MODE_PRIVATE);
        String saved = spNotified.getString(PREF_NOTIFIED, "");
        if (!saved.isEmpty()) {
            for (String id : saved.split("\\|")) {
                if (!id.isEmpty()) notifiedMsgs.add(id);
            }
        }

        // ★水印：第一次装这个版本时，记录当前时间
        //   早于水印的消息一律不弹（避免升级后一次性弹一堆历史）
        watermarkTs = spNotified.getLong(PREF_WATERMARK, 0);
        if (watermarkTs == 0) {
            watermarkTs = System.currentTimeMillis();
            spNotified.edit().putLong(PREF_WATERMARK, watermarkTs).apply();
            DebugLog.i("msg watermark set: " + watermarkTs);
        }

        DebugLog.i("MonitorService created, notifiedMsgs=" + notifiedMsgs.size()
                + " watermark=" + watermarkTs);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if ("STOP".equals(action)) {
            DebugLog.i("MonitorService stop requested");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!running) {
            running = true;
            startForeground(NOTIF_ID,
                    Notifier.buildServiceNotification(this, "正在监控编译机状态"));
            DebugLog.i("MonitorService started (foreground)");
            poll();
        }
        return START_STICKY;
    }

    private void poll() {
        if (!running) return;

        final String url = settings.apiUrl();
        ApiClient.fetch(url, new ApiClient.Callback() {
            @Override
            public void onSuccess(Snapshot snap, long latencyMs) {
                if (!running) return;
                checkAlerts(snap);
                checkMessages(snap);
                updateServiceText(snap);
                ui.postDelayed(MonitorService.this::poll, 15000);
            }

            @Override
            public void onError(String error) {
                if (!running) return;
                DebugLog.w("MonitorService poll failed: " + error);
                ui.postDelayed(MonitorService.this::poll, 30000);
            }
        });
    }

    /** 告警检测 */
    private void checkAlerts(Snapshot snap) {
        if (!settings.isNotifyAlert()) return;

        Snapshot.Sys sys = snap.system;
        int memTh = settings.getMemThreshold();
        int diskTh = settings.getDiskThreshold();

        // 内存
        if (sys.mem >= memTh) {
            String key = "mem-" + (sys.mem / 5);  // 每 5% 一档，避免刷屏
            if (!notifiedAlerts.contains(key)) {
                notifiedAlerts.add(key);
                Notifier.alert(this, "内存告警",
                        "已用 " + sys.mem + "% （" + sys.memUsed + " / " + sys.memTotal + "）", 1);
            }
        } else if (sys.mem < memTh - 10) {
            // 恢复正常，清除标记
            notifiedAlerts.removeIf(k -> k.startsWith("mem-"));
        }

        // 磁盘
        if (sys.disk >= diskTh) {
            String key = "disk-" + (sys.disk / 5);
            if (!notifiedAlerts.contains(key)) {
                notifiedAlerts.add(key);
                Notifier.alert(this, "磁盘告警",
                        "已用 " + sys.disk + "%，剩余 " + sys.diskFree, 2);
            }
        } else if (sys.disk < diskTh - 10) {
            notifiedAlerts.removeIf(k -> k.startsWith("disk-"));
        }

        // 温度
        if (sys.cpuTemp >= 85) {
            String key = "temp-" + (sys.cpuTemp / 5);
            if (!notifiedAlerts.contains(key)) {
                notifiedAlerts.add(key);
                Notifier.alert(this, "温度告警", sys.cpuTemp + "°C，注意散热", 3);
            }
        }

        // 后端告警列表（优先使用服务端的性质）
        if (snap.alerts != null) {
            for (Snapshot.Alert a : snap.alerts) {
                if ("critical".equals(a.level) && !notifiedAlerts.contains(a.id)) {
                    notifiedAlerts.add(a.id);
                    Notifier.alert(this, a.title, a.detail, 4);
                }
            }
        }
    }

    /** 消息提示 */
    private void checkMessages(Snapshot snap) {
        if (!settings.isNotifyMessage()) return;
        if (snap.messages == null) return;

        boolean changed = false;
        for (Snapshot.Msg m : snap.messages) {
            if (m.id == null || m.id.isEmpty()) continue;
            if (notifiedMsgs.contains(m.id)) continue;

            // ★过滤：早于水印的历史消息不弹（升级后不会一次性弹一堆）
            if (m.ts > 0 && m.ts < watermarkTs) {
                notifiedMsgs.add(m.id);   // 记下但不说
                changed = true;
                continue;
            }

            notifiedMsgs.add(m.id);
            changed = true;

            DebugLog.i("new message: " + m.title + " / " + m.body);
            Notifier.message(this,
                    m.from.isEmpty() ? "DSH" : m.from,
                    m.title, m.body,
                    m.id.hashCode());
        }

        // ★持久化（关键：重启后不会重复弹）
        if (changed) saveNotifiedIds();

        // 防止集合无限增长
        if (notifiedMsgs.size() > 500) {
            notifiedMsgs.clear();
            saveNotifiedIds();
        }
    }

    /** 把已通知 ID 存盘 */
    private void saveNotifiedIds() {
        try {
            StringBuilder sb = new StringBuilder();
            for (String id : notifiedMsgs) {
                if (sb.length() > 0) sb.append('|');
                sb.append(id);
            }
            spNotified.edit().putString(PREF_NOTIFIED, sb.toString()).apply();
            DebugLog.d("notified ids saved: " + notifiedMsgs.size());
        } catch (Throwable t) {
            DebugLog.e("saveNotifiedIds failed", t);
        }
    }

    private void updateServiceText(Snapshot snap) {
        String text;
        Snapshot.Task t = snap.activeTask();
        if (t != null) {
            text = t.name + " " + Math.round(t.progress * 100) + "%";
        } else {
            text = "待命 · CPU " + snap.system.cpu + "% · 内存 " + snap.system.mem + "%";
        }
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, Notifier.buildServiceNotification(this, text));
        } catch (Throwable t2) {
            DebugLog.w("update service notification failed");
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        ui.removeCallbacksAndMessages(null);
        DebugLog.i("MonitorService destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
