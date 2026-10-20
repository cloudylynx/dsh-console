package io.github.dshconsole.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * 主界面：底部三栏
 *   首页 / DSH / 设置
 */
public class MainActivity extends AppCompatActivity {

    private AppSettings settings;
    private BottomNavigationView nav;
    private FrameLayout container;
    /** ★二级页覆盖层（消息/快捷命令），与底栏 Fragment 完全隔离 */
    private FrameLayout overlayContainer;

    private HomeFragment homeFragment;
    private TerminalFragment terminalFragment;
    private FilesFragment filesFragment;
    private MessagesFragment messagesFragment;
    private DshFragment dshFragment;
    private SettingsFragment settingsFragment;
    private Fragment current;
    private int selectedTab = 0;
    private int pendingShowTab = -1;   // ★待显示的 tab（-1 表示无）
    private static final String KEY_TAB = "selected_tab";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        settings = new AppSettings(this);
        applyTheme(settings.getThemeMode());
        super.onCreate(savedInstanceState);

        applyAccent();

        DebugLog.i("=== MainActivity onCreate (SDK " + Build.VERSION.SDK_INT
                + ", recreate=" + (savedInstanceState != null) + ") ===");

        if (savedInstanceState != null) {
            selectedTab = savedInstanceState.getInt(KEY_TAB, 0);
        }

        buildUi();
        requestNotificationPermission();

        if (settings.isMonitorEnabled()) {
            startMonitorService();
        }
    }

    /** 应用主题（跟随系统 / 浅色 / 深色） */
    public static void applyTheme(int mode) {
        switch (mode) {
            case AppSettings.THEME_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case AppSettings.THEME_DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(
                        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }

    /**
     * ★应用强调色（包括莫奈取色）
     *
     * 莫奈取色 = Android 12+ 的 Material You DynamicColors：
     *   系统从壁纸提取主色，App 自动跟随
     * 固定色 = 用一个预设色覆盖主题的 colorPrimary
     */
    public void applyAccent() {
        if (!(getApplication() instanceof DshConsoleApp)) return;
        DshConsoleApp app = (DshConsoleApp) getApplication();
        app.applyAccentToTheme(this, settings);
    }

    private void buildUi() {
        container = new FrameLayout(this);
        container.setId(View.generateViewId());

        nav = new BottomNavigationView(this);
        nav.setId(View.generateViewId());
        buildNavMenu();   // ★按设置的顺序动态构建
        nav.setOnItemSelectedListener(item -> {
            // ★关键：点底栏时先关闭二级页覆盖层
            //   否则 overlay 会一直盖住内容 → 看起来是"空白"
            if (isOverlayOpen()) {
                closeOverlay();
            }
            int idx = tabIndexById(item.getItemId());
            if (idx >= 0) showTab(idx);
            return true;
        });

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.addView(container, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(nav, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        // ★二级页覆盖层：作为最外层包裹，盖住 container + nav
        overlayContainer = new FrameLayout(this);
        overlayContainer.setId(View.generateViewId());
        overlayContainer.setVisibility(View.GONE);
        overlayContainer.setBackgroundColor(0xFFFAFAFA);

        // ★修复：状态栏遮挡标题
        //   1) 让内容区避开状态栏（顶部 inset）
        //   2) 底部导航避开手势条（底部 inset）
        //   3) 状态栏本身用 surface 色（主题里已设）
        // 外层容器：root（正常界面）+ overlay（二级页）
        FrameLayout outer = new FrameLayout(this);
        outer.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        outer.addView(overlayContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        applyWindowInsets(outer);

        setContentView(outer);

        // ★修复：重建时不立即 showTab
        //   因为 onCreate 阶段 FragmentManager 还在恢复旧事务，
        //   commit() 会和恢复冲突 → 导致空白
        //   改为在 onPostResume（恢复完成后）再显示
        if (selectedTab >= 0 && selectedTab < TAB_IDS.length) {
            nav.setSelectedItemId(TAB_IDS[selectedTab]);
        }
        pendingShowTab = selectedTab;
    }

    /** 底栏图标资源（按 tab 索引） */
    private static final int[] TAB_ICONS = {
            R.drawable.ic_home,      // 0 首页
            R.drawable.ic_terminal,  // 1 终端
            R.drawable.ic_folder,    // 2 文件
            R.drawable.ic_dsh,       // 3 DSH
            R.drawable.ic_settings,  // 4 设置
    };

    /** 稳定的 menu item id（用于把 id 映射回 tab 索引） */
    private static final int[] TAB_IDS = {
            R.id.nav_home, R.id.nav_terminal, R.id.nav_files,
            R.id.nav_dsh, R.id.nav_settings
    };

    /** ★按用户设置的顺序动态构建底栏 */
    private void buildNavMenu() {
        try {
            // ① 先 inflate：保证 R.id.nav_xxx 与资源被引用（防 shrinkResources 裁剪）
            try {
                nav.inflateMenu(R.menu.bottom_nav);
            } catch (Throwable t) {
                DebugLog.e("inflateMenu failed", t);
            }

            // ② 再清空，按用户顺序重建
            nav.getMenu().clear();

            int[] order = settings.getTabOrder();
            if (order == null || order.length == 0) {
                order = new int[]{0, 1, 2, 3, 4};
            }

            for (int i = 0; i < order.length; i++) {
                int idx = order[i];
                if (idx < 0 || idx >= TAB_IDS.length || idx >= TAB_ICONS.length
                        || idx >= AppSettings.TAB_NAMES.length) {
                    DebugLog.w("bad tab index: " + idx + " at " + i);
                    continue;
                }
                MenuItem item = nav.getMenu().add(0, TAB_IDS[idx], i,
                        AppSettings.TAB_NAMES[idx]);
                item.setIcon(TAB_ICONS[idx]);
            }

            // ③ 兜底：如果一个都没加成功，用默认 6 项
            if (nav.getMenu().size() == 0) {
                DebugLog.w("nav empty, using hardcoded default");
                for (int i = 0; i < 5; i++) {
                    MenuItem item = nav.getMenu().add(0, TAB_IDS[i], i,
                            AppSettings.TAB_NAMES[i]);
                    item.setIcon(TAB_ICONS[i]);
                }
            }

            DebugLog.i("nav built, size=" + nav.getMenu().size()
                    + " order=" + java.util.Arrays.toString(order));
        } catch (Throwable t) {
            DebugLog.e("buildNavMenu failed", t);
            // ④ 最后兜底：全部硬编码
            try {
                nav.getMenu().clear();
                String[] names = {"首页", "终端", "文件", "DSH", "设置"};
                int[] ids = {R.id.nav_home, R.id.nav_terminal, R.id.nav_files,
                        R.id.nav_dsh, R.id.nav_settings};
                for (int i = 0; i < 5; i++) {
                    nav.getMenu().add(0, ids[i], i, names[i]);
                }
            } catch (Throwable ignored) {}
        }
    }

    /** menu item id → tab 索引 */
    /** ★供设置页调用：终端设置变了（应用字号） */
    public void onTerminalSettingsChanged() {
        try {
            if (terminalFragment != null) {
                terminalFragment.onAllSettingsChanged();
            }
        } catch (Throwable t) {
            DebugLog.e("onTerminalSettingsChanged failed", t);
        }
    }

    /** ★供设置页调用：刷新终端页面 */
    public void onTerminalReload() {
        try {
            if (terminalFragment != null) {
                terminalFragment.reloadTerminal();
            }
        } catch (Throwable t) {
            DebugLog.e("onTerminalReload failed", t);
        }
    }

    /**
     * ★更新消息未读角标
     *   用 Material 的 BadgeDrawable，自动跟随莫奈取色
     */
    /** ★当前未读数（供首页消息按钮显示角标） */
    private int unreadMessages = 0;

    public int getUnreadMessages() {
        return unreadMessages;
    }

    /** ★更新未读数（首页消息按钮会读这个值） */
    public void updateMessageBadge(int unreadCount) {
        try {
            this.unreadMessages = unreadCount;
            DebugLog.d("unread messages: " + unreadCount);
            // 如果首页已创建，通知它刷新角标
            if (homeFragment != null && homeFragment.isAdded()) {
                homeFragment.updateMsgBadge(unreadCount);
            }
        } catch (Throwable t) {
            DebugLog.e("updateMessageBadge failed", t);
        }
    }

    /** ★打开消息中心（二级页） */
    /**
     * ★打开二级页（消息/快捷命令）
     *   用独立 overlay 容器，完全不影响底栏 Fragment 状态
     *   → 不会再出现"点底栏空白"的 bug
     */
    private void openOverlay(Fragment f, String tag) {
        try {
            if (overlayContainer == null) {
                DebugLog.e("overlayContainer is null");
                return;
            }
            getSupportFragmentManager().beginTransaction()
                    .replace(overlayContainer.getId(), f, tag)
                    .commitNow();
            overlayContainer.setVisibility(View.VISIBLE);
            DebugLog.i("overlay opened: " + tag);
        } catch (Throwable t) {
            DebugLog.e("openOverlay failed", t);
        }
    }

    /** ★关闭二级页，返回主界面 */
    public void closeOverlay() {
        try {
            if (overlayContainer == null) return;
            Fragment f = getSupportFragmentManager().findFragmentById(overlayContainer.getId());
            if (f != null) {
                getSupportFragmentManager().beginTransaction()
                        .remove(f).commitNow();
            }
            overlayContainer.setVisibility(View.GONE);
            DebugLog.i("overlay closed");
        } catch (Throwable t) {
            DebugLog.e("closeOverlay failed", t);
            if (overlayContainer != null) overlayContainer.setVisibility(View.GONE);
        }
    }

    /** 二级页是否打开中 */
    public boolean isOverlayOpen() {
        return overlayContainer != null
                && overlayContainer.getVisibility() == View.VISIBLE;
    }

    /** ★打开消息中心 */
    public void showMessages() {
        openOverlay(new MessagesFragment(), "messages");
    }

    /** ★打开快捷命令页（A1） */
    public void showCommands() {
        openOverlay(new CommandsFragment(), "commands");
    }

    /** ★跳转到设置页（未配置服务器时引导用） */
    public void openSettings() {
        try {
            showTab(4);   // 4 = 设置
        } catch (Throwable t) {
            DebugLog.e("openSettings failed", t);
        }
    }

    /** ★打开实时日志（WebView 内嵌） */
    public void showLogs() {
        openOverlay(new LogsFragment(), "logs");
    }



    /** 供设置页调用：底栏顺序变了，重建 */
    public void rebuildNav() {
        try {
            buildNavMenu();
            if (selectedTab >= 0 && selectedTab < TAB_IDS.length) {
                nav.setSelectedItemId(TAB_IDS[selectedTab]);
            }
            DebugLog.i("nav rebuilt");
        } catch (Throwable t) {
            DebugLog.e("rebuildNav failed", t);
        }
    }

    private int tabIndexById(int id) {
        for (int i = 0; i < TAB_IDS.length; i++) {
            if (TAB_IDS[i] == id) return i;
        }
        return -1;
    }
    /** ★哪些栏目需要服务器地址 */
    private boolean requiresServer(int index) {
        // 0=首页 1=终端 2=文件 3=DSH 4=设置
        // 设置页始终可用（正是配置入口）
        return index != 4;
    }

    /** ★未配置服务器时的提示对话框 */
    private void showNotConfiguredDialog() {
        try {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("尚未配置服务器")
                    .setMessage("请在设置中提供服务器地址\n\n"
                            + "需要填写：\n"
                            + "· 服务器地址（如 192.168.1.10 或域名）\n"
                            + "· 状态服务端口（默认 8080）\n"
                            + "· DSH 地址、终端地址、文件服务地址")
                    .setPositiveButton("去设置", (d, w) -> showTab(4))
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            DebugLog.e("showNotConfiguredDialog failed", t);
        }
    }




    private void showTab(int index) {
        // ★未配置服务器地址时，需要联网的页面直接提示，不发请求
        if (requiresServer(index) && !settings.isConfigured()) {
            showNotConfiguredDialog();
            return;
        }

        FragmentManager fm = getSupportFragmentManager();
        Fragment target;
        String tag;

        switch (index) {
            case 1:
                if (terminalFragment == null) terminalFragment = new TerminalFragment();
                target = terminalFragment; tag = "terminal";
                break;
            case 2:
                if (filesFragment == null) filesFragment = new FilesFragment();
                target = filesFragment; tag = "files";
                break;
            case 3:
                if (dshFragment == null) dshFragment = new DshFragment();
                target = dshFragment; tag = "dsh";
                break;
            case 4:
                if (settingsFragment == null) settingsFragment = new SettingsFragment();
                target = settingsFragment; tag = "settings";
                break;
            default:
                if (homeFragment == null) homeFragment = new HomeFragment();
                target = homeFragment; tag = "home";
                break;
        }

        if (target == current) return;

        // ★修复：先隐藏所有已添加的 fragment，再显示目标
        FragmentTransaction tx = fm.beginTransaction();
        java.util.List<Fragment> all = fm.getFragments();
        for (Fragment f : all) {
            if (f != null && f.isAdded()) tx.hide(f);
        }

        Fragment existing = fm.findFragmentByTag(tag);
        if (existing != null) {
            tx.show(existing);
            target = existing;
        } else {
            tx.add(container.getId(), target, tag);
        }
        // ★用 commitNow 而不是 commit：
        //   commit 是异步的，在 onCreate 阶段会与 Fragment 状态恢复冲突
        //   commitNow 同步执行，保证 UI 立即就绪
        try {
            tx.commitNow();
        } catch (Throwable t) {
            DebugLog.e("commitNow failed, fallback to commit", t);
            tx.commitAllowingStateLoss();
        }
        current = target;
        selectedTab = index;
        DebugLog.d("tab -> " + tag + " (index=" + index + ")");
    }

    /**
     * 处理系统栏 inset
     *
     * ★策略
     *   默认 decorFitsSystemWindows=true 时，系统已经把窗口内容限制在
     *   状态栏/导航栏之内（activity 的 content area 不包含系统栏）。
     *   但如果主题用了 NoActionBar + 全屏边缘，就可能出现内容顶到状态栏。
     *
     *   这里显式设为 false（我们自己控制），然后用 inset 加 padding：
     *     · 顶部 = 状态栏高度
     *     · 底部 = 导航栏高度
     *   → 标题永远不会被状态栏遮挡，底栏也不会被手势条盖住。
     */
    private void applyWindowInsets(View root) {
        // 让我们自己处理系统栏区域
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                androidx.core.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                top = bars.top; bottom = bars.bottom;
                left = bars.left; right = bars.right;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
            }
            v.setPadding(left, top, right, bottom);
            DebugLog.d("insets applied: t=" + top + " b=" + bottom);
            return insets;
        });

        // 状态栏图标颜色跟随主题（浅色主题用深色图标）
        try {
            androidx.core.view.WindowInsetsControllerCompat c =
                    androidx.core.view.WindowCompat.getInsetsController(
                            getWindow(), getWindow().getDecorView());
            boolean dark = (getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            c.setAppearanceLightStatusBars(!dark);
            c.setAppearanceLightNavigationBars(!dark);
        } catch (Throwable t) {
            DebugLog.w("insets controller failed: " + t);
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }


    private void startMonitorService() {
        Intent i = new Intent(this, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
        DebugLog.i("monitor service started");
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
                DebugLog.i("requesting POST_NOTIFICATIONS");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == 100) {
            boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            DebugLog.i("notification permission: " + (ok ? "granted" : "denied"));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // ★兜底：如果当前没有任何可见的 Fragment，强制显示选中的 tab
        //   注意：只在 onPostResume 处理完 pendingShowTab 后才生效，
        //   避免与重建流程打架
        if (pendingShowTab >= 0) return;   // 还有待显示的，交给 onPostResume
        if (isOverlayOpen()) return;       // ★二级页打开时不管底栏

        FragmentManager fm = getSupportFragmentManager();
        boolean anyVisible = false;
        for (Fragment f : fm.getFragments()) {
            // ★排除 overlay 里的 Fragment
            if (f != null && f.isAdded() && !f.isHidden() && f.isVisible()
                    && f.getView() != null
                    && f.getView().getParent() == container) {
                anyVisible = true;
                break;
            }
        }
        if (!anyVisible) {
            DebugLog.w("onResume: no visible fragment, forcing tab " + selectedTab);
            current = null;
            showTab(selectedTab);
        }
    }

    @Override
    public void onBackPressed() {
        // ★二级页打开时，返回键先关二级页
        if (isOverlayOpen()) {
            closeOverlay();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        // ★此时 FragmentManager 已完成状态恢复，可以安全显示 tab
        if (pendingShowTab >= 0) {
            int t = pendingShowTab;
            pendingShowTab = -1;
            DebugLog.i("onPostResume: showing pending tab " + t);
            current = null;          // 强制真正执行
            showTab(t);
        }
    }


    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(KEY_TAB, selectedTab);
    }

    /** 供设置页调用：主题变了要重建 Activity */
    public void onSettingsChanged() {
        recreate();
    }
}
