package io.github.ronynn.hashiru;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class FileListAdapter extends BaseAdapter {

    private final List<SafDoc> items = new ArrayList<>();
    private final LayoutInflater inflater;

    public FileListAdapter(Context ctx) {
        this.inflater = LayoutInflater.from(ctx);
    }

    public void setItems(List<SafDoc> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @Override public int getCount() { return items.size(); }
    @Override public SafDoc getItem(int i) { return items.get(i); }
    @Override public long getItemId(int i) { return i; }

    @Override
    public View getView(int i, View convert, ViewGroup parent) {
        View v = convert;
        if (v == null) v = inflater.inflate(R.layout.item_file, parent, false);
        TextView name = v.findViewById(R.id.name);
        TextView icon = v.findViewById(R.id.icon);
        SafDoc d = items.get(i);
        name.setText(d.name == null ? "?" : d.name);
        icon.setText(d.isDir ? "📁" : iconFor(d.name));
        return v;
    }

    private String iconFor(String n) {
        if (n == null) return "📄";
        String s = n.toLowerCase();
        if (s.endsWith(".html") || s.endsWith(".htm")) return "🌐";
        if (s.endsWith(".js") || s.endsWith(".mjs")) return "📜";
        if (s.endsWith(".css")) return "🎨";
        if (s.endsWith(".json")) return "🧾";
        if (s.endsWith(".png") || s.endsWith(".jpg") || s.endsWith(".jpeg")
                || s.endsWith(".gif") || s.endsWith(".svg") || s.endsWith(".webp")) return "🖼";
        return "📄";
    }
}