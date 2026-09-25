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

    private static final Parser PARSER = Parser.builder()
            .extensions(EXTENSIONS)
            .build();

    private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .build();

    public static String render(String ext, String raw) {
        String body;
        if (ext.equals("md") || ext.equals("markdown")) {
            body = RENDERER.render(PARSER.parse(raw));
            // Wrap tables so they scroll horizontally instead of widening the page
            body = body.replace("<table>", "<div class=\"table-wrap\"><table>")
                       .replace("</table>", "</table></div>");
        } else {
            body = "<pre class=\"plain\">" + escapeHtml(raw) + "</pre>";
        }
        return wrap(body);
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String wrap(String body) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
             + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
             + "<style>" + CSS + "</style></head><body>" + body + "</body></html>";
    }

    // Dracula palette — https://draculatheme.com
    private static final String CSS =
        "html{background:#282a36;color:#f8f8f2;overflow-x:hidden;}"
      + "body{max-width:48em;margin:0 auto;padding:1.2em 1em 4em;"
      +   "font-family:-apple-system,'Segoe UI',Roboto,sans-serif;"
      +   "font-size:17px;line-height:1.65;-webkit-text-size-adjust:100%;"
      +   "-webkit-tap-highlight-color:transparent;overflow-wrap:break-word;}"
      + "h1,h2,h3,h4,h5,h6{line-height:1.3;margin:1.6em 0 .6em;font-weight:600;}"
      + "h1{color:#ff79c6;font-size:1.9em;border-bottom:1px solid #44475a;padding-bottom:.3em;}"
      + "h2{color:#bd93f9;font-size:1.5em;border-bottom:1px solid #44475a;padding-bottom:.25em;}"
      + "h3{color:#8be9fd;font-size:1.25em;}"
      + "h4{color:#50fa7b;font-size:1.1em;}"
      + "h5{color:#ffb86c;font-size:1em;}"
      + "h6{color:#f1fa8c;font-size:1em;}"
      + "a{color:#8be9fd;text-decoration:none;}"
      + "a:hover{text-decoration:underline;}"
      + "code{background:#44475a;color:#f1fa8c;padding:.15em .4em;border-radius:4px;"
      +   "font-family:ui-monospace,Menlo,Consolas,monospace;font-size:.92em;}"
      + "pre{background:#21222c;padding:1em;border-radius:8px;overflow:auto;"
      +   "font-family:ui-monospace,Menlo,Consolas,monospace;font-size:.9em;line-height:1.5;"
      +   "border:1px solid #44475a;}"
      + "pre.plain{white-space:pre-wrap;word-wrap:break-word;}"
      + "pre code{background:none;padding:0;color:inherit;}"
      + "blockquote{border-left:3px solid #bd93f9;padding:.2em 1em;color:#b0b0b0;"
      +   "margin-left:0;background:rgba(189,147,249,0.06);}"
      + "ul,ol{padding-left:1.5em;}"
      + "li{margin:.25em 0;}"
      + ".table-wrap{overflow-x:auto;max-width:100%;margin:0 0 1em;"
      +   "-webkit-overflow-scrolling:touch;}"
      + "table{border-collapse:collapse;min-width:100%;}"
      + "th,td{border:1px solid #44475a;padding:.5em .8em;text-align:left;}"
      + "th{background:#44475a;color:#f8f8f2;font-weight:600;}"
      + "hr{border:none;border-top:1px solid #44475a;margin:2em 0;}"
      + "img{max-width:100%;height:auto;border-radius:6px;}"
      + "strong{color:#ffb86c;font-weight:600;}"
      + "em{color:#f1fa8c;}"
      + "del{color:#6272a4;}";
}