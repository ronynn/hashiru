package io.github.ronynn.hashiru;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.IOException;

public class WebViewActivity extends Activity {

    public static final String EXTRA_TREE_URI = "tree_uri";
    public static final String EXTRA_REL_PATH = "rel_path";

    private static final String POS_PREFS = "hashiru_positions";

    private LocalFileServer server;
    private WebView web;
    private SharedPreferences posPrefs;
    private String posKey;
    private boolean restorePending = true;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_webview);

        web = findViewById(R.id.web);

        // Kill the elastic/glow overscroll effect
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

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                injectTapHighlightFix();
                if (restorePending) {
                    restorePending = false;
                    int y = posPrefs.getInt(posKey, 0);
                    if (y > 0) v.scrollTo(0, y);
                }
            }
        });

        String treeUriStr = getIntent().getStringExtra(EXTRA_TREE_URI);
        String relPath = getIntent().getStringExtra(EXTRA_REL_PATH);
        if (treeUriStr == null || relPath == null) { finish(); return; }

        posPrefs = getSharedPreferences(POS_PREFS, MODE_PRIVATE);
        posKey = treeUriStr + "|" + relPath;

        // Name the Recents card after the file
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

        applyOrientationImmersive(getResources().getConfiguration().orientation);
    }

    /** Removes the default blue/gray tap highlight painted by WebView on touch. */
    private void injectTapHighlightFix() {
        String js = "(function(){try{"
                + "var s=document.createElement('style');"
                + "s.textContent='*{-webkit-tap-highlight-color:transparent !important;}"
                + "html,body{overscroll-behavior:none;}';"
                + "document.documentElement.appendChild(s);"
                + "}catch(e){}})();";
        web.evaluateJavascript(js, null);
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
        applyOrientationImmersive(cfg.orientation);
    }

    private void applyOrientationImmersive(int orientation) {
        View decor = getWindow().getDecorView();
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_FULLSCREEN);
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private void savePosition() {
        if (web == null || posPrefs == null || posKey == null) return;
        posPrefs.edit().putInt(posKey, web.getScrollY()).apply();
    }

    @Override
    protected void onPause() {
        savePosition();
        super.onPause();
    }

    @Override
    protected void onStop() {
        savePosition();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (server != null) { server.stop(); server = null; }
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}