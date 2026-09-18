package quest.server.classes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

/**
 * The Admin's Classes and Children screens (`docs/prompts/dashboard-first-one-school.md` §6): sections with join
 * codes, the printable card, and the roster with its CSV/XLSX import.
 */
class ClassAdminTest extends ClassesTestSupport {
    private static final String A = "ca-school-a";
    private String admin;

    @Override String prefix() { return "ca-"; }

    @BeforeEach void seed() throws Exception {
        school(A, "Cedar Primary", "CAAAAA");
        admin = adminToken();
    }

    @AfterEach void cleanUp() { removeSeed(); }

    @Test void a_section_is_created_listed_and_retired_with_its_join_code() throws Exception {
        var created = json(mvc.perform(scoped(post("/admin/classes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"curriculum\":\"british\",\"grade\":1,\"name\":\"1A\"}"), admin, A))
                .andExpect(status().isCreated()).andReturn());
        assertThat(created.get("name").asText()).isEqualTo("1A");
        assertThat(created.get("joinCode").asText()).hasSize(6).matches("[A-Z0-9]{6}");
        assertThat(created.get("active").asBoolean()).isTrue();
        String id = created.get("id").asText();

        // the same name twice in one grade is a 409, not a second section nobody can tell apart
        mvc.perform(scoped(post("/admin/classes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"curriculum\":\"british\",\"grade\":1,\"name\":\"1A\"}"), admin, A)).andExpect(status().isConflict());

        var sibling = section("british", 1, "1B");
        assertThat(sibling.get("joinCode").asText()).isNotEqualTo(created.get("joinCode").asText());

        var list = json(mvc.perform(scoped(get("/admin/classes?curriculum=british&grade=1"), admin, A)).andExpect(status().isOk()).andReturn());
        assertThat(names(list)).containsExactly("1A", "1B");
        assertThat(list.get(0).get("children").asInt()).isZero();

        // a new code retires the printed cards; retiring the section keeps it, inactive, with everything on it
        String before = created.get("joinCode").asText();
        var rolled = json(mvc.perform(scoped(post("/admin/classes/" + id + "/join-code"), admin, A)).andExpect(status().isOk()).andReturn());
        assertThat(rolled.get("joinCode").asText()).isNotEqualTo(before);

        var retired = json(mvc.perform(scoped(patch("/admin/classes/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false,\"joinCodeEnabled\":false}"), admin, A)).andExpect(status().isOk()).andReturn());
        assertThat(retired.get("active").asBoolean()).isFalse();
        assertThat(retired.get("joinCodeEnabled").asBoolean()).isFalse();
    }

    /** The printable card: a real PDF, with the school, the class and the code on it. */
    @Test void the_join_card_is_a_pdf_that_carries_the_code() throws Exception {
        var section = section("british", 2, "2A");
        var response = mvc.perform(scoped(get("/admin/classes/" + section.get("id").asText() + "/join-card.pdf"), admin, A))
                .andExpect(status().isOk()).andReturn().getResponse();
        byte[] pdf = response.getContentAsByteArray();
        assertThat(response.getContentType()).startsWith("application/pdf");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
            assertThat(text).contains("2A").contains("Cedar Primary").contains("Grade 2");
            // the code is printed in two groups of three, because that is how a code read off paper is typed
            assertThat(text.replace(" ", "")).contains(section.get("joinCode").asText());
        }
    }

    @Test void a_child_is_added_edited_and_moved_between_the_schools_sections() throws Exception {
        var oneA = section("british", 1, "1A");
        var oneB = section("british", 1, "1B");
        var child = json(mvc.perform(scoped(post("/admin/classes/" + oneA.get("id").asText() + "/children").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Nour Al Amin\",\"parentEmail\":\"Parent@Example.TEST\"}"), admin, A))
                .andExpect(status().isCreated()).andReturn());
        assertThat(child.get("parentEmail").asText()).isEqualTo("parent@example.test");
        assertThat(child.get("active").asBoolean()).isTrue();
        assertThat(child.get("hasParent").asBoolean()).as("a roster row exists long before any parent account").isFalse();

        // the same name in the same class is a 409 rather than a second row nobody can tell apart
        mvc.perform(scoped(post("/admin/classes/" + oneA.get("id").asText() + "/children").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"nour  al-amin\"}"), admin, A)).andExpect(status().isConflict());

        var moved = json(mvc.perform(scoped(patch("/admin/children/" + child.get("id").asText()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Nour A.\",\"active\":false,\"classId\":\"" + oneB.get("id").asText() + "\"}"), admin, A))
                .andExpect(status().isOk()).andReturn());
        assertThat(moved.get("name").asText()).isEqualTo("Nour A.");
        assertThat(moved.get("active").asBoolean()).isFalse();
        assertThat(moved.get("classId").asText()).isEqualTo(oneB.get("id").asText());
        assertThat(json(mvc.perform(scoped(get("/admin/classes/" + oneA.get("id").asText() + "/children"), admin, A))
                .andExpect(status().isOk()).andReturn())).isEmpty();
    }

    /** The preview writes nothing, names every line, and a commit inserts the `new` ones only. */
    @Test void a_csv_import_previews_before_it_writes_and_marks_duplicates_and_bad_lines() throws Exception {
        var section = section("british", 3, "3A");
        String id = section.get("id").asText();
        mvc.perform(scoped(post("/admin/classes/" + id + "/children").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Sara Al Harbi\"}"), admin, A)).andExpect(status().isCreated());

        String csv = """
                name,parentEmail
                Sara Al Harbi,sara.parent@example.test
                Omar Nasser,omar.parent@example.test
                ,nobody@example.test
                Lina Haddad,not-an-address
                Omar Nasser,twice@example.test
                """;
        var preview = importRoster(id, "roster.csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8), true);
        assertThat(preview.get("dryRun").asBoolean()).isTrue();
        assertThat(statuses(preview)).containsExactly("duplicate", "new", "invalid", "invalid", "duplicate");
        assertThat(preview.get("rows").get(0).get("line").asInt()).as("the file's own row number, header included").isEqualTo(2);
        assertThat(preview.get("rows").get(2).get("reason").asText()).isEqualTo("no name");
        assertThat(preview.get("rows").get(3).get("reason").asText()).isEqualTo("not an email address");
        assertThat(preview.get("summary").get("added").asInt()).isEqualTo(1);
        assertThat(preview.get("summary").get("duplicate").asInt()).isEqualTo(2);
        assertThat(preview.get("summary").get("invalid").asInt()).isEqualTo(2);
        assertThat(roster(id)).as("a dry run writes nothing").hasSize(1);

        var committed = importRoster(id, "roster.csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
        assertThat(committed.get("dryRun").asBoolean()).isFalse();
        assertThat(roster(id)).hasSize(2);
        assertThat(names(roster(id))).containsExactly("Omar Nasser", "Sara Al Harbi");
    }

    @Test void an_xlsx_import_reads_the_same_two_columns() throws Exception {
        var section = section("american", 1, "1A");
        String id = section.get("id").asText();
        var preview = importRoster(id, "roster.xlsx", xlsx(), false);
        assertThat(statuses(preview)).containsExactly("new", "new");
        assertThat(names(roster(id))).containsExactly("Hana Salem", "Yusuf Idris");
        assertThat(roster(id).get(0).get("parentEmail").asText()).isEqualTo("hana.parent@example.test");
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode section(String curriculum, int grade, String name) throws Exception {
        return json(mvc.perform(scoped(post("/admin/classes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"curriculum\":\"" + curriculum + "\",\"grade\":" + grade + ",\"name\":\"" + name + "\"}"), admin, A))
                .andExpect(status().isCreated()).andReturn());
    }

    private JsonNode importRoster(String classId, String fileName, byte[] bytes, boolean dryRun) throws Exception {
        var file = new MockMultipartFile("file", fileName, "application/octet-stream", bytes);
        return json(mvc.perform(scoped(multipart("/admin/classes/" + classId + "/children/import?dryRun=" + dryRun).file(file), admin, A))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode roster(String classId) throws Exception {
        return json(mvc.perform(scoped(get("/admin/classes/" + classId + "/children"), admin, A)).andExpect(status().isOk()).andReturn());
    }

    private static java.util.List<String> names(JsonNode rows) {
        var out = new java.util.ArrayList<String>();
        rows.forEach(r -> out.add(r.get("name").asText()));
        return out;
    }

    private static java.util.List<String> statuses(JsonNode preview) {
        var out = new java.util.ArrayList<String>();
        preview.get("rows").forEach(r -> out.add(r.get("status").asText()));
        return out;
    }

    /** A real workbook rather than a recorded file, so the fixture cannot drift from what POI actually writes. */
    private static byte[] xlsx() throws Exception {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Roster");
            String[][] rows = {{"name", "parentEmail"}, {"Hana Salem", "hana.parent@example.test"}, {"Yusuf Idris", "yusuf.parent@example.test"}};
            for (int r = 0; r < rows.length; r++) {
                var row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
