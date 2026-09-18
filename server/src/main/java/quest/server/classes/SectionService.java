package quest.server.classes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.auth.AuditService;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.children.ChildRepository;
import quest.server.config.ApiException;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.ClassService;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.Entities.TeachingAssignmentEntity;
import quest.server.tenancy.JoinCodes;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.TeachingAssignmentRepository;
import quest.server.tenancy.TenantContext;

/**
 * The Admin's Classes screen (`docs/prompts/dashboard-first-one-school.md` §6): the sections of the school, the join
 * code each one shows parents, and retiring one that is no longer taught.
 *
 * <p><strong>Scope.</strong> Nothing here takes a school from the caller: the school is `TenantContext.writeSchoolId()`
 * and every read is a filtered repository query, so an Admin who has picked a school with `X-School-Id` sees that
 * one and a Managerial caller her own. `NoSchoolIdLiteralTest` is what keeps it that way.
 *
 * <p><strong>Query count.</strong> The list is four statements whatever the number of sections: the sections, their
 * assignments, the children counted per class, and the teachers named on those assignments.
 */
@Service
public class SectionService {
    private static final List<String> CURRICULA = List.of("american", "british");
    private static final int MAX_NAME = 20;

    private final ClassRepository classes; private final ClassService sections; private final JoinCodes joinCodes;
    private final TeachingAssignmentRepository assignments; private final ChildRepository children;
    private final UserRepository users; private final SchoolRepository schools; private final TenantContext tenant; private final AuditService audit;

    public SectionService(ClassRepository classes, ClassService sections, JoinCodes joinCodes,
                          TeachingAssignmentRepository assignments, ChildRepository children, UserRepository users, SchoolRepository schools,
                          TenantContext tenant, AuditService audit) {
        this.classes = classes; this.sections = sections; this.joinCodes = joinCodes; this.assignments = assignments;
        this.children = children; this.users = users; this.schools = schools; this.tenant = tenant; this.audit = audit;
    }

    /** `GET /admin/classes`, narrowed by curriculum and grade when they are given. */
    public List<ClassDto.SchoolClass> list(String curriculum, Integer grade) {
        String schoolId = tenant.writeSchoolId();
        var rows = curriculum == null || grade == null
                ? classes.findBySchoolIdAndNameIsNotNullOrderByCurriculumAscGradeAscNameAsc(schoolId)
                : classes.findBySchoolIdAndCurriculumAndGradeAndNameIsNotNullOrderByNameAsc(schoolId, oneOf(curriculum, CURRICULA, "curriculum"), grade);
        if (curriculum != null && grade == null)
            rows = rows.stream().filter(k -> k.getCurriculum().equalsIgnoreCase(curriculum.trim())).toList();
        if (curriculum == null && grade != null) rows = rows.stream().filter(k -> k.getGrade() == grade).toList();
        return decorate(rows);
    }

    /** The counts and the assignment total for a page of sections: three statements, never one per row. */
    private List<ClassDto.SchoolClass> decorate(List<ClassEntity> rows) {
        if (rows.isEmpty()) return List.of();
        var ids = rows.stream().map(ClassEntity::getId).toList();
        Map<String, Integer> childCounts = new LinkedHashMap<>();
        for (Object[] row : children.countByClassIdIn(ids)) childCounts.put((String) row[0], ((Number) row[1]).intValue());
        Map<String, Integer> assignmentCounts = new LinkedHashMap<>();
        for (var a : assignments.findByClassIdInOrderByClassIdAscSubjectAsc(ids))
            assignmentCounts.merge(a.getClassId(), 1, Integer::sum);
        return rows.stream().map(k -> dto(k, childCounts.getOrDefault(k.getId(), 0), assignmentCounts.getOrDefault(k.getId(), 0))).toList();
    }

    @Transactional
    public ClassDto.SchoolClass create(Principals.User caller, ClassDto.CreateSectionRequest request) {
        String schoolId = tenant.writeSchoolId();
        String curriculum = oneOf(request.curriculum(), CURRICULA, "curriculum");
        if (request.grade() < 1 || request.grade() > 12) throw ApiException.badRequest("grade " + request.grade() + " is outside 1–12");
        String name = name(request.name());
        classes.findFirstBySchoolIdAndCurriculumAndGradeAndNameIgnoreCase(schoolId, curriculum, request.grade(), name)
                .ifPresent(k -> { throw ApiException.conflict(name + " already exists in that grade."); });
        var section = sections.create(schoolId, curriculum, request.grade(), name);
        audit.record(caller.userId(), "class.create", "class", section.getId(), schoolId,
                Map.of("curriculum", curriculum, "grade", request.grade(), "name", name));
        return dto(section, 0, 0);
    }

    @Transactional
    public ClassDto.SchoolClass update(Principals.User caller, String classId, ClassDto.UpdateSectionRequest request) {
        var section = section(classId);
        if (request.name() != null) {
            String name = name(request.name());
            classes.findFirstBySchoolIdAndCurriculumAndGradeAndNameIgnoreCase(section.getSchoolId(), section.getCurriculum(), section.getGrade(), name)
                    .filter(other -> !other.getId().equals(section.getId()))
                    .ifPresent(other -> { throw ApiException.conflict(name + " already exists in that grade."); });
            section.setName(name);
        }
        if (request.active() != null) section.setActive(request.active());
        if (request.joinCodeEnabled() != null) section.setJoinCodeEnabled(request.joinCodeEnabled());
        classes.save(section);
        audit.record(caller.userId(), "class.update", "class", section.getId(), section.getSchoolId(),
                Map.of("name", section.getName(), "active", section.isActive(), "joinCodeEnabled", section.isJoinCodeEnabled()));
        return decorate(List.of(section)).getFirst();
    }

    /** A fresh code: the printed cards of this class stop working the moment this returns, which is the point. */
    @Transactional
    public ClassDto.SchoolClass regenerateJoinCode(Principals.User caller, String classId) {
        var section = section(classId);
        section.setJoinCode(joinCodes.generate());
        classes.save(section);
        audit.record(caller.userId(), "class.joinCode", "class", section.getId(), section.getSchoolId(), Map.of());
        return decorate(List.of(section)).getFirst();
    }

    /** `GET /admin/classes/{id}/assignments` — who teaches what in this section. */
    public List<ClassDto.TeachingAssignment> assignmentsOf(String classId) {
        var section = section(classId);
        var rows = assignments.findByClassIdOrderBySubjectAsc(classId);
        return withTeacherNames(rows, Map.of(section.getId(), section));
    }

    /**
     * What one teacher is assigned to teach. `GET /me` answers it with her account so the dashboard can build her
     * whole navigation from the first request (`docs/teacher-flow.md` §2); two statements, never one per class.
     */
    public List<ClassDto.TeachingAssignment> assignmentsOfTeacher(String teacherId) {
        var rows = assignments.findBySchoolIdAndTeacherIdOrderByClassIdAscSubjectAsc(tenant.writeSchoolId(), teacherId);
        return withTeacherNames(rows, sectionsOf(rows));
    }

    /** `[assignment with the class and the teacher named]`, resolving both in one lookup rather than one per row. */
    List<ClassDto.TeachingAssignment> withTeacherNames(List<TeachingAssignmentEntity> rows, Map<String, ClassEntity> sectionsById) {
        if (rows.isEmpty()) return List.of();
        var names = new LinkedHashMap<String, String>();
        users.findAllById(rows.stream().map(TeachingAssignmentEntity::getTeacherId).distinct().toList())
                .forEach(u -> names.put(u.getId(), displayName(u)));
        return rows.stream().map(a -> {
            var k = sectionsById.get(a.getClassId());
            return new ClassDto.TeachingAssignment(a.getId(), a.getClassId(), k == null ? null : k.getName(),
                    k == null ? null : k.getCurriculum(), k == null ? 0 : k.getGrade(), a.getSubject(),
                    a.getTeacherId(), names.get(a.getTeacherId()));
        }).toList();
    }

    /** The sections a set of assignments points at, by id, in one statement. */
    Map<String, ClassEntity> sectionsOf(List<TeachingAssignmentEntity> rows) {
        var out = new LinkedHashMap<String, ClassEntity>();
        for (var k : classes.findBySchoolIdAndNameIsNotNullOrderByCurriculumAscGradeAscNameAsc(tenant.writeSchoolId())) out.put(k.getId(), k);
        out.keySet().retainAll(rows.stream().map(TeachingAssignmentEntity::getClassId).collect(java.util.stream.Collectors.toSet()));
        return out;
    }

    static String displayName(quest.server.auth.Entities.UserEntity user) {
        return user.getDisplayName() == null || user.getDisplayName().isBlank() ? user.getEmail() : user.getDisplayName();
    }

    /**
     * Public `GET /classes/lookup?code=` — the class, its course and the school's name, and nothing else: no
     * roster, no teacher, no join code back, no school settings. The query names no school on purpose: a parent has
     * none yet, and the code is the credential. Unknown, disabled, inactive and "belongs to a suspended school" are
     * all the same uniform 404, so the route cannot be swept for which codes exist; the rate limiter in front of it
     * is the other half of that.
     */
    public ClassDto.ClassLookup lookup(String code) {
        var section = classes.findByJoinCode(JoinCodes.normalise(code)).orElseThrow(() -> ApiException.notFound("class"));
        var school = schools.findById(section.getSchoolId()).orElseThrow(() -> ApiException.notFound("class"));
        if (!"active".equals(school.getStatus())) throw ApiException.notFound("class");
        return new ClassDto.ClassLookup(section.getId(), section.getName(), section.getGrade(), section.getCurriculum(), school.getName());
    }

    /** A section of the caller's scope, or 404; a pre-V7 leftover row is not a section and is never reachable. */
    public ClassEntity section(String classId) {
        return classes.findOneById(classId).filter(ClassEntity::isSection).orElseThrow(() -> ApiException.notFound("class"));
    }

    static ClassDto.SchoolClass dto(ClassEntity k, int children, int assignments) {
        return new ClassDto.SchoolClass(k.getId(), k.getSchoolId(), k.getCurriculum(), k.getGrade(), k.getSubject(),
                k.getTeacherId(), null, k.getCreatedAt() == null ? 0 : k.getCreatedAt().toEpochMilli(), k.getName(),
                k.getJoinCode(), k.isActive(), k.isJoinCodeEnabled(), children, assignments);
    }

    private static String name(String value) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isEmpty() || cleaned.length() > MAX_NAME) throw ApiException.badRequest("A class name is 1–" + MAX_NAME + " characters.");
        return cleaned;
    }

    static String oneOf(String value, List<String> allowed, String field) {
        String cleaned = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(cleaned)) throw ApiException.badRequest(field + " is " + String.join(" or ", allowed) + ", not " + value);
        return cleaned;
    }
}
