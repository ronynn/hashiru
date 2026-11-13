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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class MusicActivity extends Activity {

    private static final String TAG = "MusicActivity";
    private static final String BRIDGE_TAG = "MusicBridge";

    private static final int REQ_AUDIO_PERM = 100;
    private static final int REQ_COVER_PICK = 200;

    private WebView web;
    private LocalFileServer server;
    private long pendingPlayId = -1;
    private long pendingCoverSongId = -1;
    private boolean permissionRequested = false;
    private boolean systemBarsLight = false;

    private final BroadcastReceiver mediaCmdReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            String cmd = intent.getStringExtra("cmd");
            if (cmd == null) {
                Log.w(BRIDGE_TAG, "media cmd with no action");
                return;
            }
            if ("seek".equals(cmd)) {
                long pos = intent.getLongExtra("pos", -1);
                if (pos >= 0) {
                    evalJs("window.__hashiruMusic && window.__hashiruMusic.cmd('seek', " + pos + ")");
                }
                return;
            }
            evalJs("window.__hashiruMusic && window.__hashiruMusic.cmd(" + jsStr(cmd) + ")");
        }
    };

    @Override protected void onCreate(Bundle b) {
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
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);

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

        applySystemBars(getResources().getConfiguration().orientation);
        handleAudioIntent(getIntent());
        requestPerms();

        web.loadUrl("http://127.0.0.1:" + server.getListeningPort() + "/_music/");
    }

    @Override protected void onResume() {
        super.onResume();
        applySystemBars(getResources().getConfiguration().orientation);
    }

    @Override public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        applySystemBars(cfg.orientation);
    }

    private void applySystemBars(int orientation) {
        Window win = getWindow();
        View decor = win.getDecorView();
        boolean portrait = orientation == Configuration.ORIENTATION_PORTRAIT;

        win.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

        if (portrait) {
            win.setStatusBarColor(Color.TRANSPARENT);
            win.setNavigationBarColor(Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= 29) {
                win.setStatusBarContrastEnforced(false);
                win.setNavigationBarContrastEnforced(false);
                win.setNavigationBarDividerColor(Color.TRANSPARENT);
            }
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                      | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                      | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (systemBarsLight) {
                if (Build.VERSION.SDK_INT >= 23) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(flags);
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

    private void setSystemBarsLight(boolean light) {
        systemBarsLight = light;
        applySystemBars(getResources().getConfiguration().orientation);
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

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_AUDIO_PERM) {
            boolean postNotif = true;
            if (Build.VERSION.SDK_INT >= 33) {
                for (int i = 0; i < perms.length; i++) {
                    if ("android.permission.POST_NOTIFICATIONS".equals(perms[i])
                            && results[i] != PackageManager.PERMISSION_GRANTED) {
                        postNotif = false;
                    }
                }
            }
            if (!postNotif) {
                evalJs("window.__hashiruMusic && window.__hashiruMusic.notifyPermissionDenied && "
                        + "window.__hashiruMusic.notifyPermissionDenied()");
            }
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

    /** Safe way to start the service for any command. */
    private void startServiceSafe(Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            Log.w(BRIDGE_TAG, "service start failed for " + i.getAction(), e);
        }
    }

    public class MusicBridge {

        @JavascriptInterface
        public long consumePendingPlayId() {
            long id = pendingPlayId;
            pendingPlayId = -1;
            return id;
        }

        @JavascriptInterface
        public void onPlay(final String title, final String artist,
                           final long songId, final long posMs, final long durMs) {
            Log.d(BRIDGE_TAG, "onPlay title=" + title + " id=" + songId
                    + " pos=" + posMs + " dur=" + durMs);
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(MusicActivity.this, MusicPlaybackService.class);
                    i.setAction(MusicPlaybackService.ACTION_START);
                    i.putExtra(MusicPlaybackService.EXTRA_TITLE, title);
                    i.putExtra(MusicPlaybackService.EXTRA_ARTIST, artist);
                    i.putExtra(MusicPlaybackService.EXTRA_SONG_ID, songId);
                    i.putExtra(MusicPlaybackService.EXTRA_POSITION, posMs);
                    i.putExtra(MusicPlaybackService.EXTRA_DURATION, durMs);
                    if (Build.VERSION.SDK_INT >= 26) {
                        startForegroundService(i);
                    } else {
                        startService(i);
                    }
                } catch (Exception e) {
                    Log.e(BRIDGE_TAG, "onPlay service start failed", e);
                }
            });
        }

        @JavascriptInterface public void onPause() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(MusicActivity.this, MusicPlaybackService.class);
                    i.setAction(MusicPlaybackService.ACTION_PAUSE);
                    startServiceSafe(i);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface public void onStop() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(MusicActivity.this, MusicPlaybackService.class);
                    i.setAction(MusicPlaybackService.ACTION_STOP);
                    startServiceSafe(i);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface public void onPosition(final long posMs, final long durMs) {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(MusicActivity.this, MusicPlaybackService.class);
                    i.setAction(MusicPlaybackService.ACTION_UPDATE_POSITION);
                    i.putExtra(MusicPlaybackService.EXTRA_POSITION, posMs);
                    i.putExtra(MusicPlaybackService.EXTRA_DURATION, durMs);
                    startServiceSafe(i);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface public void refreshNotification() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(MusicActivity.this, MusicPlaybackService.class);
                    i.setAction(MusicPlaybackService.ACTION_UPDATE_META);
                    startServiceSafe(i);
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

        @JavascriptInterface public String getWallpaperTheme() {
            try {
                String result = DynamicTheme.getWallpaperTheme(MusicActivity.this);
                Log.d(BRIDGE_TAG, "getWallpaperTheme=" + result);
                return result;
            } catch (Throwable e) {
                Log.e(BRIDGE_TAG, "getWallpaperTheme failed", e);
                return "{\"ok\":false,\"error\":\"exception\"}";
            }
        }

        @JavascriptInterface public boolean getSystemDark() {
            int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return mode == Configuration.UI_MODE_NIGHT_YES;
        }

        @JavascriptInterface public int getStatusBarHeightPx() {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
        }

        @JavascriptInterface public int getNavBarHeightPx() {
            int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
        }

        @JavascriptInterface public void setBarsLight(final boolean light) {
            runOnUiThread(() -> setSystemBarsLight(light));
        }
    }
}