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
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.TenantContext;

@Service
public class ChildService {
    private final ChildRepository children; private final SchoolRepository schools;
    public ChildService(ChildRepository children, SchoolRepository schools) { this.children = children; this.schools = schools; }

    public Entities.ChildEntity owned(String childId, Principals.Parent parent) {
        return children.findById(childId).filter(c -> c.getDeletedAt() == null && c.getParentId().equals(parent.parentId())).orElseThrow(() -> ApiException.notFound("child"));
    }

    public List<Child> list(Principals.Parent parent) { return children.findByParentIdAndDeletedAtIsNullOrderByCreatedAt(parent.parentId()).stream().map(ChildService::dto).toList(); }

    @Transactional
    public Child create(Principals.Parent parent, CreateChildRequest req) {
        validate(req.getName(), req.getGrade(), req.getAvatarColor());
        var e = new Entities.ChildEntity();
        e.setId(UUID.randomUUID().toString()); e.setParentId(parent.parentId()); e.setName(req.getName().trim()); e.setAvatarColor(req.getAvatarColor());
        e.setCurriculum(req.getCurriculum().name().toLowerCase()); e.setGrade(req.getGrade()); e.setLanguages(String.join(",", req.getLanguages())); e.setCreatedAt(Instant.now());
        e.setSchoolId(schoolOf(req.getSchoolCode()));
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
