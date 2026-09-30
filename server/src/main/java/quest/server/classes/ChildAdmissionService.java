package quest.server.classes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.auth.AuditService;
import quest.server.auth.AuthService;
import quest.server.auth.Entities.ParentEntity;
import quest.server.auth.ParentAccounts;
import quest.server.auth.ParentRepository;
import quest.server.auth.Principals;
import quest.server.children.ChildRepository;
import quest.server.children.Entities.ChildEntity;
import quest.server.config.ApiException;
import quest.server.management.PeopleDirectoryService;
import quest.server.platform.Phones;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.TeacherScope;
import quest.server.tenancy.TenantContext;

/**
 * The Admin's <strong>Children &amp; parents</strong> page (MA1, the owner's item 5): one form that creates the child,
 * the parent's login and the roster placement together, and the page that lists what came of it.
 *
 * <p><strong>The parent's login is not in this database.</strong> She signs in to the app with Firebase
 * email/password, so the account is created through {@link ParentAccounts} — Firebase on QA and in production, an
 * in-memory stand-in under `quest.auth.fake` — and only then is the `parents` row written with the uid it answered.
 * The initial password is the Admin's to choose and read out in the room; it is never stored here, never logged and
 * never in the audit row, exactly as a teacher's one-time password is not.
 *
 * <p><strong>Reuse, and the refusal.</strong> An address the school already has is reused — a second child of the same
 * family is one parent account, and `parentCreated` is false — but an address whose family belongs to <em>another</em>
 * school is a 409, because linking her would hand that school's parent a child of this one. The question only has an
 * answer across schools, which is why {@link ChildRepository#findSchoolIdsOfParentAcrossSchools} is native: a
 * Hibernate filter would make it answer "no other school" every time.
 *
 * <p><strong>Nothing takes a school from the request.</strong> The section goes through {@link TeacherScope} and the
 * page is read with {@link TenantContext#writeSchoolId()}, so another school's class is a 404 and another school's
 * children are not on the page.
 */
@Service
public class ChildAdmissionService {
    /** Firebase's own floor is six characters; a password an Admin reads out in a room should be longer than that. */
    private static final int MIN_PASSWORD = 8;
    private static final int MAX_CHILD_NAME = 40, MAX_PARENT_NAME = 80;

    private final TeacherScope scope; private final RosterService rosters; private final ChildRepository children;
    private final ClassRepository sections; private final ParentRepository parents; private final ParentAccounts accounts;
    private final TemporaryPasswords passwords; private final TenantContext tenant; private final AuditService audit;

    public ChildAdmissionService(TeacherScope scope, RosterService rosters, ChildRepository children,
                                ClassRepository sections, ParentRepository parents, ParentAccounts accounts,
                                TemporaryPasswords passwords, TenantContext tenant, AuditService audit) {
        this.scope = scope; this.rosters = rosters; this.children = children; this.sections = sections;
        this.parents = parents; this.accounts = accounts; this.passwords = passwords; this.tenant = tenant;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- POST /admin/children

    @Transactional
    public ClassDto.ChildAdmission admit(Principals.User caller, ClassDto.AdmitChildRequest request) {
        var section = scope.requireClass(caller, request.classId());
        String childName = text(request.name(), "name", MAX_CHILD_NAME);
        String parentName = text(request.parentName(), "parentName", MAX_PARENT_NAME);
        // Sent for confirmation, not to choose with: a Grade 1 British child in a Grade 1 American class would be
        // shown lessons written for a syllabus she is not taught (the rule `RosterService.attach` refuses too).
        if (request.grade() != null && request.grade() != section.getGrade())
            throw ApiException.badRequest(section.getName() + " is grade " + section.getGrade() + ".");
        if (request.curriculum() != null && !request.curriculum().isBlank()
                && !section.getCurriculum().equalsIgnoreCase(request.curriculum().trim()))
            throw ApiException.badRequest(section.getName() + " is " + section.getCurriculum() + ".");
        String email = AuthService.normalise(request.parentEmail());
        if (!email.contains("@")) throw ApiException.badRequest("That is not an email address.");
        String phone = Phones.normalise(request.parentPhone(), "parentPhone");
        String password = password(request.parentInitialPassword());

        var parent = parents.findFirstByEmailIgnoreCase(email).orElse(null);
        boolean created = false;
        if (parent == null) {
            var account = accounts.byEmail(email).orElseGet(() -> accounts.create(email, password, parentName));
            // `firebase_uid` is unique, and a row carrying this uid under another address is the same person (she
            // changed her address in the app): reuse it, rather than meeting the constraint with a second insert.
            parent = parents.findByFirebaseUid(account.uid()).orElse(null);
            if (parent == null) { parent = insert(account, email, parentName, phone); created = true; }
        }
        if (!created) parent = reuse(parent, section, parentName, phone);
        var child = rosters.admit(caller, section, childName, email, parent.getId());
        // The password is deliberately not in the audit details: the row is readable by every Admin, for ever.
        audit.record(caller.userId(), "child.parentAccount", "child", child.getId(), section.getSchoolId(),
                Map.of("parentCreated", created));
        return new ClassDto.ChildAdmission(child.getId(), parent.getId(), created);
    }

    /**
     * The local copy of a login that now exists. The Firebase account itself is <em>reused</em> when the address
     * already has one — a parent who signed up in the app before the school typed her in — so the uid she already
     * signs in with is the one that is stored and the password in the request is deliberately <strong>not</strong>
     * applied: it is nobody's business to overwrite a password she chose.
     * `POST /admin/children/{id}/parent/reset-password` is the route that changes one on purpose.
     */
    private ParentEntity insert(ParentAccounts.Account account, String email, String parentName, String phone) {
        var row = new ParentEntity();
        row.setId(UUID.randomUUID().toString()); row.setFirebaseUid(account.uid()); row.setEmail(email);
        row.setDisplayName(parentName); row.setPhone(phone); row.setCreatedAt(Instant.now());
        return parents.save(row);
    }

    /** The family the school already has, with whatever the form filled in that was still blank. */
    private ParentEntity reuse(ParentEntity parent, ClassEntity section, String parentName, String phone) {
        for (String other : children.findSchoolIdsOfParentAcrossSchools(parent.getId()))
            if (!section.getSchoolId().equals(other))
                throw ApiException.conflict(parent.getEmail() + " is already a parent at another school.");
        if (phone != null) parent.setPhone(phone);
        if (parent.getDisplayName() == null || parent.getDisplayName().isBlank()) parent.setDisplayName(parentName);
        return parents.save(parent);
    }

    // ---------------------------------------------------------------- POST /admin/children/{id}/parent/reset-password

    /**
     * A new password for the parent of this child, answered once. The provider is what holds it
     * ({@link ParentAccounts#password}), so a deployment without Firebase configured answers 503 here and writes
     * nothing — the one route of this package that cannot work on a local `parents` row alone.
     */
    @Transactional
    public ClassDto.TemporaryPassword resetParentPassword(Principals.User caller, String childId) {
        var child = children.findOneById(childId).filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("child"));
        if (child.getClassId() == null) throw ApiException.badRequest("That child is not in a class yet.");
        scope.requireClass(caller, child.getClassId());                          // her class is the gate, as on the PATCH
        if (child.getParentId() == null) throw ApiException.notFound("parent");
        var parent = parents.findById(child.getParentId()).orElseThrow(() -> ApiException.notFound("parent"));
        String temporary = passwords.generate();
        accounts.password(parent.getFirebaseUid(), temporary);
        audit.record(caller.userId(), "child.parentPassword", "child", child.getId(), child.getSchoolId(), Map.of());
        return new ClassDto.TemporaryPassword(temporary);
    }

    // ---------------------------------------------------------------- GET /admin/children/search

    /**
     * A page of the school's children with the parent beside each one — the owner's item 5, matched on the child's
     * name, either address the school holds, or the parent's name and telephone number. Four statements whatever the
     * page holds: the count, the page, the sections it names and the parent accounts it names.
     */
    @Transactional(readOnly = true)
    public ClassDto.FamilyPage search(String q, int page, int size) {
        String schoolId = tenant.writeSchoolId();
        int index = PeopleDirectoryService.page(page), each = PeopleDirectoryService.size(size);
        String pattern = PeopleDirectoryService.pattern(q);
        int total = (int) children.countSchoolDirectory(schoolId, pattern);
        var rows = children.findSchoolDirectory(schoolId, pattern,
                PageRequest.of(index, each, Sort.by("name").ascending().and(Sort.by("id").ascending())));

        var sectionsById = new LinkedHashMap<String, ClassEntity>();
        var classIds = rows.stream().map(ChildEntity::getClassId).filter(java.util.Objects::nonNull).distinct().toList();
        if (!classIds.isEmpty()) sections.findAllById(classIds).forEach(s -> sectionsById.put(s.getId(), s));
        var accountsById = new LinkedHashMap<String, ParentEntity>();
        var parentIds = rows.stream().map(ChildEntity::getParentId).filter(java.util.Objects::nonNull).distinct().toList();
        if (!parentIds.isEmpty()) parents.findAllById(parentIds).forEach(p -> accountsById.put(p.getId(), p));

        var out = new ArrayList<ClassDto.FamilyRow>(rows.size());
        for (var child : rows) {
            var section = child.getClassId() == null ? null : sectionsById.get(child.getClassId());
            var parent = child.getParentId() == null ? null : accountsById.get(child.getParentId());
            out.add(new ClassDto.FamilyRow(child.getId(), child.getName(), child.getGrade(),
                    child.getCurriculum() == null ? null : child.getCurriculum().toLowerCase(Locale.ROOT),
                    child.getClassId(), section == null ? null : section.getName(),
                    parent == null ? null : parent.getId(), parent == null ? null : parent.getDisplayName(),
                    parent == null ? child.getParentEmail() : parent.getEmail(),
                    parent == null ? null : parent.getPhone(), child.isActive()));
        }
        return new ClassDto.FamilyPage(index, each, total, List.copyOf(out));
    }

    // ---------------------------------------------------------------- helpers

    private static String password(String value) {
        String raw = value == null ? "" : value;
        if (raw.trim().length() < MIN_PASSWORD)
            throw ApiException.badRequest("parentInitialPassword needs at least " + MIN_PASSWORD + " characters.");
        return raw;
    }

    private static String text(String value, String field, int max) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isEmpty() || cleaned.length() > max) throw ApiException.badRequest(field + " is 1–" + max + " characters.");
        return cleaned;
    }
}
