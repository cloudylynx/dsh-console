package io.github.dshconsole.app;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * 实时日志页（WebView 内嵌后端日志接口）
 *
 * ★为什么用 WebView 而不是原生重写
 *   · 那个页面已经有 Tab 切换、SSE 推流、彩色分类、状态栏
 *   · 用 WebView 直接套，功能一毛不少，还省得两套代码不同步
 *
 * ★后端可选启用 Token 认证（见 server/README）
 *   如果以后加了认证，在设置里填，这里用 Basic Auth 带上
 */
public class LogsFragment extends Fragment {

    private AppSettings settings;
    private WebView web;
    private ProgressBar loading;
    private TextView tvError;
    private LinearLayout errorBox;
    private TextView btnReload;
    private TextView btnAuto;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup c,
                             @Nullable Bundle b) {
        settings = new AppSettings(requireContext());
        return buildView();
    }

    private View buildView() {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);

        // ===== 顶部工具条 =====
        LinearLayout toolbar = new LinearLayout(requireContext());
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), dp(8), dp(12), dp(8));
        try {
            toolbar.setBackgroundColor(
                    com.google.android.material.color.MaterialColors.getColor(
                            toolbar, com.google.android.material.R.attr.colorSurfaceContainer));
        } catch (Throwable t) {
            toolbar.setBackgroundColor(0x0A000000);
        }

        // 返回（关闭二级页）
        TextView btnBack = new TextView(requireContext());
        btnBack.setText("←");
        btnBack.setTextSize(22);
        btnBack.setPadding(0, dp(2), dp(14), dp(2));
        btnBack.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).closeOverlay();
            }
        });
        toolbar.addView(btnBack);

        TextView title = new TextView(requireContext());
        title.setText("实时日志");
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        toolbar.addView(title);

        btnReload = toolbarButton("刷新");
        btnReload.setOnClickListener(v -> reload());
        toolbar.addView(btnReload);

        btnAuto = toolbarButton("自动滚动");
        btnAuto.setOnClickListener(v -> toggleAutoScroll());
        toolbar.addView(btnAuto);

        root.addView(toolbar);

        // ===== WebView =====
        FrameLayout webBox = new FrameLayout(requireContext());
        root.addView(webBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        web = new WebView(requireContext());
        web.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        webBox.addView(web);

        loading = new ProgressBar(requireContext(), null,
                android.R.attr.progressBarStyleHorizontal);
        loading.setMax(100);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(3));
        lp.gravity = Gravity.TOP;
        webBox.addView(loading, lp);

        // 错误层
        errorBox = new LinearLayout(requireContext());
        errorBox.setOrientation(LinearLayout.VERTICAL);
        errorBox.setGravity(Gravity.CENTER);
        errorBox.setVisibility(View.GONE);
        errorBox.setBackgroundColor(0xFFFAFAFA);

        tvError = new TextView(requireContext());
        tvError.setTextSize(13);
        tvError.setGravity(Gravity.CENTER);
        tvError.setPadding(dp(24), dp(24), dp(24), dp(16));
        errorBox.addView(tvError);

        TextView retry = new TextView(requireContext());
        retry.setText("重试");
        retry.setTextSize(14);
        retry.setPadding(dp(22), dp(10), dp(22), dp(10));
        android.graphics.drawable.GradientDrawable rbg =
                new android.graphics.drawable.GradientDrawable();
        rbg.setCornerRadius(dp(20));
        rbg.setColor(0xFF7AA2F7);
        retry.setBackground(rbg);
        retry.setTextColor(0xFFFFFFFF);
        retry.setOnClickListener(v -> reload());
        errorBox.addView(retry);

        webBox.addView(errorBox, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        setupWebView();
        reload();
        return root;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);           // 页面靠 JS 做 SSE 和 Tab
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setUserAgentString(s.getUserAgentString() + " DshConsole/1.0");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                loading.setVisibility(View.VISIBLE);
                errorBox.setVisibility(View.GONE);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                loading.setVisibility(View.GONE);
                DebugLog.i("log page loaded");
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req,
                                        WebResourceError err) {
                if (req == null || !req.isForMainFrame()) return;
                String d = String.valueOf(err.getDescription());
                DebugLog.e("log page error: " + d);
                showError("日志页面加载失败\n\n" + d
                        + "\n\n检查服务：\nsystemctl status dsh-console");
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                loading.setProgress(p);
                if (p >= 100) loading.setVisibility(View.GONE);
            }
        });
    }

    private void reload() {
        try {
            String url = "http://" + settings.getHost() + ":" + settings.getLogPort() + "/api/logs";
            DebugLog.i("loading log page: " + url);
            errorBox.setVisibility(View.GONE);
            web.loadUrl(url);
        } catch (Throwable t) {
            DebugLog.e("reload failed", t);
            showError("无法加载：" + t.getMessage());
        }
    }

    /** 切换自动滚动（用 JS 操作页面内部变量） */
    private void toggleAutoScroll() {
        if (web == null) return;
        // 页面里有个 autoScroll 变量，但它是模块作用域，取不到。
        // 改为直接滚动到底部/保持当前位置
        String js = "(function(){"
                + "var el=document.getElementById('log');"
                + "if(!el)return 'no-el';"
                + "var atBottom = el.scrollTop + el.clientHeight >= el.scrollHeight - 40;"
                + "if(atBottom){el.scrollTop=0;return 'paused';}"
                + "else{el.scrollTop=el.scrollHeight;return 'resumed';}"
                + "})()";
        web.evaluateJavascript(js, value -> {
            boolean paused = String.valueOf(value).contains("paused");
            btnAuto.setAlpha(paused ? 0.45f : 1f);
            DebugLog.d("autoscroll toggle: " + value);
        });
    }

    private void showError(String msg) {
        tvError.setText(msg);
        errorBox.setVisibility(View.VISIBLE);
        loading.setVisibility(View.GONE);
    }

    private TextView toolbarButton(String text) {
        TextView b = new TextView(requireContext());
        b.setText(text);
        b.setTextSize(11);
        b.setPadding(dp(10), dp(5), dp(10), dp(5));
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(14));
        try {
            bg.setColor(com.google.android.material.color.MaterialColors.getColor(
                    b, com.google.android.material.R.attr.colorSecondaryContainer));
            b.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                    b, com.google.android.material.R.attr.colorOnSecondaryContainer));
        } catch (Throwable t) {
            bg.setColor(0x11000000);
            b.setTextColor(0xFF333333);
        }
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    @Override
    public void onDestroyView() {
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroyView();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
