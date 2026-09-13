package quest.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.jupiter.api.Test;
import quest.server.files.SlideConverter;
import quest.server.service.GenerationServiceSeedTestHook;

class SlideConverterTest {
    @Test void pptxBecomesOnePngPerSlide() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (XMLSlideShow show = new XMLSlideShow()) {
            for (String text : List.of("Counting by 2s", "2, 4, 6, 8", "Homework: none")) {
                XSLFTextBox box = show.createSlide().createTextBox();
                box.setAnchor(new Rectangle(50, 50, 400, 100));
                box.setText(text);
            }
            show.write(bos);
        }
        List<byte[]> pngs = SlideConverter.pptxToPngs(bos.toByteArray());
        assertEquals(3, pngs.size());
        for (byte[] png : pngs) {
            assertTrue(png.length > 100);
            assertEquals((byte) 0x89, png[0]);   // PNG signature
        }
    }

    @Test void seedDependsOnExcludedIdsAndLengthOnly() {
        assertEquals(GenerationServiceSeedTestHook.seed(java.util.Set.of("a", "b"), 7), GenerationServiceSeedTestHook.seed(java.util.Set.of("b", "a"), 7));
        assertTrue(!GenerationServiceSeedTestHook.seed(java.util.Set.of("a"), 7).equals(GenerationServiceSeedTestHook.seed(java.util.Set.of("a"), 5)));
    }

    @Test void pdfPagesBecomePngs() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            doc.save(bos);
        }
        List<byte[]> pngs = SlideConverter.pdfToPngs(bos.toByteArray(), 40);
        assertEquals(2, pngs.size());
        assertEquals((byte) 0x89, pngs.get(0)[0]);
    }
}
