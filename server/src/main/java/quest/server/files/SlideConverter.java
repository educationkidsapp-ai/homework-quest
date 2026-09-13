package quest.server.files;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;

/** Converts PPTX to one PNG per slide (the API takes images, not PowerPoint) and counts PDF pages. */
public final class SlideConverter {
    private SlideConverter() {}

    public static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private static final int MAX_WIDTH = 1400;

    public static List<byte[]> pptxToPngs(byte[] pptx) throws IOException {
        List<byte[]> out = new ArrayList<>();
        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(pptx))) {
            Dimension size = show.getPageSize();
            double scale = Math.min(1.0, MAX_WIDTH / size.getWidth()) * 1.5;
            int w = (int) Math.ceil(size.getWidth() * scale);
            int h = (int) Math.ceil(size.getHeight() * scale);
            for (XSLFSlide slide : show.getSlides()) {
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, w, h);
                g.scale(scale, scale);
                slide.draw(g);
                g.dispose();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ImageIO.write(img, "png", bos);
                out.add(bos.toByteArray());
            }
        }
        return out;
    }

    public static int pdfPageCount(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) { return doc.getNumberOfPages(); }
    }

    /** Renders each PDF page to a PNG (for providers that take images but not documents). */
    public static List<byte[]> pdfToPngs(byte[] pdf, int maxPages) throws IOException {
        List<byte[]> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pages = Math.min(doc.getNumberOfPages(), maxPages);
            for (int i = 0; i < pages; i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, 110, ImageType.RGB);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ImageIO.write(img, "png", bos);
                out.add(bos.toByteArray());
            }
        }
        return out;
    }
}
