package quest.server.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import quest.server.config.ApiException;

/** B5b: a chat photo keeps its pixels and its orientation, and loses everything the camera wrote about who and where. */
public class ImageMetadataTest {
    private static final String[] TELLTALES = {"GPS", "Pixel 9", "xap/1.0", "Photoshop", "a private comment", "SECOND-PICTURE"};

    @Test void a_phone_jpeg_loses_its_exif_xmp_iptc_comment_and_appendix_and_stays_upright() throws Exception {
        byte[] photo = phonePhoto(6);
        assertThat(text(photo)).contains(TELLTALES);                                     // the fixture carries all of it

        byte[] clean = ImageMetadata.strip(photo, "image/jpeg");

        assertThat(text(clean)).doesNotContain(TELLTALES);
        assertThat(ImageInfo.orientation(clean)).as("the turn survives, alone").isEqualTo(6);
        assertThat(ImageInfo.size(clean, "image/jpeg")).isEqualTo(new ImageInfo.Size(300, 400));
        assertThat(text(clean).split("Exif", -1)).as("one EXIF, the orientation's").hasSize(2);
        var pixels = ImageIO.read(new ByteArrayInputStream(clean));
        assertThat(pixels.getWidth()).isEqualTo(400);
        assertThat(pixels.getHeight()).isEqualTo(300);
    }

    @Test void an_upright_jpeg_keeps_no_exif_at_all() throws Exception {
        byte[] clean = ImageMetadata.strip(phonePhoto(1), "image/jpeg");
        assertThat(text(clean)).doesNotContain("Exif").doesNotContain(TELLTALES);
        assertThat(ImageIO.read(new ByteArrayInputStream(clean)).getWidth()).isEqualTo(400);
    }

    @Test void a_png_loses_its_text_exif_and_time_chunks() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB), "png", out);
        byte[] png = out.toByteArray();
        byte[] dirty = withChunksAfterHeader(png, chunk("tEXt", "Comment\0GPS 51.5,-0.1"), chunk("eXIf", "MM\0*GPS"), chunk("tIME", "1234567"));
        byte[] clean = ImageMetadata.strip(dirty, "image/png");
        assertThat(text(clean)).doesNotContain("GPS").doesNotContain("tEXt").doesNotContain("eXIf").doesNotContain("tIME");
        assertThat(ImageIO.read(new ByteArrayInputStream(clean)).getWidth()).isEqualTo(20);
    }

    @Test void a_webp_loses_its_exif_and_xmp_chunks_and_their_flags() {
        byte[] riff = webp();
        byte[] clean = ImageMetadata.strip(riff, "image/webp");
        assertThat(text(clean)).doesNotContain("GPS").doesNotContain("EXIF").doesNotContain("XMP ");
        assertThat(clean[20] & 0x0C).as("the EXIF and XMP flags").isZero();
        assertThat((clean[4] & 0xFF) | (clean[5] & 0xFF) << 8).as("the RIFF size").isEqualTo(clean.length - 8);
        assertThat(ImageInfo.size(clean, "image/webp")).isEqualTo(new ImageInfo.Size(640, 480));
    }

    @Test void a_file_whose_containers_cannot_be_walked_is_refused() {
        byte[] broken = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0x7F, 0x7F, 'E', 'x'};
        assertThatThrownBy(() -> ImageMetadata.strip(broken, "image/jpeg")).isInstanceOf(ApiException.class);
        assertThat(ImageMetadata.strip("%PDF-1.7".getBytes(), "application/pdf")).as("a PDF is not an image").isEqualTo("%PDF-1.7".getBytes());
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * A 400 × 300 JPEG as a phone writes it: EXIF with an orientation, a camera model and a GPS block, an XMP packet, a
     * Photoshop/IPTC block and a comment before the image, and a second picture appended after its end.
     */
    public static byte[] phonePhoto(int orientation) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB), "jpg", out);
        byte[] jpeg = out.toByteArray();
        int app0 = 2 + 2 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF));
        var exif = new ByteArrayOutputStream();
        exif.writeBytes(new byte[] {'E', 'x', 'i', 'f', 0, 0, 'M', 'M', 0, 0x2A, 0, 0, 0, 8, 0, 3,
                0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0,          // Orientation
                0x01, 0x0F, 0, 2, 0, 0, 0, 4, 'P', 'i', 'x', 0,                       // Make
                (byte) 0x88, 0x25, 0, 4, 0, 0, 0, 1, 0, 0, 0, 50,                     // GPSInfo
                0, 0, 0, 0});
        exif.writeBytes("GPS 51.5007N 0.1246W Pixel 9".getBytes(StandardCharsets.US_ASCII));
        var dirty = new ByteArrayOutputStream();
        dirty.write(jpeg, 0, app0);
        dirty.writeBytes(segment(0xE1, exif.toByteArray()));
        dirty.writeBytes(segment(0xE1, "http://ns.adobe.com/xap/1.0/\0<x:xmpmeta exif:GPSLatitude='51'/>".getBytes(StandardCharsets.US_ASCII)));
        dirty.writeBytes(segment(0xED, "Photoshop 3.0\0 IPTC city".getBytes(StandardCharsets.US_ASCII)));
        dirty.writeBytes(segment(0xFE, "a private comment".getBytes(StandardCharsets.US_ASCII)));
        dirty.write(jpeg, app0, jpeg.length - app0);
        dirty.writeBytes("SECOND-PICTURE with its own GPS".getBytes(StandardCharsets.US_ASCII));
        return dirty.toByteArray();
    }

    private static byte[] segment(int marker, byte[] payload) {
        int len = payload.length + 2;
        var out = new ByteArrayOutputStream();
        out.write(0xFF); out.write(marker); out.write(len >> 8); out.write(len & 0xFF); out.writeBytes(payload);
        return out.toByteArray();
    }

    private static byte[] chunk(String type, String data) {
        byte[] d = data.getBytes(StandardCharsets.ISO_8859_1);
        var out = new ByteArrayOutputStream();
        out.write(d.length >>> 24); out.write(d.length >>> 16); out.write(d.length >>> 8); out.write(d.length);
        out.writeBytes(type.getBytes(StandardCharsets.US_ASCII)); out.writeBytes(d);
        var crc = new java.util.zip.CRC32(); crc.update(type.getBytes(StandardCharsets.US_ASCII)); crc.update(d);
        long c = crc.getValue(); out.write((int) (c >>> 24)); out.write((int) (c >>> 16)); out.write((int) (c >>> 8)); out.write((int) c);
        return out.toByteArray();
    }

    /** The chunks after IHDR (8-byte signature + 25-byte IHDR chunk). */
    private static byte[] withChunksAfterHeader(byte[] png, byte[]... chunks) {
        var out = new ByteArrayOutputStream();
        out.write(png, 0, 33);
        for (byte[] c : chunks) out.writeBytes(c);
        out.write(png, 33, png.length - 33);
        return out.toByteArray();
    }

    /** An extended WebP header (640 × 480, EXIF and XMP flags set), then an EXIF and an XMP chunk. */
    private static byte[] webp() {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', 'X', 10, 0, 0, 0, 0x0C, 0, 0, 0,
                0x7F, 0x02, 0x00, (byte) 0xDF, 0x01, 0x00});
        out.writeBytes(new byte[] {'E', 'X', 'I', 'F', 7, 0, 0, 0, 'G', 'P', 'S', ' ', '5', '1', '!', 0});   // odd length, padded
        out.writeBytes(new byte[] {'X', 'M', 'P', ' ', 4, 0, 0, 0, 'G', 'P', 'S', '!'});
        byte[] b = out.toByteArray();
        int riff = b.length - 8;
        b[4] = (byte) riff; b[5] = (byte) (riff >> 8);
        return b;
    }

    private static String text(byte[] b) { return new String(b, StandardCharsets.ISO_8859_1); }
}
