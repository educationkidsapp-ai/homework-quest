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
import org.springframework.web.bind.annotation.RestController;

/**
 * §5: the permission matrix is defined once. Every endpoint carries a `@PreAuthorize`, and its key exists in
 * `permissions.json` — a new route without one fails here, next to `PermissionsTest` (which checks the other
 * direction: every served route has a row in that file).
 */
class PreAuthorizeCoverageTest {
    private static final Pattern KEY = Pattern.compile("@permit\\.has\\('([^']+)'\\)");
    private static final Set<String> ALLOWED_PLAIN = Set.of("permitAll");

    private final Permissions permissions = new Permissions(new com.fasterxml.jackson.databind.ObjectMapper());

    @Test void every_endpoint_carries_a_preauthorize_whose_key_is_declared() throws Exception {
        List<String> unannotated = new ArrayList<>();
        List<String> unknown = new ArrayList<>();

        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) continue;
                if (AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class) == null) continue;
                var pre = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
                if (pre == null) { unannotated.add(controller.getSimpleName() + "#" + method.getName()); continue; }
                String expression = pre.value().trim();
                if (ALLOWED_PLAIN.contains(expression)) continue;
                Matcher m = KEY.matcher(expression);
                if (!m.matches()) { unknown.add(controller.getSimpleName() + "#" + method.getName() + " -> " + expression); continue; }
                if (!permissions.isDeclared(m.group(1))) unknown.add(controller.getSimpleName() + "#" + method.getName() + " -> " + m.group(1));
            }
        }

        assertThat(unannotated).as("annotate these with @PreAuthorize(\"@permit.has('<key from permissions.json>')\") or permitAll").isEmpty();
        assertThat(unknown).as("these expressions are neither permitAll nor a key declared in permissions.json").isEmpty();
    }

    @Test void the_controllers_are_actually_found() {
        assertThat(controllers()).as("the reflection sweep must see the whole API, not an empty list").hasSizeGreaterThan(5);
    }

    /** Every `@RestController` in the server, found the way ArchUnit finds classes (no Spring context needed). */
    private List<Class<?>> controllers() {
        JavaClasses classes = new ClassFileImporter().importPackages("quest.server");
        var out = new LinkedHashSet<Class<?>>();
        classes.stream().filter(c -> c.isAnnotatedWith(RestController.class)).forEach(c -> out.add(c.reflect()));
        return List.copyOf(out);
    }
}
