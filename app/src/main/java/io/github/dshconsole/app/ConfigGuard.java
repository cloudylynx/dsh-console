package io.github.dshconsole.app;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 未配置服务器地址时的统一提示。
 *
 * 开源版不预置任何默认服务器，用户必须先在设置里填写地址。
 * 未填写时，各页面显示「请在设置中提供服务器地址」而不是发请求报错。
 */
public final class ConfigGuard {

    private ConfigGuard() {}

    /**
     * 在页面根节点上覆盖一个「未配置」提示层。
     *
     * @param root      页面根布局
     * @param configured 是否已配置服务器地址
     * @return true 表示已显示提示（调用方应跳过数据加载）
     */
    public static boolean showIfUnconfigured(View root, boolean configured) {
        if (configured) {
            hide(root);
            return false;
        }
        show(root);
        return true;
    }

    /** 显示提示层 */
    public static void show(View root) {
        if (root == null) return;
        ViewGroup parent = (ViewGroup) root.getParent();
        if (parent == null) return;

        // 已有则不重复创建
        if (parent.findViewWithTag(TAG) != null) {
            parent.findViewWithTag(TAG).setVisibility(View.VISIBLE);
            return;
        }

        LinearLayout box = new LinearLayout(root.getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(48, 48, 48, 48);
        box.setBackgroundColor(0x00000000);
        box.setTag(TAG);

        TextView icon = new TextView(root.getContext());
        icon.setText("⚙");
        icon.setTextSize(40);
        icon.setGravity(Gravity.CENTER);
        box.addView(icon);

        TextView msg = new TextView(root.getContext());
        msg.setText("请在设置中提供服务器地址");
        msg.setTextSize(16);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(0, 24, 0, 0);
        box.addView(msg);

        TextView hint = new TextView(root.getContext());
        hint.setText("设置 → 服务器 → 填写地址与端口");
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        hint.setTextColor(0xFF888888);
        hint.setPadding(0, 12, 0, 0);
        box.addView(hint);

        TextView btn = new TextView(root.getContext());
        btn.setText("去设置");
        btn.setTextSize(14);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(32, 12, 32, 12);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(24);
        bg.setColor(0xFF7AA2F7);
        btn.setBackground(bg);
        btn.setTextColor(0xFFFFFFFF);
        btn.setOnClickListener(v -> {
            try {
                if (v.getContext() instanceof MainActivity) {
                    ((MainActivity) v.getContext()).openSettings();
                }
            } catch (Throwable ignored) {}
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = 28;
        box.addView(btn, blp);

        parent.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** 隐藏提示层 */
    public static void hide(View root) {
        if (root == null) return;
        ViewGroup parent = (ViewGroup) root.getParent();
        if (parent == null) return;
        View v = parent.findViewWithTag(TAG);
        if (v != null) v.setVisibility(View.GONE);
    }

    private static final String TAG = "dsh_notconfigured_overlay";
}
