package com.stlslicer.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DecimalFormat;

public class MainActivityV13 extends Activity {

    private static final int FILE_CHOOSER_REQUEST = 501;
    private static final String APP_URL = "https://stabilityai-stable-fast-3d.hf.space";

    private WebView webView;
    private ProgressBar progressBar;
    private TextView status;
    private TextView dimensions;
    private EditText heightCm;
    private Button create3d;
    private Button saveStl;
    private Button viewStl;
    private ValueCallback<Uri[]> filePathCallback;

    private volatile String modelUrl;
    private volatile boolean converting;
    private String cachedUserAgent;
    private File lastStlFile;
    private GlbToStl.Result lastResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(248, 249, 252));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(18, 10, 10, 4);

        TextView title = new TextView(this);
        title.setText("Фото в 3D");
        title.setTextSize(24);
        title.setTextColor(Color.rgb(20, 25, 35));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button reload = new Button(this);
        reload.setText("Обновить");
        header.addView(reload);
        root.addView(header);

        status = new TextView(this);
        status.setText("Выберите картинку ниже, затем нажмите «СОЗДАТЬ 3D».");
        status.setTextSize(14);
        status.setTextColor(Color.DKGRAY);
        status.setPadding(18, 0, 18, 4);
        root.addView(status);

        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        sizeRow.setGravity(Gravity.CENTER_VERTICAL);
        sizeRow.setPadding(12, 0, 12, 2);

        TextView hLabel = new TextView(this);
        hLabel.setText("Высота готовой модели, см:");
        hLabel.setTextSize(15);
        sizeRow.addView(hLabel);

        heightCm = new EditText(this);
        heightCm.setText("10");
        heightCm.setTextSize(16);
        heightCm.setSingleLine(true);
        heightCm.setInputType(android.text.InputType.TYPE_CLASS_NUMBER |
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        sizeRow.addView(heightCm, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(sizeRow);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(8, 0, 8, 2);

        create3d = new Button(this);
        create3d.setText("СОЗДАТЬ 3D");
        create3d.setEnabled(false);
        actions.addView(create3d, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.05f));

        saveStl = new Button(this);
        saveStl.setText("СОХРАНИТЬ STL");
        saveStl.setEnabled(false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.15f);
        sp.setMargins(4, 0, 4, 0);
        actions.addView(saveStl, sp);

        viewStl = new Button(this);
        viewStl.setText("3D");
        viewStl.setEnabled(false);
        actions.addView(viewStl, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0.5f));
        root.addView(actions);

        dimensions = new TextView(this);
        dimensions.setText("Задано: высота 10,0 см • ширина и глубина появятся после STL");
        dimensions.setTextSize(14);
        dimensions.setTextColor(Color.rgb(50, 55, 65));
        dimensions.setPadding(18, 0, 18, 5);
        root.addView(dimensions);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        root.addView(progressBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 8));

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setUserAgentString(settings.getUserAgentString() + " PhotoTo3D-Android/1.3");
        cachedUserAgent = settings.getUserAgentString();

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.addJavascriptInterface(new JsBridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                create3d.setEnabled(true);
                status.setText("Загрузите изображение ниже. После загрузки нажмите «СОЗДАТЬ 3D».");
                injectRussianUiAndModelDetector();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setIndeterminate(false);
                progressBar.setProgress(newProgress);
                if (newProgress >= 100) progressBar.setVisibility(View.GONE);
            }

            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                startActivityForResult(Intent.createChooser(intent, "Выберите картинку"),
                        FILE_CHOOSER_REQUEST);
                return true;
            }
        });

        reload.setOnClickListener(v -> resetAndReload());
        create3d.setOnClickListener(v -> triggerGenerate3d());
        saveStl.setOnClickListener(v -> convertAndSaveStl());
        viewStl.setOnClickListener(v -> showStlViewer());
        heightCm.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus && lastResult == null) updatePlannedDimensions();
        });

        webView.loadUrl(APP_URL);
    }

    private void resetAndReload() {
        modelUrl = null;
        lastStlFile = null;
        lastResult = null;
        saveStl.setEnabled(false);
        viewStl.setEnabled(false);
        create3d.setEnabled(false);
        updatePlannedDimensions();
        webView.reload();
    }

    private float readHeightCm() {
        try {
            float cm = Float.parseFloat(heightCm.getText().toString().trim().replace(',', '.'));
            return Math.max(0.5f, Math.min(100f, cm));
        } catch (Exception e) {
            return 10f;
        }
    }

    private void updatePlannedDimensions() {
        DecimalFormat df = new DecimalFormat("0.0");
        dimensions.setText("Задано: высота " + df.format(readHeightCm())
                + " см • ширина и глубина появятся после STL");
    }

    private void triggerGenerate3d() {
        modelUrl = null;
        lastStlFile = null;
        lastResult = null;
        saveStl.setEnabled(false);
        viewStl.setEnabled(false);
        updatePlannedDimensions();
        status.setText("Запускаю создание 3D-модели…");

        String js = "(function(){function all(r,a){try{if(r.querySelectorAll)r.querySelectorAll('button').forEach(function(b){a.push(b)});if(r.querySelectorAll)r.querySelectorAll('*').forEach(function(e){if(e.shadowRoot)all(e.shadowRoot,a)});}catch(x){}}var a=[];all(document,a);var b=a.find(function(x){var t=(x.innerText||x.textContent||'').trim().toLowerCase();return t==='создать 3d'||t==='run'||t==='generate'||t.indexOf('create 3d')>=0;});if(!b)return 'NOT_FOUND';b.click();return 'CLICKED';})()";
        webView.evaluateJavascript(js, value -> {
            if (value == null || value.contains("NOT_FOUND")) {
                status.setText("Не нашёл кнопку генерации. Прокрутите вниз и попробуйте снова.");
            } else {
                status.setText("3D создаётся. Когда модель будет готова, «СОХРАНИТЬ STL» станет активной.");
            }
        });
    }

    private final class JsBridge {
        @JavascriptInterface
        public void onModelUrl(String url) {
            if (url == null || !url.toLowerCase().contains(".glb")) return;
            String clean = url.replace("&amp;", "&");
            if (clean.equals(modelUrl)) return;
            modelUrl = clean;
            lastStlFile = null;
            lastResult = null;
            runOnUiThread(() -> {
                saveStl.setEnabled(true);
                viewStl.setEnabled(false);
                status.setText("3D-модель готова. Проверьте высоту и нажмите «СОХРАНИТЬ STL».");
                updatePlannedDimensions();
            });
        }
    }

    private void injectRussianUiAndModelDetector() {
        String js = "javascript:(function(){if(window.__PHOTO3D_RU_V13)return;window.__PHOTO3D_RU_V13=true;var m={'Input Image':'Исходная картинка','Preview Background Removal':'Предпросмотр удаления фона','Foreground Ratio':'Размер объекта','Remeshing':'Перестроение сетки','None':'Нет','Triangle':'Треугольная','Quad':'Четырёхугольная','Target Vertex Count':'Количество вершин','Texture Size':'Размер текстуры','Run':'Создать 3D','Generate':'Создать 3D','Remove Background':'Удалить фон','3D Model':'3D-модель','Download':'Скачать'};function walk(r){try{var w=document.createTreeWalker(r,NodeFilter.SHOW_TEXT),n;while((n=w.nextNode())){var t=(n.nodeValue||'').trim();if(m[t])n.nodeValue=n.nodeValue.replace(t,m[t]);}if(r.querySelectorAll)r.querySelectorAll('*').forEach(function(e){if(e.shadowRoot)walk(e.shadowRoot);});}catch(x){}}function send(u){try{if(!u||typeof u!=='string'||u.toLowerCase().indexOf('.glb')<0)return;AndroidBridge.onModelUrl(new URL(u,location.href).href);}catch(x){}}function scan(r){try{var a=[r];if(r.querySelectorAll)a=a.concat(Array.from(r.querySelectorAll('*')));a.forEach(function(e){['src','href','value','data-src','data-value'].forEach(function(k){try{send(e.getAttribute&&e.getAttribute(k));}catch(x){}});['src','href','value','model','url'].forEach(function(k){try{send(e[k]);}catch(x){}});if(e.shadowRoot)scan(e.shadowRoot);});}catch(x){}}function tick(){walk(document);scan(document);try{performance.getEntriesByType('resource').forEach(function(e){send(e.name)});}catch(x){}}tick();setInterval(tick,1000);})()";
        webView.evaluateJavascript(js, null);
    }

    private void convertAndSaveStl() {
        if (converting) return;
        final String url = modelUrl;
        if (url == null) {
            status.setText("3D ещё не готово. Сначала создайте модель.");
            return;
        }

        final float targetMm = readHeightCm() * 10f;
        final String userAgent = cachedUserAgent;
        final String cookies = CookieManager.getInstance().getCookie(url);

        converting = true;
        saveStl.setEnabled(false);
        status.setText("Скачиваю 3D-модель и преобразую в STL…");
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);

        new Thread(() -> {
            try {
                byte[] glb = downloadModel(url, userAgent, cookies);
                GlbToStl.Result result = GlbToStl.convert(glb, targetMm);
                File temp = new File(getCacheDir(), "photo3d_model.stl");
                try (FileOutputStream fos = new FileOutputStream(temp)) {
                    fos.write(result.stlBytes);
                }
                saveToDownloads(result.stlBytes);
                lastStlFile = temp;
                lastResult = result;
                runOnUiThread(() -> finishStlSuccess(result));
            } catch (Exception e) {
                runOnUiThread(() -> finishStlError(e));
            }
        }, "stl-worker").start();
    }

    private byte[] downloadModel(String address, String userAgent, String cookies) throws Exception {
        if (address.startsWith("blob:")) {
            throw new IllegalArgumentException("Сервис отдал временную ссылку. Создайте модель ещё раз.");
        }
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(30_000);
        c.setReadTimeout(120_000);
        c.setInstanceFollowRedirects(true);
        if (userAgent != null) c.setRequestProperty("User-Agent", userAgent);
        if (cookies != null) c.setRequestProperty("Cookie", cookies);
        c.connect();
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalArgumentException("сервер вернул код " + code);
        }
        int len = c.getContentLength();
        if (len > 120_000_000) throw new IllegalArgumentException("GLB больше 120 МБ");
        try (InputStream in = c.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(8192, len > 0 ? len : 8192))) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > 120_000_000) throw new IllegalArgumentException("GLB больше 120 МБ");
            }
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private void finishStlSuccess(GlbToStl.Result result) {
        converting = false;
        progressBar.setIndeterminate(false);
        progressBar.setVisibility(View.GONE);
        saveStl.setEnabled(true);
        viewStl.setEnabled(true);
        DecimalFormat df = new DecimalFormat("0.0");
        dimensions.setText("Размер STL: "
                + df.format(result.widthMm / 10f) + " × "
                + df.format(result.heightMm / 10f) + " × "
                + df.format(result.depthMm / 10f) + " см • "
                + result.triangleCount + " треугольников");
        status.setText("STL сохранён в «Загрузки/Фото-в-3D». Нажмите «3D» для просмотра.");
        Toast.makeText(this, "STL сохранён", Toast.LENGTH_LONG).show();
    }

    private void finishStlError(Exception e) {
        converting = false;
        progressBar.setIndeterminate(false);
        progressBar.setVisibility(View.GONE);
        saveStl.setEnabled(modelUrl != null);
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        status.setText("Не удалось создать STL: " + msg);
        Toast.makeText(this, "Ошибка STL: " + msg, Toast.LENGTH_LONG).show();
    }

    private Uri saveToDownloads(byte[] data) throws Exception {
        String name = "Photo3D_" + System.currentTimeMillis() + ".stl";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, "model/stl");
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/Фото-в-3D");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("Не удалось создать файл в Загрузках");
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("Нет доступа к файлу");
                out.write(data);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
            return uri;
        }

        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) throw new IllegalStateException("Нет папки Загрузки");
        File file = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
        return Uri.fromFile(file);
    }

    private void showStlViewer() {
        File file = lastStlFile;
        GlbToStl.Result r = lastResult;
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Сначала сохраните STL", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Dialog dialog = new Dialog(this);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackgroundColor(Color.rgb(20, 24, 32));

            DecimalFormat df = new DecimalFormat("0.0");
            TextView tip = new TextView(this);
            tip.setText("Вращение: 1 палец • Масштаб: 2 пальца • "
                    + df.format(r.widthMm / 10f) + " × "
                    + df.format(r.heightMm / 10f) + " × "
                    + df.format(r.depthMm / 10f) + " см");
            tip.setTextColor(Color.WHITE);
            tip.setTextSize(15);
            tip.setPadding(16, 14, 16, 10);
            box.addView(tip);

            STLViewer viewer = new STLViewer(this);
            viewer.loadSTL(file);
            box.addView(viewer, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

            Button close = new Button(this);
            close.setText("Закрыть");
            close.setOnClickListener(v -> dialog.dismiss());
            box.addView(close);

            dialog.setContentView(box);
            dialog.setOnShowListener(x -> {
                Window w = dialog.getWindow();
                if (w != null) w.setLayout(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.MATCH_PARENT);
            });
            dialog.show();
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка просмотра: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidBridge");
            webView.destroy();
        }
        super.onDestroy();
    }
}
