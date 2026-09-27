package quest.server.broadcasts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.server.ApiTestSupport;
import quest.server.ClassFixtures;
import quest.server.auth.AdminJwtService;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.TeacherRepository;
import quest.server.auth.UserRepository;
import quest.server.chat.ChatBus;
import quest.server.chat.ChatEvent;
import quest.server.chat.ChatMessageRepository;
import quest.server.chat.ChatThreadRepository;
import quest.server.children.ChildRepository;
import quest.server.classes.SchoolSeed;
import quest.server.flags.FlagKeys;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.StaffScopeRepository;
import quest.server.tenancy.TeachingAssignmentRepository;
import quest.server.tenancy.TenantContext;

/**
 * RM2 (DR6, DR5) on the owner's own fixture, the one `CoordinatorCommsApiTest` uses: Maya teaches math in 1A and 1B
 * British, Rami english in 1A American, Lina coordinates math/British, Omar english/American, Nour manages British and
 * Sami American.
 *
 * <p>What it proves, in the brief's order: Nour's British weekly plan reaches the British parents, Maya and Lina and
 * nobody American, with a bell row for the staff and none for the author; re-posting the week replaces the plan; Sami's
 * event reaches the American parent only; a coordinator's announcement reaches the parents of her classes only and is
 * one row in both her feeds; Nour and Lina share one thread and Nour and the admin another; a British parent lists Nour
 * and opens a complaint with her; Sami can reach neither Lina nor Nour's thread; and every bus event names one school.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BroadcastApiTest extends ApiTestSupport {
    private static final String SCHOOL = "bc-school", OTHER_SCHOOL = "bc-school-b", DIR = "seed/acceptance/";
    private static final String STAFF_PASSWORD = "bc-pass";
    private static final String LINA = "coord.math@test.com", OMAR = "coord.english@test.com";
    private static final String NOUR = "manager@test.com", SAMI = "manager2@test.com";
    private static final String MAYA = "maya@test.com", RAMI = "rami@test.com";
    private static final String BRITISH_PARENT = "bc-parent-uk", BRITISH_B_PARENT = "bc-parent-uk-b", AMERICAN_PARENT = "bc-parent-us";

    @Autowired SchoolSeed seed;
    @Autowired SchoolRepository schools;
    @Autowired ClassRepository classes;
    @Autowired TeachingAssignmentRepository assignments;
    @Autowired StaffScopeRepository staffScopes;
    @Autowired UserRepository users;
    @Autowired TeacherRepository teacherProfiles;
    @Autowired ChildRepository childRows;
    @Autowired BroadcastRepository broadcastRows;
    @Autowired BroadcastReadRepository readRows;
    @Autowired ChatThreadRepository threadRows;
    @Autowired ChatMessageRepository messageRows;
    @Autowired quest.server.notifications.NotificationRepository notificationRows;
    @Autowired quest.server.teacher.AnnouncementRepository announcementRows;
    @Autowired quest.server.flags.SchoolFlagRepository schoolFlags;
    @Autowired quest.server.flags.FeatureFlags featureFlags;
    @Autowired ChatBus bus;
    @Autowired AdminJwtService jwt;

    private String lina, omar, nour, sami, maya, rami, britishA, britishB, americanA;
    /** A British science coordinator whose only section is 1B, so a row naming 1A is one she must not hear about. */
    private static final String HUDA = "bc-huda";
    private String childBritishA, childBritishB, childAmerican, adminToken, adminUserId;
    private final List<ChatEvent> heard = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final LocalDate week = LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.SUNDAY));

    @BeforeAll void loadTheSchool() throws Exception {
        school(SCHOOL, "Broadcast School", "BCAST1");
        school(OTHER_SCHOOL, "Quiet School", "BCAST2");
        seed.load(SCHOOL, STAFF_PASSWORD, false, DIR);
        lina = idOf(LINA); omar = idOf(OMAR); nour = idOf(NOUR); sami = idOf(SAMI); maya = idOf(MAYA); rami = idOf(RAMI);
        britishA = sectionId("1A British"); britishB = sectionId("1B British"); americanA = sectionId("1A American");
        childBritishA = child("Lila", britishA, "british", BRITISH_PARENT, 1);
        childBritishB = child("Bilal", britishB, "british", BRITISH_B_PARENT, 1);
        childAmerican = child("Hana", americanA, "american", AMERICAN_PARENT, 1);
        // Science in 1B British only, and a coordinator for it: the smallest fixture in which "same track, other
        // section" exists at all, which is what the fan-out has to tell apart from "same track".
        ClassFixtures.assign(assignments, classes.findById(britishB).orElseThrow(), "science", maya);
        staff(HUDA, "Huda", "COORDINATOR");
        scopeRow("bc-scope-science", HUDA, "science", "british");
        for (String key : List.of(FlagKeys.CHAT, FlagKeys.ANNOUNCEMENTS)) { flag(SCHOOL, key, true); flag(OTHER_SCHOOL, key, true); }
        adminToken = adminToken();
        adminUserId = users.findByEmailIgnoreCase("admin@test.local").orElseThrow().getId();
        bus.subscribe(heard::add);
    }

    @AfterAll void takeItBackOut() {
        readRows.deleteAll(readRows.findAll().stream().filter(r -> r.getSchoolId().startsWith("bc-")).toList());
        broadcastRows.deleteAll(broadcastRows.findAll().stream().filter(b -> b.getSchoolId().startsWith("bc-")).toList());
        notificationRows.deleteAll(notificationRows.findAll().stream().filter(n -> n.getSchoolId().startsWith("bc-")).toList());
        messageRows.deleteAll(messageRows.findAll().stream().filter(m -> m.getSchoolId().startsWith("bc-")).toList());
        threadRows.deleteAll(threadRows.findAll().stream().filter(t -> t.getSchoolId().startsWith("bc-")).toList());
        announcementRows.deleteAll(announcementRows.findAll().stream().filter(a -> a.getSchoolId().startsWith("bc-")).toList());
        childRows.deleteAll(childRows.findAll().stream().filter(c -> c.getSchoolId().startsWith("bc-")).toList());
        assignments.deleteAll(assignments.findAll().stream().filter(a -> SCHOOL.equals(a.getSchoolId())).toList());
        staffScopes.deleteAll(staffScopes.findAll().stream().filter(r -> r.getSchoolId().startsWith("bc-")).toList());
        classes.deleteAll(classes.findAll().stream().filter(k -> k.getSchoolId().startsWith("bc-")).toList());
        var staff = new ArrayList<UserEntity>(users.findBySchoolId(SCHOOL));
        staff.addAll(users.findBySchoolId(OTHER_SCHOOL));
        teacherProfiles.deleteAll(teacherProfiles.findAllById(staff.stream().map(UserEntity::getId).toList()));
        users.deleteAll(staff);
    }

    // ---------------------------------------------------------------- the manager's weekly plan

    @Test @Order(1) void the_british_weekly_plan_reaches_the_british_department_only() throws Exception {
        var plan = created(nour, "/management/broadcasts", "{\"kind\":\"weekly_plan\",\"weekStart\":\"" + week + "\","
                + "\"title\":\"Week of subtraction\",\"bodyEn\":\"Subtraction all week; swimming on Thursday.\","
                + "\"bodyAr\":\"الطرح\",\"audience\":[\"parents\",\"teachers\",\"coordinators\"],"
                + "\"attachment\":{\"url\":\"/media/pages/plan-1\",\"name\":\"plan.pdf\"}}");
        assertThat(plan.get("kind").asText()).isEqualTo("weekly_plan");
        assertThat(plan.get("weekStart").asText()).isEqualTo(week.toString());
        assertThat(plan.get("curriculum").asText()).isEqualTo("british");
        assertThat(plan.get("authorRole").asText()).isEqualTo("MANAGERIAL");
        assertThat(plan.get("attachment").get("name").asText()).isEqualTo("plan.pdf");
        assertThat(plan.get("sectionIds")).as("the whole department, so no section is named").isEmpty();

        // The two British parents see it; the American one does not.
        for (var pair : List.of(List.of(BRITISH_PARENT, childBritishA), List.of(BRITISH_B_PARENT, childBritishB))) {
            var feed = parentGet(pair.get(0), "/children/" + pair.get(1) + "/broadcasts");
            assertThat(names(feed.get("items"), "title")).contains("Week of subtraction");
            assertThat(feed.get("unread").asInt()).isEqualTo(1);
        }
        assertThat(names(parentGet(AMERICAN_PARENT, "/children/" + childAmerican + "/broadcasts").get("items"), "title"))
                .doesNotContain("Week of subtraction");

        // Maya teaches inside the department and Lina coordinates inside it: both get the row and a bell.
        for (String staff : List.of(maya, lina)) {
            String role = staff.equals(maya) ? "TEACHER" : "COORDINATOR";
            assertThat(names(staffGet(staff, role, "/me/broadcasts").get("items"), "title")).contains("Week of subtraction");
            assertThat(names(staffGet(staff, role, "/me/notifications"), "kind")).contains("broadcast.posted");
        }
        // Rami and Omar are American: neither the feed nor the bell.
        assertThat(staffGet(rami, "TEACHER", "/me/broadcasts").get("items")).isEmpty();
        assertThat(names(staffGet(omar, "COORDINATOR", "/me/notifications"), "kind")).doesNotContain("broadcast.posted");
        // The author is not notified about her own broadcast, and reads it as already hers.
        assertThat(names(staffGet(nour, "MANAGERIAL", "/me/notifications"), "kind")).doesNotContain("broadcast.posted");
        assertThat(names(staffGet(nour, "MANAGERIAL", "/management/broadcasts"), "title")).containsExactly("Week of subtraction");

        // Every bus event of the fan-out names this school and this school only.
        assertThat(heard.stream().filter(e -> ChatEvent.NOTIFICATION.equals(e.kind())).map(ChatEvent::schoolId).distinct().toList())
                .allSatisfy(school -> assertThat(school).isEqualTo(SCHOOL));
    }

    @Test @Order(2) void re_posting_the_week_replaces_the_plan_and_the_read_marks() throws Exception {
        String first = names(staffGet(nour, "MANAGERIAL", "/management/broadcasts"), "id").get(0);
        staffPost(BRITISH_PARENT, "/children/" + childBritishA + "/broadcasts/" + first + "/read");
        assertThat(parentGet(BRITISH_PARENT, "/children/" + childBritishA + "/broadcasts").get("unread").asInt()).isZero();

        var again = created(nour, "/management/broadcasts", "{\"kind\":\"weekly_plan\",\"weekStart\":\"" + week + "\","
                + "\"title\":\"Week of subtraction (v2)\",\"bodyEn\":\"Swimming moved to Wednesday.\","
                + "\"audience\":[\"parents\",\"teachers\"]}");
        // One week, one bell entry: the superseded plan's notification goes with its row, or Maya is left with two
        // titles for one week and one of them opens a feed that no longer has it.
        var bells = names(staffGet(maya, "TEACHER", "/me/notifications"), "title");
        assertThat(bells).contains("Week of subtraction (v2)").doesNotContain("Week of subtraction");
        assertThat(bells.stream().filter(t -> t != null && t.startsWith("Week of subtraction")).count()).isOne();
        assertThat(names(staffGet(maya, "TEACHER", "/me/broadcasts").get("items"), "title"))
                .containsExactly("Week of subtraction (v2)");
        assertThat(names(staffGet(nour, "MANAGERIAL", "/management/broadcasts"), "id"))
                .as("one plan per week per department").containsExactly(again.get("id").asText());
        var feed = parentGet(BRITISH_PARENT, "/children/" + childBritishA + "/broadcasts");
        assertThat(names(feed.get("items"), "title")).containsExactly("Week of subtraction (v2)");
        assertThat(feed.get("unread").asInt()).as("the replacement arrives unread").isEqualTo(1);
    }

    @Test @Order(3) void the_american_managers_event_reaches_the_american_parent_only() throws Exception {
        created(sami, "/management/broadcasts", "{\"kind\":\"event\",\"title\":\"Thanksgiving assembly\","
                + "\"bodyEn\":\"Thursday at nine.\",\"audience\":[\"parents\"]}");
        assertThat(names(parentGet(AMERICAN_PARENT, "/children/" + childAmerican + "/broadcasts").get("items"), "title"))
                .contains("Thanksgiving assembly");
        assertThat(names(parentGet(BRITISH_PARENT, "/children/" + childBritishA + "/broadcasts").get("items"), "title"))
                .doesNotContain("Thanksgiving assembly");

        // A section of the other department is 403, and the three kinds are the only kinds.
        mvc.perform(as(post("/management/broadcasts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"event\",\"bodyEn\":\"Hello\",\"audience\":[\"parents\"],\"sectionIds\":[\"" + britishA + "\"]}"),
                token(sami, "MANAGERIAL"))).andExpect(status().isForbidden());
        // A weekly plan whose sections span both tracks names no department, and a plan with no department would
        // match — and delete — every department's plan for that week. Refused, so the delete is always one track's.
        mvc.perform(as(post("/management/broadcasts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"weekly_plan\",\"weekStart\":\"" + week + "\",\"bodyEn\":\"Both tracks\","
                                + "\"audience\":[\"parents\"],\"sectionIds\":[\"" + britishA + "\",\"" + americanA + "\"]}"),
                adminToken).header(TenantContext.HEADER, SCHOOL)).andExpect(status().isBadRequest());
        // The American manager's own plan leaves the British one where it is.
        created(sami, "/management/broadcasts", "{\"kind\":\"weekly_plan\",\"weekStart\":\"" + week + "\","
                + "\"title\":\"American week\",\"bodyEn\":\"Spelling bee.\",\"audience\":[\"parents\"]}");
        assertThat(names(staffGet(nour, "MANAGERIAL", "/management/broadcasts"), "title"))
                .as("the other department's replacement is not hers").contains("Week of subtraction (v2)");
        assertThat(names(parentGet(AMERICAN_PARENT, "/children/" + childAmerican + "/broadcasts").get("items"), "title"))
                .contains("American week");

        for (String body : List.of("{\"kind\":\"newsletter\",\"bodyEn\":\"Hi\",\"audience\":[\"parents\"]}",
                "{\"kind\":\"event\",\"bodyEn\":\"Hi\",\"audience\":[]}",
                "{\"kind\":\"weekly_plan\",\"bodyEn\":\"Hi\",\"audience\":[\"parents\"]}",
                "{\"kind\":\"event\",\"bodyEn\":\"Hi\",\"audience\":[\"parents\"],\"weekStart\":\"" + week + "\"}"))
            mvc.perform(as(post("/management/broadcasts").contentType(MediaType.APPLICATION_JSON).content(body), token(sami, "MANAGERIAL")))
                    .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- the coordinator's own broadcast

    @Test @Order(4) void the_coordinators_announcement_reaches_the_parents_of_her_classes_only() throws Exception {
        var written = created(lina, "COORDINATOR", "/coordinator/broadcasts",
                "{\"kind\":\"announcement\",\"bodyEn\":\"Times tables week starts Sunday.\"}");
        assertThat(ids(written.get("sectionIds"))).containsExactlyInAnyOrder(britishA, britishB);
        assertThat(written.get("authorRole").asText()).isEqualTo("COORDINATOR");
        assertThat(written.get("curriculum").isNull()).as("her sections say which track it is").isTrue();
        // The app labels the card from these two: "from your maths coordinator" / "from the British department".
        assertThat(written.get("subject").asText()).isEqualTo("math");
        assertThat(written.get("authorName").asText()).isNotBlank();

        for (var pair : List.of(List.of(BRITISH_PARENT, childBritishA), List.of(BRITISH_B_PARENT, childBritishB)))
            assertThat(names(parentGet(pair.get(0), "/children/" + pair.get(1) + "/broadcasts").get("items"), "bodyEn"))
                    .contains("Times tables week starts Sunday.");
        assertThat(names(parentGet(AMERICAN_PARENT, "/children/" + childAmerican + "/broadcasts").get("items"), "bodyEn"))
                .doesNotContain("Times tables week starts Sunday.");

        // DR6 is one feature: the same call keeps the `announcements` row the app screen already reads — and that row
        // now says who is speaking, so the app need not treat a coordinator's note as a teacher's.
        var cards = parentGet(BRITISH_PARENT, "/children/" + childBritishA + "/announcements");
        assertThat(names(cards, "bodyEn")).contains("Times tables week starts Sunday.");
        var card = rowWith(cards, "bodyEn", "Times tables week starts Sunday.");
        assertThat(card.get("authorRole").asText()).isEqualTo("COORDINATOR");
        assertThat(card.get("subject").asText()).isEqualTo("math");
        assertThat(card.get("curriculum").asText()).isEqualTo("british");
        assertThat(card.get("teacherName").asText()).isNotBlank();
        // And the legacy door writes exactly one broadcast of its own.
        created(lina, "COORDINATOR", "/coordinator/announcements", "{\"bodyEn\":\"Bring a ruler tomorrow.\"}");
        assertThat(names(staffGet(lina, "COORDINATOR", "/coordinator/broadcasts"), "bodyEn"))
                .containsExactlyInAnyOrder("Bring a ruler tomorrow.", "Times tables week starts Sunday.");
        assertThat(names(parentGet(BRITISH_PARENT, "/children/" + childBritishA + "/announcements"), "bodyEn"))
                .contains("Bring a ruler tomorrow.");
        // A weekly plan is the manager's kind, and Nour's plan is not hers to list.
        mvc.perform(as(post("/coordinator/broadcasts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"weekly_plan\",\"weekStart\":\"" + week + "\",\"bodyEn\":\"Mine now\"}"),
                token(lina, "COORDINATOR"))).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- the manager's chat

    @Test @Order(5) void nour_and_lina_share_one_thread_and_sami_reaches_neither() throws Exception {
        var thread = created(nour, "/management/chat/threads", "{\"coordinatorUserId\":\"" + lina + "\"}");
        assertThat(thread.get("staffRole").asText()).isEqualTo("MANAGERIAL");
        assertThat(thread.get("teacherId").asText()).as("the row names the other person").isEqualTo(lina);
        String threadId = thread.get("id").asText();
        assertThat(created(nour, "/management/chat/threads", "{\"coordinatorUserId\":\"" + lina + "\"}").get("id").asText())
                .as("asked for twice, it is the same thread").isEqualTo(threadId);
        // The thread Lina opened in R4 and the one Nour opens here are one row, from either side.
        assertThat(names(staffGet(lina, "COORDINATOR", "/coordinator/chat/threads"), "id")).contains(threadId);

        created(nour, "/management/chat/threads/" + threadId + "/messages", "{\"body\":\"Please review Maya's plan.\"}");
        assertThat(names(staffGet(lina, "COORDINATOR", "/coordinator/chat/threads/" + threadId + "/messages"), "body"))
                .containsExactly("Please review Maya's plan.");
        created(lina, "COORDINATOR", "/coordinator/chat/threads/" + threadId + "/messages", "{\"body\":\"Done.\"}");
        var hers = staffGet(nour, "MANAGERIAL", "/management/chat/threads");
        assertThat(rowWith(hers, "id", threadId).get("unread").asInt()).isEqualTo(1);
        staffPostStaff(nour, "MANAGERIAL", "/management/chat/threads/" + threadId + "/read");
        assertThat(rowWith(staffGet(nour, "MANAGERIAL", "/management/chat/threads"), "id", threadId).get("unread").asInt()).isZero();

        // Sami manages the other department: Lina is not a coordinator he is told about, and her thread is not his.
        mvc.perform(as(post("/management/chat/threads").contentType(MediaType.APPLICATION_JSON)
                .content("{\"coordinatorUserId\":\"" + lina + "\"}"), token(sami, "MANAGERIAL"))).andExpect(status().isNotFound());
        mvc.perform(as(get("/management/chat/threads/" + threadId + "/messages"), token(sami, "MANAGERIAL"))).andExpect(status().isNotFound());
        assertThat(names(staffGet(sami, "MANAGERIAL", "/management/chat/threads"), "id")).doesNotContain(threadId);
    }

    @Test @Order(6) void nour_and_the_admin_chat_and_support_sees_the_thread() throws Exception {
        assertThat(names(staffGet(nour, "MANAGERIAL", "/management/admins"), "userId")).contains(adminUserId);
        var thread = created(nour, "/management/chat/threads", "{\"adminUserId\":\"" + adminUserId + "\"}");
        String threadId = thread.get("id").asText();
        created(nour, "/management/chat/threads/" + threadId + "/messages", "{\"body\":\"The British department needs a second hall slot.\"}");

        // The Admin's support list carries it with the staff role, and she answers in her own thread.
        var support = json(mvc.perform(scoped(get("/admin/chat/threads"))).andExpect(status().isOk()).andReturn());
        assertThat(rowWith(support, "id", threadId).get("staffRole").asText()).isEqualTo("MANAGERIAL");
        json(mvc.perform(scoped(post("/admin/chat/threads/" + threadId + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Approved for Wednesdays.\"}"))).andExpect(status().isCreated()).andReturn());
        assertThat(names(staffGet(nour, "MANAGERIAL", "/management/chat/threads/" + threadId + "/messages"), "body"))
                .containsExactly("The British department needs a second hall slot.", "Approved for Wednesdays.");
        // Opened from the Admin's side it is the same row, and a manager of another school is 404.
        assertThat(json(mvc.perform(scoped(post("/admin/chat/threads").contentType(MediaType.APPLICATION_JSON)
                .content("{\"managerUserId\":\"" + nour + "\"}"))).andExpect(status().isCreated()).andReturn()).get("id").asText())
                .isEqualTo(threadId);
        mvc.perform(as(post("/admin/chat/threads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"managerUserId\":\"" + nour + "\"}"), adminToken).header(TenantContext.HEADER, OTHER_SCHOOL))
                .andExpect(status().isNotFound());
    }

    @Test @Order(7) void a_british_parent_lists_nour_and_opens_a_complaint_with_her() throws Exception {
        var options = parentGet(BRITISH_PARENT, "/children/" + childBritishA + "/managers");
        assertThat(names(options, "teacherId")).containsExactly(nour);
        assertThat(options.get(0).get("staffRole").asText()).isEqualTo("MANAGERIAL");
        assertThat(options.get(0).has("id")).as("no thread until she writes").isFalse();
        // A coordinator thread on her ordinary list carries the subjects the chooser showed (RM2 addendum): she writes
        // to Lina, and the row that appears beside the teachers' names the subject rather than leaving it null.
        mvc.perform(post("/children/" + childBritishA + "/chat/threads/" + lina + "/messages")
                        .header("Authorization", bearer(BRITISH_PARENT)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Is the ruler for maths or art?\"}")).andExpect(status().isCreated());
        assertThat(rowWith(parentGet(BRITISH_PARENT, "/children/" + childBritishA + "/chat/threads"), "staffRole", "COORDINATOR")
                .get("subject").asText()).isEqualTo("math");

        var opened = json(mvc.perform(post("/children/" + childBritishA + "/chat/threads/" + nour + "/messages")
                        .header("Authorization", bearer(BRITISH_PARENT)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"The maths coordinator has not replied for a week.\",\"topic\":\"complaint\"}"))
                .andExpect(status().isCreated()).andReturn());
        String threadId = opened.get("threadId").asText();
        var inbox = rowWith(staffGet(nour, "MANAGERIAL", "/management/chat/threads"), "id", threadId);
        assertThat(inbox.get("topic").asText()).isEqualTo("complaint");
        assertThat(inbox.get("childName").asText()).isEqualTo("Lila");
        assertThat(inbox.get("parentName").asText()).contains("@");
        created(nour, "/management/chat/threads/" + threadId + "/messages", "{\"body\":\"I will speak to her today.\"}");
        assertThat(names(parentGet(BRITISH_PARENT, "/children/" + childBritishA + "/chat/threads/" + nour + "/messages"), "body"))
                .containsExactly("The maths coordinator has not replied for a week.", "I will speak to her today.");

        // Sami is the other department's: the American parent's child has him, the British parent's does not reach him.
        assertThat(names(parentGet(AMERICAN_PARENT, "/children/" + childAmerican + "/managers"), "teacherId")).containsExactly(sami);
        mvc.perform(post("/children/" + childBritishA + "/chat/threads/" + sami + "/messages")
                        .header("Authorization", bearer(BRITISH_PARENT)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Hello?\"}")).andExpect(status().isNotFound());
        // And the thread is Nour's, not Sami's: not his row at all, so 404 rather than 403.
        mvc.perform(as(post("/management/chat/threads/" + threadId + "/read"), token(sami, "MANAGERIAL"))).andExpect(status().isNotFound());
    }

    @Test @Order(8) void a_teacher_holds_none_of_the_managers_keys() throws Exception {
        created(nour, "/management/broadcasts", "{\"kind\":\"announcement\",\"title\":\"Staff meeting\","
                + "\"bodyEn\":\"Thursday after school.\",\"audience\":[\"teachers\"]}");
        for (String path : List.of("/management/broadcasts", "/management/chat/threads", "/management/admins"))
            mvc.perform(as(get(path), token(maya, "TEACHER"))).andExpect(status().isForbidden());
        mvc.perform(as(post("/coordinator/broadcasts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"event\",\"bodyEn\":\"Mine\"}"), token(maya, "TEACHER"))).andExpect(status().isForbidden());
        // A teacher reads the feed and marks it read; she composes nothing.
        var hers = names(staffGet(maya, "TEACHER", "/me/broadcasts").get("items"), "id");
        for (String each : hers)
            mvc.perform(as(post("/me/broadcasts/" + each + "/read"), token(maya, "TEACHER"))).andExpect(status().isOk());
        assertThat(staffGet(maya, "TEACHER", "/me/broadcasts").get("unread").asInt()).isZero();
        String id = hers.get(0);
        // Rami is in the other department: the same row is not his to mark.
        mvc.perform(as(post("/me/broadcasts/" + id + "/read"), token(rami, "TEACHER"))).andExpect(status().isNotFound());
    }

    /**
     * The blocker the reviewer found: the bell and the feed have to be the same question. A row naming 1A British with
     * `audience:["coordinators"]` reaches Lina, who coordinates maths there, and not Huda, whose only section is 1B —
     * a track-only fan-out put the whole body in Huda's bell and linked her to a feed without the row in it.
     */
    @Test @Order(9) void a_section_named_row_reaches_only_the_coordinators_of_those_sections() throws Exception {
        var row = created(nour, "/management/broadcasts", "{\"kind\":\"announcement\",\"title\":\"1A parents evening\","
                + "\"bodyEn\":\"Tuesday, 1A British only.\",\"audience\":[\"coordinators\"],\"sectionIds\":[\"" + britishA + "\"]}");
        assertThat(ids(row.get("sectionIds"))).containsExactly(britishA);

        assertThat(names(staffGet(lina, "COORDINATOR", "/me/broadcasts").get("items"), "title")).contains("1A parents evening");
        assertThat(names(staffGet(lina, "COORDINATOR", "/me/notifications"), "title")).contains("1A parents evening");

        assertThat(names(staffGet(HUDA, "COORDINATOR", "/me/broadcasts").get("items"), "title")).doesNotContain("1A parents evening");
        assertThat(names(staffGet(HUDA, "COORDINATOR", "/me/notifications"), "title"))
                .as("the bell must never say what the feed will not show").doesNotContain("1A parents evening");
        // And the same row addressed to the department does reach her, because then it is her track that decides.
        created(nour, "/management/broadcasts", "{\"kind\":\"announcement\",\"title\":\"Whole department\","
                + "\"bodyEn\":\"Everyone, please read.\",\"audience\":[\"coordinators\"]}");
        assertThat(names(staffGet(HUDA, "COORDINATOR", "/me/broadcasts").get("items"), "title")).contains("Whole department");
        assertThat(names(staffGet(HUDA, "COORDINATOR", "/me/notifications"), "title")).contains("Whole department");
    }

    // ---------------------------------------------------------------- fixture helpers

    /** A staff account with no teacher profile behind it — `ManagementApiTest`'s own helper. */
    private void staff(String id, String displayName, String role) {
        var u = users.findById(id).orElseGet(UserEntity::new);
        u.setId(id); u.setSchoolId(SCHOOL); u.setEmail(id + "@seed.test"); u.setPasswordHash("x");
        u.setRole(role); u.setStatus("active"); u.setDisplayName(displayName);
        if (u.getCreatedAt() == null) u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        users.save(u);
    }

    /** One `staff_scopes` row: a coordinator's is a subject, and a null `curriculum` means both tracks (DR5). */
    private void scopeRow(String id, String userId, String subject, String curriculum) {
        var row = staffScopes.findById(id).orElseGet(quest.server.tenancy.Entities.StaffScopeEntity::new);
        row.setId(id); row.setSchoolId(SCHOOL); row.setUserId(userId);
        row.setSubject(subject); row.setCurriculum(curriculum);
        if (row.getCreatedAt() == null) row.setCreatedAt(Instant.now());
        staffScopes.save(row);
    }

    private String token(String userId, String role) { return jwt.issue(userId, userId + "@seed.test", role, SCHOOL).token(); }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String token) { return b.header("Authorization", "Bearer " + token); }

    private MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder b) { return as(b, adminToken).header(TenantContext.HEADER, SCHOOL); }

    private static String bearer(String parentUid) { return "Bearer fake-token-" + parentUid; }

    private JsonNode parentGet(String parentUid, String path) throws Exception {
        return json(mvc.perform(get(path).header("Authorization", bearer(parentUid))).andExpect(status().isOk()).andReturn());
    }

    private void staffPost(String parentUid, String path) throws Exception {
        mvc.perform(post(path).header("Authorization", bearer(parentUid))).andExpect(status().isOk());
    }

    private JsonNode staffGet(String userId, String role, String path) throws Exception {
        return json(mvc.perform(as(get(path), token(userId, role))).andExpect(status().isOk()).andReturn());
    }

    private JsonNode created(String userId, String path, String body) throws Exception { return created(userId, "MANAGERIAL", path, body); }

    private JsonNode created(String userId, String role, String path, String body) throws Exception {
        return json(mvc.perform(as(post(path).contentType(MediaType.APPLICATION_JSON).content(body), token(userId, role)))
                .andExpect(status().isCreated()).andReturn());
    }

    private void staffPostStaff(String userId, String role, String path) throws Exception {
        mvc.perform(as(post(path), token(userId, role))).andExpect(status().isOk());
    }

    private static List<String> names(JsonNode rows, String field) {
        var out = new ArrayList<String>();
        rows.forEach(row -> out.add(row.get(field) == null || row.get(field).isNull() ? null : row.get(field).asText()));
        return out;
    }

    private static List<String> ids(JsonNode rows) {
        var out = new ArrayList<String>();
        rows.forEach(row -> out.add(row.asText()));
        return out;
    }

    private static JsonNode rowWith(JsonNode rows, String field, String value) {
        for (var row : rows) if (row.get(field) != null && value.equals(row.get(field).asText())) return row;
        throw new AssertionError("no row with " + field + " = " + value + " in " + rows);
    }

    private void school(String id, String name, String code) {
        schools.findById(id).orElseGet(() -> {
            var s = new SchoolEntity();
            s.setId(id); s.setName(name); s.setCode(code);
            s.setCurriculumOptionsJson("[\"british\",\"american\"]"); s.setGradeOptionsJson("[1,2,3]");
            s.setStatus("active"); s.setCreatedAt(Instant.now());
            return schools.save(s);
        });
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

    /** A child of a real parent: created through the parent API so her `parents` row exists, then placed by hand. */
    private String child(String name, String classId, String curriculum, String parentUid, int grade) throws Exception {
        String id = json(mvc.perform(post("/children").header("Authorization", bearer(parentUid)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"avatarColor\":\"sun\",\"curriculum\":\"" + curriculum
                                + "\",\"grade\":" + grade + ",\"schoolCode\":\"BCAST1\"}"))
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
