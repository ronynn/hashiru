package io.github.ronynn.hashiru;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class WebViewActivity extends Activity {

    public static final String EXTRA_TREE_URI = "tree_uri";
    public static final String EXTRA_REL_PATH = "rel_path";

    private static final String POS_PREFS = "hashiru_positions";
    private static final int REQ_FILE_CHOOSER = 2001;
    private static final int REQ_SAVE_FILE = 2002;

    private static final int BAR_COLOR_HTML = 0xFF1A1A1A;

    private LocalFileServer server;
    private WebView web;
    private SharedPreferences posPrefs;
    private String posKey;
    private boolean restorePending = true;
    private ValueCallback<Uri[]> fileChooserCallback;
    private String currentExt = "";
    private boolean isReader = false;
    private int statusBarHeight = 0;

    private String pendingSaveMime;
    private String pendingSaveBase64;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_webview);

        statusBarHeight = getStatusBarHeight();

        web = findViewById(R.id.web);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // FIX: file-chooser results are content:// URIs; WebView needs this.
        s.setAllowContentAccess(true);
        s.setAllowFileAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);

        posPrefs = getSharedPreferences(POS_PREFS, MODE_PRIVATE);

        web.addJavascriptInterface(new SaveBridge(), "__HashiruNative");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (u == null) return false;
                String scheme = u.getScheme();
                if (scheme == null) return false;
                // Keep local server content in the WebView
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    String host = u.getHost();
                    if ("127.0.0.1".equals(host) || "localhost".equals(host)) return false;
                }
                // Everything else → external handler
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, u);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (ActivityNotFoundException ignored) {}
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                injectTapHighlightFix();
                if (isReader) injectReaderSafeAreaPadding();
                if (restorePending) {
                    restorePending = false;
                    int y = posPrefs.getInt(posKey, 0);
                    if (y > 0) v.scrollTo(0, y);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w,
                    ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = cb;
                Intent intent = params.createIntent();
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    startActivityForResult(intent, REQ_FILE_CHOOSER);
                } catch (ActivityNotFoundException e) {
                    fileChooserCallback = null;
                    return false;
                }
                return true;
            }
        });

        // FIX: browser-style download support (blob:, data:, http(s)).
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition,
                                        String mime, long contentLength) {
                handleDownload(url, mime);
            }
        });

        // Mode 1: launched from another app with a file
        if (Intent.ACTION_VIEW.equals(getIntent().getAction()) && getIntent().getData() != null) {
            openDirectFile(getIntent().getData());
            applyOrientation(getResources().getConfiguration().orientation);
            return;
        }

        // Mode 2: launched from MainActivity
        String treeUriStr = getIntent().getStringExtra(EXTRA_TREE_URI);
        String relPath = getIntent().getStringExtra(EXTRA_REL_PATH);
        if (treeUriStr == null || relPath == null) { finish(); return; }

        posKey = treeUriStr + "|" + relPath;
        currentExt = extOf(relPath);
        isReader = isReaderExt(currentExt);

        if (Build.VERSION.SDK_INT >= 21) {
            setTaskDescription(new ActivityManager.TaskDescription(relPath));
        }

        Uri treeUri = Uri.parse(treeUriStr);
        try {
            // FIX: singleton server — stable origin, shared across activities.
            server = LocalFileServer.acquire(treeUri, getContentResolver());
        } catch (IOException e) {
            finish();
            return;
        }
        int port = server.getListeningPort();
        web.loadUrl("http://127.0.0.1:" + port + "/" + encodePath(relPath));

        applyOrientation(getResources().getConfiguration().orientation);
    }

    // ------------------------------------------------------------------ downloads

    private void handleDownload(String url, String mimeFromHeader) {
        if (url == null) return;

        if (url.startsWith("blob:")) {
            requestBlobAsBase64(url, mimeFromHeader);
            return;
        }

        if (url.startsWith("data:")) {
            int comma = url.indexOf(',');
            if (comma < 0) return;
            String meta = url.substring(5, comma);
            String data = url.substring(comma + 1);
            boolean isB64 = meta.toLowerCase().contains(";base64");
            String m = meta.split(";")[0];
            if (m.isEmpty()) m = (mimeFromHeader == null ? "*/*" : mimeFromHeader);
            if (isB64) {
                startSaveDocument(m, data);
            } else {
                try {
                    String decoded = java.net.URLDecoder.decode(data, "UTF-8");
                    String b64 = Base64.encodeToString(
                            decoded.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                    startSaveDocument(m, b64);
                } catch (Exception ignored) {}
            }
            return;
        }

        // Any other scheme (http/https/ftp/…) — hand off to the system.
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException ignored) {}
    }

    private void requestBlobAsBase64(String blobUrl, String mime) {
        String safeUrl = jsString(blobUrl);
        String safeMime = jsString(mime == null ? "" : mime);
        String js = "(function(){try{"
                + "fetch(" + safeUrl + ").then(function(r){return r.blob();}).then(function(b){"
                + "var fr=new FileReader();"
                + "fr.onload=function(){var s=fr.result;var i=s.indexOf(',');"
                + "var b64=(i>=0?s.substring(i+1):s);"
                + "__HashiruNative.downloadBase64(b.type||" + safeMime + ",b64);};"
                + "fr.onerror=function(){};"
                + "fr.readAsDataURL(b);"
                + "}).catch(function(){});"
                + "}catch(e){}})();";
        web.evaluateJavascript(js, null);
    }

    private static String jsString(String s) {
        if (s == null) return "''";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\u2028': sb.append("\\u2028"); break;
                case '\u2029': sb.append("\\u2029"); break;
                default: sb.append(c);
            }
        }
        sb.append('\'');
        return sb.toString();
    }

    public class SaveBridge {
        @JavascriptInterface
        public void downloadBase64(String mime, String base64) {
            final String m = mime;
            final String d = base64;
            runOnUiThread(() -> startSaveDocument(m, d));
        }
    }

    private void startSaveDocument(String mime, String base64) {
        if (base64 == null) return;
        pendingSaveMime = (mime == null || mime.isEmpty())
                ? "application/octet-stream" : mime;
        pendingSaveBase64 = base64;

        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(pendingSaveMime);
        i.putExtra(Intent.EXTRA_TITLE, suggestNameFor(pendingSaveMime));
        try {
            startActivityForResult(i, REQ_SAVE_FILE);
        } catch (ActivityNotFoundException e) {
            pendingSaveBase64 = null;
            pendingSaveMime = null;
        }
    }

    private String suggestNameFor(String mime) {
        if (mime == null) return "download";
        if (mime.startsWith("text/html")) return "page.html";
        if (mime.startsWith("text/plain")) return "note.txt";
        if (mime.startsWith("application/json")) return "data.json";
        if (mime.startsWith("image/png")) return "image.png";
        if (mime.startsWith("image/jpeg")) return "image.jpg";
        if (mime.startsWith("image/gif")) return "image.gif";
        if (mime.startsWith("image/svg")) return "image.svg";
        if (mime.startsWith("text/csv")) return "data.csv";
        if (mime.startsWith("application/pdf")) return "document.pdf";
        return "download";
    }

    // ------------------------------------------------------------------ direct file

    private void openDirectFile(Uri fileUri) {
        String name = queryDisplayName(fileUri);
        currentExt = extOf(name);
        isReader = isReaderExt(currentExt);
        posKey = fileUri.toString();

        if (Build.VERSION.SDK_INT >= 21) {
            setTaskDescription(new ActivityManager.TaskDescription(
                    name == null ? "Hashiru" : name));
        }

        try {
            InputStream in = getContentResolver().openInputStream(fileUri);
            if (in == null) { finish(); return; }
            String raw = readAll(in);

            // FIX: stable base URL → stable origin for localStorage even for ACTION_VIEW files.
            String baseUrl = "https://hashiru.local/" + Uri.encode(fileUri.toString()) + "/";

            if (currentExt.equals("html") || currentExt.equals("htm")) {
                // FIX: render HTML as HTML, not escaped.
                web.loadDataWithBaseURL(baseUrl, raw, "text/html", "utf-8", null);
            } else if (isReader) {
                String html = ReaderRenderer.render(currentExt, raw);
                web.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null);
            } else {
                String escaped = ReaderRenderer.render("txt", raw);
                web.loadDataWithBaseURL(baseUrl, escaped, "text/html", "utf-8", null);
            }
        } catch (IOException e) {
            finish();
        }
    }

    // ------------------------------------------------------------------ helpers

    private String extOf(String pathOrName) {
        if (pathOrName == null) return "";
        int slash = pathOrName.lastIndexOf('/');
        String name = slash >= 0 ? pathOrName.substring(slash + 1) : pathOrName;
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "";
        return name.substring(dot + 1).toLowerCase();
    }

    private boolean isReaderExt(String ext) {
        return ext.equals("md") || ext.equals("markdown")
            || ext.equals("txt") || ext.equals("org") || ext.equals("twee");
    }

    private String queryDisplayName(Uri uri) {
        try {
            Cursor c = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) return c.getString(0);
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignored) {}
        return uri.getLastPathSegment();
    }

    private String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);

        if (req == REQ_FILE_CHOOSER) {
            if (fileChooserCallback != null) {
                Uri[] result = WebChromeClient.FileChooserParams.parseResult(res, data);
                fileChooserCallback.onReceiveValue(result);
                fileChooserCallback = null;
            }
            return;
        }

        if (req == REQ_SAVE_FILE) {
            if (res == RESULT_OK && data != null && data.getData() != null
                    && pendingSaveBase64 != null) {
                Uri target = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(target,
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception ignored) {}
                try {
                    OutputStream os = getContentResolver().openOutputStream(target, "w");
                    if (os != null) {
                        byte[] bytes = Base64.decode(pendingSaveBase64, Base64.DEFAULT);
                        os.write(bytes);
                        os.flush();
                        os.close();
                    }
                } catch (IOException ignored) {}
            }
            pendingSaveBase64 = null;
            pendingSaveMime = null;
        }
    }

    private void injectTapHighlightFix() {
        String js = "(function(){try{"
                + "var s=document.createElement('style');"
                + "s.textContent='*{-webkit-tap-highlight-color:transparent !important;}"
                + "html,body{overscroll-behavior:none;}';"
                + "document.documentElement.appendChild(s);"
                + "}catch(e){}})();";
        web.evaluateJavascript(js, null);
    }

    private void injectReaderSafeAreaPadding() {
        if (statusBarHeight <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        int topPad = statusBarHeight + (int) (20 * density);

        String js = "(function(){try{"
                + "var id='hashiru-safe-top';"
                + "var old=document.getElementById(id);"
                + "if(old) old.parentNode.removeChild(old);"
                + "var st=document.createElement('style');"
                + "st.id=id;"
                + "st.textContent='body{padding-top:" + topPad + "px !important;}';"
                + "document.head.appendChild(st);"
                + "}catch(e){}})();";
        web.evaluateJavascript(js, null);
    }

    private int getStatusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) return getResources().getDimensionPixelSize(id);
        return 0;
    }

    private String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(Uri.encode(parts[i]));
        }
        return sb.toString();
    }

    @Override
    public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        applyOrientation(cfg.orientation);
    }

    private void applyOrientation(int orientation) {
        Window win = getWindow();
        View decor = win.getDecorView();
        WindowManager.LayoutParams lp = win.getAttributes();

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN);
            if (Build.VERSION.SDK_INT >= 30) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            } else if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            win.setAttributes(lp);
            return;
        }

        if (isReader) {
            win.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            win.setStatusBarColor(Color.TRANSPARENT);
            win.setNavigationBarColor(BAR_COLOR_HTML);
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            }
        } else {
            win.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            win.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            win.setStatusBarColor(BAR_COLOR_HTML);
            win.setNavigationBarColor(BAR_COLOR_HTML);
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            }
        }
        win.setAttributes(lp);
    }

    private void savePosition() {
        if (web == null || posPrefs == null || posKey == null) return;
        posPrefs.edit().putInt(posKey, web.getScrollY()).apply();
    }

    @Override protected void onPause() { savePosition(); super.onPause(); }
    @Override protected void onStop()  { savePosition(); super.onStop(); }

    @Override
    protected void onDestroy() {
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }
        // FIX: do NOT stop the LocalFileServer — it lives for the process,
        // so the 127.0.0.1:PORT origin (and its localStorage) stays stable.
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}