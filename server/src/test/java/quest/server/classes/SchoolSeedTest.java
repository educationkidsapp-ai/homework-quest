package quest.server.classes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import quest.server.auth.TeacherRepository;
import quest.server.config.ApiException;
import quest.server.tenancy.Entities.TeachingAssignmentEntity;

/**
 * What the seed is worth is that it goes in through the Admin services: a school it loads is a school an Admin could
 * have typed, join codes and all. It is loaded once for the whole class — 600 children is not something to write four
 * times — into a school of this test's own, because the suite shares one H2 database.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchoolSeedTest extends ClassesTestSupport {
    private static final String SCHOOL = "seed-school";
    /** The first row of `teachers.csv`; the load in {@link #loadTheSchool} deliberately runs without a password. */
    private static final String SEEDED_TEACHER = "sara.al-harbi@school.test";
    private static final String STAFF_PASSWORD = "seed-staff-password";

    @Autowired SchoolSeed seed;
    @Autowired TeacherRepository profiles;

    private SchoolSeed.Counts first;

    @Override String prefix() { return SCHOOL; }

    @BeforeAll void loadTheSchool() {
        school(SCHOOL, "Seed School", "SEED01");
        first = seed.load(SCHOOL, null);                                        // no staff password: that is a test of its own
    }

    @AfterAll void takeItBackOut() {
        removeSeed();
        var staff = users.findBySchoolIdAndRole(SCHOOL, "TEACHER");
        profiles.deleteAll(profiles.findAllById(staff.stream().map(u -> u.getId()).toList()));
        users.deleteAll(staff);
    }

    @Test void loads_thirty_classes_forty_teachers_and_six_hundred_children() {
        assertThat(first).isEqualTo(new SchoolSeed.Counts(30, 40, 60, 600));

        var sections = classes.findAll().stream().filter(k -> SCHOOL.equals(k.getSchoolId())).toList();
        assertThat(sections).hasSize(30);
        assertThat(sections).allSatisfy(k -> assertThat(k.getJoinCode()).isNotBlank());
        assertThat(users.findBySchoolIdAndRole(SCHOOL, "TEACHER")).hasSize(40);

        var roster = new LinkedHashMap<String, Integer>();
        var names = new LinkedHashMap<String, Integer>();
        for (var child : childRows.findAll()) {
            if (!SCHOOL.equals(child.getSchoolId())) continue;
            roster.merge(child.getClassId(), 1, Integer::sum);
            names.merge(child.getClassId() + "/" + child.getName().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        assertThat(roster.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(600);
        assertThat(roster).hasSize(30).allSatisfy((classId, count) -> assertThat(count).isBetween(15, 25));
        assertThat(names.values()).as("no child is on the same roster twice").allMatch(count -> count == 1);
    }

    @Test void every_class_is_taught_by_exactly_one_teacher_per_subject() {
        var mine = assignments.findAll().stream().filter(a -> SCHOOL.equals(a.getSchoolId())).toList();
        assertThat(mine).hasSize(60);

        var perSlot = new LinkedHashMap<String, Integer>();
        for (var a : mine) perSlot.merge(a.getClassId() + "/" + a.getSubject(), 1, Integer::sum);
        assertThat(perSlot).hasSize(60).allSatisfy((slot, count) -> assertThat(count).isEqualTo(1));
        assertThat(mine.stream().map(TeachingAssignmentEntity::getClassId).distinct()).hasSize(30);
        assertThat(mine.stream().map(TeachingAssignmentEntity::getSubject).distinct()).containsExactlyInAnyOrder("math", "english");
        assertThat(mine.stream().map(TeachingAssignmentEntity::getTeacherId).distinct()).hasSize(40);
    }

    @Test void a_second_run_writes_nothing() {
        assertThat(seed.load(SCHOOL)).isEqualTo(new SchoolSeed.Counts(0, 0, 0, 0));

        assertThat(classes.findAll().stream().filter(k -> SCHOOL.equals(k.getSchoolId()))).hasSize(30);
        assertThat(users.findBySchoolIdAndRole(SCHOOL, "TEACHER")).hasSize(40);
        assertThat(assignments.findAll().stream().filter(a -> SCHOOL.equals(a.getSchoolId()))).hasSize(60);
        assertThat(childRows.findAll().stream().filter(c -> SCHOOL.equals(c.getSchoolId()))).hasSize(600);
    }

    /**
     * QA is seeded before SEED_STAFF_PASSWORD exists as often as not, so the run that finally carries it has to reach
     * the teachers an earlier run created — a password that only ever lands on new rows is a password e2e cannot use.
     */
    @Test void a_later_run_gives_the_staff_password_to_teachers_already_seeded() throws Exception {
        assertThat(seed.load(SCHOOL, STAFF_PASSWORD)).isEqualTo(new SchoolSeed.Counts(0, 0, 0, 0));

        var session = json(mvc.perform(post("/auth/sign-in").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + SEEDED_TEACHER + "\",\"password\":\"" + STAFF_PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(session.get("role").asText()).isEqualTo("TEACHER");
        assertThat(session.get("schoolId").asText()).isEqualTo(SCHOOL);
        assertThat(session.get("mustChangePassword").asBoolean()).as("e2e signs in without a first-login dance").isFalse();
    }

    @Test void a_malformed_row_fails_the_load_naming_its_line() {
        String file = "fullName,email,subjects,curriculum\nSara Al Harbi,sara@school.test,math,british\nbroken\n";
        assertThatThrownBy(() -> SchoolSeed.parse("teachers.csv", file, 4))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("seed/teachers.csv line 3")
                .hasMessageContaining("4 columns expected, 1 found");
    }
}
