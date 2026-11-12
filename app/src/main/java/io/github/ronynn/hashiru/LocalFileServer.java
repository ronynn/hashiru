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
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

    private String musicHtmlCache;
    private final Map<Long, byte[]> coverCache = new ConcurrentHashMap<>();
    private final Map<Long, String> mimeCache = new ConcurrentHashMap<>();

    private static final Set<String> READER_EXTS = new HashSet<>(
            Arrays.asList("md", "markdown", "txt", "org", "twee", "csv"));

    private static final String PLACEHOLDER_SVG =
            "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100' width='100' height='100'>"
          + "<rect width='100' height='100' fill='#2a2a2a'/>"
          + "<text x='50' y='68' font-size='52' text-anchor='middle' fill='#666' "
          + "font-family='sans-serif'>\u266A</text></svg>";

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
            return serveMusicIndex();
        if (uri.equals("_music/lib"))
            return serveMusicLib();
        if (uri.startsWith("_music/stream/"))
            return serveMusicStream(uri.substring("_music/stream/".length()), session);
        if (uri.startsWith("_music/cover/"))
            return serveMusicCover(uri.substring("_music/cover/".length()));
        if (uri.startsWith("_music/fav"))
            return serveMusicFav(session);

        return plain(Response.Status.NOT_FOUND, "Music route not found");
    }

    private Response serveMusicIndex() {
        if (musicHtmlCache == null) {
            try {
                InputStream in = appContext.getAssets().open("music.html");
                musicHtmlCache = readAll(in);
            } catch (IOException e) {
                return plain(Response.Status.INTERNAL_ERROR, "music.html missing in assets");
            }
        }
        Response r = newFixedLengthResponse(
                Response.Status.OK, "text/html; charset=utf-8", musicHtmlCache);
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
                    MediaStore.Audio.Media.DATA
            };
            String sel = MediaStore.Audio.Media.IS_MUSIC + " != 0";
            String sort = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC";

            Cursor c = null;
            try {
                c = resolver.query(uri, proj, sel, null, sort);
                if (c != null) {
                    int idxId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                    int idxTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                    int idxArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                    int idxAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                    int idxDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                    int idxData = c.getColumnIndex(MediaStore.Audio.Media.DATA);
                    int idxBucket = c.getColumnIndex("bucket_display_name");

                    boolean first = true;
                    while (c.moveToNext()) {
                        long id = c.getLong(idxId);
                        String title = c.getString(idxTitle);
                        String artist = c.getString(idxArtist);
                        String album = c.getString(idxAlbum);
                        long duration = c.getLong(idxDur);

                        String folder = null;
                        if (idxBucket >= 0 && !c.isNull(idxBucket)) {
                            folder = c.getString(idxBucket);
                        }
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
            } finally {
                if (c != null) c.close();
            }
            mimeCache.put(id, mime);
        }

        ParcelFileDescriptor pfd = null;
        try {
            pfd = resolver.openFileDescriptor(audioUri, "r");
        } catch (Exception e) {
            return plain(Response.Status.NOT_FOUND, "cannot open");
        }
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

        byte[] cached = coverCache.get(id);
        if (cached != null) {
            Response r = newFixedLengthResponse(Response.Status.OK,
                    "image/jpeg", new ByteArrayInputStream(cached), cached.length);
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
        return newFixedLengthResponse(
                Response.Status.OK, "application/json", body);
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

    /** Wraps a stream and closes an associated resource (like a PFD) on close. */
    private static final class AutoCloseInputStream extends FilterInputStream {
        private final java.io.Closeable resource;
        AutoCloseInputStream(InputStream in, java.io.Closeable resource) {
            super(in);
            this.resource = resource;
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

    /** Limits reads to a fixed byte count. */
    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;
        BoundedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }
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

    // ---- existing reader helpers (unchanged) ----

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
        } finally {
            c.close();
        }
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
            } finally {
                c.close();
            }
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
                String html = ReaderRenderer.render(
                        ext, raw, settings, sbhDp, nbhDp, blur);
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