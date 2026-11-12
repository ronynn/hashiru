package io.github.ronynn.hashiru;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

public class MusicPlaybackService extends Service {

    public static final String ACTION_MEDIA_CMD = "io.github.ronynn.hashiru.MUSIC_MEDIA_CMD";

    public static final String ACTION_START      = "io.github.ronynn.hashiru.MUSIC_START";
    public static final String ACTION_UPDATE_META = "io.github.ronynn.hashiru.MUSIC_META";
    public static final String ACTION_PAUSE      = "io.github.ronynn.hashiru.MUSIC_PAUSE";
    public static final String ACTION_STOP       = "io.github.ronynn.hashiru.MUSIC_STOP";

    public static final String EXTRA_TITLE  = "title";
    public static final String EXTRA_ARTIST = "artist";

    private static final String CHANNEL_ID = "hashiru_music_playback";
    private static final int NOTIF_ID = 0x4D555349; // "MUSI"
    private static final int NOTIF_COLOR = 0xFF7AA2F7;

    private MediaSession mediaSession;
    private boolean playing = false;
    private String title = "Music";
    private String artist = "";

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        ensureSession();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;

        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }

        if (ACTION_PAUSE.equals(action)) {
            playing = false;
        } else if (ACTION_START.equals(action)) {
            playing = true;
        } else if (ACTION_UPDATE_META.equals(action) && intent != null) {
            String t = intent.getStringExtra(EXTRA_TITLE);
            String a = intent.getStringExtra(EXTRA_ARTIST);
            if (t != null && !t.isEmpty()) title = t;
            if (a != null) artist = a;
        }

        startForegroundSafely();
        updateNotification();
        updateSession();
        return START_NOT_STICKY;
    }

    private void startForegroundSafely() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private Notification buildNotification() {
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;

        Intent openIntent = new Intent(this, MusicActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent, piFlags);

        Intent toggleIntent = new Intent(ACTION_MEDIA_CMD)
                .setPackage(getPackageName())
                .putExtra("cmd", playing ? "pause" : "play");
        PendingIntent togglePi = PendingIntent.getBroadcast(this, 1, toggleIntent, piFlags);

        Notification.MediaStyle style = new Notification.MediaStyle().setShowActionsInCompactView(0);
        if (mediaSession != null) style.setMediaSession(mediaSession.getSessionToken());

        int iconRes = getApplicationInfo().icon;
        if (iconRes == 0) iconRes = android.R.drawable.ic_media_play;

        Bitmap bigIcon = null;
        try { bigIcon = BitmapFactory.decodeResource(getResources(), iconRes); }
        catch (Exception ignored) {}

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CHANNEL_ID);
        else b = new Notification.Builder(this);

        b.setSmallIcon(iconRes)
         .setContentTitle(title)
         .setContentText(playing ? (artist.isEmpty() ? "Playing" : artist) : "Paused")
         .setContentIntent(contentIntent)
         .setStyle(style)
         .setOnlyAlertOnce(true)
         .setOngoing(playing)
         .setColor(NOTIF_COLOR)
         .setColorized(true)
         .setVisibility(Notification.VISIBILITY_PUBLIC)
         .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                 playing ? "Pause" : "Play", togglePi);

        if (bigIcon != null) b.setLargeIcon(bigIcon);
        return b.build();
    }

    private void updateNotification() {
        try {
            NotificationManager nm = Build.VERSION.SDK_INT >= 23
                    ? getSystemService(NotificationManager.class)
                    : (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification());
        } catch (SecurityException ignored) {}
    }

    private void ensureSession() {
        if (Build.VERSION.SDK_INT < 21) return;
        if (mediaSession != null) return;
        mediaSession = new MediaSession(this, "HashiruMusic");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay()  { broadcast("play"); }
            @Override public void onPause() { broadcast("pause"); }
            @Override public void onStop()  { broadcast("stop"); }
        });
        mediaSession.setActive(true);
    }

    private void updateSession() {
        if (mediaSession == null) return;
        mediaSession.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .build());
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY
                        | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build());
    }

    private void broadcast(String cmd) {
        Intent i = new Intent(ACTION_MEDIA_CMD)
                .setPackage(getPackageName())
                .putExtra("cmd", cmd);
        sendBroadcast(i);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Music playback", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Now playing");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private void shutdown() {
        try {
            if (Build.VERSION.SDK_INT >= 24)
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
        } catch (Exception ignored) {}
        try {
            NotificationManager nm = Build.VERSION.SDK_INT >= 23
                    ? getSystemService(NotificationManager.class)
                    : (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_ID);
        } catch (Exception ignored) {}
        if (mediaSession != null) {
            try {
                mediaSession.setPlaybackState(new PlaybackState.Builder()
                        .setState(PlaybackState.STATE_STOPPED, 0, 1.0f).build());
                mediaSession.setActive(false);
                mediaSession.release();
            } catch (Exception ignored) {}
            mediaSession = null;
        }
        stopSelf();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // User swiped the app from recents → tell the activity to stop playback,
        // then shut ourselves down.
        broadcast("stop");
        Intent stopIntent = new Intent(this, MusicPlaybackService.class);
        stopIntent.setAction(ACTION_STOP);
        try { startService(stopIntent); } catch (Exception ignored) {}
        shutdown();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        try {
            if (Build.VERSION.SDK_INT >= 24)
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}