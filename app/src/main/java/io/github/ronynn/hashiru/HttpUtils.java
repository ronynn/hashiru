package io.github.ronynn.hashiru;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/** Shared helpers used by both reader and music routes. */
public final class HttpUtils {

    private HttpUtils() {}

    public static NanoHTTPD.Response plain(NanoHTTPD.Response.Status s, String body) {
        return NanoHTTPD.newFixedLengthResponse(
                s, "text/plain; charset=utf-8", body == null ? "" : body);
    }

    public static String jsonStr(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    public static String headerIgnoreCase(NanoHTTPD.IHTTPSession session, String name) {
        if (session == null) return null;
        Map<String, String> headers = session.getHeaders();
        if (headers == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet())
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        return null;
    }

    public static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    public static byte[] readBytes(InputStream in) throws IOException {
        if (in == null) return null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }

    public static String guessMime(String name) {
        if (name == null) return "application/octet-stream";
        String n = name.toLowerCase();
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html; charset=utf-8";
        if (n.endsWith(".js") || n.endsWith(".mjs")) return "application/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".ico")) return "image/x-icon";
        if (n.endsWith(".woff")) return "font/woff";
        if (n.endsWith(".woff2")) return "font/woff2";
        if (n.endsWith(".ttf")) return "font/ttf";
        if (n.endsWith(".wasm")) return "application/wasm";
        if (n.endsWith(".xml")) return "application/xml";
        if (n.endsWith(".txt")) return "text/plain; charset=utf-8";
        if (n.endsWith(".csv")) return "text/csv; charset=utf-8";
        return "application/octet-stream";
    }

    /** Closes an associated resource (like a PFD) when the stream is closed. */
    public static final class AutoCloseInputStream extends FilterInputStream {
        private final java.io.Closeable resource;
        public AutoCloseInputStream(InputStream in, java.io.Closeable resource) {
            super(in);
            this.resource = resource;
        }
        @Override public void close() throws IOException {
            try { super.close(); }
            finally {
                if (resource != null) {
                    try { resource.close(); } catch (IOException ignored) {}
                }
            }
        }
    }

    /** Caps reads to a fixed byte count. */
    public static final class BoundedInputStream extends FilterInputStream {
        private long remaining;
        public BoundedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }
        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = super.read();
            if (b >= 0) remaining--;
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int toRead = (int) Math.min(len, remaining);
            int n = super.read(b, off, toRead);
            if (n > 0) remaining -= n;
            return n;
        }
    }
}