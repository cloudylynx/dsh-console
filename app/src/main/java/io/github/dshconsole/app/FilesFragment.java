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
import android.widget.EditText;
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
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 文件管理页
 *
 * 功能：
 *   · 浏览白名单目录（下载站 / 中控台 / 恢复镜像 / 临时 / Home）
 *   · 显示大小、修改时间
 *   · 点击文件下载
 *   · ★删除需二次确认（弹窗必须点"确认删除"）
 */
public class FilesFragment extends Fragment {

    private AppSettings settings;
    private Typeface font;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    private LinearLayout rootList;
    private TextView tvPath, tvStatus;
    private LinearLayout breadcrumb;
    private com.google.android.material.button.MaterialButton btnUpload;

    private int currentRoot = 0;
    private String currentPath = "";
    private String currentRootName = "";
    private boolean currentWritable = false;

    /** 文件选择请求码 */
    private static final int REQ_PICK_FILE = 1001;

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
        LinearLayout outer = new LinearLayout(requireContext());
        outer.setOrientation(LinearLayout.VERTICAL);

        // ===== 顶部：路径 + 根目录切换 =====
        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(8));

        TextView title = new TextView(requireContext());
        title.setText("文件");
        title.setTextSize(22);
        title.setTypeface(font != null ? font : Typeface.DEFAULT_BOLD);
        header.addView(title);

        // 根目录横排
        HorizontalScrollView hsv = new HorizontalScrollView(requireContext());
        hsv.setHorizontalScrollBarEnabled(false);
        breadcrumb = new LinearLayout(requireContext());
        breadcrumb.setOrientation(LinearLayout.HORIZONTAL);
        breadcrumb.setPadding(0, dp(8), 0, 0);
        hsv.addView(breadcrumb);
        header.addView(hsv);

        tvPath = new TextView(requireContext());
        tvPath.setTextSize(11);
        tvPath.setTextColor(0xFF888888);
        tvPath.setPadding(0, dp(6), 0, 0);
        header.addView(tvPath);

        outer.addView(header);

        // ===== 分隔线 =====
        View divider = new View(requireContext());
        divider.setBackgroundColor(0x11757575);
        outer.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        // ===== 状态行 =====
        // 状态行 + 上传按钮
        LinearLayout statusRow = new LinearLayout(requireContext());
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(dp(16), dp(6), dp(12), dp(6));

        tvStatus = new TextView(requireContext());
        tvStatus.setTextSize(11);
        tvStatus.setTextColor(0xFF999999);
        tvStatus.setText("加载中…");
        tvStatus.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        statusRow.addView(tvStatus);

        // ★上传按钮（B2）
        btnUpload = new com.google.android.material.button.MaterialButton(requireContext());
        btnUpload.setText("上传");
        btnUpload.setTextSize(11);
        btnUpload.setAllCaps(false);
        btnUpload.setMinWidth(0);
        btnUpload.setMinimumWidth(0);
        btnUpload.setInsetTop(0);
        btnUpload.setInsetBottom(0);
        btnUpload.setPadding(dp(12), 0, dp(12), 0);
        btnUpload.setCornerRadius(dp(8));
        try {
            int bg = com.google.android.material.color.MaterialColors.getColor(
                    btnUpload, com.google.android.material.R.attr.colorPrimaryContainer);
            int fg = com.google.android.material.color.MaterialColors.getColor(
                    btnUpload, com.google.android.material.R.attr.colorOnPrimaryContainer);
            btnUpload.setBackgroundTintList(android.content.res.ColorStateList.valueOf(bg));
            btnUpload.setTextColor(fg);
        } catch (Throwable ignored) {}
        btnUpload.setOnClickListener(v -> pickFile());
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(32));
        statusRow.addView(btnUpload, ulp);

        outer.addView(statusRow);

        // ===== 文件列表 =====
        ScrollView sv = new ScrollView(requireContext());
        rootList = new LinearLayout(requireContext());
        rootList.setOrientation(LinearLayout.VERTICAL);
        rootList.setPadding(dp(10), 0, dp(10), dp(24));
        sv.addView(rootList);
        outer.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        return outer;
    }

    // ==================== 数据加载 ====================

    @Override
    public void onResume() {
        super.onResume();
        loadRoots();
    }

    private void loadRoots() {
        pool.execute(() -> {
            try {
                String json = httpGet(apiBase() + "/roots");
                JSONObject o = new JSONObject(json);
                JSONArray arr = o.getJSONArray("roots");
                final List<String> names = new ArrayList<>();
                final List<Boolean> writable = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject r = arr.getJSONObject(i);
                    names.add(r.getString("name"));
                    writable.add(r.getBoolean("writable"));
                }
                ui.post(() -> {
                    if (!isAdded()) return;
                    buildRootButtons(names);
                    loadDir(0, "");
                });
            } catch (Throwable t) {
                DebugLog.e("loadRoots failed", t);
                ui.post(() -> {
                    if (isAdded()) tvStatus.setText("连接文件服务失败：" + t.getMessage());
                });
            }
        });
    }

    private void buildRootButtons(List<String> names) {
        breadcrumb.removeAllViews();
        for (int i = 0; i < names.size(); i++) {
            final int idx = i;
            TextView btn = new TextView(requireContext());
            btn.setText(names.get(i));
            btn.setTextSize(13);
            btn.setPadding(dp(12), dp(6), dp(12), dp(6));
            btn.setTypeface(font != null ? font : Typeface.DEFAULT);

            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(16));
            bg.setColor(idx == currentRoot ? 0xFF0A84FF : 0x11000000);
            btn.setBackground(bg);
            btn.setTextColor(idx == currentRoot ? 0xFFFFFFFF : 0xFF333333);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            breadcrumb.addView(btn, lp);

            btn.setOnClickListener(v -> {
                currentRoot = idx;
                currentPath = "";
                buildRootButtons(names);
                loadDir(idx, "");
            });
        }
    }

    private void loadDir(int rootIdx, String path) {
        tvStatus.setText("加载中…");
        pool.execute(() -> {
            try {
                String url = apiBase() + "/list?root=" + rootIdx
                        + "&path=" + URLEncoder.encode(path, "UTF-8");
                String json = httpGet(url);
                final JSONObject o = new JSONObject(json);
                ui.post(() -> {
                    if (!isAdded()) return;
                    renderDir(o);
                });
            } catch (Throwable t) {
                DebugLog.e("loadDir failed", t);
                ui.post(() -> {
                    if (isAdded()) tvStatus.setText("加载失败：" + t.getMessage());
                });
            }
        });
    }

    private void renderDir(JSONObject o) {
        try {
            currentPath = o.optString("path", "/");
            currentRootName = o.optString("root", "");
            currentWritable = o.optBoolean("writable", false);

            String abs = o.optString("absPath", "");
            tvPath.setText(abs);
            int count = o.optInt("count", 0);
            tvStatus.setText(count + " 项"
                    + (currentWritable ? " · 可删除" : " · 只读"));

            rootList.removeAllViews();

            JSONArray items = o.optJSONArray("items");
            if (items == null || items.length() == 0) {
                TextView empty = new TextView(requireContext());
                empty.setText("(空目录)");
                empty.setTextSize(13);
                empty.setTextColor(0xFF999999);
                empty.setPadding(dp(16), dp(30), 0, 0);
                rootList.addView(empty);
                return;
            }

            // 返回上级
            if (!currentPath.equals("") && !currentPath.equals("/")) {
                rootList.addView(buildBackRow());
            }

            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                rootList.addView(buildFileRow(it));
            }
        } catch (Throwable t) {
            DebugLog.e("renderDir failed", t);
        }
    }

    private View buildBackRow() {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView tv = new TextView(requireContext());
        tv.setText("⬆ 返回上级");
        tv.setTextSize(13);
        tv.setTextColor(0xFF0A84FF);
        row.addView(tv);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(0x06000000);
        row.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);

        row.setOnClickListener(v -> {
            String parent = currentPath;
            int idx = parent.lastIndexOf('/');
            if (idx <= 0) parent = "";
            else parent = parent.substring(0, idx);
            loadDir(currentRoot, parent);
        });
        return row;
    }

    private View buildFileRow(JSONObject it) throws Exception {
        final String name = it.getString("name");
        final boolean isDir = it.getBoolean("isDir");
        final String sizeText = it.optString("sizeText", "");
        final long mtime = it.optLong("mtime", 0);
        final boolean writable = it.optBoolean("writable", false);
        final String fullPath = (currentPath.endsWith("/") ? currentPath : currentPath + "/") + name;

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));

        // 图标
        TextView tvIcon = new TextView(requireContext());
        tvIcon.setText(isDir ? "📁" : iconFor(name));
        tvIcon.setTextSize(18);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ilp.rightMargin = dp(10);
        row.addView(tvIcon, ilp);

        // 名字 + 详情
        LinearLayout texts = new LinearLayout(requireContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvName = new TextView(requireContext());
        tvName.setText(name);
        tvName.setTextSize(13);
        tvName.setTextColor(0xFF222222);
        texts.addView(tvName);

        StringBuilder sub = new StringBuilder();
        if (!isDir && !sizeText.isEmpty()) sub.append(sizeText);
        if (mtime > 0) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append(new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    .format(new Date(mtime)));
        }
        if (sub.length() > 0) {
            TextView tvSub = new TextView(requireContext());
            tvSub.setText(sub.toString());
            tvSub.setTextSize(10);
            tvSub.setTextColor(0xFF999999);
            texts.addView(tvSub);
        }
        row.addView(texts);

        // 删除按钮（仅可写文件）
        if (writable) {
            TextView tvDel = new TextView(requireContext());
            tvDel.setText("删除");
            tvDel.setTextSize(11);
            tvDel.setTextColor(0xFFB3261E);
            tvDel.setPadding(dp(10), dp(6), dp(10), dp(6));

            GradientDrawable dbg = new GradientDrawable();
            dbg.setCornerRadius(dp(8));
            dbg.setColor(0x14FF3B30);
            tvDel.setBackground(dbg);

            tvDel.setOnClickListener(v -> confirmDelete(fullPath, name, isDir));
            row.addView(tvDel);
        }

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(0x06000000);
        row.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        row.setLayoutParams(lp);

        // 点击：目录进/文件下载
        row.setOnClickListener(v -> {
            if (isDir) {
                loadDir(currentRoot, fullPath);
            } else {
                downloadFile(fullPath, name);
            }
        });

        return row;
    }

    // ==================== 上传（B2）====================

    /** 打开系统文件选择器 */
    private void pickFile() {
        if (!currentWritable) {
            Toast.makeText(requireContext(),
                    "当前目录只读，无法上传", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_PICK_FILE);
            DebugLog.i("file picker opened");
        } catch (Throwable t) {
            DebugLog.e("pickFile failed", t);
            Toast.makeText(requireContext(), "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onActivityResult(int reqCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(reqCode, resultCode, data);
        if (reqCode != REQ_PICK_FILE) return;
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            DebugLog.i("file pick cancelled");
            return;
        }
        android.net.Uri uri = data.getData();
        if (uri == null) return;

        String name = "upload-" + System.currentTimeMillis();
        try {
            android.database.Cursor c = requireContext().getContentResolver()
                    .query(uri, null, null, null, null);
            if (c != null) {
                int idx2 = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx2 >= 0 && c.moveToFirst()) {
                    String n = c.getString(idx2);
                    if (n != null && !n.isEmpty()) name = n;
                }
                c.close();
            }
        } catch (Throwable t) {
            DebugLog.w("get filename failed: " + t);
        }

        final String filename = name;
        DebugLog.i("uploading: " + filename);
        tvStatus.setText("上传中：" + filename);
        Toast.makeText(requireContext(), "开始上传 " + filename, Toast.LENGTH_SHORT).show();

        final android.net.Uri finalUri = uri;
        pool.execute(() -> {
            try {
                String url = apiBase() + "/upload?root=" + currentRoot
                        + "&path=" + URLEncoder.encode(currentPath, "UTF-8")
                        + "&name=" + URLEncoder.encode(filename, "UTF-8");

                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(300000);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setChunkedStreamingMode(65536);   // 流式，未知长度时用
                conn.setRequestProperty("Content-Type", "application/octet-stream");

                java.io.InputStream in = requireContext().getContentResolver()
                        .openInputStream(finalUri);
                java.io.OutputStream out = conn.getOutputStream();
                byte[] buf = new byte[65536];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                }
                out.flush();
                out.close();
                in.close();

                int code = conn.getResponseCode();
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(
                                code >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                                "UTF-8"))) {
                    char[] cbuf = new char[4096];
                    int cn;
                    while ((cn = br.read(cbuf)) > 0) sb.append(cbuf, 0, cn);
                }
                conn.disconnect();

                final long bytes = total;
                final int httpCode = code;
                final String resp = sb.toString();
                DebugLog.i("upload done: " + httpCode + " " + bytes + " bytes");

                ui.post(() -> {
                    if (!isAdded()) return;
                    if (httpCode == 200) {
                        Toast.makeText(requireContext(),
                                "上传成功：" + (bytes / 1048576) + " MB",
                                Toast.LENGTH_SHORT).show();
                        refresh();
                    } else {
                        tvStatus.setText("上传失败：HTTP " + httpCode);
                        Toast.makeText(requireContext(),
                                "上传失败：" + resp, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Throwable t) {
                DebugLog.e("upload failed", t);
                ui.post(() -> {
                    if (!isAdded()) return;
                    tvStatus.setText("上传失败：" + t.getMessage());
                    Toast.makeText(requireContext(),
                            "上传失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }


    // ==================== 删除（二次确认）====================

    private void confirmDelete(String path, String name, boolean isDir) {
        // ★二次确认：必须点"确认删除"
        new AlertDialog.Builder(requireContext())
                .setTitle("确认删除")
                .setMessage("确定要删除" + (isDir ? "目录" : "文件") + "吗？\n\n"
                        + name + "\n\n"
                        + "⚠️ 此操作不可恢复")
                .setPositiveButton("确认删除", (d, w) -> doDelete(path))
                .setNegativeButton("取消", null)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show();
    }

    private void doDelete(String path) {
        tvStatus.setText("删除中…");
        pool.execute(() -> {
            try {
                // ★后端要求 confirm=YES
                String url = apiBase() + "/delete?root=" + currentRoot
                        + "&path=" + URLEncoder.encode(path, "UTF-8")
                        + "&confirm=YES";
                String json = httpPost(url);
                JSONObject o = new JSONObject(json);
                ui.post(() -> {
                    if (!isAdded()) return;
                    if (o.optBoolean("ok")) {
                        Toast.makeText(requireContext(),
                                "已删除 " + o.optString("deleted"), Toast.LENGTH_SHORT).show();
                        DebugLog.i("deleted " + path);
                        refresh();
                    } else {
                        tvStatus.setText("删除失败：" + o.optString("error"));
                        Toast.makeText(requireContext(),
                                "删除失败：" + o.optString("error"), Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Throwable t) {
                DebugLog.e("delete failed", t);
                ui.post(() -> {
                    if (isAdded()) {
                        tvStatus.setText("删除失败：" + t.getMessage());
                        Toast.makeText(requireContext(),
                                "删除失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void refresh() {
        loadDir(currentRoot, currentPath);
    }

    private void downloadFile(String path, String name) {
        Toast.makeText(requireContext(), "下载 " + name, Toast.LENGTH_SHORT).show();
        try {
            String url = apiBase() + "/download?root=" + currentRoot
                    + "&path=" + URLEncoder.encode(path, "UTF-8");
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
            startActivity(i);
        } catch (Throwable t) {
            DebugLog.e("download failed", t);
            Toast.makeText(requireContext(),
                    "无法下载：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 工具 ====================

    private String apiBase() {
        return "http://" + settings.getHost() + ":" + settings.getFilePort();
    }

    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                char[] buf = new char[8192];
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
            conn.setConnectTimeout(8000);
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
                char[] buf = new char[8192];
                int n;
                while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
            }
            if (code != 200) throw new Exception("HTTP " + code + ": " + sb);
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String iconFor(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".apk")) return "📦";
        if (n.endsWith(".img") || n.endsWith(".zip") || n.endsWith(".tar")
                || n.endsWith(".gz")) return "💾";
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".webp") || n.endsWith(".gif")) return "🖼";
        if (n.endsWith(".md") || n.endsWith(".txt")) return "📄";
        if (n.endsWith(".json") || n.endsWith(".js") || n.endsWith(".sh")) return "⚙";
        if (n.endsWith(".mp4") || n.endsWith(".mkv")) return "🎬";
        if (n.endsWith(".mp3") || n.endsWith(".flac")) return "🎵";
        return "📄";
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }
}
