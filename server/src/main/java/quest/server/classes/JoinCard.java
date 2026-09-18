package quest.server.classes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;
import quest.server.config.ApiException;
import quest.server.tenancy.Entities.ClassEntity;

/**
 * The A5 card a school prints and hands to parents (`docs/prompts/dashboard-first-one-school.md` §6): the school's
 * name, the class, and the join code large enough to read across a table, with the one line that says what to do
 * with it.
 *
 * <p>Standard-14 Helvetica on purpose — no font file is embedded, so the card is a couple of kilobytes and renders
 * identically everywhere. The card carries no roster and no teacher: it is handed out, and what is on it is exactly
 * what {@code GET /classes/lookup} already answers to anybody holding the code.
 */
@Component
public class JoinCard {
    private static final PDRectangle A5 = new PDRectangle(420f, 595f);       // 148 × 210 mm at 72 dpi
    private static final float MARGIN = 48f;

    public byte[] render(String platformName, String schoolName, ClassEntity section) {
        if (section.getJoinCode() == null) throw ApiException.badRequest("That class has no join code yet.");
        var helvetica = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        var bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var page = new PDPage(A5);
            document.addPage(page);
            try (var canvas = new PDPageContentStream(document, page)) {
                float width = A5.getWidth();
                centred(canvas, bold, 16f, schoolName == null || schoolName.isBlank() ? platformName : schoolName, width, 520f);
                centred(canvas, helvetica, 11f, platformName, width, 498f);
                centred(canvas, bold, 34f, section.getName(), width, 404f);
                centred(canvas, helvetica, 14f, label(section), width, 376f);
                centred(canvas, helvetica, 12f, "Your class join code", width, 300f);
                centred(canvas, bold, 54f, spaced(section.getJoinCode()), width, 236f);
                centred(canvas, helvetica, 11f, "Open the app, choose Join a class and type this code.", width, 160f);
                canvas.setLineWidth(1f);
                canvas.moveTo(MARGIN, 196f); canvas.lineTo(width - MARGIN, 196f); canvas.stroke();
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("could not render the join card", e);
        }
    }

    private static String label(ClassEntity section) {
        String curriculum = section.getCurriculum() == null ? "" : Character.toUpperCase(section.getCurriculum().charAt(0)) + section.getCurriculum().substring(1);
        return curriculum + " · Grade " + section.getGrade();
    }

    /** "ACD 4KM": a code read off paper is typed one group at a time. */
    private static String spaced(String code) { return code.length() == 6 ? code.substring(0, 3) + " " + code.substring(3) : code; }

    private static void centred(PDPageContentStream canvas, PDType1Font font, float size, String text, float pageWidth, float y) throws IOException {
        String value = text == null ? "" : text;
        float textWidth = font.getStringWidth(value) / 1000 * size;
        canvas.beginText();
        canvas.setFont(font, size);
        canvas.newLineAtOffset((pageWidth - textWidth) / 2, y);
        canvas.showText(value);
        canvas.endText();
    }
}
