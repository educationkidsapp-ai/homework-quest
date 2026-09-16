package quest.server.teacher;

import java.util.List;
import org.springframework.stereotype.Component;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.TenantContext;

/**
 * Who may act on a class, and on the rows that hang off one (§5).
 *
 * <p>The school is <strong>not</strong> checked here: `classes.findOneById` is a query, so the `school` Hibernate
 * filter has already reduced it to the caller's school — another school's class is `Optional.empty()` and answers
 * 404, join code and all, exactly as another school's lesson does. What this adds is the rule inside a school:
 *
 * <ul>
 *   <li>a TEACHER reaches only the classes she owns (`classes.teacher_id` = her user id); anything else is 403,
 *       because the class exists and is hers to know about — it is simply not hers to teach;</li>
 *   <li>a MANAGERIAL user reads any class of her school and writes none (`permissions.json` gives her the `.read`
 *       keys only, and {@link #ownedClass} refuses her outright as the second lock);</li>
 *   <li>the platform ADMIN reaches any class, scoped by `X-School-Id` when she has picked a school (D6).</li>
 * </ul>
 */
@Component
public class TeacherAccess {
    private final ClassRepository classes; private final TenantContext tenant;

    public TeacherAccess(ClassRepository classes, TenantContext tenant) { this.classes = classes; this.tenant = tenant; }

    /** A class the caller may read: hers, or any of her school for MANAGERIAL and ADMIN. */
    public ClassEntity readableClass(Principals.User caller, String classId) {
        var klass = classes.findOneById(classId).orElseThrow(() -> ApiException.notFound("class"));
        if (isTeacher(caller) && !caller.userId().equals(klass.getTeacherId()))
            throw ApiException.forbidden("That class is taught by someone else.");
        return klass;
    }

    /** A class the caller may write into: hers as a TEACHER, or any of the school she is scoped to as ADMIN. */
    public ClassEntity ownedClass(Principals.User caller, String classId) {
        if ("MANAGERIAL".equals(caller.role()))
            throw ApiException.forbidden("Managerial accounts can read this school's classes but not post into them.");
        return readableClass(caller, classId);
    }

    /** Every class the caller may write into, for validating a list of class ids in one pass. */
    public List<ClassEntity> ownedClasses(Principals.User caller) {
        String schoolId = tenant.writeSchoolId();
        if (isTeacher(caller)) return classes.findBySchoolIdAndTeacherIdOrderByCurriculumAscGradeAscSubjectAsc(schoolId, caller.userId());
        return classes.findBySchoolIdOrderByCurriculumAscGradeAscSubjectAsc(schoolId);
    }

    /**
     * The teacher a row belongs to. A TEACHER always owns what she creates; an ADMIN acting on a school's behalf
     * must say whose it is, because the app shows the teacher's name and photo on the island and on the card.
     */
    public String authorOf(Principals.User caller, ClassEntity klass) {
        if (isTeacher(caller)) return caller.userId();
        if (klass.getTeacherId() != null) return klass.getTeacherId();
        throw ApiException.badRequest("That class has no teacher yet — assign one before posting to it.");
    }

    /** The school a write lands in: the caller's, or the one an Admin picked with `X-School-Id` (else the default). */
    public String writeSchoolId() { return tenant.writeSchoolId(); }

    /** True for a TEACHER principal — the only role whose reach is narrowed to her own classes. */
    public boolean isTeacher(Principals.User caller) { return "TEACHER".equals(caller.role()); }

    /** The caller, or 401 — every controller in this package starts with it. */
    public static Principals.User require(Principals.User caller) {
        if (caller == null) throw ApiException.unauthorized("Sign in first.");
        return caller;
    }
}
