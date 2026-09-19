package quest.server.classes;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import quest.server.config.QuestProperties;
import quest.server.files.FileStore;
import quest.server.tenancy.TenantContext;

/**
 * `quest.seed.reset` (SEED_RESET): <strong>empty QA of its test data once, before the seed runs.</strong> The owner's
 * acceptance environment starts from two teachers and nothing else, and QA is never re-created between deploys — it
 * still holds the P1.6 schools, their staff, their children and every lesson anyone ever generated. This is the one
 * thing that takes them out.
 *
 * <p><strong>What it deletes.</strong> Every school-scoped row of every school: lessons and everything hanging off
 * them (steps, source files, page images, skills, plays, stops, parent panels), children and everything keyed by a
 * child (attempts, stop and lesson completions, parent unlocks, stickers, streaks, media), teacher questions, their
 * answers, announcements, sections, teaching assignments, staff invitations, and the staff accounts themselves —
 * TEACHER and MANAGERIAL, never the platform ADMIN. Then the schools that are not `default` go entirely: Al Noor,
 * Green Valley, their flag overrides, their audit trail, their theme. The blobs behind the uploads, the rendered page
 * images, the extracted Markdown and the children's recordings are deleted from the bucket with the rows.
 *
 * <p><strong>What it keeps.</strong> The `default` school and its theme, the platform ADMIN, `platform_settings`, the
 * feature-flag defaults, the `courses` reference rows, and the two permanent caches — `analysis_cache` and
 * `generation_cache` are keyed by a content hash and by prompt version, not by a school, so keeping them costs the
 * owner nothing and saves QA a re-analysis of every file anyone uploads next.
 *
 * <p><strong>Parents go too.</strong> A parent is a Firebase account with a `parents` row and children hanging off
 * it; her children are school rows, so the row would be left pointing at nothing. Everyone re-registers in the app
 * after the wipe — `docs/runbook.md` says so, because the owner is the first person it happens to.
 *
 * <p><strong>The three §6 sample lessons go with them</strong> (`lesson-counting-by-2s`, `lesson-sh-sound`,
 * `lesson-hot-soup-1`): they are `default`-school lessons and the pass above already names them. What they needed
 * was not another statement here but {@link quest.server.content.ContentSeed} not running on QA at all — it is a
 * `CommandLineRunner` ordered after this one, so it wrote all three back the moment the wipe had finished, and the
 * owner's acceptance environment started with three lessons nobody had written.
 *
 * <p><strong>One-shot.</strong> A deploy that forgets to put SEED_RESET back to `false` must not wipe the owner's
 * work on the next revision, so the run writes a `seed_resets` row and every later start finds it and does nothing.
 * The second start therefore deletes nothing and — the seed reconciling rather than adding — seeds nothing either.
 * `SEED_RESET_TOKEN` is the way to ask for another wipe: the row is named after the token, so a value nobody has
 * used runs the wipe once, and the same value again does nothing.
 *
 * <p><strong>Never under `prod`.</strong> The check is in the constructor, so a production revision configured with
 * SEED_RESET=true fails to start with the reason rather than starting and emptying the database.
 *
 * <p><strong>Why SQL.</strong> Every other query in the server is a repository query scoped by the `school` Hibernate
 * filter, because every other query runs for a caller. This one runs for no caller, at startup, over every school in
 * turn and over tables no entity is mapped to at all — so it names `school_id` in each statement itself, one
 * transaction per school, and logs a row count per table.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SeedReset implements CommandLineRunner {
    /** The environment variable, for the message the refusal under `prod` prints. */
    static final String ENV = "SEED_RESET";
    private static final Logger log = LoggerFactory.getLogger(SeedReset.class);
    private static final String STAFF = "'TEACHER','MANAGERIAL'";
    /** The lessons of one school, as a subquery every lesson-child statement below reuses. */
    private static final String LESSONS = "SELECT id FROM lessons WHERE school_id = ?";
    private static final String KIDS = "SELECT id FROM children WHERE school_id = ?";

    /** One `DELETE`, the table it empties and the statement that does it; `?` is always the school id. */
    private record Step(String table, String sql) {}

    private final QuestProperties props; private final JdbcTemplate jdbc; private final TransactionTemplate tx;
    private final FileStore files;

    public SeedReset(QuestProperties props, JdbcTemplate jdbc, PlatformTransactionManager transactions,
                     FileStore files, Environment environment) {
        this.props = props; this.jdbc = jdbc; this.tx = new TransactionTemplate(transactions); this.files = files;
        if (props.seed() != null) props.seed().profileOrFull();                 // a SEED_PROFILE typo fails the start here
        if (enabled(props) && List.of(environment.getActiveProfiles()).contains("prod"))
            throw new IllegalStateException(ENV + "=true is refused under the `prod` profile: it deletes every "
                    + "school's lessons, children, staff and schools. Unset " + ENV + " and redeploy.");
    }

    private static boolean enabled(QuestProperties props) { return props.seed() != null && props.seed().reset(); }

    @Override public void run(String... args) {
        if (!enabled(props)) return;
        String mark = props.seed().resetMark();
        if (!jdbc.queryForList("SELECT id FROM seed_resets WHERE id = ?", String.class, mark).isEmpty()) {
            log.info("{}=true, but `{}` has already run against this database — nothing deleted. Set {}_TOKEN to a "
                    + "new value to wipe again.", ENV, mark, ENV);
            return;
        }
        var counts = new LinkedHashMap<String, Integer>();
        var blobs = new ArrayList<String>();
        var schools = jdbc.queryForList("SELECT id FROM schools ORDER BY id", String.class);
        for (var schoolId : schools) {
            blobs.addAll(blobsOf(schoolId));
            boolean keep = TenantContext.DEFAULT_SCHOOL.equals(schoolId);
            tx.executeWithoutResult(status -> {
                run(schoolId, contents(), counts);
                if (!keep) run(schoolId, teardown(), counts);
            });
            log.info("seed reset: {} emptied{}", schoolId, keep ? "" : " and deleted");
        }
        tx.executeWithoutResult(status -> counts.merge("parents", jdbc.update("DELETE FROM parents"), Integer::sum));
        counts.forEach((table, rows) -> log.info("seed reset: {} {} row(s) deleted", table, rows));

        // The bucket before the ledger, never after: the ledger is what stops the next start doing any of this
        // again, so a process killed between the two would leave blobs nothing points at and nothing will revisit.
        int gone = 0;
        for (var path : blobs) {
            try { files.delete(path); gone++; } catch (RuntimeException e) { log.warn("seed reset: {} not deleted: {}", path, e.toString()); }
        }
        tx.executeWithoutResult(status -> jdbc.update("INSERT INTO seed_resets (id, ran_at, deleted_json) VALUES (?, ?, ?)",
                mark, Timestamp.from(Instant.now()), json(counts)));
        log.info("seed reset: {} school(s), {} stored file(s) deleted; the default school, the platform admin, "
                + "platform settings, the flag defaults and the permanent caches were kept", schools.size(), gone);
    }

    // ---------------------------------------------------------------- the statements

    /**
     * The school-scoped rows, in the order the foreign keys allow: everything that points at a child or a lesson
     * before the child or the lesson, and the sections after the lessons, children and announcements that name them.
     */
    private static List<Step> contents() {
        return List.of(
                new Step("teacher_question_answers", "DELETE FROM teacher_question_answers WHERE school_id = ?"),
                new Step("teacher_questions", "DELETE FROM teacher_questions WHERE school_id = ?"),
                new Step("announcements", "DELETE FROM announcements WHERE school_id = ?"),
                new Step("child_media", "DELETE FROM child_media WHERE child_id IN (" + KIDS + ")"),
                new Step("attempts", "DELETE FROM attempts WHERE child_id IN (" + KIDS + ")"),
                new Step("stop_completions", "DELETE FROM stop_completions WHERE child_id IN (" + KIDS + ")"),
                new Step("lesson_completions", "DELETE FROM lesson_completions WHERE child_id IN (" + KIDS + ")"),
                new Step("parent_unlocks", "DELETE FROM parent_unlocks WHERE child_id IN (" + KIDS + ")"),
                new Step("stickers", "DELETE FROM stickers WHERE child_id IN (" + KIDS + ")"),
                new Step("streaks", "DELETE FROM streaks WHERE child_id IN (" + KIDS + ")"),
                new Step("children", "DELETE FROM children WHERE school_id = ?"),
                new Step("stops", "DELETE FROM stops WHERE play_id IN (SELECT id FROM plays WHERE lesson_id IN (" + LESSONS + "))"),
                new Step("plays", "DELETE FROM plays WHERE lesson_id IN (" + LESSONS + ")"),
                new Step("parent_panels", "DELETE FROM parent_panels WHERE lesson_id IN (" + LESSONS + ")"),
                new Step("page_images", "DELETE FROM page_images WHERE lesson_id IN (" + LESSONS + ")"),
                new Step("skills", "DELETE FROM skills WHERE lesson_id IN (" + LESSONS + ")"),
                new Step("source_files", "DELETE FROM source_files WHERE lesson_id IN (" + LESSONS + ")"),
                new Step("lesson_steps", "DELETE FROM lesson_steps WHERE lesson_id IN (" + LESSONS + ")"),
                new Step("lessons", "DELETE FROM lessons WHERE school_id = ?"),
                new Step("teaching_assignments", "DELETE FROM teaching_assignments WHERE school_id = ?"),
                new Step("classes", "DELETE FROM classes WHERE school_id = ?"),
                new Step("refresh_tokens", "DELETE FROM refresh_tokens WHERE user_id IN (SELECT id FROM users WHERE school_id = ? AND role IN (" + STAFF + "))"),
                new Step("teachers", "DELETE FROM teachers WHERE user_id IN (SELECT id FROM users WHERE school_id = ? AND role IN (" + STAFF + "))"),
                new Step("users", "DELETE FROM users WHERE school_id = ? AND role IN (" + STAFF + ")"),
                new Step("invites", "DELETE FROM invites WHERE school_id = ?"));
    }

    /** What is left of a school that is not `default` once {@link #contents} has run: the school itself. */
    private static List<Step> teardown() {
        return List.of(
                new Step("refresh_tokens", "DELETE FROM refresh_tokens WHERE user_id IN (SELECT id FROM users WHERE school_id = ?)"),
                new Step("teachers", "DELETE FROM teachers WHERE user_id IN (SELECT id FROM users WHERE school_id = ?)"),
                new Step("users", "DELETE FROM users WHERE school_id = ?"),
                new Step("school_feature_flags", "DELETE FROM school_feature_flags WHERE school_id = ?"),
                new Step("flag_audit", "DELETE FROM flag_audit WHERE school_id = ?"),
                new Step("audit_log", "DELETE FROM audit_log WHERE school_id = ?"),
                new Step("schools", "DELETE FROM schools WHERE id = ?"));
    }

    private void run(String schoolId, List<Step> steps, Map<String, Integer> counts) {
        for (var step : steps) counts.merge(step.table(), jdbc.update(step.sql(), schoolId), Integer::sum);
    }

    /**
     * Every path in the bucket this school's rows point at, read before the rows go: the uploads and the Markdown
     * extracted from them, the rendered page images, and the children's recordings and drawings.
     */
    private List<String> blobsOf(String schoolId) {
        var paths = new ArrayList<String>();
        paths.addAll(jdbc.queryForList("SELECT storage_path FROM source_files WHERE lesson_id IN (" + LESSONS + ")", String.class, schoolId));
        paths.addAll(jdbc.queryForList("SELECT markdown_path FROM source_files WHERE markdown_path IS NOT NULL AND lesson_id IN (" + LESSONS + ")", String.class, schoolId));
        paths.addAll(jdbc.queryForList("SELECT storage_path FROM page_images WHERE lesson_id IN (" + LESSONS + ")", String.class, schoolId));
        paths.addAll(jdbc.queryForList("SELECT storage_path FROM child_media WHERE child_id IN (" + KIDS + ")", String.class, schoolId));
        return paths;
    }

    /** The counts as they are stored in `seed_resets.deleted_json`; every key is a table name and every value a number. */
    private static String json(Map<String, Integer> counts) {
        return counts.entrySet().stream().map(e -> "\"" + e.getKey() + "\":" + e.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }
}
