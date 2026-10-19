package io.github.ronynn.hashiru;

import android.content.Context;
import android.content.SharedPreferences;

public class ReaderSettings {

    public static final String PREFS = "hashiru";
    public static final String KEY_THEME = "reader_theme";
    public static final String KEY_FONT = "reader_font";
    public static final String KEY_SIZE = "reader_size";
    public static final String KEY_BLUR = "reader_blur";
    public static final String KEY_ERUDA = "eruda_enabled";

    public static final String[] THEME_VALUES = {
        "dark", "nord", "gruvbox", "things", "paper"
    };
    public static final String[] THEME_LABELS = {
        "Dracula (Dark)", "Nord", "Gruvbox Dark", "Things (Light)", "Paper"
    };

    public static final String[] FONT_VALUES = {
        "sans", "sans-light", "sans-condensed", "serif", "mono"
    };
    public static final String[] FONT_LABELS = {
        "Sans", "Sans Light", "Sans Condensed", "Serif", "Monospace"
    };

    public static final int[] SIZE_VALUES = { 15, 17, 20, 23, 27 };
    public static final String[] SIZE_LABELS = {
        "Smol · 15", "Normal · 17", "Chonk · 20",
        "Big Chonk · 23", "ABSOLUTE · 27"
    };

    public String theme = "dark";
    public String font = "sans";
    public int fontSize = 17;
    public boolean blurEnabled = true;
    public boolean erudaEnabled = false;

    public static ReaderSettings load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ReaderSettings s = new ReaderSettings();
        s.theme = p.getString(KEY_THEME, "dark");
        s.font = p.getString(KEY_FONT, "sans");
        s.fontSize = p.getInt(KEY_SIZE, 17);
        s.blurEnabled = p.getBoolean(KEY_BLUR, true);
        s.erudaEnabled = p.getBoolean(KEY_ERUDA, false);
        return s;
    }

    public boolean isLightTheme() {
        return "things".equals(theme) || "paper".equals(theme);
    }
}