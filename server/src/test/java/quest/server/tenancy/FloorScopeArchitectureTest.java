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
 * Feature 20 for the sixth role, in the shape {@code CoordinatorScopeArchitectureTest} gives the fourth:
 * <strong>every `/floor` route resolves what it names through {@link FloorScope}</strong>, and <strong>the only
 * writes under `/floor` are her Messages</strong>.
 *
 * <p>Two rules, because a floor supervisor has two ways to be wrong. The first is the teacher's and the
 * coordinator's: a handler wired straight to a repository, with no scope check anywhere in its reach, would answer
 * with the whole school. The second is this namespace's own — her area is read-only on everything except
 * communication, so the first POST added under it would quietly make a read-only role a writing one, and the verb is
 * asserted rather than reviewed.
 */
class FloorScopeArchitectureTest {
    private static final JavaClasses SERVER = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("quest.server");

    private static final String SCOPE = FloorScope.class.getName();

    /**
     * The methods of {@link FloorScope} that narrow what the caller reaches. {@code require} is deliberately not one
     * of them — it turns a missing token into a 401 and says nothing about whose class or child is in the path, so a
     * handler that calls only that is exactly the bug this catches.
     */
    private static final Set<String> CHECKS = Set.of("reach", "floorsOf", "requireSection", "requireChild",
            "requireTeacher", "teacherIdsOf");

    @Test void every_floor_controller_reaches_the_floor_scope() {
        var controllers = floorControllers();
        assertThat(controllers).as("the sweep must actually find the /floor controllers").isNotEmpty();
        assertThat(controllers.stream().filter(c -> !reaches(c)).map(JavaClass::getSimpleName).toList())
                .as("a /floor controller must resolve what it names through FloorScope (directly or through a service that does)")
                .isEmpty();
    }

    @Test void every_floor_handler_reaches_a_scope_check() {
        var missing = new ArrayList<String>();
        int handlers = 0;
        for (JavaClass controller : floorControllers())
            for (JavaMethod method : controller.getMethods()) {
                if (routes(controller, method).isEmpty()) continue;
                handlers++;
                if (!reachesACheck(method)) missing.add(controller.getSimpleName() + "#" + method.getName());
            }
        assertThat(handlers).as("the sweep must actually find the /floor handlers").isGreaterThanOrEqualTo(10);
        assertThat(missing).as("a /floor handler must resolve its scope through FloorScope (%s) — "
                + "FloorScope.require only checks that somebody is signed in", CHECKS).isEmpty();
    }

    /**
     * Her reads are read-only, and the only writes under `/floor` are her Messages, one argued line each.
     *
     * <ul>
     *   <li>{@code POST /floor/chat/threads} — her thread with a parent of her floor, a teacher on it, a manager or
     *       the Admin. She is one of the two people on it, so nobody else can open it for her.</li>
     *   <li>{@code POST …/threads/{id}/messages} and {@code …/read} — the coordinator's two chat writes, thread-keyed
     *       because one of her threads has no child on it. Both go through `ChatService`, which checks the peer.</li>
     * </ul>
     */
    private static final Set<String> COMMUNICATION_WRITES = Set.of(
            "POST /floor/chat/threads", "POST /floor/chat/threads/{id}/messages", "POST /floor/chat/threads/{id}/read");

    @Test void the_only_writes_in_the_floor_namespace_are_her_messages() {
        var writes = new ArrayList<String>();
        for (JavaClass controller : floorControllers())
            for (JavaMethod method : controller.getMethods())
                for (String route : routes(controller, method))
                    if (!route.startsWith("GET ") && !COMMUNICATION_WRITES.contains(route))
                        writes.add(controller.getSimpleName() + "#" + method.getName() + " -> " + route);
        assertThat(writes).as("`/floor` writes nothing but her Messages (%s)", COMMUNICATION_WRITES).isEmpty();
        var declared = new ArrayList<String>();
        for (JavaClass controller : floorControllers())
            for (JavaMethod method : controller.getMethods()) declared.addAll(routes(controller, method));
        assertThat(declared).as("a route on the allow-list was renamed or removed: update the list").containsAll(COMMUNICATION_WRITES);
    }

    // ---------------------------------------------------------------- the sweep

    /** `METHOD /path` for every route a handler declares under `/floor`, class-level prefix included. */
    private static List<String> routes(JavaClass controller, JavaMethod method) {
        var mapping = AnnotatedElementUtils.findMergedAnnotation(method.reflect(), RequestMapping.class);
        if (mapping == null) return List.of();
        var prefixes = paths(AnnotatedElementUtils.findMergedAnnotation(controller.reflect(), RequestMapping.class));
        var out = new ArrayList<String>();
        for (RequestMethod verb : mapping.method().length > 0 ? mapping.method() : RequestMethod.values())
            for (String prefix : prefixes.isEmpty() ? List.of("") : prefixes)
                for (String path : paths(mapping)) if ((prefix + path).startsWith("/floor/")) out.add(verb.name() + " " + prefix + path);
        return out;
    }

    private static List<String> paths(RequestMapping mapping) {
        if (mapping == null) return List.of();
        String[] declared = mapping.path().length > 0 ? mapping.path() : mapping.value();
        return List.of(declared);
    }

    private static List<JavaClass> floorControllers() {
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
