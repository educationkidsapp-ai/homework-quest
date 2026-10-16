package quest.server.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.server.ApiTestSupport;
import quest.server.analysis.LlmClient;
import quest.server.auth.AdminJwtService;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.TeacherRepository;
import quest.server.auth.UserRepository;
import quest.server.children.ChildRepository;
import quest.server.classes.SchoolSeed;
import quest.server.flags.FlagKeys;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.StaffScopeRepository;
import quest.server.tenancy.TeachingAssignmentRepository;

/**
 * Feature 27 end to end, on the acceptance fixture {@code ComplaintApiTest} uses — Maya teaches math in 1A British,
 * Lina coordinates math there and Nour manages the British department — with the provider replaced by a mock, so
 * every answer, every rejection and every outage below is the one the test asked for.
 *
 * <p>What it proves: the triage reaches the three staff areas and <strong>never the parent</strong>; a failing
 * provider and a twice-invalid answer each leave the complaint exactly as it was and no triage row; the triage writes
 * no message; a high urgency rings the department manager once; the names, the e-mail address and the number the
 * parent wrote are masked before the provider is given anything; the switch off makes no call at all; and the parent
 * is told when to expect an answer, which follows feature 19's office hours and not this feature's switch.
 *
 * <p>The triage runs after the complaint has committed and on a task thread, so every assertion about it goes through
 * {@link #triagedWithin} rather than straight after the POST — which is the behaviour, not a concession to the test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ComplaintTriageApiTest extends ApiTestSupport {
    private static final String SCHOOL = "trg-school";
    private static final String PARENT_UID = "trg-parent-1a";
    private static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");
    /** 2026-10-18 is a Sunday, a working day on feature 14's defaults (Sunday–Thursday, 07:00–14:00). */
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 18), FRIDAY = SUNDAY.minusDays(2);

    private static final String GOOD = """
        {"category":"transport","urgency":"normal","summaryAr":"الحافلة تتأخر كل صباح.",
         "summaryEn":"The bus is late every morning and the child waits alone.",
         "replyAr":"شكرًا لتواصلكم، سننظر في الأمر.","replyEn":"Thank you for telling us; we are looking into it."}""";

    @MockitoBean LlmClient llm;
    @Autowired SchoolSeed seed;
    @Autowired SchoolRepository schools;
    @Autowired ClassRepository classes;
    @Autowired TeachingAssignmentRepository assignments;
    @Autowired StaffScopeRepository staffScopes;
    @Autowired UserRepository users;
    @Autowired TeacherRepository teacherProfiles;
    @Autowired ChildRepository childRows;
    @Autowired ChatThreadRepository threadRows;
    @Autowired ChatMessageRepository messageRows;
    @Autowired ComplaintEventRepository eventRows;
    @Autowired ComplaintTriageRepository triageRows;
    @Autowired ComplaintTriageService triage;
    @Autowired AdminJwtService jwt;
    @Autowired quest.server.notifications.NotificationRepository notificationRows;
    @Autowired quest.server.flags.SchoolFlagRepository schoolFlags;
    @Autowired quest.server.flags.FeatureFlags featureFlags;

    private String maya, lina, nour, britishA, child;
    private final AtomicReference<String> answer = new AtomicReference<>(GOOD);

    @BeforeAll void loadTheSchool() throws Exception {
        schools.findById(SCHOOL).orElseGet(() -> {
            var s = new SchoolEntity();
            s.setId(SCHOOL); s.setName("Triage School"); s.setCode("TRGS1");
            s.setCurriculumOptionsJson("[\"british\",\"american\"]"); s.setGradeOptionsJson("[1,2,3]");
            s.setStatus("active"); s.setCreatedAt(Instant.now());
            return schools.save(s);
        });
        seed.load(SCHOOL, "trg-pass", false, "seed/acceptance/");
        var school = schools.findById(SCHOOL).orElseThrow();
        school.setTimezone(RIYADH.getId());
        schools.save(school);
        maya = idOf("maya@test.com"); lina = idOf("coord.math@test.com"); nour = idOf("manager@test.com");
        britishA = sectionId("1A British");
        child = child("Lila", britishA);
        flag(FlagKeys.CHAT, true);
    }

    @BeforeEach void theProviderAnswersWhatEachTestAsksFor() {
        answer.set(GOOD);
        when(llm.name()).thenReturn("mock");
        when(llm.complete(any(), any(), any())).thenAnswer(call -> {
            String next = answer.get();
            if (next == null) throw new LlmClient.LlmException("the provider is busy", null, true);
            return new LlmClient.Result(next, 400, 200);
        });
        flag(FlagKeys.COMPLAINT_TRIAGE, true);
        flag(FlagKeys.CHAT_OFFICE_HOURS, false);
    }

    @AfterEach void forgetWhatThisTestWrote() {
        clock.release();
        triageRows.deleteAll(triageRows.findAll().stream().filter(t -> SCHOOL.equals(t.getSchoolId())).toList());
        messageRows.deleteAll(messageRows.findAll().stream().filter(m -> m.getSchoolId().startsWith("trg-")).toList());
        eventRows.deleteAll(eventRows.findAll().stream().filter(e -> e.getSchoolId().startsWith("trg-")).toList());
        threadRows.deleteAll(threadRows.findAll().stream().filter(t -> t.getSchoolId().startsWith("trg-")).toList());
        notificationRows.deleteAll(notificationRows.findAll().stream()
                .filter(n -> n.getSchoolId() != null && n.getSchoolId().startsWith("trg-")).toList());
    }

    @AfterAll void takeItBackOut() {
        childRows.deleteAll(childRows.findAll().stream().filter(c -> c.getSchoolId().startsWith("trg-")).toList());
        assignments.deleteAll(assignments.findAll().stream().filter(a -> SCHOOL.equals(a.getSchoolId())).toList());
        staffScopes.deleteAll(staffScopes.findAll().stream().filter(r -> r.getSchoolId().startsWith("trg-")).toList());
        classes.deleteAll(classes.findAll().stream().filter(k -> k.getSchoolId().startsWith("trg-")).toList());
        var staff = new ArrayList<UserEntity>(users.findBySchoolId(SCHOOL));
        teacherProfiles.deleteAll(teacherProfiles.findAllById(staff.stream().map(UserEntity::getId).toList()));
        users.deleteAll(staff);
    }

    // ---------------------------------------------------------------- the triage the staff read

    @Test void every_staff_area_reads_the_triage_and_the_parent_never_does() throws Exception {
        var created = complain("The bus is always late", "She waits alone for twenty minutes every morning.");
        String id = created.get("complaint").get("id").asText();
        assertThat(created.get("complaint").hasNonNull("triage")).as("the answer to the POST is hers, and it is too early anyway").isFalse();
        triagedWithin(id);

        for (var area : List.of(new String[] {maya, "TEACHER", "/teacher"}, new String[] {lina, "COORDINATOR", "/coordinator"},
                new String[] {nour, "MANAGERIAL", "/management"})) {
            var onTheList = rowWith(staffGet(area[0], area[1], area[2] + "/complaints").get("complaints"), "id", id).get("triage");
            assertThat(onTheList.get("category").asText()).as(area[2] + " list").isEqualTo("transport");
            assertThat(onTheList.get("urgency").asText()).isEqualTo("normal");
            var detail = staffGet(area[0], area[1], area[2] + "/complaints/" + id).get("complaint").get("triage");
            assertThat(detail.get("summaryEn").asText()).startsWith("The bus is late every morning");
            assertThat(detail.get("summaryAr").asText()).isEqualTo("الحافلة تتأخر كل صباح.");
            assertThat(detail.get("replyEn").asText()).isEqualTo("Thank you for telling us; we are looking into it.");
            assertThat(detail.get("replyAr").asText()).isEqualTo("شكرًا لتواصلكم، سننظر في الأمر.");
        }

        // The parent: neither on her list nor on her detail, which is the one thing a client cannot be allowed to leak.
        assertThat(rowWith(parentJson("/children/" + child + "/complaints").get("complaints"), "id", id).hasNonNull("triage")).isFalse();
        var hers = parentJson("/children/" + child + "/complaints/" + id);
        assertThat(hers.get("complaint").hasNonNull("triage")).isFalse();
        assertThat(hers.toString()).as("not the summary, not the suggested reply, not the category, anywhere in her body")
                .doesNotContain("transport").doesNotContain("looking into it").doesNotContain("الحافلة");
    }

    @Test void the_triage_sends_nothing_and_the_suggested_reply_is_only_a_suggestion() throws Exception {
        var created = complain("The bus is always late", "She waits alone every morning.");
        String id = created.get("complaint").get("id").asText();
        triagedWithin(id);
        assertThat(messageRows.findAll().stream().filter(m -> id.equals(m.getThreadId())).toList())
                .as("the parent's own first message, and nothing the triage wrote").hasSize(1);
        var detail = staffGet(maya, "TEACHER", "/teacher/complaints/" + id);
        assertThat(detail.get("messages").size()).isEqualTo(1);
        assertThat(detail.get("complaint").get("triage").get("replyEn").asText()).isNotEmpty();
        assertThat(parentJson("/children/" + child + "/complaints/" + id).get("messages").size()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- an AI failure is not the parent's problem

    @Test void a_provider_that_is_down_still_leaves_the_complaint_created() throws Exception {
        answer.set(null);                                                       // every call throws a transient failure
        var created = complain("The bus is always late", "She waits alone every morning.");
        String id = created.get("complaint").get("id").asText();
        assertThat(created.get("complaint").get("status").asText()).isEqualTo("open");
        assertThat(created.get("messages").size()).isEqualTo(1);
        assertThat(noTriageAfterTheProviderHasBeenAsked(id)).isTrue();
        assertThat(staffGet(maya, "TEACHER", "/teacher/complaints/" + id).get("complaint").hasNonNull("triage")).isFalse();
        // And the bell the complaint itself rings is untouched.
        assertThat(kindsAbout(maya, "TEACHER", id)).containsExactly("complaint.new");
    }

    @Test void an_answer_that_is_wrong_twice_leaves_no_triage() throws Exception {
        answer.set("{\"category\":\"weather\",\"urgency\":\"immediately\"}");
        var created = complain("The bus is always late", "She waits alone every morning.");
        String id = created.get("complaint").get("id").asText();
        assertThat(created.get("complaint").get("title").asText()).isEqualTo("The bus is always late");
        assertThat(noTriageAfterTheProviderHasBeenAsked(id)).isTrue();
        verify(llm, times(2)).complete(any(), any(), any());                    // asked again once, and then given up on
    }

    @Test void with_the_switch_off_the_provider_is_never_asked() throws Exception {
        flag(FlagKeys.COMPLAINT_TRIAGE, false);
        var created = complain("The bus is always late", "She waits alone every morning.");
        String id = created.get("complaint").get("id").asText();
        assertThat(created.get("complaint").get("status").asText()).isEqualTo("open");
        for (int i = 0; i < 20 && triageRows.findById(id).isEmpty(); i++) Thread.sleep(50);
        assertThat(triageRows.findById(id)).isEmpty();
        verify(llm, never()).complete(any(), any(), any());
    }

    // ---------------------------------------------------------------- the one escalation

    @Test void a_high_urgency_rings_the_department_manager_once() throws Exception {
        answer.set(GOOD.replace("\"urgency\":\"normal\"", "\"urgency\":\"high\""));
        var created = complain("She was left on the bus", "Nobody noticed she was still on board. This is unsafe.");
        String id = created.get("complaint").get("id").asText();
        triagedWithin(id);
        assertThat(triageRows.findById(id).orElseThrow().getUrgency()).isEqualTo("high");
        // The row is written *before* `escalate` runs, so waiting for the row is not waiting for the bell.
        escalatedWithin(id);

        var manager = rowWith(staffGet(nour, "MANAGERIAL", "/me/notifications"), "lessonId", id);
        assertThat(manager.get("kind").asText()).isEqualTo("complaint.new");
        assertThat(manager.get("title").asText()).isEqualTo("Urgent complaint needs attention");
        assertThat(manager.get("body").asText()).isEqualTo("She was left on the bus");
        assertThat(manager.get("link").asText()).isEqualTo("/management/complaints?open=" + id);
        assertThat(kindsAbout(nour, "MANAGERIAL", id)).as("rung once, not once per read").hasSize(1);
        // The recipient keeps the one bell the complaint itself rang; she is not told twice about the same thing.
        assertThat(kindsAbout(maya, "TEACHER", id)).containsExactly("complaint.new");
        assertThat(kindsAbout(lina, "COORDINATOR", id)).as("a coordinator is not the escalation").isEmpty();
    }

    /**
     * The negative, run <strong>on the test thread</strong>. Waiting for the triage row and then asserting that
     * nobody was rung proves nothing: the service writes that row and escalates afterwards, so the assertion used to
     * run before the bell it is about could have landed — a triage that escalated every urgency passed this test.
     * The complaint is therefore created with the switch off (no task thread at all), the switch is turned on, and
     * {@link ComplaintTriageService#triage} is called here: when it returns, everything it was ever going to do is
     * done, and "nobody extra was rung" is a fact.
     */
    @Test void a_normal_urgency_tells_the_manager_nothing() throws Exception {
        flag(FlagKeys.COMPLAINT_TRIAGE, false);
        var created = complain("The bus is always late", "She waits alone every morning.");
        String id = created.get("complaint").get("id").asText();
        flag(FlagKeys.COMPLAINT_TRIAGE, true);

        triage.triage(id);
        assertThat(triageRows.findById(id).orElseThrow().getUrgency()).as("triaged, and on this thread").isEqualTo("normal");
        assertThat(kindsAbout(nour, "MANAGERIAL", id)).isEmpty();
        assertThat(kindsAbout(maya, "TEACHER", id)).as("the recipient's own bell, and no second one").containsExactly("complaint.new");
    }

    // ---------------------------------------------------------------- what the provider is not told

    @Test void no_name_no_address_and_no_number_reaches_the_provider() throws Exception {
        String id = complain("Lila was left behind by the bus",
                "Ms Maya knows about it. Ring me on 0551234567 or write to lilas.dad@example.com.")
                .get("complaint").get("id").asText();
        triagedWithin(id);

        var system = ArgumentCaptor.forClass(String.class);
        var user = ArgumentCaptor.forClass(String.class);
        verify(llm, atLeastOnce()).complete(system.capture(), user.capture(), any());
        String sent = user.getValue();
        assertThat(sent).as("the child's name").doesNotContain("Lila")
                .as("the recipient's name").doesNotContain("Maya")
                .as("the number she wrote").doesNotContain("0551234567")
                .as("the address she wrote").doesNotContain("lilas.dad@example.com");
        assertThat(sent).contains("[name]").contains("[phone]").contains("[email]");
        assertThat(sent).as("the complaint's own words are what it is given").contains("left behind by the bus");
        assertThat(system.getValue()).as("Prompt F, which says the masks are masks").contains("[name], [email] and [phone]");
    }

    /**
     * The masks are the guard's, and the prompt says so — which is exactly why a model may write them back. The
     * dashboard's "Use suggested reply" pastes `replyEn` into the box unchanged and the member of staff presses
     * Send, so a draft reading "Dear parent of [name], we will call you on [phone]" would reach the parent as
     * written. It is rejected like any other broken answer, and the retry is told why.
     */
    @Test void a_suggested_reply_that_echoes_a_mask_is_refused() throws Exception {
        answer.set(GOOD.replace("Thank you for telling us; we are looking into it.",
                "Dear parent of [name], we will call you on [phone]."));
        var created = complain("Lila was left behind by the bus", "Ring me on 0551234567.");
        String id = created.get("complaint").get("id").asText();

        assertThat(noTriageAfterTheProviderHasBeenAsked(id)).as("twice rejected is no triage at all").isTrue();
        verify(llm, times(2)).complete(any(), any(), any());
        var second = ArgumentCaptor.forClass(String.class);
        verify(llm, times(2)).complete(any(), second.capture(), any());
        assertThat(second.getValue()).as("the retry is told what was wrong with the first answer").contains("placeholder");
    }

    /** And the masks a <em>summary</em> carries are the staff's own reading, which is not a reply to anybody. */
    @Test void a_summary_that_names_a_mask_is_still_a_triage() throws Exception {
        answer.set(GOOD.replace("The bus is late every morning and the child waits alone.",
                "[name]'s mother says the bus is late every morning."));
        var created = complain("The bus is always late", "She waits alone every morning.");
        String id = created.get("complaint").get("id").asText();
        triagedWithin(id);
        assertThat(triageRows.findById(id).orElseThrow().getSummaryEn()).contains("[name]");
    }

    // ---------------------------------------------------------------- the parent's expected reply time

    @Test void the_expected_reply_time_follows_the_recipients_office_hours() throws Exception {
        flag(FlagKeys.CHAT_OFFICE_HOURS, true);

        // Inside her hours: four hours from now.
        at(SUNDAY, "09:00");
        String morning = complain("The bus is always late", "She waits alone every morning.").get("complaint").get("id").asText();
        assertThat(expectedReplyBy(morning)).isEqualTo(instant(SUNDAY, "13:00"));

        // Still inside them, but four hours would be after the school has closed: the cap is today's closing time.
        at(SUNDAY, "13:30");
        String afternoon = complain("The bus is always late again", "And again this afternoon.").get("complaint").get("id").asText();
        assertThat(expectedReplyBy(afternoon)).as("14:00, not 17:30").isEqualTo(instant(SUNDAY, "14:00"));

        // Outside them: two hours after her next working period starts (Friday waits for Sunday).
        at(FRIDAY, "10:00");
        String weekend = complain("The bus was late on Thursday too", "Thursday as well.").get("complaint").get("id").asText();
        assertThat(expectedReplyBy(weekend)).isEqualTo(instant(SUNDAY, "09:00"));

        // The switch off promises nothing, which is the app feature 19 shipped onto.
        flag(FlagKeys.CHAT_OFFICE_HOURS, false);
        assertThat(parentJson("/children/" + child + "/complaints/" + morning).hasNonNull("expectedReplyBy")).isFalse();
    }

    @Test void the_expected_reply_time_does_not_depend_on_the_triage_switch() throws Exception {
        flag(FlagKeys.CHAT_OFFICE_HOURS, true);
        flag(FlagKeys.COMPLAINT_TRIAGE, false);
        at(SUNDAY, "09:00");
        String id = complain("The bus is always late", "She waits alone every morning.").get("complaint").get("id").asText();
        assertThat(expectedReplyBy(id)).isEqualTo(instant(SUNDAY, "13:00"));
        assertThat(staffGet(maya, "TEACHER", "/teacher/complaints/" + id).hasNonNull("expectedReplyBy"))
                .as("it is a promise to the parent, not a deadline the school is measured on").isFalse();
    }

    // ---------------------------------------------------------------- helpers

    /** The complaint, once the task thread has written its triage — or an assertion failure saying it never did. */
    private void triagedWithin(String complaintId) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (triageRows.findById(complaintId).isPresent()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("the complaint was never triaged: " + complaintId);
    }

    /** The escalation bell, once the task thread has written it — the triage row lands before it, not with it. */
    private void escalatedWithin(String complaintId) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (!kindsAbout(nour, "MANAGERIAL", complaintId).isEmpty()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("the department manager was never rung about: " + complaintId);
    }

    /** Waits for the provider to have been asked (and to have failed), then answers whether no row was written. */
    private boolean noTriageAfterTheProviderHasBeenAsked(String complaintId) throws Exception {
        for (int i = 0; i < 100; i++) {
            try { verify(llm, atLeastOnce()).complete(any(), any(), any()); break; }
            catch (AssertionError notYet) { Thread.sleep(50); }
        }
        Thread.sleep(200);                                                      // and nothing lands afterwards either
        return triageRows.findById(complaintId).isEmpty();
    }

    private JsonNode complain(String title, String body) throws Exception {
        return json(mvc.perform(post("/children/" + child + "/complaints").header("Authorization", bearer(PARENT_UID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"staffId\":\"" + maya + "\",\"title\":" + ChatTestSupport.quote(title) + ",\"body\":" + ChatTestSupport.quote(body) + "}"))
                .andExpect(status().isCreated()).andReturn());
    }

    private Instant expectedReplyBy(String complaintId) throws Exception {
        var detail = parentJson("/children/" + child + "/complaints/" + complaintId);
        assertThat(detail.hasNonNull("expectedReplyBy")).as("the parent is told something").isTrue();
        return Instant.ofEpochMilli(detail.get("expectedReplyBy").asLong());
    }

    private void at(LocalDate day, String riyadhTime) { clock.pinTo(instant(day, riyadhTime)); }

    private static Instant instant(LocalDate day, String riyadhTime) {
        return day.atTime(LocalTime.parse(riyadhTime)).atZone(RIYADH).toInstant();
    }

    private List<String> kindsAbout(String userId, String role, String complaintId) throws Exception {
        var out = new ArrayList<String>();
        for (var n : staffGet(userId, role, "/me/notifications"))
            if (complaintId.equals(n.path("lessonId").asText())) out.add(n.get("kind").asText());
        return out;
    }

    private JsonNode parentJson(String path) throws Exception {
        return json(mvc.perform(get(path).header("Authorization", bearer(PARENT_UID))).andExpect(status().isOk()).andReturn());
    }

    private JsonNode staffGet(String userId, String role, String path) throws Exception {
        return json(mvc.perform(as(get(path), token(userId, role))).andExpect(status().isOk()).andReturn());
    }

    private String token(String userId, String role) { return jwt.issue(userId, userId + "@seed.test", role, SCHOOL).token(); }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String token) { return b.header("Authorization", "Bearer " + token); }

    private static String bearer(String parentUid) { return "Bearer fake-token-" + parentUid; }

    private static JsonNode rowWith(JsonNode rows, String field, String value) {
        for (var row : rows) if (row.get(field) != null && value.equals(row.get(field).asText())) return row;
        throw new AssertionError("no row with " + field + " = " + value + " in " + rows);
    }

    private void flag(String key, boolean enabled) {
        var row = schoolFlags.findOne(SCHOOL, key).orElseGet(() -> {
            var fresh = new quest.server.flags.Entities.SchoolFeatureFlagEntity();
            fresh.setSchoolId(SCHOOL); fresh.setFlagKey(key);
            return fresh;
        });
        row.setEnabled(enabled); row.setUpdatedBy("test"); row.setUpdatedAt(Instant.now());
        schoolFlags.save(row);
        featureFlags.invalidate(SCHOOL);
    }

    private String child(String name, String classId) throws Exception {
        String id = json(mvc.perform(post("/children").header("Authorization", bearer(PARENT_UID)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"avatarColor\":\"sun\",\"curriculum\":\"british\",\"grade\":1,\"schoolCode\":\"TRGS1\"}"))
                .andExpect(status().is2xxSuccessful()).andReturn()).get("id").asText();
        childRows.findById(id).ifPresent(c -> { c.setClassId(classId); childRows.save(c); });
        return id;
    }

    private String idOf(String email) {
        return users.findBySchoolId(SCHOOL).stream().filter(u -> email.equalsIgnoreCase(u.getEmail())).findFirst().orElseThrow().getId();
    }

    private String sectionId(String name) {
        return classes.findAll().stream().filter(k -> SCHOOL.equals(k.getSchoolId())
                        && name.toLowerCase(Locale.ROOT).equals(k.getName() == null ? null : k.getName().toLowerCase(Locale.ROOT)))
                .findFirst().orElseThrow().getId();
    }
}
