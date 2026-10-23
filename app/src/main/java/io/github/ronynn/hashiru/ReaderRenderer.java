package io.github.ronynn.hashiru;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ReaderRenderer {

    private static final List<Extension> EXTENSIONS = Arrays.asList(
            TablesExtension.create(),
            StrikethroughExtension.create()
    );

    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();

    private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .escapeHtml(true)
            .sanitizeUrls(true)
            .build();

    public static String render(String ext, String raw,
                                ReaderSettings settings,
                                int sbhDp, int nbhDp, boolean blurEnabled) {
        boolean isMd = ext.equals("md") || ext.equals("markdown") || ext.equals("txt");
        boolean isCsv = ext.equals("csv");

        String body;
        if (isCsv) {
            body = renderCsv(raw);
        } else if (isMd) {
            Node doc = PARSER.parse(raw);
            convertSoftBreaksToHard(doc);
            body = RENDERER.render(doc);
            body = body.replace("<table>", "<div class=\"table-wrap\"><table>")
                       .replace("</table>", "</table></div>");
        } else {
            body = "<pre class=\"plain\">" + escapeHtml(raw) + "</pre>";
        }
        return wrap(body, raw, settings, sbhDp, nbhDp, blurEnabled);
    }

    private static void convertSoftBreaksToHard(Node node) {
        Node child = node.getFirstChild();
        while (child != null) {
            Node next = child.getNext();
            if (child instanceof SoftLineBreak) {
                Node hard = new HardLineBreak();
                child.insertAfter(hard);
                child.unlink();
            } else {
                convertSoftBreaksToHard(child);
            }
            child = next;
        }
    }

    // ---- CSV ----

    private static String renderCsv(String raw) {
        List<String[]> rows = parseCsv(raw);
        if (rows.isEmpty()) return "<p><em>(empty CSV)</em></p>";

        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"table-wrap\"><table>");
        String[] header = rows.get(0);
        sb.append("<thead><tr>");
        for (String h : header)
            sb.append("<th>").append(escapeHtml(h)).append("</th>");
        sb.append("</tr></thead><tbody>");
        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            sb.append("<tr>");
            for (String cell : row)
                sb.append("<td>").append(escapeHtml(cell)).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</tbody></table></div>");
        return sb.toString();
    }

    private static List<String[]> parseCsv(String text) {
        List<String[]> rows = new ArrayList<>();
        if (text == null) return rows;
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    current.add(field.toString());
                    field.setLength(0);
                } else if (c == '\n' || c == '\r') {
                    current.add(field.toString());
                    field.setLength(0);
                    boolean blank = current.size() == 1 && current.get(0).isEmpty();
                    if (!blank) rows.add(current.toArray(new String[0]));
                    current = new ArrayList<>();
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                } else {
                    field.append(c);
                }
            }
        }
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            boolean blank = current.size() == 1 && current.get(0).isEmpty();
            if (!blank) rows.add(current.toArray(new String[0]));
        }
        return rows;
    }

    // ---- helpers ----

    public static int backgroundArgb(String theme) {
        switch (theme) {
            case "nord":    return 0xFF2e3440;
            case "gruvbox": return 0xFF282828;
            case "things":  return 0xFFfafafa;
            case "paper":   return 0xFFf5f1e8;
            default:        return 0xFF282a36;
        }
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        int n = s.length();
        StringBuilder sb = null;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            String rep = null;
            switch (c) {
                case '&': rep = "&amp;"; break;
                case '<': rep = "&lt;";  break;
                case '>': rep = "&gt;";  break;
            }
            if (rep != null) {
                if (sb == null) {
                    sb = new StringBuilder(n + 16);
                    sb.append(s, 0, i);
                }
                sb.append(rep);
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? s : sb.toString();
    }

    private static String wrap(String body, String rawSource, ReaderSettings s,
                               int sbhDp, int nbhDp, boolean blurEnabled) {
        Palette p = paletteFor(s.theme);
        String css = themeCss(s, p, sbhDp, nbhDp, blurEnabled);
        String sourceJson = jsonEscape(rawSource);

        String csp = "default-src 'none'; "
                   + "img-src 'self' data:; "
                   + "style-src 'unsafe-inline'; "
                   + "script-src 'unsafe-inline'; "
                   + "font-src 'self';";

        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
             + "<meta http-equiv=\"Content-Security-Policy\" content=\"" + csp + "\">"
             + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">"
             + "<meta name=\"referrer\" content=\"no-referrer\">"
             + "<style>" + css + "</style></head><body>"
             + "<div id=\"hashiru-view\">" + body + "</div>"
             + "<textarea id=\"hashiru-editor\" spellcheck=\"false\" autocorrect=\"off\" autocomplete=\"off\"></textarea>"
             + "<button id=\"hashiru-edit-btn\" type=\"button\" aria-label=\"Edit\">"
             +   "<svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z\"/></svg>"
             + "</button>"
             + "<button id=\"hashiru-done\" type=\"button\" aria-label=\"Done\">"
             +   "<svg viewBox=\"0 0 24 24\" fill=\"currentColor\"><path d=\"M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z\"/></svg>"
             + "</button>"
             + "<div id=\"hashiru-sb-track\"><div id=\"hashiru-sb-thumb\"></div></div>"
             + "<div id=\"hashiru-statusblur\"></div>"
             + "<div id=\"hashiru-navblur\"></div>"
             + "<script id=\"hashiru-source\" type=\"application/json\">" + sourceJson + "</script>"
             + "<script>" + EDIT_SCRIPT + "</script>"
             + "</body></html>";
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '<':  sb.append("\\u003c"); break;
                case '>':  sb.append("\\u003e"); break;
                case '&':  sb.append("\\u0026"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String fontCss(ReaderSettings s) {
        switch (s.font) {
            case "sans-light":     return "font-family:sans-serif;font-weight:300;";
            case "sans-condensed": return "font-family:sans-serif-condensed;";
            case "serif":          return "font-family:serif;";
            case "mono":           return "font-family:monospace;";
            default:               return "font-family:sans-serif;";
        }
    }

    private static String themeCss(ReaderSettings s, Palette p,
                                   int sbhDp, int nbhDp, boolean blurEnabled) {
        int size = s.fontSize;

        int contentTop = sbhDp + 68;

        int navBarDp  = nbhDp > 0 ? nbhDp : 24;
        int bottomPad = navBarDp + 40;

        int btnTop    = sbhDp + 12;
        int sbTop     = sbhDp + 8;
        int editorTop = contentTop;

        boolean showTopFade = blurEnabled;
        boolean showBotFade = blurEnabled;
        int topFadeH = sbhDp + 6;
        int botFadeH = navBarDp + 6;

        // Stronger fade — text is visible (dimmed) at every point, but the
        // top of the mask is clearly denser than before so the fade is
        // actually perceptible. 0.80 == 20% of text still shows through.
        String topMask = "linear-gradient(to bottom,"
            + "rgba(0,0,0,0.80) 0%,"
            + "rgba(0,0,0,0.35) 50%,"
            + "rgba(0,0,0,0) 100%)";
        String botMask = "linear-gradient(to top,"
            + "rgba(0,0,0,0.80) 0%,"
            + "rgba(0,0,0,0.35) 50%,"
            + "rgba(0,0,0,0) 100%)";

        String topFadeRule = showTopFade
            ? "#hashiru-statusblur{position:fixed;top:0;left:0;right:0;"
              + "height:" + topFadeH + "px;"
              + "background:" + p.bg + ";"
              + "-webkit-mask-image:" + topMask + ";"
              + "mask-image:" + topMask + ";"
              + "z-index:9700;pointer-events:none;}"
            : "#hashiru-statusblur{display:none;}";

        String botFadeRule = showBotFade
            ? "#hashiru-navblur{position:fixed;bottom:0;left:0;right:0;"
              + "height:" + botFadeH + "px;"
              + "background:" + p.bg + ";"
              + "-webkit-mask-image:" + botMask + ";"
              + "mask-image:" + botMask + ";"
              + "z-index:9400;pointer-events:none;}"
            : "#hashiru-navblur{display:none;}";

        String glassButton =
            "position:fixed;"
            + "top:" + btnTop + "px;right:16px;"
            + "width:44px;height:44px;padding:0;margin:0;"
            + "border-radius:50%;"
            + "border:1px solid rgba(127,127,127,0.22);"
            + "background:rgba(127,127,127,0.10);"
            + "-webkit-backdrop-filter:blur(16px) saturate(160%);"
            + "backdrop-filter:blur(16px) saturate(160%);"
            + "color:" + p.text + ";"
            + "font-family:sans-serif;font-size:20px;"
            + "box-sizing:border-box;z-index:9600;cursor:pointer;"
            + "-webkit-tap-highlight-color:transparent;"
            + "align-items:center;justify-content:center;"
            + "box-shadow:0 2px 8px rgba(0,0,0,0.10);";

        return "html{background:" + p.bg + ";color:" + p.text + ";overflow-x:hidden;}"
            + "body{max-width:48em;margin:0 auto;"
            +   "padding:" + contentTop + "px 1em " + bottomPad + "px;"
            +   fontCss(s) + "font-size:" + size + "px;line-height:1.55;"
            +   "-webkit-text-size-adjust:100%;-webkit-tap-highlight-color:transparent;"
            +   "overflow-wrap:break-word;touch-action:manipulation;}"
            + "::selection{background:" + p.selectionBg + ";color:" + p.selectionFg + ";}"
            + "h1,h2,h3,h4,h5,h6{line-height:1.3;margin:1em 0 .4em;font-weight:600;}"
            + "h1{color:" + p.h1 + ";font-size:1.9em;border-bottom:1px solid " + p.border + ";padding-bottom:.3em;}"
            + "h2{color:" + p.h2 + ";font-size:1.5em;border-bottom:1px solid " + p.border + ";padding-bottom:.25em;}"
            + "h3{color:" + p.h3 + ";font-size:1.25em;}"
            + "h4{color:" + p.h4 + ";font-size:1.1em;}"
            + "h5{color:" + p.h5 + ";font-size:1em;}"
            + "h6{color:" + p.h6 + ";font-size:1em;}"
            + "a{color:" + p.link + ";text-decoration:none;}"
            + "a:hover{text-decoration:underline;}"
            + "a:focus{outline:2px solid " + p.link + ";outline-offset:2px;border-radius:2px;}"
            + "strong,b{color:" + p.strong + ";font-weight:600;}"
            + "em,i{color:" + p.em + ";}"
            + "del,s{color:" + p.del + ";text-decoration:line-through;}"
            + "mark{background:" + p.markBg + ";color:" + p.markFg + ";padding:0 .3em;border-radius:3px;}"
            + "code{background:" + p.codeBg + ";color:" + p.codeFg + ";padding:.15em .4em;border-radius:4px;"
            +   "font-family:ui-monospace,Menlo,Consolas,monospace;font-size:.92em;}"
            + "kbd{background:" + p.kbdBg + ";color:" + p.kbdFg + ";padding:.1em .4em;border-radius:4px;"
            +   "font-family:ui-monospace,Menlo,Consolas,monospace;font-size:.88em;"
            +   "border:1px solid " + p.border + ";}"
            + "pre{background:" + p.preBg + ";padding:1em;border-radius:8px;overflow:auto;"
            +   "font-family:ui-monospace,Menlo,Consolas,monospace;font-size:.9em;line-height:1.5;"
            +   "border:1px solid " + p.preBorder + ";}"
            + "pre.plain{white-space:pre-wrap;word-wrap:break-word;}"
            + "pre code{background:none;padding:0;color:inherit;}"
            + "blockquote{border-left:3px solid " + p.blockquoteBorder + ";padding:.2em 1em;"
            +   "color:" + p.blockquoteFg + ";margin:.6em 0;background:" + p.blockquoteBg + ";}"
            + "blockquote p:first-child{margin-top:0;}"
            + "blockquote p:last-child{margin-bottom:0;}"
            + "ul,ol{padding-left:1.5em;}"
            + "li{margin:.15em 0;}"
            + "ul li::marker{color:" + p.listMarker + ";}"
            + "ol li::marker{color:" + p.listMarker + ";font-weight:600;}"
            + ".table-wrap{overflow-x:auto;max-width:100%;margin:0 0 1em;-webkit-overflow-scrolling:touch;}"
            + "table{border-collapse:collapse;min-width:100%;}"
            + "th,td{border:1px solid " + p.tableBorder + ";padding:.5em .8em;text-align:left;}"
            + "th{background:" + p.thBg + ";color:" + p.thFg + ";font-weight:600;}"
            + "hr{border:none;border-top:1px solid " + p.hr + ";margin:1.5em 0;}"
            + "img{max-width:100%;height:auto;border-radius:6px;}"
            + "p{margin:.6em 0;}"
            + "#hashiru-editor{position:fixed;top:0;left:0;right:0;"
            +   "width:100%;height:100vh;height:100dvh;"
            +   "background:" + p.bg + ";color:" + p.text + ";"
            +   "font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;line-height:1.5;"
            +   "border:none;outline:none;box-sizing:border-box;"
            +   "padding:20px;padding-top:" + editorTop + "px;padding-bottom:40vh;"
            +   "resize:none;display:none;overflow-y:auto;z-index:9500;}"
            + "#hashiru-edit-btn{" + glassButton + "display:flex;}"
            + "#hashiru-done{" + glassButton + "display:none;}"
            + "#hashiru-edit-btn svg,#hashiru-done svg{width:20px;height:20px;display:block;}"
            + "#hashiru-sb-track{position:fixed;right:0;"
            +   "top:" + sbTop + "px;bottom:20px;width:20px;"
            +   "z-index:8000;touch-action:none;cursor:pointer;"
            +   "opacity:0;pointer-events:none;transition:opacity .15s;}"
            + "#hashiru-sb-thumb{position:absolute;right:3px;top:0;"
            +   "width:4px;min-height:24px;"
            +   "background:rgba(127,127,127,0.55);border-radius:2px;"
            +   "pointer-events:none;}"
            // ---- Landscape ----
            // Status bar is transparent (text flows behind, top fade visible).
            // Nav bar is opaque theme-color (content does NOT go behind it), so
            // there's no bottom fade and no extra bottom padding for it.
            // sbhDp-derived offsets (button top, editor top) stay as-is —
            // they still clear the transparent status bar.
            + "@media (orientation:landscape){"
            +   "#hashiru-navblur{display:none !important;}"
            +   "body{max-width:none;"
            +     "padding-bottom:40px;"
            +     "padding-left:max(2em,5vw);"
            +     "padding-right:max(2em,5vw);}"
            +   "#hashiru-editor{padding-bottom:20vh;"
            +     "left:50%;right:auto;width:90%;max-width:900px;"
            +     "transform:translateX(-50%);}"
            + "}"
            + topFadeRule
            + botFadeRule;
    }

    private static class Palette {
        String bg, text, border, hr;
        String h1, h2, h3, h4, h5, h6;
        String link;
        String codeBg, codeFg, preBg, preBorder;
        String blockquoteBorder, blockquoteBg, blockquoteFg;
        String tableBorder, thBg, thFg;
        String strong, em, del;
        String markBg, markFg;
        String listMarker;
        String kbdBg, kbdFg;
        String selectionBg, selectionFg;
        String accent;
    }

    private static Palette paletteFor(String theme) {
        Palette p = new Palette();
        switch (theme) {
            case "nord":
                p.bg="#2e3440"; p.text="#d8dee9";
                p.border="#3b4252"; p.hr="#4c566a";
                p.h1="#88c0d0"; p.h2="#81a1c1"; p.h3="#5e81ac";
                p.h4="#a3be8c"; p.h5="#ebcb8b"; p.h6="#b48ead";
                p.link="#88c0d0";
                p.codeBg="#3b4252"; p.codeFg="#ebcb8b";
                p.preBg="#292e39"; p.preBorder="#3b4252";
                p.blockquoteBorder="#5e81ac";
                p.blockquoteBg="rgba(94,129,172,0.08)";
                p.blockquoteFg="#9099a8";
                p.tableBorder="#3b4252"; p.thBg="#3b4252"; p.thFg="#eceff4";
                p.strong="#8fbcbb"; p.em="#d08770"; p.del="#4c566a";
                p.markBg="#ebcb8b"; p.markFg="#2e3440";
                p.listMarker="#88c0d0";
                p.kbdBg="#3b4252"; p.kbdFg="#d8dee9";
                p.selectionBg="#434c5e"; p.selectionFg="#eceff4";
                p.accent="#88c0d0"; break;
            case "gruvbox":
                p.bg="#282828"; p.text="#ebdbb2";
                p.border="#3c3836"; p.hr="#504945";
                p.h1="#fabd2f"; p.h2="#b8bb26"; p.h3="#83a598";
                p.h4="#d3869b"; p.h5="#fe8019"; p.h6="#8ec07c";
                p.link="#83a598";
                p.codeBg="#3c3836"; p.codeFg="#fabd2f";
                p.preBg="#1d2021"; p.preBorder="#3c3836";
                p.blockquoteBorder="#b8bb26";
                p.blockquoteBg="rgba(184,187,38,0.07)";
                p.blockquoteFg="#a89984";
                p.tableBorder="#3c3836"; p.thBg="#3c3836"; p.thFg="#ebdbb2";
                p.strong="#fe8019"; p.em="#d79921"; p.del="#928374";
                p.markBg="#fabd2f"; p.markFg="#282828";
                p.listMarker="#b8bb26";
                p.kbdBg="#3c3836"; p.kbdFg="#ebdbb2";
                p.selectionBg="#504945"; p.selectionFg="#ebdbb2";
                p.accent="#b8bb26"; break;
            case "things":
                p.bg="#fafafa"; p.text="#1c1c1e";
                p.border="#e5e5ea"; p.hr="#d1d1d6";
                p.h1="#1c1c1e"; p.h2="#007aff"; p.h3="#5856d6";
                p.h4="#af52de"; p.h5="#ff9500"; p.h6="#8e8e93";
                p.link="#007aff";
                p.codeBg="#f2f2f7"; p.codeFg="#c7254e";
                p.preBg="#f2f2f7"; p.preBorder="#e5e5ea";
                p.blockquoteBorder="#007aff";
                p.blockquoteBg="rgba(0,122,255,0.06)";
                p.blockquoteFg="#636366";
                p.tableBorder="#e5e5ea"; p.thBg="#f2f2f7"; p.thFg="#1c1c1e";
                p.strong="#1c1c1e"; p.em="#5856d6"; p.del="#c7c7cc";
                p.markBg="#ffeb3b"; p.markFg="#1c1c1e";
                p.listMarker="#007aff";
                p.kbdBg="#e5e5ea"; p.kbdFg="#1c1c1e";
                p.selectionBg="#b3d7ff"; p.selectionFg="#1c1c1e";
                p.accent="#007aff"; break;
            case "paper":
                p.bg="#f5f1e8"; p.text="#3c3c3c";
                p.border="#d4c7b0"; p.hr="#bfb296";
                p.h1="#5c3d1e"; p.h2="#8b4513"; p.h3="#a0522d";
                p.h4="#b8860b"; p.h5="#d2691e"; p.h6="#8a7a5a";
                p.link="#a0522d";
                p.codeBg="#e8dfd0"; p.codeFg="#8b4513";
                p.preBg="#ebe3d4"; p.preBorder="#d4c7b0";
                p.blockquoteBorder="#a0522d";
                p.blockquoteBg="rgba(160,82,45,0.05)";
                p.blockquoteFg="#8a7a6a";
                p.tableBorder="#d4c7b0"; p.thBg="#e8dfd0"; p.thFg="#3c3c3c";
                p.strong="#8b4513"; p.em="#a0522d"; p.del="#a89880";
                p.markBg="#ffe4b5"; p.markFg="#3c3c3c";
                p.listMarker="#a0522d";
                p.kbdBg="#e8dfd0"; p.kbdFg="#3c3c3c";
                p.selectionBg="#e8d4a8"; p.selectionFg="#3c3c3c";
                p.accent="#a0522d"; break;
            default:
                p.bg="#282a36"; p.text="#f8f8f2";
                p.border="#44475a"; p.hr="#6272a4";
                p.h1="#ff79c6"; p.h2="#bd93f9"; p.h3="#8be9fd";
                p.h4="#50fa7b"; p.h5="#ffb86c"; p.h6="#f1fa8c";
                p.link="#8be9fd";
                p.codeBg="#44475a"; p.codeFg="#f1fa8c";
                p.preBg="#21222c"; p.preBorder="#44475a";
                p.blockquoteBorder="#bd93f9";
                p.blockquoteBg="rgba(189,147,249,0.08)";
                p.blockquoteFg="#b0b0b0";
                p.tableBorder="#44475a"; p.thBg="#44475a"; p.thFg="#f8f8f2";
                p.strong="#ffb86c"; p.em="#f1fa8c"; p.del="#6272a4";
                p.markBg="#f1fa8c"; p.markFg="#282a36";
                p.listMarker="#bd93f9";
                p.kbdBg="#44475a"; p.kbdFg="#f8f8f2";
                p.selectionBg="#44475a"; p.selectionFg="#f8f8f2";
                p.accent="#bd93f9"; break;
        }
        return p;
    }

    private static final String EDIT_SCRIPT =
        "(function(){"
      + "var view=document.getElementById('hashiru-view');"
      + "var editor=document.getElementById('hashiru-editor');"
      + "var doneBtn=document.getElementById('hashiru-done');"
      + "var editBtn=document.getElementById('hashiru-edit-btn');"
      + "var sbTrack=document.getElementById('hashiru-sb-track');"
      + "var sbThumb=document.getElementById('hashiru-sb-thumb');"
      + "var srcEl=document.getElementById('hashiru-source');"
      + "if(!view||!editor||!doneBtn||!editBtn)return;"
      + "var current=srcEl?JSON.parse(srcEl.textContent):'';"
      + "var editing=false;"
      + "var editStartScrollY=0;"

      + "function fitEditor(){"
      + "  try{var vv=window.visualViewport;"
      + "    var h=vv?vv.height:window.innerHeight;"
      + "    editor.style.height=h+'px';"
      + "  }catch(e){}"
      + "}"
      + "if(window.visualViewport)"
      + "  window.visualViewport.addEventListener('resize',fitEditor);"
      + "window.addEventListener('resize',fitEditor);"

      + "function updateScrollbar(){"
      + "  if(!sbTrack||!sbThumb)return;"
      + "  if(editing){sbTrack.style.opacity='0';sbTrack.style.pointerEvents='none';return;}"
      + "  try{"
      + "    var doc=document.documentElement;"
      + "    var total=doc.scrollHeight;"
      + "    var vp=window.innerHeight;"
      + "    var max=total-vp;"
      + "    if(max<=4){sbTrack.style.opacity='0';sbTrack.style.pointerEvents='none';return;}"
      + "    sbTrack.style.opacity='1';sbTrack.style.pointerEvents='auto';"
      + "    var trackH=sbTrack.getBoundingClientRect().height;"
      + "    if(trackH<=0)return;"
      + "    var thumbH=Math.max(24,trackH*vp/total);"
      + "    var thumbTop=(window.scrollY/max)*(trackH-thumbH);"
      + "    sbThumb.style.height=thumbH+'px';"
      + "    sbThumb.style.top=thumbTop+'px';"
      + "  }catch(e){}"
      + "}"
      + "function scrollFromTrackY(cy){"
      + "  if(!sbTrack)return;"
      + "  try{"
      + "    var r=sbTrack.getBoundingClientRect();"
      + "    var rel=Math.max(0,Math.min(1,(cy-r.top)/r.height));"
      + "    var max=document.documentElement.scrollHeight-window.innerHeight;"
      + "    window.scrollTo(0,max*rel);"
      + "  }catch(e){}"
      + "}"
      + "if(sbTrack){"
      + "  var dragging=false;"
      + "  sbTrack.addEventListener('touchstart',function(e){"
      + "    dragging=true;scrollFromTrackY(e.touches[0].clientY);e.preventDefault();"
      + "  },{passive:false});"
      + "  sbTrack.addEventListener('touchmove',function(e){"
      + "    if(!dragging)return;scrollFromTrackY(e.touches[0].clientY);e.preventDefault();"
      + "  },{passive:false});"
      + "  sbTrack.addEventListener('touchend',function(e){dragging=false;e.preventDefault();},{passive:false});"
      + "  window.addEventListener('scroll',updateScrollbar,{passive:true});"
      + "  window.addEventListener('resize',updateScrollbar);"
      + "  window.addEventListener('load',updateScrollbar);"
      + "  setTimeout(updateScrollbar,50);"
      + "  setTimeout(updateScrollbar,200);"
      + "  setTimeout(updateScrollbar,500);"
      + "}"

      + "function enterEdit(){"
      + "  if(editing)return;"
      + "  editStartScrollY=window.scrollY||window.pageYOffset||0;"
      + "  var oldDocH=document.documentElement.scrollHeight;"
      + "  var oldVpH=window.innerHeight;"
      + "  var ratio=oldDocH>oldVpH?(editStartScrollY/(oldDocH-oldVpH)):0;"
      + "  editing=true;"
      + "  editor.value=current;"
      + "  view.style.display='none';"
      + "  editor.style.display='block';"
      + "  editBtn.style.display='none';"
      + "  doneBtn.style.display='flex';"
      + "  if(sbTrack){sbTrack.style.opacity='0';sbTrack.style.pointerEvents='none';}"
      + "  try{document.body.style.setProperty('padding-top','0','important');}catch(e){}"
      + "  fitEditor();"
      + "  setTimeout(function(){"
      + "    try{"
      + "      editor.focus();"
      + "      var eMax=Math.max(0,editor.scrollHeight-editor.clientHeight);"
      + "      editor.scrollTop=Math.max(0,Math.min(eMax,ratio*eMax));"
      + "    }catch(e){}"
      + "  },60);"
      + "}"

      + "function exitEdit(save){"
      + "  if(!editing)return;"
      + "  editing=false;"
      + "  var v=editor.value;"
      + "  editor.style.display='none';"
      + "  view.style.display='';"
      + "  editBtn.style.display='flex';"
      + "  doneBtn.style.display='none';"
      + "  try{document.body.style.removeProperty('padding-top');}catch(e){}"
      + "  if(save&&v!==current){"
      + "    current=v;"
      + "    try{__HashiruNative.saveAndReload(v,editStartScrollY);}catch(e){}"
      + "  }else{"
      + "    function restore(){try{window.scrollTo(0,editStartScrollY);}catch(e){}}"
      + "    requestAnimationFrame(function(){requestAnimationFrame(restore);});"
      + "    setTimeout(restore,60);"
      + "    setTimeout(restore,200);"
      + "    setTimeout(updateScrollbar,300);"
      + "  }"
      + "}"

      + "editBtn.addEventListener('click',function(e){e.preventDefault();enterEdit();});"
      + "doneBtn.addEventListener('click',function(e){e.preventDefault();exitEdit(true);});"
      + "})();";
}