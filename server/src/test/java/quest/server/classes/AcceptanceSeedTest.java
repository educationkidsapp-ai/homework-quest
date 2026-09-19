package quest.server.classes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import quest.server.auth.TeacherRepository;
import quest.server.config.QuestProperties;
import quest.server.tenancy.Entities.TeachingAssignmentEntity;

/**
 * `SEED_PROFILE=acceptance`: the owner's own QA environment, which is two teachers and nothing else. The children
 * arrive when he registers as a parent in the app, which is why this profile seeds none — a roster row typed by the
 * seed would be a second child with the same name beside the one the app creates.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AcceptanceSeedTest extends ClassesTestSupport {
    private static final String SCHOOL = "acceptance-school";
    private static final String DIR = "seed/acceptance/";
    private static final String STAFF_PASSWORD = "nine-char";                    // 9 characters, under MIN_PASSWORD

    @Autowired SchoolSeed seed;
    @Autowired TeacherRepository profiles;
    @Autowired QuestProperties props;

    private SchoolSeed.Counts first;

    @Override String prefix() { return SCHOOL; }

    @BeforeAll void loadTheSchool() {
        school(SCHOOL, "Acceptance School", "ACPT01");
        first = seed.load(SCHOOL, STAFF_PASSWORD, false, DIR);
    }

    @AfterAll void takeItBackOut() {
        removeSeed();
        var staff = users.findBySchoolIdAndRole(SCHOOL, "TEACHER");
        profiles.deleteAll(profiles.findAllById(staff.stream().map(u -> u.getId()).toList()));
        users.deleteAll(staff);
    }

    @Test void the_profile_names_the_directory_the_files_are_read_from() {
        assertThat(new QuestProperties.Seed(true, "acceptance", false, null).directory()).isEqualTo(DIR);
        assertThat(new QuestProperties.Seed(true, null, false, null).directory()).isEqualTo("seed/");
        assertThat(new QuestProperties.Seed(true, " ACCEPTANCE ", false, null).directory()).isEqualTo(DIR);
        assertThatThrownBy(() -> new QuestProperties.Seed(true, "nonsense", false, null).profileOrFull())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SEED_PROFILE=nonsense is not a seed profile");
        assertThat(props.seed().profileOrFull()).as("the suite runs the default").isEqualTo("full");
    }

    @Test void loads_three_sections_two_teachers_three_assignments_and_no_children() {
        assertThat(first).isEqualTo(new SchoolSeed.Counts(3, 2, 3, 0));

        var sections = classes.findAll().stream().filter(k -> SCHOOL.equals(k.getSchoolId())).toList();
        assertThat(sections.stream().map(k -> k.getCurriculum() + " " + k.getGrade() + " " + k.getName()))
                .containsExactlyInAnyOrder("british 1 1A British", "british 1 1B British", "american 1 1A American");
        assertThat(sections).allSatisfy(k -> assertThat(k.getJoinCode()).isNotBlank());
        assertThat(users.findBySchoolIdAndRole(SCHOOL, "TEACHER").stream().map(u -> u.getEmail().toLowerCase(Locale.ROOT)))
                .containsExactlyInAnyOrder("maya@test.com", "rami@test.com");
        assertThat(childRows.findAll().stream().filter(c -> SCHOOL.equals(c.getSchoolId()))).isEmpty();
    }

    @Test void maya_teaches_both_british_sections_and_rami_the_american_one() {
        assertThat(slotsOf("maya@test.com")).containsExactly("1A British · math", "1B British · math");
        assertThat(slotsOf("rami@test.com")).containsExactly("1A American · english");
        assertThat(assignments.findAll().stream().filter(a -> SCHOOL.equals(a.getSchoolId()))).hasSize(3);
    }

    /** Nine characters is under `MIN_PASSWORD`, which the seed deliberately does not apply: see {@link SchoolSeed}. */
    @Test void both_teachers_sign_in_with_the_shared_password() throws Exception {
        for (String email : List.of("maya@test.com", "rami@test.com")) {
            var session = json(mvc.perform(post("/auth/sign-in").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + email + "\",\"password\":\"" + STAFF_PASSWORD + "\"}"))
                    .andExpect(status().isOk()).andReturn());
            assertThat(session.get("role").asText()).isEqualTo("TEACHER");
            assertThat(session.get("schoolId").asText()).isEqualTo(SCHOOL);
            assertThat(session.get("mustChangePassword").asBoolean()).isFalse();
        }
    }

    /** An address the file writes in another case is the same teacher: the seed matches on the lower-cased one. */
    @Test void a_second_run_writes_nothing() {
        assertThat(seed.load(SCHOOL, STAFF_PASSWORD, false, DIR)).isEqualTo(new SchoolSeed.Counts(0, 0, 0, 0));
        assertThat(classes.findAll().stream().filter(k -> SCHOOL.equals(k.getSchoolId()))).hasSize(3);
        assertThat(users.findBySchoolIdAndRole(SCHOOL, "TEACHER")).hasSize(2);
        assertThat(assignments.findAll().stream().filter(a -> SCHOOL.equals(a.getSchoolId()))).hasSize(3);
        assertThat(childRows.findAll().stream().filter(c -> SCHOOL.equals(c.getSchoolId()))).isEmpty();
    }

    private List<String> slotsOf(String email) {
        String teacherId = users.findBySchoolIdAndRole(SCHOOL, "TEACHER").stream()
                .filter(u -> email.equalsIgnoreCase(u.getEmail())).findFirst().orElseThrow().getId();
        var names = new java.util.LinkedHashMap<String, String>();
        classes.findAll().forEach(k -> names.put(k.getId(), k.getName()));
        return assignments.findAll().stream()
                .filter(a -> SCHOOL.equals(a.getSchoolId()) && teacherId.equals(a.getTeacherId()))
                .map((TeachingAssignmentEntity a) -> names.get(a.getClassId()) + " · " + a.getSubject()).sorted().toList();
    }
}
