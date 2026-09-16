package quest.server.flags;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * §4: "A CI check … fails the build if a new route, screen or controller is added without a flag reference." On the
 * server that means every `@RestController` added from P2.1 onwards carries {@link FeatureFlag} — on the class when
 * the whole controller is one feature, or on every handler otherwise.
 *
 * <p>{@link #PRE_FLAG_CONTROLLERS} is the closed list of what existed before this package: the lesson pipeline, auth,
 * the parent API, media, schools and users all predate §4's flags and are not retro-fitted here (P3.0 and P4.0 flag
 * the routes they own as they touch them). {@link #INFRASTRUCTURE} is the other exemption and is meant to stay
 * small: a flag that could switch off the endpoint which switches flags, or the theme and platform-settings routes
 * every screen needs before it can render, has no way back on.
 *
 * <p>Adding a name to either list is the thing to argue about in review; adding a controller is not.
 */
class FeatureFlagCoverageTest {
    /** Pre-flag controllers, phase 1. Nothing may be added here. */
    private static final Set<String> PRE_FLAG_CONTROLLERS = Set.of(
            "AdminLessonController", "AdminReportsController", "AdminAuthController", "AuthController",
            "ChildController", "AdminPanelController", "HealthController", "LessonController", "MediaController",
            "SchoolController", "UserController");

    /**
     * The flag, theme and platform-settings routes themselves: infrastructure, not a feature (P2.1). P3.0 adds the
     * two controllers the dashboard shell itself is made of, for the same kind of reason:
     *
     * <ul>
     *   <li>{@code HomeController} — `/` sends every signed-in user to their Home (§6 screen 2), so a flag that
     *       could switch it off would leave that person with nowhere to land. What a Home <em>reports</em> about a
     *       flagged feature is gated by that feature's own flag, where the feature lives.</li>
     *   <li>{@code DashboardDataController} — classes are §2's unit of publishing and usage is how a school is run.
     *       Neither is a feature a tenant can be without, and gating them would hide the School page that shows
     *       which flags a school has.</li>
     *   <li>{@code DashboardController} (P3.4a) — not an API at all but the static Angular bundle at `/dashboard/`,
     *       the sibling of the pre-flag {@code AdminPanelController}. It serves index.html and a handful of hashed
     *       files; there is no feature behind it to switch off, and a flag that could 404 the page is the one flag
     *       nobody could ever turn back on, since the screen that edits flags is inside that bundle. Flags gate
     *       what the dashboard <em>shows</em>, route by route, in the API each screen calls.</li>
     *   <li>{@code TeacherController} (P4.0) — a teacher's profile is what decides which lessons she may publish at
     *       all (`TenantGuard.lessonCreator` reads it), the chooser options are the only way to submit a lesson the
     *       server will accept, and her lessons and her students are the job rather than an addition to it. A flag
     *       over them would leave a TEACHER signed in with nothing she can do. The two parts of her dashboard that
     *       <em>are</em> features carry real flags: {@code TeacherQuestionController} is `teacherQuestions` and
     *       {@code AnnouncementController} is `announcements`, both on the class so the parent half is gated too.</li>
     * </ul>
     */
    private static final Set<String> INFRASTRUCTURE = Set.of(
            "FlagController", "ThemeController", "PlatformSettingsController",
            "HomeController", "DashboardDataController", "DashboardController", "TeacherController");

    private static final JavaClasses SERVER = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("quest.server");

    @Test void every_controller_added_after_this_package_references_a_flag() {
        var missing = new ArrayList<String>();
        for (Class<?> controller : controllers()) {
            String name = controller.getSimpleName();
            if (PRE_FLAG_CONTROLLERS.contains(name) || INFRASTRUCTURE.contains(name)) continue;
            if (AnnotatedElementUtils.findMergedAnnotation(controller, FeatureFlag.class) != null) continue;
            for (Method method : controller.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) continue;
                if (AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class) == null) continue;
                if (AnnotatedElementUtils.findMergedAnnotation(method, FeatureFlag.class) == null)
                    missing.add(name + "#" + method.getName());
            }
        }
        assertThat(missing)
                .as("§4: annotate the controller or the handler with @FeatureFlag(\"<key from FlagKeys>\") so the route is a 404 while the feature is off")
                .isEmpty();
    }

    @Test void every_flag_named_by_an_annotation_is_a_real_flag() {
        var unknown = new ArrayList<String>();
        for (Class<?> controller : controllers()) {
            var onClass = AnnotatedElementUtils.findMergedAnnotation(controller, FeatureFlag.class);
            if (onClass != null && !FlagKeys.ALL.contains(onClass.value())) unknown.add(controller.getSimpleName() + " -> " + onClass.value());
            for (Method method : controller.getDeclaredMethods()) {
                var flag = AnnotatedElementUtils.findMergedAnnotation(method, FeatureFlag.class);
                if (flag != null && !FlagKeys.ALL.contains(flag.value()))
                    unknown.add(controller.getSimpleName() + "#" + method.getName() + " -> " + flag.value());
            }
        }
        assertThat(unknown).as("@FeatureFlag must name one of FlagKeys.ALL, which V5__flags_themes.sql seeds").isEmpty();
    }

    /** The allow-lists must name controllers that exist, or a rename would silently exempt a new one. */
    @Test void the_allow_lists_are_still_accurate() {
        var names = controllers().stream().map(Class::getSimpleName).collect(java.util.stream.Collectors.toSet());
        assertThat(names).as("the sweep must see the whole API").hasSizeGreaterThan(5).containsAll(INFRASTRUCTURE);
        assertThat(PRE_FLAG_CONTROLLERS).as("a controller on the phase-1 list was renamed or removed: update the list").allSatisfy(
                name -> assertThat(names).contains(name));
    }

    private List<Class<?>> controllers() {
        var out = new LinkedHashSet<Class<?>>();
        SERVER.stream().filter(c -> c.isAnnotatedWith(RestController.class)).forEach(c -> out.add(c.reflect()));
        return List.copyOf(out);
    }
}
