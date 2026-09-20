package quest.server.grading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * §7's "Export the gradebook as CSV/XLSX". The workbooks are opened with POI rather than compared byte for byte:
 * what matters is that the file a teacher double-clicks opens, has the rows she saw on screen, and holds its scores
 * as numbers she can sort and average — not that the bytes match a fixture.
 */
class GradingExportTest extends GradingTestSupport {
    private static final String A = "gx-school", TEACHER = "gx-teacher", CLASS_1A = "gx-1a", LESSON = "gx-lesson-1";

    @Override public String prefix() { return "gx-"; }

    private String teacherToken, maya;

    @BeforeEach void seed() throws Exception {
        school(A, "Export Academy", "GXSCHA");
        teacher(TEACHER, A, "Ms Sara");
        var section = klass(CLASS_1A, A, TEACHER, "1A");
        lesson(LESSON, A, section, LocalDate.now().minusDays(1));
        enableGrading(adminToken(), A);
        teacherToken = token(TEACHER, "TEACHER", A);
        maya = child("Maya", "GXSCHA", section);
        child("Omar", "GXSCHA", section);
        playTheSample(maya, LESSON);
    }

    @AfterEach void clean() { removeSeed(); }

    @Test void the_results_csv_has_a_header_a_row_per_child_and_a_bom() throws Exception {
        byte[] body = download("/teacher/lessons/" + LESSON + "/results.csv", "text/csv");

        assertThat(body[0] & 0xFF).as("Excel on Windows needs the BOM to read Arabic names").isEqualTo(0xEF);
        var lines = new String(body, StandardCharsets.UTF_8).substring(1).split("\n");
        assertThat(lines[0]).startsWith("Child,Played,Level played,Level reached,Answered,Out of");
        assertThat(lines[0]).as("each stop column names the level it belongs to (N4.5 D1)")
                .contains(",L1 " + stopId(LESSON, 1) + ",");
        assertThat(lines).as("the header and both children").hasSize(3);
        assertThat(lines[1]).startsWith("Maya,yes,1,1,3,3");
        assertThat(lines[2]).startsWith("Omar,no,,0,0,0");
    }

    /**
     * N4.5 D1 in the sheet: three levels, one column group each, and a child's row filling only the group she
     * played. The old export took its columns from the top level alone, so a class that played Level 1 came out as
     * a grid of empty cells.
     */
    @Test void the_results_csv_writes_one_column_group_per_level() throws Exception {
        String multi = "gx-multi-1";
        readyToPublish(multi, A, klass(CLASS_1A, A, TEACHER, "1A"), LocalDate.now(), "homework");
        mvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/teacher/lessons/" + multi + "/publish")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"classIds\":[\"" + CLASS_1A + "\"]}"), teacherToken))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        playTheSample(maya, multi);                                             // Level 1 only

        var lines = new String(download("/teacher/lessons/" + multi + "/results.csv", "text/csv"),
                StandardCharsets.UTF_8).substring(1).split("\n");
        var header = lines[0].split(",");
        assertThat(header).as("Level 1's three stops, then Level 2's and Level 3's one each")
                .endsWith("L1 " + stopId(multi, 1), "L1 " + stopId(multi, 2), "L1 " + stopId(multi, 3) + " (open)",
                        "L2 " + multi + ":L2v0:s1", "L3 " + multi + ":L3v0:s1");
        var hers = java.util.Arrays.stream(lines).filter(l -> l.startsWith("Maya")).findFirst().orElseThrow().split(",", -1);
        assertThat(hers[hers.length - 5]).as("her Level 1 cells are filled").isEqualTo("100");
        assertThat(hers[hers.length - 2]).as("and the levels she did not play are empty").isEmpty();
        assertThat(hers[hers.length - 1]).isEmpty();
    }

    @Test void the_results_workbook_opens_in_poi_with_its_scores_as_numbers() throws Exception {
        byte[] body = download("/teacher/lessons/" + LESSON + "/results.xlsx", GradingExports.XLSX);

        var rows = read(body);
        assertThat(rows.get(0).get(0)).isEqualTo("Child");
        assertThat(rows).hasSize(3);
        var maya = rows.stream().filter(r -> "Maya".equals(r.get(0))).findFirst().orElseThrow();
        assertThat(maya).contains("50.0");                                     // the auto score, held as a number
        assertThat(maya).contains(Bands.DEVELOPING);
    }

    @Test void the_gradebook_csv_ends_with_the_class_average_row() throws Exception {
        byte[] body = download("/teacher/classes/" + CLASS_1A + "/gradebook.csv", "text/csv");

        var lines = new String(body, StandardCharsets.UTF_8).substring(1).strip().split("\n");
        assertThat(lines[0]).startsWith("Child,");
        assertThat(lines[0]).endsWith("Average,Band,Trend");
        assertThat(lines[lines.length - 1]).startsWith("Class average,50");
    }

    @Test void the_gradebook_workbook_opens_in_poi_with_a_column_per_lesson() throws Exception {
        byte[] body = download("/teacher/classes/" + CLASS_1A + "/gradebook.xlsx", GradingExports.XLSX);

        var rows = read(body);
        assertThat(rows.get(0)).as("child, one lesson, then average, band and trend").hasSize(5);
        assertThat(rows).as("the header, both children and the class average").hasSize(4);
        assertThat(rows.get(rows.size() - 1).get(0)).isEqualTo("Class average");
    }

    /** A download is still a read of somebody's class: another school's is a 404 before a byte is written. */
    @Test void an_export_is_scoped_like_the_page_it_comes_from() throws Exception {
        school("gx-school-b", "Other", "GXSCHB");
        teacher("gx-teacher-b", "gx-school-b", "Ms Other");
        enableGrading(adminToken(), "gx-school-b");

        mvc.perform(as(get("/teacher/lessons/" + LESSON + "/results.csv"), token("gx-teacher-b", "TEACHER", "gx-school-b")))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- helpers

    private byte[] download(String path, String contentType) throws Exception {
        var response = mvc.perform(as(get(path), teacherToken)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getContentType()).startsWith(contentType);
        assertThat(response.getHeader("Content-Disposition")).contains("attachment; filename=");
        return response.getContentAsByteArray();
    }

    /** Every row of the first sheet as strings, so an assertion reads the same for a number and a word. */
    private static List<List<String>> read(byte[] workbook) throws Exception {
        var rows = new ArrayList<List<String>>();
        GradingExports.eachRow(workbook, row -> {
            var cells = new ArrayList<String>();
            for (int c = 0; c < row.getLastCellNum(); c++) {
                var cell = row.getCell(c);
                cells.add(cell == null ? "" : cell.getCellType() == CellType.NUMERIC
                        ? String.valueOf(cell.getNumericCellValue()) : cell.getStringCellValue());
            }
            rows.add(cells);
        });
        return rows;
    }
}
