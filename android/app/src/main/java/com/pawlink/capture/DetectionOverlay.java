package com.pawlink.capture;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class DetectionOverlay extends View {
    private static final class DrawBox {
        final RectF bounds;
        final String label;
        final int color;

        DrawBox(RectF bounds, String label, int color) {
            this.bounds = bounds;
            this.label = label;
            this.color = color;
        }
    }

    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<DrawBox> drawBoxes = new ArrayList<>();

    DetectionOverlay(Context context) {
        super(context);
        setWillNotDraw(false);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(dp(2.5f));
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(14f));
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        labelPaint.setStyle(Paint.Style.FILL);
    }

    void setDetections(List<VisionAnalyzer.DetectionBox> detections,
                       int imageWidth, int imageHeight) {
        if (imageWidth <= 0 || imageHeight <= 0 || detections.isEmpty()) {
            drawBoxes = new ArrayList<>();
            invalidate();
            return;
        }
        float scale = Math.max(getWidth() / (float) imageWidth,
                getHeight() / (float) imageHeight);
        float offsetX = (getWidth() - imageWidth * scale) / 2f;
        float offsetY = (getHeight() - imageHeight * scale) / 2f;
        List<DrawBox> next = new ArrayList<>(detections.size());
        for (VisionAnalyzer.DetectionBox detection : detections) {
            RectF source = detection.bounds;
            RectF mapped = new RectF(
                    source.left * scale + offsetX,
                    source.top * scale + offsetY,
                    source.right * scale + offsetX,
                    source.bottom * scale + offsetY);
            int color = colorFor(detection.category);
            String label = String.format(Locale.US, "%s %.0f%%",
                    translated(detection.category), detection.score * 100f);
            next.add(new DrawBox(mapped, label, color));
        }
        drawBoxes = next;
        invalidate();
    }

    void clear() {
        drawBoxes = new ArrayList<>();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float pad = dp(5f);
        for (DrawBox box : drawBoxes) {
            boxPaint.setColor(box.color);
            canvas.drawRoundRect(box.bounds, dp(5f), dp(5f), boxPaint);

            float textWidth = textPaint.measureText(box.label);
            Paint.FontMetrics metrics = textPaint.getFontMetrics();
            float labelHeight = metrics.descent - metrics.ascent + pad * 2f;
            float left = Math.max(0f, Math.min(box.bounds.left, getWidth() - textWidth - pad * 2f));
            float top = box.bounds.top - labelHeight;
            if (top < 0f) {
                top = box.bounds.top >= 0f
                        ? box.bounds.top
                        : Math.min(getHeight() - labelHeight,
                        Math.max(0f, box.bounds.bottom - labelHeight));
            }
            RectF background = new RectF(left, top, left + textWidth + pad * 2f,
                    top + labelHeight);
            labelPaint.setColor(Color.argb(220, Color.red(box.color),
                    Color.green(box.color), Color.blue(box.color)));
            canvas.drawRoundRect(background, dp(4f), dp(4f), labelPaint);
            canvas.drawText(box.label, left + pad, top + pad - metrics.ascent, textPaint);
        }
    }

    private int colorFor(String category) {
        if ("cat".equalsIgnoreCase(category)) return Color.rgb(51, 214, 114);
        if ("person".equalsIgnoreCase(category)) return Color.rgb(64, 156, 255);
        if ("dog".equalsIgnoreCase(category)) return Color.rgb(197, 112, 255);
        return Color.rgb(255, 174, 66);
    }

    private String translated(String category) {
        if ("cat".equalsIgnoreCase(category)) return "猫";
        if ("person".equalsIgnoreCase(category)) return "人";
        if ("dog".equalsIgnoreCase(category)) return "狗";
        return category;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
