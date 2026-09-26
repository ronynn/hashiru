package io.github.ronynn.hashiru;

import android.content.ContentResolver;
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
import java.util.Set;

import fi.iki.elonen.NanoHTTPD;

public class LocalFileServer extends NanoHTTPD {

    private static final String TAG = "LocalFileServer";

    // FIX: fixed port → the WebView origin (http://127.0.0.1:PORT) never changes,
    // so localStorage / IndexedDB / cookies survive across launches and rotations.
    private static final int PORT = 17463;

    private static LocalFileServer instance;

    private Uri treeUri;
    private ContentResolver resolver;
    private String rootDocId;

    private static final Set<String> READER_EXTS = new HashSet<>(
            Arrays.asList("md", "markdown", "txt", "org", "twee"));

    private LocalFileServer() {
        super("127.0.0.1", PORT);
    }

    /**
     * FIX: shared instance. First caller starts it; subsequent callers just
     * retarget the tree. Do NOT stop() this from activities — it must outlive them.
     */
    public static synchronized LocalFileServer acquire(Uri treeUri, ContentResolver cr)
            throws IOException {
        if (instance == null) {
            instance = new LocalFileServer();
            instance.start(SOCKET_READ_TIMEOUT, false);
        }
        instance.treeUri = treeUri;
        instance.resolver = cr;
        instance.rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
        return instance;
    }

    public String origin() {
        return "http://127.0.0.1:" + PORT;
    }

    @Override
    public Response serve(IHTTPSession session) {
        if (treeUri == null || resolver == null) {
            return plain(Response.Status.SERVICE_UNAVAILABLE, "No tree selected");
        }

        // NanoHTTPD already percent-decodes session.getUri(); do NOT decode again.
        String uri = session.getUri();
        if (uri.startsWith("/")) uri = uri.substring(1);

        // FIX: check traversal per-segment (allows names like "notes..txt").
        for (String seg : uri.split("/")) {
            if ("..".equals(seg)) return plain(Response.Status.FORBIDDEN, "Forbidden");
        }

        try {
            if (uri.isEmpty()) {
                return serveDirOrIndex(rootDocId, "/");
            }
            String cur = rootDocId;
            for (String part : uri.split("/")) {
                if (part.isEmpty()) continue;
                String next = findChild(cur, part);
                if (next == null) return plain(Response.Status.NOT_FOUND, "Not found: " + uri);
                cur = next;
            }
            return serveDoc(cur, uri);
        } catch (Exception e) {
            Log.w(TAG, "serve failed for " + uri, e);
            return plain(Response.Status.INTERNAL_ERROR,
                    e.getMessage() == null ? "error" : e.getMessage());
        }
    }

    // FIX: serve index.html when a directory (root or subfolder) is requested.
    private Response serveDirOrIndex(String dirDocId, String pathForError) {
        String id = findChild(dirDocId, "index.html");
        if (id != null) return serveDoc(id, pathForError);
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
            while (c.moveToNext()) {
                if (name.equals(c.getString(1))) return c.getString(0);
            }
        } finally {
            c.close();
        }
        return null;
    }

    private Response serveDoc(String docId, String pathForError) {
        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
        String mime = null;
        String name = null;
        String[] proj = {
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        Cursor c = resolver.query(docUri, proj, null, null, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    mime = c.getString(0);
                    name = c.getString(1);
                }
            } finally {
                c.close();
            }
        }

        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
            return serveDirOrIndex(docId, pathForError);
        }

        String ext = extensionOf(name);

        // Reader rendering path (markdown / plain text)
        if (READER_EXTS.contains(ext)) {
            try {
                InputStream in = resolver.openInputStream(docUri);
                if (in == null) return plain(Response.Status.NOT_FOUND, "Cannot open");
                String raw = readAll(in);
                String html = ReaderRenderer.render(ext, raw);
                Response r = newFixedLengthResponse(
                        Response.Status.OK, "text/html; charset=utf-8", html);
                r.addHeader("Cache-Control", "no-store");
                return r;
            } catch (IOException e) {
                return plain(Response.Status.INTERNAL_ERROR, e.getMessage());
            }
        }

        // FIX: force text/html for .html/.htm even if SAF misreports them.
        if (ext.equals("html") || ext.equals("htm")) {
            mime = "text/html; charset=utf-8";
        } else if (mime == null || "application/octet-stream".equals(mime)) {
            mime = guessMime(name);
        }

        try {
            InputStream in = resolver.openInputStream(docUri);
            if (in == null) return plain(Response.Status.NOT_FOUND, "Cannot open");
            Response r = newChunkedResponse(Response.Status.OK, mime, in);
            r.addHeader("Cache-Control", "no-store");
            return r;
        } catch (IOException e) {
            return plain(Response.Status.INTERNAL_ERROR, e.getMessage());
        }
    }

    private static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "";
        return name.substring(dot + 1).toLowerCase();
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
        return "application/octet-stream";
    }
}