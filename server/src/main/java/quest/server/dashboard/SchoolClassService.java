package quest.server.dashboard;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.auth.AuditService;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.config.ApiException;
import quest.server.schools.SchoolService;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.ClassEntity;

/**
 * The School page's Classes tab (§6 screen 5): the school's (curriculum, grade, subject) units and who teaches each.
 *
 * <p>Classes are still created lazily by publishing ({@link quest.server.tenancy.ClassService}); what this adds is
 * the Admin's side of §2 — seeing them, adding one before anybody has published into it, and assigning the teacher
 * whose lessons will land there.
 *
 * <p><strong>Scope.</strong> The school in the path is checked against the caller before anything is read
 * ({@link SchoolService#requireVisible}: another school is a 404, not a 403), and every read then names that
 * `school_id`. A teacher can only be assigned if she is a TEACHER <em>of that school</em>, which is what stops a
 * class of school A being pointed at a user of school B.
 */
@Service
public class SchoolClassService {
    private static final List<String> CURRICULA = List.of("american", "british");
    private static final List<String> SUBJECTS = List.of("math", "english");

    private final ClassRepository classes; private final UserRepository users; private final SchoolService schools;
    private final AuditService audit;

    public SchoolClassService(ClassRepository classes, UserRepository users, SchoolService schools, AuditService audit) {
        this.classes = classes; this.users = users; this.schools = schools; this.audit = audit;
    }

    /** Every class of the school, with the teacher's name resolved in one lookup rather than one per row. */
    public List<SchoolDataDto.SchoolClass> list(Principals.User caller, String schoolId) {
        schools.requireVisible(caller, schoolId);
        return decorate(classes.findBySchoolIdOrderByCurriculumAscGradeAscSubjectAsc(schoolId));
    }

    /** The same list without the visibility check, for callers that have already made it (`GET /school/teachers`). */
    List<SchoolDataDto.SchoolClass> listChecked(String schoolId) {
        return decorate(classes.findBySchoolIdOrderByCurriculumAscGradeAscSubjectAsc(schoolId));
    }

    @Transactional
    public SchoolDataDto.SchoolClass create(Principals.User caller, String schoolId, SchoolDataDto.CreateClassRequest request) {
        schools.requireVisible(caller, schoolId);
        String curriculum = oneOf(request.curriculum(), CURRICULA, "curriculum");
        String subject = oneOf(request.subject(), SUBJECTS, "subject");
        if (request.grade() < 1 || request.grade() > 12) throw ApiException.badRequest("grade " + request.grade() + " is outside 1–12");
        var teacher = request.teacherId() == null ? null : teacherOf(schoolId, request.teacherId());

        // `classes` is unique on (school, curriculum, grade, subject, teacher), so the duplicate is answered as a 409
        // rather than left to surface as a constraint violation the caller cannot read.
        String teacherId = teacher == null ? null : teacher.getId();
        boolean taken = classes.findBySchoolIdAndCurriculumAndGradeAndSubject(schoolId, curriculum, request.grade(), subject)
                .stream().anyMatch(k -> java.util.Objects.equals(k.getTeacherId(), teacherId));
        if (taken) throw ApiException.conflict("That class already exists" + (teacherId == null ? "" : " for that teacher") + ".");

        // The same identity the lazy path uses, so publishing into this combination finds this row rather than making
        // a second one; a suffix keeps a second class of the same combination (a parallel form) possible.
        String base = schoolId + ":" + curriculum + ":" + request.grade() + ":" + subject;
        String id = classes.findById(base).isPresent() ? base + ":" + UUID.randomUUID().toString().substring(0, 8) : base;

        var entity = new ClassEntity();
        entity.setId(id); entity.setSchoolId(schoolId); entity.setCurriculum(curriculum); entity.setGrade(request.grade());
        entity.setSubject(subject); entity.setTeacherId(teacher == null ? null : teacher.getId()); entity.setCreatedAt(Instant.now());
        classes.save(entity);
        audit.record(caller.userId(), "class.create", "class", id, schoolId,
                Map.of("curriculum", curriculum, "grade", request.grade(), "subject", subject));
        return toDto(entity, teacher == null ? null : name(teacher));
    }

    /** §6 screen 5: assigning the class's teacher, or handing it back to nobody. */
    @Transactional
    public SchoolDataDto.SchoolClass update(Principals.User caller, String schoolId, String classId,
                                            SchoolDataDto.UpdateClassRequest request) {
        schools.requireVisible(caller, schoolId);
        var entity = classes.findOneById(classId).filter(k -> schoolId.equals(k.getSchoolId()))
                .orElseThrow(() -> ApiException.notFound("class"));
        UserEntity teacher = null;
        if (request.clearTeacher()) entity.setTeacherId(null);
        else if (request.teacherId() != null && !request.teacherId().isBlank()) {
            teacher = teacherOf(schoolId, request.teacherId());
            if (!teacher.getId().equals(entity.getTeacherId())
                    && classes.findFirstBySchoolIdAndCurriculumAndGradeAndSubjectAndTeacherIdOrderByCreatedAtAsc(
                            schoolId, entity.getCurriculum(), entity.getGrade(), entity.getSubject(), teacher.getId()).isPresent())
                throw ApiException.conflict("She already has a class for that curriculum, grade and subject.");
            entity.setTeacherId(teacher.getId());
        }
        classes.save(entity);
        audit.record(caller.userId(), "class.assign", "class", entity.getId(), schoolId,
                Map.of("teacherId", entity.getTeacherId() == null ? "" : entity.getTeacherId()));
        return toDto(entity, teacher == null ? teacherName(entity.getTeacherId()) : name(teacher));
    }

    // ---------------------------------------------------------------- helpers

    private List<SchoolDataDto.SchoolClass> decorate(List<ClassEntity> rows) {
        var teacherIds = rows.stream().map(ClassEntity::getTeacherId).filter(java.util.Objects::nonNull).distinct().toList();
        var names = new LinkedHashMap<String, String>();
        if (!teacherIds.isEmpty()) users.findAllById(teacherIds).forEach(u -> names.put(u.getId(), name(u)));
        return rows.stream().map(k -> toDto(k, names.get(k.getTeacherId()))).toList();
    }

    /**
     * The teacher a class may be handed to: a TEACHER row of <em>this</em> school that is not disabled. `findById`
     * bypasses the tenant filter by design, so the school is compared here rather than assumed.
     */
    private UserEntity teacherOf(String schoolId, String teacherId) {
        return users.findById(teacherId)
                .filter(u -> schoolId.equals(u.getSchoolId()) && "TEACHER".equals(u.getRole()) && !"disabled".equals(u.getStatus()))
                .orElseThrow(() -> ApiException.badRequest("That teacher is not a teacher of this school."));
    }

    private String teacherName(String teacherId) {
        return teacherId == null ? null : users.findById(teacherId).map(SchoolClassService::name).orElse(null);
    }

    private static String name(UserEntity user) {
        return user.getDisplayName() == null || user.getDisplayName().isBlank() ? user.getEmail() : user.getDisplayName();
    }

    private static SchoolDataDto.SchoolClass toDto(ClassEntity k, String teacherName) {
        return new SchoolDataDto.SchoolClass(k.getId(), k.getSchoolId(), k.getCurriculum(), k.getGrade(), k.getSubject(),
                k.getTeacherId(), teacherName, k.getCreatedAt() == null ? 0 : k.getCreatedAt().toEpochMilli());
    }

    private static String oneOf(String value, List<String> allowed, String field) {
        String cleaned = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(cleaned)) throw ApiException.badRequest(field + " is " + String.join(" or ", allowed) + ", not " + value);
        return cleaned;
    }
}
