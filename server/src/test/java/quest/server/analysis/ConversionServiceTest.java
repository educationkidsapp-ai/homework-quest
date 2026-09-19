package quest.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import quest.server.admin.AdminPipelineTest;
import quest.server.config.ApiException;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.Entities.SourceFileEntity;
import quest.server.content.LessonRepository;
import quest.server.content.SourceFileRepository;
import quest.server.files.FileStore;

/**
 * CR4 §4 without Node or Tesseract: the converter is driven through a stubbed {@link ProcessRunner}, so every exit
 * code anydoc can produce is exercised as the real binary would produce it.
 */
class ConversionServiceTest {

    // ---------------------------------------------------------------- anydoc's four answers

    @Test void a_clean_run_stores_the_markdown_next_to_the_original() {
        var fixture = new Fixture();
        fixture.runner.answer(0, "# Hot Soup\n\nMummy is in bed.", "");
        var f = fixture.file("slides.pdf", "pdf", "application/pdf");

        var converted = fixture.service().convert(f);

        assertThat(converted.method()).isEqualTo("anydoc");
        assertThat(converted.cacheHit()).isFalse();
        assertThat(f.getConvertStatus()).isEqualTo("ready");
        assertThat(f.getConvertErrorCode()).isNull();
        assertThat(f.getMarkdownPath()).isEqualTo(f.getStoragePath() + ".md");
        assertThat(f.getMarkdownChars()).isEqualTo("# Hot Soup\n\nMummy is in bed.".length());
        assertThat(fixture.store.text(f.getMarkdownPath())).contains("Mummy is in bed.");
        assertThat(fixture.runner.commands.get(0)).containsSubsequence("anydoc", "-o").doesNotContain("--ocr");
    }

    @Test void a_csv_is_converted_with_the_csv_format_flag() {
        var fixture = new Fixture();
        fixture.runner.answer(0, "| a | b |\n| - | - |\n| 1 | 2 |", "");
        fixture.service().convert(fixture.file("marks.csv", "csv", "text/csv"));
        assertThat(fixture.runner.commands.get(0)).containsSubsequence("--format", "csv");
    }

    @Test void exit_one_maps_the_stderr_prefix_to_the_error_code_the_teacher_is_told_about() {
        assertThat(errorCodeFor("anydoc: document is encrypted")).isEqualTo("encrypted");
        assertThat(errorCodeFor("anydoc: unsupported input: application/zip")).isEqualTo("unsupported");
        assertThat(errorCodeFor("anydoc: malformed document: xref table broken")).isEqualTo("malformed");
        assertThat(errorCodeFor("anydoc: io error: disk full")).isEqualTo("io");
        assertThat(errorCodeFor("anydoc: something new we have never seen")).isEqualTo("malformed");
        // the exact strings the image's binary writes, as `docs/runbook.md` records them
        assertThat(ConversionService.codeOf("anydoc: unsupported input: application/vnd.oasis.opendocument.text")).isEqualTo("unsupported");
        assertThat(ConversionService.codeOf("anydoc: malformed document: xref table broken")).isEqualTo("malformed");
        assertThat(ConversionService.codeOf("anydoc: document is encrypted")).isEqualTo("encrypted");
        assertThat(ConversionService.codeOf("anydoc: io error: disk full")).isEqualTo("io");
    }

    private String errorCodeFor(String stderr) {
        var fixture = new Fixture();
        fixture.runner.answer(1, "", stderr);
        var f = fixture.file("slides.pdf", "pdf", "application/pdf");
        assertThatThrownBy(() -> fixture.service().convert(f)).isInstanceOf(ApiException.class);
        assertThat(f.getConvertStatus()).isEqualTo("error");
        assertThat(f.getMarkdownPath()).isNull();
        return f.getConvertErrorCode();
    }

    @Test void exit_three_reads_the_named_pages_with_tesseract_under_page_headings() throws Exception {
        var fixture = new Fixture();
        fixture.runner.answer(3, "", "anydoc: all 2 pages need OCR");
        fixture.runner.answer(0, "Mummy is in bed.", "");
        fixture.runner.answer(0, "Alan makes hot soup.", "");
        var f = fixture.file("scan.pdf", "pdf", "application/pdf", AdminPipelineTest.pdf("one", "two"));

        var converted = fixture.service().convert(f);

        assertThat(converted.method()).isEqualTo("ocr");
        assertThat(f.getConvertMethod()).isEqualTo("ocr");
        assertThat(fixture.store.text(f.getMarkdownPath())).contains("## Page 1").contains("Mummy is in bed.").contains("## Page 2").contains("Alan makes hot soup.");
        assertThat(fixture.runner.commands.get(1)).containsSubsequence("tesseract", "stdout", "-l", "eng+ara");
    }

    @Test void only_the_pages_anydoc_named_are_sent_to_ocr() throws Exception {
        var fixture = new Fixture();
        fixture.runner.answer(3, "", "anydoc: page 2 of 2 needs OCR");
        fixture.runner.answer(0, "Only the second page.", "");
        var f = fixture.file("scan.pdf", "pdf", "application/pdf", AdminPipelineTest.pdf("one", "two"));

        fixture.service().convert(f);

        assertThat(fixture.runner.commands).hasSize(2);   // anydoc, then one tesseract
        assertThat(fixture.store.text(f.getMarkdownPath())).contains("## Page 2").doesNotContain("## Page 1");
    }

    @Test void the_stderr_grammar_is_read_the_way_anydoc_writes_it() {
        assertThat(ConversionService.pagesNeedingOcr("anydoc: page 1 of 1 needs OCR")).containsExactly(1);
        assertThat(ConversionService.pagesNeedingOcr("anydoc: pages 2, 3 of 10 need OCR")).containsExactly(2, 3);
        assertThat(ConversionService.pagesNeedingOcr("anydoc: all 5 pages need OCR")).isEmpty();
    }

    // ---------------------------------------------------------------- the limits and the cache

    @Test void markdown_over_the_cap_is_truncated_and_says_so() {
        var fixture = new Fixture(false, 100);
        fixture.runner.answer(0, "x".repeat(500), "");
        var f = fixture.file("long.docx", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        fixture.service().convert(f);

        String md = fixture.store.text(f.getMarkdownPath());
        assertThat(md).startsWith("x".repeat(100)).contains("truncated at 100 characters");
        assertThat(f.getMarkdownChars()).isEqualTo(md.length()).isLessThan(200);
    }

    @Test void the_same_bytes_are_converted_once_and_the_second_file_copies_the_markdown() {
        var fixture = new Fixture();
        fixture.runner.answer(0, "# Shared lesson", "");
        var first = fixture.file("slides.pdf", "pdf", "application/pdf");
        fixture.service().convert(first);

        var second = fixture.file("a-colleagues-copy.pdf", "pdf", "application/pdf");   // same bytes, so the same file_hash
        var converted = fixture.service().convert(second);

        assertThat(converted.cacheHit()).isTrue();
        assertThat(fixture.runner.commands).hasSize(1);   // no second anydoc run
        assertThat(second.getConvertStatus()).isEqualTo("ready");
        assertThat(second.getConvertMethod()).isEqualTo("anydoc");
        assertThat(second.getMarkdownPath()).isEqualTo(second.getStoragePath() + ".md").isNotEqualTo(first.getMarkdownPath());
        assertThat(fixture.store.text(second.getMarkdownPath())).isEqualTo("# Shared lesson");
    }

    @Test void another_schools_markdown_is_never_reused_however_identical_the_bytes() {
        var fixture = new Fixture();
        fixture.schools.put("lesson-b", "school-b");
        fixture.runner.answer(0, "# School A's reading of it", "");
        fixture.service().convert(fixture.file("worksheet.pdf", "pdf", "application/pdf"));

        fixture.runner.answer(0, "# School B converts it itself", "");
        var theirs = fixture.file("lesson-b", "worksheet.pdf", "pdf", "application/pdf", "bytes of pdf".getBytes(StandardCharsets.UTF_8));
        var converted = fixture.service().convert(theirs);

        assertThat(theirs.getFileHash()).isEqualTo(fixture.rows.get(0).getFileHash());   // the same bytes, on purpose
        assertThat(converted.cacheHit()).as("a hash is not a tenant boundary").isFalse();
        assertThat(fixture.runner.commands).hasSize(2);
        assertThat(fixture.store.text(theirs.getMarkdownPath())).isEqualTo("# School B converts it itself");
    }

    @Test void a_teachers_typed_text_is_hers_and_is_never_inherited_by_another_file() {
        var fixture = new Fixture();
        var typed = fixture.file("scan.pdf", "pdf", "application/pdf");
        fixture.service().acceptMarkdown(typed, "# My own notes, which are not in the file at all");

        fixture.runner.answer(0, "# What the file actually says", "");
        var next = fixture.file("scan.pdf", "pdf", "application/pdf");   // same bytes, same school
        var converted = fixture.service().convert(next);

        assertThat(converted.cacheHit()).isFalse();
        assertThat(converted.method()).isEqualTo("anydoc");
        assertThat(fixture.store.text(next.getMarkdownPath())).isEqualTo("# What the file actually says");
    }

    @Test void a_file_that_is_already_ready_is_not_converted_again() {
        var fixture = new Fixture();
        fixture.runner.answer(0, "# Once", "");
        var f = fixture.file("slides.pdf", "pdf", "application/pdf");
        fixture.service().convert(f);
        fixture.service().convert(f);
        assertThat(fixture.runner.commands).hasSize(1);
    }

    // ---------------------------------------------------------------- a binary that is not there

    @Test void a_missing_binary_is_an_operator_error_naming_the_environment_variable() {
        var fixture = new Fixture();
        fixture.runner.missing = true;
        var f = fixture.file("slides.pdf", "pdf", "application/pdf");

        assertThatThrownBy(() -> fixture.service().convert(f)).isInstanceOf(ApiException.class).hasMessageContaining("QUEST_ANYDOC_BIN");
        assertThat(f.getConvertErrorCode()).isEqualTo("tool_missing");
    }

    @Test void the_builtin_fallback_needs_the_profile_as_well_as_the_property() {
        var fixture = new Fixture(true, 400_000).profile("qa");   // the property says yes, the profile does not
        fixture.runner.missing = true;
        var f = fixture.file("slides.pdf", "pdf", "application/pdf");

        assertThatThrownBy(() -> fixture.service().convert(f)).isInstanceOf(ApiException.class).hasMessageContaining("QUEST_ANYDOC_BIN");
        assertThat(f.getConvertErrorCode()).isEqualTo("tool_missing");
    }

    @Test void with_the_builtin_fallback_on_a_missing_binary_falls_back_to_pdfbox() throws Exception {
        var fixture = new Fixture(true, 400_000);
        fixture.runner.missing = true;
        var f = fixture.file("slides.pdf", "pdf", "application/pdf", AdminPipelineTest.pdf("Mummy is in bed", "Alan makes soup"));

        var converted = fixture.service().convert(f);

        assertThat(converted.method()).isEqualTo("text");
        assertThat(f.getConvertStatus()).isEqualTo("ready");
        assertThat(fixture.store.text(f.getMarkdownPath())).contains("## Page 1").contains("Mummy is in bed");
    }

    @Test void a_kind_of_file_we_do_not_convert_is_unsupported_before_a_process_is_started() {
        var fixture = new Fixture();
        var f = fixture.file("notes.txt", "text", "text/plain");
        assertThatThrownBy(() -> fixture.service().convert(f)).isInstanceOf(ApiException.class);
        assertThat(f.getConvertErrorCode()).isEqualTo("unsupported");
        assertThat(fixture.runner.commands).isEmpty();
    }

    @Test void a_bucket_that_refuses_the_write_leaves_the_row_in_error_rather_than_converting() {
        var fixture = new Fixture();
        fixture.runner.answer(0, "# Fine markdown, nowhere to put it", "");
        var f = fixture.file("slides.pdf", "pdf", "application/pdf");
        fixture.store.failWritesTo(".md");

        assertThatThrownBy(() -> fixture.service().convert(f)).isInstanceOf(ApiException.class).hasMessageContaining("io");
        assertThat(f.getConvertStatus()).as("a row left at `converting` is one the editor polls forever").isEqualTo("error");
        assertThat(f.getConvertErrorCode()).isEqualTo("io");
    }

    @Test void an_ocr_request_is_recorded_on_the_file_and_honoured_by_the_next_convert() throws Exception {
        var fixture = new Fixture();
        var f = fixture.file("scan.pdf", "pdf", "application/pdf", AdminPipelineTest.pdf("one"));
        var service = fixture.service();

        service.requestOcr(f);
        assertThat(f.getConvertStatus()).isEqualTo("converting");
        assertThat(ConversionService.ocrRequested(f)).isTrue();
        assertThat(fixture.runner.commands).as("recording the request runs nothing on this thread").isEmpty();

        fixture.runner.answer(0, "Read from the picture.", "");
        var converted = service.convert(f);

        assertThat(converted.method()).isEqualTo("ocr");
        assertThat(fixture.runner.commands).hasSize(1);
        assertThat(fixture.runner.commands.get(0)).contains("tesseract");   // anydoc is not asked again
        assertThat(fixture.store.text(f.getMarkdownPath())).contains("Read from the picture.");
    }

    @Test void the_teachers_pasted_text_is_stored_as_the_extracted_markdown() {
        var fixture = new Fixture();
        var f = fixture.file("scan.pdf", "pdf", "application/pdf");

        fixture.service().acceptMarkdown(f, "# What the slides say\n\nMummy is in bed.");

        assertThat(f.getConvertStatus()).isEqualTo("ready");
        assertThat(f.getConvertMethod()).isEqualTo("text");
        assertThat(f.getConvertErrorCode()).isNull();
        assertThat(fixture.store.text(f.getMarkdownPath())).contains("Mummy is in bed.");
        assertThat(fixture.runner.commands).isEmpty();
    }

    @Test void the_file_type_comes_from_the_name_and_falls_back_to_what_the_slide_processor_called_it() {
        var fixture = new Fixture();
        assertThat(ConversionService.extensionOf(fixture.file("Slides.PPTX", "pptx", "x"))).isEqualTo("pptx");
        assertThat(ConversionService.extensionOf(fixture.file("scan", "image", "image/png"))).isEqualTo("png");
    }

    @Test void every_conversion_error_code_has_a_message_that_says_what_to_do_next() {
        for (String code : List.of("encrypted", "unsupported", "malformed", "needs_ocr", "ocr_failed", "io"))
            assertThat(LessonSteps.Messages.of(code, "detail")).as(code).contains("Type the text instead").contains("detail");
        assertThat(LessonSteps.Messages.of("tool_missing", null)).contains("QUEST_ANYDOC_BIN").contains("QUEST_TESSERACT_BIN");
    }

    // ---------------------------------------------------------------- fixture

    /** An in-memory bucket, a stubbed runner and a repository that remembers what was saved. */
    private static final class Fixture {
        final Store store = new Store();
        final StubRunner runner = new StubRunner();
        final List<SourceFileEntity> rows = new ArrayList<>();
        /** lesson id → the school it belongs to; `lesson` is the default one every `file()` lands in. */
        final Map<String, String> schools = new HashMap<>(Map.of("lesson", "school-a"));
        private final boolean builtinFallback; private final int maxChars; private String profile = "test";

        Fixture() { this(false, 400_000); }
        Fixture(boolean builtinFallback, int maxChars) { this.builtinFallback = builtinFallback; this.maxChars = maxChars; }

        Fixture profile(String p) { this.profile = p; return this; }

        ConversionService service() {
            var repo = Mockito.mock(SourceFileRepository.class);
            Mockito.when(repo.findByFileHash(Mockito.anyString())).thenAnswer(i -> rows.stream().filter(r -> r.getFileHash().equals(i.getArgument(0))).toList());
            Mockito.when(repo.save(Mockito.any())).thenAnswer(i -> i.getArgument(0));
            var lessonRepo = Mockito.mock(LessonRepository.class);
            Mockito.when(lessonRepo.findById(Mockito.anyString())).thenAnswer(i -> {
                String school = schools.get(i.<String>getArgument(0));
                if (school == null) return Optional.empty();
                var l = new LessonEntity(); l.setId(i.getArgument(0)); l.setSchoolId(school); return Optional.of(l);
            });
            var env = new org.springframework.mock.env.MockEnvironment();
            env.setActiveProfiles(profile);
            return new ConversionService(repo, lessonRepo, store, new SlideProcessor(), runner, "anydoc", "tesseract", 120, 20, maxChars, builtinFallback, env);
        }

        SourceFileEntity file(String name, String kind, String mime) { return file(name, kind, mime, ("bytes of " + kind).getBytes(StandardCharsets.UTF_8)); }

        SourceFileEntity file(String name, String kind, String mime, byte[] bytes) { return file("lesson", name, kind, mime, bytes); }

        SourceFileEntity file(String lessonId, String name, String kind, String mime, byte[] bytes) {
            var e = new SourceFileEntity();
            e.setId("f" + rows.size()); e.setLessonId(lessonId); e.setFileName(name); e.setKind(kind); e.setMimeType(mime);
            e.setFileHash(quest.api.validation.Sha256.INSTANCE.hex(bytes));
            e.setStoragePath("uploads/lesson/" + e.getId() + "/" + name); e.setSizeBytes(bytes.length); e.setCreatedAt(Instant.now());
            store.put(e.getStoragePath(), bytes, mime);
            rows.add(e);
            return e;
        }
    }

    private static final class Store implements FileStore {
        private final Map<String, Blob> blobs = new HashMap<>();
        private String failSuffix;
        void failWritesTo(String suffix) { failSuffix = suffix; }
        @Override public Stored put(String path, byte[] bytes, String mimeType) {
            if (failSuffix != null && path.endsWith(failSuffix)) throw new IllegalStateException("the bucket said no");
            blobs.put(path, new Blob(bytes, mimeType)); return new Stored(path, mimeType, bytes.length);
        }
        @Override public Optional<Blob> get(String path) { return Optional.ofNullable(blobs.get(path)); }
        @Override public void delete(String path) { blobs.remove(path); }
        String text(String path) { return new String(blobs.get(path).bytes(), StandardCharsets.UTF_8); }
    }

    /** Answers the queued exit codes in order, writing `-o <file>` for anydoc the way the binary would. */
    private static final class StubRunner implements ProcessRunner {
        final List<List<String>> commands = new ArrayList<>();
        private final List<Ran> answers = new ArrayList<>();
        boolean missing;

        void answer(int exit, String stdout, String stderr) { answers.add(new Ran(exit, stdout, stderr)); }

        @Override public Ran run(List<String> command, Path workDir, Duration timeout) throws IOException {
            if (missing) throw new IOException("Cannot run program \"" + command.get(0) + "\": No such file or directory");
            commands.add(command);
            Ran ran = answers.isEmpty() ? new Ran(0, "", "") : answers.remove(0);
            int out = command.indexOf("-o");
            if (ran.exitCode() == 0 && out >= 0) java.nio.file.Files.writeString(Path.of(command.get(out + 1)), ran.stdout());
            return ran;
        }
    }
}
