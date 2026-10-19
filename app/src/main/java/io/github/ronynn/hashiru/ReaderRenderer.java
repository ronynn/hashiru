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

    // escapeHtml(true): raw HTML in source is rendered as text → no <script>,
    //                   <iframe>, <style>, on* handlers, etc. can inject.
    // sanitizeUrls(true): strips javascript:/vbscript:/file:/data: (non-image)
    //                     schemes from <a href> and <img src>.
    // Together: user content cannot execute JavaScript. Only the app's own
    // inline EDIT_SCRIPT runs, and .html files bypass this renderer entirely.
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .escapeHtml(true)
            .sanitizeUrls(true)
            .build();

    public static String render(String ext, String raw,
                                ReaderSettings settings,
                                int sbhDp, boolean blurEnabled) {
        boolean isMd = ext.equals("md") || ext.equals("markdown") || ext.equals("txt");

        String body;
        if (isMd) {
            body = RENDERER.render(PARSER.parse(raw));
            body = body.replace("<table>", "<div class=\"table-wrap\"><table>")
                       .replace("</table>", "</table></div>");
        } else {
            // .org / .twee / anything else — escape by hand, wrap in <pre>
            body = "<pre class=\"plain\">" + escapeHtml(raw) + "</pre>";
        }
        return wrap(body, raw, settings, sbhDp, blurEnabled);
    }

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
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String wrap(String body, String rawSource, ReaderSettings s,
                               int sbhDp, boolean blurEnabled) {
        Palette p = paletteFor(s.theme);
        String css = themeCss(s, p, sbhDp, blurEnabled);
        String sourceJson = jsonEscape(rawSource);

        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
             + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">"
             + "<style>" + css + "</style></head><body>"
             + "<div id=\"hashiru-view\">" + body + "</div>"
             + "<textarea id=\"hashiru-editor\" spellcheck=\"false\" autocorrect=\"off\" autocomplete=\"off\"></textarea>"
             + "<button id=\"hashiru-done\" type=\"button\">✓</button>"
             + "<div id=\"hashiru-statusblur\"></div>"
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
            case "sans-condensed": return "font-family:sans-serif;font-stretch:75%;";
            case "serif":          return "font-family:serif;";
            case "mono":           return "font-family:monospace;";
            default:               return "font-family:sans-serif;";
        }
    }

    private static String themeCss(ReaderSettings s, Palette p, int sbhDp, boolean blurEnabled) {
        int size = s.fontSize;
        int topPad = sbhDp > 0 ? sbhDp + 4 : 8;
        int btnTop = sbhDp > 0 ? sbhDp + 12 : 12;
        int editorTop = sbhDp > 0 ? sbhDp + 68 : 68;

        boolean showBlur = blurEnabled && sbhDp > 0;
        int blurH = showBlur ? sbhDp + 6 : 0;

        String blurGradient = s.isLightTheme()
            ? "linear-gradient(to bottom,"
              + "rgba(255,255,255,0.45) 0%,"
              + "rgba(255,255,255,0.18) 50%,"
              + "rgba(255,255,255,0) 100%)"
            : "linear-gradient(to bottom,"
              + "rgba(0,0,0,0.14) 0%,"
              + "rgba(0,0,0,0.06) 50%,"
              + "rgba(0,0,0,0) 100%)";

        String blurRule = showBlur
            ? "#hashiru-statusblur{position:fixed;top:0;left:0;right:0;"
              + "height:" + blurH + "px;"
              + "-webkit-backdrop-filter:blur(8px);backdrop-filter:blur(8px);"
              + "background:" + blurGradient + ";"
              + "-webkit-mask-image:linear-gradient(to bottom,"
              +   "black 0%,black 40%,transparent 100%);"
              + "mask-image:linear-gradient(to bottom,"
              +   "black 0%,black 40%,transparent 100%);"
              + "z-index:9700;pointer-events:none;}"
            : "#hashiru-statusblur{display:none;}";

        return "html{background:" + p.bg + ";color:" + p.text + ";overflow-x:hidden;}"
            + "body{max-width:48em;margin:0 auto;"
            +   "padding:" + topPad + "px 1em 4em;"
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
            +   "font-family:ui-monospace,Menlo,Consolas,monospace;font-size:15px;line-height:1.5;"
            +   "border:none;outline:none;box-sizing:border-box;"
            +   "padding:20px;padding-top:" + editorTop + "px;padding-bottom:40vh;"
            +   "resize:none;display:none;overflow-y:auto;z-index:9500;}"
            + "#hashiru-done{position:fixed;top:" + btnTop + "px;right:16px;z-index:9600;"
            +   "width:44px;height:44px;padding:0;"
            +   "border-radius:50%;border:none;"
            +   "background:" + p.accent + ";color:#fff;"
            +   "font-family:sans-serif;font-size:22px;font-weight:700;"
            +   "line-height:44px;text-align:center;"
            +   "box-shadow:0 2px 8px rgba(0,0,0,.35);display:none;cursor:pointer;}"
            + blurRule;
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
      + "var editing=false;"
      + "var view=document.getElementById('hashiru-view');"
      + "var editor=document.getElementById('hashiru-editor');"
      + "var doneBtn=document.getElementById('hashiru-done');"
      + "var srcEl=document.getElementById('hashiru-source');"
      + "if(!view||!editor||!doneBtn)return;"
      + "var current=srcEl?JSON.parse(srcEl.textContent):'';"
      + "var tapTimes=[];"
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

      + "function sourcePosFromPoint(x,y){"
      + "  try{"
      + "    var range=null;"
      + "    if(document.caretRangeFromPoint)range=document.caretRangeFromPoint(x,y);"
      + "    else if(document.caretPositionFromPoint){"
      + "      var cp=document.caretPositionFromPoint(x,y);"
      + "      if(cp)range={startContainer:cp.offsetNode,startOffset:cp.offset};"
      + "    }"
      + "    if(!range||!range.startContainer)return 0;"
      + "    var node=range.startContainer;"
      + "    if(node.nodeType!==3){"
      + "      for(var i=0;i<node.childNodes.length;i++){"
      + "        if(node.childNodes[i].nodeType===3){node=node.childNodes[i];break;}"
      + "      }"
      + "    }"
      + "    if(node.nodeType!==3)return 0;"
      + "    var text=node.textContent||'';"
      + "    var off=Math.min(range.startOffset,text.length);"
      + "    var start=Math.max(0,off-20);"
      + "    var snippet=text.substring(start,Math.min(text.length,off+20));"
      + "    if(!snippet)return 0;"
      + "    var idx=current.indexOf(snippet);"
      + "    if(idx>=0)return idx+(off-start);"
      + "    var pre=text.substring(0,off);"
      + "    var words=pre.split(/\\s+/);"
      + "    var last=words[words.length-1];"
      + "    if(last&&last.length>=3){"
      + "      idx=current.indexOf(last);"
      + "      if(idx>=0)return idx+last.length;"
      + "    }"
      + "    return 0;"
      + "  }catch(e){return 0;}"
      + "}"

      + "function enterEditAt(x,y){"
      + "  if(editing)return;"
      + "  editStartScrollY=window.scrollY||window.pageYOffset||0;"
      + "  var pos=sourcePosFromPoint(x,y);"
      + "  editing=true;"
      + "  editor.value=current;"
      + "  view.style.display='none';"
      + "  editor.style.display='block';"
      + "  doneBtn.style.display='block';"
      + "  try{document.body.style.setProperty('padding-top','0','important');}catch(e){}"
      + "  fitEditor();"
      + "  setTimeout(function(){"
      + "    try{"
      + "      editor.focus();"
      + "      if(pos>=0&&pos<=editor.value.length){"
      + "        editor.setSelectionRange(pos,pos);"
      + "        var lineNum=(editor.value.substring(0,pos).match(/\\n/g)||[]).length;"
      + "        editor.scrollTop=Math.max(0,(lineNum-4)*22);"
      + "      }else{"
      + "        editor.setSelectionRange(0,0);"
      + "        editor.scrollTop=0;"
      + "      }"
      + "    }catch(e){}"
      + "  },60);"
      + "}"

      + "function exitEdit(save){"
      + "  if(!editing)return;editing=false;"
      + "  var v=editor.value;"
      + "  editor.style.display='none';"
      + "  view.style.display='';"
      + "  doneBtn.style.display='none';"
      + "  try{document.body.style.removeProperty('padding-top');}catch(e){}"

      + "  if(save&&v!==current){"
      + "    current=v;"
      + "    try{__HashiruNative.saveAndReload(v,editStartScrollY);}catch(e){}"
      + "  }else{"
      + "    function restore(){"
      + "      try{window.scrollTo(0,editStartScrollY);}catch(e){}"
      + "    }"
      + "    requestAnimationFrame(function(){"
      + "      requestAnimationFrame(restore);"
      + "    });"
      + "    setTimeout(restore,60);"
      + "    setTimeout(restore,200);"
      + "  }"
      + "}"

      + "view.addEventListener('touchend',function(e){"
      + "  if(e.changedTouches.length!==1)return;"
      + "  var t=e.changedTouches[0];"
      + "  var now=Date.now();"
      + "  tapTimes.push(now);"
      + "  while(tapTimes.length&&now-tapTimes[0]>500)tapTimes.shift();"
      + "  if(tapTimes.length>=3){"
      + "    tapTimes=[];"
      + "    e.preventDefault();e.stopPropagation();"
      + "    try{window.getSelection().removeAllRanges();}catch(x){}"
      + "    enterEditAt(t.clientX,t.clientY);"
      + "  }"
      + "},{passive:false});"

      + "doneBtn.addEventListener('click',function(){exitEdit(true);});"
      + "})();";
}