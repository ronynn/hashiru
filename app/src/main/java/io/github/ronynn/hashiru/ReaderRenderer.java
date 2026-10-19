package io.github.ronynn.hashiru;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.util.Arrays;
import java.util.List;

public class ReaderRenderer {

    private static final List<Extension> EXTENSIONS = Arrays.asList(
            TablesExtension.create(),
            StrikethroughExtension.create()
    );

    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().extensions(EXTENSIONS).build();

    public static String render(String ext, String raw, ReaderSettings settings) {
        boolean isMd = ext.equals("md") || ext.equals("markdown")
                || (ext.equals("txt") && settings.readTxtAsMd);

        String body;
        if (isMd) {
            body = RENDERER.render(PARSER.parse(raw));
            body = body.replace("<table>", "<div class=\"table-wrap\"><table>")
                       .replace("</table>", "</table></div>");
        } else {
            body = "<pre class=\"plain\">" + escapeHtml(raw) + "</pre>";
        }
        return wrap(body, raw, settings);
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String wrap(String body, String rawSource, ReaderSettings s) {
        Palette p = paletteFor(s.theme);
        String css = themeCss(s, p);
        String sourceJson = jsonEscape(rawSource);

        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
             + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
             + "<style>" + css + "</style></head><body>"
             + "<div id=\"hashiru-view\">" + body + "</div>"
             + "<textarea id=\"hashiru-editor\" spellcheck=\"false\" autocorrect=\"off\" autocomplete=\"off\"></textarea>"
             + "<button id=\"hashiru-done\" type=\"button\">✓ Done</button>"
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

    private static String fontFamily(String f) {
        switch (f) {
            case "condensed": return "'Roboto Condensed','Arial Narrow',sans-serif-condensed,sans-serif";
            case "light":     return "'Roboto Light',sans-serif-light,sans-serif";
            case "medium":    return "'Roboto Medium',sans-serif-medium,sans-serif";
            case "serif":     return "'Noto Serif',Georgia,'Times New Roman',serif";
            case "mono":      return "'Droid Sans Mono',ui-monospace,Menlo,Consolas,monospace";
            case "cursive":   return "casual,cursive";
            default:          return "Roboto,-apple-system,'Segoe UI',sans-serif";
        }
    }

    private static String themeCss(ReaderSettings s, Palette p) {
        String ff = fontFamily(s.font);
        int size = s.fontSize;

        return "html{background:" + p.bg + ";color:" + p.text + ";overflow-x:hidden;}"
            + "body{max-width:48em;margin:0 auto;padding:.8em 1em 4em;"
            +   "font-family:" + ff + ";font-size:" + size + "px;line-height:1.5;"
            +   "-webkit-text-size-adjust:100%;-webkit-tap-highlight-color:transparent;"
            +   "overflow-wrap:break-word;touch-action:manipulation;}"
            + "h1,h2,h3,h4,h5,h6{line-height:1.3;margin:1em 0 .4em;font-weight:600;}"
            + "h1{color:" + p.h1 + ";font-size:1.9em;border-bottom:1px solid " + p.border + ";padding-bottom:.3em;}"
            + "h2{color:" + p.h2 + ";font-size:1.5em;border-bottom:1px solid " + p.border + ";padding-bottom:.25em;}"
            + "h3{color:" + p.h3 + ";font-size:1.25em;}"
            + "h4{color:" + p.h4 + ";font-size:1.1em;}"
            + "h5{color:" + p.h5 + ";font-size:1em;}"
            + "h6{color:" + p.h6 + ";font-size:1em;}"
            + "a{color:" + p.link + ";text-decoration:none;}a:hover{text-decoration:underline;}"
            + "code{background:" + p.codeBg + ";color:" + p.codeFg + ";padding:.15em .4em;border-radius:4px;"
            +   "font-family:ui-monospace,Menlo,Consolas,monospace;font-size:.92em;}"
            + "pre{background:" + p.preBg + ";padding:1em;border-radius:8px;overflow:auto;"
            +   "font-family:ui-monospace,Menlo,Consolas,monospace;font-size:.9em;line-height:1.5;"
            +   "border:1px solid " + p.preBorder + ";}"
            + "pre.plain{white-space:pre-wrap;word-wrap:break-word;}"
            + "pre code{background:none;padding:0;color:inherit;}"
            + "blockquote{border-left:3px solid " + p.blockquoteBorder + ";padding:.2em 1em;"
            +   "color:" + p.blockquoteFg + ";margin-left:0;background:rgba(128,128,128,0.06);}"
            + "ul,ol{padding-left:1.5em;}li{margin:.15em 0;}"
            + ".table-wrap{overflow-x:auto;max-width:100%;margin:0 0 1em;-webkit-overflow-scrolling:touch;}"
            + "table{border-collapse:collapse;min-width:100%;}"
            + "th,td{border:1px solid " + p.tableBorder + ";padding:.5em .8em;text-align:left;}"
            + "th{background:" + p.thBg + ";color:" + p.thFg + ";font-weight:600;}"
            + "hr{border:none;border-top:1px solid " + p.border + ";margin:1.5em 0;}"
            + "img{max-width:100%;height:auto;border-radius:6px;}"
            + "strong{color:" + p.strong + ";font-weight:600;}"
            + "em{color:" + p.em + ";}"
            + "del{color:" + p.del + ";}"
            + "p{margin:.6em 0;}"
            + "#hashiru-editor{position:fixed;top:0;left:0;right:0;bottom:0;"
            +   "width:100%;height:100%;background:" + p.bg + ";color:" + p.text + ";"
            +   "font-family:ui-monospace,Menlo,Consolas,monospace;font-size:15px;line-height:1.5;"
            +   "border:none;outline:none;padding:20px;padding-top:60px;box-sizing:border-box;"
            +   "resize:none;z-index:9500;display:none;}"
            + "#hashiru-done{position:fixed;bottom:20px;right:20px;z-index:10000;"
            +   "padding:12px 20px;border-radius:24px;border:none;"
            +   "background:" + p.accent + ";color:#fff;font-size:15px;font-weight:600;"
            +   "box-shadow:0 4px 12px rgba(0,0,0,.3);display:none;cursor:pointer;}";
    }

    private static class Palette {
        String bg, text, border;
        String h1, h2, h3, h4, h5, h6;
        String link, codeBg, codeFg, preBg, preBorder;
        String blockquoteBorder, blockquoteFg;
        String tableBorder, thBg, thFg;
        String strong, em, del, accent;
    }

    private static Palette paletteFor(String theme) {
        Palette p = new Palette();
        switch (theme) {
            case "light":
                p.bg="#ffffff"; p.text="#24292f"; p.border="#d0d7de";
                p.h1="#1f2328"; p.h2="#1f2328"; p.h3="#1f2328";
                p.h4="#1f2328"; p.h5="#1f2328"; p.h6="#1f2328";
                p.link="#0969da";
                p.codeBg="#f6f8fa"; p.codeFg="#24292f";
                p.preBg="#f6f8fa"; p.preBorder="#d0d7de";
                p.blockquoteBorder="#d0d7de"; p.blockquoteFg="#57606a";
                p.tableBorder="#d0d7de"; p.thBg="#f6f8fa"; p.thFg="#24292f";
                p.strong="#1f2328"; p.em="#24292f"; p.del="#8c959f";
                p.accent="#0969da"; break;
            case "sepia":
                p.bg="#f5ecd9"; p.text="#4a3f35"; p.border="#d4c7a8";
                p.h1="#a0522d"; p.h2="#8b4513"; p.h3="#6b4423";
                p.h4="#6b4423"; p.h5="#6b4423"; p.h6="#6b4423";
                p.link="#b8860b";
                p.codeBg="#e8dcc0"; p.codeFg="#6b4423";
                p.preBg="#ede4d0"; p.preBorder="#d4c7a8";
                p.blockquoteBorder="#a0522d"; p.blockquoteFg="#8a7a6a";
                p.tableBorder="#d4c7a8"; p.thBg="#e8dcc0"; p.thFg="#4a3f35";
                p.strong="#a0522d"; p.em="#8b4513"; p.del="#a89880";
                p.accent="#a0522d"; break;
            case "solarized_dark":
                p.bg="#002b36"; p.text="#839496"; p.border="#073642";
                p.h1="#cb4b16"; p.h2="#268bd2"; p.h3="#859900";
                p.h4="#2aa198"; p.h5="#b58900"; p.h6="#6c71c4";
                p.link="#268bd2";
                p.codeBg="#073642"; p.codeFg="#b58900";
                p.preBg="#00252e"; p.preBorder="#073642";
                p.blockquoteBorder="#586e75"; p.blockquoteFg="#93a1a1";
                p.tableBorder="#073642"; p.thBg="#073642"; p.thFg="#93a1a1";
                p.strong="#b58900"; p.em="#268bd2"; p.del="#586e75";
                p.accent="#268bd2"; break;
            case "gruvbox":
                p.bg="#282828"; p.text="#ebdbb2"; p.border="#3c3836";
                p.h1="#fabd2f"; p.h2="#b8bb26"; p.h3="#83a598";
                p.h4="#d3869b"; p.h5="#fe8019"; p.h6="#8ec07c";
                p.link="#83a598";
                p.codeBg="#3c3836"; p.codeFg="#fabd2f";
                p.preBg="#1d2021"; p.preBorder="#3c3836";
                p.blockquoteBorder="#b8bb26"; p.blockquoteFg="#a89984";
                p.tableBorder="#3c3836"; p.thBg="#3c3836"; p.thFg="#ebdbb2";
                p.strong="#fe8019"; p.em="#d79921"; p.del="#928374";
                p.accent="#b8bb26"; break;
            case "amoled":
                p.bg="#000000"; p.text="#e6e6e6"; p.border="#1f1f1f";
                p.h1="#ff79c6"; p.h2="#bd93f9"; p.h3="#8be9fd";
                p.h4="#50fa7b"; p.h5="#ffb86c"; p.h6="#f1fa8c";
                p.link="#8be9fd";
                p.codeBg="#1a1a1a"; p.codeFg="#f1fa8c";
                p.preBg="#0a0a0a"; p.preBorder="#1f1f1f";
                p.blockquoteBorder="#bd93f9"; p.blockquoteFg="#999999";
                p.tableBorder="#1f1f1f"; p.thBg="#1a1a1a"; p.thFg="#f0f0f0";
                p.strong="#ffb86c"; p.em="#f1fa8c"; p.del="#555555";
                p.accent="#bd93f9"; break;
            case "nord":
                p.bg="#2e3440"; p.text="#d8dee9"; p.border="#3b4252";
                p.h1="#88c0d0"; p.h2="#81a1c1"; p.h3="#5e81ac";
                p.h4="#a3be8c"; p.h5="#ebcb8b"; p.h6="#b48ead";
                p.link="#88c0d0";
                p.codeBg="#3b4252"; p.codeFg="#ebcb8b";
                p.preBg="#292e39"; p.preBorder="#3b4252";
                p.blockquoteBorder="#5e81ac"; p.blockquoteFg="#9099a8";
                p.tableBorder="#3b4252"; p.thBg="#3b4252"; p.thFg="#eceff4";
                p.strong="#8fbcbb"; p.em="#d08770"; p.del="#4c566a";
                p.accent="#88c0d0"; break;
            case "themeforest_light":
                p.bg="#ffffff"; p.text="#333333"; p.border="#e0e0e0";
                p.h1="#1a1a1a"; p.h2="#2c3e50"; p.h3="#34495e";
                p.h4="#34495e"; p.h5="#34495e"; p.h6="#34495e";
                p.link="#27ae60";
                p.codeBg="#f4f4f4"; p.codeFg="#c0392b";
                p.preBg="#f8f9fa"; p.preBorder="#e0e0e0";
                p.blockquoteBorder="#27ae60"; p.blockquoteFg="#666666";
                p.tableBorder="#e0e0e0"; p.thBg="#f8f9fa"; p.thFg="#333333";
                p.strong="#27ae60"; p.em="#555555"; p.del="#999999";
                p.accent="#27ae60"; break;
            default: // dark (Dracula)
                p.bg="#282a36"; p.text="#f8f8f2"; p.border="#44475a";
                p.h1="#ff79c6"; p.h2="#bd93f9"; p.h3="#8be9fd";
                p.h4="#50fa7b"; p.h5="#ffb86c"; p.h6="#f1fa8c";
                p.link="#8be9fd";
                p.codeBg="#44475a"; p.codeFg="#f1fa8c";
                p.preBg="#21222c"; p.preBorder="#44475a";
                p.blockquoteBorder="#bd93f9"; p.blockquoteFg="#b0b0b0";
                p.tableBorder="#44475a"; p.thBg="#44475a"; p.thFg="#f8f8f2";
                p.strong="#ffb86c"; p.em="#f1fa8c"; p.del="#6272a4";
                p.accent="#bd93f9"; break;
        }
        return p;
    }

    private static final String EDIT_SCRIPT =
        "(function(){"
      + "var editing=false,lastTap=0;"
      + "var view=document.getElementById('hashiru-view');"
      + "var editor=document.getElementById('hashiru-editor');"
      + "var doneBtn=document.getElementById('hashiru-done');"
      + "var srcEl=document.getElementById('hashiru-source');"
      + "if(!view||!editor||!doneBtn)return;"
      + "var current=srcEl?JSON.parse(srcEl.textContent):'';"
      + "function enterEdit(){"
      + "  if(editing)return;"
      + "  editing=true;editor.value=current;"
      + "  view.style.display='none';editor.style.display='block';"
      + "  doneBtn.style.display='block';window.scrollTo(0,0);"
      + "  setTimeout(function(){editor.focus();},50);"
      + "}"
      + "function exitEdit(save){"
      + "  if(!editing)return;editing=false;"
      + "  var v=editor.value;"
      + "  editor.style.display='none';view.style.display='';doneBtn.style.display='none';"
      + "  if(save&&v!==current){current=v;"
      + "    try{__HashiruNative.saveAndReload(v);}catch(e){}}"
      + "}"
      + "view.addEventListener('touchend',function(e){"
      + "  if(e.changedTouches.length!==1)return;"
      + "  var now=Date.now();"
      + "  if(now-lastTap<320){"
      + "    e.preventDefault();e.stopPropagation();"
      + "    enterEdit();lastTap=0;"
      + "  }else{lastTap=now;}"
      + "},{passive:false});"
      + "doneBtn.addEventListener('click',function(){exitEdit(true);});"
      + "})();";
}