package quest.server.classes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import quest.server.auth.AuditService;
import quest.server.auth.Principals;
import quest.server.children.ChildRepository;
import quest.server.children.Entities.ChildEntity;
import quest.server.config.ApiException;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.TeacherScope;

/**
 * The children of a class, typed in before anybody has an account for them
 * (`docs/prompts/dashboard-first-one-school.md` §3): Admin owns the roster, and a teacher may edit her own classes'
 * while `teacher.rosterEdit` is on.
 *
 * <p><strong>Scope.</strong> Every entry point takes the class through {@link TeacherScope} — an Admin reaches any
 * section of the school she is scoped to, a teacher only the ones she is assigned to, and another school's is a 404
 * because the lookup is a filtered query. A child is then read by that class's id, never by a parameter, which is
 * what stops `PATCH /admin/children/{id}` reaching across a school boundary.
 *
 * <p><strong>Duplicates</strong> are "the same name, in this class" — compared on a normalised form (case, accents
 * and runs of spaces folded away) because the file a school uploads is a person's typing, not a key.
 */
@Service
public class RosterService {
    private static final int MAX_NAME = 40;
    /** The four the app paints a child's tile with; a roster row gets one so her card is not blank on first sight. */
    private static final List<String> AVATARS = List.of("sky", "sun", "mint", "lavender");

    private final ChildRepository children; private final TeacherScope scope; private final RosterImport reader;
    private final AuditService audit;

    public RosterService(ChildRepository children, TeacherScope scope, RosterImport reader, AuditService audit) {
        this.children = children; this.scope = scope; this.reader = reader; this.audit = audit;
    }

    /**
     * `GET /admin/children`: every live child of the school, and with `unassigned=true` only the ones who are on no
     * section's roster — which is how an Admin finds a child a parent has just registered in the app, to attach her.
     * There is no other way to see her: every other roster read is by `class_id`, and hers is null.
     */
    public List<ClassDto.RosterChild> ofSchool(boolean unassignedOnly) {
        return children.findBySchoolIdAndDeletedAtIsNullOrderByNameAsc(scope.writeSchoolId()).stream()
                .filter(c -> !unassignedOnly || c.getClassId() == null).map(RosterService::dto).toList();
    }

    public List<ClassDto.RosterChild> list(Principals.User caller, String classId) {
        var section = scope.requireClass(caller, classId);
        return children.findByClassIdAndDeletedAtIsNullOrderByNameAsc(section.getId()).stream().map(RosterService::dto).toList();
    }

    @Transactional
    public ClassDto.RosterChild add(Principals.User caller, String classId, ClassDto.CreateRosterChildRequest request) {
        var section = writable(caller, classId);
        String name = name(request.name());
        var roster = children.findByClassIdAndDeletedAtIsNullOrderByNameAsc(section.getId());
        if (roster.stream().anyMatch(c -> normalise(c.getName()).equals(normalise(name))))
            throw ApiException.conflict(name + " is already on this class's roster.");
        var child = insert(section, name, request.parentEmail() == null ? null : email(request.parentEmail()),
                photo(request.photoUrl()), roster.size());
        audit.record(caller.userId(), "child.create", "child", child.getId(), section.getSchoolId(), Map.of("classId", section.getId()));
        return dto(child);
    }

    /**
     * The same edit reached through a teacher's own route, where the class is in the path: the child has to be on
     * <em>that</em> class's roster, checked before anything is written, so a valid child id under the wrong class is
     * a 404 rather than a write followed by a refusal.
     */
    @Transactional
    public ClassDto.RosterChild updateIn(Principals.User caller, String classId, String childId, ClassDto.UpdateRosterChildRequest request) {
        var section = writable(caller, classId);
        var child = children.findOneById(childId).filter(c -> c.getDeletedAt() == null).orElseThrow(() -> ApiException.notFound("child"));
        if (!section.getId().equals(child.getClassId())) throw ApiException.notFound("child");
        return update(caller, childId, request);
    }

    @Transactional
    public ClassDto.RosterChild update(Principals.User caller, String childId, ClassDto.UpdateRosterChildRequest request) {
        var child = children.findOneById(childId).filter(c -> c.getDeletedAt() == null).orElseThrow(() -> ApiException.notFound("child"));
        // Her current class is the gate: a teacher may only edit a child of a class she is assigned to.
        if (child.getClassId() == null) throw ApiException.badRequest("That child is not in a class yet.");
        writable(caller, child.getClassId());
        if (request.name() != null) child.setName(name(request.name()));
        if (request.parentEmail() != null) child.setParentEmail(request.parentEmail().isBlank() ? null : email(request.parentEmail()));
        if (request.photoUrl() != null) child.setPhotoUrl(request.photoUrl().isBlank() ? null : photo(request.photoUrl()));
        if (request.active() != null) child.setActive(request.active());
        if (request.classId() != null && !request.classId().equals(child.getClassId())) {
            var target = writable(caller, request.classId());                   // the destination has to be hers too
            child.setClassId(target.getId()); child.setCurriculum(target.getCurriculum()); child.setGrade(target.getGrade());
        }
        children.save(child);
        audit.record(caller.userId(), "child.update", "child", child.getId(), child.getSchoolId(), Map.of("classId", child.getClassId()));
        return dto(child);
    }

    /**
     * `POST …/classes/{classId}/roster/attach`: put a child who already exists onto this section's roster.
     *
     * <p>The child the Admin means here is not a roster row somebody typed — she is a child a <em>parent</em>
     * registered in the app, with the school's join code and no section at all. Until she has one,
     * {@link quest.server.children.SchoolLessons} gives her every section of her curriculum and grade, so a lesson
     * published to 1A and copied to 1B reaches her twice; with one, she sees her own class's copy and nothing else,
     * and her work appears on that class's teacher's dashboard.
     *
     * <p>Three refusals and one silence: another school's child is a 404 for a scoped caller (the lookup is a
     * filtered query) and a 409 for the unscoped platform ADMIN, who can see across schools; a child whose
     * curriculum or grade is not this section's is a 409, because a Grade 1 British child in a Grade 1 American
     * class would be shown lessons written for a syllabus she is not taught; and attaching a child who is already on
     * this roster writes nothing and answers the same row.
     */
    @Transactional
    public ClassDto.RosterChild attach(Principals.User caller, String classId, String childId) {
        var section = writable(caller, classId);
        var child = children.findOneById(childId).filter(c -> c.getDeletedAt() == null).orElseThrow(() -> ApiException.notFound("child"));
        if (!section.getSchoolId().equals(child.getSchoolId()))
            throw ApiException.conflict(child.getName() + " belongs to another school.");
        if (!section.getCurriculum().equalsIgnoreCase(child.getCurriculum()) || section.getGrade() != child.getGrade())
            throw ApiException.conflict(child.getName() + " is " + child.getCurriculum() + " grade " + child.getGrade()
                    + ", and " + section.getName() + " is " + section.getCurriculum() + " grade " + section.getGrade() + ".");
        if (section.getId().equals(child.getClassId())) return dto(child);      // already there: idempotent
        child.setClassId(section.getId());
        children.save(child);
        audit.record(caller.userId(), "child.attach", "child", child.getId(), section.getSchoolId(), Map.of("classId", section.getId()));
        return dto(child);
    }

    /**
     * `DELETE …/classes/{classId}/roster/{childId}`: take her off this section's roster again. She keeps her account,
     * her attempts and her progress — only the link to the class goes, which puts her back to seeing every section of
     * her curriculum and grade. A child who is not on this roster is a 404, so the path cannot detach someone else's.
     */
    @Transactional
    public ClassDto.RosterChild detach(Principals.User caller, String classId, String childId) {
        var section = writable(caller, classId);
        var child = children.findOneById(childId).filter(c -> c.getDeletedAt() == null).orElseThrow(() -> ApiException.notFound("child"));
        if (!section.getId().equals(child.getClassId())) throw ApiException.notFound("child");
        child.setClassId(null);
        children.save(child);
        audit.record(caller.userId(), "child.detach", "child", child.getId(), section.getSchoolId(), Map.of("classId", section.getId()));
        return dto(child);
    }

    /**
     * `POST /admin/classes/{id}/children/import`. With `dryRun` nothing is written and the answer is the preview the
     * Admin confirms; without it the same answer describes what was inserted, and only `new` rows were.
     *
     * <p>A duplicate inside the <em>file</em> counts as a duplicate too, so uploading a list with the same child
     * twice adds her once whether or not she was on the roster already.
     */
    @Transactional
    public ClassDto.ImportPreview importRoster(Principals.User caller, String classId, MultipartFile file, boolean dryRun) {
        var section = writable(caller, classId);
        if (file == null || file.isEmpty()) throw ApiException.badRequest("Attach a CSV or XLSX file.");
        var roster = children.findByClassIdAndDeletedAtIsNullOrderByNameAsc(section.getId());
        var seen = new LinkedHashSet<String>();
        roster.forEach(c -> seen.add(normalise(c.getName())));

        var rows = new ArrayList<ClassDto.ImportRow>();
        int added = 0, duplicate = 0, invalid = 0, position = roster.size();
        for (var line : reader.read(file)) {
            String reason = invalidReason(line);
            if (reason != null) { rows.add(new ClassDto.ImportRow(line.line(), line.name(), line.parentEmail(), ClassDto.INVALID, reason)); invalid++; continue; }
            String name = line.name().trim();
            if (!seen.add(normalise(name))) {
                rows.add(new ClassDto.ImportRow(line.line(), name, line.parentEmail(), ClassDto.DUPLICATE, "already on this class's roster"));
                duplicate++;
                continue;
            }
            // The address is stored the way a typed one is — trimmed and lower case — so the two paths cannot leave
            // the same parent under two spellings for whatever later matches a child to an account by it.
            if (!dryRun) insert(section, name, line.parentEmail() == null ? null : email(line.parentEmail()), null, position++);
            rows.add(new ClassDto.ImportRow(line.line(), name, line.parentEmail(), ClassDto.NEW, null));
            added++;
        }
        if (!dryRun) audit.record(caller.userId(), "child.import", "class", section.getId(), section.getSchoolId(),
                Map.of("added", added, "duplicate", duplicate, "invalid", invalid));
        return new ClassDto.ImportPreview(dryRun, rows, new ClassDto.ImportSummary(rows.size(), added, duplicate, invalid));
    }

    // ---------------------------------------------------------------- helpers

    /** A section the caller may write into: any of an Admin's scope, one a teacher is assigned to. */
    private ClassEntity writable(Principals.User caller, String classId) {
        var section = scope.requireClass(caller, classId);
        if (scope.isTeacher(caller)) return section;
        if ("MANAGERIAL".equals(caller.role()))
            throw ApiException.forbidden("Managerial accounts can read this school's rosters but not change them.");
        return section;
    }

    private ChildEntity insert(ClassEntity section, String name, String parentEmail, String photoUrl, int position) {
        var child = new ChildEntity();
        child.setId(UUID.randomUUID().toString()); child.setSchoolId(section.getSchoolId()); child.setClassId(section.getId());
        child.setName(name); child.setParentEmail(parentEmail); child.setPhotoUrl(photoUrl); child.setActive(true);
        child.setAvatarColor(AVATARS.get(Math.floorMod(position, AVATARS.size())));
        child.setCurriculum(section.getCurriculum()); child.setGrade(section.getGrade()); child.setLanguages("en");
        child.setCreatedAt(Instant.now());
        return children.save(child);
    }

    private static String invalidReason(RosterImport.Line line) {
        if (line.name() == null || line.name().isBlank()) return "no name";
        if (line.name().trim().length() > MAX_NAME) return "name longer than " + MAX_NAME + " characters";
        if (line.parentEmail() != null && !line.parentEmail().contains("@")) return "not an email address";
        return null;
    }

    /** Case, accents and runs of spaces folded away: a roster is typed by hand and "Sara  al-Harbi" is one child. */
    static String normalise(String name) {
        String folded = java.text.Normalizer.normalize(name == null ? "" : name, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        return folded.replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String name(String value) {
        String cleaned = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty() || cleaned.length() > MAX_NAME) throw ApiException.badRequest("A name is 1–" + MAX_NAME + " characters.");
        return cleaned;
    }

    private static String email(String value) {
        String cleaned = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!cleaned.contains("@")) throw ApiException.badRequest("That is not an email address.");
        return cleaned;
    }

    private static String photo(String url) {
        if (url == null || url.isBlank()) return null;
        if (!url.trim().toLowerCase(Locale.ROOT).startsWith("https://")) throw ApiException.badRequest("A photo URL must start with https://");
        return url.trim();
    }

    static ClassDto.RosterChild dto(ChildEntity c) {
        return new ClassDto.RosterChild(c.getId(), c.getClassId(), c.getName(), c.getParentEmail(), c.getPhotoUrl(),
                c.isActive(), c.getParentId() != null);
    }
}
