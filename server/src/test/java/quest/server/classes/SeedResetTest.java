package quest.server.classes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import quest.server.config.QuestProperties;
import quest.server.files.FileStore;

/**
 * `SEED_RESET=true`: QA emptied of its test data once, so the owner's acceptance environment starts from two
 * teachers and nothing else.
 *
 * <p><strong>It runs against a database of its own.</strong> The suite shares one in-memory H2 (`questtest`) and this
 * test deletes every school-scoped row there is, so it names its own URL — a context of its own is the price of
 * testing a wipe honestly rather than testing a narrowed version of it.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:seedreset;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SeedResetTest {
    private static final String NOOR = "rst-noor";
    private static final List<String> BLOBS = List.of("rst/one.pdf", "rst/one.md", "rst/page-1.png", "rst/retell.webm");

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired FileStore files;
    @Autowired SchoolSeed seed;
    @Autowired Environment environment;

    /** The one thing SEED_RESET=true must never do: run under `prod`. The refusal is in the constructor. */
    @Test @Order(1) void a_prod_revision_configured_to_wipe_refuses_to_start() {
        var prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> new SeedReset(props(true), jdbc, transactions, files, prod))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SEED_RESET=true is refused under the `prod` profile");
        assertThat(new SeedReset(props(false), jdbc, transactions, files, prod)).isNotNull();
    }

    @Test @Order(2) void it_deletes_every_school_scoped_row_and_keeps_the_platform() {
        fixtures();
        int flags = count("feature_flags");
        assertThat(count("lessons")).isPositive();
        assertThat(count("children")).isPositive();

        reset().run();

        for (String table : List.of("lessons", "lesson_steps", "source_files", "page_images", "skills", "plays", "stops",
                "parent_panels", "children", "attempts", "stop_completions", "lesson_completions", "parent_unlocks",
                "stickers", "streaks", "child_media", "parents", "teacher_questions", "teacher_question_answers",
                "announcements", "classes", "teaching_assignments", "invites", "teachers"))
            assertThat(count(table)).as(table + " is empty").isZero();

        assertThat(jdbc.queryForList("SELECT id FROM schools", String.class)).containsExactly("default");
        assertThat(jdbc.queryForList("SELECT role FROM users", String.class)).containsExactly("ADMIN");
        assertThat(count("school_feature_flags")).isZero();
        assertThat(jdbc.queryForList("SELECT school_id FROM audit_log WHERE school_id = ?", String.class, NOOR)).isEmpty();
        for (String path : BLOBS) assertThat(files.get(path)).as(path + " is out of the bucket").isEmpty();

        // …and what the platform is made of stays
        assertThat(count("platform_settings")).isEqualTo(1);
        assertThat(count("feature_flags")).as("the flag defaults are the platform's, not a school's").isEqualTo(flags);
        assertThat(count("courses")).isEqualTo(6);
        assertThat(count("analysis_cache") + count("generation_cache")).as("the caches are keyed by hash, not by school").isZero();
        assertThat(jdbc.queryForObject("SELECT code FROM schools WHERE id = 'default'", String.class)).isEqualTo("HQ0001");
        assertThat(count("seed_resets")).isEqualTo(1);
    }

    /** A deploy that forgot to put the variable back to `false` must not wipe the owner's work on the next revision. */
    @Test @Order(3) void a_second_run_deletes_nothing_and_the_seed_adds_nothing() {
        jdbc.update("INSERT INTO parents (id, firebase_uid, email, created_at) VALUES ('rst-after', 'uid-after', 'after@test.local', CURRENT_TIMESTAMP)");

        reset().run();

        assertThat(jdbc.queryForList("SELECT id FROM parents", String.class)).containsExactly("rst-after");
        assertThat(count("seed_resets")).isEqualTo(1);

        assertThat(seed.load("default", "nine-char", false, "seed/acceptance/")).isEqualTo(new SchoolSeed.Counts(3, 2, 3, 0));
        assertThat(seed.load("default", "nine-char", false, "seed/acceptance/")).isEqualTo(new SchoolSeed.Counts(0, 0, 0, 0));
        assertThat(count("classes")).isEqualTo(3);
        assertThat(count("children")).isZero();
    }

    // ---------------------------------------------------------------- the QA database as the wipe finds it

    private SeedReset reset() { return new SeedReset(props(true), jdbc, transactions, files, environment); }

    private static QuestProperties props(boolean reset) {
        return new QuestProperties(null, null, null, null, null, null, null,
                new QuestProperties.Seed(true, "acceptance", reset, "nine-char"), null, null, null, null);
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    /** A second school with staff, sections, a lesson and a child, and the default school holding the same again. */
    private void fixtures() {
        for (String path : BLOBS) files.put(path, "bytes".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
        sql("INSERT INTO schools (id, name, code, curriculum_options_json, grade_options_json, theme_json, feature_flags_json, status, created_at)"
                + " VALUES ('" + NOOR + "', 'Al Noor', 'RSTNOR', '[\"british\"]', '[1]', '{\"primary\":\"#123456\"}', '{}', 'active', CURRENT_TIMESTAMP)");
        user("rst-t1", NOOR, "TEACHER"); user("rst-m1", NOOR, "MANAGERIAL"); user("rst-t2", "default", "TEACHER");
        sql("INSERT INTO teachers (user_id, subjects_json, curriculum, grades_json, updated_at) VALUES ('rst-t1', '[\"math\"]', 'british', '[1]', CURRENT_TIMESTAMP)");
        sql("INSERT INTO teachers (user_id, subjects_json, curriculum, grades_json, updated_at) VALUES ('rst-t2', '[\"math\"]', 'british', '[1]', CURRENT_TIMESTAMP)");
        section("rst-k1", NOOR, "1A British"); section("rst-k2", "default", "1A British");
        sql("INSERT INTO teaching_assignments (id, school_id, teacher_id, class_id, subject, created_at) VALUES ('rst-a1', '" + NOOR + "', 'rst-t1', 'rst-k1', 'math', CURRENT_TIMESTAMP)");
        sql("INSERT INTO teaching_assignments (id, school_id, teacher_id, class_id, subject, created_at) VALUES ('rst-a2', 'default', 'rst-t2', 'rst-k2', 'math', CURRENT_TIMESTAMP)");
        sql("INSERT INTO parents (id, firebase_uid, email, created_at) VALUES ('rst-p1', 'uid-p1', 'p1@test.local', CURRENT_TIMESTAMP)");
        child("rst-c1", NOOR, "rst-k1", "rst-p1"); child("rst-c2", "default", "rst-k2", null);
        lesson("rst-l1", NOOR, "rst-k1"); lesson("rst-l2", "default", "rst-k2");

        sql("INSERT INTO lesson_steps (id, lesson_id, step, position, status, attempt, updated_at) VALUES ('rst-l1:convert', 'rst-l1', 'convert', 1, 'done', 0, CURRENT_TIMESTAMP)");
        sql("INSERT INTO source_files (id, lesson_id, file_name, file_hash, kind, mime_type, page_count, storage_path, size_bytes, markdown_path, created_at)"
                + " VALUES ('rst-f1', 'rst-l1', 'one.pdf', 'hash', 'pdf', 'application/pdf', 1, 'rst/one.pdf', 5, 'rst/one.md', CURRENT_TIMESTAMP)");
        sql("INSERT INTO page_images (id, lesson_id, page_number, storage_path, width, height) VALUES ('rst-i1', 'rst-l1', 1, 'rst/page-1.png', 10, 10)");
        sql("INSERT INTO skills (id, lesson_id, name, subject, method, confidence) VALUES ('rst-s1', 'rst-l1', 'adding', 'math', 'counting on', 0.9)");
        sql("INSERT INTO plays (id, lesson_id, level, variant, play_json, prompt_version, generated_at) VALUES ('rst-y1', 'rst-l1', 1, 0, '{}', 'v1', CURRENT_TIMESTAMP)");
        sql("INSERT INTO stops (id, play_id, lesson_id, position, type, category, title, ingredient, content_json, parent_tip_en, parent_tip_ar)"
                + " VALUES ('rst-o1', 'rst-y1', 'rst-l1', 1, 'mcq', 'practice', 'Add two', 'adding', '{}', 'en', 'ar')");
        sql("INSERT INTO parent_panels (lesson_id, panel_json, updated_at) VALUES ('rst-l1', '{}', CURRENT_TIMESTAMP)");

        sql("INSERT INTO attempts (id, child_id, stop_id, lesson_id, level, answer_json, correct, attempt_number, answered_at)"
                + " VALUES ('rst-at1', 'rst-c1', 'rst-o1', 'rst-l1', 1, '{}', TRUE, 1, CURRENT_TIMESTAMP)");
        sql("INSERT INTO stop_completions (child_id, stop_id, lesson_id, level, stars, completed_at) VALUES ('rst-c1', 'rst-o1', 'rst-l1', 1, 3, CURRENT_TIMESTAMP)");
        sql("INSERT INTO lesson_completions (child_id, lesson_id, level, stars_earned, stars_total, completed_at) VALUES ('rst-c1', 'rst-l1', 1, 3, 3, CURRENT_TIMESTAMP)");
        sql("INSERT INTO parent_unlocks (child_id, lesson_id, level) VALUES ('rst-c1', 'rst-l1', 1)");
        sql("INSERT INTO stickers (id, child_id, sticker_key, earned_at) VALUES ('rst-st1', 'rst-c1', 'star', CURRENT_TIMESTAMP)");
        sql("INSERT INTO streaks (child_id, current_days, last_played_date) VALUES ('rst-c1', 1, CURRENT_DATE)");
        sql("INSERT INTO child_media (id, child_id, stop_id, kind, storage_path, mime_type, size_bytes, created_at)"
                + " VALUES ('rst-md1', 'rst-c1', 'rst-o1', 'audio', 'rst/retell.webm', 'audio/webm', 5, CURRENT_TIMESTAMP)");

        sql("INSERT INTO teacher_questions (id, school_id, teacher_id, title, from_date, to_date, created_at)"
                + " VALUES ('rst-q1', '" + NOOR + "', 'rst-t1', 'How was it?', CURRENT_DATE, CURRENT_DATE, CURRENT_TIMESTAMP)");
        sql("INSERT INTO teacher_question_answers (id, school_id, question_id, child_id, stop_id, answer_json, answered_at)"
                + " VALUES ('rst-qa1', '" + NOOR + "', 'rst-q1', 'rst-c1', 'rst-o1', '{}', CURRENT_TIMESTAMP)");
        sql("INSERT INTO announcements (id, school_id, teacher_id, class_id, body_en, published_at, created_at)"
                + " VALUES ('rst-an1', '" + NOOR + "', 'rst-t1', 'rst-k1', 'Trip on Sunday', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");

        sql("INSERT INTO invites (id, school_id, email, role, token_hash, invited_by, expires_at, created_at)"
                + " VALUES ('rst-in1', '" + NOOR + "', 'new@test.local', 'TEACHER', 'hash-rst', 'rst-t1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        sql("INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, created_at) VALUES ('rst-rt1', 'rst-t1', 'rt-hash', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        sql("INSERT INTO school_feature_flags (school_id, flag_key, enabled, updated_by, updated_at) VALUES ('" + NOOR + "', 'complaints', TRUE, 'admin', CURRENT_TIMESTAMP)");
        sql("INSERT INTO flag_audit (id, flag_key, school_id, enabled, actor_user_id, created_at) VALUES ('rst-fa1', 'complaints', '" + NOOR + "', TRUE, 'admin', CURRENT_TIMESTAMP)");
        sql("INSERT INTO audit_log (id, actor_user_id, action, school_id, created_at) VALUES ('rst-al1', 'admin', 'school.create', '" + NOOR + "', CURRENT_TIMESTAMP)");
    }

    private void user(String id, String schoolId, String role) {
        sql("INSERT INTO users (id, school_id, email, password_hash, role, status, must_change_password, language, created_at, updated_at)"
                + " VALUES ('" + id + "', '" + schoolId + "', '" + id + "@test.local', 'x', '" + role + "', 'active', FALSE, 'en', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
    }

    private void section(String id, String schoolId, String name) {
        sql("INSERT INTO classes (id, school_id, curriculum, grade, name, join_code, active, join_code_enabled, created_at)"
                + " VALUES ('" + id + "', '" + schoolId + "', 'british', 1, '" + name + "', '" + id.toUpperCase().replace("-", "") + "', TRUE, TRUE, CURRENT_TIMESTAMP)");
    }

    private void child(String id, String schoolId, String classId, String parentId) {
        sql("INSERT INTO children (id, parent_id, school_id, class_id, name, avatar_color, curriculum, grade, languages, created_at)"
                + " VALUES ('" + id + "', " + (parentId == null ? "NULL" : "'" + parentId + "'") + ", '" + schoolId + "', '" + classId
                + "', '" + id + "', 'sky', 'british', 1, 'en', CURRENT_TIMESTAMP)");
    }

    private void lesson(String id, String schoolId, String classId) {
        sql("INSERT INTO lessons (id, course_id, school_id, class_id, subject, date, status, created_at, updated_at)"
                + " VALUES ('" + id + "', 'british/1', '" + schoolId + "', '" + classId + "', 'math', CURRENT_DATE, 'published', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
    }

    private void sql(String statement) { jdbc.update(statement); }
}
