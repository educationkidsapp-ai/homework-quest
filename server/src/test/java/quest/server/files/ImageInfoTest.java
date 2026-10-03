package quest.server.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** B5: an image's size as the viewer sees it, and the downscaled copy `?w=` answers — turned upright like the original. */
class ImageInfoTest {

    @Test void a_png_and_a_jpeg_are_measured_from_their_headers() throws Exception {
        assertThat(ImageInfo.size(encode(40, 30, "png"), "image/png")).isEqualTo(new ImageInfo.Size(40, 30));
        assertThat(ImageInfo.size(encode(400, 300, "jpg"), "image/jpeg")).isEqualTo(new ImageInfo.Size(400, 300));
        assertThat(ImageInfo.size("%PDF-1.7".getBytes(), "application/pdf")).isNull();
        assertThat(ImageInfo.size(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}, "image/jpeg")).as("a header that says nothing").isNull();
    }

    /** A phone photo stored landscape with "turn me 90°" is a portrait photo to everybody who looks at it. */
    @Test void a_photo_stored_on_its_side_is_measured_and_shrunk_upright() throws Exception {
        byte[] sideways = withOrientation(encode(400, 300, "jpg"), 6);
        assertThat(ImageInfo.orientation(sideways)).isEqualTo(6);
        assertThat(ImageInfo.size(sideways, "image/jpeg")).isEqualTo(new ImageInfo.Size(300, 400));
        var small = ImageIO.read(new ByteArrayInputStream(ImageInfo.downscale(sideways, "image/jpeg", 150)));
        assertThat(small.getWidth()).isEqualTo(150);
        assertThat(small.getHeight()).isEqualTo(200);
    }

    @Test void only_what_is_wider_than_asked_and_decodable_is_shrunk() throws Exception {
        var small = ImageIO.read(new ByteArrayInputStream(ImageInfo.downscale(encode(400, 300, "png"), "image/png", 100)));
        assertThat(small.getWidth()).isEqualTo(100);
        assertThat(small.getHeight()).isEqualTo(75);
        assertThat(ImageInfo.downscale(encode(80, 60, "png"), "image/png", 100)).as("already that narrow").isNull();
        assertThat(ImageInfo.downscale(webp(), "image/webp", 100)).as("no WebP encoder: the original is answered").isNull();
        assertThat(ImageInfo.downscale("%PDF-1.7".getBytes(), "application/pdf", 100)).isNull();
    }

    @Test void a_webp_is_measured_from_its_own_header() {
        assertThat(ImageInfo.size(webp(), "image/webp")).isEqualTo(new ImageInfo.Size(640, 480));
        assertThat(ImageInfo.orientation(webp())).as("not a JPEG: no EXIF turn").isEqualTo(1);
    }

    private static byte[] encode(int w, int h, String format) throws Exception {
        var image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    /** The JPEG with an APP1 `Exif` segment, holding one IFD0 entry — Orientation — inserted after its JFIF APP0. */
    private static byte[] withOrientation(byte[] jpeg, int orientation) {
        int app0 = 2 + 2 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF));
        byte[] app1 = {(byte) 0xFF, (byte) 0xE1, 0, 34, 'E', 'x', 'i', 'f', 0, 0,
                'M', 'M', 0, 0x2A, 0, 0, 0, 8,                                   // big-endian TIFF, IFD0 at 8
                0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0,   // one SHORT: Orientation
                0, 0, 0, 0};
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, app0); out.write(app1, 0, app1.length); out.write(jpeg, app0, jpeg.length - app0);
        return out.toByteArray();
    }

    /** The first 30 bytes of an extended WebP: a 640 × 480 canvas. */
    private static byte[] webp() {
        return new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', 'X', 10, 0, 0, 0, 0, 0, 0, 0,
                0x7F, 0x02, 0x00, (byte) 0xDF, 0x01, 0x00};
    }
}
