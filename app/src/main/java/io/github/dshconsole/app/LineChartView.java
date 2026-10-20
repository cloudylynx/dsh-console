package io.github.dshconsole.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 轻量折线图（纯 Canvas，无第三方库）
 *
 * 支持：
 *   · 单曲线 / 双曲线（如磁盘 IO 的读+写）
 *   · 渐变填充
 *   · 自动或固定 Y 轴
 *   · 可选顶部数值标签
 */
public class LineChartView extends View {

    private final Paint pGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDivider = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path1 = new Path();
    private final Path path2 = new Path();
    private final Path fill1 = new Path();
    private final Path fill2 = new Path();

    // 曲线 1（主）
    private final float[] data1 = new float[240];
    private int count1 = 0;
    private int color1 = 0xFF0A84FF;

    // 曲线 2（副，可选）
    private boolean dual = false;
    private final float[] data2 = new float[240];
    private int count2 = 0;
    private int color2 = 0xFFFF3B30;

    private float maxY = 100f;
    private boolean autoScale = false;
    private int capacity = 60;
    private String unit = "%";
    private boolean showLabels = false;

    /** 数值格式化回调（默认按 unit 拼接） */
    public interface ValueFormatter {
        String format(float v);
    }

    private ValueFormatter formatter = null;

    public LineChartView(Context c) { this(c, null); }

    public LineChartView(Context c, AttributeSet a) {
        super(c, a);
        float d = c.getResources().getDisplayMetrics().density;
        pGrid.setColor(0x12757575);
        pGrid.setStrokeWidth(1f * d);
        pLine.setStyle(Paint.Style.STROKE);
        pLine.setStrokeWidth(1.8f * d);
        pLine.setStrokeCap(Paint.Cap.ROUND);
        pLine.setStrokeJoin(Paint.Join.ROUND);
        pFill.setStyle(Paint.Style.FILL);
        pText.setTextSize(8.5f * d);
        pDot.setStyle(Paint.Style.FILL);
        pDivider.setColor(0x20757575);
        pDivider.setStrokeWidth(1f * d);
    }

    public void setColor(int c) {
        this.color1 = c;
        invalidate();
    }

    /** 开启双曲线：第一条为主色，第二条为 subColor */
    public void setDual(boolean b, int subColor) {
        this.dual = b;
        this.color2 = subColor;
        invalidate();
    }

    public void setUnit(String u) { this.unit = u; }

    public void setFormatter(ValueFormatter f) { this.formatter = f; }

    public void setFixedMax(float max) {
        this.autoScale = false;
        this.maxY = Math.max(1f, max);
        invalidate();
    }

    public void setAutoScale(boolean b) {
        this.autoScale = b;
        invalidate();
    }

    public void setCapacity(int n) {
        capacity = Math.max(10, Math.min(data1.length, n));
        invalidate();
    }

    public void setShowLabels(boolean b) { showLabels = b; }

    public void clear() {
        count1 = 0;
        count2 = 0;
        invalidate();
    }

    /** 追加一个点（单曲线） */
    public void add(float v) {
        if (count1 >= capacity) {
            System.arraycopy(data1, 1, data1, 0, capacity - 1);
            data1[capacity - 1] = v;
        } else {
            data1[count1++] = v;
        }
        invalidate();
    }

    /** 追加一对点（双曲线） */
    public void add2(float v1, float v2) {
        if (count1 >= capacity) {
            System.arraycopy(data1, 1, data1, 0, capacity - 1);
            data1[capacity - 1] = v1;
        } else {
            data1[count1++] = v1;
        }
        if (count2 >= capacity) {
            System.arraycopy(data2, 1, data2, 0, capacity - 1);
            data2[capacity - 1] = v2;
        } else {
            data2[count2++] = v2;
        }
        invalidate();
    }

    public float last1() { return count1 > 0 ? data1[count1 - 1] : 0f; }
    public float last2() { return count2 > 0 ? data2[count2 - 1] : 0f; }

    private float resolveMax() {
        if (!autoScale) return Math.max(1f, maxY);
        float m = 0f;
        for (int i = 0; i < count1; i++) if (data1[i] > m) m = data1[i];
        if (dual) {
            for (int i = 0; i < count2; i++) if (data2[i] > m) m = data2[i];
        }
        m *= 1.2f;
        if (m < 1f) m = 1f;
        float exp = (float) Math.pow(10, Math.floor(Math.log10(m)));
        float norm = m / exp;
        float nice;
        if (norm <= 1f) nice = 1f;
        else if (norm <= 2f) nice = 2f;
        else if (norm <= 5f) nice = 5f;
        else nice = 10f;
        return nice * exp;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float d = getResources().getDisplayMetrics().density;
        float padT = 4f * d;
        float padB = 2f * d;
        float chartH = h - padT - padB;
        float my = resolveMax();

        // 网格
        for (int i = 0; i <= 2; i++) {
            float y = padT + chartH * i / 2f;
            canvas.drawLine(0, y, w, y, pGrid);
        }

        // 曲线 1
        drawSeries(canvas, data1, count1, color1, path1, fill1,
                w, padT, chartH, my, d);
        // 曲线 2
        if (dual) {
            drawSeries(canvas, data2, count2, color2, path2, fill2,
                    w, padT, chartH, my, d);
        }

        // 顶部数值
        if (showLabels) {
            String l1 = fmt(last1()) + unit;
            pText.setTextAlign(Paint.Align.RIGHT);
            pText.setColor((color1 & 0x00FFFFFF) | 0xDD000000);
            canvas.drawText(l1, w - 2f * d, padT + 9f * d, pText);

            if (dual) {
                String l2 = fmt(last2()) + unit;
                pText.setTextAlign(Paint.Align.LEFT);
                pText.setColor((color2 & 0x00FFFFFF) | 0xDD000000);
                canvas.drawText(l2, 2f * d, padT + 9f * d, pText);
            }
        }
    }

    private void drawSeries(Canvas canvas, float[] data, int count,
                            int color, Path line, Path fill,
                            int w, float padT, float chartH, float my, float d) {
        if (count < 2) {
            float y = padT + chartH;
            pLine.setColor((color & 0x00FFFFFF) | 0x22000000);
            canvas.drawLine(0, y, w, y, pLine);
            return;
        }

        int show = Math.min(count, capacity);
        float stepX = w / Math.max(1, capacity - 1);

        line.reset();
        fill.reset();

        for (int i = 0; i < show; i++) {
            float v = data[i];
            if (Float.isNaN(v) || Float.isInfinite(v)) v = 0;
            float x = stepX * i;
            float y = padT + chartH - (v / my) * chartH;
            if (y < padT) y = padT;
            if (y > padT + chartH) y = padT + chartH;

            if (i == 0) {
                line.moveTo(x, y);
                fill.moveTo(x, padT + chartH);
                fill.lineTo(x, y);
            } else {
                line.lineTo(x, y);
                fill.lineTo(x, y);
            }
        }
        fill.lineTo(stepX * (show - 1), padT + chartH);
        fill.close();

        pFill.setShader(new LinearGradient(0, padT, 0, padT + chartH,
                (color & 0x00FFFFFF) | 0x38000000,
                (color & 0x00FFFFFF) | 0x01000000,
                Shader.TileMode.CLAMP));
        canvas.drawPath(fill, pFill);
        pFill.setShader(null);

        pLine.setColor(color);
        canvas.drawPath(line, pLine);

        // 末端圆点
        float lastV = data[show - 1];
        if (Float.isNaN(lastV) || Float.isInfinite(lastV)) lastV = 0;
        float ly = padT + chartH - (lastV / my) * chartH;
        if (ly < padT) ly = padT;
        if (ly > padT + chartH) ly = padT + chartH;
        pDot.setColor(color);
        canvas.drawCircle(stepX * (show - 1), ly, 2.6f * d, pDot);
    }

    private String fmt(float v) {
        if (formatter != null) return formatter.format(v);
        if (autoScale) {
            if (v >= 1024) return String.format("%.1f", v / 1024f);
            return String.format("%.0f", v);
        }
        return String.valueOf(Math.round(v));
    }
}
