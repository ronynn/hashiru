package io.github.ronynn.hashiru;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Writes ID3v2.4 text frames + APIC cover frames into MP3 files. */
final class Id3TagWriter {

    private static final String TAG = "Id3TagWriter";

    private Id3TagWriter() {}

    /** Public API — writes APIC into an MP3, preserving existing text frames. */
    static void embedCoverInMp3(Context ctx, Uri audioUri,
                                byte[] coverData, String coverMime) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            byte[] fileBytes = HttpUtils.readBytes(cr.openInputStream(audioUri));
            if (fileBytes == null) return;

            int audioStart = findId3TagEnd(fileBytes);
            byte[] existingCover = findApicData(fileBytes, 10, audioStart);
            byte[] finalCover = coverData != null ? coverData : existingCover;

            String existingTitle  = findTextFrame(fileBytes, 10, audioStart, "TIT2");
            String existingArtist = findTextFrame(fileBytes, 10, audioStart, "TPE1");
            String existingAlbum  = findTextFrame(fileBytes, 10, audioStart, "TALB");
            String existingTrack  = findTextFrame(fileBytes, 10, audioStart, "TRCK");

            String mime = coverMime != null ? coverMime : "image/jpeg";
            byte[] newTag = buildId3Tag(existingTitle, existingArtist, existingAlbum,
                    existingTrack, finalCover, mime);

            try (OutputStream out = cr.openOutputStream(audioUri, "rwt")) {
                if (out == null) return;
                out.write(newTag);
                out.write(fileBytes, audioStart, fileBytes.length - audioStart);
            }
        } catch (Exception e) {
            Log.w(TAG, "embedCoverInMp3 failed", e);
        }
    }

    /** Writes text frames (TIT2/TPE1/TALB/TRCK), preserving the existing APIC. */
    static void writeTextFrames(ContentResolver cr, Uri audioUri,
                                String title, String artist,
                                String album, String track) throws IOException {
        byte[] fileBytes = HttpUtils.readBytes(cr.openInputStream(audioUri));
        if (fileBytes == null) throw new IOException("read failed");

        int audioStart = findId3TagEnd(fileBytes);
        byte[] existingCover = findApicData(fileBytes, 10, audioStart);
        byte[] newTag = buildId3Tag(title, artist, album, track,
                existingCover, "image/jpeg");

        try (OutputStream out = cr.openOutputStream(audioUri, "rwt")) {
            if (out == null) throw new IOException("write failed");
            out.write(newTag);
            out.write(fileBytes, audioStart, fileBytes.length - audioStart);
        }
    }

    // ---------- Internals ----------

    private static int findId3TagEnd(byte[] data) {
        if (data.length < 10) return 0;
        if (data[0] != 'I' || data[1] != 'D' || data[2] != '3') return 0;
        int size = ((data[6] & 0x7f) << 21) | ((data[7] & 0x7f) << 14)
                 | ((data[8] & 0x7f) << 7)  | (data[9] & 0x7f);
        int end = 10 + size;
        if ((data[5] & 0x10) != 0) end += 10;
        return Math.min(end, data.length);
    }

    private static String findTextFrame(byte[] data, int start, int end, String frameId) {
        int pos = start;
        while (pos + 10 <= end) {
            String id = new String(data, pos, 4, StandardCharsets.ISO_8859_1);
            if (id.equals("\0\0\0\0")) break;
            int size = ((data[pos+4] & 0x7f) << 21) | ((data[pos+5] & 0x7f) << 14)
                     | ((data[pos+6] & 0x7f) << 7)  | (data[pos+7] & 0x7f);
            if (id.equals(frameId) && size > 1) {
                int enc = data[pos + 10];
                if (enc == 3)
                    return new String(data, pos + 11, size - 1, StandardCharsets.UTF_8);
                return new String(data, pos + 11, size - 1, StandardCharsets.ISO_8859_1);
            }
            pos += 10 + size;
        }
        return null;
    }

    private static byte[] findApicData(byte[] data, int start, int end) {
        int pos = start;
        while (pos + 10 <= end) {
            String id = new String(data, pos, 4, StandardCharsets.ISO_8859_1);
            if (id.equals("\0\0\0\0")) break;
            int size = ((data[pos+4] & 0x7f) << 21) | ((data[pos+5] & 0x7f) << 14)
                     | ((data[pos+6] & 0x7f) << 7)  | (data[pos+7] & 0x7f);
            if (id.equals("APIC")) {
                int fpos = pos + 10;
                int fEnd = fpos + size;
                if (fEnd > data.length) return null;
                int enc = data[fpos];
                int p = fpos + 1;
                while (p < fEnd && data[p] != 0) p++;
                p++;
                p++;
                if (enc == 1 || enc == 2) {
                    if (p + 1 < fEnd) p += 2;
                    while (p + 1 < fEnd && !(data[p] == 0 && data[p+1] == 0)) p += 2;
                    p += 2;
                } else {
                    while (p < fEnd && data[p] != 0) p++;
                    p++;
                }
                int len = fEnd - p;
                if (len > 0) {
                    byte[] img = new byte[len];
                    System.arraycopy(data, p, img, 0, len);
                    return img;
                }
                return null;
            }
            pos += 10 + size;
        }
        return null;
    }

    private static byte[] buildId3Tag(String title, String artist, String album,
                                       String track, byte[] cover, String coverMime)
            throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(new byte[]{'I','D','3', 4, 0, 0, 0,0,0,0});
        if (title != null && !title.isEmpty())   writeTextFrame(buf, "TIT2", title);
        if (artist != null && !artist.isEmpty()) writeTextFrame(buf, "TPE1", artist);
        if (album != null && !album.isEmpty())   writeTextFrame(buf, "TALB", album);
        if (track != null && !track.isEmpty())   writeTextFrame(buf, "TRCK", track);
        if (cover != null && cover.length > 0)
            writeApicFrame(buf, cover, coverMime != null ? coverMime : "image/jpeg");
        byte[] tag = buf.toByteArray();
        int size = tag.length - 10;
        tag[6] = (byte)((size >> 21) & 0x7f);
        tag[7] = (byte)((size >> 14) & 0x7f);
        tag[8] = (byte)((size >> 7)  & 0x7f);
        tag[9] = (byte)( size        & 0x7f);
        return tag;
    }

    private static void writeTextFrame(ByteArrayOutputStream buf, String id, String text)
            throws IOException {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        int size = 1 + textBytes.length;
        buf.write(id.getBytes(StandardCharsets.ISO_8859_1));
        buf.write((size >> 21) & 0x7f);
        buf.write((size >> 14) & 0x7f);
        buf.write((size >> 7)  & 0x7f);
        buf.write( size        & 0x7f);
        buf.write(0); buf.write(0);
        buf.write(3);
        buf.write(textBytes);
    }

    private static void writeApicFrame(ByteArrayOutputStream buf, byte[] image, String mime)
            throws IOException {
        byte[] mimeBytes = mime.getBytes(StandardCharsets.ISO_8859_1);
        int size = 1 + mimeBytes.length + 1 + 1 + 1 + image.length;
        buf.write("APIC".getBytes(StandardCharsets.ISO_8859_1));
        buf.write((size >> 21) & 0x7f);
        buf.write((size >> 14) & 0x7f);
        buf.write((size >> 7)  & 0x7f);
        buf.write( size        & 0x7f);
        buf.write(0); buf.write(0);
        buf.write(0);
        buf.write(mimeBytes);
        buf.write(0);
        buf.write(3);
        buf.write(0);
        buf.write(image);
    }
}