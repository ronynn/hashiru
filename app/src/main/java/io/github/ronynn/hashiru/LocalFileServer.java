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
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import fi.iki.elonen.NanoHTTPD;

public class LocalFileServer extends NanoHTTPD {

    private static final String TAG = "LocalFileServer";
    private static final int PORT = 17463;
    private static final String MUSIC_PREFS = "hashiru_music";

    private static LocalFileServer instance;

    private Uri treeUri;
    private ContentResolver resolver;
    private Context appContext;
    private String rootDocId;

    private final Map<String, String> assetCache = new ConcurrentHashMap<>();
    private final Map<Long, byte[]> coverCache = new ConcurrentHashMap<>();
    private final Map<Long, String> mimeCache = new ConcurrentHashMap<>();

    private static final Set<String> READER_EXTS = new HashSet<>(
            Arrays.asList("md", "markdown", "txt", "org", "twee", "csv"));

    private static final String PLACEHOLDER_SVG =
            "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100' width='100' height='100'>"
          + "<rect width='100' height='100' fill='#1a1a1a'/>"
          + "<text x='50' y='68' font-size='52' text-anchor='middle' fill='#555' "
          + "font-family='monospace'>\u266A</text></svg>";

    private LocalFileServer() { super("127.0.0.1", PORT); }

    public static synchronized LocalFileServer acquire(Uri treeUri, ContentResolver cr, Context ctx)
            throws IOException {
        if (instance == null) {
            instance = new LocalFileServer();
            instance.start(SOCKET_READ_TIMEOUT, false);
        }
        if (treeUri != null) {
            instance.treeUri = treeUri;
            instance.rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
        }
        instance.resolver = cr;
        instance.appContext = ctx.getApplicationContext();
        return instance;
    }

    public String origin() { return "http://127.0.0.1:" + PORT; }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if (uri.startsWith("/")) uri = uri.substring(1);

        for (String seg : uri.split("/"))
            if ("..".equals(seg)) return plain(Response.Status.FORBIDDEN, "Forbidden");

        if (uri.equals("_music") || uri.startsWith("_music/"))
            return serveMusic(uri, session);

        if (treeUri == null || resolver == null)
            return plain(Response.Status.SERVICE_UNAVAILABLE, "No tree selected");

        int sbhDp = 0, nbhDp = 0;
        boolean blur = true;
        try {
            Map<String, String> parms = session.getParms();
            String sbh = parms.get("sbh");  if (sbh != null) sbhDp = Integer.parseInt(sbh);
            String nbh = parms.get("nbh");  if (nbh != null) nbhDp = Integer.parseInt(nbh);
            String b   = parms.get("blur"); if (b   != null) blur  = "1".equals(b);
        } catch (Exception ignored) {}

        try {
            if (uri.isEmpty())
                return serveDirOrIndex(rootDocId, "/", sbhDp, nbhDp, blur, session);

            String cur = rootDocId;
            for (String part : uri.split("/")) {
                if (part.isEmpty()) continue;
                String next = findChild(cur, part);
                if (next == null)
                    return plain(Response.Status.NOT_FOUND, "Not found: " + uri);
                cur = next;
            }
            return serveDoc(cur, uri, sbhDp, nbhDp, blur, session);
        } catch (Exception e) {
            Log.w(TAG, "serve failed for " + uri, e);
            return plain(Response.Status.INTERNAL_ERROR,
                    e.getMessage() == null ? "error" : e.getMessage());
        }
    }

    // ==================================================================
    // MUSIC ROUTES
    // ==================================================================

    private Response serveMusic(String uri, IHTTPSession session) {
        if (resolver == null || appContext == null)
            return plain(Response.Status.SERVICE_UNAVAILABLE, "Music not ready");

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

        return plain(Response.Status.NOT_FOUND, "Music route not found");
    }

    private Response serveAsset(String assetPath, String mime) {
        String content = assetCache.get(assetPath);
        if (content == null) {
            try {
                InputStream in = appContext.getAssets().open(assetPath);
                content = readAll(in);
                assetCache.put(assetPath, content);
            } catch (IOException e) {
                return plain(Response.Status.NOT_FOUND, "asset missing: " + assetPath);
            }
        }
        Response r = newFixedLengthResponse(
                Response.Status.OK, mime + "; charset=utf-8", content);
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

    private Response serveMusicLib() {
        StringBuilder sb = new StringBuilder(4096);
        boolean hasPerm = hasAudioPermission();
        sb.append("{\"hasPermission\":").append(hasPerm).append(",\"songs\":[");

        if (hasPerm) {
            SharedPreferences prefs = appContext.getSharedPreferences(MUSIC_PREFS, Context.MODE_PRIVATE);
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

                        // Overrides take precedence
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
                          .append(",\"title\":").append(jsonStr(title))
                          .append(",\"artist\":").append(jsonStr(artist))
                          .append(",\"album\":").append(jsonStr(album))
                          .append(",\"folder\":").append(jsonStr(folder))
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
        Response r = newFixedLengthResponse(
                Response.Status.OK, "application/json; charset=utf-8", sb.toString());
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    private Response serveMusicStream(String idStr, IHTTPSession session) {
        long id;
        try { id = Long.parseLong(idStr); }
        catch (Exception e) { return plain(Response.Status.BAD_REQUEST, "bad id"); }

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
        catch (Exception e) { return plain(Response.Status.NOT_FOUND, "cannot open"); }
        if (pfd == null) return plain(Response.Status.NOT_FOUND, "cannot open");

        long fileLength = pfd.getStatSize();
        if (fileLength <= 0) {
            try { pfd.close(); } catch (Exception ignored) {}
            return plain(Response.Status.INTERNAL_ERROR, "unknown length");
        }

        long start = 0, end = fileLength - 1;
        boolean partial = false;

        String range = headerIgnoreCase(session, "range");
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
                        Response r = plain(Response.Status.RANGE_NOT_SATISFIABLE, "");
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
            InputStream bounded = new BoundedInputStream(fis, contentLength);
            InputStream auto = new AutoCloseInputStream(bounded, pfd);

            Response.Status status = partial
                    ? Response.Status.PARTIAL_CONTENT : Response.Status.OK;
            Response r = newFixedLengthResponse(status, mime, auto, contentLength);
            r.addHeader("Accept-Ranges", "bytes");
            if (partial)
                r.addHeader("Content-Range",
                        "bytes " + start + "-" + end + "/" + fileLength);
            r.addHeader("Cache-Control", "no-cache");
            return r;
        } catch (Exception e) {
            try { pfd.close(); } catch (Exception ignored) {}
            return plain(Response.Status.INTERNAL_ERROR, "stream failed");
        }
    }

    private Response serveMusicCover(String idStr) {
        long id;
        try { id = Long.parseLong(idStr); }
        catch (Exception e) { return serveSvg(); }

        // 1. User-picked cover (filesDir/covers/<id>.*)
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
                Response r = newFixedLengthResponse(Response.Status.OK, mime, in, f.length());
                r.addHeader("Cache-Control", "no-cache");
                return r;
            } catch (IOException e) { /* fall through */ }
        }

        // 2. Memory cache
        byte[] cached = coverCache.get(id);
        if (cached != null) {
            Response r = newFixedLengthResponse(Response.Status.OK,
                    "image/jpeg", new ByteArrayInputStream(cached), cached.length);
            r.addHeader("Cache-Control", "max-age=86400");
            return r;
        }

        // 3. Embedded picture
        Uri audioUri = ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(appContext, audioUri);
            byte[] pic = mmr.getEmbeddedPicture();
            if (pic != null && pic.length > 0) {
                coverCache.put(id, pic);
                Response r = newFixedLengthResponse(Response.Status.OK,
                        "image/jpeg", new ByteArrayInputStream(pic), pic.length);
                r.addHeader("Cache-Control", "max-age=86400");
                return r;
            }
        } catch (Exception ignored) {
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
        return serveSvg();
    }

    private Response serveSvg() {
        byte[] bytes = PLACEHOLDER_SVG.getBytes(StandardCharsets.UTF_8);
        Response r = newFixedLengthResponse(Response.Status.OK,
                "image/svg+xml", new ByteArrayInputStream(bytes), bytes.length);
        r.addHeader("Cache-Control", "max-age=86400");
        return r;
    }

    private Response serveMusicFav(IHTTPSession session) {
        Map<String, String> parms = session.getParms();
        String idStr = parms.get("id");
        String onStr = parms.get("on");
        if (idStr == null) return plain(Response.Status.BAD_REQUEST, "missing id");
        long id;
        try { id = Long.parseLong(idStr); }
        catch (Exception e) { return plain(Response.Status.BAD_REQUEST, "bad id"); }
        boolean on = "1".equals(onStr) || "true".equalsIgnoreCase(onStr);

        SharedPreferences prefs = appContext.getSharedPreferences(MUSIC_PREFS, Context.MODE_PRIVATE);
        prefs.edit().putBoolean("fav_" + id, on).apply();

        String body = "{\"ok\":true,\"id\":" + id + ",\"isFav\":" + on + "}";
        return newFixedLengthResponse(Response.Status.OK, "application/json", body);
    }

    private Response serveMusicMetadata(IHTTPSession session) {
        Map<String, String> parms = session.getParms();
        String idStr = parms.get("id");
        if (idStr == null) return plain(Response.Status.BAD_REQUEST, "missing id");
        long id;
        try { id = Long.parseLong(idStr); }
        catch (Exception e) { return plain(Response.Status.BAD_REQUEST, "bad id"); }

        boolean isUpdate = parms.containsKey("title")
                        || parms.containsKey("artist")
                        || parms.containsKey("album")
                        || parms.containsKey("track");

        if (isUpdate) {
            boolean ok = saveMetadata(id,
                    parms.get("title"), parms.get("artist"),
                    parms.get("album"), parms.get("track"));
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                    "{\"ok\":" + ok + ",\"id\":" + id + "}");
        }

        SharedPreferences prefs = appContext.getSharedPreferences(MUSIC_PREFS, MODE_PRIVATE);
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
        sb.append("\"title\":").append(jsonStr(title)).append(',');
        sb.append("\"artist\":").append(jsonStr(artist)).append(',');
        sb.append("\"album\":").append(jsonStr(album)).append(',');
        sb.append("\"track\":").append(jsonStr(track));
        sb.append("}");
        return newFixedLengthResponse(Response.Status.OK, "application/json", sb.toString());
    }

    private boolean saveMetadata(long id, String title, String artist, String album, String track) {
        SharedPreferences prefs = appContext.getSharedPreferences(MUSIC_PREFS, MODE_PRIVATE);
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
            try { writeId3Tags(audioUri, title, artist, album, track); }
            catch (Exception ex) { Log.w(TAG, "ID3 write failed", ex); }
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

    // ==================================================================
    // ID3v2.4 writing
    // ==================================================================

    private void writeId3Tags(Uri audioUri, String title, String artist,
                              String album, String track) throws IOException {
        byte[] fileBytes = readBytes(resolver.openInputStream(audioUri));
        if (fileBytes == null) throw new IOException("read failed");

        int audioStart = findId3TagEnd(fileBytes);
        byte[] existingCover = findApicData(fileBytes, 10, audioStart);
        byte[] newTag = buildId3Tag(title, artist, album, track, existingCover, "image/jpeg");

        try (java.io.OutputStream out = resolver.openOutputStream(audioUri, "rwt")) {
            if (out == null) throw new IOException("write failed");
            out.write(newTag);
            out.write(fileBytes, audioStart, fileBytes.length - audioStart);
        }
    }

    public static void embedCoverInMp3(Context ctx, Uri audioUri,
                                       byte[] coverData, String coverMime) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            byte[] fileBytes = readBytes(cr.openInputStream(audioUri));
            if (fileBytes == null) return;

            int audioStart = findId3TagEnd(fileBytes);
            byte[] existingCover = findApicData(fileBytes, 10, audioStart);
            byte[] finalCover = coverData != null ? coverData : existingCover;

            String existingTitle  = findTextFrame(fileBytes, 10, audioStart, "TIT2");
            String existingArtist = findTextFrame(fileBytes, 10, audioStart, "TPE1");
            String existingAlbum  = findTextFrame(fileBytes, 10, audioStart, "TALB");
            String existingTrack  = findTextFrame(fileBytes, 10, audioStart, "TRCK");

            String mime = coverMime != null ? coverMime : "image/jpeg";
            byte[] newTag = buildId3Tag(existingTitle, existingArtist, existingAlbum,
                    existingTrack, finalCover, mime);

            try (java.io.OutputStream out = cr.openOutputStream(audioUri, "rwt")) {
                if (out == null) return;
                out.write(newTag);
                out.write(fileBytes, audioStart, fileBytes.length - audioStart);
            }
        } catch (Exception e) {
            Log.w(TAG, "embedCoverInMp3 failed", e);
        }
    }

    private static byte[] readBytes(InputStream in) throws IOException {
        if (in == null) return null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally { try { in.close(); } catch (Exception ignored) {} }
    }

    private static int findId3TagEnd(byte[] data) {
        if (data.length < 10) return 0;
        if (data[0] != 'I' || data[1] != 'D' || data[2] != '3') return 0;
        int size = ((data[6] & 0x7f) << 21) | ((data[7] & 0x7f) << 14)
                 | ((data[8] & 0x7f) << 7)  | (data[9] & 0x7f);
        int end = 10 + size;
        if ((data[5] & 0x10) != 0) end += 10;
        return Math.min(end, data.length);
    }

    private static String findTextFrame(byte[] data, int start, int end, String frameId) {
        int pos = start;
        while (pos + 10 <= end) {
            String id = new String(data, pos, 4, StandardCharsets.ISO_8859_1);
            if (id.equals("\0\0\0\0")) break;
            int size = ((data[pos+4] & 0x7f) << 21) | ((data[pos+5] & 0x7f) << 14)
                     | ((data[pos+6] & 0x7f) << 7)  | (data[pos+7] & 0x7f);
            if (id.equals(frameId) && size > 1) {
                int enc = data[pos + 10];
                if (enc == 3) return new String(data, pos + 11, size - 1, StandardCharsets.UTF_8);
                return new String(data, pos + 11, size - 1, StandardCharsets.ISO_8859_1);
            }
            pos += 10 + size;
        }
        return null;
    }

    private static byte[] findApicData(byte[] data, int start, int end) {
        int pos = start;
        while (pos + 10 <= end) {
            String id = new String(data, pos, 4, StandardCharsets.ISO_8859_1);
            if (id.equals("\0\0\0\0")) break;
            int size = ((data[pos+4] & 0x7f) << 21) | ((data[pos+5] & 0x7f) << 14)
                     | ((data[pos+6] & 0x7f) << 7)  | (data[pos+7] & 0x7f);
            if (id.equals("APIC")) {
                int fpos = pos + 10;
                int fEnd = fpos + size;
                if (fEnd > data.length) return null;
                int enc = data[fpos];
                int p = fpos + 1;
                while (p < fEnd && data[p] != 0) p++;
                p++;
                p++;
                if (enc == 1 || enc == 2) {
                    if (p + 1 < fEnd) p += 2;
                    while (p + 1 < fEnd && !(data[p] == 0 && data[p+1] == 0)) p += 2;
                    p += 2;
                } else {
                    while (p < fEnd && data[p] != 0) p++;
                    p++;
                }
                int len = fEnd - p;
                if (len > 0) {
                    byte[] img = new byte[len];
                    System.arraycopy(data, p, img, 0, len);
                    return img;
                }
                return null;
            }
            pos += 10 + size;
        }
        return null;
    }

    private static byte[] buildId3Tag(String title, String artist, String album,
                                       String track, byte[] cover, String coverMime)
            throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(new byte[]{'I','D','3', 4, 0, 0, 0,0,0,0});

        if (title != null && !title.isEmpty())   writeTextFrame(buf, "TIT2", title);
        if (artist != null && !artist.isEmpty()) writeTextFrame(buf, "TPE1", artist);
        if (album != null && !album.isEmpty())   writeTextFrame(buf, "TALB", album);
        if (track != null && !track.isEmpty())   writeTextFrame(buf, "TRCK", track);
        if (cover != null && cover.length > 0)
            writeApicFrame(buf, cover, coverMime != null ? coverMime : "image/jpeg");

        byte[] tag = buf.toByteArray();
        int size = tag.length - 10;
        tag[6] = (byte)((size >> 21) & 0x7f);
        tag[7] = (byte)((size >> 14) & 0x7f);
        tag[8] = (byte)((size >> 7)  & 0x7f);
        tag[9] = (byte)( size        & 0x7f);
        return tag;
    }

    private static void writeTextFrame(ByteArrayOutputStream buf, String id, String text)
            throws IOException {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        int size = 1 + textBytes.length;
        buf.write(id.getBytes(StandardCharsets.ISO_8859_1));
        buf.write((size >> 21) & 0x7f);
        buf.write((size >> 14) & 0x7f);
        buf.write((size >> 7)  & 0x7f);
        buf.write( size        & 0x7f);
        buf.write(0); buf.write(0);
        buf.write(3);
        buf.write(textBytes);
    }

    private static void writeApicFrame(ByteArrayOutputStream buf, byte[] image, String mime)
            throws IOException {
        byte[] mimeBytes = mime.getBytes(StandardCharsets.ISO_8859_1);
        int size = 1 + mimeBytes.length + 1 + 1 + 1 + image.length;
        buf.write("APIC".getBytes(StandardCharsets.ISO_8859_1));
        buf.write((size >> 21) & 0x7f);
        buf.write((size >> 14) & 0x7f);
        buf.write((size >> 7)  & 0x7f);
        buf.write( size        & 0x7f);
        buf.write(0); buf.write(0);
        buf.write(0);
        buf.write(mimeBytes);
        buf.write(0);
        buf.write(3);
        buf.write(0);
        buf.write(image);
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private static String jsonStr(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String headerIgnoreCase(IHTTPSession session, String name) {
        if (session == null) return null;
        Map<String, String> headers = session.getHeaders();
        if (headers == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet())
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        return null;
    }

    private static final class AutoCloseInputStream extends FilterInputStream {
        private final java.io.Closeable resource;
        AutoCloseInputStream(InputStream in, java.io.Closeable resource) {
            super(in); this.resource = resource;
        }
        @Override public void close() throws IOException {
            try { super.close(); }
            finally {
                if (resource != null) {
                    try { resource.close(); } catch (IOException ignored) {}
                }
            }
        }
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;
        BoundedInputStream(InputStream in, long limit) { super(in); this.remaining = limit; }
        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = super.read();
            if (b >= 0) remaining--;
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int toRead = (int) Math.min(len, remaining);
            int n = super.read(b, off, toRead);
            if (n > 0) remaining -= n;
            return n;
        }
    }

    // ---- reader helpers (unchanged) ----

    private Response serveDirOrIndex(String dirDocId, String pathForError,
                                     int sbhDp, int nbhDp, boolean blur,
                                     IHTTPSession session) {
        String id = findChild(dirDocId, "index.html");
        if (id != null) return serveDoc(id, pathForError, sbhDp, nbhDp, blur, session);
        return plain(Response.Status.NOT_FOUND, "No index.html at " + pathForError);
    }

    private String findChild(String parentDocId, String name) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        String[] proj = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        Cursor c = resolver.query(children, proj, null, null, null);
        if (c == null) return null;
        try {
            while (c.moveToNext())
                if (name.equals(c.getString(1))) return c.getString(0);
        } finally { c.close(); }
        return null;
    }

    private Response serveDoc(String docId, String pathForError,
                              int sbhDp, int nbhDp, boolean blur, IHTTPSession session) {
        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);

        String mime = null, name = null;
        long lastModified = 0;
        String[] proj = {
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        Cursor c = resolver.query(docUri, proj, null, null, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    mime = c.getString(0);
                    name = c.getString(1);
                    try { lastModified = c.getLong(2); } catch (Exception ignored) {}
                }
            } finally { c.close(); }
        }

        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime))
            return serveDirOrIndex(docId, pathForError, sbhDp, nbhDp, blur, session);

        String ext = extensionOf(name);

        if (READER_EXTS.contains(ext)) {
            try {
                InputStream in = resolver.openInputStream(docUri);
                if (in == null) return plain(Response.Status.NOT_FOUND, "Cannot open");
                String raw = readAll(in);
                ReaderSettings settings = appContext != null
                        ? ReaderSettings.load(appContext) : new ReaderSettings();
                String html = ReaderRenderer.render(ext, raw, settings, sbhDp, nbhDp, blur);
                Response r = newFixedLengthResponse(
                        Response.Status.OK, "text/html; charset=utf-8", html);
                r.addHeader("Cache-Control", "no-store");
                return r;
            } catch (IOException e) {
                return plain(Response.Status.INTERNAL_ERROR, e.getMessage());
            }
        }

        String etag = "\"" + docId + "-" + lastModified + "\"";
        boolean hasValidator = lastModified > 0;

        if (hasValidator) {
            String inm = headerIgnoreCase(session, "if-none-match");
            if (inm != null && (inm.equals(etag) || inm.equals("*"))) {
                Response r = newFixedLengthResponse(Response.Status.NOT_MODIFIED, mime, null);
                r.addHeader("ETag", etag);
                r.addHeader("Cache-Control", "no-cache");
                return r;
            }
        }

        if (ext.equals("html") || ext.equals("htm"))
            mime = "text/html; charset=utf-8";
        else if (mime == null || "application/octet-stream".equals(mime))
            mime = guessMime(name);

        try {
            InputStream in = resolver.openInputStream(docUri);
            if (in == null) return plain(Response.Status.NOT_FOUND, "Cannot open");
            Response r = newChunkedResponse(Response.Status.OK, mime, in);
            if (hasValidator) {
                r.addHeader("ETag", etag);
                r.addHeader("Cache-Control", "no-cache");
            } else {
                r.addHeader("Cache-Control", "no-store");
            }
            return r;
        } catch (IOException e) {
            return plain(Response.Status.INTERNAL_ERROR, e.getMessage());
        }
    }

    private static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private Response plain(Response.Status s, String body) {
        return newFixedLengthResponse(s, "text/plain; charset=utf-8", body == null ? "" : body);
    }

    private String guessMime(String name) {
        if (name == null) return "application/octet-stream";
        String n = name.toLowerCase();
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html; charset=utf-8";
        if (n.endsWith(".js") || n.endsWith(".mjs")) return "application/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".ico")) return "image/x-icon";
        if (n.endsWith(".woff")) return "font/woff";
        if (n.endsWith(".woff2")) return "font/woff2";
        if (n.endsWith(".ttf")) return "font/ttf";
        if (n.endsWith(".wasm")) return "application/wasm";
        if (n.endsWith(".xml")) return "application/xml";
        if (n.endsWith(".txt")) return "text/plain; charset=utf-8";
        if (n.endsWith(".csv")) return "text/csv; charset=utf-8";
        return "application/octet-stream";
    }
}