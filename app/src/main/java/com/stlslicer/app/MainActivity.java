package com.stlslicer.app;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.graphics.Color;
import android.view.View;
import android.widget.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final int REQ_OPEN_STL = 10;
    private static final int REQ_SAVE_GCODE = 11;
    private static final Pattern XY_TOKEN = Pattern.compile(
            "(?i)([XY])\\s*([-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?)");

    private TextView fileInfo, status, layerLabel, gcodeHint, gcodePosition;
    private EditText nozzle, bed, layer, speed, infill;
    private EditText bottomLayers, topLayers, wallLines, fanLayer;
    private Button sliceButton, resetGcodeView, placementModeButton;
    private Button centerGcodeButton, saveGcodeButton;
    private SeekBar layerSeek;
    private Uri selectedFile;
    private STLViewer stlViewer;
    private Gcode3DViewer gcodeViewer;
    private File generatedGcode;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private EditText addField(LinearLayout root, String label, String value) {
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(16);
        root.addView(text);

        EditText field = new EditText(this);
        field.setInputType(8194);
        field.setText(value);
        root.addView(field);
        return field;
    }

    private TextView addSection(LinearLayout root, String title) {
        TextView text = new TextView(this);
        text.setText(title);
        text.setTextSize(21);
        text.setTextColor(Color.BLACK);
        text.setPadding(0, 22, 0, 8);
        root.addView(text);
        return text;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 24, 28, 28);
        scroll.addView(root);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("STL Slicer 3D");
        title.setTextSize(28);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        TextView printer = new TextView(this);
        printer.setText("Creality Ender-3 V3 SE | PETG | стол 220×220 мм | офлайн");
        printer.setTextSize(17);
        root.addView(printer);

        Button open = new Button(this);
        open.setText("ОТКРЫТЬ STL");
        root.addView(open);

        fileInfo = new TextView(this);
        fileInfo.setText("Файл не выбран");
        fileInfo.setTextSize(16);
        root.addView(fileInfo);

        stlViewer = new STLViewer(this);
        root.addView(stlViewer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                600
        ));

        addSection(root, "Параметры печати PETG");

        nozzle = addField(root, "Температура сопла (°C)", "235");
        bed = addField(root, "Температура стола (°C)", "75");
        layer = addField(root, "Высота слоя (мм)", "0.2");
        speed = addField(root, "Скорость печати (мм/с)", "50");
        infill = addField(root, "Заполнение внутри (%)", "30");
        bottomLayers = addField(root, "Сплошных нижних слоёв", "3");
        topLayers = addField(root, "Сплошных верхних слоёв", "3");
        wallLines = addField(root, "Периметры стенок (наружные + внутренние), линий", "3");
        fanLayer = addField(root, "Вентилятор включить с слоя", "4");

        sliceButton = new Button(this);
        sliceButton.setText("СОЗДАТЬ G-CODE");
        root.addView(sliceButton);

        status = new TextView(this);
        status.setText("Ожидание STL-файла");
        status.setTextSize(16);
        root.addView(status);

        addSection(root, "Готовый G-code на столе 220×220 мм");

        layerLabel = new TextView(this);
        layerLabel.setText("3D-просмотр G-code");
        layerLabel.setTextSize(20);
        layerLabel.setTextColor(Color.BLACK);
        layerLabel.setVisibility(View.GONE);
        root.addView(layerLabel);

        gcodeHint = new TextView(this);
        gcodeHint.setText("После слайсинга здесь появится стол. Сначала расположите готовый G-code, затем сохраните файл.");
        gcodeHint.setTextSize(14);
        gcodeHint.setVisibility(View.GONE);
        root.addView(gcodeHint);

        gcodePosition = new TextView(this);
        gcodePosition.setText("Центр G-code: X110.0 Y110.0 мм");
        gcodePosition.setTextSize(16);
        gcodePosition.setTextColor(Color.BLACK);
        gcodePosition.setVisibility(View.GONE);
        root.addView(gcodePosition);

        layerSeek = new SeekBar(this);
        layerSeek.setVisibility(View.GONE);
        root.addView(layerSeek);

        placementModeButton = new Button(this);
        placementModeButton.setText("РАЗМЕСТИТЬ НА СТОЛЕ");
        placementModeButton.setVisibility(View.GONE);
        root.addView(placementModeButton);

        centerGcodeButton = new Button(this);
        centerGcodeButton.setText("ПО ЦЕНТРУ СТОЛА");
        centerGcodeButton.setVisibility(View.GONE);
        root.addView(centerGcodeButton);

        resetGcodeView = new Button(this);
        resetGcodeView.setText("СБРОСИТЬ 3D-ВИД");
        resetGcodeView.setVisibility(View.GONE);
        root.addView(resetGcodeView);

        gcodeViewer = new Gcode3DViewer(this);
        gcodeViewer.setVisibility(View.GONE);
        root.addView(gcodeViewer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                780
        ));

        saveGcodeButton = new Button(this);
        saveGcodeButton.setText("СОХРАНИТЬ ГОТОВЫЙ G-CODE");
        saveGcodeButton.setVisibility(View.GONE);
        root.addView(saveGcodeButton);

        layerSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                gcodeViewer.setLayer(progress);
                layerLabel.setText("3D G-code: слой " + (progress + 1) +
                        " из " + gcodeViewer.getLayerCount());
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        gcodeViewer.setOnPlacementChangedListener((x, y) -> runOnUiThread(() ->
                gcodePosition.setText(String.format(Locale.US,
                        "Центр G-code: X%.1f  Y%.1f мм", x, y))));

        placementModeButton.setOnClickListener(v -> {
            boolean enable = !gcodeViewer.isPlacementMode();
            gcodeViewer.setPlacementMode(enable);
            if (enable) {
                placementModeButton.setText("ГОТОВО — ВЕРНУТЬСЯ В 3D");
                gcodeHint.setText("Режим размещения: вид сверху. Двигайте готовую деталь одним пальцем по столу. Масштаб — двумя пальцами.");
            } else {
                placementModeButton.setText("РАЗМЕСТИТЬ НА СТОЛЕ");
                gcodeHint.setText("3D-режим: вращение одним пальцем, масштаб двумя. Кнопка «Разместить на столе» возвращает вид сверху.");
            }
        });

        centerGcodeButton.setOnClickListener(v -> gcodeViewer.centerOnBed());
        resetGcodeView.setOnClickListener(v -> {
            gcodeViewer.resetView();
            placementModeButton.setText("РАЗМЕСТИТЬ НА СТОЛЕ");
            gcodeHint.setText("3D-режим: вращение одним пальцем, масштаб двумя. Кнопка «Разместить на столе» возвращает вид сверху.");
        });
        saveGcodeButton.setOnClickListener(v -> askWhereToSave());
        open.setOnClickListener(v -> openStl());
        sliceButton.setOnClickListener(v -> startSlicing());
    }

    private void openStl() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQ_OPEN_STL);
    }

    private void startSlicing() {
        if (selectedFile == null) {
            status.setText("Сначала выберите STL-файл");
            return;
        }

        final SlicerEngine.Settings settings;
        try {
            settings = readSettings();
            settings.validate();
        } catch (Exception e) {
            status.setText("Ошибка параметров: " + safeMessage(e));
            return;
        }

        sliceButton.setEnabled(false);
        hideGcodePreview();
        status.setText("Подготовка модели...");

        worker.execute(() -> {
            File temp = new File(getCacheDir(), "stl_slicer_output.gcode");
            try (InputStream in = getContentResolver().openInputStream(selectedFile);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(temp))) {

                if (in == null) throw new IOException("Не удалось открыть STL");

                SlicerEngine.slice(in, out, settings, (current, total) -> {
                    if (current == 1 || current == total ||
                            current % Math.max(1, total / 20) == 0) {
                        int percent = (int)Math.round(current * 100.0 / total);
                        runOnUiThread(() -> status.setText(
                                "Слайсинг: " + percent + "% (" + current +
                                        "/" + total + " слоёв)"));
                    }
                });

                generatedGcode = temp;
                int previewLayers = gcodeViewer.loadGcode(temp);

                runOnUiThread(() -> {
                    sliceButton.setEnabled(true);
                    showGcodePreview(previewLayers);
                    status.setText("G-code готов. Расположите уже нарезанную деталь на столе и только потом нажмите «Сохранить готовый G-code».");
                });
            } catch (Exception e) {
                if (temp.exists()) temp.delete();
                runOnUiThread(() -> {
                    sliceButton.setEnabled(true);
                    hideGcodePreview();
                    status.setText("Ошибка слайсинга: " + safeMessage(e));
                });
            }
        });
    }

    private void showGcodePreview(int count) {
        if (count <= 0) {
            hideGcodePreview();
            return;
        }

        int last = count - 1;
        layerSeek.setMax(last);
        layerSeek.setProgress(last);
        gcodeViewer.setLayer(last);
        gcodeViewer.setPlacementMode(true);

        layerLabel.setText("3D G-code: слой " + count + " из " + count);
        gcodePosition.setText(String.format(Locale.US,
                "Центр G-code: X%.1f  Y%.1f мм",
                gcodeViewer.getModelCenterX(), gcodeViewer.getModelCenterY()));
        placementModeButton.setText("ГОТОВО — ВЕРНУТЬСЯ В 3D");
        gcodeHint.setText("Режим размещения: вид сверху. Двигайте готовую деталь одним пальцем по столу. Масштаб — двумя пальцами.");

        layerLabel.setVisibility(View.VISIBLE);
        gcodeHint.setVisibility(View.VISIBLE);
        gcodePosition.setVisibility(View.VISIBLE);
        layerSeek.setVisibility(View.VISIBLE);
        placementModeButton.setVisibility(View.VISIBLE);
        centerGcodeButton.setVisibility(View.VISIBLE);
        resetGcodeView.setVisibility(View.VISIBLE);
        gcodeViewer.setVisibility(View.VISIBLE);
        saveGcodeButton.setVisibility(View.VISIBLE);
    }

    private void hideGcodePreview() {
        layerLabel.setVisibility(View.GONE);
        gcodeHint.setVisibility(View.GONE);
        gcodePosition.setVisibility(View.GONE);
        layerSeek.setVisibility(View.GONE);
        placementModeButton.setVisibility(View.GONE);
        centerGcodeButton.setVisibility(View.GONE);
        resetGcodeView.setVisibility(View.GONE);
        gcodeViewer.setVisibility(View.GONE);
        saveGcodeButton.setVisibility(View.GONE);
    }

    private SlicerEngine.Settings readSettings() {
        SlicerEngine.Settings s = new SlicerEngine.Settings();
        s.nozzleTemperature = parseNumber(nozzle, "температура сопла");
        s.bedTemperature = parseNumber(bed, "температура стола");
        s.layerHeight = parseNumber(layer, "высота слоя");
        s.printSpeed = parseNumber(speed, "скорость");
        s.infillPercent = parseNumber(infill, "заполнение");
        s.bottomSolidLayers = parseInteger(bottomLayers, "нижние слои");
        s.topSolidLayers = parseInteger(topLayers, "верхние слои");
        s.perimeterCount = parseInteger(wallLines, "периметры стенок");
        s.fanStartLayer = parseInteger(fanLayer, "слой включения вентилятора");

        // Нарезка всегда начинается по центру стола. После слайсинга пользователь
        // двигает уже готовый G-code, и смещение записывается при сохранении.
        s.modelCenterX = 110;
        s.modelCenterY = 110;
        s.nozzleDiameter = 0.4;
        s.filamentDiameter = 1.75;
        s.travelSpeed = 150;
        s.extrusionMultiplier = 1.0;
        s.bedSizeX = 220;
        s.bedSizeY = 220;
        s.fanSpeed = 255;
        return s;
    }

    private double parseNumber(EditText field, String name) {
        String text = field.getText().toString().trim().replace(',', '.');
        if (text.isEmpty())
            throw new IllegalArgumentException("не заполнено поле: " + name);
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("неверное число: " + name);
        }
    }

    private int parseInteger(EditText field, String name) {
        String text = field.getText().toString().trim();
        if (text.isEmpty())
            throw new IllegalArgumentException("не заполнено поле: " + name);
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("нужно целое число: " + name);
        }
    }

    private void askWhereToSave() {
        if (generatedGcode == null || !generatedGcode.exists()) {
            status.setText("G-code не найден");
            return;
        }

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, makeGcodeName());
        startActivityForResult(intent, REQ_SAVE_GCODE);
    }

    private String makeGcodeName() {
        String name = getSelectedFileName();
        if (name == null || name.trim().isEmpty()) return "model.gcode";
        String lower = name.toLowerCase();
        if (lower.endsWith(".stl")) name = name.substring(0, name.length() - 4);
        return name + ".gcode";
    }

    private String getSelectedFileName() {
        if (selectedFile == null) return null;
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(selectedFile, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    private String getDisplayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    private void saveGeneratedGcode(Uri destination) {
        if (generatedGcode == null || !generatedGcode.exists()) {
            status.setText("Нет готового G-code");
            return;
        }

        final double shiftX = gcodeViewer.getOffsetX();
        final double shiftY = gcodeViewer.getOffsetY();
        final double centerX = gcodeViewer.getModelCenterX();
        final double centerY = gcodeViewer.getModelCenterY();

        status.setText("Сохранение G-code с выбранным положением на столе...");
        worker.execute(() -> {
            try (OutputStream raw = getContentResolver().openOutputStream(destination, "w")) {
                if (raw == null)
                    throw new IOException("Не удалось открыть место сохранения");

                writePlacedGcode(generatedGcode, raw, shiftX, shiftY);
                raw.flush();

                String savedName = getDisplayName(destination);
                runOnUiThread(() -> {
                    String base = savedName != null ? savedName : "G-code";
                    status.setText(String.format(Locale.US,
                            "Готово: %s. Положение на столе сохранено: X%.1f Y%.1f мм.",
                            base, centerX, centerY));
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText(
                        "Ошибка сохранения: " + safeMessage(e)));
            }
        });
    }

    private void writePlacedGcode(File source,
                                  OutputStream destination,
                                  double shiftX,
                                  double shiftY) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                     new FileInputStream(source), StandardCharsets.US_ASCII), 64 * 1024);
             BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(
                     destination, StandardCharsets.US_ASCII), 64 * 1024)) {

            bw.write(String.format(Locale.US,
                    "; Final bed placement offset: X%.3f Y%.3f mm%n",
                    shiftX, shiftY));

            boolean absoluteXYZ = true;
            String line;
            while ((line = br.readLine()) != null) {
                String command = firstWord(line).toUpperCase(Locale.US);

                if (command.equals("G90")) {
                    absoluteXYZ = true;
                } else if (command.equals("G91")) {
                    absoluteXYZ = false;
                } else if (absoluteXYZ && (command.equals("G0") || command.equals("G00") ||
                        command.equals("G1") || command.equals("G01"))) {
                    line = shiftXY(line, shiftX, shiftY);
                }

                bw.write(line);
                bw.newLine();
            }
            bw.flush();
        }
    }

    private String shiftXY(String line, double dx, double dy) {
        int commentAt = line.indexOf(';');
        String code = commentAt >= 0 ? line.substring(0, commentAt) : line;
        String comment = commentAt >= 0 ? line.substring(commentAt) : "";

        Matcher m = XY_TOKEN.matcher(code);
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

    private String firstWord(String line) {
        String t = line.trim();
        if (t.isEmpty() || t.startsWith(";")) return "";
        int space = t.indexOf(' ');
        int tab = t.indexOf('\t');
        int end;
        if (space < 0) end = tab;
        else if (tab < 0) end = space;
        else end = Math.min(space, tab);
        return end < 0 ? t : t.substring(0, end);
    }

    private String safeMessage(Throwable e) {
        String msg = e.getMessage();
        return (msg == null || msg.trim().isEmpty())
                ? e.getClass().getSimpleName() : msg;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_OPEN_STL && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;

            selectedFile = uri;
            generatedGcode = null;
            hideGcodePreview();

            try {
                int flags = data.getFlags() &
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(
                        uri, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }

            String fileName = getSelectedFileName();
            fileInfo.setText(fileName == null ? "STL выбран" : fileName);

            try {
                stlViewer.loadSTL(uri);
                status.setText("3D-модель загружена. Задайте параметры печати и создайте G-code.");
            } catch (Exception e) {
                selectedFile = null;
                fileInfo.setText("Ошибка открытия STL");
                status.setText(safeMessage(e));
            }
            return;
        }

        if (requestCode == REQ_SAVE_GCODE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) saveGeneratedGcode(uri);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }
}
