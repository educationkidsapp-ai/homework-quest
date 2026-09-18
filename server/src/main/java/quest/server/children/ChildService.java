package quest.server.children;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kotlin.collections.CollectionsKt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.api.dto.Child;
import quest.api.dto.CreateChildRequest;
import quest.api.dto.Curriculum;
import quest.api.dto.UpdateChildRequest;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.JoinCodes;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.TenantContext;

@Service
public class ChildService {
    private final ChildRepository children; private final SchoolRepository schools; private final ClassRepository classes; private final TenantContext tenant;
    public ChildService(ChildRepository children, SchoolRepository schools, ClassRepository classes, TenantContext tenant) { this.children = children; this.schools = schools; this.classes = classes; this.tenant = tenant; }

    /** A parent's own child. `findOneById`, not `findById`: filters do not apply to `em.find`. */
    public Entities.ChildEntity owned(String childId, Principals.Parent parent) {
        // `parent.parentId()` first: since V7 a roster child may have no parent at all, and `getParentId()` is null.
        return children.findOneById(childId).filter(c -> c.getDeletedAt() == null && parent.parentId().equals(c.getParentId())).orElseThrow(() -> ApiException.notFound("child"));
    }

    /**
     * A child a dashboard user may see: of her school, or of the school an Admin switched to. Everything hanging off a
     * child — attempts, completions, media, stickers, streaks, parent unlocks — is reached by `child_id`, so this is
     * the one gate in front of all of them.
     *
     * <p>The scope is resolved <em>first</em>, and deliberately not treated as "no scope, see everything": a dashboard
     * principal with no resolvable school is refused there with 403 before any child is read. A null scope past that
     * line is only the platform ADMIN (D6), a parent or a job.
     */
    public Entities.ChildEntity scoped(String childId) {
        var schoolId = tenant.schoolId();
        var child = children.findOneById(childId).filter(c -> c.getDeletedAt() == null).orElseThrow(() -> ApiException.notFound("child"));
        if (schoolId != null && !schoolId.equals(child.getSchoolId())) throw ApiException.notFound("child");
        return child;
    }

    public List<Child> list(Principals.Parent parent) { return children.findByParentIdAndDeletedAtIsNullOrderByCreatedAt(parent.parentId()).stream().map(ChildService::dto).toList(); }

    /**
     * A parent's new child. Two ways to say where she belongs, in order of precedence:
     *
     * <ul>
     *   <li><strong>`joinCode`</strong> (V7): the code on a class's card. It settles the school, the section, the
     *       curriculum and the grade all at once, because the card is the more specific answer than anything the
     *       parent could pick from a list — so whatever `curriculum`, `grade` and `schoolCode` say is ignored.</li>
     *   <li>`schoolCode` with `curriculum` + `grade`: the pre-V7 shape the app still sends. The child has no section
     *       until Admin puts her in one, and her map falls back to §2's "every class of her school with that
     *       curriculum and grade" ({@link SchoolLessons}).</li>
     * </ul>
     */
    @Transactional
    public Child create(Principals.Parent parent, CreateChildRequest req) {
        var section = req.getJoinCode() == null || req.getJoinCode().isBlank() ? null
                : classes.findByJoinCode(JoinCodes.normalise(req.getJoinCode())).orElseThrow(() -> ApiException.notFound("class"));
        String curriculum = section != null ? section.getCurriculum() : req.getCurriculum().name().toLowerCase();
        int grade = section != null ? section.getGrade() : req.getGrade();
        validate(req.getName(), grade, req.getAvatarColor());
        var e = new Entities.ChildEntity();
        e.setId(UUID.randomUUID().toString()); e.setParentId(parent.parentId()); e.setName(req.getName().trim()); e.setAvatarColor(req.getAvatarColor());
        e.setCurriculum(curriculum); e.setGrade(grade); e.setLanguages(String.join(",", req.getLanguages())); e.setCreatedAt(Instant.now());
        e.setSchoolId(section != null ? section.getSchoolId() : schoolOf(req.getSchoolCode()));
        if (section != null) e.setClassId(section.getId());
        return dto(children.save(e));
    }

    @Transactional
    public Child update(Principals.Parent parent, String id, UpdateChildRequest req) {
        var e = owned(id, parent);
        if (req.getName() != null) e.setName(req.getName().trim());
        if (req.getAvatarColor() != null) e.setAvatarColor(req.getAvatarColor());
        if (req.getCurriculum() != null) e.setCurriculum(req.getCurriculum().name().toLowerCase());
        if (req.getGrade() != null) e.setGrade(req.getGrade());
        if (req.getLanguages() != null) e.setLanguages(String.join(",", req.getLanguages()));
        validate(e.getName(), e.getGrade(), e.getAvatarColor());
        return dto(children.save(e));
    }

    @Transactional
    public void delete(Principals.Parent parent, String id) { var e = owned(id, parent); e.setDeletedAt(Instant.now()); children.save(e); }

    /** A parent joins a school with its 6-character code; without one the child stays in the default school. */
    private String schoolOf(String code) {
        if (code == null || code.isBlank()) return TenantContext.DEFAULT_SCHOOL;
        return schools.findByCodeIgnoreCase(code.trim()).orElseThrow(() -> ApiException.notFound("school")).getId();
    }

    private static void validate(String name, int grade, String avatar) {
        if (name == null || name.isBlank() || name.length() > 40) throw ApiException.badRequest("Name must be 1–40 characters.");
        if (grade < 1 || grade > 3) throw ApiException.badRequest("Grade must be 1, 2 or 3.");
        if (!List.of("sky", "sun", "mint", "lavender").contains(avatar)) throw ApiException.badRequest("Avatar colour must be sky, sun, mint or lavender.");
    }

    public static Child dto(Entities.ChildEntity e) {
        var langs = e.getLanguages() == null || e.getLanguages().isBlank() ? List.of("en") : List.of(e.getLanguages().split(","));
        return new Child(e.getId(), e.getName(), e.getAvatarColor(), Curriculum.valueOf(e.getCurriculum().toUpperCase()), e.getGrade(), CollectionsKt.toList(langs), e.getSchoolId());
    }
}
