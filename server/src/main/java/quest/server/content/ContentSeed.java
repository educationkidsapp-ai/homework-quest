package quest.server.content;

import java.time.Instant;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import quest.api.CacheKeys;
import quest.api.dto.Play;
import quest.api.dto.PublishedLesson;
import quest.api.samples.Seeds;
import quest.server.config.Json;
import quest.server.tenancy.ClassService;
import quest.server.tenancy.TenantContext;

/** Dev database seed (profiles local, dev, h2): the three §6 lessons from shared-api, published with all levels and the variant. */
@Component
@Profile({"local", "dev", "h2", "test", "qa"})
public class ContentSeed implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(ContentSeed.class);
    private final LessonRepository lessons; private final SkillRepository skills; private final LessonStore store; private final Json json; private final ClassService classes;
    public ContentSeed(LessonRepository lessons, SkillRepository skills, LessonStore store, Json json, ClassService classes) { this.lessons = lessons; this.skills = skills; this.store = store; this.json = json; this.classes = classes; }

    @Override @Transactional
    public void run(String... args) {
        for (PublishedLesson seed : Seeds.INSTANCE.getLessons()) {
            if (lessons.existsById(seed.getId())) continue;
            var e = new Entities.LessonEntity();
            e.setId(seed.getId()); e.setCourseId(seed.getCourse().getKey()); e.setSubject(seed.getSubject().name().toLowerCase());
            e.setDate(LocalDate.of(seed.getDate().getYear(), seed.getDate().getMonthNumber(), seed.getDate().getDayOfMonth()));
            e.setStatus("published"); e.setVersion(seed.getVersion()); e.setTitle(seed.getTitle()); e.setSourceHash("seed-" + seed.getId());
            e.setCreatedBy("seed"); e.setCreatedAt(Instant.now()); e.setUpdatedAt(Instant.now()); e.setPublishedAt(Instant.now());
            e.setSchoolId(TenantContext.DEFAULT_SCHOOL);
            e.setClassId(classes.findOrCreateSection(TenantContext.DEFAULT_SCHOOL, seed.getCourse().getCurriculum().name().toLowerCase(), seed.getCourse().getGrade()).getId());
            lessons.save(e);
            int pos = 0;
            for (var s : seed.getSkills()) {
                var se = new Entities.SkillEntity();
                se.setId(s.getId()); se.setLessonId(seed.getId()); se.setName(s.getName()); se.setSubject(s.getSubject().name().toLowerCase()); se.setMethod(s.getMethod());
                se.setConfidence(1.0); se.setConfirmed(true); se.setPosition(pos++);
                skills.save(se);
            }
            for (Play p : seed.getPlays()) store.savePlay(seed.getId(), p, CacheKeys.PROMPT_B_VERSION, 0);
            store.savePlay(seed.getId(), seed.getVariant(), CacheKeys.PROMPT_B_VERSION, 0);
            store.savePanel(seed.getId(), seed.getParentPanel());
            log.info("seeded lesson {}", seed.getId());
        }
    }
}
