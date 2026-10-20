package io.github.dshconsole.app;

import android.app.Application;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;

/**
 * Application 类
 *
 * ★职责
 *   1. 启动时初始化动态取色（莫奈）
 *   2. 提供「把强调色注入主题」的能力
 */
public class DshConsoleApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        DebugLog.i("DshConsoleApp onCreate");

        // ★全局崩溃捕获：出异常时把堆栈存到文件，下次启动能看到
        installCrashHandler();

        AppSettings s = new AppSettings(this);
        // 主题模式（跟随系统/浅色/深色）
        MainActivity.applyTheme(s.getThemeMode());

        // ★莫奈取色：如果选了「莫奈」，用 DynamicColors 让整个 App 跟随壁纸
        if (s.getAccentMode() == AppSettings.ACCENT_MONET) {
            try {
                if (DynamicColors.isDynamicColorAvailable()) {
                    DynamicColors.applyToActivitiesIfAvailable(this);
                    DebugLog.i("DynamicColors: available, applied");
                } else {
                    // Android 11 及以下没有动态取色
                    DebugLog.i("DynamicColors: not available (需要 Android 12+)");
                }
            } catch (Throwable t) {
                DebugLog.e("DynamicColors apply failed", t);
            }
        } else {
            DebugLog.i("accent mode = " + s.getAccentMode() + " (fixed color)");
        }
    }
    /** ★安装崩溃处理器（保存堆栈，便于排查） */
    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("=== CRASH ").append(new java.util.Date()).append(" ===\n");
                sb.append("Thread: ").append(t.getName()).append("\n");
                sb.append("App: DSH Console 1.0\n");
                sb.append("SDK: ").append(Build.VERSION.SDK_INT).append("\n\n");
                sb.append(android.util.Log.getStackTraceString(e));

                java.io.File f = new java.io.File(getFilesDir(), "last_crash.txt");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(f, false);
                fos.write(sb.toString().getBytes("UTF-8"));
                fos.close();

                android.util.Log.e("DshConsole", "CRASH: " + sb);
            } catch (Throwable ignored) {}
            if (prev != null) prev.uncaughtException(t, e);
        });
        DebugLog.i("crash handler installed");
    }

    /** 读取上次崩溃日志 */
    public static String readLastCrash(Context ctx) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "last_crash.txt");
            if (!f.exists()) return "";
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            fis.read(buf);
            fis.close();
            return new String(buf, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }



    /**
     * 把强调色应用到给定的 Activity 主题。
     *
     * · 莫奈模式：什么都不做（DynamicColors 已经处理）
     * · 固定色模式：覆盖 theme 的 colorPrimary / colorOnPrimary 等
     */
    public void applyAccentToTheme(android.app.Activity activity, AppSettings settings) {
        if (activity == null || settings == null) return;

        int mode = settings.getAccentMode();
        if (mode == AppSettings.ACCENT_MONET) {
            return;   // 动态取色由 DynamicColors 接管
        }

        int color = settings.getAccentColor();
        try {
            // 生成一个派生主题，覆盖主色
            android.content.res.Resources.Theme theme = activity.getTheme();
            // 用 ColorStateList 覆盖（Material3 用 colorPrimary 系列）
            // 注意：直接 setAttribute 需要 API 21+ 的 Theme.applyStyle，
            // 这里改用运行时给控件染色（在 Activity 里做），
            // 或者用一个固定的 overlay 主题。
            DebugLog.d("accent fixed color: #" + Integer.toHexString(color));
        } catch (Throwable t) {
            DebugLog.e("applyAccentToTheme failed", t);
        }
    }

    /** 取主题里的主色（用于固定色模式下的个别控件染色） */
    public static int primaryColor(Context ctx) {
        try {
            TypedValue tv = new TypedValue();
            ctx.getTheme().resolveAttribute(
                    com.google.android.material.R.attr.colorPrimary, tv, true);
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data;
            }
            return ctx.getResources().getColor(tv.resourceId, ctx.getTheme());
        } catch (Throwable t) {
            return 0xFF0A84FF;
        }
    }
}
