package quest.server.broadcasts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.server.ApiTestSupport;
import quest.server.ClassFixtures;
import quest.server.analysis.LlmClient;
import quest.server.analysis.SampleLlmClient;
import quest.server.auth.AdminJwtService;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.UserRepository;
import quest.server.flags.FlagKeys;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.StaffScopeRepository;
import quest.server.tenancy.TeachingAssignmentRepository;

/**
 * Feature 22: a leader speaks, the assistant writes, <strong>and nothing is sent until she presses Send</strong>.
 *
 * <p>The fixture is the smallest one in which "her real cells" means something: Nada manages the British department
 * of one school and it teaches grades 1 and 2, the same school teaches American grade 1 (hers to see, not hers to
 * address), and <em>another</em> school teaches British grade 6. Every audience the model could suggest is therefore
 * one of four things — a grade nobody teaches, the other academy, another school's cell, or one of her own — and only
 * the last survives.
 *
 * <p>The {@link LlmClient} is stubbed rather than taken from the context for {@code StopFromTextTest}'s reason: the
 * happy path delegates to the real {@link SampleLlmClient}, which is deterministic, and the audience, `rephrase` and
 * `model_failed` paths each need a model that answers one particular way.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VoiceDraftApiTest extends ApiTestSupport {
    private static final String SCHOOL = "vd-school", OTHER = "vd-other";
    private static final String NADA = "vd-nada", HIND = "vd-hind", FAR_AWAY = "vd-faraway";
    private static final String TEACHER = "vd-teacher", COORDINATOR = "vd-coord", SUPERVISOR = "vd-floor";
    /** A child and the parent who registered her: the two names that may never reach the model. */
    private static final String CHILD_NAME = "Lamya Alqahtani", PARENT_UID = "vd-parent";
    /** The day every test reads as "now", so an expiry the transcript names is one fixed instant. */
    private static final LocalDate TODAY = LocalDate.of(2027, 2, 1);
    private static final String EXPIRES_ON = "2027-03-05";

    private static final String TRANSCRIPT = """
            رحلة الصف الأول إلى المتحف
            الرحلة يوم الخميس، والمصروف عشرين ريال، ونرجع الساعة واحدة. آخر يوم للتسجيل 2027-03-05""";

    @MockitoBean LlmClient llm;
    private final SampleLlmClient sample = new SampleLlmClient();

    @Autowired SchoolRepository schools;
    @Autowired ClassRepository classes;
    @Autowired TeachingAssignmentRepository assignments;
    @Autowired StaffScopeRepository staffScopes;
    @Autowired UserRepository users;
    @Autowired AnnouncementDraftRepository drafts;
    @Autowired BroadcastRepository broadcastRows;
    @Autowired BroadcastReadRepository readRows;
    @Autowired quest.server.children.ChildRepository childRows;
    @Autowired quest.server.notifications.NotificationRepository notificationRows;
    @Autowired quest.server.flags.SchoolFlagRepository schoolFlags;
    @Autowired quest.server.flags.FeatureFlags featureFlags;
    @Autowired quest.server.broadcasts.VoiceDraftRetention retention;
    @Autowired quest.server.platform.SchoolCalendar calendar;
    @Autowired AdminJwtService jwt;

    private String childId;

    @BeforeAll void loadTwoSchools() throws Exception {
        school(SCHOOL, "Voice Primary", "VDRFT1"); school(OTHER, "Distant Primary", "VDRFT2");
        staff(NADA, SCHOOL, "Nada", "MANAGERIAL"); staff(HIND, SCHOOL, "Hind", "MANAGERIAL");
        staff(TEACHER, SCHOOL, "Ms Reem", "TEACHER"); staff(COORDINATOR, SCHOOL, "Ms Ohoud", "COORDINATOR");
        staff(SUPERVISOR, SCHOOL, "Mr Majed", "FLOOR_SUPERVISOR");
        staff(FAR_AWAY, OTHER, "Mrs Salma", "MANAGERIAL");
        // A department row is `subject NULL, curriculum = <track>`; Nada and Hind both manage British, so one of them
        // sending the other's draft is a 404 about the draft and not about the department.
        department("vd-scope-nada", SCHOOL, NADA); department("vd-scope-hind", SCHOOL, HIND);
        department("vd-scope-salma", OTHER, FAR_AWAY);
        scopeRow("vd-scope-ohoud", SCHOOL, COORDINATOR, "math", "british");
        // Her two cells, in the order `ManagerScope.reach` returns them: British grade 1, then British grade 2.
        ClassFixtures.section(classes, assignments, SCHOOL + ":british:1:math", SCHOOL, "british", 1, "math", TEACHER);
        ClassFixtures.section(classes, assignments, SCHOOL + ":british:2:art", SCHOOL, "british", 2, "art", TEACHER);
        // The other academy of her own school: hers to read, never hers to address.
        ClassFixtures.section(classes, assignments, SCHOOL + ":american:1:math", SCHOOL, "american", 1, "math", TEACHER);
        // British grade 6 exists — in the other school. It is the cell a crossed-school check would hand her.
        ClassFixtures.section(classes, assignments, OTHER + ":british:6:math", OTHER, "british", 6, "math", null);
        for (String school : List.of(SCHOOL, OTHER))
            for (String key : List.of(FlagKeys.ANNOUNCEMENTS, FlagKeys.VOICE_ANNOUNCEMENT)) flag(school, key, true);
        childId = child(CHILD_NAME, SCHOOL + ":british:1:math");
    }

    @AfterAll void takeItBackOut() {
        drafts.deleteAll(drafts.findAll().stream().filter(d -> d.getSchoolId().startsWith("vd-")).toList());
        readRows.deleteAll(readRows.findAll().stream().filter(r -> r.getSchoolId().startsWith("vd-")).toList());
        broadcastRows.deleteAll(broadcastRows.findAll().stream().filter(b -> b.getSchoolId().startsWith("vd-")).toList());
        notificationRows.deleteAll(notificationRows.findAll().stream().filter(n -> n.getSchoolId().startsWith("vd-")).toList());
        childRows.deleteAll(childRows.findAll().stream().filter(c -> c.getSchoolId().startsWith("vd-")).toList());
        assignments.deleteAll(assignments.findAll().stream().filter(a -> a.getSchoolId().startsWith("vd-")).toList());
        staffScopes.deleteAll(staffScopes.findAll().stream().filter(r -> r.getSchoolId().startsWith("vd-")).toList());
        classes.deleteAll(classes.findAll().stream().filter(k -> k.getSchoolId().startsWith("vd-")).toList());
        var staff = new ArrayList<UserEntity>(users.findBySchoolId(SCHOOL));
        staff.addAll(users.findBySchoolId(OTHER));
        users.deleteAll(staff);
    }

    @BeforeEach void pinTheDayAndTheModel() {
        clock.pinTo(TODAY);
        when(llm.complete(any(), any(), any()))
                .thenAnswer(i -> sample.complete(i.getArgument(0), i.getArgument(1), i.getArgument(2)));
    }

    @AfterEach void release() { clock.release(); }

    // ---------------------------------------------------------------- what she gets back

    @Test void the_transcript_becomes_a_bilingual_announcement_she_has_not_sent() throws Exception {
        long broadcastsBefore = broadcastRows.count();

        var draft = draft(NADA, TRANSCRIPT, status().isCreated());
        assertThat(draft.get("id").asText()).isNotBlank();
        assertThat(draft.get("title").asText()).isEqualTo("رحلة الصف الأول إلى المتحف");
        assertThat(draft.get("bodyAr").asText()).contains("عشرين ريال");
        assertThat(draft.get("bodyEn").asText()).isNotBlank();
        assertThat(draft.get("transcript").asText()).as("the preview shows what she actually said, underneath").isEqualTo(TRANSCRIPT);
        // Her first cell, which is a real one: British grade 1.
        assertThat(draft.get("audience").get("curriculum").asText()).isEqualTo("british");
        assertThat(draft.get("audience").get("grade").asInt()).isOne();
        // The day she named, read as the end of that day in the school's own zone — never the server's.
        assertThat(draft.get("expiresAt").asLong()).isEqualTo(LocalDate.parse(EXPIRES_ON)
                .atTime(LocalTime.of(23, 59, 59)).atZone(calendar.of(SCHOOL).zone()).toInstant().toEpochMilli());

        assertThat(broadcastRows.count()).as("preparing an announcement posts nothing").isEqualTo(broadcastsBefore);
        var row = row(draft);
        assertThat(row.getBroadcastId()).as("and the row says so").isNull();
        assertThat(row.getAuthorId()).isEqualTo(NADA);
        assertThat(row.getSchoolId()).isEqualTo(SCHOOL);
        assertThat(drafts.byAuthor(SCHOOL, NADA, PageRequest.of(0, 20))).extracting(Entities.AnnouncementDraftEntity::getId)
                .as("hers to re-open, newest first").contains(row.getId());
    }

    /**
     * The spec's first test: "Audience suggestion only returns real grades and academies of this school." Each case is
     * a model that answers with one audience; only the last of the four is one of Nada's own cells, and a dropped
     * suggestion is `null` — her whole department, which is what an empty `sectionIds` already means to a broadcast.
     */
    @Test void the_suggested_audience_survives_only_when_it_is_one_of_her_own_cells() throws Exception {
        assertThat(audienceOf(answer("british", 9))).as("a grade the school does not teach").isNull();
        assertThat(audienceOf(answer("american", 1))).as("the other academy of her own school").isNull();
        assertThat(audienceOf(answer("british", 6))).as("a cell only the other school has").isNull();
        assertThat(audienceOf(answer("french", 1))).as("an academy nobody has").isNull();

        var kept = audienceOf(answer("british", 2));
        assertThat(kept).isNotNull();
        assertThat(kept.get("curriculum").asText()).isEqualTo("british");
        assertThat(kept.get("grade").asInt()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- Send is the only thing that sends

    @Test void only_an_explicit_send_posts_the_announcement_and_one_draft_sends_once() throws Exception {
        var draft = draft(NADA, TRANSCRIPT, status().isCreated());
        String draftId = draft.get("id").asText();
        long before = broadcastRows.count();

        var posted = json(mvc.perform(as(post("/management/broadcasts").contentType(MediaType.APPLICATION_JSON)
                .content(send(draftId, "Museum trip")), token(NADA, SCHOOL, "MANAGERIAL"))).andExpect(status().isCreated()).andReturn());
        assertThat(broadcastRows.count()).isEqualTo(before + 1);
        assertThat(row(draft).getBroadcastId()).as("the transcript follows exactly what went out")
                .isEqualTo(posted.get("id").asText());

        // Pressing Send twice posts once.
        var again = json(mvc.perform(as(post("/management/broadcasts").contentType(MediaType.APPLICATION_JSON)
                .content(send(draftId, "Museum trip")), token(NADA, SCHOOL, "MANAGERIAL"))).andExpect(status().isConflict()).andReturn());
        assertThat(again.get("code").asText()).isEqualTo("already_sent");
        assertThat(broadcastRows.count()).isEqualTo(before + 1);
    }

    @Test void a_draft_of_another_author_or_another_school_is_not_found() throws Exception {
        String hers = draft(NADA, TRANSCRIPT, status().isCreated()).get("id").asText();
        String elsewhere = draft(FAR_AWAY, TRANSCRIPT, status().isCreated()).get("id").asText();
        long before = broadcastRows.count();

        // Hind manages the same department and still cannot send Nada's words.
        refusedSend(HIND, SCHOOL, hers);
        // And the other school's draft is not Nada's either, nor is an id that never existed.
        refusedSend(NADA, SCHOOL, elsewhere);
        refusedSend(NADA, SCHOOL, "vd-no-such-draft");
        assertThat(broadcastRows.count()).isEqualTo(before);
        assertThat(drafts.findAll().stream().filter(d -> hers.equals(d.getId()) || elsewhere.equals(d.getId())))
                .as("a refused send links nothing").allSatisfy(d -> assertThat(d.getBroadcastId()).isNull());
    }

    // ---------------------------------------------------------------- who may ask, and when

    /**
     * Only the two leadership roles: the two other dashboard roles, the floor supervisor and a parent are each 403,
     * which is `permissions.json` and `SecurityConfig` answering before a row could be written. A child plays through
     * her parent's token and so is the same answer.
     */
    @Test void a_teacher_a_coordinator_a_supervisor_and_a_parent_are_all_refused() throws Exception {
        long before = drafts.count();
        for (String[] who : new String[][] {{TEACHER, "TEACHER"}, {COORDINATOR, "COORDINATOR"}, {SUPERVISOR, "FLOOR_SUPERVISOR"}})
            mvc.perform(as(post("/management/broadcasts/voice-drafts").contentType(MediaType.APPLICATION_JSON)
                    .content(body(TRANSCRIPT)), token(who[0], SCHOOL, who[1]))).andExpect(status().isForbidden());
        mvc.perform(post("/management/broadcasts/voice-drafts").header("Authorization", PARENT)
                .contentType(MediaType.APPLICATION_JSON).content(body(TRANSCRIPT))).andExpect(status().isForbidden());
        assertThat(drafts.count()).as("nobody refused wrote a row").isEqualTo(before);
    }

    @Test void the_route_is_not_there_while_the_switch_is_off() throws Exception {
        flag(SCHOOL, FlagKeys.VOICE_ANNOUNCEMENT, false);
        try {
            draft(NADA, TRANSCRIPT, status().isNotFound());
        } finally {
            flag(SCHOOL, FlagKeys.VOICE_ANNOUNCEMENT, true);
        }
    }

    // ---------------------------------------------------------------- privacy

    /**
     * The spec's privacy line: "Children's personal data is never sent to the AI provider." What the prompt carries is
     * her own words, the date in her school's zone and her academy-and-grade cells — the labels she reads on the
     * preview herself — and nothing else.
     */
    @Test void the_model_is_never_told_a_child_or_a_parent() throws Exception {
        draft(NADA, TRANSCRIPT, status().isCreated());

        var turn = ArgumentCaptor.forClass(String.class);
        verify(llm).complete(any(), turn.capture(), any());
        String sent = turn.getValue();
        assertThat(sent).contains(TRANSCRIPT).contains(TODAY.toString())
                .contains("curriculum \"british\", grade 1").contains("curriculum \"british\", grade 2");
        assertThat(sent).as("her own department only").doesNotContain("american");
        assertThat(sent).doesNotContain(CHILD_NAME).doesNotContain("Lamya").doesNotContain("Alqahtani")
                .doesNotContain(PARENT_UID).doesNotContain(childId);
    }

    // ---------------------------------------------------------------- the edges of the model

    @Test void a_transcript_that_is_blank_or_longer_than_four_thousand_characters_is_refused() throws Exception {
        draft(NADA, "   ", status().isBadRequest());
        draft(NADA, "ا".repeat(4001), status().isBadRequest());
        mvc.perform(as(post("/management/broadcasts/voice-drafts").contentType(MediaType.APPLICATION_JSON)
                .content("{}"), token(NADA, SCHOOL, "MANAGERIAL"))).andExpect(status().isBadRequest());
    }

    /** Two tries, then say so — `StopTextService`'s rule, and the second turn carries the validator's own errors. */
    @Test void an_answer_the_schema_cannot_hold_is_retried_once_and_then_asks_her_to_say_it_again() throws Exception {
        answers("{\"nonsense\":true}");
        long before = drafts.count();

        var error = draft(NADA, TRANSCRIPT, status().isUnprocessableEntity());
        assertThat(error.get("code").asText()).isEqualTo("rephrase");
        assertThat(error.get("message").asText()).startsWith("Couldn't write that announcement").contains("\"title\"");

        var turns = ArgumentCaptor.forClass(String.class);
        verify(llm, times(2)).complete(any(), turns.capture(), any());
        assertThat(turns.getAllValues().get(1)).as("the second attempt is told what was wrong with the first")
                .contains("rejected by the validator");
        assertThat(drafts.count()).as("a refused answer stores nothing").isEqualTo(before);
    }

    @Test void a_provider_that_cannot_answer_is_a_bad_gateway() throws Exception {
        org.mockito.Mockito.doThrow(new LlmClient.LlmException("the provider is busy", null, true))
                .when(llm).complete(any(), any(), any());
        long before = drafts.count();

        var error = draft(NADA, TRANSCRIPT, status().isBadGateway());
        assertThat(error.get("code").asText()).isEqualTo(quest.api.dto.ApiError.MODEL_FAILED);
        assertThat(drafts.count()).isEqualTo(before);
    }

    // ---------------------------------------------------------------- the privacy sweep

    /** A draft she thought better of is her voice in text form; a week later it is gone. A sent one stays with what was sent. */
    @Test void an_unsent_draft_is_deleted_after_a_week_and_a_sent_one_is_kept() throws Exception {
        String forgotten = draft(NADA, TRANSCRIPT, status().isCreated()).get("id").asText();
        var sentDraft = draft(NADA, TRANSCRIPT, status().isCreated());
        mvc.perform(as(post("/management/broadcasts").contentType(MediaType.APPLICATION_JSON)
                .content(send(sentDraft.get("id").asText(), "Museum trip")), token(NADA, SCHOOL, "MANAGERIAL")))
                .andExpect(status().isCreated());

        clock.pinTo(TODAY.plusDays(6));
        retention.sweep();
        assertThat(drafts.findById(forgotten)).as("six days is still hers to send").isPresent();

        clock.pinTo(TODAY.plusDays(8));
        retention.sweep();
        assertThat(drafts.findById(forgotten)).as("an unsent draft does not outlive the week").isEmpty();
        assertThat(drafts.findById(sentDraft.get("id").asText()))
                .as("the transcript of an announcement that went out stays with it").isPresent();
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode draft(String userId, String transcript, ResultMatcher expected) throws Exception {
        return json(mvc.perform(as(post("/management/broadcasts/voice-drafts").contentType(MediaType.APPLICATION_JSON)
                .content(body(transcript)), token(userId, userId.equals(FAR_AWAY) ? OTHER : SCHOOL, "MANAGERIAL")))
                .andExpect(expected).andReturn());
    }

    /** The audience of one draft prepared by a model that answered with exactly `json`, or null when it was dropped. */
    private JsonNode audienceOf(String json) throws Exception {
        answers(json);
        var draft = draft(NADA, TRANSCRIPT, status().isCreated());
        var audience = draft.get("audience");
        return audience == null || audience.isNull() ? null : audience;
    }

    private static String answer(String curriculum, int grade) {
        return "{\"title\":\"رحلة\",\"bodyAr\":\"رحلة الصف\",\"bodyEn\":\"A trip.\","
                + "\"audience\":{\"curriculum\":\"" + curriculum + "\",\"grade\":" + grade + "}}";
    }

    private void answers(String json) {
        org.mockito.Mockito.doReturn(new LlmClient.Result(json, 20, 10)).when(llm).complete(any(), any(), any());
    }

    private String body(String transcript) {
        return mapper.createObjectNode().put("transcript", transcript).toString();
    }

    private static String send(String draftId, String title) {
        return "{\"kind\":\"announcement\",\"title\":\"" + title + "\",\"bodyEn\":\"The trip is on Thursday.\","
                + "\"bodyAr\":\"الرحلة يوم الخميس\",\"audience\":[\"parents\"],\"voiceDraftId\":\"" + draftId + "\"}";
    }

    private void refusedSend(String userId, String schoolId, String draftId) throws Exception {
        var error = json(mvc.perform(as(post("/management/broadcasts").contentType(MediaType.APPLICATION_JSON)
                .content(send(draftId, "Not hers")), token(userId, schoolId, "MANAGERIAL")))
                .andExpect(status().isNotFound()).andReturn());
        assertThat(error.get("code").asText()).isEqualTo("not_found");
    }

    private Entities.AnnouncementDraftEntity row(JsonNode draft) {
        return drafts.findById(draft.get("id").asText()).orElseThrow();
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String token) {
        return b.header("Authorization", "Bearer " + token);
    }

    private String token(String userId, String schoolId, String role) {
        return jwt.issue(userId, userId + "@vd.test", role, schoolId).token();
    }

    private void school(String id, String name, String code) {
        schools.findById(id).orElseGet(() -> {
            var s = new SchoolEntity();
            s.setId(id); s.setName(name); s.setCode(code);
            s.setCurriculumOptionsJson("[\"british\",\"american\"]"); s.setGradeOptionsJson("[1,2,3,4,5,6]");
            s.setStatus("active"); s.setCreatedAt(Instant.now());
            return schools.save(s);
        });
    }

    private void staff(String id, String schoolId, String displayName, String role) {
        var u = users.findById(id).orElseGet(UserEntity::new);
        u.setId(id); u.setSchoolId(schoolId); u.setEmail(id + "@vd.test"); u.setPasswordHash("x");
        u.setRole(role); u.setStatus("active"); u.setDisplayName(displayName);
        if (u.getCreatedAt() == null) u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        users.save(u);
    }

    /** A manager's row is the one with no subject: `subject NULL, curriculum = her department`. */
    private void department(String id, String schoolId, String userId) { scopeRow(id, schoolId, userId, null, "british"); }

    private void scopeRow(String id, String schoolId, String userId, String subject, String curriculum) {
        var row = staffScopes.findById(id).orElseGet(quest.server.tenancy.Entities.StaffScopeEntity::new);
        row.setId(id); row.setSchoolId(schoolId); row.setUserId(userId);
        row.setSubject(subject); row.setCurriculum(curriculum);
        if (row.getCreatedAt() == null) row.setCreatedAt(Instant.now());
        staffScopes.save(row);
    }

    private void flag(String schoolId, String key, boolean enabled) {
        var row = schoolFlags.findOne(schoolId, key).orElseGet(() -> {
            var fresh = new quest.server.flags.Entities.SchoolFeatureFlagEntity();
            fresh.setSchoolId(schoolId); fresh.setFlagKey(key);
            return fresh;
        });
        row.setEnabled(enabled); row.setUpdatedBy("test"); row.setUpdatedAt(Instant.now());
        schoolFlags.save(row);
        featureFlags.invalidate(schoolId);
    }

    private String child(String name, String classId) throws Exception {
        String id = json(mvc.perform(post("/children").header("Authorization", "Bearer fake-token-" + PARENT_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"avatarColor\":\"sun\",\"curriculum\":\"british\",\"grade\":1,\"schoolCode\":\"VDRFT1\"}"))
                .andExpect(status().is2xxSuccessful()).andReturn()).get("id").asText();
        childRows.findById(id).ifPresent(c -> { c.setClassId(classId); childRows.save(c); });
        return id;
    }
}
