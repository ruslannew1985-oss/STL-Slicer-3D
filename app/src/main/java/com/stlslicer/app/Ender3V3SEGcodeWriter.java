package com.stlslicer.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts the slicer's internal toolpath file into a conservative Marlin
 * G-code file intended for Creality Ender-3 V3 SE SD-card printing.
 *
 * The model placement offset is applied only between ;LAYER:0 and ;END, so
 * purge/presentation moves are never shifted together with the model.
 */
public final class Ender3V3SEGcodeWriter {

    private static final Pattern AXIS = Pattern.compile(
            "(?i)([XYZE])\\s*([-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?)");
    private static final Pattern XY = Pattern.compile(
            "(?i)([XY])\\s*([-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?)");

    private Ender3V3SEGcodeWriter() {}

    public static final class Validation {
        public final int layers;
        public final int extrusionMoves;
        public final double maxExtrusion;
        public final long bytes;

        Validation(int layers, int extrusionMoves, double maxExtrusion, long bytes) {
            this.layers = layers;
            this.extrusionMoves = extrusionMoves;
            this.maxExtrusion = maxExtrusion;
            this.bytes = bytes;
        }

        public boolean isPrintable() {
            return layers > 0 && extrusionMoves >= 3 && maxExtrusion > 0.05 && bytes > 200;
        }
    }

    public static Validation validate(File source) throws IOException {
        if (source == null || !source.isFile()) throw new IOException("G-code файл не найден");

        int layers = 0;
        int extrusionMoves = 0;
        double maxE = 0.0;
        double currentE = 0.0;
        boolean absoluteE = true;

        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(source), StandardCharsets.US_ASCII), 64 * 1024)) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith(";LAYER:")) layers++;

                String cmd = firstWord(t).toUpperCase(Locale.US);
                if ("M82".equals(cmd)) { absoluteE = true; continue; }
                if ("M83".equals(cmd)) { absoluteE = false; continue; }
                if ("G92".equals(cmd)) {
                    Double e = valueOf(t, 'E');
                    if (e != null) currentE = e;
                    continue;
                }

                if ("G0".equals(cmd) || "G00".equals(cmd) ||
                        "G1".equals(cmd) || "G01".equals(cmd)) {
                    Double e = valueOf(t, 'E');
                    if (e != null) {
                        double next = absoluteE ? e : currentE + e;
                        if (next > currentE + 1e-6) extrusionMoves++;
                        currentE = next;
                        maxE = Math.max(maxE, currentE);
                    }
                }
            }
        }
        return new Validation(layers, extrusionMoves, maxE, source.length());
    }

    public static Validation write(File source,
                                   OutputStream destination,
                                   double shiftX,
                                   double shiftY,
                                   double nozzleTemp,
                                   double bedTemp) throws IOException {
        Validation validation = validate(source);
        if (!validation.isPrintable()) {
            throw new IOException("G-code не содержит достаточного количества печатных движений с экструзией");
        }
        if (!Double.isFinite(nozzleTemp) || nozzleTemp < 150 || nozzleTemp > 320)
            throw new IOException("Неверная температура сопла");
        if (!Double.isFinite(bedTemp) || bedTemp < 0 || bedTemp > 130)
            throw new IOException("Неверная температура стола");

        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                     new FileInputStream(source), StandardCharsets.US_ASCII), 64 * 1024);
             BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(
                     destination, StandardCharsets.US_ASCII), 64 * 1024)) {

            writeHeader(bw, nozzleTemp, bedTemp, validation, shiftX, shiftY);

            boolean modelSection = false;
            boolean absoluteXYZ = true;
            int copiedLayers = 0;
            int copiedExtrusionMoves = 0;
            double currentE = 0.0;
            boolean absoluteE = true;

            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();

                if (trimmed.startsWith(";LAYER:")) {
                    modelSection = true;
                    copiedLayers++;
                }
                if (!modelSection) continue;
                if (trimmed.startsWith(";END")) break;

                String command = firstWord(trimmed).toUpperCase(Locale.US);
                if ("G90".equals(command)) absoluteXYZ = true;
                else if ("G91".equals(command)) absoluteXYZ = false;
                else if ("M82".equals(command)) absoluteE = true;
                else if ("M83".equals(command)) absoluteE = false;
                else if ("G92".equals(command)) {
                    Double e = valueOf(trimmed, 'E');
                    if (e != null) currentE = e;
                }

                if (absoluteXYZ && isMove(command)) {
                    line = shiftXY(line, shiftX, shiftY);
                }

                if (isMove(command)) {
                    Double e = valueOf(trimmed, 'E');
                    if (e != null) {
                        double next = absoluteE ? e : currentE + e;
                        if (next > currentE + 1e-6) copiedExtrusionMoves++;
                        currentE = next;
                    }
                }

                writeLine(bw, line);
            }

            if (copiedLayers <= 0 || copiedExtrusionMoves < 3) {
                throw new IOException("После подготовки G-code печатные слои не найдены");
            }

            writeFooter(bw);
            bw.flush();
        }

        return validation;
    }

    private static void writeHeader(BufferedWriter bw,
                                    double nozzleTemp,
                                    double bedTemp,
                                    Validation validation,
                                    double shiftX,
                                    double shiftY) throws IOException {
        writeLine(bw, ";FLAVOR:Marlin");
        writeLine(bw, ";Generated by STL Slicer 3D");
        writeLine(bw, ";TARGET_MACHINE.NAME:Creality Ender-3 V3 SE");
        writeLine(bw, ";Layers:" + validation.layers);
        writeLine(bw, String.format(Locale.US, ";Filament used: %.3fm", validation.maxExtrusion / 1000.0));
        writeLine(bw, String.format(Locale.US, ";Model placement offset X%.3f Y%.3f", shiftX, shiftY));
        writeLine(bw, "M220 S100 ; reset feedrate");
        writeLine(bw, "M221 S100 ; reset flowrate");
        writeLine(bw, "G21 ; millimeters");
        writeLine(bw, "G90 ; absolute XYZ");
        writeLine(bw, "M82 ; absolute extrusion");
        writeLine(bw, "M107 ; fan off");
        writeLine(bw, "G28 ; home all axes");
        writeLine(bw, "M420 S1 ; use saved bed mesh");
        writeLine(bw, "G92 E0 ; reset extruder");
        writeLine(bw, "G1 Z2.0 F3000");
        writeLine(bw, "G1 X2.0 Y20.0 Z0.28 F5000.0 ; purge start");
        writeLine(bw, String.format(Locale.US, "M190 S%.0f ; wait for bed", bedTemp));
        writeLine(bw, String.format(Locale.US, "M109 S%.0f ; wait for nozzle", nozzleTemp));
        writeLine(bw, "G1 X2.0 Y100.0 Z0.28 F1500.0 E15.00000 ; purge line 1");
        writeLine(bw, "G1 X3.0 Y100.0 Z0.28 F5000.0");
        writeLine(bw, "G1 X3.0 Y20.0 Z0.28 F1500.0 E30.00000 ; purge line 2");
        writeLine(bw, "G92 E0 ; reset extruder after purge");
        writeLine(bw, "G1 E-0.80000 F2400 ; retract");
        writeLine(bw, "G1 Z2.0 F3000");
        writeLine(bw, "G1 E0.00000 F2400 ; unretract");
        writeLine(bw, ";MODEL_START");
    }

    private static void writeFooter(BufferedWriter bw) throws IOException {
        writeLine(bw, ";MODEL_END");
        writeLine(bw, "M107 ; fan off");
        writeLine(bw, "M83 ; relative extrusion for safe retract");
        writeLine(bw, "G1 E-2.0 F1800");
        writeLine(bw, "G91 ; relative XYZ");
        writeLine(bw, "G1 Z5.0 F3000");
        writeLine(bw, "G90 ; absolute XYZ");
        writeLine(bw, "G1 X2.0 Y218.0 F3000 ; present print");
        writeLine(bw, "M104 S0 ; hotend off");
        writeLine(bw, "M140 S0 ; bed off");
        writeLine(bw, "M84 X Y E ; disable motors except Z");
        writeLine(bw, ";END");
    }

    private static boolean isMove(String command) {
        return "G0".equals(command) || "G00".equals(command) ||
                "G1".equals(command) || "G01".equals(command);
    }

    private static String shiftXY(String line, double dx, double dy) {
        int commentAt = line.indexOf(';');
        String code = commentAt >= 0 ? line.substring(0, commentAt) : line;
        String comment = commentAt >= 0 ? line.substring(commentAt) : "";

        Matcher m = XY.matcher(code);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            char axis = Character.toUpperCase(m.group(1).charAt(0));
            double value;
            try {
                value = Double.parseDouble(m.group(2));
            } catch (NumberFormatException e) {
                continue;
            }
            value += axis == 'X' ? dx : dy;
            String replacement = axis + String.format(Locale.US, "%.3f", value);
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString() + comment;
    }

    private static Double valueOf(String line, char wanted) {
        int comment = line.indexOf(';');
        String code = comment >= 0 ? line.substring(0, comment) : line;
        Matcher m = AXIS.matcher(code);
        while (m.find()) {
            if (Character.toUpperCase(m.group(1).charAt(0)) == Character.toUpperCase(wanted)) {
                try { return Double.parseDouble(m.group(2)); }
                catch (NumberFormatException ignored) { return null; }
            }
        }
        return null;
    }

    private static String firstWord(String line) {
        String t = line == null ? "" : line.trim();
        if (t.isEmpty() || t.startsWith(";")) return "";
        int space = t.indexOf(' ');
        int tab = t.indexOf('\t');
        int end;
        if (space < 0) end = tab;
        else if (tab < 0) end = space;
        else end = Math.min(space, tab);
        return end < 0 ? t : t.substring(0, end);
    }

    private static void writeLine(BufferedWriter bw, String line) throws IOException {
        bw.write(line == null ? "" : line);
        // Explicit CRLF is the safest choice for removable-media printer firmware.
        bw.write("\r\n");
    }
}
