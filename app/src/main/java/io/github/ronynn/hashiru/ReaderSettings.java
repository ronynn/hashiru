package io.github.ronynn.hashiru;

import android.content.Context;
import android.content.SharedPreferences;

public class ReaderSettings {

    public static final String PREFS = "hashiru";
    public static final String KEY_THEME = "reader_theme";
    public static final String KEY_FONT = "reader_font";
    public static final String KEY_SIZE = "reader_size";
    public static final String KEY_ERUDA = "eruda_enabled";

    public static final String[] THEME_VALUES = {
        "dark", "amoled", "nord", "gruvbox",
        "light", "sepia", "solarized_dark", "themeforest_light"
    };
    public static final String[] THEME_LABELS = {
        "Dracula (Dark)", "AMOLED (Black)", "Nord", "Gruvbox Dark",
        "Light", "Sepia", "Solarized Dark", "ThemeForest Light"
    };

    public static final String[] FONT_VALUES = {
        "sans", "sans-light", "sans-thin", "sans-medium", "sans-black",
        "sans-condensed", "serif", "mono", "cursive"
    };
    public static final String[] FONT_LABELS = {
        "Sans (Roboto)", "Sans Light", "Sans Thin", "Sans Medium", "Sans Black",
        "Sans Condensed", "Serif", "Monospace", "Cursive"
    };

    public static final int[] SIZE_VALUES = { 14, 16, 17, 18, 20, 22, 24 };
    public static final String[] SIZE_LABELS = {
        "Smol · 14", "Smol+ · 16", "Normal · 17", "Comfy · 18",
        "Chonk · 20", "Big Chonk · 22", "ABSOLUTE UNIT · 24"
    };

    public String theme = "dark";
    public String font = "sans";
    public int fontSize = 17;
    public boolean erudaEnabled = false;

    public static ReaderSettings load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ReaderSettings s = new ReaderSettings();
        s.theme = p.getString(KEY_THEME, "dark");
        s.font = p.getString(KEY_FONT, "sans");
        s.fontSize = p.getInt(KEY_SIZE, 17);
        s.erudaEnabled = p.getBoolean(KEY_ERUDA, false);
        return s;
    }

    public boolean isLightTheme() {
        return "light".equals(theme) || "sepia".equals(theme)
            || "themeforest_light".equals(theme);
    }
}