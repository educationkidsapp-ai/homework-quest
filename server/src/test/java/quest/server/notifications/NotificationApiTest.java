package quest.server.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import quest.api.dto.NotificationKind;
import quest.api.dto.NotificationView;
import quest.server.content.Entities.LessonEntity;
import quest.server.grading.GradingTestSupport;

/**
 * E2 (D26): the bell is written by the lesson lifecycle and read back by its owner and nobody else.
 *
 * <p>The first test is the whole claim in one run: a lesson generated from typed text on the fake client leaves
 * `generating` for `review` exactly once, so the teacher who created it finds exactly one `lesson.ready` row, her
 * colleague in the same school finds none, and polling the lesson afterwards — which is what the dashboard does
 * every couple of seconds — adds nothing, because a poll writes no status.
 */
class NotificationApiTest extends GradingTestSupport {
    private static final String SCHOOL = "nt-school", MINE = "nt-teacher", OTHER = "nt-other", KLASS = "nt-1a";

    @Autowired NotificationRepository rows;
    @Autowired NotificationService notifications;

    private String mine, other;

    @Override public String prefix() { return "nt-"; }

    @BeforeEach void seed() {
        school(SCHOOL, "Bell Academy", "NTSCH1");
        teacher(MINE, SCHOOL, "Ms Mine"); teacher(OTHER, SCHOOL, "Ms Other");
        klass(KLASS, SCHOOL, MINE, "1A");
        mine = token(MINE, "TEACHER", SCHOOL); other = token(OTHER, "TEACHER", SCHOOL);
    }

    @AfterEach void clean() {
        rows.deleteAll(rows.findAll().stream().filter(n -> n.getSchoolId().startsWith(prefix())).toList());
        removeSeed();
    }

    @Test void a_finished_pipeline_rings_the_creators_bell_once_and_nobody_elses() throws Exception {
        String id = draft("nt-lesson-1");
        mvc.perform(as(post("/teacher/lessons/" + id + "/generate-from-text").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Nadia paints a yellow bell. The bell rings when the wind blows. Her class counts the rings.\"}"), mine))
                .andExpect(status().isOk());
        awaitReview(id);

        var bell = list(mine, "");
        assertThat(bell).hasSize(1);
        assertThat(bell.get(0).get("kind").asText()).isEqualTo("lesson.ready");
        assertThat(bell.get(0).get("title").asText()).isEqualTo("Questions ready");
        assertThat(bell.get(0).get("link").asText()).as("a dashboard path, not a URL").isEqualTo("/teacher/lessons/" + id);
        assertThat(bell.get(0).get("lessonId").asText()).isEqualTo(id);
        assertThat(count(mine)).isEqualTo(1);
        assertThat(list(other, "")).as("her colleague did not create it").isEmpty();

        // the dashboard polls this every couple of seconds; a poll writes no status and so can make no row
        mvc.perform(as(get("/teacher/lessons/" + id + "/status"), mine)).andExpect(status().isOk());
        mvc.perform(as(get("/teacher/lessons/" + id + "/status"), mine)).andExpect(status().isOk());
        assertThat(count(mine)).isEqualTo(1);
    }

    @Test void the_bell_reads_unread_marks_read_and_never_shows_another_users_row() throws Exception {
        var ready = write(MINE, NotificationKind.LESSON_READY, "Questions ready");
        write(MINE, NotificationKind.LESSON_FAILED, "Generation stopped");
        var theirs = write(OTHER, NotificationKind.LESSON_READY, "Questions ready");

        assertThat(count(mine)).isEqualTo(2);
        assertThat(list(mine, "?unread=true")).hasSize(2);

        mvc.perform(as(post("/me/notifications/" + ready.getId() + "/read"), mine)).andExpect(status().isOk());
        assertThat(count(mine)).isEqualTo(1);
        assertThat(list(mine, "?unread=true")).hasSize(1);
        assertThat(list(mine, "?limit=50")).as("read rows stay in the list").hasSize(2);

        // another user's id is not found rather than forbidden: the row is not hers to learn the existence of
        mvc.perform(as(post("/me/notifications/" + theirs.getId() + "/read"), mine)).andExpect(status().isNotFound());
        mvc.perform(as(get("/me/notifications?limit=0"), mine)).andExpect(status().isBadRequest());
        mvc.perform(as(get("/me/notifications?limit=101"), mine)).andExpect(status().isBadRequest());

        mvc.perform(as(post("/me/notifications/read-all"), mine)).andExpect(status().isOk());
        assertThat(count(mine)).isZero();
        assertThat(count(other)).as("read-all is hers alone").isEqualTo(1);
    }

    /**
     * Every dashboard role has a bell, and since B3 a parent has her own rows on the same routes — never a staff
     * member's: her recipient key is `parent:<id>`, so a staff row's id is a 404 to her.
     */
    @Test void a_managerial_user_has_a_bell_and_a_parent_reads_only_her_own() throws Exception {
        var head = teacher("nt-head", SCHOOL, "Head");
        head.setRole("MANAGERIAL"); users.save(head);
        assertThat(list(token("nt-head", "MANAGERIAL", SCHOOL), "")).isEmpty();
        var staffRow = write("nt-head", NotificationKind.LESSON_READY, "Questions ready");
        assertThat(parentGet("/me/notifications")).isEmpty();
        assertThat(parentGet("/me/notifications/unread-count").get("count").asInt()).isZero();
        mvc.perform(post("/me/notifications/" + staffRow.getId() + "/read").header("Authorization", PARENT)).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- helpers

    private NotificationView write(String userId, NotificationKind kind, String title) {
        return notifications.notify(SCHOOL, userId, kind, title, "because.", "/teacher/lessons/nt-x", "nt-x");
    }

    private JsonNode list(String token, String query) throws Exception {
        return json(mvc.perform(as(get("/me/notifications" + query), token)).andExpect(status().isOk()).andReturn());
    }

    private int count(String token) throws Exception {
        return json(mvc.perform(as(get("/me/notifications/unread-count"), token)).andExpect(status().isOk()).andReturn()).get("count").asInt();
    }

    /**
     * T1 (review): the `chat.message` throttle is the database's rule, not a read followed by an insert. Eight threads
     * write to one thread at the same moment and she ends with **one** unread row, carrying one of the bodies — before
     * V26 both halves of a racing pair read "none unread" and both inserted.
     */
    @Test void concurrent_messages_on_one_thread_leave_exactly_one_unread_row() throws Exception {
        String thread = "nt-thread-1";
        int writers = 8;
        var start = new java.util.concurrent.CountDownLatch(1);
        var done = new java.util.concurrent.CountDownLatch(writers);
        var failures = new java.util.concurrent.CopyOnWriteArrayList<Throwable>();
        for (int i = 0; i < writers; i++) {
            int n = i;
            Thread.startVirtualThread(() -> {
                try { start.await(); notifications.chatMessage(SCHOOL, MINE, thread, "Maya", "line " + n); }
                catch (Throwable t) { failures.add(t); }
                finally { done.countDown(); }
            });
        }
        start.countDown();
        assertThat(done.await(20, java.util.concurrent.TimeUnit.SECONDS)).as("every writer finished").isTrue();
        assertThat(failures).as("a losing insert is recovered, never thrown").isEmpty();

        var unread = rows.unreadAbout(MINE, "chat.message", thread);
        assertThat(unread).as("one unread row per thread per recipient, whoever wrote first").hasSize(1);
        assertThat(unread.get(0).getBody()).startsWith("line ");
        assertThat(unread.get(0).getLink()).isEqualTo("/teacher/chat?thread=" + thread);

        // reading it lets the next message ring again: the index only constrains the unread row
        notifications.markThreadRead(MINE, thread);
        notifications.chatMessage(SCHOOL, MINE, thread, "Maya", "and again");
        assertThat(rows.unreadAbout(MINE, "chat.message", thread)).hasSize(1);
        assertThat(rows.newest(MINE, org.springframework.data.domain.PageRequest.of(0, 20)).stream()
                .filter(r -> "chat.message".equals(r.getKind())).count()).as("two rows in all: the read one and the new one").isEqualTo(2);
    }

    /** A hand-written lesson of hers with nothing in it yet — `generate-from-text` is the shortest whole pipeline. */
    private String draft(String id) {
        var l = lessons.findById(id).orElseGet(LessonEntity::new);
        l.setId(id); l.setSchoolId(SCHOOL); l.setClassId(KLASS); l.setTeacherId(MINE); l.setCreatedBy(MINE + "@seed.test");
        l.setCourseId("british/1"); l.setSubject("math"); l.setDate(LocalDate.of(2026, 11, 4));
        l.setStatus("draft"); l.setVersion(0); l.setSource("manual"); l.setTitle("The yellow bell");
        if (l.getCreatedAt() == null) l.setCreatedAt(Instant.now());
        l.setUpdatedAt(Instant.now());
        lessons.save(l);
        return id;
    }

    private void awaitReview(String id) throws Exception {
        for (int i = 0; i < 150; i++) {
            var l = json(mvc.perform(as(get("/teacher/lessons/" + id), mine)).andExpect(status().isOk()).andReturn());
            if ("review".equals(l.get("status").asText())) return;
            if ("error".equals(l.get("status").asText())) throw new AssertionError("lesson failed: " + l.get("error"));
            Thread.sleep(100);
        }
        throw new AssertionError("timed out waiting for review");
    }
}
