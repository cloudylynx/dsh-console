package io.github.dshconsole.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 全局设置
 */
public class AppSettings {

    private static final String NAME = "dsh_console";
    private final SharedPreferences sp;

    // Keys
    private static final String K_HOST = "host";
    private static final String K_PORT = "port";
    private static final String K_DSH_URL = "dsh_url";
    private static final String K_DSH_TOKEN = "dsh_token";
    private static final String K_THEME = "theme_mode";       // 0跟随 1浅色 2深色
    private static final String K_ACCENT = "accent_color";    // 强调色
    private static final String K_ACCENT_MODE = "accent_mode"; // 0=莫奈 1=蓝 2=绿...
    private static final String K_INTERVAL = "refresh_sec";   // 刷新间隔
    private static final String K_CHART_POINTS = "chart_points";
    private static final String K_MONITOR = "monitor_enabled"; // 后台监控
    private static final String K_NOTIFY_MSG = "notify_msg";
    private static final String K_NOTIFY_ALERT = "notify_alert";
    private static final String K_MEM_TH = "mem_threshold";
    private static final String K_DISK_TH = "disk_threshold";
    private static final String K_DEBUG = "debug_enabled";
    private static final String K_TERM_URL = "terminal_url";
    private static final String K_TERM_USER = "terminal_user";
    private static final String K_TERM_PASS = "terminal_pass";
    private static final String K_FILE_PORT = "file_port";
    private static final String K_LOG_PORT = "log_port";
    private static final String K_TERM_FONT = "terminal_font_size";
    private static final String K_KEYPAD_HEIGHT = "keypad_height";
    private static final String K_KEYPAD_THEME = "keypad_theme";
    private static final String K_TAB_ORDER = "tab_order";
    private static final String K_TAB_ORDER_VER = "tab_order_ver";
    /**
     * 底栏顺序的格式版本。
     * 历史上有过栏数变化，旧版本存下来的顺序按下标已经对不上，
     * 所以版本号一变就把已存的顺序作废、回到默认顺序。
     */
    private static final int TAB_ORDER_VER = 3;

    public static final int THEME_SYSTEM = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;

    public AppSettings(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /**
     * ★是否已配置服务器地址
     *   未配置时各页面应显示"请在设置中提供服务器地址"，而不是发请求报错。
     */
    public boolean isConfigured() {
        String h = getHost();
        return h != null && !h.trim().isEmpty();
    }

    /** 未配置时统一提示文案 */
    public static final String NOT_CONFIGURED = "请在设置中提供服务器地址";

    public String getHost() { return sp.getString(K_HOST, ""); }
    public void setHost(String v) { sp.edit().putString(K_HOST, v).apply(); }

    public int getPort() { return sp.getInt(K_PORT, 8080); }
    public void setPort(int v) { sp.edit().putInt(K_PORT, v).apply(); }

    public String getDshUrl() {
        return sp.getString(K_DSH_URL, "");
    }
    public void setDshUrl(String v) { sp.edit().putString(K_DSH_URL, v).apply(); }

    public String getDshToken() { return sp.getString(K_DSH_TOKEN, ""); }
    public void setDshToken(String v) { sp.edit().putString(K_DSH_TOKEN, v).apply(); }

    public int getThemeMode() { return sp.getInt(K_THEME, THEME_SYSTEM); }
    public void setThemeMode(int v) { sp.edit().putInt(K_THEME, v).apply(); }

    public static final int ACCENT_MONET = 0;   // ★莫奈取色（跟随壁纸）

    public int getAccentMode() { return sp.getInt(K_ACCENT_MODE, ACCENT_MONET); }
    public void setAccentMode(int v) { sp.edit().putInt(K_ACCENT_MODE, v).apply(); }

    /** 预设强调色（mode >= 1 时用） */
    public static final int[] ACCENT_COLORS = {
            0xFF0A84FF, // 1 蓝
            0xFF34C759, // 2 绿
            0xFFFF9F0A, // 3 橙
            0xFFAF52DE, // 4 紫
            0xFFFF3B30, // 5 红
            0xFF32ADE6, // 6 青
            0xFFFF2D55, // 7 粉
            0xFF8E8E93, // 8 灰
    };
    public static final String[] ACCENT_NAMES = {
            "莫奈取色", "蓝", "绿", "橙", "紫", "红", "青", "粉", "灰"
    };

    public int getAccentColor() {
        int mode = getAccentMode();
        if (mode == ACCENT_MONET) {
            return sp.getInt(K_ACCENT, 0xFF0A84FF);  // 实际用主题色，这个只是兜底
        }
        int i = mode - 1;
        if (i >= 0 && i < ACCENT_COLORS.length) return ACCENT_COLORS[i];
        return 0xFF0A84FF;
    }
    public void setAccentColor(int v) { sp.edit().putInt(K_ACCENT, v).apply(); }

    public int getInterval() { return sp.getInt(K_INTERVAL, 3); }
    public void setInterval(int v) { sp.edit().putInt(K_INTERVAL, Math.max(1, v)).apply(); }

    public int getChartPoints() { return sp.getInt(K_CHART_POINTS, 60); }
    public void setChartPoints(int v) { sp.edit().putInt(K_CHART_POINTS, Math.max(10, v)).apply(); }

    public boolean isMonitorEnabled() { return sp.getBoolean(K_MONITOR, true); }
    public void setMonitorEnabled(boolean v) { sp.edit().putBoolean(K_MONITOR, v).apply(); }

    public boolean isNotifyMessage() { return sp.getBoolean(K_NOTIFY_MSG, true); }
    public void setNotifyMessage(boolean v) { sp.edit().putBoolean(K_NOTIFY_MSG, v).apply(); }

    public boolean isNotifyAlert() { return sp.getBoolean(K_NOTIFY_ALERT, true); }
    public void setNotifyAlert(boolean v) { sp.edit().putBoolean(K_NOTIFY_ALERT, v).apply(); }

    public int getMemThreshold() { return sp.getInt(K_MEM_TH, 90); }
    public void setMemThreshold(int v) { sp.edit().putInt(K_MEM_TH, v).apply(); }

    public int getDiskThreshold() { return sp.getInt(K_DISK_TH, 90); }
    public void setDiskThreshold(int v) { sp.edit().putInt(K_DISK_TH, v).apply(); }

    public boolean isDebugEnabled() { return sp.getBoolean(K_DEBUG, true); }
    public void setDebugEnabled(boolean v) { sp.edit().putBoolean(K_DEBUG, v).apply(); }

    public String getTerminalUrl() {
        return sp.getString(K_TERM_URL, "");
    }
    public void setTerminalUrl(String v) { sp.edit().putString(K_TERM_URL, v).apply(); }

    public String getTerminalUser() { return sp.getString(K_TERM_USER, ""); }
    public void setTerminalUser(String v) { sp.edit().putString(K_TERM_USER, v).apply(); }

    public String getTerminalPass() { return sp.getString(K_TERM_PASS, ""); }
    public void setTerminalPass(String v) { sp.edit().putString(K_TERM_PASS, v).apply(); }

    /** 辅助键盘高度（dp）：控制按键的垂直内边距 */
    public int getKeypadHeight() { return sp.getInt(K_KEYPAD_HEIGHT, 8); }
    public void setKeypadHeight(int v) {
        sp.edit().putInt(K_KEYPAD_HEIGHT, Math.max(3, Math.min(16, v))).apply();
    }

    /** 辅助键盘配色：0=跟随主题(自动) 1=深色 2=浅色 */
    public int getKeypadTheme() { return sp.getInt(K_KEYPAD_THEME, 0); }
    public void setKeypadTheme(int v) { sp.edit().putInt(K_KEYPAD_THEME, v).apply(); }

    /** 终端字号（px，实际用 CSS fontSize） */
    public int getTerminalFontSize() { return sp.getInt(K_TERM_FONT, 14); }
    public void setTerminalFontSize(int v) {
        sp.edit().putInt(K_TERM_FONT, Math.max(8, Math.min(32, v))).apply();
    }

    /** 带字号参数的终端 URL */
    public String getTerminalUrlWithFont() {
        String base = getTerminalUrl();
        int size = getTerminalFontSize();
        String sep = base.contains("?") ? "&" : "?";
        return base + sep + "fontSize=" + size;
    }

    public int getFilePort() { return sp.getInt(K_FILE_PORT, 8080); }
    public void setFilePort(int v) { sp.edit().putInt(K_FILE_PORT, v).apply(); }

    /** 编译日志服务端口（与主服务同端口时用 /api/logs） */
    public int getLogPort() { return sp.getInt(K_LOG_PORT, 8080); }
    public void setLogPort(int v) { sp.edit().putInt(K_LOG_PORT, v).apply(); }

    /**
     * 底栏顺序
     *
     * 索引含义: 0=首页 1=终端 2=文件 3=DSH 4=设置
     * 默认: [0,1,2,3,4]
     *
     * ★这里所有下标都必须以 TAB_NAMES.length 为界：
     *   旧代码写的是 `v < 6` 但 `used[]` 只有 5 个元素，
     *   一旦 SharedPreferences 里存进 "5" 就会越界崩溃。
     */
    private static final String DEFAULT_ORDER = "0,1,2,3,4";

    /** 底栏项数量（TAB_NAMES / TAB_ICONS / TAB_IDS 共用） */
    private static final int TAB_COUNT = 5;

    public int[] getTabOrder() {
        // ★版本迁移：旧格式的顺序与当前栏数不兼容，重置
        int savedVer = sp.getInt(K_TAB_ORDER_VER, 1);
        if (savedVer < TAB_ORDER_VER) {
            sp.edit().remove(K_TAB_ORDER)
                    .putInt(K_TAB_ORDER_VER, TAB_ORDER_VER).apply();
            DebugLog.i("tab order migrated to v" + TAB_ORDER_VER);
        }

        String raw = sp.getString(K_TAB_ORDER, DEFAULT_ORDER);
        String[] parts = raw.split(",");
        int[] out = new int[TAB_COUNT];
        boolean[] used = new boolean[TAB_COUNT];
        int n = 0;

        for (String p : parts) {
            if (n >= TAB_COUNT) break;
            try {
                int v = Integer.parseInt(p.trim());
                if (v >= 0 && v < TAB_COUNT && !used[v]) {
                    out[n++] = v;
                    used[v] = true;
                }
            } catch (Exception ignored) {
                // 非法项跳过，后面统一补齐
            }
        }

        // 补齐缺失的项，保证返回的 5 个下标互不重复且都在范围内
        for (int i = 0; i < TAB_COUNT && n < TAB_COUNT; i++) {
            if (!used[i]) {
                out[n++] = i;
                used[i] = true;
            }
        }
        return out;
    }

    public void setTabOrder(int[] order) {
        StringBuilder sb = new StringBuilder();
        boolean[] used = new boolean[TAB_COUNT];
        if (order != null) {
            for (int i = 0; i < order.length && sb.length() < 20; i++) {
                int v = order[i];
                if (v < 0 || v >= TAB_COUNT || used[v]) continue;
                used[v] = true;
                if (sb.length() > 0) sb.append(',');
                sb.append(v);
            }
        }
        for (int i = 0; i < TAB_COUNT; i++) {
            if (!used[i]) {
                if (sb.length() > 0) sb.append(',');
                sb.append(i);
            }
        }
        sp.edit().putString(K_TAB_ORDER, sb.toString()).apply();
    }

    /** 底栏项的显示名 */
    public static final String[] TAB_NAMES = {"首页", "终端", "文件", "DSH", "设置"};

    public String apiUrl() {
        return "http://" + getHost() + ":" + getPort() + "/progress";
    }
}
