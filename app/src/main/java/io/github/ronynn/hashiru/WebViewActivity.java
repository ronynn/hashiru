package io.github.ronynn.hashiru;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.IOException;

public class WebViewActivity extends Activity {

    public static final String EXTRA_TREE_URI = "tree_uri";
    public static final String EXTRA_REL_PATH = "rel_path";

    private LocalFileServer server;
    private WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_webview);

        web = findViewById(R.id.web);

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
        web.setWebViewClient(new WebViewClient());

        String treeUriStr = getIntent().getStringExtra(EXTRA_TREE_URI);
        String relPath = getIntent().getStringExtra(EXTRA_REL_PATH);
        if (treeUriStr == null || relPath == null) { finish(); return; }

        Uri treeUri = Uri.parse(treeUriStr);
        server = new LocalFileServer(treeUri, getContentResolver());
        try {
            server.start();
        } catch (IOException e) {
            finish();
            return;
        }

        int port = server.getListeningPort();
        String url = "http://127.0.0.1:" + port + "/" + encodePath(relPath);
        web.loadUrl(url);
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
    protected void onDestroy() {
        if (server != null) { server.stop(); server = null; }
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}