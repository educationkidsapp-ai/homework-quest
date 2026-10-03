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
            "/admin/auth/sign-in", "/admin/lessons", "/admin/lessons/{id}", "/admin/lessons/{id}/status", "/admin/lessons/{id}/files", "/admin/lessons/{id}/analyze", "/admin/lessons/{id}/skills",
            "/admin/lessons/{id}/files/{fileId}/markdown", "/admin/lessons/{id}/files/{fileId}/retry-conversion",
            "/admin/stops/{stopId}", "/admin/stops/{stopId}/regenerate", "/admin/stops/{stopId}/from-text", "/admin/plays/{playId}/regenerate", "/admin/lessons/{id}/parent-panel",
            "/admin/lessons/{id}/plays/{level}/generate",
            "/admin/lessons/{id}/publish", "/admin/lessons/{id}/unpublish", "/admin/cache", "/admin/usage", "/admin/calendar");
    /** `quest.api.dashboard.DashboardApi` (P1.3, P2.1): the Angular client is generated from exactly these. */
    static final List<String> DASHBOARD_API = List.of(
            "/auth/sign-in", "/auth/refresh", "/auth/sign-out", "/auth/forgot-password", "/auth/reset-password", "/auth/change-password",
            "/me", "/me/permissions", "/parent/me",
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
            "/teacher/lessons", "/teacher/lessons/{id}", "/teacher/lessons/{id}/status", "/teacher/lessons/{id}/copy",
            "/teacher/lessons/{id}/publish", "/teacher/lessons/{id}/unpublish",
            "/teacher/lessons/{id}/files", "/teacher/lessons/{id}/images", "/teacher/lessons/{id}/analyze",
            "/teacher/lessons/{id}/files/{fileId}/markdown", "/teacher/lessons/{id}/files/{fileId}/retry-conversion",
            "/teacher/lessons/{id}/retry", "/teacher/lessons/{id}/steps/{step}/retry",
            "/teacher/lessons/{id}/skills", "/teacher/lessons/{id}/generate-from-text",
            "/teacher/lessons/{id}/parent-panel", "/teacher/lessons/{id}/plays", "/teacher/lessons/{id}/plays/{level}/generate",
            "/teacher/stops/{stopId}", "/teacher/stops/{stopId}/regenerate", "/teacher/stops/{stopId}/from-text",
            "/teacher/plays/{playId}/stops", "/teacher/plays/{playId}/order", "/teacher/plays/{playId}/regenerate",
            "/teacher/questions", "/teacher/questions/{id}", "/teacher/questions/{id}/send",
            "/teacher/questions/{id}/results",
            "/teacher/announcements", "/teacher/announcements/{id}",
            "/children/{id}/announcements",
            "/children/{id}/teacher-questions/{questionId}", "/children/{id}/teacher-questions/{questionId}/answers");

    /**
     * N4.1: results, marking, release, the gradebook and the child page (`quest.api.dashboard.Grading.kt`,
     * `docs/teacher-flow.md` step 9). All of it is behind the `gradebook` flag at run time, and the exports are
     * behind it too; they are still in the document, because `server/openapi.json` describes the API the server can
     * serve, not what one school has switched on.
     */
    static final List<String> GRADING_API = List.of(
            "/teacher/lessons/{id}/results", "/teacher/lessons/{id}/results.csv", "/teacher/lessons/{id}/results.xlsx",
            "/teacher/marks", "/teacher/lessons/{id}/release",
            "/teacher/classes/{classId}/gradebook", "/teacher/classes/{classId}/gradebook.csv",
            "/teacher/classes/{classId}/gradebook.xlsx",
            "/teacher/children/{childId}");

    /**
     * N4.3: exams (`quest.api.dashboard.Exams.kt`, `docs/teacher-flow.md` step 10). All of it is behind the `exams`
     * flag at run time, exports and printable sheet included; it is still in the document, because
     * `server/openapi.json` describes the API the server can serve rather than what one school has switched on.
     */
    static final List<String> EXAMS_API = List.of(
            "/teacher/classes/{classId}/exams",
            "/teacher/exams/{id}", "/teacher/exams/{id}/publish", "/teacher/exams/{id}/release",
            "/teacher/exams/{id}/reopen/{childId}",
            "/teacher/exams/{id}/results", "/teacher/exams/{id}/results.csv", "/teacher/exams/{id}/results.xlsx",
            "/teacher/exams/{id}/results/{childId}.pdf");

    /**
     * N1.1: sections, teaching assignments and class rosters (`quest.api.dashboard.Classes.kt`,
     * `docs/teacher-flow.md` §1–§2). `/teacher/classes/{classId}/children**` is behind `teacher.rosterEdit` at run
     * time and is still in the document, for the same reason the teacher's feature routes are: `server/openapi.json`
     * describes the API the server can serve, not what one school has switched on.
     */
    static final List<String> CLASSES_API = List.of(
            "/admin/classes", "/admin/classes/{id}", "/admin/classes/{id}/join-code",
            "/admin/classes/{id}/join-card.pdf", "/admin/classes/{id}/assignments",
            "/admin/classes/{id}/children", "/admin/classes/{id}/children/unassigned",
            "/admin/classes/{id}/children/import", "/admin/children", "/admin/children/{id}",
            // MA1: the Children & parents page — a child created with her parent's Firebase account, the paged
            // directory beside the roster list, and a new password for that parent.
            "/admin/children/search", "/admin/children/{id}/parent/reset-password",
            "/admin/classes/{id}/roster/attach", "/admin/classes/{id}/roster/{childId}",
            "/admin/teachers", "/admin/teachers/{id}", "/admin/teachers/{id}/reset-password",
            "/admin/teachers/{id}/assignments",
            // MA1: the school's non-teaching staff. No account behind a row, so no reset-password twin.
            "/admin/workers", "/admin/workers/{id}",
            "/teacher/classes/{classId}/children", "/teacher/classes/{classId}/children/unassigned",
            "/teacher/classes/{classId}/children/{childId}",
            "/teacher/classes/{classId}/roster/attach", "/teacher/classes/{classId}/roster/{childId}");

    /**
     * C1: chat (`quest.api.dto.Chat.kt`, `docs/runbook.md` "Chat and notifications"). Behind the `chat` flag at run
     * time and still in the document, for the reason every flagged area is. The socket itself, `/ws/chat`, is not an
     * OpenAPI path: its frames are `ChatFrame.schema.json`.
     *
     * <p>R4 added the parent's `/children/{id}/coordinators` here — it is a chat route, behind the same flag and on the
     * same rows. The coordinator's own half is in {@link #COORDINATOR_API}, with the rest of her namespace.
     */
    static final List<String> CHAT_API = List.of(
            "/children/{id}/chat/threads", "/children/{id}/chat/threads/{teacherId}/messages", "/children/{id}/chat/threads/{teacherId}/read",
            "/children/{id}/coordinators",
            "/teacher/chat/threads", "/teacher/chat/threads/{childId}/messages", "/teacher/chat/threads/{childId}/read",
            "/teacher/managers", "/teacher/coordinators", "/teacher/chat/staff-threads", "/teacher/chat/staff-threads/{id}/messages",
            "/teacher/chat/staff-threads/{id}/read",
            "/admin/chat/threads", "/admin/chat/threads/{threadId}/messages", "/admin/chat/threads/{threadId}/read",
            "/children/{id}/managers");

    /**
     * E2: the dashboard bell (`quest.api.dto.Notifications.kt`, D26). Not behind a flag and not behind a role
     * beyond the three dashboard ones — every signed-in dashboard user has a bell, and the rows are her own.
     */
    static final List<String> NOTIFICATIONS_API = List.of(
            "/me/notifications", "/me/notifications/unread-count", "/me/notifications/{id}/read", "/me/notifications/read-all");

    /** B4: the parent's phones for push (`quest.api.dto.Push.kt`, `ContentApi.registerDevice`/`unregisterDevice`). */
    static final List<String> DEVICES_API = List.of("/me/devices", "/me/devices/{token}");

    /**
     * R2 and R3: the coordinator's read-only area and the Admin routes that create one
     * (`quest.api.dashboard.Coordinator.kt`, `docs/plan.md` phase R). Every path here is a GET except the two that
     * mint an account and set a scope, because DR2 makes the role read-only on teaching data.
     *
     * <p>R4 (DR3, DR4) adds the five writes: her chat threads with parents and with the manager of her department,
     * the `open` / `resolved` patch that answers a complaint, and the announcement to the parents of her classes. The
     * chat paths carry the `chat` flag and the announcement paths carry `announcements`, the very keys the parent's
     * and the teacher's halves of those two features carry.
     *
     * <p>R2's own routes are not behind a feature flag — see `FeatureFlagCoverageTest.INFRASTRUCTURE`. R3's are: the
     * three `results` paths and the child page carry `gradebook` and the two exam paths carry `exams`, the same keys
     * the teacher's `GradingController` and `ExamController` carry, so a school with the feature off answers 404 to
     * both roles. The register carries none, as `/teacher/classes/{classId}/attendance` carries none.
     */
    static final List<String> COORDINATOR_API = List.of(
            "/coordinator/me", "/coordinator/teachers", "/coordinator/classes", "/coordinator/calendar",
            "/coordinator/lessons", "/coordinator/lessons/{id}", "/coordinator/lessons/{id}/status",
            "/coordinator/classes/{id}/attendance", "/coordinator/classes/{id}/results",
            "/coordinator/lessons/{id}/results", "/coordinator/children/{id}",
            "/coordinator/classes/{id}/exams", "/coordinator/exams/{id}/results",
            "/coordinator/chat/threads", "/coordinator/chat/threads/{id}/messages", "/coordinator/chat/threads/{id}/read",
            "/coordinator/chat/threads/{id}/status", "/coordinator/complaints", "/coordinator/managers",
            "/coordinator/announcements", "/coordinator/broadcasts",
            "/admin/coordinators", "/admin/coordinators/{id}",
            "/admin/coordinators/{id}/reset-password", "/admin/coordinators/{id}/scopes");

    /**
     * RM1: the department manager's read-only area and the Admin routes that create one
     * (`quest.api.dashboard.Management.kt`, DR5). {@link #COORDINATOR_API}'s mirror one axis over — wide in subject,
     * narrow in track — and every path is a GET except the two that mint an account and set a department.
     *
     * <p>`/management/**` carries no flag except where the feature does: the three `results` paths and the child page
     * carry `gradebook`, the two exam paths carry `exams`, and the register carries none, exactly as the coordinator's
     * and the teacher's halves of those features do. The area itself is in `FeatureFlagCoverageTest.INFRASTRUCTURE`.
     */
    static final List<String> MANAGEMENT_API = List.of(
            "/management/me", "/management/coordinators", "/management/teachers", "/management/classes",
            "/management/calendar", "/management/stats", "/management/usage",
            "/management/lessons", "/management/lessons/{id}", "/management/lessons/{id}/status",
            "/management/classes/{id}/attendance", "/management/classes/{id}/results",
            "/management/lessons/{id}/results", "/management/children/{id}",
            "/management/classes/{id}/exams", "/management/exams/{id}/results",
            "/admin/managers", "/admin/managers/{id}", "/admin/managers/{id}/reset-password",
            "/admin/managers/{id}/scopes",
            // RM5 (DR7): the staff register — the area's one write — and the department's people directory.
            "/management/staff-attendance", "/management/staff-attendance/summary", "/management/staff-attendance/{userId}",
            "/management/people/children", "/management/people/teachers", "/management/people/coordinators");

    /**
     * RM2 (DR6): the broadcast feature — the two composers, the dashboard feed and the app feed — and the manager's
     * half of the chat (`quest.api.dto.Broadcasts.kt`, `docs/runbook.md` "Broadcasts"). The broadcast paths carry the
     * `announcements` flag and the chat paths `chat`, the very keys the features they supersede and join already
     * carry; both are in the document, because it describes the API the server can serve rather than what one school
     * has switched on.
     *
     * <p>MG1 adds the three weekly-plan archives (owner's item 4) — the manager's, every other dashboard role's and
     * the parent's — which carry the same two keys as the feeds they page backwards through.
     */
    static final List<String> BROADCASTS_API = List.of(
            "/management/broadcasts", "/coordinator/broadcasts",
            "/me/broadcasts", "/me/broadcasts/{id}/read",
            "/children/{id}/broadcasts", "/children/{id}/broadcasts/{broadcastId}/read",
            "/management/chat/threads", "/management/chat/threads/{id}/messages",
            "/management/chat/threads/{id}/read", "/management/admins",
            "/management/weekly-plans", "/me/weekly-plans", "/children/{id}/weekly-plans",
            // S1: the manager's Complaints inbox and its status write, and the school logo's two writes.
            "/management/complaints", "/management/chat/threads/{id}/status", "/admin/schools/{id}/logo");

    /** Public and unauthenticated (§3, §4, §6 screen 1, §A): read before anyone has a token. */
    static final List<String> PUBLIC_API = List.of("/schools/{id}/flags", "/schools/{id}/theme", "/platform-settings",
            "/schools/logo", "/classes/lookup", "/schools/{id}/logo");

    @Test void every_shared_api_route_is_served() throws Exception {
        var doc = json(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn());
        Set<String> paths = new java.util.HashSet<>(); doc.get("paths").fieldNames().forEachRemaining(paths::add);
        assertThat(paths).containsAll(CONTENT_API);
        assertThat(paths).containsAll(ADMIN_API);
        assertThat(paths).containsAll(DASHBOARD_API);
        assertThat(paths).containsAll(DASHBOARD_DATA_API);
        assertThat(paths).containsAll(TEACHER_API);
        assertThat(paths).containsAll(GRADING_API);
        assertThat(paths).containsAll(EXAMS_API);
        assertThat(paths).containsAll(CLASSES_API);
        assertThat(paths).containsAll(CHAT_API);
        assertThat(paths).containsAll(NOTIFICATIONS_API);
        assertThat(paths).containsAll(DEVICES_API);
        assertThat(paths).containsAll(COORDINATOR_API);
        assertThat(paths).containsAll(MANAGEMENT_API);
        assertThat(paths).containsAll(BROADCASTS_API);
        assertThat(paths).containsAll(PUBLIC_API);
        // MH1: the upload and the download of a broadcast attachment — the app and the dashboard both call them.
        assertThat(paths).contains("/media/pages/{id}", "/media/child/{id}", "/media/attachments", "/media/attachments/{id}");
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

    /**
     * RM1 addendum: springdoc keys `components/schemas` by <em>simple name</em>, so two records called
     * `CreateAnnouncementRequest` — the teacher's (`classId`) and the coordinator's (`classIds`) — collapsed into one
     * schema and `POST /coordinator/announcements` was documented with the teacher's body, which generated a client
     * that sent the wrong field. `CoordinatorDto.CreateAnnouncementRequest` now carries
     * `@Schema(name = "CoordinatorCreateAnnouncementRequest")`; this fails the build if that is ever dropped, or if a
     * third shape joins the collision.
     */
    @Test void the_two_announcement_bodies_are_two_schemas() throws Exception {
        var doc = json(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn());
        var schemas = doc.get("components").get("schemas");
        var teacher = schemas.get("CreateAnnouncementRequest");
        var coordinator = schemas.get("CoordinatorCreateAnnouncementRequest");
        assertThat(teacher).as("the teacher's announcement body").isNotNull();
        assertThat(coordinator).as("the coordinator's announcement body must have a schema of its own").isNotNull();
        assertThat(teacher.get("properties").has("classId")).as("the teacher posts to one class").isTrue();
        assertThat(coordinator.get("properties").has("classIds")).as("the coordinator posts to the classes in her scope").isTrue();
        assertThat(coordinator.get("properties").has("classId")).isFalse();

        String ref = doc.get("paths").get("/coordinator/announcements").get("post").get("requestBody")
                .get("content").get(org.springframework.http.MediaType.APPLICATION_JSON_VALUE).get("schema").get("$ref").asText();
        assertThat(ref).isEqualTo("#/components/schemas/CoordinatorCreateAnnouncementRequest");
        assertThat(doc.get("paths").get("/teacher/announcements").get("post").get("requestBody")
                .get("content").get(org.springframework.http.MediaType.APPLICATION_JSON_VALUE).get("schema").get("$ref").asText())
                .isEqualTo("#/components/schemas/CreateAnnouncementRequest");
    }

    /**
     * MH1, the same trap one package over: `BroadcastDto.Attachment` (what is on a broadcast) and
     * `MediaController.Attachment` (what an upload answers) are two shapes with one simple name, and springdoc keys
     * `components/schemas` by simple name — so without the two `@Schema(name = …)` the generated client had one
     * `Attachment` and `BroadcastView.attachment` pointed at the upload's body.
     */
    @Test void the_two_attachment_shapes_are_two_schemas() throws Exception {
        var doc = json(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn());
        var schemas = doc.get("components").get("schemas");
        assertThat(schemas.has("Attachment")).as("neither shape may claim the bare name").isFalse();
        assertThat(schemas.get("BroadcastAttachment").get("properties").has("url")).isTrue();
        assertThat(schemas.get("AttachmentRef").get("properties").has("sizeBytes")).isTrue();
        assertThat(schemas.get("BroadcastAttachment").get("properties").has("sizeBytes")).isFalse();
        assertThat(doc.get("paths").get("/media/attachments").get("post").get("responses").get("201")
                .get("content").get(org.springframework.http.MediaType.APPLICATION_JSON_VALUE).get("schema").get("$ref").asText())
                .isEqualTo("#/components/schemas/AttachmentRef");
        assertThat(schemas.get("BroadcastView").get("properties").get("attachment").get("$ref").asText())
                .isEqualTo("#/components/schemas/BroadcastAttachment");
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
