package quest.server.tenancy;

import java.time.Instant;
import org.springframework.stereotype.Service;
import quest.server.config.ApiException;

/** Classes are created lazily: publishing into a (school, curriculum, grade, subject) that has none makes one. */
@Service
public class ClassService {
    private final ClassRepository classes;
    public ClassService(ClassRepository classes) { this.classes = classes; }

    public Entities.ClassEntity get(String id) { return classes.findById(id).orElseThrow(() -> ApiException.notFound("class")); }

    /** The class a lesson belongs to, created with no teacher when the school has none for that combination yet. */
    public Entities.ClassEntity findOrCreate(String schoolId, String curriculum, int grade, String subject) {
        return classes.findFirstBySchoolIdAndCurriculumAndGradeAndSubjectOrderByCreatedAtAsc(schoolId, curriculum, grade, subject)
                .orElseGet(() -> {
                    var e = new Entities.ClassEntity();
                    e.setId(schoolId + ":" + curriculum + ":" + grade + ":" + subject);
                    e.setSchoolId(schoolId); e.setCurriculum(curriculum); e.setGrade(grade); e.setSubject(subject); e.setCreatedAt(Instant.now());
                    return classes.save(e);
                });
    }
}
