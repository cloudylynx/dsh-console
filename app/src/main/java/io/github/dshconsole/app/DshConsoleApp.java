package io.github.dshconsole.app;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.util.TypedValue;

import com.google.android.material.color.DynamicColors;

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

        // ★强调色的实际应用放在 applyAccentToTheme（每个 Activity 创建时调），
        //   因为「莫奈 / 固定色」是可以在设置里随时切换的，
        //   如果在这里一次性 applyToActivitiesIfAvailable，切换后就不会再生效。
        try {
            boolean monetAvailable = DynamicColors.isDynamicColorAvailable();
            DebugLog.i("accent mode = " + s.getAccentMode()
                    + " · DynamicColors " + (monetAvailable ? "available" : "unavailable"));
        } catch (Throwable t) {
            DebugLog.e("DynamicColors probe failed", t);
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
     * 固定强调色对应的主题覆盖层。
     * 索引 = accentMode - 1（0 是莫奈，没有覆盖层）。
     * 浅色 / 深色两份定义分别在 res/values/themes.xml 和 res/values-night/themes.xml，
     * 用同名 style，系统会按当前深浅色自动挑，这里不用判断。
     */
    private static final int[] ACCENT_OVERLAYS = {
            R.style.ThemeOverlay_DshConsole_Accent1,   // 蓝
            R.style.ThemeOverlay_DshConsole_Accent2,   // 绿
            R.style.ThemeOverlay_DshConsole_Accent3,   // 橙
            R.style.ThemeOverlay_DshConsole_Accent4,   // 紫
            R.style.ThemeOverlay_DshConsole_Accent5,   // 红
            R.style.ThemeOverlay_DshConsole_Accent6,   // 青
            R.style.ThemeOverlay_DshConsole_Accent7,   // 粉
            R.style.ThemeOverlay_DshConsole_Accent8,   // 灰
    };

    /**
     * 把强调色应用到给定的 Activity 主题。
     *
     * · 莫奈模式：交给 DynamicColors（这里再调一次，保证「固定色 → 莫奈」
     *   这种切换也能立即生效，而不是要杀进程重开）
     * · 固定色模式：在 setContentView 之前叠一层 ThemeOverlay，
     *   覆盖 colorPrimary / colorPrimaryContainer 等，Material3 控件自然跟随。
     *
     * ★调用时机：必须在 Activity 里任何 View 创建之前（MainActivity.onCreate
     *   在 super.onCreate 之后、buildUi 之前调用）。
     */
    public void applyAccentToTheme(android.app.Activity activity, AppSettings settings) {
        if (activity == null || settings == null) return;

        int mode = settings.getAccentMode();

        if (mode == AppSettings.ACCENT_MONET) {
            // 动态取色由 DynamicColors 接管；从固定色切回来时也要能生效
            try {
                if (DynamicColors.isDynamicColorAvailable()) {
                    DynamicColors.applyToActivityIfAvailable(activity);
                    DebugLog.i("accent: monet applied to activity");
                } else {
                    DebugLog.i("accent: monet requested but unavailable (< Android 12)");
                }
            } catch (Throwable t) {
                DebugLog.e("accent: monet apply failed", t);
            }
            return;
        }

        int idx = mode - 1;
        if (idx < 0 || idx >= ACCENT_OVERLAYS.length) {
            DebugLog.w("accent: unknown mode " + mode + ", keep default theme");
            return;
        }

        try {
            activity.getTheme().applyStyle(ACCENT_OVERLAYS[idx], true);
            String name = (mode < AppSettings.ACCENT_NAMES.length)
                    ? AppSettings.ACCENT_NAMES[mode] : String.valueOf(mode);
            DebugLog.i("accent: overlay applied (" + name + ")");
        } catch (Throwable t) {
            DebugLog.e("accent: overlay apply failed", t);
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
