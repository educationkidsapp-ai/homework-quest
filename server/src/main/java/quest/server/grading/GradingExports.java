package quest.server.grading;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import quest.server.config.ApiException;

/**
 * §7's "Export the gradebook as CSV/XLSX", and the same for one lesson's results.
 *
 * <p>Both formats are built from exactly the same rows ({@link #resultsRows}, {@link #gradebookRows}), so a teacher
 * who opens the spreadsheet and a colleague who opens the CSV are looking at the same numbers — a second layout for
 * the second format is how the two drift apart. A number is written as a number in the workbook and as plain digits
 * in the CSV; an empty cell is empty in both, never a zero, because a child who did not play is not a child who
 * scored nothing.
 *
 * <p>The CSV is written with a UTF-8 BOM: the column that matters most to a teacher is the child's name, these
 * schools have Arabic ones, and Excel on Windows reads a BOM-less UTF-8 CSV as the local code page and mangles them.
 */
@Component
public class GradingExports {
    public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    // ---------------------------------------------------------------- one lesson's results

    public byte[] resultsCsv(GradingDto.LessonResults results) { return csv(resultsRows(results)); }

    public byte[] resultsXlsx(GradingDto.LessonResults results) {
        return xlsx("Results", resultsRows(results));
    }

    /** Header, then one row per child: her score, what it was made of, and one column per stop. */
    private static List<List<Object>> resultsRows(GradingDto.LessonResults results) {
        var rows = new java.util.ArrayList<List<Object>>();
        var header = new java.util.ArrayList<Object>(List.of("Child", "Played", "Level reached", "Completion %",
                "Stars", "Auto score", "Teacher score", "Score", "Band", "Needs marking", "Comment"));
        for (var stop : results.stops()) header.add(stop.title() + (stop.open() ? " (open)" : ""));
        rows.add(header);
        for (var child : results.children()) {
            var row = new java.util.ArrayList<Object>();
            row.add(child.name()); row.add(child.attempted() ? "yes" : "no"); row.add(child.levelReached());
            row.add(child.completion()); row.add(child.starsEarned() + "/" + child.starsTotal());
            row.add(child.autoScore()); row.add(child.teacherScore()); row.add(child.score()); row.add(child.band());
            row.add(child.needsMarking()); row.add(child.comment());
            for (var stop : results.stops()) {
                var mine = child.stops().stream().filter(s -> s.stopId().equals(stop.stopId())).findFirst().orElse(null);
                row.add(mine == null || !mine.attempted() ? null : mine.needsMarking() ? "needs marking" : mine.score());
            }
            rows.add(row);
        }
        return rows;
    }

    // ---------------------------------------------------------------- the gradebook grid

    public byte[] gradebookCsv(GradingDto.Gradebook book) { return csv(gradebookRows(book)); }

    public byte[] gradebookXlsx(GradingDto.Gradebook book) { return xlsx("Gradebook", gradebookRows(book)); }

    /** The grid as it is on screen: children down, lessons across, then the child's average and band. */
    private static List<List<Object>> gradebookRows(GradingDto.Gradebook book) {
        var rows = new java.util.ArrayList<List<Object>>();
        var header = new java.util.ArrayList<Object>(List.of("Child"));
        for (var lesson : book.lessons()) header.add(lesson.date() + " " + (lesson.title() == null ? lesson.lessonId() : lesson.title()));
        header.add("Average"); header.add("Band"); header.add("Trend");
        rows.add(header);
        for (var child : book.children()) {
            var row = new java.util.ArrayList<Object>();
            row.add(child.name());
            for (var cell : child.cells()) row.add(cell.needsMarking() && cell.score() == null ? "needs marking" : cell.score());
            row.add(child.average()); row.add(child.band()); row.add(child.trend());
            rows.add(row);
        }
        var average = new java.util.ArrayList<Object>();
        average.add("Class average");
        for (var lesson : book.lessons()) average.add(lesson.classAverage());
        average.add(null); average.add(null); average.add(null);
        rows.add(average);
        return rows;
    }

    // ---------------------------------------------------------------- the two writers

    private static byte[] csv(List<List<Object>> rows) {
        var out = new StringBuilder();
        for (var row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) out.append(',');
                out.append(quote(row.get(i)));
            }
            out.append('\n');
        }
        byte[] body = out.toString().getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, withBom, 0, BOM.length);
        System.arraycopy(body, 0, withBom, BOM.length, body.length);
        return withBom;
    }

    /** RFC 4180: a cell holding a comma, a quote or a newline is quoted and its quotes are doubled. */
    private static String quote(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value);
        if (text.indexOf(',') < 0 && text.indexOf('"') < 0 && text.indexOf('\n') < 0 && text.indexOf('\r') < 0) return text;
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    private static byte[] xlsx(String sheetName, List<List<Object>> rows) {
        try (var workbook = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(sheetName);
            var bold = workbook.createCellStyle();
            var font = workbook.createFont(); font.setBold(true); bold.setFont(font);
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r);
                var values = rows.get(r);
                for (int c = 0; c < values.size(); c++) {
                    var cell = row.createCell(c);
                    Object value = values.get(c);
                    if (value == null) continue;
                    if (value instanceof Number number) cell.setCellValue(number.doubleValue());
                    else cell.setCellValue(String.valueOf(value));
                    if (r == 0) cell.setCellStyle(bold);
                }
            }
            sheet.createFreezePane(1, 1);
            workbook.write(bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw ApiException.badRequest("the export could not be written: " + e.getMessage());
        }
    }

    /** Used by the tests to walk a workbook without repeating the POI boilerplate. */
    static void eachRow(byte[] workbook, Consumer<Row> consumer) throws IOException {
        try (var book = new XSSFWorkbook(new java.io.ByteArrayInputStream(workbook))) {
            book.getSheetAt(0).forEach(consumer);
        }
    }
}
