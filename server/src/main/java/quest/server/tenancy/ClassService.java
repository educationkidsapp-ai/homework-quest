package quest.server.tenancy;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import quest.server.config.ApiException;

/** Classes are created lazily: publishing into a (school, curriculum, grade, subject) that has none makes one. */
@Service
public class ClassService {
    private final ClassRepository classes;
    public ClassService(ClassRepository classes) { this.classes = classes; }

    public Entities.ClassEntity get(String id) { return classes.findOneById(id).orElseThrow(() -> ApiException.notFound("class")); }

    /** The class a lesson belongs to, created with no teacher when the school has none for that combination yet. */
    public Entities.ClassEntity findOrCreate(String schoolId, String curriculum, int grade, String subject) {
        return classes.findFirstBySchoolIdAndCurriculumAndGradeAndSubjectOrderByCreatedAtAsc(schoolId, curriculum, grade, subject)
                .orElseGet(() -> create(schoolId + ":" + curriculum + ":" + grade + ":" + subject, schoolId, curriculum, grade, subject, null));
    }

    /**
     * The class a teacher publishes into (§5: "the class gets `teacher_id` = her user id"): hers if she already has
     * one, otherwise the school's unassigned class for that combination, which she claims, otherwise a new one.
     */
    public Entities.ClassEntity findOrCreateForTeacher(String schoolId, String curriculum, int grade, String subject, String teacherId) {
        if (teacherId == null) return findOrCreate(schoolId, curriculum, grade, subject);
        var mine = classes.findFirstBySchoolIdAndCurriculumAndGradeAndSubjectAndTeacherIdOrderByCreatedAtAsc(schoolId, curriculum, grade, subject, teacherId);
        if (mine.isPresent()) return mine.get();
        var free = classes.findBySchoolIdAndCurriculumAndGradeAndSubject(schoolId, curriculum, grade, subject).stream().filter(k -> k.getTeacherId() == null).findFirst();
        if (free.isPresent()) { var k = free.get(); k.setTeacherId(teacherId); return classes.save(k); }
        return create(schoolId + ":" + curriculum + ":" + grade + ":" + subject + ":" + UUID.randomUUID().toString().substring(0, 8), schoolId, curriculum, grade, subject, teacherId);
    }

    private Entities.ClassEntity create(String id, String schoolId, String curriculum, int grade, String subject, String teacherId) {
        var e = new Entities.ClassEntity();
        e.setId(id);
        e.setSchoolId(schoolId); e.setCurriculum(curriculum); e.setGrade(grade); e.setSubject(subject); e.setTeacherId(teacherId); e.setCreatedAt(Instant.now());
        return classes.save(e);
    }
}
