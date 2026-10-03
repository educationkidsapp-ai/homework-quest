package quest.server.files;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * B5: what a chat bubble needs to know about an image before its bytes arrive — its size as the viewer will see it —
 * and the smaller copy `GET /media/attachments/{id}?w=` answers. Read from the bytes the server already holds, never
 * from anything the client said.
 *
 * <p><strong>As the viewer sees it.</strong> A phone photo is usually stored on its side with an EXIF orientation
 * that tells the viewer to turn it, and every browser and image library honours that tag. `javax.imageio` does not, so
 * the stored width and height are swapped for orientations 5–8 and a downscaled copy is turned the same way; without
 * that, a portrait photo would be laid out — and shrunk — as a landscape one.
 *
 * <p>WebP is measured from its own header (no `ImageIO` reader ships with the JDK) and is never downscaled: the
 * original is answered instead, which is what a caller that asks for a size the server cannot make gets anyway.
 */
public final class ImageInfo {
    private ImageInfo() {}

    /**
     * The widths a downscaled copy is made at. `?w=` is rounded up to one of them (and down to the last), so each image
     * is decoded at most three times in its life — the copy is stored beside the original ({@link #copyPath}) and every
     * later request for that width is a read.
     */
    static final int[] WIDTHS = {320, 640, 1280};
    /** B5 review: the most an upload may be — on a side, and in all. A larger one is refused before it is stored. */
    static final int MAX_SIDE = 8192;
    static final long MAX_PIXELS = 40_000_000L;

    /** The stored width {@code asked} is answered with. */
    static int width(int asked) {
        for (int w : WIDTHS) if (asked <= w) return w;
        return WIDTHS[WIDTHS.length - 1];
    }

    /** Where the copy of {@code original} at {@code width} is kept: beside it, so deleting one deletes the family. */
    static String copyPath(String original, int width) { return original + ".w" + width + ".jpg"; }

    /** Every copy an original may have, for whoever deletes it. */
    public static java.util.List<String> copyPaths(String original) {
        return java.util.Arrays.stream(WIDTHS).mapToObj(w -> copyPath(original, w)).toList();
    }

    /** Whether an image of this size is past what an upload may be. */
    static boolean tooLarge(Size s) { return s.width() > MAX_SIDE || s.height() > MAX_SIDE || (long) s.width() * s.height() > MAX_PIXELS; }

    record Size(int width, int height) {}

    /** The displayed size, or null when the bytes do not say (an unreadable header, a PDF). */
    static Size size(byte[] b, String mime) {
        if ("image/webp".equals(mime)) return webp(b);
        if (!mime.startsWith("image/")) return null;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(b))) {
            var readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            ImageReader r = readers.next();
            try { r.setInput(in, true, true); return turned(r.getWidth(0), r.getHeight(0), orientation(b)); } finally { r.dispose(); }
        } catch (IOException | RuntimeException e) { return null; }
    }

    /**
     * A JPEG at most {@code width} pixels wide, turned upright, or null when the original should be answered instead:
     * a WebP or a PDF, an image already that narrow, one too large to decode, or bytes `ImageIO` cannot read (a CMYK
     * JPEG). The decode is subsampled to about twice the target, so a 12-megapixel photo never sits in memory whole.
     */
    static byte[] downscale(byte[] b, String mime, int width) {
        if (!"image/jpeg".equals(mime) && !"image/png".equals(mime)) return null;
        int o = orientation(b);
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(b))) {
            var readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            ImageReader r = readers.next();
            BufferedImage src;
            try {
                r.setInput(in, true, true);
                int w = r.getWidth(0), h = r.getHeight(0);
                var shown = turned(w, h, o);
                if (shown.width() <= width || tooLarge(new Size(w, h))) return null;           // an upload older than the limit
                var param = r.getDefaultReadParam();
                int step = Math.max(1, shown.width() / (width * 2));
                param.setSourceSubsampling(step, step, 0, 0);
                src = r.read(0, param);
            } finally { r.dispose(); }
            return jpeg(src, o, width);
        } catch (IOException | RuntimeException e) { return null; }
    }

    private static byte[] jpeg(BufferedImage src, int orientation, int width) throws IOException {
        boolean sideways = orientation >= 5;
        int w = src.getWidth(), h = src.getHeight(), shownW = sideways ? h : w, shownH = sideways ? w : h;
        double scale = (double) width / shownW;
        var out = new BufferedImage(width, Math.max(1, (int) Math.round(shownH * scale)), BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(Color.WHITE); g.fillRect(0, 0, out.getWidth(), out.getHeight());   // a transparent PNG on white, not black
            g.scale(scale, scale);
            switch (orientation) {
                case 3, 4 -> { g.translate(w, h); g.rotate(Math.PI); }
                case 5, 6 -> { g.translate(h, 0); g.rotate(Math.PI / 2); }
                case 7, 8 -> { g.translate(0, w); g.rotate(-Math.PI / 2); }
                default -> { }
            }
            g.drawImage(src, 0, 0, null);
        } finally { g.dispose(); }
        var bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(out, "jpg", bytes)) return null;
        return bytes.toByteArray();
    }

    private static Size turned(int w, int h, int orientation) { return orientation >= 5 ? new Size(h, w) : new Size(w, h); }

    /** The EXIF orientation (1–8) of a JPEG, 1 when there is none: APP1 `Exif`, then the TIFF IFD0 tag 0x0112. */
    static int orientation(byte[] b) {
        if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) return 1;
        int i = 2;
        while (i + 4 <= b.length && (b[i] & 0xFF) == 0xFF) {
            int marker = b[i + 1] & 0xFF, len = u16(b, i + 2, false);
            if (marker == 0xDA || marker == 0xD9 || len < 2) return 1;                // image data: no more metadata
            int end = Math.min(b.length, i + 2 + len);
            if (marker == 0xE1 && i + 10 <= end && b[i + 4] == 'E' && b[i + 5] == 'x' && b[i + 6] == 'i' && b[i + 7] == 'f')
                return tiff(b, i + 10, end);
            i += 2 + len;
        }
        return 1;
    }

    private static int tiff(byte[] b, int t, int end) {
        if (t + 8 > end) return 1;
        boolean le = b[t] == 'I';
        long ifd = t + u32(b, t + 4, le);
        if (ifd + 2 > end) return 1;
        int n = u16(b, (int) ifd, le);
        for (int k = 0; k < n; k++) {
            long e = ifd + 2 + 12L * k;
            if (e + 12 > end) return 1;
            if (u16(b, (int) e, le) == 0x0112) { int v = u16(b, (int) e + 8, le); return v >= 1 && v <= 8 ? v : 1; }
        }
        return 1;
    }

    /** A WebP's canvas: the extended (`VP8X`), lossless (`VP8L`) and lossy (`VP8 `) headers each say it differently. */
    private static Size webp(byte[] b) {
        if (b.length < 30) return null;
        String chunk = new String(b, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
        return switch (chunk) {
            case "VP8X" -> new Size(1 + u24(b, 24), 1 + u24(b, 27));
            case "VP8L" -> new Size(1 + (((b[22] & 0x3F) << 8) | (b[21] & 0xFF)),
                    1 + (((b[24] & 0x0F) << 10) | ((b[23] & 0xFF) << 2) | ((b[22] & 0xC0) >> 6)));
            case "VP8 " -> new Size(u16(b, 26, true) & 0x3FFF, u16(b, 28, true) & 0x3FFF);
            default -> null;
        };
    }

    private static int u16(byte[] b, int i, boolean le) {
        if (i < 0 || i + 2 > b.length) return 0;
        return le ? (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8 : (b[i] & 0xFF) << 8 | (b[i + 1] & 0xFF);
    }
    private static long u32(byte[] b, int i, boolean le) {
        if (i < 0 || i + 4 > b.length) return Integer.MAX_VALUE;
        return le ? ((long) u16(b, i + 2, true) << 16) | u16(b, i, true) : ((long) u16(b, i, false) << 16) | u16(b, i + 2, false);
    }
    private static int u24(byte[] b, int i) { return (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8 | (b[i + 2] & 0xFF) << 16; }
}
