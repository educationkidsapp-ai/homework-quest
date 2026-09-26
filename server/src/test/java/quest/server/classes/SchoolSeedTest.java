package quest.server.classes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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
    /** The slot #70 moved: `assignments.csv` gave 3A British · math to Sara, it now belongs to Omar. */
    private static final String MOVED = "3A British";
    private static final String SARA = SEEDED_TEACHER;
    private static final String OMAR = "omar.nasser@school.test";
    /** The only row of `managers.csv`: the school's Management account, which no endpoint can create. */
    private static final String SEEDED_MANAGER = "manager.a@school.test";

    @Autowired SchoolSeed seed;
    @Autowired TeacherRepository profiles;
    @Autowired quest.server.tenancy.StaffScopeRepository staffScopes;

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
        users.deleteAll(users.findBySchoolIdAndRole(SCHOOL, "MANAGERIAL"));
        users.deleteAll(users.findBySchoolIdAndRole(SCHOOL, "COORDINATOR"));
        staffScopes.deleteAll(staffScopes.findAll().stream().filter(r -> SCHOOL.equals(r.getSchoolId())).toList());
    }

    @Test void loads_thirty_classes_forty_teachers_and_six_hundred_children() {
        assertThat(first).isEqualTo(new SchoolSeed.Counts(30, 40, 2, 6, 60, 600));

        var sections = classes.findAll().stream().filter(k -> SCHOOL.equals(k.getSchoolId())).toList();
        assertThat(sections).hasSize(30);
        assertThat(sections).allSatisfy(k -> assertThat(k.getJoinCode()).isNotBlank());
        assertThat(users.findBySchoolIdAndRole(SCHOOL, "TEACHER")).hasSize(40);
        // R2: one manager per department, and one coordinator per subject the school teaches, across both tracks.
        assertThat(users.findBySchoolIdAndRole(SCHOOL, "MANAGERIAL")).hasSize(2).anySatisfy(m -> {
            assertThat(m.getEmail()).isEqualTo(SEEDED_MANAGER);
            assertThat(m.getDisplayName()).isEqualTo("Huda Salem");
            assertThat(m.getStatus()).isEqualTo("active");
        });
        assertThat(scopesOf("MANAGERIAL")).containsExactlyInAnyOrder("null/british", "null/american");
        // One per subject the platform has, each across both tracks: the two the school teaches see classes, the
        // rest exist so the role can be signed in as and read on QA.
        assertThat(users.findBySchoolIdAndRole(SCHOOL, "COORDINATOR")).hasSize(6)
                .extracting(u -> u.getEmail()).containsExactlyInAnyOrder(
                        "coordinator.math@school.test", "coordinator.english@school.test", "coordinator.science@school.test",
                        "coordinator.french@school.test", "coordinator.religion@school.test", "coordinator.arabic@school.test");
        assertThat(scopesOf("COORDINATOR")).containsExactlyInAnyOrder("math/null", "english/null", "science/null",
                "french/null", "religion/null", "arabic/null");

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
        assertThat(seed.load(SCHOOL)).isEqualTo(new SchoolSeed.Counts(0, 0, 0, 0, 0, 0));

        assertThat(classes.findAll().stream().filter(k -> SCHOOL.equals(k.getSchoolId()))).hasSize(30);
        assertThat(users.findBySchoolIdAndRole(SCHOOL, "TEACHER")).hasSize(40);
        assertThat(users.findBySchoolIdAndRole(SCHOOL, "MANAGERIAL")).hasSize(2);
        assertThat(users.findBySchoolIdAndRole(SCHOOL, "COORDINATOR")).hasSize(6);
        assertThat(staffScopes.findAll().stream().filter(r -> SCHOOL.equals(r.getSchoolId()))).hasSize(8);
        assertThat(assignments.findAll().stream().filter(a -> SCHOOL.equals(a.getSchoolId()))).hasSize(60);
        assertThat(childRows.findAll().stream().filter(c -> SCHOOL.equals(c.getSchoolId()))).hasSize(600);
    }

    /**
     * QA is seeded before SEED_STAFF_PASSWORD exists as often as not, so the run that finally carries it has to reach
     * the teachers an earlier run created — a password that only ever lands on new rows is a password e2e cannot use.
     */
    @Test void a_later_run_gives_the_staff_password_to_teachers_already_seeded() throws Exception {
        assertThat(seed.load(SCHOOL, STAFF_PASSWORD)).isEqualTo(new SchoolSeed.Counts(0, 0, 0, 0, 0, 0));

        assertThat(signIn(SEEDED_TEACHER).get("role").asText()).isEqualTo("TEACHER");
        // The Management account is on the same password, and for the same reason: e2e has to be able to be her.
        assertThat(signIn(SEEDED_MANAGER).get("role").asText()).isEqualTo("MANAGERIAL");
        // …and so is the coordinator, for the same reason (R2).
        assertThat(signIn("coordinator.math@school.test").get("role").asText()).isEqualTo("COORDINATOR");
    }

    /** `subject/curriculum` for every `staff_scopes` row of one role of this school, nulls spelled out. */
    private java.util.List<String> scopesOf(String role) {
        var ids = users.findBySchoolIdAndRole(SCHOOL, role).stream().map(u -> u.getId()).toList();
        return staffScopes.findAll().stream().filter(r -> ids.contains(r.getUserId()))
                .map(r -> r.getSubject() + "/" + r.getCurriculum()).toList();
    }

    /** Signs in with the shared staff password and asserts what every seeded account has in common. */
    private com.fasterxml.jackson.databind.JsonNode signIn(String email) throws Exception {
        var session = json(mvc.perform(post("/auth/sign-in").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + STAFF_PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(session.get("schoolId").asText()).isEqualTo(SCHOOL);
        assertThat(session.get("mustChangePassword").asBoolean()).as("e2e signs in without a first-login dance").isFalse();
        return session;
    }

    /**
     * QA is not re-created between deploys: a run whose `assignments.csv` has moved a slot meets the school holding
     * the old one. #70 moved 3A/3B British · math from Sara to Omar and the seed answered 409 from a
     * `CommandLineRunner`, which is a Cloud Run revision that never answers `/health`. The file is the truth now.
     */
    @Test void a_slot_the_file_moved_is_reconciled_on_the_next_run() {
        String sara = teacherId(SARA), omar = teacherId(OMAR);
        move(classId(MOVED), "math", sara);                                     // the school as the previous deploy left it

        assertThat(seed.load(SCHOOL)).isEqualTo(new SchoolSeed.Counts(0, 0, 0, 0, 1, 0));

        assertThat(slotsOf(omar)).containsExactly("3A British · math", "3B British · math");
        assertThat(slotsOf(sara)).containsExactly("1A British · math", "1B British · math");
        assertThat(assignments.findAll().stream().filter(a -> SCHOOL.equals(a.getSchoolId()))).hasSize(60);
    }

    /**
     * The same slot held by a colleague an Admin typed in by hand is hers: the seed says so at WARN and takes neither
     * the assignment off her nor the server down. Nothing else in the file moves because of it.
     */
    @Test void a_slot_an_admin_gave_away_is_left_alone_and_does_not_throw() {
        String omar = teacherId(OMAR), moved = classId(MOVED);
        var outsider = user(SCHOOL + "-outsider", SCHOOL, "admin.made@school.test", "TEACHER");
        move(moved, "math", outsider.getId());

        assertThatNoException().isThrownBy(() -> seed.load(SCHOOL));

        assertThat(slotsOf(outsider.getId())).containsExactly("3A British · math");
        assertThat(slotsOf(omar)).as("the file's other rows are untouched").containsExactly("3B British · math");

        assignments.deleteAll(assignments.findAll().stream().filter(a -> outsider.getId().equals(a.getTeacherId())).toList());
        users.delete(outsider);
        assertThat(seed.load(SCHOOL)).isEqualTo(new SchoolSeed.Counts(0, 0, 0, 0, 1, 0));
        assertThat(slotsOf(omar)).containsExactly("3A British · math", "3B British · math");
    }

    /** A fixture nobody can load is a QA nuisance; a seed that rethrows makes it an outage. Startup goes on. */
    @Test void a_broken_load_is_logged_and_never_aborts_startup() {
        assertThatNoException().isThrownBy(() -> seed.load(SCHOOL + "-missing", null, true));
        assertThat(classes.findAll().stream().filter(k -> (SCHOOL + "-missing").equals(k.getSchoolId()))).isEmpty();
    }

    @Test void a_malformed_row_fails_the_load_naming_its_line() {
        String file = "fullName,email,subjects,curriculum\nSara Al Harbi,sara@school.test,math,british\nbroken\n";
        assertThatThrownBy(() -> SchoolSeed.parse("teachers.csv", file, 4))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("seed/teachers.csv line 3")
                .hasMessageContaining("4 columns expected, 1 found");
    }
    // ---------------------------------------------------------------- reading the seeded school back

    private String teacherId(String email) {
        return users.findBySchoolIdAndRole(SCHOOL, "TEACHER").stream().filter(u -> email.equalsIgnoreCase(u.getEmail()))
                .findFirst().orElseThrow().getId();
    }

    private String classId(String name) {
        return classes.findAll().stream().filter(k -> SCHOOL.equals(k.getSchoolId()) && name.equals(k.getName()))
                .findFirst().orElseThrow().getId();
    }

    /** What she teaches, named the way the files name it. */
    private List<String> slotsOf(String teacherId) {
        var names = new LinkedHashMap<String, String>();
        classes.findAll().forEach(k -> names.put(k.getId(), k.getName()));
        return assignments.findAll().stream().filter(a -> SCHOOL.equals(a.getSchoolId()) && teacherId.equals(a.getTeacherId()))
                .map(a -> names.get(a.getClassId()) + " · " + a.getSubject()).sorted().toList();
    }

    /** Hands a slot to somebody else behind the service's back — the state a previous deploy leaves behind. */
    private void move(String classId, String subject, String teacherId) {
        assignments.deleteAll(assignments.findAll().stream()
                .filter(a -> SCHOOL.equals(a.getSchoolId()) && classId.equals(a.getClassId()) && subject.equals(a.getSubject())).toList());
        var row = new TeachingAssignmentEntity();
        row.setId(UUID.randomUUID().toString()); row.setSchoolId(SCHOOL); row.setTeacherId(teacherId);
        row.setClassId(classId); row.setSubject(subject); row.setCreatedAt(Instant.now());
        assignments.save(row);
    }
}
