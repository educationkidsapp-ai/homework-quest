package quest.server.analysis;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import net.coobird.thumbnailator.Thumbnails;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quest.server.config.ApiException;

/**
 * Turns an uploaded PDF / PPTX / image into pages: extracted text plus a PNG render of every page.
 * PPTX is converted with LibreOffice when present (`soffice --headless`), else read with POI (text only,
 * pages rendered as plain text cards).
 */
@Component
public class SlideProcessor {
    private static final Logger log = LoggerFactory.getLogger(SlideProcessor.class);
    public static final int MAX_PAGES = 40; public static final int RENDER_PX = 1000; public static final long MAX_BYTES = 25L * 1024 * 1024;

    public record Page(int number, String text, byte[] png, int width, int height) {}
    public record Source(String kind, List<Page> pages) {
        public String textDump() {
            var sb = new StringBuilder();
            for (var p : pages) sb.append("--- Page ").append(p.number()).append(" ---\n").append(p.text().isBlank() ? "(no text — see image)" : p.text().strip()).append("\n\n");
            return sb.toString();
        }
        public boolean needsImages() { return pages.stream().anyMatch(p -> p.text().strip().length() < 40); }
    }

    public Source process(String fileName, String mimeType, byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new ApiException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "Files must be under 25 MB.");
        String lower = fileName == null ? "" : fileName.toLowerCase();
        try {
            if (lower.endsWith(".pdf") || "application/pdf".equals(mimeType)) return new Source("pdf", pdf(bytes));
            if (lower.endsWith(".pptx") || (mimeType != null && mimeType.contains("presentation"))) return new Source("pptx", pptx(bytes));
            if (lower.matches(".*\\.(png|jpe?g|webp)$") || (mimeType != null && mimeType.startsWith("image/"))) return new Source("image", List.of(image(bytes)));
        } catch (ApiException e) { throw e; } catch (Exception e) {
            log.warn("unreadable {}: {}", fileName, e.toString());
            throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "unreadable_file", "We couldn't read " + fileName + ". Try exporting it as PDF.");
        }
        throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "unreadable_file", "Only PDF, PPTX, PNG and JPEG files are supported.");
    }

    List<Page> pdf(byte[] bytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            if (doc.isEncrypted()) throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "unreadable_file", "The PDF is password-protected.");
            int n = Math.min(doc.getNumberOfPages(), MAX_PAGES);
            if (n == 0) throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "unreadable_file", "The PDF has no pages.");
            var renderer = new PDFRenderer(doc); var stripper = new PDFTextStripper();
            List<Page> pages = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                stripper.setStartPage(i + 1); stripper.setEndPage(i + 1);
                String text = stripper.getText(doc);
                BufferedImage img = renderer.renderImageWithDPI(i, 110, ImageType.RGB);
                pages.add(page(i + 1, text, img));
            }
            return pages;
        }
    }

    List<Page> pptx(byte[] bytes) throws Exception {
        var soffice = findSoffice();
        if (soffice != null) {
            try { return pdf(convertWithSoffice(soffice, bytes)); } catch (Exception e) { log.warn("soffice conversion failed, falling back to POI: {}", e.toString()); }
        }
        try (var show = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            List<Page> pages = new ArrayList<>(); int i = 0;
            for (var slide : show.getSlides()) {
                if (++i > MAX_PAGES) break;
                var text = new StringBuilder();
                for (XSLFShape shape : slide.getShapes()) if (shape instanceof XSLFTextShape ts) text.append(ts.getText()).append('\n');
                var dim = show.getPageSize();
                BufferedImage img = new BufferedImage(Math.max(1, dim.width), Math.max(1, dim.height), BufferedImage.TYPE_INT_RGB);
                var g = img.createGraphics();
                try { g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, img.getWidth(), img.getHeight()); slide.draw(g); } catch (Exception e) { log.debug("slide draw failed: {}", e.toString()); } finally { g.dispose(); }
                pages.add(page(i, text.toString(), img));
            }
            if (pages.isEmpty()) throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "unreadable_file", "The presentation has no slides.");
            return pages;
        }
    }

    Page image(byte[] bytes) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        if (img == null) throw new IOException("not an image");
        return page(1, "", img);
    }

    private Page page(int number, String text, BufferedImage img) throws IOException {
        var out = new ByteArrayOutputStream();
        Thumbnails.of(img).size(RENDER_PX, RENDER_PX).keepAspectRatio(true).outputFormat("png").toOutputStream(out);
        BufferedImage scaled = ImageIO.read(new ByteArrayInputStream(out.toByteArray()));
        return new Page(number, text == null ? "" : text, out.toByteArray(), scaled.getWidth(), scaled.getHeight());
    }

    static String findSoffice() {
        for (String c : List.of("/usr/bin/soffice", "/usr/lib/libreoffice/program/soffice", "/opt/homebrew/bin/soffice", "/Applications/LibreOffice.app/Contents/MacOS/soffice"))
            if (Files.isExecutable(Path.of(c))) return c;
        return null;
    }

    private static byte[] convertWithSoffice(String soffice, byte[] pptx) throws Exception {
        Path dir = Files.createTempDirectory("quest-pptx");
        try {
            Path in = dir.resolve("slides.pptx"); Files.write(in, pptx);
            var p = new ProcessBuilder(soffice, "--headless", "--convert-to", "pdf", "--outdir", dir.toString(), in.toString()).redirectErrorStream(true).start();
            if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IOException("soffice timed out"); }
            Path out = dir.resolve("slides.pdf");
            if (!Files.exists(out)) throw new IOException("soffice produced no PDF");
            return Files.readAllBytes(out);
        } finally { try (var s = Files.walk(dir)) { s.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete()); } }
    }
}
