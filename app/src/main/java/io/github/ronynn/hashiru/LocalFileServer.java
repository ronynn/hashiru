package io.github.ronynn.hashiru;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import java.io.IOException;

import fi.iki.elonen.NanoHTTPD;

/**
 * Router / entry point for the local HTTP server.
 * Feature areas live in dedicated handlers:
 *   - ReaderRoutes: SAF-backed reader (markdown, html, csv, etc.)
 *   - MusicRoutes:  MediaStore-backed music library, streaming, covers, tags
 *
 * Public API kept stable for WebViewActivity & MusicActivity:
 *   - acquire(treeUri, cr, ctx) — get/create the singleton
 *   - embedCoverInMp3(...)      — writes APIC into an MP3 (delegates)
 *   - getListeningPort()        — inherited from NanoHTTPD
 */
public class LocalFileServer extends NanoHTTPD {

    private static final int PORT = 17463;

    private static LocalFileServer instance;

    private MusicRoutes music;
    private ReaderRoutes reader;

    private LocalFileServer() {
        super("127.0.0.1", PORT);
    }

    public static synchronized LocalFileServer acquire(Uri treeUri,
                                                       ContentResolver cr,
                                                       Context ctx) throws IOException {
        if (instance == null) {
            instance = new LocalFileServer();
            instance.start(SOCKET_READ_TIMEOUT, false);
        }
        Context app = ctx.getApplicationContext();
        instance.music = new MusicRoutes(cr, app);
        if (treeUri != null) {
            instance.reader = new ReaderRoutes(treeUri, cr, app);
        }
        return instance;
    }

    public String origin() {
        return "http://127.0.0.1:" + PORT;
    }

    /** Public API kept for MusicActivity — delegates to Id3TagWriter. */
    public static void embedCoverInMp3(Context ctx, Uri audioUri,
                                       byte[] coverData, String coverMime) {
        Id3TagWriter.embedCoverInMp3(ctx, audioUri, coverData, coverMime);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if (uri.startsWith("/")) uri = uri.substring(1);

        for (String seg : uri.split("/")) {
            if ("..".equals(seg))
                return HttpUtils.plain(Response.Status.FORBIDDEN, "Forbidden");
        }

        if (uri.equals("_music") || uri.startsWith("_music/")) {
            if (music == null)
                return HttpUtils.plain(Response.Status.SERVICE_UNAVAILABLE, "Music not ready");
            return music.serve(uri, session);
        }

        if (reader == null)
            return HttpUtils.plain(Response.Status.SERVICE_UNAVAILABLE, "No tree selected");
        return reader.serve(uri, session);
    }
}