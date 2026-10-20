package io.github.dshconsole.app;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * DSH 页面（WebView 内嵌）
 *
 * 特性：
 *   · 自动保存 Cookie（登录一次，下次免登）
 *   · 支持注入 token（设置里配置）
 *   · 加载失败显示重试
 *   · 返回键处理
 */
public class DshFragment extends Fragment {

    private AppSettings settings;
    private WebView web;
    private ProgressBar loading;
    private LinearLayout errorBox;
    private TextView tvError;
    private boolean retriedAuth = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup c,
                             @Nullable Bundle b) {
        settings = new AppSettings(requireContext());
        retriedAuth = false;   // ★每次新建 View 都重置重试标记

        FrameLayout root = new FrameLayout(requireContext());

        web = new WebView(requireContext());
        web.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(web);

        // 加载进度条
        loading = new ProgressBar(requireContext(), null,
                android.R.attr.progressBarStyleHorizontal);
        loading.setMax(100);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(3));
        lp.gravity = Gravity.TOP;
        root.addView(loading, lp);

        // 错误提示
        errorBox = new LinearLayout(requireContext());
        errorBox.setOrientation(LinearLayout.VERTICAL);
        errorBox.setGravity(Gravity.CENTER);
        errorBox.setVisibility(View.GONE);
        errorBox.setBackgroundColor(0xFFFFFFFF);

        tvError = new TextView(requireContext());
        tvError.setTextSize(14);
        tvError.setGravity(Gravity.CENTER);
        tvError.setPadding(dp(24), dp(24), dp(24), dp(16));
        errorBox.addView(tvError);

        Button retry = new Button(requireContext());
        retry.setText("重试");
        retry.setOnClickListener(v -> {
            retriedAuth = false;      // 重置重试标记
            clearAuthCookies();       // 清掉可能失效的 cookie
            loadDsh();
        });
        errorBox.addView(retry);

        Button relogin = new Button(requireContext());
        relogin.setText("重新登录（清 Cookie）");
        relogin.setOnClickListener(v -> {
            retriedAuth = false;
            clearAuthCookies();
            Toast.makeText(requireContext(), "已清除登录态，重新加载",
                    Toast.LENGTH_SHORT).show();
            loadDsh();
        });
        errorBox.addView(relogin);

        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        root.addView(errorBox, elp);

        setupWebView();
        loadDsh();

        return root;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " DshConsole/1.0");

        // Cookie 持久化
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                DebugLog.d("DSH load start: " + url);
                loading.setVisibility(View.VISIBLE);
                errorBox.setVisibility(View.GONE);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                DebugLog.i("DSH load done: " + url);
                loading.setVisibility(View.GONE);

                // ★检测是否 401：读取页面文本判断
                v.evaluateJavascript(
                        "(function(){try{return document.body?document.body.innerText:''}catch(e){return ''}})()",
                        value -> {
                            String text = value == null ? "" : value;
                            if (isAuthError(text) && !retriedAuth) {
                                retriedAuth = true;
                                DebugLog.w("DSH 401 detected, retry with token");
                                loading.setVisibility(View.VISIBLE);
                                loadDshWithToken();
                            } else if (isAuthError(text)) {
                                showError("DSH 鉴权失败\n\n"
                                        + "请检查：\n"
                                        + "1. 设置 → ② DSH 页面 → 访问 Token 是否填写\n"
                                        + "2. Token 是否已过期（DSH 重启会换 token）\n"
                                        + "3. 当前 URL: " + settings.getDshUrl());
                            }
                        });

                injectToken();
            }

            @Override
            public void onReceivedError(WebView v, android.webkit.WebResourceRequest req,
                                        android.webkit.WebResourceError err) {
                if (req == null || !req.isForMainFrame()) return;
                String d = String.valueOf(err.getDescription());
                DebugLog.e("DSH load error: " + d);
                showError("加载失败\n" + d);
            }
        });

        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                loading.setProgress(p);
                if (p >= 100) loading.setVisibility(View.GONE);
            }
        });
    }

    /** 注入 token（若设置了） */
    private void injectToken() {
        String token = settings.getDshToken();
        if (token == null || token.trim().isEmpty()) return;
        String js = "javascript:(function(){"
                + "try{"
                + "localStorage.setItem('dsh_token','" + token.replace("'", "\\'") + "');"
                + "localStorage.setItem('token','" + token.replace("'", "\\'") + "');"
                + "document.cookie='token=" + token.replace("'", "\\'") + ";path=/';"
                + "}catch(e){console.log('inject fail',e);}"
                + "})()";
        web.evaluateJavascript(js, null);
        DebugLog.i("token injected");
    }

    private void loadDsh() {
        String url = settings.getDshUrl();
        String token = settings.getDshToken();

        // ★关键修复：DSH 的鉴权是在 HTTP 层拦截的，
        //   必须在首次请求时就带上 ?token=xxx，
        //   不能等页面 onPageFinished 再注入（那时已经被 401 拦下）。
        //   DSH 验证 token 后会 303 跳转并 Set-Cookie，
        //   WebView 的 CookieManager 会自动保存，后续请求就免 token。
        if (token != null && !token.trim().isEmpty()
                && !url.contains("token=")) {
            String sep = url.contains("?") ? "&" : "?";
            url = url + sep + "token=" + token.trim();
            DebugLog.i("DSH loading with token (masked)");
        } else {
            DebugLog.i("DSH loading: " + url);
        }

        errorBox.setVisibility(View.GONE);
        try {
            web.loadUrl(url);
        } catch (Throwable t) {
            DebugLog.e("loadDsh failed", t);
            showError("无法加载: " + t.getMessage());
        }
    }

    /** 检测 401 错误文本 */
    private boolean isAuthError(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        return lower.contains("authentication required")
                || lower.contains("reopen the url")
                || lower.contains("401");
    }

    /** 强制带 token 重载（用于 401 后重试） */
    /** 清除 DSH 的登录 cookie（token 过期/换机器时用） */
    private void clearAuthCookies() {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
            DebugLog.i("auth cookies cleared");
        } catch (Throwable t) {
            DebugLog.e("clear cookies failed", t);
        }
    }


    private void loadDshWithToken() {
        String url = settings.getDshUrl();
        String token = settings.getDshToken();
        if (token != null && !token.trim().isEmpty() && !url.contains("token=")) {
            url = url + (url.contains("?") ? "&" : "?") + "token=" + token.trim();
        }
        DebugLog.i("DSH retry with token");
        errorBox.setVisibility(View.GONE);
        web.loadUrl(url);
    }


    private void showError(String msg) {
        tvError.setText(msg);
        errorBox.setVisibility(View.VISIBLE);
        loading.setVisibility(View.GONE);
    }

    /** 供 Activity 调用：返回键 */
    public boolean canGoBack() {
        return web != null && web.canGoBack();
    }

    public void goBack() {
        if (web != null) web.goBack();
    }

    public void reload() {
        if (web != null) web.reload();
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
