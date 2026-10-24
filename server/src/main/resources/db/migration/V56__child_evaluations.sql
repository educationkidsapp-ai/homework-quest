-- Feature 33 `teacher evaluation of a child` (docs/features/33-teacher-evaluation-of-child.md): a few times a year
-- the school asks every teacher to evaluate each child she teaches; the leaders of feature 17's board make sure it
-- is done on time and read what was written, and the parent reads the approved ones in plain words.
--
-- 1. The `childEvaluations` switch, seeded **on** — D30 (V54): a switch is a kill switch now, and
--    `FlagAdminTest.every_flag_except_multiSchool_is_on_by_default` is what fails when a new one is seeded off.
--
-- 2. `evaluation_rounds` — the Admin's round: a name in both languages, the day it opens, the deadline as an instant
--    (she enters it in the school's own zone, `SchoolCalendar`) and how many hours before it to remind. `released_at`
--    is the one-way door to the parents: before it no parent sees anything of the round, and it is set once.
--
-- 3. `child_evaluations` — one row per (round, teacher, class, subject, child), which is the unique index, so
--    "save my draft again" is an update of that row rather than a second one. The four criteria are `SMALLINT` 1–4
--    (4 = excellent … 1 = needs support); the parent is never told the number, only the word the server maps it to.
--    `state` is DRAFT / SUBMITTED / RETURNED / APPROVED — the **workflow** the teacher and the leader move through,
--    and deliberately not the compliance status: on time, late and missing are computed from `submitted_at` against
--    the round's deadline (as feature 31's are), so an Admin who moves the deadline re-reads every row rather than
--    leaving old ones asserting a rule nobody holds any more.
--
-- 4. `evaluation_reminders` — what has been sent, keyed (school, round, teacher) and unique, exactly as V53's
--    `material_reminders`: the job inserts the key with ON CONFLICT DO NOTHING and sends only when its insert wrote
--    the row, so a rerun and a second Cloud Run instance send one reminder between them.
--
-- Every table carries `school_id` for the Hibernate `school` filter, and `IsolationTest` has a case for all three.
-- Additive and idempotent: re-running it over QA data adds a switch that is on, three empty tables, and no row
-- twice. Valid on PostgreSQL 16 and on H2 in PostgreSQL mode (the test profile): no partial index, no UUID function.

INSERT INTO feature_flags (flag_key, description, default_on, rollout_stage, created_at)
SELECT 'childEvaluations', 'Teachers evaluate each child they teach, leaders review it and parents read it in words', TRUE, 'internal', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM feature_flags f WHERE f.flag_key = 'childEvaluations');

CREATE TABLE IF NOT EXISTS evaluation_rounds (
    id                   TEXT PRIMARY KEY,
    school_id            TEXT NOT NULL REFERENCES schools(id),
    name_en              TEXT NOT NULL,
    name_ar              TEXT NOT NULL,
    opens_on             DATE NOT NULL,
    due_at               TIMESTAMP NOT NULL,
    remind_hours_before  INTEGER NOT NULL,
    released_at          TIMESTAMP,
    released_by          TEXT,
    created_by           TEXT,
    created_at           TIMESTAMP NOT NULL,
    updated_at           TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS evaluation_rounds_school_due ON evaluation_rounds(school_id, due_at);

CREATE TABLE IF NOT EXISTS child_evaluations (
    id              TEXT PRIMARY KEY,
    school_id       TEXT NOT NULL REFERENCES schools(id),
    round_id        TEXT NOT NULL,
    teacher_id      TEXT NOT NULL,
    class_id        TEXT NOT NULL,
    subject         TEXT NOT NULL,
    child_id        TEXT NOT NULL,
    participation   SMALLINT,
    effort          SMALLINT,
    behaviour       SMALLINT,
    understanding   SMALLINT,
    comment         TEXT,
    going_well      TEXT,
    next_step       TEXT,
    state           TEXT NOT NULL,
    submitted_at    TIMESTAMP,
    reviewed_by     TEXT,
    reviewed_at     TIMESTAMP,
    review_comment  TEXT,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS child_evaluations_once
    ON child_evaluations(school_id, round_id, teacher_id, class_id, subject, child_id);
CREATE INDEX IF NOT EXISTS child_evaluations_round ON child_evaluations(school_id, round_id);
CREATE INDEX IF NOT EXISTS child_evaluations_child ON child_evaluations(child_id, state);

CREATE TABLE IF NOT EXISTS evaluation_reminders (
    id          TEXT PRIMARY KEY,
    school_id   TEXT NOT NULL REFERENCES schools(id),
    round_id    TEXT NOT NULL,
    teacher_id  TEXT NOT NULL,
    sent_at     TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS evaluation_reminders_once ON evaluation_reminders(school_id, round_id, teacher_id);
