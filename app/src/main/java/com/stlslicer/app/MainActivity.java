
package com.stlslicer.app;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.*;
import android.graphics.Color;
import java.io.InputStream;

public class MainActivity extends Activity {

    TextView fileInfo, status;
    EditText nozzle, bed, layer, speed, infill;
    Uri selectedFile;
STLViewer stlViewer;
LinearLayout rootLayout;
    EditText addField(LinearLayout root, String label, String value) {
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
        printer.setText("Creality Ender-3 V3 SE | PETG");
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

        Button slice = new Button(this);
        slice.setText("ПОДГОТОВИТЬ G-CODE");
        root.addView(slice);

        status = new TextView(this);
        status.setText("Ожидание STL-файла");
        status.setTextSize(16);
        root.addView(status);

        open.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, 10);
        });

        slice.setOnClickListener(v -> {
            if (selectedFile == null) {
                status.setText("Сначала выберите STL-файл");
            } else {
                status.setText(
                    "STL выбран. Параметры PETG заданы. " +
                    "Для создания G-code необходимо подключить " +
                    "движок слайсинга."
                );
            }
        });
    }

    @Override
    protected void onActivityResult(
        int requestCode,
        int resultCode,
        Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 10 &&
            resultCode == RESULT_OK &&
            data != null) {

            Uri uri = data.getData();

            if (uri != null) {
                String path = uri.toString();

                if (!path.toLowerCase().endsWith(".stl") &&
                    !path.toLowerCase().contains(".stl")) {
                    fileInfo.setText(
                        "Выбран файл. Убедитесь, что это STL."
                    );
                } else {
                    fileInfo.setText("STL выбран");
                }

                selectedFile = uri;
                try {
    stlViewer.loadSTL(uri);
    fileInfo.setText("3D-модель загружена");
    status.setText("Вращайте модель пальцем");
} catch (Exception e) {
    fileInfo.setText("Ошибка открытия STL");
    status.setText(e.getMessage());
                }
                status.setText("Файл готов к обработке");
            }
        }
    }
}
