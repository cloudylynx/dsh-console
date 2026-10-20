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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 消息中心（A2）
 *
 * 功能：
 *   · 显示所有收到的消息（未读高亮）
 *   · 时间倒序
 *   · 点击标记已读
 *   · 全部已读按钮
 *   · 下拉刷新
 */
public class MessagesFragment extends Fragment {

    private AppSettings settings;
    private Typeface font;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    private SwipeRefreshLayout swipe;
    private LinearLayout listContainer;
    private TextView tvEmpty, tvUnread;

    private final List<Snapshot.Msg> messages = new ArrayList<>();

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
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(14), dp(16), dp(10));

        LinearLayout titleBox = new LinearLayout(requireContext());
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(requireContext());
        title.setText("消息");
        title.setTextSize(22);
        title.setTypeface(font != null ? font : Typeface.DEFAULT_BOLD);
        titleBox.addView(title);

        tvUnread = new TextView(requireContext());
        tvUnread.setTextSize(11);
        tvUnread.setTextColor(0xFF888888);
        tvUnread.setText("加载中…");
        titleBox.addView(tvUnread);

                // ★返回按钮（overlay 模式没有底栏）
        TextView btnBack = new TextView(requireContext());
        btnBack.setText("\u2190");
        btnBack.setTextSize(22);
        btnBack.setPadding(dp(8), dp(4), dp(16), dp(4));
        btnBack.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).closeOverlay();
            }
        });
        header.addView(btnBack, 0);

        header.addView(titleBox);

        TextView btnReadAll = new TextView(requireContext());
        btnReadAll.setText("全部已读");
        btnReadAll.setTextSize(12);
        btnReadAll.setPadding(dp(12), dp(7), dp(12), dp(7));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0x11000000);
        btnReadAll.setBackground(bg);
        btnReadAll.setOnClickListener(v -> markAllRead());
        header.addView(btnReadAll);

        root.addView(header);

        // ===== 列表 =====
        swipe = new SwipeRefreshLayout(requireContext());
        ScrollView sv = new ScrollView(requireContext());
        listContainer = new LinearLayout(requireContext());
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(dp(12), 0, dp(12), dp(24));
        sv.addView(listContainer);
        swipe.addView(sv);
        swipe.setOnRefreshListener(this::loadMessages);

        tvEmpty = new TextView(requireContext());
        tvEmpty.setText("暂无消息");
        tvEmpty.setTextSize(13);
        tvEmpty.setTextColor(0xFF999999);
        tvEmpty.setGravity(Gravity.CENTER);
        tvEmpty.setPadding(0, dp(60), 0, 0);

        root.addView(swipe, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    // ==================== 数据 ====================

    @Override
    public void onResume() {
        super.onResume();
        loadMessages();
    }

    private void loadMessages() {
        pool.execute(() -> {
            try {
                String json = httpGet("http://" + settings.getHost() + ":"
                        + settings.getPort() + "/messages");
                final Snapshot tmp = new Snapshot();
                org.json.JSONObject root = new org.json.JSONObject(json);
                org.json.JSONArray arr = root.optJSONArray("messages");
                List<Snapshot.Msg> loaded = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        org.json.JSONObject o = arr.getJSONObject(i);
                        Snapshot.Msg m = new Snapshot.Msg();
                        m.id = o.optString("id", "");
                        m.title = o.optString("title", "");
                        m.body = o.optString("body", "");
                        m.from = o.optString("from", "");
                        m.ts = o.optLong("ts", 0);
                        m.read = o.optBoolean("read", false);
                        loaded.add(m);
                    }
                }
                ui.post(() -> {
                    if (!isAdded()) return;
                    messages.clear();
                    messages.addAll(loaded);
                    render();
                    if (swipe != null) swipe.setRefreshing(false);
                });
            } catch (Throwable t) {
                DebugLog.e("loadMessages failed", t);
                ui.post(() -> {
                    if (!isAdded()) return;
                    tvUnread.setText("加载失败：" + t.getMessage());
                    if (swipe != null) swipe.setRefreshing(false);
                });
            }
        });
    }

    private void render() {
        listContainer.removeAllViews();

        int unread = 0;
        for (Snapshot.Msg m : messages) if (!m.read) unread++;

        tvUnread.setText(messages.isEmpty() ? "暂无消息"
                : (unread > 0 ? unread + " 条未读 / 共 " + messages.size() + " 条"
                              : "全部已读 · 共 " + messages.size() + " 条"));
        tvUnread.setTextColor(unread > 0 ? 0xFFB3261E : 0xFF34C759);

        // ★通知 MainActivity 更新角标
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).updateMessageBadge(unread);
        }

        if (messages.isEmpty()) {
            listContainer.addView(tvEmpty);
            return;
        }

        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        for (Snapshot.Msg m : messages) {
            listContainer.addView(buildMsgRow(m, fmt));
        }
    }

    private View buildMsgRow(Snapshot.Msg m, SimpleDateFormat fmt) {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        // 顶部：发件人 + 时间 + 未读点
        LinearLayout head = new LinearLayout(requireContext());
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        if (!m.read) {
            View dot = new View(requireContext());
            int ds = dp(7);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ds, ds);
            dlp.rightMargin = dp(7);
            dot.setLayoutParams(dlp);
            GradientDrawable dbg = new GradientDrawable();
            dbg.setShape(GradientDrawable.OVAL);
            try {
                dbg.setColor(com.google.android.material.color.MaterialColors.getColor(
                        card, com.google.android.material.R.attr.colorPrimary));
            } catch (Throwable t) {
                dbg.setColor(0xFF0A84FF);
            }
            dot.setBackground(dbg);
            head.addView(dot);
        }

        TextView tvFrom = new TextView(requireContext());
        tvFrom.setText(m.from.isEmpty() ? "DSH" : m.from);
        tvFrom.setTextSize(12);
        tvFrom.setTextColor(0xFF666666);
        if (!m.read && font != null) tvFrom.setTypeface(font, Typeface.BOLD);
        tvFrom.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(tvFrom);

        TextView tvTime = new TextView(requireContext());
        tvTime.setText(fmt.format(new Date(m.ts)));
        tvTime.setTextSize(10);
        tvTime.setTextColor(0xFF999999);
        head.addView(tvTime);

        card.addView(head);

        // 标题
        if (!m.title.isEmpty()) {
            TextView tvTitle = new TextView(requireContext());
            tvTitle.setText(m.title);
            tvTitle.setTextSize(14);
            tvTitle.setPadding(0, dp(6), 0, 0);
            if (!m.read) {
                tvTitle.setTypeface(font != null ? font : Typeface.DEFAULT_BOLD);
                tvTitle.setTextColor(0xFF1A1A1A);
            } else {
                tvTitle.setTextColor(0xFF555555);
            }
            card.addView(tvTitle);
        }

        // 内容
        if (!m.body.isEmpty()) {
            TextView tvBody = new TextView(requireContext());
            tvBody.setText(m.body);
            tvBody.setTextSize(13);
            tvBody.setTextColor(m.read ? 0xFF888888 : 0xFF333333);
            tvBody.setPadding(0, dp(5), 0, 0);
            card.addView(tvBody);
        }

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(m.read ? 0x06000000 : 0x0F0A84FF);
        card.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);

        // 点击标记已读
        card.setOnClickListener(v -> {
            if (!m.read) markRead(m);
        });

        return card;
    }

    private void markRead(Snapshot.Msg m) {
        pool.execute(() -> {
            try {
                String url = "http://" + settings.getHost() + ":" + settings.getPort()
                        + "/message/read?id=" + java.net.URLEncoder.encode(m.id, "UTF-8");
                httpPost(url);
                DebugLog.i("message marked read: " + m.id);
                ui.post(this::loadMessages);
            } catch (Throwable t) {
                DebugLog.e("markRead failed", t);
            }
        });
    }

    private void markAllRead() {
        pool.execute(() -> {
            try {
                String url = "http://" + settings.getHost() + ":" + settings.getPort()
                        + "/message/read-all";
                httpPost(url);
                DebugLog.i("all messages marked read");
                ui.post(() -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(), "全部已读", Toast.LENGTH_SHORT).show();
                    loadMessages();
                });
            } catch (Throwable t) {
                DebugLog.e("markAllRead failed", t);
            }
        });
    }

    // ==================== 工具 ====================

    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(10000);
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

    private void httpPost(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.getOutputStream().close();
            conn.getResponseCode();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
