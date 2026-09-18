package quest.server.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import quest.server.ApiTestSupport;
import quest.server.auth.AdminJwtService;
import quest.server.config.ApiException;
import quest.server.content.LessonRepository;

/** P1.1: the default school exists, every migrated row is in it, and the token/child/lesson shapes carry the tenant. */
class TenancyContractTest extends ApiTestSupport {
    @Autowired SchoolRepository schools;
    @Autowired ClassRepository classes;
    @Autowired LessonRepository lessons;
    @Autowired TenantContext tenant;
    @Autowired AdminJwtService jwt;

    @Test void the_default_school_is_seeded() {
        var school = schools.findById("default").orElseThrow();
        assertThat(school.getCode()).isEqualTo("HQ0001");
        assertThat(school.getCurriculumOptionsJson()).contains("american", "british");
        assertThat(school.getGradeOptionsJson()).isEqualTo("[1,2,3]");
        assertThat(school.getFeatureFlagsJson()).isEqualTo("{}");
        assertThat(schools.findByCodeIgnoreCase("hq0001")).isPresent();
    }

    /**
     * The migrated rows, not every row: other test classes publish lessons into schools of their own, so asserting
     * over `findAll()` only passed while this class happened to run before them. The seeded lessons are exactly the
     * pre-tenancy content V4 moved into the default school, which is what P1.1 promised.
     */
    @Test void every_seeded_lesson_lives_in_a_class_of_the_default_school() {
        var seeded = quest.api.samples.Seeds.INSTANCE.getLessons().stream().map(l -> lessons.findById(l.getId()).orElseThrow()).toList();
        assertThat(seeded).isNotEmpty().allSatisfy(l -> {
            assertThat(l.getSchoolId()).isEqualTo("default");
            assertThat(classes.findById(l.getClassId()).orElseThrow().getSchoolId()).isEqualTo("default");
        });
    }

    @Test void sign_in_carries_the_role_and_the_school() throws Exception {
        var body = json(mvc.perform(post("/admin/auth/sign-in").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin@test.local\",\"password\":\"admin1234\"}")).andExpect(status().isOk()).andReturn());
        assertThat(body.get("role").asText()).isEqualTo("ADMIN");
        assertThat(body.get("schoolId").isNull() || !body.has("schoolId")).isTrue();
        assertThat(body.get("mustChangePassword").asBoolean()).isFalse();
        assertThat(body.get("token").asText()).startsWith("admin.");
    }

    @Test void an_admin_lesson_lands_in_a_class_of_the_write_school() throws Exception {
        var token = adminToken();
        var created = json(mvc.perform(admin(post("/admin/lessons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"curriculum\":\"british\",\"grade\":2,\"subject\":\"math\",\"date\":\"2026-03-04\",\"source\":\"manual\",\"title\":\"Tenancy\"}"), token))
                .andExpect(status().is2xxSuccessful()).andReturn());
        var lesson = lessons.findById(created.get("id").asText()).orElseThrow();
        assertThat(lesson.getSchoolId()).isEqualTo("default");
        var klass = classes.findById(lesson.getClassId()).orElseThrow();
        assertThat(klass.getSchoolId()).isEqualTo("default");
        assertThat(klass.getCurriculum()).isEqualTo("british");
        assertThat(klass.getGrade()).isEqualTo(2);
        // V7 (D14): the class is a **section**, not a (curriculum, grade, subject) triple. An Admin who names no
        // class gets the school's section for that course — created as "2A" if it had none — and the subject stays
        // on the lesson, where it belongs, rather than on the class.
        assertThat(klass.isSection()).isTrue();
        assertThat(klass.getName()).isEqualTo("2A");
        assertThat(klass.getJoinCode()).isNotBlank();
        assertThat(lesson.getSubject()).isEqualTo("math");
    }

    @Test void the_tenant_scope_follows_the_token_then_the_header() {
        var own = school();                                                         // a teacher's school has to exist: the scope fails closed
        try {
            tenant.set("TEACHER", own, "another-school");                           // a teacher's token wins over any header
            assertThat(tenant.schoolId()).isEqualTo(own);
            assertThat(tenant.writeSchoolId()).isEqualTo(own);

            tenant.set("ADMIN", null, null);                                        // D6: reads across schools, writes into `default`
            assertThat(tenant.schoolId()).isNull();
            assertThat(tenant.writeSchoolId()).isEqualTo("default");

            tenant.set("ADMIN", null, "default");                                   // the school switcher
            assertThat(tenant.schoolId()).isEqualTo("default");

            tenant.set("ADMIN", null, "no-such-school");
            assertThatThrownBy(tenant::schoolId).isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).error().code()).isEqualTo("not_found"));

            tenant.set("TEACHER", "no-such-school", null);                          // a non-ADMIN scope fails closed, not open
            assertThatThrownBy(tenant::schoolId).isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).error().code()).isEqualTo("forbidden"));
        } finally { tenant.clear(); }
        assertThat(tenant.schoolId()).isNull();
    }

    /** A school of this test's own, so that nothing here depends on (or collides with) the schools other tests seed. */
    private String school() {
        var s = new Entities.SchoolEntity();
        s.setId("contract-" + UUID.randomUUID()); s.setName("Contract School"); s.setCode(UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        s.setCurriculumOptionsJson("[\"british\"]"); s.setGradeOptionsJson("[1,2,3]"); s.setCreatedAt(Instant.now());
        return schools.save(s).getId();
    }

    @Test void a_dashboard_token_carries_the_role_and_the_school() {
        var issued = jwt.issue("u-1", "teacher@school-a.test", "TEACHER", "school-a");
        var user = jwt.verify(issued.token()).orElseThrow();
        assertThat(user.role()).isEqualTo("TEACHER");
        assertThat(user.schoolId()).isEqualTo("school-a");
        assertThat(jwt.verify(jwt.issue("u-2", "boss@platform.test", "ADMIN", null).token()).orElseThrow().schoolId()).isNull();
    }

    @Test void a_child_joins_the_school_whose_code_the_parent_typed() throws Exception {
        var s = new Entities.SchoolEntity();
        s.setId(UUID.randomUUID().toString()); s.setName("Al Noor"); s.setCode("ALN123");
        s.setCurriculumOptionsJson("[\"british\"]"); s.setGradeOptionsJson("[1,2,3]"); s.setCreatedAt(Instant.now());
        schools.save(s);

        var joined = parentPost("/children", "{\"name\":\"Maya\",\"avatarColor\":\"sun\",\"curriculum\":\"british\",\"grade\":1,\"schoolCode\":\"aln123\"}");
        assertThat(joined.get("schoolId").asText()).isEqualTo(s.getId());

        var defaulted = parentPost("/children", "{\"name\":\"Omar\",\"avatarColor\":\"mint\",\"curriculum\":\"american\",\"grade\":2}");
        assertThat(defaulted.get("schoolId").asText()).isEqualTo("default");

        mvc.perform(post("/children").header("Authorization", PARENT).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Lina\",\"avatarColor\":\"sky\",\"curriculum\":\"british\",\"grade\":1,\"schoolCode\":\"ZZZZZZ\"}"))
                .andExpect(status().isNotFound());
    }
}
