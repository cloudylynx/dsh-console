package io.github.dshconsole.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * 设置页
 *
 * 分区：
 *   ① 服务器
 *   ② DSH 页面
 *   ③ 外观（主题/颜色/刷新）
 *   ④ 通知与后台监控
 *   ⑤ 调试（最底部，哥哥特别要求）
 */
public class SettingsFragment extends Fragment {

    private AppSettings s;
    private LinearLayout root;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup c,
                             @Nullable Bundle b) {
        s = new AppSettings(requireContext());

        ScrollView sv = new ScrollView(requireContext());
        root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, dp(12), p, dp(32));
        sv.addView(root);

        buildServerSection();
        buildDshSection();
        buildAppearanceSection();
        buildTabOrderSection();
        buildTerminalSection();
        buildNotifySection();
        buildDebugSection();   // ★最底部

        return sv;
    }

    // ==================== ① 服务器 ====================

    private void buildServerSection() {
        section("① 服务器");

        EditText etHost = input("服务器地址", s.getHost(), InputType.TYPE_CLASS_TEXT);
        etHost.setOnFocusChangeListener((v, has) -> {
            if (!has) { s.setHost(etHost.getText().toString().trim()); }
        });

        EditText etPort = input("端口", String.valueOf(s.getPort()), InputType.TYPE_CLASS_NUMBER);
        etPort.setOnFocusChangeListener((v, has) -> {
            if (!has) {
                try { s.setPort(Integer.parseInt(etPort.getText().toString().trim())); }
                catch (Exception ignored) {}
            }
        });

        row("测试连接", "验证服务器是否可达", v -> {
            final String url = "http://" + etHost.getText().toString().trim()
                    + ":" + etPort.getText().toString().trim() + "/progress";
            DebugLog.i("testing: " + url);
            new Thread(() -> {
                final String result = ApiClient.testConnection(url);
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    DebugLog.i("test result: " + result.replace("\n", " | "));
                    dialog("测试结果", result);
                });
            }).start();
        });
    }

    // ==================== ② DSH ====================

    private void buildDshSection() {
        section("② DSH 页面");

        EditText etUrl = input("网页地址", s.getDshUrl(), InputType.TYPE_TEXT_VARIATION_URI);
        etUrl.setOnFocusChangeListener((v, has) -> {
            if (!has) { s.setDshUrl(etUrl.getText().toString().trim()); }
        });

        EditText etToken = input("访问 Token（可选）", s.getDshToken(), InputType.TYPE_CLASS_TEXT);
        etToken.setOnFocusChangeListener((v, has) -> {
            if (!has) { s.setDshToken(etToken.getText().toString().trim()); }
        });

        row("保存并重新加载", "让 DSH 页面使用新配置", v -> {
            Toast.makeText(requireContext(), "已保存", Toast.LENGTH_SHORT).show();
            // 重启 Activity 让 DSH Fragment 重新加载
            safeRecreate();
        });
    }

    // ==================== ③ 外观 ====================

    private void buildAppearanceSection() {
        section("③ 外观");

        // 主题模式
        TextView tvTheme = new TextView(requireContext());
        tvTheme.setText("主题模式：" + themeName(s.getThemeMode()));
        tvTheme.setTextSize(14);
        tvTheme.setPadding(0, dp(8), 0, dp(8));
        tvTheme.setOnClickListener(v -> {
            String[] items = {"跟随系统", "浅色", "深色"};
            new AlertDialog.Builder(requireContext())
                    .setTitle("主题模式")
                    .setSingleChoiceItems(items, s.getThemeMode(), (d, which) -> {
                        s.setThemeMode(which);
                        MainActivity.applyTheme(which);
                        tvTheme.setText("主题模式：" + themeName(which));
                        d.dismiss();
                        safeRecreate();
                    })
                    .show();
        });
        root.addView(tvTheme);

        // ★强调色（含莫奈取色）
        int curMode = s.getAccentMode();
        row("强调色", AppSettings.ACCENT_NAMES[Math.min(curMode,
                        AppSettings.ACCENT_NAMES.length - 1)],
                v -> {
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                            requireContext())
                            .setTitle("强调色")
                            .setSingleChoiceItems(AppSettings.ACCENT_NAMES, curMode,
                                    (d, which) -> {
                                        s.setAccentMode(which);
                                        d.dismiss();
                                        String name = AppSettings.ACCENT_NAMES[which];
                                        DebugLog.i("accent mode -> " + name);
                                        if (which == AppSettings.ACCENT_MONET) {
                                            if (android.os.Build.VERSION.SDK_INT < 31) {
                                                Toast.makeText(requireContext(),
                                                        "莫奈取色需要 Android 12+\n当前系统不支持，将使用默认色",
                                                        Toast.LENGTH_LONG).show();
                                            } else {
                                                Toast.makeText(requireContext(),
                                                        "已启用莫奈取色\n重启 App 生效",
                                                        Toast.LENGTH_SHORT).show();
                                            }
                                        } else {
                                            Toast.makeText(requireContext(),
                                                    "已选择 " + name, Toast.LENGTH_SHORT).show();
                                        }
                                        safeRecreate();
                                    })
                            .show();
                });

        // 刷新间隔
        slider("刷新间隔", 1, 30, s.getInterval(), "秒",
                val -> s.setInterval(val));

        // 图表点数
        slider("图表显示点数", 10, 120, s.getChartPoints(), "点",
                val -> s.setChartPoints(val));
    }

    // ==================== 底栏排序 ====================

    private void buildTabOrderSection() {
        section("\u2463 底栏顺序");

        final int[] order = s.getTabOrder();

        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(4), dp(16), dp(12));

        for (int i = 0; i < order.length; i++) {
            final int pos = i;
            int tabIdx = order[i];

            LinearLayout rowBox = new LinearLayout(requireContext());
            rowBox.setOrientation(LinearLayout.HORIZONTAL);
            rowBox.setGravity(Gravity.CENTER_VERTICAL);
            rowBox.setPadding(0, dp(8), 0, dp(8));

            TextView tvName = new TextView(requireContext());
            tvName.setText((i + 1) + ". " + AppSettings.TAB_NAMES[tabIdx]);
            tvName.setTextSize(14);
            tvName.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                    tvName, com.google.android.material.R.attr.colorOnSurface));
            tvName.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            rowBox.addView(tvName);

            // 上移
            if (i > 0) {
                com.google.android.material.button.MaterialButton up =
                        new com.google.android.material.button.MaterialButton(
                                requireContext(), null,
                                com.google.android.material.R.attr.materialButtonOutlinedStyle);
                up.setText("\u2191");
                up.setTextSize(14);
                up.setMinWidth(0);
                up.setMinimumWidth(0);
                up.setPadding(dp(10), 0, dp(10), 0);
                up.setOnClickListener(v -> {
                    int tmp = order[pos];
                    order[pos] = order[pos - 1];
                    order[pos - 1] = tmp;
                    s.setTabOrder(order);
                    DebugLog.i("tab order -> " + java.util.Arrays.toString(order));
                    rebuildSection();
                });
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(36));
                blp.leftMargin = dp(4);
                rowBox.addView(up, blp);
            }

            // 下移
            if (i < order.length - 1) {
                com.google.android.material.button.MaterialButton down =
                        new com.google.android.material.button.MaterialButton(
                                requireContext(), null,
                                com.google.android.material.R.attr.materialButtonOutlinedStyle);
                down.setText("\u2193");
                down.setTextSize(14);
                down.setMinWidth(0);
                down.setMinimumWidth(0);
                down.setPadding(dp(10), 0, dp(10), 0);
                down.setOnClickListener(v -> {
                    int tmp = order[pos];
                    order[pos] = order[pos + 1];
                    order[pos + 1] = tmp;
                    s.setTabOrder(order);
                    DebugLog.i("tab order -> " + java.util.Arrays.toString(order));
                    rebuildSection();
                });
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(36));
                blp.leftMargin = dp(4);
                rowBox.addView(down, blp);
            }

            box.addView(rowBox);

            if (i < order.length - 1) {
                View divider = new View(requireContext());
                divider.setBackgroundColor(0x11757575);
                box.addView(divider, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
            }
        }

        root.addView(box);

        row("恢复默认顺序", "首页 · 终端 · 文件 · DSH · 设置", v -> {
            s.setTabOrder(new int[]{0, 1, 2, 3, 4});
            Toast.makeText(requireContext(), "已恢复默认顺序", Toast.LENGTH_SHORT).show();
            rebuildSection();
        });
    }

    /** ★统一的安全重建（防抖 800ms，避免连续 recreate 打架） */
    private long lastRecreateAt = 0;

    /** ★通知终端：设置变了（只重新应用字号，不刷新页面） */
    private void notifyTerminalChanged() {
        try {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).onTerminalSettingsChanged();
            }
        } catch (Throwable t) {
            DebugLog.e("notifyTerminalChanged failed", t);
        }
    }

    /** ★通知终端：需要刷新页面 */
    private void notifyTerminalReload() {
        try {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).onTerminalReload();
            }
        } catch (Throwable t) {
            DebugLog.e("notifyTerminalReload failed", t);
        }
    }


    private void safeRecreate() {
        long now = System.currentTimeMillis();
        if (now - lastRecreateAt < 800) {
            DebugLog.d("safeRecreate debounced (" + (now - lastRecreateAt) + "ms)");
            return;
        }
        lastRecreateAt = now;
        if (getActivity() != null && !getActivity().isFinishing()) {
            DebugLog.i("safeRecreate");
            getActivity().recreate();
        }
    }

    /** ★重建设置页（防抖：500ms 内多次调用只执行一次） */
    private long lastRebuildAt = 0;

    private void rebuildSection() {
        long now = System.currentTimeMillis();
        if (now - lastRebuildAt < 500) {
            DebugLog.d("rebuildSection debounced");
            return;
        }
        lastRebuildAt = now;

        // 先更新底栏（不需要重建 Activity）
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).rebuildNav();
        }
        // 重建自己（让列表按新顺序渲染）
        safeRecreate();
    }

    // ==================== 终端与文件 ====================

    private void buildTerminalSection() {
        section("\u2464 终端与文件");

        EditText etFilePort = input("文件端口",
                String.valueOf(s.getFilePort()), InputType.TYPE_CLASS_NUMBER);
        etFilePort.setOnFocusChangeListener((v, has) -> {
            if (!has) {
                try { s.setFilePort(Integer.parseInt(etFilePort.getText().toString().trim())); }
                catch (Exception ignored) {}
            }
        });

        EditText etLogPort = input("日志端口",
                String.valueOf(s.getLogPort()), InputType.TYPE_CLASS_NUMBER);
        etLogPort.setOnFocusChangeListener((v, has) -> {
            if (!has) {
                try { s.setLogPort(Integer.parseInt(etLogPort.getText().toString().trim())); }
                catch (Exception ignored) {}
            }
        });

        EditText etTermUrl = input("终端地址",
                s.getTerminalUrl(), InputType.TYPE_TEXT_VARIATION_URI);
        etTermUrl.setOnFocusChangeListener((v, has) -> {
            if (!has) s.setTerminalUrl(etTermUrl.getText().toString().trim());
        });

        EditText etTermUser = input("终端用户名",
                s.getTerminalUser(), InputType.TYPE_CLASS_TEXT);
        etTermUser.setOnFocusChangeListener((v, has) -> {
            if (!has) s.setTerminalUser(etTermUser.getText().toString().trim());
        });

        EditText etTermPass = input("终端密码",
                s.getTerminalPass(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etTermPass.setOnFocusChangeListener((v, has) -> {
            if (!has) s.setTerminalPass(etTermPass.getText().toString().trim());
        });

        // ★终端字号（立即生效，不需要重建 Activity）
        slider("终端字号", 8, 28, s.getTerminalFontSize(), " px",
                v -> {
                    s.setTerminalFontSize(v);
                    notifyTerminalChanged();
                    Toast.makeText(requireContext(),
                            "字号已应用：" + v + "px", Toast.LENGTH_SHORT).show();
                });

        // ★键盘高度
        slider("键盘高度", 3, 16, s.getKeypadHeight(), " dp",
                v -> {
                    s.setKeypadHeight(v);
                    notifyTerminalChanged();
                    Toast.makeText(requireContext(),
                            "键盘高度 " + v + "dp，切回终端页生效", Toast.LENGTH_SHORT).show();
                });

        // ★键盘配色
        int kpTheme = s.getKeypadTheme();
        String[] kpNames = {"跟随系统", "深色", "浅色"};
        row("键盘配色", kpNames[Math.min(kpTheme, 2)], v -> {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                    requireContext())
                    .setTitle("键盘配色")
                    .setSingleChoiceItems(kpNames, kpTheme, (d, which) -> {
                        s.setKeypadTheme(which);
                        d.dismiss();
                        Toast.makeText(requireContext(),
                                "已选 " + kpNames[which], Toast.LENGTH_SHORT).show();
                        safeRecreate();
                    })
                    .show();
        });

        // ★刷新终端按钮
        row("刷新终端", "重新加载终端页面（字号/配置改动后可用）", v -> {
            notifyTerminalReload();
            Toast.makeText(requireContext(), "终端已刷新", Toast.LENGTH_SHORT).show();
        });

        row("测试终端连接", "验证 ttyd 是否可达", v -> {
            final String url = etTermUrl.getText().toString().trim();
            new Thread(() -> {
                String result;
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                            new java.net.URL(url).openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(5000);
                    int code = c.getResponseCode();
                    result = code == 200 || code == 401
                            ? "✅ 终端在线 (HTTP " + code + ")\n" + url
                            : "⚠️ 异常响应 HTTP " + code;
                } catch (Throwable t) {
                    result = "❌ 无法连接\n" + t.getMessage();
                }
                final String r = result;
                if (getActivity() != null) getActivity().runOnUiThread(() -> dialog("终端测试", r));
            }).start();
        });
    }

    // ==================== ④ 通知与监控 ====================

    private void buildNotifySection() {
        section("\u2465 通知与后台监控");

        switchRow("后台监控", "锁屏/后台时继续检查状态并发通知",
                s.isMonitorEnabled(), v -> s.setMonitorEnabled(v));

        switchRow("消息推送", "我发消息给你时通知你",
                s.isNotifyMessage(), v -> s.setNotifyMessage(v));

        switchRow("告警推送", "内存/磁盘/温度异常时通知",
                s.isNotifyAlert(), v -> s.setNotifyAlert(v));

        slider("内存告警阈值", 50, 99, s.getMemThreshold(), "%",
                v -> s.setMemThreshold(v));

        slider("磁盘告警阈值", 50, 99, s.getDiskThreshold(), "%",
                v -> s.setDiskThreshold(v));

        row("发送测试通知", "验证通知是否正常工作", v -> {
            Notifier.test(requireContext());
            Toast.makeText(requireContext(), "已发送测试通知", Toast.LENGTH_SHORT).show();
            DebugLog.i("test notification sent");
        });
    }

    // ==================== ⑤ 调试（最底部）====================

    private void buildDebugSection() {
        section("\u2466 调试");

        final TextView tvLog = new TextView(requireContext());
        tvLog.setTextSize(10);
        tvLog.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvLog.setPadding(dp(10), dp(10), dp(10), dp(10));
        tvLog.setTextColor(0xFF333333);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(0xFFF5F5F5);
        bg.setStroke(dp(1), 0xFFDDDDDD);
        tvLog.setBackground(bg);

        // 日志内容（倒序：最新在上）
        tvLog.setText(DebugLog.dumpReversed().isEmpty()
                ? "(暂无日志)" : DebugLog.dumpReversed());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220));
        lp.topMargin = dp(8);
        root.addView(tvLog, lp);

        // 按钮行
        LinearLayout btnRow = new LinearLayout(requireContext());
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnRefresh = new Button(requireContext());
        btnRefresh.setText("刷新");
        btnRefresh.setOnClickListener(v -> {
            String log = DebugLog.dumpReversed();
            tvLog.setText(log.isEmpty() ? "(暂无日志)" : log);
            Toast.makeText(requireContext(),
                    "共 " + DebugLog.size() + " 条", Toast.LENGTH_SHORT).show();
        });
        btnRow.addView(btnRefresh, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button btnCopy = new Button(requireContext());
        btnCopy.setText("复制");
        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("dsh_console_log", DebugLog.dump()));
            Toast.makeText(requireContext(), "已复制全部日志", Toast.LENGTH_SHORT).show();
        });
        btnRow.addView(btnCopy, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button btnClear = new Button(requireContext());
        btnClear.setText("清空");
        btnClear.setOnClickListener(v -> {
            DebugLog.clear();
            tvLog.setText("(暂无日志)");
            Toast.makeText(requireContext(), "已清空", Toast.LENGTH_SHORT).show();
        });
        btnRow.addView(btnClear, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(btnRow);

        // 调试信息面板
        row("查看环境信息", "SDK 版本、设备、配置等", v -> {
            StringBuilder sb = new StringBuilder();
            sb.append("=== 设备 ===\n");
            sb.append("SDK: ").append(android.os.Build.VERSION.SDK_INT).append("\n");
            sb.append("Release: ").append(android.os.Build.VERSION.RELEASE).append("\n");
            sb.append("Model: ").append(android.os.Build.MODEL).append("\n");
            sb.append("Brand: ").append(android.os.Build.BRAND).append("\n\n");
            sb.append("=== 配置 ===\n");
            sb.append("API: ").append(s.apiUrl()).append("\n");
            sb.append("DSH: ").append(s.getDshUrl()).append("\n");
            sb.append("Token: ").append(s.getDshToken().isEmpty() ? "(未设置)"
                    : "(已设置 " + s.getDshToken().length() + " 字符)").append("\n");
            sb.append("主题: ").append(themeName(s.getThemeMode())).append("\n");
            sb.append("刷新: ").append(s.getInterval()).append("s\n");
            sb.append("监控: ").append(s.isMonitorEnabled() ? "开" : "关").append("\n");
            sb.append("日志条数: ").append(DebugLog.size()).append("\n\n");
            sb.append("=== 上次数据 ===\n");
            Snapshot last = ApiClient.getLastSnapshot();
            if (last == null) {
                sb.append("(无)\n");
            } else {
                sb.append("ts: ").append(last.ts).append("\n");
                sb.append("任务: ").append(last.tasks.size()).append(" 个\n");
                sb.append("CPU: ").append(last.system.cpu).append("%\n");
                sb.append("内存: ").append(last.system.mem).append("%\n");
                sb.append("磁盘: ").append(last.system.disk).append("%\n");
                sb.append("告警: ").append(last.alerts.size()).append("\n");
                sb.append("消息: ").append(last.messages.size()).append("\n");
            }
            dialog("环境信息", sb.toString());
        });

        row("查看崩溃日志", "上次闪退的堆栈信息（如有）", v -> {
            String crash = DshConsoleApp.readLastCrash(requireContext());
            if (crash.isEmpty()) {
                dialog("崩溃日志", "没有崩溃记录 ✅");
            } else {
                dialog("崩溃日志", crash);
            }
        });

        row("查看原始 JSON", "上次 /progress 的完整响应", v -> {
            Snapshot last = ApiClient.getLastSnapshot();
            String raw = (last == null || last.rawJson.isEmpty())
                    ? "(无数据)" : last.rawJson;
            dialog("原始 JSON", raw);
        });

        row("打开实时日志", "编译/任务/系统日志（首页守护卡片也有入口）", v -> {
            try {
                android.content.Intent i = new android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("http://" + s.getHost() + ":" + s.getLogPort() + "/api/logs"));
                startActivity(i);
            } catch (Throwable t) {
                Toast.makeText(requireContext(), "无法打开", Toast.LENGTH_SHORT).show();
            }
        });

        switchRow("记录详细日志", "关闭可减少开销",
                s.isDebugEnabled(), v -> s.setDebugEnabled(v));

        // 版本信息
        TextView tvVer = new TextView(requireContext());
        tvVer.setText("\nDSH 控制台 v1.0\n"
                + "编译于 2026-10-19\n"
                + "日志缓冲区: " + DebugLog.size() + "/500 条");
        tvVer.setTextSize(10);
        tvVer.setTextColor(0xFF999999);
        tvVer.setGravity(Gravity.CENTER);
        tvVer.setPadding(0, dp(16), 0, 0);
        root.addView(tvVer);
    }

    // ==================== UI 辅助 ====================

    // ==================== M3 UI 辅助 ====================

    /** M3 分区标题 */
    private void section(String title) {
        TextView tv = new TextView(requireContext());
        tv.setText(title);
        tv.setTextSize(13);
        tv.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                tv, com.google.android.material.R.attr.colorPrimary));
        tv.setPadding(0, dp(24), 0, dp(10));
        root.addView(tv);
    }

    /** M3 卡片容器（每个分区一个卡片） */
    private com.google.android.material.card.MaterialCardView card() {
        com.google.android.material.card.MaterialCardView c =
                new com.google.android.material.card.MaterialCardView(requireContext());
        c.setCardElevation(dp(0));
        c.setStrokeWidth(0);
        c.setRadius(dp(16));
        c.setCardBackgroundColor(com.google.android.material.color.MaterialColors.getColor(
                c, com.google.android.material.R.attr.colorSurfaceVariant));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        c.setLayoutParams(lp);
        return c;
    }

    /** M3 输入框（TextInputLayout + TextInputEditText） */
    private EditText input(String label, String value, int inputType) {
        com.google.android.material.textfield.TextInputLayout til =
                new com.google.android.material.textfield.TextInputLayout(requireContext());
        til.setHint(label);
        til.setBoxCornerRadii(dp(12), dp(12), dp(12), dp(12));

        com.google.android.material.textfield.TextInputEditText et =
                new com.google.android.material.textfield.TextInputEditText(requireContext());
        et.setText(value);
        et.setTextSize(14);
        et.setInputType(inputType);
        et.setSingleLine(true);
        til.addView(et);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        root.addView(til, lp);
        return et;
    }

    /** M3 可点击行 */
    private void row(String title, String desc, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setClickable(true);
        row.setFocusable(true);

        android.util.TypedValue tv = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);
        row.setOnClickListener(onClick);

        LinearLayout texts = new LinearLayout(requireContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView t1 = new TextView(requireContext());
        t1.setText(title);
        t1.setTextSize(15);
        t1.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                t1, com.google.android.material.R.attr.colorOnSurface));
        texts.addView(t1);

        if (desc != null && !desc.isEmpty()) {
            TextView t2 = new TextView(requireContext());
            t2.setText(desc);
            t2.setTextSize(12);
            t2.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                    t2, com.google.android.material.R.attr.colorOnSurfaceVariant));
            texts.addView(t2);
        }
        row.addView(texts);

        TextView arrow = new TextView(requireContext());
        arrow.setText("›");
        arrow.setTextSize(22);
        arrow.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                arrow, com.google.android.material.R.attr.colorOnSurfaceVariant));
        row.addView(arrow);

        root.addView(row);
    }

    /** M3 开关行 */
    private void switchRow(String title, String desc, boolean value,
                           java.util.function.Consumer<Boolean> onChange) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout texts = new LinearLayout(requireContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView t1 = new TextView(requireContext());
        t1.setText(title);
        t1.setTextSize(15);
        t1.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                t1, com.google.android.material.R.attr.colorOnSurface));
        texts.addView(t1);

        if (desc != null && !desc.isEmpty()) {
            TextView t2 = new TextView(requireContext());
            t2.setText(desc);
            t2.setTextSize(12);
            t2.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                    t2, com.google.android.material.R.attr.colorOnSurfaceVariant));
            texts.addView(t2);
        }
        row.addView(texts);

        com.google.android.material.materialswitch.MaterialSwitch sw =
                new com.google.android.material.materialswitch.MaterialSwitch(requireContext());
        sw.setChecked(value);
        sw.setOnCheckedChangeListener((v, checked) -> {
            onChange.accept(checked);
            DebugLog.i("setting " + title + " -> " + checked);
        });
        row.addView(sw);

        root.addView(row);
    }

    /** M3 滑条（Slider） */
    private void slider(String title, int min, int max, int value, String unit,
                        java.util.function.IntConsumer onChange) {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(10), dp(16), dp(4));

        TextView tv = new TextView(requireContext());
        tv.setText(title + " · " + value + unit);
        tv.setTextSize(13);
        tv.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                tv, com.google.android.material.R.attr.colorOnSurfaceVariant));
        box.addView(tv);

        com.google.android.material.slider.Slider sl =
                new com.google.android.material.slider.Slider(requireContext());
        sl.setValueFrom(min);
        sl.setValueTo(max);
        sl.setStepSize(1);
        sl.setValue(Math.max(min, Math.min(max, value)));
        sl.addOnChangeListener((s, v, fromUser) ->
                tv.setText(title + " · " + (int) v + unit));
        sl.addOnSliderTouchListener(
                new com.google.android.material.slider.Slider.OnSliderTouchListener() {
                    @Override
                    public void onStartTrackingTouch(@NonNull com.google.android.material.slider.Slider s) {}

                    @Override
                    public void onStopTrackingTouch(@NonNull com.google.android.material.slider.Slider s) {
                        int v = (int) s.getValue();
                        onChange.accept(v);
                        DebugLog.i("setting " + title + " -> " + v + unit);
                    }
                });
        box.addView(sl);

        root.addView(box);
    }

    /** M3 对话框 */
    private void dialog(String title, String msg) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) requireContext()
                            .getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText(title, msg));
                    Toast.makeText(requireContext(), "已复制", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private String themeName(int mode) {
        switch (mode) {
            case AppSettings.THEME_LIGHT: return "浅色";
            case AppSettings.THEME_DARK: return "深色";
            default: return "跟随系统";
        }
    }


    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
