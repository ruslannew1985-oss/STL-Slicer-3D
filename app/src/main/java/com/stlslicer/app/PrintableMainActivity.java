package com.stlslicer.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.io.*;
import java.lang.reflect.Field;
import java.util.Locale;

/**
 * Keeps the existing UI/features from MainActivity, but replaces only the
 * final G-code save path with a validated Ender-3 V3 SE compatible writer.
 */
public class PrintableMainActivity extends MainActivity {

    private static final int REQ_SAVE_PRINTABLE_GCODE = 9101;

    private File pendingSource;
    private double pendingShiftX;
    private double pendingShiftY;
    private double pendingNozzle;
    private double pendingBed;
    private String pendingSuggestedName;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installPrintReadySaveButton();
    }

    private void installPrintReadySaveButton() {
        try {
            Button save = getField("saveGcodeButton", Button.class);
            save.setOnClickListener(v -> preparePrintReadySave());
        } catch (Exception e) {
            setStatus("Ошибка настройки сохранения: " + safe(e));
        }
    }

    private void preparePrintReadySave() {
        try {
            File source = getField("generatedGcode", File.class);
            if (source == null || !source.isFile()) {
                setStatus("Сначала создайте G-code");
                return;
            }

            Ender3V3SEGcodeWriter.Validation validation =
                    Ender3V3SEGcodeWriter.validate(source);
            if (!validation.isPrintable()) {
                setStatus("Этот G-code непечатный: не найдены реальные движения экструдера. Создайте G-code заново.");
                return;
            }

            Gcode3DViewer viewer = getField("gcodeViewer", Gcode3DViewer.class);
            EditText nozzle = getField("nozzle", EditText.class);
            EditText bed = getField("bed", EditText.class);

            pendingSource = source;
            pendingShiftX = viewer.getOffsetX();
            pendingShiftY = viewer.getOffsetY();
            pendingNozzle = parseNumber(nozzle.getText().toString(), 235.0);
            pendingBed = parseNumber(bed.getText().toString(), 75.0);
            pendingSuggestedName = makeSafePrinterName();

            setStatus(String.format(Locale.US,
                    "Проверка пройдена: %d слоёв, %d печатных движений. Сохраняю файл для Ender-3 V3 SE...",
                    validation.layers, validation.extrusionMoves));

            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_TITLE, pendingSuggestedName);
            startActivityForResult(intent, REQ_SAVE_PRINTABLE_GCODE);
        } catch (Exception e) {
            setStatus("Не удалось подготовить G-code: " + safe(e));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_SAVE_PRINTABLE_GCODE) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }

        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri destination = data.getData();

        final File source = pendingSource;
        final double shiftX = pendingShiftX;
        final double shiftY = pendingShiftY;
        final double nozzle = pendingNozzle;
        final double bed = pendingBed;

        if (source == null || !source.isFile()) {
            setStatus("Исходный G-code потерян. Создайте его заново.");
            return;
        }

        setStatus("Формирую печатный G-code для Ender-3 V3 SE...");

        new Thread(() -> {
            File checked = new File(getCacheDir(), "ender3v3se_print_ready.gcode");
            try {
                Ender3V3SEGcodeWriter.Validation sourceStats;
                try (OutputStream tempOut = new BufferedOutputStream(new FileOutputStream(checked))) {
                    sourceStats = Ender3V3SEGcodeWriter.write(
                            source, tempOut, shiftX, shiftY, nozzle, bed);
                }

                Ender3V3SEGcodeWriter.Validation finalStats =
                        Ender3V3SEGcodeWriter.validate(checked);
                if (!finalStats.isPrintable() || checked.length() < 500) {
                    throw new IOException("финальный файл не прошёл проверку печатных команд");
                }

                try (InputStream in = new BufferedInputStream(new FileInputStream(checked));
                     OutputStream out = getContentResolver().openOutputStream(destination, "w")) {
                    if (out == null) throw new IOException("не удалось открыть файл на устройстве");
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                    out.flush();
                }

                long size = checked.length();
                runOnUiThread(() -> setStatus(String.format(Locale.US,
                        "ГОТОВО ДЛЯ ПЕЧАТИ: %d слоёв, %d команд экструзии, %.1f КБ. " +
                                "На флешке используйте короткое имя латиницей и расширение .gcode.",
                        sourceStats.layers, sourceStats.extrusionMoves, size / 1024.0)));
            } catch (Exception e) {
                runOnUiThread(() -> setStatus(
                        "G-code НЕ сохранён как готовый: " + safe(e)));
            } finally {
                if (checked.exists()) checked.delete();
            }
        }, "gcode-save").start();
    }

    private String makeSafePrinterName() {
        String name = null;
        try { name = getField("selectedModelName", String.class); }
        catch (Exception ignored) {}

        if (name == null) name = "MODEL";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);

        name = name.toUpperCase(Locale.US)
                .replaceAll("[^A-Z0-9_-]+", "_")
                .replaceAll("_+", "_");
        while (name.startsWith("_")) name = name.substring(1);
        while (name.endsWith("_")) name = name.substring(0, name.length() - 1);
        if (name.isEmpty()) name = "MODEL";
        if (name.length() > 24) name = name.substring(0, 24);
        return name + ".gcode";
    }

    private double parseNumber(String text, double fallback) {
        try {
            return Double.parseDouble(text.trim().replace(',', '.'));
        } catch (Exception e) {
            return fallback;
        }
    }

    private void setStatus(String message) {
        try {
            TextView status = getField("status", TextView.class);
            status.setText(message);
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("unchecked")
    private <T> T getField(String name, Class<T> type) throws Exception {
        Field f = MainActivity.class.getDeclaredField(name);
        f.setAccessible(true);
        Object value = f.get(this);
        if (value == null) return null;
        return (T) value;
    }

    private String safe(Throwable e) {
        String m = e.getMessage();
        return (m == null || m.trim().isEmpty()) ? e.getClass().getSimpleName() : m;
    }
}
