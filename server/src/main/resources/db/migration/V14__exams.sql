-- N4.3 `backend/exams` — `docs/teacher-flow.md` step 10 and the teacher prompt §8.
--
-- An exam is a lesson with `type = 'exam'`; that column has existed since V7 and nothing here changes a lesson.
-- Two additive tables carry what a lesson has no room for, both idempotent for the QA data already in place.
--
-- 1. `exam_settings` — §8's `ExamSettings(lessonId, opensAt, closesAt, level, hintsOff, releaseMode)`, one row per
--    exam lesson, keyed by the lesson so a copy of an exam into a sibling class is a different exam with a window
--    of its own rather than a second view of the same one.
--
--    `level` is text rather than an integer because `mixed` is one of its four values and a nullable integer would
--    say "no level yet" instead. `single_attempt` and `hints_off` are §8's rules rather than the teacher's choices
--    and are stored so that a school given a looser exam later does not need a migration to express it.
--
--    Deliberately no `released_at` here: release is a lesson-level fact and V13 already put `lessons.released_at`
--    and `lessons.release_withdrawn` there for homework. An exam releases through exactly the same two columns, so
--    the parent's report has one rule to read rather than two.
--
-- 2. `exam_attempts` — §8's one attempt per child per exam, and the only row an exam needs beyond the lesson's own
--    attempts: when she started, when she was last seen, when she submitted, and whether the teacher has re-opened
--    it for her. The unique index on (child, exam) is what makes the second start a 409 rather than a race.
--
--    There is no `exam_results` table. §7's score is a pure function of the attempts, the stops and the teacher's
--    marks (`grading/Scoring.java` says why at length), and an exam is scored by exactly that function; a stored
--    copy would need a hook on the upload path, a backfill, and a way to notice that a mark changed. What is
--    genuinely new — start, submit, time taken, re-opened — is what this table holds.
--
-- `school_id` is on both rows so the Hibernate `school` filter scopes them like every other tenant table; it is the
-- lesson's school, written by the service, and never taken from a request.

CREATE TABLE IF NOT EXISTS exam_settings (
    lesson_id        TEXT PRIMARY KEY,
    school_id        TEXT NOT NULL,
    opens_at         TIMESTAMP NOT NULL,
    closes_at        TIMESTAMP NOT NULL,
    level            TEXT NOT NULL DEFAULT 'mixed',
    duration_minutes INTEGER,
    single_attempt   BOOLEAN NOT NULL DEFAULT TRUE,
    hints_off        BOOLEAN NOT NULL DEFAULT TRUE,
    numbers_off      BOOLEAN NOT NULL DEFAULT TRUE,
    release_mode     TEXT NOT NULL DEFAULT 'auto_on_close',
    created_at       TIMESTAMP NOT NULL,
    updated_at       TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS exam_settings_school ON exam_settings(school_id);
-- The auto-release sweep asks one question a minute: which windows have closed since I last looked.
CREATE INDEX IF NOT EXISTS exam_settings_closes_at ON exam_settings(closes_at);

CREATE TABLE IF NOT EXISTS exam_attempts (
    id               TEXT PRIMARY KEY,
    school_id        TEXT NOT NULL,
    lesson_id        TEXT NOT NULL,
    child_id         TEXT NOT NULL,
    state            TEXT NOT NULL DEFAULT 'started',
    started_at       TIMESTAMP NOT NULL,
    last_seen_at     TIMESTAMP NOT NULL,
    submitted_at     TIMESTAMP,
    seconds_taken    INTEGER,
    reopened_at      TIMESTAMP,
    reopened_by      TEXT,
    reopen_closes_at TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS exam_attempts_child_lesson ON exam_attempts(child_id, lesson_id);
CREATE INDEX IF NOT EXISTS exam_attempts_lesson ON exam_attempts(lesson_id);
CREATE INDEX IF NOT EXISTS exam_attempts_school ON exam_attempts(school_id);

-- The `exams` flag this package's routes sit behind was seeded off by V7 and is left exactly as it is: a flag
-- default is an Admin decision, not a migration's, and `PUT /admin/schools/{id}/flags` is how a school turns it on.
