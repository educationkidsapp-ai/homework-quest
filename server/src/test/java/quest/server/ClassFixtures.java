package quest.server;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.Entities.TeachingAssignmentEntity;
import quest.server.tenancy.TeachingAssignmentRepository;

/**
 * Building a class the way V7 does, in one place, because every fixture in the suite needs the same three things
 * that a pre-V7 `ClassEntity` did not have: a `name` (without it the row is a legacy leftover and
 * {@link quest.server.tenancy.TeacherScope} will not return it), a unique `joinCode`, and — when the fixture names a
 * teacher — the `teaching_assignments` row that is now what "her class" means.
 *
 * <p>The name is `<grade><subject initial>` ("1M", "1E"), which keeps the old fixtures' habit of one class per
 * (grade, subject) working while being unique inside a (school, curriculum, grade) as a section name must be.
 */
public final class ClassFixtures {
    private ClassFixtures() {}

    /** Join codes are unique platform-wide; the suite shares one database, so they come from one counter. */
    private static final AtomicInteger CODES = new AtomicInteger();

    public static ClassEntity section(ClassRepository classes, TeachingAssignmentRepository assignments, String id,
                                      String schoolId, String curriculum, int grade, String subject, String teacherId) {
        var existing = classes.findById(id);
        if (existing.isPresent()) return existing.get();
        var k = new ClassEntity();
        k.setId(id); k.setSchoolId(schoolId); k.setCurriculum(curriculum); k.setGrade(grade); k.setSubject(subject);
        k.setTeacherId(teacherId); k.setName(name(grade, subject)); k.setJoinCode(code());
        k.setActive(true); k.setJoinCodeEnabled(true); k.setCreatedAt(Instant.now());
        classes.save(k);
        if (teacherId != null && subject != null && assignments != null) assign(assignments, k, subject, teacherId);
        return k;
    }

    /** One teaching assignment, idempotent on (class, subject) so a fixture may be re-seeded. */
    public static TeachingAssignmentEntity assign(TeachingAssignmentRepository assignments, ClassEntity section,
                                                  String subject, String teacherId) {
        var existing = assignments.findByClassIdAndSubject(section.getId(), subject);
        if (existing.isPresent()) return existing.get();
        var a = new TeachingAssignmentEntity();
        a.setId("ta:" + section.getId() + ":" + subject); a.setSchoolId(section.getSchoolId());
        a.setTeacherId(teacherId); a.setClassId(section.getId()); a.setSubject(subject); a.setCreatedAt(Instant.now());
        return assignments.save(a);
    }

    private static String name(int grade, String subject) {
        return grade + (subject == null || subject.isEmpty() ? "A" : subject.substring(0, 1).toUpperCase(java.util.Locale.ROOT));
    }

    private static String code() { return "T" + String.format("%05d", CODES.incrementAndGet()); }
}
