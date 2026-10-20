package io.github.dshconsole.app;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 快捷命令面板（A1）
 *
 * 功能：
 *   · 显示预设命令按钮（网格）
 *   · 点击执行，显示实时结果
 *   · 危险命令需二次确认
 *
 * ★按哥哥要求：不含「编译」和「同步源码」
 */
public class CommandsFragment extends Fragment {

    private AppSettings settings;
    private Typeface font;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    private LinearLayout gridContainer;
    private TextView tvStatus;
    private ScrollView outputScroll;
    private TextView tvOutput;

    private final List<Cmd> commands = new ArrayList<>();

    public static class Cmd {
        public String id = "", name = "", desc = "";
        public boolean danger = false;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup c,
                             @Nullable Bundle b) {
        settings = new AppSettings(requireContext());
        try {
            font = ResourcesCompat.getFont(requireContext(), R.font.dsh_console_bold);
        } catch (Throwable ignored) {}
        return buildView();
    }

    private View buildView() {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);

        // ===== 头部 =====
        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(14), dp(16), dp(8));

        // ★标题行：返回按钮 + 标题
        LinearLayout titleRow = new LinearLayout(requireContext());
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView btnBack = new TextView(requireContext());
        btnBack.setText("\u2190");
        btnBack.setTextSize(22);
        btnBack.setPadding(0, dp(2), dp(14), dp(2));
        btnBack.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).closeOverlay();
            }
        });
        titleRow.addView(btnBack);

        TextView title = new TextView(requireContext());
        title.setText("快捷命令");
        title.setTextSize(22);
        title.setTypeface(font != null ? font : Typeface.DEFAULT_BOLD);
        titleRow.addView(title);

        header.addView(titleRow);

        tvStatus = new TextView(requireContext());
        tvStatus.setTextSize(11);
        tvStatus.setTextColor(0xFF888888);
        tvStatus.setText("加载中…");
        header.addView(tvStatus);

        root.addView(header);

        // ===== 命令按钮网格 =====
        ScrollView sv = new ScrollView(requireContext());
        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), 0, dp(12), dp(16));

        gridContainer = new LinearLayout(requireContext());
        gridContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(gridContainer);

        // ===== 输出区 =====
        TextView outTitle = new TextView(requireContext());
        outTitle.setText("输出");
        outTitle.setTextSize(13);
        outTitle.setTypeface(font != null ? font : Typeface.DEFAULT_BOLD);
        outTitle.setPadding(0, dp(18), 0, dp(6));
        content.addView(outTitle);

        tvOutput = new TextView(requireContext());
        tvOutput.setTextSize(10);
        tvOutput.setTypeface(Typeface.MONOSPACE);
        tvOutput.setTextColor(0xFF333333);
        tvOutput.setPadding(dp(10), dp(10), dp(10), dp(10));
        tvOutput.setText("(等待执行…)");

        GradientDrawable obg = new GradientDrawable();
        obg.setCornerRadius(dp(10));
        obg.setColor(0xFFF5F5F5);
        obg.setStroke(dp(1), 0xFFE0E0E0);
        tvOutput.setBackground(obg);

        outputScroll = new ScrollView(requireContext());
        outputScroll.addView(tvOutput);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220));
        content.addView(outputScroll, olp);

        sv.addView(content);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        loadCommands();
        return root;
    }

    // ==================== 加载命令 ====================

    private void loadCommands() {
        pool.execute(() -> {
            try {
                String json = httpGet(apiBase() + "/cmds");
                JSONObject o = new JSONObject(json);
                JSONArray arr = o.getJSONArray("commands");
                List<Cmd> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.getJSONObject(i);
                    Cmd cmd = new Cmd();
                    cmd.id = c.getString("id");
                    cmd.name = c.getString("name");
                    cmd.desc = c.optString("desc", "");
                    cmd.danger = c.optBoolean("danger", false);
                    list.add(cmd);
                }
                ui.post(() -> {
                    if (!isAdded()) return;
                    commands.clear();
                    commands.addAll(list);
                    renderGrid();
                    tvStatus.setText(list.size() + " 个命令 · 点击执行");
                });
            } catch (Throwable t) {
                DebugLog.e("loadCommands failed", t);
                ui.post(() -> {
                    if (isAdded()) tvStatus.setText("加载失败：" + t.getMessage());
                });
            }
        });
    }

    private void renderGrid() {
        gridContainer.removeAllViews();

        LinearLayout row = null;
        for (int i = 0; i < commands.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(requireContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = dp(8);
                gridContainer.addView(row, rlp);
            }

            final Cmd cmd = commands.get(i);
            LinearLayout cell = new LinearLayout(requireContext());
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setPadding(dp(14), dp(14), dp(14), dp(14));
            cell.setClickable(true);
            cell.setFocusable(true);

            android.util.TypedValue tv = new android.util.TypedValue();
            requireContext().getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackground, tv, true);
            cell.setForeground(androidx.core.content.ContextCompat.getDrawable(
                    requireContext(), tv.resourceId));

            TextView tvName = new TextView(requireContext());
            tvName.setText((cmd.danger ? "⚠ " : "") + cmd.name);
            tvName.setTextSize(14);
            tvName.setTypeface(font != null ? font : Typeface.DEFAULT_BOLD);
            tvName.setTextColor(cmd.danger ? 0xFFB3261E : 0xFF1A1A1A);
            cell.addView(tvName);

            TextView tvDesc = new TextView(requireContext());
            tvDesc.setText(cmd.desc);
            tvDesc.setTextSize(10);
            tvDesc.setTextColor(0xFF888888);
            tvDesc.setPadding(0, dp(3), 0, 0);
            cell.addView(tvDesc);

            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14));
            try {
                // M3 表面色
                bg.setColor(com.google.android.material.color.MaterialColors.getColor(
                        cell, com.google.android.material.R.attr.colorSurfaceVariant));
            } catch (Throwable t) {
                bg.setColor(0x0A000000);
            }
            cell.setBackground(bg);

            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            clp.setMargins(dp(4), 0, dp(4), 0);
            row.addView(cell, clp);

            cell.setOnClickListener(v -> {
                if (cmd.danger) confirmRun(cmd);
                else runCmd(cmd);
            });
        }
    }

    // ==================== 执行 ====================

    private void confirmRun(Cmd cmd) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("确认执行")
                .setMessage("命令：" + cmd.name + "\n\n" + cmd.desc
                        + "\n\n⚠️ 这个命令会改变系统状态")
                .setPositiveButton("执行", (d, w) -> runCmd(cmd))
                .setNegativeButton("取消", null)
                .show();
    }

    private void runCmd(Cmd cmd) {
        ui.post(() -> {
            tvStatus.setText("执行中：" + cmd.name + "…");
            tvOutput.setText("(执行中…)");
        });

        pool.execute(() -> {
            try {
                String json = httpPost(apiBase() + "/cmd?id=" + cmd.id);
                JSONObject o = new JSONObject(json);
                String execId = o.optString("execId", "");
                if (execId.isEmpty()) {
                    String err = o.optString("error", "unknown");
                    ui.post(() -> {
                        tvStatus.setText("执行失败：" + err);
                        tvOutput.setText(err);
                    });
                    return;
                }
                DebugLog.i("cmd exec: " + execId);
                // 轮询结果
                pollResult(execId, cmd.name);
            } catch (Throwable t) {
                DebugLog.e("runCmd failed", t);
                ui.post(() -> {
                    tvStatus.setText("执行失败：" + t.getMessage());
                    tvOutput.setText(String.valueOf(t.getMessage()));
                });
            }
        });
    }

    private void pollResult(String execId, String name) {
        final long startAt = System.currentTimeMillis();
        final int[] attempts = {0};

        Runnable poll = new Runnable() {
            @Override
            public void run() {
                attempts[0]++;
                // 最多等 60 秒
                if (System.currentTimeMillis() - startAt > 60000) {
                    tvStatus.setText("超时：" + name);
                    return;
                }
                pool.execute(() -> {
                    try {
                        String json = httpGet(apiBase() + "/cmd/result?id="
                                + java.net.URLEncoder.encode(execId, "UTF-8"));
                        JSONObject o = new JSONObject(json);
                        boolean running = o.optBoolean("running", false);
                        String output = o.optString("output", "");
                        int code = o.optInt("code", -1);

                        ui.post(() -> {
                            if (!isAdded()) return;
                            tvOutput.setText(output.isEmpty() ? "(无输出)" : output);
                            outputScroll.post(() ->
                                    outputScroll.fullScroll(View.FOCUS_DOWN));
                            if (running) {
                                tvStatus.setText("执行中… (" + output.length() + " 字符)");
                                ui.postDelayed(this, 800);
                            } else {
                                tvStatus.setText("完成 · 退出码 " + code);
                                tvStatus.setTextColor(code == 0 ? 0xFF34C759 : 0xFFB3261E);
                            }
                        });
                    } catch (Throwable t) {
                        ui.post(() -> {
                            if (attempts[0] < 20) ui.postDelayed(this, 1000);
                            else tvStatus.setText("查询失败：" + t.getMessage());
                        });
                    }
                });
            }
        };
        ui.post(poll);
    }

    // ==================== 工具 ====================

    private String apiBase() {
        return "http://" + settings.getHost() + ":" + settings.getPort();
    }

    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(30000);
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                char[] buf = new char[4096];
                int n;
                while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
            }
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String httpPost(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.getOutputStream().close();
            int code = conn.getResponseCode();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(
                            code >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                            "UTF-8"))) {
                char[] buf = new char[4096];
                int n;
                while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
            }
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
