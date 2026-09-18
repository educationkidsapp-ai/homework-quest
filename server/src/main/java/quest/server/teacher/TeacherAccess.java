package quest.server.teacher;

import java.util.List;
import org.springframework.stereotype.Component;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.TeacherScope;

/**
 * Who may act on a class, and on the rows that hang off one (§5) — <strong>a façade over
 * {@link TeacherScope}</strong> since V7, kept so the P4.0 services in this package (calendar, students, questions,
 * announcements) read the same as they did while the rule underneath them changed.
 *
 * <p>That rule is now `docs/teacher-flow.md` §2's: a teacher reaches a class through a <em>teaching assignment</em>,
 * not through `classes.teacher_id`, which V7 stopped writing. Everything else this class promised still holds —
 * another school's class is a 404 because the lookup is a filtered query, a MANAGERIAL user reads and never writes,
 * and the platform ADMIN reaches any class of the school she is scoped to.
 *
 * <p>Because every service here goes through {@link TeacherScope}, so does every `/teacher/**` handler, which is
 * what `TeacherScopeArchitectureTest` proves rather than assumes.
 */
@Component
public class TeacherAccess {
    private final TeacherScope scope;

    public TeacherAccess(TeacherScope scope) { this.scope = scope; }

    /** A class the caller may read: one she is assigned to, or any of her school for MANAGERIAL and ADMIN. */
    public ClassEntity readableClass(Principals.User caller, String classId) { return scope.requireClass(caller, classId); }

    /**
     * A class the caller may write into: one she is assigned to as a TEACHER, or any of her scope as ADMIN. The
     * class is resolved first, so a class that does not exist is a 404 whoever asks — the refusal never doubles as
     * confirmation that an id is real.
     */
    public ClassEntity ownedClass(Principals.User caller, String classId) {
        var section = scope.requireClass(caller, classId);
        if (caller != null && "MANAGERIAL".equals(caller.role()))
            throw ApiException.forbidden("Managerial accounts can read this school's classes but not post into them.");
        return section;
    }

    /** Every class the caller may write into, for validating a list of class ids in one pass. */
    public List<ClassEntity> ownedClasses(Principals.User caller) { return scope.classesOf(caller); }

    /**
     * The teacher a row belongs to. A TEACHER always owns what she creates; an ADMIN acting on a school's behalf
     * inherits whoever teaches that class, because the app shows the teacher's name and photo on the island.
     */
    public String authorOf(Principals.User caller, ClassEntity klass) {
        if (scope.isTeacher(caller)) return caller.userId();
        return scope.assignmentsOf(caller).stream().filter(a -> a.getClassId().equals(klass.getId())).findFirst()
                .map(quest.server.tenancy.Entities.TeachingAssignmentEntity::getTeacherId)
                .orElseThrow(() -> ApiException.badRequest("That class has no teacher yet — assign one before posting to it."));
    }

    /** The school a write lands in: the caller's, or the one an Admin picked with `X-School-Id` (else the default). */
    public String writeSchoolId() { return scope.writeSchoolId(); }

    /** True for a TEACHER principal — the only role whose reach is narrowed to her own assignments. */
    public boolean isTeacher(Principals.User caller) { return scope.isTeacher(caller); }

    /** The caller, or 401 — every controller in this package starts with it. */
    public static Principals.User require(Principals.User caller) { return TeacherScope.require(caller); }
}
