package io.github.ronynn.hashiru;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String PREFS = "hashiru";
    private static final String KEY_URI = "tree_uri";
    private static final String KEY_MUSIC_ICON = "music_launcher_enabled";
    private static final String KEY_RECENT = "recent_files";
    private static final int MAX_RECENT = 8;
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

        syncMusicLauncherState();

        list = findViewById(R.id.list);
        pathView = findViewById(R.id.path);
        Button pick = findViewById(R.id.pick);
        ImageButton menu = findViewById(R.id.menu);
        ImageButton fab = findViewById(R.id.fab);

        adapter = new FileListAdapter(this);
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.empty));

        pick.setOnClickListener(v -> pickFolder());
        menu.setOnClickListener(this::showSettingsMenu);

        list.setOnItemClickListener((p, v, pos, id) -> {
            SafDoc d = adapter.getItem(pos);
            if (d.isDir) {
                folderPath.add(d.name);
                currentDocId = d.docId;
                refresh();
            } else {
                openInWebView(d, false);
            }
        });

        list.setOnItemLongClickListener((p, v, pos, id) -> {
            SafDoc d = adapter.getItem(pos);
            showFileContextMenu(d, pos);
            return true;
        });

        fab.setOnClickListener(v -> showNewFileDialog());
        fab.setOnLongClickListener(v -> {
            showNewFolderDialog();
            return true;
        });

        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URI, null);
        if (saved != null) {
            try { setTree(Uri.parse(saved)); } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------
    // Recent files
    // -------------------------------------------------------------

    private void addRecentFile(String treeUriStr, String relPath, String displayName) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String stored = prefs.getString(KEY_RECENT, "");
        List<String> entries = new ArrayList<>();
        if (!stored.isEmpty()) {
            for (String line : stored.split("\n")) {
                if (!line.isEmpty()) entries.add(line);
            }
        }
        String newEntry = treeUriStr + "\t" + relPath + "\t" + displayName;
        entries.remove(newEntry);
        entries.add(0, newEntry);
        while (entries.size() > MAX_RECENT) entries.remove(entries.size() - 1);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(entries.get(i));
        }
        prefs.edit().putString(KEY_RECENT, sb.toString()).apply();
    }

    private List<String[]> getRecentFiles() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String stored = prefs.getString(KEY_RECENT, "");
        List<String[]> out = new ArrayList<>();
        if (stored.isEmpty()) return out;
        for (String line : stored.split("\n")) {
            if (line.isEmpty()) continue;
            String[] parts = line.split("\t");
            if (parts.length >= 3) out.add(parts);
        }
        return out;
    }

    private void showRecentFilesDialog() {
        final List<String[]> recents = getRecentFiles();
        if (recents.isEmpty()) { toast("No recent files"); return; }

        String[] labels = new String[recents.size()];
        for (int i = 0; i < recents.size(); i++) {
            String[] r = recents.get(i);
            String relPath = r[1];
            String displayName = r[2];
            labels[i] = displayName + "   ·   " + shortenPath(relPath);
        }

        new AlertDialog.Builder(this)
                .setTitle("Recent files")
                .setItems(labels, (d, w) -> {
                    String[] r = recents.get(w);
                    openFromRecent(r[0], r[1]);
                })
                .setNegativeButton("Clear", (d, w) -> {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .remove(KEY_RECENT).apply();
                })
                .show();
    }

    private void openFromRecent(String treeUriStr, String relPath) {
        try {
            Intent i = new Intent(this, WebViewActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
            String identity = treeUriStr + "|" + relPath;
            i.setData(Uri.parse("hashiru://open/" + Uri.encode(identity)));
            i.putExtra(WebViewActivity.EXTRA_TREE_URI, treeUriStr);
            i.putExtra(WebViewActivity.EXTRA_REL_PATH, relPath);
            startActivity(i);
        } catch (Exception e) {
            toast("Failed to open");
        }
    }

    private String shortenPath(String path) {
        if (path == null) return "";
        int idx = path.lastIndexOf('/');
        if (idx <= 0) return path;
        String folder = path.substring(0, idx);
        if (folder.length() > 24) folder = "…" + folder.substring(folder.length() - 23);
        return folder + "/";
    }

    // -------------------------------------------------------------
    // Music launcher toggle
    // -------------------------------------------------------------

    private void syncMusicLauncherState() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(KEY_MUSIC_ICON, true);
        setMusicLauncherEnabled(enabled);
    }

    private void setMusicLauncherEnabled(boolean enabled) {
        try {
            ComponentName alias = new ComponentName(this, "io.github.ronynn.hashiru.MusicLauncher");
            int state = enabled
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            getPackageManager().setComponentEnabledSetting(
                    alias, state, PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------
    // File context menu
    // -------------------------------------------------------------

    private void showFileContextMenu(SafDoc d, int pos) {
        PopupMenu pm = new PopupMenu(this, list.getChildAt(pos));
        Menu m = pm.getMenu();
        m.add(0, 1, 0, d.isDir ? "Open" : "Edit");
        m.add(0, 2, 1, "Rename");
        if (!d.isDir) m.add(0, 3, 2, "Copy");
        m.add(0, 4, 3, "Delete");

        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    if (d.isDir) {
                        folderPath.add(d.name);
                        currentDocId = d.docId;
                        refresh();
                    } else {
                        openInWebView(d, true);
                    }
                    return true;
                case 2: showRenameDialog(d); return true;
                case 3: copyFile(d); return true;
                case 4: confirmDelete(d); return true;
            }
            return false;
        });
        pm.show();
    }

    private void showNewFileDialog() {
        if (treeUri == null) { toast("Pick a folder first"); return; }

        final EditText input = new EditText(this);
        input.setHint("filename.md");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF888888);

        new AlertDialog.Builder(this)
                .setTitle("New file")
                .setView(input)
                .setPositiveButton("Create", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    if (!name.contains(".")) name += ".md";
                    String mime = SafDoc.mimeForName(name);
                    SafDoc created = SafDoc.createFile(getContentResolver(),
                            treeUri, currentDocId, name, mime);
                    if (created != null) {
                        toast("Created " + name);
                        refresh();
                    } else {
                        toast("Failed to create file");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showNewFolderDialog() {
        if (treeUri == null) { toast("Pick a folder first"); return; }

        final EditText input = new EditText(this);
        input.setHint("Folder name");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF888888);

        new AlertDialog.Builder(this)
                .setTitle("New folder")
                .setView(input)
                .setPositiveButton("Create", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    SafDoc created = SafDoc.createFolder(getContentResolver(),
                            treeUri, currentDocId, name);
                    if (created != null) {
                        toast("Created " + name);
                        refresh();
                    } else {
                        toast("Failed to create folder");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showRenameDialog(SafDoc d) {
        final EditText input = new EditText(this);
        input.setText(d.name);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextColor(0xFFFFFFFF);
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this)
                .setTitle("Rename")
                .setView(input)
                .setPositiveButton("Rename", (dlg, w) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty() || newName.equals(d.name)) return;
                    if (SafDoc.rename(getContentResolver(), treeUri, d, newName)) {
                        toast("Renamed");
                        refresh();
                    } else {
                        toast("Rename failed");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDelete(SafDoc d) {
        new AlertDialog.Builder(this)
                .setTitle("Delete")
                .setMessage("Delete \"" + d.name + "\"?")
                .setPositiveButton("Delete", (dlg, w) -> {
                    if (SafDoc.delete(getContentResolver(), treeUri, d)) {
                        toast("Deleted");
                        refresh();
                    } else {
                        toast("Delete failed");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void copyFile(SafDoc d) {
        String base = d.name;
        String ext = "";
        int dot = base.lastIndexOf('.');
        if (dot > 0) { ext = base.substring(dot); base = base.substring(0, dot); }

        String newName;
        int n = 1;
        do {
            newName = base + " (copy" + (n > 1 ? " " + n : "") + ")" + ext;
            n++;
        } while (SafDoc.find(getContentResolver(), treeUri, currentDocId, newName) != null
                && n < 100);

        SafDoc copy = SafDoc.duplicateFile(getContentResolver(), treeUri,
                d, currentDocId, newName);
        if (copy != null) {
            toast("Copied to " + newName);
            refresh();
        } else {
            toast("Copy failed");
        }
    }

    // -------------------------------------------------------------
    // Settings menu
    // -------------------------------------------------------------

    private void showSettingsMenu(View anchor) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        m.add(0, 7, 0, "Recent files…");
        m.add(0, 1, 1, "Theme…");
        m.add(0, 2, 2, "Font…");
        m.add(0, 3, 3, "Font size…");
        MenuItem autosave = m.add(0, 4, 4, "Autosave");
        autosave.setCheckable(true);
        autosave.setChecked(prefs.getBoolean(ReaderSettings.KEY_AUTOSAVE, false));
        MenuItem eruda = m.add(0, 5, 5, "Eruda");
        eruda.setCheckable(true);
        eruda.setChecked(prefs.getBoolean(ReaderSettings.KEY_ERUDA, false));
        MenuItem musicIcon = m.add(0, 6, 6, "Music launcher icon");
        musicIcon.setCheckable(true);
        musicIcon.setChecked(prefs.getBoolean(KEY_MUSIC_ICON, true));

        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: showThemeDialog(); return true;
                case 2: showFontDialog();  return true;
                case 3: showSizeDialog();  return true;
                case 4: {
                    boolean v = !item.isChecked();
                    prefs.edit().putBoolean(ReaderSettings.KEY_AUTOSAVE, v).apply();
                    item.setChecked(v);
                    return true;
                }
                case 5: {
                    boolean v = !item.isChecked();
                    prefs.edit().putBoolean(ReaderSettings.KEY_ERUDA, v).apply();
                    item.setChecked(v);
                    return true;
                }
                case 6: {
                    boolean v = !item.isChecked();
                    prefs.edit().putBoolean(KEY_MUSIC_ICON, v).apply();
                    setMusicLauncherEnabled(v);
                    item.setChecked(v);
                    return true;
                }
                case 7: showRecentFilesDialog(); return true;
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

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // -------------------------------------------------------------
    // Folder picking / navigation
    // -------------------------------------------------------------

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_PICK);
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

    // -------------------------------------------------------------
    // Open in WebViewActivity
    // -------------------------------------------------------------

    private void openInWebView(SafDoc d, boolean editMode) {
        String rel;
        if (folderPath.isEmpty()) rel = d.name;
        else rel = String.join("/", folderPath) + "/" + d.name;

        // Record in recents (view mode — edit-mode opens still land in the
        // same entry so the user can pick the file up later in read mode).
        addRecentFile(treeUri.toString(), rel, d.name);

        Intent i = new Intent(this, WebViewActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);

        String identity = treeUri.toString() + "|" + rel
                + (editMode ? "|edit" : "");
        i.setData(Uri.parse("hashiru://open/" + Uri.encode(identity)));

        i.putExtra(WebViewActivity.EXTRA_TREE_URI, treeUri.toString());
        i.putExtra(WebViewActivity.EXTRA_REL_PATH, rel);
        i.putExtra(WebViewActivity.EXTRA_EDIT_MODE, editMode);
        startActivity(i);
    }
}