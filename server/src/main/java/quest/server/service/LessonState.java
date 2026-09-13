package quest.server.service;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.api.dto.Enums;
import quest.server.api.dto.Skills;
import quest.server.config.Json;
import quest.server.domain.LessonEntity;
import quest.server.domain.SkillEntity;
import quest.server.repository.*;

/** Transactional state changes used by the async pipeline (separate bean so the proxy applies). */
@Service
public class LessonState {
    private final LessonRepository lessons;
    private final SkillRepository skills;
    private final Json json;

    public LessonState(LessonRepository lessons, SkillRepository skills, Json json) { this.lessons = lessons; this.skills = skills; this.json = json; }

    @Transactional
    public void storeSkills(LessonEntity lesson, Skills.SkillExtraction result) {
        skills.deleteAll(skills.findByLessonIdOrderByPosition(lesson.getId()));
        int pos = 0;
        String prefix = lesson.getId().substring(0, 8);
        for (Skills.ExtractedSkill s : result.skills()) {
            SkillEntity e = new SkillEntity();
            e.setId(prefix + "-" + LessonService.slug(s.id()));
            e.setLessonId(lesson.getId()); e.setName(s.name()); e.setSubject(s.subject().wire()); e.setMethod(s.method());
            e.setExamplesJson(json.write(s.examples())); e.setSlideNumbersJson(json.write(s.slideNumbers())); e.setConfidence(s.confidence());
            e.setUnsureJson(s.unsure() == null ? null : json.write(s.unsure())); e.setConfirmed(false); e.setPosition(pos++);
            skills.save(e);
        }
    }

    @Transactional
    public void setStatus(LessonEntity lesson, Enums.Status status, String code, String message) {
        lesson.setStatus(status.wire()); lesson.setErrorCode(code); lesson.setErrorMessage(message); lesson.setUpdatedAt(Instant.now());
        lessons.save(lesson);
    }
}
