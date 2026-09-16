package quest.server.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import quest.server.auth.Entities.UserEntity;
import quest.server.auth.Principals;
import quest.server.auth.UserRepository;
import quest.server.content.Entities.LessonEntity;
import quest.server.content.LessonRepository;
import quest.server.schools.SchoolService;
import quest.server.tenancy.ClassRepository;
import quest.server.tenancy.Entities.ClassEntity;
import quest.server.tenancy.Entities.SchoolEntity;
import quest.server.tenancy.SchoolRepository;
import quest.server.tenancy.TenantContext;

/**
 * P3.0's reports are hand-written native SQL — `CAST(ts AS DATE)`, `||` concatenation inside a
 * `COUNT(DISTINCT …)`, a `UNION ALL` of two aggregates, `NOT EXISTS`, `LEFT JOIN … GROUP BY`, `LIKE` on a lower-cased
 * column, and `setMaxResults` — and the rest of the suite only ever runs it on H2 in PostgreSQL mode. H2's
 * compatibility mode is a good approximation and not the database QA runs on, so every one of those statements is
 * executed here against a real PostgreSQL 16 as well.
 *
 * <p>It asserts that the statements <em>run and shape an answer</em>, not what the numbers are: the figures are
 * `HomeTest`, `SchoolDataTest` and `UsageQueryCountTest`'s job, and repeating them here would only be a second place
 * to update. Skipped without Docker (this Mac), so CI is where it earns its keep.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"quest.auth.fake=true", "quest.llm.provider=fake",
        "quest.auth.jwt-secret=test-secret-test-secret-test-secret-test-secret", "spring.profiles.active=h2"})
class PostgresReportsTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    private static final String SCHOOL = "pg-school", TEACHER = "pg-teacher", MANAGER = "pg-manager";

    @Autowired HomeService home;
    @Autowired UsageService usage;
    @Autowired TeacherDirectoryService teachers;
    @Autowired SchoolClassService classes;
    @Autowired SchoolService schools;
    @Autowired SchoolRepository schoolRows;
    @Autowired ClassRepository classRows;
    @Autowired LessonRepository lessons;
    @Autowired UserRepository users;
    @Autowired TenantContext tenant;

    @Test void every_report_statement_runs_on_postgres() {
        seed();
        var school = schoolRows.findById(SCHOOL).orElseThrow();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        // §6 screen 5 and 19: the usage tab, including the `||` concatenation and the per-teacher consistency queries.
        var schoolUsage = usage.schoolUsage(school, today.minusDays(13).toString(), today.toString());
        assertThat(schoolUsage.playsPerDay()).hasSize(14);
        assertThat(schoolUsage.lessonsPublishedPerWeek()).isNotEmpty();
        assertThat(schoolUsage.teacherConsistency()).extracting(SchoolDataDto.TeacherConsistency::teacherId).contains(TEACHER);

        // Billing: the per-day token sums bucketed into months.
        var billing = usage.billing(school, 3);
        assertThat(billing.months()).hasSize(3);
        assertThat(billing.totalTokens()).isGreaterThanOrEqualTo(2_000);

        // §6 screen 10: the UNION ALL over the two cache tables and the LEFT JOIN cost-per-school.
        var platform = usage.platformUsage(null, null);
        assertThat(platform.schools()).isGreaterThanOrEqualTo(2);
        assertThat(platform.cacheHitRate()).isBetween(0.0, 1.0);
        assertThat(platform.costPerSchool()).extracting(SchoolDataDto.SchoolCost::schoolId).contains(SCHOOL);

        // §6 screens 5 and 20: the Classes tab and the staff list.
        assertThat(classes.listChecked(SCHOOL)).extracting(SchoolDataDto.SchoolClass::id).contains(SCHOOL + ":british:1:math");
        assertThat(teachers.teachers(SCHOOL)).extracting(SchoolDataDto.TeacherSummary::userId).contains(TEACHER);

        // §6 screen 1: LOWER(email) LIKE, with setMaxResults(2) behind it.
        assertThat(schools.logoByEmail("someone@pg-school.test").name()).isEqualTo("Postgres Academy");
        assertThat(schools.logoByEmail("someone@nobody.test")).isNull();

        // The Overview counts.
        assertThat(schools.toDto(school).teachers()).isEqualTo(1);
    }

    @Test void every_home_runs_on_postgres() {
        seed();
        // ADMIN across the platform, then narrowed with the school switcher, then the two scoped roles: four
        // different statement sets, including the stale-invite cutoff, `NOT EXISTS` and the today's-lesson lookup.
        assertThat(as("ADMIN", "pg-admin", null, () -> home.home(admin())).cards()).hasSize(3);
        assertThat(as("ADMIN", "pg-admin", SCHOOL, () -> home.home(admin())).schoolName()).isEqualTo("Postgres Academy");

        var teacher = as("TEACHER", TEACHER, SCHOOL, () -> home.home(new Principals.User(TEACHER, "t@pg-school.test", "TEACHER", SCHOOL)));
        assertThat(teacher.classes()).extracting(HomeDto.TeacherClassInfo::classId).contains(SCHOOL + ":british:1:math");
        assertThat(teacher.weakSkills()).isNotNull();

        var manager = as("MANAGERIAL", MANAGER, SCHOOL, () -> home.home(new Principals.User(MANAGER, "m@pg-school.test", "MANAGERIAL", SCHOOL)));
        assertThat(manager.cards()).extracting(HomeDto.HomeCard::key).containsExactly("children", "activeFamilies", "teachers");
    }

    // ---------------------------------------------------------------- fixture

    private static Principals.User admin() { return new Principals.User("pg-admin", "admin@pg.test", "ADMIN", null); }

    /** Runs the body as a dashboard user of one school, exactly as the JWT filter and the interceptor would. */
    private <T> T as(String role, String userId, String schoolId, java.util.function.Supplier<T> body) {
        tenant.set(role, "ADMIN".equals(role) ? null : schoolId, "ADMIN".equals(role) ? schoolId : null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new Principals.User(userId, userId + "@pg.test", role, schoolId), null,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        try { return body.get(); } finally { tenant.clear(); SecurityContextHolder.clearContext(); }
    }

    private void seed() {
        if (schoolRows.existsById(SCHOOL)) return;
        var school = new SchoolEntity();
        school.setId(SCHOOL); school.setName("Postgres Academy"); school.setCode("PGSCHL");
        school.setCurriculumOptionsJson("[\"british\"]"); school.setGradeOptionsJson("[1,2,3]");
        school.setStatus("active"); school.setCreatedAt(Instant.now());
        schoolRows.save(school);

        user(TEACHER, "teacher@pg-school.test", "TEACHER");
        user(MANAGER, "manager@pg-school.test", "MANAGERIAL");

        var klass = new ClassEntity();
        klass.setId(SCHOOL + ":british:1:math"); klass.setSchoolId(SCHOOL); klass.setCurriculum("british");
        klass.setGrade(1); klass.setSubject("math"); klass.setTeacherId(TEACHER); klass.setCreatedAt(Instant.now());
        classRows.save(klass);

        lesson("pg-lesson-1", LocalDate.now(ZoneOffset.UTC), 1_200);
        lesson("pg-lesson-2", LocalDate.now(ZoneOffset.UTC).minusDays(3), 900);
    }

    private void user(String id, String email, String role) {
        var u = new UserEntity();
        u.setId(id); u.setSchoolId(SCHOOL); u.setEmail(email); u.setPasswordHash("x"); u.setRole(role);
        u.setStatus("active"); u.setCreatedAt(Instant.now()); u.setUpdatedAt(Instant.now());
        users.save(u);
    }

    private void lesson(String id, LocalDate date, long tokens) {
        var l = new LessonEntity();
        l.setId(id); l.setSchoolId(SCHOOL); l.setClassId(SCHOOL + ":british:1:math"); l.setCourseId("british/1");
        l.setSubject("math"); l.setDate(date); l.setStatus("published"); l.setVersion(1); l.setTitle("Lesson " + id);
        l.setSource("pdf"); l.setTokenUsage(tokens); l.setPublishedAt(Instant.now());
        l.setCreatedAt(Instant.now()); l.setUpdatedAt(Instant.now());
        lessons.save(l);
    }
}
