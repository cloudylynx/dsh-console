package io.github.dshconsole.app;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 全局调试日志缓冲区
 *
 * ★为什么需要这个
 *   以后出 bug 时，直接在手机 App 的「设置 → 调试」里看日志，
 *   不用重新编译、不用连 adb。
 *
 * 特性：
 *   · 内存环形缓冲（最多 500 条）
 *   · 分级：D/I/W/E
 *   · 同时输出到 logcat
 *   · 可复制、清空、导出
 */
public class DebugLog {

    private static final String TAG = "DshConsole";
    private static final int MAX_LINES = 500;

    public static class Entry {
        public final long ts;
        public final String level;
        public final String msg;

        Entry(long ts, String level, String msg) {
            this.ts = ts;
            this.level = level;
            this.msg = msg;
        }

        public String format() {
            SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
            return f.format(new Date(ts)) + " " + level + "/ " + msg;
        }
    }

    private static final List<Entry> BUFFER = new ArrayList<>();
    private static final Object LOCK = new Object();

    public static void d(String msg) { add("D", msg); }
    public static void i(String msg) { add("I", msg); }
    public static void w(String msg) { add("W", msg); }
    public static void e(String msg) { add("E", msg); }

    public static void e(String msg, Throwable t) {
        add("E", msg + " :: " + (t == null ? "null" : t.getClass().getSimpleName()
                + ": " + t.getMessage()));
    }

    private static void add(String level, String msg) {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            BUFFER.add(new Entry(now, level, msg));
            while (BUFFER.size() > MAX_LINES) BUFFER.remove(0);
        }
        // 同时打 logcat
        switch (level) {
            case "E": Log.e(TAG, msg); break;
            case "W": Log.w(TAG, msg); break;
            case "D": Log.d(TAG, msg); break;
            default: Log.i(TAG, msg); break;
        }
    }

    /** 按时间正序返回所有日志 */
    public static String dump() {
        StringBuilder sb = new StringBuilder();
        synchronized (LOCK) {
            for (Entry e : BUFFER) sb.append(e.format()).append('\n');
        }
        return sb.toString();
    }

    /** 倒序（最新在上） */
    public static String dumpReversed() {
        StringBuilder sb = new StringBuilder();
        synchronized (LOCK) {
            for (int i = BUFFER.size() - 1; i >= 0; i--) {
                sb.append(BUFFER.get(i).format()).append('\n');
            }
        }
        return sb.toString();
    }

    public static int size() {
        synchronized (LOCK) { return BUFFER.size(); }
    }

    public static void clear() {
        synchronized (LOCK) { BUFFER.clear(); }
        Log.i(TAG, "debug log cleared");
    }
}
