package quest.server.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * DR5 for the department manager, in the shape {@link CoordinatorScopeArchitectureTest} gives the coordinator:
 * <strong>every `/management` route resolves what it names through {@link ManagerScope}</strong>, and
 * <strong>none of them writes</strong>.
 *
 * <p>Two rules, because a manager has the same two ways to be wrong. The first is the teacher's and the coordinator's:
 * a handler wired straight to a repository, with no scope check anywhere in its reach, would answer with the whole
 * school — both departments, which is the very thing DR5 separates. The second is RM1's own: the area is read-only
 * until RM2 brings broadcasts and RM5 brings staff attendance, so the first write added under `/management` would
 * quietly make a reading role a writing one, and the verb is asserted rather than reviewed. When RM2 lands, its writes
 * are named in {@link #ALLOWED_WRITES} one argued line each, exactly as R4's are next door.
 */
class ManagerScopeArchitectureTest {
    private static final JavaClasses SERVER = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("quest.server");

    private static final String SCOPE = ManagerScope.class.getName();

    /**
     * The methods of {@link ManagerScope} that narrow what the caller reaches. {@code require} is deliberately
     * not one of them — it turns a missing token into a 401 and says nothing about whose class or lesson is in the
     * path, so a handler that calls only that is exactly the bug this catches.
     */
    private static final Set<String> CHECKS = Set.of("reach", "sectionsOf", "requireSection", "requireLesson",
            "requireChild", "departments", "coordinatorsOf", "teachersOf");

    @Test void every_management_controller_reaches_the_manager_scope() {
        var controllers = managementControllers();
        assertThat(controllers).as("the sweep must actually find the /management controllers").isNotEmpty();
        assertThat(controllers.stream().filter(c -> !reaches(c)).map(JavaClass::getSimpleName).toList())
                .as("a /management controller must resolve what it names through ManagerScope (directly or through a service that does)")
                .isEmpty();
    }

    /**
     * The one shape of handler {@link #CHECKS} cannot speak for: a read of rows keyed by
     * <strong>`(school_id, author_id)`</strong> — the leader's own, nobody else's. A department check on these would
     * not narrow them, it would <em>widen</em> them: it would let the other manager of the same department read what
     * this one uploaded, which is the opposite of what feature 26 promises. So they are named here one line each, and
     * the exemption is <em>proved</em> rather than trusted — {@link #author_scoped_reads_really_are_keyed_by_author}
     * fails unless each of them reaches an author-keyed query. Nothing else is excused.
     */
    private static final Set<String> AUTHOR_SCOPED_READS = Set.of(
            // Feature 26: her own last twenty document summaries, and one of them.
            "DocumentReaderController#myDocumentSummaries",
            "DocumentReaderController#documentSummary");

    /** The queries that make an {@link #AUTHOR_SCOPED_READS} entry true: both name the author beside the school. */
    private static final Set<String> AUTHOR_KEYED_QUERIES = Set.of("findOneOfAuthor", "byAuthor");

    @Test void every_management_handler_reaches_a_scope_check() {
        var missing = new ArrayList<String>();
        int handlers = 0;
        for (JavaClass controller : managementControllers())
            for (JavaMethod method : controller.getMethods()) {
                if (routes(controller, method).isEmpty()) continue;
                handlers++;
                String handler = controller.getSimpleName() + "#" + method.getName();
                if (AUTHOR_SCOPED_READS.contains(handler)) continue;
                if (!reachesACheck(method)) missing.add(handler);
            }
        assertThat(handlers).as("the sweep must actually find the /management handlers").isGreaterThanOrEqualTo(15);
        assertThat(missing).as("a /management handler must resolve its scope through ManagerScope (%s) — "
                + "ManagerScope.require only checks that somebody is signed in", CHECKS).isEmpty();
    }

    /**
     * What {@link #AUTHOR_SCOPED_READS} claims, checked: each exempted handler really does reach a query that names
     * the author, so "narrower than any department" is a fact about the code and not a sentence in a comment. An
     * entry added to that list for a handler that reads by school alone fails here.
     */
    @Test void author_scoped_reads_really_are_keyed_by_author() {
        var unproved = new ArrayList<String>();
        for (JavaClass controller : managementControllers())
            for (JavaMethod method : controller.getMethods()) {
                String handler = controller.getSimpleName() + "#" + method.getName();
                if (!AUTHOR_SCOPED_READS.contains(handler)) continue;
                for (String route : routes(controller, method))
                    if (!route.startsWith("GET ")) unproved.add(handler + " is a write (" + route + "), not a read");
                if (!reachesAny(method, AUTHOR_KEYED_QUERIES)) unproved.add(handler + " reaches no author-keyed query");
            }
        assertThat(unproved).as("an exempted handler must be a GET that reads by author (%s)", AUTHOR_KEYED_QUERIES).isEmpty();
        var handlers = new ArrayList<String>();
        for (JavaClass controller : managementControllers())
            for (JavaMethod method : controller.getMethods())
                if (!routes(controller, method).isEmpty()) handlers.add(controller.getSimpleName() + "#" + method.getName());
        assertThat(handlers).as("an exempted handler was renamed or removed: update AUTHOR_SCOPED_READS").containsAll(AUTHOR_SCOPED_READS);
    }

    /**
     * DR5: RM1 was read-only. The manager's writes arrive package by package, behind their own key (and their own flag
     * where the feature has one), and each is named here with the argument for it. An unlisted write appearing under
     * `/management` is the thing to stop: a read namespace that grows an editor.
     *
     * <p>RM5's one write is the staff register. It writes about the <em>staff</em> of her own department and never
     * about a child, a lesson or a class: the roster it upserts is {@link ManagerScope#teachersOf} and
     * {@link ManagerScope#coordinatorsOf}, so a `userId` the department does not hold is a 403 before a row is touched,
     * and no other part of the area became writable with it.
     *
     * <p>RM2 (DR6, DR5) brings four, all of them the manager <em>speaking</em> rather than editing teaching data:
     * <ul>
     *   <li>`POST /management/broadcasts` — the weekly plan, an announcement or an event for her department, behind
     *       the `announcements` flag and `management.broadcast`. It writes a `broadcasts` row and notifications, and
     *       touches nothing a teacher owns.</li>
     *   <li>the three chat writes — her thread with a coordinator or the admin, a message in one of her threads, and
     *       the read receipt — behind the `chat` flag and `management.chat`, on `chat_threads` rows that are hers.</li>
     * </ul>
     */
    private static final Set<String> ALLOWED_WRITES = Set.of(
            "PUT /management/staff-attendance",
            "POST /management/broadcasts",
            // Feature 22: the same manager speaking, one step earlier. It writes one `announcement_drafts` row — her
            // own unsent text — tells nobody, and posts nothing; Send is `POST /management/broadcasts` above. Behind
            // the `voiceAnnouncement` flag and `broadcast.voice`, and its cells come from `ManagerScope.reach`.
            "POST /management/broadcasts/voice-drafts",
            // Feature 26: she uploads a circular and one `document_summaries` row — her own read of it — is stored.
            // It delivers nothing, writes nothing a teacher owns, keeps no copy of the file, and the announcement it
            // suggests is addressed to `ManagerScope.departments`; Send is `POST /management/broadcasts` above.
            "POST /management/documents/read",
            "POST /management/chat/threads",
            "POST /management/chat/threads/{id}/messages",
            "POST /management/chat/threads/{id}/read",
            // B6: her Complaints page — a reply and a read on a complaint addressed to her, and `resolved`/`open` on one
            // addressed to her or to a teacher or coordinator of her department. `chat_threads` rows, nothing a teacher owns.
            "POST /management/complaints/{id}/messages",
            "POST /management/complaints/{id}/read",
            "PATCH /management/complaints/{id}/status");

    @Test void the_management_namespace_writes_only_what_rm2_added() {
        var writes = new ArrayList<String>();
        for (JavaClass controller : managementControllers())
            for (JavaMethod method : controller.getMethods())
                for (String route : routes(controller, method))
                    if (!route.startsWith("GET ") && !ALLOWED_WRITES.contains(route))
                        writes.add(controller.getSimpleName() + "#" + method.getName() + " -> " + route);
        assertThat(writes).as("DR5: the area reads; a write under `/management` is argued for in ALLOWED_WRITES (%s)", ALLOWED_WRITES).isEmpty();
        var declared = new ArrayList<String>();
        for (JavaClass controller : managementControllers())
            for (JavaMethod method : controller.getMethods()) declared.addAll(routes(controller, method));
        assertThat(declared).as("a route on the allow-list was renamed or removed: update the list").containsAll(ALLOWED_WRITES);
    }

    // ---------------------------------------------------------------- the sweep

    /** `METHOD /path` for every route a handler declares under `/management`, class-level prefix included. */
    private static List<String> routes(JavaClass controller, JavaMethod method) {
        var mapping = AnnotatedElementUtils.findMergedAnnotation(method.reflect(), RequestMapping.class);
        if (mapping == null) return List.of();
        var prefixes = paths(AnnotatedElementUtils.findMergedAnnotation(controller.reflect(), RequestMapping.class));
        var out = new ArrayList<String>();
        for (RequestMethod verb : mapping.method().length > 0 ? mapping.method() : RequestMethod.values())
            for (String prefix : prefixes.isEmpty() ? List.of("") : prefixes)
                for (String path : paths(mapping)) if ((prefix + path).startsWith("/management")) out.add(verb.name() + " " + prefix + path);
        return out;
    }

    private static List<String> paths(RequestMapping mapping) {
        if (mapping == null) return List.of();
        String[] declared = mapping.path().length > 0 ? mapping.path() : mapping.value();
        return List.of(declared);
    }

    private static List<JavaClass> managementControllers() {
        var out = new ArrayList<JavaClass>();
        SERVER.stream().filter(c -> c.isAnnotatedWith(RestController.class)).forEach(c -> {
            for (JavaMethod method : c.getMethods()) if (!routes(c, method).isEmpty()) { out.add(c); return; }
        });
        return out;
    }

    /** Breadth-first over the call graph inside `quest.server`, looking for one of {@link #CHECKS}. */
    private static boolean reachesACheck(JavaMethod from) { return reaches(from, SCOPE, CHECKS); }

    /** The same walk, for a method of any owner — what {@link #AUTHOR_SCOPED_READS} is checked with. */
    private static boolean reachesAny(JavaMethod from, Set<String> names) { return reaches(from, null, names); }

    private static boolean reaches(JavaMethod from, String owner, Set<String> names) {
        Set<String> seen = new LinkedHashSet<>();
        var queue = new ArrayDeque<JavaMethod>();
        queue.add(from);
        while (!queue.isEmpty()) {
            var current = queue.poll();
            if (!seen.add(current.getFullName())) continue;
            if ((owner == null || current.getOwner().getName().equals(owner)) && names.contains(current.getName())) return true;
            for (var call : current.getMethodCallsFromSelf()) {
                if (!call.getTargetOwner().getPackageName().startsWith("quest.server")) continue;
                call.getTarget().resolveMember().ifPresent(target -> { if (!seen.contains(target.getFullName())) queue.add(target); });
            }
        }
        return false;
    }

    /** Breadth-first over class-level dependencies, inside `quest.server` only. */
    private static boolean reaches(JavaClass from) {
        Set<String> seen = new LinkedHashSet<>();
        var queue = new ArrayDeque<JavaClass>();
        queue.add(from);
        while (!queue.isEmpty()) {
            var current = queue.poll();
            if (!seen.add(current.getName())) continue;
            if (current.getName().equals(SCOPE)) return true;
            for (var dependency : current.getDirectDependenciesFromSelf()) {
                var next = dependency.getTargetClass();
                if (next.getPackageName().startsWith("quest.server") && !seen.contains(next.getName())) queue.add(next);
            }
        }
        return false;
    }
}
