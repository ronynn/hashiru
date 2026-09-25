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
import android.view.View;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class WebViewActivity extends Activity {

    public static final String EXTRA_TREE_URI = "tree_uri";
    public static final String EXTRA_REL_PATH = "rel_path";

    private static final String POS_PREFS = "hashiru_positions";
    private static final int REQ_FILE_CHOOSER = 2001;

    private LocalFileServer server;
    private WebView web;
    private SharedPreferences posPrefs;
    private String posKey;
    private boolean restorePending = true;
    private ValueCallback<Uri[]> fileChooserCallback;
    private String currentExt = "";
    private int statusBarHeight = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_webview);

        // Draw under the status bar (top-only edge-to-edge)
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(Color.TRANSPARENT);

        statusBarHeight = getStatusBarHeight();

        web = findViewById(R.id.web);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);

        posPrefs = getSharedPreferences(POS_PREFS, MODE_PRIVATE);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                injectTapHighlightFix();
                injectSafeAreaPadding();
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

        if (Build.VERSION.SDK_INT >= 21) {
            setTaskDescription(new ActivityManager.TaskDescription(relPath));
        }

        Uri treeUri = Uri.parse(treeUriStr);
        server = new LocalFileServer(treeUri, getContentResolver());
        try {
            server.start();
        } catch (IOException e) {
            finish();
            return;
        }
        int port = server.getListeningPort();
        web.loadUrl("http://127.0.0.1:" + port + "/" + encodePath(relPath));

        applyOrientation(getResources().getConfiguration().orientation);
    }

    private void openDirectFile(Uri fileUri) {
        String name = queryDisplayName(fileUri);
        currentExt = extOf(name);
        posKey = fileUri.toString();

        if (Build.VERSION.SDK_INT >= 21) {
            setTaskDescription(new ActivityManager.TaskDescription(
                    name == null ? "Hashiru" : name));
        }

        try {
            InputStream in = getContentResolver().openInputStream(fileUri);
            if (in == null) { finish(); return; }
            String raw = readAll(in);
            String html = ReaderRenderer.render(currentExt, raw);
            web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        } catch (IOException e) {
            finish();
        }
    }

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

    /**
     * Pushes page content below the transparent status bar at rest.
     * Content still flows under the bar while scrolling — that's the point.
     * Reader pages get a bit more breathing room to match their 1.2em top padding.
     */
    private void injectSafeAreaPadding() {
        if (statusBarHeight <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        int extraDp = isReaderExt(currentExt) ? 20 : 4;
        int topPad = statusBarHeight + (int) (extraDp * density);

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
        View decor = getWindow().getDecorView();
        WindowManager.LayoutParams lp = getWindow().getAttributes();

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // Full-bleed: hide status + nav, draw into cutout.
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
        } else {
            // Portrait: draw under status bar only. Nav stays visible.
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            }
        }
        getWindow().setAttributes(lp);
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
        if (server != null) { server.stop(); server = null; }
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}