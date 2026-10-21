package com.stlslicer.app;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/** Normalizes STL, 3MF and ZIP model files into a local binary STL. */
public final class ModelFileLoader {
    private static final long MAX_ENTRY_BYTES = 120L * 1024L * 1024L;
    private static final int MAX_TRIANGLES = 300_000;

    public static final class PreparedModel {
        public final File stlFile;
        public final String displayName;
        public PreparedModel(File stlFile, String displayName) {
            this.stlFile = stlFile;
            this.displayName = displayName;
        }
    }

    private ModelFileLoader() {}

    public static boolean is3mfPackage(File file) {
        try (ZipFile zip = new ZipFile(file)) {
            return findModelEntry(zip) != null;
        } catch (Exception e) {
            return false;
        }
    }

    public static List<String> listArchiveModels(File archive) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String lower = e.getName().toLowerCase(Locale.US);
                if (lower.endsWith(".stl") || lower.endsWith(".3mf")) {
                    if (e.getSize() > MAX_ENTRY_BYTES)
                        throw new IOException("Модель в ZIP слишком большая: " + e.getName());
                    names.add(e.getName());
                }
            }
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public static PreparedModel prepare(File source,
                                        String displayName,
                                        File cacheDir) throws IOException {
        String name = displayName == null ? source.getName() : displayName;
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".stl")) {
            return new PreparedModel(source, name);
        }
        if (lower.endsWith(".3mf") || is3mfPackage(source)) {
            File out = new File(cacheDir, uniqueBase(name) + ".stl");
            convert3mfToStl(source, out);
            return new PreparedModel(out, name);
        }
        if (lower.endsWith(".zip")) {
            List<String> models = listArchiveModels(source);
            if (models.isEmpty()) throw new IOException("В ZIP нет файлов STL или 3MF");
            if (models.size() > 1)
                throw new IOException("В ZIP несколько моделей — выберите одну");
            return prepareArchiveEntry(source, models.get(0), cacheDir);
        }
        return new PreparedModel(source, name);
    }

    public static PreparedModel prepareArchiveEntry(File archive,
                                                    String entryName,
                                                    File cacheDir) throws IOException {
        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null || entry.isDirectory())
                throw new IOException("Файл не найден в ZIP: " + entryName);
            if (entry.getSize() > MAX_ENTRY_BYTES)
                throw new IOException("Модель в ZIP слишком большая");

            String base = new File(entryName).getName();
            String lower = base.toLowerCase(Locale.US);
            if (!(lower.endsWith(".stl") || lower.endsWith(".3mf")))
                throw new IOException("Поддерживаются только STL и 3MF внутри ZIP");

            File extracted = new File(cacheDir,
                    "zip_" + System.nanoTime() + "_" + sanitize(base));
            try (InputStream in = new BufferedInputStream(zip.getInputStream(entry));
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(extracted))) {
                copyLimited(in, out, MAX_ENTRY_BYTES);
            }

            if (lower.endsWith(".3mf")) {
                File stl = new File(cacheDir, uniqueBase(base) + ".stl");
                convert3mfToStl(extracted, stl);
                return new PreparedModel(stl, base);
            }
            return new PreparedModel(extracted, base);
        }
    }

    private static void convert3mfToStl(File file, File output) throws IOException {
        final ModelData data = new ModelData();
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry modelEntry = findModelEntry(zip);
            if (modelEntry == null) throw new IOException("В 3MF не найден файл 3D-модели");
            try (InputStream in = new BufferedInputStream(zip.getInputStream(modelEntry))) {
                try {
                    SAXParserFactory factory = SAXParserFactory.newInstance();
                    factory.setNamespaceAware(true);
                    try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignored) {}
                    try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignored) {}
                    try { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); } catch (Exception ignored) {}
                    factory.newSAXParser().parse(new LimitedInputStream(in, MAX_ENTRY_BYTES), new ThreeMfHandler(data));
                } catch (Exception e) {
                    throw new IOException("Не удалось прочитать структуру 3MF", e);
                }
            }
        }

        List<float[]> triangles = new ArrayList<>();
        Set<Integer> stack = new HashSet<>();
        if (!data.buildItems.isEmpty()) {
            for (BuildItem item : data.buildItems) {
                emitObject(data, item.objectId, item.transform, stack, triangles);
            }
        } else {
            for (Object3mf obj : data.objects.values()) {
                if (!obj.vertices.isEmpty() && !obj.triangles.isEmpty())
                    emitObject(data, obj.id, Transform.identity(), stack, triangles);
            }
        }

        if (triangles.isEmpty()) throw new IOException("В 3MF нет печатаемой геометрии");
        writeBinaryStl(output, triangles);
    }

    private static ZipEntry findModelEntry(ZipFile zip) {
        ZipEntry fallback = null;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            if (e.isDirectory()) continue;
            String lower = e.getName().replace('\\', '/').toLowerCase(Locale.US);
            if (lower.equals("3d/3dmodel.model")) return e;
            if (lower.endsWith(".model") && fallback == null) fallback = e;
        }
        return fallback;
    }

    private static void emitObject(ModelData data,
                                   int objectId,
                                   Transform world,
                                   Set<Integer> stack,
                                   List<float[]> out) throws IOException {
        if (!stack.add(objectId)) throw new IOException("Циклическая ссылка объектов в 3MF");
        Object3mf obj = data.objects.get(objectId);
        if (obj == null) {
            stack.remove(objectId);
            throw new IOException("3MF ссылается на отсутствующий объект " + objectId);
        }

        if (!obj.triangles.isEmpty()) {
            for (Tri t : obj.triangles) {
                if (t.a < 0 || t.b < 0 || t.c < 0 ||
                        t.a >= obj.vertices.size() ||
                        t.b >= obj.vertices.size() ||
                        t.c >= obj.vertices.size())
                    throw new IOException("Неверные индексы треугольника в 3MF");
                Vec3 a = world.apply(obj.vertices.get(t.a));
                Vec3 b = world.apply(obj.vertices.get(t.b));
                Vec3 c = world.apply(obj.vertices.get(t.c));
                out.add(new float[] {
                        (float)a.x, (float)a.y, (float)a.z,
                        (float)b.x, (float)b.y, (float)b.z,
                        (float)c.x, (float)c.y, (float)c.z
                });
                if (out.size() > MAX_TRIANGLES)
                    throw new IOException("3MF слишком сложный: больше " + MAX_TRIANGLES + " треугольников");
            }
        }

        for (Component c : obj.components) {
            Transform childWorld = Transform.multiply(world, c.transform);
            emitObject(data, c.objectId, childWorld, stack, out);
        }
        stack.remove(objectId);
    }

    private static void writeBinaryStl(File output, List<float[]> tris) throws IOException {
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(output), 64 * 1024)) {
            byte[] header = new byte[80];
            byte[] label = "Converted from 3MF by STL Slicer 3D".getBytes("US-ASCII");
            System.arraycopy(label, 0, header, 0, Math.min(header.length, label.length));
            out.write(header);

            ByteBuffer count = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            count.putInt(tris.size());
            out.write(count.array());

            ByteBuffer rec = ByteBuffer.allocate(50).order(ByteOrder.LITTLE_ENDIAN);
            for (float[] v : tris) {
                rec.clear();
                float ax = v[3] - v[0], ay = v[4] - v[1], az = v[5] - v[2];
                float bx = v[6] - v[0], by = v[7] - v[1], bz = v[8] - v[2];
                float nx = ay * bz - az * by;
                float ny = az * bx - ax * bz;
                float nz = ax * by - ay * bx;
                float len = (float)Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len > 1e-12f) { nx /= len; ny /= len; nz /= len; }
                else { nx = ny = nz = 0f; }
                rec.putFloat(nx).putFloat(ny).putFloat(nz);
                for (int i = 0; i < 9; i++) rec.putFloat(v[i]);
                rec.putShort((short)0);
                out.write(rec.array());
            }
        }
    }

    private static final class ThreeMfHandler extends DefaultHandler {
        private final ModelData data;
        private Object3mf current;
        ThreeMfHandler(ModelData data) { this.data = data; }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes a) throws org.xml.sax.SAXException {
            String tag = (localName == null || localName.isEmpty() ? qName : localName).toLowerCase(Locale.US);
            try {
                if ("model".equals(tag)) {
                    data.unitScale = unitScale(a.getValue("unit"));
                } else if ("object".equals(tag)) {
                    current = new Object3mf(parseInt(a, "id"));
                    data.objects.put(current.id, current);
                } else if ("vertex".equals(tag) && current != null) {
                    current.vertices.add(new Vec3(
                            parseDouble(a, "x") * data.unitScale,
                            parseDouble(a, "y") * data.unitScale,
                            parseDouble(a, "z") * data.unitScale));
                } else if ("triangle".equals(tag) && current != null) {
                    current.triangles.add(new Tri(
                            parseInt(a, "v1"), parseInt(a, "v2"), parseInt(a, "v3")));
                } else if ("component".equals(tag) && current != null) {
                    current.components.add(new Component(
                            parseInt(a, "objectid"),
                            Transform.parse(a.getValue("transform"), data.unitScale)));
                } else if ("item".equals(tag)) {
                    data.buildItems.add(new BuildItem(
                            parseInt(a, "objectid"),
                            Transform.parse(a.getValue("transform"), data.unitScale)));
                }
            } catch (RuntimeException ex) {
                throw new org.xml.sax.SAXException(ex);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String tag = (localName == null || localName.isEmpty() ? qName : localName).toLowerCase(Locale.US);
            if ("object".equals(tag)) current = null;
        }
    }

    private static int parseInt(Attributes a, String name) {
        String v = a.getValue(name);
        if (v == null) throw new IllegalArgumentException("Нет атрибута " + name);
        return Integer.parseInt(v);
    }

    private static double parseDouble(Attributes a, String name) {
        String v = a.getValue(name);
        if (v == null) throw new IllegalArgumentException("Нет атрибута " + name);
        return Double.parseDouble(v);
    }

    private static double unitScale(String unit) {
        if (unit == null || unit.isEmpty() || "millimeter".equalsIgnoreCase(unit)) return 1.0;
        if ("micron".equalsIgnoreCase(unit)) return 0.001;
        if ("centimeter".equalsIgnoreCase(unit)) return 10.0;
        if ("meter".equalsIgnoreCase(unit)) return 1000.0;
        if ("inch".equalsIgnoreCase(unit)) return 25.4;
        if ("foot".equalsIgnoreCase(unit)) return 304.8;
        return 1.0;
    }

    private static final class ModelData {
        final Map<Integer, Object3mf> objects = new LinkedHashMap<>();
        final List<BuildItem> buildItems = new ArrayList<>();
        double unitScale = 1.0;
    }
    private static final class Object3mf {
        final int id;
        final List<Vec3> vertices = new ArrayList<>();
        final List<Tri> triangles = new ArrayList<>();
        final List<Component> components = new ArrayList<>();
        Object3mf(int id) { this.id = id; }
    }
    private static final class Vec3 {
        final double x, y, z;
        Vec3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
    }
    private static final class Tri {
        final int a, b, c;
        Tri(int a, int b, int c) { this.a = a; this.b = b; this.c = c; }
    }
    private static final class Component {
        final int objectId;
        final Transform transform;
        Component(int objectId, Transform transform) { this.objectId = objectId; this.transform = transform; }
    }
    private static final class BuildItem {
        final int objectId;
        final Transform transform;
        BuildItem(int objectId, Transform transform) { this.objectId = objectId; this.transform = transform; }
    }

    private static final class Transform {
        final double[] m;
        Transform(double[] m) { this.m = m; }
        static Transform identity() {
            return new Transform(new double[] {
                    1,0,0,0,
                    0,1,0,0,
                    0,0,1,0,
                    0,0,0,1
            });
        }
        static Transform parse(String s, double unitScale) {
            if (s == null || s.trim().isEmpty()) return identity();
            String[] p = s.trim().split("\\s+");
            if (p.length != 12) return identity();
            double[] q = new double[12];
            for (int i = 0; i < 12; i++) q[i] = Double.parseDouble(p[i]);
            return new Transform(new double[] {
                    q[0], q[3], q[6], q[9] * unitScale,
                    q[1], q[4], q[7], q[10] * unitScale,
                    q[2], q[5], q[8], q[11] * unitScale,
                    0, 0, 0, 1
            });
        }
        static Transform multiply(Transform a, Transform b) {
            double[] r = new double[16];
            for (int row = 0; row < 4; row++) {
                for (int col = 0; col < 4; col++) {
                    double s = 0;
                    for (int k = 0; k < 4; k++) s += a.m[row * 4 + k] * b.m[k * 4 + col];
                    r[row * 4 + col] = s;
                }
            }
            return new Transform(r);
        }
        Vec3 apply(Vec3 v) {
            double x = m[0]*v.x + m[1]*v.y + m[2]*v.z + m[3];
            double y = m[4]*v.x + m[5]*v.y + m[6]*v.z + m[7];
            double z = m[8]*v.x + m[9]*v.y + m[10]*v.z + m[11];
            return new Vec3(x, y, z);
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private long count;
        LimitedInputStream(InputStream in, long limit) { super(in); this.limit = limit; }
        @Override public int read() throws IOException {
            int v = super.read();
            if (v >= 0 && ++count > limit) throw new IOException("Распакованный 3MF слишком большой");
            return v;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0 && (count += n) > limit) throw new IOException("Распакованный 3MF слишком большой");
            return n;
        }
    }

    private static void copyLimited(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > limit) throw new IOException("Файл слишком большой");
            out.write(buffer, 0, n);
        }
    }

    private static String uniqueBase(String name) {
        String base = new File(name == null ? "model" : name).getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return "model_" + System.nanoTime() + "_" + sanitize(base);
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]+", "_");
    }
}
