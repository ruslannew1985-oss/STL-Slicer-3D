package com.stlslicer.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class GlbToStl {

    public static final class Result {
        public final byte[] stlBytes;
        public final float widthMm;
        public final float heightMm;
        public final float depthMm;
        public final int triangleCount;

        Result(byte[] stlBytes, float widthMm, float heightMm, float depthMm, int triangleCount) {
            this.stlBytes = stlBytes;
            this.widthMm = widthMm;
            this.heightMm = heightMm;
            this.depthMm = depthMm;
            this.triangleCount = triangleCount;
        }
    }

    private static final int GLB_MAGIC = 0x46546C67;
    private static final int JSON_CHUNK = 0x4E4F534A;
    private static final int BIN_CHUNK = 0x004E4942;
    private static final int MODE_TRIANGLES = 4;
    private static final int MAX_TRIANGLES = 800_000;

    private GlbToStl() {}

    public static Result convert(byte[] glb, float requestedHeightMm) throws Exception {
        if (glb == null || glb.length < 20) throw new IllegalArgumentException("Файл GLB пустой или повреждён");
        if (!Float.isFinite(requestedHeightMm) || requestedHeightMm <= 0f) requestedHeightMm = 100f;

        ByteBuffer bb = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
        int magic = bb.getInt();
        int version = bb.getInt();
        int totalLength = bb.getInt();
        if (magic != GLB_MAGIC || version != 2) throw new IllegalArgumentException("Поддерживается только GLB 2.0");
        if (totalLength > glb.length || totalLength < 20) throw new IllegalArgumentException("Неверная длина GLB");

        String jsonText = null;
        byte[] bin = null;
        while (bb.position() + 8 <= totalLength) {
            int chunkLen = bb.getInt();
            int chunkType = bb.getInt();
            if (chunkLen < 0 || bb.position() + chunkLen > totalLength) throw new IllegalArgumentException("Повреждённый блок GLB");
            byte[] data = new byte[chunkLen];
            bb.get(data);
            if (chunkType == JSON_CHUNK) {
                jsonText = new String(data, StandardCharsets.UTF_8).replace("\u0000", "").trim();
            } else if (chunkType == BIN_CHUNK && bin == null) {
                bin = data;
            }
        }
        if (jsonText == null || bin == null) throw new IllegalArgumentException("В GLB нет геометрии");

        JSONObject root = new JSONObject(jsonText);
        Parser parser = new Parser(root, bin);
        List<float[]> triangles = parser.readTriangles();
        if (triangles.isEmpty()) throw new IllegalArgumentException("В модели нет треугольной сетки");
        if (triangles.size() > MAX_TRIANGLES) throw new IllegalArgumentException("Модель слишком сложная для телефона");

        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (float[] t : triangles) {
            for (int i = 0; i < 9; i += 3) {
                float x = t[i], y = t[i + 1], z = t[i + 2];
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            }
        }

        float rawW = maxX - minX;
        float rawH = maxY - minY;
        float rawD = maxZ - minZ;
        float referenceH = rawH;
        if (!(referenceH > 1e-7f)) referenceH = Math.max(rawW, Math.max(rawH, rawD));
        if (!(referenceH > 1e-7f)) throw new IllegalArgumentException("Нулевой размер модели");
        float scale = requestedHeightMm / referenceH;
        float cx = (minX + maxX) * 0.5f;
        float cz = (minZ + maxZ) * 0.5f;

        int count = triangles.size();
        long outSize = 84L + 50L * count;
        if (outSize > Integer.MAX_VALUE) throw new IllegalArgumentException("STL слишком большой");
        ByteBuffer out = ByteBuffer.allocate((int) outSize).order(ByteOrder.LITTLE_ENDIAN);
        byte[] header = "Photo to 3D - Android STL export".getBytes(StandardCharsets.US_ASCII);
        out.put(header, 0, Math.min(header.length, 80));
        while (out.position() < 80) out.put((byte) 0);
        out.putInt(count);

        for (float[] tri : triangles) {
            float[] v = new float[9];
            for (int i = 0; i < 9; i += 3) {
                v[i] = (tri[i] - cx) * scale;
                v[i + 1] = (tri[i + 1] - minY) * scale;
                v[i + 2] = (tri[i + 2] - cz) * scale;
            }
            float ax = v[3] - v[0], ay = v[4] - v[1], az = v[5] - v[2];
            float bx = v[6] - v[0], by = v[7] - v[1], bz = v[8] - v[2];
            float nx = ay * bz - az * by;
            float ny = az * bx - ax * bz;
            float nz = ax * by - ay * bx;
            float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nl > 1e-12f) { nx /= nl; ny /= nl; nz /= nl; } else { nx = ny = nz = 0f; }
            out.putFloat(nx).putFloat(ny).putFloat(nz);
            for (float f : v) out.putFloat(f);
            out.putShort((short) 0);
        }

        return new Result(out.array(), rawW * scale, rawH * scale, rawD * scale, count);
    }

    private static final class Parser {
        private final JSONObject root;
        private final byte[] bin;
        private final JSONArray accessors;
        private final JSONArray views;
        private final JSONArray meshes;
        private final JSONArray nodes;
        private final List<float[]> triangles = new ArrayList<>();

        Parser(JSONObject root, byte[] bin) {
            this.root = root;
            this.bin = bin;
            this.accessors = root.optJSONArray("accessors");
            this.views = root.optJSONArray("bufferViews");
            this.meshes = root.optJSONArray("meshes");
            this.nodes = root.optJSONArray("nodes");
        }

        List<float[]> readTriangles() throws Exception {
            if (accessors == null || views == null || meshes == null) return triangles;
            JSONArray scenes = root.optJSONArray("scenes");
            if (scenes != null && scenes.length() > 0 && nodes != null) {
                int sceneIndex = root.optInt("scene", 0);
                if (sceneIndex < 0 || sceneIndex >= scenes.length()) sceneIndex = 0;
                JSONArray rootNodes = scenes.getJSONObject(sceneIndex).optJSONArray("nodes");
                if (rootNodes != null) {
                    float[] id = identity();
                    for (int i = 0; i < rootNodes.length(); i++) visitNode(rootNodes.getInt(i), id);
                }
            } else if (nodes != null && nodes.length() > 0) {
                float[] id = identity();
                for (int i = 0; i < nodes.length(); i++) visitNode(i, id);
            } else {
                float[] id = identity();
                for (int i = 0; i < meshes.length(); i++) readMesh(i, id);
            }
            return triangles;
        }

        private void visitNode(int index, float[] parent) throws Exception {
            if (index < 0 || index >= nodes.length()) return;
            JSONObject n = nodes.getJSONObject(index);
            float[] local = nodeMatrix(n);
            float[] world = multiply(parent, local);
            if (n.has("mesh")) readMesh(n.getInt("mesh"), world);
            JSONArray children = n.optJSONArray("children");
            if (children != null) {
                for (int i = 0; i < children.length(); i++) visitNode(children.getInt(i), world);
            }
        }

        private void readMesh(int meshIndex, float[] world) throws Exception {
            if (meshIndex < 0 || meshIndex >= meshes.length()) return;
            JSONArray prims = meshes.getJSONObject(meshIndex).optJSONArray("primitives");
            if (prims == null) return;
            for (int p = 0; p < prims.length(); p++) {
                JSONObject prim = prims.getJSONObject(p);
                if (prim.optInt("mode", MODE_TRIANGLES) != MODE_TRIANGLES) continue;
                JSONObject attrs = prim.optJSONObject("attributes");
                if (attrs == null || !attrs.has("POSITION")) continue;
                int posAccessor = attrs.getInt("POSITION");
                float[][] positions = readPositions(posAccessor, world);
                int[] indices = prim.has("indices") ? readIndices(prim.getInt("indices")) : null;
                if (indices == null) {
                    for (int i = 0; i + 2 < positions.length; i += 3) addTriangle(positions[i], positions[i + 1], positions[i + 2]);
                } else {
                    for (int i = 0; i + 2 < indices.length; i += 3) {
                        int a = indices[i], b = indices[i + 1], c = indices[i + 2];
                        if (a >= 0 && b >= 0 && c >= 0 && a < positions.length && b < positions.length && c < positions.length)
                            addTriangle(positions[a], positions[b], positions[c]);
                    }
                }
            }
        }

        private void addTriangle(float[] a, float[] b, float[] c) throws Exception {
            if (triangles.size() >= MAX_TRIANGLES) throw new IllegalArgumentException("Слишком много треугольников");
            triangles.add(new float[]{a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]});
        }

        private float[][] readPositions(int accessorIndex, float[] world) throws Exception {
            JSONObject a = accessors.getJSONObject(accessorIndex);
            if (a.optInt("componentType") != 5126 || !"VEC3".equals(a.optString("type")))
                throw new IllegalArgumentException("Неподдерживаемый формат координат GLB");
            int count = a.getInt("count");
            JSONObject v = views.getJSONObject(a.getInt("bufferView"));
            int base = v.optInt("byteOffset", 0) + a.optInt("byteOffset", 0);
            int stride = v.optInt("byteStride", 12);
            if (stride < 12) stride = 12;
            float[][] out = new float[count][3];
            ByteBuffer b = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count; i++) {
                int off = base + i * stride;
                if (off < 0 || off + 12 > bin.length) throw new IllegalArgumentException("Координаты выходят за границы GLB");
                float x = b.getFloat(off), y = b.getFloat(off + 4), z = b.getFloat(off + 8);
                out[i] = transform(world, x, y, z);
            }
            return out;
        }

        private int[] readIndices(int accessorIndex) throws Exception {
            JSONObject a = accessors.getJSONObject(accessorIndex);
            int count = a.getInt("count");
            int component = a.getInt("componentType");
            int size;
            if (component == 5121) size = 1;
            else if (component == 5123) size = 2;
            else if (component == 5125) size = 4;
            else throw new IllegalArgumentException("Неподдерживаемый формат индексов GLB");
            JSONObject v = views.getJSONObject(a.getInt("bufferView"));
            int base = v.optInt("byteOffset", 0) + a.optInt("byteOffset", 0);
            int stride = v.optInt("byteStride", size);
            if (stride < size) stride = size;
            int[] out = new int[count];
            ByteBuffer b = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count; i++) {
                int off = base + i * stride;
                if (off < 0 || off + size > bin.length) throw new IllegalArgumentException("Индексы выходят за границы GLB");
                if (component == 5121) out[i] = bin[off] & 0xff;
                else if (component == 5123) out[i] = Short.toUnsignedInt(b.getShort(off));
                else {
                    long val = Integer.toUnsignedLong(b.getInt(off));
                    if (val > Integer.MAX_VALUE) throw new IllegalArgumentException("Слишком большой индекс GLB");
                    out[i] = (int) val;
                }
            }
            return out;
        }

        private static float[] transform(float[] m, float x, float y, float z) {
            return new float[]{
                    m[0] * x + m[4] * y + m[8] * z + m[12],
                    m[1] * x + m[5] * y + m[9] * z + m[13],
                    m[2] * x + m[6] * y + m[10] * z + m[14]
            };
        }

        private static float[] nodeMatrix(JSONObject n) throws Exception {
            JSONArray mat = n.optJSONArray("matrix");
            if (mat != null && mat.length() == 16) {
                float[] m = new float[16];
                for (int i = 0; i < 16; i++) m[i] = (float) mat.getDouble(i);
                return m;
            }
            float tx = 0, ty = 0, tz = 0;
            JSONArray t = n.optJSONArray("translation");
            if (t != null && t.length() >= 3) { tx = (float)t.getDouble(0); ty = (float)t.getDouble(1); tz = (float)t.getDouble(2); }
            float sx = 1, sy = 1, sz = 1;
            JSONArray s = n.optJSONArray("scale");
            if (s != null && s.length() >= 3) { sx = (float)s.getDouble(0); sy = (float)s.getDouble(1); sz = (float)s.getDouble(2); }
            float qx = 0, qy = 0, qz = 0, qw = 1;
            JSONArray q = n.optJSONArray("rotation");
            if (q != null && q.length() >= 4) { qx = (float)q.getDouble(0); qy = (float)q.getDouble(1); qz = (float)q.getDouble(2); qw = (float)q.getDouble(3); }
            float xx=qx*qx, yy=qy*qy, zz=qz*qz, xy=qx*qy, xz=qx*qz, yz=qy*qz, xw=qx*qw, yw=qy*qw, zw=qz*qw;
            float[] m = identity();
            m[0] = (1 - 2*(yy+zz))*sx; m[1] = (2*(xy+zw))*sx; m[2] = (2*(xz-yw))*sx;
            m[4] = (2*(xy-zw))*sy; m[5] = (1 - 2*(xx+zz))*sy; m[6] = (2*(yz+xw))*sy;
            m[8] = (2*(xz+yw))*sz; m[9] = (2*(yz-xw))*sz; m[10] = (1 - 2*(xx+yy))*sz;
            m[12] = tx; m[13] = ty; m[14] = tz;
            return m;
        }

        private static float[] identity() {
            float[] m = new float[16];
            m[0]=m[5]=m[10]=m[15]=1f;
            return m;
        }

        private static float[] multiply(float[] a, float[] b) {
            float[] r = new float[16];
            for (int c=0;c<4;c++) for (int row=0;row<4;row++) {
                float sum=0;
                for (int k=0;k<4;k++) sum += a[k*4+row] * b[c*4+k];
                r[c*4+row]=sum;
            }
            return r;
        }
    }
}
