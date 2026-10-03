package quest.server.files;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.http.HttpStatus;
import quest.server.config.ApiException;

/**
 * B5b (review of #204): a chat photo is stored without what the camera wrote about it — the GPS position of the
 * parent's home above all, and the make, model, times and thumbnails beside it. The app strips it too, but an old build
 * and the dashboard do not, so the server never relies on that.
 *
 * <p><strong>Lossless, by removal.</strong> Nothing is decoded or re-encoded: the metadata containers are taken out of
 * the file and the pixels are the bytes the sender sent.
 * <ul>
 *   <li><strong>JPEG</strong> — every APP1 (EXIF and XMP), APP13 (IPTC / Photoshop), APP3–APP12 and APP15 segment, every
 *       comment, and anything after the main image's end (a phone's appended depth map or second picture, which carries
 *       an EXIF of its own) go; APP0 (JFIF), APP2's colour profile and APP14 (Adobe colour transform) stay, because
 *       they decide how the pixels look. <strong>Upright without a re-encode:</strong> when the photo was stored on its
 *       side, a new APP1 is written that holds the EXIF orientation and nothing else — every browser and image library
 *       turns the picture by it, exactly as before, and it says nothing about who took it or where.</li>
 *   <li><strong>PNG</strong> — the `eXIf`, `tEXt`, `zTXt`, `iTXt` and `tIME` chunks go; everything after `IEND` too.</li>
 *   <li><strong>WebP</strong> — the `EXIF` and `XMP ` chunks go and the extended header's two flags for them are cleared.</li>
 * </ul>
 * A file whose containers cannot be walked is refused rather than stored as it came: storing it would be storing
 * whatever it carries.
 */
final class ImageMetadata {
    private ImageMetadata() {}

    /** The bytes without their metadata; {@code mime} is the sniffed type. A PDF or anything else is answered as it is. */
    static byte[] strip(byte[] b, String mime) {
        try {
            return switch (mime) {
                case "image/jpeg" -> jpeg(b);
                case "image/png" -> png(b);
                case "image/webp" -> webp(b);
                default -> b;
            };
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_request", "That image could not be read.");
        }
    }

    // ---------------------------------------------------------------- JPEG

    private static byte[] jpeg(byte[] b) {
        int orientation = ImageInfo.orientation(b);
        var out = new ByteArrayOutputStream(b.length);
        out.write(0xFF); out.write(0xD8);
        int i = 2; boolean oriented = orientation == 1, entropy = false;
        while (i < b.length) {
            if (entropy) {                                      // scan data: only a real marker ends it
                if ((b[i] & 0xFF) != 0xFF || i + 1 >= b.length) { out.write(b[i++]); continue; }
                int next = b[i + 1] & 0xFF;
                if (next == 0x00 || (next >= 0xD0 && next <= 0xD7) || next == 0xFF) { out.write(b[i++]); continue; }
                entropy = false;
            }
            if ((b[i] & 0xFF) != 0xFF || i + 1 >= b.length) throw new IllegalArgumentException("marker expected at " + i);
            int marker = b[i + 1] & 0xFF;
            if (marker == 0xFF) { i++; continue; }                                   // fill byte
            if (marker == 0xD9) { out.write(0xFF); out.write(0xD9); return out.toByteArray(); }   // EOI: nothing after it is kept
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { out.write(b, i, 2); i += 2; continue; }   // no length
            if (i + 3 >= b.length) throw new IllegalArgumentException("truncated segment");
            int len = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            if (len < 2 || i + 2 + len > b.length) throw new IllegalArgumentException("segment overruns the file");
            if (!oriented && marker != 0xE0) { out.writeBytes(orientationSegment(orientation)); oriented = true; }
            if (keep(marker, b, i)) out.write(b, i, 2 + len);
            i += 2 + len;
            if (marker == 0xDA) entropy = true;                                      // SOS: its scan data follows
        }
        throw new IllegalArgumentException("no end of image");
    }

    /** Which segments stay: everything but the metadata containers named in the class comment. */
    private static boolean keep(int marker, byte[] b, int i) {
        if (marker == 0xFE) return false;                                             // COM
        if (marker == 0xE2) return startsWith(b, i + 4, "ICC_PROFILE\0");            // APP2: the colour profile, not MPF
        if (marker == 0xE0 || marker == 0xEE) return true;                            // APP0 JFIF, APP14 Adobe
        return marker < 0xE0 || marker > 0xEF;                                         // any other APPn goes
    }

    /** An APP1 `Exif` with one IFD0 entry — Orientation — and nothing else. */
    private static byte[] orientationSegment(int orientation) {
        return new byte[] {(byte) 0xFF, (byte) 0xE1, 0, 34, 'E', 'x', 'i', 'f', 0, 0,
                'M', 'M', 0, 0x2A, 0, 0, 0, 8,
                0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0,
                0, 0, 0, 0};
    }

    // ---------------------------------------------------------------- PNG

    private static final Set<String> PNG_METADATA = Set.of("eXIf", "tEXt", "zTXt", "iTXt", "tIME");

    private static byte[] png(byte[] b) {
        var out = new ByteArrayOutputStream(b.length);
        out.write(b, 0, 8);
        int i = 8;
        while (i + 12 <= b.length) {
            long len = ((long) (b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            if (len > b.length - i - 12L) throw new IllegalArgumentException("chunk overruns the file");
            String type = new String(b, i + 4, 4, StandardCharsets.US_ASCII);
            int total = 12 + (int) len;
            if (!PNG_METADATA.contains(type)) out.write(b, i, total);
            i += total;
            if ("IEND".equals(type)) return out.toByteArray();
        }
        throw new IllegalArgumentException("no IEND");
    }

    // ---------------------------------------------------------------- WebP

    private static byte[] webp(byte[] b) {
        var out = new ByteArrayOutputStream(b.length);
        out.write(b, 0, 12);
        int i = 12;
        while (i + 8 <= b.length) {
            long len = (b[i + 4] & 0xFF) | ((b[i + 5] & 0xFF) << 8) | ((b[i + 6] & 0xFF) << 16) | ((long) (b[i + 7] & 0xFF) << 24);
            long padded = len + (len & 1);
            if (padded > b.length - i - 8L) throw new IllegalArgumentException("chunk overruns the file");
            String type = new String(b, i, 4, StandardCharsets.US_ASCII);
            if (!"EXIF".equals(type) && !"XMP ".equals(type)) {
                int at = out.size();
                out.write(b, i, 8 + (int) padded);
                if ("VP8X".equals(type)) {                                              // clear the EXIF (0x08) and XMP (0x04) flags
                    byte[] sofar = out.toByteArray();
                    sofar[at + 8] = (byte) (sofar[at + 8] & ~0x0C);
                    out.reset(); out.writeBytes(sofar);
                }
            }
            i += 8 + (int) padded;
        }
        byte[] result = out.toByteArray();
        int riff = result.length - 8;
        result[4] = (byte) riff; result[5] = (byte) (riff >> 8); result[6] = (byte) (riff >> 16); result[7] = (byte) (riff >> 24);
        return result;
    }

    private static boolean startsWith(byte[] b, int at, String prefix) {
        byte[] p = prefix.getBytes(StandardCharsets.US_ASCII);
        if (at + p.length > b.length) return false;
        for (int k = 0; k < p.length; k++) if (b[at + k] != p[k]) return false;
        return true;
    }
}
