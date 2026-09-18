package quest.server.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * `docs/teacher-flow.md` §2: <strong>"everything the teacher sees and does is limited to her assignments … API calls
 * outside them return 403, whether or not the UI was bypassed."</strong> A rule that lives only in review is a rule
 * that a new route forgets, so it is checked here.
 *
 * <p><strong>What the rule proves, exactly.</strong> Every `@RestController` that serves a `/teacher/**` route
 * depends on {@link TeacherScope} — directly, or transitively through the services it calls. ArchUnit reasons about
 * class-level dependencies, not about which branch of a method runs, so this is the strongest form the rule can
 * take: it cannot prove that a given handler <em>calls</em> a check on every path, only that the check is reachable
 * from the controller at all. What it does guarantee is the thing that actually goes wrong in practice — a new
 * `/teacher/**` controller wired straight to a repository, with no scope check anywhere in its reach, fails the
 * build.
 *
 * <p>The three checks themselves are {@link TeacherScope#requireClass}, {@link TeacherScope#requireAssignment} and
 * {@link TeacherScope#assignmentsOf}; the second half of the guarantee is that they are the only way a class is
 * resolved, which {@link TeacherScope#section} enforces by refusing a pre-V7 row and letting the `school` filter
 * refuse another school's.
 */
class TeacherScopeArchitectureTest {
    private static final JavaClasses SERVER = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("quest.server");

    private static final String SCOPE = TeacherScope.class.getName();

    @Test void every_teacher_controller_reaches_the_teacher_scope() {
        var missing = new ArrayList<String>();
        var controllers = teacherControllers();
        for (JavaClass controller : controllers)
            if (!reaches(controller, SCOPE)) missing.add(controller.getSimpleName());
        assertThat(controllers).as("the sweep must actually find the /teacher/** controllers").isNotEmpty();
        assertThat(missing)
                .as("a /teacher/** controller must resolve its class through TeacherScope (directly or through a service that does)")
                .isEmpty();
    }

    /** The checks live in one class, so a second copy of the rule cannot drift away from the first. */
    @Test void the_scope_is_the_only_place_that_decides_what_a_teacher_reaches() {
        var offenders = SERVER.stream()
                .filter(c -> !c.getName().equals(SCOPE) && !c.getPackageName().equals("quest.server.tenancy"))
                .filter(c -> c.getDirectDependenciesFromSelf().stream()
                        .anyMatch(d -> d.getTargetClass().getName().equals(TeachingAssignmentRepository.class.getName())))
                .map(JavaClass::getSimpleName).sorted().toList();
        assertThat(offenders)
                .as("read `teaching_assignments` through TeacherScope, not straight from the repository")
                .containsExactly("SectionService", "TeachingStaffService");
    }

    /** Every `@RestController` with at least one mapping under `/teacher`. */
    private static List<JavaClass> teacherControllers() {
        var out = new ArrayList<JavaClass>();
        SERVER.stream().filter(c -> c.isAnnotatedWith(RestController.class)).forEach(c -> {
            if (serves(c.reflect())) out.add(c);
        });
        return out;
    }

    private static boolean serves(Class<?> controller) {
        var prefixes = paths(AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class));
        for (var method : controller.getDeclaredMethods()) {
            var mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping == null) continue;
            for (String prefix : prefixes.isEmpty() ? List.of("") : prefixes)
                for (String path : paths(mapping)) if ((prefix + path).startsWith("/teacher")) return true;
        }
        return false;
    }

    private static List<String> paths(RequestMapping mapping) {
        if (mapping == null) return List.of();
        String[] declared = mapping.path().length > 0 ? mapping.path() : mapping.value();
        return List.of(declared);
    }

    /** Breadth-first over class-level dependencies, inside `quest.server` only. */
    private static boolean reaches(JavaClass from, String target) {
        Set<String> seen = new LinkedHashSet<>();
        var queue = new ArrayDeque<JavaClass>();
        queue.add(from);
        while (!queue.isEmpty()) {
            var current = queue.poll();
            if (!seen.add(current.getName())) continue;
            if (current.getName().equals(target)) return true;
            for (var dependency : current.getDirectDependenciesFromSelf()) {
                var next = dependency.getTargetClass();
                if (next.getPackageName().startsWith("quest.server") && !seen.contains(next.getName())) queue.add(next);
            }
        }
        return false;
    }
}
