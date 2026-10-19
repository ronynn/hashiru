package io.github.ronynn.hashiru;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;

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
    private final List<String> folderPath = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);

        list = findViewById(R.id.list);
        pathView = findViewById(R.id.path);
        Button pick = findViewById(R.id.pick);
        ImageButton menu = findViewById(R.id.menu);

        adapter = new FileListAdapter(this);
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.empty));

        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_PICK);
        });

        menu.setOnClickListener(this::showSettingsMenu);

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

    private void showSettingsMenu(View anchor) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, "Theme…");
        m.add(0, 2, 1, "Font…");
        m.add(0, 3, 2, "Font size…");
        MenuItem blur = m.add(0, 4, 3, "Blur status bar");
        blur.setCheckable(true);
        blur.setChecked(prefs.getBoolean(ReaderSettings.KEY_BLUR, true));
        MenuItem eruda = m.add(0, 5, 4, "Eruda");
        eruda.setCheckable(true);
        eruda.setChecked(prefs.getBoolean(ReaderSettings.KEY_ERUDA, false));

        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: showThemeDialog(); return true;
                case 2: showFontDialog();  return true;
                case 3: showSizeDialog();  return true;
                case 4: {
                    boolean v = !item.isChecked();
                    prefs.edit().putBoolean(ReaderSettings.KEY_BLUR, v).apply();
                    item.setChecked(v);
                    return true;
                }
                case 5: {
                    boolean v = !item.isChecked();
                    prefs.edit().putBoolean(ReaderSettings.KEY_ERUDA, v).apply();
                    item.setChecked(v);
                    return true;
                }
            }
            return false;
        });
        pm.show();
    }

    private void showThemeDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int idx = indexOf(ReaderSettings.THEME_VALUES,
                prefs.getString(ReaderSettings.KEY_THEME, "dark"));
        new AlertDialog.Builder(this)
                .setTitle("Theme")
                .setSingleChoiceItems(ReaderSettings.THEME_LABELS, idx, (d, w) -> {
                    prefs.edit().putString(ReaderSettings.KEY_THEME,
                            ReaderSettings.THEME_VALUES[w]).apply();
                    d.dismiss();
                })
                .show();
    }

    private void showFontDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int idx = indexOf(ReaderSettings.FONT_VALUES,
                prefs.getString(ReaderSettings.KEY_FONT, "sans"));
        new AlertDialog.Builder(this)
                .setTitle("Font")
                .setSingleChoiceItems(ReaderSettings.FONT_LABELS, idx, (d, w) -> {
                    prefs.edit().putString(ReaderSettings.KEY_FONT,
                            ReaderSettings.FONT_VALUES[w]).apply();
                    d.dismiss();
                })
                .show();
    }

    private void showSizeDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int cur = prefs.getInt(ReaderSettings.KEY_SIZE, 15);
        int idx = 2;
        for (int i = 0; i < ReaderSettings.SIZE_VALUES.length; i++)
            if (ReaderSettings.SIZE_VALUES[i] == cur) idx = i;
        new AlertDialog.Builder(this)
                .setTitle("Font size")
                .setSingleChoiceItems(ReaderSettings.SIZE_LABELS, idx, (d, w) -> {
                    prefs.edit().putInt(ReaderSettings.KEY_SIZE,
                            ReaderSettings.SIZE_VALUES[w]).apply();
                    d.dismiss();
                })
                .show();
    }

    private static int indexOf(String[] arr, String v) {
        if (v == null) return 0;
        for (int i = 0; i < arr.length; i++) if (v.equals(arr[i])) return i;
        return 0;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null) {
            Uri u = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
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
        if (folderPath.isEmpty()) rel = d.name;
        else rel = String.join("/", folderPath) + "/" + d.name;

        Intent i = new Intent(this, WebViewActivity.class);
        // NEW_DOCUMENT without MULTIPLE_TASK → OS looks for an existing task
        // whose base intent matches (by data URI). Same file → brought forward.
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);

        // Identity for the OS: (tree URI, rel path). Same file in the same
        // tree always maps to the same URI → no duplicate task.
        String identity = treeUri.toString() + "|" + rel;
        i.setData(Uri.parse("hashiru://open/" + Uri.encode(identity)));

        i.putExtra(WebViewActivity.EXTRA_TREE_URI, treeUri.toString());
        i.putExtra(WebViewActivity.EXTRA_REL_PATH, rel);
        startActivity(i);
    }
}