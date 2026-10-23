package io.github.ronynn.hashiru;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WebViewActivity extends Activity {

    public static final String EXTRA_TREE_URI = "tree_uri";
    public static final String EXTRA_REL_PATH = "rel_path";

    private static final String TAG = "WebViewActivity";
    private static final String POS_PREFS = "hashiru_positions";
    private static final int REQ_FILE_CHOOSER = 2001;
    private static final int REQ_SAVE_FILE = 2002;

    private static final int BAR_COLOR_HTML = 0xFF1A1A1A;
    private static final String CHANNEL_ID = "hashiru_media";
    private static final String ACTION_MEDIA_CMD = "io.github.ronynn.hashiru.MEDIA_CMD";
    private static final int NOTIFICATION_COLOR = 0xFF1976D2;

    private static final int MAX_READER_BYTES = 5 * 1024 * 1024;

    private static final Map<String, WeakReference<WebViewActivity>> OPEN_DOCS
            = new ConcurrentHashMap<>();

    private LocalFileServer server;
    private WebView web;
    private SharedPreferences posPrefs;
    private String posKey;
    private boolean restorePending = true;
    private int pendingRestoreY = -1;

    private boolean clearHistoryOnNextFinish = false;

    private ValueCallback<Uri[]> fileChooserCallback;
    private String currentExt = "";
    private boolean isReader = false;
    private int statusBarHeight = 0;
    private int navBarHeight = -1;
    private ReaderSettings settings;

    private String ident;
    private Uri currentDirectUri;
    private Uri currentTreeUri;
    private String currentRelPath;

    private String pendingSaveMime;
    private String pendingSaveBase64;

    private MediaSession mediaSession;
    private boolean mediaPlaying = false;
    private String currentMediaTitle = "Media";

    private final int notifId = System.identityHashCode(this) & 0x7FFFFFFF;

    private final BroadcastReceiver mediaCmdReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent i) {
            String cmd = i.getStringExtra("cmd");
            if (cmd == null) return;
            if ("toggle".equals(cmd)) sendJsMediaCmd(mediaPlaying ? "pause" : "play");
            else if ("stop".equals(cmd)) { sendJsMediaCmd("stop"); stopMediaNotification(); }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_webview);

        settings = ReaderSettings.load(this);
        statusBarHeight = getStatusBarHeight();

        String treeUriStr = getIntent().getStringExtra(EXTRA_TREE_URI);
        String relPath = getIntent().getStringExtra(EXTRA_REL_PATH);
        Uri directUri = null;
        if (!(treeUriStr != null && relPath != null)
                && Intent.ACTION_VIEW.equals(getIntent().getAction())
                && getIntent().getData() != null) {
            directUri = getIntent().getData();
        }

        if (treeUriStr != null && relPath != null) {
            ident = "tree|" + treeUriStr + "|" + relPath;
            currentExt = extOf(relPath);
        } else if (directUri != null) {
            ident = "uri|" + directUri.toString();
            currentExt = extOf(queryDisplayName(directUri));
        } else {
            finish();
            return;
        }
        isReader = isReaderExt(currentExt);

        WebViewActivity existing = findExisting(ident);
        if (existing != null && existing != this && !existing.isFinishing()) {
            try {
                ActivityManager am = (ActivityManager)
                        getSystemService(Context.ACTIVITY_SERVICE);
                am.moveTaskToFront(existing.getTaskId(), 0);
                finish();
                return;
            } catch (Exception e) {
                Log.w(TAG, "moveTaskToFront failed", e);
            }
        }
        registerOpen(ident, this);

        final int bg = isReader
                ? ReaderRenderer.backgroundArgb(settings.theme)
                : BAR_COLOR_HTML;
        getWindow().setBackgroundDrawable(new ColorDrawable(bg));

        IntentFilter filter = new IntentFilter(ACTION_MEDIA_CMD);
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(mediaCmdReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else
            registerReceiver(mediaCmdReceiver, filter);

        web = findViewById(R.id.web);
        web.setBackgroundColor(bg);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
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
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    String host = u.getHost();
                    if ("127.0.0.1".equals(host) || "localhost".equals(host)) return false;
                }
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, u);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (ActivityNotFoundException ignored) {}
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                return blockExternalForReader(u == null ? null : u.toString());
            }

            @SuppressWarnings("deprecation")
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, String url) {
                return blockExternalForReader(url);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                injectTapHighlightFix();
                injectEruda();
                if (!isReader) injectMediaHooks();

                if (pendingRestoreY >= 0) {
                    int y = pendingRestoreY;
                    pendingRestoreY = -1;
                    scrollAfterLoad(v, y);
                } else if (restorePending) {
                    restorePending = false;
                    int y = posPrefs.getInt(posKey, 0);
                    if (y > 0) scrollAfterLoad(v, y);
                }

                if (clearHistoryOnNextFinish) {
                    clearHistoryOnNextFinish = false;
                    try { v.clearHistory(); } catch (Exception ignored) {}
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = cb;
                Intent intent = params.createIntent();
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                try { startActivityForResult(intent, REQ_FILE_CHOOSER); }
                catch (ActivityNotFoundException e) {
                    fileChooserCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.setDownloadListener(new DownloadListener() {
            @Override public void onDownloadStart(String url, String ua, String cd,
                                                  String mime, long len) {
                handleDownload(url, mime);
            }
        });

        if (treeUriStr != null && relPath != null) {
            openFromTree(treeUriStr, relPath);
        } else {
            openDirectFile(directUri);
        }
    }

    /**
     * Drop any subresource request originating outside of the local server
     * when in reader mode. Reader content is not supposed to reach the net.
     */
    private WebResourceResponse blockExternalForReader(String url) {
        if (!isReader || url == null) return null;
        Uri u = Uri.parse(url);
        String scheme = u.getScheme();
        if (scheme == null) return null;
        if ("data".equals(scheme) || "blob".equals(scheme) || "about".equals(scheme))
            return null;
        String host = u.getHost();
        if ("127.0.0.1".equals(host) || "localhost".equals(host)) return null;
        // Empty response = dropped. Equivalent to a broken image / failed XHR.
        return new WebResourceResponse("text/plain", "utf-8",
                new ByteArrayInputStream(new byte[0]));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (web == null) return;
        if (isReader) {
            if (pendingRestoreY < 0) pendingRestoreY = web.getScrollY();
            reloadCurrentFile();
        } else {
            web.reload();
        }
    }

    private static WebViewActivity findExisting(String ident) {
        if (ident == null) return null;
        WeakReference<WebViewActivity> ref = OPEN_DOCS.get(ident);
        return ref == null ? null : ref.get();
    }

    private static void registerOpen(String ident, WebViewActivity act) {
        if (ident == null) return;
        OPEN_DOCS.put(ident, new WeakReference<>(act));
    }

    private static void unregisterOpen(String ident, WebViewActivity act) {
        if (ident == null) return;
        WeakReference<WebViewActivity> ref = OPEN_DOCS.get(ident);
        if (ref != null && ref.get() == act) OPEN_DOCS.remove(ident);
    }

    private void openFromTree(String treeUriStr, String relPath) {
        posKey = treeUriStr + "|" + relPath;
        currentTreeUri = Uri.parse(treeUriStr);
        currentRelPath = relPath;

        if (Build.VERSION.SDK_INT >= 21)
            setTaskDescription(new ActivityManager.TaskDescription(relPath));

        try { server = LocalFileServer.acquire(currentTreeUri, getContentResolver(), this); }
        catch (IOException e) { finish(); return; }

        applyOrientation(getResources().getConfiguration().orientation);
        web.loadUrl(buildServerUrl());
    }

    private void openDirectFile(Uri fileUri) {
        currentDirectUri = fileUri;
        posKey = fileUri.toString();

        if (Build.VERSION.SDK_INT >= 21)
            setTaskDescription(new ActivityManager.TaskDescription(
                    queryDisplayName(fileUri)));

        applyOrientation(getResources().getConfiguration().orientation);

        try {
            InputStream in = getContentResolver().openInputStream(fileUri);
            if (in == null) { finish(); return; }
            String raw = readAllCapped(in);
            String baseUrl = "https://hashiru.local/"
                    + Uri.encode(fileUri.toString()) + "/";

            if (currentExt.equals("html") || currentExt.equals("htm")) {
                web.loadDataWithBaseURL(baseUrl, raw, "text/html", "utf-8", null);
                return;
            }
            String html;
            if (isReader) {
                html = ReaderRenderer.render(
                        currentExt, raw, settings,
                        currentSbhDp(), currentNbhDp(), settings.blurEnabled);
            } else {
                html = ReaderRenderer.render(
                        "txt", raw, settings,
                        currentSbhDp(), currentNbhDp(), settings.blurEnabled);
            }
            web.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null);
        } catch (IOException e) { finish(); }
    }

    private String buildServerUrl() {
        return "http://127.0.0.1:" + server.getListeningPort()
                + "/" + encodePath(currentRelPath)
                + "?sbh=" + currentSbhDp()
                + "&nbh=" + currentNbhDp()
                + "&blur=" + (settings.blurEnabled ? "1" : "0");
    }

    private int currentSbhDp() {
        if (!isReader) return 0;
        if (statusBarHeight <= 0) return 0;
        float density = getResources().getDisplayMetrics().density;
        return Math.round(statusBarHeight / density);
    }

    private int currentNbhDp() {
        if (!isReader) return 0;
        int px = getNavigationBarHeight();
        if (px <= 0) return 0;
        float density = getResources().getDisplayMetrics().density;
        return Math.round(px / density);
    }

    /** Reads navigation bar height from the framework resource — works pre-layout. */
    private int getNavigationBarHeight() {
        if (navBarHeight >= 0) return navBarHeight;
        try {
            int id = getResources().getIdentifier(
                    "navigation_bar_height", "dimen", "android");
            if (id > 0) {
                navBarHeight = getResources().getDimensionPixelSize(id);
                return navBarHeight;
            }
        } catch (Exception ignored) {}
        navBarHeight = 0;
        return 0;
    }

    private void scrollAfterLoad(WebView v, int y) {
        if (y <= 0) return;
        final String js = "(function(){var y=" + y + ";"
                + "function s(){try{"
                + "  var max=Math.max(0,document.documentElement.scrollHeight-window.innerHeight);"
                + "  window.scrollTo(0,Math.min(y,max));"
                + "}catch(e){}}"
                + "requestAnimationFrame(function(){requestAnimationFrame(s);});"
                + "setTimeout(s,80);"
                + "setTimeout(s,250);"
                + "})();";
        v.postDelayed(() -> {
            try { v.evaluateJavascript(js, null); } catch (Exception ignored) {}
        }, 30);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ReaderSettings fresh = ReaderSettings.load(this);
        boolean appearanceChanged = !fresh.theme.equals(settings.theme)
                || !fresh.font.equals(settings.font)
                || fresh.fontSize != settings.fontSize;
        boolean blurChanged = fresh.blurEnabled != settings.blurEnabled;
        settings = fresh;

        if (isReader && web != null && (appearanceChanged || blurChanged)) {
            int themeBg = ReaderRenderer.backgroundArgb(settings.theme);
            getWindow().setBackgroundDrawable(new ColorDrawable(themeBg));
            web.setBackgroundColor(themeBg);
            applyOrientation(getResources().getConfiguration().orientation);
            if (pendingRestoreY < 0) pendingRestoreY = web.getScrollY();
            reloadCurrentFile();
        }
        if (!mediaPlaying) injectEruda();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        applyOrientation(cfg.orientation);
        // No reload — @media (orientation:landscape) handles the layout
        // (padding, side bars, fade hiding) purely in CSS.
    }

    private void reloadCurrentFile() {
        if (web == null) return;
        if (isReader) clearHistoryOnNextFinish = true;
        if (server != null && currentRelPath != null) {
            web.loadUrl(buildServerUrl());
        } else if (currentDirectUri != null) {
            try {
                InputStream in = getContentResolver().openInputStream(currentDirectUri);
                if (in == null) return;
                String raw = readAllCapped(in);
                String html;
                if (currentExt.equals("html") || currentExt.equals("htm")) {
                    html = raw;
                } else {
                    html = ReaderRenderer.render(
                            currentExt, raw, settings,
                            currentSbhDp(), currentNbhDp(), settings.blurEnabled);
                }
                String baseUrl = "https://hashiru.local/"
                        + Uri.encode(currentDirectUri.toString()) + "/";
                web.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null);
            } catch (IOException ignored) {}
        }
    }

    public class SaveBridge {

        @JavascriptInterface
        public void downloadBase64(String mime, String base64) {
            final String m = mime, d = base64;
            runOnUiThread(() -> startSaveDocument(m, d));
        }

        @JavascriptInterface
        public boolean saveAndReload(final String source, final int scrollY) {
            if (source == null) return false;
            if (!saveSourceSync(source)) {
                Log.w(TAG, "save failed");
                return false;
            }
            pendingRestoreY = Math.max(0, scrollY);
            runOnUiThread(WebViewActivity.this::reloadCurrentFile);
            return true;
        }

        @JavascriptInterface
        public void onMediaPlay(final String title) {
            runOnUiThread(() -> {
                mediaPlaying = true;
                currentMediaTitle = (title == null || title.isEmpty()) ? "Media" : title;
                ensureMediaSession();
                showOrUpdateMediaNotification(currentMediaTitle, true);
            });
        }

        @JavascriptInterface
        public void onMediaPause() {
            runOnUiThread(() -> {
                mediaPlaying = false;
                showOrUpdateMediaNotification(currentMediaTitle, false);
            });
        }

        @JavascriptInterface
        public void onMediaStop() {
            runOnUiThread(() -> {
                mediaPlaying = false;
                stopMediaNotification();
            });
        }
    }

    private boolean saveSourceSync(String source) {
        if (source == null) return false;
        try {
            if (currentDirectUri != null) {
                OutputStream os = getContentResolver()
                        .openOutputStream(currentDirectUri, "wt");
                if (os == null) return false;
                os.write(source.getBytes(StandardCharsets.UTF_8));
                os.close();
                return true;
            } else if (currentTreeUri != null && currentRelPath != null) {
                String docId = resolveDocId(currentTreeUri, currentRelPath);
                if (docId == null) return false;
                Uri docUri = DocumentsContract.buildDocumentUriUsingTree(
                        currentTreeUri, docId);
                OutputStream os = getContentResolver().openOutputStream(docUri, "wt");
                if (os == null) return false;
                os.write(source.getBytes(StandardCharsets.UTF_8));
                os.close();
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "save failed", e);
        }
        return false;
    }

    private String resolveDocId(Uri treeUri, String relPath) {
        String[] parts = relPath.split("/");
        String docId = DocumentsContract.getTreeDocumentId(treeUri);
        for (String part : parts) {
            docId = findChildId(treeUri, docId, part);
            if (docId == null) return null;
        }
        return docId;
    }

    private String findChildId(Uri treeUri, String parentDocId, String name) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, parentDocId);
        Cursor c = null;
        try {
            c = getContentResolver().query(children,
                    new String[]{
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME
                    }, null, null, null);
            if (c == null) return null;
            while (c.moveToNext())
                if (name.equals(c.getString(1))) return c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private void injectTapHighlightFix() {
        if (web == null) return;
        String js = "(function(){try{"
            + "var s=document.createElement('style');"
            + "s.textContent='*{-webkit-tap-highlight-color:transparent !important;}"
            + "html,body{overscroll-behavior:none;}';"
            + "document.documentElement.appendChild(s);"
            + "}catch(e){}})();";
        web.evaluateJavascript(js, null);
    }

    private void injectEruda() {
        if (web == null || settings == null || !settings.erudaEnabled) return;
        if (isReader) return;
        String js = "(function(){"
            + "if(window.__hashiruErudaLoaded)return;"
            + "window.__hashiruErudaLoaded=true;"
            + "var s=document.createElement('script');"
            + "s.src='https://cdn.jsdelivr.net/npm/eruda@3';"
            + "s.onload=function(){try{eruda.init();}catch(e){}};"
            + "s.onerror=function(){};"
            + "document.head.appendChild(s);"
            + "})();";
        web.evaluateJavascript(js, null);
    }

    private void injectMediaHooks() {
        if (web == null) return;
        String js = "(function(){"
            + "function hook(m){"
            + "  if(m.__hashiruHooked)return;m.__hashiruHooked=true;"
            + "  m.addEventListener('play',function(){"
            + "    var t=m.getAttribute('title')||m.getAttribute('data-title')"
            + "         ||document.title||'Media';"
            + "    try{__HashiruNative.onMediaPlay(t);}catch(e){}"
            + "  });"
            + "  m.addEventListener('pause',function(){"
            + "    if(m.ended)return;"
            + "    try{__HashiruNative.onMediaPause();}catch(e){}"
            + "  });"
            + "  m.addEventListener('ended',function(){"
            + "    try{__HashiruNative.onMediaStop();}catch(e){}"
            + "  });"
            + "  m.addEventListener('loadedmetadata',function(){"
            + "    if(!m.paused)try{__HashiruNative.onMediaPlay("
            + "      m.getAttribute('title')||document.title||'Media');"
            + "    }catch(e){}"
            + "  });"
            + "}"
            + "document.querySelectorAll('audio,video').forEach(hook);"
            + "if(!window.__hashiruMediaObserver){"
            + "  window.__hashiruMediaObserver=new MutationObserver(function(ms){"
            + "    ms.forEach(function(mo){mo.addedNodes.forEach(function(n){"
            + "      if(!n||!n.nodeName)return;"
            + "      if(n.nodeName==='AUDIO'||n.nodeName==='VIDEO')hook(n);"
            + "      else if(n.querySelectorAll) n.querySelectorAll('audio,video').forEach(hook);"
            + "    });});"
            + "  });"
            + "  window.__hashiruMediaObserver.observe(document.body,"
            + "    {childList:true,subtree:true});"
            + "}"
            + "})();";
        web.evaluateJavascript(js, null);
    }

    private void ensureMediaSession() {
        if (Build.VERSION.SDK_INT < 21) return;
        if (mediaSession != null) return;
        mediaSession = new MediaSession(this, "HashiruMedia_" + notifId);
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay()  { sendJsMediaCmd("play"); }
            @Override public void onPause() { sendJsMediaCmd("pause"); }
            @Override public void onStop()  { sendJsMediaCmd("stop"); stopMediaNotification(); }
        });
        mediaSession.setActive(true);
    }

    private void sendJsMediaCmd(String cmd) {
        if (web == null) return;
        String inner;
        switch (cmd) {
            case "play":
                inner = "document.querySelectorAll('audio,video')"
                      + ".forEach(function(m){try{m.play();}catch(e){}});";
                break;
            case "pause":
                inner = "document.querySelectorAll('audio,video')"
                      + ".forEach(function(m){try{m.pause();}catch(e){}});";
                break;
            case "stop":
                inner = "document.querySelectorAll('audio,video')"
                      + ".forEach(function(m){try{m.pause();m.currentTime=0;}catch(e){}});";
                break;
            default: return;
        }
        web.evaluateJavascript("try{" + inner + "}catch(e){}", null);
    }

    private void showOrUpdateMediaNotification(String title, boolean playing) {
        if (Build.VERSION.SDK_INT < 21) return;
        ensureMediaSession();
        createNotificationChannel();

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;

        Intent toggleI = new Intent(ACTION_MEDIA_CMD)
                .setPackage(getPackageName()).putExtra("cmd", "toggle");
        PendingIntent togglePi = PendingIntent.getBroadcast(this, 0, toggleI, piFlags);

        Notification.MediaStyle style = new Notification.MediaStyle()
                .setShowActionsInCompactView(0);
        if (mediaSession != null) style.setMediaSession(mediaSession.getSessionToken());

        int iconRes = getApplicationInfo().icon;
        if (iconRes == 0) iconRes = android.R.drawable.ic_media_play;

        Bitmap bigIcon = null;
        try {
            bigIcon = BitmapFactory.decodeResource(getResources(), iconRes);
        } catch (Exception ignored) {}

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CHANNEL_ID);
        else b = new Notification.Builder(this);

        b.setSmallIcon(iconRes)
         .setContentTitle(title == null ? "Media" : title)
         .setContentText(playing ? "Playing" : "Paused")
         .setStyle(style)
         .setOngoing(playing)
         .setOnlyAlertOnce(true)
         .setColor(NOTIFICATION_COLOR)
         .setColorized(true)
         .setVisibility(Notification.VISIBILITY_PUBLIC)
         .addAction(playing
                     ? android.R.drawable.ic_media_pause
                     : android.R.drawable.ic_media_play,
                 playing ? "Pause" : "Play", togglePi);

        if (bigIcon != null) b.setLargeIcon(bigIcon);

        try {
            notificationManager().notify(notifId, b.build());
        } catch (SecurityException e) {
            Log.d(TAG, "notification blocked (no POST_NOTIFICATIONS)");
        }

        if (mediaSession != null) {
            mediaSession.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE,
                            title == null ? "Media" : title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Hashiru")
                    .build());
            PlaybackState state = new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY
                            | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_STOP)
                    .setState(
                            playing ? PlaybackState.STATE_PLAYING
                                    : PlaybackState.STATE_PAUSED,
                            PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                            1.0f)
                    .build();
            mediaSession.setPlaybackState(state);
        }
    }

    private void stopMediaNotification() {
        try { notificationManager().cancel(notifId); } catch (Exception ignored) {}
        mediaPlaying = false;
        if (mediaSession != null) {
            mediaSession.setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_STOPPED, 0, 1.0f).build());
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
    }

    @SuppressWarnings("deprecation")
    private NotificationManager notificationManager() {
        if (Build.VERSION.SDK_INT >= 23)
            return getSystemService(NotificationManager.class);
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Media playback", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Playback controls");
        ch.setShowBadge(false);
        notificationManager().createNotificationChannel(ch);
    }

    private void handleDownload(String url, String mimeFromHeader) {
        if (url == null) return;
        if (url.startsWith("blob:")) { requestBlobAsBase64(url, mimeFromHeader); return; }
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
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException ignored) {}
    }

    private void requestBlobAsBase64(String blobUrl, String mime) {
        String safeUrl = jsString(blobUrl);
        String safeMime = jsString(mime == null ? "" : mime);
        String js = "(function(){try{fetch(" + safeUrl + ").then(function(r){return r.blob();})"
                + ".then(function(b){var fr=new FileReader();"
                + "fr.onload=function(){var s=fr.result;var i=s.indexOf(',');"
                + "var b64=(i>=0?s.substring(i+1):s);"
                + "__HashiruNative.downloadBase64(b.type||" + safeMime + ",b64);};"
                + "fr.onerror=function(){};fr.readAsDataURL(b);}).catch(function(){});"
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

    private void startSaveDocument(String mime, String base64) {
        if (base64 == null) return;
        pendingSaveMime = (mime == null || mime.isEmpty())
                ? "application/octet-stream" : mime;
        pendingSaveBase64 = base64;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(pendingSaveMime);
        i.putExtra(Intent.EXTRA_TITLE, suggestNameFor(pendingSaveMime));
        try { startActivityForResult(i, REQ_SAVE_FILE); }
        catch (ActivityNotFoundException e) {
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

    private String extOf(String pathOrName) {
        if (pathOrName == null) return "";
        int slash = pathOrName.lastIndexOf('/');
        String name = slash >= 0 ? pathOrName.substring(slash + 1) : pathOrName;
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private boolean isReaderExt(String ext) {
        return ext.equals("md") || ext.equals("markdown")
                || ext.equals("txt") || ext.equals("org") || ext.equals("twee")
                || ext.equals("csv");
    }

    private String queryDisplayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return uri.getLastPathSegment();
    }

    private String readAllCapped(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_READER_BYTES) {
                bos.write(buf, 0, n - (total - MAX_READER_BYTES));
                bos.write("\n\n---\n_[truncated at 5 MB]_\n"
                        .getBytes(StandardCharsets.UTF_8));
                break;
            }
            bos.write(buf, 0, n);
        }
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
                        os.write(Base64.decode(pendingSaveBase64, Base64.DEFAULT));
                        os.flush();
                        os.close();
                    }
                } catch (IOException ignored) {}
            }
            pendingSaveBase64 = null;
            pendingSaveMime = null;
        }
    }

    private int getStatusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) return getResources().getDimensionPixelSize(id);
        return 0;
    }

    private String encodePath(String path) {
        if (path == null) return "";
        StringBuilder sb = new StringBuilder();
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(Uri.encode(parts[i]));
        }
        return sb.toString();
    }

    private void applyOrientation(int orientation) {
        Window win = getWindow();
        View decor = win.getDecorView();
        WindowManager.LayoutParams lp = win.getAttributes();

        if (!isReader && orientation == Configuration.ORIENTATION_LANDSCAPE) {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
            if (Build.VERSION.SDK_INT >= 30)
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            else if (Build.VERSION.SDK_INT >= 28)
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            win.setAttributes(lp);
            return;
        }

        if (isReader) {
            // Transparent status bar AND transparent nav bar — content flows
            // under both. HTML top/bottom padding keeps text clear of chrome;
            // CSS overlay handles the top/bottom fade in portrait.
            win.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            win.setStatusBarColor(Color.TRANSPARENT);
            win.setNavigationBarColor(Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= 28)
                win.setNavigationBarDividerColor(Color.TRANSPARENT);

            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (settings.isLightTheme()) {
                if (Build.VERSION.SDK_INT >= 23)
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26)
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(flags);

            if (Build.VERSION.SDK_INT >= 28)
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
        } else {
            // HTML portrait: opaque chrome.
            win.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            win.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            win.setStatusBarColor(BAR_COLOR_HTML);
            win.setNavigationBarColor(BAR_COLOR_HTML);
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            if (Build.VERSION.SDK_INT >= 28)
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
        }
        win.setAttributes(lp);
    }

    private void savePosition() {
        if (web == null || posPrefs == null || posKey == null) return;
        posPrefs.edit().putInt(posKey, web.getScrollY()).apply();
    }

    @Override protected void onPause()  { savePosition(); super.onPause(); }
    @Override protected void onStop()   { savePosition(); super.onStop(); }

    @Override
    protected void onDestroy() {
        unregisterOpen(ident, this);
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }
        try { unregisterReceiver(mediaCmdReceiver); } catch (Exception ignored) {}
        if (isFinishing()) stopMediaNotification();
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}