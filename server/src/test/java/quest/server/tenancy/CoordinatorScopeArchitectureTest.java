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
 * DR2 for the fourth role, in the shape `TeacherScopeArchitectureTest` gives §2: <strong>every `/coordinator` route
 * resolves what it names through {@link CoordinatorScope}</strong>, and <strong>none of them writes</strong>.
 *
 * <p>Two rules, because a coordinator has two ways to be wrong. The first is the teacher's: a handler wired straight
 * to a repository, with no scope check anywhere in its reach, would answer with the whole school. The second is this
 * package's own — R2 is a read namespace, and the first POST added under it would quietly make a read-only role a
 * writing one, so the verb is asserted rather than reviewed. R4 adds the communication writes, and does it by
 * amending this list with the argument for each one.
 */
class CoordinatorScopeArchitectureTest {
    private static final JavaClasses SERVER = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("quest.server");

    private static final String SCOPE = CoordinatorScope.class.getName();

    /**
     * The methods of {@link CoordinatorScope} that narrow what the caller reaches. {@code require} is deliberately
     * not one of them — it turns a missing token into a 401 and says nothing about whose class or lesson is in the
     * path, so a handler that calls only that is exactly the bug this catches.
     */
    private static final Set<String> CHECKS = Set.of("reach", "sectionsOf", "requireSection", "requireLesson", "requireChild", "scopesOf");

    @Test void every_coordinator_controller_reaches_the_coordinator_scope() {
        var controllers = coordinatorControllers();
        assertThat(controllers).as("the sweep must actually find the /coordinator controllers").isNotEmpty();
        assertThat(controllers.stream().filter(c -> !reaches(c)).map(JavaClass::getSimpleName).toList())
                .as("a /coordinator controller must resolve what it names through CoordinatorScope (directly or through a service that does)")
                .isEmpty();
    }

    @Test void every_coordinator_handler_reaches_a_scope_check() {
        var missing = new ArrayList<String>();
        int handlers = 0;
        for (JavaClass controller : coordinatorControllers())
            for (JavaMethod method : controller.getMethods()) {
                if (routes(controller, method).isEmpty()) continue;
                handlers++;
                if (!reachesACheck(method)) missing.add(controller.getSimpleName() + "#" + method.getName());
            }
        assertThat(handlers).as("the sweep must actually find the /coordinator handlers").isGreaterThanOrEqualTo(6);
        assertThat(missing).as("a /coordinator handler must resolve its scope through CoordinatorScope (%s) — "
                + "CoordinatorScope.require only checks that somebody is signed in", CHECKS).isEmpty();
    }

    /** DR2: her writes are communication and arrive with R4, so this package serves reads and nothing else. */
    @Test void the_coordinator_namespace_is_read_only() {
        var writes = new ArrayList<String>();
        for (JavaClass controller : coordinatorControllers())
            for (JavaMethod method : controller.getMethods())
                for (String route : routes(controller, method))
                    if (!route.startsWith("GET ")) writes.add(controller.getSimpleName() + "#" + method.getName() + " -> " + route);
        assertThat(writes).as("DR2: `/coordinator` is read-only until R4 adds the communication routes").isEmpty();
    }

    // ---------------------------------------------------------------- the sweep

    /** `METHOD /path` for every route a handler declares under `/coordinator`, class-level prefix included. */
    private static List<String> routes(JavaClass controller, JavaMethod method) {
        var mapping = AnnotatedElementUtils.findMergedAnnotation(method.reflect(), RequestMapping.class);
        if (mapping == null) return List.of();
        var prefixes = paths(AnnotatedElementUtils.findMergedAnnotation(controller.reflect(), RequestMapping.class));
        var out = new ArrayList<String>();
        for (RequestMethod verb : mapping.method().length > 0 ? mapping.method() : RequestMethod.values())
            for (String prefix : prefixes.isEmpty() ? List.of("") : prefixes)
                for (String path : paths(mapping)) if ((prefix + path).startsWith("/coordinator")) out.add(verb.name() + " " + prefix + path);
        return out;
    }

    private static List<String> paths(RequestMapping mapping) {
        if (mapping == null) return List.of();
        String[] declared = mapping.path().length > 0 ? mapping.path() : mapping.value();
        return List.of(declared);
    }

    private static List<JavaClass> coordinatorControllers() {
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
