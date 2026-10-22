package com.stlslicer.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/** 2D interactive placement preview for a 220x220 mm printer bed. */
public class BedPlacementView extends View {

    public interface OnPositionChangedListener {
        void onPositionChanged(double x, double y);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private double bedWidth = 220.0;
    private double bedDepth = 220.0;
    private double modelWidth = 0.0;
    private double modelDepth = 0.0;
    private double centerX = 110.0;
    private double centerY = 110.0;
    private OnPositionChangedListener listener;

    public BedPlacementView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(18, 21, 28));
    }

    public void setBedSize(double width, double depth) {
        bedWidth = Math.max(1, width);
        bedDepth = Math.max(1, depth);
        centerX = clampX(centerX);
        centerY = clampY(centerY);
        invalidate();
    }

    public void setModelSize(double width, double depth) {
        modelWidth = Math.max(0, width);
        modelDepth = Math.max(0, depth);
        centerX = clampX(centerX);
        centerY = clampY(centerY);
        invalidate();
    }

    public void setCenter(double x, double y) {
        centerX = clampX(x);
        centerY = clampY(y);
        invalidate();
    }

    public double getCenterXmm() { return centerX; }
    public double getCenterYmm() { return centerY; }

    public void setOnPositionChangedListener(OnPositionChangedListener l) {
        listener = l;
    }

    private double clampX(double x) {
        double half = Math.min(modelWidth / 2.0, bedWidth / 2.0);
        return Math.max(half, Math.min(bedWidth - half, x));
    }

    private double clampY(double y) {
        double half = Math.min(modelDepth / 2.0, bedDepth / 2.0);
        return Math.max(half, Math.min(bedDepth - half, y));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float padding = 34f;
        float usableW = Math.max(1f, getWidth() - padding * 2f);
        float usableH = Math.max(1f, getHeight() - padding * 2f);
        float scale = (float)Math.min(usableW / bedWidth, usableH / bedDepth);
        float left = (getWidth() - (float)(bedWidth * scale)) / 2f;
        float top = (getHeight() - (float)(bedDepth * scale)) / 2f;
        float right = left + (float)(bedWidth * scale);
        float bottom = top + (float)(bedDepth * scale);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(32, 36, 44));
        canvas.drawRect(left, top, right, bottom, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(Color.rgb(76, 82, 92));
        for (int mm = 20; mm < 220; mm += 20) {
            float x = left + mm * scale;
            float y = bottom - mm * scale;
            canvas.drawLine(x, top, x, bottom, paint);
            canvas.drawLine(left, y, right, y, paint);
        }

        paint.setStrokeWidth(3f);
        paint.setColor(Color.rgb(190, 195, 205));
        canvas.drawRect(left, top, right, bottom, paint);

        if (modelWidth > 0 && modelDepth > 0) {
            float cx = left + (float)(centerX * scale);
            float cy = bottom - (float)(centerY * scale);
            float halfW = (float)(modelWidth * scale / 2.0);
            float halfD = (float)(modelDepth * scale / 2.0);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(125, 0, 200, 190));
            canvas.drawRect(cx - halfW, cy - halfD, cx + halfW, cy + halfD, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4f);
            paint.setColor(Color.rgb(0, 230, 210));
            canvas.drawRect(cx - halfW, cy - halfD, cx + halfW, cy + halfD, paint);

            paint.setStrokeWidth(2f);
            canvas.drawLine(cx - 12, cy, cx + 12, cy, paint);
            canvas.drawLine(cx, cy - 12, cx, cy + 12, paint);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(28f);
        paint.setColor(Color.WHITE);
        canvas.drawText("220 мм", left + 6, top + 30, paint);
        String pos = String.format(Locale.US, "X %.1f   Y %.1f мм", centerX, centerY);
        canvas.drawText(pos, left + 6, bottom - 10, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN &&
                event.getActionMasked() != MotionEvent.ACTION_MOVE) {
            return true;
        }

        float padding = 34f;
        float usableW = Math.max(1f, getWidth() - padding * 2f);
        float usableH = Math.max(1f, getHeight() - padding * 2f);
        float scale = (float)Math.min(usableW / bedWidth, usableH / bedDepth);
        float left = (getWidth() - (float)(bedWidth * scale)) / 2f;
        float top = (getHeight() - (float)(bedDepth * scale)) / 2f;
        float bottom = top + (float)(bedDepth * scale);

        double x = (event.getX() - left) / scale;
        double y = (bottom - event.getY()) / scale;
        centerX = clampX(x);
        centerY = clampY(y);
        invalidate();
        if (listener != null) listener.onPositionChanged(centerX, centerY);
        return true;
    }
}
