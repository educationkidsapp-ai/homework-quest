package quest.server.ai;

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
import org.springframework.web.bind.annotation.RestController;

/**
 * Feature 30, the owner's rule: <strong>no AI call is possible from any child-facing endpoint.</strong>
 *
 * <p>Hints and practice sets are written once, by the lesson's `HINTS` pipeline step, and approved by a teacher
 * before a child ever sees them. The reason that is worth a rule rather than a convention is what the alternative
 * looks like: a hint generated at play time is a model call on the critical path of a six-year-old's wrong answer —
 * paid for per child per mistake, unreviewed, unbounded in what it might say, and impossible to take back.
 *
 * <p>So the rule is asserted over the call graph, in the shape `CoordinatorScopeArchitectureTest` gives §2's scope
 * rule: breadth-first from each child-facing handler through every method it calls inside `quest.server`, and
 * neither {@link AiService} nor {@link quest.server.analysis.LlmClient} may be reachable. A service that grows an AI
 * dependency and is also used by the map fails here, which is the point — the mistake this catches is nobody writing
 * a child-facing prompt, it is a helper that quietly becomes one.
 *
 * <p>{@link quest.server.children.NoAiFromChildEndpointsTest} is the same rule at run time, with the provider
 * replaced by a mock that must not be touched. The two catch different things: this one sees code that is never
 * called in a test, and that one sees a call this one cannot resolve statically.
 */
class ChildEndpointsHaveNoAiTest {
    private static final JavaClasses SERVER = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("quest.server");

    /** The two classes a child-facing request may never reach: the one loop, and the provider under it. */
    private static final Set<String> FORBIDDEN = Set.of(AiService.class.getName(), quest.server.analysis.LlmClient.class.getName());

    /**
     * What a child's or her parent's phone calls: her map and her lesson, what she answers, the pictures she uploads
     * and the media she reads back. `/children/**` covers P4.0's teacher-question routes too, which is why they need
     * no naming of their own, and `/media/**` is listed whole because a page crop and a recording are served to the
     * same tablet.
     */
    private static final List<String> CHILD_FACING = List.of("/children", "/lessons/", "/media/");

    /**
     * The exam-attempt path, which no route of its own leads to: a child hands her paper in through
     * `POST /children/{id}/attempts` like any other answer, and the exam half of that is this service. Named
     * explicitly so a model call added inside it is caught even if the sweep of the handler above ever misses it.
     */
    private static final List<Class<?>> ALSO = List.of(quest.server.exams.ExamAttemptService.class, quest.server.children.AttemptService.class);

    @Test void no_child_facing_handler_can_reach_the_ai() {
        var roots = childFacingHandlers();
        assertThat(roots).as("the sweep must actually find the child-facing handlers").hasSizeGreaterThanOrEqualTo(8);
        var reaching = new ArrayList<String>();
        for (JavaMethod handler : roots) {
            String found = reaches(handler);
            if (found != null) reaching.add(handler.getOwner().getSimpleName() + "#" + handler.getName() + " -> " + found);
        }
        assertThat(reaching).as("feature 30: generation happens at lesson build time only — a child-facing route may "
                + "never reach %s", FORBIDDEN).isEmpty();
    }

    @Test void the_attempt_services_can_reach_the_ai_from_no_method_at_all() {
        var reaching = new ArrayList<String>();
        for (Class<?> type : ALSO)
            for (JavaMethod method : SERVER.get(type).getMethods()) {
                String found = reaches(method);
                if (found != null) reaching.add(type.getSimpleName() + "#" + method.getName() + " -> " + found);
            }
        assertThat(reaching).as("an attempt — a homework's or an exam's — is scored on the server with no model in it").isEmpty();
    }

    @Test void the_sweep_can_see_an_ai_call_when_there_is_one() {
        // the guard on the guard: the same breadth-first walk from a route that really does ask a model
        var generate = SERVER.get(quest.server.analysis.GenerationService.class).getMethods().stream()
                .filter(m -> m.getName().equals("generatePlay")).findFirst().orElseThrow();
        assertThat(reaches(generate)).as("a walk that finds nothing anywhere proves nothing").isNotNull();
    }

    // ---------------------------------------------------------------- the sweep

    /** The name of the forbidden class this method can reach, or null. Breadth-first inside `quest.server` only. */
    private static String reaches(JavaMethod from) {
        Set<String> seen = new LinkedHashSet<>();
        var queue = new ArrayDeque<JavaMethod>();
        queue.add(from);
        while (!queue.isEmpty()) {
            var current = queue.poll();
            if (!seen.add(current.getFullName())) continue;
            if (FORBIDDEN.contains(current.getOwner().getName())) return current.getOwner().getSimpleName() + "#" + current.getName();
            for (var call : current.getMethodCallsFromSelf()) {
                if (!call.getTargetOwner().getPackageName().startsWith("quest.server")) continue;
                if (FORBIDDEN.contains(call.getTargetOwner().getName())) return call.getTargetOwner().getSimpleName() + "#" + call.getName();
                call.getTarget().resolveMember().ifPresent(target -> { if (!seen.contains(target.getFullName())) queue.add(target); });
            }
        }
        return null;
    }

    /** Every handler of a `@RestController` whose route a child's or her parent's app calls. */
    private static List<JavaMethod> childFacingHandlers() {
        var out = new ArrayList<JavaMethod>();
        SERVER.stream().filter(c -> c.isAnnotatedWith(RestController.class)).forEach(controller -> {
            for (JavaMethod method : controller.getMethods()) if (!routes(controller, method).isEmpty()) out.add(method);
        });
        return out;
    }

    /** The child-facing paths one handler declares, class-level prefix included. */
    private static List<String> routes(JavaClass controller, JavaMethod method) {
        var mapping = AnnotatedElementUtils.findMergedAnnotation(method.reflect(), RequestMapping.class);
        if (mapping == null) return List.of();
        var prefixes = paths(AnnotatedElementUtils.findMergedAnnotation(controller.reflect(), RequestMapping.class));
        var out = new ArrayList<String>();
        for (String prefix : prefixes.isEmpty() ? List.of("") : prefixes)
            for (String path : paths(mapping)) {
                String whole = prefix + path;
                if (CHILD_FACING.stream().anyMatch(whole::startsWith)) out.add(whole);
            }
        return out;
    }

    private static List<String> paths(RequestMapping mapping) {
        if (mapping == null) return List.of();
        String[] declared = mapping.path().length > 0 ? mapping.path() : mapping.value();
        return List.of(declared);
    }
}
