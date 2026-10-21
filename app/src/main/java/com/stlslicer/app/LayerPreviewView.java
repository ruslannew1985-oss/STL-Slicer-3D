package com.stlslicer.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LayerPreviewView extends View {
    public static final class Segment {
        final float x1, y1, x2, y2;
        Segment(float x1, float y1, float x2, float y2) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
        }
    }

    private final Paint printPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<List<Segment>> layers = new ArrayList<>();
    private int currentLayer = 0;
    private float minX = 0, minY = 0, maxX = 220, maxY = 220;

    private static final Pattern X = Pattern.compile("(?:^|\\s)X(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern Y = Pattern.compile("(?:^|\\s)Y(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern E = Pattern.compile("(?:^|\\s)E(-?\\d+(?:\\.\\d+)?)");

    public LayerPreviewView(Context context) {
        super(context);
        printPaint.setColor(Color.rgb(0, 120, 255));
        printPaint.setStrokeWidth(3f);
        printPaint.setStyle(Paint.Style.STROKE);
        printPaint.setStrokeCap(Paint.Cap.ROUND);

        bedPaint.setColor(Color.rgb(40, 40, 45));
        bedPaint.setStyle(Paint.Style.FILL);

        gridPaint.setColor(Color.rgb(80, 80, 85));
        gridPaint.setStrokeWidth(1f);
    }

    public int loadGcode(File file) throws IOException {
        layers.clear();
        currentLayer = 0;
        minX = Float.POSITIVE_INFINITY;
        minY = Float.POSITIVE_INFINITY;
        maxX = Float.NEGATIVE_INFINITY;
        maxY = Float.NEGATIVE_INFINITY;

        List<Segment> current = null;
        float lastX = 0f, lastY = 0f;
        double lastE = 0.0;
        boolean haveXY = false;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), java.nio.charset.StandardCharsets.US_ASCII), 64 * 1024)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(";LAYER:")) {
                    current = new ArrayList<>();
                    layers.add(current);
                    haveXY = false;
                    continue;
                }
                if (current == null || !(line.startsWith("G0") || line.startsWith("G1"))) continue;

                Float x = findFloat(X, line);
                Float y = findFloat(Y, line);
                Double e = findDouble(E, line);
                float nextX = x != null ? x : lastX;
                float nextY = y != null ? y : lastY;

                boolean extruding = e != null && e > lastE + 1e-7;
                if (haveXY && extruding && (x != null || y != null)) {
                    Segment s = new Segment(lastX, lastY, nextX, nextY);
                    current.add(s);
                    include(s.x1, s.y1);
                    include(s.x2, s.y2);
                }

                if (x != null || y != null) haveXY = true;
                if (x != null) lastX = x;
                if (y != null) lastY = y;
                if (e != null) lastE = e;
            }
        }

        if (!Float.isFinite(minX) || !Float.isFinite(maxX)) {
            minX = 0; minY = 0; maxX = 220; maxY = 220;
        }
        postInvalidate();
        return layers.size();
    }

    public int getLayerCount() { return layers.size(); }

    public void setLayer(int layer) {
        if (layers.isEmpty()) return;
        currentLayer = Math.max(0, Math.min(layer, layers.size() - 1));
        invalidate();
    }

    public int getCurrentLayer() { return currentLayer; }

    private void include(float x, float y) {
        minX = Math.min(minX, x); maxX = Math.max(maxX, x);
        minY = Math.min(minY, y); maxY = Math.max(maxY, y);
    }

    private static Float findFloat(Pattern p, String line) {
        Matcher m = p.matcher(line);
        if (!m.find()) return null;
        try { return Float.parseFloat(m.group(1)); }
        catch (NumberFormatException ex) { return null; }
    }

    private static Double findDouble(Pattern p, String line) {
        Matcher m = p.matcher(line);
        if (!m.find()) return null;
        try { return Double.parseDouble(m.group(1)); }
        catch (NumberFormatException ex) { return null; }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        canvas.drawColor(Color.rgb(25, 25, 28));

        float pad = 28f;
        float modelW = Math.max(1f, maxX - minX);
        float modelH = Math.max(1f, maxY - minY);
        float scale = Math.min((w - pad * 2f) / modelW, (h - pad * 2f) / modelH);
        float drawW = modelW * scale;
        float drawH = modelH * scale;
        float ox = (w - drawW) / 2f;
        float oy = (h - drawH) / 2f;

        for (int i = 0; i <= 10; i++) {
            float gx = ox + drawW * i / 10f;
            float gy = oy + drawH * i / 10f;
            canvas.drawLine(gx, oy, gx, oy + drawH, gridPaint);
            canvas.drawLine(ox, gy, ox + drawW, gy, gridPaint);
        }

        if (layers.isEmpty() || currentLayer >= layers.size()) return;
        for (Segment s : layers.get(currentLayer)) {
            float x1 = ox + (s.x1 - minX) * scale;
            float y1 = oy + drawH - (s.y1 - minY) * scale;
            float x2 = ox + (s.x2 - minX) * scale;
            float y2 = oy + drawH - (s.y2 - minY) * scale;
            canvas.drawLine(x1, y1, x2, y2, printPaint);
        }
    }
}
