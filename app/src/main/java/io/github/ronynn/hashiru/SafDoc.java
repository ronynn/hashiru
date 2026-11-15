package io.github.ronynn.hashiru;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class SafDoc {
    public final String docId;
    public final String name;
    public final String mime;
    public final boolean isDir;

    public SafDoc(String docId, String name, String mime) {
        this.docId = docId;
        this.name = name;
        this.mime = mime;
        this.isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
    }

    private static final String[] PROJ = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
    };

    public static List<SafDoc> list(ContentResolver cr, Uri treeUri, String parentDocId) {
        List<SafDoc> out = new ArrayList<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        Cursor c = cr.query(children, PROJ, null, null, null);
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    out.add(new SafDoc(c.getString(0), c.getString(1), c.getString(2)));
                }
            } finally {
                c.close();
            }
        }
        return out;
    }

    public static SafDoc find(ContentResolver cr, Uri treeUri, String parentDocId, String name) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        Cursor c = cr.query(children, PROJ, null, null, null);
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    if (name.equals(c.getString(1))) {
                        return new SafDoc(c.getString(0), c.getString(1), c.getString(2));
                    }
                }
            } finally {
                c.close();
            }
        }
        return null;
    }

    public Uri docUri(Uri treeUri) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
    }

    // ---------------- Create / Rename / Delete / Duplicate ----------------

    public static SafDoc createFile(ContentResolver cr, Uri treeUri,
                                    String parentDocId, String displayName, String mimeType) {
        try {
            Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId);
            Uri newUri = DocumentsContract.createDocument(cr, parentUri, mimeType, displayName);
            if (newUri == null) return null;
            String newDocId = DocumentsContract.getDocumentId(newUri);
            return new SafDoc(newDocId, displayName, mimeType);
        } catch (Exception e) {
            return null;
        }
    }

    public static SafDoc createFolder(ContentResolver cr, Uri treeUri,
                                      String parentDocId, String displayName) {
        return createFile(cr, treeUri, parentDocId, displayName,
                DocumentsContract.Document.MIME_TYPE_DIR);
    }

    public static boolean rename(ContentResolver cr, Uri treeUri,
                                 SafDoc doc, String newName) {
        try {
            Uri docUri = doc.docUri(treeUri);
            Uri result = DocumentsContract.renameDocument(cr, docUri, newName);
            return result != null;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean delete(ContentResolver cr, Uri treeUri, SafDoc doc) {
        try {
            Uri docUri = doc.docUri(treeUri);
            return DocumentsContract.deleteDocument(cr, docUri);
        } catch (Exception e) {
            return false;
        }
    }

    /** Copy file contents to a new file in the same parent. Returns the new doc. */
    public static SafDoc duplicateFile(ContentResolver cr, Uri treeUri,
                                       SafDoc src, String parentDocId, String newName) {
        InputStream in = null;
        OutputStream out = null;
        try {
            Uri srcUri = src.docUri(treeUri);
            in = cr.openInputStream(srcUri);
            if (in == null) return null;

            SafDoc dest = createFile(cr, treeUri, parentDocId, newName, src.mime);
            if (dest == null) return null;

            Uri destUri = dest.docUri(treeUri);
            out = cr.openOutputStream(destUri, "w");
            if (out == null) return null;

            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return dest;
        } catch (Exception e) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
    }

    public static String mimeForName(String name) {
        if (name == null) return "text/plain";
        String s = name.toLowerCase();
        if (s.endsWith(".md") || s.endsWith(".markdown")) return "text/markdown";
        if (s.endsWith(".txt"))    return "text/plain";
        if (s.endsWith(".html") || s.endsWith(".htm")) return "text/html";
        if (s.endsWith(".js") || s.endsWith(".mjs")) return "application/javascript";
        if (s.endsWith(".css"))    return "text/css";
        if (s.endsWith(".json"))   return "application/json";
        if (s.endsWith(".csv"))    return "text/csv";
        if (s.endsWith(".xml"))    return "application/xml";
        return "text/plain";
    }
}