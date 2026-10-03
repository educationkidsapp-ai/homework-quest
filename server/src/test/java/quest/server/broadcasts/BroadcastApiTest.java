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
import quest.server.push.ParentDeviceRepository;
import quest.server.push.PushProbe;
import quest.server.push.PushSender;
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
    /** MG1: a second British grade, so "her department" and "one grade of it" are two different audiences. */
    private static final String RITA = "bc-rita", BRITISH_2_PARENT = "bc-parent-uk-2";
    /** A teacher in <em>both</em> departments at different grades — the cross-product bug's only witness. */
    private static final String ZAID = "bc-zaid", HALA = "bc-hala";

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
    @Autowired quest.server.files.AttachmentRepository attachmentRows;
    @Autowired quest.server.files.FileStore store;
    @Autowired quest.server.files.UploadRetention retention;
    @Autowired PushSender pushes;
    @Autowired ParentDeviceRepository devices;

    private String lina, omar, nour, sami, maya, rami, britishA, britishB, americanA;
    /** A British science coordinator whose only section is 1B, so a row naming 1A is one she must not hear about. */
    private static final String HUDA = "bc-huda";
    /** A coordinator of maths and english in *both* tracks (`curriculum` NULL), which the seed has none of. */
    private static final String DANA = "bc-dana";
    private String childBritishA, childBritishB, childAmerican, adminToken, adminUserId;
    private String british2A, childBritish2;
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
        // DR5's both-tracks coordinator: `curriculum` NULL on every row, so she belongs to both departments and
        // reports to both managers. The acceptance seed has none, and this path is only reachable with one.
        staff(DANA, "Dana", "COORDINATOR");
        scopeRow("bc-scope-dana-math", DANA, "math", null);
        scopeRow("bc-scope-dana-english", DANA, "english", null);
        // MG1: grade 2 British, a teacher who is only in it and a child who sits in it. Grade 1 is everyone else's,
        // so a plan for grade 2 has an audience that no grade-1 assertion above can accidentally satisfy.
        staff(RITA, "Rita", "TEACHER");
        // `art`, which no coordinator in this fixture holds a scope row for: the new section widens the manager's
        // department (which is the point) without quietly widening Lina's or Huda's subject scope.
        british2A = ClassFixtures.section(classes, assignments, SCHOOL + ":british:2:art", SCHOOL, "british", 2, "art", RITA).getId();
        for (String key : List.of(FlagKeys.CHAT, FlagKeys.ANNOUNCEMENTS)) { flag(SCHOOL, key, true); flag(OTHER_SCHOOL, key, true); }
        childBritish2 = child("Yara", british2A, "british", BRITISH_2_PARENT, 2);
        // Zaid teaches grade 1 British and grade 3 American. His tracks are {british, american} and his grades
        // {1, 3}: matched independently that is four cells, two of which he teaches nobody in.
        staff(ZAID, "Zaid", "TEACHER");
        ClassFixtures.assign(assignments, classes.findById(britishA).orElseThrow(), "drama", ZAID);
        ClassFixtures.section(classes, assignments, SCHOOL + ":american:3:drama", SCHOOL, "american", 3, "drama", ZAID);
        // Grade 3 British exists and is somebody else's: the cell Zaid would be handed by a crossed track and grade.
        staff(HALA, "Hala", "TEACHER");
        ClassFixtures.section(classes, assignments, SCHOOL + ":british:3:drama", SCHOOL, "british", 3, "drama", HALA);
        adminToken = adminToken();
        adminUserId = users.findByEmailIgnoreCase("admin@test.local").orElseThrow().getId();
        bus.subscribe(heard::add);
    }

    @AfterAll void takeItBackOut() {
        attachmentRows.deleteAll(attachmentRows.findAll().stream().filter(a -> a.getSchoolId().startsWith("bc-")).toList());
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
        var plan = created(nour, "/management/broadcasts", "{\"kind\":\"weekly_plan\",\"weekStart\":\"" + week + "\",\"grade\":1,"
                + "\"title\":\"Week of subtraction\",\"bodyEn\":\"Subtraction all week; swimming on Thursday.\","
                + "\"bodyAr\":\"الطرح\",\"audience\":[\"parents\",\"teachers\",\"coordinators\"],"
                + "\"attachmentId\":\"" + imageId(nour, "MANAGERIAL") + "\"}");
        assertThat(plan.get("kind").asText()).isEqualTo("weekly_plan");
        assertThat(plan.get("weekStart").asText()).isEqualTo(week.toString());
        assertThat(plan.get("curriculum").asText()).isEqualTo("british");
        assertThat(plan.get("authorRole").asText()).isEqualTo("MANAGERIAL");
        // MH1: the attachment is a reference now, and `url` is the route the recipient actually fetches.
        assertThat(plan.get("attachment").get("type").asText()).isEqualTo("image/png");
        assertThat(plan.get("attachment").get("url").asText())
                .isEqualTo("/media/attachments/" + plan.get("attachment").get("id").asText());
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

        var again = created(nour, "/management/broadcasts", "{\"kind\":\"weekly_plan\",\"weekStart\":\"" + week + "\",\"grade\":1,"
                + "\"title\":\"Week of subtraction (v2)\",\"bodyEn\":\"Swimming moved to Wednesday.\","
                + "\"attachmentId\":\"" + imageId(nour, "MANAGERIAL") + "\"}");
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
        // A weekly plan names one grade of one department (MH1), so sections instead of a grade — here spanning both
        // tracks, which would name no department at all and match every department's plan for that week — is refused.
        mvc.perform(as(post("/management/broadcasts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"weekly_plan\",\"weekStart\":\"" + week + "\",\"bodyEn\":\"Both tracks\","
                                + "\"audience\":[\"parents\"],\"sectionIds\":[\"" + britishA + "\",\"" + americanA + "\"]}"),
                adminToken).header(TenantContext.HEADER, SCHOOL)).andExpect(status().isBadRequest());
        // The American manager's own plan leaves the British one where it is.
        created(sami, "/management/broadcasts", "{\"kind\":\"weekly_plan\",\"weekStart\":\"" + week + "\",\"grade\":1,"
                + "\"title\":\"American week\",\"bodyEn\":\"Spelling bee.\","
                + "\"attachmentId\":\"" + imageId(sami, "MANAGERIAL") + "\"}");
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

    /**
     * DR5 reads a `curriculum NULL` scope row as both tracks, so a coordinator holding only such rows belongs to both
     * departments: each manager's department-wide row reaches her exactly once, and her feed shows both.
     */
    @Test @Order(10) void a_both_tracks_coordinator_hears_from_both_departments_once() throws Exception {
        created(nour, "/management/broadcasts", "{\"kind\":\"announcement\",\"title\":\"British notice\","
                + "\"bodyEn\":\"British department.\",\"audience\":[\"coordinators\"]}");
        created(sami, "/management/broadcasts", "{\"kind\":\"announcement\",\"title\":\"American notice\","
                + "\"bodyEn\":\"American department.\",\"audience\":[\"coordinators\"]}");

        assertThat(names(staffGet(DANA, "COORDINATOR", "/me/broadcasts").get("items"), "title"))
                .contains("British notice", "American notice");
        var bells = names(staffGet(DANA, "COORDINATOR", "/me/notifications"), "title");
        assertThat(bells.stream().filter("British notice"::equals).count()).as("one bell entry, not one per track").isOne();
        assertThat(bells.stream().filter("American notice"::equals).count()).isOne();
        // Lina coordinates British only: the American department's notice is not hers, on either half.
        assertThat(names(staffGet(lina, "COORDINATOR", "/me/broadcasts").get("items"), "title")).doesNotContain("American notice");
        assertThat(names(staffGet(lina, "COORDINATOR", "/me/notifications"), "title")).doesNotContain("American notice");
    }

    // ---------------------------------------------------------------- MG1: the plan per grade, and the archive

    /**
     * The owner's item 3: "the manager is who adds the weekly plan for all grades" — which is a plan <em>per</em>
     * grade as well as one for all of them. The grade narrows the same department-wide row, so the audience is the
     * sections of that grade and the read-time predicate is the one every feed and the fan-out already use.
     */
    @Test @Order(11) void a_grade_plan_reaches_that_grade_and_the_all_grades_plan_reaches_the_department() throws Exception {
        var plan = created(nour, "/management/broadcasts", "{\"kind\":\"weekly_plan\",\"weekStart\":\"" + week + "\",\"grade\":2,"
                + "\"title\":\"Grade 2 week\",\"bodyEn\":\"Long division all week.\","
                + "\"attachmentId\":\"" + imageId(nour, "MANAGERIAL") + "\"}");
        assertThat(plan.get("grade").asInt()).isEqualTo(2);
        assertThat(plan.get("curriculum").asText()).isEqualTo("british");

        // Rita teaches grade 2 British and nothing else: hers, on the feed and on the bell.
        assertThat(names(staffGet(RITA, "TEACHER", "/me/broadcasts").get("items"), "title")).contains("Grade 2 week");
        assertThat(names(staffGet(RITA, "TEACHER", "/me/notifications"), "title")).contains("Grade 2 week");
        // Maya teaches grade 1 British: the same department, the wrong grade — neither half, or the bell would say
        // what the feed will not show.
        assertThat(names(staffGet(maya, "TEACHER", "/me/broadcasts").get("items"), "title")).doesNotContain("Grade 2 week");
        assertThat(names(staffGet(maya, "TEACHER", "/me/notifications"), "title")).doesNotContain("Grade 2 week");
        // MH1: there is no all-grades plan any more, so grade 1's week is Maya's and not Rita's.
        assertThat(names(staffGet(RITA, "TEACHER", "/me/broadcasts").get("items"), "title")).doesNotContain("Week of subtraction (v2)");
        assertThat(names(staffGet(maya, "TEACHER", "/me/broadcasts").get("items"), "title")).contains("Week of subtraction (v2)");

        assertThat(names(parentGet(BRITISH_2_PARENT, "/children/" + childBritish2 + "/broadcasts").get("items"), "title"))
                .contains("Grade 2 week");
        assertThat(names(parentGet(BRITISH_PARENT, "/children/" + childBritishA + "/broadcasts").get("items"), "title"))
                .doesNotContain("Grade 2 week");
        // The two rows coexist: a grade plan does not replace the department's plan for its week.
        assertThat(names(staffGet(nour, "MANAGERIAL", "/management/broadcasts"), "title"))
                .contains("Grade 2 week", "Week of subtraction (v2)");

        String image = imageId(nour, "MANAGERIAL");
        for (String body : List.of(
                "{\"kind\":\"weekly_plan\",\"weekStart\":\"" + week + "\",\"grade\":9,\"bodyEn\":\"Nobody teaches it\",\"attachmentId\":\"" + image + "\"}",
                "{\"kind\":\"weekly_plan\",\"weekStart\":\"" + week + "\",\"grade\":1,\"bodyEn\":\"Two ways of saying it\","
                        + "\"sectionIds\":[\"" + britishA + "\"],\"attachmentId\":\"" + image + "\"}",
                // MH1: the plan is one grade's week as an image, so neither half may be left out — and an id that is
                // not this author's own upload is the same 400 as one that never existed.
                "{\"kind\":\"weekly_plan\",\"weekStart\":\"" + week + "\",\"bodyEn\":\"Every grade\",\"attachmentId\":\"" + image + "\"}",
                "{\"kind\":\"weekly_plan\",\"weekStart\":\"" + week + "\",\"grade\":1,\"bodyEn\":\"No image\"}",
                "{\"kind\":\"weekly_plan\",\"weekStart\":\"" + week + "\",\"grade\":1,\"attachmentId\":\"" + imageId(sami, "MANAGERIAL") + "\"}"))
            mvc.perform(as(post("/management/broadcasts").contentType(MediaType.APPLICATION_JSON).content(body), token(nour, "MANAGERIAL")))
                    .andExpect(status().isBadRequest());
    }

    /** The replace key is (school, week, department, grade): a re-post takes out its own row and no other. */
    @Test @Order(12) void re_posting_a_grade_plan_replaces_that_grades_plan_only() throws Exception {
        created(nour, "/management/broadcasts", "{\"kind\":\"weekly_plan\",\"weekStart\":\"" + week + "\",\"grade\":2,"
                + "\"title\":\"Grade 2 week (v2)\",\"bodyEn\":\"Long division, and a test on Thursday.\","
                + "\"attachmentId\":\"" + imageId(nour, "MANAGERIAL") + "\"}");
        assertThat(names(staffGet(RITA, "TEACHER", "/me/broadcasts").get("items"), "title"))
                .contains("Grade 2 week (v2)").doesNotContain("Grade 2 week");
        assertThat(names(staffGet(RITA, "TEACHER", "/me/notifications"), "title")).doesNotContain("Grade 2 week");
    }

    /**
     * The owner's item 4: "a feature to see all weekly plans". The archive is the feed read backwards — past weeks
     * and expired rows included, which is the one thing it must do that `GET /me/broadcasts` must not.
     */
    @Test @Order(14) void the_archive_holds_past_weeks_and_stops_at_the_department() throws Exception {
        var old = created(nour, "/management/broadcasts", "{\"kind\":\"weekly_plan\",\"weekStart\":\"" + week.minusWeeks(3) + "\",\"grade\":1,"
                + "\"title\":\"Three weeks ago\",\"bodyEn\":\"Shapes.\","
                + "\"attachmentId\":\"" + imageId(nour, "MANAGERIAL") + "\"}");
        // Expired by hand: there is no way to post a row that is already over, and "the feed hides it, the archive
        // does not" is exactly the difference between the two screens.
        broadcastRows.findById(old.get("id").asText()).ifPresent(row -> { row.setExpiresAt(clock.instant().minusSeconds(60)); broadcastRows.save(row); });
        assertThat(names(staffGet(maya, "TEACHER", "/me/broadcasts").get("items"), "title")).doesNotContain("Three weeks ago");
        assertThat(planTitles(staffGet(maya, "TEACHER", "/me/weekly-plans"))).contains("Three weeks ago");

        var hers = staffGet(nour, "MANAGERIAL", "/management/weekly-plans");
        assertThat(hers.get("weeks").get(0).get("weekStart").asText()).as("newest week first").isEqualTo(week.toString());
        assertThat(planTitles(hers)).contains("Three weeks ago", "Week of subtraction (v2)", "Grade 2 week (v2)")
                .as("the other department's week is not hers").doesNotContain("American week");
        // By grade inside a week, and her own archive counts the readers.
        var thisWeek = hers.get("weeks").get(0).get("items");
        assertThat(thisWeek.get(0).get("plan").get("grade").asInt()).isEqualTo(1);
        assertThat(thisWeek.get(0).get("readBy").asInt()).isGreaterThanOrEqualTo(0);
        // MH1: a parent has no bell, so the archive carries her own unread count — this is her notification.
        assertThat(parentGet(BRITISH_2_PARENT, "/children/" + childBritish2 + "/weekly-plans").get("unread").asInt())
                .isGreaterThan(0);
        assertThat(planTitles(staffGet(nour, "MANAGERIAL", "/management/weekly-plans?grade=2"))).containsExactly("Grade 2 week (v2)");

        assertThat(planTitles(staffGet(sami, "MANAGERIAL", "/management/weekly-plans")))
                .contains("American week").doesNotContain("Week of subtraction (v2)");
        // A teacher's archive is her own audience, grade included; a parent's is her child's section.
        assertThat(planTitles(staffGet(maya, "TEACHER", "/me/weekly-plans"))).doesNotContain("Grade 2 week (v2)");
        assertThat(planTitles(staffGet(RITA, "TEACHER", "/me/weekly-plans"))).contains("Grade 2 week (v2)");
        assertThat(planTitles(parentGet(BRITISH_2_PARENT, "/children/" + childBritish2 + "/weekly-plans"))).contains("Grade 2 week (v2)");
        assertThat(planTitles(parentGet(AMERICAN_PARENT, "/children/" + childAmerican + "/weekly-plans"))).doesNotContain("Grade 2 week (v2)");
        // A window that is not one, and one that is too wide, are both refused rather than silently narrowed.
        for (String query : List.of("?from=" + week + "&to=" + week.minusWeeks(1), "?from=" + week.minusWeeks(300) + "&to=" + week))
            mvc.perform(as(get("/management/weekly-plans" + query), token(nour, "MANAGERIAL"))).andExpect(status().isBadRequest());
    }

    /**
     * The reviewer's blocker: <strong>a track and a grade are one key, not two.</strong> Zaid teaches grade 1 British
     * and grade 3 American, so a set of tracks crossed with a set of grades offered him four cells — and handed him
     * the British department's grade 3 plan, a week of work for children he has never taught, on the feed and in the
     * bell alike. The pair he actually sits in is what decides it, in the one predicate the fan-out, the feeds and
     * the archives all share.
     */
    @Test @Order(13) void a_teacher_in_two_departments_gets_neither_departments_other_grade() throws Exception {
        created(nour, "/management/broadcasts", "{\"kind\":\"weekly_plan\",\"weekStart\":\"" + week + "\",\"grade\":3,"
                + "\"title\":\"British grade 3\",\"bodyEn\":\"Fractions.\",\"attachmentId\":\"" + imageId(nour, "MANAGERIAL") + "\"}");
        created(sami, "/management/broadcasts", "{\"kind\":\"weekly_plan\",\"weekStart\":\"" + week + "\",\"grade\":3,"
                + "\"title\":\"American grade 3\",\"bodyEn\":\"Spelling.\",\"attachmentId\":\"" + imageId(sami, "MANAGERIAL") + "\"}");
        created(sami, "/management/broadcasts", "{\"kind\":\"weekly_plan\",\"weekStart\":\"" + week.minusWeeks(1) + "\",\"grade\":1,"
                + "\"title\":\"American grade 1\",\"bodyEn\":\"Counting.\",\"attachmentId\":\"" + imageId(sami, "MANAGERIAL") + "\"}");

        var feed = names(staffGet(ZAID, "TEACHER", "/me/broadcasts").get("items"), "title");
        assertThat(feed).as("the two cells he actually teaches in").contains("American grade 3")
                .as("british|3 and american|1 are cells he holds no section in")
                .doesNotContain("British grade 3", "American grade 1");
        // Grade 1 British is his too, through the department-wide plan and a grade plan alike.
        assertThat(feed).contains("Week of subtraction (v2)");
        var bells = names(staffGet(ZAID, "TEACHER", "/me/notifications"), "title");
        assertThat(bells).as("the bell must never say what the feed will not show")
                .doesNotContain("British grade 3", "American grade 1");
        // The archive asks the same question, so the three screens cannot drift apart.
        var archive = planTitles(staffGet(ZAID, "TEACHER", "/me/weekly-plans"));
        assertThat(archive).contains("American grade 3").doesNotContain("British grade 3", "American grade 1");
        // And each row still reaches the teacher whose grade it is.
        assertThat(names(staffGet(RITA, "TEACHER", "/me/broadcasts").get("items"), "title"))
                .as("Rita is british|2") .doesNotContain("British grade 3");
        assertThat(names(staffGet(HALA, "TEACHER", "/me/broadcasts").get("items"), "title"))
                .as("Hala is british|3, so it is hers").contains("British grade 3").doesNotContain("American grade 3");
    }

    // ---------------------------------------------------------------- MG1: manager ↔ teacher chat

    /**
     * The owner's item 6, the half RM2 left out: the manager and her teachers. The teacher holds the `teacher_id`
     * side and the manager `peer_user_id`, the rule R4 set for the coordinator and RM2 for the admin, so whichever
     * side opens it there is one row and `findForStaff` finds it for both.
     */
    @Test @Order(15) void nour_and_maya_share_one_thread_and_sami_reaches_neither_end() throws Exception {
        int before = (int) heard.stream().filter(e -> ChatEvent.MESSAGE.equals(e.kind())).count();
        var thread = created(nour, "/management/chat/threads", "{\"teacherUserId\":\"" + maya + "\"}");
        String threadId = thread.get("id").asText();
        assertThat(thread.get("staffRole").asText()).isEqualTo("MANAGERIAL");
        assertThat(thread.get("teacherId").asText()).as("the row names the other person").isEqualTo(maya);
        created(nour, "/management/chat/threads/" + threadId + "/messages", "{\"body\":\"Your grade 1 plan, please.\"}");

        assertThat(names(staffGet(maya, "TEACHER", "/teacher/managers"), "userId")).containsExactly(nour);
        assertThat(names(staffGet(maya, "TEACHER", "/teacher/chat/staff-threads"), "id")).containsExactly(threadId);
        assertThat(names(staffGet(maya, "TEACHER", "/teacher/chat/staff-threads/" + threadId + "/messages"), "body"))
                .containsExactly("Your grade 1 plan, please.");
        created(maya, "TEACHER", "/teacher/chat/staff-threads/" + threadId + "/messages", "{\"body\":\"Sent it this morning.\"}");
        assertThat(names(staffGet(nour, "MANAGERIAL", "/management/chat/threads/" + threadId + "/messages"), "body"))
                .containsExactly("Your grade 1 plan, please.", "Sent it this morning.");
        // Opened from her side it is the same row, and the read receipt clears her own badge.
        assertThat(created(maya, "TEACHER", "/teacher/chat/staff-threads", "{\"managerUserId\":\"" + nour + "\"}").get("id").asText())
                .isEqualTo(threadId);
        staffPostStaff(maya, "TEACHER", "/teacher/chat/staff-threads/" + threadId + "/read");
        assertThat(rowWith(staffGet(maya, "TEACHER", "/teacher/chat/staff-threads"), "id", threadId).get("unread").asInt()).isZero();
        // Both messages crossed the bus naming this thread and this school: the sockets carry it either way.
        var frames = heard.stream().filter(e -> ChatEvent.MESSAGE.equals(e.kind()) && threadId.equals(e.threadId())).toList();
        assertThat(frames).hasSize(2).allSatisfy(e -> { assertThat(e.schoolId()).isEqualTo(SCHOOL); assertThat(e.peerUserId()).isEqualTo(nour); });
        assertThat(heard.stream().filter(e -> ChatEvent.MESSAGE.equals(e.kind())).count()).isEqualTo(before + 2L);

        // Sami manages the other department: Maya is not his teacher and he is not her manager, from either side.
        mvc.perform(as(post("/management/chat/threads").contentType(MediaType.APPLICATION_JSON)
                .content("{\"teacherUserId\":\"" + maya + "\"}"), token(sami, "MANAGERIAL"))).andExpect(status().isNotFound());
        mvc.perform(as(post("/teacher/chat/staff-threads").contentType(MediaType.APPLICATION_JSON)
                .content("{\"managerUserId\":\"" + sami + "\"}"), token(maya, "TEACHER"))).andExpect(status().isNotFound());
        // And a thread of hers is not Rami's to read, whatever id he sends.
        mvc.perform(as(get("/teacher/chat/staff-threads/" + threadId + "/messages"), token(rami, "TEACHER"))).andExpect(status().isNotFound());
    }

    /**
     * The owner's item 7: a bell entry that cannot be opened is a bell entry nobody uses. Every kind carries a link
     * on the <em>recipient's</em> own dashboard — the area is her role's, never the author's.
     */
    @Test @Order(16) void every_notification_kind_links_into_the_recipients_own_area() throws Exception {
        String id = created(nour, "/management/broadcasts", "{\"kind\":\"announcement\",\"title\":\"Link check\","
                + "\"bodyEn\":\"Open me.\",\"audience\":[\"teachers\",\"coordinators\"]}").get("id").asText();
        assertThat(rowWith(staffGet(maya, "TEACHER", "/me/notifications"), "title", "Link check").get("link").asText())
                .isEqualTo("/teacher/broadcasts?open=" + id);
        assertThat(rowWith(staffGet(lina, "COORDINATOR", "/me/notifications"), "title", "Link check").get("link").asText())
                .isEqualTo("/coordinator/broadcasts?open=" + id);

        // `teacher.message` now opens the thread it was appended to, so the manager can answer rather than only read.
        String threadId = names(staffGet(maya, "TEACHER", "/teacher/chat/staff-threads"), "id").get(0);
        mvc.perform(as(post("/teacher/messages/coordinator").contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"The projector in 1A is broken.\"}"), token(maya, "TEACHER"))).andExpect(status().isOk());
        assertThat(rowWith(staffGet(nour, "MANAGERIAL", "/me/notifications"), "kind", "teacher.message").get("link").asText())
                .isEqualTo("/management/messages?thread=" + threadId);
        assertThat(names(staffGet(nour, "MANAGERIAL", "/management/chat/threads/" + threadId + "/messages"), "body"))
                .contains("The projector in 1A is broken.");
        // Sami manages no department of hers, so there is no thread of his to open — the screen, without one.
        assertThat(rowWith(staffGet(sami, "MANAGERIAL", "/me/notifications"), "kind", "teacher.message").get("link").asText())
                .isEqualTo("/management/messages");
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

    /** MH1: the staff seed files gained an optional `phone`, and the acceptance numbers land on the accounts. */
    @Test @Order(20) void the_acceptance_seed_carries_the_staff_phone_numbers() {
        assertThat(users.findById(maya).orElseThrow().getPhone()).isEqualTo("+971501000101");
        assertThat(users.findById(nour).orElseThrow().getPhone()).isEqualTo("+971501000201");
        assertThat(users.findById(lina).orElseThrow().getPhone()).isEqualTo("+971501000301");
    }

    // ---------------------------------------------------------------- MH1: the image, and the parent thread she opens

    /**
     * The owner's item 6 in full: "no title/message — just the week and an uploaded image". The server writes the
     * sentence she did not type, so `broadcasts.body_en` is never null and the bell still has a headline, and the
     * audience is the whole grade whatever she ticked — a plan has one audience, and the owner named it.
     */
    @Test @Order(17) void a_plan_is_a_week_a_grade_and_an_image_and_needs_nothing_else() throws Exception {
        var bare = created(nour, "/management/broadcasts", "{\"kind\":\"weekly_plan\",\"weekStart\":\"" + week.minusWeeks(2) + "\","
                + "\"grade\":2,\"audience\":[\"parents\"],\"attachmentId\":\"" + imageId(nour, "MANAGERIAL") + "\"}");
        assertThat(bare.get("title").asText()).isEqualTo("Weekly plan · Grade 2 · week of " + week.minusWeeks(2));
        assertThat(bare.get("bodyEn").asText()).isEqualTo(bare.get("title").asText());
        assertThat(ids(bare.get("audience"))).containsExactly("parents", "teachers", "coordinators");
        assertThat(planTitles(staffGet(RITA, "TEACHER", "/me/weekly-plans"))).contains(bare.get("title").asText());
    }

    /**
     * MH1: an attachment is readable exactly by whoever may read a broadcast that carries it — the one predicate the
     * feeds, the archives and the bell share — plus its uploader, who has to see it in the composer before she posts.
     * Everything else is 404 and never 403, `MediaAccess`'s rule: an id that exists in another department must look
     * exactly like an id that never existed.
     */
    @Test @Order(18) void the_plans_image_reaches_that_grade_and_nobody_else() throws Exception {
        String image = imageId(nour, "MANAGERIAL");
        // Hers before it is attached to anything; nobody else's, not even the other manager of the same school.
        mvc.perform(as(get("/media/attachments/" + image), token(nour, "MANAGERIAL"))).andExpect(status().isOk());
        mvc.perform(as(get("/media/attachments/" + image), token(sami, "MANAGERIAL"))).andExpect(status().isNotFound());
        mvc.perform(get("/media/attachments/" + image).header("Authorization", bearer(BRITISH_PARENT))).andExpect(status().isNotFound());

        created(nour, "/management/broadcasts", "{\"kind\":\"weekly_plan\",\"weekStart\":\"" + week.minusWeeks(4) + "\","
                + "\"grade\":1,\"title\":\"Grade 1, four weeks back\",\"attachmentId\":\"" + image + "\"}");
        // A parent and a teacher of grade 1 British may read it; the American side and the other grade may not.
        mvc.perform(get("/media/attachments/" + image).header("Authorization", bearer(BRITISH_PARENT)))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Content-Type", "image/png"));
        mvc.perform(as(get("/media/attachments/" + image), token(maya, "TEACHER"))).andExpect(status().isOk());
        mvc.perform(as(get("/media/attachments/" + image), token(RITA, "TEACHER"))).andExpect(status().isNotFound());
        mvc.perform(get("/media/attachments/" + image).header("Authorization", bearer(AMERICAN_PARENT))).andExpect(status().isNotFound());
        mvc.perform(get("/media/attachments/" + java.util.UUID.randomUUID()).header("Authorization", bearer(BRITISH_PARENT)))
                .andExpect(status().isNotFound());
        // Only an image or a PDF, told by its own bytes: text announced as a PNG is refused.
        var text = new org.springframework.mock.web.MockMultipartFile("file", "plan.png", "image/png", "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/media/attachments").file(text),
                token(nour, "MANAGERIAL"))).andExpect(status().isBadRequest());
    }

    /** S1 (owner's list of 2026-10-01): a weekly plan may be a PDF — sniffed, served inline with its own type, and a plan's alone. */
    @Test @Order(19) void a_weekly_plan_may_be_a_pdf_and_an_announcement_may_not() throws Exception {
        // Announced as a PNG on purpose: the stored type is what the bytes are.
        var pdf = new org.springframework.mock.web.MockMultipartFile("file", "Week 5.PDF", "image/png", "%PDF-1.7\n%%EOF".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var uploaded = json(mvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/media/attachments").file(pdf),
                token(nour, "MANAGERIAL"))).andExpect(status().isCreated()).andReturn());
        assertThat(uploaded.get("type").asText()).isEqualTo("application/pdf");
        String id = uploaded.get("id").asText();

        mvc.perform(as(post("/management/broadcasts").contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"announcement\",\"bodyEn\":\"Read this\","
                + "\"audience\":[\"parents\"],\"attachmentId\":\"" + id + "\"}"), token(nour, "MANAGERIAL"))).andExpect(status().isBadRequest());

        var plan = created(nour, "/management/broadcasts", "{\"kind\":\"weekly_plan\",\"weekStart\":\"" + week.minusWeeks(5) + "\","
                + "\"grade\":1,\"attachmentId\":\"" + id + "\"}");
        assertThat(plan.get("attachment").get("type").asText()).as("how a client picks a PDF link over an image view").isEqualTo("application/pdf");
        assertThat(plan.get("attachment").get("name").asText()).isEqualTo("week 5.pdf");
        mvc.perform(get("/media/attachments/" + id).header("Authorization", bearer(BRITISH_PARENT))).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Type", "application/pdf"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Disposition", "inline; filename=\"week5.pdf\""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get("/media/attachments/" + id).header("Authorization", bearer(AMERICAN_PARENT))).andExpect(status().isNotFound());
    }

    /**
     * The reviewer's blocker: `replacePlan` deletes the superseded plan, so a plan re-posted every week left one
     * orphaned image per post — row and bytes — that nothing swept and only its uploader could still read. Both halves
     * are asserted here: the replacement reclaims the old image <em>at once</em>, and `UploadRetention.sweep` is the
     * backstop for an upload nobody ever attached.
     */
    @Test @Order(21) void replacing_a_plan_reclaims_the_image_it_replaced() throws Exception {
        String first = imageId(nour, "MANAGERIAL");
        var week1 = week.minusWeeks(6);
        created(nour, "/management/broadcasts", "{\"kind\":\"weekly_plan\",\"weekStart\":\"" + week1 + "\",\"grade\":1,"
                + "\"title\":\"Six weeks back\",\"attachmentId\":\"" + first + "\"}");
        String path = attachmentRows.findById(first).orElseThrow().getStoragePath();
        assertThat(store.get(path)).as("the bytes are there while the plan is").isPresent();

        String second = imageId(nour, "MANAGERIAL");
        created(nour, "/management/broadcasts", "{\"kind\":\"weekly_plan\",\"weekStart\":\"" + week1 + "\",\"grade\":1,"
                + "\"title\":\"Six weeks back (v2)\",\"attachmentId\":\"" + second + "\"}");
        assertThat(attachmentRows.findById(first)).as("the superseded plan's row goes with it").isEmpty();
        assertThat(store.get(path)).as("and so do its bytes").isEmpty();
        assertThat(attachmentRows.findById(second)).as("the replacement's own image stays").isPresent();
        mvc.perform(as(get("/media/attachments/" + first), token(nour, "MANAGERIAL"))).andExpect(status().isNotFound());

        // Re-posting the *same* image must not delete the bytes the new row points at.
        created(nour, "/management/broadcasts", "{\"kind\":\"weekly_plan\",\"weekStart\":\"" + week1 + "\",\"grade\":1,"
                + "\"title\":\"Six weeks back (v3)\",\"attachmentId\":\"" + second + "\"}");
        assertThat(attachmentRows.findById(second)).isPresent();
        mvc.perform(as(get("/media/attachments/" + second), token(nour, "MANAGERIAL"))).andExpect(status().isOk());
    }

    /**
     * The sweep's own half: an upload nobody attached is an orphan, and one a broadcast points at never is. Age is the
     * other guard — a fresh upload is what a composer is still holding, so the sweep leaves it alone.
     */
    @Test @Order(22) void the_sweep_drops_an_unattached_upload_and_keeps_an_attached_one() throws Exception {
        String orphan = imageId(nour, "MANAGERIAL"), live = imageId(nour, "MANAGERIAL");
        created(nour, "/management/broadcasts", "{\"kind\":\"weekly_plan\",\"weekStart\":\"" + week.minusWeeks(7) + "\","
                + "\"grade\":1,\"title\":\"Seven weeks back\",\"attachmentId\":\"" + live + "\"}");
        String orphanPath = attachmentRows.findById(orphan).orElseThrow().getStoragePath();

        retention.sweep();
        assertThat(attachmentRows.findById(orphan)).as("too young to be swept — she may still be composing").isPresent();

        // Aged by hand: there is no way to wait a day in a test, and the grace period is the rule being proved.
        attachmentRows.findById(orphan).ifPresent(a -> {
            a.setCreatedAt(clock.instant().minusSeconds(60 * 60 * 25)); attachmentRows.save(a);
        });
        retention.sweep();
        assertThat(attachmentRows.findById(orphan)).as("nothing references it and it is a day old").isEmpty();
        assertThat(store.get(orphanPath)).isEmpty();
        assertThat(attachmentRows.findById(live)).as("a broadcast points at this one, whatever its age").isPresent();
    }

    /**
     * The owner's item 5: the manager writes to a parent from the Children directory. Until MH1 only the parent could
     * open that thread, so there was nobody for her to press. It is the same row either side creates — `staff_role`
     * `MANAGERIAL`, `child_id` the child — so it lands in the parent's app list beside her coordinator threads.
     */
    @Test @Order(19) void the_manager_opens_a_thread_with_a_childs_parent() throws Exception {
        var thread = created(nour, "/management/chat/threads", "{\"childId\":\"" + childBritishA + "\"}");
        assertThat(thread.get("staffRole").asText()).isEqualTo("MANAGERIAL");
        assertThat(thread.get("childId").asText()).isEqualTo(childBritishA);
        assertThat(created(nour, "/management/chat/threads", "{\"childId\":\"" + childBritishA + "\"}").get("id").asText())
                .as("asked for twice, it is the same row").isEqualTo(thread.get("id").asText());
        assertThat(names(staffGet(nour, "MANAGERIAL", "/management/chat/threads"), "childId")).contains(childBritishA);
        // The parent sees the same manager on her own list, which is where she answers from.
        assertThat(names(parentGet(BRITISH_PARENT, "/children/" + childBritishA + "/managers"), "teacherId")).contains(nour);

        // A child of the other department is 403 — `ManagerScope.requireChild`'s answer everywhere under
        // `/management` — and a child nobody has registered for is 404 `no_parent`.
        mvc.perform(as(post("/management/chat/threads").contentType(MediaType.APPLICATION_JSON)
                .content("{\"childId\":\"" + childAmerican + "\"}"), token(nour, "MANAGERIAL"))).andExpect(status().isForbidden());
        String orphan = rosterChild("Nabil", britishA);
        mvc.perform(as(post("/management/chat/threads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"childId\":\"" + orphan + "\"}"), token(nour, "MANAGERIAL")))
                .andExpect(status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("no_parent"));
    }

    /** A roster row nobody has registered for: `parent_id` null, which is what MH1's `no_parent` refusal is about. */
    private String rosterChild(String name, String classId) {
        var row = new quest.server.children.Entities.ChildEntity();
        row.setId("bc-roster-" + name.toLowerCase(Locale.ROOT)); row.setSchoolId(SCHOOL); row.setName(name);
        row.setAvatarColor("sun"); row.setCurriculum("british"); row.setGrade(1); row.setClassId(classId);
        row.setCreatedAt(Instant.now());
        return childRows.save(row).getId();
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

    /** The smallest thing `AttachmentService` will accept as a PNG: the eight-byte signature and a little after it. */
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

    /**
     * MH1: a weekly plan <em>is</em> an image, so every plan below uploads one first — and as its own author, because
     * a composer may only attach her own upload.
     */
    private String imageId(String userId, String role) throws Exception {
        var file = new org.springframework.mock.web.MockMultipartFile("file", "plan.png", "image/png", PNG);
        return json(mvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart("/media/attachments").file(file), token(userId, role)))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
    }

    /**
     * B4: a broadcast is pushed to the parents whose child's feed shows it — the grade's, here — in the phone's language,
     * and to nobody else: not another grade, not the other department, not a parent of the same grade at another school.
     */
    @Test @Order(30) void a_grade_announcement_is_pushed_to_that_grades_parents_only() throws Exception {
        var foreign = ClassFixtures.section(classes, assignments, OTHER_SCHOOL + ":british:2:art", OTHER_SCHOOL, "british", 2, "art", HALA);
        child("Zed", foreign.getId(), "british", "bc-parent-foreign", 2, "BCAST2");
        String grade2 = PushProbe.token("bc-grade2"), grade1 = PushProbe.token("bc-grade1"), american = PushProbe.token("bc-us"), abroad = PushProbe.token("bc-abroad");
        PushProbe.register(mvc, bearer(BRITISH_2_PARENT), grade2, "ar");
        PushProbe.register(mvc, bearer(BRITISH_PARENT), grade1, null);
        PushProbe.register(mvc, bearer(AMERICAN_PARENT), american, null);
        PushProbe.register(mvc, bearer("bc-parent-foreign"), abroad, null);

        var posted = created(nour, "/management/broadcasts", "{\"kind\":\"announcement\",\"grade\":2,\"bodyEn\":\"Museum trip on Monday.\","
                + "\"bodyAr\":\"رحلة إلى المتحف يوم الاثنين.\",\"audience\":[\"parents\"]}");
        String id = posted.get("id").asText();

        var pushed = PushProbe.await(pushes, grade2, 1);
        assertThat(pushed).hasSize(1);
        var push = pushed.get(0).message();
        assertThat(push.getKind()).isEqualTo(quest.api.dto.NotificationKind.BROADCAST_POSTED);
        assertThat(push.getTitle()).as("no title typed: the kind, in Arabic").isEqualTo("إعلان");
        assertThat(push.getBody()).isEqualTo("رحلة إلى المتحف يوم الاثنين.");
        var row = rowWith(parentGet(BRITISH_2_PARENT, "/me/notifications"), "kind", "broadcast.posted");
        assertThat(push.getNotificationId()).as("her row, which the push is").isEqualTo(row.get("id").asText());
        assertThat(row.get("childId").asText()).isEqualTo(childBritish2);
        assertThat(row.get("link").asText()).isEqualTo("/children/" + childBritish2 + "/broadcasts?open=" + id);
        assertThat(row.get("body").asText()).as("the row is English; the push is the phone's language").isEqualTo("Museum trip on Monday.");
        assertThat(push.getBroadcastId()).isEqualTo(id);
        assertThat(push.getChildId()).isEqualTo(childBritish2);
        assertThat(push.getLink()).isEqualTo("/children/" + childBritish2 + "/broadcasts?open=" + id);
        assertThat(push.getCollapseKey()).isEqualTo("broadcast:" + id);
        for (String other : List.of(grade1, american, abroad))
            assertThat(PushProbe.sentTo(pushes, other)).as("not this grade's, or not this school's").isEmpty();
        assertThat(names(parentGet("bc-parent-foreign", "/me/notifications"), "kind")).doesNotContain("broadcast.posted");

        for (String token : List.of(grade2, grade1, american, abroad)) devices.deleteByTokenValue(token);
        assignments.deleteAll(assignments.findAll().stream().filter(a -> OTHER_SCHOOL.equals(a.getSchoolId())).toList());
    }

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

    /** Every plan title of an archive, week by week — the archives are grouped, so `names` cannot read them. */
    private static List<String> planTitles(JsonNode archive) {
        var out = new ArrayList<String>();
        archive.get("weeks").forEach(week -> week.get("items").forEach(entry -> {
            var title = entry.get("plan").get("title");
            out.add(title == null || title.isNull() ? null : title.asText());
        }));
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
        return child(name, classId, curriculum, parentUid, grade, "BCAST1");
    }

    private String child(String name, String classId, String curriculum, String parentUid, int grade, String schoolCode) throws Exception {
        String id = json(mvc.perform(post("/children").header("Authorization", bearer(parentUid)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"avatarColor\":\"sun\",\"curriculum\":\"" + curriculum
                                + "\",\"grade\":" + grade + ",\"schoolCode\":\"" + schoolCode + "\"}"))
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
