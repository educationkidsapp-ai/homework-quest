package quest.server.tenancy;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import quest.server.config.ApiException;

/**
 * Sections, since V7 (D14). A class is "1A" inside a curriculum and grade; it is normally created by Admin
 * ({@link quest.server.classes.SectionService}), and created lazily here for the two callers that have a
 * (curriculum, grade) and no section to put a lesson in: the content seed, and the pre-V7 `webAdmin` shape of
 * `POST /admin/lessons` where an ADMIN names no class.
 */
@Service
public class ClassService {
    private final ClassRepository classes; private final JoinCodes joinCodes;
    public ClassService(ClassRepository classes, JoinCodes joinCodes) { this.classes = classes; this.joinCodes = joinCodes; }

    public Entities.ClassEntity get(String id) { return classes.findOneById(id).orElseThrow(() -> ApiException.notFound("class")); }

    /**
     * The school's first section of a (curriculum, grade), created as "&lt;grade&gt;A" when it has none. Sibling
     * sections leave `subject` and `teacher_id` NULL, which is what keeps the surviving V4 unique index — on
     * (school, curriculum, grade, subject, teacher_id), where NULLs are distinct on PostgreSQL 16 and H2 — from ever
     * colliding.
     */
    public Entities.ClassEntity findOrCreateSection(String schoolId, String curriculum, int grade) {
        var existing = classes.findBySchoolIdAndCurriculumAndGradeAndNameIsNotNullOrderByNameAsc(schoolId, curriculum, grade);
        if (!existing.isEmpty()) return existing.getFirst();
        return create(schoolId, curriculum, grade, grade + "A");
    }

    /** A new section with a fresh join code; the id keeps the pre-V7 shape so nothing that parses it has to change. */
    public Entities.ClassEntity create(String schoolId, String curriculum, int grade, String name) {
        String base = schoolId + ":" + curriculum + ":" + grade + ":" + name.toLowerCase(java.util.Locale.ROOT);
        String id = classes.findById(base).isPresent() ? base + ":" + UUID.randomUUID().toString().substring(0, 8) : base;
        var e = new Entities.ClassEntity();
        e.setId(id); e.setSchoolId(schoolId); e.setCurriculum(curriculum); e.setGrade(grade); e.setName(name);
        e.setJoinCode(joinCodes.generate()); e.setActive(true); e.setJoinCodeEnabled(true); e.setCreatedAt(Instant.now());
        return classes.save(e);
    }
}
