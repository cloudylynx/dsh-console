package io.github.dshconsole.app;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 网络层：拉取 /progress，带调试日志 */
public class ApiClient {

    public interface Callback {
        void onSuccess(Snapshot snapshot, long latencyMs);
        void onError(String error);
    }

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static volatile Snapshot lastSnapshot;

    public static Snapshot getLastSnapshot() { return lastSnapshot; }

    public static void fetch(String url, Callback cb) {
        DebugLog.d("GET " + url);
        POOL.execute(() -> {
            long t0 = System.currentTimeMillis();
            HttpURLConnection conn = null;
            try {
                URL u = new URL(url);
                conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/json");

                int code = conn.getResponseCode();
                long ms = System.currentTimeMillis() - t0;
                DebugLog.d("HTTP " + code + " in " + ms + "ms");

                if (code != 200) {
                    final String err = "HTTP " + code;
                    DebugLog.w("fetch failed: " + err);
                    UI.post(() -> cb.onError(err));
                    return;
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    char[] buf = new char[8192];
                    int n;
                    while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
                }

                String body = sb.toString();
                DebugLog.d("response " + body.length() + " bytes");

                Snapshot snap = Snapshot.parse(body);
                lastSnapshot = snap;
                DebugLog.i("parsed: tasks=" + snap.tasks.size()
                        + " cpu=" + snap.system.cpu + "%"
                        + " mem=" + snap.system.mem + "%"
                        + " disk=" + snap.system.disk + "%"
                        + " alerts=" + snap.alerts.size()
                        + " msgs=" + snap.messages.size());

                final long latency = ms;
                UI.post(() -> cb.onSuccess(snap, latency));

            } catch (Throwable t) {
                DebugLog.e("fetch exception: " + url, t);
                final String err = t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : ": " + t.getMessage());
                UI.post(() -> cb.onError(err));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    /** 同步测试连接（用于设置里的「测试」按钮） */
    public static String testConnection(String url) {
        HttpURLConnection conn = null;
        long t0 = System.currentTimeMillis();
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            long ms = System.currentTimeMillis() - t0;
            if (code == 200) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    char[] buf = new char[8192];
                    int n;
                    while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
                }
                Snapshot s = Snapshot.parse(sb.toString());
                return "成功 (HTTP 200, " + ms + "ms)\n"
                        + "任务: " + s.tasks.size() + " 个\n"
                        + "CPU: " + s.system.cpu + "%\n"
                        + "内存: " + s.system.mem + "%\n"
                        + "磁盘: " + s.system.disk + "%\n"
                        + "网速: ↓" + fmtSpeed(s.system.netRxSpeed)
                        + " ↑" + fmtSpeed(s.system.netTxSpeed) + "\n"
                        + "告警: " + s.alerts.size() + " 条";
            } else {
                return "失败: HTTP " + code + " (" + ms + "ms)";
            }
        } catch (Throwable t) {
            return "失败: " + t.getClass().getSimpleName()
                    + "\n" + (t.getMessage() == null ? "" : t.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static String fmtSpeed(long bytesPerSec) {
        if (bytesPerSec < 1024) return bytesPerSec + " B/s";
        if (bytesPerSec < 1024 * 1024) return String.format("%.1f KB/s", bytesPerSec / 1024.0);
        return String.format("%.1f MB/s", bytesPerSec / 1048576.0);
    }
}
