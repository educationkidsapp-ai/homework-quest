package quest.server.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import quest.server.ApiTestSupport;
import quest.server.admin.AdminPipelineTest;

/**
 * §2: "A child belongs to a school + curriculum + grade; the map merges every published lesson from every Class
 * matching those three." Two Classes of school A (two teachers, two subjects) reach a British grade 1 child of
 * school A; school B's British grade 1 lesson and the seeded lessons of the default school do not.
 */
class TenantMapTest extends ApiTestSupport {
    private static final String A = "map-school-a", B = "map-school-b";

    @Autowired SchoolRepository schools;
    @Autowired ClassRepository classes;
    @Autowired quest.server.tenancy.TeachingAssignmentRepository assignments;
    @Autowired quest.server.auth.UserRepository users;
    @Autowired jakarta.persistence.EntityManagerFactory emf;

    @BeforeEach void seed() {
        school(A, "Mapville Primary", "MAPAAA"); school(B, "Otherton School", "MAPBBB");
        user("map-teacher-english", A); user("map-teacher-math", A);
        klass(A, "english", "map-teacher-english"); klass(A, "math", "map-teacher-math"); klass(B, "english", null);
    }

    @Test void a_child_sees_every_class_of_her_school_and_nothing_of_another() throws Exception {
        var token = adminToken();
        var english = publish(token, A, "english", "2027-06-02", "Story time in A");
        var math = publish(token, A, "math", "2027-06-03", "Counting in A");
        var otherSchool = publish(token, B, "english", "2027-06-04", "Story time in B");

        var child = parentPost("/children", "{\"name\":\"Nour\",\"avatarColor\":\"mint\",\"curriculum\":\"british\",\"grade\":1,\"schoolCode\":\"MAPAAA\"}");
        var map = parentGet("/children/" + child.get("id").asText() + "/map?from=2027-06-01&to=2027-06-10&today=2027-06-02");

        var lessonIds = new ArrayList<String>();
        map.get("islands").forEach(i -> { if (i.hasNonNull("lessonId")) lessonIds.add(i.get("lessonId").asText()); });
        assertThat(lessonIds).contains(english, math).doesNotContain(otherSchool);
        assertThat(map.toString()).doesNotContain(otherSchool);

        // the two lessons really come from two Classes of A with two teachers and two subjects
        var classIds = List.of(A + ":british:1:english", A + ":british:1:math");
        assertThat(classIds).allSatisfy(id -> assertThat(classes.findById(id).orElseThrow().getTeacherId()).isNotNull());

        // and the same child in school B sees only B's lesson
        var sibling = parentPost("/children", "{\"name\":\"Sami\",\"avatarColor\":\"sky\",\"curriculum\":\"british\",\"grade\":1,\"schoolCode\":\"MAPBBB\"}");
        var otherMap = parentGet("/children/" + sibling.get("id").asText() + "/map?from=2027-06-01&to=2027-06-10&today=2027-06-04");
        var otherIds = new ArrayList<String>();
        otherMap.get("islands").forEach(i -> { if (i.hasNonNull("lessonId")) otherIds.add(i.get("lessonId").asText()); });
        assertThat(otherIds).contains(otherSchool).doesNotContain(english, math);

        assertNoNPlusOne(child.get("id").asText(), 2);
        publish(token, A, "english", "2027-06-05", "Another story in A");        // a third lesson must not cost more queries
        assertNoNPlusOne(child.get("id").asText(), 3);
    }

    /**
     * V7 (D14): once a child sits in a section (`children.class_id` — set by the join code, by the roster, or by the
     * migration's backfill) the map is <em>that class's</em> published lessons, every subject and every teacher.
     * The pre-V7 rule stays as the fallback for a child who has no section yet, which the test above exercises.
     */
    @Test void a_child_in_a_section_sees_that_sections_lessons_and_not_her_schools_other_classes() throws Exception {
        var token = adminToken();
        var english = publish(token, A, "english", "2027-07-02", "Story time in 1E");
        var math = publish(token, A, "math", "2027-07-03", "Counting in 1M");

        var englishClass = classes.findById(A + ":british:1:english").orElseThrow();
        var child = parentPost("/children", "{\"name\":\"Rania\",\"avatarColor\":\"sun\",\"curriculum\":\"british\",\"grade\":1,"
                + "\"joinCode\":\"" + englishClass.getJoinCode() + "\"}");

        var map = parentGet("/children/" + child.get("id").asText() + "/map?from=2027-07-01&to=2027-07-10&today=2027-07-02");
        var lessonIds = new ArrayList<String>();
        map.get("islands").forEach(i -> { if (i.hasNonNull("lessonId")) lessonIds.add(i.get("lessonId").asText()); });
        assertThat(lessonIds).contains(english).doesNotContain(math);
    }

    /** The map costs the same number of statements whatever the number of lessons (Hibernate statistics). */
    private void assertNoNPlusOne(String childId, int lessons) throws Exception {
        var stats = emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true); stats.clear();
        parentGet("/children/" + childId + "/map?from=2027-06-01&to=2027-06-10&today=2027-06-02");
        long statements = stats.getPrepareStatementCount();
        System.out.println("map statements with " + lessons + " lessons: " + statements);
        assertThat(statements).as("statements for a map of %d lessons", lessons).isLessThanOrEqualTo(12);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Runs the real pipeline (sample LLM) for one school and returns the published lesson id. The class is named
     * explicitly: since V7 an Admin who names none gets the school's <em>first</em> section for that course, so
     * without this both subjects would land in the same one and the test would prove nothing.
     */
    private String publish(String token, String schoolId, String subject, String date, String title) throws Exception {
        byte[] pdf = AdminPipelineTest.pdf(title, "Mummy is in bed. She has a cold.", "Alan and Daddy make hot soup.");
        var id = json(mvc.perform(scoped(post("/admin/lessons"), token, schoolId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"curriculum\":\"british\",\"grade\":1,\"subject\":\"" + subject + "\",\"date\":\"" + date + "\",\"title\":\"" + title
                        + "\",\"classId\":\"" + schoolId + ":british:1:" + subject + "\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        mvc.perform(scoped(multipart("/admin/lessons/" + id + "/files").file(new MockMultipartFile("files", "slides.pdf", "application/pdf", pdf)), token, schoolId)).andExpect(status().isOk());
        mvc.perform(scoped(post("/admin/lessons/" + id + "/analyze"), token, schoolId)).andExpect(status().isOk());
        var lesson = awaitScoped(token, schoolId, id, "needs_review");
        var skills = mapper.createArrayNode();
        for (var s : lesson.get("skills")) skills.addObject().put("id", s.get("id").asText()).put("name", s.get("name").asText()).put("subject", s.get("subject").asText()).put("method", s.get("method").asText());
        mvc.perform(scoped(post("/admin/lessons/" + id + "/skills"), token, schoolId).contentType(MediaType.APPLICATION_JSON).content(skills.toString())).andExpect(status().isOk());
        awaitScoped(token, schoolId, id, "review");
        mvc.perform(scoped(post("/admin/lessons/" + id + "/publish"), token, schoolId)).andExpect(status().isOk());
        return id;
    }

    private com.fasterxml.jackson.databind.JsonNode awaitScoped(String token, String schoolId, String id, String terminal) throws Exception {
        for (int i = 0; i < 100; i++) {
            var l = json(mvc.perform(scoped(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/admin/lessons/" + id), token, schoolId)).andReturn());
            if (terminal.equals(l.get("status").asText())) return l;
            if ("error".equals(l.get("status").asText())) throw new AssertionError("lesson failed: " + l.get("error"));
            Thread.sleep(100);
        }
        throw new AssertionError("timed out waiting for " + terminal);
    }

    private MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder b, String token, String schoolId) {
        return b.header("Authorization", "Bearer " + token).header(TenantContext.HEADER, schoolId);
    }

    private void school(String id, String name, String code) {
        if (schools.existsById(id)) return;
        var s = new Entities.SchoolEntity();
        s.setId(id); s.setName(name); s.setCode(code); s.setCurriculumOptionsJson("[\"british\"]"); s.setGradeOptionsJson("[1,2,3]"); s.setCreatedAt(Instant.now());
        schools.save(s);
    }

    private void user(String id, String schoolId) {
        if (users.existsById(id)) return;
        var u = new quest.server.auth.Entities.UserEntity();
        u.setId(id); u.setSchoolId(schoolId); u.setEmail(id + "@alpha.test"); u.setPasswordHash("x"); u.setRole("TEACHER"); u.setCreatedAt(Instant.now()); u.setUpdatedAt(Instant.now());
        users.save(u);
    }

    private void klass(String schoolId, String subject, String teacherId) {
        String id = schoolId + ":british:1:" + subject;
        if (classes.existsById(id)) return;
        quest.server.ClassFixtures.section(classes, assignments, id, schoolId, "british", 1, subject, teacherId);
    }
}
