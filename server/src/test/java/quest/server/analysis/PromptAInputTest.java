package quest.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.Entities.SourceFileEntity;
import quest.server.content.LessonRepository;
import quest.server.content.PageImageRepository;
import quest.server.content.SkillRepository;
import quest.server.content.SourceFileRepository;
import quest.server.files.FileStore;

/**
 * CR4 §4's central claim, tested where it is decided: Prompt A is handed the Markdown the Convert step wrote and
 * <em>nothing else</em>. The recording client says {@code acceptsPdf()}, which is what used to make the PDF go out
 * with the turn, and it still receives no attachment at all.
 */
class PromptAInputTest {
    private static final String MARKDOWN = "# Hot Soup for Mummy\n\n## Page 1\n\nMummy is in bed. She has a cold.";

    @Test void prompt_a_reads_the_markdown_and_attaches_nothing() {
        var f = new Recording();
        var lesson = lesson();
        service(f, Optional.of(MARKDOWN)).analyze(lesson);

        assertThat(f.attachments).isEmpty();
        assertThat(f.user).contains("Mummy is in bed. She has a cold.")
                .contains("--- slides.pdf ---")
                .contains("converted to Markdown on our side")
                .contains("there are no images");
    }

    @Test void a_file_that_was_never_converted_is_an_error_the_teacher_can_act_on() {
        var f = new Recording();
        assertThatThrownBy(() -> service(f, Optional.empty()).analyze(lesson()))
                .isInstanceOf(ApiException.class).hasMessageContaining("markdown_missing").hasMessageContaining("slides.pdf");
        assertThat(f.user).isNull();
    }

    /** A recording client that would happily take a PDF — the point being that it is never offered one. */
    private static final class Recording implements LlmClient {
        private final SampleLlmClient inner = new SampleLlmClient();
        String user; List<Attachment> attachments;
        @Override public String name() { return "recording"; }
        @Override public boolean acceptsPdf() { return true; }
        @Override public Result complete(String system, String user, List<Attachment> attachments) {
            this.user = user; this.attachments = attachments;
            return inner.complete(system, user, attachments);
        }
    }

    private static LessonEntity lesson() {
        var l = new LessonEntity();
        l.setId("11111111-2222-3333"); l.setCourseId("british/1"); l.setSubject("english"); l.setStatus("draft");
        l.setDate(LocalDate.of(2026, 10, 1)); l.setCreatedAt(Instant.now()); l.setUpdatedAt(Instant.now());
        return l;
    }

    private static AnalysisService service(LlmClient llm, Optional<String> markdown) {
        var file = new SourceFileEntity();
        file.setId("file-1"); file.setLessonId("11111111-2222-3333"); file.setFileName("slides.pdf"); file.setKind("pdf");
        file.setMimeType("application/pdf"); file.setFileHash("abc"); file.setStoragePath("uploads/x/slides.pdf"); file.setCreatedAt(Instant.now());

        var lessons = Mockito.mock(LessonRepository.class);
        Mockito.when(lessons.save(Mockito.any())).thenAnswer(i -> i.getArgument(0));
        Mockito.when(lessons.findById(Mockito.anyString())).thenAnswer(i -> Optional.of(lesson()));
        var sourceFiles = Mockito.mock(SourceFileRepository.class);
        Mockito.when(sourceFiles.findByLessonIdOrderByCreatedAt(Mockito.anyString())).thenReturn(List.of(file));
        var pageImages = Mockito.mock(PageImageRepository.class);
        Mockito.when(pageImages.findByLessonIdOrderByPageNumber(Mockito.anyString())).thenReturn(new ArrayList<>());
        var skills = Mockito.mock(SkillRepository.class);
        Mockito.when(skills.findByLessonIdOrderByPosition(Mockito.anyString())).thenReturn(new ArrayList<>());
        Mockito.when(skills.save(Mockito.any())).thenAnswer(i -> i.getArgument(0));
        var cache = Mockito.mock(AnalysisCacheRepository.class);
        Mockito.when(cache.findById(Mockito.anyString())).thenReturn(Optional.empty());
        Mockito.when(cache.save(Mockito.any())).thenAnswer(i -> i.getArgument(0));
        var conversion = Mockito.mock(ConversionService.class);
        Mockito.when(conversion.markdown(Mockito.any())).thenReturn(markdown);

        return new AnalysisService(lessons, sourceFiles, pageImages, skills, cache, Mockito.mock(FileStore.class), new SlideProcessor(),
                conversion, llm, new Json(new ObjectMapper()), Mockito.mock(LessonState.class));
    }
}
