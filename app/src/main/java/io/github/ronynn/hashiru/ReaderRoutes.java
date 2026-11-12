package io.github.ronynn.hashiru;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import fi.iki.elonen.NanoHTTPD;

/** Reader-side routes: SAF tree, markdown/html/csv rendered on-the-fly, asset passthrough. */
final class ReaderRoutes {

    private static final String TAG = "ReaderRoutes";

    private static final Set<String> READER_EXTS = new HashSet<>(
            Arrays.asList("md", "markdown", "txt", "org", "twee", "csv"));

    private final Uri treeUri;
    private final String rootDocId;
    private final ContentResolver resolver;
    private final Context appContext;

    ReaderRoutes(Uri treeUri, ContentResolver cr, Context ctx) {
        this.treeUri = treeUri;
        this.rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
        this.resolver = cr;
        this.appContext = ctx;
    }

    NanoHTTPD.Response serve(String uri, NanoHTTPD.IHTTPSession session) {
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
                    return HttpUtils.plain(NanoHTTPD.Response.Status.NOT_FOUND,
                            "Not found: " + uri);
                cur = next;
            }
            return serveDoc(cur, uri, sbhDp, nbhDp, blur, session);
        } catch (Exception e) {
            Log.w(TAG, "serve failed for " + uri, e);
            return HttpUtils.plain(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    e.getMessage() == null ? "error" : e.getMessage());
        }
    }

    private NanoHTTPD.Response serveDirOrIndex(String dirDocId, String pathForError,
                                               int sbhDp, int nbhDp, boolean blur,
                                               NanoHTTPD.IHTTPSession session) {
        String id = findChild(dirDocId, "index.html");
        if (id != null) return serveDoc(id, pathForError, sbhDp, nbhDp, blur, session);
        return HttpUtils.plain(NanoHTTPD.Response.Status.NOT_FOUND,
                "No index.html at " + pathForError);
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

    private NanoHTTPD.Response serveDoc(String docId, String pathForError,
                                        int sbhDp, int nbhDp, boolean blur,
                                        NanoHTTPD.IHTTPSession session) {
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

        // Reader path — rendered HTML on the fly
        if (READER_EXTS.contains(ext)) {
            try {
                InputStream in = resolver.openInputStream(docUri);
                if (in == null)
                    return HttpUtils.plain(NanoHTTPD.Response.Status.NOT_FOUND, "Cannot open");
                String raw = HttpUtils.readAll(in);
                ReaderSettings settings = appContext != null
                        ? ReaderSettings.load(appContext) : new ReaderSettings();
                String html = ReaderRenderer.render(ext, raw, settings, sbhDp, nbhDp, blur);
                NanoHTTPD.Response r = NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.OK, "text/html; charset=utf-8", html);
                r.addHeader("Cache-Control", "no-store");
                return r;
            } catch (IOException e) {
                return HttpUtils.plain(NanoHTTPD.Response.Status.INTERNAL_ERROR, e.getMessage());
            }
        }

        // Non-reader path — ETag-based revalidating cache
        String etag = "\"" + docId + "-" + lastModified + "\"";
        boolean hasValidator = lastModified > 0;

        if (hasValidator) {
            String inm = HttpUtils.headerIgnoreCase(session, "if-none-match");
            if (inm != null && (inm.equals(etag) || inm.equals("*"))) {
                NanoHTTPD.Response r = NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.NOT_MODIFIED, mime, null);
                r.addHeader("ETag", etag);
                r.addHeader("Cache-Control", "no-cache");
                return r;
            }
        }

        if (ext.equals("html") || ext.equals("htm"))
            mime = "text/html; charset=utf-8";
        else if (mime == null || "application/octet-stream".equals(mime))
            mime = HttpUtils.guessMime(name);

        try {
            InputStream in = resolver.openInputStream(docUri);
            if (in == null)
                return HttpUtils.plain(NanoHTTPD.Response.Status.NOT_FOUND, "Cannot open");
            NanoHTTPD.Response r = NanoHTTPD.newChunkedResponse(
                    NanoHTTPD.Response.Status.OK, mime, in);
            if (hasValidator) {
                r.addHeader("ETag", etag);
                r.addHeader("Cache-Control", "no-cache");
            } else {
                r.addHeader("Cache-Control", "no-store");
            }
            return r;
        } catch (IOException e) {
            return HttpUtils.plain(NanoHTTPD.Response.Status.INTERNAL_ERROR, e.getMessage());
        }
    }

    private static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }
}