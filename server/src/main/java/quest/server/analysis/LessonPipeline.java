package quest.server.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import quest.api.dto.LessonStatus;
import quest.server.config.ApiException;
import quest.server.content.LessonRepository;

/** The two background jobs the admin panel polls for: analyse (Prompt A) and generate (Prompts B + C). */
@Service
public class LessonPipeline {
    private static final Logger log = LoggerFactory.getLogger(LessonPipeline.class);
    private final LessonRepository lessons; private final AnalysisService analysis; private final GenerationService generation; private final LessonState state;

    public LessonPipeline(LessonRepository lessons, AnalysisService analysis, GenerationService generation, LessonState state) {
        this.lessons = lessons; this.analysis = analysis; this.generation = generation; this.state = state;
    }

    @Async
    public void analyzeAsync(String lessonId) {
        try {
            var lesson = lessons.findById(lessonId).orElseThrow();
            analysis.analyze(lesson);
            state.set(lessonId, LessonStatus.NEEDS_REVIEW);
        } catch (ApiException e) { log.warn("analysis of {} failed: {}", lessonId, e.getMessage()); state.fail(lessonId, e.error().code(), e.error().message()); }
        catch (Exception e) { log.error("analysis of {} crashed", lessonId, e); state.fail(lessonId, "model_failed", "Something went wrong while reading the slides. Try again."); }
    }

    @Async
    public void generateAsync(String lessonId) {
        try {
            var lesson = lessons.findById(lessonId).orElseThrow();
            generation.generateAll(lesson);
            state.set(lessonId, LessonStatus.REVIEW);
        } catch (ApiException e) { log.warn("generation for {} failed: {}", lessonId, e.getMessage()); state.fail(lessonId, e.error().code(), e.error().message()); }
        catch (Exception e) { log.error("generation for {} crashed", lessonId, e); state.fail(lessonId, "model_failed", "Something went wrong while writing the levels. Try again."); }
    }
}
