package quest.server.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import quest.server.notifications.NotificationRepository;

/**
 * U1 item 2: `POST /teacher/messages/coordinator` — a teacher writing to the people who handle her school's
 * messages, which nothing in the contract could carry before.
 *
 * <p>What the three tests pin down is the whole of it: her words reach every coordinator <em>of her own school</em>
 * and nobody else's, a school with no coordinator is told so rather than told "sent", and the route is a TEACHER's.
 */
class TeacherCoordinatorMessageTest extends TeacherTestSupport {
    private static final String A = "tcm-school-a", B = "tcm-school-b";
    private static final String TEACHER_A = "tcm-teacher-a", MANAGER_A = "tcm-manager-a", MANAGER_A2 = "tcm-manager-a2";
    private static final String TEACHER_B = "tcm-teacher-b", MANAGER_B = "tcm-manager-b";

    @Autowired NotificationRepository notifications;

    @Override String prefix() { return "tcm-"; }

    private String teacherAToken, teacherBToken, managerAToken;

    @BeforeEach void seed() {
        school(A, "Message Academy", "TCMSCA");
        school(B, "Other Academy", "TCMSCB");
        teacher(TEACHER_A, A, "a@tcm.test", "Ms Sara", "[\"math\"]", "british", "[1]");
        user(MANAGER_A, A, "m1@tcm.test", "MANAGERIAL");
        user(MANAGER_A2, A, "m2@tcm.test", "MANAGERIAL");
        teacher(TEACHER_B, B, "b@tcm.test", "Ms Hana", "[\"math\"]", "british", "[1]");
        user(MANAGER_B, B, "m3@tcm.test", "MANAGERIAL");
        // Each teacher gets a section of her own: the school the message goes to is resolved through her
        // assignments (`TeacherScope.assignmentsOf`), not from the token alone.
        klass(A + ":british:1:math", A, "british", 1, "math", TEACHER_A);
        klass(B + ":british:1:math", B, "british", 1, "math", TEACHER_B);
        teacherAToken = token(TEACHER_A, "TEACHER", A);
        teacherBToken = token(TEACHER_B, "TEACHER", B);
        managerAToken = token(MANAGER_A, "MANAGERIAL", A);
    }

    @AfterEach void clean() {
        notifications.deleteAll(notifications.findAll().stream().filter(n -> n.getSchoolId().startsWith(prefix())).toList());
        removeSeed();
    }

    @Test void her_message_reaches_every_coordinator_of_her_own_school() throws Exception {
        var result = json(mvc.perform(as(post("/teacher/messages/coordinator").contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"The projector in 1A is broken.\"}"), teacherAToken))
                .andExpect(status().isOk()).andReturn());
        assertThat(result.get("delivered").asInt()).isEqualTo(2);

        var written = notifications.findAll().stream().filter(n -> A.equals(n.getSchoolId())).toList();
        assertThat(written).hasSize(2);
        assertThat(written).allSatisfy(row -> {
            assertThat(row.getKind()).isEqualTo("teacher.message");
            // Her own words, untranslated: the dashboard shows the server's body for this kind.
            assertThat(row.getBody()).isEqualTo("The projector in 1A is broken.");
            assertThat(row.getTitle()).isEqualTo("Message from Ms Sara");
        });
        assertThat(written).extracting("userId").containsExactlyInAnyOrder(MANAGER_A, MANAGER_A2);
        // Another school's coordinator is not on this message, whatever her role.
        assertThat(notifications.findAll().stream().noneMatch(n -> MANAGER_B.equals(n.getUserId()))).isTrue();
    }

    @Test void an_empty_message_is_refused_and_a_school_without_a_coordinator_is_told_so() throws Exception {
        mvc.perform(as(post("/teacher/messages/coordinator").contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"   \"}"), teacherAToken)).andExpect(status().isBadRequest());

        users.deleteAll(users.findAllById(java.util.List.of(MANAGER_B)));
        mvc.perform(as(post("/teacher/messages/coordinator").contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Anyone there?\"}"), teacherBToken)).andExpect(status().isConflict());
    }

    /**
     * The limit is the notification body's, and it is refused rather than clipped.
     *
     * At `@Size(max = 2000)` the 501st character reached `NotificationService.clip`, which shortened it to an
     * ellipsis — she was told "sent" over a message the coordinator would read half of.
     */
    @Test void a_message_past_the_limit_is_refused_rather_than_silently_shortened() throws Exception {
        String justFits = "x".repeat(quest.server.notifications.NotificationService.BODY_MAX);
        mvc.perform(as(post("/teacher/messages/coordinator").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new TeacherDto.CoordinatorMessageRequest(justFits))), teacherAToken))
                .andExpect(status().isOk());
        assertThat(notifications.findAll().stream().filter(n -> A.equals(n.getSchoolId())))
                .allSatisfy(row -> assertThat(row.getBody()).isEqualTo(justFits));

        String oneTooMany = "x".repeat(quest.server.notifications.NotificationService.BODY_MAX + 1);
        mvc.perform(as(post("/teacher/messages/coordinator").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new TeacherDto.CoordinatorMessageRequest(oneTooMany))), teacherAToken))
                .andExpect(status().isBadRequest());
    }

    /** `teacher.message.coordinator` is TEACHER-only in `permissions.json`: a coordinator does not write to herself. */
    @Test void a_coordinator_may_not_post_to_this_route() throws Exception {
        mvc.perform(as(post("/teacher/messages/coordinator").contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Hello\"}"), managerAToken)).andExpect(status().isForbidden());
    }
}
