package com.stlslicer.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Context;
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

public class MainActivity extends Activity {

    private static final int FILE_CHOOSER_REQUEST = 501;
    private static final String APP_URL = "https://stabilityai-stable-fast-3d.hf.space";

    private WebView webView;
    private ProgressBar progressBar;
    private TextView status;
    private TextView dimensions;
    private EditText heightCm;
    private Button saveStl;
    private Button viewStl;
    private ValueCallback<Uri[]> filePathCallback;

    private volatile String modelUrl;
    private volatile boolean converting;
    private File lastStlFile;
    private GlbToStl.Result lastResult;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(248, 249, 252));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(22, 12, 12, 6);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Фото в 3D");
        title.setTextSize(25);
        title.setTextColor(Color.rgb(20, 25, 35));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button reload = new Button(this);
        reload.setText("Обновить");
        header.addView(reload);
        root.addView(header);

        status = new TextView(this);
        status.setText("1. Выберите картинку  2. Нажмите «Создать 3D»  3. Сохраните STL");
        status.setTextSize(14);
        status.setTextColor(Color.DKGRAY);
        status.setPadding(22, 0, 22, 6);
        root.addView(status);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(16, 2, 16, 4);

        TextView hLabel = new TextView(this);
        hLabel.setText("Высота, см:");
        hLabel.setTextSize(15);
        controls.addView(hLabel);

        heightCm = new EditText(this);
        heightCm.setText("10");
        heightCm.setTextSize(16);
        heightCm.setSingleLine(true);
        heightCm.setSelectAllOnFocus(true);
        heightCm.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.45f);
        hp.setMargins(6, 0, 8, 0);
        controls.addView(heightCm, hp);

        saveStl = new Button(this);
        saveStl.setText("Сохранить STL");
        saveStl.setEnabled(false);
        controls.addView(saveStl, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        viewStl = new Button(this);
        viewStl.setText("3D");
        viewStl.setEnabled(false);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.48f);
        vp.setMargins(6, 0, 0, 0);
        controls.addView(viewStl, vp);
        root.addView(controls);

        dimensions = new TextView(this);
        dimensions.setText("Размер модели появится после создания STL");
        dimensions.setTextSize(13);
        dimensions.setTextColor(Color.rgb(70, 75, 85));
        dimensions.setPadding(22, 0, 22, 5);
        root.addView(dimensions);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        root.addView(progressBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 8));

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
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
        settings.setUserAgentString(settings.getUserAgentString() + " PhotoTo3D-Android/1.1");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.addJavascriptInterface(new JsBridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setProgress(100);
                progressBar.setVisibility(View.GONE);
                status.setText("Выберите изображение ниже. Интерфейс переведён на русский.");
                injectRussianUiAndModelDetector();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(newProgress);
                if (newProgress >= 100) progressBar.setVisibility(View.GONE);
            }

            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> filePathCallbackNew,
                                             FileChooserParams fileChooserParams) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = filePathCallbackNew;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
                startActivityForResult(Intent.createChooser(intent, "Выберите картинку"), FILE_CHOOSER_REQUEST);
                return true;
            }
        });

        reload.setOnClickListener(v -> {
            modelUrl = null;
            lastStlFile = null;
            lastResult = null;
            saveStl.setEnabled(false);
            viewStl.setEnabled(false);
            dimensions.setText("Размер модели появится после создания STL");
            webView.reload();
        });

        saveStl.setOnClickListener(v -> convertAndSaveStl());
        viewStl.setOnClickListener(v -> showStlViewer());
        webView.loadUrl(APP_URL);
    }

    private final class JsBridge {
        @JavascriptInterface
        public void onModelUrl(String url) {
            if (url == null || url.length() < 5 || !url.toLowerCase().contains(".glb")) return;
            String clean = url.replace("&amp;", "&");
            if (clean.equals(modelUrl)) return;
            modelUrl = clean;
            lastStlFile = null;
            lastResult = null;
            runOnUiThread(() -> {
                saveStl.setEnabled(true);
                viewStl.setEnabled(false);
                status.setText("3D-модель готова. Задайте высоту в сантиметрах и нажмите «Сохранить STL».");
                dimensions.setText("GLB найден — можно подготовить STL");
            });
        }
    }

    private void injectRussianUiAndModelDetector() {
        String js = "javascript:(function(){" +
                "if(window.__PHOTO3D_RU)return;window.__PHOTO3D_RU=true;" +
                "var style=document.createElement('style');style.innerHTML='header{display:none!important} footer{display:none!important} canvas,model-viewer{touch-action:none!important;pointer-events:auto!important} .prose{max-width:100%!important}';document.head.appendChild(style);" +
                "var map={" +
                "'Input Image':'Исходная картинка','Preview Background Removal':'Предпросмотр удаления фона','Foreground Ratio':'Размер объекта'," +
                "'Remeshing':'Перестроение сетки','None':'Нет','Triangle':'Треугольная','Quad':'Четырёхугольная'," +
                "'Target Vertex Count':'Количество вершин','Texture Size':'Размер текстуры','Run':'Создать 3D','Remove Background':'Удалить фон'," +
                "'3D Model':'3D-модель','HDR Environment Map':'Освещение HDR','HDR Env Map':'Карта освещения HDR','Examples':'Примеры'};" +
                "function walk(root){try{" +
                "var intro=root.querySelectorAll?root.querySelectorAll('.prose'):[];intro.forEach(function(e){var t=e.innerText||'';if(t.indexOf('SF3D is a state-of-the-art')>=0||t.indexOf('Stable Fast 3D Mesh Reconstruction')>=0)e.style.display='none';});" +
                "var walker=document.createTreeWalker(root,NodeFilter.SHOW_TEXT);var n;while((n=walker.nextNode())){var t=(n.nodeValue||'').trim();if(map[t])n.nodeValue=n.nodeValue.replace(t,map[t]);}" +
                "var els=root.querySelectorAll?root.querySelectorAll('*'):[];els.forEach(function(e){e.style&&((e.tagName==='CANVAS'||e.tagName==='MODEL-VIEWER')&&(e.style.touchAction='none',e.style.pointerEvents='auto'));if(e.shadowRoot)walk(e.shadowRoot);});" +
                "}catch(x){}}" +
                "function send(u){try{if(!u||typeof u!=='string')return;if(u.toLowerCase().indexOf('.glb')<0)return;AndroidBridge.onModelUrl(new URL(u,location.href).href);}catch(x){}}" +
                "function scanRoot(root){try{var els=[root];if(root.querySelectorAll)els=els.concat(Array.from(root.querySelectorAll('*')));els.forEach(function(e){if(!e)return;['src','href','value','data-src','data-value'].forEach(function(a){try{var v=e.getAttribute&&e.getAttribute(a);send(v);}catch(x){}});['src','href','value','model','url'].forEach(function(k){try{var v=e[k];if(typeof v==='string')send(v);}catch(x){}});if(e.shadowRoot)scanRoot(e.shadowRoot);});}catch(x){}}" +
                "function scan(){walk(document);scanRoot(document);try{performance.getEntriesByType('resource').forEach(function(e){send(e.name);});}catch(x){}}" +
                "scan();setInterval(scan,1200);" +
                "try{var of=window.fetch;window.fetch=async function(){var r=await of.apply(this,arguments);try{var c=r.clone();var ct=c.headers.get('content-type')||'';if(ct.indexOf('json')>=0||ct.indexOf('text')>=0)c.text().then(function(t){var m=t.match(/https?:[^\\\"' ]+\\.glb[^\\\"' ]*/ig);if(m)m.forEach(send);});}catch(x){}return r;};}catch(x){}" +
                "})()";
        webView.evaluateJavascript(js, null);
    }

    private void convertAndSaveStl() {
        if (converting) return;
        final String url = modelUrl;
        if (url == null) {
            status.setText("Ссылка на 3D-модель ещё не найдена. Дождитесь появления результата.");
            injectRussianUiAndModelDetector();
            return;
        }

        float cm;
        try {
            cm = Float.parseFloat(heightCm.getText().toString().trim().replace(',', '.'));
        } catch (Exception e) {
            cm = 10f;
        }
        if (cm < 0.5f) cm = 0.5f;
        if (cm > 100f) cm = 100f;
        final float targetMm = cm * 10f;

        converting = true;
        saveStl.setEnabled(false);
        status.setText("Скачиваю 3D-модель и преобразую в STL…");
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);

        new Thread(() -> {
            try {
                byte[] glb = downloadModel(url);
                GlbToStl.Result result = GlbToStl.convert(glb, targetMm);
                File temp = new File(getCacheDir(), "photo3d_model.stl");
                try (FileOutputStream fos = new FileOutputStream(temp)) { fos.write(result.stlBytes); }
                Uri saved = saveToDownloads(result.stlBytes);
                lastStlFile = temp;
                lastResult = result;
                runOnUiThread(() -> {
                    converting = false;
                    progressBar.setIndeterminate(false);
                    progressBar.setVisibility(View.GONE);
                    saveStl.setEnabled(true);
                    viewStl.setEnabled(true);
                    DecimalFormat df = new DecimalFormat("0.0");
                    dimensions.setText("Размер: " + df.format(result.widthMm / 10f) + " × " + df.format(result.heightMm / 10f) + " × " + df.format(result.depthMm / 10f) + " см  •  " + result.triangleCount + " треугольников");
                    status.setText("STL сохранён в «Загрузки/Фото-в-3D». Нажмите «3D», чтобы вращать модель пальцем.");
                    Toast.makeText(this, "STL сохранён", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    converting = false;
                    progressBar.setIndeterminate(false);
                    progressBar.setVisibility(View.GONE);
                    saveStl.setEnabled(modelUrl != null);
                    status.setText("Не удалось создать STL: " + e.getMessage());
                    Toast.makeText(this, "Ошибка STL: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private byte[] downloadModel(String address) throws Exception {
        if (address.startsWith("blob:")) throw new IllegalArgumentException("Сервис отдал временную ссылку. Обновите результат и попробуйте ещё раз.");
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(30_000);
        c.setReadTimeout(120_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", webView.getSettings().getUserAgentString());
        String cookies = CookieManager.getInstance().getCookie(address);
        if (cookies != null) c.setRequestProperty("Cookie", cookies);
        c.connect();
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalArgumentException("сервер вернул код " + code);
        int len = c.getContentLength();
        if (len > 120_000_000) throw new IllegalArgumentException("GLB больше 120 МБ");
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(8192, len > 0 ? len : 8192))) {
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

    private Uri saveToDownloads(byte[] data) throws Exception {
        String name = "Photo3D_" + System.currentTimeMillis() + ".stl";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, "model/stl");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Фото-в-3D");
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
        } else {
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) throw new IllegalStateException("Нет папки Загрузки");
            File file = new File(dir, name);
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(data); }
            return Uri.fromFile(file);
        }
    }

    private void showStlViewer() {
        File file = lastStlFile;
        GlbToStl.Result r = lastResult;
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Сначала нажмите «Сохранить STL»", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Dialog dialog = new Dialog(this);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackgroundColor(Color.rgb(20, 24, 32));

            TextView tip = new TextView(this);
            DecimalFormat df = new DecimalFormat("0.0");
            String size = r == null ? "" : ("  •  " + df.format(r.widthMm/10f) + " × " + df.format(r.heightMm/10f) + " × " + df.format(r.depthMm/10f) + " см");
            tip.setText("Вращение: 1 палец • Масштаб: 2 пальца" + size);
            tip.setTextColor(Color.WHITE);
            tip.setTextSize(15);
            tip.setPadding(16, 14, 16, 10);
            box.addView(tip);

            STLViewer viewer = new STLViewer(this);
            viewer.loadSTL(file);
            box.addView(viewer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

            Button close = new Button(this);
            close.setText("Закрыть");
            close.setOnClickListener(v -> dialog.dismiss());
            box.addView(close);
            dialog.setContentView(box);
            Window w = dialog.getWindow();
            if (w != null) w.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT);
            dialog.setOnShowListener(x -> {
                Window ww = dialog.getWindow();
                if (ww != null) ww.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT);
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
            if (resultCode == RESULT_OK && data != null && data.getData() != null) results = new Uri[]{data.getData()};
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed();
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
