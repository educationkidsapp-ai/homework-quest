package quest.server.classes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import quest.server.children.Entities.ChildEntity;
import quest.server.flags.FlagKeys;

/**
 * §3 of the acceptance environment: the owner registers as a parent with the school's join code, his children arrive
 * with a curriculum and a grade and no section at all, and somebody has to put them in one — otherwise
 * {@link quest.server.children.SchoolLessons} shows them every section of their grade, and a lesson published to 1A
 * and copied to 1B reaches the same child twice.
 */
class RosterAttachTest extends ClassesTestSupport {
    private static final String A = "ra-school-a", B = "ra-school-b", MAYA = "ra-maya";
    private String admin, hers, britishA, britishB, american, child;

    @Override String prefix() { return "ra-"; }

    @BeforeEach void seed() throws Exception {
        school(A, "Attach School", "RAAAAA"); school(B, "Other School", "RABBBB");
        admin = adminToken();
        britishA = section(A, "british", "1A British"); britishB = section(A, "british", "1B British");
        american = section(A, "american", "1A American");
        user(MAYA, A, "maya@ra.test", "TEACHER");
        mvc.perform(scoped(put("/admin/teachers/" + MAYA + "/assignments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"assignments\":[{\"classId\":\"" + britishA + "\",\"subject\":\"math\"}]}"), admin, A)).andExpect(status().isOk());
        hers = token(MAYA, "TEACHER", A);
        child = appChild(A, "british", 1, "Owner Child").getId();
        setFlag(true);
    }

    @AfterEach void cleanUp() throws Exception { setFlag(false); removeSeed(); }

    @Test void the_admin_attaches_a_child_the_app_created_and_detaches_her_again() throws Exception {
        assertThat(childRows.findOneById(child).orElseThrow().getClassId()).isNull();
        // she is on no roster, so the only way to find her is the school-wide list the Admin attaches from
        assertThat(json(mvc.perform(scoped(get("/admin/children?unassigned=true"), admin, A)).andExpect(status().isOk()).andReturn())
                .findValuesAsText("id")).containsExactly(child);

        var attached = json(mvc.perform(scoped(post("/admin/classes/" + britishA + "/roster/attach")
                .contentType(MediaType.APPLICATION_JSON).content(body(child)), admin, A)).andExpect(status().isOk()).andReturn());
        assertThat(attached.get("classId").asText()).isEqualTo(britishA);
        assertThat(json(mvc.perform(scoped(get("/admin/classes/" + britishA + "/children"), admin, A)).andExpect(status().isOk()).andReturn())).hasSize(1);

        // the same call twice writes nothing and answers the same row
        var again = json(mvc.perform(scoped(post("/admin/classes/" + britishA + "/roster/attach")
                .contentType(MediaType.APPLICATION_JSON).content(body(child)), admin, A)).andExpect(status().isOk()).andReturn());
        assertThat(again.get("id").asText()).isEqualTo(child);
        assertThat(again.get("classId").asText()).isEqualTo(britishA);

        assertThat(json(mvc.perform(scoped(get("/admin/children?unassigned=true"), admin, A)).andExpect(status().isOk()).andReturn())).isEmpty();
        assertThat(json(mvc.perform(scoped(get("/admin/children"), admin, A)).andExpect(status().isOk()).andReturn())).hasSize(1);

        var detached = json(mvc.perform(scoped(delete("/admin/classes/" + britishA + "/roster/" + child), admin, A))
                .andExpect(status().isOk()).andReturn());
        assertThat(detached.get("classId").isNull()).isTrue();
        assertThat(childRows.findOneById(child).orElseThrow().getClassId()).isNull();
        // she is nobody's now, so detaching her a second time is a 404 rather than a silent success
        mvc.perform(scoped(delete("/admin/classes/" + britishA + "/roster/" + child), admin, A)).andExpect(status().isNotFound());
    }

    /** A Grade 1 British child in a Grade 1 American section would be shown a syllabus she is not taught. */
    @Test void a_curriculum_or_grade_that_is_not_the_sections_is_a_409() throws Exception {
        mvc.perform(scoped(post("/admin/classes/" + american + "/roster/attach")
                .contentType(MediaType.APPLICATION_JSON).content(body(child)), admin, A)).andExpect(status().isConflict());

        String grade2 = appChild(A, "british", 2, "Older Child").getId();
        mvc.perform(scoped(post("/admin/classes/" + britishA + "/roster/attach")
                .contentType(MediaType.APPLICATION_JSON).content(body(grade2)), admin, A)).andExpect(status().isConflict());
    }

    /**
     * A scoped caller never sees the other school's child at all — the lookup is a filtered query, so it is a 404.
     * The platform ADMIN with no `X-School-Id` reads across schools (D6) and is the one caller who can reach her;
     * she is refused with a 409 rather than quietly moved into a school she does not belong to.
     */
    @Test void a_child_of_another_school_is_refused() throws Exception {
        String theirs = appChild(B, "british", 1, "Other Child").getId();
        mvc.perform(scoped(post("/admin/classes/" + britishA + "/roster/attach")
                .contentType(MediaType.APPLICATION_JSON).content(body(theirs)), admin, A)).andExpect(status().isNotFound());
        mvc.perform(as(post("/admin/classes/" + britishA + "/roster/attach")
                .contentType(MediaType.APPLICATION_JSON).content(body(theirs)), admin)).andExpect(status().isConflict());
        assertThat(childRows.findOneById(theirs).orElseThrow().getClassId()).isNull();
    }

    @Test void a_teacher_attaches_into_her_own_section_only() throws Exception {
        var attached = json(mvc.perform(as(post("/teacher/classes/" + britishA + "/roster/attach")
                .contentType(MediaType.APPLICATION_JSON).content(body(child)), hers)).andExpect(status().isOk()).andReturn());
        assertThat(attached.get("classId").asText()).isEqualTo(britishA);

        // 1B is her school's and not hers
        mvc.perform(as(post("/teacher/classes/" + britishB + "/roster/attach")
                .contentType(MediaType.APPLICATION_JSON).content(body(child)), hers)).andExpect(status().isForbidden());
        mvc.perform(as(delete("/teacher/classes/" + britishB + "/roster/" + child), hers)).andExpect(status().isForbidden());

        mvc.perform(as(delete("/teacher/classes/" + britishA + "/roster/" + child), hers)).andExpect(status().isOk());
        assertThat(childRows.findOneById(child).orElseThrow().getClassId()).isNull();
    }

    /** Her half of the roster is `teacher.rosterEdit`, and the Admin's routes are not a way round the flag. */
    @Test void the_teacher_routes_follow_the_roster_flag() throws Exception {
        setFlag(false);
        mvc.perform(as(post("/teacher/classes/" + britishA + "/roster/attach")
                .contentType(MediaType.APPLICATION_JSON).content(body(child)), hers)).andExpect(status().isNotFound());
        mvc.perform(as(delete("/teacher/classes/" + britishA + "/roster/" + child), hers)).andExpect(status().isNotFound());
        setFlag(true);
        mvc.perform(as(post("/admin/classes/" + britishA + "/roster/attach")
                .contentType(MediaType.APPLICATION_JSON).content(body(child)), hers)).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- helpers

    private static String body(String childId) { return "{\"childId\":\"" + childId + "\"}"; }

    /** A child as the app creates her: a school, a curriculum and a grade, and no section. */
    private ChildEntity appChild(String schoolId, String curriculum, int grade, String name) {
        var child = new ChildEntity();
        child.setId("ra-" + UUID.randomUUID()); child.setSchoolId(schoolId); child.setName(name);
        child.setCurriculum(curriculum); child.setGrade(grade); child.setAvatarColor("sky"); child.setLanguages("en");
        child.setActive(true); child.setCreatedAt(Instant.now());
        return childRows.save(child);
    }

    private String section(String schoolId, String curriculum, String name) throws Exception {
        return json(mvc.perform(scoped(post("/admin/classes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"curriculum\":\"" + curriculum + "\",\"grade\":1,\"name\":\"" + name + "\"}"), admin, schoolId))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
    }

    private void setFlag(boolean enabled) throws Exception {
        mvc.perform(as(put("/admin/schools/" + A + "/flags/" + FlagKeys.TEACHER_ROSTER_EDIT).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":" + enabled + "}"), admin)).andExpect(status().isOk());
    }
}
