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

    @Test void every_management_handler_reaches_a_scope_check() {
        var missing = new ArrayList<String>();
        int handlers = 0;
        for (JavaClass controller : managementControllers())
            for (JavaMethod method : controller.getMethods()) {
                if (routes(controller, method).isEmpty()) continue;
                handlers++;
                if (!reachesACheck(method)) missing.add(controller.getSimpleName() + "#" + method.getName());
            }
        assertThat(handlers).as("the sweep must actually find the /management handlers").isGreaterThanOrEqualTo(15);
        assertThat(missing).as("a /management handler must resolve its scope through ManagerScope (%s) — "
                + "ManagerScope.require only checks that somebody is signed in", CHECKS).isEmpty();
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
            "POST /management/chat/threads",
            "POST /management/chat/threads/{id}/messages",
            "POST /management/chat/threads/{id}/read");

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
    private static boolean reachesACheck(JavaMethod from) {
        Set<String> seen = new LinkedHashSet<>();
        var queue = new ArrayDeque<JavaMethod>();
        queue.add(from);
        while (!queue.isEmpty()) {
            var current = queue.poll();
            if (!seen.add(current.getFullName())) continue;
            if (current.getOwner().getName().equals(SCOPE) && CHECKS.contains(current.getName())) return true;
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
