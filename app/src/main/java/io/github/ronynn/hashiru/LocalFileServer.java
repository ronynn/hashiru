package io.github.ronynn.hashiru;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import fi.iki.elonen.NanoHTTPD;

public class LocalFileServer extends NanoHTTPD {

    private static final String TAG = "LocalFileServer";
    private static final int PORT = 17463;

    private static LocalFileServer instance;

    private Uri treeUri;
    private ContentResolver resolver;
    private Context appContext;
    private String rootDocId;

    // csv added: server renders it through ReaderRenderer → text/html → WebView
    // never sees text/csv or octet-stream, so setDownloadListener won't fire.
    private static final Set<String> READER_EXTS = new HashSet<>(
            Arrays.asList("md", "markdown", "txt", "org", "twee", "csv"));

    private LocalFileServer() { super("127.0.0.1", PORT); }

    public static synchronized LocalFileServer acquire(Uri treeUri, ContentResolver cr, Context ctx)
            throws IOException {
        if (instance == null) {
            instance = new LocalFileServer();
            instance.start(SOCKET_READ_TIMEOUT, false);
        }
        instance.treeUri = treeUri;
        instance.resolver = cr;
        instance.appContext = ctx.getApplicationContext();
        instance.rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
        return instance;
    }

    public String origin() { return "http://127.0.0.1:" + PORT; }

    @Override
    public Response serve(IHTTPSession session) {
        if (treeUri == null || resolver == null)
            return plain(Response.Status.SERVICE_UNAVAILABLE, "No tree selected");

        String uri = session.getUri();
        if (uri.startsWith("/")) uri = uri.substring(1);

        for (String seg : uri.split("/"))
            if ("..".equals(seg)) return plain(Response.Status.FORBIDDEN, "Forbidden");

        int sbhDp = 0;
        boolean blur = true;
        try {
            Map<String, String> parms = session.getParms();
            String sbh = parms.get("sbh");
            if (sbh != null) sbhDp = Integer.parseInt(sbh);
            String b = parms.get("blur");
            if (b != null) blur = "1".equals(b);
        } catch (Exception ignored) {}

        try {
            if (uri.isEmpty())
                return serveDirOrIndex(rootDocId, "/", sbhDp, blur, session);

            String cur = rootDocId;
            for (String part : uri.split("/")) {
                if (part.isEmpty()) continue;
                String next = findChild(cur, part);
                if (next == null)
                    return plain(Response.Status.NOT_FOUND, "Not found: " + uri);
                cur = next;
            }
            return serveDoc(cur, uri, sbhDp, blur, session);
        } catch (Exception e) {
            Log.w(TAG, "serve failed for " + uri, e);
            return plain(Response.Status.INTERNAL_ERROR,
                    e.getMessage() == null ? "error" : e.getMessage());
        }
    }

    private Response serveDirOrIndex(String dirDocId, String pathForError,
                                     int sbhDp, boolean blur, IHTTPSession session) {
        String id = findChild(dirDocId, "index.html");
        if (id != null) return serveDoc(id, pathForError, sbhDp, blur, session);
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
                              int sbhDp, boolean blur, IHTTPSession session) {
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
            return serveDirOrIndex(docId, pathForError, sbhDp, blur, session);

        String ext = extensionOf(name);

        // ---- Reader path: rendered HTML on the fly, always no-store ----
        if (READER_EXTS.contains(ext)) {
            try {
                InputStream in = resolver.openInputStream(docUri);
                if (in == null) return plain(Response.Status.NOT_FOUND, "Cannot open");
                String raw = readAll(in);
                ReaderSettings settings = appContext != null
                        ? ReaderSettings.load(appContext) : new ReaderSettings();
                String html = ReaderRenderer.render(ext, raw, settings, sbhDp, blur);
                Response r = newFixedLengthResponse(
                        Response.Status.OK, "text/html; charset=utf-8", html);
                r.addHeader("Cache-Control", "no-store");
                return r;
            } catch (IOException e) {
                return plain(Response.Status.INTERNAL_ERROR, e.getMessage());
            }
        }

        // ---- Non-reader path: ETag-based revalidating cache ----
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

    private static String headerIgnoreCase(IHTTPSession session, String name) {
        if (session == null) return null;
        Map<String, String> headers = session.getHeaders();
        if (headers == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet())
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        return null;
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