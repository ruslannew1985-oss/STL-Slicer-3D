package com.stlslicer.app;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Lightweight offline STL -> G-code slicer.
 * Intended for Creality Ender-3 V3 SE / PETG profile used by the app.
 */
public final class SlicerEngine {

    public interface ProgressListener {
        void onProgress(int layer, int totalLayers);
    }

    public static final class Settings {
        public double nozzleTemperature = 235;
        public double bedTemperature = 75;
        public double layerHeight = 0.20;
        public double printSpeed = 50;
        public double infillPercent = 20;
        public double nozzleDiameter = 0.40;
        public double filamentDiameter = 1.75;
        public int perimeterCount = 2;
        public double travelSpeed = 150;
        public double extrusionMultiplier = 1.0;
        public double bedSizeX = 220;
        public double bedSizeY = 220;

        // PETG defaults requested for this app.
        public int bottomSolidLayers = 4;
        public int fanStartLayer = 4;     // human layer number, starts from 1
        public int fanSpeed = 255;        // full fan when enabled

        public void validate() {
            if (layerHeight < 0.05 || layerHeight > 1.0)
                throw new IllegalArgumentException("Высота слоя должна быть 0.05–1.0 мм");
            if (printSpeed < 5 || printSpeed > 300)
                throw new IllegalArgumentException("Скорость печати должна быть 5–300 мм/с");
            if (infillPercent < 0 || infillPercent > 100)
                throw new IllegalArgumentException("Заполнение должно быть 0–100%");
            if (nozzleTemperature < 150 || nozzleTemperature > 320)
                throw new IllegalArgumentException("Температура сопла должна быть 150–320 °C");
            if (bedTemperature < 0 || bedTemperature > 130)
                throw new IllegalArgumentException("Температура стола должна быть 0–130 °C");
            if (nozzleDiameter <= 0 || filamentDiameter <= 0)
                throw new IllegalArgumentException("Неверный диаметр сопла или филамента");
            if (perimeterCount < 1 || perimeterCount > 10)
                throw new IllegalArgumentException("Количество периметров должно быть 1–10");
            if (bottomSolidLayers < 0 || bottomSolidLayers > 50)
                throw new IllegalArgumentException("Неверное количество нижних сплошных слоёв");
            if (fanStartLayer < 1)
                throw new IllegalArgumentException("Слой включения вентилятора должен быть >= 1");
            if (fanSpeed < 0 || fanSpeed > 255)
                throw new IllegalArgumentException("Скорость вентилятора должна быть 0–255");
        }
    }

    private static final double EPS = 1e-7;
    private static final double JOIN_TOLERANCE = 0.03;

    private SlicerEngine() {}

    public static void slice(InputStream stlInput,
                             OutputStream gcodeOutput,
                             Settings settings,
                             ProgressListener progress) throws IOException {
        settings.validate();
        Mesh mesh = readStl(stlInput);

        double width = mesh.maxX - mesh.minX;
        double depth = mesh.maxY - mesh.minY;
        double height = mesh.maxZ - mesh.minZ;

        if (width <= EPS || depth <= EPS || height <= EPS)
            throw new IOException("STL имеет нулевой размер");
        if (width > settings.bedSizeX - 10 || depth > settings.bedSizeY - 10)
            throw new IOException("Модель не помещается на стол 220×220 мм");

        int layers = Math.max(1, (int)Math.ceil(height / settings.layerHeight));
        double shiftX = settings.bedSizeX / 2.0 - (mesh.minX + mesh.maxX) / 2.0;
        double shiftY = settings.bedSizeY / 2.0 - (mesh.minY + mesh.maxY) / 2.0;
        double filamentArea = Math.PI * settings.filamentDiameter * settings.filamentDiameter / 4.0;
        double lineWidth = settings.nozzleDiameter;

        BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(gcodeOutput, StandardCharsets.US_ASCII), 64 * 1024);
        writeHeader(out, settings, layers);

        double[] extrusion = {0.0};

        for (int layerIndex = 0; layerIndex < layers; layerIndex++) {
            int humanLayer = layerIndex + 1;
            double modelZ = mesh.minZ + Math.min(height - EPS, humanLayer * settings.layerHeight);
            double printerZ = Math.min(height, humanLayer * settings.layerHeight);

            List<Segment> segments = sliceAt(mesh, modelZ);
            List<List<Point>> loops = stitchLoops(segments);

            out.write("\n;LAYER:" + layerIndex + "\n");
            out.write(String.format(Locale.US, ";Z:%.3f%n", printerZ));

            // Fan is deliberately off for the first three layers.
            // Turn it on immediately before printing the requested layer.
            if (humanLayer == settings.fanStartLayer) {
                out.write("M106 S" + settings.fanSpeed + " ; fan on from layer " + humanLayer + "\n");
            }

            out.write(String.format(Locale.US, "G0 Z%.3f F%.0f%n",
                    printerZ, settings.travelSpeed * 60.0));

            if (!loops.isEmpty()) {
                List<Boolean> holes = classifyHoles(loops);

                for (int i = 0; i < loops.size(); i++) {
                    List<Point> loop = loops.get(i);
                    boolean hole = holes.get(i);

                    for (int p = 0; p < settings.perimeterCount; p++) {
                        double offset = (p + 0.5) * lineWidth;
                        if (hole) offset = -offset;
                        List<Point> perimeter = offsetPolygon(loop, offset);
                        if (perimeter.size() >= 3) {
                            writeClosedPath(out, perimeter, shiftX, shiftY,
                                    settings, lineWidth, filamentArea, extrusion, ";TYPE:WALL");
                        }
                    }
                }

                if (humanLayer <= settings.bottomSolidLayers) {
                    // Dense skin on the bottom. Alternating direction between layers
                    // closes the floor and makes it appear opaque in the 3D G-code view.
                    writeFill(out, loops, shiftX, shiftY, layerIndex,
                            settings, lineWidth, filamentArea, extrusion,
                            1.0, ";TYPE:BOTTOM");
                } else if (settings.infillPercent > 0.1) {
                    writeFill(out, loops, shiftX, shiftY, layerIndex,
                            settings, lineWidth, filamentArea, extrusion,
                            settings.infillPercent / 100.0, ";TYPE:INFILL");
                }
            }

            if (progress != null) progress.onProgress(humanLayer, layers);
        }

        writeFooter(out);
        out.flush();
    }

    private static void writeHeader(BufferedWriter out, Settings s, int layers) throws IOException {
        out.write("; STL Slicer 3D - offline Java slicer\n");
        out.write("; Printer: Creality Ender-3 V3 SE\n");
        out.write("; Material: PETG\n");
        out.write("; Layers: " + layers + "\n");
        out.write("; Bottom solid layers: " + s.bottomSolidLayers + "\n");
        out.write("; Fan starts at layer: " + s.fanStartLayer + "\n");
        out.write("G21 ; millimeters\n");
        out.write("G90 ; absolute XYZ\n");
        out.write("M82 ; absolute extrusion\n");
        out.write("M107 ; fan off for first layers\n");
        out.write(String.format(Locale.US, "M140 S%.0f%n", s.bedTemperature));
        out.write(String.format(Locale.US, "M104 S%.0f%n", s.nozzleTemperature));
        out.write(String.format(Locale.US, "M190 S%.0f%n", s.bedTemperature));
        out.write(String.format(Locale.US, "M109 S%.0f%n", s.nozzleTemperature));
        out.write("G28 ; home\n");
        out.write("G92 E0\n");
        out.write("G0 Z2.0 F3000\n");
    }

    private static void writeFooter(BufferedWriter out) throws IOException {
        out.write("\n;END\n");
        out.write("M107 ; fan off\n");
        out.write("G92 E0\n");
        out.write("G1 E-2 F1800\n");
        out.write("G1 Z10 F3000\n");
        out.write("G28 X Y\n");
        out.write("M104 S0\n");
        out.write("M140 S0\n");
        out.write("M84\n");
    }

    private static void writeClosedPath(BufferedWriter out,
                                        List<Point> path,
                                        double shiftX, double shiftY,
                                        Settings s,
                                        double lineWidth,
                                        double filamentArea,
                                        double[] extrusion,
                                        String typeComment) throws IOException {
        if (path.size() < 3) return;
        out.write(typeComment + "\n");
        Point first = path.get(0);
        out.write(String.format(Locale.US, "G0 X%.3f Y%.3f F%.0f%n",
                first.x + shiftX, first.y + shiftY, s.travelSpeed * 60.0));

        Point prev = first;
        for (int i = 1; i <= path.size(); i++) {
            Point cur = path.get(i % path.size());
            extrusion[0] += extrusionFor(distance(prev, cur), lineWidth,
                    s.layerHeight, filamentArea, s.extrusionMultiplier);
            out.write(String.format(Locale.US, "G1 X%.3f Y%.3f E%.5f F%.0f%n",
                    cur.x + shiftX, cur.y + shiftY,
                    extrusion[0], s.printSpeed * 60.0));
            prev = cur;
        }
    }

    private static void writeOpenLine(BufferedWriter out,
                                      Point a, Point b,
                                      double shiftX, double shiftY,
                                      Settings s,
                                      double lineWidth,
                                      double filamentArea,
                                      double[] extrusion) throws IOException {
        out.write(String.format(Locale.US, "G0 X%.3f Y%.3f F%.0f%n",
                a.x + shiftX, a.y + shiftY, s.travelSpeed * 60.0));
        extrusion[0] += extrusionFor(distance(a, b), lineWidth,
                s.layerHeight, filamentArea, s.extrusionMultiplier);
        out.write(String.format(Locale.US, "G1 X%.3f Y%.3f E%.5f F%.0f%n",
                b.x + shiftX, b.y + shiftY,
                extrusion[0], s.printSpeed * 60.0));
    }

    private static double extrusionFor(double pathLength,
                                       double lineWidth,
                                       double layerHeight,
                                       double filamentArea,
                                       double multiplier) {
        return pathLength * lineWidth * layerHeight / filamentArea * multiplier;
    }

    private static void writeFill(BufferedWriter out,
                                  List<List<Point>> loops,
                                  double shiftX, double shiftY,
                                  int layerIndex,
                                  Settings s,
                                  double lineWidth,
                                  double filamentArea,
                                  double[] extrusion,
                                  double density,
                                  String typeComment) throws IOException {
        density = Math.max(0.01, Math.min(1.0, density));
        // Slight overlap for 100% bottom skin avoids visible gaps between lines.
        double spacing = density >= 0.999
                ? Math.max(lineWidth * 0.90, 0.05)
                : Math.max(lineWidth, lineWidth / density);

        Bounds b = boundsOfLoops(loops);
        if (!b.isValid()) return;
        out.write(typeComment + "\n");

        boolean horizontal = (layerIndex % 2 == 0);
        if (horizontal) {
            boolean reverse = false;
            for (double y = b.minY + spacing * 0.5; y <= b.maxY; y += spacing) {
                List<Double> xs = new ArrayList<>();
                for (List<Point> loop : loops) collectHorizontalIntersections(loop, y, xs);
                Collections.sort(xs);
                for (int i = 0; i + 1 < xs.size(); i += 2) {
                    Point a = new Point(xs.get(i), y);
                    Point c = new Point(xs.get(i + 1), y);
                    if (distance(a, c) >= lineWidth * 0.5) {
                        if (reverse) writeOpenLine(out, c, a, shiftX, shiftY,
                                s, lineWidth, filamentArea, extrusion);
                        else writeOpenLine(out, a, c, shiftX, shiftY,
                                s, lineWidth, filamentArea, extrusion);
                        reverse = !reverse;
                    }
                }
            }
        } else {
            boolean reverse = false;
            for (double x = b.minX + spacing * 0.5; x <= b.maxX; x += spacing) {
                List<Double> ys = new ArrayList<>();
                for (List<Point> loop : loops) collectVerticalIntersections(loop, x, ys);
                Collections.sort(ys);
                for (int i = 0; i + 1 < ys.size(); i += 2) {
                    Point a = new Point(x, ys.get(i));
                    Point c = new Point(x, ys.get(i + 1));
                    if (distance(a, c) >= lineWidth * 0.5) {
                        if (reverse) writeOpenLine(out, c, a, shiftX, shiftY,
                                s, lineWidth, filamentArea, extrusion);
                        else writeOpenLine(out, a, c, shiftX, shiftY,
                                s, lineWidth, filamentArea, extrusion);
                        reverse = !reverse;
                    }
                }
            }
        }
    }

    private static void collectHorizontalIntersections(List<Point> loop,
                                                       double y,
                                                       List<Double> xs) {
        for (int i = 0; i < loop.size(); i++) {
            Point a = loop.get(i);
            Point b = loop.get((i + 1) % loop.size());
            if ((a.y <= y && b.y > y) || (b.y <= y && a.y > y)) {
                double t = (y - a.y) / (b.y - a.y);
                xs.add(a.x + t * (b.x - a.x));
            }
        }
    }

    private static void collectVerticalIntersections(List<Point> loop,
                                                     double x,
                                                     List<Double> ys) {
        for (int i = 0; i < loop.size(); i++) {
            Point a = loop.get(i);
            Point b = loop.get((i + 1) % loop.size());
            if ((a.x <= x && b.x > x) || (b.x <= x && a.x > x)) {
                double t = (x - a.x) / (b.x - a.x);
                ys.add(a.y + t * (b.y - a.y));
            }
        }
    }

    private static List<Boolean> classifyHoles(List<List<Point>> loops) {
        List<Boolean> result = new ArrayList<>(loops.size());
        for (int i = 0; i < loops.size(); i++) {
            Point test = representativePoint(loops.get(i));
            int containing = 0;
            for (int j = 0; j < loops.size(); j++) {
                if (i != j && pointInPolygon(test, loops.get(j))) containing++;
            }
            result.add((containing & 1) == 1);
        }
        return result;
    }

    private static Point representativePoint(List<Point> p) {
        double x = 0, y = 0;
        for (Point q : p) {
            x += q.x;
            y += q.y;
        }
        return new Point(x / p.size(), y / p.size());
    }

    private static boolean pointInPolygon(Point p, List<Point> poly) {
        boolean inside = false;
        for (int i = 0, j = poly.size() - 1; i < poly.size(); j = i++) {
            Point a = poly.get(i), b = poly.get(j);
            boolean cross = ((a.y > p.y) != (b.y > p.y)) &&
                    (p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x);
            if (cross) inside = !inside;
        }
        return inside;
    }

    private static List<Point> offsetPolygon(List<Point> poly, double inwardDistance) {
        if (poly.size() < 3 || Math.abs(inwardDistance) < EPS)
            return new ArrayList<>(poly);

        double area = signedArea(poly);
        double side = area >= 0 ? 1.0 : -1.0;
        double d = inwardDistance * side;
        List<Point> result = new ArrayList<>(poly.size());

        for (int i = 0; i < poly.size(); i++) {
            Point prev = poly.get((i - 1 + poly.size()) % poly.size());
            Point cur = poly.get(i);
            Point next = poly.get((i + 1) % poly.size());

            Line l1 = offsetLine(prev, cur, d);
            Line l2 = offsetLine(cur, next, d);
            Point intersection = lineIntersection(l1, l2);

            if (intersection == null ||
                    distance(intersection, cur) > Math.abs(inwardDistance) * 20.0 + 10.0) {
                double nx = l1.nx + l2.nx;
                double ny = l1.ny + l2.ny;
                double len = Math.hypot(nx, ny);
                if (len < EPS)
                    result.add(new Point(cur.x + l1.nx * Math.abs(d),
                            cur.y + l1.ny * Math.abs(d)));
                else
                    result.add(new Point(cur.x + nx / len * Math.abs(d),
                            cur.y + ny / len * Math.abs(d)));
            } else {
                result.add(intersection);
            }
        }
        return result;
    }

    private static Line offsetLine(Point a, Point b, double signedDistance) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double len = Math.hypot(dx, dy);
        if (len < EPS) return new Line(a, b, 0, 0);

        double nx = -dy / len;
        double ny = dx / len;
        Point oa = new Point(a.x + nx * signedDistance, a.y + ny * signedDistance);
        Point ob = new Point(b.x + nx * signedDistance, b.y + ny * signedDistance);
        double sign = Math.signum(signedDistance == 0 ? 1 : signedDistance);
        return new Line(oa, ob, nx * sign, ny * sign);
    }

    private static Point lineIntersection(Line a, Line b) {
        double x1 = a.a.x, y1 = a.a.y, x2 = a.b.x, y2 = a.b.y;
        double x3 = b.a.x, y3 = b.a.y, x4 = b.b.x, y4 = b.b.y;
        double den = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
        if (Math.abs(den) < EPS) return null;

        double det1 = x1 * y2 - y1 * x2;
        double det2 = x3 * y4 - y3 * x4;
        return new Point(
                (det1 * (x3 - x4) - (x1 - x2) * det2) / den,
                (det1 * (y3 - y4) - (y1 - y2) * det2) / den);
    }

    private static double signedArea(List<Point> p) {
        double area = 0;
        for (int i = 0; i < p.size(); i++) {
            Point a = p.get(i);
            Point b = p.get((i + 1) % p.size());
            area += a.x * b.y - b.x * a.y;
        }
        return area * 0.5;
    }

    private static List<Segment> sliceAt(Mesh mesh, double z) {
        List<Segment> result = new ArrayList<>();

        for (int i = 0; i < mesh.triangles.length; i += 11) {
            if (z < mesh.triangles[i + 9] - EPS || z > mesh.triangles[i + 10] + EPS)
                continue;

            Point3 a = new Point3(mesh.triangles[i], mesh.triangles[i + 1], mesh.triangles[i + 2]);
            Point3 b = new Point3(mesh.triangles[i + 3], mesh.triangles[i + 4], mesh.triangles[i + 5]);
            Point3 c = new Point3(mesh.triangles[i + 6], mesh.triangles[i + 7], mesh.triangles[i + 8]);

            List<Point> hits = new ArrayList<>(3);
            intersectEdge(a, b, z, hits);
            intersectEdge(b, c, z, hits);
            intersectEdge(c, a, z, hits);
            dedupe(hits);

            if (hits.size() >= 2) {
                Point p1 = hits.get(0);
                Point p2 = hits.get(1);

                if (hits.size() > 2) {
                    double best = -1;
                    for (int u = 0; u < hits.size(); u++) {
                        for (int v = u + 1; v < hits.size(); v++) {
                            double d = distance(hits.get(u), hits.get(v));
                            if (d > best) {
                                best = d;
                                p1 = hits.get(u);
                                p2 = hits.get(v);
                            }
                        }
                    }
                }

                if (distance(p1, p2) > JOIN_TOLERANCE * 0.1)
                    result.add(new Segment(p1, p2));
            }
        }
        return result;
    }

    private static void intersectEdge(Point3 a, Point3 b, double z, List<Point> out) {
        double da = a.z - z;
        double db = b.z - z;

        if (Math.abs(da) < EPS && Math.abs(db) < EPS) return;
        if ((da < -EPS && db < -EPS) || (da > EPS && db > EPS)) return;

        if (Math.abs(da) < EPS) {
            out.add(new Point(a.x, a.y));
            return;
        }
        if (Math.abs(db) < EPS) {
            out.add(new Point(b.x, b.y));
            return;
        }

        double t = (z - a.z) / (b.z - a.z);
        if (t >= -EPS && t <= 1 + EPS)
            out.add(new Point(a.x + t * (b.x - a.x),
                    a.y + t * (b.y - a.y)));
    }

    private static void dedupe(List<Point> points) {
        for (int i = points.size() - 1; i >= 0; i--) {
            for (int j = 0; j < i; j++) {
                if (distance(points.get(i), points.get(j)) < JOIN_TOLERANCE * 0.25) {
                    points.remove(i);
                    break;
                }
            }
        }
    }

    private static List<List<Point>> stitchLoops(List<Segment> segments) {
        List<List<Point>> loops = new ArrayList<>();
        if (segments.isEmpty()) return loops;

        Map<Long, List<Integer>> byEndpoint = new HashMap<>();
        for (int i = 0; i < segments.size(); i++) {
            byEndpoint.computeIfAbsent(key(segments.get(i).a), k -> new ArrayList<>()).add(i);
            byEndpoint.computeIfAbsent(key(segments.get(i).b), k -> new ArrayList<>()).add(i);
        }

        boolean[] used = new boolean[segments.size()];
        for (int start = 0; start < segments.size(); start++) {
            if (used[start]) continue;

            Segment s = segments.get(start);
            used[start] = true;
            List<Point> path = new ArrayList<>();
            path.add(s.a);
            path.add(s.b);

            Point current = s.b;
            boolean closed = false;
            int guard = 0;

            while (guard++ < segments.size() + 5) {
                if (path.size() >= 3 && distance(current, path.get(0)) <= JOIN_TOLERANCE) {
                    path.remove(path.size() - 1);
                    closed = true;
                    break;
                }

                Integer nextIndex = findUnusedAt(current, byEndpoint, segments, used);
                if (nextIndex == null) break;

                Segment next = segments.get(nextIndex);
                used[nextIndex] = true;
                Point nextPoint = distance(current, next.a) <= distance(current, next.b)
                        ? next.b : next.a;
                path.add(nextPoint);
                current = nextPoint;
            }

            if (closed) {
                path = simplifyLoop(path);
                if (path.size() >= 3 && Math.abs(signedArea(path)) > 0.01)
                    loops.add(path);
            }
        }
        return loops;
    }

    private static List<Point> simplifyLoop(List<Point> input) {
        List<Point> p = new ArrayList<>();
        for (Point q : input) {
            if (p.isEmpty() || distance(p.get(p.size() - 1), q) > JOIN_TOLERANCE * 0.25)
                p.add(q);
        }

        if (p.size() > 1 && distance(p.get(0), p.get(p.size() - 1)) <= JOIN_TOLERANCE * 0.25)
            p.remove(p.size() - 1);

        boolean changed = true;
        int passes = 0;
        while (changed && p.size() >= 4 && passes++ < 3) {
            changed = false;
            for (int i = p.size() - 1; i >= 0; i--) {
                Point a = p.get((i - 1 + p.size()) % p.size());
                Point b = p.get(i);
                Point c = p.get((i + 1) % p.size());
                double abx = b.x - a.x, aby = b.y - a.y;
                double bcx = c.x - b.x, bcy = c.y - b.y;
                double cross = Math.abs(abx * bcy - aby * bcx);
                double scale = Math.max(1.0,
                        Math.hypot(abx, aby) + Math.hypot(bcx, bcy));

                if (cross <= 1e-5 * scale) {
                    p.remove(i);
                    changed = true;
                    if (p.size() < 4) break;
                }
            }
        }
        return p;
    }

    private static Integer findUnusedAt(Point p,
                                        Map<Long, List<Integer>> byEndpoint,
                                        List<Segment> segments,
                                        boolean[] used) {
        long px = Math.round(p.x / JOIN_TOLERANCE);
        long py = Math.round(p.y / JOIN_TOLERANCE);

        for (long dx = -1; dx <= 1; dx++) {
            for (long dy = -1; dy <= 1; dy++) {
                List<Integer> candidates = byEndpoint.get(combine(px + dx, py + dy));
                if (candidates == null) continue;
                for (int idx : candidates) {
                    if (used[idx]) continue;
                    Segment s = segments.get(idx);
                    if (distance(p, s.a) <= JOIN_TOLERANCE ||
                            distance(p, s.b) <= JOIN_TOLERANCE)
                        return idx;
                }
            }
        }
        return null;
    }

    private static long key(Point p) {
        return combine(Math.round(p.x / JOIN_TOLERANCE),
                Math.round(p.y / JOIN_TOLERANCE));
    }

    private static long combine(long x, long y) {
        return (x << 32) ^ (y & 0xffffffffL);
    }

    private static Bounds boundsOfLoops(List<List<Point>> loops) {
        Bounds b = new Bounds();
        for (List<Point> loop : loops)
            for (Point p : loop) b.add(p);
        return b;
    }

    private static double distance(Point a, Point b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    private static Mesh readStl(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = input.read(buf)) != -1) {
            bytes.write(buf, 0, n);
            if (bytes.size() > 50_000_000)
                throw new IOException("STL больше 50 МБ");
        }
        byte[] data = bytes.toByteArray();

        if (data.length >= 84) {
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            long count = Integer.toUnsignedLong(b.getInt(80));
            if (count > 0 && 84L + count * 50L == data.length) {
                if (count > 300_000)
                    throw new IOException("Слишком сложная модель: больше 300000 треугольников");

                float[] tris = new float[(int)count * 11];
                b.position(84);

                for (int i = 0; i < count; i++) {
                    b.position(b.position() + 12); // normal
                    int base = i * 11;
                    float minZ = Float.POSITIVE_INFINITY;
                    float maxZ = Float.NEGATIVE_INFINITY;

                    for (int j = 0; j < 9; j++) {
                        float v = b.getFloat();
                        if (!Float.isFinite(v))
                            throw new IOException("STL содержит неверные координаты");
                        tris[base + j] = v;
                        if (j % 3 == 2) {
                            minZ = Math.min(minZ, v);
                            maxZ = Math.max(maxZ, v);
                        }
                    }
                    tris[base + 9] = minZ;
                    tris[base + 10] = maxZ;
                    b.position(b.position() + 2);
                }
                return makeMesh(tris);
            }
        }

        String text = new String(data, StandardCharsets.US_ASCII);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^\\s*vertex\\s+([-+\\d.eE]+)\\s+([-+\\d.eE]+)\\s+([-+\\d.eE]+)")
                .matcher(text);

        List<Float> values = new ArrayList<>();
        while (m.find()) {
            for (int i = 1; i <= 3; i++) {
                try {
                    values.add(Float.parseFloat(m.group(i)));
                } catch (NumberFormatException e) {
                    throw new IOException("Неверные координаты ASCII STL", e);
                }
            }
            if (values.size() > 2_700_000)
                throw new IOException("Слишком сложная модель");
        }

        if (values.isEmpty() || values.size() % 9 != 0)
            throw new IOException("Не удалось прочитать STL");

        int triangleCount = values.size() / 9;
        float[] tris = new float[triangleCount * 11];

        for (int t = 0; t < triangleCount; t++) {
            float minZ = Float.POSITIVE_INFINITY;
            float maxZ = Float.NEGATIVE_INFINITY;
            for (int j = 0; j < 9; j++) {
                float v = values.get(t * 9 + j);
                tris[t * 11 + j] = v;
                if (j % 3 == 2) {
                    minZ = Math.min(minZ, v);
                    maxZ = Math.max(maxZ, v);
                }
            }
            tris[t * 11 + 9] = minZ;
            tris[t * 11 + 10] = maxZ;
        }
        return makeMesh(tris);
    }

    private static Mesh makeMesh(float[] tris) throws IOException {
        Mesh mesh = new Mesh();
        mesh.triangles = tris;
        mesh.minX = mesh.minY = mesh.minZ = Double.POSITIVE_INFINITY;
        mesh.maxX = mesh.maxY = mesh.maxZ = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < tris.length; i += 11) {
            for (int v = 0; v < 9; v += 3) {
                double x = tris[i + v];
                double y = tris[i + v + 1];
                double z = tris[i + v + 2];
                mesh.minX = Math.min(mesh.minX, x);
                mesh.maxX = Math.max(mesh.maxX, x);
                mesh.minY = Math.min(mesh.minY, y);
                mesh.maxY = Math.max(mesh.maxY, y);
                mesh.minZ = Math.min(mesh.minZ, z);
                mesh.maxZ = Math.max(mesh.maxZ, z);
            }
        }

        if (!Double.isFinite(mesh.minX)) throw new IOException("STL пуст");
        return mesh;
    }

    private static final class Mesh {
        float[] triangles;
        double minX, minY, minZ, maxX, maxY, maxZ;
    }

    private static final class Point3 {
        final double x, y, z;
        Point3(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final class Point {
        final double x, y;
        Point(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final class Segment {
        final Point a, b;
        Segment(Point a, Point b) {
            this.a = a;
            this.b = b;
        }
    }

    private static final class Line {
        final Point a, b;
        final double nx, ny;
        Line(Point a, Point b, double nx, double ny) {
            this.a = a;
            this.b = b;
            this.nx = nx;
            this.ny = ny;
        }
    }

    private static final class Bounds {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;

        void add(Point p) {
            minX = Math.min(minX, p.x);
            maxX = Math.max(maxX, p.x);
            minY = Math.min(minY, p.y);
            maxY = Math.max(maxY, p.y);
        }

        boolean isValid() {
            return Double.isFinite(minX) && Double.isFinite(minY) &&
                    Double.isFinite(maxX) && Double.isFinite(maxY) &&
                    maxX > minX && maxY > minY;
        }
    }
}
