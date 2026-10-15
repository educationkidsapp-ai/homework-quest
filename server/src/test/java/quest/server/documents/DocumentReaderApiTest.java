package quest.server.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.server.ApiTestSupport;
import quest.server.analysis.LlmClient;
import quest.server.analysis.ProcessRunner;
import quest.server.analysis.SampleLlmClient;
import quest.server.auth.AdminJwtService;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.UserRepository;
import quest.server.flags.FlagKeys;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.SchoolRepository;

/**
 * Feature 26: a circular is read, <strong>the file is gone</strong>, and nothing is sent.
 *
 * <p>Two binaries the test machine does not have, so both are driven through the {@link ProcessRunner} the
 * extraction already shells out through ({@code ConversionServiceTest}'s way):
 *
 * <ul>
 *   <li><strong>by default it is not installed</strong> — an {@link IOException}, which is what a missing binary
 *       really is — so a PDF takes the built-in PDFBox route the `test` profile allows, exactly as the local e2e
 *       does;</li>
 *   <li><strong>the image test stubs it to answer</strong>, because a photograph has no text layer to fall back to:
 *       Tesseract is the only way its words are ever read, and that is the path the feature is sold on.</li>
 * </ul>
 *
 * <p>The {@link LlmClient} is stubbed rather than taken from the context for {@code VoiceDraftApiTest}'s reason: the
 * happy path delegates to the real {@link SampleLlmClient}, which is deterministic, and the invented-date, `rephrase`
 * and `model_failed` paths each need a model that answers one particular way.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentReaderApiTest extends ApiTestSupport {
    private static final String SCHOOL = "dr-school", OTHER = "dr-other";
    private static final String NADA = "dr-nada", HIND = "dr-hind", FAR_AWAY = "dr-faraway";
    private static final String TEACHER = "dr-teacher", COORDINATOR = "dr-coord", SUPERVISOR = "dr-floor";
    /** The day every test reads as "now", so the sweep's cutoff is one fixed instant. */
    private static final LocalDate TODAY = LocalDate.of(2027, 2, 1);

    /** The circular, as plain lines a Helvetica PDF can carry and the built-in extractor can read back. */
    private static final List<String> CIRCULAR = List.of(
            "Ministry circular 12/1448 - mid-term tests",
            "The mid-term tests begin on 2027-03-08 and end on 2027-03-12.",
            "Parents must be told one week before.",
            "- Publish the timetable",
            "- Collect the consent forms",
            "Contact the department on 0551234567 or at circulars@example.edu.");

    @MockitoBean LlmClient llm;
    @MockitoBean ProcessRunner runner;
    private final SampleLlmClient sample = new SampleLlmClient();

    @Autowired SchoolRepository schools;
    @Autowired UserRepository users;
    @Autowired DocumentSummaryRepository summaries;
    @Autowired DocumentRetention retention;
    @Autowired quest.server.ai.AiCallRepository aiCalls;
    @Autowired quest.server.flags.SchoolFlagRepository schoolFlags;
    @Autowired quest.server.flags.FeatureFlags featureFlags;
    @Autowired quest.server.files.FileStore files;
    @Autowired quest.server.config.QuestProperties props;
    @Autowired AdminJwtService jwt;

    @BeforeAll void loadTwoSchools() {
        school(SCHOOL, "Reader Primary", "DRDOC1"); school(OTHER, "Distant Primary", "DRDOC2");
        staff(NADA, SCHOOL, "Nada", "MANAGERIAL"); staff(HIND, SCHOOL, "Hind", "MANAGERIAL");
        staff(TEACHER, SCHOOL, "Ms Reem", "TEACHER"); staff(COORDINATOR, SCHOOL, "Ms Ohoud", "COORDINATOR");
        staff(SUPERVISOR, SCHOOL, "Mr Majed", "FLOOR_SUPERVISOR");
        staff(FAR_AWAY, OTHER, "Mrs Salma", "MANAGERIAL");
        for (String school : List.of(SCHOOL, OTHER)) flag(school, FlagKeys.DOCUMENT_READER, true);
    }

    @AfterAll void takeItBackOut() {
        summaries.deleteAll(summaries.findAll().stream().filter(d -> d.getSchoolId().startsWith("dr-")).toList());
        aiCalls.deleteAll(aiCalls.findAll().stream().filter(c -> c.getSchoolId() != null && c.getSchoolId().startsWith("dr-")).toList());
        var staff = new ArrayList<UserEntity>(users.findBySchoolId(SCHOOL));
        staff.addAll(users.findBySchoolId(OTHER));
        users.deleteAll(staff);
    }

    /** No binary and the real sample model: the two defaults every test but its own overrides. */
    @BeforeEach void pinTheDayAndTheTools() throws IOException {
        clock.pinTo(TODAY);
        org.mockito.Mockito.doThrow(new IOException("anydoc is not installed on this machine")).when(runner).run(any(), any(), any());
        when(llm.complete(any(), any(), any()))
                .thenAnswer(i -> sample.complete(i.getArgument(0), i.getArgument(1), i.getArgument(2)));
    }

    @AfterEach void release() { clock.release(); }

    // ---------------------------------------------------------------- what she gets back

    @Test void a_pdf_circular_becomes_a_bilingual_summary_with_its_dates_and_actions() throws Exception {
        var summary = read(NADA, "circular.pdf", pdf(1, CIRCULAR), status().isCreated());

        assertThat(summary.get("id").asText()).isNotBlank();
        assertThat(summary.get("fileName").asText()).isEqualTo("circular.pdf");
        assertThat(summary.get("pages").asInt()).isOne();
        assertThat(summary.get("title").asText()).contains("Ministry circular 12/1448");
        assertThat(summary.get("summaryAr").asText()).contains("mid-term tests");
        assertThat(summary.get("summaryEn").asText()).isNotBlank();
        assertThat(dates(summary)).as("the two days the circular names, as real dates").containsExactly("2027-03-08", "2027-03-12");
        assertThat(strings(summary.get("actionsAr"))).containsExactly("Publish the timetable", "Collect the consent forms");
        assertThat(strings(summary.get("actionsEn"))).hasSameSizeAs(strings(summary.get("actionsAr")));
        assertThat(summary.get("announcement").get("bodyAr").asText())
                .as("parents are mentioned, so there is something for the compose sheet to open with").isNotBlank();

        var row = summaries.findById(summary.get("id").asText()).orElseThrow();
        assertThat(row.getAuthorId()).isEqualTo(NADA);
        assertThat(row.getSchoolId()).isEqualTo(SCHOOL);
        assertThat(summaries.byAuthor(SCHOOL, NADA, PageRequest.of(0, 20)))
                .extracting(Entities.DocumentSummaryEntity::getId).as("hers to re-open, newest first").contains(row.getId());
        // Her list and one row read back the same thing, and both are hers alone.
        assertThat(ids(json(mvc.perform(as(get("/management/documents"), token(NADA, SCHOOL, "MANAGERIAL")))
                .andExpect(status().isOk()).andReturn()))).contains(row.getId());
        assertThat(json(mvc.perform(as(get("/management/documents/" + row.getId()), token(NADA, SCHOOL, "MANAGERIAL")))
                .andExpect(status().isOk()).andReturn()).get("title").asText()).isEqualTo(summary.get("title").asText());
    }

    /**
     * A photograph has no text layer, so Tesseract is the only way it is ever read. The runner is stubbed to answer
     * as the real binary would, and the command it is handed is asserted: `eng+ara`, because half the circulars are
     * Arabic and half the stamps on them are English.
     */
    @Test void a_photograph_is_read_with_arabic_and_english_ocr() throws Exception {
        var commands = ocrAnswering(String.join("\n", CIRCULAR));

        var summary = read(NADA, "letter.png", png(600, 800), status().isCreated());

        assertThat(summary.get("pages").asInt()).isOne();
        assertThat(summary.get("summaryAr").asText()).contains("mid-term tests");
        assertThat(dates(summary)).containsExactly("2027-03-08", "2027-03-12");
        assertThat(commands).hasSize(1);
        assertThat(commands.get(0)).containsSubsequence("tesseract", "stdout", "-l", "eng+ara");
    }

    // ---------------------------------------------------------------- the file is gone

    /**
     * The spec's first privacy line. Nothing of the upload survives the request: no object in the bucket — the whole
     * store is counted, not one guessed path — and no temporary directory of the two the extraction uses. It is
     * asserted on a successful read <em>and</em> on one the model refused, because a `finally` that only runs when
     * the answer was good is the bug this test exists for.
     */
    @Test void nothing_of_the_uploaded_file_is_left_behind() throws Exception {
        var storeBefore = storedFiles();
        var tempBefore = extractionTempDirs();

        read(NADA, "circular.pdf", pdf(2, CIRCULAR), status().isCreated());
        assertThat(storedFiles()).as("the circular is never written to the bucket").isEqualTo(storeBefore);
        assertThat(extractionTempDirs()).as("and its temporary file is deleted in a `finally`").isEqualTo(tempBefore);

        answers("{\"nonsense\":true}");
        read(NADA, "circular.pdf", pdf(2, CIRCULAR), status().isUnprocessableEntity());
        assertThat(storedFiles()).as("a refused read leaves nothing either").isEqualTo(storeBefore);
        assertThat(extractionTempDirs()).isEqualTo(tempBefore);
    }

    // ---------------------------------------------------------------- the edges of the file

    @Test void a_document_over_ten_megabytes_is_too_large_and_nothing_is_read() throws Exception {
        long before = summaries.count();
        var error = read(NADA, "huge.pdf", new byte[11 * 1024 * 1024], status().isPayloadTooLarge());
        assertThat(error.get("code").asText()).isEqualTo(quest.api.dto.ApiError.TOO_LARGE);
        assertThat(summaries.count()).isEqualTo(before);
        verify(llm, times(0)).complete(any(), any(), any());
    }

    @Test void a_document_over_twenty_pages_is_too_long() throws Exception {
        long before = summaries.count();
        var error = read(NADA, "textbook.pdf", pdf(21, CIRCULAR), status().isUnprocessableEntity());
        assertThat(error.get("code").asText()).isEqualTo(quest.api.dto.ApiError.TOO_LONG);
        assertThat(error.get("message").asText()).contains("21");
        assertThat(summaries.count()).as("a refused document is not read and not stored").isEqualTo(before);
        verify(llm, times(0)).complete(any(), any(), any());
    }

    @Test void a_file_that_is_neither_a_document_nor_an_image_is_refused_by_its_first_bytes() throws Exception {
        // A .pdf by name and a zip archive by content: the name decides nothing.
        var error = read(NADA, "circular.pdf", "<html><body>not a circular</body></html>".getBytes(), status().isBadRequest());
        assertThat(error.get("code").asText()).isEqualTo("bad_request");
        read(NADA, "empty.pdf", new byte[0], status().isBadRequest());
    }

    // ---------------------------------------------------------------- the edges of the model

    /**
     * The spec's "every key date is a real calendar date". The model answers with the 30th of February, which parses
     * as nothing; the second attempt is told exactly that, and when it says the same the leader is asked for another
     * copy rather than shown a date that does not exist.
     */
    @Test void an_invented_date_is_retried_once_and_then_asks_for_another_copy() throws Exception {
        answers("{\"title\":\"تعميم\",\"summaryAr\":\"تعميم\",\"summaryEn\":\"A circular.\","
                + "\"keyDates\":[{\"date\":\"2027-02-30\",\"labelAr\":\"الاختبارات\",\"labelEn\":\"Tests\"}]}");
        long before = summaries.count();

        var error = read(NADA, "circular.pdf", pdf(1, CIRCULAR), status().isUnprocessableEntity());
        assertThat(error.get("code").asText()).isEqualTo(DocumentReaderService.REPHRASE);
        assertThat(error.get("message").asText()).contains("2027-02-30");

        var turns = ArgumentCaptor.forClass(String.class);
        verify(llm, times(2)).complete(any(), turns.capture(), any());
        assertThat(turns.getAllValues().get(1)).as("the second attempt is told what was wrong with the first")
                .contains("rejected by the validator").contains("2027-02-30");
        assertThat(summaries.count()).as("a refused answer stores nothing").isEqualTo(before);
    }

    @Test void a_provider_that_cannot_answer_is_a_bad_gateway() throws Exception {
        org.mockito.Mockito.doThrow(new LlmClient.LlmException("the provider is busy", null, true))
                .when(llm).complete(any(), any(), any());
        long before = summaries.count();

        var error = read(NADA, "circular.pdf", pdf(1, CIRCULAR), status().isBadGateway());
        assertThat(error.get("code").asText()).isEqualTo(quest.api.dto.ApiError.MODEL_FAILED);
        assertThat(summaries.count()).isEqualTo(before);
    }

    /** The guard the task opts into: a telephone number and an e-mail address in the circular do not reach the provider. */
    @Test void the_provider_is_not_told_a_telephone_number_or_an_email_address() throws Exception {
        read(NADA, "circular.pdf", pdf(1, CIRCULAR), status().isCreated());

        var turn = ArgumentCaptor.forClass(String.class);
        verify(llm).complete(any(), turn.capture(), any());
        String sent = turn.getValue();
        assertThat(sent).contains("mid-term tests").contains(TODAY.toString()).contains("circular.pdf");
        assertThat(sent).doesNotContain("0551234567").doesNotContain("circulars@example.edu")
                .contains(quest.server.ai.AiInputGuard.PHONE_MASK).contains(quest.server.ai.AiInputGuard.EMAIL_MASK);
        assertThat(sent).as("the dates the circular really gives are not masked away with them").contains("2027-03-08");
    }

    // ---------------------------------------------------------------- who may ask, and when

    /** Only the two leadership roles; the two other dashboard roles, the floor supervisor and a parent are each 403. */
    @Test void a_teacher_a_coordinator_a_supervisor_and_a_parent_are_all_refused() throws Exception {
        long before = summaries.count();
        byte[] bytes = pdf(1, CIRCULAR);
        for (String[] who : new String[][] {{TEACHER, "TEACHER"}, {COORDINATOR, "COORDINATOR"}, {SUPERVISOR, "FLOOR_SUPERVISOR"}}) {
            mvc.perform(as(upload("circular.pdf", bytes), token(who[0], SCHOOL, who[1]))).andExpect(status().isForbidden());
            mvc.perform(as(get("/management/documents"), token(who[0], SCHOOL, who[1]))).andExpect(status().isForbidden());
        }
        mvc.perform(upload("circular.pdf", bytes).header("Authorization", PARENT)).andExpect(status().isForbidden());
        mvc.perform(get("/management/documents").header("Authorization", PARENT)).andExpect(status().isForbidden());
        assertThat(summaries.count()).as("nobody refused wrote a row").isEqualTo(before);
    }

    @Test void the_routes_are_not_there_while_the_switch_is_off() throws Exception {
        flag(SCHOOL, FlagKeys.DOCUMENT_READER, false);
        try {
            read(NADA, "circular.pdf", pdf(1, CIRCULAR), status().isNotFound());
            mvc.perform(as(get("/management/documents"), token(NADA, SCHOOL, "MANAGERIAL"))).andExpect(status().isNotFound());
        } finally {
            flag(SCHOOL, FlagKeys.DOCUMENT_READER, true);
        }
    }

    /** What a leader read is hers: another author of her own school is a 404, and another school's is the same answer. */
    @Test void a_summary_of_another_author_or_another_school_is_not_found() throws Exception {
        String hers = read(NADA, "circular.pdf", pdf(1, CIRCULAR), status().isCreated()).get("id").asText();
        String elsewhere = read(FAR_AWAY, "circular.pdf", pdf(1, CIRCULAR), status().isCreated()).get("id").asText();

        refused(HIND, SCHOOL, hers);
        refused(NADA, SCHOOL, elsewhere);
        refused(NADA, SCHOOL, "dr-no-such-summary");
        assertThat(json(mvc.perform(as(get("/management/documents"), token(HIND, SCHOOL, "MANAGERIAL")))
                .andExpect(status().isOk()).andReturn()).size()).as("and it is not in her list either").isZero();
        assertThat(summaries.findById(elsewhere).orElseThrow().getSchoolId()).isEqualTo(OTHER);
    }

    // ---------------------------------------------------------------- the retention sweep

    /** A circular is somebody else's business that passed through the school; a month later the read of it is gone. */
    @Test void a_summary_is_deleted_after_thirty_days() throws Exception {
        String id = read(NADA, "circular.pdf", pdf(1, CIRCULAR), status().isCreated()).get("id").asText();

        clock.pinTo(TODAY.plusDays(29));
        retention.sweep();
        assertThat(summaries.findById(id)).as("twenty-nine days is still hers to re-read").isPresent();

        clock.pinTo(TODAY.plusDays(31));
        retention.sweep();
        assertThat(summaries.findById(id)).as("a month later it is gone").isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode read(String userId, String fileName, byte[] bytes, ResultMatcher expected) throws Exception {
        return json(mvc.perform(as(upload(fileName, bytes),
                token(userId, userId.equals(FAR_AWAY) ? OTHER : SCHOOL, "MANAGERIAL"))).andExpect(expected).andReturn());
    }

    private MockHttpServletRequestBuilder upload(String fileName, byte[] bytes) {
        return multipart("/management/documents/read").file(new MockMultipartFile("file", fileName, null, bytes));
    }

    private void refused(String userId, String schoolId, String id) throws Exception {
        var error = json(mvc.perform(as(get("/management/documents/" + id), token(userId, schoolId, "MANAGERIAL")))
                .andExpect(status().isNotFound()).andReturn());
        assertThat(error.get("code").asText()).isEqualTo("not_found");
    }

    private void answers(String json) {
        org.mockito.Mockito.doReturn(new LlmClient.Result(json, 20, 10)).when(llm).complete(any(), any(), any());
    }

    /** Tesseract, installed and answering {@code text}; the returned list collects every command it was handed. */
    private List<List<String>> ocrAnswering(String text) throws IOException {
        var commands = new ArrayList<List<String>>();
        org.mockito.Mockito.doAnswer(i -> {
            commands.add(i.getArgument(0));
            return new ProcessRunner.Ran(0, text, "");
        }).when(runner).run(any(), any(), any());
        return commands;
    }

    private static List<String> ids(JsonNode list) {
        var out = new ArrayList<String>();
        for (JsonNode row : list) out.add(row.get("id").asText());
        return out;
    }

    private static List<String> dates(JsonNode summary) {
        var out = new ArrayList<String>();
        for (JsonNode date : summary.get("keyDates")) out.add(date.get("date").asText());
        return out;
    }

    private static List<String> strings(JsonNode array) {
        var out = new ArrayList<String>();
        for (JsonNode line : array) out.add(line.asText());
        return out;
    }

    // ---------------------------------------------------------------- what is on disk

    /** Every path in the test bucket. Compared whole, so an object written under any name at all shows up. */
    private Set<String> storedFiles() throws IOException {
        Path root = Path.of(props.storage().localDir());
        if (!Files.exists(root)) return Set.of();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).map(p -> root.relativize(p).toString()).collect(java.util.stream.Collectors.toSet());
        }
    }

    /** The two temporary directories `DocumentText` creates, by their own prefixes — never the test bucket's. */
    private Set<String> extractionTempDirs() throws IOException {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> list = Files.list(tmp)) {
            return list.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("quest-anydoc") || n.startsWith("quest-ocr") || n.startsWith("quest-doc"))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    // ---------------------------------------------------------------- the fixtures

    /** A PDF with a real text layer, so the built-in extractor has something to read back out of it. */
    private static byte[] pdf(int pages, List<String> lines) throws Exception {
        try (var doc = new PDDocument()) {
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 0; i < pages; i++) {
                var page = new PDPage(); doc.addPage(page);
                try (var content = new PDPageContentStream(doc, page)) {
                    content.beginText();
                    content.setFont(font, 11);
                    content.setLeading(15);
                    content.newLineAtOffset(40, 740);
                    for (String line : lines) { content.showText(line); content.newLine(); }
                    content.endText();
                }
            }
            var out = new ByteArrayOutputStream(); doc.save(out); return out.toByteArray();
        }
    }

    /** A plain white PNG: a photograph of a page, as far as anything but Tesseract is concerned. */
    private static byte[] png(int width, int height) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        try { g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, width, height); } finally { g.dispose(); }
        var out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String token) {
        return b.header("Authorization", "Bearer " + token);
    }

    private String token(String userId, String schoolId, String role) {
        return jwt.issue(userId, userId + "@dr.test", role, schoolId).token();
    }

    private void school(String id, String name, String code) {
        schools.findById(id).orElseGet(() -> {
            var s = new SchoolEntity();
            s.setId(id); s.setName(name); s.setCode(code);
            s.setCurriculumOptionsJson("[\"british\",\"american\"]"); s.setGradeOptionsJson("[1,2,3,4,5,6]");
            s.setStatus("active"); s.setCreatedAt(Instant.now());
            return schools.save(s);
        });
    }

    private void staff(String id, String schoolId, String displayName, String role) {
        var u = users.findById(id).orElseGet(UserEntity::new);
        u.setId(id); u.setSchoolId(schoolId); u.setEmail(id + "@dr.test"); u.setPasswordHash("x");
        u.setRole(role); u.setStatus("active"); u.setDisplayName(displayName);
        if (u.getCreatedAt() == null) u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        users.save(u);
    }

    private void flag(String schoolId, String key, boolean enabled) {
        var row = schoolFlags.findOne(schoolId, key).orElseGet(() -> {
            var fresh = new quest.server.flags.Entities.SchoolFeatureFlagEntity();
            fresh.setSchoolId(schoolId); fresh.setFlagKey(key);
            return fresh;
        });
        row.setEnabled(enabled); row.setUpdatedBy("test"); row.setUpdatedAt(Instant.now());
        schoolFlags.save(row);
        featureFlags.invalidate(schoolId);
    }
}
