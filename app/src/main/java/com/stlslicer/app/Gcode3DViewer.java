package com.stlslicer.app;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.opengl.GLU;
import android.view.MotionEvent;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class Gcode3DViewer extends GLSurfaceView {

    private final ToolpathRenderer renderer;
    private float lastX, lastY;
    private float pinchDistance = -1f;

    public Gcode3DViewer(Context context) {
        super(context);
        setEGLContextClientVersion(1);
        renderer = new ToolpathRenderer();
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    public int loadGcode(File file) throws IOException {
        List<float[]> layers = parseGcode(file);
        if (layers.isEmpty()) throw new IOException("В G-code не найдено печатных траекторий");
        renderer.setLayers(layers);
        renderer.setLayer(layers.size() - 1);
        requestRender();
        return layers.size();
    }

    public int getLayerCount() {
        return renderer.getLayerCount();
    }

    public void setLayer(int layer) {
        renderer.setLayer(layer);
        requestRender();
    }

    public void resetView() {
        renderer.resetView();
        requestRender();
    }

    private List<float[]> parseGcode(File file) throws IOException {
        List<List<Float>> rawLayers = new ArrayList<>();
        int currentLayer = -1;

        double x = 0, y = 0, z = 0, e = 0;
        boolean absoluteXYZ = true;
        boolean absoluteE = true;

        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.US_ASCII), 64 * 1024)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith(";LAYER:")) {
                    try {
                        currentLayer = Integer.parseInt(line.substring(7).trim());
                    } catch (Exception ignored) {
                        currentLayer++;
                    }
                    while (rawLayers.size() <= currentLayer) rawLayers.add(new ArrayList<>());
                    continue;
                }

                if (line.startsWith("G90")) { absoluteXYZ = true; continue; }
                if (line.startsWith("G91")) { absoluteXYZ = false; continue; }
                if (line.startsWith("M82")) { absoluteE = true; continue; }
                if (line.startsWith("M83")) { absoluteE = false; continue; }
                if (line.startsWith("G92")) {
                    Double ne = valueOf(line, 'E');
                    if (ne != null) e = ne;
                    continue;
                }

                if (!(line.startsWith("G0") || line.startsWith("G1"))) continue;

                Double vx = valueOf(line, 'X');
                Double vy = valueOf(line, 'Y');
                Double vz = valueOf(line, 'Z');
                Double ve = valueOf(line, 'E');

                double nx = vx == null ? x : (absoluteXYZ ? vx : x + vx);
                double ny = vy == null ? y : (absoluteXYZ ? vy : y + vy);
                double nz = vz == null ? z : (absoluteXYZ ? vz : z + vz);
                double ne = ve == null ? e : (absoluteE ? ve : e + ve);

                boolean extruding = ve != null && ne > e + 1e-7;
                if (extruding && currentLayer >= 0 && (Math.abs(nx - x) > 1e-7 || Math.abs(ny - y) > 1e-7)) {
                    while (rawLayers.size() <= currentLayer) rawLayers.add(new ArrayList<>());
                    List<Float> layer = rawLayers.get(currentLayer);
                    add(layer, (float)x, (float)y, (float)z);
                    add(layer, (float)nx, (float)ny, (float)nz);
                }

                x = nx; y = ny; z = nz; e = ne;
            }
        }

        List<float[]> layers = new ArrayList<>();
        for (List<Float> src : rawLayers) {
            float[] a = new float[src.size()];
            for (int i = 0; i < a.length; i++) a[i] = src.get(i);
            layers.add(a);
        }
        return layers;
    }

    private static void add(List<Float> list, float x, float y, float z) {
        list.add(x); list.add(y); list.add(z);
    }

    private static Double valueOf(String line, char key) {
        int comment = line.indexOf(';');
        if (comment >= 0) line = line.substring(0, comment);
        for (int i = 0; i < line.length(); i++) {
            if (Character.toUpperCase(line.charAt(i)) == key) {
                int j = i + 1;
                while (j < line.length() && Character.isWhitespace(line.charAt(j))) j++;
                int k = j;
                while (k < line.length()) {
                    char c = line.charAt(k);
                    if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') k++;
                    else break;
                }
                if (k > j) {
                    try { return Double.parseDouble(line.substring(j, k)); }
                    catch (Exception ignored) { return null; }
                }
            }
        }
        return null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getPointerCount() >= 2) {
            float dx = event.getX(0) - event.getX(1);
            float dy = event.getY(0) - event.getY(1);
            float d = (float)Math.hypot(dx, dy);
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE && pinchDistance > 0) {
                renderer.zoom(d / pinchDistance);
                requestRender();
            }
            pinchDistance = d;
            return true;
        }

        pinchDistance = -1f;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            lastX = event.getX();
            lastY = event.getY();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            renderer.rotate(event.getX() - lastX, event.getY() - lastY);
            lastX = event.getX();
            lastY = event.getY();
            requestRender();
        }
        return true;
    }

    private static class ToolpathRenderer implements Renderer {
        private List<float[]> layers = new ArrayList<>();
        private int selectedLayer = 0;
        private FloatBuffer lowerBuffer;
        private FloatBuffer currentBuffer;
        private int lowerVertices = 0;
        private int currentVertices = 0;

        private float centerX, centerY, centerZ, modelScale = 1f;
        private float rotX = 58f, rotY = -38f;
        private float zoom = 1f;

        synchronized int getLayerCount() { return layers.size(); }

        synchronized void resetView() {
            rotX = 58f;
            rotY = -38f;
            zoom = 1f;
        }

        synchronized void rotate(float dx, float dy) {
            rotY += dx * 0.65f;
            rotX += dy * 0.65f;
            rotX = Math.max(-180f, Math.min(180f, rotX));
        }

        synchronized void zoom(float ratio) {
            if (ratio > 0.1f && ratio < 10f)
                zoom = Math.max(0.2f, Math.min(6f, zoom * ratio));
        }

        synchronized void setLayers(List<float[]> newLayers) {
            layers = newLayers;
            selectedLayer = Math.max(0, layers.size() - 1);
            computeBounds();
            rebuildBuffers();
        }

        synchronized void setLayer(int layer) {
            if (layers.isEmpty()) return;
            selectedLayer = Math.max(0, Math.min(layers.size() - 1, layer));
            rebuildBuffers();
        }

        private void computeBounds() {
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
            for (float[] layer : layers) {
                for (int i = 0; i + 2 < layer.length; i += 3) {
                    minX = Math.min(minX, layer[i]); maxX = Math.max(maxX, layer[i]);
                    minY = Math.min(minY, layer[i + 1]); maxY = Math.max(maxY, layer[i + 1]);
                    minZ = Math.min(minZ, layer[i + 2]); maxZ = Math.max(maxZ, layer[i + 2]);
                }
            }
            if (!Float.isFinite(minX)) { minX = minY = minZ = -1; maxX = maxY = maxZ = 1; }
            centerX = (minX + maxX) * 0.5f;
            centerY = (minY + maxY) * 0.5f;
            centerZ = (minZ + maxZ) * 0.5f;
            float size = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
            modelScale = size <= 0 ? 1f : 1.8f / size;
        }

        private void rebuildBuffers() {
            int lowerFloats = 0;
            for (int i = 0; i < selectedLayer; i++) lowerFloats += layers.get(i).length;
            float[] lower = new float[lowerFloats];
            int pos = 0;
            for (int i = 0; i < selectedLayer; i++) {
                float[] src = layers.get(i);
                System.arraycopy(src, 0, lower, pos, src.length);
                pos += src.length;
            }
            float[] current = layers.isEmpty() ? new float[0] : layers.get(selectedLayer);
            lowerBuffer = toBuffer(lower);
            currentBuffer = toBuffer(current);
            lowerVertices = lower.length / 3;
            currentVertices = current.length / 3;
        }

        private FloatBuffer toBuffer(float[] data) {
            ByteBuffer bb = ByteBuffer.allocateDirect(Math.max(1, data.length) * 4).order(ByteOrder.nativeOrder());
            FloatBuffer fb = bb.asFloatBuffer();
            if (data.length > 0) fb.put(data);
            fb.position(0);
            return fb;
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            gl.glClearColor(0.07f, 0.08f, 0.11f, 1f);
            gl.glEnable(GL10.GL_DEPTH_TEST);
            gl.glDisable(GL10.GL_LIGHTING);
            gl.glDisable(GL10.GL_CULL_FACE);
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int w, int h) {
            gl.glViewport(0, 0, w, h);
            gl.glMatrixMode(GL10.GL_PROJECTION);
            gl.glLoadIdentity();
            GLU.gluPerspective(gl, 45f, (float)w / Math.max(1, h), 0.1f, 100f);
            gl.glMatrixMode(GL10.GL_MODELVIEW);
        }

        @Override
        public synchronized void onDrawFrame(GL10 gl) {
            gl.glClear(GL10.GL_COLOR_BUFFER_BIT | GL10.GL_DEPTH_BUFFER_BIT);
            gl.glLoadIdentity();
            gl.glTranslatef(0f, 0f, -4f);
            gl.glScalef(zoom, zoom, zoom);
            gl.glRotatef(rotX, 1, 0, 0);
            gl.glRotatef(rotY, 0, 1, 0);
            gl.glScalef(modelScale, modelScale, modelScale);
            gl.glTranslatef(-centerX, -centerY, -centerZ);

            gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);

            if (lowerVertices > 0) {
                lowerBuffer.position(0);
                gl.glVertexPointer(3, GL10.GL_FLOAT, 0, lowerBuffer);
                gl.glColor4f(0.10f, 0.55f, 0.95f, 0.65f);
                gl.glLineWidth(1.5f);
                gl.glDrawArrays(GL10.GL_LINES, 0, lowerVertices);
            }

            if (currentVertices > 0) {
                currentBuffer.position(0);
                gl.glVertexPointer(3, GL10.GL_FLOAT, 0, currentBuffer);
                gl.glColor4f(0.10f, 0.95f, 0.78f, 1f);
                gl.glLineWidth(3.0f);
                gl.glDrawArrays(GL10.GL_LINES, 0, currentVertices);
            }

            gl.glDisableClientState(GL10.GL_VERTEX_ARRAY);
        }
    }
}
