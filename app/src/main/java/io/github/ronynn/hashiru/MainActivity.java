package io.github.ronynn.hashiru;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String PREFS = "hashiru";
    private static final String KEY_URI = "tree_uri";
    private static final int REQ_PICK = 1001;

    private ListView list;
    private TextView pathView;
    private FileListAdapter adapter;

    private Uri treeUri;
    private String rootDocId;
    private String currentDocId;
    private final List<String> folderPath = new ArrayList<>(); // names from root

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        list = findViewById(R.id.list);
        pathView = findViewById(R.id.path);
        Button pick = findViewById(R.id.pick);

        adapter = new FileListAdapter(this);
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.empty));

        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(i, REQ_PICK);
        });

        list.setOnItemClickListener((p, v, pos, id) -> {
            SafDoc d = adapter.getItem(pos);
            if (d.isDir) {
                folderPath.add(d.name);
                currentDocId = d.docId;
                refresh();
            } else {
                openInWebView(d);
            }
        });

        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URI, null);
        if (saved != null) {
            try { setTree(Uri.parse(saved)); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null) {
            Uri u = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(
                        u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {}
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_URI, u.toString()).apply();
            setTree(u);
        }
    }

    private void setTree(Uri u) {
        treeUri = u;
        rootDocId = DocumentsContract.getTreeDocumentId(u);
        currentDocId = rootDocId;
        folderPath.clear();
        refresh();
    }

    private void refresh() {
        if (treeUri == null) return;
        List<SafDoc> items = SafDoc.list(getContentResolver(), treeUri, currentDocId);
        // directories first, then alphabetical
        items.sort((a, b) -> {
            if (a.isDir != b.isDir) return a.isDir ? -1 : 1;
            String an = a.name == null ? "" : a.name;
            String bn = b.name == null ? "" : b.name;
            return an.compareToIgnoreCase(bn);
        });
        adapter.setItems(items);

        StringBuilder sb = new StringBuilder("/");
        for (int i = 0; i < folderPath.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(folderPath.get(i));
        }
        pathView.setText(sb.toString());
    }

    private String resolveCurrentDocId() {
        String cur = rootDocId;
        for (String name : folderPath) {
            SafDoc child = SafDoc.find(getContentResolver(), treeUri, cur, name);
            if (child == null) return rootDocId;
            cur = child.docId;
        }
        return cur;
    }

    @Override
    public void onBackPressed() {
        if (!folderPath.isEmpty()) {
            folderPath.remove(folderPath.size() - 1);
            currentDocId = resolveCurrentDocId();
            refresh();
        } else {
            super.onBackPressed();
        }
    }

    private void openInWebView(SafDoc d) {
        String rel;
        if (folderPath.isEmpty()) {
            rel = d.name;
        } else {
            rel = String.join("/", folderPath) + "/" + d.name;
        }
        Intent i = new Intent(this, WebViewActivity.class);
        i.putExtra(WebViewActivity.EXTRA_TREE_URI, treeUri.toString());
        i.putExtra(WebViewActivity.EXTRA_REL_PATH, rel);
        startActivity(i);
    }
}