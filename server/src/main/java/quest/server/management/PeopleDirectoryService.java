package quest.server.management;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.auth.ParentRepository;
import quest.server.auth.Principals;
import quest.server.children.ChildRepository;
import quest.server.config.ApiException;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.ManagerScope;

/**
 * RM5 (DR7): <strong>the people directory</strong> — who is in the department, with the contact details the school
 * actually holds. Three lists, all department-scoped, all paginated, all searchable by name or address.
 *
 * <p><strong>Nothing here invents a field.</strong> A child's contact is her registered parent's account — address,
 * telephone number (MH1's `parents.phone`, null until she types one into the app) and `parentId`, which is what tells
 * the screen there is somebody to message at all — beside the roster's own `children.parent_email`, which is what the
 * school imported. `placedAt` is the roster row's creation, which is when she joined the section.
 *
 * <p><strong>The teacher and coordinator lists are the department's own rows, paged.</strong> They are the same
 * records `/management/teachers` and `/management/coordinators` answer — subjects, sections, tracks and coverage
 * already resolved by {@link ManagementService} out of one {@link ManagerScope#reach} — filtered and sliced here
 * rather than queried again, because a department has tens of staff and two more statements would buy nothing. The
 * children are paged in the database, where there are thousands of them.
 */
@Service
public class PeopleDirectoryService {
    /** A page nobody asked to size, and the largest one anybody may ask for. */
    static final int DEFAULT_SIZE = 25, MAX_SIZE = 100;
    /** The `escape` character both `like` statements in {@link ChildRepository} declare. */
    private static final char ESCAPE = '\\';

    private final ManagerScope scope; private final ManagementService management;
    private final ChildRepository children; private final ParentRepository parents;

    public PeopleDirectoryService(ManagerScope scope, ManagementService management, ChildRepository children,
                                 ParentRepository parents) {
        this.scope = scope; this.management = management; this.children = children; this.parents = parents;
    }

    // ---------------------------------------------------------------- GET /management/people/children

    /**
     * The children of her department, or of one section of it. A `classId` outside the department is a 403 before
     * anything is read; without one the search runs across every section she manages.
     */
    @Transactional(readOnly = true)
    public ManagementDto.ChildDirectory children(Principals.User caller, String classId, String q, int page, int size) {
        var sections = classId == null || classId.isBlank()
                ? scope.sectionsOf(caller) : List.of(scope.requireSection(caller, classId));
        int index = page(page), each = size(size);
        if (sections.isEmpty()) return new ManagementDto.ChildDirectory(index, each, 0, List.of());
        var byId = new LinkedHashMap<String, ClassEntity>();
        for (var section : sections) byId.put(section.getId(), section);
        String pattern = pattern(q);
        int total = (int) children.countDirectory(byId.keySet(), pattern);
        var rows = children.findDirectory(byId.keySet(), pattern,
                PageRequest.of(index, each, Sort.by("name").ascending().and(Sort.by("id").ascending())));

        var accounts = new LinkedHashMap<String, quest.server.auth.Entities.ParentEntity>();
        var parentIds = rows.stream().map(quest.server.children.Entities.ChildEntity::getParentId).filter(java.util.Objects::nonNull).distinct().toList();
        if (!parentIds.isEmpty()) parents.findAllById(parentIds).forEach(p -> accounts.put(p.getId(), p));

        var out = new ArrayList<ManagementDto.DirectoryChild>(rows.size());
        for (var child : rows) {
            var section = byId.get(child.getClassId());
            var account = child.getParentId() == null ? null : accounts.get(child.getParentId());
            out.add(new ManagementDto.DirectoryChild(child.getId(), child.getName(), child.getClassId(),
                    section == null ? null : section.getName(), child.getGrade(),
                    ManagerScope.normalise(child.getCurriculum()), account == null ? null : account.getEmail(),
                    child.getParentEmail(), account == null ? null : account.getId(),
                    account == null ? null : account.getPhone(),
                    child.getCreatedAt() == null ? 0L : child.getCreatedAt().toEpochMilli()));
        }
        return new ManagementDto.ChildDirectory(index, each, total, List.copyOf(out));
    }

    // ---------------------------------------------------------------- GET /management/people/teachers, /coordinators

    /** Every teacher of the department, with her subjects and her sections, matched on name or address. */
    @Transactional(readOnly = true)
    public ManagementDto.TeacherDirectory teachers(Principals.User caller, String q, int page, int size) {
        var matched = management.teachers(caller).stream()
                .filter(t -> matches(q, t.displayName(), t.email())).toList();
        return new ManagementDto.TeacherDirectory(page(page), size(size), matched.size(), slice(matched, page, size));
    }

    /** Every coordinator of the department, with her subjects and her tracks, matched the same way. */
    @Transactional(readOnly = true)
    public ManagementDto.CoordinatorDirectory coordinators(Principals.User caller, String q, int page, int size) {
        var matched = management.coordinators(caller).stream()
                .filter(c -> matches(q, c.displayName(), c.email())).toList();
        return new ManagementDto.CoordinatorDirectory(page(page), size(size), matched.size(), slice(matched, page, size));
    }

    // ---------------------------------------------------------------- paging and matching

    private static <T> List<T> slice(List<T> all, int page, int size) {
        int rows = size(size), from = page(page) * rows;
        return from >= all.size() ? List.of() : List.copyOf(all.subList(from, Math.min(all.size(), from + rows)));
    }

    /** `size=0` (the default nobody set) is {@link #DEFAULT_SIZE}; anything above {@link #MAX_SIZE} is refused. */
    private static int size(int size) {
        if (size < 0 || size > MAX_SIZE) throw ApiException.badRequest("`size` is between 1 and " + MAX_SIZE + ".");
        return size == 0 ? DEFAULT_SIZE : size;
    }

    private static int page(int page) {
        if (page < 0) throw ApiException.badRequest("`page` counts from 0.");
        return page;
    }

    /**
     * `%maya%`, lower-cased, or `%` when nothing was asked for — the statement always carries a pattern, so neither
     * `like` needs an `is null` test.
     *
     * <p><strong>A search box is literal text.</strong> `%`, `_` and the escape character itself are wildcards to
     * SQL and three ordinary characters to the person typing them, so each is escaped here and both statements in
     * {@link ChildRepository} declare {@code escape '\'}. Without this, `%` matches every child of the department
     * and `_` matches any single character — a search that quietly widens rather than narrows.
     */
    static String pattern(String q) {
        if (q == null || q.isBlank()) return "%";
        var out = new StringBuilder("%");
        for (char c : q.trim().toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '%' || c == '_' || c == ESCAPE) out.append(ESCAPE);
            out.append(c);
        }
        return out.append('%').toString();
    }

    /** The in-memory half of the same rule: a blank `q` matches everybody. */
    private static boolean matches(String q, String... fields) {
        if (q == null || q.isBlank()) return true;
        String needle = q.trim().toLowerCase(Locale.ROOT);
        for (String field : fields) if (field != null && field.toLowerCase(Locale.ROOT).contains(needle)) return true;
        return false;
    }
}
