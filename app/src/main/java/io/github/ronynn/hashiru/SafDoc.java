package io.github.ronynn.hashiru;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

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
}