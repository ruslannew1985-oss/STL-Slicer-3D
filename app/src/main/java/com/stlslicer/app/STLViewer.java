
package com.stlslicer.app;

import android.content.Context;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.opengl.GLU;
import android.opengl.GLES10;
import android.view.MotionEvent;

import java.io.*;
import java.nio.*;
import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class STLViewer extends GLSurfaceView {
    private final ModelRenderer renderer;
    private float lastX, lastY;
    private float distance = 3f;

    public STLViewer(Context context) {
        super(context);
        setEGLContextClientVersion(1);
        renderer = new ModelRenderer();
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    public void loadSTL(Uri uri) throws IOException {
        byte[] bytes;
        try (InputStream in = getContext()
                .getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Не удалось открыть STL");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                if (out.size() > 50_000_000)
                    throw new IOException("STL больше 50 МБ");
            }
            bytes = out.toByteArray();
        }

        float[] triangles = parseSTL(bytes);
        if (triangles.length == 0)
            throw new IOException("В STL нет треугольников");

        renderer.setMesh(triangles);
        requestRender();
    }

    private float[] parseSTL(byte[] bytes) throws IOException {
        if (bytes.length >= 84) {
            ByteBuffer b = ByteBuffer.wrap(bytes)
                    .order(ByteOrder.LITTLE_ENDIAN);
            long count = Integer.toUnsignedLong(b.getInt(80));
            if (count > 0 && 84L + count * 50L == bytes.length) {
                if (count > 300000)
                    throw new IOException("Слишком сложная модель");
                float[] v = new float[(int)count * 9];
                b.position(84);
                for (int i = 0; i < count; i++) {
                    b.position(b.position() + 12);
                    for (int j = 0; j < 9; j++)
                        v[i * 9 + j] = b.getFloat();
                    b.position(b.position() + 2);
                }
                return v;
            }
        }

        String text = new String(bytes,
                java.nio.charset.StandardCharsets.US_ASCII);
        List<Float> list = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^\\s*vertex\\s+([-+\\d.eE]+)\\s+([-+\\d.eE]+)\\s+([-+\\d.eE]+)")
                .matcher(text);
        while (m.find()) {
            try {
                for (int j = 1; j <= 3; j++)
                    list.add(Float.parseFloat(m.group(j)));
            } catch (NumberFormatException e) {
                throw new IOException("Неверные координаты STL", e);
            }
            if (list.size() > 2700000)
                throw new IOException("Слишком сложная модель");
        }
        if (list.size() % 9 != 0)
            throw new IOException("Неполный треугольник STL");
        float[] result = new float[list.size()];
        for (int i = 0; i < result.length; i++)
            result[i] = list.get(i);
        return result;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getPointerCount() >= 2) {
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx = event.getX(0) - event.getX(1);
                float dy = event.getY(0) - event.getY(1);
                float d = (float)Math.hypot(dx, dy);
                if (distance > 0)
                    renderer.zoom(d / distance);
                distance = d;
                requestRender();
            } else {
                distance = -1;
            }
            return true;
        }

        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            lastX = event.getX();
            lastY = event.getY();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            renderer.rotate(event.getX() - lastX,
                    event.getY() - lastY);
            lastX = event.getX();
            lastY = event.getY();
            requestRender();
        }
        return true;
    }

    private static class ModelRenderer implements Renderer {
        private FloatBuffer vertices;
        private FloatBuffer normals;
        private int vertexCount;
        private float rotX = 25f, rotY = -35f;
        private float zoom = 1f;

        synchronized void setMesh(float[] data) throws IOException {
            float minX = Float.POSITIVE_INFINITY;
            float minY = minX, minZ = minX;
            float maxX = Float.NEGATIVE_INFINITY;
            float maxY = maxX, maxZ = maxX;
            for (int i = 0; i < data.length; i += 3) {
                float x = data[i], y = data[i+1], z = data[i+2];
                if (!Float.isFinite(x) || !Float.isFinite(y)
                        || !Float.isFinite(z))
                    throw new IOException("Неверная геометрия STL");
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            }
            float cx = (minX + maxX) / 2f;
            float cy = (minY + maxY) / 2f;
            float cz = (minZ + maxZ) / 2f;
            float size = Math.max(maxX-minX,
                    Math.max(maxY-minY, maxZ-minZ));
            if (size <= 0) size = 1;
            for (int i = 0; i < data.length; i += 3) {
                data[i] = (data[i] - cx) * 1.5f / size;
                data[i+1] = (data[i+1] - cy) * 1.5f / size;
                data[i+2] = (data[i+2] - cz) * 1.5f / size;
            }
            ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4)
                    .order(ByteOrder.nativeOrder());
            FloatBuffer fb = bb.asFloatBuffer();
            fb.put(data).position(0);
            
float[] normalData = new float[data.length];

for (int i = 0; i < data.length; i += 9) {
    float ax = data[i + 3] - data[i];
    float ay = data[i + 4] - data[i + 1];
    float az = data[i + 5] - data[i + 2];

    float bx = data[i + 6] - data[i];
    float by = data[i + 7] - data[i + 1];
    float bz = data[i + 8] - data[i + 2];

    float nx = ay * bz - az * by;
    float ny = az * bx - ax * bz;
    float nz = ax * by - ay * bx;

    float length = (float) Math.sqrt(
            nx * nx + ny * ny + nz * nz);

    if (length > 0) {
        nx /= length;
        ny /= length;
        nz /= length;
    }

    for (int j = 0; j < 9; j += 3) {
        normalData[i + j] = nx;
        normalData[i + j + 1] = ny;
        normalData[i + j + 2] = nz;
    }
}

ByteBuffer normalBuffer = ByteBuffer
        .allocateDirect(normalData.length * 4)
        .order(ByteOrder.nativeOrder());

normals = normalBuffer.asFloatBuffer();
normals.put(normalData);
normals.position(0);
            
            vertices = fb;
            vertexCount = data.length / 3;
        }

        synchronized void rotate(float dx, float dy) {
    rotY += dx * 0.7f;
    rotX += dy * 0.7f;

    rotX = Math.max(-180f, Math.min(180f, rotX));
        }

        synchronized void zoom(float ratio) {
            if (ratio > 0.1f && ratio < 10f)
                zoom = Math.max(0.2f, Math.min(5f, zoom * ratio));
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            gl.glClearColor(0.10f, 0.12f, 0.16f, 1f);
            gl.glDisable(GL10.GL_CULL_FACE);
            gl.glEnable(GL10.GL_DEPTH_TEST);
            gl.glEnable(GL10.GL_LIGHTING);
gl.glEnable(GL10.GL_LIGHT0);

float[] lightPosition = {1f, 1f, 3f, 0f};
float[] ambient = {0.35f, 0.35f, 0.35f, 1f};
float[] diffuse = {0.8f, 0.8f, 0.8f, 1f};

gl.glLightfv(GL10.GL_LIGHT0, GL10.GL_POSITION,
        lightPosition, 0);
gl.glLightfv(GL10.GL_LIGHT0, GL10.GL_AMBIENT,
        ambient, 0);
gl.glLightfv(GL10.GL_LIGHT0, GL10.GL_DIFFUSE,
        diffuse, 0);

gl.glEnable(GL10.GL_COLOR_MATERIAL);
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int w, int h) {
            gl.glViewport(0, 0, w, h);
            gl.glMatrixMode(GL10.GL_PROJECTION);
            gl.glLoadIdentity();
            GLU.gluPerspective(gl, 45f,
                    (float)w / Math.max(1, h), 0.1f, 100f);
            gl.glMatrixMode(GL10.GL_MODELVIEW);
        }

        @Override
        public synchronized void onDrawFrame(GL10 gl) {
            gl.glClear(GL10.GL_COLOR_BUFFER_BIT |
                    GL10.GL_DEPTH_BUFFER_BIT);
            gl.glLoadIdentity();
            gl.glTranslatef(0, 0, -3.5f);
            gl.glScalef(zoom, zoom, zoom);
            gl.glRotatef(rotX, 1, 0, 0);
            gl.glRotatef(rotY, 0, 1, 0);

            if (vertices == null) return;

            gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);
            gl.glVertexPointer(3, GL10.GL_FLOAT, 0, vertices);
            gl.glColor4f(0.2f, 0.75f, 0.95f, 1f);
            gl.glEnable(GL10.GL_POLYGON_OFFSET_FILL);
gl.glPolygonOffset(1.0f, 1.0f);

gl.glDrawArrays(GL10.GL_TRIANGLES, 0, vertexCount);

gl.glDisable(GL10.GL_POLYGON_OFFSET_FILL);

gl.glColor4f(0.05f, 0.10f, 0.15f, 1f);
gl.glLineWidth(1.0f);

for (int i = 0; i < vertexCount; i += 3) {
    gl.glDrawArrays(GL10.GL_LINE_LOOP, i, 3);
}
            gl.glDisableClientState(GL10.GL_VERTEX_ARRAY);
        }
    }
}
