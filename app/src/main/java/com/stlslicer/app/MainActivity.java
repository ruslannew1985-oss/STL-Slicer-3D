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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_OPEN_STL = 10;
    private static final int REQ_SAVE_GCODE = 11;

    private TextView fileInfo, status, layerLabel, gcodeHint;
    private EditText nozzle, bed, layer, speed, infill;
    private Button sliceButton, resetGcodeView;
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
        printer.setText("Creality Ender-3 V3 SE | PETG | офлайн");
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

        TextView settings = new TextView(this);
        settings.setText("Параметры печати PETG");
        settings.setTextSize(21);
        settings.setTextColor(Color.BLACK);
        root.addView(settings);

        nozzle = addField(root, "Температура сопла (°C)", "235");
        bed = addField(root, "Температура стола (°C)", "75");
        layer = addField(root, "Высота слоя (мм)", "0.2");
        speed = addField(root, "Скорость печати (мм/с)", "50");
        infill = addField(root, "Заполнение (%)", "20");

        sliceButton = new Button(this);
        sliceButton.setText("СОЗДАТЬ G-CODE");
        root.addView(sliceButton);

        status = new TextView(this);
        status.setText("Ожидание STL-файла");
        status.setTextSize(16);
        root.addView(status);

        layerLabel = new TextView(this);
        layerLabel.setText("3D-просмотр G-code");
        layerLabel.setTextSize(20);
        layerLabel.setTextColor(Color.BLACK);
        layerLabel.setVisibility(View.GONE);
        root.addView(layerLabel);

        gcodeHint = new TextView(this);
        gcodeHint.setText("Вращение — одним пальцем. Масштаб — двумя пальцами. Ползунок показывает модель до выбранного слоя.");
        gcodeHint.setTextSize(14);
        gcodeHint.setVisibility(View.GONE);
        root.addView(gcodeHint);

        layerSeek = new SeekBar(this);
        layerSeek.setVisibility(View.GONE);
        root.addView(layerSeek);

        resetGcodeView = new Button(this);
        resetGcodeView.setText("СБРОСИТЬ 3D-ВИД");
        resetGcodeView.setVisibility(View.GONE);
        root.addView(resetGcodeView);

        gcodeViewer = new Gcode3DViewer(this);
        gcodeViewer.setVisibility(View.GONE);
        root.addView(gcodeViewer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                760
        ));

        layerSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                gcodeViewer.setLayer(progress);
                layerLabel.setText("3D G-code: слой " + (progress + 1) + " из " + gcodeViewer.getLayerCount());
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        resetGcodeView.setOnClickListener(v -> gcodeViewer.resetView());
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
                    if (current == 1 || current == total || current % Math.max(1, total / 20) == 0) {
                        int percent = (int)Math.round(current * 100.0 / total);
                        runOnUiThread(() ->
                                status.setText("Слайсинг: " + percent + "% (" + current + "/" + total + " слоёв)")
                        );
                    }
                });

                generatedGcode = temp;
                int previewLayers = gcodeViewer.loadGcode(temp);

                runOnUiThread(() -> {
                    sliceButton.setEnabled(true);
                    showGcodePreview(previewLayers);
                    status.setText("G-code готов. Проверьте объёмную модель по слоям и сохраните файл.");
                    askWhereToSave();
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
        layerLabel.setText("3D G-code: слой " + count + " из " + count);
        layerLabel.setVisibility(View.VISIBLE);
        gcodeHint.setVisibility(View.VISIBLE);
        layerSeek.setVisibility(View.VISIBLE);
        resetGcodeView.setVisibility(View.VISIBLE);
        gcodeViewer.setVisibility(View.VISIBLE);
    }

    private void hideGcodePreview() {
        layerLabel.setVisibility(View.GONE);
        gcodeHint.setVisibility(View.GONE);
        layerSeek.setVisibility(View.GONE);
        resetGcodeView.setVisibility(View.GONE);
        gcodeViewer.setVisibility(View.GONE);
    }

    private SlicerEngine.Settings readSettings() {
        SlicerEngine.Settings s = new SlicerEngine.Settings();
        s.nozzleTemperature = parseNumber(nozzle, "температура сопла");
        s.bedTemperature = parseNumber(bed, "температура стола");
        s.layerHeight = parseNumber(layer, "высота слоя");
        s.printSpeed = parseNumber(speed, "скорость");
        s.infillPercent = parseNumber(infill, "заполнение");

        s.nozzleDiameter = 0.4;
        s.filamentDiameter = 1.75;
        s.perimeterCount = 2;
        s.travelSpeed = 150;
        s.extrusionMultiplier = 1.0;
        s.bedSizeX = 220;
        s.bedSizeY = 220;
        return s;
    }

    private double parseNumber(EditText field, String name) {
        String text = field.getText().toString().trim().replace(',', '.');
        if (text.isEmpty()) throw new IllegalArgumentException("не заполнено поле: " + name);
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("неверное число: " + name);
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

        status.setText("Сохранение G-code...");
        worker.execute(() -> {
            try (InputStream in = new BufferedInputStream(new FileInputStream(generatedGcode));
                 OutputStream out = getContentResolver().openOutputStream(destination, "w")) {

                if (out == null) throw new IOException("Не удалось открыть место сохранения");

                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                out.flush();

                String savedName = getDisplayName(destination);
                runOnUiThread(() -> {
                    if (savedName != null && savedName.toLowerCase().endsWith(".gcode")) {
                        status.setText("Готово: " + savedName + ". 3D-просмотр остаётся доступен ниже.");
                    } else {
                        status.setText("G-code сохранён. 3D-просмотр остаётся доступен ниже.");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() ->
                        status.setText("Ошибка сохранения: " + safeMessage(e))
                );
            }
        });
    }

    private String safeMessage(Throwable e) {
        String msg = e.getMessage();
        return (msg == null || msg.trim().isEmpty()) ? e.getClass().getSimpleName() : msg;
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
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }

            String fileName = getSelectedFileName();
            fileInfo.setText(fileName == null ? "STL выбран" : fileName);

            try {
                stlViewer.loadSTL(uri);
                status.setText("3D-модель загружена. Вращайте пальцем или создайте G-code.");
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
