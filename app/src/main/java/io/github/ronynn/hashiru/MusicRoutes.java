package io.github.ronynn.hashiru;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import fi.iki.elonen.NanoHTTPD;

/** Music routes: library scan, Range-aware stream, covers, favs, metadata, assets. */
final class MusicRoutes {

    private static final String TAG = "MusicRoutes";
    private static final String MUSIC_PREFS = "hashiru_music";

    private final ContentResolver resolver;
    private final Context appContext;

    private final Map<String, String> assetCache = new ConcurrentHashMap<>();
    private final Map<Long, byte[]> coverCache = new ConcurrentHashMap<>();
    private final Map<Long, String> mimeCache = new ConcurrentHashMap<>();

    private static final String PLACEHOLDER_SVG =
            "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100' width='100' height='100'>"
          + "<rect width='100' height='100' fill='#1a1a1a'/>"
          + "<text x='50' y='68' font-size='52' text-anchor='middle' fill='#555' "
          + "font-family='monospace'>\u266A</text></svg>";

    MusicRoutes(ContentResolver cr, Context ctx) {
        this.resolver = cr;
        this.appContext = ctx;
    }

    NanoHTTPD.Response serve(String uri, NanoHTTPD.IHTTPSession session) {
        if (resolver == null || appContext == null)
            return HttpUtils.plain(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                    "Music not ready");

        if (uri.equals("_music") || uri.equals("_music/") || uri.equals("_music/index.html"))
            return serveAsset("music.html", "text/html");
        if (uri.equals("_music/music.css"))
            return serveAsset("music.css", "text/css");
        if (uri.equals("_music/lib"))
            return serveMusicLib();
        if (uri.startsWith("_music/stream/"))
            return serveMusicStream(uri.substring("_music/stream/".length()), session);
        if (uri.startsWith("_music/cover/"))
            return serveMusicCover(uri.substring("_music/cover/".length()));
        if (uri.startsWith("_music/fav"))
            return serveMusicFav(session);
        if (uri.startsWith("_music/metadata"))
            return serveMusicMetadata(session);

        return HttpUtils.plain(NanoHTTPD.Response.Status.NOT_FOUND, "Music route not found");
    }

    private NanoHTTPD.Response serveAsset(String assetPath, String mime) {
        String content = assetCache.get(assetPath);
        if (content == null) {
            try {
                InputStream in = appContext.getAssets().open(assetPath);
                content = HttpUtils.readAll(in);
                assetCache.put(assetPath, content);
            } catch (IOException e) {
                return HttpUtils.plain(NanoHTTPD.Response.Status.NOT_FOUND,
                        "asset missing: " + assetPath);
            }
        }
        NanoHTTPD.Response r = NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, mime + "; charset=utf-8", content);
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    private boolean hasAudioPermission() {
        if (appContext == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return appContext.checkSelfPermission("android.permission.READ_MEDIA_AUDIO")
                        == PackageManager.PERMISSION_GRANTED;
            } else {
                return appContext.checkSelfPermission(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE)
                        == PackageManager.PERMISSION_GRANTED;
            }
        } catch (Exception e) { return false; }
    }

    private NanoHTTPD.Response serveMusicLib() {
        StringBuilder sb = new StringBuilder(4096);
        boolean hasPerm = hasAudioPermission();
        sb.append("{\"hasPermission\":").append(hasPerm).append(",\"songs\":[");

        if (hasPerm) {
            SharedPreferences prefs = appContext.getSharedPreferences(
                    MUSIC_PREFS, Context.MODE_PRIVATE);
            Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            String[] proj = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.TRACK,
                    MediaStore.Audio.Media.MIME_TYPE
            };
            String sel = MediaStore.Audio.Media.IS_MUSIC + " != 0";
            String sort = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC";

            Cursor c = null;
            try {
                c = resolver.query(uri, proj, sel, null, sort);
                if (c != null) {
                    int idxId     = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                    int idxTitle  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                    int idxArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                    int idxAlbum  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                    int idxDur    = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                    int idxData   = c.getColumnIndex(MediaStore.Audio.Media.DATA);
                    int idxTrack  = c.getColumnIndex(MediaStore.Audio.Media.TRACK);
                    int idxBucket = c.getColumnIndex("bucket_display_name");

                    boolean first = true;
                    while (c.moveToNext()) {
                        long id = c.getLong(idxId);
                        String title  = c.getString(idxTitle);
                        String artist = c.getString(idxArtist);
                        String album  = c.getString(idxAlbum);
                        long duration = c.getLong(idxDur);
                        int track = (idxTrack >= 0 && !c.isNull(idxTrack)) ? c.getInt(idxTrack) : 0;

                        String oTitle  = prefs.getString("meta_" + id + "_title",  null);
                        String oArtist = prefs.getString("meta_" + id + "_artist", null);
                        String oAlbum  = prefs.getString("meta_" + id + "_album",  null);
                        String oTrack  = prefs.getString("meta_" + id + "_track",  null);
                        if (oTitle != null)  title  = oTitle;
                        if (oArtist != null) artist = oArtist;
                        if (oAlbum != null)  album  = oAlbum;
                        if (oTrack != null) {
                            try { track = Integer.parseInt(oTrack); } catch (Exception ignored) {}
                        }

                        String folder = null;
                        if (idxBucket >= 0 && !c.isNull(idxBucket))
                            folder = c.getString(idxBucket);
                        if (folder == null || folder.isEmpty()) {
                            String path = idxData >= 0 ? c.getString(idxData) : null;
                            if (path != null) {
                                int slash = path.lastIndexOf('/');
                                if (slash > 0) {
                                    int prev = path.lastIndexOf('/', slash - 1);
                                    folder = prev > 0 ? path.substring(prev + 1, slash)
                                                      : path.substring(0, slash);
                                }
                            }
                        }
                        if (folder == null || folder.isEmpty()) folder = "Unknown";

                        boolean isFav = prefs.getBoolean("fav_" + id, false);

                        if (!first) sb.append(',');
                        first = false;
                        sb.append("{\"id\":").append(id)
                          .append(",\"title\":").append(HttpUtils.jsonStr(title))
                          .append(",\"artist\":").append(HttpUtils.jsonStr(artist))
                          .append(",\"album\":").append(HttpUtils.jsonStr(album))
                          .append(",\"folder\":").append(HttpUtils.jsonStr(folder))
                          .append(",\"track\":").append(track)
                          .append(",\"duration\":").append(duration)
                          .append(",\"isFav\":").append(isFav)
                          .append('}');
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "music lib scan failed", e);
            } finally {
                if (c != null) c.close();
            }
        }

        sb.append("]}");
        NanoHTTPD.Response r = NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json; charset=utf-8", sb.toString());
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    private NanoHTTPD.Response serveMusicStream(String idStr, NanoHTTPD.IHTTPSession session) {
        long id;
        try { id = Long.parseLong(idStr); }
        catch (Exception e) {
            return HttpUtils.plain(NanoHTTPD.Response.Status.BAD_REQUEST, "bad id");
        }

        Uri audioUri = ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);

        String mime = mimeCache.get(id);
        if (mime == null) {
            mime = "audio/mpeg";
            Cursor c = null;
            try {
                c = resolver.query(audioUri,
                        new String[]{MediaStore.Audio.Media.MIME_TYPE}, null, null, null);
                if (c != null && c.moveToFirst()) {
                    String m = c.getString(0);
                    if (m != null && !m.isEmpty()) mime = m;
                }
            } catch (Exception ignored) {
            } finally { if (c != null) c.close(); }
            mimeCache.put(id, mime);
        }

        ParcelFileDescriptor pfd = null;
        try { pfd = resolver.openFileDescriptor(audioUri, "r"); }
        catch (Exception e) {
            return HttpUtils.plain(NanoHTTPD.Response.Status.NOT_FOUND, "cannot open");
        }
        if (pfd == null)
            return HttpUtils.plain(NanoHTTPD.Response.Status.NOT_FOUND, "cannot open");

        long fileLength = pfd.getStatSize();
        if (fileLength <= 0) {
            try { pfd.close(); } catch (Exception ignored) {}
            return HttpUtils.plain(NanoHTTPD.Response.Status.INTERNAL_ERROR, "unknown length");
        }

        long start = 0, end = fileLength - 1;
        boolean partial = false;

        String range = HttpUtils.headerIgnoreCase(session, "range");
        if (range != null && range.startsWith("bytes=")) {
            String spec = range.substring(6).trim();
            int dash = spec.indexOf('-');
            if (dash >= 0) {
                try {
                    String sStr = spec.substring(0, dash).trim();
                    String eStr = spec.substring(dash + 1).trim();
                    if (!sStr.isEmpty()) start = Long.parseLong(sStr);
                    if (!eStr.isEmpty()) end = Math.min(Long.parseLong(eStr), fileLength - 1);
                    if (start < 0) start = 0;
                    if (start > end || start >= fileLength) {
                        try { pfd.close(); } catch (Exception ignored) {}
                        NanoHTTPD.Response r = HttpUtils.plain(
                                NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE, "");
                        r.addHeader("Content-Range", "bytes */" + fileLength);
                        return r;
                    }
                    partial = true;
                } catch (Exception ignored) {
                    start = 0; end = fileLength - 1; partial = false;
                }
            }
        }

        long contentLength = end - start + 1;
        try {
            FileDescriptor fd = pfd.getFileDescriptor();
            FileInputStream fis = new FileInputStream(fd);
            if (start > 0) {
                long skipped = 0;
                while (skipped < start) {
                    long s = fis.skip(start - skipped);
                    if (s <= 0) break;
                    skipped += s;
                }
            }
            InputStream bounded = new HttpUtils.BoundedInputStream(fis, contentLength);
            InputStream auto = new HttpUtils.AutoCloseInputStream(bounded, pfd);

            NanoHTTPD.Response.Status status = partial
                    ? NanoHTTPD.Response.Status.PARTIAL_CONTENT
                    : NanoHTTPD.Response.Status.OK;
            NanoHTTPD.Response r = NanoHTTPD.newFixedLengthResponse(
                    status, mime, auto, contentLength);
            r.addHeader("Accept-Ranges", "bytes");
            if (partial)
                r.addHeader("Content-Range",
                        "bytes " + start + "-" + end + "/" + fileLength);
            r.addHeader("Cache-Control", "no-cache");
            return r;
        } catch (Exception e) {
            try { pfd.close(); } catch (Exception ignored) {}
            return HttpUtils.plain(NanoHTTPD.Response.Status.INTERNAL_ERROR, "stream failed");
        }
    }

    private NanoHTTPD.Response serveMusicCover(String idStr) {
        long id;
        try { id = Long.parseLong(idStr); }
        catch (Exception e) { return serveSvg(); }

        File coverDir = new File(appContext.getFilesDir(), "covers");
        File[] matches = coverDir.listFiles((d, name) -> name.startsWith(id + "."));
        if (matches != null && matches.length > 0) {
            File f = matches[0];
            String mime = f.getName().endsWith(".png") ? "image/png"
                        : f.getName().endsWith(".webp") ? "image/webp"
                        : f.getName().endsWith(".gif") ? "image/gif"
                        : "image/jpeg";
            try {
                InputStream in = new FileInputStream(f);
                NanoHTTPD.Response r = NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.OK, mime, in, f.length());
                r.addHeader("Cache-Control", "no-cache");
                return r;
            } catch (IOException e) { /* fall through */ }
        }

        byte[] cached = coverCache.get(id);
        if (cached != null) {
            NanoHTTPD.Response r = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK, "image/jpeg",
                    new ByteArrayInputStream(cached), cached.length);
            r.addHeader("Cache-Control", "max-age=86400");
            return r;
        }

        Uri audioUri = ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(appContext, audioUri);
            byte[] pic = mmr.getEmbeddedPicture();
            if (pic != null && pic.length > 0) {
                coverCache.put(id, pic);
                NanoHTTPD.Response r = NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.OK, "image/jpeg",
                        new ByteArrayInputStream(pic), pic.length);
                r.addHeader("Cache-Control", "max-age=86400");
                return r;
            }
        } catch (Exception ignored) {
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
        return serveSvg();
    }

    private NanoHTTPD.Response serveSvg() {
        byte[] bytes = PLACEHOLDER_SVG.getBytes(StandardCharsets.UTF_8);
        NanoHTTPD.Response r = NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, "image/svg+xml",
                new ByteArrayInputStream(bytes), bytes.length);
        r.addHeader("Cache-Control", "max-age=86400");
        return r;
    }

    private NanoHTTPD.Response serveMusicFav(NanoHTTPD.IHTTPSession session) {
        Map<String, String> parms = session.getParms();
        String idStr = parms.get("id");
        String onStr = parms.get("on");
        if (idStr == null)
            return HttpUtils.plain(NanoHTTPD.Response.Status.BAD_REQUEST, "missing id");
        long id;
        try { id = Long.parseLong(idStr); }
        catch (Exception e) {
            return HttpUtils.plain(NanoHTTPD.Response.Status.BAD_REQUEST, "bad id");
        }
        boolean on = "1".equals(onStr) || "true".equalsIgnoreCase(onStr);

        SharedPreferences prefs = appContext.getSharedPreferences(
                MUSIC_PREFS, Context.MODE_PRIVATE);
        prefs.edit().putBoolean("fav_" + id, on).apply();

        String body = "{\"ok\":true,\"id\":" + id + ",\"isFav\":" + on + "}";
        return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, "application/json", body);
    }

    private NanoHTTPD.Response serveMusicMetadata(NanoHTTPD.IHTTPSession session) {
        Map<String, String> parms = session.getParms();
        String idStr = parms.get("id");
        if (idStr == null)
            return HttpUtils.plain(NanoHTTPD.Response.Status.BAD_REQUEST, "missing id");
        long id;
        try { id = Long.parseLong(idStr); }
        catch (Exception e) {
            return HttpUtils.plain(NanoHTTPD.Response.Status.BAD_REQUEST, "bad id");
        }

        boolean isUpdate = parms.containsKey("title")
                        || parms.containsKey("artist")
                        || parms.containsKey("album")
                        || parms.containsKey("track");

        if (isUpdate) {
            boolean ok = saveMetadata(id,
                    parms.get("title"), parms.get("artist"),
                    parms.get("album"), parms.get("track"));
            return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK, "application/json",
                    "{\"ok\":" + ok + ",\"id\":" + id + "}");
        }

        SharedPreferences prefs = appContext.getSharedPreferences(
                MUSIC_PREFS, Context.MODE_PRIVATE);
        String title  = prefs.getString("meta_" + id + "_title",  null);
        String artist = prefs.getString("meta_" + id + "_artist", null);
        String album  = prefs.getString("meta_" + id + "_album",  null);
        String track  = prefs.getString("meta_" + id + "_track",  null);

        if (title == null || artist == null || album == null || track == null) {
            Uri audioUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
            Cursor c = null;
            try {
                c = resolver.query(audioUri,
                        new String[]{MediaStore.Audio.Media.TITLE,
                                     MediaStore.Audio.Media.ARTIST,
                                     MediaStore.Audio.Media.ALBUM,
                                     MediaStore.Audio.Media.TRACK},
                        null, null, null);
                if (c != null && c.moveToFirst()) {
                    if (title == null)  title = c.getString(0);
                    if (artist == null) artist = c.getString(1);
                    if (album == null)  album = c.getString(2);
                    if (track == null)  track = String.valueOf(c.getInt(3));
                }
            } catch (Exception ignored) {
            } finally { if (c != null) c.close(); }
        }

        StringBuilder sb = new StringBuilder("{");
        sb.append("\"title\":").append(HttpUtils.jsonStr(title)).append(',');
        sb.append("\"artist\":").append(HttpUtils.jsonStr(artist)).append(',');
        sb.append("\"album\":").append(HttpUtils.jsonStr(album)).append(',');
        sb.append("\"track\":").append(HttpUtils.jsonStr(track));
        sb.append("}");
        return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, "application/json", sb.toString());
    }

    private boolean saveMetadata(long id, String title, String artist,
                                 String album, String track) {
        SharedPreferences prefs = appContext.getSharedPreferences(
                MUSIC_PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = prefs.edit();
        if (title != null)  e.putString("meta_" + id + "_title",  title);
        if (artist != null) e.putString("meta_" + id + "_artist", artist);
        if (album != null)  e.putString("meta_" + id + "_album",  album);
        if (track != null)  e.putString("meta_" + id + "_track",  track);
        e.apply();

        Uri audioUri = ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
        android.content.ContentValues cv = new android.content.ContentValues();
        if (title != null)  cv.put(MediaStore.Audio.Media.TITLE,  title);
        if (artist != null) cv.put(MediaStore.Audio.Media.ARTIST, artist);
        if (album != null)  cv.put(MediaStore.Audio.Media.ALBUM,  album);
        if (track != null) {
            try { cv.put(MediaStore.Audio.Media.TRACK, Integer.parseInt(track)); }
            catch (Exception ignored) {}
        }
        if (cv.size() > 0) {
            try { resolver.update(audioUri, cv, null, null); }
            catch (Exception ex) { Log.w(TAG, "MediaStore update failed", ex); }
        }

        String mime = getAudioMimeFor(id);
        if (mime != null && (mime.contains("mpeg") || mime.contains("mp3"))) {
            try {
                Id3TagWriter.writeTextFrames(resolver, audioUri,
                        title, artist, album, track);
            } catch (Exception ex) { Log.w(TAG, "ID3 write failed", ex); }
        }
        return true;
    }

    private String getAudioMimeFor(long id) {
        Uri audioUri = ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
        Cursor c = null;
        try {
            c = resolver.query(audioUri,
                    new String[]{MediaStore.Audio.Media.MIME_TYPE}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        } finally { if (c != null) c.close(); }
        return null;
    }
}