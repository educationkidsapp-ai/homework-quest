package quest.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * §5: the permission matrix is defined once, and the annotation on a route is the key `permissions.json` gives that
 * route — not merely *a* key that file happens to declare. Checking only that the key exists somewhere let
 * `@permit.has('lesson.read')` sit on `POST /admin/schools` and still pass, which would hand every Teacher the power
 * to create schools; here the annotation is compared with `Permissions.find(method, path)` for the very route the
 * mapping declares. `permitAll` is checked the same way, from the other side: the route's row must be one that grants
 * `PUBLIC`, so a route cannot be opened to the world by dropping its `@permit.has` for a `permitAll`.
 *
 * <p>`PermissionsTest` checks the remaining direction — that every route the server actually serves has a row.
 */
class PreAuthorizeCoverageTest {
    private static final Pattern KEY = Pattern.compile("@permit\\.has\\('([^']+)'\\)");
    private static final Set<String> ALLOWED_PLAIN = Set.of("permitAll");

    private final Permissions permissions = new Permissions(new com.fasterxml.jackson.databind.ObjectMapper());

    @Test void every_endpoint_carries_the_preauthorize_that_permissions_json_gives_its_route() {
        List<String> unannotated = new ArrayList<>();
        List<String> wrong = new ArrayList<>();

        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) continue;
                var mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) continue;
                String where = controller.getSimpleName() + "#" + method.getName();

                var pre = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
                if (pre == null) { unannotated.add(where); continue; }
                String expression = pre.value().trim();
                Matcher m = KEY.matcher(expression);
                String annotated = m.matches() ? m.group(1) : null;
                if (annotated == null && !ALLOWED_PLAIN.contains(expression)) { wrong.add(where + " -> " + expression + " is neither permitAll nor @permit.has('…')"); continue; }

                var routes = routes(controller, mapping);
                if (routes.isEmpty()) { wrong.add(where + " -> the mapping declares no HTTP method and path to check against permissions.json"); continue; }
                for (String route : routes) {
                    String[] parts = route.split(" ", 2);
                    var declared = permissions.find(parts[0], parts[1]).orElse(null);
                    if (declared == null) { wrong.add(where + " -> " + route + " has no row in permissions.json"); continue; }
                    if (annotated == null) {           // permitAll: only legitimate when the row itself is public
                        if (!permissions.roles(declared.permission()).contains(Permissions.PUBLIC))
                            wrong.add(where + " -> " + route + " is permitAll but " + declared.permission() + " is not granted to PUBLIC");
                    } else if (!annotated.equals(declared.permission())) {
                        wrong.add(where + " -> " + route + " is annotated " + annotated + " but permissions.json says " + declared.permission());
                    }
                }
            }
        }

        assertThat(unannotated).as("annotate these with @PreAuthorize(\"@permit.has('<key from permissions.json>')\") or permitAll").isEmpty();
        assertThat(wrong).as("each annotation must carry exactly the key permissions.json gives that route").isEmpty();
    }

    @Test void the_controllers_are_actually_found() {
        assertThat(controllers()).as("the reflection sweep must see the whole API, not an empty list").hasSizeGreaterThan(5);
    }

    /** `METHOD /path` for every route a handler declares, class-level `@RequestMapping` prefix included. */
    private static List<String> routes(Class<?> controller, RequestMapping mapping) {
        var prefixes = paths(AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class));
        var out = new ArrayList<String>();
        for (RequestMethod verb : mapping.method())
            for (String prefix : prefixes.isEmpty() ? List.of("") : prefixes)
                for (String path : paths(mapping)) out.add(verb.name() + " " + prefix + path);
        return out;
    }

    private static List<String> paths(RequestMapping mapping) {
        if (mapping == null) return List.of();
        String[] declared = mapping.path().length > 0 ? mapping.path() : mapping.value();
        return List.of(declared);
    }

    /**
     * Every `@RestController` in the server, found the way ArchUnit finds classes (no Spring context needed).
     * `DoNotIncludeTests` keeps `target/test-classes` out: a controller a test stands up in its own context (P2.1's
     * flag probes) is not part of the API and has no row in `permissions.json` to be checked against.
     */
    private List<Class<?>> controllers() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests()).importPackages("quest.server");
        var out = new LinkedHashSet<Class<?>>();
        classes.stream().filter(c -> c.isAnnotatedWith(RestController.class)).forEach(c -> out.add(c.reflect()));
        return List.copyOf(out);
    }
}
