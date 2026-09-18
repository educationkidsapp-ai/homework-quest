package quest.server.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import quest.api.dto.Stop;
import quest.server.ApiTestSupport;

/**
 * The server's OpenAPI document must expose exactly the routes the shared `ContentApi` / `AdminApi` call.
 * A renamed endpoint fails here before it fails in the app.
 */
class OpenApiContractTest extends ApiTestSupport {
    static final List<String> CONTENT_API = List.of(
            "/children", "/children/{id}", "/children/{id}/map", "/children/{id}/attempts", "/children/{id}/stops/{stopId}/media", "/children/{id}/progress", "/lessons/{id}");
    static final List<String> ADMIN_API = List.of(
            "/admin/auth/sign-in", "/admin/lessons", "/admin/lessons/{id}", "/admin/lessons/{id}/files", "/admin/lessons/{id}/analyze", "/admin/lessons/{id}/skills",
            "/admin/stops/{stopId}", "/admin/stops/{stopId}/regenerate", "/admin/plays/{playId}/regenerate", "/admin/lessons/{id}/parent-panel",
            "/admin/lessons/{id}/publish", "/admin/lessons/{id}/unpublish", "/admin/cache", "/admin/usage", "/admin/calendar");
    /** `quest.api.dashboard.DashboardApi` (P1.3, P2.1): the Angular client is generated from exactly these. */
    static final List<String> DASHBOARD_API = List.of(
            "/auth/sign-in", "/auth/refresh", "/auth/sign-out", "/auth/forgot-password", "/auth/reset-password", "/auth/change-password",
            "/me", "/me/permissions",
            "/admin/schools", "/admin/schools/{id}", "/admin/schools/{id}/invites", "/admin/schools/{id}/users",
            "/admin/users", "/admin/users/{id}", "/admin/users/{id}/reset-password", "/admin/users/{id}/impersonate",
            "/invites/{token}", "/invites/{token}/accept", "/schools/by-code/{code}",
            "/admin/flags", "/admin/flags/audit", "/admin/flags/{key}/all", "/admin/schools/{id}/flags/{key}",
            "/admin/schools/{id}/theme", "/admin/platform-settings");
    /** P3.0: what the Angular Homes, School page, usage screens and New school wizard call (§6 screens 2, 4–6, 10, 19–20). */
    static final List<String> DASHBOARD_DATA_API = List.of(
            "/me/home",
            "/admin/schools/wizard",
            "/admin/schools/{id}/classes", "/admin/schools/{id}/classes/{classId}",
            "/admin/schools/{id}/usage", "/admin/schools/{id}/billing",
            "/admin/usage/platform", "/school/usage", "/school/teachers");
    /**
     * P4.0: §5's teacher profile and §6 screens 12-16, plus the two app routes the teacher's features add.
     * `/teacher/questions/**` and `/teacher/announcements/**` (and their `/children/**` halves) are behind the
     * `teacherQuestions` and `announcements` flags at run time; they are still in `server/openapi.json`, because the
     * document describes the API the server can serve, not what one school has switched on.
     */
    static final List<String> TEACHER_API = List.of(
            "/teacher/profile", "/teacher/options",
            "/admin/users/{id}/teacher-profile",
            "/teacher/classes/{classId}/calendar", "/teacher/classes/{classId}/students",
            "/teacher/students/{childId}/timeline",
            "/teacher/week", "/teacher/classes",
            "/teacher/lessons", "/teacher/lessons/{id}", "/teacher/lessons/{id}/copy",
            "/teacher/lessons/{id}/publish", "/teacher/lessons/{id}/unpublish",
            "/teacher/lessons/{id}/files", "/teacher/lessons/{id}/images", "/teacher/lessons/{id}/analyze",
            "/teacher/lessons/{id}/retry", "/teacher/lessons/{id}/steps/{step}/retry",
            "/teacher/lessons/{id}/skills", "/teacher/lessons/{id}/generate-from-text",
            "/teacher/lessons/{id}/parent-panel",
            "/teacher/stops/{stopId}", "/teacher/stops/{stopId}/regenerate",
            "/teacher/plays/{playId}/stops", "/teacher/plays/{playId}/order", "/teacher/plays/{playId}/regenerate",
            "/teacher/questions", "/teacher/questions/{id}", "/teacher/questions/{id}/send",
            "/teacher/questions/{id}/results",
            "/teacher/announcements", "/teacher/announcements/{id}",
            "/children/{id}/announcements",
            "/children/{id}/teacher-questions/{questionId}", "/children/{id}/teacher-questions/{questionId}/answers");

    /**
     * N1.1: sections, teaching assignments and class rosters (`quest.api.dashboard.Classes.kt`,
     * `docs/teacher-flow.md` §1–§2). `/teacher/classes/{classId}/children**` is behind `teacher.rosterEdit` at run
     * time and is still in the document, for the same reason the teacher's feature routes are: `server/openapi.json`
     * describes the API the server can serve, not what one school has switched on.
     */
    static final List<String> CLASSES_API = List.of(
            "/admin/classes", "/admin/classes/{id}", "/admin/classes/{id}/join-code",
            "/admin/classes/{id}/join-card.pdf", "/admin/classes/{id}/assignments",
            "/admin/classes/{id}/children", "/admin/classes/{id}/children/import", "/admin/children/{id}",
            "/admin/teachers", "/admin/teachers/{id}", "/admin/teachers/{id}/reset-password",
            "/admin/teachers/{id}/assignments",
            "/teacher/classes/{classId}/children", "/teacher/classes/{classId}/children/{childId}");

    /** Public and unauthenticated (§3, §4, §6 screen 1, §A): read before anyone has a token. */
    static final List<String> PUBLIC_API = List.of("/schools/{id}/flags", "/schools/{id}/theme", "/platform-settings",
            "/schools/logo", "/classes/lookup");

    @Test void every_shared_api_route_is_served() throws Exception {
        var doc = json(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn());
        Set<String> paths = new java.util.HashSet<>(); doc.get("paths").fieldNames().forEachRemaining(paths::add);
        assertThat(paths).containsAll(CONTENT_API);
        assertThat(paths).containsAll(ADMIN_API);
        assertThat(paths).containsAll(DASHBOARD_API);
        assertThat(paths).containsAll(DASHBOARD_DATA_API);
        assertThat(paths).containsAll(TEACHER_API);
        assertThat(paths).containsAll(CLASSES_API);
        assertThat(paths).containsAll(PUBLIC_API);
        assertThat(paths).contains("/media/pages/{id}", "/media/child/{id}");
    }

    /**
     * P3.0b: `Stop` is the one shape springdoc builds by reflecting over the bean rather than from the codec, so it is
     * checked against the sealed hierarchy itself. A caller can receive exactly the concrete subtypes — an abstract
     * rung (`Stop.SingleAnswer`) is a rung, never a body — and no schema in the hierarchy may demand a field the codec
     * does not write, because a computed Kotlin property (`category`, `correctId`, `optionIds`) is a bean getter and
     * not a serialised element.
     */
    @Test void the_stop_union_is_the_concrete_subtypes_and_requires_only_serialised_fields() throws Exception {
        var schemas = json(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn()).get("components").get("schemas");
        List<Class<?>> hierarchy = new ArrayList<>(); collect(Stop.class, hierarchy);
        List<String> concrete = hierarchy.stream().filter(this::isConcrete).map(Class::getSimpleName).sorted().toList();

        List<String> variants = new ArrayList<>();
        schemas.get("Play").get("properties").get("stops").get("items").get("oneOf")
                .forEach(v -> variants.add(v.get("$ref").asText().substring(v.get("$ref").asText().lastIndexOf('/') + 1)));
        assertThat(variants.stream().sorted().toList()).as("`Play.stops` is every concrete Stop and nothing else").isEqualTo(concrete);

        hierarchy.add(Stop.class);
        for (Class<?> type : hierarchy) {
            var schema = schemas.get(type.getSimpleName());
            if (schema == null || schema.get("required") == null) continue;
            List<String> required = new ArrayList<>(); schema.get("required").forEach(name -> required.add(name.asText()));
            assertThat(serialisedFields(type)).as(type.getSimpleName() + " requires a field the codec never writes").containsAll(required);
        }
    }

    private void collect(Class<?> root, List<Class<?>> out) {
        if (root.getPermittedSubclasses() == null) return;
        for (Class<?> sub : root.getPermittedSubclasses()) { out.add(sub); collect(sub, out); }
    }

    private boolean isConcrete(Class<?> type) { return !type.isInterface() && !Modifier.isAbstract(type.getModifiers()); }

    /** What the codec writes: a concrete class's own elements, a rung's shared ones — plus the `type` discriminator. */
    private Set<String> serialisedFields(Class<?> type) {
        Set<String> names = new HashSet<>(List.of("type"));
        if (isConcrete(type)) { names.addAll(elements(type)); return names; }
        List<Class<?>> subtypes = new ArrayList<>(); collect(type, subtypes);
        Set<String> shared = null;
        for (Class<?> sub : subtypes) { if (!isConcrete(sub)) continue; var own = elements(sub); if (shared == null) shared = own; else shared.retainAll(own); }
        names.addAll(shared == null ? Set.of() : shared);
        return names;
    }

    private Set<String> elements(Class<?> type) {
        var descriptor = kotlinx.serialization.SerializersKt.serializer(type).getDescriptor();
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < descriptor.getElementsCount(); i++) out.add(descriptor.getElementName(i));
        return out;
    }
}
