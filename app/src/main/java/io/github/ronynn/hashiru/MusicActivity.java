package io.github.ronynn.hashiru;

import android.app.Activity;
import android.app.WallpaperManager;
import android.app.WallpaperColors;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class MusicActivity extends Activity {

    private static final String TAG = "MusicActivity";
    private static final int REQ_AUDIO_PERM = 100;
    private static final int REQ_COVER_PICK = 200;

    private WebView web;
    private LocalFileServer server;
    private long pendingPlayId = -1;
    private long pendingCoverSongId = -1;
    private boolean permissionRequested = false;

    private final BroadcastReceiver mediaCmdReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            String cmd = intent.getStringExtra("cmd");
            if (cmd == null) return;
            evalJs("window.__hashiruMusic && window.__hashiruMusic.cmd(" + jsStr(cmd) + ")");
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_music);
        applySystemBars(getResources().getConfiguration().orientation);

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

    @Override public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        applySystemBars(cfg.orientation);
    }

    private void applySystemBars(int orientation) {
        Window win = getWindow();
        View decor = win.getDecorView();
        boolean portrait = orientation == Configuration.ORIENTATION_PORTRAIT;
        int bg = 0xFF111111;

        win.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

        if (portrait) {
            win.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            win.setStatusBarColor(bg);
            win.setNavigationBarColor(bg);
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            if (Build.VERSION.SDK_INT >= 29) {
                win.setStatusBarContrastEnforced(false);
                win.setNavigationBarContrastEnforced(false);
            }
        } else {
            win.setStatusBarColor(Color.TRANSPARENT);
            win.setNavigationBarColor(Color.TRANSPARENT);
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
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

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
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

    // Saves the picked cover to app-private storage AND (for MP3) embeds it as
    // an APIC frame in the actual file via LocalFileServer.embedCoverInMp3().
    private void saveCoverFor(long songId, Uri src) {
        File dir = new File(getFilesDir(), "covers");
        if (!dir.exists()) dir.mkdirs();
        File[] old = dir.listFiles((d, name) -> name.startsWith(songId + "."));
        if (old != null) for (File f : old) f.delete();

        String ext = "jpg";
        String mime = getContentResolver().getType(src);
        if (mime != null) {
            if (mime.contains("png")) ext = "png";
            else if (mime.contains("webp")) ext = "webp";
            else if (mime.contains("gif")) ext = "gif";
        }
        File out = new File(dir, songId + "." + ext);

        byte[] coverBytes = null;
        try (InputStream in = getContentResolver().openInputStream(src);
             FileOutputStream os = new FileOutputStream(out)) {
            if (in == null) return;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) { os.write(b, 0, n); buf.write(b, 0, n); }
            coverBytes = buf.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "save cover failed", e);
            return;
        }

        // Embed into MP3 if applicable
        String audioMime = getAudioMime(songId);
        if (audioMime != null && (audioMime.contains("mpeg") || audioMime.contains("mp3"))) {
            try {
                Uri audioUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId);
                LocalFileServer.embedCoverInMp3(this, audioUri, coverBytes,
                        "image/" + ("png".equals(ext) ? "png" : "jpeg"));
            } catch (Exception e) {
                Log.w(TAG, "MP3 embed failed", e);
            }
        }
    }

    private String getAudioMime(long id) {
        Uri uri = ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
        Cursor c = null;
        try {
            c = getContentResolver().query(uri,
                    new String[]{MediaStore.Audio.Media.MIME_TYPE}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        } finally { if (c != null) c.close(); }
        return null;
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_COVER_PICK) {
            if (res == RESULT_OK && data != null && data.getData() != null
                    && pendingCoverSongId > 0) {
                saveCoverFor(pendingCoverSongId, data.getData());
                evalJs("window.__hashiruMusic && window.__hashiruMusic.refreshCover && "
                        + "window.__hashiruMusic.refreshCover(" + pendingCoverSongId + ")");
                evalJs("window.__hashiruMusic && window.__hashiruMusic.refreshNotification && "
                        + "window.__hashiruMusic.refreshNotification()");
            }
            pendingCoverSongId = -1;
        }
    }

    @Override protected void onDestroy() {
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

    public class MusicBridge {

        @JavascriptInterface public long consumePendingPlayId() {
            long id = pendingPlayId; pendingPlayId = -1; return id;
        }

        @JavascriptInterface public void onPlay(final String title, final String artist, final long songId) {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(MusicActivity.this, MusicPlaybackService.class);
                    i.setAction(MusicPlaybackService.ACTION_START);
                    i.putExtra(MusicPlaybackService.EXTRA_TITLE, title);
                    i.putExtra(MusicPlaybackService.EXTRA_ARTIST, artist);
                    i.putExtra(MusicPlaybackService.EXTRA_SONG_ID, songId);
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                    else startService(i);
                } catch (Exception e) { Log.w(TAG, "service start failed", e); }
            });
        }

        @JavascriptInterface public void onPause() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(MusicActivity.this, MusicPlaybackService.class);
                    i.setAction(MusicPlaybackService.ACTION_PAUSE);
                    startService(i);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface public void onStop() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(MusicActivity.this, MusicPlaybackService.class);
                    i.setAction(MusicPlaybackService.ACTION_STOP);
                    startService(i);
                } catch (Exception ignored) {}
            });
        }

        /** Rebuilds notification without changing play state. */
        @JavascriptInterface public void refreshNotification() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(MusicActivity.this, MusicPlaybackService.class);
                    i.setAction(MusicPlaybackService.ACTION_UPDATE_META);
                    startService(i);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface public void pickCover(final long songId) {
            runOnUiThread(() -> {
                if (songId <= 0) return;
                pendingCoverSongId = songId;
                Intent picker = new Intent(Intent.ACTION_GET_CONTENT);
                picker.setType("image/*");
                picker.addCategory(Intent.CATEGORY_OPENABLE);
                try { startActivityForResult(picker, REQ_COVER_PICK); }
                catch (ActivityNotFoundException e) {
                    Intent fallback = new Intent(Intent.ACTION_PICK,
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                    try { startActivityForResult(fallback, REQ_COVER_PICK); }
                    catch (Exception e2) { pendingCoverSongId = -1; }
                }
            });
        }

        /** Wallpaper-derived theme; returns `{"hue": N, "dark": bool}` or `{"hue": -1}`. */
        @JavascriptInterface public String getWallpaperTheme() {
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    WallpaperManager wm = WallpaperManager.getInstance(MusicActivity.this);
                    WallpaperColors wc = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
                    if (wc != null && wc.getPrimaryColor() != null) {
                        float[] hsv = new float[3];
                        Color.colorToHSV(wc.getPrimaryColor().toArgb(), hsv);
                        boolean dark = hsv[2] < 0.55f;
                        return "{\"hue\":" + hsv[0] + ",\"dark\":" + dark + "}";
                    }
                } catch (Exception e) {
                    Log.w(TAG, "wallpaper colors failed", e);
                }
            }
            return "{\"hue\":-1}";
        }
    }
}