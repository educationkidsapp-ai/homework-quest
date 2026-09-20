package quest.server.exams;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

/**
 * §8's "printable per-child result sheet (PDF) for the school file".
 *
 * <p>Built with PDFBox and the Standard-14 Helvetica, exactly as {@link quest.server.classes.JoinCard} is and for
 * the same reason: no font file is embedded, so the sheet is a few kilobytes and renders identically on every
 * printer a school owns.
 *
 * <p><strong>What is on it is what the teacher sees.</strong> The rows come from
 * {@link ExamService#results} — the same scorer, the same marks, the same per-question list — so a sheet filed in
 * September cannot disagree with the screen it was printed from. What it leaves out is everything about the
 * <em>other</em> children: a sheet is about one child, and a class average is the only number on it that is not
 * hers, because a mark means nothing without one.
 *
 * <p>Standard-14 Helvetica cannot draw Arabic, and these schools have children with Arabic names. Rather than
 * printing a row of question marks, {@link #ascii} keeps what the font can render and the name is still identified
 * by the class, the exam and the date; embedding a Unicode face is the follow-up that fixes it properly.
 */
@Component
public class ExamSheet {
    private static final PDRectangle A4 = PDRectangle.A4;
    private static final float MARGIN = 56f, LINE = 16f;

    public byte[] render(String schoolName, ExamDto.ExamResults results, ExamDto.ExamChildResult child) {
        var body = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        var bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var page = new PDPage(A4);
            document.addPage(page);
            float width = A4.getWidth(), right = width - MARGIN;
            try (var canvas = new PDPageContentStream(document, page)) {
                float y = A4.getHeight() - MARGIN;
                text(canvas, bold, 18f, ascii(results.title() == null ? "Exam" : results.title()), MARGIN, y);
                y -= LINE + 6;
                text(canvas, body, 11f, ascii(line(schoolName, results.className(), results.date())), MARGIN, y);
                y -= LINE + 10;
                rule(canvas, y, width);
                y -= LINE + 10;

                text(canvas, bold, 14f, ascii(child.name()), MARGIN, y);
                y -= LINE + 6;
                for (String row : List.of(
                        "Result: " + value(child.percent(), "%") + "   Band: " + value(child.band(), ""),
                        "Stars: " + child.score() + " of " + child.maxScore()
                                + "   Time taken: " + (child.secondsTaken() == null ? "-" : ExamExports.duration(child.secondsTaken())),
                        "Handed in: " + (child.submittedAt() == null ? "not handed in" : child.state())
                                + (child.reopened() ? "   (re-opened by the teacher)" : "")
                                + (child.needsMarking() > 0 ? "   " + child.needsMarking() + " answer(s) still to mark" : ""),
                        "Class average: " + value(results.classAverage(), "%")
                                + "   Sat: " + results.sat() + " of " + results.roster())) {
                    text(canvas, body, 11f, row, MARGIN, y);
                    y -= LINE;
                }
                if (child.comment() != null && !child.comment().isBlank()) {
                    y -= 6;
                    text(canvas, bold, 11f, "Teacher's comment", MARGIN, y); y -= LINE;
                    for (String wrapped : wrap(ascii(child.comment()), 92)) { text(canvas, body, 11f, wrapped, MARGIN, y); y -= LINE; }
                }

                y -= 10;
                rule(canvas, y, width);
                y -= LINE + 6;
                text(canvas, bold, 11f, "Questions", MARGIN, y);
                text(canvas, bold, 11f, "Class", right - 90, y);
                y -= LINE + 2;
                for (var question : results.questions()) {
                    if (y < MARGIN + LINE) break;                                // one page: a filed sheet is one sheet
                    text(canvas, body, 10f, ascii(clip(question.title(), 60)), MARGIN, y);
                    text(canvas, body, 10f, summary(question), right - 90, y);
                    y -= LINE;
                }
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("could not render the exam sheet", e);
        }
    }

    /** What the class did with a question, as the difficulty list has already worked it out. */
    private static String summary(ExamDto.ExamQuestion question) {
        if (question.open()) return question.averageStars() == null ? "not marked" : question.averageStars() + " stars";
        if (question.answered() == 0) return "not reached";
        return question.correct() + "/" + question.answered() + " right";
    }

    private static String line(String schoolName, String className, String date) {
        var parts = new java.util.ArrayList<String>();
        if (schoolName != null && !schoolName.isBlank()) parts.add(schoolName);
        if (className != null && !className.isBlank()) parts.add(className);
        if (date != null) parts.add(date);
        return String.join("  ·  ", parts);
    }

    private static String value(Object value, String suffix) { return value == null ? "-" : value + suffix; }

    private static String clip(String text, int max) {
        String value = text == null ? "" : text;
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    /** What Standard-14 Helvetica can draw; see the class comment for why the rest is dropped rather than mangled. */
    static String ascii(String text) {
        if (text == null) return "";
        var out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) out.append(c == '…' ? '.' : c == '·' ? '-' : c < 32 || c > 126 ? ' ' : c);
        return out.toString().replaceAll(" {2,}", " ").strip();
    }

    private static List<String> wrap(String text, int width) {
        var out = new java.util.ArrayList<String>();
        var line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() + word.length() + 1 > width) { out.add(line.toString()); line.setLength(0); }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out.isEmpty() ? List.of("") : out;
    }

    private static void rule(PDPageContentStream canvas, float y, float pageWidth) throws IOException {
        canvas.setLineWidth(0.75f);
        canvas.moveTo(MARGIN, y); canvas.lineTo(pageWidth - MARGIN, y); canvas.stroke();
    }

    private static void text(PDPageContentStream canvas, PDType1Font font, float size, String value, float x, float y) throws IOException {
        canvas.beginText();
        canvas.setFont(font, size);
        canvas.newLineAtOffset(x, y);
        canvas.showText(ascii(value));
        canvas.endText();
    }
}
