package io.github.ronynn.hashiru;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;

import fi.iki.elonen.NanoHTTPD;

public class LocalFileServer extends NanoHTTPD {

    private final Uri treeUri;
    private final ContentResolver resolver;
    private final String rootDocId;

    public LocalFileServer(Uri treeUri, ContentResolver resolver) {
        super("127.0.0.1", 0); // ephemeral port
        this.treeUri = treeUri;
        this.resolver = resolver;
        this.rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if (uri.startsWith("/")) uri = uri.substring(1);

        // Decode percent-encoding (%20 etc.)
        try {
            uri = URLDecoder.decode(uri, "UTF-8");
        } catch (Exception ignored) {}

        if (uri.contains("..")) return plain(Response.Status.FORBIDDEN, "Forbidden");

        // Root: serve index.html if present
        if (uri.isEmpty()) {
            String id = findChild(rootDocId, "index.html");
            if (id != null) return serveDoc(id);
            return newFixedLengthResponse(Response.Status.OK, "text/html",
                    "<html><body><h3>No index.html in root</h3></body></html>");
        }

        String cur = rootDocId;
        for (String part : uri.split("/")) {
            if (part.isEmpty()) continue;
            String next = findChild(cur, part);
            if (next == null) return plain(Response.Status.NOT_FOUND, "Not found: " + uri);
            cur = next;
        }
        return serveDoc(cur);
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

    private Response serveDoc(String docId) {
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

        if (mime == null || DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
            return plain(Response.Status.NOT_FOUND, "Not a file");
        }
        if ("application/octet-stream".equals(mime)) {
            mime = guessMime(name);
        }

        try {
            InputStream in = resolver.openInputStream(docUri);
            if (in == null) return plain(Response.Status.NOT_FOUND, "Cannot open");
            Response r = newChunkedResponse(Response.Status.OK, mime, in);
            r.addHeader("Cache-Control", "no-store");
            r.addHeader("Access-Control-Allow-Origin", "*");
            return r;
        } catch (IOException e) {
            return plain(Response.Status.INTERNAL_ERROR, e.getMessage());
        }
    }

    private Response plain(Response.Status s, String body) {
        return newFixedLengthResponse(s, "text/plain", body);
    }

    private String guessMime(String name) {
        if (name == null) return "application/octet-stream";
        String n = name.toLowerCase();
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
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
        if (n.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }
}