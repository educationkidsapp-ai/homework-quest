package quest.server.service;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.api.dto.ApiError;
import quest.server.api.dto.Enums;
import quest.server.api.dto.Skills;
import quest.server.domain.LessonEntity;
import quest.server.domain.SkillEntity;
import quest.server.repository.*;

/**
 * Background steps of the lesson job. Each step persists its status so `GET /lessons/{id}` can be polled.
 * Logs carry ids and counts only — never slide text.
 */
@Service
public class LessonPipeline {
    private static final Logger log = LoggerFactory.getLogger(LessonPipeline.class);
    private final LessonRepository lessons;
    private final UploadRepository uploads;
    private final SkillRepository skills;
    private final SkillExtractionService extraction;
    private final GenerationService generation;
    private final LessonState state;

    public LessonPipeline(LessonRepository lessons, UploadRepository uploads, SkillRepository skills,
                          SkillExtractionService extraction, GenerationService generation, LessonState state) {
        this.lessons = lessons; this.uploads = uploads; this.skills = skills; this.extraction = extraction; this.generation = generation; this.state = state;
    }

    @Async
    public void extract(String lessonId) {
        LessonEntity lesson = lessons.findById(lessonId).orElse(null);
        if (lesson == null) return;
        state.setStatus(lesson, Enums.Status.READING, null, null);
        try {
            Skills.SkillExtraction result = extraction.extract(lesson, uploads.findByLessonIdAndDeletedAtIsNull(lessonId));
            state.storeSkills(lesson, result);
            state.setStatus(lesson, Enums.Status.NEEDS_CONFIRMATION, null, null);
            log.info("extract done lesson={} skills={}", lessonId, result.skills().size());
        } catch (SkillExtractionService.ExtractionException e) {
            log.warn("extract failed lesson={} code={}", lessonId, e.code);
            state.setStatus(lesson, Enums.Status.ERROR, e.code, e.getMessage());
        } catch (RuntimeException e) {
            log.error("extract crashed lesson={} {}", lessonId, e.getClass().getSimpleName(), e);
            state.setStatus(lesson, Enums.Status.ERROR, ApiError.MODEL_FAILED, "Something went wrong while reading the slides. Please try again.");
        }
    }

    @Async
    public void generate(String lessonId) {
        LessonEntity lesson = lessons.findById(lessonId).orElse(null);
        if (lesson == null) return;
        List<SkillEntity> confirmed = skills.findByLessonIdAndConfirmedTrueOrderByPosition(lessonId);
        try {
            for (SkillEntity skill : confirmed) {
                generation.generate(skill, lesson.getGrade(), Enums.Mode.NORMAL, List.of(), lesson.getPracticeLength());
            }
            state.setStatus(lesson, Enums.Status.READY, null, null);
            log.info("generate done lesson={} sets={}", lessonId, confirmed.size());
        } catch (GenerationService.GenerationException e) {
            log.warn("generate failed lesson={} reason={}", lessonId, e.getMessage());
            state.setStatus(lesson, Enums.Status.ERROR, ApiError.MODEL_FAILED, "The questions could not be made right now. Tap Try again.");
        } catch (RuntimeException e) {
            log.error("generate crashed lesson={} {}", lessonId, e.getClass().getSimpleName(), e);
            state.setStatus(lesson, Enums.Status.ERROR, ApiError.MODEL_FAILED, "Something went wrong while making the questions. Please try again.");
        }
    }
}
