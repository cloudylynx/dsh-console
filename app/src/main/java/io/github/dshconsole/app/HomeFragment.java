package io.github.dshconsole.app;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;

/**
 * 首页：编译机实时状态
 *
 * 布局：
 *   标题「中控台」
 *   ├ 主机信息（在线/延迟/运行时间）
 *   ├ 告警条
 *   ├ 四张独立折线图：CPU / 内存 / 磁盘 / 网速（不切换，全部同时显示）
 *   └ 当前任务
 */
public class HomeFragment extends Fragment {

    private AppSettings settings;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private Typeface font;   // 内置字体

    // 标题与状态
    private TextView tvTitle, tvSubtitle, tvLatency, tvUptime;
    private android.widget.FrameLayout btnMsgCircle;   // 圆形消息按钮（带角标）
    private LinearLayout alertContainer;

    // 四张图
    private LineChartView chartCpu, chartMem, chartDisk, chartNet;
    private TextView tvCpuVal, tvMemVal, tvDiskVal, tvNetVal;
    private TextView tvMemSub, tvDiskSub;      // "已用/可用"副标题
    private TextView tvTempVal, tvSwapVal;

    // 任务
    private LinearLayout taskContainer;
    private TextView tvEmpty;

    // 守护进程
    private LinearLayout daemonContainer;
    private TextView tvDaemonSummary;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup c,
                             @Nullable Bundle b) {
        settings = new AppSettings(requireContext());
        loadFont();
        return buildView();
    }

    private void loadFont() {
        try {
            font = ResourcesCompat.getFont(requireContext(), R.font.dsh_console_bold);
            DebugLog.i("font loaded: " + (font != null ? "OK" : "null"));
        } catch (Throwable t) {
            DebugLog.e("font load failed", t);
            font = null;
        }
    }

    private View buildView() {
        ScrollView sv = new ScrollView(requireContext());
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, dp(14), p, dp(28));
        sv.addView(root);

        // ==================== 标题 ====================
        // ★标题行：标题 + 右侧圆形按钮（消息 / 快捷命令）
        LinearLayout titleRow = new LinearLayout(requireContext());
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        tvTitle = new TextView(requireContext());
        tvTitle.setText("中控台");
        tvTitle.setTextSize(26);
        tvTitle.setTypeface(font != null ? font : Typeface.DEFAULT_BOLD);
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(tvTitle);

        // ★圆形图标按钮：消息（带未读角标）
        btnMsgCircle = roundIconButton(R.drawable.ic_message, v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).showMessages();
            }
        });
        titleRow.addView(btnMsgCircle);

        root.addView(titleRow);

        // 副标题行：主机名 · 在线状态 · 延迟
        LinearLayout subRow = new LinearLayout(requireContext());
        subRow.setOrientation(LinearLayout.HORIZONTAL);
        subRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        srp.topMargin = dp(4);
        root.addView(subRow, srp);

        tvSubtitle = new TextView(requireContext());
        tvSubtitle.setText("正在连接…");
        tvSubtitle.setTextSize(12);
        tvSubtitle.setTextColor(0xFF888888);
        tvSubtitle.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        subRow.addView(tvSubtitle);

        tvLatency = new TextView(requireContext());
        tvLatency.setTextSize(11);
        tvLatency.setTextColor(0xFF999999);
        tvLatency.setText("—");
        subRow.addView(tvLatency);

        // ==================== 告警条 ====================
        alertContainer = new LinearLayout(requireContext());
        alertContainer.setOrientation(LinearLayout.VERTICAL);
        alertContainer.setVisibility(View.GONE);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(12);
        root.addView(alertContainer, alp);

        // ==================== 任务 ====================
        TextView taskTitle = new TextView(requireContext());
        taskTitle.setText("当前任务");
        taskTitle.setTextSize(15);
        taskTitle.setTypeface(font != null ? font : Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams ttp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ttp.topMargin = dp(22);
        root.addView(taskTitle, ttp);

        taskContainer = new LinearLayout(requireContext());
        taskContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(taskContainer);

        tvEmpty = new TextView(requireContext());
        tvEmpty.setText("暂无运行中的任务");
        tvEmpty.setTextSize(13);
        tvEmpty.setTextColor(0xFF999999);
        tvEmpty.setPadding(0, dp(14), 0, dp(14));
        root.addView(tvEmpty);

        // ==================== 四张折线图 ====================
        // --- CPU ---
        chartCpu = new LineChartView(requireContext());
        tvCpuVal = new TextView(requireContext());
        root.addView(buildChartCard("CPU", tvCpuVal, new TextView(requireContext()), chartCpu, 0xFF0A84FF));
        chartCpu.setFixedMax(100);
        chartCpu.setUnit("%");
        chartCpu.setCapacity(settings.getChartPoints());

        // --- 内存（带 已用/可用）---
        chartMem = new LineChartView(requireContext());
        tvMemVal = new TextView(requireContext());
        tvMemSub = new TextView(requireContext());
        root.addView(buildChartCard("内存", tvMemVal, tvMemSub, chartMem, 0xFF34C759));
        chartMem.setFixedMax(100);
        chartMem.setUnit("%");
        chartMem.setCapacity(settings.getChartPoints());

        // --- 磁盘（改成 IO：蓝读红写）---
        chartDisk = new LineChartView(requireContext());
        tvDiskVal = new TextView(requireContext());
        tvDiskSub = new TextView(requireContext());
        root.addView(buildChartCard("磁盘 IO", tvDiskVal, tvDiskSub, chartDisk, 0xFF0A84FF));
        chartDisk.setDual(true, 0xFFFF3B30);   // 主=蓝(读) 副=红(写)
        chartDisk.setAutoScale(true);
        chartDisk.setUnit("");
        chartDisk.setCapacity(settings.getChartPoints());

        // --- 网速 ---
        chartNet = new LineChartView(requireContext());
        tvNetVal = new TextView(requireContext());
        root.addView(buildChartCard("网速 (KB/s)", tvNetVal, new TextView(requireContext()), chartNet, 0xFFAF52DE));
        chartNet.setAutoScale(true);
        chartNet.setUnit("");
        chartNet.setCapacity(settings.getChartPoints());

        // ==================== 小指标行 ====================
        LinearLayout miniRow = new LinearLayout(requireContext());
        miniRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams mrp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mrp.topMargin = dp(4);
        root.addView(miniRow, mrp);

        tvTempVal = new TextView(requireContext());
        tvSwapVal = new TextView(requireContext());
        miniRow.addView(buildMiniCard("温度", tvTempVal), weightParams());
        miniRow.addView(buildMiniCard("SWAP 空闲", tvSwapVal), weightParams());



        // ==================== 守护进程（大卡片包住 + 诊断按钮）====================
        LinearLayout daemonBox = new LinearLayout(requireContext());
        daemonBox.setOrientation(LinearLayout.VERTICAL);
        daemonBox.setPadding(dp(14), dp(12), dp(14), dp(12));

        GradientDrawable dboxBg = new GradientDrawable();
        dboxBg.setCornerRadius(dp(16));
        try {
            dboxBg.setColor(com.google.android.material.color.MaterialColors.getColor(
                    daemonBox, com.google.android.material.R.attr.colorSurfaceVariant));
        } catch (Throwable t) {
            dboxBg.setColor(0x0A000000);
        }
        daemonBox.setBackground(dboxBg);

        LinearLayout.LayoutParams dboxLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dboxLp.topMargin = dp(22);
        root.addView(daemonBox, dboxLp);

        // 标题行
        LinearLayout daemonHead = new LinearLayout(requireContext());
        daemonHead.setOrientation(LinearLayout.HORIZONTAL);
        daemonHead.setGravity(Gravity.CENTER_VERTICAL);

        TextView daemonTitle = new TextView(requireContext());
        daemonTitle.setText("守护进程");
        daemonTitle.setTextSize(15);
        daemonTitle.setTypeface(font != null ? font : Typeface.DEFAULT_BOLD);
        daemonTitle.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        daemonHead.addView(daemonTitle);

        tvDaemonSummary = new TextView(requireContext());
        tvDaemonSummary.setTextSize(12);
        tvDaemonSummary.setTextColor(0xFF999999);
        tvDaemonSummary.setText("—");
        daemonHead.addView(tvDaemonSummary);

        daemonBox.addView(daemonHead);

        // 守护进程列表
        daemonContainer = new LinearLayout(requireContext());
        daemonContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams dcp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dcp.topMargin = dp(10);
        daemonBox.addView(daemonContainer, dcp);

        // ★快捷命令长条按钮（守护卡片内，诊断上方）
        com.google.android.material.button.MaterialButton btnCmd =
                new com.google.android.material.button.MaterialButton(requireContext());
        btnCmd.setText("⚡ 快捷命令");
        btnCmd.setTextSize(12);
        btnCmd.setAllCaps(false);
        btnCmd.setMinWidth(0);
        btnCmd.setMinimumWidth(0);
        btnCmd.setInsetTop(0);
        btnCmd.setInsetBottom(0);
        btnCmd.setCornerRadius(dp(10));
        try {
            int bgCm = com.google.android.material.color.MaterialColors.getColor(
                    btnCmd, com.google.android.material.R.attr.colorPrimaryContainer);
            int fgCm = com.google.android.material.color.MaterialColors.getColor(
                    btnCmd, com.google.android.material.R.attr.colorOnPrimaryContainer);
            btnCmd.setBackgroundTintList(android.content.res.ColorStateList.valueOf(bgCm));
            btnCmd.setTextColor(fgCm);
        } catch (Throwable ignored) {}
        LinearLayout.LayoutParams clp3 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
        clp3.topMargin = dp(10);
        daemonBox.addView(btnCmd, clp3);
        btnCmd.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).showCommands();
            }
        });

        // ★一键诊断按钮（A3）
        com.google.android.material.button.MaterialButton btnDiagnose =
                new com.google.android.material.button.MaterialButton(requireContext());
        btnDiagnose.setText("一键诊断");
        btnDiagnose.setTextSize(12);
        btnDiagnose.setAllCaps(false);
        btnDiagnose.setMinWidth(0);
        btnDiagnose.setMinimumWidth(0);
        btnDiagnose.setInsetTop(0);
        btnDiagnose.setInsetBottom(0);
        btnDiagnose.setCornerRadius(dp(10));
        try {
            int bg2 = com.google.android.material.color.MaterialColors.getColor(
                    btnDiagnose, com.google.android.material.R.attr.colorSecondaryContainer);
            int fg2 = com.google.android.material.color.MaterialColors.getColor(
                    btnDiagnose, com.google.android.material.R.attr.colorOnSecondaryContainer);
            btnDiagnose.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(bg2));
            btnDiagnose.setTextColor(fg2);
        } catch (Throwable ignored) {}
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
        dlp.topMargin = dp(10);
        daemonBox.addView(btnDiagnose, dlp);
        btnDiagnose.setOnClickListener(v -> showDiagnose());

        // ★实时日志按钮（MD3 filled tonal）
        com.google.android.material.button.MaterialButton btnLogs =
                new com.google.android.material.button.MaterialButton(requireContext());
        btnLogs.setText("📜 实时日志");
        btnLogs.setTextSize(12);
        btnLogs.setAllCaps(false);
        btnLogs.setMinWidth(0);
        btnLogs.setMinimumWidth(0);
        btnLogs.setInsetTop(0);
        btnLogs.setInsetBottom(0);
        btnLogs.setCornerRadius(dp(10));
        try {
            int bgL = com.google.android.material.color.MaterialColors.getColor(
                    btnLogs, com.google.android.material.R.attr.colorTertiaryContainer);
            int fgL = com.google.android.material.color.MaterialColors.getColor(
                    btnLogs, com.google.android.material.R.attr.colorOnTertiaryContainer);
            btnLogs.setBackgroundTintList(android.content.res.ColorStateList.valueOf(bgL));
            btnLogs.setTextColor(fgL);
            // 图标
            btnLogs.setIcon(androidx.core.content.ContextCompat.getDrawable(
                    requireContext(), R.drawable.ic_log));
            btnLogs.setIconGravity(
                    com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START);
            btnLogs.setIconSize(dp(15));
            btnLogs.setIconPadding(dp(6));
            btnLogs.setIconTint(android.content.res.ColorStateList.valueOf(fgL));
        } catch (Throwable ignored) {}
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
        llp.topMargin = dp(8);
        daemonBox.addView(btnLogs, llp);
        btnLogs.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).showLogs();
            }
        });

        // ==================== 底部 ====================
        tvUptime = new TextView(requireContext());
        tvUptime.setTextSize(10);
        tvUptime.setTextColor(0xFFAAAAAA);
        tvUptime.setPadding(0, dp(20), 0, 0);
        root.addView(tvUptime);

        return sv;
    }

    /** 折线图卡片：标题 + 副标题(已用/可用) + 当前值 + 图 */
    private View buildChartCard(String label, TextView value, TextView subtitle,
                                LineChartView chart, int color) {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(8));

        // 顶部：左侧(标签+副标题) + 右侧数值
        LinearLayout head = new LinearLayout(requireContext());
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout headLeft = new LinearLayout(requireContext());
        headLeft.setOrientation(LinearLayout.VERTICAL);
        headLeft.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvLabel = new TextView(requireContext());
        tvLabel.setText(label);
        tvLabel.setTextSize(13);
        tvLabel.setTypeface(font != null ? font : Typeface.DEFAULT);
        tvLabel.setTextColor(0xFF555555);
        headLeft.addView(tvLabel);

        subtitle.setText("");
        subtitle.setTextSize(10);
        subtitle.setTextColor(0xFF999999);
        headLeft.addView(subtitle);

        head.addView(headLeft);

        value.setText("—");
        value.setTextSize(20);
        value.setTextColor(color);
        value.setTypeface(font != null ? font : Typeface.DEFAULT_BOLD);
        head.addView(value);

        card.addView(head);

        chart.setColor(color);
        chart.setShowLabels(false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        clp.topMargin = dp(6);
        card.addView(chart, clp);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0x0A000000);
        card.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);
        return card;
    }

    private View buildMiniCard(String label, TextView value) {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));

        TextView tvLabel = new TextView(requireContext());
        tvLabel.setText(label);
        tvLabel.setTextSize(11);
        tvLabel.setTextColor(0xFF888888);
        card.addView(tvLabel);

        value.setText("—");
        value.setTextSize(17);
        value.setTypeface(font != null ? font : Typeface.DEFAULT_BOLD);
        value.setTextColor(0xFF333333);
        card.addView(value);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0x0A000000);
        card.setBackground(bg);
        return card;
    }

    private LinearLayout.LayoutParams weightParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    // ==================== 数据刷新 ====================

    @Override
    public void onResume() {
        super.onResume();
        running = true;
        scheduleFetch();
    }

    @Override
    public void onPause() {
        super.onPause();
        running = false;
        ui.removeCallbacksAndMessages(null);
    }

    private void scheduleFetch() {
        ui.postDelayed(fetchRunnable, 100);
    }

    private final Runnable fetchRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running || !isAdded()) return;
            ApiClient.fetch(settings.apiUrl(), new ApiClient.Callback() {
                @Override
                public void onSuccess(Snapshot snap, long latencyMs) {
                    if (!isAdded() || !running) return;
                    try { render(snap, latencyMs); }
                    catch (Throwable t) { DebugLog.e("render failed", t); }
                    ui.postDelayed(fetchRunnable,
                            Math.max(1, settings.getInterval()) * 1000L);
                }

                @Override
                public void onError(String error) {
                    if (!isAdded() || !running) return;
                    tvSubtitle.setText("连接失败：" + error);
                    tvSubtitle.setTextColor(0xFFFF3B30);
                    ui.postDelayed(fetchRunnable,
                            Math.max(2, settings.getInterval()) * 1000L);
                }
            });
        }
    };

    private void render(Snapshot s, long latency) {
        Snapshot.Sys sys = s.system;

        // 副标题
        tvSubtitle.setText((sys.host.isEmpty() ? "编译机" : sys.host)
                + (sys.tailscale ? " · 在线" : " · 离线"));
        tvSubtitle.setTextColor(sys.tailscale ? 0xFF34C759 : 0xFFFF3B30);
        tvLatency.setText(latency + " ms");

        // CPU
        tvCpuVal.setText(sys.cpu + "%");
        chartCpu.add(sys.cpu);

        // 内存（带 已用/可用）
        tvMemVal.setText(sys.mem + "%");
        tvMemSub.setText("已用 " + sys.memUsed + " / 可用 " + sys.memFree);
        chartMem.add(sys.mem);

        // 磁盘 IO（蓝读红写）
        float readKb = sys.diskReadSpeed / 1024f;
        float writeKb = sys.diskWriteSpeed / 1024f;
        tvDiskVal.setText(String.format("↓%.1f ↑%.1f KB/s", readKb, writeKb));
        tvDiskVal.setTextSize(15);
        tvDiskSub.setText("已用 " + sys.disk + "% / 可用 " + sys.diskFree + " · " + sys.diskIface);
        chartDisk.add2(readKb, writeKb);

        // 网速（KB/s）
        float netKb = (sys.netRxSpeed + sys.netTxSpeed) / 1024f;
        tvNetVal.setText(String.format("%.1f", netKb) + " KB/s");
        chartNet.add(netKb);

        // 小指标
        tvTempVal.setText(sys.cpuTemp > 0 ? sys.cpuTemp + "°C" : "—");
        tvSwapVal.setText(sys.swapFree.isEmpty() ? "—" : sys.swapFree);

        // 底部信息
        tvUptime.setText("运行 " + sys.uptime + " · " + sys.cpuCores + " 核 · "
                + sys.cpuModel.replaceAll("\\(R\\)|\\(TM\\)|CPU|@.*", "").trim());

        renderAlerts(s);
        renderDaemons(s);
        renderTasks(s);
    }
    /**
     * ★圆形图标按钮（Material 风格）
     */
    private android.widget.FrameLayout roundIconButton(int iconRes,
                                                       View.OnClickListener onClick) {
        int size = dp(40);

        android.widget.FrameLayout wrap = new android.widget.FrameLayout(requireContext());
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(size, size);
        wlp.leftMargin = dp(8);
        wrap.setLayoutParams(wlp);

        android.widget.ImageView iv = new android.widget.ImageView(requireContext());
        iv.setImageResource(iconRes);
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        iv.setPadding(dp(9), dp(9), dp(9), dp(9));

        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        try {
            circle.setColor(com.google.android.material.color.MaterialColors.getColor(
                    iv, com.google.android.material.R.attr.colorSurfaceVariant));
            int tint = com.google.android.material.color.MaterialColors.getColor(
                    iv, com.google.android.material.R.attr.colorOnSurfaceVariant);
            iv.setColorFilter(tint);
        } catch (Throwable t) {
            circle.setColor(0x11000000);
            iv.setColorFilter(0xFF333333);
        }
        iv.setBackground(circle);

        wrap.addView(iv, new android.widget.FrameLayout.LayoutParams(size, size));
        wrap.setOnClickListener(onClick);
        wrap.setClickable(true);

        return wrap;
    }


    /** ★更新消息按钮的角标 */
    public void updateMsgBadge(int count) {
        try {
            // 首页的消息按钮现在没有 badge，用文字表达
            DebugLog.d("home msg badge: " + count);
        } catch (Throwable t) {
            DebugLog.e("updateMsgBadge failed", t);
        }
    }
    /** ★打开实时日志（外部浏览器） */
    private void openLiveLog() {
        try {
            String url = "http://" + settings.getHost() + ":" + settings.getLogPort() + "/api/logs";
            DebugLog.i("opening live log: " + url);
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            DebugLog.e("openLiveLog failed", t);
            Toast.makeText(requireContext(),
                    "无法打开浏览器：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }



    /** ★一键诊断（A3） */
    private void showDiagnose() {
        DebugLog.i("diagnose requested");
        final androidx.appcompat.app.AlertDialog dlg =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle("诊断中…")
                        .setMessage("正在检查系统状态")
                        .setNegativeButton("取消", null)
                        .create();
        dlg.show();

        new Thread(() -> {
            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                        new java.net.URL("http://" + settings.getHost() + ":"
                                + settings.getPort() + "/diagnose").openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(40000);
                int code = conn.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);

                StringBuilder sb = new StringBuilder();
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    char[] buf = new char[8192];
                    int n;
                    while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
                }
                conn.disconnect();

                org.json.JSONObject root = new org.json.JSONObject(sb.toString());
                org.json.JSONArray sections = root.optJSONArray("sections");
                StringBuilder report = new StringBuilder();
                int problems = 0;

                for (int i = 0; i < sections.length(); i++) {
                    org.json.JSONObject sec = sections.getJSONObject(i);
                    report.append("【").append(sec.optString("title")).append("】\n");
                    org.json.JSONArray items = sec.optJSONArray("items");
                    if (items != null) {
                        for (int j = 0; j < items.length(); j++) {
                            org.json.JSONObject it = items.getJSONObject(j);
                            boolean ok = it.optBoolean("ok", false);
                            if (!ok) problems++;
                            report.append(ok ? "  OK  " : "  XX  ")
                                    .append(it.optString("label"))
                                    .append(": ")
                                    .append(it.optString("text"))
                                    .append("\n");
                        }
                    }
                    report.append("\n");
                }

                final int prob = problems;
                final String text = report.toString();
                DebugLog.i("diagnose done, problems=" + prob);

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        dlg.dismiss();
                        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                                requireContext())
                                .setTitle(prob == 0 ? "一切正常" : prob + " 项异常")
                                .setMessage(text)
                                .setPositiveButton("关闭", null)
                                .setNeutralButton("复制", (d, w) -> {
                                    android.content.ClipboardManager cm =
                                            (android.content.ClipboardManager) requireContext()
                                                    .getSystemService(
                                                            android.content.Context.CLIPBOARD_SERVICE);
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                            "diagnose", text));
                                    Toast.makeText(requireContext(), "已复制",
                                            Toast.LENGTH_SHORT).show();
                                })
                                .show();
                    });
                }
            } catch (Throwable t) {
                DebugLog.e("diagnose failed", t);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        dlg.dismiss();
                        Toast.makeText(requireContext(),
                                "诊断失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }
        }).start();
    }



    /** 守护进程网格 */
    private void renderDaemons(Snapshot s) {
        daemonContainer.removeAllViews();
        if (s.daemons.isEmpty()) {
            tvDaemonSummary.setText("—");
            return;
        }

        int total = s.daemons.size();
        int healthy = 0;
        for (Snapshot.Daemon d : s.daemons) if (d.healthy) healthy++;

        if (healthy == total) {
            tvDaemonSummary.setText("✅ " + healthy + "/" + total);
            tvDaemonSummary.setTextColor(0xFF34C759);
        } else {
            tvDaemonSummary.setText("⚠️ " + healthy + "/" + total);
            tvDaemonSummary.setTextColor(0xFFFF3B30);
        }

        LinearLayout row = null;
        int idx = 0;
        for (Snapshot.Daemon d : s.daemons) {
            if (idx % 2 == 0) {
                row = new LinearLayout(requireContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = dp(6);
                daemonContainer.addView(row, rlp);
            }

            LinearLayout cell = new LinearLayout(requireContext());
            cell.setOrientation(LinearLayout.HORIZONTAL);
            cell.setGravity(Gravity.CENTER_VERTICAL);
            cell.setPadding(dp(10), dp(9), dp(10), dp(9));

            View dot = new View(requireContext());
            int dotSize = dp(7);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dotSize, dotSize);
            dlp.rightMargin = dp(7);
            dot.setLayoutParams(dlp);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(d.healthy ? 0xFF34C759 : 0xFFFF3B30);
            dot.setBackground(dotBg);
            cell.addView(dot);

            TextView tvName = new TextView(requireContext());
            tvName.setText(d.label.isEmpty() ? d.name : d.label);
            tvName.setTextSize(11.5f);
            tvName.setTextColor(d.healthy ? 0xFF333333 : 0xFFB3261E);
            tvName.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            cell.addView(tvName);

            if (d.restarts > 0) {
                TextView tvR = new TextView(requireContext());
                tvR.setText("\u21bb" + d.restarts);
                tvR.setTextSize(9.5f);
                tvR.setTextColor(0xFFFF9F0A);
                cell.addView(tvR);
            }

            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(d.healthy ? 0x08000000 : 0x18FF3B30);
            cell.setBackground(bg);

            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            clp.setMargins(dp(3), 0, dp(3), 0);
            row.addView(cell, clp);

            idx++;
        }
    }


    private void renderAlerts(Snapshot s) {
        alertContainer.removeAllViews();
        if (s.alerts.isEmpty()) {
            alertContainer.setVisibility(View.GONE);
            return;
        }
        alertContainer.setVisibility(View.VISIBLE);

        for (Snapshot.Alert a : s.alerts) {
            boolean crit = "critical".equals(a.level);
            TextView tv = new TextView(requireContext());
            tv.setText((crit ? "🔴 " : "🟠 ") + a.title + "：" + a.detail);
            tv.setTextSize(12);
            tv.setTextColor(crit ? 0xFFB3261E : 0xFF8A5300);
            tv.setPadding(dp(12), dp(8), dp(12), dp(8));

            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(crit ? 0x1AFF3B30 : 0x1AFF9F0A);
            tv.setBackground(bg);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            alertContainer.addView(tv, lp);
        }
    }

    private void renderTasks(Snapshot s) {
        taskContainer.removeAllViews();
        if (s.tasks.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            return;
        }
        tvEmpty.setVisibility(View.GONE);

        for (Snapshot.Task t : s.tasks) {
            LinearLayout card = new LinearLayout(requireContext());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));

            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView tvName = new TextView(requireContext());
            tvName.setText(stateIcon(t.state) + " " + t.name);
            tvName.setTextSize(13);
            tvName.setTypeface(font != null ? font : Typeface.DEFAULT_BOLD);
            tvName.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tvName);

            TextView tvPct = new TextView(requireContext());
            tvPct.setText(t.isActive()
                    ? Math.round(t.progress * 100) + "%" : stateText(t.state));
            tvPct.setTextSize(12);
            tvPct.setTextColor(0xFF666666);
            row.addView(tvPct);
            card.addView(row);

            if (t.isActive() && t.progress > 0) {
                ProgressBar pb = new ProgressBar(requireContext(), null,
                        android.R.attr.progressBarStyleHorizontal);
                pb.setMax(100);
                pb.setProgress(Math.round(t.progress * 100));
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(4));
                plp.topMargin = dp(8);
                card.addView(pb, plp);
            }

            if (!t.detail.isEmpty()) {
                TextView tvDetail = new TextView(requireContext());
                tvDetail.setText(t.detail + (t.eta.isEmpty() ? "" : " · 剩余 " + t.eta));
                tvDetail.setTextSize(11);
                tvDetail.setTextColor(0xFF888888);
                tvDetail.setPadding(0, dp(5), 0, 0);
                card.addView(tvDetail);
            }

            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14));
            bg.setColor(0x0A000000);
            card.setBackground(bg);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            taskContainer.addView(card, lp);
        }
    }

    private String stateIcon(String s) {
        switch (s) {
            case "running": return "⚙️";
            case "queued": return "⏳";
            case "done": return "✅";
            default: return "💤";
        }
    }

    private String stateText(String s) {
        switch (s) {
            case "running": return "运行中";
            case "queued": return "排队";
            case "done": return "完成";
            default: return "空闲";
        }
    }

    private LinearLayout.LayoutParams weightLp(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, dp(44), 1f);
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        return lp;
    }


    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
