package io.github.ronynn.hashiru;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.IOException;

public class MusicActivity extends Activity {

    private static final String TAG = "MusicActivity";
    private static final int REQ_AUDIO_PERM = 100;

    private WebView web;
    private LocalFileServer server;
    private long pendingPlayId = -1;
    private boolean permissionRequested = false;

    private final BroadcastReceiver mediaCmdReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            String cmd = intent.getStringExtra("cmd");
            if (cmd == null) return;
            if ("stop".equals(cmd)) {
                evalJs("window.__hashiruMusic && window.__hashiruMusic.cmd('stop')");
                // Don't finish — user might have just paused via service.
                // If we got 'stop' from onTaskRemoved, the activity is being
                // destroyed anyway and onDestroy will clean up.
            } else {
                evalJs("window.__hashiruMusic && window.__hashiruMusic.cmd(" + jsStr(cmd) + ")");
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_music);

        web = findViewById(R.id.web);
        web.setBackgroundColor(0xFF111111);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setSupportZoom(false);

        web.addJavascriptInterface(new MusicBridge(), "__HashiruMusic");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return false;
            }
        });
        web.setWebChromeClient(new WebChromeClient());

        IntentFilter filter = new IntentFilter(MusicPlaybackService.ACTION_MEDIA_CMD);
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(mediaCmdReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else
            registerReceiver(mediaCmdReceiver, filter);

        try {
            server = LocalFileServer.acquire(null, getContentResolver(), this);
        } catch (IOException e) {
            Log.e(TAG, "server failed", e);
            finish();
            return;
        }

        handleAudioIntent(getIntent());
        requestPerms();

        web.loadUrl("http://127.0.0.1:" + server.getListeningPort() + "/_music/");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAudioIntent(intent);
        if (pendingPlayId > 0) {
            evalJs("window.__hashiruMusic && window.__hashiruMusic.playById(" + pendingPlayId + ")");
            pendingPlayId = -1;
        }
    }

    private void handleAudioIntent(Intent intent) {
        if (intent == null) return;
        if (!Intent.ACTION_VIEW.equals(intent.getAction())) return;
        Uri uri = intent.getData();
        if (uri == null) return;
        long id = resolveMediaStoreId(uri);
        if (id > 0) pendingPlayId = id;
        else Log.w(TAG, "unrecognized audio URI: " + uri);
    }

    private long resolveMediaStoreId(Uri uri) {
        try {
            String authority = uri.getAuthority();
            if (authority != null && authority.startsWith("media")) {
                String last = uri.getLastPathSegment();
                if (last != null) {
                    try { return Long.parseLong(last); } catch (Exception ignored) {}
                }
            }
            String path = uri.getPath();
            if (path != null) {
                Cursor c = getContentResolver().query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.Audio.Media._ID},
                        MediaStore.Audio.Media.DATA + "=?",
                        new String[]{path}, null);
                if (c != null) {
                    try { if (c.moveToFirst()) return c.getLong(0); }
                    finally { c.close(); }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "resolveMediaStoreId failed", e);
        }
        return -1;
    }

    private void requestPerms() {
        if (permissionRequested) return;
        permissionRequested = true;

        java.util.List<String> perms = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.READ_MEDIA_AUDIO")
                    != PackageManager.PERMISSION_GRANTED)
                perms.add("android.permission.READ_MEDIA_AUDIO");
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED)
                perms.add("android.permission.POST_NOTIFICATIONS");
        } else {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED)
                perms.add(android.Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (!perms.isEmpty())
            requestPermissions(perms.toArray(new String[0]), REQ_AUDIO_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_AUDIO_PERM) {
            evalJs("window.__hashiruMusic && window.__hashiruMusic.rescan && window.__hashiruMusic.rescan()");
        }
    }

    private void evalJs(String js) {
        if (web == null) return;
        try { web.evaluateJavascript(js, null); } catch (Exception ignored) {}
    }

    private static String jsStr(String s) {
        if (s == null) return "''";
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                default: sb.append(c);
            }
        }
        sb.append("'");
        return sb.toString();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(mediaCmdReceiver); } catch (Exception ignored) {}
        if (isFinishing()) {
            evalJs("window.__hashiruMusic && window.__hashiruMusic.cmd('stop')");
            try {
                Intent stop = new Intent(this, MusicPlaybackService.class);
                stop.setAction(MusicPlaybackService.ACTION_STOP);
                startService(stop);
            } catch (Exception ignored) {}
        }
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }

    // ==================================================================
    // JS Bridge
    // ==================================================================

    public class MusicBridge {

        @JavascriptInterface
        public long consumePendingPlayId() {
            long id = pendingPlayId;
            pendingPlayId = -1;
            return id;
        }

        @JavascriptInterface
        public void onPlay(final String title, final String artist) {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(MusicActivity.this, MusicPlaybackService.class);
                    i.setAction(MusicPlaybackService.ACTION_START);
                    i.putExtra(MusicPlaybackService.EXTRA_TITLE, title);
                    i.putExtra(MusicPlaybackService.EXTRA_ARTIST, artist);
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                    else startService(i);
                } catch (Exception e) {
                    Log.w(TAG, "service start failed", e);
                }
            });
        }

        @JavascriptInterface
        public void onPause() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(MusicActivity.this, MusicPlaybackService.class);
                    i.setAction(MusicPlaybackService.ACTION_PAUSE);
                    startService(i);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void onStop() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(MusicActivity.this, MusicPlaybackService.class);
                    i.setAction(MusicPlaybackService.ACTION_STOP);
                    startService(i);
                } catch (Exception ignored) {}
            });
        }
    }
}