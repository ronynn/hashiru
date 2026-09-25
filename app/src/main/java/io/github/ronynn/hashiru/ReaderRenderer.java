package io.github.ronynn.hashiru;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

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
  
    private static final Parser PARSER = Parser.builder().build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().build();

    public static String render(String ext, String raw) {
        String body;
        if (ext.equals("md") || ext.equals("markdown")) {
            body = RENDERER.render(PARSER.parse(raw));
        } else {
            // txt, org, twee — plain monospace for now.
            // Swap this branch for a real org/twee parser later if you want.
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

    private static final String CSS =
        "html{background:#121212;color:#e0e0e0;}"
      + "body{max-width:48em;margin:0 auto;padding:1.2em 1em 4em;"
      +   "font-family:-apple-system,'Segoe UI',Roboto,sans-serif;"
      +   "font-size:17px;line-height:1.65;-webkit-text-size-adjust:100%;"
      +   "-webkit-tap-highlight-color:transparent;}"
      + "h1,h2,h3,h4,h5,h6{color:#fff;line-height:1.3;margin:1.6em 0 .6em;font-weight:600;}"
      + "h1{font-size:1.9em;border-bottom:1px solid #2a2a2a;padding-bottom:.3em;}"
      + "h2{font-size:1.5em;border-bottom:1px solid #222;padding-bottom:.25em;}"
      + "h3{font-size:1.25em;}"
      + "h4{font-size:1.1em;}"
      + "p,ul,ol,blockquote,pre,table{margin:0 0 1em;}"
      + "a{color:#7ab7ff;text-decoration:none;}"
      + "a:hover{text-decoration:underline;}"
      + "code{background:#1e1e1e;padding:.15em .4em;border-radius:4px;"
      +   "font-family:ui-monospace,Menlo,Consolas,monospace;font-size:.92em;}"
      + "pre{background:#1a1a1a;padding:1em;border-radius:8px;overflow:auto;"
      +   "font-family:ui-monospace,Menlo,Consolas,monospace;font-size:.9em;line-height:1.5;}"
      + "pre.plain{white-space:pre-wrap;word-wrap:break-word;}"
      + "pre code{background:none;padding:0;}"
      + "blockquote{border-left:3px solid #444;padding:.2em 1em;color:#b0b0b0;margin-left:0;}"
      + "ul,ol{padding-left:1.5em;}"
      + "li{margin:.25em 0;}"
      + "table{border-collapse:collapse;width:100%;}"
      + "th,td{border:1px solid #333;padding:.5em .8em;text-align:left;}"
      + "th{background:#1e1e1e;font-weight:600;}"
      + "hr{border:none;border-top:1px solid #333;margin:2em 0;}"
      + "img{max-width:100%;height:auto;border-radius:6px;}";
}