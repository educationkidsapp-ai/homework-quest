package quest.server.classes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import quest.server.config.QuestProperties;
import quest.server.files.FileStore;

/**
 * `SEED_RESET=true`: QA emptied of its test data once, so the owner's acceptance environment starts from two
 * teachers and nothing else. The same three tests run twice — on H2 ({@link SeedResetTest}) and on PostgreSQL 16
 * ({@link SeedResetPostgresTest}) — because a wipe is exactly the kind of code whose `DELETE … IN (SELECT …)`
 * chains and foreign-key order can pass on one engine and fail on the other, and QA is PostgreSQL.
 *
 * <p><strong>Each subclass brings a database of its own.</strong> The rest of the suite shares one H2 and one
 * container, and these tests delete every school-scoped row there is: sharing either would poison it for everything
 * else.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class SeedResetSupport {
    /** Two schools beside `default`, as QA holds them: Al Noor and Green Valley from the P1.6 fixture. */
    private static final String NOOR = "rst-noor", GREEN = "rst-green";
    private static final List<String> SCHOOLS = List.of("default", NOOR, GREEN);
    /** One of `ContentSeed`'s three §6 lessons, which QA held with no section and which the wipe must still reach. */
    private static final String SAMPLE = "lesson-counting-by-2s";

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired FileStore files;
    @Autowired SchoolSeed seed;
    @Autowired Environment environment;

    /** The one thing SEED_RESET=true must never do: run under `prod`. The refusal is in the constructor. */
    @Test @Order(1) void a_prod_revision_configured_to_wipe_refuses_to_start() {
        var prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> new SeedReset(props(true, "acceptance"), jdbc, transactions, files, prod))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SEED_RESET=true is refused under the `prod` profile");
        assertThat(new SeedReset(props(false, "acceptance"), jdbc, transactions, files, prod)).isNotNull();
    }

    /** A SEED_PROFILE nobody has is a typo, and a typo that silently seeds the 30-class school is worse than a crash. */
    @Test @Order(2) void an_unknown_seed_profile_fails_the_start() {
        assertThatThrownBy(() -> new SeedReset(props(false, "acceptence"), jdbc, transactions, files, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SEED_PROFILE=acceptence is not a seed profile");
    }

    @Test @Order(3) void it_deletes_every_school_scoped_row_and_keeps_the_platform() {
        var blobs = fixtures();
        int flags = count("feature_flags");
        assertThat(count("lessons")).isGreaterThanOrEqualTo(3);
        assertThat(count("children")).isGreaterThanOrEqualTo(3);
        assertThat(count("schools")).isEqualTo(3);

        reset().run();

        for (String table : List.of("lessons", "lesson_steps", "source_files", "page_images", "skills", "plays", "stops",
                "parent_panels", "children", "attempts", "stop_completions", "lesson_completions", "parent_unlocks",
                "stickers", "streaks", "child_media", "parents", "teacher_questions", "teacher_question_answers",
                "announcements", "classes", "teaching_assignments", "invites", "teachers", "refresh_tokens",
                "chat_threads", "chat_messages"))
            assertThat(count(table)).as(table + " is empty").isZero();

        assertThat(jdbc.queryForList("SELECT id FROM schools", String.class)).containsExactly("default");
        assertThat(jdbc.queryForList("SELECT role FROM users", String.class)).isNotEmpty().allMatch("ADMIN"::equals);
        assertThat(jdbc.queryForList("SELECT id FROM users WHERE id = 'rst-admin'", String.class)).containsExactly("rst-admin");
        // The two schools that are gone take their flag overrides, their flag audit and their audit trail with them;
        // the default school keeps its own, which are the platform's record of itself and name nothing deleted.
        for (String table : List.of("school_feature_flags", "flag_audit", "audit_log"))
            assertThat(jdbc.queryForList("SELECT DISTINCT school_id FROM " + table + " WHERE school_id IS NOT NULL", String.class))
                    .as(table + " names no deleted school").containsExactly("default");
        for (String path : blobs) assertThat(files.get(path)).as(path + " is out of the bucket").isEmpty();

        // …and what the platform is made of stays
        assertThat(count("platform_settings")).isEqualTo(1);
        assertThat(count("feature_flags")).as("the flag defaults are the platform's, not a school's").isEqualTo(flags);
        assertThat(count("courses")).isEqualTo(6);
        assertThat(count("analysis_cache") + count("generation_cache")).as("the caches are keyed by hash, not by school").isZero();
        assertThat(jdbc.queryForObject("SELECT code FROM schools WHERE id = 'default'", String.class)).isEqualTo("HQ0001");
        assertThat(count("seed_resets")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT id FROM seed_resets", String.class)).containsExactly("once");
    }

    /**
     * The three §6 samples go with everything else — they are `default`-school lessons and the per-school pass
     * already names them, section or no section. What put them back on QA was {@link quest.server.content.ContentSeed}
     * running there at all, which {@link quest.server.content.ContentSeedProfileTest} is now the guard against.
     */
    @Test @Order(4) void the_sample_lessons_go_with_everything_else() {
        assertThat(count("lessons", "id LIKE 'lesson-%'")).as("no §6 sample survives the wipe").isZero();
        assertThat(count("plays") + count("stops") + count("parent_panels") + count("skills")).isZero();
    }

    /** A deploy that forgot to put the variable back to `false` must not wipe the owner's work on the next revision. */
    @Test @Order(5) void a_second_run_deletes_nothing_and_the_seed_adds_nothing() {
        jdbc.update("INSERT INTO parents (id, firebase_uid, email, created_at) VALUES ('rst-after', 'uid-after', 'after@test.local', CURRENT_TIMESTAMP)");

        reset().run();

        assertThat(jdbc.queryForList("SELECT id FROM parents", String.class)).containsExactly("rst-after");
        assertThat(count("seed_resets")).isEqualTo(1);

        assertThat(seed.load("default", "nine-char", false, "seed/acceptance/")).isEqualTo(new SchoolSeed.Counts(3, 2, 1, 3, 0));
        assertThat(seed.load("default", "nine-char", false, "seed/acceptance/")).isEqualTo(new SchoolSeed.Counts(0, 0, 0, 0, 0));
        assertThat(count("classes")).isEqualTo(3);
        assertThat(count("children")).isZero();
    }

    /**
     * `SEED_RESET_TOKEN`: the ledger is what makes the wipe one-shot, and this is the only handle on it — nobody has
     * `psql` against QA, so without a token a second wipe is unreachable. A value nobody has used runs once and
     * writes its own row; the same value again is as inert as the un-tokenised second run above.
     */
    @Test @Order(6) void a_new_reset_token_runs_the_wipe_again_and_the_same_one_never_does() {
        jdbc.update("INSERT INTO parents (id, firebase_uid, email, created_at) VALUES ('rst-token', 'uid-token', 'token@test.local', CURRENT_TIMESTAMP)");

        reset("2026-09-r2").run();

        assertThat(count("parents")).as("a token nobody has used wipes again").isZero();
        assertThat(jdbc.queryForList("SELECT id FROM seed_resets ORDER BY id", String.class))
                .containsExactly("once", "token:2026-09-r2");

        jdbc.update("INSERT INTO parents (id, firebase_uid, email, created_at) VALUES ('rst-keep', 'uid-keep', 'keep@test.local', CURRENT_TIMESTAMP)");
        reset("2026-09-r2").run();
        assertThat(jdbc.queryForList("SELECT id FROM parents", String.class)).containsExactly("rst-keep");
        assertThat(count("seed_resets")).isEqualTo(2);
    }

    // ---------------------------------------------------------------- the QA database as the wipe finds it

    private SeedReset reset() { return reset(null); }

    /** The wipe as one deploy configures it; `token` is `SEED_RESET_TOKEN`, and null is the original one-shot run. */
    private SeedReset reset(String token) { return new SeedReset(props(true, "acceptance", token), jdbc, transactions, files, environment); }

    private static QuestProperties props(boolean reset, String profile) { return props(reset, profile, null); }

    private static QuestProperties props(boolean reset, String profile, String token) {
        return new QuestProperties(null, null, null, null, null, null, null,
                new QuestProperties.Seed(true, profile, reset, "nine-char", token), null, null, null, null);
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    private int count(String table, String where) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class); }

    /**
     * Three schools — `default` and the two QA still holds — each with staff, a section, a teaching assignment, a
     * parent, a child, a lesson and one row in every table that hangs off a lesson or a child, plus the flag
     * override, the flag audit, the audit trail and the invitation that only a whole school's deletion takes out.
     * Returns the blob paths the rows point at.
     */
    private List<String> fixtures() {
        sql("INSERT INTO users (id, school_id, email, password_hash, role, status, must_change_password, language, created_at, updated_at)"
                + " VALUES ('rst-admin', NULL, 'rst-admin@test.local', 'x', 'ADMIN', 'active', FALSE, 'en', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        school(NOOR, "Al Noor", "RSTNOR"); school(GREEN, "Green Valley", "RSTGRN");
        var blobs = new ArrayList<String>();
        for (var schoolId : SCHOOLS) blobs.addAll(contents(schoolId, "rst-" + schoolId.replace("rst-", "")));
        sample();
        return blobs;
    }

    /**
     * One §6 sample lesson as QA holds it: seeded by `ContentSeed` before `lessons.school_id` existed, so it names
     * no school and every `WHERE school_id = ?` in the wipe walks straight past it.
     */
    private void sample() {
        // `ContentSeed` has already written the three of them into this context (it runs under `test`, as it did
        // under `qa`), so the wipe finds them exactly as QA did: `default`-school lessons with no section.
        sql("UPDATE lessons SET class_id = NULL WHERE id = '" + SAMPLE + "'");
        assertThat(count("lessons", "id = '" + SAMPLE + "'")).as("the sample lesson is there to delete").isOne();
    }

    /** One school's worth of everything the wipe walks through; `k` prefixes every id so three sets never collide. */
    private List<String> contents(String schoolId, String k) {
        var blobs = List.of(k + "/one.pdf", k + "/one.md", k + "/page-1.png", k + "/retell.webm");
        for (String path : blobs) files.put(path, "bytes".getBytes(StandardCharsets.UTF_8), "application/octet-stream");

        user(k + "-t1", schoolId, "TEACHER"); user(k + "-m1", schoolId, "MANAGERIAL");
        sql("INSERT INTO teachers (user_id, subjects_json, curriculum, grades_json, updated_at) VALUES ('" + k + "-t1', '[\"math\"]', 'british', '[1]', CURRENT_TIMESTAMP)");
        sql("INSERT INTO classes (id, school_id, curriculum, grade, name, join_code, active, join_code_enabled, created_at)"
                + " VALUES ('" + k + "-k1', '" + schoolId + "', 'british', 1, '1A British', '" + code(k) + "', TRUE, TRUE, CURRENT_TIMESTAMP)");
        sql("INSERT INTO teaching_assignments (id, school_id, teacher_id, class_id, subject, created_at)"
                + " VALUES ('" + k + "-a1', '" + schoolId + "', '" + k + "-t1', '" + k + "-k1', 'math', CURRENT_TIMESTAMP)");
        sql("INSERT INTO parents (id, firebase_uid, email, created_at) VALUES ('" + k + "-p1', 'uid-" + k + "', '" + k + "@test.local', CURRENT_TIMESTAMP)");
        sql("INSERT INTO children (id, parent_id, school_id, class_id, name, avatar_color, curriculum, grade, languages, created_at)"
                + " VALUES ('" + k + "-c1', '" + k + "-p1', '" + schoolId + "', '" + k + "-k1', '" + k + " child', 'sky', 'british', 1, 'en', CURRENT_TIMESTAMP)");
        sql("INSERT INTO lessons (id, course_id, school_id, class_id, subject, date, status, created_at, updated_at)"
                + " VALUES ('" + k + "-l1', 'british/1', '" + schoolId + "', '" + k + "-k1', 'math', CURRENT_DATE, 'published', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");

        sql("INSERT INTO lesson_steps (id, lesson_id, step, position, status, attempt, updated_at) VALUES ('" + k + "-l1:convert', '" + k + "-l1', 'convert', 1, 'done', 0, CURRENT_TIMESTAMP)");
        sql("INSERT INTO source_files (id, lesson_id, file_name, file_hash, kind, mime_type, page_count, storage_path, size_bytes, markdown_path, created_at)"
                + " VALUES ('" + k + "-f1', '" + k + "-l1', 'one.pdf', 'hash-" + k + "', 'pdf', 'application/pdf', 1, '" + k + "/one.pdf', 5, '" + k + "/one.md', CURRENT_TIMESTAMP)");
        sql("INSERT INTO page_images (id, lesson_id, page_number, storage_path, width, height) VALUES ('" + k + "-i1', '" + k + "-l1', 1, '" + k + "/page-1.png', 10, 10)");
        sql("INSERT INTO skills (id, lesson_id, name, subject, method, confidence) VALUES ('" + k + "-s1', '" + k + "-l1', 'adding', 'math', 'counting on', 0.9)");
        sql("INSERT INTO plays (id, lesson_id, level, variant, play_json, prompt_version, generated_at) VALUES ('" + k + "-y1', '" + k + "-l1', 1, 0, '{}', 'v1', CURRENT_TIMESTAMP)");
        sql("INSERT INTO stops (id, play_id, lesson_id, position, type, category, title, ingredient, content_json, parent_tip_en, parent_tip_ar)"
                + " VALUES ('" + k + "-o1', '" + k + "-y1', '" + k + "-l1', 1, 'mcq', 'practice', 'Add two', 'adding', '{}', 'en', 'ar')");
        sql("INSERT INTO parent_panels (lesson_id, panel_json, updated_at) VALUES ('" + k + "-l1', '{}', CURRENT_TIMESTAMP)");

        sql("INSERT INTO attempts (id, child_id, stop_id, lesson_id, level, answer_json, correct, attempt_number, answered_at)"
                + " VALUES ('" + k + "-at1', '" + k + "-c1', '" + k + "-o1', '" + k + "-l1', 1, '{}', TRUE, 1, CURRENT_TIMESTAMP)");
        sql("INSERT INTO stop_completions (child_id, stop_id, lesson_id, level, stars, completed_at) VALUES ('" + k + "-c1', '" + k + "-o1', '" + k + "-l1', 1, 3, CURRENT_TIMESTAMP)");
        sql("INSERT INTO lesson_completions (child_id, lesson_id, level, stars_earned, stars_total, completed_at) VALUES ('" + k + "-c1', '" + k + "-l1', 1, 3, 3, CURRENT_TIMESTAMP)");
        sql("INSERT INTO parent_unlocks (child_id, lesson_id, level) VALUES ('" + k + "-c1', '" + k + "-l1', 1)");
        sql("INSERT INTO stickers (id, child_id, sticker_key, earned_at) VALUES ('" + k + "-st1', '" + k + "-c1', 'star', CURRENT_TIMESTAMP)");
        sql("INSERT INTO streaks (child_id, current_days, last_played_date) VALUES ('" + k + "-c1', 1, CURRENT_DATE)");
        sql("INSERT INTO child_media (id, child_id, stop_id, kind, storage_path, mime_type, size_bytes, created_at)"
                + " VALUES ('" + k + "-md1', '" + k + "-c1', '" + k + "-o1', 'audio', '" + k + "/retell.webm', 'audio/webm', 5, CURRENT_TIMESTAMP)");

        sql("INSERT INTO teacher_questions (id, school_id, teacher_id, title, from_date, to_date, created_at)"
                + " VALUES ('" + k + "-q1', '" + schoolId + "', '" + k + "-t1', 'How was it?', CURRENT_DATE, CURRENT_DATE, CURRENT_TIMESTAMP)");
        sql("INSERT INTO teacher_question_answers (id, school_id, question_id, child_id, stop_id, answer_json, answered_at)"
                + " VALUES ('" + k + "-qa1', '" + schoolId + "', '" + k + "-q1', '" + k + "-c1', '" + k + "-o1', '{}', CURRENT_TIMESTAMP)");
        sql("INSERT INTO announcements (id, school_id, teacher_id, class_id, body_en, published_at, created_at)"
                + " VALUES ('" + k + "-an1', '" + schoolId + "', '" + k + "-t1', '" + k + "-k1', 'Trip on Sunday', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");

        sql("INSERT INTO invites (id, school_id, email, role, token_hash, invited_by, expires_at, created_at)"
                + " VALUES ('" + k + "-in1', '" + schoolId + "', '" + k + "-new@test.local', 'TEACHER', 'hash-" + k + "', '" + k + "-t1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        sql("INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, created_at) VALUES ('" + k + "-rt1', '" + k + "-t1', 'rt-" + k + "', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        sql("INSERT INTO school_feature_flags (school_id, flag_key, enabled, updated_by, updated_at) VALUES ('" + schoolId + "', 'complaints', TRUE, 'rst-admin', CURRENT_TIMESTAMP)");
        sql("INSERT INTO flag_audit (id, flag_key, school_id, enabled, actor_user_id, created_at) VALUES ('" + k + "-fa1', 'complaints', '" + schoolId + "', TRUE, 'rst-admin', CURRENT_TIMESTAMP)");
        sql("INSERT INTO audit_log (id, actor_user_id, action, school_id, created_at) VALUES ('" + k + "-al1', 'rst-admin', 'school.create', '" + schoolId + "', CURRENT_TIMESTAMP)");
        return blobs;
    }

    private void school(String id, String name, String code) {
        sql("INSERT INTO schools (id, name, code, curriculum_options_json, grade_options_json, theme_json, feature_flags_json, status, created_at)"
                + " VALUES ('" + id + "', '" + name + "', '" + code + "', '[\"british\"]', '[1]', '{\"primary\":\"#123456\"}', '{}', 'active', CURRENT_TIMESTAMP)");
    }

    private void user(String id, String schoolId, String role) {
        sql("INSERT INTO users (id, school_id, email, password_hash, role, status, must_change_password, language, created_at, updated_at)"
                + " VALUES ('" + id + "', '" + schoolId + "', '" + id + "@test.local', 'x', '" + role + "', 'active', FALSE, 'en', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
    }

    /** Six characters A–Z0–9, unique across `classes` on both engines. */
    private static String code(String k) {
        String letters = k.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return (letters + "000000").substring(0, 6);
    }

    private void sql(String statement) { jdbc.update(statement); }
}
