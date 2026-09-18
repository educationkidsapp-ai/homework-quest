package quest.server.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import quest.server.ApiTestSupport;

/**
 * `V7__sections.sql`'s backfill, run against rows that look the way pre-V7 rows looked.
 *
 * <p>It has to be a test of its own because Flyway applies V7 to an <em>empty</em> database — both here and in the
 * CI migration check — so the backfill itself never touches a row in either place and would ship unexercised. The
 * statements are read out of the migration file rather than copied, so the test cannot drift from what actually
 * runs, and they are executed twice: once over the legacy rows, and once more to prove the guards make a second run
 * a no-op, which is what "idempotent for existing QA data" means.
 */
class SectionBackfillTest extends ApiTestSupport {
    private static final Path MIGRATION = Path.of("src/main/resources/db/migration/V7__sections.sql");
    private static final String SCHOOL = "bf-school", PREFIX = "bf-";

    @Autowired JdbcTemplate jdbc;
    @Autowired ClassRepository classes;
    @Autowired TeachingAssignmentRepository assignments;

    @AfterEach void cleanUp() {
        jdbc.update("DELETE FROM teaching_assignments WHERE school_id = ?", SCHOOL);
        jdbc.update("DELETE FROM children WHERE school_id = ?", SCHOOL);
        jdbc.update("DELETE FROM lessons WHERE school_id = ?", SCHOOL);
        jdbc.update("DELETE FROM classes WHERE school_id = ?", SCHOOL);
        jdbc.update("DELETE FROM users WHERE id LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM schools WHERE id = ?", SCHOOL);
    }

    @Test void one_section_per_course_takes_over_the_lessons_children_and_teachers_of_the_old_rows() throws Exception {
        school();
        user(PREFIX + "sara"); user(PREFIX + "maryam");
        // Three pre-V7 rows in British Grade 1 — the shape V4 produced: one per (subject, teacher), no name, no code.
        legacyClass(PREFIX + "a-math", "british", 1, "math", PREFIX + "sara");
        legacyClass(PREFIX + "b-english", "british", 1, "english", PREFIX + "maryam");
        legacyClass(PREFIX + "c-free", "british", 1, "science", null);
        legacyClass(PREFIX + "d-grade2", "british", 2, "math", PREFIX + "sara");
        lesson(PREFIX + "lesson-1", PREFIX + "a-math", "british", 1, "math");
        lesson(PREFIX + "lesson-2", PREFIX + "b-english", "british", 1, "english");
        child(PREFIX + "child-1", "british", 1);
        child(PREFIX + "child-2", "british", 2);

        backfill();

        // the group's first row by id becomes "1A" with a code; the others stay, inactive and unnamed
        var section = classes.findById(PREFIX + "a-math").orElseThrow();
        assertThat(section.getName()).isEqualTo("1A");
        assertThat(section.getJoinCode()).isNotBlank().hasSize(6);
        assertThat(section.isActive()).isTrue();
        assertThat(section.getSubject()).as("the old columns are left exactly as they were").isEqualTo("math");
        for (String legacy : List.of(PREFIX + "b-english", PREFIX + "c-free")) {
            var row = classes.findById(legacy).orElseThrow();
            assertThat(row.getName()).isNull();
            assertThat(row.isSection()).isFalse();
            assertThat(row.isActive()).isFalse();
        }
        assertThat(classes.findById(PREFIX + "d-grade2").orElseThrow().getName()).as("grade 2 is its own group").isEqualTo("2A");

        // every lesson of the group now points at the section, and carries the teacher of the row it came from
        assertThat(jdbc.queryForObject("SELECT class_id FROM lessons WHERE id = ?", String.class, PREFIX + "lesson-1")).isEqualTo(PREFIX + "a-math");
        assertThat(jdbc.queryForObject("SELECT class_id FROM lessons WHERE id = ?", String.class, PREFIX + "lesson-2")).isEqualTo(PREFIX + "a-math");
        assertThat(jdbc.queryForObject("SELECT teacher_id FROM lessons WHERE id = ?", String.class, PREFIX + "lesson-1")).isEqualTo(PREFIX + "sara");
        assertThat(jdbc.queryForObject("SELECT teacher_id FROM lessons WHERE id = ?", String.class, PREFIX + "lesson-2")).isEqualTo(PREFIX + "maryam");
        assertThat(jdbc.queryForObject("SELECT type FROM lessons WHERE id = ?", String.class, PREFIX + "lesson-1")).isEqualTo("homework");

        // one assignment per (section, subject), from the rows that named a teacher
        var mine = assignments.findByClassIdOrderBySubjectAsc(PREFIX + "a-math");
        assertThat(mine).extracting(Entities.TeachingAssignmentEntity::getSubject).containsExactly("english", "math");
        assertThat(mine).extracting(Entities.TeachingAssignmentEntity::getTeacherId).containsExactly(PREFIX + "maryam", PREFIX + "sara");
        assertThat(assignments.findByClassIdOrderBySubjectAsc(PREFIX + "c-free")).as("a row with no teacher yields none").isEmpty();

        // children land in the section of their (school, curriculum, grade)
        assertThat(jdbc.queryForObject("SELECT class_id FROM children WHERE id = ?", String.class, PREFIX + "child-1")).isEqualTo(PREFIX + "a-math");
        assertThat(jdbc.queryForObject("SELECT class_id FROM children WHERE id = ?", String.class, PREFIX + "child-2")).isEqualTo(PREFIX + "d-grade2");
    }

    @Test void running_it_again_changes_nothing() throws Exception {
        school();
        user(PREFIX + "sara");
        legacyClass(PREFIX + "a-math", "british", 1, "math", PREFIX + "sara");
        legacyClass(PREFIX + "b-english", "british", 1, "english", PREFIX + "sara");
        lesson(PREFIX + "lesson-1", PREFIX + "b-english", "british", 1, "english");
        child(PREFIX + "child-1", "british", 1);

        backfill();
        String code = classes.findById(PREFIX + "a-math").orElseThrow().getJoinCode();
        long assignmentsAfterFirst = assignments.findByClassIdOrderBySubjectAsc(PREFIX + "a-math").size();

        backfill();

        assertThat(classes.findById(PREFIX + "a-math").orElseThrow().getJoinCode()).as("a second run must not roll the code").isEqualTo(code);
        assertThat(assignments.findByClassIdOrderBySubjectAsc(PREFIX + "a-math")).hasSize((int) assignmentsAfterFirst);
        assertThat(jdbc.queryForObject("SELECT class_id FROM lessons WHERE id = ?", String.class, PREFIX + "lesson-1")).isEqualTo(PREFIX + "a-math");
        assertThat(jdbc.queryForObject("SELECT class_id FROM children WHERE id = ?", String.class, PREFIX + "child-1")).isEqualTo(PREFIX + "a-math");
    }

    // ---------------------------------------------------------------- the migration's own statements

    /** Section 4 of `V7__sections.sql`, split on `;`, executed in order — the file is the source, not a copy of it. */
    private void backfill() throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
        int from = sql.indexOf("-- ---------------------------------------------------------------- 4. backfill");
        int to = sql.indexOf("-- ---------------------------------------------------------------- 5. flags");
        assertThat(from).as("the backfill section of the migration must still be findable").isPositive();
        assertThat(to).isGreaterThan(from);
        // Comments go first and statements second: a `--` line may itself contain a `;`, and splitting before
        // stripping would cut a sentence in half and hand the tail of it to the database.
        var statements = new ArrayList<String>();
        for (String raw : stripComments(sql.substring(from, to)).split(";")) {
            if (!raw.isBlank()) statements.add(raw.trim());
        }
        assertThat(statements).hasSize(6);
        statements.forEach(jdbc::execute);
    }

    private static String stripComments(String section) {
        var out = new StringBuilder();
        for (String line : section.split("\n")) if (!line.trim().startsWith("--")) out.append(line).append('\n');
        return out.toString();
    }

    // ---------------------------------------------------------------- pre-V7 rows

    private void school() {
        jdbc.update("INSERT INTO schools (id, name, code, curriculum_options_json, grade_options_json, feature_flags_json, status, created_at)"
                + " VALUES (?, ?, ?, '[\"british\"]', '[1,2,3]', '{}', 'active', CURRENT_TIMESTAMP)", SCHOOL, "Backfill School", "BFAAAA");
    }

    private void user(String id) {
        jdbc.update("INSERT INTO users (id, school_id, email, password_hash, role, status, must_change_password, language, created_at, updated_at)"
                + " VALUES (?, ?, ?, 'x', 'TEACHER', 'active', FALSE, 'en', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", id, SCHOOL, id + "@bf.test");
    }

    /** A class as V4 wrote them: no name, no join code, a subject and (sometimes) a teacher. */
    private void legacyClass(String id, String curriculum, int grade, String subject, String teacherId) {
        jdbc.update("INSERT INTO classes (id, school_id, curriculum, grade, subject, teacher_id, created_at, active, join_code_enabled)"
                + " VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, TRUE, TRUE)", id, SCHOOL, curriculum, grade, subject, teacherId);
    }

    private void lesson(String id, String classId, String curriculum, int grade, String subject) {
        jdbc.update("INSERT INTO lessons (id, school_id, course_id, class_id, subject, date, status, version, practice_length,"
                + " source, token_usage, tokens_saved, created_at, updated_at, type)"
                + " VALUES (?, ?, ?, ?, ?, DATE '2027-05-04', 'published', 1, 7, 'pdf', 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'homework')",
                id, SCHOOL, curriculum + "/" + grade, classId, subject);
    }

    private void child(String id, String curriculum, int grade) {
        jdbc.update("INSERT INTO children (id, parent_id, school_id, name, avatar_color, curriculum, grade, languages, created_at, active)"
                + " VALUES (?, NULL, ?, ?, 'sun', ?, ?, 'en', ?, TRUE)", id, SCHOOL, id, curriculum, grade, Instant.now());
    }
}
