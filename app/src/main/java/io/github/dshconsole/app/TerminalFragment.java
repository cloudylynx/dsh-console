package io.github.dshconsole.app;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.HttpAuthHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;

/**
 * 终端页（内嵌 ttyd + 辅助键盘）
 *
 * ★辅助键盘
 *   模仿 Termux / Hacker's Keyboard 的终端功能区：
 *   ESC 方向键 Ctrl Alt FN Home End PgUp PgDn 等
 *
 * ★注入方式
 *   ttyd 的 xterm.js 实例暴露为 window.term，
 *   底层 WebSocket 协议：send([0x30, ...utf8(data)])
 *   0x30 = '0' = INPUT 消息类型
 *   注入时优先用 term.input() / coreService.triggerDataEvent()，再降级到 paste()
 */
public class TerminalFragment extends Fragment {

    private AppSettings settings;
    private Typeface font;
    private WebView web;
    private ProgressBar loading;
    private LinearLayout errorBox;
    private TextView tvError;
    private boolean retriedAuth = false;

    /** 辅助键盘是否展开 */
    private boolean keyboardVisible = true;

    /** 粘滞修饰键状态 */
    private boolean ctrlActive = false;
    private boolean altActive = false;
    private boolean fnActive = false;

    // 键盘容器与按钮引用
    private LinearLayout keypadContainer;
    private TextView btnCtrl, btnAlt, btnFn;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup c,
                             @Nullable Bundle b) {
        settings = new AppSettings(requireContext());
        try {
            font = ResourcesCompat.getFont(requireContext(), R.font.dsh_console_bold);
        } catch (Throwable ignored) {}
        retriedAuth = false;
        ctrlActive = altActive = fnActive = false;

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);

        // ===== 顶部工具条 =====
        LinearLayout toolbar = new LinearLayout(requireContext());
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(10), dp(6), dp(10), dp(6));
        // M3 表面色
        try {
            toolbar.setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(
                    toolbar, com.google.android.material.R.attr.colorSurfaceContainer));
        } catch (Throwable t) {
            toolbar.setBackgroundColor(0x0A000000);
        }

        com.google.android.material.button.MaterialButton btnReload = m3Button("刷新");
        try {
            btnReload.setIcon(androidx.core.content.ContextCompat.getDrawable(
                    requireContext(), R.drawable.ic_refresh));
            btnReload.setIconGravity(
                    com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START);
            btnReload.setIconSize(dp(16));
            btnReload.setIconPadding(dp(6));
        } catch (Throwable ignored) {}
        btnReload.setOnClickListener(v -> reloadWithCurrentSettings());
        toolbar.addView(btnReload);

        com.google.android.material.button.MaterialButton btnKb = m3Button("键盘");
        try {
            btnKb.setIcon(androidx.core.content.ContextCompat.getDrawable(
                    requireContext(), R.drawable.ic_keyboard));
            btnKb.setIconGravity(
                    com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START);
            btnKb.setIconSize(dp(16));
            btnKb.setIconPadding(dp(6));
        } catch (Throwable ignored) {}
        btnKb.setOnClickListener(v -> {
            keyboardVisible = !keyboardVisible;
            if (keypadContainer != null) {
                keypadContainer.setVisibility(keyboardVisible ? View.VISIBLE : View.GONE);
            }
            btnKb.setAlpha(keyboardVisible ? 1f : 0.6f);
        });
        toolbar.addView(btnKb);

        root.addView(toolbar);

        // ===== WebView 容器 =====
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

        errorBox = new LinearLayout(requireContext());
        errorBox.setOrientation(LinearLayout.VERTICAL);
        errorBox.setGravity(Gravity.CENTER);
        errorBox.setVisibility(View.GONE);

        tvError = new TextView(requireContext());
        tvError.setTextSize(14);
        tvError.setGravity(Gravity.CENTER);
        tvError.setPadding(dp(24), dp(24), dp(24), dp(16));
        errorBox.addView(tvError);

        Button retry = new Button(requireContext());
        retry.setText("重试");
        retry.setOnClickListener(v -> {
            retriedAuth = false;
            loadTerminal();
        });
        errorBox.addView(retry);

        webBox.addView(errorBox, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // ===== 辅助键盘 =====
        keypadContainer = buildKeypad();
        root.addView(keypadContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        setupWebView();
        loadTerminal();

        return root;
    }

    // ==================== 辅助键盘 ====================

    private LinearLayout buildKeypad() {
        LinearLayout pad = new LinearLayout(requireContext());
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setPadding(dp(4), dp(6), dp(4), dp(8));
        pad.setBackgroundColor(keypadBgColor());

        // ---- 第 1 行 ----
        LinearLayout row1 = newRow();
        pad.addView(row1, rowLp());
        addKey(row1, "ESC", () -> sendKey("esc"));
        addKey(row1, "<", () -> sendText("<"));
        addKey(row1, ">", () -> sendText(">"));
        addKey(row1, "\\", () -> sendText("\\"));
        addKey(row1, "=", () -> sendText("="));
        addKey(row1, "^", () -> sendText("^"));
        addKey(row1, "$", () -> sendText("$"));
        addKey(row1, "()", () -> sendText("()"));
        addKey(row1, "{}", () -> sendText("{}"));
        addKey(row1, "[]", () -> sendText("[]"));
        addKey(row1, "↵", () -> sendKey("enter"), 46);

        // ---- 第 2 行 ----
        LinearLayout row2 = newRow();
        pad.addView(row2, rowLp());
        addKey(row2, "⇤", () -> sendKey("tab"));
        addKey(row2, "&", () -> sendText("&"));
        addKey(row2, ";", () -> sendText(";"));
        addKey(row2, "/", () -> sendText("/"));
        addKey(row2, "~", () -> sendText("~"));
        addKey(row2, "%", () -> sendText("%"));
        addKey(row2, "*", () -> sendText("*"));
        addKey(row2, "HOME", () -> sendKey("home"));
        addKey(row2, "↑", () -> sendKey("up"));
        addKey(row2, "END", () -> sendKey("end"));
        addKey(row2, "PGUP", () -> sendKey("pgup"));

        // ---- 第 3 行 ----
        LinearLayout row3 = newRow();
        pad.addView(row3, rowLp());
        btnCtrl = addKey(row3, "CTRL", this::toggleCtrl);
        btnFn = addKey(row3, "FN", this::toggleFn);
        btnAlt = addKey(row3, "ALT", this::toggleAlt);
        addKey(row3, "|", () -> sendText("|"));
        addKey(row3, "-", () -> sendText("-"));
        addKey(row3, "+", () -> sendText("+"));
        addKey(row3, "\"", () -> sendText("\""));
        addKey(row3, "←", () -> sendKey("left"));
        addKey(row3, "↓", () -> sendKey("down"));
        addKey(row3, "→", () -> sendKey("right"));
        addKey(row3, "PGDN", () -> sendKey("pgdn"));

        return pad;
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout.LayoutParams rowLp() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    /** 添加一个键（返回按钮引用，便于高亮） */
    private TextView addKey(LinearLayout row, String label, Runnable action) {
        return addKey(row, label, action, 0);
    }

    private TextView addKey(LinearLayout row, String label, Runnable action, int minWidthDp) {
        TextView btn = new TextView(requireContext());
        btn.setText(label);
        btn.setTextSize(11);
        btn.setGravity(Gravity.CENTER);
        int ph = settings.getKeypadHeight();
        btn.setPadding(dp(9), dp(ph), dp(9), dp(ph));
        if (minWidthDp > 0) {
            btn.setMinWidth(dp(minWidthDp));
            btn.setMinimumWidth(dp(minWidthDp));
        }
        try {
            if (font != null) btn.setTypeface(font);
        } catch (Throwable ignored) {}

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(7));
        bg.setColor(keypadBtnColor());
        btn.setBackground(bg);
        btn.setTextColor(keypadTextColor());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(1), dp(2), dp(1), dp(2));
        row.addView(btn, lp);

        btn.setOnClickListener(v -> action.run());
        return btn;
    }

    // ==================== 配色（跟随主题 / 手动）====================

    /** 是否深色模式 */
    private boolean isDark() {
        int mode = settings.getKeypadTheme();
        if (mode == 1) return true;
        if (mode == 2) return false;
        return (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** 按键背景色 */
    private int keypadBtnColor() {
        return isDark() ? 0x33FFFFFF : 0xFFEDEDF0;
    }

    /** 按键文字色 */
    private int keypadTextColor() {
        return isDark() ? 0xFFE8E8EA : 0xFF1A1A1A;
    }

    /** 强调色（用于高亮） */
    private int accentColor() {
        try {
            return com.google.android.material.color.MaterialColors.getColor(
                    requireView(), com.google.android.material.R.attr.colorPrimary);
        } catch (Throwable t) {
            return 0xFF0A84FF;
        }
    }

    /** 键盘容器背景 */
    private int keypadBgColor() {
        return isDark() ? 0x1AFFFFFF : 0xFFF7F7FA;
    }


    // ==================== 修饰键 ====================

    private void toggleCtrl() {
        ctrlActive = !ctrlActive;
        if (ctrlActive) {
            altActive = false;
            fnActive = false;
        }
        updateModifierUi();
        DebugLog.d("ctrl=" + ctrlActive);
    }

    private void toggleAlt() {
        altActive = !altActive;
        if (altActive) {
            ctrlActive = false;
            fnActive = false;
        }
        updateModifierUi();
        DebugLog.d("alt=" + altActive);
    }

    private void toggleFn() {
        fnActive = !fnActive;
        updateModifierUi();
        DebugLog.d("fn=" + fnActive);
    }

    private void updateModifierUi() {
        highlight(btnCtrl, ctrlActive);
        highlight(btnAlt, altActive);
        highlight(btnFn, fnActive);
    }

    private void highlight(TextView btn, boolean on) {
        if (btn == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(7));
        bg.setColor(on ? accentColor() : keypadBtnColor());
        btn.setBackground(bg);
        btn.setTextColor(on ? 0xFFFFFFFF : keypadTextColor());
    }

    /** 发送普通文本（受修饰键影响） */
    private void sendText(String text) {
        String data;
        if (ctrlActive) {
            data = ctrlChars(text);
            ctrlActive = false;
            updateModifierUi();
        } else if (altActive) {
            data = ESC + text;
            altActive = false;
            updateModifierUi();
        } else {
            data = text;
        }
        injectData(data);
    }

    /** 发送特殊键 */
    private void sendKey(String key) {
        String seq = keySequence(key);
        if (seq == null) return;

        if (ctrlActive) {
            // Ctrl + 特殊键：主要处理 Ctrl+C 等
            if ("c".equals(key)) seq = String.valueOf((char) 3);
        }
        if (altActive) {
            seq = ESC + seq;
            altActive = false;
            updateModifierUi();
        }
        injectData(seq);
    }

    /** Ctrl+字符 → 控制字符 */
    private String ctrlChars(String s) {
        if (s.isEmpty()) return s;
        char c = s.charAt(0);
        if (c >= 'a' && c <= 'z') return String.valueOf((char) (c - 'a' + 1));
        if (c >= 'A' && c <= 'Z') return String.valueOf((char) (c - 'A' + 1));
        switch (c) {
            case '[': return ESC;
            case '\\': return String.valueOf((char) 28);
            case ']': return String.valueOf((char) 29);
            case '^': return String.valueOf((char) 30);
            case '_': return String.valueOf((char) 31);
            case ' ': return String.valueOf((char) 0);
            default: return s;
        }
    }

    /** 特殊键的转义序列 */
    private String keySequence(String key) {
        // NOTE: Java unicode escapes are processed BEFORE lexing,
        //       so CR/LF escapes would break the source file.
        //       Use octal or char casts instead.
        switch (key) {
            case "esc":   return ESC;
            case "tab":   return "\011";          // HT
            case "enter": return "\015";          // CR
            case "up":    return ESC + "[A";
            case "down":  return ESC + "[B";
            case "right": return ESC + "[C";
            case "left":  return ESC + "[D";
            case "home":  return ESC + "[H";
            case "end":   return ESC + "[F";
            case "pgup":  return ESC + "[5~";
            case "pgdn":  return ESC + "[6~";
            case "del":   return ESC + "[3~";
            default:      return null;
        }
    }

    /** ESC 字符（0x1B） */
    private static final String ESC = String.valueOf((char) 27);

    /**
     * ★核心：把数据注入到 ttyd 的 WebSocket
     *
     * 多级降级策略：
     *   1. term.input(data, true)            — xterm 公开 API
     *   2. coreService.triggerDataEvent()    — 底层
     *   3. term.paste(data)                  — 模拟粘贴
     *   4. 直接找 socket.send([0x30,...])    — 兜底
     */
    private void injectData(String data) {
        if (web == null) return;
        String escaped = jsEscape(data);

        String js = "(function(){"
                + "var d='" + escaped + "';"
                + "var t=window.term;"
                // 1. term.input()
                + "try{"
                + "  if(t&&typeof t.input==='function'){t.input(d,true);return 'input';}"
                + "}catch(e){}"
                // 2. coreService.triggerDataEvent
                + "try{"
                + "  var cs=t&&t.coreService;"
                + "  if(cs&&typeof cs.triggerDataEvent==='function'){"
                + "    cs.triggerDataEvent(d,true);return 'trigger';}"
                + "}catch(e){}"
                // 3. term.paste()
                + "try{"
                + "  if(t&&typeof t.paste==='function'){t.paste(d);return 'paste';}"
                + "}catch(e){}"
                // 4. 找 socket 直接发（ttyd 协议：0x30 + utf8）
                + "try{"
                + "  var sock=null;"
                + "  var walk=function(o,depth){"
                + "    if(!o||depth>3||sock)return;"
                + "    try{"
                + "      for(var k in o){"
                + "        if(k==='socket'&&o[k]&&o[k].send){sock=o[k];return;}"
                + "      }"
                + "      if(o.coreService)walk(o.coreService,depth+1);"
                + "    }catch(e){}"
                + "  };"
                + "  walk(t,0);"
                + "  if(sock){"
                + "    var enc=new TextEncoder();"
                + "    var payload=enc.encode(d);"
                + "    var buf=new Uint8Array(payload.length+1);"
                + "    buf[0]=0x30;"
                + "    buf.set(payload,1);"
                + "    sock.send(buf);"
                + "    return 'socket';"
                + "  }"
                + "}catch(e){}"
                + "return 'none';"
                + "})()";

        web.evaluateJavascript(js, value -> {
            DebugLog.d("inject [" + printable(data) + "] -> " + value);
        });
    }

    /** 转成可打印形式（用于日志） */
    private String printable(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c < 32) sb.append("\\x").append(String.format("%02x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }

    /** JS 字符串转义 */
    private String jsEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 32) {
                        sb.append("\\x").append(String.format("%02x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    // ==================== WebView ====================

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setUserAgentString(s.getUserAgentString() + " DshConsole/1.0");
        // ★兜底方案：WebView 的文本缩放（对 canvas 无效，但影响 DOM 部分）
        s.setTextZoom(100);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                DebugLog.d("Terminal load start");
                loading.setVisibility(View.VISIBLE);
                errorBox.setVisibility(View.GONE);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                DebugLog.i("Terminal load done");
                loading.setVisibility(View.GONE);
                // 稍等再应用，确保 xterm 已初始化
                // 分三次尝试，确保 xterm 完全初始化
                web.postDelayed(() -> applyFontSize(), 300);
                web.postDelayed(() -> applyFontSize(), 1000);
                web.postDelayed(() -> {
                    applyFontSize();
                    installHelper();
                }, 2000);
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req,
                                        WebResourceError err) {
                if (req == null || !req.isForMainFrame()) return;
                String d = String.valueOf(err.getDescription());
                DebugLog.e("Terminal error: " + d);
                showError("终端加载失败\n\n" + d);
            }

            @Override
            public void onReceivedHttpAuthRequest(WebView v, HttpAuthHandler handler,
                                                  String host, String realm) {
                String user = settings.getTerminalUser();
                String pass = settings.getTerminalPass();
                DebugLog.i("terminal auth request host=" + host);
                if (!user.isEmpty() || !pass.isEmpty()) {
                    handler.proceed(user, pass);
                } else {
                    handler.cancel();
                    showError("终端需要密码\n\n请到 设置 → ④ 终端与文件 填写");
                }
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

    /** 安装辅助脚本（检测 window.term 是否就绪） */
    private void installHelper() {
        String js = "(function(){"
                + "window.__dshReady = !!(window.term);"
                + "return window.__dshReady ? 'ok' : 'no-term';"
                + "})()";
        web.evaluateJavascript(js, v -> DebugLog.i("helper install -> " + v));
    }

    /** ★强制应用字号（立即生效） */
    public void applyFontSize() {
        if (web == null) return;
        final int size = settings.getTerminalFontSize();

        // ★关键：ttyd 用 WebGL 渲染器，CSS font-size 对 canvas 无效！
        //   必须设置 xterm 的 options.fontSize 并触发重排/fit。
        //   xterm 5.x 的 options 是 setter，直接赋值即可。
        String js = "(function(){"
                + "var s=" + size + ";"
                + "var result={size:s};"
                + "var t=window.term;"
                + "if(!t){return 'no-term';}"
                // 方案A: options.fontSize 赋值 + fit
                + "try{"
                + "  if(t.options){t.options.fontSize=s;result.a='options';}"
                + "}catch(e){result.aErr=String(e);}"
                // 方案B: 内部 optionsService
                + "try{"
                + "  var os=t._core&&t._core._optionsService;"
                + "  if(!os)os=t._optionsService;"
                + "  if(os&&os.options){os.options.fontSize=s;result.b='service';}"
                + "}catch(e){result.bErr=String(e);}"
                // 方案C: 旧的 setOption API
                + "try{"
                + "  if(typeof t.setOption==='function'){t.setOption('fontSize',s);result.c='setOption';}"
                + "}catch(e){result.cErr=String(e);}"
                // 触发重排
                + "try{"
                + "  if(typeof t.fit==='function'){t.fit();result.fit='ok';}"
                + "}catch(e){result.fitErr=String(e);}"
                // 强制刷新
                + "try{"
                + "  if(typeof t.refresh==='function'){t.refresh(0,999);result.refresh='ok';}"
                + "}catch(e){}"
                + "return JSON.stringify(result);"
                + "})()";

        web.evaluateJavascript(js, v -> {
            DebugLog.i("fontSize inject -> " + v);
        });
    }

    /** ★重建键盘（高度/配色改了） */
    public void rebuildKeypad() {
        try {
            if (keypadContainer == null) return;
            ViewGroup parent = (ViewGroup) keypadContainer.getParent();
            if (parent == null) return;
            int idx = parent.indexOfChild(keypadContainer);
            boolean wasVisible = keypadContainer.getVisibility() == View.VISIBLE;
            parent.removeView(keypadContainer);
            keypadContainer = buildKeypad();
            if (!wasVisible) keypadContainer.setVisibility(View.GONE);
            parent.addView(keypadContainer, idx, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            DebugLog.i("keypad rebuilt (height=" + settings.getKeypadHeight() + ")");
        } catch (Throwable t) {
            DebugLog.e("rebuildKeypad failed", t);
        }
    }

    /** 供外部调用：所有终端设置变了 */
    public void onAllSettingsChanged() {
        applyFontSize();
        rebuildKeypad();
    }

    /** 供外部调用：设置变了，立即刷新字号 */
    public void onSettingsChanged() {
        if (web == null) return;
        DebugLog.i("terminal settings changed, reapplying");
        applyFontSize();
    }

    /** 刷新终端（重新加载页面） */
    public void reloadTerminal() {
        reloadWithCurrentSettings();
    }

    private void reloadWithCurrentSettings() {
        if (web == null) return;
        DebugLog.i("terminal reload");
        retriedAuth = false;
        ctrlActive = altActive = fnActive = false;
        updateModifierUi();
        loadTerminal();
    }

    private void loadTerminal() {
        String url = settings.getTerminalUrlWithFont();
        // 加时间戳强制刷新（避免缓存）
        url = url + (url.contains("?") ? "&" : "?") + "_t=" + System.currentTimeMillis();
        DebugLog.i("Terminal loading: " + url);
        errorBox.setVisibility(View.GONE);
        try {
            web.loadUrl(url);
        } catch (Throwable t) {
            DebugLog.e("loadTerminal failed", t);
            showError("无法加载: " + t.getMessage());
        }
    }

    private void showError(String msg) {
        tvError.setText(msg);
        errorBox.setVisibility(View.VISIBLE);
        loading.setVisibility(View.GONE);
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

    /** ★M3 文字按钮（Tonal 风格，程序化配色） */
    private com.google.android.material.button.MaterialButton m3Button(String text) {
        com.google.android.material.button.MaterialButton b =
                new com.google.android.material.button.MaterialButton(requireContext());

        // 手动实现 Tonal 配色：
        //   背景 = colorSecondaryContainer
        //   文字 = colorOnSecondaryContainer
        try {
            int bg = com.google.android.material.color.MaterialColors.getColor(
                    b, com.google.android.material.R.attr.colorSecondaryContainer);
            int fg = com.google.android.material.color.MaterialColors.getColor(
                    b, com.google.android.material.R.attr.colorOnSecondaryContainer);
            b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(bg));
            b.setTextColor(fg);
            b.setIconTint(android.content.res.ColorStateList.valueOf(fg));
        } catch (Throwable t) {
            DebugLog.w("m3Button color failed: " + t);
        }

        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setCornerRadius(dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(36));
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
